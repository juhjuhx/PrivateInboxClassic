# X200 Pro / OriginOS 群組突發漏接 — 診斷與操作指引

> **狀態：模擬器已驗證，真機待驗證。**
>
> 本文件說明：修好了什麼、怎麼在 X200 Pro 上確認、以及要收集什麼證據。
> 真機（vivo X200 Pro / OriginOS）的驗證 **尚未執行** —— 見 §5。

---

## 1. 修了什麼

### 1.1 真正的漏接機制：去重鍵識別的是「通知」而不是「訊息」

**症狀**：群組一次來很多訊息，Private Inbox 只存到一部分。

**原因**（實測數據）：

| 指標 | 修復前 | 修復後 |
|------|--------|--------|
| listener 收到的回呼 | 121 | 121 |
| 解析出的訊息 | 120 | 120 |
| 寫入資料庫的嘗試 | 1260 | 1260 |
| **實際儲存的列** | **20** | **100** |

去重鍵在「來源沒有提供時間戳」時，退化成**擷取時間**。
而擷取時間是**每則通知**一個，不是每則訊息一個 ——
所以同一則群組通知裡的 20 則訊息，全部算出**同一個去重鍵**，
全部被當成重複訊息，最後只留下 1 列。

**修法**：退化成**每則訊息的識別碼** —— 訊息在通知訊息清單中的**索引**。
這個索引在同一則通知的後續修訂中是穩定的（同一則訊息位置不變），
但不同訊息之間不同 —— 即使兩則訊息的文字完全一樣。

**既有資料不受影響**：來源有提供時間戳時，時間戳照舊使用，
指紋與舊版**位元級相同**，不會被重複收錄。

### 1.2 訊息歷史被截斷在 25 則

`previews.takeLast(25)` 會**靜默丟掉**超過 25 則的群組通知中較早的訊息。
而來源只會送最新的修訂版本，所以被丟掉的訊息**永遠無法找回**。

**修法**：上限提高到 500；若真的超過，被丟棄的數量會記錄成
`MESSAGE_HISTORY_TRUNCATED` 診斷（不是靜默遺失）。

### 1.3 工作佇列無上限、連線後不補抓

| 問題 | 修法 |
|------|------|
| `newSingleThreadExecutor()` 的佇列無上限，突發會變成看不見的積壓 | 有界佇列（512），溢出記錄為 `QUEUE_OVERFLOW` |
| worker 因單則畸形通知死亡 → **所有擷取靜默停止** | 記錄 `WORKER_FAILURE`，worker 繼續運作 |
| Listener 重新連線後，之前未擷取到的通知永遠遺失 | 連線時讀一次 `activeNotifications()` 補抓 |

**補抓的限制**：只能補回**目前仍在通知列**中的通知。
已經被來源移除的通知，平台本身已經沒有資料，**無法復原**。

### 1.4 套件可見性（Android 11+）

`<queries>` 元素原本完全缺失。在 Android 11 以上，App **看不到**自己未宣告的套件，
所以來源清單在這些裝置上會是空的。

**修法**：加入明確的 `<queries>` 清單（LINE、Instagram、Messenger、
WhatsApp、Telegram、Threads、KakaoTalk、Teams）。
**沒有**使用 `QUERY_ALL_PACKAGES`。

---

## 2. 這一版的驗證結果

| 項目 | 結果 |
|------|------|
| Instrumentation | **67/67 PASS**，0 skip |
| Unit | **50/50 PASS** |
| Python 連接器 | **23/23 PASS** |
| Lint | 0 error |
| 三個稽核腳本 | 全部 PASS |

### 群組突發測試（3 次連續執行，全部 PASS）

裝置：Android 16 (API 36)、arm64-v8a 模擬器。

| 案例 | 內容 | 結果 |
|------|------|------|
| A | 單一群組，20 次更新 | `post_attempts=20  db_saved=20` |
| B | 5 個群組，100 則訊息 | `post_attempts=100 db_saved=100` |
| C | 5 個群組交錯更新 | `post_attempts=100 db_saved=100` |
| D | 兩則**相同文字**的不同訊息 | `duplicate_text_count=2`（兩則都保留）|
| D2 | 同一則通知重複更新 10 次 | `identical_update_rows=1`（不重複收錄）|
| E | 取消後的通知 | 已取消的不在 active 清單 |
| F | 照片開 / 關 | `captured_on=20 captured_off=20`（不受影響）|

**沒有無法解釋的遺失。** 每個案例的 `post_attempts` 都等於 `db_saved`。

---

## 3. 在 X200 Pro 上怎麼做

### 3.1 安裝

```bash
adb install -r PrivateInboxClassic-v0.4.1-burst-debug.apk
```

簽章與既有安裝**完全相同**（憑證 SHA-256 `c876051e...`），
所以 `install -r` 會**直接覆蓋更新，不會刪除資料**。

> **不要**用 `adb uninstall` —— 那會刪掉已擷取的訊息。

### 3.2 確認權限與設定

| 項目 | 怎麼查 | 應該是什麼 |
|------|--------|-----------|
| 通知存取 | `adb shell settings get secure enabled_notification_listeners` | 含有 `tw.personal.privateinbox` |
| 背景執行 | vivo 設定 → 應用程式管理 → 私人收件匣 → **允許後台高耗電**、**允許自啟動**、**最近任務畫面加鎖** | 全部開啟 |
| 來源 App | LINE / Threads / Instagram 的設定裡也要允許後台接收 | 開啟 |
| 電池 | 測試期間不要用省電模式 | 一般即可 |

> vivo 的後台管理是**自啟動 + 後台高耗電 + 最近任務鎖定**三項一起生效的。
> 只開一項通常不夠。**不要**直接改全域安全設定來「假裝修好」。

### 3.3 重現並觀察

1. 讓 LINE 群組**一次連續**發送 10–20 則訊息。
2. 打開 Private Inbox，確認**每一則**都有被保存。
3. 對照群組裡實際的訊息數。

### 3.4 匿名診斷輸出

App 內：**設定 → 診斷** → 匯出。

匯出的內容只包含：時間、trace id、階段代碼、來源套件名、
錯誤型別名稱、耗時。

**不包含**：訊息正文、寄件者、相簿名稱、URI、圖片位元組、完整 Bundle。

診斷代碼對照：

| 代碼 | 意義 |
|------|------|
| `MESSAGE_HISTORY_TRUNCATED` | 單則通知訊息超過 500 則，丟棄數在 error 欄位 |
| `QUEUE_OVERFLOW` | 佇列滿了，有通知被拒絕（error 欄位是深度）|
| `WORKER_FAILURE` | worker 處理某則通知時出錯（已記錄，worker 未死）|
| `ACTIVE_RECOVERED` | 連線時補回了 N 則仍存在的通知 |
| `DB_INSERT_SUCCESS` / `DB_DUPLICATE` | 入庫結果 |

**請不要把匯出的診斷檔交給任何人** —— 裡面有你的使用時間與來源套件名。

---

## 4. 若仍然漏接

請把下列三樣東西回報，**不要**貼任何訊息內容：

1. **診斷匯出檔**（匿名）
2. `adb shell dumpsys notification --noredact | grep -A5 "tw.personal.privateinbox"` 的相關片段
3. 說明：群組一次發了幾則、實際存到幾則、是**鎖屏**還是**亮屏**時發生

有了 (1) 的 `QUEUE_OVERFLOW` / `WORKER_FAILURE` / `MESSAGE_HISTORY_TRUNCATED`
計數，就能直接判斷是哪一層在漏。

---

## 5. 目前**未**驗證的事（誠實列出）

| 項目 | 狀態 | 原因 |
|------|------|------|
| vivo X200 Pro / OriginOS 真機 | **NOT TESTED** | 本輪沒有連接真機 |
| OriginOS 版本 | **UNKNOWN** | 沒有裝置可讀 `ro.vivo.os.version` |
| 亮屏 / 鎖屏差異 | **NOT TESTED** | 需要真機 |
| Wi-Fi / 行動數據差異 | **NOT TESTED** | 需要真機 |
| 省電模式 | **NOT TESTED** | 需要真機 |
| vivo 後台自啟動的實際行為 | **NOT TESTED** | 需要真機 |
| 官方 ROM 的通知分組行為 | **NOT TESTED** | 需要真機 |
| Android 9/13/14/15 | **NOT TESTED** | 只驗證了 Android 16 |
| 正式 release 簽章 | **BLOCKED** | 金鑰必須由擁有者保管 |

**模擬器上的結論不能直接外推到 vivo OriginOS。**
OriginOS 有自己的後台管理與通知分組策略，兩者都可能影響結果。

---

## 6. 下一個具體動作

```bash
# 1. 連上真機
adb devices                      # 確認 X200 Pro 出現且為 device 狀態

# 2. 讀取裝置資訊（不臆測）
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.vivo.os.version     # 可能不存在，記為 UNKNOWN

# 3. 覆蓋安裝（不清資料）
adb install -r PrivateInboxClassic-v0.4.1-burst-debug.apk

# 4. 確認通知存取
adb shell settings get secure enabled_notification_listeners

# 5. 依 §3.2 開啟 vivo 的後台權限

# 6. 依 §3.3 重現並觀察
```

**若要在真機上跑同一套自動測試**（需要 USB 偵錯）：

```bash
bash scripts/run-burst-tests.sh --repeat 3
```

這支腳本會輸出本文件 §2 相同格式的分母與指標，
可以直接和模擬器的數字對照。
