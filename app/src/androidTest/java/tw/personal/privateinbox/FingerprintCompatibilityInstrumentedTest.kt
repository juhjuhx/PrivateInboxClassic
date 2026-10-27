package tw.personal.privateinbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R1-C: fingerprint compatibility proven through REAL `save()` outcomes.
 *
 * The weak version of this test called `CryptoStore.fingerprint()` twice with the
 * same arguments and asserted equality — which proves only that a pure function
 * is pure. It says nothing about whether an ALREADY-STORED row is recognised on
 * re-delivery after the v0.4.6 change.
 *
 * Here every case goes through the production write path and asserts the actual
 * `NEW` / `DUPLICATE` result:
 *
 *   - same shortcut, different title (the rename case)
 *   - different shortcut, same title (the collision case)
 *   - source timestamp missing (the discriminator fallback)
 *   - two identical bodies in the same millisecond
 *   - multiple legacy aliases for one shortcut
 *
 * Nothing here rewrites a stored fingerprint; the point is that it does not need
 * to.
 */
@RunWith(AndroidJUnit4::class)
class FingerprintCompatibilityInstrumentedTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pkg = "jp.naver.line.android"

    @Before
    fun setUp() {
        DestructiveTestAssumption.requireIsolated(ctx)
        TestDataIsolation.clearDatabase(ctx)
    }

    @After
    fun tearDown() {
        TestDataIsolation.tryClearDatabase(ctx)
    }

    private fun save(
        title: String, sender: String, body: String, ts: Long,
        discriminator: String, shortcut: String?
    ): InboxDatabase.SaveResult {
        val legacyKey = LegacyKeyCodec.format(pkg, shortcut, title)
        val db = InboxDatabase(ctx)
        return try {
            db.save(
                packageName = pkg, receivedAt = System.currentTimeMillis(),
                sourceTimestamp = ts, title = title, sender = sender, body = body,
                image = null, conversationKey = legacyKey,
                messageKind = SourceRegistry.MessageKind.GROUP_MESSAGE.name,
                messageDiscriminator = discriminator
            )
        } finally { db.close() }
    }

    private fun rowCount(): Int {
        val db = InboxDatabase(ctx)
        return try { db.recent(1000).size } finally { db.close() }
    }

    // ---------------------------------------------------------------- cases

    @Test
    fun sameShortcutSameTitleIsDuplicateOnRedelivery() {
        val a = save("家族", "Alice", "hello", 1_700_000_000_000L, "c:Alice:hello:i0", "sc-1")
        assertEquals("first delivery is NEW", InboxDatabase.SaveResult.NEW, a)

        val b = save("家族", "Alice", "hello", 1_700_000_000_000L, "c:Alice:hello:i0", "sc-1")
        assertEquals("the identical re-delivery is DUPLICATE",
            InboxDatabase.SaveResult.DUPLICATE, b)
        assertEquals("no extra row", 1, rowCount())
    }

    @Test
    fun sameShortcutDifferentTitleChangesTheFingerprint_documentedLimitation() {
        // This is the honest statement of the v0.4.5 fingerprint design: the key
        // embeds the title, so a rename changes the fingerprint input. The
        // canonical conversation still stays ONE (asserted separately); the ROW
        // is treated as new because the legacy key it is hashed with is different.
        val a = save("家族", "Alice", "hello", 1_700_000_000_000L, "c:Alice:hello:i0", "sc-1")
        assertEquals(InboxDatabase.SaveResult.NEW, a)

        val b = save("家族（新）", "Alice", "hello", 1_700_000_000_000L, "c:Alice:hello:i0", "sc-1")
        assertEquals(
            "a renamed title produces a different fingerprint input by design",
            InboxDatabase.SaveResult.NEW, b
        )
        assertEquals("so the row count grows — documented, not hidden", 2, rowCount())

        // But the CANONICAL conversation is still one, which is the v0.4.6 fix.
        resolve(pkg, "sc-1", "家族")
        resolve(pkg, "sc-1", "家族（新）")
        val store = ConversationStore(ctx)
        try {
            assertEquals(
                "the rename must not create a second canonical conversation",
                1, store.conversationCount(pkg)
            )
        } finally { store.close() }
    }

    @Test
    fun differentShortcutSameTitleIsNotDuplicate() {
        val a = save("家族", "Alice", "hello", 1_700_000_000_000L, "c:Alice:hello:i0", "sc-A")
        val b = save("家族", "Alice", "hello", 1_700_000_000_000L, "c:Alice:hello:i0", "sc-B")
        assertEquals(InboxDatabase.SaveResult.NEW, a)
        assertEquals("two different groups are two different messages",
            InboxDatabase.SaveResult.NEW, b)
        assertEquals(2, rowCount())

        // Resolve both, as the listener does for each delivered notification.
        val ra = resolve(pkg, "sc-A", "家族")
        val rb = resolve(pkg, "sc-B", "家族")
        assertNotEquals("two shortcuts are two conversations", ra.conversationId, rb.conversationId)

        val store = ConversationStore(ctx)
        try {
            assertEquals("and two canonical conversations", 2, store.conversationCount(pkg))
        } finally { store.close() }
    }

    @Test
    fun missingSourceTimestampUsesTheDiscriminatorAndStillDedups() {
        // ts = 0 -> the fingerprint uses the discriminator. Two deliveries with
        // the same discriminator must dedup; a different discriminator must not.
        val a = save("家族", "Alice", "hello", 0L, "c:Alice:hello:i0", "sc-1")
        assertEquals(InboxDatabase.SaveResult.NEW, a)

        val b = save("家族", "Alice", "hello", 0L, "c:Alice:hello:i0", "sc-1")
        assertEquals("the same discriminator dedups even without a timestamp",
            InboxDatabase.SaveResult.DUPLICATE, b)

        val c = save("家族", "Alice", "hello", 0L, "c:Alice:hello:i1", "sc-1")
        assertEquals("a different discriminator is a different message",
            InboxDatabase.SaveResult.NEW, c)
        assertEquals(2, rowCount())
    }

    @Test
    fun twoIdenticalBodiesInTheSameMillisecondAreBothStored() {
        // The classic dedup trap: same sender, same text, same millisecond.
        val a = save("家族", "Alice", "ok", 1_700_000_000_000L, "c:Alice:ok:i0", "sc-1")
        val b = save("家族", "Alice", "ok", 1_700_000_000_000L, "c:Alice:ok:i1", "sc-1")
        assertEquals(InboxDatabase.SaveResult.NEW, a)
        assertEquals("the discriminator keeps the second one apart",
            InboxDatabase.SaveResult.NEW, b)
        assertEquals(2, rowCount())

        // Re-delivering each must dedup against its own row.
        assertEquals(InboxDatabase.SaveResult.DUPLICATE,
            save("家族", "Alice", "ok", 1_700_000_000_000L, "c:Alice:ok:i0", "sc-1"))
        assertEquals(InboxDatabase.SaveResult.DUPLICATE,
            save("家族", "Alice", "ok", 1_700_000_000_000L, "c:Alice:ok:i1", "sc-1"))
        assertEquals("no rows added by the re-deliveries", 2, rowCount())
    }

    @Test
    fun multipleLegacyAliasesForOneShortcutStillDedupAgainstTheirOwnRows() {
        // Two titles for the same shortcut: two legacy keys, two rows, one
        // canonical conversation. Each key dedups against ITSELF.
        assertEquals(InboxDatabase.SaveResult.NEW,
            save("家族", "Alice", "m1", 1_700_000_000_001L, "c:Alice:m1:i0", "sc-1"))
        assertEquals(InboxDatabase.SaveResult.NEW,
            save("家族（新）", "Alice", "m2", 1_700_000_000_002L, "c:Alice:m2:i1", "sc-1"))

        assertEquals(InboxDatabase.SaveResult.DUPLICATE,
            save("家族", "Alice", "m1", 1_700_000_000_001L, "c:Alice:m1:i0", "sc-1"))
        assertEquals(InboxDatabase.SaveResult.DUPLICATE,
            save("家族（新）", "Alice", "m2", 1_700_000_000_002L, "c:Alice:m2:i1", "sc-1"))
        assertEquals("still two rows", 2, rowCount())

        resolve(pkg, "sc-1", "家族")
        resolve(pkg, "sc-1", "家族（新）")
        val store = ConversationStore(ctx)
        try {
            val cid = store.find(pkg, AliasType.SHORTCUT, "sc-1")
            assertEquals("both legacy keys converge on one canonical id",
                1, store.conversationCount(pkg))
            // And the alias rows for that conversation include both legacy keys.
            val legacy = store.aliasesOf(cid!!).filter { it.first == AliasType.LEGACY_KEY.name }
            assertEquals(2, legacy.size)
        } finally { store.close() }
    }

    @Test
    fun theStoredFingerprintBytesAreExactlyTheHistoricalFormula() {
        // Prove the row that got written carries the fingerprint computed by the
        // UNCHANGED historical formula, not some new derivation.
        val title = "家族"
        val sender = "Alice"
        val body = "hello"
        val ts = 1_700_000_000_000L
        val disc = "c:Alice:hello:i0"
        val legacyKey = LegacyKeyCodec.format(pkg, "sc-1", title)

        assertEquals(InboxDatabase.SaveResult.NEW,
            save(title, sender, body, ts, disc, "sc-1"))

        val expected = CryptoStore.fingerprint(
            pkg, ts, legacyKey, "$sender\u0000$body", disc
        )
        val db = InboxDatabase(ctx)
        val actual = try {
            db.readableDatabase.rawQuery(
                "SELECT fingerprint FROM messages ORDER BY id DESC LIMIT 1", null
            ).use { c -> c.moveToFirst(); c.getBlob(0) }
        } finally { db.close() }

        assertEquals(
            "the stored fingerprint must be the historical formula's output",
            expected.joinToString("") { "%02x".format(it) },
            actual.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun theCanonicalIdNeverAppearsInTheStoredConversationKey() {
        // The legacy key stays exactly as v0.4.5 wrote it; the canonical id lives
        // only in the side tables. If `cv_` ever appeared in conversation_key the
        // DHC V1 id would change and dedup would break.
        save("家族", "Alice", "hello", 1_700_000_000_000L, "c:Alice:hello:i0", "sc-1")
        resolve(pkg, "sc-1", "家族")

        val db = InboxDatabase(ctx)
        val key = try {
            db.readableDatabase.rawQuery(
                "SELECT conversation_key FROM messages ORDER BY id DESC LIMIT 1", null
            ).use { c -> c.moveToFirst(); c.getString(0) }
        } finally { db.close() }

        assertEquals(
            "the stored key is the legacy format, verbatim",
            LegacyKeyCodec.format(pkg, "sc-1", "家族"), key
        )
        assertNotEquals("and it is definitely not a canonical id", true, key.startsWith("cv_"))
    }

    private fun resolve(pkg: String, shortcutId: String?, title: String): Resolution {
        val store = ConversationStore(ctx)
        return try {
            ConversationIdentityResolver(store).resolve(pkg, shortcutId, title)
        } finally { store.close() }
    }
}
