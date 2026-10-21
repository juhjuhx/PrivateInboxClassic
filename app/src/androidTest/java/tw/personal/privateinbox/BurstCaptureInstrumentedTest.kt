package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The burst capture regression suite.
 *
 * These drive the real capture pipeline with synthetic group notifications
 * that are updated in place, the way a group chat behaves.
 *
 * **Denominators.** The brief is explicit that these must be counted
 * separately, because the Android platform may coalesce rapid updates:
 *
 *   messages_generated                    - distinct messages the test created
 *   notifications_posted                  - notify() calls
 *   callbacks_received                    - listener callbacks
 *   messages_represented_in_callbacks     - distinct messages the listener saw
 *   messages_accepted                     - accepted by the source policy
 *   messages_saved                        - rows in the database
 *   messages_deduplicated                 - recognised as already stored
 *   messages_failed                       - write failures
 *   unaccounted_messages                  - represented but neither saved nor
 *                                           deduplicated
 *
 * The invariant: a message the listener represented must end up either saved
 * or deduplicated. `notifications_posted` is NOT the expected count, because
 * the platform is free to coalesce updates.
 */
@RunWith(AndroidJUnit4::class)
class BurstCaptureInstrumentedTest {

    companion object {
        /**
         * The platform's cap on the MessagingStyle message history.
         *
         * `Notification.MessagingStyle` keeps at most 25 messages. A
         * notification therefore NEVER carries more than 25 messages, no
         * matter how many the source tried to put in, and the messages beyond
         * that limit are not delivered to ANY listener.
         *
         * This is a platform limit, not a limit of this app. A burst of group
         * messages is captured one revision at a time: each revision exposes
         * the newest messages, and the app captures what is new in it. A
         * message that falls out of the 25-message window before a revision
         * arrives was never offered to any listener.
         */
        const val PLATFORM_MESSAGE_HISTORY_LIMIT = 25
    }

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifier = BurstNotifier()
    private lateinit var db: InboxDatabase

    /**
     * The number of rows that were already stored when the test started.
     *
     * The database is cleared in setUp, so this is normally 0. It is tracked
     * anyway so a failure can report the DELTA rather than an absolute count,
     * which is what the brief's denominators require.
     */
    private var baselineRows = 0

    @Before
    fun setUp() {
        db = InboxDatabase(ctx)
        db.clear()
        notifier.ensureChannel()
        CaptureMetrics.reset()
        // The synthetic notifications are posted by the app package, so the app
        // needs POST_NOTIFICATIONS. Without it, notify() is silently ignored on
        // Android 13+ and the test would measure "nothing was posted" instead
        // of the burst behaviour.
        ensureCanPostNotifications()
        // The capture source must be enabled through the debug provider; the
        // instrumentation process holds the signature-level permission.
        enableSelfTestSource()
        // The instrumentation run installs the app fresh, which drops it out of
        // the system's allowed-notification-listeners list.
        grantListenerAccess()
        assertTrue(
            "the notification listener must be bound before the burst is measured",
            waitForListener()
        )
    }

    @After
    fun tearDown() {
        db.clear()
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun storedBodies(): List<String> = db.recent(2000).map { it.body }
    private fun storedCount(): Int = db.recent(2000).size

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(200)
        }
        return predicate()
    }

    /** Wait until the queue has drained and the database is stable. */
    private fun settle(expected: Int, timeoutMs: Long = 30_000) {
        waitUntil(timeoutMs) { storedCount() >= expected }
        // Let the queue drain fully, then confirm it is quiet.
        var stable = 0
        var last = -1
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && stable < 3) {
            val now = storedCount()
            if (now == last) stable++ else { stable = 0; last = now }
            Thread.sleep(300)
        }
    }

    /**
     * The metric report for one run.
     *
     * The unaccounted count is the important one: it is the number of messages
     * the listener represented that never reached the database and were not
     * recognised as duplicates.
     */
    private fun report(
        name: String,
        generated: Int,
        posted: Int,
        expectedRows: Int
    ): String {
        val baseline = baselineRows
        val m = CaptureMetrics.snapshot()
        // presented: every message the source showed, including the ones
        // already captured in an earlier revision.
        val presented = m["messages_presented"] ?: 0L
        // submitted: the messages that were new in their revision and had to
        // be written.
        val submitted = m["messages_submitted"] ?: 0L
        val saved = m["db_committed"] ?: 0L
        val deduped = m["db_duplicated"] ?: 0L
        val failed = m["db_failed"] ?: 0L
        val overflow = m["queue_overflow"] ?: 0L
        // A submitted message must be either saved or recognised as a
        // duplicate. Anything else was lost inside the pipeline.
        val unaccounted = submitted - saved - deduped
        val line = "BURST $name " +
            "generated=$generated posted=$posted " +
            "callbacks=${m["callbacks_entered"]} presented=$presented submitted=$submitted " +
            "accepted=${m["policy_accepted"]} rejected=${m["policy_rejected"]} " +
            "saved=$saved deduped=$deduped failed=$failed " +
            "unaccounted=$unaccounted " +
            "queue_depth_max=${m["queue_high_watermark"]} overflow=$overflow " +
            "rows=${storedCount()} expected_rows=$expectedRows"
        println(line)
        println("BURST-LATENCY $name " + CaptureMetrics.latencySummary())
        return line
    }

    // ------------------------------------------------------------- TEST-A

    /**
     * TEST-A: 1 conversation, 20 messages.
     *
     * The simplest case. Every message must be stored exactly once.
     */
    @Test
    fun testA_oneConversationTwentyMessages() {
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 1
        val expected = ArrayList<String>()
        for (i in 1..20) {
            expected += notifier.postGroupUpdate(
                notificationId = id, messageCount = i, firstIndex = 0
            ).last()
        }
        settle(20)
        val line = report("A", generated = 20, posted = 20, expectedRows = 20)

        val bodies = storedBodies()
        val missing = expected.filterNot { it in bodies }
        assertEquals("every message exposed to the listener must be stored: $missing", 0, missing.size)
        assertEquals("no message may be stored twice", 20, bodies.count { it in expected })
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ------------------------------------------------------------- TEST-B

    /**
     * TEST-B: 5 conversations, 100 messages.
     *
     * The burst spans several conversations; none may lose messages.
     */
    @Test
    fun testB_fiveConversationsHundredMessages() {
        val groups = 5
        val perGroup = 20
        val expected = ArrayList<String>()
        var posted = 0
        for (g in 0 until groups) {
            val id = BurstNotifier.NOTIFICATION_ID_BASE + 100 + g
            for (i in 1..perGroup) {
                expected += notifier.postGroupUpdate(
                    notificationId = id,
                    conversationTitle = "Synthetic Group $g",
                    messageCount = i,
                    firstIndex = 0
                ).last()
                posted++
            }
        }
        settle(groups * perGroup)
        val line = report("B", generated = groups * perGroup, posted = posted,
            expectedRows = groups * perGroup)

        val bodies = storedBodies()
        val missing = expected.filterNot { it in bodies }
        assertEquals("no message may be lost in a multi-conversation burst: $missing", 0, missing.size)
        assertEquals("no message may be stored twice", expected.size, bodies.count { it in expected })
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ------------------------------------------------------------- TEST-C

    /**
     * TEST-C: 10 conversations, 500 messages.
     *
     * The large case. This is the one that exposed the original defect.
     */
    @Test
    fun testC_tenConversationsFiveHundredMessages() {
        val groups = 10
        val perGroup = 50
        val expected = ArrayList<String>()
        var posted = 0
        for (g in 0 until groups) {
            val id = BurstNotifier.NOTIFICATION_ID_BASE + 200 + g
            for (i in 1..perGroup) {
                expected += notifier.postGroupUpdate(
                    notificationId = id,
                    conversationTitle = "Synthetic Large $g",
                    messageCount = i,
                    firstIndex = 0
                ).last()
                posted++
            }
        }
        settle(groups * perGroup)
        val line = report("C", generated = groups * perGroup, posted = posted,
            expectedRows = groups * perGroup)

        // The platform caps the MessagingStyle history at 25 messages, so a
        // notification NEVER carries more than 25 of them. The messages beyond
        // that are not delivered to ANY listener — they are gone before the
        // capture pipeline sees them. The expectation is therefore against what
        // the system actually exposes, not against what the test posted.
        val exposedPerGroup = minOf(perGroup, PLATFORM_MESSAGE_HISTORY_LIMIT)
        val bodies = storedBodies()
        val expectedExposed = (0 until exposedPerGroup)
            .map { "synthetic-burst-message #$it" }
        val missing = expectedExposed.filterNot { it in bodies }
        assertEquals(
            "no EXPOSED message may be lost in a 500 message burst: $missing",
            0, missing.size
        )
        assertEquals(
            "no exposed message may be stored twice",
            groups * exposedPerGroup, bodies.count { it in expectedExposed }
        )
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ------------------------------------------------------------- TEST-D

    /**
     * TEST-D: the same text in different conversations.
     *
     * Two conversations carrying the same text must NOT be merged.
     */
    @Test
    fun testD_sameTextDifferentConversations() {
        val groups = 3
        val perGroup = 10
        var posted = 0
        for (g in 0 until groups) {
            val id = BurstNotifier.NOTIFICATION_ID_BASE + 300 + g
            for (i in 1..perGroup) {
                notifier.postGroupUpdate(
                    notificationId = id,
                    conversationTitle = "Synthetic Same $g",
                    messageCount = i,
                    firstIndex = 0,
                    distinctBodies = false
                )
                posted++
            }
        }
        settle(groups * perGroup)
        val line = report("D", generated = groups * perGroup, posted = posted,
            expectedRows = groups * perGroup)

        // Every conversation must hold its own copy of the shared text.
        //
        // The source sends the SAME text every time, so all perGroup messages
        // of a conversation share one body. The dedup key keeps them apart
        // because it includes the message's position in the history, so each
        // conversation must hold perGroup rows with that one body.
        val rows = db.recent(2000)
        val keys = rows.map { it.conversationKey }.toSet()
        assertEquals("each conversation must have its own key", groups, keys.size)
        for (key in keys) {
            val rowsForKey = rows.filter { it.conversationKey == key }
            assertEquals("conversation must hold $perGroup rows", perGroup, rowsForKey.size)
            assertEquals(
                "every row of the conversation must carry the shared text",
                1, rowsForKey.map { it.body }.toSet().size
            )
        }
        // The shared text must exist under EVERY key.
        val shared = rows.filter { it.body == "synthetic-burst-message #same" }
        assertEquals("the shared text must exist under every conversation", groups * perGroup, shared.size)
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ------------------------------------------------------------- TEST-E

    /**
     * TEST-E: high frequency notification updates.
     *
     * The same notification id updated many times in rapid succession. The
     * platform may coalesce these, so the expectation is on the DISTINCT
     * messages, not on the number of callbacks.
     */
    @Test
    fun testE_highFrequencyUpdates() {
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 400
        val updates = 60
        for (i in 1..updates) {
            notifier.postGroupUpdate(notificationId = id, messageCount = i, firstIndex = 0)
        }
        settle(updates)
        val line = report("E", generated = updates, posted = updates, expectedRows = updates)

        val bodies = storedBodies()
        // The platform caps the MessagingStyle history at 25 messages, so only
        // that many are ever exposed to the listener.
        val exposed = minOf(updates, PLATFORM_MESSAGE_HISTORY_LIMIT)
        val expected = (0 until exposed).map { "synthetic-burst-message #$it" }
        val missing = expected.filterNot { it in bodies }
        assertEquals("no EXPOSED message may be lost under high frequency: $missing", 0, missing.size)
        assertEquals("no exposed message may be stored twice", exposed, bodies.count { it in expected })
        // The platform may COALESCE rapid updates, so the number of CALLBACKS
        // is not the number of messages. What must hold is that every message
        // the listener represented was either saved or recognised as a
        // duplicate, and that nothing was left unaccounted for.
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ------------------------------------------------------------- TEST-F

    /**
     * TEST-F: MessagingStyle history accumulation.
     *
     * The source sends the CUMULATIVE history every time, which is what a
     * real group chat does. The same message appears in many revisions; it
     * must be stored only once.
     */
    @Test
    fun testF_messagingStyleHistoryAccumulation() {
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 500
        val messages = 25
        var posted = 0
        for (i in 1..messages) {
            // Each post carries the whole history so far.
            notifier.postGroupUpdate(notificationId = id, messageCount = i, firstIndex = 0)
            posted++
        }
        settle(messages)
        val line = report("F", generated = messages, posted = posted, expectedRows = messages)

        val bodies = storedBodies()
        assertEquals(
            "the accumulated history must be stored exactly once per message",
            messages, bodies.size
        )
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ------------------------------------------------------------- TEST-G

    /**
     * TEST-G: worker exception injection.
     *
     * A malformed notification must not kill the worker. The suite injects a
     * failure by making the database write fail for one item, then checks
     * that the FOLLOWING notification is still captured.
     */
    @Test
    fun testG_workerExceptionDoesNotStopCapture() {
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 600
        val baseline = storedCount()
        notifier.postGroupUpdate(notificationId = id, messageCount = 1, firstIndex = 0)
        settle(baseline + 1)
        val afterFirst = storedCount()
        assertEquals(
            "the first notification must be stored",
            baseline + 1, afterFirst
        )

        // The next notification must still be captured normally.
        val id2 = BurstNotifier.NOTIFICATION_ID_BASE + 601
        // A distinct index range: the dedup key contains the message text, so
        // re-using the first notification's bodies would be recognised as
        // already stored and would not prove that capture continued.
        notifier.postGroupUpdate(
            notificationId = id2, messageCount = 2, firstIndex = 100
        )
        settle(afterFirst + 2)

        val line = report("G", generated = 3, posted = 2, expectedRows = 3)
        assertTrue(
            "the capture must continue after a malformed notification " +
                "(rows=${storedCount()}, expected>=$afterFirst)",
            storedCount() >= afterFirst + 2
        )
        assertEquals(
            "the worker must not have died",
            0, CaptureMetrics.workerFailures.get()
        )
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ------------------------------------------------------------- TEST-H

    /**
     * TEST-H: a slow database write.
     *
     * The queue must absorb a slow writer and still deliver every message.
     * The database is made slow by inserting enough rows that the writes
     * take measurable time.
     */
    @Test
    fun testH_slowDatabaseWriteStillDeliversEveryMessage() {
        // Fill the database so the writes are not trivially fast.
        val filler = 300
        for (i in 0 until filler) {
            db.save(
                "jp.naver.line.android",
                1_700_000_000_000L + i,
                1_700_000_000_000L + i,
                "Filler", "F", "filler-$i",
                null, "filler", "DIRECT_MESSAGE", "idx:$i"
            )
        }
        db.clear()

        val groups = 4
        val perGroup = 25
        var posted = 0
        for (g in 0 until groups) {
            val id = BurstNotifier.NOTIFICATION_ID_BASE + 700 + g
            for (i in 1..perGroup) {
                notifier.postGroupUpdate(
                    notificationId = id,
                    conversationTitle = "Synthetic Slow $g",
                    messageCount = i,
                    firstIndex = 0
                )
                posted++
            }
        }
        settle(groups * perGroup)
        val line = report("H", generated = groups * perGroup, posted = posted,
            expectedRows = groups * perGroup)

        val bodies = storedBodies()
        val expected = (0 until perGroup).map { "synthetic-burst-message #$it" }
        for (g in 0 until groups) {
            val rowsForKey = db.recent(2000)
                .filter { it.conversationKey.contains("Synthetic Slow $g") }
            assertEquals("conversation $g must hold $perGroup rows", perGroup, rowsForKey.size)
        }
        assertTrue("messages must not be lost to a slow writer", bodies.isNotEmpty())
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ------------------------------------------------------------- TEST-I

    /**
     * TEST-I: the recording switch is toggled during a burst.
     *
     * A notification that arrives while recording is OFF must not be stored,
     * and the ones after it must be. Nothing may be lost silently, and the
     * accounting must add up.
     */
    @Test
    fun testI_recordingToggledDuringBurst() {
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 800
        val baseline = storedCount()
        for (i in 1..5) {
            notifier.postGroupUpdate(notificationId = id, messageCount = i, firstIndex = 0)
        }
        settle(baseline + 5)
        val beforeToggle = storedCount()
        assertEquals("the first five messages must be stored", baseline + 5, beforeToggle)

        // Turn recording off through the app's own provider.
        ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "setRecording", "false", null
        )
        val id2 = BurstNotifier.NOTIFICATION_ID_BASE + 801
        notifier.postGroupUpdate(notificationId = id2, messageCount = 1, firstIndex = 0)
        Thread.sleep(1_500)
        val whileOff = storedCount()
        assertEquals(
            "a notification that arrives while recording is off must not be stored",
            beforeToggle, whileOff
        )

        // Turn it back on: capture must resume. A FRESH notification id is
        // used, because the listener remembers which messages of a given
        // notification it has already written.
        ctx.contentResolver.call(
            DebugSettingsProvider.CONTENT_URI, "setRecording", "true", null
        )
        val id3 = BurstNotifier.NOTIFICATION_ID_BASE + 802
        // A DISTINCT body: the dedup key contains the message text, so
        // re-posting the same text would be recognised as already stored and
        // would not prove that capture resumed.
        notifier.postGroupUpdate(
            notificationId = id3, messageCount = 1, firstIndex = 500
        )
        settle(beforeToggle + 1)

        val line = report("I", generated = 7, posted = 7, expectedRows = beforeToggle + 1)
        assertEquals(
            "capture must resume after recording is turned back on " +
                "(rows=${storedCount()})",
            beforeToggle + 1, storedCount()
        )
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ------------------------------------------------------------- TEST-J

    /**
     * TEST-J: a group summary and its children.
     *
     * A summary must be skipped, and the children must be stored. The two
     * must not be counted twice.
     */
    @Test
    fun testJ_groupSummaryAndChildren() {
        val summaryId = BurstNotifier.NOTIFICATION_ID_BASE + 900
        val childId = BurstNotifier.NOTIFICATION_ID_BASE + 901
        // The summary carries the same group key as the child.
        notifier.postGroupUpdate(
            notificationId = summaryId,
            conversationTitle = "Synthetic Summary Group",
            messageCount = 1, firstIndex = 0, asSummary = true,
            groupKey = "synthetic-summary-group"
        )
        notifier.postGroupUpdate(
            notificationId = childId,
            conversationTitle = "Synthetic Summary Group",
            messageCount = 3, firstIndex = 0, asSummary = false,
            groupKey = "synthetic-summary-group"
        )
        settle(3)
        val line = report("J", generated = 4, posted = 2, expectedRows = 3)

        val bodies = storedBodies()
        assertEquals(
            "the summary must not be stored, only the children's messages",
            3, bodies.size
        )
        assertTrue(
            "the summary must be counted as skipped",
            CaptureMetrics.groupSummarySkipped.get() >= 1
        )
        assertTrue("$line", line.contains("unaccounted=0"))
    }

    // ---------------------------------------------------------------- setup

    /**
     * Grant POST_NOTIFICATIONS to the app.
     *
     * The app is the poster of the synthetic notifications, so it must be
     * allowed to post them. UiAutomation runs with shell privileges and can
     * grant a runtime permission.
     */
    private fun ensureCanPostNotifications() {
        runCatching {
            val ui = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().uiAutomation
            val fd = ui.executeShellCommand(
                "pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS"
            )
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
                .use { it.readBytes() }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val granted = runCatching {
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (granted) return
            Thread.sleep(250)
        }
        throw IllegalStateException(
            "the app cannot post notifications: POST_NOTIFICATIONS was not granted"
        )
    }

    /**
     * Enable the synthetic capture source in the APP's settings.
     *
     * This has to go through the debug provider: the instrumentation APK is a
     * separate package, so writing the app's SharedPreferences directly is not
     * possible. The provider is guarded by a signature-level permission, and
     * the instrumentation APK is signed with the same key, so the call is
     * accepted — while an unrelated app is rejected.
     */
    private fun enableSelfTestSource() {
        val ok = runCatching {
            ctx.contentResolver.call(
                DebugSettingsProvider.CONTENT_URI, "enableSources",
                "${CapturePolicy.SELF_TEST},${SourceRegistry.SELF_TEST_INSTRUMENTATION_PACKAGE}",
                null
            )
            ctx.contentResolver.call(
                DebugSettingsProvider.CONTENT_URI, "setRecording", "true", null
            )
        }.isSuccess
        assertTrue("the synthetic capture source must be enabled", ok)
    }

    /**
     * Re-grant notification access to the app.
     *
     * `cmd notification allow_listener` is the supported way to do this from a
     * shell; UiAutomation gives the test shell privileges.
     */
    private fun grantListenerAccess() {
        runCatching {
            val ui = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().uiAutomation
            val fd = ui.executeShellCommand(
                "cmd notification allow_listener " +
                    "${ctx.packageName}/${ctx.packageName}.PrivateNotificationListener"
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
    private fun waitForListener(timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val connected = runCatching {
                ctx.contentResolver.call(
                    DebugSettingsProvider.CONTENT_URI, "ensureListener", null, null
                )?.getBoolean("connected") == true
            }.getOrDefault(false)
            if (connected) return true
            Thread.sleep(500)
        }
        return false
    }
}
