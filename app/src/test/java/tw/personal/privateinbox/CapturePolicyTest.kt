package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure source-policy logic. No Android classes touched. */
class CapturePolicyTest {
    private val line = "jp.naver.line.android"
    private val ig = "com.instagram.android"

    @Test
    fun `unknown source is never allowed without opt-in`() {
        assertFalse(CapturePolicy.allowed(line, emptySet()))
        assertFalse(CapturePolicy.allowed(ig, emptySet()))
    }

    @Test
    fun `explicitly selected known source is allowed`() {
        assertTrue(CapturePolicy.allowed(line, setOf(line)))
    }

    @Test
    fun `selected source does not open other packages`() {
        assertFalse(CapturePolicy.allowed(ig, setOf(line)))
    }

    @Test
    fun `package not on the known allowlist is refused even when selected`() {
        assertFalse(CapturePolicy.allowed("com.example.bank", setOf("com.example.bank")))
    }

    @Test
    fun `whitespace and line feeds are collapsed to single spaces`() {
        assertEquals("你好 世界", CapturePolicy.normalize("  你好   世界 \r\n"))
    }

    @Test
    fun `blank input normalizes to null`() {
        assertNull(CapturePolicy.normalize("   "))
        assertNull(CapturePolicy.normalize(null))
    }

    @Test
    fun `overlong bodies are capped`() {
        val normalized = CapturePolicy.normalize("x".repeat(30_000))
        assertEquals(20_000, normalized!!.length)
    }

    @Test
    fun `known package maps to its display name`() {
        assertEquals("LINE", CapturePolicy.nameFor(line))
        assertEquals("Instagram", CapturePolicy.nameFor(ig))
    }

    @Test
    fun `unknown package falls back to its raw name`() {
        assertEquals("com.example.dev", CapturePolicy.nameFor("com.example.dev"))
    }

    @Test
    fun `every known app is a messaging or chat package`() {
        val all = CapturePolicy.knownApps.map { it.second }
        assertTrue(all.size >= 5)
        assertTrue(all.contains(line))
        assertTrue(all.contains(ig))
        assertTrue(all.contains("com.whatsapp"))
        assertTrue(all.contains("com.facebook.orca"))
        assertTrue(all.contains("org.telegram.messenger"))
    }
}
