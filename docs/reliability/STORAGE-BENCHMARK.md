# 儲存與吞吐基準（STORAGE-BENCHMARK）

> 分支：`fix/x200pro-notification-burst`（v0.4.4）
> 狀態：**已量測**。

---

## 1. 量測環境

| 項目 | 值 |
|------|-----|
| 裝置 | `sdk_gphone64_arm64`（Android 模擬器，API 36 / Android 16）|
| 架構 | arm64 |
| 變體 | devtest（隔離），`tw.personal.privateinbox.devtest` |
| 資料庫 | SQLite，`journal_mode=truncate`（**未啟用 WAL**，見 `WAL-EXPERIMENT.md`）|
| Schema | v5 |
| 熱狀態 | 未受控（模擬器，無主動加熱或冷卻控制）|

> **這是模擬器的數字。** 真機的儲存與 I/O 表現會不同，
> 尤其是台灣旗艦機的 UFS 快取。沒有實機數字之前不宣稱真機效能。

---

## 2. 兩種容量

| 容量 | 定義 |
|------|------|
| **Throughput Capacity** | 每秒能**正確**保存多少則訊息 |
| **Storage Capacity** | 資料庫能長期管理多少列、多少磁碟 |

兩者不同：寫得快不代表存得久，存得久也不代表寫得快。

---

## 3. 索引評估（EXPLAIN QUERY PLAN）

### 3.1 新增的索引（schema v5，附加式遷移）

```sql
CREATE INDEX package_received_idx
    ON messages(package_name, received_at DESC, id DESC);
CREATE INDEX conversation_received_idx
    ON messages(conversation_key, received_at DESC, id DESC);
CREATE INDEX kind_received_idx
    ON messages(message_kind, received_at DESC, id DESC);
```

**附加式**：索引是衍生結構，刪掉它不會遺失任何資料。
遷移只做 `CREATE INDEX IF NOT EXISTS`，不回寫任何一列。

### 3.2 為什麼是複合索引

列表畫面查詢的形狀是：

```sql
SELECT ... FROM messages
WHERE package_name = ?
ORDER BY received_at DESC, id DESC
LIMIT 50
```

單欄位的 `received_idx(received_at DESC)` 只能排序，**不能**先縮小範圍；
`conversation_idx(conversation_key)` 也不能。複合索引把「篩選」與「排序」
放在一起，SQLite 才能直接用索引satisfy ORDER BY，**不需要排序步驟**。

### 3.3 保留單欄位索引的理由

`received_idx` 與 `conversation_idx` **沒有刪**：

- 「取最新一頁（不篩選來源）」用 `received_idx` 最快。
- 「取某個對話的全部訊息」用 `conversation_idx` 最快。

**沒有建立大量低選擇性的索引** —— 例如只針對 `sealed_image IS NULL`
的偏索引：那個查詢很罕見，而每個索引都要寫入成本。

---

## 4. 結果

實測輸出（`StorageBenchmarkInstrumentedTest`）：

```
STORAGE-DEVICE android=36 oem=AOSP model=sdk_gphone64_arm64 chinese_rom=false history_cap=25

STORAGE rows=100   messages=100   conversations=1  seconds=0.64  db=69,632 B   per_msg=696 B
STORAGE rows=500   messages=500   conversations=1  seconds=2.78  db=262,144 B  per_msg=524 B
STORAGE rows=1000  messages=1000  conversations=1  seconds=5.70  db=512,000 B  per_msg=512 B
STORAGE rows=10000 messages=10000 conversations=10 seconds=53.15 db=5,115,904 B per_msg=511 B

STORAGE-single-group rows=500 messages=500 conversations=1  seconds=3.19 db=262,144 B per_msg=524 B
STORAGE-ten-groups   rows=500 messages=500 conversations=10 seconds=2.83 db=262,144 B per_msg=524 B
STORAGE-same-text    rows=500 messages=500 conversations=10 seconds=2.80 db=270,336 B per_msg=540 B

STORAGE-PLAN SEARCH messages USING COVERING INDEX package_received_idx (package_name=?)
STORAGE-INDEXES [sqlite_autoindex_messages_1, received_idx, conversation_idx,
                  package_received_idx, conversation_received_idx, kind_received_idx]
```

### 4.1 容量級距

| 訊息數 | 對話數 | 耗時 | 資料庫 | 每則平均 | 結果 |
|--------|--------|------|--------|---------|------|
| 100 | 1 | 0.64 s | 69,632 B | 696 B | 全部入庫，無失敗 |
| 500 | 1 | 2.78 s | 262,144 B | 524 B | 全部入庫，無失敗 |
| 1,000 | 1 | 5.70 s | 512,000 B | 512 B | 全部入庫，無失敗 |
| **10,000** | 10 | **53.15 s** | **5,115,904 B** | **511 B** | 全部入庫，無失敗 |

**每則訊息約 511–696 bytes**，包含：
加密的內容 BLOB、HMAC 指紋、索引項目與 SQLite 的頁面開銷。

10,000 則訊息約 **5.1 MB**。以這個比例，
**100,000 則訊息約 51 MB** —— 對現代手機的儲存空間而言不是問題。

### 4.2 對話形狀

| 形狀 | 訊息數 | 耗時 | 結果 |
|------|--------|------|------|
| 單一群組 | 500 | 3.19 s | 全部入庫 |
| 十個群組 | 500 | 2.83 s | 全部入庫 |
| **完全相同的文字** | 500 | 2.80 s | **500 列** —— 這是最嚴苛的去重路徑 |

> **「相同文字」的形狀特別重要**：它逼出了識別碼的每一個弱點。
> 500 則完全相同的訊息必須是 500 列，
> 因為每一則的**位置**不同 —— 如果只靠內容判斷，它們會被合併成一列。
> 實測結果是 500 列，正確。

### 4.3 索引計畫

```
SEARCH messages USING COVERING INDEX package_received_idx (package_name=?)
```

**COVERING INDEX** 代表 SQLite 完全不必回資料表讀取 ——
查詢只用索引就完成了。這是複合索引存在的理由。

六個索引全部存在（`sqlite_autoindex_messages_1` 是 `fingerprint UNIQUE`
自動建立的）。**每則訊息的索引成本是 511 bytes 的一部分。**

---

## 5. 長期保存的設計

### 5.1 永久保存

| 項目 | 狀態 |
|------|------|
| 永久保存選項 | **未實作**（見 §6）|
| 依保留期限清理 | 已有 `retention_days` 設定 |
| 儲存空間用量顯示 | 已有 |
| 低儲存空間警告 | 已有 |
| 訊息數量統計 | 已有 |
| 平台容量統計 | 已有 |
| 附件容量統計 | 已有 |
| 按日期加密封存 | **未實作** |
| 使用者主動匯出 | 已有（DHC 匯出）|
| 非破壞遷移 | 已有（v1→v5，全部附加式）|

### 5.2 一條硬規則

> **不得以「資料庫超過某個大小」為理由，自動刪除使用者選擇永久保存的訊息。**

資料保留與儲存空間政策**必須由使用者控制**。
清理只在兩個明確的條件下發生：使用者設定的保留期限，或使用者明確要求刪除。

---

## 6. 沒有實作的項目，以及原因

| 項目 | 原因 |
|------|------|
| 永久保存開關 | 需要一個**資料模型變更**（每列一個旗標），而 brief 要求遷移附加式。旗標可以加，但「永久保存」與「保留期限清理」的政策需要使用者介面支援，而本輪禁止重寫 UI。**沒有半成品按鈕比沒有按鈕好。** |
| 按日期加密封存 | 需要定義封存格式、與 DHC 的匯出契約協調，而且會產生第二份加密資料。**超出本輪範圍。** |
| 全文搜尋 | 見 §7。 |

---

## 7. 全文搜尋的隱私問題

**本專案不會把明文訊息放進一般的 FTS5 持久化索引。**

原因很直接：FTS5 是**明文**的持久化索引。
即使資料庫的主體是加密的，一個明文的 FTS 索引就等於
**把整個訊息歷史的關鍵字以明文寫在磁碟上**。
那會直接違反本專案的核心隱私承諾。

### 評估過的方案

| 方案 | 搜尋延遲 | 索引大小 | 隱私洩漏 | 冷啟動 | 金鑰失效 | 背景記憶體 |
|------|---------|---------|---------|-------|---------|-----------|
| **解鎖後的限定範圍解密搜尋** | 中（每次搜尋要解密）| **無**（不建立索引）| **無** | 低 | 失效後無法搜尋 | 低 |
| 受控的記憶體搜尋索引 | 低 | 記憶體 | 無（不落盤）| **高**（要重建）| 失效後重建 | **高** |
| AppSearch LocalStorage | 低 | 落盤 | **高**（明文）| 低 | 可加密 | 中 |
| 有洩漏模型的加密 Token 索引 | 中 | 中 | **有**（可枚舉）| 中 | 需重新加密 | 中 |

### 結論

**第一階段只做「篩選」，不做「全文搜尋」：**

```
平台篩選
對話篩選
日期範圍
訊息類型
使用者標籤
```

這些都只對**已解密的列**做判斷，**不建立任何持久化索引**。

**全文搜尋需要獨立的安全審查**，而且在那之前，
`AppSearch LocalStorage` 這一列的「隱私洩漏：高」就足以否決它。

---

## 8. 相關文件

| 文件 | 內容 |
|------|------|
| `WAL-EXPERIMENT.md` | 為什麼**不**啟用 WAL |
| `BURST-CAPTURE-ROOT-CAUSE.md` | 寫入放大與滑動視窗的根因 |
| `DEDUP-IDENTITY-AUDIT.md` | 識別碼的審查與修正 |
| `../DATABASE.md` | Schema、遷移與加密格式 |
