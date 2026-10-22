# Changelog

本專案遵循 [Keep a Changelog](https://keepachangelog.com/) 的格式，
版本號遵循 [Semantic Versioning](https://semver.org/)。

---

## [0.4.4-alpha] — 未發布

本輪是**效能與可攜性**。**沒有**改變資料格式，**沒有**改變 UI，
**沒有**新增權限，**沒有**引入任何網路程式碼。

### 效能

- **通知回呼不再做磁碟 I/O。** `CaptureDiagnostics.note()` 原本在**主執行緒**上
  每次事件都做一次 SharedPreferences 讀改寫，而回呼路徑每則通知呼叫它
  20 次以上。計數器與事件改為在記憶體，並在背景執行緒上寫入。
  **實測：回呼 p50 從 623 ms 降到 4 ms。**
- **一次交易寫入多則訊息。** 原本每則訊息都是自己的交易，而每個交易都要
  fsync。寫入端現在把數個通知的訊息合成**一個**交易。
  **實測：500 則訊息的交易數從 648 降到 40；佇列等待 p50 從 33.9 秒降到 3.5 秒。**
- **熱路徑少兩個配置。** `CapturePolicy.normalize()` 原本每次呼叫都編譯一個
  新的 `Regex`（每則通知最多 50 個）；`LocalSettings.selected()` 原本走
  `getSharedPreferences(...).all`，會把整個偏好設定檔載入成 map。
- **延遲分段量測。** 新增 `PipelineLatency`，用**單調時鐘**
  (`SystemClock.elapsedRealtimeNanos`) 分別量測 T0–T6 各階段。
  **入列時間不再被當成持久化時間** —— 提交階段是分開量的。

### 資料庫

- **Schema v5**：新增三個複合索引
  （`package_name, received_at DESC, id DESC` 等）。
  遷移**只建立索引**，不回寫也不刪除任何一列。
  **實測：查詢計畫變成 `USING COVERING INDEX package_received_idx`。**
- 新增 `InboxDatabase.saveBatches()`：多組訊息寫入**一個**交易。

### 測試

- **去重黃金集**：32 個獨立案例，每個都有自己的預期結果與理由。
  涵蓋跨對話不合併、無未解釋的遺失、重複不放大、重啟冪等、
  DB 重試正確，以及**明確標記**資料本身無法分辨的模糊案例。
- **延遲基準**：分別量測回呼、入列、佇列等待、提交、端到端。
- **儲存基準**：100 / 500 / 1,000 / 10,000 則，量測吞吐、資料庫大小、
  每則平均位元組與查詢計畫。
- 修正一個**真實的量測錯誤**：指標住在 App 行程且是累計的，
  而每個 instrumentation 測試都跑在同一個行程。
  在一個測試的 `setUp` 裡 `reset()` 會清掉**同時正在量測的其他測試**的
  計數器。突發矩陣改成**取 baseline 然後相減**。

### 相容性

- 新增 `DeviceCapabilities`：執行期偵測 Android 版本、製造商、
  通知歷史上限與可用能力。
- 新增**使用者可操作**的背景設定指引（小米 / vivo / OPPO / realme /
  HONOR / 華為），依 `Build.MANUFACTURER` 推導，**不為單一品牌寫死**。
  App **永遠不會**自動修改系統安全設定。
- 中國版 ROM **不需要** Google Play Services，App 也沒有任何 Play 相依。

### 文件

- **README.md（英文，主文件）** 與 **README.zh-TW.md**，互相連結。
  README **明說**平台的限制，並把沒實測的機型標為 `NOT TESTED`。
- 新增 `SECURITY.md`、`CODE_OF_CONDUCT.md`、Issue / PR 範本。
- 新增 `docs/TESTING.md`、`docs/DATABASE.md`、`docs/DHC-INTEGRATION.md`、
  `docs/ROADMAP.md`、`docs/COMPATIBILITY.md`、
  `docs/OPEN-SOURCE-PROVENANCE.md`、`docs/REPOSITORY-METADATA.md`。
- 新增 Rust Core 的三份研究文件。**結論：目前不需要 Rust** ——
  瓶頸是磁碟 I/O 與排程，不是運算。
- 新增 `docs/GITHUB-PUSH-HANDOFF.md`：**本輪沒有推送**。

### 修正的缺陷

- **準備好的批次是 per-instance 的**，但負責寫入的 worker 屬於**服務**那個
  實例，所以其他實例（補抓路徑與測試）準備的資料**永遠不會被寫入**。
  訊息就這樣消失了。批次改為共享，並且各自帶著準備時的 context ——
  因為系統沒有建立的實例，其 `applicationContext` 是 null。
- 診斷的 flush 若持有物件 monitor 跨越寫入，會讓 worker 的每次 `note()`
  都阻塞；提交延遲因此到了 **40 秒**。flush 現在在 monitor 之外寫入。
- 每次事件都提交一個 flush 任务是**退步**：一百則通知會提交兩千個任務，
  發佈一百則通知的耗時從 130 ms 變成 **116 秒**。flush 現在會合併。

### 未變更

Telegram Classic UI、`SourceRegistry` 的平台清單、SQLite 的加密格式、
`CryptoStore`、`ConversationIdentity`、Keystore 格式、App Lock、
既有訊息資料、DHC v0.2 的匯出格式、Debug 簽章。

---

## [0.4.3-alpha] — 未發布

### 新增

- **破壞性測試隔離**：`devtest` 變體、`DestructiveOperationGuard`、
  `scripts/audit-test-isolation.py` 建置閘門。
- **去重識別碼審查**：修正兩個真實缺陷。
- **擷取計數驗證**：機器可檢查的計數關係。
- **WAL A/B 實驗**：結論是**不啟用 WAL**。
- **行程死亡實驗**：量到遺失視窗。

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
