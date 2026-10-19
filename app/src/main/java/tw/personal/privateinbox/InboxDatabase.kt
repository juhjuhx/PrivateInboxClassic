package tw.personal.privateinbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** V3: stores the source (original) notification timestamp separately from capture time.
 * Source package names and times remain plain metadata; content and images are AES-GCM sealed. */
class InboxDatabase(context: Context) : SQLiteOpenHelper(context, "inbox.db", null, 3) {
    data class Entry(
        val id: Long, val packageName: String, val receivedAt: Long,
        val sourceTimestamp: Long,
        val title: String, val sender: String, val body: String, val hasImage: Boolean
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            package_name TEXT NOT NULL,
            received_at INTEGER NOT NULL,
            source_ts INTEGER NOT NULL DEFAULT 0,
            fingerprint BLOB NOT NULL UNIQUE,
            sealed_content BLOB NOT NULL,
            sealed_image BLOB
        )""".trimIndent())
        db.execSQL("CREATE INDEX received_idx ON messages(received_at DESC)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // PRAGMA statements are queries, not updates: execSQL() rejects them with
        // "Queries can be performed using SQLiteDatabase query or rawQuery methods only".
        runCatching { db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() } }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        when (oldVersion) {
            1 -> {
                db.execSQL("ALTER TABLE messages ADD COLUMN sealed_image BLOB")
                db.execSQL("ALTER TABLE messages ADD COLUMN source_ts INTEGER NOT NULL DEFAULT 0")
            }
            2 -> db.execSQL("ALTER TABLE messages ADD COLUMN source_ts INTEGER NOT NULL DEFAULT 0")
            else -> throw IllegalStateException("Missing migration $oldVersion to $newVersion")
        }
    }

    fun save(packageName: String, receivedAt: Long, sourceTimestamp: Long,
             title: String, sender: String, body: String, image: ByteArray?) {
        val json = JSONObject().put("title", title).put("sender", sender)
            .put("body", body).put("sourceTs", sourceTimestamp).toString()
            .toByteArray(Charsets.UTF_8)
        // Dedup key: when the source provides no original timestamp (0), fall back to
        // the capture time so repeated posts of the identical notification dedup stably
        // across re-sends, without merging genuinely different messages.
        val dedupTs = if (sourceTimestamp > 0) sourceTimestamp else receivedAt
        val fingerprint = CryptoStore.fingerprint(packageName, dedupTs, title, "$sender\u0000$body")
        val protectedImage = image?.let { CryptoStore.seal("$packageName:image", it) }
        val cv = ContentValues().apply {
            put("package_name", packageName)
            put("received_at", receivedAt)
            put("source_ts", sourceTimestamp)
            put("fingerprint", fingerprint)
            put("sealed_content", CryptoStore.seal(packageName, json))
            if (protectedImage != null) put("sealed_image", protectedImage)
        }
        val db = writableDatabase
        val inserted = db.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted == -1L && protectedImage != null) {
            // Photo appeared in a follow-up edit of the *same* notification.
            db.execSQL(
                "UPDATE messages SET sealed_image=? WHERE fingerprint=? AND sealed_image IS NULL",
                arrayOf<Any>(protectedImage, fingerprint)
            )
        }
    }

    fun recent(limit: Int = 1000): List<Entry> {
        val output = ArrayList<Entry>()
        readableDatabase.query("messages",
            arrayOf("id", "package_name", "received_at", "source_ts", "sealed_content", "sealed_image"),
            null, null, null, null, "received_at DESC, id DESC", limit.coerceIn(1, 2000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(1)
                try {
                    val decoded = JSONObject(String(CryptoStore.open(pkg, c.getBlob(4)), Charsets.UTF_8))
                    output += Entry(c.getLong(0), pkg, c.getLong(2), c.getLong(3),
                        decoded.optString("title"), decoded.optString("sender"),
                        decoded.optString("body"), !c.isNull(5))
                } catch (_: Exception) { /* Ignore corrupt rows without logging content. */ }
            }
        }
        return output
    }

    fun loadImage(id: Long): ByteArray? {
        readableDatabase.query("messages", arrayOf("package_name", "sealed_image"),
            "id=?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            if (!c.moveToFirst() || c.isNull(1)) return null
            return CryptoStore.open("${c.getString(0)}:image", c.getBlob(1))
        }
    }

    /** Truncate the write-ahead log so encrypted sidecars cannot outlive a deletion.
     *  PRAGMA is a query, so it must go through rawQuery(), not execSQL(). */
    private fun checkpointWal(db: SQLiteDatabase) {
        runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }
    }

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
