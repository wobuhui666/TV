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
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.TmdbLibraryApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
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
import com.fongmi.android.tv.ui.library.LibraryDetailState;
import com.fongmi.android.tv.ui.library.LibraryDetailView;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
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
    private final AtomicInteger sourceActions = new AtomicInteger();
    private final List<String> requestedFolders = java.util.Collections.synchronizedList(new ArrayList<>());
    private final Map<String, byte[]> artwork = new HashMap<>();
    private OkHttpClient original, originalTmdb, fixture;
    private Object originalSites, originalHome, originalConfig;
    private boolean series, anime, noSources, tmdbFails, holdDetails, holdSearch, summaryTypesMissing;
    private boolean directPrefetch, fallbackLogo, reorderedEpisodes, generatedEpisodes, absoluteEpisodes, holdLink;
    private boolean multipleSeasons, holdSecondSeason, multipleLines, longSeason, mixedQualities;
    private boolean folderSearch, snapshotTaken;
    private String failedDetailSite = "", failedPlaybackSite = "", heldDetailSite = "";

    @Before public void setup() throws Exception {
        assertEquals("Only an isolated preview application", "com.fongmi.android.tv.preview", instrumentation.getTargetContext().getPackageName());
        File external = instrumentation.getTargetContext().getExternalFilesDir(null);
        assertNotNull("Target external files directory is available", external);
        File root = new File(external, "library-fixtures");
        prepareArtworkFixtures(root);
        for (String name : new String[]{"movie-backdrop.jpg", "series-backdrop.jpg", "anime-backdrop.jpg", "movie-episode.jpg", "series-episode.jpg", "anime-episode.jpg"}) {
            File file = new File(root, name);
            assertTrue("Reference fixture exists: " + file, file.isFile());
            assertTrue("Target application can read reference fixture: " + file, file.canRead());
            artwork.put(name, Files.readAllBytes(file.toPath()));
        }
        File[] images = root.listFiles((directory, name) -> name.endsWith(".jpg") || name.endsWith(".png"));
        if (images != null) for (File file : images) artwork.put(file.getName(), Files.readAllBytes(file.toPath()));
        main(() -> {
            for (String key : KEYS) if (Prefers.getPrefers().contains(key)) saved.put(key, Prefers.getPrefers().getAll().get(key));
            original = OkHttp.client(); originalTmdb = (OkHttpClient) field(TmdbNetwork.class, "client");
            originalSites = field(VodConfig.get(), "sites"); originalHome = field(VodConfig.get(), "home"); originalConfig = field(VodConfig.get(), "config");
            snapshotTaken = true;
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
        if (!snapshotTaken) return;
        try {
            for (Activity active : allActivities()) if (!activities.contains(active) && (active instanceof LibraryDetailActivity
                    || active instanceof LibrarySearchActivity || active instanceof LibraryImagesActivity || active instanceof VideoActivity
                    || active instanceof ExperienceModeActivity || active instanceof VodActivity)) activities.add(active);
            main(() -> { for (int i = activities.size() - 1; i >= 0; i--) if (!activities.get(i).isDestroyed()) activities.get(i).finish(); });
            releasePlayer.countDown();
            releaseDetails.countDown();
            releaseSearch.countDown();
            releaseLink.countDown();
            releaseSeason.countDown();
            await("Activities close", () -> activities.stream().allMatch(Activity::isDestroyed));
            if (fixture != null) await("Fixture calls finish", () -> fixture.dispatcher().runningCallsCount() == 0);
        } finally {
            releasePlayer.countDown(); releaseDetails.countDown(); releaseSearch.countDown(); releaseLink.countDown(); releaseSeason.countDown();
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
                assertTrue(editor.commit()); TmdbLibraryApi.clearCache();
            });
            if (fixture != null) fixture.connectionPool().evictAll();
        }
    }

    private void prepareArtworkFixtures(File root) throws Exception {
        int targetUid = instrumentation.getTargetContext().getApplicationInfo().uid;
        assertEquals("Fixture reads run as the target application", targetUid, android.os.Process.myUid());
        assertTrue("Target application creates the fixture directory", root.isDirectory() || root.mkdirs());
        String manifest = new String(readArtworkAsset("SHA256SUMS"), StandardCharsets.UTF_8);
        Map<String, String> expected = new HashMap<>();
        for (String line : manifest.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            assertTrue("Valid artwork checksum entry", line.matches("[a-f0-9]{64}  [a-z0-9-]+\\.(jpg|png)"));
            String name = line.substring(66);
            assertNull("Unique artwork checksum: " + name, expected.put(name, line.substring(0, 64)));
        }
        assertEquals("All eight reference fixtures are packaged in the test APK", java.util.Set.of(
                "movie-backdrop.jpg", "series-backdrop.jpg", "anime-backdrop.jpg", "movie-episode.jpg",
                "series-episode.jpg", "anime-episode.jpg", "series-logo.png", "anime-logo.png"), expected.keySet());
        JSONArray files = new JSONArray();
        for (String name : new java.util.TreeSet<>(expected.keySet())) {
            byte[] asset = readArtworkAsset(name);
            assertEquals("Packaged reference fixture checksum: " + name, expected.get(name), sha256(asset));
            File file = new File(root, name);
            Files.write(file.toPath(), asset);
            assertTrue("Target application can read copied reference fixture: " + file, file.isFile() && file.canRead());
            byte[] actual = Files.readAllBytes(file.toPath());
            assertEquals("Target application reads the exact reference fixture: " + name, expected.get(name), sha256(actual));
            files.put(new JSONObject().put("name", name).put("bytes", actual.length)
                    .put("sha256", expected.get(name)).put("readableByTargetUid", true));
        }
        JSONObject proof = new JSONObject().put("schema", "tv.library-fixture-read.v1")
                .put("source", "test-apk-assets/media-library").put("packageName", instrumentation.getTargetContext().getPackageName())
                .put("targetUid", targetUid).put("processUid", android.os.Process.myUid()).put("pid", android.os.Process.myPid())
                .put("fixtureDirectory", root.getCanonicalPath()).put("files", files);
        File evidence = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        assertTrue("Fixture evidence directory exists", evidence != null && (evidence.isDirectory() || evidence.mkdirs()));
        Files.write(new File(evidence, "library-fixture-read.json").toPath(), proof.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readArtworkAsset(String name) throws IOException {
        try (InputStream input = instrumentation.getContext().getAssets().open("media-library/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private String sha256(byte[] value) throws Exception {
        StringBuilder digest = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(value)) {
            digest.append(Character.forDigit((item >>> 4) & 15, 16));
            digest.append(Character.forDigit(item & 15, 16));
        }
        return digest.toString();
    }

    @Test public void movieUsesReferenceLayoutAndOnlyPlaysAfterRemoteConfirmation() {
        LibraryDetailActivity detail = open(false, false);
        await("Resources complete", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        assertEquals(0, plays.get());
        await("Initial focus selects play without requesting playback", () -> "play".equals(focused(detail))
                && device.findObject(By.res("play")) != null && device.findObject(By.res("play")).isFocused());
        assertEquals(0, plays.get());
        awaitArtwork(detail);
        capturePage(detail, "library-movie-reference");
        captureLowerSections(detail, "library-movie-sections");
        focus(detail, "play");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        VideoActivity video = awaitPlayer();
        await("Media requested", () -> plays.get() == 1);
        assertTrue(video.isFullscreenForPlayback());
        assertTrue(video.getIntent().getBooleanExtra("libraryPlayback", false));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Return to the same work page", () -> video.isDestroyed() && detail.hasWindowFocus());
        assertFocused(detail, "play");
        assertEquals(1, plays.get());
    }

    @Test public void seriesShowsEpisodeCardsAndResolvesTheExactSelectedEpisode() {
        LibraryDetailActivity detail = open(true, false);
        await("TMDB episode cards", () -> ((List<?>) field(detail, "episodes")).size() == 4);
        awaitArtwork(detail);
        capturePage(detail, "library-series-reference");
        captureLowerSections(detail, "library-series-sections");
        focus(detail, "episode:1:2");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        VideoActivity video = awaitPlayer();
        await("Second episode selected", () -> !requestedMedia.isEmpty());
        assertEquals("second", requestedMedia.get(0));
        assertEquals(2, video.getIntent().getIntExtra("mediaEpisode", -1));
        assertTrue(video.isFullscreenForPlayback());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Episode focus survives the list redraw", () -> video.isDestroyed() && detail.hasWindowFocus()
                && "episode:1:2".equals(focused(detail)));
    }

    @Test public void emptyResourcesKeepSeriesDetailsUsableAndQuiet() {
        LibraryDetailActivity detail = open(true, true);
        await("Resource search finishes", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        await("Episode metadata still loaded", () -> ((List<?>) field(detail, "episodes")).size() == 4);
        assertEquals(0, plays.get());
        assertNotNull(device.wait(Until.findObject(By.res("play")), 10000));
        await("The empty catalogue reports unavailable resources", () -> detail.getString(R.string.library_no_resources).equals(state(detail).getPlayLabel()));
        awaitArtwork(detail);
        capturePage(detail, "library-no-resources-reference");
        captureLowerSections(detail, "library-no-resources-sections");
        Keep previous = Keep.findDiscover("tmdb:tv:101");
        boolean wasKept = state(detail).getKept();
        try {
            click(detail, "resourceStatus");
            await("The empty resource action saves the work", () -> state(detail).getKept() != wasKept);
            assertEquals(!wasKept, Keep.findDiscover("tmdb:tv:101") != null);
            assertEquals("Saving an unavailable work never starts playback", 0, plays.get());
        } finally {
            main(() -> {
                Keep current = Keep.findDiscover("tmdb:tv:101");
                if (current != null) current.delete();
                if (previous != null) previous.save();
            });
        }
    }

    @Test public void rapidEpisodeNavigationPreservesFocusAndPlaybackTarget() {
        LibraryDetailActivity detail = open(true, false);
        await("Episode cards loaded", () -> ((List<?>) field(detail, "episodes")).size() == 4);
        awaitArtwork(detail);
        focus(detail, "play");
        capturePage(detail, "library-polish-hero");
        assertEpisodeFocusRoundTrip(detail);
        capturePage(detail, "library-polish-episodes");
        assertEquals("Moving focus does not start playback", 0, plays.get());
    }

    @Test public void galleryFocusFitsTheArtworkAndReturnsFromTheImageViewer() {
        LibraryDetailActivity detail = open(false, false);
        loadExtrasThroughMenu(detail);
        await("Gallery data loaded", () -> hasItem(detail, "image:0"));
        focus(detail, "image:0");
        UiObject2 image = detailNode("image:0");
        Rect bounds = image.getVisibleBounds();
        assertTrue("Focused gallery artwork has visible bounds", bounds.width() > 0 && bounds.height() > 0);
        assertTrue("The image-only gallery retains landscape proportions", bounds.width() > bounds.height());
        assertTrue("Gallery cards do not render title or metadata captions", image.getText() == null || image.getText().isEmpty());
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
            for (int index = 0; index < controls.getChildCount(); index++)
                assertNotEquals(viewer[0].getString(R.string.library_back), ((TextView) controls.getChildAt(index)).getText().toString());
        });
        for (boolean light : new boolean[]{true, false}) {
            main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(light));
            int expected = JetStreamPalette.resolveColor(viewer[0], R.color.jetstream_background);
            await("The image viewer follows the app appearance", () -> ((View) field(viewer[0], "root")).getBackground() instanceof ColorDrawable surface
                    && surface.getColor() == expected);
            assertFalse("Appearance changes retain the open image viewer", viewer[0].isDestroyed());
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Return restores the same artwork focus", () -> viewer[0].isDestroyed() && detail.hasWindowFocus()
                && "image:0".equals(focused(detail)));
        assertEquals("Browsing images does not start playback", 0, plays.get());
    }

    @Test public void episodeFocusStillWorksWhenSystemAnimationsAreDisabled() throws IOException {
        String previousScale = device.executeShellCommand("settings get global animator_duration_scale").trim();
        try {
            device.executeShellCommand("settings put global animator_duration_scale 0");
            LibraryDetailActivity detail = open(true, false);
            await("Episode cards loaded", () -> ((List<?>) field(detail, "episodes")).size() == 4);
            assertEpisodeFocusRoundTrip(detail);
            assertHeroFocusRoundTrip(detail, false);
            assertEquals("Disabled animations do not trigger playback", 0, plays.get());
        } finally {
            device.executeShellCommand(previousScale.isEmpty() || "null".equals(previousScale)
                    ? "settings delete global animator_duration_scale"
                    : "settings put global animator_duration_scale " + previousScale);
        }
    }

    @Test public void immersiveDetailsKeepRemoteNavigationAndBackWithoutAHeader() {
        LibraryDetailActivity detail = open(true, false);
        awaitArtwork(detail);
        assertHeroFocusRoundTrip(detail, true);
        assertEquals("Scrolling only browses the work", 0, plays.get());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Remote Back returns from the scrolled work page", detail::isDestroyed);
    }

    @Test public void lightDetailsFollowAppAppearanceAcrossTheImmersiveCanvas() {
        main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(true));
        LibraryDetailActivity detail = open(false, false);
        awaitArtwork(detail);
        chooseResourceFocus(detail, "fixture-a");
        instrumentation.waitForIdleSync(); SystemClock.sleep(450);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Rendered page screenshot", screenshot);
        try {
            int x = com.fongmi.android.tv.ui.custom.LibraryViews.dp(24);
            int y = screenshot.getHeight() * 9 / 10;
            int surface = screenshot.getPixel(x, y);
            assertTrue("The content margin follows the app light appearance", brightness(surface) > 150);
            int above = screenshot.getPixel(x, y - 4), below = screenshot.getPixel(x, y + 4);
            int jump = Math.max(Math.abs(Color.red(above) - Color.red(below)),
                    Math.max(Math.abs(Color.green(above) - Color.green(below)), Math.abs(Color.blue(above) - Color.blue(below))));
            assertTrue("Immersive shading remains continuous in the content margin", jump <= 48);
        } finally { screenshot.recycle(); }
        capturePage(detail, "library-polish-surface");
        assertEquals(0, plays.get());
    }

    @Test public void lazyGalleryAppearsOnNavigationWithoutStartingPlayback() {
        LibraryDetailActivity detail = open(false, false);
        awaitArtwork(detail);
        assertNull("Offscreen gallery cards are not eagerly laid out", device.findObject(By.res("image:0")));
        loadExtrasThroughMenu(detail);
        await("Gallery metadata ready", () -> hasItem(detail, "image:0"));
        focus(detail, "image:0");
        assertNotNull("Navigating to the gallery composes its visible card", detailNode("image:0"));
        assertNotNull("The actual bounded backdrop remains loaded while browsing", loadedBackdrop(detail));
        capturePage(detail, "library-polish-lazy-gallery");
        focus(detail, "play");
        assertFocused(detail, "play");
        assertEquals("Image loading and scrolling do not start playback", 0, plays.get());
    }

    @Test public void changingAppThemeWhileBrowsingKeepsEpisodeFocusAndScroll() {
        LibraryDetailActivity detail = open(true, false);
        await("Episode cards loaded", () -> hasItem(detail, "episode:1:2"));
        awaitArtwork(detail);
        focus(detail, "episode:1:2");
        instrumentation.waitForIdleSync(); SystemClock.sleep(450);
        Rect original = detailNode("episode:1:2").getVisibleBounds();
        Object playbackTarget = field(detail, "selectedEpisode");
        int previousBrightness = -1;
        for (boolean light : new boolean[]{true, false}) {
            main(() -> com.fongmi.android.tv.ui.rex.RexTheme.setLight(light));
            instrumentation.waitForIdleSync(); SystemClock.sleep(450);
            assertFalse("Changing appearance keeps the current detail Activity", detail.isDestroyed());
            assertTrue("The original detail window remains active", detail.hasWindowFocus());
            assertFocused(detail, "episode:1:2");
            assertEquals("Browsing an episode preserves the playback target", playbackTarget, field(detail, "selectedEpisode"));
            Rect current = detailNode("episode:1:2").getVisibleBounds();
            assertEquals("Theme changes retain the row's vertical position", original.top, current.top, 2);
            assertEquals("Theme changes retain the episode's measured height", original.height(), current.height(), 2);
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try {
                int actual = brightness(screenshot.getPixel(com.fongmi.android.tv.ui.custom.LibraryViews.dp(24), screenshot.getHeight() * 9 / 10));
                if (light) assertTrue("The detail follows the app light appearance", actual > 150);
                else assertTrue("The detail follows the app dark appearance", actual < previousBrightness - 60);
                previousBrightness = actual;
            } finally { screenshot.recycle(); }
            capturePage(detail, light ? "library-polish-theme-light" : "library-polish-theme-dark");
        }
        assertEquals("Changing appearance does not start playback", 0, plays.get());
    }

    private void assertHeroFocusRoundTrip(LibraryDetailActivity detail, boolean capture) {
        assertNull("The immersive detail has no separate return icon", detail.findViewById(R.id.back));
        assertNull("The immersive detail has no clock or status header", device.findObject(By.res(java.util.regex.Pattern.compile("(?:.*:id/)?libraryHeader"))));
        assertNull("The immersive detail has no clock", device.findObject(By.res("clock")));
        focus(detail, "episode:1:1");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await("Up reaches the season control", () -> "seasonSelector".equals(focused(detail)));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await("Up returns to the primary action", () -> "play".equals(focused(detail)));
        assertFocused(detail, "play");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        await("Down reaches the quick source action", () -> "source".equals(focused(detail)));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        await("Down returns to the selected episode", () -> focused(detail).startsWith("episode:1:"));
        if (capture) capturePage(detail, "library-polish-immersive-focus");
    }

    private void assertEpisodeFocusRoundTrip(LibraryDetailActivity detail) {
        assertTrue(hasItem(detail, "episode:1:1")); assertTrue(hasItem(detail, "episode:1:2"));
        Object target = field(detail, "selectedEpisode");
        focus(detail, "episode:1:1");
        for (int round = 0; round < 3; round++) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        }
        await("Rapid remote navigation returns to the first episode", () -> "episode:1:1".equals(focused(detail)));
        assertFocused(detail, "episode:1:1");
        assertEquals("Remote browsing preserves the main playback target", target, field(detail, "selectedEpisode"));
        Rect bounds = detailNode("episode:1:1").getVisibleBounds();
        assertTrue("The focused card is kept within the safe viewport", bounds.left >= 0 && bounds.top >= 0
                && bounds.right <= device.getDisplayWidth() && bounds.bottom <= device.getDisplayHeight());
        assertTrue("Episode artwork remains a landscape card", bounds.width() > bounds.height() / 2);
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

    @Test public void sourceFolderSearchOpensItsOriginalListAndReturnsToTheSameRow() {
        folderSearch = true;
        LibrarySearchActivity search = (LibrarySearchActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibrarySearchActivity.class)
                .putExtra("keyword", "野兽之心").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(search);
        await("Folder search completes", () -> searchComplete(search));
        com.fongmi.android.tv.source.LibrarySearchIndex.Snapshot snapshot = (com.fongmi.android.tv.source.LibrarySearchIndex.Snapshot) field(search, "snapshot");
        assertEquals(1, snapshot.works().size());
        assertEquals(0, snapshot.resourceCount());
        assertEquals(2, snapshot.supplements().size());
        assertTrue(snapshot.works().get(0).resources().isEmpty());
        RecyclerView results = search.findViewById(R.id.results);
        main(() -> search.findViewById(R.id.sources).performClick());
        await("Source shortcut focuses the folder row", () -> results.findViewHolderForAdapterPosition(2) != null
                && search.getCurrentFocus() == results.findViewHolderForAdapterPosition(2).itemView);
        View row = results.findViewHolderForAdapterPosition(2).itemView;
        main(() -> {
            assertEquals("Folder results use the available row width", results.getWidth() - results.getPaddingLeft() - results.getPaddingRight(), row.getWidth());
            assertEquals(work().getName(), ((TextView) row.findViewById(R.id.name)).getText().toString());
            assertTrue(((TextView) row.findViewById(R.id.remark)).getText().toString().contains("影视源一"));
            assertNotNull("A missing poster still has a folder icon", ((ImageView) row.findViewById(R.id.image)).getDrawable());
        });
        NativeUiEvidence.capture("library-source-folder-search");
        assertEquals(0, sourceActions.get());
        main(row::performClick);
        final VodActivity[] folder = new VodActivity[1];
        await("The native source list receives the untouched folder ID", () -> {
            for (Activity activity : allActivities()) if (activity instanceof VodActivity value && value.hasWindowFocus()) folder[0] = value;
            return folder[0] != null && requestedFolders.contains("gy:original-folder-id");
        });
        activities.add(folder[0]);
        assertEquals("fixture-a", folder[0].getIntent().getStringExtra("key"));
        assertEquals(0, plays.get());
        device.pressBack();
        await("Back restores the chosen folder result", () -> search.hasWindowFocus() && search.getCurrentFocus() == row);
        main(() -> {
            results.scrollToPosition(3);
        });
        await("The source action row is laid out", () -> results.findViewHolderForAdapterPosition(3) != null);
        main(() -> results.findViewHolderForAdapterPosition(3).itemView.performClick());
        await("Only an explicit click invokes the source action", () -> sourceActions.get() == 1);
        assertTrue(search.hasWindowFocus());
        assertEquals(0, plays.get());
    }

    @Test public void folderOnlyResultsRemainFocusableWhenMetadataFailsAndFilterChanges() {
        folderSearch = true; tmdbFails = true;
        LibrarySearchActivity search = (LibrarySearchActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibrarySearchActivity.class)
                .putExtra("keyword", "野兽之心").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(search);
        RecyclerView results = search.findViewById(R.id.results);
        await("Folder-only search finishes and focuses its first row", () -> searchComplete(search)
                && results.findViewHolderForAdapterPosition(1) != null && search.getCurrentFocus() == results.findViewHolderForAdapterPosition(1).itemView);
        main(() -> search.findViewById(R.id.filterMovies).performClick());
        await("Movie filtering still preserves source navigation", () -> searchComplete(search)
                && ((com.fongmi.android.tv.source.LibrarySearchIndex.Snapshot) field(search, "snapshot")).supplements().size() == 2);
        main(() -> {
            search.findViewById(R.id.filterMovies).requestFocus();
            search.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
            search.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
        });
        await("Down enters a full-width folder row", () -> results.findViewHolderForAdapterPosition(1) != null
                && search.getCurrentFocus() == results.findViewHolderForAdapterPosition(1).itemView);
        assertEquals(0, sourceActions.get()); assertEquals(0, plays.get());
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

    @Test public void qualityFiltersAreReachableFromResourceCards() {
        mixedQualities = true;
        LibraryDetailActivity detail = open(false, false);
        await("Both quality groups are available", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning()
                && state(detail).getResources().size() == 2
                && state(detail).getResourceFilters().stream().anyMatch(filter -> filter.getKey().equals("filter:4K")));
        String first = "resource:" + LibraryIdentity.sourceKey(resource(detail, "fixture-a"));
        String fourK = "resource:" + LibraryIdentity.sourceKey(resource(detail, "fixture-b"));
        focus(detail, first);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await("Up reaches the active All quality filter", () -> "filter:all".equals(focused(detail)));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        await("Right reaches the 4K quality filter", () -> "filter:4K".equals(focused(detail)));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("4K filtering keeps only the 4K source card", () -> state(detail).getResources().size() == 1
                && fourK.equals(state(detail).getResources().get(0).getKey()));
        assertTrue("The active quality chip remains selected", state(detail).getResourceFilters().stream()
                .anyMatch(filter -> filter.getKey().equals("filter:4K") && filter.getSelected()));
        assertEquals("Changing quality only browses resources", 0, plays.get());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        await("Down reaches the filtered 4K card", () -> fourK.equals(focused(detail)));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
        await("Up returns to the selected quality filter", () -> "filter:4K".equals(focused(detail)));
        assertFocused(detail, "filter:4K");
        assertEquals("Resource navigation does not start playback", 0, plays.get());
    }

    @Test public void manualResourceChoiceUsesThatSourceAndReturnsToItsCard() {
        LibraryDetailActivity detail = open(false, false);
        await("Manual source cards", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        chooseResource(detail, "fixture-b");
        VideoActivity video = awaitPlayer();
        await("Manual source requests media", () -> plays.get() == 1);
        assertEquals("fixture-b", video.getIntent().getStringExtra("key"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Manual card focus returns", () -> video.isDestroyed() && detail.hasWindowFocus() && ("resource:" + LibraryIdentity.sourceKey(resource(detail, "fixture-b"))).equals(focused(detail)));
    }

    @Test public void playerSourceControlReturnsToUnifiedResourcesWithCurrentProgress() {
        LibraryDetailActivity detail = open(false, false);
        await("Resources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        click(detail, "play");
        VideoActivity first = awaitPlayer(); await("First playback request", () -> plays.get() == 1);
        main(() -> {
            History current = (History) field(first, "mHistory"); current.setPosition(180_000); current.setDuration(3_600_000);
            first.findViewById(R.id.change2).performClick();
        });
        await("Player source action returns to the current source card", () -> first.isDestroyed() && detail.hasWindowFocus()
                && ("resource:" + LibraryIdentity.sourceKey(resource(detail, "fixture-a"))).equals(focused(detail)));
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
        click(detail, "play");
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
        focus(detail, "play");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue("Source detail started", detailsEntered.await(10, TimeUnit.SECONDS));
        await("The playback button describes cancellation", () -> state(detail).getPlayLabel().equals(detail.getString(R.string.library_cancel_playback)));

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);

        await("Cancellation stays on the work page", () -> !resources.isSelecting() && detail.hasWindowFocus());
        holdDetails = false;
        releaseDetails.countDown();
        await("Cancelled calls settle", () -> fixture.dispatcher().runningCallsCount() == 0);
        assertEquals(0, plays.get());
        assertFocused(detail, "play");
        assertFalse(allActivities().stream().anyMatch(activity -> activity instanceof VideoActivity));
    }

    @Test public void automaticSelectionSkipsAFailedSourceDetail() {
        failedDetailSite = "/fixture-a";
        LibraryDetailActivity detail = open(false, false);
        await("Sources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        click(detail, "play");
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
        click(detail, "episode:1:2");
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
        click(detail, "play");
        assertTrue("Source detail started", detailsEntered.await(10, TimeUnit.SECONDS));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Selection cancelled while the page stays open", () -> !((LibraryResourcesController) field(detail, "resources")).isSelecting() && detail.hasWindowFocus());
        holdDetails = false; releaseDetails.countDown();
        await("Cancelled calls settle", () -> fixture.dispatcher().runningCallsCount() == 0);
        assertEquals(0, plays.get());
        assertTrue(allActivities().stream().noneMatch(activity -> activity instanceof VideoActivity));
        click(detail, "play");
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
        click(detail, "play");
        await("Automatic detail requests reach their deadlines", () -> !((LibraryResourcesController) field(detail, "resources")).isSelecting());
        assertTrue(detail.hasWindowFocus()); assertEquals(0, plays.get());
        assertTrue(allActivities().stream().noneMatch(activity -> activity instanceof VideoActivity));
    }

    @Test public void leavingTheWorkPageCancelsAnUnfinishedSelection() throws Exception {
        holdDetails = true;
        LibraryDetailActivity detail = open(false, false);
        await("Resources ready", () -> !((LibraryResourcesController) field(detail, "resources")).isRunning());
        click(detail, "play");
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
        click(detail, "play");
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
            await("Third episode card", () -> hasItem(detail, "episode:1:3"));
            click(detail, "episode:1:3");
            VideoActivity video = awaitPlayer(); await("Third episode requested", () -> plays.get() == 1);
            assertEquals("third", requestedMedia.get(0));
            assertFalse(video.getIntent().hasExtra("libraryProgress"));
            assertTrue(((History) field(video, "mHistory")).getPosition() <= 0);
        } finally { AppDatabase.get().getHistoryDao().delete(VodConfig.getCid(), saved.getKey()); }
    }

    @Test public void resumeStartsOnTheRangeContainingTheSavedEpisode() {
        series = longSeason = true;
        History saved = new History(); saved.setKey("fixture-b@@@fixture-work@@@" + VodConfig.getCid()); saved.setCid(VodConfig.getCid());
        saved.setVodName(work().getName()); saved.setVodPic(work().getPic()); saved.setVodFlag("线路一"); saved.setVodRemarks("第35集"); saved.setEpisodeUrl("episode-35");
        saved.setMediaRef("tmdb:tv:101"); saved.setMediaSeason(1); saved.setMediaEpisode(35); saved.setPosition(120_000); saved.setDuration(2_700_000);
        saved.setCreateTime(System.currentTimeMillis()); AppDatabase.get().getHistoryDao().insertOrUpdate(saved);
        try {
            LibraryDetailActivity detail = open(true, false);
            await("The saved episode opens its containing range", () -> hasItem(detail, "episode:1:35")
                    && state(detail).getEpisodes().size() == 5);
            assertEquals(List.of("episode:1:31", "episode:1:32", "episode:1:33", "episode:1:34", "episode:1:35"),
                    state(detail).getEpisodes().stream().map(item -> item.getKey()).toList());
            assertTrue("The saved episode stays selected", state(detail).getEpisodes().stream()
                    .anyMatch(item -> item.getKey().equals("episode:1:35") && item.getSelected()));
            String resumeHint = detail.getString(R.string.library_detail_resume_episode_hint,
                    detail.getString(R.string.library_season, 1), 35, com.fongmi.android.tv.utils.Util.timeMs(120_000));
            await("The primary action describes episode 35 and its saved progress", () -> resumeHint.equals(state(detail).getPlayHint()));
            assertEquals(Integer.valueOf(35), field(detail, "selectedEpisode")); assertEquals(0, plays.get());

            focus(detail, "episode:1:35");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            await("Down reaches the current episode range", () -> "range:catalog:1".equals(focused(detail)));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            await("Left reaches the first episode range", () -> "range:catalog:0".equals(focused(detail)));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            await("The first range becomes the browsing page", () -> state(detail).getEpisodes().size() == 30
                    && hasItem(detail, "episode:1:1") && !hasItem(detail, "episode:1:35"));
            assertTrue("The selected browsing range is retained", state(detail).getRanges().stream()
                    .anyMatch(range -> range.getKey().equals("range:catalog:0") && range.getSelected()));
            assertEquals("Browsing a different range keeps the saved playback episode", Integer.valueOf(35), field(detail, "selectedEpisode"));
            assertEquals("Browsing a different range keeps the resume hint", resumeHint, state(detail).getPlayHint());
            instrumentation.waitForIdleSync();
            assertEquals("The page remains on the manually chosen range", "episode:1:1", state(detail).getEpisodes().get(0).getKey());
            assertEquals("Range browsing never starts playback", 0, plays.get());
        } finally { AppDatabase.get().getHistoryDao().delete(VodConfig.getCid(), saved.getKey()); }
    }

    @Test public void browsingEpisodesWithTheRemoteKeepsTheResumeTargetAndTime() {
        series = true;
        History saved = new History(); saved.setKey("fixture-b@@@fixture-work@@@" + VodConfig.getCid()); saved.setCid(VodConfig.getCid());
        saved.setVodName(work().getName()); saved.setVodPic(work().getPic()); saved.setVodFlag("线路一"); saved.setVodRemarks("第2集"); saved.setEpisodeUrl("second");
        saved.setMediaRef("tmdb:tv:101"); saved.setMediaSeason(1); saved.setMediaEpisode(2); saved.setPosition(120_000); saved.setDuration(2_700_000);
        saved.setCreateTime(System.currentTimeMillis()); AppDatabase.get().getHistoryDao().insertOrUpdate(saved);
        try {
            LibraryDetailActivity detail = open(true, false);
            await("Episodes and resources ready", () -> hasItem(detail, "episode:1:4")
                    && !((LibraryResourcesController) field(detail, "resources")).isRunning());
            String resumeHint = detail.getString(R.string.library_detail_resume_episode_hint,
                    detail.getString(R.string.library_season, 1), 2, com.fongmi.android.tv.utils.Util.timeMs(120_000));
            await("Resume action identifies the saved episode and time", () -> resumeHint.equals(state(detail).getPlayHint())
                    && playText(detail).contains("2:00"));
            String resumeLabel = playText(detail);
            focus(detail, "play");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            await("Down exposes the source quick action", () -> "source".equals(focused(detail)));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            await("Down enters the resumed episode", () -> "episode:1:2".equals(focused(detail)));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            await("The fourth episode is only being browsed", () -> "episode:1:4".equals(focused(detail)));
            assertEquals(Integer.valueOf(2), field(detail, "selectedEpisode"));
            assertEquals(resumeLabel, playText(detail)); assertEquals(0, plays.get());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            await("Up reaches the season control", () -> "seasonSelector".equals(focused(detail)));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            await("Up returns to the main resume action", () -> "play".equals(focused(detail)));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            VideoActivity video = awaitPlayer(); await("The saved episode resumes", () -> plays.get() == 1);
            assertEquals(List.of("second"), requestedMedia); assertEquals("fixture-b", video.getIntent().getStringExtra("key"));
            assertEquals(2, video.getIntent().getIntExtra("mediaEpisode", -1));
            assertEquals(120_000, ((History) field(video, "mHistory")).getPosition());
        } finally { AppDatabase.get().getHistoryDao().delete(VodConfig.getCid(), saved.getKey()); }
    }

    @Test public void changingSeasonRemovesOldEpisodeActionsWhileTheNewSeasonLoads() throws Exception {
        multipleSeasons = holdSecondSeason = true;
        LibraryDetailActivity detail = open(true, false);
        await("First season episode actions ready", () -> hasItem(detail, "episode:1:2"));
        focus(detail, "seasonSelector"); instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        UiObject2 secondSeason = device.wait(Until.findObject(By.text(detail.getString(R.string.library_season, 2))), 10000);
        assertNotNull("The season menu offers the second season", secondSeason); secondSeason.click();
        assertTrue("Second season request started", seasonEntered.await(10, TimeUnit.SECONDS));
        await("The old season is removed while the next season loads", () -> state(detail).getEpisodesLoading()
                && !hasItem(detail, "episode:1:2"));
        assertNull("Old season actions are removed from accessibility", device.findObject(By.res("episode:1:2")));
        UiObject2 loading = detailNode("episodesLoading");
        assertFalse("Loading copy is not another action", loading.isFocusable());
        assertNotNull("The season control remains available", detailNode("seasonSelector"));
        assertEquals(0, plays.get());
        holdSecondSeason = false; releaseSeason.countDown();
        await("The new season replaces the waiting state", () -> hasItem(detail, "episode:2:1") && !state(detail).getEpisodesLoading());
        assertEquals(0, plays.get());
    }

    @Test public void simpleModeLoadsExtraInformationOnlyWhenRequested() {
        main(() -> ExperienceSettings.putMode(ExperienceMode.SIMPLE));
        LibraryDetailActivity detail = open(false, false);
        assertTrue("Simple mode keeps cast data hidden", state(detail).getCast().isEmpty());
        assertEquals(0, extrasRequests.get());
        loadExtrasThroughMenu(detail);
        await("Extra information expands", () -> field(detail, "extras") != null && !state(detail).getCast().isEmpty());
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
        await("Source detail exposes original episodes", () -> hasItem(detail, "original:线路一:second"));
        assertNull(field(detail, "metadata")); assertEquals(0, plays.get());
        click(detail, "original:线路一:second");
        VideoActivity video = awaitPlayer(); await("Original second episode requested", () -> plays.get() == 1);
        assertEquals("second", requestedMedia.get(0)); assertFalse(video.getIntent().hasExtra("mediaEpisode"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Original episode returns to its work", () -> video.isDestroyed() && detail.hasWindowFocus());
        click(detail, "play");
        awaitPlayer(); await("The source-only work resumes its exact original episode", () -> plays.get() == 2);
        assertEquals(List.of("second", "second"), requestedMedia);
    }

    @Test public void rawEpisodeSelectionReplacesAnEarlierNumberedPlaybackTarget() {
        series = tmdbFails = true;
        Vod item = work(); item.setId("fixture-work"); item.setTypeName(""); item.setSite(VodConfig.get().getSite("fixture-a"));
        LibraryDetailActivity detail = (LibraryDetailActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), LibraryDetailActivity.class)
                .putExtra("work", item).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); activities.add(detail);
        await("Original episodes and sources ready", () -> hasItem(detail, "original:线路一:second")
                && !((LibraryResourcesController) field(detail, "resources")).isRunning());

        click(detail, "play");
        VideoActivity first = awaitPlayer(); await("Main action starts the first episode", () -> requestedMedia.equals(List.of("first")));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("First playback returns its numbered target", () -> first.isDestroyed() && detail.hasWindowFocus()
                && Integer.valueOf(1).equals(field(detail, "selectedEpisode")));

        click(detail, "original:线路一:second");
        VideoActivity second = awaitPlayer(); await("Raw selection starts the second episode", () -> requestedMedia.equals(List.of("first", "second")));
        assertFalse("The raw selection does not invent catalogue coordinates", second.getIntent().hasExtra("mediaEpisode"));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await("Raw selection replaces the previous numbered target", () -> second.isDestroyed() && detail.hasWindowFocus()
                && field(detail, "selectedEpisode") == null);

        click(detail, "play");
        awaitPlayer(); await("Resume keeps the last explicitly selected source episode", () -> plays.get() == 3);
        assertEquals(List.of("first", "second", "second"), requestedMedia);
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
        click(detail, "play");
        VideoActivity video = awaitPlayer();
        await("Prefetched response accepted by the full-screen player", () -> field(video, "mQualityResult") != null);
        assertTrue(video.isFullscreenForPlayback()); assertEquals(0, plays.get()); assertEquals(1, linkRequests.get());
        assertFalse(video.getIntent().hasExtra("libraryPrefetch"));
    }

    @Test public void episodeFocusPrefetchUsesTheExplicitNumberInAReorderedPlaylist() {
        directPrefetch = reorderedEpisodes = true;
        LibraryDetailActivity detail = open(true, false);
        await("Episode cards", () -> hasItem(detail, "episode:1:2"));
        focus(detail, "episode:1:2");
        await("Second episode link warmed", () -> prefetchedEpisodes.contains("second") && ((com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch) field(detail, "linkPrefetch")).isReady());
        assertEquals(0, plays.get());
        assertFalse("Speculative prefetch leaves the main playback target unchanged", Integer.valueOf(2).equals(field(detail, "selectedEpisode")));
        click(detail, "episode:1:2");
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
        await("Second episode", () -> hasItem(detail, "episode:1:2"));
        click(detail, "episode:1:2");
        VideoActivity video = awaitPlayer(); await("Second requested", () -> plays.get() == 1);
        main(() -> ((com.fongmi.android.tv.playback.vod.VodPlaybackController) field(video, "mVod")).nextEpisode(false));
        await("Third requested instead of neighboring first", () -> plays.get() == 2);
        assertEquals(List.of("second", "third"), requestedMedia);
        assertEquals(Integer.valueOf(3), ((History) field(video, "mHistory")).getMediaEpisode());
    }

    @Test public void fullscreenEpisodePickerChangesTheEpisodeAndReturnsItsHistory() {
        LibraryDetailActivity detail = open(true, false);
        await("Second episode available", () -> hasItem(detail, "episode:1:2"));
        click(detail, "episode:1:2");
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
        await("The reopened episode sheet owns Back and focuses the selected episode", () -> {
            androidx.fragment.app.DialogFragment dialog = (androidx.fragment.app.DialogFragment) video.getSupportFragmentManager().findFragmentByTag("vod-selection");
            if (dialog == null || dialog.getDialog() == null || !dialog.getDialog().isShowing()
                    || dialog.getDialog().getWindow() == null || !dialog.getDialog().getWindow().getDecorView().hasWindowFocus()) return false;
            View focused = dialog.getDialog().getCurrentFocus();
            return focused instanceof TextView text && text.getText().toString().equals("第3集");
        });
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
        await("Second episode available", () -> hasItem(detail, "episode:1:2"));
        click(detail, "episode:1:2");
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
        click(detail, "episode:1:2");
        await("Unknown generated numbers remain manual", () -> !((LibraryResourcesController) field(detail, "resources")).isSelecting() && detail.hasWindowFocus());
        assertEquals(0, plays.get()); assertEquals(0, linkRequests.get());
        assertTrue(allActivities().stream().noneMatch(activity -> activity instanceof VideoActivity));
    }

    @Test public void seriesLogoFallsBackToOriginalLanguageAndShowsCurrentSeasonUpdateCount() {
        anime = fallbackLogo = true;
        LibraryDetailActivity detail = open(true, false);
        await("Original-language logo is displayed", () -> state(detail).getLogo().endsWith("anime-logo.png")
                && "loaded".equals(accessibilityState("logo")));
        await("Current season progress", () -> state(detail).getSeriesStatus().contains(detail.getString(R.string.library_series_progress, 4, 12)));
        assertTrue(state(detail).getSeriesStatus().contains("12")); assertEquals(0, plays.get());
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
        chooseResourceFocus(detail, site);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private void captureLowerSections(LibraryDetailActivity detail, String name) {
        loadExtrasThroughMenu(detail);
        LibraryDetailState display = state(detail);
        if (!display.getStills().isEmpty()) focus(detail, display.getStills().get(0).getKey());
        else if (!display.getCast().isEmpty()) focus(detail, display.getCast().get(0).getKey());
        instrumentation.waitForIdleSync(); SystemClock.sleep(450);
        capturePage(detail, name);
        display = state(detail);
        if (!display.getSimilar().isEmpty()) focus(detail, display.getSimilar().get(display.getSimilar().size() - 1).getKey());
        capturePage(detail, name + "-bottom");
        focus(detail, state(detail).getPlayEnabled() ? "play" : "summary");
    }
    private void capturePage(LibraryDetailActivity detail, String name) {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("native_ui_evidence"))) return;
        NativeUiEvidence.capture(name);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("native-usability-evidence");
        try {
            Files.write(new File(directory, name + ".focus").toPath(), focused(detail).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException error) { throw new AssertionError("Capture stable focus key", error); }
    }

    private LibraryDetailView host(LibraryDetailActivity detail) {
        final LibraryDetailView[] found = new LibraryDetailView[1];
        main(() -> found[0] = findHost(detail.getWindow().getDecorView()));
        assertNotNull("Compose detail host is attached", found[0]);
        return found[0];
    }
    private LibraryDetailView findHost(View root) {
        if (root instanceof LibraryDetailView value) return value;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            LibraryDetailView result = findHost(group.getChildAt(i));
            if (result != null) return result;
        }
        return null;
    }
    private LibraryDetailState state(LibraryDetailActivity detail) {
        LibraryDetailView host = host(detail);
        final LibraryDetailState[] current = new LibraryDetailState[1];
        main(() -> current[0] = host.currentState());
        return current[0];
    }
    private String focused(LibraryDetailActivity detail) {
        LibraryDetailView host = host(detail);
        final String[] key = new String[1];
        main(() -> key[0] = host.focusedKey());
        return key[0];
    }
    private String playText(LibraryDetailActivity detail) {
        LibraryDetailState current = state(detail);
        return current.getPlayLabel() + " " + current.getPlayHint();
    }
    private boolean hasItem(LibraryDetailActivity detail, String key) {
        LibraryDetailState current = state(detail);
        return java.util.stream.Stream.of(current.getEpisodes(), current.getResources(), current.getCast(), current.getTrailers(), current.getStills(), current.getSimilar())
                .flatMap(List::stream).anyMatch(item -> key.equals(item.getKey()));
    }
    private UiObject2 detailNode(String key) {
        UiObject2 node = device.wait(Until.findObject(By.res(key)), 10000);
        assertNotNull("Visible Compose node: " + key, node);
        return node;
    }
    private void focus(LibraryDetailActivity detail, String key) {
        LibraryDetailView host = host(detail);
        main(() -> host.focusKey(key));
        await("Actual focus reaches " + key, () -> {
            UiObject2 node = device.findObject(By.res(key));
            return key.equals(focused(detail)) && node != null && node.isFocused();
        });
        instrumentation.waitForIdleSync();
    }
    private void assertFocused(LibraryDetailActivity detail, String key) {
        assertEquals(key, focused(detail));
        assertTrue("TV focus is exposed to accessibility", detailNode(key).isFocused());
    }
    private void click(LibraryDetailActivity detail, String key) {
        focus(detail, key);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private void chooseResourceFocus(LibraryDetailActivity detail, String site) {
        String key = LibraryIdentity.sourceKey(resource(detail, site));
        focus(detail, "resource:" + key);
    }
    private void loadExtrasThroughMenu(LibraryDetailActivity detail) {
        if (field(detail, "extras") != null && !state(detail).getStills().isEmpty()) return;
        click(detail, "more");
        UiObject2 expand = device.wait(Until.findObject(By.text(detail.getString(R.string.library_more))), 10000);
        assertNotNull("More menu exposes supplemental information", expand); expand.click();
        await("Supplemental catalogue data loaded", () -> field(detail, "extras") != null);
    }
    private int brightness(int color) { return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3; }
    private String accessibilityState(String key) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root == null) return "";
        try { return accessibilityState(root, key); }
        finally { root.recycle(); }
    }
    private String accessibilityState(AccessibilityNodeInfo node, String key) {
        String id = node.getViewIdResourceName();
        if (key.equals(id) || id != null && id.endsWith(":id/" + key)) {
            CharSequence state = AccessibilityNodeInfoCompat.wrap(node).getStateDescription();
            return state == null ? "" : state.toString();
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
        await("Reference artwork actually loaded", () -> loadedBackdrop(activity) != null);
        Bitmap bitmap = loadedBackdrop(activity);
        assertNotNull(bitmap);
        assertTrue("Backdrop decoding is bounded to the TV viewport", bitmap.getWidth() <= 1920 && bitmap.getHeight() <= 1080);
        instrumentation.waitForIdleSync(); SystemClock.sleep(450);
    }
    private Bitmap loadedBackdrop(LibraryDetailActivity detail) {
        LibraryDetailView view = host(detail);
        String expected = state(detail).getBackdrop();
        final Bitmap[] found = new Bitmap[1];
        main(() -> found[0] = loadedBackdrop(view, expected));
        return found[0];
    }
    private Bitmap loadedBackdrop(View view, String expected) {
        if (view instanceof ImageView image && view.getClass().getSimpleName().equals("DetailArtworkImageView")
                && expected.equals(field(view, "artworkUrl")) && image.getDrawable() instanceof BitmapDrawable drawable) {
            Bitmap bitmap = drawable.getBitmap();
            if (bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 100 && bitmap.getHeight() > 100) return bitmap;
        }
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            Bitmap bitmap = loadedBackdrop(group.getChildAt(index), expected);
            if (bitmap != null) return bitmap;
        }
        return null;
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
        if (folderSearch) {
            if (url.encodedPath().equals("/fixture-action")) { sourceActions.incrementAndGet(); return "{\"msg\":\"账号设置已打开\"}"; }
            if (url.queryParameter("t") != null) {
                requestedFolders.add(url.queryParameter("t"));
                return "{\"list\":[{\"vod_id\":\"selected-version\",\"vod_name\":\"版本一\"}],\"page\":1,\"pagecount\":1}";
            }
            if (url.encodedPath().equals("/fixture-b")) return "{\"list\":[]}";
            return App.gson().toJson(Map.of("list", List.of(
                    Map.of("vod_id", "gy:original-folder-id", "vod_name", work().getName(), "vod_year", "2026", "type_name", "电视剧", "vod_tag", "folder"),
                    Map.of("vod_name", "账号设置", "action", "https://library-fixture.invalid/fixture-action"))));
        }
        Vod item = new Vod(); item.setId("fixture-work"); item.setName(work().getName()); item.setYear("2026"); item.setTypeName(series ? "电视剧" : "电影"); item.setPic(work().getPic()); item.setRemarks(mixedQualities && url.encodedPath().equals("/fixture-b") ? "4K" : "1080P");
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
            if (longSeason) {
                for (int i = 1; i <= 35; i++) episodes.add(Map.of("episode_number", i, "name", "长季故事 " + i,
                        "runtime", 45, "air_date", "2026-01-01", "still_path", "/series-episode.jpg",
                        "overview", "一场相遇改变了原本平静的生活，新的故事由此展开。"));
                return App.gson().toJson(Map.of("episodes", episodes));
            }
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
                : List.of(Map.of("season_number", 1, "episode_count", longSeason ? 35 : anime ? 12 : 30)));
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
