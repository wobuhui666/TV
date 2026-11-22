package com.fongmi.android.tv.ui.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.core.graphics.ColorUtils
import com.fongmi.android.tv.R
import com.fongmi.android.tv.test.CorePlaybackActivity
import com.fongmi.android.tv.ui.calendar.AiringCalendarDay
import com.fongmi.android.tv.ui.calendar.AiringCalendarItem
import com.fongmi.android.tv.ui.calendar.AiringCalendarPreview
import com.fongmi.android.tv.ui.calendar.AiringCalendarState
import com.fongmi.android.tv.ui.calendar.AiringCalendarView
import com.fongmi.android.tv.ui.rex.RexTheme
import com.fongmi.android.tv.ui.theme.JetStreamThemeController
import com.github.catvod.utils.Prefers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Shipping Compose controls and real remote input; account snapshots have no network side effects. */
@RunWith(AndroidJUnit4::class)
class CalendarProvidersAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val actions = CopyOnWriteArrayList<Pair<String, String>>()
    private val date = LocalDate.of(2026, 11, 18)
    private lateinit var activity: CorePlaybackActivity
    private lateinit var host: AiringCalendarView
    private var originalTheme: Map<String, Any?> = emptyMap()

    @Before fun setup() {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview",
            instrumentation.targetContext.packageName)
        originalTheme = Prefers.getPrefers().all.filterKeys { it in listOf("tv_theme_mode", "tv_light_theme") }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CorePlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CorePlaybackActivity
        main {
            host = AiringCalendarView(activity)
            host.setListener(object : AiringCalendarView.Listener {
                override fun onAction(action: String, key: String) { actions.add(action to key) }
                override fun onFocused(key: String) { }
            })
            activity.setContentView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            host.submit(snapshot())
        }
        await("Calendar window receives focus") { read { activity.hasWindowFocus() && host.isAttachedToWindow } }
        await("Shipping provider control is rendered") { device.hasObject(By.res("provider")) }
    }

    @After fun cleanup() {
        if (::activity.isInitialized) {
            main { host.setListener(null); activity.finish() }
            await("Preview host closes") { read { activity.isDestroyed } }
        }
        main {
            for (key in listOf("tv_theme_mode", "tv_light_theme")) {
                originalTheme[key]?.let { Prefers.put(key, it) } ?: Prefers.remove(key)
            }
            JetStreamThemeController.refresh()
        }
    }

    @Test fun shouldReachDisconnectedTraktAccountAndReturnToItsSelectedDateWithRemote() {
        submit(snapshot(provider = "trakt", mode = "following", connected = false, items = emptyList(),
            emptyMessage = activity.getString(R.string.calendar_connected_required)))
        assertVisibleText(activity.getString(R.string.calendar_connected_required))
        assertFalse(device.hasObject(By.res("follow")))
        assertFalse(device.hasObject(By.res("keeps")))
        focus("provider")
        device.pressDPadRight()
        awaitFocused("account")
        device.pressDPadCenter()
        await("Account action is delivered") { actions.contains("account" to "") }
        device.pressDPadDown()
        awaitFocused("date:$date")
        device.pressDPadUp()
        awaitFocused("account")
        assertEquals("following", read { host.currentState().mode })
        assertEquals(date.toString(), read { host.currentState().selectedDate })
    }

    @Test fun shouldKeepPartialSchedulesNavigableAndSuppressRepeatedFollowWhileAccountIsBusy() {
        val state = snapshot(provider = "trakt", connected = true, canFollow = true,
            failed = true, accountBusy = true)
        submit(state)
        assertVisibleText(activity.getString(R.string.calendar_partial_failed))
        focus("date:$date")
        device.pressDPadDown()
        awaitFocused("item:trakt-first")
        device.pressDPadRight()
        awaitFocused("retry")
        device.pressDPadCenter()
        await("Retry remains available with partial data") { actions.contains("retry" to "") }
        focus("follow")
        val before = actions.count { it.first == "follow" }
        device.pressDPadCenter()
        instrumentation.waitForIdleSync()
        assertEquals("Busy account prevents a second write", before, actions.count { it.first == "follow" })
        submit(state.copy(accountBusy = false))
        focus("follow")
        device.pressDPadCenter()
        await("Follow becomes available after account work finishes") { actions.count { it.first == "follow" } == before + 1 }
        assertTrue(device.hasObject(By.res("item:trakt-first")))
    }

    @Test fun shouldReplaceOldProviderPosterFocusWhileRetainingFollowingModeAndDate() {
        submit(snapshot(provider = "trakt", mode = "following", connected = true, canFollow = true))
        focus("item:trakt-first")
        val previous = read { host.currentState() }
        submit(previous.copy(provider = "bangumi", items = emptyList(), preview = AiringCalendarPreview(),
            loading = true, canFollow = false))
        submit(snapshot(provider = "bangumi", mode = "following", connected = true,
            canFollow = true, followingTitle = true))
        awaitFocused("item:bangumi-first")
        assertEquals(previous.mode, read { host.currentState().mode })
        assertEquals(previous.selectedDate, read { host.currentState().selectedDate })
        assertEquals(previous.weekStart, read { host.currentState().weekStart })
        assertFalse(device.hasObject(By.res("item:trakt-first")))
        focus("follow")
        assertVisibleText(activity.getString(R.string.calendar_pause))
        device.pressDPadCenter()
        await("Bangumi pause keeps the follow action reachable") { actions.contains("follow" to "") }
    }

    @Test fun shouldRestorePosterFocusWhenSwitchingFromAnAccountToLocalFavorites() {
        submit(snapshot(provider = "trakt", mode = "following", connected = true, canFollow = true))
        focus("account")
        submit(snapshot(provider = "tmdb", mode = "following"))
        awaitFocused("item:tmdb-first")
        assertFalse(device.hasObject(By.res("account")))
        assertFalse(device.hasObject(By.res("follow")))
        assertTrue(device.hasObject(By.res("keeps")))
        focus("keeps")
        device.pressDPadCenter()
        await("Local favorites remain reachable") { actions.contains("keeps" to "") }
        device.pressDPadDown()
        awaitFocused("date:$date")
    }

    @Test fun shouldRemoveTheMutationActionAfterDisconnectWithoutDiscardingVisibleSchedules() {
        val state = snapshot(provider = "trakt", connected = true, canFollow = true, followingTitle = true)
        submit(state)
        focus("follow")
        assertVisibleText(activity.getString(R.string.calendar_unfollow))
        submit(state.copy(connected = false, canFollow = false, followingTitle = false))
        awaitFocused("item:trakt-first")
        assertFalse(device.hasObject(By.res("follow")))
        assertTrue(device.hasObject(By.res("item:trakt-first")))
        focus("provider")
        device.pressDPadRight()
        awaitFocused("account")
        device.pressDPadCenter()
        await("Disconnected account can be reconnected") { actions.contains("account" to "") }
    }

    @Test fun shouldUseLightBackgroundAndDarkTextThenRestoreDarkThemeWithoutLosingDateOrRemoteFocus() {
        val file = File(activity.cacheDir, "calendar-theme-artwork.png")
        val artwork = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        artwork.eraseColor(Color.rgb(45, 120, 190))
        file.outputStream().use { artwork.compress(Bitmap.CompressFormat.PNG, 100, it) }
        artwork.recycle()
        val state = snapshot(provider = "bangumi", mode = "following", connected = true, canFollow = true,
            followingTitle = true).let { it.copy(preview = it.preview.copy(backdrop = file.toURI().toString(),
                overview = "A fixture overview for checking calendar text contrast.", metadata = listOf("EP 7.5", "2026-11-18"))) }
        submit(state)
        focus("date:$date")
        for (light in listOf(false, true, false)) {
            main { RexTheme.setLight(light); host.refreshTheme() }
            instrumentation.waitForIdleSync()
            val screenshot = File(activity.getExternalFilesDir("native-usability-evidence"),
                if (light) "calendar-providers-light.png" else "calendar-providers-dark.png")
            await("Calendar background follows the selected appearance") {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                try { (ColorUtils.calculateLuminance(bitmap.getPixel(3, 3)) > .8) == light }
                finally { bitmap.recycle() }
            }
            assertTrue(device.takeScreenshot(screenshot))
            val bitmap = android.graphics.BitmapFactory.decodeFile(screenshot.path)
            try {
                for (tag in listOf("calendarTitle", "calendarPreviewTitle", "calendarPreviewOverview", "calendarCardTitle:bangumi-first")) {
                    val bounds = device.findObject(By.res(tag))?.visibleBounds ?: throw AssertionError("Visible $tag")
                    var glyphs = 0
                    for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                        val luminance = ColorUtils.calculateLuminance(bitmap.getPixel(x, y))
                        if (if (light) luminance < .15 else luminance > .7) glyphs++
                    }
                    assertTrue("$tag uses readable ${if (light) "dark" else "light"} glyphs", glyphs > 20)
                }
                val bounds = device.findObject(By.res("date:$date"))!!.visibleBounds
                val capsule = bitmap.getPixel(bounds.centerX(), bounds.top + 4)
                assertTrue("Focused date uses the inverse theme capsule",
                    if (light) ColorUtils.calculateLuminance(capsule) < .2 else ColorUtils.calculateLuminance(capsule) > .7)
            } finally { bitmap.recycle() }
            awaitFocused("date:$date")
            assertEquals(date.toString(), read { host.currentState().selectedDate })
        }
        file.delete()
    }

    private fun snapshot(provider: String = "tmdb", mode: String = "public", connected: Boolean = false,
        canFollow: Boolean = false, followingTitle: Boolean = false, failed: Boolean = false,
        accountBusy: Boolean = false, items: List<AiringCalendarItem>? = null, emptyMessage: String = ""):
        AiringCalendarState {
        val monday = date.minusDays(2)
        val cards = items ?: listOf(AiringCalendarItem("$provider-first", "Calendar fixture"))
        return AiringCalendarState(mode = mode, provider = provider, connected = connected,
            canFollow = canFollow, followingTitle = followingTitle, failed = failed, accountBusy = accountBusy,
            weekStart = monday.toString(), selectedDate = date.toString(), todayDate = date.toString(),
            weekLabel = "11/16 – 11/22", hasKeeps = connected || provider == "tmdb",
            days = (0..6).map { day ->
                val current = monday.plusDays(day.toLong())
                AiringCalendarDay(current.toString(), current.dayOfWeek.name.take(3), current == date, current == date)
            }, items = cards, emptyMessage = emptyMessage,
            preview = cards.firstOrNull()?.let { AiringCalendarPreview(title = it.title, key = it.key) }
                ?: AiringCalendarPreview())
    }

    private fun submit(state: AiringCalendarState) {
        main { host.submit(state) }
        instrumentation.waitForIdleSync()
        await("Provider state is rendered") { device.hasObject(By.res("provider")) &&
            read { host.currentState() == state } }
    }

    private fun focus(key: String) { main { host.focusKey(key) }; awaitFocused(key) }

    private fun awaitFocused(key: String) = await("Actual remote focus reaches $key") {
        read { host.focusedKey() == key } && device.findObject(By.res(key))?.isFocused == true
    }

    private fun assertVisibleText(text: String) = await("Visible label: $text") { device.hasObject(By.text(text)) }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun <T> read(action: () -> T): T {
        val value = AtomicReference<T>()
        main { value.set(action()) }
        return value.get()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }
}
