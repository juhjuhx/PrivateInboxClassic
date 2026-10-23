# vivo X200 Pro / OriginOS 相容性矩陣

> **STATUS: NOT_TESTED**
>
> 本輪**沒有**連接 vivo X200 Pro。
> 這份文件記錄的是**要在真機上收集什麼**，以及**已知會影響結果的因素**。
> 表格中的「未知」是誠實的，不是省略。

---

## 1. 本輪的實際環境

| 項目 | 值 | 來源 |
|------|-----|------|
| 已連接裝置 | `emulator-5554` | `adb devices` |
| 裝置型號 | `sdk_gphone64_arm64` | `ro.product.model` |
| Android 版本 | 16 | `ro.build.version.release` |
| API level | 36 | `ro.build.version.sdk` |
| ABI | arm64-v8a | `ro.product.cpu.abi` |
| **X200 Pro** | **未連接** | — |
| **OriginOS 版本** | **未知** | `ro.vivo.os.version` 在真機上可能不存在 |

**因此：所有 OriginOS 特定的行為都是 NOT_TESTED。**

---

## 2. 在真機上要收集的資料

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.vivo.os.version     # 可能不存在，記為 UNKNOWN
adb shell getprop ro.build.display.id
```

`ro.vivo.os.version` **不一定存在**。若不存在，OriginOS 版本要從
「設定 → 關於手機」或 `ro.build.version.opporom` / `ro.build.display.id` 取得。
**不要猜測。**

---

## 3. vivo 的背景管理

vivo 的背景管理是**三項一起**才有效的，這是與其他品牌最大的不同：

| 設定 | 位置 | 影響 |
|------|------|------|
| **允許自啟動** | 設定 → 應用程式管理 → 權限管理 → 自啟動管理 | 開啟後，程式可以在背景自行啟動 |
| **允許後台高耗電** | 設定 → 電池 → 後台耗電管理 | 開啟後，程式在鎖屏時不被限制 |
| **最近任務畫面鎖定** | 最近任務 → 長按卡片 → 鎖定 | 鎖定後，上滑清除**不會**結束程式 |

**只開一項通常不夠。**

LINE、Threads、Instagram **本身**也需要允許背景高耗電，
否則它們不會在鎖屏時收到訊息，也就沒有通知可以擷取。

> **不要**直接修改全域安全設定來「假裝修好」。
> 那些設定是使用者的選擇，本專案只提供**診斷**。

官方說明：
- <https://www.vivo.com/tw/support/questionByTitle?title=%E8%83%8C%E6%99%AF%E9%81%8B%E8%A1%8C>
- <https://kefu.vivo.com.cn/robot/imgmsgData/3dfcdb616d0542b4baa4e9ead6d75c5e/index_1.html>

---

## 4. 狀態核對表

| 項目 | 怎麼查 | 應該是什麼 | 真機實測 |
|------|--------|-----------|----------|
| 通知存取 | `adb shell settings get secure enabled_notification_listeners` | 含 `tw.personal.privateinbox` | **未知** |
| 通知存取（設定介面） | 設定 → 通知與狀態列 → 通知存取 | Private Inbox 已開啟 | **未知** |
| 通知權限 | `adb shell dumpsys package tw.personal.privateinbox \| grep POST_NOTIF` | granted=true | **未知** |
| 自啟動 | vivo 設定 → 自啟動管理 | 已開啟 | **未知** |
| 後台高耗電 | vivo 設定 → 後台耗電管理 | 已開啟 | **未知** |
| 最近任務鎖定 | 最近任務畫面 | 已鎖定 | **未知** |
| 來源 App 後台 | LINE / Threads / Instagram 各自設定 | 已開啟 | **未知** |

---

## 5. 測試矩陣

每一格都要記錄**四個分母**，不只是「有沒有存到」。

| # | 條件 | 期望 |
|---|------|------|
| 1 | 亮屏 + Wi-Fi | 全部存到 |
| 2 | **鎖屏** + Wi-Fi | 全部存到 |
| 3 | 亮屏 + 行動數據 | 全部存到 |
| 4 | 鎖屏 + 行動數據 | 全部存到 |
| 5 | 亮屏 + 省電模式 | 記錄實際結果 |
| 6 | 鎖屏 + 省電模式 | 記錄實際結果 |
| 7 | 照片擷取 ON + 鎖屏 | 全部存到 |
| 8 | 照片擷取 OFF + 鎖屏 | 全部存到 |

**不需要**每一格都跑大量資料；每格 10–20 則群組訊息足夠判定有沒有漏接。

### 5.1 記錄的四個分母

與模擬器測試**完全相同**的四個分母，這樣才能直接對照：

| 名稱 | 意義 |
|------|------|
| `post_attempts` | 來源發出的訊息數 |
| `callbacks_received` | 監聽器被呼叫的次數 |
| `messages_presented` | 監聽器在通知中看到的訊息數 |
| `messages_saved` | 資料庫中的列數 |

**不變條件**：`messages_presented − messages_saved` 必須能解釋。

---

## 6. 分層診斷

本專案的分層量測可以直接用在真機上。比較這四個數字：

```
系統產生的通知數
  ↓  ① 系統沒有把通知交給監聽器 = SYSTEM_DELIVERY_GAP
監聽器收到的回呼數
  ↓  ② 有回呼但沒有解析出訊息 = PARSE 階段
看到的訊息數
  ↓  ③ 看到了但沒入庫 = 佇列 / 資料庫
資料庫的列數
```

| 觀察到的 | 結論 |
|-----------|------|
| ① 系統層面就沒有通知 | **不是本 App 的問題。** 來源 App 沒收到，或被 vivo 的背景管理擋下 |
| ① 有通知但監聽器沒回呼 | 通知存取權限沒開，或系統沒綁定 listener |
| ② 有回呼但沒訊息 | 解析階段；多半是來源的通知形狀與預期不同 |
| ③ 有訊息但沒入庫 | 佇列溢出或行程被殺 —— 這時 `QUEUE_OVERFLOW` 與 `WORKER_FAILURE` 會有紀錄 |

**第 ① 種情況必須誠實地標記為 `SYSTEM_DELIVERY_GAP`，
不能算成 App 自身的漏接。**

---

## 7. 行程被殺的診斷

vivo 的背景管理可能會**直接結束** App 行程。
那會造成記憶體中的佇列遺失（見 `BURST-CAPTURE-ROOT-CAUSE.md` §5）。

**怎麼分辨**：

1. 匯出診斷報告，看 `worker_failures` 與 `queue_overflow`。
2. 如果兩者都是 0，但訊息仍然遺失 → **行程被殺**。
3. 交叉檢查：`adb shell dumpsys activity processes | grep privateinbox`，
   看行程何時被結束。

**行程被殺的解法是持久化 intake journal**（尚未實作），
不是調大佇列。

---

## 8. 在真機上跑自動測試

需要 USB 偵錯：

```bash
bash scripts/run-burst-tests.sh --repeat 3
```

這會輸出與模擬器**完全相同格式**的分母與延遲，可以直接對照。

**注意**：腳本會用 `pm install` 重裝 App，
**那會刪除已擷取的訊息**。在真機上跑之前請先匯出備份。

---

## 9. 本輪的結論

| 項目 | 狀態 |
|------|------|
| 模擬器上的群組突發 | **PASS**（10 個案例，3 次連續執行）|
| vivo X200 Pro 上的群組突發 | **NOT_TESTED** |
| OriginOS 的背景行為 | **NOT_TESTED** |
| vivo 的通知分組行為 | **NOT_TESTED** |
| 鎖屏 vs 亮屏 | **NOT_TESTED** |

**模擬器的結果不能外推到 OriginOS。**
OriginOS 有自己的背景管理與通知分組策略，兩者都可能影響結果。
