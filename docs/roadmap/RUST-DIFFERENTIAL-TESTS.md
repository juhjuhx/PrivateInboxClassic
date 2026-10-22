# Rust / Kotlin 差異測試設計（RUST-DIFFERENTIAL-TESTS）

> 狀態：**RESEARCH_ONLY** —— 沒有 Rust 程式碼存在。
> 這份文件描述**如果**要遷移，差異測試必須長什麼樣。

---

## 1. 為什麼差異測試是門檻

Rust 的價值主張是「同樣的邏輯，用 Rust 實作一次，在兩個地方跑」。
那個主張的**全部風險**都集中在「同樣」這兩個字上。

**如果 Kotlin 與 Rust 的結果不同，那個差異會是什麼？**

- 訊息 id 不同 → 匯入不冪等 → 匯入兩次產生兩份
- 對話身分不同 → 兩個對話被合併，或一個對話被拆開
- 分類不同 → 匯出契約相容性破壞

**所以差異測試不是「錦上添花」，它是 Rust 方案的前提條件。**

本專案已經有一次真實的跨語言缺陷：Android 的 base64 附加換行，
Python 端沒有，同一則訊息在兩邊的 id 不同。
那個缺陷**不是**被 Rust 修好的 —— 它是被**測試**修好的。
這本身就是一個論據：測試比換語言便宜，而且更有效。

---

## 2. 測試的形狀

### 2.1 黃金集

**Kotlin 的輸出是對照組**，凍結在 repository 裡。

```
app/src/test/resources/golden/
  dedup/case-a1.json
  dedup/case-a2.json
  ...
  conversation/case-b1.json
  conversation/case-b2.json
  classification/case-h1.json
  export/picbox01-001.json
```

每個檔案包含**輸入**與**期望輸出**。

**為什麼以 Kotlin 為基準**：Kotlin 實作是**已經上線**的。
它可能有錯，但它是**已知的**狀態。
Rust 實作若與它不同，差異必須被解釋，而不是被接受。

### 2.2 差異測試的執行

```
for each golden case:
    kotlin_result = kotlin_implementation(input)
    rust_result   = rust_implementation(input)
    assert kotlin_result == rust_result   # 位元級
```

**位元級**，不是「語意上相同」。
`message_id` 是一個 base64 字串，一個字元的差異就是不同的 id。

### 2.3 差異報告

**差異不應該讓測試直接失敗** —— 它應該產生一份報告，
讓人**解釋**每一個差異。

```
DifferentialReport {
  case: "dedup/A3",
  kotlin: "msg_7Hq2...",
  rust:   "msg_7Hq3...",
  explanation: "the discriminator is hashed with a different separator"
}
```

**解釋過的差異**是允許的。**沒解釋的差異**不是。

---

## 3. 必須涵蓋的案例

至少要涵蓋 `DedupGoldenCorpusTest` 的**同一組 32 個案例**，
外加：

| 群組 | 案例 |
|------|------|
| **正規化** | 空白、換行、emoji、組合字元、超長文字、RTL |
| **對話身分** | 短 ID 與長 ID、同一捷徑的不同格式、沒有捷徑 |
| **分類** | 群組摘要、附件、直接訊息、未知來源 |
| **匯出** | PICBOX01 的每一個欄位、每種訊息類型、邊界的時間值 |
| **加密封裝** | 相同的金鑰與明文 → **完全相同的密文**（GCM 的 IV 必須確定性導出）|
| **空與極端值** | 空字串、最大長度、Unicode 邊界 |

**加密那一項要特別小心**：GCM 的 IV 若隨機產生，
密文就不會位元級相同。那不是差異測試能比較的東西 ——
**比較的是明文往返**，不是密文。

---

## 4. 效能差異測試

| 測量 | 目的 |
|------|------|
| 單次呼叫的端到端時間 | JNI 邊界成本 |
| 100,000 則訊息的批次時間 | 實際工作負載 |
| 記憶體峰值 | Rust 的配置行為 |
| 二進位大小 | 這是使用者實際下載的東西 |

**量測方法**：端到端，不是微基準。至少 1000 次迭代，取 p50/p95/p99。

**Rust 必須是「不劣於」Kotlin**，而不是「平均更快」。
一個 p99 變差的 Rust 實作對這個專案**沒有價值** ——
這個 App 的問題是尾端延遲，不是平均值。

---

## 5. 影子模式

在切換之前，兩邊**同時**跑，Rust 的結果**不寫入**。

```kotlin
// 影子模式：Rust 的結果只被比對，不被使用
val kotlinResult = kotlinNormalize(input)
val rustResult = rustNormalize(input)
if (kotlinResult != rustResult) {
    ShadowDiff.report(case, kotlinResult, rustResult)
}
kotlinResult  // 正式路徑仍然是 Kotlin
```

影子模式要跑夠久才有意義：
**至少涵蓋一個完整的使用週期**（不同的來源、不同的訊息形狀、
尖峰與離峰）。

**在影子模式裡跑滿一個週期且零差異，才可以談切換。**

---

## 6. 切換的程序

1. Rust 通過**全部**黃金集，位元級一致。
2. 影子模式跑滿一個週期，零差異。
3. 用 feature flag 切**一個**函式，保留 Kotlin 為後備。
4. 量測。Kotlin 保留在後備至少一個版本。
5. 一次切一個函式。

**任何一步失敗就回滾**，而且回滾必須是**一個開關**，
不是一次程式碼還原。

---

## 7. 這個計畫的成本（誠實估算）

| 項目 | 成本 |
|------|------|
| 黃金集（32+ 案例 × 兩種語言）| 中等，且**不會過期** —— 它是規格 |
| Rust crate + UniFFI 綁定 | 中等 |
| 差異測試框架 | 中等 |
| 影子模式 | 需要一個版本的使用時間 |
| 團隊學習 Rust 與 FFI | **最高，而且不可量化** |
| 授權審查 | 一次性，但結論可能改變架構 |

**對照**：`DedupGoldenCorpusTest`（32 個案例）花了**一個工作階段**，
而且它**永遠**保護 Kotlin 實作。

**在影子模式的證據出現之前，Rust 是一個沒有收益證明的成本。**

---

## 8. 結論

> **差異測試是 Rust 方案的前提，不是它的後果。**
>
> 本專案已經有了 Kotlin 那一半（32 個黃金案例）。
> 那一半**立刻**就有價值 —— 它今天就保護著擷取管線，
> 無論未來用什麼語言。
>
> Rust 那一半**只在真的要遷移的那一天**才有價值。
> 在那之前，它是選項，不是投資。
