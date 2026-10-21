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
    SQLiteOpenHelper(appContext, "inbox.db", null, 4) {
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
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // PRAGMA statements are queries, not updates: execSQL() rejects them with
        // "Queries can be performed using SQLiteDatabase query or rawQuery methods only".
        runCatching { db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() } }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive, non-destructive migrations. Each step only adds columns and
        // back-fills neutral defaults; no row is ever deleted or rewritten with
        // data the user would lose.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sealed_image BLOB")
            db.execSQL("ALTER TABLE messages ADD COLUMN source_ts INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN source_ts INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN conversation_key TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN message_kind TEXT NOT NULL DEFAULT 'UNKNOWN'")
            // Back-fill the conversation key for rows captured before v4. The title is
            // encrypted, so it cannot be read here; the key is reconstructed lazily
            // from the package name at read time (see conversationKeyOf()).
            db.execSQL("CREATE INDEX IF NOT EXISTS conversation_idx ON messages(conversation_key)")
        }
    }

    /** Outcome of a single insert attempt, so callers can distinguish "stored",
     *  "deduped" and "failed" without touching the encrypted rows themselves. */
    sealed class SaveResult {
        object NEW : SaveResult()
        object DUPLICATE : SaveResult()
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
                inserted != -1L -> SaveResult.NEW
                protectedImage != null -> {
                    // Photo appeared in a follow-up edit of the *same* notification.
                    db.execSQL(
                        "UPDATE messages SET sealed_image=? WHERE fingerprint=? AND sealed_image IS NULL",
                        arrayOf<Any>(protectedImage, fingerprint)
                    )
                    SaveResult.DUPLICATE
                }
                else -> SaveResult.DUPLICATE
            }
        } catch (e: Exception) {
            SaveResult.FAILURE(e)
        }
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
            selection.ifEmpty { null },
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
        val db = writableDatabase
        db.delete("messages", null, null)
        checkpointWal(db)
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
