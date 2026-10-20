# Rust PoC 測試計畫（RUST-POC-TEST-PLAN）

> **STATUS: FUTURE_RESEARCH**
>
> 這是 Phase 1（Rust Parser / Dedup PoC）的具體驗收計畫。
> 本輪**沒有實作**任何 Rust 程式碼。

---

## 1. PoC 的唯一目的

回答一個問題：

> **Rust 能不能產出與現有 Kotlin 實作完全一致的結果？**

如果不能，這條路徑就該停下來。這個計畫的全部內容都是為了讓
「能」或「不能」有明確的證據。

---

## 2. 範圍

### 做

| 函式 | 現有 Kotlin 位置 | 需要的 Rust crate |
|------|-----------------|-------------------|
| `normalize(value)` | `CapturePolicy.normalize()` | 無外部相依 |
| `computeMessageId(...)` | `DhcExporter.computeMessageId()` | `sha2` 或 `ring` |
| 分類決策 | `SourceRegistry.classify()` | 無外部相依 |

### 不做

- 不碰 JNI、UniFFI、Gradle 設定。
- 不碰 Android App 的任何一行。
- 不引入 SQLite、影像、序列化框架。
- 不改變任何現有行為。

---

## 3. 黃金測試集（Golden Corpus）

### 3.1 產生方式

由**現有的 Kotlin 實作**產生，作為對照組：

```
kotlin :app:testDebugUnitTest --tests '*CorpusGenerator*'
  → 產生 app/src/test/resources/golden/corpus.jsonl
```

每一行是一筆完整案例：

```json
{
  "case_id": "normalize-0001",
  "function": "normalize",
  "input": { "value": "  你好   世界 \r\n" },
  "expected": { "value": "你好 世界" }
}
```

### 3.2 案例覆蓋

| 函式 | 必測邊界 |
|------|----------|
| `normalize` | 空字串、純空白、只有換行、超過 20,000 字元、Unicode emoji、RTL 文字、組合字符、全形空白、零寬字元、單字元 |
| `computeMessageId` | 相同輸入必須相同 id、body 不同必須不同 id、時間戳不同必須不同 id、對話不同必須不同 id、空 body、長 body、非 ASCII body |
| `classify` | 每一個 `MessageKind` 至少 3 個案例、社交平台的 DM 與社交動態、沒有訊息容器的社交通知、mention 優先於 reply、未知 kind 落到 UNKNOWN |

**案例數目標：≥ 120 筆。**

### 3.3 語料來源

- **只用合成資料。** 不使用真實聊天內容。
- 刻意包含上面列出的所有邊界。
- 案例**凍結**在 repo 中，Rust 與 Kotlin 都必須通過同一份。

---

## 4. 驗收標準

### 4.1 Rust 側

```bash
cd rust-core
cargo test
```

- 每一筆黃金案例都有一個對應的 Rust 測試。
- 輸出**位元級相同**：同一個 SHA-256、同一個 base64url 字串。

### 4.2 跨語言差異測試

一個獨立程式，讀取黃金集並比較：

```
corpus.jsonl → Kotlin 參考輸出 (JSONL) ─┐
              → Rust 實際輸出   (JSONL) ─┴→ 逐筆比對 → 差異報告
```

**通過條件：0 筆差異。**

任何一筆差異都是 **FAIL**，不是 WARN。

### 4.3 CI 門檻

差異測試是**建置的硬性門檻**：任何差異都讓 CI 失敗。
這是為了確保 Rust 與 Kotlin 在往後的開發中**不會漂移**。

---

## 5. 產出物

```
rust-core/
├── Cargo.toml
├── src/
│   ├── lib.rs           normalize / computeMessageId / classify
│   └── ...
├── tests/
│   ├── golden.rs        讀取 corpus.jsonl 並逐筆比對
│   └── corpus.jsonl     凍結的黃金集
└── Makefile             或 justfile：test / gen-corpus / diff
```

---

## 6. 時間與資源估算

| 項目 | 估算 |
|------|------|
| crate 骨架 | 半天 |
| 三個函式的 Rust 實作 | 1 天 |
| 黃金集產生器（Kotlin） | 半天 |
| 黃金集（120+ 筆） | 半天 |
| Rust 單元測試 | 1 天 |
| 跨語言差異測試 | 1 天 |
| **合計** | **約 5 天** |

**成本很低，所以值得先做。** 但也因為成本低，
**不應該因為「看起來很酷」就做** —— 必須先有 Phase 5 的授權答案，
否則可能做出無法安全發布的東西。

---

## 7. 停止條件

出現以下任一情況，**停止**並回到 Kotlin：

1. Rust 無法在合理時間內做到位元級一致（通常意味著語意有隱藏差異，
   這本身就是有價值的發現，應記錄下來並修正 Kotlin 的隱晦之處）。
2. 授權問題無法在 Phase 5 得到可接受的答案。
3. 建置複雜度開始影響日常開發節奏（這是實際風險：Android + Rust
   兩套工具鏈的 CI 維護成本不低）。

---

## 8. 與本專案其他文件的關係

| 文件 | 關係 |
|------|------|
| `RUST-CORE-MIGRATION.md` | 總體架構與分階段計畫；本文件是 Phase 1 的細節 |
| `../engineering/PERFORMANCE-BASELINE.md` | 遷移前需要先有效能基準，才知道 Rust 有沒有實際收益 |
| `../verification/TEST-MATRIX.md` | 現有 95 項測試是遷移的「不能退步」基準 |
