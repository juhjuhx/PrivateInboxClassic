# SQLite Storage（DATABASE）

> 實測數字見 [`reliability/STORAGE-BENCHMARK.md`](reliability/STORAGE-BENCHMARK.md)。

---

## 1. Schema

**版本 5。** 遷移**只增不改**。

```sql
CREATE TABLE messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    package_name TEXT NOT NULL,
    received_at INTEGER NOT NULL,
    source_ts INTEGER NOT NULL DEFAULT 0,
    conversation_key TEXT NOT NULL DEFAULT '',
    message_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
    fingerprint BLOB NOT NULL UNIQUE,
    sealed_content BLOB NOT NULL,
    sealed_image BLOB
);
```

| 欄位 | 意義 |
|------|------|
| `id` | 本機列號。**不是**跨裝置的身分 |
| `package_name` | 來源套件 |
| `received_at` | 擷取時間（毫秒） |
| `source_ts` | 來源提供的時間戳。**沒有就是 0，永不臆造** |
| `conversation_key` | 對話身分。**不是**顯示名稱 |
| `message_kind` | 分類 |
| `fingerprint` | HMAC-SHA256 去重鍵，**UNIQUE** |
| `sealed_content` | AES-256-GCM 密封的 JSON（title / sender / body / sourceTs）|
| `sealed_image` | 選擇性，密封的縮圖 |

---

## 2. 加密格式

| 項目 | 值 |
|------|-----|
| 演算法 | AES-256-GCM |
| 金鑰 | Android Keystore，**不離開裝置** |
| IV | 每筆訊息一個，隨密文一起存 |
| AAD | 綁定 `packageName` |
| 指紋 | HMAC-SHA256（金鑰導出），**不是**純雜湊 |

**為什麼指紋是 HMAC 而不是 SHA-256**：
純雜湊可以對低熵的訊息做字典攻擊 —— 嘗試所有可能的字串然後比對雜湊。
HMAC 帶著祕密的金鑰，攻擊者沒有它就算不出來。

**`fingerprint` 是 UNIQUE**，所以資料庫是防止重複的**第二道防線**。
第一道是擷取端的記憶體過濾。

---

## 3. 索引

```sql
CREATE INDEX received_idx             ON messages(received_at DESC)
CREATE INDEX conversation_idx         ON messages(conversation_key)
CREATE INDEX package_received_idx     ON messages(package_name, received_at DESC, id DESC)
CREATE INDEX conversation_received_idx ON messages(conversation_key, received_at DESC, id DESC)
CREATE INDEX kind_received_idx        ON messages(message_kind, received_at DESC, id DESC)
```

**複合索引為什麼存在**：列表查詢**同時篩選與排序**，
單欄位索引只能做其中一件事。複合索引讓 SQLite 直接用索引滿足
`ORDER BY`，**不需要排序步驟**。

實測計畫：

```
SEARCH messages USING COVERING INDEX package_received_idx (package_name=?)
```

`COVERING INDEX` 代表完全不必回資料表。

**單欄位索引保留**，因為「取最新一頁（不篩選來源）」與
「取某個對話的全部訊息」仍然用它們。

---

## 4. 遷移

**附加式。每一版只新增欄位或索引，永不回寫或刪除任何一列。**

| 版本 | 變更 |
|------|------|
| 1 | 初始 schema |
| 2 | `sealed_image`、`source_ts` |
| 3 | `source_ts`（補上 v2 漏掉的） |
| 4 | `conversation_key`、`message_kind`、`conversation_idx` |
| 5 | 三個複合索引 |

**v4 的回填說明**：`conversation_key` 沒有回填舊資料，
因為標題是加密的，遷移時讀不到。舊列的鍵是**在讀取時惰性重建**的。

---

## 5. 容量

| 指標 | 實測值 |
|------|--------|
| 每則訊息的磁碟 | **511 bytes**（10,000 則時）|
| 10,000 則 | 5.1 MB |
| 100,000 則（外推）| 約 51 MB |

寫入的**臨商**：一次交易寫入多則，所以一個批次只付**一次** fsync。

---

## 6. 保留策略

| 規則 | 說明 |
|------|------|
| 清理只在兩個條件下發生 | 使用者設定的保留期限，或使用者明確要求刪除 |
| **絕不**因為資料庫太大而自動刪除 | 資料保留政策由使用者控制 |
| 永久保存 | 見 `reliability/STORAGE-BENCHMARK.md` §6 —— 尚未實作，且本輪不做半成品 |

---

## 7. 匯出

匯出**不使用**資料庫的加密格式。它是另一個容器：

1. 用 Keystore 金鑰解密每一列。
2. 組成一個 `PRIVATE_INBOX_EXPORT_V1` 套件。
3. 用**使用者密碼**導出的金鑰重新加密。

**Keystore 金鑰不離開裝置，密碼不離開使用者的腦袋。**

匯出格式的契約在 `integrations/dhc/protocol/contract.py`，
它是**本專案**定義的，DHC 是採用的一方。
