package tw.personal.privateinbox

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView

/** Lightweight zoomable photo surface: pinch-to-zoom, double-tap zoom, and panning.
 *  Pure framework views — no image-loading or gesture libraries are added.
 *  The bitmap is already decrypted and downsampled by the caller. */
class PhotoZoomView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setContentDescription("放大的照片預覽")
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (image.scaleX * detector.scaleFactor).coerceIn(1f, 5f)
                // Keep the gesture focus point stationary while scaling.
                val px = newScale * detector.focusX - image.scaleX * detector.focusX
                val py = newScale * detector.focusY - image.scaleY * detector.focusY
                image.scaleX = newScale
                image.scaleY = newScale
                image.translationX += px
                image.translationY += py
                clampTranslation()
                return true
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
        })

    private val doubleTapListener = object : GestureDetector.OnDoubleTapListener {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (image.scaleX > 1.2f) resetZoom() else zoomTo(2.5f)
            return true
        }
        override fun onDoubleTapEvent(e: MotionEvent): Boolean = false
    }

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private companion object {
        const val TAP_TIMEOUT_MS = 220L
        const val TAP_SLOP_PX = 24f
    }

    init {
        addView(image, LayoutParams(-1, -1))
        tapDetector.setOnDoubleTapListener(doubleTapListener)
    }

    fun setBitmap(bitmap: Bitmap?) {
        image.setImageBitmap(bitmap)
        image.scaleX = 1f
        image.scaleY = 1f
        image.translationX = 0f
        image.translationY = 0f
    }

    fun hasImage(): Boolean = image.drawable != null

    private fun zoomTo(scale: Float) {
        image.animate().scaleX(scale).scaleY(scale).translationX(0f).translationY(0f)
            .setDuration(180).start()
    }

    private fun resetZoom() {
        image.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(180).start()
    }

    /** Keep the zoomed image from being dragged completely off-screen. */
    private fun clampTranslation() {
        val w = image.measuredWidth
        val h = image.measuredHeight
        if (w <= 0 || h <= 0) return
        // FIT_CENTER content size before scaling.
        val fit = minOf(w.toFloat() / image.width.coerceAtLeast(1), h.toFloat() / image.height.coerceAtLeast(1)).coerceAtMost(1f)
        val contentW = w * fit * image.scaleX
        val contentH = h * fit * image.scaleY
        val maxX = ((contentW - w) / 2f).coerceAtLeast(0f)
        val maxY = ((contentH - h) / 2f).coerceAtLeast(0f)
        image.translationX = image.translationX.coerceIn(-maxX, maxX)
        image.translationY = image.translationY.coerceIn(-maxY, maxY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    image.translationX += event.x - lastX
                    image.translationY += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    clampTranslation()
                }
            }
            MotionEvent.ACTION_UP -> {
                clampTranslation()
                // Accessibility: a tap that did not drag must still raise a click event.
                if (event.eventTime - downTime < TAP_TIMEOUT_MS &&
                    kotlin.math.abs(event.x - downX) < TAP_SLOP_PX &&
                    kotlin.math.abs(event.y - downY) < TAP_SLOP_PX
                ) {
                    performClick()
                }
            }
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
