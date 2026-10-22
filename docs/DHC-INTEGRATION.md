# DHC Integration（DHC-INTEGRATION）

> Private Inbox Classic 與 **DHC**（離線知識庫連接器）之間的資料交換。

---

## 1. 關係

```
   Private Inbox Classic                DHC
   (Android, 加密資料庫)     --檔案-->  (離線知識庫)
                              匯出套件
```

**兩邊交換的是資料契約，不是原始碼。**

這個方向是刻意的：契約由 Private Inbox 定義，DHC 採用它。
所以兩個專案可以各自授權，而且**一方被刪除不會讓另一方壞掉**。

---

## 2. 匯出流程

1. 使用者在 App 裡選來源、篩選條件與時間範圍。
2. App 用 Keystore 金鑰**解密**資料庫裡的列。
3. 組成 `PRIVATE_INBOX_EXPORT_V1` 套件（見 `protocol/contract.py`）。
4. 用**使用者提供��密碼**導出的金鑰**重新加密**。
5. 使用者用 SAF 選一個位置存檔。

**Keystore 金鑰不離開裝置。密碼不離開使用者的腦袋。**
App **不**把任何訊息交給網路服務。

---

## 3. 契約

| 項目 | 規則 |
|------|------|
| **版本化** | `SCHEMA_VERSION` 在匯入時被檢查。**未知的版本被拒絕，永遠不被猜測** |
| **冪等** | `message_id` 是內容衍生的穩定識別，所以匯入同一個套件兩次**不會**產生重複 |
| **自描述** | 匯入端需要的每個欄位都在套件裡 |
| **不依賴列號** | `message_id` **不是** Android 的 SQLite row id：row id 是單一裝置本地的，重裝或跨裝置就會撞號 |

### `message_id` 為什麼是內容衍生的

它必須在**兩台裝置**、**重裝之後**、**匯入兩次**之後都一樣。
只有從內容衍生的識別符才有這個性質。

---

## 4. 隱私

| 規則 | 說明 |
|------|------|
| 不列印訊息內容 | 摘要只有**計數**與**識別碼** |
| 不上傳 | 匯出是一個檔案，使用者決定放哪 |
| 不自動交給 DHC | 匯出是**使用者主動**的動作 |
| 沒有網路權限 | 兩個專案都是離線的 |

---

## 5. 版本相容性

**擴充契約時要新增版本，不是改變既有欄位的意義。**

| 需求 | 做法 |
|------|------|
| 加入分類 | 新增欄位 + `SCHEMA_VERSION` + 1 |
| 加入使用者標籤 | 同上 |
| 改變既有欄位的意義 | **不要**。那會讓舊的匯出套件被新匯入端誤解 |

DHC 的匯入端遇到未知的版本會**拒絕**並回報，
而不是猜測 —— 這是刻意的：猜測會把資料解讀成錯的意思。

---

## 6. 測試

| 測試 | 數量 | 指令 |
|------|------|------|
| 連接器（Python）| 23 | `python3 integrations/dhc/tests/test_connector.py` |
| 跨語言 fixture | 7 | `python3 integrations/dhc/tests/test_fixtures.py` |
| 契約相容性 | — | `python3 integrations/dhc/tests/test_contract.py` |

跨語言 fixture 測試是**兩邊都跑**的：Android 端產生匯出，
Python 端匯入，兩邊的識別符必須**完全一致**。

**這個測試在 v0.4.3 找到了一個真實缺陷**：Android 端的 base64 會附加換行，
Python 端不會，所以同一則訊息在兩邊的 id 不同，匯入就不是冪等的。
修法是統一用 `URL_SAFE|NO_PADDING|NO_WRAP` 並去除結尾的 `=` 與換行。

---

## 7. 授權

| 專案 | 授權 |
|------|------|
| Private Inbox Classic | MIT |
| DHC | **AGPL-3.0** |
| 匯出契約 | 定義在 Private Inbox（MIT）|

**因為契約在 MIT 這邊**，DHC 採用它**不構成**逆向的授權義務。
若未來要在 Rust Core 共用原始碼，邊界必須重新評估 ——
見 [`roadmap/RUST-CORE-ARCHITECTURE.md`](roadmap/RUST-CORE-ARCHITECTURE.md) §6。
