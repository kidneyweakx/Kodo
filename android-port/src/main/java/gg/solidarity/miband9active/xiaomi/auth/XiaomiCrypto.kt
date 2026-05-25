/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, Yoran Vulker  (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                        (Kotlin port)
 *
 *  Pure crypto primitives ported from XiaomiAuthService.java.
 *  No Android-framework deps — only javax.crypto + bouncycastle for AES-CCM.
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package gg.solidarity.miband9active.xiaomi.auth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

object XiaomiCrypto {

    private val MIWEAR_AUTH = "miwear-auth".toByteArray(Charsets.UTF_8)

    /**
     * Parse the user-pasted auth key into 16 bytes.
     *   - bare hex (32 chars)
     *   - "0x"-prefixed hex (34 chars)
     *   - numeric user-id (plaintext fallback) — returns null so caller uses
     *     the clear-text handshake path.
     */
    fun parseAuthKey(raw: String): ByteArray? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val hex = if (trimmed.startsWith("0x")) trimmed.substring(2) else trimmed
        if (hex.length != 32) return null
        return runCatching { hex.hexToBytes() }.getOrNull()
    }

    /**
     * Build the 64-byte derived material used to seed the encrypted session.
     * Layout: bytes 0..15 = decryptionKey, 16..31 = encryptionKey,
     *         32..35 = decryptionNonce, 36..39 = encryptionNonce.
     */
    fun deriveSession(secretKey16: ByteArray, phoneNonce16: ByteArray, watchNonce16: ByteArray): ByteArray {
        require(secretKey16.size == 16) { "secretKey must be 16 bytes" }
        require(phoneNonce16.size == 16) { "phoneNonce must be 16 bytes" }
        require(watchNonce16.size == 16) { "watchNonce must be 16 bytes" }

        // Step A: HMAC(phone||watch, secret) → intermediate key.
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(phoneNonce16 + watchNonce16, "HmacSHA256"))
        }
        val intermediate = mac.doFinal(secretKey16)

        // Step B: re-init mac with the intermediate, then expand via the
        // "miwear-auth" counter-mode KDF until we have 64 bytes.
        mac.init(SecretKeySpec(intermediate, "HmacSHA256"))
        val out = ByteArray(64)
        var tmp = ByteArray(0)
        var counter: Byte = 1
        var written = 0
        while (written < out.size) {
            mac.update(tmp)
            mac.update(MIWEAR_AUTH)
            mac.update(counter)
            tmp = mac.doFinal()
            val take = minOf(tmp.size, out.size - written)
            System.arraycopy(tmp, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }

    /** Confirms the watch hmac matches our derived decryption key. */
    fun verifyWatchHmac(decryptionKey16: ByteArray, watchNonce16: ByteArray, phoneNonce16: ByteArray, watchHmac: ByteArray): Boolean {
        val expected = hmacSha256(decryptionKey16, watchNonce16 + phoneNonce16)
        return expected.contentEquals(watchHmac)
    }

    fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(input)

    /** Encrypted-session V1 frame: 12-byte CCM nonce = (4-byte session prefix, 4 zero, 4-byte little-endian counter). */
    fun encryptV1(encryptionKey16: ByteArray, encryptionNonce4: ByteArray, counter: Int, payload: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(encryptionNonce4)
            .putInt(0)
            .putInt(counter)
            .array()
        return ccm(forEncrypt = true, key = encryptionKey16, nonce = nonce, payload = payload, macBits = 32)
    }

    fun decryptV1(decryptionKey16: ByteArray, decryptionNonce4: ByteArray, ciphertext: ByteArray, checkMac: Boolean = true): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(decryptionNonce4)
            .putInt(0)
            .putInt(0)
            .array()
        val macBits = if (checkMac) 32 else 0
        val effective = if (checkMac) ciphertext else ciphertext.copyOfRange(0, ciphertext.size - 4)
        return ccm(forEncrypt = false, key = decryptionKey16, nonce = nonce, payload = effective, macBits = macBits)
    }

    /** V2 transport uses AES/CTR with key reused as IV (legacy quirk). */
    fun encryptV2(encryptionKey16: ByteArray, payload: ByteArray): ByteArray = ctr(Cipher.ENCRYPT_MODE, encryptionKey16, encryptionKey16, payload)

    fun decryptV2(decryptionKey16: ByteArray, ciphertext: ByteArray): ByteArray = ctr(Cipher.DECRYPT_MODE, decryptionKey16, decryptionKey16, ciphertext)

    // -------------------------------------------------------------------- internals

    private fun ccm(forEncrypt: Boolean, key: ByteArray, nonce: ByteArray, payload: ByteArray, macBits: Int): ByteArray {
        val engine = AESEngine().apply { init(forEncrypt, KeyParameter(key)) }
        val cipher = CCMBlockCipher(engine).apply {
            init(forEncrypt, AEADParameters(KeyParameter(key), macBits, nonce, null))
        }
        val out = ByteArray(cipher.getOutputSize(payload.size))
        val written = cipher.processBytes(payload, 0, payload.size, out, 0)
        cipher.doFinal(out, written)
        return out
    }

    private fun ctr(op: Int, key: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(op, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(payload)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "hex length must be even" }
        return ByteArray(length / 2) { i ->
            ((this[2 * i].digitToInt(16) shl 4) or this[2 * i + 1].digitToInt(16)).toByte()
        }
    }
}
