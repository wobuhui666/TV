package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.fongmi.android.tv.R
import com.fongmi.android.tv.setting.ExperienceMode

/** Static miniature layouts: no image requests, bitmap buffers, or running animations. */
class LibraryModePreviewView(context: Context, private val mode: ExperienceMode) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scenery = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leftScrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomScrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screenClip = Path().apply { addRoundRect(1f, 1f, 319f, 179f, 12f, 12f, Path.Direction.CW) }
    private val previewTitle = context.getString(R.string.library_mode_preview_title)
    private val farMountain = Path().apply {
        moveTo(0f, 115f)
        cubicTo(55f, 104f, 95f, 24f, 154f, 53f)
        cubicTo(208f, 89f, 251f, 38f, 320f, 65f)
        lineTo(320f, 180f); lineTo(0f, 180f); close()
    }
    private val nearMountain = Path().apply {
        moveTo(0f, 144f)
        cubicTo(63f, 119f, 89f, 161f, 151f, 113f)
        cubicTo(220f, 58f, 261f, 104f, 320f, 99f)
        lineTo(320f, 180f); lineTo(0f, 180f); close()
    }
    private val playIcon = ContextCompat.getDrawable(context, R.drawable.msr_play_arrow)?.mutate()
    private var surface = Color.BLACK
    private var foreground = Color.WHITE
    private var secondary = Color.GRAY
    private var outline = Color.GRAY
    private var mountainFar = Color.GRAY
    private var mountainNear = Color.DKGRAY

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        paint.typeface = jetStreamTypeface(context)
        bindJetStreamTheme { updateColors() }
    }

    private fun updateColors() {
        surface = LibraryPalette.surface()
        foreground = LibraryPalette.primary()
        secondary = ColorUtils.blendARGB(surface, foreground, .4f)
        outline = ColorUtils.blendARGB(surface, foreground, .14f)
        // The same quiet artwork is used in all three modes; only their layout changes.
        val skyTop = if (LibraryPalette.isLight()) 0xFFE0E5EB.toInt() else 0xFF353E48.toInt()
        val skyBottom = if (LibraryPalette.isLight()) 0xFFF0F2F4.toInt() else 0xFF65727B.toInt()
        mountainFar = if (LibraryPalette.isLight()) 0xFFBBC5CC.toInt() else 0xFF454F57.toInt()
        mountainNear = if (LibraryPalette.isLight()) 0xFF9CA9B3.toInt() else 0xFF252E36.toInt()
        scenery.shader = LinearGradient(0f, 0f, 280f, 180f, skyTop, skyBottom, Shader.TileMode.CLAMP)
        leftScrim.shader = LinearGradient(0f, 0f, 220f, 0f,
            ColorUtils.setAlphaComponent(surface, 210), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        bottomScrim.shader = LinearGradient(0f, 95f, 0f, 180f,
            Color.TRANSPARENT, surface, Shader.TileMode.CLAMP)
        playIcon?.setTint(surface)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.scale(width / 320f, height / 180f)
        paint.style = Paint.Style.FILL
        paint.color = surface
        canvas.drawRoundRect(.5f, .5f, 319.5f, 179.5f, 12f, 12f, paint)
        val content = canvas.save()
        canvas.clipPath(screenClip)
        when (mode) {
            ExperienceMode.LIBRARY -> library(canvas)
            ExperienceMode.SIMPLE -> simple(canvas)
            ExperienceMode.CLASSIC -> classic(canvas)
        }
        canvas.restoreToCount(content)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = outline
        canvas.drawRoundRect(.5f, .5f, 319.5f, 179.5f, 12f, 12f, paint)
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(save)
    }

    private fun library(canvas: Canvas) {
        artwork(canvas, 1f, 1f, 318f, 103f, scrim = true)
        title(canvas, 18f, 52f)
        play(canvas, 247f, 69f, 54f)
        line(canvas, 18f, 88f, 156f, 2f, secondary)
        line(canvas, 18f, 94f, 112f, 2f, secondary)
        episodes(canvas, 111f, 35f)
        line(canvas, 18f, 160f, 35f, 3f, secondary)
        paint.color = outline
        repeat(6) { canvas.drawCircle(72f + it * 28f, 162f, 7f, paint) }
    }

    private fun simple(canvas: Canvas) {
        artwork(canvas, 1f, 1f, 318f, 119f, scrim = true)
        title(canvas, 18f, 68f)
        play(canvas, 247f, 86f, 54f)
        line(canvas, 18f, 105f, 166f, 2f, secondary)
        episodes(canvas, 130f, 33f)
    }

    private fun classic(canvas: Canvas) {
        artwork(canvas, 18f, 18f, 129f, 73f)
        play(canvas, 69f, 44f, 27f)
        paint.color = foreground
        paint.textSize = 14f
        canvas.drawText(previewTitle, 163f, 37f, paint)
        line(canvas, 163f, 48f, 108f, 3f, secondary)
        line(canvas, 163f, 60f, 88f, 3f, secondary)
        line(canvas, 163f, 78f, 138f, 2f, secondary)
        line(canvas, 18f, 110f, 42f, 3f, foreground)
        line(canvas, 77f, 110f, 38f, 3f, secondary)
        line(canvas, 18f, 120f, 42f, 1f, foreground)
        repeat(2) { row ->
            repeat(6) { index ->
                val x = 18f + index * 48f
                val y = 132f + row * 20f
                paint.color = if (row == 0 && index == 0) foreground else outline
                canvas.drawRoundRect(x, y, x + 42f, y + 15f, 4f, 4f, paint)
                line(canvas, x + 15f, y + 7f, 12f, 2f, if (row == 0 && index == 0) surface else secondary)
            }
        }
    }

    private fun title(canvas: Canvas, x: Float, y: Float) {
        paint.color = foreground
        paint.textSize = 19f
        canvas.drawText(previewTitle, x, y, paint)
        line(canvas, x, y + 10f, 40f, 3f, secondary)
        line(canvas, x + 49f, y + 10f, 28f, 3f, secondary)
        line(canvas, x + 87f, y + 10f, 34f, 3f, secondary)
    }

    private fun episodes(canvas: Canvas, y: Float, height: Float) {
        repeat(3) { index ->
            val x = 18f + index * 97f
            artwork(canvas, x, y, 90f, height)
            line(canvas, x, y + height + 6f, 52f, 2f, secondary)
        }
    }

    private fun artwork(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, scrim: Boolean = false) {
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.scale(width / 320f, height / 180f)
        canvas.clipRect(0f, 0f, 320f, 180f)
        canvas.drawRect(0f, 0f, 320f, 180f, scenery)
        paint.color = mountainFar
        canvas.drawPath(farMountain, paint)
        paint.color = mountainNear
        canvas.drawPath(nearMountain, paint)
        if (scrim) {
            canvas.drawRect(0f, 0f, 320f, 180f, leftScrim)
            canvas.drawRect(0f, 0f, 320f, 180f, bottomScrim)
        }
        canvas.restoreToCount(save)
    }

    private fun play(canvas: Canvas, x: Float, y: Float, width: Float) {
        paint.color = foreground
        canvas.drawRoundRect(x, y, x + width, y + 19f, 9.5f, 9.5f, paint)
        val iconX = if (width > 30f) x + 8f else x + 7f
        playIcon?.setBounds(iconX.toInt(), (y + 3f).toInt(), (iconX + 13f).toInt(), (y + 16f).toInt())
        playIcon?.draw(canvas)
        if (width > 30f) line(canvas, x + 27f, y + 8f, 17f, 3f, surface)
    }

    private fun line(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, color: Int) {
        paint.color = color
        canvas.drawRoundRect(x, y, x + width, y + height, height / 2f, height / 2f, paint)
    }
}
