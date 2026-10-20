# Private Inbox Classic — 交接文件（HANDOFF）

**CURRENT_PHASE**：PHASE 8 — APK 交付與驗收

---

## 1. 狀態摘要

| 項目 | 狀態 |
|------|------|
| 分支 | `integration/dsh-v040`（工作樹 `../PrivateInboxClassic-v0.4-wt`） |
| BASELINE_SHA | `6ccb69f`（接手時的 HEAD） |
| 本輪分支 HEAD | 見 `git log -1` |
| 版本 | 0.4.0-alpha (versionCode 6) |
| Build | ✅ BUILD SUCCESSFUL |
| 單元測試 | ✅ 42/42 PASS |
| Python DHC 連接器 | ✅ 23/23 PASS |
| Instrumentation | ✅ 60/60（0 skip）— Android 16 模擬器 |
| Lint | ✅ 0 error |
| 秘密稽核 | ✅ PASS（工作樹 + 完整 Git 歷史） |
| APK | ✅ 已產出，權限與簽章已驗證，Debug 版已安裝並啟動 |
| 遠端 | **無**（未推送任何地方） |

---

## 2. 本輪實際修正的缺陷

每個都先在裝置上重現，再修正，再回歸測試。

### 2.1 P0 — 會讓擷取靜默停止的崩潰

| # | 缺陷 | 根因 | 影響 |
|---|------|------|------|
| 1 | `ClassCastException` in `LocalSettings.selected()` | 偏好設定值型別錯誤（String 而非 Set）時 `getStringSet` 拋例外 | **`NotificationListenerService` 被系統殺掉，所有擷取靜默停止**直到重新綁定 |
| 2 | `onNotificationPosted` 未受保護 | 監聽器拋例外會被系統殺掉 | 單一畸形通知即可停止全部擷取 |
| 3 | `NPE` in `MainActivity.showSettings()` | `c["last_ts"] as Long` 讀取不存在的 key | **整個設定畫面崩潰** |
| 4 | `applicationContext` 為 null 時 NPE | 非系統建立的 service 實例 | 直接呼叫 `onNotificationPosted` 會崩潰 |

### 2.2 P0 — 自我測試長期 SKIP

三個 SYSTEM E2E 測試先前**永遠被跳過**。逐項找出根因：

| # | 根因 | 修正 |
|---|------|------|
| a | 連線檢查用 `isListenerConnectedInProcess()`（App 行程的 static），但 instrumentation 跑在**另一個行程**，該 flag 永遠不可能為 true | 改為透過 debug provider 讀取 App 行程的真實連線證據 |
| b | instrumentation APK 是**獨立套件、有自己的 UID**，無法寫入 App 的 SharedPreferences，也無法讀 App 的資料庫與診斷 | 新增 debug-only `DebugSettingsProvider`（`app/src/debug/`，release 不存在） |
| c | 從測試行程發出的通知會被蓋上**測試套件**的套件名，不是 App 套件，因此被來源政策拒絕 | 把 instrumentation 套件也註冊為自我測試來源 |
| d | 從測試行程檢查 `POST_NOTIFICATIONS`，但該權限屬於 **App 套件** | 改為查詢 App 套件的權限，並用 `UiAutomation` 授權 |

**結果：0 skip，34/34 通過。** 一則通知真的走過
Android 系統 → 監聽器 → 解析 → 加密入庫 → UI，且重啟後仍在。

### 2.3 P1 — 其他真實缺陷

| # | 缺陷 | 修正 |
|---|------|------|
| 5 | 搜尋畫面的來源與日期篩選器是區域變數，每次重新繪製都會**無聲重置** | 改為 Activity 狀態，與平台篩選共用 |
| 6 | 「失敗事件」計數器把**政策決定**（記錄關閉、來源未勾選）也算成失敗 | 失敗只統計真正的失敗 |
| 7 | `@Synchronized` 用在有 backing field 的 property 上 | 移除（所有存取都在同一物件的 `@Synchronized` 方法內，monitor 仍序列化） |

---

## 3. 新增功能

| 功能 | 位置 |
|------|------|
| 平台來源註冊表（唯一來源） | `SourceRegistry.kt` |
| Threads 支援 + 訊息分類 | `SourceRegistry.classify()` |
| 平台篩選列 | `MainActivity.platformFilterBar()` |
| 穩定的對話識別 | `ConversationIdentity.kt` |
| 資料庫 schema v4（非破壞性遷移） | `InboxDatabase.kt` |
| 可重現簽章（debug/release/upload 三種身分） | `app/build.gradle.kts`、`keystore/` |
| 秘密稽核（工作樹 + 完整歷史） | `scripts/audit-secrets.sh` |
| 診斷 JSON schema 版本 + 最後成功保存時間 | `CaptureDiagnostics.kt` |
| Debug-only 設定橋接（僅 debug 組態） | `app/src/debug/.../DebugSettingsProvider.kt` |
| GitHub Actions CI | `.github/workflows/android.yml` |
| 開源文件集 | `README.md`、`LICENSE`、`PRIVACY.md`、`CONTRIBUTING.md`、`AGENTS.md`、`CHANGELOG.md`、`docs/` |

---

## 4. 簽章狀態

| 身分 | 憑證 SHA-256 | 位置 |
|------|--------------|------|
| Debug（本專案採用） | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | `keystore/debug.keystore`（git-ignored） |
| Debug（另一份，未採用） | `46488e99...` | `~/.android/debug.keystore` |

**採用前者，因為它是裝置上實際安裝的那一份**（以 `apksigner` 對裝置上的
`base.apk` 實測確認）。**不得輪替**，否則無法覆蓋更新既有安裝。

Release / Upload 金鑰**尚未建立** — 需要使用者在自己機器上建立並保管
（見 `docs/SIGNING.md`）。

---

## 5. 已知阻塞

| 項目 | 狀態 | 原因 |
|------|------|------|
| 實體手機上的 LINE / Messenger / Instagram / Threads 驗證 | **NOT TESTED** | 本次無實體裝置；計畫見 `docs/REAL-DEVICE-TEST-PLAN.md` |
| Release 正式簽章 | **BLOCKED** | 需使用者建立並保管金鑰 |
| AAB 產出 | 可建置但未簽章 | 需 upload 金鑰 |
| 推送至 GitHub | **未執行** | 未取得使用者授權；且 `juhjuhx/PrivateInboxClassic` 是否存在尚未查證 |
| Keystore 金鑰失效的明確 UI 提示 | 未實作 | 目前只做到「不 Crash、跳過無法解密的列」 |

---

## 6. 下一個可執行的具體動作

```bash
# 1. 在本工作樹驗證一切
cd ../PrivateInboxClassic-v0.4-wt
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export GRADLE_USER_HOME="<workspace>/gradle_home"
export ANDROID_SDK_ROOT="<workspace>/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="<workspace>/.android-home"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest
bash scripts/audit.sh && bash scripts/audit-secrets.sh

# 2. 產出交付物
cp app/build/outputs/apk/debug/app-debug.apk \
   ../PrivateInboxClassic-v0.4.0-alpha-debug.apk
shasum -a 256 ../PrivateInboxClassic-v0.4.0-alpha-debug.apk

# 3. 需要使用者授權才能做的事
#    - 建立正式 release / upload 金鑰
#    - 建立或推送 GitHub repository
#    - 在實體手機上執行 REAL-DEVICE-TEST-PLAN.md
```

**建議的下一個工程項目**（依優先序）：

1. Keystore 金鑰失效的明確處理（偵測 → UI 提示 → 清理無法解密的列）。
2. 聊天室列表分頁（現在一次載入 1500 筆並全數解密）。
3. 實體手機相容性矩陣（依 `docs/REAL-DEVICE-TEST-PLAN.md`）。

---

## 7. 環境限制速查

| 症狀 | 解法 |
|------|------|
| `libnative-platform.dylib` 載入失敗 | `export GRADLE_USER_HOME=<workspace>/gradle_home` |
| Kotlin daemon 無法連線 | 已由 `gradle.properties` 的 `in-process` 策略處理 |
| debug keystore 無法建立 | `export ANDROID_USER_HOME=<workspace>/.android-home` |
| emulator 無法開機 | `export HOME=<workspace>/emulator-home` |
| `not encryption aware!` | 裝置被鎖屏；`adb shell locksettings clear` |
| instrumentation 找不到 provider | App 未安裝；測試會自動啟動 App 行程 |
