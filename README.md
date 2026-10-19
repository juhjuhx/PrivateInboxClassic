# Private Inbox Classic — Android

**版本：0.2.0-source-candidate（未編譯、未做真機驗收）**

古早 Telegram Android 2.0 / Material Design 1.0 視覺語言的純離線通知收件匣。獨立實作，參考 QuietInbox 的產品理念，**目前不是 QuietInbox 的 fork，也未複製其 GPL 原始碼**。本專案與 Telegram / LINE / QuietInbox 均無關聯。

## 功能

- 傳統藍灰色頂欄、白底圓形頭像列表、訊息分組、搜尋、聊天室泡泡與時間戳、單張照片大圖檢視。
- Android `NotificationListenerService` 擷取明確勾選來源的新通知；支援 `MessagingStyle` 與一般通知。
- 圖片：`MessagingStyle.Message.dataUri`（`image/*` MIME）或 `BigPictureStyle` 的 `EXTRA_PICTURE` / `EXTRA_PICTURE_ICON`。僅能取得通知系統提供且當下有讀取權限的媒體，不保證每個 App / OEM 的行為。
- 圖片最多長邊 1280 px，JPEG 壓縮並保存在 **Android Keystore AES-256-GCM 加密的 SQLite BLOB**；不會偷偷讀相簿或檔案庫。每張上限約 700 KiB。
- 文字標題/傳送者/內文也用 AES-256-GCM 加密，指紋以 HMAC 去重；來源套件名稱與時間仍為 SQLite 明文中繼資料（不是全庫 SQLCipher）。
- 進入必須驗證手機解鎖憑證；防止截圖；退出畫面需要重新解鎖。
- 沒有 `INTERNET` 權限、廣告、遙測、雲端備份、Accessibility、Root 權限、`READ_EXTERNAL_STORAGE`。
- 通知來源和影像擷取都 **預設關閉**。需要使用者自己勾選並到系統授予通知存取權限。

## 已知限制

1. 不能將 LINE 或 Instagram 的原始聊天資料庫當成通知讀取。已撤回訊息只有在通知內容成功保存後才可能保留。**打開本 App 不會主動要求原通訊軟體回報已讀；打開通訊軟體本身仍可能觸發已讀。**
2. 沒有從通知得到圖片位元資料、只有文字「傳送了一張照片」、受加密/刪除/ROM 限制或 Uri 已撤銷時，不會有原圖可看。預覽不是媒體原檔，EXIF/動圖資訊也不保留。
3. 圖片最多保存為經壓縮的預覽，沒有備份/同步。解除安裝、重置 Keystore 或清除 App 資料後資料可能永久無法復原。
4. 由於沒有搭配 Android SDK 的執行環境，目前只有原始碼、靜態檢查與純 Kotlin policy test。**不應聲稱此版本已驗證能在 Android 16、LINE、WhatsApp 上正常運作。**
5. 有 Root 或惡意系統權限程式的手機上，Android 本機加密不能保證對抗已取得系統級權限的攻擊。
6. 單裝置本機搜尋會將最多 1500 則近期紀錄暫時解密到記憶體；返回背景後會清空參照。沒有全庫分頁或多張圖片相簿。
7. 部分 Android/OEM 可能不保證背景監聽、短暫 URI 權限或通知完整性；正式發行前需實測。通知來源為本機可辨識的部分 App 套件名稱。

## 建置 APK

開啟根目錄至 Android Studio（建議 Android SDK 36 / JDK 17）。使用 Gradle 8.13 執行：

```bash
gradle --no-daemon :app:assembleDebug
```

將得到 `app/build/outputs/apk/debug/app-debug.apk`。目前未內附 Gradle Wrapper JAR，請在 IDE 中設定 Gradle 或於可信任機器使用官方 Gradle。

**免自行裝 SDK 的方式：** 建立你自己私人的 GitHub repository，放入這個專案；在 Actions 中執行 `Android debug APK` 工作流程，完成後在 workflow 的 Artifacts 下載 `PrivateInboxClassic-debug-apk`。Debug APK 以 GitHub runner 自動產生的 debug key 簽署，只供安裝測試，正式使用請配置你自己持有的 release 簽章。

## 安裝與使用

1. 在 Android 9–16 裝置安裝 APK，先設定 PIN/密碼/圖形鎖。
2. 解鎖本 App，點藍色浮動齒輪進入「來源與隱私」。
3. 選擇 LINE 等來源，勾選「保存可存取的通知照片」，啟用訊息記錄。
4. 點「前往 Android 通知存取授權」，**僅授權此應用程式**。
5. 從另一個帳號發送非敏感測試訊息與照片，驗證在本 App 中看到通知副本／照片；測試 LINE 是否仍為未讀。
6. 把通知改為隱藏內容、撤回訊息、連續發照片、重複推播、鎖屏、手機重啟，再驗證有無漏記。

## 安全驗收指令

```bash
bash scripts/audit.sh
# 真正編譯後：
aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
```

若需要真正以 QuietInbox 作上游，必須先取得該專案一個經過稽核的 GitLab source ref 並驗證 GPL-3.0-or-later、差異與 Android build；**本版本未聲稱完成上游整合。**

## 參考

- https://gitlab.com/aa22396584/quietinbox
- https://telegram.org/blog/android-2-0
- https://developer.android.com/reference/android/app/Notification.MessagingStyle.Message
- https://developer.android.com/reference/android/app/Notification
- https://developer.android.com/reference/android/service/notification/NotificationListenerService

**清單右側的綠色數字是本機保存的通知筆數，並非 LINE / Telegram 真正的未讀數量。**

**更新提醒：** 每次 GitHub Actions runner 產生的 Debug 簽章可能不同，不能保證直接覆蓋更新已安裝的 App；卸載重裝會失去本機資料。長期使用請建立由你自己保管且持續使用的 Release keystore、簽章與資料遷移流程。
