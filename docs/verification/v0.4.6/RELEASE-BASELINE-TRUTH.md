# Release Baseline Truth (R00) — the ONE valid state

全部數值來自實際檔案與最新命令輸出（2026-10-27）。歷史數字見各文件，
不得以此文件改寫過去執行結果。

## 唯一有效狀態
| 項目 | 值 | 來源 |
|---|---|---|
| HEAD | `bb36c83f524c0aa87ba04680f0f4c0bfea79318f` | `git rev-parse HEAD` |
| Branch | `v045-reliability`（tracks origin/fix/x200pro-notification-burst, ahead 7） | `git branch -vv` |
| Dirty | 80 entries（11 tracked modified + 69 untracked；無 commit） | `git status --porcelain=v1 -uall` |
| APK | `app-prod-debug.apk`，**versionCode 11 / versionName 0.4.6** | aapt2 badging（實檔） |
| APK SHA-256 | `a817434edefcb8f5becec48b4ab46ba91badc80bed2b294c0355247c1d6a653c` | shasum（實檔，key A 重建） |
| Signing | Debug key A `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | apksigner（實檔） |
| Instrumentation | 239 run / 1 flaky (SystemE2E link) / 0 product failures | `/tmp/t05-final.log`（最新全量） |
| JVM | 157/157 | test-results XML |
| DHC | Python 27 + Kotlin 14 + real round trip PASS | host_interop.sh |
| Flake | `SystemNotificationE2E` link — FLAKY_UNRESOLVED（見 FLAKE-ANALYSIS.md） | — |
| Blockers | 1) 無 release keystore（PRODUCTION_SIGNING_BLOCKED）2) 無真獨立審查（T13=SELF_REVIEW_ONLY）3) 無實機/OEM 驗證 | — |
| Public upgrade | VERIFIED — v0.4.4-alpha (key A) → v0.4.6 prod (key A) via adb install -r | 實測於 emulator-5554 |

## 漂移調和（歷史紀錄 vs 真相）
| 舊報告 | 舊值 | 真相 |
|---|---|---|
| FINAL-V046-HANDOFF | 238 run / 2 failures；APK `450a951a…` | 該值對應**當時**建置；現 APK 為 `a817434e…`（v11/0.4.6，key A 重建以匹配公開 v0.4.4-alpha 簽章）。歷史數字保留於 git/快照，不改寫 |
| AGENT_STATE 舊段 | 239/1 flaky | 與最新全量一致 |
| Dirty 數量 | 73 / 74 / 82 不一 | 以 `git status --porcelain=v1 -uall` 當下為準（隨新文件增加而變動） |

## 結論
公開發布一律以本文件 + 實檔 SHA 為準。
