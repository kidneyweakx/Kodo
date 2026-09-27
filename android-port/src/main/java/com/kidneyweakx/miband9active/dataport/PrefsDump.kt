/*  Copyright (C) 2024 José Rebelo                                           (Gadgetbridge)
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
 * Generic SharedPreferences ⇄ JSON, in the same shape as Gadgetbridge's
 * JsonBackupPreferences (util/backup/JsonBackupPreferences.java):
 *
 *   { "preferences": { "<key>": { "type": "Boolean|Float|Integer|Long|String|HashSet",
 *                                  "value": … } } }
 *
 * Restore clears the file and re-puts every value (importInto upstream),
 * except keys the backup deliberately left out (secrets when the user did
 * not include them), which keep their current on-device value.
 */
package com.kidneyweakx.miband9active.dataport

import android.content.Context
import android.content.SharedPreferences
import com.kidneyweakx.miband9active.AppContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object PrefsDump {
    private const val BOOLEAN = "Boolean"
    private const val FLOAT = "Float"
    private const val INTEGER = "Integer"
    private const val LONG = "Long"
    private const val STRING = "String"
    private const val HASHSET = "HashSet"

    private fun prefs(name: String): SharedPreferences =
        AppContext.context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** Every SharedPreferences file of the native engine present on disk (see KodoBackupFormat.isOwnPrefsName). */
    fun ownPrefsNames(): List<String> {
        val dir = File(AppContext.context.applicationInfo.dataDir, "shared_prefs")
        return dir.list().orEmpty()
            .filter { it.endsWith(".xml") }
            .map { it.removeSuffix(".xml") }
            .filter { KodoBackupFormat.isOwnPrefsName(it) }
            .sorted()
    }

    class Dump(val json: JSONObject, val strippedKeys: List<String>)

    fun dump(name: String, includeSecrets: Boolean): Dump {
        val values = JSONObject()
        val stripped = ArrayList<String>()
        for ((key, v) in prefs(name).all.toSortedMap()) {
            if (v == null) continue
            if (!includeSecrets && KodoBackupFormat.isSecretKey(key)) {
                stripped += key
                continue
            }
            val entry = JSONObject()
            when (v) {
                is Boolean -> entry.put("type", BOOLEAN).put("value", v)
                is Float -> entry.put("type", FLOAT).put("value", if (v.isFinite()) v.toDouble() else 0.0)
                is Int -> entry.put("type", INTEGER).put("value", v)
                is Long -> entry.put("type", LONG).put("value", v)
                is String -> entry.put("type", STRING).put("value", v)
                is Set<*> -> entry.put("type", HASHSET).put("value", JSONArray(v.filterIsInstance<String>().sorted()))
                else -> continue
            }
            values.put(key, entry)
        }
        return Dump(JSONObject().put("preferences", values), stripped)
    }

    /**
     * Replace [name] with the backup [json]; keys in [preserve] keep their
     * current value. Returns the number of keys written.
     */
    fun restore(name: String, json: JSONObject, preserve: Set<String>): Int {
        val p = prefs(name)
        val kept = p.all.filterKeys { it in preserve }
        val editor = p.edit().clear()
        var n = 0
        val values = json.optJSONObject("preferences") ?: JSONObject()
        for (key in values.keys()) {
            val entry = values.optJSONObject(key) ?: continue
            if (key in preserve) continue
            when (entry.optString("type")) {
                BOOLEAN -> editor.putBoolean(key, entry.optBoolean("value"))
                FLOAT -> editor.putFloat(key, entry.optDouble("value", 0.0).toFloat())
                INTEGER -> editor.putInt(key, entry.optInt("value"))
                LONG -> editor.putLong(key, entry.optLong("value"))
                STRING -> editor.putString(key, entry.optString("value"))
                HASHSET -> {
                    val arr = entry.optJSONArray("value") ?: JSONArray()
                    editor.putStringSet(key, HashSet<String>().apply { for (i in 0 until arr.length()) add(arr.optString(i)) })
                }
                else -> continue
            }
            n++
        }
        for ((key, v) in kept) {
            when (v) {
                is Boolean -> editor.putBoolean(key, v)
                is Float -> editor.putFloat(key, v)
                is Int -> editor.putInt(key, v)
                is Long -> editor.putLong(key, v)
                is String -> editor.putString(key, v)
                is Set<*> -> editor.putStringSet(key, HashSet(v.filterIsInstance<String>()))
            }
        }
        if (!editor.commit()) throw DataPortException(DataPortException.IO, "could not write preferences '$name'")
        return n
    }

    /** Typed read of one value from a dump (null when absent / wrong type). */
    fun string(json: JSONObject, key: String): String? =
        json.optJSONObject("preferences")?.optJSONObject(key)
            ?.takeIf { it.optString("type") == STRING }
            ?.let { if (it.isNull("value")) null else it.optString("value") }

    fun boolean(json: JSONObject, key: String): Boolean? =
        json.optJSONObject("preferences")?.optJSONObject(key)
            ?.takeIf { it.optString("type") == BOOLEAN }
            ?.optBoolean("value")

    fun stringSet(json: JSONObject, key: String): Set<String>? =
        json.optJSONObject("preferences")?.optJSONObject(key)
            ?.takeIf { it.optString("type") == HASHSET }
            ?.optJSONArray("value")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
}
