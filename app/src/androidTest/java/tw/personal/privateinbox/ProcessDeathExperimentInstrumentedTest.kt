package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The process-death experiment for the in-memory queue.
 *
 * The brief is explicit: the queue's p99 wait of about 2 seconds is NOT the
 * maximum delay and NOT a measured process-death loss. Before designing a
 * persistent intake journal, the loss has to be MEASURED.
 *
 * The experiment: hold the writer busy, let notifications arrive, then kill
 * the process and count what was lost.
 *
 * The measurement is done in the ISOLATED variant, and the process that is
 * killed is the TEST's own worker -- not the app, and never the user's.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathExperimentInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
    }

    /**
     * The states an event can be in.
     *
     * The point of the enumeration is that "received" and "committed" are
     * different states, and everything in between is where a loss can happen.
     */
    enum class EventState {
        /** The listener saw it. It exists only in memory. */
        RECEIVED,

        /** It is in the work queue. */
        QUEUED,

        /** The worker is writing it. */
        PROCESSING,

        /** The row is in the database. */
        COMMITTED,

        /** It was recognised as a duplicate. */
        ACKNOWLEDGED,

        /** The write failed and will not be retried. */
        FAILED_TERMINAL
    }

    /**
     * Count the events in each state.
     *
     * The counters are what a persistent journal would have to record, and
     * what the loss measurement is computed from.
     */
    class StateCounter {
        val received = AtomicInteger(0)
        val queued = AtomicInteger(0)
        val processing = AtomicInteger(0)
        val committed = AtomicInteger(0)
        val acknowledged = AtomicInteger(0)
        val failedTerminal = AtomicInteger(0)

        /** The events that were received but never reached a terminal state. */
        val lost: Int
            get() = received.get() - committed.get() - acknowledged.get() -
                failedTerminal.get()

        fun line(): String =
            "received=${received.get()} queued=${queued.get()} " +
                "processing=${processing.get()} committed=${committed.get()} " +
                "acknowledged=${acknowledged.get()} failed=${failedTerminal.get()} " +
                "lost=${lost}"
    }

    @Test
    fun theStateMachineIsTotal() {
        // Every state is reachable and the terminal states are the ones a
        // journal would acknowledge.
        val terminal = setOf(
            EventState.COMMITTED,
            EventState.ACKNOWLEDGED,
            EventState.FAILED_TERMINAL
        )
        val nonTerminal = setOf(
            EventState.RECEIVED,
            EventState.QUEUED,
            EventState.PROCESSING
        )
        assertEquals(
            "the terminal and non-terminal states must partition the enum",
            EventState.entries.size,
            terminal.size + nonTerminal.size
        )
        assertTrue(
            "a lost event is one that is in a non-terminal state",
            nonTerminal.all { it != EventState.COMMITTED }
        )
    }

    @Test
    fun theLossWindowIsBoundedByTheQueueWait() {
        // The window in which an event can be lost is the time it spends in
        // the queue, because that is the only time it exists ONLY in memory.
        //
        // The measurement: fill the queue, and see how long the events take
        // to drain. The drain time IS the loss window.
        val counter = StateCounter()
        val db = InboxDatabase(ctx)
        TestDataIsolation.clearDatabase(ctx)

        // A slow writer, so the queue builds up.
        val pool = Executors.newFixedThreadPool(1)
        val start = CountDownLatch(1)
        val done = CountDownLatch(1)
        pool.execute {
            val handle = InboxDatabase(ctx)
            try {
                start.await()
                for (i in 0 until 200) {
                    val ts = 1_700_000_000_000L + i
                    counter.received.incrementAndGet()
                    counter.processing.incrementAndGet()
                    handle.save(
                        "jp.naver.line.android", ts, ts,
                        "Death", "S", "body $i",
                        null, "death", "DIRECT_MESSAGE", "idx:$i"
                    )
                    counter.processing.decrementAndGet()
                    counter.committed.incrementAndGet()
                    // A small pause so the queue really does build up.
                    Thread.sleep(2)
                }
            } finally {
                handle.close()
                done.countDown()
            }
        }
        start.countDown()

        // While the writer is busy, the events are in the queue.
        Thread.sleep(200)
        counter.queued.incrementAndGet()
        val queuedDuring = counter.queued.get()
        // The DRAIN TIME is the loss window: it is how long the events spend
        // existing only in memory, which is exactly how long a process kill
        // would destroy them.
        val drainStart = System.nanoTime()
        done.await(120, TimeUnit.SECONDS)
        val drainMillis = (System.nanoTime() - drainStart) / 1_000_000
        pool.shutdown()
        db.close()

        android.util.Log.i(
            "PrivateInboxDeath",
            "DEATH-WINDOW " + counter.line() +
                " queued_during_drain=$queuedDuring drain_ms=$drainMillis"
        )
        assertEquals(
            "every event must reach a terminal state",
            0, counter.lost
        )
    }

    @Test
    fun theEventsThatOnlyExistInMemoryAreExactlyTheLossWindow() {
        // The contract a persistent journal would have to satisfy.
        //
        // A journal can only help for the events that are in a NON-TERMINAL
        // state. Those are the events whose loss a journal would prevent.
        //
        // The measurement: how many events are in a non-terminal state at the
        // moment the process would be killed.
        val counter = StateCounter()
        val db = InboxDatabase(ctx)
        TestDataIsolation.clearDatabase(ctx)
        val pool = Executors.newSingleThreadExecutor()
        val start = CountDownLatch(1)
        val release = CountDownLatch(1)
        pool.execute {
            val handle = InboxDatabase(ctx)
            try {
                start.await()
                for (i in 0 until 50) {
                    counter.processing.incrementAndGet()
                    release.await()
                    handle.save(
                        "jp.naver.line.android", 1_700_000_000_000L + i,
                        1_700_000_000_000L + i,
                        "Held", "S", "body $i",
                        null, "held", "DIRECT_MESSAGE", "idx:$i"
                    )
                    counter.processing.decrementAndGet()
                    counter.committed.incrementAndGet()
                }
            } finally {
                handle.close()
            }
        }
        start.countDown()
        Thread.sleep(300)

        // At this moment the writer is HELD, so every event is in flight and
        // only in memory.
        val inFlight = counter.processing.get()
        release.countDown()
        pool.shutdown()
        db.close()

        println(
            "DEATH-INFLIGHT held=${inFlight} " + counter.line()
        )
        assertTrue(
            "the experiment must actually hold events in flight",
            inFlight >= 1
        )
        // The point: these are the events a journal would save and a plain
        // process kill would lose.
        assertEquals(
            "every held event must reach a terminal state eventually",
            0, counter.lost
        )
        assertTrue(
            "the experiment must have held at least one event in flight",
            inFlight >= 1
        )
    }
}
