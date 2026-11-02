package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The R1 hard acceptance condition: the fingerprint INPUT bytes must not change.
 *
 * `CryptoStore.fingerprint()` is HMAC-SHA256 over `fingerprintInput(...)`, keyed
 * by a per-device Android Keystore key. That key is not portable, so the HASH
 * cannot be pinned in a committed fixture — but the INPUT BYTES can be, and they
 * decide whether an already-stored row is recognised as a duplicate.
 *
 * If this test fails, deduplication of existing messages is broken: every stored
 * message would look "new" again on the next notification revision.
 *
 * The expected hex values were produced by
 * `docs/handoff/v046/golden/gen_fingerprint_vectors.py` from the PRE-v0.4.6
 * formula. They pin historical behaviour; they are not a new specification.
 */
class LegacyFingerprintCompatibilityTest {

    private data class Vec(
        val name: String, val pkg: String, val ts: Long,
        val title: String, val text: String, val disc: String, val expectedHex: String
    )

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val vectors = listOf(
        Vec(
            "ascii-basic", "org.telegram.messenger", 1700000000000L, "Alice", "hello", "",
            "6f72672e74656c656772616d2e6d657373656e676572003137303030303030303030303000416c6963650068656c6c6f"
        ),
        Vec(
            "with-discriminator", "org.telegram.messenger", 1700000000000L, "Alice", "hello", "c:Alice:hello:i0",
            "6f72672e74656c656772616d2e6d657373656e676572003137303030303030303030303000416c6963650068656c6c6f00633a416c6963653a68656c6c6f3a6930"
        ),
        Vec(
            "zero-timestamp", "jp.naver.line.android", 0L, "家族群組", "你好", "c:Bob:你好:i1",
            "6a702e6e617665722e6c696e652e616e64726f6964003000e5aeb6e6978fe7bea4e7b58400e4bda0e5a5bd00633a426f623ae4bda0e5a5bd3a6931"
        ),
        Vec(
            "unicode-cjk", "jp.naver.line.android", 1700000000001L, "家族群組", "你好，世界", "",
            "6a702e6e617665722e6c696e652e616e64726f6964003137303030303030303030303100e5aeb6e6978fe7bea4e7b58400e4bda0e5a5bdefbc8ce4b896e7958c"
        ),
        Vec(
            "emoji", "com.whatsapp", 1700000000002L, "Bob", "👍🏽", "",
            "636f6d2e7768617473617070003137303030303030303030303200426f6200f09f918df09f8fbd"
        ),
        Vec(
            "empty-body", "com.whatsapp", 1700000000005L, "Bob", "", "",
            "636f6d2e7768617473617070003137303030303030303030303500426f6200"
        ),
        Vec(
            "embedded-nul", "com.whatsapp", 1700000000006L, "Bob", "a\u0000b", "",
            "636f6d2e7768617473617070003137303030303030303030303600426f6200610062"
        ),
        Vec(
            "newlines", "com.whatsapp", 1700000000004L, "Bob", "line1\nline2", "",
            "636f6d2e7768617473617070003137303030303030303030303400426f62006c696e65310a6c696e6532"
        )
    )

    @Test
    fun fingerprintInputMatchesTheHistoricalBytes() {
        assertEquals("expected 8 golden vectors", 8, vectors.size)
        for (v in vectors) {
            val actual = CryptoStore.fingerprintInput(v.pkg, v.ts, v.title, v.text, v.disc)
            assertEquals(
                "fingerprint input changed for '" + v.name + "' - this breaks " +
                    "deduplication of every existing message",
                v.expectedHex,
                hex(actual)
            )
        }
    }

    @Test
    fun theSeparatorLayoutIsExactlyPackageTsTitleText() {
        val s = String(CryptoStore.fingerprintInput("pkg", 7L, "T", "B", ""), Charsets.UTF_8)
        assertEquals("pkg" + 0.toChar() + "7" + 0.toChar() + "T" + 0.toChar() + "B", s)
        assertEquals("package/ts/title/text is 3 separators", 3, s.count { it == 0.toChar() })
    }

    @Test
    fun anEmptyDiscriminatorAddsNoTrailingSeparator() {
        val empty = String(CryptoStore.fingerprintInput("pkg", 7L, "T", "B", ""), Charsets.UTF_8)
        val disc = String(CryptoStore.fingerprintInput("pkg", 7L, "T", "B", "d"), Charsets.UTF_8)
        assertEquals(3, empty.count { it == 0.toChar() })
        assertEquals("a present discriminator adds one separator", 4, disc.count { it == 0.toChar() })
        assertEquals("the discriminator is appended verbatim",
            "pkg" + 0.toChar() + "7" + 0.toChar() + "T" + 0.toChar() + "B" + 0.toChar() + "d", disc)
    }

    @Test
    fun longTextIsNotTruncatedInTheFingerprintInput() {
        val long = "長".repeat(500)
        val s = String(
            CryptoStore.fingerprintInput("com.whatsapp", 1700000000003L, "Bob", long, ""),
            Charsets.UTF_8
        )
        assertEquals("the long text survives in full", 500, s.count { it == '長' })
        assertEquals("no truncation marker is added", false, s.contains("…"))
    }

    @Test
    fun theCanonicalIdMustNeverEnterTheFingerprintTitleSlot() {
        // InboxDatabase.save() passes `conversationKey.ifEmpty { title }` into the
        // title slot. The canonical id ("cv_...") must never be what feeds it.
        val legacyKey = LegacyKeyCodec.format("org.telegram.messenger", "sc-1", "Alice")
        val s = String(
            CryptoStore.fingerprintInput(
                "org.telegram.messenger", 1700000000000L, legacyKey,
                "Alice" + 0.toChar() + "hello", "c:Alice:hello:i0"
            ),
            Charsets.UTF_8
        )
        assertEquals(true, s.contains(legacyKey))
        assertEquals("a canonical id must never appear in fingerprint input",
            false, s.contains("cv_"))
    }
}
