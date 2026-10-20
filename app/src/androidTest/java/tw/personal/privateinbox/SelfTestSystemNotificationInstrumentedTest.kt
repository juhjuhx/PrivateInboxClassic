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

    private fun ensureSelfTestEnabled() {
        LocalSettings.setRecording(ctx, true)
        val sel = LocalSettings.selected(ctx).toMutableSet()
        sel.add(CapturePolicy.SELF_TEST)
        LocalSettings.updateSources(ctx, sel)
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
        // Wait for the app process to record a real onListenerConnected() event.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (CaptureDiagnostics.hasConnectedEvidence(ctx)) return true
            Thread.sleep(500)
        }
        return false
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
            if (markerRowCount(marker)) { stored = true; break }
            Thread.sleep(500)
        }
        assertTrue("System-posted self-test notification must reach the listener", stored)
        // The listener must have recorded a real connection, not just a grant.
        assertTrue("listener connection evidence must exist",
            CaptureDiagnostics.hasConnectedEvidence(ctx))
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
        LocalSettings.setRecording(ctx, true)
        val sel = LocalSettings.selected(ctx).toMutableSet()
        sel.remove(CapturePolicy.SELF_TEST)
        LocalSettings.updateSources(ctx, sel)
        val marker = SelfTestNotifier.BODY + " #rejected" + System.nanoTime()
        val before = markerRowCount(marker)
        SelfTestNotifier.post(ctx, System.currentTimeMillis(), marker)
        Thread.sleep(TimeUnit.SECONDS.toMillis(5))
        assertEquals("unselected source must not be stored", before,
            markerRowCount(marker))
    }

    private fun SelfTestRowCount(
        predicate: (InboxDatabase.Entry) -> Boolean = { it.packageName == CapturePolicy.SELF_TEST }
    ): Int {
        val db = InboxDatabase(ctx)
        val n = try { db.recent(500).count(predicate) } finally { db.close() }
        return n
    }

    /** Count only the self-test rows whose body carries [marker]. */
    private fun markerRowCount(marker: String): Int =
        SelfTestRowCount(predicate = { it.body.contains(marker) })
}
