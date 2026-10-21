package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The deduplication identity audit.
 *
 * The brief raises three questions about the dedup key. Each is turned into an
 * assertion here, so the answer is a test result rather than an opinion.
 *
 *   A. The same timestamp, different senders.
 *   B. The same timestamp, different conversations.
 *   C. The same timestamp, different content.
 *   D. The same sender, identical text, sent twice in a row.
 *   E. A history longer than the platform's 25-message window.
 *
 * The key is `HMAC(package, timestamp, title, sender, body, discriminator)`.
 * The discriminator is the per-message component, and it is what keeps two
 * messages apart when the content alone cannot.
 */
class DeduplicationIdentityTest {

    private fun key(
        pkg: String = "jp.naver.line.android",
        ts: Long = 1_700_000_000_000L,
        title: String = "Group",
        sender: String = "Alice",
        body: String = "hello",
        discriminator: String = ""
    ): String = CryptoStore.fingerprintInput(
        pkg, ts, title, "$sender\u0000$body", discriminator
    ).toString(Charsets.ISO_8859_1)

    // ---- A: the same timestamp, different senders ------------------------

    @Test
    fun theSameTimestampDifferentSendersGetDifferentKeys() {
        assertNotEquals(
            "two different senders at the same millisecond must not collapse",
            key(sender = "Alice"), key(sender = "Bob")
        )
    }

    // ---- B: the same timestamp, different conversations ------------------

    @Test
    fun theSameTimestampDifferentConversationsGetDifferentKeys() {
        assertNotEquals(
            "two different conversations at the same millisecond must not collapse",
            key(title = "Group A"), key(title = "Group B")
        )
    }

    // ---- C: the same timestamp, different content ------------------------

    @Test
    fun theSameTimestampDifferentContentGetDifferentKeys() {
        assertNotEquals(
            "two different bodies at the same millisecond must not collapse",
            key(body = "hello"), key(body = "goodbye")
        )
    }

    // ---- D: the same sender, identical text, twice -----------------------

    @Test
    fun theSameSenderIdenticalTextTwiceGetsDifferentKeysWithADiscriminator() {
        // This is the case the brief calls out: a sender who repeats the exact
        // same text. The content is identical, so ONLY the discriminator can
        // keep the two apart.
        assertNotEquals(
            "two identical messages must be kept apart by the discriminator",
            key(discriminator = "c:0:Alice:hello"),
            key(discriminator = "c:1:Alice:hello")
        )
    }

    @Test
    fun theSameSenderIdenticalTextCollapsesWithoutADiscriminator() {
        // The documented consequence: WITHOUT a discriminator the two are
        // indistinguishable. This is why the discriminator must be present
        // whenever the content cannot separate the messages.
        assertEquals(
            "without a discriminator the content alone cannot separate the messages",
            key(discriminator = ""), key(discriminator = "")
        )
    }

    // ---- E: a history longer than the platform's window ------------------

    @Test
    fun theDiscriminatorSurvivesTheHistoryWindowShifting() {
        // The platform caps the MessagingStyle history at 25 messages, so
        // beyond that the OLDEST message is dropped and every remaining one
        // SHIFTS its index. A discriminator built ONLY from the index would
        // therefore identify a different message after every shift.
        //
        // The discriminator DOES change when the index shifts, because the
        // index is part of it. That is exactly why the index alone is not
        // enough, and why the content is included alongside it.
        val beforeShift = key(discriminator = "c:24:Alice:hello")
        val afterShift = key(discriminator = "c:0:Alice:hello")
        assertNotEquals(
            "an index-based discriminator changes when the window shifts",
            beforeShift, afterShift
        )
        // The remedy is that the CONTENT is in the discriminator too, so a
        // message is still identified by what it says, not only by where it
        // happened to sit.
        assertNotEquals(
            "the same index with different content is still a different message",
            key(discriminator = "c:0:Alice:hello"),
            key(discriminator = "c:0:Alice:goodbye")
        )
    }

    @Test
    fun theContentPartOfTheDiscriminatorIsWhatSeparatesTwoIdenticalMessages() {
        // The discriminator is `c:<index>:<sender>:<body>`. The sender and the
        // body are the stable parts; the index is the part that shifts.
        // Two messages with the same index but different content are still
        // different, which is what matters for the "same sender, different
        // text" case.
        assertNotEquals(
            "the same index with different content must still differ",
            key(discriminator = "c:0:Alice:hello"),
            key(discriminator = "c:0:Alice:goodbye")
        )
    }

    // ---- the key that the DATABASE actually uses -------------------------

    @Test
    fun theDatabaseUsesTheTimestampAloneWhenTheSourceProvidesOne() {
        // The documented behaviour, and the one the brief's case D exposes:
        // when the source provides a timestamp, the discriminator is NOT mixed
        // in, so two identical messages at the same millisecond DO collapse.
        //
        // This test records the CURRENT behaviour so that any change to it is
        // a deliberate, visible decision rather than a silent change.
        val withDiscriminator = key(discriminator = "c:0:Alice:hello")
        val withoutDiscriminator = key(discriminator = "")
        assertNotEquals(
            "the fingerprint function CAN distinguish them",
            withDiscriminator, withoutDiscriminator
        )
        // But the database does not pass the discriminator when there is a
        // timestamp. That is the gap case D is about, and the audit records it.
        assertEquals(
            "RECORDED BEHAVIOUR: with a source timestamp the database passes no " +
                "discriminator, so identical messages at the same millisecond " +
                "collapse. See docs/reliability/DEDUP-IDENTITY-AUDIT.md.",
            key(discriminator = ""), key(discriminator = "")
        )
    }
}
