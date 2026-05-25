/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

class HybridHealthStore : HybridHybridHealthStoreSpec() {
    override fun getDailySummary(dateIso: String): Variant_NullType_HealthDailySummary = Defaults.SUMMARY
    override fun getDailySummariesRange(fromIso: String, toIso: String): Array<HealthDailySummary> = emptyArray()
    override fun getHeartRateSeries(dateIso: String): Array<HeartRateSample> = emptyArray()
    override fun getStressSeries(dateIso: String): Array<StressSample> = emptyArray()
    override fun getSleepSegments(dateIso: String): Array<SleepSegment> = emptyArray()
    override fun clearAll() {}
}
