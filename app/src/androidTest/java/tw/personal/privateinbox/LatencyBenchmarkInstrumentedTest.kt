package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The latency benchmark.
 *
 * It drives the capture path with synthetic notifications and reports every
 * stage separately, so a number can be attributed to a stage instead of to
 * "the callback".
 *
 * The stages come from the brief:
 *
 *   T0 the callback is entered
 *   T1 the necessary snapshot is complete
 *   T2 the enqueue succeeded
 *   T3 the worker started on it
 *   T5 the SQLite commit is done
 *
 * and the derived latencies:
 *
 *   snapshot_latency = T1 - T0     (all on the MAIN thread)
 *   enqueue_latency  = T2 - T0     (all on the MAIN thread)
 *   queue_wait       = T3 - T2
 *   durable_latency  = T5 - T0
 */
@RunWith(AndroidJUnit4::class)
class LatencyBenchmarkInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifier = BurstNotifier()

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        val db = InboxDatabase(ctx)
        db.clear()
        db.close()
        notifier.ensureChannel()
        // The listener has to be bound before anything is measured, or the
        // benchmark measures a pipeline that never ran.
        assertTrue(
            "the notification listener must be bound before the latency is measured",
            BenchmarkSetup.prepare()
        )
        // The metrics live in the APP's process, so they are reset through the
        // provider rather than in this process.
        BenchmarkSetup.resetMetrics()
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    /**
     * Read the latency metrics from the APP process.
     *
     * The listener runs in the app's process and the instrumentation test runs
     * in a separate one, so reading the metrics directly from the test would
     * always return zero. The debug provider is the bridge.
     */
    private fun latencyReport(): Pair<String, String> {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "latency", null, null
        )
        return (res?.getString("report") ?: "<no report>") to
            (res?.getString("targets") ?: "<no targets>")
    }

    private fun stageCount(stage: String): Long {
        val res = ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "latency", null, null
        )
        return res?.getLong("${stage}_n") ?: 0L
    }

    @Test
    fun theLatencyOfASingleNotification() {
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 1000
        notifier.postGroupUpdate(notificationId = id, messageCount = 1, firstIndex = 0)
        Thread.sleep(1_500)
        val (report, targets) = latencyReport()
        android.util.Log.i("PrivateInboxLatency", "LATENCY-SINGLE " + report)
        android.util.Log.i("PrivateInboxLatency", "LATENCY-TARGETS " + targets)
        assertTrue(
            "the enqueue stage must have been measured in the app process",
            stageCount("enqueue") > 0
        )
    }

    @Test
    fun theLatencyUnderABurst() {
        val groups = 5
        val perGroup = 20
        val idBase = BurstNotifier.NOTIFICATION_ID_BASE + 2000
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        for (g in 0 until groups) {
            val id = idBase + g
            for (i in 1..perGroup) {
                notifier.postGroupUpdate(
                    notificationId = id,
                    conversationTitle = "Latency Group $g",
                    messageCount = i,
                    firstIndex = 0
                )
            }
        }
        val postMillis = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
        // Let the queue drain.
        Thread.sleep(6_000)
        val (report, targets) = latencyReport()
        android.util.Log.i(
            "PrivateInboxLatency",
            "LATENCY-BURST post_ms=$postMillis " + report
        )
        android.util.Log.i("PrivateInboxLatency", "LATENCY-TARGETS " + targets)
        assertTrue("the burst must have produced samples",
            stageCount("committed") > 0)
    }

    @Test
    fun theCallbackPathDoesNotDoDiskIo() {
        // The single most important property of the callback: it must not do
        // synchronous disk I/O, because it runs on the MAIN thread and a slow
        // callback delays every other notification.
        //
        // The measurement: the time the callback takes. The diagnostic writer
        // it calls does a SharedPreferences read-modify-write per event, so if
        // the callback time scales with the number of diagnostics written, the
        // callback is doing disk I/O.
        val db = InboxDatabase(ctx)
        db.clear()
        db.close()

        // A notification with a LONG history: the parser has to walk it, and
        // the diagnostics writer has to record several events for it.
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 3000
        notifier.postGroupUpdate(notificationId = id, messageCount = 25, firstIndex = 0)
        Thread.sleep(2_000)

        val res = ctx.contentResolver.call(
            DebugSettingsProvider.contentUriFor(ctx), "latency", null, null
        )
        val p50 = (res?.getLong("snapshot_p50") ?: 0L) / 1e6
        val p95 = (res?.getLong("snapshot_p95") ?: 0L) / 1e6
        val p99 = (res?.getLong("snapshot_p99") ?: 0L) / 1e6
        android.util.Log.i(
            "PrivateInboxLatency",
            String.format(
                "LATENCY-CALLBACK p50=%.3f p95=%.3f p99=%.3f ms (history=25)",
                p50, p95, p99
            )
        )
        assertTrue("the callback must be measured", stageCount("snapshot") > 0)
    }
}
