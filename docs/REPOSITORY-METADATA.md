# Repository Metadata

> 這份文件是**給後續的 Agent 或維護者**用的，
> 讓他們可以依此更新 GitHub 的 About 與 Topics。
>
> **本輪沒有修改遠端 Repository 設定**（brief 明確禁止）。

---

## 1. Description（About 欄位）

```
Offline, encrypted Android notification inbox with local message history, source filtering, and privacy-first storage.
```

**字數**：108 字元。GitHub 的 About 欄位上限是 350 字元。

**中文版**（若要設定兩種語言）：

```
離線、加密的 Android 通知收件匣：保留本機訊息歷史、可過濾來源、隱私優先的儲存。
```

---

## 2. Topics

```
android
kotlin
notification-listener
privacy
offline-first
encrypted-storage
sqlite
notification-history
open-source
```

**建議的順序**：前五個是最能說明這個專案是什麼的。

**可以考慮但尚未使用**：

| Topic | 為什麼沒放 |
|-------|-----------|
| `android-keystore` | 太細節 |
| `notification-history` | 已在清單裡 |
| `telegram-client` | **不應該放** —— 這個專案不是 Telegram 客戶端 |
| `chat` | **不應該放** —— 會讓人誤以為是聊天軟體 |

---

## 3. Homepage / Website

**留空。** 這個專案沒有網站，而放一個假的網站比不放更糟。

---

## 4. Social preview

**沒有。** 需要一張不含真實聊天內容的合成預覽圖。
**本輪未產生**（截圖需要 UI，而 brief 禁止重寫 UI，
而現有的 UI 截圖需要實機）。

---

## 5. 設定方式（由下一位 Agent 執行）

```bash
# Description
gh repo edit juhjuhx/PrivateInboxClassic \
  --description "Offline, encrypted Android notification inbox with local message history, source filtering, and privacy-first storage."

# Topics（gh repo edit 會取代整組，所以一次給全）
gh repo edit juhjuhx/PrivateInboxClassic \
  --add-topic android \
  --add-topic kotlin \
  --add-topic notification-listener \
  --add-topic privacy \
  --add-topic offline-first \
  --add-topic encrypted-storage \
  --add-topic sqlite \
  --add-topic notification-history \
  --add-topic open-source
```

**確認**：

```bash
gh repo view juhjuhx/PrivateInboxClassic --json description,repositoryTopics
```

---

## 6. 發布前的檢查

- [ ] Description 已設定
- [ ] Topics 已設定（九個）
- [ ] 語言標籤由 GitHub 自動偵測（Kotlin、Python、Shell）
- [ ] **Repository 仍是 PRIVATE** —— 除非擁有者明確決定公開
- [ ] 沒有建立公開 Release
- [ ] 沒有執行 `gh repo edit --visibility public`

---

## 7. 為什麼這個專案**不應該**被宣稱成什麼

| 錯誤的說法 | 為什麼錯 |
|-----------|---------|
| 「Telegram 客戶端」 | 它**不**讀取聊天歷史、不發送訊息、不是一個 messenger |
| 「聊天記錄備份工具」 | 它只備份**通知系統交付過的**訊息 |
| 「能還原所有撤回的訊息」 | 只有**曾經以通知形式出現過**的訊息才會被保存 |
| 「能擷取所有應用程式的通知」 | 它是**明確的來源白名單**，不是全域錄音器 |
| 「跨裝置同步」 | **沒有**雲端同步，資料完全在本機 |
