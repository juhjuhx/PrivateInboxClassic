# HANDOFF — Private Inbox Classic v0.4.2

> 交給下一位接手者。這份文件說明**現在的狀態**、**這輪做了什麼**、
> **還沒做什麼**。

---

## 1. 快速索引

| 文件 | 內容 |
|------|------|
| `AGENT_STATE.md` | 給 agent 的狀態摘要與下一個動作 |
| `TEST-REPORT.md` | 所有測試的結果（PASS / FAIL / NOT TESTED）|
| `engineering/SECURITY-AUDIT.md` | 15 項安全審查的完整結果 |
| `engineering/PERFORMANCE-BASELINE.md` | 效能量測基準 |
| `reliability/BURST-CAPTURE-ROOT-CAUSE.md` | **群組突發漏接的根因與量測** |
| `reliability/QUEUE-BACKPRESSURE.md` | 佇列設計與背壓量測 |
| `reliability/NOTIFICATION-DEDUP.md` | 去重策略與三個版本的演進 |
| `reliability/X200PRO-ORIGINOS-MATRIX.md` | **真機要收集什麼** |
| `roadmap/NOTIFICATION-MIRROR.md` | 通知鏡像規格（DESIGN_ONLY）|
| `roadmap/RUST-CORE.md` | Rust 核心研究（FUTURE_RESEARCH）|
| `roadmap/MEDIA-CAPTURE.md` | 圖片擷取（DEFERRED）|
| `roadmap/MULTI-DEVICE-SYNC.md` | 多裝置同步（PLANNING_ONLY）|

---

## 2. 這一輪的成果

### 2.1 群組突發漏接（最高優先）

**症狀**：多個群組同時大量傳送訊息時，部分訊息沒有被保存。

**真正的根因不是 OriginOS，也不是 SQLite。是寫入放大。**

| 指標 | 修正前 | 修正後 |
|------|--------|--------|
| `messages_saved`（500 則的場景） | 250 | **500** |
| `unaccounted` | **3,821** | **0** |
| `queue_wait` p99 | 39,144 ms | **2,091 ms** |
| `callback` p99 | — | 47 ms |

兩個原因：

1. **寫入放大**：群組通知帶的是累積的歷史，所以 500 則訊息被呈現了
   9,500 次，而每一次都先做了一次 AES-GCM 加密才發現是重複。
   現在只提交新訊息。
2. **去重識別碼用索引**：平台的 `MessagingStyle` 上限是 25 則，
   超過後索引會位移，第 26 則以後永遠抓不到。
   現在用訊息內容。

**驗證**：10 個案例的矩陣（TEST-A..TEST-J），3 次連續執行，全部通過。

### 2.2 Release 產物完整性

| 問題 | 修正 |
|------|------|
| Release AAB 帶著 **debug 簽章** | AAB 的簽章組態在沒有正式金鑰時清空 |
| 回報的「v0.4.1 AAB」實際是 **v0.4.0-alpha** | 稽核腳本改驗 AAB 內的版本與簽章 |

稽核腳本已用舊產物驗證過：它會**同時**報出這兩個缺陷。

### 2.3 跨語言一致性

DHC 的跨語言測試找到一個真實缺陷：

```
python:  msg_vAqh2ijUIiDWkO4grhsE
android: msg_vAqh2ijUIiDWkO4grhsE\n
```

Android 的 `Base64` 預設附加換行。摘要完全相同，結果差一個字元。
後果是同一則訊息在兩邊有不同的 id，**匯入不再冪等**。

修正後兩個方向都通過：Android 匯出 → Python 匯入，
Python 匯出 → Android 驗證。

### 2.4 其他

- **Keystore 復原流程**：暫時鎖定與永久失效分開處理；刪除需要兩次確認；
  診斷面板顯示金鑰狀態與管線指標。
- **列表分頁**：首屏讀取是**固定的 ~89 ms**，與資料表大小無關。
- **DHC v0.2 匯出流程**：選範圍 → SAF 選位置 → 密碼 → 加密寫出。
  密碼用完即歸零，永不儲存。

---

## 3. 驗證結果

| 項目 | 結果 |
|------|------|
| Instrumentation | **102/102 PASS**，0 skip |
| Unit | **50/50 PASS** |
| Python DHC | **23/23 PASS** |
| 跨語言 fixture | **7/7 PASS** |
| Lint | 0 error |
| `audit.sh` / `audit-secrets.sh` / `audit-release.sh` | 全部 PASS |
| CI（run 39532595947） | **GREEN**，20 個步驟全部成功 |

---

## 4. 交付產物

| 檔案 | 大小 | SHA-256 |
|------|------|---------|
| `PrivateInboxClassic-v0.4.2-debug.apk` | 5,948,649 B | `dca038856f83761f080cf8ec64e43fa73e43925e846911c1eedee3bc5e6f8567` |
| `PrivateInboxClassic-v0.4.2-release-unsigned.apk` | 4,975,111 B | `d63707f15e10b8e893d7149e78433e6878e9816a7be8d03eb319ec34cae5a0c5` |
| `PrivateInboxClassic-v0.4.2.aab` | 1,736,305 B | `7dd33706f00f797397491ad9e28bf6afbdb3d2788713ff0d6f14429300bea7a4` |

**簽章憑證 SHA-256**：`c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`
（與手機上既有版本**相同**，`adb install -r` 可直接覆蓋，不刪資料）

**Release 產物是未簽署的** —— 正式金鑰必須由擁有者保管。

---

## 5. 尚未完成（誠實列出）

| 項目 | 狀態 | 原因 |
|------|------|------|
| **vivo X200 Pro 真機驗證** | **NOT_TESTED** | 本輪沒有連接真機 |
| OriginOS 背景行為 | **NOT_TESTED** | 同上 |
| 鎖屏 / 亮屏、省電模式 | **NOT_TESTED** | 需要實機 |
| 正式 release 簽章 | **BLOCKED** | 金鑰必須由擁有者保管 |
| 持久化 intake journal | **NOT_IMPLEMENTED** | 記憶體佇列在行程被殺時會遺失事件 |
| Android 9/13/14/15 | **NOT_TESTED** | 只驗證了 Android 16 |
| DHC 正式 UI 整合 | **CONNECTOR_READY** | 匯出/匯入已就緒，DHC 端未接 |

---

## 6. 下一步

### 6.1 立即（不需要授權）

```bash
cd <worktree>
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export GRADLE_USER_HOME="<workspace>/gradle_home"
export ANDROID_SDK_ROOT="<workspace>/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_USER_HOME="<workspace>/.android-home"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/build-tools/36.0.0:$PATH"

./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest
bash scripts/audit.sh && bash scripts/audit-secrets.sh && bash scripts/audit-release.sh
python3 integrations/dhc/tests/test_connector.py
bash scripts/run-burst-tests.sh --repeat 3
```

### 6.2 需要實機

```bash
adb install -r PrivateInboxClassic-v0.4.2-debug.apk
# vivo 設定：允許自啟動 + 允許後台高耗電 + 最近任務鎖定（三項都要）
# LINE / Threads / Instagram 本身也要允許後台接收
bash scripts/run-burst-tests.sh --repeat 3
```

**注意**：`run-burst-tests.sh` 會重裝 App，那會刪除已擷取的訊息。
在真機上跑之前先匯出備份。

詳細步驟見 `reliability/X200PRO-ORIGINOS-MATRIX.md`。

### 6.3 需要授權

- 建立正式 release / upload 金鑰。
- 合併到 `integration/dsh-v040`（本分支未合併）。
- 建立公開 Release（本專案維持私人）。

---

## 7. 不要做的事

1. **不要**把 Debug 金鑰當成正式簽章。
2. **不要**用 `uninstall` 更新 App —— 會刪掉資料。
3. **不要**在真機上跑 `run-burst-tests.sh` 而不先備份。
4. **不要**宣稱 OriginOS 的行為已驗證 —— 沒有實機就是 NOT_TESTED。
5. **不要**把記憶體佇列的可靠性說成「不丟失」—— 行程被殺時會遺失。
