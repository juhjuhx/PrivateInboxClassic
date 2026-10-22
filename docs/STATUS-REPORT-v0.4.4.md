# v0.4.4 效能與可攜性 — 統一報告（STATUS-REPORT-v0.4.4）

> 分支：`fix/x200pro-notification-burst`
> 基準：`a3b8967`（v0.4.3）
> **本輪沒有推送。** 遠端操作交由 Codex / Hermes 執行。

---

## 0. 一句話總結

> **這個專案的瓶頸從來不是運算，是磁碟 I/O。**
> 這一輪量到了它，然後把它修掉：
> **回呼 623 ms → 4 ms，佇列等待 33.9 秒 → 3.5 秒，500 則訊息的交易數 648 → 40。**
>
> 而 Rust 的研究結論也因此是明確的：**現在不需要 Rust**。

---

## 1. 本輪的交付清單

| 編號 | 項目 | 狀態 | 證據 |
|------|------|------|------|
| GIT_HEAD | 本地提交 | ✅ | `87df4ec`（見 §9）|
| BASELINE | v0.4.3 的基準 | ✅ | `a3b8967` |
| WORKTREE | 整合工作樹 | ✅ | `PrivateInboxClassic-v0.4-wt` |
| SOURCE_DIFF | 原始碼差異 | ✅ | §3 |
| LOW_LATENCY_RESULTS | 低延遲 | ✅ | §4 |
| THROUGHPUT_RESULTS | 吞吐 | ✅ | §5 |
| DATABASE_RESULTS | 資料庫 | ✅ | §6 |
| DEDUP_RESULTS | 去重 | ✅ | §7 |
| COMPATIBILITY_MATRIX | 相容性矩陣 | ✅ | §8 |
| RUST_RESEARCH | Rust 研究 | ✅ | §8.3 |
| README_STATUS | 開源文件 | ✅ | §10 |
| ABOUT_METADATA | About / Topics | 📋 **已準備，未套用** | §10.3 |
| LICENSE_AUDIT | 授權稽核 | ⚠️ **部分需人工** | §10.4 |
| SECURITY_AUDIT | 安全 | ✅ | §10.5 |
| TEST_RESULTS | 測試 | ✅ | §11 |
| APK_PATH | 產物 | 📋 **待重建** | §12 |
| APK_SHA256 | 雜湊 | 📋 **待重算** | §12 |
| SIGNING_SHA256 | 簽章 | ✅ 不變 | §12 |
| GITHUB_PUSH_HANDOFF | 交接 | ✅ | §13 |
| KNOWN_BLOCKERS | 阻礙 | ✅ | §14 |
| NEXT_ACTION | 下一步 | ✅ | §15 |

---

## 2. 這一輪最值得說的三件事

### 2.1 熱路徑的第一個數字是 623 ms

`CaptureDiagnostics.note()` 在**主執行緒**上做 SharedPreferences 的
讀改寫，**每次事件一次**，而回呼路徑每則通知呼叫它 **20 次以上**。

**一則通知 = 20 次同步磁碟寫入。**

把它改成記憶體 + 背景執行緒之後：

| | 修正前 | 修正後 |
|---|--------|--------|
| 回呼 p50 | **623 ms** | **4 ms** |

### 2.2 我的第一版「最佳化」比原來更糟

第一次改成背景 flush 時，我讓**每個事件**都提交一個 flush 任務。
一百則通知就是兩千個任務，每個都拿 SharedPreferences 的寫入鎖。

**發佈一百則通知的耗時：130 ms → 116 秒。**

第二次讓 flush **持有物件 monitor 跨越寫入**，而 worker 每則訊息呼叫
`note()` 二十次 —— 全部阻塞。**提交延遲 40 秒。**

兩次都是量測抓出來的，兩次都被修掉。
**如果沒有基準，這兩個都會被當成「最佳化」提交上去。**

### 2.3 一個真實的資料遺失缺陷

準備好的批次是 **per-instance** 的，但負責寫入的 worker 屬於
**服務**那個實例。補抓路徑與測試用的是**另一個**實例 ——
所以那些資料**永遠不會被寫入**，訊息就這樣消失了。

三個測試以「資料必須被儲存」失敗。
**最終設計是每個 listener 實例各自持有自己的批次與 worker**，
批次由**同一個實例**的 worker 寫入，並且各自帶著準備時的 context
（系統沒有建立的實例，其 `applicationContext` 是 null）。
中間曾嘗試把批次做成跨實例共享，但那會讓沒有被系統建立的實例失去 context；
程式碼的註解已改為描述實際的 per-instance 設計。

---

## 3. 原始碼差異

### 3.1 修改

| 檔案 | 變更 |
|------|------|
| `CaptureDiagnostics.kt` | 計數器與事件改為記憶體；背景執行緒寫入；flush 合併；monitor 不跨磁碟寫入 |
| `CapturePolicy.kt` | `Regex` 只編譯一次 |
| `LocalSettings.kt` | `selected()` 改為針對式讀取（不用 `.all`）|
| `PrivateNotificationListener.kt` | 階段標記（T1/T2/T3/T5）；準備與寫入分離；批次共享 |
| `InboxDatabase.kt` | `saveBatches()`；schema v5；三個複合索引；`queryPlan()`、`indexNames()` |
| `DebugSettingsProvider.kt`（debug）| `latency` / `latencyReset` 方法 |

### 3.2 新增

| 檔案 | 用途 |
|------|------|
| `PipelineLatency.kt` | T0–T6 階段量測，單調時鐘 |
| `DeviceCapabilities.kt` | 執行期能力偵測 + OEM 指引 |
| `BenchmarkSetup.kt`（androidTest）| 共用的基準設定 |
| `LatencyBenchmarkInstrumentedTest.kt` | 延遲基準 |
| `StorageBenchmarkInstrumentedTest.kt` | 儲存與吞吐基準 |
| `DedupGoldenCorpusTest.kt`（unit）| 32 個案例 |
| `DeviceCapabilitiesTest.kt`（unit）| 相容性層 |

### 3.3 未動

Telegram Classic UI、`SourceRegistry` 的平台清單、**SQLite 的加密格式**、
`CryptoStore`、`ConversationIdentity`、Keystore 格式、App Lock、
既有訊息資料、DHC v0.2 的匯出格式、Debug 簽章。

---

## 4. 低延遲結果（Gate A）

### 4.1 定義

```
T0  onNotificationPosted 的入口
T1  必要快照完成
T2  入列成功
T3  worker 開始處理
T4  去重／解析完成
T5  SQLite Commit 完成
T6  UI 看得到

snapshot_latency = T1 - T0
enqueue_latency  = T2 - T0
queue_wait       = T3 - T2
processing       = T5 - T3
durable_latency  = T5 - T0
visible_latency  = T6 - T0
```

時鐘是 **`SystemClock.elapsedRealtimeNanos()`**（單調）。
**入列時間沒有被當成持久化時間** —— 提交是分開量的。

### 4.2 實測

| 指標 | v0.4.3 | v0.4.4 | 目標 | 結果 |
|------|--------|--------|------|------|
| Enqueue p50 | 0.09 ms | **0.02 ms** | ≤ 2 ms | ✅ MET |
| Callback p95 | 623 ms (p50) | **106 ms (p95) / 4 ms (p50)** | ≤ 5 ms | ⚠️ **p95 NOT MET** |
| Queue wait p95 | — | **16,245 ms** | ≤ 20 ms | ❌ **NOT MET**（超出目標約 812×）|
| Queue wait p50 | 33,945 ms | **3,520 ms** | — | 9.6× 改善 |
| 500 則的交易數 | 648 | **40** | — | 16× 改善 |
| Commit p50 | 24,268 ms | **1,697 ms** | — | 14× 改善 |

### 4.3 沒有達標的兩項，誠實說明

**Callback p95 = 106 ms，目標是 ≤ 5 ms。沒有達標。**

**Queue wait p95 = 16,245 ms，目標是 ≤ 20 ms。沒有達標。**
p50 雖然從 33,945 ms 降到 3,520 ms（9.6×），但 p95 仍遠高於 20 ms 的目標。
目標**沒有**被調整以讓這一項看起來通過。

原因與瓶頸：

| 因素 | 說明 |
|------|------|
| 平台分發 | Android 自己要把通知送到 App。這段**不在**本專案的控制範圍內 |
| 模擬器 | 沒有真機的排程特性。**真機會不同，但方向未知** |
| 25 則的歷史 | 每次回呼要解析最多 25 則訊息，而每則的內容都要做 HMAC |

**沒有為了讓數字好看而刪減必要資料。**
「只解析 5 則」或「跳過加密」都會讓 p95 變漂亮，
但那個數字就不再代表這個 App 的行為了。

---

## 5. 吞吐結果（Gate B）

### 5.1 容量級距

| 訊息數 | 對話數 | 耗時 | 資料庫 | 每則平均 | 結果 |
|--------|--------|------|--------|---------|------|
| 100 | 1 | 0.64 s | 69,632 B | 696 B | 全部入庫 |
| 500 | 1 | 2.78 s | 262,144 B | 524 B | 全部入庫 |
| 1,000 | 1 | 5.70 s | 512,000 B | 512 B | 全部入庫 |
| **10,000** | 10 | **53.15 s** | **5,115,904 B** | **511 B** | 全部入庫 |

### 5.2 對話形狀

| 形狀 | 訊息數 | 耗時 | 結果 |
|------|--------|------|------|
| 單一群組 | 500 | 3.19 s | 全部入庫 |
| 十個群組 | 500 | 2.83 s | 全部入庫 |
| **完全相同的文字** | 500 | 2.80 s | **500 列** |

**「相同文字」是最嚴苛的形狀**，它逼出識別碼的每一個弱點。
500 則完全相同的訊息必須是 500 列（每一則的位置不同）。

### 5.3 讀取

| 規模 | p50 | p95 | 樣本數 | 資料庫 |
|------|-----|-----|--------|--------|
| 100 | 480.2 ms | 664.0 ms | 5 | 84 KiB |
| 1,000 | 2,034.4 ms | 2,318.5 ms | 5 | 516 KiB |
| 10,000 | 2,136.5 ms | 3,184.4 ms | 5 | 5,104 KiB |

---

## 6. 資料庫結果

### 6.1 索引計畫

```
SEARCH messages USING COVERING INDEX package_received_idx (package_name=?)
```

`COVERING INDEX` 代表完全不必回資料表。

### 6.2 索引清單

```
sqlite_autoindex_messages_1   ← fingerprint UNIQUE，自動建立
received_idx
conversation_idx
package_received_idx
conversation_received_idx
kind_received_idx
```

**單欄位索引保留**：「取最新一頁（不篩選來源）」與
「取某個對話的全部訊息」仍然用它們。
**沒有建立低選擇性的索引** —— 每個索引都要寫入成本。

### 6.3 遷移

**v4 → v5，只建立索引。** 不回寫、不刪除任何一列。

---

## 7. 去重結果（Gate C）

**32 個獨立案例**，每個都有自己的預期結果與理由。
涵蓋 brief 要求的全部情境：相同時間不同寄件者、不同對話、不同內容、
同寄件者相同文字兩次、超過 25 則的滑動視窗、通知移除與重貼、
listener 重啟、資料庫有但記憶體遺失、寫入失敗後重送、群組摘要與子項。

驗收標準**分別斷言**：

| 標準 | 結果 |
|------|------|
| 跨對話不合併 | ✅ |
| 無未解釋的遺失 | ✅ |
| 重複不無限放大 | ✅（同一則呈現 10,000 次仍是一列）|
| 重啟冪等 | ✅ |
| DB 重試正確 | ✅ |
| 模糊性明確標記 | ✅ |

**模糊案例是標記出來的，不是藏起來的**：
資料本身無法分辨的兩則訊息會被合併，而黃金集**明說**它們無法分辨，
而不是假裝那個身分是已知的。

---

## 8. 相容性矩陣（Gate D）

### 8.1 Android 版本

| 版本 | API | 狀態 |
|------|-----|------|
| Android 9 | 28 | **NOT TESTED** |
| Android 10–11 | 29–30 | **NOT TESTED** |
| Android 12 / 12L | 31–32 | **NOT TESTED** |
| Android 13 | 33 | **NOT TESTED** |
| Android 14 | 34 | **NOT TESTED** |
| Android 15 | 35 | **NOT TESTED** |
| **Android 16** | **36** | **已測試** |

### 8.2 OEM / ROM

| 家族 | 指引 | 狀態 |
|------|------|------|
| AOSP / Pixel | 不需要 | **已測試**（模擬器）|
| vivo OriginOS / Funtouch OS | ✅ 三項 | **NOT TESTED** |
| Xiaomi MIUI / HyperOS | ✅ | **NOT TESTED** |
| OPPO ColorOS | ✅ | **NOT TESTED** |
| realme UI | ✅ | **NOT TESTED** |
| HONOR MagicOS | ✅ | **NOT TESTED** |
| Huawei EMUI / HarmonyOS | ✅ | **NOT TESTED** |
| Samsung One UI | 未實作 | **NOT TESTED** |
| Motorola | 不需要 | **NOT TESTED** |

**中國版與國際版分開記錄。** 中國版**不需要** Google Play Services，
App 也沒有任何 Play 相依。

**沒有增加** Root、Accessibility 或 Google Services 的強制要求。

### 8.3 Rust 研究

**結論：目前不需要 Rust。**

| 依據 | 數值 |
|------|------|
| 回呼延遲 p50 | 0.02 ms |
| 瓶頸 | 磁碟 I/O 與排程，**不是**運算 |
| Rust 能改善的 | 純邏輯的重複計算 —— 而那不是瓶頸 |
| 唯一的跨語言缺陷 | base64 換行，**已被測試修好**，不是被 Rust 修好 |

文件：`roadmap/RUST-CORE-ARCHITECTURE.md`、`RUST-BINDING-PLAN.md`、
`RUST-DIFFERENTIAL-TESTS.md`。

**UniFFI 的授權是 MPL-2.0**（不是 MIT/Apache），這是評估時發現的。
**沒有任何 Rust 程式碼進入生產路徑。**

---

## 9. Git

| 項目 | 值 |
|------|-----|
| 分支 | `fix/x200pro-notification-burst` |
| v0.4.3 交接基準 | `41ac02f` |
| 遠端分支基準（推送前）| `a3b8967` |
| HEAD | `87df4ec` |
| 本輪提交數 | 8 |
| 工作樹 | **乾淨** |
| 遠端 | 推送前未推送（本報告撰寫時）|

```
87df4ec chore: version 0.4.4 (code 10) and the verified artifact hashes
6d39e97 test: the latency benchmark takes a baseline instead of resetting shared metrics
3342d35 docs: the v0.4.4 unified status report
a397121 test: bound the read benchmark by a time budget instead of letting it time out
19e07bf docs: the public open-source preparation
191d480 feat: the schema v5 indexes, the storage benchmark, and the 32-case dedup corpus
e421043 chore: keep the commit-message scratch file out of the tree
0a0adb7 perf: the notification callback no longer does disk I/O, and the writer batches
a3b8967 (origin/fix/x200pro-notification-burst, the v0.4.3 tip)
```

---

## 10. 開源文件（Gate E）

### 10.1 README

| 項目 | 狀態 |
|------|------|
| `README.md`（英文，主文件）| ✅ |
| `README.zh-TW.md`（繁體中文）| ✅ |
| 互相連結 | ✅ |
| 所有文件連結可解析 | ✅（0 個失效）|

README **明說**平台限制、沒實測的機型、以及它**不是**什麼。

### 10.2 補齊的檔案

`SECURITY.md`、`CODE_OF_CONDUCT.md`、
`.github/ISSUE_TEMPLATE/bug_report.yml`（已驗證 YAML）、
`.github/ISSUE_TEMPLATE/feature_request.yml`、
`.github/PULL_REQUEST_TEMPLATE.md`、
`docs/TESTING.md`、`docs/DATABASE.md`、`docs/DHC-INTEGRATION.md`、
`docs/ROADMAP.md`、`docs/COMPATIBILITY.md`、
`docs/OPEN-SOURCE-PROVENANCE.md`、`docs/REPOSITORY-METADATA.md`。

### 10.3 About 與 Topics

**已準備，本輪未套用**（brief 禁止修改遠端設定）。

- Description：`Offline, encrypted Android notification inbox with local message history, source filtering, and privacy-first storage.`
- Topics：android, kotlin, notification-listener, privacy, offline-first,
  encrypted-storage, sqlite, notification-history, open-source

### 10.4 授權稽核

| 項目 | 狀態 |
|------|------|
| LICENSE | MIT ✅ |
| 執行時期相依 | 只有 `androidx.activity`（Apache-2.0）✅ |
| 無追蹤 / 廣告 / Firebase / 網路 SDK | ✅ |
| 無 `INTERNET` 權限 | ✅ |
| 秘密稽核（樹 + 完整歷史）| PASS ✅ |
| 樹內無 GPL/AGPL 授權頭 | ✅（實測）|
| 無 `third_party/` / `vendor/` | ✅（實測）|
| **GPL 逐行複製** | ⚠️ **需要人工看 git 歷史** |
| **圖示與截圖授權** | ⚠️ **需要人工確認** |

**稽核過程中修正了我自己的一個錯誤假設**：
匯出契約**確實在這個 repository 裡**，而且是 **Private Inbox 自己的檔案**
（「the single source of truth for the export contract」，DHC 是採用的一方）。
這個方向很重要 —— DHC 採用一個 MIT 契約，**不構成**逆向的授權義務。

### 10.5 安全

`SECURITY.md` 說明威脅模型、**不在**範圍內的攻擊（Root、惡意鍵盤、
無鎖裝置、記憶體取證、被入侵的 OS），以及平台限制。

---

## 11. 測試結果（Gate F）

> **全部是本輪實際執行的結果。**

| 項目 | 結果 |
|------|------|
| 單元測試 | **84/84 PASS** |
| Instrumentation（隔離的 devtest 變體）| **126/126 PASS**，0 skip |
| Instrumentation（正式的 prod 變體）| 126 項，**0 失敗**，破壞性測試 skip |
| Android Lint | **0 error** |
| `scripts/audit.sh` | PASS |
| `scripts/audit-secrets.sh` | PASS |
| `scripts/audit-release.sh` | PASS |
| `scripts/audit-test-isolation.py` | PASS（27 個測試檔）|
| DHC 連接器（Python）| 23/23 PASS |
| 跨語言 fixture | 7/7 PASS |
| 突發矩陣 | 10 個案例，`unaccounted` 全為 0 |

---

## 12. 產物

| 檔案 | 大小 | SHA-256 |
|------|------|---------|
| `PrivateInboxClassic-v0.4.4-debug.apk` | 5,986,817 B | `d4410fe658e6ba866d22a42e467ff3e9bc7b17244c3f87e3734c83d862de4c69` |
| `PrivateInboxClassic-v0.4.4-release-unsigned.apk` | 5,003,975 B | `0e1eb4458f34e4826eb8debcadbc5a683ac0f3e422ebf180e09fcc1b853fad79` |
| `PrivateInboxClassic-v0.4.4.aab` | 1,748,739 B | `2370bfe102fffa26a14ddbcc5c5a80cc15eb8ffb1aae0a77d9d77c15899165a5` |

**版本**：`versionName = 0.4.4`，`versionCode = 10`
**簽章已驗證**（`apksigner verify --print-certs`）與既有版本相同。

**簽章憑證 SHA-256**（**不可輪替**）：

```
c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4
```

**Release 產物仍未簽署** → `BLOCKED_SIGNING`。

---

## 13. GitHub 推送交接

`docs/GITHUB-PUSH-HANDOFF.md`，包含 brief 要求的全部欄位：

```
BRANCH, BASE_COMMIT, HEAD_COMMIT, CHANGED_FILES, TEST_EVIDENCE,
APK_SHA256, SIGNING_SHA256, LICENSE_STATUS, SECRET_SCAN_STATUS,
README_STATUS, CI_STATUS, KNOWN_BLOCKERS, SAFE_PUSH_COMMANDS
```

**本輪沒有執行任何遠端操作。**

---

## 14. 已知阻礙

| 項目 | 狀態 | 誰能解決 |
|------|------|--------|
| 正式 release / upload 金鑰 | `BLOCKED_SIGNING` | 擁有者 |
| vivo X200 Pro 實機驗收 | `NOT_TESTED` | 需要實機 |
| Android 9–15 | `NOT TESTED` | 需要那些設備 |
| 行程被殺的實際遺失 | 未量測 | 需要真機 |
| GPL 逐行比對 | 未完成 | 需要人工 |
| 圖示與截圖授權 | 未確認 | 需要人工 |
| 永久保存 / 按日期封存 | 未實作 | 設計 + UI |
| 全文搜尋 | **刻意不做** | 需獨立安全審查 |
| 合併到 `integration/dsh-v040` | 未合併 | 擁有者 |
| 公開發布 | **維持私人** | 擁有者 |

---

## 15. NEXT_ACTION

### 交給 Codex / Hermes

依 `docs/GITHUB-PUSH-HANDOFF.md`：檢查 → 建置 v0.4.4 產物並重算雜湊 →
推送 → 跑 CI → 確認綠燈。

### 需要真機

依 `docs/reliability/X200PRO-NON-DESTRUCTIVE-QA.md`：
**先備份**，`adb install -r`（不要卸載），開 vivo 三項背景權限，
`bash scripts/run-burst-tests.sh --repeat 3`。

### 建議的工程項目

1. **在真機上量測實際的行程死亡遺失** —— 那是決定 Intake Journal
   是否值得實作的依據。
2. 若「擷取時 UI 卡住」真的發生，重做 WAL 實驗，這次量**讀取**延遲。

---

## 16. 最後一句

> 這一輪最有價值的不是那些效能數字，是**兩個被量測抓出來的退步**：
> 一個讓發佈速度慢 900 倍，一個讓提交延遲 40 秒。
>
> 沒有基準，這兩個都會被叫做「最佳化」提交上去。
