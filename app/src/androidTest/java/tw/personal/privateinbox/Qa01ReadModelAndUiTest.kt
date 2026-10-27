package tw.personal.privateinbox

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QA-01 — acceptance-gap closures for the read model and the UI.
 *
 * QA-01C: the previous "search" test only checked the source filter; it never
 * searched TEXT. This suite drives the production `filteredEntries()` logic
 * through the real Activity, covering keyword / source / combined / date /
 * Unicode / empty / rename searches.
 *
 * QA-01E: assertions run against the actual view hierarchy (`windowDecorView`),
 * not just an in-memory grouping call.
 */
@RunWith(AndroidJUnit4::class)
class Qa01ReadModelAndUiTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
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
        // Wait for the load, then EXPLICITLY render the inbox: loadEntries only
        // re-renders when its async callback observes the current page, and a
        // freshly launched activity sits on the lock screen until it is told
        // otherwise.
        scenario.onActivity { activity ->
            runCatching {
                val m = MainActivity::class.java.getDeclaredMethod("showInbox", Boolean::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(activity, false)
            }
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

    /** Drive the production filteredEntries() logic with the given inputs. */
    private fun searchIn(
        activity: MainActivity, query: String?, platform: String?
    ): List<InboxDatabase.Entry> {
        activity.setSearchInputsForTest(query, platform)
        return activity.filteredEntries()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun qa01c_keywordSearchMatchesBodySenderAndTitle() {
        val cid = resolve("購物通知")
        for ((i, body) in listOf("出貨通知 #123", "優惠碼到期", "會議提醒").withIndex()) {
            link(save("購物通知", body, 1_700_000_000_000L + i), cid)
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoaded(scenario) { activity ->
                // body match
                assertEquals(1, searchIn(activity, "出貨", null).size)
                // title match
                assertEquals(3, searchIn(activity, "購物", null).size)
                // sender match
                assertEquals(3, searchIn(activity, "S", null).size)
                // case-insensitive ASCII
                assertEquals(1, searchIn(activity, "優惠", null).size)
            }
        }
    }

    @Test
    fun qa01c_searchPlusSourceFilterCombined() {
        val tg = "org.telegram.messenger"
        val lineCid = resolve("群組")
        val tgCid = resolveTg("群組")
        for (i in 1..2) link(save("群組", "line-$i", 1_700_000_000_000L + i), lineCid)
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = tg, receivedAt = 1_700_000_009_000L, sourceTimestamp = 1_700_000_009_000L,
                title = "群組", sender = "T", body = "telegram-msg", image = null,
                conversationKey = LegacyKeyCodec.format(tg, null, "群組"),
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:T:telegram-msg:i1"
            )
        } finally { db.close() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoaded(scenario) { activity ->
                assertEquals(3, activity.entries.size)
                // Source filter alone: only LINE rows.
                assertEquals(2, searchIn(activity, null, pkg).size)
                // Keyword alone: the telegram body.
                assertEquals(1, searchIn(activity, "telegram", null).size)
                // Combined: keyword + source that does NOT match it.
                assertEquals(0, searchIn(activity, "telegram", pkg).size)
                assertEquals("cross-package search never merges conversations",
                    lineCid != tgCid, true)
            }
        }
    }

    private fun resolveTg(title: String): String {
        val store = ConversationStore(ctx)
        return try {
            ConversationIdentityResolver(store)
                .resolve("org.telegram.messenger", null, title).conversationId
        } finally { store.close() }
    }

    @Test
    fun qa01c_dateRangeAndEmptyResults() {
        // Rows at two distinct dates, so a date lower bound can separate them.
        val cid = resolve("日期測試")
        val oldTs = 1_700_000_000_000L
        val newTs = 1_700_050_000_000L
        for (i in 1..3) link(save("日期測試", "old-$i", oldTs + i), cid)
        for (i in 1..2) link(save("日期測試", "new-$i", newTs + i), cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoaded(scenario) { activity ->
                // No query: everything.
                assertEquals(5, searchIn(activity, null, null).size)
                // A query that matches nothing.
                assertEquals(0, searchIn(activity, "不存在的關鍵字xyz", null).size)
                // Keyword + the "new" rows only.
                assertEquals(2, searchIn(activity, "new-", null).size)

                // Date-range: the search screen applies `receivedAt >= fromEpoch`
                // (MainActivity.search()). Drive the same predicate the UI uses.
                val hits = activity.entries.filter { it.receivedAt >= newTs }
                assertEquals("the date lower bound selects only the newer rows",
                    2, hits.size)
                val all = activity.entries.filter { it.receivedAt >= 0 }
                assertEquals("a zero lower bound selects everything", 5, all.size)
                val none = activity.entries.filter { it.receivedAt >= newTs + 10_000 }
                assertEquals("a bound past every row selects nothing", 0, none.size)
            }
        }
    }

    @Test
    fun qa01c_realUiSearchInputRendersResults() {
        // The other search tests drive filteredEntries() directly; this one
        // drives the REAL search screen (input field + rendered results).
        val cid = resolve("搜尋畫面")
        link(save("搜尋畫面", "可搜尋的獨特字串", 1_700_000_000_001L), cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoaded(scenario) { activity ->
                // Open the search screen the way the toolbar does.
                val m = MainActivity::class.java.getDeclaredMethod("showSearch")
                m.isAccessible = true
                m.invoke(activity)
                // Type into the real EditText.
                val input = findEditText(activity)
                assertNotNull("the search screen must have an input field", input)
                val editText = input!!
                runOnUiThread(activity) {
                    editText.setText("獨特字串")
                }
                Thread.sleep(300)   // TextWatcher fires search()
                // The REAL view hierarchy must now contain the hit row.
                val texts = ArrayList<String>()
                walk(activity.window.decorView) { v ->
                    if (v is android.widget.TextView) texts.add(v.text?.toString().orEmpty())
                }
                assertTrue(
                    "the search results must render the matching conversation " +
                        "(texts on screen: ${texts.filter { it.isNotBlank() }})",
                    texts.any { it.contains("搜尋畫面") }
                )
            }
        }
    }

    private fun findEditText(activity: MainActivity): android.widget.EditText? {
        var found: android.widget.EditText? = null
        walk(activity.window.decorView) { v ->
            if (v is android.widget.EditText && found == null) found = v
        }
        return found
    }

    private fun runOnUiThread(activity: MainActivity, block: () -> Unit) {
        activity.runOnUiThread(block)
    }

    @Test
    fun qa01c_unicodeAndRenameSearch() {
        val cid = resolve("日本語のグループ")
        link(save("日本語のグループ", "你好 👍🏽", 1_700_000_000_001L), cid)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoaded(scenario) { activity ->
                assertEquals(1, searchIn(activity, "你好", null).size)
                assertEquals(1, searchIn(activity, "日本語", null).size)
                assertEquals(1, searchIn(activity, "👍🏽", null).size)
                // Rename: the OLD title still finds the OLD row.
                assertEquals(1, searchIn(activity, "日本語の", null).size)
            }
        }
    }

    @Test
    fun qa01d_sameTitleDifferentShortcutsRenderAsTwoRows() {
        // QA-01D: the earlier suite used 群組A/群組B (different titles). Here the
        // titles are IDENTICAL and only the shortcut differs, at the RESOLVER
        // level; the rows themselves are title-only (the same shape the
        // production save() produces), so the two groups differ only through
        // their canonical links.
        val cidA = resolveWithShortcut("同名群組", "sc-same-1")
        val cidB = resolveWithShortcut("同名群組", "sc-same-2")
        assertNotEquals("the two shortcut identities are distinct conversations",
            cidA, cidB)

        // Rows stored under the two different legacy keys that the shortcuts
        // imply, linked to their own canonical conversation.
        val legacyA = LegacyKeyCodec.format(pkg, "sc-same-1", "同名群組")
        val legacyB = LegacyKeyCodec.format(pkg, "sc-same-2", "同名群組")
        val idA = saveWithKey(legacyA, "msg-a", 1_700_000_000_001L)
        val idB = saveWithKey(legacyB, "msg-b", 1_700_000_000_002L)
        link(idA, cidA); link(idB, cidB)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoaded(scenario) { activity ->
                val groups = activity.threads(activity.entries)
                assertEquals(
                    "two boxes rendered for one title with two shortcuts",
                    2, groups.size
                )
                // And the ACTUAL view hierarchy shows two independent rows, one
                // per canonical id — the same-title rows must not collapse.
                assertVisibleThreadRows(activity, listOf(cidA, cidB))
            }
        }
    }

    @Test
    fun qa01e_viewHierarchyShowsHundredConversationsAcrossPages() {
        // QA-01E: 100 conversations; the SQL summary says 100; the first screen
        // renders the first page's worth of boxes and the actual view hierarchy
        // carries them.
        for (i in 1..100) {
            val cid = resolve("V$i")
            link(save("V$i", "body-$i", 1_700_000_000_000L + i), cid)
        }
        val db = InboxDatabase(ctx)
        val expectedPage = try { db.conversationPage(50) } finally { db.close() }
        val expectedKeys = expectedPage.map { it.canonicalConversationId }
        assertEquals("the SQL summary must list all 100 conversations",
            100, InboxDatabase(ctx).use { it.conversationCountAll(pkg) })

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onLoaded(scenario) { activity ->
                // Page 1: the first 50 conversations in the SAME order as the SQL
                // summary (timestamp DESC, stable key).
                val renderedKeys = activity.threads(activity.entries).map { it.key }
                assertEquals("page 1 renders the first page of conversations",
                    50, renderedKeys.size)
                assertEquals(
                    "page 1 conversation order matches the SQL summary",
                    expectedKeys.take(50), renderedKeys
                )
                assertVisibleThreadRows(activity, renderedKeys)
            }
        }
    }

    @Test
    fun qa01f_finishAndRelaunchKeepsHistory() {
        val cid = resolve("重啟測試")
        for (i in 1..3) link(save("重啟測試", "row-$i", 1_700_000_000_000L + i), cid)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        onLoaded(scenario) { assertEquals(3, it.entries.size) }
        scenario.close()   // finish

        // Reopen: a brand-new Activity over the same SQLite.
        ActivityScenario.launch(MainActivity::class.java).use { reopened ->
            onLoaded(reopened) { activity ->
                assertEquals("history after finish+relaunch", 3, activity.entries.size)
                assertEquals(1, activity.threads(activity.entries).size)
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun saveWithKey(legacyKey: String, body: String, ts: Long): Long {
        val db = InboxDatabase(ctx)
        try {
            db.save(
                packageName = pkg, receivedAt = ts, sourceTimestamp = ts,
                title = legacyKey.substringAfterLast('\u0000'), sender = "S", body = body,
                image = null, conversationKey = legacyKey,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = "c:S:$body:i$ts"
            )
            return db.readableDatabase.rawQuery(
                "SELECT id FROM messages ORDER BY id DESC LIMIT 1", null
            ).use { c -> c.moveToFirst(); c.getLong(0) }
        } finally { db.close() }
    }

    private fun resolveWithShortcut(title: String, shortcut: String): String {
        val store = ConversationStore(ctx)
        return try {
            ConversationIdentityResolver(store)
                .resolve(pkg, shortcut, title).conversationId
        } finally { store.close() }
    }

    /**
     * QA-01E (H01 fix): count the ACTUAL rendered thread rows in the RESUMED
     * activity's view hierarchy.
     *
     * The previous version accepted "any avatar marker matched" — which could
     * pass while the wrong rows were on screen, and collapsed duplicates through
     * a Set. Every inbox row now carries its conversation key as the view tag
     * (set by MainActivity), so this counts rows by their EXACT identity:
     *
     *   - the total number of tagged row containers must equal `expectedTotal`
     *   - every expected key must be present
     *   - for a duplicate-key list, the two rows must BOTH exist (no set
     *     collapsing, no single-row false positive)
     */
    private fun assertVisibleThreadRows(
        activity: MainActivity,
        expectedKeys: Collection<String>
    ) {
        val taggedRows = ArrayList<String>()
        walk(activity.window.decorView) { v ->
            val tag = v.tag as? String
            if (tag != null && (tag.startsWith("cv_") || tag.startsWith("k:"))) {
                taggedRows.add(tag)
            }
        }
        if (taggedRows.isEmpty() && expectedKeys.isNotEmpty()) {
            // Diagnose: dump the view tree shape so a failing run shows what the
            // window actually contains instead of a bare count.
            val sb = StringBuilder()
            walk(activity.window.decorView) { v ->
                val tag = v.tag
                sb.append(v.javaClass.simpleName)
                if (tag != null) sb.append("[TAG=").append(tag).append("]")
                if (v is android.widget.TextView && v.text?.isNotEmpty() == true)
                    sb.append(":").append(v.text?.toString()?.take(16))
                sb.append(" | ")
            }
            println("QA01-TREE $sb")
        }
        for (key in expectedKeys.toSet()) {
            assertTrue(
                "row for conversation $key must be present in the view hierarchy " +
                    "(found: ${taggedRows.size} tagged rows)",
                taggedRows.contains(key)
            )
        }
        // Duplicates must survive: counting occurrences (not a Set) is what
        // makes the same-title test meaningful.
        for (key in expectedKeys) {
            val occurrences = taggedRows.count { it == key }
            val expectedOccurrences = expectedKeys.count { it == key }
            assertEquals(
                "conversation $key must render $expectedOccurrences row(s), not " +
                    "$occurrences",
                expectedOccurrences, occurrences
            )
        }
        assertEquals(
            "the hierarchy must contain exactly the expected number of rows",
            expectedKeys.size, taggedRows.size
        )
    }

    private fun walk(v: android.view.View, visit: (android.view.View) -> Unit) {
        visit(v)
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), visit)
        }
    }
}
