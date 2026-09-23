/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, Yoran Vulker  (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                        (Kotlin port)
 *
 *  Holds the per-connection auth + cipher state. The Java original kept this
 *  as long-lived mutable fields on `XiaomiAuthService`; we wrap it in a
 *  data class so the BLE layer can hand it off cleanly between coroutines.
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.kidneyweakx.miband9active.xiaomi.auth

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * One instance per handshake: Gadgetbridge's startEncryptedHandshake()
 * regenerates the phone nonce every time a session config arrives, so the
 * driver creates a fresh [XiaomiAuthSession] for every link-up / handshake.
 */
class XiaomiAuthSession(val secretKey16: ByteArray) {
    init {
        require(secretKey16.size == 16) { "secretKey must be 16 bytes" }
    }

    val phoneNonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private var encryptionKey: ByteArray = ByteArray(16)
    private var decryptionKey: ByteArray = ByteArray(16)
    private var encryptionNonce: ByteArray = ByteArray(4)
    private var decryptionNonce: ByteArray = ByteArray(4)
    private val counter = AtomicInteger(0)

    @Volatile
    var encryptionInitialised: Boolean = false
        private set

    fun installWatchNonce(watchNonce16: ByteArray, watchHmac: ByteArray): Boolean {
        val derived = XiaomiCrypto.deriveSession(secretKey16, phoneNonce, watchNonce16)
        decryptionKey = derived.copyOfRange(0, 16)
        encryptionKey = derived.copyOfRange(16, 32)
        decryptionNonce = derived.copyOfRange(32, 36)
        encryptionNonce = derived.copyOfRange(36, 40)

        if (!XiaomiCrypto.verifyWatchHmac(decryptionKey, watchNonce16, phoneNonce, watchHmac)) {
            return false
        }
        encryptionInitialised = true
        return true
    }

    /** HMAC the watch nonce so the band can verify *us*. */
    fun phoneAck(watchNonce16: ByteArray): ByteArray =
        XiaomiCrypto.hmacSha256(encryptionKey, phoneNonce + watchNonce16)

    fun encryptV1(payload: ByteArray): ByteArray {
        check(encryptionInitialised) { "session not authenticated yet" }
        val i = counter.getAndIncrement()
        return XiaomiCrypto.encryptV1(encryptionKey, encryptionNonce, i, payload)
    }

    /**
     * AES-CCM with an explicit packet counter. XiaomiAuthService.handleWatchNonce
     * encrypts the AuthDeviceInfo with `encrypt(bytes, 0)` — always counter 0.
     */
    fun encryptV1(payload: ByteArray, packetCounter: Int): ByteArray {
        check(encryptionInitialised) { "session not authenticated yet" }
        return XiaomiCrypto.encryptV1(encryptionKey, encryptionNonce, packetCounter, payload)
    }

    fun decryptV1(ciphertext: ByteArray, checkMac: Boolean = true): ByteArray {
        check(encryptionInitialised) { "session not authenticated yet" }
        return XiaomiCrypto.decryptV1(decryptionKey, decryptionNonce, ciphertext, checkMac)
    }

    /** V2 (AES-CTR) encryption. Refuses to run before keys are derived — an all-zero key would leak garbage to the band. */
    fun encryptV2(payload: ByteArray): ByteArray {
        check(encryptionInitialised) { "V2 encryption requested before auth keys were derived" }
        return XiaomiCrypto.encryptV2(encryptionKey, payload)
    }

    fun decryptV2(ciphertext: ByteArray): ByteArray = XiaomiCrypto.decryptV2(decryptionKey, ciphertext)
}
