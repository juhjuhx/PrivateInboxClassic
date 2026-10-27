package tw.personal.privateinbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * P0-B verification: Batch persistence, fault injection, and retry state machine.
 *
 * Uses real SQLite triggers for fault injection (not mocks).
 * Verifies:
 * 1. Write failure once then retry succeeds: exactly one copy saved, no duplicates.
 * 2. Consecutive failures reach MAX_FLUSH_ATTEMPTS: terminates, no infinite loop, terminal diagnostic.
 * 3. Multi-message batch: accounting is exact per-message.
 * 4. Multiple consecutive batches: no deadlock, starvation, or data crosstalk.
 * 5. SQLite transaction rollback: never reports COMMITTED.
 * 6. No subsequent notifications: scheduled retries still execute without new intake.
 * 7. Simulated process death: in-memory state is lost without journal.
 */
@RunWith(AndroidJUnit4::class)
class BatchRetryFaultInjectionInstrumentedTest {

    private val targetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The listener created by the current test.
     *
     * CaptureMetrics is a GLOBAL object and every listener owns its own worker
     * thread, so a worker left running by one test keeps flushing that test's
     * PREPARED batches — including the ones waiting in the retry backoff — into
     * the NEXT test's freshly cleared database. That is a real cross-test
     * contamination: the next test then reads the previous test's message
     * bodies ("expected Alice but was Eve") and sees an inflated dbFailed.
     * Stopping the worker in @After is what makes each test hermetic.
     */
    private var listener: PrivateNotificationListener? = null

    /** Set once a test destroyed its own listener (the process-death test). */
    private var listenerDestroyed = false

    /** Create (and remember) a listener whose worker is stopped in tearDown. */
    private fun newListener(): PrivateNotificationListener =
        PrivateNotificationListener().also {
            listener = it
            listenerDestroyed = false
        }

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(targetContext)
        LocalSettings.setRecording(targetContext, true)
        LocalSettings.setCaptureImages(targetContext, false)
        LocalSettings.updateSources(targetContext, setOf("org.telegram.messenger", "jp.naver.line.android"))
        TestDataIsolation.clearDatabase(targetContext)
        CaptureMetrics.reset()
        CaptureDiagnostics.clear(targetContext)
        // Clean up any leftover fault triggers
        dropFaultTrigger()
    }

    /**
     * Stop the worker BEFORE the fault trigger and the database are reset, so
     * no retry can fire between the two. Order matters: a worker still sleeping
     * in its retry backoff would otherwise wake up after dropFaultTrigger() and
     * write a batch from the test that already finished.
     */
    @After
    fun tearDown() {
        // Only destroy a listener the test has not already destroyed itself:
        // Service.onDestroy() is not meant to be called twice.
        if (!listenerDestroyed) listener?.onDestroy()
        listener = null
        dropFaultTrigger()
    }

    private fun channel(): String {
        val id = "fault-test-channel"
        val nm = targetContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(id, "test", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        return id
    }

    private fun buildNotification(
        conversation: String,
        messages: List<Pair<String, String>>,
        tag: String = "t${System.currentTimeMillis()}"
    ): StatusBarNotification {
        val builder = Notification.Builder(targetContext, channel())
            .setContentTitle(conversation)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
        val style = Notification.MessagingStyle(
            android.app.Person.Builder().setName(conversation).build()
        )
        for ((who, text) in messages) {
            val person = android.app.Person.Builder().setName(who).build()
            val m = Notification.MessagingStyle.Message(text, System.currentTimeMillis(), person)
            style.addMessage(m)
        }
        builder.setStyle(style)
        val n = builder.build()

        val ctor = StatusBarNotification::class.java.getConstructor(
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Notification::class.java,
            android.os.UserHandle::class.java, Long::class.javaPrimitiveType
        )
        return ctor.newInstance(
            "org.telegram.messenger", "org.telegram.messenger", 0, tag, 0, 0, 0, n,
            android.os.UserHandle.getUserHandleForUid(Process.myUid()),
            System.currentTimeMillis()
        )
    }

    private fun awaitRows(expected: Int, timeoutMs: Long = 8000): List<InboxDatabase.Entry> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var rows = emptyList<InboxDatabase.Entry>()
        while (System.currentTimeMillis() < deadline) {
            val db = InboxDatabase(targetContext)
            rows = try { db.recent(100) } finally { db.close() }
            if (rows.size >= expected) return rows
            Thread.sleep(200)
        }
        return rows
    }

    private fun createFaultTrigger(failCount: Int = -1) {
        // failCount < 0 means always fail; >= 0 means fail that many times then succeed
        //
        // DESIGN CONSTRAINT (verified against real SQLite): RAISE(ABORT) rolls
        // back the whole statement, including any UPDATE the trigger body
        // itself made — so a self-decrementing "fail N times" trigger can
        // never count down; "fail once" silently becomes "fail forever". The
        // trigger here is therefore GUARD-ONLY: it aborts while
        // fault_control.remaining > 0 and has NO side effects. Arming and
        // disarming (UPDATE fault_control) is done by the TEST from outside,
        // in its own statement, which the abort cannot roll back.
        val db = SQLiteDatabase.openOrCreateDatabase(targetContext.getDatabasePath("inbox.db"), null)
        db.execSQL("CREATE TABLE IF NOT EXISTS fault_control (remaining INTEGER)")
        db.execSQL("DELETE FROM fault_control")
        db.execSQL("INSERT INTO fault_control (remaining) VALUES (${if (failCount < 0) 1 else failCount})")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS fault_trigger BEFORE INSERT ON messages
            WHEN (SELECT remaining FROM fault_control) > 0
            BEGIN
                SELECT RAISE(ABORT, 'injected fault');
            END;""")
        db.close()
    }

    /** Disarm the fault: subsequent inserts succeed. Safe to call repeatedly. */
    private fun disarmFault() {
        val dbPath = targetContext.getDatabasePath("inbox.db")
        if (!dbPath.exists()) return
        val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("UPDATE fault_control SET remaining = 0")
        db.close()
    }

    /**
     * Wait until the retry machine has observed a real failure, then disarm.
     *
     * A fixed Thread.sleep() before disarmFault() is a RACE: the backoff is
     * 1000 ms and the budget is MAX_FLUSH_ATTEMPTS=3, so on a slow machine the
     * third attempt (TERMINAL — the batch is dropped for good) can land before
     * the test wakes up. The "fault clears, retry succeeds" path then becomes
     * unreachable and the test fails intermittently even though the product is
     * correct. RETRY_SCHEDULED is the actual signal that attempt 1 failed, so
     * disarming right after it is deterministic instead of guessing a duration.
     */
    private fun awaitFirstFailureThenDisarm(timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val scheduled = CaptureDiagnostics.recent(targetContext, 50)
                .any { it.code == CaptureDiagnostics.DB_INSERT_RETRY_SCHEDULED }
            if (scheduled) break
            Thread.sleep(50)
        }
        disarmFault()
    }

    private fun dropFaultTrigger() {
        val dbPath = targetContext.getDatabasePath("inbox.db")
        if (!dbPath.exists()) return
        val db = SQLiteDatabase.openDatabase(dbPath.path, null, SQLiteDatabase.OPEN_READWRITE)
        db.execSQL("DROP TRIGGER IF EXISTS fault_trigger;")
        db.execSQL("DROP TABLE IF EXISTS fault_control;")
        db.close()
    }

    @Test
    fun writeFailureOnce_retrySucceeds_exactlyOneCopySaved() {
        // Fail once, then succeed
        createFaultTrigger(1)

        val listener = newListener()
        val sbn = buildNotification("RetryConvo", listOf("Alice" to "Hello retry"))

        listener.capture(targetContext, sbn)

        // Poll for the retry-scheduled diagnostic — proof that the first
        // attempt actually FAILED against the fault (not silently skipped).
        val diagDeadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < diagDeadline) {
            val events = CaptureDiagnostics.recent(targetContext, 50)
            if (events.any { it.code == CaptureDiagnostics.DB_INSERT_RETRY_SCHEDULED }) break
            Thread.sleep(100)
        }

        val events = CaptureDiagnostics.recent(targetContext, 50)
        assertTrue(
            "Diagnostics must record DB_INSERT_RETRY_SCHEDULED",
            events.any { it.code == CaptureDiagnostics.DB_INSERT_RETRY_SCHEDULED }
        )

        // First attempt failed; the guard-only trigger is still armed until
        // the test disarms it. The scheduled retry (bounded backoff) must
        // then succeed — exactly one copy, no duplicates.
        disarmFault()

        val rows = awaitRows(1, timeoutMs = 8000)
        assertEquals("Exactly one row must be saved after successful retry", 1, rows.size)
        assertEquals("Alice", rows[0].sender)
        assertEquals("Hello retry", rows[0].body)

        assertEquals("Committed metric must be 1", 1, CaptureMetrics.dbCommitted.get())
    }

    @Test
    fun consecutiveFailuresReachLimit_terminatesAndRecordsTerminal() {
        // Always fail
        createFaultTrigger(-1)

        val listener = newListener()
        val sbn = buildNotification("TerminalConvo", listOf("Bob" to "Msg 1", "Bob" to "Msg 2"))

        listener.capture(targetContext, sbn)

        // Wait for attempts to exhaust
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline && CaptureMetrics.dbFailed.get() < 2) {
            Thread.sleep(200)
        }

        assertEquals("Terminal failure must record dbFailed", 2, CaptureMetrics.dbFailed.get())
        assertEquals("No rows should be committed", 0, CaptureMetrics.dbCommitted.get())

        val events = CaptureDiagnostics.recent(targetContext, 50)
        assertTrue(
            "Diagnostics must record DB_INSERT_TERMINAL",
            events.any { it.code == CaptureDiagnostics.DB_INSERT_TERMINAL }
        )
    }

    @Test
    fun multiMessageBatch_accountingIsExactPerMessage() {
        val listener = newListener()
        val sbn = buildNotification("BatchConvo", listOf("Carol" to "M1", "Carol" to "M2", "Carol" to "M3"))

        listener.capture(targetContext, sbn)

        val rows = awaitRows(3, timeoutMs = 8000)
        assertEquals(3, rows.size)
        assertEquals(3, CaptureMetrics.dbCommitted.get())
        assertEquals(0, CaptureMetrics.dbFailed.get())
        assertEquals(0, CaptureMetrics.dbDuplicated.get())

        // Re-capture the SAME notification through a FRESH listener (the
        // process-restart semantics). The same listener instance would filter
        // the re-delivery at the SOURCE via its per-notification memory (that
        // path is exercised by the pipeline tests); a fresh listener has no
        // memory, so the rows reach the database and the fingerprint dedup
        // must report them as DUPLICATE.
        newListener().capture(targetContext, sbn)

        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && CaptureMetrics.dbDuplicated.get() < 3) {
            Thread.sleep(100)
        }
        assertEquals(3, CaptureMetrics.dbDuplicated.get())
        val db = InboxDatabase(targetContext)
        val finalCount = try { db.recent(100).size } finally { db.close() }
        assertEquals("Duplicate capture must not insert extra rows", 3, finalCount)
    }

    @Test
    fun concurrentOrSequentialBatches_noDeadlockOrCrosstalk() {
        val listener = newListener()
        val count = 4
        for (i in 1..count) {
            val sbn = buildNotification("Convo$i", listOf("Sender$i" to "Body-$i"), tag = "tag-$i")
            listener.capture(targetContext, sbn)
        }

        val rows = awaitRows(count, timeoutMs = 8000)
        assertEquals(count, rows.size)

        for (i in 1..count) {
            assertTrue("Must contain Body-$i", rows.any { it.body == "Body-$i" && it.sender == "Sender$i" })
        }
    }

    @Test
    fun transactionRollback_neverReportsCommitted() {
        createFaultTrigger(-1)

        val listener = newListener()
        val sbn = buildNotification("RollbackConvo", listOf("Dave" to "Never committed"))

        listener.capture(targetContext, sbn)

        // Wait for the FIRST attempt to be observed as failed, then jump on the
        // window before the terminal attempt. See awaitFirstFailureThenDisarm.
        awaitFirstFailureThenDisarm()
        assertEquals("Rollback transaction must never report committed", 0, CaptureMetrics.dbCommitted.get())

        val rows = awaitRows(1, timeoutMs = 8000)
        assertEquals(1, rows.size)
        assertEquals(1, CaptureMetrics.dbCommitted.get())
    }

    @Test
    fun noSubsequentNotifications_scheduledRetryCompletes() {
        createFaultTrigger(1)

        val listener = newListener()
        val sbn = buildNotification("StandaloneRetry", listOf("Eve" to "Should recover automatically"))

        listener.capture(targetContext, sbn)

        // Disarm right after the FIRST observed failure and NEVER call
        // capture() again. The scheduled retry must complete on its own —
        // that is the whole point of this test. See awaitFirstFailureThenDisarm
        // for why a fixed sleep is a race here.
        awaitFirstFailureThenDisarm()

        val rows = awaitRows(1, timeoutMs = 8000)
        assertEquals("Scheduled retry must complete without subsequent notifications", 1, rows.size)
        assertEquals("Should recover automatically", rows[0].body)
    }

    @Test
    fun simulatedProcessDeath_inMemoryBatchLostWithoutJournal() {
        createFaultTrigger(-1)

        val listener = newListener()
        val sbn = buildNotification("DoomedConvo", listOf("Frank" to "In-memory only"))

        listener.capture(targetContext, sbn)
        Thread.sleep(500)

        listener.onDestroy()
        // The test destroyed its own listener; tearDown must not do it again.
        listenerDestroyed = true

        val db = InboxDatabase(targetContext)
        val entries = try { db.recent(10) } finally { db.close() }
        assertFalse("Uncommitted in-memory message cannot survive listener destruction without journal",
            entries.any { it.body == "In-memory only" })
    }
}
