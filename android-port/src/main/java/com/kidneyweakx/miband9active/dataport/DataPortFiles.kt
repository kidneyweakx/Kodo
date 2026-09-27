/*
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
 * URI / temp-file plumbing for the data port. `uri` may be content:// (SAF
 * document picker), file:// or an absolute path. Temp copies live in
 * cacheDir/dataport-tmp and are swept when older than an hour.
 */
package com.kidneyweakx.miband9active.dataport

import android.content.Context
import android.net.Uri
import com.kidneyweakx.miband9active.AppContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

internal object DataPortFiles {
    private const val TMP_DIR = "dataport-tmp"
    private const val BACKUP_DIR = "backups"
    private const val TMP_MAX_AGE_MS = 60 * 60 * 1000L
    private const val BACKUP_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    private val ctx: Context get() = AppContext.context

    fun tmpDir(): File = File(ctx.cacheDir, TMP_DIR).apply { mkdirs() }

    fun backupDir(): File = File(ctx.cacheDir, BACKUP_DIR).apply { mkdirs() }

    fun newTemp(prefix: String, suffix: String): File = File.createTempFile(prefix, suffix, tmpDir())

    /** Deletes stale temp files, keeping [keep]. */
    fun sweepTemp(keep: Set<File> = emptySet()) {
        val now = System.currentTimeMillis()
        tmpDir().listFiles()?.forEach { f ->
            if (f !in keep && now - f.lastModified() > TMP_MAX_AGE_MS) f.delete()
        }
    }

    /** Backups older than a day in cacheDir/backups (they were shared already, or abandoned). */
    fun sweepBackups(keep: File? = null) {
        val now = System.currentTimeMillis()
        backupDir().listFiles()?.forEach { f ->
            if (f != keep && now - f.lastModified() > BACKUP_MAX_AGE_MS) f.delete()
        }
    }

    /** A local file for file:// or absolute paths, null for content:// (must be copied). */
    fun localFile(uri: String): File? = when {
        uri.startsWith("file://") -> File(Uri.parse(uri).path ?: throw bad(uri))
        uri.startsWith("/") -> File(uri)
        else -> null
    }

    fun open(uri: String): InputStream = try {
        when {
            uri.startsWith("content://") ->
                ctx.contentResolver.openInputStream(Uri.parse(uri))
                    ?: throw DataPortException(DataPortException.IO, "cannot open $uri")
            else -> (localFile(uri) ?: throw bad(uri)).inputStream()
        }
    } catch (e: FileNotFoundException) {
        throw DataPortException(DataPortException.IO, "file not found: $uri", e)
    } catch (e: SecurityException) {
        throw DataPortException(DataPortException.IO, "no permission to read $uri", e)
    }

    /** Reads up to [n] leading bytes (fewer when the file is shorter). */
    fun head(uri: String, n: Int = 100): ByteArray = open(uri).use { input ->
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        buf.copyOf(read)
    }

    /**
     * The input as a local file: the file itself for file:// / paths, else a
     * streamed copy in the temp dir ([isTemp] = true, caller deletes).
     */
    fun materialize(uri: String, onBytes: (Long) -> Unit = {}): Pair<File, Boolean> {
        localFile(uri)?.let { f ->
            if (!f.isFile) throw DataPortException(DataPortException.IO, "file not found: $uri")
            return f to false
        }
        val out = newTemp("input-", ".bin")
        try {
            open(uri).use { input -> out.outputStream().use { copy(input, it, onBytes) } }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return out to true
    }

    fun copy(input: InputStream, output: java.io.OutputStream, onBytes: (Long) -> Unit = {}): Long {
        val buf = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            output.write(buf, 0, r)
            total += r
            onBytes(total)
        }
        return total
    }

    fun size(uri: String): Long? {
        localFile(uri)?.let { return it.length() }
        return runCatching {
            ctx.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun bad(uri: String) = DataPortException(DataPortException.BAD_FILE, "unsupported uri '$uri'")
}
