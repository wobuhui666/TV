package com.fongmi.android.tv.player.skip;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
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
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.source.EpisodeLabel;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.ech.EchSettings;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/** Real source titles/lists + live identities, with explicitly substituted video and HTTP markers. */
@RunWith(AndroidJUnit4.class)
public final class CommunitySkipRealPlaylistAndroidTest {
    private static final long INTRO_START_MS = 5_000;
    private static final long INTRO_END_MS = 9_000;
    private static final Map<String, Object> SETTINGS = Map.ofEntries(
            Map.entry("player_engine", PlayerSetting.ENGINE_EXO), Map.entry("tunnel", false),
            Map.entry("preload", false), Map.entry("preload_next", false), Map.entry("incognito", true),
            Map.entry("audio_effect_preset", 0), Map.entry("audio_pass_through", false),
            Map.entry("audio_prefer", false), Map.entry("video_prefer", false), Map.entry("av3a", false),
            Map.entry("subtitle_libass", false), Map.entry("ai_subtitle_enabled", false),
            Map.entry("community_skip_enabled", true), Map.entry("tmdb_connection_mode", Setting.TMDB_DIRECT));
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final ExecutorService worker = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>()) {
        @Override protected void beforeExecute(Thread thread, Runnable task) { observeWorker(); }
    };
    private final JSONObject report = new JSONObject();
    private final JSONArray cases = new JSONArray();
    private final List<Long> automaticSeeks = new ArrayList<>();
    private final Set<Player> observedPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicReference<String> playbackError = new AtomicReference<>();
    private final AtomicBoolean firstFrame = new AtomicBoolean();
    private final AtomicBoolean lookupsOffMain = new AtomicBoolean(true);
    private CorePlaybackActivity activity;
    private PlayerManager manager;
    private VodPlaybackController controller;
    private CommunitySkipRuntime runtime;
    private MockWebServer controlled;
    private ControlledMarkers markers;
    private File video;
    private String tmdbKey;
    private int probeCount;
    private int decodedCount;
    private int progressCount;
    private int identityMatchedCount;
    private int identityRejectedCount;
    private int introSeekCount;
    private int creditsSeekCount;
    private int consumedCount;
    private int blockedCount;
    private int publicRangeCount;
    private int publicRequestCount;
    private boolean nonBlocking;
    private boolean advancedDuringLookup;
    private JSONObject currentStage;

    @Test(timeout = 420_000)
    public void shouldVerifyRealPlaylistsWithSubstitutedVideoAndExplicitControlledMarkers() throws Exception {
        assertEquals("Disposable preview only", "com.fongmi.android.tv.preview", target.getPackageName());
        File inputs = new File(target.getFilesDir(), "real-playlist-fixture");
        Map<String, ?> saved = new HashMap<>(Prefers.getPrefers().getAll());
        Doh originalDoh = OkHttp.dns().getDoh();
        JSONObject fixture = new JSONObject(consume(new File(inputs, "fixtures.json")));
        tmdbKey = consume(new File(inputs, "tmdb-key.txt"));
        video = new File(inputs, "video.mp4");
        assertEquals("Fixture schema", "tv.community-skip-playlist-fixture.v1", fixture.getString("schema"));
        assertTrue("Runtime metadata credential", tmdbKey.matches("[a-fA-F0-9]{32}"));
        assertTrue("Substituted local video staged", video.isFile() && video.length() > 0);
        JSONArray fixtures = fixture.getJSONArray("cases");
        assertTrue("Multiple actual source titles", fixtures.length() >= 3 && fixtures.length() <= 12);
        report.put("schema", "tv.community-skip-real-playlist.v1").put("cases", cases)
                .put("fixtureCaseCount", fixtures.length()).put("passed", false)
                .put("videoSubstituted", true).put("markersControlled", true)
                .put("publicCoverageVerified", false).put("networkMode", "ECH_OFF")
                .put("credentialFilesRemoved", true);
        try {
            main(() -> SETTINGS.forEach(Prefers::put));
            EchSettings.setEnabled(false);
            OkHttp.dns().setDoh(originalDoh);
            activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(target,
                    CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(activity::hasWindowFocus, "Video surface focused", 8_000);
            for (int caseIndex = 0; caseIndex < fixtures.length(); caseIndex++) {
                JSONObject source = fixtures.getJSONObject(caseIndex);
                Vod media = media(source, caseIndex);
                JSONArray probes = source.getJSONArray("probes");
                assertTrue("Bounded playlist probes", probes.length() > 0 && probes.length() <= 12);
                for (int index = 0; index < probes.length(); index++) verifyProbe(source, media, probes.getJSONObject(index));
            }
            assertTrue("Several real titles matched through TMDB", identityMatchedCount >= 3);
            assertTrue("Actual substituted video decoded", decodedCount >= 3);
            assertTrue("Actual playback clock advanced", progressCount >= 3);
            assertTrue("Real controller intro seeks", introSeekCount >= 3);
            assertTrue("Real controller credits seeks", creditsSeekCount >= 3);
            assertEquals("Supplemented ranges preserve consumed intros", introSeekCount, consumedCount);
            assertTrue("HTTP lookup remains off main and video advances", nonBlocking && advancedDuringLookup && lookupsOffMain.get());
            report.put("passed", true);
        } catch (Throwable failure) {
            report.put("failureTypes", errorTypes(failure));
            throw new AssertionError("Real playlist verification failed; inspect the sanitized report");
        } finally {
            releasePlayback();
            if (activity != null) main(activity::finish);
            worker.shutdownNow();
            OkHttp.client().dispatcher().cancelAll();
            report.put("workerCleanupComplete", worker.awaitTermination(5, TimeUnit.SECONDS));
            main(() -> {
                for (String key : SETTINGS.keySet()) {
                    if (saved.containsKey(key)) Prefers.put(key, saved.get(key)); else Prefers.remove(key);
                }
            });
            if (saved.containsKey("ech_enabled")) Prefers.put("ech_enabled", saved.get("ech_enabled"));
            else Prefers.remove("ech_enabled");
            OkHttp.dns().setDoh(originalDoh);
            OkHttp.client().connectionPool().evictAll();
            Map<String, ?> restored = Prefers.getPrefers().getAll();
            boolean networkRestored = restored.containsKey("ech_enabled") == saved.containsKey("ech_enabled")
                    && java.util.Objects.equals(restored.get("ech_enabled"), saved.get("ech_enabled"))
                    && originalDoh.toString().equals(OkHttp.dns().getDoh().toString());
            report.put("networkPreferencesRestored", networkRestored);
            tmdbKey = null;
            assertTrue("Remove substituted test video", video.delete() || !video.exists());
            counts();
            writeReport();
            assertTrue("Original network selection restored", networkRestored);
        }
    }

    private Vod media(JSONObject source, int caseIndex) throws Exception {
        Vod media = new Vod();
        media.setId("real-playlist-source-" + source.getString("caseId"));
        media.setName(source.getString("title"));
        media.setYear(source.optString("year"));
        media.setTypeName(source.optString("typeName"));
        JSONArray flags = source.getJSONArray("flags");
        assertTrue("Real source lines", flags.length() > 0 && flags.length() <= 32);
        for (int flagIndex = 0; flagIndex < flags.length(); flagIndex++) {
            JSONObject input = flags.getJSONObject(flagIndex);
            JSONArray episodes = input.getJSONArray("episodes");
            Flag flag = new Flag(input.getString("name"));
            assertTrue("Bounded actual playlist", episodes.length() > 0 && episodes.length() <= 2_000);
            for (int episodeIndex = 0; episodeIndex < episodes.length(); episodeIndex++) {
                Object item = episodes.get(episodeIndex);
                String label = item instanceof JSONObject object ? object.getString("name") : (String) item;
                boolean generated = item instanceof JSONObject object && object.optBoolean("generated");
                String replacement = Uri.fromFile(video).buildUpon()
                        .fragment("playlist-" + caseIndex + "-" + flagIndex + "-" + episodeIndex).build().toString();
                Episode episode = Episode.create(label, replacement);
                episode.setGeneratedLabel(generated);
                flag.getEpisodes().add(episode);
            }
            assertEquals("No playlist entries lost during video substitution", episodes.length(), flag.getEpisodes().size());
            media.getFlags().add(flag);
        }
        return media;
    }

    private void verifyProbe(JSONObject source, Vod media, JSONObject probe) throws Exception {
        int flagIndex = probe.getInt("flagIndex"), episodeIndex = probe.getInt("episodeIndex");
        Flag flag = media.getFlags().get(flagIndex);
        Episode episode = flag.getEpisodes().get(episodeIndex);
        String expectation = probe.getString("expectIdentity");
        assertTrue("Finite fixture expectation", List.of("MATCHED", "REJECT", "MEASURE").contains(expectation));
        EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode(media.getName(), episode.getName());
        JSONObject stage = new JSONObject().put("caseId", source.getString("caseId"))
                .put("title", source.getString("title")).put("flagName", flag.getFlag()).put("episodeLabel", episode.getName())
                .put("flagIndex", flagIndex).put("episodeIndex", episodeIndex).put("episodeCount", flag.getEpisodes().size())
                .put("sourceSeasonKnown", parsed != null && parsed.season() != null).put("parsedSeasonKnown", parsed != null && parsed.season() != null)
                .put("parsedSeason", parsed == null || parsed.season() == null ? JSONObject.NULL : parsed.season())
                .put("parsedEpisode", parsed == null ? JSONObject.NULL : parsed.episode())
                .put("generatedLabel", episode.isGeneratedLabel()).put("labelKind", labelKind(episode))
                .put("expectedIdentityStatus", expectation).put("status", "STARTED").put("phase", "IDENTITY");
        cases.put(stage);
        currentStage = stage;
        probeCount++;
        writeReport();
        try {
            if (probe.has("expectedParsedSeason")) {
                Object expected = probe.get("expectedParsedSeason");
                assertEquals("Source season evidence is preserved", expected == JSONObject.NULL ? null : ((Number) expected).intValue(),
                        parsed == null ? null : parsed.season());
            }
            long identityStart = SystemClock.elapsedRealtime();
            EpisodeIdentity identity = realIdentity(media, episode);
            stage.put("identityMs", SystemClock.elapsedRealtime() - identityStart)
                    .put("identityStatus", identity == null ? expectation.equals("REJECT") ? "REJECTED" : "UNCONFIRMED" : "MATCHED");
            if (expectation.equals("MATCHED")) {
                assertNotNull("Real TMDB lookup must confirm the expected title", identity);
                assertEquals("Canonical id from the real metadata response", probe.getLong("expectedTmdbId"), identity.tmdbId());
                assertEquals("Actual season", probe.getInt("expectedSeason"), identity.season());
                assertEquals("Actual episode, independent of playlist position", probe.getInt("expectedEpisode"), identity.episode());
            } else if (expectation.equals("REJECT")) {
                assertNull("Unreliable source identity must remain unconfirmed", identity);
                identityRejectedCount++;
            }
            if (identity != null) {
                identityMatchedCount++;
                stage.put("tmdbId", identity.tmdbId()).put("season", identity.season()).put("episode", identity.episode());
            }
            stage.put("phase", "VIDEO_PREPARING");
            prepareVideo(media, flag, episode, identity);
            await(() -> manager.getPlaybackState() == Player.STATE_READY && manager.canSeek() && manager.getDuration() > 0,
                    "Substituted MP4 has a decoded seekable timeline", 15_000);
            long duration = read(manager::getDuration);
            assertEquals("Actual generated video duration", 90_000, duration, 100);
            markers.durationMs = duration;
            stage.put("durationMs", duration).put("phase", "VIDEO_PREPARED");
            recordPlaybackState(stage);
            writeReport();
            main(manager::play);
            stage.put("phase", "FIRST_FRAME");
            await(() -> manager.isPlaying() && firstFrame.get(), "Actual H264 first frame rendered", 8_000);
            recordPlaybackState(stage);
            stage.put("videoDecoded", true);
            decodedCount++;
            stage.put("phase", "PROGRESS");
            long before = read(manager::getPosition);
            // Do not start the controlled provider timer before the separate public-service probe.
            await(() -> manager.getPosition() - before > 200, "Actual Exo playback advances", 3_000);
            boolean progress = read(manager::getPosition) - before > 200;
            assertTrue("Actual Exo playback clock advances", progress);
            if (decodedCount == 1) saveVideoScreenshot();
            progressCount++;
            stage.put("videoDecoded", true).put("playbackProgressVerified", progress);
            main(manager::pause);
            stage.put("phase", "PUBLIC_PROVIDERS");
            measurePublicProviders(identity, duration, stage);
            if (identity == null) {
                main(manager::play);
                drive(900);
                await(this::identityDone, "Rejected identity decision completed off main", 10_000);
                assertEquals("Rejected identity must not query the controlled providers", 0, markers.requests.get());
                assertTrue("Rejected identity must not seek", read(automaticSeeks::isEmpty));
                blockedCount++;
                stage.put("noSeekForRejectedIdentity", true).put("actualSeekCount", 0);
            } else {
                stage.put("phase", "CONTROLLED_SKIP");
                verifyControlledFlow(duration, stage);
            }
            stage.put("lookupOffMainThread", lookupsOffMain.get()).put("status", "VERIFIED").put("phase", "VERIFIED");
        } catch (Throwable failure) {
            recordPlaybackState(stage);
            stage.put("status", "FAILED").put("failureTypes", errorTypes(failure));
            throw failure;
        } finally {
            releasePlayback();
            counts();
            writeReport();
            currentStage = null;
        }
    }

    private EpisodeIdentity realIdentity(Vod media, Episode episode) throws Exception {
        EpisodeIdentityResolver resolver = new EpisodeIdentityResolver(tmdbKey);
        Future<EpisodeIdentity> lookup = worker.submit(() -> {
            observeWorker();
            return resolver.resolve(media.getId(), media.getName(), media.getYear(), media.getTypeName(),
                    episode.getName(), !episode.isGeneratedLabel());
        });
        try { return lookup.get(12, TimeUnit.SECONDS); }
        finally { resolver.cancel(); if (!lookup.isDone()) lookup.cancel(true); }
    }

    private void measurePublicProviders(EpisodeIdentity identity, long duration, JSONObject stage) throws Exception {
        if (identity == null) {
            stage.put("publicProviderStatus", "NOT_QUERIED_UNCONFIRMED");
            return;
        }
        AtomicInteger requests = new AtomicInteger();
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        OkHttpClient observed = OkHttp.trustedClient().newBuilder().addInterceptor(chain -> {
            observeWorker();
            requests.incrementAndGet();
            Response response = chain.proceed(chain.request());
            statuses.add(response.code());
            return response;
        }).build();
        CommunitySkipApi api = new CommunitySkipApi(observed, endpoint("THEINTRODB"), endpoint("INTRODB"), endpoint("THEOTHERDB"));
        Future<List<SkipSegment>> result = worker.submit(() -> { observeWorker(); return api.fetch(identity, duration); });
        try {
            // The substituted timeline is intentional. These are never claimed to cover the source video.
            List<SkipSegment> ranges = result.get(10, TimeUnit.SECONDS);
            publicRangeCount += ranges.size();
            publicRequestCount += requests.get();
            stage.put("publicProviderStatus", ranges.isEmpty() ? "NO_RANGES_FOR_TEST_DURATION" : "RANGES_FOR_TEST_DURATION")
                    .put("publicProviderRangeCountWithTestDuration", ranges.size())
                    .put("publicProviderRequestCount", requests.get()).put("publicProviderHttpStatuses", new JSONArray(statuses));
        } finally { api.cancel(); if (!result.isDone()) result.cancel(true); }
    }

    private void prepareVideo(Vod media, Flag selected, Episode episode, EpisodeIdentity expected) throws Exception {
        markers = new ControlledMarkers(expected);
        controlled = new MockWebServer();
        controlled.setDispatcher(markers);
        controlled.start();
        main(() -> {
            playbackError.set(null);
            firstFrame.set(false);
            automaticSeeks.clear();
            observedPlayers.clear();
            manager = new PlayerManager(new PlayerCallback());
            attachPlayer(manager.getPlayer());
            assertTrue("Shipping Exo engine selected", manager.getPlayer() instanceof ExoPlayer);
            manager.bindPlayerView(activity.view);
            // Exo's engine-level bind hook is empty; the product UI binds its PlayerView directly.
            activity.view.setPlayer(manager.getPlayer());
            History history = new History();
            history.setKey("real-playlist-" + probeCount);
            history.setVodFlag(selected.getFlag());
            history.setVodRemarks(episode.getName());
            history.setEpisodeUrl(episode.getUrl());
            for (Flag flag : media.getFlags()) flag.setSelected(selected);
            selected.toggle(true, episode);
            VodPlaybackState state = new VodPlaybackState();
            state.setMedia(media);
            state.setFlags(media.getFlags());
            state.setHistory(history);
            assertSame("Selected real playlist item, including non-first positions", episode, state.getEpisode());
            VodPlaybackHost host = (VodPlaybackHost) Proxy.newProxyInstance(VodPlaybackHost.class.getClassLoader(),
                    new Class<?>[]{VodPlaybackHost.class}, (proxy, method, args) -> {
                        if (method.getName().equals("canSkipCommunitySegment")) return manager.isPlaying() && manager.canSeek();
                        if (method.getName().equals("skipCommunitySegment")) {
                            long destination = (long) args[0];
                            automaticSeeks.add(destination);
                            manager.seekTo(destination);
                        }
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return manager.getPosition();
                        if (method.getReturnType() == String.class) return "";
                        if (method.getReturnType() == List.class) return List.of();
                        return null;
                    });
            controller = new VodPlaybackController(host, state);
            runtime = new CommunitySkipRuntime(worker, () -> new EpisodeIdentityResolver(tmdbKey),
                    () -> new CommunitySkipApi(new OkHttpClient(), controlled.url("/theintrodb").toString(),
                            controlled.url("/introdb").toString(), controlled.url("/theotherdb").toString()));
            setField(controller, "communitySkip", runtime);
            runtime.prepare(media, history, episode);
            Result result = new Result();
            result.setUrl(episode.getUrl());
            result.setFormat("video/mp4");
            manager.start(PlaySpec.from(result, history.getKey(), MediaMetadata.EMPTY), 20_000, 0);
            manager.getPlayer().setVolume(0);
        });
    }

    private void verifyControlledFlow(long duration, JSONObject stage) throws Exception {
        main(manager::play);
        driveUntil(() -> markers.introArrived.getCount() == 0, "Controlled HTTP lookup arrived", 8_000);
        long before = read(manager::getPosition);
        long uiStart = SystemClock.elapsedRealtime();
        main(this::tick);
        long uiMs = SystemClock.elapsedRealtime() - uiStart;
        drive(600);
        boolean advanced = read(manager::getPosition) - before > 200;
        assertTrue("Actual video advances while HTTP response is held", advanced);
        assertTrue("UI tick is not held by metadata HTTP", uiMs < 1_000);
        stage.put("uiTurnMs", uiMs).put("playbackAdvancedWhileLookup", advanced);
        nonBlocking = true;
        advancedDuringLookup = true;
        markers.releaseIntro.countDown();
        driveUntil(() -> markers.creditsArrived.getCount() == 0 && hasRange(SkipSegment.Type.INTRO),
                "Intro published before delayed credits", 4_000);
        seekAndVerify(INTRO_START_MS + 250, INTRO_END_MS, duration, false);
        introSeekCount++;
        stage.put("controlledIntroSeekVerified", true);
        assertEquals("Credits response is still held when intro applies", 1, markers.releaseCredits.getCount());
        markers.releaseCredits.countDown();
        driveUntil(() -> hasRange(SkipSegment.Type.CREDITS), "Credits supplement received", 4_000);
        main(() -> { manager.pause(); manager.seekTo(INTRO_START_MS + 250); controller.onSeek(); });
        await(() -> manager.getPlaybackState() == Player.STATE_READY && Math.abs(manager.getPosition() - INTRO_START_MS - 250) < 100,
                "Rewound intro position ready", 5_000);
        main(manager::play);
        drive(700);
        assertEquals("Supplemented credits do not reset consumed intro", 1, read(() -> Collections.frequency(automaticSeeks, INTRO_END_MS)).intValue());
        consumedCount++;
        stage.put("consumedRangePreserved", true);
        seekAndVerify(duration - 17_000 + 250, duration, duration, true);
        creditsSeekCount++;
        assertFalse("Controlled API coordinates match the real metadata identity", markers.coordinateMismatch.get());
        stage.put("controlledCreditsSeekVerified", true).put("actualSeekCount", read(automaticSeeks::size));
    }

    private void seekAndVerify(long from, long destination, long duration, boolean creditsAtEnd) {
        int initial = read(automaticSeeks::size);
        main(() -> { manager.pause(); manager.seekTo(from); controller.onSeek(); });
        await(() -> manager.getPlaybackState() == Player.STATE_READY && Math.abs(manager.getPosition() - from) < 150,
                "Actual seek position stable", 5_000);
        main(manager::play);
        driveUntil(() -> automaticSeeks.size() > initial && automaticSeeks.subList(initial, automaticSeeks.size()).contains(destination),
                "Production controller emitted the actual community seek", 5_000);
        main(manager::pause);
        await(() -> (manager.getPlaybackState() == Player.STATE_READY
                        || creditsAtEnd && destination == duration && manager.getPlaybackState() == Player.STATE_ENDED)
                        && Math.abs(manager.getPosition() - destination) < 1_000,
                "Exo reached the actual marker destination", 6_000);
    }

    private boolean identityDone() {
        Object session = field(runtime, "active");
        return session == null || (boolean) field(session, "identityDone");
    }

    @SuppressWarnings("unchecked") private boolean hasRange(SkipSegment.Type type) {
        Object session = field(runtime, "active");
        Object policy = session == null ? null : field(session, "policy");
        return policy != null && ((List<SkipSegment>) field(policy, "segments")).stream().anyMatch(range -> range.type() == type);
    }

    private void attachPlayer(Player player) {
        if (activity != null && player instanceof ExoPlayer) activity.view.setPlayer(player);
        if (!observedPlayers.add(player)) return;
        int generation = probeCount;
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException failure) {
                if (generation == probeCount) playbackError.set("PLAYBACK_ERROR");
            }
        });
        if (player instanceof ExoPlayer exo) exo.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
                if (generation == probeCount) firstFrame.set(true);
            }
        });
    }

    private void observeWorker() { if (Looper.myLooper() == Looper.getMainLooper()) lookupsOffMain.set(false); }
    private void tick() { controller.onTimeChanged(System.currentTimeMillis(), manager.getPosition(), manager.getDuration()); }
    private void drive(long milliseconds) {
        long deadline = SystemClock.elapsedRealtime() + milliseconds;
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNull("No playback error", playbackError.get());
            main(this::tick);
            SystemClock.sleep(150);
        }
    }
    private void driveUntil(BooleanSupplier condition, String description, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNull("No playback error", playbackError.get());
            if (read(() -> { tick(); return condition.getAsBoolean(); })) return;
            SystemClock.sleep(150);
        }
        fail(description);
    }
    private void await(BooleanSupplier condition, String description, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNull("No playback error", playbackError.get());
            if (read(condition::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail(description);
    }

    private void releasePlayback() throws Exception {
        if (markers != null) { markers.releaseIntro.countDown(); markers.releaseCredits.countDown(); }
        main(() -> {
            if (activity != null) activity.view.setPlayer(null);
            if (runtime != null) runtime.stop();
            if (controller != null) controller.stopCommunitySkip();
            if (manager != null) manager.release();
            runtime = null; controller = null; manager = null;
        });
        if (controlled != null) controlled.shutdown();
        controlled = null;
        markers = null;
    }

    private void counts() throws Exception {
        report.put("probeCount", probeCount).put("videoDecodedCount", decodedCount).put("playbackProgressCount", progressCount)
                .put("realIdentityMatchedCount", identityMatchedCount).put("identityRejectedCount", identityRejectedCount)
                .put("controlledIntroSeekCount", introSeekCount).put("controlledCreditsSeekCount", creditsSeekCount)
                .put("consumedRangePreservedCount", consumedCount).put("blockedIdentityNoSeekCount", blockedCount)
                .put("publicProviderRangeCountWithTestDuration", publicRangeCount).put("publicProviderRequestCount", publicRequestCount)
                .put("backgroundQueryNonBlocking", nonBlocking && lookupsOffMain.get()).put("playbackAdvancedWhileLookup", advancedDuringLookup);
    }
    private void recordPlaybackState(JSONObject stage) throws Exception {
        if (manager == null) return;
        stage.put("playbackState", read(manager::getPlaybackState)).put("playWhenReady", read(() -> manager.getPlayer().getPlayWhenReady()))
                .put("playing", read(manager::isPlaying)).put("firstFrameRendered", firstFrame.get())
                .put("surfaceBound", read(() -> activity != null && activity.view.getPlayer() == manager.getPlayer()));
    }

    private void writeReport() throws Exception {
        File directory = target.getExternalFilesDir("community-skip-playlist-verification");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, "report.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private void saveVideoScreenshot() {
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        if (screenshot == null) return;
        try {
            File directory = target.getExternalFilesDir("community-skip-playlist-verification");
            if (directory == null || !(directory.isDirectory() || directory.mkdirs())) return;
            try (FileOutputStream output = new FileOutputStream(new File(directory, "generated-video.png"))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
        } catch (Exception ignored) {
            // The actual decoded frame/clock/seek assertions remain the required proof.
        } finally { screenshot.recycle(); }
    }
    private static String consume(File file) throws Exception {
        assertTrue("Private input staged", file.isFile());
        String value = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
        assertTrue("Private input removed after reading", file.delete());
        return value;
    }
    private static String labelKind(Episode episode) {
        EpisodeLabel label = episode.getLabel();
        if (episode.isGeneratedLabel()) return "GENERATED";
        if (label.isVariety()) return "VARIETY";
        if (label.isPreview()) return "PREVIEW";
        if (label.isAmbiguous()) return "AMBIGUOUS";
        if (!label.part().isEmpty()) return "SPLIT";
        return label.isApiSafe() ? "EPISODE" : "UNSUPPORTED";
    }
    private static JSONArray errorTypes(Throwable error) {
        JSONArray names = new JSONArray();
        for (int depth = 0; error != null && depth < 5; depth++, error = error.getCause()) {
            String name = error.getClass().getSimpleName();
            names.put(name.matches("[A-Za-z0-9_$]{1,100}") ? name : "UnknownError");
        }
        return names;
    }
    private void main(Runnable action) { read(() -> { action.run(); return null; }); }
    private <T> T read(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(action.get()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) {
            if (currentStage != null) {
                try { currentStage.put("uiFailureTypes", errorTypes(failure.get())); }
                catch (Exception ignored) { }
            }
            throw new AssertionError("Real playlist UI assertion failed");
        }
        return value.get();
    }
    private static Object field(Object target, String name) {
        try { Field value = target.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Real playlist state inspection failed"); }
    }
    private static void setField(Object target, String name, Object value) {
        try { Field property = target.getClass().getDeclaredField(name); property.setAccessible(true); property.set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Real playlist dependency setup failed"); }
    }
    private static String endpoint(String name) {
        try { Field value = CommunitySkipApi.class.getDeclaredField(name); value.setAccessible(true); return (String) value.get(null); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Production provider contract unavailable"); }
    }

    private final class PlayerCallback implements PlayerManager.Callback {
        private final int generation = probeCount;
        @Override public void onPrepare() { }
        @Override public void onTracksChanged() { }
        @Override public void onDecodeChanged() { }
        @Override public void onMediaOptionsChanged() { }
        @Override public void onError(String error) { if (generation == probeCount) playbackError.set("PLAYBACK_ERROR"); }
        @Override public void onPlayerRebuild(Player player) { if (generation == probeCount) attachPlayer(player); }
        @Override public void onDanmakuSourceChanged(Uri uri) { }
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) { }
        @Override public void onDanmakuEnabledChanged(boolean enabled) { }
        @Override public void onDanmakuSent(String text) { }
    }

    private final class ControlledMarkers extends Dispatcher {
        final EpisodeIdentity expected;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicBoolean coordinateMismatch = new AtomicBoolean();
        final CountDownLatch introArrived = new CountDownLatch(1), creditsArrived = new CountDownLatch(1);
        final CountDownLatch releaseIntro = new CountDownLatch(1), releaseCredits = new CountDownLatch(1);
        volatile long durationMs;
        ControlledMarkers(EpisodeIdentity expected) { this.expected = expected; }
        @Override public MockResponse dispatch(RecordedRequest request) {
            observeWorker();
            requests.incrementAndGet();
            if (expected == null) { coordinateMismatch.set(true); return json("{}").setResponseCode(400); }
            try {
                String path = request.getRequestUrl().encodedPath();
                if (path.equals("/theintrodb")) {
                    boolean matches = Long.toString(expected.tmdbId()).equals(request.getRequestUrl().queryParameter("tmdb_id"))
                            && Integer.toString(expected.season()).equals(request.getRequestUrl().queryParameter("season"))
                            && Integer.toString(expected.episode()).equals(request.getRequestUrl().queryParameter("episode"));
                    if (!matches) { coordinateMismatch.set(true); return json("{}").setResponseCode(400); }
                    introArrived.countDown();
                    releaseIntro.await(5, TimeUnit.SECONDS);
                    return json(new JSONObject().put("tvdb_id", 1).put("tmdb_id", expected.tmdbId()).put("type", "tv")
                            .put("season", expected.season()).put("episode", expected.episode()).put("group_duration_ms", durationMs)
                            .put("intro", new JSONArray().put(new JSONObject().put("start_ms", INTRO_START_MS).put("end_ms", INTRO_END_MS))).toString());
                }
                if (path.equals("/introdb")) return json("{}");
                JSONObject body = new JSONObject(request.getBody().readUtf8());
                if (!Long.toString(expected.tmdbId()).equals(body.getString("platform_value"))
                        || expected.season() != body.getInt("season_number") || expected.episode() != body.getInt("episode_number")) {
                    coordinateMismatch.set(true); return json("{}").setResponseCode(400);
                }
                creditsArrived.countDown();
                releaseCredits.await(5, TimeUnit.SECONDS);
                JSONObject item = new JSONObject().put("video_type", "tv").put("version_runtime", durationMs / 1000.0)
                        .put("base_video_season", new JSONObject().put("season_number", expected.season()))
                        .put("base_video_episode", new JSONObject().put("episode_number", expected.episode()))
                        .put("marker", new JSONArray().put(new JSONObject().put("marker_type", "credits")
                                .put("time_start", (durationMs - 17_000) / 1000.0).put("time_end", durationMs / 1000.0)));
                return json(new JSONObject().put("items", new JSONArray().put(item)).toString());
            } catch (Exception error) { return json("{}").setResponseCode(400); }
        }
    }
    private static MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
}
