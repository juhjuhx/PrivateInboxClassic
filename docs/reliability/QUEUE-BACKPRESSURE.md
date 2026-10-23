# 佇列背壓（QUEUE-BACKPRESSURE）

> 分支：`fix/x200pro-notification-burst`　基準：`8494910`
> 狀態：**已實作並量測**

---

## 1. 設計原則

| 原則 | 實作 |
|------|------|
| 主執行緒快速退出 | Callback 只做政策檢查、最小快照、提交佇列 |
| 不在 callback 裡解碼圖片 | 原始 bytes 的讀取是必須的（provider 會撤銷授權），**解碼**在 worker |
| 不在 callback 裡查資料庫 | 資料庫存取全部在 worker |
| **有界**工作佇列 | 容量 512 |
| 記錄佇列深度 | 即時深度 + 高水位 |
| 明確區分過載與解析失敗 | `QUEUE_OVERFLOW` vs `PARSE_FAILED` |
| **不允許無聲丟棄** | 溢出記錄為 `QUEUE_OVERFLOW`（含深度）|
| 單一 writer | 保持 SQLite 寫入順序，去重鍵依賴它 |
| 異常隔離 | worker 捕捉例外，記錄 `WORKER_FAILURE`，**繼續運作** |

---

## 2. 架構

```
onNotificationPosted (主執行緒)
  ├─ CaptureMetrics.callbacksEntered++
  ├─ 政策檢查（來源 + 錄製開關）
  ├─ 解析（MessagingStyle / InboxStyle / 純文字）
  ├─ 記錄哪些訊息是新的（見 NOTIFICATION-DEDUP.md）
  └─ submit { ... }  ─── 有界佇列（512）──▶  worker 執行緒
                                  │                      │
                                  └─ 溢出 → QUEUE_OVERFLOW   ├─ 讀取 media bytes
                                                             ├─ 解碼 / 縮圖
                                                             ├─ 寫入 SQLite
                                                             └─ 例外 → WORKER_FAILURE
```

**為什麼是單一 worker**：去重鍵依賴**寫入順序**。
多個 writer 會競爭，產生 `database is locked` 與非確定性的去重結果。
量測顯示單一 writer 足以應付：修正後 500 則訊息的寫入等待 p99 是 2.1 秒。

---

## 3. 量測結果

裝置：Android 16 (API 36)、arm64-v8a 模擬器。

### 3.1 修正前 vs 修正後（TEST-C：10 對話 / 500 則訊息）

| 指標 | 修正前 | 修正後 | 改善 |
|------|--------|--------|------|
| `messages_presented` | 9,500 | 9,500 | — |
| `messages_submitted` | 250 | **500** | 2.0× |
| `messages_saved` | 250 | **500** | 2.0× |
| `unaccounted` | **3,821** | **0** | 完全消除 |
| `queue_depth_max` | 476 | 243 | 49% |
| `queue_wait` p50 | 20,342 ms | **1,444 ms** | **14.1×** |
| `queue_wait` p95 | 37,883 ms | **2,023 ms** | **18.7×** |
| `queue_wait` p99 | 39,144 ms | **2,091 ms** | **18.7×** |
| `queue_wait` max | 39,374 ms | 2,100 ms | 18.8× |
| `overflow` | 0 | 0 | — |
| `end_to_end` p99 | 39,081 ms | **2,099 ms** | 18.6× |

**最大的改善不是速度，是「沒有東西遺失」**：`unaccounted` 從 3,821 變成 0。

### 3.2 各案例的佇列等待

| 案例 | 內容 | p50 | p95 | p99 | max | 深度上限 |
|------|------|-----|-----|-----|-----|---------|
| A | 1 對話 / 20 則 | 80 ms | 149 ms | 149 ms | 158 ms | 11 |
| B | 5 對話 / 100 則 | 413 ms | 634 ms | 658 ms | 665 ms | 63 |
| C | 10 對話 / 500 則 | 1,444 ms | 2,023 ms | 2,091 ms | 2,100 ms | 243 |
| D | 相同文字 / 3 對話 | 245 ms | 269 ms | 270 ms | 273 ms | 22 |
| E | 高頻更新 60 次 | 193 ms | 290 ms | 295 ms | 298 ms | 32 |
| F | 歷史累積 25 則 | 1,109 ms | 1,157 ms | 1,160 ms | 1,163 ms | 21 |
| G | Worker 例外 | 0 ms | 0 ms | 0 ms | 0 ms | 1 |
| H | 慢速寫入 100 則 | 396 ms | 520 ms | 542 ms | 545 ms | 57 |
| I | 錄製開關切換 | 28 ms | 56 ms | 56 ms | 56 ms | 4 |
| J | 群組摘要 + 子項 | 0 ms | 0 ms | 0 ms | 0 ms | 2 |

**沒有任何案例溢出**（`overflow = 0`），深度最高 243，遠低於 512 的上限。

### 3.3 Callback 耗時（主執行緒）

| 案例 | p50 | p95 | p99 | max |
|------|-----|-----|-----|-----|
| A | 5 ms | 16 ms | 17 ms | 20 ms |
| B | 4 ms | 21 ms | 58 ms | 89 ms |
| **C（500 則）** | **3 ms** | **20 ms** | **47 ms** | 113 ms |
| E | 4 ms | 21 ms | 25 ms | 83 ms |
| H | 3 ms | 15 ms | 39 ms | 70 ms |

**Callback p99 = 47 ms**（最壞的情況：500 則訊息）。
這是主執行緒上的時間，所以這是最敏感的數字。
它**不隨訊息數量成長** —— 這是修正的直接效果：
callback 裡只剩下最小的工作。

---

## 4. 過載處理

### 4.1 溢出

佇列滿時，`submit()` 回傳 false，並記錄：

```
QUEUE_OVERFLOW  error="depth=512"
```

**沒有靜默丟棄。** 使用者可以在診斷面板看到溢出次數。

### 4.2 為什麼容量是 512

一則通知可帶數百則訊息，而一個對話的突發會產生多則通知。
512 足以涵蓋最壞情況的合理量，同時讓記憶體有界。

**512 × 每個任務的估計大小** 是可接受的 —— 任務只持有
已解析的預覽與已讀取的 media bytes，不是整個通知。

### 4.3 Worker 例外

```kotlin
} catch (e: Throwable) {
    CaptureMetrics.workerFailures.incrementAndGet()
    // 記錄，然後繼續
}
```

**為什麼要特別處理**：worker 死亡會**靜默停止所有擷取**，
因為沒有任何執行緒再去拿佇列裡的任務。系統不會重啟這個服務。

---

## 5. 已知限制

### 5.1 記憶體中的佇列會在行程死亡時遺失

事件在 callback 與入庫之間尚未持久化時，行程被殺，那一筆就沒了。

**本專案沒有實作持久化 intake journal。**

這是有意識的取捨，理由：

1. 持久化 journal 本身也要經過同一條管線 —— 它自己也會遺失。
2. 量測顯示佇列等待已經降到 2.1 秒以內，這個視窗很窄。
3. 引入 journal 會帶來新的相容性與一致性風險。

**但這個限制在 vivo 的背景下管理下是真實風險** ——
那可能會直接殺掉行程。見 `X200PRO-ORIGINOS-MATRIX.md` §7。

**判斷標準**：若真機測試顯示行程被殺是遺失的主因，
就必須實作 journal。屆時應該先做 journal（它對單機使用也有價值），
再做多裝置同步。

### 5.2 沒有跨行程的佇列持久性

同上。

### 5.3 單一 writer 的吞吐上限

量測顯示 500 則訊息的寫入等待 p99 是 2.1 秒。
若單機的訊息量遠高於此（例如同一秒內數千則），會需要重新評估。

**目前沒有證據顯示需要多 worker。** 而多 writer 會破壞去重所需的順序。

---

## 6. 診斷

診斷面板顯示：

| 項目 | 來源 |
|------|------|
| 通知回呼 | `callbacks_entered` |
| 通過來源政策 | `policy_accepted` |
| 呈現的訊息 | `messages_presented` |
| 送去寫入 | `messages_submitted` |
| 入庫 / 重複 / 失敗 | `db_committed` / `db_duplicated` / `db_failed` |
| 佇列最大深度 | `queue_high_watermark` |
| 佇列溢出 | `queue_overflow` |
| Worker 例外 | `worker_failures` |
| 截斷的訊息 | `messages_truncated` |
| 回呼耗時 p95/p99 | `callbackDuration` |
| 佇列等待 p95/p99 | `queueWait` |

**這些都是行程內的計數器**，行程結束就歸零。這一點在面板上已標明。
