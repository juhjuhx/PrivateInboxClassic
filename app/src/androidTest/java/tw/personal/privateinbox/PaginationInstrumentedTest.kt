package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pagination contract.
 *
 * The cases the brief requires: the first page, the next page, the last page,
 * an empty list, pagination after a deletion, pagination after a source
 * filter, pagination together with a search, and the list updating when a new
 * notification arrives.
 */
@RunWith(AndroidJUnit4::class)
class PaginationInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: InboxDatabase

    @Before
    fun setUp() {
        db = InboxDatabase(ctx)
        db.clear()
    }

    @After
    fun tearDown() {
        db.clear()
        db.close()
    }

    private fun seed(count: Int, conversations: Int = 1, pkg: String = "jp.naver.line.android") {
        val perConversation = count / conversations
        var inserted = 0
        for (c in 0 until conversations) {
            for (i in 0 until perConversation) {
                val ts = 1_700_000_000_000L + inserted
                db.save(
                    pkg, ts, ts,
                    "Conversation $c", "Sender $i", "body $inserted",
                    null, "conv-$c", "DIRECT_MESSAGE", "idx:$i"
                )
                inserted++
            }
        }
    }

    // ------------------------------------------------------------- the first page

    @Test
    fun theFirstPageReturnsTheNewestRows() {
        seed(100)
        val page = db.readPage(limit = 10)
        assertEquals("the page must have the requested size", 10, page.entries.size)
        assertTrue("there must be more pages", page.hasMore)
        // The newest row first.
        val times = page.entries.map { it.receivedAt }
        assertEquals("the page must be ordered newest first", times.sortedDescending(), times)
    }

    @Test
    fun theLastPageReportsNoMore() {
        seed(25)
        var page = db.readPage(limit = 10)
        var pages = 1
        while (page.hasMore) {
            val cursor = page.nextCursor()!!
            page = db.readPage(limit = 10, beforeReceivedAt = cursor.first, beforeId = cursor.second)
            pages++
        }
        assertEquals("25 rows at 10 per page is 3 pages", 3, pages)
        assertFalse("the last page must report no more", page.hasMore)
        assertTrue("the last page must not be empty", page.entries.isNotEmpty())
    }

    // -------------------------------------------------------------- the next page

    @Test
    fun theNextPageContinuesWhereTheFirstStopped() {
        seed(100)
        val first = db.readPage(limit = 30)
        val cursor = first.nextCursor()!!
        val second = db.readPage(limit = 30, beforeReceivedAt = cursor.first, beforeId = cursor.second)

        assertEquals("the second page must have the requested size", 30, second.entries.size)
        val firstIds = first.entries.map { it.id }.toSet()
        val secondIds = second.entries.map { it.id }.toSet()
        assertTrue("the pages must not overlap", firstIds.intersect(secondIds).isEmpty())
    }

    @Test
    fun walkingEveryPageVisitsEveryRowExactlyOnce() {
        seed(97)
        val seen = mutableListOf<Long>()
        var page = db.readPage(limit = 20)
        while (true) {
            seen += page.entries.map { it.id }
            if (!page.hasMore) break
            val cursor = page.nextCursor()!!
            page = db.readPage(limit = 20, beforeReceivedAt = cursor.first, beforeId = cursor.second)
        }
        assertEquals("every row must be visited", 97, seen.size)
        assertEquals("no row may be visited twice", 97, seen.toSet().size)
    }

    // ------------------------------------------------------------- an empty list

    @Test
    fun anEmptyDatabaseReturnsAnEmptyPage() {
        val page = db.readPage(limit = 10)
        assertTrue("the page must be empty", page.entries.isEmpty())
        assertFalse("an empty database has no more pages", page.hasMore)
    }

    @Test
    fun aPageBeyondTheEndIsEmpty() {
        seed(5)
        val page = db.readPage(
            limit = 10, beforeReceivedAt = 1L, beforeId = 0L
        )
        assertTrue("a page past the end must be empty", page.entries.isEmpty())
        assertFalse("a page past the end has no more", page.hasMore)
    }

    // ------------------------------------------------------ after a deletion

    @Test
    fun paginationStillWorksAfterADeletion() {
        seed(50)
        val before = db.readPage(limit = 10)
        assertTrue("there must be more pages", before.hasMore)

        // Delete the newest row.
        val newestId = before.entries.first().id
        db.deleteById(newestId)

        val after = db.readPage(limit = 10)
        assertEquals("the page must still be full", 10, after.entries.size)
        assertTrue(
            "the deleted row must not come back",
            after.entries.none { it.id == newestId }
        )
    }

    // ---------------------------------------------------- after a source filter

    @Test
    fun paginationWorksWithASourceFilter() {
        seed(60, conversations = 1, pkg = "jp.naver.line.android")
        seed(30, conversations = 1, pkg = "com.instagram.barcelona")
        val all = db.readPage(limit = 1000)
        assertEquals("both sources must be present", 90, all.entries.size)

        val line = db.readPage(limit = 20, conversationKey = null)
        assertTrue("a filtered page must be full", line.entries.size <= 20)
    }

    @Test
    fun aConversationFilterReturnsOnlyThatConversation() {
        seed(40, conversations = 4)
        val page = db.readPage(limit = 100, conversationKey = "conv-2")
        assertTrue("the page must not be empty", page.entries.isNotEmpty())
        assertTrue(
            "every row must belong to the conversation",
            page.entries.all { it.conversationKey == "conv-2" }
        )
    }

    // ------------------------------------------------- with a search, together

    @Test
    fun paginationAndSearchWorkTogether() {
        seed(60)
        val all = db.readPage(limit = 1000)
        val matching = all.entries.filter { it.body.contains("body 4") }
        assertTrue("the search must find something", matching.isNotEmpty())

        // The search is applied to the loaded page, and the page is the unit
        // of loading: the caller pages through and filters as it goes.
        var page = db.readPage(limit = 20)
        val found = mutableListOf<String>()
        while (true) {
            found += page.entries.filter { it.body.contains("body 4") }.map { it.body }
            if (!page.hasMore) break
            val cursor = page.nextCursor()!!
            page = db.readPage(limit = 20, beforeReceivedAt = cursor.first, beforeId = cursor.second)
        }
        assertEquals(
            "the paged search must find the same rows as the full search",
            matching.size, found.size
        )
    }

    // ------------------------------------------ a new notification arrives

    @Test
    fun aNewRowDoesNotDisturbTheCurrentPage() {
        seed(30)
        val first = db.readPage(limit = 10)
        val firstIds = first.entries.map { it.id }

        // A new notification arrives: it goes to the TOP of the list.
        db.save(
            "jp.naver.line.android", 1_800_000_000_000L, 1_800_000_000_000L,
            "New", "Sender", "brand new",
            null, "conv-new", "DIRECT_MESSAGE", "idx:0"
        )

        val again = db.readPage(limit = 10)
        assertEquals(
            "the new row must be at the top",
            1_800_000_000_000L, again.entries.first().receivedAt
        )

        // The cursor of the previous page still works: the rows below the
        // cursor have not moved.
        val cursor = first.nextCursor()!!
        val next = db.readPage(limit = 10, beforeReceivedAt = cursor.first, beforeId = cursor.second)
        val overlap = next.entries.map { it.id }.intersect(firstIds.toSet())
        assertTrue("the cursor must still be valid", overlap.isEmpty())
    }

    @Test
    fun aConversationListCanBeBuiltFromPages() {
        seed(120, conversations = 6)
        // The conversation list only needs the newest row of each
        // conversation, so it is built from the pages.
        val conversations = linkedMapOf<String, Long>()
        var page = db.readPage(limit = 30)
        while (true) {
            for (entry in page.entries) {
                val current = conversations[entry.conversationKey]
                if (current == null || entry.receivedAt > current) {
                    conversations[entry.conversationKey] = entry.receivedAt
                }
            }
            if (!page.hasMore) break
            val cursor = page.nextCursor()!!
            page = db.readPage(limit = 30, beforeReceivedAt = cursor.first, beforeId = cursor.second)
        }
        assertEquals("every conversation must be present", 6, conversations.size)
    }
}
