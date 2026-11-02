# Claude Review — Evidence Ledger (PrivateInboxClassic v0.4.6)

Every row below is something this review actually ran or read. Columns: what was checked, how
(command / source), result, and evidence class.

Evidence classes:
- `MEASURED` — produced by a command in this review.
- `SOURCE_READ` — read the actual source at the stated commit.
- `CI_ARTIFACT_INSPECTED` — read GitHub Actions run/artifact/report data via `gh`.
- `DOC_READ` — read a project document (treated as a claim, not proof).
- `INFERRED` — reasoned from the above; labelled where used.

---

## 1. Repository, commit topology and review scope

| # | Check | Command / source | Result | Class |
|---|---|---|---|---|
| E-01 | Isolated clone, no product edits | `git -C [臨時審查目錄]/repo status --short` | empty output, before and after the audit | MEASURED |
| E-02 | Review HEAD | `git rev-parse HEAD` in the clone | `cf220061bbfef022a3aa6c935f3c1e5bf57d1691` | MEASURED |
| E-03 | PR head matches review HEAD | `gh pr view 4 --repo juhjuhx/PrivateInboxClassic --json headRefOid,baseRefOid,isDraft,author,reviews,reviewDecision` | `headRefOid=cf220061…`, `baseRefOid=ff1091fb…`, `isDraft=true`, author `juhjuhx`, `reviews=[]`, `reviewDecision` empty | CI_ARTIFACT_INSPECTED |
| E-04 | Product-code HEAD holds | `git diff --name-only 79f2942059a815d275403d924be1c1e25024d786 cf220061` | 15 paths, all under `docs/` | MEASURED |
| E-05 | Baseline is an ancestor | `git merge-base --is-ancestor 3d56a7f5 cf220061 && echo yes` | `yes` | MEASURED |
| E-06 | Diff size vs baseline | `git diff --shortstat 3d56a7f5 cf220061` | `106 files changed, 17245 insertions(+), 172 deletions(-)`; 49 commits | MEASURED |
| E-07 | Original workspace untouched | `git -C ~/Desktop/我的已讀不回助理/PrivateInboxClassic-v0.2/PrivateInboxClassic worktree list` + `status --short` for each worktree | 3 worktrees, 0 dirty entries | MEASURED |
| E-08 | `v045-reliability` dirty worktree not found/modified | `mdfind -name v045-reliability`, `find ~ -maxdepth 6 -type d -name 'v045-reliability'` | No local directory matched; nothing was written or reset | MEASURED |
| E-09 | Session's GitHub identity | `gh auth status` | logged in as `juhjuhx` — **the PR author**; formal self-approval not permitted and not attempted | MEASURED |

## 2. CI

| # | Check | Command / source | Result | Class |
|---|---|---|---|---|
| E-10 | CI run HEAD and conclusion | `gh api repos/juhjuhx/PrivateInboxClassic/actions/runs/41219559793` | `head_sha=cf220061…`, `conclusion=success`, `event=pull_request` | CI_ARTIFACT_INSPECTED |
| E-11 | CI jobs | `gh api …/runs/41219559793/jobs` | APK job and instrumentation job both `success`; emulator step succeeded | CI_ARTIFACT_INSPECTED |
| E-12 | Instrumentation counts | Downloaded `test-reports` artifact (id `13050215847`) and parsed the XML | 290 tests, 290 run, **0 skipped, 0 failed** | CI_ARTIFACT_INSPECTED |
| E-13 | JVM counts | Same artifact, `testDevtestDebugUnitTest` HTML/XML | 157 tests, 157 passed, 0 skipped | CI_ARTIFACT_INSPECTED |
| E-14 | No `|| true` on authoritative test step | Read `.github/workflows/android.yml` | Instrumentation step runs `:app:connectedDevtestDebugAndroidTest` bare; no `continue-on-error` anywhere in the file | SOURCE_READ |
| E-15 | Release-build masking | `.github/workflows/android.yml:109-110` | `:app:assembleProdRelease || echo "Release APK unsigned — …"` — masked | SOURCE_READ |
| E-16 | IR-12 CI run | `gh api …/runs/41204287676` | `head_sha=c6d9b1a…`, `conclusion=success` | CI_ARTIFACT_INSPECTED |
| E-17 | CI artifacts of 41219559793 | `gh api …/runs/41219559793/artifacts` | `13050465126 PrivateInboxClassic-debug-apk` (2,176,402 B zip), `13050215847 test-reports` (23,459 B) | CI_ARTIFACT_INSPECTED |
| E-18 | CI debug APK is runner-signed | Downloaded artifact, `apksigner --print-certs` and `shasum -a 256` | APK `22a5bd0c…`, cert `646fe90f…` — differs from every previous CI cert and from `c876051e…` | MEASURED |

## 3. APK identity

Environment note: `apksigner`/`aapt2` required `JAVA_HOME=/opt/homebrew/opt/openjdk@17`.

| # | Check | Command | Result | Class |
|---|---|---|---|---|
| E-19 | Public v0.4.4 asset | `gh release download v0.4.4-alpha`, `shasum -a 256`, `apksigner --print-certs`, `aapt2 dump badging` | SHA-256 `6c6def174902a192cf5f65846261c517ff42edff62bde8ee9c72104ddd8c1556`; cert `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4`; `versionCode=10`, `versionName=0.4.4`; 5,986,817 B | MEASURED |
| E-20 | Candidate rebuild (first) | Copied `keystore/signing.properties` + `keystore/debug.keystore` from `PrivateInboxClassic-v0.4-wt` into the clone (git-ignored; `git status` stayed clean), `./gradlew :app:assembleProdDebug` at `cf220061` | SHA-256 `d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67`; cert `c876051e…`; vc11/0.4.6; 6,074,045 B | MEASURED |
| E-21 | Candidate rebuild (clean) | `./gradlew clean :app:assembleProdDebug` again | identical SHA-256 `d7b348a7…` | MEASURED |
| E-22 | Negative control (wrong key) | Fresh clone WITHOUT the project keystore (`~/.android/debug.keystore` default), same commit, `assembleProdDebug` | SHA-256 `e051fec4e1d6bae9211b264a844b192c9ff56003a987c9ff9496f3784bb52fa5`, cert `46488e99…` → hash is keyed to the signing key; E-20 is not an accident | MEASURED |
| E-23 | Candidate manifest facts | `aapt2 dump badging/xmltree` on the `d7b348a7` APK | `package=tw.personal.privateinbox`, vc11/0.4.6; permissions only `POST_NOTIFICATIONS` + AGP `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; `application-debuggable` present; `allowBackup=false`; `dataExtractionRules` present; listener `exported=false` + BIND; `DebugSettingsProvider` exported with signature permission; `usesCleartextTraffic=false` | MEASURED |
| E-24 | CI artifact 13043974395 (IR-12 run) | Downloaded artifact, unzipped, `shasum -a 256` on APK and zip, `apksigner --print-certs` | APK `385d85ba59421806123d1b34d685472df872f30757b58bd4617643766d99c971`, archive `85a0c40e…`, cert `256bb32f8c3be8060c5b1f6390604936b498d2625fad861b63717a17d2abf0f5` | MEASURED |
| E-25 | Staged release APK | `shasum -a 256` on `workspace/releases/v0.4.6/PrivateInboxClassic-v0.4.6-alpha-debug.apk`, `aapt2 dump badging`, `apksigner --print-certs` | `a817434e…` (6,047,517 B), vc11/0.4.6, cert `c876051e…` — **≠ d7b348a7** | MEASURED |
| E-26 | Staged checksum file | `cat SHA256SUMS.txt` in the same directory | `68ed5c1d…` — matches neither `a817434e…` nor `d7b348a7…` | MEASURED |
| E-27 | No release published | `gh release list --repo juhjuhx/PrivateInboxClassic` | only `v0.4.4-alpha` | CI_ARTIFACT_INSPECTED |
| E-28 | Signing material not in Git | `git rev-list --objects --all \| grep -Ei 'keystore\|\.jks\|signing\.properties'` | no hits; `.gitignore` covers `*.keystore`, `keystore/*` | MEASURED |

## 4. Source review

| # | Area | Source read at `cf220061` | Conclusion | Class |
|---|---|---|---|---|
| E-29 | Fail-closed detail read | `InboxDatabase.kt:459-581` `readConversationMessages()` | empty `ReadResult` when no alias and no link; no unconstrained scan; logged with canonical id only (`:502`) | SOURCE_READ |
| E-30 | Membership + cursor parentheses | `InboxDatabase.kt:520-575` | membership = `hex(...) IN (?)… OR EXISTS(...)`, wrapped in `( … )` before `AND (received_at < ? OR (received_at = ? AND id < ?))` | SOURCE_READ |
| E-31 | NUL-safe keys | `LegacyKeyCodec.kt`, `InboxDatabase.kt` membership builder | keys compared via `hex()`, so `\0` cannot truncate a match | SOURCE_READ |
| E-32 | Bounded placeholders | same | one `?` per LEGACY_KEY alias of a single conversation + one link `?` | SOURCE_READ |
| E-33 | Read-model logging | `InboxDatabase.kt:540` `Log.d("READMODEL", "sql=… args=$args")` | logs hex legacy keys (pkg + title) and canonical ids → AGENTS.md rule 4 violation (bodies not logged) = F-05 | SOURCE_READ |
| E-34 | Migrations | `InboxDatabase.kt:94-151` (`onOpen` probe), `:153-…` (`onUpgrade` v2–v6), `ConversationStore.kt:70-229` | additive only; guarded `ALTER TABLE`; v6 createSchema+backfill inside a transaction; repair state reset on failure | SOURCE_READ |
| E-35 | Delete semantics | `InboxDatabase.kt` `deleteMessage(s)`; AUTOINCREMENT on `messages.id` | deletes rows only; orphan `message_conversation` rows remain; ids never reused → no cross-conversation revival | SOURCE_READ |
| E-36 | Merge atomicity | `ConversationStore.kt:364-432` | validate-before-transaction; journal+repoint in one transaction; merged-away `into` not rejected; **no product call site** (`rg '\.merge\('`) | SOURCE_READ |
| E-37 | Unmerge | `ConversationStore.kt:445-498` | restores links from journal, correlated alias UPDATE, clears `merged_into`, one transaction | SOURCE_READ |
| E-38 | Identity resolver | `ConversationIdentityResolver.kt` | shortcut→SHORTCUT alias (adopt) else LEGACY_KEY adopt/new; registers both; no shortcut → TITLE_ONLY ambiguous; never merges on title | SOURCE_READ |
| E-39 | Capture thread | `PrivateNotificationListener.kt:711-748` (`onNotificationPosted` → `capture`), `:848`, `:872-874`, `:1072` `readPictureBytes`; `ImageCapture.kt:150-166` | `createScaledBitmap` + up to 4 JPEG encodes and up to 2 s/file blocking media reads execute on the callback (main) thread = F-02 | SOURCE_READ |
| E-40 | Discriminator | `PrivateNotificationListener.kt:1068-1070`; `git show v0.4.4-alpha:…` | identical in v0.4.4 and v0.4.6 → no upgrade-time fingerprint change; index-based fallback ⇒ shift duplicates = F-03 | MEASURED + SOURCE_READ |
| E-41 | Dedup write path | `InboxDatabase.kt:775-974` `save`/`saveBatches` | HMAC-SHA256 fingerprint over pkg⌗ts⌗key⌗sender⌗body⌗discriminator, `CONFLICT_IGNORE`, batch transaction; seal happens BEFORE insert (comment at `:848` is wrong) | SOURCE_READ |
| E-42 | `existingIdForFingerprint` | `InboxDatabase.kt:895-899` | binds `String(…, ISO_8859_1)` to a BLOB column → never matches → `id=-1` on DUPLICATE (diagnostic only) = F-12 | SOURCE_READ |
| E-43 | Pagination `hasMore` | `InboxDatabase.kt:578-580`, `:715-717`, `:1103-1105` | computed on decrypted output AFTER skipping unreadable rows → early stop when a page contains a failure = F-04 | SOURCE_READ |
| E-44 | `chatHasMore` handling | `MainActivity.kt:1004-1006`, `:1144-1151`, `:1161-1206` | only assigned on a non-empty page; empty completion leaves the affordance on = F-04 | SOURCE_READ |
| E-45 | `deleteMessageNow` gen | `MainActivity.kt:860-883`; `:921-997`; `:1161-1206` | does not bump `chatRequestGen`; re-derived interleavings (delete filters list, older pages are strictly older than the cursor, `chatLoading` gates `showChat`, refresh never advances the cursor) → **no resurrection path found**; conclusion stands, residual cursor/`hasMore` covered by F-04 | SOURCE_READ + INFERRED |
| E-46 | Lock/lifecycle | `MainActivity.kt:223-234` (`onStop`), `:1599`, unconditional `FLAG_SECURE` in `onCreate` | `onStop` clears entries/currentThread, bumps gen, shows lock; `chatEntries` intentionally retained; settings text says **v0.3.1** = F-13 | SOURCE_READ |
| E-47 | Crypto | `CryptoStore.kt` (full) | AES-256-GCM, per-row random 12-byte IV, 128-bit tag, AAD=pkg (images `pkg:image`), Keystore non-exportable keys, `TEMPORARILY_UNAVAILABLE` vs `INVALIDATED` distinguished, nothing auto-deleted | SOURCE_READ |
| E-48 | Debug provider | `DebugSettingsProvider.kt` (full), `src/debug/AndroidManifest.xml` | debug source set only; signature permission; `Binder.getCallingUid()` + same-signer; `postSelfTest` and `ensureListener` are real control surfaces, reachable only by same-signed UIDs | SOURCE_READ |
| E-49 | Export | `DhcExportService.kt`, `DhcExporter.kt`, `MainActivity.kt:1369-1470` | PBKDF2-HMAC-SHA256 210k, AES-256-GCM 12-byte nonce, passphrase zeroed, SAF, no network; **export only, no import** | SOURCE_READ |
| E-50 | Build config | `app/build.gradle.kts` | `applicationId=tw.personal.privateinbox`, vc11/0.4.6, min 28 / compile+target 36, `prod`/`devtest` flavors, release signing null → unsigned release, debug keystore from `keystore/signing.properties` or `-P` | SOURCE_READ |
| E-51 | Release scripts | `scripts/release/verify-artifact.sh`, `scripts/audit-release.sh` | verify-artifact checks sha256, cert, package, permissions, INTERNET absence, installed-signature compatibility and exits nonzero; audit-release checks packaged APKs for forbidden perms/provider/backup rules | SOURCE_READ |

## 5. Tests reviewed

| # | File | Observation | Class |
|---|---|---|---|
| E-52 | `IR03NulSafeLinking…` | 6 tests; real NUL keys in `conversation_key`; asserts NEW row real id | SOURCE_READ |
| E-53 | `IR05SchemaRepair…` | 6 tests; failed repair not remembered; messages preserved | SOURCE_READ |
| E-54 | `ConversationMerge…` | 7+ tests; sealed content byte-identical across merge; alias ownership restored; cross-package rejected | SOURCE_READ |
| E-55 | `IR11…InstrumentedTest.kt:686-689` | `lockRejectsInFlightChatCallback` reflectively sets `unlocked=false` and calls `showLock()` — **not** the real `onStop()` | SOURCE_READ |
| E-56 | `IR11…InstrumentedTest.kt:477` | `dbQueryFailureDoesNotLeakOtherConversation` injects no failure | SOURCE_READ |
| E-57 | `FingerprintCompatibility…` | real `save()` NEW/DUPLICATE assertions; renaming creates a new row (documented) | SOURCE_READ |

## 6. Documents reviewed (treated as claims)

| # | Document | Claim reviewed | Class |
|---|---|---|---|
| E-58 | `docs/verification/v0.4.6/IR12-APK-UPGRADE-EVIDENCE.md` | CI artifact `13043974395` `385d85ba`/`256bb32f` ≠ candidate `d7b348a7`/`c876051e`; API33 upgrade vc10→11, 120-byte ciphertext unchanged, survives restart; “does not authorise publishing” | DOC_READ; binary part re-measured (E-24), runtime part **not** re-executed |
| E-59 | `docs/verification/v0.4.6/SIGNING-IDENTITY-AUDIT.md` | v0.4.4 `6c6def17`/`c876051e`; keystore `.android-home` = `c876051e`, `~/.android` = `46488e99`; §5 admits the earlier test only moved a marker file; §8 CI artifact identity | DOC_READ; identity part re-measured (E-19, E-22) |
| E-60 | `docs/verification/v0.4.6/RELEASE-BASELINE-TRUTH.md` | HEAD `79f2942`, 290/290, 157/157, `d7b348a7`/`c876051e` | DOC_READ; all re-verified (E-04, E-12, E-13, E-20) |
| E-61 | `docs/releases/v0.4.6-ALPHA-CLOSURE.md` | `EVIDENCE_REPAIRED`; limitations do **not** mention debug→production key loss, `debuggable`, or missing restore; §14 “Backup Restore” refers to repo snapshots | DOC_READ |
| E-62 | `docs/handoff/current/IR12-REVIEW-PACKAGE.md` | artifact table + CI-artifact warning | DOC_READ |
| E-63 | `docs/verification/v0.4.6/FINAL-TEST-MATRIX.md` | 290/290, 157/157 | DOC_READ; matches E-12/E-13 |
| E-64 | `docs/handoff/current/AGENT_STATE.md:9` | “239 run / 1 flaky” — stale | DOC_READ |
| E-65 | `README.md:286-288` | 126 instrumentation / 84 unit — stale | DOC_READ |
| E-66 | `README.zh-TW.md:176-182`, `:226-239` | claims “no export / no pagination / key loss only skips rows” — contradicted by code (E-43/E-44/E-49) = F-07; release table still only v0.4.4 | DOC_READ |
| E-67 | PR #4 body | says CI ran on `c6d9b1a`; actual run is on `cf220061`; mixes the superseded marker-only test with IR-12 = F-14 | CI_ARTIFACT_INSPECTED |

## 7. Things this review did **not** do (explicit limits)

| # | Not done | Why | Consequence for the report |
|---|---|---|---|
| L-01 | Did not re-run the emulator upgrade test | No isolated emulator upgrade session was executed | `ENCRYPTED_UPGRADE_EVIDENCE=EVIDENCE_CHAIN_REVIEWED`; `UPGRADE_INDEPENDENTLY_REPRODUCED=NO` |
| L-02 | Did not run instrumentation/JVM locally | Relied on CI reports | `INSTRUMENTATION`/`JVM` evidence = `CI_ARTIFACT_INSPECTED`, not local |
| L-03 | Did not test on a physical device or OEM variant | Out of scope | `REAL_DEVICE_OEM=NOT_VERIFIED` |
| L-04 | Did not create, rotate or use a release keystore | Prohibited | Release signing remains `UNVERIFIED`; release APK is unsigned by design |
| L-05 | Did not publish anything to GitHub | Account is the PR author; no authorization to post | `FORMAL_GITHUB_APPROVAL=PENDING`; report is local only |
| L-06 | Did not touch `v045-reliability` or any dirty worktree | Prohibited | Original workspace verified clean before/after (E-07) |
| L-07 | Did not fix the findings | Prohibited in this round | Report only; minimal fixes described |

---

## 8. Delta re-review — `cf220061 → d376f40` (2026-11-01)

All rows below were produced in the corrective round. Review clone re-checked out at `d376f40`.

| # | Check | Command / source | Result | Class |
|---|---|---|---|---|
| E-70 | Corrective commit(s) | `git log --oneline cf220061..origin/v046-integration-repair-final` | exactly one: `d376f40 fix(F-05,F-01,F-06,F-07)…` | MEASURED |
| E-71 | Diff scope | `git diff --stat cf220061 d376f40` | 10 files, +620/−23; 2 product files, 1 new androidTest, 2 new docs, 2 new evidence XMLs | MEASURED |
| E-72 | Whitespace hygiene | `git diff --check cf220061 d376f40` | 1 warning: trailing whitespace, `docs/releases/v0.4.6-ALPHA-CLOSURE.md:50` | MEASURED |
| E-73 | Product change content | `git diff … InboxDatabase.kt MainActivity.kt` | 3 log argument-lists only; +6-line `@VisibleForTesting internal fun loadOlderForTest()` | SOURCE_READ |
| E-74 | Remaining log sites in main sources | `grep -rn --include=*.kt 'Log\.(d\|i\|w\|e\|v)\(' app/src/main/java` | 9 total: 5 `MainActivity`, 3 `InboxDatabase`, 1 `DestructiveOperationGuard`; none interpolates title/key/id/SQL/args | SOURCE_READ |
| E-75 | Staged APK SHA-256 | `shasum -a 256 …/v0.4.6/PrivateInboxClassic-v0.4.6-alpha-debug.apk` | `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7` | MEASURED |
| E-76 | Staging checksum | `shasum -a 256 -c SHA256SUMS.txt` | `OK`, exit 0 | MEASURED |
| E-77 | Signature + cert | `apksigner verify --verbose --print-certs <staged>` | `Verifies`, v2 true, cert `c876051e…` | MEASURED |
| E-78 | Badging / permissions / flags | `aapt2 dump badging|permissions|xmltree` | `tw.personal.privateinbox` 11/0.4.6; no INTERNET; `debuggable=true`, `allowBackup=false`, `cleartext=false` | MEASURED |
| E-79 | APK contains the F-05 fix | `unzip classes*.dex; strings …` | new strings present; `fail-closed: unknown canonicalId=`, `sql=`, `args=` absent | MEASURED |
| E-80 | Reproducibility | `./gradlew :app:assembleProdDebug --rerun-tasks -Pdebug.storeFile=…/.android-home/debug.keystore …` at `d376f40` | rebuilt APK = `b68fd821…` **identical** (7,428,285-byte build tree; 6,074,285-byte APK) | MEASURED |
| E-81 | Old binary isolated | `find releases -name '*.apk' -exec shasum` | `a817434e…` only under `_backup-pre-ir12-fix/`; its sums file records `68ed5c1d…` | MEASURED |
| E-82 | Release script | `verify-artifact.sh <apk> b68fd821…` | exit 0, `ALL CHECKS PASSED` | MEASURED |
| E-83 | Script negative test (SHA) | same with `d7b348a7…` | `FAIL: SHA-256 mismatch`, non-zero | MEASURED |
| E-84 | Script negative test (cert) | same with a different-cert APK | passes — no cert expectation exists (D-06) | MEASURED |
| E-85 | CI run identity | `gh api …/runs/41231048769` | `head_sha=d376f40…`, `conclusion=success`, `event=pull_request` | CI_INSPECTED |
| E-86 | CI steps | `…/runs/41231048769/jobs` | one job, 28 steps, all `success` (build, lint, 3 audits, DHC ×3, unit, emulator, uploads) | CI_INSPECTED |
| E-87 | Instrumentation count | `gh run view 41231048769 --log` | `Starting 291 tests on emulator-5554 - 16` … `Finished 291 tests …`; every progress line `(0 skipped) (0 failed)` | CI_INSPECTED |
| E-88 | JVM count | `test-reports` artifact, `tests/testProdDebugUnitTest/index.html` | `157 tests`, `0 failures`, `0 ignored` | CI_INSPECTED |
| E-89 | Instrumentation XML availability | artifact listing | **not published** — only `testProdDebugUnitTest` HTML; corrects the earlier ledger's E-12 wording (D-07) | CI_INSPECTED |
| E-90 | Test-count delta | `git grep -c '@Test' <rev> -- 'app/src/androidTest/**/*.kt'` | 290 at `cf220061`, **291** at `d376f40`; run 41204287676 (`c6d9b1a`) also 290 | MEASURED |
| E-91 | CI artifact is not the release asset | `gh run download 41231048769 -n PrivateInboxClassic-debug-apk` | SHA `ff516433…`, cert `4def3c89…`; not present in the release dir | MEASURED |
| E-92 | Old HEAD cannot produce the artifact | `git show 79f2942:.../InboxDatabase.kt \| grep` | `no alias and no link` → 0 hits; `sql=` → 1 hit | MEASURED |
| E-93 | PR review state | `gh api …/pulls/4/reviews` | empty; PR `draft=true`, `mergeable=true`; body reviewed | CI_INSPECTED |
| E-94 | Resolution matrix coverage | `grep '^### F-' docs/verification/v0.4.6/CLAUDE-FINDING-RESOLUTION-MATRIX.md` | F-01,05,06,07,02,04 + F-03 (as P3), F-08, F-09 = 9 of 14; F-10…F-14 absent | DOC_READ |
| E-95 | Deferred items in backlog | `docs/roadmap/v0.4.7-BACKLOG.md` | contains no F-IDs and none of F-02/F-03/F-04/F-08/F-09 | DOC_READ |

**Delta limits:** no emulator upgrade session was executed (`ENCRYPTED_UPGRADE=EVIDENCE_CHAIN_REVIEWED`);
no instrumentation suite was run locally; nothing was posted to GitHub; the repository was not modified.
The rebuild in E-80 overwrote only `[臨時工作樹]/app/build/outputs/…`, never the staged artifact.
