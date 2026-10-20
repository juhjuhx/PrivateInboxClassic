package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the notification listener's robustness.
 *
 * Background context: a ClassCastException inside onNotificationPosted kills
 * the listener service, which silently stops ALL capture until the system
 * rebinds it. These tests pin the two guards that prevent that.
 */
@RunWith(AndroidJUnit4::class)
class ListenerRobustnessInstrumentedTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun malformedSourcePreferenceDoesNotCrashTheListener() {
        // Write a preferences file whose selected_source_packages has the wrong
        // type (a String where a Set is expected) — exactly what a manual edit or
        // a partial write can produce.
        val prefsFile = ctx.getSharedPreferences("local_config", Context.MODE_PRIVATE)
        prefsFile.edit()
            .putString("selected_source_packages", "jp.naver.line.android")
            .putBoolean("recording_enabled", true)
            .commit()

        // Must not throw.
        val selected = LocalSettings.selected(ctx)
        // The malformed value is tolerated: either it is recovered as a single
        // source, or it degrades to an empty set. Either way, no crash.
        assertTrue("selected sources must be a set of strings", selected.all { it.isNotEmpty() })

        // A second read (the one the listener performs) must also be safe.
        LocalSettings.selected(ctx)
    }

    @Test
    fun emptyAndAbsentPreferencesReturnEmptySourceSet() {
        ctx.getSharedPreferences("local_config", Context.MODE_PRIVATE).edit()
            .remove("selected_source_packages")
            .commit()
        assertEquals(emptySet<String>(), LocalSettings.selected(ctx))
    }

    @Test
    fun listenerSurvivesAMalformedNotification() {
        // Configure the APP's settings through the debug provider: the
        // instrumentation package has its own data directory.
        providerEnableSources("org.telegram.messenger")
        providerSetRecording(true)

        // The capture routine is exercised with a real application context, the
        // same one the bound service uses.
        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val listener = PrivateNotificationListener()

        // A null StatusBarNotification must be handled without throwing.
        listener.capture(appCtx, null)

        // A notification with no extras at all must also be handled.
        val empty = android.app.Notification.Builder(ctx, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        val sbn = StatusBarNotificationFactory.create(empty, "org.telegram.messenger")
        listener.capture(appCtx, sbn)

        // The listener must still be usable afterwards.
        assertTrue("listener must survive malformed input", providerIsRecording())
    }

    @Test
    fun recordingDisabledIsRecordedAsAStageNotACrash() {
        providerEnableSources("org.telegram.messenger")
        providerSetRecording(false)

        val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
        val empty = android.app.Notification.Builder(ctx, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        val sbn = StatusBarNotificationFactory.create(empty, "org.telegram.messenger")

        // The listener must record the event and the policy stage, not crash.
        // The counters live in the APP's preferences, so they are read through
        // the debug provider.
        val before = providerLong("received")
        val failuresBefore = providerLong("failed")
        PrivateNotificationListener().capture(appCtx, sbn)
        val after = providerLong("received")

        assertTrue("the event must be counted as received", after > before)
        assertTrue("recording-disabled must not be counted as a failure",
            providerLong("failed") == failuresBefore)
    }

    /** Read a diagnostic counter from the app's own preferences. */
    private fun providerLong(key: String): Long {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "counters", null, null
        ) ?: return 0L
        return res.getLong(key, 0L)
    }

    private fun providerEnableSources(vararg packages: String) {
        ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "enableSources",
            packages.joinToString(","), null
        )
    }

    private fun providerSetRecording(enabled: Boolean) {
        ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "setRecording", enabled.toString(), null
        )
    }

    private fun providerIsRecording(): Boolean {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "counters", null, null
        ) ?: return false
        return res.getBoolean("recording", false)
    }
}
