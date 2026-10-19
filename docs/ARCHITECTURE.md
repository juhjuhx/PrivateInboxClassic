# Private Inbox Classic — 架構說明

## 1. 專案定位

Private Inbox Classic 是一個**完全離線**、個人使用的 Android 通知訊息保存工具。
它只做一件事：把 Android 系統交給 `NotificationListenerService` 的通知內容，
在本機加密保存成副本。

它**不是**聊天客戶端、**不是**訊息轉發工具、**不是**聊天記錄備份工具。
它無法讀取 LINE / Instagram / WhatsApp 的私有資料庫，也不需要那些帳號密碼。

## 2. 技術棧

| 項目 | 選擇 | 理由 |
|------|------|------|
| 語言 | Kotlin 2.2.20 | 與 Android 生態一致 |
| 建置 | AGP 8.13.2 / Gradle 8.13 / JDK 17 | 已驗證可建置 |
| UI | 原生 Android View（`ComponentActivity` + `LinearLayout`） | 零 UI 依賴，可精準重現 2014–2016 Material One 視覺；避免 Compose 帶來的額外依賴與體積 |
| 資料庫 | `SQLiteOpenHelper`（自訂 `InboxDatabase`） | 只需單表 + BLOB，不需要 Room 的抽象成本 |
| 加密 | Android Keystore AES-256-GCM（`CryptoStore`） | 金鑰不離開 TEE/Keystore，程式碼內無明文金鑰 |
| 背景工作 | `Executors`（`io` 執行緒池） | 避免引入 Coroutines 依賴；DB 與影像解碼皆離開主執行緒 |
| 依賴 | 僅 `androidx.activity:activity:1.9.2` + `junit:junit:4.13.2`（測試） | 唯一 runtime 依賴是 predictive-back 相容的 Activity 庫 |

**刻意不引入**：Firebase、Facebook SDK、Google Analytics、廣告 SDK、任何遙測、
SQLite 擴充、任何網路函式庫。

## 3. 模組結構

```
app/src/main/java/tw/personal/privateinbox/
├── MainActivity.kt            全部 UI：鎖定畫面、聊天列表、聊天室、照片檢視器、搜尋、設定
├── PrivateNotificationListener.kt   通知監聽服務（擷取入口）
├── CapturePolicy.kt           純 Kotlin：來源白名單、文字正規化（無 Android 相依）
├── CryptoStore.kt             Android Keystore AES-256-GCM 封裝 + HMAC 指紋
├── ImageCapture.kt            影像擷取：讀取 bytes / 驗證 MIME / 降級縮圖 / JPEG 壓縮
├── InboxDatabase.kt            SQLite schema v3、遷移、加密欄位存取
├── LocalSettings.kt            SharedPreferences：來源開關、保留期限、已讀標記
└── PhotoZoomView.kt           純框架實作的縮放照片檢視器（雙指/雙擊）
```

## 4. 資料流

### 4.1 通知擷取

```
Android 系統
  │  (使用者已授權通知存取)
  ▼
PrivateNotificationListener.onNotificationPosted()
  ├─ 檢查 isRecording 開關
  ├─ CapturePolicy.allowed(pkg, selected)  ← 双重把關：白名單 + 使用者勾選
  ├─ 跳過 FLAG_GROUP_SUMMARY
  ├─ 解析 MessagingStyle / TEXT_LINES / BIG_TEXT → List<Preview>
  │
  ├─ 【回呼內，唯一必須即時的部分】
  │    讀取 content:// 影像 URI 的原始 bytes
  │    （provider 可能於回呼結束後撤銷授權）
  │
  └─ worker.execute {  ← 背景執行緒
       ├─ ImageCapture.toPreview() 解碼 + 降級至 1280px + JPEG 壓縮
       ├─ InboxDatabase.save() → HMAC 指紋去重 → AES-GCM 加密寫入
       └─ 失敗一律吞掉，不寫入 Logcat
     }
```

### 4.2 讀取與顯示

```
MainActivity.loadEntries()          ← io 執行緒
  ├─ InboxDatabase.recent(1500)     ← 查詢 + 逐筆解密
  ├─ LocalSettings.retentionDays() → pruneOlderThanDays()
  └─ runOnUiThread { 重繪聊天列表 }
```

聊天室縮圖與全螢幕照片同樣在 `io` 執行緒解密 + 解碼後才回主執行緒設定
`ImageView` / `PhotoZoomView`。

## 5. 資料模型

`messages` 單表（schema v3）：

| 欄位 | 型別 | 加密 | 說明 |
|------|------|------|------|
| `id` | INTEGER PK | 否 | 自增主鍵 |
| `package_name` | TEXT | 否 | 來源 App 套件名（metadata 明文） |
| `received_at` | INTEGER | 否 | 本機擷取時間 |
| `source_ts` | INTEGER | 否 | 原始訊息時間；系統未提供時為 0（**不捏造**） |
| `fingerprint` | BLOB | 否 | HMAC-SHA256 去重鍵，UNIQUE |
| `sealed_content` | BLOB | **是** | `{title, sender, body, sourceTs}` JSON 的 AES-GCM 密文 |
| `sealed_image` | BLOB | **是** | JPEG 預覽的 AES-GCM 密文（可為 NULL） |

索引：`received_idx ON messages(received_at DESC)`

### 去重策略

`fingerprint = HMAC-SHA256(pkg \0 dedupTs \0 title \0 sender \0 body)`

- `dedupTs` = 原始時間（若 >0），否則退回擷取時間。
- 資料庫以 `CONFLICT_IGNORE` + UNIQUE 指紋阻擋重複寫入。
- 同一則通知的後續更新若帶入照片，會以 `UPDATE ... WHERE fingerprint=? AND sealed_image IS NULL` 補上影像，
  **不會**產生第二筆訊息。
- 不同訊息（不同文字/時間）指紋不同，因此不會被誤合併。

## 6. 加密設計

| 用途 | 金鑰 | 演算法 | 說明 |
|------|------|--------|------|
| 訊息內容 / 照片 | `private-inbox-aes-v1` | AES-256-GCM | 每筆獨立 IV（12 bytes）前置於密文；AAD 綁定套件名稱 |
| 去重指紋 | `private-inbox-hmac-v1` | HMAC-SHA256 | 以 HMAC 而非裸 SHA-256，避免低熵訊息被字典攻擊還原 |

- 金鑰由 `AndroidKeyStore` 產生並保管，**從不寫入程式碼、SharedPreferences 或磁碟**。
- 金鑰不使用 `setUserAuthenticationRequired(true)`：App 必須能在背景持續擷取通知，
  驗證改由 UI 進入時的裝置身分驗證把關。
- GCM 驗證失敗 → 解密拋出例外 → 呼叫端以 `runCatching` 吞掉，**單筆損毀不會 Crash**。
- `PRAGMA secure_delete=ON`：刪除列時覆寫頁面內容，減少殘留。

### 未加密的 metadata（明確聲明）

`package_name`、`received_at`、`source_ts` 為明文中繼資料。
`fingerprint` 為 HMAC，不可逆推原文。
**資料庫並非整體 SQLCipher 加密**——這是本專案的已知限制，見 `SECURITY.md`。

## 7. UI 架構

單一 `ComponentActivity`，以 `FrameLayout` 容器切換頁面（`page` 欄位記錄當前頁）。

```
"locked"    → showLock()      裝置身分驗證閘門
"inbox"     → showInbox()     聊天列表
"chat"      → showChat()      聊天室
"photo"     → showPhoto()     照片檢視器
"search"    → showSearch()    搜尋
"settings"  → showSettings()  來源與隱私設定
```

- 返回導航：`OnBackPressedCallback.handleOnBackPressed()`（AndroidX predictive-back 相容）。
- App Lock：`onStop()` 立即 `unlocked = false` 並回到鎖定畫面；背景時記憶體中的解密資料清空。
- `FLAG_SECURE` 貫穿整個 Activity：阻止截圖與錄影。

## 8. 執行緒模型

| 位置 | 執行緒 | 說明 |
|------|--------|------|
| `onNotificationPosted` 讀取 URI bytes | 主執行緒（listener callback） | **必要**：provider 授權可能在回呼結束後撤銷 |
| 影像解碼 / 縮圖 / JPEG 壓縮 | `worker` 單執行緒 | CPU 密集，已移出 callback |
| DB 寫入 | `worker` 單執行緒 | 序列化寫入，避免競爭 |
| DB 讀取 + 解密 | `io` 固定 2 執行緒 | 聊天列表、聊天室、照片檢視器共用 |
| UI 更新 | `runOnUiThread` | 檢查 `isFinishing / isDestroyed` |

## 9. 相容性

- `minSdk = 28`（Android 9）、`targetSdk = 36`、`compileSdk = 36`
- API 分支：API 30+ 用 `Notification.MessagingStyle.Message.getMessagesFromBundleArray()`；
  API 28–29 退回解析 Bundle 欄位。
- API 31+ 支援 `EXTRA_PICTURE_ICON`（`Icon`）；API 33+ 用型別安全的 `getParcelable(key, Class)`。

## 10. 已知架構限制

1. 資料庫非整體加密（見上）。
2. 搜尋是「解密後在記憶體比對」，僅涵蓋最近 1500 筆。
3. 沒有匯出 / 備份 / 遷移功能（刻意：避免敏感資料外流）。
4. 聊天列表一次載入 1500 筆並全數解密，極大量資料時會有記憶體壓力。
5. 沒有 Instrumentation 測試（見 `TEST-REPORT.md`）。
