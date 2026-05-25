/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.Promise

class HybridGpsTracker : HybridHybridGpsTrackerSpec() {
    private var _active = false
    override val isWorkoutActive: Boolean get() = _active
    override val lastSample: Variant_NullType_GpsSample get() = Defaults.GPS
    override fun startWorkout(type: WorkoutType): Promise<Unit> = Promise.async { _active = true }
    override fun stopWorkout(): Promise<Unit> = Promise.async { _active = false }
    override fun onSample(listener: (sample: GpsSample) -> Unit): () -> Unit = noopUnsubscribe()
}
