# Claude Review — Finding Resolution Matrix (v0.4.6)

Response to the Claude independent review. **14 original findings are preserved
in full** — none dropped, no severity silently changed — followed by the delta
findings raised by the corrective re-review.

| | |
|---|---|
| Reviewed HEAD (code) | `d376f407976f511837f963227115e5610f42083f` |
| Historical code baseline | `79f2942` (pre-F-05; cannot produce the release APK) |
| Original review HEAD | `cf220061` |
| CI | run [41231048769](https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41231048769) — 291/291 |

**Original finding census (unchanged):** P0 = 0, **P1 = 1**, **P2 = 6**, **P3 = 7**
→ 14 findings. All 14 appear below with their original severity.

Status vocabulary: `FIXED` / `DEFERRED` / `ACCEPTED_RISK` / `NOT_APPLICABLE` / `OPEN`.

---

## Original findings F-01 … F-14

### F-01 — P1 — Release staging APK is not the verified candidate, and its checksum file is wrong
- **Status:** FIXED (verified)
- **Fixed commit:** `d376f40`
- **Evidence:** old staging APK + wrong sums backed up to
  `workspace/releases/_backup-pre-ir12-fix/` (backup APK confirmed `a817434e…`).
  Staged APK replaced with the F-05-fixed build; `SHA256SUMS.txt` regenerated;
  `shasum -a 256 -c` → OK; `scripts/release/verify-artifact.sh` → ALL CHECKS PASSED.
  Staged APK = `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7`,
  cert `c876051e…` (identical to public v0.4.4-alpha).
- **Release blocking:** NO (closed)

### F-02 — P2 — Image scaling/JPEG encoding and serialized media reads run on the notification callback (main) thread
- **Status:** DEFERRED (disclosed)
- **Deferred version:** v0.4.7
- **Evidence:** unchanged in code. Disclosed in `docs/releases/v0.4.6-RELEASE-NOTES.md`
  and README item 7 ("image capture off by default; decode/encode still run on the
  notification callback thread"). Backlog entry added.
- **Reason for deferral:** moving capture-time image work off the callback is an
  architecture change, out of scope for a documentation/closure round and for a
  frozen v0.4.6 feature set.
- **Release blocking:** NO (disclosed)

### F-03 — P2 — Duplicate rows after a window shift for timestamp-less messages
- **Status:** DEFERRED (disclosed)
- **Deferred version:** v0.4.7
- **Original severity preserved: P2.** (An earlier revision of this matrix
  downgraded it to P3 without rationale; that was wrong and is corrected here.)
- **Evidence:** unchanged in code. Disclosed in release notes and README item 8.
  Backlog entry added.
- **Reason for deferral:** fixing it requires changing the dedup discriminator,
  which would alter the identity of already-stored rows.
- **Release blocking:** NO (disclosed)

### F-04 — P2 — Pagination can stop early or stay stuck when a page contains an undecryptable row / is empty
- **Status:** DEFERRED (disclosed)
- **Deferred version:** v0.4.7
- **Evidence:** unchanged in code. Disclosed in release notes and README item 9.
  Backlog entry added.
- **Release blocking:** NO (disclosed)

### F-05 — P2 — Conversation titles and legacy keys are written to Logcat
- **Status:** FIXED (verified)
- **Fixed commit:** `d376f40`
- **Evidence:** `InboxDatabase.kt` no longer logs the canonical id, the SQL text or
  the query arguments; only `predicates/bindArgs/bounded/cursor` and row counts.
  New `NoSensitiveLogInstrumentedTest` reads the device log buffer while opening a
  conversation, loading an older page and running search. Verified non-vacuous:
  re-introducing the old log makes it fail; restoring the fix makes it pass.
- **Caveat (D-08):** the "fails if re-introduced" property was demonstrated once
  interactively; it is not a recorded mutation run in CI. See D-08.
- **Release blocking:** NO (closed)

### F-06 — P2 — Release disclosure gaps (debug signature ⇒ no upgrade path, debuggable build, one-way export)
- **Status:** FIXED (disclosure); wording corrected this round
- **Fixed commit:** `d376f40` (release notes created), wording corrections in this
  documentation commit
- **Evidence:** `docs/releases/v0.4.6-RELEASE-NOTES.md` created; `ALPHA-CLOSURE` §11
  rewritten. Both now state: Experimental Debug Alpha, Debug certificate,
  `android:debuggable=true`, no release keystore, no in-place upgrade to a
  differently-signed build (uninstall loses all data), DHC export one-way with no
  in-app restore, emulator-only validation.
- **Release blocking:** NO (closed)

### F-07 — P2 — README "known issues" contradict shipped v0.4.6 behaviour
- **Status:** FIXED
- **Fixed commit:** `d376f40`
- **Evidence:** `README.zh-TW.md` known-issues rewritten with a "current as of"
  line; corrected the export claim (export exists), the pagination claim
  (pagination exists) and the key-loss behaviour.
- **Release blocking:** NO (closed)

### F-08 — P3 — Stale test numbers in user/handoff docs
- **Status:** FIXED this round (was PARTIAL)
- **Fixed commit:** this documentation commit
- **Evidence:** `README.md` 126/84 → 291 instrumentation / 157 unit;
  `README.zh-TW.md`, handoff, closure, release notes, reconciliation, baseline-truth
  and `TASK_STATE.json` all updated to 291/291 and 157/157 against run 41231048769.
- **Release blocking:** NO

### F-09 — P3 — Merge/unmerge is unreachable but has latent atomicity defects
- **Status:** ACCEPTED_RISK (unchanged, unreachable from UI)
- **Evidence:** merge/unmerge is not wired to any UI path in v0.4.6. Code unchanged.
  Backlog entry added.
- **Reason:** latent only; fixing it now would be unreviewed churn in frozen code.
- **Release blocking:** NO

### F-10 — P3 — CI masks a release-build failure
- **Status:** OPEN (unchanged)
- **Evidence:** `.github/workflows/android.yml:110` still runs
  `:app:assembleProdRelease || echo "..."`. Not modified this round (documentation-only).
- **Release blocking:** NO — noted as a CI weakness; the Alpha asset is `prodDebug`,
  not the release build.

### F-11 — P3 — UI-test effectiveness (reflective `unlocked=false` hooks)
- **Status:** OPEN (unchanged)
- **Evidence:** the instrumentation hooks that force `unlocked` via reflection are
  unchanged. A lock-state test therefore exercises the code path but not the real
  credential gate. No real lock-screen failure-injection test exists.
- **Release blocking:** NO (test-quality limitation, disclosed)

### F-12 — P3 — `existingIdForFingerprint` never matches; `save()` comment is wrong
- **Status:** OPEN (unchanged)
- **Evidence:** `InboxDatabase.kt:892,894` still call `existingIdForFingerprint`,
  which binds `String(fingerprint)` against a BLOB column and so never matches;
  the fallback therefore returns `DUPLICATE(-1)` rather than the real row id.
  Functionally the row is still not inserted twice (the UNIQUE constraint holds),
  but the returned id is not the existing one.
- **Release blocking:** NO (no duplicate insertion; id-reporting defect only)

### F-13 — P3 — Settings screen shows the wrong version
- **Status:** OPEN (unchanged)
- **Evidence:** `MainActivity.kt:1605` still renders
  `Private Inbox Classic v0.3.1（內部測試）`. This is **product code**, which this
  documentation-only round must not modify.
- **Release blocking:** NO (cosmetic/incorrect label; tracked for v0.4.7)

### F-14 — P3 — PR #4 body carries stale CI and upgrade narrative
- **Status:** FIXED this round (re-verified after the PR body update)
- **Fixed commit:** this documentation commit (PR description)
- **Evidence:** PR body now states current HEAD, current run 41231048769, 291/291,
  the correct APK/cert, correct provenance (`d376f40`), and the CI-artifact warning.
  Re-verified after editing.
- **Release blocking:** NO

---

## Original census (preserved)

| Severity | Count | Fixed | Deferred / Accepted risk | Open |
|---|---|---|---|---|
| P0 | 0 | — | — | 0 |
| **P1** | **1** | 1 | 0 | **0** |
| **P2** | **6** | 3 (F-05, F-06, F-07) | 3 (F-02, F-03, F-04) | 0 |
| **P3** | **7** | 2 (F-08, F-14) | 1 (F-09) | 4 (F-10, F-11, F-12, F-13) |
| **Total** | **14** | 6 | 4 | 4 |

**Open P0 = 0. Open P1 = 0.**

---

## Delta findings D-01 … D-08

Raised by the corrective re-review against the previous documentation. These are
**not** part of the original 14 and are counted separately.

| ID | Sev | Finding | Status | Evidence |
|---|---|---|---|---|
| **D-01** | P2 | Resolution matrix had reduced 14 findings to 9 and downgraded F-03 P2→P3 without rationale | **FIXED** | This document: all 14 present, original severities preserved, census stated |
| **D-02** | P2 | Release notes / closure §5 / PR body attributed the APK to `79f2942` | **FIXED** | All now state the release binary source is `d376f40`; `79f2942` labelled historical baseline only |
| **D-03** | P3 | Stale test counts (290/290, 126/84) across docs | **FIXED** | 291/291 and 157/157 against run 41231048769 everywhere; historical counts labelled with their own HEAD |
| **D-04** | P3 | IR-12 §6 told publishers to reject anything ≠ `d7b348a7…` (i.e. to reject the real asset); `ui-newapk-*` unreferenced | **FIXED** | IR-12 now requires `b68fd821…` + cert `c876051e…`; `ui-newapk-*` referenced as the current evidence |
| **D-05** | P3 | `IR12-REVIEW-PACKAGE`, `TASK_STATE`, `RELEASE-BASELINE-TRUTH`, `FINAL-EVIDENCE-RECONCILIATION` still carried `d7b348a7` / 290 as current | **FIXED** | Updated to `b68fd821…` / 291 against run 41231048769 |
| **D-06** | P3 | `verify-artifact.sh` cannot reject a wrong certificate (no expected-cert parameter) | **DEFERRED** | Script **not** modified this round. Release checklist now mandates an independent certificate comparison. Backlog entry added. **Not marked FIXED.** |
| **D-07** | P3 | CI uploads JVM HTML only; connected-test XML never published, so 291/291 cannot be audited per test from artifacts | **DEFERRED** | Workflow not modified. This document states the evidence class honestly (see below). Backlog entry added. **Not marked FIXED.** |
| **D-08** | P3 | Handoff asserted "test fails if re-introduced" with no recorded mutation run; F-05 test has run once in CI; `git diff --check` not clean | **PARTIALLY FIXED** | `git diff --check` now clean (verified). The mutation claim is now labelled as a single interactive demonstration, not a recorded CI run. F-05 test still has one CI execution — no flake evidence claimed. |

---

## Evidence class for the 291/291 claim (D-07 honesty statement)

The current evidence is **not** a per-test XML artifact. It is:

- CI emulator started successfully.
- AGP console reported `Starting` / `Finished 291 tests` with **0 skipped, 0 failed**.
- JVM 157/157 has readable test reports (uploaded as HTML).
- The Android connected-test XML under
  `app/build/outputs/androidTest-results/connected/**` is **not** uploaded as a
  GitHub artifact, so the instrumentation result cannot be re-derived per test
  from artifacts alone.

This is weaker than a published per-test XML and is recorded as such (D-07). It is
not claimed to be more than it is.

---

## Not in scope this round

Documentation only. No product code, Android tests, Gradle, shell scripts,
manifest, APK, keystore, or signing properties were modified. No merge, no
release, no IR-13.
