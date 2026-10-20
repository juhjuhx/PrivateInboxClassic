# 參與開發（CONTRIBUTING）

感謝你對 Private Inbox Classic 的興趣。

本專案是**個人使用的離線通知保存工具**，核心價值是「不收集、不外傳、
不依賴網路」。任何會削弱這個核心的變更都不會被接受。

---

## 開發環境

| 工具 | 版本 |
|------|------|
| JDK | 17 |
| Android SDK Platform | 36 |
| Build Tools | 36.0.0 |
| Gradle | 8.13（由 wrapper 自動取得） |

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

完整說明見 [docs/BUILDING.md](docs/BUILDING.md)。

---

## 提交前的檢查清單

```bash
./gradlew :app:assembleDebug \
          :app:testDebugUnitTest \
          :app:lintDebug
bash scripts/audit.sh
bash scripts/audit-secrets.sh
```

- [ ] 單元測試通過
- [ ] Lint 沒有 error
- [ ] 最終 APK 沒有 `INTERNET`、`READ_SMS`、`READ_CONTACTS`、
      `QUERY_ALL_PACKAGES`、`READ_EXTERNAL_STORAGE`
- [ ] 沒有把金鑰或密碼提交進來
- [ ] 沒有把訊息內容寫進 Logcat
- [ ] 文件與程式碼一致

---

## 不可跨越的界線

1. **不加 `android.permission.INTERNET`。** CI 會直接讓建置失敗。
2. **不加追蹤、廣告或分析 SDK。** 唯一允許的 runtime 依賴是
   `androidx.activity`。
3. **不加 Root、Accessibility Service 或任何繞過沙盒的機制。**
4. **不記錄訊息內容。** 診斷只能記錄：時間、階段代碼、來源套件名、
   錯誤型別名。
5. **不移除 `FLAG_SECURE`。** 任何 build type 都必須啟用。
6. **不寫死金鑰。** 全部來自 Android Keystore。
7. **遷移不刪除使用者資料。** 只能新增欄位。
8. **不宣稱沒實際跑過的測試通過。** 沒跑就是 `NOT TESTED`。

---

## 新增一個通訊平台

只需要動一個檔案：

```kotlin
// app/src/main/java/tw/personal/privateinbox/SourceRegistry.kt
Source("com.example.messenger", "Example", "EX", 10, isSocial = false),
```

篩選列、來源設定開關、白名單政策、分類器都會自動跟著更新。
再補一項 `SourceRegistryTest` 的測試即可。

---

## 測試的三個層次

| 層次 | 位置 | 適合測什麼 |
|------|------|-----------|
| JVM 單元測試 | `app/src/test/` | 純邏輯：分類、白名單、指紋、格式驗證 |
| Instrumentation | `app/src/androidTest/` | 真實 Android：資料庫、加密、監聽器、UI |
| 靜態稽核 | `scripts/` | Manifest 權限、秘密與金鑰 |

### Instrumentation 測試的跨套件陷阱

instrumentation APK 是**獨立的套件、有自己的 UID**：

- 它**不能**寫入 App 的 `SharedPreferences** → 用 debug 專用的
  `DebugSettingsProvider`（`app/src/debug/`，release 組態不存在）。
- 它**不能**直接讀 App 的診斷計數 → 同樣用 provider。
- 但 instrumentation **target 是 App 套件**，所以 `targetContext` **就是**
  App 的 context，`InboxDatabase(targetContext)` 開的是真實資料庫。

另外，instrumentation 需要**已解鎖**的裝置：App 使用 Keystore（CE-backed），
鎖屏時 App 程序無法啟動，會出現
`SecurityException: Package ... is not encryption aware!`。

---

## 提交訊息

小而專注，並說明**為什麼**。這個 repository 的歷史是這個專案的稽核軌跡。

範例：

```
Fix a ClassCastException that killed the notification listener

A preferences value of the wrong type made getStringSet throw, which kills
the NotificationListenerService and silently stops ALL capture until the
system rebinds it. The read is now type-safe and degrades to "no sources"
instead of taking the app down.
```

---

## 回報問題

請附上：

1. 裝置型號與 Android 版本
2. 是否已授予通知存取權限
3. 設定 → 通知監聽診斷 → 「匯出匿名診斷報告」的內容
   （**不含**訊息內容）
4. 你的來源 App 與通知內容的形狀（例如是否為群組、是否附照片）

**請不要貼上真實的私人訊息內容。**

---

## 授權

貢獻程式碼即同意以 [MIT License](LICENSE) 釋出。
詳見 AGENTS.md 與 LICENSE 中的 provenance 說明。
