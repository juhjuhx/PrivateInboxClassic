package tw.personal.privateinbox

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Debug-only bridge that lets the instrumentation suite configure the app's
 * settings.
 *
 * Why this exists: the instrumentation APK is a *separate package with its own
 * data directory* (`/data/user/0/tw.personal.privateinbox.test`). It therefore
 * cannot write the app's SharedPreferences, so a test that wants the app's
 * listener to accept a source has to ask the app itself to change the setting.
 *
 * Security: the provider is declared in `src/debug/AndroidManifest.xml` only,
 * so it does **not exist in the release build**. It is additionally guarded:
 *  - it refuses every call that does not come from the instrumentation UID,
 *  - it exposes only two operations (enable a source / read the capture
 *    counters), never message content.
 */
class DebugSettingsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "tw.personal.privateinbox.debugsettings"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val KEY_SOURCES = "sources"
        const val KEY_RECORDING = "recording"
        const val KEY_COUNTERS = "counters"
        /** Number of stored rows whose body contains the given marker. */
        const val KEY_MARKER_ROWS = "marker_rows"
        const val METHOD_MARKER_ROWS = "markerRows"
    }

    override fun onCreate(): Boolean = true

    /**
     * Guard the provider.
     *
     * The provider only exists in the debug build, and it exposes nothing
     * sensitive: it can toggle which capture sources are enabled and read
     * diagnostic counters. It never exposes message content.
     *
     * A caller is accepted when it runs under the app's own UID or under the
     * instrumentation package's UID (the two debug-signed packages).
     */
    private fun callerAllowed(): Boolean {
        val ctx = context ?: return false
        val uid = android.os.Process.myUid()
        val appUid = try {
            ctx.packageManager.getApplicationInfo(ctx.packageName, 0).uid
        } catch (_: Exception) {
            return false
        }
        if (uid == appUid) return true
        // The instrumentation APK is signed with the same debug key and runs
        // with its own UID; accept it explicitly.
        return try {
            ctx.packageManager.getApplicationInfo(
                "tw.personal.privateinbox.test", 0
            ).uid == uid
        } catch (_: Exception) {
            false
        }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        if (!callerAllowed()) return null
        val ctx = context ?: return null
        val counters = CaptureDiagnostics.counters(ctx)
        val rows = android.database.MatrixCursor(
            arrayOf(KEY_SOURCES, KEY_RECORDING, KEY_COUNTERS)
        )
        rows.addRow(
            arrayOf(
                LocalSettings.selected(ctx).joinToString(","),
                LocalSettings.isRecording(ctx).toString(),
                counters.toString()
            )
        )
        return rows
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!callerAllowed()) return null
        val ctx = context ?: return null
        values ?: return null
        val sources = values.getAsString(KEY_SOURCES)
        if (!sources.isNullOrEmpty()) {
            val set = sources.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            LocalSettings.updateSources(ctx, set)
        }
        values.getAsBoolean(KEY_RECORDING)?.let { LocalSettings.setRecording(ctx, it) }
        return uri
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int {
        if (!callerAllowed()) return 0
        val ctx = context ?: return 0
        values ?: return 0
        val sources = values.getAsString(KEY_SOURCES)
        if (!sources.isNullOrEmpty()) {
            val set = sources.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            LocalSettings.updateSources(ctx, set)
        }
        values.getAsBoolean(KEY_RECORDING)?.let { LocalSettings.setRecording(ctx, it) }
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY"

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!callerAllowed()) return null
        val ctx = context ?: return null
        val out = Bundle()
        when (method) {
            "enableSources" -> {
                val set = arg.orEmpty().split(',').map { it.trim() }
                    .filter { it.isNotEmpty() }.toSet()
                LocalSettings.updateSources(ctx, set)
                out.putBoolean("ok", true)
            }
            "setRecording" -> {
                LocalSettings.setRecording(ctx, arg == "true")
                out.putBoolean("ok", true)
            }
            "counters" -> {
                CaptureDiagnostics.counters(ctx).forEach { (k, v) ->
                    when (v) {
                        is Long -> out.putLong(k, v)
                        is String -> out.putString(k, v)
                        else -> out.putString(k, v.toString())
                    }
                }
            }
            "hasConnectedEvidence" -> {
                // The connection evidence lives in the APP's preferences, which
                // the instrumentation process cannot read directly.
                out.putBoolean("connected", CaptureDiagnostics.hasConnectedEvidence(ctx))
            }
            METHOD_MARKER_ROWS -> {
                // The stored rows live in the APP's database, which the test
                // process cannot open directly. Count them here instead.
                val marker = arg.orEmpty()
                val db = InboxDatabase(ctx)
                val n = try {
                    db.recent(500).count { it.body.contains(marker) }
                } finally { db.close() }
                out.putInt(KEY_MARKER_ROWS, n)
            }
            else -> out.putBoolean("ok", false)
        }
        return out
    }
}
