package tw.personal.privateinbox

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** V2: private image previews are also AES-GCM encrypted. Source package and times remain plain. */
class InboxDatabase(context: Context) : SQLiteOpenHelper(context, "inbox.db", null, 2) {
    data class Entry(
        val id: Long, val packageName: String, val receivedAt: Long,
        val title: String, val sender: String, val body: String, val hasImage: Boolean
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            package_name TEXT NOT NULL,
            received_at INTEGER NOT NULL,
            fingerprint BLOB NOT NULL UNIQUE,
            sealed_content BLOB NOT NULL,
            sealed_image BLOB
        )""".trimIndent())
        db.execSQL("CREATE INDEX received_idx ON messages(received_at DESC)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA secure_delete=ON")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion >= 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sealed_image BLOB")
            return
        }
        throw IllegalStateException("Missing migration $oldVersion to $newVersion")
    }

    fun save(packageName: String, receivedAt: Long, messageTimestamp: Long,
             title: String, sender: String, body: String, image: ByteArray?) {
        val json = JSONObject().put("title", title).put("sender", sender).put("body", body)
            .toString().toByteArray(Charsets.UTF_8)
        val fingerprint = CryptoStore.fingerprint(packageName, messageTimestamp, title, "$sender\u0000$body")
        val protectedImage = image?.let { CryptoStore.seal("$packageName:image", it) }
        val cv = ContentValues().apply {
            put("package_name", packageName)
            put("received_at", receivedAt)
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
            arrayOf("id", "package_name", "received_at", "sealed_content", "sealed_image"),
            null, null, null, null, "received_at DESC, id DESC", limit.coerceIn(1, 2000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(1)
                try {
                    val decoded = JSONObject(String(CryptoStore.open(pkg, c.getBlob(3)), Charsets.UTF_8))
                    output += Entry(c.getLong(0), pkg, c.getLong(2),
                        decoded.optString("title"), decoded.optString("sender"),
                        decoded.optString("body"), !c.isNull(4))
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

    fun clear() {
        val db = writableDatabase
        db.delete("messages", null, null)
        db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    }
}
