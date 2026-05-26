# Privacy Policy — Kodō

**Effective date:** 2026-05-26
**App:** Kodō (`com.kidneyweakx.kodo`)
**Developer contact:** gm@solidarity.gg
**Source code:** AGPL-3.0-or-later — published on GitHub.

> Kodō is a slim, local-first companion app for the Xiaomi Smart Band 9 Active. It is an independent third-party app and is not affiliated with, endorsed by, or sponsored by Xiaomi.

---

## 1. The short version

- **No accounts. No cloud. No analytics. No ads. No tracking SDKs.**
- Everything Kodō reads from your phone — notifications, calendar events, music metadata, health samples, the band's pairing key — stays on **your device**.
- Data is sent **only** to two destinations:
  1. Your paired Mi Band 9 Active, over encrypted Bluetooth Low Energy.
  2. *(Optional, only if you turn it on)* a public weather provider you select, to fetch a forecast that gets relayed to the band.
- We do **not** operate any server. The developer cannot see your data because there is no backend to send it to.

---

## 2. What Kodō stores on your device

| Data | Where | Why |
|------|-------|-----|
| Paired band MAC address, model, firmware version | App-private MMKV storage | To reconnect to the same band on app start |
| Band authentication key (32 bytes) | App-private MMKV storage | Required by Xiaomi's protocol to talk to the band |
| Last sync timestamp, last battery %, recent samples | App-private MMKV cache | To show the dashboard without waiting for a fresh sync |
| Activity samples (steps, heart-rate, sleep, workouts) downloaded from the band | App-private storage | To display on the dashboard and optionally forward to Health Connect |
| Notification filter list (which apps may forward to the band) | App-private MMKV storage | So your choice survives restarts |
| Theme, language, and other UI preferences | App-private MMKV storage | UI settings |

This data is uninstalled with the app. You can also clear it from **Settings → Reset Kodō**.

---

## 3. Permissions Kodō requests, and what they are used for

| Android permission | Why Kodō needs it | Leaves the device? |
|--------------------|-------------------|--------------------|
| `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | Discover, pair with, and sync the Mi Band 9 Active. | No (sent only to the band) |
| `ACCESS_FINE_LOCATION` | Required by Android for BLE scanning on Android 11 and below. **Kodō does not read your location for any purpose.** | No |
| `POST_NOTIFICATIONS` | Show local sync / status notifications. | No |
| `BIND_NOTIFICATION_LISTENER_SERVICE` (granted via Settings) | Read incoming notifications **only** so the title and a snippet can be forwarded to your band. You choose which apps may forward. | The forwarded snippet is sent only to your band over encrypted BLE. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION` | Keep an active sync or GPS workout running without being killed. The `LOCATION` foreground service type is **only** used while you have started a workout that you have opted to record GPS for. | No |
| `RECEIVE_BOOT_COMPLETED` | Restore BLE reconnection after the phone reboots. | No |
| `READ_PHONE_STATE` | Detect an incoming call so the band can show "Call from …". | No (the caller string is sent only to the band over BLE) |
| `VIBRATE` | Mirror band vibration for local notifications. | No |
| Read calendar (optional, granted by you in system settings) | Forward today's events to the band. | No (only to the band over BLE) |
| Health Connect read / write (optional, granted by you per data type) | Read existing samples to merge, write new samples synced from the band. Health Connect data stays on your device under Google's Health Connect framework. | No |

---

## 4. The one network request Kodō may make

If — and only if — you enable an **online weather provider** in Kodō's settings, the app issues a standard HTTPS request to that provider (for example, Open-Meteo) with your selected location to fetch a forecast. The forecast is relayed to the band so it can show the weather on the watchface.

- You can leave the weather provider set to **Off**; in that case Kodō makes no network requests at all.
- The third-party weather provider's privacy policy applies to that request. Kodō does not add any identifier to the request beyond a generic User-Agent.

Kodō makes **no other network requests**. No telemetry, no analytics, no crash reporting, no ad networks, no remote config.

---

## 5. Children

Kodō is not directed at children under 13 and does not knowingly process data from children.

---

## 6. Security

- Communication with the band uses the Xiaomi authentication protocol (HMAC-SHA256 challenge / AES-CCM encrypted channel).
- All on-device storage is in the app's private sandbox.
- You can revoke individual permissions in Android Settings at any time. Kodō will degrade gracefully (for example, disabling notification forwarding) rather than fail.

---

## 7. Data Safety — Google Play form quick reference

For Play Console's Data Safety questionnaire:

| Play Console question | Kodō's answer |
|---|---|
| Does your app collect or share any user data? | **No.** All processing is on-device. The optional weather request does not include personal data. |
| Is all of the user data collected by your app encrypted in transit? | N/A — no user data leaves the app. BLE to the band is encrypted. |
| Do you provide a way for users to request that their data be deleted? | Yes — uninstall the app, or use Settings → Reset Kodō. |

---

## 8. Open source

Kodō is licensed under the GNU Affero General Public License v3.0 or later, and includes code ported from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (AGPL-3.0-or-later). You can audit exactly what the app does — including every byte sent to the band — by reading the source.

---

## 9. Changes to this policy

If we change how Kodō handles data, this document will be updated and the **Effective date** at the top will change. The history of changes is visible in this file's Git history.

---

## 10. Contact

Questions about this policy or a privacy concern? Email **gm@solidarity.gg**.

---

# 隱私權政策 — Kodō(繁體中文)

**生效日期:** 2026-05-26
**應用程式:** Kodō(`com.kidneyweakx.kodo`)
**開發者聯絡方式:** gm@solidarity.gg
**原始碼:** AGPL-3.0-or-later,公開於 GitHub。

> Kodō 是給小米手環 9 Active 用的輕量、本機優先伴侶 App。本 App 為獨立第三方專案,**與小米沒有任何隸屬、合作、贊助或代言關係**。

---

## 1. 一句話版本

- **不註冊、不上雲、無分析、無廣告、無追蹤 SDK。**
- Kodō 從你手機讀到的所有東西 — 通知、行事曆、音樂中繼資料、健康資料、手環配對金鑰 — **完全留在你的裝置上**。
- 資料**只**會送到兩個地方:
  1. 你已配對的 Mi Band 9 Active,透過加密的 Bluetooth Low Energy。
  2. *(可選,要你自己打開才會啟用)* 你選擇的公開天氣服務供應商,用來抓預報轉發到手環。
- 我們**沒有架設任何伺服器**。開發者看不到你的資料,因為沒有後端可以收。

---

## 2. Kodō 在你裝置上儲存的東西

| 資料 | 儲存位置 | 用途 |
|------|----------|------|
| 已配對手環的 MAC、型號、韌體版本 | App 私有 MMKV | 開 App 時自動連回同一支手環 |
| 手環授權金鑰(32 bytes) | App 私有 MMKV | 小米協定要求,沒有就無法跟手環溝通 |
| 上次同步時間、上次電量、近期樣本 | App 私有 MMKV 快取 | 不用等同步就能立刻顯示儀表板 |
| 從手環下載的活動樣本(步數、心率、睡眠、運動) | App 私有儲存 | 顯示在儀表板上,選擇性轉寫進 Health Connect |
| 通知過濾名單(哪些 App 可以轉發到手環) | App 私有 MMKV | 你的設定能在重啟後保留 |
| 主題、語言等介面偏好 | App 私有 MMKV | UI 設定 |

解除安裝 App 時這些資料會一併移除。你也可以在 **設定 → 重置 Kodō** 主動清除。

---

## 3. Kodō 要求的權限,以及實際用途

| Android 權限 | Kodō 為什麼需要 | 會離開裝置嗎? |
|-------------|----------------|---------------|
| `BLUETOOTH`、`BLUETOOTH_ADMIN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN` | 搜尋、配對、與手環同步。 | 不會(只送到手環) |
| `ACCESS_FINE_LOCATION` | Android 11 及以下做 BLE 掃描要求的權限。**Kodō 不會以任何形式讀取你的位置。** | 不會 |
| `POST_NOTIFICATIONS` | 顯示本機同步/狀態通知。 | 不會 |
| `BIND_NOTIFICATION_LISTENER_SERVICE`(透過系統設定授權) | 讀取進來的通知,**只**把標題與簡短內容轉發給手環。轉發哪些 App 由你決定。 | 內容只透過加密 BLE 送到手環。 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`FOREGROUND_SERVICE_LOCATION` | 同步中或 GPS 運動進行中時,維持 service 不被系統殺掉。`LOCATION` 類型**只**在你主動啟動需要 GPS 的運動時才用到。 | 不會 |
| `RECEIVE_BOOT_COMPLETED` | 手機重開後恢復 BLE 連線。 | 不會 |
| `READ_PHONE_STATE` | 偵測來電,讓手環顯示「來自 ⋯⋯ 的來電」。 | 不會(來電者字串只透過 BLE 送到手環) |
| `VIBRATE` | 本機通知時跟手環一起震動。 | 不會 |
| 讀取行事曆(可選,由你在系統設定授權) | 把今天的行程轉發到手環。 | 不會(只透過 BLE 送到手環) |
| Health Connect 讀寫(可選,你逐項授權) | 讀既有樣本來合併、寫入從手環同步來的新樣本。Health Connect 的資料在 Google 的 Health Connect 架構下留在你的裝置。 | 不會 |

---

## 4. Kodō 可能會發出的唯一網路請求

只有當你**主動**在 Kodō 設定中開啟**線上天氣服務**時,App 才會對你選的供應商(例如 Open-Meteo)發出一個標準 HTTPS 請求,帶上你設定的城市/座標,取回預報轉發給手環,讓手環表盤可以顯示天氣。

- 把天氣供應商設為**關閉**,Kodō 就完全不會發出任何網路請求。
- 那筆請求受該天氣供應商的隱私權政策約束。Kodō 除了一般的 User-Agent 之外不會在請求中加入任何識別碼。

Kodō **不會**發出任何其他網路請求。沒有遙測、沒有分析、沒有崩潰回報、沒有廣告聯播、沒有遠端設定。

---

## 5. 兒童

Kodō 並非以未滿 13 歲兒童為對象,也不會刻意處理兒童資料。

---

## 6. 安全

- 與手環的通訊使用小米的驗證協定(HMAC-SHA256 挑戰 / AES-CCM 加密通道)。
- 本機儲存全部在 App 私有沙箱中。
- 你可以隨時在 Android 系統設定撤銷個別權限。Kodō 會以「優雅降級」方式處理(例如停用通知轉發),不會崩潰。

---

## 7. Google Play Data Safety 速查

填 Play Console 的 Data Safety 表單時可直接參考:

| Play Console 問題 | Kodō 的答案 |
|---|---|
| App 是否蒐集或分享任何使用者資料? | **否。** 所有處理都在裝置本機。可選的天氣請求不包含個人資料。 |
| App 蒐集的使用者資料傳輸時是否全程加密? | 不適用 — 沒有使用者資料離開 App。與手環的 BLE 通訊本身就是加密的。 |
| 使用者是否能要求刪除其資料? | 是 — 解除安裝 App,或從 設定 → 重置 Kodō。 |

---

## 8. 開源

Kodō 採 GNU Affero 通用公共授權條款 v3.0 或之後版本授權,包含從 [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)(同樣是 AGPL-3.0-or-later)移植的程式碼。你可以直接讀原始碼來稽核 App 的所有行為,包括每一個送到手環的位元組。

---

## 9. 政策變更

如果 Kodō 處理資料的方式有所變動,本文件會更新,頁首的**生效日期**會跟著變。變更歷史可從本檔的 Git history 直接看到。

---

## 10. 聯絡

對本政策有任何疑問或隱私顧慮,請來信 **gm@solidarity.gg**。
