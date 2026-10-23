# 現況統一報告 — Private Inbox Classic v0.4.2

> 這份文件是**一次執行的結果**，不是記憶的轉述。
> 每一個結論都有實際執行的證據。
> 產生時間：本次工作階段，HEAD `cbc44b3` + 本報告的修正。

---

## 0. 一句話總結

**建置是綠的，但有兩個真實的技術債**：記憶體中的工作佇列在行程被殺時會遺失事件；
資料庫沒有啟用 WAL，雖然實測兩個寫入者並存沒有失敗，但那是靠 SQLite 的重試在撐。

---

## 1. 建置狀態（實測）

### 1.1 工具鏈

| 項目 | 值 | 證據 |
|------|-----|------|
| Gradle | 8.13 | `gradle/wrapper/gradle-wrapper.properties` |
| AGP | 8.13.2 | `app/build.gradle.kts` |
| JDK | 17 | `JAVA_HOME` 環境 |
| compileSdk / targetSdk | 36 | `app/build.gradle.kts` |
| minSdk | 28 | `app/build.gradle.kts` |
| Kotlin 編譯策略 | `in-process` | `gradle.properties` |

### 1.2 實際建置結果

| 指令 | 結果 |
|------|------|
| `clean assembleDebug assembleRelease bundleRelease testDebugUnitTest lintDebug` | **BUILD SUCCESSFUL** |
| `connectedDebugAndroidTest` | **105/105 PASS，0 skip** |
| `testDebugUnitTest` | **50/50 PASS** |
| `lintDebug` | **0 error** |
| `scripts/audit.sh` | **PASS** |
| `scripts/audit-secrets.sh` | **PASS**（含全歷史掃描）|
| `scripts/audit-release.sh` | **PASS** |
| `integrations/dhc/tests/test_connector.py` | **OK**（23/23）|
| GitHub Actions run 39532595947 | **GREEN**，20 個步驟全部成功 |

### 1.3 具體建置問題

| # | 問題 | 影響 | 現況 |
|---|------|------|------|
| 1 | `GRADLE_USER_HOME` 必須指到工作區 | `~/.gradle` 在沙盒不可寫時 Gradle 直接起不來 | **已用環境變數繞過**，不是專案本身的問題 |
| 2 | `HOME` 必須指到 `<workspace>/emulator-home` | 否則 emulator 無法開機 | 同上 |
| 3 | `kotlin.compiler.execution.strategy=in-process` | 少了 Kotlin daemon socket 目錄 | 已在 `gradle.properties` 固化 |
| 4 | **儀器測試會重裝 App** | App 的 external files 目錄被刪除，跨平台產物會消失 | 測試已改用 `adb exec-out cat` 直接把位元組送進 App 的 cache |
| 5 | **App 沒有儲存空間權限** | 測試讀不到 `/sdcard/Download` | 同上；**不新增權限**是刻意的 |
| 6 | 套件可視性（Android 11+） | 沒有 `<queries>` 就看不到任何來源套件 | 已在 manifest 補上 9 個套件 |
| 7 | `git diff --check` 乾淨 | 但兩個 Kotlin 檔案含 **NUL 位元組** | 已修（`DhcExporter.kt`、`CaptureMetrics.kt`）|
| 8 | `create("debug")` 與 AGP 預建的 `debug` 衝突 | 專案無法建置 | 改用 `named("debug")` |
| 9 | `bundleRelease` 在設定階段找不到 | 改用 `tasks.matching {}.configureEach` | 已修 |
| 10 | `java.util.Properties` 在 `android {}` 內解析成 DSL 的 `java` | 編譯失敗 | 移到區塊外並用明確 import |

**沒有**任何一項是未處理的建置阻斷。

---

## 2. 架構

### 2.1 分層

```
┌─ Android 系統 ────────────────────────────────────────────┐
│  通知（LINE / Threads / Instagram / Messenger / …）          │
└───────────────┬──────────────────────────────────────────┘
                │ NotificationListenerService
                ▼
┌─ 擷取層 ─────────────────────────────────────────────────┐
│  PrivateNotificationListener (597 行)                     │
│    主執行緒：政策檢查 → 解析 → 快照 → 提交                 │
│    佇列：有界 512，單一 writer 執行緒                     │
│    記憶：已寫入的訊息識別碼（避免寫入放大）               │
└───────────────┬──────────────────────────────────────────┘
                ▼
┌─ 儲存層 ─────────────────────────────────────────────────┐
│  InboxDatabase (485 行) — SQLiteOpenHelper, schema v4     │
│  CryptoStore — Android Keystore, AES-256-GCM + HMAC       │
│  內容：標題 / 寄件者 / 內文 / 圖片皆加密                  │
│  明文：套件名 / 時間戳 / 指紋 / 對話鍵 / 訊息類型          │
└───────────────┬──────────────────────────────────────────┘
                ▼
┌─ 呈現層 ─────────────────────────────────────────────────┐
│  MainActivity (1,429 行 / 52 個方法) — 原生 View          │
│  Telegram Classic 風格，LinearLayout + TextView           │
│  列表分頁：首屏 50 筆，固定 ~89 ms                        │
└──────────────────────────────────────────────────────────┘

┌─ 匯出層（獨立） ─────────────────────────────────────────┐
│  dhc/DhcExporter — PICBOX01 容器，PBKDF2 → AES-256-GCM   │
│  dhc/DhcExportPlan — 先產生計畫給使用者確認              │
│  dhc/DhcExportService — SAF 選位置 + 密碼加密寫出        │
│  integrations/dhc/ — Python 參考實作與匯入器             │
└──────────────────────────────────────────────────────────┘
```

### 2.2 依賴關係（實測）

| 模組 | 被幾個檔案引用 |
|------|--------------|
| `SourceRegistry` | 6 |
| `InboxDatabase` | 4 |
| `CryptoStore` | 3 |
| `CapturePolicy` | 2 |
| `ConversationIdentity` | 1 |

**單一真相來源**成立：平台清單只有 `SourceRegistry` 一處，
加密只有 `CryptoStore` 一處。

### 2.3 執行緒模型（實測）

| 執行緒 | 負責 | 證據 |
|--------|------|------|
| 主執行緒 | 監聽回呼（只做最小工作）、UI | `onNotificationPosted` |
| capture-worker（單一 Thread） | 解碼、寫入資料庫 | `PrivateNotificationListener.kt:83` |
| io（2 執行緒池） | UI 的讀取與寫入 | `MainActivity.kt:122` |

**worker 從不碰 UI 執行緒**：在 listener 裡搜尋 `runOnUiThread` 與
`Looper.getMainLooper` 結果為空。

---

## 3. 使用方針

### 3.1 使用者要做的事

| 步驟 | 動作 | 位置 |
|------|------|------|
| 1 | 開啟「通知存取」 | 設定 → 通知與狀態列 → 通知存取 |
| 2 | 勾選要擷取的來源 | App 設定 → 擷取設定 |
| 3 | 決定是否擷取照片 | App 設定 → 擷取設定 |
| 4 | 可選：vivo 背景三項 | 設定 → 自啟動 / 後台耗電 / 最近任務鎖定 |
| 5 | 選資料保留期限 | App 設定 → 保留期限 |

### 3.2 隱私界線（實測自 manifest）

| 項目 | 狀態 |
|------|------|
| `INTERNET` | **不存在** |
| `ACCESS_NETWORK_STATE` | **不存在** |
| `READ_SMS` / `READ_CONTACTS` | **不存在** |
| `QUERY_ALL_PACKAGES` | **不存在**（改用 9 個 `<queries>`）|
| `READ_EXTERNAL_STORAGE` | **不存在** |
| `POST_NOTIFICATIONS` | 存在 —— **只**用於本 App 自己的自我測試通知 |
| exported 元件 | 只有 `MainActivity`（LAUNCHER 必須）|
| `PrivateNotificationListener` | `exported="false"` |
| `DebugSettingsProvider` | 只在 debug 組態，且需 signature 權限 |

### 3.3 什麼不做

- 不聯網、不上傳、不追蹤。
- 不自動把訊息交給任何 LLM。
- 不在鎖屏上顯示內容（除非明確 opt-in）。
- 不刪除無法解密的資料（除非使用者兩次確認）。

---

## 4. 具體事例

### 事例 A — 群組一次來 30 則

**發生什麼**：LINE 群組連續送 30 則。

| 階段 | 數量 |
|------|------|
| 來源發出 | 30 |
| 平台呈現給 listener | 每則都是一次獨立修訂，所以 30 則都出現過 |
| 寫入資料庫 | **30** |
| 無法解釋的遺失 | **0** |

**曾經的行為**：30 則只存到 1 則 —— 去重鍵在沒有時間戳時退化成「擷取時間」，
而擷取時間是**每則通知**一個，所以全部訊息算出同一個鍵。

**現在**：去重鍵退化成**訊息自身的內容**。

### 事例 B — 兩個群組同時傳送完全相同的文字

**發生什麼**：群組 A 與群組 B 都送「明天見」。

**結果**：兩則都保留，在**不同的對話鍵**底下。
驗證方式是「同一段文字存在於每一個對話鍵底下」——
這是「沒有合併」的正向證明，不是推論。

### 事例 C — 同一則通知被來源更新 10 次

**結果**：只存 1 列（`identical_update_rows=1`）。
但如果那 10 次更新帶的是**不同**的訊息，則 10 則都會被存。

### 事例 D — 通知在鎖屏時被系統收回

**結果**：收不回。平台沒有那份資料了。
這不是本 App 的漏接 —— 分層診斷會把這種情況標成
`SYSTEM_DELIVERY_GAP`，不算成 App 的問題。

### 事例 E — 使用者變更螢幕鎖設定

**發生什麼**：Android 撤銷了加密金鑰。

**結果**：
1. 診斷面板顯示 `KEY_INVALIDATED` 與金鑰診斷。
2. 對話框說明「受影響的 N 則訊息已永久無法解密」。
3. **資料保留**，不自動刪除。
4. 清除需要**兩次**確認。

**不會**發生的事：把暫時鎖屏（裝置沒解鎖）當成永久失效。

---

## 5. 嵌套問題（Nested problems）分析

「嵌套」在這裡有兩種意思，兩種都查了。

### 5.1 嵌套的**程式結構** — 沒有問題

| 檢查 | 結果 |
|------|------|
| 模組之間有循環相依嗎 | **沒有**。耦合是單向的：listener → database → crypto |
| 一個檔案承擔多個關注點嗎 | **沒有明顯的**。每個檔案單一職責（平台表 / 加密 / 政策 / 身份 / 資料庫 / 診斷 / 指標）|
| 回呼 → 寫入資料庫的巢狀深度 | 3 層（`capture` → `submit` → `db.save`），每層職責單一 |
| 巢狀的 try/catch 會不會吞掉錯誤 | **不會**。worker 捕捉例外、記錄、**繼續** |

### 5.2 嵌套的**執行期資源** — 有兩個真實的技術債

這是重點。

#### 技術債 1：記憶體中的佇列（行程死亡會遺失事件）

```
主執行緒 → 有界佇列（512）→ 單一 worker 執行緒
                │
                └── 這裡的任務只存在於記憶體
```

**風險**：如果行程在「callback 已收到」與「已寫入資料庫」之間被殺，
那一筆就永遠遺失，而且**沒有任何紀錄**。

**這在 vivo 上是真實風險**：OriginOS 的背景管理可能直接結束行程。
量測顯示佇列等待已降到 p99 2.1 秒，所以視窗很窄 —— 但它存在。

**沒有實作持久化 intake journal。** 這是下一個優先項目。

**已量測的證據**（500 則訊息）：`queue_wait p99 = 2,091 ms`。
也就是最壞情況下，一個事件在記憶體裡停留約 2 秒。

#### 技術債 2：資料庫沒有啟用 WAL，卻有兩個寫入者

**實測**：`journal_mode=truncate`（**不是** WAL）。

**實測**：兩個 writer × 150 列 = **300 列，0 失敗，0 遺失**。

| 讀 | 值 |
|----|----|
| listener 的 writer | 1（單一 Thread）|
| UI 的 writer | 2 執行緒池（`MainActivity.kt:122`）|
| 實際並行寫入者 | **最多 2** |

**為什麼現在沒事**：SQLite 在 `truncate` 模式的預設 busy timeout 是 3 秒，
而實測的寫入遠低於那個時間，所以重試成功。

**為什麼這仍然是技術債**：
1. 那個 3 秒是 SQLite 的預設，不是本專案選的。
2. 寫入變慢時（例如大量圖片），兩個 writer 會互相等，UI 會卡住。
3. WAL 模式下讀取**不會**阻擋寫入 —— 那正是這個 App 需要的性質
   （列表在背景讀取，同時 listener 在寫入）。

**建議**：`SQLiteOpenHelper.setWriteAheadLoggingEnabled(true)`。
**這是本報告建議的下一個程式碼變更**，但**本輪沒有實作** ——
它會改變資料庫檔案格式，應該獨立驗證。

#### 沒有發現的嵌套問題

| 檢查 | 結果 |
|------|------|
| 兩個 writer 造成資料遺失 | **沒有**（實測 300/300）|
| 讀取被寫入阻塞 | **沒有**（實測 200 列，0 失敗）|
| listener 的 worker 呼叫 UI 執行緒 | **沒有**（搜尋結果為空）|
| callback 在主執行緒做磁碟或密碼學工作 | **沒有**（callback 只做政策檢查、解析、快照、提交）|
| 例外讓 worker 死掉 | **不會**（捕捉、記錄、繼續）|
| 佇列溢出靜默丟棄 | **不會**（記錄 `QUEUE_OVERFLOW`）|

---

## 6. 文件漂移（doc-drift）

用 `doc-drift` 的流程掃描，**發現 3 個真實的漂移**，全部已修：

| # | 位置 | 宣告 | 事實 | 處置 |
|---|------|------|------|------|
| 1 | `README.md:216` | 交付產物是 v0.4.1 | 當前建置是 **v0.4.2** | 已更新為 v0.4.2 的雜湊 |
| 2 | `docs/TEST-REPORT.md:21` | 單元測試 42/42 | 實際 **50/50** | 已更新 |
| 3 | `docs/AGENT_STATE.md:17` | instrumentation 95/95 | 實際 **105/105** | 已更新 |

**沒有**發現模糊措辭（`AGENTS.md`、`README.md`、`AGENT_STATE.md`、`HANDOFF.md`
的 hedge-phrase 密度都是 0）。

**沒有**發現規則之間的衝突：`AGENTS.md` 的八條硬性規則與
`docs/SECURITY.md`、`docs/engineering/SECURITY-AUDIT.md` 的內容一致。

---

## 7. 尚未驗證（誠實列出）

| 項目 | 狀態 | 原因 |
|------|------|------|
| **vivo X200 Pro / OriginOS 真機** | **NOT_TESTED** | 本輪沒有連接實機 |
| OriginOS 背景管理、通知分組 | **NOT_TESTED** | 同上 |
| 鎖屏 / 省電模式 / 行動數據 | **NOT_TESTED** | 需要實機 |
| Android 9 / 13 / 14 / 15 | **NOT_TESTED** | 只驗證了 Android 16 |
| 正式 release 簽章 | **BLOCKED** | 金鑰必須由擁有者保管 |
| 行程被殺時的資料遺失 | **未量測** | 需要在真機上強制殺行程 |
| WAL 模式的影響 | **未量測** | 尚未啟用 WAL |

---

## 8. 下一個動作

依優先序：

1. **在 X200 Pro 上跑 `bash scripts/run-burst-tests.sh --repeat 3`**，
   與 `docs/reliability/BURST-CAPTURE-ROOT-CAUSE.md` 的模擬器數字對照。
2. **啟用 WAL**（`setWriteAheadLoggingEnabled(true)`）並量測前後差異。
   這解決「兩個 writer 互相等」與「讀取阻塞寫入」。
3. **持久化 intake journal**，消除行程死亡時的遺失視窗。
4. 正式 release / upload 金鑰（需要擁有者）。

---

## 9. 本報告的證據清單

| 結論 | 證據來源 |
|------|---------|
| 建置成功 | `clean assembleDebug assembleRelease bundleRelease testDebugUnitTest lintDebug` → EXIT 0 |
| 105/105 儀器測試 | `app/build/outputs/androidTest-results/connected/debug/*.xml` |
| 50/50 單元測試 | `app/build/test-results/testDebugUnitTest/*.xml` |
| 0 lint error | `app/build/reports/lint-results-debug.xml` |
| 三個稽核腳本 | 實際執行輸出 |
| 佇列等待 p99 2,091 ms | `CaptureMetrics.queueWait`，TEST-C |
| callback p99 47 ms | `CaptureMetrics.callbackDuration`，TEST-C |
| `journal_mode=truncate` | `ConcurrentWriterInstrumentedTest` |
| 300 列 / 0 失敗 | 同上，兩個 writer × 150 |
| 讀取不阻塞寫入 | 同上，`aReaderDoesNotBlockAWriter` |
| 權限清單 | `app/src/main/AndroidManifest.xml` |
| 耦合數量 | `grep -rl` 對每個模組 |
| 執行緒模型 | `Executors.newFixedThreadPool`、`Thread({...})` |
| 3 個文件漂移 | `doc-drift` Step 0/2，兩側都有 `file:line` |
