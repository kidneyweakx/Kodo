/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Holds an application Context that the Nitro Hybrid implementations can
 *  use without going through React/Expo native module lifecycle. Populated
 *  by [InitializerProvider] which Android instantiates automatically before
 *  Application.onCreate via the `<provider>` declared in the manifest.
 */
package com.kidneyweakx.miband9active

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

object AppContext {
    @Volatile private var app: Context? = null
    val context: Context
        get() = app ?: throw IllegalStateException("AppContext not initialized; declare <provider android:name=\"com.kidneyweakx.miband9active.InitializerProvider\" /> in the manifest.")

    internal fun install(context: Context) {
        app = context.applicationContext
    }
}

class InitializerProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { AppContext.install(it) }
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
