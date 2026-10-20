# 建置教學（BUILDING）

## 1. 必要環境

| 工具 | 版本 | 說明 |
|------|------|------|
| JDK | 17 | AGP 8.13 要求 JDK 17 |
| Gradle | 8.13 | 由 `./gradlew` 自動下載（wrapper） |
| Android Gradle Plugin | 8.13.2 | 由 `build.gradle.kts` 指定 |
| Kotlin | 2.2.20 | 由 Gradle plugin 解析 |
| Android SDK Platform | 36 (`android-36`) | `compileSdk` / `targetSdk` |
| Build Tools | 36.0.0 | `aapt2`、`apksigner` |

不需要 Android Studio，但用它開啟專案最方便。

---

## 2. 設定環境變數

```bash
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

`local.properties` 會自動指向 SDK（此檔已被 git-ignore）：

```properties
sdk.dir=/path/to/android-sdk
```

---

## 3. 建置指令

```bash
# Debug APK
./gradlew :app:assembleDebug
# 產出：app/build/outputs/apk/debug/app-debug.apk

# Release APK
./gradlew :app:assembleRelease

# Google Play AAB
./gradlew :app:bundleRelease
```

### 測試

```bash
# JVM 單元測試
./gradlew :app:testDebugUnitTest

# Android Lint
./gradlew :app:lintDebug

# 儀器測試（需要模擬器或實機）
./gradlew :app:connectedDebugAndroidTest

# 一次跑完
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

### 靜態稽核

```bash
bash scripts/audit.sh          # Manifest 權限與隱私政策
bash scripts/audit-secrets.sh  # 秘密與金鑰（工作樹 + 完整 Git 歷史）
```

---

## 4. 受限環境注意事項

某些沙盒或 CI 環境下，home 目錄不可寫，會出現以下症狀：

| 症狀 | 原因 | 解法 |
|------|------|------|
| `Failed to load native library 'libnative-platform.dylib'` | `~/.gradle` 不可寫 | `export GRADLE_USER_HOME="$PWD/../gradle_home"` |
| `Could not connect to Kotlin compile daemon` | `~/Library/Application Support` 不可寫 | 專案已設定 `kotlin.compiler.execution.strategy=in-process` |
| `Unable to create debug keystore in ~/.android` | `~/.android` 不可寫 | `export ANDROID_USER_HOME="$PWD/../.android-home"` |
| emulator `Failed to create jwk directory` | `~/Library/Caches` 不可寫 | `export HOME="$PWD/../emulator-home"` |

`gradle.properties` 已內建 `kotlin.compiler.execution.strategy=in-process`，
因此 Kotlin daemon 問題不需要額外設定。

---

## 5. 儀器測試的前置條件

`connectedDebugAndroidTest` 需要：

1. 已啟動的模擬器或已連接的實機（`adb devices` 可見）。
2. **裝置已解鎖**。本 App 使用 Keystore（CE-backed），鎖屏時 App 程序
   無法啟動，會出現
   `SecurityException: Package ... is not encryption aware!`。
3. 通知存取權限已授予（自我測試需要）。

```bash
# 啟動模擬器
emulator -avd <avd-name> -no-window -no-audio -no-boot-anim -no-snapshot

# 確認解鎖
adb shell dumpsys user | grep State      # 應為 RUNNING_UNLOCKED

# 授予通知存取權限與自我測試通知權限
adb shell cmd notification allow_listener \
  tw.personal.privateinbox/tw.personal.privateinbox.PrivateNotificationListener
adb shell pm grant tw.personal.privateinbox android.permission.POST_NOTIFICATIONS
```

---

## 6. 關於 `DebugSettingsProvider`

`app/src/debug/` 底下有一個 **只在 debug 組態存在** 的
`DebugSettingsProvider`。它的存在理由：

instrumentation APK 是**獨立的套件、有自己的資料目錄**
（`/data/user/0/tw.personal.privateinbox.test`），因此**無法**寫入
App 的 SharedPreferences，也**無法**直接讀取 App 的資料庫。

這個 provider 讓儀器測試透過標準的 `ContentResolver` 呼叫，
讓 **App 自己**去改設定與數資料。

- **不會出現在 release 組態**（`src/debug/` 專屬）。
- 只提供兩種操作：切換擷取來源、讀取診斷計數。**不提供任何訊息內容。**
- 呼叫端以 UID 檢查限制，只接受 App 自身與 instrumentation 套件。

---

## 7. 疑難排解

| 症狀 | 可能原因 | 檢查 |
|------|----------|------|
| App 無法啟動，顯示「需要螢幕鎖」 | 裝置未設定 PIN/密碼/圖形鎖 | 這是**設計行為**，不是 bug |
| 通知監聽一直顯示未連線 | 系統尚未綁定服務 | 設定頁點「重新檢查監聽器」；OEM 需開啟後台權限 |
| 自我測試顯示「未找到」 | 通知監聽未綁定，或 POST_NOTIFICATIONS 未授權 | 設定 → 診斷 → 發送測試通知 |
| 圖片抓不到 | 通知未附帶影像資料 | 只會保存文字，這是**預期行為** |
| 安裝 APK 失敗（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`） | 簽章與既有安裝不同 | 需用相同的簽章金鑰；見 [SIGNING.md](SIGNING.md) |

---

## 8. 相關文件

- [SIGNING.md](SIGNING.md) — 簽章設定與金鑰指紋
- [TEST-REPORT.md](TEST-REPORT.md) — 測試結果
- [ARCHITECTURE.md](ARCHITECTURE.md) — 架構說明
