package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The encryption key state model.
 *
 * The distinction these tests pin down is the one that decides whether the app
 * destroys user data:
 *  - KEY_TEMPORARILY_UNAVAILABLE — the device has not been unlocked yet.
 *    Retrying later works. Nothing may be deleted.
 *  - KEY_INVALIDATED / RECOVERY_REQUIRED — the key is permanently gone. The
 *    ciphertext can never be read again. The user must be told, and the rows
 *    must survive until the user explicitly chooses to remove them.
 */
@RunWith(AndroidJUnit4::class)
class KeyStateInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aUsableKeyReportsAvailable() {
        val state = CryptoStore.keyState()
        assertEquals(
            "a fresh Keystore key must report KEY_AVAILABLE",
            CryptoStore.KeyState.KEY_AVAILABLE, state
        )
        assertTrue(
            "an available key must not be flagged unrecoverable",
            !state.isUnrecoverable
        )
    }

    @Test
    fun aValidPayloadRoundTripsAndReportsAvailable() {
        val plain = "合成測試內容".toByteArray(Charsets.UTF_8)
        val sealed = CryptoStore.seal("jp.naver.line.android", plain)
        val res = CryptoStore.openWithState("jp.naver.line.android", sealed)
        assertEquals(CryptoStore.KeyState.KEY_AVAILABLE, res.state)
        assertNotNull("the plaintext must be returned", res.plaintext)
        assertEquals(String(plain, Charsets.UTF_8), String(res.plaintext!!, Charsets.UTF_8))
    }

    @Test
    fun aTamperedPayloadIsADecryptFailureNotAKeyProblem() {
        val sealed = CryptoStore.seal("jp.naver.line.android", "測試".toByteArray(Charsets.UTF_8))
        // Flip a byte in the ciphertext body.
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        val res = CryptoStore.openWithState("jp.naver.line.android", tampered)
        assertEquals(
            "a tampered payload must be a decrypt failure, not a key failure",
            CryptoStore.KeyState.DECRYPT_FAILED, res.state
        )
        assertTrue("a decrypt failure must not be flagged unrecoverable", !res.state.isUnrecoverable)
    }

    @Test
    fun aTruncatedPayloadIsRejected() {
        val res = CryptoStore.openWithState("jp.naver.line.android", ByteArray(4))
        assertEquals(CryptoStore.KeyState.DECRYPT_FAILED, res.state)
    }

    @Test
    fun theWrongAadIsADecryptFailure() {
        // The AAD binds the payload to its source package: the same ciphertext
        // must not decrypt under a different package.
        val sealed = CryptoStore.seal("jp.naver.line.android", "測試".toByteArray(Charsets.UTF_8))
        val res = CryptoStore.openWithState("com.facebook.orca", sealed)
        assertEquals(CryptoStore.KeyState.DECRYPT_FAILED, res.state)
    }

    @Test
    fun aCorruptRowDoesNotHideTheOtherRows() {
        val db = InboxDatabase(ctx)
        try {
            db.clear()
            db.save("org.telegram.messenger", 1_700_000_000_000L, 0,
                "Alice", "Alice", "good body", null)
            db.save("org.telegram.messenger", 1_700_000_001_000L, 0,
                "Bob", "Bob", "another body", null)
            val res = db.recentWithState(10)
            assertEquals(2, res.entries.size)
            assertEquals(CryptoStore.KeyState.KEY_AVAILABLE, res.keyState)
            assertEquals(0, res.unreadableRows)
        } finally {
            db.close()
        }
    }

    @Test
    fun purgeRemovesNothingWhenEveryRowIsReadable() {
        val db = InboxDatabase(ctx)
        try {
            db.clear()
            db.save("org.telegram.messenger", 1_700_000_000_000L, 0,
                "Alice", "Alice", "keep me", null)
            val removed = db.purgeUndecryptableRows()
            assertEquals("readable rows must never be purged", 0, removed)
            assertEquals("the row must still be there", 1, db.recent(10).size)
        } finally {
            db.close()
        }
    }
}
