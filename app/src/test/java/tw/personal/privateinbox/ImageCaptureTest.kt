package tw.personal.privateinbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the image-magic validation gate in ImageCapture. */
class ImageCaptureTest {

    @Test
    fun `jpeg magic passes`() {
        val data = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(8)
        assertTrue(ImageCapture.looksLikeImage(data, null))
    }

    @Test
    fun `png magic passes`() {
        val data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(ImageCapture.looksLikeImage(data, null))
    }

    @Test
    fun `webp riff container passes`() {
        val data = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        assertTrue(ImageCapture.looksLikeImage(data, null))
    }

    @Test
    fun `heic isobmff ftyp passes`() {
        val data = byteArrayOf(0, 0, 0, 0x20) + "ftypmif1".toByteArray() + ByteArray(4)
        assertTrue(ImageCapture.looksLikeImage(data, null))
    }

    @Test
    fun `arbitrary text is rejected without mime`() {
        val data = "hello this is not an image".toByteArray()
        assertFalse(ImageCapture.looksLikeImage(data, null))
    }

    @Test
    fun `declared image mime rescues unknown magic`() {
        val data = "some unknown container".toByteArray()
        assertTrue(ImageCapture.looksLikeImage(data, "image/avif"))
    }

    @Test
    fun `declared non image mime does not rescue`() {
        val data = "some unknown container".toByteArray()
        assertFalse(ImageCapture.looksLikeImage(data, "text/plain"))
    }

    @Test
    fun `tiny payloads are rejected`() {
        assertFalse(ImageCapture.looksLikeImage(byteArrayOf(0xFF.toByte()), null))
    }
}
