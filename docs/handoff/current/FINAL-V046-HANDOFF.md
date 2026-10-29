# Private Inbox Classic v0.4.6 — Final Handoff

**Status:** IR_REMEDIATED — PENDING INDEPENDENT REVIEW SIGN-OFF  
**Branch:** `v045-reliability` (local, uncommitted) @ `bb36c83f`  
**Public integration:** `release/v0.4.6-alpha` @ `6f8191d` (GitHub)  
**Draft PR:** https://github.com/juhjuhx/PrivateInboxClassic/pull/4 (PR #3 superseded — marker only)
**IR head:** `fd2338c` — independent-review remediation (IR-01…IR-10)  
**Working tree:** 80 entries dirty (preserved, uncommitted on `v045-reliability`)

---

## Verified Test Results (Fresh, after IR remediation)

| Suite | Result |
|-------|--------|
| Full instrumentation | **274 / 274 PASS** (0 failures, 0 skipped) |
| JVM unit tests | **157 / 157 PASS** |
| Security audits | PASS (manifest/privacy, secrets+history, test isolation 47 files) |
| Release audit / version regression | PASS / PASS |
| DHC connector | 23 / 23 PASS |
| Lint | PASS |
| Build (prod + devtest) | PASS |

## Artifacts

| Artifact | SHA-256 |
|----------|---------|
| PrivateInboxClassic-v0.4.6-alpha-debug.apk | `a817434edefcb8f5becec48b4ab46ba91badc80bed2b294c0355247c1d6a653c` |
| Signing cert | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` (key A) |

## Upgrade Compatibility
**VERIFIED** — Public v0.4.4-alpha (key A) → v0.4.6 prod (key A) via `adb install -r` succeeds.

## Flake status — CORRECTED
The `SystemNotificationE2E` "FLAKY_UNRESOLVED" classification was **wrong**.
`DebugSettingsProvider.call()` had no `postSelfTest` branch, so the test always
threw "the app failed to post"; CI masked it with `|| true`. IR-06 implements the
provider methods and removes `|| true`. The suite now passes for real (265/265)
and CI is authoritative. See `IR-REMEDIATION-EVIDENCE.md` and the correction
header in `FLAKE-ANALYSIS.md`.

## Release Gate Status
| Gate | Status |
|------|--------|
| Clean integration branch | PASS |
| Full diff security review | PASS (SHIP IT) |
| CI / Build / Migration / DHC | PASS |
| APK signing verified | PASS (debug key A) |
| Public upgrade compatibility | VERIFIED |
| Unresolved flake disclosed | YES (FLAKY_UNRESOLVED) |
| IR remediation (IR-01…IR-06) | PASS — RED/GREEN evidence in IR-REMEDIATION-EVIDENCE.md |
| Independent review (2nd reviewer) | PENDING — blocks publication |
| Release keystore | NOT EXISTS (BLOCKS production release) |

**Cannot publish Alpha Release until:** an independent reviewer signs off on the
`3d56a7f5..316c44c` range. The previously-cited flake blocker no longer applies
(it was a missing implementation, now fixed).
