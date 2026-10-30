# Release Baseline Truth (R00) — the ONE valid state

全部數值來自實際檔案與最新命令輸出（2026-10-27）。歷史數字見各文件，
不得以此文件改寫過去執行結果。

## 唯一有效狀態
| 項目 | 值 | 來源 |
|---|---|---|
| HEAD | `122cddaa84a1ae62b808e6ff3c682ea895959008` | `git rev-parse HEAD` |
| Branch | `v046-integration-repair-final`（PR #4） | `git branch --show-current` |
| Dirty | clean（本輪結束時） | `git status --short` |
| APK | `PrivateInboxClassic-v0.4.6-alpha-debug.apk`（`app-prod-debug.apk`），**versionCode 11 / versionName 0.4.6** | aapt2 badging（實檔） |
| APK SHA-256 | `d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67` | shasum（實檔，`prodDebug`，2026-10-30 重建） |
| Signing | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`（keystore `.android-home/debug.keystore`） | apksigner（實檔） |
| Instrumentation | **290 / 290 PASS**，0 failures，0 skipped | GitHub Actions run 41011478801 + 本機重跑 |
| JVM | 157 / 157 | test-results XML |
| Lint | PASS | `:app:lintDevtestDebug` |
| DHC | Python + Kotlin + real round trip PASS | host_interop.sh |
| Flake | `SystemNotificationE2E` — 歷史 flake，**未在 run 41011478801 出現**；不宣稱永久消失 | FLAKE-ANALYSIS.md |
| Blockers | 1) 無 release keystore（PRODUCTION_SIGNING_BLOCKED）2) **尚無真正獨立審查**（開發 Agent 的 PR 留言不算）3) 無實機/OEM 驗證 | — |
| Public upgrade | **VERIFIED（emulator API 33）** — 公開 v0.4.4-alpha（`6c6def17…`, cert `c876051e…`）→ v0.4.6（`d7b348a7…`, cert `c876051e…`）`adb install -r` 成功，資料保留、簽章不變 | SIGNING-IDENTITY-AUDIT.md |

## 漂移調和（歷史紀錄 vs 真相）
| 舊報告 | 舊值 | 真相 |
|---|---|---|
| FINAL-V046-HANDOFF | 238 run / 2 failures；APK `450a951a…` | 該值對應**當時**建置。歷史數字保留於 git/快照，不改寫 |
| 本文件舊版 / ALPHA-CLOSURE 舊版 | APK `a817434e…`、instrumentation 239 | **`a817434e…` 無法由本 source tree 重現**，已由實測 `d7b348a7…` 取代；instrumentation 現為 290/290。見 SIGNING-IDENTITY-AUDIT.md |
| AGENT_STATE 舊段 | 239/1 flaky | 與最新全量一致 |
| Dirty 數量 | 73 / 74 / 82 不一 | 以 `git status --porcelain=v1 -uall` 當下為準（隨新文件增加而變動） |

## 結論
公開發布一律以本文件 + 實檔 SHA 為準。
