package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-REFRESH regression: the chat/inbox list is rebuilt from `InboxDatabase.recent()`
 * each time the app resumes. This test verifies that a capture made through one
 * database handle is immediately visible through a fresh handle — the same read the
 * UI performs — so "DB wrote but the list didn't refresh" is caught here.
 *
 * Visual refresh itself (scrolled layout, thumbnails) is covered by the manual
 * real-device test plan; this asserts the data the UI renders.
 */
@RunWith(AndroidJUnit4::class)
class UiRefreshInstrumentedTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun captureViaOneHandleVisibleToFreshUiRead() {
        val writer = InboxDatabase(ctx)
        val row = writer.save("jp.naver.line.android",
            System.currentTimeMillis(), 0L, "UI-Refresh Line", "Alice", "ui refresh probe", null)
        writer.close()
        // The writer's insert must be a real NEW row, not a swallowed failure.
        assertNotNull(row)
        assertTrue(row is InboxDatabase.SaveResult.NEW)
        // A brand-new handle (what the UI opens on resume) must see the row.
        val reader = InboxDatabase(ctx)
        try {
            val found = reader.recent(500).any { it.body == "ui refresh probe" }
            assertEquals("UI re-read must surface the new capture (UI_REFRESH_SUCCESS)", true, found)
        } finally { reader.close() }
    }

    @Test
    fun databaseSurvivesReopenAfterWrites() {
        val db1 = InboxDatabase(ctx)
        db1.save("jp.naver.line.android", System.currentTimeMillis(), 1L, "Reopen Line", "Bob", "reopen probe", null)
        db1.close()
        val db2 = InboxDatabase(ctx)
        try {
            // Reopen recovers WAL automatically; assert the row survived.
            val found = db2.recent(500).any { it.body == "reopen probe" }
            assertEquals("row must persist across reopen", true, found)
        } finally { db2.close() }
    }
}
