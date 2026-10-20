# Changelog

本專案遵循 [Keep a Changelog](https://keepachangelog.com/) 的格式，
版本號遵循 [Semantic Versioning](https://semver.org/)。

---

## [0.4.0-alpha] — 未發布

### 新增

- **平台來源註冊表** `SourceRegistry.kt` — 平台清單與分類規則的唯一來源。
  新增平台只需修改一處，篩選列、來源設定與分類器都會自動更新。
- **Threads 支援**（`com.instagram.barcelona`）— 私訊與社交動態分開處理，
  社交動態（按讚、追蹤、推薦）不會混進私訊收件匣。
- **訊息分類** — 區分 `DIRECT_MESSAGE`、`GROUP_MESSAGE`、`MENTION`、
  `REPLY`、`SOCIAL_ACTIVITY`、`UNKNOWN`。無法可靠分類的通知標記為
  `UNKNOWN`，不會假裝是私訊。
- **平台篩選列** — 主畫面水平篩選列，可依來源過濾並顯示各來源筆數。
- **穩定的對話識別** `ConversationIdentity.kt` — 以平台的 conversation
  shortcut id 為主，跨 App 永不合併。資料庫 schema 升到 v4
  （新增 `conversation_key`、`message_kind`）。
- **可重現的簽章設定** — debug / release / upload 三種身分分離，
  設定檔與金鑰皆 git-ignore。見 `docs/SIGNING.md`。
- **秘密稽核腳本** `scripts/audit-secrets.sh` — 掃描工作樹**與完整 Git 歷史**。
- **診斷 JSON schema 版本** 與「最後成功保存」時間戳。
- `docs/SKILL-ROUTING.md` — 實際的技能盤點與選用紀錄。
- `docs/PLATFORM-COMPATIBILITY.md` — 各平台相容性矩陣，標註驗證層級。

### 修正

- **`LocalSettings.selected()` 的 ClassCastException 會讓監聽服務崩潰。**
  偏好設定值型別錯誤時會拋出例外，導致 `NotificationListenerService`
  被系統殺掉，**所有擷取靜默停止**直到重新綁定。讀取現在型別安全。
- **設定畫面 NPE** — `c["last_ts"] as Long` 讀取不存在的 key，
  整個設定畫面崩潰。計數讀取改為型別安全的輔助方法。
- **`onNotificationPosted` 未受保護** — 監聽器拋出例外會被系統殺掉。
  現在整條擷取路徑都有防護，單一畸形通知無法停止擷取。
- **搜尋畫面的來源與日期篩選器會無聲重置** — 它們是區域變數，
  每次重新繪製都會重建。現在改為 Activity 狀態，與平台篩選共用。
- `CaptureDiagnostics` 的 `@Synchronized` 用在有 backing field 的 property 上
  （編譯錯誤）。

### 變更

- 自我測試通知改用**每次執行唯一的標記**，舊資料不會被誤判為本次成功。
- 診斷計數改用 `SaveResult` 分類，不再有空 catch 吞掉錯誤。

---

## [0.3.1] — 未發布

### 新增

- **通知管線診斷** `CaptureDiagnostics.kt` — 逐階段記錄
  （收到 → 政策 → 解析 → 加密 → 入庫 → UI 刷新），
  以 `traceId` 串連同一則通知的各階段。
- **監聽器連線的誠實分級** — `UNKNOWN` / `PERMISSION_DENIED` /
  `PERMISSION_GRANTED_NOT_CONNECTED` / `CONNECTED` / `DISCONNECTED`。
  **權限已授予 ≠ 監聽器已連線**，UI 不會拿授權狀態假裝成已連線。
- **系統自我測試** `SelfTestNotifier.kt` — 透過真正的 `NotificationManager`
  發布通知，驗證完整的系統 → 監聽器 → 入庫 → UI 路徑。
- 診斷畫面：權限狀態、連線狀態、各階段計數、最後錯誤，
  以及重新檢查 / 發送測試通知 / 重新整理 / 清除紀錄 / 匯出 JSON。

### 變更

- `InboxDatabase.save()` 回傳 `SaveResult`（`NEW` / `DUPLICATE` / `FAILURE`），
  不再有空 catch 吞掉錯誤。
- `POST_NOTIFICATIONS` 權限：**僅**用於本 App 自己的測試通知，
  擷取 LINE 等來源不需要它。

---

## [0.3.0] — 已驗證

### 新增

- 完整的 Gradle Wrapper（含官方 distribution SHA-256）。
- 18 項 JVM 單元測試。
- 14 項 instrumentation 測試（Android 16 模擬器）。
- `docs/` 完整文件集。

### 修正

- **`execSQL("PRAGMA ...")` 使整個資料庫無法開啟。** PRAGMA 是查詢語句，
  `execSQL()` 只接受更新語句。改用 `rawQuery()`。
  這個缺陷只有實機／模擬器測試能發現。
- `OnBackPressedCallback` 覆寫了錯誤的方法名。
- `GestureDetector.SimpleOnDoubleTapGestureListener` 不存在於公開 SDK。
- 影像解碼從通知回呼移到背景執行緒。
- 未讀 Badge 從「歷史總筆數」改為「上次查看後新擷取的筆數」。
- 聊天室加入日期分隔，並移除 120 筆的硬性上限。

---

## [0.2.0-source-candidate] — 初始候選版

第一版可讀原始碼，未經編譯驗證。
