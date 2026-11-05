package com.fongmi.android.tv.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverFilterPanel;
import com.fongmi.android.tv.bean.DiscoverQuery;
import com.fongmi.android.tv.bean.DiscoverRequestState;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.activity.DiscoverActivity;
import com.fongmi.android.tv.ui.activity.RexSectionActivity;
import com.fongmi.android.tv.ui.custom.JetStreamPageProgressLayout;
import com.fongmi.android.tv.ui.home.PosterHomeCatalog;
import com.fongmi.android.tv.ui.rex.RexFeedLoader;
import com.fongmi.android.tv.ui.rex.RexFeedResult;
import com.fongmi.android.tv.ui.rex.RexSections;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Production filter activity and fallback loader, with entirely synthetic responses. */
@RunWith(AndroidJUnit4.class)
public final class CompactDiscoveryIntegrationTest {
    private static final String[] PREFERENCES = {"tmdb_proxy_url", "browse_bridge_base", "browse_bridge_sk", "browse_home_layout_v1", "browse_poster_home"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<Activity> activities = new ArrayList<>();
    private final List<HttpUrl> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Object> savedPreferences = new HashMap<>();
    private final Map<Map<Object, Object>, Map<Object, Object>> savedCaches = new IdentityHashMap<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<Call> heldCall = new AtomicReference<>();
    private OkHttpClient originalClient;
    private OkHttpClient originalTmdb;
    private OkHttpClient fixtureClient;
    private RexFeedLoader loader;
    private volatile boolean queryFails;
    private volatile boolean holdQuery;
    private volatile boolean holdFeed;
    private volatile boolean holdDirect;
    private volatile boolean emptyQuery;
    private volatile boolean prepared;

    @Before public void setup() {
        String pkg = instrumentation.getTargetContext().getPackageName();
        assertEquals("Disposable preview application only", "com.fongmi.android.tv.preview", pkg);
        main(() -> {
            for (String key : PREFERENCES) {
                if (Prefers.getPrefers().contains(key)) savedPreferences.put(key, Prefers.getPrefers().getAll().get(key));
            }
            originalClient = OkHttp.client();
            originalTmdb = (OkHttpClient) field(TmdbNetwork.class, "client");
            fixtureClient = new OkHttpClient.Builder().addInterceptor(chain -> {
                HttpUrl url = chain.request().url();
                requests.add(url);
                boolean held = holdQuery && url.encodedPath().endsWith("/discover/movie")
                        && "vote_average.desc".equals(url.queryParameter("sort_by"))
                        || holdFeed && url.host().equals("assets.rexnow.tv")
                        || holdDirect && url.encodedPath().endsWith("/search_subjects");
                if (held) {
                    heldCall.set(chain.call());
                    try {
                        if (!release.await(12, TimeUnit.SECONDS)) throw new IOException("Fixture timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                }
                if (url.encodedPath().endsWith("/new_search_subjects") && queryFails) throw new IOException("Synthetic query error");
                String body = response(url);
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Synthetic discovery fixture")
                        .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
            }).build();
            saveCache(DiscoverApi.class);
            saveCache(RexFeedLoader.class);
            Setting.putTmdbProxyUrl("https://compact-fixture.invalid/");
            Setting.putBridgeSk("");
            BrowseExperienceSettings.putHomeLayout(BrowseExperienceSettings.HOME_COMPACT);
            setField(OkHttp.get(), "client", fixtureClient);
            // Use a different dispatcher to verify that query cancellation reaches TMDB too.
            setField(TmdbNetwork.class, "client", fixtureClient.newBuilder().dispatcher(new okhttp3.Dispatcher()).build());
            prepared = true;
        });
    }

    @After public void cleanup() {
        release.countDown();
        if (!prepared) return;
        main(() -> {
            if (loader != null) loader.close();
            for (Activity activity : activities) if (!activity.isDestroyed()) activity.finish();
        });
        await("All activities close", () -> activities.stream().allMatch(Activity::isDestroyed));
        await("All synthetic calls finish", () -> fixtureClient.dispatcher().runningCallsCount() == 0
                && ((OkHttpClient) field(TmdbNetwork.class, "client")).dispatcher().runningCallsCount() == 0);
        main(() -> {
            setField(OkHttp.get(), "client", originalClient);
            setField(TmdbNetwork.class, "client", originalTmdb);
            for (Map.Entry<Map<Object, Object>, Map<Object, Object>> entry : savedCaches.entrySet()) {
                entry.getKey().clear();
                entry.getKey().putAll(entry.getValue());
            }
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            for (String key : PREFERENCES) {
                Object saved = savedPreferences.get(key);
                if (saved instanceof String text) editor.putString(key, text);
                else if (saved instanceof Integer layout) editor.putInt(key, layout);
                else if (saved instanceof Boolean flag) editor.putBoolean(key, flag);
                else editor.remove(key);
            }
            assertTrue(editor.commit());
            fixtureClient.connectionPool().evictAll();
        });
    }

    @Test public void categoryEntryUsesOneQueryAndKeepsThePageCompact() {
        for (int category : new int[]{PosterHomeCatalog.ALL, PosterHomeCatalog.TV, PosterHomeCatalog.TOP}) {
            requests.clear();
            DiscoverActivity activity = filters(category);
            await("Initial filter response", () -> (boolean) field(activity, "queryFinished"));
            HttpUrl query = requests.stream().filter(url -> url.encodedPath().endsWith("/new_search_subjects")).findFirst().orElseThrow();
            assertEquals(category == PosterHomeCatalog.TV ? "电视剧" : "电影", query.queryParameter("tags"));
            assertEquals(category == PosterHomeCatalog.TOP ? "S" : "T", query.queryParameter("sort"));
            assertEquals("Only the requested query is loaded", 1, requests.size());
            main(() -> {
                ArrayObjectAdapter adapter = (ArrayObjectAdapter) field(activity, "mAdapter");
                assertTrue(adapter.get(0) instanceof DiscoverFilterPanel);
                assertNull(field(activity, "rexSpotlightAdapter"));
                assertTrue(((Map<?, ?>) field(activity, "content")).isEmpty());
                assertTrue(((JetStreamPageProgressLayout) activity.findViewById(R.id.progressLayout)).isContent());
            });
            if (category == PosterHomeCatalog.TV) NativeUiEvidence.capture("compact-filter-tv");
            close(activity);
        }
    }

    @Test public void emptyAndFailedQueriesLeaveFiltersAndRetryActionable() {
        emptyQuery = true;
        DiscoverActivity activity = filters(PosterHomeCatalog.ALL);
        await("Empty query finishes", () -> (boolean) field(activity, "queryFinished"));
        main(() -> {
            assertTrue(((JetStreamPageProgressLayout) activity.findViewById(R.id.progressLayout)).isContent());
            activity.onFilterGroupClick(2);
            assertEquals(2, panel(activity).getExpandedRow());
        });
        NativeUiEvidence.capture("compact-filter-empty");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("BACK collapses the filter", () -> panel(activity).getExpandedRow() == -1 && !activity.isFinishing());
        queryFails = true;
        choose(activity, 5, DiscoverQuery.SORT_RATING);
        await("Failed query finishes", () -> (boolean) field(activity, "queryFinished") && (boolean) field(activity, "queryError"));
        main(() -> activity.findViewById(R.id.recycler).requestFocus());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        await("MENU reaches filters despite failed query", () -> activity.findViewById(R.id.summary) != null
                && activity.findViewById(R.id.summary).hasFocus());
        main(() -> {
            ((androidx.leanback.widget.VerticalGridView) activity.findViewById(R.id.recycler)).setSelectedPosition(2);
        });
        await("Retry row binds", () -> activity.findViewById(R.id.retry) != null && activity.findViewById(R.id.retry).isShown());
        NativeUiEvidence.capture("compact-filter-error");
        queryFails = false;
        emptyQuery = false;
        main(() -> activity.findViewById(R.id.retry).performClick());
        await("Retry restores results", () -> (boolean) field(activity, "queryFinished") && !state(activity).isEmpty());
        NativeUiEvidence.capture("compact-filter-results");
    }

    @Test public void changingFiltersCancelsTheSeparateTmdbDispatcherAndIgnoresOldResults() {
        assertFalse("Synthetic TMDB key required", BuildConfig.TMDB_API_KEY.isEmpty());
        DiscoverActivity activity = filters(PosterHomeCatalog.ALL);
        await("Initial query", () -> (boolean) field(activity, "queryFinished"));
        choose(activity, 0, "tmdb");
        await("TMDB query", () -> (boolean) field(activity, "queryFinished"));
        holdQuery = true;
        choose(activity, 5, DiscoverQuery.SORT_RATING);
        await("Slow rating query starts", () -> heldCall.get() != null);
        int generation = value(() -> state(activity).getGeneration());
        choose(activity, 5, DiscoverQuery.SORT_LATEST);
        await("Old query is cancelled", () -> heldCall.get().isCanceled());
        await("Replacement query arrives", () -> (boolean) field(activity, "queryFinished") && !state(activity).isEmpty());
        assertFalse(value(() -> state(activity).accepts(generation)));
        release.countDown();
        await("Cancelled body delivery settles", () -> ((OkHttpClient) field(TmdbNetwork.class, "client")).dispatcher().runningCallsCount() == 0);
        assertEquals(List.of("tmdb:movie:303"), value(() -> state(activity).getItems().stream().map(Vod::getId).toList()));
    }

    @Test public void directDoubanFallbackAlsoWorksInTheSectionList() {
        AtomicReference<List<RexFeedResult>> results = new AtomicReference<>();
        main(() -> {
            loader = new RexFeedLoader(true);
            loader.load(List.of(RexSections.byKey("douban_movie")), results::set);
        });
        await("Independent Douban fallback arrives", () -> results.get() != null && results.get().size() == 1);
        assertEquals("douban:701", results.get().get(0).getItems().get(0).getId());
        assertTrue(requests.stream().anyMatch(url -> url.encodedPath().endsWith("/search_subjects")));
        RexSectionActivity activity = start(new Intent(instrumentation.getTargetContext(), RexSectionActivity.class)
                .putExtra("section_key", "douban_movie"), RexSectionActivity.class);
        await("Section list uses the same fallback", () -> ((ArrayObjectAdapter) field(activity, "mAdapter")).size() > 0);
        NativeUiEvidence.capture("compact-douban-section");
    }

    @Test public void closingLoaderCancelsTheFeedAndDoesNotStartFallback() {
        holdFeed = true;
        AtomicReference<List<RexFeedResult>> results = new AtomicReference<>();
        main(() -> {
            loader = new RexFeedLoader(true);
            loader.load(List.of(RexSections.byKey("douban_movie")), results::set);
        });
        await("Feed body begins", () -> heldCall.get() != null);
        main(loader::close);
        assertTrue(heldCall.get().isCanceled());
        release.countDown();
        await("Feed cancellation settles", () -> fixtureClient.dispatcher().runningCallsCount() == 0);
        instrumentation.waitForIdleSync();
        assertNull(results.get());
        assertEquals("No dated feed or direct fallback after closure", 1, requests.size());
    }

    @Test public void closingLoaderCancelsTheIndependentDoubanFallback() {
        holdDirect = true;
        AtomicReference<List<RexFeedResult>> results = new AtomicReference<>();
        main(() -> {
            loader = new RexFeedLoader(true);
            loader.load(List.of(RexSections.byKey("douban_movie")), results::set);
        });
        await("Direct Douban fallback begins", () -> heldCall.get() != null);
        main(loader::close);
        assertTrue(heldCall.get().isCanceled());
        release.countDown();
        await("Fallback body delivery settles", () -> fixtureClient.dispatcher().runningCallsCount() == 0);
        instrumentation.waitForIdleSync();
        assertNull(results.get());
    }

    private String response(HttpUrl url) {
        String path = url.encodedPath();
        if (url.host().equals("assets.rexnow.tv")) return "[]";
        if (path.endsWith("/search_subjects")) return "{\"subjects\":[{\"id\":\"701\",\"title\":\"Fallback fixture\",\"cover\":\"android.resource://" + instrumentation.getTargetContext().getPackageName() + "/" + R.drawable.wallpaper_1 + "\",\"rate\":\"8.1\"}]}";
        if (path.endsWith("/new_search_subjects")) return emptyQuery ? "{\"data\":[]}" : "{\"data\":[{\"id\":\"101\",\"title\":\"Query fixture\",\"cover\":\"android.resource://" + instrumentation.getTargetContext().getPackageName() + "/" + R.drawable.wallpaper_1 + "\",\"rate\":\"8.2\"}]}";
        if (path.contains("/genre/")) return "{\"genres\":[]}";
        if (path.endsWith("/discover/movie")) {
            int id = "vote_average.desc".equals(url.queryParameter("sort_by")) ? 202 : "primary_release_date.desc".equals(url.queryParameter("sort_by")) ? 303 : 201;
            return "{\"page\":1,\"total_pages\":1,\"results\":[{\"id\":" + id + ",\"title\":\"TMDB fixture\",\"poster_path\":\"/poster.jpg\",\"media_type\":\"movie\"}]}";
        }
        return "{}";
    }

    private DiscoverActivity filters(int category) {
        return start(new Intent(instrumentation.getTargetContext(), DiscoverActivity.class)
                .putExtra("filters_only", true).putExtra("home_category", category), DiscoverActivity.class);
    }

    private <T extends Activity> T start(Intent intent, Class<T> type) {
        T activity = type.cast(instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        activities.add(activity);
        await("Activity focused", activity::hasWindowFocus);
        return activity;
    }

    private void close(Activity activity) {
        main(activity::finish);
        await("Activity closes", activity::isDestroyed);
    }

    private void choose(DiscoverActivity activity, int row, String value) {
        main(() -> activity.onFilterClick(row, panel(activity).getRow(row).stream()
                .filter(option -> value.equals(option.getValue())).findFirst().orElseThrow()));
    }

    private DiscoverFilterPanel panel(DiscoverActivity activity) { return (DiscoverFilterPanel) field(activity, "filterPanel"); }
    private DiscoverRequestState state(DiscoverActivity activity) { return (DiscoverRequestState) field(activity, "requestState"); }

    @SuppressWarnings("unchecked") private void saveCache(Class<?> owner) {
        Map<Object, Object> cache = (Map<Object, Object>) field(owner, "CACHE");
        savedCaches.put(cache, new HashMap<>(cache));
        cache.clear();
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner instanceof Class<?> ? null : owner, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void main(Runnable task) { instrumentation.runOnMainSync(task); }
    private <T> T value(Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        main(() -> result.set(task.get()));
        return result.get();
    }

    private void await(String message, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (value(condition::getAsBoolean)) return;
            SystemClock.sleep(40);
        }
        fail(message);
    }
}
