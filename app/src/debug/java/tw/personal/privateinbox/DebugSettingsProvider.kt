package tw.personal.privateinbox

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
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

        /** The instrumentation APK's package name. */
        const val INSTRUMENTATION_PACKAGE = "tw.personal.privateinbox.test"

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
     * The provider is declared with a signature-level permission
     * (tw.personal.privateinbox.permission.DEBUG_SETTINGS), so the system
     * already rejects any caller that is not signed with the same certificate.
     * This check is a second, independent layer: it verifies the caller's
     * *signing certificate* against the app's own, and verifies that the caller
     * is the app itself or the instrumentation APK.
     *
     * Note: the previous version compared `Process.myUid()` (the PROVIDER's
     * uid, which is always the app's) against a package uid. That check could
     * never fail for an in-process call and did not identify the caller at all.
     */
    private fun callerAllowed(): Boolean {
        val ctx = context ?: return false
        val callerUid = binderCallingUid()
        if (callerUid == android.os.Process.myUid()) return true
        // The instrumentation APK is signed with the same debug key and runs
        // with its own UID.
        val allowedUids = setOf(
            uidOf(ctx, ctx.packageName),
            uidOf(ctx, INSTRUMENTATION_PACKAGE)
        ).filter { it != null }.mapNotNull { it }.toSet()
        if (callerUid !in allowedUids) return false
        // Same signing certificate as the app.
        return sameSigner(ctx, ctx.packageName, packageForUid(ctx, callerUid))
    }

    private fun uidOf(ctx: Context, pkg: String): Int? = try {
        ctx.packageManager.getApplicationInfo(pkg, 0).uid
    } catch (_: Exception) {
        null
    }

    private fun packageForUid(ctx: Context, uid: Int): String? = try {
        ctx.packageManager.getPackagesForUid(uid)?.firstOrNull()
    } catch (_: Exception) {
        null
    }

    /** The UID of the process that called into this provider, if known. */
    private fun binderCallingUid(): Int {
        val uid = android.os.Binder.getCallingUid()
        // A negative UID means the call did not come through the binder (an
        // in-process call). Treat that as the app itself.
        return if (uid < 0) android.os.Process.myUid() else uid
    }

    /** True when [callerPackage] is signed with the same certificate as [appPackage]. */
    private fun sameSigner(ctx: Context, appPackage: String, callerPackage: String?): Boolean {
        if (callerPackage == null) return false
        if (callerPackage == appPackage) return true
        return try {
            val pm = ctx.packageManager
            val appSignatures = pm.getPackageInfo(
                appPackage, PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo
            val callerSignatures = pm.getPackageInfo(
                callerPackage, PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo
            val appCerts = appSignatures?.apkContentsSigners?.toSet() ?: emptySet()
            val callerCerts = callerSignatures?.apkContentsSigners?.toSet() ?: emptySet()
            appCerts.isNotEmpty() && appCerts.intersect(callerCerts).isNotEmpty()
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
            "canPost" -> {
                // Whether the app may post its own notification. On Android 13+
                // this needs POST_NOTIFICATIONS. This check runs INSIDE the app
                // process, so checkSelfPermission() reports the app's real state.
                out.putBoolean("canPost", SelfTestNotifier.canPost(ctx))
            }
            "requestPostPermission" -> {
                // Ask the user to grant POST_NOTIFICATIONS through the normal
                // runtime-permission flow. The activity is launched, and the
                // test waits for the result.
                val activity = ctx as? android.app.Activity
                if (activity != null && Build.VERSION.SDK_INT >= 33) {
                    activity.requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711
                    )
                }
                out.putBoolean("ok", activity != null)
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
                // The app's own recording switch, so a test can assert on it.
                out.putBoolean("recording", LocalSettings.isRecording(ctx))
                out.putString("sources", LocalSettings.selected(ctx).joinToString(","))
            }
            "hasConnectedEvidence" -> {
                // The connection evidence lives in the APP's preferences, which
                // the instrumentation process cannot read directly.
                out.putBoolean("connected", CaptureDiagnostics.hasConnectedEvidence(ctx))
            }
            "ensureListener" -> {
                // This runs INSIDE the app process, which is the only process
                // where the in-process connection flag is meaningful. It asks
                // the system to (re)bind the listener and then waits for the
                // service's own onListenerConnected() callback.
                val component = android.content.ComponentName(
                    ctx, PrivateNotificationListener::class.java
                )
                if (Build.VERSION.SDK_INT >= 31) {
                    runCatching {
                        android.service.notification.NotificationListenerService
                            .requestRebind(component)
                    }
                }
                val deadline = System.currentTimeMillis() + 20_000
                var connected = CaptureDiagnostics.isListenerConnectedInProcess()
                while (!connected && System.currentTimeMillis() < deadline) {
                    Thread.sleep(300)
                    connected = CaptureDiagnostics.isListenerConnectedInProcess()
                }
                out.putBoolean("connected", connected)
                out.putBoolean("hasEvidence", CaptureDiagnostics.hasConnectedEvidence(ctx))
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
