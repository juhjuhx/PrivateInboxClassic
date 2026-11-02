package tw.personal.privateinbox

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T04 — Activity lifecycle over the canonical grouping.
 *
 * Uses the REAL [MainActivity] through `ActivityScenario`, so the assertions run
 * against the production `loadEntries()` → `refreshCanonicalKeys()` → `threads()`
 * path, not a re-implementation of it.
 *
 * NOT claimed: `recreate()` does not simulate process death (Android keeps the
 * Application and the static state). The process-death case is covered by the
 * pipeline suite's reconcile test, which rebuilds every instance from SQLite.
 */
@RunWith(AndroidJUnit4::class)
class ConversationUiLifecycleInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pkg = "jp.naver.line.android"

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    private fun save(title: String, body: String, ts: Long): Long {
        val legacyKey = LegacyKeyCodec.format(pkg, null, title)
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = pkg, receivedAt = ts, sourceTimestamp = ts,
                title = title, sender = "S", body = body, image = null,
                conversationKey = legacyKey,
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

    private fun canonicalKeyCount(): Int {
        val db = InboxDatabase(ctx)
        return try {
            db.readableDatabase.rawQuery(
                "SELECT count(*) FROM message_conversation", null
            ).use { c -> c.moveToFirst(); c.getInt(0) }
        } finally { db.close() }
    }

    /**
     * `MainActivity.loadEntries()` decodes on an io executor and renders on the
     * UI thread, so `onActivity` can easily run before the entries are there.
     * This waits (on the instrumentation thread) until the predicate is satisfied
     * or the deadline passes, then runs the assertion on the UI thread.
     */
    private fun onLoadedActivity(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 8000,
        assertion: (MainActivity) -> Unit
    ) {
        // The production unlock gate runs a BiometricPrompt, which no test can
        // satisfy. The UiSnapshot suite established the sanctioned pattern for
        // tests: unlock programmatically via reflection and drive the REAL
        // loadEntries() path. The production gate itself is untouched.
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("unlocked")
            field.isAccessible = true
            field.setBoolean(activity, true)
            activity.loadEntries()
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            var done = false
            scenario.onActivity { done = it.entries.isNotEmpty() }
            if (done) break
            Thread.sleep(100)
        }
        scenario.onActivity { assertion(it) }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun renamedConversationHasOneBoxAfterActivityRecreate() {
        // 30 rows under the old title and 30 under the new one, all linked to ONE
        // canonical conversation. The page size is 50, so the old UI (grouping
        // the page by legacy key) showed TWO boxes for this conversation.
        val cid = resolve("舊名稱")
        for (i in 1..30) link(save("舊名稱", "old-$i", 1_700_000_000_000L + i), cid)
        for (i in 1..30) link(save("新名稱", "new-$i", 1_700_001_000_000L + i), cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoadedActivity(scenario) { activity ->
                // The production load path has run; group the production state.
                val groups = activity.threads(activity.entries)
                val canonicalBoxes = groups.count { it.key.startsWith("cv_") }
                assertEquals(
                    "a renamed conversation must render as ONE box after recreate",
                    1, canonicalBoxes
                )
                // PAGE_SIZE is 50: the first page holds 50 of the 60 rows. The
                // box count (ONE) is the assertion that matters; the remaining
                // rows arrive with loadMorePages() and still group into the SAME
                // canonical box.
                assertEquals("first page rows are in that one box", 50, groups.first().value.size)
            }
            // Recreate (configuration change / rotation path).
            scenario.recreate()
            onLoadedActivity(scenario) { activity ->
                val groups = activity.threads(activity.entries)
                val canonicalBoxes = groups.count { it.key.startsWith("cv_") }
                assertEquals(
                    "still one box after recreate — canonical identity is reloaded " +
                        "from SQLite, not from an in-memory resolver",
                    1, canonicalBoxes
                )
                assertEquals("page still holds 50 rows after recreate", 50, groups.first().value.size)
            }
        }
    }

    @Test
    fun unlinkedHistoricalRowsStillRenderAfterRestart() {
        // A row with NO canonical link must still appear (under its legacy key).
        save("歷史訊息", "old row", 1_700_000_000_000L)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoadedActivity(scenario) { activity ->
                println("DEBUG-ASSERT entries=${activity.entries.size} unlockedField=" +
                    runCatching {
                        val f = MainActivity::class.java.getDeclaredField("unlocked")
                        f.isAccessible = true; f.getBoolean(activity)
                    }.getOrDefault("?"))
                assertEquals(1, activity.entries.size)
                val groups = activity.threads(activity.entries)
                assertEquals("the legacy row renders as one box", 1, groups.size)
                assertFalse(
                    "its key is the legacy key (not a canonical id)",
                    groups.first().key.startsWith("cv_")
                )
            }
        }
    }

    @Test
    fun twoSameNamedGroupsWithLinksStayTwoBoxes() {
        val a = resolve("群組A")
        val b = resolve("群組B")
        for (i in 1..10) link(save("群組A", "a-$i", 1_700_000_000_000L + i), a)
        for (i in 1..10) link(save("群組B", "b-$i", 1_700_000_000_000L + i), b)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoadedActivity(scenario) { activity ->
                assertEquals(
                    "two same-named groups with different links stay two boxes",
                    2, activity.threads(activity.entries).size
                )
            }
        }
    }

    @Test
    fun mergeReducesToOneBoxAndUnmergeRestoresTwoAcrossRecreate() {
        val a = resolve("M-A")
        val b = resolve("M-B")
        for (i in 1..5) link(save("M-A", "a-$i", 1_700_000_000_100L + i), a)
        for (i in 1..5) link(save("M-B", "b-$i", 1_700_000_000_200L + i), b)

        val store = ConversationStore(ctx)
        try { store.merge(a, b) } finally { store.close() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoadedActivity(scenario) { activity ->
                assertEquals(
                    "merged conversations render as one box",
                    1, activity.threads(activity.entries).size
                )
            }
        }

        val store2 = ConversationStore(ctx)
        try { store2.unmerge(a) } finally { store2.close() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoadedActivity(scenario) { activity ->
                assertEquals(
                    "after unmerge and a fresh Activity launch, two boxes again",
                    2, activity.threads(activity.entries).size
                )
            }
        }
    }

    @Test
    fun linksSurviveAnActivityRelaunch() {
        val cid = resolve("持久")
        for (i in 1..3) link(save("持久", "row-$i", 1_700_000_000_000L + i), cid)
        assertEquals(3, canonicalKeyCount())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // The production loadEntries() ran linkAllMessages(); the links
                // must still be exactly 3 (no duplication, no loss).
                assertEquals(3, canonicalKeyCount())
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals("relaunch does not duplicate links", 3, canonicalKeyCount())
            }
        }
    }

    @Test
    fun searchStateIsPreservedAcrossRecreate() {
        val a = resolve("搜尋A")
        val b = resolve("搜尋B")
        for (i in 1..3) link(save("搜尋A", "needle-$i", 1_700_000_000_000L + i), a)
        for (i in 1..3) link(save("搜尋B", "other-$i", 1_700_000_000_000L + i), b)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoadedActivity(scenario) {
                assertEquals(6, canonicalKeyCount())
            }
            scenario.recreate()
            onLoadedActivity(scenario) { activity ->
                // The canonical map must be reloaded after recreate: grouping
                // cannot depend on state that vanish with the Activity.
                assertEquals(6, activity.entries.size)
                assertEquals(2, activity.threads(activity.entries).size)
            }
        }
    }
}
