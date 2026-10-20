# 通知監聽診斷（NOTIFICATION-DIAGNOSTICS）

版本 0.3.1 起，Private Inbox Classic 內建**本機、匿名、有限大小**的通知管線診斷。
目的：讓「訊息沒有被保存」這種 P0 問題可以定位到**具體階段**，而不是憑猜。

---

## 1. 診斷管線階段

```
SYSTEM_NOTIFICATION
        │
        ▼
LISTENER_RECEIVED          監聽器是否真正收到事件
        │
        ▼
RECORDING_POLICY          訊息記錄開關（LocalSettings.isRecording）
        │
        ▼
SOURCE_POLICY             來源套件白名單（CapturePolicy.allowed）
        │
        ▼
NOTIFICATION_PARSER       previews / extras 解析（MessagingStyle、BigTextStyle…）
        │
        ▼
ENCRYPT                   AES-GCM 封裝
        │
        ▼
DATABASE_COMMIT           入庫（NEW / DUPLICATE / FAILURE）
        │
        ▼
UI_REFRESH                列表重建（InboxDatabase.recent() 重新讀取）
```

每個失敗階段都有明確的诊断代碼（`CaptureDiagnostics.kt` 常數）：

| 代碼 | 階段 | 意義 |
|------|------|------|
| `LISTENER_CONNECTED` / `LISTENER_DISCONNECTED` | lifecycle | 系統綁定 / 解除監聽器 |
| `NOTIFICATION_RECEIVED` | SYSTEM_NOTIFICATION | 事件進入管線 |
| `RECORDING_DISABLED` | RECORDING_POLICY | 訊息記錄開關為關 |
| `SOURCE_NOT_ALLOWED` | SOURCE_POLICY | 來源套件不在白名單 |
| `GROUP_SUMMARY_SKIPPED` | NOTIFICATION_PARSER | 群組摘要通知，跳過 |
| `EMPTY_NOTIFICATION` | NOTIFICATION_PARSER | 無 extras 或無可顯示內容 |
| `PARSE_FAILED` / `PARSE_SUCCESS` | NOTIFICATION_PARSER | 解析結果 |
| `ENCRYPT_FAILED` | ENCRYPT | 加密失敗 |
| `DB_OPEN_FAILED` / `DB_INSERT_FAILED` / `DB_DUPLICATE` / `DB_INSERT_SUCCESS` | DATABASE_COMMIT | 資料庫結果 |
| `UI_REFRESH_SUCCESS` / `UI_REFRESH_FAILED` | UI_REFRESH | 新資料列能否被新 handle 讀到 |
| `MEDIA_URI_UNAVAILABLE` / `MEDIA_DECODE_FAILED` | （圖片）| 圖片失效 / 解碼失敗（**不影響文字入庫**）|

### 監聽器連線狀態（誠實分級）

`CaptureDiagnostics.resolveStatus()` 只依真實證據回傳：

- `UNKNOWN` — 無連線證據、也無拒證據（例如該 API 在被測裝置上不可用）
- `PERMISSION_DENIED` — 系統 enabled-listener 清單不含本 App
- `PERMISSION_GRANTED_NOT_CONNECTED` — 授權了，但本行程沒有 `onListenerConnected()` 證據
- `CONNECTED` — 本行程真的收到 `onListenerConnected()`
- `DISCONNECTED` — 收到 `onListenerDisconnected()` 或 rebind 被拒

**權限已授予 ≠ 監聽器已連線。** UI 不會拿 SharedPreferences 硬寫「已連線」。

---

## 2. 私人內容保護

- 診斷事件**不記錄**：訊息正文、圖片位元組、圖片 URI、完整 extras、聯絡人資料、金鑰、資料庫明文。
- 只記錄：時間戳、不含內容的診斷識別碼、來源套件名、階段、結果代碼、錯誤型別名、耗時。
- 所有資料只存在於 App 私人 `SharedPreferences` + 記憶體 Ring Buffer（上限 500 筆），不離開裝置。

---

## 3. 使用者畫面

進入 **設定 → 通知監聽診斷**：

| 列 | 意義 |
|----|------|
| 通知存取權限 | `enabled_notification_listeners` 是否含本 App |
| 監聽器連線狀態 | 上表五種誠實狀態之一 |
| 訊息記錄開關 | `LocalSettings.isRecording` |
| 允許來源數量 | 已勾選來源數 |
| 收到通知事件 / 解析成功 / 資料庫寫入成功 / 失敗事件 | 累積計數 |
| 最後錯誤代碼 | 最後一個 `last_code / last_err` |
| 最後收到通知 | 最後事件時間（「尚無」= 尚無任何事件）|

按鍵：

- **重新檢查連線**：單次、使用者觸發的 `requestRebind()`；成功也**不會**被記成 CONNECTED，
  真正的連線證據仍以服務端 `onListenerConnected()` 為準。
- **發送測試通知**：走真實的 `NotificationManager` → 系統 → 監聽器 → 入庫 → UI 顯示，
  並即時回報結果（通過 / 未找到 / 權限不足）。
- **重新整理訊息**：重建列表。
- **匯出匿名診斷報告**：JSON（計數 + 最近 50 筆事件）複製到剪貼簿，無私人內容。
- **清除診斷紀錄**：歸零計數與 ring buffer。

---

## 4. 系統測試通知（Self Test）

固定來源：

- `Source: Private Inbox Self Test`
- `Sender: Test User`
- `Body: Hello Private Inbox`

流程：

1. 使用者按下「發送測試通知」。
2. `SelfTestNotifier.post()` 經真實 `NotificationManager` 發布。
3. Android 系統把事件交給 `PrivateNotificationListener`。
4. Source Policy 識別 `CapturePolicy.SELF_TEST`（=`tw.personal.privateinbox`）。
5. Parser 解析固定測試資料 → 加密 → 資料庫 Commit。
6. UI 顯示 Self Test 對話。
7. 重新開啟 App 後資料仍存在（加密行可重讀）。

### Android 13+ 的 POST_NOTIFICATIONS

- 只在 Android 13+ 需要 `POST_NOTIFICATIONS` 才能讓本 App 發出自己的測試通知。
- 此權限**只用於本 App 自己的通知**；接收 LINE/Messenger 通知**不需要**它。
- 若使用者拒絕：UI 明確顯示「測試無法發送：本機未允許本程式顯示通知」。

### 兩類測試的分界（不得混為一談）

| 測試 | 路徑 | 證明什麼 |
|------|------|---------|
| `DIRECT_PARSER_TEST` | 直接建 `StatusBarNotification` 呼叫 `capture()` | 解析 + 儲存流程正確 |
| `SYSTEM_NOTIFICATION_E2E_TEST` | 由 Android 系統發布通知事件 | 監聽器真的被系統路由、端到端入庫 |

模擬器 / 直測**不能**證明真實 LINE 已修復。LINE / Messenger 真機相容性仍需實體手機驗證。

---

## 5. 如何判斷各故障

| 症狀 | 看什麼 |
|------|--------|
| 監聽器未連線 | 狀態 = `PERMISSION_DENIED`（去授權）或 `PERMISSION_GRANTED_NOT_CONNECTED`（OEM 背景限制 / 需重新檢查連線）|
| Parser 失敗 | `PARSE_FAILED` / `EMPTY_NOTIFICATION` 事件、`last_code` 含 parse 前綴 |
| DB 寫入失敗 | `DB_OPEN_FAILED` / `DB_INSERT_FAILED`、`last_err` 為例外型別名 |
| UI 沒更新 | `UI_REFRESH_FAILED`（資料庫寫入成功但 UI 沒顯示）|
| 圖片拿不到 | `MEDIA_URI_UNAVAILABLE` / `MEDIA_DECODE_FAILED`（文字仍會入庫）|
| 來源被拒 | `SOURCE_NOT_ALLOWED`（確認 UI 勾選與 Listener 讀取的是同一份 `LocalSettings`）|

---

## 6. 資料庫修正驗證

此前 `InboxDatabase` 的 `execSQL("PRAGMA ...")` 已改為 `rawQuery("PRAGMA ...")`，
本輪另補：

- `save()` 回傳 `SaveResult`（`NEW` / `DUPLICATE` / `FAILURE`），**不再有空 catch 吞掉錯誤**。
- `DbContractInstrumentedTest` 直接驗證「save 必回傳具體結果、重複 → DUPLICATE、
  不同內容不會被錯誤合併、加密往返可重讀」。
- `InboxDatabaseTest` 既有「資料庫損毀不 crash」「重開後 WAL checkpoint 可讀」仍然有效。
