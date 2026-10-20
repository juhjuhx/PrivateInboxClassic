# 磁碟空間手冊（DISK-SPACE-RUNBOOK）

本專案在開發期間**真的**因為磁碟空間不足而中斷過一次。
這份手冊記錄那次故障的診斷與處理，避免下次重蹈覆轍。

---

## 1. 故障實錄

### 1.1 症狀

```text
FATAL | Your device does not have enough disk space to run avd: `test36`.
```

Emulator 完全無法啟動，ADB 顯示 `device offline`，
`adb install` 之後所有指令都沒有回應。

### 1.2 真正的失敗類型

**ENOSPC（磁碟空間不足）**，不是工具額度耗盡、不是 context 耗盡、
不是模型路由失敗。

診斷指令與結果：

```bash
$ df -h /System/Volumes/Data
Filesystem      Size  Used Avail Capacity iused  ifree %iused  Mounted on
/dev/disk3s5   228Gi  204Gi  1.7Gi    100%    2.0M   18M     5%  /System/Volumes/Data
```

**只有 1.7 GiB 可用**，而 Android 16 系統映像本身就需要 4.3 GiB，
加上 AVD 的 userdata 需要 2 GiB 以上。

`df -i` 顯示 inode 尚有 52,910,088 個可用 → **不是** inode 耗盡。

### 1.3 空間去哪了

| 目錄 | 大小 | 可否刪 |
|------|------|--------|
| `android-sdk/system-images` | 4.3 GiB | 否（測試需要） |
| `android-sdk/emulator` | 1.2 GiB | 否（測試需要） |
| `.android-home/avd/test36.avd/userdata-qemu.img.qcow2` | 2.2 GiB | 可（可重建） |
| `gradle_home` | 1.0 GiB | 部分可（build cache 可重建） |

### 1.4 處理方式（全部非破壞性）

```bash
# 1. 刪掉可重建的 Gradle build cache
rm -rf gradle_home/caches/build-cache-*

# 2. 刪掉 AVD 的 userdata（可由系統映像重建）
rm -rf .android-home/avd/test36.avd/userdata-qemu.img.qcow2

# 3. 縮小 AVD 的資料分割區上限
sed -i.bak 's/disk.dataPartition.size=6G/disk.dataPartition.size=2G/' \
  .android-home/avd/test36.avd/config.ini
```

結果：可用空間 1.7 GiB → **3.9 GiB** → 後續 22–25 GiB。

**沒有**刪除任何使用者資料、Keystore、通知資料庫或原始碼。

---

## 2. 預防

### 2.1 開始工作前先看空間

```bash
df -h /System/Volumes/Data
```

**低於 10 GiB 就先清理**，不要等 emulator 開不起來。

### 2.2 可安全刪除的項目

| 項目 | 指令 | 影響 |
|------|------|------|
| Gradle build cache | `rm -rf $GRADLE_USER_HOME/caches/build-cache-*` | 下次建置慢一點 |
| Gradle transforms | `rm -rf $GRADLE_USER_HOME/caches/transforms-*` | 下次建置慢一點 |
| AVD userdata | `rm -rf $ANDROID_USER_HOME/avd/*/userdata-qemu.img.qcow2` | 模擬器重新初始化 |
| 舊 AVD snapshot | `rm -rf $ANDROID_USER_HOME/avd/*/snapshots/` | 模擬器開機慢一點 |
| 專案的 build 目錄 | `rm -rf app/build` | 需要重新建置 |

### 2.3 **不可**刪除的項目

```text
rm -rf ~                        # 絕對不行
rm -rf $PROJECT_ROOT             # 絕對不行
git clean -fdx                  # 會刪掉未追蹤的原始碼
git reset --hard                # 會丟失未提交的工作
rm -rf $ANDROID_SDK             # 需要重新下載 5.5 GiB
rm keystore/*                   # 會讓已安裝的 App 無法覆蓋更新
rm -rf <user database>          # 使用者的通知資料
```

---

## 3. 相關故障的判別

| 症狀 | 可能原因 | 判別方法 |
|------|----------|----------|
| emulator 說「not enough disk space」 | **ENOSPC** | `df -h` |
| emulator 說「Running multiple emulators with the same AVD」 | 舊行程未結束 | `pkill -f qemu-system` |
| Gradle 說 `libnative-platform.dylib` 載入失敗 | home 不可寫 | `GRADLE_USER_HOME` |
| Gradle 說 `Could not connect to Kotlin compile daemon` | home 不可寫 | `in-process` 策略 |
| `SecurityException: not encryption aware!` | **裝置鎖屏** | `dumpsys user` 看 State |
| ADB `device offline` | emulator 崩潰或磁碟不足 | `tail /tmp/emu*.log` |

---

## 4. 清理前的分類

在刪除任何東西之前，先分類：

| 類別 | 例子 | 處理 |
|------|------|------|
| **USER DATA** | 通知資料庫、Keystore、實機上的 App 資料 | **絕不刪** |
| **SOURCE CODE** | `app/src/`、`docs/`、`integrations/` | **絕不刪** |
| **BUILD ARTIFACTS** | `app/build/`、APK | 可刪（可重建） |
| **REGENERABLE CACHE** | Gradle cache、AVD userdata、system images | 可刪（可下載） |
| **FORENSIC EVIDENCE** | 測試 XML、建置日誌、截圖、稽核報告 | **保留**（或先歸檔） |

**若任何刪除動作會碰到 USER DATA 或 SOURCE CODE，停下來問使用者。**
