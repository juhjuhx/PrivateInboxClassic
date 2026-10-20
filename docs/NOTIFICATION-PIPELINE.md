# 通知擷取管線（NOTIFICATION-PIPELINE）

本文件說明 Private Inbox Classic 從 Android 系統到資料庫的完整擷取路徑，
以及每個階段的失敗分類。

---

## 1. 管線總覽

```
Android 系統
    │
    ▼
PrivateNotificationListener.onNotificationPosted()
    │   全程 try/catch 保護 —— 監聽器拋例外會被系統殺掉，
    │   導致「所有擷取靜默停止」，單一畸形通知不得造成這種後果。
    ▼
Source Validation  ─── 不在白名單 → SOURCE_NOT_ALLOWED，結束
    │   白名單 = 程式內建清單 ∩ 使用者勾選（雙重把關）
    ▼
Notification Classification
    │   SourceRegistry.classify() → DIRECT_MESSAGE / GROUP_MESSAGE /
    │   MENTION / REPLY / SOCIAL_ACTIVITY / UNKNOWN
    ▼
Message Parser  ─── 無內容 → EMPTY_NOTIFICATION，結束
    │   MessagingStyle → EXTRA_TEXT_LINES → EXTRA_BIG_TEXT/EXTRA_TEXT
    ▼
Normalization  ─── CapturePolicy.normalize()
    │   收合空白與換行、截斷至 20,000 字元
    ▼
Deduplication  ─── HMAC-SHA256(pkg, ts, title, sender, body)
    │   UNIQUE 欄位 + CONFLICT_IGNORE
    ▼
Encryption  ─── AES-256-GCM，每筆獨立 IV
    ▼
Database Transaction  ─── NEW / DUPLICATE / FAILURE
    ▼
UI Refresh  ─── 重新讀取 recent()，重建列表
```

每一個階段都會在 `CaptureDiagnostics` 留下事件，並以 **traceId** 串連
同一則通知的所有階段。

---

## 2. 階段代碼

| 代碼 | 階段 | 意義 |
|------|------|------|
| `LISTENER_CONNECTED` | lifecycle | 系統實際綁定了監聽服務 |
| `LISTENER_DISCONNECTED` | lifecycle | 系統解除綁定 |
| `NOTIFICATION_RECEIVED` | SYSTEM_NOTIFICATION | 事件進入管線 |
| `RECORDING_DISABLED` | RECORDING_POLICY | 記錄開關是關的（**政策決定，不是失敗**） |
| `SOURCE_NOT_ALLOWED` | SOURCE_POLICY | 來源未勾選（**政策決定，不是失敗**） |
| `GROUP_SUMMARY_SKIPPED` | NOTIFICATION_PARSER | 群組摘要通知 |
| `EMPTY_NOTIFICATION` | NOTIFICATION_PARSER | 沒有可顯示內容 |
| `PARSE_SUCCESS` | NOTIFICATION_PARSER | 解析成功 |
| `PARSE_FAILED` | SYSTEM_NOTIFICATION | 擷取路徑拋出例外（只記例外型別名） |
| `ENCRYPT_FAILED` | ENCRYPT | 加密失敗 |
| `DB_OPEN_FAILED` | DATABASE_COMMIT | 資料庫無法開啟 |
| `DB_INSERT_FAILED` | DATABASE_COMMIT | 寫入失敗 |
| `DB_DUPLICATE` | DATABASE_COMMIT | 重複通知 |
| `DB_INSERT_SUCCESS` | DATABASE_COMMIT | 寫入成功 |
| `UI_REFRESH_SUCCESS` | UI_REFRESH | 列表已重建 |
| `UI_REFRESH_FAILED` | UI_REFRESH | 寫入成功但列表沒更新 |
| `MEDIA_URI_UNAVAILABLE` | MEDIA | 影像 URI 讀不到 |
| `MEDIA_DECODE_FAILED` | MEDIA | 影像解碼失敗（**不影響文字入庫**） |

> **「失敗事件」計數器只統計真正的失敗。**
> `RECORDING_DISABLED` 與 `SOURCE_NOT_ALLOWED` 是使用者的政策決定，
> 不是錯誤，因此不計入失敗。

---

## 3. 監聽器連線狀態

`CaptureDiagnostics.resolveStatus()` 只依真實證據回傳：

| 狀態 | 條件 |
|------|------|
| `PERMISSION_DENIED` | 系統的 enabled-listener 清單不含本 App |
| `PERMISSION_GRANTED_NOT_CONNECTED` | 已授權，但本行程沒有 `onListenerConnected()` 證據 |
| `CONNECTED` | **本行程真的收到** `onListenerConnected()` |
| `DISCONNECTED` | 收到 `onListenerDisconnected()` 或 rebind 被拒 |
| `UNKNOWN` | 兩者都無法判斷 |

**權限已授予 ≠ 監聽器已連線。** UI 不會拿授權狀態假裝成已連線。

`requestRebind()` 成功**也不會**被記成 CONNECTED — 真正的連線證據
永遠只來自服務自己的 `onListenerConnected()` 回呼。

---

## 4. 解析順序

`PrivateNotificationListener.previews()` 依序嘗試：

1. **`EXTRA_MESSAGES`**（MessagingStyle）
   - API 30+：`Notification.MessagingStyle.Message.getMessagesFromBundleArray()`
   - API 28–29：手動解析 Bundle 欄位（平台內部欄位名，無穩定性保證）
2. **`EXTRA_TEXT_LINES`**（InboxStyle），最多取最後 15 行
3. **`EXTRA_BIG_TEXT` / `EXTRA_TEXT`**（BigTextStyle 或一般通知）

### 時間語意

- `Message.timestamp` 是**原始訊息時間**。
- 系統未提供時存 `0`，**絕不捏造**。UI 會標註「（擷取）」。

---

## 5. 去重

```
fingerprint = HMAC-SHA256(pkg \0 dedupTs \0 title \0 sender \0 body)
```

- `dedupTs` = 原始時間（>0 時），否則退回擷取時間。
- 用 **HMAC** 而非裸 SHA-256：避免低熵的簡短訊息被字典攻擊還原原文。
- 資料庫以 `UNIQUE` 欄位 + `CONFLICT_IGNORE` 硬性阻擋重複。
- 同一則通知的後續更新若帶入照片，會以
  `UPDATE ... WHERE fingerprint=? AND sealed_image IS NULL` 補上影像，
  **不產生第二列**。

| 情境 | 結果 |
|------|------|
| 相同通知 ID 被重用於**不同**訊息 | 文字/時間不同 → 指紋不同 → 正確保存為兩則 |
| 多則訊息合併成一個通知 | `EXTRA_MESSAGES` 逐則展開 |
| 群組訊息 | `senderPerson.name` 記錄發話者 |
| 來源 App 重複推播 | 指紋相同 → 去重 |

---

## 6. 通知取消

**不實作 `onNotificationRemoved` 的刪除邏輯。**

使用者的核心需求是「訊息被撤回後還能看」。若監聽通知取消並刪除對應資料，
恰好會摧毀這個功能。

已保存的副本一律依使用者的保留期限政策管理。

---

## 7. 影像處理

影像處理是唯一**必須在通知回呼內完成**的部分（provider 可能隨時撤銷授權），
但解碼不在回呼內：

```
回呼內：  readBytes(uri)     ← 讀原始 bytes（可能被撤銷授權）
回呼外：  toPreview(bytes)   ← 驗證 magic byte、降級縮圖、JPEG 壓縮
```

- 讀取有 2 秒逾時上限，慢的 provider 不會卡住回呼。
- 照片開關關閉時，**完全不讀取任何影像位元組**。
- 影像失敗**永遠不影響文字入庫**。

---

## 8. 診斷資料的隱私

診斷**不記錄**：訊息正文、圖片位元組、圖片 URI、完整 extras、
聯絡人資料、金鑰、資料庫明文。

只記錄：時間戳、traceId、來源套件名、階段代碼、錯誤型別名、耗時。

儲存於 App 私有 SharedPreferences（持久化 100 筆，保留 7 天）
與記憶體 ring buffer（500 筆）。

匯出的 JSON 以 `JSONObject`/`JSONArray` 建構（每個值都跳脫），
回傳前**重新解析驗證**，並帶 `schema_version`。
