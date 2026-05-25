# mi-band-9-active — contributor playbook for Claude

A slim Mi Band 9 Active companion app built on **Expo SDK 56 + RN 0.85 + Reanimated 4 + Nitro Modules**. AGPL-3.0-or-later because we port from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge).

**Platform**: Android only. We do not build, ship, or test on iOS. `app.json` declares `"platforms": ["android"]` and Nitro specs only declare a `kotlin` target. Do not re-add Swift.

**Background work is power-budget gated.** See `docs/POWER.md` — the rules there are not suggestions. No foreground service outside an active sync, no WAKE_LOCK, no background-location, periodic interval ≥ 30 min, realtime HR off by default.

See `FEATURES.md` for the full feature scope. See `NOTICE.md` for upstream attribution.

## Project layout

| Path | Purpose |
|------|---------|
| `app/` | Screens (Expo Router file-based routing). `(onboarding)`, `(settings)` are stacks. |
| `components/themed/` | **Theme-aware primitives. Default to these for new UI.** `ThemedButton`, `ThemedText`, `ThemedSurface`, `contrast.ts`. |
| `components/common/` | Older shared bits if any creep back in. Prefer `themed/`. |
| `components/<feature>/` | Feature-scoped components (`onboarding/`, `notifications/`, `dashboard/`, `pair/`). |
| `constants/DesignSystem.ts` | `Colors`, `Spacing`, `Radius`, `Typography`, `IconSize`, `Size`, `Shadow`. Static tokens. |
| `context/ThemeContext.tsx` | Single source of truth for theme palette, mode, custom accent, tint intensity, contrast. |
| `libs/services/` | JS-side facades (`bandLink.ts`, `notificationBridge.ts`, `healthStore.ts`, `cache.ts`). All native calls go through these. |
| `modules/native/` | Nitro HybridObject TS specs + Kotlin/Swift implementations. **No screen calls Android APIs directly.** |
| `locales/` | `zh-Hant.json` + `en.json`. Two locales, period. |
| `__tests__/unit/` | Bun unit tests. Run with `bun test`. |

## Commit style — strict

`feat(scope): summary in present tense`, **whole line ≤ 120 chars**. Other allowed prefixes: `fix`, `chore`, `refactor`, `docs`, `style`, `perf`, `test`, `build`, `ci`. **Every meaningful step commits** so the history reads like a build log.

Examples:

- `feat(onboarding): wire bluetooth permission step with reanimated transition`
- `feat(nitro): scaffold HybridBandLink spec covering scan/pair/sync`
- `fix(notifications): drop double-encoded emoji on filter list`

## Theme system — how it works

`useTheme()` returns:

```ts
{
  theme: ThemePalette   // accent, accentLight, accentDark, secondary,
                        // background.{primary,secondary,tertiary},
                        // text.{primary,secondary,tertiary},
                        // glassBorder, gradient
  themeId: ThemeId
  themeMode: 'light' | 'dark' | 'auto'
  tintIntensity: 'subtle' | 'balanced' | 'vivid'
  increaseContrast: boolean
  customAccent: string | null
  setTheme / setThemeMode / setCustomAccent / setTintIntensity / setIncreaseContrast
}
```

The resolved `theme.accent` already accounts for `customAccent` and `tintIntensity`. **Never re-implement that logic.**

## Mandatory rules — read before writing UI code

These rules exist because we had real bugs (invisible button labels on light accents, button-size whiplash between onboarding steps, four reinvented `PrimaryButton`s drifting apart, skeleton flashing on cache hits). Skip them and you reintroduce those bugs.

### 1. Buttons → `ThemedButton` / `ThemedIconButton`

```tsx
import { ThemedButton, ThemedIconButton } from '@/components/themed';

<ThemedButton label="繼續" onPress={next} size="lg" fullWidth />
<ThemedButton variant="secondary" label="稍後" onPress={skip} />
<ThemedButton variant="destructive" label="解除配對" onPress={unpair} />
<ThemedButton variant="ghost" label="略過" onPress={skipAll} />
```

**Do not write a new `PrimaryButton` / `CtaButton` / `ActionButton` per screen.** Extend `ThemedButton` if you genuinely need a new variant — but talk it through first.

#### Sizing
- `sm` (36 min-height) — chips, inline actions.
- `md` (44 min-height, default) — iOS HIG / Material touch target.
- `lg` (52 min-height) — hero CTAs, onboarding "Continue", paywall.

#### Stretching
- `fullWidth` uses `alignSelf: 'stretch'` so the button takes width but **never grows in height**. Fixes the onboarding bug where `flex: 1` on a single button made it fill the screen.
- Two side-by-side buttons in a row: wrap each in `<View style={{ flex: 1 }}>` and pass `fullWidth` on the inner `ThemedButton`.

#### Text color on accent backgrounds — **never hardcode**

`ThemedButton` calls `readableTextOn(accent)` to pick white vs. near-black. On a light accent, white text drops to 1.07:1 contrast. The helper enforces WCAG AA-large (3:1) and flips automatically.

### 2. Text → `ThemedText`

```tsx
<ThemedText variant="headlineLarge">儀表板</ThemedText>
<ThemedText variant="bodyMedium" tone="secondary">本機儲存,不上雲。</ThemedText>
<ThemedText variant="caption" tone="error">同步失敗</ThemedText>
```

**Never** use raw `fontSize: 14, fontWeight: '600'`. Bypasses our scale and breaks future Dynamic Type.

### 3. Surfaces / cards → `ThemedSurface`

```tsx
<ThemedSurface variant="card" padded>
  <ThemedText>…</ThemedText>
</ThemedSurface>
```

### 4. Colors come from `useTheme()`, never hardcoded hex

Hex literals are allowed only for: brand source colors in `ThemeContext.tsx`, the universal `ON_DARK`/`ON_LIGHT` text constants, and platform brand colors.

### 5. Don't break `themeMode` / `tintIntensity` / `increaseContrast`

Derive from `theme.*` so the user's choice propagates.

### 6. Touch targets

Minimum `44 × 44`. `ThemedButton size="md"` already meets this.

### 7. Haptics

Use `hapticsBridge` (`selection` for choices, `tap` for nav, `success` for finalizing, `warning` for risky). `ThemedButton` calls the right one by default.

---

### 8. No fake data — ever ⭐ (ported rule from aniseekr-expo)

Anything that looks like real, computed, device-specific, user-specific, or source-of-truth data **must come from a real source** — meaning a Nitro module call, MMKV cache, or route param. If the source isn't available, the UI shows a real loading or error state — never a plausible-looking placeholder.

Why this rule: in the previous project we shipped `fallbackAnalysisFromUrl()` — a function that hashed a URL into "plausible" RGB averages so tiles always rendered something. The tiles looked correct and were completely meaningless. Same class of bug: **content that pretends to know something it doesn't**.

In **this** codebase the rule maps to:

- ❌ **Hash/seed → plausible heart rate / step / sleep numbers** while the band is disconnected. If `bandLink.state === 'disconnected'` and we have no cached sample, the dashboard card renders `'—'` and a "尚未同步" / "Not synced yet" subtitle, not `72 bpm`.
- ❌ **Hardcoded sample workouts** (`const SAMPLE_WORKOUT = { kcal: 320, … }`) on the render path. Mocks live under `__tests__/` or behind a `__DEV__` flag, never shipped.
- ❌ **Lorem-ipsum body text** on a notifications preview. Either show the real notification or render an empty state.
- ❌ **Fake battery %, fake sync timestamp** computed from `Math.random()` or `Date.now() % 100`.
- ❌ **Scene-specific copy** baked into a generic screen (e.g. "你今天比昨天多走 2,341 步,真棒!" with a number that doesn't actually come from the diff).

The three real states for any data-driven card:

| State | What to render |
|-------|----------------|
| `loading` | Skeleton / "同步中…" / spinner — clearly transient |
| `ready` | The real cached or live value |
| `error` / `null` | "—" / "尚未同步" — clearly *no data*, not a guess |

Ask yourself: "would a screenshot of this card mislead the user about what we actually know from the band?" If yes, it's fake data.

Generic guidance is fine (the `BatteryCard` saying "充電到 80% 後拔掉可延長電池壽命" is photography-knowledge-equivalent: domain advice, not pretending to know the band's current state).

---

### 9. State ownership → keep render state small and local ⭐ (ported rule)

React state is for values that must change rendered JSX. Do **not** put every BLE event, sensor tick, gesture value, cache snapshot, and async phase into the screen root.

Use the narrowest owner for each kind of state:

| State kind | Default owner |
|------------|---------------|
| BLE notification packet streams, realtime HR ticks | `useRef` + a throttled `useState` mirror only for the visible value |
| Reanimated gesture / progress / shared values | `SharedValue` (never mirror into React state per frame) |
| In-flight flags, cancellation tokens, BLE-connection refs | `useRef` |
| Derived values | `useMemo` or plain constants, **not** mirrored `useState` |
| Persisted preferences, paired-band info, auth key | MMKV (`libs/services/cache.ts`) + a feature hook with a small public API |
| Modal/sheet open state | Local state in the smallest component that owns the trigger |
| Large async resource (`data/loading/error`) | One reducer or feature hook, not three setters in the screen |

For the dashboard and the live-HR sheet specifically:

- High-frequency BLE notifications (`heart_rate_realtime`, `step_realtime`, `connection_rssi`) must stay off the React render path. Use a `useRef` ring buffer + a 250–500ms throttled mirror for the display number.
- The route screen should orchestrate feature hooks; it should not own every card's loading state, every settings sheet, every chart toggle directly.
- If adding a new dashboard card requires another top-level `useState` in `app/(tabs)/index.tsx`, first ask whether it belongs in a feature hook, a `SharedValue`, a child component, or a reducer.
- Avoid effects whose only job is to reconcile state that could have been derived. If reconciliation is necessary, keep it close to the state it fixes and guard against redundant setter calls.
- Before optimizing, profile or at least count render-triggering state changes. Fix the state with the largest render fan-out, not the one that's visually nearby.

---

### 10. Navigation feel → never `await` on the first-paint path ⭐ (ported rule)

Skeletons are for **cold** loads only. If a skeleton flashes when the data is already in MMKV, that's a bug. Discord's "Supercharging Discord Mobile" is the reference.

**Budget**: tap → first frame must do <16ms of JS and show real chrome (header, last-sync timestamp, last cached numbers), not a skeleton.

**Rules**:

1. **Sync cache on the render path.** `cache.getSync<T>(key)` returns the MMKV value or `null` — call it inside `useState(() => …)` so initial state is non-null on warm hits. `await cache.get()` is for background revalidation only. Render shape: `data ?? <Skeleton/>`, not `loading ? <Skeleton/> : data`.
2. **Route params carry chrome.** List → detail must pass `{ id, title, lastSyncedAt, batteryPct? }` via `router.push({ pathname, params })`. The detail screen reads them from `useLocalSearchParams()` and paints the hero on frame 1, before any I/O.
3. **`useFocusEffect` is a refresh trigger, not a load trigger.** Guard with `lastLoadedKey === currentKey` and skip; if you must revalidate, do it silently — never clear state and re-show a skeleton.
4. **Don't wrap I/O in `InteractionManager.runAfterInteractions`.** That defers the network/BLE call itself, so warm hits also wait for the push animation. Defer the *expensive child state setter* via `requestAnimationFrame`, never the fetch.
5. **Stale-while-revalidate** — render stale, refresh silently. Only surface a "重新同步中…" affordance after ~500ms.
6. **Prefetch on press-in** for list items, not on mount of the next screen.
7. **Don't add `unmountOnBlur` / new top-level Context providers.** Tabs stay mounted is the feature. New cross-screen state goes in a feature store with selector subscription.

**Checklist for any new/touched screen**:

- [ ] Warm cache hit → frame 1 shows real chrome, not skeleton
- [ ] Tab re-focus with snapshot → no visible reload
- [ ] Zero `await`s between mount and first paint
- [ ] `loading` initial value derives from sync cache miss, not `true`
- [ ] List that links here calls prefetch on press-in

---

## Native bridge — Nitro Modules

The JS layer **never** touches `BluetoothGatt`, `NotificationListenerService`, or Xiaomi protocol bytes directly. All native interaction goes through Nitro HybridObjects declared in `modules/native/*/spec.ts` and implemented in Kotlin under `android/src/main/java/.../mibandactive/`.

If you find yourself writing `requireNativeModule('Xiaomi…')`, stop — that's TurboModules, not Nitro. Use `HybridObject` from `react-native-nitro-modules`.

If you need a new native capability:

1. Add the method to the TS spec under `modules/native/<area>/spec.ts`.
2. Re-run `bun nitrogen` to regenerate the Kotlin/Swift glue.
3. Implement the Kotlin side.
4. Expose a JS-side facade in `libs/services/<area>.ts`.
5. Commit each step.

## Anti-patterns — don't repeat these

- **Per-screen `const BG = '#0A0A0A'`** → use `theme.background.primary`.
- **Reinventing `PrimaryButton` per file** → use `ThemedButton`.
- **Inline `<Text style={{ fontSize: 17, fontWeight: '600' }}>`** → use `<ThemedText variant="titleLarge">`.
- **`shadowColor: '#000'` everywhere** → `Shadow.subtle/.medium/.heavy` from `DesignSystem`.
- **Hash-seeded "plausible" placeholders** → return `null` and render an error state. See Rule 8.
- **Hardcoded sample heart-rate / step data in production paths** → `__tests__/` or `__DEV__` flag only. See Rule 8.
- **20+ `useState` at the top of a screen** → split into feature hooks / reducers / refs. See Rule 9.
- **High-frequency BLE notifications mirrored into React state every packet** → ring buffer + throttle. See Rule 9.
- **`setLoading(true)` + `await cache.get()` on mount** → seed via `cache.getSync()`. See Rule 10.
- **`useFocusEffect` that unconditionally refetches** → guard with `lastLoadedKey`. See Rule 10.
- **`InteractionManager.runAfterInteractions` around a BLE call** → defer the setter, not the I/O. See Rule 10.
- **Calling `requireNativeModule(...)` outside `modules/native/`** → use a Nitro HybridObject instead.

## Workflow

```bash
bun install                        # install deps
bun test                           # run unit tests
bun test __tests__/unit/foo.test.ts   # single file
bunx tsc --noEmit                  # type check (no emit)
bun lint                           # eslint
bun expo prebuild                  # generate ios/ + android/
bun expo run:android               # device pairing needs a real phone
```

When changing `components/themed/*`, add or update tests under `__tests__/unit/themed-*.test.ts`.

## License reminder

Every new source file gets this header:

```
/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) {year} {your name}
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */
```

Ported files keep the **original** Gadgetbridge `Copyright (C)` line **above** ours.
