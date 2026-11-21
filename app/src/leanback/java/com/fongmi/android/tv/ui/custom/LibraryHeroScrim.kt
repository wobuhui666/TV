package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.fongmi.android.tv.R

class LibraryHeroScrim(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint()
    private val side = Paint()
    private val fade = Paint()
    private var surface = 0
    private var light = false
    private var collapse = 0f

    init {
        bindJetStreamTheme {
            light = LibraryPalette.isLight()
            setSurface(jetStreamColor(R.color.jetstream_background))
        }
    }

    fun setCollapse(value: Float) {
        collapse = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun setSurface(color: Int) {
        surface = color
        updateShader()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateShader()
    }

    private fun updateShader() {
        if (height == 0) return
        val colors = if (light) intArrayOf(0x28000000, Color.TRANSPARENT, withAlpha(surface, 204), surface)
            else intArrayOf(0x38000000, 0x08000000, withAlpha(surface, 102), surface)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), colors,
            floatArrayOf(0f, .25f, .72f, 1f), Shader.TileMode.CLAMP)
        side.shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
            if (light) intArrayOf(withAlpha(surface, 245), withAlpha(surface, 215), withAlpha(surface, 40), Color.TRANSPARENT)
            else intArrayOf(withAlpha(surface, 235), withAlpha(surface, 180), withAlpha(surface, 25), Color.TRANSPARENT),
            floatArrayOf(0f, .35f, .75f, 1f), Shader.TileMode.CLAMP)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), side)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        fade.color = surface
        fade.alpha = (collapse * 170).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fade)
    }
}
