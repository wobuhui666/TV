package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.viewbinding.ViewBinding
import com.fongmi.android.tv.R
import com.fongmi.android.tv.setting.ExperienceMode
import com.fongmi.android.tv.setting.ExperienceSettings
import com.fongmi.android.tv.ui.base.BaseActivity
import com.fongmi.android.tv.ui.custom.LibraryModePreviewView
import com.fongmi.android.tv.ui.custom.LibraryPalette
import com.fongmi.android.tv.ui.custom.LibraryViews
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.fongmi.android.tv.utils.Notify

class ExperienceModeActivity : BaseActivity() {
    private lateinit var root: LinearLayout

    override fun getBinding(): ViewBinding {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LibraryViews.dp(48), LibraryViews.dp(36), LibraryViews.dp(48), LibraryViews.dp(28))
            clipChildren = false
            clipToPadding = false
        }
        return object : ViewBinding { override fun getRoot(): View = this@ExperienceModeActivity.root }
    }

    override fun customWall() = false

    override fun initView(state: Bundle?) {
        LibraryViews.bindAppearance(root) { root.setBackgroundColor(LibraryPalette.surface()) }
        root.addView(LibraryViews.text(this, getString(R.string.library_mode), 30, Color.WHITE))
        root.addView(LibraryViews.text(this, getString(R.string.library_mode_hint), 14, 0xFFBCB9C4.toInt()).apply {
            setPadding(0, LibraryViews.dp(10), 0, 0)
        })
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        val back = LibraryViews.button(this, getString(R.string.library_back)).apply {
            id = View.generateViewId()
            setOnClickListener { finish() }
        }
        val selected = ExperienceSettings.getMode()
        val cards = ExperienceMode.values().map { mode ->
            modeCard(mode, mode == selected).apply {
                nextFocusDownId = back.id
                setOnFocusChangeListener { target, focused ->
                    LibraryViews.animateFocus(target, focused)
                    if (focused) back.nextFocusUpId = id
                }
                row.addView(this, LinearLayout.LayoutParams(0, LibraryViews.dp(292), 1f).apply {
                    marginEnd = if (mode == ExperienceMode.CLASSIC) 0 else LibraryViews.dp(20)
                })
            }
        }
        cards.forEachIndexed { index, card ->
            card.nextFocusLeftId = cards[(index - 1).coerceAtLeast(0)].id
            card.nextFocusRightId = cards[(index + 1).coerceAtMost(cards.lastIndex)].id
        }
        root.addView(back, LinearLayout.LayoutParams(-2, -2))
        cards[selected.ordinal].post { cards[selected.ordinal].requestFocus() }
    }

    private fun modeCard(mode: ExperienceMode, selected: Boolean): LinearLayout {
        val labels = intArrayOf(R.string.library_mode_full, R.string.library_mode_simple, R.string.library_mode_classic)
        val help = intArrayOf(R.string.library_mode_full_help, R.string.library_mode_simple_help, R.string.library_mode_classic_help)
        return LinearLayout(this).apply {
            id = View.generateViewId()
            tag = mode.name
            orientation = LinearLayout.VERTICAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(LibraryViews.dp(18), LibraryViews.dp(18), LibraryViews.dp(18), LibraryViews.dp(20))
            LibraryViews.focus(this)
            background = cardBackground(this)
            isSelected = selected
            addView(LibraryModePreviewView(context, mode), LinearLayout.LayoutParams(-1, LibraryViews.dp(132)))
            val titleRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(LibraryViews.text(context, getString(labels[mode.ordinal]), 22, Color.WHITE),
                LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) titleRow.addView(ImageView(context).apply {
                setImageResource(R.drawable.msr_check)
                contentDescription = getString(R.string.library_mode_current)
                LibraryViews.bindAppearance(this) { setColorFilter(LibraryPalette.primary()) }
            }, LinearLayout.LayoutParams(LibraryViews.dp(22), LibraryViews.dp(22)))
            addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = LibraryViews.dp(22) })
            addView(LibraryViews.text(context, getString(help[mode.ordinal]), 14, 0xFFBCB9C4.toInt()).apply {
                maxLines = 3
                setLineSpacing(LibraryViews.dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = LibraryViews.dp(10) })
            contentDescription = listOf(getString(labels[mode.ordinal]), getString(help[mode.ordinal]),
                if (selected) getString(R.string.library_mode_current) else "").filter(String::isNotEmpty).joinToString(" · ")
            setOnClickListener {
                ExperienceSettings.putMode(mode)
                Notify.show(R.string.library_mode_applied)
                finish()
            }
        }
    }

    private fun cardBackground(card: View): StateListDrawable {
        fun shape(state: IntArray, stroke: Int): GradientDrawable = GradientDrawable().apply {
            cornerRadius = LibraryViews.dp(20).toFloat()
        }.also { drawable ->
            LibraryViews.bindAppearance(card) {
                val surface = JetStreamPalette.controlContainer()
                val outline = JetStreamPalette.controlOutline()
                drawable.setColor(surface.getColorForState(state, surface.defaultColor))
                drawable.setStroke(LibraryViews.dp(stroke), outline.getColorForState(state, outline.defaultColor))
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(intArrayOf(android.R.attr.state_focused), 2))
            addState(intArrayOf(android.R.attr.state_pressed), shape(intArrayOf(android.R.attr.state_pressed), 2))
            addState(intArrayOf(android.R.attr.state_selected), shape(intArrayOf(android.R.attr.state_selected), 1))
            addState(intArrayOf(), shape(intArrayOf(), 0))
        }
    }

    companion object {
        @JvmStatic fun start(activity: Activity) { activity.startActivity(Intent(activity, ExperienceModeActivity::class.java)) }
    }
}
