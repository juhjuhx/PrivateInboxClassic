package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the v0.3.1 self-test source policy. Pure logic, no Android classes. */
class SelfTestSourcePolicyTest {
    private val line = "jp.naver.line.android"

    @Test
    fun `self test source is a distinct non-messaging constant`() {
        assertEquals("tw.personal.privateinbox", CapturePolicy.SELF_TEST)
    }

    @Test
    fun `self test is allowed only when explicitly selected`() {
        assertTrue(CapturePolicy.allowed(CapturePolicy.SELF_TEST, setOf(CapturePolicy.SELF_TEST)))
        assertFalse(CapturePolicy.allowed(CapturePolicy.SELF_TEST, emptySet()))
    }

    @Test
    fun `self test does not open messaging sources`() {
        assertFalse(CapturePolicy.allowed(line, setOf(CapturePolicy.SELF_TEST)))
    }

    @Test
    fun `known apps list carries the isolated self test entry`() {
        val entry = CapturePolicy.knownApps.firstOrNull { it.second == CapturePolicy.SELF_TEST }
        assertTrue(entry != null && entry.first == "Private Inbox Self Test")
    }
}
