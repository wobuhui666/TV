package com.fongmi.android.tv.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fongmi.android.tv.App
import com.fongmi.android.tv.event.ConfigEvent
import com.fongmi.android.tv.event.RefreshEvent
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.utils.FileUtil
import com.fongmi.android.tv.utils.Task
import com.fongmi.android.tv.utils.WallColorUtil
import com.github.catvod.utils.Prefers
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.Future

/** TV-only opt-in background; wallpaper theme extraction does not own an Activity. */
object JetStreamWallpaper {
    private const val KEY_VISIBLE = "leanback_wallpaper_visible"
    private var wallpaperVisible by mutableStateOf(Prefers.getBoolean(KEY_VISIBLE, false))
    private var started = false
    private var generation = 0L
    private var pending: Future<*>? = null

    class VisibilityEvent

    // Matching native and Compose canvases leave the wallpaper visible but quiet.
    val canvasAlpha: Float
        get() = if (wallpaperVisible) 0.78f else 1f

    @JvmStatic
    fun isVisible(): Boolean = wallpaperVisible

    @JvmStatic
    fun setVisible(value: Boolean) {
        if (wallpaperVisible == value) return
        Prefers.put(KEY_VISIBLE, value)
        wallpaperVisible = value
        EventBus.getDefault().post(VisibilityEvent())
    }

    @JvmStatic
    fun start() {
        if (started) return
        started = true
        EventBus.getDefault().register(this)
        refreshColor()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onConfigEvent(event: ConfigEvent) {
        if (event.type() == ConfigEvent.Type.WALL) refreshColor()
    }

    @JvmStatic
    fun refreshColor() {
        val request = ++generation
        val wall = Setting.getWall()
        val type = Setting.getWallType()
        pending?.cancel(true)
        pending = Task.submit {
            val color = WallColorUtil.getColor(wall, type, FileUtil.getWallCache())
            App.post {
                if (request == generation && wall == Setting.getWall() && type == Setting.getWallType()) {
                    pending = null
                    if (color != Setting.getWallColor()) {
                        Setting.putWallColor(color)
                        if (Setting.getThemeColor() == Setting.THEME_FOLLOW_WALLPAPER) RefreshEvent.theme()
                    }
                }
            }
        }
    }
}
