# Claude — Final Corrective Re-review (Delta)

**Project:** PrivateInboxClassic — v0.4.6-alpha release candidate
**Repository:** `juhjuhx/PrivateInboxClassic` · PR #4 (`https://github.com/juhjuhx/PrivateInboxClassic/pull/4`)
**Original review HEAD:** `cf220061bbfef022a3aa6c935f3c1e5bf57d1691`
**Corrective HEAD:** `d376f407976f511837f963227115e5610f42083f`
**CI under review:** https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41231048769
**Review date:** 2026-11-01 (Asia/Taipei)
**Reviewer identity:** this session is authenticated as `juhjuhx`, the PR author. Nothing was posted to GitHub. No code, merge, release, upload or keystore operation was performed.

**Scope:** delta only — `cf220061 → d376f40`, its blast radius, and the final release evidence. The 106-file audit from the previous round is not repeated; prior conclusions carry over except where this diff touches them.

---

## 0. Method and evidence classes

| Class | Meaning |
|---|---|
| `MEASURED` | produced by a command run in this review, on this machine |
| `SOURCE_READ` | read the file at the stated commit |
| `CI_INSPECTED` | read GitHub Actions run metadata / logs / artifacts via `gh` |
| `DOC_READ` | read a project document — a claim, not proof |
| `INFERRED` | reasoned from the above; labelled where used |

Working copies used, all read-only for the repository itself:
- review clone `[臨時審查目錄]/repo`, checked out at `d376f40` (fetched this round).
- Codex build worktree `[臨時工作樹]` (gitdir-linked to `codex-integration/.git`), at `d376f40`, clean before the review.
- release staging `[用戶主目錄]/workspace/releases/`.

---

## 1. F-01 — Release artifact integrity → **CLOSED**

### 1.1 What the diff did

F-01 is closed out-of-tree (release staging) plus in-tree documentation. The corrective commit does **not** add a staging directory to the repository; the staged bytes live in the workspace. The in-tree part is the hash correction in `FINAL-V046-HANDOFF.md`, `v0.4.6-ALPHA-CLOSURE.md` and the new release notes.

### 1.2 Independent re-measurement of the staging set

| Check | Command | Result | Class |
|---|---|---|---|
| Staged APK SHA-256 | `shasum -a 256 PrivateInboxClassic-v0.4.6-alpha-debug.apk` | `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7` — **matches the expected value exactly** | MEASURED |
| Checksum manifest | `shasum -a 256 -c SHA256SUMS.txt` | `PrivateInboxClassic-v0.4.6-alpha-debug.apk: OK`, exit 0 | MEASURED |
| Manifest contents | `cat SHA256SUMS.txt` | one line, the same hash | MEASURED |
| APK size / mtime | `ls -l` | 6,074,285 B, 2026-11-01 01:50 | MEASURED |
| Signature | `apksigner verify --verbose --print-certs` | `Verifies`; v2 scheme true; **cert `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`**; DN `CN=Android Debug, O=Android, C=US`; RSA 2048 | MEASURED |
| Package identity | `aapt2 dump badging` | `tw.personal.privateinbox`, versionCode **11**, versionName **0.4.6**, targetSdk 36 | MEASURED |
| Permissions | `aapt2 dump permissions` | `POST_NOTIFICATIONS`, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; **no `INTERNET`** | MEASURED |
| Manifest flags | `aapt2 dump xmltree … AndroidManifest.xml` | `debuggable=true`, `allowBackup=false`, `usesCleartextTraffic=false` | MEASURED |
| Contents are the F-05-fixed build | `unzip classes*.dex` + `strings` | `fail-closed: no alias and no link` **present**, `query: predicates=` **present**; `fail-closed: unknown canonicalId=` **absent**, `sql=` **absent**, `args=` **absent** | MEASURED |
| Reproducibility from the reviewed HEAD | `./gradlew :app:assembleProdDebug --rerun-tasks` at `d376f40` with `.android-home/debug.keystore` | rebuilt APK SHA-256 = **`b68fd821…`, identical** | MEASURED |

**The staged APK is bit-for-bit reproducible from `d376f40` with the project keystore.** That is the strongest available statement of artifact provenance: the file being released is the file this review read the source for.

### 1.3 Release-directory hygiene

| Check | Result | Class |
|---|---|---|
| Old, wrong binary isolated | `releases/_backup-pre-ir12-fix/` holds `a817434e…` plus its own (wrong) `SHA256SUMS.txt` recording `68ed5c1d…` — both superseded, both outside the release directory | MEASURED |
| Release directory contents | `v0.4.6/` holds exactly: the new APK, its `SHA256SUMS.txt`, the public `v0.4.4` APK (`6c6def17…`), and `public-v0.4.4/` (same `6c6def17…`) | MEASURED |
| CI runner APK excluded | Downloaded artifact `13054470664` from run 41231048769: APK SHA `ff516433…`, cert `4def3c89…` — **different from the candidate on both**, and not present anywhere in the release directory | MEASURED |

### 1.4 Release script

| Check | Result | Class |
|---|---|---|
| `verify-artifact.sh <apk> b68fd821…` | exit **0**, `ALL CHECKS PASSED` (SHA match, signature OK, cert printed, identity `tw.personal.privateinbox` 11/0.4.6, INTERNET absent) | MEASURED |
| Rejects a wrong SHA | run with `d7b348a7…` → `FAIL: SHA-256 mismatch`, non-zero exit | MEASURED |
| Rejects a wrong certificate | **cannot** — the script has no expected-certificate argument; it only prints the cert (and compares it to an installed device APK when `adb` is present). A wrong-cert artifact passes the script | MEASURED |

**Verdict F-01: CLOSED / `RELEASE_ARTIFACT=VERIFIED`.** The one residual is the script's inability to assert a certificate (new finding D-06, P3), not an artifact defect.

---

## 2. F-05 — Privacy logging → **CLOSED**

### 2.1 The product change

The diff touches two product files. Both changes are minimal and confined to F-05.

`InboxDatabase.kt` (log strings only; SQL, cursor and crypto logic untouched):

| Line | Before | After |
|---|---|---|
| 504 | `fail-closed: unknown canonicalId=$canonicalId` | `fail-closed: no alias and no link` |
| 548 | `sql=${"SELECT id FROM messages WHERE $selection"} args=$args` | `query: predicates=${…0/1} bindArgs=${args.size} bounded=$bounded cursor=${bool}` |
| 586 | `rawRows=$__n output=${output.size}` | unchanged — counts only, permitted |

`MainActivity.kt`: one six-line test hook, `internal fun loadOlderForTest()` annotated `@VisibleForTesting`, delegating to the existing `loadOlderChatMessages()`. It mirrors the pre-existing hooks (`openThreadForTest`, `chatStateForTest`, `setSearchInputsForTest`).

### 2.2 The nine required checks

| # | Requirement | Result | Class |
|---|---|---|---|
| 1 | No canonical conversation ID in Logcat | Pass — the two identifier-bearing sites were rewritten; the only `READMODEL` lines left are the fail-closed stage code, a predicate/arity shape, and row counts | SOURCE_READ |
| 2 | No legacy conversation key or hex-encoded title | Pass — `args` is never interpolated; only `args.size` | SOURCE_READ |
| 3 | No SQL query arguments | Pass — SQL text removed; `bindArgs` is a count | SOURCE_READ |
| 4 | No plaintext message content | Pass — no log site in `app/src/main/` interpolates body/title/sender. All nine `Log.*` call sites in main sources were enumerated: 5 in `MainActivity` (stage codes, counts, exception), 3 in `InboxDatabase` (above), 1 in `DestructiveOperationGuard` (the guard's refusal text, package name only) | SOURCE_READ |
| 5 | Counts / stage codes contain no private identifiers | Pass — `conversationPage convos=N rows=N`, `rawRows=N output=N`, `UI update rows=N` | SOURCE_READ |
| 6 | The test exercises the real DB read and paging paths | Pass — the test inserts 150 rows through `InboxDatabase.save()`, links them via `ConversationStore.linkMessage`, launches `MainActivity`, unlocks, loads the inbox, opens the thread (first page at `CHAT_PAGE_SIZE=100`), calls `loadOlderForTest()` and requires `entries > 100`, then runs the search path (`filteredEntries()`). It asserts `entries > 0` and `> 100`, so a no-op would fail | SOURCE_READ |
| 7 | A Logcat read failure is not mistaken for success | Pass — `readLogcat()` swallows an exec exception and returns what it collected; if nothing was collected the assertions `assertFalse(...)` pass trivially, but the final `assertTrue(log.contains("READMODEL"))` fails. A blind test therefore **fails**, not passes. `clearLogcat()` failure is benign because the probe strings are unique | SOURCE_READ + INFERRED |
| 8 | A mutation test proves the test catches re-introduced leakage | **NOT EVIDENCED** — no mutation run is recorded anywhere in the tree or the PR. By inspection the assertions do cover all four identifier classes (title substring, canonical id substring, formatted legacy key, uppercase hex of that key, SQL text prefix, `args=[`), so a straight revert to the old lines would fail the test — but that is analysis, not an executed mutation test | SOURCE_READ; `MUTATION_TEST=NOT_EXECUTED` |
| 9 | `loadOlderForTest()` is not an unprotected external entry | Pass — Kotlin `internal`, `@VisibleForTesting`, no `exported` component, no IPC/broadcast/content-provider surface, no reflection indirection, same pattern as existing hooks. It does ship inside the debug APK, as do the other hooks | SOURCE_READ |

### 2.3 Residual observations (not blocking)

- `MainActivity.kt:425` logs `Log.e("LOADENTRIES", "loadEntries failed", e)` — an exception object. Android `SQLiteException` messages can embed the failing SQL text. The string contains no bound arguments and no conversation keys, so this does not re-open F-05, but it is the one remaining place where a future change could reintroduce metadata indirectly. Worth a one-line hardening pass in v0.4.7.
- `DestructiveOperationGuard` logs a package name — explicitly allowed by `AGENTS.md` hard rule 4 and by the test's own allow-list.

**Verdict F-05: CLOSED.** Code fix verified on source and on the shipped binary; regression test is present and counted in CI. The mutation-test claim in the handoff table ("regression test fails if re-introduced") is analytically true but unproven by execution.

---

## 3. F-06 / F-07 — Release disclosure and README → **CLOSED IN SUBSTANCE, corrections pending**

### 3.1 F-06 (disclosure)

New `docs/releases/v0.4.6-RELEASE-NOTES.md` (147 lines) states, in plain language and up front:
Experimental Debug Alpha; Debug certificate and no release keystore; `android:debuggable=true` with the `adb`/`run-as` consequence; no in-place upgrade to a future production-signed build (uninstall ⇒ all data lost); DHC export is one-way with **no in-app restore**; image capture off by default with main-thread decode; duplicate capture without timestamps; pagination limitation with undecryptable rows; emulator-only validation.

`docs/releases/v0.4.6-ALPHA-CLOSURE.md` §11 was rewritten to the same content. This satisfies the F-06 gate, which required publication not to proceed without it.

**Code cross-check:** export-only with no import path is confirmed — the settings surface offers `匯出` only (`MainActivity.kt:1365-1447`), and nothing in the DHC code reads an archive back into the app.

### 3.2 F-07 (README)

`README.zh-TW.md` known-issues was rewritten (14 items, grouped by release form / data preservation / performance / validation / other). The two contradicted claims were corrected: export now described as existing but one-way; pagination now described as existing with a limitation. Item 2 (key loss) now says detected, reported, skipped, never auto-deleted.

### 3.3 What is still wrong inside these same documents

See §8 findings D-02, D-03, D-04. Summary: the new release notes state `Source HEAD: 79f2942`, which is **false for the shipped binary** (see §5.3); the notes say `290/290` where the reviewed build is 291/291; the closure's §5 repeats the wrong provenance, §7 still describes the upgrade as `d7b348a7`, and §8 still cites the old run. The disclosure *substance* is right; the surrounding facts are stale.

**Verdict F-06 / F-07: CLOSED in substance.** No product change is required; documentation corrections are required before publication (D-02…D-04).

---

## 4. Review 1 — Scope of the corrective diff

```
git diff --check cf220061 d376f40   → 1 warning: trailing whitespace in
                                      docs/releases/v0.4.6-ALPHA-CLOSURE.md:50
git diff --stat cf220061 d376f40    → 10 files, +620/−23
git log --oneline cf220061..d376f40 → 1 commit: d376f40
```

| File | Allowed by the brief? |
|---|---|
| `README.zh-TW.md` | yes — F-07 |
| `app/src/main/java/.../InboxDatabase.kt` | yes — F-05 (log strings only) |
| `app/src/main/java/.../MainActivity.kt` | yes — "necessary test hook" (F-05) |
| `app/src/androidTest/.../NoSensitiveLogInstrumentedTest.kt` (new) | yes — F-05 regression test |
| `docs/releases/v0.4.6-RELEASE-NOTES.md` (new) | yes — F-06 |
| `docs/releases/v0.4.6-ALPHA-CLOSURE.md` | yes — F-06 |
| `docs/handoff/current/FINAL-V046-HANDOFF.md` | yes — F-01 evidence |
| `docs/verification/v0.4.6/CLAUDE-FINDING-RESOLUTION-MATRIX.md` (new) | yes — review response |
| `docs/verification/v0.4.6/ir12-evidence/ui-newapk-*.xml` (2 new) | yes — upgrade evidence |

No merge, no release, no tag, no keystore change, no CI masking change, no build-config change. **Scope: conforms.** The one blemish is the trailing whitespace at `v0.4.6-ALPHA-CLOSURE.md:50` (`git diff --check` is not clean).

---

## 5. Review 3 (restated) — New artifact identity

| Property | Measured value | Expected | Verdict |
|---|---|---|---|
| APK SHA-256 | `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7` | same | **MATCH** |
| Certificate SHA-256 | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | same | **MATCH** |
| versionCode / versionName | 11 / 0.4.6 | 11 / 0.4.6 | **MATCH** |
| Application ID | `tw.personal.privateinbox` | same | **MATCH** |
| INTERNET permission | absent | absent | **MATCH** |
| Same cert as public v0.4.4 (`6c6def17…`) | yes | yes | **MATCH** |
| Built from the F-05-fixed tree | yes (dex strings + bit-identical rebuild from `d376f40`) | yes | **MATCH** |
| Sums file verifies | exit 0 | — | **PASS** |
| Old binary confined to backup | yes (`_backup-pre-ir12-fix`) | — | **PASS** |
| CI runner APK excluded | yes (`ff516433…` / cert `4def3c89…`, not in release dir) | — | **PASS** |

### 5.3 Provenance contradiction (new finding)

`79f2942` contains the old leaky strings and none of the new ones:

```
git show 79f2942:.../InboxDatabase.kt | grep -c "no alias and no link"   → 0
git show 79f2942:.../InboxDatabase.kt | grep -c "sql="                   → 1
```

The shipped APK contains the new strings (`MEASURED`, §1.2). Therefore **`b68fd821` cannot have been built from `79f2942`**; it is built from `d376f40`, which is exactly what reproduced it bit-for-bit. Every document that attributes `b68fd821` to source HEAD `79f2942` is stating something provably false about the release asset.

---

## 6. Review 5 — Upgrade compatibility for the new APK

| Item | Status |
|---|---|
| Public v0.4.4 install → encrypted message captured → `adb install -r` of a candidate → same cert → ciphertext preserved → decrypted & displayed → survives restart | **`EVIDENCE_CHAIN_REVIEWED`** — the written chain (`IR12-APK-UPGRADE-EVIDENCE.md`) is for `d7b348a7…`, the **pre-F-05** build, not for `b68fd821…` |
| Fresh evidence for the new build | Two new UI dumps, `ui-newapk-chat-detail.xml` and `ui-newapk-after-restart.xml`, show `IR12 Probe Chat` → `IR12-ENCRYPTED-MARKER-20261101`, captured `11/01 01:51`, i.e. after the 01:50 build of the staged APK and distinct from the original 00:16 run. This is consistent with a re-run against the new binary, but **no narrative, commands or hashes were recorded for it** |
| Independently reproduced this round | **No** — no emulator upgrade session was executed |
| Behavioural equivalence argument | `MEASURED`: the only product delta between the `d7b348a7` tree (`cf220061`) and `d376f40` is two log strings and one unused-by-production test hook. SQLite access, cursor construction, AES-GCM path and UI render are unchanged, so decryption behaviour cannot differ. This supports the evidence chain but does not replace it |

**Required documentation fix:** append a dated section to `IR12-APK-UPGRADE-EVIDENCE.md` recording the `b68fd821` run (commands, install output, `dumpsys` signature, DB path, the two dumps, timestamps), and stop asserting `d7b348a7` as "the only build eligible to become the v0.4.6-alpha asset" (its §6 currently instructs a publisher to reject the real artifact).

---

## 7. Review 4 — CI evidence for 291/291

| Check | Evidence | Result |
|---|---|---|
| Run HEAD | `gh api …/runs/41231048769` | `head_sha=d376f407976f…`, branch `v046-integration-repair-final`, `conclusion=success` | **MATCH** |
| Job | `…/jobs` | one job "Build, test and audit", 28 steps, all `success` | PASS |
| Emulator really ran | run log | `Starting 291 tests on emulator-5554 - 16` … `Finished 291 tests on emulator-5554 - 16`, `BUILD SUCCESSFUL in 11m 2s` | PASS |
| Failures / skips during the run | periodic progress lines | every line reads `(0 skipped) (0 failed)` | PASS |
| Build / Lint / audits / DHC | job steps | `Static safety audit`, `Secret audit`, `Test isolation audit`, `DHC connector tests`, `DHC cross-language fixtures`, `DHC contract`, `Unit tests`, `Android Lint`, `Assemble debug APKs`, `Assemble test APK`, `Assemble release APK`, `Audit the built artifacts`, `Audit the built APK permissions`, `Verify the APK signature` — all success | PASS |
| JVM 157/157 | `test-reports` artifact, `tests/testProdDebugUnitTest/index.html` | counters `157 tests`, `0 failures`, `0 ignored` | PASS |
| No masking | `.github/workflows/android.yml` | instrumentation step is bare `:app:connectedDevtestDebugAndroidTest`; the only `|| true` are SDK licences and KVM group setup. **However** `assembleProdRelease || echo …` still masks a release-build failure (F-10, unchanged) | PARTIAL |
| The new F-05 test is inside the 291 | static count at both commits | `@Test` in `app/src/androidTest`: **290** at `cf220061` → **291** at `d376f40`; previous run 41204287676 (HEAD `c6d9b1a`, same 290-test tree) also reported 290. The delta is exactly the one added method | PASS (strong), with the caveat below |

**Caveat — raw XML not published.** The workflow uploads only `app/build/reports/tests/**` (JVM HTML) plus lint. Android's connected-test XML lives under `app/build/outputs/androidTest-results/connected/**`, which is not uploaded, and the `test-reports` artifact of this run contains only `testProdDebugUnitTest`. Instrumentation evidence therefore rests on AGP's own console summary, which it derives from that XML. This is materially weaker than the per-test XML the previous ledger claimed to have parsed; the claim in the earlier round should be corrected. Recommendation: upload the androidTest results directory (D-07, P3).

**Did the F-05 fix change SQLite / decryption / UI behaviour?** No. The diff to `InboxDatabase` is confined to three `Log.d` argument lists; no statement text, selection builder, cursor, binding or crypto call changed (`git diff` read line by line). `MainActivity` gains one delegating method. `MEASURED` + `SOURCE_READ`.

---

## 8. Review 6 — Documentation consistency, and new findings

### 8.1 Item A — stale provenance and test counts

| Document | Claim | Actual | Severity |
|---|---|---|---|
| `docs/releases/v0.4.6-RELEASE-NOTES.md` | "Source HEAD: `79f2942` (product code)" | the artifact builds from `d376f40`; `79f2942` cannot produce it (§5.3) | **D-02, P2** |
| …same | "290/290 instrumentation tests" | 291/291 on the reviewed HEAD | D-03, P3 |
| `docs/releases/v0.4.6-ALPHA-CLOSURE.md` §5 | "source HEAD `79f2942`" for the `b68fd821` build | ditto → false | **D-02, P2** |
| …§7 | upgrade verified for `d7b348a7…` | release asset is `b68fd821…`; the §7 text also still says encrypted-row survival is "inferred", contradicting IR-12 | D-04, P3 |
| …§8 | 290/290, run 41013529150 | 291/291, run 41231048769 | D-03, P3 |
| `docs/handoff/current/FINAL-V046-HANDOFF.md` | header "Code HEAD `79f2942` (product code + tests)", CI run 41013529150, 290/290 (§2, §7) | `d376f40` changes product code and adds a test; 291/291 | D-02/D-03 |
| `README.zh-TW.md:173` | "最後核對：`79f2942`" | the reviewed code is `d376f40` | D-03, P3 |
| `docs/verification/.../FINAL-EVIDENCE-RECONCILIATION.md`, `IR12-REVIEW-PACKAGE.md`, `TASK_STATE.json`, `RELEASE-BASELINE-TRUTH.md` | `d7b348a7`, 290/290 | stale | D-05, P3 |
| PR #4 body | "Current HEAD `d376f40` (code HEAD `79f2942` + docs-only commits)"; "291/291 on HEAD `c6d9b1a`"; later "290/290 code HEAD `79f2942`"; "rebuilt from `79f2942`" = `b68fd821` | three false statements: the corrective commit is **not** docs-only; the run is on `d376f40`; the rebuild was not from `79f2942` | **D-02 / D-06, P2/P3** |

### 8.2 Item B — the resolution matrix altered the record

The matrix's summary says:

```
P0 0 · P1 1 FIXED · P2 5 (3 fixed, 2 deferred) · P3 3
```

The original review recorded **P1 = 1, P2 = 6, P3 = 7 (14 findings)**. The matrix covers nine of them.

| Original | Original severity | In matrix? | Matrix severity | Actual current status |
|---|---|---|---|---|
| F-01 release staging | P1 | yes | P1 | **FIXED — verified** |
| F-02 image work on main thread | P2 | yes | P2 | OPEN — disclosed (deferred) |
| F-03 timestamp-less duplicate rows | **P2** | yes | **P3 (downgraded, no rationale)** | OPEN — disclosed in README item 8; not fixed |
| F-04 pagination early-stop | P2 | yes | P2 | OPEN — disclosed |
| F-05 Logcat identifiers | P2 | yes | P2 | **FIXED — verified** |
| F-06 disclosure gaps | P2 | yes | P2 | FIXED in substance (doc corrections pending) |
| F-07 README contradictions | P2 | yes | P2 | FIXED in substance |
| F-08 stale test numbers | P3 | yes | P3 | PARTIAL — README.zh-TW fixed; `README.md:122,286-288` still says 126/84 |
| F-09 merge atomicity (latent) | P3 | yes | P3 | OPEN — unchanged, unreachable from UI |
| F-10 CI masks release-build failure | P3 | **no** | — | OPEN — `android.yml` still has `assembleProdRelease || echo …` |
| F-11 UI-test effectiveness | P3 | **no** | — | OPEN — reflective `unlocked=false` hooks unchanged |
| F-12 `existingIdForFingerprint` String-vs-BLOB | P3 | **no** | — | OPEN — still binds `String(fingerprint)` |
| F-13 settings shows v0.3.1 | P3 | **no** | — | OPEN — `MainActivity.kt:1605` unchanged |
| F-14 PR body stale | P3 | **no** | — | OPEN — body now carries three false statements |

**FINDINGS_RECONCILED = NO.** Five findings (F-10…F-14) disappeared without a status, and F-03's severity was reduced without explanation. Also, the matrix says deferred items were "moved to `docs/roadmap/v0.4.7-BACKLOG.md`", but that file contains no F-IDs and none of F-02/F-03/F-04/F-08/F-09.

This is a documentation/governance defect, not evidence of hidden engineering work: every omitted item was checked against the tree and is genuinely unchanged, not silently fixed or hidden.

### 8.3 New findings

| ID | Severity | Finding |
|---|---|---|
| **D-01** | **P2** | `CLAUDE-FINDING-RESOLUTION-MATRIX.md` reduces the finding set from 14 to 9 and lowers F-03 from P2 to P3 without rationale or status for the omitted five. A resolution matrix must retain the original severity and give every finding one of FIXED / DEFERRED / ACCEPTED_RISK / NOT_APPLICABLE / OPEN |
| **D-02** | **P2** | Release notes, closure §5 and the PR body attribute the shipped APK to source HEAD `79f2942`; the artifact is provably built from `d376f40` (bit-identical rebuild; `79f2942` lacks the F-05 strings). A release document must name the correct provenance |
| **D-03** | P3 | Stale test counts: 290/290 remains in release notes, closure §8, handoff header/§2/§7, `README.md` (126/84), `FINAL-EVIDENCE-RECONCILIATION`, `TASK_STATE.json`; current is 291/291 (instrumentation) and 157/157 (JVM) |
| **D-04** | P3 | Closure §7 and IR-12 describe the verified upgrade as `d7b348a7`; the fresh `ui-newapk-*` dumps for `b68fd821` are unreferenced. IR-12 §6 tells a publisher to reject anything that is not `d7b348a7` — i.e. to reject the actual release asset |
| **D-05** | P3 | `IR12-REVIEW-PACKAGE.md`, `TASK_STATE.json`, `RELEASE-BASELINE-TRUTH.md`, `FINAL-EVIDENCE-RECONCILIATION.md` still carry `d7b348a7` / 290/290 as current |
| **D-06** | P3 | `scripts/release/verify-artifact.sh` cannot reject a wrong certificate — no expected-cert parameter. It prints the cert for manual comparison only. (Verified: a different-cert APK passes) |
| **D-07** | P3 | CI uploads JVM HTML only; the connected-test XML is never published, so 291/291 cannot be audited per test from artifacts alone. Recommend uploading `app/build/outputs/androidTest-results/connected/**` |
| **D-08** | P3 | The handoff table asserts "regression test fails if re-introduced" without a recorded mutation run; the F-05 test has run once in CI (no repeat/flake evidence); `git diff --check` is not clean (trailing space at closure line 50) |

None of these is P0 or P1. D-01 and D-02 are release-evidence accuracy problems for a document that will be attached to a public release, which is why they block publication as documentation, not as code.

---

## 9. Formal sign-off status

| Check | Result |
|---|---|
| Formal GitHub reviews on PR #4 | **0** (`gh api …/pulls/4/reviews` returns an empty list) |
| PR state | open, **draft**, `mergeable=true` |
| Session identity | `juhjuhx` — the PR author |
| Self-approval attempted | No. GitHub also rejects it (`Can not request changes on your own pull request`); no second identity was used or fabricated |
| Required for release | a formal GitHub review from a reviewer who did not implement IR-11 / this correction |

`FORMAL_GITHUB_APPROVAL = PENDING`.

---

## 10. Final release recommendation

1. **Engineering: no further code change is required for F-01 or F-05.** The artifact is now exactly what it claims to be and is reproducible from the reviewed HEAD.
2. **Documentation: a small corrective patch is required** — and it is genuinely small:
   - rewrite the matrix summary to the original 14 findings with original severities and a status per finding (F-10…F-14 = OPEN, unchanged; F-03 stays P2, OPEN/disclosed);
   - replace `79f2942` with `d376f40` wherever the *shipped binary's* provenance is stated (release notes, closure §5, PR body) and correct the "docs-only" framing of `d376f40`;
   - update 290/290 → 291/291 with run 41231048769 in release notes, closure §8, handoff; fix `README.md` 126/84; fix the README.zh-TW currency pin;
   - append a recorded `b68fd821` section to `IR12-APK-UPGRADE-EVIDENCE.md` and remove the "must equal `d7b348a7`" publication instruction from its §6; correct closure §7;
   - rewrite the PR body's HEAD/CI/provenance statements.
3. **After that patch:** an independent reviewer (not `juhjuhx`) performs the formal GitHub review on PR #4. No merge, no tag, no GitHub Release until that review exists.
4. **Standing limitations to keep in the published text:** debug signing with no release keystore; `debuggable=true`; no in-place migration to a future production-signed build; one-way export; emulator-only validation.

The release is **technically ready and administratively blocked**.

---

```text
PROJECT=PrivateInboxClassic
OLD_REVIEW_HEAD=cf220061
NEW_REVIEW_HEAD=d376f40
F01=CLOSED_VERIFIED
F05=CLOSED_VERIFIED
F06=CLOSED_IN_SUBSTANCE
F07=CLOSED_IN_SUBSTANCE
FINDINGS_RECONCILED=NO (9/14 in matrix; F-03 downgraded P2->P3; F-10..F-14 omitted)

APK_SHA256=b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7
CERT_SHA256=c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4
STAGING_CHECKSUM=VERIFIED (shasum -c exit 0)
RELEASE_SCRIPT=verify-artifact.sh EXIT 0; rejects wrong SHA; CANNOT reject wrong cert (D-06)
ENCRYPTED_UPGRADE=EVIDENCE_CHAIN_REVIEWED (d7b348a7 narrative; b68fd821 dumps present but undocumented; not independently reproduced)

CI_RUN=41231048769 (head d376f40, success)
INSTRUMENTATION=291/291, 0 skipped, 0 failed (AGP console; XML not published - D-07)
JVM=157/157, 0 skipped, 0 failed (artifact HTML)
SECURITY_AUDITS=PASS (static safety, secret/history, test isolation) + Lint + DHC PASS

OPEN_P0=0
OPEN_P1=0
OPEN_P2=5 (F-02, F-03, F-04 disclosed/open; D-01, D-02 documentation)
TECHNICAL_RE_REVIEW=CHANGES_REQUESTED (documentation only)
FORMAL_GITHUB_APPROVAL=PENDING (0 reviews; reviewer is the author)
RELEASE_READINESS=BLOCKED_ON_DOC_CORRECTIONS_AND_INDEPENDENT_SIGNOFF
NEXT_ATOMIC_ACTION=Codex applies the minimal documentation patch in §10.2 (matrix reconciliation, provenance 79f2942->d376f40, 290->291, IR12 b68fd821 section, PR body), then a non-author reviewer signs PR #4
```

---

## Appendix — commands actually run

```bash
# diff scope
git diff --check cf220061 d376f40; git diff --stat cf220061 d376f40
git log --oneline cf220061..origin/v046-integration-repair-final

# artifact
shasum -a 256 PrivateInboxClassic-v0.4.6-alpha-debug.apk
shasum -a 256 -c SHA256SUMS.txt
apksigner verify --verbose --print-certs <apk>
aapt2 dump badging <apk>; aapt2 dump permissions <apk>
aapt2 dump xmltree <apk> --file AndroidManifest.xml
unzip -o <apk> 'classes*.dex' && strings classes*.dex | grep -F ...
./gradlew --no-daemon --offline :app:assembleProdDebug --rerun-tasks \
  -Pdebug.storeFile=[用戶主目錄]/.android-home/debug.keystore \
  -Pdebug.storePassword=android -Pdebug.keyAlias=androiddebugkey -Pdebug.keyPassword=android
ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools \
  bash scripts/release/verify-artifact.sh <apk> b68fd821…

# CI
gh api repos/juhjuhx/PrivateInboxClassic/actions/runs/41231048769[/jobs|/artifacts]
gh run view 41231048769 --log    # "Starting 291 / Finished 291 ... (0 skipped) (0 failed)"
gh run download 41231048769 -n test-reports -D …   # JVM HTML: 157 / 0 / 0
gh run download 41231048769 -n PrivateInboxClassic-debug-apk -D …
gh api repos/juhjuhx/PrivateInboxClassic/pulls/4[/reviews]
```

**Nothing was written to the repository, pushed, merged, released, uploaded or signed.**
