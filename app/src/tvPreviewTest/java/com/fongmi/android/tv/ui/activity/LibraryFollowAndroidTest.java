package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.Until;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLibraryApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.calendar.AiringCalendarRepository;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.library.LibraryDetailState;
import com.fongmi.android.tv.ui.library.LibraryDetailView;
import com.fongmi.android.tv.ui.calendar.AiringCalendarState;
import com.fongmi.android.tv.ui.calendar.AiringCalendarView;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

/** Production library actions with synthetic metadata, no source and no external artwork. */
@RunWith(AndroidJUnit4.class)
public final class LibraryFollowAndroidTest {
    private static final long TV_ID = 901181;
    private static final long OTHER_TV_ID = 901182;
    private static final long MOVIE_ID = 901183;
    private static final String TV_KEY = "tmdb:tv:" + TV_ID;
    private static final String OTHER_TV_KEY = "tmdb:tv:" + OTHER_TV_ID;
    private static final String MOVIE_KEY = "tmdb:movie:" + MOVIE_ID;
    private static final String SOURCE_KEY = "follow-fixture" + AppDatabase.SYMBOL + "same-show";
    private static final String[] PREF_KEYS = {"tv_experience_mode_v1", "tmdb_connection_mode", "tmdb_proxy_url", "preload_next", "incognito"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final List<Activity> activities = new ArrayList<>();
    private final List<Keep> previousKeeps = new ArrayList<>();
    private final Map<String, Object> previousPreferences = new HashMap<>();
    private OkHttpClient originalClient, originalTmdbClient, fixture;
    private Object originalSites, originalHome, originalConfig;
    private LocalDate fixtureDate;

    @Before public void setup() {
        assertEquals("Only the isolated preview application", "com.fongmi.android.tv.preview", instrumentation.getTargetContext().getPackageName());
        fixtureDate = LocalDate.now(AiringCalendarRepository.TIME_ZONE);
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff386572);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        bitmap.recycle();
        byte[] picture = output.toByteArray();
        fixture = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (!chain.request().url().host().equals("library-follow.invalid")) throw new IOException("Offline follow fixture");
            String path = chain.request().url().encodedPath();
            byte[] body;
            String type = "application/json";
            if (path.endsWith(".png")) { body = picture; type = "image/png"; }
            else {
                String json;
                if (path.contains("/season/")) json = "{\"season_number\":1,\"episodes\":[{\"season_number\":1,\"episode_number\":1,\"air_date\":\"" + fixtureDate + "\",\"name\":\"First episode\"},"
                        + "{\"season_number\":1,\"episode_number\":2,\"air_date\":\"" + fixtureDate + "\",\"name\":\"Second episode\"}]}";
                else if (path.endsWith("/discover/tv")) json = "{\"page\":1,\"total_pages\":1,\"results\":[" + metadata(TV_ID, false) + "]}";
                else if (path.endsWith("/tv/" + TV_ID)) json = metadata(TV_ID, false);
                else if (path.endsWith("/movie/" + MOVIE_ID)) json = metadata(MOVIE_ID, true);
                else json = "{\"results\":[],\"logos\":[],\"list\":[],\"page\":1,\"total_pages\":1}";
                body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline follow fixture")
                    .body(ResponseBody.create(body, MediaType.get(type))).build();
        }).build();
        main(() -> {
            for (String key : PREF_KEYS) if (Prefers.getPrefers().contains(key)) previousPreferences.put(key, Prefers.getPrefers().getAll().get(key));
            originalClient = OkHttp.client(); originalTmdbClient = (OkHttpClient) getField(TmdbNetwork.class, "client");
            originalSites = getField(VodConfig.get(), "sites"); originalHome = getField(VodConfig.get(), "home"); originalConfig = getField(VodConfig.get(), "config");
            VodConfig.get().init(); setField(VodConfig.get(), "sites", List.of()); setField(VodConfig.get(), "home", new Site());
            for (Keep keep : Keep.getVodAndDiscover()) if (isFixture(keep)) { previousKeeps.add(keep); keep.delete(); }
            ExperienceSettings.putMode(ExperienceMode.LIBRARY);
            Setting.putTmdbConnectionMode(Setting.TMDB_CUSTOM_PROXY); Setting.putTmdbProxyUrl("https://library-follow.invalid/");
            Prefers.put("preload_next", false); Prefers.put("incognito", true);
            setField(OkHttp.get(), "client", fixture); setField(TmdbNetwork.class, "client", fixture);
            TmdbLibraryApi.clearCache();
        });
    }

    @After public void cleanup() {
        main(() -> { for (Activity activity : activities) if (!activity.isDestroyed()) activity.finish(); });
        await("Fixture activities finish", () -> activities.stream().allMatch(Activity::isDestroyed));
        fixture.dispatcher().cancelAll();
        main(() -> {
            for (Keep keep : Keep.getVodAndDiscover()) if (isFixture(keep)) keep.delete();
            for (Keep keep : previousKeeps) keep.save();
            setField(OkHttp.get(), "client", originalClient); setField(TmdbNetwork.class, "client", originalTmdbClient);
            setField(VodConfig.get(), "sites", originalSites); setField(VodConfig.get(), "home", originalHome); setField(VodConfig.get(), "config", originalConfig);
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            for (String key : PREF_KEYS) {
                Object value = previousPreferences.get(key);
                if (value instanceof String text) editor.putString(key, text);
                else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                else if (value instanceof Integer number) editor.putInt(key, number);
                else editor.remove(key);
            }
            assertTrue(editor.commit()); TmdbLibraryApi.clearCache();
        });
        fixture.connectionPool().evictAll();
    }

    @Test public void shouldFollowAndUnfollowSeriesWithoutAPlaybackSource() {
        LibraryDetailActivity detail = open(work(TV_KEY));
        await("No source leaves playback unavailable", () -> !state(detail).getPlayEnabled());
        assertTrue(state(detail).getFollowable()); assertFalse(state(detail).getKept());
        awaitKeepLabel(detail, R.string.library_detail_follow);
        keepButton().click();
        await("Follow state updates", () -> state(detail).getKept());
        Keep saved = Keep.findDiscover(TV_KEY);
        assertNotNull(saved); assertEquals(TV_KEY, saved.getMediaRef()); assertEquals(Keep.TYPE_DISCOVER, saved.getType());
        awaitKeepLabel(detail, R.string.library_detail_following);
        NativeUiEvidence.capture("library-follow-no-source");
        keepButton().click();
        await("Unfollow clears the calendar identity", () -> !state(detail).getKept() && Keep.findDiscover(TV_KEY) == null);
        assertFalse("Following never enables or starts playback", state(detail).getPlayEnabled());
        assertTrue("Following stays on the detail page", detail.hasWindowFocus());
    }

    @Test public void shouldRecognizeLinkedBookmarksAndRemoveOnlyMatchingFollowDuplicates() {
        main(() -> keep(SOURCE_KEY, TV_KEY, Keep.TYPE_VOD).save());
        LibraryDetailActivity detail = open(work(TV_KEY));
        await("A source bookmark already follows the same TMDB work", () -> state(detail).getKept());
        main(() -> {
            keep(TV_KEY, "", Keep.TYPE_DISCOVER).save();
            keep(OTHER_TV_KEY, OTHER_TV_KEY, Keep.TYPE_DISCOVER).save();
            RefreshEvent.keep();
        });
        keepButton().click();
        await("Unfollow removes both matching storage forms", () -> !state(detail).getKept()
                && Keep.find(SOURCE_KEY) == null && Keep.findDiscover(TV_KEY) == null);
        assertNotNull("An unrelated followed show is retained", Keep.findDiscover(OTHER_TV_KEY));
        main(() -> { keep(SOURCE_KEY, TV_KEY, Keep.TYPE_VOD).save(); RefreshEvent.keep(); });
        await("A bookmark event updates the visible follow state", () -> state(detail).getKept());
    }

    @Test public void shouldKeepMoviesAndUnmatchedSourceWorksAsOrdinaryBookmarks() {
        LibraryDetailActivity movie = open(work(MOVIE_KEY));
        assertTrue(state(movie).getMovie()); assertFalse(state(movie).getFollowable());
        assertEquals(movie.getString(R.string.keep), keepButton().getContentDescription());
        keepButton().click();
        await("A movie still uses the existing bookmark action", () -> state(movie).getKept() && Keep.findDiscover(MOVIE_KEY) != null);
        main(movie::finish); await("Movie detail finishes", movie::isDestroyed);
        Vod source = work("same-show"); source.setSite(Site.get("follow-fixture", "Fixture")); source.setTypeName("tv");
        LibraryDetailActivity unmatched = open(source);
        assertFalse("An unlinked source cannot claim a dated follow identity", state(unmatched).getFollowable());
        assertEquals(unmatched.getString(R.string.keep), keepButton().getContentDescription());
    }

    @Test public void shouldShowLibraryFollowInCalendarAndRemoveItAfterReturningFromUnfollow() {
        LibraryDetailActivity original = open(work(TV_KEY));
        keepButton().click();
        await("The library action stores a followed work", () -> state(original).getKept() && Keep.findDiscover(TV_KEY) != null);
        assertEquals(TV_KEY, Keep.findDiscover(TV_KEY).getMediaRef());

        AiringCalendarActivity calendar = (AiringCalendarActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), AiringCalendarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activities.add(calendar);
        await("Calendar receives focus", calendar::hasWindowFocus);
        UiObject2 following = device.wait(Until.findObject(By.res("mode:following")), 10000);
        assertNotNull("My follows is a visible calendar control", following);
        following.click();
        String key = TV_KEY + "|" + fixtureDate;
        await("The library follow supplies this week's episode schedule", () -> {
            AiringCalendarState state = calendarState(calendar);
            return state.getMode().equals("following") && !state.getLoading()
                    && state.getItems().stream().anyMatch(item -> item.getKey().equals(key));
        });
        AiringCalendarState loaded = calendarState(calendar);
        assertEquals(fixtureDate.toString(), loaded.getSelectedDate());
        assertEquals("Both episodes share one work card", 1L, loaded.getItems().stream().filter(item -> item.getKey().equals(key)).count());
        assertEquals("S01E01 · S01E02", loaded.getItems().stream().filter(item -> item.getKey().equals(key)).findFirst().orElseThrow().getSubtitle());
        main(() -> ((AiringCalendarView) calendar.findViewById(R.id.calendarRoot)).focusKey("item:" + key));
        await("Focusing the work selects its stable preview", () -> calendarState(calendar).getPreview().getKey().equals(key));
        assertEquals("Follow fixture series", calendarState(calendar).getPreview().getTitle());
        assertEquals("S01E01 · S01E02", calendarState(calendar).getPreview().getSubtitle());
        NativeUiEvidence.capture("library-follow-calendar");

        UiObject2 card = device.wait(Until.findObject(By.res("item:" + key)), 10000);
        assertNotNull("The followed work can open its library detail", card);
        card.click();
        LibraryDetailActivity[] opened = new LibraryDetailActivity[1];
        await("Calendar opens a new canonical library detail", () -> {
            opened[0] = resumedDetailExcept(original);
            return opened[0] != null && opened[0].hasWindowFocus() && state(opened[0]).getKept();
        });
        activities.add(opened[0]);
        assertEquals("The calendar opens the same TMDB work", TV_KEY, ((Vod) opened[0].getIntent().getParcelableExtra("work")).getId());
        keepButton().click();
        await("Unfollow removes the stored calendar identity", () -> !state(opened[0]).getKept() && Keep.findDiscover(TV_KEY) == null);
        device.pressBack();
        await("Returning refreshes My follows without the removed work", () -> calendar.hasWindowFocus()
                && !calendarState(calendar).getLoading()
                && calendarState(calendar).getItems().stream().noneMatch(item -> item.getKey().equals(key)));
        assertEquals("following", calendarState(calendar).getMode());
        assertEquals(fixtureDate.toString(), calendarState(calendar).getSelectedDate());
    }

    private LibraryDetailActivity open(Vod work) {
        LibraryDetailActivity detail = (LibraryDetailActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibraryDetailActivity.class)
                .putExtra("work", work).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activities.add(detail);
        await("The library work is rendered", () -> state(detail).getTitle().equals(work.getName()) && detail.hasWindowFocus());
        assertNotNull(device.wait(Until.findObject(By.res("keep")), 10000));
        return detail;
    }

    private Vod work(String key) {
        Vod work = new Vod(); work.setId(key); work.setName(key.startsWith("tmdb:movie:") ? "Follow fixture movie" : "Follow fixture series");
        work.setTypeName(key.startsWith("tmdb:movie:") ? "movie" : "tv"); work.setYear("2026");
        work.setPic("https://library-follow.invalid/poster.png"); work.setBackdrop(work.getPic());
        return work;
    }

    private Keep keep(String key, String reference, int type) {
        Keep keep = new Keep(); keep.setKey(key); keep.setMediaRef(reference); keep.setType(type); keep.setCid(type == Keep.TYPE_DISCOVER ? 0 : VodConfig.getCid());
        keep.setVodName("Follow fixture series"); keep.setVodPic("https://library-follow.invalid/poster.png"); keep.setSiteName("Fixture"); keep.setCreateTime(System.currentTimeMillis());
        return keep;
    }

    private boolean isFixture(Keep keep) {
        return List.of(TV_KEY, OTHER_TV_KEY, MOVIE_KEY, SOURCE_KEY).contains(keep.getKey()) || TV_KEY.equals(keep.getMediaRef());
    }

    private UiObject2 keepButton() {
        UiObject2 button = device.wait(Until.findObject(By.res("keep")), 10000);
        assertNotNull("The library follow/bookmark action is visible", button);
        return button;
    }

    private void awaitKeepLabel(LibraryDetailActivity detail, int labelResource) {
        String expected = detail.getString(labelResource);
        // currentState changes before Compose publishes its replacement action node.
        // TV Button exposes the same label as description or merged text across SDKs.
        await("The rendered follow action exposes " + expected, () -> expected.equals(keepLabel()));
        assertEquals("The action retains its exact accessible label", expected, keepLabel());
    }

    private String keepLabel() {
        UiObject2 button = device.findObject(By.res("keep"));
        if (button == null) return "";
        try {
            String description = button.getContentDescription();
            if (description != null && !description.isEmpty()) return description;
            String text = button.getText();
            return text == null ? "" : text;
        } catch (StaleObjectException ignored) {
            // A state update can replace the semantic node between lookup and access.
            return "";
        }
    }

    private LibraryDetailState state(LibraryDetailActivity detail) {
        LibraryDetailState[] result = new LibraryDetailState[1];
        main(() -> result[0] = ((LibraryDetailView) detail.findViewById(R.id.libraryRoot)).currentState());
        return result[0];
    }

    private AiringCalendarState calendarState(AiringCalendarActivity calendar) {
        AiringCalendarState[] result = new AiringCalendarState[1];
        main(() -> result[0] = ((AiringCalendarView) calendar.findViewById(R.id.calendarRoot)).currentState());
        return result[0];
    }

    private LibraryDetailActivity resumedDetailExcept(LibraryDetailActivity previous) {
        LibraryDetailActivity[] result = new LibraryDetailActivity[1];
        main(() -> {
            for (Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                if (activity instanceof LibraryDetailActivity detail && detail != previous) { result[0] = detail; break; }
            }
        });
        return result[0];
    }

    private String metadata(long id, boolean movie) {
        return "{\"id\":" + id + ",\"" + (movie ? "title" : "name") + "\":\"Follow fixture " + (movie ? "movie" : "series")
                + "\",\"poster_path\":\"/poster.png\",\"backdrop_path\":\"/poster.png\",\"overview\":\"Calendar follow fixture\",\""
                + (movie ? "release_date" : "first_air_date") + "\":\"2026-11-01\",\"seasons\":[{\"season_number\":1,\"episode_count\":2}],"
                + "\"credits\":{\"cast\":[]},\"images\":{\"logos\":[]},\"genres\":[]}";
    }

    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        assertTrue(message, condition.getAsBoolean());
    }
    private Object getField(Object target, String name) {
        try {
            Field field = findField(target, name); field.setAccessible(true);
            return field.get(target instanceof Class<?> ? null : target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void setField(Object target, String name, Object value) {
        try {
            Field field = findField(target, name); field.setAccessible(true);
            field.set(target instanceof Class<?> ? null : target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private Field findField(Object target, String name) throws NoSuchFieldException {
        Class<?> type = target instanceof Class<?> value ? value : target.getClass();
        while (type != null) {
            try { return type.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
}
