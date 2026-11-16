package com.fongmi.android.tv.ui.custom

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import com.fongmi.android.tv.R
import com.fongmi.android.tv.databinding.AdapterRexPosterBinding
import androidx.annotation.FontRes
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.fongmi.android.tv.utils.ImgUtil
import com.fongmi.android.tv.utils.FontWarmup
import com.fongmi.android.tv.utils.ResUtil

/** A native card owns focus and activation; its image and text remain descriptive. */
object LibraryViews {
    @JvmStatic fun artworkBitmap(view: ImageView): Bitmap? = bitmap(view.drawable)

    private fun bitmap(drawable: Drawable?): Bitmap? {
        if (drawable is BitmapDrawable) return drawable.bitmap
        if (drawable is LayerDrawable) for (index in drawable.numberOfLayers - 1 downTo 0) {
            bitmap(drawable.getDrawable(index))?.let { return it }
        }
        return null
    }

    @JvmStatic fun text(context: Context, value: String, size: Int, color: Int): TextView = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(LibraryPalette.readableText(color))
        includeFontPadding = false
        applyTypeface(this)
        if (size >= 20) font(this, R.font.misans_semibold)
        LibraryPalette.textRole(color)?.let { role -> bindJetStreamTheme { setTextColor(jetStreamColor(role)) } }
    }

    @JvmStatic fun applyTypeface(view: TextView) { FontWarmup.apply(view, R.font.misans, true) }

    @JvmStatic fun bindAppearance(view: View, update: Runnable) { view.bindJetStreamTheme { update.run() } }

    private fun font(view: TextView, @FontRes resource: Int) {
        FontWarmup.apply(view, resource, false)
    }

    private fun artworkSurface(view: ImageView, circular: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            if (circular) shape = GradientDrawable.OVAL else cornerRadius = view.jetStreamDp(8)
        }.also { drawable ->
            view.bindJetStreamTheme {
                drawable.setColor(view.jetStreamColor(R.color.jetstream_surface_container))
                view.foreground?.invalidateSelf()
            }
        }
    }

    @JvmStatic fun section(context: Context, parent: LinearLayout, title: String): LinearLayout {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        parent.addView(section, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        section.addView(text(context, title, 20, Color.WHITE).apply {
            font(this, R.font.misans_semibold)
            setPadding(0, 0, 0, dp(12))
        })
        return section
    }

    @JvmStatic fun rail(context: Context, section: LinearLayout): LinearLayout {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(8), dp(4), dp(10))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(row, FrameLayout.LayoutParams(-2, -2))
        section.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    @JvmStatic fun button(context: Context, title: String): TextView = text(context, title, 14, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(9), dp(18), dp(9))
        minHeight = dp(48)
        font(this, R.font.misans_medium)
        focus(this)
    }

    @JvmStatic fun card(context: Context, rail: LinearLayout, width: Int, image: String, imageHeight: Int,
                        title: String, metadata: String?, description: String?, click: Runnable): ViewGroup {
        if (imageHeight > 0) {
            val binding = AdapterRexPosterBinding.inflate(LayoutInflater.from(context), rail, false)
            binding.root.layoutParams = LinearLayout.LayoutParams(dp(width), -2).apply { marginEnd = dp(16) }
            binding.poster.layoutParams = binding.poster.layoutParams.apply { height = dp(imageHeight) }
            binding.name.text = title
            binding.name.visibility = if (title.isEmpty()) View.GONE else View.VISIBLE
            val subtitle = listOf(metadata.orEmpty(), description.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
            binding.meta.text = subtitle
            binding.meta.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
            if (width > imageHeight) {
                binding.name.textSize = 14f
                binding.name.layoutParams = binding.name.layoutParams.apply { height = dp(22) }
                binding.meta.textSize = 11f
                binding.meta.layoutParams = binding.meta.layoutParams.apply { height = dp(18) }
            }
            binding.root.contentDescription = listOf(title, subtitle).filter(String::isNotBlank).joinToString(" · ")
            binding.root.setOnClickListener { click.run() }
            rail.addView(binding.root)
            ImgUtil.loadArtwork(title, image, binding.image, null)
            return binding.root
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, if (title.isEmpty() && metadata.isNullOrEmpty() && description.isNullOrEmpty()) 0 else dp(6))
        }
        rail.addView(card, LinearLayout.LayoutParams(dp(width), -2).apply { marginEnd = dp(16) })
        if (!metadata.isNullOrEmpty()) card.addView(text(context, metadata, 12, 0xFFA9A6B2.toInt()).apply {
            setPadding(dp(2), dp(10), dp(2), 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (title.isNotEmpty()) card.addView(text(context, title, 14, Color.WHITE).apply {
            font(this, R.font.misans_medium)
            setPadding(dp(2), dp(6), dp(2), dp(2))
            maxLines = 2
            setLineSpacing(dp(2).toFloat(), 1f)
            ellipsize = TextUtils.TruncateAt.END
        })
        if (!description.isNullOrEmpty()) card.addView(text(context, description, 12, 0xFF9793A1.toInt()).apply {
            setPadding(dp(2), dp(5), dp(2), dp(4))
            maxLines = 2
            setLineSpacing(dp(2).toFloat(), 1f)
            ellipsize = TextUtils.TruncateAt.END
        })
        card.contentDescription = title + if (metadata.isNullOrEmpty()) "" else " · $metadata"
        focus(card)
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.setOnClickListener { click.run() }
        return card
    }

    @JvmStatic fun related(context: Context, rail: LinearLayout, image: String, title: String, metadata: String,
                           description: String, click: Runnable): ViewGroup =
        card(context, rail, 144, image, 216, title, metadata, "", click)

    @JvmStatic fun focus(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.isClickable = true
        val artwork = (view as? ViewGroup)?.getChildAt(0) as? ImageView
        view.background = if (artwork?.foreground is LibraryArtworkFocus) null else controlBackground(view, 8)
        bindFocusMotion(view)
    }

    @JvmStatic fun chip(view: View) {
        view.background = controlBackground(view, 8)
        if (view is TextView) view.bindJetStreamTheme { view.setTextColor(JetStreamPalette.controlText()) }
    }

    @JvmStatic fun heroAction(view: View) {
        view.background = controlBackground(view, if (view.id == R.id.back) 24 else 12, overlay = true)
    }

    @JvmStatic fun playButton(view: View) {
        view.background = primaryBackground(view)
        if (view is TextView) view.bindJetStreamTheme {
            view.setTextColor(if (LibraryPalette.isLight()) JetStreamPalette.onSurfaceInt() else JetStreamPalette.onPrimaryInt())
            font(view, R.font.misans_medium)
        }
    }

    private fun controlBackground(view: View, radius: Int, overlay: Boolean = false): StateListDrawable {
        val focused = intArrayOf(android.R.attr.state_focused)
        val pressed = intArrayOf(android.R.attr.state_pressed)
        val selected = intArrayOf(android.R.attr.state_selected)
        return StateListDrawable().withJetStreamFocusMotion().apply {
            addState(focused, controlShape(view, radius, focused, 1.5f, overlay))
            addState(pressed, controlShape(view, radius, pressed, 1.5f, overlay))
            addState(selected, controlShape(view, radius, selected, 1f, overlay))
            addState(intArrayOf(), controlShape(view, radius, intArrayOf(), 0f, overlay))
        }
    }

    private fun controlShape(view: View, radius: Int, state: IntArray, stroke: Float, overlay: Boolean): GradientDrawable {
        return GradientDrawable().apply { cornerRadius = view.jetStreamDp(radius) }.also { drawable ->
            view.bindJetStreamTheme {
                val containers = JetStreamPalette.controlContainer()
                val outlines = JetStreamPalette.controlOutline()
                drawable.setColor(if (overlay && state.isEmpty()) view.jetStreamColor(R.color.jetstream_overlay_surface_light)
                    else containers.getColorForState(state, containers.defaultColor))
                drawable.setStroke((stroke * view.resources.displayMetrics.density).toInt(), outlines.getColorForState(state, outlines.defaultColor))
            }
        }
    }

    private fun primaryBackground(view: View): StateListDrawable {
        fun shape(focused: Boolean): GradientDrawable = GradientDrawable().apply {
            cornerRadius = view.jetStreamDp(24)
        }.also { drawable ->
            view.bindJetStreamTheme {
                drawable.setColor(if (LibraryPalette.isLight()) JetStreamPalette.surfaceInt() else JetStreamPalette.primaryInt())
                val outlines = JetStreamPalette.controlOutline()
                val state = if (focused) intArrayOf(android.R.attr.state_focused) else intArrayOf()
                val outline = if (focused || LibraryPalette.isLight()) outlines.getColorForState(state, outlines.defaultColor) else Color.TRANSPARENT
                drawable.setStroke(view.jetStreamDpInt(1), outline)
            }
        }
        return StateListDrawable().withJetStreamFocusMotion().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(true))
            addState(intArrayOf(), shape(false))
        }
    }

    @JvmStatic fun bindFocusMotion(view: View) {
        view.setOnFocusChangeListener { target, focused ->
            animateFocus(target, focused)
        }
        TouchFocus.bind(view)
    }

    @JvmStatic fun animateFocus(view: View, focused: Boolean) {
        if (view is JetStreamVodCardRootLayout) {
            JetStreamAnimator.animateFocus(view, focused, JetStreamAnimator.FOCUS_SCALE_CARD, 0,
                if (LibraryMotion.enabled(view)) JetStreamAnimator.FOCUS_DURATION else 0)
            return
        }
        val artwork = (view as? ViewGroup)?.getChildAt(0) as? ImageView
        val target = artwork?.takeIf { it.foreground is LibraryArtworkFocus } ?: view
        val enabled = LibraryMotion.enabled(view)
        (view.background as? DrawableContainer)?.apply {
            setEnterFadeDuration(if (enabled) JetStreamAnimator.FOCUS_DURATION.toInt() else 0)
            setExitFadeDuration(if (enabled) JetStreamAnimator.FOCUS_DURATION.toInt() else 0)
            if (!enabled) jumpToCurrentState()
        }
        (artwork?.foreground as? LibraryArtworkFocus)?.animate(focused, enabled)
        val scale = if (focused) JetStreamAnimator.FOCUS_SCALE_VIDEO else 1f
        val lift = if (focused) dp(if (artwork != null) 8 else 4).toFloat() else 0f
        target.animate().cancel()
        if (!enabled) {
            target.scaleX = scale
            target.scaleY = scale
            target.translationZ = lift
            return
        }
        target.animate().scaleX(scale).scaleY(scale).translationZ(lift)
            .setInterpolator(DecelerateInterpolator(1.8f)).setDuration(JetStreamAnimator.FOCUS_DURATION).start()
    }

    @JvmStatic fun portrait(card: ViewGroup) {
        val poster = card.findViewById<View>(R.id.poster)
        poster.layoutParams = poster.layoutParams.apply { height = dp(68) }
        (poster as? ViewGroup)?.foreground = null
        card.findViewById<ImageView>(R.id.image)?.apply {
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply { width = dp(68); height = dp(68); gravity = Gravity.CENTER_HORIZONTAL }
            if (this is com.google.android.material.imageview.ShapeableImageView) {
                shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(dp(34).toFloat()).build()
            }
            background = artworkSurface(this, circular = true)
            clipToOutline = true
        }
        for (i in 1 until card.childCount) (card.getChildAt(i) as? TextView)?.gravity = Gravity.CENTER
    }

    @JvmStatic fun dp(value: Int) = ResUtil.dp2px(value)
}
