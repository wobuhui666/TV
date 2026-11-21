package com.fongmi.android.tv.ui.custom

import android.graphics.Bitmap
import android.widget.ImageView
import com.fongmi.android.tv.utils.GaussianBlurTransformation
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/** The expensive blur happens once per artwork; scrolling only updates two view properties. */
class LibraryHeroEffects(private val original: ImageView, private val blurred: ImageView) {
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var released = false
    private var prepared: Bitmap? = null
    private var source: Bitmap? = null
    private var scrollY = 0
    private var heroHeight = 1

    init {
        blurred.alpha = 0f
        blurred.scaleType = original.scaleType
    }

    fun setBitmap(bitmap: Bitmap) {
        if (released || bitmap.isRecycled || source === bitmap) return
        source = bitmap
        val ticket = ++generation
        prepared = null
        blurred.setImageDrawable(null)
        blurred.alpha = 0f
        // Copy while Glide still owns a valid source, then give only our small bitmap to the worker.
        val scale = min(1f, 220f / bitmap.width.coerceAtLeast(1))
        val downsampled = Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        val owned = downsampled.copy(Bitmap.Config.ARGB_8888, true) ?: return
        if (downsampled !== bitmap) downsampled.recycle()
        worker.execute {
            val ready = runCatching { GaussianBlurTransformation.blurBitmap(owned) }.isSuccess
            original.post {
                if (!ready || released || ticket != generation) {
                    owned.recycle()
                    return@post
                }
                prepared = owned
                blurred.setImageBitmap(owned)
                onScroll(scrollY, heroHeight)
            }
        }
    }

    fun onScroll(scrollY: Int, heroHeight: Int) {
        this.scrollY = scrollY.coerceAtLeast(0)
        this.heroHeight = heroHeight.coerceAtLeast(1)
        if (released) return
        val motion = LibraryMotion.enabled(original)
        val offset = if (motion) min(this.scrollY * .5f, this.heroHeight * .5f) else 0f
        original.translationY = offset
        blurred.translationY = offset
        val progress = (this.scrollY / (this.heroHeight * .72f)).coerceIn(0f, 1f)
        // Smoothstep avoids an abrupt blur edge as the poster first starts leaving the viewport.
        blurred.alpha = if (prepared == null) 0f else progress * progress * (3f - 2f * progress)
    }

    fun release() {
        released = true
        generation++
        source = null
        worker.shutdownNow()
        blurred.setImageDrawable(null)
        // A displayed bitmap may still be referenced by RenderThread's previous frame.
        prepared = null
    }
}
