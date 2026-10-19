# 影像擷取設計（IMAGE-CAPTURE）

本文件說明 Private Inbox Classic 如何取得通知中「實際可取得」的照片，
以及**明確不會**做的事。

---

## 0. 最重要的誠實聲明

> **本 App 不能還原被撤回的照片。**
>
> 它只能保存「通知裡真的附帶了、而且當下真的讀得到」的影像位元組。
> 如果 LINE 只推了文字「傳送了一張照片」而沒有附帶影像資料，
> 本 App 就只保存那句文字——**不會**用假圖片、佔位圖或任何方式偽造照片。
>
> 這不是功能缺失，這是 Android 通知系統的資料邊界。

---

## 1. 影像來源

依 API level 與通知樣式，檢查以下來源：

| 來源 | 常數 | 取得方式 | 最低 API |
|------|------|----------|----------|
| BigPictureStyle 圖 | `Notification.EXTRA_PICTURE` | `Bitmap` | 全版本 |
| BigPictureStyle 圖示 | `Notification.EXTRA_PICTURE_ICON` | `Icon` → `Drawable` → `Bitmap` | 31 |
| MessagingStyle 訊息附件 | `MessagingStyle.Message.getDataUri()` | `content://` URI | 30（正式 API） |
| 訊息附件 MIME | `MessagingStyle.Message.getDataMimeType()` | 字串 | 30 |

API 33+ 使用型別安全的 `extras.getParcelable(key, Class)`；
較舊版本使用已棄用的單參數版本（以 `@Suppress("DEPRECATION")` 標示）。

---

## 2. 完整擷取流程

```
Notification Posted
    ↓
Parse Notification  ──→ 找到 mediaUri / mediaMime / EXTRA_PICTURE
    ↓
Detect Image
    ↓
Resolve Accessible Image Data
    │  ★ 讀取 content:// bytes 必須在 listener callback 內完成
    │    （provider 可能於 callback 結束後撤銷授權）
    ↓
Validate MIME / Size
    │  · magic-byte 嗅探（JPEG/PNG/GIF/WebP/HEIC-ISOBMFF）
    │  · 宣告 MIME 為 image/* 時可救援未知 magic
    │  · 輸入上限 8 MiB
    ↓
Decode with Memory Limits
    │  · 先以 inJustDecodeBounds 取得尺寸
    │  · 依尺寸計算 inSampleSize，確保解碼後 ≤ 2048px
    ↓
Generate Local Preview
    │  · 等比縮放到最長邊 1280px
    │  · JPEG 壓縮：q82 → q67 → q52 → q38 逐級降質
    │  · 上限 700 KiB，超過則放棄存圖（只留文字）
    ↓
Encrypt Image
    │  · AES-256-GCM，AAD = "<packageName>:image"
    │  · 每張獨立隨機 IV（12 bytes）
    ↓
Save Metadata
    │  · 寫入 messages.sealed_image (BLOB)
    ↓
Display in Conversation
```

---

## 3. 安全措施詳解

### 3.1 只接受 `content://` URI

```kotlin
if (uri == null || uri.scheme != "content") return null
```

`file://` 會被拒絕——避免讀取其他 App 透過 `FileProvider` 暴露的檔案路徑。
`http://` / `https://` 會被拒絕——**影像絕不從網路載入**。

### 3.2 Magic-byte 嗅探（8 個單元測試覆蓋）

`ImageCapture.looksLikeImage(data, declaredMime)` 檢查：

| 格式 | 簽章 |
|------|------|
| JPEG | `FF D8 FF` |
| PNG | `89 50 4E 47` |
| GIF | `47 49 46` |
| WebP | `52 49 46 46` … `57 45 42 50`（offset 8–11） |
| HEIC/AVIF | offset 4–7 為 `ftyp` |

- 沒有可辨識簽章時，**只有**宣告 MIME 為 `image/*` 才接受
  （涵蓋部分 AVIF 變體）。
- 宣告 MIME 為非 `image/*`（例如 `text/plain`）**不會**被接受。
- 資料長度 < 4 bytes 直接拒絕。

**意義**：即使某個 content provider 回傳了 HTML 錯誤頁或任意文字，
也不會被當成圖片解碼。

### 3.3 大小限制

| 限制 | 值 | 目的 |
|------|-----|------|
| `MAX_INPUT_BYTES` | 8 MiB | 防止惡意／異常 provider 灌爆記憶體 |
| 解碼後尺寸上限 | 2048px | `inSampleSize` 計算基準 |
| `MAX_SIDE` | 1280px | 預覽最長邊（需求指定） |
| `MAX_STORED_BYTES` | 700 KiB | 單張加密後大小上限 |

### 3.4 EXIF 旋轉

讀取 `ExifInterface.TAG_ORIENTATION`，套用對應的 `Matrix` 旋轉／翻轉。
**不保留**原始 EXIF metadata（GPS、设备型号等一律丟棄）——
預覽不需要，且保留會增加洩漏面。

### 3.5 加密與儲存

- 每張圖片獨立 AES-256-GCM 加密，**每張獨立 nonce**（GCM 每次加密自動產生新 IV）。
- AAD 為 `"<packageName>:image"`，與文字欄位的 AAD（純套件名）不同，
  防止圖片密文被搬到文字欄位，或反之。
- 存於 SQLite BLOB（`sealed_image`），**不是**檔案系統上的圖片。
- 因此**不會**出現在相簿、Gallery、`MediaStore` 或任何檔案瀏覽器中。
- App 沒有 `WRITE_EXTERNAL_STORAGE`，無法在外部儲存空間寫任何東西。

### 3.6 記憶體管理

| 位置 | 措施 |
|------|------|
| 擷取 | `inSampleSize` 降級解碼；不載入完整原圖 |
| 縮圖 | 最長邊 1280px |
| 聊天室縮圖 | `decodedForDisplay(bytes, 480)` |
| 全螢幕 | `decodedForDisplay(bytes, 1600)` |
| 顯示 | 預覽只在記憶體中解密，不落地明文檔案 |

### 3.7 並發安全

- 擷取端：`worker` 是**單執行緒**佇列 → 寫入序列化，無競爭。
- 顯示端：`io` 是 2 執行緒池 → 讀取可並行；
  每次操作各自 `InboxDatabase(this)` 實例並在 `finally` 關閉，不共享連線。
- UI 更新統一走 `runOnUiThread`，並檢查 `isFinishing || isDestroyed`。

### 3.8 解密失敗不 Crash

```kotlin
val bytes = try { db.loadImage(entry.id) } catch (_: Exception) { null }
finally { db.close() }
```

- `CryptoStore.open()` 在 GCM 驗證失敗時拋出例外。
- 所有呼叫端都以 `runCatching` / `try-catch` 包住。
- 結果為 `null` → UI 顯示「無法載入這張照片（可能已失效或損毀）」。

**單張圖片損毀不影響其他圖片、不影響訊息文字、不影響 App 啟動。**

---

## 4. 顯示流程

### 4.1 聊天列表

以 `📷` 前綴標示，不解碼任何圖片。
（需求明確要求：不得在列表中一次解碼所有完整圖片。）

### 4.2 聊天室縮圖

- 固定尺寸 `230dp × 170dp`，`CENTER_CROP`。
- 背景為分隔線灰色，載入完成前顯示灰色方塊。
- 在 `io` 執行緒解密 + 解碼（`maxSide = 480`），再回主執行緒設定。
- 點擊 → 進入全螢幕檢視器。

### 4.3 全螢幕檢視器（`PhotoZoomView`）

純框架實作，**不引入任何影像或手勢函式庫**：

| 功能 | 實作 |
|------|------|
| 雙指縮放 | `ScaleGestureDetector`，比例限制 1f–5f |
| 焦點維持 | 以 `detector.focusX/Y` 計算位移，縮放時保持手指下的點不動 |
| 雙擊放大 | `GestureDetector.OnDoubleTapListener`（公開 API，非內部類） |
| 平移 | `ACTION_MOVE` 累積位移，單指且非縮放中 |
| 邊界夾制 | `clampTranslation()` 防止圖片被拖出畫面 |
| 載入狀態 | 顯示「正在載入照片…」並在載入前隱藏影像 |
| 錯誤狀態 | 「無法載入這張照片（可能已失效或損毀）」 |
| 返回 | `OnBackPressedCallback` → 回到聊天室 |

`PhotoZoomView` 亦實作 `performClick()` 與點擊判定
（符合 Android 無障礙規範，避免 lint `ClickableViewAccessibility` 警告）。

---

## 5. 刪除與同步清除

| 動作 | 行為 |
|------|------|
| 刪除單則訊息 | `db.deleteMessage(id)` 同時移除該列的 `sealed_image` BLOB |
| 清除全部資料 | `db.clear()` → `DELETE FROM messages` + `PRAGMA wal_checkpoint(TRUNCATE)` |
| 保留期限到期 | `pruneOlderThanDays(days)` 依 `received_at` 刪除過期列（含影像） |

訊息與其照片在**同一列**（同一 BLOB 欄位），
因此**不會**出現「訊息刪了但照片還在」的孤兒資料。

---

## 6. 已知限制

### 6.1 無法取得照片的情況

| 原因 | 說明 |
|------|------|
| 通知只有文字 | 極常見。LINE / Instagram 常只推「傳送了一張照片」 |
| URI 授權已撤銷 | provider 在通知撤銷或逾時後撤銷授權 |
| MIME 非 `image/*` | 例如 `video/mp4` → 不處理 |
| 影像超限 | 輸入 > 8 MiB 或壓縮後仍 > 700 KiB |
| 影像損毀 | magic-byte 檢查攔下 |
| ROM 限制 | 部分 OEM 對通知 extras 的處理不同 |

以上所有情況**都會安全降級為「只保存文字」**，不產生假資料。

### 6.2 預覽不是原檔

- 保存的是**壓縮後的 JPEG 預覽**（最長邊 1280px、≤ 700 KiB）。
- **不是**原始照片檔案，**不保留** EXIF，**不保留**動態照片/GIF 動畫、
  **不保留**原始色彩描述檔。
- 用途是「知道當時有這張圖」，不是「檢視原始照片」。

### 6.3 不讀相簿

本 App **不需要**也不會讀取裝置相簿。
沒有 `READ_MEDIA_IMAGES`、沒有 `READ_EXTERNAL_STORAGE` 權限，
`ContentResolver` 只會被用來讀取通知中 `dataUri` 指向的那一個 URI。

---

## 7. 測試覆蓋

### 7.1 單元測試（8 項，**全部 PASS**）

`app/src/test/java/tw/personal/privateinbox/ImageCaptureTest.kt`：

| 測試 | 狀態 |
|------|------|
| JPEG magic 通過 | PASS |
| PNG magic 通過 | PASS |
| WebP RIFF 容器通過 | PASS |
| HEIC ISOBMFF ftyp 通過 | PASS |
| 任意文字在無 MIME 時被拒絕 | PASS |
| 宣告 image MIME 可救援未知 magic | PASS |
| 宣告非 image MIME 不得救援 | PASS |
| 過短 payload 被拒絕 | PASS |

### 7.2 未測試

| 項目 | 狀態 |
|------|------|
| 真實通知的影像 URI 擷取 | **NOT TESTED**（無實機） |
| 損毀圖片在 UI 顯示錯誤狀態 | **NOT TESTED** |
| 失效 URI 不 Crash | **NOT TESTED** |
| 重新啟動後讀取圖片 | **NOT TESTED** |
| 超大圖片（>2048px）降級 | **NOT TESTED**（單元測試未涵蓋，需 instrumentation） |
| 圖片刪除 | **NOT TESTED** |
