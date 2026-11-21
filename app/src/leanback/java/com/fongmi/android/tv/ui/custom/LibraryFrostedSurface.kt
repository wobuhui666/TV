package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.fongmi.android.tv.utils.GaussianBlurTransformation
import com.fongmi.android.tv.R
import java.util.concurrent.Executors

/** Samples only the content behind the fixed bar, excluding the bar itself. */
class LibraryFrostedSurface @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()
    private val worker = Executors.newSingleThreadExecutor()
    private var snapshot: Bitmap? = null
    private var input: View? = null
    private var progress = 0f
    private var busy = false
    private var generation = 0
    private var requested = 0
    private var lastCapture = 0L
    private var overlayColor = 0
    private var foregroundColor = 0
    private val refresh = Runnable { capture() }

    init {
        bindJetStreamTheme {
            overlayColor = jetStreamColor(R.color.jetstream_overlay_surface_light)
            foregroundColor = jetStreamColor(R.color.jetstream_on_surface)
            generation++
            requested++
            snapshot = null
            removeCallbacks(refresh)
            if (input != null && progress > 0f && isAttachedToWindow && !worker.isShutdown) post(refresh)
            invalidate()
        }
    }

    fun update(content: View, value: Float) {
        input = content
        requested++
        progress = value.coerceIn(0f, 1f)
        invalidate()
        if (progress == 0f) {
            removeCallbacks(refresh)
            return
        }
        removeCallbacks(refresh)
        postDelayed(refresh, (100L - (SystemClock.uptimeMillis() - lastCapture)).coerceAtLeast(0L))
    }

    private fun capture() {
        val content = input ?: return
        if (busy || progress == 0f || width == 0 || height == 0 || !isAttachedToWindow || worker.isShutdown) return
        val bitmap = Bitmap.createBitmap((width / 4).coerceAtLeast(1), ((height + LibraryViews.dp(24)) / 4).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(.25f, .25f)
            content.draw(canvas)
            canvas.setBitmap(null)
        } catch (_: RuntimeException) {
            bitmap.recycle()
            return
        }
        busy = true
        lastCapture = SystemClock.uptimeMillis()
        val ticket = generation
        val captureRequest = requested
        worker.execute {
            val ready = runCatching { GaussianBlurTransformation.blurBitmap(bitmap) }.isSuccess
            post {
                busy = false
                if (!ready || ticket != generation || !isAttachedToWindow) {
                    bitmap.recycle()
                    if (ticket != generation && isAttachedToWindow && input != null && progress > 0f && !worker.isShutdown) {
                        removeCallbacks(refresh)
                        postDelayed(refresh, 100L)
                    }
                    return@post
                }
                snapshot = bitmap
                invalidate()
                if (progress > 0f && requested != captureRequest) {
                    removeCallbacks(refresh)
                    postDelayed(refresh, 100L)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (progress == 0f) return
        snapshot?.let {
            destination.set(0, 0, width, height + LibraryViews.dp(24))
            paint.alpha = (255 * progress).toInt()
            canvas.drawBitmap(it, null, destination, paint)
        }
        paint.color = overlayColor
        paint.alpha = ((if (snapshot == null) 245 else Color.alpha(overlayColor)) * progress).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = foregroundColor
        paint.alpha = (10 * progress).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        generation++
        removeCallbacks(refresh)
        input = null
        snapshot = null
        worker.shutdownNow()
        super.onDetachedFromWindow()
    }
}
