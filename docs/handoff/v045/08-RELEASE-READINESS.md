# v0.4.5 Release Readiness

## Current version
- `versionCode 10`, `versionName 0.4.4` (prod) / `0.4.4-devtest` (devtest).
- The reliability work lands on the **v045-reliability** branch without bumping
  the version. A release bump is a separate, deliberate step and is NOT done here.

## Build status (verified in this review)

| Target | Result |
|---|---|
| `:app:assembleProdDebug --rerun-tasks` | BUILD SUCCESSFUL (exit 0) |
| `:app:assembleDevtestDebug --rerun-tasks` | BUILD SUCCESSFUL (exit 0) |
| `:app:assembleDevtestDebugAndroidTest` | BUILD SUCCESSFUL (exit 0) |
| `:app:testProdDebugUnitTest` | BUILD SUCCESSFUL (exit 0) |
| `:app:lintProdDebug` | BUILD SUCCESSFUL (exit 0) |

## Test status

| Suite | Result |
|---|---|
| Full devtest instrumentation | **139 / 139 pass**, 25 classes, 0 skipped |
| `BatchRetryFaultInjectionInstrumentedTest` | 7 / 7 pass (8 consecutive repeats, 0 flakes) |
| `MigrationInstrumentedTest` | 6 / 6 pass |
| `NotificationPipelineInstrumentedTest` | 10 / 10 pass |
| `WalBenchmarkInstrumentedTest` | 3 / 3 pass |
| Order-varied regressions (fault-first / normal-first / interleaved) | 21 / 21, 21 / 21, 17 / 17 after the isolation fix |

## APK hashes (post-fix, current tree)

| Artifact | SHA-256 |
|---|---|
| `app-prod-debug.apk` | `587b8b0b26fa3ae3b4bfc3addef86c6ccaf5aa730c2c70f0129d4db235599528` |
| `app-devtest-debug.apk` | `4fa123239302e3bf3ac322e2b6cfa2c7ccf16114ab0a728072c901026d96fa5a` |
| `app-devtest-debug-androidTest.apk` | `701e44eece85839d21ade896c7f2c5448a28258a080b0156042997ec05249d28` |

## Signing
- Both APKs signed with the Android **debug** key:
  `CN=Android Debug, O=Android, C=US`
  SHA-256 `46488e99373f3c1af0c630642f2d9ec459b8db671391f3ad817efb3aae99da94`
  SHA-1 `8076197535c2977693b118332e9fce9e2bc0fd13`
- **SIGNING_STATUS = DEBUG_KEY_ONLY.**
  This build cannot replace a release build installed from a different signing
  key, and no release keystore exists in this repository. Any user-visible
  release requires a separately provisioned release key — out of scope and
  explicitly not performed here (no key was created, rotated or replaced).

## Security review

| Check | Result |
|---|---|
| `android.permission.INTERNET` | absent (prod and devtest) |
| Permissions present | `POST_NOTIFICATIONS` + variant-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` only |
| Tracking / analytics SDK | none |
| Root / Accessibility / sandbox bypass | none |
| `FLAG_SECURE` | unconditional |
| Hard-coded keys | none (`audit-secrets.sh` PASS) |
| Debug provider scope | `app/src/debug/` only — not in release |
| Destructive test helper | refuses production package id (code-enforced) |
| `scripts/audit.sh` | PASS |
| `scripts/audit-secrets.sh` | PASS (no secrets in tree or history) |
| `scripts/audit-test-isolation.py` | PASS (29 files, no unguarded destructive op) |

## Known limitations (must be stated in release notes)
1. **Process death loses in-flight messages.** Batches staged between capture
   and commit are in memory only; the platform will not re-deliver. Persistent
   Intake Journal is DESIGN_ONLY.
2. **No recovery of what the phone never received** (no network path by design).
3. **OEM battery managers** may kill the listener; tray-based catch-up only
   covers notifications still displayed at rebind.
4. **`versionCode` unchanged** — this is a candidate, not a new release.
5. **Performance**: the retry backoff holds the single writer for up to ~2 s per
   permanently failing batch. Bounded, but a burst arriving during a backoff
   waits in the queue (capacity 512). Measured by the existing burst/WAL
   benchmarks, which pass; no latency regression was observed in this review
   beyond the pre-existing baseline.

## Git history risk
- The public GitHub history was previously de-identified. **Any new commit must
  be built on the current anonymised baseline.** Do not push old history back to
  the remote, do not force-push, do not rewrite.
- This review created **no commits**. The candidate change set is the uncommitted
  working tree, snapshotted outside the repo at
  `~/Desktop/pi-v045-review-snapshot/` (tracked.diff + untracked.tgz + hashes).

## CI status
NOT OBSERVED. No CI run was executed or inspected during this review; the
evidence above is local emulator + local Gradle only.

## Next release blockers
1. Independent sign-off of this review (`INDEPENDENT-REVIEW.md`).
2. A focused local commit of the reliability change set on the anonymised base.
3. A release signing decision (a new release key must never replace an installed
   key silently — it changes the update path for existing users).
4. Decide whether to bump `versionCode`/`versionName`.

**This document does NOT declare a release shipped.**
