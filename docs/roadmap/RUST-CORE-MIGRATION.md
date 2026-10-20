# Rust 核心遷移（Rust Core Migration）

> **STATUS: FUTURE_RESEARCH**
>
> 本輪**沒有**改動任何生產程式碼。這份文件是研究與計畫。
> 現有的 Kotlin 實作**完全沒有被重寫**，運作中的版本不受影響。

---

## 1. 目標與範圍

使用者希望長期把部分核心邏輯逐步遷移到 Rust。

### 1.1 建議遷移到 Rust 的部分

| 模組 | 理由 |
|------|------|
| 訊息正規化 | 純函式、跨語言需要一致、規則明確 |
| 訊息分類 | 同上，且目前已有 `SourceRegistryTest` 20 項測試可作為契約 |
| 去重 | 雜湊演算法必須跨 Android / DHC 一致；Rust 與 Python 都容易實作 |
| 對話識別 | 純邏輯，需要與匯出契約共用同一份實作 |
| 可攜式事件 schema | 與 DHC 契約共用；一處定義、兩處使用 |
| DHC 同步協定 | 與桌面端共用核心，是 Rust 最直接的價值 |

### 1.2 明確**不**遷移的部分

| 模組 | 理由 |
|------|------|
| `NotificationListenerService` | Android 系統 API，Rust 無法直接使用 |
| `NotificationManager` | 同上 |
| `ContentResolver` / 影像讀取 | 需要 Android Context |
| Activity / UI | 不在範圍內 |
| **Android Keystore** | **必須留在 Kotlin。** 金鑰的操作透過 Android Keystore API；Rust 若要存取，需要 JNI 進入同一個 provider，複雜度與風險都不成比例 |

> **Keystore 留在 Kotlin 這件事是硬性邊界。** 一旦金鑰操作進入 Rust，
> 「金鑰不離開裝置」這個保證就需要額外論證。

---

## 2. 生態系調查（實測結果）

以 GitHub API 查證，非記憶：

| 專案 | Stars | 語言 | 授權 | 最後推送 | 狀態 |
|------|-------|------|------|----------|------|
| [mozilla/uniffi-rs](https://github.com/mozilla/uniffi-rs) | 5,039 | Rust | **MPL-2.0** | 2026-10-16 | 活躍 |
| [bbqsrc/cargo-ndk](https://github.com/bbqsrc/cargo-ndk) | 979 | Rust | Apache-2.0 | 2026-10-17 | 活躍 |
| [rusqlite/rusqlite](https://github.com/rusqlite/rusqlite) | 4,442 | Rust | MIT | 2026-10-18 | 活躍 |

### 2.1 授權注意

**UniFFI 是 MPL-2.0，不是 MIT 或 Apache-2.0。**
MPL-2.0 是「檔案層級的 copyleft」：使用 UniFFI 本身沒問題，
但**修改了 UniFFI 的檔案**就必須以 MPL-2.0 發布那些檔案。

實務上的影響：本專案**只使用** UniFFI 產生的綁定程式碼，不修改 UniFFI 本身，
所以 Private Inbox 的 MIT 授權不受影響。**但這個判斷必須在實際導入時重新確認。**

### 2.2 本機工具鏈（實測）

| 項目 | 狀態 |
|------|------|
| cargo / rustc | **1.97.1**，可用 |
| rustup | 可用 |
| 已安裝 target | `a64-apple-darwin`（**Android target 未安裝**） |
| Android NDK | 27.3.13750724、28.2.13676358（可用） |

Android target 需要另外安裝：

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

---

## 3. 技術選型

### 3.1 綁定層：UniFFI

| 選項 | 評估 |
|------|------|
| **UniFFI** | 為 Kotlin 產生綁定的成熟方案。**優點**：型別安全、自動產生 Kotlin 資料類別、錯誤以例外呈現。**缺點**：MPL-2.0、額外建置步驟 |
| 手寫 JNI | 完全控制，但型別安全與錯誤處理都要自己維護，錯誤面更大 |
| C ABI + 手動封裝 | 最少相依，但最容易出錯 |

**建議：UniFFI。** 理由是本專案最在意的正確性 —— 錯誤處理與型別安全
比建置複雜度重要。

### 3.2 交叉編譯：cargo-ndk

`cargo-ndk` 處理 NDK toolchain 的尋找與 target triple 對應。
本機已有 NDK 27 與 28，可直接使用。

### 3.3 資料庫：rusqlite

若 Rust 核心需要直接存取 SQLite，rusqlite 是 MIT 授權、活躍維護的選擇。
**但本專案目前不需要**：資料庫存取仍在 Kotlin，Rust 核心只做純邏輯。

**建議：第一階段不引入 rusqlite。** 等 Rust 核心真的需要碰資料庫再說。

---

## 4. 分階段計畫

### Phase 1 — Rust Parser / Dedup PoC

**目標**：證明 Rust 能產出與 Kotlin **完全一致**的結果。

- 建立 `rust-core/` crate，實作 `normalize()` 與 `computeMessageId()`。
- 不接進 App，只是一個可獨立執行的測試程式。
- 產出**黃金測試集（golden corpus）**：從現有的合成測試資料產生
  固定輸入 → 固定輸出的配對。
- **驗收**：Rust 對黃金集的每一筆輸出，都與 Kotlin 的既有結果**位元級相同**。

**不做**：任何 JNI、任何 UniFFI、任何 App 內整合。

### Phase 2 — UniFFI Kotlin 綁定

- 用 UniFFI 把 Phase 1 的 crate 包成 Kotlin 可呼叫的介面。
- Kotlin 端先以**影子模式（shadow mode）**呼叫：同一筆輸入同時餵給 Kotlin
  與 Rust，比對結果，把差異記錄下來。
- App 仍然使用 Kotlin 的實作。

**不做**：切換生產路徑。

### Phase 3 — 黃金集差異測試

- 擴大黃金集，涵蓋真實通知形狀的多種變化。
- 把差異測試納入 CI，成為**建置的門檻**：任何差異都讓建置失敗。
- 在 CI 之外，於實體裝置上跑同一套差異測試。

### Phase 4 — 漸進式生產路由

- 一次只切換**一個**純函式。
- 每次切換都保留 Kotlin 實作為 feature flag 的另一個分支，
  至少一個版本後才移除。
- 切換後必須重新跑完整測試套件（目前 53 項 instrumentation + 42 項 unit）。

### Phase 5 — DHC 共用核心評估

- 評估 DHC 端（TypeScript / Node）是否值得共用 Rust 核心。
- **注意**：DHC 是 AGPL-3.0。若共用 Rust 原始碼，該核心必須是 AGPL，
  這會影響 Private Inbox 的 MIT 核心邊界。
- **可能的解法**：Rust 核心獨立發布（例如 Apache-2.0），DHC 以
  process 或動態連結的方式使用它，而不是複製原始碼。
- **這個授權問題必須在動工前解決，不是實作時再說。**

---

## 5. 測試要求

**契約先於實作。** 在寫任何 Rust 之前，先定義：

```text
輸入：來源套件名、對話標題、寄件人、內文、原始時間戳
輸出：正規化後的欄位、分類結果、訊息 id、對話 id
```

然後：

1. Kotlin 現有實作產生黃金集。
2. Rust 實作必須對每一筆產生**完全相同**的輸出。
3. 差異 = 失敗。任何差異都不得進入正式通知管線。

### 5.1 Rust 單元測試

- `cargo test` 涵蓋與 Kotlin 相同的邊界案例
  （空白、超長、無時間戳、Unicode、Emoji、RTL）。
- 與 Kotlin 測試**一對一對應**，方便逐條比對。

### 5.2 跨語言一致性測試

- 同一組輸入 → Kotlin 輸出與 Rust 輸出 → 位元級比對。
- 在 CI 中執行，任何差異讓建置失敗。
- 在實體裝置上執行同一套（透過 instrumentation）。

---

## 6. 風險

| 風險 | 評估 | 緩解 |
|------|------|------|
| 授權污染 | **高** | Phase 5 之前必須解決；Rust 核心獨立發布 |
| 建置複雜度上升 | 中 | UniFFI + cargo-ndk 都是成熟方案；CI 需要快取 |
| 除錯困難 | 中 | 影子模式期間兩邊都有日誌；Rust 崩潰會帶JNI 符號 |
| 二進位大小 | 低 | 只遷移純邏輯，不帶 SQLite/影像函式庫 |
| 收益不明確 | **中** | Kotlin 實作目前**正確且已測試**。Rust 的價值主要在 DHC 共用 |
| Keystore 邊界 | 低 | 明確不遷移 Keystore |

**最大的一點**：目前 Kotlin 實作是**正確且有 95 項測試覆蓋**的。
遷移的收益必須明確大於「引入一個新的語言與建置系統」的風險，
否則不值得做。

---

## 7. 結論

- **現有 Kotlin 實作不需要被重寫**，它在 53 項 instrumentation 測試
  與 42 項單元測試下運作正常。
- 真正值得 Rust 化的，是**需要跨 Android 與桌面端共用**的那部分
  （去重、分類、事件 schema）。
- **Phase 1（PoC + 黃金集）成本低、風險小**，可以先做來取得真實資料，
  再決定是否繼續。
- **Phase 5 的授權問題必須提前解決**，不能留到最後。
