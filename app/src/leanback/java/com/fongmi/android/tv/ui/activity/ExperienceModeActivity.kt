package com.fongmi.android.tv.ui.activity

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.viewbinding.ViewBinding
import com.fongmi.android.tv.R
import com.fongmi.android.tv.setting.ExperienceMode
import com.fongmi.android.tv.setting.ExperienceSettings
import com.fongmi.android.tv.ui.base.BaseActivity
import com.fongmi.android.tv.ui.custom.LibraryViews
import com.fongmi.android.tv.ui.custom.LibraryPalette
import com.fongmi.android.tv.ui.theme.JetStreamPalette
import com.fongmi.android.tv.utils.Notify

class ExperienceModeActivity : BaseActivity() {
    private lateinit var root: LinearLayout

    override fun getBinding(): ViewBinding {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(LibraryViews.dp(48), LibraryViews.dp(40), LibraryViews.dp(48), LibraryViews.dp(32))
            setBackgroundColor(LibraryPalette.surface())
            clipChildren = false
            clipToPadding = false
        }
        return object : ViewBinding { override fun getRoot(): View = this@ExperienceModeActivity.root }
    }

    override fun customWall() = false

    override fun initView(state: Bundle?) {
        LibraryViews.bindAppearance(root) { root.setBackgroundColor(LibraryPalette.surface()) }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(LibraryViews.text(this, getString(R.string.library_mode), 30, -1))
        heading.addView(LibraryViews.text(this, getString(R.string.library_mode_hint), 14, 0xFFBCB9C4.toInt()).apply {
            setPadding(0, LibraryViews.dp(10), 0, 0)
        })
        root.addView(heading)
        val cards = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        root.addView(cards, LinearLayout.LayoutParams(-1, 0, 1f))
        val labels = intArrayOf(R.string.library_mode_full, R.string.library_mode_simple, R.string.library_mode_classic)
        val help = intArrayOf(R.string.library_mode_full_help, R.string.library_mode_simple_help, R.string.library_mode_classic_help)
        ExperienceMode.values().forEach { mode ->
            val card = LibraryViews.card(this, cards, 268, "", 0, getString(labels[mode.ordinal]),
                "", getString(help[mode.ordinal]), Runnable {
                    ExperienceSettings.putMode(mode)
                    Notify.show(R.string.library_mode_applied)
                    finish()
                })
            card.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = if (mode == ExperienceMode.CLASSIC) 0 else LibraryViews.dp(16)
            }
            card.setPadding(LibraryViews.dp(16), LibraryViews.dp(16), LibraryViews.dp(16), LibraryViews.dp(18))
            card.minimumHeight = LibraryViews.dp(304)
            (card.getChildAt(0) as TextView).apply { textSize = 22f; setPadding(0, LibraryViews.dp(18), 0, LibraryViews.dp(8)) }
            (card.getChildAt(1) as TextView).apply { textSize = 14f; maxLines = 4; setPadding(0, 0, 0, 0) }
            card.addView(preview(mode), 0)
            card.isSelected = ExperienceSettings.getMode() == mode
            card.addView(LibraryViews.text(this, if (card.isSelected) getString(R.string.library_mode_current) else "", 12, 0xFFD9D6E2.toInt()).apply {
                setPadding(0, LibraryViews.dp(14), 0, 0)
            })
            if (ExperienceSettings.getMode() == mode) card.post { card.requestFocus() }
        }
        val back: TextView = LibraryViews.button(this, getString(R.string.library_back))
        root.addView(back, LinearLayout.LayoutParams(-2, -2))
        back.setOnClickListener { finish() }
    }

    private fun preview(mode: ExperienceMode): FrameLayout {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, LibraryViews.dp(136))
            background = GradientDrawable().apply { setColor(LibraryPalette.placeholder()); cornerRadius = LibraryViews.dp(8).toFloat() }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val hero = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(JetStreamPalette.primaryContainerInt(), LibraryPalette.placeholder()))
        }
        frame.addView(hero, FrameLayout.LayoutParams(-1, LibraryViews.dp(if (mode == ExperienceMode.CLASSIC) 24 else 82)))
        frame.addView(LibraryViews.text(this, getString(if (mode == ExperienceMode.CLASSIC) R.string.library_resources else R.string.library_mode_preview_title), 14, -1).apply {
            setPadding(LibraryViews.dp(12), 0, 0, 0)
        }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = LibraryViews.dp(if (mode == ExperienceMode.CLASSIC) 4 else 52) })
        if (mode != ExperienceMode.CLASSIC) {
            frame.addView(LibraryViews.text(this, "▶", 12, 0xFF202027.toInt()).apply {
                setTextColor(if (LibraryPalette.isLight()) JetStreamPalette.onSurfaceInt() else JetStreamPalette.onPrimaryInt())
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(-1); cornerRadius = LibraryViews.dp(20).toFloat() }
            }, FrameLayout.LayoutParams(LibraryViews.dp(54), LibraryViews.dp(22), Gravity.END).apply {
                rightMargin = LibraryViews.dp(12); topMargin = LibraryViews.dp(50)
            })
        }
        val rail = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(LibraryViews.dp(10), 0, LibraryViews.dp(10), 0) }
        repeat(if (mode == ExperienceMode.SIMPLE) 3 else 4) { index ->
            rail.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                    if (index % 2 == 0) JetStreamPalette.primaryContainerInt() else JetStreamPalette.secondaryContainerInt(), LibraryPalette.placeholder()
                )).apply { cornerRadius = LibraryViews.dp(4).toFloat() }
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = LibraryViews.dp(5) })
        }
        frame.addView(rail, FrameLayout.LayoutParams(-1, LibraryViews.dp(if (mode == ExperienceMode.CLASSIC) 92 else 34)).apply {
            topMargin = LibraryViews.dp(if (mode == ExperienceMode.CLASSIC) 34 else 92)
        })
        return frame
    }

    companion object {
        @JvmStatic fun start(activity: Activity) { activity.startActivity(Intent(activity, ExperienceModeActivity::class.java)) }
    }
}
