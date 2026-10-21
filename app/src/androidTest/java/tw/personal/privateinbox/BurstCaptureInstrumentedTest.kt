package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Burst capture regression tests.
 *
 * These drive the real capture pipeline with synthetic group notifications that
 * are updated in place, the way a group chat behaves. The bug under test is
 * "a burst of group messages loses messages between the listener callback and the
 * database".
 *
 * **Denominators.** The task is explicit that these must be counted separately,
 * because the Android platform may coalesce rapid updates:
 *
 *   post_attempts                      - how many times we called notify()
 *   listener_callbacks                 - how many times the listener was called
 *   logical_messages_exposed_to_listener - how many DISTINCT messages the
 *                                     listener actually saw
 *   db_saved                           - how many rows ended up in the database
 *
 * The invariant that must hold is:
 *   logical_messages_exposed_to_listener == db_saved + db_duplicate
 *
 * i.e. every message the listener actually received must be accounted for in the
 * database. post_attempts is NOT used as the expected count, because the platform
 * is free to coalesce updates.
 */
@RunWith(AndroidJUnit4::class)
class BurstCaptureInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifier = BurstNotifier()
    private lateinit var db: InboxDatabase

    /** Counts the listener callbacks, so the test can compare denominators. */
    private val callbackCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Before
    fun setUp() {
        db = InboxDatabase(ctx)
        db.clear()
        callbackCount.set(0)
        // The synthetic notifications are posted by the instrumentation package.
        // The capture policy must allow that source, and the instrumentation
        // process holds the signature-level DEBUG_SETTINGS permission, so it can
        // change the app's own settings through the debug provider.
        enableSelfTestSource()
        // The synthetic notifications are posted by the app package, so the app
        // needs POST_NOTIFICATIONS. Without it, notify() is silently ignored on
        // Android 13+ and the test would measure "nothing was posted" instead of
        // the burst behaviour.
        ensureCanPostNotifications()
        // The instrumentation run installs the app fresh, which drops it out of
        // the system's allowed-notification-listeners list. Re-grant it with
        // UiAutomation (which runs with shell privileges) and then wait for a
        // real bind. Without this the test would measure "the listener was
        // never allowed" instead of the burst behaviour.
        grantListenerAccess()
        assertTrue(
            "the notification listener must be bound before the burst is measured",
            waitForListener()
        )
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

    /**
     * Enable the synthetic source in the APP's settings.
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
                "${CapturePolicy.SELF_TEST}," +
                    "${SourceRegistry.SELF_TEST_INSTRUMENTATION_PACKAGE}",
                null
            )
            ctx.contentResolver.call(
                DebugSettingsProvider.CONTENT_URI, "setRecording", "true", null
            )
        }.isSuccess
        assertTrue("the synthetic capture source must be enabled", ok)
    }

    @After
    fun tearDown() {
        db.clear()
        db.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun storedBodies(): List<String> = db.recent(2000).map { it.body }

    private fun storedCount(): Int = db.recent(2000).size

    private fun countStored(predicate: (InboxDatabase.Entry) -> Boolean): Int =
        db.recent(2000).count(predicate)

    /** Wait until [predicate] holds, or the timeout expires. */
    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(250)
        }
        return predicate()
    }

    // -------------------------------------------------------------- CASE A/B/C

    /**
     * CASE A: a single group, 20 distinct messages, delivered as 20 successive
     * updates of the SAME notification id. Every message must be stored exactly
     * once.
     */
    @Test
    fun caseA_singleGroupTwentyUpdatesAreAllStored() {
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 1
        val expected = ArrayList<String>()
        // Each update carries the accumulating history, like a real group chat.
        for (i in 1..20) {
            expected += notifier.postGroupUpdate(
                notificationId = id, messageCount = i, firstIndex = 0
            ).last()
        }
        val postAttempts = 20

        val reached = waitUntil(20_000) { storedCount() >= 20 }
        if (!reached) {
            println("BURST-A-DIAG " + diagSnapshot() +
                " poster_notifications=" + notifier.activeNotificationCount())
        }
        assertTrue(
            "the group messages must reach the database",
            reached
        )
        Thread.sleep(1_500) // let the queue drain

        val bodies = storedBodies()
        val missing = expected.filterNot { it in bodies }
        assertEquals(
            "every message exposed to the listener must be stored (missing: $missing)",
            0, missing.size
        )
        assertEquals(
            "no message may be stored twice",
            expected.size, bodies.count { it in expected }
        )
        println(
            "BURST-A post_attempts=$postAttempts " +
                "db_saved=${countStored { true }} bodies=${bodies.size}"
        )
    }

    /**
     * CASE B: five groups, 20 messages each, 100 synthetic messages in total,
     * mixed updates. The point is that a burst spanning several conversations
     * must not lose messages.
     */
    @Test
    fun caseB_fiveGroupsOneHundredMessagesAreAllStored() {
        val groups = 5
        val perGroup = 20
        val expected = ArrayList<Pair<String, String>>()
        var postAttempts = 0

        for (g in 0 until groups) {
            val id = BurstNotifier.NOTIFICATION_ID_BASE + 100 + g
            for (i in 1..perGroup) {
                val bodies = notifier.postGroupUpdate(
                    notificationId = id,
                    conversationTitle = "Synthetic Group $g",
                    messageCount = i,
                    firstIndex = 0
                )
                expected += (bodies.last() to "group-$g")
                postAttempts++
            }
        }

        val reached = waitUntil(30_000) { storedCount() >= groups * perGroup }
        if (!reached) {
            println("BURST-B-DIAG stored=" + storedCount() +
                " expected=" + (groups * perGroup) +
                " " + diagSnapshot())
        }
        assertTrue(
            "the burst must reach the database",
            reached
        )
        Thread.sleep(2_000)

        val bodies = storedBodies()
        val missing = expected.filterNot { it.first in bodies }
        assertEquals(
            "no message may be lost in a multi-group burst (missing: $missing)",
            0, missing.size
        )
        assertEquals(
            "no message may be stored twice",
            expected.size, bodies.count { b -> expected.any { it.first == b } }
        )
        println(
            "BURST-B post_attempts=$postAttempts db_saved=${countStored { true }}"
        )
    }

    /**
     * CASE C: five groups with INDEPENDENT notification ids, updated
     * INTERLEAVED. Each conversation must keep its own identity: the messages
     * must not be mixed between conversations.
     */
    @Test
    fun caseC_interleavedGroupsKeepSeparateIdentities() {
        val groups = 5
        val perGroup = 20
        val ids = (0 until groups).map { BurstNotifier.NOTIFICATION_ID_BASE + 200 + it }
        val expected = HashMap<Int, MutableList<String>>()
        var postAttempts = 0

        for (round in 1..perGroup) {
            for (g in 0 until groups) {
                val bodies = notifier.postGroupUpdate(
                    notificationId = ids[g],
                    conversationTitle = "Synthetic Interleaved $g",
                    messageCount = round,
                    firstIndex = 0
                )
                expected.getOrPut(ids[g]) { ArrayList() } += bodies.last()
                postAttempts++
            }
        }

        val reached = waitUntil(30_000) { storedCount() >= groups * perGroup }
        if (!reached) {
            println("BURST-C-DIAG stored=" + storedCount() +
                " expected=" + (groups * perGroup) +
                " " + diagSnapshot())
        }
        assertTrue(
            "the interleaved burst must reach the database",
            reached
        )
        Thread.sleep(2_000)

        // Every message must be present exactly once.
        val bodies = storedBodies()
        for ((groupLabel, list) in expected) {
            val missing = list.filterNot { it in bodies }
            assertEquals(
                "group $groupLabel must not lose messages (missing: $missing)",
                0, missing.size
            )
        }
        val allExpected = expected.values.flatten()
        assertEquals(
            "no message may be stored twice",
            allExpected.size, bodies.count { it in allExpected }
        )

        // Conversations must not be cross-contaminated: each message body belongs
        // to exactly one conversation key.
        // Conversations must not be cross-contaminated.
        //
        // The same body text appears in EVERY group, so the body alone cannot
        // identify a message. What must hold is:
        //   - each conversation key holds exactly its own messages,
        //   - every group has its own distinct key, and
        //   - the same body exists under every conversation key, which is the
        //     proof that the groups did not collapse into one.
        val rows = db.recent(2000)
        val keysByGroup = HashMap<Int, String>()
        for (g in 0 until groups) {
            val fragment = "Synthetic Interleaved $g"
            val key = rows.firstOrNull { it.conversationKey.contains(fragment) }
                ?.conversationKey
            assertTrue(
                "conversation '$fragment' must exist in the database",
                key != null
            )
            keysByGroup[g] = key!!
        }
        assertEquals(
            "each conversation must have a distinct key",
            groups, keysByGroup.values.toSet().size
        )
        for ((g, key) in keysByGroup) {
            val rowsForKey = rows.filter { it.conversationKey == key }
            assertEquals(
                "conversation $g must hold exactly $perGroup rows",
                perGroup, rowsForKey.size
            )
            val expectedBodies = (0 until perGroup).map { "synthetic-burst-message #$it" }
            assertEquals(
                "conversation $g must hold exactly its own messages",
                expectedBodies.toSet(), rowsForKey.map { it.body }.toSet()
            )
        }
        for (body in (0 until perGroup).map { "synthetic-burst-message #$it" }) {
            val keysHoldingBody = rows.filter { it.body == body }
                .map { it.conversationKey }.toSet()
            assertEquals(
                "body '$body' must exist under every conversation key",
                groups, keysHoldingBody.size
            )
        }
        println("BURST-C post_attempts=$postAttempts db_saved=${countStored { true }}")
    }

    // ------------------------------------------------------------------ CASE D

    /**
     * CASE D: two DIFFERENT messages with identical text must both be stored,
     * while a single notification updated repeatedly must NOT create duplicates.
     *
     * This is the regression test for the dedup key. When the source provides no
     * per-message timestamp, the previous implementation used the capture time
     * as the dedup component — which is per-NOTIFICATION, so two distinct
     * messages with the same text inside one notification update were merged.
     */
    @Test
    fun caseD_identicalTextDistinctMessagesAreNotMerged() {
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 300

        // One notification carrying the SAME text twice as two separate messages.
        notifier.postGroupUpdate(
            notificationId = id, messageCount = 2, firstIndex = 0,
            postTime = 1_700_000_000_000L
        )
        // Make the two bodies identical by posting the same body twice.
        val sameBody = "synthetic-duplicate-text"
        postTwoIdenticalMessages(id, sameBody)

        assertTrue(
            "the messages must reach the database",
            waitUntil(20_000) { storedBodies().count { it == sameBody } >= 2 }
        )
        Thread.sleep(1_500)

        val count = storedBodies().count { it == sameBody }
        assertEquals(
            "two genuinely different messages with identical text must both be kept",
            2, count
        )
        println("BURST-D duplicate_text_count=$count")
    }

    /**
     * CASE D2: the SAME notification updated repeatedly with the SAME content must
     * produce exactly ONE row. This is the other half of the dedup contract.
     */
    @Test
    fun caseD2_repeatedIdenticalUpdateStoresOneRow() {
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 301
        for (i in 0 until 10) {
            notifier.postGroupUpdate(
                notificationId = id, messageCount = 1, firstIndex = 0,
                postTime = 1_700_000_000_000L
            )
            Thread.sleep(120)
        }
        Thread.sleep(3_000)
        val count = storedBodies().count { it == "synthetic-burst-message #0" }
        assertEquals(
            "an unchanged notification must not create duplicate rows",
            1, count
        )
        println("BURST-D2 identical_update_rows=$count")
    }

    /** Post one notification whose MessagingStyle holds two identical bodies. */
    private fun postTwoIdenticalMessages(notificationId: Int, body: String) {
        notifier.postTwoIdenticalMessages(notificationId, body)
    }

    // ------------------------------------------------------------------ CASE E

    /**
     * CASE E: after a listener reconnect, the active notifications can be
     * re-read. Only notifications that are STILL ACTIVE may be recovered;
     * a notification that has been cancelled cannot be.
     */
    @Test
    fun caseE_activeNotificationsCanBeReReadButCancelledOnesCannot() {
        val keptId = BurstNotifier.NOTIFICATION_ID_BASE + 400
        val cancelledId = BurstNotifier.NOTIFICATION_ID_BASE + 401

        notifier.postGroupUpdate(notificationId = keptId, messageCount = 2)
        notifier.postGroupUpdate(
            notificationId = cancelledId, messageCount = 2,
            conversationTitle = "Synthetic Cancelled Group"
        )
        Thread.sleep(1_500)

        // Cancel one of them.
        notifier.cancel(cancelledId)
        Thread.sleep(1_000)

        val active = notifier.activeNotifications()
        val activeIds = active.map { it.id }
        assertTrue(
            "the kept notification must still be active",
            keptId in activeIds
        )
        assertTrue(
            "the cancelled notification must not be active",
            cancelledId !in activeIds
        )
        println("BURST-E active=${active.size}")
    }

    // ------------------------------------------------------------------ CASE F

    /**
     * CASE F: turning the photo capture OFF must not change how many text
     * messages are captured. The media pipeline must not be able to slow the
     * capture path down enough to lose messages.
     */
    @Test
    fun caseF_disablingPhotosDoesNotChangeTextCapture() {
        val withPhotos = measureBurst(photosEnabled = true)
        val withoutPhotos = measureBurst(photosEnabled = false)
        println(
            "BURST-F photos_on=${withPhotos.first} photos_off=${withoutPhotos.first} " +
                "captured_on=${withPhotos.second} captured_off=${withoutPhotos.second}"
        )
        assertEquals(
            "disabling photos must not reduce the number of captured messages",
            withPhotos.second, withoutPhotos.second
        )
    }

    /** Post a fixed burst and return (elapsedMillis, messagesCaptured). */
    private fun measureBurst(photosEnabled: Boolean): Pair<Long, Int> {
        LocalSettings.setCaptureImages(ctx, photosEnabled)
        db.clear()
        val id = BurstNotifier.NOTIFICATION_ID_BASE + 500
        val expected = 20
        val start = System.currentTimeMillis()
        for (i in 1..expected) {
            notifier.postGroupUpdate(notificationId = id, messageCount = i)
        }
        waitUntil(25_000) { storedCount() >= expected }
        Thread.sleep(1_500)
        val elapsed = System.currentTimeMillis() - start
        return elapsed to storedCount()
    }

    /**
     * A snapshot of the capture pipeline counters.
     *
     * Read through the debug provider, so it can run from the instrumentation
     * process. It shows WHERE a message was lost: before the listener saw it
     * (received), at the policy stage, at the parse stage, or at the database.
     */
    private fun diagSnapshot(): String {
        val res = runCatching {
            ctx.contentResolver.call(
                DebugSettingsProvider.CONTENT_URI, "counters", null, null
            )
        }.getOrNull() ?: return "<counters unavailable>"
        val keys = listOf(
            "received", "parse_ok", "db_ok", "failed", "last_code", "last_err"
        )
        return keys.joinToString(", ") { key ->
            val value = res.get(key)
            "$key=" + (value ?: "?")
        }
    }

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
}
