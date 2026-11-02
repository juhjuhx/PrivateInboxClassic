package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The historical conversation key format.
 *
 * v0.4.5 built the key as `package \0 (shortcutId ?: title) \0 title`. That is
 * the format already stored in `messages.conversation_key`, and it is an INPUT
 * to the dedup fingerprint, so it can never be rewritten. v0.4.6 must be able to
 * PARSE it in order to back-fill alias rows without changing the stored value.
 *
 * These tests pin the parse/format behaviour, including the cases where the
 * format is genuinely ambiguous — an ambiguous key must be reported as such, not
 * guessed at.
 */
class LegacyKeyCodecTest {

    // ---------------------------------------------------------------- format

    @Test
    fun formatsTheHistoricalShape() {
        val key = LegacyKeyCodec.format(
            packageName = "org.telegram.messenger",
            discriminator = "shortcut-1",
            title = "Alice"
        )
        assertEquals("org.telegram.messenger\u0000shortcut-1\u0000Alice", key)
    }

    @Test
    fun formatWithBlankShortcutFallsBackToTitleAsTheMiddlePart() {
        // v0.4.5: `shortcutId?.takeIf { isNotBlank() } ?: title`
        val key = LegacyKeyCodec.format(
            packageName = "jp.naver.line.android",
            discriminator = null,
            title = "家族群組"
        )
        assertEquals("jp.naver.line.android\u0000家族群組\u0000家族群組", key)
    }

    // ---------------------------------------------------------------- parse

    @Test
    fun parsesAKeyWithAShortcut() {
        val parsed = LegacyKeyCodec.parse("org.telegram.messenger\u0000shortcut-1\u0000Alice")
        assertEquals("org.telegram.messenger", parsed.packageName)
        assertEquals("shortcut-1", parsed.middle)
        assertEquals("Alice", parsed.title)
        assertTrue("a middle part distinct from the title is a usable shortcut",
            parsed.middleLooksLikeShortcut)
        assertFalse(parsed.ambiguous)
    }

    @Test
    fun parsesATitleOnlyKeyAsAmbiguousNotAsAShortcut() {
        // middle == title means the historical code had no shortcut id: the
        // middle part is just the title repeated, and must NOT be mistaken for
        // a stable shortcut identifier.
        val parsed = LegacyKeyCodec.parse("jp.naver.line.android\u0000家族群組\u0000家族群組")
        assertEquals("家族群組", parsed.middle)
        assertEquals("家族群組", parsed.title)
        assertFalse("middle equals title -> no real shortcut", parsed.middleLooksLikeShortcut)
        assertTrue("a title-only identity is ambiguous", parsed.ambiguous)
    }

    @Test
    fun parsesATitleThatItselfContainsTheSeparatorAsAmbiguous() {
        // A title with an embedded NUL cannot round-trip. It must be flagged,
        // never silently split at the wrong boundary.
        val parsed = LegacyKeyCodec.parse("org.telegram.messenger\u0000a\u0000b\u0000c")
        assertTrue("extra separators make the key ambiguous", parsed.ambiguous)
    }

    @Test
    fun aKeyWithNoSeparatorIsAmbiguousAndPackageOnly() {
        val parsed = LegacyKeyCodec.parse("org.telegram.messenger")
        assertEquals("org.telegram.messenger", parsed.packageName)
        assertEquals("", parsed.title)
        assertTrue(parsed.ambiguous)
    }

    @Test
    fun emptyKeyIsEmptyNotAmbiguousCrash() {
        val parsed = LegacyKeyCodec.parse("")
        assertEquals("", parsed.packageName)
        assertTrue(parsed.ambiguous)
    }

    // ---------------------------------------------------------------- unicode

    @Test
    fun unicodeAndEmojiSurviveRoundTrip() {
        val key = LegacyKeyCodec.format(
            packageName = "jp.naver.line.android",
            discriminator = "sc-😀",
            title = "日本語のグループ 👍🏽"
        )
        val parsed = LegacyKeyCodec.parse(key)
        assertEquals("sc-😀", parsed.middle)
        assertEquals("日本語のグループ 👍🏽", parsed.title)
        assertFalse(parsed.ambiguous)
    }

    @Test
    fun longTitleIsNotTruncated() {
        val long = "長".repeat(500)
        val key = LegacyKeyCodec.format("jp.naver.line.android", "sc-1", long)
        val parsed = LegacyKeyCodec.parse(key)
        assertEquals(long, parsed.title)
        assertEquals(long.length, parsed.title.length)
    }

    @Test
    fun newlinesInsideTheTitleArePreserved() {
        val title = "line one\nline two"
        val key = LegacyKeyCodec.format("jp.naver.line.android", "sc-1", title)
        assertEquals(title, LegacyKeyCodec.parse(key).title)
    }

    // ---------------------------------------------------------------- null

    @Test
    fun nullAndBlankAreEquivalentForTheMiddlePart() {
        val a = LegacyKeyCodec.format("pkg", null, "T")
        val b = LegacyKeyCodec.format("pkg", "   ", "T")
        assertEquals("blank shortcut must behave like null", a, b)
    }

    @Test
    fun noShortcutMarkerIsRecognised() {
        // The resolver needs to know whether a legacy key came from a real
        // shortcut or from the title fallback.
        val withShortcut = LegacyKeyCodec.parse("pkg\u0000sc-9\u0000Alice")
        val withoutShortcut = LegacyKeyCodec.parse("pkg\u0000Alice\u0000Alice")
        assertTrue(withShortcut.middleLooksLikeShortcut)
        assertFalse(withoutShortcut.middleLooksLikeShortcut)
    }

    @Test
    fun packageNameIsAlwaysTheFirstSegment() {
        val parsed = LegacyKeyCodec.parse("com.whatsapp\u0000x\u0000y")
        assertEquals("com.whatsapp", parsed.packageName)
    }

    @Test
    fun parsedTitleIsNeverNull() {
        assertNull(LegacyKeyCodec.parse("pkg\u0000x").title.takeIf { false })
        assertEquals("", LegacyKeyCodec.parse("pkg\u0000x").title)
    }
}
