# Rust 綁定計畫（RUST-BINDING-PLAN）

> 狀態：**RESEARCH_ONLY** —— 沒有 Rust 程式碼進入生產路徑。
> 依據 `RUST-CORE-ARCHITECTURE.md` 的結論：**目前不建議遷移**。
> 這份文件描述**如果**要遷移，會是什麼樣子。

---

## 1. 綁定方式比較

| 項目 | UniFFI | 手寫 JNI | C ABI + 手動封裝 |
|------|--------|---------|-----------------|
| 型別安全 | 自動產生 | 自己維護 | 自己維護 |
| 錯誤處理 | 例外 | 自己維護 | 自己維護 |
| 建置整合 | 需額外步驟 | 需 CMake/NDK | 需 CMake/NDK |
| 授權 | **MPL-2.0** | 無 | 無 |
| 除錯 | 需要符號 | 需要符號 | 需要符號 |
| 維護成本 | 中 | 高 | 高 |

**建議：UniFFI。**

理由是這個專案的痛點是**錯誤面**（已經有過三次因為例外處理不對
而讓整個擷取停擺的教訓），而 UniFFI 的自動型別轉換與例外封裝
能減少一整類手寫錯誤。

---

## 2. 具體的建置配置

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                // Only the ABIs the app actually ships.
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../rust-core")
            version = "3.22.1"
        }
    }
}
```

```toml
# rust-core/Cargo.toml
[lib]
crate-type = ["cdylib", "staticlib"]

[dependencies]
uniffi = { version = "0.27", features = ["kotlin"] }
sha2 = "0.10"
```

```bash
# The NDK is already present in the toolchain.
cargo ndk \
  -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ./app/src/main/jniLibs build release
```

---

## 3. 呼叫成本（**必須量測，不能推測**）

| 呼叫類型 | 預期 | 為什麼這個數字重要 |
|-----------|------|------------------|
| 無配置純函式 | 數百奈秒 | JNI 邊界的成本可能超過函式本身 |
| 有字串的配置 | 數微秒 | 字串複製是主要成本 |
| 一次處理 100 則訊息 | 需要量測 | 這是實際的呼叫模式 |

**量測方法**：

1. 同一個輸入，Kotlin 實作跑 N 次，Rust 實作跑 N 次。
2. 量的是**端到端**時間（包含邊界成本），不是微基準。
3. 至少 1000 次迭代取 p50/p95/p99。

**本專案已經有一次「假設更好但實測更差」的記錄**：
`PERFORMANCE-BASELINE.md` 的金鑰快取實驗，量到 **1.38 倍變慢**，
於是被回滾。Rust 必須有同樣的證據標準。

---

## 4. 二進位大小

| 項目 | 估算 |
|------|------|
| 純邏輯 crate（release，strip）| 每個 ABI 200 KB – 1 MB |
| 含 panic 訊息 | 每個 ABI +100–300 KB |
| 目前的 APK | 5.9 MB，**無原生相依** |
| 加入後 | 每個 ABI +1–3 MB |

**目前的 APK 是完全沒有 `.so` 的**，這是它小而且沒有 ABI 相容性問題的原因。
加入 Rust 會放棄這個優勢。

---

## 5. Crash 邊界

**這是最被低估的風險。**

Rust 的 panic 預設會**中止整個進程**。在一個背景持續擷取通知的 App 裡，
一個偶發的 panic 會讓**所有擷取停止**，而症狀與「沒有通知」完全一樣。

必須的做法：

```rust
// 每個跨邊界的呼叫都要包起來
#[uniffi::export]
pub fn normalize(value: String) -> String {
    match std::panic::catch_unwind(|| normalize_impl(value)) {
        Ok(result) => result,
        Err(_) => String::new(),  // 明確的 fallback，不是崩潰
    }
}
```

而且 Kotlin 側也必須把每個 Rust 呼叫包在 `runCatching` 裡 ——
**兩層都要有**，因為 `catch_unwind` 不攔截 abort。

---

## 6. ABI 相容性

| 項目 | 說明 |
|------|------|
| 需要支援的 ABI | arm64-v8a（主流）、armeabi-v7a（舊機）、x86_64（模擬器）|
| 每個 ABI 一份 .so | APK 體積的三倍來源 |
| 最低 API | Rust 支援比 Kotlin 廣，但要對齊 minSdk 28 |
| 符號 | release 也要保留足夠的符號，否則 native crash 無法診斷 |

**16 KB page size**（Android 15+ 的要求）必須在建置時驗證，
否則新系統上會載入失敗。

---

## 7. 開發與維護成本

| 項目 | 成本 |
|------|------|
| 新增語言與工具鏈 | 一次性的，但會持續 |
| 每次改動要重建 | Rust 的編譯時間比 Kotlin 慢（尤其 release）|
| 兩種語言的程式碼導覽 | 開發者需要同時懂兩邊 |
| 差異測試的維護 | 每次邏輯變更都要兩邊同步 |
| 除錯 | native crash 的診斷需要符號與 native 工具 |

---

## 8. 如果真的要做，順序

1. **先做 PoC**：一個 crate，只有一個純函式，用 UniFFI 綁定。
2. **量測呼叫成本**：端到端，不是微基準。
3. **建立黃金集**：Kotlin 的輸出作為對照，凍結在 repo 裡。
4. **差異測試**：Rust 對黃金集，必須**位元級**一致。
5. **影子模式**：Kotlin 與 Rust 同時跑，比對結果，Rust 不寫入。
6. **feature flag 切換**：一次只切一個函式，保留 Kotlin 實作為後備。
7. **重新評估**：如果量測沒有改善，回到 Kotlin。

**第 1 步到第 4 步的成本很低**，而且**不需要**動任何生產路徑。
那才是值得先做的部分。

---

## 9. 判斷準則

**在下列證據出現之前，不應該把任何 Rust 放進生產路徑：**

- [ ] 差異測試涵蓋 30 個以上獨立情境
- [ ] Rust 的端到端延遲**優於** Kotlin（不是相等）
- [ ] 二進位大小的增加被接受
- [ ] panic 邊界有 `catch_unwind` **且**有量測過它的失敗率
- [ ] 授權審查完成（MPL-2.0 的影響、DHC 的 AGPL 邊界）
- [ ] 16 KB page size 驗證通過
