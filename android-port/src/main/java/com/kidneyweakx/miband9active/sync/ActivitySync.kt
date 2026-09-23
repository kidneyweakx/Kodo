/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker                         (Gadgetbridge)
 *
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * Activity-file sync, translated from the request/queue half of
 * XiaomiActivityFileFetcher.java + XiaomiHealthService.java
 * (fetchRecordedDataToday / fetchRecordedDataPast / requestRecordedData /
 * ackRecordedData / handleActivityFetchResponse), Gadgetbridge 75f923904f:
 *
 *   1. (8,1) TODAY → band answers (8,1) with N × 7-byte file ids.
 *   2. Queue them (PriorityQueue, XiaomiActivityFileId.compareTo = timestamp,
 *      then summary-before-details-before-gps), start fetching, and send
 *      (8,2) PAST; its id list is merged into the queue when it arrives
 *      (upstream holds the "finished" signal until then — 00c0a2ba5c).
 *   3. ONE file at a time: (8,3) with the file id → ACTIVITY-channel chunks
 *      until num == total → CRC-32 → parse → persist.
 *   4. (8,5) ack ONLY if the file parsed and persisted. (Upstream acks right
 *      after the CRC check, before parsing; we keep unparsed files on the
 *      band so a later parser version can still read them.)
 *
 * Timeouts: 5 s without a chunk (upstream #4305), 60 s per file, 10 s per
 * id-list response, 180 s total. A file whose parse fails 3 times is no
 * longer requested (it stays on the band).
 */
package com.kidneyweakx.miband9active.sync

import android.os.SystemClock
import android.util.Log
import com.google.protobuf.ByteString
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.SampleStore
import com.kidneyweakx.miband9active.xiaomi.activity.ActivityCommands
import com.kidneyweakx.miband9active.xiaomi.activity.ActivityFileContent
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileFetcher
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityParser
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

object ActivitySync {
    private const val TAG = "MB9A_ActivitySync"

    private const val TOTAL_TIMEOUT_MS = 180_000L
    private const val LIST_TIMEOUT_MS = 10_000L
    private const val CHUNK_TIMEOUT_MS = 5_000L
    private const val FILE_TIMEOUT_MS = 60_000L
    private const val MAX_PARSE_FAILURES = 3

    const val PHASE_LISTING = "health"
    const val PHASE_HEALTH = "health"
    const val PHASE_SLEEP = "sleep"
    const val PHASE_WORKOUTS = "workouts"

    private val inFlight = AtomicReference<CompletableDeferred<Int>?>(null)

    /**
     * Fetch, parse, persist and ack every pending activity file.
     *
     * A concurrent second call does not start another exchange: it waits for
     * the running one and returns its result (0 if that one was cancelled).
     *
     * @return number of files parsed and persisted.
     */
    suspend fun run(
        driver: MiBand9BleDriver,
        onProgress: (phase: String, progress: Double) -> Unit,
    ): Int {
        val mine = CompletableDeferred<Int>()
        while (true) {
            val existing = inFlight.get()
            if (existing != null) {
                Log.i(TAG, "sync already running; awaiting it")
                return try {
                    existing.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    0
                }
            }
            if (inFlight.compareAndSet(null, mine)) break
        }
        try {
            val n = withContext(Dispatchers.IO) { doRun(driver, onProgress) }
            mine.complete(n)
            return n
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            inFlight.compareAndSet(mine, null)
        }
    }

    private class IdList(val subtype: Int, val bytes: ByteArray)

    private suspend fun doRun(
        driver: MiBand9BleDriver,
        onProgress: (phase: String, progress: Double) -> Unit,
    ): Int = coroutineScope {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val idLists = Channel<IdList>(Channel.UNLIMITED)

        // Subscribe BEFORE sending anything (UNDISPATCHED runs up to the
        // SharedFlow subscription synchronously).
        val chunkJob = launch(start = CoroutineStart.UNDISPATCHED) {
            driver.activityChunks.collect { chunks.trySend(it) }
        }
        val cmdJob = launch(start = CoroutineStart.UNDISPATCHED) {
            driver.incoming.collect { msg ->
                if (msg.type == ActivityCommands.COMMAND_TYPE &&
                    (msg.subtype == ActivityCommands.CMD_ACTIVITY_FETCH_TODAY ||
                        msg.subtype == ActivityCommands.CMD_ACTIVITY_FETCH_PAST)
                ) {
                    idLists.trySend(IdList(msg.subtype, msg.command.health.activityRequestFileIds.toByteArray()))
                }
            }
        }

        var parsed = 0
        try {
            val finished = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                parsed = syncLoop(driver, chunks, idLists, onProgress) { parsed = it }
            }
            if (finished == null) Log.w(TAG, "total sync time exceeded; stopping after $parsed files")
        } finally {
            chunkJob.cancel()
            cmdJob.cancel()
            chunks.close()
            idLists.close()
        }
        onProgress(PHASE_WORKOUTS, 1.0)
        Log.i(TAG, "activity sync finished: $parsed files parsed")
        parsed
    }

    private suspend fun syncLoop(
        driver: MiBand9BleDriver,
        chunks: Channel<ByteArray>,
        idLists: Channel<IdList>,
        onProgress: (phase: String, progress: Double) -> Unit,
        onParsedCount: (Int) -> Unit,
    ): Int {
        val queue = PriorityQueue<XiaomiActivityFileId>()
        // Every id seen this run (queued or already fetched): the PAST list can
        // repeat ids from TODAY after we've fetched them (#4305).
        val seen = HashSet<XiaomiActivityFileId>()
        var pastPending = true
        var parsed = 0
        var done = 0

        fun handle(list: IdList) {
            if (list.subtype == ActivityCommands.CMD_ACTIVITY_FETCH_PAST) pastPending = false
            enqueue(queue, seen, list.bytes)
        }

        onProgress(PHASE_LISTING, 0.02)
        sendFetchToday(driver)
        val gotToday = withTimeoutOrNull(LIST_TIMEOUT_MS) {
            while (true) {
                val l = idLists.receive()
                handle(l)
                if (l.subtype == ActivityCommands.CMD_ACTIVITY_FETCH_TODAY) break
            }
        }
        if (gotToday == null) Log.w(TAG, "no response to FETCH_TODAY within ${LIST_TIMEOUT_MS}ms")

        // Upstream requests the past list right after today's response and
        // keeps fetching meanwhile.
        if (pastPending) sendFetchPast(driver)

        val assembler = XiaomiActivityFileFetcher()
        while (true) {
            while (true) {
                val l = idLists.tryReceive().getOrNull() ?: break
                handle(l)
            }

            val fileId = queue.poll()
            if (fileId == null) {
                if (!pastPending) break
                val l = withTimeoutOrNull(LIST_TIMEOUT_MS) { idLists.receive() }
                if (l == null) {
                    Log.w(TAG, "no response to FETCH_PAST within ${LIST_TIMEOUT_MS}ms")
                    break
                }
                handle(l)
                continue
            }

            val remaining = queue.size + 1
            onProgress(phaseFor(fileId), 0.05 + 0.9 * done / (done + remaining).toDouble())
            done++

            // Drop anything left over from a previous (timed-out) file.
            while (chunks.tryReceive().isSuccess) Unit
            assembler.reset()

            Log.d(TAG, "requesting $fileId")
            sendRequest(driver, fileId)
            val file = receiveFile(chunks, assembler)
            if (file == null) {
                Log.w(TAG, "timed out / invalid data for $fileId; not acking")
                continue
            }
            if (file.fileId != fileId) {
                Log.w(TAG, "band sent ${file.fileId} while ${fileId} was requested")
            }

            dumpRaw(file.fileId, file.data)

            val content = XiaomiActivityParser.parse(file.fileId, file.data)
            val ok = content != null && persist(content)
            if (ok) {
                SampleStore.recordFileParsed(file.fileId)
                sendAck(driver, file.fileId)
                parsed++
                onParsedCount(parsed)
                Log.i(TAG, "parsed + acked ${file.fileId}")
            } else {
                runCatching { SampleStore.recordFileParseFailure(file.fileId) }
                Log.w(TAG, "failed to parse ${file.fileId}; left on band")
            }
        }
        return parsed
    }

    private fun enqueue(
        queue: PriorityQueue<XiaomiActivityFileId>,
        seen: MutableSet<XiaomiActivityFileId>,
        bytes: ByteArray,
    ) {
        if (bytes.size % 7 != 0) {
            Log.w(TAG, "file id list of ${bytes.size} bytes is not a multiple of 7; ignoring")
            return
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 7) {
            val id = XiaomiActivityFileId.from(buf)
            if (id.timestamp.time == 0L && id.version == 0) {
                Log.w(TAG, "skipping invalid file id with no timestamp and version")
                continue
            }
            if (!seen.add(id)) {
                Log.w(TAG, "ignoring duplicated file $id")
                continue
            }
            val failures = runCatching { SampleStore.parseFailures(id) }.getOrDefault(0)
            if (failures >= MAX_PARSE_FAILURES) {
                Log.w(TAG, "skipping $id: failed to parse $failures times")
                continue
            }
            queue.add(id)
        }
    }

    private suspend fun receiveFile(
        chunks: Channel<ByteArray>,
        assembler: XiaomiActivityFileFetcher,
    ): XiaomiActivityFileFetcher.ChunkResult.Complete? {
        val deadline = SystemClock.elapsedRealtime() + FILE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val chunk = withTimeoutOrNull(CHUNK_TIMEOUT_MS) { chunks.receive() } ?: return null
            when (val r = assembler.assemble(chunk)) {
                is XiaomiActivityFileFetcher.ChunkResult.Partial -> Unit
                is XiaomiActivityFileFetcher.ChunkResult.Complete -> return r
                is XiaomiActivityFileFetcher.ChunkResult.Invalid -> {
                    Log.w(TAG, "activity file rejected: ${r.reason}")
                    return null
                }
            }
        }
        return null
    }

    /** Persist parsed content. Returns true only when fully stored. */
    private fun persist(content: ActivityFileContent): Boolean = try {
        when (content) {
            is ActivityFileContent.DailyDetails -> {
                SampleStore.upsertActivitySamples(content.samples)
                true
            }
            is ActivityFileContent.DailySummary -> {
                SampleStore.upsertDailySummary(content.summary)
                true
            }
            is ActivityFileContent.Sleep -> {
                SampleStore.upsertSleep(content.sessions, content.stages)
                content.complete
            }
            is ActivityFileContent.ManualSamples -> {
                SampleStore.upsertManualSamples(content.samples)
                true
            }
            is ActivityFileContent.WorkoutSummary -> {
                SampleStore.upsertWorkout(content.fileId, content.fields, content.raw)
                true
            }
            is ActivityFileContent.WorkoutGps -> {
                SampleStore.upsertWorkoutGps(content.fileId, content.points)
                true
            }
            is ActivityFileContent.Empty -> {
                Log.i(TAG, "${content.fileId}: ${content.reason}")
                true
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "failed to persist ${content.fileId}", t)
        false
    }

    private fun phaseFor(fileId: XiaomiActivityFileId): String = when {
        fileId.type == XiaomiActivityFileId.Type.SPORTS -> PHASE_WORKOUTS
        fileId.subtype == XiaomiActivityFileId.Subtype.ACTIVITY_SLEEP ||
            fileId.subtype == XiaomiActivityFileId.Subtype.ACTIVITY_SLEEP_STAGES -> PHASE_SLEEP
        else -> PHASE_HEALTH
    }

    /** Same as upstream dumpBytesToExternalStorage: raw bytes for debugging / re-parse. */
    private fun dumpRaw(fileId: XiaomiActivityFileId, bytes: ByteArray) {
        runCatching {
            val ctx = AppContext.context
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val out = fileId.outputFile(File(base, "rawFetchOperations"))
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }.onFailure { Log.w(TAG, "failed to dump raw bytes for $fileId", it) }
    }

    // ------------------------------------------------------------- commands

    private suspend fun sendFetchToday(driver: MiBand9BleDriver) {
        driver.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(ActivityCommands.COMMAND_TYPE)
                .setSubtype(ActivityCommands.CMD_ACTIVITY_FETCH_TODAY)
                .setHealth(
                    XiaomiProto.Health.newBuilder().setActivitySyncRequestToday(
                        // TODO upstream: official app sends 0, but sometimes 1?
                        XiaomiProto.ActivitySyncRequestToday.newBuilder().setUnknown1(0).build(),
                    ).build(),
                )
                .build(),
        )
    }

    private suspend fun sendFetchPast(driver: MiBand9BleDriver) {
        driver.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(ActivityCommands.COMMAND_TYPE)
                .setSubtype(ActivityCommands.CMD_ACTIVITY_FETCH_PAST)
                .build(),
        )
    }

    private suspend fun sendRequest(driver: MiBand9BleDriver, fileId: XiaomiActivityFileId) {
        driver.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(ActivityCommands.COMMAND_TYPE)
                .setSubtype(ActivityCommands.CMD_ACTIVITY_FETCH_REQUEST)
                .setHealth(
                    XiaomiProto.Health.newBuilder()
                        .setActivityRequestFileIds(ByteString.copyFrom(fileId.toBytes()))
                        .build(),
                )
                .build(),
        )
    }

    private suspend fun sendAck(driver: MiBand9BleDriver, fileId: XiaomiActivityFileId) {
        driver.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(ActivityCommands.COMMAND_TYPE)
                .setSubtype(ActivityCommands.CMD_ACTIVITY_FETCH_ACK)
                .setHealth(
                    XiaomiProto.Health.newBuilder()
                        .setActivitySyncAckFileIds(ByteString.copyFrom(fileId.toBytes()))
                        .build(),
                )
                .build(),
        )
    }
}
