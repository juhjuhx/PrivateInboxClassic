package tw.personal.privateinbox

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The shared setup a benchmark needs before it can measure anything.
 *
 * Three things have to be true before a notification reaches the listener:
 *
 *  1. The capture source has to be enabled, and that setting lives in the
 *     APP's process. The instrumentation APK is a separate package, so it
 *     cannot write the app's preferences directly; the debug provider is the
 *     bridge.
 *  2. Notification access has to be granted. The instrumentation run installs
 *     the app fresh, which drops it out of the allowed-listeners list.
 *  3. The system has to have actually BOUND the listener. A grant is not a
 *     bind, and a notification posted before the bind is never delivered.
 *
 * Every benchmark shares this, because a benchmark that measures a pipeline
 * that never ran measures nothing.
 */
object BenchmarkSetup {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Prepare the pipeline for measurement.
     *
     * @return true when the listener is bound and the source is enabled
     */
    fun prepare(): Boolean {
        ensureCanPostNotifications()
        enableSource()
        grantListenerAccess()
        return waitForListener()
    }

    /**
     * Make sure the app may post its own notifications.
     *
     * The synthetic notifications are posted by the app package, and from
     * API 33 that needs POST_NOTIFICATIONS. Without it, notify() is silently
     * ignored -- the notification never appears, the listener never sees it,
     * and the benchmark measures "nothing happened" instead of the latency.
     */
    fun ensureCanPostNotifications() {
        runCatching {
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            val fd = ui.executeShellCommand(
                "pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS"
            )
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }

    /**
     * Enable the synthetic capture source in the APP's settings.
     *
     * The burst notifier posts from the TARGET context, so the notification
     * carries the APP's own package name. The source that has to be enabled
     * is therefore the app's own package -- which is exactly the self-test
     * source the app already registers.
     *
     * The instrumentation package is enabled as well, because a notification
     * posted from the test process carries THAT package.
     */
    fun enableSource() {
        runCatching {
            ctx.contentResolver.call(
                DebugSettingsProvider.contentUriFor(ctx), "enableSources",
                "${SourceRegistry.selfTestPackage(ctx.packageName)}," +
                    "${SourceRegistry.selfTestInstrumentationPackage(ctx.packageName)}",
                null
            )
            ctx.contentResolver.call(
                DebugSettingsProvider.contentUriFor(ctx), "setRecording", "true", null
            )
        }
    }

    /**
     * Re-grant notification access.
     *
     * `cmd notification allow_listener` is the supported way from a shell,
     * and UiAutomation gives the test shell privileges.
     */
    fun grantListenerAccess() {
        runCatching {
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            val fd = ui.executeShellCommand(
                "cmd notification allow_listener " +
                    VariantNames.listenerComponent(ctx.packageName)
            )
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
                .use { it.readBytes() }
        }
    }

    /**
     * Wait until the app's listener service is actually connected.
     *
     * The provider runs inside the app process, so it can observe the
     * in-process connection flag — the only real evidence of a live bind.
     */
    fun waitForListener(timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val connected = runCatching {
                ctx.contentResolver.call(
                    DebugSettingsProvider.contentUriFor(ctx), "ensureListener", null, null
                )?.getBoolean("connected") == true
            }.getOrDefault(false)
            if (connected) return true
            Thread.sleep(500)
        }
        return false
    }

    /** Reset the metrics in the APP's process. */
    fun resetMetrics() {
        runCatching {
            ctx.contentResolver.call(
                DebugSettingsProvider.contentUriFor(ctx), "latencyReset", null, null
            )
        }
    }
}
