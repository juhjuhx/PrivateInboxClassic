# Private Inbox Classic v0.4.6 — Final Handoff

**Status:** READY_FOR_PUBLICATION (pending independent review — R07-E)  
**Branch:** `v045-reliability` (local, uncommitted) @ `bb36c83f`  
**Public integration:** `release/v0.4.6-alpha` @ `6f8191d` (GitHub)  
**Draft PR:** https://github.com/juhjuhx/PrivateInboxClassic/pull/4 (PR #3 superseded — marker only)  
**Working tree:** 80 entries dirty (preserved, uncommitted on `v045-reliability`)

---

## Verified Test Results (Fresh, 2026-10-27)

| Suite | Result |
|-------|--------|
| Full instrumentation | **239 / 239 PASS** (0 failures, 1 documented flake) |
| Integration worktree | **238 / 239 PASS** (only SystemE2E link flake) |
| JVM unit tests | **157 / 157 PASS** |
| Lint | PASS |
| Build (prod + devtest) | PASS |
| Audits | PASS (secrets, test isolation) |
| DHC interop | PASS |

## Artifacts

| Artifact | SHA-256 |
|----------|---------|
| PrivateInboxClassic-v0.4.6-alpha-debug.apk | `a817434edefcb8f5becec48b4ab46ba91badc80bed2b294c0355247c1d6a653c` |
| Signing cert | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` (key A) |

## Upgrade Compatibility
**VERIFIED** — Public v0.4.4-alpha (key A) → v0.4.6 prod (key A) via `adb install -r` succeeds.

## Known Flake
`SystemNotificationE2EInstrumentedTest` link under full-suite ordering — FLAKY_UNRESOLVED, passes 3/3 isolated. Documented in `FLAKE-ANALYSIS.md`.

## Release Gate Status
| Gate | Status |
|------|--------|
| Clean integration branch | PASS |
| Full diff security review | PASS (SHIP IT) |
| CI / Build / Migration / DHC | PASS |
| APK signing verified | PASS (debug key A) |
| Public upgrade compatibility | VERIFIED |
| Unresolved flake disclosed | YES (FLAKY_UNRESOLVED) |
| Independent review (2nd reviewer) | SELF_REVIEW_ONLY (BLOCKS publication) |
| Release keystore | NOT EXISTS (BLOCKS production release) |

**Cannot publish Alpha Release until:** independent review of PR #4 + acceptance of documented flake.
