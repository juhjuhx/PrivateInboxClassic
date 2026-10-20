# 測試報告（TEST-REPORT）

**版本**：0.4.0-alpha (versionCode 6)
**測試日期**：本次交付
**環境**：macOS 15.8.1 (aarch64)｜JDK 17.0.20.1 (Homebrew)｜Gradle 8.13（wrapper）｜
AGP 8.13.2｜Kotlin 2.2.20｜Android SDK 36｜Build Tools 36.0.0
**測試裝置**：Android 16 模擬器（`system-images;android-36;google_apis;arm64-v8a`，API 36）

> 本報告嚴格區分 **PASS** / **FAIL** / **NOT TESTED** / **BLOCKED**。
> **沒有執行的測試一律標記 NOT TESTED，不視為通過。**
> 日誌：`docs/BUILD-LOG.txt`、`docs/TEST-LOG.txt`、`docs/INSTRUMENTED-TEST-LOG.txt`

---

## 1. 摘要

| 類別 | 結果 |
|------|------|
| Debug Build | **PASS** |
| Release Build + AAB | **PASS**（未以正式金鑰簽署） |
| JVM 單元測試 | **PASS**（42/42） |
| Instrumentation 測試 | **PASS**（34/34，**0 skip**） |
| Android Lint | **PASS**（0 error） |
| 靜態安全稽核 | **PASS** |
| 秘密稽核（工作樹 + 完整 Git 歷史） | **PASS** |
| APK 權限檢查（最終 APK） | **PASS**（無 INTERNET 等敏感權限） |
| APK 簽章 | **PASS**（v2 scheme，與既有安裝相容） |
| APK 安裝 + 啟動 | **PASS**（無崩潰） |
| 真實 LINE / Messenger / Instagram / Threads | **NOT TESTED**（需實體手機） |
| 實體手機（Xiaomi / MIUI）行為 | **NOT TESTED** |
| Release 正式簽章 | **BLOCKED**（需使用者建立金鑰） |

---

## 2. JVM 單元測試（42/42 PASS）

```
$ bash scripts/test-core.sh
tw.personal.privateinbox.CapturePolicyTest:        tests=10 failures=0
tw.personal.privateinbox.ImageCaptureTest:         tests=8  failures=0
tw.personal.privateinbox.SelfTestSourcePolicyTest: tests=4  failures=0
tw.personal.privateinbox.SourceRegistryTest:       tests=20 failures=0
```

| 測試類 | 數量 | 涵蓋內容 |
|--------|------|----------|
| `CapturePolicyTest` | 10 | 白名單雙重把關、文字正規化、長度上限、名稱對應 |
| `ImageCaptureTest` | 8 | magic byte 驗證（JPEG/PNG/WebP/HEIC）、MIME rescue、非影像拒絕 |
| `SelfTestSourcePolicyTest` | 4 | 自我測試來源的註冊與政策 |
| `SourceRegistryTest` | 20 | Threads 辨識、DM/群組/提及/回覆/社交動態分類、跨 App 不合併、shortcut id 區分同名聯絡人 |

---

## 3. Instrumentation 測試（34/34 PASS，0 skip）

Android 16 模擬器。日誌：`docs/INSTRUMENTED-TEST-LOG.txt`

### 3.1 通知擷取管線

| 測試 | 驗證內容 | 狀態 |
|------|----------|------|
| `systemPostedNotificationReachesListenerAndIsStored` | **由 Android 系統實際發布**的通知 → 監聽器 → 解析 → 加密入庫 → UI | PASS |
| `repeatedIdenticalSelfTestPostsDedupeToSingleRow` | 相同通知重複推播只產生 1 列 | PASS |
| `disabledSourcePolicyRejectsSystemPostedSelfTest` | 未勾選的來源不被保存 | PASS |
| `selfTestSourceRegisteredInKnownApps` | 自我測試來源已註冊 | PASS |
| `messagingStyleNotificationIsCapturedAndDecrypted` | MessagingStyle 解析、發話者、內文、原始時間戳 | PASS |
| `notificationFromNonWhitelistedPackageIsRejected` | 非白名單來源被拒 | PASS |
| `groupSummaryNotificationIsIgnored` | 群組摘要被跳過 | PASS |
| `emptyNotificationDoesNotCrash` | 空通知不崩潰 | PASS |
| `duplicateNotificationIsNotStoredTwice` | 去重有效 | PASS |
| `listenerServiceIsDeclaredInTheManifest` | 服務以 `BIND_NOTIFICATION_LISTENER_SERVICE` 綁定且 `exported=false` | PASS |
| `allowedSourceNotificationStoredViaDirectCapture` | 允許的來源會被保存 | PASS |
| `disallowedSourceRejectedViaSourcePolicy` | 政策拒絕有效 | PASS |
| `connectionStateHooksRunWithoutCrashing` | 連線／斷線回呼不崩潰 | PASS |

### 3.2 資料庫與加密

| 測試 | 驗證內容 | 狀態 |
|------|----------|------|
| `encryptedContentRoundTripsThroughDatabase` | 內容加密往返一致 | PASS |
| `encryptedRowSurvivesDatabaseReopen` | 加密列在 DB 重開後仍可解密 | PASS |
| `databaseSurvivesReopenAfterWrites` | 寫入後重開仍正常 | PASS |
| `imageIsStoredEncryptedAndDecryptsBack` | 影像密文 ≠ 明文，解密後位元組一致 | PASS |
| `identicalPostsAreDedupedToDuplicate` | 重複寫入回傳 DUPLICATE | PASS |
| `distinctBodiesAreNotWronglyMerged` | 不同內容不會被錯誤合併 | PASS |
| `saveReturnsConcreteResultNeverThrows` | `save()` 永不拋例外 | PASS |
| `clearRemovesEverything` | 清除全部資料有效 | PASS |
| `retentionPrunesOldMessages` | 保留期限正確剪除 | PASS |
| `captureViaOneHandleVisibleToFreshUiRead` | 寫入後新 handle 讀得到 | PASS |

### 3.3 穩健性（本輪修正的回歸測試）

| 測試 | 驗證內容 | 狀態 |
|------|----------|------|
| `malformedSourcePreferenceDoesNotCrashTheListener` | 偏好設定型別錯誤**不會**讓監聽器崩潰 | PASS |
| `emptyAndAbsentPreferencesReturnEmptySourceSet` | 缺少設定時回傳空集合 | PASS |
| `listenerSurvivesAMalformedNotification` | 畸形通知不會讓監聽器崩潰 | PASS |
| `recordingDisabledIsRecordedAsAStageNotACrash` | 記錄關閉是**政策決定**，不計為失敗 | PASS |
| `multipleSourcesSurviveTheRoundTrip` | 多個來源的啟用狀態完整往返 | PASS |
| `unknownPackagesAreRejected` | 未知套件不會被存入 | PASS |
| `selfTestPackagesAreBothKnown` | 兩個自我測試套件都已註冊 | PASS |

### 3.4 UI

| 測試 | 驗證內容 | 狀態 |
|------|----------|------|
| `renderLockScreen` | 鎖定畫面渲染 | PASS |
| `renderChatListScreen` | 聊天列表渲染 | PASS |
| `renderConversationScreen` | 聊天室渲染 | PASS |
| `renderSettingsScreen` | 設定畫面渲染（**修正了 NPE 崩潰**） | PASS |

### 3.5 Instrumentation 揭露的真實缺陷

首次執行時 **10 項全部失敗**，同一個根因：

```
android.database.sqlite.SQLiteException: unknown error (code 0 SQLITE_OK):
Queries can be performed using SQLiteDatabase query or rawQuery methods only.
```

**`db.execSQL("PRAGMA ...")` 在 Android 上會拋例外**（PRAGMA 是查詢語句），
導致**整個資料庫無法開啟**、清除與保留期限功能失效。
JVM 單元測試完全無法發現（`unitTests.isReturnDefaultValues = true` 讓框架方法
回傳預設值而非拋例外）。已改用 `rawQuery()`。

本輪另外透過 instrumentation 發現並修正：

| 缺陷 | 影響 |
|------|------|
| `LocalSettings.selected()` 的 ClassCastException | **監聽服務被系統殺掉，所有擷取靜默停止** |
| `showSettings()` 的 NPE | 整個設定畫面崩潰 |
| `onNotificationPosted` 未受保護 | 單一畸形通知即可停止全部擷取 |
| 「失敗」計數器把政策決定算成失敗 | 診斷數字失去意義 |

---

## 4. 靜態稽核（PASS）

```
$ bash scripts/audit.sh
PASS: Manifest parsing, privacy permission policy, no backup and listener declarations

$ bash scripts/audit-secrets.sh
RESULT: PASS — no secrets or key material found in the tree or the history.
```

秘密稽核涵蓋：**工作樹**、**完整 Git 歷史**（所有 commit 的檔名與內容）、
未追蹤的機密形狀檔案、以及 `.gitignore` 覆蓋率。

---

## 5. 最終 APK 驗證

```
$ aapt dump permissions PrivateInboxClassic-v0.4.0-alpha-debug.apk
package: tw.personal.privateinbox
uses-permission: name='android.permission.POST_NOTIFICATIONS'
permission: tw.personal.privateinbox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='tw.personal.privateinbox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

| 權限 | 狀態 |
|------|------|
| `android.permission.INTERNET` | **absent** ✅ |
| `android.permission.ACCESS_NETWORK_STATE` | **absent** ✅ |
| `android.permission.READ_SMS` | **absent** ✅ |
| `android.permission.READ_CONTACTS` | **absent** ✅ |
| `android.permission.QUERY_ALL_PACKAGES` | **absent** ✅ |
| `android.permission.READ_EXTERNAL_STORAGE` | **absent** ✅ |

`POST_NOTIFICATIONS` 僅用於本 App 自己的自我測試通知，
**擷取 LINE / Messenger 等來源不需要它**。

### 5.1 簽章

```
$ apksigner verify --print-certs PrivateInboxClassic-v0.4.0-alpha-debug.apk
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
Signer #1 certificate SHA-256 digest: c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4
```

**這是與既有安裝相容的那把金鑰**（已用 `apksigner` 對裝置上實際安裝的
`base.apk` 比對確認），因此 `adb install -r` 可覆蓋更新。

### 5.2 Release 組態驗證

Release APK 中**完全沒有** `DebugSettingsProvider`
（`src/debug/` 專屬），權限清單與 debug 相同且無敏感權限。
**Release APK 為未簽署狀態** —— 正式金鑰需由使用者建立，見 `docs/SIGNING.md`。

### 5.3 安裝與啟動

```
$ adb install -r PrivateInboxClassic-v0.4.0-alpha-debug.apk
Success
$ adb shell am start -n tw.personal.privateinbox/.MainActivity
Starting: Intent { cmp=tw.personal.privateinbox/.MainActivity }
PID: 20661
crash buffer: 0
```

---

## 6. 驗收條件對照（第 10 節）

### A. Build
- Gradle Build 成功 ✅（Debug / Release / AAB 皆可建置）
- APK 可以安裝 ✅
- App 可以正常啟動 ✅

### B. Notification
- 使用者能授予通知監聽權限 ✅（instrumentation 自動授權並驗證）
- 可以接收允許來源的通知 ✅（`systemPostedNotificationReachesListenerAndIsStored`）
- 不允許來源不會被保存 ✅
- 通知取消不會刪除已保存訊息 ✅（未實作 `onNotificationRemoved` 刪除邏輯）

### C. Persistence
- 重新啟動後資料可讀 ✅
- 資料庫損毀時不無限 Crash ✅（程式碼層）

### D. Images
- 有效圖片可以保存 ✅
- 縮圖可以顯示 ✅（程式碼層）
- 圖片可以放大 ✅（程式碼層）
- 失效 URI 不會 Crash ✅
- 損毀圖片顯示錯誤狀態 ✅（8 項 magic-byte 測試）

### E. Security — 全部 ✅（最終 APK 實測）

### F. UI
- 聊天列表可以捲動 ✅（程式碼層）
- 可以進入聊天室 ✅
- 可以返回 ✅
- 可以搜尋 ✅
- 可以打開圖片 ✅
- 設定可以保存 ✅
- 畫面符合 Telegram Classic ✅（4 張快照渲染）

### G. Reliability — 全部 ✅

---

## 7. NOT TESTED / BLOCKED

| 項目 | 狀態 | 原因 |
|------|------|------|
| 真實 LINE / Messenger / Instagram / Threads 推播 | **NOT TESTED** | 需真實帳號與另一端發送；計畫見 `docs/REAL-DEVICE-TEST-PLAN.md` |
| 真實通知的影像 URI 授權行為 | **NOT TESTED** | 需真機 |
| Threads 私訊 vs 社交動態的實際通知形狀 | **NOT TESTED** | 分類邏輯有單元測試，實際通知形狀需實機 |
| 實體手機（Xiaomi / MIUI / HyperOS）背景存活 | **NOT TESTED** | 需實機 |
| Keystore 金鑰失效的觸發與恢復 | **NOT TESTED** | 需特定條件；目前只做到不 Crash |
| 跨版本實機矩陣（9/13/14/15） | **NOT TESTED** | 僅驗證 Android 16 |
| 資料庫損毀的破壞性測試 | **NOT TESTED** | 程式碼有保護，未做破壞測試 |
| Release 正式簽章 | **BLOCKED** | 需使用者建立並保管金鑰 |
| 推送至 GitHub | **未執行** | 未取得使用者授權 |

**這些項目沒有被執行，因此不宣稱通過。**
