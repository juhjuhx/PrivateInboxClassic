# AGENT_STATE — Private Inbox Classic v0.4.2

> 這份檔案是給**下一個**接手的工作者（人或 AI agent）看的。
> 它記錄的是「現在在哪、接下來做什麼」，不是成就報告。

## 快速狀態

| 項目 | 值 |
|------|-----|
| PROJECT | Private Inbox Classic (`tw.personal.privateinbox`) |
| CURRENT_BRANCH | `fix/x200pro-notification-burst` |
| BASELINE | `8494910`（v0.4.1 群組突發修復） |
| CURRENT_PHASE | R6 — 文件收斂、CI、推送 |
| CURRENT_TASK | 完整驗證 + 推送私人倉庫 |
| LAST_SUCCESS | 95 項 instrumentation 0 失敗 0 skip；50 項 unit；23 項 python；7 項跨語言；Lint 0 error；三個稽核腳本 PASS |
| BUILD_STATUS | BUILD SUCCESSFUL |
| TEST_STATUS | instrumentation 95/95、unit 50/50、python 23/23、fixture 7/7 |
| DISK_STATUS | 20 GiB free |
| BLOCKERS | 正式 release 金鑰未由使用者建立；**vivo X200 Pro 真機未連接** |
| NEXT_ACTION | 在 X200 Pro 上執行 `bash scripts/run-burst-tests.sh --repeat 3` |

---

## 1. 這輪完成了什麼

| 階段 | 內容 | 狀態 |
|------|------|------|
| R0 | 保全、磁碟、CI、產物完整性 | ✅ |
| R1 | 群組突發擷取：診斷 + 修復 + 10 案例矩陣 | ✅ |
| R2 | Keystore 復原流程 | ✅ |
| R3 | 聊天列表分頁 + 效能基準 | ✅ |
| R4 | DHC Connector v0.2：匯出 UI + 跨語言缺陷 | ✅ |
| R5 | 設計文件（鏡像、Rust、圖片、同步） | ✅ |
| R6 | 文件、CI、稽核、推送 | 🔄 進行中 |

### R1 找到並修掉的真實缺陷

| 缺陷 | 影響 | 修正 |
|------|------|------|
| **寫入放大** | 群組的歷史被重複寫入，500 則訊息做了 9,500 次加密，佇列飽和 | 只提交新訊息 |
| **去重識別碼用索引** | 平台 25 則上限讓索引位移，第 26 則以後永遠抓不到 | 改用訊息內容 |
| **Release AAB 用 debug 金鑰** | 以 release 之名散佈 debug 簽章的產物 | AAB 簽章組態清空 |
| **回報的 AAB 是舊的 v0.4.0** | 檔名是 v0.4.1，內容是 v0.4.0-alpha | 稽核腳本改驗版本與簽章 |
| **跨語言 base64 換行** | 同一則訊息在兩邊有不同的 id，匯入不冪等 | 加 `NO_WRAP` |

**量測**（TEST-C：10 對話 / 500 則訊息）：

| 指標 | 修正前 | 修正後 |
|------|--------|--------|
| `messages_saved` | 250 | **500** |
| `unaccounted` | 3,821 | **0** |
| `queue_wait` p99 | 39,144 ms | **2,091 ms** |
| `callback` p99 | — | 47 ms |

---

## 2. 現有的重要事實（省得重新發現）

- **Gradle 啟動失敗不是套件損壞**，是 `~/.gradle` 在沙盒不可寫。
  用 `GRADLE_USER_HOME=<workspace>/gradle_home` 解決。
- **emulator 需要 `HOME=<workspace>/emulator-home`**。
- **裝置必須解鎖**，否則 Keystore（CE-backed）無法使用。
- **instrumentation APK 是獨立套件、有自己的 UID**，
  無法寫入 App 的 SharedPreferences —— 必須用 debug 的
  `DebugSettingsProvider` 橋接。
- **instrumentation 會重裝 App**，所以 App 的 external files 目錄會被刪除。
  跨平台的產物必須寫到 shared storage，或在測試內用 `adb` 拉進來。
- **App 沒有儲存空間權限**，所以 `/sdcard/Download` 讀不了 ——
  測試要用 `adb exec-out cat` 把位元組直接送進 App 的 cache。
- **平台限制**：`Notification.MessagingStyle` 最多保留 25 則訊息。
  第 26 則以後任何 App 都收不到。

---

## 3. 下一步

### 3.1 立即可做

```bash
cd <worktree>
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export GRADLE_USER_HOME="<workspace>/gradle_home"
export ANDROID_SDK_ROOT="<workspace>/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="<workspace>/.android-home"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/build-tools/36.0.0:$PATH"

./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest
bash scripts/audit.sh
bash scripts/audit-secrets.sh
bash scripts/audit-release.sh
python3 integrations/dhc/tests/test_connector.py
bash scripts/run-burst-tests.sh --repeat 3
```

### 3.2 需要使用者授權

| 項目 | 為什麼 |
|------|--------|
| 正式 release / upload 金鑰 | 金鑰必須由擁有者保管 |
| 推送新的私人倉庫分支 | 改變遠端狀態 |
| 在 X200 Pro 上測試 | 需要實機 |

### 3.3 建議的下一個工程項目

依優先序：

1. **持久化 intake journal**。
   記憶體中的佇列在行程被殺時會遺失事件 —— 在 vivo 的背景管理下
   這是真實風險。見 `docs/reliability/QUEUE-BACKPRESSURE.md` §5.1。
2. **Keystore 復原的最後一步**：使用者確認後的「重新開始」流程。
3. **實機相容性矩陣**：見 `docs/reliability/X200PRO-ORIGINOS-MATRIX.md`。

---

## 4. 重要路徑

| 用途 | 路徑 |
|------|------|
| 專案（本輪） | `<workspace>/PrivateInboxClassic-v0.2/PrivateInboxClassic-v0.4-wt` |
| 工具鏈 | `<workspace>/android-sdk`、`<workspace>/gradle_home` |
| Emulator AVD | `<workspace>/.android-home/avd/test36.avd` |
| Debug keystore | `<worktree>/keystore/debug.keystore`（git-ignored）|

---

## 5. 不可破壞的規則

完整清單見 `AGENTS.md`。最容易忘記的幾條：

1. 不加 `INTERNET` 權限。
2. 不加追蹤 SDK。
3. 不移除 `FLAG_SECURE`。
4. 不在 Logcat 輸出訊息內容。
5. 遷移不刪除使用者資料。
6. **不宣稱沒實際跑過的測試通過。**
7. **不把 Debug 金鑰當成正式簽章。**
