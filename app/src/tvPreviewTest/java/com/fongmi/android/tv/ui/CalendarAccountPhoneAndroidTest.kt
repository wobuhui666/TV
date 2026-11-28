package com.fongmi.android.tv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.fongmi.android.tv.R
import com.fongmi.android.tv.calendar.CalendarAccountStore
import com.fongmi.android.tv.calendar.CalendarAccountToken
import com.fongmi.android.tv.calendar.CalendarProvider
import com.fongmi.android.tv.server.Server
import com.fongmi.android.tv.setting.Setting
import com.fongmi.android.tv.setting.ThemeMode
import com.fongmi.android.tv.test.NativeUiEvidence
import com.fongmi.android.tv.ui.activity.CalendarThemePreviewActivity
import com.fongmi.android.tv.ui.theme.JetStreamThemeController
import com.github.catvod.net.OkHttp
import com.google.gson.JsonParser
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/** Real QR pixels, LAN HTTP, account callbacks and Keystore; Bangumi replies are controlled fixtures. */
@RunWith(AndroidJUnit4::class)
class CalendarAccountPhoneAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val http = OkHttpClient()
    private val trusted = OkHttp::class.java.getDeclaredField("trusted").apply { isAccessible = true }
    private lateinit var originalClient: OkHttpClient
    private lateinit var originalTheme: ThemeMode
    private var activity: CalendarThemePreviewActivity? = null
    private val prefs get() = instrumentation.targetContext.getSharedPreferences("calendar_accounts", 0)
    private var saved = emptyMap<String, Any?>()

    @Before fun setup() {
        assertEquals("Disposable preview only", "com.fongmi.android.tv.preview", instrumentation.targetContext.packageName)
        saved = prefs.all.toMap()
        originalTheme = Setting.getThemeMode()
        originalClient = OkHttp.trustedClient()
        val fixture = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "api.bgm.tv") { "Unexpected account host" }
            val valid = request.header("Authorization") == "Bearer valid-fixture"
            val body = if (!valid) "{}" else if (request.url.encodedPath == "/v0/me")
                "{\"username\":\"phone-fixture\"}" else "{\"data\":[],\"total\":0,\"offset\":0,\"limit\":50}"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (valid) 200 else 401)
                .message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        trusted.set(OkHttp.get(), fixture)
        Server.get().start()
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync {
            activity?.finish()
            CalendarAccountStore.disconnect(CalendarProvider.BANGUMI)
            val editor = prefs.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                }
            }
            editor.commit()
            Setting.putThemeMode(originalTheme)
            JetStreamThemeController.refresh()
        }
        instrumentation.waitForIdleSync()
        trusted.set(OkHttp.get(), originalClient)
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdownNow()
    }

    @Test fun shouldDecodeDarkDialogAndInvalidatePhoneWhenCancelled() {
        val code = open(ThemeMode.DARK)
        NativeUiEvidence.capture("bangumi-tv-dark")
        assertEquals("ready", status(code))
        http.newCall(Request.Builder().url(Server.get().getAddress(true) + "/account.html").build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("no-store", it.header("Cache-Control"))
            assertTrue(it.body.string().contains("account-token"))
        }
        device.pressBack()
        await { status(code) == "ended" }
    }

    @Test fun shouldKeepManualTokenEntryReachableInLightMode() {
        val code = open(ThemeMode.LIGHT)
        NativeUiEvidence.capture("bangumi-tv-light")
        val manual = device.wait(Until.findObject(By.res("android:id/button3")), 5000)
        assertNotNull("Manual entry is visible below the QR content", manual)
        manual!!.click()
        assertNotNull(device.wait(Until.findObject(By.clazz(EditText::class.java)), 5000))
        assertEquals("ended", status(code))
        device.pressBack()
    }

    @Test fun shouldPreserveOldAccountOnFailureThenEncryptValidPhoneToken() {
        instrumentation.runOnMainSync {
            CalendarAccountStore.saveToken(CalendarProvider.BANGUMI, CalendarAccountToken("previous-fixture"))
        }
        val code = open(ThemeMode.DARK)
        submit(code, "invalid-fixture")
        await { status(code) == "invalid_token" }
        assertEquals("previous-fixture", CalendarAccountStore.token(CalendarProvider.BANGUMI)?.accessToken)
        submit(code, "valid-fixture")
        await { status(code) == "connected" }
        assertEquals("valid-fixture", CalendarAccountStore.token(CalendarProvider.BANGUMI)?.accessToken)
        assertFalse(prefs.getString("bangumi_token", "")!!.contains("valid-fixture"))
        assertTrue(CalendarAccountStore.lastSync(CalendarProvider.BANGUMI) > 0)
        await { !device.hasObject(By.desc(activity!!.getString(R.string.calendar_phone_connect))) }
        submit(code, "must-not-replace")
        assertEquals("valid-fixture", CalendarAccountStore.token(CalendarProvider.BANGUMI)?.accessToken)
    }

    private fun open(mode: ThemeMode): String {
        instrumentation.runOnMainSync { Setting.putThemeMode(mode); JetStreamThemeController.refresh() }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CalendarThemePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CalendarThemePreviewActivity
        instrumentation.runOnMainSync { activity!!.showAccount(CalendarProvider.BANGUMI) }
        val label = if (CalendarAccountStore.token(CalendarProvider.BANGUMI) == null) R.string.calendar_connect else R.string.calendar_reconnect
        val connect = device.wait(Until.findObject(By.text(activity!!.getString(label))), 10000)
        assertNotNull(connect)
        connect!!.click()
        val qr = device.wait(Until.findObject(By.desc(activity!!.getString(R.string.calendar_phone_connect))), 10000)
        assertNotNull(qr)
        assertNotNull("Cancel is visible", device.wait(Until.findObject(By.res("android:id/button2")), 5000))
        assertNotNull("Manual entry is visible", device.wait(Until.findObject(By.res("android:id/button3")), 5000))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bounds = qr!!.visibleBounds
        val screen = instrumentation.uiAutomation.takeScreenshot()
        val bitmap = Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
        val url = try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
        } finally { bitmap.recycle(); screen.recycle() }
        val uri = URI(url)
        assertEquals("/account.html", uri.path)
        assertTrue(uri.fragment.matches(Regex("[a-f0-9]{48}")))
        return uri.fragment
    }

    private fun submit(code: String, token: String) {
        val body = "{\"token\":\"$token\"}".toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(Server.get().getAddress(true) + "/account/session")
            .header("X-Calendar-Pairing", code).post(body).build()).execute().use { assertEquals(200, it.code) }
    }

    private fun status(code: String): String = http.newCall(Request.Builder()
        .url(Server.get().getAddress(true) + "/account/session").header("X-Calendar-Pairing", code).build()).execute().use {
        if (it.code == 410) "ended" else {
            assertEquals(200, it.code)
            JsonParser.parseString(it.body.string()).asJsonObject.get("state").asString
        }
    }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Account transition completed", check())
    }
}
