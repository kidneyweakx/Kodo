/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.Promise

/** Thin bridge between Nitro spec and our internal MiBand9BleDriver. */
class HybridBandLink : HybridHybridBandLinkSpec() {

    override val connectionState: ConnectionState get() = ConnectionState.DISCONNECTED
    override val currentBand: Variant_NullType_PairedBand get() = Defaults.BAND
    override val battery: Variant_NullType_BatteryInfo get() = Defaults.BATTERY

    override fun scan(options: BandLinkScanOptions): Promise<Array<DiscoveredBand>> =
        Promise.async { emptyArray() }

    override fun stopScan() {}

    override fun pair(deviceId: String, options: BandLinkPairOptions): Promise<PairedBand> =
        Promise.async {
            throw NotImplementedError("BandLink.pair: MiBand9BleDriver integration pending")
        }

    override fun forget(): Promise<Unit> = Promise.async { Unit }
    override fun connect(): Promise<Unit> = Promise.async { Unit }
    override fun disconnect() {}

    override fun syncSince(sinceIso: String): Promise<Double> = Promise.async { 0.0 }
    override fun fetchTodaySummary(): Promise<Variant_NullType_HealthDailySummary> =
        Promise.async { Defaults.SUMMARY }

    override fun onConnectionStateChange(listener: (state: ConnectionState) -> Unit): () -> Unit = noopUnsubscribe()
    override fun onBatteryChange(listener: (battery: BatteryInfo) -> Unit): () -> Unit = noopUnsubscribe()
    override fun onScanResult(listener: (band: DiscoveredBand) -> Unit): () -> Unit = noopUnsubscribe()
    override fun onSyncProgress(listener: (event: SyncProgress) -> Unit): () -> Unit = noopUnsubscribe()
}
