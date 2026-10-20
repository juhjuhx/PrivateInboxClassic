# 平台相容性矩陣（PLATFORM-COMPATIBILITY）

本文件記錄每個通訊平台的擷取相容性狀態。
**每一格都標註了驗證方式**，沒有實測的一律是 `NOT TESTED`。

驗證層級定義：

| 層級 | 意義 |
|------|------|
| `SIMULATOR_E2E` | 在 Android 16 模擬器上，用真實的 Android 通知系統走完整條管線並成功入庫 |
| `UNIT` | 純 JVM 單元測試涵蓋的分類／辨識邏輯 |
| `CODE` | 程式碼路徑存在且可編譯，但未在該平台上實測 |
| `NOT TESTED` | 沒有任何測試涵蓋 |

---

## 1. 平台 × 情境矩陣

| 平台 | 套件名稱 | 文字 | 群組 | 照片 | 通知更新 | 撤回 | 背景擷取 | 重啟復原 |
|------|----------|------|------|------|----------|------|----------|----------|
| LINE | `jp.naver.line.android` | NOT TESTED | NOT TESTED | NOT TESTED | CODE | CODE | SIMULATOR_E2E | SIMULATOR_E2E |
| Instagram | `com.instagram.android` | NOT TESTED | NOT TESTED | NOT TESTED | CODE | CODE | SIMULATOR_E2E | SIMULATOR_E2E |
| Messenger | `com.facebook.orca` | NOT TESTED | NOT TESTED | NOT TESTED | CODE | CODE | SIMULATOR_E2E | SIMULATOR_E2E |
| Threads | `com.instagram.barcelona` | UNIT | UNIT | CODE | CODE | CODE | SIMULATOR_E2E | SIMULATOR_E2E |
| WhatsApp | `com.whatsapp` | NOT TESTED | NOT TESTED | NOT TESTED | CODE | CODE | SIMULATOR_E2E | SIMULATOR_E2E |
| Telegram | `org.telegram.messenger` | NOT TESTED | NOT TESTED | NOT TESTED | CODE | CODE | SIMULATOR_E2E | SIMULATOR_E2E |
| Telegram X | `org.thunderdog.challegram` | NOT TESTED | NOT TESTED | NOT TESTED | CODE | CODE | SIMULATOR_E2E | SIMULATOR_E2E |

### 讀法（重要）

- **`SIMULATOR_E2E` 只證明「Android 通知系統 → 監聽器 → 解析 → 加密 → 入庫 →
  UI → 重啟後仍在」這條管線是通的**，證明對象是「Android 通知系統」，
  **不是**某個通訊 App 的實際推播格式。
- 每一個平台的實際推播格式（MessagingStyle 參數、Category、Shortcut id、
  影像 URI 授權行為）**都仍需實機驗證**。
- 使用者先前成功擷取過一則真實 Instagram 訊息 — 該成功案例對應的程式碼路徑
  是 `PrivateNotificationListener.capture()`，至今**未被移除或改動語意**，
  保留作為回歸參考。

---

## 2. 已在模擬器上實測的部分

Android 16 模擬器（`system-images;android-36;google_apis;arm64-v8a`）：

| 項目 | 結果 |
|------|------|
| 系統實際綁定通知監聽服務 | **PASS**（`dumpsys notification` 顯示 `INotificationListener$Stub$Proxy`） |
| `onListenerConnected()` 真的被呼叫 | **PASS**（診斷記錄 `LISTENER_CONNECTED`） |
| 通知由 Android 系統投遞到監聽器 | **PASS**（`NOTIFICATION_RECEIVED` 計數增加） |
| 非白名單來源被拒絕 | **PASS**（`SOURCE_NOT_ALLOWED`） |
| 解析成功 | **PASS**（`PARSE_SUCCESS`） |
| 加密入庫 | **PASS**（`DB_INSERT_SUCCESS`，密文 ≠ 明文） |
| UI 可讀取 | **PASS**（`captureViaOneHandleVisibleToFreshUiRead`） |
| 重啟後仍可讀 | **PASS**（`encryptedRowSurvivesDatabaseReopen`） |
| 重複通知去重 | **PASS**（`duplicateNotificationIsNotStoredTwice`） |
| 群組摘要被跳過 | **PASS**（`groupSummaryNotificationIsIgnored`） |
| 空通知不崩潰 | **PASS**（`emptyNotificationDoesNotCrash`） |
| 畸形偏好設定不崩潰 | **PASS**（`malformedSourcePreferenceDoesNotCrashTheListener`） |

---

## 3. Threads 專屬說明

Threads（`com.instagram.barcelona`）是社交網路，**私訊與社交動態走同一個
通知通道**。因此本專案：

- 依通知是否帶有訊息容器（`EXTRA_MESSAGES` / `EXTRA_TEXT_LINES`）區分：
  - 有 → `DIRECT_MESSAGE`（私訊）
  - 沒有 → `SOCIAL_ACTIVITY`（貼文按讚、追蹤、推薦）
- **社交動態不會混進預設的私訊收件匣**。
- 判斷不出來時標記 `UNKNOWN`，**不假裝是私訊**。

分類邏輯由 `SourceRegistryTest` 的 20 項單元測試涵蓋（`UNIT` 層級）。

**實機驗證狀態：`THREADS_E2E = NOT TESTED`**。Threads 尚未在實體裝置上
推播過真實私訊與社交動態通知，無法確認其實際的 Category、Shortcut metadata
與影像 URI 行為。需依 `docs/REAL-DEVICE-TEST-PLAN.md` 補驗。

---

## 4. Android 版本相容性

| 版本 | API | 狀態 | 備註 |
|------|-----|------|------|
| Android 9 | 28 | CODE | `MessagingStyle` 只能手動解析 Bundle 欄位（平台內部欄位名，無保證穩定） |
| Android 10 | 29 | CODE | 同上 |
| Android 11 | 30 | CODE | 起用 `getMessagesFromBundleArray()` 官方 API |
| Android 12 | 31 | CODE | 支援 `EXTRA_PICTURE_ICON`（`Icon`） |
| Android 13 | 33 | CODE | 通知存取權限需使用者明確授權；`POST_NOTIFICATIONS` 僅用於自我測試通知 |
| Android 14 | 34 | CODE | edge-to-edge 強制；已處理系統列 inset |
| Android 15 | 35 | CODE | edge-to-edge 強制；本 App 未使用前景服務 |
| Android 16 | 36 | **SIMULATOR_E2E** | 完整管線已實測 |

---

## 5. OEM 影響

| 廠商 | 影響 | 可控性 |
|------|------|--------|
| 小米 / MIUI / HyperOS | 背景服務可能被回收，導致監聽中斷 | 需開啟「允許後台活動 / 允許自啟動 / 不要自動管理」 |
| OneUI | 類似 | 需將本 App 設為「不受限制」 |
| 原生 Pixel / AOSP | 行為最接近平台規範 | 僅需授予通知存取權限 |

> **沒有任何設定能保證所有 OEM 版本都讓背景監聽常駐。**
> 本專案對此不提供保證。

---

## 6. 已知的平台層級限制

1. **通知存取權限是全域的**，不是逐 App 授權。本專案以程式內白名單限制
   實際擷取範圍，但那是程式自律，不是系統隔離。
2. **無法讀取通訊 App 的歷史聊天記錄**，只能看到當下投遞到通知列的內容。
3. **影像 URI 授權是短暫的**，本專案已在監聽器回呼內立即讀取位元組以
   最大化成功率，但仍不能保證取得。
4. **同名聯絡人**在沒有 conversation shortcut id 時無法區分。
5. **通知內容可能被來源 App 隱藏**（例如鎖屏時設為「隱藏敏感內容」），
   此時擷取到的文字可能不完整。
