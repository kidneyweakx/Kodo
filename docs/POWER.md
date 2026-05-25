# Power-saving strategy (Android-only)

This project only ships to Android. Every battery decision below is enforced both in the JS facades (`libs/services/*`) and in the Kotlin Nitro implementations under `android/src/main/java/.../mibandactive/` (to be authored).

---

## Non-negotiables

1. **No always-on foreground service.** A foreground service is started **only** during an active sync window (≤ 60s typical) and **stopped immediately** when the sync resolves. Connecting → syncing → disconnect is one atomic job.
2. **No `WAKE_LOCK`.** WorkManager wakes the device for us. We never pin the CPU on ourselves.
3. **No background location permission.** Android's BLE-scan needs `ACCESS_FINE_LOCATION` *only while in foreground*. We do not request `ACCESS_BACKGROUND_LOCATION`.
4. **Periodic sync interval ≥ 30 min** (`PeriodicWorkRequest`, minimum 15 min by Android rules, we use 30). User can extend this but cannot shorten below 30 min.
5. **Realtime HR streaming OFF by default.** It is the single biggest power sink on the band and on the phone radio. Users must explicitly opt in per-session.

## BLE scan policy

| Phase | Duration | Duty cycle | Window |
|---|---|---|---|
| Onboarding scan (foreground) | 12 s max | continuous | one-shot |
| Auto-reconnect (background, screen off) | 1.2 s active / 12 s idle | ≤ 10% | only during scheduled work window |
| Manual re-pair | 12 s max | continuous | user-triggered |

We never start a background `BluetoothLeScanner` that runs > 15 s. The Kotlin implementation must call `stopScan()` on a Handler-posted timeout, not on the assumption the OS will preempt.

## WorkManager schedule

| Job | Cadence | Constraints |
|---|---|---|
| `DailySummarySyncWorker` | every 30 min (periodic) | `requiresBatteryNotLow=true`, no network constraint |
| `NotificationFlushWorker` | one-time, expedited, on NLS event | `setExpedited()` only when band is already connected; otherwise enqueue normal |
| `WeatherPushWorker` | every 6h | `requiresBatteryNotLow=true`, network connected |
| `CalendarPushWorker` | every 6h | `requiresBatteryNotLow=true` |

`requiresBatteryNotLow=true` means: when the phone is under 15% battery, we skip the run. The user can still pull-to-refresh manually on the dashboard.

## Doze / App Standby

We **assume Doze**. We do not ask for "Ignore battery optimization" in the onboarding flow (it's a soft prompt only, skip is OK). The work pattern is designed to fire during maintenance windows that Doze itself grants.

If the OEM is aggressive (MIUI, ColorOS, EMUI), we let the user know via an in-app banner: "你的廠商可能會在背景殺掉同步,設定 → 電池 → 不限制本 App / Your OEM may kill background sync — exempt this app in system settings."

## Notification forwarding

`NotificationListenerService` callbacks are short. We:

- Drop notifications that are not in the user's allow-list **before** waking the BLE stack.
- Coalesce a 250ms window of incoming notifications into a single BLE write.
- Downscale notification icons to **24×24 PNG** before send (Gadgetbridge sends 40×40+; we save flash + RF time).

## What we do NOT do (and won't add)

- ❌ Persistent foreground service "for reliability".
- ❌ `JobIntentService`, `AlarmManager.setExactAndAllowWhileIdle()` polling loops.
- ❌ Background BLE scan with `SCAN_MODE_LOW_LATENCY`.
- ❌ Continuous heart-rate streaming as the default.
- ❌ Asking for `ACCESS_BACKGROUND_LOCATION`.

## Auditing

There's a Logcat tag prefix `MB9A_POWER` on every wake-up. Run `adb shell dumpsys batterystats` after a 24h soak to verify the per-app wake count stays under 50.
