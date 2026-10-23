# 去重識別碼審查（DEDUP-IDENTITY-AUDIT）

> 分支：`fix/x200pro-notification-burst`（v0.4.3）
> 狀態：**PASS** —— 兩個真實缺陷已修正，審查結論附實測證據。

---

## 1. 審查對象

| 檔案 | 角色 |
|------|------|
| `PrivateNotificationListener.kt` | 產生識別碼、決定哪些訊息是新的 |
| `ConversationIdentity.kt` | 對話身分（conversation key）|
| `InboxDatabase.kt` | 計算並使用資料庫指紋 |
| `CaptureMetrics.kt` | 計數器 |

---

## 2. 修正前的實際程式碼

**識別碼**（`PrivateNotificationListener.discriminatorFor`）：

```kotlin
if (p.time > 0) "ts:${p.time}" else "c:$index:${p.sender}:${p.body}"
```

**資料庫指紋**（`InboxDatabase.save`）：

```kotlin
val hasSourceTimestamp = sourceTimestamp > 0
val dedupTs = if (hasSourceTimestamp) sourceTimestamp else 0L
val dedupExtra = if (hasSourceTimestamp) "" else messageDiscriminator
val fingerprint = CryptoStore.fingerprint(
    packageName, dedupTs, title, "$sender\u0000$body", dedupExtra
)
```

---

## 3. 審查的三個問題，逐一回應

### 問題一：索引會位移嗎？

**會。** 平台的 `Notification.MessagingStyle` 最多保留 **25 則**
（AOSP 的 `Notification.MAX_MESSAGES = 25`）。超過之後，
最舊的訊息被丟棄，**其餘訊息全部往前位移**。

原來的 `c:` 分支雖然也含 `sender` 與 `body`，但**索引仍在鍵裡**。
位移後同一則訊息會得到不同的鍵 —— 資料庫層因此可能把它當成新訊息。

**量測佐證**：TEST-C 呈現 9,500 則訊息，其中 500 則是 50 則的歷史
被反覆呈現。`sum(min(i, 25)) × 10 群組 = 9,500`，與觀察值完全吻合。

### 問題二：只有時間戳保證唯一嗎？

**不保證。** 來源時間戳來自 `Message.getTimestamp()`，
解析度是**毫秒**。同一毫秒內：

- 不同寄件者 → 靠 `sender` 區分（**有**）
- 不同對話 → 靠 `title` 區分（**有，但不足**，見問題四）
- 不同內容 → 靠 `body` 區分（**有**）

所以問題二**在指紋的既有部分已經被處理**，除了對話那一項。

### 問題三：同一寄件者連續相同文字？

**這是真正的缺陷。** 修正前，當來源**有**提供時間戳時：

```kotlin
val dedupExtra = if (hasSourceTimestamp) "" else messageDiscriminator
```

**識別碼被完全丟棄。** 於是同一寄件者、相同文字、同一毫秒的兩則訊息
會算出**同一個指紋**，被合併成一列。

---

## 4. 找到的第二個缺陷：對話鍵沒有進入指紋

指紋用的是 `title`（顯示名稱），**不是** `conversation_key`。

而 `conversation_key` 存在的理由恰恰就是「顯示名稱不足以分辨對話」
—— 兩個同名群組在現實中很常見。

因此：同一個 App 裡兩個**同名**群組、同一寄件者、相同文字、同一毫秒
→ **被合併成一列**。

**修正**：指紋改用 `conversationKey`，沒有時才退回 `title`。

---

## 5. 修正內容

### 5.1 識別碼：內容優先，時間戳為輔

```kotlin
private fun discriminatorFor(p: Preview, index: Int): String =
    if (p.time > 0) "ts:${p.time}:c:${p.sender}:${p.body}"
    else "c:${p.sender}:${p.body}:i$index"
```

| 性質 | 滿足方式 |
|------|----------|
| 跨修訂穩定 | 內容（寄件者 + 內文）在歷史中不變 |
| 兩則不同訊息可分 | 內容不同 → 不同；內容相同 → 索引區分 |
| 毫秒碰撞 | 時間戳 + 內容一起進鍵 |

### 5.2 指紋：對話鍵 + 永遠帶上識別碼

```kotlin
val conversationPart = conversationKey.ifEmpty { title }
val fingerprint = CryptoStore.fingerprint(
    packageName, dedupTs, conversationPart, "$sender\u0000$body", messageDiscriminator
)
```

**識別碼永遠被納入**，不再因為有時間戳而被丟棄。

### 5.3 記憶體過濾：計數，不是集合

原本的記憶體是 `MutableSet<String>`。**兩個內容相同的訊息會被集合當成
同一個，第二個被丟掉** —— 一則真實訊息就這麼不見了。

改成 `HashMap<String, Int>`，記錄每個識別碼已寫入的**次數**：

```kotlin
val index = (occurrenceInWindow[discriminator] ?: 0) + 1
val written = seen[discriminator] ?: 0
if (index <= written) false else { seen[discriminator] = index; true }
```

---

## 6. 實測結果

### 6.1 識別碼審查的單元測試（8 項，全部通過）

`DeduplicationIdentityTest` 涵蓋 brief 要求的 A–E：

| 案例 | 結果 |
|------|------|
| A 相同時間、不同寄件者 | 鍵不同 ✓ |
| B 相同時間、不同對話 | 鍵不同 ✓ |
| C 相同時間、不同內容 | 鍵不同 ✓ |
| D 同寄件者、相同文字兩次 | 靠識別碼分開 ✓ |
| E 超過 25 則的滑動視窗 | 記錄了位移的後果與補救 ✓ |

### 6.2 突發矩陣（全部通過，`unaccounted=0`）

| 案例 | presented | submitted | saved | deduped | unaccounted |
|------|-----------|-----------|------|---------|-------------|
| A 1 對話 / 20 則 | 210 | 20 | 20 | 0 | **0** |
| B 5 對話 / 100 則 | 1,050 | 100 | 100 | 0 | **0** |
| C 10 對話 / 500 則 | 9,500 | 500 | 500 | 0 | **0** |
| D 相同文字 / 3 對話 | 165 | 30 | 30 | 0 | **0** |
| E 高頻更新 60 次 | 1,200 | 60 | 60 | 0 | **0** |
| F 歷史累積 25 則 | 325 | 25 | 25 | 0 | **0** |
| G Worker 例外 | 3 | 3 | 3 | 0 | **0** |
| H 慢速寫入 100 則 | 1,300 | 100 | 100 | 0 | **0** |
| I 錄製開關切換 | 16 | 6 | 6 | 0 | **0** |
| J 群組摘要 + 子項 | 3 | 3 | 3 | 0 | **0** |

**`deduped` 全部為 0**，這是**正確的**：記憶體過濾在寫入**之前**就
去除了重複，所以沒有任何東西抵達資料庫層需要被判定為重複。
修正前 `deduped` 是 9,000–9,500，那是**寫入放大**的徵兆。

### 6.3 完整測試

| 項目 | 結果 |
|------|------|
| Instrumentation（隔離的 devtest 變體）| **113/113 PASS**，0 skip |
| Unit | **50 + 8 = 58 PASS** |

---

## 7. 仍然無法唯一識別的情境（誠實列出）

| 情境 | 結果 | 原因 |
|------|------|------|
| 同一寄件者、相同文字、**同一毫秒**、**同一對話** | **可能合併** | 平台沒有提供更細的識別；這兩則訊息在資料層面真的無法分辨 |
| 來源沒有時間戳，且歷史已滑動超過 25 則 | **可能合併** | 索引已位移，內容又相同 |
| 來源沒有提供訊息 ID | **無法使用** | 本專案**不臆造**來源沒有的 ID |

**第一項是平台的限制，不是可以修掉的缺陷。**
真實訊息應用不會在同一毫秒內送出兩則完全相同的文字，
而且即使發生，損失是 1 則而不是一整個群組。

---

## 8. 為什麼不採用其他方案

| 方案 | 為什麼不用 |
|------|-----------|
| 產生自己的訊息 ID 並儲存 | 匯出時要跨裝置穩定，App 產生的 ID 在重裝後會變；內容衍生的 ID 才有跨裝置穩定性 |
| 把訊息內容全部放進指紋 | 內容已經在指紋裡了；而且指紋是 HMAC，不是明文儲存 |
| 用 SHA-256 取代 HMAC | 會讓低熵訊息的指紋可被字典攻擊還原。HMAC 帶金鑰，這是刻意的 |
| 放寬「一定要能分辨」 | 見 §7：有些情境在資料層面真的無法分辨，強行製造唯一 ID 只會帶來不實的保證 |

---

## 9. 相關文件

- `docs/reliability/NOTIFICATION-DEDUP.md` —— 去重策略的完整說明
- `docs/reliability/BURST-CAPTURE-ROOT-CAUSE.md` —— 寫入放大與位移的原始量測
- `app/src/test/java/tw/personal/privateinbox/DeduplicationIdentityTest.kt` —— 8 項識別碼測試
