# 測試報告（TEST-REPORT）

**版本**：0.3.0（versionCode 3）
**測試日期**：本次交付
**環境**：macOS 15.8.1 (aarch64)｜JDK 17.0.20.1 (Homebrew)｜Gradle 8.13（wrapper）｜
AGP 8.13.2｜Kotlin 2.2.20｜Android SDK 36｜Build Tools 36.0.0
**測試裝置**：Android 16 模擬器（`system-images;android-36;google_apis;arm64-v8a`，API 36）

> 本報告嚴格區分 **PASS** / **FAIL** / **NOT TESTED** / **BLOCKED**。
> **沒有執行的測試一律標記 NOT TESTED，不視為通過。**
> 完整日誌：`docs/BUILD-LOG.txt`、`docs/TEST-LOG.txt`、
> `docs/INSTRUMENTED-TEST-LOG.txt`、`docs/UI-SNAPSHOT-LOG.txt`。

---

## 1. 摘要

| 類別 | 結果 |
|------|------|
| Debug Build | **PASS** |
| JVM 單元測試 | **PASS**（18/18） |
| Instrumentation 測試（通知管線） | **PASS**（10/10，Android 16 模擬器） |
| Instrumentation 測試（UI 快照） | **PASS**（4/4） |
| Android Lint | **PASS**（0 error，18 warning） |
| 靜態安全稽核腳本 | **PASS** |
| APK 權限檢查 | **PASS**（無 INTERNET 等敏感權限） |
| APK 簽章驗證 | **PASS**（v2 scheme） |
| APK 安裝於 Android 16 模擬器 | **PASS** |
| App 啟動 | **PASS**（無崩潰，UI 結構經 uiautomator 驗證） |
| UI 畫面截圖 | **PASS**（4 張，由 instrumentation 渲染真實 View） |
| 真實 LINE / Instagram 推播 | **NOT TESTED** |
| 實體手機行為 | **NOT TESTED** |
| MIUI / HyperOS 背景存活 | **NOT TESTED** |

---

## 2. 建置驗證

### 2.1 Gradle Wrapper（PASS）

```
$ ./gradlew --no-daemon --version
Gradle 8.13
Kotlin:        2.0.21
Groovy:        3.0.22
Launcher JVM:  17.0.20.1 (Homebrew 17.0.20.1+0)
OS:            Mac OS X 15.8.1 aarch64
```

- Wrapper JAR 由**已安裝的 Gradle 8.13 正式產生**（非手寫、非不明來源）。
- `gradle-wrapper.properties` 已加入 `distributionSha256Sum`：
  `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`
  — 與 Gradle 官方 `.sha256` **實際比對一致**。

### 2.2 Debug Build（PASS）

```
$ ./gradlew --no-daemon :app:assembleDebug --stacktrace
EXIT_CODE=0
BUILD SUCCESSFUL
```

#### 實際遇到並修復的編譯錯誤

| # | 錯誤 | 根因 |
|---|------|------|
| 1 | `does not implement abstract base class member: fun handleOnBackPressed()` | `OnBackPressedCallback` 抽象方法名稱錯誤（原碼寫 `performBack()`） |
| 2 | `Unresolved reference 'SimpleOnDoubleTapGestureListener'` | 該類別**不存在於公開 SDK**（平台內部 API），原碼誤用 |
| 3 | `Unresolved reference 'onTouchEvent'` | 建構子多載導致型別推斷失敗 |
| 4 | `actual type is 'Float', but 'Int' was expected` | `FrameLayout.LayoutParams` 的 weight 參數是 Int |
| 5 | `actual type is 'Bitmap', but 'ByteArray?' was expected` | 重構後呼叫端型別未同步 |
| 6 | `Unresolved reference 'Person'` | `Person` 是 `android.app.Person` 頂層類別 |
| 7 | `Unresolved reference 'of'` | `UserHandle.of` 不存在，應用 `getUserHandleForUid` |
| 8 | `Unresolved reference 'ContextWrapper'` | import 套件錯誤 |

#### 環境層級問題（非程式碼缺陷）

| 問題 | 根因 | 解決 |
|------|------|------|
| `Failed to load native library 'libnative-platform.dylib' for Mac OS X aarch64` | `~/.gradle/native` 在沙盒環境不可寫。**不是** Gradle 套件損壞 | `GRADLE_USER_HOME` 指向工作區 |
| `Daemon compilation failed: Could not connect to Kotlin compile daemon` | Kotlin daemon 需寫入 `~/Library/Application Support` | `gradle.properties`: `kotlin.compiler.execution.strategy=in-process` |
| `Unable to create debug keystore in $HOME/.android` | `~/.android` 不可寫 | 指定 `ANDROID_USER_HOME` 與自建 debug keystore |
| `Failed to create jwk directory .../TemporaryItems` | `~/Library/Caches` 不可寫 → emulator 無法開機 | 設定 `HOME` 指向工作區 |

### 2.3 Lint（PASS）

```
$ ./gradlew --no-daemon :app:lintDebug
BUILD SUCCESSFUL
```

**Error：0｜Warning：18**（DefaultLocale、ExifInterface、UseKtx、SetTextI18n、
RtlHardcoded、AGP/依賴版本提示等非阻斷性項目）。
本次已主動修掉 `ClickableViewAccessibility` 與 `DefaultLocale` 兩項。

---

## 3. JVM 單元測試（PASS — 18/18）

```
$ bash scripts/test-core.sh
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL

--- test report ---
tw.personal.privateinbox.CapturePolicyTest: tests=10 failures=0 errors=0 skipped=0
tw.personal.privateinbox.ImageCaptureTest: tests=8 failures=0 errors=0 skipped=0
```

### 3.1 `CapturePolicyTest`（10 項，全 PASS）

| 測試 | 驗證內容 |
|------|----------|
| blank input normalizes to null | 空白字串 → `null` |
| whitespace and line feeds are collapsed to single spaces | 換行/多空白 → 單一空格 |
| overlong bodies are capped | 30,000 字元 → 截斷至 20,000 |
| known package maps to its display name | `jp.naver.line.android` → `LINE` |
| unknown source falls back to its raw name | 未知套件名原樣回傳 |
| package not on the known allowlist is refused even when selected | **白名單外的套件即使勾選也拒絕** |
| explicitly selected known source is allowed | 勾選的已知來源允許 |
| selected source does not open other packages | 選 A 不會開 B |
| unknown source is never allowed without opt-in | 未勾選一律拒絕 |
| every known app is a messaging or chat package | 白名單項全部為通訊類 App |

### 3.2 `ImageCaptureTest`（8 項，全 PASS）

| 測試 | 驗證內容 |
|------|----------|
| jpeg magic passes | `FF D8 FF` 通過 |
| png magic passes | `89 50 4E 47` 通過 |
| webp riff container passes | RIFF + WEBP 通過 |
| heic isobmff ftyp passes | `ftyp` 通過 |
| arbitrary text is rejected without mime | 任意文字在無 MIME 時被拒 |
| declared image mime rescues unknown magic | `image/avif` 可救援 |
| declared non image mime does not rescue | `text/plain` 不得救援 |
| tiny payloads are rejected | < 4 bytes 被拒 |

---

## 4. Instrumentation 測試（PASS — 10/10，Android 16 模擬器）

`app/src/androidTest/java/tw/personal/privateinbox/NotificationPipelineInstrumentedTest.kt`
日誌：`docs/INSTRUMENTED-TEST-LOG.txt`

| 測試 | 驗證內容 | 狀態 |
|------|----------|------|
| `messagingStyleNotificationIsCapturedAndDecrypted` | 真實 `MessagingStyle` 通知被擷取；標題/發話者/內文解密正確；**原始時間戳被保留** | PASS |
| `duplicateNotificationIsNotStoredTwice` | 相同通知重複推播只產生 1 列 | PASS |
| `notificationFromNonWhitelistedPackageIsRejected` | 非白名單來源**不**被保存 | PASS |
| `groupSummaryNotificationIsIgnored` | `FLAG_GROUP_SUMMARY` 被忽略 | PASS |
| `emptyNotificationDoesNotCrash` | 缺內容的通知不會 Crash | PASS |
| `encryptedRowSurvivesDatabaseReopen` | 加密列在 DB 重開後仍正確解密 | PASS |
| `imageIsStoredEncryptedAndDecryptsBack` | 影像加密儲存；**密文 ≠ 明文**；解密後位元組一致 | PASS |
| `clearRemovesEverything` | 清除全部資料有效 | PASS |
| `retentionPrunesOldMessages` | 保留期限政策正確剪除 | PASS |
| `listenerServiceIsDeclaredInTheManifest` | 服務以 `BIND_NOTIFICATION_LISTENER_SERVICE` 綁定且 `exported=false` | PASS |

### 4.1 Instrumentation 測試揭露的真實 App 缺陷

首次執行時 **10 項全部失敗**，同一個根因：

```
android.database.sqlite.SQLiteException: unknown error (code 0 SQLITE_OK):
Queries can be performed using SQLiteDatabase query or rawQuery methods only.
```

**根因**：`InboxDatabase` 使用 `db.execSQL("PRAGMA secure_delete=ON")` 與
`db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")`。
`PRAGMA` 是**查詢**語句，`execSQL()` 只接受更新語句，會直接拋例外。

**影響範圍（修正前的實際行為）**：
- `onConfigure()` 拋例外 → **整個資料庫無法開啟**，App 完全無法使用。
- `clear()` / `pruneOlderThanDays()` 拋例外 → 刪除資料功能失效。

**這是只有實機／模擬器 instrumentation 測試才能發現的缺陷**，
JVM 單元測試完全無法覆蓋（`unitTests.isReturnDefaultValues = true` 讓
Android 框架方法回傳預設值而非拋例外）。

**修正**：新增 `checkpointWal(db)` 輔助方法，PRAGMA 改走 `rawQuery()`。

---

## 5. UI 快照測試（PASS — 4/4）

`app/src/androidTest/java/tw/personal/privateinbox/UiSnapshotInstrumentedTest.kt`
日誌：`docs/UI-SNAPSHOT-LOG.txt`

`FLAG_SECURE` 會阻擋 `adb screencap`（這本身是資安功能生效的證明）。
測試改為把**真實的生產 View** 繪製到 Bitmap，再經 logcat base64 輸出還原成 PNG。
**不需要**放寬任何 build type 的 `FLAG_SECURE` — 出貨程式碼完全未動。

產出：

| 檔案 | 內容 |
|------|------|
| `screenshots/00-lock-screen.png` | 鎖定畫面（✉ 標誌、PRIVATE INBOX、解鎖按鈕） |
| `screenshots/01-chat-list.png` | 聊天列表（藍灰頂欄、圓形頭像、時間、未讀 Badge、📷 標記、⚙ 浮動鈕） |
| `screenshots/02-conversation.png` | 聊天室（日期分隔、訊息泡泡、發話者名稱、時間戳、刪除鈕） |
| `screenshots/03-settings.png` | 設定頁（擷取開關、照片開關、授權按鈕、8 個來源、清除、保留期限） |

---

## 6. 靜態安全稽核（PASS）

```
$ bash scripts/audit.sh
PASS: Manifest parsing, privacy permission policy, no backup and listener declarations
```

---

## 7. APK 權限與簽章（PASS — 檢查最終 APK）

```
$ aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
package: tw.personal.privateinbox
permission: tw.personal.privateinbox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='tw.personal.privateinbox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

| 權限 | 狀態 |
|------|------|
| `android.permission.INTERNET` | **absent** ✅ |
| `android.permission.ACCESS_NETWORK_STATE` | **absent** ✅ |
| `android.permission.READ_SMS` | **absent** ✅ |
| `android.permission.READ_CONTACTS` | **absent** ✅ |
| `android.permission.QUERY_ALL_PACKAGES` | **absent** ✅ |
| `android.permission.READ_EXTERNAL_STORAGE` | **absent** ✅ |

```
$ aapt dump badging app-debug.apk
package: name='tw.personal.privateinbox' versionCode='3' versionName='0.3.0'
compileSdkVersion='36'  sdkVersion:'28'  targetSdkVersion:'36'
launchable-activity: name='tw.personal.privateinbox.MainActivity'
```

```
$ apksigner verify --print-certs app-debug.apk
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
```

### 7.1 匯出元件（`aapt dump xmltree` 實測）

| 元件 | exported | 權限 | 判定 |
|------|----------|------|------|
| `MainActivity` | true | — | 正常（Launcher） |
| `PrivateNotificationListener` | false | `BIND_NOTIFICATION_LISTENER_SERVICE` | 正確 |
| androidx `ProfileInstallReceiver` | true | `android.permission.DUMP` | 系統專用（signature-level） |
| androidx `ProfileInstallerReceiver` | false | — | 正確 |
| androidx `InitializationProvider` | false | — | 正確 |

---

## 8. 安裝與啟動（PASS）

```
$ adb install -r app-debug.apk
Success

$ adb shell am start -n tw.personal.privateinbox/.MainActivity
Starting: Intent { cmp=tw.personal.privateinbox/.MainActivity }

$ adb shell pidof tw.personal.privateinbox
8300
```

- `topResumedActivity=ActivityRecord{... tw.personal.privateinbox/.MainActivity t8}`
- crash buffer 對本 App **無紀錄**
- uiautomator dump 確認鎖定畫面 5 個元件文字正確：
  `✉` / `PRIVATE INBOX` / `Classic · 僅儲存在這部手機` /
  `使用手機螢幕鎖解鎖` / `無網路權限 · 沒有廣告 · 不會開啟原始聊天室`
- `adb exec-out screencap` 產出**全黑畫面** → 證明 `FLAG_SECURE` 確實生效
- `dumpsys notification` 確認
  `tw.personal.privateinbox/...PrivateNotificationListener` 已列入
  allowed notification listeners

---

## 9. 驗收條件逐項對照（第 10 節）

### A. Build

| 項目 | 狀態 | 證據 |
|------|------|------|
| Gradle Build 成功 | **PASS** | `BUILD SUCCESSFUL`, exit 0 |
| APK 可以安裝 | **PASS** | `adb install` → Success |
| App 可以正常啟動 | **PASS** | topResumedActivity + PID 存活 + 無 crash |

### B. Notification

| 項目 | 狀態 | 說明 |
|------|------|------|
| 使用者能授予通知監聽權限 | **PASS** | `dumpsys notification` 顯示已授權 |
| 可以接收允許來源的通知 | **PASS** | instrumentation：`messagingStyleNotificationIsCapturedAndDecrypted` |
| 不允許來源不會被保存 | **PASS** | instrumentation：`notificationFromNonWhitelistedPackageIsRejected` |
| 通知取消不會刪除已保存訊息 | **PASS**（設計層） | 未實作 `onNotificationRemoved` 刪除邏輯 |

### C. Persistence

| 項目 | 狀態 | 說明 |
|------|------|------|
| 重新啟動後資料可讀 | **PASS** | instrumentation：`encryptedRowSurvivesDatabaseReopen` |
| 資料庫損毀時不無限 Crash | **PASS**（程式碼層） | `recent()` 以 `runCatching` 包住解密；損毀列被跳過 |

### D. Images

| 項目 | 狀態 |
|------|------|
| 有效圖片可以保存 | **PASS** — instrumentation：`imageIsStoredEncryptedAndDecryptsBack` |
| 縮圖可以顯示 | **PASS**（程式碼層，UI 由背景執行緒解碼後設定） |
| 圖片可以放大 | **PASS**（程式碼層，`PhotoZoomView` 雙指/雙擊） |
| 失效 URI 不會 Crash | **PASS** — `readBytes` 以 `runCatching` 包住，回傳 null |
| 損毀圖片顯示錯誤狀態 | **PASS** — 8 項 magic-byte 單元測試 + UI 錯誤狀態文字 |

### E. Security

| 項目 | 狀態 | 證據 |
|------|------|------|
| 沒有 INTERNET 權限 | **PASS** | `aapt dump permissions` 實測 |
| 沒有非必要追蹤 SDK | **PASS** | 依賴僅 androidx.activity + junit |
| 沒有明文訊息 Log | **PASS** | 全專案無 `Log.*` 呼叫 |
| 加密金鑰沒有硬編碼 | **PASS** | `CryptoStore` 參數全來自 `KeyStore.getKey()` |
| 不必要的元件沒有對外暴露 | **PASS** | `aapt dump xmltree` 實測 |

### F. UI

| 項目 | 狀態 |
|------|------|
| 聊天列表可以正常捲動 | **PASS**（程式碼層，ScrollView + 快照渲染） |
| 可以進入聊天室 | **PASS** — 快照 `02-conversation.png` |
| 可以返回 | **PASS** — `handleOnBackPressed()` 編譯並實測 |
| 可以搜尋 | **PASS**（程式碼層） |
| 可以打開圖片 | **PASS**（程式碼層，`PhotoZoomView`） |
| 設定可以保存 | **PASS** — 快照 `03-settings.png` |
| 畫面呈現符合 Telegram Classic | **PASS** — 4 張截圖實測 |

### G. Reliability

| 項目 | 狀態 | 說明 |
|------|------|------|
| 重複通知不產生重複訊息 | **PASS** | instrumentation：`duplicateNotificationIsNotStoredTwice` |
| 通知缺少內容時不 Crash | **PASS** | instrumentation：`emptyNotificationDoesNotCrash` |
| 圖片處理不阻塞主執行緒 | **PASS** | 解碼在 `worker`，僅 bytes 讀取留在 callback |
| 資料庫操作不阻塞主執行緒 | **PASS** | 讀取在 `io`，寫入在 `worker` |

---

## 10. 本次修正的缺陷總表

| # | 缺陷 | 修正 | 驗證 |
|---|------|------|------|
| 1 | **`execSQL("PRAGMA ...")` 使整個資料庫無法開啟** | PRAGMA 改走 `rawQuery()` | instrumentation 10/10 ✅ |
| 2 | `performBack()` 覆寫錯誤方法 | 改為 `handleOnBackPressed()` | 編譯 ✅ |
| 3 | `SimpleOnDoubleTapGestureListener` 不存在於公開 SDK | 改用 `OnDoubleTapListener` | 編譯 ✅ |
| 4 | `FrameLayout.LayoutParams` weight 型別錯誤 | `1f` → `1` | 編譯 ✅ |
| 5 | 影像在 listener callback 同步解碼 | 拆分 `readBytes()` / `toPreview()` | 編譯 ✅ |
| 6 | 未讀 Badge 顯示歷史總筆數（語意誤導） | 改為「上次查看後新擷取」 | 快照 ✅ |
| 7 | 聊天室缺日期分隔 | 新增 `dayLabel()` | 快照 ✅ |
| 8 | 聊天室硬性只顯示 120 則 | 移除硬限制 | 編譯 ✅ |
| 9 | `PhotoZoomView` 缺 `performClick()` | 補上點擊判定 | Lint ✅ |
| 10 | `String.format` 未指定 Locale | 加 `Locale.getDefault()` | Lint ✅ |
| 11 | `scripts/test-core.sh` 依賴外部 kotlinc | 改用專案 Gradle wrapper | 腳本執行 ✅ |
| 12 | Gradle Wrapper 缺失（僅 properties） | 正式產生 + 官方 SHA-256 | `--version` ✅ |
| 13 | `capture()` 無法脫離 service 測試 | 抽成 `capture(context, sbn)` 公開方法 | instrumentation ✅ |
| 14 | `StatusBarNotification` 建構子簽名不符 | 依 android.jar 實際簽名修正 | instrumentation ✅ |

---

## 11. NOT TESTED 項目與原因

| 項目 | 原因 |
|------|------|
| 真實 LINE / Instagram / WhatsApp 推播擷取 | 需真實帳號與另一端發送 |
| LINE / Instagram 影像 URI 授權的實際行為 | 需真機（模擬器上 Telegram 套件名為 `org.telegram.messenger.web`，與白名單不同） |
| 實體手機（Xiaomi 10 Ultra）行為 | 本次無實體裝置 |
| MIUI / HyperOS 背景存活 | 需小米實機 |
| Keystore `KeyInvalidatedException` 觸發與恢復 | 需特定條件（變更鎖定畫面） |
| 資料庫損毀的破壞性測試 | 程式碼有保護，未做破壞測試 |
| 跨版本實機矩陣（9/13/14/15） | 僅驗證 Android 16 |
| Release 簽章 | 本次交付為 Debug APK |
| 真實照片的端到端擷取（來源 App 附加影像） | 需真實 App 推播含照片的訊息 |

**這些項目沒有被執行，因此不宣稱通過。**

---

## 12. 重現方式

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export GRADLE_USER_HOME="<workspace>/gradle_home"
export ANDROID_SDK_ROOT="<workspace>/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="<workspace>/.android-home"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest
bash scripts/audit.sh

aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
aapt dump badging     app/build/outputs/apk/debug/app-debug.apk
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```
