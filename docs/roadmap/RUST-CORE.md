# Rust 核心（RUST-CORE）

> **STATUS: FUTURE_RESEARCH**
>
> 本輪**沒有**改動任何生產程式碼。
> 現有的 Kotlin 實作**完全沒有被重寫**，運作中的版本不受影響。

---

## 1. 目標

把**跨 Android 與桌面端需要共用**的那部分邏輯，逐步遷移到 Rust。

### 1.1 建議遷移

| 模組 | 理由 |
|------|------|
| 訊息正規化 | 純函式、跨語言需要一致、規則明確 |
| 訊息分類 | 同上，且已有 `SourceRegistryTest` 20 項測試可作為契約 |
| 去重識別碼 | **本輪已證明跨語言一致性是會出錯的地方**（見 §4.1） |
| 對話識別 | 純邏輯，需要與匯出契約共用同一份實作 |
| 可攜式事件 schema | 與 DHC 契約共用；一處定義、兩處使用 |

### 1.2 明確**不**遷移

| 模組 | 理由 |
|------|------|
| `NotificationListenerService` | Android 系統 API，Rust 無法直接使用 |
| `NotificationManager` | 同上 |
| `ContentResolver` / 影像讀取 | 需要 Android Context |
| Activity / UI | 不在範圍內 |
| **Android Keystore** | **必須留在 Kotlin。** 金鑰操作透過 Android Keystore API；Rust 若要存取需要 JNI 進入同一個 provider，複雜度與風險都不成比例 |

> **Keystore 留在 Kotlin 是硬性邊界。**
> 一旦金鑰操作進入 Rust，「金鑰不離開裝置」這個保證就需要額外論證。

---

## 2. 生態系調查（實測）

以 GitHub API 查證，非記憶：

| 專案 | Stars | 授權 | 最後推送 | 狀態 |
|------|-------|------|----------|------|
| [mozilla/uniffi-rs](https://github.com/mozilla/uniffi-rs) | 5,042 | **MPL-2.0** | 2026-10-16 | 活躍 |
| [bbqsrc/cargo-ndk](https://github.com/bbqsrc/cargo-ndk) | 979 | Apache-2.0 | 2026-10-17 | 活躍 |
| [rusqlite/rusqlite](https://github.com/rusqlite/rusqlite) | 4,443 | MIT | 2026-10-18 | 活躍 |

### 2.1 授權注意：UniFFI 是 MPL-2.0

**不是** MIT 或 Apache-2.0。MPL-2.0 是「檔案層級的 copyleft」：
使用 UniFFI 本身沒問題，但**修改了 UniFFI 的檔案**就必須以 MPL-2.0 發布那些檔案。

實務影響：本專案**只使用** UniFFI 產生的綁定程式碼，不修改 UniFFI 本身，
所以 Private Inbox 的 MIT 授權不受影響。
**但這個判斷必須在實際導入時重新確認。**

### 2.2 本機工具鏈（實測）

| 項目 | 狀態 |
|------|------|
| cargo / rustc | **1.97.1**，可用 |
| rustup | 可用 |
| 已安裝 target | `aarch64-apple-darwin`（**Android target 未安裝**）|
| Android NDK | 27.3.13750724、28.2.13676358（可用）|

Android target 需要另外安裝：

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

---

## 3. 技術選型

| 選項 | 評估 |
|------|------|
| **UniFFI** | 為 Kotlin 產生綁定的成熟方案。**優點**：型別安全、自動產生 Kotlin 資料類別、錯誤以例外呈現。**缺點**：MPL-2.0、額外建置步驟 |
| 手寫 JNI | 完全控制，但型別安全與錯誤處理都要自己維護，錯誤面更大 |
| C ABI + 手動封裝 | 最少相依，但最容易出錯 |

**建議：UniFFI。** 理由是本專案最在意的正確性 —— 錯誤處理與型別安全
比建置複雜度重要。

**rusqlite**：若 Rust 核心需要直接存取 SQLite。
**但本專案目前不需要** —— 資料庫存取仍在 Kotlin，Rust 核心只做純邏輯。
**建議：第一階段不引入。**

---

## 4. 遷移的真正理由：跨語言一致性

本輪的 DHC 跨語言測試找到一個**真實的缺陷**：

```
python:  msg_vAqh2ijUIiDWkO4grhsE
android: msg_vAqh2ijUIiDWkO4grhsE\n
```

Android 的 `Base64` 預設會附加**換行**，Python 的不會。
**摘要本身完全相同**，但結果差一個字元。

後果不是美觀問題：同一則訊息在兩邊會得到**不同的 id**，
所以**匯入不再是冪等的** —— 同一則訊息會在匯入端存成兩列。

這個缺陷**只**在跨語言測試裡才會出現。
任何只看單一邊的測試都看不到它。

**這是 Rust 遷移最強的理由**：把兩邊共用的部分變成**一份實作**，
這個類別的缺陷就不可能再發生。

Rust 與 Python 在這裡特別有利：兩邊都有 `sha2`、`base64` 與
`getrandom` 的成熟實作，而且 **`base64` 的換行行為在兩邊都是可設定的**。

---

## 5. 分階段計畫

### Phase 1 — Rust PoC

**目標**：證明 Rust 能產出與 Kotlin **完全一致**的結果。

- 建立 `rust-core/` crate，實作 `normalize()` 與 `computeMessageId()`。
- **不**接進 App，只是一個可獨立執行的測試程式。
- 產出**黃金測試集（golden corpus）**：從現有的合成測試資料產生
  固定輸入 → 固定輸出的配對。
- **驗收**：Rust 對黃金集的每一筆輸出，都與 Kotlin 的既有結果**位元級相同**。

**不做**：任何 JNI、任何 UniFFI、任何 App 內整合。

### Phase 2 — UniFFI Kotlin 綁定

- 用 UniFFI 把 Phase 1 的 crate 包成 Kotlin 可呼叫的介面。
- Kotlin 端先以**影子模式（shadow mode）**呼叫：同一筆輸入同時餵給
  Kotlin 與 Rust，比對結果，把差異記錄下來。
- App 仍然使用 Kotlin 的實作。

**不做**：切換生產路徑。

### Phase 3 — 黃金集差異測試

- 擴大黃金集，涵蓋真實通知形狀的多種變化。
- 差異測試納入 CI，成為**建置的門檻**：任何差異都讓建置失敗。
- 在 CI 之外，於實體裝置上跑同一套差異測試。

### Phase 4 — 漸進式生產路由

- 一次只切換**一個**純函式。
- 每次切換都保留 Kotlin 實作為 feature flag 的另一個分支，
  至少一個版本後才移除。
- 切換後必須重新跑完整測試套件。

### Phase 5 — DHC 共用核心評估

- 評估 DHC 端（TypeScript / Node）是否值得共用 Rust 核心。
- **注意**：DHC 是 AGPL-3.0。若共用 Rust 原始碼，該核心必須是 AGPL，
  這會影響 Private Inbox 的 MIT 核心邊界。
- **可能的解法**：Rust 核心獨立發布（例如 Apache-2.0），DHC 以
  process 或動態連結的方式使用它，而不是複製原始碼。
- **這個授權問題必須在動工前解決，不是實作時再說。**

---

## 6. Golden Corpus 差異測試計畫

### 6.1 語料來源

- **只用合成資料。** 不使用真實聊天內容。
- 刻意包含所有邊界：空白、超長、Unicode、Emoji、RTL、零寬字元。
- 案例**凍結**在 repo 中，Rust 與 Kotlin 都必須通過同一份。

### 6.2 覆蓋範圍

| 函式 | 必測邊界 |
|------|----------|
| `normalize` | 空字串、純空白、只有換行、超過 20,000 字元、Unicode emoji、RTL 文字、組合字符、全形空白、零寬字元、單字元 |
| `computeMessageId` | 相同輸入相同 id、body 不同必須不同、對話不同必須不同、來源不同必須不同、時間戳相同、空 body、長 body、非 ASCII body、**base64 換行行為** |
| `classify` | 每一個 `MessageKind` 至少 3 例、社交平台的 DM 與社交動態、沒有訊息容器的社交通知、mention 優先於 reply、未知 kind 落到 UNKNOWN |

**案例數目標：≥ 120 筆。**

### 6.3 差異測試的要求

Rust 與 Kotlin 的輸出必須**位元級相同**。
任何差異都是 **FAIL**，不是 WARN。

**特別注意 base64 的換行**：這正是本輪找到的缺陷。
黃金集必須包含 base64 的各���編碼變體，
並且兩邊的實作都必須**明確**設定換行行為。

---

## 7. 測試要求

| 層級 | 要求 |
|------|------|
| Rust 單元測試 | 與 Kotlin 測試**一對一對應**，方便逐條比對 |
| 跨語言一致性 | 同一組輸入 → 兩邊輸出 → 位元級比對。CI 門檻 |
| 實機 | 同一套差異測試在實體裝置上執行 |

---

## 8. 風險

| 風險 | 評估 | 緩解 |
|------|------|------|
| 授權污染 | **高** | Phase 5 之前必須解決；Rust 核心獨立發布 |
| 建置複雜度上升 | 中 | UniFFI + cargo-ndk 都是成熟方案；CI 需要快取 |
| 除錯困難 | 中 | 影子模式期間兩邊都有日誌；Rust 崩潰會帶 JNI 符號 |
| 二進位大小 | 低 | 只遷移純邏輯，不帶 SQLite / 影像函式庫 |
| 收益不明確 | **中** | Kotlin 實作目前**正確且有 100 項測試覆蓋** |
| Keystore 邊界 | 低 | 明確不遷移 Keystore |

**最大的一點**：目前 Kotlin 實作是**正確且有 100 項測試覆蓋**的。
遷移的收益必須明確大於「引入一個新的語言與建置系統」的風險。

**唯一已經證實的收益**是 §4 的跨語言一致性 ——
但那個問題**也可以**用更好的跨語言測試解決，不一定需要 Rust。
所以 Rust 遷移的**優先級低於**把跨語言測試做扎實。

---

## 9. 結論

- 現有 Kotlin 實作**不需要被重寫**，它在 100 項測試下運作正常。
- Rust 的價值主要在**跨語言共用**，而本輪已證明這個問題真實存在。
- **Phase 1（PoC + 黃金集）成本低、風險小**，可以先做來取得真實資料。
- 但在 Phase 1 之前，**先把跨語言測試做完整** ——
  那比較便宜，而且能立刻防止同類缺陷再次發生。
- **Phase 5 的授權問題必須提前解決。**
