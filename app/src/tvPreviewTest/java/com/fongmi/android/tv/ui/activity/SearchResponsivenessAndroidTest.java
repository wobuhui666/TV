package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.Window;
import android.widget.EditText;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLibraryApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.source.LibrarySearchIndex;
import com.fongmi.android.tv.ui.adapter.LibrarySearchAdapter;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

/** Public search journeys with large offline responses; handler delay excludes adb command cost. */
@RunWith(AndroidJUnit4.class)
public final class SearchResponsivenessAndroidTest {
    private static final int WORKS = 240, SOURCES = 8;
    private static final String[] KEYS = {"hot", "keyword", "tmdb_connection_mode", "tmdb_proxy_url", "incognito", "tv_experience_mode_v1"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<Activity> activities = new ArrayList<>();
    private final Map<String, Object> saved = new HashMap<>();
    private final CountDownLatch releaseLookup = new CountDownLatch(1), releaseSuggestions = new CountDownLatch(1);
    private final AtomicInteger apiRequests = new AtomicInteger();
    private OkHttpClient original, originalTmdb, fixture;
    private Object originalSites, originalHome;
    private String worksJson, sourcesJson, wordsJson, smallWordsJson;
    private byte[] artwork;
    private long inputStartupMs;
    private boolean holdLookup, holdSuggestions, failSuggestions;

    @Before public void setup() throws Exception {
        assertEquals("Only the disposable preview", "com.fongmi.android.tv.preview", instrumentation.getTargetContext().getPackageName());
        JSONArray works = new JSONArray(), sources = new JSONArray(), words = new JSONArray();
        for (int i = 0; i < WORKS; i++) {
            String name = "压测作品" + i;
            works.put(new JSONObject().put("id", 20000 + i).put("media_type", "movie").put("title", name)
                    .put("release_date", "2026-01-01").put("poster_path", "/movie-backdrop.jpg"));
            sources.put(new JSONObject().put("vod_id", "work-" + i).put("vod_name", name).put("vod_year", "2026")
                    .put("type_name", "电影").put("vod_pic", "https://search-perf.invalid/t/p/w342/movie-backdrop.jpg"));
        }
        for (int i = 0; i < 5000; i++) words.put(new JSONObject().put("title", "缓存热搜" + i));
        worksJson = new JSONObject().put("page", 1).put("total_pages", 1).put("results", works).toString();
        sourcesJson = new JSONObject().put("list", sources).toString();
        wordsJson = new JSONObject().put("data", words).toString();
        JSONArray small = new JSONArray(); for (int i = 0; i < 30; i++) small.put(new JSONObject().put("title", "普通热搜" + i));
        smallWordsJson = new JSONObject().put("data", small).toString();
        File root = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "library-fixtures");
        artwork = Files.readAllBytes(new File(root, "movie-backdrop.jpg").toPath());
        main(() -> {
            for (String key : KEYS) if (Prefers.getPrefers().contains(key)) saved.put(key, Prefers.getPrefers().getAll().get(key));
            original = OkHttp.client(); originalTmdb = (OkHttpClient) field(TmdbNetwork.class, "client");
            originalSites = field(VodConfig.get(), "sites"); originalHome = field(VodConfig.get(), "home");
            List<Site> sites = new ArrayList<>();
            for (int i = 0; i < SOURCES; i++) {
                Site site = Site.get("bulk-" + i, "压测源" + i);
                site.setApi("https://search-perf.invalid/bulk-" + i); set(site, "type", 4); sites.add(site);
            }
            set(VodConfig.get(), "sites", sites); set(VodConfig.get(), "home", sites.get(0));
            ExperienceSettings.putMode(ExperienceMode.LIBRARY); Prefers.put("incognito", true);
            Setting.putKeyword("[]"); Setting.putHot("{\"data\":[]}");
            Setting.putTmdbConnectionMode(Setting.TMDB_CUSTOM_PROXY); Setting.putTmdbProxyUrl("https://search-perf.invalid/");
            fixture = new OkHttpClient.Builder().addInterceptor(chain -> {
                String host = chain.request().url().host(), path = chain.request().url().encodedPath();
                if (host.equals("suggest.video.iqiyi.com") || host.equals("hot.api.coolmarket.eu.org")) {
                    if (holdSuggestions) waitFor(releaseSuggestions);
                    if (failSuggestions) throw new IOException("Offline unavailable suggestion service");
                    return response(chain.request(), smallWordsJson.getBytes(StandardCharsets.UTF_8), "application/json");
                }
                if (!host.equals("search-perf.invalid")) throw new IOException("Offline search performance fixture");
                if (path.contains("/t/p/")) return response(chain.request(), artwork, "image/jpeg");
                if (holdLookup) waitFor(releaseLookup);
                if (path.contains("/search/")) { apiRequests.incrementAndGet(); return response(chain.request(), worksJson.getBytes(StandardCharsets.UTF_8), "application/json"); }
                if (path.startsWith("/bulk-")) return response(chain.request(), sourcesJson.getBytes(StandardCharsets.UTF_8), "application/json");
                throw new IOException("Unknown offline search path");
            }).build();
            set(OkHttp.get(), "client", fixture); set(TmdbNetwork.class, "client", fixture); TmdbLibraryApi.clearCache();
        });
    }

    @After public void cleanup() throws Exception {
        releaseLookup.countDown(); releaseSuggestions.countDown();
        main(() -> {
            for (Activity activity : activeActivities()) if (!activities.contains(activity)) activities.add(activity);
            for (int i = activities.size() - 1; i >= 0; i--) if (!activities.get(i).isDestroyed()) activities.get(i).finish();
        });
        await("Search activities close", () -> activities.stream().allMatch(Activity::isDestroyed), 10000);
        await("Offline requests finish", () -> fixture.dispatcher().runningCallsCount() == 0, 10000);
        main(() -> {
            set(OkHttp.get(), "client", original); set(TmdbNetwork.class, "client", originalTmdb);
            set(VodConfig.get(), "sites", originalSites); set(VodConfig.get(), "home", originalHome);
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            for (String key : KEYS) {
                Object value = saved.get(key);
                if (value instanceof String text) editor.putString(key, text);
                else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                else if (value instanceof Integer number) editor.putInt(key, number);
                else editor.remove(key);
            }
            assertTrue(editor.commit()); TmdbLibraryApi.clearCache();
        });
    }

    @Test public void largeSourceResultsKeepMainResponsiveAndOnlyCreateVisiblePosters() throws Exception {
        holdLookup = true;
        SearchActivity input = openInput();
        LibrarySearchActivity results;
        try (Monitor navigation = new Monitor("result-navigation", input)) {
            navigation.phase = "submit-and-switch";
            long submitted = SystemClock.uptimeMillis();
            main(() -> {
                EditText keyword = input.findViewById(R.id.keyword);
                keyword.getText().replace(0, keyword.length(), "压测作品");
                keyword.setSelection(keyword.length()); input.onSearch();
            });
            results = awaitResults();
            await("The old input activity finishes its STOP transition", () -> {
                final boolean[] stopped = new boolean[1];
                main(() -> stopped[0] = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED).contains(input));
                return stopped[0];
            }, 15000);
            navigation.phaseElapsedMs = SystemClock.uptimeMillis() - submitted;
        }
        try (Monitor monitor = new Monitor("large-results", results)) {
            monitor.phase = "data-arrival";
            releaseLookup.countDown();
            try { await("Large catalog and all eight sources settle", () -> { monitor.observeSearchProgress(results); return settled(results); }, 45000); }
            finally { monitor.captureSearchState(results); }
            RecyclerView recycler = results.findViewById(R.id.results);
            await("Recycled grid receives its catalog", () -> recycler.getAdapter().getItemCount() >= WORKS, 10000);
            await("The visible grid finishes layout", () -> recycler.getChildCount() > 0 && !recycler.isComputingLayout(), 10000);
            SystemClock.sleep(500);
            LibrarySearchIndex.Snapshot snapshot = (LibrarySearchIndex.Snapshot) field(results, "snapshot");
            assertEquals(WORKS, snapshot.works().size());
            assertTrue("Sources are attached to the works", snapshot.resourceCount() >= WORKS);
            assertTrue("Identical raw metadata is prepared once across sources", ((Map<?, ?>) field(field(results, "index"), "preparedSources")).size() <= WORKS);
            main(() -> {
                assertTrue("Large result lists create visible cards rather than every poster", ((LibrarySearchAdapter) recycler.getAdapter()).getCreatedCards() < 64);
                assertTrue("Only the viewport is attached", recycler.getChildCount() < 40);
            });
            monitor.assertResponsive();
        }
        capture("library-polish-search-results");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Back returns to the original input", () -> results.isDestroyed() && input.hasWindowFocus(), 10000);
    }

    @Test public void cachedWordsAndRapidInputStayUsableWhenSuggestionsNeverArrive() throws Exception {
        JSONArray history = new JSONArray(); for (int i = 0; i < 5000; i++) history.put("历史关键词" + i);
        main(() -> { Setting.putHot(wordsJson); Setting.putKeyword(history.toString()); });
        holdSuggestions = failSuggestions = true;
        SearchActivity input = openInput();
        try (Monitor monitor = new Monitor("cached-input", input)) {
            await("Cached lists are safely bounded", () -> {
                RecyclerView words = input.findViewById(R.id.wordRecycler), records = input.findViewById(R.id.recordRecycler);
                return words.getAdapter().getItemCount() == 30
                        && records.getAdapter().getItemCount() == 9;
            }, 10000);
            RecyclerView keyboard = input.findViewById(R.id.keyboard);
            await("Actual television keyboard letters are laid out", () -> keyboard.findViewHolderForAdapterPosition(7) != null
                    && keyboard.findViewHolderForAdapterPosition(8) != null && keyboard.findViewHolderForAdapterPosition(9) != null, 10000);
            monitor.phase = "typing";
            main(() -> {
                for (int position : new int[]{7, 8, 9}) assertTrue("Production keyboard handles the letter", keyboard.findViewHolderForAdapterPosition(position).itemView.performClick());
                EditText keyword = input.findViewById(R.id.keyword);
                assertEquals("Three real keyboard edits are retained", 3, keyword.length());
                assertEquals(keyword.length(), keyword.getSelectionStart());
            });
            monitor.phase = "waiting-for-suggestions";
            SystemClock.sleep(1000);
            monitor.assertResponsive();
        }
        capture("library-polish-search-input");
        main(() -> {
            RecyclerView keyboard = input.findViewById(R.id.keyboard);
            EditText keyword = input.findViewById(R.id.keyword);
            assertTrue(keyboard.findViewHolderForAdapterPosition(1).itemView.performClick());
            assertEquals(2, keyword.getSelectionStart());
            assertTrue(keyboard.findViewHolderForAdapterPosition(3).itemView.performClick());
            assertEquals("Backspace edits the middle without replacing the buffer", 2, keyword.length());
            assertEquals(1, keyword.getSelectionStart());
            assertTrue(keyboard.findViewHolderForAdapterPosition(2).itemView.performClick());
            assertEquals(2, keyword.getSelectionStart());
            assertTrue(keyboard.findViewHolderForAdapterPosition(3).itemView.performLongClick());
            assertEquals(0, keyword.length()); assertEquals(0, keyword.getSelectionStart());
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Unreachable suggestions cannot prevent Back", input::isDestroyed, 10000);
    }

    @Test public void tmdbNetworkAndCachedCallbacksUseMainAndCancelledCacheDoesNotDeliver() throws Exception {
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1);
        AtomicInteger wrongThread = new AtomicInteger(), cancelled = new AtomicInteger(), failures = new AtomicInteger(), successes = new AtomicInteger();
        TmdbLibraryApi.Listener<TmdbLibraryApi.Page> network = listener(first, wrongThread, failures, successes);
        TmdbLibraryApi.Listener<TmdbLibraryApi.Page> cache = listener(second, wrongThread, failures, successes);
        main(() -> TmdbLibraryApi.search("线程检查", "", 1, BuildConfig.TMDB_API_KEY, new Object(), network));
        assertTrue("Network page completes", first.await(15, TimeUnit.SECONDS));
        main(() -> TmdbLibraryApi.search("线程检查", "", 1, BuildConfig.TMDB_API_KEY, new Object(), cache));
        assertTrue("Cached page completes", second.await(15, TimeUnit.SECONDS));
        Object cancelledTag = new Object();
        main(() -> {
            TmdbLibraryApi.search("线程检查", "", 1, BuildConfig.TMDB_API_KEY, cancelledTag, new TmdbLibraryApi.Listener<>() {
                @Override public void onSuccess(TmdbLibraryApi.Page page) { cancelled.incrementAndGet(); }
                @Override public void onError(Exception error) { cancelled.incrementAndGet(); }
            });
            TmdbLibraryApi.cancel(cancelledTag);
        });
        SystemClock.sleep(750);
        assertEquals("Cache avoids another network parse", 1, apiRequests.get());
        assertEquals("Callbacks always update UI on main", 0, wrongThread.get());
        assertEquals("Both network and cache actually return parsed pages", 2, successes.get());
        assertEquals("Threading checks are not error-only callbacks", 0, failures.get());
        assertEquals("Cancellation suppresses queued cached delivery", 0, cancelled.get());
    }

    @Test public void heartbeatProbeDetectsAControlledMainThreadBlock() throws Exception {
        try (Monitor monitor = new Monitor("probe-control", null)) {
            new Handler(Looper.getMainLooper()).post(() -> SystemClock.sleep(300));
            SystemClock.sleep(650);
            assertTrue("The monitor detects main-thread starvation independently of rendering", monitor.maxDelay() >= 200);
        }
    }

    private TmdbLibraryApi.Listener<TmdbLibraryApi.Page> listener(CountDownLatch done, AtomicInteger wrongThread, AtomicInteger failures, AtomicInteger successes) {
        return new TmdbLibraryApi.Listener<>() {
            @Override public void onSuccess(TmdbLibraryApi.Page page) { if (Looper.myLooper() != Looper.getMainLooper()) wrongThread.incrementAndGet(); successes.incrementAndGet(); done.countDown(); }
            @Override public void onError(Exception error) { if (Looper.myLooper() != Looper.getMainLooper()) wrongThread.incrementAndGet(); failures.incrementAndGet(); done.countDown(); }
        };
    }
    private SearchActivity openInput() {
        long started = SystemClock.uptimeMillis();
        List<Activity> previous = new ArrayList<>();
        main(() -> previous.addAll(activeActivities()));
        // Animated TV pages need not reach global UI idle. Match the production launch.
        main(() -> instrumentation.getTargetContext().startActivity(new Intent(instrumentation.getTargetContext(), SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        final SearchActivity[] opened = new SearchActivity[1];
        await("Input activity reaches RESUMED and owns its window", () -> {
            main(() -> { for (Activity activity : activeActivities()) if (activity instanceof SearchActivity input && !previous.contains(input)) opened[0] = input; });
            return opened[0] != null && opened[0].hasWindowFocus();
        }, 15000);
        activities.add(opened[0]); inputStartupMs = SystemClock.uptimeMillis() - started; return opened[0];
    }
    private void capture(String name) throws IOException {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("native_ui_evidence"))) return;
        assertTrue(name.matches("library-polish-[a-z0-9-]+"));
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Capture the already measured search window", screenshot);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)); output.getFD().sync();
        } finally { screenshot.recycle(); }
    }
    private LibrarySearchActivity awaitResults() {
        final LibrarySearchActivity[] result = new LibrarySearchActivity[1];
        await("Submitting a keyword enters the production library results page", () -> {
            main(() -> { for (Activity value : activeActivities()) if (value instanceof LibrarySearchActivity search) result[0] = search; });
            return result[0] != null && result[0].hasWindowFocus();
        }, 10000);
        activities.add(result[0]); return result[0];
    }
    private List<Activity> activeActivities() {
        List<Activity> values = new ArrayList<>();
        values.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED));
        values.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STARTED));
        return values;
    }
    private boolean settled(LibrarySearchActivity search) {
        return !(boolean) field(search, "sourceLoading") && !(boolean) field(search, "tmdbLoading")
                && !(boolean) field(search, "matching") && !(boolean) field(search, "renderPending");
    }
    private Response response(okhttp3.Request request, byte[] data, String type) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Offline search fixture")
                .body(ResponseBody.create(data, MediaType.get(type))).build();
    }
    private void waitFor(CountDownLatch release) throws IOException {
        try { if (!release.await(20, TimeUnit.SECONDS)) throw new IOException("Held search fixture deadline"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Cancelled fixture", interrupted); }
    }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private Object field(Object owner, String name) {
        try { java.lang.reflect.Field member = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name); member.setAccessible(true); return member.get(owner instanceof Class<?> ? null : owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void set(Object owner, String name, Object value) {
        try { java.lang.reflect.Field member = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name); member.setAccessible(true); member.set(owner instanceof Class<?> ? null : owner, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void await(String description, BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) { if (condition.getAsBoolean()) return; SystemClock.sleep(30); }
        fail(description);
    }

    private final class Monitor implements AutoCloseable {
        private final String name;
        private final Object token = new Object();
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        private final HandlerThread frameThread = new HandlerThread("search-frame-metrics");
        private final ConcurrentLinkedQueue<Long> pending = new ConcurrentLinkedQueue<>();
        private final List<Long> delays = Collections.synchronizedList(new ArrayList<>()), frames = Collections.synchronizedList(new ArrayList<>());
        private final List<String> stacks = Collections.synchronizedList(new ArrayList<>());
        private final List<JSONObject> stallEvents = Collections.synchronizedList(new ArrayList<>());
        private final List<JSONObject> searchProgress = new ArrayList<>();
        private final AtomicBoolean measuring = new AtomicBoolean(true);
        private final long started = SystemClock.uptimeMillis();
        private long frozenMax = -1;
        private JSONObject searchState;
        private long lastProgress;
        volatile String phase = "ready";
        long phaseElapsedMs;
        private final Activity activity;
        private final Window.OnFrameMetricsAvailableListener frameListener = (window, metrics, dropped) -> { if (measuring.get()) frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)); };

        Monitor(String name, Activity activity) {
            this.name = name; this.activity = activity; frameThread.start();
            if (activity != null) main(() -> activity.getWindow().addOnFrameMetricsAvailableListener(frameListener, new Handler(frameThread.getLooper())));
            watcher.scheduleAtFixedRate(() -> {
                if (!measuring.get()) return;
                long now = SystemClock.uptimeMillis(); Long oldest = pending.peek();
                if (oldest != null && now - oldest > 250 && stacks.size() < 5) {
                    StringBuilder stack = new StringBuilder(); for (StackTraceElement line : Looper.getMainLooper().getThread().getStackTrace()) stack.append(line).append('\n'); stacks.add(stack.toString());
                    try { stallEvents.add(new JSONObject().put("elapsedMs", now - started).put("oldestPendingMs", now - oldest).put("phase", phase)); }
                    catch (org.json.JSONException ignored) { }
                }
                if (!measuring.get()) return;
                pending.add(now);
                main.postAtTime(() -> { if (measuring.get()) delays.add(SystemClock.uptimeMillis() - now); pending.remove(now); }, token, now);
            }, 0, 50, TimeUnit.MILLISECONDS);
        }
        long maxDelay() { if (frozenMax >= 0) return frozenMax; synchronized (delays) { return delays.stream().mapToLong(Long::longValue).max().orElse(0); } }
        void finishMeasurement() {
            if (!measuring.getAndSet(false)) return;
            Long oldest = pending.peek();
            frozenMax = Math.max(maxDelay(), oldest == null ? 0 : SystemClock.uptimeMillis() - oldest);
            watcher.shutdownNow(); main.removeCallbacksAndMessages(token);
        }
        void captureSearchState(LibrarySearchActivity search) throws Exception {
            searchState = searchCounts(search);
            JSONObject workers = new JSONObject();
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                if (!entry.getKey().getName().contains("library-match")) continue;
                StringBuilder stack = new StringBuilder(); for (StackTraceElement line : entry.getValue()) stack.append(line).append('\n');
                workers.put(entry.getKey().getName(), stack.toString());
            }
            searchState.put("matchingWorkerStacks", workers);
        }
        void observeSearchProgress(LibrarySearchActivity search) {
            if (SystemClock.uptimeMillis() - lastProgress < 1000) return;
            lastProgress = SystemClock.uptimeMillis();
            try { searchProgress.add(searchCounts(search).put("elapsedMs", lastProgress - started)); }
            catch (org.json.JSONException error) { throw new AssertionError(error); }
        }
        JSONObject searchCounts(LibrarySearchActivity search) throws org.json.JSONException {
            JSONObject counts = new JSONObject();
            for (String flag : new String[]{"sourceLoading", "tmdbLoading", "matching", "matchScheduled", "renderPending"}) counts.put(flag, field(search, flag));
            LibrarySearchIndex.Snapshot snapshot = (LibrarySearchIndex.Snapshot) field(search, "snapshot");
            counts.put("publishedWorks", snapshot.works().size()).put("publishedResources", snapshot.resourceCount());
            Object index = field(search, "index");
            for (String map : new String[]{"works", "sources", "preparedSources"}) counts.put(map + "Count", ((Map<?, ?>) field(index, map)).size());
            return counts;
        }
        void assertResponsive() {
            finishMeasurement();
            long budget = Long.parseLong(InstrumentationRegistry.getArguments().getString("search_main_budget_ms", "250"));
            assertTrue("Main handler delay for " + name + " = " + maxDelay() + "ms, budget=" + budget, maxDelay() <= budget);
        }
        @Override public void close() throws Exception {
            finishMeasurement();
            if (activity != null && !activity.isDestroyed()) main(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(frameListener));
            frameThread.quitSafely(); frameThread.join(1500);
            JSONObject report = new JSONObject().put("stage", name).put("maxMainHandlerDelayMs", maxDelay())
                    .put("inputStartupMsIncludingLayoutAndRendering", inputStartupMs)
                    .put("mainHandlerSamples", delays.size()).put("frameCount", frames.size()).put("mainStacksOver250Ms", new JSONArray(stacks))
                    .put("frameDurationNanos", new JSONArray(frames)).put("fixtureWorks", WORKS).put("fixtureSources", SOURCES);
            report.put("stallEvents", new JSONArray(stallEvents));
            report.put("searchProgress", new JSONArray(searchProgress));
            report.put("phaseElapsedMs", phaseElapsedMs);
            if (searchState != null) report.put("searchStateAtSettlementOrDeadline", searchState);
            File directory = instrumentation.getTargetContext().getExternalFilesDir("search-responsiveness");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            Files.write(new File(directory, name + ".json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
}
