package com.fongmi.android.tv.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverFacet;
import com.fongmi.android.tv.bean.DiscoverFilterPanel;
import com.fongmi.android.tv.bean.RexSection;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.DiscoverActivity;
import com.fongmi.android.tv.ui.activity.DiscoverResultActivity;
import com.fongmi.android.tv.ui.activity.RexSectionActivity;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.JetStreamChipRow;
import com.fongmi.android.tv.ui.rex.RexFeedLoader;
import com.fongmi.android.tv.ui.rex.RexSections;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

/** Real wall activities, Compose chip clicks and delayed HTTP fixtures; no external API is called. */
@RunWith(AndroidJUnit4.class)
public final class PosterWallFiltersIntegrationTest {
    private static final String[] SETTINGS = {"tmdb_connection_mode", "tmdb_proxy_url", "browse_bridge_sk"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<Activity> launched = new ArrayList<>();
    private final Map<String, Object> savedSettings = new HashMap<>();
    private final List<HttpUrl> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseDelayed = new CountDownLatch(1);
    private final AtomicInteger delayedStarted = new AtomicInteger();
    private final AtomicInteger delayedReturned = new AtomicInteger();
    private final AtomicInteger delayedCancelled = new AtomicInteger();
    private final Map<Map<Object, Object>, Map<Object, Object>> savedCaches = new IdentityHashMap<>();
    private OkHttpClient originalClient, originalTmdbClient, fixtureClient;
    private volatile boolean pagination, rapidFilters, delayedGenres;
    private byte[] artwork;
    private boolean prepared;

    @Before public void setup() {
        String pkg = instrumentation.getTargetContext().getPackageName();
        assertTrue("Disposable validation package only", pkg.equals("com.fongmi.android.tv.preview")
                || pkg.equals("com.fongmi.android.tv.sourceprobe"));
        assertEquals("leanback", BuildConfig.FLAVOR_mode);
        assertFalse("Build the fixture variant with a non-secret TMDB_API_KEY", BuildConfig.TMDB_API_KEY.isEmpty());
        artwork = artwork();
        main(() -> {
            for (String key : SETTINGS) if (Prefers.getPrefers().contains(key)) savedSettings.put(key, Prefers.getPrefers().getAll().get(key));
            originalClient = OkHttp.client();
            originalTmdbClient = (OkHttpClient) field(TmdbNetwork.class, "client");
            fixtureClient = new OkHttpClient.Builder().addInterceptor(chain -> {
                HttpUrl url = chain.request().url();
                if (url.encodedPath().contains("/t/p/")) return new Response.Builder().request(chain.request())
                        .protocol(Protocol.HTTP_1_1).code(200).message("Fixture artwork")
                        .body(ResponseBody.create(artwork, MediaType.get("image/png"))).build();
                requests.add(url);
                boolean hold = pagination && url.encodedPath().endsWith("/movie/top_rated") && "2".equals(url.queryParameter("page"))
                        || delayedGenres && url.encodedPath().endsWith("/genre/movie/list")
                        || rapidFilters && url.encodedPath().endsWith("/discover/movie")
                        && !"popularity.desc".equals(url.queryParameter("sort_by"));
                if (hold) {
                    delayedStarted.incrementAndGet();
                    try {
                        if (!releaseDelayed.await(25, TimeUnit.SECONDS)) throw new IOException("Fixture release timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Fixture interrupted", error);
                    }
                    if (chain.call().isCanceled()) delayedCancelled.incrementAndGet();
                    delayedReturned.incrementAndGet();
                }
                String body;
                try { body = response(url); }
                catch (Exception error) { throw new IOException("Invalid wall fixture", error); }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("Wall fixture").body(ResponseBody.create(body, MediaType.get("application/json"))).build();
            }).build();
            fixtureClient.dispatcher().setMaxRequestsPerHost(12);
            prepared = true;
            saveCache(DiscoverApi.class, "CACHE");
            saveCache(RexFeedLoader.class, "CACHE");
            Setting.putTmdbProxyUrl("https://wall-fixture.invalid/");
            Setting.putTmdbConnectionMode(Setting.TMDB_CUSTOM_PROXY);
            Setting.putBridgeSk("");
            setField(OkHttp.get(), "client", fixtureClient);
            setField(TmdbNetwork.class, "client", fixtureClient);
        });
    }

    @After public void cleanup() {
        if (!prepared) return;
        releaseDelayed.countDown();
        main(() -> {
            for (int i = launched.size() - 1; i >= 0; i--) if (!launched.get(i).isDestroyed()) launched.get(i).finish();
            fixtureClient.dispatcher().cancelAll();
        });
        try {
            await(() -> launched.stream().allMatch(Activity::isDestroyed), "validation activities close");
            await(() -> fixtureClient.dispatcher().runningCallsCount() == 0 && fixtureClient.dispatcher().queuedCallsCount() == 0,
                    "fixture calls close before restoring transports");
        } finally {
            main(() -> {
                setField(OkHttp.get(), "client", originalClient);
                setField(TmdbNetwork.class, "client", originalTmdbClient);
                fixtureClient.connectionPool().evictAll();
                for (Map.Entry<Map<Object, Object>, Map<Object, Object>> saved : savedCaches.entrySet()) {
                    saved.getKey().clear(); saved.getKey().putAll(saved.getValue());
                }
                SharedPreferences.Editor editor = Prefers.getPrefers().edit();
                for (String key : SETTINGS) {
                    Object value = savedSettings.get(key);
                    if (value instanceof String text) editor.putString(key, text);
                    else if (value instanceof Integer mode) editor.putInt(key, mode);
                    else editor.remove(key);
                }
                assertTrue(editor.commit());
            });
        }
    }

    @Test(timeout = 60000)
    public void paginationFillsTheFocusedTailRowWithoutDuplicateItemsOrFocusLoss() throws Exception {
        pagination = true;
        RexSectionActivity activity = wall("top_movie");
        await(() -> items(activity).size() == 20, "first twenty posters");
        ArrayObjectAdapter tail = value(() -> row(activity, 3));
        assertEquals(2, value(tail::size).intValue());
        VerticalGridView grid = value(() -> activity.findViewById(R.id.recycler));
        await(() -> grid.hasFocus() && grid.getSelectedPosition() == 0, "initial focus request completes before selecting the page boundary");
        main(() -> grid.setSelectedPosition(3));
        await(() -> horizontal(grid, 3) != null, "tail row is attached");
        HorizontalGridView posters = value(() -> horizontal(grid, 3));
        main(() -> posters.setSelectedPosition(1));
        await(() -> posters.findViewHolderForAdapterPosition(1) != null, "twentieth poster is attached");
        View card = value(() -> posters.findViewHolderForAdapterPosition(1).itemView);
        main(() -> assertTrue(card.requestFocus()));
        await(card::hasFocus, "twentieth poster has actual focus");
        main(() -> ((CustomScroller) field(activity, "scroller")).checkMore());
        await(() -> delayedStarted.get() > 0, "second page request is held at the real HTTP boundary");
        capture(activity, "wall-pagination-before");
        releaseDelayed.countDown();
        await(() -> items(activity).size() == 39, "overlapping second page is deduplicated");
        main(() -> {
            assertSame("Append to the existing row adapter instead of creating a page-shaped gap", tail, row(activity, 3));
            assertEquals(6, tail.size());
            for (int i = 0; i < 6; i++) assertEquals("Every row before the final row is full", 6, row(activity, i).size());
            assertEquals(3, row(activity, 6).size());
            assertEquals(39, items(activity).stream().map(Vod::getId).distinct().count());
            assertTrue("Appending cannot send focus back to the first page", card.hasFocus());
            assertEquals(3, grid.getSelectedPosition());
            assertEquals(1, posters.getSelectedPosition());
        });
        capture(activity, "wall-pagination-filled");
    }

    @Test(timeout = 60000)
    public void lateGenreChoicesKeepTheFocusedLogicalFilterGroup() throws Exception {
        delayedGenres = true;
        RexSectionActivity activity = wall("top_movie");
        await(() -> items(activity).size() == 20 && panel(activity).getVisibleRows().equals(List.of(3, 4, 5)), "wall renders while genre metadata is pending");
        JetStreamChipRow summary = value(() -> activity.findViewById(R.id.summary));
        main(() -> {
            summary.setFocusedPosition(1);
            assertTrue(summary.requestFocus());
        });
        await(() -> summary.hasFocus() && summary.getFocusedPosition() == 1, "year filter has actual focus");
        releaseDelayed.countDown();
        await(() -> panel(activity).getVisibleRows().equals(List.of(2, 3, 4, 5)) && summary.getFocusedPosition() == 2,
                "new genre group cannot change the focused year group");
        main(() -> {
            assertTrue(summary.hasFocus());
            assertEquals(4, panel(activity).getVisibleRows().get(summary.getFocusedPosition()).intValue());
        });
        capture(activity, "wall-late-genres-focus");
    }

    @Test(timeout = 90000)
    public void sparseSummaryChipsSendConstrainedQueriesAndRejectLateFilterResponses() throws Exception {
        rapidFilters = true;
        RexSectionActivity activity = wall("top_movie");
        await(() -> items(activity).size() == 20 && panel(activity).getVisibleRows().equals(List.of(2, 3, 4, 5)), "sparse TMDB rows are ready");
        choose(activity, 2, "28");
        await(() -> delayedStarted.get() == 1, "genre request starts before changing region");
        choose(activity, 3, "US");
        await(() -> delayedStarted.get() == 2, "region request starts before changing year");
        choose(activity, 4, String.valueOf(LocalDate.now().getYear()));
        await(() -> delayedStarted.get() == 3, "year request starts before changing sort");
        choose(activity, 5, "popular");
        await(() -> items(activity).size() == 6 && items(activity).get(0).getName().equals("Selected film 1"), "latest constrained query reaches the wall");
        HttpUrl latest = lastUrl("/discover/movie");
        assertEquals("28", latest.queryParameter("with_genres"));
        assertEquals("US", latest.queryParameter("with_origin_country"));
        String year = String.valueOf(LocalDate.now().getYear());
        assertEquals(year + "-01-01", latest.queryParameter("primary_release_date.gte"));
        assertEquals(year + "-12-31", latest.queryParameter("primary_release_date.lte"));
        assertEquals("popularity.desc", latest.queryParameter("sort_by"));
        assertEquals("100", latest.queryParameter("vote_count.gte"));
        main(() -> assertTrue("Results must not steal focus from the active chips", activity.findViewById(R.id.filterContainer).hasFocus()));
        releaseDelayed.countDown();
        await(() -> delayedReturned.get() == 3 && fixtureClient.dispatcher().runningCallsCount() == 0, "cancelled responses return after the selected response");
        assertEquals(3, delayedCancelled.get());
        main(() -> {
            assertEquals(6, items(activity).size());
            assertEquals("Selected film 1", items(activity).get(0).getName());
            assertEquals(5, panel(activity).getExpandedRow());
        });
        capture(activity, "wall-filters-expanded");
    }

    @Test(timeout = 90000)
    public void regionalAndDoubanWallsFilterOnlyLoadedMetadataWithoutRefetching() throws Exception {
        for (String key : List.of("cn_series", "douban_movie")) {
            RexSectionActivity activity = wall(key);
            await(() -> items(activity).size() == 6, key + " feed is loaded");
            main(() -> assertEquals("No region or media filter can be invented for a fixed feed", List.of(2, 4, 5), panel(activity).getVisibleRows()));
            int feedRequests = feedCount();
            choose(activity, 2, "动作");
            await(() -> items(activity).size() == 3, "local genre uses actual feed metadata");
            choose(activity, 4, "2026");
            await(() -> items(activity).size() == 2, "local year combines with genre");
            choose(activity, 5, "rating");
            main(() -> {
                assertEquals("tmdb:tv:105", items(activity).get(0).getId());
                assertEquals("tmdb:tv:101", items(activity).get(1).getId());
            });
            assertEquals("Filtering an already fetched feed must not issue more HTTP", feedRequests, feedCount());
            capture(activity, "wall-local-filter-" + key);
            finish(activity);
        }
    }

    @Test(timeout = 60000)
    public void animeOffersOnlyRealUpdateDaysAndTrendingPreservesTheDailyMediaEndpoint() throws Exception {
        RexSectionActivity anime = wall("anime_daily");
        await(() -> items(anime).size() == 4 && panel(anime).getVisibleRows().contains(1), "anime weekly metadata");
        int count = feedCount();
        choose(anime, 1, "星期二");
        await(() -> items(anime).size() == 2, "actual Tuesday group");
        assertEquals(count, feedCount());
        main(() -> {
            assertEquals(anime.getString(R.string.poster_wall_update_day), panel(anime).getLabel(1));
            assertFalse(panel(anime).getVisibleRows().contains(3));
            assertTrue(items(anime).stream().allMatch(item -> item.getName().startsWith("Tuesday")));
        });
        capture(anime, "wall-anime-update-day");
        finish(anime);
        RexSectionActivity trending = wall("trending_day");
        await(() -> !items(trending).isEmpty(), "daily trending items");
        main(() -> assertEquals(List.of(1), panel(trending).getVisibleRows()));
        choose(trending, 1, "tv");
        await(() -> lastUrlOrNull("/trending/tv/day") != null && items(trending).stream().allMatch(item -> item.getId().startsWith("tmdb:tv:")), "TV daily trending endpoint");
        HttpUrl url = lastUrl("/trending/tv/day");
        assertNull(url.queryParameter("with_genres"));
        assertNull(url.queryParameter("with_origin_country"));
        assertNull(url.queryParameter("sort_by"));
        capture(trending, "wall-trending-media");
    }

    @Test(timeout = 120000)
    public void everyHomeWallLoadsWithOnlyItsApplicableFilterRows() throws Exception {
        assertEquals(12, RexSections.home().size());
        for (RexSection section : RexSections.home()) {
            RexSectionActivity activity = wall(section.getKey());
            await(() -> !items(activity).isEmpty(), "populated " + section.getKey() + " wall");
            main(() -> {
                List<Integer> visible = panel(activity).getVisibleRows();
                if (section.getKey().equals("trending_day")) assertEquals(List.of(1), visible);
                else if (section.getQuery() != null) {
                    assertTrue(visible.contains(2)); assertTrue(visible.contains(3)); assertTrue(visible.contains(5));
                    assertEquals(!section.getKey().equals("now_playing"), visible.contains(4));
                } else {
                    assertFalse(visible.contains(3)); assertTrue(visible.contains(4)); assertTrue(visible.contains(5));
                    assertEquals(section.getKey().equals("anime_daily"), visible.contains(1));
                }
            });
            finish(activity);
        }
    }

    @Test(timeout = 90000)
    public void facetResultsPreserveTheirFixedConstraintAndMenuReachesRecommendationFilters() throws Exception {
        for (String kind : List.of(DiscoverFacet.GENRE, DiscoverFacet.COMPANY, DiscoverFacet.PROVIDER)) {
            Intent intent = new Intent(instrumentation.getTargetContext(), DiscoverResultActivity.class)
                    .putExtra("facet", new DiscoverFacet(kind, kind.equals(DiscoverFacet.GENRE) ? "28" : "420", "Fixture " + kind, ""));
            DiscoverResultActivity activity = start(intent, DiscoverResultActivity.class);
            await(() -> !items(activity).isEmpty() && panel(activity).getVisibleRows().contains(3), "facet wall filters");
            choose(activity, 3, "US");
            await(() -> {
                HttpUrl current = lastUrlOrNull("/discover/movie");
                return current != null && "US".equals(current.queryParameter("with_origin_country")) && !items(activity).isEmpty();
            }, "facet query receives the selected origin country");
            HttpUrl url = lastUrl("/discover/movie");
            if (kind.equals(DiscoverFacet.GENRE)) assertEquals("28", url.queryParameter("with_genres"));
            else if (kind.equals(DiscoverFacet.COMPANY)) assertEquals("420", url.queryParameter("with_companies"));
            else {
                assertEquals("420", url.queryParameter("with_watch_providers"));
                assertEquals("CN", url.queryParameter("watch_region"));
            }
            capture(activity, "wall-facet-" + kind);
            finish(activity);
        }
        DiscoverActivity recommendations = start(new Intent(instrumentation.getTargetContext(), DiscoverActivity.class), DiscoverActivity.class);
        await(recommendations::hasWindowFocus, "recommendations window");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
        await(() -> {
            JetStreamChipRow summary = recommendations.findViewById(R.id.summary);
            return summary != null && summary.hasFocus();
        }, "MENU reaches the existing recommendation filter summary");
        capture(recommendations, "recommendation-filter-menu");
    }

    private RexSectionActivity wall(String key) {
        return start(new Intent(instrumentation.getTargetContext(), RexSectionActivity.class).putExtra("section_key", key), RexSectionActivity.class);
    }

    private <T extends Activity> T start(Intent intent, Class<T> type) {
        T activity = type.cast(instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        launched.add(activity);
        await(activity::hasWindowFocus, "activity window becomes focused");
        return activity;
    }

    private void finish(Activity activity) {
        main(activity::finish);
        await(activity::isDestroyed, "wall finishes before opening the next wall");
    }

    private void choose(Activity activity, int row, String value) {
        String group = activity.getString(switch (row) {
            case 2 -> R.string.discover_filter_genre;
            case 3 -> R.string.discover_filter_region;
            case 4 -> R.string.discover_filter_year;
            case 5 -> R.string.discover_filter_sort;
            default -> R.string.discover_filter_media;
        });
        String summary = value(() -> {
            DiscoverFilterPanel panel = panel(activity);
            if (!panel.getLabel(row).isEmpty()) return panel.getRow(row).stream().filter(option -> option.isSelected()).findFirst().orElseThrow().getLabel();
            if (row == 1) return panel.getRow(row).stream().filter(option -> option.isSelected()).findFirst().orElseThrow().getLabel();
            com.fongmi.android.tv.bean.DiscoverFilterOption selected = panel.getRow(row).stream().filter(option -> option.isSelected()).findFirst().orElse(null);
            if (selected == null || (row == 2 || row == 3) && selected.getValue().isEmpty()
                    || row == 4 && selected.getStartDate().isEmpty() && selected.getEndDate().isEmpty()) return group;
            return group + " · " + selected.getLabel();
        });
        tapText(value(() -> activity.findViewById(R.id.summary)), summary);
        await(() -> panel(activity).getExpandedRow() == row, "summary maps to actual sparse filter row " + row);
        String label = value(() -> panel(activity).getRow(row).stream().filter(option -> value.equals(option.getValue())).findFirst().orElseThrow().getLabel());
        tapText(value(() -> activity.findViewById(R.id.options)), label);
        await(() -> panel(activity).getRow(row).stream().anyMatch(option -> value.equals(option.getValue()) && option.isSelected()), "selected actual chip " + row);
    }

    private static ArrayObjectAdapter row(Activity activity, int position) {
        return (ArrayObjectAdapter) ((ListRow) ((ArrayObjectAdapter) field(activity, "mAdapter")).get(position)).getAdapter();
    }

    private static List<Vod> items(Activity activity) {
        List<Vod> values = new ArrayList<>();
        ArrayObjectAdapter adapter = (ArrayObjectAdapter) field(activity, "mAdapter");
        for (int i = 0; i < adapter.size(); i++) {
            if (!(adapter.get(i) instanceof ListRow row)) continue;
            for (int j = 0; j < row.getAdapter().size(); j++) if (row.getAdapter().get(j) instanceof Vod item) values.add(item);
        }
        return values;
    }

    private static DiscoverFilterPanel panel(Activity activity) {
        return (DiscoverFilterPanel) field(field(activity, "filters"), "panel");
    }

    private static HorizontalGridView horizontal(RecyclerView page, int position) {
        RecyclerView.ViewHolder holder = page.findViewHolderForAdapterPosition(position);
        return holder == null ? null : horizontal(holder.itemView);
    }

    private static HorizontalGridView horizontal(View view) {
        if (view instanceof HorizontalGridView grid) return grid;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            HorizontalGridView found = horizontal(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private void tapText(View host, String text) {
        await(() -> host != null && host.isShown() && host.getWidth() > 0 && host.getHeight() > 0, "chip row is laid out");
        Rect hostBounds = value(() -> { Rect bounds = new Rect(); assertTrue(host != null && host.getGlobalVisibleRect(bounds)); return bounds; });
        Rect target = null;
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline && target == null) {
            if (Build.VERSION.SDK_INT >= 33) instrumentation.getUiAutomation().clearCache();
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try { target = textBounds(root, text, hostBounds, 0); } finally { root.recycle(); }
            }
            if (target == null) SystemClock.sleep(50);
        }
        assertNotNull("Visible Compose chip " + text, target);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, target.exactCenterX(), target.exactCenterY(), 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { instrumentation.sendPointerSync(down); } finally { down.recycle(); }
        SystemClock.sleep(40);
        MotionEvent up = MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY(), 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { instrumentation.sendPointerSync(up); } finally { up.recycle(); }
    }

    private static Rect textBounds(AccessibilityNodeInfo node, String text, Rect host, int depth) {
        if (depth > 50) return null;
        if (text.contentEquals(node.getText() == null ? "" : node.getText()) && node.isVisibleToUser()) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty() && host.contains(bounds.centerX(), bounds.centerY())) return bounds;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            Rect found;
            try { found = textBounds(child, text, host, depth + 1); } finally { child.recycle(); }
            if (found != null) return found;
        }
        return null;
    }

    private void capture(Activity activity, String name) throws Exception {
        SystemClock.sleep(200);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        File directory = new File(activity.getExternalFilesDir(null), "tv-polish-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }
    }

    private String response(HttpUrl url) throws Exception {
        String path = url.encodedPath();
        if (path.endsWith("/genre/movie/list") || path.endsWith("/genre/tv/list")) return "{\"genres\":[{\"id\":28,\"name\":\"动作\"},{\"id\":18,\"name\":\"剧情\"}]}";
        if (path.endsWith("/watch/providers/movie")) return "{\"results\":[{\"provider_id\":420,\"provider_name\":\"Fixture Stream\",\"logo_path\":\"/provider.png\"}]}";
        if (path.contains("/3/")) {
            boolean selected = path.endsWith("/discover/movie") && rapidFilters;
            boolean obsolete = selected && !"popularity.desc".equals(url.queryParameter("sort_by"));
            String type = path.contains("/tv/") || path.endsWith("/tv") ? "tv" : "movie";
            int page = "2".equals(url.queryParameter("page")) ? 2 : 1;
            JSONArray results = new JSONArray();
            int count = selected ? 6 : 20;
            for (int i = 1; i <= count; i++) {
                int number = page == 2 ? 19 + i : i;
                String title = (obsolete ? "Obsolete film " : selected ? "Selected film " : "Wall film ") + number;
                results.put(new JSONObject().put("id", (obsolete ? 700000 : selected ? 800000 : 900000) + number)
                        .put("media_type", type).put("title", title).put("name", title)
                        .put("poster_path", "/wall-" + number + ".png").put("backdrop_path", "/wall-" + number + ".png")
                        .put("release_date", "2026-01-01").put("first_air_date", "2026-01-01")
                        .put("vote_average", 8.5).put("genre_ids", new JSONArray().put(28)));
            }
            return new JSONObject().put("page", page).put("total_pages", selected ? 1 : 2).put("results", results).toString();
        }
        if (path.contains("/regional-tv/latest.json")) return new JSONObject().put("schemaVersion", 1).put("date", "2026-11-01").toString();
        if (path.contains("/regional-tv/")) {
            boolean variety = path.contains("variety");
            String region = path.contains("korean") ? "korean" : path.contains("western") ? "western" : "chinese";
            JSONArray feed = new JSONArray();
            for (int i = 0; i < (variety ? 20 : 6); i++) {
                JSONObject info = localItem(101 + i, "Feed film " + i, i % 6);
                info.put("genreTitle", i % 6 == 0 || i % 6 == 2 || i % 6 == 4 ? "动作" : "剧情");
                feed.put(info);
            }
            return new JSONObject().put("schemaVersion", 1).put("region", region).put("category", variety ? "variety" : "series").put("items", feed).toString();
        }
        if (path.contains("/douban/")) {
            JSONArray feed = new JSONArray();
            for (int i = 0; i < 6; i++) feed.put(new JSONObject().put("title", "Feed film " + i).put("tmdb_info", localItem(101 + i, "Feed film " + i, i))
                    .put("genres", new JSONArray().put(i == 0 || i == 2 || i == 4 ? "动作" : "剧情")));
            return new JSONObject().put("items", feed).toString();
        }
        if (path.contains("/anime/")) return new JSONObject()
                .put("星期一", new JSONArray().put(new JSONObject().put("tmdb_info", localItem(201, "Monday anime 1", 0)))
                        .put(new JSONObject().put("tmdb_info", localItem(202, "Monday anime 2", 1))))
                .put("星期二", new JSONArray().put(new JSONObject().put("tmdb_info", localItem(203, "Tuesday anime 1", 2)))
                        .put(new JSONObject().put("tmdb_info", localItem(204, "Tuesday anime 2", 3)))).toString();
        return "{}";
    }

    private static JSONObject localItem(int id, String title, int index) throws Exception {
        int[] years = {2026, 2025, 2025, 2024, 2026, 2026};
        double[] ratings = {8.0, 9.0, 7.0, 9.5, 8.7, 6.5};
        return new JSONObject().put("id", id).put("mediaType", "tv").put("title", title)
                .put("posterPath", "/local-" + id + ".png").put("backdropPath", "/local-" + id + ".png")
                .put("releaseDate", years[index] + "-01-01").put("rating", ratings[index]).put("genreTitle", "动画");
    }

    private int feedCount() { return (int) requests.stream().filter(url -> url.encodedPath().contains("/datas/rex/")).count(); }
    private HttpUrl lastUrl(String suffix) { HttpUrl url = lastUrlOrNull(suffix); assertNotNull("Expected metadata path " + suffix, url); return url; }
    private HttpUrl lastUrlOrNull(String suffix) {
        for (int i = requests.size() - 1; i >= 0; i--) if (requests.get(i).encodedPath().endsWith(suffix)) return requests.get(i);
        return null;
    }

    private byte[] artwork() {
        Bitmap bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.rgb(44, 64, 96));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.rgb(168, 196, 230));
        canvas.drawCircle(60, 62, 30, paint); paint.setColor(Color.rgb(230, 237, 248)); paint.setTextSize(18);
        canvas.drawText("TV fixture", 17, 126, paint);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); bitmap.recycle();
        return stream.toByteArray();
    }

    @SuppressWarnings("unchecked") private void saveCache(Class<?> type, String name) {
        Map<Object, Object> cache = (Map<Object, Object>) field(type, name);
        savedCaches.put(cache, new HashMap<>(cache)); cache.clear();
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true); return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true); field.set(owner instanceof Class<?> ? null : owner, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void await(BooleanSupplier predicate, String reason) {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (value(predicate::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for " + reason);
    }

    private void main(Runnable action) { value(() -> { action.run(); return null; }); }
    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }
}
