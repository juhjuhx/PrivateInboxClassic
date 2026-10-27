package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long-text & Unicode golden corpus for v0.4.5.
 *
 * Each case documents:
 *   - input
 *   - what CapturePolicy.normalize() must return (or null)
 *   - why
 *
 * The contract: normalize() must NOT silently truncate, must NOT alter
 * semantic content, must NOT collapse internal whitespace runs into a
 * single space (that is a display-layer concern).
 *
 * RED: these tests assert the NEW contract. They fail against the v0.4.4
 * implementation which truncates at 20_000 and collapses whitespace.
 */
class LongTextGoldenCorpusTest {

    // ── Whitespace preservation (no semantic alteration) ──

    @Test
    fun `leading and trailing whitespace is preserved (raw capture)`() {
        // Raw capture: do NOT trim. Whitespace is content.
        assertEquals("  hello  ", CapturePolicy.normalize("  hello  "))
    }

    @Test
    fun `internal tabs and newlines are preserved`() {
        assertEquals("a\tb\nc", CapturePolicy.normalize("a\tb\nc"))
    }

    @Test
    fun `multiple spaces are preserved`() {
        assertEquals("a   b", CapturePolicy.normalize("a   b"))
    }

    @Test
    fun `blank-only input normalizes to null`() {
        assertNull(CapturePolicy.normalize("   \t\n  "))
        assertNull(CapturePolicy.normalize(null))
    }

    @Test
    fun `empty string normalizes to null`() {
        assertNull(CapturePolicy.normalize(""))
    }

    // ── Long text: NO silent truncation ──

    @Test
    fun `256 chars survive intact`() {
        val s = "a".repeat(256)
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `1024 chars survive intact`() {
        val s = "a".repeat(1024)
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `4096 chars survive intact`() {
        val s = "a".repeat(4096)
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `16384 chars survive intact`() {
        val s = "a".repeat(16384)
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `65536 chars survive intact`() {
        val s = "a".repeat(65536)
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `over-20000 no longer truncated`() {
        // v0.4.4 silently capped at 20_000. v0.4.5 must preserve.
        val s = "x".repeat(30_000)
        assertEquals(s, CapturePolicy.normalize(s))
    }

    // ── Unicode ──

    @Test
    fun `traditional Chinese preserved`() {
        assertEquals("你好世界", CapturePolicy.normalize("你好世界"))
    }

    @Test
    fun `simplified Chinese preserved`() {
        assertEquals("你好世界", CapturePolicy.normalize("你好世界"))
    }

    @Test
    fun `Japanese preserved`() {
        assertEquals("こんにちは世界", CapturePolicy.normalize("こんにちは世界"))
    }

    @Test
    fun `Korean preserved`() {
        assertEquals("안녕하세요", CapturePolicy.normalize("안녕하세요"))
    }

    @Test
    fun `Arabic preserved`() {
        assertEquals("مرحبا بالعالم", CapturePolicy.normalize("مرحبا بالعالم"))
    }

    @Test
    fun `RTL control characters preserved`() {
        val s = "\u202Ehello\u202C"
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `emoji preserved`() {
        assertEquals("😀🎉", CapturePolicy.normalize("😀🎉"))
    }

    @Test
    fun `ZWJ emoji sequence preserved`() {
        val s = "👨‍👩‍👧‍👦"
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `variation selectors preserved`() {
        val s = "❤️"
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `combining characters preserved`() {
        val s = "e\u0301"
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `surrogate pairs preserved`() {
        val s = "𐍈"
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `zero-width characters preserved`() {
        val s = "a\u200Bb"
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `embedded null preserved`() {
        val s = "a\u0000b"
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `control characters preserved`() {
        val s = "a\u0001b\u001F"
        assertEquals(s, CapturePolicy.normalize(s))
    }

    @Test
    fun `repeated newlines preserved`() {
        assertEquals("a\n\n\nb", CapturePolicy.normalize("a\n\n\nb"))
    }

    @Test
    fun `markdown symbols preserved`() {
        assertEquals("# **bold** _italic_ `code`", CapturePolicy.normalize("# **bold** _italic_ `code`"))
    }

    @Test
    fun `quotes and backslashes preserved`() {
        assertEquals("\"quoted\" 'single' \\path\\to", CapturePolicy.normalize("\"quoted\" 'single' \\path\\to"))
    }

    @Test
    fun `JSON escape characters preserved`() {
        assertEquals("a\\nb\\tc", CapturePolicy.normalize("a\\nb\\tc"))
    }

    @Test
    fun `mixed scripts preserved`() {
        assertEquals("Hello 你好 こんにちは 안녕", CapturePolicy.normalize("Hello 你好 こんにちは 안녕"))
    }

    @Test
    fun `mojibake-like text preserved (no auto-fix)`() {
        val s = "Ã©Ã¨Ã "
        assertEquals(s, CapturePolicy.normalize(s))
    }

    // ── Existing behavior that must remain ──

    @Test
    fun `known package maps to its display name`() {
        assertEquals("LINE", CapturePolicy.nameFor("jp.naver.line.android"))
    }

    @Test
    fun `unknown package falls back to its raw name`() {
        assertEquals("com.example.dev", CapturePolicy.nameFor("com.example.dev"))
    }
}
