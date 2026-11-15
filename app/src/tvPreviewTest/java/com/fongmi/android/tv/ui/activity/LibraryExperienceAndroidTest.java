package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLibraryApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.source.LibraryIdentity;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.custom.JetStreamVodCardRootLayout;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

/** Offline production activities with real reference artwork and a held media request. */
@RunWith(AndroidJUnit4.class)
public final class LibraryExperienceAndroidTest {
    private static final String[] KEYS = {"tv_experience_mode_v1", "source_selection_mode", "tmdb_connection_mode", "tmdb_proxy_url", "incognito", "player_engine", "community_skip_enabled", "preload_next", "tv_light_theme", "tv_theme_mode", "player_scale"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final List<Activity> activities = new ArrayList<>();
    private final Map<String, Object> saved = new HashMap<>();
    private final CountDownLatch releasePlayer = new CountDownLatch(1);
    private final CountDownLatch releaseDetails = new CountDownLatch(1);
    private final CountDownLatch detailsEntered = new CountDownLatch(1);
    private final CountDownLatch releaseSearch = new CountDownLatch(1);
    private final CountDownLatch searchEntered = new CountDownLatch(1);
    private final CountDownLatch releaseLink = new CountDownLatch(1);
    private final CountDownLatch releaseSeason = new CountDownLatch(1);
    private final CountDownLatch seasonEntered = new CountDownLatch(1);
    private final AtomicInteger plays = new AtomicInteger();
    private final AtomicInteger linkRequests = new AtomicInteger();
    private final List<String> prefetchedEpisodes = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> requestedMedia = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> requestedSites = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger extrasRequests = new AtomicInteger();
    private final Map<String, byte[]> artwork = new HashMap<>();
    private OkHttpClient original, originalTmdb, fixture;
    private Object originalSites, originalHome, originalConfig;
    private boolean series, anime, noSources, tmdbFails, holdDetails, holdSearch, summaryTypesMissing;
    private boolean directPrefetch, fallbackLogo, reorderedEpisodes, generatedEpisodes, absoluteEpisodes, holdLink;
    private boolean multipleSeasons, holdSecondSeason, multipleLines;
    private String failedDetailSite = "", failedPlaybackSite = "", heldDetailSite = "";

    @Before public void setup() throws Exception {
        assertEquals("Only an isolated preview application", "com.fongmi.android.tv.preview", instrumentation.getTargetContext().getPackageName());
        File root = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "library-fixtures");
        for (String name : new String[]{"movie-backdrop.jpg", "series-backdrop.jpg", "anime-backdrop.jpg", "movie-episode.jpg", "series-episode.jpg", "anime-episode.jpg"}) {
            File file = new File(root, name);
            assertTrue("Reference fixture exists: " + file, file.isFile());
            artwork.put(name, Files.readAllBytes(file.toPath()));
        }
        File[] images = root.listFiles((directory, name) -> name.endsWith(".jpg") || name.endsWith(".png"));
        if (images != null) for (File file : images) artwork.put(file.getName(), Files.readAllBytes(file.toPath()));
        main(() -> {
            for (String key : KEYS) if (Prefers.getPrefers().contains(key)) saved.put(key, Prefers.getPrefers().getAll().get(key));
            original = OkHttp.client(); originalTmdb = (OkHttpClient) field(TmdbNetwork.class, "client");
            originalSites = field(VodConfig.get(), "sites"); originalHome = field(VodConfig.get(), "home"); originalConfig = field(VodConfig.get(), "config");
            VodConfig.get().init();
            List<Site> sites = new ArrayList<>();
            for (String key : new String[]{"fixture-a", "fixture-b"}) {
                Site site = Site.get(key, key.equals("fixture-a") ? "影视源一" : "影视源二");
                site.setApi("https://library-fixture.invalid/" + key); set(site, "type", 4); sites.add(site);
            }
            set(VodConfig.get(), "sites", sites); set(VodConfig.get(), "home", sites.get(0));
            ExperienceSettings.putMode(ExperienceMode.LIBRARY);
            Prefers.put("source_selection_mode", "LEGACY"); Prefers.put("incognito", true);
            Prefers.put("community_skip_enabled", false); Prefers.put("preload_next", false);
            PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
            Setting.putTmdbConnectionMode(Setting.TMDB_CUSTOM_PROXY); Setting.putTmdbProxyUrl("https://library-fixture.invalid/");
            com.fongmi.android.tv.ui.rex.RexTheme.setLight(false);
            TmdbLibraryApi.clearCache();
            fixture = new OkHttpClient.Builder().addInterceptor(chain -> {
                okhttp3.HttpUrl url = chain.request().url();
                if (!url.host().equals("library-fixture.invalid") && !url.host().equals("i.ytimg.com")) throw new IOException("Offline library fixture");
                byte[] body;
                String contentType = "application/json";
                if (url.host().equals("i.ytimg.com")) {
                    String trailer = url.encodedPath().contains("fixture456") ? "movie-trailer-2.jpg" : url.encodedPath().contains("fixture789") ? "movie-trailer-3.jpg" : "movie-trailer.jpg";
                    body = artwork.getOrDefault(trailer, artwork.get("movie-backdrop.jpg")); contentType = "image/jpeg";
                } else if (url.encodedPath().contains("/t/p/")) {
                    String name = url.encodedPath().substring(url.encodedPath().lastIndexOf('/') + 1);
                    if (name.endsWith(".png") && !artwork.containsKey(name)) throw new IOException("Missing logo fixture: " + name);
                    body = artwork.getOrDefault(name, artwork.get("movie-backdrop.jpg")); contentType = "image/jpeg";
                    if (name.endsWith(".png")) contentType = "image/png";
                } else if (url.encodedPath().startsWith("/fixture-")) {
                    if (url.queryParameter("play") != null) {
                        boolean speculative = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                                .anyMatch(frame -> frame.getClassName().equals("com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch"));
                        if (speculative) {
                            prefetchedEpisodes.add(url.queryParameter("play")); linkRequests.incrementAndGet();
                            if (holdLink) {
                                try { if (!releaseLink.await(30, TimeUnit.SECONDS)) throw new IOException("Held link timed out"); }
                                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                            }
                            if (!directPrefetch) return response(chain.request(), "{\"parse\":1,\"url\":\"https://library-fixture.invalid/unresolved\",\"flag\":\"线路一\"}", "application/json");
                            return response(chain.request(), "{\"parse\":0,\"url\":\"https://library-fixture.invalid/held-media.m3u8\"}", "application/json");
                        }
                        requestedMedia.add(url.queryParameter("play")); requestedSites.add(url.encodedPath()); plays.incrementAndGet();
                        if (url.encodedPath().equals(failedPlaybackSite)) throw new IOException("Fixture source playback failure");
                        try { if (!releasePlayer.await(45, TimeUnit.SECONDS)) throw new IOException("Held playback timed out"); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                        throw new IOException("Held fixture never decodes remote media");
                    }
                    if (url.queryParameter("ids") != null && holdDetails
                            && (heldDetailSite.isEmpty() || url.encodedPath().equals(heldDetailSite))) {
                        detailsEntered.countDown();
                        try { if (!releaseDetails.await(45, TimeUnit.SECONDS)) throw new IOException("Held source detail timed out"); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    }
                    if (url.queryParameter("ids") != null && url.encodedPath().equals(failedDetailSite)) throw new IOException("Fixture source detail failure");
                    body = sourceResponse(url).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    if (tmdbFails) throw new IOException("Offline TMDB failure");
                    if (holdSecondSeason && url.encodedPath().endsWith("/season/2")) {
                        seasonEntered.countDown();
                        try { if (!releaseSeason.await(45, TimeUnit.SECONDS)) throw new IOException("Held season timed out"); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    }
                    if (holdSearch && url.encodedPath().contains("/search/multi")) {
                        searchEntered.countDown();
                        try { if (!releaseSearch.await(45, TimeUnit.SECONDS)) throw new IOException("Held TMDB search timed out"); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    }
                    body = metadataResponse(url).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline library fixture").body(ResponseBody.create(body, MediaType.get(contentType))).build();
            }).build();
            set(OkHttp.get(), "client", fixture); set(TmdbNetwork.class, "client", fixture);
        });
    }

    @After public void cleanup() {
        for (Activity active : allActivities()) if (!activities.contains(active) && (active instanceof LibraryDetailActivity
                || active instanceof LibrarySearchActivity || active instanceof LibraryImagesActivity || active instanceof VideoActivity
                || active instanceof ExperienceModeActivity)) activities.add(active);
        main(() -> { for (int i = activities.size() - 1; i >= 0; i--) if (!activities.get(i).isDestroyed()) activities.get(i).finish(); });
        releasePlayer.countDown();
        releaseDetails.countDown();
        releaseSearch.countDown();
        releaseLink.countDown();
        releaseSeason.countDown();
        await("Activities close", () -> activities.stream().allMatch(Activity::isDestroyed));
        await("Fixture calls finish", () -> fixture.dispatcher().runningCallsCount() == 0);
        main(() -> {
            set(OkHttp.get(), "client", original); set(TmdbNetwork.class, "client", originalTmdb);
            set(VodConfig.get(), "sites", originalSites); set(VodConfig.get(), "home", originalHome); set(VodConfig.get(), "config", originalConfig);
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            for (String key : KEYS) {
                Object value = saved.get(key);
                if (value instanceof String text) editor.putString(key, text);
                else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                else if (value instanceof Integer number) editor.putInt(key, number);
                else editor.remove(key);
            }
            assertTrue(editor.commit()); TmdbLibraryApi.clearCache(); fixture.connectionPool().evictAll();
        });
    }

    @Test public void movieUsesReferenceLayoutAndOnlyPlaysAfterRemoteConfirmation() {
        LibraryDetailActivity detail = open(false, false);
        await("Resources complete", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        assertEquals(0, plays.get());
        awaitArtwork(detail);
        capturePage(detail, "library-movie-reference");
        captureLowerSections(detail, "library-movie-sections");
        main(() -> assertTrue(detail.findViewById(R.id.play).requestFocus()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        VideoActivity video = awaitPlayer();
        await("Media requested", () -> plays.get() == 1);
        assertTrue(video.isFullscreenForPlayback());
        assertTrue(video.getIntent().getBooleanExtra("libraryPlayback", false));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Return to the same work page", () -> video.isDestroyed() && detail.hasWindowFocus());
        assertTrue(detail.findViewById(R.id.play).hasFocus());
        assertEquals(1, plays.get());
    }

    @Test public void seriesShowsEpisodeCardsAndResolvesTheExactSelectedEpisode() {
        LibraryDetailActivity detail = open(true, false);
        await("TMDB episode cards", () -> ((List<?>) field(detail, "episodes")).size() == 4);
        awaitArtwork(detail);
        capturePage(detail, "library-series-reference");
        captureLowerSections(detail, "library-series-sections");
        main(() -> assertTrue(detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2").requestFocus()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        VideoActivity video = awaitPlayer();
        await("Second episode selected", () -> !requestedMedia.isEmpty());
        assertEquals("second", requestedMedia.get(0));
        assertEquals(2, video.getIntent().getIntExtra("mediaEpisode", -1));
        assertTrue(video.isFullscreenForPlayback());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Episode focus survives the list redraw", () -> video.isDestroyed() && detail.hasWindowFocus()
                && detail.getCurrentFocus() != null && "episode:1:2".equals(detail.getCurrentFocus().getTag()));
    }

    @Test public void emptyResourcesKeepSeriesDetailsUsableAndQuiet() {
        LibraryDetailActivity detail = open(true, true);
        await("Resource search finishes", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        await("Episode metadata still loaded", () -> ((List<?>) field(detail, "episodes")).size() == 4);
        assertEquals(0, plays.get());
        assertNotNull(device.wait(Until.findObject(By.res(detail.getPackageName(), "play").text(detail.getString(R.string.library_no_resources))), 10000));
        awaitArtwork(detail);
        capturePage(detail, "library-no-resources-reference");
        captureLowerSections(detail, "library-no-resources-sections");
    }

    @Test public void rapidEpisodeNavigationKeepsCaptionsStillAndRestoresTheFocusedEpisode() {
        LibraryDetailActivity detail = open(true, false);
        await("Episode cards loaded", () -> ((List<?>) field(detail, "episodes")).size() == 4);
        awaitArtwork(detail);
        main(() -> assertTrue(detail.findViewById(R.id.play).requestFocus()));
        capturePage(detail, "library-polish-hero");
        assertEpisodeFocusRoundTrip(detail);
        capturePage(detail, "library-polish-episodes");
        assertEquals("Moving focus does not start playback", 0, plays.get());
    }

    @Test public void galleryFocusFitsTheArtworkAndReturnsFromTheImageViewer() {
        LibraryDetailActivity detail = open(false, false);
        main(() -> ((android.widget.ScrollView) detail.findViewById(R.id.libraryScroll)).scrollTo(0, 520));
        await("Gallery data loaded", () -> field(detail, "extras") != null
                && detail.findViewById(R.id.libraryRoot).findViewWithTag("image:0") != null);
        ViewGroup card = detail.findViewById(R.id.libraryRoot).findViewWithTag("image:0");
        main(() -> assertTrue(card.requestFocus()));
        await("Gallery art loaded", () -> com.fongmi.android.tv.ui.custom.LibraryViews.artworkBitmap(card.findViewById(R.id.image)) != null);
        instrumentation.waitForIdleSync(); SystemClock.sleep(350);
        main(() -> {
            assertEquals("Image gallery hides its empty title", View.GONE, card.findViewById(R.id.name).getVisibility());
            assertEquals("Image gallery hides its empty metadata", View.GONE, card.findViewById(R.id.meta).getVisibility());
            View art = card.findViewById(R.id.poster);
            assertTrue("Focus bounds follow the image height", card.getHeight() - art.getHeight() <= com.fongmi.android.tv.ui.custom.LibraryViews.dp(8));
            assertEquals("The gallery uses the app card focus scale", 1.04f, card.getScaleX(), .001f);
            assertEquals(1.04f, card.getScaleY(), .001f);
            assertTrue("App card selection follows focus", card.isSelected());
            assertNotNull("The app poster draws the shared focus foreground", art.getForeground());
        });
        capturePage(detail, "library-polish-gallery");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        final LibraryImagesActivity[] viewer = new LibraryImagesActivity[1];
        await("Gallery opens its image viewer", () -> {
            for (Activity activity : allActivities()) if (activity instanceof LibraryImagesActivity value && value.hasWindowFocus()) viewer[0] = value;
            return viewer[0] != null;
        });
        activities.add(viewer[0]);
        assertNull("The image viewer has no separate return icon", viewer[0].findViewById(R.id.back));
        main(() -> {
            LinearLayout gallery = (LinearLayout) field(viewer[0], "root");
            LinearLayout controls = (LinearLayout) gallery.getChildAt(1);
            assertEquals("The gallery only exposes image navigation", 2, controls.getChildCount());
            for (int index = 0; index < controls.getChildCount(); index++) {
                assertNotEquals(viewer[0].getString(R.string.library_back), ((TextView) controls.getChildAt(index)).getText().toString());
            }
        });
        for (boolean light : new boolean[]{true, false}) {
            main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(light));
            int expected = com.fongmi.android.tv.ui.theme.JetStreamPalette.resolveColor(viewer[0], R.color.jetstream_background);
            await("The image viewer follows the app appearance", () -> ((View) field(viewer[0], "root")).getBackground() instanceof ColorDrawable surface
                    && surface.getColor() == expected);
            assertFalse("Appearance changes retain the open image viewer", viewer[0].isDestroyed());
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Return restores the same artwork focus", () -> viewer[0].isDestroyed() && detail.hasWindowFocus()
                && detail.getCurrentFocus() != null && "image:0".equals(detail.getCurrentFocus().getTag()));
        assertEquals("Browsing images does not start playback", 0, plays.get());
    }

    @Test public void episodeFocusStillWorksWhenSystemAnimationsAreDisabled() throws IOException {
        String previousScale = device.executeShellCommand("settings get global animator_duration_scale").trim();
        try {
            device.executeShellCommand("settings put global animator_duration_scale 0");
            LibraryDetailActivity detail = open(true, false);
            await("Episode cards loaded", () -> ((List<?>) field(detail, "episodes")).size() == 4);
            assertEpisodeFocusRoundTrip(detail);
            assertHeaderTitleRoundTrip(detail, false);
            assertEquals("Disabled animations do not trigger playback", 0, plays.get());
        } finally {
            device.executeShellCommand(previousScale.isEmpty() || "null".equals(previousScale)
                    ? "settings delete global animator_duration_scale"
                    : "settings put global animator_duration_scale " + previousScale);
        }
    }

    @Test public void stickyHeaderTracksTheRealTitleAndRemoteBackLeavesTheScrolledPage() {
        main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(true));
        LibraryDetailActivity detail = open(true, false);
        awaitArtwork(detail);
        assertHeaderTitleRoundTrip(detail, true);
        assertEquals("Scrolling only browses the work", 0, plays.get());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Remote Back returns from the scrolled work page", detail::isDestroyed);
    }

    @Test public void heroBlendsIntoTheLightPageWithoutAVisibleSeam() {
        main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(true));
        LibraryDetailActivity detail = open(false, false);
        awaitArtwork(detail);
        main(() -> {
            ScrollView scroll = detail.findViewById(R.id.libraryScroll);
            View hero = detail.findViewById(R.id.hero);
            scroll.scrollTo(0, Math.max(0, hero.getHeight() - scroll.getHeight() / 2));
        });
        instrumentation.waitForIdleSync(); SystemClock.sleep(450);
        int[] seam = new int[2];
        main(() -> {
            View hero = detail.findViewById(R.id.hero);
            hero.getLocationOnScreen(seam);
            seam[1] += hero.getHeight();
        });
        capturePage(detail, "library-polish-surface");
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Rendered page screenshot", screenshot);
        try {
            int x = com.fongmi.android.tv.ui.custom.LibraryViews.dp(24);
            int y = seam[1];
            assertTrue("Hero-to-content seam remains visible", y > 6 && y + 6 < screenshot.getHeight());
            int above = screenshot.getPixel(x, y - 4);
            int below = screenshot.getPixel(x, y + 4);
            assertTrue("Page surface is light; pixel=" + Integer.toHexString(below) + ", seamY=" + y,
                    (Color.red(below) + Color.green(below) + Color.blue(below)) / 3 > 150);
            int jump = Math.max(Math.abs(Color.red(above) - Color.red(below)),
                    Math.max(Math.abs(Color.green(above) - Color.green(below)), Math.abs(Color.blue(above) - Color.blue(below))));
            assertTrue("Poster fades continuously into the page surface; RGB jump=" + jump, jump <= 48);
        } finally { screenshot.recycle(); }
        assertEquals(0, plays.get());
    }

    @Test public void scrollingUsesARealBlurredPosterAndMovesItSlowerThanTheContent() {
        main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(true));
        LibraryDetailActivity detail = open(false, false);
        awaitArtwork(detail);
        ScrollView scroll = detail.findViewById(R.id.libraryScroll);
        ImageView original = detail.findViewById(R.id.backdrop);
        ImageView blurred = detail.findViewById(R.id.blurredBackdrop);
        main(() -> scroll.scrollTo(0, 0));
        await("Full poster starts without blur", () -> blurred.getAlpha() < .01f);
        await("A blurred poster bitmap is prepared", () -> com.fongmi.android.tv.ui.custom.LibraryViews.artworkBitmap(blurred) != null);
        Bitmap originalBitmap = com.fongmi.android.tv.ui.custom.LibraryViews.artworkBitmap(original);
        Bitmap blurredBitmap = com.fongmi.android.tv.ui.custom.LibraryViews.artworkBitmap(blurred);
        assertNotNull(originalBitmap); assertNotNull(blurredBitmap);
        Bitmap resizedOriginal = Bitmap.createScaledBitmap(originalBitmap, blurredBitmap.getWidth(), blurredBitmap.getHeight(), true);
        try {
            double sourceEdges = posterEdgeEnergy(resizedOriginal);
            double blurredEdges = posterEdgeEnergy(blurredBitmap);
            assertTrue("Reference poster has enough contrast to verify blur", sourceEdges > .5);
            assertTrue("Blur reduces actual poster edge contrast; original=" + sourceEdges + ", blurred=" + blurredEdges,
                    blurredEdges < sourceEdges * .85);
        } finally { if (resizedOriginal != originalBitmap) resizedOriginal.recycle(); }
        int[] initialPosition = new int[2];
        int[] scrollDistance = new int[1];
        main(() -> {
            original.getLocationOnScreen(initialPosition);
            scrollDistance[0] = detail.findViewById(R.id.hero).getHeight() / 3;
            scroll.scrollTo(0, scrollDistance[0]);
        });
        instrumentation.waitForIdleSync(); SystemClock.sleep(450);
        main(() -> {
            int[] movedPosition = new int[2]; original.getLocationOnScreen(movedPosition);
            int movement = initialPosition[1] - movedPosition[1];
            assertTrue("Poster moves upwards during scrolling", movement > 0);
            assertTrue("Poster parallax moves slower than content", movement < scroll.getScrollY());
            assertTrue("Blur appears progressively at intermediate scroll", blurred.getAlpha() > .05f && blurred.getAlpha() < 1f);
        });
        capturePage(detail, "library-polish-poster-motion");
        main(() -> scroll.scrollTo(0, 0));
        await("Returning to the top restores the sharp poster", () -> blurred.getAlpha() < .01f
                && Math.abs(original.getTranslationY()) < .01f && Math.abs(blurred.getTranslationY()) < .01f);
        assertEquals("Poster effects do not start playback", 0, plays.get());
    }

    @Test public void changingAppThemeWhileBrowsingKeepsEpisodeFocusAndScroll() {
        LibraryDetailActivity detail = open(true, false);
        await("Episode cards loaded", () -> ((List<?>) field(detail, "episodes")).size() == 4);
        awaitArtwork(detail);
        View episode = detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2");
        assertNotNull(episode);
        main(() -> assertTrue(episode.requestFocus()));
        await("Second episode owns browsing focus", () -> detail.getCurrentFocus() == episode);
        instrumentation.waitForIdleSync(); SystemClock.sleep(450);
        ScrollView scroll = detail.findViewById(R.id.libraryScroll);
        int originalScroll = scroll.getScrollY();
        assertTrue("The detail is scrolled to its episode row", originalScroll > 0);
        for (boolean light : new boolean[]{true, false}) {
            main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(light));
            instrumentation.waitForIdleSync(); SystemClock.sleep(450);
            assertFalse("Changing appearance keeps the current detail Activity", detail.isDestroyed());
            int expected = com.fongmi.android.tv.ui.theme.JetStreamPalette.resolveColor(detail, R.color.jetstream_background);
            await("Detail adopts the shared app surface", () -> detail.findViewById(R.id.libraryRoot).getBackground() instanceof ColorDrawable color
                    && color.getColor() == expected);
            main(() -> {
                assertTrue("The original detail window remains active", detail.hasWindowFocus());
                assertSame("Theme changes preserve the exact focused episode card", episode, detail.getCurrentFocus());
                assertEquals("episode:1:2", detail.getCurrentFocus().getTag());
                assertFalse("Browsing an episode does not replace the main playback target", Integer.valueOf(2).equals(field(detail, "selectedEpisode")));
                assertEquals("Changing colors retains scroll position", originalScroll, scroll.getScrollY());
            });
            int[] heroPosition = new int[2];
            main(() -> {
                View hero = detail.findViewById(R.id.hero);
                hero.getLocationOnScreen(heroPosition); heroPosition[1] += hero.getHeight();
            });
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try {
                int sampleY = Math.max(detail.findViewById(R.id.libraryHeader).getBottom() + com.fongmi.android.tv.ui.custom.LibraryViews.dp(16),
                        heroPosition[1] + com.fongmi.android.tv.ui.custom.LibraryViews.dp(16));
                assertTrue("A blank body margin is on screen", sampleY < screenshot.getHeight() - 4);
                int actual = screenshot.getPixel(com.fongmi.android.tv.ui.custom.LibraryViews.dp(24), sampleY);
                assertEquals("Rendered page uses the same surface as the app", expected, actual);
            } finally { screenshot.recycle(); }
            capturePage(detail, light ? "library-polish-theme-light" : "library-polish-theme-dark");
        }
        assertEquals("Changing appearance does not start playback", 0, plays.get());
    }

    private double posterEdgeEnergy(Bitmap bitmap) {
        double energy = 0;
        int samples = 0;
        for (int y = 1; y < bitmap.getHeight(); y++) for (int x = 1; x < bitmap.getWidth(); x++) {
            int pixel = bitmap.getPixel(x, y);
            double center = (Color.red(pixel) * .2126 + Color.green(pixel) * .7152 + Color.blue(pixel) * .0722);
            int left = bitmap.getPixel(x - 1, y), top = bitmap.getPixel(x, y - 1);
            energy += Math.abs(center - (Color.red(left) * .2126 + Color.green(left) * .7152 + Color.blue(left) * .0722))
                    + Math.abs(center - (Color.red(top) * .2126 + Color.green(top) * .7152 + Color.blue(top) * .0722));
            samples += 2;
        }
        return samples == 0 ? 0 : energy / samples;
    }

    private void assertHeaderTitleRoundTrip(LibraryDetailActivity detail, boolean capture) {
        ScrollView scroll = detail.findViewById(R.id.libraryScroll);
        View header = detail.findViewById(R.id.libraryHeader);
        TextView compactTitle = detail.findViewById(R.id.headerTitle);
        assertNotNull(header); assertNotNull(compactTitle);
        assertNull("The detail has no separate return icon", detail.findViewById(R.id.back));
        main(() -> scroll.scrollTo(0, 0));
        await("Full hero keeps the compact title hidden", () -> compactTitle.getAlpha() < .05f);
        int[] collapsedScroll = new int[1];
        main(() -> {
            View anchor = detail.findViewById(R.id.logo).isShown() ? detail.findViewById(R.id.logo) : detail.findViewById(R.id.title);
            int[] titlePosition = new int[2];
            int[] headerPosition = new int[2];
            anchor.getLocationOnScreen(titlePosition); header.getLocationOnScreen(headerPosition);
            collapsedScroll[0] = Math.max(0, titlePosition[1] + anchor.getHeight() - headerPosition[1] - header.getHeight()
                    + com.fongmi.android.tv.ui.custom.LibraryViews.dp(24));
            assertTrue("Scroll target passes the actual hero title", collapsedScroll[0] > 0);
            scroll.scrollTo(0, collapsedScroll[0]);
        });
        await("Compact title appears after the hero title passes the header", () -> compactTitle.getAlpha() > .95f);
        instrumentation.waitForIdleSync(); SystemClock.sleep(450);
        main(() -> {
            assertEquals(work().getName(), compactTitle.getText().toString());
            int[] compactPosition = new int[2]; compactTitle.getLocationOnScreen(compactPosition);
            int[] headerPosition = new int[2]; header.getLocationOnScreen(headerPosition);
            assertEquals("Compact title is centered", headerPosition[0] + header.getWidth() / 2f,
                    compactPosition[0] + compactTitle.getWidth() / 2f, com.fongmi.android.tv.ui.custom.LibraryViews.dp(2));
        });
        if (capture) capturePage(detail, "library-polish-header");
        main(() -> scroll.scrollTo(0, 0));
        await("Returning to the top restores the hero title", () -> compactTitle.getAlpha() < .05f);
        instrumentation.waitForIdleSync(); SystemClock.sleep(450);
        main(() -> {
            View anchor = detail.findViewById(R.id.logo).isShown() ? detail.findViewById(R.id.logo) : detail.findViewById(R.id.title);
            assertTrue("Original title is visible again", anchor.getGlobalVisibleRect(new Rect()));
            scroll.scrollTo(0, collapsedScroll[0]);
            View episode = detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:1");
            assertTrue("Episode navigation remains reachable after removing the return control", episode != null && episode.requestFocus());
        });
        await("Episode focus remains in the scrolled content", () -> detail.getCurrentFocus() != null
                && "episode:1:1".equals(detail.getCurrentFocus().getTag()));
    }

    private void assertEpisodeFocusRoundTrip(LibraryDetailActivity detail) {
        View first = detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:1");
        View second = detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2");
        assertNotNull(first); assertNotNull(second);
        main(() -> assertTrue(first.requestFocus()));
        for (int round = 0; round < 3; round++) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        }
        await("Rapid remote navigation returns to the first episode", () -> detail.getCurrentFocus() == first);
        await("Previous focused app card returns to normal size", () -> Math.abs(second.getScaleX() - 1f) < .001f
                && Math.abs(second.getScaleY() - 1f) < .001f);
        main(() -> {
            for (View card : new View[]{first, second}) {
                float expectedScale = card.hasFocus() ? 1.04f : 1f;
                assertEquals("Episode card uses the app focus scale", expectedScale, card.getScaleX(), .001f);
                assertEquals(expectedScale, card.getScaleY(), .001f);
                assertEquals("Card selection follows its current focus", card.hasFocus(), card.isSelected());
                int[] cardPosition = new int[2]; card.getLocationOnScreen(cardPosition);
                View header = detail.findViewById(R.id.libraryHeader);
                assertTrue("Remote-focused cards remain below the sticky header", !card.hasFocus()
                        || cardPosition[1] >= header.getBottom() - com.fongmi.android.tv.ui.custom.LibraryViews.dp(2));
                ViewGroup content = (ViewGroup) card;
                assertNotNull("Episodes use the app poster image", content.findViewById(R.id.image));
                assertNotNull("Episodes use the app focus foreground", content.findViewById(R.id.poster).getForeground());
                for (int index = 1; index < content.getChildCount(); index++) if (content.getChildAt(index) instanceof TextView caption) {
                    assertEquals("Episode captions keep their size", 1f, caption.getScaleX(), .001f);
                    assertEquals(1f, caption.getScaleY(), .001f);
                }
            }
        });
    }

    @Test public void unifiedSearchShowsOneWorkWithTwoResourcesAndASourceSupplement() {
        LibrarySearchActivity search = (LibrarySearchActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibrarySearchActivity.class)
                .putExtra("keyword", "野兽之心").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(search);
        await("TMDB, sources and background matching finish", () -> searchComplete(search));
        com.fongmi.android.tv.source.LibrarySearchIndex.Snapshot snapshot = (com.fongmi.android.tv.source.LibrarySearchIndex.Snapshot) field(search, "snapshot");
        assertEquals(1, snapshot.works().size());
        assertEquals(2, snapshot.works().get(0).resources().size());
        assertEquals(1, snapshot.supplements().size());
        await("Search posters loaded", () -> fixture.dispatcher().runningCallsCount() == 0);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(1000);
        RecyclerView results = search.findViewById(R.id.results);
        await("First production search card is laid out", () -> results.findViewHolderForAdapterPosition(0) != null
                && results.findViewHolderForAdapterPosition(0).itemView.getWidth() > 0);
        ViewGroup card = (ViewGroup) results.findViewHolderForAdapterPosition(0).itemView;
        TextView name = card.findViewById(R.id.name), metadata = card.findViewById(R.id.meta);
        main(() -> {
            assertNull("Search uses remote Back without a separate return control", search.findViewById(R.id.back));
            assertTrue("Search uses the app's production poster card", card instanceof JetStreamVodCardRootLayout);
            View poster = card.findViewById(R.id.poster);
            assertEquals("Search posters preserve a 2:3 aspect ratio", poster.getWidth() * 1.5f, poster.getHeight(), 1f);
            assertNotNull("Search uses the shared poster focus foreground", poster.getForeground());
            assertNotNull(card.findViewById(R.id.image));
            List<TextView> captions = new ArrayList<>();
            for (int index = 0; index < card.getChildCount(); index++) {
                if (card.getChildAt(index) instanceof TextView text && text.getVisibility() == View.VISIBLE) captions.add(text);
            }
            assertEquals("Search cards have only a title and one metadata row", List.of(name, metadata), captions);
            assertEquals(1, name.getMaxLines()); assertEquals(1, metadata.getMaxLines());
            assertEquals(work().getName(), name.getText().toString());
            assertTrue("The unified work card shows its two resources", metadata.getText().toString().contains("2"));
            assertTrue(card.requestFocus());
        });
        await("Search uses complete-card focus motion", () -> search.getCurrentFocus() == card
                && Math.abs(card.getScaleX() - 1.04f) < .001f && card.isSelected());
        NativeUiEvidence.capture("library-search-reference");
        for (boolean light : new boolean[]{true, false}) {
            main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(light));
            instrumentation.waitForIdleSync(); SystemClock.sleep(450);
            main(() -> {
                assertFalse("Appearance changes preserve the search Activity", search.isDestroyed());
                assertTrue(search.hasWindowFocus());
                assertSame("Appearance changes preserve the production card instance", card, results.findViewHolderForAdapterPosition(0).itemView);
                assertSame("Appearance changes preserve search focus", card, search.getCurrentFocus());
                assertEquals(1.04f, card.getScaleX(), .001f); assertEquals(1.04f, card.getScaleY(), .001f);
                assertTrue(card.isSelected());
                ColorStateList colors = JetStreamPalette.resolveColorStateList(search, R.color.jetstream_list_supporting_text);
                assertEquals("Metadata follows the shared app text role", colors.getColorForState(metadata.getDrawableState(), colors.getDefaultColor()), metadata.getCurrentTextColor());
            });
            NativeUiEvidence.capture(light ? "library-search-theme-light" : "library-search-theme-dark");
        }
        assertEquals(0, plays.get());
    }

    @Test public void tmdbFailurePreservesSourceResults() {
        tmdbFails = true;
        LibrarySearchActivity search = (LibrarySearchActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibrarySearchActivity.class)
                .putExtra("keyword", "野兽之心").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(search);
        await("Source response despite metadata failure", () -> searchComplete(search) && (boolean) field(search, "tmdbError"));
        assertFalse(((com.fongmi.android.tv.source.LibrarySearchIndex.Snapshot) field(search, "snapshot")).supplements().isEmpty());
        assertEquals(0, plays.get());
    }

    @Test public void searchRemoteMovesBetweenFiltersResultsAndSourceSupplement() {
        LibrarySearchActivity search = (LibrarySearchActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibrarySearchActivity.class)
                .putExtra("keyword", "野兽之心").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(search);
        await("Search completes", () -> searchComplete(search));
        RecyclerView results = search.findViewById(R.id.results);
        await("First search poster is laid out", () -> results.findViewHolderForAdapterPosition(0) != null);
        main(() -> {
            View first = results.findViewHolderForAdapterPosition(0).itemView;
            assertTrue(first.requestFocus());
            search.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            search.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
            assertSame("Up returns directly to the active filter", search.findViewById(R.id.filter), search.getCurrentFocus());
            search.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
            search.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
            assertSame("Down returns directly to the result grid", first, search.getCurrentFocus());
            View sources = search.findViewById(R.id.sources);
            assertEquals(View.VISIBLE, sources.getVisibility());
            assertTrue(sources.requestFocus());
            sources.performClick();
        });
        await("Source shortcut focuses the original source result", () -> {
            View source = results.findViewHolderForAdapterPosition(2) == null ? null : results.findViewHolderForAdapterPosition(2).itemView;
            return source != null && search.getCurrentFocus() == source;
        });
        assertEquals("Navigating search never starts playback", 0, plays.get());
    }

    @Test public void modeSelectionKeepsPlaybackAndSourcePreferences() {
        main(() -> Prefers.put("player_scale", 2));
        ExperienceModeActivity mode = (ExperienceModeActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), ExperienceModeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(mode);
        await("Mode selection window", mode::hasWindowFocus);
        NativeUiEvidence.capture("library-mode-reference");
        main(() -> {
            LinearLayout root = (LinearLayout) field(mode, "root");
            ((LinearLayout) root.getChildAt(1)).getChildAt(1).performClick();
        });
        await("Mode applied", () -> mode.isDestroyed());
        assertEquals(ExperienceMode.SIMPLE, ExperienceSettings.getMode());
        assertEquals(2, Prefers.getInt("player_scale"));
        assertEquals(2, VodConfig.get().getSites().size());
    }

    @Test public void manualResourceChoiceUsesThatSourceAndReturnsToItsCard() {
        LibraryDetailActivity detail = open(false, false);
        await("Manual source cards", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        chooseResource(detail, "fixture-b");
        VideoActivity video = awaitPlayer();
        await("Manual source requests media", () -> plays.get() == 1);
        assertEquals("fixture-b", video.getIntent().getStringExtra("key"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Manual card focus returns", () -> video.isDestroyed() && detail.hasWindowFocus() && detail.getCurrentFocus() != null
                && ("resource:" + LibraryIdentity.sourceKey(resource(detail, "fixture-b"))).equals(detail.getCurrentFocus().getTag()));
    }

    @Test public void playerSourceControlReturnsToUnifiedResourcesWithCurrentProgress() {
        LibraryDetailActivity detail = open(false, false);
        await("Resources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        main(() -> detail.findViewById(R.id.play).performClick());
        VideoActivity first = awaitPlayer(); await("First playback request", () -> plays.get() == 1);
        main(() -> {
            History current = (History) field(first, "mHistory"); current.setPosition(180_000); current.setDuration(3_600_000);
            first.findViewById(R.id.change2).performClick();
        });
        await("Player source action returns to the current source card", () -> first.isDestroyed() && detail.hasWindowFocus()
                && detail.getCurrentFocus() != null && ("resource:" + LibraryIdentity.sourceKey(resource(detail, "fixture-a"))).equals(detail.getCurrentFocus().getTag()));
        chooseResource(detail, "fixture-b");
        VideoActivity second = awaitPlayer(); await("Replacement resource requests playback", () -> plays.get() == 2);
        assertEquals("fixture-b", second.getIntent().getStringExtra("key"));
        assertEquals(180_000, ((History) field(second, "mHistory")).getPosition());
    }

    @Test public void choosingAnotherResourceReplacesAnInFlightSelectionWithOnePress() throws Exception {
        holdDetails = true;
        heldDetailSite = "/fixture-a";
        LibraryDetailActivity detail = open(false, false);
        LibraryResourcesController resources = (LibraryResourcesController) field(detail, "resources");
        await("Both source cards are ready", () -> !resources.isRunning() && resources.snapshot().size() == 2);
        main(() -> detail.findViewById(R.id.play).performClick());
        assertTrue("First source detail is held", detailsEntered.await(10, TimeUnit.SECONDS));
        await("The explicit selection is active", resources::isSelecting);

        chooseResource(detail, "fixture-b");

        VideoActivity video = awaitPlayer();
        await("The replacement source requests playback", () -> plays.get() == 1);
        assertEquals("fixture-b", video.getIntent().getStringExtra("key"));
        assertEquals(List.of("/fixture-b"), requestedSites);
        assertEquals(List.of("movie"), requestedMedia);
        holdDetails = false;
        releaseDetails.countDown();
        instrumentation.waitForIdleSync();
        assertEquals("The cancelled source cannot launch another player", 1, plays.get());
    }

    @Test public void playbackButtonOffersCancelWhileResolvingAndCancelsWithoutLeaving() throws Exception {
        holdDetails = true;
        LibraryDetailActivity detail = open(false, false);
        LibraryResourcesController resources = (LibraryResourcesController) field(detail, "resources");
        await("Sources ready", () -> !resources.isRunning());
        main(() -> assertTrue(detail.findViewById(R.id.play).requestFocus()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue("Source detail started", detailsEntered.await(10, TimeUnit.SECONDS));
        await("The playback button describes cancellation", () -> ((TextView) detail.findViewById(R.id.play)).getText()
                .toString().equals(detail.getString(R.string.library_cancel_playback)));

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);

        await("Cancellation stays on the work page", () -> !resources.isSelecting() && detail.hasWindowFocus());
        holdDetails = false;
        releaseDetails.countDown();
        await("Cancelled calls settle", () -> fixture.dispatcher().runningCallsCount() == 0);
        assertEquals(0, plays.get());
        assertTrue(detail.findViewById(R.id.play).hasFocus());
        assertFalse(allActivities().stream().anyMatch(activity -> activity instanceof VideoActivity));
    }

    @Test public void automaticSelectionSkipsAFailedSourceDetail() {
        failedDetailSite = "/fixture-a";
        LibraryDetailActivity detail = open(false, false);
        await("Sources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        main(() -> detail.findViewById(R.id.play).performClick());
        VideoActivity video = awaitPlayer();
        await("Fallback source requests media", () -> plays.get() == 1);
        assertEquals("fixture-b", video.getIntent().getStringExtra("key"));
    }

    @Test public void manualPlaybackFailureReturnsWithoutChoosingAnotherResource() {
        failedPlaybackSite = "/fixture-a";
        LibraryDetailActivity detail = open(false, false);
        await("Sources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        chooseResource(detail, "fixture-a");
        await("Manual failure returns to resources", () -> plays.get() == 1 && !(boolean) field(detail, "playbackActive") && detail.hasWindowFocus());
        assertEquals(List.of("/fixture-a"), requestedSites);
        assertFalse(((LibraryResourcesController) field(detail, "resources")).isSelecting());
    }

    @Test public void automaticPlaybackFailureKeepsTheRequestedEpisodeOnTheNextSource() {
        failedPlaybackSite = "/fixture-a";
        LibraryDetailActivity detail = open(true, false);
        await("Sources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        main(() -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2").performClick());
        await("A second source is requested", () -> plays.get() >= 2);
        assertEquals(List.of("second", "second"), requestedMedia);
        assertEquals(List.of("/fixture-a", "/fixture-b"), requestedSites);
        VideoActivity video = awaitPlayer();
        assertEquals(2, video.getIntent().getIntExtra("mediaEpisode", -1));
        assertEquals("fixture-b", video.getIntent().getStringExtra("key"));
    }

    @Test public void cancellingSourceDetailPreventsALatePlaybackLaunch() throws Exception {
        holdDetails = true;
        LibraryDetailActivity detail = open(false, false);
        await("Sources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        main(() -> detail.findViewById(R.id.play).performClick());
        assertTrue("Source detail started", detailsEntered.await(10, TimeUnit.SECONDS));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Selection cancelled while the page stays open", () -> !((LibraryResourcesController) field(detail, "resources")).isSelecting() && detail.hasWindowFocus());
        holdDetails = false; releaseDetails.countDown();
        await("Cancelled calls settle", () -> fixture.dispatcher().runningCallsCount() == 0);
        assertEquals(0, plays.get());
        assertTrue(allActivities().stream().noneMatch(activity -> activity instanceof VideoActivity));
        main(() -> detail.findViewById(R.id.play).performClick());
        awaitPlayer();
        await("A fresh selection still works", () -> plays.get() == 1);
    }

    @Test public void resumeHistoryKeepsItsSourceEpisodeAndProgress() {
        series = true;
        History history = new History(); history.setKey("fixture-b@@@fixture-work@@@" + VodConfig.getCid()); history.setCid(VodConfig.getCid());
        history.setVodName(work().getName()); history.setVodPic(work().getPic()); history.setVodFlag("线路一"); history.setVodRemarks("第2集"); history.setEpisodeUrl("second");
        history.setMediaRef("tmdb:tv:101"); history.setMediaSeason(1); history.setMediaEpisode(2); history.setPosition(120_000); history.setDuration(2_700_000);
        LibraryDetailActivity detail = (LibraryDetailActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibraryDetailActivity.class)
                .putExtra("work", work()).putExtra("resumeHistory", history.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(detail);
        VideoActivity video = awaitPlayer();
        await("Resume source requests its second episode", () -> plays.get() == 1);
        assertEquals("fixture-b", video.getIntent().getStringExtra("key")); assertEquals("second", requestedMedia.get(0));
        assertEquals(120_000, ((History) field(video, "mHistory")).getPosition());
        assertEquals(2, video.getIntent().getIntExtra("mediaEpisode", -1));
    }

    @Test public void automaticSelectionStopsWaitingForUnresponsiveSourceDetails() {
        holdDetails = true;
        LibraryDetailActivity detail = open(false, false);
        await("Sources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        main(() -> detail.findViewById(R.id.play).performClick());
        await("Automatic detail requests reach their deadlines", () -> !((LibraryResourcesController) field(detail, "resources")).isSelecting());
        assertTrue(detail.hasWindowFocus()); assertEquals(0, plays.get());
        assertTrue(allActivities().stream().noneMatch(activity -> activity instanceof VideoActivity));
    }

    @Test public void leavingTheWorkPageCancelsAnUnfinishedSelection() throws Exception {
        holdDetails = true;
        LibraryDetailActivity detail = open(false, false);
        await("Resources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        main(() -> detail.findViewById(R.id.play).performClick());
        assertTrue(detailsEntered.await(15, TimeUnit.SECONDS));
        LibrarySearchActivity search = (LibrarySearchActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibrarySearchActivity.class)
                .putExtra("keyword", "other work").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(search);
        await("The new page cancels the previous selection", () -> search.hasWindowFocus() && !((LibraryResourcesController) field(detail, "resources")).isSelecting());
        holdDetails = false; releaseDetails.countDown();
        await("Cancelled detail settles", () -> fixture.dispatcher().runningCallsCount() == 0);
        assertEquals(0, plays.get()); assertTrue(allActivities().stream().noneMatch(activity -> activity instanceof VideoActivity));
    }

    @Test public void minimalSearchSummariesAreValidatedFromDetailBeforeAutomaticPlayback() {
        summaryTypesMissing = true;
        LibraryDetailActivity detail = open(false, false);
        await("Minimal source summaries ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        assertTrue(resource(detail, "fixture-a").getTypeName().isEmpty());
        main(() -> detail.findViewById(R.id.play).performClick());
        VideoActivity video = awaitPlayer(); await("Validated movie detail starts playback", () -> plays.get() == 1);
        assertEquals("fixture-a", video.getIntent().getStringExtra("key")); assertEquals("movie", requestedMedia.get(0));
    }

    @Test public void choosingAnotherEpisodeDoesNotReuseTheSavedEpisodesProgress() {
        series = true;
        History saved = new History(); saved.setKey("fixture-a@@@fixture-work@@@" + VodConfig.getCid()); saved.setCid(VodConfig.getCid());
        saved.setVodName(work().getName()); saved.setVodPic(work().getPic()); saved.setVodFlag("线路一"); saved.setVodRemarks("第2集"); saved.setEpisodeUrl("second");
        saved.setMediaRef("tmdb:tv:101"); saved.setMediaSeason(1); saved.setMediaEpisode(2); saved.setPosition(120_000); saved.setDuration(2_700_000);
        saved.setCreateTime(System.currentTimeMillis());
        AppDatabase.get().getHistoryDao().insertOrUpdate(saved);
        try {
            LibraryDetailActivity detail = open(true, false);
            await("Third episode card", () -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:3") != null);
            main(() -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:3").performClick());
            VideoActivity video = awaitPlayer(); await("Third episode requested", () -> plays.get() == 1);
            assertEquals("third", requestedMedia.get(0));
            assertFalse(video.getIntent().hasExtra("libraryProgress"));
            assertTrue(((History) field(video, "mHistory")).getPosition() <= 0);
        } finally { AppDatabase.get().getHistoryDao().delete(VodConfig.getCid(), saved.getKey()); }
    }

    @Test public void browsingEpisodesWithTheRemoteKeepsTheResumeTargetAndTime() {
        series = true;
        History saved = new History(); saved.setKey("fixture-b@@@fixture-work@@@" + VodConfig.getCid()); saved.setCid(VodConfig.getCid());
        saved.setVodName(work().getName()); saved.setVodPic(work().getPic()); saved.setVodFlag("线路一"); saved.setVodRemarks("第2集"); saved.setEpisodeUrl("second");
        saved.setMediaRef("tmdb:tv:101"); saved.setMediaSeason(1); saved.setMediaEpisode(2); saved.setPosition(120_000); saved.setDuration(2_700_000);
        saved.setCreateTime(System.currentTimeMillis());
        AppDatabase.get().getHistoryDao().insertOrUpdate(saved);
        try {
            LibraryDetailActivity detail = open(true, false);
            await("Episodes and resources ready", () -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:4") != null
                    && !((LibraryResourcesController) field(detail, "resources")).isRunning());
            TextView play = detail.findViewById(R.id.play);
            await("Resume action identifies the saved episode and time", () -> play.getText().toString().contains(detail.getString(R.string.library_episode, 2))
                    && play.getText().toString().contains("2:00"));
            String resumeLabel = play.getText().toString();
            main(() -> assertTrue(play.requestFocus()));

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            await("Down enters the resumed episode", () -> detail.getCurrentFocus() != null
                    && "episode:1:2".equals(detail.getCurrentFocus().getTag()));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            await("The fourth episode is only being browsed", () -> detail.getCurrentFocus() != null
                    && "episode:1:4".equals(detail.getCurrentFocus().getTag()));
            assertEquals(Integer.valueOf(2), field(detail, "selectedEpisode"));
            assertEquals(resumeLabel, play.getText().toString());
            assertEquals(0, plays.get());

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            await("Up reaches the season control", () -> detail.getCurrentFocus() != null
                    && "seasonSelector".equals(detail.getCurrentFocus().getTag()));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            await("Up returns to the main resume action", play::hasFocus);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);

            VideoActivity video = awaitPlayer();
            await("The saved episode resumes", () -> plays.get() == 1);
            assertEquals(List.of("second"), requestedMedia);
            assertEquals("fixture-b", video.getIntent().getStringExtra("key"));
            assertEquals(2, video.getIntent().getIntExtra("mediaEpisode", -1));
            assertEquals(120_000, ((History) field(video, "mHistory")).getPosition());
        } finally { AppDatabase.get().getHistoryDao().delete(VodConfig.getCid(), saved.getKey()); }
    }

    @Test public void changingSeasonRemovesOldEpisodeActionsWhileTheNewSeasonLoads() throws Exception {
        multipleSeasons = holdSecondSeason = true;
        LibraryDetailActivity detail = open(true, false);
        await("First season episode actions ready", () -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2") != null);
        View oldEpisode = detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2");
        main(() -> assertTrue(detail.findViewById(R.id.libraryRoot).findViewWithTag("seasonSelector").requestFocus()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        androidx.test.uiautomator.UiObject2 secondSeason = device.wait(Until.findObject(By.text(detail.getString(R.string.library_season, 2))), 10000);
        assertNotNull("The season menu offers the second season", secondSeason);
        secondSeason.click();

        assertTrue("Second season request started", seasonEntered.await(10, TimeUnit.SECONDS));
        main(() -> {
            assertNull("Old season actions are removed immediately", detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2"));
            assertFalse("The removed episode cannot receive remote focus", oldEpisode.isShown());
            View loading = detail.findViewById(R.id.libraryRoot).findViewWithTag("episodesLoading");
            assertNotNull("The waiting state replaces old episodes", loading);
            assertTrue("The waiting state is visible", loading.isShown());
            assertFalse("Loading copy is not another action button", loading.isFocusable());
            assertTrue("The season control remains available while loading", detail.findViewById(R.id.libraryRoot)
                    .findViewWithTag("seasonSelector").isFocusable());
        });
        assertEquals(0, plays.get());

        holdSecondSeason = false;
        releaseSeason.countDown();
        await("The new season replaces the waiting state", () -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:2:1") != null
                && detail.findViewById(R.id.libraryRoot).findViewWithTag("episodesLoading") == null);
        assertEquals(0, plays.get());
    }

    @Test public void simpleModeLoadsExtraInformationOnlyWhenRequested() {
        main(() -> ExperienceSettings.putMode(ExperienceMode.SIMPLE));
        LibraryDetailActivity detail = open(false, false);
        assertEquals(View.GONE, ((View) field(detail, "castSection")).getVisibility());
        assertEquals(0, extrasRequests.get());
        main(() -> detail.findViewById(R.id.more).performClick());
        device.wait(Until.findObject(By.text(detail.getString(R.string.library_more))), 10000).click();
        await("Extra information expands", () -> field(detail, "extras") != null && ((View) field(detail, "castSection")).isShown());
        assertEquals(1, extrasRequests.get()); assertEquals(0, plays.get());
    }

    @Test public void classicModeKeepsTheOriginalPlayerEntryAndSmallWindow() {
        LibraryDetailActivity detail = open(false, false);
        main(() -> ExperienceSettings.putMode(ExperienceMode.CLASSIC));
        Vod source = new Vod(); source.setId("fixture-work"); source.setName(work().getName()); source.setPic(work().getPic());
        main(() -> LibraryNavigation.source(detail, "fixture-a", source, null));
        VideoActivity video = awaitPlayer(); await("Classic playback request", () -> plays.get() == 1);
        assertFalse(video.getIntent().getBooleanExtra("libraryPlayback", false));
        assertFalse(video.isFullscreenForPlayback());
    }

    @Test public void searchFilterRestartsAnInFlightRequestAndIgnoresItsLateResponse() throws Exception {
        holdSearch = true;
        LibrarySearchActivity search = (LibrarySearchActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibrarySearchActivity.class)
                .putExtra("keyword", "野兽之心").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(search);
        assertTrue("Initial search started", searchEntered.await(10, TimeUnit.SECONDS));
        main(() -> {
            View seriesFilter = search.findViewById(R.id.filterSeries);
            assertTrue("Visible filters are immediately reachable", seriesFilter.requestFocus());
            seriesFilter.performClick();
        });
        await("Replacement series request and matching finish", () -> searchComplete(search) && (int) field(search, "page") == 1);
        holdSearch = false; releaseSearch.countDown();
        await("Old search response settles", () -> fixture.dispatcher().runningCallsCount() == 0);
        com.fongmi.android.tv.source.LibrarySearchIndex.Snapshot snapshot = (com.fongmi.android.tv.source.LibrarySearchIndex.Snapshot) field(search, "snapshot");
        assertEquals(1, snapshot.works().size()); assertEquals("tv", snapshot.works().get(0).item().getTypeName());
        assertEquals("tv", field(search, "type"));
        main(() -> {
            assertSame("Async results do not steal the selected filter's focus", search.findViewById(R.id.filterSeries), search.getCurrentFocus());
            assertTrue(search.findViewById(R.id.filterSeries).isSelected());
            assertFalse(search.findViewById(R.id.filter).isSelected());
        });
    }

    @Test public void sourceOnlySeriesKeepsOriginalEpisodesWhenTmdbIsUnavailable() {
        series = tmdbFails = true;
        Vod item = work(); item.setId("fixture-work"); item.setTypeName(""); item.setSite(VodConfig.get().getSite("fixture-a"));
        LibraryDetailActivity detail = (LibraryDetailActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibraryDetailActivity.class)
                .putExtra("work", item).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(detail);
        await("Source detail exposes original episodes", () -> detail.findViewById(R.id.libraryRoot).findViewWithTag("original:线路一:second") != null);
        assertNull(field(detail, "metadata")); assertEquals(0, plays.get());
        main(() -> detail.findViewById(R.id.libraryRoot).findViewWithTag("original:线路一:second").performClick());
        VideoActivity video = awaitPlayer(); await("Original second episode requested", () -> plays.get() == 1);
        assertEquals("second", requestedMedia.get(0)); assertFalse(video.getIntent().hasExtra("mediaEpisode"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Original episode returns to its work", () -> video.isDestroyed() && detail.hasWindowFocus());
        main(() -> detail.findViewById(R.id.play).performClick());
        awaitPlayer(); await("The source-only work resumes its exact original episode", () -> plays.get() == 2);
        assertEquals(List.of("second", "second"), requestedMedia);
    }

    @Test public void animeUsesTheReferenceArtworkAndEpisodeLayout() {
        anime = true;
        LibraryDetailActivity detail = open(true, false);
        await("Anime episodes", () -> ((List<?>) field(detail, "episodes")).size() == 4);
        awaitArtwork(detail); capturePage(detail, "library-anime-reference");
        captureLowerSections(detail, "library-anime-sections");
        assertEquals(0, plays.get());
    }

    @Test public void detailPrefetchesTheConfirmedLinkWithoutOpeningAPlayerAndClickConsumesIt() {
        directPrefetch = true;
        LibraryDetailActivity detail = open(false, false);
        await("Confirmed link ready on the work page", () -> ((com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch) field(detail, "linkPrefetch")).isReady());
        assertEquals(List.of("movie"), prefetchedEpisodes); assertEquals(1, linkRequests.get()); assertEquals(0, plays.get());
        assertTrue(allActivities().stream().noneMatch(activity -> activity instanceof VideoActivity));
        main(() -> detail.findViewById(R.id.play).performClick());
        VideoActivity video = awaitPlayer();
        await("Prefetched response accepted by the full-screen player", () -> field(video, "mQualityResult") != null);
        assertTrue(video.isFullscreenForPlayback()); assertEquals(0, plays.get()); assertEquals(1, linkRequests.get());
        assertFalse(video.getIntent().hasExtra("libraryPrefetch"));
    }

    @Test public void episodeFocusPrefetchUsesTheExplicitNumberInAReorderedPlaylist() {
        directPrefetch = reorderedEpisodes = true;
        LibraryDetailActivity detail = open(true, false);
        await("Episode cards", () -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2") != null);
        main(() -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2").requestFocus());
        await("Second episode link warmed", () -> prefetchedEpisodes.contains("second") && ((com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch) field(detail, "linkPrefetch")).isReady());
        assertEquals(0, plays.get());
        assertFalse("Speculative prefetch leaves the main playback target unchanged", Integer.valueOf(2).equals(field(detail, "selectedEpisode")));
        main(() -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2").performClick());
        VideoActivity video = awaitPlayer(); await("Warm episode reaches player", () -> field(video, "mQualityResult") != null);
        assertEquals("second", ((History) field(video, "mHistory")).getEpisodeUrl()); assertEquals(2, video.getIntent().getIntExtra("mediaEpisode", -1));
        assertEquals(0, plays.get());
    }

    @Test public void returningQuicklyToTheWorkPageStartsAFreshPrefetch() {
        directPrefetch = true;
        LibraryDetailActivity detail = open(false, false);
        await("First link ready", () -> ((com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch) field(detail, "linkPrefetch")).isReady());
        int first = linkRequests.get();
        LibrarySearchActivity search = (LibrarySearchActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibrarySearchActivity.class)
                .putExtra("keyword", "other").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(search);
        await("Search page visible", search::hasWindowFocus);
        main(search::finish);
        await("Fresh link ready on return", () -> detail.hasWindowFocus() && linkRequests.get() > first
                && ((com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch) field(detail, "linkPrefetch")).isReady());
        assertEquals(0, plays.get());
    }

    @Test public void aCancelledLinkResponseCannotLaunchPlaybackAfterLeavingTheWork() {
        directPrefetch = holdLink = true;
        LibraryDetailActivity detail = open(false, false);
        await("Link request started", () -> linkRequests.get() == 1);
        LibrarySearchActivity search = (LibrarySearchActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibrarySearchActivity.class)
                .putExtra("keyword", "other").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(search);
        await("Previous page paused", search::hasWindowFocus);
        holdLink = false; releaseLink.countDown();
        await("Cancelled link finished", () -> fixture.dispatcher().runningCallsCount() == 0);
        assertFalse(((com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch) field(detail, "linkPrefetch")).isReady());
        assertEquals(0, plays.get()); assertTrue(allActivities().stream().noneMatch(activity -> activity instanceof VideoActivity));
    }

    @Test public void nextEpisodeFollowsCoordinatesInAReorderedSourcePlaylist() {
        reorderedEpisodes = true;
        LibraryDetailActivity detail = open(true, false);
        await("Second episode", () -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2") != null);
        main(() -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2").performClick());
        VideoActivity video = awaitPlayer(); await("Second requested", () -> plays.get() == 1);
        main(() -> ((com.fongmi.android.tv.playback.vod.VodPlaybackController) field(video, "mVod")).nextEpisode(false));
        await("Third requested instead of neighboring first", () -> plays.get() == 2);
        assertEquals(List.of("second", "third"), requestedMedia);
        assertEquals(Integer.valueOf(3), ((History) field(video, "mHistory")).getMediaEpisode());
    }

    @Test public void fullscreenEpisodePickerChangesTheEpisodeAndReturnsItsHistory() {
        LibraryDetailActivity detail = open(true, false);
        await("Second episode available", () -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2") != null);
        main(() -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2").performClick());
        VideoActivity video = awaitPlayer();
        await("The initial episode is loading", () -> requestedMedia.equals(List.of("second")));

        // The held media fixture deliberately never reaches READY: navigation must still work.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        androidx.test.uiautomator.UiObject2 episodes = device.wait(Until.findObject(By.text(video.getString(R.string.vod_control_episodes))), 10000);
        assertNotNull("Fullscreen exposes a direct episode action while buffering", episodes);
        episodes.click();
        await("Episode side sheet opens", () -> video.getSupportFragmentManager().findFragmentByTag("vod-selection") != null);
        await("The current episode receives focus", () -> {
            androidx.fragment.app.DialogFragment dialog = (androidx.fragment.app.DialogFragment) video.getSupportFragmentManager().findFragmentByTag("vod-selection");
            View focused = dialog == null || dialog.getDialog() == null ? null : dialog.getDialog().getCurrentFocus();
            return focused instanceof TextView text && text.getText().toString().equals("第2集");
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("The third episode is requested in the same player", () -> requestedMedia.equals(List.of("second", "third")));
        assertTrue(video.isFullscreenForPlayback());
        assertFalse(video.isDestroyed());
        assertEquals(3, ((History) field(video, "mHistory")).getMediaEpisode().intValue());
        assertTrue("The launch episode no longer overrides selection", video.getVodPlayId().isEmpty());

        await("The direct episode action returns after selection", () -> video.getSupportFragmentManager().findFragmentByTag("vod-selection") == null
                && video.hasWindowFocus() && device.findObject(By.text(video.getString(R.string.vod_control_episodes))) != null);
        device.findObject(By.text(video.getString(R.string.vod_control_episodes))).click();
        await("Episode side sheet reopens", () -> video.getSupportFragmentManager().findFragmentByTag("vod-selection") != null);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Back closes only the episode sheet", () -> video.getSupportFragmentManager().findFragmentByTag("vod-selection") == null && video.hasWindowFocus());
        assertTrue(video.isFullscreenForPlayback());
        assertFalse(video.isFinishing());
        for (int i = 0; i < 4 && !video.isFinishing() && !video.isDestroyed(); i++) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            instrumentation.waitForIdleSync();
        }
        await("Returning to details carries the chosen episode", () -> video.isDestroyed() && detail.hasWindowFocus()
                && Integer.valueOf(3).equals(field(detail, "selectedEpisode")));
        assertEquals(3, ((History) field(detail, "history")).getMediaEpisode().intValue());
    }

    @Test public void fullscreenLinePickerKeepsTheCurrentEpisode() {
        multipleLines = true;
        LibraryDetailActivity detail = open(true, false);
        await("Second episode available", () -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2") != null);
        main(() -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2").performClick());
        VideoActivity video = awaitPlayer();
        await("Initial line starts the second episode", () -> requestedMedia.equals(List.of("second")));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        androidx.test.uiautomator.UiObject2 playlist = device.wait(Until.findObject(By.text(video.getString(R.string.vod_control_group_playlist))), 10000);
        assertNotNull("Playback options remain reachable", playlist);
        playlist.click();
        androidx.test.uiautomator.UiObject2 lines = device.wait(Until.findObject(By.text(video.getString(R.string.vod_control_line))), 10000);
        assertNotNull("Multiple lines expose a line selector", lines);
        lines.click();
        await("Line side sheet opens", () -> video.getSupportFragmentManager().findFragmentByTag("vod-selection") != null);
        androidx.test.uiautomator.UiObject2 secondLine = device.wait(Until.findObject(By.text("线路二")), 10000);
        assertNotNull(secondLine);
        secondLine.click();
        await("Switching lines preserves the current episode", () -> requestedMedia.equals(List.of("second", "alt-second")));
        assertTrue(video.isFullscreenForPlayback());
        assertEquals("线路二", ((History) field(video, "mHistory")).getVodFlag());
        assertEquals(2, ((History) field(video, "mHistory")).getMediaEpisode().intValue());
    }

    @Test public void generatedOrAbsolutePlaylistPositionsCannotSupplyTheRequestedSeasonEpisode() {
        generatedEpisodes = true;
        LibraryDetailActivity detail = open(true, false);
        await("Resources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        main(() -> detail.findViewById(R.id.libraryRoot).findViewWithTag("episode:1:2").performClick());
        await("Unknown generated numbers remain manual", () -> !((LibraryResourcesController) field(detail, "resources")).isSelecting() && detail.hasWindowFocus());
        assertEquals(0, plays.get()); assertEquals(0, linkRequests.get());
        assertTrue(allActivities().stream().noneMatch(activity -> activity instanceof VideoActivity));
    }

    @Test public void seriesLogoFallsBackToOriginalLanguageAndShowsCurrentSeasonUpdateCount() {
        anime = fallbackLogo = true;
        LibraryDetailActivity detail = open(true, false);
        await("Original-language logo is displayed", () -> detail.findViewById(R.id.logo).getVisibility() == View.VISIBLE
                && detail.findViewById(R.id.title).getVisibility() == View.GONE);
        await("Current season progress", () -> ((android.widget.TextView) detail.findViewById(R.id.seriesStatus)).getText().toString().contains(detail.getString(R.string.library_series_progress, 4, 12)));
        assertTrue(((android.widget.TextView) detail.findViewById(R.id.seriesStatus)).getText().toString().contains("12"));
        assertEquals(0, plays.get());
        capturePage(detail, "library-anime-logo-update");
    }

    private Response response(okhttp3.Request request, String body, String type) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Offline fixture")
                .body(ResponseBody.create(body, MediaType.get(type))).build();
    }

    private Vod resource(LibraryDetailActivity detail, String site) {
        return ((LibraryResourcesController) field(detail, "resources")).snapshot().stream().filter(source -> source.getSiteKey().equals(site)).findFirst().orElseThrow();
    }
    private boolean searchComplete(LibrarySearchActivity search) {
        return !(boolean) field(search, "sourceLoading") && !(boolean) field(search, "tmdbLoading")
                && !(boolean) field(search, "matching") && !(boolean) field(search, "renderPending");
    }
    private void chooseResource(LibraryDetailActivity detail, String site) {
        String key = LibraryIdentity.sourceKey(resource(detail, site));
        main(() -> assertTrue(detail.findViewById(R.id.libraryRoot).findViewWithTag("resource:" + key).requestFocus()));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private void captureLowerSections(LibraryDetailActivity detail, String name) {
        main(() -> ((android.widget.ScrollView) detail.findViewById(R.id.libraryScroll)).scrollTo(0, 520));
        await("Supplementary sections loaded", () -> field(detail, "extras") != null);
        instrumentation.waitForIdleSync();
        main(() -> ((android.widget.ScrollView) detail.findViewById(R.id.libraryScroll)).scrollTo(0, 520));
        instrumentation.waitForIdleSync(); SystemClock.sleep(900);
        capturePage(detail, name);
        main(() -> {
            android.widget.ScrollView scroll = detail.findViewById(R.id.libraryScroll);
            scroll.scrollTo(0, Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight() + scroll.getPaddingTop() + scroll.getPaddingBottom()));
        });
        instrumentation.waitForIdleSync(); SystemClock.sleep(500);
        capturePage(detail, name + "-bottom");
        main(() -> ((android.widget.ScrollView) detail.findViewById(R.id.libraryScroll)).scrollTo(0, 0));
    }
    private void capturePage(LibraryDetailActivity detail, String name) {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("native_ui_evidence"))) return;
        NativeUiEvidence.capture(name);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        try {
            Files.write(new File(directory, name + ".scroll").toPath(), String.valueOf(((android.widget.ScrollView) detail.findViewById(R.id.libraryScroll)).getScrollY()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException error) { throw new AssertionError("Capture scroll coordinates", error); }
    }

    private LibraryDetailActivity open(boolean tv, boolean empty) {
        series = tv; noSources = empty;
        LibraryDetailActivity detail = (LibraryDetailActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibraryDetailActivity.class)
                .putExtra("work", work()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(detail);
        await("Work window and metadata", () -> detail.hasWindowFocus() && field(detail, "metadata") != null);
        instrumentation.waitForIdleSync();
        return detail;
    }
    private VideoActivity awaitPlayer() {
        final VideoActivity[] video = new VideoActivity[1];
        await("Fullscreen playback activity", () -> {
            for (Activity activity : allActivities()) if (activity instanceof VideoActivity value && !value.isDestroyed() && !value.isFinishing()) video[0] = value;
            return video[0] != null;
        });
        if (!activities.contains(video[0])) activities.add(video[0]);
        return video[0];
    }

    private void awaitArtwork(LibraryDetailActivity activity) {
        await("Reference artwork loaded", () -> {
            android.graphics.Bitmap bitmap = com.fongmi.android.tv.ui.custom.LibraryViews.artworkBitmap(activity.findViewById(R.id.backdrop));
            return bitmap != null && bitmap.getWidth() > 100 && bitmap.getHeight() > 100;
        });
        instrumentation.waitForIdleSync();
        SystemClock.sleep(800);
    }
    private List<Activity> allActivities() {
        List<Activity> result = new ArrayList<>();
        main(() -> {
            result.addAll(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED));
            result.addAll(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.STARTED));
        });
        return result;
    }
    private Vod work() {
        Vod item = new Vod(); item.setId(series ? "tmdb:tv:" + (anime ? 103 : 101) : "tmdb:movie:102"); item.setTypeName(series ? "tv" : "movie");
        item.setName(series ? anime ? "FX 战士久留美" : "锦金钗" : "野兽之心"); item.setYear("2026");
        item.setPic("https://library-fixture.invalid/t/p/w342/" + (anime ? "anime" : series ? "series" : "movie") + "-backdrop.jpg"); item.setBackdrop(item.getPic());
        item.setContent("为了寻找新的生活，他们踏上未知的旅程。在一次次相遇与离别中，命运的线索逐渐展开。");
        return item;
    }
    private String sourceResponse(okhttp3.HttpUrl url) {
        if (noSources) return "{\"list\":[]}";
        Vod item = new Vod(); item.setId("fixture-work"); item.setName(work().getName()); item.setYear("2026"); item.setTypeName(series ? "电视剧" : "电影"); item.setPic(work().getPic()); item.setRemarks("1080P");
        if (url.queryParameter("ids") != null) {
            item.setPlayFrom("线路一"); item.setPlayUrl(series ? generatedEpisodes ? "first#second#third#fourth" : reorderedEpisodes ? "第4集$fourth#第2集$second#第1集$first#第3集$third" : "第1集$first#第2集$second#第3集$third#第4集$fourth" : "正片$movie");
            if (multipleLines) {
                item.setPlayFrom("线路一$$$线路二");
                item.setPlayUrl(item.getPlayUrl() + "$$$第1集$alt-first#第2集$alt-second#第3集$alt-third#第4集$alt-fourth");
            }
            return App.gson().toJson(Result.vod(item));
        }
        if (summaryTypesMissing) item.setTypeName("");
        List<Vod> items = new ArrayList<>(List.of(item));
        if (url.encodedPath().equals("/fixture-a")) { Vod exclusive = new Vod(); exclusive.setId("exclusive"); exclusive.setName("片源独有影片"); exclusive.setTypeName("电影"); exclusive.setPic(work().getPic()); items.add(exclusive); }
        return App.gson().toJson(Result.list(items));
    }
    private String metadataResponse(okhttp3.HttpUrl url) {
        if (url.encodedPath().endsWith("/images")) return "{\"logos\":[{\"iso_639_1\":\"ja\",\"file_path\":\"/anime-logo.png\"}]}";
        if (url.encodedPath().contains("/search/tv")) return "{\"page\":1,\"total_pages\":1,\"results\":[{\"id\":101,\"media_type\":\"tv\",\"name\":\"锦金钗\",\"first_air_date\":\"2026-10-01\",\"poster_path\":\"/series-backdrop.jpg\"}]}";
        if (url.encodedPath().contains("/search/")) return "{\"page\":1,\"total_pages\":1,\"results\":[{\"id\":102,\"media_type\":\"movie\",\"title\":\"野兽之心\",\"release_date\":\"2026-09-19\",\"poster_path\":\"/movie-backdrop.jpg\"}]}";
        if (url.encodedPath().contains("/season/")) {
            List<Map<String, Object>> episodes = new ArrayList<>();
            for (int i = 1; i <= 4; i++) episodes.add(Map.of("episode_number", i, "name", (anime ? new String[]{"FX 是什么？", "有杠杆可以改变的", "就…非常显然不可吗？", "没有亏损也太爽了～"} : new String[]{"归来", "相逢", "抉择", "远行"})[i - 1], "runtime", anime ? 24 : 45, "air_date", "2026-10-01", "still_path", "/" + (anime ? "anime" : "series") + "-episode" + (i == 1 ? "" : "-" + i) + ".jpg", "overview", "一场相遇改变了原本平静的生活，新的故事由此展开。"));
            return App.gson().toJson(Map.of("episodes", episodes));
        }
        String type = anime ? "anime" : series ? "series" : "movie";
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("id", anime ? 103 : series ? 101 : 102); metadata.put(series ? "name" : "title", work().getName());
        metadata.put(series ? "first_air_date" : "release_date", "2026-10-01");
        if (series) metadata.put("episode_run_time", List.of(anime ? 24 : 45)); else metadata.put("runtime", 101);
        metadata.put("vote_average", 7.9); metadata.put("poster_path", "/" + type + "-backdrop.jpg"); metadata.put("backdrop_path", "/" + type + "-backdrop.jpg");
        metadata.put("overview", work().getContent()); metadata.put("genres", List.of(Map.of("id", 18, "name", anime ? "动画" : "剧情")));
        metadata.put("production_countries", List.of(Map.of("name", anime ? "日本" : series ? "中国大陆" : "美国")));
        metadata.put("seasons", multipleSeasons
                ? List.of(Map.of("season_number", 1, "episode_count", 4), Map.of("season_number", 2, "episode_count", 4))
                : List.of(Map.of("season_number", 1, "episode_count", anime ? 12 : 30)));
        if (series) {
            metadata.put("original_language", anime ? "ja" : "zh"); metadata.put("in_production", true);
            metadata.put("last_episode_to_air", Map.of("season_number", 1, "episode_number", 4, "air_date", "2026-10-22"));
            metadata.put("next_episode_to_air", Map.of("season_number", 1, "episode_number", 5, "air_date", "2026-11-19"));
        }
        List<Map<String, Object>> cast = new ArrayList<>();
        String[] names = anime ? new String[]{"铃木爱奈", "津户美沙罗", "伊藤彩沙", "阿原里香", "和海之", "石雨拳", "董丰素子", "宾有纪"}
                : series ? new String[]{"演员一", "演员二", "演员三"} : new String[]{"大卫·阿耶", "布拉德·皮特", "J·K·西蒙斯", "Anna Lambe", "Justin Melnick", "Shaun DeZem"};
        for (int i = 0; i < names.length; i++) cast.add(Map.of("id", i + 1, "name", names[i], "character", i == 0 && !series ? "导演" : "主演", "profile_path", "/" + type + "-actor-" + (i + 1) + ".jpg"));
        metadata.put("credits", Map.of("cast", cast)); metadata.put("images", Map.of("backdrops", List.of(Map.of("file_path", "/" + type + "-backdrop.jpg"), Map.of("file_path", "/" + type + "-still-2.jpg"), Map.of("file_path", "/" + type + "-still-3.jpg"))));
        if (series && !fallbackLogo) metadata.put("images", Map.of("logos", List.of(Map.of("iso_639_1", anime ? "ja" : "zh", "file_path", "/" + type + "-logo.png")), "backdrops", List.of(Map.of("file_path", "/" + type + "-backdrop.jpg"), Map.of("file_path", "/" + type + "-still-2.jpg"), Map.of("file_path", "/" + type + "-still-3.jpg"))));
        if (url.queryParameter("append_to_response") != null && url.queryParameter("append_to_response").contains("videos")) {
            extrasRequests.incrementAndGet();
            metadata.put("videos", Map.of("results", series ? List.of() : List.of(Map.of("name", "New International Trailer", "site", "YouTube", "key", "fixture123"), Map.of("name", "Official Trailer 2", "site", "YouTube", "key", "fixture456"), Map.of("name", "Official Trailer", "site", "YouTube", "key", "fixture789"))));
        }
        List<Map<String, Object>> recommendations = new ArrayList<>();
        String[] related = anime ? new String[]{"日式面包王", "蜡笔小新", "哆啦A梦"} : series ? new String[]{"贝尔戈维亚：下一章", "凡人修仙传", "大梦归离"} : new String[]{"远山恋人", "Hero Dog", "势不两立"};
        for (int i = 0; i < related.length; i++) recommendations.add(Map.of("id", 202 + i, series ? "name" : "title", related[i], "poster_path", "/" + type + "-related-" + (i + 1) + ".jpg", "backdrop_path", "/" + type + "-related-" + (i + 1) + ".jpg", "vote_average", 6.6 + i * .3, "overview", "在未知的旅程中，新的故事正缓缓展开。", series ? "first_air_date" : "release_date", "2026-01-01"));
        metadata.put("recommendations", Map.of("results", recommendations));
        return App.gson().toJson(metadata);
    }
    private Object field(Object target, String name) { return HomePreviewFixtures.field(target, name); }
    private void set(Object target, String name, Object value) { HomePreviewFixtures.setField(target, name, value); }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void await(String description, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 60_000;
        while (SystemClock.uptimeMillis() < deadline) { if (condition.getAsBoolean()) return; SystemClock.sleep(60); }
        NativeUiEvidence.capture("library-test-failure");
        String warm = "";
        for (Activity active : activities) if (active instanceof LibraryDetailActivity && !active.isDestroyed()) {
            LibraryResourcesController resource = (LibraryResourcesController) field(active, "resources");
            warm += "; selected=" + field(active, "selectedEpisode") + "; prep=" + resource.isPreparing() + "; sources=" + resource.snapshot().size()
                    + "; ready=" + ((com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch) field(active, "linkPrefetch")).isReady()
                    + "; running=" + ((com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch) field(active, "linkPrefetch")).isRunning();
        }
        fail(description + "; plays=" + plays.get() + "; links=" + linkRequests.get() + "; prefetched=" + prefetchedEpisodes + "; sites=" + requestedSites + "; media=" + requestedMedia + warm);
    }
}
