# DHC Connector v0.1

> **STATUS: PROTOTYPE**
>
> The data contract, the encrypted container format, the Android exporter and the
> Python import adapter are complete and tested end to end.
> **The DHC workstation itself is NOT integrated.** What exists here is an
> offline import layer that a future DHC plugin can consume. The brief's
> acceptance criterion is met; the "integrated into DHC" claim is not made.

---

## 1. 為什麼是這個架構

**不能**讓 DHC 直接讀 Android 的 `inbox.db`：

1. Android Keystore 的金鑰不應離開裝置，DHC 也無法解密 `sealed_content`。
2. 直接複製資料庫等於把「密文 + 明文 metadata」一起搬走，違背本專案的隱私立場。
3. Android 資料庫格式沒有版本化的對外契約，日後改 schema 會直接破壞匯入端。

所以路徑是：

```
Android Private Inbox
    │  使用者明確授權（SAF 選檔，本輪尚未接上 UI）
    ▼
Local Export Service（Android 端）
    │  版本化的加密封包
    ▼
User Selected File
    │
    ▼
DHC Import Adapter（本輪成果，獨立 Python 工具）
    │
    ▼
Local Data Store
    │
    ▼
Authorized Query / Search
```

**本輪維持 Private Inbox 的零 INTERNET 權限**：沒有新增網路同步、
沒有自動上傳、沒有公網 API。

---

## 2. 與 DHC 的授權邊界

已查證 DHC CLI（<https://github.com/hades60414-sys/dhc-cli>）：

| 項目 | 實測結果 |
|------|----------|
| 授權 | **AGPL-3.0** |
| 主要語言 | TypeScript（Node.js / Electron） |
| `domains/` | 每個子資料夾是一組給 AI Agent 用的 Markdown skill，**不是**資料匯入 API |
| `plugins/` | 真正的擴充點：manifest 需要 `name` / `displayName` / `entry`，型別 `react` 或 `module`，插槽 `tab` |
| `plugins/starter` | 是 git submodule（`cloudcli-ai/cloudcli-plugin-starter`） |

**決策**：

- **沒有**修改或推送 DHC 的 repository。
- **沒有**把任何 AGPL 原始碼複製進 Private Inbox 的 MIT 核心。
- 本專案只實作**自己的資料契約**（`protocol/contract.py`）與
  **自己的容器格式**（`protocol/crypto.py`），兩者都是獨立原創。
- DHC 端的接入留待後續：屆時應在 DHC 側建立一個 plugin，
  呼叫本專案的 importer。**那個 plugin 會是 AGPL 的**，
  但它不影響 Private Inbox 核心仍是 MIT。

---

## 3. 資料契約：`PRIVATE_INBOX_EXPORT_V1`

定義於 `integrations/dhc/protocol/contract.py`。

```json
{
  "schema_version": 1,
  "schema_name": "PRIVATE_INBOX_EXPORT_V1",
  "export_id": "exp_...",
  "source_device_id": "dev_...",
  "export_created_at": 1700000000000,
  "cursor": null,
  "messages": [
    {
      "message_id": "msg_...",
      "conversation_id": "...",
      "source_application": "jp.naver.line.android",
      "message_kind": "DIRECT_MESSAGE",
      "received_at": 1700000000000,
      "source_timestamp": 1700000000000,
      "sender": "Alice",
      "body": "...",
      "attachment_metadata": { "kind": "image", "media_id": "..." }
    }
  ]
}
```

### 3.1 為什麼 `message_id` 不是 SQLite row id

row id 是**裝置本地**的：換一台裝置、重新安裝、資料庫重建，id 都會變，
兩台裝置的第 3 筆訊息會撞在一起。

`message_id` 改為**內容衍生**：

```
msg_ = base64url(sha256(conversation_id \0 source_app \0 source_ts \0 body)[:15])
```

好處：

- 同一則訊息在任何裝置都得到同一個 id → **匯入冪等**。
- 包含 body，所以同一秒的兩則不同訊息不會撞。
- 不含裝置資訊，不洩漏硬體指紋。

### 3.2 支援的匯入語意

| 需求 | 實作 |
|------|------|
| Incremental Export Cursor | `cursor` 欄位；匯入端以 `message_id` 去重 |
| Idempotent Import | `LocalStore.insert()` 先查 `message_id`，已存在則跳過 |
| Duplicate Detection | 回傳 `duplicates_skipped` 計數 |
| Schema Version Validation | `contract.parse()` 檢查 `schema_version` 與 `schema_name` |
| Reject Unknown Versions | 版本不符 → `ContractError` / `UnsupportedVersionError`，**不猜測** |
| Invalid Input Handling | 缺欄位、未知 `message_kind`、非 JSON → 明確錯誤 |
| Import Error Report | `ImportSummary.errors[]` 逐列記錄，不中斷整批匯入 |

### 3.3 訊息分類

`message_kind` 沿用 Android 端的分類器：

`DIRECT_MESSAGE` / `GROUP_MESSAGE` / `MENTION` / `REPLY` / `SOCIAL_ACTIVITY` / `UNKNOWN`

`private_only()` 只回傳私訊類型。**社交動態不會混進私人收件匣**，
這與 Android 端的預設行為一致。

---

## 4. 加密容器格式

定義於 `integrations/dhc/protocol/crypto.py` 與
`app/src/main/java/tw/personal/privateinbox/dhc/DhcExporter.kt`。

```
+--------+------------------+---------------+--------------------+
| PICBOX | headerLen (4, BE)| header (JSON) | ciphertext+tag    |
+--------+------------------+---------------+--------------------+
```

header 內容：

```json
{
  "schema_version": 1,
  "kdf": "PBKDF2-HMAC-SHA256",
  "kdf_iterations": 210000,
  "kdf_salt": "<base64, 16 bytes>",
  "nonce": "<base64, 12 bytes>",
  "cipher": "AES-256-GCM",
  "body_length": 792
}
```

### 4.1 密碼學選擇與理由

| 項目 | 選擇 | 理由 |
|------|------|------|
| KDF | **PBKDF2-HMAC-SHA256**, 210,000 次 | Android 的 `SecretKeyFactory`（JDK 標準）與 Python 的 `hashlib.pbkdf2_hmac` **原生支援**，兩邊位元級一致，不需要第三方相依，也不用自己實作 |
| AEAD | **AES-256-GCM** | Android 的 `javax.crypto.Cipher` 與 Python 的 `cryptography` / `pycryptodome` 都支援 |
| Nonce | 每個封包 12 bytes，CSPRNG 隨機 | **絕不重複**。GCM 下 nonce 重複是致命的 |
| Salt | 每個封包 16 bytes，CSPRNG 隨機 | 同一個密碼每次匯出都得到不同的金鑰 |
| AAD | `PICBOX01 ‖ headerLen ‖ header` | header 被驗證，攻擊者無法調包 KDF 參數、salt 或 nonce |
| Tag | GCM 內建 16 bytes | 竄改密文或 header 都會被偵測 |

**沒有自創演算法。** 全部是標準建構。

### 4.2 密碼與金鑰的界線

| 項目 | 處理 |
|------|------|
| Android Keystore 金鑰 | **絕不離開裝置**。只用它解密自己的資料列 |
| 匯出 passphrase | 使用者提供。**不寫入磁碟、不寫入日誌、不寫入封包** |
| 匯出金鑰 | 由 passphrase + salt 派生，只存在於記憶體 |
| 匯出結果 | 使用者選定的檔案，AEAD 加密 |

### 4.3 Python 端的 AEAD 實作

`cryptography` 優先，沒有就退回 `pycryptodome`（`Crypto.Cipher`）。
兩者都是成熟、廣泛使用的 AES-GCM 實作。模組是**延遲載入**的，
所以沒有安裝任何一個時，仍然可以閱讀原始碼。

---

## 5. 驗收測試

### 5.1 Python 端（23 項，全 PASS）

```bash
python3 integrations/dhc/tests/test_connector.py
```

涵蓋 brief §7.6 要求的每一項：

| 情境 | 結果 |
|------|------|
| Correct Passphrase | PASS |
| Wrong Passphrase | PASS（被拒絕） |
| Tampered Ciphertext | PASS（被拒絕） |
| Tampered Header | PASS（AAD 驗證攔截） |
| Duplicate Import | PASS（跳過重複，計數正確） |
| Unsupported Version（容器層） | PASS（`UnsupportedVersionError`） |
| Unsupported Version（契約層） | PASS（`ContractError`） |
| Corrupted Archive | PASS |
| Truncated Archive | PASS |
| Oversized Input | PASS（先驗長度，不會配置宣稱的記憶體） |
| Empty Archive | PASS |
| Nonce/Salt 永不重複 | PASS |
| 匯入檔案權限 0600 | PASS |

### 5.2 跨平台往返（Android → Python）

**這是本輪最關鍵的證據。**

Android（Kotlin + JCE）用同一個 passphrase 產生封包，
Python 參考實作解密並讀出訊息：

```
$ bash integrations/dhc/tests/host_interop.sh
bundle:  .../app/build/outputs/dhc-export-test.picbox (997 bytes)

--- report ---
{
  "header": {
    "body_length": 792,
    "cipher": "AES-256-GCM",
    "kdf": "PBKDF2-HMAC-SHA256",
    "kdf_iterations": 210000,
    "schema_version": 1
  },
  "messages_in_bundle": 2,
  "ok": true,
  "private_messages": 1,
  "social_activity": 1,
  "message_ids": [ "msg_...", "msg_..." ]
}
OK: the wrong passphrase is rejected.

RESULT: PASS - Android export -> Python import works end to end.
```

Android 端的測試：`DhcExportInstrumentedTest`（7 項）。

**兩邊的 `message_id` 完全一致** —— 這證明 contract 的雜湊實作也跨平台一致。

### 5.3 執行方式

```bash
# Android 端產生封包（會寫到裝置的 shared storage）
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=tw.personal.privateinbox.DhcExportInstrumentedTest

# 主機端解密並匯入
bash integrations/dhc/tests/host_interop.sh

# Python 端完整測試
python3 integrations/dhc/tests/test_connector.py
```

---

## 6. 尚未完成的部分

| 項目 | 狀態 | 說明 |
|------|------|------|
| Android 匯出 UI（SAF 選檔） | **未實作** | `DhcExporter` 已可用，但設定頁還沒有「匯出」按鈕與 Storage Access Framework 串接 |
| 照片附件 | **延後** | 契約已預留 `attachment_metadata`，但不傳位元組 |
| 匯出 passphrase 輸入 | **未實作** | 需要 UI 讓使用者輸入，且不能記錄 |
| DHC plugin | **未實作** | 契約與 importer 已就緒，DHC 側的 plugin 需另外開發（會是 AGPL） |
| 雙向同步 | **不屬本輪範圍** | 見 `docs/roadmap/MULTI-DEVICE-SYNC.md` |

---

## 7. 目錄

```
integrations/dhc/
├── protocol/
│   ├── contract.py        資料契約（雙方的唯一真相來源）
│   └── crypto.py          加密容器格式
├── importer/
│   └── importer.py        本機匯入 CLI + LocalStore
├── tests/
│   ├── test_connector.py           23 項 Python 測試
│   ├── python_interop_check.py    供 Android 測試呼叫的檢查腳本
│   └── host_interop.sh            主機端往返檢查
└── README.md             本文件
```
