/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  SharedPreferences-backed JSON store for samples coming off the band so
 *  HybridHealthStore can read them without going through MMKV (Nitro layer
 *  already does MMKV on the JS side; we just need a Kotlin-side equivalent
 *  to back the spec calls). The reason this exists at all is that the JS
 *  cache lives in MMKV (`mb9a-cache.v1`) and Kotlin would have to JNI into
 *  that — easier to keep a parallel native store.
 */
package com.kidneyweakx.miband9active

import android.content.Context
import com.kidneyweakx.miband9active.xiaomi.activity.SleepStageSample
import com.kidneyweakx.miband9active.xiaomi.activity.SleepSummary
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivitySample
import org.json.JSONArray
import org.json.JSONObject

object SampleStore {
    private const val PREFS = "miband9active_samples"

    private fun prefs() = AppContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isoOf(epochSec: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochSec * 1000L
        }
        return "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }

    fun persistActivity(samples: List<XiaomiActivitySample>) {
        if (samples.isEmpty()) return
        val groupedByDay = samples.groupBy { isoOf(it.timestampSec) }
        val p = prefs().edit()
        for ((dayIso, daySamples) in groupedByDay) {
            val key = "samples:$dayIso"
            val existing = JSONArray(prefs().getString(key, "[]"))
            daySamples.forEach { s ->
                val obj = JSONObject().apply {
                    put("ts", s.timestampSec)
                    put("steps", s.steps)
                    put("hr", s.heartRate)
                    put("spo2", s.spo2)
                    put("stress", s.stress)
                    put("kcal", s.activeCalories)
                    put("distCm", s.distanceCm)
                    put("energy", s.energy)
                }
                existing.put(obj)
            }
            p.putString(key, existing.toString())
        }
        p.apply()
    }

    fun persistSleep(dayIso: String, summary: SleepSummary, stages: List<SleepStageSample>) {
        val summaryJson = JSONObject().apply {
            put("bedTime", summary.bedTimeSec)
            put("wake", summary.wakeupTimeSec)
            put("total", summary.totalMinutes)
            put("deep", summary.deepMinutes)
            put("light", summary.lightMinutes)
            put("rem", summary.remMinutes)
            put("awake", summary.awakeMinutes)
        }
        val stagesJson = JSONArray()
        stages.forEach { s ->
            stagesJson.put(JSONObject().apply { put("ts", s.timestampSec); put("stage", s.stage) })
        }
        prefs().edit()
            .putString("sleep-summary:$dayIso", summaryJson.toString())
            .putString("sleep-stages:$dayIso", stagesJson.toString())
            .apply()
    }

    fun loadActivity(dayIso: String): List<XiaomiActivitySample> {
        val raw = prefs().getString("samples:$dayIso", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                XiaomiActivitySample(
                    timestampSec = o.getLong("ts"),
                    steps = o.getInt("steps"),
                    heartRate = o.getInt("hr"),
                    spo2 = o.getInt("spo2"),
                    stress = o.getInt("stress"),
                    activeCalories = o.getInt("kcal"),
                    distanceCm = o.getInt("distCm"),
                    energy = o.getInt("energy"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun loadSleep(dayIso: String): Pair<SleepSummary?, List<SleepStageSample>> {
        val summaryRaw = prefs().getString("sleep-summary:$dayIso", null)
        val stagesRaw = prefs().getString("sleep-stages:$dayIso", null)
        val summary = summaryRaw?.let {
            runCatching {
                val o = JSONObject(it)
                SleepSummary(
                    bedTimeSec = o.getLong("bedTime"),
                    wakeupTimeSec = o.getLong("wake"),
                    totalMinutes = o.getInt("total"),
                    deepMinutes = o.getInt("deep"),
                    lightMinutes = o.getInt("light"),
                    remMinutes = o.getInt("rem"),
                    awakeMinutes = o.getInt("awake"),
                )
            }.getOrNull()
        }
        val stages = stagesRaw?.let {
            runCatching {
                val arr = JSONArray(it)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SleepStageSample(o.getLong("ts"), o.getInt("stage"))
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        return summary to stages
    }

    fun clearAll() {
        prefs().edit().clear().apply()
    }
}
