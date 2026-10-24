package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.leanback.widget.HorizontalGridView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.ui.theme.RexTheme
import kotlin.math.abs

/** One focus group owns the hero, its extracted background, and the preview rail. */
class JetStreamRexSectionCard @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private val surface = GradientDrawable().apply { cornerRadius = jetStreamDp(22) }
    private var artworkColor: Int? = null
    var surfaceColor: Int = 0
        private set

    init {
        orientation = VERTICAL
        background = surface
        clipToOutline = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        bindJetStreamTheme { updateColors() }
    }

    fun setArtworkColor(color: Int?) {
        artworkColor = color
        updateColors()
    }

    private fun updateColors() {
        val base = jetStreamColor(R.color.jetstream_surface_container)
        surfaceColor = artworkColor?.let { ColorUtils.blendARGB(it, base, if (RexTheme.isLight) .84f else .82f) } ?: base
        surface.setColor(surfaceColor)
        findViewById<JetStreamRexSectionScrim>(R.id.scrim)?.setSurfaceColor(surfaceColor)
        foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                // Stroke center is inset by 1dp; its outer radius matches the 22dp clip.
                cornerRadius = jetStreamDp(21)
                setStroke(jetStreamDpInt(2), jetStreamColor(R.color.jetstream_primary))
            })
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Leanback otherwise replaces this with its much smaller default outline.
        outlineProvider = ViewOutlineProvider.BACKGROUND
        updateColors()
    }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
        isActivated = true
    }

    override fun clearChildFocus(child: View) {
        super.clearChildFocus(child)
        isActivated = hasFocus()
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val hero = findViewById<View>(R.id.hero)
        val rail = findViewById<HorizontalGridView>(R.id.posters) ?: return super.focusSearch(focused, direction)
        if (direction == View.FOCUS_DOWN && focused === hero) {
            return rail.findViewHolderForAdapterPosition(rail.selectedPosition.coerceAtLeast(0))?.itemView ?: rail
        }
        if (direction == View.FOCUS_UP && rail.hasFocus()) return hero
        return super.focusSearch(focused, direction)
    }
}

/** A dark text scrim joins the artwork to the sampled card surface at its bottom edge. */
class JetStreamRexSectionScrim @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seam = Paint(Paint.ANTI_ALIAS_FLAG)
    private var surfaceColor = Color.TRANSPARENT

    fun setSurfaceColor(color: Int) {
        if (surfaceColor == color) return
        surfaceColor = color
        updateShaders()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShaders()
    }

    private fun updateShaders() {
        if (height == 0) return
        shade.shader = LinearGradient(0f, height * .3f, 0f, height - jetStreamDp(28),
            Color.TRANSPARENT, 0xD0000000.toInt(), Shader.TileMode.CLAMP)
        seam.shader = LinearGradient(0f, height - jetStreamDp(28), 0f, height.toFloat(),
            Color.TRANSPARENT, surfaceColor, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), seam)
    }
}

/** Horizontal gestures belong to the inner rail; vertical gestures still scroll the home page. */
class JetStreamRexSectionRail @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : HorizontalGridView(context, attrs, defStyleAttr) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    init {
        JetStreamTouch.install(this)
        isFocusableInTouchMode = true
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.y - downY) > slop && abs(event.y - downY) > abs(event.x - downX)) {
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
    }
}
