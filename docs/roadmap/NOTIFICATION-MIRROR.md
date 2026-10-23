# 通知鏡像（NOTIFICATION-MIRROR）

> **STATUS: DESIGN_ONLY**
>
> 本輪**不實作**。這份文件是規格，不是承諾。
> 現有的擷取路徑**完全不受影響**。

---

## 1. 目的

Private Inbox 是**被動的存檔**：使用者要打開 App 才看得到已保存的訊息。
鏡像功能讓使用者在**收到新通知的當下**，由 Private Inbox 自己也發一則通知，
把使用者拉回 App 檢視該則副本。

它**不是**轉發功能。通知的內容是 Private Inbox 自己產生的摘要。

---

## 2. 設計紅線

| 紅線 | 理由 |
|------|------|
| **預設關閉** | 使用者未明確同意前，不產生任何額外通知 |
| **不得冒充來源 App** | 模仿 LINE 的通知外觀 = 冒充。使用者會誤以為是對方真的在說話 |
| **不得產生通知迴圈** | 自己的通知被系統投遞 → 監聽器收到 → 觸發保存 → 再鏡像 → 無限迴圈 |
| **不得重複通知** | 同一則訊息只鏡像一次 |
| **不得在保存失敗時鏡像** | 只在 `SaveResult.NEW` 時鏡像 |
| **不得洪水式轟炸** | 需要速率限制 |
| **鎖屏不得洩漏內容** | 預設通用預覽；完整內容必須明確 opt-in |
| **顯示詳細內容前必須通過 App Lock** | 點進通知仍要驗證 |

### 2.1 通知迴圈的具體防護

現有的 `CapturePolicy.allowed()` 使用**雙重把關**：

1. 套件名必須在程式內建清單中（`SourceRegistry`）。
2. 使用者必須已勾選。

`tw.personal.privateinbox` **不在**任何真實來源清單中，
但自我測試來源是例外 —— 見 §2.2。

**第二道防護是必要的**，因為自我測試把自身套件名加入了白名單：

1. 鏡像通知使用**獨立的 notification id 區間**。
2. 鏡像前先檢查 `sbn.packageName != BuildConfig.APPLICATION_ID`。
3. 監聽器收到自身套件的通知時直接忽略。

### 2.2 與自我測試來源的衝突

這是實作時**必須先解決**的問題：

自我測試來源 `tw.personal.privateinbox` 與 `tw.personal.privateinbox.test`
都在白名單中，且**刻意**在 `SourceRegistry` 裡標記為 `isSelfTest = true`。

鏡像通知的來源套件名就是 `tw.personal.privateinbox`，
所以**它會被擷取路徑當成自我測試通知收錄**。

**解法**：鏡像通知使用 `SelfTestNotifier` 同一個 notification id 區間，
並且在 `CapturePolicy` 增加一個明確的判斷：
`isSelfTest` 來源**只接受**帶有自我測試專用標記的通知。
標記可以放在 `Notification.extras` 的自訂 key 裡
（例如 `tw.personal.privateinbox.SELF_TEST` = true）。

這個標記**必須**由 App 自己在鏡像時寫入，並且在擷取時檢查。
沒有它，鏡像通知會造成無限迴圈。

---

## 3. 使用者設定

```
設定 → 通知鏡像

  [ ] 產生鏡像通知                    預設關閉

  顯示內容
  ( ) 通用預覽（推薦）                鎖屏只顯示「Private Inbox：1 則新訊息」
  ( ) 完整預覽                        顯示寄件人與摘要，需要明確 opt-in

  [ ] 靜音通知                        使用無聲 channel
  [ ] 點擊後開啟對話                  直接跳到該則訊息所在的對話
  [ ] 顯示詳細內容前要求解鎖          沿用 App Lock

  速率限制：每分鐘最多 [3] 則
```

**不提供沒有實際作用的開關。** 每一個都必須對應可觀察的行為差異。

---

## 4. 通知規格

| 項目 | 規格 |
|------|------|
| Channel | 獨立的 `notification_mirror` channel，`IMPORTANCE_DEFAULT` |
| 標題 | **永遠是** `Private Inbox` |
| 內容（通用預覽） | `1 則新訊息` / `3 則新訊息` |
| 內容（完整預覽） | `Alice: 晚上一起吃飯嗎？` |
| 小圖示 | App 自己的圖示 |
| PendingIntent | 開啟 MainActivity 的對應對話 |
| 自動取消 | 是 |
| 鎖屏可見性 | 預設 `VISIBILITY_PRIVATE` |
| Notification id | 獨立的區間，與自我測試不重疊 |

### 為什麼不顯示來源 App 的名稱

顯示「LINE: Alice」看起來**就是** LINE 自己的通知。
使用者在鎖屏上會誤以為是 LINE 通知，實際上這是 Private Inbox 的存檔提醒。

正確的做法是顯示「Private Inbox」，讓使用者知道這是存檔提醒。

---

## 5. 實作位置

| 階段 | 位置 | 說明 |
|------|------|------|
| 觸發 | `PrivateNotificationListener.capture()` 的 worker 迴圈內 | 只有 `SaveResult.NEW` 才觸發 |
| 產生 | 新的 `MirrorNotifier` 物件 | 與 `SelfTestNotifier` 分離，但共用 channel 建立邏輯 |
| 設定 | `LocalSettings` 新增一組鍵 | 全部預設為關閉 |
| UI | 設定頁新增一個區段 | 與既有開關風格一致 |
| 去重 | 同一則 `messageId` 只鏡像一次 | 需要一個已鏡像的識別碼集合 |

### 資料流

```
notification posted
  → capture() → worker
    → db.save() → SaveResult.NEW
      → 檢查鏡像開關
        → 檢查是否已鏡像
          → 檢查速率限制
            → MirrorNotifier.post()
```

**每一個環節都必須失敗安全**：鏡像的任何錯誤都不能影響保存流程。
`MirrorNotifier.post()` 必須被 `runCatching` 包住。

---

## 6. 需要實作的測試

| 測試 | 驗證內容 |
|------|----------|
| 鏡像預設關閉 | 安裝後不產生任何鏡像通知 |
| 開啟後只對 NEW 鏡像 | `DUPLICATE` 與 `FAILURE` 不產生通知 |
| **自身通知不被保存** | 鏡像通知回到監聽器時被忽略（**無迴圈**）|
| 速率限制生效 | 短時間內大量通知只產生 N 則鏡像 |
| 通用預覽不洩漏內容 | 鎖屏文字不含寄件人與內容 |
| 完整預覽需明確 opt-in | 未 opt-in 時只有通用預覽 |
| 鏡像失敗不影響保存 | 通知發送失敗時資料仍已入庫 |
| 同一訊息不重複鏡像 | 同一 `messageId` 只產生一則通知 |

---

## 7. 未來實作計畫

1. `CapturePolicy` 增加自我測試標記的判斷（**先做這個**，否則會有迴圈）。
2. 新增 `MirrorNotifier`。
3. `LocalSettings` 加入鏡像設定，預設全部關閉。
4. 在 `capture()` 的 worker 中，於 `SaveResult.NEW` 之後呼叫。
5. 設定頁新增區段。
6. 加入上列 8 項測試。
7. 觀察一個版本後，再考慮是否預設開啟「通用預覽」。

**風險評估**：主要風險是通知洪水與使用者騷擾。兩者都由速率限制與
預設關閉緩解。**不建議**在第一版就預設開啟。

---

## 8. 為什麼這輪不做

1. 這個功能的價值取決於使用者的實際需求，目前沒有明確需求。
2. 它增加了「應用程式產生通知」這條新路徑，而那條路徑正是通知迴圈的溫床。
   在核心擷取路徑穩定之前加入它，風險不划算。
3. 本輪的優先級是資安收斂、群組突發可靠性與效能。
4. §2.2 的自我測試衝突**必須先解決**，那需要改動擷取路徑的核心判斷 ——
   在本輪的範圍之外。
