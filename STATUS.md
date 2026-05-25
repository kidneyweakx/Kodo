# STATUS

Generated: 2026-05-26

## ✅ Feature wiring complete — 13/13 HybridObjects send/receive real protobuf

```bash
bun install && git submodule update --init
bun expo prebuild --platform android --no-install --clean
bunx nitrogen
cd android && ./gradlew :app:assembleDebug
# → BUILD SUCCESSFUL — debug APK at android/app/build/outputs/apk/debug/app-debug.apk (~238 MB)
```

`bunx tsc --noEmit` clean. `bun lint` 0 warnings. `./gradlew :app:assembleDebug` BUILD SUCCESSFUL (4 ABIs: armeabi-v7a, arm64-v8a, x86, x86_64).

## ✅ Every Mi Band 9 Active capability is wired

| Capability | Hybrid | Engine call | Verified path |
|---|---|---|---|
| BLE scan / pair / V2 auth handshake | `HybridBandLink` | `MiBand9BleDriver.connect` + `XiaomiAuthSession.installWatchNonce` | scan→pair→encrypted session |
| **Activity sync (HR / SpO₂ / Stress / Sleep / Steps / kcal / dist)** | `HybridBandLink.syncSince` | `Health(8,2)` request → activity chunks → `XiaomiActivityFileFetcher` (CRC-validated) → parsers → `SampleStore` → `Health(8,3)` per-file ack | end-to-end with proper per-file ack |
| **Periodic 30-min background sync** | `HybridBandLink.setPeriodicSync` | `MiBand9PeriodicSyncWorker` (WorkManager, `requiresBatteryNotLow=true`) | `bandLink.setPeriodicSync(true)` from JS |
| Read persisted samples | `HybridHealthStore` | `SampleStore.loadActivity / loadSleep` | summaries + HR / Stress / Sleep series |
| Notification access permission | `HybridNotificationBridge` | `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` | deep-link |
| Notification filter / DnD mirror | `HybridNotificationBridge` | SharedPreferences + `MiBand9NotificationListener` | persists across launches |
| **Notification push** | `HybridNotificationBridge.push` | `Notification(7,0)` + auto-forward from system NLS | also triggers icon flow below |
| **Notification icon upload** | `HybridNotificationBridge` | listens for `Notification(7,16)` icon-query + `Notification(7,15)` icon-request → `IconConverter` (RGB565/ARGB8565) → `MiBand9DataUploader.upload(TYPE_NOTIFICATION_ICON)` | reactive band ⇄ phone |
| Find phone (band → phone) | observed via `driver.incoming` | filter `SystemCommands.CMD_FIND_PHONE` | JS owns audio |
| Clock sync | `HybridSystemControl.syncClock` | `System(2,3)` Date+Time+TimeZone | with DST offset |
| Language push | `HybridSystemControl.setPreferences` | `System(2,6)` `Language.code` | `zh_TW` / `en_US` |
| **Camera shutter** | `HybridCameraRemote.arm/disarm/onShutter` | `System(2,8)` enable + `driver.incoming` subscribe | armed band → shutter callback |
| **GPS push during workout** | `HybridGpsTracker.startWorkout` | `Health(8,48)` `WorkoutLocation` on every fix | location\|connectedDevice fg service |
| GPS foreground service | `MiBand9GpsService` | own `FOREGROUND_SERVICE_LOCATION` permission | only while workout active |
| Weather push | `HybridWeatherBridge.push` | `Weather(10,0)` `WeatherCurrent` | OWM code → Xiaomi 0..33 via `XiaomiWeatherConditions` |
| Third-party weather intake | `HybridWeatherProvider` | `ACTION_GENERIC_WEATHER` broadcast | Breezy / GBWeather / OWMW / Tasker-bridged Samsung |
| Calendar push | `HybridCalendarBridge.pushEvents/clearEvents` | `Calendar(12,0)` `CalendarSync` | 10-min advance notify |
| Music now-playing push | `HybridMusicBridge.pushNowPlaying` | `Music(18,0)` `MusicInfo` | title / artist / position / duration |
| Music control buttons | `HybridMusicBridge.onCommand` | filter `Music(18,1)` in `driver.incoming` | play / pause / next / prev / vol ± |
| Sedentary reminder | `HybridSedentary.set` | `Health(8,29)` + SharedPreferences | persisted |
| **Fitbit / Samsung Health / Google Fit export** | `HybridHealthConnect.exportDay` | `SampleStore` → `HealthConnectExporter` → HC client | HR / SpO₂ / Steps / SleepSession records |
| **Watchface list / install / activate / delete** | `HybridWatchface` | `Watchface(4,0/1/2/4)` + `MiBand9DataUploader.upload(TYPE_WATCHFACE)` | md5 + crc32 + chunked over DATA channel |
| Find band (phone → band ring) | — | — | Mi Band 9 Active hardware doesn't support |

## ✅ Engine layer (Gadgetbridge → Kotlin)

| Area | File |
|---|---|
| Protobuf schema | `android-port/src/main/proto/xiaomi.proto` → generated `XiaomiProto.java` |
| Conditions / Workout types | `XiaomiWeatherConditions.kt`, `XiaomiWorkoutType.kt` |
| Activity samples + parsers + fetcher | `XiaomiActivityFileId.kt`, `XiaomiComplexActivityParser.kt`, `DailyDetailsParser.kt`, `SleepStagesParser.kt`, `XiaomiActivityFileFetcher.kt` (chunk header + CRC + sealed `ParsedActivityFile`) |
| Crypto + auth | `XiaomiCrypto.kt` (CCM/CTR/HMAC/KDF), `XiaomiAuthSession.kt` |
| V1 + V2 protocol | `XiaomiUuids.kt`, `XiaomiCharacteristicV1.kt`, `XiaomiSppPacketV2.kt`, `V2PacketAccumulator.kt`, `MiBand9BleDriver.kt` |
| Data uploader (watchface / icon / firmware) | `xiaomi/services/MiBand9DataUploader.kt` (md5 + crc32 + chunked) |
| Notifications + icon | `IconConverter.kt`, `MiBand9NotificationListener.kt` |
| Weather receiver | `GenericWeatherReceiver.kt` |
| Wire command IDs | `SystemCommands.kt` |
| GPS workout service | `gps/MiBand9GpsService.kt` |
| WorkManager periodic sync | `sync/MiBand9PeriodicSyncWorker.kt` |
| Storage / context | `AppContext.kt`, `SampleStore.kt`, `DriverHolder.kt` |
| Health Connect writer | `healthconnect/HealthConnectExporter.kt` |

## ✅ Nitro

13/13 `.nitro.ts` → `bunx nitrogen` → 13 generated `HybridHybrid<X>Spec.kt` → 13 Kotlin impls under `com.margelo.nitro.miband9active.Hybrid<X>`.

C++ JNI + Kotlin class registration generated automatically; native CMake target `MiBand9ActiveNitro` builds for all four ABIs.

## ✅ Build matrix

- minSdk 26 / compileSdk 36 / targetSdk 35 / Kotlin 2.1.20 / Gradle 9.3.1
- Expo SDK 56.0.4, RN 0.85.3, Reanimated 4.3.1
- Nitro Modules 0.35.7 + Nitrogen 0.35.7
- BouncyCastle 1.78.1, protobuf-javalite 3.25.5, kotlinx-coroutines 1.9.0
- androidx.health.connect 1.1.0-rc02, androidx.work 2.10.0

## ❌ Explicitly out of scope

- **Find band (phone → band ring)** — Mi Band 9 Active hardware doesn't support; upstream Gadgetbridge's `MiBand9ActiveCoordinator.supportsFindDevice` returns `false`.
- Other watches / fitness bands.
- Cloud sync / telemetry / crash reporting SaaS.

Everything else from `FEATURES.md` is reachable end-to-end through `NitroModules.createHybridObject(...)` from JS.
