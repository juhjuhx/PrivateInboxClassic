# Independent Review Remediation — IR-01 … IR-07

**Baseline reviewed:** `fbe283b4` (PR #4 head at review time)
**Repair commits:** on `v046-integration-repair-final` (pushed to PR #4)
**Emulator:** `emulator-5554`, API 36 (`test36` AVD)
**Rule:** RED → FIX → GREEN → FULL REGRESSION → REVIEW

Each finding below records the file, the line of the defect, the RED evidence
produced before the fix, the fix itself, the GREEN evidence after it, and the
regression result.

---

## IR-01 — Production conversation pagination

**File / line (RED):** `MainActivity.kt:335` — `db.readPage(limit = PAGE_SIZE)`
returned 50 **messages**, which were grouped into conversations only afterwards
(`threads()` at `MainActivity.kt:511`).

**RED evidence.** Reading 50 messages then grouping means:
* a conversation holding 100 recent messages fills the whole page, so an
  older-but-distinct conversation is never rendered;
* a conversation whose rows straddle the page boundary is rendered twice.
The new `IR01ConversationPaginationInstrumentedTest` pins both: with A holding
100 recent messages and B holding one older message, the old path returns a
single conversation; the fixed path returns both.

**Fix.**
* `MainActivity.loadEntries()` now calls
  `InboxDatabase.conversationPage(limit = PAGE_SIZE, offset = 0)` and decrypts
  only each conversation's newest message via the new
  `InboxDatabase.entryForMessageId(id)` (O(1) per conversation).
* `MainActivity.loadMorePages()` pages by **conversation offset**
  (`pagesLoaded * PAGE_SIZE`), not by a message timestamp cursor.
* `MainActivity.showChat()` pages the **detail** screen by message through
  `readConversationMessages(key, limit = CHAT_PAGE_SIZE)`, falling back to the
  already-loaded rows when unavailable.

**GREEN evidence.** `IR01ConversationPaginationInstrumentedTest` — 4/4 OK:
* `conversationWithManyRecentMessagesDoesNotHideAnOlderConversation`
* `hundredConversationsPageWithoutLossOrDuplication`
* `unlinkedLegacyRowsRemainVisible` (legacy rows keep `k:` grouping)
* `renameAndMergeKeepPagingCorrect`

---

## IR-02 — Cross-conversation read

**File / line (RED):** `InboxDatabase.kt:367` `readConversationMessages()` built
its `WHERE` clause only from `LEGACY_KEY` aliases:

```kotlin
if (keyHexes.isNotEmpty()) { selection.append("hex(conversation_key) IN (...)") }
...
"... FROM messages " + (if (selection.isNotEmpty()) "WHERE $selection " else "") + ...
```

With no alias, `selection` was empty, so the statement had **no WHERE clause**
and decrypted the entire `messages` table.

**RED evidence.** `IR02FailClosedReadInstrumentedTest.unknownCanonicalIdReturnsEmptyNotTheWholeTable`
reads an unknown `cv_…` id against a populated table. Before the fix it returned
every row; after the fix it returns 0.

**Fix (fail-closed).**
* Collect both membership signals: `LEGACY_KEY` aliases **and** the ids in
  `message_conversation`.
* If BOTH are empty → return `ReadResult(emptyList(), …)` immediately. There is
  no code path that can emit a query without a `WHERE`.
* When present, membership is the UNION of the two signals, always wrapped in
  parentheses before the cursor predicate so the `OR` cannot escape the scope.

**GREEN evidence.** `IR02FailClosedReadInstrumentedTest` — 6/6 OK:
unknown id → 0 rows; deleted aliases still scoped by link; partial migration
returns only linked rows; merge/unmerge never widen; cross-package stays scoped;
empty database returns empty for any id (including a NUL-containing key).

---

## IR-03 — NUL-safe canonical linking

**File / line (RED):** `PrivateNotificationListener.kt:500`

```kotlin
"SELECT m.id FROM messages m LEFT JOIN message_conversation l " +
"ON l.message_id = m.id WHERE m.conversation_key = ? AND l.message_id IS NULL " +
"ORDER BY m.id DESC LIMIT ?", arrayOf(legacyKey, batch.messages.size.toString())
```

`legacy_key` contains NUL separators, and SQLite truncates a bound Java String
at the first NUL, so the comparison could not identify the batch. The
`ORDER BY id DESC LIMIT <batch size>` then linked whichever rows happened to be
newest — including rows written by a **concurrent notification** for the same
conversation.

**RED evidence.** The old query is replaced; `IR03NulSafeLinkingInstrumentedTest`
proves the hazard directly: both rows' `conversation_key` contain a NUL
(`instr(conversation_key, char(0)) > 0`) and both share the truncated package
prefix, so a prefix comparison matches **2** rows — it cannot tell them apart.

**Fix.**
* `InboxDatabase.SaveResult.NEW` now carries the **actual id** SQLite returned
  from `insertWithOnConflict` (`SaveResult.NEW(inserted)`).
  `DUPLICATE` carries the existing id when resolvable.
* `linkBatchToCanonicalConversation(..., newRowIds)` links exactly those ids.
  The key-comparison query and the `ORDER BY id DESC LIMIT` heuristic are gone.
* The fingerprint formula is untouched.

**GREEN evidence.** `IR03NulSafeLinkingInstrumentedTest` — 6/6 OK: NEW carries a
real row id; DUPLICATE is never NEW; mixed `[NEW, FAILURE, NEW]` links both;
NUL-key rows link to their own conversations; linking survives a fresh helper;
reconcile does not re-link an already-linked row.

---

## IR-04 — Conversation summary cardinality

**File / line (RED):** `InboxDatabase.kt:323` — the outer join

```sql
LEFT JOIN conversation_aliases a
  ON a.package_name = g.package_name
 AND a.alias_type = 'SHORTCUT'
 AND a.conversation_id = g.gid
```

is one-to-many: a conversation owning two SHORTCUT aliases produced **two**
summary rows for the same conversation.

**RED evidence.** `IR04SummaryCardinalityInstrumentedTest.twoShortcutAliasesProduceExactlyOneSummaryRow`
adds a second SHORTCUT alias to one conversation. Before the fix the page held 2
rows for 1 conversation and `conversationCountAll` disagreed with the page.

**Fix.** The join is pinned to exactly ONE deterministic alias row via a
correlated sub-select ordered `STRONG` first, then `alias_value`, `LIMIT 1`.
`DISTINCT` is deliberately **not** used — it would hide the bad aggregation
instead of correcting the one-to-many relation.

**GREEN evidence.** `IR04SummaryCardinalityInstrumentedTest` — 5/5 OK: two
shortcuts → one row; 10 conversations with two shortcuts each → exactly 10 rows
(not 20); latest id/timestamp match the newest message; paging with aliases is
stable (40 conversations, no repeats); merge/unmerge keep cardinality.

---

## IR-05 — Schema repair recovery

**File / line (RED):** `InboxDatabase.kt:96`

```kotlin
if (schemaRepaired.compareAndSet(false, true)) { ... runCatching { createSchema(db) } ... }
```

The flag was set to `true` **before** the work. A single transient failure
(`SQLITE_BUSY` from the capture writer, a read-only mount, a DDL error part-way)
permanently marked the schema repaired for the process, so every later open
skipped the repair.

**RED evidence.** `IR05SchemaRepairInstrumentedTest.aFailedRepairIsNotRememberedAsSuccess`
drops all six canonical tables and asserts the count really is 0; a helper that
had "remembered" success would leave them missing. After the fix the next open
rebuilds all six.

**Fix.** Tri-state `AtomicInteger` repair state:
`REPAIR_NONE = 0` → `REPAIR_RUNNING = 1` → `REPAIR_DONE = 2`.
* `compareAndSet(NONE, RUNNING)` guards concurrent repair (a second connection
  does not run the DDL concurrently).
* The repair is followed by a **verification** probe
  (`countCanonicalTables(db) >= CANONICAL_TABLE_COUNT`).
* On any throwable, or a failed verification, the state returns to
  `REPAIR_NONE` so the **next open retries**; only a verified success sets
  `REPAIR_DONE`.

**GREEN evidence.** `IR05SchemaRepairInstrumentedTest` — 5/5 OK: fresh DB → 6
tables; repeated opens stable; a dropped table is recreated on the next open; a
failed repair is not remembered as success; existing messages survive the repair.

---

## IR-06 — Authoritative CI

**File / line (RED):** `.github/workflows/android.yml` — the instrumentation step
ran `SystemNotificationE2E` with `|| true`, so a green workflow could not prove
the suite passed.

**Root cause found during this round (this is NOT a flake).**
`DebugSettingsProvider.call()` had **no** `postSelfTest` / `cancelSelfTest`
branch at all. The E2E test calls `postSelfTest`, always received `null`, and
threw `IllegalStateException: the app failed to post`
(`SystemNotificationE2EInstrumentedTest.kt:90`). The failure had been
mislabelled FLAKY_UNRESOLVED; the `|| true` masked it.

**Fix.**
* Implemented `postSelfTest` and `cancelSelfTest` in
  `app/src/debug/.../DebugSettingsProvider.kt`, delegating to the new
  `SelfTestNotifier.postCustom(ctx, id, title, body, shortcutId)`, which posts
  through the real `NotificationManager` and sets the shortcut id so the
  resolver has a STRONG signal.
* Removed `|| true` and every `continue-on-error`. The instrumentation step is
  authoritative: one `./gradlew :app:connectedDevtestDebugAndroidTest`
  invocation so the JUnit XML contains every test.
* Test reports are still uploaded with `if: always()` (JUnit XML, counts,
  skips, failures).

**GREEN evidence.** The suite passes for real: full instrumentation
**272 / 272 PASS** (0 failures, 0 skipped) on `emulator-5554`, and GitHub
Actions run 40793600918 reported `Starting 265 tests … Finished 265 …
BUILD SUCCESSFUL` with 0 failures, 0 skipped (265 was before the IR-08
follow-up suite was added). The test also now grants and VERIFIES
POST_NOTIFICATIONS itself instead of inheriting a grant from another class.

---

## IR-07 — Review and publication gate

Focused commits, push to PR #4, GitHub CI verification, full
`3d56a7f5..HEAD` diff review, documentation refresh, and the publication
verdict are recorded in the handoff and in the reviewer report.

---

## Full regression (final)

| Suite | Result |
|-------|--------|
| Instrumentation (all suites) | **272 / 272 PASS**, 0 failures, 0 skipped |
| JVM unit tests | 157 / 157 PASS |
| audit.sh / audit-secrets.sh / audit-test-isolation.py | PASS / PASS / PASS (47 files) |
| audit-release.sh / verify-version.sh | PASS / PASS |
| DHC connector | 23 / 23 PASS |

## IR-08 — defects found IN the IR fix by the independent review

The reviewer found that the IR-01 rewrite introduced new defects. Each is
fixed and pinned by `IR08ReviewFollowupInstrumentedTest` (7 tests):

| Defect | Severity | Fix |
|---|---|---|
| `keyState`/`unreadableRows` never assigned → key recovery dead | P1 | `conversationPreview()` reports state; MainActivity restores both fields |
| Unlinked conversation detail collapsed to 1 message | P2 | `readMessagesByLegacyKey()`; the FIRST routing attempt was dead code — see IR-09 |
| `chatHasMore` written, never read → silent truncation | P2 | real "load older messages" control |
| Offset paging skips conversations on insert | P2 | keyset cursor as `HAVING` inside the derived table |
| `conversationPage(search=)` accepted and ignored | P2 | parameter removed |
| Dead `db` parameter, dead `conversationCursor` | P3 | removed |
| E2E never granted POST_NOTIFICATIONS (ordering-dependent pass) | P1-adjacent | test grants and verifies it itself |

**A note on the keyset fix:** SQLite does not apply column affinity inside a
`HAVING` clause, so a TEXT-bound timestamp compared against an INTEGER
aggregate silently matches nothing — the filter appeared to work and returned
the same page forever. This was caught by the new IR-08 tests, not by
inspection. The cursor timestamps are now inlined as numeric literals.

## IR-09 — the second review round

The re-review found that one IR-08 fix was **dead code**, and that a P2 from the
first round was still open. Both are now closed:

| Defect | Severity | Fix |
|---|---|---|
| Unlinked-detail routing branched on a `"k:"` prefix that `currentThread` never carries, so the branch was unreachable and the chat screen still showed 1 message | **P1** | Routing is data-driven: try the canonical read, fall back to `readMessagesByLegacyKey()` when it returns nothing. The unused prefix constant is removed. |
| `readConversationMessages()` read `linkedIds` unbounded and emitted one `?` per message; >999 messages exceeded SQLite's bound-variable limit and threw | P2 | Membership is an `EXISTS` sub-select, so the placeholder count is constant |

**Why the previous suite did not catch the P1:** `IR08` called the DB method
directly, which was correct, and never drove `showChat()`. The routing test now
opens the conversation through the real Activity (`openThreadForTest` →
`showChat()` → view hierarchy) and asserts every message body is rendered, so a
dead branch cannot pass again. A 1200-message read was added for the variable
limit.

Full instrumentation after IR-09: **274 / 274 PASS**.

## Verdict

All engineering findings IR-01 … IR-08 are closed with RED/GREEN evidence.
Publication remains gated on the independent reviewer's final sign-off over the
`3d56a7f5..HEAD` range.
