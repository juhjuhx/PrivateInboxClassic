# Claude Review — Release Gate Matrix (PrivateInboxClassic v0.4.6)

Review HEAD `cf220061`, product code `79f2942`, CI run `41219559793`.
Status vocabulary: `PASS` / `FAIL` / `PARTIAL` / `NOT_VERIFIED` / `BLOCKED`.

## 1. Source and process gates

| Gate | Status | Evidence | Notes |
|---|---|---|---|
| Reviewed HEAD is the actual PR head | PASS | E-02, E-03 | `cf220061` on both |
| Product code unchanged after code HEAD | PASS | E-04 | only `docs/` differs in `79f2942..cf220061` |
| Baseline is an ancestor | PASS | E-05 | `3d56a7f5` |
| PR remains Draft until evidence complete | PASS | E-03 | `isDraft=true` |
| No release published | PASS | E-27 | only `v0.4.4-alpha` |
| Formal independent GitHub review exists | **BLOCKED** | E-03, E-09 | 0 reviews; session account is the PR author → `NOT_ELIGIBLE` for this account |
| Working tree preserved during review | PASS | E-01, E-07, E-08 | isolated clone; original workspace untouched |

## 2. CI and test gates

| Gate | Status | Evidence | Notes |
|---|---|---|---|
| CI executed on the reviewed SHA | PASS | E-10 | `41219559793`, `head_sha=cf220061` |
| Instrumentation actually ran on an emulator | PASS | E-11 | emulator runner step succeeded |
| Instrumentation 290/290, 0 skipped, 0 failed | PASS | E-12 | parsed report XML |
| JVM 157/157, 0 skipped | PASS | E-13 | parsed report |
| No `|| true` / `continue-on-error` on test steps | PASS | E-14 | authoritative step is bare |
| Test APK builds | PASS | E-11 | job succeeded |
| Lint | PASS | E-11 | job succeeded |
| `audit.sh`, `audit-secrets.sh`, `audit-test-isolation.py` | PASS | E-11 | job succeeded |
| DHC Python tests + contract | PASS | E-11 | job succeeded |
| Release build failure visible in CI | **FAIL** | E-15 | `assembleProdRelease || echo …` masks it (F-10, P3) |
| CI artifact not treated as an upgrade asset | PASS | E-18, E-24 | every run re-signs with a fresh cert |
| Test effectiveness sufficient for the lock/failure paths | **PARTIAL** | E-55, E-56 | F-11 |

## 3. Data and isolation gates

| Gate | Status | Evidence | Notes |
|---|---|---|---|
| `readConversationMessages()` fail-closed | PASS | E-29 | empty result, no full-table scan |
| Membership OR parenthesised; cursor cannot escape it | PASS | E-30 | SQL read directly |
| NUL-safe legacy keys | PASS | E-31 | `hex()` comparison |
| Placeholder count bounded | PASS | E-32 | per-conversation aliases only |
| Cross-package read impossible | PASS | E-31, E-36 | alias pkg-scoped; cross-pkg merge rejected |
| Migrations additive, nothing deleted | PASS | E-34 | v2–v6 guarded ALTERs |
| Repair is idempotent and durable | PASS | E-34, E-53 | state reset on failure |
| Merge/unmerge atomic (as implemented) | PASS | E-36, E-37 | single transactions; unreachable in 0.4.6 |
| Merge/unmerge correctness if later wired to UI | **PARTIAL** | E-36 | latent TOCTOU + merged-away target (F-09) |
| Deleted message cannot be resurrected | PASS | E-35, E-45 | re-derived; no path found |
| Pagination never silently truncates | **PARTIAL** | E-43 | F-04 (P2) |
| Pagination always terminates with correct affordance | **PARTIAL** | E-44 | F-04 (P2) |

## 4. Security and privacy gates

| Gate | Status | Evidence | Notes |
|---|---|---|---|
| No `INTERNET` permission in the packaged APK | PASS | E-23 | measured on `d7b348a7` |
| No analytics/tracking dependency | PASS | E-23, E-50 | androidx only |
| Listening service not over-exported | PASS | E-23 | `exported=false` + BIND |
| Debug provider present only in debug | PASS | E-48 | `src/debug` manifest only |
| Debug provider signature + UID protected | PASS | E-48 | two independent layers |
| `allowBackup=false`, extraction rules exclude data | PASS | E-23 | measured |
| `FLAG_SECURE` unconditional | PASS | E-46 | set in `onCreate` |
| AES-256-GCM with random IV, tag, AAD | PASS | E-47 | code read |
| Keystore key state handled without data loss | PASS | E-47 | distinct INVALIDATED handling; no auto-delete |
| No plaintext message bodies in logs | PASS | E-33 | none found |
| No conversation metadata/titles in logs | **FAIL** | E-33 | `READMODEL` logs query args (F-05, P2) |
| Keystore/signing material absent from Git | PASS | E-28 | history scanned |

## 5. Build and artifact gates

| Gate | Status | Evidence | Notes |
|---|---|---|---|
| Public v0.4.4 identity matches the documented one | PASS | E-19 | measured |
| Candidate binary obtainable and verified | PASS | E-20, E-21 | `d7b348a7` reproduced twice |
| Candidate identity is not an artifact of the wrong key | PASS | E-22 | control build differs |
| Candidate version code/name correct | PASS | E-23 | 11 / 0.4.6 |
| Candidate manifest matches the documented security posture | PASS | E-23 | measured |
| CI artifact certificate distinct from candidate | PASS | E-18, E-24 | excluded as upgrade asset |
| Release staging directory internally consistent | **FAIL** | E-25, E-26 | staged `a817434e`, sums `68ed5c1d`, candidate `d7b348a7` → **F-01, P1** |
| Checksum manifest verifies | **FAIL** | E-26 | sums file matches no file present |
| Release APK (production key) produced and verified | NOT_VERIFIED | E-50 | unsigned by design; keystore not used |

## 6. Upgrade and compatibility gates

| Gate | Status | Evidence | Notes |
|---|---|---|---|
| Public v0.4.4 → candidate signature compatible | PASS | E-19, E-20 | same cert `c876051e…` |
| Fingerprint/discriminator compatible with v0.4.4 data | PASS | E-40 | identical code on the tag |
| Encrypted ciphertext preserved across upgrade | EVIDENCE_CHAIN_REVIEWED | E-58 | not re-executed (L-01) |
| Upgrade independently reproduced in this review | **NO** | L-01 | explicitly not claimed |
| Debug → future production key migration path defined | **FAIL** | E-50, E-61 | none; uninstall loses data |
| User export exists for data preservation | PASS | E-49 | encrypted DHC export |
| In-app restore of an export exists | **FAIL** | E-49 | export is one-way |
| Debug-signed/debuggable nature disclosed to users | **FAIL** | E-61, E-66 | F-06 |
| Release notes exist for v0.4.6 | **FAIL** | E-27 | no release, no notes |

## 7. Documentation gates

| Gate | Status | Evidence | Notes |
|---|---|---|---|
| Test counts in the release evidence are current | PASS | E-63 | 290/157 |
| Test counts in the user README are current | **FAIL** | E-65 | F-08 |
| Handoff state reflects the real run | **FAIL** | E-64 | F-08 |
| Known-issues list matches shipped behaviour | **FAIL** | E-66 | F-07 |
| In-app version string correct | **FAIL** | E-46 | F-13 |
| PR description matches the reviewed SHA and current evidence | **FAIL** | E-67 | F-14 |
| No self-review presented as independent | PASS | E-03, E-09 | this report explicitly disclaims it |

## 8. Publication decision

| Condition | Met? |
|---|---|
| No P0 findings | YES (0) |
| No P1 findings | **NO** (1: F-01) |
| P0/P1 gates also require Formal Review | **NO** (0 reviews; author account ineligible) |
| Release notes disclose data-loss / signature migration risks | **NO** |
| Release staging set verifies | **NO** |
| Emulator upgrade chain reviewed | YES (`EVIDENCE_CHAIN_REVIEWED`) |

**Publication gate: CLOSED.**

Blocking items, in order:
1. **F-01** replace the staged APK with `d7b348a7…`, regenerate `SHA256SUMS.txt`, run `verify-artifact.sh`.
2. **F-06** write the v0.4.6 release notes with the debug-signature migration, one-way export, debuggable-build, and emulator-only disclosures.
3. **F-07** correct `README.zh-TW` known issues.
4. Obtain a genuinely independent PR review (this account cannot provide it).
5. Optional but recommended before publication: F-02, F-03, F-04, F-05 (P2) fixed or recorded as accepted limitations.

---

# DELTA — Re-measured at `d376f40` (2026-11-01)

The matrix above is the state at `cf220061`. Codex's corrective commit changes these gates:

| Gate | Then | Now | Evidence |
|---|---|---|---|
| Candidate binary obtainable and verified | PASS (`d7b348a7`) | **PASS** (`b68fd821`; rebuilt `--rerun-tasks` from `d376f40` → identical) | `shasum`, `apksigner --print-certs` |
| Release staging directory internally consistent | **FAIL** (F-01) | **PASS** — staged APK = candidate, sums verify exit 0, old binary in `_backup-pre-ir12-fix/` | `shasum -c` |
| Checksum manifest verifies | **FAIL** | **PASS** — exit 0 | `shasum -a 256 -c` |
| CI artifact kept out of the release set | PASS | **PASS** — run-41231048769 artifact `ff516433…` / cert `4def3c89…`, absent from the release dir | `gh run download`, `apksigner` |
| No conversation metadata/titles in logs | **FAIL** (F-05) | **PASS** — identifiers and SQL text removed; regression test in CI | source read + dex strings |
| Instrumentation 290/290, 0 skipped, 0 failed | PASS | **PASS at 291/291** (run 41231048769, `d376f40`; 0 skipped, 0 failed). Raw XML not published — console + static `@Test` count (290→291) | `gh run view --log` |
| JVM 157/157, 0 skipped | PASS | **PASS** — artifact HTML counters 157/0/0 | artifact |
| Release notes disclose risks | **NO** | **PASS** — `v0.4.6-RELEASE-NOTES.md` + closure §11 | doc read |
| Release-build failure visible in CI | **FAIL** | **FAIL (unchanged)** — `assembleProdRelease \|\| echo …` still masks it (F-10) | workflow |
| `verify-artifact.sh` can reject a wrong certificate | NOT_VERIFIED | **FAIL** — no expected-cert argument; wrong-cert APK exits 0 (D-06) | executed |
| Instrumentation evidence auditable from raw XML | not claimed | **FAIL** — connected-test XML not uploaded (D-07) | artifact listing |
| Documentation states the artifact's real provenance | PASS | **FAIL (new, D-02)** — release notes/closure/PR say `79f2942`; artifact is from `d376f40` | measured |
| Finding set preserved | PASS (14) | **FAIL (new, D-01)** — matrix holds 9; F-03 P2→P3 unexplained; F-10…F-14 missing | doc read |

**Delta publication gate: CLOSED on engineering, OPEN on documentation.** No code change is
required. Blocking items now:
1. **D-01** reconcile the resolution matrix to the original 14 findings and severities, with a
   status for each.
2. **D-02** correct the shipped artifact's provenance (`79f2942` → `d376f40`) in release notes,
   closure §5 and the PR body, and drop the "docs-only" description of `d376f40`.
3. **D-03/D-04/D-05** refresh the test counts (291/291) and the IR-12 section for `b68fd821`.
4. Obtain a genuinely independent PR review (this account cannot provide one).

`TECHNICAL_RE_REVIEW=CHANGES_REQUESTED` · `RELEASE_ARTIFACT=VERIFIED` · `FORMAL_GITHUB_APPROVAL=PENDING`.
