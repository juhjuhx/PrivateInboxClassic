# Private Inbox Classic — Android

**版本：0.3.0（可編譯、已產出 Debug APK、已通過 18 項單元測試 + Android 16 模擬器驗證）**

古早 Telegram Android 2.0 / Material Design 1.0 視覺語言的純離線通知收件匣。
獨立實作，參考 QuietInbox 的**產品理念**，**不是 QuietInbox 的 fork，也未複製其 GPL 原始碼**。
本專案與 Telegram / LINE / QuietInbox 均無關聯。

---

## 這是什麼

一個**完全離線**、個人使用的 Android 通知訊息保存工具。

它把 Android 系統交給 `NotificationListenerService` 的通知內容，在本機加密保存成副本。
當原始訊息在 LINE / Telegram / WhatsApp 被撤回時，只要通知在撤回前被成功擷取，
你仍能查看當時的副本。

它**不是**聊天客戶端，**不能**讀取通訊 App 的歷史記錄，**不需**任何帳號密碼、Root 或 Accessibility。

---

## 功能

- **Telegram Classic 介面**：傳統藍灰頂欄（`#5682A3` / 狀態列 `#497799`）、
  白底圓形頭像列表、緊湊訊息列、聊天室泡泡、日期分隔、單張照片大圖檢視。
- **通知擷取**：`NotificationListenerService` 擷取使用者明確勾選來源的新通知；
  支援 `MessagingStyle`、`InboxStyle`、`BigTextStyle` 與一般通知。
- **照片擷取**：`MessagingStyle.Message.dataUri`（`image/*` MIME）、
  `BigPictureStyle` 的 `EXTRA_PICTURE` / `EXTRA_PICTURE_ICON`。
  僅能取得通知系統提供且當下有讀取權限的媒體。
- **加密**：圖片最多長邊 1280 px、JPEG 壓縮後存為 **Android Keystore AES-256-GCM 加密的 SQLite BLOB**；
  文字（標題/傳送者/內文）同樣加密；去重指紋用 HMAC-SHA256。
  不會偷偷讀相簿或檔案庫，每張上限約 700 KiB。
- **App Lock**：進入必須驗證手機解鎖憑證；`FLAG_SECURE` 防止截圖；離開畫面立即重新鎖定。
- **零網路**：沒有 `INTERNET` 權限、廣告、遙測、雲端備份、Accessibility、Root、
  `READ_EXTERNAL_STORAGE`。
- **預設全關**：通知來源與照片擷取都必須使用者手動開啟，並到系統授予通知存取權限。

---

## 已驗證的建置與測試

| 項目 | 狀態 |
|------|------|
| `./gradlew :app:assembleDebug` | **PASS**（BUILD SUCCESSFUL） |
| 單元測試 | **PASS** 18/18（CapturePolicy 10 + ImageCapture 8） |
| Android Lint | **PASS**（0 error） |
| 靜態安全稽核 `scripts/audit.sh` | **PASS** |
| APK 權限（`aapt dump permissions` 檢查最終 APK） | **PASS** — 無 INTERNET / READ_SMS / READ_CONTACTS / QUERY_ALL_PACKAGES |
| APK 簽章 | **PASS**（APK Signature Scheme v2） |
| 安裝於 Android 16 模擬器 | **PASS** |
| App 啟動 | **PASS**（無崩潰，UI 結構經 uiautomator 驗證） |
| Instrumentation 測試 | 見 `docs/TEST-REPORT.md` |

完整證據：`docs/TEST-REPORT.md`、`docs/BUILD-LOG.txt`、`docs/INSTRUMENTED-TEST-LOG.txt`。

---

## 建置 APK

需要 **JDK 17**、**Android SDK 36**、**Build Tools 36.0.0**。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"

./gradlew --no-daemon :app:assembleDebug
```

產出：`app/build/outputs/apk/debug/app-debug.apk`

若在沙盒／受限環境中，`~/.gradle` 與 `~/Library/Application Support` 可能不可寫，
請設定：

```bash
export GRADLE_USER_HOME="$PWD/../gradle_home"
```

並在 `gradle.properties` 保留 `kotlin.compiler.execution.strategy=in-process`
（避免 Kotlin daemon 需要寫入 home 目錄）。

### 本次交付的 APK

| 項目 | 值 |
|------|-----|
| 路徑 | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | 5,892,991 bytes（約 5.6 MB） |
| SHA-256 | `bcc174768630aacf4b45c2682654e401aaccc7336d7d5f4f7a6c72869eda3642` |
| Application ID | `tw.personal.privateinbox` |
| Version | `0.3.0` (versionCode 3) |
| minSdk / targetSdk | 28 / 36 |
| 簽章 | Android Debug key（**測試版**） |

> **這是 Debug 測試版 APK**，由 Gradle 自動產生的 debug key 簽署。
> 正式使用請自備 release keystore 簽章；且不同機器產生的 debug key 不同，
> 會導致無法覆蓋更新既有安裝。

---

## 安裝與使用

1. 在 Android 9–16 裝置安裝 APK，**先設定 PIN / 密碼 / 圖形鎖**（本 App 需要裝置身分驗證）。
2. 開啟 App，點「使用手機螢幕鎖解鎖」。
3. 進入右下角 ⚙ 設定 → 勾選要擷取的來源（LINE / Instagram / WhatsApp / Messenger / Telegram…）。
4. 開啟「保存可存取的通知照片」（若要存照片）。
5. 點「前往 Android 通知存取授權」→ **只授權此應用程式**。
6. 小米 / MIUI / HyperOS 裝置請另做背景與省電設定，見 `docs/ANDROID-COMPATIBILITY.md` §3。
7. 從**另一個帳號**發送非敏感測試訊息，確認本 App 出現該則副本。
8. 撤回原訊息，確認副本**仍然存在**。

---

## 專案結構

```
app/src/main/java/tw/personal/privateinbox/
├── MainActivity.kt                 全部 UI（鎖定 / 列表 / 聊天室 / 照片 / 搜尋 / 設定）
├── PrivateNotificationListener.kt  通知監聽服務
├── CapturePolicy.kt                純 Kotlin 來源白名單與文字正規化
├── CryptoStore.kt                  Keystore AES-256-GCM + HMAC
├── ImageCapture.kt                 影像驗證 / 降級 / 壓縮
├── InboxDatabase.kt                SQLite schema v3 + 遷移
├── LocalSettings.kt                設定、保留期限、已讀標記
└── PhotoZoomView.kt                純框架縮放照片檢視器

app/src/test/          JVM 單元測試（18 項）
app/src/androidTest/   Instrumentation 測試
```

詳細架構見 `docs/ARCHITECTURE.md`。

---

## 安全驗收指令

```bash
bash scripts/audit.sh

# 針對最終 APK（不是原始碼）
aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
aapt dump badging     app/build/outputs/apk/debug/app-debug.apk
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

完整資安審查報告見 `docs/SECURITY.md`。

---

## 已知限制

1. **不能讀取通訊 App 的原始聊天資料庫。**
   只有在通知內容成功保存後，撤回的訊息才可能留有副本。
   開啟本 App **不會**讓原始聊天室標記已讀；反之，使用者在原始 App 開啟對話仍會標記已讀。
2. **照片只保存通知「實際提供」的那一份，且必須當下可讀。**
   若通知只有文字「傳送了一張照片」而沒有附帶影像資料，本 App **只保存文字**，
   不會用假圖片代替。**不能還原所有已撤回的照片。**
3. **保存的是壓縮預覽**，不是原始照片：最長邊 1280 px、JPEG、≤ 700 KiB、
   不保留 EXIF、不保留動態照片或 GIF 動畫。
4. **資料庫不是整體 SQLCipher 加密。**
   內容（標題/傳送者/內文/照片）已加密；
   但來源套件名、時間戳、訊息筆數仍為明文 metadata。詳見 `docs/SECURITY.md` §2.2。
5. **解除安裝、重設 Keystore 或清除 App 資料後，資料可能永久無法復原。**
   沒有備份或匯出功能（刻意設計，避免敏感資料外流）。
6. **Android Keystore 金鑰失效**（例如變更鎖定畫面）會使既有密文永久不可讀。
   目前只做到「不 Crash、略過無法解密的列」，**尚未實作明確的錯誤提示與資料清理流程**——
   這是最優先的待辦項目。
7. **部分 Android / OEM 可能不保證背景監聽、短暫 URI 權限或通知完整性。**
   本專案**不依賴 Root**，也**不嘗試**繞過 Android 沙盒。
8. **同名聯絡人無法區分**：通知 API 只提供顯示名稱，同一 App 內兩個同名聯絡人會被合併。
9. **搜尋僅涵蓋最近 1500 筆**，且會暫時將資料解密到記憶體比對。
10. **沒有 Instrumentation / UI 自動化測試的完整覆蓋**（本次僅部分補上）。

---

## 文件

| 文件 | 內容 |
|------|------|
| `docs/ARCHITECTURE.md` | 實際架構、資料流、資料模型、加密設計、執行緒模型 |
| `docs/SECURITY.md` | 資安審查：已驗證 / 未驗證 / 平台限制 三分法 |
| `docs/NOTIFICATION-CAPTURE.md` | 通知擷取、去重策略、已知限制 |
| `docs/IMAGE-CAPTURE.md` | 影像擷取流程、安全措施、誠實邊界 |
| `docs/ANDROID-COMPATIBILITY.md` | 各版本重點、小米/MIUI 適配指引 |
| `docs/TEST-REPORT.md` | 測試結果（PASS / FAIL / NOT TESTED） |
| `docs/HANDOFF.md` | 交接文件：已完成 / 阻塞 / 下一步 |
| `docs/UI_SPEC.md` | 視覺規格 |
| `docs/BUILD-LOG.txt` | 完整編譯日誌 |
| `docs/INSTRUMENTED-TEST-LOG.txt` | Instrumentation 測試日誌 |

---

## 授權

本專案原始碼為獨立實作。未複製或改作任何 GPL 專案原始碼。
唯一的第三方 runtime 依賴為 `androidx.activity`（Apache-2.0）。
測試依賴 `androidx.test.*` 與 `junit` 僅用於測試，不會進入 APK。

---

## 參考

- <https://gitlab.com/aa22396584/quietinbox>（產品理念參考）
- <https://telegram.org/blog/android-2-0>（視覺語言參考）
- <https://developer.android.com/reference/android/service/notification/NotificationListenerService>
- <https://developer.android.com/reference/android/app/Notification.MessagingStyle.Message>

**清單右側的藍色數字是「自你上次查看該對話後新擷取的通知筆數」，
不是 LINE / Telegram 真正的未讀數量**（那個數字本 App 無法觀測）。
