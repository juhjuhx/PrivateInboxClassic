package tw.personal.privateinbox

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Save a resized notification-provided preview, never fetch remote URLs or chat databases. */
object ImageCapture {
    private const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    private const val MAX_SIDE = 1280
    private const val MAX_STORED_BYTES = 700 * 1024
    // TASK 5: an expensive media-URI read must not hold the notification callback open.
    // Cap the blocking read; on overrun give up (text still stores).
    private const val READ_TIMEOUT_MS = 2_000L

    @Suppress("DEPRECATION")
    fun fromNotification(context: Context, extras: Bundle): ByteArray? {
        val raw = if (Build.VERSION.SDK_INT >= 33)
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        else extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        raw?.let { return asPreview(it) }
        if (Build.VERSION.SDK_INT >= 31) {
            val icon = if (Build.VERSION.SDK_INT >= 33)
                extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
            else extras.getParcelable(Notification.EXTRA_PICTURE_ICON) as? Icon
            if (icon != null) {
                val drawable = runCatching { icon.loadDrawable(context) }.getOrNull()
                if (drawable != null) {
                    val w = drawable.intrinsicWidth.coerceIn(1, MAX_SIDE)
                    val h = drawable.intrinsicHeight.coerceIn(1, MAX_SIDE)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    return asPreview(bmp)
                }
            }
        }
        return null
    }

    /** Read the raw bytes a notification media URI exposes, without decoding.
     *  Must run while the provider still grants the listener read access, so it is
     *  called from the notification callback. Decoding happens later, off the
     *  callback thread, in [toPreview].
     *  TASK 5: the read is deadline-bounded ([READ_TIMEOUT_MS]) so a slow/stalled
     *  content provider can never hold the notification callback open; on overrun
     *  we give up (return null) and the text row is still stored. */
    fun readBytes(context: Context, uri: Uri?): ByteArray? {
        if (uri == null || uri.scheme != "content") return null
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    if (System.currentTimeMillis() > deadline) return@use null
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_INPUT_BYTES) return@use null
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        }.getOrNull()
    }

    /** Decode raw bytes into a bounded JPEG preview. CPU bound: run off the UI thread. */
    fun toPreview(data: ByteArray?, declaredMime: String? = null): ByteArray? {
        if (data == null || data.isEmpty()) return null
        return runCatching {
            // Reject bytes that are not actually an image before decoding.
            if (!looksLikeImage(data, declaredMime)) return@runCatching null
            // Decode with downsampling to cap bitmap allocations.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sampling = 1
            while (bounds.outWidth / sampling > 2048 || bounds.outHeight / sampling > 2048) sampling *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sampling }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return@runCatching null
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(data))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> Unit
            }
            val oriented = if (matrix.isIdentity) bmp else
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            asPreview(oriented)
        }.getOrNull()
    }

    /** Provider must already grant the notification listener read permission.
     * [mediaType] is the MIME the source notification declared (an image mime type, or null). */
    fun fromUri(context: Context, uri: Uri?, mediaType: String? = null): ByteArray? {
        val data = readBytes(context, uri) ?: return null
        return toPreview(data, mediaType)
    }

    /** Reject payloads that are not images: magic-byte sniff plus the declared MIME. */
    internal fun looksLikeImage(data: ByteArray, declaredMime: String?): Boolean {
        if (data.size < 4) return false
        val sig = IntArray(4) { data[it].toInt() and 0xff }
        val magic = when {
            // JPEG FF D8 FF
            sig[0] == 0xFF && sig[1] == 0xD8 && sig[2] == 0xFF -> true
            // PNG 89 50 4E 47
            sig[0] == 0x89 && sig[1] == 0x50 && sig[2] == 0x4E && sig[3] == 0x47 -> true
            // GIF47 49 / GIF47 56 (GIF8)
            sig[0] == 0x47 && sig[1] == 0x49 && sig[2] == 0x46 -> true
            // WebP 52 49 46 46 ... 57 45 42 50
            sig[0] == 0x52 && sig[1] == 0x49 && sig[2] == 0x46 && sig[3] == 0x46 ->
                data.size > 11 && data[8].toInt() and 0xff == 0x57 &&
                data[9].toInt() and 0xff == 0x45 &&
                data[10].toInt() and 0xff == 0x42 &&
                data[11].toInt() and 0xff == 0x50
            // HEIC/ISOBMFF (ftyp at offset 4)
            data.size > 11 && data[4].toInt() and 0xff == 0x66 &&
                data[5].toInt() and 0xff == 0x74 &&
                data[6].toInt() and 0xff == 0x79 &&
                data[7].toInt() and 0xff == 0x70 -> true
            else -> false
        }
        if (magic) return true
        // No recognizable image magic: only accept if the declared MIME explicitly
        // says image/* (some HEIC/AVIF variants are not sniffed above).
        return declaredMime?.startsWith("image/", true) == true
    }

    internal fun asPreview(bitmap: Bitmap): ByteArray? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val ratio = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return runCatching {
            val resized = if (bitmap.width == w && bitmap.height == h) bitmap
                          else Bitmap.createScaledBitmap(bitmap, w, h, true)
            val output = ByteArrayOutputStream()
            for (quality in intArrayOf(82, 67, 52, 38)) {
                output.reset()
                resized.compress(Bitmap.CompressFormat.JPEG, quality, output)
                if (output.size() <= MAX_STORED_BYTES) return@runCatching output.toByteArray()
            }
            null
        }.getOrNull()
    }

    fun decodedForDisplay(data: ByteArray, maxSide: Int = 1600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
