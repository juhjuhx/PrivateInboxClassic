package tw.personal.privateinbox

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInfo
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
     *
     * The provider lives in the app process, so the app must be running for
     * the call to succeed. Retry until it answers.
     */
    private fun ensureSelfTestEnabled() {
        // The self-test source must match the RUNNING variant: the system
        // stamps the package of whoever posted the notification, and in the
        // devtest variant that is not the production package.
        val sources = listOf(
            SourceRegistry.selfTestPackage(ctx.packageName),
            SourceRegistry.selfTestInstrumentationPackage(ctx.packageName),
            CapturePolicy.SELF_TEST,
            SourceRegistry.SELF_TEST_INSTRUMENTATION_PACKAGE
        ).joinToString(",")
        ensureAppProcessRunning()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                ctx.contentResolver.call(
                    DebugSettingsProvider.contentUriFor(ctx), "enableSources", sources, null
                )
                ctx.contentResolver.call(
                    DebugSettingsProvider.contentUriFor(ctx), "setRecording", "true", null
                )
            }.isSuccess
            if (ok) return
            ensureAppProcessRunning()
            Thread.sleep(800L)
        }
    }

    /**
     * Can the app post its own notification?
     *
     * The permission belongs to the APP package, and the runtime grant state
     * is only visible to the app itself. The debug provider runs inside the app
     * process, so it can answer this authoritatively.
     */
    private fun canPostNow(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val res = runCatching {
            ctx.contentResolver.call(
                DebugSettingsProvider.contentUriFor(ctx), "canPost", null, null
            )
        }.getOrNull()
        return res?.getBoolean("canPost") == true
    }

    /**
     * Ensure the app holds POST_NOTIFICATIONS.
     *
     * The permission belongs to the app package. The instrumentation runner
     * cannot grant it with plain PackageManager calls, but UiAutomation (which
     * runs with shell privileges) can. If that is not possible, the test falls
     * back to the normal runtime-permission dialog.
     */
    private fun ensureCanPost(): Boolean {
        // The provider runs in the app process, so the app must be running
        // before the permission state can be queried.
        ensureAppProcessRunning()
        if (canPostNow()) return true
        // UiAutomation.executeShellCommand runs with shell privileges and can
        // grant a runtime permission to the app package.
        runCatching {
            val uiAutomation = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().uiAutomation
            val fd = uiAutomation.executeShellCommand(
                "pm grant ${ctx.packageName} ${android.Manifest.permission.POST_NOTIFICATIONS}"
            )
            val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
                .use { it.readBytes().toString(Charsets.UTF_8) }
            android.util.Log.i("PrivateInboxSelfTest", "pm grant output: $output")
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (canPostNow()) return true
            Thread.sleep(500L)
        }
        android.util.Log.i("PrivateInboxSelfTest",
            "canPostNow=false for ${ctx.packageName}")
        return false
    }

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
        // The notification-access grant is lost on every fresh install. The
        // instrumentation runner cannot grant it with PackageManager, but
        // UiAutomation runs with shell privileges and can.
        ensureNotificationAccessGranted()
        if (!SelfTestNotifier.listenerPermissionGranted(ctx)) {
            android.util.Log.i("PrivateInboxSelfTest",
                "notification access not granted for ${ctx.packageName}")
            return false
        }

        // The bound service lives in the APP process, not in the test process.
        // Make sure the app is running so the system can bind to it. The
        // provider also runs in the app process, so it can observe the
        // in-process connection flag and wait for the service's own
        // onListenerConnected() callback — the only real connection evidence.
        ensureAppProcessRunning()

        // The provider is only reachable once the app is installed and its
        // process exists. Retry briefly: the first start may still be warming
        // up, and a fresh install may not have registered the provider yet.
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val res = runCatching {
                ctx.contentResolver.call(
                    DebugSettingsProvider.contentUriFor(ctx), "ensureListener", null, null
                )
            }.getOrNull()
            if (res?.getBoolean("connected") == true) return true
            if (res?.getBoolean("hasEvidence") == true) return true
            // Make sure the app is up, then wait for the system to bind.
            ensureAppProcessRunning()
            Thread.sleep(1000L)
        }
        println("SELFTEST: listener never became connected")
        return false
    }

    /**
     * Grant the app notification-access through the system settings service.
     *
     * `cmd notification allow_listener` is the supported way to do this from a
     * shell, and UiAutomation gives the test shell privileges.
     */
    private fun ensureNotificationAccessGranted() {
        if (SelfTestNotifier.listenerPermissionGranted(ctx)) return
        runCatching {
            val uiAutomation = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().uiAutomation
            val fd = uiAutomation.executeShellCommand(
                "cmd notification allow_listener ${ctx.packageName}/" +
                    VariantNames.listenerComponent(ctx.packageName)
            )
            val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
                .use { it.readBytes().toString(Charsets.UTF_8) }
            android.util.Log.i("PrivateInboxSelfTest", "allow_listener output: $output")
        }
        // Give the system a moment to bind the service afterwards.
        Thread.sleep(1500L)
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
        Thread.sleep(1200L)
    }

    /** Read the app's real listener-connection evidence through the provider. */
    private fun hasConnectedEvidence(): Boolean {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "hasConnectedEvidence", null, null
        )
        return res?.getBoolean("connected") == true
    }

    @Test
    fun selfTestSourceRegisteredInKnownApps() {
        assertTrue(CapturePolicy.knownApps.any { it.second == CapturePolicy.SELF_TEST })
    }

    @Test
    fun systemPostedNotificationReachesListenerAndIsStored() {
        // Without POST_NOTIFICATIONS (API 33+) we cannot post our own notification;
        // skip honestly instead of fabricating a pass. The reason is included in
        // the assumption message so a skipped run is still diagnosable.
        val canPost = ensureCanPost()
        assumeTrue("POST_NOTIFICATIONS not granted to ${ctx.packageName} -> system E2E NOT TESTED",
            canPost)
        val connected = ensureListenerConnected()
        assumeTrue("listener not bindable in this environment -> system E2E NOT TESTED",
            connected)
        ensureSelfTestEnabled()
        // Unique marker so the row we look for can only be the one THIS run posted.
        // Without it, a stale row from a previous run would be counted as success.
        val marker = SelfTestNotifier.BODY + " #" + System.nanoTime()
        val before = markerRowCount(marker)
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
        assumeTrue("POST_NOTIFICATIONS not granted to ${ctx.packageName} -> dedup NOT TESTED",
            ensureCanPost())
        assumeTrue("listener not bindable in this environment -> dedup NOT TESTED",
            ensureListenerConnected())
        ensureSelfTestEnabled()
        // A per-run marker with one fixed timestamp: two posts of the SAME marker
        // must collapse to one row, and no stale row from an earlier run may be
        // mistaken for the result. The timestamp must be current — the system
        // may drop a notification whose message time is far in the past.
        val marker = SelfTestNotifier.BODY + " #dedup" + System.nanoTime()
        val fixed = System.currentTimeMillis()
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
        assumeTrue("POST_NOTIFICATIONS not granted to ${ctx.packageName} -> NOT TESTED",
            ensureCanPost())
        assumeTrue("listener not bindable in this environment -> NOT TESTED",
            ensureListenerConnected())
        // Explicitly turn OFF the self-test source while recording stays on: the
        // listener must record SOURCE_NOT_ALLOWED, not store the row.
        // The change must be applied to the APP's settings, not the test's.
        ensureAppProcessRunning()
        ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "enableSources", "", null
        )
        ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "setRecording", "true", null
        )
        val marker = SelfTestNotifier.BODY + " #rejected" + System.nanoTime()
        val before = markerRowCount(marker)
        SelfTestNotifier.post(ctx, System.currentTimeMillis(), marker)
        Thread.sleep(TimeUnit.SECONDS.toMillis(5))
        assertEquals("unselected source must not be stored", before,
            markerRowCount(marker))
    }

    /**
     * Count the stored self-test rows that carry [marker].
     *
     * The rows live in the APP's database; the test process cannot open it
     * directly, so the debug-only provider counts them on the app's behalf.
     */
    private fun markerRowCount(marker: String): Int {
        val res = runCatching {
            ctx.contentResolver.call(
                DebugSettingsProvider.contentUriFor(ctx),
                DebugSettingsProvider.METHOD_MARKER_ROWS, marker, null
            )
        }.getOrNull()
        return res?.getInt(DebugSettingsProvider.KEY_MARKER_ROWS) ?: 0
    }
}
