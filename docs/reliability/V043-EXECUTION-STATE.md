# v0.4.3 執行狀態（V043-EXECUTION-STATE）

> 分支：`fix/x200pro-notification-burst`
> 基線：`41ac02f`（v0.4.2 狀態報告）
> 本文件記錄的是**這一輪實際做了什麼**。

---

## 1. 狀態恢復（R1）

| 項目 | 報告值 | 實際值 | 結論 |
|------|--------|--------|------|
| HEAD | `41ac02f` | `41ac02f` | **一致** |
| 分支 | `fix/x200pro-notification-burst` | 同 | 一致 |
| 工作樹 | — | 乾淨，無未提交變更 | — |
| 磁碟 | — | 21 GiB 可用、inode 41M 可用 | 充足 |
| GitHub | PRIVATE | PRIVATE，`gh auth` 正常 | 可存取 |
| CI | 39450024722 GREEN | 確認為 `success` | 真的綠 |

**上一輪的測試證據是否對應到 commit？**

| 項目 | 值 |
|------|-----|
| JUnit XML | `app/build/outputs/androidTest-results/connected/debug/*.xml` |
| 內容 | 105 tests, 0 failures, 0 skipped |
| XML 時間戳 | `2026-10-21T02:48:46` |
| Commit 時間戳 | `2026-10-21 10:51:21 +0800` |

**XML 比 commit 早 3 分鐘** —— 也就是說那次執行是在 commit **之前**跑的。
但那一輪的 commit `41ac02f` 同時新增了 `ConcurrentWriterInstrumentedTest.kt`
與 `InboxDatabase.journalMode()`，而 105 這個數字**包含**了那 3 項新測試
（原本 102 + 3 = 105）。所以證據對應的是**同一份程式碼**。

**本輪沒有沿用舊報告** —— 所有數字都重新執行過。

---

## 2. P0 — 破壞性測試隔離

### 2.1 缺陷

8 個測試檔直接對目標套件的資料庫呼叫 `db.clear()` / `purgeUndecryptableRows()`。
目標套件就是 `tw.personal.privateinbox` —— **使用者安裝的那一個**。

`run-burst-tests.sh` 也一樣，而上一輪的交接文件正是讓使用者在真機上跑它。

### 2.2 三層保護

| 層 | 機制 | 檔案 |
|----|------|------|
| 隔離 | `devtest` 變體，ID `tw.personal.privateinbox.devtest`，有自己的資料目錄 | `app/build.gradle.kts` |
| 守衛 | 依**套件 ID** 拒絕破壞性操作，訊息說明「資料沒有刪除」 | `DestructiveOperationGuard.kt` |
| 閘門 | 測試繞過守衛 → **建置失敗** | `scripts/audit-test-isolation.py` |

### 2.3 為什麼依套件 ID 而不是 build type

如果依 build type 判斷，`release` 組態裝到手機上就會失去保護。
依套件 ID 判斷，**任何**組態只要是正式套件就會被守住。

### 2.4 量測

| 變體 | 結果 |
|------|------|
| devtest（隔離）| **119 項，0 失敗，0 skip** |
| prod（正式 App）| **119 項，0 失敗，66 skip** |

正式套件上的破壞性測試是**帶原因的 skip**：

```
SKIPPED: this test destroys the database, so it only runs against the
ISOLATED variant (tw.personal.privateinbox.devtest). The target here is
tw.personal.privateinbox, and nothing was deleted.
```

### 2.5 閘門的雙向驗證

```
乾淨的樹          → PASS（24 個測試檔）
注入 db.clear()    → FAIL（2 個發現）
```

**閘門抓到了本輪自己新增的測試**（`ProcessDeathExperiment`）——
那是真實的證據：它不是裝飾。

### 2.6 變體帶來的連帶修改

權限名稱、provider authority、自我測試來源套件、listener 元件
都變成**依執行中的套件解析**（`VariantNames.kt`），
所以同一份測試程式碼可以對兩個變體執行。

---

## 3. P1 — 去重識別碼審查

### 3.1 找到的兩個缺陷

| # | 缺陷 | 後果 |
|---|------|------|
| 1 | 來源有時間戳時，**識別碼被完全丟棄** | 同一毫秒的相同文字合併 |
| 2 | 指紋用**顯示名稱**，不是 `conversation_key` | 兩個同名群組合併 |

### 3.2 修正

- 指紋改用 `conversation_key`（沒有才退回 title）。
- 識別碼**永遠**納入，且**內容優先**：
  `ts:<時間戳>:c:<寄件者>:<內文>`，沒有時間戳則 `c:<寄件者>:<內文>:i<索引>`。
- 記憶體過濾從 `Set` 改成**計數**。

### 3.3 測試

`DeduplicationIdentityTest` 8 項，涵蓋 brief 的 A–E 五個案例。

---

## 4. P1 — 擷取計數驗證

### 4.1 brief 指出的不一致

| 報告 | 數字 | 問題 |
|------|------|------|
| 根因文件 | `submitted=250 saved=250 unaccounted=250` | 依公式應為 0 |
| 狀態報告 | `unaccounted=3,821` | 混用了**另一次執行**的 `deduped` |

**兩個都不對。** 根因是 `presented`（訊息**出現次數**）被拿來減
`saved`（**列數**）與另一個來源��� `deduped`。

### 4.2 現在的定義

```
unaccounted = accepted − committed − duplicates − failures − inFlight
```

全部是**訊息單位**。`presented` 與 `callbacks` 不參與這個計算，
因為它們是**出現次數**與**回呼次數**。

### 4.3 機器可檢查

`CaptureAccountingTest` 8 項，明確列出每一個必須成立的關係：

```
callbacks        >= attempts
presented        >= callbacks
candidates       <= presented
accepted         <= candidates
committed + duplicates + failures + inFlight <= accepted
unaccounted      >= 0
```

---

## 5. P1 — WAL 實驗

### 5.1 結果

```
mode=truncate  writes=500  failures=0  busy=0  p50=25  p95=41  p99=63  max=252
mode=wal       writes=500  failures=0  busy=0  p50=24  p95=40  p99=77  max=330
```

**WAL 沒有改善，因此沒有啟用。** 保留基準程式碼。

### 5.2 基準本身的缺陷（已修）

第一版寫成 `PRAGMA journal_mode = \$mode`，跳脫寫錯，
送出去的是字面值 `$mode`。所以「切到 WAL」**根本沒發生**，
讀回來一直是 `truncate` —— 那根本不是 A/B。

修正後讀回 `set=wal read=wal`。

### 5.3 誠實陳述

WAL **不會**移除 SQLite 的單一 writer 規則。
它解決的是「讀寫互相阻塞」，而這個 App 的讀寫負載不重疊：
只有**一個** writer，讀取發生在使用者捲動時。

**這份實驗沒有覆蓋**：讀寫交錯、圖片拖慢寫入、低磁碟空間、崩潰復原。
文件寫明了若「擷取時 UI 卡住」出現，必須重做實驗並改量**讀取**延遲。

---

## 6. P2 — 行程死亡實驗

### 6.1 量測

```
DEATH-WINDOW received=200 queued=1 processing=0 committed=200
             acknowledged=0 failed=0 lost=0 drain_ms=5565
```

`drain_ms` = 遺失視窗 = 事件只存在於記憶體的時間。
刻意放慢 writer 時是 5.6 秒（正常操作的佇列等待 p99 是 2.1 秒）。

### 6.2 沒做的事與原因

**沒有真的殺行程。**
在真機上殺行程會影響使用者的資料；在模擬器上殺不等於 OriginOS 的行為。
所以這是**視窗的上界量測**，不是「殺掉後遺失幾則」的計數。

### 6.3 Intake Journal

**維持設計文件，未接入正式管線。**
證據不足以說明它有足夠改善，而一個寫錯的 journal 會讓擷取**整個停擺**。

契約、狀態機、必須滿足的條件、與**無法關閉的缺口**
（`RECEIVED` 與 `JOURNALED` 之間被殺 → 遺失）
都在 `docs/reliability/INTAKE-JOURNAL.md`。

---

## 7. P2 — X200 Pro

**REAL_DEVICE = NOT_TESTED。** 本輪沒有連接真機。

| 項目 | 狀態 |
|------|------|
| vivo X200 Pro 連線 | **NOT_CONNECTED** |
| OriginOS 版本 | **UNKNOWN** |
| 通知存取 | **NOT_TESTED** |
| vivo 三項背景權限 | **NOT_TESTED** |
| 鎖屏 / 省電模式 | **NOT_TESTED** |
| 多群組同時發送 | **NOT_TESTED** |

流程寫在 `docs/reliability/X200PRO-NON-DESTRUCTIVE-QA.md`，
**是程序，不是結果**。

---

## 8. 交付

| 檔案 | SHA-256 |
|------|---------|
| `PrivateInboxClassic-v0.4.3-debug.apk` | `41c9119f645c8c9d306f4afd62efa7eb8dd6370994cd579fc878bd767a5d7f22` |
| `PrivateInboxClassic-v0.4.3-release-unsigned.apk` | `fd5d26e5d0ad78368b319f52396050fd3f07f77fda12fdf53d3ed69f5fa3f5d9` |
| `PrivateInboxClassic-v0.4.3.aab` | `adf17f9f2f4ffe6d2cb720f4651c8ac75f0ac24f51872c86d67def9097d8672b` |

簽章憑證 SHA-256：`c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`
（**與使用者手機上既有版本相同**，`adb install -r` 可直接覆蓋，不刪資料）

AAB **未簽署**，`versionName=0.4.3`、`versionCode=9`。

---

## 9. 保留的產品

以下**完全沒有**被改動：

- Telegram Classic UI
- `SourceRegistry` 的平台清單（只**新增**了 devtest 自我測試來源）
- SQLite schema（v4）
- Keystore 加密格式
- App Lock
- 既有訊息歷史
- DHC Connector v0.2

本輪**沒有**進行：Rust 正式核心、通知鏡像實作、圖片擷取重建、
雲端同步、公開上架。
