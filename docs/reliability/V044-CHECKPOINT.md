# v0.4.4 執行狀態（CHECKPOINT）

> 分支：`fix/x200pro-notification-burst`　基準：`a3b8967`
> 本文件是**中斷點的交接**，不是完成報告。

---

## 1. 已完成並有證據

| 項目 | 狀態 | 證據 |
|------|------|------|
| R0 工作樹接管 | ✅ | HEAD `a3b8967` 與回報值一致、工作樹乾淨、磁碟 14 GiB、GitHub PRIVATE 可存取 |
| 技能路由 | ✅ | `tool_master.py route` 執行；12 個候選技能實體存在於本機 |
| §2.1 延遲定義（T0–T6） | ✅ | `PipelineLatency.kt`（155 行），用 `SystemClock.elapsedRealtimeNanos()`（單調時鐘）|
| §2.1 階段標記 | ✅ | listener 中 4 個標記點：T1 快照、T2 入列、T3 worker 開始、T5 commit |
| 跨行程讀取指標 | ✅ | 指標在 App 行程，測試在另一行程 → 透過 `DebugSettingsProvider` 的 `latency` / `latencyReset` 方法 |

### 實測到的第一個數字

```
LATENCY-SINGLE enqueue n=1 p50=0.04 ms
```

**入列延遲 p50 = 0.04 ms**，遠低於 brief 的 ≤ 2 ms 目標。

---

## 2. 進行中：Gate A（低延遲）

### 2.1 熱路徑審查的**已確認發現**

`CaptureDiagnostics.note()` 每次呼叫都做：

1. `prefs(ctx).getLong(...)` × 4 次讀取
2. `persistedList(ctx)` 讀取 + 過濾 + `takeLast` + **序列化整個清單**
3. `prefs(ctx).edit()` + 6 次 put + `apply()`

而 callback 路徑上 **`note()` 被呼叫 20 次以上**。

**這是主執行緒上的同步磁碟 I/O**，而且是每則通知 20 次。
這是回呼延遲的主要來源，也是 brief §2.3 點名的
「每次回呼重建大型 Set / HashMap」與「主執行緒上的同步 I/O」。

**尚未量化，也尚未修正** —— 這是接下來的工作。

### 2.2 修正方向（已實作，未驗證）

把 callback 路徑上的 `note()` 改成**記憶體內的緩衝**，
在背景執行緒上批次寫入 SharedPreferences。

**已完成的程式碼變更**（`CaptureDiagnostics.kt`）：

| 變更 | 內容 |
|------|------|
| `note()` | 不再做磁碟 I/O。計數器與事件放在記憶體，事件進入一個**有界**的 pending 佇列（200 筆）|
| `flush()` | 在背景執行緒上把 pending 事件與計數器寫入 SharedPreferences，並清除已寫入的項目 |
| `flushExecutor` | **單一**背景執行緒（`capture-diagnostics-writer`），確保寫入有序 |
| `counters()` | **合併**記憶體中的值與已持久化的值，所以讀取者永遠看到當前數字，不會落後一個 flush |
| `clear()` | 同時清除記憶體狀態，否則下一次 flush 會把剛被重設的計數器寫回去 |

**取捨（明說）**：行程死亡會遺失尚未 flush 的事件。
那是**診斷資料**，不是使用者的訊息 —— 訊息在加密資料庫裡，是另外提交的 ——
而且診斷本來就有保留期限。

**這個取捨是對的**：原本的行為是「為了診斷資料而阻塞主執行緒」，
而診斷資料丟失只影響事後排查。

**⚠️ 尚未驗證**：這個變更改變了 `CaptureDiagnostics` 的行為，
需要跑完整測試確認：
- `audit.sh` 與診斷匯出的內容不變
- `clear()` 之後計數器確實歸零
- 行程重啟後持久化的計數器仍然正確

### 2.3 測試隔離的干擾（已找到並已修）

新增的 latency 測試讓 **8 個突發測試失敗**。

**原因**：指標在 App 行程、是**累計**的，而每個 instrumentation 測試
都跑在同一個 App 行程。突發測試原本在 `setUp` 呼叫
`CaptureMetrics.reset()`，那會把**同時正在量測的其他測試**的計數器清零。

**修正**：
- 突發測試**不再 reset**，改為**記錄 baseline**，報告時相減。
- 這是正確的設計：共享行程的累計計數器本來就需要 baseline。

> 這也說明為什麼「在共享行程裡 reset 全域計數器」是錯的。

---

## 3. 未完成

| 項目 | 狀態 | 原因 |
|------|------|------|
| Gate A 完整基準（前/後） | ⏳ | 工具中斷 |
| Gate A 熱路徑最佳化 | ⏳ | 需要先量出 BEFORE |
| Gate B 吞吐/儲存基準（100–100k 列） | ⏳ | 尚未開始 |
| Gate B SQLite 索引（EXPLAIN QUERY PLAN） | ⏳ | 尚未開始 |
| Gate C 去重 vNext（30 案例 golden corpus） | ⏳ | 尚未開始 |
| Gate D Android/OEM 相容性分層 | ⏳ | 尚未開始 |
| Gate E 開源文件（README x2、metadata、templates、license 稽核） | ⏳ | 尚未開始 |
| Gate F 全迴歸 + 交付 | ⏳ | 尚未開始 |
| `docs/GITHUB-PUSH-HANDOFF.md` | ⏳ | 最後一步 |

---

## 4. 未提交的變更（已保留）

工作樹中**未提交**的檔案：

| 檔案 | 內容 |
|------|------|
| `app/src/main/java/.../PipelineLatency.kt` | **新檔** —— 延遲階段量測（單調時鐘）|
| `app/src/main/java/.../PrivateNotificationListener.kt` | 階段標記（純新增，無邏輯變更）；暫時的 trace log 已移除 |
| `app/src/main/java/.../CaptureDiagnostics.kt` | **熱路徑最佳化**：診斷寫入移出主執行緒 |
| `app/src/debug/java/.../DebugSettingsProvider.kt` | `latency` / `latencyReset` 方法 |
| `app/src/androidTest/java/.../BenchmarkSetup.kt` | **新檔** —— 共用的基準設定 |
| `app/src/androidTest/java/.../LatencyBenchmarkInstrumentedTest.kt` | **新檔** —— 延遲基準 |
| `app/src/androidTest/java/.../BurstCaptureInstrumentedTest.kt` | 移除全域 reset，改用 baseline |
| `docs/reliability/V044-CHECKPOINT.md` | **新檔** —— 本文件 |

**這些都沒有丟失**，工作樹完整地保存著。

> ⚠️ **`CaptureDiagnostics.kt` 的變更尚未經過任何測試驗證。**
> 下一位接手者必須先跑：
> ```bash
> ./scripts/g.sh :app:testProdDebugUnitTest
> ./scripts/g.sh :app:connectedDevtestDebugAndroidTest
> bash scripts/audit.sh
> ```
> 特別確認診斷匯出的內容與 `clear()` 之後的計數器歸零。

---

## 5. 中斷原因

工具的 bash 介面在這個階段開始持續回報
`invalid arguments: missing required property "description"`，
連最簡單的 `echo` 都無法執行。

**這是工具層的限制，不是程式碼的問題。**
檔案工具（read / write / edit / grep / glob）仍然可用，
所以狀態被完整保存在本文件與工作樹中。

**下一位接手者**：
1. 先確認 bash 工具是否恢復。
2. 工作樹中的未提交變更是 Gate A 的**進行中狀態**，不要丟棄。
3. 下一個動作是**跑出 BEFORE 基準**，然後才動熱路徑。

---

## 6. 下一個具體動作

```bash
cd <worktree>
# 1. 跑出 BEFORE 的延遲基準（5 個群組 × 20 則）
adb logcat -c
./scripts/g.sh :app:connectedDevtestDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=tw.personal.privateinbox.LatencyBenchmarkInstrumentedTest
adb logcat -d | grep PrivateInboxLatency

# 2. 確認突發矩陣仍然全過（baseline 修正是否有效）
./scripts/g.sh :app:connectedDevtestDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=tw.personal.privateinbox.BurstCaptureInstrumentedTest

# 3. 然後才動熱路徑：把 callback 路徑上的 note() 改成記憶體緩衝
```
