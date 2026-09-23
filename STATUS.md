# STATUS

Updated: 2026-09-24 · upstream reference: Gadgetbridge `75f923904f` (master)

Every area below was re-audited line-by-line against the upstream Java and
fixed where it diverged. **Compiled ≠ verified**: the column on the right is
what still needs a real Mi Band 9 Active to confirm. Treat anything not in
"Verified on device" as untested.

## Build

```bash
bun install
bunx expo prebuild --platform android --clean --no-install
bunx nitrogen
cd android && ./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a,armeabi-v7a
```

Expo SDK 57 · RN 0.86.3 · Reanimated 4.5 · Nitro 0.35 · Kotlin 2.1.20 · minSdk 26 / target 36.
Release APKs are built by `.github/workflows/release.yml` on `v*` tags.

## Areas

| Area | Kotlin | Key upstream fixes in this pass | Needs on-device check |
|---|---|---|---|
| Transport (V2 framing, auth, reconnect) | `xiaomi/protocol/*`, `auth/*`, `DriverHolder`, `BandStore` | activity channel is raw bytes (was parsed as protobuf); per-link reset; whole-frame write queue with retry; handshake on any session config; device info encrypted with counter 0; passive `autoConnect` reconnect; persisted band | wrong key → `AUTH_REJECTED`; bond-after-pair; reconnect after range loss / BT toggle; status 133 |
| Activity sync | `sync/ActivitySync`, `xiaomi/activity/*`, `SampleStore` | parser dispatch by (type, subtype, detail); daily summary v4 + validity bitmap; sleep details; manual samples v1; workout GPS; sleep stage map (2=deep, 3=light); ack only after parse; SQLite upserts, local-day buckets | file versions the band actually sends; chunk pacing |
| Health Connect | `healthconnect/*`, `HybridHealthConnect` | stable record ids, per-instant zone offsets, correct permissions per record, permission sheet via Activity result registry | permission sheet on Android 14+; upserts |
| Notifications | `xiaomi/notifications/*` | upstream timestamp/key/app-name; dismiss sync both ways; open-on-phone; icon query/request/upload (ARGB8565 byte order, exact size); listener filters (ongoing, group summary, media, low importance, DnD) | emoji, icon format, Samsung/MIUI dialers |
| Calls | `calls/CallAlertEngine` | incoming call → band; reject (`endCall`) / silence from the band; phonebook lookup | with/without READ_PHONE_STATE / ANSWER_PHONE_CALLS |
| Music | `media/MediaSessionTracker` | correct ids (GET 0 / SEND 1 / BUTTON 2); answers band's info request; native media-session dispatch; real volume | band music screen + buttons |
| System | `xiaomi/services/SystemService`, `DeviceFeatures` | clock with 12/24h + DST; lower-case language codes; camera shutter no longer fires on our own ack; find phone with stop action | camera shutter event id (upstream has none) |
| Health prefs | `HealthSettingsService`, `HybridSedentary` | HR interval in seconds; SpO₂ numeric all-day mode; sedentary is 8/12–13 `StandingReminder` (was an empty command on 29) | SpO₂ mode sticks |
| Alarms / reminders / sleep mode | `ScheduleService`, `HybridSchedule` | new: list/create/update/delete, smart wake, slot count from band | alarm edit/delete; smart wake |
| Weather | `WeatherService`, `GenericWeatherReceiver`, `OwmWeather` | current + daily + hourly, Beaufort wind, condition mapping, cloudy icon fix, Kelvin handling, Gadgetbridge broadcast action | band accepts no-UV current; multi-location |
| Calendar | `CalendarService` | subtype 1 (was 0); CalendarContract, 7 days, first-alert reminder | — |
| GPS workouts | `gps/*` | answer band's open-workout request; stream only while the band's workout runs; `TYPE_LOCATION` FGS | Android 14 background FGS start |
| Watch faces | `WatchfaceService`, `MiBand9DataUploader` | id from file header; ack subscribed before send; timeout + one upload at a time | real install end-to-end |

## Verified on device

Nothing from this pass yet.

## Out of scope (upstream `MiBand9ActiveCoordinator` says unsupported)

Find band, manual HR, world clocks, custom vibration patterns, wrist-raise/band-DND (no upstream implementation).
