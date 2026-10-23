# Android 與 OEM 相容性（COMPATIBILITY）

> 分支：`fix/x200pro-notification-burst`（v0.4.4）
> **狀態：部分驗證。** 有實測證據的行標「已測試」；
> 沒有實機的行標「NOT TESTED」。

---

## 1. 三層架構

這個 App **不是**某個品牌的手機專用產品。通知處理的架構分成三層：

### 第 1 層：Android Framework

| 元件 | 依賴的 API 契約 | 最低版本 |
|------|---------------|---------|
| `NotificationListenerService` | 綁定後收到 `onNotificationPosted` / `onNotificationRemoved` / `onListenerConnected` | API 18 |
| `MessagingStyle` | `EXTRA_MESSAGES` 攜帶歷史 | API 24 |
| `Notification.shortcutId` | 對話身分的可靠來源 | API 25 |
| `AndroidKeyStore` | AES-256-GCM、HMAC-SHA256 | API 23 |
| `SQLiteOpenHelper` | schema v4 | API 28（minSdk）|
| `requestRebind` | 使用者要求時重新綁定 | API 24 |
| `POST_NOTIFICATIONS` | 執行時期權限 | API 33 |

**這一層是穩定的，App 只依賴它。**

### 第 2 層：能力偵測（Capability Detection）

`DeviceCapabilities`（`app/src/main/java/.../DeviceCapabilities.kt`）
在執行期偵測實際可用的能力：

| 偵測項 | 方式 | 影響 |
|--------|------|------|
| Android 版本 | `Build.VERSION.SDK_INT` | 決定權限流程 |
| 製造商 | `Build.MANUFACTURER` | 決定要給哪一種背景設定指引 |
| 通知歷史上限 | 平台常數 25 | 決定單次修訂能帶多少訊息 |
| 重新綁定 | API 24+ | 「重新檢查連線」按鈕 |
| 移除回呼 | API 18+ | 釋放每則通知的記憶 |

**未知的製造商永遠落到 `OTHER`，不會被猜成已知品牌** ——
給錯的品牌建議比沒有建議更糟。

### 第 3 層：OEM 指引（只有建議，沒有動作）

App **永遠不會**修改系統安全設定。它只告訴使用者要去哪裡改。

---

## 2. 已知的平台限制

| 限制 | 數值 | 影響 |
|------|------|------|
| `MessagingStyle` 歷史上限 | **25 則** | 單次修訂最多帶 25 則訊息。第 26 則以後**任何 App 都收不到** |
| 通知存取權限 | 使用者授權 | 撤銷後 App 立即停止擷取 |
| 自我通知排除 | 平台行為 | App 自己發出的通知不會回到自己的 listener |
| 背景限制 | 各家 ROM 不同 | 需要使用者手動設定 |

### 關於 25 則上限

這是 `Notification.MAX_MESSAGES = 25`，在所有支援的版本上都一樣。

**實務影響有限**：群組一次來 30 則時，前 25 則會被截斷，
但每一則訊息都是**獨立的一次修訂**被送達，實務上每一則都會在
某一次修訂中出現在歷史裡而被擷取到。
只有當 26 則以上**在同一次修訂內同時到達**時，第 26 則才會被丟棄。

---

## 3. Android 版本矩陣

| 版本 | API | 狀態 | 證據 |
|------|-----|------|------|
| Android 9 | 28 | **NOT TESTED** | 沒有該版本的設備 |
| Android 10 | 29 | **NOT TESTED** | |
| Android 11 | 30 | **NOT TESTED** | |
| Android 12 / 12L | 31 / 32 | **NOT TESTED** | |
| Android 13 | 33 | **NOT TESTED** | |
| Android 14 | 34 | **NOT TESTED** | |
| Android 15 | 35 | **NOT TESTED** | |
| **Android 16** | **36** | **已測試** | 105 項 instrumentation 全過、50 項 unit 全過、CI 綠 |

**minSdk 是 28**，程式碼只使用 API 28 有的東西。
`DeviceCapabilities` 的每一個能力偵測都對應到一個實際的版本差異。

> **不能宣稱「完全相容」。** 沒有一台 Android 9 的實機跑過這個 App。

---

## 4. OEM / ROM 矩陣

### 4.1 國際版

| 品牌 / ROM | 背景政策 | App 內的指引 | 狀態 |
|-----------|---------|------------|------|
| Google Pixel / AOSP | 無特殊限制 | 不顯示 | **已測試**（模擬器為 AOSP userdebug）|
| Samsung One UI | 會限制背景 App | 未實作 | **NOT TESTED** |
| Motorola Android | 接近原生 | 不顯示 | **NOT TESTED** |
| 其他 AOSP 衍生版 | 依 ROM 而定 | 落到 `OTHER` | **NOT TESTED** |

### 4.2 中國大陸版

**這些 ROM 沒有 Google Play Services，本 App 也不需要它。**
App 沒有任何 Play Services 相依，也沒有任何網路 SDK。

| 品牌 / ROM | 背景政策 | App 內的指引 | 狀態 |
|-----------|---------|------------|------|
| **vivo OriginOS / Funtouch OS** | 會限制背景，需要**三項**同時開啟 | ✅ 已實作 | **NOT TESTED**（無實機）|
| Xiaomi MIUI / HyperOS | 會限制背景 | ✅ 已實作 | **NOT TESTED** |
| OPPO ColorOS | 會限制背景 | ✅ 已實作 | **NOT TESTED** |
| realme UI | ColorOS 衍生 | ✅ 已實作 | **NOT TESTED** |
| HONOR MagicOS | 會限制背景 | ✅ 已實作 | **NOT TESTED** |
| Huawei EMUI / HarmonyOS | 會限制背景 | ✅ 已實作 | **NOT TESTED** |

### 4.3 vivo 的三項背景設定

**只開一項通常不夠。**

| 設定 | 路徑 | 效果 |
|------|------|------|
| 允許自啟動 | 設定 → 應用程式管理 → 權限管理 → 自啟動管理 | 允許程式在背景自行啟動 |
| 允許後台高耗電 | 設定 → 電池 → 後台耗電管理 | 鎖屏時不被限制 |
| 最近任務畫面鎖定 | 最近任務 → 長按卡片 → 鎖定 | 上滑清除不會結束程式 |

**App 不會也不應該自動修改這些設定。** 那是使用者的選擇。

官方說明：
- <https://www.vivo.com/tw/support/questionByTitle?title=%E8%83%8C%E6%99%AF%E9%81%8B%E8%A1%8C>
- <https://kefu.vivo.com.cn/robot/imgmsgData/3dfcdb616d0542b4baa4e9ead6d75c5e/index_1.html>

---

## 5. 相容性測試矩陣

| 測試 | 方法 | 狀態 |
|------|------|------|
| 通知存取授權 / 撤銷 | `settings get secure enabled_notification_listeners` | **已測試**（授權路徑）|
| Listener 重新綁定 | `requestRebind` + `ensureListener` | **已測試** |
| 前景 | 儀器測試 | **已測試** |
| 背景 | 需要實機 | **NOT TESTED** |
| 鎖屏 | 需要實機 | **NOT TESTED** |
| 省電模式 | 需要實機 | **NOT TESTED** |
| 行程重建 | `adb shell am kill` | 需要實機 |
| 多個通訊 App | 來源篩選 | **已測試**（合成來源）|
| 對話通知分組 | 群組摘要測試 | **已測試** |
| 重複通知更新 | 突發矩陣 TEST-E | **已測試** |
| 資料庫重開 | `KeyStateInstrumentedTest` | **已測試** |
| 裝置重啟 | 需要實機 | **NOT TESTED** |

---

## 6. 不能做的事

| 禁止 | 原因 |
|------|------|
| 以 Root 取得通知 | 會破壞安全模型，且不被允許作為一般安裝要求 |
| 用 Accessibility 服務攔截通知 | 同上；Android 已經提供通知存取權限這個正確機制 |
| 停用 Android 安全保護 | 同上 |
| 要求 Google Play Services | 中國版 ROM 沒有它；App 不依賴它 |
| 自動修改全域安全設定 | 那是使用者的選擇 |
| 為單一 OEM 寫死整條通知管線 | 這個 App 不是某個品牌的手機專用產品 |

---

## 7. 使用者看到的指引

`DeviceCapabilities.backgroundGuidance()` 回傳針對該品牌的設定路徑。
它是**建議**，出現在設定頁與診斷面板。

每個家族的路徑都在 `DeviceCapabilities.kt` 的 `when` 裡明確定義，
並且**每一句都說明要開哪三項**（或該品牌的實際項目）。

**新增一個品牌只需要在 `OemFamily` 加一個值、在 `backgroundGuidance()`
加一段。** 判斷全部來自 `Build.MANUFACTURER`，不是硬編碼的裝置清單。
