# 跨裝置身分（Cross-Device Identity）v0.4.5

> **STATUS: CONTRACT_DEFINED — 說明哪些識別符可以跨裝置、哪些永遠不行。**

---

## 1. 三種識別符，三種命運

| 識別符 | 產生方式 | 跨裝置穩定？ | 跨重裝穩定？ |
|--------|----------|:---:|:---:|
| Keystore HMAC 指紋（`CryptoStore.fingerprintInput()`） | 本機金鑰 HMAC-SHA256 | ❌ | ❌ |
| Android SSAID | `Settings.Secure.ANDROID_ID` | ❌（重置/重裝會變） | ❌ |
| `source_device_id`（`DhcExporter.deviceId()`） | 隨機 UUID，存 SharedPreferences | ❌（每台裝置各自產生，**本來就該不同**） | ⚠️（清除 app 資料即重新產生） |
| `message_id`（`contract.compute_message_id()`） | 內容 SHA-256 | ✅ | ✅ |

只有**內容衍生**的識別符可以跨裝置。這不是實作偷懶，是密學的必然：
Keystore 金鑰不離開裝置、不可匯出，所以任何依賴它的值都無法在第二台
裝置上重現。

## 2. 為什麼指紋不能跨裝置比較（具體機制）

`CryptoStore.fingerprintInput()` 用 AndroidKeyStore 裡的金鑰對輸入做
HMAC。該金鑰：

1. 產生於每台裝置第一次啟動時，`setUserAuthenticationRequired` 等屬性
   由 `CryptoStore` 決定，材料來自裝置硬體信任根（TEE/StrongBox）。
2. **不可匯出**：`KeyStore` 條目沒有 `getKey()` 途徑可以拿到原始位元組
   （除非金鑰以加密形式離線備份，本專案不做）。
3. 因此同一個輸入在裝置 A 與裝置 B 上產生**完全無關**的 HMAC 值。

結論：**任何「比對兩台裝置的訊息指紋」的設計都註定失敗**。
跨裝置等值判斷只有兩條路：

- 比對**明文內容衍生**的雜湊（`message_id`）—— 匯出套件裡已經有；
- 或比對**套件密文容器**的金鑰指紋（使用者密碼的 PBKDF2 輸出）——
  這只能證明「同一個密碼」，不能證明「同一則訊息」。

## 3. 本專案的取捨

| 需求 | 採用 | 理由 |
|------|------|------|
| 「這則訊息我在另一台裝置也收過」 | `message_id` 相等 | 內容衍生，冪等合併的基石 |
| 「這個匯出檔來自哪台裝置」 | `source_device_id` | 只是標籤，不做等值判斷的依據 |
| 「這個匯出檔沒被改過」 | 容器 AEAD（AES-256-GCM） | 竄改會解密失敗，不需要指紋 |
| 「兩台裝置是同一個使用者」 | **不做** | 需要配對協定，見 roadmap，本輪不做 |

## 4. 給未來同步層的約束

1. 任何跨裝置疑識判斷**必須**基於 `message_id`；引入第二種跨裝置
   識別符時必須同樣是內容衍生的，並新增契約版本。
2. `source_device_id` 只能做顯示與歸因，**不得**參與去重、排序或衝突解決。
3. 如果未來需要「裝置等值」（配對後的兩台裝置互認），必須走
   `docs/roadmap/MULTI-DEVICE-SYNC.md` §1.1 的配對協定，
   用配對時交換的金鑰建立**共享**的識別，而不是試圖重現 Keystore 金鑰。
4. 指紋（Keystore HMAC）在本專案中的唯一用途是**單機**上讓使用者目視
   確認金鑰存在且未換 —— 它從來不是同步原語。
