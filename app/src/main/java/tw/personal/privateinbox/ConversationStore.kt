package tw.personal.privateinbox

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/**
 * The canonical conversation layer (schema v6).
 *
 * ## The problem this solves
 *
 * v0.4.5 grouped the inbox by `messages.conversation_key`, a string built as
 * `pkg \0 (shortcutId ?: title) \0 title`. Because the title was part of the
 * key, renaming a contact split one chat into two boxes; and because a missing
 * shortcut fell back to the title, two same-named groups could collapse into
 * one. Both are identity bugs, not display bugs.
 *
 * ## The constraint that shapes everything here
 *
 * `conversation_key` is an INPUT to the dedup fingerprint
 * ([CryptoStore.fingerprintInput]) and to the DHC V1 message id. It is therefore
 * **immutable**. This class never rewrites it, never touches `messages`, and
 * stores the canonical mapping in SIDE TABLES:
 *
 * ```
 * conversations            one row per canonical conversation
 * conversation_aliases     (pkg, type, value) -> conversation_id
 * message_conversation     messages.id -> conversation_id
 * ```
 *
 * The canonical id is allocated once and mapped. It is never derived from a hash
 * of mutable data, because that would reintroduce "rename changes identity".
 *
 * ## Ambiguity is a first-class outcome
 *
 * When the source supplies no shortcut id, the only signal is a display title,
 * which can change and can collide. That is recorded as a WEAK alias and the
 * resolution is reported ambiguous. The system never merges two conversations on
 * weak evidence, and never presents a weak grouping as certain.
 */
class ConversationStore(private val appContext: Context) : AliasLookup {

    companion object {
        const val TABLE_CONVERSATIONS = "conversations"
        const val TABLE_ALIASES = "conversation_aliases"
        const val TABLE_MESSAGE_LINKS = "message_conversation"
        const val TABLE_MERGE_UNDO = "message_conversation_undo"
        const val TABLE_MERGE_UNDO_ALIAS = "conversation_alias_undo"
        const val TABLE_MERGE_LOG = "conversation_merge_log"

        const val ALIAS_SHORTCUT = "SHORTCUT"
        const val ALIAS_LEGACY_KEY = "LEGACY_KEY"
        const val ALIAS_MANUAL = "MANUAL"

        const val STRENGTH_STRONG = "STRONG"
        const val STRENGTH_WEAK = "WEAK"

        private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
            var found = false
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == column) found = true
                }
            }
            return found
        }

        /** Create the v6 tables. Idempotent: every statement is IF NOT EXISTS. */
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_CONVERSATIONS (
                conversation_id TEXT PRIMARY KEY,
                package_name    TEXT NOT NULL,
                is_group        INTEGER NOT NULL DEFAULT 0,
                display_title   TEXT NOT NULL DEFAULT '',
                created_at      INTEGER NOT NULL,
                merged_into     TEXT
            )""".trimIndent())
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS conversations_pkg_idx " +
                    "ON $TABLE_CONVERSATIONS(package_name)"
            )
            db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_ALIASES (
                package_name    TEXT NOT NULL,
                alias_type      TEXT NOT NULL,
                alias_value     TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                strength        TEXT NOT NULL,
                created_at      INTEGER NOT NULL,
                PRIMARY KEY (package_name, alias_type, alias_value)
            )""".trimIndent())
            // v0.4.6 G2.5: the FULL provenance, kept in its own column so the
            // historical `strength` values ("STRONG"/"WEAK") stay untouched for
            // older readers. `strength` remains the compatibility view; this
            // column is the evidence. Rows written before this column existed
            // keep NULL here, which reads back as AMBIGUOUS — the conservative
            // fallback the brief requires (historical STRONG must NOT be
            // silently reinterpreted as PLATFORM_CONFIRMED).
            if (!columnExists(db, TABLE_ALIASES, "provenance")) {
                db.execSQL(
                    "ALTER TABLE $TABLE_ALIASES ADD COLUMN provenance TEXT"
                )
            }
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS conversation_aliases_cid_idx " +
                    "ON $TABLE_ALIASES(conversation_id)"
            )
            db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_MESSAGE_LINKS (
                message_id      INTEGER PRIMARY KEY,
                conversation_id TEXT NOT NULL
            )""".trimIndent())
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS message_conversation_cid_idx " +
                    "ON $TABLE_MESSAGE_LINKS(conversation_id)"
            )
            // Undo journal for a merge. One row per MESSAGE LINK plus one row per
            // ALIAS, all tagged with the merge's batch id, so an unmerge restores
            // the exact previous state — including alias ownership, which the
            // first version of this class failed to restore.
            db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_MERGE_UNDO (
                conversation_id TEXT NOT NULL,
                message_id      INTEGER NOT NULL,
                batch           TEXT NOT NULL,
                PRIMARY KEY (conversation_id, message_id)
            )""".trimIndent())
            db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_MERGE_UNDO_ALIAS (
                batch           TEXT NOT NULL,
                package_name    TEXT NOT NULL,
                alias_type      TEXT NOT NULL,
                alias_value     TEXT NOT NULL,
                original_owner  TEXT NOT NULL,
                PRIMARY KEY (batch, package_name, alias_type, alias_value)
            )""".trimIndent())
            db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_MERGE_LOG (
                batch           TEXT PRIMARY KEY,
                source          TEXT NOT NULL,
                target          TEXT NOT NULL,
                created_at      INTEGER NOT NULL,
                undone          INTEGER NOT NULL DEFAULT 0
            )""".trimIndent())
        }

        /**
         * Back-fill aliases for every distinct legacy `conversation_key`.
         *
         * Runs in ONE transaction and is safe to re-run: it only considers keys
         * that have no LEGACY_KEY alias yet.
         *
         * ## The only automatic merge
         *
         * Two legacy keys may map to the SAME canonical conversation **only** when
         * both parse to the same non-empty shortcut id (the historical key carries
         * the shortcut in its middle slot). That is precisely the v0.4.5 rename
         * bug: `pkg\0sc-1\0Alice` and `pkg\0sc-1\0Alice Chen` are one
         * conversation. Everything else stays separate.
         */
        fun backfillAliases(db: SQLiteDatabase, now: Long = System.currentTimeMillis()): Int {
            val keys = ArrayList<Pair<String, String>>() // package -> legacy key
            db.rawQuery(
                "SELECT DISTINCT package_name, conversation_key FROM messages " +
                    "WHERE conversation_key != ''",
                null
            ).use { c ->
                while (c.moveToNext()) keys.add(c.getString(0) to c.getString(1))
            }
            if (keys.isEmpty()) return 0

            val existing = HashSet<String>() // "$pkg\u0000$key"
            db.rawQuery(
                "SELECT package_name, alias_value FROM $TABLE_ALIASES WHERE alias_type = ?",
                arrayOf(ALIAS_LEGACY_KEY)
            ).use { c ->
                while (c.moveToNext()) existing.add("${c.getString(0)}\u0000${c.getString(1)}")
            }

            // shortcut id -> conversation id, so two legacy keys with the same
            // shortcut converge on one canonical conversation (the rename fix).
            val shortcutOwner = HashMap<String, String>() // "$pkg\u0000$sc" -> cid
            db.rawQuery(
                "SELECT package_name, alias_value, conversation_id FROM $TABLE_ALIASES " +
                    "WHERE alias_type = ?",
                arrayOf(ALIAS_SHORTCUT)
            ).use { c ->
                while (c.moveToNext()) {
                    shortcutOwner["${c.getString(0)}\u0000${c.getString(1)}"] = c.getString(2)
                }
            }

            var created = 0
            db.beginTransaction()
            try {
                for ((pkg, key) in keys) {
                    val id = "$pkg\u0000$key"
                    if (id in existing) continue
                    val parsed = LegacyKeyCodec.parse(key)
                    val shortcut = parsed.middle.takeIf { parsed.middleLooksLikeShortcut }
                    val scKey = shortcut?.let { "$pkg\u0000$it" }
                    val cid = scKey?.let { shortcutOwner[it] }
                        ?: newConversationId(db)
                    // A shortcut parsed out of a STORED key is an inference about
                    // a string, not a platform assertion — even though the same
                    // shortcut arriving on a live notification WOULD be
                    // PLATFORM_CONFIRMED. The distinction is the whole point of
                    // the taxonomy, so the back-fill records the weaker evidence.
                    val strength = if (shortcut != null) {
                        Strength.LEGACY_INFERRED.storedValue
                    } else {
                        Strength.TITLE_ONLY.storedValue
                    }

                    insertConversationIfMissing(db, cid, pkg, parsed.title, now)
                    insertAlias(db, pkg, ALIAS_LEGACY_KEY, key, cid, strength, now,
                        provenance = if (shortcut != null)
                            Strength.LEGACY_INFERRED.name else Strength.AMBIGUOUS.name)
                    if (shortcut != null) {
                        insertAlias(db, pkg, ALIAS_SHORTCUT, shortcut, cid,
                            Strength.LEGACY_INFERRED.storedValue, now,
                            provenance = Strength.LEGACY_INFERRED.name)
                        shortcutOwner["$pkg\u0000$shortcut"] = cid
                    }
                    existing.add(id)
                    created++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return created
        }

        private fun newConversationId(db: SQLiteDatabase): String = "cv_" + UUID.randomUUID()

        private fun insertConversationIfMissing(
            db: SQLiteDatabase, cid: String, pkg: String, title: String, now: Long
        ) {
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_CONVERSATIONS " +
                    "(conversation_id, package_name, is_group, display_title, created_at) " +
                    "VALUES (?, ?, 0, ?, ?)",
                arrayOf(cid, pkg, title, now)
            )
        }

        private fun insertAlias(
            db: SQLiteDatabase, pkg: String, type: String, value: String,
            cid: String, strength: String, now: Long,
            provenance: String? = null
        ) {
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_ALIASES " +
                    "(package_name, alias_type, alias_value, conversation_id, strength, " +
                    " created_at, provenance) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(pkg, type, value, cid, strength, now, provenance)
            )
        }
    }

    // ------------------------------------------------------------ AliasLookup

    override fun find(pkg: String, type: AliasType, value: String): String? {
        readable().rawQuery(
            "SELECT conversation_id FROM $TABLE_ALIASES " +
                "WHERE package_name = ? AND alias_type = ? AND alias_value = ?",
            arrayOf(pkg, type.name, value)
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    override fun put(
        pkg: String, type: AliasType, value: String,
        conversationId: String, strength: Strength
    ) {
        val db = writable()
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_CONVERSATIONS " +
                    "(conversation_id, package_name, is_group, display_title, created_at) " +
                    "VALUES (?, ?, 0, '', ?)",
                arrayOf(conversationId, pkg, System.currentTimeMillis())
            )
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE_ALIASES " +
                    "(package_name, alias_type, alias_value, conversation_id, strength, " +
                    " created_at, provenance) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(pkg, type.name, value, conversationId, strength.storedValue,
                    System.currentTimeMillis(), strength.name)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun newConversationId(): String = "cv_" + UUID.randomUUID()

    // ------------------------------------------------------------ linking

    /** Record which canonical conversation a stored message belongs to. */
    fun linkMessage(messageId: Long, conversationId: String) {
        writable().execSQL(
            "INSERT OR REPLACE INTO $TABLE_MESSAGE_LINKS (message_id, conversation_id) " +
                "VALUES (?, ?)",
            arrayOf(messageId, conversationId)
        )
    }

    /** The canonical conversation of a message, or null when never linked. */
    fun conversationOf(messageId: Long): String? {
        readable().rawQuery(
            "SELECT conversation_id FROM $TABLE_MESSAGE_LINKS WHERE message_id = ?",
            arrayOf(messageId.toString())
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    /**
     * Link every unlinked message to a conversation derived from its own legacy
     * key. Used after a migration so old rows appear under a canonical id.
     *
     * Rows whose legacy key has no alias are given one by the same rule as
     * [backfillAliases] (shortcut => strong, otherwise weak/ambiguous).
     */
    fun linkAllMessages(): Int {
        val db = writable()
        var linked = 0
        db.beginTransaction()
        try {
            val pending = ArrayList<Triple<Long, String, String>>() // id, pkg, key
            db.rawQuery(
                "SELECT m.id, m.package_name, m.conversation_key FROM messages m " +
                    "LEFT JOIN $TABLE_MESSAGE_LINKS l ON l.message_id = m.id " +
                    "WHERE l.message_id IS NULL",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    pending.add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
                }
            }
            for ((id, pkg, key) in pending) {
                val cid = find(pkg, AliasType.LEGACY_KEY, key) ?: continue
                db.execSQL(
                    "INSERT OR REPLACE INTO $TABLE_MESSAGE_LINKS " +
                        "(message_id, conversation_id) VALUES (?, ?)",
                    arrayOf(id, cid)
                )
                linked++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return linked
    }

    // ------------------------------------------------------------ merge

    /**
     * Merge [from] into [into]. Messages move; `messages` rows are untouched.
     *
     * A snapshot of the previous links is written first, so [unmerge] restores
     * the exact prior state. Returns the number of message links repointed.
     */
    fun merge(from: String, into: String, batchId: String = UUID.randomUUID().toString()): Int {
        // --- validation (no partial work is ever started on a bad request) ---
        if (from == into) return 0                     // self-merge: no-op
        val db = writable()

        val fromPkg = packageOf(db, from)
            ?: return -1                               // unknown source
        val intoPkg = packageOf(db, into)
            ?: return -1                               // unknown target
        if (fromPkg != intoPkg) return -1               // cross-package: refused
        if (isMergedAway(db, from) || isMergedAway(db, into)) {
            // Refuse to build a chain on top of an already-merged conversation:
            // that is how a cycle becomes possible.
            if (isMergedAway(db, from)) return -1
        }
        if (wouldCycle(db, from, into)) return -1

        var moved = 0
        db.beginTransaction()
        try {
            // 1. Journal the message links.
            db.execSQL(
                "INSERT OR REPLACE INTO $TABLE_MERGE_UNDO (conversation_id, message_id, batch) " +
                    "SELECT conversation_id, message_id, ? FROM $TABLE_MESSAGE_LINKS " +
                    "WHERE conversation_id = ?",
                arrayOf(batchId, from)
            )
            // 2. Journal the ALIAS ownership — this is what unmerge was missing.
            db.execSQL(
                "INSERT OR REPLACE INTO $TABLE_MERGE_UNDO_ALIAS " +
                    "(batch, package_name, alias_type, alias_value, original_owner) " +
                    "SELECT ?, package_name, alias_type, alias_value, conversation_id " +
                    "FROM $TABLE_ALIASES WHERE conversation_id = ?",
                arrayOf(batchId, from)
            )
            // 3. Log the merge itself.
            db.execSQL(
                "INSERT OR REPLACE INTO $TABLE_MERGE_LOG " +
                    "(batch, source, target, created_at, undone) VALUES (?,?,?,?,0)",
                arrayOf(batchId, from, into, System.currentTimeMillis())
            )

            db.rawQuery(
                "SELECT count(*) FROM $TABLE_MESSAGE_LINKS WHERE conversation_id = ?",
                arrayOf(from)
            ).use { c -> if (c.moveToFirst()) moved = c.getInt(0) }

            // 4. Repoint links and aliases atomically.
            db.execSQL(
                "UPDATE $TABLE_MESSAGE_LINKS SET conversation_id = ? WHERE conversation_id = ?",
                arrayOf(into, from)
            )
            db.execSQL(
                "UPDATE $TABLE_ALIASES SET conversation_id = ? WHERE conversation_id = ?",
                arrayOf(into, from)
            )
            db.execSQL(
                "UPDATE $TABLE_CONVERSATIONS SET merged_into = ? WHERE conversation_id = ?",
                arrayOf(into, from)
            )
            db.setTransactionSuccessful()
        } catch (e: Throwable) {
            // endTransaction() without setTransactionSuccessful rolls back.
            moved = -1
        } finally {
            db.endTransaction()
        }
        return moved
    }

    /**
     * Undo the merge that moved [mergedAway] away.
     *
     * Restores BOTH the message links and the alias ownership, using the batch
     * journal written by [merge]. Without the alias restore, a later notification
     * for the unmerged conversation would resolve into the target — silently
     * re-merging what the user just separated.
     *
     * Returns the number of message links restored, or 0 when there is nothing to
     * undo (idempotent).
     */
    fun unmerge(mergedAway: String): Int {
        val db = writable()
        var restored = 0
        db.beginTransaction()
        try {
            val batch = db.rawQuery(
                "SELECT batch FROM $TABLE_MERGE_LOG WHERE source = ? AND undone = 0",
                arrayOf(mergedAway)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return 0

            // 1. Restore message links.
            db.rawQuery(
                "SELECT message_id FROM $TABLE_MERGE_UNDO WHERE batch = ?",
                arrayOf(batch)
            ).use { c ->
                while (c.moveToNext()) {
                    db.execSQL(
                        "UPDATE $TABLE_MESSAGE_LINKS SET conversation_id = ? WHERE message_id = ?",
                        arrayOf(mergedAway, c.getLong(0))
                    )
                    restored++
                }
            }

            // 2. Restore ALIAS ownership — the defect this fix addresses.
            db.execSQL(
                "UPDATE $TABLE_ALIASES SET conversation_id = (" +
                    "SELECT u.original_owner FROM $TABLE_MERGE_UNDO_ALIAS u " +
                    "WHERE u.package_name = $TABLE_ALIASES.package_name " +
                    "  AND u.alias_type = $TABLE_ALIASES.alias_type " +
                    "  AND u.alias_value = $TABLE_ALIASES.alias_value " +
                    "  AND u.batch = ?) " +
                    "WHERE EXISTS (SELECT 1 FROM $TABLE_MERGE_UNDO_ALIAS u " +
                    "  WHERE u.package_name = $TABLE_ALIASES.package_name " +
                    "    AND u.alias_type = $TABLE_ALIASES.alias_type " +
                    "    AND u.alias_value = $TABLE_ALIASES.alias_value " +
                    "    AND u.batch = ?)",
                arrayOf(batch, batch)
            )

            // 3. Clear the merged flag and mark the batch undone.
            db.execSQL(
                "UPDATE $TABLE_CONVERSATIONS SET merged_into = NULL WHERE conversation_id = ?",
                arrayOf(mergedAway)
            )
            db.execSQL("UPDATE $TABLE_MERGE_LOG SET undone = 1 WHERE batch = ?", arrayOf(batch))
            db.setTransactionSuccessful()
        } catch (e: Throwable) {
            restored = -1
        } finally {
            db.endTransaction()
        }
        return restored
    }

    /** Where [cid] was merged into, or null when it stands alone. */
    fun mergedInto(cid: String): String? {
        readable().rawQuery(
            "SELECT merged_into FROM $TABLE_CONVERSATIONS WHERE conversation_id = ?",
            arrayOf(cid)
        ).use { c -> return if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }

    private fun packageOf(db: SQLiteDatabase, cid: String): String? =
        db.rawQuery(
            "SELECT package_name FROM $TABLE_CONVERSATIONS WHERE conversation_id = ?",
            arrayOf(cid)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun isMergedAway(db: SQLiteDatabase, cid: String): Boolean =
        db.rawQuery(
            "SELECT merged_into FROM $TABLE_CONVERSATIONS WHERE conversation_id = ?",
            arrayOf(cid)
        ).use { c -> c.moveToFirst() && !c.isNull(0) }

    /** True when merging [from] into [into] would close a cycle. */
    private fun wouldCycle(db: SQLiteDatabase, from: String, into: String): Boolean {
        var cur: String? = into
        var hops = 0
        while (cur != null && hops < 64) {
            if (cur == from) return true
            cur = db.rawQuery(
                "SELECT merged_into FROM $TABLE_CONVERSATIONS WHERE conversation_id = ?",
                arrayOf(cur)
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
            hops++
        }
        return false
    }

    /** Register an alias, reporting a conflict instead of silently ignoring it. */
    fun putAliasChecked(
        pkg: String, type: AliasType, value: String,
        conversationId: String, strength: Strength
    ): AliasPutResult {
        val existing = find(pkg, type, value)
        if (existing != null && existing != conversationId) return AliasPutResult.CONFLICT
        put(pkg, type, value, conversationId, strength)
        return if (existing == null) AliasPutResult.INSERTED else AliasPutResult.ALREADY_SAME
    }

    // ------------------------------------------------------------ queries

    /** Number of canonical conversations for a package (excluding merged-away). */
    fun conversationCount(pkg: String): Int {
        readable().rawQuery(
            "SELECT count(*) FROM $TABLE_CONVERSATIONS " +
                "WHERE package_name = ? AND merged_into IS NULL",
            arrayOf(pkg)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * The stored provenance of an alias — the FULL taxonomy name, or null when
     * the row predates the column. Callers must treat null as AMBIGUOUS: an old
     * "STRONG" row must not be silently reinterpreted as PLATFORM_CONFIRMED.
     */
    fun provenanceOf(pkg: String, type: AliasType, value: String): Strength {
        readable().rawQuery(
            "SELECT provenance FROM $TABLE_ALIASES " +
                "WHERE package_name = ? AND alias_type = ? AND alias_value = ?",
            arrayOf(pkg, type.name, value)
        ).use { c ->
            if (!c.moveToFirst()) return Strength.AMBIGUOUS
            return if (c.isNull(0)) Strength.AMBIGUOUS
            else Strength.fromStored(c.getString(0))
        }
    }

    /** The alias rows for one conversation: (type, value, stored strength). */
    fun aliasesOf(cid: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        readable().rawQuery(
            "SELECT alias_type, alias_value, strength FROM $TABLE_ALIASES " +
                "WHERE conversation_id = ?",
            arrayOf(cid)
        ).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getString(0), c.getString(1), c.getString(2)))
        }
        return out
    }

    /**
     * True when the conversation rests on weak evidence.
     *
     * Certain provenance is `PLATFORM_CONFIRMED` (a live shortcut) or
     * `MANUAL_CONFIRMED` (a user decision). The historical `STRONG` value is
     * still honoured because older rows contain it.
     */
    fun isAmbiguous(cid: String): Boolean =
        aliasesOf(cid).none { Strength.fromStored(it.third).isStrong }

    private fun readable(): SQLiteDatabase = helper.readableDatabase
    private fun writable(): SQLiteDatabase = helper.writableDatabase

    /**
     * The open helper.
     *
     * `SQLiteOpenHelper` pools connections per instance, so creating a new
     * helper per call would open a second pool against the same file and make
     * `SQLITE_BUSY` far more likely under the capture worker. One helper per
     * store instance, and callers are expected to reuse the store.
     */
    private val helper = InboxDatabase(appContext)

    /** Release the pooled connection. Safe to call more than once. */
    fun close() {
        runCatching { helper.close() }
    }
}
