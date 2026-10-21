# X200 Pro 非破壞性驗收流程（X200PRO-NON-DESTRUCTIVE-QA）

> **REAL_DEVICE = NOT_TESTED**
>
> 本輪**沒有**連接 vivo X200 Pro。
> 本文件是**要在真機上執行的流程**，不是執行結果。
> 任何「已通過」的宣稱都必須附上裝置資訊與實際輸出。

---

## 1. 本輪的裝置狀態（實際讀取）

```
$ adb devices -l
emulator-5554  device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a

$ adb shell getprop ro.product.model          -> sdk_gphone64_arm64
$ adb shell getprop ro.build.version.release  -> 16
$ adb shell getprop ro.build.version.sdk      -> 36
$ adb shell getprop ro.vivo.os.version        -> (空)
$ adb shell getprop ro.build.display.id       -> sdk_gphone64_arm64-userdebug 16 BE2A.250530.026.F3 13894323 dev-keys
```

**這是模擬器，不是 X200 Pro。**
`ro.vivo.os.version` 在模擬器上不存在 —— 這正是為什麼不能臆測 OriginOS 版本。

---

## 2. 絕對不做的事

| 禁止 | 原因 |
|------|------|
| `adb uninstall tw.personal.privateinbox` | 會刪掉使用者已擷取的訊息 |
| `adb shell pm clear tw.personal.privateinbox` | 同上 |
| 刪除裝置上的 `inbox.db` | 同上 |
| 輪替 Debug keystore | 會讓 `adb install -r` 失敗 |
| 修改全域安全設定來「假裝修好」 | 那是使用者的選擇，不是診斷手段 |
| 對正式 App 跑破壞性測試 | v0.4.3 之後已經不可能：守衛會拒絕 |

---

## 3. 唯讀檢查（安全）

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.vivo.os.version      # 可能不存在，記為 UNKNOWN
adb shell getprop ro.build.display.id

# 已安裝的 App 與簽章
adb shell pm list packages | grep privateinbox
adb shell dumpsys package tw.personal.privateinbox | grep -E "versionName|versionCode|firstInstallTime"
```

**簽章相容性**（升級前必查）：

```bash
# 手機上既有版本的簽章
adb shell dumpsys package tw.personal.privateinbox | grep -A2 "signatures"
```

若簽章不同，`adb install -r` 會失敗而且**不會**動到資料。
若相同，直接覆蓋更新，資料保留。

---

## 4. 覆蓋安裝

```bash
adb install -r PrivateInboxClassic-v0.4.3-debug.apk
```

`install -r` **保留資料**。不要用 `uninstall`。

安裝後確認版本：

```bash
adb shell dumpsys package tw.personal.privateinbox | grep -E "versionName|versionCode"
```

---

## 5. vivo 的背景權限（三項一起）

vivo 的背景管理需要**三項同時**開啟，只開一項通常不夠。

| 設定 | 路徑 | 狀態 |
|------|------|------|
| 允許自啟動 | 設定 → 應用程式管理 → 權限管理 → 自啟動管理 | □ |
| 允許後台高耗電 | 設定 → 電池 → 後台耗電管理 | □ |
| 最近任務畫面鎖定 | 最近任務 → 長按卡片 → 鎖定 | □ |

**來源 App 本身也要**：

| App | 需要的設定 |
|-----|-----------|
| LINE | 允許後台接收通知 |
| Threads | 允許後台接收通知 |
| Instagram | 允許後台接收通知 |
| Messenger | 允許後台接收通知 |

**每一項的實際狀態都要記錄**，不能只記「有開」。

---

## 6. 通知存取權限

```bash
adb shell settings get secure enabled_notification_listeners
```

必須含有 `tw.personal.privateinbox`。

---

## 7. 測試矩陣

測試資料**只用合成的非私人內容**。不要用真實的私人對話。

| # | 條件 | 記錄 |
|---|------|------|
| 1 | 亮屏 + Wi-Fi | |
| 2 | **鎖屏** + Wi-Fi | |
| 3 | 亮屏 + 行動數據 | |
| 4 | **鎖屏** + 行動數據 | |
| 5 | 亮屏 + 省電模式 | |
| 6 | **鎖屏** + 省電模式 | |
| 7 | 照片擷取 ON + 鎖屏 | |
| 8 | 照片擷取 OFF + 鎖屏 | |
| 9 | **多個群組同時發送** | |

第 9 項是關鍵：至少 3 個群組，每個 10 則，共 30 則，同時送出。

### 每一格要記錄的四個分母

與模擬器測試**完全相同**的分母，這樣才能直接對照：

| 名稱 | 意義 | 單位 |
|------|------|------|
| `notification_attempts` | 來源送出的通知數 | 次 |
| `listener_callbacks` | listener 被呼叫的次數 | 次 |
| `presented_occurrences` | listener 在通知中看到的訊息數 | **次**（含重複呈現）|
| `submitted_messages` | 送去寫入的訊息數 | **則** |
| `saved` | 資料庫的列數 | **則** |
| `unaccounted` | `submitted − saved − deduped − failed` | **則**，必須為 0 |

**不可**只比較「通知數目」與「訊息數目」。

---

## 8. 分層診斷

| 觀察到的 | 結論 | 歸屬 |
|-----------|------|------|
| ① 來源沒收到訊息 | 來源 App 的問題 | **不是**本 App |
| ① 有通知，但 listener 沒回呼 | 通知存取沒開，或沒被綁定 | 權限問題 |
| ② 有回呼但沒訊息 | 解析階段 | 來源的通知形狀 |
| ③ 有訊息但沒入庫 | 佇列溢出 / 行程被殺 | 本 App |
| ③ `unaccounted > 0` | 管線內遺失 | 本 App，**真正的漏接** |

**第 ① 種情況必須記為 `SYSTEM_DELIVERY_GAP`，不能算成 App 自身的漏接。**

---

## 9. 診斷輸出

App 設定 → 診斷 → 匯出。

**匿名**：只含時間、trace id、階段代碼、來源套件名、錯誤型別名、耗時。
**不含**：訊息正文、寄件者、相簿名稱、URI、圖片位元組、完整 Bundle。

不要把匯出的診斷檔交給任何人 —— 裡面有你的使用時間與來源套件名。

---

## 10. 若要跑自動測試

需要 USB 偵錯：

```bash
bash scripts/run-burst-tests.sh --repeat 3
```

**這支腳本現在跑的是隔離的 devtest 變體**
（`tw.personal.privateinbox.devtest`），它有**自己的資料目錄**，
**碰不到**正式 App 的資料庫。

而且它在正式 App 有安裝時會先提示：

```
NOTE: the production app tw.personal.privateinbox is installed on this device.
      The burst tests run against the ISOLATED variant
      (tw.personal.privateinbox.devtest), which has its own data directory,
      so the production app's messages are NOT touched.
```

**它不會卸載任何東西。**

---

## 11. 本輪的結論

| 項目 | 狀態 |
|------|------|
| vivo X200 Pro 連線 | **NOT_CONNECTED** |
| OriginOS 版本 | **UNKNOWN** |
| 通知存取狀態 | **NOT_TESTED** |
| vivo 三項背景權限 | **NOT_TESTED** |
| 鎖屏 / 省電模式 | **NOT_TESTED** |
| 多群組同時發送 | **NOT_TESTED** |

**模擬器的結果不能冒充真機結果。**
本專案在模擬器上量到的是「管線本身不會漏」；
OriginOS 有自己的背景管理與通知分組策略，兩者都可能影響結果。
