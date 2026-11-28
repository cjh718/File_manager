
package com.folio.files

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val imageMatrixValues = FloatArray(9)
    private val transformMatrix = Matrix()
    private val startPoint = PointF()
    private val lastPoint = PointF()

    private var currentScale = 1f
    private val minimumScale = 1f
    private val maximumScale = 5f

    private var isDragging = false
    private var isSwipeCandidate = false

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    private val swipeThreshold = 72f * resources.displayMetrics.density

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newScale = (currentScale * scaleFactor)
                    .coerceIn(minimumScale, maximumScale)

                val appliedScale = newScale / currentScale
                currentScale = newScale

                transformMatrix.postScale(
                    appliedScale,
                    appliedScale,
                    detector.focusX,
                    detector.focusY
                )

                imageMatrix = transformMatrix
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun setImageBitmap(bitmap: android.graphics.Bitmap?) {
        super.setImageBitmap(bitmap)
        resetZoom()
    }

    private fun resetZoom() {
        currentScale = 1f
        transformMatrix.reset()
        imageMatrix = transformMatrix
        post { fitImageToView() }
    }

    private fun fitImageToView() {
        val drawable = drawable ?: return
        if (width <= 0 || height <= 0) return

        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()

        if (drawableWidth <= 0f || drawableHeight <= 0f) return

        val scale = min(
            width.toFloat() / drawableWidth,
            height.toFloat() / drawableHeight
        )

        val dx = (width - drawableWidth * scale) / 2f
        val dy = (height - drawableHeight * scale) / 2f

        transformMatrix.reset()
        transformMatrix.setScale(scale, scale)
        transformMatrix.postTranslate(dx, dy)

        imageMatrix = transformMatrix
        currentScale = 1f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastPoint.set(event.x, event.y)
                startPoint.set(event.x, event.y)
                isDragging = true
                isSwipeCandidate = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isDragging = false
                isSwipeCandidate = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress &&
                    isDragging &&
                    currentScale > minimumScale
                ) {
                    val dx = event.x - lastPoint.x
                    val dy = event.y - lastPoint.y

                    transformMatrix.postTranslate(dx, dy)
                    imageMatrix = transformMatrix
                }

                lastPoint.set(event.x, event.y)
            }

            MotionEvent.ACTION_UP -> {
                isDragging = false
                if (isSwipeCandidate && currentScale == minimumScale) {
                    val dx = event.x - startPoint.x
                    val dy = event.y - startPoint.y
                    if (kotlin.math.abs(dx) >= swipeThreshold &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.25f
                    ) {
                        if (dx < 0f) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                    }
                }
                isSwipeCandidate = false
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isSwipeCandidate = false
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
