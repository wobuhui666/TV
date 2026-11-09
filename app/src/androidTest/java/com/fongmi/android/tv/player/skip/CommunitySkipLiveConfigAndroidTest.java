package com.fongmi.android.tv.player.skip;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackState;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.ech.EchSettings;
import com.github.catvod.utils.CallTimeout;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.CertificateException;
import java.security.cert.CertPathValidatorException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import static org.junit.Assert.*;

/** Opt-in measurement of an authorized private configuration; public output never contains its URLs. */
@RunWith(AndroidJUnit4.class)
public final class CommunitySkipLiveConfigAndroidTest {
    private static final Map<String, Object> SETTINGS = Map.ofEntries(
            Map.entry("player_engine", PlayerSetting.ENGINE_EXO), Map.entry("tunnel", false),
            Map.entry("incognito", true), Map.entry("preload", false), Map.entry("preload_next", false),
            Map.entry("subtitle_libass", false), Map.entry("ai_subtitle_enabled", false),
            Map.entry("community_skip_enabled", true), Map.entry("tmdb_connection_mode", Setting.TMDB_DIRECT));
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final ExecutorService sources = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "private-live-source-probe");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger lookupTasks = new AtomicInteger();
    private final AtomicInteger activeLookups = new AtomicInteger();
    private final AtomicBoolean allLookupsOffMain = new AtomicBoolean(true);
    private final ExecutorService metadata = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), task -> new Thread(task, "private-live-metadata")) {
        @Override protected void beforeExecute(Thread thread, Runnable task) {
            allLookupsOffMain.compareAndSet(true, Looper.myLooper() != Looper.getMainLooper());
            lookupTasks.incrementAndGet();
            activeLookups.incrementAndGet();
        }

        @Override protected void afterExecute(Runnable task, Throwable failure) {
            activeLookups.decrementAndGet();
        }
    };
    private final JSONObject report = new JSONObject();
    private final JSONArray stages = new JSONArray();
    private final AtomicReference<String> playerError = new AtomicReference<>();
    private final AtomicBoolean firstFrameRendered = new AtomicBoolean();
    private final AtomicInteger playbackHttpStatus = new AtomicInteger();
    private final AtomicInteger playbackErrorCode = new AtomicInteger();
    private final List<Long> automaticSeeks = new ArrayList<>();
    private CorePlaybackActivity activity;
    private PlayerManager manager;
    private VodPlaybackController controller;
    private CommunitySkipRuntime runtime;
    private History history;
    private String tmdbKey;
    private Doh originalDoh;
    private Object originalEch;
    private boolean hadOriginalEch;
    private boolean networkSelectionSaved;

    @Test(timeout = 420_000)
    public void shouldMeasureTheAuthorizedConfigurationAndActualPlaybackMetadata() throws Exception {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview", target.getPackageName());
        Map<String, ?> saved = new HashMap<>(Prefers.getPrefers().getAll());
        File privateDirectory = new File(target.getFilesDir(), "live-community-skip");
        String sourceUrl = consume(new File(privateDirectory, "source-url.txt"));
        tmdbKey = consume(new File(privateDirectory, "tmdb-key.txt"));
        assertTrue("Runtime TMDB credential format", tmdbKey.matches("[a-fA-F0-9]{32}"));
        report.put("schema", "tv.community-skip-live.v1").put("stages", stages)
                .put("credentialFilesRemoved", true).put("passed", false)
                .put("automaticSkipVerified", false).put("communityRecordFound", false)
                .put("episodeIdentityVerified", false).put("directPlaybackVerified", false);
        try {
            report.put("configStatus", "PREPARING_SETTINGS");
            JSONObject uiSetup = new JSONObject().put("phase", "UI_SETUP").put("status", "STARTED");
            stages.put(uiSetup);
            try {
                main(() -> SETTINGS.forEach(Prefers::put));
                uiSetup.put("status", "COMPLETED");
            } catch (AssertionError failure) {
                uiSetup.put("status", "FAILED").put("failureTypes", report.optJSONArray("uiFailureTypes"));
                report.put("configStatus", "SETTINGS_FAILURE");
                throw failure;
            }
            loadConfiguration(sourceUrl);
            sourceUrl = null;
            activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(target,
                    CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertTrue("Playback test window focused", waitFor(activity::hasWindowFocus, 8_000));
            List<Site> sites = VodConfig.get().getSites();
            int tested = 0, resolved = 0, playback = 0, matched = 0;
            int caseIndex = 0;
            // HTTP sources are not dependent on an ARM-only guarded JAR in this x86 validation package.
            List<Site> selected = new ArrayList<>();
            for (String name : List.of("非凡", "人人", "韩剧")) {
                for (Site site : sites) if (site.getName().contains(name)) { selected.add(site); break; }
            }
            for (Site site : selected) {
                if (tested++ >= 3) break;
                List<String> queries = site.getName().contains("非凡")
                        ? List.of("莲花楼", "绝命毒师") : site.getName().contains("人人")
                        ? List.of("白莲花度假村") : List.of("绝命毒师");
                for (String query : queries) {
                    JSONObject stage = new JSONObject().put("siteIndex", sites.indexOf(site))
                            .put("caseIndex", caseIndex++)
                            .put("sourceType", site.getType()).put("sourceKind", site.getType() == 3 ? "JAR" : "HTTP")
                            .put("phase", "SEARCH").put("status", "STARTED");
                    stages.put(stage);
                    long start = SystemClock.elapsedRealtime();
                    try {
                        Result result = call(() -> SiteApi.searchContent(site, query, false, "1"), 15_000);
                        stage.put("searchCount", result.getList().size());
                        report.put("sourceSearchVerified", report.optBoolean("sourceSearchVerified") || !result.getList().isEmpty());
                        Vod search = matchingDrama(result.getList(), query);
                        if (search == null) { stage.put("status", "NO_MATCHING_DRAMA"); continue; }
                        stage.put("phase", "DETAIL");
                        Vod media = call(() -> SiteApi.detailContent(site.getKey(), search.getId()).getVod(), 15_000);
                        List<Flag> flags = media.getFlags();
                        stage.put("flagCount", flags.size());
                        report.put("sourceDetailVerified", report.optBoolean("sourceDetailVerified") || !flags.isEmpty());
                        Flag line = directLine(flags);
                        if (line == null || line.getEpisodes().isEmpty()) { stage.put("status", "NO_EPISODES"); continue; }
                        stage.put("episodeCount", line.getEpisodes().size());
                        Episode episode = line.getEpisodes().get(0);
                        EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode(media.getName(), episode.getName());
                        stage.put("sourceSeasonExplicit", parsed != null && parsed.season() != null)
                                .put("sourceEpisodeGenerated", episode.isGeneratedLabel());
                        stage.put("phase", "PLAYER");
                        Result play = call(() -> SiteApi.playerContent(site.getKey(), line.getFlag(), episode.getUrl()), 15_000);
                        if (play.getUrl().v().isEmpty()) { stage.put("status", "EMPTY_PLAYER_URL"); continue; }
                        resolved++;
                        report.put("sourcePlayResolved", true);
                        if (play.needParse() || play.isUseParse()) {
                            if (play.getKey().isEmpty()) play.setKey(site.getKey());
                            stage.put("phase", "PARSE").put("parseRequired", true);
                            if (!resolveConfiguredParser(play, stage)) { stage.put("status", "PARSER_REQUIRED"); continue; }
                        }
                        stage.put("phase", "PLAYBACK");
                        int initialLookupTasks = lookupTasks.get();
                        preparePlayback(site, media, line, episode, play);
                        waitFor(() -> lookupTasks.get() > initialLookupTasks, 2_000);
                        long uiStart = SystemClock.elapsedRealtime();
                        instrumentation.runOnMainSync(() -> {});
                        long uiTurnMs = SystemClock.elapsedRealtime() - uiStart;
                        boolean nonBlocking = lookupTasks.get() > initialLookupTasks && allLookupsOffMain.get() && uiTurnMs < 1_000;
                        stage.put("lookupOffMainThread", allLookupsOffMain.get()).put("uiTurnMs", uiTurnMs);
                        report.put("backgroundQueryNonBlocking", report.optBoolean("backgroundQueryNonBlocking") || nonBlocking);
                        boolean ready = waitFor(() -> playerError.get() != null || manager.getPlaybackState() == Player.STATE_READY
                                && manager.getDuration() > 0 && manager.canSeek(), 18_000);
                        if (!ready || playerError.get() != null) {
                            stage.put("status", ready ? "PLAYBACK_ERROR" : "PLAYBACK_TIMEOUT")
                                    .put("skipStatus", "DURATION_UNAVAILABLE");
                            if (playbackHttpStatus.get() > 0) stage.put("httpStatus", playbackHttpStatus.get());
                            if (playbackErrorCode.get() > 0) stage.put("playbackErrorCode", playbackErrorCode.get());
                            if (recordIdentity(stage) != null) matched++;
                            continue;
                        }
                        long duration = read(manager::getDuration);
                        stage.put("durationMs", duration);
                        main(manager::play);
                        if (!waitFor(manager::isPlaying, 4_000)) {
                            stage.put("status", "NOT_PLAYING");
                            if (recordIdentity(stage) != null) matched++;
                            continue;
                        }
                        long before = read(manager::getPosition);
                        main(() -> controller.onTimeChanged(System.currentTimeMillis(), manager.getPosition(), duration));
                        boolean advancedDuringLookup = false;
                        long lastPosition = before;
                        long observationDeadline = SystemClock.elapsedRealtime() + 1_000;
                        while (SystemClock.elapsedRealtime() < observationDeadline) {
                            boolean running = activeLookups.get() > 0;
                            SystemClock.sleep(100);
                            long position = read(manager::getPosition);
                            long advance = position - lastPosition;
                            if ((running || activeLookups.get() > 0) && advance > 0 && advance < 1_500)
                                advancedDuringLookup = true;
                            lastPosition = position;
                            main(() -> controller.onTimeChanged(System.currentTimeMillis(), manager.getPosition(), duration));
                        }
                        boolean progress = lastPosition > before;
                        boolean trackSelected = read(() -> manager.getPlayer() instanceof ExoPlayer exo && exo.getVideoFormat() != null);
                        boolean decoded = firstFrameRendered.get();
                        stage.put("videoDecoded", decoded).put("playbackProgressVerified", progress)
                                .put("videoTrackSelected", trackSelected)
                                .put("playbackAdvancedWhileLookup", advancedDuringLookup);
                        report.put("videoDecoded", report.optBoolean("videoDecoded") || decoded)
                                .put("playbackProgressVerified", report.optBoolean("playbackProgressVerified") || progress)
                                .put("playbackAdvancedWhileLookup", report.optBoolean("playbackAdvancedWhileLookup") || advancedDuringLookup);
                        if (progress && decoded) playback++;
                        main(() -> { manager.pause(); controller.onTimeChanged(System.currentTimeMillis(), manager.getPosition(), duration); });
                        stage.put("phase", "METADATA");
                        waitFor(this::lookupCompleted, 19_000);
                        EpisodeIdentity identity = recordIdentity(stage);
                        if (identity != null) matched++;
                        List<SkipSegment> ranges = read(this::ranges);
                        int intro = 0, credits = 0;
                        for (SkipSegment range : ranges) if (range.type() == SkipSegment.Type.INTRO) intro++; else credits++;
                        stage.put("introCount", intro).put("creditsCount", credits);
                        report.put("communityRecordFound", report.optBoolean("communityRecordFound") || !ranges.isEmpty());
                        EnumSet<SkipSegment.Type> checkedTypes = EnumSet.noneOf(SkipSegment.Type.class);
                        for (SkipSegment range : ranges) {
                            if (!SegmentSkipPolicy.isUsable(range, duration) || !checkedTypes.add(range.type())) continue;
                            boolean skipped = verifyAutomaticSeek(range, duration);
                            stage.put(range.type() == SkipSegment.Type.INTRO ? "introAutomaticSkipVerified" : "creditsAutomaticSkipVerified", skipped);
                            if (skipped) {
                                stage.put("automaticSkipVerified", true);
                                report.put("automaticSkipVerified", true);
                            }
                        }
                        stage.put("actualSeekCount", automaticSeeks.size());
                        stage.put("skipStatus", identity == null ? "IDENTITY_UNCONFIRMED"
                                : ranges.isEmpty() ? "NO_MATCHED_COMMUNITY_RANGES" : "MATCHED_COMMUNITY_RANGES");
                        stage.put("status", identity == null ? "IDENTITY_UNCONFIRMED"
                                : ranges.isEmpty() ? "NO_MATCHED_COMMUNITY_RANGES" : "MATCHED_COMMUNITY_RANGES");
                    } catch (Throwable error) {
                        stage.put("status", "SOURCE_FAILURE").put("errorTypes", errorTypes(error));
                    } finally {
                        releasePlayback();
                        stage.put("elapsedMs", SystemClock.elapsedRealtime() - start);
                        writeReport();
                    }
                }
            }
            report.put("testedSiteCount", tested).put("testedCaseCount", caseIndex).put("resolvedCaseCount", resolved)
                    .put("playableCaseCount", playback).put("matchedCaseCount", matched)
                    .put("lookupCount", lookupTasks.get()).put("lookupOffMainThread", allLookupsOffMain.get())
                    .put("directPlaybackVerified", playback > 0).put("episodeIdentityVerified", matched > 0)
                    .put("measurementCompleted", true).put("passed", true);
            // A completed availability measurement can honestly report unavailable sources or no coverage.
        } catch (Throwable failure) {
            report.put("failureTypes", errorTypes(failure));
            throw new AssertionError("Private live configuration measurement failed; inspect sanitized report");
        } finally {
            releasePlayback();
            if (activity != null) main(activity::finish);
            sources.shutdownNow();
            metadata.shutdownNow();
            OkHttp.client().dispatcher().cancelAll();
            report.put("workerCleanupComplete", sources.awaitTermination(3, TimeUnit.SECONDS)
                    && metadata.awaitTermination(3, TimeUnit.SECONDS));
            BaseLoader.get().clear();
            VodConfig.get().clear();
            restoreNetworkSelection();
            main(() -> {
                for (String key : SETTINGS.keySet()) {
                    if (saved.containsKey(key)) Prefers.put(key, saved.get(key)); else Prefers.remove(key);
                }
            });
            tmdbKey = null;
            writeReport();
        }
    }

    private void loadConfiguration(String sourceUrl) throws Exception {
        Map<String, ?> preferences = Prefers.getPrefers().getAll();
        hadOriginalEch = preferences.containsKey("ech_enabled");
        originalEch = preferences.get("ech_enabled");
        originalDoh = OkHttp.dns().getDoh();
        networkSelectionSaved = true;
        long start = SystemClock.elapsedRealtime();
        report.put("defaultConfigLoaded", false).put("configObserverRestored", true);
        for (String mode : List.of("DEFAULT", "ECH_OFF", "SYSTEM_DNS")) {
            JSONObject stage = new JSONObject().put("phase", "CONFIG").put("mode", mode).put("status", "STARTED");
            stages.put(stage);
            report.put("configMode", mode);
            boolean loaded = false;
            try {
                OkHttp.get().clear();
                EchSettings.setEnabled(mode.equals("DEFAULT"));
                OkHttp.dns().setDoh(mode.equals("SYSTEM_DNS") ? new Doh().url("") : originalDoh);
                OkHttp.client().connectionPool().evictAll();
                loaded = loadConfigurationAttempt(sourceUrl, stage);
            } catch (Throwable failure) {
                if (stage.getString("status").equals("STARTED")) setConfigStatus(stage, "MODE_SETUP_FAILURE");
                if (!stage.has("failureTypes")) stage.put("failureTypes", errorTypes(failure));
                if (!stage.has("failureCategory")) stage.put("failureCategory", configFailureCategory(failure));
            } finally {
                if (mode.equals("DEFAULT")) report.put("defaultConfigLoaded", loaded);
                report.put("configLoadMs", SystemClock.elapsedRealtime() - start);
                writeReport();
            }
            if (loaded) return;
        }
        report.put("configStatus", "ALL_MODES_FAILED");
        fail("Authorized configuration failed in all three real network modes");
    }

    private boolean loadConfigurationAttempt(String sourceUrl, JSONObject stage) throws Exception {
        setConfigStatus(stage, "STARTED");
        writeReport();
        long start = SystemClock.elapsedRealtime();
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicBoolean succeeded = new AtomicBoolean();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger httpStatus = new AtomicInteger();
        AtomicReference<String> networkFailure = new AtomicReference<>();
        AtomicReference<String> tlsFailure = new AtomicReference<>();
        AtomicReference<JSONArray> networkTypes = new AtomicReference<>();
        AtomicReference<String> callbackFailure = new AtomicReference<>();
        OkHttpClient original;
        OkHttpClient observer;
        try {
            synchronized (OkHttp.class) {
                original = OkHttp.client();
                observer = original.newBuilder().connectionPool(new ConnectionPool()).addInterceptor(chain -> {
                    if (!"VodConfig".equals(chain.request().tag())) return chain.proceed(chain.request());
                    requests.incrementAndGet();
                    try {
                        Response response = chain.proceed(chain.request());
                        httpStatus.set(response.code());
                        return response;
                    } catch (Exception failure) {
                        networkFailure.set(configFailureCategory(failure));
                        tlsFailure.set(tlsFailureCategory(failure));
                        networkTypes.set(errorTypes(failure));
                        throw failure;
                    }
                }).build();
                setField(OkHttp.get(), "client", observer);
            }
        } catch (Exception | LinkageError failure) {
            setConfigStatus(stage, "OBSERVER_INSTALL_FAILED");
            stage.put("failureTypes", errorTypes(failure)).put("failureCategory", configFailureCategory(failure));
            writeReport();
            throw failure;
        }
        try {
            setConfigStatus(stage, "LOAD_REQUESTED");
            try {
                call(() -> {
                    VodConfig.load(Config.find(sourceUrl, 0), new Callback() {
                        @Override public void success() { succeeded.set(true); loaded.countDown(); }
                        @Override public void error(String message) {
                            callbackFailure.set(configMessageCategory(message));
                            loaded.countDown();
                        }
                    });
                    return null;
                }, 5_000);
            } catch (Exception | LinkageError failure) {
                setConfigStatus(stage, "SYNC_FAILURE");
                stage.put("failureTypes", errorTypes(failure)).put("failureCategory", configFailureCategory(failure));
                throw failure;
            }
            setConfigStatus(stage, "LOAD_DISPATCHED");
            if (!loaded.await(90, TimeUnit.SECONDS)) {
                setConfigStatus(stage, "CALLBACK_TIMEOUT");
                stage.put("failureCategory", "CALLBACK_TIMEOUT");
                return false;
            }
            if (!succeeded.get()) {
                setConfigStatus(stage, "CALLBACK_ERROR");
                String category = httpFailureCategory(httpStatus.get());
                if (category == null) category = networkFailure.get();
                if (category == null) category = callbackFailure.get();
                stage.put("failureCategory", category == null ? "CALLBACK_ERROR" : category);
                return false;
            }
            int count = VodConfig.get().getSites().size();
            stage.put("itemCount", count);
            report.put("configuredSiteCount", count);
            if (count == 0) {
                setConfigStatus(stage, "EMPTY_SITES");
                stage.put("failureCategory", "EMPTY_SITES");
                return false;
            }
            setConfigStatus(stage, "LOADED");
            report.put("configLoaded", true).put("configFetchedByApp", true);
            return true;
        } finally {
            try {
                if (!succeeded.get()) {
                    OkHttp.cancel("VodConfig");
                    Field futureField = VodConfig.class.getSuperclass().getDeclaredField("future");
                    futureField.setAccessible(true);
                    Future<?> future = (Future<?>) futureField.get(VodConfig.get());
                    if (future != null) future.cancel(true);
                }
            } finally {
                synchronized (OkHttp.class) { setField(OkHttp.get(), "client", original); }
                observer.connectionPool().evictAll();
                boolean restored = field(OkHttp.get(), "client") == original;
                long elapsed = SystemClock.elapsedRealtime() - start;
                report.put("configObserverRestored", report.optBoolean("configObserverRestored") && restored);
                stage.put("elapsedMs", elapsed).put("requestCount", requests.get()).put("observerRestored", restored);
                if (httpStatus.get() > 0) stage.put("httpStatus", httpStatus.get());
                if (tlsFailure.get() != null) stage.put("tlsFailureCategory", tlsFailure.get());
                if (networkTypes.get() != null) stage.put("networkFailureTypes", networkTypes.get());
                if (networkTypes.get() != null && !stage.has("failureTypes")) stage.put("failureTypes", networkTypes.get());
                if (networkFailure.get() != null && !stage.has("failureCategory"))
                    stage.put("failureCategory", networkFailure.get());
                writeReport();
            }
        }
    }

    private void restoreNetworkSelection() throws Exception {
        if (!networkSelectionSaved) return;
        if (hadOriginalEch) Prefers.put("ech_enabled", originalEch); else Prefers.remove("ech_enabled");
        OkHttp.dns().setDoh(originalDoh);
        OkHttp.client().connectionPool().evictAll();
        Map<String, ?> preferences = Prefers.getPrefers().getAll();
        boolean restored = preferences.containsKey("ech_enabled") == hadOriginalEch
                && java.util.Objects.equals(originalEch, preferences.get("ech_enabled"))
                && originalDoh.toString().equals(OkHttp.dns().getDoh().toString());
        report.put("networkPreferencesRestored", restored);
        assertTrue("Restore the original ECH preference and DoH selection", restored);
    }

    private static String tlsFailureCategory(Throwable failure) {
        boolean handshake = false, protocol = false, certificate = false, ech = false, peerClosed = false, tls = false;
        for (int count = 0; failure != null && count < 8; count++, failure = failure.getCause()) {
            handshake |= failure instanceof SSLHandshakeException;
            protocol |= failure instanceof SSLProtocolException;
            certificate |= failure instanceof CertificateException || failure instanceof CertPathValidatorException
                    || failure instanceof SSLPeerUnverifiedException;
            tls |= failure instanceof SSLException;
            String name = failure.getClass().getSimpleName();
            String message = failure.getMessage();
            ech |= name.equals("EchRejectedException") || message != null && message.contains("ECH_REJECTED");
            if (message != null) {
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                peerClosed |= lower.contains("peer closed") || lower.contains("closed by peer")
                        || lower.contains("connection closed") || lower.contains("connection reset")
                        || lower.contains("peer shut down") || lower.contains("unexpected end");
            }
        }
        if (ech) return "ECH";
        if (certificate) return "CERTIFICATE";
        if (peerClosed && tls) return "PEER_CLOSED";
        if (protocol) return "PROTOCOL";
        if (handshake) return "HANDSHAKE";
        return tls ? "OTHER" : null;
    }

    private void setConfigStatus(JSONObject stage, String status) throws Exception {
        stage.put("status", status);
        report.put("configStatus", status);
    }

    private static String httpFailureCategory(int status) {
        if (status == 401) return "HTTP_AUTHENTICATION_FAILED";
        if (status == 403) return "HTTP_FORBIDDEN";
        if (status == 404) return "HTTP_NOT_FOUND";
        return status >= 400 ? "HTTP_ERROR" : null;
    }

    private static String configFailureCategory(Throwable failure) {
        for (int count = 0; failure != null && count < 8; count++, failure = failure.getCause()) {
            if (failure instanceof UnknownHostException) return "DNS_FAILURE";
            if (failure instanceof SocketTimeoutException) return "NETWORK_TIMEOUT";
            if (failure instanceof TimeoutException) return "DISPATCH_TIMEOUT";
            if (failure instanceof SSLException) return "TLS_FAILURE";
            if (failure instanceof ConnectException) return "CONNECTION_FAILURE";
            if (failure instanceof InterruptedException || failure instanceof InterruptedIOException)
                return "INTERRUPTED";
            if (failure instanceof LinkageError) return "PLUGIN_LINKAGE_FAILURE";
        }
        return "UNCLASSIFIED_EXCEPTION";
    }

    private static String configMessageCategory(String message) {
        // Inspect locally and emit only this finite set; source error text can contain credentials.
        if (message == null) return "CALLBACK_ERROR";
        if (message.contains("Unable to resolve host") || message.contains("UnknownHostException")) return "DNS_FAILURE";
        if (message.contains("timeout") || message.contains("timed out")) return "NETWORK_TIMEOUT";
        if (message.contains("SSL") || message.contains("certificate")) return "TLS_FAILURE";
        if (message.contains("Failed to connect") || message.contains("ConnectException")) return "CONNECTION_FAILURE";
        if (message.contains("dlopen") || message.contains("UnsatisfiedLinkError") || message.contains("ELF"))
            return "PLUGIN_LINKAGE_FAILURE";
        if (message.contains("ClassNotFoundException") || message.contains("NoClassDefFoundError"))
            return "PLUGIN_CLASS_FAILURE";
        if (message.contains("Expected BEGIN_OBJECT") || message.contains("JsonSyntaxException")
                || message.contains("MalformedJsonException")) return "JSON_PARSE_FAILURE";
        return "CALLBACK_ERROR";
    }

    private void preparePlayback(Site site, Vod media, Flag line, Episode episode, Result play) {
        main(() -> {
            playerError.set(null);
            firstFrameRendered.set(false);
            playbackHttpStatus.set(0);
            playbackErrorCode.set(0);
            automaticSeeks.clear();
            manager = new PlayerManager(new PlayerCallback());
            manager.getPlayer().addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException failure) {
                    playbackErrorCode.set(failure.errorCode);
                    Throwable cause = failure;
                    for (int count = 0; cause != null && count < 8; count++, cause = cause.getCause()) {
                        if (cause instanceof HttpDataSource.InvalidResponseCodeException response)
                            playbackHttpStatus.set(response.responseCode);
                    }
                }
            });
            if (manager.getPlayer() instanceof ExoPlayer exo) exo.addAnalyticsListener(new AnalyticsListener() {
                @Override public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
                    firstFrameRendered.set(true);
                }
            });
            manager.bindPlayerView(activity.view);
            history = new History();
            history.setKey("private-live-config-" + VodConfig.get().getSites().indexOf(site));
            history.setVodFlag(line.getFlag());
            history.setVodRemarks(episode.getName());
            history.setEpisodeUrl(episode.getUrl());
            line.toggle(true, episode);
            VodPlaybackState state = new VodPlaybackState();
            state.setMedia(media);
            state.setHistory(history);
            state.setFlags(List.of(line));
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
            runtime = new CommunitySkipRuntime(metadata, () -> new EpisodeIdentityResolver(tmdbKey), CommunitySkipApi::new);
            setField(controller, "communitySkip", runtime);
            runtime.prepare(media, history, episode);
            manager.start(PlaySpec.from(play, history.getKey(), MediaMetadata.EMPTY), 18_000, 0);
            manager.getPlayer().setVolume(0);
        });
    }

    private boolean resolveConfiguredParser(Result play, JSONObject stage) throws Exception {
        List<Parse> jsonParsers = VodConfig.get().getParses().stream().filter(parse -> parse.getType() == 1).toList();
        if (jsonParsers.size() != 1) {
            stage.put("parseStatus", "NO_UNIQUE_JSON_PARSER");
            return false;
        }
        Parse original = VodConfig.get().getParse();
        AtomicReference<ParseJob> job = new AtomicReference<>();
        AtomicReference<String> resolvedUrl = new AtomicReference<>();
        AtomicReference<Map<String, String>> resolvedHeaders = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        try {
            main(() -> {
                setField(VodConfig.get(), "parse", jsonParsers.get(0));
                job.set(ParseJob.create(new ParseCallback() {
                    @Override public void onParseSuccess(Map<String, String> headers, String url, String from) {
                        resolvedHeaders.set(headers == null ? Map.of() : new HashMap<>(headers));
                        resolvedUrl.set(url);
                        completed.countDown();
                    }

                    @Override public void onParseError() { completed.countDown(); }
                }).start(play, true));
            });
            boolean callback = completed.await(20, TimeUnit.SECONDS);
            boolean resolved = callback && resolvedUrl.get() != null && !resolvedUrl.get().isEmpty();
            stage.put("parseStatus", resolved ? "RESOLVED" : callback ? "ERROR" : "TIMEOUT")
                    .put("parserResolved", resolved);
            if (!resolved) return false;
            play.setUrl(resolvedUrl.get());
            play.setPlayUrl("");
            // Result.setHeader only accepts an empty previous header; use the parser's actual map.
            setField(play, "header", new HashMap<>(resolvedHeaders.get()));
            play.setParse(0);
            report.put("sourceParseResolved", true);
            return true;
        } finally {
            main(() -> {
                try { if (job.get() != null) job.get().stop(); }
                finally { setField(VodConfig.get(), "parse", original); }
            });
        }
    }

    private boolean verifyAutomaticSeek(SkipSegment range, long duration) {
        if (read(() -> automaticSeeks.contains(range.endMs()) && manager.getPosition() >= range.endMs()
                && manager.getPosition() < range.endMs() + 5_000)) return true;
        int previousSeeks = read(automaticSeeks::size);
        main(() -> {
            manager.pause(); manager.seekTo(range.startMs() + Math.min(250, (range.endMs() - range.startMs()) / 4));
            controller.onSeek();
        });
        if (!waitFor(() -> manager.getPlaybackState() == Player.STATE_READY, 6_000)) return false;
        main(manager::play);
        if (!waitFor(manager::isPlaying, 3_000)) return false;
        main(() -> controller.onTimeChanged(System.currentTimeMillis(), manager.getPosition(), duration));
        if (!waitFor(() -> automaticSeeks.size() > previousSeeks
                && automaticSeeks.subList(previousSeeks, automaticSeeks.size()).contains(range.endMs()), 3_000)) return false;
        main(manager::pause);
        return waitFor(() -> manager.getPlaybackState() == Player.STATE_READY
                && Math.abs(manager.getPosition() - range.endMs()) < 1_000, 6_000);
    }

    private EpisodeIdentity recordIdentity(JSONObject stage) throws Exception {
        waitFor(() -> active() == null || (boolean) field(active(), "identityDone"), 10_000);
        EpisodeIdentity identity = read(() -> active() == null ? null : (EpisodeIdentity) field(active(), "identity"));
        stage.put("identityStatus", identity == null ? "UNCONFIRMED" : "MATCHED")
                .put("episodeIdentityVerified", identity != null);
        if (identity != null) stage.put("tmdbId", identity.tmdbId()).put("season", identity.season()).put("episode", identity.episode())
                .put("seasonConfirmedByMetadata", !stage.optBoolean("sourceSeasonExplicit"));
        return identity;
    }

    private boolean lookupCompleted() {
        Object session = active();
        if (session == null || (boolean) field(session, "identityDone") && field(session, "identity") == null) return true;
        Future<?> future = (Future<?>) field(session, "task");
        return (boolean) field(session, "fetchStarted") && future != null && future.isDone();
    }

    private Object active() { return runtime == null ? null : field(runtime, "active"); }

    @SuppressWarnings("unchecked") private List<SkipSegment> ranges() {
        Object session = active();
        Object policy = session == null ? null : field(session, "policy");
        return policy == null ? List.of() : (List<SkipSegment>) field(policy, "segments");
    }

    private static Vod matchingDrama(List<Vod> items, String query) {
        String expected = switch (query) {
            case "绝命毒师" -> "绝命毒师第一季";
            case "白莲花度假村" -> "白莲花度假村第一季";
            default -> query;
        };
        for (Vod item : items) if (item.getName().replaceAll("\\s", "").equals(expected)
                && EpisodeIdentityResolver.parseEpisode(item.getName(), "第01集") != null) return item;
        return null;
    }

    private static Flag directLine(List<Flag> flags) {
        for (Flag line : flags) if (!line.getEpisodes().isEmpty() && (line.getFlag().toLowerCase().contains("m3u8")
                || line.getEpisodes().get(0).getUrl().toLowerCase().contains(".m3u8"))) return line;
        return flags.isEmpty() ? null : flags.get(0);
    }

    private void releasePlayback() {
        main(() -> {
            if (runtime != null) runtime.stop();
            if (controller != null) controller.stopCommunitySkip();
            if (manager != null) manager.release();
            manager = null; controller = null; runtime = null; history = null;
        });
    }

    private <T> T call(Callable<T> task, long milliseconds) throws Exception {
        Future<T> future = sources.submit(() -> {
            try (CallTimeout timeout = new CallTimeout(milliseconds, false)) {
                timeout.start(Thread.currentThread()::interrupt);
                Runnable restore = timeout.enter();
                try { return task.call(); } finally { restore.run(); }
            }
        });
        try { return future.get(milliseconds + 2_000, TimeUnit.MILLISECONDS); }
        catch (Exception error) { future.cancel(true); throw new Exception("LIVE_SOURCE_CALL_FAILED", error); }
    }

    private boolean waitFor(BooleanSupplier predicate, long milliseconds) {
        long deadline = SystemClock.elapsedRealtime() + milliseconds;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (read(predicate::getAsBoolean)) return true;
            SystemClock.sleep(40);
        }
        return false;
    }

    private static String consume(File file) throws Exception {
        assertTrue("Stage private runtime input", file.isFile());
        String value = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
        assertTrue("Remove runtime credential file after reading", file.delete());
        return value;
    }

    private void writeReport() throws Exception {
        File directory = target.getExternalFilesDir("community-skip-live-verification");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, "report.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JSONArray errorTypes(Throwable error) {
        JSONArray types = new JSONArray();
        for (int count = 0; error != null && count < 5; count++, error = error.getCause()) {
            String name = error.getClass().getSimpleName();
            types.put(name.matches("[A-Za-z0-9_$]{1,100}") ? name : "UnknownFailure");
        }
        return types;
    }

    private void main(Runnable task) { read(() -> { task.run(); return null; }); }
    private <T> T read(Supplier<T> task) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(task.get()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) {
            try { report.put("uiFailureTypes", errorTypes(failure.get())); }
            catch (Exception ignored) { }
            throw new AssertionError("Private live UI check failed");
        }
        return value.get();
    }
    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Private live state inspection failed"); }
    }
    private static void setField(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Private live transport setup failed"); }
    }
    private final class PlayerCallback implements PlayerManager.Callback {
        @Override public void onPrepare() { }
        @Override public void onTracksChanged() { }
        @Override public void onDecodeChanged() { }
        @Override public void onMediaOptionsChanged() { }
        @Override public void onError(String message) { playerError.set("PLAYBACK_ERROR"); }
        @Override public void onPlayerRebuild(Player player) { playerError.set("DECODER_REBUILT"); }
        @Override public void onDanmakuSourceChanged(Uri uri) { }
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) { }
        @Override public void onDanmakuEnabledChanged(boolean enabled) { }
        @Override public void onDanmakuSent(String text) { }
    }
}
