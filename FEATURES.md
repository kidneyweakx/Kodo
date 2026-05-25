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
| **Inactivity / 久坐提醒** | ✅ (`XiaomiPreferences.FEAT_INACTIVITY`) |
| Calendar events sync | ✅ |
| Weather push | ✅ (詳見 §12) |
| World clocks | ❌ skip (slot count = 0 upstream) |

## 6b. Camera remote / 拍照遙控 ⭐

| Feature | Status |
|---|---|
| 從手環觸發手機快門 | ✅ (`XiaomiSystemService.handleCameraRemote` + `setCameraRemoteConfig`) |
| 同步前端用 `CameraX` PreviewView,從 Nitro 接 shutter event | 規劃中 |

## 6c. GPS / 健身路徑

| Feature | Status |
|---|---|
| Workout 期間 phone → band 即時 GPS 推送 | ✅ (`XiaomiSupport.onSetGpsLocation`) |
| 解析 band → phone 的 workout GPS track | ✅ (`WorkoutGpsParser`) |
| 背景 GPS 追蹤(僅運動進行中) | 規劃中 — `FOREGROUND_SERVICE_LOCATION` foreground only |
| 永遠不要 `ACCESS_BACKGROUND_LOCATION` | 規範,見 docs/POWER.md |

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

## 12. Weather provider / 天氣資料來源

Mi Band 9 Active 沒有獨立天氣 API,所以我們把 Gadgetbridge 的 **`ACTION_GENERIC_WEATHER` Intent broadcast** 接過來,任何外部 App(GBWeather、Tasker、Breezy Weather、OWMW、Samsung Weather + Bixby Routine 橋接)都可以推天氣給我們,我們再經 `HybridWeatherBridge.push()` 寫到手環。

| Source | How to wire |
|---|---|
| OpenWeatherMap (用戶提供 API key) | 內建,30 分鐘輪詢一次 |
| Breezy Weather / GBWeather | 廣播 `gg.solidarity.miband9active.ACTION_GENERIC_WEATHER` |
| Samsung Weather | 經由「Samsung Routines」+ Tasker 橋接到 broadcast |
| LineageOS / CM weather | 預留 receiver(暫不做,需要時補) |

## 13. Health Connect export(Fitbit / Samsung Health / Google Fit 共用)⭐

Android 上 Fitbit/Samsung Health/Google Fit 都從 **Health Connect** 統一讀資料。我們的 Nitro `HybridHealthConnect` 把每日的心率、SpO₂、步數、睡眠 stage 寫進 Health Connect;Fitbit 開啟「在 Health Connect 中允許讀取」即可同步。

| Record | Source field | Target Health Connect type |
|---|---|---|
| Steps | `HealthDailySummary.steps` | `StepsRecord` |
| Heart rate (resting) | `HealthDailySummary.restingHeartRate` | `HeartRateRecord` |
| SpO₂ avg | `HealthDailySummary.spo2Average` | `OxygenSaturationRecord` |
| Sleep stages | `SleepSegment[]` | `SleepSessionRecord` + `SleepStageRecord` |
| Active calories | `HealthDailySummary.activeCalories` | `ActiveCaloriesBurnedRecord` |
| Distance | `HealthDailySummary.distanceMeters` | `DistanceRecord` |

Ported from `Gadgetbridge/app/src/main/java/.../util/healthconnect/` (Kotlin).

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
