package tw.personal.privateinbox

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T05-B — SYSTEM notification E2E (category B), building on the existing
 * SelfTest harness.
 *
 * What "system" means here, precisely: the notification is POSTED through the
 * Android NotificationManager of the devtest app, so it traverses the REAL
 * system notification pipeline — system_server routing, listener binding,
 * `onNotificationPosted` on the bind thread, the production parser, the worker,
 * encryption, commit and the canonical link. Nothing calls `capture()` directly.
 *
 * The posted package is the DEVTEST package (the app itself). It is NOT, and is
 * never claimed to be, a LINE or Telegram notification. Category D
 * (real third-party apps on real hardware) remains REAL_DEVICE_NOT_TESTED.
 *
 * Category C (Activity rendering) is asserted here through the REAL
 * MainActivity, driven the same way the UiSnapshot/lifecycle suites drive it.
 */
@RunWith(AndroidJUnit4::class)
class SystemNotificationE2EInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        // Remove THIS app's self-test notifications from the tray FIRST: they
        // are re-captured by the listener's catch-up, so a clear that happens
        // while one is still posted gets repopulated (the previous run's
        // "first system message" came back as a second row).
        runCatching {
            ctx.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        }
        Thread.sleep(300)
        TestDataIsolation.clearDatabase(ctx)
        // H02: a previous burst test's system notifications may still be
        // draining through the bound listener after the clear (the cancel and
        // the catch-up race through system_server). Wait until the database is
        // STABLE — the count stops changing — before this test's own
        // notifications are posted. All assertions below are then made on the
        // marker-tagged rows only, so leftovers can never fake a PASS.
        val deadline = System.currentTimeMillis() + 30_000
        var last = -1
        var stable = 0
        while (System.currentTimeMillis() < deadline && stable < 3) {
            val n = rows().size
            if (n == last) stable++ else { stable = 0; last = n }
            Thread.sleep(300)
        }
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun systemPostedNotificationIsCapturedLinkedAndRendered() {
        enableSelfTest()
        grantListener()

        // The notification must be posted by the APP package so the self-test
        // source policy accepts it. The debug provider runs inside the app
        // process, so it can post on the app's behalf — that is the closest a
        // test can get to a real user notification without a second fixture app.
        val marker = "sys-e2e-${System.currentTimeMillis()}"
        val poster = object {
            fun post(id: Int, title: String, body: String, shortcutId: String?) {
                val res = runCatching {
                    ctx.contentResolver.call(
                        DebugSettingsProvider.contentUriFor(ctx), "postSelfTest",
                        "$id\u0000$title\u0000$body\u0000${shortcutId ?: ""}", null
                    )
                }.getOrNull()
                check(res?.getBoolean("posted") == true) { "the app failed to post" }
            }

            fun cancelAll() {
                runCatching {
                    ctx.contentResolver.call(
                        DebugSettingsProvider.contentUriFor(ctx), "cancelSelfTest", null, null
                    )
                }
            }
        }
        try {
            // 1. Post through the REAL NotificationManager.
            poster.post(
                id = 0x5201,
                title = "System E2E 聊天",
                body = "first system message $marker",
                shortcutId = "sc-sys-1"
            )

            // 2-6. Wait for the production pipeline to commit + link THIS
            // test's message (identified by its unique marker body), so any
            // leftovers from an earlier test can never fake the count.
            val rows = awaitMarker("first system message $marker", timeoutMs = 20_000)
            assertEquals(
                "exactly one message with this marker committed by the real pipeline",
                1, rows.size
            )
            assertNotNull(
                "the canonical link was created by the listener",
                rows[0].conversationId
            )

            // 7. The REAL MainActivity renders the conversation.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onLoaded(scenario) { activity ->
                    assertEquals(1, activity.entries.size)
                    assertEquals(
                        "the system-posted message renders in one box",
                        1, activity.threads(activity.entries).size
                    )
                }
            }

            // 8. Relaunch: history persists.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onLoaded(scenario) { activity ->
                    assertEquals("history survives a relaunch", 1, activity.entries.size)
                }
            }

            // 9. Rename: a second system notification with a NEW title but the
            //    SAME shortcut must land in the SAME conversation.
            poster.post(
                id = 0x5201,
                title = "System E2E 聊天（改名）",
                body = "second system message $marker",
                shortcutId = "sc-sys-1"
            )
            val renamed = awaitMarker("second system message $marker", timeoutMs = 20_000)
            assertEquals(
                "the renamed second message must be committed",
                1, renamed.size
            )
            assertNotNull("and it must be linked", renamed[0].conversationId)
            assertEquals(
                "the new and the old message must point at the SAME conversation id",
                rows[0].conversationId, renamed[0].conversationId
            )
            // And the UI must still show exactly one conversation for the
            // renamed thread: both rows share one canonical id, so the legacy
            // keys of this conversation group into ONE box.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                onLoaded(scenario) { activity ->
                    val renamedGroups = activity.threads(activity.entries)
                        .filter { thread -> thread.value.any { it.body == "second system message $marker" } }
                    assertEquals(
                        "the renamed conversation still renders as exactly one box",
                        1, renamedGroups.size
                    )
                    assertEquals(
                        "and that box carries both messages",
                        2, renamedGroups.first().value.size
                    )
                }
            }

            // 10. A different shortcut with the same title is another conversation.
            poster.post(
                id = 0x5202,
                title = "System E2E 聊天",
                body = "different group $marker",
                shortcutId = "sc-sys-2"
            )
            awaitRows(minRows = 3, timeoutMs = 20_000)
            val store2 = ConversationStore(ctx)
            try {
                assertEquals(
                    "same title, different shortcut: two conversations",
                    2, store2.conversationCount(ctx.packageName)
                )
            } finally { store2.close() }
        } finally {
            poster.cancelAll()
        }
    }

    // ------------------------------------------------------------- harness

    private fun enableSelfTest() {
        val sources = listOf(
            SourceRegistry.selfTestPackage(ctx.packageName),
            SourceRegistry.selfTestInstrumentationPackage(ctx.packageName),
            CapturePolicy.SELF_TEST,
            SourceRegistry.SELF_TEST_INSTRUMENTATION_PACKAGE
        ).joinToString(",")
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
            Thread.sleep(800)
        }
    }

    private fun grantListener() {
        runCatching {
            val ui = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().uiAutomation
            val fd = ui.executeShellCommand(
                "cmd notification allow_listener " +
                    VariantNames.listenerComponent(ctx.packageName)
            )
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        // Wait for the in-process connected flag via the debug provider.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val connected = runCatching {
                ctx.contentResolver.call(
                    DebugSettingsProvider.contentUriFor(ctx), "ensureListener", null, null
                )?.getBoolean("connected") == true
            }.getOrDefault(false)
            if (connected) return
            Thread.sleep(500)
        }
    }

    private data class Row(val id: Long, val conversationId: String?)

    private fun rows(): List<Row> {
        val db = InboxDatabase(ctx)
        return try {
            val out = ArrayList<Row>()
            db.readableDatabase.rawQuery(
                "SELECT m.id, l.conversation_id FROM messages m " +
                    "LEFT JOIN message_conversation l ON l.message_id = m.id ORDER BY m.id",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(Row(c.getLong(0), if (c.isNull(1)) null else c.getString(1)))
                }
            }
            out
        } finally { db.close() }
    }

    /**
     * Wait until exactly one row carries the marker body AND is linked.
     *
     * Two stages with independent deadlines: the COMMIT (the message appears)
     * and the LINK (the canonical id appears). They are separate steps in the
     * listener — the link runs after the commit — and under load (a full suite
     * just finished a benchmark) the second can lag the first by seconds. A
     * single combined deadline made the test report "no link" while the link was
     * still in flight; two stages remove that false negative.
     */
    private fun awaitMarker(marker: String, timeoutMs: Long): List<Row> {
        // Stage 1: the committed row exists.
        val commitDeadline = System.currentTimeMillis() + timeoutMs
        var committed: List<Row>
        while (true) {
            committed = rows().filter { bodyOf(it.id) == marker }
            if (committed.isNotEmpty() ||
                System.currentTimeMillis() >= commitDeadline) break
            Thread.sleep(250)
        }
        // Stage 2: its canonical link appears (the listener links after commit).
        val linkDeadline = System.currentTimeMillis() + timeoutMs
        var r = committed
        while (System.currentTimeMillis() < linkDeadline) {
            r = rows().filter { bodyOf(it.id) == marker }
            if (r.all { it.conversationId != null }) break
            Thread.sleep(250)
        }
        if (r.any { it.conversationId == null }) {
            // H02/R01 diagnostics: dump the listener's identity diagnostics so a
            // failed link shows WHY (LINK_FAILED / no diagnostics at all).
            val diag = CaptureDiagnostics.recent(ctx, 100)
                .filter { it.stage == "IDENTITY" }
                .joinToString(";") { "${it.code}:${it.errorType ?: "-"}" }
            println("LINKDIAG stage2-unlinked ids=${r.filter { it.conversationId == null }.map { it.id }} identityDiag=[$diag]")
        }
        return r
    }

    /** The decrypted body of one row, through the production read path. */
    private fun bodyOf(id: Long): String {
        val db = InboxDatabase(ctx)
        return try {
            db.readableDatabase.rawQuery(
                "SELECT package_name, sealed_content FROM messages WHERE id = ?",
                arrayOf(id.toString())
            ).use { c ->
                if (!c.moveToFirst()) return ""
                // Rows are sealed with the key of the PACKAGE that posted the
                // notification, which is stored on the row — never assume it.
                val res = CryptoStore.openWithState(c.getString(0), c.getBlob(1))
                if (res.plaintext == null) return ""
                org.json.JSONObject(String(res.plaintext, Charsets.UTF_8))
                    .optString("body")
            }
        } finally { db.close() }
    }

    private fun awaitRows(minRows: Int, timeoutMs: Long): List<Row> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var r = rows()
        while (System.currentTimeMillis() < deadline) {
            r = rows()
            if (r.size >= minRows && r.all { it.conversationId != null }) break
            Thread.sleep(250)
        }
        return r
    }

    private fun onLoaded(
        scenario: ActivityScenario<MainActivity>,
        assertion: (MainActivity) -> Unit
    ) {
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("unlocked")
            field.isAccessible = true
            field.setBoolean(activity, true)
            activity.loadEntries()
        }
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            scenario.onActivity { done = it.entries.isNotEmpty() }
            if (done) break
            Thread.sleep(100)
        }
        scenario.onActivity { assertion(it) }
    }
}
