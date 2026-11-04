package tw.personal.privateinbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.4.7 — listener connection-state honesty.
 *
 * The OriginOS field report showed `PERMISSION_GRANTED_NOT_CONNECTED` alongside
 * intermittent disconnects. That state is ambiguous: it can mean "the process
 * was killed", "the system has not rebound yet", or "the user revoked access".
 * These tests pin down that each of those cases is reported distinctly and that
 * a rebind REQUEST is never mistaken for a live connection.
 *
 * These run on the production package and never touch user data: they only read
 * and write the diagnostics SharedPreferences.
 */
@RunWith(AndroidJUnit4::class)
class V047ListenerLifecycleInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        CaptureDiagnostics.clear(ctx)
    }

    @After
    fun tearDown() {
        CaptureDiagnostics.clear(ctx)
    }

    /** A grant is not a connection: with no onListenerConnected() the state must
     *  be PERMISSION_GRANTED_NOT_CONNECTED (or PERMISSION_DENIED if the device
     *  genuinely has no access), never CONNECTED. */
    @Test
    fun permissionGrantedWithoutConnectionIsNotReportedAsConnected() {
        val status = CaptureDiagnostics.resolveStatus(ctx)
        assertNotEquals(CaptureDiagnostics.STATE_CONNECTED, status)
        if (CaptureDiagnostics.listenerAccessGranted(ctx)) {
            assertEquals(CaptureDiagnostics.STATE_PERMISSION_GRANTED_NOT_CONNECTED, status)
        } else {
            assertEquals(CaptureDiagnostics.STATE_PERMISSION_DENIED, status)
        }
    }

    /** markConnected() is the only thing that earns CONNECTED. */
    @Test
    fun markConnectedEarnsConnectedState() {
        CaptureDiagnostics.markConnected(ctx)
        assertTrue(CaptureDiagnostics.isListenerConnectedInProcess())
        assertEquals(CaptureDiagnostics.STATE_CONNECTED, CaptureDiagnostics.resolveStatus(ctx))
    }

    /** A disconnect must drop the live flag but leave the persisted evidence. */
    @Test
    fun disconnectClearsLiveFlagButKeepsEvidence() {
        CaptureDiagnostics.markConnected(ctx)
        CaptureDiagnostics.markDisconnected(ctx, "test")
        assertFalse(CaptureDiagnostics.isListenerConnectedInProcess())
        // Fresh evidence is still inside the TTL window.
        assertTrue(CaptureDiagnostics.hasConnectedEvidence(ctx))
    }

    /** onDestroy without a clean disconnect must still clear the live flag. */
    @Test
    fun destroyedListenerClearsLiveFlag() {
        CaptureDiagnostics.markConnected(ctx)
        CaptureDiagnostics.markDestroyed(ctx)
        assertFalse(CaptureDiagnostics.isListenerConnectedInProcess())
    }

    /** Each connect gets a distinct instance id, so two binds are distinguishable. */
    @Test
    fun listenerInstanceIdsAreDistinct() {
        CaptureDiagnostics.markConnected(ctx)
        val first = CaptureDiagnostics.listenerInstance(ctx)
        CaptureDiagnostics.markDisconnected(ctx, "test")
        CaptureDiagnostics.markConnected(ctx)
        val second = CaptureDiagnostics.listenerInstance(ctx)
        assertTrue("first instance id must be recorded", !first.isNullOrEmpty())
        assertTrue("second instance id must be recorded", !second.isNullOrEmpty())
        assertNotEquals("each bind must get its own instance id", first, second)
    }

    /** The process session id is stable within one process. */
    @Test
    fun processSessionIdIsStableWithinProcess() {
        val a = CaptureDiagnostics.processSession(ctx)
        val b = CaptureDiagnostics.processSession(ctx)
        assertEquals(a, b)
        assertTrue("session id must be non-empty", a.isNotEmpty())
        assertNotEquals("unknown", a)
    }

    /** A user-initiated rebind is recorded as a REQUEST, never as a connection. */
    @Test
    fun rebindRequestDoesNotFabricateConnection() {
        CaptureDiagnostics.markDisconnected(ctx, "test")
        val before = CaptureDiagnostics.isListenerConnectedInProcess()
        CaptureDiagnostics.rebind(ctx, userInitiated = true)
        // Whether or not the system honours it, the in-process flag must not flip
        // to true without an actual onListenerConnected() callback.
        assertEquals(before, CaptureDiagnostics.isListenerConnectedInProcess())
        assertFalse(CaptureDiagnostics.isListenerConnectedInProcess())
    }

    /** Automatic rebinds are rate-limited so a disconnected listener cannot storm. */
    @Test
    fun automaticRebindRespectsCooldown() {
        CaptureDiagnostics.rebind(ctx, userInitiated = true)
        val accepted = CaptureDiagnostics.rebind(ctx, userInitiated = false)
        assertFalse("second automatic rebind inside the cooldown must be refused", accepted)
        val events = CaptureDiagnostics.recent(ctx, 50).map { it.code }
        assertTrue("cooldown must be recorded", events.contains(CaptureDiagnostics.REBIND_COOLDOWN))
    }

    /** Export carries the real version and the new session fields. */
    @Test
    fun exportCarriesRealVersionAndSessionFields() {
        val json = CaptureDiagnostics.export(ctx)
        val obj = org.json.JSONObject(json)
        assertEquals(3, obj.getInt("schema_version"))
        assertEquals(BuildConfig.VERSION_NAME, obj.getString("version"))
        assertEquals(BuildConfig.VERSION_CODE, obj.getInt("version_code"))
        val d = obj.getJSONObject("diagnostics")
        assertTrue(d.has("process_session_id"))
        assertTrue(d.has("process_start_count"))
        assertTrue(d.has("listener_connected_in_process"))
        assertTrue(d.has("connection_evidence"))
        assertTrue(d.has("rebind_count"))
        assertTrue(d.has("connection_evidence_ttl_ms"))
        // The old hard-coded value must be gone.
        assertNotEquals("0.4.0-alpha", obj.getString("version"))
    }

    /** Stale evidence past the TTL must not be treated as a current connection. */
    @Test
    fun staleConnectionEvidenceIsNotTrusted() {
        // Write a CONNECTED flag dated well beyond the TTL, bypassing markConnected.
        val prefs = ctx.getSharedPreferences("capture_diag", Context.MODE_PRIVATE)
        val old = System.currentTimeMillis() - (60L * 60L * 1000L) // 1 hour ago
        prefs.edit()
            .putBoolean("connected_flag", true)
            .putLong("connected_ts", old)
            .apply()
        assertFalse(
            "evidence older than the TTL must not count as connected",
            CaptureDiagnostics.hasConnectedEvidence(ctx)
        )
        val events = CaptureDiagnostics.recent(ctx, 50).map { it.code }
        assertTrue(
            "staleness must be recorded",
            events.contains(CaptureDiagnostics.CONNECTION_EVIDENCE_STALE)
        )
    }

    /** Diagnostics must never carry a message body, title or sender. */
    @Test
    fun exportContainsNoPrivateContent() {
        CaptureDiagnostics.note(ctx, "jp.naver.line.android", "parse", "PARSE_SUCCESS")
        val json = CaptureDiagnostics.export(ctx)
        // The only allowed package identifier is the package name itself.
        assertFalse("export must not contain a conversation title", json.contains("title"))
        assertFalse("export must not contain a message body", json.contains("body"))
        assertFalse("export must not contain a sender", json.contains("sender"))
    }
}
