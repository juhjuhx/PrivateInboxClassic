package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The storage benchmark.
 *
 * Two capacities matter and they are different:
 *
 *  THROUGHPUT CAPACITY -- how many messages per second can be stored
 *  CORRECTLY.
 *
 *  STORAGE CAPACITY -- how many rows and how much disk the database can
 *  manage over a long life.
 *
 * The numbers are reported with the device, the Android version and the
 * load, because a throughput number without them is not a number.
 */
@RunWith(AndroidJUnit4::class)
class StorageBenchmarkInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        // The database is cleared HERE, so each scale starts from zero. The
        // benchmark measures the storage of ONE scale, and a leftover row
        // from a previous scale would make the count wrong.
        TestDataIsolation.clearDatabase(ctx)
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    /** The result of one storage run. */
    data class StorageResult(
        val rows: Int,
        val messages: Int,
        val conversations: Int,
        val seconds: Double,
        val dbBytes: Long,
        val bytesPerMessage: Long
    ) {
        val perSecond: Double get() = if (seconds <= 0.0) 0.0 else messages / seconds
        fun line(): String = String.format(
            "rows=%d messages=%d convs=%d sec=%.2f msg/s=%.1f db=%d B per_msg=%d B",
            rows, messages, conversations, seconds, perSecond, dbBytes, bytesPerMessage
        )
    }

    /**
     * Store a set of messages and report the result.
     *
     * The messages are written through the SAME path the capture pipeline
     * uses, including the encryption, so the measured cost is the real one.
     */
    private fun store(
        messages: Int,
        conversations: Int,
        sameText: Boolean = false
    ): StorageResult {
        // The database is cleared HERE, not only in the setUp. A test method
        // measures several scales in a loop, and the setUp runs once, so the
        // second scale would otherwise count the first scale's rows.
        TestDataIsolation.clearDatabase(ctx)
        val db = InboxDatabase(ctx)
        val start = System.nanoTime()
        val pending = ArrayList<InboxDatabase.PendingMessage>(messages)
        for (i in 0 until messages) {
            val conv = i % conversations
            pending.add(
                InboxDatabase.PendingMessage(
                    packageName = "jp.naver.line.android",
                    receivedAt = 1_700_000_000_000L + i,
                    sourceTimestamp = 1_700_000_000_000L + i,
                    title = "Conversation $conv",
                    sender = if (sameText) "Same Sender" else "Sender $conv",
                    body = if (sameText) "Same text every time" else "Message body $i",
                    image = null,
                    conversationKey = "conv-$conv",
                    messageKind = "DIRECT_MESSAGE",
                    messageDiscriminator = "idx:$i"
                )
            )
        }
        // One transaction, like the pipeline does.
        db.saveBatches(listOf(pending))
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        val rows = db.countRows()
        db.close()
        val file = databaseFile()
        return StorageResult(
            rows = rows,
            messages = messages,
            conversations = conversations,
            seconds = seconds,
            dbBytes = if (file != null && file.exists()) file.length() else 0L,
            bytesPerMessage = if (messages > 0) (file?.length() ?: 0L) / messages else 0L
        )
    }

    private fun databaseFile(): File? {
        val dir = ctx.getDatabasePath("inbox.db").parentFile ?: return null
        return File(dir, "inbox.db")
    }

    @Test
    fun theStorageCapacityIsMeasuredAtEachScale() {
        val device = DeviceCapabilities.describe()
        android.util.Log.i("PrivateInboxStorage", "STORAGE-DEVICE $device")
        val scales = listOf(100, 500, 1000, 10_000)
        for (scale in scales) {
            val result = store(scale, if (scale <= 1000) 1 else 10)
            android.util.Log.i("PrivateInboxStorage", "STORAGE $result")
            assertEquals("every message must be stored", scale, result.rows)
        }
    }

    @Test
    fun theThroughputIsMeasuredAcrossConversationShapes() {
        val device = DeviceCapabilities.describe()
        android.util.Log.i("PrivateInboxStorage", "STORAGE-DEVICE $device")
        // One group, ten groups, and the same text repeated: the last one is
        // the shape that stresses the DEDUP path the most.
        val shapes = listOf(
            "single-group" to (500 to 1),
            "ten-groups" to (500 to 10),
            "same-text" to (500 to 10)
        )
        for ((name, shape) in shapes) {
            val sameText = name == "same-text"
            val result = store(shape.first, shape.second, sameText)
            android.util.Log.i("PrivateInboxStorage", "STORAGE-$name $result")
            assertEquals("every message must be stored", shape.first, result.rows)
        }
    }

    @Test
    fun theDatabaseFileGrowsPredictably() {
        val before = databaseFile()?.length() ?: 0L
        val result = store(1000, 10)
        val after = databaseFile()?.length() ?: 0L
        android.util.Log.i(
            "PrivateInboxStorage",
            "STORAGE-GROWTH before=$before after=$after delta=${after - before} per_msg=${result.bytesPerMessage}"
        )
        assertTrue("the database must have grown", after >= before)
        assertTrue(
            "a stored message must cost some disk",
            result.bytesPerMessage > 0
        )
    }

    @Test
    fun theQueryPlansUseTheIndexes() {
        val db = InboxDatabase(ctx)
        db.saveBatches(
            listOf(
                List(200) { i ->
                    InboxDatabase.PendingMessage(
                        packageName = "jp.naver.line.android",
                        receivedAt = 1_700_000_000_000L + i,
                        sourceTimestamp = 1_700_000_000_000L + i,
                        title = "Conversation ${i % 5}",
                        sender = "Sender ${i % 5}",
                        body = "Body $i",
                        image = null,
                        conversationKey = "conv-${i % 5}",
                        messageKind = "DIRECT_MESSAGE",
                        messageDiscriminator = "idx:$i"
                    )
                }
            )
        )
        // The plan for the query the list screen runs.
        val plan = db.queryPlan(
            "SELECT id FROM messages WHERE package_name = ? ORDER BY received_at DESC, id DESC LIMIT 50"
        )
        android.util.Log.i("PrivateInboxStorage", "STORAGE-PLAN $plan")
        val indexes = db.indexNames()
        android.util.Log.i("PrivateInboxStorage", "STORAGE-INDEXES $indexes")
        db.close()
        assertTrue(
            "the plan must use an index, not a full table scan: $plan",
            plan.contains("USING INDEX") || plan.contains("USING COVERING INDEX")
        )
        assertTrue(
            "the composite index must exist: $indexes",
            indexes.contains("package_received_idx")
        )
    }
}
