# Roadmap（ROADMAP）

> 每一項都標明**狀態**與**證據**。
> 沒有實測證據的功能一律標 `NOT TESTED` 或 `RESEARCH`。

---

## 1. 已完成

| 項目 | 版本 | 證據 |
|------|------|------|
| 破壞性測試隔離 | v0.4.3 | devtest 119/119；prod 119 項 0 失敗 66 skip |
| 去重識別碼修正 | v0.4.3 | `DeduplicationIdentityTest` 8 項；`DEDUP-IDENTITY-AUDIT.md` |
| 擷取計數驗證 | v0.4.3 | `CaptureAccountingTest` 8 項 |
| WAL A/B 實驗 | v0.4.3 | 結論是**不啟用**；`WAL-EXPERIMENT.md` |
| 行程死亡實驗 | v0.4.3 | `drain_ms=5565`；`INTAKE-JOURNAL.md` |
| 熱路徑最佳化 | v0.4.4 | callback 623 ms → 4 ms；queue wait 33.9 s → 3.5 s |
| 寫入批次 | v0.4.4 | 500 則訊息的交易數 648 → 40 |
| Schema v5 複合索引 | v0.4.4 | `USING COVERING INDEX package_received_idx` |
| 去重黃金集 | v0.4.4 | 32 個案例，12 項測試 |
| 儲存基準 | v0.4.4 | 10,000 列 53.15 s，511 bytes/則 |
| 相容性分層 | v0.4.4 | `DeviceCapabilities.kt`；`COMPATIBILITY.md` |
| 開源文件 | v0.4.4 | README 中英、metadata、templates、授權稽核 |

---

## 2. 研究中（**沒有**接進正式管線）

### 2.1 持久化 Intake Journal

**狀態**：契約已寫下，遺失視窗已量到，**但沒有實作**。

| 已完成 | 未完成 |
|--------|--------|
| 狀態機與終態定義 | 實作 |
| 九項必須滿足的條件 | 與擷取管線整合 |
| 遺失視窗量測（5.6 秒）| 真機上的實際遺失量 |
| `RECEIVED`→`JOURNALED` 的不可消除缺口 | |

**為什麼不做**：證據不足以證明它有足夠改善，而一個寫錯的 journal
會讓擷取**整個停擺**。

**什麼情況下該做**：真機上量到實際的遺失。

→ [`reliability/INTAKE-JOURNAL.md`](reliability/INTAKE-JOURNAL.md)

### 2.2 Rust Core

**狀態**：研究。**沒有任何一行 Rust 在生產路徑裡。**

**結論：目前不需要 Rust。** 瓶頸是磁碟 I/O 與排程，不是運算。
唯一真正的跨語言一致性問題（base64 換行）已經用更好的測試解決了。

→ [`roadmap/RUST-CORE-ARCHITECTURE.md`](roadmap/RUST-CORE-ARCHITECTURE.md)
→ [`roadmap/RUST-BINDING-PLAN.md`](roadmap/RUST-BINDING-PLAN.md)
→ [`roadmap/RUST-DIFFERENTIAL-TESTS.md`](roadmap/RUST-DIFFERENTIAL-TESTS.md)

---

## 3. 設計中

| 項目 | 內容 | 位置 |
|------|------|------|
| 通知鏡像 | 把擷取到的通知映射成可瀏覽的時間軸 | `roadmap/` |
| 媒體擷取強化 | 圖片的完整處理流程 | `roadmap/` |
| 多裝置同步 | **設計中，沒有任何程式碼** | `roadmap/` |

> **多裝置同步需要網路，而本專案沒有網路權限。**
> 那是刻意的取捨，不是待辦事項。

---

## 4. 已知不做的事

| 項目 | 原因 |
|------|------|
| 聊天歷史還原 | 那是不同的產品，而且需要不同的權限 |
| 訊息傳送 / 回覆 | 這是**存檔器**，不是客戶端 |
| 雲端同步 | 與「沒有 INTERNET 權限」這個核心承諾衝突 |
| 全文搜尋（持久化）| 明文的 FTS 索引會洩漏整個訊息歷史。見 `STORAGE-BENCHMARK.md` §7 |
| 廣告 / 推薦 / 社交 | 不在這個專案的目的裡 |

---

## 5. 需要真機的事

| 項目 | 為什麼需要真機 |
|------|---------------|
| vivo OriginOS 的背景行為 | 模擬器不等於 OriginOS |
| 鎖屏 / 省電模式 | 只能��真機量 |
| 行程被殺的實際遺失 | 要真的被殺才量得到 |
| Android 9–15 | 需要那些版本的設備 |

→ [`reliability/X200PRO-NON-DESTRUCTIVE-QA.md`](reliability/X200PRO-NON-DESTRUCTIVE-QA.md)
