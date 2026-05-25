# STATUS — what actually works right now

Generated: 2026-05-26

---

## ✅ Now compiles end-to-end

`bun expo prebuild --platform android` followed by `./gradlew :app:compileDebugKotlin` is **green**. The Kotlin port lives under `android-port/` and is bundled into the Android source set by `plugins/with-android-port.js`. Protobuf is generated from `android-port/src/main/proto/xiaomi.proto` at build time.

Build matrix:
- minSdk **26** (Health Connect requirement)
- compileSdk **36**, targetSdk **35**
- Kotlin **2.1.20**
- Expo SDK 56.0.4 / RN 0.85.3 / Reanimated 4.3.1 / Nitro Modules 0.35.7
- Bouncycastle 1.78.1, protobuf-javalite 3.25.5, kotlinx-coroutines 1.9.0, androidx.health.connect 1.1.0-rc02

## ✅ Kotlin port shipped under `android-port/`

| File | Mirrors upstream | Notes |
|---|---|---|
| `proto/xiaomi.proto` | `vendor/Gadgetbridge/.../proto/xiaomi.proto` | verbatim copy; `protoc` generates `XiaomiProto.java` |
| `xiaomi/XiaomiWeatherConditions.kt` | `XiaomiWeatherConditions.java` | OWM → Xiaomi code mapping |
| `xiaomi/XiaomiWorkoutType.kt` | `XiaomiWorkoutType.java` | first-class kinds + i18n keys |
| `xiaomi/activity/XiaomiActivityFileId.kt` | `XiaomiActivityFileId.java` | 7-byte file id + nested Type/Subtype/DetailType |
| `xiaomi/activity/XiaomiComplexActivityParser.kt` | `XiaomiComplexActivityParser.java` | bit-packed sample stream reader |
| `xiaomi/activity/XiaomiActivitySample.kt` | `XiaomiActivitySample.java` | data class (no GreenDAO) |
| `xiaomi/activity/DailyDetailsParser.kt` | `DailyDetailsParser.java` | bytes → List<sample> (no DB) |
| `xiaomi/activity/SleepStagesParser.kt` | `SleepStagesParser.java` | sleep summary + stages |
| `xiaomi/auth/XiaomiCrypto.kt` | `XiaomiAuthService.java` (crypto half) | session KDF, AES-CCM, AES-CTR, HMAC-SHA256, auth-key parser |
| `xiaomi/auth/XiaomiAuthSession.kt` | (new) | per-connection holder for keys + counters |
| `xiaomi/protocol/XiaomiUuids.kt` | `XiaomiUuids.java` (subset) | only Mi Band 9 Active UUIDs |
| `xiaomi/protocol/XiaomiCharacteristicV1.kt` | `XiaomiCharacteristicV1.java` | chunked TX/RX, missing-chunk recovery, nonce counter |
| `xiaomi/protocol/XiaomiSppPacketV2.kt` | `XiaomiSppPacketV2.java` | A5A5 framing, CRC-16/ARC, sealed Ack/SessionConfig/Data variants |
| `xiaomi/notifications/IconConverter.kt` | `XiaomiBitmapUtils.java` (slim) | 24×24 RGB565 / ARGB8565 for the band |
| `xiaomi/notifications/MiBand9NotificationListener.kt` | `NotificationListener.java` + `XiaomiNotificationService.java` | allow-list filter + DnD mirror, decoupled forwarder |
| `xiaomi/services/GenericWeatherReceiver.kt` | `GenericWeatherReceiver.java` | broadcast bridge for Breezy/GBWeather/OWMW/Samsung-via-Tasker |
| `xiaomi/services/SystemCommands.kt` | constants from `XiaomiSystemService`, `XiaomiHealthService`, etc. | wire IDs only |
| `healthconnect/HealthConnectExporter.kt` | inspired by `util/healthconnect/*` | HR / SpO₂ / Steps / Sleep records so Fitbit/Samsung Health/Google Fit can read |

## ⏳ Still TODO

| Slice | Why deferred |
|---|---|
| `MiBand9BleDriver.kt` (Android `BluetoothGatt` + V2 protocol orchestrator + Nitro bridge) | The largest single port; couples auth-session, characteristic V1/V2, activity fetcher, and the Nitro `HybridBandLink` surface. |
| Camera/Find Phone via `SystemService` proto messages | needs the BLE driver above |
| Calendar / Music service implementations | needs the BLE driver above |
| Sedentary preference editor RPC | needs the BLE driver above |
| GPS `LocationManager` foreground service | needs the BLE driver above |
| Watchface install (`XiaomiInstallHandler`, `XiaomiWatchfaceService`) | post-MVP |
| Activity fetcher (`XiaomiActivityFileFetcher` + workout summary/GPS parsers) | port partially done (id + parsers); fetcher driver pending the BLE layer |

Effort estimate: a focused day on `MiBand9BleDriver.kt` after the next port pass — that unlocks Camera/Calendar/Music/GPS all at once because they all dispatch through the same V2 channel.

## How to verify right now

```bash
bun install
bun expo prebuild --platform android --no-install
cd android && ./gradlew :app:compileDebugKotlin
# → BUILD SUCCESSFUL, XiaomiProto.java generated, .class files for our port present
```

Running the app: `bun expo run:android` will launch the JS shell. Pairing won't talk to a real band until `MiBand9BleDriver.kt` lands; everything else (theme, onboarding flow, dashboard with "尚未同步" placeholders, sync-status bar) is interactive today.
