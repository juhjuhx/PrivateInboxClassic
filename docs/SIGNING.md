# 簽章與發布（SIGNING）

本文件說明 Private Inbox Classic 的三種簽章身分、可重現的建置方式，
以及每種身分的用途與風險。

---

## 1. 三種簽章身分

| 身分 | 用途 | 金鑰位置 | 能否公開 |
|------|------|----------|----------|
| **Debug** | 本機測試、`adb install -r` | `keystore/debug.keystore`（git-ignored） | 僅供測試，**不可**散布 |
| **Release** | 正式發行 APK | 使用者自備，**不進 Git** | 私密 |
| **Upload** | Google Play 上傳 | 使用者自備，**不進 Git** | 私密 |

**Debug 與 Release 的簽章不同，因此兩者不能互相覆蓋安裝。**
Android 會以簽章判定「是否為同一個 App」；簽章不同 = 不同的 App。
使用者若已安裝 Debug 版，之後要裝 Release 版必須先解除安裝
（會遺失本機資料）。

**Upload 與 Release 分離的理由**：Google Play App Signing 允許
「上傳金鑰」與「應用程式簽章金鑰」不同。上傳金鑰遺失時可以向 Google
申請替換，而不必改變已上架 App 的簽章。

---

## 2. 已確認的簽章指紋（本專案實測）

本工作區存在**兩份不同的 debug keystore**，這是造成舊 APK 無法覆蓋安裝的
根本原因：

| 金鑰 | 憑證 SHA-256 | 狀態 |
|------|--------------|------|
| A：`~/.android/debug.keystore` | `46:48:8E:99:37:3F:3C:1A:F0:C6:30:64:2F:2D:9E:C4:59:B8:DB:67:13:91:F3:AD:81:7E:FB:3A:AE:99:DA:94` | Android 預設產生 |
| B：`.android-home/debug.keystore` | `C8:76:05:1E:28:79:D0:05:AB:F6:10:27:7E:58:F4:0B:72:8C:62:CA:C3:6A:77:48:F3:35:72:00:AF:33:77:A4` | 本專案建置時使用 |

**已安裝於模擬器上的 APK 實際使用金鑰 B**（以 `apksigner verify --print-certs`
對裝置上的 `base.apk` 實測確認）。

**因此本專案採用金鑰 B**，並複製到專案內的
`keystore/debug.keystore`（已 git-ignored），讓每一台機器都用同一把
金鑰簽署，`adb install -r` 才能在每次重新建置後正常運作。

> **不得自行輪替這把 debug 金鑰。** 換成金鑰 A 會讓目前已安裝的 App
> 無法被覆蓋更新。若使用者手機上的簽章與本專案產生的不同，
> 必須明確告知使用者需先解除安裝，**不得**為了讓安裝成功而清除使用者資料。

---

## 3. 可重現的建置流程

### 3.1 設定檔

`keystore/signing.properties`（**已 git-ignored**）：

```properties
debug.storeFile=keystore/debug.keystore
debug.storePassword=android
debug.keyAlias=androiddebugkey
debug.keyPassword=android

# release.storeFile=keystore/release.keystore
# release.storePassword=
# release.keyAlias=
# release.keyPassword=

# upload.storeFile=keystore/upload.keystore
# upload.storePassword=
# upload.keyAlias=
# upload.keyPassword=
```

範本：`keystore/signing.properties.example`

也可以完全用命令列參數覆寫，不必建立檔案：

```bash
./gradlew :app:assembleRelease \
  -Prelease.storeFile=/secure/path/release.keystore \
  -Prelease.storePassword=... \
  -Prelease.keyAlias=... \
  -Prelease.keyPassword=...
```

**密碼不會被寫入任何產出物、日誌或 Agent 對話。**
`keystore/` 目錄整個被 git-ignore，只有 `signing.properties.example` 會進版控。

### 3.2 建立新的正式金鑰

```bash
keytool -genkeypair -v \
  -keystore keystore/release.keystore \
  -alias privateinbox \
  -keyalg RSA -keysize 4096 -validity 10000
```

> **本專案刻意不自行產生正式金鑰。**
> 正式私鑰一旦產生就必須由使用者保管與備份；Agent 產生的金鑰若無法交接
> 或無法復原，等於永久失去更新既有安裝的能力。
> 請使用者在自己的機器上執行上面的指令，並自行決定備份位置。

### 3.3 建置

```bash
# Debug（本機測試）
./gradlew :app:assembleDebug

# Release APK
./gradlew :app:assembleRelease

# Release AAB（Google Play）
./gradlew :app:bundleRelease
```

**當 release 金鑰未設定時**，`assembleRelease` 會：
1. 在建置輸出印出明確警告：
   `SIGNING: no release keystore configured. The release build will be signed with the DEBUG key and is NOT distributable.`
2. 仍然以 debug 金鑰簽署，讓開發者可以做本機 smoke test。

這樣的產出**必須**視為不可散布版本。

---

## 4. 驗證簽章

```bash
$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner verify --print-certs <apk>
```

輸出範例：

```
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
Signer #1 certificate SHA-256 digest: c876051e...
```

把這個 digest 記錄到 `docs/HANDOFF.md` 與發布說明中，
使用者就能確認手上安裝的版本與新產出的版本是否同源。

---

## 5. Git 安全

`.gitignore` 已排除：

```
*.keystore
*.jks
keystore/          (僅保留 signing.properties.example)
local.properties
```

**稽核結果**：本專案的 Git 歷史中**沒有**任何 keystore 或密碼檔案。
稽核指令見 `scripts/audit-secrets.sh`。

---

## 6. 已知限制

- Debug 金鑰位於 `keystore/debug.keystore`，**不在** Git 中。
   clone 的人必須自行產生一份；簽章指紋因此不會完全一致。
  對本機開發無影響（各自產生 debug 金鑰即可），但要與既有安裝互相覆蓋時
  必須使用同一把金鑰。
- Release / Upload 金鑰完全由使用者保管，Agent 無法驗證其指紋。
- `bundleRelease` 的 upload 金鑰透過 AGP 8 的 `androidComponents` API 套用；
  若未設定 upload 金鑰，AAB 會沿用 release 金鑰簽署（Play 允許此設定）。
