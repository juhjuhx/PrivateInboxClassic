# C12 — Disk Cleanup Dry-Run Manifest (PrivateInbox Classic v0.4.6)

**Date**: 2026-11-01  
**Disk before**: 7.4Gi free (97% used)  
**Disk after**: 7.4Gi free (97% used)  
**Actual space recovered**: 0GB  
**Cache cleaned**: NO  

## Principle
No destructive cleanup without owner authorization. No `git clean`, `reset --hard`, or `rm -rf` on work areas. No deletion of unknown data.

## Candidate Inventory

| Path | Size | Purpose | Owner | Classification | Decision | Rationale |
|---|---|---|---|---|---|---|
| /tmp/v046-final/ | 45MB | Active release worktree (PR HEAD ee52d16) | PIC v0.4.6 | KEEP_ACTIVE | Keep | Linked to PR #4 via git worktree; contains docs commits and pushed state |
| /Users/huang/Desktop/我的已讀不回助理/gradle_home | 1.1GB | Gradle wrapper distribution cache | PIC | KEEP_ACTIVE | Keep | Required for builds; distributionUrl pinned to gradle-8.13 |
| ~/.gradle | 2.9GB | Global Gradle daemon/cache | PIC + other projects | KEEP_ACTIVE | Keep | Shared across projects; contains credentials/config; do not wipe |
| /Users/huang/Desktop/我的已讀不回助理/android-sdk | 5.5GB | Local Android SDK (platforms 33/36) | PIC | SAFE_REGENERABLE | DRY-RUN only | Referenced in scripts but Homebrew SDK is complete. Deletion would break scripted builds that reference this path. Requires owner authorization + script updates. |
| /Users/huang/Desktop/我的已讀不回助理/.android-home/avd/test36.avd | 5.2GB | Emulator AVD API 36 | PIC | KEEP_ACTIVE | Keep | Required for release verification (API 33 upgrade, API 36 suite). Do not wipe during active release cycle. |
| /tmp/v046-final/app/build/ | 40MB | Build outputs (APKs, intermediates) | PIC | SAFE_REGENERABLE | Keep | Build outputs are regenerable but needed for artifact verification. Do not delete until release published. |
| /Users/huang/Desktop/我的已讀不回助理/PrivateInboxClassic-v0.2/ | 1.3GB | Historical worktrees (main repo + codex-integration + anon) | PIC | KEEP_ACTIVE | Keep | Contains dirty v045-reliability worktree (43 uncommitted files) — protected historical evidence. Do not touch. |
| /Users/huang/Desktop/我的已讀不回助理/*.apk (~50MB) | ~50MB | Historical APK builds (v0.4.0–v0.4.4) | PIC | SAFE_REGENERABLE | Keep | Historical release artifacts; may be needed for upgrade evidence comparison. |

## Candidates NOT Touched (per C6-C rules)
- ~/.hermes/state.db — preserved
- ~/.codex/packages — preserved
- Active runtime directories — preserved
- Git repositories with dirty worktrees — preserved (v045-reliability)
- Release APKs — preserved
- Signing keys/keystore — preserved
- User data — preserved
- Unverified snapshots — preserved

## Cleanup Manifest (DRY-RUN only — no action taken)

No deletions were performed. The following are documented for potential future owner-authorized cleanup:

| Candidate | Size | Dependencies | Restoration | Risk | Est. Recovery |
|---|---|---|---|---|---|
| android-sdk (5.5GB) | Scripts reference this path; Homebrew SDK complete | Re-download from Android SDK Manager | Medium (breaks scripted paths) | 5.5GB |
| app/build/ (40MB) | Gradle build outputs | Re-run assemble tasks | Low | 40MB |
| Historical APKs (~50MB) | Upgrade evidence comparison | Re-download from GitHub releases | Low | 50MB |

## Summary
- **DISK_FREE_BEFORE**: 7.4Gi
- **DISK_FREE_AFTER**: 7.4Gi  
- **ACTUAL_SPACE_RECOVERED**: 0GB
- **CLEANUP_MANIFEST**: See above (dry-run only, no deletions)
- **No destructive actions performed** — owner authorization required for any cleanup
