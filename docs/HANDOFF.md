# HANDOFF — Private Inbox Classic v0.4.4

> 交給下一位接手者。這份文件說明**現在的狀態**、**這輪做了什麼**、
> **還沒做什麼**。

---

## 1. 快速索引

| 文件 | 內容 |
|------|------|
| `AGENT_STATE.md` | 給 agent 的狀態摘要與下一個動作 |
| **GITHUB-PUSH-HANDOFF.md** | **本輪的交付：怎麼推送、怎麼跑 CI** |
| `TEST-REPORT.md` | 所有測試的結果 |
| `COMPATIBILITY.md` | Android / OEM 相容性矩陣 |
| `OPEN-SOURCE-PROVENANCE.md` | 授權與來源稽核 |
| `REPOSITORY-METADATA.md` | GitHub About 與 Topics |
| `reliability/STORAGE-BENCHMARK.md` | 儲存與吞吐的實測 |
| `reliability/DEDUP-IDENTITY-AUDIT.md` | 去重識別碼審查 |
| `reliability/WAL-EXPERIMENT.md` | 為什麼不啟用 WAL |
| `reliability/INTAKE-JOURNAL.md` | 行程死亡量測與 journal 契約 |
| `reliability/X200PRO-NON-DESTRUCTIVE-QA.md` | 真機驗收程序 |
| `roadmap/RUST-*.md` | Rust Core 的三份研究文件 |

---

## 2. 這一輪是效能與可攜性

| 項目 | 修正前 | 修正後 |
|------|--------|--------|
| 回呼 p50 | **623 ms** | **4 ms** |
| 入列 p50 | 0.09 ms | **0.02 ms** |
| 佇列等待 p50（500 則）| **33,945 ms** | **3,520 ms** |
| 500 則訊息的交易數 | **648** | **40** |
| 每則訊息的磁碟 | — | **511 bytes** |

**瓶頸是磁碟 I/O，不是運算。** 這是 Rust 研究得出「目前不需要 Rust」的依據。

### 修了什麼

| 缺陷 | 後果 |
|------|------|
| 診斷寫入在主執行緒做磁碟 I/O | 每則通知 20 次同步寫入，回呼 623 ms |
| 每則訊息一個交易 | 每個交易一次 fsync，500 則 = 648 次 |
| flush 持有 monitor 跨越寫入 | worker 阻塞，提交延遲 40 秒 |
| 每個事件一個 flush 任務 | 發佈 100 則從 130 ms 變 116 秒 |
| `normalize()` 每次編譯 Regex | 每則通知最多 50 個 |
| `selected()` 走 `.all` | 每則通知載入整個偏好設定檔 |
| 準備好的批次是 per-instance | 其他實例的訊息**永遠不會被寫入** |

---

## 3. 驗證結果

| 項目 | 結果 |
|------|------|
| 單元測試 | **84/84 PASS** |
| Instrumentation（隔離的 devtest 變體）| **126/126 PASS**，0 skip |
| Instrumentation（正式的 prod 變體）| 126 項，**0 失敗**，破壞性測試 skip |
| Android Lint | 0 error |
| 四個稽核腳本 | 全部 PASS |
| DHC 連接器 / 跨語言 fixture | 23/23、7/7 PASS |
| 突發矩陣 | 10 個案例，`unaccounted` 全為 0 |
| 儲存基準 | 100 / 500 / 1,000 / 10,000 列，全部入庫 |

---

## 4. 保留未動

- Telegram Classic UI
- `SourceRegistry` 的平台清單
- SQLite 的**加密格式**（schema 版本變了，格式沒變）
- `CryptoStore`、`ConversationIdentity`
- Keystore 格式、App Lock
- 既有訊息資料
- DHC v0.2 的匯出格式
- Debug 簽章

本輪**沒有**進行：Rust 正式核心、通知鏡像實作、圖片擷取重構、
雲端同步、公開上架、推送。

---

## 5. 尚未完成（誠實列出）

| 項目 | 狀態 | 原因 |
|------|------|------|
| **vivo X200 Pro / OriginOS** | **NOT_TESTED** | 沒有連接實機 |
| Android 9–15 | **NOT TESTED** | 沒有那些版本的設備 |
| 行程被殺的實際遺失 | **未量測** | 需要在真機上真的被殺 |
| Intake Journal | **設計文件，未接入** | 證據不足以證明值得 |
| WAL | **未啟用** | 實驗顯示沒有改善 |
| 正式 release 簽章 | **BLOCKED** | 金鑰必須由擁有者保管 |
| 永久保存 / 按日期封存 | 未實作 | 需要資料模型 + UI 支援 |
| 全文搜尋 | 未實作，**刻意不做** | 明文索引會洩漏整個訊息歷史 |
| GPL 來源的逐行比對 | **需要人工** | 要人看 git 歷史 |
| 圖示與截圖授權 | **需要人工** | 要人確認來源 |
| 推送 | **本輪不做** | 交由 Codex / Hermes |

**模擬器的結果不能冒充真機結果。**

---

## 6. 下一步

### 6.1 推送（交給 Codex / Hermes）

依 **`docs/GITHUB-PUSH-HANDOFF.md`** 執行。**先檢查，再推送。**

### 6.2 需要真機

```bash
adb install -r <v0.4.4-debug.apk>   # 先備份！不要卸載
# vivo 三項：允許自啟動 + 允許後台高耗電 + 最近任務鎖定
bash scripts/run-burst-tests.sh --repeat 3
```

詳細步驟見 `docs/reliability/X200PRO-NON-DESTRUCTIVE-QA.md`。

### 6.3 建議的工程項目

1. **在真機上量測實際的行程死亡遺失** —— 那是決定 Intake Journal
   是否值得實作的依據。
2. 若「擷取時 UI 卡住」真的發生，重做 WAL 實驗，這次量**讀取**延遲。

---

## 7. 不要做的事

1. **不要**在正式套件上執行破壞性測試。守衛會拒絕，稽核腳本會讓建置失敗。
2. **不要**用 `uninstall` 更新 App —— 會刪掉資料。
3. **不要**宣稱 OriginOS 的行為已驗證 —— 沒有實機就是 NOT_TESTED。
4. **不要**把記憶體佇列的可靠性說成「不丟失」—— 行程被殺時會遺失。
5. **不要**用 debug keystore 簽署 release 產物。
6. **不要**把入列時間當成持久化時間。
