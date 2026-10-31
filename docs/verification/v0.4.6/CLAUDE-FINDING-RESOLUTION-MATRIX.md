# Claude Review — Finding Resolution Matrix (v0.4.6)

Response to `/tmp/pic-review-20261101/` (Claude independent review of
`cf220061`). One row per finding, with what was actually done and the evidence.

Reviewed HEAD: `cf220061bbfef022a3aa6c935f3c1e5bf57d1691`
Product baseline: `79f2942`

---

## Summary

| Severity | Count | Status |
|---|---|---|
| P0 | 0 | — |
| P1 | 1 | **FIXED** |
| P2 | 5 | 3 fixed, 2 deferred with disclosure |
| P3 | 3 | moved to v0.4.7 backlog |

**No P0 or P1 remains open.**

---

## P1

### F-01 — Release staging APK is not the verified candidate, and its checksum is wrong

**Status: FIXED**

What was wrong: staging held `a817434e…` (unreviewed binary) while
`SHA256SUMS.txt` recorded `68ed5c1d…`, matching neither file.

What was done:

1. Old staging APK and its incorrect checksum backed up to
   `workspace/releases/_backup-pre-ir12-fix/` (verified: backup APK is
   `a817434e…`).
2. Rebuilt from the F-05-fixed source with the project keystore
   (`.android-home/debug.keystore`) via `-P` flags.
3. Verified package identity: `tw.personal.privateinbox`, versionCode 11,
   versionName 0.4.6, `prodDebug`, no `INTERNET`.
4. Verified signature with `apksigner` — cert
   `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`,
   **identical to the public v0.4.4-alpha**.
5. Measured the new APK SHA-256:
   `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7`
6. Replaced the staged APK; regenerated `SHA256SUMS.txt`.
7. `shasum -a 256 -c SHA256SUMS.txt` → **OK**.
8. `scripts/release/verify-artifact.sh <apk> b68fd821…` → **ALL CHECKS PASSED**.

Note on the hash: the review's minimal fix suggested `d7b348a7…`. That was the
pre-F-05 binary. Because F-05 changed product code, the correct value is the
new build `b68fd821…`. The certificate — which is what governs upgrade
compatibility — is unchanged.

The CI artifact (`385d85ba…` / cert `256bb32f…`) was **not** used; it carries the
runner's ephemeral key and cannot update an existing install.

---

## P2

### F-05 — Conversation titles and legacy keys written to Logcat

**Status: FIXED**

Removed from `InboxDatabase.kt`:

- `:502` — `fail-closed: unknown canonicalId=$canonicalId` → now
  `fail-closed: no alias and no link` (no id).
- `:540` — `sql=… args=$args` → now logs only
  `query: predicates=N bindArgs=N bounded=N cursor=bool`. The SQL text, the
  hex-encoded legacy keys and the canonical ids are gone.
- `:574` — `rawRows/output` counts retained (permitted: counts only).

`BuildConfig.DEBUG` was deliberately **not** used as the fix: this release is
`prodDebug`, so that guard would not have removed anything.

Focused regression test added: `NoSensitiveLogInstrumentedTest` — opens a
conversation, loads an older page, and runs the search path while reading the
device's own log buffer, asserting absence of the title, the canonical id, the
legacy key, its hex form, SQL text and query arguments.

**Verified non-vacuous:** temporarily re-introducing the old log made the test
fail (`canonical conversation id leaked to Logcat`); restoring the fix made it
pass. So the test detects the regression it claims to.

Remaining log statements were audited: `LOADENTRIES` logs counts and stage
codes only; `DestructiveOperationGuard` logs a package name. Both are permitted
by AGENTS.md hard rule 4.

### F-06 — Release disclosure gaps

**Status: FIXED**

Created `docs/releases/v0.4.6-RELEASE-NOTES.md` and rewrote the README
known-issues list. Both now state plainly: Experimental Debug Alpha; Debug
certificate; `android:debuggable=true`; no release keystore; no in-place upgrade
to a differently-signed build (uninstall ⇒ data loss); DHC export exists but
**no in-app restore**; emulator-only validation; image capture off by default
with main-thread limits; possible duplicate capture without timestamps;
pagination limitation with undecryptable rows.

### F-07 — README known issues contradicted shipped behaviour

**Status: FIXED**

Rewrote `README.zh-TW.md` known issues with a "current as of" line. Corrected:
item 2 (key loss now described as detected + skipped, not merely "no crash"),
item 5 (**export exists** — previously claimed none), item 7 (**pagination
exists** — previously claimed a single 1500-row load).

### F-02 — Image scaling / JPEG encoding on the notification callback thread

**Status: DEFERRED (disclosed)**

Not fixed. It is a latency issue, not a privacy or data-integrity issue, and
changing the capture threading is an architecture change outside this release's
scope. Disclosed in the release notes and README ("image capture off by default;
when enabled, decode/encode still run on the main thread"). Moved to v0.4.7.

### F-04 — Pagination affordance / silent truncation edge cases

**Status: DEFERRED (disclosed)**

P2, edge-case only. Disclosed. v0.4.7.

---

## P3 → v0.4.7 backlog

- **F-03** — captured-during-merge rows stay with the target after unmerge
  (asserted contract, documented).
- **F-08** — stale test counts in docs: corrected to the current 290/290 in the
  matrix and handoff this round.
- **F-09** — latent TOCTOU in merge and merged-away target handling (not wired
  to UI yet).

---

## Not in scope this round

No new features, no IR-13, no architecture refactor, no keystore rotation, no
touching `v045-reliability` dirty files, no merge, no release.
