# STATUS — what actually works right now

Generated: 2026-05-26

## ✅ Builds end-to-end

```bash
bun install
git submodule update --init     # pulls upstream Gadgetbridge (read-only reference)
bun expo prebuild --platform android --no-install --clean
bunx nitrogen                   # generates 12 Hybrid Kotlin + C++ specs
cd android && ./gradlew :app:assembleDebug
# → BUILD SUCCESSFUL, ~238 MB debug APK at android/app/build/outputs/apk/debug/app-debug.apk
```

## ✅ Nitro fully wired

12 `*.nitro.ts` specs → `nitrogen` → 12 `HybridHybrid<Foo>Spec.kt` abstract classes → 12 Kotlin implementations under `com.margelo.nitro.miband9active.Hybrid<Foo>`. JS calls `NitroModules.createHybridObject<HybridBandLink>('HybridBandLink')` and gets a real Kotlin instance over JSI.

| Hybrid spec | Kotlin impl | Engine wired |
|---|---|---|
| `HybridBandLink` | `HybridBandLink.kt` | ✅ `MiBand9BleDriver` (scan / pair / connect / GATT / V2 framing / auth). `syncSince` is the only TODO. |
| `HybridHealthStore` | `HybridHealthStore.kt` | TODO (needs MMKV read of persisted samples) |
| `HybridNotificationBridge` | `HybridNotificationBridge.kt` | ✅ `MiBand9NotificationListener` + SharedPreferences for allow-list and DnD mirror |
| `HybridSystemControl` | `HybridSystemControl.kt` | In-memory prefs; band push pending the driver |
| `HybridMusicBridge` | `HybridMusicBridge.kt` | Stub |
| `HybridWeatherBridge` | `HybridWeatherBridge.kt` | Stub (will call `driver.sendCommand`) |
| `HybridCalendarBridge` | `HybridCalendarBridge.kt` | Stub |
| `HybridCameraRemote` | `HybridCameraRemote.kt` | Stub (arm/disarm flag; shutter event TBD) |
| `HybridGpsTracker` | `HybridGpsTracker.kt` | ✅ `MiBand9GpsService` (foreground location\|connectedDevice) |
| `HybridSedentary` | `HybridSedentary.kt` | In-memory config |
| `HybridWeatherProvider` | `HybridWeatherProvider.kt` | ✅ `GenericWeatherReceiver` (broadcast bridge for Breezy / GBWeather / OWMW / Samsung-via-Tasker) + SharedPreferences for OWM config |
| `HybridHealthConnect` | `HybridHealthConnect.kt` | ✅ `HealthConnectExporter` (HR / SpO₂ / Steps / SleepSession) — Fitbit / Samsung Health / Google Fit read from this |

## ✅ Build matrix

- minSdk **26** (Health Connect requirement), compileSdk **36**, targetSdk **35**
- Kotlin **2.1.20** (Expo modules requirement), Gradle 9.3.1
- Expo SDK 56.0.4, RN 0.85.3, Reanimated 4.3.1
- Nitro Modules **0.35.7** + Nitrogen **0.35.7** (auto-codegen via `bunx nitrogen`)
- BouncyCastle 1.78.1, protobuf-javalite 3.25.5, kotlinx-coroutines 1.9.0, androidx.health.connect 1.1.0-rc02

## ✅ Engine layer (Kotlin port of Gadgetbridge) — all compile

| Area | File | Mirrors upstream |
|---|---|---|
| Protobuf schema | `android-port/src/main/proto/xiaomi.proto` | verbatim |
| Generated proto Java | `XiaomiProto.java` (auto, ~50 k LOC) | upstream same |
| Conditions enum | `XiaomiWeatherConditions.kt` | `XiaomiWeatherConditions.java` |
| Workout types | `XiaomiWorkoutType.kt` | `XiaomiWorkoutType.java` |
| Activity file id | `xiaomi/activity/XiaomiActivityFileId.kt` | `XiaomiActivityFileId.java` |
| Bit-packed sample reader | `XiaomiComplexActivityParser.kt` | upstream same |
| Sample data class | `XiaomiActivitySample.kt` | upstream minus DAO |
| Daily details parser | `DailyDetailsParser.kt` | upstream minus DB |
| Sleep stages parser | `SleepStagesParser.kt` | upstream minus DB |
| Activity file fetcher | `XiaomiActivityFileFetcher.kt` | upstream slim |
| AES-CCM / CTR / HMAC | `xiaomi/auth/XiaomiCrypto.kt` | `XiaomiAuthService.java` (crypto) |
| Auth session holder | `XiaomiAuthSession.kt` | upstream fields encapsulated |
| Mi Band 9 Active UUIDs | `xiaomi/protocol/XiaomiUuids.kt` | filtered subset |
| V1 char (chunk + retry) | `XiaomiCharacteristicV1.kt` | `XiaomiCharacteristicV1.java` |
| V2 packet framing | `XiaomiSppPacketV2.kt` | `XiaomiSppPacketV2.java` (sealed-class rewrite) |
| V2 buffer accumulator | `V2PacketAccumulator.kt` | upstream `processBuffer` |
| GATT + V2 + auth driver | `MiBand9BleDriver.kt` | new (replaces `XiaomiBleProtocolV2` + GBDevice scaffold) |
| Icon downscale | `xiaomi/notifications/IconConverter.kt` | `XiaomiBitmapUtils.java` (slim) |
| NLS listener | `MiBand9NotificationListener.kt` | upstream `NotificationListener.java` |
| Weather receiver | `xiaomi/services/GenericWeatherReceiver.kt` | upstream |
| Command constants | `xiaomi/services/SystemCommands.kt` | upstream constants |
| Workout GPS service | `gps/MiBand9GpsService.kt` | new |
| App context holder | `AppContext.kt` + `InitializerProvider` | new |
| Health Connect writer | `healthconnect/HealthConnectExporter.kt` | inspired by upstream |

## ✅ Build wiring (Expo config plugins)

| Plugin | Job |
|---|---|
| `expo-build-properties` | minSdk 26 / compileSdk 36 / kotlin 2.1.20 |
| `plugins/with-project-build-gradle.js` | protobuf-gradle-plugin classpath |
| `plugins/with-android-port.js` | source sets (android-port + nitrogen) + Bouncycastle / protobuf-javalite / coroutines / HealthConnect / WorkManager + protobuf plugin |
| `plugins/with-manifest.js` | `InitializerProvider`, `MiBand9NotificationListener`, `MiBand9GpsService` (foregroundServiceType=location\|connectedDevice), `GenericWeatherReceiver` |

## ⏳ What's still TODO

| Slice | Why deferred |
|---|---|
| `HybridBandLink.syncSince` — orchestrate `XiaomiActivityFileFetcher` over `driver.activityChunks` + replay `XiaomiHealthService.fetch_activity_file` commands | parser + driver both exist; needs the loop |
| `HybridHealthStore` MMKV read | depends on sync loop above |
| Wire `HybridSystemControl` (clock / find-phone / preferences) through `driver.sendCommand` | mechanical after the driver runs on real hardware |
| Wire `HybridMusicBridge` to `MediaSessionManager` + driver commands | same |
| `HybridWeatherBridge.push` → `driver.sendCommand` with weather payload | same |
| `HybridCalendarBridge.pushEvents` → `driver.sendCommand` | same |
| `HybridCameraRemote.onShutter` → subscribe to `driver.incoming` (`SystemCommands.CMD_CAMERA_REMOTE_SET`) | same |
| Watchface install (`XiaomiInstallHandler`, `XiaomiWatchfaceService`) | post-MVP |

Each is one driver method call away — heavy lifting (BLE GATT, V2 framing, auth, protobuf) is done.
