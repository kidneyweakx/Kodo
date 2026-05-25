/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.Promise

class HybridSystemControl : HybridHybridSystemControlSpec() {
    private var prefs = BandPreferenceSnapshot(
        language = DisplayLanguage.ZH_HANT,
        use24HourClock = true,
        heartRateRealtime = false,
        heartRateInterval = HeartRateInterval._30M,
        stepGoal = 8_000.0,
    )

    override fun ringPhone() {}
    override fun silencePhone() {}
    override fun syncClock(): Promise<Unit> = Promise.async { Unit }
    override fun getPreferences(): BandPreferenceSnapshot = prefs
    override fun setPreferences(prefs: BandPreferenceSnapshot): Promise<BandPreferenceSnapshot> = Promise.async {
        this.prefs = prefs
        prefs
    }
}
