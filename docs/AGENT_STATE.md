# AGENT_STATE — Private Inbox Classic v0.4.3

> 這份檔案是給**下一個**接手的工作者（人或 AI agent）看的。
> 它記錄的是「現在在哪、接下來做什麼」，不是成就報告。

## 快速狀態

| 項目 | 值 |
|------|-----|
| PROJECT | Private Inbox Classic (`tw.personal.privateinbox`) |
| CURRENT_BRANCH | `fix/x200pro-notification-burst` |
| BASELINE | `41ac02f`（v0.4.2 狀態報告） |
| CURRENT_PHASE | v0.4.3 Reliability Hardening — 完成 |
| CURRENT_TASK | 推送私人倉庫並等待 CI |
| LAST_SUCCESS | 119 項 instrumentation（隔離變體）0 失敗 0 skip；119 項（正式變體）0 失敗 66 skip；66 項 unit；Lint 0 error；四個稽核腳本 PASS |
| LAST_FAILURE | 無（本輪所有失敗皆已修正並重跑通過）|
| GIT_HEAD | 見 `git rev-parse HEAD` |
| WORKTREE | `<workspace>/PrivateInboxClassic-v0.2/PrivateInboxClassic-v0.4-wt` |
| DISK_FREE | 約 20 GiB |
| QUEUE_TEST_STATUS | PASS（10 個突發案例，unaccounted 全為 0）|
| BUILD_STATUS | BUILD SUCCESSFUL（clean）|
| TEST_STATUS | instrumentation 119/119（隔離）、unit 66/66、python 23/23、跨語言 7/7 |
| APK_STATUS | v0.4.3 debug + release-unsigned + AAB 已產出 |
| GITHUB_STATUS | PRIVATE 倉庫，CI 執行中 |
| DEVICE_STATUS | **REAL_DEVICE=NOT_TESTED**（沒有連接 X200 Pro）|
| NEXT_ACTION | 在 X200 Pro 上依 `docs/reliability/X200PRO-NON-DESTRUCTIVE-QA.md` 驗收 |
| BLOCKERS | 正式 release 金鑰未由擁有者建立；真機未連接 |

---

## 1. 這一輪完成了什麼

| 階段 | 內容 | 狀態 |
|------|------|------|
| R0 | 恢復狀態、確認 HEAD、驗證 JUnit XML 對應 | ✅ |
| **P0** | **破壞性測試隔離**（devtest 變體 + 守衛 + 建置閘門）| ✅ |
| P1 | 去重識別碼審查（兩個真實缺陷已修）| ✅ |
| P1 | 擷取計數驗證（機器可檢查、單位標明）| ✅ |
| P1 | WAL A/B 實驗（**結論：不啟用**）| ✅ |
| P2 | 行程死亡實驗 + Intake Journal 契約 | ✅ |
| P2 | X200 Pro 非破壞性流程（**NOT_TESTED**）| ✅ |

### P0：破壞性測試會刪掉使用者的訊息

**真實缺陷**：8 個測試檔直接對**目標 App**（正式套件）的資料庫呼叫
`db.clear()`。在裝著正式 App 的手機上跑測試，會**刪掉使用者已擷取的訊息**。
`run-burst-tests.sh` 也一樣，而上一輪正是讓使用者在真機上跑這支腳本。

**三層保護**：

1. **應用程式 ID 隔離**：`devtest` 變體，ID 為 `tw.personal.privateinbox.devtest`，
   有自己的資料目錄，**物理上碰不到**正式資料庫。
2. **程式層守衛**：`DestructiveOperationGuard` 依**套件 ID**（不是 build type）
   拒絕在正式套件上執行破壞性操作，並明確說「資料沒有刪除」。
   `TestDataIsolation` 是測試唯一被允許的入口，而且只存在於 debug 組態。
3. **建置閘門**：`scripts/audit-test-isolation.py` 在測試繞過守衛時**讓建置失敗**。
   雙向驗證過：乾淨的樹通過，注入違規時報錯。它還額外抓到 3 個
   `purgeUndecryptableRows()` 呼叫。

**量測**：

| 變體 | 結果 |
|------|------|
| devtest（隔離）| 119 項，**0 失敗，0 skip** |
| prod（正式 App）| 119 項，**0 失敗，66 skip** |

正式套件上的破壞性測試是**帶原因 skip**，不是失敗 —— skip 表示
「這裡沒跑，原因是 X」，失敗則會錯誤地表示產品壞了。

### P1：去重鍵不是訊息身分

找到**兩個**真實缺陷：

1. **來源有時間戳時，識別碼被完全丟棄**。時間戳是毫秒解析度，
   同一毫秒的相同文字會合併。
2. **指紋用的是顯示名稱，不是 `conversation_key`**。兩個同名群組會合併 ——
   而 `conversation_key` 存在的理由恰恰就是顯示名稱不足以分辨對話。

修正：指紋改用 `conversation_key`；識別碼**永遠**納入且以內容優先
（時間戳 + 寄件者 + 內文），索引只作最後手段。記憶體過濾從 `Set`
改成**計數**，否則兩則相同內容的訊息會被丟掉一則。

### P1：擷取計數混用單位

brief 指出 `submitted=250 saved=250 unaccounted=250` 在公式下不成立，
而另一份文件的 `3,821` 來自**另一次執行**。兩個數字都不對。

現在計算是**機器可檢查**的（`CaptureAccountingTest`，8 項），
每個關係都明確列出，報告的每個計數器都標明單位。

### P1：WAL 實驗 —— 結論是**不啟用**

```
mode=truncate  writes=500  failures=0  busy=0  p50=25  p95=41  p99=63  max=252
mode=wal       writes=500  failures=0  busy=0  p50=24  p95=40  p99=77  max=330
```

WAL 沒有改善，某些指標還比較差。**沒有啟用 WAL。**
基準程式碼保留在專案裡。

> 第一版的基準有真實缺陷：`PRAGMA journal_mode = \$mode` 的跳脫寫錯，
> 「切到 WAL」根本沒發生（讀回 `truncate`）。修正後讀回 `wal`，
> 這才是有效的 A/B。

### P2：行程死亡實驗

```
DEATH-WINDOW received=200 committed=200 lost=0 drain_ms=5565
```

`drain_ms` 就是遺失視窗：事件**只存在於記憶體**的時間。
刻意放慢 writer 時是 5.6 秒（正常操作的佇列等待 p99 是 2.1 秒）。

實驗**沒有真的殺行程** —— 在真機上殺行程會影響使用者的資料，
在模擬器上殺則不等於 OriginOS 的行為。所以這是視窗的**上界量測**。

**Intake Journal 維持設計文件，未接入正式管線**：證據不足以說明它有足夠
改善，而一個寫錯的 journal 會讓擷取整個停擺。

---

## 2. 現有的重要事實

- **Gradle 啟動失敗不是套件損壞**，是 `~/.gradle` 不可寫 → `GRADLE_USER_HOME`。
- **emulator 需要 `HOME=<workspace>/emulator-home`**。
- **裝置必須解鎖**，否則 Keystore（CE-backed）無法使用。
- **instrumentation APK 是獨立套件** → 需要 debug 的 `DebugSettingsProvider`。
- **instrumentation 會重裝 App** → App 的 external files 目錄會被刪除。
- **App 沒有儲存空間權限** → 測試要用 `adb exec-out cat` 傳位元組進 App cache。
- **平台限制**：`MessagingStyle` 最多 25 則。
- **`journal_mode` 是 `truncate`**，不是 WAL（實測）。
- **WAL 不移除 SQLite 的單一 writer 規則**，只是讓讀寫不互相阻塞。

---

## 3. 下一步

### 3.1 需要真機

```bash
adb install -r PrivateInboxClassic-v0.4.3-debug.apk
# vivo：允許自啟動 + 允許後台高耗電 + 最近任務鎖定（三項都要）
bash scripts/run-burst-tests.sh --repeat 3
```

詳細步驟見 `docs/reliability/X200PRO-NON-DESTRUCTIVE-QA.md`。

### 3.2 需要授權

- 正式 release / upload 金鑰。
- 合併到 `integration/dsh-v040`（本分支未合併）。
- 公開 Release（本專案維持私人）。

### 3.3 建議的工程項目

1. **在真機上量測實際的行程死亡遺失** —— 那是決定 Intake Journal
   是否值得實作的依據。見 `docs/reliability/INTAKE-JOURNAL.md` §5。
2. 若「擷取時 UI 卡住」真的發生，重做 WAL 實驗，這次量**讀取**延遲。

---

## 4. 重要路徑

| 用途 | 路徑 |
|------|------|
| 專案 | `<workspace>/PrivateInboxClassic-v0.2/PrivateInboxClassic-v0.4-wt` |
| 工具鏈 | `<workspace>/android-sdk`、`<workspace>/gradle_home` |
| Emulator AVD | `<workspace>/.android-home/avd/test36.avd` |
| Debug keystore | `<worktree>/keystore/debug.keystore`（git-ignored）|

---

## 5. 不可破壞的規則

完整清單見 `AGENTS.md`。這一輪新增的：

6. **不得在正式套件上執行破壞性測試。** 守衛會拒絕，稽核腳本會讓建置失敗。
7. **不得宣稱沒實際跑過的測試通過。** 真機的每一格都是 NOT_TESTED。
