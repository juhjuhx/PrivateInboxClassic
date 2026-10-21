# AGENT_STATE — Private Inbox Classic v0.4.1

> 這份檔案是給**下一個**接手的工作者（人或 AI agent）看的。
> 它記錄的是「現在在哪、接下來做什麼」，不是成就報告。

## 快速狀態

| 項目 | 值 |
|------|-----|
| PROJECT | Private Inbox Classic (`tw.personal.privateinbox`) |
| CURRENT_BRANCH | `integration/dsh-v040` |
| CURRENT_HEAD | `6ccb69f` 之後的整合提交（見 `git log`） |
| BASELINE | `6ccb69f`（本輪接手時的 HEAD） |
| CURRENT_PHASE | X200 Pro 群組突發漏接 P0 修復 — 模擬器已驗證，真機待驗證 |
| CURRENT_TASK | 在 vivo X200 Pro / OriginOS 上驗證並收集診斷證據 |
| LAST_SUCCESS | 67 項 instrumentation 0 失敗 0 skip；50 項 unit；23 項 python；Lint 0 error；三個稽核腳本 PASS |
| LAST_COMMAND | `./gradlew --no-daemon :app:connectedDebugAndroidTest` |
| LAST_EXIT_CODE | 0 |
| BUILD_STATUS | BUILD SUCCESSFUL |
| TEST_STATUS | instrumentation 67/67、unit 50/50、python 23/23 |
| DISK_STATUS | 22 GiB free（曾因 ENOSPC 失敗，見 `operations/DISK-SPACE-RUNBOOK.md`） |
| BLOCKERS | 正式 release 金鑰未由使用者建立；**vivo X200 Pro 真機未連接，群組突發真機驗證未做** |
| NEXT_ACTION | 把 v0.4.1-burst-debug.apk 覆蓋安裝到 X200 Pro，依 docs/engineering/X200PRO-BURST-FIX.md §3 驗證 |

---

## 1. 這輪做了什麼

| 階段 | 內容 | 狀態 |
|------|------|------|
| R0 | 磁碟故障分類（真實 ENOSPC，非工具額度） | ✅ |
| R1 | 保護可用版本、提交三個資安修正 | ✅ |
| R2 | 安全收斂 + 效能實測 | ✅ |
| R3 | DHC Connector v0.1（跨平台往返已證明） | ✅ |
| R4 | 通知鏡像規格（設計文件，未實作） | ✅ |
| R5 | Rust 遷移研究與 PoC 計畫（未實作） | ✅ |
| R6 | GitHub 私人倉庫 + 文件 + CI | ✅ 完成（CI 綠） |

### 修正的三個真實缺陷

1. **`DebugSettingsProvider` 對所有 App 開放**（`exported="true"` 且無權限）。
   任何 App 都能讀診斷資料、**並且能開啟訊息記錄**。已改為 signature-level 權限。
2. **Release APK 帶著 Android Debug 簽章**。已改為未簽署，不再以 debug key 冒充。
3. **備份規則是空的 `<cloud-backup>` 節點**，依賴平台預設而非明確宣告。
   已改為明確排除全部九個網域。

---

## 2. 現有的重要事實（省得重新發現）

- **Gradle 啟動失敗不是套件損壞**，是 `~/.gradle` 在沙盒不可寫。
  用 `GRADLE_USER_HOME=<workspace>/gradle_home` 解決。
- **Kotlin daemon 同理**，用 `kotlin.compiler.execution.strategy=in-process`（已在
  `gradle.properties`）。
- **emulator 需要 `HOME=<workspace>/emulator-home`**，否則無法開機。
- **裝置必須解鎖**，否則 App 程序無法啟動（Keystore 是 CE-backed），
  會出現 `SecurityException: Package ... is not encryption aware!`。
- **instrumentation APK 是獨立套件、有自己的 UID**，無法寫入 App 的
  SharedPreferences。必須用 debug-only 的 `DebugSettingsProvider` 橋接。
- **App 的 external files 目錄會在 instrumentation 結束時被刪除**，
  跨平台的封包必須寫到 shared storage。

---

## 3. 下一步（具體、可執行）

### 3.1 立即可做

```bash
cd <worktree>
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export GRADLE_USER_HOME="<workspace>/gradle_home"
export ANDROID_SDK_ROOT="<workspace>/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="<workspace>/.android-home"
export HOME="<workspace>/emulator-home"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/build-tools/36.0.0:$PATH"

# 完整驗證
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest
bash scripts/audit.sh
bash scripts/audit-secrets.sh
bash scripts/audit-release.sh
python3 integrations/dhc/tests/test_connector.py
```

### 3.2 需要使用者授權才能做

| 項目 | 為什麼需要授權 |
|------|--------------|
| 建立正式 release / upload 金鑰 | 金鑰必須由使用者保管；Agent 產生的金鑰無法交接 |
| 建立 / 推送 GitHub 倉庫 | 建立遠端倉庫會改變公開範圍 |
| 實體手機真實來源驗證 | 需要實機與真實帳號 |

### 3.3 建議的下一個工程項目

依優先序：

1. **Keystore 金鑰失效的完整復原流程** — 已有狀態模型與提示框，
   但還缺「明確的復原或清理流程」的最後一步。
2. **列表分頁** — 目前一次解密 1,500 筆，首屏約 2.7 s。
   見 `docs/engineering/PERFORMANCE-BASELINE.md` §5。
3. **實體手機相容性矩陣** — 見 `docs/REAL-DEVICE-TEST-PLAN.md`。

---

## 4. 重要路徑

| 用途 | 路徑 |
|------|------|
| 專案（本輪整合） | `<workspace>/PrivateInboxClassic-v0.2/PrivateInboxClassic-v0.4-wt` |
| 產出的 APK | `<workspace>/PrivateInboxClassic-v0.4.1-debug.apk` |
| 工具鏈 | `<workspace>/android-sdk`、`<workspace>/gradle/gradle-8.13` |
| Gradle 快取 | `<workspace>/gradle_home` |
| Emulator AVD | `<workspace>/.android-home/avd/test36.avd` |
| Debug keystore | `<worktree>/keystore/debug.keystore`（git-ignored） |

---

## 5. 不可破壞的規則

完整清單見 `AGENTS.md`。最容易忘記的幾條：

1. 不加 `INTERNET` 權限。
2. 不加追蹤 SDK。
3. 不移除 `FLAG_SECURE`。
4. 不在 Logcat 輸出訊息內容。
5. 遷移不刪除使用者資料。
6. **不宣稱沒實際跑過的測試通過。**


---

## 附錄：X200 Pro 群組突發漏接修復（本次）

### 分支

`fix/x200pro-burst-capture`（基準 `6436fec`）

### 修正的三個真實缺陷

| 缺陷 | 影響 | 修正 |
|------|------|------|
| **去重鍵識別通知而非訊息** | 來源無時間戳時退化成擷取時間，同一則群組通知的所有訊息算出同一個鍵 → 100 則訊息只存 20 列 | 退化成每則訊息的索引識別碼 |
| **訊息歷史截斷在 25 則** | 較早的訊息靜默丟棄，且來源只送最新修訂 → 永久遺失 | 上限 500，超過時記錄 `MESSAGE_HISTORY_TRUNCATED` |
| **佇列無上限 + 不補抓** | 突發變成看不見的積壓；worker 死亡會靜默停止所有擷取 | 有界佇列 512 + 溢出回報 + worker 存活 + 連線時補抓 active 通知 |

### 另修

- `<queries>` 元素缺失 → Android 11+ 看不到任何來源套件，來源清單是空的。

### 量測（模擬器，Android 16 / API 36 / arm64-v8a，3 次連續執行）

```
CASE A  post_attempts=20  db_saved=20
CASE B  post_attempts=100 db_saved=100
CASE C  post_attempts=100 db_saved=100
CASE D  duplicate_text_count=2      (兩則相同文字都保留)
CASE D2 identical_update_rows=1     (不重複收錄)
CASE E  active=10
CASE F  captured_on=20 captured_off=20
```

修復前的對照：`db_saved=20`（CASE B 期望 100）。

### 測試

- `BurstCaptureInstrumentedTest`（7 項）— 新增
- `FingerprintIdentityTest`（8 項）— 新增
- 總計：instrumentation **67/67**、unit **50/50**、python **23/23**

### 交付

| 檔案 | SHA-256 |
|------|---------|
| `PrivateInboxClassic-v0.4.1-burst-debug.apk` | `6f5886dc32d5467160001fa4b8b96c41ba1c9e2889e2f5f71d7faa7ec9bc746e` |
| `PrivateInboxClassic-v0.4.1-burst-release-unsigned.apk` | `e166f6a6b377f5eb8c6fbdfbddcbb69646ce12eb52654af60ebcbff5f988f4f4` |

簽章憑證 SHA-256：`c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`
（**與既有安裝相同**，`adb install -r` 可直接覆蓋，不刪資料）

### 未驗證

**vivo X200 Pro / OriginOS 真機：NOT TESTED**（本輪沒有連接真機）。
詳見 `docs/engineering/X200PRO-BURST-FIX.md` §5。
