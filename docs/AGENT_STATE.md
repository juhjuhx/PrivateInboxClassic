# AGENT_STATE — Private Inbox Classic v0.4.4

> 這份檔案是給**下一個**接手的工作者（人或 AI agent）看的。
> 它記錄的是「現在在哪、接下來做什麼」，不是成就報告。

## 快速狀態

| 項目 | 值 |
|------|-----|
| PROJECT | Private Inbox Classic (`tw.personal.privateinbox`) |
| CURRENT_BRANCH | `fix/x200pro-notification-burst` |
| BASELINE | `41ac02f`（v0.4.3 狀態報告）|
| CURRENT_PHASE | v0.4.4 Performance & Portability — 完成 |
| CURRENT_TASK | 交由 Codex / Hermes 執行 GitHub 推送 |
| LAST_SUCCESS | 見「驗證狀態」一節 |
| LAST_FAILURE | 無（本輪所有失敗皆已修正並重跑通過）|
| GIT_HEAD | `87df4ec`（v0.4.4 程式碼）；文件修正另成一個 commit |
| WORKTREE | `<workspace>/PrivateInboxClassic-v0.2/PrivateInboxClassic-v0.4-wt` |
| DISK_FREE | 約 11 GiB（建置後）|
| QUEUE_TEST_STATUS | PASS（10 個突發案例，unaccounted 全為 0）|
| BUILD_STATUS | BUILD SUCCESSFUL |
| TEST_STATUS | 見「驗證狀態」一節 |
| APK_STATUS | 已重建（prod debug / prod release-unsigned / prodRelease AAB，SHA-256 見 `docs/GITHUB-PUSH-HANDOFF.md` §5）|
| GITHUB_STATUS | **PRIVATE**；推送由本輪 Reviewer 依 `docs/GITHUB-PUSH-HANDOFF.md` 執行 |
| DEVICE_STATUS | **REAL_DEVICE=NOT_TESTED**（沒有連接 X200 Pro）|
| NEXT_ACTION | 由 Codex / Hermes 依 `docs/GITHUB-PUSH-HANDOFF.md` 推送並跑 CI |
| BLOCKERS | 正式 release 金鑰未由擁有者建立；真機未連接；GPL 來源需人工逐行比對 |

---

## 驗證狀態

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

---

## 1. 這一輪完成了什麼

| 階段 | 內容 | 狀態 |
|------|------|------|
| R0 | 狀態恢復：HEAD `a3b8967` 與回報值一致、工作樹乾淨、CI 39644240175 綠 | ✅ |
| §2 | 延遲分段量測（T0–T6，單調時鐘）| ✅ |
| §2 | 熱路徑最佳化（回呼 623 ms → 4 ms）| ✅ |
| §2 | 寫入批次（交易數 648 → 40）| ✅ |
| §3 | 儲存與吞吐基準（100–10,000 列）| ✅ |
| §4 | Schema v5 複合索引（`EXPLAIN QUERY PLAN` 驗證）| ✅ |
| §5 | 去重黃金集（32 個案例）| ✅ |
| §6 | 相容性分層（`DeviceCapabilities` + OEM 指引）| ✅ |
| §7 | Rust Core 研究（三份文件，**不接入**）| ✅ |
| §8 | 開源文件（README 中英、metadata、templates、授權稽核）| ✅ |
| §9 | Git 準備（**未推送**）| ✅ |

---

## 2. 這一轮的三個關鍵數字

| 指標 | 修正前 | 修正後 |
|------|--------|--------|
| 回呼 p50 | **623 ms** | **4 ms** |
| 佇列等待 p50（500 則）| **33,945 ms** | **3,520 ms** |
| 500 則訊息的交易數 | **648** | **40** |

**瓶頸不是運算，是磁碟 I/O。** 這是 Rust 研究得出「目前不需要 Rust」的依據。

---

## 3. 本輪修正的真實缺陷

| 缺陷 | 後果 |
|------|------|
| 準備與寫入分屬不同實例 | 其他實例（補抓路徑、測試）準備的資料**永遠不會被寫入**，訊息就這樣消失；最終改為每個實例各自持有批次與 worker |
| 診斷 flush 持有 monitor 跨越寫入 | worker 的每次 `note()` 都阻塞，提交延遲到 **40 秒** |
| 每個事件都提交一個 flush 任務 | 發佈一百則通知從 130 ms 變成 **116 秒** |
| `normalize()` 每次呼叫編譯新的 Regex | 每則通知最多 50 個 Regex，編譯在主執行緒 |
| `LocalSettings.selected()` 走 `.all` | 每則通知把整個偏好設定檔載入成 map |
| 累計計數器在共享行程裡被 reset | **量測本身是錯的** —— 一個測試會清掉另一個正在量測的測試的計數器 |

---

## 4. 現有的重要事實

- **Gradle 啟動失敗不是套件損壞**，是 `~/.gradle` 不可寫 → `GRADLE_USER_HOME`。
- **emulator 需要 `HOME=<workspace>/emulator-home`**。
- **裝置必須解鎖**，否則 Keystore（CE-backed）無法使用。
- **instrumentation APK 是獨立套件** → 需要 debug 的 `DebugSettingsProvider`。
- **instrumentation 會重裝 App** → App 的 external files 目錄會被刪除。
- **App 沒有儲存空間權限** → 測試要用 `adb exec-out cat` 傳位元組進 App cache。
- **平台限制**：`MessagingStyle` 最多 25 則。
- **`journal_mode` 是 `truncate`**，不是 WAL（實測後決定**不**啟用）。
- **WAL 不移除 SQLite 的單一 writer 規則**，只是讓讀寫不互相阻塞。
- **Android 13+ 發通知需要 `POST_NOTIFICATIONS`**，沒有它 `notify()` 會被靜默丟棄。
- **診斷的 flush 必須在 monitor 之外寫入**。

---

## 5. 下一步

### 5.1 交給 Codex / Hermes（**本輪不做**）

```bash
# 1. 檢查
git status --short                 # 必須是空的
bash scripts/audit-secrets.sh      # 必須 PASS
python3 scripts/audit-test-isolation.py   # 必須 PASS
git diff --check 41ac02f..HEAD

# 2. 建置 v0.4.4 的產物並重算雜湊
./scripts/g.sh :app:assembleProdDebug

# 3. 推送
git push origin fix/x200pro-notification-burst

# 4. CI
gh workflow run android.yml --repo juhjuhx/PrivateInboxClassic \
  --ref fix/x200pro-notification-burst
```

詳細的指令在 **`docs/GITHUB-PUSH-HANDOFF.md`**。

### 5.2 需要真機

依 `docs/reliability/X200PRO-NON-DESTRUCTIVE-QA.md` 執行。
**必須先備份**，而且**不要**卸載正式 App。

### 5.3 需要授權

- 正式 release / upload 金鑰（`BLOCKED_SIGNING`）。
- 合併到 `integration/dsh-v040`（本分支**未**合併）。
- 公開發布（本專案維持**私人**）。

---

## 6. 重要路徑

| 用途 | 路徑 |
|------|------|
| 專案 | `<workspace>/PrivateInboxClassic-v0.2/PrivateInboxClassic-v0.4-wt` |
| 工具鏈 | `<workspace>/android-sdk`、`<workspace>/gradle_home` |
| Emulator AVD | `<workspace>/.android-home/avd/test36.avd` |
| Debug keystore | `<worktree>/keystore/debug.keystore`（git-ignored）|

---

## 7. 不可破壞的規則

完整清單見 `AGENTS.md`。這一輪新增的：

6. **不得在正式套件上執行破壞性測試。** 守衛會拒絕，稽核腳本會讓建置失敗。
7. **不得宣稱沒實際跑過的測試通過。** 真機的每一格都是 NOT_TESTED。
8. **不得把入列時間當成持久化時間。** 提交階段是分開量的。
9. **不得在共享行程裡重置累計計數器。** 取 baseline 然後相減。
