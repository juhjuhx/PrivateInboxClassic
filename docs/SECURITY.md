# Private Inbox Classic — 資安設計與審查

本文件區分三類陳述，**不混用**：
「已驗證」= 本專案在本次建置中實際檢查過；
「未驗證」= 設計如此但未經實機測試；
「平台限制」= Android 平台本身的邊界，App 無法突破。

---

## 1. 已驗證的安全措施

### 1.1 最終 APK 權限檢查（`aapt dump permissions` 實測）

實際檢查輸出：

```
package: tw.personal.privateinbox
permission: tw.personal.privateinbox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='tw.personal.privateinbox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

逐項確認（全部 PASS）：

| 權限 | 結果 |
|------|------|
| `android.permission.INTERNET` | **absent** |
| `android.permission.ACCESS_NETWORK_STATE` | **absent** |
| `android.permission.READ_SMS` | **absent** |
| `android.permission.READ_CONTACTS` | **absent** |
| `android.permission.QUERY_ALL_PACKAGES` | **absent** |
| `android.permission.READ_EXTERNAL_STORAGE` | **absent** |

唯一的 `uses-permission` 是 androidx 自動產生的
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`——這是 androidx 為防止自家
動態廣播接收器被外部呼叫而自我簽署的權限，**不是系統權限**，
無法被其他 App 取得或利用。

**因此：本 App 在技術上無法連線到任何伺服器。** 這不是政策宣告，是缺少
`INTERNET` 權限的必然結果。

### 1.2 沒有追蹤 SDK

`app/build.gradle.kts` 的完整依賴清單：

```kotlin
dependencies {
    implementation("androidx.activity:activity:1.9.2")
    testImplementation("junit:junit:4.13.2")
}
```

沒有 Firebase、沒有 Crashlytics、沒有 Facebook SDK、沒有 Google Analytics、
沒有廣告 SDK、沒有任何遙測庫。
可用 `apkanalyzer` 或解壓 APK 的 `META-INF` 逐一核對。

### 1.3 金鑰管理

- 金鑰由 `android.security.keystore.KeyGenParameterSpec` 在
  **AndroidKeyStore** provider 內產生，App 程序無法匯出金鑰位元組。
- 演算法：AES-256-GCM（內容）、HMAC-SHA256（去重指紋）。
- 原始碼內**沒有任何**硬編碼的 key、IV 或 salt。`CryptoStore.kt` 全部
  參數來自 `KeyStore.getKey()`。
- GCM 的 IV 由 `Cipher.init(ENCRYPT_MODE, key)` 產生——**每次加密都是新的隨機 IV**，
  不會重複使用。密文格式為 `IV(12) || ciphertext||tag`。
- AAD 綁定套件名稱，防止密文被跨 App／跨欄位搬移重用。

### 1.4 資料保護

- 訊息文字（標題/傳送者/內文）以 AES-256-GCM 加密後才寫入 SQLite。
- 照片預覽同樣以 AES-256-GCM 加密為 BLOB。
- 照片存於 **App private storage**（`/data/data/tw.personal.privateinbox/`），
  由 Android 的 per-UID sandbox 保護。
- `PRAGMA secure_delete=ON`：刪除列時覆寫頁面內容。
- 刪除資料時執行 `PRAGMA wal_checkpoint(TRUNCATE)`，避免 WAL 殘留明文。
- **不在外部儲存空間留下任何明文副本**（沒有 `WRITE_EXTERNAL_STORAGE` 權限）。

### 1.5 匯出元件審查（`aapt dump xmltree` 實測）

| 元件 | exported | 權限 | 判定 |
|------|----------|------|------|
| `MainActivity` | `true` | — | **正常**：Launcher activity 必須 exported 才能從桌面啟動 |
| `PrivateNotificationListener` | `false` | `BIND_NOTIFICATION_LISTENER_SERVICE` | **正確**：Android 官方規範要求 |
| androidx `ProfileInstallReceiver` | `true` | `android.permission.DUMP` | **系統專用**：`DUMP` 是 signature-level 權限，只有 shell/root 可持有，第三方 App 無法呼叫 |
| androidx `ProfileInstallerReceiver` | `false` | — | 正確 |
| androidx `androidx.startup.InitializationProvider` | `false` | — | 正確 |

**沒有 FileProvider、沒有自訂 ContentProvider、沒有 exported Service/Receiver。**
App 不對外提供任何 content URI。

### 1.6 備份

- `android:allowBackup="false"`（Manifest 實測）
- `android:fullBackupContent="false"`
- `res/xml/data_extraction_rules.xml`：`<cloud-backup>` 與 `<device-transfer>` 皆為空
  → **不參與 Auto Backup，也不參與裝置轉移**。

理由：Keystore 金鑰不會跟著資料備份走。若允許備份，會得到一份「有密文、
無金鑰」的資料庫——既無法還原，又等於把密文送離本機。

### 1.7 Logcat

- `PrivateNotificationListener` 的 `catch (_: Exception) {}` 區塊**刻意留空**，
  不記錄任何訊息、影像內容或 provider 路徑。
- 全專案無 `Log.d` / `Log.i` / `Log.e` 呼叫。
- `ImageCapture` 失敗只回傳 `null`，不記錄檔案路徑或 URI。

### 1.8 螢幕保護

- `window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)` 在 `onCreate` 立即設定。
  效果：禁止截圖、禁止螢幕錄影、禁止多任務縮圖預覽。
- **保護範圍說明**：`FLAG_SECURE` 只擋住「畫面被複製」。它**不**防止：
  - 有 Root／系統級權限的程式直接讀取本 App 的記憶體或檔案；
  - 使用者在 App 內主動截圖之外的任何形式的外部攝影；
  - 第三方鍵盤的側錄——本 App 未自建鍵盤。

### 1.9 App Lock

- 進入需 `KeyguardManager.createConfirmDeviceCredentialIntent` 驗證裝置身分。
- 若裝置未設定 PIN/密碼/圖形鎖，直接拒絕進入（不降級）。
- `onStop()` 立即 `unlocked = false`、清空記憶體中的解密資料、回到鎖定畫面。
- **不繞過**系統驗證：無法在不通知使用者的情況下讀取內容。

### 1.10 來源白名單雙重把關

擷取前必須同時滿足：
1. `CapturePolicy.allowed(pkg, selected)`：套件名在**程式內建白名單**中
   （LINE / Instagram / WhatsApp / Messenger / Telegram / Telegram X / Teams / KakaoTalk）；
2. 該套件在 `LocalSettings.selected()` 中被**使用者手動勾選**。

任一不滿足即拒絕。預設全部關閉。
程式碼中沒有讀取其他 App 資料庫、沒有 Accessibility Service、
沒有 Root 指令、沒有繞過沙盒的邏輯。

### 1.11 專案授權

本專案為獨立實作。**未**複製、fork 或改作 QuietInbox 或其他 GPL 專案的原始碼，
因此不承擔 GPL-3.0-or-later 的原始碼提供義務。
唯一的第三方 runtime 依賴 `androidx.activity` 為 Apache-2.0。

參考 QuietInbox 的**產品理念**（離線保存通知、SQLite 儲存、來源白名單），
這是功能概念的參考，不涉及程式碼複製。

---

## 2. 已知未驗證的風險

以下項目**設計上已處理，但本次未經實機測試**（無 Android 裝置／模擬器）：

| 項目 | 狀態 |
|------|------|
| 通知擷取端到端行為（LINE 實際推播） | NOT TESTED |
| LINE / Instagram 影像 URI 授權的實際行為 | NOT TESTED — 已知高風險，見 §4 |
| `MessagingStyle` 群組發話者名稱的實際呈現 | NOT TESTED |
| Android Keystore `KeyInvalidatedException` 的實際觸發與恢復路徑 | NOT TESTED |
| MIUI / HyperOS 的背景存活 | NOT TESTED — 見 `ANDROID-COMPATIBILITY.md` |
| Instrumentation / UI 自動化測試 | NOT TESTED |
| 長時間運行的記憶體洩漏 | NOT TESTED |
| 資料庫損毀後的實際行為 | 部分驗證（程式碼有 try/catch 保護，但未經破壞性測試） |

### 2.1 `KeyInvalidatedException` 處理狀況

`CryptoStore.aesKey()` 在 `KeyStore.getKey()` 取得金鑰時，若金鑰因
「裝置鎖定畫面變更」「生物辨識重新註冊」等原因被系統失效，會拋出
`KeyInvalidatedException`。

**現況**：`InboxDatabase.recent()` 與 `loadImage()` 以 `runCatching` 包住解密，
單筆解密失敗會被跳過，App **不會 Crash**。
但**尚未實作**「金鑰失效 → 偵測 → 提示使用者 → 清空無法解密的資料列」的
完整流程。若金鑰失效，所有既有密文都將永久不可讀，App 會顯示一個空清單
而不是明確錯誤。

**這是本專案最優先的待辦項目。**

### 2.2 資料庫未整體加密

**明確聲明**：本專案**不是** SQLCipher。資料庫檔案本身是明文的 SQLite。

未加密的欄位：

| 欄位 | 內容 | 風險評估 |
|------|------|----------|
| `package_name` | 來源 App 套件名 | 洩漏「你用哪些通訊軟體」 |
| `received_at` | 擷取時間戳 | 洩漏「你什麼時候收到訊息」 |
| `source_ts` | 原始訊息時間戳 | 洩漏「對方什麼時候傳訊息」 |
| `fingerprint` | HMAC-SHA256 | 不可逆推原文，但相同訊息有相同指紋，可做關聯分析 |
| 資料表列數 | 訊息總數 | 洩漏聊天量 |

**已加密的欄位**：標題、傳送者、訊息內文、照片本體。

風險權衡：採用 SQLCipher 會增加一個原生 `.so` 依賴、顯著增大 APK、
並引入「何時載入 passphrase」的額外問題。目前選擇「內容加密 + metadata 明文」
的方案，並在此明確揭露其邊界。

### 2.3 其他

- `SharedPreferences`（來源開關、保留期限、已讀標記）**未加密**。
  內容敏感度低（僅 App 內部設定與 thread key），但已讀標記會洩漏
  「你曾經看過哪個對話」。
- 搜尋功能會把最近 1500 筆解密到記憶體比對。離開背景時清空參照。
- 資料無備份／匯出機制（刻意設計）。
- 清除資料不保證快閃記憶體的物理覆寫（`secure_delete` 只覆寫頁面內容）。

---

## 3. Android 平台本身的限制

以下**不是**本 App 可以解決的問題，屬於 Android 沙盒與通知系統的邊界：

1. **通知存取權限是全域廣泛權限。**
   `BIND_NOTIFICATION_LISTENER_SERVICE` 授予後，App 可看到**所有** App 的通知
   （雖然本專案在程式內用白名單限制實際擷取範圍）。這是使用者必須知情同意的。

2. **無法讀取通訊 App 的歷史聊天記錄。**
   通知監聽只拿到「當下被投递到通知列的內容」。已撤回、未推播、或在推播前
   已讀的訊息，本 App **永遠看不到**。

3. **影像 URI 授權是短暫且不保證的。**
   某些 App 給的 `content://` URI 只在通知存活期間可讀；本 App 已在
   listener callback 內立即讀取 bytes 以最大化成功率的機會，
   但仍**不能保證**取得照片本體。

4. **沒有 Root 的裝置上，App 無法看到其他 App 的私有資料。**
   本專案**不**依賴 Root，也**不**嘗試繞過沙盒。

5. **系統更新可能改變通知 extras 結構。**
   已對 API 30+ 與 API 28–29 分別處理，但未來版本仍可能需要調整。

6. **OEM 背景限制。**
   MIUI / HyperOS / OneUI 等會殺死背景 App 的監聽服務。詳見相容性文件。

---

## 4. 影像擷取的誠實邊界

這是本專案最容易被誤解的部分，因此明確列出：

| 情況 | 本 App 的行為 |
|------|--------------|
| 通知內含 `EXTRA_PICTURE`（BigPictureStyle bitmap） | 保存縮圖 ✅ |
| 通知內含 `EXTRA_PICTURE_ICON`（API 31+） | 保存縮圖 ✅ |
| `MessagingStyle.Message` 的 `dataUri` 為 `image/*` 且可讀 | 保存縮圖 ✅ |
| 通知只有文字「傳送了一張照片」，**沒有**附帶任何影像資料 | **只保存文字，不產生假圖片** ✅ |
| 影像 URI 已被撤銷／讀取失敗 | 只保存文字（不記錄內容到 Logcat） ✅ |
| 影像格式損毀 | magic-byte 檢查攔下，不解碼 ✅ |
| 影像超過 1280px | 降級縮圖，最長邊 1280px ✅ |
| 影像超過 700 KiB（JPEG 壓縮後） | 逐級降低品質，仍過大則放棄存圖 ✅ |

**不會**發生的事：不會用佔位圖假裝是照片、不會宣稱能還原所有已撤回的圖片、
不會為了拿照片去讀其他 App 的私有資料、不會把任何圖片上傳到第三方。

---

## 5. 依賴檢查結果

| 依賴 | 版本 | 授權 | 用途 |
|------|------|------|------|
| `androidx.activity:activity` | 1.9.2 | Apache-2.0 | predictive-back 相容的 `OnBackPressedCallback` |
| `junit:junit` | 4.13.2 | EPL-1.0 | 單元測試（僅 test） |
| AGP | 8.13.2 | Apache-2.0 | 建置工具 |
| Kotlin Gradle Plugin | 2.2.20 | Apache-2.0 | 語言工具鏈 |

無原生 `.so` 依賴（無 SQLCipher、無 Glide、無 Picasso）。
無已知 CVE 的遙測或廣告 SDK。

---

## 6. 結論

**已驗證**：無網路權限、無追蹤 SDK、金鑰在 Keystore、內容 AES-256-GCM 加密、
匯出元件最小化、備份關閉、Logcat 無明文、來源白名單雙重把關。

**未驗證**：所有端到端通知／影像擷取的實機行為、Keystore 金鑰失效的完整恢復流程。

**平台限制**：無法讀取通訊 App 歷史記錄、無法保證取得影像本體、
通知存取權限是廣泛權限、OEM 可能殺死背景服務。

**不能聲稱的**：本 App「絕對安全」、「能還原所有已撤回的訊息與照片」、
「在任何 MIUI 版本都能常駐」——這三項均無證據支持。
