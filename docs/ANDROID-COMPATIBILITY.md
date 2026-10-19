# Android 相容性（ANDROID-COMPATIBILITY）

## 1. 支援範圍

| 項目 | 值 |
|------|-----|
| `minSdk` | 28（Android 9 Pie） |
| `targetSdk` | 36（Android 16） |
| `compileSdk` | 36 |
| 實測建置環境 | macOS 15.8.1 (aarch64)、JDK 17.0.20.1、Gradle 8.13、AGP 8.13.2、Build Tools 36.0.0 |

**實機驗證狀態：尚未進行。** 本專案在本次交付中完成的是
「可編譯、可簽章、權限正確」的驗證，**不是**跨版本實機行為的驗證。
詳見 `TEST-REPORT.md`。

---

## 2. 各版本重點

### Android 9 / 10（API 28–29）

- `MessagingStyle` 的 `EXTRA_MESSAGES` 只能靠手動解析 Bundle 欄位
  （`text` / `sender` / `time` / `uri` / `type`）。
  這些是平台內部欄位名，Android 官方未保證穩定。
- `EXTRA_PICTURE_ICON`（`Icon`）**不存在**，只有 `EXTRA_PICTURE`。
- 已用 `@Suppress("DEPRECATION")` 標示相容路徑。

### Android 11（API 30）

- `Notification.MessagingStyle.Message.getMessagesFromBundleArray()` 正式可用，
  這是取得 `dataUri` / `dataMimeType` / `senderPerson` 的正確途徑。
- 本專案在此版本起改用官方解析 API。

### Android 12（API 31）

- 新增 `EXTRA_PICTURE_ICON`（`Icon` 型別），可取得 BigPictureStyle 的圖示。
- 需 `Icon.loadDrawable(context)` 轉成 `Drawable` 再畫成 `Bitmap`。

### Android 13（API 33）

- `extras.getParcelable(key, Class)` 型別安全 API 可用，避免 ClassCastException。
- **通知權限變更**：App 不再能直接讀取其他 App 的通知；
  `NotificationListenerService` 改為需要使用者在
  「設定 → 通知 → 通知存取權限」中明確授權。
  系統在授予後會顯示一次性告知。
- 本專案已提供「前往 Android 通知存取授權」按鈕。

### Android 14（API 34）

- **背景執行限制**：對「從背景啟動 Activity」與「背景啟動前景服務」有更嚴格規範。
  影響：背景期間無法自行彈出 UI。
  本專案的 `NotificationListenerService` 是**系統綁定的既有服務**，
  不屬於受限情境，**不需要** foreground service。
- `targetSdk 34+` 的 edge-to-edge 強制性：本專案已於 `onCreate` 設定
  `setOnApplyWindowInsetsListener` 處理系統列 inset，避免控制項被遮住。

### Android 15（API 35）

- 對 targetSdk 35 的 App 強制 edge-to-edge。
- 新的「敏感度」分類 API（`Notification.FLAG_RECEIVED_FOREGROUND` 等）。
  本專案未依賴這些旗標。
- 前台服務時限限制：對本專案無影響（未使用 FGS）。

### Android 16（API 36）

- 本專案的 `compileSdk` / `targetSdk` 為 36，**可編譯**。
- edge-to-edge 強制執行，程式碼已處理。
- 部分 `Window` API 標記為 deprecated（`statusBarColor` / `navigationBarColor`
  / `systemUiVisibility`）——本專案仍使用它們以維持 2014–2016 Material One
  視覺的固定配色；編譯器僅發出警告，非錯誤。
  未來若需改用 `WindowInsetsController` 配色，屬於視覺微調，不影響功能。

---

## 3. 小米 / MIUI / HyperOS 適配指引

> **重要聲明**：以下設定是「協助提高存活率」的建議，
> **無法保證**所有 MIUI / HyperOS 版本都能讓背景服務常駐。
> 本專案**不會**聲稱保證背景常駐。

### 3.1 通知存取權限

設定 → 應用程式管理 → 其他權限 → **通知存取權限** → 開啟「Private Inbox Classic」。

MIUI 額外注意：部分版本在「無權限」頁面另有「通知讀寫」項目，
需一併開啟。

### 3.2 允許後台運行

設定 → 應用程式管理 → Private Inbox Classic → 其他設定 →
**允許後台活動 / 後台彈出介面 / 允許自啟動** 全部開啟。

MIUI 12+ 另有「省電策略」需設為「無限制」。

### 3.3 電池最佳化排除

設定 → 應用程式管理 → Private Inbox Classic → 省電 →
**不要自動管理 / 允許後台活動** → 關閉「休眠」。

或直接開啟：
設定 → 其他設定 → 應用程式管理 → 搜尋本 App → 省電策略 → 無限制。

### 3.4 鎖屏顯示（若希望鎖屏時仍能擷取）

本專案**不需要**通知內容在鎖屏上顯示才能擷取——
`NotificationListenerService` 讀的是系統投遞的通知物件，
與鎖屏顯示設定無關。

但若來源 App 在鎖屏時隱藏內容（例如設為「隱藏敏感內容」），
則**該狀態下推播的訊息可能不含完整文字**。
這是來源 App 的行為，非本專案可控制。

### 3.5 HyperOS 額外注意

- HyperOS 對後台活動的限制比 MIUI 更嚴。
- 部分版本需要開啟「自啟動管理」白名單。
- 部分版本有「省電模式」會直接凍結第三方背景服務。

**誠實的結論**：即使完成以上所有設定，某些 MIUI / HyperOS 版本
在極端省電情境下仍可能停止背景監聽。**本專案不對此提供保證。**

---

## 4. 通知擷取的平台限制

| 限制 | 影響 |
|------|------|
| 通知存取權限是全域的 | 授予後理論上可見所有通知（本專案用程式內白名單限制） |
| 已撤回的訊息 | 只有在**撤回前**已成功擷取才留有副本 |
| 靜音時不推播的 App | 永遠擷取不到 |
| 使用者已讀的訊息 | 若 App 在讀取前就撤銷通知，本 App 看不到 |
| 影像 URI 授權短暫 | 可能讀不到照片本體 |
| ROM 差異 | 部分 OEM 對通知 extras 處理不同 |

**本專案不依賴 Root、不使用 Accessibility Service、不繞過沙盒。**
在一般非 Root Android 裝置上運作。

---

## 5. 檢查清單（使用者安裝後）

1. 設定 PIN / 密碼 / 圖形鎖（App 要求裝置身分驗證，沒有就拒絕執行）。
2. 安裝 APK 後開啟 App → 完成裝置身分驗證。
3. 進入設定 → 勾選要擷取的來源（LINE / Instagram / WhatsApp…）。
4. 開啟「保存可存取的通知照片」（若要存照片）。
5. 點「前往 Android 通知存取授權」→ 只勾選本 App。
6. 小米裝置：完成 §3.2 / §3.3 的後台與省電設定。
7. 從**另一個帳號**發送非敏感測試訊息。
8. 回到本 App，確認列表出現該則訊息。
9. 測試照片：發送含照片的訊息，確認是否出現 📷 與縮圖。
   **若沒有照片，說明該 App 的通知未附帶影像資料——這是正常行為。**
10. 測試撤回：在來源 App 撤回訊息，確認本 App 的副本**仍然存在**。
11. 重啟手機，確認資料仍可讀取。

---

## 6. 已驗證 / 未驗證

| 項目 | 狀態 |
|------|------|
| compileSdk 36 / targetSdk 36 可編譯 | **PASS**（實測） |
| minSdk 28 設定正確 | **PASS**（aapt 實測 `sdkVersion:'28'`） |
| Android 9 實機行為 | **NOT TESTED** |
| Android 13 通知授權流程 | **NOT TESTED** |
| Android 14/15/16 edge-to-edge 實機呈現 | **NOT TESTED** |
| MIUI / HyperOS 背景存活 | **NOT TESTED** |
| 鎖屏狀態下的擷取 | **NOT TESTED** |
