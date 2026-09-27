# Power-saving strategy (Android-only)

This project only ships to Android. Every battery decision below is enforced in the Kotlin engine under `android-port/src/main/java/com/kidneyweakx/miband9active/` and the JS facades in `libs/services/*`.

---

## Non-negotiables

1. **Persistent link, passive only.** While a band is paired we keep one GATT connection open so notifications, calls, music, find-phone and band-initiated GPS reach the phone the moment they happen. This is what Gadgetbridge does. It costs almost nothing because:
   - It is a normal GATT client with **no foreground service**, **no scanning** and **no wake lock**.
   - After a clean drop (status 0, 8 = out of range / supervision timeout, 19, 22) we re-arm `connectGatt(autoConnect = true)` **immediately**, while the disconnect callback still holds the CPU. A Handler delay would not fire during suspend. After a stack error (129/133/147) we wait 5 s, then back off 5 s → 10 min. The Bluetooth controller reconnects when the band returns; the app does no polling.
   - Every process start re-arms the reconnect without scanning: reboot (notification-listener rebind or WorkManager's boot reschedule), an OEM kill, or a worker wake-up.
   - A user disconnect is persisted. Process restarts and the worker respect it until the next explicit connect or pair.
   - Reconnect stops on a wrong key or missing permission, and pauses while Bluetooth is off. It resumes when Bluetooth comes back.
   - Everything is tagged `MB9A_POWER` in logcat.
   - **Keep-alive anchor:** the bound `NotificationListenerService` keeps the process out of Android's cached/frozen state. Without notification access the link is best-effort. Settings › Connection & power shows whether the listener is connected.
2. **Foreground services only while the user is doing something.** The only one is `MiBand9GpsService` (`FOREGROUND_SERVICE_TYPE_LOCATION`). It runs only while a phone-GPS workout is active and stops on workout end, or 2 min after the band disconnects. Sync never uses a foreground service.
3. **We never acquire a `WAKE_LOCK`.** WorkManager's merged manifest declares the permission for its own scheduler; none of our code holds one.
4. **No background location permission.** BLE scanning needs `ACCESS_FINE_LOCATION` only in the foreground (onboarding / re-pair), and GPS workouts run inside the foreground service above. We never request `ACCESS_BACKGROUND_LOCATION`.
5. **Periodic sync interval ≥ 30 min** (`PeriodicWorkRequest`; Android's minimum is 15, we clamp to 30 natively). The user can lengthen it but not shorten it.
6. **Realtime HR streaming off by default.** It is the single biggest power sink on the band and the phone radio. When the user turns it on, it stops automatically when the last listener unsubscribes, on disconnect, and after 5 minutes.

## BLE scan policy

| Phase | Duration | Mode | Trigger |
|---|---|---|---|
| Onboarding scan (foreground) | 12 s max, ends early on `stopScan()` | `SCAN_MODE_LOW_LATENCY` | user on the "Find your band" step |
| Re-pair from Settings | 12 s max | `SCAN_MODE_LOW_LATENCY` | user-triggered |
| Reconnect | — | **no scan**: passive `autoConnect` | automatic |

We never run a background `BluetoothLeScanner`. Scans stop on a Handler-posted timeout, never on the assumption that the OS will preempt them.

## WorkManager schedule

| Job | Cadence | Constraints |
|---|---|---|
| `MiBand9PeriodicSyncWorker` | every 30 min (user: 30 min–4 h) | `requiresBatteryNotLow`. Uses the existing link (connects only if needed) → `ActivitySync` (one sync at a time app-wide) → Health Connect export of yesterday and today. The setting is persisted natively and re-applied with `KEEP` at process start. The job skips runs after a user disconnect and cancels itself when no band is paired. |
| `OwmWeatherWorker` | every ≥ 6 h, **only if** the user entered an OpenWeatherMap key | network connected, `requiresBatteryNotLow` |
| `CalendarPushWorker` | every 6 h, only when calendar sync is on | `requiresBatteryNotLow`; pushes only if already connected |

With `requiresBatteryNotLow` the job is skipped when the phone is below ~15 %. The user can still sync manually from the dashboard or Settings.

Weather received from other apps (the Gadgetbridge `ACTION_GENERIC_WEATHER` broadcast), calendar, health preferences, alarms and the clock are also pushed when the band connects. These ride on the existing link, so no extra wake-up is needed.

## Doze / App Standby

We **assume Doze**. Exempting the app from battery optimisation is optional: it appears in the onboarding checklist and in Settings › Sync & data, with its real status, and can be skipped. Periodic work runs in the maintenance windows Doze grants.

On aggressive OEM ROMs (MIUI, ColorOS, EMUI) the Sync & data page shows whether the app is still restricted and links straight to the exemption screen.

## Notification forwarding

`NotificationListenerService` callbacks are short. We:

- Forward **only allow-listed apps**. The list is strict: nothing is forwarded until the user switches an app on. Everything else is dropped **before** the BLE stack is touched.
- Drop ongoing, group-summary, local-only, media, low-importance and own-app notifications, plus repeats of the same content.
- Drop notifications while the band is disconnected. The listener never starts a connection.
- Coalesce a 250 ms window of incoming notifications into one BLE write.
- Send icons at the exact size and format the band asks for (upstream `XiaomiBitmapUtils`). Each app's icon is uploaded once, only when the band says it doesn't have it cached.

## What we do NOT do (and won't add)

- ❌ Persistent foreground service "for reliability". The passive link above needs none.
- ❌ `JobIntentService`, `AlarmManager.setExactAndAllowWhileIdle()` polling loops.
- ❌ Background BLE scans of any mode.
- ❌ Continuous heart-rate streaming as the default.
- ❌ Asking for `ACCESS_BACKGROUND_LOCATION`.

## Auditing

Every wake-up and link transition logs under the `MB9A_POWER` tag through `PowerLog`. PowerLog also keeps a persisted 24 h wake-up count and the last 50 events, both shown in Settings › Connection & power. Event kinds include `process_start`, `worker`, `connected`, `disconnected`, `reconnect_armed` and `realtime_hr`. After a 24 h soak, run `adb shell dumpsys batterystats --charged com.kidneyweakx.kodo` and check that:

- Wake-ups stay under 50 per day.
- There are no wakelocks attributed to the app outside WorkManager.
- Bluetooth scan time is only from onboarding.
