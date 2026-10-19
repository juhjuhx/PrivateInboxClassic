# 通知擷取設計（NOTIFICATION-CAPTURE）

本文件說明 Private Inbox Classic 如何從 Android 系統取得通知內容、
如何解析、如何去重，以及它**做不到**什麼。

---

## 1. 權限模型

### 1.1 需要什麼

| 項目 | 需求 | 說明 |
|------|------|------|
| 通知存取權限 | `BIND_NOTIFICATION_LISTENER_SERVICE` | 系統在 `AndroidManifest.xml` 綁定到 `PrivateNotificationListener` |
| 使用者授權 | 必須 | Android 沒有「自動授予」路徑，使用者必須在系統設定中手動開啟 |
| 特殊權限 | **不需要** | 不需要 `INTERNET`、不需要 Accessibility、不需要 Root、不需要帳號密碼 |

### 1.2 授權流程

```
App 設定頁 → 「前往 Android 通知存取授權」
  → Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
  → 系統「通知存取權限」列表
  → 使用者只勾選「Private Inbox Classic」
  → 系統立即綁定服務
```

Android 13+ 會在授權後顯示一次性通知，告知使用者「此應用程式可讀取所有通知」。

### 1.3 重要限制（必須向使用者說明）

**`BIND_NOTIFICATION_LISTENER_SERVICE` 是全域廣泛權限，不是逐 App 授權。**
一旦授予，本 App 理論上能看到所有 App 的通知。

本專案的緩解措施是**程式內白名單 + 使用者勾選**的雙重把關
（見 §5）。但這是**程式自律**，不是系統層級的隔離——
有能力修改 APK 的人可以繞過。誠實的說法是：
「白名單限制了正常使用情境下的擷取範圍。」

---

## 2. 擷取入口

`PrivateNotificationListener`（`NotificationListenerService` 子類）：

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification?) {
    if (sbn == null || !LocalSettings.isRecording(this)) return       // ① 總開關
    val pkg = sbn.packageName
    if (!CapturePolicy.allowed(pkg, LocalSettings.selected(this))) return  // ② 白名單 + 勾選
    if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return  // ③ 略過群組摘要
    val extras = sbn.notification.extras ?: return
    ...
}
```

### 為什麼跳過 `FLAG_GROUP_SUMMARY`

群組摘要通知（`FLAG_GROUP_SUMMARY`）是「3 個新訊息」這種彙總項目，
其內容不是真實訊息。若不跳過，會把摘要當成一則假訊息保存。

### 為什麼不處理 `onNotificationRemoved`

**通知被取消 ≠ 資料可以刪除。**

使用者的核心需求是「訊息被撤回後還能看」。若監聽 `onNotificationRemoved`
並刪除對應資料，恰好會摧毀這個功能。

因此：**本 App 完全不實作 `onNotificationRemoved` 的刪除邏輯。**
已保存的副本一律依使用者的保留期限政策（`LocalSettings.retentionDays`）管理。

---

## 3. 通知解析

依序嘗試三種來源，第一個有結果就使用：

### 3.1 `Notification.EXTRA_MESSAGES`（MessagingStyle）

**API 30+**（Android 11+）使用官方解析：

```kotlin
Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
    .map { m -> Preview(
        text = m.text?.toString(),
        sender = m.senderPerson?.name?.toString(),
        time = m.timestamp,          // 原始訊息時間
        mediaUri = m.dataUri,        // 影像 URI
        mediaMime = m.dataMimeType   // 影像 MIME
    ) }
```

**API 28–29**（Android 9/10）退回直接讀 Bundle 欄位
（`text` / `sender` / `time` / `uri` / `type`）。
這些是平台內部欄位名，Android 官方未保證穩定；
若將來平台改版，這條路徑可能失效——已加註說明。

**時間語意**：`m.timestamp` 是**原始訊息時間**。
若系統未提供（0），**存 0，不捏造**。UI 會在該則訊息標註「（擷取）」。

### 3.2 `Notification.EXTRA_TEXT_LINES`（InboxStyle / 收件匣樣式）

多行文字，各自成為一則預覽，最多取最後 15 行。

### 3.3 `Notification.EXTRA_BIG_TEXT` / `EXTRA_TEXT`（BigTextStyle / 一般通知）

單行文字。**即使文字為空也要回傳一筆空的 Preview**——
因為 BigPictureStyle 可能有圖但沒文字，那筆仍然值得保存。

---

## 4. 訊息類型與附件

`Preview` 資料結構承載：

| 欄位 | 來源 | 用途 |
|------|------|------|
| `body` | `Message.text` / `EXTRA_TEXT_LINES` | 訊息內文 |
| `sender` | `Message.senderPerson.name` | 群組中的發話者名稱 |
| `time` | `Message.timestamp` | 原始訊息時間（0 = 系統未提供） |
| `mediaUri` | `Message.dataUri` | 影像 URI |
| `mediaMime` | `Message.dataMimeType` | 影像 MIME 型別 |

---

## 5. 來源白名單（雙重把關）

`CapturePolicy.kt`：

```kotlin
val knownApps = listOf(
    "LINE" to "jp.naver.line.android",
    "Instagram" to "com.instagram.android",
    "WhatsApp" to "com.whatsapp",
    "Messenger" to "com.facebook.orca",
    "Telegram" to "org.telegram.messenger",
    "Telegram X" to "org.thunderdog.challegram",
    "Teams" to "com.microsoft.teams",
    "KakaoTalk" to "com.kakao.talk"
)

fun allowed(packageName: String, selected: Set<String>): Boolean =
    knownApps.any { it.second == packageName } && packageName in selected
```

**兩個條件都必須成立**：
1. 套件名在程式內建白名單中（防止被繞過選項去擷取任意 App）；
2. 使用者在設定頁主動勾選（預設全部關閉）。

`LocalSettings.updateSources()` 再次做交集過濾，
確保 `SharedPreferences` 裡不可能出現白名單外的套件名。

---

## 6. 去重策略

### 6.1 指紋

```kotlin
fingerprint = HMAC-SHA256(pkg \0 dedupTs \0 title \0 sender \0 body)
```

- `dedupTs` = 原始時間（>0 時），否則退回擷取時間。
- 用 **HMAC** 而非裸 SHA-256：避免低熵的簡短訊息被字典攻擊還原原文。
- 資料庫欄位 `fingerprint BLOB NOT NULL UNIQUE` 硬性保證唯一。

### 6.2 寫入流程

```kotlin
val inserted = db.insertWithOnConflict("messages", null, cv, CONFLICT_IGNORE)
if (inserted == -1L && protectedImage != null) {
    // 同一則通知的後續更新帶入照片 → 補上影像，不新增一列
    db.execSQL("UPDATE messages SET sealed_image=? WHERE fingerprint=? AND sealed_image IS NULL",
        arrayOf(protectedImage, fingerprint))
}
```

**這解決了兩個核心情境**：

1. **通知更新**（例如同一則訊息從「文字」變成「文字+照片」）：
   不會產生第二筆，照片補進原列。✅
2. **重複推播**（App 重新發出相同通知、相同通知 ID 重用）：
   指紋相同 → `CONFLICT_IGNORE` 攔下。✅

### 6.3 邊界情況

| 情境 | 結果 |
|------|------|
| 相同通知 ID 被 App 重用於**不同**訊息 | 文字/時間不同 → 指紋不同 → 正確保存為兩則 ✅ |
| 多則訊息合併成一個通知 | `EXTRA_MESSAGES` 逐則展開，各自保存 ✅ |
| 群組訊息 | `senderPerson.name` 記錄發話者 ✅ |
| 同名聯絡人 | 通知 API 無法區分 → **刻意合併**（無法解決，見 §8） |
| 來源 App 重新發出通知 | 指紋相同 → 去重 ✅ |

### 6.4 不會錯誤合併的保證

去重鍵包含 `title`、`sender`、`body`、`dedupTs` 四項。
只有四項**全部相同**才會被視為重複。
不同訊息幾乎不可能同時滿足這四個條件。

唯一的理論風險：兩則**完全相同**的文字在同一毫秒送出、同一標題、同一寄件人。
這種情況下合併為一則是合理結果。

---

## 7. 執行緒模型

```
onNotificationPosted (主執行緒 / listener callback)
  ├─ 檢查開關、白名單、群組摘要
  ├─ 解析 extras → List<Preview>          （純記憶體操作，快）
  ├─ 讀取 content:// 影像 URI 的 bytes    （必須在此時，見下）
  └─ worker.execute {                     （背景單執行緒）
       ├─ ImageCapture.toPreview()        （解碼 + 縮圖 + JPEG 壓縮，CPU 密集）
       ├─ InboxDatabase.save()             （序列化寫入）
       └─ 失敗靜默吞掉，不寫 Logcat
     }
```

### 為什麼讀 bytes 必須在 callback 內

`content://` URI 的讀取授權是**暫時性**的。許多通訊 App 在通知被撤銷或
一段時間後就會撤銷該授權。在 callback 內立即讀取是唯一能最大化
成功率的時機。

**但**解碼與縮圖是 CPU 密集操作，若留在 callback 會阻塞系統通知處理。
因此拆分為：`callback 內讀 bytes` → `背景執行緒解碼`。

這是本專案在 v0.2 審計後修正的實際效能缺陷。

---

## 8. 已知限制

### 8.1 看不到的東西

- 通訊 App 的**歷史**聊天記錄（無論是否撤回）。
- 在推播前就已被讀取的訊息。
- 從未被推播為通知的訊息（部分 App 在靜音時不發通知）。
- 使用者自行關閉該 App 通知權限後的所有內容。

### 8.2 同名聯絡人

Android 通知 API 只提供 `Conversation` 標題（對使用者顯示的名稱），
不提供穩定的聯絡人 ID。因此**同一個 App 內兩個同名聯絡人無法區分**，
本專案刻意將它們合併為一則對話。這是 API 層級的限制，非實作缺陷。

不同 App 之間**不會**合併（`package_name` 參與去重鍵）。

### 8.3 「已讀」的語意

本 App **不會**開啟原始聊天室，**不會**發送任何請求。
因此**不會**觸發 LINE / Telegram 的已讀回報。

但要注意：**使用者在原始通訊 App 裡打開對話本身，仍會標記已讀。**
本 App 對此完全無能為力，也不嘗試介入。

### 8.4 原始時間不可得時

若通知未提供 `Message.timestamp`，存 0，UI 顯示擷取時間並標註「（擷取）」。
**絕不捏造原始時間。**

---

## 9. 測試覆蓋

| 項目 | 狀態 |
|------|------|
| `CapturePolicy.allowed` 白名單邏輯（4 種組合） | **PASS**（單元測試） |
| `CapturePolicy.normalize` 空白/超長/換行處理 | **PASS**（單元測試） |
| 未知套件名拒絕 | **PASS**（單元測試） |
| 實際通知擷取（真實 App 推播） | **NOT TESTED**（無實機） |
| MessagingStyle 群組發話者 | **NOT TESTED** |
| 通知更新補照片流程 | **NOT TESTED**（需實機或 instrumentation） |
