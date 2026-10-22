# Private Inbox Classic

> **English: [README.md](README.md)**

**一個完全離線、注重隱私的 Android 通知訊息保存工具。**

把 Android 系統交給通知監聽器的訊息，在本機加密保存成副本。
當原始訊息在 LINE / Telegram / WhatsApp / Instagram / Messenger / Threads
被撤回時，只要通知在撤回前成功擷取過，你仍能查看當時的副本。

- **沒有 INTERNET 權限** — 技術上無法連線到任何伺服器
- **沒有帳號登入、沒有廣告、沒有遙測、沒有雲端**
- **不需要 Root、Accessibility 或任何繞過沙盒的技巧**
- 介面重現 2014–2016 年 Telegram Android 的 Material Design 1.0 風格

---

## 目錄

- [功能](#功能)
- [支援的平台](#支援的平台)
- [安裝](#安裝)
- [從原始碼建置](#從原始碼建置)
- [通知擷取的實際限制](#通知擷取的實際限制)
- [照片保存的實際限制](#照片保存的實際限制)
- [隱私與安全設計](#隱私與安全設計)
- [已知問題](#已知問題)
- [專案架構](#專案架構)
- [版本](#版本)
- [授權](#授權)

---

## 功能

| 功能 | 說明 |
|------|------|
| 通知擷取 | `NotificationListenerService`，支援 `MessagingStyle`、`InboxStyle`、`BigTextStyle` 與一般通知 |
| 來源白名單 | 每個通訊 App 可獨立開關，預設全部關閉 |
| 平台篩選 | 主畫面水平篩選列：全部 / LINE / IG / MS / TH / WA / TG… |
| 訊息分類 | 區分私訊、群組、提及、回覆、社交動態與未知 |
| 對話識別 | 以平台的 conversation shortcut id 為主，跨 App 永不合併 |
| 加密保存 | 內容與照片以 Android Keystore AES-256-GCM 加密後存入 SQLite |
| 照片預覽 | 縮圖、雙指縮放、雙擊放大；照片與訊息同步刪除 |
| 搜尋 | 依聯絡人 / 訊息 / 來源 / 日期範圍搜尋，全部在本機進行 |
| 診斷 | 逐階段追蹤擷取管線，可匯出匿名 JSON 報告 |
| 自我測試 | 透過真正的 Android 通知系統驗證擷取管線是否正常 |
| App Lock | 進入需驗證手機解鎖憑證；`FLAG_SECURE` 防止截圖 |

---

## 支援的平台

分類依據是**真實的 Android 套件名稱**，不是通知文字猜測。

| 平台 | 套件名稱 |
|------|----------|
| LINE | `jp.naver.line.android` |
| Instagram | `com.instagram.android` |
| Messenger | `com.facebook.orca` |
| Threads | `com.instagram.barcelona` |
| WhatsApp | `com.whatsapp` |
| Telegram | `org.telegram.messenger` |
| Telegram X | `org.thunderdog.challegram` |
| Teams | `com.microsoft.teams` |
| KakaoTalk | `com.kakao.talk` |

要新增平台，只要在 `SourceRegistry.kt` 加一筆 — 篩選列、來源設定與分類器
都會自動跟著更新。

---

## 安裝

1. 安裝 APK 後，**先在 Android 設定建立 PIN / 密碼 / 圖形鎖**。
   本 App 需要裝置身分驗證，沒有螢幕鎖就不會開啟。
2. 開啟 App，點「使用手機螢幕鎖解鎖」。
3. 進入右下角 ⚙ 設定 → 勾選要擷取的來源。
4. 需要照片的話，開啟「保存可存取的通知照片」。
5. 點「前往 Android 通知存取授權」→ **只授權此應用程式**。
6. 從另一個帳號發送非敏感測試訊息，確認列表出現該則副本。

### 小米 / MIUI / HyperOS

背景存活需要額外設定，否則通知監聽服務可能被系統回收：

1. 設定 → 應用程式管理 → Private Inbox Classic → 其他權限 →
   **通知存取權限** 開啟。
2. 設定 → 應用程式管理 → Private Inbox Classic → 其他設定 →
   **允許後台活動 / 允許自啟動 / 後台彈出介面** 全部開啟。
3. 設定 → 其他設定 → 應用程式管理 → Private Inbox Classic → 省電 →
   **不要自動管理**。

> 即使完成以上設定，部分 MIUI / HyperOS 版本在極端省電情境下仍可能停止
> 背景監聽。**本專案不對此提供保證。**

---

## 從原始碼建置

需要 **JDK 17**、**Android SDK 36**、**Build Tools 36.0.0**。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"

./gradlew :app:assembleDebug               # Debug APK
./gradlew :app:testDebugUnitTest           # 單元測試
./gradlew :app:lintDebug                   # Android Lint
./gradlew :app:connectedDebugAndroidTest   # 儀器測試（需模擬器或實機）
```

產出：`app/build/outputs/apk/debug/app-debug.apk`

簽章設定見 [docs/SIGNING.md](docs/SIGNING.md)。
建置教學見 [docs/BUILDING.md](docs/BUILDING.md)。

---

## 通知擷取的實際限制

**本 App 不會讀取通訊 App 的聊天資料庫。** 它只拿 Android 系統實際投递到
通知列的內容。因此：

- 已撤回的訊息，只有在**撤回前**已成功擷取才留有副本。
- 使用者在原始 App 裡打開對話，該對話仍會被標記已讀 — 本 App 無法也不會介入。
  反過來說，**本 App 自己不會讓你的對話變成已讀**。
- 應用程式在推播前就被讀取的訊息，本 App 永遠看不到。
- 通知存取權限是**全域廣泛權限**。授予後系統層面可見所有通知；
  本專案用程式內白名單限制實際擷取範圍，但這是程式自律而非系統隔離。

---

## 照片保存的實際限制

**本 App 不能還原被撤回的照片。** 只能保存「通知裡真的附帶、而且當下真的
讀得到」的影像位元組。

- 通知只有文字「傳送了一張照片」而沒有附帶影像 → **只保存文字，不產生假圖片**。
- 保存的是壓縮預覽：最長邊 1280 px、JPEG、≤ 700 KiB、**不保留 EXIF**、
  不保留動態照片或 GIF 動畫。
- 不會讀取你的相簿。沒有 `READ_MEDIA_IMAGES` 權限。
- 圖片只存在 App 私有儲存空間並加密，**不在相簿或任何外部儲存空間留下明文**。

---

## 隱私與安全設計

| 措施 | 狀態 |
|------|------|
| 無 INTERNET 權限（最終 APK 實測） | ✅ |
| 無第三方追蹤 / 廣告 / 遙測 SDK | ✅ |
| 內容與照片 AES-256-GCM 加密 | ✅ |
| 金鑰在 Android Keystore，程式碼內無明文金鑰 | ✅ |
| 匯出元件最小化 | ✅ |
| 自動備份與裝置轉移關閉 | ✅ |
| Logcat 無聊天內容 | ✅ |
| App Lock + `FLAG_SECURE` | ✅ |
| 資料庫**未**整體 SQLCipher 加密 | ⚠️ metadata 仍為明文，見 [docs/SECURITY.md](docs/SECURITY.md) |

**明確聲明**：即使沒有 INTERNET 權限，也不能說本 App「絕對安全」。
已 Root 或具系統級權限的程式仍可讀取本 App 的資料；
`FLAG_SECURE` 只擋「畫面被複製」，不防外部攝影。

完整審查報告見 [docs/SECURITY.md](docs/SECURITY.md)。

---

## 已知問題

1. **資料庫不是整體加密**：來源套件名、時間戳、訊息筆數仍為明文 metadata。
2. **Keystore 金鑰失效**（例如變更鎖定畫面）會使既有密文永久不可讀，
   目前只做到「不 Crash、略過無法解密的列」。
3. **搜尋僅涵蓋最近 1500 筆**，並會暫時把資料解密到記憶體比對。
4. **同名聯絡人**：沒有 shortcut id 時，同一 App 內兩個同名聯絡人無法區分。
5. **解除安裝或清除 App 資料後，資料永久無法復原**（無備份／匯出功能，刻意設計）。
6. **真實 LINE / Messenger / Threads 尚未在實體手機上驗證** — 見
   [docs/REAL-DEVICE-TEST-PLAN.md](docs/REAL-DEVICE-TEST-PLAN.md)。
7. 沒有完整分頁；聊天列表一次載入 1500 筆並全數解密。

---

## 專案架構

```
app/src/main/java/tw/personal/privateinbox/
├── MainActivity.kt                 全部 UI（鎖定 / 列表 / 聊天室 / 照片 / 搜尋 / 診斷 / 設定）
├── PrivateNotificationListener.kt  通知監聽服務與擷取管線
├── CapturePolicy.kt                來源允許政策（委派給 SourceRegistry）
├── SourceRegistry.kt               平台來源、分類規則的唯一來源
├── ConversationIdentity.kt         穩定的對話識別
├── CaptureDiagnostics.kt           逐階段診斷與 JSON 匯出
├── SelfTestNotifier.kt             透過真實系統通知的自我測試
├── CryptoStore.kt                  Keystore AES-256-GCM + HMAC
├── ImageCapture.kt                 影像驗證 / 降級 / 壓縮
├── InboxDatabase.kt                SQLite schema v4 + 遷移
├── LocalSettings.kt                設定、保留期限、已讀標記
└── PhotoZoomView.kt               純框架縮放照片檢視器
```

更完整的說明見 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

---

## 版本

| 版本 | 說明 |
|------|------|
| 0.4.0-alpha | 平台分類、Threads、診斷強化、可重現簽章 |
| 0.3.1 | 通知診斷、自我測試、SaveResult |
| 0.3.0 | 建置復原、Gradle Wrapper、真實 APK、instrumentation 測試 |

詳細變更見 [CHANGELOG.md](CHANGELOG.md)。

---

## 交付產物（v0.4.2）

| 檔案 | 大小 | SHA-256 | 狀態 |
|------|------|---------|------|
| `PrivateInboxClassic-v0.4.2-debug.apk` | 5,948,649 B | `dca038856f83761f080cf8ec64e43fa73e43925e846911c1eedee3bc5e6f8567` | Debug 測試版，簽章與既有安裝相容 |
| `PrivateInboxClassic-v0.4.2-release-unsigned.apk` | 4,975,111 B | `d63707f15e10b8e893d7149e78433e6878e9816a7be8d03eb319ec34cae5a0c5` | **未簽署**，需正式金鑰 |
| `PrivateInboxClassic-v0.4.2.aab` | 1,736,305 B | `7dd33706f00f797397491ad9e28bf6afbdb3d2788713ff0d6f14429300bea7a4` | **未簽署**（已修正：舊版會誤用 debug 金鑰）|

Debug APK 的簽章憑證 SHA-256：
`c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`
（與既有安裝相容，因此 `adb install -r` 可覆蓋更新）。

**Release 產物是未簽署的**，因為正式金鑰必須由擁有者保管。
見 `docs/SIGNING.md`。

---

## 授權

[MIT License](LICENSE)。

本專案為**獨立實作**，未複製任何 GPL 專案的原始碼。
QuietInbox（GPL-3.0-or-later）僅作為**產品理念參考**。
詳見 LICENSE 檔案中的 provenance 說明。

---

## 相關文件

| 文件 | 內容 |
|------|------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架構、資料流、資料模型 |
| [docs/BUILDING.md](docs/BUILDING.md) | 建置教學 |
| [docs/NOTIFICATION-PIPELINE.md](docs/NOTIFICATION-PIPELINE.md) | 通知擷取管線與診斷 |
| [docs/PLATFORM-COMPATIBILITY.md](docs/PLATFORM-COMPATIBILITY.md) | 各平台相容性矩陣 |
| [docs/SECURITY.md](docs/SECURITY.md) | 資安審查 |
| [docs/SIGNING.md](docs/SIGNING.md) | 簽章與發布 |
| [docs/TEST-REPORT.md](docs/TEST-REPORT.md) | 測試結果 |
| [docs/HANDOFF.md](docs/HANDOFF.md) | 交接文件 |
| [docs/REAL-DEVICE-TEST-PLAN.md](docs/REAL-DEVICE-TEST-PLAN.md) | 實機測試計畫 |
