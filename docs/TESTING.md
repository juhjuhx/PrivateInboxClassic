# Testing（TESTING）

> 完整的測試索引在 [`verification/TEST-MATRIX.md`](verification/TEST-MATRIX.md)，
> 實際結果在 [`TEST-REPORT.md`](TEST-REPORT.md)。
> 這份文件說明**怎麼跑**與**什麼時候才算真的通過**。

---

## 1. 執行

| 層級 | 指令 | 需要裝置 |
|------|------|----------|
| JVM 單元測試 | `./scripts/g.sh :app:testProdDebugUnitTest` | 否 |
| Android Lint | `./scripts/g.sh :app:lintProdDebug` | 否 |
| Instrumentation（隔離）| `./scripts/g.sh :app:connectedDevtestDebugAndroidTest` | **是** |
| Instrumentation（正式）| `./scripts/g.sh :app:connectedProdDebugAndroidTest` | **是** |
| Python 連接器 | `python3 integrations/dhc/tests/test_connector.py` | 否 |
| 跨語言 fixture | `python3 integrations/dhc/tests/test_fixtures.py` | 否 |
| 產物稽核 | `bash scripts/audit-release.sh` | 否 |
| 秘密稽核 | `bash scripts/audit-secrets.sh` | 否 |
| 隔離稽核 | `python3 scripts/audit-test-isolation.py` | 否 |

---

## 2. 兩件事會讓測試「看起來通過但其實沒測到」

這兩件事本專案都踩過，而且都被現有的檢查抓到。

### 2.1 共享行程的累計計數器

**指標住在 App 行程裡，而每個 instrumentation 測試都跑在同一個 App 行程。**
所以在其中一個測試的 `setUp` 裡 `reset()` 會把**同時正在量測的其他測試**
的計數器清零。

結果是：某個測試會突然失敗，而每一個測試單獨跑都會過。

**規則**：共享行程的累計計數器**只能取 baseline 然後相減**，
不能在 `setUp` 裡重置。

### 2.2 沒有 `POST_NOTIFICATIONS`，通知會被靜默丟棄

Android 13+ 需要執行時期權限才能發通知。沒有它，
`notify()` **不會報錯**，通知只是不會出現 —— 於是測試量到的是
「什麼都沒發生」，而不是它想量的東西。

**規則**：任何要發通知的測試，必須先
`pm grant <package> android.permission.POST_NOTIFICATIONS`。

---

## 3. 裝置要求

| 要求 | 原因 |
|------|------|
| 裝置**必須解鎖** | Keystore 是 CE-backed 的，鎖屏時會拋 `SecurityException: ... is not encryption aware!` |
| 至少 API 28 | 專案的 minSdk |
| 通知存取權限 | instrumentation 會重裝 App，重裝會把它從允許清單裡踢掉，所以測試要自己重新授權 |

---

## 4. 破壞性測試隔離

**這是本專案最重要的測試規則。**

八個測試檔會清空資料庫。在裝著**正式 App** 的手機上跑它們，
會**刪掉使用者已擷取的訊息**。

### 三層保護

| 層 | 機制 | 檔案 |
|----|------|------|
| 隔離 | `devtest` 變體有獨立的 applicationId 與資料目錄 | `app/build.gradle.kts` |
| 守衛 | 依**套件 ID** 拒絕破壞性操作，訊息說明「資料沒有刪除」 | `DestructiveOperationGuard.kt` |
| 閘門 | 測試繞過守衛 → **建置失敗** | `scripts/audit-test-isolation.py` |

### 在正式變體上，測試是 **skip** 不是 **fail**

```
SKIPPED: this test destroys the database, so it only runs against the
ISOLATED variant (tw.personal.privateinbox.devtest). The target here is
tw.personal.privateinbox, and nothing was deleted.
```

**skip 是正確的**：skip 表示「這裡沒跑，原因是 X」，
fail 會錯誤地表示產品壞了。

---

## 5. 「通過」的定義

> **沒有實際執行過的測試，一律標 `NOT TESTED`。**

一份報告裡，每一個結果都必須附上：

1. **指令**（完整，可複製）
2. **輸出**（關鍵行）
3. **環境**（裝置、Android 版本、熱狀態、負載）

**特別地**：

- 模擬器的結果**不能**當成真機的結果。
- 「上次跑過是綠的」**不是**本輪的驗證。
- 一個會 skip 的測試**不算**通過。

---

## 6. 新增一個測試時

| 類型 | 放哪裡 | 注意 |
|------|--------|------|
| 純邏輯 | `app/src/test/` | 不需要裝置 |
| 需要資料庫或通知 | `app/src/androidTest/` | 需要解鎖的裝置 |
| 會清空資料庫 | `app/src/androidTest/` | **必須**走 `TestDataIsolation` |
| 跨語言 | `integrations/dhc/tests/` | 必須有對應的 Android 端 fixture |

**每一個新測試都要**：

1. 若會清空資料庫 → 用 `TestDataIsolation.clearDatabase(ctx)`，
   並在 `@Before` 呼叫 `DestructiveTestAssumption.requireIsolated(ctx)`。
2. 在 `@After` 用 `tryClearDatabase(ctx)`，**不要**在 `@After` 裡呼叫
   `assumeTrue` —— 那會變成硬失敗（`TestCouldNotBeSkippedException`）。
3. 斷言要**解釋**預期結果，不要只寫 `assertTrue(x)`。
