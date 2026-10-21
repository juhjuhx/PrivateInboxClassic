# HANDOFF — Private Inbox Classic v0.4.3

> 交給下一位接手者。這份文件說明**現在的狀態**、**這輪做了什麼**、
> **還沒做什麼**。

---

## 1. 快速索引

| 文件 | 內容 |
|------|------|
| `AGENT_STATE.md` | 給 agent 的狀態摘要與下一個動作 |
| `TEST-REPORT.md` | 所有測試的結果 |
| `engineering/SECURITY-AUDIT.md` | 15 項安全審查的結果 |
| **reliability/V043-EXECUTION-STATE.md** | **這一輪實際做了什麼** |
| **reliability/DEDUP-IDENTITY-AUDIT.md** | **去重識別碼審查與修正** |
| **reliability/WAL-EXPERIMENT.md** | **WAL A/B 實驗與「不啟用」的結論** |
| **reliability/INTAKE-JOURNAL.md** | **行程死亡量測與 journal 契約** |
| **reliability/X200PRO-NON-DESTRUCTIVE-QA.md** | **真機驗收程序（NOT_TESTED）** |
| `reliability/BURST-CAPTURE-ROOT-CAUSE.md` | 群組突發漏接的根因 |
| `reliability/QUEUE-BACKPRESSURE.md` | 佇列設計與背壓 |
| `reliability/NOTIFICATION-DEDUP.md` | 去重策略 |
| `roadmap/*` | 通知鏡像、Rust 核心、圖片、多裝置同步 |

---

## 2. 這一輪的三個重點

### 2.1 破壞性測試會刪掉使用者的訊息（P0，已修）

八個測試檔直接對**正式套件**的資料庫呼叫 `db.clear()`。
上一輪的交接文件正是讓使用者在自己的手機上跑 `run-burst-tests.sh`。

**三層保護**：`devtest` 變體（獨立資料目錄）、
`DestructiveOperationGuard`（依套件 ID 拒絕）、
`scripts/audit-test-isolation.py`（建置閘門，雙向驗證過）。

**量測**：devtest 119/119 全過；prod 119 項 0 失敗 66 skip（帶原因）。

### 2.2 去重鍵不是訊息身分（P1，已修）

- 來源有時間戳時**識別碼被丟棄** → 同一毫秒的相同文字合併。
- 指紋用**顯示名稱**而非 `conversation_key` → 兩個同名群組合併。

現在：識別碼永遠納入且內容優先；指紋用對話鍵；記憶體過濾改為計數。
8 項新測試鎖定。

### 2.3 WAL 實驗：結論是**不啟用**（P1）

```
mode=truncate  p50=25  p95=41  p99=63  max=252   failures=0
mode=wal       p50=24  p95=40  p99=77  max=330   failures=0
```

WAL 沒有改善，因此**沒有啟用**。基準程式碼保留。

---

## 3. 驗證結果

| 項目 | 結果 |
|------|------|
| Instrumentation（隔離的 devtest 變體）| **119/119 PASS**，0 skip |
| Instrumentation（正式的 prod 變體）| **119 項，0 失敗，66 skip** |
| Unit | **66/66 PASS** |
| Python DHC | **23/23 PASS** |
| 跨語言 fixture | **7/7 PASS** |
| Lint | 0 error |
| `audit.sh` / `audit-secrets.sh` / `audit-release.sh` / `audit-test-isolation.py` | 全部 PASS |
| 突發矩陣 | 10 個案例，`unaccounted` 全為 0 |
| GitHub Actions | 見 §7 |

---

## 4. 交付產物

| 檔案 | 大小 | SHA-256 |
|------|------|---------|
| `PrivateInboxClassic-v0.4.3-debug.apk` | 6,238,033 B | `41c9119f645c8c9d306f4afd62efa7eb8dd6370994cd579fc878bd767a5d7f22` |
| `PrivateInboxClassic-v0.4.3-release-unsigned.apk` | 4,980,391 B | `fd5d26e5d0ad78368b319f52396050fd3f07f77fda12fdf53d3ed69f5fa3f5d9` |
| `PrivateInboxClassic-v0.4.3.aab` | 1,738,118 B | `adf17f9f2f4ffe6d2cb720f4651c8ac75f0ac24f51872c86d67def9097d8672b` |

**簽章憑證 SHA-256**：`c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`
（**與手機上既有版本相同** → `adb install -r` 直接覆蓋，不刪資料）

**Release 產物未簽署** —— 正式金鑰必須由擁有者保管。

---

## 5. 保留的產品

以下**完全沒有**被改動：

- Telegram Classic UI
- `SourceRegistry` 的平台清單（只**新增**了 devtest 自我測試來源）
- SQLite schema（v4）
- Keystore 加密格式
- App Lock
- 既有訊息歷史
- DHC Connector v0.2

本輪**沒有**進行：Rust 正式核心、通知鏡像實作、圖片擷取重建、
雲端同步、公開上架。

---

## 6. 尚未完成（誠實列出）

| 項目 | 狀態 | 原因 |
|------|------|------|
| **vivo X200 Pro / OriginOS 真機** | **NOT_TESTED** | 本輪沒有連接實機 |
| OriginOS 版本 | **UNKNOWN** | `ro.vivo.os.version` 沒有實機可讀 |
| 鎖屏 / 省電模式 / 行動數據 | **NOT_TESTED** | 需要實機 |
| 行程被殺的實際遺失量 | **未量測** | 需要在真機上真的被殺 |
| Intake Journal | **設計文件，未接入** | 證據不足以證明有足夠改善 |
| WAL | **未啟用** | 實驗顯示沒有改善 |
| 正式 release 簽章 | **BLOCKED** | 金鑰必須由擁有者保管 |
| Android 9/13/14/15 | **NOT_TESTED** | 只驗證了 Android 16 |

**模擬器的結果不能冒充真機結果。**

---

## 7. CI

分支 `fix/x200pro-notification-burst` 推送到**私人**倉庫。
CI 包含：測試隔離稽核、秘密稽核、DHC 測試與跨語言 fixture、
契約相容性、單元測試、Lint、建置、產物稽核、權限稽核、簽章稽核，
以及在**隔離的 devtest 變體**上的 instrumentation。

---

## 8. 下一步

### 8.1 需要真機

```bash
adb install -r PrivateInboxClassic-v0.4.3-debug.apk
# vivo 三項：允許自啟動 + 允許後台高耗電 + 最近任務鎖定
bash scripts/run-burst-tests.sh --repeat 3
```

詳細步驟見 `docs/reliability/X200PRO-NON-DESTRUCTIVE-QA.md`。

### 8.2 需要授權

- 正式 release / upload 金鑰。
- 合併到 `integration/dsh-v040`（本分支未合併）。
- 公開 Release（本專案維持私人）。

### 8.3 建議的工程項目

1. **在真機上量測實際的行程死亡遺失** —— 那是決定 Intake Journal
   是否值得實作的依據。
2. 若「擷取時 UI 卡住」真的發生，重做 WAL 實驗，這次量**讀取**延遲。

---

## 9. 不要做的事

1. **不要**在正式套件上執行破壞性測試。守衛會拒絕，稽核腳本會讓建置失敗。
2. **不要**用 `uninstall` 更新 App —— 會刪掉資料。
3. **不要**在真機上跑 `run-burst-tests.sh` 而不先備份（它會重裝**隔離**變體，
   不碰正式資料，但保險起見先備份）。
4. **不要**宣稱 OriginOS 的行為已驗證 —— 沒有實機就是 NOT_TESTED。
5. **不要**把記憶體佇列的可靠性說成「不丟失」—— 行程被殺時會遺失。
