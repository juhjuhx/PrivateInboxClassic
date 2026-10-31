# Private Inbox Classic v0.4.6 — Final Handoff

**Status:** EVIDENCE_REPAIRED — PENDING INDEPENDENT REVIEW SIGN-OFF  
**Branch:** `v046-integration-repair-final`  
**Code HEAD:** `79f2942059a815d275403d924be1c1e25024d786` (product code + tests)  
**Documentation HEAD:** `ecadb9337426cf9be27d5197d038aba2d201ee71` (docs-only commits on top)  
**PR:** https://github.com/juhjuhx/PrivateInboxClassic/pull/4  
**CI:** [run 41013529150](https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41013529150) — success, 290/290  
**Working tree:** clean on this branch

---

## Verified Test Results (Fresh, after IR remediation)

| Suite | Result |
|-------|--------|
| Full instrumentation | **290 / 290 PASS** (0 skipped, 0 failed) |
| IR-11 regression | **11 / 11 PASS** |
| JVM unit tests | **157 / 157 PASS** |
| Android Lint | PASS |
| Security audits | PASS (secrets+history, test isolation 50 files) |
| Release audit / version regression | PASS / PASS |
| DHC connector | PASS |
| Lint | PASS |
| Build (prod + devtest) | PASS |

## Artifacts

| Artifact | SHA-256 |
|----------|---------|
| PrivateInboxClassic-v0.4.6-alpha-debug.apk | `b68fd8210e7ea43c12fe4818459dbafb9bb53cc31162d475b9926e30353996a7` |
| Signing cert | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` (keystore `.android-home/debug.keystore`) |

> Superseded: this table previously recorded APK
> `a817434edefcb8f5becec48b4ab46ba91badc80bed2b294c0355247c1d6a653c`, which is
> not reproducible from source HEAD `79f2942`. The measured value replaces it.
> See `docs/verification/v0.4.6/SIGNING-IDENTITY-AUDIT.md`.

## Upgrade Compatibility
**VERIFIED (emulator API 33)** — public `v0.4.4-alpha` (`6c6def17…`, cert
`c876051e…`) → v0.4.6 (`d7b348a7…`, cert `c876051e…`) via `adb install -r`.
versionCode 10 → 11; app data dir, marker file and `shared_prefs/` preserved;
package signature `[a0e2e7cb]` unchanged; new build launches with no crash.

**Limitation:** the test preserved a marker file + prefs, not a populated
encrypted message table (no messaging package on the emulator, so the capture
whitelist correctly refused synthetic notifications). Encrypted-row survival is
inferred from the intact data directory, not directly observed.

## Flake status — CORRECTED
The `SystemNotificationE2E` "FLAKY_UNRESOLVED" classification was **wrong**.
`DebugSettingsProvider.call()` had no `postSelfTest` branch, so the test always
threw "the app failed to post"; CI masked it with `|| true`. IR-06 implements the
provider methods and removes `|| true`. The suite now passes for real (265/265)
and CI is authoritative. See `IR-REMEDIATION-EVIDENCE.md` and the correction
header in `FLAKE-ANALYSIS.md`.

## Release Gate Status
| Gate | Status |
|------|--------|
| Clean integration branch | PASS |
| Full diff security review | SELF_REVIEW_ONLY (not independent sign-off) |
| CI / Build / Migration / DHC | PASS |
| APK signing verified | PASS — cert `c876051e…`, matches public v0.4.4-alpha |
| Public upgrade compatibility | VERIFIED (emulator API 33, data preserved) |
| Encrypted message-table readability after upgrade | **VERIFIED on the F-05-fixed APK** (API 33: decrypted, displayed, and again after restart) — `IR12-APK-UPGRADE-EVIDENCE.md` |
| F-05 privacy logs | **FIXED** — READMODEL no longer logs SQL/args/titles/keys/ids; regression test fails if re-introduced |
| F-01 release staging | **FIXED** — staging APK replaced with the F-05-fixed build, sums regenerated and verified |
| F-06/F-07 disclosure | **FIXED** — release notes created; README known-issues rewritten |
| Unresolved flake disclosed | Historical FLAKY_UNRESOLVED record; not observed in run 41013529150 |
| IR remediation (IR-01…IR-11) | PASS — evidence in IR-REMEDIATION-EVIDENCE.md + SIGNING-IDENTITY-AUDIT.md |
| Independent review (2nd reviewer) | **PENDING — blocks publication** |
| Release keystore | NOT EXISTS (BLOCKS production release) |

**Cannot publish Alpha Release until:** a reviewer who did not implement IR-11
submits a formal GitHub review on PR #4. The previously-cited flake blocker no
longer applies (it was a missing implementation, fixed by IR-06).

**Upgrade evidence (IR-12):** a genuine encrypted message was captured by the
v0.4.4-alpha install (stored as ciphertext only — the plaintext marker appears
nowhere in the database file), then the candidate was installed over it with
`adb install -r`. versionCode 10 → 11, package signature unchanged, data and
prefs preserved, ciphertext byte-identical, and the upgraded app **decrypted and
displayed** the message — verified again after a force-stop and relaunch.
Encrypted message-table readability is therefore **VERIFIED**, not merely
inferred. Details: `docs/verification/v0.4.6/IR12-APK-UPGRADE-EVIDENCE.md`.

---

## IR-11 — chat detail pagination safety (source HEAD `79f2942`)

| Item | Change |
|---|---|
| **IR-11A** Conversation state isolation | `chatConversationId` resets `chatEntries`/`chatCursor`/`chatHasMore`/`chatPageSize`/`chatLoading` on switch; A→B→A leaks nothing |
| **IR-11B** Cursor ordering | full `(received_at, id)` keyset cursor, not `received_at` alone |
| **IR-11C** Async fetch/render separation | SQLite + AES on the IO executor; `renderChatPage()` UI-thread only |
| **IR-11D** Corrective patch | `chatRequestGen` invalidates stale callbacks; stale generation cannot clear the newer request's `chatLoading`; `refreshChat()` is render-only (photo return / delete never advance the cursor); lock/`onStop` invalidate in-flight work; delete failure reported honestly |

### IR-11 regression coverage — 11 tests, all PASS

`IR11NavigationAndPaginationSafetyInstrumentedTest`:

1. `conversationIsolationAtoBtoA`
2. `cursorOrderingSameTimestamp`
3. `photoPreviewReturnPreservesHistory`
4. `deleteMessageShowsCorrectCount`
5. `pagination350and650`
6. `switchConversationDuringAsyncLoad`
7. `dbQueryFailureDoesNotLeakOtherConversation`
8. `photoReturnPreservesHistoryAndCursor`
9. `deleteRemovesMessageFromChatPage`
10. `staleCallbackDoesNotClearNewRequestLoading`
11. `lockRejectsInFlightChatCallback`

### Full instrumentation — 290 / 290 PASS

GitHub Actions run [41011478801](https://github.com/juhjuhx/PrivateInboxClassic/actions/runs/41011478801),
emulator API 36, 0 skipped, 0 failed. Reproduced locally (`OK (290 tests)`).

## Review state

- **No independent sign-off yet.** The PR comment at
  [#4 (comment)](https://github.com/juhjuhx/PrivateInboxClassic/pull/4#issuecomment-6515429268)
  was written by the same agent that implemented IR-11, so it is **not** an
  arm's-length review of IR-11 and must not be counted as APPROVED.
- GitHub rejects self-review (`Can not request changes on your own pull request`),
  which is why it is a comment rather than a formal review.
- Remaining blockers: no release keystore (Debug Alpha only), no independent
  reviewer, no physical-device/OEM verification.
