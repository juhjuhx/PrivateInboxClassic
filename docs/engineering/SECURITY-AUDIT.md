# 安全審查報告（SECURITY-AUDIT）

**版本**：0.4.1 (versionCode 7)
**審查方式**：以**最終封裝的 APK / AAB** 為對象，不是原始碼。
**日期**：本次交付

---

## 1. 審查範圍

| 項目 | 審查方式 | 結果 |
|------|----------|------|
| A. Android Manifest | `aapt dump xmltree` 對最終 APK | ✅ |
| B. NotificationListenerService | 原始碼 + 封裝後權限 | ✅ |
| C. Exported Components | `aapt dump xmltree` | ✅ |
| D. DebugSettingsProvider | 封裝後 manifest + 裝置實測 | ✅ **本輪修正** |
| E. Backup / Device Transfer | 封裝後 manifest + 編譯後的規則資源 | ✅ **本輪修正** |
| F. Android Keystore | 原始碼 + 狀態模型測試 | ✅ |
| G. AES-GCM | 原始碼 + 往返測試 + 竄改測試 | ✅ |
| H. SQLite 明文 metadata | schema + 資料模型 | ⚠️ 已知且已揭露 |
| I. 通知診斷 | 原始碼 + 匯出驗證 | ✅ |
| J. 影像解碼攻擊面 | 原始碼 + 邊界測試 | ✅ |
| K. 資料保留 | 原始碼 | ✅ |
| L. 資料刪除 | 原始碼 + 測試 | ✅ |
| M. 相依稽核 | Gradle 依賴清單 + APK 內容 | ✅ |
| N. 簽章設定 | Gradle + `apksigner` 對實際產物 | ✅ **本輪修正** |
| O. 秘密 / Git 歷史 | 全歷史掃描 | ✅ |

---

## 2. 本輪修正的三個問題

### 2.1 [HIGH] DebugSettingsProvider 對所有 App 開放

**重現（修正前）**：

```bash
$ adb shell content call --uri content://tw.personal.privateinbox.debugsettings \
    --method setRecording --arg true
Result: Bundle[{ok=true}]
$ adb shell run-as tw.personal.privateinbox cat shared_prefs/local_config.xml
<boolean name="recording_enabled" value="true" />
```

`adb shell` 以 uid 2000 執行，與 App 的 uid 不同。**任何安裝在手機上的 App
都能讀取擷取診斷資料，並且能開啟訊息記錄。**

**修正**：

- 宣告 `tw.personal.privateinbox.permission.DEBUG_SETTINGS`，
  `android:protectionLevel="signature"`（封裝後驗證為 `0x2`）。
- Provider 要求該權限。
- 額外獨立檢查：呼叫者 UID + **簽章憑證**與 App 相同。
  先前的實作比較的是 `Process.myUid()`（**Provider 自己的** uid，永遠是 App 的），
  根本沒有辨識到呼叫者。

**驗證（修正後）**：

```bash
$ adb shell content call --uri content://tw.personal.privateinbox.debugsettings \
    --method setRecording --arg true
java.lang.SecurityException: Permission Denial: opening provider
  tw.personal.privateinbox.DebugSettingsProvider from (null) (pid=5341, uid=2000)
  requires tw.personal.privateinbox.permission.DEBUG_SETTINGS
```

App 的 `recording_enabled` 維持未設定 → **攻擊者無法開啟擷取**。

**Release 組態確認**：最終 release APK 中**完全沒有** `DebugSettingsProvider`。

### 2.2 [HIGH] Release APK 帶著 Android Debug 簽章

**重現（修正前）**：

```bash
$ apksigner verify --print-certs app-release-unsigned.apk
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
```

檔名寫著 "release-unsigned"，實際卻帶著 debug 簽章 —— 與測試版無法區分，
容易誤散布。

**修正**：沒有設定 release 金鑰時，release 組態**不設定** signing config，
產出真正的未簽署 artifact，並在建置輸出印出明確警告。

**驗證（修正後）**：

```bash
$ apksigner verify app-release-unsigned.apk
DOES NOT VERIFY
ERROR: Missing META-INF/MANIFEST.MF
```

使用者既有的 debug 金鑰**未被輪替**（仍為 `c876051e...`），
`adb install -r` 仍可覆蓋更新。

### 2.3 [MEDIUM] 備份規則依賴平台預設

原本的 `data_extraction_rules.xml` 是**空的** `<cloud-backup>` / `<device-transfer>`
節點，只留 XML 註解。這是依賴平台的預設行為，而非明確宣告。

**修正**：兩個節點各自明確排除全部九個網域
（root / file / database / sharedpref / external 及 device_* 對應項）。

**驗證**：對**封裝後**的 release APK，用 `aapt2 dump xmltree` 讀出編譯後的
資源，確認 18 條 exclude 規則與九個網域都在。

平台層面的驗證：`dumpsys backup` 中**完全找不到**本套件 ——
`allowBackup="false"` 讓它在備份管理器層面就沒有被註冊。

---

## 3. 逐項結果

### A. Android Manifest

| 檢查 | 結果 |
|------|------|
| `android.permission.INTERNET` | **absent** ✅ |
| `android.permission.ACCESS_NETWORK_STATE` | **absent** ✅ |
| `android.permission.READ_SMS` | **absent** ✅ |
| `android.permission.READ_CONTACTS` | **absent** ✅ |
| `android.permission.QUERY_ALL_PACKAGES` | **absent** ✅ |
| `android.permission.READ_EXTERNAL_STORAGE` | **absent** ✅ |
| `android.permission.POST_NOTIFICATIONS` | present — **僅**用於本 App 自己的自我測試通知 |

`POST_NOTIFICATIONS` 的影響：Android 13+ 上，若使用者拒絕，本 App
**無法發送自己的測試通知**。**擷取 LINE / Messenger 等來源完全不需要它**。
已在 `PRIVACY.md` 與 `SECURITY.md` 中一致說明。

### B. NotificationListenerService

- 以 `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` 綁定。
- `android:exported="false"`。
- 系統層面只能由持有該簽章權限的系統程序綁定。
- 來源白名單**雙重把關**：`CapturePolicy.allowed()` 要求套件名在程式內建
  清單中 **且** 使用者已勾選。

### C. Exported Components

| 元件 | exported | 判定 |
|------|----------|------|
| `MainActivity` | true | 正常（Launcher 必須 exported） |
| `PrivateNotificationListener` | false | 正確 |
| `DebugSettingsProvider`（debug only） | true + signature 權限 | 正確 |
| androidx `ProfileInstallReceiver` | true + `DUMP` | 系統專用（signature-level） |
| androidx `ProfileInstallerReceiver` | false | 正確 |
| androidx `InitializationProvider` | false | 正確 |

**沒有** FileProvider、**沒有**自訂 ContentProvider、**沒有** exported Service。

### F. Android Keystore

- 金鑰在 `AndroidKeyStore` provider 內產生，程式碼中**沒有**明文金鑰。
- AES-256-GCM（內容與照片）、HMAC-SHA256（去重指紋）。
- 每次加密由 `Cipher.init(ENCRYPT_MODE)` 產生新的隨機 IV，**絕不重複**。
- AAD 綁定套件名，照片的 AAD 另加 `:image` 後綴。

**金鑰失效的處理（本輪新增）**：

| 狀態 | 意義 | 行為 |
|------|------|------|
| `KEY_AVAILABLE` | 金鑰可用 | 正常 |
| `KEY_TEMPORARILY_UNAVAILABLE` | 裝置尚未解鎖等暫時性狀況 | 重試可能成功，**不刪任何資料** |
| `KEY_INVALIDATED` | 永久失效（改變螢幕鎖、重新註冊生物辨識） | 提示使用者，資料保留 |
| `DECRYPT_FAILED` | 該列資料損毀或遭竄改 | 僅跳過該列 |
| `RECOVERY_REQUIRED` | 金鑰已失效，資料永久不可讀 | 明確提示 + **需使用者確認**才可清除 |

**不會**因為解密失敗就自動刪除資料庫。清除動作
（`purgeUndecryptableRows()`）只能由使用者明確確認的流程觸發。

### G. AES-GCM

- 密文格式：`IV(12) ‖ ciphertext ‖ tag(16)`。
- 竄改密文 → GCM 驗證失敗 → 該列被跳過，**不會**回傳錯誤的明文。
- 用錯 AAD（不同套件）→ 同樣被 GCM 攔下。
- 這兩項都有測試覆蓋（`KeyStateInstrumentedTest`）。

### H. SQLite 明文 metadata

**明確揭露的未加密內容**：

| 欄位 | 內容 | 風險 |
|------|------|------|
| `package_name` | 來源套件名 | 看出你用哪些通訊軟體 |
| `received_at` / `source_ts` | 時間戳 | 看出你什麼時候收到訊息 |
| `fingerprint` | HMAC-SHA256 | 不可逆推原文，但相同訊息有相同指紋，可做關聯分析 |
| 列數 | 訊息總數 | 看出聊天量 |

**已加密**：標題、寄件者、訊息內文、照片本體。

**決策理由**：採用 SQLCipher 會增加一個原生 `.so` 依賴、顯著增大 APK，
並引入「金鑰何時載入」的額外問題。這個取捨**在此明確揭露**，
而不是假裝資料庫是整體加密的。

### I. 通知診斷

- 只記錄：時間、traceId、階段代碼、來源套件名、錯誤型別名、耗時。
- **不記錄**：訊息正文、圖片位元組、圖片 URI、完整 extras、聯絡人資料、金鑰。
- 匯出的 JSON 以 `JSONObject`/`JSONArray` 建構（每個值都跳脫），
  回傳前**重新解析驗證**，並帶 `schema_version`。
- 存放於 App 私有 SharedPreferences，持久化 100 筆、保留 7 天。

### J. 影像解碼攻擊面

| 防線 | 實作 |
|------|------|
| URI scheme | 只接受 `content://`；拒絕 `file://` 與 `http(s)://` |
| 讀取逾時 | 2 秒上限，慢的 provider 不能卡住通知回呼 |
| 大小上限 | 輸入 8 MiB |
| 格式驗證 | magic byte（JPEG/PNG/GIF/WebP/HEIC）+ 宣告 MIME |
| 解碼記憶體 | `inJustDecodeBounds` 先取得尺寸，`inSampleSize` 確保 ≤ 2048 px |
| 預覽尺寸 | 最長邊 1280 px |
| 儲存大小 | ≤ 700 KiB |
| EXIF | 只讀方向旋轉，其餘全部丟棄（含 GPS） |
| 失敗影響 | **影像失敗不影響文字入庫** |

### K / L. 資料保留與刪除

- 保留期限：永久 / 30 / 90 / 180 天，由使用者設定。
- 刪除單則訊息時，其照片 BLOB 一併刪除（同一列）。
- 清除全部 → `DELETE FROM messages` + `PRAGMA wal_checkpoint(TRUNCATE)`。
- `PRAGMA secure_delete=ON`（頁面內容覆寫）。
- 解除安裝 / 清除資料 / 金鑰失效 → **資料永久無法復原**（無備份功能，刻意設計）。

### M. 相依稽核

| 相依 | 版本 | 授權 | 用途 |
|------|------|------|------|
| `androidx.activity:activity` | 1.9.2 | Apache-2.0 | predictive-back 相容的 `OnBackPressedCallback` |
| `junit:junit` | 4.13.2 | EPL-1.0 | 測試 |
| `androidx.test.*` | 1.2.1 / 1.6.2 / 1.6.1 | Apache-2.0 | 儀器測試（僅 androidTest） |

**無** Firebase、Crashlytics、Facebook SDK、Google Analytics、廣告 SDK、
OkHttp、Retrofit、Glide、SQLCipher。
**無原生 `.so` 依賴**（本專案不使用任何原生函式庫）。

### N. 簽章設定

| 身分 | 憑證 SHA-256 | 狀態 |
|------|--------------|------|
| Debug | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | 可重現，與既有安裝相容 |
| Release | — | **BLOCKED**：需擁有者建立 |
| Upload | — | **BLOCKED**：需擁有者建立 |

沒有設定 release 金鑰時，release 產物是**未簽署**的，且建置會印出警告。
**不會**用 debug 金鑰冒充正式發行。

### O. 秘密與 Git 歷史

`scripts/audit-secrets.sh` 掃描：

1. 追蹤檔案中是否有金鑰／密碼檔。
2. **完整 Git 歷史**（所有 commit）的檔名。
3. **完整 Git 歷史**的內容（秘密賦值樣式）。
4. 工作樹中未追蹤的機密形狀檔案。
5. `.gitignore` 是否覆蓋了必要的項目。

**結果：PASS，無任何發現。**

金鑰、密碼、`local.properties`、`.agent/state.json` 都沒有進 Git。

---

## 4. 殘餘風險

| 風險 | 等級 | 說明 |
|------|------|------|
| 資料庫 metadata 明文 | 中 | 已在 §3.H 明確揭露 |
| 通知存取權限是廣泛權限 | 中 | 平台限制；本專案以程式自律限制範圍 |
| 通知內容可能被來源 App 隱藏 | 低 | 平台行為 |
| Root 攻擊 | 低 | 平台限制；無法對抗 |
| 自我測試需要 POST_NOTIFICATIONS | 低 | 不影響擷取 |
| Keystore 失效 = 資料永久遺失 | 中 | 已提供明確提示與確認流程，但無法復原 |
| OEM 背景限制 | 中 | 平台限制；見相容性文件 |

---

## 5. 結論

**已驗證**：最終 APK 無 INTERNET 與其他敏感權限、無追蹤 SDK、
金鑰不在程式碼內、內容 AES-256-GCM 加密、匯出元件最小化、
備份與裝置轉移排除、Logcat 無聊天內容、來源白名單雙重把關、
Git 歷史無秘密。

**本輪修正**：三個實際可利用的問題（provider 越權、release 誤簽、
備份規則空洞）。

**不能聲稱**：本 App「絕對安全」。有 Root 或系統級權限的程式仍可讀取
本 App 的資料；`FLAG_SECURE` 只擋畫面複製；資料庫 metadata 仍是明文。
