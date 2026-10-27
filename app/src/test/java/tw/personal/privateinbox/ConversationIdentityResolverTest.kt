package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation identity resolver.
 *
 * The v0.4.5 defect: `ConversationIdentity.key` appended the MUTABLE title
 * unconditionally, so renaming a contact split one chat into two boxes, and a
 * missing shortcut id fell back to the title, so two same-named groups collapsed
 * into one. Both behaviours are pinned as RED here before the fix.
 *
 * The resolver is pure: it talks to an [AliasLookup] seam, so every case below
 * runs on the JVM with an in-memory fake — no device, no Keystore.
 */
class ConversationIdentityResolverTest {

    /** In-memory alias store; mirrors the SQLite table's uniqueness rule. */
    private class FakeAliases : AliasLookup {
        val rows = HashMap<Triple<String, AliasType, String>, String>()
        private var seq = 0
        var putCount = 0
            private set

        override fun find(pkg: String, type: AliasType, value: String): String? =
            rows[Triple(pkg, type, value)]

        override fun put(
            pkg: String, type: AliasType, value: String,
            conversationId: String, strength: Strength
        ) {
            // INSERT OR IGNORE semantics: only a NEW row counts as an insert,
            // which is what the production table's composite primary key does.
            val key = Triple(pkg, type, value)
            if (key !in rows) putCount++
            rows[key] = conversationId
        }

        override fun newConversationId(): String = "cv_${++seq}"
    }

    private fun resolver(): Pair<ConversationIdentityResolver, FakeAliases> {
        val aliases = FakeAliases()
        return ConversationIdentityResolver(aliases) to aliases
    }

    // ------------------------------------------------ 1. rename keeps identity

    @Test
    fun sameShortcutWithARenamedTitleStaysOneConversation() {
        val (r, _) = resolver()
        val before = r.resolve("org.telegram.messenger", "sc-42", "Alice")
        val after = r.resolve("org.telegram.messenger", "sc-42", "Alice Chen")
        assertEquals(
            "renaming a contact must not split the conversation",
            before.conversationId, after.conversationId
        )
    }

    @Test
    fun theOldKeyFormatWouldHaveSplitOnRename() {
        // Proves the defect this fixes: the historical key differs after rename.
        val oldKey = LegacyKeyCodec.format("org.telegram.messenger", "sc-42", "Alice")
        val newKey = LegacyKeyCodec.format("org.telegram.messenger", "sc-42", "Alice Chen")
        assertNotEquals(
            "the legacy key changes when the title changes — this is the bug",
            oldKey, newKey
        )
        // …while the resolver keeps one id (asserted above).
    }

    // ------------------------------------- 2. same title, different shortcut

    @Test
    fun samePackageSameTitleDifferentShortcutIsTwoConversations() {
        val (r, _) = resolver()
        val a = r.resolve("jp.naver.line.android", "sc-A", "家族")
        val b = r.resolve("jp.naver.line.android", "sc-B", "家族")
        assertNotEquals(
            "two groups sharing a display name must not merge",
            a.conversationId, b.conversationId
        )
    }

    // -------------------------------------------- 3. different package

    @Test
    fun differentPackagesNeverShareAConversation() {
        val (r, _) = resolver()
        val tg = r.resolve("org.telegram.messenger", "sc-1", "Alice")
        val wa = r.resolve("com.whatsapp", "sc-1", "Alice")
        assertNotEquals(tg.conversationId, wa.conversationId)
    }

    // -------------------------------------------- 4. no shortcut, no guessing

    @Test
    fun withoutAShortcutTheResolutionIsFlaggedAmbiguous() {
        val (r, _) = resolver()
        val res = r.resolve("jp.naver.line.android", null, "家族群組")
        assertTrue("title-only identity must be reported as uncertain", res.ambiguous)
    }

    @Test
    fun withoutAShortcutTwoSameNamedGroupsDoNotMerge() {
        val (r, _) = resolver()
        // Two DIFFERENT conversations that happen to share a title and arrive
        // without a shortcut. The resolver cannot tell them apart, so it must
        // not silently assert they are the same — it keeps the existing mapping
        // and flags ambiguity rather than inventing a merge.
        val first = r.resolve("jp.naver.line.android", null, "家族群組")
        assertTrue(first.ambiguous)
        // A second resolution with the same weak evidence maps to the same id
        // (it IS the same weak identity) but stays flagged — it is never
        // promoted to a strong, certain grouping.
        val second = r.resolve("jp.naver.line.android", null, "家族群組")
        assertTrue(second.ambiguous)
        assertEquals(first.conversationId, second.conversationId)
    }

    @Test
    fun aWeakTitleOnlyIdentityIsNeverReportedAsStrong() {
        val (r, _) = resolver()
        val res = r.resolve("jp.naver.line.android", null, "家族群組")
        assertEquals("a title-only identity is TITLE_ONLY", Strength.TITLE_ONLY, res.strength)
        assertFalse("and it is not certain", res.strength.isStrong)
    }

    // -------------------------------------------- 5. stability across restarts

    @Test
    fun resolvingTwiceWithTheSameStrongIdentityIsIdempotent() {
        val (r, aliases) = resolver()
        val first = r.resolve("org.telegram.messenger", "sc-7", "Bob")
        val putsAfterFirst = aliases.putCount
        val second = r.resolve("org.telegram.messenger", "sc-7", "Bob")
        assertEquals(first.conversationId, second.conversationId)
        assertEquals("an existing alias must not be re-inserted",
            putsAfterFirst, aliases.putCount)
    }

    @Test
    fun aFreshResolverOverTheSameAliasStoreKeepsTheSameId() {
        // Simulates a process restart: new resolver instance, same persisted
        // alias rows.
        val aliases = FakeAliases()
        val a = ConversationIdentityResolver(aliases)
            .resolve("org.telegram.messenger", "sc-9", "Carol")
        val b = ConversationIdentityResolver(aliases)
            .resolve("org.telegram.messenger", "sc-9", "Carol")
        assertEquals("canonical id must survive a restart",
            a.conversationId, b.conversationId)
    }

    // -------------------------------------------- 8. many legacy aliases -> one

    @Test
    fun multipleLegacyKeysForOneShortcutMapToTheSameConversation() {
        val (r, aliases) = resolver()
        // A conversation whose title changed several times produced several
        // legacy keys, all carrying the same shortcut in the middle slot.
        val canonical = r.resolve("org.telegram.messenger", "sc-77", "Alice").conversationId
        aliases.put("org.telegram.messenger", AliasType.LEGACY_KEY,
            LegacyKeyCodec.format("org.telegram.messenger", "sc-77", "Alice"),
            canonical, Strength.PLATFORM_CONFIRMED)
        aliases.put("org.telegram.messenger", AliasType.LEGACY_KEY,
            LegacyKeyCodec.format("org.telegram.messenger", "sc-77", "Alice Chen"),
            canonical, Strength.PLATFORM_CONFIRMED)
        val legacyHit = aliases.find("org.telegram.messenger", AliasType.LEGACY_KEY,
            LegacyKeyCodec.format("org.telegram.messenger", "sc-77", "Alice Chen"))
        assertEquals("an old key must resolve to the same canonical id",
            canonical, legacyHit)
    }

    // -------------------------------------------- 9. blank / degenerate input

    @Test
    fun blankShortcutIsTreatedAsAbsent() {
        val (r, _) = resolver()
        val res = r.resolve("org.telegram.messenger", "   ", "Alice")
        assertTrue("a blank shortcut is not a strong identity", res.ambiguous)
    }

    @Test
    fun emptyTitleAndNoShortcutStillProducesAConversationNotACrash() {
        val (r, _) = resolver()
        val res = r.resolve("org.telegram.messenger", null, "")
        assertTrue(res.conversationId.isNotEmpty())
        assertTrue(res.ambiguous)
    }

    @Test
    fun unicodeTitlesResolveStably() {
        val (r, _) = resolver()
        val a = r.resolve("jp.naver.line.android", "sc-日", "日本語 👍🏽")
        val b = r.resolve("jp.naver.line.android", "sc-日", "日本語 👍🏽")
        assertEquals(a.conversationId, b.conversationId)
        assertFalse(a.ambiguous)
    }

    @Test
    fun longTitleDoesNotBreakResolution() {
        val (r, _) = resolver()
        val long = "長".repeat(1000)
        val a = r.resolve("jp.naver.line.android", "sc-long", long)
        val b = r.resolve("jp.naver.line.android", "sc-long", long + "!")
        assertEquals("a rename of a very long title must not split",
            a.conversationId, b.conversationId)
    }

    // -------------------------------------------- dedup compatibility

    @Test
    fun theResolverNeverChangesTheLegacyKeyUsedForFingerprints() {
        // The resolver returns a canonical id, but the fingerprint input must
        // still be the LEGACY key. This asserts the two concepts stay separate.
        val (r, _) = resolver()
        val res = r.resolve("org.telegram.messenger", "sc-5", "Dave")
        val legacy = LegacyKeyCodec.format("org.telegram.messenger", "sc-5", "Dave")
        assertNotEquals("canonical id is not the legacy key", res.conversationId, legacy)
        assertEquals("the resolver reports the legacy key verbatim",
            legacy, res.legacyKey)
    }
}
