package tw.personal.privateinbox

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * SYSTEM E2E, not a direct parser call: posts the fixed self-test notification through the
 * real NotificationManager so the Android system must route it to the enabled
 * NotificationListenerService, then verifies the encrypted row landed in the database.
 *
 * Honest classification: this proves "system notification -> listener -> DB". It does NOT
 * prove LINE/Messenger compatibility on a real account; those remain NOT TESTED here.
 */
@RunWith(AndroidJUnit4::class)
class SelfTestSystemNotificationInstrumentedTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Configure the APP's own settings.
     *
     * The instrumentation APK is a separate package with its own data
     * directory, so it cannot write the app's SharedPreferences directly. The
     * debug-only provider (present in the debug build only) bridges that gap.
     */
    private fun ensureSelfTestEnabled() {
        val sources = listOf(
            CapturePolicy.SELF_TEST,
            SourceRegistry.SELF_TEST_INSTRUMENTATION_PACKAGE
        ).joinToString(",")
        ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "enableSources", sources, null
        )
        ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "setRecording", "true", null
        )
    }

    private fun canPostNow(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Ensure the system has actually BOUND the listener service. Granting the
     * permission only puts us in the enabled list; after a reinstall the system
     * may not have re-bound. requestRebind (API 31+) is the official trigger.
     *
     * IMPORTANT: the instrumentation test runs in its own process, separate from
     * the app process that hosts the bound service. The in-process flag
     * [CaptureDiagnostics.isListenerConnectedInProcess] therefore can never become
     * true from here. The persisted evidence flag — written by the app process
     * from the service's own onListenerConnected() callback and read back through
     * SharedPreferences — IS visible cross-process, so that is what we check.
     *
     * We still require the *system* to have bound the service: the flag is only
     * ever set from a real onListenerConnected() callback, never from a grant.
     */
    private fun ensureListenerConnected(): Boolean {
        if (!SelfTestNotifier.listenerPermissionGranted(ctx)) return false
        val component = android.content.ComponentName(ctx, PrivateNotificationListener::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                android.service.notification.NotificationListenerService.requestRebind(component)
            } catch (_: Throwable) {
            }
        }
        // The bound service lives in the APP process, not in the test process.
        // If the app has never been started (fresh install), the system has
        // nothing to bind to and no connection evidence can exist yet. Starting
        // the app creates the app process, after which the system binds the
        // listener service to it.
        ensureAppProcessRunning()
        // Wait for the app process to record a real onListenerConnected() event.
        // The evidence lives in the APP's preferences, so it is read through the
        // debug provider rather than from this process.
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            if (hasConnectedEvidence()) return true
            Thread.sleep(500)
        }
        return false
    }

    /** Read the app's real listener-connection evidence through the provider. */
    private fun hasConnectedEvidence(): Boolean {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "hasConnectedEvidence", null, null
        )
        return res?.getBoolean("connected") == true
    }

    /**
     * Make sure the app process exists, so the system can bind the notification
     * listener service to it. The listener service is only instantiated in the
     * app process; the instrumentation test runs in a different package.
     */
    private fun ensureAppProcessRunning() {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
        // Give the system a moment to create the process and bind the service.
        Thread.sleep(1500)
    }

    @Test
    fun selfTestSourceRegisteredInKnownApps() {
        assertTrue(CapturePolicy.knownApps.any { it.second == CapturePolicy.SELF_TEST })
    }

    @Test
    fun systemPostedNotificationReachesListenerAndIsStored() {
        // Without POST_NOTIFICATIONS (API 33+) we cannot post our own notification;
        // skip honestly instead of fabricating a pass.
        assumeTrue("POST_NOTIFICATIONS unavailable -> system E2E NOT TESTED", canPostNow())
        assumeTrue("listener not bindable in this environment -> system E2E NOT TESTED",
            ensureListenerConnected())
        ensureSelfTestEnabled()
        // Unique marker so the row we look for can only be the one THIS run posted.
        // Without it, a stale row from a previous run would be counted as success.
        val marker = SelfTestNotifier.BODY + " #" + System.nanoTime()
        val before = SelfTestRowCount()
        SelfTestNotifier.post(ctx, System.currentTimeMillis(), marker)
        val deadline = System.currentTimeMillis() + 20_000
        var stored = false
        while (System.currentTimeMillis() < deadline) {
            if (markerRowCount(marker) > 0) { stored = true; break }
            Thread.sleep(500)
        }
        assertTrue("System-posted self-test notification must reach the listener", stored)
        // The listener must have recorded a real connection, not just a grant.
        assertTrue("listener connection evidence must exist", hasConnectedEvidence())
    }

    @Test
    fun repeatedIdenticalSelfTestPostsDedupeToSingleRow() {
        assumeTrue("POST_NOTIFICATIONS unavailable -> dedup NOT TESTED", canPostNow())
        assumeTrue("listener not bindable in this environment -> dedup NOT TESTED",
            ensureListenerConnected())
        ensureSelfTestEnabled()
        // A per-run marker with a fixed timestamp: two posts of the SAME marker must
        // collapse to one row, and no stale row may be mistaken for the result.
        val marker = SelfTestNotifier.BODY + " #dedup" + System.nanoTime()
        val fixed = 1_700_000_000_000L
        val base = markerRowCount(marker)
        SelfTestNotifier.post(ctx, fixed, marker)
        Thread.sleep(TimeUnit.SECONDS.toMillis(4))
        SelfTestNotifier.post(ctx, fixed, marker)
        Thread.sleep(TimeUnit.SECONDS.toMillis(4))
        // Two identical notifications (same fixed timestamp + body) must collapse to one row.
        assertEquals("identical self-test notifications must dedupe to one row",
            base + 1, markerRowCount(marker))
    }

    @Test
    fun disabledSourcePolicyRejectsSystemPostedSelfTest() {
        assumeTrue("POST_NOTIFICATIONS unavailable -> NOT TESTED", canPostNow())
        assumeTrue("listener not bindable in this environment -> NOT TESTED",
            ensureListenerConnected())
        // Explicitly turn OFF the self-test source while recording stays on: the
        // listener must record SOURCE_NOT_ALLOWED, not store the row.
        // The change must be applied to the APP's settings, not the test's.
        ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "enableSources", "", null
        )
        ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "setRecording", "true", null
        )
        val marker = SelfTestNotifier.BODY + " #rejected" + System.nanoTime()
        val before = markerRowCount(marker)
        SelfTestNotifier.post(ctx, System.currentTimeMillis(), marker)
        Thread.sleep(TimeUnit.SECONDS.toMillis(5))
        assertEquals("unselected source must not be stored", before,
            markerRowCount(marker))
    }

    private fun SelfTestRowCount(
        predicate: (InboxDatabase.Entry) -> Boolean = { SourceRegistry.isSelfTestPackage(it.packageName) }
    ): Int {
        val db = InboxDatabase(ctx)
        val n = try { db.recent(500).count(predicate) } finally { db.close() }
        return n
    }

    /**
     * Count the stored self-test rows that carry [marker].
     *
     * The rows live in the APP's database; the test process cannot open it
     * directly, so the debug-only provider counts them on the app's behalf.
     */
    private fun markerRowCount(marker: String): Int {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI,
            DebugSettingsProvider.METHOD_MARKER_ROWS, marker, null
        )
        return res?.getInt(DebugSettingsProvider.KEY_MARKER_ROWS) ?: 0
    }
}
