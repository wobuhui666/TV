package com.fongmi.android.tv.ui.activity;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
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
import androidx.compose.ui.semantics.SemanticsNode;
import androidx.compose.ui.semantics.SemanticsProperties;
import androidx.compose.ui.text.AnnotatedString;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.Until;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLibraryApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.calendar.AiringCalendarRepository;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.calendar.AiringCalendarState;
import com.fongmi.android.tv.ui.calendar.AiringCalendarView;
import com.fongmi.android.tv.ui.custom.LibraryMotion;
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

import java.io.IOException;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<Integer, String> workDates = new ConcurrentHashMap<>();
    private final Map<Integer, String> workTitles = new ConcurrentHashMap<>();
    private final Map<String, Keep> savedFixtureKeeps = new HashMap<>();
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
    private volatile boolean provideEpisodeStills = true;
    private LocalDate fixtureDate;
    private volatile String heldDate = "";
    private volatile int pageSize = 3, totalPages = 1;

    @Before public void setup() {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        fixtureDate = LocalDate.now(AiringCalendarRepository.TIME_ZONE);
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
                    body = CalendarArtworkFixtures.image(url.encodedPath());
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
            for (Map.Entry<String, Keep> saved : savedFixtureKeeps.entrySet()) {
                Keep current = Keep.findDiscover(saved.getKey());
                if (current != null) current.delete();
                if (saved.getValue() != null) saved.getValue().save();
            }
        });
    }

    @Test public void shouldNavigateDatesAndWeeksWithRemoteInEveryExperienceMode() {
        for (ExperienceMode mode : ExperienceMode.values()) {
            main(() -> ExperienceSettings.putMode(mode));
            open(true);
            await("Opening the day initially focuses its first poster", () -> focused().startsWith("item:") && nodeFocused(focused()));
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
        for (int column = 0; column < 20 && state().getItems().size() == 20; column++) {
            String before = focused();
            device.pressDPadRight();
            await("Right reaches the next poster in the horizontal row", () -> !before.equals(focused()) || state().getItems().size() == 40);
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

    @Test public void shouldStartOnTodaysPosterBelowTwoSafeHeaderLayers() {
        open(true);
        String first = state().getItems().get(0).getKey();
        await("Today starts on its first actual poster", () -> focused().equals("item:" + first) && nodeFocused("item:" + first));
        Rect primary = nodeBounds("calendarPrimaryHeader");
        Rect dates = nodeBounds("calendarDateHeader");
        Rect row = nodeBounds("calendarMediaRow");
        Rect window = hostBounds();
        assertTrue("Mode and week controls occupy the first header layer", primary.top < dates.top && primary.bottom <= dates.top);
        assertTrue("The date strip precedes the horizontal poster row", dates.bottom < row.top);
        assertTrue("Header actions have safe horizontal margins", primary.left >= window.left + dp(48)
                && primary.right <= window.right - dp(48));
        for (com.fongmi.android.tv.ui.calendar.AiringCalendarDay day : state().getDays()) {
            UiObject2 number = device.wait(Until.findObject(By.res("date:" + day.getDate())), 10_000);
            assertNotNull("Each date has a visible numeric day", number);
            assertTrue("The visible date action retains its full accessible date", renderedDateDescription(day.getDate()).contains(day.getDate()));
            assertEquals("The date strip exposes its actual rendered numeric label", String.valueOf(LocalDate.parse(day.getDate()).getDayOfMonth()), renderedDayNumber(number, day.getDate()));
        }
        assertEquals(fixtureDate.toString(), state().getSelectedDate());
        NativeUiEvidence.capture("calendar-two-headers-today-focus");
    }

    @Test public void shouldKeepUniformPosterGeometryAndRespectDisabledAnimations() throws Exception {
        assertEquals("Animator preference tests use the dedicated emulator", "1", shell("getprop ro.kernel.qemu").trim());
        String originalScale = shell("settings get global animator_duration_scale").trim();
        assertTrue("The saved animator value is a setting value", originalScale.matches("null|[0-9]+(?:\\.[0-9]+)?"));
        try {
            for (boolean stills : new boolean[]{false, true}) {
                provideEpisodeStills = stills;
                seedFollow(11801); seedFollow(11802);
                shell("settings put global animator_duration_scale 1");
                main(TmdbLibraryApi::clearCache);
                open(true);
                click("mode:following");
                await("Two dated followed works load", () -> !state().getLoading() && state().getItems().size() >= 2);
                await("Enabled system motion is observed", () -> LibraryMotion.enabled(host()));
                String first = state().getItems().get(0).getKey();
                String second = state().getItems().get(1).getKey();
                focus("date:" + state().getSelectedDate());
                Rect before = renderedImageBounds(first);
                Rect neighbor = nodeBounds("item:" + second);
                Rect neighborImage = renderedImageBounds(second);
                double aspect = (double) before.width() / before.height();
                assertEquals("Genuine still or backdrop art selects landscape slots; poster fallback stays portrait", stills ? 16d / 9d : 2d / 3d, aspect, .04);
                assertEquals("Every image in the row uses the same aspect", aspect,
                        (double) neighborImage.width() / neighborImage.height(), .02);
                focus("item:" + first);
                String scaledEvidence = stills ? "calendar-landscape-focus-scale" : "calendar-poster-focus-scale";
                NativeUiEvidence.capture(scaledEvidence);
                await("Focus paints a 1.06 image scale", () -> Math.abs((double) renderedImageBounds(first).width() / before.width() - 1.06) <= .025);
                assertEquals("Focusing does not move the neighboring image", neighbor, nodeBounds("item:" + second));
                Rect focusedImage = renderedImageBounds(first);
                Rect window = hostBounds();
                assertTrue("The scaled focused image stays within safe screen margins", focusedImage.left >= window.left + dp(48)
                        && focusedImage.right <= window.right - dp(48));
                if (evidenceEnabled()) assertPaintedFocusScale(scaledEvidence, before, focusedImage, 1.06);
                shell("settings put global animator_duration_scale 0");
                await("The existing window observes disabled system motion", () -> !LibraryMotion.enabled(host()));
                main(activity::finish); await("Enabled-motion page closes", activity::isDestroyed);

                open(true); click("mode:following");
                await("Disabled motion has dated content", () -> !state().getLoading() && state().getItems().size() >= 2 && !LibraryMotion.enabled(host()));
                String key = state().getItems().get(0).getKey();
                focus("date:" + state().getSelectedDate());
                Rect resting = renderedImageBounds(key);
                focus("item:" + key);
                String disabledEvidence = stills ? "calendar-landscape-motion-disabled" : "calendar-poster-motion-disabled";
                NativeUiEvidence.capture(disabledEvidence);
                Rect disabledFocus = renderedImageBounds(key);
                assertEquals("Disabling animation leaves focused image geometry unchanged", resting, disabledFocus);
                if (evidenceEnabled()) assertPaintedFocusScale(disabledEvidence, resting, disabledFocus, 1.0);
                main(activity::finish); await("Disabled-motion page closes", activity::isDestroyed);
            }
        } finally {
            shell("null".equals(originalScale) ? "settings delete global animator_duration_scale"
                    : "settings put global animator_duration_scale " + originalScale);
        }
    }

    @Test public void shouldShowCompleteFocusedWorkFactsAndRealEpisodeDatesWithoutInventedTimes() {
        open(true);
        String first = state().getItems().get(0).getKey();
        focus("item:" + first);
        await("Focused work metadata and episode overview load", () -> !state().getPreview().getMetadata().isEmpty()
                && !state().getPreview().getOverview().isEmpty()
                && String.join(" · ", state().getPreview().getMetadata()).contains("S01E01")
                && String.join(" · ", state().getPreview().getMetadata()).contains("S01E02"));
        String facts = String.join(" · ", state().getPreview().getMetadata());
        assertTrue("Rating comes from the fetched metadata", facts.contains("8.4"));
        assertTrue("Runtime comes from the fetched episode", facts.matches("(?s).*\\b45\\b.*"));
        assertTrue("Network comes from the actual network array", facts.contains("Fixture Network"));
        assertTrue("The selected date retains both dated episode coordinates", facts.contains("S01E01") && facts.contains("S01E02"));
        assertTrue("The displayed airing date comes from the matched episodes", facts.contains(fixtureDate.toString()));
        assertFalse("A date-only API never creates an airing clock", facts.matches("(?s).*\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b.*"));
        assertTrue("Overview comes from the supplied work or matching episode", state().getPreview().getOverview().startsWith("Synthetic overview for work "));
        assertEquals("The focused work owns the full title", state().getItems().get(0).getTitle(), state().getPreview().getTitle());
        assertNotNull(device.wait(Until.findObject(By.res("calendarPreviewMetadata")), 10_000));
        assertNotNull(device.wait(Until.findObject(By.res("calendarPreviewOverview")), 10_000));
        NativeUiEvidence.capture("calendar-complete-work-info");
    }

    @Test public void shouldPaintAndReplaceFullBackgroundWithSevenToOneTextContrast() {
        open(true);
        String first = state().getItems().get(0).getKey();
        focus("item:" + first);
        String[] firstPainted = new String[1];
        await("The first full background is actually painted", () -> {
            AiringCalendarState snapshot = state();
            String uri = finalEpisodeBackdrop(snapshot, first);
            if (uri == null || loadedBackground(uri) == null || !"loaded".equals(accessibilityState("calendarBackdrop"))) return false;
            firstPainted[0] = uri;
            return true;
        });
        String oldBackdrop = firstPainted[0];
        assertEquals("The painted background covers the actual app window", hostBounds(), nodeBounds("calendarBackdrop"));
        assertNonUniformArtwork(loadedBackground(oldBackdrop));
        Bitmap firstFrame = null;
        if (evidenceEnabled()) {
            NativeUiEvidence.capture("calendar-full-background-first");
            firstFrame = evidenceBitmap("calendar-full-background-first");
            assertBackgroundPixels(firstFrame, oldBackdrop);
        }
        String next = state().getItems().get(1).getKey();
        device.pressDPadRight();
        String[] secondPainted = new String[1];
        await("The second work replaces the full background", () -> {
            AiringCalendarState snapshot = state();
            String uri = finalEpisodeBackdrop(snapshot, next);
            if (uri == null || oldBackdrop.equals(uri) || !focused().equals("item:" + next)
                    || loadedBackground(uri) == null || loadedBackground(oldBackdrop) != null) return false;
            secondPainted[0] = uri;
            return true;
        });
        String newBackdrop = secondPainted[0];
        assertNonUniformArtwork(loadedBackground(newBackdrop));
        try {
            for (boolean light : new boolean[]{false, true}) {
                main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(light));
                settleThemeFrame();
                String evidence = light ? "calendar-full-background-light" : "calendar-full-background-dark";
                NativeUiEvidence.capture(evidence);
                if (evidenceEnabled()) {
                    Bitmap frame = evidenceBitmap(evidence);
                    try {
                        assertBackgroundPixels(frame, newBackdrop);
                        assertBackgroundChanged(firstFrame, frame);
                    } finally { frame.recycle(); }
                    assertRenderedTextContrast(evidence, light, "calendarPreviewTitle");
                    for (int index = 0; index < heroChips().size(); index++) {
                        assertRenderedTextContrast(evidence, light, "calendarPreviewMetadataText:" + index);
                    }
                    assertRenderedTextContrast(evidence, light, "calendarPreviewOverview");
                }
            }
        } finally { if (firstFrame != null) firstFrame.recycle(); }
    }

    private String finalEpisodeBackdrop(AiringCalendarState snapshot, String key) {
        if (!key.equals(snapshot.getPreview().getKey())) return null;
        String facts = String.join(" · ", snapshot.getPreview().getMetadata());
        if (!facts.contains("S01E01") || !facts.contains("S01E02")) return null;
        String media = key.substring(0, key.lastIndexOf('|'));
        String expectedFile = "/still-" + media.substring(media.lastIndexOf(':') + 1) + ".png";
        for (var item : snapshot.getItems()) if (item.getKey().equals(key)) {
            String uri = item.getEpisodeStill();
            return !uri.isBlank() && uri.endsWith(expectedFile) && uri.equals(snapshot.getPreview().getBackdrop()) ? uri : null;
        }
        return null;
    }

    private String json(HttpUrl url) throws Exception {
        String path = url.encodedPath();
        if (path.endsWith("/discover/tv")) {
            int page = Integer.parseInt(url.queryParameter("page"));
            String date = url.queryParameter("air_date.gte");
            JSONArray results = new JSONArray();
            for (int i = 1; i <= pageSize; i++) {
                int id = 118000 + (int) Math.floorMod(LocalDate.parse(date).toEpochDay(), 100000L) * 100 + (page - 1) * pageSize + i;
                String title = "Calendar " + date + " #" + id;
                workDates.put(id, date); workTitles.put(id, title);
                results.put(new JSONObject().put("id", id).put("name", title)
                        .put("first_air_date", "2020-01-01").put("poster_path", "/poster-" + id + ".png")
                        .put("backdrop_path", provideEpisodeStills ? "/backdrop-" + id + ".png" : ""));
            }
            return new JSONObject().put("page", page).put("total_pages", totalPages).put("results", results).toString();
        }
        if (path.endsWith("/images")) return "{\"logos\":[]}";
        if (path.contains("/season/")) {
            String show = path.substring(path.indexOf("/tv/") + 4, path.indexOf("/season/"));
            int id = Integer.parseInt(show);
            String date = workDates.getOrDefault(id, fixtureDate.toString());
            JSONArray episodes = new JSONArray();
            for (int number = 1; number <= 2; number++) episodes.put(new JSONObject()
                    .put("season_number", 1).put("episode_number", number).put("air_date", date)
                    .put("name", "Synthetic episode " + number).put("runtime", 45)
                    .put("overview", overview(id))
                    .put("still_path", provideEpisodeStills ? "/still-" + id + ".png" : ""));
            return new JSONObject().put("season_number", 1).put("episodes", episodes).toString();
        }
        String tail = path.substring(path.lastIndexOf('/') + 1);
        int id = tail.matches("\\d+") ? Integer.parseInt(tail) : 11801;
        return new JSONObject().put("id", id).put("name", workTitles.getOrDefault(id, "Calendar fixture " + id))
                .put("first_air_date", "2020-01-01").put("original_language", "en")
                .put("vote_average", 8.4).put("episode_run_time", new JSONArray().put(45))
                .put("overview", overview(id))
                .put("networks", new JSONArray().put(new JSONObject().put("id", 118).put("name", "Fixture Network")))
                .put("genres", new JSONArray().put(new JSONObject().put("id", 18).put("name", "Drama")))
                .put("poster_path", "/poster-" + id + ".png").put("backdrop_path", provideEpisodeStills ? "/backdrop-" + id + ".png" : "")
                .put("images", new JSONObject().put("logos", new JSONArray().put(new JSONObject()
                        .put("file_path", validLogo ? "/valid-logo-" + id + ".png" : "/failed-logo.png")
                        .put("iso_639_1", "en").put("width", 320).put("height", 80))))
                .put("seasons", new JSONArray().put(new JSONObject().put("season_number", 1).put("episode_count", 2))).toString();
    }

    private String overview(int id) {
        return "Synthetic overview for work " + id + ". Two researchers cross a mountain pass while a storm approaches. "
                + "Their journey reveals a distant observatory and a promise they must keep.";
    }

    private void seedFollow(int id) {
        main(() -> {
            String key = "tmdb:tv:" + id;
            if (!savedFixtureKeeps.containsKey(key)) savedFixtureKeeps.put(key, Keep.findDiscover(key));
            Keep keep = new Keep(); keep.setKey(key); keep.setMediaRef(key); keep.setType(Keep.TYPE_DISCOVER); keep.setCid(0);
            keep.setVodName("Calendar fixture " + id); keep.setVodPic("https://" + HOST + "/t/p/w500/poster-" + id + ".png");
            keep.setSiteName("Fixture Network"); keep.setCreateTime(System.currentTimeMillis()); keep.save();
        });
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

    /** Full, opaque window layers only; excludes row thumbnails and Compose's alpha-zero preloader. */
    private Bitmap loadedBackground(String expectedUrl) {
        if (expectedUrl.isEmpty()) return null;
        Bitmap[] result = new Bitmap[1];
        main(() -> result[0] = backgroundArtwork(host(), expectedUrl, hostBoundsOnMain()));
        return result[0];
    }

    private Bitmap backgroundArtwork(View view, String expectedUrl, Rect window) {
        if (view instanceof ImageView image && view.getClass().getSimpleName().equals("DetailArtworkImageView")
                && expectedUrl.equals(field(view, "artworkUrl")) && image.getDrawable() instanceof BitmapDrawable drawable
                && isOpaqueWindowArtwork(image, window)) {
            Bitmap bitmap = drawable.getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        }
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            Bitmap bitmap = backgroundArtwork(group.getChildAt(index), expectedUrl, window);
            if (bitmap != null) return bitmap;
        }
        return null;
    }

    private boolean isOpaqueWindowArtwork(ImageView image, Rect window) {
        if (!image.isShown() || !image.isAttachedToWindow()) return false;
        float opacity = 1f;
        Object composeHolder = null;
        try {
            Class<?> holderClass = Class.forName("androidx.compose.ui.viewinterop.AndroidViewHolder");
            for (View ancestor = image; ancestor != null;) {
                if (ancestor.getVisibility() != View.VISIBLE || ancestor.getAlpha() <= 0f) return false;
                opacity *= ancestor.getAlpha();
                if (holderClass.isInstance(ancestor)) composeHolder = ancestor;
                ancestor = ancestor.getParent() instanceof View parent ? parent : null;
            }
            if (composeHolder == null) throw new AssertionError("The background image has no Compose AndroidViewHolder");
            Object layout = invoke(composeHolder, "getLayoutNode");
            for (Object node = layout; node != null; node = invoke(node, "getParent$ui_release")) {
                if (!Boolean.TRUE.equals(invoke(node, "isPlaced"))) return false;
                Object coordinator = invoke(node, "getInnerCoordinator$ui_release");
                if (Boolean.TRUE.equals(invoke(coordinator, "isTransparent"))) return false;
                for (Object layer = coordinator; layer != null && invoke(layer, "getLayoutNode") == node;
                        layer = invoke(layer, "getWrappedBy$ui_release")) {
                    if (invoke(layer, "getLayer") != null) opacity *= ((Number) inheritedField(layer, "lastLayerAlpha")).floatValue();
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect the background's actual Compose placement/alpha", error);
        }
        if (opacity < .99f) return false;
        Rect visible = new Rect();
        return image.getGlobalVisibleRect(visible) && visible.equals(window);
    }

    private Object invoke(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private Object inheritedField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private Rect nodeBounds(String tag) {
        StaleObjectException stale = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                UiObject2 node = device.wait(Until.findObject(By.res(tag)), 2000);
                if (node != null) {
                    Rect bounds = node.getVisibleBounds();
                    if (bounds.width() > 0 && bounds.height() > 0) return bounds;
                }
            } catch (StaleObjectException error) { stale = error; }
            SystemClock.sleep(50);
        }
        if (stale != null) throw new AssertionError("Rendered node remains stale after four lookups: " + tag, stale);
        throw new AssertionError("Rendered node has no stable visible bounds after four lookups: " + tag);
    }

    private Rect renderedImageBounds(String key) {
        String tag = "calendarArtwork:" + key;
        for (int attempt = 0; attempt < 4; attempt++) {
            Rect[] measured = new Rect[1];
            main(() -> {
                SemanticsNode image = semanticNode(semanticsRoot(), tag);
                if (image != null && image.getLayoutNode$ui_release().isPlaced() && !image.isTransparent$ui_release()) {
                    Rect bounds = semanticsBounds(image);
                    if (bounds.width() > 0 && bounds.height() > 0) measured[0] = bounds;
                }
            });
            if (measured[0] != null) return measured[0];
            SystemClock.sleep(50);
        }
        throw new AssertionError("The actual unmerged image node has no placed visible bounds: " + tag);
    }

    private SemanticsNode semanticsRoot() {
        View compose = findComposeView(host());
        assertNotNull("The rendered calendar owns its semantics tree", compose);
        try { return (SemanticsNode) invoke(invoke(compose, "getSemanticsOwner"), "getUnmergedRootSemanticsNode"); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Cannot read the rendered semantics tree", error); }
    }

    private SemanticsNode semanticNode(SemanticsNode node, String tag) {
        var config = node.getConfig();
        if (config.contains(SemanticsProperties.INSTANCE.getTestTag()) && tag.equals(config.get(SemanticsProperties.INSTANCE.getTestTag()))) return node;
        for (SemanticsNode child : node.getChildren$ui_release(true, false, false)) {
            SemanticsNode result = semanticNode(child, tag);
            if (result != null) return result;
        }
        return null;
    }

    private Rect semanticsBounds(SemanticsNode node) {
        int[] screen = new int[2], window = new int[2];
        host().getLocationOnScreen(screen); host().getLocationInWindow(window);
        var bounds = node.getBoundsInWindow();
        return new Rect((int) Math.floor(bounds.getLeft()) + screen[0] - window[0], (int) Math.floor(bounds.getTop()) + screen[1] - window[1],
                (int) Math.ceil(bounds.getRight()) + screen[0] - window[0], (int) Math.ceil(bounds.getBottom()) + screen[1] - window[1]);
    }

    private String renderedDateDescription(String isoDate) {
        Rect cell = nodeBounds("date:" + isoDate);
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                UiObject2 date = device.findObject(By.res("date:" + isoDate));
                if (date != null) {
                    String observed = accessibleDescription(date, isoDate);
                    if (!observed.isEmpty()) return observed;
                }
            } catch (StaleObjectException ignored) { }
            String[] observed = new String[1];
            main(() -> observed[0] = dateDescription(semanticsRoot(), isoDate, cell));
            if (observed[0] != null) return observed[0];
            SystemClock.sleep(50);
        }
        throw new AssertionError("The rendered date cell exposes no actual full-date description: " + isoDate);
    }

    private String accessibleDescription(UiObject2 node, String isoDate) {
        String description = node.getContentDescription();
        if (description != null && description.contains(isoDate)) return description;
        for (UiObject2 child : node.getChildren()) {
            description = accessibleDescription(child, isoDate);
            if (!description.isEmpty()) return description;
        }
        return "";
    }

    private String dateDescription(SemanticsNode node, String isoDate, Rect cell) {
        var config = node.getConfig();
        if (config.contains(SemanticsProperties.INSTANCE.getContentDescription()) && node.getLayoutNode$ui_release().isPlaced()
                && !node.isTransparent$ui_release()) {
            Rect safeCell = new Rect(cell); safeCell.inset(-2, -2);
            Rect bounds = semanticsBounds(node);
            if (bounds.width() > 0 && bounds.height() > 0 && safeCell.contains(bounds)) {
                for (String label : config.get(SemanticsProperties.INSTANCE.getContentDescription())) if (label.contains(isoDate)) return label;
            }
        }
        for (SemanticsNode child : node.getChildren$ui_release(true, false, false)) {
            String label = dateDescription(child, isoDate, cell);
            if (label != null) return label;
        }
        return null;
    }

    private void settleThemeFrame() {
        CountDownLatch frames = new CountDownLatch(1);
        main(() -> host().postOnAnimation(() -> host().postOnAnimation(frames::countDown)));
        try { assertTrue("Theme has two actual rendering frames", frames.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        instrumentation.waitForIdleSync();
        nodeBounds("calendarPreviewTitle");
        for (int index = 0; index < heroChips().size(); index++) nodeBounds("calendarPreviewMetadataText:" + index);
        nodeBounds("calendarPreviewOverview");
    }

    private String renderedDayNumber(UiObject2 date, String isoDate) {
        String text = accessibleText(date);
        if (!text.isEmpty()) return text;
        String[] observed = new String[1];
        Rect cell = date.getVisibleBounds();
        main(() -> {
            View compose = findComposeView(host());
            assertNotNull("The rendered calendar owns a Compose semantics tree", compose);
            int[] screen = new int[2], window = new int[2];
            host().getLocationOnScreen(screen); host().getLocationInWindow(window);
            try {
                Object owner = invoke(compose, "getSemanticsOwner");
                SemanticsNode root = (SemanticsNode) invoke(owner, "getUnmergedRootSemanticsNode");
                observed[0] = numericText(root, "date:" + isoDate, "daynumber:" + isoDate,
                        cell, screen[0] - window[0], screen[1] - window[1]);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Cannot inspect the rendered calendar's semantics owner", error);
            }
        });
        assertNotNull("The visible date number is published by its rendered node", observed[0]);
        return observed[0];
    }

    private String accessibleText(UiObject2 node) {
        String text = node.getText();
        if (text != null && !text.isEmpty()) return text;
        for (UiObject2 child : node.getChildren()) {
            text = accessibleText(child);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private View findComposeView(View view) {
        if (view.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView")) return view;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            View compose = findComposeView(group.getChildAt(index));
            if (compose != null) return compose;
        }
        return null;
    }

    /** Read the rendered control's text property when platform merging exposes only its rich description. */
    private String numericText(SemanticsNode node, String dateTag, String numberTag, Rect cell, int dx, int dy) {
        var config = node.getConfig();
        String tag = config.contains(SemanticsProperties.INSTANCE.getTestTag())
                ? config.get(SemanticsProperties.INSTANCE.getTestTag()) : "";
        if ((numberTag.equals(tag) || dateTag.equals(tag)) && config.contains(SemanticsProperties.INSTANCE.getText())) {
            assertTrue("The number's actual layout is placed", node.getLayoutNode$ui_release().isPlaced());
            assertFalse("The number's rendered layer is not transparent", node.isTransparent$ui_release());
            var bounds = node.getBoundsInWindow();
            Rect pixels = new Rect((int) Math.floor(bounds.getLeft()) + dx, (int) Math.floor(bounds.getTop()) + dy,
                    (int) Math.ceil(bounds.getRight()) + dx, (int) Math.ceil(bounds.getBottom()) + dy);
            Rect safeCell = new Rect(cell); safeCell.inset(-2, -2);
            assertTrue("The actual numeric layout is visible inside its date action", pixels.width() > 0 && pixels.height() > 0 && safeCell.contains(pixels));
            List<AnnotatedString> labels = config.get(SemanticsProperties.INSTANCE.getText());
            return String.join("\n", labels.stream().map(AnnotatedString::getText).toList());
        }
        for (SemanticsNode child : node.getChildren()) {
            String text = numericText(child, dateTag, numberTag, cell, dx, dy);
            if (text != null) return text;
        }
        return null;
    }

    private Rect hostBounds() {
        Rect[] bounds = new Rect[1];
        main(() -> bounds[0] = hostBoundsOnMain());
        return bounds[0];
    }

    private Rect hostBoundsOnMain() {
        int[] location = new int[2]; host().getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + host().getWidth(), location[1] + host().getHeight());
    }

    private boolean evidenceEnabled() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("native_ui_evidence"));
    }

    private Bitmap evidenceBitmap(String name) {
        File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        assertNotNull("Actual screenshot directory exists", directory);
        Bitmap bitmap = BitmapFactory.decodeFile(new File(directory, name + ".png").getPath());
        assertNotNull("Actual screenshot is readable: " + name, bitmap);
        return bitmap;
    }

    private List<String> heroChips() {
        LinkedHashSet<String> chips = new LinkedHashSet<>();
        String status = state().getPreview().getAiringStatus();
        int label = switch (status) {
            case "aired" -> R.string.calendar_status_aired;
            case "today" -> R.string.calendar_status_today;
            case "upcoming" -> R.string.calendar_status_upcoming;
            default -> 0;
        };
        if (label != 0) chips.add(activity.getString(label));
        for (String fact : state().getPreview().getMetadata()) if (!fact.isBlank()) chips.add(fact);
        return new ArrayList<>(chips);
    }

    /** Compare unobstructed right-side screen pixels with the actual displayed source scene. */
    private void assertBackgroundPixels(Bitmap frame, String url) {
        Bitmap displayed = loadedBackground(url);
        assertNotNull("A full opaque background owns this screenshot", displayed);
        Bitmap source = displayed.copy(Bitmap.Config.ARGB_8888, false);
        assertNotNull("Read the background's actual decoded scene", source);
        Rect window = hostBounds();
        int matched = 0;
        try {
            double scale = Math.max((double) window.width() / source.getWidth(), (double) window.height() / source.getHeight());
            double cropX = (source.getWidth() * scale - window.width()) / 2d;
            double cropY = (source.getHeight() * scale - window.height()) / 2d;
            for (int[] point : backgroundPoints(window)) {
                assertTrue("Background sample lies inside the real PNG", point[0] >= 0 && point[1] >= 0
                        && point[0] < frame.getWidth() && point[1] < frame.getHeight());
                int sourceX = Math.max(0, Math.min(source.getWidth() - 1, (int) ((point[0] - window.left + cropX) / scale)));
                int sourceY = Math.max(0, Math.min(source.getHeight() - 1, (int) ((point[1] - window.top + cropY) / scale)));
                int expected = source.getPixel(sourceX, sourceY), actual = frame.getPixel(point[0], point[1]);
                int peakSource = Math.max(Color.red(expected), Math.max(Color.green(expected), Color.blue(expected)));
                int peakFrame = Math.max(Color.red(actual), Math.max(Color.green(actual), Color.blue(actual)));
                if (peakSource == 0 || peakFrame < 4) continue;
                // Black readability scrims attenuate the scene; they do not replace its color pattern.
                double attenuation = (double) peakFrame / peakSource;
                if (attenuation > 1.05) continue;
                int tolerance = 6 + (int) Math.round(peakFrame * .04);
                if (Math.abs(Color.red(actual) - Color.red(expected) * attenuation) <= tolerance
                        && Math.abs(Color.green(actual) - Color.green(expected) * attenuation) <= tolerance
                        && Math.abs(Color.blue(actual) - Color.blue(expected) * attenuation) <= tolerance) matched++;
            }
            Log.i("CalendarBackground", "Actual PNG/source correspondences=" + matched + "/9 url=" + url);
            assertTrue("At least six actual screen samples retain the decoded scene's color pattern", matched >= 6);
        } finally { source.recycle(); }
    }

    private void assertBackgroundChanged(Bitmap previous, Bitmap current) {
        assertNotNull("A first real background frame was captured", previous);
        int changed = 0;
        for (int[] point : backgroundPoints(hostBounds())) {
            int before = previous.getPixel(point[0], point[1]), after = current.getPixel(point[0], point[1]);
            int difference = Math.abs(Color.red(before) - Color.red(after)) + Math.abs(Color.green(before) - Color.green(after))
                    + Math.abs(Color.blue(before) - Color.blue(after));
            if (difference >= 8) changed++;
        }
        Log.i("CalendarBackground", "Actual before/after background differences=" + changed + "/9");
        assertTrue("Changing focused work repaints multiple unobstructed background regions", changed >= 3);
    }

    private List<int[]> backgroundPoints(Rect window) {
        List<int[]> points = new ArrayList<>();
        for (float row : new float[]{.30f, .38f, .46f}) for (float column : new float[]{.72f, .81f, .90f}) {
            points.add(new int[]{window.left + Math.round(window.width() * column), window.top + Math.round(window.height() * row)});
        }
        return points;
    }

    private int dp(int value) {
        int[] pixels = new int[1];
        main(() -> pixels[0] = Math.round(value * host().getResources().getDisplayMetrics().density));
        return pixels[0];
    }

    private String shell(String command) throws IOException {
        ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int count; (count = input.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
            return bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }

    private void assertNonUniformArtwork(Bitmap source) {
        assertNotNull("The displayed bitmap exists", source);
        Bitmap sample = source.copy(Bitmap.Config.ARGB_8888, false);
        assertNotNull("Read back the actual displayed bitmap", sample);
        try {
            java.util.Set<Integer> colors = new java.util.HashSet<>();
            for (int row = 1; row <= 4; row++) for (int column = 1; column <= 4; column++) {
                colors.add(sample.getPixel(sample.getWidth() * column / 5, sample.getHeight() * row / 5));
            }
            assertTrue("The reference scene is painted, not a featureless placeholder", colors.size() >= 4);
        } finally { sample.recycle(); }
    }

    private void assertPaintedFocusScale(String evidence, Rect resting, Rect focused, double expectedScale) {
        Bitmap frame = evidenceBitmap(evidence);
        int matched = 0;
        List<Integer> paintedWidths = new ArrayList<>();
        try {
            int radius = dp(10);
            for (float fraction : new float[]{.30f, .50f, .70f}) {
                int y = focused.top + Math.round(focused.height() * fraction);
                int left = -1, right = -1;
                for (int x = Math.max(0, resting.left - radius); x < Math.min(frame.getWidth(), resting.left + radius); x++) {
                    if (isFocusWhite(frame.getPixel(x, y))) { left = x; break; }
                }
                for (int x = Math.max(0, resting.right - radius); x < Math.min(frame.getWidth(), resting.right + radius); x++) {
                    if (isFocusWhite(frame.getPixel(x, y))) right = x;
                }
                if (left >= 0 && right > left) {
                    int width = right - left + 1;
                    paintedWidths.add(width);
                    if (Math.abs((double) width / resting.width() - expectedScale) <= .025) matched++;
                }
            }
            Log.i("CalendarFocusPixels", evidence + " resting=" + resting.width() + " actualWhiteBorders=" + paintedWidths + " expected=" + expectedScale);
            assertTrue("Actual PNG focus borders paint the expected " + expectedScale + " image scale on multiple rows", matched >= 2);
        } finally { frame.recycle(); }
    }

    private boolean isFocusWhite(int color) {
        return Color.red(color) >= 235 && Color.green(color) >= 235 && Color.blue(color) >= 235;
    }

    private void assertRenderedTextContrast(String evidence, boolean light, String tag) {
        File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        assertNotNull("Screenshot directory exists", directory);
        Bitmap screenshot = BitmapFactory.decodeFile(new File(directory, evidence + ".png").getPath());
        assertNotNull("Decode the actual native screenshot", screenshot);
        try {
            Rect bounds = nodeBounds(tag);
            assertTrue("The title has screenshot bounds", bounds.intersect(0, 0, screenshot.getWidth(), screenshot.getHeight()));
            int background = screenshot.getPixel(bounds.centerX(), Math.min(screenshot.getHeight() - 1, bounds.bottom + 3)) | 0xff000000;
            double backgroundLuminance = ColorUtils.calculateLuminance(background);
            int contrastedPixels = 0;
            for (int y = bounds.top; y < bounds.bottom; y++) {
                for (int x = bounds.left; x < bounds.right; x++) {
                    int pixel = screenshot.getPixel(x, y) | 0xff000000;
                    double luminance = ColorUtils.calculateLuminance(pixel);
                    boolean direction = backgroundLuminance > .5 ? luminance < backgroundLuminance - .2 : luminance > backgroundLuminance + .2;
                    if (direction && ColorUtils.calculateContrast(pixel, background) >= 7.0) contrastedPixels++;
                }
            }
            Log.i("CalendarContrast", tag + " light=" + light + " background=" + backgroundLuminance
                    + " contrastedPixels=" + contrastedPixels + " bounds=" + bounds);
            assertTrue("Actual " + tag + " glyph pixels have at least 7:1 contrast with the " + (light ? "light" : "dark") + " theme background",
                    contrastedPixels >= Math.max(20, bounds.width() * bounds.height() / 200));
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
