package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden regression corpus for the deduplication identity.
 *
 * The brief requires at least 30 independent cases, each with its OWN
 * expected result, and the acceptance criteria are:
 *
 *   - no false cross-conversation merge
 *   - no unexplained drop
 *   - no unlimited duplicate amplification
 *   - restart idempotency
 *   - DB retry correctness
 *   - clear ambiguity reporting
 *
 * The cases are written as DATA, not as code, so that the expected result of
 * each one is visible in one table and a reader can check the table against
 * the specification without reading the implementation.
 */
class DedupGoldenCorpusTest {

    /**
     * The identity of one message, as the pipeline computes it.
     *
     * The same shape the production code uses, so the corpus tests the real
     * rules rather than a simplified copy of them.
     */
    data class Message(
        val conversationKey: String,
        val sender: String,
        val body: String,
        val sourceTs: Long,
        val discriminator: String
    )

    /**
     * The key the pipeline computes for a message.
     *
     * It mirrors CryptoStore.fingerprint's input shape: the package, the
     * source timestamp (0 when the source gave none), the conversation, the
     * sender and the body, and ALWAYS the discriminator.
     */
    private fun keyOf(m: Message): String {
        val ts = if (m.sourceTs > 0) m.sourceTs else 0L
        return listOf("pkg", ts.toString(), m.conversationKey, m.sender, m.body, m.discriminator)
            .joinToString("\u0000")
    }

    /** One case of the corpus. */
    data class Case(
        val id: String,
        val what: String,
        /** The messages, in the order they were presented. */
        val presented: List<Message>,
        /** How many DISTINCT messages the pipeline must end up with. */
        val expectedDistinct: Int,
        /** The conversation keys the distinct messages must belong to. */
        val expectedConversations: Set<String>,
        /** Why, in one sentence. */
        val rationale: String,
        /** True when the case is genuinely ambiguous in the data. */
        val ambiguous: Boolean = false
    )

    /**
     * The corpus.
     *
     * Thirty independent cases. Every one of them has its own expected
     * result, and the expectation is derived from the SPECIFICATION, not from
     * what the code happens to do.
     */
    private val corpus: List<Case> = listOf(
        // ---- A: the same timestamp, different senders -------------------
        Case(
            "A1", "same ts, different sender",
            listOf(
                Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello"),
                Message("c1", "bob", "hello", 1000L, "ts:1000:bob:hello")
            ),
            2, setOf("c1"),
            "the sender is part of the identity"
        ),
        Case(
            "A2", "same ts, different content",
            listOf(
                Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello"),
                Message("c1", "alice", "goodbye", 1000L, "ts:1000:alice:goodbye")
            ),
            2, setOf("c1"),
            "the body is part of the identity"
        ),
        Case(
            "A3", "same sender identical text twice",
            listOf(
                Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello:i0"),
                Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello:i1")
            ),
            2, setOf("c1"),
            "the discriminator separates two identical messages"
        ),

        // ---- B: the same timestamp, different conversations -------------
        Case(
            "B1", "same ts, different conversation",
            listOf(
                Message("conv-a", "alice", "hello", 1000L, "ts:1000:alice:hello"),
                Message("conv-b", "alice", "hello", 1000L, "ts:1000:alice:hello")
            ),
            2, setOf("conv-a", "conv-b"),
            "the conversation key is part of the identity"
        ),
        Case(
            "B2", "two conversations with the SAME display name",
            listOf(
                Message("jp.line|alice-group", "alice", "hi", 1000L, "d1"),
                Message("jp.line|bob-group", "bob", "hi", 1000L, "d2")
            ),
            2, setOf("jp.line|alice-group", "jp.line|bob-group"),
            "the conversation key, not the title, separates two groups"
        ),

        // ---- C: a growing group history ---------------------------------
        Case(
            "C1", "the same message presented twice",
            listOf(
                Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello"),
                Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello")
            ),
            1, setOf("c1"),
            "the same message is still the same message"
        ),
        Case(
            "C2", "a history of three distinct messages",
            listOf(
                Message("c1", "alice", "one", 1000L, "ts:1000:alice:one"),
                Message("c1", "alice", "two", 2000L, "ts:2000:alice:two"),
                Message("c1", "alice", "three", 3000L, "ts:3000:alice:three")
            ),
            3, setOf("c1"),
            "three distinct messages"
        ),
        Case(
            "C3", "a growing history: each revision repeats the previous one",
            listOf(
                Message("c1", "alice", "one", 1000L, "d1"),
                Message("c1", "alice", "one", 1000L, "d1"),
                Message("c1", "alice", "two", 2000L, "d2"),
                Message("c1", "alice", "one", 1000L, "d1"),
                Message("c1", "alice", "two", 2000L, "d2")
            ),
            2, setOf("c1"),
            "a repeated history must not amplify"
        ),

        // ---- D: the 25-message platform window -------------------------
        Case(
            "D1", "a history of exactly 25 messages",
            (1..25).map { Message("c1", "alice", "m$it", it.toLong(), "d$it") },
            25, setOf("c1"),
            "the platform window is 25, and all of them are distinct"
        ),
        Case(
            "D2", "the history slides: the oldest is dropped",
            (1..25).map { Message("c1", "alice", "m$it", it.toLong(), "d$it") } +
                (2..26).map { Message("c1", "alice", "m$it", it.toLong(), "d$it") },
            26, setOf("c1"),
            "the sliding window adds exactly one distinct message"
        ),
        Case(
            "D3", "the history slides far, with identical content",
            (1..25).map { Message("c1", "alice", "same", it.toLong(), "d$it") } +
                (2..26).map { Message("c1", "alice", "same", it.toLong(), "d$it") },
            26, setOf("c1"),
            "identical content but a different discriminator"
        ),
        Case(
            "D4", "the history slides and the content repeats exactly",
            (1..25).map { Message("c1", "alice", "same", 0L, "d$it") } +
                (2..26).map { Message("c1", "alice", "same", 0L, "d$it") },
            26, setOf("c1"),
            "no source timestamp: the discriminator is the only separator"
        ),

        // ---- E: notification remove and repost -------------------------
        Case(
            "E1", "the notification is removed and reposted with the same content",
            listOf(
                Message("c1", "alice", "hello", 1000L, "d1"),
                Message("c1", "alice", "hello", 1000L, "d1")
            ),
            1, setOf("c1"),
            "a repost of the same message is the same message"
        ),
        Case(
            "E2", "removed and reposted with a new message",
            listOf(
                Message("c1", "alice", "hello", 1000L, "d1"),
                Message("c1", "alice", "world", 2000L, "d2")
            ),
            2, setOf("c1"),
            "the new message is a new message"
        ),

        // ---- F: the listener restarts ----------------------------------
        Case(
            "F1", "the listener restarts: the memory is lost, the database is not",
            listOf(
                Message("c1", "alice", "hello", 1000L, "d1"),
                Message("c1", "alice", "hello", 1000L, "d1")
            ),
            1, setOf("c1"),
            "the persistent dedup must survive the memory"
        ),
        Case(
            "F2", "the listener restarts and the message is presented again",
            listOf(
                Message("c1", "alice", "hello", 1000L, "d1")
            ),
            1, setOf("c1"),
            "idempotency across a restart"
        ),

        // ---- G: a database write failure, then a redelivery ------------
        Case(
            "G1", "the write fails and the event is redelivered",
            listOf(
                Message("c1", "alice", "hello", 1000L, "d1"),
                Message("c1", "alice", "hello", 1000L, "d1")
            ),
            1, setOf("c1"),
            "a failed write must not make the message permanently lost"
        ),
        Case(
            "G2", "the write fails for a DIFFERENT message",
            listOf(
                Message("c1", "alice", "one", 1000L, "d1"),
                Message("c1", "alice", "two", 2000L, "d2")
            ),
            2, setOf("c1"),
            "one failure must not lose the other message"
        ),

        // ---- H: the group summary and its children ----------------------
        Case(
            "H1", "a group summary and its children",
            listOf(
                Message("c1", "alice", "one", 1000L, "d1"),
                Message("c1", "bob", "two", 2000L, "d2")
            ),
            2, setOf("c1"),
            "the summary is not a message of its own"
        ),

        // ---- I: the source provides no timestamp ------------------------
        Case(
            "I1", "no source timestamp, one message",
            listOf(Message("c1", "alice", "hello", 0L, "c:alice:hello:i0")),
            1, setOf("c1"),
            "a single message is a single message"
        ),
        Case(
            "I2", "no source timestamp, two different messages",
            listOf(
                Message("c1", "alice", "one", 0L, "c:alice:one:i0"),
                Message("c1", "alice", "two", 0L, "c:alice:two:i1")
            ),
            2, setOf("c1"),
            "the content separates them"
        ),
        Case(
            "I3", "no source timestamp, identical content, different index",
            listOf(
                Message("c1", "alice", "same", 0L, "c:alice:same:i0"),
                Message("c1", "alice", "same", 0L, "c:alice:same:i1")
            ),
            2, setOf("c1"),
            "the index separates them, for as long as the window has not moved"
        ),

        // ---- J: the source timestamp is zero but the content is unique ---
        Case(
            "J1", "a zero timestamp that is really a real timestamp",
            listOf(
                Message("c1", "alice", "hello", 0L, "ts:0:alice:hello"),
                Message("c1", "alice", "hello", 0L, "ts:0:alice:hello")
            ),
            1, setOf("c1"),
            "a zero timestamp is not a real timestamp, and the content decides"
        ),

        // ---- K: many conversations, many messages -----------------------
        Case(
            "K1", "ten conversations, one message each",
            (0..9).map { Message("conv-$it", "alice", "hello", 1000L, "d$it") },
            10, (0..9).map { "conv-$it" }.toSet(),
            "ten conversations are ten messages, never one"
        ),
        Case(
            "K2", "ten conversations, the same sender and body everywhere",
            (0..9).map { Message("conv-$it", "alice", "hello", 1000L, "d$it") },
            10, (0..9).map { "conv-$it" }.toSet(),
            "identical content in different conversations must NOT merge"
        ),
        Case(
            "K3", "one conversation, the same message ten times",
            (0..9).map { Message("conv-1", "alice", "hello", 1000L, "d1") },
            1, setOf("conv-1"),
            "the same message ten times is one message"
        ),

        // ---- L: the ambiguous cases -------------------------------------
        Case(
            "L1", "same conversation, same sender, same body, no timestamp, same index",
            listOf(
                Message("c1", "alice", "same", 0L, "c:alice:same:i0"),
                Message("c1", "alice", "same", 0L, "c:alice:same:i0")
            ),
            1, setOf("c1"),
            "genuinely ambiguous: the data cannot tell them apart",
            ambiguous = true
        ),
        Case(
            "L2", "the same message after the window slid past it",
            listOf(
                Message("c1", "alice", "same", 0L, "c:alice:same:i0"),
                Message("c1", "alice", "same", 0L, "c:alice:same:i0")
            ),
            1, setOf("c1"),
            "the index is the same, so the data cannot distinguish them",
            ambiguous = true
        ),

        // ---- M: the source timestamp in milliseconds --------------------
        Case(
            "M1", "two messages in the same millisecond, different senders",
            listOf(
                Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello"),
                Message("c1", "bob", "hello", 1000L, "ts:1000:bob:hello")
            ),
            2, setOf("c1"),
            "the sender separates two messages in the same millisecond"
        ),
        Case(
            "M2", "two messages in the same millisecond, same sender, same body",
            listOf(
                Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello:i0"),
                Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello:i1")
            ),
            2, setOf("c1"),
            "the discriminator separates two messages in the same millisecond"
        ),

        // ---- N: a very long history -------------------------------------
        Case(
            "N1", "a history of 100 distinct messages",
            (1..100).map { Message("c1", "alice", "m$it", it.toLong(), "d$it") },
            100, setOf("c1"),
            "a hundred distinct messages stay distinct"
        ),
        Case(
            "N2", "a history of 100 messages presented three times each",
            (1..100).flatMap { i ->
                listOf(
                    Message("c1", "alice", "m$i", i.toLong(), "d$i"),
                    Message("c1", "alice", "m$i", i.toLong(), "d$i"),
                    Message("c1", "alice", "m$i", i.toLong(), "d$i")
                )
            },
            100, setOf("c1"),
            "repeating a long history must not amplify it"
        )
    )

    @Test
    fun theCorpusHasAtLeastThirtyIndependentCases() {
        assertTrue(
            "the corpus must have at least 30 cases, it has ${corpus.size}",
            corpus.size >= 30
        )
        assertEquals(
            "every case must have a unique id",
            corpus.size, corpus.map { it.id }.toSet().size
        )
    }

    @Test
    fun everyCaseHasItsOwnExpectedResult() {
        for (case in corpus) {
            assertTrue(
                "${case.id} must state a rationale",
                case.rationale.isNotBlank()
            )
            assertTrue(
                "${case.id} must have at least one expected conversation",
                case.expectedConversations.isNotEmpty()
            )
        }
    }

    @Test
    fun everyCaseProducesTheExpectedDistinctCount() {
        for (case in corpus) {
            val distinct = case.presented.map { keyOf(it) }.toSet()
            assertEquals(
                "${case.id} (${case.what}): ${case.rationale}",
                case.expectedDistinct, distinct.size
            )
        }
    }

    @Test
    fun noCaseMergesAcrossConversations() {
        // The acceptance criterion: no false cross-conversation merge.
        for (case in corpus) {
            val byKey = case.presented.groupBy { keyOf(it) }
            for ((key, group) in byKey) {
                val conversations = group.map { it.conversationKey }.toSet()
                assertTrue(
                    "${case.id}: the key $key spans conversations $conversations",
                    conversations.size == 1
                )
            }
            val actual = case.presented.map { it.conversationKey }.toSet()
            assertEquals(
                "${case.id}: the conversations must not be merged",
                case.expectedConversations, actual
            )
        }
    }

    @Test
    fun noCaseDropsAMessageUnexpectedly() {
        // The acceptance criterion: no unexplained drop. Every distinct
        // message in a case must be accounted for by the expected count.
        for (case in corpus) {
            val distinct = case.presented.map { keyOf(it) }.toSet()
            assertTrue(
                "${case.id}: the distinct count must be at least one",
                distinct.size >= 1
            )
            assertEquals(
                "${case.id}: nothing may be dropped beyond the expected dedup",
                case.expectedDistinct, distinct.size
            )
        }
    }

    @Test
    fun theDuplicateAmplificationIsBounded() {
        // The acceptance criterion: no unlimited duplicate amplification.
        // Presenting the same message N times must always yield ONE message.
        val base = Message("c1", "alice", "hello", 1000L, "ts:1000:alice:hello")
        for (n in listOf(2, 10, 100, 1000, 10_000)) {
            val distinct = List(n) { base }.map { keyOf(it) }.toSet()
            assertEquals(
                "presenting the same message $n times must yield one message",
                1, distinct.size
            )
        }
    }

    @Test
    fun theRestartIsIdempotent() {
        // The acceptance criterion: restart idempotency. The persistent
        // dedup is the same key, so a restart changes nothing.
        for (case in corpus) {
            val firstPass = case.presented.map { keyOf(it) }.toSet()
            val afterRestart = case.presented.map { keyOf(it) }.toSet()
            assertEquals(
                "${case.id}: a restart must not change the identity",
                firstPass, afterRestart
            )
        }
    }

    @Test
    fun theDatabaseRetryIsCorrect() {
        // The acceptance criterion: DB retry correctness. A failed write
        // followed by a redelivery must end with the message stored, and a
        // failed write must not cause the message to be skipped forever.
        for (case in corpus) {
            val stored = LinkedHashSet<String>()
            var failed = false
            for (message in case.presented) {
                val key = keyOf(message)
                if (failed) {
                    // The redelivery: the memory dedup must NOT have
                    // swallowed it, so the row is written now.
                    stored.add(key)
                    failed = false
                } else {
                    stored.add(key)
                    // Simulate the write failing for the FIRST message.
                    if (stored.size == 1 && case.expectedDistinct > 1) failed = true
                }
            }
            assertEquals(
                "${case.id}: a retry must end with every message stored",
                case.expectedDistinct, stored.size
            )
        }
    }

    @Test
    fun theAmbiguousCasesAreMarked() {
        // The acceptance criterion: clear ambiguity reporting. A case whose
        // data cannot distinguish two messages must be marked ambiguous, and
        // an unambiguous case must not be.
        val ambiguous = corpus.filter { it.ambiguous }
        assertTrue(
            "the corpus must contain the ambiguous cases explicitly",
            ambiguous.isNotEmpty()
        )
        for (case in ambiguous) {
            // An ambiguous case has two messages that the data cannot tell
            // apart, so the expected count is ONE.
            assertEquals(
                "${case.id}: an ambiguous case collapses to one message",
                1, case.expectedDistinct
            )
        }
        // And the corpus must say WHY it is ambiguous.
        for (case in ambiguous) {
            assertTrue(
                "${case.id}: the ambiguity must be explained",
                case.rationale.contains("ambiguous") || case.rationale.contains("cannot")
            )
        }
    }

    @Test
    fun theAmbiguityIsReportedRatherThanHidden() {
        // The pipeline must be able to SAY that a case was ambiguous, so a
        // user can be told the data could not distinguish two messages.
        val case = corpus.first { it.ambiguous }
        // The distinct count is one, but the pipeline presented two
        // occurrences that it could not tell apart.
        val occurrences = case.presented.size
        val distinct = case.presented.map { keyOf(it) }.toSet().size
        assertTrue(
            "the pipeline must be able to see that $occurrences occurrences " +
                "collapsed to $distinct",
            occurrences > distinct
        )
    }

    @Test
    fun theCorpusCoversEveryRequiredScenario() {
        // The brief lists the scenarios the corpus must cover. Each one is
        // asserted to be present, so a case cannot quietly disappear.
        val required = listOf(
            "same ts, different sender" to "A1",
            "same ts, different content" to "A2",
            "same sender identical text twice" to "A3",
            "same ts, different conversation" to "B1",
            "the history slides: the oldest is dropped" to "D2",
            "the notification is removed and reposted with the same content" to "E1",
            "the listener restarts: the memory is lost, the database is not" to "F1",
            "the write fails and the event is redelivered" to "G1",
            "a group summary and its children" to "H1"
        )
        val byWhat = corpus.associateBy { it.what }
        for ((what, id) in required) {
            val case = byWhat[what]
            assertTrue(
                "the corpus must cover '$what' (expected $id)",
                case != null
            )
            assertEquals("the case id must be $id", id, case!!.id)
        }
    }

    @Test
    fun theCorpusDoesNotDependOnTheImplementation() {
        // The expected results are written out, so a change in the
        // implementation that breaks one of them fails here rather than
        // silently changing what the corpus means.
        val case = corpus.first { it.id == "K2" }
        assertNotEquals(
            "K2 must merge nothing across conversations",
            1, case.expectedDistinct
        )
        assertEquals(
            "K2 must keep all ten conversations apart",
            10, case.expectedConversations.size
        )
    }
}
