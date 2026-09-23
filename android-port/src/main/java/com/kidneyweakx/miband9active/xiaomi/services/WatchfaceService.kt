/*  Copyright (C) 2023-2024 José Rebelo  (Gadgetbridge XiaomiWatchfaceService, XiaomiFWHelper)
 *  Copyright (C) 2026 kidneyweakx       (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Command type 4: 0 list, 1 set active, 2 delete, 4 install.
 *  Install: 4/4 WatchfaceInstallStart { id, size } -> band installStatus
 *  (0 = go ahead) -> DATA upload TYPE_WATCHFACE (16) -> set active -> re-list.
 *  The id is parsed from the file like XiaomiFWHelper.parseAsWatchface():
 *  magic 0x5A 0xA5, numeric id NUL-terminated at 0x28, name at 0x68
 *  (0xFFFFFFFF there = localized table; we don't decode it -> "").
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.net.Uri
import android.util.Log
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.DriverHolder
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.json.JSONArray
import org.json.JSONObject

object WatchfaceService {
    private const val TAG = "MB9A_Watchface"
    private const val KEY_LIST = "watchface_list"
    /** XiaomiFWHelper: 128 MiB guard. */
    private const val MAX_FILE_SIZE = 1024 * 1024 * 128

    data class Face(val id: String, val name: String, val canDelete: Boolean, val active: Boolean)
    data class FileInfo(val id: String, val name: String, val bytes: ByteArray)

    fun cachedList(): List<Face> {
        val arr = FeatureStore.getJsonArray(KEY_LIST) ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Face(o.optString("id"), o.optString("name"), o.optBoolean("del"), o.optBoolean("active"))
        }
    }

    suspend fun list(): List<Face> {
        val reply = BandChannel.request(WatchfaceCommands.COMMAND_TYPE, WatchfaceCommands.CMD_WATCHFACE_LIST, timeoutMs = 8_000)
            ?: throw IllegalStateException(if (BandChannel.isConnected) "Band did not answer the watchface list request" else "Band not connected")
        if (reply.hasWatchface()) onList(reply.watchface.watchfaceList)
        return cachedList()
    }

    private fun onList(list: XiaomiProto.WatchfaceList) {
        val arr = JSONArray()
        list.watchfaceList.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("del", it.canDelete).put("active", it.active))
        }
        FeatureStore.putJsonArray(KEY_LIST, arr)
    }

    suspend fun setActive(id: String) {
        val ok = BandChannel.send(WatchfaceCommands.COMMAND_TYPE, WatchfaceCommands.CMD_WATCHFACE_SET) {
            setWatchface(XiaomiProto.Watchface.newBuilder().setWatchfaceId(id))
        }
        if (!ok) throw IllegalStateException("Band not connected")
    }

    /** XiaomiWatchfaceService.deleteWatchface(): refuses non-user, unknown and active faces. */
    suspend fun delete(id: String) {
        val known = cachedList().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Refusing to delete unknown watchface $id")
        if (!known.canDelete) throw IllegalArgumentException("Refusing to delete non-user watchface $id")
        if (known.active) throw IllegalArgumentException("Refusing to delete active watchface $id")
        val ok = BandChannel.send(WatchfaceCommands.COMMAND_TYPE, WatchfaceCommands.CMD_WATCHFACE_DELETE) {
            setWatchface(XiaomiProto.Watchface.newBuilder().setWatchfaceId(id))
        }
        if (!ok) throw IllegalStateException("Band not connected")
        // upstream re-requests the list after the delete ack; our list() waits for it
        runCatching { list() }
    }

    /** Read + validate a watchface file. */
    suspend fun inspect(uri: String): FileInfo = withContext(Dispatchers.IO) {
        val bytes = readAll(uri)
        parse(bytes) ?: throw IllegalArgumentException("Not a Xiaomi watchface file")
    }

    suspend fun install(uri: String, onProgress: (Int) -> Unit): FileInfo {
        val info = inspect(uri)
        val drv = DriverHolder.current ?: throw IllegalStateException("Band not connected")
        val reply = BandChannel.request(
            XiaomiProto.Command.newBuilder()
                .setType(WatchfaceCommands.COMMAND_TYPE)
                .setSubtype(WatchfaceCommands.CMD_WATCHFACE_INSTALL)
                .setWatchface(
                    XiaomiProto.Watchface.newBuilder().setWatchfaceInstallStart(
                        XiaomiProto.WatchfaceInstallStart.newBuilder().setId(info.id).setSize(info.bytes.size),
                    ),
                )
                .build(),
            timeoutMs = 10_000,
        ) { it.type == WatchfaceCommands.COMMAND_TYPE && it.subtype == WatchfaceCommands.CMD_WATCHFACE_INSTALL }
            ?: throw IllegalStateException("Band did not answer the install request")
        val status = reply.watchface.installStatus
        if (status != 0) {
            // proto: 0 not installed (go), 2 already installed
            throw IllegalStateException("Band rejected watchface ${info.id} (installStatus=$status)")
        }
        Log.i(TAG, "installing watchface ${info.id} (${info.bytes.size} B)")
        val ok = MiBand9DataUploader.upload(drv, MiBand9DataUploader.TYPE_WATCHFACE, info.bytes, onProgress)
        if (!ok) throw IllegalStateException("Watchface upload failed")
        setActive(info.id)
        runCatching { list() }
        return info
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            WatchfaceCommands.CMD_WATCHFACE_LIST -> if (cmd.hasWatchface()) onList(cmd.watchface.watchfaceList)
            WatchfaceCommands.CMD_WATCHFACE_SET -> Log.d(TAG, "watchface set ack=${cmd.watchface.ack}")
            WatchfaceCommands.CMD_WATCHFACE_DELETE -> Log.d(TAG, "watchface delete ack=${cmd.watchface.ack}")
            else -> Unit
        }
    }

    // ------------------------------------------------------------ file handling

    private fun readAll(uri: String): ByteArray {
        val ctx = AppContext.context
        val stream: InputStream = when {
            uri.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(uri))
                ?: throw IllegalArgumentException("Cannot open $uri")
            uri.startsWith("file://") -> File(Uri.parse(uri).path ?: throw IllegalArgumentException("Bad uri $uri")).inputStream()
            else -> File(uri).inputStream()
        }
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FILE_SIZE) throw IllegalArgumentException("File larger than the maximum expected size")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    /** XiaomiFWHelper.parseAsWatchface() */
    fun parse(fw: ByteArray): FileInfo? {
        if (fw.size < 0x70 || fw[0] != 0x5A.toByte() || fw[1] != 0xA5.toByte()) return null
        val id = untilNullTerminator(fw, 0x28) ?: return null
        if (!Regex("^\\d+$").matches(id)) return null
        val localized = fw[0x68] == 0xFF.toByte() && fw[0x69] == 0xFF.toByte() &&
            fw[0x6A] == 0xFF.toByte() && fw[0x6B] == 0xFF.toByte()
        val name = if (localized) "" else (untilNullTerminator(fw, 0x68) ?: return null)
        return FileInfo(id, name, fw)
    }

    private fun untilNullTerminator(bytes: ByteArray, start: Int): String? {
        for (i in start until bytes.size) {
            if (bytes[i] == 0.toByte()) return String(bytes, start, i - start, Charsets.UTF_8)
        }
        return null
    }
}
