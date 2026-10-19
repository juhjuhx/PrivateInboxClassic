# Private Inbox Classic — 交接文件（HANDOFF）

**最後更新**：本次交付
**CURRENT_PHASE**：PHASE 8 — APK 交付與驗收（Debug APK 已產出並可安裝）

---

## 1. CURRENT_PHASE

| 項目 | 狀態 |
|------|------|
| PHASE 0 Repository Audit | ✅ 完成 |
| PHASE 1 Build Recovery | ✅ 完成（Gradle 8.13 wrapper + 官方 SHA-256 + 3 個編譯錯誤修復） |
| PHASE 2 Notification Pipeline | ✅ 完成（解析 + 去重 + 背景執行緒化） |
| PHASE 3 Encrypted Persistence | ✅ 完成（AES-256-GCM，儀器測試已驗證重開後可讀） |
| PHASE 4 Image Support | ✅ 完成（讀取 bytes / 驗證 / 降級 / 加密 / 縮放檢視器） |
| PHASE 5 Telegram Classic UI | ✅ 完成（Material One 視覺保留，僅做修正） |
| PHASE 6 Settings | ✅ 完成（來源白名單、權限、保留期限、清除、App Lock、儲存量） |
| PHASE 7 QA | ✅ 完成（單元測試 + Lint + 靜態稽核 + 模擬器實測） |
| PHASE 8 APK Delivery | ✅ 完成（Debug APK 已產出、已安裝、已啟動驗證） |

---

## 2. BUILD_STATUS

```
$ ./gradlew --no-daemon :app:assembleDebug
EXIT_CODE=0
BUILD SUCCESSFUL
```

完整日誌：`docs/BUILD-LOG.txt`

### 本次修復的真實編譯錯誤

| # | 錯誤 | 根因 | 檔案 |
|---|------|------|------|
| 1 | `does not implement abstract base class member: fun handleOnBackPressed()` | `OnBackPressedCallback` 抽象方法名稱錯誤 | `MainActivity.kt` |
| 2 | `Unresolved reference 'SimpleOnDoubleTapGestureListener'` | 該類別不存在於公開 SDK（平台內部 API） | `PhotoZoomView.kt` |
| 3 | `Unresolved reference 'onTouchEvent'` | 建構子多載推斷失敗 | `PhotoZoomView.kt` |
| 4 | `actual type is 'Float', but 'Int' was expected` | `FrameLayout.LayoutParams` weight 參數型別 | `MainActivity.kt` |
| 5 | `actual type is 'Bitmap', but 'ByteArray?' was expected` | 重構後呼叫端型別未同步 | `PrivateNotificationListener.kt` |
| 6 | `Unresolved reference 'Person'` | `Person` 是 `android.app.Person` 頂層類別 | androidTest |
| 7 | `Unresolved reference 'of'` | `UserHandle.of` 不存在，應用 `getUserHandleForUid` | androidTest |

---

## 3. SDK_STATUS

| 項目 | 狀態 | 路徑 |
|------|------|------|
| JDK 17 | ✅ 可用 | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| Gradle 8.13 | ✅ 可用 | `<workspace>/gradle/gradle-8.13` |
| Android SDK 36 | ✅ 可用 | `/opt/homebrew/share/android-commandlinetools`（platforms/android-36） |
| Build Tools 36.0.0 | ✅ 可用 | `build-tools/36.0.0`（aapt2 2.20-13193326） |
| platform-tools (adb) | ✅ 可用 | `platform-tools/adb` |
| cmdline-tools | ✅ 可用 | `cmdline-tools/latest`（含 sdkmanager、apkanalyzer） |
| Emulator | ✅ 已安裝 | `<workspace>/android-sdk/emulator` |
| system-images android-36 google_apis arm64-v8a | ✅ 已安裝 | `<workspace>/android-sdk/system-images/...` |
| 模擬器 AVD `test36` | ✅ 可開機 | `<workspace>/.android-home/avd/test36.avd` |

### 環境限制與解法

| 限制 | 解法 |
|------|------|
| `~/.gradle/native` 不可寫 → Gradle 無法啟動 | `GRADLE_USER_HOME=<workspace>/gradle_home` |
| `~/Library/Application Support` 不可寫 → Kotlin daemon 失敗 | `gradle.properties`: `kotlin.compiler.execution.strategy=in-process` |
| `~/Library/Caches/TemporaryItems` 不可寫 → emulator 無法開機 | 設定 `HOME=<workspace>/emulator-home` |
| `sdkmanager` manifest 解析失敗 | 改用新版 `android sdk install` 指令 |
| `/opt/homebrew/share` 不可寫 | 建立 `<workspace>/android-sdk` 並 symlink 既有套件 |

> **注意**：Gradle 啟動失敗的真正根因**不是**「Gradle distribution 損壞」或
> 「缺少 macOS aarch64 native library」，而是 home 目錄在沙盒環境不可寫。

---

## 4. WRAPPER_STATUS

```
gradlew                          ✅ 8752 bytes, 可執行
gradlew.bat                      ✅ 2966 bytes
gradle/wrapper/gradle-wrapper.jar ✅ 43705 bytes（由已安裝 Gradle 8.13 正式產生）
gradle/wrapper/gradle-wrapper.properties ✅ 含 distributionSha256Sum
```

```
distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip
distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
```

該 SHA-256 已與 Gradle 官方 `.sha256` 檔案**實際比對一致**，
且與本機下載的 `gradle-8.13-bin.zip` hash 相同。

驗證：`$ ./gradlew --no-daemon --version` → `Gradle 8.13` ✅

---

## 5. TEST_RESULTS

### PASS

| 項目 | 結果 | 證據 |
|------|------|------|
| JVM 單元測試 | **18/18 PASS** | `docs/TEST-LOG.txt`、XML 在 `app/build/test-results/` |
| CapturePolicyTest | 10/10 PASS | 白名單雙重把關、正規化、上限 |
| ImageCaptureTest | 8/8 PASS | magic-byte 驗證、MIME rescue |
| Android Lint | **0 error** | `app/build/reports/lint-results-debug.html` |
| 靜態安全稽核 | PASS | `bash scripts/audit.sh` |
| Debug Build | PASS | `BUILD SUCCESSFUL`, exit 0 |
| APK 權限檢查 | PASS | `aapt dump permissions`：**無** INTERNET / READ_SMS / READ_CONTACTS / QUERY_ALL_PACKAGES / READ_EXTERNAL_STORAGE / ACCESS_NETWORK_STATE |
| APK 簽章 | PASS | `apksigner verify` → v2 scheme true |
| APK 安裝 | PASS | `adb install` → `Success` |
| App 啟動 | PASS | `topResumedActivity=tw.personal.privateinbox/.MainActivity`，PID 存活，crash buffer 空白 |
| UI 結構 | PASS | uiautomator dump 確認鎖定畫面 5 個元件文字正確 |
| FLAG_SECURE | PASS | `adb exec-out screencap` 產出全黑畫面 → 截圖確實被系統攔截 |
| 通知監聽授權 | PASS | `dumpsys notification` 顯示 `tw.personal.privateinbox/...PrivateNotificationListener` 在 allowed 清單 |
| 白名單拒絕非授權來源 | PASS | 實機上 `com.android.shell` 通知被拒（資料庫未建立） |
| Instrumentation 測試（管線） | **10/10 PASS** | `docs/INSTRUMENTED-TEST-LOG.txt`，Android 16 模擬器 |
| Instrumentation 測試（UI 快照） | **4/4 PASS** | `docs/UI-SNAPSHOT-LOG.txt` |
| UI 畫面截圖 | **PASS** | `screenshots/` 4 張，由 instrumentation 渲染真實 View |

### NOT TESTED

| 項目 | 原因 |
|------|------|
| 真實 LINE / Instagram / WhatsApp 推播擷取 | 需真實帳號與另一端發送 |
| LINE / Instagram 影像 URI 授權的實際行為 | 需真機 |
| MIUI / HyperOS 背景存活 | 需小米實機 |
| Keystore `KeyInvalidatedException` 觸發 | 需特定條件 |
| 資料庫損毀的破壞性測試 | 程式碼有保護，未做破壞測試 |
| Release 簽章 | 本次交付為 Debug APK |
| 跨版本實機矩陣（9/13/14/15） | 僅驗證 Android 16 |

### BLOCKED

無阻斷項。APK 已產出並可安裝。

---

## 6. SECURITY_STATUS

| 項目 | 狀態 |
|------|------|
| INTERNET 權限 | **absent**（最終 APK 實測） |
| 追蹤 SDK | 無（唯一 runtime 依賴 `androidx.activity:1.9.2`） |
| 金鑰硬編碼 | 無（全部來自 `KeyStore.getKey()`） |
| 內容加密 | AES-256-GCM（文字 + 照片） |
| Logcat 明文 | 無（全專案無 `Log.*` 呼叫） |
| exported 元件 | 最小化（僅 Launcher Activity 為 true） |
| 備份 | `allowBackup=false` + 空 data extraction rules |
| 資料庫整體加密 | **未採用**（metadata 為明文，已在 SECURITY.md §2.2 明確揭露） |
| Keystore 金鑰失效處理 | **僅做到不 Crash**，未實作明確提示與清理流程 → 最優先待辦 |

---

## 7. CHANGED_FILES

### 本階段修改

| 檔案 | 變更 |
|------|------|
| `app/src/main/java/.../MainActivity.kt` | `performBack` → `handleOnBackPressed`；`FrameLayout.LayoutParams` weight 型別；`threadKey()` 抽取；未讀 Badge 改為「上次查看後新擷取」；新增 `dayLabel()` 日期分隔；移除 120 筆硬限制；`String.format` 加 Locale |
| `app/src/main/java/.../PhotoZoomView.kt` | 改用公開的 `OnDoubleTapListener`；補 `performClick()` 與點擊判定（無障礙） |
| `app/src/main/java/.../PrivateNotificationListener.kt` | 拆分 `readBytes()`（callback 內）與 `toPreview()`（背景執行緒）；新增 `readPictureBytes()` |
| `app/src/main/java/.../ImageCapture.kt` | 新增 `readBytes()` / `toPreview()`；`asPreview` 改為 `internal` |
| `app/src/main/java/.../LocalSettings.kt` | 新增 `lastSeen()` / `markSeen()` 與 `SEEN` prefs |
| `app/src/main/java/.../InboxDatabase.kt` | （既有 commit 已含 source_ts 與 deleteMessage） |
| `app/build.gradle.kts` | versionCode 2→3、versionName 0.3.0；新增 `testInstrumentationRunner` 與 androidTest 依賴 |
| `gradle.properties` | 新增 `kotlin.compiler.execution.strategy=in-process`、caching、parallel |
| `gradle/wrapper/gradle-wrapper.properties` | 新增 `distributionSha256Sum` |
| `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` | 正式產生（原本缺失） |
| `.gitignore` | 新增 `gradle_home/`、`gradle/`、`android-sdk/`、`*.log` |
| `scripts/test-core.sh` | 改用 Gradle wrapper（原本依賴外部 kotlinc）；移除重複的 `core-tests/` |
| `.github/workflows/android-apk.yml` | 補上 unit test、lint、wrapper 驗證、test results artifact |
| `app/src/androidTest/.../NotificationPipelineInstrumentedTest.kt` | **新增**：10 項 instrumentation 測試 |

### 文件

| 檔案 | 狀態 |
|------|------|
| `README.md` | 重寫（實際建置/測試結果 + 已知限制） |
| `docs/ARCHITECTURE.md` | 新建 |
| `docs/SECURITY.md` | 重寫（已驗證/未驗證/平台限制三分法） |
| `docs/NOTIFICATION-CAPTURE.md` | 新建 |
| `docs/IMAGE-CAPTURE.md` | 新建 |
| `docs/ANDROID-COMPATIBILITY.md` | 新建 |
| `docs/TEST-REPORT.md` | 新建 |
| `docs/HANDOFF.md` | 本檔案（重寫） |
| `docs/BUILD-LOG.txt` | 建置日誌 |
| `docs/TEST-LOG.txt` | 測試日誌 |
| `docs/INSTRUMENTED-TEST-LOG.txt` | Instrumentation 日誌 |

---

## 8. GIT_HEAD

```
分支：feature/phase1-build-recovery-and-verification
HEAD：d4f551642b5a25004f53ab3790ca59e20da8b707
      PHASE 1-8: build recovery, real APK, PRAGMA fix, instrumentation tests, docs
前一 commit：eaafe94  PHASE 2-6: dedup w/ source timestamp, image MIME+magic
                validation, async UI, retention & storage, zoom viewer,
                search filters, JUnit tests, predictive back
再前一：382395d  PHASE 0: baseline audit
```

> 本階段變更已提交到功能分支 `feature/phase1-build-recovery-and-verification`，
> **未推送到 main，也未推送到任何遠端**（未取得使用者授權）。
> `main` 仍停留在 `eaafe94`。

---

## 9. APK_STATUS

| 項目 | 值 |
|------|-----|
| 路徑 | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | 5,892,991 bytes |
| SHA-256 | `bcc174768630aacf4b45c2682654e401aaccc7336d7d5f4f7a6c72869eda3642` |
| Application ID | `tw.personal.privateinbox` |
| Version | `0.3.0` (versionCode 3) |
| minSdk | 28 |
| targetSdk | 36 |
| 簽章 | Android Debug key（v2 scheme）— **測試版** |
| 權限 | 僅 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（androidx 自簽） |
| 已安裝驗證 | ✅ Android 16 模擬器安裝 + 啟動成功 |

---

## 10. NEXT_ACTION

### 優先級 P0 — 必須先做

1. **實作 Keystore 金鑰失效的明確處理**（`docs/SECURITY.md` §2.1 指出這是最優先缺口）：
   - 在 `CryptoStore` 捕捉 `KeyInvalidatedException` / `UnrecoverableKeyException`；
   - 在 UI 明確提示「金鑰已失效，既有資料無法解密」；
   - 提供「清除無法解密的資料列」動作；
   - 加入 instrumentation 測試覆蓋。
2. **審查並合併本功能分支**（`d4f5516`）：需人工確認後再 merge 到 main。
3. **重新產生正式 APK 並更新 SHA-256**（任何後續程式碼變更後都必須重跑
   build + 測試 + 權限檢查）。

### 優先級 P1

4. **補齊聊天室列表的分頁**（目前一次載入 1500 筆全解密）。
5. **確認 Android 9/13/14/15 的實機或模擬器相容性**。

> UI 截圖**已完成**：`screenshots/` 內 4 張（鎖定、列表、聊天室、設定）。
> 由 `UiSnapshotInstrumentedTest` 把真實 View 繪到 Bitmap 產生，
> 完全不需要放寬 `FLAG_SECURE`（該保護在任何 build type 都無條件啟用）。

### 優先級 P2

6. 若要對外散布：**GPL 相關確認**（本專案為獨立實作，無 GPL 程式碼混入，
   但需在 LICENSE 檔案中明確聲明）。
7. Release keystore 流程與簽章輪替政策。
8. 資料匯出／備份（需使用者明確同意，且需處理金鑰遷移）。

---

## 11. 環境重建指令（供下一個 Coding Agent 使用）

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export GRADLE_USER_HOME="<workspace>/gradle_home"
export ANDROID_SDK_ROOT="<workspace>/android-sdk"     # 已含 symlink 到 /opt/homebrew/share/android-commandlinetools
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="<workspace>/.android-home"
export HOME="<workspace>/emulator-home"                # 僅啟動 emulator 時需要
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

cd <workspace>/PrivateInboxClassic-v0.2/PrivateInboxClassic

# 建置 + 測試
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest

# 模擬器
emulator -avd test36 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
adb shell cmd notification allow_listener tw.personal.privateinbox/tw.personal.privateinbox.PrivateNotificationListener
adb shell locksettings set-pin 1234

# APK 檢查
aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```
