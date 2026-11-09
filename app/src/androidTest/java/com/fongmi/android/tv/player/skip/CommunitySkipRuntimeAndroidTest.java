package com.fongmi.android.tv.player.skip;

import android.app.Instrumentation;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackState;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/** Real background identity/HTTP acquisition feeding the shipping controller and Exo seek path. */
@RunWith(AndroidJUnit4.class)
public final class CommunitySkipRuntimeAndroidTest {
    private static final long DURATION = 90_000;
    private static final Map<String, Object> SETTINGS = Map.ofEntries(
            Map.entry("player_engine", PlayerSetting.ENGINE_EXO), Map.entry("tunnel", false),
            Map.entry("preload", false), Map.entry("preload_next", false), Map.entry("incognito", true),
            Map.entry("audio_effect_preset", 0), Map.entry("audio_pass_through", false),
            Map.entry("audio_prefer", false), Map.entry("video_prefer", false), Map.entry("av3a", false),
            Map.entry("subtitle_libass", false), Map.entry("ai_subtitle_enabled", false),
            Map.entry("community_skip_enabled", true));
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicReference<String> playbackError = new AtomicReference<>();
    private final List<Long> seeks = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final AtomicInteger introRequests = new AtomicInteger();
    private final AtomicInteger otherRequests = new AtomicInteger();
    private final AtomicInteger identityRequests = new AtomicInteger();
    private final AtomicInteger sourceRequests = new AtomicInteger();
    private Map<String, ?> saved;
    private PlayerManager manager;
    private VodPlaybackController controller;
    private CommunitySkipRuntime runtime;
    private Vod media;
    private Episode episode;
    private History history;
    private File audio;
    private ExecutorService worker;
    private MockWebServer server;
    private Supplier<EpisodeIdentityResolver> resolverFactory;

    @Before public void setUp() throws Exception {
        String app = instrumentation.getTargetContext().getPackageName();
        assertTrue("Disposable validation application only", app.equals("com.fongmi.android.tv.preview")
                || app.equals("com.fongmi.android.tv.sourceprobe"));
        saved = new HashMap<>(Prefers.getPrefers().getAll());
        server = new MockWebServer();
        server.start();
        worker = Executors.newFixedThreadPool(2);
        resolverFactory = () -> new EpisodeIdentityResolver("");
        audio = new File(instrumentation.getTargetContext().getCacheDir(), "community-skip-runtime.wav");
        try (FileOutputStream output = new FileOutputStream(audio)) { output.write(wav()); }
        main(() -> {
            SETTINGS.forEach(Prefers::put);
            manager = new PlayerManager(new Callback());
            assertTrue(manager.getPlayer() instanceof ExoPlayer);
            Result result = new Result();
            result.setUrl(Uri.fromFile(audio).toString());
            result.setFormat("audio/wav");
            manager.start(PlaySpec.from(result, "community-skip-runtime-fixture", MediaMetadata.EMPTY), 20_000, 0);
            manager.pause();
            manager.getPlayer().setVolume(0);
            media = new Vod();
            media.setId("tmdb:tv:123");
            media.setName("Runtime fixture");
            media.setYear("2020");
            media.setTypeName("tv");
            episode = Episode.create("S01E01", audio.toString());
            history = new History();
            history.setKey("community-skip-runtime-fixture");
            history.setVodFlag("fixture");
            history.setVodRemarks(episode.getName());
            history.setEpisodeUrl(episode.getUrl());
            Flag flag = new Flag("fixture");
            flag.getEpisodes().add(episode);
            flag.toggle(true, episode);
            VodPlaybackState state = new VodPlaybackState();
            state.setMedia(media);
            state.setFlags(List.of(flag));
            state.setHistory(history);
            VodPlaybackHost host = (VodPlaybackHost) Proxy.newProxyInstance(VodPlaybackHost.class.getClassLoader(),
                    new Class<?>[]{VodPlaybackHost.class}, (proxy, method, args) -> {
                        if (method.getName().equals("canSkipCommunitySegment")) return manager.isPlaying() && manager.canSeek();
                        if (method.getName().equals("skipCommunitySegment")) {
                            long target = (long) args[0];
                            seeks.add(target);
                            manager.seekTo(target);
                        }
                        if (method.getName().equals("requestPlayer")) sourceRequests.incrementAndGet();
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return manager.getPosition();
                        if (method.getReturnType() == String.class) return "";
                        if (method.getReturnType() == List.class) return List.of();
                        return null;
                    });
            controller = new VodPlaybackController(host, state);
            installRuntime();
        });
        await(() -> manager.getPlaybackState() == Player.STATE_READY && manager.canSeek(), "PCM VOD ready");
        main(() -> assertEquals(DURATION, manager.getDuration(), 5));
    }

    @Test public void shouldPrepareMetadataWithoutWaitingForDurationOrBlockingPlayback() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1), release = gate();
        AtomicInteger resolverInstances = new AtomicInteger();
        resolverFactory = () -> {
            resolverInstances.incrementAndGet();
            return new EpisodeIdentityResolver("");
        };
        serve(request -> {
            if (is(request, "/theintrodb")) {
                introRequests.incrementAndGet();
                arrived.countDown();
                hold(release);
                return response(both(DURATION));
            }
            return response("{}");
        });
        main(() -> {
            installRuntime();
            runtime.prepare(media, history, episode);
            runtime.prepare(media, history, episode);
            runtime.target(0, C.TIME_UNSET, history, false);
        });
        SystemClock.sleep(150);
        assertEquals("No provider lookup before the actual duration is known", 0, introRequests.get());
        play();
        main(this::tick);
        assertTrue("The HTTP worker reaches the controlled provider", arrived.await(2, TimeUnit.SECONDS));
        long before = read(manager::getPosition);
        AtomicBoolean mainTurn = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> mainTurn.set(true));
        assertTrue("The main thread runs while metadata HTTP is waiting", mainTurn.get());
        SystemClock.sleep(200);
        assertTrue("Playback continues while the provider is waiting", read(manager::getPosition) > before);
        main(() -> assertTrue(seeks.isEmpty()));
        release.countDown();
        seekAndTickUntil(5_200, 9_000);
        assertEquals("Exactly one metadata batch was requested", 1, introRequests.get());
        assertEquals("Player readiness reuses the prepared episode identity", 1, resolverInstances.get());
    }

    @Test public void shouldStartIdentityBeforeSourceResolutionWithoutDelayingTheSourceRequest() throws Exception {
        CountDownLatch identityArrived = new CountDownLatch(1), releaseIdentity = gate();
        AtomicBoolean onMain = new AtomicBoolean();
        resolverFactory = () -> new EpisodeIdentityResolver("fixture-key", server.url("/3/").toString(), request -> {
            onMain.set(onMain.get() || Looper.myLooper() == Looper.getMainLooper());
            return new OkHttpClient().newCall(request);
        });
        serve(request -> {
            if (is(request, "/3/tv/123")) {
                identityRequests.incrementAndGet(); identityArrived.countDown(); hold(releaseIdentity);
                return response("{\"id\":123,\"name\":\"Runtime fixture\",\"first_air_date\":\"2020-01-01\","
                        + "\"seasons\":[{\"season_number\":1,\"episode_count\":12,\"air_date\":\"2020-01-01\"}],\"external_ids\":{}}");
            }
            return response(is(request, "/theintrodb") ? both(DURATION) : "{}");
        });
        main(() -> {
            media.setId("source-id");
            installRuntime();
            controller.refresh();
            assertEquals("Source resolution is requested immediately", 1, sourceRequests.get());
        });
        assertTrue("Metadata starts before a source result is returned", identityArrived.await(2, TimeUnit.SECONDS));
        assertEquals("Only identity can run before actual duration", 0, introRequests.get());
        AtomicBoolean uiTurn = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> uiTurn.set(true));
        assertTrue("Slow identity work does not block UI or source dispatch", uiTurn.get());
        releaseIdentity.countDown();
        seekAndTickUntil(5_200, 9_000);
        assertEquals("Ready playback reuses the early identity lookup", 1, identityRequests.get());
        assertFalse(onMain.get());
    }

    @Test public void shouldApplyTheIntroBeforeSlowCreditsAndPreserveConsumedRanges() throws Exception {
        CountDownLatch creditsArrived = new CountDownLatch(1), releaseCredits = gate();
        serve(request -> {
            if (is(request, "/theintrodb")) {
                introRequests.incrementAndGet();
                return response(introOnly(DURATION));
            }
            if (is(request, "/theotherdb")) {
                otherRequests.incrementAndGet();
                creditsArrived.countDown();
                hold(releaseCredits);
                return response(credits(DURATION));
            }
            return response("{}");
        });
        prepareWithDuration();
        assertTrue(creditsArrived.await(2, TimeUnit.SECONDS));
        seekAndTickUntil(5_200, 9_000);
        assertEquals("The fallback credits request is still blocked", 1, otherRequests.get());
        assertEquals("Intro is usable before the final lookup returns", 1L, releaseCredits.getCount());
        releaseCredits.countDown();
        await(() -> ranges().size() == 2, "Credits merge into the current policy");
        main(() -> seeks.clear());
        seekPaused(5_200);
        play();
        main(() -> { tick(); manager.pause(); assertTrue("A later batch must not reset skip-once", seeks.isEmpty()); });
        seekAndTickUntil(73_200, 87_000);
    }

    @Test public void shouldCancelAnOldEpisodeBeforeItsDelayedRangesCanSeek() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1), release = gate();
        serve(request -> {
            if (is(request, "/theintrodb")) {
                introRequests.incrementAndGet(); arrived.countDown(); hold(release);
                return response(both(DURATION));
            }
            return response("{}");
        });
        prepareWithDuration();
        assertTrue(arrived.await(2, TimeUnit.SECONDS));
        main(() -> {
            history.setEpisodeUrl("another-episode");
            runtime.target(5_200, DURATION, history, true);
        });
        release.countDown();
        SystemClock.sleep(200);
        seekPaused(5_200); play();
        main(() -> { tick(); manager.pause(); assertTrue(seeks.isEmpty()); });
        assertEquals("A cancelled identity must not start fallback providers", 0, otherRequests.get());
    }

    @Test public void shouldHonorPauseAndAnExplicitManualResetAfterBackgroundResultsArrive() throws Exception {
        serve(request -> response(is(request, "/theintrodb") ? both(DURATION) : "{}"));
        prepareWithDuration();
        await(() -> !ranges().isEmpty(), "Real HTTP ranges arrive while paused");
        seekPaused(5_200);
        main(() -> { tick(); assertTrue("Paused playback does not consume a range", seeks.isEmpty()); });
        main(() -> controller.setOpening(0));
        play();
        main(() -> { tick(); manager.pause(); assertTrue("Manual reset wins over community intro", seeks.isEmpty()); });
        seekAndTickUntil(73_200, 87_000);
    }

    @Test public void shouldRetryARejectedBackgroundSubmissionWithoutReplayingOrRebinding() throws Exception {
        worker.shutdownNow();
        RejectOnceExecutor rejected = new RejectOnceExecutor(2);
        worker = rejected;
        serve(request -> {
            if (is(request, "/theintrodb")) introRequests.incrementAndGet();
            return response(is(request, "/theintrodb") ? both(DURATION) : "{}");
        });
        main(() -> {
            installRuntime();
            runtime.prepare(media, history, episode);
        });
        main(() -> runtime.target(0, DURATION, history, false));
        seekAndTickUntil(5_200, 9_000);
        assertEquals("The first provider enqueue was rejected once", 1, rejected.rejections.get());
        assertEquals("The failed enqueue is retried within the same session", 1, introRequests.get());
    }

    @Test public void shouldDiscardThePreviousDurationAndRefetchBeforeUsingNewRanges() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1), newArrived = new CountDownLatch(1);
        CountDownLatch releaseOld = gate(), releaseNew = gate();
        serve(request -> {
            if (!is(request, "/theintrodb")) return response("{}");
            introRequests.incrementAndGet();
            String query = request.getPath();
            if (query != null && query.contains("duration_ms=" + DURATION)) {
                arrived.countDown(); hold(releaseOld); return response(both(DURATION));
            }
            newArrived.countDown(); hold(releaseNew);
            return response(both(DURATION + 10_000));
        });
        prepareWithDuration();
        assertTrue(arrived.await(2, TimeUnit.SECONDS));
        main(() -> runtime.target(5_200, DURATION + 10_000, history, false));
        releaseOld.countDown();
        assertTrue("The corrected duration starts a new request", newArrived.await(2, TimeUnit.SECONDS));
        main(() -> {
            assertEquals("A stale response cannot seek before the new cut is confirmed", C.TIME_UNSET,
                    runtime.target(5_200, DURATION + 10_000, history, true));
        });
        releaseNew.countDown();
        await(() -> !ranges().isEmpty(), "New duration ranges are installed");
        main(() -> {
            assertEquals("Only the new cut duration is usable", 9_000, runtime.target(5_200, DURATION + 10_000, history, true));
        });
    }

    @Test public void shouldRetryTransientIdentityFailureOnTheWorkerAndKeepTheMainThreadFree() throws Exception {
        AtomicBoolean requestOnMain = new AtomicBoolean();
        resolverFactory = () -> new EpisodeIdentityResolver("fixture-key", server.url("/3/").toString(), request -> {
            requestOnMain.set(requestOnMain.get() || Looper.myLooper() == Looper.getMainLooper());
            return new OkHttpClient().newCall(request);
        });
        serve(request -> {
            if (is(request, "/3/tv/123")) {
                if (identityRequests.incrementAndGet() == 1) return new MockResponse().setResponseCode(503);
                return response("{\"id\":123,\"name\":\"Runtime fixture\",\"first_air_date\":\"2020-01-01\","
                        + "\"seasons\":[{\"season_number\":1,\"episode_count\":12,\"air_date\":\"2020-01-01\"}],\"external_ids\":{}}");
            }
            return response(is(request, "/theintrodb") ? both(DURATION) : "{}");
        });
        main(() -> {
            // An ordinary source needs metadata; explicit TMDB ids deliberately remain usable offline.
            media.setId("source-id");
            installRuntime();
            runtime.prepare(media, history, episode);
        });
        // Supply the exact search contract so retry is caused by a real 503, not an ambiguous identity.
        seekAndTickUntil(5_200, 9_000);
        assertTrue("Identity traffic ran off the main thread", !requestOnMain.get());
        assertTrue("Transient identity failures are retried", identityRequests.get() >= 2);
    }

    @After public void tearDown() throws Exception {
        releases.forEach(CountDownLatch::countDown);
        main(() -> {
            if (runtime != null) runtime.stop();
            if (controller != null) controller.stopCommunitySkip();
            if (manager != null) manager.release();
            if (saved != null) for (String key : SETTINGS.keySet()) {
                if (saved.containsKey(key)) Prefers.put(key, saved.get(key));
                else Prefers.remove(key);
            }
        });
        if (worker != null) worker.shutdownNow();
        if (server != null) server.shutdown();
        if (audio != null) assertTrue(audio.delete() || !audio.exists());
    }

    private void installRuntime() {
        if (runtime != null) runtime.stop();
        runtime = new CommunitySkipRuntime(worker, resolverFactory, () -> new CommunitySkipApi(new OkHttpClient(),
                server.url("/theintrodb").toString(), server.url("/introdb").toString(), server.url("/theotherdb").toString()));
        // Inject transport dependencies only. All policies below come from production HTTP parsing.
        setField(controller, "communitySkip", runtime);
    }

    private void prepareWithDuration() {
        main(() -> { runtime.prepare(media, history, episode); runtime.target(0, DURATION, history, false); });
    }

    private void serve(java.util.function.Function<RecordedRequest, MockResponse> handler) {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (is(request, "/3/search/tv")) {
                    return response("{\"results\":[{\"id\":123,\"name\":\"Runtime fixture\",\"first_air_date\":\"2020-01-01\"}]}");
                }
                return handler.apply(request);
            }
        });
    }

    private static boolean is(RecordedRequest request, String path) {
        return request.getPath() != null && (request.getPath().equals(path) || request.getPath().startsWith(path + "?"));
    }

    private CountDownLatch gate() { CountDownLatch latch = new CountDownLatch(1); releases.add(latch); return latch; }
    private static void hold(CountDownLatch gate) {
        try { gate.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
    private static MockResponse response(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private static String introOnly(long duration) {
        return "{\"tvdb_id\":123,\"tmdb_id\":123,\"type\":\"tv\",\"season\":1,\"episode\":1,\"group_duration_ms\":"
                + duration + ",\"intro\":[{\"start_ms\":5000,\"end_ms\":9000}]}";
    }
    private static String both(long duration) {
        return introOnly(duration).replace("}]}", "}],\"credits\":[{\"start_ms\":73000,\"end_ms\":87000}]}");
    }
    private static String credits(long duration) {
        return "{\"items\":[{\"video_type\":\"tv\",\"version_runtime\":" + duration / 1000
                + ",\"base_video_season\":{\"season_number\":1},\"base_video_episode\":{\"episode_number\":1},"
                + "\"marker\":[{\"marker_type\":\"credits\",\"time_start\":73,\"time_end\":87}]}]}";
    }

    @SuppressWarnings("unchecked")
    private List<SkipSegment> ranges() {
        Object active = field(runtime, "active");
        if (active == null) return List.of();
        Object policy = field(active, "policy");
        return policy == null ? List.of() : (List<SkipSegment>) field(policy, "segments");
    }

    private void seekAndTickUntil(long from, long target) {
        seekPaused(from);
        play();
        await(() -> { tick(); return seeks.contains(target); }, "Metadata drives the real Exo seek to " + target);
        main(manager::pause);
        await(() -> manager.getPlaybackState() == Player.STATE_READY, "Exo ready after community seek");
        main(() -> assertEquals(target, manager.getPosition(), 100));
    }
    private void seekPaused(long position) {
        main(() -> { manager.pause(); manager.seekTo(position); controller.onSeek(); });
        await(() -> manager.getPlaybackState() == Player.STATE_READY && Math.abs(manager.getPosition() - position) < 50,
                "Paused position ready");
    }
    private void play() { main(manager::play); await(manager::isPlaying, "Exo playing"); }
    private void tick() { controller.onTimeChanged(System.currentTimeMillis(), manager.getPosition(), manager.getDuration()); }
    private void await(BooleanSupplier condition, String description) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNull(playbackError.get());
            if (read(condition::getAsBoolean)) return;
            SystemClock.sleep(30);
        }
        fail(description);
    }
    private void main(Runnable action) { read(() -> { action.run(); return null; }); }
    private <T> T read(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(action.get()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Runtime main-thread check failed", failure.get());
        return value.get();
    }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void setField(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static byte[] wav() {
        int rate = 16_000, samples = rate * (int) (DURATION / 1000), bytes = samples * 2;
        ByteBuffer output = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        output.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
        output.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        output.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        output.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        for (int i = 0; i < samples; i++) output.putShort((short) (Math.sin(i * 2 * Math.PI * 440 / rate) * 1000));
        return output.array();
    }
    private final class Callback implements PlayerManager.Callback {
        @Override public void onPrepare() { }
        @Override public void onTracksChanged() { }
        @Override public void onDecodeChanged() { }
        @Override public void onMediaOptionsChanged() { }
        @Override public void onError(String error) { playbackError.set(error); }
        @Override public void onPlayerRebuild(Player player) { playbackError.set("Unexpected decoder replacement"); }
        @Override public void onDanmakuSourceChanged(Uri uri) { }
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) { }
        @Override public void onDanmakuEnabledChanged(boolean enabled) { }
        @Override public void onDanmakuSent(String text) { }
    }
    private static final class RejectOnceExecutor extends AbstractExecutorService {
        final AtomicInteger rejections = new AtomicInteger();
        private final AtomicInteger submissions = new AtomicInteger();
        private final int rejectedSubmission;
        private final ExecutorService delegate = Executors.newFixedThreadPool(2);
        RejectOnceExecutor(int rejectedSubmission) { this.rejectedSubmission = rejectedSubmission; }
        @Override public void execute(Runnable command) {
            if (submissions.incrementAndGet() == rejectedSubmission) {
                rejections.incrementAndGet();
                throw new RejectedExecutionException("Controlled provider enqueue");
            }
            delegate.execute(command);
        }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
