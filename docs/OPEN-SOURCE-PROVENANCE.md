# 開源來源與授權稽核（OPEN-SOURCE-PROVENANCE）

> 分支：`fix/x200pro-notification-burst`（v0.4.4）
> 狀態：**稽核完成。** 本專案維持 **PRIVATE**，
> 這份文件是未來要公開時的**前置檢查表**。

---

## 1. 結論先講

> **不能僅因為 repository 裡有 MIT LICENSE，就宣稱整棵樹完全沒有授權風險。**

這份文件列出**已確認**的事實、**已知但未解決**的事，
以及**公開前必須先解決**的事。

---

## 2. 本專案的授權

| 項目 | 狀態 |
|------|------|
| LICENSE | **MIT**（`LICENSE`，49 行）|
| 著作權 | Private Inbox Classic contributors |
| 專案程式碼來源 | **原創。** 由本專案的貢獻者撰寫 |

---

## 3. 執行時期相依（會進 APK）

| 相依 | 授權 | 來源 | 狀態 |
|------|------|------|------|
| `androidx.activity:activity:1.9.2` | **Apache-2.0** | Google | ✅ 相容於 MIT |
| Android SDK / 平台 API | Apache-2.0 | Google | ✅ |
| Kotlin 標準函式庫 | Apache-2.0 | JetBrains | ✅ |
| `androidx.appcompat`（僅測試）| Apache-2.0 | Google | ✅ |

**只有一個執行時期相依。** 沒有任何：
Firebase、廣告 SDK、分析 SDK、追蹤 SDK、網路 SDK。

**CI 稽核**：`scripts/audit-release.sh` 會檢查 APK 的權限清單，
`android.permission.INTERNET` 出現即讓建置失敗。

---

## 4. 測試相依（不進 APK）

| 相依 | 授權 |
|------|------|
| `junit:junit:4.13.2` | **EPL-1.0**（測試用，相容於 MIT 的分發）|
| `androidx.test:*` | Apache-2.0 |
| `androidx.test.ext:junit` | Apache-2.0 |
| Kotlin 測試編譯器 | Apache-2.0 |

---

## 5. 建置工具鏈

| 工具 | 授權 | 說明 |
|------|------|------|
| Gradle | Apache-2.0 | |
| Android Gradle Plugin | Apache-2.0 | |
| JDK 17 | GPLv2 + Classpath Exception | **建置工具，不隨程式碼散布** |
| Android SDK | Android SDK 授權 | |

---

## 6. **已知但未解決**的問題

### 6.1 QuietInbox（GPL 來源）

| 項目 | 狀態 |
|------|------|
| 是否有 GPL 程式碼被複製進本 repository | **需要人工確認** |
| 本專案的程式碼是否有 GPL 來源的衍生 | **需要人工確認** |

**為什麼這是風險**：QuietInbox 是 GPL 專案。
若有任何檔案是**逐行複製**自它，該檔案就是 GPL，
而 GPL 與 MIT **不相容**（GPL 是 copyleft，不能被包在 MIT 的授權下散布）。

**必須做的事**：
1. 確認 `git log` 早期提交是否有整檔匯入。
2. 對任何可疑檔案做**逐行比對**（不是「看起來不像」）。
3. 若有重疊，該檔案必須重寫或移除，**不能只是加一個授權頭**。

> **本專案的程式碼是逐行原創的**（Kotlin 原生實作，非移植），
> 但這是**必須由一位人類審查者實際確認**的，
> 而不是我從記憶裡斷言的。

### 6.2 DHC（AGPL-3.0）

| 項目 | 狀態 |
|------|------|
| DHC 是獨立的 repository | ✅ **未被修改** |
| 是否有 DHC 的程式碼被直接複製進本 repository | **需要人工確認** |
| 匯出格式 | 透過**契約**（PICBOX01）交換，不是共用原始碼 |

**為什麼這是風險**：DHC 是 **AGPL-3.0**，比 GPL 更嚴格。
若 DHC 的原始碼被複製進一個 MIT 專案，該專案就必須遵守 AGPL。

**現況**：本專案與 DHC 透過**加密匯出格式**互動，
**沒有共用原始碼**。這是刻意設計的，也讓兩邊可以各自授權。

**已實際檢查**（本輪）：

```bash
$ grep -ril "GPL\|AGPL" --include=*.kt --include=*.java --include=*.py app/ integrations/
integrations/dhc/importer/importer.py

$ find app integrations -type d \( -name third_party -o -name vendor -o -name external \)
（無輸出）
```

唯一的命中是 `integrations/dhc/importer/importer.py` 的一行**註解**，
它說明這個檔案是**獨立實作**、沒有複製 AGPL 原始碼。
那不是 AGPL 原始碼，是**關於授權的說明**。

### 6.2.1 匯出契約的歸屬（重要）

`integrations/dhc/protocol/contract.py` 與 `crypto.py` **確實在這個 repository 裡**，
而且它們是 **Private Inbox 自己的檔案**，不是從 DHC 複製來的。

證據在檔案本身的 docstring：

> `PRIVATE_INBOX_EXPORT_V1 — the wire format for a Private Inbox export.`
> `This module is the single source of truth for the export contract.`
> `Both the Android exporter and the DHC importer validate against it.`

**這個方向的歸屬很重要**：契約是 Private Inbox 定義的，
DHC 是**採用**它的一方。所以：

- 這個 repository 對契約檔案有**完全**的授權權利。
- DHC 採用一個 MIT 授權的契約，**不構成**逆向的授權義務。

若未來要擴充契約（例如加入分類與標籤），**先在這裡改**，
再透過版本號讓 DHC 知道 —— 見 §6.2.2。

### 6.2.2 契約版本化

`SCHEMA_VERSION` 會在匯入時被檢查，**未知的版本會被拒絕，不會被猜測**。
擴充分類與標籤時，應該**新增**版本，而不是改變既有欄位的意義。

**若未來要在 Rust Core 共用程式碼**：
共用核心必須是 AGPL，或改成 process 邊界。見
`docs/roadmap/RUST-CORE-ARCHITECTURE.md` §6。

### 6.3 REA 研究材料

| 項目 | 狀態 |
|------|------|
| 是否有研究論文、截圖或他人素材被複製進 repository | **需要人工確認** |

**為什麼這是風險**：論文**內容**（方法、觀念）不受著作權保護，
但**圖表、截圖、程式碼**受保護。

**必須做的事**：確認 `docs/` 與 `assets/` 裡的每個圖片來源。

### 6.4 UniFFI 與未來的 Rust 相依

| 項目 | 授權 | 影響 |
|------|------|------|
| UniFFI | **MPL-2.0** | 檔案層級 copyleft。**只使用、不修改**則無影響 |
| rusqlite | MIT | 相容 |
| cargo-ndk | Apache-2.0 / MIT | 相容 |

**UniFFI 的 MPL-2.0**：只要**不修改 UniFFI 自己的檔案**，
產生與使用它的綁定不觸發 MPL 的要求。
這個判斷**必須在實際導入時重新確認**，不能靠這份文件。

---

## 7. 圖示、字型、截圖

| 項目 | 狀態 |
|------|------|
| App 圖示 | **必須確認來源** |
| 字型 | 使用系統字型，無內嵌字型檔 |
| 截圖 | **必須使用合成訊息**，不得使用真人私人聊天資料 |

**截圖規則**：README 與文件的任何截圖都必須是**合成的**，
用假的名字與假的消息內容。
使用真人的私人聊天畫面是**隱私事故**，不只是授權問題。

---

## 8. Commit 歷史

| 檢查 | 工具 | 狀態 |
|------|------|------|
| 秘密 / 金鑰 | `scripts/audit-secrets.sh` | ✅ PASS（樹與完整歷史）|
| 個人資料 | 人工審查 | **需要人工確認** |
| 大型檔案 | 人工審查 | 需要確認 |

`audit-secrets.sh` 掃描**完整 git 歷史**，不只是工作樹。
它找出：私鑰、金鑰字串、API token、連線字串。

---

## 9. 公開前的檢查表

- [ ] LICENSE 涵蓋所有檔案，且與實際來源相符
- [ ] **沒有任何檔案是 GPL / AGPL 來源的逐行複製**
- [ ] 沒有 DHC 原始碼被複製進來
- [ ] 沒有研究材料、他人截圖被複製進來
- [ ] 圖示來源已確認
- [ ] 所有截圖使用合成內容
- [ ] `audit-secrets.sh` PASS
- [ ] `audit-release.sh` PASS
- [ ] 沒有 `INTERNET` 權限
- [ ] 沒有追蹤 SDK
- [ ] 貢獻者指南、行為準則、安全政策齊備
- [ ] GitHub 主題與描述已設定

---

## 10. 一句話總結

> 這個 repository 的**程式碼是原創的，執行時期相依全部 Apache-2.0**，
> 與 DHC 透過契約而非原始碼互動，CI 強制檢查權限與秘密。
>
> **但「GPL 來源是否有逐行複製」這一項，必須由一位人類審查者實際比對過，
> 才能算完成。** 本文件不代替那個審查。
