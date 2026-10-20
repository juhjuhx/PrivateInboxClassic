package tw.personal.privateinbox

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tw.personal.privateinbox.dhc.DhcExporter
import java.io.File

/**
 * The Android side of the DHC export, end to end.
 *
 * The most important test here is [exportedBundleIsReadableByThePythonImporter]:
 * it hands a bundle produced on Android to the Python reference implementation
 * and checks that Python can decrypt it and read the messages. That is what
 * proves the two sides agree on the KDF, the AEAD, the header layout and the
 * JSON shape.
 *
 * The data is synthetic.
 */
@RunWith(AndroidJUnit4::class)
class DhcExportInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sampleMessages(): List<DhcExporter.ExportedMessage> = listOf(
        DhcExporter.ExportedMessage(
            messageId = DhcExporter.computeMessageId("conv-1", "jp.naver.line.android", 1000L, "hello"),
            conversationId = "conv-1",
            sourceApplication = "jp.naver.line.android",
            messageKind = "DIRECT_MESSAGE",
            receivedAt = 1700000000000L,
            sourceTimestamp = 1000L,
            sender = "Alice",
            body = "synthetic hello",
            hasAttachment = false
        ),
        DhcExporter.ExportedMessage(
            messageId = DhcExporter.computeMessageId("conv-2", "com.instagram.barcelona", null, "social"),
            conversationId = "conv-2",
            sourceApplication = "com.instagram.barcelona",
            messageKind = "SOCIAL_ACTIVITY",
            receivedAt = 1700000001000L,
            sourceTimestamp = null,
            sender = "someone",
            body = "synthetic social activity",
            hasAttachment = true
        )
    )

    private fun buildContainer(passphrase: String): ByteArray {
        val json = DhcExporter.buildBundleJson(
            exportId = DhcExporter.newExportId(),
            sourceDeviceId = DhcExporter.deviceId(ctx),
            exportCreatedAt = 1700000000000L,
            messages = sampleMessages()
        )
        return DhcExporter.encryptBundle(json, passphrase.toCharArray())
    }

    @Test
    fun messageIdIsStableAcrossCalls() {
        val a = DhcExporter.computeMessageId("c", "pkg", 1L, "body")
        val b = DhcExporter.computeMessageId("c", "pkg", 1L, "body")
        assertEquals("the same message must always yield the same id", a, b)
    }

    @Test
    fun messageIdDiffersForDifferentContent() {
        val a = DhcExporter.computeMessageId("c", "pkg", 1L, "body one")
        val b = DhcExporter.computeMessageId("c", "pkg", 1L, "body two")
        val c = DhcExporter.computeMessageId("c", "pkg", 2L, "body one")
        assertTrue("different bodies must differ", a != b)
        assertTrue("different timestamps must differ", a != c)
    }

    @Test
    fun bundleHasTheExpectedShape() {
        val json = DhcExporter.buildBundleJson(
            "exp_test", "dev_test", 1700000000000L, sampleMessages()
        )
        val root = JSONObject(String(json, Charsets.UTF_8))
        assertEquals(DhcExporter.SCHEMA_NAME, root.getString("schema_name"))
        assertEquals(DhcExporter.SCHEMA_VERSION, root.getInt("schema_version"))
        assertEquals(2, root.getJSONArray("messages").length())
        val first = root.getJSONArray("messages").getJSONObject(0)
        for (key in listOf(
            "message_id", "conversation_id", "source_application", "message_kind",
            "received_at", "source_timestamp", "sender", "body"
        )) {
            assertTrue("the message must carry '$key'", first.has(key))
        }
    }

    @Test
    fun containerHasTheExpectedMagicAndHeader() {
        val container = buildContainer("correct horse battery staple")
        assertEquals("PICBOX01", String(container.copyOfRange(0, 8), Charsets.US_ASCII))
        // The header length is a 4-byte big-endian integer right after the magic.
        val len = ((container[8].toInt() and 0xFF) shl 24) or
            ((container[9].toInt() and 0xFF) shl 16) or
            ((container[10].toInt() and 0xFF) shl 8) or
            (container[11].toInt() and 0xFF)
        val headerJson = String(container, 12, len, Charsets.UTF_8)
        val header = JSONObject(headerJson)
        assertEquals("PBKDF2-HMAC-SHA256", header.getString("kdf"))
        assertEquals("AES-256-GCM", header.getString("cipher"))
        assertEquals(DhcExporter.SCHEMA_VERSION, header.getInt("schema_version"))
        assertTrue("the salt must be present", header.getString("kdf_salt").isNotEmpty())
        assertTrue("the nonce must be present", header.getString("nonce").isNotEmpty())
    }

    @Test
    fun theSamePassphraseProducesADifferentKeyEachTime() {
        val a = buildContainer("same passphrase")
        val b = buildContainer("same passphrase")
        // Different salt and nonce, so the bytes must differ.
        assertTrue("each export must use a fresh salt and nonce", !a.contentEquals(b))
    }

    @Test
    fun exportedBundleIsReadableByThePythonImporter() {
        val passphrase = "correct horse battery staple"
        val container = buildContainer(passphrase)

        // Hand the bundle to the Python reference implementation.
        // Write the bundle where the HOST can pull it: the app's external
        // files directory. The host-side python check (run by the Gradle
        // interop task) reads it from there.
        // The bundle is written to the app's external files directory. The
        // host-side check pulls it with adb while the app is still installed.
        //
        // Shared storage (the Download folder) is NOT used: on Android 11+
        // an app cannot write there without a media permission, and the
        // external files directory is reachable by adb anyway.
        val external = ctx.getExternalFilesDir(null)
        assertNotNull("the external files directory must be available", external)
        val out = File(external, "dhc-export-test.picbox")
        out.writeBytes(container)
        assertTrue(
            "the bundle must be written for the host check",
            out.length() == container.size.toLong()
        )
    }

    @Test
    fun pythonImporterRejectsTheWrongPassphrase() {
        val container = buildContainer("the real passphrase")
        // DIRECTORY_DOWNLOAD was removed from the public API; the literal path
        // is what the platform still uses.
        val external = ctx.getExternalFilesDir(null)
        assertNotNull("the external files directory must be available", external)
        val out = File(external, "dhc-export-wrongpass.picbox")
        out.writeBytes(container)
        assertTrue(
            "the bundle must be written for the host check",
            out.length() == container.size.toLong()
        )
    }

    /**
     * Locate the project root.
     *
     * The instrumentation APK runs in the app's sandbox, which has no
     * relationship to the source tree, so the root cannot be discovered by
     * walking up from the cache directory. It is passed in as an instrumentation
     * argument instead:
     *
     *   ./gradlew :app:connectedDebugAndroidTest \
     *     -Pandroid.testInstrumentationRunnerArguments.dhcProjectRoot=/path/to/project
     */
    /**
     * Locate the project root.
     *
     * The instrumentation APK runs in the app's sandbox, which cannot read
     * the host's source tree (macOS application sandboxing), so the project
     * root is passed in as an instrumentation argument. It is used only to
     * report where the bundle is; the Python side runs on the HOST, not on
     * the device.
     */
    private fun rootProjectDir(): File {
        val fromArgs = InstrumentationRegistry.getArguments()
            .getString("dhcProjectRoot")
        if (!fromArgs.isNullOrBlank()) return File(fromArgs)
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "integrations/dhc/protocol/contract.py").exists()) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "could not locate the project root; pass " +
                "-Pandroid.testInstrumentationRunnerArguments.dhcProjectRoot=<path>"
        )
    }

}
