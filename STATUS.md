# STATUS — what actually works right now

> A literal answer to "現在是可以用的嗎?" — **the JS/UI shell is usable for design iteration; real BLE features are still TODO.**

Generated: 2026-05-26

---

## ✅ Works today (cold-run from `bun expo run:android`)

| Surface | What you see |
|---|---|
| Boot → onboarding redirect (`app/index.tsx`) | `cache.getSync` decides — no skeleton flash. |
| Onboarding (7 steps) | All screens render. Permissions buttons are best-effort (see below). |
| Language pick → MMKV persists | ✅ |
| Bluetooth permission step | Button advances flow. Real runtime BT prompt happens on first `scan()` (when Kotlin exists). |
| Notification permission step | Tap "前往設定" → Kotlin opens `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` (TODO Kotlin). For now, button is no-op + advances. |
| Battery step | Doc-only; tap advances. |
| Scan / Auth / Done | Scan currently returns `[]` (no native), so the "empty" branch shows. Auth-key paste advances regardless and records `onboardingDone`. |
| Tabs (Today / Notifications / Settings) | Render with empty "尚未同步 / Not synced yet" placeholders. |
| **Live sync status bar** | Subscribes to `bandLink.onSyncProgress`; in safe-mode shows "LAST SYNC · NEVER · TAP TO SYNC". Tap fires `bandLink.syncSince()` (no-op in safe-mode). |
| Theme + locale toggles | ✅ Persist via MMKV. |
| Reanimated ambient blobs / FadeIn cascades | ✅ |
| Vector logo + gradient hero | ✅ |

## ⏳ Not wired (Kotlin Nitro implementations not yet authored)

Every `HybridObject` spec in `modules/native/*` ships a TS spec only. The Kotlin `HybridBandLinkSpec` etc. would normally be **generated** by `nitro-codegen` from these specs, then implemented by hand. We have not run codegen because the published `nitro-codegen` (0.29.4) is older than the installed `react-native-nitro-modules` (0.35.7); we will pin and run it as a follow-up.

| Hybrid | Kotlin TODO |
|---|---|
| `HybridBandLink` | BLE scan/pair flow ported from `XiaomiBleSupport`, `XiaomiBleProtocolV1/V2`, `XiaomiAuthService` |
| `HybridHealthStore` | Local SQLite/MMKV sample storage + parsers ported from `XiaomiSampleProvider*` |
| `HybridNotificationBridge` | `NotificationListenerService` subclass + filter persistence + emoji-aware 24×24 icon downscale |
| `HybridSystemControl` | Find-phone ringer (band → phone), clock sync, preferences |
| `HybridMusicBridge` | `MediaSession` listener + remote control |
| `HybridWeatherBridge` | Push weather frames over BLE (`XiaomiWeatherService` payload schema) |
| `HybridCalendarBridge` | Calendar provider read + push |
| `HybridCameraRemote` | Band shutter → `CameraX` capture (foreground only) |
| `HybridGpsTracker` | Foreground service of type `location\|connectedDevice` + push to band via BLE |
| `HybridSedentary` | Inactivity reminder preferences (FEAT_INACTIVITY) |
| `HybridWeatherProvider` | `BroadcastReceiver` for `ACTION_GENERIC_WEATHER` + OWM polling worker |
| `HybridHealthConnect` | Health Connect client → exports daily samples (Fitbit etc. read from this) |

**Effort estimate**: ~3–5 days of Kotlin work per major Hybrid; the auth + BLE parser pair is the gating item because every other Hybrid depends on a connected `XiaomiSupport` channel.

## How to run the JS shell right now

```bash
bun install
bun expo prebuild --platform android --clean
bun expo run:android   # needs an Android emulator or device + JDK 17 + Android SDK
```

Onboarding will run end-to-end visually. Pair step will not actually find a band until the Kotlin Nitro pieces land — `safe.ts` keeps the shell from crashing in that interim.

## Roadmap to first real device sync

1. Pin `nitro-codegen@^0.35` (when published) or run codegen from the matching tag.
2. Run `bun nitrogen` to emit `HybridBandLinkSpec.kt` etc.
3. Author `HybridBandLink.kt` — BLE GATT, V1/V2 protocol, auth challenge. This is the largest port.
4. Author `HybridHealthStore.kt` — sample persistence.
5. Author `HybridNotificationBridge.kt` + `MiBand9NotificationListenerService`.
6. Add WorkManager periodic worker as documented in `docs/POWER.md`.
7. Author `HybridHealthConnect.kt` so Fitbit/Samsung Health/Google Fit can read.
8. Author `HybridGpsTracker.kt` + `HybridCameraRemote.kt` + `HybridWeatherProvider.kt` + `HybridSedentary.kt`.
9. Replace `safeCall`/`safeAsync` defaults with surfaced errors (the wrappers were a dev-time convenience).

Until step 3 lands, **don't expect actual heart-rate numbers on the dashboard**. The numerals will stay '—' and the screens will keep saying "尚未同步 / Not synced yet" — which by Rule 8 of `CLAUDE.md` is the correct behavior.
