package com.fongmi.android.tv.ui.activity;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLibraryApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.calendar.AiringCalendarRepository;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.calendar.AiringCalendarState;
import com.fongmi.android.tv.ui.calendar.AiringCalendarView;
import com.fongmi.android.tv.ui.theme.JetStreamThemeController;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

/** Real calendar activity and remote focus, with generated artwork and an offline TMDB transport. */
@RunWith(AndroidJUnit4.class)
public final class AiringCalendarAndroidTest {
    @BeforeClass public static void clearPreviousEvidence() {
        NativeUiEvidence.clear();
    }

    private static final String HOST = "calendar-fixture.invalid";
    private static final List<String> PREF_KEYS = List.of("tv_experience_mode_v1", "tmdb_connection_mode",
            "tmdb_proxy_url", "tv_theme_mode", "tv_light_theme");
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final List<HttpUrl> requested = Collections.synchronizedList(new ArrayList<>());
    private final List<AiringCalendarActivity> activities = new ArrayList<>();
    private final CountDownLatch heldEntered = new CountDownLatch(1);
    private final CountDownLatch releaseHeld = new CountDownLatch(1);
    private final AtomicInteger cancelled = new AtomicInteger();
    private Map<String, ?> savedPreferences;
    private OkHttpClient original, originalTmdb, fixture;
    private AiringCalendarActivity activity;
    private TvHomePreviewActivity home;
    private HomePreviewFixtures.Session homeSession;
    private volatile boolean failDiscover;
    private volatile boolean validLogo;
    private volatile String heldDate = "";
    private volatile int pageSize = 3, totalPages = 1;

    @Before public void setup() {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        byte[] artwork = bitmap();
        main(() -> {
            savedPreferences = new HashMap<>(Prefers.getPrefers().getAll());
            original = OkHttp.client();
            originalTmdb = (OkHttpClient) field(TmdbNetwork.class, "client");
            ExperienceSettings.putMode(ExperienceMode.LIBRARY);
            Setting.putTmdbConnectionMode(Setting.TMDB_CUSTOM_PROXY);
            Setting.putTmdbProxyUrl("https://" + HOST + "/");
            TmdbLibraryApi.clearCache();
            fixture = new OkHttpClient.Builder().addInterceptor(chain -> {
                HttpUrl url = chain.request().url();
                requested.add(url);
                if (!HOST.equals(url.host())) throw new IOException("Offline calendar fixture");
                byte[] body;
                String type = "application/json";
                if (url.encodedPath().contains("/t/p/")) {
                    if (url.encodedPath().endsWith("failed-logo.png")) throw new IOException("Missing fixture logo");
                    body = artwork;
                    type = "image/png";
                } else {
                    if (url.encodedPath().endsWith("/discover/tv")) {
                        String date = url.queryParameter("air_date.gte");
                        if (heldDate.equals(date)) {
                            heldEntered.countDown();
                            try {
                                if (!releaseHeld.await(20, TimeUnit.SECONDS)) throw new IOException("Held calendar request timed out");
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IOException(error);
                            }
                            if (chain.call().isCanceled()) cancelled.incrementAndGet();
                        }
                        if (failDiscover) throw new IOException("Offline schedule failure");
                    }
                    try { body = json(url).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
                    catch (Exception error) { throw new IOException(error); }
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Offline calendar fixture")
                        .body(ResponseBody.create(body, MediaType.get(type))).build();
            }).build();
            set(OkHttp.get(), "client", fixture);
            set(TmdbNetwork.class, "client", fixture);
        });
    }

    @After public void cleanup() {
        releaseHeld.countDown();
        main(() -> {
            for (AiringCalendarActivity active : activities) if (!active.isDestroyed()) active.finish();
        });
        await("Calendar activities close", () -> activities.stream().allMatch(AiringCalendarActivity::isDestroyed));
        closeHomeFixture();
        fixture.dispatcher().cancelAll();
        await("Offline calls finish", () -> fixture.dispatcher().runningCallsCount() == 0);
        main(() -> {
            set(OkHttp.get(), "client", original);
            set(TmdbNetwork.class, "client", originalTmdb);
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            for (String key : PREF_KEYS) {
                Object value = savedPreferences.get(key);
                if (value instanceof String text) editor.putString(key, text);
                else if (value instanceof Integer number) editor.putInt(key, number);
                else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                else editor.remove(key);
            }
            assertTrue("Preference values and presence are restored", editor.commit());
            JetStreamThemeController.refresh();
            TmdbLibraryApi.clearCache();
            fixture.connectionPool().evictAll();
        });
    }

    @Test public void shouldNavigateDatesAndWeeksWithRemoteInEveryExperienceMode() {
        for (ExperienceMode mode : ExperienceMode.values()) {
            main(() -> ExperienceSettings.putMode(mode));
            open(true);
            LocalDate initial = LocalDate.parse(state().getSelectedDate());
            assertEquals(LocalDate.now(AiringCalendarRepository.TIME_ZONE), initial);
            assertEquals(7, state().getDays().size());
            LocalDate monday = LocalDate.parse(state().getWeekStart());
            focus("date:" + monday);
            await("Date focus selects Monday", () -> state().getSelectedDate().equals(monday.toString()) && !state().getLoading());
            device.pressDPadRight();
            LocalDate tuesday = monday.plusDays(1);
            await("Right selects Tuesday", () -> state().getSelectedDate().equals(tuesday.toString()) && !state().getLoading());
            assertFocused("date:" + tuesday);
            device.pressDPadCenter();
            await("Date confirmation enters posters", () -> focused().startsWith("item:"));
            device.pressDPadUp();
            await("First poster row returns to selected date", () -> focused().equals("date:" + tuesday));
            click("week:next");
            await("Next week preserves weekday", () -> state().getSelectedDate().equals(tuesday.plusDays(7).toString()) && !state().getLoading());
            assertFocused("week:next");
            click("week:previous");
            await("Previous week returns", () -> state().getSelectedDate().equals(tuesday.toString()) && !state().getLoading());
            click("today");
            await("Today restores current date", () -> state().getSelectedDate().equals(initial.toString()) && !state().getLoading());
            NativeUiEvidence.capture("calendar-" + mode.name().toLowerCase(java.util.Locale.ROOT));
            main(activity::finish);
            await("Calendar closes", activity::isDestroyed);
        }
    }

    @Test public void shouldOpenTheCalendarFromItsVisibleHomeNavigationInEveryMode() {
        for (ExperienceMode mode : ExperienceMode.values()) {
            Intent destination;
            try {
                main(() -> {
                    // Home installs its own offline interceptor on this base client. The
                    // calendar interceptor would reject the home URL before it reached that fixture.
                    set(OkHttp.get(), "client", original);
                    set(TmdbNetwork.class, "client", originalTmdb);
                    homeSession = HomePreviewFixtures.openCompact(instrumentation.getTargetContext());
                    ExperienceSettings.putMode(mode);
                });
                home = (TvHomePreviewActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), TvHomePreviewActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                await("Production home loads its source fixture", () -> home.hasWindowFocus()
                        && ((Result) HomePreviewFixtures.field(home, "mResult")).getTypes().size() == 2);
                UiObject2 calendar = device.wait(Until.findObject(By.text(home.getString(R.string.calendar_nav))), 10_000);
                assertNotNull("The calendar entry is visible in " + mode, calendar);
                calendar.click();
                await("The production listener routes the calendar", () -> home.lastNavigation != null
                        && String.valueOf(R.string.calendar_nav).equals(home.lastNavKey));
                destination = new Intent(home.lastNavigation);
                assertNotNull(destination.getComponent());
                assertEquals(AiringCalendarActivity.class.getName(), destination.getComponent().getClassName());
            } finally {
                try { closeHomeFixture(); }
                finally {
                    main(() -> {
                        set(OkHttp.get(), "client", fixture);
                        set(TmdbNetwork.class, "client", fixture);
                    });
                }
            }
            activity = (AiringCalendarActivity) instrumentation.startActivitySync(destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            activities.add(activity);
            await("The routed calendar activity loads", () -> activity.hasWindowFocus() && !state().getLoading()
                    && !state().getItems().isEmpty());
            assertEquals("public", state().getMode());
            main(activity::finish);
            await("The routed calendar closes", activity::isDestroyed);
        }
    }

    @Test public void shouldCancelPreviousWeekAndIgnoreItsLateResponse() throws Exception {
        LocalDate today = LocalDate.now(AiringCalendarRepository.TIME_ZONE);
        heldDate = today.toString();
        open(false);
        assertTrue("The old request is held", heldEntered.await(10, TimeUnit.SECONDS));
        click("week:next");
        LocalDate next = today.plusDays(7);
        await("New week completes while old request is held", () -> state().getSelectedDate().equals(next.toString())
                && !state().getLoading() && !state().getItems().isEmpty());
        releaseHeld.countDown();
        await("The held transport was cancelled", () -> cancelled.get() > 0);
        assertEquals(next.toString(), state().getSelectedDate());
        assertTrue("A late old title cannot replace the new week", state().getItems().stream().allMatch(item -> item.getTitle().contains(next.toString())));
        assertFocused("week:next");
    }

    @Test public void shouldLoadNextPageWhenScrollingToTheVisibleTail() {
        pageSize = 20;
        totalPages = 2;
        open(true);
        assertFalse("The next page waits for the tail", requestSnapshot().stream().anyMatch(url -> "2".equals(url.queryParameter("page"))));
        focus("item:" + state().getItems().get(0).getKey());
        for (int row = 0; row < 3; row++) {
            String before = focused();
            device.pressDPadDown();
            await("Down reaches the next poster row", () -> !before.equals(focused()));
        }
        await("Visible tail requests page two", () -> requestSnapshot().stream().anyMatch(url -> "2".equals(url.queryParameter("page")))
                && state().getItems().size() == 40 && !state().getLoading());
        assertEquals("Each page has distinct works", 40, state().getItems().stream().map(item -> item.getKey()).distinct().count());
        assertTrue("Pagination keeps the remote on a poster", focused().startsWith("item:"));
    }

    @Test public void shouldDisplayNetworkErrorAndRetryWithoutShowingAnEmptySchedule() {
        failDiscover = true;
        open(false);
        await("Failure is delivered", () -> state().getFailed() && !state().getLoading()
                && device.findObject(By.text(activity.getString(R.string.calendar_load_failed))) != null);
        assertNotNull("The failure message wins over the empty-day label", device.findObject(By.text(activity.getString(R.string.calendar_load_failed))));
        assertNull("Network failure is not displayed as no schedule", device.findObject(By.text(activity.getString(R.string.calendar_empty_day))));
        failDiscover = false;
        click("retry");
        await("Retry restores results", () -> !state().getFailed() && !state().getLoading() && !state().getItems().isEmpty());
        Log.i("CalendarFocus", "Retry results ready; last focus=" + focused());
        await("Removing retry restores an actual valid focus target", () -> {
            String key = focused();
            return (key.startsWith("item:") || key.startsWith("date:")) && nodeFocused(key);
        });
        Log.i("CalendarFocus", "Retry focus restored=" + focused());
        assertTrue("Removing retry restores a valid focus target", focused().startsWith("item:") || focused().startsWith("date:"));
    }

    @Test public void shouldKeepReadableTitleWhenArtworkLogoFailsInBothThemes() {
        open(true);
        for (boolean light : new boolean[]{false, true}) {
            main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(light));
            focus("item:" + state().getItems().get(0).getKey());
            await("Logo candidates are attempted", () -> requestSnapshot().stream().anyMatch(url -> url.encodedPath().endsWith("failed-logo.png")));
            await("Missing logo falls back to text", () -> "unavailable".equals(accessibilityState("calendarLogo")));
            String title = state().getPreview().getTitle();
            assertFalse(title.isEmpty());
            assertNotNull("Plain title remains readable", device.findObject(By.text(title)));
            String evidence = light ? "calendar-artwork-light" : "calendar-artwork-dark";
            NativeUiEvidence.capture(evidence);
            if ("true".equals(InstrumentationRegistry.getArguments().getString("native_ui_evidence"))) {
                assertRenderedTextContrast(evidence, light, "calendarTitle");
                assertRenderedTextContrast(evidence, light, "calendarPreviewTitle");
            }
        }
    }

    @Test public void shouldPaintArtistTitleAndReplaceItWhenAnotherWorkReceivesFocus() {
        validLogo = true;
        open(true);
        String first = state().getItems().get(0).getKey();
        focus("item:" + first);
        await("The first artwork logo is painted", () -> first.equals(state().getPreview().getKey())
                && "loaded".equals(accessibilityState("calendarLogo"))
                && loadedArtwork(state().getPreview().getLogo()) != null);
        String firstLogo = state().getPreview().getLogo();
        assertTrue(firstLogo.contains("valid-logo-"));
        NativeUiEvidence.capture("calendar-artwork-logo-loaded");
        String next = state().getItems().get(1).getKey();
        String nextTitle = state().getItems().get(1).getTitle();
        device.pressDPadRight();
        await("The next work owns the preview and its displayed logo", () -> focused().equals("item:" + next)
                && next.equals(state().getPreview().getKey()) && nextTitle.equals(state().getPreview().getTitle())
                && !firstLogo.equals(state().getPreview().getLogo())
                && "loaded".equals(accessibilityState("calendarLogo"))
                && loadedArtwork(state().getPreview().getLogo()) != null);
        assertNull("The old artwork is cleared from the preview", loadedArtwork(firstLogo));
        NativeUiEvidence.capture("calendar-artwork-next-logo-loaded");
    }

    @Test public void shouldKeepEmptyDatesNavigable() {
        pageSize = 0;
        open(false);
        await("Empty schedule completes", () -> !state().getLoading() && !state().getFailed()
                && device.findObject(By.text(activity.getString(R.string.calendar_empty_day))) != null);
        LocalDate monday = LocalDate.parse(state().getWeekStart());
        focus("date:" + monday);
        await("Empty Monday completes", () -> state().getSelectedDate().equals(monday.toString()) && !state().getLoading());
        device.pressDPadDown();
        assertFocused("date:" + monday);
        device.pressDPadRight();
        await("Empty date navigation selects Tuesday", () -> focused().equals("date:" + monday.plusDays(1)));
        device.pressDPadUp();
        await("Up keeps the header reachable", () -> focused().startsWith("mode:"));
    }

    private String json(HttpUrl url) throws Exception {
        String path = url.encodedPath();
        if (path.endsWith("/discover/tv")) {
            int page = Integer.parseInt(url.queryParameter("page"));
            String date = url.queryParameter("air_date.gte");
            JSONArray results = new JSONArray();
            for (int i = 1; i <= pageSize; i++) {
                int id = 11800 + (page - 1) * pageSize + i;
                results.put(new JSONObject().put("id", id).put("name", "Calendar " + date + " #" + id)
                        .put("first_air_date", "2020-01-01").put("poster_path", "/poster-" + id + ".png")
                        .put("backdrop_path", "/backdrop-" + id + ".png"));
            }
            return new JSONObject().put("page", page).put("total_pages", totalPages).put("results", results).toString();
        }
        if (path.endsWith("/images")) return "{\"logos\":[]}";
        if (path.contains("/season/")) return "{\"episodes\":[]}";
        String tail = path.substring(path.lastIndexOf('/') + 1);
        int id = tail.matches("\\d+") ? Integer.parseInt(tail) : 11801;
        return new JSONObject().put("id", id).put("name", "Calendar fixture " + id)
                .put("first_air_date", "2020-01-01").put("original_language", "en")
                .put("poster_path", "/poster-" + id + ".png").put("backdrop_path", "/backdrop-" + id + ".png")
                .put("images", new JSONObject().put("logos", new JSONArray().put(new JSONObject()
                        .put("file_path", validLogo ? "/valid-logo-" + id + ".png" : "/failed-logo.png")
                        .put("iso_639_1", "en").put("width", 320).put("height", 80))))
                .put("seasons", new JSONArray()).toString();
    }

    private byte[] bitmap() {
        Bitmap bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(53, 88, 123));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        bitmap.recycle();
        return bytes.toByteArray();
    }

    private List<HttpUrl> requestSnapshot() {
        synchronized (requested) { return new ArrayList<>(requested); }
    }

    private Bitmap loadedArtwork(String expectedUrl) {
        if (expectedUrl.isEmpty()) return null;
        Bitmap[] result = new Bitmap[1];
        main(() -> result[0] = loadedArtwork(host(), expectedUrl));
        return result[0];
    }

    private void assertRenderedTextContrast(String evidence, boolean light, String tag) {
        File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        assertNotNull("Screenshot directory exists", directory);
        Bitmap screenshot = BitmapFactory.decodeFile(new File(directory, evidence + ".png").getPath());
        assertNotNull("Decode the actual native screenshot", screenshot);
        try {
            UiObject2 title = device.wait(Until.findObject(By.res(tag)), 10_000);
            assertNotNull("The rendered title is exposed", title);
            Rect bounds = title.getVisibleBounds();
            assertTrue("The title has screenshot bounds", bounds.intersect(0, 0, screenshot.getWidth(), screenshot.getHeight()));
            int background = screenshot.getPixel(bounds.centerX(), Math.min(screenshot.getHeight() - 1, bounds.bottom + 3)) | 0xff000000;
            double backgroundLuminance = ColorUtils.calculateLuminance(background);
            assertTrue("Screenshot background matches the selected theme", light ? backgroundLuminance > .5 : backgroundLuminance < .2);
            int contrastedPixels = 0;
            for (int y = bounds.top; y < bounds.bottom; y++) {
                for (int x = bounds.left; x < bounds.right; x++) {
                    int pixel = screenshot.getPixel(x, y) | 0xff000000;
                    double luminance = ColorUtils.calculateLuminance(pixel);
                    boolean direction = light ? luminance < backgroundLuminance - .2 : luminance > backgroundLuminance + .2;
                    if (direction && ColorUtils.calculateContrast(pixel, background) >= 4.5) contrastedPixels++;
                }
            }
            Log.i("CalendarContrast", tag + " light=" + light + " background=" + backgroundLuminance
                    + " contrastedPixels=" + contrastedPixels + " bounds=" + bounds);
            assertTrue("Actual " + tag + " glyph pixels contrast with the " + (light ? "light" : "dark") + " background",
                    contrastedPixels >= Math.max(20, bounds.width() * bounds.height() / 1000));
        } finally {
            screenshot.recycle();
        }
    }

    private Bitmap loadedArtwork(View view, String expectedUrl) {
        if (view instanceof ImageView image && view.getClass().getSimpleName().equals("DetailArtworkImageView")
                && expectedUrl.equals(field(view, "artworkUrl")) && image.getDrawable() instanceof BitmapDrawable drawable) {
            Bitmap bitmap = drawable.getBitmap();
            if (bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) return bitmap;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                Bitmap bitmap = loadedArtwork(group.getChildAt(index), expectedUrl);
                if (bitmap != null) return bitmap;
            }
        }
        return null;
    }

    private void closeHomeFixture() {
        try {
            if (home != null && !home.isDestroyed()) {
                main(home::finish);
                await("Home closes before its fixture is restored", home::isDestroyed);
            }
        } finally {
            home = null;
            if (homeSession != null) {
                try { main(homeSession::close); }
                finally { homeSession = null; }
            }
        }
    }

    private void open(boolean waitForData) {
        activity = (AiringCalendarActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), AiringCalendarActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activities.add(activity);
        await("Calendar window receives focus", activity::hasWindowFocus);
        if (waitForData) await("Schedule loads", () -> !state().getLoading() && !state().getItems().isEmpty());
    }

    private AiringCalendarView host() { return activity.findViewById(R.id.calendarRoot); }
    private AiringCalendarState state() {
        AiringCalendarState[] value = new AiringCalendarState[1];
        main(() -> value[0] = host().currentState());
        return value[0];
    }
    private String focused() {
        String[] value = new String[1];
        main(() -> value[0] = host().focusedKey());
        return value[0];
    }
    private void focus(String key) {
        main(() -> host().focusKey(key));
        await("Remote focus reaches " + key, () -> key.equals(focused()) && nodeFocused(key));
    }
    private boolean nodeFocused(String key) {
        UiObject2 node = device.findObject(By.res(key));
        return node != null && node.isFocused();
    }
    private void assertFocused(String key) {
        assertEquals(key, focused());
        assertTrue("The actual accessibility node has focus", nodeFocused(key));
    }
    private void click(String key) { focus(key); device.pressDPadCenter(); }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void await(String message, BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < end) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        assertTrue(message, condition.getAsBoolean());
    }

    private String accessibilityState(String key) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root == null) return "";
        try { return accessibilityState(root, key); }
        finally { root.recycle(); }
    }
    private String accessibilityState(AccessibilityNodeInfo node, String key) {
        if (key.equals(node.getViewIdResourceName())) {
            CharSequence value = AccessibilityNodeInfoCompat.wrap(node).getStateDescription();
            return value == null ? "" : value.toString();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                String value = accessibilityState(child, key);
                if (!value.isEmpty()) return value;
            } finally { child.recycle(); }
        }
        return "";
    }
    private Object field(Object target, String name) {
        try {
            Class<?> type = target instanceof Class<?> value ? value : target.getClass();
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target instanceof Class<?> ? null : target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void set(Object target, String name, Object value) {
        try {
            Class<?> type = target instanceof Class<?> clazz ? clazz : target.getClass();
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target instanceof Class<?> ? null : target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
