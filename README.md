# Kodō (鼓動)

> **Slim, single-device, local-first companion for the Xiaomi Smart Band 9 Active.**
> 專為 **小米 Smart Band 9 Active** 打造的瘦身版開源伴侶 App。

[![License: AGPL-3.0-or-later](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue.svg)](./LICENSE)
[![Platform: Android](https://img.shields.io/badge/platform-Android-3DDC84.svg)](https://www.android.com/)
[![Built on Expo](https://img.shields.io/badge/Expo-SDK%2056-000020.svg)](https://expo.dev/)
[![Nitro Modules](https://img.shields.io/badge/Nitro-0.35-7C3AED.svg)](https://nitro.margelo.com/)

Kodō (Japanese **鼓動** — "heartbeat") is a tiny, modern, fully local companion app for the **Xiaomi Smart Band 9 Active**. It is a focused Expo / Nitro Modules port of [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), reduced to a single device family and rebuilt around a small, battery-conscious React Native UI.

No cloud account. No telemetry. No vendor SDK. Just BLE, Health Connect, and your wrist.

---

## Why fork Gadgetbridge?

Gadgetbridge is the gold standard for free wearable support, but it is a multi-vendor giant. For users who only own the Mi Band 9 Active, ~95% of that binary is dead code that still pays for itself in RAM, scan-filter time, and battery drain.

Kodō ships exactly one device coordinator, one protocol stack, two locales, and one polished UI — and nothing else.

- **One device.** Only `Xiaomi Smart Band 9 Active ####` BLE adverts match.
- **Two locales.** Traditional Chinese (`zh-Hant`) and English. No runtime language downloads.
- **Defaults tuned for battery life**, not feature completeness — periodic sync ≥ 30 min, realtime HR off, no `WAKE_LOCK`, no background-location.
- **Local-first.** All samples stay on device. Optional one-way export to Health Connect (Fitbit / Samsung Health / Google Fit read from there).

See [`FEATURES.md`](./FEATURES.md) for the full feature inventory and the explicit cut list, and [`docs/POWER.md`](./docs/POWER.md) for the power-budget rules.

---

## Status

**13 / 13 Nitro HybridObjects wired end-to-end. Debug APK builds clean.** See [`STATUS.md`](./STATUS.md) for the verification matrix.

| Capability                                | State |
|-------------------------------------------|-------|
| BLE scan / encrypted V2 auth handshake    | ✅    |
| Activity sync (HR / SpO₂ / Stress / Sleep / Steps / kcal / dist) with per-file ack | ✅ |
| Periodic 30-min background sync via WorkManager (`requiresBatteryNotLow=true`) | ✅ |
| Notification mirror + per-app filter + icon upload (RGB565/ARGB8565) | ✅ |
| Watchface list / install / activate / delete (md5 + crc32 chunked upload) | ✅ |
| Weather push (OWM + `ACTION_GENERIC_WEATHER` intake from Breezy / GBWeather) | ✅ |
| Calendar / Music / Camera-shutter / GPS-during-workout | ✅ |
| Health Connect export (Steps / HR / SpO₂ / Sleep / kcal / distance) | ✅ |
| Clock + language push (`zh_TW` / `en_US`) | ✅ |
| Find phone (band → phone)                 | ✅    |
| Find band (phone → band)                  | ❌ hardware doesn't support |

---

## Quickstart

Kodō is **Android only**. iOS is not built, shipped, or tested.

```bash
bun install
git submodule update --init                       # pulls vendor/Gadgetbridge reference
bun expo prebuild --platform android --no-install --clean
bunx nitrogen                                     # generate Kotlin/C++ glue from .nitro.ts specs
cd android && ./gradlew :app:assembleDebug        # → app-debug.apk
# or for an installed dev build on a connected phone:
bun expo run:android
```

Useful day-to-day commands:

```bash
bun test                       # bun unit tests
bunx tsc --noEmit              # type-check
bun lint                       # eslint
```

A real Mi Band 9 Active and a real Android phone are required to exercise pairing, sync, and notifications. The Android emulator is fine for UI work but cannot scan BLE adverts.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  React Native (Expo SDK 56 + Reanimated 4 + Expo Router)     │
│    components/themed/   →  ThemedButton / Text / Surface     │
│    components/<feature> →  feature-scoped UI                 │
│    libs/services/       →  JS facades (the only thing apps   │
│                            ever import for native calls)     │
└──────────────────┬───────────────────────────────────────────┘
                   │  Nitro HybridObjects (TS specs)
                   ▼
┌──────────────────────────────────────────────────────────────┐
│  Kotlin engine (android-port/)                               │
│    MiBand9BleDriver  ·  XiaomiAuthSession  ·  V2 packets     │
│    XiaomiActivityFileFetcher + parsers (HR/SpO₂/Sleep/…)     │
│    MiBand9DataUploader (watchface / icon / firmware)         │
│    MiBand9NotificationListener  ·  HealthConnectExporter     │
│    MiBand9PeriodicSyncWorker (WorkManager, 30 min)           │
│    MiBand9GpsService (foreground, only during a workout)     │
└──────────────────────────────────────────────────────────────┘
```

The React layer never calls `BluetoothGatt` directly. Every native capability is exposed through exactly one Nitro HybridObject (`modules/native/<area>/spec.ts`) regenerated by `bunx nitrogen`.

Detailed mapping `Gadgetbridge class → Nitro HybridObject → JS facade` lives in [`FEATURES.md`](./FEATURES.md#mapping-gadgetbridge--nitro-module--js-api).

---

## Repo layout

```
.
├── app/                  # Expo Router screens (onboarding / settings / dashboard)
├── components/
│   ├── themed/           # ThemedButton / ThemedText / ThemedSurface — use these
│   ├── brand/            # logo lockup, animated marks
│   └── <feature>/        # onboarding, notifications, dashboard, pair
├── constants/            # DesignSystem tokens (Colors / Spacing / Radius / Shadow / …)
├── context/              # ThemeContext (palette + mode + tint intensity + contrast)
├── libs/services/        # JS facades over Nitro modules
├── modules/native/       # Nitro HybridObject TS specs (Kotlin glue is generated)
├── android/              # Generated by `expo prebuild` — do not hand-edit
├── android-port/         # Kotlin engine ported from Gadgetbridge
├── nitrogen/             # Generated specs (kotlin + c++) — checked in
├── locales/              # zh-Hant.json + en.json (two locales, period)
├── docs/                 # POWER.md and friends
├── vendor/Gadgetbridge/  # Upstream source kept as reference (git submodule, gitignored)
├── FEATURES.md           # Full feature inventory + cut list
├── STATUS.md             # Build + wiring verification matrix
├── NOTICE.md             # Upstream attribution
├── LICENSE               # AGPL-3.0-or-later
└── CLAUDE.md             # Contributor playbook (themed primitives, no-fake-data, etc.)
```

---

## Privacy

- No analytics SDK. No crash-reporter SaaS. No remote logging.
- No account. No cloud sync.
- Samples (HR / SpO₂ / Stress / Sleep / Steps) are persisted to the app's private storage via MMKV.
- The only optional egress is **Health Connect** export, which you opt into per record type.
- Network calls happen for weather only, and only if you wire an OpenWeatherMap API key or a third-party broadcast source.

---

## Contributing

Read [`CLAUDE.md`](./CLAUDE.md) first — it is the contributor playbook. Highlights:

- Use `ThemedButton` / `ThemedText` / `ThemedSurface` for **all** new UI. Don't reinvent a `PrimaryButton` per screen.
- **No fake data, ever.** If the band hasn't synced, render `—`, not a plausible number.
- **No `await` between mount and first paint.** Read MMKV synchronously via `cache.getSync()` on warm hits.
- Commit style: `feat(scope): summary` — present tense, ≤ 120 chars.
- Every native capability lives behind a Nitro HybridObject; JS never touches `BluetoothGatt`.

By contributing, you agree to license your contribution under AGPL-3.0-or-later.

---

## License

**GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later).**

Kodō is a **derivative work** of Gadgetbridge (AGPL-3.0-or-later). Every ported file retains its original `Copyright (C) … <author>` header; new files carry our own header that references this notice. See [`LICENSE`](./LICENSE) for the full text and [`NOTICE.md`](./NOTICE.md) for upstream attribution and your obligations as a distributor.

Because of AGPL §13, if you modify Kodō **and** let users interact with it over a network, you must offer those users the modified source.

"Xiaomi", "Mi", "Mi Band", and "Smart Band" are trademarks of Xiaomi Inc. Kodō is **not** affiliated with, endorsed by, or sponsored by Xiaomi.
