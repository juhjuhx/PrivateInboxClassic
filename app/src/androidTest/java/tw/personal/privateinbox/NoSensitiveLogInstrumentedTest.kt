package tw.personal.privateinbox

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * F-05 regression: opening a conversation, searching, and paging older history
 * must not write private identifiers to Logcat.
 *
 * Why this is a real test and not a code read: the shipped v0.4.6 artifact is
 * `prodDebug` with `android:debuggable=true`, so anything reachable with
 * `adb logcat` is reachable by anyone holding the device or a USB cable. A
 * comment in the source is not evidence; this reads the device's own log buffer
 * while the production read paths actually run.
 *
 * What is asserted absent (AGENTS.md hard rule 4 forbids all of it):
 *   - conversation titles / contact names
 *   - legacy conversation keys (plain or hex-encoded)
 *   - canonical conversation ids
 *   - SQL text and query arguments
 *
 * What is allowed and therefore not asserted against: package names, stage
 * codes, timestamps, counts, exception type names.
 */
@RunWith(AndroidJUnit4::class)
class NoSensitiveLogInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pkg = "jp.naver.line.android"

    /** A title and body distinctive enough that any leak is unambiguous. */
    private val secretTitle = "ZZPRIVACYPROBETITLEZZ"
    private val secretBody = "ZZPRIVACYPROBEBODYZZ"

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
        clearLogcat()
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    private fun save(title: String, body: String, ts: Long): Long {
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = pkg, receivedAt = ts, sourceTimestamp = ts,
                title = title, sender = "S", body = body, image = null,
                conversationKey = LegacyKeyCodec.format(pkg, null, title),
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:S:$body:i$ts"
            )
            return db.readableDatabase.rawQuery(
                "SELECT id FROM messages ORDER BY id DESC LIMIT 1", null
            ).use { c -> c.moveToFirst(); c.getLong(0) }
        } finally { db.close() }
    }

    private fun resolve(title: String): String {
        val store = ConversationStore(ctx)
        return try {
            ConversationIdentityResolver(store).resolve(pkg, null, title).conversationId
        } finally { store.close() }
    }

    private fun link(id: Long, cid: String) {
        val store = ConversationStore(ctx)
        try { store.linkMessage(id, cid) } finally { store.close() }
    }

    private fun clearLogcat() {
        try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        } catch (_: Exception) {
            // If logcat cannot be cleared the test still runs; the assertions
            // below look for values that must never appear anywhere.
        }
    }

    /** Everything currently in the device log buffer. */
    private fun readLogcat(): String {
        val sb = StringBuilder()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
            }
            p.waitFor()
        } catch (_: Exception) {
            // Fall through with whatever was collected.
        }
        return sb.toString()
    }

    /**
     * Exercise every production read path that used to log identifiers:
     * first page load, older-page load, and the inbox read model.
     */
    @Test
    fun chatSearchAndPagingDoNotLogPrivateIdentifiers() {
        val cid = resolve(secretTitle)
        val ids = ArrayList<Long>()
        // Enough rows to guarantee a second page exists at CHAT_PAGE_SIZE = 100.
        for (i in 1..150) {
            ids.add(save(secretTitle, "$secretBody-$i", 1_700_000_000_000L + i * 1000L))
        }
        for (id in ids) link(id, cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Unlock and load the inbox (LOADENTRIES path).
            scenario.onActivity { activity ->
                val f = MainActivity::class.java.getDeclaredField("unlocked")
                f.isAccessible = true
                f.setBoolean(activity, true)
                activity.loadEntries()
            }
            waitFor(scenario) { it.entries.isNotEmpty() }

            // Open the conversation: first page (READMODEL query path).
            scenario.onActivity { activity ->
                activity.openThreadForTest(threadKeyOf(activity), secretTitle)
            }
            waitFor(scenario) { activity ->
                activity.chatStateForTest()["loading"] == false &&
                    (activity.chatStateForTest()["entries"] as Int) > 0
            }

            // Load an older page (READMODEL with cursor predicates).
            scenario.onActivity { activity -> activity.loadOlderForTest() }
            waitFor(scenario) { activity ->
                activity.chatStateForTest()["loading"] == false &&
                    (activity.chatStateForTest()["entries"] as Int) > 100
            }

            // Search screen also builds read-model queries.
            scenario.onActivity { activity ->
                activity.setSearchInputsForTest(secretTitle, null)
                activity.filteredEntries()
            }
        }

        val log = readLogcat()

        // The buffer must not contain the private identifiers.
        assertFalse(
            "conversation title leaked to Logcat:\n" + log.lines()
                .filter { it.contains(secretTitle) }.take(5).joinToString("\n"),
            log.contains(secretTitle)
        )
        assertFalse(
            "canonical conversation id leaked to Logcat",
            log.contains(cid)
        )
        val legacyKey = LegacyKeyCodec.format(pkg, null, secretTitle)
        assertFalse(
            "legacy conversation key leaked to Logcat",
            log.contains(legacyKey)
        )
        // Hex-encoded form of the same key.
        val hex = legacyKey.toByteArray(Charsets.UTF_8)
            .joinToString("") { "%02X".format(it) }
        assertFalse(
            "hex-encoded legacy conversation key leaked to Logcat",
            log.contains(hex, ignoreCase = true)
        )
        // SQL text must not be echoed.
        assertFalse(
            "SQL text leaked to Logcat",
            log.contains("SELECT id FROM messages WHERE", ignoreCase = true)
        )
        assertFalse(
            "query arguments leaked to Logcat",
            log.contains("args=[", ignoreCase = true)
        )

        // Sanity: the read paths really did run, so this is not a vacuous pass.
        assertTrue(
            "expected the read model to have run during this test",
            log.contains("READMODEL")
        )
    }

    private fun threadKeyOf(activity: MainActivity): String =
        activity.threads(activity.entries).first { e ->
            e.value.any { it.title == secretTitle }
        }.key

    private fun waitFor(
        scenario: ActivityScenario<MainActivity>,
        predicate: (MainActivity) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + 20_000
        var ok = false
        while (System.currentTimeMillis() < deadline && !ok) {
            scenario.onActivity { activity -> ok = predicate(activity) }
            if (!ok) Thread.sleep(150)
        }
        assertTrue("timed out waiting for the UI to settle", ok)
    }
}
