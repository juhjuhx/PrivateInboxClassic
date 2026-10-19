# 安全設計、可保證的邊界與風險

## 驗證狀態

目前是 v0.2 Kotlin 原始碼候選版；沒有在 Android SDK 編譯，也沒有實機與黑箱滲透測試。不能對外宣稱已通過安全審計。

## 權限與資安模型

- Android OS 可對 NotificationListenerService 提供各類通知。程式中 `CapturePolicy.allowed`、全域 recording switch、影像擷取 switch 限制實際儲存的來源與內容。**系統權限本身不是每個 App 分開授權。**
- Manifest 無網路權限、READ_SMS、聯絡人讀取權限、外部儲存權限或 Accessibility。沒有整合第三方廣告或統計 SDK。備份關閉。
- Android Keystore AES-256-GCM 對每列通知 JSON 加密，影像 JPEG 對應不同 AAD 加密，避免把照片以明文檔案存在相簿、cache 或 MediaStore。Android Keystore HMAC 防止對常見文字的單純 hash 字典攻擊。
- SQLite 的 packageName、receivedAt、主鍵及圖像欄位是否存在仍為明文。這不等於 SQLCipher full-database encryption。
- 解密需要 app 執行中的 Keystore 存取能力；裝置解鎖驗證是 UI gate，**不是每次密鑰使用的硬體強制使用者驗證**。裝置有 Root 惡意程式、受害 process 被注入、ROM 惡意修改等情況，仍可能被讀取。
- `FLAG_SECURE` 限制一般截圖／最近使用預覽，無法防其他裝置拍照，也非 Root 防禦。
- 來源圖片如果 Android 通知從未提供，無法還原原圖。image/* Data URI 只接受 `content://`，最多讀 8 MiB，轉為長邊最多 1280px 的 JPEG，單張最多 700KiB。不存原始 EXIF。

## 殘餘風險／發版 Gate

1. 必須實機驗證 API 28、29、35、36，尤其 Android 16 UI Insets、Android 通知 listener 實際權限、Android 13+ 隱私設定、OEM 背景限制。
2. 測 LINE、IG、Telegram、WhatsApp 真正圖片 MIME/URI 存取與通知更新，補上 Android instrumentation test。不同來源未實測之前不可聲稱完整支援。
3. Android Keystore AES 在 Root、回復機制及工作設定檔的故障行為尚待測。驗證 media preview 無明文落到備份/cache。
4. SQLite 沒有 retention quota / auto-prune；大量圖片會占用私有儲存空間。清除紀錄並不保證快閃記憶體物理覆寫。
5. UI 目前一次最多載入近期 1500 項文字與最多 120 項聊天室內容；歷史更多項目仍存於資料庫但暫無分頁。
6. 多次通知更新、相同時戳同文訊息、不同群組同標題的分組去重規則仍需真機校正。
7. 正式 release 用持有人自己的 keystore 簽署，禁用 debug logging，完成 APK 依賴/權限/manifest 稽核。
