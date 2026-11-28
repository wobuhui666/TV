package com.fongmi.android.tv.test

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.fongmi.android.tv.R
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.ui.activity.TvPreviewActivity
import com.fongmi.android.tv.ui.custom.JetStreamVodControlView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production Compose toolbar through real touch and remote input. */
@RunWith(AndroidJUnit4::class)
class PlaybackSpeedToolbarTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: TvPreviewActivity? = null

    @Test
    fun shouldChangeSpeedWithoutOpeningSettingsAndRetainRemoteFocus() {
        val originalTheme = Setting.getThemeMode()
        try {
            for (light in listOf(false, true)) {
                main { Setting.putLightTheme(light) }
                val host = instrumentation.startActivitySync(Intent(
                    instrumentation.targetContext, TvPreviewActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TvPreviewActivity
                activity = host
                for (mode in listOf("vod", "live", "cast")) {
                    val clicks = AtomicInteger()
                    main {
                        host.showPage("playback")
                        val controls = host.controls
                        controls.setTransportActions(mode != "cast", mode != "cast", mode == "vod")
                        controls.setEpisodeSelectionVisible(mode == "vod")
                        controls.setDanmakuToggle(mode == "vod", true)
                        controls.setLiveMode(mode == "live")
                        controls.setCommand("speed", "1.00", true, false)
                        controls.setCommand("line", "线路 1", true, false)
                        controls.setCommand("text", "字幕", true, false)
                        controls.setCommand("scale", "自动", true, false)
                        controls.setListener(object : JetStreamVodControlView.Listener {
                            override fun onCommand(key: String) {
                                if (key == "speed") {
                                    val value = if (clicks.incrementAndGet() == 1) "1.25" else "1.50"
                                    controls.setCommand("speed", value, true, false)
                                }
                            }
                            override fun onPlayPause() {}
                            override fun onPrevious() {}
                            override fun onNext() {}
                            override fun onRepeat() {}
                            override fun onCommandLongClick(key: String) {}
                            override fun onSeekTo(positionMs: Long) {}
                            override fun onShowControls() {}
                        })
                    }

                    val speed = speed("1.00")
                    val settings = text(host.getString(R.string.vod_control_group_settings))
                    val speedBounds = speed.visibleBounds
                    val settingsBounds = settings.visibleBounds
                    assertTrue("Speed is in the bottom toolbar", speedBounds.centerY() > device.displayHeight / 2)
                    assertTrue("Speed and settings share a row", kotlin.math.abs(speedBounds.centerY() - settingsBounds.centerY()) < host.dp(8))
                    assertTrue("Speed leaves space for other actions", speedBounds.right < settingsBounds.left)
                    assertTrue("Settings remains inside the screen", settingsBounds.right <= device.displayWidth)
                    capture("${if (light) "light" else "dark"}-$mode-speed")

                    speed.click()
                    speed("1.25")
                    assertEquals("Touch changes speed once", 1, clicks.get())
                    main { assertFalse(host.controls.isSettingsDrawerOpen()) }
                    device.pressDPadCenter()
                    speed("1.50")
                    assertEquals("Remote confirmation keeps the touched button focused", 2, clicks.get())
                    main { assertFalse(host.controls.isSettingsDrawerOpen()) }

                    text(host.getString(R.string.vod_control_group_settings)).click()
                    main { assertTrue(host.controls.isSettingsDrawerOpen()) }
                    assertNull("Speed is no longer a settings item", findText(host.getString(R.string.playback_command_speed)))
                    // The preview host's system Back finishes the Activity. Left is
                    // the production drawer's own remote shortcut for closing it.
                    device.pressDPadLeft()
                    main {
                        assertFalse("Left closes the settings drawer", host.controls.isSettingsDrawerOpen())
                        assertFalse("The preview remains available for the next mode", host.isFinishing)
                    }

                    main { host.controls.setCommand("speed", "1.50", false, false) }
                    assertNull("Hidden speed command has no toolbar button", findSpeed("1.50"))
                }
                main { host.finish() }
                activity = null
            }
        } finally {
            main {
                activity?.finish()
                Setting.putThemeMode(originalTheme)
            }
        }
    }

    private fun findSpeed(value: String): UiObject2? {
        refresh()
        val description = "${activity!!.getString(R.string.playback_command_speed)} $value×"
        return device.findObject(By.desc(description))
    }

    private fun speed(value: String): UiObject2 = awaitControl("speed $value") { findSpeed(value) }

    private fun findText(value: String): UiObject2? {
        refresh()
        return device.findObject(By.text(value))
    }

    private fun text(value: String): UiObject2 = awaitControl(value) { findText(value) }

    private fun awaitControl(name: String, find: () -> UiObject2?): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            find()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Missing toolbar control: $name")
    }

    private fun refresh() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val output = File(activity!!.getExternalFilesDir(null), "upstream-verification")
        assertTrue(output.isDirectory || output.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(output, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
        device.dumpWindowHierarchy(File(output, "$name.xml"))
    }

    private fun main(task: () -> Unit) {
        val error = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { task() } catch (failure: Throwable) { error.set(failure) } }
        error.get()?.let { throw AssertionError("Playback toolbar fixture failed", it) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
    }
}
