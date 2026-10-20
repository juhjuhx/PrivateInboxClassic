# 實機測試指引（REAL-DEVICE-TEST-PLAN）

適用：Private Inbox Classic v0.3.1-debug。
目的：在**實體手機**上驗證 v0.3.0 未解的 P0 問題（LINE / Messenger 通知未入庫），
並確認 v0.3.1 的诊断能定位到具體階段。

> **前提誠實聲明**：模擬器上的所有 PASS 都不能替代本指引。LINE E2E 與
> Messenger E2E 在本輪 = **NOT TESTED**，只有走完全程才算驗證。

---

## 0. 簽章相容性（先讀）

本專案存在**兩份不同的 Android Debug Keystore**：

| Keystore | 位置 | 建立 | 簽过的版本 |
|----------|------|------|-----------|
| A | `~/.android/debug.keystore` | 2025-09-15 | 早期 Hermes build（v0.2） |
| B | `.android-home/debug.keystore` | 2026-10-19 | v0.3.0 / v0.3.1（DeepSeek 行） |

v0.3.1-debug.apk 用 **B** 簽章。使用者手機上若裝的是**早期 build（A 簽章）**：

- **不能直接覆蓋安裝**（同 applicationId 不同憑證 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
- 保留既有 App 資料與「直接更新」二者只能選一。

### 安全遷移方案（不丢資料）

1. 安裝前先在舊版內把重要對話備份（若已無資料可讀，此步可跳）。
2. 安裝 v0.3.1-debug：Android 會要求「先移除舊版」。移除舊版會**清除該應用的既有本機資料**
   （AES-GCM 金鑰在 KeyStore，App 被移除後舊資料本來也無法被新簽名的 App 讀取——
   不是資料洩漏，是憑證不同）。
3. 若使用者堅持保留舊資料：需先用**同一把金鑰**重新打包（即改用 A 簽章重出 APK），
   但這要求 A 的憑證仍可讀，且舊版 schema 與新版相容。本輪未做此方案——
   標記 `SIGNING_COMPATIBILITY=INCOMPATIBLE`，提供的是「接受移除舊資料、全新安裝」的路線。

---

## 1. 安裝

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 若回報 INSTALL_FAILED_UPDATE_INCOMPATIBLE → 見 §0，先移除舊版再裝
```

## 2. 授權（必要前置）

1. 開 App → 授權畫面上「授權通知存取」
   → Android 設定 → 通知存取權限 → 開啟 Private Inbox Classic。
2. 若 Android 13+：同意 POST_NOTIFICATIONS（**只**給本 App 自己發測試通知用）。
3. 設定頁：勾選 LINE、Messenger（或其分身套件名）、Instagram、WhatsApp、Telegram。
   **確認套件名**：OEM / 分身可能不同（`com.whatsapp` vs 雙開 App 的別名）。
   可在「通知監聽診斷」裡看「允許來源數量」是否如預期。

## 3. 基線檢查（開始前）

- 設定頁 → 通知監聽診斷：
  - 通知存取權限 = 已授權
  - 監聽器連線狀態 = CONNECTED（若不是，按「重新檢查連線」，再看一次）
  - 收到通知事件 = 0（或先按「清除診斷紀錄」歸零）

## 4. 發送測試通知（系統 E2E）

- 按「發送測試通知」→ 成功時 UI 會顯示 Self Test 對話（Test User / Hello Private Inbox）。
- 記下診斷：`UI_REFRESH_SUCCESS`。重開 App → 該對話仍在。
- 若失敗：看「監聽器連線狀態」與「最後錯誤代碼」——
  - `PERMISSION_DENIED`：未授權
  - `PERMISSION_GRANTED_NOT_CONNECTED`：OEM 背景限制（MIUI/HyperOS 需關「自啟動管理」+ 鎖後台）
  - `rebind_refused`：系統拒絕重新綁定，重開 App 後再試
  - `POST_NOTIFICATIONS` 未給：回設定同意

## 5. 真實 LINE 驗證

1. 從另一台裝置對實機 LINE 帳號發文字訊息（「你好 v0.3.1」）。
2. 20 秒內：LINE 對話應出現在 App 內。
3. 再發一張**含圖片**的訊息：
   - LINE 若**未**在通知中附圖片本體（常見），App 只保存「傳送了一張照片」文字，
     診斷記 `MEDIA_URI_UNAVAILABLE`——**這是正確行為，不是 bug**。
   - LINE 若附了圖片，App 顯示縮圖、診斷無 `MEDIA_DECODE_FAILED`。
4. 診斷頁應顯示：收到通知事件 +1、資料庫寫入成功 +1、無 `DB_*` 失敗。

## 6. 真實 Messenger 驗證

同 §5，改用 Messenger 帳號。

## 7. 撤回訊息場景

1. 對方發訊息 → App 存到 → 對方撤回。
2. App 內的既有副本**不應**消失（這是產品核心）。
3. 通知被取消 ≠ 刪除已存資料。

## 8. 背景存活（MIUI / HyperOS 專用）

1. 手機靜置 10 分鐘後再發一則 LINE。
2. 若漏接：診斷 `PERMISSION_GRANTED_NOT_CONNECTED` →
   到 OEM 電量管理 → Private Inbox Classic → 允許自啟動 + 無限制後台。
3. 重試 §5。

## 9. 記錄與交付

每步截「通知監聽診斷」頁 + 對話列表頁。失敗時複製「匯出匿名診斷報告」JSON
貼到交付包（無私人內容，可安全分享）。

---

## 判定表

| 結果 | 判定 |
|------|------|
| 自測通知 PASS + LINE 文字入庫 + Messenger 入庫 | **v0.3.1 真機 P0 修復完成** |
| 自測 PASS 但 LINE/Messenger 漏 | 定位到具體代碼（通常 `SOURCE_NOT_ALLOWED` 或 OEM 背景限制）|
| 自測也失敗 | 監聽器層問題（權限 / 背景限制 / rebind）|
| LINE 圖片未保存但文字在 | 符合預期（LINE 未在通知附圖片本體），不是 bug |
