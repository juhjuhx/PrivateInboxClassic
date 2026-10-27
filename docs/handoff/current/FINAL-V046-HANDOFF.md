# FINAL — Private Inbox Classic v0.4.6 Engineering Handoff

## Repository identity
- Remote: https://github.com/juhjuhx/PrivateInboxClassic
- Branch: `v045-reliability` (tracks `origin/fix/x200pro-notification-burst`, ahead 7)
- HEAD: `bb36c83f524c0aa87ba04680f0f4c0bfea79318f` — **0 commits created**; all work is
  an uncommitted working tree (51→56 entries)
- Public default branch: `integration/dsh-v040` — DO NOT push this branch to it
  (history pre-dates anonymization requirements)

## What v0.4.6 adds (all implemented + tested)
1. Canonical Conversation Identity (R1): `cv_<uuid>` conversations + aliases +
   message links; legacy key/fingerprint/DHC-V1 untouched.
2. SQLite schema v6: 6 new tables + provenance column; additive migrations with
   transactional rollback proof; one-shot `onOpen` repair for pre-G1 v6 DBs.
3. Merge/Unmerge with batch-scoped undo journals (alias ownership + links).
4. 5-value alias provenance persisted in a new column; NULL → AMBIGUOUS.
5. Production wiring: listener → resolve → link → reconcile; UI canonical
   grouping + search/filter/date assertions through the real Activity.
6. Version metadata: versionCode 11 / versionName 0.4.6 (upgrade from installed
   10 verified by a real `adb install -r`).
7. Release CLI: verify-artifact.sh / install-artifact.sh (signature-mismatch
   refuses with a backup/restore message; tested with a re-signed APK).

## Test results (fresh, after the last production change)
| Suite | Result |
|---|---|
| Devtest instrumentation (full) | 238 run / 2 failures — both the documented FLAKY class |
| BurstCapture alone | 10/10 |
| SystemNotificationE2E alone | 1/1 |
| JVM unit | 157/157 |
| Python DHC reference tests | 27/27 |
| Kotlin DHC + Python fixture | 14/14 |
| Real Kotlin→Python round trip | PASS (wrong passphrase rejected) |
| Lint / builds / 3 audits / git diff --check | PASS |
| Benchmarks (14) | PASS + canonical-layer measurements |

Test-level taxonomy: A (direct capture) PASS · B (system delivery) PASS ·
C (Activity UI) PASS · D (real third-party apps / OEM) **REAL_DEVICE_NOT_TESTED**.

## Artifacts (SHA-256)
| Artifact | SHA-256 |
|---|---|
| `app-prod-debug.apk` (versionCode 11, 0.4.6) | `450a951acc533752f1aac8355dab69e04f52d7ee41e3d57a4b0e0e6264de0176` |
| `app-devtest-debug.apk` | `894852bcdf3cd25ad015ca726f29a84c1ef0bf3195ffb61fdd5aeba2dcbbe93b` |
| `dhc-export-test.picbox` (device export) | `aec5c7102798d8d0645564a99926dc5a627b1780582ff610326212e8f328b67e` |

Signing: debug key `46488e99373f3c1af0c630642f2d9ec459b8db671391f3ad817efb3aae99da94`
(measured; `docs/SIGNING.md` corrected this round).
**PRODUCTION_SIGNING_BLOCKED** — no release keystore exists; debug APKs are not
distributable releases.

## Known limitations (documented, not hidden)
1. Rename changes the fingerprint input → a re-delivered message is a new ROW
   (the canonical conversation stays one). Row-level cross-name dedup is a
   documented limitation; fixing it requires rewriting stored keys — forbidden.
2. Messages captured during a merge stay with the merge target after unmerge
   (asserted contract; must be disclosed in UI copy).
3. Process death between capture and commit loses in-flight rows (Persistent
   Intake Journal = DESIGN_ONLY).
4. **FLAKY_UNRESOLVED**: BurstCapture.testC/testE and SystemNotificationE2E fail
   intermittently ONLY under full-suite ordering (see FLAKE-ANALYSIS.md).
5. Emulator-only evidence; no OEM/real-device validation.
6. Version bump was made as part of this round; the public repo still shows
   v0.4.4-alpha as its only tag.

## Evidence paths
- `docs/verification/v0.4.6/FINAL-TEST-MATRIX.md` (+ DHC-INTEROP, PERFORMANCE,
  SYSTEM-E2E, FLAKE-ANALYSIS, R1-ACCEPTANCE-CORRECTIONS)
- `docs/handoff/current/TASK_STATE.json` (per-task ledger)
- Logs: `/tmp/t12-full.log`, `/tmp/t05-full.log`, `/tmp/perf.log`, `/tmp/g3-full.log`
- Snapshots: `~/Desktop/pi-v046-r0-snapshot/`, `pi-v046-r1postfix-snapshot/`,
  `pi-v046-t00-snapshot/`

## Safe public integration plan (NOT executed)
1. On the anonymized public baseline (`integration/dsh-v040`), create
   `v046-reliability` from it — never merge `v045-reliability` history directly.
2. Apply this working tree as a patch series: (a) v0.4.5 reliability fixes,
   (b) v0.4.6 identity layer + tests, (c) docs. Re-run the full suite per commit.
3. Confirm no private paths/keys in the patches (`audit-secrets.sh` + manual grep).
4. Only then open a Draft PR; do not merge without independent review.

## Next atomic actions
1. T13: independent review of the full diff (SELF_REVIEW_ONLY was all that was
   available this round).
2. T12 flake: run the suite under AndroidX Test Orchestrator with
   `clearPackageData`, or isolate the burst suite into its own process.
3. T07: same-conditions baseline for future comparisons is now recorded; re-measure
   on any hardware change.
