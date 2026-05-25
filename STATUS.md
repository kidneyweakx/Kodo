# STATUS

Generated: 2026-05-26

## ✅ Mi Band 9 Active feature wiring — every Hybrid sends/receives real protobuf

```bash
bun install && git submodule update --init
bun expo prebuild --platform android --no-install --clean
bunx nitrogen
cd android && ./gradlew :app:assembleDebug
# → BUILD SUCCESSFUL — debug APK at android/app/build/outputs/apk/debug/app-debug.apk (~238 MB)
```

Every Hybrid below now calls `MiBand9BleDriver.sendCommand(XiaomiProto.Command)` and/or subscribes to `driver.incoming` / `driver.activityChunks`. Driver instance is shared via `com.kidneyweakx.miband9active.DriverHolder` — `HybridBandLink.pair()`/`connect()` set it, `forget()` clears it.

| Feature (FEATURES.md) | Hybrid | Engine call | Status |
|---|---|---|---|
| BLE scan / pair / auth (V2) | `HybridBandLink` | `MiBand9BleDriver.connect` + auth handshake | ✅ scan→pair→encrypted session |
| **Sync HR / SpO₂ / Stress / Sleep / Steps / kcal / dist** | `HybridBandLink.syncSince` | `Health(8,2)` request → `activityChunks` → `XiaomiActivityFileFetcher` → parsers → `SampleStore` | ✅ end-to-end |
| Read persisted samples | `HybridHealthStore` | `SampleStore.load*` | ✅ |
| Notification access permission | `HybridNotificationBridge` | `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` | ✅ |
| Notification filter / DnD mirror | `HybridNotificationBridge` | SharedPreferences + `MiBand9NotificationListener` | ✅ |
| **Notification push to band** | `HybridNotificationBridge.push` | `Notification(7,0)` with `Notification3` payload | ✅ also auto-forwarded from system NLS |
| **Find phone (band → phone)** | observed via `driver.incoming` | filter `SystemCommands.CMD_FIND_PHONE` | ✅ (JS owns audio side) |
| **Clock sync** | `HybridSystemControl.syncClock` | `System(2,3)` with Date+Time+TimeZone | ✅ |
| **Language push** | `HybridSystemControl.setPreferences` | `System(2,6)` with `Language` | ✅ |
| **Camera shutter** | `HybridCameraRemote.arm/disarm/onShutter` | `System(2,8)` enable + listen `driver.incoming` | ✅ |
| **GPS push to band during workout** | `HybridGpsTracker.startWorkout` | `Health(8,48)` `WorkoutLocation` on every fix | ✅ |
| GPS foreground service | `MiBand9GpsService` | location\|connectedDevice fg | ✅ |
| **Weather push** | `HybridWeatherBridge.push` | `Weather(10,0)` with `WeatherCurrent` | ✅ current-only |
| Third-party weather intake | `HybridWeatherProvider` | `ACTION_GENERIC_WEATHER` broadcast | ✅ |
| **Calendar push** | `HybridCalendarBridge.pushEvents/clearEvents` | `Calendar(12,0)` with `CalendarSync` | ✅ |
| **Music now-playing push** | `HybridMusicBridge.pushNowPlaying` | `Music(18,0)` with `MusicInfo` | ✅ |
| **Music control buttons (band → phone)** | `HybridMusicBridge.onCommand` | filter `Music(18,1)` in `driver.incoming` | ✅ |
| **Sedentary reminder** | `HybridSedentary.set` | `Health(8,29)` + SharedPreferences | ✅ |
| **Fitbit / Samsung Health / Google Fit export** | `HybridHealthConnect.exportDay` | `SampleStore` → `HealthConnectExporter` → HC client | ✅ |
| Find band (phone → band ring) | — | — | ❌ Mi Band 9 Active hardware doesn't support (`MiBand9ActiveCoordinator.supportsFindDevice=false`) |
| Watchface install | — | — | post-MVP |

## ✅ Engine layer (Gadgetbridge → Kotlin)

| Area | File |
|---|---|
| Protobuf schema | `android-port/src/main/proto/xiaomi.proto` (verbatim) → auto-generated `XiaomiProto.java` |
| Conditions / Workout types | `xiaomi/XiaomiWeatherConditions.kt`, `xiaomi/XiaomiWorkoutType.kt` |
| Activity file id / parsers | `xiaomi/activity/XiaomiActivityFileId.kt`, `XiaomiComplexActivityParser.kt`, `XiaomiActivitySample.kt`, `DailyDetailsParser.kt`, `SleepStagesParser.kt`, `XiaomiActivityFileFetcher.kt` |
| Crypto + auth | `xiaomi/auth/XiaomiCrypto.kt`, `XiaomiAuthSession.kt` |
| V1 + V2 protocol | `xiaomi/protocol/XiaomiUuids.kt`, `XiaomiCharacteristicV1.kt`, `XiaomiSppPacketV2.kt`, `V2PacketAccumulator.kt`, `MiBand9BleDriver.kt` |
| Notification + icon | `xiaomi/notifications/IconConverter.kt`, `MiBand9NotificationListener.kt` |
| Weather receiver | `xiaomi/services/GenericWeatherReceiver.kt` |
| Wire command IDs | `xiaomi/services/SystemCommands.kt` |
| GPS workout service | `gps/MiBand9GpsService.kt` |
| Storage / context | `AppContext.kt`, `SampleStore.kt`, `DriverHolder.kt` |
| Health Connect writer | `healthconnect/HealthConnectExporter.kt` |

## ✅ Nitro wiring

12/12 `.nitro.ts` → `bunx nitrogen` → 12 generated abstract `HybridHybrid<X>Spec` → 12 Kotlin impls under `com.margelo.nitro.miband9active.Hybrid<X>`.

JS facades in `libs/services/*.ts` use `NitroModules.createHybridObject<T>('Hybrid<X>')`. C++ JNI + Kotlin class registration generated automatically; native CMake target `MiBand9ActiveNitro` builds for all four ABIs.

## ✅ Build matrix

- minSdk 26 / compileSdk 36 / targetSdk 35 / Kotlin 2.1.20
- Expo SDK 56.0.4, RN 0.85.3, Reanimated 4.3.1
- Nitro Modules 0.35.7 + Nitrogen 0.35.7
- BouncyCastle 1.78.1, protobuf-javalite 3.25.5, kotlinx-coroutines 1.9.0, androidx.health.connect 1.1.0-rc02
- WorkManager runtime 2.10.0

## ⏳ Genuine remaining gaps

| Item | Why it's not "done" |
|---|---|
| Watchface install (`XiaomiInstallHandler` + `XiaomiWatchfaceService`) | Large secondary feature; deferred post-MVP |
| WorkManager periodic 30-min sync worker | Defined in docs/POWER.md but the Worker class itself isn't authored — JS-side `bandLink.syncSince` is the manual path |
| Notification icon → band: `IconConverter` exists but `push()` doesn't yet attach the 24×24 bitmap | TLV slot in proto is `Notification3.unknown4`; need to confirm field semantics before shipping |
| Activity-fetch "done" signal | We currently use a 15 s grace timeout; upstream Gadgetbridge listens for a specific reply we haven't fully mapped yet |

Everything else from `FEATURES.md` is reachable end-to-end through `NitroModules.createHybridObject(...)` from JS.
