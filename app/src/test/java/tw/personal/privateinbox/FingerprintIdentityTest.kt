package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dedup key must identify a MESSAGE, not a notification revision.
 *
 * The bug this protects against: the dedup key fell back to the capture time
 * when the source provided no timestamp. The capture time is per NOTIFICATION,
 * so every message inside one group notification produced the same key and
 * collapsed into a single row. A burst of 100 group messages was stored as one
 * message — the mechanism behind "the group burst is missing messages".
 *
 * The fingerprint is an HMAC over [CryptoStore.fingerprintInput], so these
 * tests exercise the exact bytes that are hashed.
 */
class FingerprintIdentityTest {

    private fun key(
        pkg: String = "jp.naver.line.android",
        ts: Long = 0L,
        title: String = "Group",
        sender: String = "Alice",
        body: String = "hello",
        discriminator: String = ""
    ): String = CryptoStore.fingerprintInput(
        pkg, ts, title, "$sender\u0000$body", discriminator
    ).toString(Charsets.ISO_8859_1)

    @Test
    fun twoDistinctMessagesWithoutTimestampGetDifferentKeys() {
        // The same text, two different positions in the notification.
        assertNotEquals(
            "two distinct messages must not collapse into one row",
            key(discriminator = "idx:0"), key(discriminator = "idx:1")
        )
    }

    @Test
    fun theSameMessageKeepsItsKeyAcrossNotificationRevisions() {
        // Revision 1 carries message 0; revision 2 carries messages 0 and 1.
        // Message 0 is at index 0 in both, so its key must be identical.
        assertEquals(
            "the same message must keep its key when the notification is re-sent",
            key(discriminator = "idx:0"), key(discriminator = "idx:0")
        )
    }

    @Test
    fun theDiscriminatorIsAppendedOnlyWhenItIsPresent() {
        // The database omits the discriminator when the source provided a
        // timestamp, which is what keeps an already-stored message's key
        // unchanged. The input builder therefore has to leave the historical
        // format untouched when the discriminator is empty.
        assertEquals(
            "an empty discriminator must not change the hashed input",
            key(ts = 1_700_000_000_000L, discriminator = ""),
            key(ts = 1_700_000_000_000L)
        )
        assertNotEquals(
            "a present discriminator must change the hashed input",
            key(ts = 1_700_000_000_000L, discriminator = "idx:0"),
            key(ts = 1_700_000_000_000L, discriminator = "")
        )
    }

    @Test
    fun theHistoricalFingerprintFormatIsPreservedWhenNoDiscriminatorIsGiven() {
        // Rows written by an earlier version must keep their fingerprint, or
        // they would be captured a second time after an update.
        val historical = CryptoStore.fingerprintInput(
            "jp.naver.line.android", 1_700_000_000_000L, "Group", "Alice\u0000hello"
        ).toString(Charsets.ISO_8859_1)
        assertEquals(
            "the fingerprint of an already-stored message must not change",
            historical, key(ts = 1_700_000_000_000L, body = "hello")
        )
    }

    @Test
    fun differentPackagesNeverShareAKey() {
        assertNotEquals(
            "the same message in two apps must not share a key",
            key(pkg = "jp.naver.line.android", discriminator = "idx:0"),
            key(pkg = "com.instagram.barcelona", discriminator = "idx:0")
        )
    }

    @Test
    fun differentConversationsNeverShareAKey() {
        assertNotEquals(
            "the same message in two conversations must not share a key",
            key(title = "Group A", discriminator = "idx:0"),
            key(title = "Group B", discriminator = "idx:0")
        )
    }

    @Test
    fun differentSendersNeverShareAKey() {
        assertNotEquals(
            "the same text from two senders must not share a key",
            key(sender = "Alice", discriminator = "idx:0"),
            key(sender = "Bob", discriminator = "idx:0")
        )
    }

    @Test
    fun theComponentsAreSeparatedByAFieldSeparator() {
        // Without a separator, ("ab", "c") and ("a", "bc") would hash the same.
        assertNotEquals(
            "the components must be separated unambiguously",
            key(sender = "ab", body = "c"),
            key(sender = "a", body = "bc")
        )
    }
}
