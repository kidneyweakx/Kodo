/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.services.SystemCommands
import com.margelo.nitro.core.Promise
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridCameraRemote : HybridHybridCameraRemoteSpec() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var watchJob: Job? = null
    @Volatile private var _armed = false

    override val armed: Boolean get() = _armed

    override fun arm(): Promise<Unit> = Promise.async {
        val drv = DriverHolder.current ?: run { _armed = true; return@async }
        drv.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(SystemCommands.COMMAND_TYPE)
                .setSubtype(SystemCommands.CMD_CAMERA_REMOTE_SET)
                .setSystem(
                    XiaomiProto.System.newBuilder().setCamera(
                        XiaomiProto.Camera.newBuilder().setEnabled(true),
                    ),
                )
                .build(),
        )
        _armed = true
        startWatch()
    }

    override fun disarm(): Promise<Unit> = Promise.async {
        val drv = DriverHolder.current
        drv?.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(SystemCommands.COMMAND_TYPE)
                .setSubtype(SystemCommands.CMD_CAMERA_REMOTE_SET)
                .setSystem(
                    XiaomiProto.System.newBuilder().setCamera(
                        XiaomiProto.Camera.newBuilder().setEnabled(false),
                    ),
                )
                .build(),
        )
        _armed = false
        watchJob?.cancel(); watchJob = null
    }

    override fun onShutter(listener: () -> Unit): () -> Unit {
        listeners += listener
        if (_armed) startWatch()
        return { listeners -= listener }
    }

    private fun startWatch() {
        if (watchJob != null) return
        val drv = DriverHolder.current ?: return
        watchJob = scope.launch {
            drv.incoming.collect { msg ->
                // Band-side shutter press comes back as an unsolicited
                // system.camera message while we are armed. We don't have a
                // dedicated `shutterPressed` field in the proto schema, so
                // any system-camera command while armed is treated as a press.
                if (_armed &&
                    msg.type == SystemCommands.COMMAND_TYPE &&
                    msg.command.hasSystem() &&
                    msg.command.system.hasCamera()
                ) {
                    listeners.forEach { it() }
                }
            }
        }
    }
}
