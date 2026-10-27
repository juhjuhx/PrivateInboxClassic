# WORKTREE INVENTORY — v0.4.6 R0

Generated from actual `git` commands on 2026-10-24. No value here is assumed.

## Worktrees

| Absolute path | HEAD | Branch | Tracking | Dirty |
|---|---|---|---|---|
| `/Users/huang/Desktop/我的已讀不回助理/PrivateInboxClassic-v0.2/codex-integration` | `bb36c83f524c0aa87ba04680f0f4c0bfea79318f` | `v045-reliability` | `origin/fix/x200pro-notification-burst` (ahead 7) | yes |

`git worktree list --porcelain` reports exactly ONE worktree. There is no second
checkout, no detached worktree, no stale registered tree.

## The only valid working tree

**`PrivateInboxClassic-v0.2/codex-integration`** — this is the sole working tree
and the only one that may be modified.

## Branches present

| Branch | HEAD | Notes |
|---|---|---|
| `v045-reliability` (current) | `bb36c83` | ahead 7 of `origin/fix/x200pro-notification-burst` |
| `integration/dsh-v040` | `3d56a7f` | tracks `origin/integration/dsh-v040`; this is the public default branch per the brief, but it is NOT checked out here |

**Discrepancy with the brief, recorded rather than smoothed over:** the brief
said the public default branch is `integration/dsh-v040`. That matches the
remote-tracking branch, but the local checked-out branch `v045-reliability`
tracks `origin/fix/x200pro-notification-burst`, not `integration/dsh-v040`. Any
future integration must reconcile those two.

## Remote

```
origin  https://github.com/juhjuhx/PrivateInboxClassic.git (fetch)
origin  https://github.com/juhjuhx/PrivateInboxClassic.git (push)
```

No push, merge, force-push or release was performed.

## Dirty status (complete)

### Modified (4) — the v0.4.5 reliability candidate, still uncommitted
```
 M app/src/androidTest/java/tw/personal/privateinbox/BurstCaptureInstrumentedTest.kt
 M app/src/main/java/tw/personal/privateinbox/CaptureDiagnostics.kt
 M app/src/main/java/tw/personal/privateinbox/InboxDatabase.kt
 M app/src/main/java/tw/personal/privateinbox/PrivateNotificationListener.kt
```

### Untracked — v0.4.5 handoff + v0.4.6 work
```
CHECKPOINT-0.md  CHECKPOINT-R1.md  CHECKPOINT-R2.md
app/src/androidTest/.../BatchRetryFaultInjectionInstrumentedTest.kt
app/src/androidTest/.../ConversationIdentityInstrumentedTest.kt
app/src/main/java/.../ConversationIdentityResolver.kt
app/src/main/java/.../ConversationStore.kt
app/src/main/java/.../LegacyKeyCodec.kt
app/src/test/java/.../ConversationIdentityResolverTest.kt
app/src/test/java/.../LegacyFingerprintCompatibilityTest.kt
app/src/test/java/.../LegacyKeyCodecTest.kt
docs/handoff/v045/**   (10 files)
docs/handoff/v046/**   (11 files + golden/)
scripts/release/verify-artifact.sh
scripts/release/install-artifact.sh
```

`git diff --check` → clean (no whitespace errors).

## Existing APK artifacts

| Path | Built from | Note |
|---|---|---|
| `app/build/outputs/apk/prod/debug/app-prod-debug.apk` | current tree | debug-signed |
| `app/build/outputs/apk/devtest/debug/app-devtest-debug.apk` | current tree | debug-signed |
| `app/build/outputs/apk/androidTest/devtest/debug/app-devtest-debug-androidTest.apk` | current tree | test APK |

## Signing fingerprints (measured, not quoted from docs)

| Item | SHA-256 |
|---|---|
| `keystore/debug.keystore` certificate | `46488e99373f3c1af0c630642f2d9ec459b8db671391f3ad817efb3aae99da94` |
| Installed devtest APK on emulator | same (`46488e99…`) |
| `app-prod-debug.apk` | same (`46488e99…`) |

**`docs/SIGNING.md` claims the project uses key B (`c876051e…`). That contradicts
the measured artifacts.** The document is wrong and is scheduled for correction
in R4. No key was rotated, replaced or regenerated.

## Existing test reports (with dates)

| Path | Timestamp | Contents |
|---|---|---|
| `app/build/test-results/testProdDebugUnitTest/` | 2026-10-24 19:42 | JVM unit XML (157 tests last run) |
| `app/build/reports/lint-results-prodDebug.html` | 2026-10-24 19:42 | lint report |
| `app/build/outputs/androidTest-results/.../TEST-*.xml` | 2026-10-24 12:56 | **STALE** — still shows the old 54-failure run; `am instrument` does not refresh it |

## Documentation duplication / reference map

| Topic | Files | Status |
|---|---|---|
| Repository state | `docs/handoff/v045/01-REPOSITORY-STATE.md`, `docs/handoff/v046/00-BASELINE.md`, `docs/AGENT_STATE.md` | three overlapping snapshots |
| Architecture | `docs/ARCHITECTURE.md`, `docs/handoff/v045/05-ARCHITECTURE.md`, `docs/handoff/v046/01-ARCHITECTURE-AS-IS.md`, `docs/architecture/` | 4 places |
| Root cause | `docs/handoff/v045/03-P0-ROOT-CAUSE.md` | single |
| Migration | `docs/handoff/v046/04-MIGRATION-CONTRACT.md`, `docs/DATABASE.md` | overlapping |
| Test evidence | `docs/handoff/v045/04-TEST-EVIDENCE.md`, `docs/handoff/v046/06-REGRESSION-EVIDENCE.md`, `docs/handoff/v046/ACCEPTANCE-EVIDENCE.md`, `docs/TEST-REPORT.md`, `docs/TEST-LOG.txt`, `docs/INSTRUMENTED-TEST-LOG.txt` | 6 places |
| Status reports | `docs/STATUS-REPORT-v0.4.2.md`, `docs/STATUS-REPORT-v0.4.4.md` | historical, superseded |
| Agent state | `docs/AGENT_STATE.md`, `docs/handoff/v046/AGENT_STATE.md`, `CHECKPOINT-0.md`, `CHECKPOINT-R1.md`, `CHECKPOINT-R2.md` | 5 places |

Consolidation targets are specified in `docs/engineering/CLEANUP-MANIFEST.md`
(R4). Nothing was moved or deleted in R0.

## Safety snapshot

| | |
|---|---|
| Location | `~/Desktop/pi-v046-r0-snapshot/` (outside the repo, mode 700) |
| Contents | `tracked.diff`, `untracked.tgz`, `HEAD.txt`, `branches.txt`, `SHA256SUMS.txt` |
| Size | 108 KB |
| Restore | `git apply tracked.diff` + `tar -xzf untracked.tgz` from the repo root |

Manifest:
```
10fe65aaabc05ce3e00d604f291aea87f439046fb0321899e4c9622eb1e6318d  branches.txt
d0627244c36c96c3bad400418a3c7ad7dc662a027afa82b981621dd83b4ef3d7  HEAD.txt
c6242010fdd43e580e4213eb35ed892b245a60a19bc402857263b734dd8f40b6  tracked.diff
17a3a98c113bfc0a6f1eaeab3d3f2cca0a69f0b68b549c9612c699e4965938cc  untracked.tgz
```

## Not deleted
User message database, keystore, original APKs, uncommitted source, test
evidence and historical worktrees were all left untouched.
