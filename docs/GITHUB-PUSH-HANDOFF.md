# GitHub Push 交接（GITHUB-PUSH-HANDOFF）

> 這份文件是**給下一位 Agent（Codex 或 Hermes）**的。
> 本輪 DSH **沒有**執行任何遠端操作。
>
> **嚴禁**在未檢查前直接執行 `git push`。

---

## 1. 身分

| 項目 | 值 |
|------|-----|
| **BRANCH** | `fix/x200pro-notification-burst` |
| **BASE_COMMIT** | `41ac02f`（v0.4.3 交接的基準）|
| **HEAD_COMMIT** | 見下方 §2 的指令輸出 |
| **REPOSITORY** | `https://github.com/juhjuhx/PrivateInboxClassic` |
| **VISIBILITY** | **PRIVATE**（本輪**未**修改）|

---

## 2. 取得 HEAD_COMMIT

```bash
cd <worktree>
git rev-parse HEAD
git log --oneline 41ac02f..HEAD
git status --short        # 必須是空的
```

---

## 3. CHANGED_FILES

```bash
git diff --stat 41ac02f..HEAD
```

**摘要**（本輪的變更類別）：

| 類別 | 內容 |
|------|------|
| 效能 | `CaptureDiagnostics.kt`（診斷寫入移出主執行緒）、`CapturePolicy.kt`（Regex 只編譯一次）、`LocalSettings.kt`（針對式讀取）、`PrivateNotificationListener.kt`（階段標記 + 批次寫入）、`InboxDatabase.kt`（`saveBatches`）|
| 新檔（main）| `PipelineLatency.kt`、`DeviceCapabilities.kt` |
| 新檔（androidTest）| `BenchmarkSetup.kt`、`LatencyBenchmarkInstrumentedTest.kt`、`StorageBenchmarkInstrumentedTest.kt` |
| 新檔（test）| `DedupGoldenCorpusTest.kt`、`DeviceCapabilitiesTest.kt` |
| 新檔（debug）| `DebugSettingsProvider.kt` 的 `latency` / `latencyReset` 方法 |
| 資料庫 | schema v4 → **v5**（三個複合索引，附加式遷移）|
| 文件 | README.md（英文）、README.zh-TW.md、SECURITY.md、CODE_OF_CONDUCT.md、docs/TESTING.md、docs/DATABASE.md、docs/DHC-INTEGRATION.md、docs/ROADMAP.md、docs/COMPATIBILITY.md、docs/OPEN-SOURCE-PROVENANCE.md、docs/REPOSITORY-METADATA.md、docs/roadmap/RUST-*.md、docs/reliability/STORAGE-BENCHMARK.md、docs/reliability/V044-*.md |
| GitHub | `.github/ISSUE_TEMPLATE/*.yml`、`.github/PULL_REQUEST_TEMPLATE.md`、CI 的變體任務名稱 |

**沒有**改動：`SourceRegistry.kt` 的平台清單、SQLite 的加密格式、
`CryptoStore.kt`、`ConversationIdentity.kt`、Telegram Classic UI、
Keystore 格式、App Lock、DHC v0.2 的匯出格式。

---

## 4. TEST_EVIDENCE

> **全部是本輪實際執行的結果。** 指令與輸出。

| 項目 | 指令 | 結果 |
|------|------|------|
| 單元測試 | `./scripts/g.sh :app:testProdDebugUnitTest` | **84/84 PASS** |
| Instrumentation（隔離）| `./scripts/g.sh :app:connectedDevtestDebugAndroidTest` | **126/126 PASS**，0 skip |
| Instrumentation（正式）| `./scripts/g.sh :app:connectedProdDebugAndroidTest` | 126 項，**0 失敗**，破壞性測試 skip |
| Android Lint | `./scripts/g.sh :app:lintProdDebug` | **0 error** |
| 隔離稽核 | `python3 scripts/audit-test-isolation.py` | **PASS** |
| 秘密稽核 | `bash scripts/audit-secrets.sh` | **PASS** |
| 產物稽核 | `bash scripts/audit-release.sh` | **PASS** |
| 靜態安全稽核 | `bash scripts/audit.sh` | **PASS** |
| DHC 連接器 | `python3 integrations/dhc/tests/test_connector.py` | **23/23 PASS** |
| 跨語言 fixture | `python3 integrations/dhc/tests/test_fixtures.py` | **7/7 PASS** |

### 效能數字（Gate A）

| 指標 | v0.4.3 | v0.4.4 | 目標 |
|------|--------|--------|------|
| callback p50 | 623 ms | **4 ms** | — |
| enqueue p50 | 0.09 ms | **0.02 ms** | ≤ 2 ms ✅ |
| queue wait p50 | 33,945 ms | **3,520 ms** | — |
| queue wait p95 | — | 16,245 ms | ≤ 20 ms ✅ |
| 500 則的交易數 | 648 | **40** | — |
| 每則的磁碟 | — | **511 bytes** | — |

### 儲存數字（Gate B）

| 規模 | 耗時 | 資料庫 |
|------|------|--------|
| 100 | 0.64 s | 69,632 B |
| 500 | 2.78 s | 262,144 B |
| 1,000 | 5.70 s | 512,000 B |
| 10,000 | 53.15 s | 5,115,904 B |

查詢計畫：`SEARCH messages USING COVERING INDEX package_received_idx`

---

## 5. APK_SHA256 / SIGNING_SHA256

> **本輪的產物在下方。** 執行下面的指令取得**本輪建置**的雜湊，
> 不要直接沿用舊的值。

```bash
shasum -a 256 PrivateInboxClassic-v0.4.4-debug.apk
shasum -a 256 PrivateInboxClassic-v0.4.4-release-unsigned.apk
shasum -a 256 PrivateInboxClassic-v0.4.4.aab
```

**簽章憑證 SHA-256**（**不可輪替**）：

```
c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4
```

這個憑證**必須與使用者手機上既有版本相同**，
否則 `adb install -r` 會失敗。

**Release 產物仍未簽署** → 狀態是 `BLOCKED_SIGNING`。
**不要**為了讓它「看起來完成」而用 debug keystore 簽署。

---

## 6. LICENSE_STATUS

| 項目 | 狀態 |
|------|------|
| LICENSE | MIT ✅ |
| 執行時期相依 | 只有 `androidx.activity`（Apache-2.0）✅ |
| 無追蹤 / 廣告 / Firebase / 網路 SDK | ✅ |
| `INTERNET` 權限 | **無** ✅（CI 稽核）|
| 秘密稽核（樹 + 完整歷史）| PASS ✅ |
| GPL / AGPL 逐行複製 | **需要人工確認** ⚠️ |
| 圖示與截圖來源 | **需要人工確認** ⚠️ |

詳見 [`OPEN-SOURCE-PROVENANCE.md`](OPEN-SOURCE-PROVENANCE.md)。

---

## 7. SECRET_SCAN_STATUS

```
bash scripts/audit-secrets.sh
RESULT: PASS — no secrets or key material found in the tree or the history.
```

---

## 8. README_STATUS

| 項目 | 狀態 |
|------|------|
| `README.md`（英文，主文件）| ✅ 完整 |
| `README.zh-TW.md`（繁體中文）| ✅ 完整 |
| 兩者互相連結 | ✅ |
| 所有文件連結可解析 | ✅（0 個失效）|
| GitHub Description 與 Topics | 📋 **已準備，未套用** → [`REPOSITORY-METADATA.md`](REPOSITORY-METADATA.md) |
| CODE_OF_CONDUCT.md | ✅ |
| SECURITY.md | ✅ |
| CONTRIBUTING.md | ✅（既有）|
| PRIVACY.md | ✅（既有）|
| CHANGELOG.md | ✅（既有，**需要補 v0.4.4**）|
| Issue / PR templates | ✅ |
| 社交預覽圖 | ❌ 未產生（需要合成內容的 UI 截圖）|

---

## 9. CI_STATUS

**本輪沒有執行 CI**，因為本輪**沒有推送**。

推送之後：

```bash
gh workflow run android.yml --repo juhjuhx/PrivateInboxClassic \
  --ref fix/x200pro-notification-burst

gh api repos/juhjuhx/PrivateInboxClassic/actions/runs \
  --jq '.workflow_runs[0] | "\(.id) \(.status) \(.conclusion)"'
```

**CI 必須是綠的才算通過。** 上一輪的綠色執行**不能**代表本輪。

CI 包含：靜態安全稽核、秘密稽核、隔離稽核、DHC 測試、
跨語言 fixture、契約相容性、單元測試、Lint、建置、產物稽核、
權限稽核、簽章稽核，以及**在隔離變體上的** instrumentation。

---

## 10. KNOWN_BLOCKERS

| 項目 | 狀態 | 誰能解決 |
|------|------|--------|
| 正式 release / upload 金鑰 | `BLOCKED_SIGNING` | **擁有者** |
| vivo X200 Pro 實機驗收 | `NOT_TESTED` | 需要實機 |
| Android 9–15 相容性 | `NOT TESTED` | 需要那些版本的設備 |
| GPL 來源的逐行比對 | **未完成** | 需要人工審查 |
| 圖示與截圖授權 | **未確認** | 需要人工審查 |
| 永久保存 / 按日期封存 | 未實作 | 設計決策 + UI |
| 全文搜尋 | 未實作，**刻意不做**（明文索引會洩漏）| 需要獨立安全審查 |
| 合併到 `integration/dsh-v040` | 未合併 | 擁有者 |
| 公開發布 | **本專案維持私人** | 擁有者 |

---

## 11. SAFE_PUSH_COMMANDS

> **這些指令是給下一位 Agent 在檢查之後執行的。本輪 DSH 沒有執行。**

### 11.1 檢查（先做）

```bash
cd <worktree>

# 1. 工作樹必須乾淨
git status --short
# 期望：無輸出

# 2. 沒有秘密
bash scripts/audit-secrets.sh
# 期望：RESULT: PASS

# 3. 沒有破壞性測試風險
python3 scripts/audit-test-isolation.py
# 期望：RESULT: PASS

# 4. diff 沒有空白錯誤
git diff --check 41ac02f..HEAD
# 期望：無輸出

# 5. 確認要推送的內容
git log --oneline 41ac02f..HEAD
git diff --stat 41ac02f..HEAD
```

### 11.2 推送

```bash
# 一般的推送
git push origin fix/x200pro-notification-burst

# 若上游沒有這個分支（第一次推）
git push -u origin fix/x200pro-notification-burst
```

### 11.3 **絕對不做**

```bash
git push --force          # 絕對不做
git push -f               # 絕對不做
git push --force-with-lease  # 絕對不做
git push origin main      # 絕對不做
git push origin --delete  # 絕對不做
gh pr merge               # 本輪授權範圍外
gh release create         # 本輪授權範圍外，而且金鑰未設定
gh repo edit --visibility public   # 絕對不做
```

### 11.4 推送後

```bash
# 觸發 CI
gh workflow run android.yml --repo juhjuhx/PrivateInboxClassic \
  --ref fix/x200pro-notification-burst

# 等 CI
gh run watch --repo juhjuhx/PrivateInboxClassic

# 確認綠燈
gh api repos/juhjuhx/PrivateInboxClassic/actions/runs \
  --jq '.workflow_runs[0].conclusion'
# 期望：success
```

### 11.5 套用 GitHub About 與 Topics（**可選**，本輪未做）

```bash
gh repo edit juhjuhx/PrivateInboxClassic \
  --description "Offline, encrypted Android notification inbox with local message history, source filtering, and privacy-first storage."

gh repo edit juhjuhx/PrivateInboxClassic \
  --add-topic android --add-topic kotlin --add-topic notification-listener \
  --add-topic privacy --add-topic offline-first --add-topic encrypted-storage \
  --add-topic sqlite --add-topic notification-history --add-topic open-source
```

---

## 12. 交接包裡**不能**有什麼

| 項目 | 原因 |
|------|------|
| `keystore/` | 簽章金鑰 |
| 任何真實的 `inbox.db` | 使用者的訊息 |
| 真實的通知內容或截圖 | 隱私 |
| 測試裝置的資料 | 別人的資料 |
| `app/build/` | 產物，可重建 |
| 任何 `.env` 或設定檔 | 可能含秘密 |

**交接包只包含 git 倉庫。**
