# 功能清單 / Feature Inventory — Mi Band 9 Active Slim

> Source of truth: `Gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/xiaomi/watches/MiBand9ActiveCoordinator.java` + `XiaomiCoordinator.java`.
>
> 此瘦身版**只支援小米 Smart Band 9 Active**(藍牙廣播名稱 `Xiaomi Smart Band 9 Active XXXX`)。所有非此手環的程式碼路徑都不會帶進專案。
>
> This slim port **only targets Xiaomi Smart Band 9 Active**. Code paths for other bands/watches are intentionally excluded.

---

## 0. Licensing & Provenance

- Gadgetbridge upstream: **AGPL-3.0-or-later**.
- This project is a derivative work and therefore **AGPL-3.0-or-later**.
- All ported logic keeps the original `Copyright (C)` headers; new files carry our own header that references the upstream notice.
- See `LICENSE`, `NOTICE.md`.

---

## 1. Connectivity / 連線

| Feature | Status | Source |
|---|---|---|
| BLE scan (filter on `Xiaomi Smart Band 9 Active ####`) | ✅ shipped | `XiaomiCoordinator.createBLEScanFilters` |
| Encrypted pairing (`BONDING_STYLE_REQUIRE_KEY` + 32-byte auth key) | ✅ shipped | `XiaomiCoordinator.getBondingStyle` |
| Plaintext numeric auth key fallback | ✅ shipped | `XiaomiCoordinator.validateAuthKey` |
| BLE Protocol V1 / V2 auto-negotiate | ✅ shipped | `XiaomiBleProtocolV1/V2.java` |
| SPP transport | ❌ removed — Band 9 Active is BLE-only | — |
| Reconnect / keep-alive | ✅ shipped | `XiaomiConnectionSupport` |

## 2. Health / 健康量測

| Feature | Status | Source |
|---|---|---|
| Heart rate — 自動量測 / Auto HR | ✅ | `supportsHeartRateMeasurement` |
| Heart rate — **手動**單次量測 / Manual HR | ❌ **Band 9 Active 不支援** | `MiBand9ActiveCoordinator.supportsManualHeartRateMeasurement` returns false |
| Heart rate — Resting HR | ✅ | `XiaomiHeartRateRestingSampleProvider` |
| SpO₂ | ✅ | `XiaomiSpo2SampleProvider` |
| Stress (1-25 relaxed / 26-50 mild / 51-80 moderate / 81-100 high) | ✅ | `XiaomiStressSampleProvider` |
| Sleep — stages + REM + awake | ✅ | `XiaomiSleepStageSampleProvider`, `supportsRemSleep`, `supportsAwakeSleep` |
| Sleep — respiratory rate | ❌ skip (Gadgetbridge still TODO) | `supportsSleepRespiratoryRate=false` |
| Temperature | ✅ | `XiaomiTemperatureSampleProvider` |
| PAI / Vitality Score | ✅ | `XiaomiPaiSampleProvider` |
| Body Energy | ❌ skip (Gadgetbridge: `false // FIXME untested`) | `supportsBodyEnergy=false` |
| Active calories / distance | ✅ | `supportsActiveCalories`, `supportsActivityDistance` |
| Realtime steps / HR streaming | ✅ (但預設關閉省電) | `supportsRealtimeData` |

## 3. Activity / 運動

| Feature | Status |
|---|---|
| Daily summary samples | ✅ |
| Workout tracking + GPS track import | ✅ (`XiaomiActivityTrackProvider`, `WorkoutSummaryParser`) |
| Activity fetch from device | ⚠️ **upstream 標註 broken**;先做但允許失敗 (`MiBand9ActiveCoordinator.isExperimental=true`) |

## 4. Notifications / 通知轉發 ⭐ (必移植)

| Feature | Status |
|---|---|
| App notification push (title/body/icon) | ✅ |
| Per-app allow/deny list | ✅ |
| Unicode emoji on band | ✅ (`supportsUnicodeEmojis`) |
| Phonebook sync (caller-ID names) | ✅ |
| Incoming call alert + reject from band | ✅ |
| SMS canned replies | ✅ (slot count from `PREF_CANNED_MESSAGES_MAX`) |
| Mute when DnD on phone | ✅ |

## 5. Onboarding ⭐ (必移植)

1. Welcome / language pick (zh-Hant or en).
2. Bluetooth + Location runtime permission.
3. Notification access permission (`NotificationListenerService`).
4. Battery-optimization whitelist prompt (Doze).
5. Scan → pick band → confirm pairing → auth-key input/derivation → done.

## 6. Productivity / 生產力

| Feature | Status |
|---|---|
| Alarms — slot count from device | ✅ (`getAlarmSlotCount`) |
| Smart wakeup window | ✅ |
| Reminders (≤ 20 chars) | ✅ |
| Calendar events sync | ✅ |
| Weather push | ✅ |
| World clocks | ❌ skip (slot count = 0 upstream) |

## 7. Music / 音樂控制

| Feature | Status |
|---|---|
| Now-playing info (title/artist/app) | ✅ |
| Play / pause / next / prev | ✅ |
| Volume up/down | ✅ |

## 8. Device features / 手環設定

| Feature | Status |
|---|---|
| Watchface management / install / uninstall | ✅ |
| Installed-app list fetch | ✅ |
| OTA firmware flash | ✅ |
| Find phone (band → phone ring) | ✅ |
| **Find band** (phone → band) | ❌ **Band 9 Active 不支援** (`MiBand9ActiveCoordinator.supportsFindDevice=false`) |
| Widgets layout (`XiaomiWidgetManager`) | ✅ |
| Settings customizer screens | ✅ (subset) |

## 9. Languages / 語系

Two locales only, baked at build time:

- `zh-Hant` (Traditional Chinese, default for `zh-*`).
- `en` (default fallback).

No runtime locale downloads. No third-party language packs.

## 10. Power-saving deltas vs upstream / 省電取捨

| Knob | Default | Reason |
|---|---|---|
| Realtime HR streaming | **off** | 連續 1Hz HR 是耗電大戶 |
| Activity poll interval | 30 min (upstream: 15 min) | 一般使用足夠 |
| Battery polling | every 60 min | 不需要更頻繁 |
| BLE scan window during auto-reconnect | 1.2s every 12s (low duty) | 比 upstream 預設更保守 |
| Notification icon bitmap | downscale to 24×24, lossless palette | 減少封包與 flash 寫入 |
| Background workers | foreground service only when actively syncing | 平時不常駐 |

## 11. Excluded / 不做

- 其他手環/手錶機種(Mi Band 2~8, Redmi Watch, Amazfit, …)。
- SPP-only 機種的橋接邏輯。
- Pebble / Garmin / Fossil / Casio / etc. provider 程式碼。
- Cloud sync, anonymous telemetry, crash reporting SaaS。
- Web UI (Gadgetbridge 沒有,我們也不做)。

---

## Mapping: Gadgetbridge → Nitro Module → JS API

| Gadgetbridge class (Java/Kotlin) | Nitro HybridObject | JS facade |
|---|---|---|
| `XiaomiBleSupport`, `XiaomiBleProtocolV1/V2` | `HybridBandLink` | `useBandLink()` |
| `XiaomiAuthService` | inside `HybridBandLink.pair()` | `bandLink.pair(authKey)` |
| `XiaomiHealthService` + sample providers | `HybridHealthStore` | `useHealthDaily(date)` |
| `XiaomiNotificationService` | `HybridNotificationBridge` | `useNotificationBridge()` |
| `XiaomiMusicService` | `HybridMusicBridge` | `useMusicBridge()` |
| `XiaomiWeatherService` | `HybridWeatherBridge` | `weatherBridge.push(...)` |
| `XiaomiCalendarService` | `HybridCalendarBridge` | `calendarBridge.sync()` |
| `XiaomiWatchfaceService` + `XiaomiInstallHandler` | `HybridWatchfaceInstaller` | `watchface.install(uri)` |
| `XiaomiSystemService` (find phone, battery, time) | `HybridSystemControl` | `system.findPhone()` |
| `XiaomiPreferences` | `HybridBandSettings` | `useBandSetting(key)` |

Every Nitro HybridObject is the **only** entry-point from JS to native. The React layer never calls Android `BluetoothGatt` directly.
