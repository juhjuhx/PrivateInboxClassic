# Private Inbox Classic v0.4.6 — Independent Forensic Release Review

**Reviewer:** Claude Code (independent audit; not an author of IR-01…IR-12)
**Date:** 2026-11-01
**Repository:** https://github.com/juhjuhx/PrivateInboxClassic
**Pull request:** https://github.com/juhjuhx/PrivateInboxClassic/pull/4 (Draft, 0 reviews)
**Review HEAD:** `cf220061bbfef022a3aa6c935f3c1e5bf57d1691`
**Product-code HEAD:** `79f2942059a815d275403d924be1c1e25024d786`
**Baseline:** `3d56a7f5` (49 commits behind HEAD; 106 files, +17,245 / −172)
**CI run:** `41219559793` (`head_sha = cf220061…`, `conclusion = success`)
**IR-12 CI run:** `41204287676` (`head_sha = c6d9b1a…`, `conclusion = success`, artifact `13043974395`)
**Workspace:** isolated clone `[臨時審查目錄]/repo`; no product file was modified; no commit, push, release, merge, APK upload or keystore operation was performed.

**Independence.** The GitHub account logged in for this session is `juhjuhx`, which is the PR author. This report is therefore an *independent technical audit*, not a GitHub formal approval. It was not posted to GitHub. `FORMAL_GITHUB_APPROVAL` remains `PENDING`.

**中文摘要**
- 基線與 CI 已獨立核對：HEAD `cf220061`、PR 仍是 Draft、0 個正式 Review；CI 真的跑在 `cf220061`，instrumentation 290/290、JVM 157/157、0 skipped。
- 公開 v0.4.4 與候選 v0.4.6 二進位身分已親自量測；候選 `d7b348a7…` 在本機以專案 Keystore 乾淨重建得到一模一樣的 SHA-256 與憑證 `c876051e…`。
- 程式碼層面未發現跨聊天室讀取、跨套件外洩或可觸發的資料毀損；`readConversationMessages()` fail-closed、NUL/hex、括號與游標條件正確；`deleteMessageNow()` 不遞增 `chatRequestGen` 的既有「安全」結論，經事件序分析後成立（未找到復活路徑）。
- 找到 1 個 P1：release staging 目錄中的 `v0.4.6-alpha-debug.apk` 不是已驗證的候選檔（`a817434e…`），且同目錄的 `SHA256SUMS.txt` 寫的是第三個雜湊（`68ed5c1d…`）。照現狀上傳就會發布未經審查的二進位。
- 另有 6 個 P2（主執行緒圖片編碼/讀取、無時間戳通知的 index-shift 重複列、分頁 `hasMore` 在解密失敗時提早結束與卡住、Logcat 洩漏對話標題/legacy key、debug→production 簽章與可除錯建置的揭露缺口、README 已知問題與實際行為矛盾）。
- **技術結論：`CHANGES_REQUESTED`。** 不可在修正 P1 與揭露缺口前發布；即使修正，也仍缺真正獨立的人類 Reviewer 正式簽核。

---

## A. Executive Verdict

| Item | Value |
|---|---|
| Review HEAD | `cf220061` — verified equal to PR `headRefOid` |
| Product code | `79f2942`; `git diff --name-only 79f2942 cf220061` yields **only `docs/` paths** (15 files) — no product, build or CI change after the code HEAD |
| Baseline | `3d56a7f5` is an ancestor of HEAD; PR base is `ff1091fb` (also an ancestor) |
| CI | Run `41219559793` on `cf220061`; job "Build, test and audit" SUCCESS; instrumentation step authoritative (no `|| true`) |
| Instrumentation | **290 / 290**, 0 failed, 0 skipped — CI test-report XML inspected, not inferred from the green check |
| JVM | **157 / 157**, 0 failed, 0 skipped — CI HTML report inspected |
| Lint / audits | Lint, `audit.sh`, `audit-secrets.sh`, `audit-test-isolation.py`, DHC Python tests/contract, `audit-release.sh` all ran and passed in CI |
| Candidate APK | **`d7b348a7…`**, cert **`c876051e…`**, versionCode 11 / 0.4.6 — **independently rebuilt twice** from `cf220061` with the project keystore |
| Public v0.4.4 | **`6c6def17…`**, cert **`c876051e…`**, vc10 — independently downloaded and measured |
| Encrypted upgrade | `EVIDENCE_CHAIN_REVIEWED` (not re-executed on an emulator) |
| Findings | **P0 = 0, P1 = 1, P2 = 6, P3 = 7** |
| Technical verdict | **`CHANGES_REQUESTED`** |
| Release recommendation | **NOT ready** to publish as a public Experimental Debug Alpha |

No P0 was found. There **is** one confirmed release-blocking finding (F-01), so the statement
`NO_CONFIRMED_RELEASE_BLOCKING_FINDINGS` is **not** applicable to this review.

The one P1 is not a product-code defect; it is a release-artifact integrity failure in the staging
directory. The product itself (within the demonstrated scope) is close to releasable: the isolation
SQL, fail-closed behaviour, crypto, manifest and migration logic held up under adversarial reading.
Publication is additionally gated by the absence of an arm's-length formal review (this session's
account is the PR author).

---

## B. Code Review

### B.1 Conversation identity and read model

Reviewed `ConversationIdentityResolver.kt`, `ConversationStore.kt`, `InboxDatabase.kt`
(`readConversationMessages`, `readMessagesByLegacyKey`, `conversationPage`, `searchMessages`),
`LegacyKeyCodec.kt`.

**Verified correct (adversarial reading):**

1. **Fail-closed.** `readConversationMessages()` (`InboxDatabase.kt:459-581`) returns an empty
   `ReadResult` when the conversation owns neither a LEGACY_KEY alias nor any message link
   (`hasAnyLink == false` and no key hexes). It never falls through to an unconstrained table scan.
   The empty result is logged with the canonical id only.
2. **Membership is alias ∪ link.** Legacy-key membership is `hex(conversation_key) IN (?,…)`;
   linked membership is `EXISTS (SELECT 1 FROM message_conversation mc WHERE mc.message_id =
   messages.id AND mc.conversation_id = ?)`. When both exist the selection is
   `A OR B`, and when a cursor is present the whole membership is parenthesised before
   `AND (received_at < ? OR (received_at = ? AND id < ?))` (`:557-572`). The cursor predicate
   therefore cannot escape the membership restriction. Verified by reading the assembled SQL, not
   by trusting the test name.
3. **NUL-safe.** Keys are compared as `hex()` values; the NUL separators in
   `pkg\0(shortcut?)\0title` cannot truncate a match. Binding a raw String to a BLOB would have
   been unsafe — the code does not do that on the read path.
4. **Bounded placeholders.** Only the LEGACY_KEY aliases of one conversation become `?`
   parameters (one per rename), plus one link parameter. No unbounded `IN` list.
5. **Cross-package isolation.** Alias lookups are `(pkg, type, value)` scoped; message rows carry
   `package_name`; merge across packages is rejected. No path was found that can return a row of
   package A in a conversation of package B.
6. **`ORDER BY received_at DESC, id DESC` keyset.** Both the conversation page (`:319-433`, after
   grouping/HAVING) and the detail read use the full pair. AUTOINCREMENT on `messages.id`
   guarantees ids are never reused, so an orphaned link can never point at a recycled row.

**Verified safe, contrary to a plausible suspicion:** `deleteMessageNow()` (`MainActivity.kt:860-883`)
does **not** increment `chatRequestGen`. I re-derived the interleavings rather than accepting the
previous reviewer's conclusion:

- `loadOlderChatMessages()` fetches strictly older than `chatCursor`; a message the user can delete
  is already loaded and therefore newer than the cursor, so it cannot appear in the older page.
- `showChat()` is guarded by `chatLoading`; delete does not start a competing load.
- `refreshChat()` is render-only and never advances the cursor.
- The delete callback filters `chatEntries`; a later older-page callback cannot re-add the deleted
  id from the database because that page excludes it.

No event order was found that resurrects a deleted message. Residual (non-security) issue: the
cursor may still point at the deleted row and `chatHasMore` is not recomputed — see F-04.

### B.2 SQLite migrations

`onCreate` (six canonical tables), `onUpgrade` v2→v6 (guarded additive `ALTER TABLE`, v6
`createSchema` + `backfillAliases`), `onOpen` repair probe (`InboxDatabase.kt:94-151`),
`ConversationStore.createSchema` (`:70-141`), `backfillAliases` (`:157-229`).

- No `DROP`, `DELETE` or table rebuild in any migration. All v2–v5 changes are `ALTER TABLE … ADD
  COLUMN` behind `columnExists`.
- `onOpen` only runs `createSchema`/`backfillAliases` when the canonical table count is < 6, and
  resets the repair state on failure so the next open retries (F-05 test covers a failed repair).
- `provenance` was already present in the initial canonical-layer commit (`99bb5bd`), so the
  6-table fast probe cannot skip a later-added column in any released path.
- `onConfigure` sets `secure_delete=ON` and `busy_timeout=5000`.
- Residual: if a DB already has all six tables but *no aliases* (only reachable via a non-released
  intermediate build), the probe skips backfill; detail reads still fall back to
  `readMessagesByLegacyKey`, so no message becomes unreadable. Not a finding.

### B.3 Merge / unmerge

`ConversationStore.merge` (`:364-432`) and `unmerge` (`:445-498`).

- `merge` validates identity (self, cross-package, cycle via `wouldCycle`, merged-away target)
  **before** opening the transaction; inside, it journals message links and alias ownership, logs
  the merge, then repoints links, aliases and `merged_into` in one transaction.
- `unmerge` restores links from journal rows and restores alias ownership with a correlated
  `UPDATE`, then clears `merged_into`; single transaction.
- **No call sites exist in product code** (`rg '\.merge\('` finds none; only the definition and
  tests). Merge/unmerge are therefore unreachable in v0.4.6 — latent, not live.
- Latent defects if it is ever wired to UI: (a) validation outside the transaction is a TOCTOU
  window; (b) `merge(A → B)` where B is itself merged away is not rejected, and reads use the raw
  `conversation_id`, so A's messages would become invisible under B's target. See F-09.

### B.4 Capture pipeline and dedup

`PrivateNotificationListener.kt` capture path (`:755-1000`), `previews()` (`:1096-1160`),
`discriminatorFor` (`:1068-1070`), `save()`/`saveBatches()` (`InboxDatabase.kt:775-974`).

- Sources are gated by `CapturePolicy`/`SourceRegistry`; group summaries and disallowed packages
  are skipped; the notification window is the full list (bounded at 500 with a recorded count);
  `MAX_PREVIEWS` does not shift the index used for the discriminator.
- Fingerprint = HMAC-SHA256 over `pkg \0 sourceTs \0 conversationKey \0 sender \0 body \0
  discriminator`; `CONFLICT_IGNORE` on the unique fingerprint. `saveBatches` runs one transaction.
- **Compatibility with v0.4.4 verified:** `git show v0.4.4-alpha:…PrivateNotificationListener.kt`
  shows the identical `discriminatorFor` format (`ts:…` / `c:…:i…`), and v0.4.4 already had
  `messageDiscriminator`. An in-place upgrade therefore does **not** change existing fingerprints
  and does not create an upgrade-time duplicate storm. (This was worth verifying because the
  `fingerprintInput` KDoc discusses the historical empty-discriminator format.)
- Retry: failed rows are carried forward with a bounded retry counter; a mixed
  `[NEW, FAILURE, NEW]` batch retries only the failed subset. Process death still loses the
  in-memory queue (disclosed limitation).
- **Defect:** for timestamp-less previews the discriminator uses the list index
  (`:1070`). A platform window shift changes that index and produces a new fingerprint → a
  duplicate row. See F-03.
- **Defect:** image work runs on the notification callback thread. See F-02.

### B.5 UI / async lifecycle

`MainActivity.kt` chat state machine (`:921-1210`), `onStop` (`:223-234`), `renderChatPage`
(`:1004-1136`), `loadOlderChatMessages` (`:1161-1206`).

- Generation invalidation: `showChat`/`loadOlder` capture `++chatRequestGen`; callbacks check
  generation, `isFinishing`, `unlocked`, `page == "chat"` and `currentThread == key` before
  rendering (`:976-996`, `:1186-1204`). Stale generation cannot clear the newer request's loading.
- `onStop()` sets `unlocked=false`, clears the inbox list and current thread, bumps the generation
  and shows the lock screen; it intentionally keeps `chatEntries` for resume. `FLAG_SECURE` is set
  unconditionally in `onCreate`.
- `refreshChat()` is render-only; photo return and delete never advance the cursor.
- **Defect:** `renderChatPage` only assigns `chatHasMore` when the incoming page is non-empty
  (`:1006`); an empty page leaves the old value, so a finished (or all-undecryptable) page can leave
  the “load older” affordance permanently on. Combined with the post-decrypt `hasMore` computation
  this is F-04.

### B.6 Other code

- `existingIdForFingerprint` binds `String(fingerprint, ISO_8859_1)` to a BLOB column
  (`InboxDatabase.kt:895-899`); SQLite never matches, so DUPLICATE results carry `id = -1`. It is
  used only for reporting — NEW rows link by their real id — so this is a dead diagnostic path,
  not a data bug. F-12.
- `save()`’s comment “The duplicate check comes BEFORE the encryption” (`:848`) is false in the
  code: sealing happens before `insertWithOnConflict`. The amplification is actually removed in the
  listener, not here. Comment-only inaccuracy; noted under F-12-family (no separate ID).
- `DebugSettingsProvider` (debug source set only) uses a signature permission plus
  `Binder.getCallingUid()` and same-signer verification; it exposes settings/counters, and
  `postSelfTest` is a real control surface but is reachable only by a same-signed caller.
- `DhcExportService`/`DhcExporter`: PBKDF2-HMAC-SHA256 (210,000), AES-256-GCM, 12-byte random
  nonce, per-bundle salt, passphrase zeroed after use, SAF destination, no network. Export is
  one-way (the desktop DHC importer consumes it); there is no in-app restore.

---

## C. Security & Privacy

| Control | Result | Evidence |
|---|---|---|
| No `INTERNET` | PASS | Packaged candidate manifest: only `POST_NOTIFICATIONS` and the AGP-generated non-exported-receiver permission |
| No analytics/tracking | PASS | Only `androidx.activity` runtime dependency |
| Listener not over-exported | PASS | `exported=false` + `BIND_NOTIFICATION_LISTENER_SERVICE` |
| Debug provider debug-only | PASS | Declared in `src/debug/AndroidManifest.xml`; absent from release source set |
| Debug provider protection | PASS | Signature-level permission + runtime calling-UID/same-signer check |
| Backup / extraction | PASS | `allowBackup=false`, `fullBackupContent=false`, extraction rules exclude the DB and prefs |
| `FLAG_SECURE` | PASS (code) | Unconditional in `MainActivity.onCreate`; not removable by a build flag |
| Crypto | PASS | AES-256-GCM, random 12-byte IV stored per row, 128-bit tag, AAD = package (images: `pkg:image`), non-exportable Keystore key, HMAC fingerprints |
| Key-state handling | PASS | Distinguishes `TEMPORARILY_UNAVAILABLE` from `INVALIDATED`; nothing is auto-deleted; an explicit confirmation is required to clear |
| No plaintext bodies in logs | PASS | No `Log` statement includes `body`, `title` or ciphertext plaintext; diagnostics store stage codes/counters |
| No conversation metadata in logs | **FAIL** | `READMODEL` logs the SQL argument list, which contains hex-encoded legacy keys (package + conversation title) and canonical ids — F-05 |
| Signing material not in Git | PASS | No `*.keystore` in `git rev-list --objects --all`; `.gitignore` covers `*.keystore` and `keystore/*` |

Threat-model notes for a **debug** alpha (disclosure, not code defects):
the shipped artifact has `android:debuggable=true`, so a user who enables USB debugging can use
`adb`/`run-as` to read the app's private directory (encrypted rows and prefs; the Keystore key
material itself is not exportable). The alpha's update trust is bound to a *debug* certificate;
anyone who obtains that keystore file could sign an update Android would accept. Both must be in
the release notes (F-06).

---

## D. Data Migration

| Item | Result |
|---|---|
| v1→v6 schema upgrade is additive | PASS — code review; no destructive DDL/DML |
| Canonical layer on an existing v0.4.4 DB | PASS (code) — `onOpen` backfill + `reconcilePendingLinks`; legacy detail fallback exists |
| One-shot repair durability | PASS (code) — AtomicInteger state reset on failure; retried on next open |
| Merge/unmerge atomicity | PASS (code, unreachable in v0.4.6) |
| Ciphertext preservation across upgrade | `EVIDENCE_CHAIN_REVIEWED` — IR-12 asserts byte-identical ciphertext; not re-executed here |
| Upgrade from public v0.4.4 to candidate | Signature-compatible (same cert `c876051e…`, vc10→11); candidate binary identity independently reproduced |
| Future production-key migration | **FAIL (disclosure)** — a differently signed production build cannot update the debug install; uninstall → all archived data lost; export exists but there is no in-app restore (F-06) |

The IR-12 evidence chain is internally consistent and its binary identity checks out against my own
rebuild. The weakest link is representational, not substantive: it records the runtime signature as
`dumpsys … [a0e2e7cb]`, which is a platform hash, not the certificate SHA-256. The certificate
identity is nevertheless independently established here from the APK itself, so the chain is
acceptable as `EVIDENCE_CHAIN_REVIEWED`. It is **not** `INDEPENDENTLY_REPRODUCED`: I did not run
the emulator upgrade.

---

## E. Runtime & Tests

- **CI is genuine.** Run `41219559793` has `head_sha = cf220061…`; the instrumentation step runs
  `:app:connectedDevtestDebugAndroidTest` with no `|| true` and no `continue-on-error`; the emulator
  action started; the test-report artifact contains **290 started / 290 finished**, 0 skipped,
  0 failed; JVM report **157/157**. The Android emulator genuinely ran (job log).
- **Tests are non-vacuous on the core paths.** IR-02 (fail-closed), IR-03 (NUL-safe linking, real
  row ids), IR-05 (repair, including a failed repair not remembered as success), merge tests
  (byte-identical sealed content before/after, alias ownership restoration), IR-10/11 pagination
  and generation tests all assert observable database/UI state.
- **Test-effectiveness gaps (P3):**
  - `lockRejectsInFlightChatCallback` (`IR11…:686-689`) does not exercise the real `onStop()`;
    it reflectively sets `unlocked=false` and calls `showLock()`. The production lifecycle path is
    only covered indirectly.
  - `dbQueryFailureDoesNotLeakOtherConversation` (`IR11…:477`) does not inject a database failure,
    so it asserts the happy path of a test named for an error path.
  - Test hooks (`openThreadForTest`, `chatStateForTest`, reflective `unlocked`) bypass the unlock
    path; acceptable for isolation, but they mean “test passed” is not “product path proven”.
  - Delete tests do not race deletion against an in-flight older-page load.
- **Not re-executed locally:** no emulator run was performed in this review. Instrumentation/JVM
  evidence is `CI_ARTIFACT_INSPECTED`. Upgrade evidence is `EVIDENCE_CHAIN_REVIEWED`.
- **CI hygiene:** the instrumentation step is authoritative; the release-build step is masked with
  `|| echo …` (`.github/workflows/android.yml:110`), so a compile error in `assembleProdRelease`
  would not fail CI (F-10). `sdkmanager --licenses || true` is benign.

---

## F. APK Identity

All hashes below were measured in this review unless marked otherwise.

| Artifact | SHA-256 | Certificate SHA-256 | version | Evidence |
|---|---|---|---|---|
| Public v0.4.4-alpha (GitHub release asset, 5,986,817 B) | `6c6def174902a192cf5f65846261c517ff42edff62bde8ee9c72104ddd8c1556` | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | 10 / 0.4.4 | downloaded and measured |
| v0.4.6 candidate (rebuilt from `cf220061`, project keystore, 6,074,045 B) | `d7b348a7ab8ea5971839e8333325478a89cb9216cf45b9e394801f8736d18d67` | `c876051e2879d005abf610277e58f40b728c62cac36a7748f3357200af3377a4` | 11 / 0.4.6 | **reproduced twice** (incremental and clean) |
| v0.4.6 candidate built with the *default* `~/.android` key (control) | `e051fec4e1d6bae9211b264a844b192c9ff56003a987c9ff9496f3784bb52fa5` | `46488e99373f3c1af0c630642f2d9ec459b8db671391f3ad817efb3aae99da94` | 11 / 0.4.6 | proves the hash is keyed to the signing key, not “a debug build” |
| CI artifact `13043974395` (run `41204287676`) | `385d85ba59421806123d1b34d685472df872f30757b58bd4617643766d99c971` (archive `85a0c40e…`) | `256bb32f8c3be8060c5b1f6390604936b498d2625fad861b63717a17d2abf0f5` | 11 / 0.4.6 | measured; runner-ephemeral key |
| CI artifact `13050465126` (run `41219559793`) | `22a5bd0c…` (measured earlier in this review) | `646fe90f…` | 11 / 0.4.6 | measured; **different cert again** |
| **Staged** `workspace/releases/v0.4.6/PrivateInboxClassic-v0.4.6-alpha-debug.apk` (6,047,517 B) | `a817434edefcb8f5becec48b4ab46ba91badc80bed2b294c0355247c1d6a653c` | `c876051e…` | 11 / 0.4.6 | measured — **not the verified candidate** |
| Adjacent `SHA256SUMS.txt` in the same staging directory | `68ed5c1df26cc57e9bcb183b5c0c410edf1317784cc35e6b025d65f387ec6481` | — | — | measured — **matches no file present** |

The CI certificate changes on every run (two different runner certificates measured), which proves
CI artifacts are ephemeral-signed and **cannot** upgrade an existing install. They must not be
treated as release assets; the docs say so, and the evidence agrees.

Packaged candidate manifest (measured with `aapt2`): no `INTERNET`; `debuggable=true`; service
`exported=false` + BIND permission; `DebugSettingsProvider` present (debug variant), `exported=true`
guarded by `DEBUG_SETTINGS` signature permission; `allowBackup=false`;
`fullBackupContent=false`; `dataExtractionRules` present; `usesCleartextTraffic=false`.

---

## G. Release Gate Matrix

Full table in `CLAUDE_RELEASE_GATE_MATRIX.md`. Summary:

| Gate | Status |
|---|---|
| Source HEAD integrity / PR state | PASS |
| CI actually executed on the reviewed HEAD | PASS |
| Instrumentation 290/290, 0 skipped | PASS |
| JVM 157/157 | PASS |
| Lint / build / security / isolation / DHC audits | PASS |
| No masked failures on the authoritative test step | PASS |
| Conversation isolation / fail-closed SQL | PASS |
| NUL and cursor predicate correctness | PASS |
| Migration additive and non-destructive | PASS |
| Crypto and key-state handling | PASS |
| Manifest, permissions, backup, FLAG_SECURE | PASS |
| No plaintext message bodies in logs | PASS |
| No conversation metadata in logs | **FAIL** (F-05) |
| Dedup across notification revisions | **PARTIAL** (F-03) |
| Listener responsiveness (main thread) | **PARTIAL** (F-02) |
| Pagination correctness / completion | **PARTIAL** (F-04) |
| Async generation/lock handling | PASS |
| Test effectiveness | **PARTIAL** (F-11) |
| Public v0.4.4 identity | PASS |
| Candidate APK identity | PASS |
| CI artifact identity | PASS (and correctly excluded as an upgrade asset) |
| Encrypted-data upgrade evidence | `EVIDENCE_CHAIN_REVIEWED` |
| Future production-key migration disclosed | **FAIL** (F-06) |
| Release staging set internally consistent | **FAIL** (F-01, P1) |
| Release notes published / disclose limitations | **FAIL** (F-06) |
| Documentation accuracy | **PARTIAL** (F-07, F-08, F-14) |
| Real-device / OEM verification | `NOT_VERIFIED` (documented limitation) |
| Independent formal GitHub review | `NOT_ELIGIBLE` for this account (PR author); currently 0 reviews |

---

## H. Findings

Each finding lists: file/line, source HEAD, trigger, observable result, evidence, reproducibility,
counter-evidence, minimal fix, and blocking status.

### F-01 — P1 — Release staging APK is not the verified candidate, and its checksum file is wrong
- **Location:** `[用戶主目錄]/workspace/releases/v0.4.6/PrivateInboxClassic-v0.4.6-alpha-debug.apk`; `.../SHA256SUMS.txt`
- **HEAD:** `cf220061` (artifact provenance is outside the repo)
- **Trigger:** upload/publish the APK that sits in the v0.4.6 release directory.
- **Observable:** the published bytes would be a binary nobody reviewed (`a817434e…`), while the
  documented/reproducible candidate is `d7b348a7…`; the adjacent `SHA256SUMS.txt` records
  `68ed5c1d…`, which matches neither file. Any checksum verification fails; the release evidence
  describes a different asset.
- **Evidence:** `shasum -a 256` on both staging files; `aapt2 dump badging` (vc11/0.4.6);
  `apksigner --print-certs` (cert `c876051e…`, so it is at least the correct key); local clean
  rebuild from `cf220061` → `d7b348a7…`, 6,074,045 B.
- **Reproducible:** yes, deterministically.
- **Counter-evidence:** `SIGNING-IDENTITY-AUDIT.md` marks `a817434e…` as superseded and
  irreproducible; nothing has been published; the correct binary can be rebuilt in ~1 minute.
- **Minimal fix:** rebuild `d7b348a7…` from `cf220061` with the project keystore, replace the staged
  file, regenerate `SHA256SUMS.txt`, and make
  `scripts/release/verify-artifact.sh <apk> d7b348a7…` a mandatory pre-upload step. Never upload
  from a directory whose sums do not verify.
- **Blocks v0.4.6 Alpha:** **YES.**

### F-02 — P2 — Image scaling/JPEG encoding and serialized media reads run on the notification callback (main) thread
- **Location:** `PrivateNotificationListener.kt:848` (`readPictureBytes`), `:872-874`
  (`ImageCapture.readBytes` per image message), `ImageCapture.kt:150-166`
  (`createScaledBitmap` + up to four JPEG compressions), `:70-87` (2 s deadline per read).
- **HEAD:** `cf220061` (product `79f2942`).
- **Trigger:** image capture enabled (default **off**) and a notification carries
  `EXTRA_PICTURE`/`EXTRA_PICTURE_ICON` or multiple image `dataUri` messages.
- **Observable:** `onNotificationPosted` (main thread) performs CPU-bound scaling/JPEG encoding
  (up to ~700 KB target) and can block for up to 2 s **per image message**, sequentially. A busy
  group with images can delay the callback for many seconds → jank, ANR risk, coalesced/dropped
  notifications (missed capture of the app's core data).
- **Evidence:** code reading; the enclosing function is called synchronously from
  `onNotificationPosted` (`:711-748`); `AGENTS.md` hard rule “Background work stays off the main
  thread” and “decoding must not” happen in the callback; the code's own comment at `:846-850`
  claims decoding happens on the worker, which is contradicted by `asPreview` being called here.
- **Reproducible:** code-level deterministic; runtime latency not measured in this review.
- **Counter-evidence:** image capture is opt-in and defaults to off; a single small image is fast;
  the text row is still written if image work fails.
- **Minimal fix:** move `asPreview`/JPEG encoding to the worker (pass the Bitmap or its bytes);
  bound the number/total time of media reads per notification; record the callback duration and add
  an instrumentation assertion on callback latency with images on.
- **Blocks v0.4.6 Alpha:** No (P2), but should be fixed or explicitly measured before publication.

### F-03 — P2 — Duplicate rows after a window shift for timestamp-less notifications
- **Location:** `PrivateNotificationListener.kt:1068-1070` (index in the discriminator),
  `:906-916` (occurrence filter), `InboxDatabase.kt:848-850` (discriminator in the fingerprint).
- **HEAD:** `cf220061`.
- **Trigger:** a notification without per-message timestamps (InboxStyle `EXTRA_TEXT_LINES`,
  compat `time=0`) whose line set shifts (oldest line dropped) between revisions.
- **Observable:** the same message reappears at a different index → different discriminator →
  different HMAC → a **second row** is inserted (duplicate message in the archive). No data loss,
  no wrong-conversation read; counts and the UI show duplicates.
- **Evidence:** code reading; the platform 25-message MessagingStyle cap is documented in the code's
  own comment, which concedes the index is only stable while the window does not shift.
- **Reproducible:** yes, with a unit test on `fingerprintInput`/`save` plus a synthetic preview
  list; not executed here.
- **Counter-evidence:** the common >25 case (MessagingStyle) carries timestamps and is stable; most
  InboxStyle digests are short and don't shift within a notification lifetime.
- **Minimal fix:** make the discriminator content-only for timestamp-less messages (accept that two
  identical bodies may collapse), or persist a per-notification ordinal map that survives shifts.
  Add a regression test with a shifted window.
- **Blocks v0.4.6 Alpha:** No.

### F-04 — P2 — Pagination can stop early or stay stuck when a page contains an undecryptable row / is empty
- **Location:** `InboxDatabase.kt:578-580`, `:715-717`, `:1103-1105` (`hasMore = output.size >
  bounded`, computed after skipped rows); `MainActivity.kt:1004-1006` (only sets `chatHasMore` for a
  non-empty page), `:1161-1206` (load-older completion), `:1053` (affordance).
- **HEAD:** `cf220061`.
- **Trigger (a):** one row in the fetched page fails decryption (corrupt ciphertext/AAD mismatch)
  while older rows exist. The fetched `limit+1` rows include a failure, so the decrypted output is
  ≤ `bounded` and `hasMore` becomes false — older history is silently unreachable for that
  conversation.
- **Trigger (b):** the next page is empty (end of history, or the remaining rows are all skipped);
  `renderChatPage` leaves `chatHasMore = true`, so the “load older” affordance remains and every tap
  returns an empty page.
- **Observable:** silent truncation of older messages, or an infinite no-progress control.
- **Evidence:** code reading; the `unreadable` counter is returned but not used to drive `hasMore`.
- **Reproducible:** (b) deterministically with a cursor at end; (a) requires a corrupted row.
- **Counter-evidence:** key invalidation makes *all* rows fail and triggers the recovery UI, so
  case (a) needs a single-row corruption (rare); (b) only shows a useless control, no data loss.
- **Minimal fix:** compute `hasMore` from the raw fetched-row count (`fetched > bounded`), use
  `unreadable` to show a “N messages could not be read” notice, and always assign
  `chatHasMore = detailPage.hasMore` on load-older completion (including empty pages).
- **Blocks v0.4.6 Alpha:** No.

### F-05 — P2 — Conversation titles and legacy keys are written to Logcat
- **Location:** `InboxDatabase.kt:540` (`Log.d("READMODEL", "sql=… args=$args")`), `:502`
  (canonical id), `:574` (row counts). The `args` list contains `hex()` legacy keys
  (`package \0 shortcut? \0 title`) and canonical ids.
- **HEAD:** `cf220061`.
- **Trigger:** every chat-detail open, older-page load and search.
- **Observable:** anyone with `adb logcat` (the shipped alpha is `debuggable=true`) can read
  contact/group names and conversation keys. Message bodies are **not** logged.
- **Evidence:** code; `AGENTS.md` hard rule 4 allows only package names, stage codes, timestamps and
  exception type names in logs.
- **Reproducible:** yes (read the code; no device needed).
- **Counter-evidence:** `Log.d` is filtered from ordinary users; debug builds require USB debugging;
  titles are metadata, not bodies.
- **Minimal fix:** delete the three `READMODEL` log statements (or wrap them in `BuildConfig.DEBUG`
  and log only counts/stage codes). Never log query arguments.
- **Blocks v0.4.6 Alpha:** No, but it violates the project's own hard rule and should be fixed
  before a privacy-focused release.

### F-06 — P2 — Release disclosure gaps: debug signature ⇒ no upgrade path, debuggable build, one-way export
- **Location:** `README.zh-TW.md:176-182`, `:226-239`; `docs/releases/v0.4.6-ALPHA-CLOSURE.md`
  §11; `app/build.gradle.kts` (release unsigned without a release key); packaged manifest
  (`debuggable=true`); `DhcExportService.kt` / `DhcExporter.kt` (export only, no restore).
- **HEAD:** `cf220061`.
- **Trigger:** a future production release signed with a different key, and users reading the alpha
  docs.
- **Observable:** Android refuses to update a debug-signed install with a production-signed one;
  the only path is uninstall → **all archived messages are lost**. The app can export an encrypted
  bundle, but there is no in-app restore, and the current docs do not tell users this. The docs also
  do not state that the alpha is `debuggable` (adb/run-as can read app-private files) or that the
  update trust is bound to a debug key.
- **Evidence:** `app/build.gradle.kts:186-216` (unsigned release without a key); measured
  `debuggable=true`; `README.zh-TW.md:180` (claims no export at all); no v0.4.6 release notes exist
  (`gh release list` shows only v0.4.4-alpha).
- **Reproducible:** yes (documentation and manifest inspection).
- **Counter-evidence:** the alpha is explicitly labelled “Debug Alpha”; the project already lists
  “Debug signing only” and `PRODUCTION_SIGNING_BLOCKED` as limitations; nothing is published yet,
  so the disclosure can be added before release.
- **Minimal fix:** the v0.4.6 release notes must state, in plain language: debug-signed; cannot be
  upgraded to a production-signed build without uninstalling (data loss); export exists but restore
  into the app does not; the build is debuggable; emulator-only validation; keep the keystore
  offline. Update `README.zh-TW` known issues accordingly.
- **Blocks v0.4.6 Alpha:** Yes for the release-notes gate (publication must not proceed without
  it); no product change required.

### F-07 — P2 — README “known issues” contradict shipped v0.4.6 behaviour
- **Location:** `README.zh-TW.md:176-182` — item 2 says key loss only “skips undecryptable rows”
  (a recovery flow with explicit confirmation exists), item 5 says there is **no export feature**
  (encrypted DHC export ships and is wired into Settings), item 7 says there is **no full
  pagination / 1500 rows at once** (keyset pagination is implemented and tested).
- **HEAD:** `cf220061`.
- **Observable:** users cannot make an informed decision about data preservation (the most
  safety-relevant disclosure), and reviewers get a stale picture of the app.
- **Evidence:** `MainActivity.kt:1369-1470` (export UI), `:1599` (settings text), IR-09/10/11
  pagination code and tests.
- **Reproducible:** yes.
- **Counter-evidence:** these are “known issues”, and some (same-name contacts) remain true; the
  item-5 claim was accurate before the DHC export existed.
- **Minimal fix:** rewrite the known-issues list for 0.4.6 and add a “current as of HEAD/date” line.
- **Blocks v0.4.6 Alpha:** No, but it should be fixed together with F-06.

### F-08 — P3 — Stale test numbers in user/handoff docs
- **Location:** `README.md:286-288` (126 instrumentation / 84 unit), `docs/handoff/current/AGENT_STATE.md:9`
  (239 run / 1 flaky), versus the measured 290/290 and 157/157.
- **HEAD:** `cf220061`.
- **Observable:** reviewers and users see numbers that contradict CI; undermines confidence in the
  rest of the documentation.
- **Evidence:** README lines; `AGENT_STATE.md`; CI reports.
- **Counter-evidence:** `FINAL-TEST-MATRIX.md` and `TASK_STATE.json` carry the current numbers and
  supersede these files; the stale pages are not the release evidence.
- **Minimal fix:** update or explicitly mark these files as historical.
- **Blocks:** No.

### F-09 — P3 — Merge/unmerge is unreachable but has latent atomicity defects
- **Location:** `ConversationStore.kt:364-432` (validation before `beginTransaction`; merged-away
  target not rejected), `:445-498` (unmerge).
- **HEAD:** `cf220061`.
- **Trigger:** only if merge is wired to the UI in a future version.
- **Observable (latent):** a merge into an already-merged-away conversation hides the source
  messages under the intermediate id; a concurrent merge can pass validation and mutate state.
- **Evidence:** no product call sites (`rg '\.merge\('` finds none); tests exercise it directly.
- **Counter-evidence:** unreachable from any user path in v0.4.6; `wouldCycle` prevents cycles.
- **Minimal fix:** move validation inside the transaction and reject `into` when it is merged away,
  before exposing the feature.
- **Blocks:** No.

### F-10 — P3 — CI masks a release-build failure
- **Location:** `.github/workflows/android.yml:109-110`
  (`./gradlew :app:assembleProdRelease || echo "…"`).
- **HEAD:** `cf220061`.
- **Observable:** a compile error in the release variant would leave CI green; the unsigned-release
  expectation and a real failure are indistinguishable.
- **Evidence:** workflow line; `audit-release.sh` skips a missing release APK.
- **Counter-evidence:** the shipped artifact is a debug build; release outputs are unsigned by
  design and not part of the alpha.
- **Minimal fix:** run `assembleProdRelease` without masking and assert the output is unsigned
  (`apksigner verify` fails / no `META-INF`), failing the step if the build fails for any other
  reason.
- **Blocks:** No.

### F-11 — P3 — UI-test effectiveness gaps on the lock and failure paths
- **Location:** `IR11NavigationAndPaginationSafetyInstrumentedTest.kt:686-689` (reflective
  `unlocked=false` + `showLock()` instead of real `onStop()`), `:477` (`dbQueryFailure…` injects no
  failure), test hooks `MainActivity.kt:826,834`.
- **HEAD:** `cf220061`.
- **Observable:** the tests can pass without exercising the production lifecycle/error path;
  regressions there would not be caught.
- **Evidence:** test source; `onStop()` at `MainActivity.kt:223-234` is not invoked.
- **Counter-evidence:** the tests do assert real DB state and real UI text elsewhere; the isolation
  hooks are deliberate and documented.
- **Minimal fix:** drive the real `onStop()` (e.g., move the activity to the background); add a
  failure-injection seam that throws from the read and assert the cross-conversation guard.
- **Blocks:** No.

### F-12 — P3 — `existingIdForFingerprint` never matches; `save()` comment is wrong
- **Location:** `InboxDatabase.kt:895-899` (String bound to a BLOB column), `:848-850` (comment
  claims dedup happens before encryption; it does not).
- **HEAD:** `cf220061`.
- **Observable:** DUPLICATE results report `id = -1`; the comment misleads maintainers about the
  write-amplification control. No data impact (NEW rows link by real id).
- **Evidence:** code reading; `insertWithOnConflict` is called after `CryptoStore.seal`.
- **Counter-evidence:** the listener's source-level dedup is the actual amplification control and
  works.
- **Minimal fix:** bind the fingerprint as a `ByteArray` (or query `hex(fingerprint)`), and correct
  the comment.
- **Blocks:** No.

### F-13 — P3 — Settings screen shows the wrong version and an incomplete plaintext-metadata disclosure
- **Location:** `MainActivity.kt:1599` — “Private Inbox Classic v0.3.1（內部測試）” in a 0.4.6 build;
  the nearby text says only “notification text and attached photos” are stored offline while
  package names, timestamps, conversation keys/titles are also stored in plaintext metadata.
- **HEAD:** `cf220061`.
- **Observable:** users see a stale version string and an understated description of stored
  metadata.
- **Evidence:** code line; schema columns `package_name`, `received_at`, `source_ts`,
  `conversation_key`, `message_kind`.
- **Counter-evidence:** other docs (README.zh-TW #1) do disclose plaintext metadata.
- **Minimal fix:** use `BuildConfig.VERSION_NAME` and list the plaintext metadata fields.
- **Blocks:** No.

### F-14 — P3 — PR #4 body carries stale CI and upgrade narrative
- **Location:** PR #4 body.
- **HEAD:** `cf220061`.
- **Observable:** the body says CI ran on `c6d9b1a`; the actual run `41219559793` has
  `head_sha = cf220061` (docs-only difference, but the statement is inaccurate). The
  “Data-preserving upgrade — executed” section describes the superseded marker-only test before
  noting the IR-12 result, which can mislead a reader skimming the table.
- **Evidence:** `gh api …/runs/41219559793`; PR body text.
- **Counter-evidence:** the body itself labels the marker-only test as superseded and points to
  IR-12.
- **Minimal fix:** update the PR body to the actual run SHA and separate the historical test from
  the current evidence.
- **Blocks:** No.

---

## I. Final Recommendation

**TECHNICAL_VERDICT = `CHANGES_REQUESTED`.**

Reasons, in release order:

1. **F-01 (P1):** the v0.4.6 release staging set is internally inconsistent. The file named for
   release is not the candidate anyone verified, and its checksum file describes a third binary.
   This must be corrected before any upload.
2. **F-06 (P2, release-notes gate):** the public alpha must disclose that a future production-signed
   build cannot replace the debug alpha without uninstalling (irreversible data loss), that the
   export is one-way, that the build is debuggable, and that validation is emulator-only.
3. **F-07 (P2):** the user-facing known-issues list currently says the opposite of what v0.4.6 does
   on the two points that matter most for data preservation (export, pagination).
4. **F-02…F-05 (P2):** fix or explicitly disclose the main-thread image work, index-shift
   duplicates, pagination early-stop, and Logcat metadata; none is a P0/P1, but together they are
   the difference between “works in the happy path” and “safe to hand to strangers”.
5. **Formal review:** this audit was performed with the PR author's GitHub account. It cannot be a
   formal GitHub approval and was not posted. A genuinely independent reviewer must still review
   PR #4 before publication.

If F-01 is corrected, F-06/F-07 disclosures are added, and the P2 code items are either fixed or
documented as accepted limitations, the technical picture supports publication as an
**Experimental Debug Alpha** — with the standing limitations (debug signing, emulator-only,
no production migration path) clearly stated. Until then, the release is **not** authorized.

**No product implementation, merge, release, APK upload, keystore operation or IR-13 was performed
as part of this review.**

---

## ADDENDUM — Delta re-review `cf220061 → d376f40` (2026-11-01)

This document is the review of `cf220061`. Codex's corrective commit `d376f40` was reviewed
separately; the full report is `CLAUDE_FINAL_CORRECTIVE_REVIEW.md` in this directory. Changes to
the conclusions above:

| Original item | Then | Now (`d376f40`) |
|---|---|---|
| **F-01** (P1) release staging | FAIL — staged `a817434e`, sums `68ed5c1d`, candidate `d7b348a7` | **FIXED / CLOSED** — staged APK is `b68fd821…`, `shasum -c` OK, cert `c876051e…`, and a fresh `--rerun-tasks` build from `d376f40` reproduces `b68fd821…` bit-for-bit. Old binary confined to `_backup-pre-ir12-fix/`; CI runner APK (`ff516433…`/`4def3c89…`) absent from the release directory. |
| **F-05** (P2) Logcat identifiers | FAIL | **FIXED / CLOSED** — identifiers and SQL text removed; new regression test present and counted in CI (291). Mutation-test claim not evidenced; exception logging at `MainActivity.kt:425` remains a minor residual. |
| **F-06** (P2) disclosure | FAIL | **CLOSED in substance** — release notes exist and cover every required point. |
| **F-07** (P2) README | FAIL | **CLOSED in substance** — known issues rewritten and accurate. |
| F-02, F-03, F-04 (P2) | open | still open, now disclosed in the release notes / README; accepted as deferred |
| F-08…F-14 (P3) | open | F-08 partially fixed; F-09…F-14 unchanged and still open |

New issues found this round (all documentation-level, none P0/P1): the resolution matrix drops five
findings and downgrades F-03 without rationale (D-01); release notes / closure / PR body attribute
the shipped APK to `79f2942`, which provably cannot produce it (D-02); stale 290/290 counts (D-03);
the IR-12 upgrade narrative still names the pre-fix binary (D-04); plus D-05…D-08. See
`CLAUDE_FINAL_CORRECTIVE_REVIEW.md` §8.

**Revised technical verdict:** `CHANGES_REQUESTED` — documentation corrections only. The artifact
itself is `VERIFIED`; formal GitHub approval remains `PENDING` (this account is the PR author, and
the PR still has zero reviews).
