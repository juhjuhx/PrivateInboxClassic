# Private Inbox Classic v0.4.6 — Reviewer Package

## Pull Request
- **URL**: https://github.com/juhjuhx/PrivateInboxClassic/pull/4
- **PR HEAD**: `ee52d16c8c5bd996eecc6aaefd7b4adad7d7d23a` (documentation commit)
- **Product Code HEAD**: `d376f407976f511837f963227115e5610f42083f` (all product code; docs-only commits on top)
- **Base Branch**: `integration/dsh-v040`
- **Status**: DRAFT — awaiting independent reviewer APPROVE

## CI Evidence (Latest)
- **Run**: 41290016900 (in progress, HEAD `ee52d16`) — docs-only commit, no product code change
- **Previous successful run**: 41275245825 attempt 2, HEAD `1d4029f`, **291/291 PASS**, 0 skipped, 0 failed
- **JVM**: 157/157 PASS
- **Lint**: PASS
- **Security audits**: PASS (secrets + history, test isolation 51 files)
- **DHC connector**: PASS

## Release Artifact Identity (Verified)
- **APK SHA-256**: `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7`
- **Certificate SHA-256**: `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`
- **Package**: `tw.personal.privateinbox`
- **versionCode / versionName**: 11 / 0.4.6
- **Variant**: prodDebug
- **No INTERNET permission**
- **Identity matches public v0.4.4-alpha cert** — in-place upgrade path preserved

## Findings Disposition (14 original + 8 delta)
- P0: 0 | P1: 1 (FIXED) | P2: 6 (3 FIXED, 3 DEFERRED) | P3: 7 (2 FIXED, 1 ACCEPTED_RISK, 4 OPEN)
- D-01 to D-08: all dispositioned (D-06/D-07 DEFERRED, D-08 PARTIALLY FIXED)
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
- Write/read access to `juhjuhx/PrivateInboxClassic` repository
- Ability to submit formal `APPROVE` on PR #4

## Reviewer Invitation Content (copy-ready)

```
Hello,

I'm requesting an independent review of PR #4 in juhjuhx/PrivateInboxClassic.

PR: https://github.com/juhjuhx/PrivateInboxClassic/pull/4
PR HEAD: ee52d16 (documentation commit on top of product code d376f40)

This is the v0.4.6 Experimental Debug Alpha release. All product code changes
are complete and verified (291/291 instrumentation, 157/157 JVM, lint, security
audits all PASS). The PR contains only documentation commits since d376f40.

Release artifact identity:
- APK SHA-256: b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7
- Certificate SHA-256: c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4

Reviewer package: docs/handoff/current/REVIEWER-PACKAGE.md
Finding matrix: docs/verification/v0.4.6/CLAUDE-FINDING-RESOLUTION-MATRIX.md

Please review and submit APPROVE, REQUEST_CHANGES, or COMMENT on PR #4.

GitHub blocks self-review by the PR author (juhjuhx), so an independent
reviewer is required.

Thank you.
```

## Ready for Review Checklist
- [x] PR HEAD is current on GitHub
- [x] CI is green on latest product code HEAD (41275245825 attempt 2)
- [x] Release artifact verified (hash + cert)
- [x] Finding matrix complete (14 original + 8 delta)
- [x] Release notes with all required disclosures
- [ ] Independent reviewer APPROVE (EXTERNAL_REVIEWER_REQUIRED)
- [ ] PR remains DRAFT until reviewer signs off

## Reviewer Package URL
https://github.com/juhjuhx/PrivateInboxClassic/pull/4 (files: docs/handoff/current/REVIEWER-PACKAGE.md)
