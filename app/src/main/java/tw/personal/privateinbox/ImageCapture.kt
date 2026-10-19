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

    /** Provider must already grant the notification listener read permission. */
    fun fromUri(context: Context, uri: Uri?): ByteArray? {
        if (uri == null || uri.scheme != "content") return null
        return runCatching {
            val data = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_INPUT_BYTES) return@use null
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            } ?: return@runCatching null
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

    private fun asPreview(bitmap: Bitmap): ByteArray? {
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
