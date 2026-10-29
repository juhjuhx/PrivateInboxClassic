package tw.personal.privateinbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** V4: adds a stable conversation key and a message-kind column so the inbox can
 *  group by conversation identity (not only the display name) and filter out
 *  social-activity notifications.
 *
 *  Source package names and times remain plain metadata; content and images are
 *  AES-GCM sealed.
 *
 *  Migration is additive: existing rows keep their data and get a neutral
 *  conversation_key derived from their package + title, so no user data is lost. */
class InboxDatabase(private val appContext: Context) :
    SQLiteOpenHelper(appContext, "inbox.db", null, 6) {
    data class Entry(
        val id: Long, val packageName: String, val receivedAt: Long,
        val sourceTimestamp: Long,
        val title: String, val sender: String, val body: String, val hasImage: Boolean,
        /** Stable conversation identity key (package + conversation discriminator). */
        val conversationKey: String = "",
        /** Classification of the notification, see [SourceRegistry.MessageKind]. */
        val messageKind: String = SourceRegistry.MessageKind.UNKNOWN.name
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            package_name TEXT NOT NULL,
            received_at INTEGER NOT NULL,
            source_ts INTEGER NOT NULL DEFAULT 0,
            conversation_key TEXT NOT NULL DEFAULT '',
            message_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
            fingerprint BLOB NOT NULL UNIQUE,
            sealed_content BLOB NOT NULL,
            sealed_image BLOB
        )""".trimIndent())
        db.execSQL("CREATE INDEX received_idx ON messages(received_at DESC)")
        db.execSQL("CREATE INDEX conversation_idx ON messages(conversation_key)")
        // The composite indexes the list screen's queries need. They are
        // ADDITIVE: an index is a derived structure, and dropping one loses
        // no data.
        db.execSQL(
            "CREATE INDEX package_received_idx " +
                "ON messages(package_name, received_at DESC, id DESC)"
        )
        db.execSQL(
            "CREATE INDEX conversation_received_idx " +
                "ON messages(conversation_key, received_at DESC, id DESC)"
        )
        db.execSQL(
            "CREATE INDEX kind_received_idx " +
                "ON messages(message_kind, received_at DESC, id DESC)"
        )
        // v0.4.6: the canonical conversation layer. Additive side tables; the
        // `messages` schema is deliberately NOT changed (see ConversationStore).
        ConversationStore.createSchema(db)
    }

    /**
     * Repair the canonical layer on every open.
     *
     * ## Why this exists
     *
     * `ConversationStore.createSchema()` is called from `onCreate` and from
     * `onUpgrade(oldVersion < 6)`. A database created by an EARLIER v0.4.6 build
     * already reports `user_version = 6`, so neither hook runs — and the tables
     * added later (the merge journal) would be missing forever, making the first
     * merge/unmerge throw "no such table".
     *
     * `onOpen` runs on every open, after the upgrade path, so a single idempotent
     * `CREATE TABLE IF NOT EXISTS` pass repairs any such database. This is
     * deliberately NOT a version bump: bumping `user_version` would run the
     * back-fill again on every existing database, and the whole point is to leave
     * `messages` untouched. The DDL is idempotent, so opening a healthy database
     * does no work.
     */
    /**
     * Whether the canonical schema was verified present in this process.
     *
     * G1.5 regression note: running `createSchema()` (6 DDL statements plus a
     * `PRAGMA table_info`) on EVERY connection open starved the burst writer —
     * the 500-message burst test hit `SQLITE_BUSY` because every pooled
     * connection took the write lock briefly at open. The repair therefore runs
     * ONCE per process, guarded by this flag, and by a cheap
     * `user_version`/table check that does not take a write lock.
     */
    private val repairState = java.util.concurrent.atomic.AtomicInteger(REPAIR_NONE)

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // IR-05: the "already repaired" flag may only be set AFTER the repair
        // actually succeeded.
        //
        // The previous version used compareAndSet(false, true) BEFORE doing the
        // work, so a single transient failure (SQLITE_BUSY from the capture
        // writer, a read-only mount, a DDL error part-way through) permanently
        // marked the schema as repaired for the whole process. Every later open
        // then skipped the repair and ran against a half-created schema for the
        // rest of the process lifetime.
        //
        // The flag now has three states, expressed as the value of an Int:
        //   0 = not attempted, 1 = in progress, 2 = verified repaired.
        // A failure leaves it at 0 so the NEXT open retries; only a verified
        // success (all six tables present afterwards) moves it to 2.
        if (repairState.get() == REPAIR_DONE) return
        if (!repairState.compareAndSet(REPAIR_NONE, REPAIR_RUNNING)) {
            // Another connection is repairing right now. Do not run the DDL a
            // second time concurrently; the caller proceeds, and if the other
            // repair fails the state returns to REPAIR_NONE and this connection
            // retries on its next open.
            return
        }
        var repaired = false
        try {
            // Fast, read-only probe: are the journal tables already there?
            val haveTables = countCanonicalTables(db)
            if (haveTables >= CANONICAL_TABLE_COUNT) {
                repaired = true
            } else {
                // An older v6 (or a fresh file): run the full idempotent repair.
                ConversationStore.createSchema(db)
                // The repair may have created the canonical tables for the first
                // time; make sure pre-existing rows are linked.
                ConversationStore.backfillAliases(db)
                // Verify the repair actually took effect before claiming success.
                repaired = countCanonicalTables(db) >= CANONICAL_TABLE_COUNT
                if (!repaired) {
                    CaptureDiagnostics.note(
                        appContext, null, "DATABASE_OPEN",
                        CaptureDiagnostics.SCHEMA_REPAIR_FAILED,
                        "verification_failed"
                    )
                }
            }
        } catch (e: Throwable) {
            // Do NOT mark repaired: a transient failure must be retried on the
            // next open instead of being remembered as success.
            CaptureDiagnostics.note(
                appContext, null, "DATABASE_OPEN",
                CaptureDiagnostics.SCHEMA_REPAIR_FAILED,
                e.javaClass.simpleName
            )
        } finally {
            repairState.set(if (repaired) REPAIR_DONE else REPAIR_NONE)
        }
    }

    /** How many of the canonical tables exist right now (read-only probe). */
    private fun countCanonicalTables(db: SQLiteDatabase): Int = runCatching {
        db.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN " +
                "('conversations','conversation_aliases','message_conversation'," +
                "'message_conversation_undo','conversation_alias_undo','conversation_merge_log')",
            null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.getOrDefault(0)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // PRAGMA statements are queries, not updates: execSQL() rejects them with
        // "Queries can be performed using SQLiteDatabase query or rawQuery methods only".
        runCatching { db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() } }
        // H02: the OPEN path itself reads `user_version` (SQLiteDatabase.getVersion),
        // which hits SQLITE_BUSY when the capture worker holds a long write
        // transaction (the 500-message burst test reproduces it deterministically
        // under suite ordering). A busy timeout makes the open WAIT instead of
        // failing immediately — the same contract every other statement already
        // has via the clear()/retry paths. 5 s covers the longest observed
        // transaction; longer contention is a real error and still throws.
        runCatching {
            db.rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive, non-destructive migrations. Each step only adds columns and
        // back-fills neutral defaults; no row is ever deleted or rewritten with
        // data the user would lose.
        //
        // P0-A fix (v0.4.5 reliability review): `ADD COLUMN` is NOT idempotent
        // in SQLite — a repeated `ADD COLUMN source_ts` crashes with
        // "duplicate column name". The historical v0.4.4 code added source_ts
        // in BOTH the <2 and the <3 step, so a v1 database hit it twice and
        // every open crashed until reinstall. Two guards make this safe:
        //
        //   1. Steps are kept version-disjoint: each ALTER runs for exactly
        //      one historical layout (the <3 step adds source_ts alone, so a
        //      v1 database gets it exactly once through the two-step chain).
        //   2. columnExists() double-checks before every ALTER, so a partially
        //      upgraded database (a previous update crashed midway) re-runs
        //      without crashing — recovery stays predictable instead of
        //      wedging the open path forever.
        fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
            var found = false
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == column) found = true
                }
            }
            return found
        }

        fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, ddl: String) {
            if (!columnExists(db, table, column)) db.execSQL(ddl)
        }

        if (oldVersion < 2) {
            addColumnIfMissing(
                db, "messages", "sealed_image",
                "ALTER TABLE messages ADD COLUMN sealed_image BLOB"
            )
        }
        if (oldVersion < 3) {
            addColumnIfMissing(
                db, "messages", "source_ts",
                "ALTER TABLE messages ADD COLUMN source_ts INTEGER NOT NULL DEFAULT 0"
            )
        }
        if (oldVersion < 4) {
            addColumnIfMissing(
                db, "messages", "conversation_key",
                "ALTER TABLE messages ADD COLUMN conversation_key TEXT NOT NULL DEFAULT ''"
            )
            addColumnIfMissing(
                db, "messages", "message_kind",
                "ALTER TABLE messages ADD COLUMN message_kind TEXT NOT NULL DEFAULT 'UNKNOWN'"
            )
            // Back-fill the conversation key for rows captured before v4. The title is
            // encrypted, so it cannot be read here; the key is reconstructed lazily
            // from the package name at read time (see legacyConversationKey).
            db.execSQL("CREATE INDEX IF NOT EXISTS conversation_idx ON messages(conversation_key)")
        }
        if (oldVersion < 5) {
            // The composite indexes the list screen's queries need.
            //
            // They are ADDITIVE: an index is a derived structure, and dropping
            // one loses no data. The migration only creates them.
            //
            // The single-column indexes above are kept: the "read everything
            // for one conversation" query and the "read the newest page" query
            // use them, and SQLite's planner still picks the composite index
            // for the filtered queries. The measurement is in
            // docs/reliability/STORAGE-BENCHMARK.md.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS package_received_idx " +
                    "ON messages(package_name, received_at DESC, id DESC)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS conversation_received_idx " +
                    "ON messages(conversation_key, received_at DESC, id DESC)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS kind_received_idx " +
                    "ON messages(message_kind, received_at DESC, id DESC)"
            )
        }
        if (oldVersion < 6) {
            // v0.4.6: the canonical conversation layer.
            //
            // ADDITIVE ONLY. `messages` is never altered, no row is rewritten,
            // and the historical `conversation_key` values are left exactly as
            // they are — that value is an input to the dedup fingerprint and to
            // the DHC V1 message id, so rewriting it would break deduplication
            // for every existing row.
            //
            // Idempotent: every DDL is IF NOT EXISTS and the back-fill only
            // considers legacy keys that have no alias yet, so a crash between
            // the DDL and the commit simply re-runs on the next open.
            ConversationStore.createSchema(db)
            ConversationStore.backfillAliases(db)
        }
    }

    /**
     * One inbox thread, grouped by CANONICAL conversation id.
     *
     * The old UI grouped the CURRENT PAGE of messages by legacy key, which is
     * wrong twice: a conversation whose rows span a page boundary appears twice,
     * and a conversation that only has older rows can be missed entirely. This
     * read model is computed by SQL over the WHOLE table, so grouping is correct
     * at any page size.
     *
     * The preview is NOT decrypted here: decrypting every row to build a list
     * would be O(n) AES operations. `latestSealedTitle` is provided so the UI can
     * decrypt just the visible page's previews through CryptoStore.
     */
    data class ConversationSummary(
        val canonicalConversationId: String,   // "cv_…" when linked, else the legacy key
        val packageName: String,
        val displayTitle: String,              // most recent decrypted title, set by the caller
        val latestMessageTimestamp: Long,      // received_at of the newest row
        val latestMessageId: Long,
        val messageCount: Int,
        val ambiguityState: String,            // provenance of the best alias
        val latestSealedContent: ByteArray
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * One page of conversations, newest message first.
     *
     * Grouping key: the canonical link when the message has one, otherwise the
     * legacy conversation_key. Unlinked historical rows therefore stay visible
     * under their own legacy key instead of disappearing.
     *
     * Stable sort: latestReceivedAt DESC, then the grouping id, so identical
     * timestamps do not shuffle across page boundaries.
     *
     * No decryption happens in this query.
     */
    fun conversationPage(
        limit: Int = 50,
        offset: Int = 0,
        packageName: String? = null,
        /**
         * P2: a keyset cursor — only conversations strictly OLDER than this
         * (latestReceived, gid) pair are returned.
         *
         * Offset paging over a LIVE table is unstable: a new message arriving in
         * an already-loaded conversation moves it to the top and shifts every
         * later row down by one, so the next offset page skips a conversation.
         * The keyset cursor is immune to that, because it is anchored to the
         * last row actually seen rather than to a position.
         */
        afterLatestReceived: Long? = null,
        afterGid: String? = null
    ): List<ConversationSummary> {
        val bounded = limit.coerceIn(1, 200)
        val db = readableDatabase
        // P2: inside a HAVING clause SQLite does NOT apply the column's INTEGER
        // affinity to a TEXT-bound parameter, so
        // `MAX(m.received_at) < '1700000031000'` compares TEXT against INTEGER
        // and never matches -- the keyset filter silently returned the same page
        // forever. The cursor values are internal Longs (never user input), so
        // they are inlined as numeric literals to keep the comparison numeric.
        val args = ArrayList<String>()
        // The grouping key: canonical when linked, legacy otherwise. The title is
        // only decrypted for the FINAL page rows, in the caller's loop, so the
        // SQL must carry the sealed bytes of each conversation's newest message.
        val sql = buildString {
            append(
                "SELECT g.gid, g.package_name, g.message_count, g.latest_id, " +
                "       g.latest_received, m.sealed_content, a.strength " +
                "FROM ("
            )
            append(
                "SELECT COALESCE(l.conversation_id, 'k:' || m.conversation_key) AS gid, " +
                "       m.package_name AS package_name, " +
                "       count(*) AS message_count, " +
                "       MAX(m.id) AS latest_id, " +
                "       MAX(m.received_at) AS latest_received " +
                "FROM messages m " +
                "LEFT JOIN message_conversation l ON l.message_id = m.id "
            )
            // (the sub-query is the derived table `g`)
            val predicates = ArrayList<String>()
            if (packageName != null) {
                predicates.add("m.package_name = ?"); args.add(packageName)
            }
            if (predicates.isNotEmpty()) {
                append("WHERE ").append(predicates.joinToString(" AND ")).append(' ')
            }
            append("GROUP BY gid, m.package_name ")
            // P2: the keyset predicate belongs on the AGGREGATED rows, inside
            // the derived table and BEFORE the LIMIT. Applying it outside would
            // filter an already-truncated page and repeat rows across pages.
            // It is anchored to the last (latest_received, gid) actually seen, so
            // a conversation that jumps to the top on a new insert cannot make
            // the next page skip one.
            if (afterLatestReceived != null && afterGid != null) {
                // The two timestamps are Longs from our own cursor, never user
                // input, so inlining them is safe and keeps the comparison
                // numeric (see the note on `args` above). The gid is bound.
                append(
                    "HAVING (MAX(m.received_at) < $afterLatestReceived OR " +
                        "        (MAX(m.received_at) = $afterLatestReceived AND " +
                        "         COALESCE(l.conversation_id, 'k:' || m.conversation_key) > ?)) "
                )
                args.add(afterGid)
            }
            append(
                "ORDER BY latest_received DESC, gid " +
                "LIMIT ? OFFSET ?) AS g"
            )
            args.add(bounded.toString()); args.add(offset.toString())
            // IR-04: the alias lookup MUST NOT multiply rows. A conversation can
            // own more than one SHORTCUT alias (two shortcut ids resolving to the
            // same conversation, or a re-issued shortcut), and a plain
            // LEFT JOIN would then emit one row PER alias for the same
            // conversation -- inflating the page and repeating conversations.
            // The join is therefore pinned to exactly ONE alias row, chosen
            // deterministically (STRONG before WEAK, then by value). This fixes
            // the one-to-many relation itself; DISTINCT is deliberately NOT used,
            // because it would hide the bad aggregation instead of correcting it.
            append(
                " JOIN messages m ON m.id = g.latest_id " +
                "LEFT JOIN conversation_aliases a ON a.rowid = (" +
                "  SELECT a2.rowid FROM conversation_aliases a2 " +
                "   WHERE a2.package_name = g.package_name " +
                "     AND a2.alias_type = 'SHORTCUT' " +
                "     AND a2.conversation_id = g.gid " +
                "   ORDER BY CASE a2.strength WHEN 'STRONG' THEN 0 ELSE 1 END, a2.alias_value " +
                "   LIMIT 1) "
            )
            append("ORDER BY g.latest_received DESC, g.gid")
        }
        val out = ArrayList<ConversationSummary>(bounded)
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ConversationSummary(
                        canonicalConversationId = c.getString(0),
                        packageName = c.getString(1),
                        displayTitle = "",          // decrypted by the caller
                        latestMessageTimestamp = c.getLong(4),
                        latestMessageId = c.getLong(3),
                        messageCount = c.getInt(2),
                        ambiguityState = c.getString(6) ?: "AMBIGUOUS",
                        latestSealedContent = c.getBlob(5)
                    )
                )
            }
        }
        return out
    }

    /** Total distinct conversations (for the "load more" affordance). */
    fun conversationCountAll(packageName: String? = null): Int {
        val db = readableDatabase
        val args = ArrayList<String>()
        val sql = buildString {
            append(
                "SELECT count(*) FROM (SELECT COALESCE(l.conversation_id, 'k:' || m.conversation_key) AS gid " +
                "FROM messages m LEFT JOIN message_conversation l ON l.message_id = m.id "
            )
            if (packageName != null) { append("WHERE m.package_name = ? "); args.add(packageName) }
            append("GROUP BY gid)")
        }
        return db.rawQuery(sql, args.toTypedArray()).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
    }

    /**
     * The messages of one canonical conversation, newest first, paged.
     *
     * Rows are matched through the canonical link when present, plus any legacy
     * key that alias-resolves to the same conversation, so a conversation whose
     * rows predate the linking still shows up.
     */
    fun readConversationMessages(
        canonicalId: String,
        limit: Int = 50,
        beforeReceivedAt: Long? = null,
        beforeId: Long? = null
    ): ReadResult {
        // Every LEGACY_KEY alias that maps to this conversation, as hex so the
        // NUL separators inside the key survive (SQLite truncates a bound String
        // at the first NUL). ONLY LEGACY_KEY aliases may be compared against
        // messages.conversation_key: they are the same string. A SHORTCUT alias
        // holds just the shortcut id, a COMPONENT of the key, and hex-matching
        // it would find nothing (and must never match).
        val keyHexes = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT hex(alias_value) FROM conversation_aliases " +
                "WHERE conversation_id = ? AND alias_type = 'LEGACY_KEY'",
            arrayOf(canonicalId)
        ).use { c ->
            while (c.moveToNext()) keyHexes.add(c.getString(0))
        }
        // IR-02: the message ids explicitly LINKED to this conversation. These
        // are the authoritative membership signal, and they exist even when no
        // LEGACY_KEY alias was ever written (a partial migration, or a merge
        // that moved links without re-writing aliases).
        //
        // P2 (independent review): this list used to be read UNBOUNDED and then
        // turned into one `?` per id. SQLite's default bound-variable limit was
        // 999 until SQLite 3.32 (2020-05-02), which ships on API 31+; minSdk=28
        // still runs on devices where the limit is genuinely 999, so a
        // conversation with more than the device's variable limit threw.
        // The membership test is now an EXISTS sub-select instead of an IN
        // list, so the number of messages never affects the number of
        // placeholders on ANY device.
        val hasAnyLink = readableDatabase.rawQuery(
            "SELECT 1 FROM message_conversation WHERE conversation_id = ? LIMIT 1",
            arrayOf(canonicalId)
        ).use { c -> c.moveToFirst() }
        // IR-02 FAIL-CLOSED: with neither an alias nor a link there is NO
        // evidence that any row belongs to this conversation. Returning an empty
        // page is the only safe answer. The previous implementation built an
        // empty WHERE clause in that case, which silently read (and decrypted)
        // the ENTIRE messages table -- a cross-conversation disclosure.
        if (keyHexes.isEmpty() && !hasAnyLink) {
            android.util.Log.d("READMODEL", "fail-closed: unknown canonicalId=$canonicalId")
            return ReadResult(emptyList(), CryptoStore.KeyState.KEY_AVAILABLE, 0, false)
        }
        val output = ArrayList<Entry>()
        var worst = CryptoStore.KeyState.KEY_AVAILABLE
        var unreadable = 0
        val bounded = limit.coerceIn(1, 500)
        val fetch = bounded + 1
        val selection = StringBuilder()
        val args = mutableListOf<String>()
        // Membership is the UNION of the two signals. A row is in scope when its
        // legacy key alias-resolves to this conversation OR it is explicitly
        // linked to it. Neither branch can widen beyond this conversation.
        if (keyHexes.isNotEmpty()) {
            args.addAll(keyHexes)
            selection.append("hex(conversation_key) IN (${keyHexes.joinToString(",") { "?" }})")
        }
        if (hasAnyLink) {
            if (selection.isNotEmpty()) selection.append(" OR ")
            // EXISTS keeps the placeholder count constant regardless of how many
            // messages the conversation holds.
            selection.append(
                "EXISTS (SELECT 1 FROM message_conversation mc " +
                    "WHERE mc.message_id = messages.id AND mc.conversation_id = ?)"
            )
            args.add(canonicalId)
        }
        if (beforeReceivedAt != null && beforeId != null) {
            // Wrap the membership expression in its own parentheses so the OR
            // can never escape and combine with the cursor predicate.
            val membership = selection.toString()
            selection.setLength(0)
            selection.append("(").append(membership).append(")")
            selection.append(" AND (received_at < ? OR (received_at = ? AND id < ?))")
            args += beforeReceivedAt.toString()
            args += beforeReceivedAt.toString()
            args += beforeId.toString()
        }
        android.util.Log.d("READMODEL", "sql=${"SELECT id FROM messages WHERE $selection"} args=$args")
        readableDatabase.rawQuery(
            "SELECT id, package_name, received_at, source_ts, sealed_content, " +
                "sealed_image, conversation_key, message_kind FROM messages " +
                "WHERE $selection " +
                "ORDER BY received_at DESC, id DESC LIMIT $fetch",
            args.toTypedArray()
        ).use { c ->
            var __n = 0
            while (c.moveToNext()) {
                __n++
                val pkg = c.getString(1)
                val res = CryptoStore.openWithState(pkg, c.getBlob(4))
                if (res.plaintext == null) {
                    unreadable++
                    if (res.state.isUnrecoverable) worst = res.state
                    else if (worst == CryptoStore.KeyState.KEY_AVAILABLE) worst = res.state
                    continue
                }
                try {
                    val decoded = JSONObject(String(res.plaintext, Charsets.UTF_8))
                    val key = c.getString(6)?.takeIf { it.isNotEmpty() }
                        ?: legacyConversationKey(pkg, decoded.optString("title"))
                    output += Entry(c.getLong(0), pkg, c.getLong(2), c.getLong(3),
                        decoded.optString("title"), decoded.optString("sender"),
                        decoded.optString("body"), !c.isNull(5),
                        key, c.getString(7) ?: SourceRegistry.MessageKind.UNKNOWN.name)
                } catch (_: Exception) {
                    unreadable++
                    if (worst == CryptoStore.KeyState.KEY_AVAILABLE) {
                        worst = CryptoStore.KeyState.DECRYPT_FAILED
                    }
                }
            }
            android.util.Log.d("READMODEL", "rawRows=$__n output=${output.size}")
        }
        val finalState = if (worst.isUnrecoverable) CryptoStore.KeyState.RECOVERY_REQUIRED
                         else worst
        val hasMore = output.size > bounded
        if (hasMore) output.removeAt(output.size - 1)
        return ReadResult(output, finalState, unreadable, hasMore)
    }

    /**
     * IR-01: decrypt exactly ONE message row by id.
     *
     * The inbox renders one row per CONVERSATION, showing that conversation's
     * newest message. `conversationPage()` already carries the newest id and its
     * sealed bytes, but the display title/sender/body must be decrypted, and the
     * sealed blob alone does not say which package it belongs to. This reads the
     * single row and decrypts it — O(1), never a table scan.
     */
    /**
     * IR-01 P1: decrypt one conversation's newest message AND report the worst
     * key state seen.
     *
     * The IR-01 rewrite moved the inbox onto `conversationPage()` and, in doing
     * so, dropped the only assignments to the Activity's `keyState` /
     * `unreadableRows`. That silently disabled the key-recovery UI: an
     * invalidated Keystore key was never surfaced, and the diagnostics panel
     * always claimed KEY_AVAILABLE. The per-conversation decrypt is now the only
     * place the inbox touches ciphertext, so it is where the signal must come
     * from.
     */
    data class ConversationPreview(
        val entry: Entry?,
        /** Worst key state observed while decrypting this preview. */
        val keyState: CryptoStore.KeyState,
        /** 1 when the row existed but could not be decrypted. */
        val unreadable: Int
    )

    fun entryForMessageId(id: Long): Entry? = conversationPreview(id).entry

    fun conversationPreview(id: Long): ConversationPreview {
        readableDatabase.rawQuery(
            "SELECT id, package_name, received_at, source_ts, sealed_content, " +
                "sealed_image, conversation_key, message_kind FROM messages WHERE id = ?",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) {
                return ConversationPreview(null, CryptoStore.KeyState.KEY_AVAILABLE, 0)
            }
            val pkg = c.getString(1)
            val res = CryptoStore.openWithState(pkg, c.getBlob(4))
            val plain = res.plaintext
                ?: return ConversationPreview(null, res.state, 1)
            val decoded = runCatching {
                JSONObject(String(plain, Charsets.UTF_8))
            }.getOrNull()
                ?: return ConversationPreview(null, CryptoStore.KeyState.DECRYPT_FAILED, 1)
            val key = c.getString(6)?.takeIf { it.isNotEmpty() }
                ?: legacyConversationKey(pkg, decoded.optString("title"))
            return ConversationPreview(
                Entry(
                    c.getLong(0), pkg, c.getLong(2), c.getLong(3),
                    decoded.optString("title"), decoded.optString("sender"),
                    decoded.optString("body"), !c.isNull(5),
                    key, c.getString(7) ?: SourceRegistry.MessageKind.UNKNOWN.name
                ),
                res.state,
                0
            )
        }
    }

    /**
     * IR-01 P2: read an UNLINKED conversation's messages by its legacy key.
     *
     * `conversationPage()` groups unlinked rows under `"k:" + conversation_key`.
     * The detail screen must be able to open such a group, and
     * `readConversationMessages()` (correctly) refuses a canonical id with
     * neither an alias nor a link. This reads by the legacy key itself, using
     * the same hex comparison that survives the NUL separators.
     */
    fun readMessagesByLegacyKey(legacyKey: String, limit: Int = 100): ReadResult {
        val bounded = limit.coerceIn(1, 500)
        val fetch = bounded + 1
        val keyHex = legacyKey.toByteArray(Charsets.UTF_8)
            .joinToString("") { "%02X".format(it) }
        val output = ArrayList<Entry>()
        var worst = CryptoStore.KeyState.KEY_AVAILABLE
        var unreadable = 0
        readableDatabase.rawQuery(
            "SELECT id, package_name, received_at, source_ts, sealed_content, " +
                "sealed_image, conversation_key, message_kind FROM messages " +
                "WHERE hex(conversation_key) = ? " +
                "ORDER BY received_at DESC, id DESC LIMIT $fetch",
            arrayOf(keyHex)
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(1)
                val res = CryptoStore.openWithState(pkg, c.getBlob(4))
                val plain = res.plaintext
                if (plain == null) {
                    unreadable++
                    if (res.state.isUnrecoverable) worst = res.state
                    else if (worst == CryptoStore.KeyState.KEY_AVAILABLE) worst = res.state
                    continue
                }
                val decoded = runCatching {
                    JSONObject(String(plain, Charsets.UTF_8))
                }.getOrNull()
                if (decoded == null) {
                    unreadable++
                    if (worst == CryptoStore.KeyState.KEY_AVAILABLE) {
                        worst = CryptoStore.KeyState.DECRYPT_FAILED
                    }
                    continue
                }
                val key = c.getString(6)?.takeIf { it.isNotEmpty() }
                    ?: legacyConversationKey(pkg, decoded.optString("title"))
                output += Entry(
                    c.getLong(0), pkg, c.getLong(2), c.getLong(3),
                    decoded.optString("title"), decoded.optString("sender"),
                    decoded.optString("body"), !c.isNull(5),
                    key, c.getString(7) ?: SourceRegistry.MessageKind.UNKNOWN.name
                )
            }
        }
        val finalState = if (worst.isUnrecoverable) CryptoStore.KeyState.RECOVERY_REQUIRED
                         else worst
        val hasMore = output.size > bounded
        if (hasMore) output.removeAt(output.size - 1)
        return ReadResult(output, finalState, unreadable, hasMore)
    }

    /**
     * The query plan for a statement, for the index evaluation.
     *
     * `EXPLAIN QUERY PLAN` returns one row per step. The plan is returned as
     * a single string so a test can assert on it.
     */
    fun queryPlan(sql: String, args: Array<String> = emptyArray()): String =
        runCatching {
            readableDatabase.rawQuery("EXPLAIN QUERY PLAN $sql", args).use { c ->
                val sb = StringBuilder()
                while (c.moveToNext()) {
                    if (sb.isNotEmpty()) sb.append(" | ")
                    sb.append(c.getString(3))
                }
                sb.toString()
            }
        }.getOrDefault("")

    /**
     * The indexes that exist on the messages table.
     */
    fun indexNames(): List<String> = runCatching {
        readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='messages'",
            null
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
    }.getOrDefault(emptyList())

    /** Outcome of a single insert attempt, so callers can distinguish "stored",
     *  "deduped" and "failed" without touching the encrypted rows themselves. */
    sealed class SaveResult {
        /**
         * IR-03: a NEW row, carrying the ACTUAL id SQLite assigned.
         *
         * Canonical linking used to rediscover this id with
         * `WHERE conversation_key = ? ORDER BY id DESC LIMIT <batch size>`.
         * That is a guess: `conversation_key` contains NUL separators (SQLite
         * truncates a bound String at the first NUL, so the comparison could
         * silently match the wrong rows), and "newest N rows with this key"
         * also picks up rows written by a CONCURRENT notification for the same
         * conversation. The insert already returns the real id -- carrying it
         * removes the guess entirely.
         */
        class NEW(val id: Long) : SaveResult()

        /** A row that already existed. [id] is the existing row id when known. */
        class DUPLICATE(val id: Long = -1L) : SaveResult()

        class FAILURE(val cause: Throwable) : SaveResult()
    }

    fun save(packageName: String, receivedAt: Long, sourceTimestamp: Long,
             title: String, sender: String, body: String, image: ByteArray?,
             conversationKey: String = "",
             messageKind: String = SourceRegistry.MessageKind.UNKNOWN.name,
             messageDiscriminator: String = ""): SaveResult {
        return try {
            val json = JSONObject().put("title", title).put("sender", sender)
                .put("body", body).put("sourceTs", sourceTimestamp).toString()
                .toByteArray(Charsets.UTF_8)
            // Dedup key.
            //
            // The key must identify a MESSAGE, not a notification revision.
            //
            // The previous implementation fell back to the CAPTURE TIME when
            // the source provided no timestamp. The capture time is per
            // NOTIFICATION, so every message inside one group notification
            // produced the SAME key and collapsed into a single row: a burst of
            // 100 group messages was stored as one message. That is the
            // mechanism behind "the group burst is missing messages".
            //
            // The fallback is now the message DISCRIMINATOR: a per-message
            // value derived from the message itself (its position in the
            // notification's message list and its content). It is stable
            // across revisions of the same notification — the same message
            // keeps its position — and it differs between two distinct
            // messages, even when their text is identical.
            //
            // When the source DOES provide a timestamp, that timestamp is used
            // exactly as before, so the fingerprint of an already-stored
            // message is unchanged and it is not captured a second time.
            val hasSourceTimestamp = sourceTimestamp > 0
            val dedupTs = if (hasSourceTimestamp) sourceTimestamp else 0L
            // The conversation key is part of the key, NOT the display title.
            //
            // Two different conversations can share a display name -- that is
            // exactly why ConversationIdentity exists and why the schema has a
            // conversation_key column. Using the title in the key meant two
            // same-named groups in the same app, from the same sender, with the
            // same text, at the same millisecond, collapsed into one row.
            val conversationPart = conversationKey.ifEmpty { title }
            // The discriminator is ALWAYS included.
            //
            // It used to be dropped when the source provided a timestamp, on
            // the reasoning that the timestamp identifies the message. It does
            // not: the source timestamp is per message but has MILLISECOND
            // resolution, so two messages sent in the same millisecond
            // collide. The discriminator distinguishes them, and it is stable
            // across revisions of the same notification, so including it does
            // not cause a re-capture.
            val fingerprint = CryptoStore.fingerprint(
                packageName, dedupTs, conversationPart, "$sender\u0000$body",
                messageDiscriminator
            )
            // NOTE ON THE WRITE AMPLIFICATION
            //
            // A group notification is delivered as a growing history, so every
            // revision re-presents every message it has ever carried. For a
            // 500-message group that is 9,500 messages presented for 500
            // distinct ones, and each one used to cost an AES-GCM operation
            // before the duplicate was discovered.
            //
            // The amplification is removed at the SOURCE, in the listener,
            // which only submits the messages that are new in this revision.
            // That is both cheaper and more correct than a second lookup here:
            // the listener already knows which messages it has written.
            //
            // A pre-encryption duplicate lookup would need the fingerprint
            // bound as a BLOB, which the query API only accepts as a string.
            // Making it work would need an extra indexed column and a schema
            // migration, which is not worth the risk for a check the listener
            // already performs.
            val db = writableDatabase

            // The duplicate check comes BEFORE the encryption.
            //
            // A group notification is delivered as a growing history, so every
            // revision re-presents every message it has ever carried. For a
            // 500-message group, that is 9,500 messages presented for 500
            // distinct ones. Sealing first cost an AES-GCM operation per
            // PRESENTED message, and 90% of them were thrown away.
            //
            // The fingerprint is an HMAC over a short string, so checking it
            // first is cheap. The measurement: with 500 messages the queue
            // reached a depth of 476 and the write wait hit 39 seconds, and
            // 3,821 messages were never accounted for.
            val protectedImage = image?.let { CryptoStore.seal("$packageName:image", it) }
            val cv = ContentValues().apply {
                put("package_name", packageName)
                put("received_at", receivedAt)
                put("source_ts", sourceTimestamp)
                put("conversation_key", conversationKey)
                put("message_kind", messageKind)
                put("fingerprint", fingerprint)
                put("sealed_content", CryptoStore.seal(packageName, json))
                if (protectedImage != null) put("sealed_image", protectedImage)
            }
            val inserted = db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            when {
                inserted != -1L -> SaveResult.NEW(inserted)
                protectedImage != null -> {
                    // Photo appeared in a follow-up edit of the *same* notification.
                    db.execSQL(
                        "UPDATE messages SET sealed_image=? WHERE fingerprint=? AND sealed_image IS NULL",
                        arrayOf<Any>(protectedImage, fingerprint)
                    )
                    SaveResult.DUPLICATE(existingIdForFingerprint(db, fingerprint))
                }
                else -> SaveResult.DUPLICATE(existingIdForFingerprint(db, fingerprint))
            }
        } catch (e: Exception) {
            SaveResult.FAILURE(e)
        }
    }


    /**
     * The id of an existing row with [fingerprint], or -1 when it cannot be
     * resolved. Used only to REPORT a duplicate's id; never to decide what to
     * link (NEW rows carry their id directly).
     */
    private fun existingIdForFingerprint(db: SQLiteDatabase, fingerprint: ByteArray): Long =
        runCatching {
            db.rawQuery(
                "SELECT id FROM messages WHERE fingerprint = ? LIMIT 1",
                arrayOf(String(fingerprint, Charsets.ISO_8859_1))
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        }.getOrDefault(-1L)

    /**
     * One row to write, in a batch.
     *
     * The fields are the same ones [save] takes. They are collected into a
     * data holder so a batch can be written without repeating the long
     * parameter list at every call site.
     */
    data class PendingMessage(
        val packageName: String,
        val receivedAt: Long,
        val sourceTimestamp: Long,
        val title: String,
        val sender: String,
        val body: String,
        val image: ByteArray?,
        val conversationKey: String = "",
        val messageKind: String = SourceRegistry.MessageKind.UNKNOWN.name,
        val messageDiscriminator: String = ""
    )

    /**
     * Write several groups of messages in ONE transaction.
     *
     * The measurement that motivated this: every [save] is its own
     * transaction, and each transaction costs an fsync. The burst benchmark
     * measured 59 ms per insert at the median, so a 500-message burst spent
     * roughly 30 SECONDS waiting for the disk -- and that wait is the queue
     * wait, because the single writer thread could not drain the queue.
     *
     * ONE transaction for the WHOLE group turns N fsyncs into one. The rows
     * are identical and the per-row result is identical; only the commit
     * boundaries change.
     *
     * The groups are kept separate in the result so the caller can still
     * report each notification's outcome separately.
     *
     * The transaction is bounded by the caller: it commits whatever it has
     * when the call ends, so a failure cannot leave a transaction open.
     */
    fun saveBatches(groups: List<List<PendingMessage>>): List<List<SaveResult>> {
        if (groups.isEmpty()) return emptyList()
        val results = ArrayList<List<SaveResult>>(groups.size)
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (group in groups) {
                val groupResults = ArrayList<SaveResult>(group.size)
                for (m in group) {
                    groupResults.add(
                        save(
                            m.packageName, m.receivedAt, m.sourceTimestamp,
                            m.title, m.sender, m.body, m.image,
                            m.conversationKey, m.messageKind, m.messageDiscriminator
                        )
                    )
                }
                results.add(groupResults)
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            // The transaction is rolled back. Every result is reported as a
            // failure, so the caller knows that nothing in this call was
            // committed.
            results.clear()
            for (group in groups) {
                results.add(List(group.size) { SaveResult.FAILURE(e) })
            }
        } finally {
            db.endTransaction()
        }
        return results
    }

    /**
     * Read the most recent messages, decrypting each sealed payload.
     *
     * The key state is reported so the caller can distinguish "this row is
     * corrupt" from "the encryption key is gone". When the key is invalidated
     * the rows are NOT silently dropped: the caller is told that recovery is
     * required, and the unreadable rows stay in the database until the user
     * explicitly chooses to remove them.
     */
    fun recent(limit: Int = 1000): List<Entry> = recentWithState(limit).entries

    /** The outcome of a read: the decrypted rows plus the worst key state seen. */
    data class ReadResult(
        val entries: List<Entry>,
        val keyState: CryptoStore.KeyState,
        /** Number of rows that could not be decrypted. */
        val unreadableRows: Int,
        /** True when another page follows this one. */
        val hasMore: Boolean = false
    ) {
        /**
         * The cursor for the NEXT page.
         *
         * It is the (received_at, id) pair of the last row, which is stable
         * even when new rows arrive, because the id breaks the tie in the
         * ordering.
         */
        fun nextCursor(): Pair<Long, Long>? {
            val last = entries.lastOrNull() ?: return null
            return last.receivedAt to last.id
        }
    }

    fun recentWithState(limit: Int = 1000): ReadResult =
        readPage(limit = limit, beforeReceivedAt = null, beforeId = null, conversationKey = null)

    /**
     * Read ONE page of messages.
     *
     * The unbounded read decrypts every row in the table, which is why the
     * first screen took seconds with a few thousand messages. A page reads
     * only what is shown.
     *
     * The cursor is the (received_at, id) pair of the LAST row of the
     * previous page. It is stable: rows are ordered by received_at DESC,
     * id DESC, and the id breaks the tie, so a row keeps its position even
     * when new rows arrive above it.
     *
     * @param limit the maximum number of rows to return
     * @param beforeReceivedAt the received_at of the cursor, or null for the
     *        first page
     * @param beforeId the id of the cursor, or null for the first page
     * @param conversationKey when set, only this conversation is returned
     */
    fun readPage(
        limit: Int = 50,
        beforeReceivedAt: Long? = null,
        beforeId: Long? = null,
        conversationKey: String? = null
    ): ReadResult {
        val output = ArrayList<Entry>()
        var worst = CryptoStore.KeyState.KEY_AVAILABLE
        var unreadable = 0
        val bounded = limit.coerceIn(1, 500)
        // One extra row is fetched so the caller can tell that there IS
        // another page, without a second COUNT query.
        val fetch = bounded + 1
        val selection = buildString {
            if (beforeReceivedAt != null && beforeId != null) {
                append("(received_at < ? OR (received_at = ? AND id < ?))")
            }
            if (conversationKey != null) {
                if (isNotEmpty()) append(" AND ")
                append("conversation_key = ?")
            }
        }
        // The selection arguments are strings: SQLite's query API only binds
        // strings, and the values are numbers and a text key, so they are
        // formatted rather than bound. The values are internal, never user
        // input, and the key is a database column value.
        val args = mutableListOf<String>()
        if (beforeReceivedAt != null && beforeId != null) {
            args += beforeReceivedAt.toString()
            args += beforeReceivedAt.toString()
            args += beforeId.toString()
        }
        if (conversationKey != null) args += conversationKey
        readableDatabase.query("messages",
            arrayOf("id", "package_name", "received_at", "source_ts", "sealed_content",
                "sealed_image", "conversation_key", "message_kind"),
            selection.takeIf { it.isNotEmpty() }?.toString(),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null, null, "received_at DESC, id DESC", fetch.toString()
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(1)
                val res = CryptoStore.openWithState(pkg, c.getBlob(4))
                if (res.plaintext == null) {
                    unreadable++
                    if (res.state.isUnrecoverable) worst = res.state
                    else if (worst == CryptoStore.KeyState.KEY_AVAILABLE) worst = res.state
                    continue
                }
                try {
                    val decoded = JSONObject(String(res.plaintext, Charsets.UTF_8))
                    val title = decoded.optString("title")
                    // Rows captured before schema v4 have an empty conversation_key.
                    // Fall back to the legacy package+title key so old data keeps
                    // grouping exactly as it did, without a destructive rewrite.
                    val key = c.getString(6)?.takeIf { it.isNotEmpty() }
                        ?: legacyConversationKey(pkg, title)
                    output += Entry(c.getLong(0), pkg, c.getLong(2), c.getLong(3),
                        title, decoded.optString("sender"),
                        decoded.optString("body"), !c.isNull(5),
                        key, c.getString(7) ?: SourceRegistry.MessageKind.UNKNOWN.name)
                } catch (_: Exception) {
                    // A corrupt row is skipped; its content is never logged.
                    unreadable++
                    if (worst == CryptoStore.KeyState.KEY_AVAILABLE) {
                        worst = CryptoStore.KeyState.DECRYPT_FAILED
                    }
                }
            }
        }
        val finalState = if (worst.isUnrecoverable) CryptoStore.KeyState.RECOVERY_REQUIRED
                         else worst
        // The extra row is dropped and reported as "there is more".
        val hasMore = output.size > bounded
        if (hasMore) output.removeAt(output.size - 1)
        return ReadResult(output, finalState, unreadable, hasMore)
    }

    companion object {
        // IR-05 repair state machine.
        /** Not attempted yet (also the state after a FAILED repair, so it retries). */
        const val REPAIR_NONE = 0
        /** A repair is currently running on another connection. */
        const val REPAIR_RUNNING = 1
        /** The schema was VERIFIED present; no further repair this process. */
        const val REPAIR_DONE = 2
        /** Number of canonical journal tables a complete repair produces. */
        const val CANONICAL_TABLE_COUNT = 6

        /** The grouping key used before schema v4: package + display title.
         *  Rows captured before the conversation_key column existed are grouped
         *  with this so old data keeps grouping exactly as it did, without a
         *  destructive rewrite. */
        fun legacyConversationKey(packageName: String, title: String): String =
            "$packageName\u0000$title"
    }

    fun loadImage(id: Long): ByteArray? {
        readableDatabase.query("messages", arrayOf("package_name", "sealed_image"),
            "id=?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            if (!c.moveToFirst() || c.isNull(1)) return null
            return CryptoStore.open("${c.getString(0)}:image", c.getBlob(1))
        }
    }

    /**
     * Delete the rows that can no longer be decrypted.
     *
     * This is deliberately NOT called automatically. It is invoked only from an
     * explicit, confirmed user action after the UI has explained that the
     * encryption key is gone and the affected messages are permanently
     * unreadable. The rows stay on disk until then, so a temporarily
     * unavailable key (device locked) never destroys anything.
     *
     * Returns the number of rows removed.
     */
    fun purgeUndecryptableRows(): Int {
        val db = writableDatabase
        var removed = 0
        db.query("messages", arrayOf("id", "package_name", "sealed_content"),
            null, null, null, null, null).use { c ->
            val doomed = ArrayList<Long>()
            while (c.moveToNext()) {
                val pkg = c.getString(1)
                val res = CryptoStore.openWithState(pkg, c.getBlob(2))
                if (res.plaintext == null) doomed += c.getLong(0)
            }
            for (id in doomed) {
                removed += db.delete("messages", "id=?", arrayOf(id.toString()))
            }
        }
        if (removed > 0) checkpointWal(db)
        return removed
    }

    /** Truncate the write-ahead log so encrypted sidecars cannot outlive a deletion.
     *  PRAGMA is a query, so it must go through rawQuery(), not execSQL(). */
    private fun checkpointWal(db: SQLiteDatabase) {
        runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
    }

    /**
     * Read every row as an EXPORT message.
     *
     * The export form carries the fields the export contract needs. The
     * bodies are decrypted, because the exporter has to encrypt them again
     * with the user's export passphrase; nothing leaves this process in the
     * clear.
     */
    fun readAllForExport(): List<tw.personal.privateinbox.dhc.DhcExporter.ExportedMessage> {
        val output = ArrayList<tw.personal.privateinbox.dhc.DhcExporter.ExportedMessage>()
        readableDatabase.query("messages",
            arrayOf("id", "package_name", "received_at", "source_ts", "sealed_content",
                "conversation_key", "message_kind"),
            null, null, null, null, "received_at DESC, id DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(1)
                val res = CryptoStore.openWithState(pkg, c.getBlob(4))
                val plaintext = res.plaintext ?: continue
                try {
                    val decoded = JSONObject(String(plaintext, Charsets.UTF_8))
                    val title = decoded.optString("title")
                    val key = c.getString(5)?.takeIf { it.isNotEmpty() }
                        ?: legacyConversationKey(pkg, title)
                    val sourceTs = c.getLong(3)
                    output += tw.personal.privateinbox.dhc.DhcExporter.ExportedMessage(
                        messageId = tw.personal.privateinbox.dhc.DhcExporter.computeMessageId(
                            conversationId = key,
                            sourceApplication = pkg,
                            sourceTimestamp = if (sourceTs > 0) sourceTs else null,
                            body = decoded.optString("body")
                        ),
                        conversationId = key,
                        sourceApplication = pkg,
                        messageKind = c.getString(6) ?: "UNKNOWN",
                        receivedAt = c.getLong(2),
                        sourceTimestamp = if (sourceTs > 0) sourceTs else null,
                        sender = decoded.optString("sender"),
                        body = decoded.optString("body"),
                        hasAttachment = false
                    )
                } catch (_: Exception) {
                    // A corrupt row is skipped. Its content is never logged.
                }
            }
        }
        return output
    }

    /**
     * Delete a single row by its id.
     *
     * @return true when a row was removed
     */
    fun deleteById(id: Long): Boolean = runCatching {
        writableDatabase.delete("messages", "id = ?", arrayOf(id.toString())) > 0
    }.getOrDefault(false)

    /**
     * Set the journal mode of the database file.
     *
     * The mode is a property of the FILE, not of the connection, so it is set
     * with a PRAGMA and it survives the handle being closed.
     *
     * @return the mode the database actually ended up in. SQLite refuses some
     *         transitions, so the effective value is read back rather than
     *         assumed.
     */
    fun setJournalMode(mode: String): String = runCatching {
        // The PRAGMA returns the EFFECTIVE mode, which may differ from the
        // requested one: SQLite refuses some transitions, for example out of
        // WAL while a transaction is open. The returned value is therefore the
        // one that is reported, never the one that was asked for.
        readableDatabase.rawQuery("PRAGMA journal_mode = $mode", null).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: mode else mode
        }
    }.getOrDefault(mode)

    /**
     * The journal mode the database is actually using.
     *
     * The single-writer guarantee depends on it: in WAL mode a reader never
     * blocks the writer, and a writer never blocks a reader.
     */
    fun journalMode(): String = runCatching {
        readableDatabase.rawQuery("PRAGMA journal_mode", null).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "unknown" else "unknown"
        }
    }.getOrDefault("unknown")

    /**
     * The number of rows in the table.
     *
     * The read path is capped, so the row count has to come from the database
     * itself when a test needs the real number.
     */
    fun countRows(): Int = runCatching {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }.getOrDefault(0)

    /**
     * The size of the database file on disk, in bytes.
     *
     * Used by the performance baseline to show how the storage grows with the
     * number of messages.
     */
    fun databaseSizeBytes(): Long = runCatching {
        val file = appContext.getDatabasePath("inbox.db")
        file.length()
    }.getOrDefault(0L)

    fun clear() {
        // The clear races the capture worker: the worker may be mid-flush (or
        // inside the retry backoff window) on ANOTHER connection when a test
        // tears down. A plain delete then dies with SQLITE_BUSY and every
        // later assertion in that test measures a poisoned database. The
        // write lock is held only for the duration of one DELETE, so a short
        // bounded retry is enough; anything longer is a real error.
        var lastError: Exception? = null
        repeat(10) {
            try {
                val db = writableDatabase
                db.beginTransaction()
                try {
                    // Order matters: the link rows reference messages, so they
                    // go first. The conversation layer is derived state — a
                    // "delete everything" that left stale conversations behind
                    // would make the next capture appear to join a group the
                    // user had already erased.
                    db.delete(ConversationStore.TABLE_MESSAGE_LINKS, null, null)
                    db.delete(ConversationStore.TABLE_MERGE_UNDO, null, null)
                    // The G1.5 journal tables must be wiped too, or a later
                    // "delete everything" leaves undo rows (and merge-log rows)
                    // behind that refer to conversations which no longer exist.
                    db.delete(ConversationStore.TABLE_MERGE_UNDO_ALIAS, null, null)
                    db.delete(ConversationStore.TABLE_MERGE_LOG, null, null)
                    db.delete(ConversationStore.TABLE_ALIASES, null, null)
                    db.delete(ConversationStore.TABLE_CONVERSATIONS, null, null)
                    db.delete("messages", null, null)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                checkpointWal(db)
                lastError = null
                return
            } catch (e: android.database.sqlite.SQLiteDatabaseLockedException) {
                lastError = e
                System.err.println("PrivateInbox: clear() waiting for the write lock ($it)")
                Thread.sleep(250)
            }
        }
        lastError?.let { throw it }
    }

    /** Delete a single captured message (its encrypted image blob is removed with the row). */
    fun deleteMessage(id: Long) {
        writableDatabase.delete("messages", "id=?", arrayOf(id.toString()))
    }

    /** Delete a whole conversation by the ids the caller already resolved in memory.
     * The title lives inside the encrypted content blob, so the UI computes the
     * message ids (grouped by package + title) and hands them in — no need to add a
     * plaintext title column or to decrypt rows here. WAL is truncated so the
     * encrypted sidecars cannot outlive the deletion. */
    fun deleteMessages(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val csv = ids.joinToString(",")
            db.execSQL("DELETE FROM messages WHERE id IN ($csv)")
            checkpointWal(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Retention policy: drop captured messages older than [days]; 0 keeps everything.
     * Returns the number of rows removed. */
    fun pruneOlderThanDays(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days.toLong() * 24L * 60L * 60L * 1000L
        val db = writableDatabase
        val removed = db.delete("messages", "received_at < ?", arrayOf(cutoff.toString()))
        if (removed > 0) checkpointWal(db)
        return removed
    }
}
