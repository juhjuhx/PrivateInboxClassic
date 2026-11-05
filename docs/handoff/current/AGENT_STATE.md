# Private Inbox Classic v0.4.6 — Agent State

**Project:** PrivateInboxClassic
**Target:** v0.4.6 Experimental Debug Alpha

## Current Gates

| Gate | Status |
|---|---|
| C0 — Machine and Disk Preflight | PASS (JDK 17, SDK Homebrew complete, 7.6Gi free) |
| C1 — Repository and Document Recovery | PASS (active worktree at /tmp/v046-final, review reports preserved) |
| C2 — Live GitHub Validation | PASS (PR #4 open, draft, CI 41275245825 attempt 2 = success, 291/291) |
| C3 — Final Release Artifact | PASS (SHA-256 b68fd821…, cert c876051e… verified) |
| C4 — Independent Review | EXTERNAL_REVIEWER_REQUIRED (0 reviews, only author account) |
| C5 — Conditional Public Alpha Release | NOT_PUBLISHED (blocked on C4) |
| C6 — Workspace Archive and Disk Recovery | IN_PROGRESS (inventory complete, no deletion without owner authorization) |
| C7 — Cross-Project Boundary | KU_YIN closed, other projects untouched |
| C8 — Final Documentation | IN_PROGRESS |

## Verified Baselines

- PR HEAD: `1d4029fbabf1f0fda0f6f49cb1696f4bb489098d`
- Binary Source HEAD: `d376f407976f511837f963227115e5610f42083f`
- CI Run: 41275245825 (attempt 2, success)
- Instrumentation: 291/291 PASS, 0 skipped, 0 failed
- JVM: 157/157 PASS
- APK SHA-256: `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7`
- Cert SHA-256: `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`
- Package: tw.personal.privateinbox, versionCode 11, versionName 0.4.6
- No INTERNET permission

## Protected Historical Worktrees (DO NOT MODIFY)

- `/Users/huang/Desktop/我的已讀不回助理/PrivateInboxClassic-v0.2/codex-integration` — branch `v045-reliability`, HEAD `bb36c83`, 43 uncommitted files preserved
- `/tmp/v046-final/` — active release worktree at PR HEAD `1d4029f`

## Disk

- Free before: 7.4Gi (97% used)
- Free after: 7.4Gi (97% used)
- Space recovered: 0GB (no destructive cleanup without owner authorization)
- Local android-sdk: 5.5GB (referenced in scripts but Homebrew SDK is complete — candidate for cleanup only with owner authorization)
- AVD test36: 5.2GB (preserved for release verification)

## Current Line — v0.4.7 (PR #5)

| Item | Value |
|---|---|
| PR | #5 — https://github.com/juhjuhx/PrivateInboxClassic/pull/5 |
| HEAD | `04c640db52a02b9751ce40139fe543fa726bb965` |
| Branch | `fix/v047-originos-listener-reliability` |
| CI | run 41777489965 — success, **302/302** instrumentation, 157/157 JVM |
| Version | 12 / 0.4.7 (v0.4.6 released as `v0.4.6-alpha`, unchanged) |
| Signed APK | `990150d3f7e7d09f6e08967b515b1cc16de9ae0b4234acf0d769b356aa1018d3` |
| Cert | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` |

### Findings status (14 original, 8 delta)

| | Count | Fixed | Deferred / accepted | Open |
|---|---|---|---|---|
| P0 | 0 | — | — | 0 |
| P1 | 1 | 1 | 0 | **0** |
| P2 | 6 | 3 | 3 | 0 |
| P3 | 7 | 3 (F-08, F-13, F-14) | 1 (F-09) | 3 (F-10, F-11, F-12) |

F-13 (settings showed the wrong version) is fixed in the v0.4.7 line —
`BuildConfig.VERSION_NAME` replaces the hard-coded `v0.3.1`, and the
diagnostics export no longer hard-codes `0.4.0-alpha`.
F-10 (CI masks the release-build failure) is **still open** and re-verified as
such: `.github/workflows/android.yml:110` still has `|| echo`. It stays until a
release keystore exists, because an unsigned release build is the expected
outcome today.

## Remaining External Blockers

- **FORMAL_REVIEW=PENDING** — independent GitHub reviewer APPROVE required.
  Only account `juhjuhx` is the PR author; GitHub blocks self-review.
- **OriginOS device verification PENDING** — the vivo X200 Pro matrix has not
  been run, so the background-drop behaviour is **not** proven fixed.
  Required conditions are listed in
  `docs/verification/v0.4.7-ROOT-CAUSE-ANALYSIS.md`.
- PR #5 is open and not draft; it must not be merged until both are resolved.

## Next Action

1. Run the v0.4.7 diagnostic matrix on the vivo X200 Pro and export the report.
2. Request an independent reviewer APPROVE on PR #5.
3. Do not merge, tag, or publish v0.4.7 until both are done.
