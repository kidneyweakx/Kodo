# mi-band-9-active

> 專為 **小米 Smart Band 9 Active** 打造的瘦身版開源伴侶 App / A slim, single-device companion app for the **Xiaomi Smart Band 9 Active**.

Built on Expo SDK 56 + React Native 0.85 + Reanimated 4. All native BLE / Xiaomi-protocol logic is wrapped behind [Nitro Modules](https://nitro.margelo.com/) — the JS layer never touches `BluetoothGatt` directly.

## Why this fork?

[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) is the gold standard for free Android wearable support, but it is a multi-vendor giant. For users who only own the **Mi Band 9 Active**, ~95% of the binary is dead code that still costs RAM, scan-filter time, and battery.

This project ships:

- One device coordinator. One protocol stack. One UI.
- Only **Traditional Chinese** and **English**.
- Defaults tuned for **battery life over feature completeness**.
- A polished Expo UI that follows the design rules from our internal `aniseekr-expo` playbook.

See [`FEATURES.md`](./FEATURES.md) for the full feature inventory and the explicit `cut` list.

## License

**AGPL-3.0-or-later.** This is a derivative work of Gadgetbridge (AGPL-3.0-or-later). See [`LICENSE`](./LICENSE) and [`NOTICE.md`](./NOTICE.md).

## Project status

Early scaffolding. Not yet usable.

## Quickstart (once the Expo app lands)

```bash
bun install
bun expo prebuild
bun expo run:android        # device pair flow needs a real phone
```

## Repo layout

```
.
├── app/                # Expo Router screens
├── components/         # ThemedButton / ThemedText / ThemedSurface and feature components
├── constants/          # DesignSystem tokens
├── context/            # ThemeContext, BandContext
├── libs/services/      # JS-side facades over Nitro modules
├── modules/native/     # Nitro HybridObject TS specs + Kotlin implementations
├── locales/            # zh-Hant / en
├── vendor/Gadgetbridge/       # upstream source, kept as reference (gitignored)
├── FEATURES.md
├── NOTICE.md
├── LICENSE             # AGPL-3.0-or-later
└── CLAUDE.md           # internal contributor playbook
```
