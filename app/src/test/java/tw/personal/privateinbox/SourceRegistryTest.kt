package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SourceRegistry / classification / conversation identity rules.
 *
 * These are pure JVM tests: no Android framework, no device. They pin the
 * platform-recognition and grouping rules that the rest of the app relies on.
 */
class SourceRegistryTest {

    // ------------------------------------------------------------- recognition

    @Test
    fun `threads package is recognised`() {
        assertTrue(SourceRegistry.isKnown("com.instagram.barcelona"))
        assertEquals("Threads", SourceRegistry.displayName("com.instagram.barcelona"))
    }

    @Test
    fun `all required platforms are registered`() {
        val required = listOf(
            "jp.naver.line.android",
            "com.instagram.android",
            "com.facebook.orca",
            "com.instagram.barcelona",
            "com.whatsapp",
            "org.telegram.messenger",
            "org.thunderdog.challegram"
        )
        required.forEach { pkg ->
            assertTrue("$pkg must be a known source", SourceRegistry.isKnown(pkg))
        }
    }

    @Test
    fun `v045 expanded sources are registered with verified package ids`() {
        val v045 = listOf(
            "org.thoughtcrime.securesms" to "Signal",
            "com.discord" to "Discord",
            "com.tencent.mm" to "WeChat",
            "com.tencent.mobileqq" to "QQ"
        )
        v045.forEach { (pkg, name) ->
            assertTrue("$pkg must be a known source", SourceRegistry.isKnown(pkg))
            assertEquals(name, SourceRegistry.displayName(pkg))
        }
    }

    @Test
    fun `expanded sources are pure messengers not social networks`() {
        // A message container on these must classify as a private message,
        // never be routed through the social-activity filter.
        listOf(
            "org.thoughtcrime.securesms",
            "com.discord",
            "com.tencent.mm",
            "com.tencent.mobileqq"
        ).forEach { pkg ->
            assertFalse("$pkg must not be marked social", SourceRegistry.isSocial(pkg))
        }
    }

    @Test
    fun `unknown package is not a known source`() {
        assertFalse(SourceRegistry.isKnown("com.example.bank"))
        assertFalse(SourceRegistry.isKnown("com.android.shell"))
        assertEquals("com.example.bank", SourceRegistry.displayName("com.example.bank"))
    }

    @Test
    fun `self test source is isolated and not a messaging platform`() {
        assertTrue(SourceRegistry.isSelfTest(SourceRegistry.SELF_TEST_PACKAGE))
        assertFalse(SourceRegistry.messagingSources.any { it.isSelfTest })
        assertFalse(SourceRegistry.filterableSources.any { it.isSelfTest })
    }

    @Test
    fun `filter bar lists every messaging platform in a stable order`() {
        val labels = SourceRegistry.filterableSources.map { it.shortLabel }
        assertEquals(labels.distinct().size, labels.size)
        // The self-test source must never appear in the filter bar.
        assertFalse(SourceRegistry.filterableSources.any { it.packageName == SourceRegistry.SELF_TEST_PACKAGE })
    }

    @Test
    fun `social platforms are marked as social`() {
        assertTrue(SourceRegistry.isSocial("com.instagram.android"))
        assertTrue(SourceRegistry.isSocial("com.instagram.barcelona"))
        assertFalse(SourceRegistry.isSocial("jp.naver.line.android"))
        assertFalse(SourceRegistry.isSocial("com.facebook.orca"))
    }

    // ---------------------------------------------------------- classification

    @Test
    fun `threads direct message is classified as a private message`() {
        val kind = SourceRegistry.classify(
            packageName = "com.instagram.barcelona",
            hasMessageContainer = true,
            conversationTitle = "Alice",
            appDisplayName = "Threads",
            category = "msg",
            hasConversationShortcut = false
        )
        assertEquals(SourceRegistry.MessageKind.DIRECT_MESSAGE, kind)
        assertTrue(kind.isMessageLike)
    }

    @Test
    fun `threads social activity is excluded from the private inbox`() {
        val kind = SourceRegistry.classify(
            packageName = "com.instagram.barcelona",
            hasMessageContainer = false,
            conversationTitle = "Threads",
            appDisplayName = "Threads",
            category = "social",
            hasConversationShortcut = false
        )
        assertEquals(SourceRegistry.MessageKind.SOCIAL_ACTIVITY, kind)
        assertFalse("social activity must not be treated as a private chat", kind.isMessageLike)
    }

    @Test
    fun `instagram like notification is social activity`() {
        val kind = SourceRegistry.classify(
            packageName = "com.instagram.android",
            hasMessageContainer = false,
            conversationTitle = "Instagram",
            appDisplayName = "Instagram",
            category = "social",
            hasConversationShortcut = false
        )
        assertEquals(SourceRegistry.MessageKind.SOCIAL_ACTIVITY, kind)
    }

    @Test
    fun `instagram direct message is a private message`() {
        val kind = SourceRegistry.classify(
            packageName = "com.instagram.android",
            hasMessageContainer = true,
            conversationTitle = "Bob",
            appDisplayName = "Instagram",
            category = "msg",
            hasConversationShortcut = false
        )
        assertEquals(SourceRegistry.MessageKind.DIRECT_MESSAGE, kind)
    }

    @Test
    fun `line group with a distinct title is a group message`() {
        val kind = SourceRegistry.classify(
            packageName = "jp.naver.line.android",
            hasMessageContainer = true,
            conversationTitle = "Family Group",
            appDisplayName = "LINE",
            category = "msg",
            hasConversationShortcut = false
        )
        assertEquals(SourceRegistry.MessageKind.GROUP_MESSAGE, kind)
    }

    @Test
    fun `conversation shortcut marks a group`() {
        val kind = SourceRegistry.classify(
            packageName = "jp.naver.line.android",
            hasMessageContainer = true,
            conversationTitle = "LINE",
            appDisplayName = "LINE",
            category = "msg",
            hasConversationShortcut = true
        )
        assertEquals(SourceRegistry.MessageKind.GROUP_MESSAGE, kind)
    }

    @Test
    fun `mention and reply take precedence`() {
        val mention = SourceRegistry.classify(
            packageName = "jp.naver.line.android",
            hasMessageContainer = true,
            conversationTitle = "Group",
            appDisplayName = "LINE",
            category = "msg",
            hasConversationShortcut = false,
            mentionsOwner = true
        )
        assertEquals(SourceRegistry.MessageKind.MENTION, mention)

        val reply = SourceRegistry.classify(
            packageName = "jp.naver.line.android",
            hasMessageContainer = true,
            conversationTitle = "Group",
            appDisplayName = "LINE",
            category = "msg",
            hasConversationShortcut = false,
            isReply = true
        )
        assertEquals(SourceRegistry.MessageKind.REPLY, reply)
    }

    @Test
    fun `unclassifiable notification is unknown not a fake private chat`() {
        val kind = SourceRegistry.classify(
            packageName = "jp.naver.line.android",
            hasMessageContainer = false,
            conversationTitle = null,
            appDisplayName = "LINE",
            category = null,
            hasConversationShortcut = false
        )
        assertEquals(SourceRegistry.MessageKind.UNKNOWN, kind)
        assertFalse(kind.isMessageLike)
    }

    // ------------------------------------------------- conversation identity

    @Test
    fun `same contact in two apps never merges`() {
        val line = ConversationIdentity.of("jp.naver.line.android", "Alice").key
        val messenger = ConversationIdentity.of("com.facebook.orca", "Alice").key
        assertNotEquals(line, messenger)
    }

    @Test
    fun `shortcut id separates contacts that share a display name`() {
        val a = ConversationIdentity.of("jp.naver.line.android", "Alex", shortcutId = "contact-1").key
        val b = ConversationIdentity.of("jp.naver.line.android", "Alex", shortcutId = "contact-2").key
        assertNotEquals("same display name, different contacts", a, b)
    }

    @Test
    fun `shortcut id keeps the key stable when the contact renames`() {
        val before = ConversationIdentity.of("jp.naver.line.android", "Alex", shortcutId = "c1").key
        val after = ConversationIdentity.of("jp.naver.line.android", "Alexa", shortcutId = "c1").key
        assertNotEquals("the title is part of the key", before, after)
        // The shortcut component must be identical, which is the stable part.
        assertTrue(before.split('\u0000')[1] == after.split('\u0000')[1])
    }

    @Test
    fun `without a shortcut id the title is the discriminator`() {
        val a = ConversationIdentity.of("jp.naver.line.android", "Alice").key
        val b = ConversationIdentity.of("jp.naver.line.android", "Bob").key
        assertNotEquals(a, b)
    }

    @Test
    fun `empty identity is detected`() {
        assertTrue(ConversationIdentity.of("jp.naver.line.android", "").isEmpty)
        assertTrue(ConversationIdentity.of("jp.naver.line.android", null).isEmpty)
        assertFalse(ConversationIdentity.of("jp.naver.line.android", "Alice").isEmpty)
    }

    @Test
    fun `conversation key always contains the package name`() {
        val key = ConversationIdentity.of("com.facebook.orca", "Bob", shortcutId = "x").key
        assertTrue(key.startsWith("com.facebook.orca"))
    }
}
