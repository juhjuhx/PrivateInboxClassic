# 測試矩陣（TEST-MATRIX）

本文件是**所有測試的索引**，說明每個測試驗證什麼、在哪裡執行。
實際的測試結果在 `TEST-REPORT.md`；測試 XML 在 `app/build/test-results/`。

---

## 1. 執行方式

| 層級 | 指令 | 需要裝置 |
|------|------|----------|
| JVM 單元測試 | `./gradlew :app:testProdDebugUnitTest` | 否 |
| Android Lint | `./gradlew :app:lintProdDebug` | 否 |
| Instrumentation | `./gradlew :app:connectedDevtestDebugAndroidTest` | **是** |
| Python 連接器 | `python3 integrations/dhc/tests/test_connector.py` | 否 |
| 封裝產物稽核 | `bash scripts/audit-release.sh` | 否 |
| 秘密稽核 | `bash scripts/audit-secrets.sh` | 否 |
| 跨平台往返 | `bash integrations/dhc/tests/host_interop.sh` | **是**（先跑 Android 端測試） |
| 一次跑完 | `bash scripts/agent.sh verify` | 部分 |

---

## 2. JVM 單元測試（42 項）

### `CapturePolicyTest`（10 項）

| 測試 | 驗證內容 |
|------|----------|
| blank input normalizes to null | 空白 → null |
| whitespace and line feeds are collapsed to single spaces | 換行折疊 |
| overlong bodies are capped | 20,000 字元上限 |
| known package maps to its display name | 套件名 → 顯示名 |
| unknown source falls back to its raw name | 未知套件名原樣回傳 |
| package not on the known allowlist is refused even when selected | **白名單外的套件即使勾選也拒絕** |
| explicitly selected known source is allowed | 勾選的已知來源允許 |
| selected source does not open other packages | 選 A 不會開 B |
| unknown source is never allowed without opt-in | 未勾選一律拒絕 |
| every known app is a messaging or chat package | 白名單項全部為通訊類 |

### `ImageCaptureTest`（8 項）

| 測試 | 驗證內容 |
|------|----------|
| jpeg magic passes | `FF D8 FF` |
| png magic passes | `89 50 4E 47` |
| webp riff container passes | RIFF + WEBP |
| heic isobmff ftyp passes | `ftyp` |
| arbitrary text is rejected without mime | 非影像被拒 |
| declared image mime rescues unknown magic | `image/avif` 可救援 |
| declared non image mime does not rescue | `text/plain` 不得救援 |
| tiny payloads are rejected | < 4 bytes |

### `SelfTestSourcePolicyTest`（4 項）

自我測試來源的註冊與政策。

### `SourceRegistryTest`（20 項）

| 群組 | 涵蓋內容 |
|------|----------|
| 平台辨識（5） | Threads 套件名、所有必要平台已註冊、未知套件不是來源、自我測試來源隔離、篩選列順序 |
| 分類（8） | Threads 私訊、Threads 社交動態排除、Instagram 按讚為社交動態、Instagram 私訊、LINE 群組、conversation shortcut 判定、mention/reply 優先、無法分類落到 UNKNOWN |
| 對話識別（5） | 跨 App 不合併、同名聯絡人以 shortcut id 區分、shortcut id 穩定性、無 shortcut 時用標題、空身份偵測、key 必含套件名 |

---

## 3. Instrumentation 測試（105 項）

### 3.1 通知擷取管線（13 項）

| 測試 | 驗證內容 | 檔案 |
|------|----------|------|
| `systemPostedNotificationReachesListenerAndIsStored` | **由 Android 系統實際發布**的通知走到加密資料庫 | `SelfTestSystemNotificationInstrumentedTest` |
| `repeatedIdenticalSelfTestPostsDedupeToSingleRow` | 相同通知只產生一列 | 同上 |
| `disabledSourcePolicyRejectsSystemPostedSelfTest` | 未勾選來源不保存 | 同上 |
| `selfTestSourceRegisteredInKnownApps` | 自我測試來源已註冊 | 同上 |
| `messagingStyleNotificationIsCapturedAndDecrypted` | MessagingStyle 解析與解密 | `NotificationPipelineInstrumentedTest` |
| `notificationFromNonWhitelistedPackageIsRejected` | 非白名單被拒 | 同上 |
| `groupSummaryNotificationIsIgnored` | 群組摘要被跳過 | 同上 |
| `emptyNotificationDoesNotCrash` | 空通知不崩潰 | 同上 |
| `duplicateNotificationIsNotStoredTwice` | 去重有效 | 同上 |
| `listenerServiceIsDeclaredInTheManifest` | 服務綁定與 exported 正確 | 同上 |
| `allowedSourceNotificationStoredViaDirectCapture` | 允許來源被保存 | `DbContractInstrumentedTest` |
| `disallowedSourceRejectedViaSourcePolicy` | 政策拒絕有效 | 同上 |
| `connectionStateHooksRunWithoutCrashing` | 連線／斷線回呼不崩潰 | 同上 |

### 3.2 資料庫與加密（10 項）

| 測試 | 驗證內容 |
|------|----------|
| `encryptedContentRoundTripsThroughDatabase` | 內容加密往返一致 |
| `encryptedRowSurvivesDatabaseReopen` | 重開後仍可解密 |
| `databaseSurvivesReopenAfterWrites` | 寫入後重開正常 |
| `imageIsStoredEncryptedAndDecryptsBack` | 影像密文 ≠ 明文，解密位元組一致 |
| `identicalPostsAreDedupedToDuplicate` | 重複寫入回傳 DUPLICATE |
| `distinctBodiesAreNotWronglyMerged` | 不同內容不合併 |
| `saveReturnsConcreteResultNeverThrows` | `save()` 永不拋例外 |
| `clearRemovesEverything` | 清除全部有效 |
| `retentionPrunesOldMessages` | 保留期限正確剪除 |
| `captureViaOneHandleVisibleToFreshUiRead` | 寫入後新 handle 讀得到 |

### 3.3 穩健性（7 項）

| 測試 | 驗證內容 |
|------|----------|
| `malformedSourcePreferenceDoesNotCrashTheListener` | **偏好設定型別錯誤不會讓監聽器崩潰**（本輪修正的真實崩潰） |
| `emptyAndAbsentPreferencesReturnEmptySourceSet` | 缺少設定回傳空集合 |
| `listenerSurvivesAMalformedNotification` | 畸形通知不會讓監聽器崩潰 |
| `recordingDisabledIsRecordedAsAStageNotACrash` | 記錄關閉是政策決定，不計為失敗 |
| `aUsableKeyReportsAvailable` | 可用金鑰回報 KEY_AVAILABLE |
| `aValidPayloadRoundTripsAndReportsAvailable` | 正常往返回報可用 |
| `aTamperedPayloadIsADecryptFailureNotAKeyProblem` | 竄改 ≠ 金鑰問題 |
| `aTruncatedPayloadIsRejected` | 截斷被拒 |
| `theWrongAadIsADecryptFailure` | 錯 AAD 被拒 |
| `aCorruptRowDoesNotHideTheOtherRows` | 一列損毀不影響其他列 |
| `purgeRemovesNothingWhenEveryRowIsReadable` | 可讀列永不被清除 |

### 3.4 Provider 存取控制（5 項）

| 測試 | 驗證內容 |
|------|----------|
| `providerIsGuardedByASignatureLevelPermission` | 權限是 signature 級 |
| `providerIsNotExportedWithoutThePermission` | Provider 要求該權限 |
| `anUnrelatedCallerCannotFlipTheRecordingSwitch` | 測試套件持有授權 |
| `theAppStateIsUnchangedByARejectedCall` | 被拒的呼叫不改變 App 狀態 |
| `releaseBuildHasNoDebugProvider` | release 組態無此 Provider |

### 3.5 UI（4 項）

`renderLockScreen` / `renderChatListScreen` / `renderConversationScreen` /
`renderSettingsScreen` — 把真實畫面繪製成圖片，驗證版面可建構。

### 3.6 效能量測（7 項）

寫入吞吐、讀取+解密延遲（100/1000/10000 列）、索引查詢延遲、
AES-GCM 成本、資料庫成長、記憶體佔用、Activity 啟動。
全部使用**合成資料**。

### 3.7 DHC 匯出（7 項）

### 3.9 群組突發擷取（7 項）

`BurstCaptureInstrumentedTest` — 合成通知驅動的突發擷取回歸測試。
詳見 `docs/engineering/X200PRO-BURST-FIX.md` 與 `TEST-REPORT.md` 附錄。

| 案例 | 驗證內容 |
|------|----------|
| A | 單一群組 20 次更新，每則都入庫且不重複 |
| B | 5 群組 100 則訊息，零遺失 |
| C | 5 群組交錯更新，各對話不串台 |
| D | 兩則相同文字的不同訊息都要保留 |
| D2 | 同一通知重複更新只產生一列 |
| E | 只能補回仍 active 的通知 |
| F | 照片開關不影響文字擷取數量 |

### 3.10 DHC 匯出（7 項）

訊息 id 穩定性、bundle 結構、容器 magic 與 header、每次匯出新 salt/nonce、
**Android 產生的封包可被主機端 Python 匯入器讀取**、
**錯誤密碼被拒絕**。

---

## 3.11 去重鍵語意（8 項）

`FingerprintIdentityTest` — 去重鍵必須識別**訊息**而非通知修訂。
可以在 JVM 上執行（指紋輸入的建構是純函式）。

| 測試 | 驗證內容 |
|------|----------|
| `twoDistinctMessagesWithoutTimestampGetDifferentKeys` | 兩則不同位置的訊息鍵不同 |
| `theSameMessageKeepsItsKeyAcrossNotificationRevisions` | 同一則訊息跨修訂鍵不變 |
| `theDiscriminatorIsAppendedOnlyWhenItIsPresent` | 有時間戳時不混入識別碼 |
| `theHistoricalFingerprintFormatIsPreservedWhenNoDiscriminatorIsGiven` | 舊格式位元級保留 |
| `differentPackagesNeverShareAKey` | 跨 App 不共用鍵 |
| `differentConversationsNeverShareAKey` | 跨對話不共用鍵 |
| `differentSendersNeverShareAKey` | 跨寄件者不共用鍵 |
| `theComponentsAreSeparatedByAFieldSeparator` | 欄位分隔不會造成碰撞 |

### 3.12 併發寫入者（3 項）

ConcurrentWriterInstrumentedTest — 證明兩個寫入者並存時不會失敗或遺失資料。

| 測試 | 驗證內容 |
|------|----------|
| theJournalModeIsRecorded | 記錄實際的 journal mode（實測為 truncate，不是 WAL）|
| twoWritersDoNotFailAndDoNotLoseRows | 2 個 writer x 150 列 = 300 列，0 失敗，0 遺失 |
| aReaderDoesNotBlockAWriter | 讀取與寫入同時進行，寫入 0 失敗 |

為什麼重要：listener 用單一 writer 執行緒，但 UI 另開 2 執行緒池的
資料庫 handle，兩者可能同時寫入。這個測試把那個風險量化，
而不是假設它不存在。

## 4. Python 連接器測試（23 項）

| 群組 | 涵蓋內容 |
|------|----------|
| 容器格式（12） | 正確密碼往返、錯誤密碼、竄改密文、竄改 header、損毀、截斷、空檔案、超大輸入、未知容器版本、nonce/salt 不重複、KDF 下限 |
| 契約（6） | 未知 schema 版本、未知訊息類型、缺欄位、訊息 id 穩定且內容衍生、私訊篩選 |
| 匯入（7） | 匯入冪等、錯誤密碼、未知版本、增量匯出不重複、匯入檔 0600 權限 |

---

## 5. 靜態稽核腳本

| 腳本 | 檢查內容 |
|------|----------|
| `scripts/audit.sh` | Manifest 解析、隱私權限政策、備份關閉、listener 宣告 |
| `scripts/audit-secrets.sh` | 工作樹 + **完整 Git 歷史** 的秘密掃描、`.gitignore` 覆蓋率 |
| `scripts/audit-release.sh` | **封裝後**的權限、Provider 存在性與保護層級、release 簽章身分、備份規則（用 aapt2 解析編譯後的資源） |

---

## 6. 尚未測試（誠實列出）

| 項目 | 原因 |
|------|------|
| 真實 LINE / Messenger / Instagram / Threads 推播 | 需實機與真實帳號 |
| 真實通知的影像 URI 授權行為 | 需實機 |
| Threads 私訊 vs 社交動態的實際通知形狀 | 需實機（分類邏輯已有單元測試） |
| 實體手機背景存活（MIUI / HyperOS） | 需實機 |
| Keystore 金鑰失效的實際觸發 | 需特定條件（狀態模型已有測試） |
| 資料庫損毀的破壞性測試 | 程式碼有保護，未做破壞測試 |
| 跨版本實機矩陣（9/13/14/15） | 僅驗證 Android 16 |
| Release 正式簽章 | 需擁有者建立金鑰 |
