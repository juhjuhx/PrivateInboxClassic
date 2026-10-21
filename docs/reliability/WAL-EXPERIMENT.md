# SQLite WAL 實驗（WAL-EXPERIMENT）

> 分支：`fix/x200pro-notification-burst`（v0.4.3）
> 結論：**WAL 未被啟用** —— 資料顯示它對這個工作負載沒有改善。
> 實驗與基準保留在專案裡。

---

## 1. 為什麼要實驗

上一輪的狀況報告記錄了兩件事：

1. 資料庫的 `journal_mode` 是 **`truncate`**，不是 WAL。
2. 兩個 writer × 150 列 = 300 列，**0 失敗、0 遺失**。

第 2 點讓人放心，但也讓人懷疑：**那 3 秒的 busy timeout 是不是全靠
SQLite 的預設在撐？** 如果是，那麼在寫入變慢時（大量圖片、加密），
UI 的讀取會被寫入卡住。

所以這一輪做了 **A/B 實驗**，而不是直接開 WAL。

---

## 2. 實驗方法

`WalBenchmarkInstrumentedTest` 對**同一個工作負載**跑兩種模式，
量測完全相同的指標：

| 指標 | 意義 |
|------|------|
| `writes` | 寫入嘗試的總數 |
| `failures` | 寫入失敗的數量 |
| `busy` | 其中是 `SQLITE_BUSY` 的數量 |
| `p50 / p95 / p99 / max` | 單次寫入的延遲（毫秒）|
| `open_ms` | 開啟資料庫的時間 |

模式是透過 `PRAGMA journal_mode` 設定在**檔案**上，並且**讀回實際生效
的值** —— SQLite 會拒絕某些轉換（例如有交易開著時不能離開 WAL），
所以報告的是生效值，不是請求值。

> 第一版的基準有一個真實的缺陷：`PRAGMA journal_mode = $mode` 的
> 跳脫寫錯了，送出去的是字面值 `$mode` 而不是模式本身，
> 所以「切到 WAL」根本沒發生 —— 讀回的值一直是 `truncate`。
> 修正後讀回 `set=wal read=wal`，這才是一個有效的 A/B。

---

## 3. 結果

### 3.1 兩個 writer（TEST-C 的負載規模）

```
WAL-A  mode=truncate  writes=500  failures=0  busy=0  p50=25  p95=41  p99=63   max=252
WAL-B  mode=wal       writes=500  failures=0  busy=0  p50=24  p95=40  p99=77   max=330
```

### 3.2 三個 writer

```
WAL-3WRITERS  mode=wal  writes=450  failures=0  busy=0  p50=29  p95=84  p99=264  max=1213
```

---

## 4. 結論

| 指標 | truncate | wal | 判斷 |
|------|----------|-----|------|
| 寫入失敗 | 0 | 0 | 相同 |
| `SQLITE_BUSY` | 0 | 0 | 相同 |
| p50 | 25 ms | 24 ms | 相同 |
| p95 | 41 ms | 40 ms | 相同 |
| p99 | **63 ms** | 77 ms | **truncate 較好** |
| max | **252 ms** | 330 ms | **truncate 較好** |

**WAL 在這個工作負載上沒有改善，某些指標還比較差。**

### 為什麼

WAL 的價值在於**讀寫並行**：讀取不會阻塞寫入，寫入不會阻塞讀取。
但這個 App 的讀寫負載**不重疊**：

- 擷取端只有**一個** writer（`capture-worker` 執行緒）。
- UI 的讀取發生在**使用者主動捲動**時，不是持續的。

所以 WAL 要解決的那個問題在這裡**不存在**，而 WAL 本身要維護
`-wal` 與 `-shm` 兩個側車檔案，代價是 checkpoint 的額外 I/O。

### 誠實的限制

這個實驗**沒有**覆蓋：

- 大量圖片同時寫入（加密與縮圖會讓寫入慢數十倍）。
- 讀取**緊接著**寫入的交錯負載（最壞情況）。
- 低磁碟空間下的 checkpoint 行為。
- 程序被殺後的 WAL 復原。

**若未來的量測顯示「UI 在擷取時卡住」，這個實驗必須重做。**

---

## 5. 沒有做的事

| 項目 | 狀態 |
|------|------|
| 沒有啟用 WAL | 資料不支持 |
| 沒有改變資料庫名稱 | — |
| 沒有破壞既有的加密 BLOB | 模式改變不影響已加密的內容 |
| 沒有把 `-wal` / `-shm` 當成安全的備份格式 | 它們**不是**備份；只備份 `-wal` 會得到一個**損毀**的資料庫 |
| 沒有改變 `journal_mode` 的預設 | 仍然是 `truncate` |

備份的正確做法仍然是**關閉資料庫後複製主檔**，或用 SQLite 自己的
backup API。側車檔案不是備份。

---

## 6. 保留的東西

| 檔案 | 用途 |
|------|------|
| `app/src/androidTest/java/tw/personal/privateinbox/WalBenchmarkInstrumentedTest.kt` | A/B 基準，隨時可以重跑 |
| `InboxDatabase.setJournalMode(mode)` | 設定並讀回實際生效的模式 |
| `InboxDatabase.journalMode()` | 讀取實際生效的模式 |

**實驗的程式碼留在專案裡**，因為下次有人想開 WAL 時，
應該先重跑這個基準，而不是靠「WAL 比較好」這種印象。

---

## 7. 下次該量測什麼

如果真的出現「擷取時 UI 卡住」的症狀，該做的實驗是：

1. 讀取與寫入**同時**進行，交錯到最壞情況（不是先寫完再讀）。
2. 寫入端帶**真實的圖片**（加密 + 縮圖），讓寫入慢下來。
3. 量測讀取的 p99 延遲，而不是寫入的。
4. 同時量測 checkpoint 的成本（WAL 會定期把頁面寫回主檔）。

**那一組數字才會決定 WAL 是不是答案。** 這一組不是。
