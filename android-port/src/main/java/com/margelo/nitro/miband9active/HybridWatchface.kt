/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.services.MiBand9DataUploader
import com.margelo.nitro.core.Promise
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridWatchface : HybridHybridWatchfaceSpec() {

    companion object {
        const val COMMAND_TYPE = 4
        const val CMD_WATCHFACE_LIST = 0
        const val CMD_WATCHFACE_SET = 1
        const val CMD_WATCHFACE_DELETE = 2
        const val CMD_WATCHFACE_INSTALL = 4
    }

    private val progressListeners = CopyOnWriteArrayList<(Double) -> Unit>()

    override fun list(): Promise<Array<WatchfaceInfo>> = Promise.async {
        val drv = DriverHolder.current ?: return@async emptyArray<WatchfaceInfo>()
        drv.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_WATCHFACE_LIST)
                .build(),
        )
        val reply = withTimeoutOrNull(8_000) {
            drv.incoming.first { it.type == COMMAND_TYPE && it.subtype == CMD_WATCHFACE_LIST }
        } ?: return@async emptyArray<WatchfaceInfo>()
        val list = reply.command.watchface.watchfaceList
        (0 until list.watchfaceCount).map { i ->
            val w = list.getWatchface(i)
            WatchfaceInfo(
                id = w.id,
                name = w.name,
                canDelete = w.canDelete,
                active = w.active,
            )
        }.toTypedArray()
    }

    override fun install(localFilePath: String, watchfaceId: String): Promise<Double> = Promise.async {
        val drv = DriverHolder.current ?: throw IllegalStateException("Band not connected")
        val bytes = File(localFilePath).readBytes()
        if (bytes.isEmpty()) throw IllegalArgumentException("Watchface file is empty: $localFilePath")

        // 1) Ask the band to begin a watchface install of this id+size.
        drv.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_WATCHFACE_INSTALL)
                .setWatchface(
                    XiaomiProto.Watchface.newBuilder().setWatchfaceInstallStart(
                        XiaomiProto.WatchfaceInstallStart.newBuilder()
                            .setId(watchfaceId)
                            .setSize(bytes.size),
                    ),
                )
                .build(),
        )

        // 2) Band replies with installStatus == 0 → start the data upload.
        val replyOk = withTimeoutOrNull(10_000) {
            drv.incoming.first { it.type == COMMAND_TYPE && it.subtype == CMD_WATCHFACE_INSTALL }
        }
        if (replyOk == null || replyOk.command.watchface.installStatus != 0) {
            throw IllegalStateException("Band rejected watchface install (status=${replyOk?.command?.watchface?.installStatus})")
        }

        // 3) Upload the bytes on the data channel.
        val ok = MiBand9DataUploader.upload(
            drv,
            MiBand9DataUploader.TYPE_WATCHFACE,
            bytes,
        ) { p -> progressListeners.forEach { it(p.toDouble()) } }
        if (!ok) throw IllegalStateException("Watchface upload failed")

        // 4) Activate the new face.
        drv.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_WATCHFACE_SET)
                .setWatchface(
                    XiaomiProto.Watchface.newBuilder().setWatchfaceId(watchfaceId),
                )
                .build(),
        )
        bytes.size.toDouble()
    }

    override fun setActive(watchfaceId: String): Promise<Unit> = Promise.async {
        val drv = DriverHolder.current ?: return@async
        drv.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_WATCHFACE_SET)
                .setWatchface(XiaomiProto.Watchface.newBuilder().setWatchfaceId(watchfaceId))
                .build(),
        )
    }

    override fun delete(watchfaceId: String): Promise<Unit> = Promise.async {
        val drv = DriverHolder.current ?: return@async
        drv.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE)
                .setSubtype(CMD_WATCHFACE_DELETE)
                .setWatchface(XiaomiProto.Watchface.newBuilder().setWatchfaceId(watchfaceId))
                .build(),
        )
    }

    override fun onInstallProgress(listener: (percent: Double) -> Unit): () -> Unit {
        progressListeners += listener
        return { progressListeners -= listener }
    }

    @Suppress("unused")
    private val keepContextAlive: android.content.Context = AppContext.context
}
