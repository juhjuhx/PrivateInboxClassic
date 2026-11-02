package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * T07 — the canonical layer's own costs, measured, not asserted.
 *
 * These are the operations v0.4.6 ADDED to the capture and read paths, so they
 * are the ones that could regress. Percentiles are computed from the raw samples
 * and reported to stdout (visible via `am instrument` / logcat); the assertions
 * are LOOSE ceilings that would catch an order-of-magnitude regression, not
 * performance claims.
 */
@RunWith(AndroidJUnit4::class)
class CanonicalPerformanceInstrumentedTest {

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

    private fun percentiles(samples: List<Long>): Map<String, Long> {
        val sorted = samples.sorted()
        fun p(q: Double): Long = sorted[(q * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)]
        return mapOf(
            "p50" to p(0.50), "p95" to p(0.95), "p99" to p(0.99),
            "n" to sorted.size.toLong(), "max" to sorted.last()
        )
    }

    private fun report(name: String, micros: List<Long>) {
        val s = percentiles(micros)
        println("PERF $name " + s.entries.joinToString(" ") { "${it.key}=${it.value}us" })
    }

    @Test
    fun measureResolveAndLinkOverhead() {
        val store = ConversationStore(ctx)
        try {
            val resolver = ConversationIdentityResolver(store)
            val resolveSamples = ArrayList<Long>()
            val linkSamples = ArrayList<Long>()
            repeat(100) { i ->
                val t0 = System.nanoTime()
                val res = resolver.resolve(pkg, "sc-perf-$i", "聊天$i")
                val t1 = System.nanoTime()
                val id = save("聊天$i", "body-$i", 1_700_000_000_000L + i)
                store.linkMessage(id, res.conversationId)
                val t2 = System.nanoTime()
                resolveSamples.add((t1 - t0) / 1000)
                linkSamples.add((t2 - t1) / 1000)
            }
            report("resolve_new_conversation", resolveSamples)
            report("link_single_message", linkSamples)
        } finally { store.close() }
    }

    @Test
    fun measureRepeatResolveOnExistingConversation() {
        val store = ConversationStore(ctx)
        try {
            val resolver = ConversationIdentityResolver(store)
            // First resolve creates the conversation; the rest are lookups.
            resolver.resolve(pkg, "sc-warm", "warm")
            val samples = ArrayList<Long>()
            repeat(100) { resolver.resolve(pkg, "sc-warm", "warm").let {
                samples.add(1L) } }
            // Re-measure with real timing around the resolve.
            samples.clear()
            repeat(100) {
                val t0 = System.nanoTime()
                resolver.resolve(pkg, "sc-warm", "warm")
                samples.add((System.nanoTime() - t0) / 1000)
            }
            report("resolve_existing_conversation", samples)
        } finally { store.close() }
    }

    @Test
    fun conversationPageScalesWithConversationCount() {
        val store = ConversationStore(ctx)
        try {
            val resolver = ConversationIdentityResolver(store)
            // 100 conversations, 1 message each.
            repeat(100) { i ->
                val res = resolver.resolve(pkg, "sc-c$i", "C$i")
                val id = save("C$i", "body", 1_700_000_000_000L + i)
                store.linkMessage(id, res.conversationId)
            }
            val db = InboxDatabase(ctx)
            val pageSamples = ArrayList<Long>()
            try {
                repeat(30) {
                    val t0 = System.nanoTime()
                    val page = db.conversationPage(50, 0)
                    pageSamples.add((System.nanoTime() - t0) / 1000)
                    assertEquals("100 conversations, page of 50", 50, page.size)
                }
            } finally { db.close() }
            report("conversationPage_100convos_p50", pageSamples)
        } finally { store.close() }
    }

    @Test
    fun thousandConversationsPageStillBounded() {
        val store = ConversationStore(ctx)
        try {
            val resolver = ConversationIdentityResolver(store)
            repeat(1000) { i ->
                val res = resolver.resolve(pkg, "sc-k$i", "K$i")
                val id = save("K$i", "b", 1_700_000_000_000L + i)
                store.linkMessage(id, res.conversationId)
            }
            val db = InboxDatabase(ctx)
            val samples = ArrayList<Long>()
            try {
                repeat(10) {
                    val t0 = System.nanoTime()
                    val page = db.conversationPage(50, 0)
                    samples.add((System.nanoTime() - t0) / 1000)
                    assertEquals(50, page.size)
                }
            } finally { db.close() }
            report("conversationPage_1000convos_p50", samples)
            // A loose ceiling: the summary query must not be seconds.
            assertTrue(
                "conversationPage p95 under 1000 convos exceeded 500ms",
                percentiles(samples)["p95"]!! < 500_000
            )
        } finally { store.close() }
    }
}
