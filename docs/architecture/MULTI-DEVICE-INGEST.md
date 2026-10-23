# 多裝置彙整契約 v0.4.5（Multi-Device Ingest Contract）

> **STATUS: CONTRACT_DEFINED — 匯入端冪等合併已由既有程式碼保證；裝置間傳輸不在本輪範圍。**
>
> 本文件定義：兩台裝置各自匯出的 DHC 套件，在被第三個匯入端（或未來的
> 同步層）合併時，必須滿足的合併語意。**沒有**新增任何傳輸、配對或網路功能。

---

## 0. 範圍

| 做 | 不做 |
|----|------|
| 定義兩個匯出套件合併時的衝突規則 | 不做裝置間自動同步 |
| 驗證 `message_id` 跨裝置穩定 | 不做配對、金鑰交換 |
| 說明 Keystore 指紋為何不能跨裝置比較 | 不新增 INTERNET 權限 |
| 說明增量游標在多來源下如何取交集 | 不改變 DHC v1 契約欄位的意義 |

自動同步的完整規劃（配對、E2EE、墓碑、撤銷）見
`docs/roadmap/MULTI-DEVICE-SYNC.md`，本輪維持 PLANNING_ONLY。

---

## 1. 前提：兩個不同裝置的加密層是隔離的

| 事實 | 結論 |
|------|------|
| 每台裝置的 `CryptoStore` 金鑰在**自己的 Android Keystore**裡 | 同一則訊息在兩台裝置上密文不同 |
| `fingerprintInput()` 用的是**本機**金鑰的 HMAC | 指紋**不能**跨裝置比較 |
| 匯出套件用**使用者密碼**重新加密，與 Keystore 無關 | 套件可以離開裝置 |

所以：**跨裝置的識別只能靠套件內容本身的欄位**，也就是
`contract.compute_message_id()` 衍生的 `message_id` ——
它是 `conversation_id + source_application + source_timestamp + body`
的 SHA-256，純內容衍生，與任何金鑰、任何資料庫 row id 無關。

## 2. 合併語意（merge semantics）

匯入端（`integrations/dhc/importer/importer.py`）已經實作的規則：

| 情況 | 規則 | 依據 |
|------|------|------|
| 兩個套件含同一 `message_id` | **保留一筆**，`duplicates_skipped` 計數 +1 | `message_id TEXT PRIMARY KEY` + 匯入前 `contains()` 檢查 |
| 同一對話、同一時間、內容不同的兩則訊息 | **兩筆都保留**（id 因 body 不同而不同） | `compute_message_id` 把 body 納入材料 |
| 內容相同、時間戳不同（例如來源重新送達） | **兩筆都保留** | 時間戳在 id 材料裡；不猜測哪個「正確」 |
| 匯入同一套件兩次 | 冪等，零新增 | 同一 `message_id` |
| 匯入順序顛倒 | 結果集合相同 | 合併是集合聯集，不是序列重播 |

**不做**內容層級的自動合併或去重猜測：訊息是原始紀錄。
如果兩台裝置對「同一則」訊息記錄了不同的 body（來源端編輯、截斷差異），
那是**兩筆紀錄**，交給使用者判讀。

## 3. 裝置歸屬（provenance）

每個套件攜帶 `source_device_id`（`DhcExporter.deviceId()` 產生的隨機 UUID，
存在 app 的 SharedPreferences，**不是** Android ID、**不是** Keystore 金鑰）。
匯入端把它與 `export_id`、`export_created_at` 一起保存在套件層級。

合併後的資料庫因此可以回答「這筆是從哪台裝置的匯出來的」，
但**不需要**也**不應該**回答「哪台裝置的紀錄比較權威」——沒有權威。

## 4. 增量游標在多來源下的行為

`cursor = "<received_at>:<message_id>"`（`DhcExportService` 產生）。

| 情況 | 行為 |
|------|------|
| 單一裝置連續匯出 | cursor 單調前進，套件是增量切片 |
| 兩台裝置各自的 cursor | **互不相干**；合併端對每個 `source_device_id` 各自追蹤一個 cursor |
| cursor 遺失 | 匯出退化為全量；合併端靠 `message_id` 冪等吸收，**不會**產生重複 |

游標從不參與識別 —— 它只決定「這次匯出包含哪些列」，
識別永遠由 `message_id` 決定。所以游標錯了最多造成重複傳輸，不會造成資料錯誤。

## 5. 驗證方式

`integrations/dhc/tests/test_connector.py` 已涵蓋：同一套件重複匯入、
跨套件重複訊息、未知版本拒絕。跨裝置合併的具體 PoC（兩份合成匯出、
交集、聯集、順序顛倒）按 `docs/v045-research.md` 的測試策略執行；
在真機雙裝置環境可用前（vivo X200 Pro = NOT_TESTED），
PoC 以合成套件為準，不宣稱已驗證真機行為。

## 6. 明確不做的事（本輪）

- 不做配對、不做 QR 碼、不做裝置間金鑰交換。
- 不做墓碑同步（刪除仍只發生在本機、本裝置）。
- 不做把兩台裝置的 Keystore 指紋「對齊」的任何機制 —— 那在密學上不可能，
  在產品上也不需要：識別靠內容衍生的 `message_id`。
- 不改動 `SCHEMA_VERSION`：合併語意是匯入端既有的行為，
  本文件把它寫下來，不是把它改掉。
