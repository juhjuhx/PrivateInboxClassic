package tw.personal.privateinbox.dhc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The cross-language interoperability check.
 *
 * The direction this file proves is the one that is easy to get wrong: a
 * bundle produced by the PYTHON reference tool is accepted by the ANDROID
 * side. The other direction (Android exports, Python imports) is covered by
 * the host-side script and by the existing instrumentation test.
 *
 * The fixture is produced by the host, because the app sandbox cannot read
 * the host's source tree. Its path is passed in as an instrumentation
 * argument.
 */
@RunWith(AndroidJUnit4::class)
class DhcPythonFixtureInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    companion object {
        /** The fixtures the host stages in /sdcard/Download/dhc-fixtures. */
        val FIXTURE_NAMES = listOf("python-export.picbox", "python-export-v99.picbox")
    }

    /**
     * The fixture directory.
     *
     * The fixtures are staged in the APP's own external files directory. The
     * shared Download folder is not usable: reading it needs a storage
     * permission, and this app deliberately has none. The app's external
     * directory is always readable by the app, and the shell can write it.
     */
    /**
     * The fixture directory inside the app's own storage.
     *
     * The fixtures are read from the HOST through `adb exec-out cat`, which
     * streams the bytes straight into the app's cache. That avoids the shared
     * storage entirely: reading /sdcard needs a storage permission, and this
     * app deliberately has none.
     */
    private fun fixtureDir(): File {
        val dir = File(ctx.cacheDir, "dhc-fixtures")
        if (dir.isDirectory && dir.list()?.isEmpty() != false) return dir
        dir.mkdirs()
        val ui = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().uiAutomation
        for (name in FIXTURE_NAMES) {
            runCatching {
                val fd = ui.executeShellCommand(
                    "cat /sdcard/Download/dhc-fixtures/$name"
                )
                val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
                    .use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    File(dir, name).writeBytes(bytes)
                }
            }
        }
        return dir
    }

    private fun fixture(name: String): File {
        val file = File(fixtureDir(), name)
        assertTrue("the fixture $name must exist at ${file.absolutePath}", file.exists())
        return file
    }

    @Test
    fun aBundleProducedByPythonIsAccepted() {
        val passphrase = "the python side made this"
        val container = fixture("python-export.picbox").readBytes()

        val plaintext = DhcExporter.decryptBundle(container, passphrase.toCharArray())
        assertNotNull("the python bundle must be accepted", plaintext)
        val body = plaintext!!

        val bundle = JSONObject(String(body, Charsets.UTF_8))
        assertEquals(
            "the schema must be the agreed one",
            "PRIVATE_INBOX_EXPORT_V1", bundle.getString("schema_name")
        )
        assertEquals("the schema version must match", 1, bundle.getInt("schema_version"))
        val messages = bundle.getJSONArray("messages")
        assertTrue("the fixture must carry messages", messages.length() > 0)

        // The message ids must be the ones the Python side computed, which is
        // what makes an import idempotent across the two sides.
        val first = messages.getJSONObject(0)
        val expectedId = DhcExporter.computeMessageId(
            conversationId = first.getString("conversation_id"),
            sourceApplication = first.getString("source_application"),
            sourceTimestamp = if (first.isNull("source_timestamp")) null
                              else first.getLong("source_timestamp"),
            body = first.getString("body")
        )
        assertEquals(
            "the message id must be identical on both sides",
            expectedId, first.getString("message_id")
        )
    }

    @Test
    fun aBundleWithTheWrongPassphraseIsRejected() {
        val container = fixture("python-export.picbox").readBytes()
        val plaintext = DhcExporter.decryptBundle(container, "not the passphrase".toCharArray())
        assertNull("the wrong passphrase must be rejected", plaintext)
    }

    @Test
    fun aTamperedHeaderIsRejected() {
        val container = fixture("python-export.picbox").readBytes().copyOf()
        // Flip a byte in the header, which is covered by the AAD.
        val headerStart = "PICBOX01".length + 4
        container[headerStart + 5] = (container[headerStart + 5].toInt() xor 0x01).toByte()
        val plaintext = DhcExporter.decryptBundle(container, "the python side made this".toCharArray())
        assertNull("a tampered header must be rejected", plaintext)
    }

    @Test
    fun aTamperedBodyIsRejected() {
        val container = fixture("python-export.picbox").readBytes().copyOf()
        // Flip a byte in the ciphertext.
        val last = container.size - 1
        container[last] = (container[last].toInt() xor 0x01).toByte()
        val plaintext = DhcExporter.decryptBundle(container, "the python side made this".toCharArray())
        assertNull("a tampered body must be rejected", plaintext)
    }

    @Test
    fun anUnknownSchemaVersionIsRejected() {
        val container = fixture("python-export-v99.picbox").readBytes()
        val plaintext = DhcExporter.decryptBundle(container, "the python side made this".toCharArray())
        assertNull("an unknown schema version must be rejected", plaintext)
    }

    @Test
    fun anEmptyFileIsRejected() {
        val plaintext = DhcExporter.decryptBundle(ByteArray(0), "any".toCharArray())
        assertNull("an empty file must be rejected", plaintext)
    }

    @Test
    fun aTruncatedFileIsRejected() {
        val container = fixture("python-export.picbox").readBytes()
        val plaintext = DhcExporter.decryptBundle(
            container.copyOf(container.size / 2), "the python side made this".toCharArray()
        )
        assertNull("a truncated file must be rejected", plaintext)
    }
}
