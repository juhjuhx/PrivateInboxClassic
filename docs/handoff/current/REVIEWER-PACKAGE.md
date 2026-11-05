# Private Inbox Classic v0.4.6 — Reviewer Package

## Pull Request
- **URL**: https://github.com/juhjuhx/PrivateInboxClassic/pull/5
- **PR HEAD**: `04c640db52a02b9751ce40139fe543fa726bb965`
- **Branch**: `fix/v047-originos-listener-reliability`
- **Base Branch**: `integration/dsh-v040`
- **Status**: OPEN — awaiting independent reviewer APPROVE
- **Note**: v0.4.6-alpha is already published (merge `8e31810`, tag `v0.4.6-alpha`).
  This PR is the v0.4.7 line and is **not** covered by the v0.4.6 owner waiver.

## CI Evidence (Latest — for THIS PR HEAD)
- **Run**: [41777489965](https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41777489965) — success, HEAD `04c640d`
- **Instrumentation**: **302 / 302 PASS**, 0 skipped, 0 failed
- **JVM**: 157 / 157 PASS
- **Lint**: PASS
- **Security audits**: PASS (secrets + history, test isolation 51 files)
- **DHC connector**: PASS
- **Debug APK run**: [41777489983](https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41777489983) — success

## Candidate Artifact Identity (locally signed, NOT published)
- **APK SHA-256**: `990150d3f7e7d09f6e08967b515b1cc16de9ae0b4234acf0d769b356aa1018d3`
- **Certificate SHA-256**: `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`
- **Package**: `tw.personal.privateinbox`
- **versionCode / versionName**: **12 / 0.4.7**
- **Variant**: prodDebug
- **No INTERNET permission**
- **Identity matches public v0.4.4-alpha and v0.4.6-alpha cert** — in-place update path preserved
- Signed locally with the preserved project keystore. The GitHub Runner's ephemeral
  key (`53091b91…`) is **not** usable for an in-place device update and must not
  be shipped.

## Published v0.4.6-alpha (for reference — must not change)
- **APK SHA-256**: `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7`
- **Certificate SHA-256**: `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`
- **Release**: https://github.com/juhjuhx/PrivateInboxClassic/releases/tag/v0.4.6-alpha

## Findings Disposition (14 original + 8 delta)
- P0: 0 | P1: 1 (FIXED) | P2: 6 (3 FIXED, 3 DEFERRED) | P3: 7 (3 FIXED, 1 ACCEPTED_RISK, 3 OPEN)
- F-13 (settings showed the wrong version) is FIXED in this line —
  `BuildConfig.VERSION_NAME` replaces the hard-coded `v0.3.1`; the diagnostics
  export no longer hard-codes `0.4.0-alpha`.
- F-10 (CI masks the release-build failure) is **still open** and re-verified as
  such on this branch — `.github/workflows/android.yml:110` still has `|| echo`.
- D-01 to D-08: all dispositioned (D-06/D-07 DEFERRED, D-08 PARTIALLY_FIXED)
- Matrix: `docs/verification/v0.4.6/CLAUDE-FINDING-RESOLUTION-MATRIX.md`

## Remaining Accepted Risks (P2/P3)
- F-02: Image work on notification callback thread — deferred to v0.4.7
- F-03: Timestamp-less duplicate rows — deferred to v0.4.7
- F-04: Pagination early-stop with undecryptable rows — deferred to v0.4.7
- F-09/F-10/F-11/F-12/F-13: latent issues, disclosed
- D-06: Release script cannot verify certificate — deferred
- D-07: Connected-test XML not published as artifact — deferred

## Release Limitations (Debug Alpha)
- Debug certificate; no release keystore
- `android:debuggable=true` — not suitable for sensitive data
- No in-place upgrade to production-signed build (data loss on switch)
- DHC export is one-way; no in-app restore
- Emulator-only validation; no physical device/OEM verification

## Reviewer Qualifications Required
- GitHub account **different from `juhjuhx`** (PR author — self-review blocked by GitHub)
- Read access to `juhjuhx/PrivateInboxClassic`; write access to submit a review
- Ability to submit formal `APPROVE` on PR #5

## Reviewer Invitation Content (copy-ready)

```
Hello,

I'm requesting an independent review of PR #5 in juhjuhx/PrivateInboxClassic.

PR: https://github.com/juhjuhx/PrivateInboxClassic/pull/5
PR HEAD: 04c640d (branch fix/v047-originos-listener-reliability)
Base: integration/dsh-v040

This is the v0.4.7 OriginOS listener-reliability hotfix, built on the
published v0.4.6-alpha. CI on this exact HEAD is green:
302/302 instrumentation, 157/157 JVM, lint, security audits all PASS.

What changed:
- Diagnostics now report the real BuildConfig version instead of hard-coded
  strings ("0.4.0-alpha" in the export, "v0.3.1" in Settings).
- Added process session id + start timestamp + counter, and a per-bind listener
  instance id, so "process restarted" is distinguishable from "never connected".
- Added LISTENER_DESTROYED: on OriginOS the service can be destroyed without a
  clean disconnect, which previously left the connected flag set.
- Persisted CONNECTED evidence now expires after 30 minutes instead of being
  read as current connectivity forever.
- Rebind requests are recorded as REQUESTED/ACCEPTED/REFUSED, never as a
  connection; automatic rebinds respect a 60 s cooldown.
- Settings explains what PERMISSION_GRANTED_NOT_CONNECTED means and lists the
  OriginOS battery/autostart settings the user must change.

Candidate artifact identity:
- APK SHA-256: 990150d3f7e7d09f6e08967b515b1cc16de9ae0b4234acf0d769b356aa1018d3
- Certificate SHA-256: c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4
- Package: tw.personal.privateinbox, versionCode 12 / versionName 0.4.7
- No INTERNET permission

Reviewer package: docs/handoff/current/REVIEWER-PACKAGE.md
Finding matrix:   docs/verification/v0.4.6/CLAUDE-FINDING-RESOLUTION-MATRIX.md
RCA + device test matrix:
  docs/verification/v0.4.7-ROOT-CAUSE-ANALYSIS.md

Please submit APPROVE, REQUEST_CHANGES, or COMMENT on PR #5.

Two things to note:
1. GitHub blocks self-review by the PR author (juhjuhx), so an independent
   reviewer is required.
2. The vivo X200 Pro device matrix has NOT been run yet, so this PR does not
   claim the OriginOS background-drop problem is solved. The device test
   conditions are in the RCA document. A review of the code and evidence is
   still valuable now; the device result is a separate release gate.

Thank you.
```

## Ready for Review Checklist
- [x] PR HEAD is current on GitHub (`04c640d`)
- [x] CI is green on this exact HEAD (41777489965 — 302/302)
- [x] Candidate artifact verified locally (hash + cert + package + version + no INTERNET)
- [x] Finding matrix complete (14 original + 8 delta), statuses re-verified against code
- [x] Release notes with all required disclosures
- [x] Root cause analysis + device test matrix documented
- [ ] Independent reviewer APPROVE (EXTERNAL_REVIEWER_REQUIRED)
- [ ] vivo X200 Pro device matrix run (separate release gate — not covered by a code review)
- [ ] v0.4.7 merged / published

## Reviewer Package URL
https://github.com/juhjuhx/PrivateInboxClassic/pull/5
(files: docs/handoff/current/REVIEWER-PACKAGE.md,
 docs/verification/v0.4.6/CLAUDE-FINDING-RESOLUTION-MATRIX.md,
 docs/verification/v0.4.7-ROOT-CAUSE-ANALYSIS.md)
