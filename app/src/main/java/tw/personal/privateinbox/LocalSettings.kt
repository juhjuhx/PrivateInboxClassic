package tw.personal.privateinbox

import android.content.Context
import java.io.File

object LocalSettings {
    private const val NAME = "local_config"
    private const val SOURCES = "selected_source_packages"
    private const val RECORDING = "recording_enabled"
    private const val IMAGES = "capture_media_enabled"
    private const val RETENTION_DAYS = "retention_days"
    private const val SEEN = "thread_last_seen"

    /**
     * The set of enabled source packages.
     *
     * Defensive on purpose: `getStringSet` throws a ClassCastException when the
     * stored value has the wrong type (for example after a manual edit of the
     * preferences XML, or a partial write). That would crash the notification
     * listener service and silently stop all capture, so the read is guarded and
     * a malformed value degrades to "no sources selected" instead of taking the
     * app down.
     *
     * The read is TARGETED rather than going through `.all`, because `.all`
     * builds a map of EVERY entry in the preferences file -- including the
     * persisted diagnostics event list, which is the largest value in there.
     * The notification callback calls this on the main thread, once per
     * notification, so the difference matters.
     *
     * A cache was tried and REMOVED: keeping one correct required a
     * SharedPreferences change listener, and that listener would want the
     * LocalSettings monitor while SharedPreferences held its own lock. That is
     * a lock-ordering hazard on a path that runs on the main thread, and a
     * deadlock there would stop ALL capture. A targeted read is safe and
     * costs almost nothing.
     */
    fun selected(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        // The typed read first: it is targeted and does not build the whole
        // preferences map.
        val typed = try {
            prefs.getStringSet(SOURCES, null)
        } catch (_: ClassCastException) {
            // The stored value has the wrong type. Fall through to the
            // tolerant path below.
            null
        }
        if (typed != null) return typed.filterIsInstance<String>().toSet()

        // The tolerant path: inspect the raw value without throwing.
        val raw = prefs.all[SOURCES]
        return when (raw) {
            is Set<*> -> raw.filterIsInstance<String>().toSet()
            is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            null -> emptySet()
            else -> emptySet()
        }
    }

    fun updateSources(context: Context, sources: Set<String>) {
        val accepted = sources.intersect(SourceRegistry.all.map { it.packageName }.toSet())
        // putStringSet keeps a reference to the set it is given. Storing the
        // live instance means a later mutation of the caller's set would
        // silently change what is persisted, so a defensive copy is stored.
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(SOURCES, accepted.toSet()).apply()
    }

    fun isRecording(context: Context): Boolean = context
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getBoolean(RECORDING, false)

    fun captureImages(context: Context): Boolean = context
        .getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(IMAGES, false)

    fun setCaptureImages(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(IMAGES, enabled).apply()
    }

    fun setRecording(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(RECORDING, enabled).apply()
    }

    /** 0 = keep forever; otherwise prune messages older than this many days. */
    fun retentionDays(context: Context): Int = context
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getInt(RETENTION_DAYS, 0)

    fun setRetentionDays(context: Context, days: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putInt(RETENTION_DAYS, days.coerceAtLeast(0)).apply()
    }

    /** Approximate private-disk footprint of the encrypted vault, in bytes. */
    fun storageBytes(context: Context): Long {
        val dbFile = context.getDatabasePath("inbox.db")
        var total = 0L
        // DB main + WAL + SHM sidecar files.
        listOf(dbFile, File(dbFile.parentFile, dbFile.name + "-wal"),
            File(dbFile.parentFile, dbFile.name + "-shm"))
            .forEach { if (it.exists()) total += it.length() }
        total += context.filesDir?.let { fileLength(it) } ?: 0L
        total += context.cacheDir?.let { fileLength(it) } ?: 0L
        return total
    }

    /** Timestamp of the last time the user opened a thread, used for the
     *  "captured since you last looked" badge. 0 means never opened. */
    fun lastSeen(context: Context, threadKey: String): Long = context
        .getSharedPreferences(SEEN, Context.MODE_PRIVATE)
        .getLong(threadKey, 0L)

    fun markSeen(context: Context, threadKey: String, at: Long) {
        context.getSharedPreferences(SEEN, Context.MODE_PRIVATE)
            .edit().putLong(threadKey, at).apply()
    }

    private fun fileLength(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.fold(0L) { acc, f -> acc + f.length() }
}
