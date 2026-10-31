package com.fongmi.android.tv.player.media;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.test.LocalSubtitleMetadataProvider;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class LocalSubtitleScannerAndroidTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File directory;
    private File video;
    private Uri provider;

    @Before public void setup() throws Exception {
        String packageName = target.getPackageName();
        assertTrue("Disposable validation package only", packageName.equals("com.fongmi.android.tv.preview")
                || packageName.equals("com.fongmi.android.tv.sourceprobe"));
        directory = Files.createTempDirectory(target.getFilesDir().toPath(), "subtitle-sidecars-").toFile();
        video = new File(directory, "Movie + 字幕.mp4");
        Files.write(video.toPath(), new byte[0]);
        Files.writeString(new File(directory, "Movie + 字幕.vtt").toPath(), "WEBVTT\n\n00:00.000 --> 00:01.000\nLocal subtitle\n");
        Files.writeString(new File(directory, "Movie + 字幕.en.srt").toPath(), "1\n00:00:00,000 --> 00:00:01,000\nEnglish subtitle\n");
        ProviderInfo[] providers = target.getPackageManager().getPackageInfo(
                instrumentation.getContext().getPackageName(), PackageManager.GET_PROVIDERS).providers;
        assertNotNull(providers);
        for (ProviderInfo entry : providers) {
            if (LocalSubtitleMetadataProvider.class.getName().equals(entry.name)) provider = Uri.parse("content://" + entry.authority);
        }
        assertNotNull("Instrumented metadata provider", provider);
        target.getContentResolver().call(provider, "reset", null, null);
    }

    @After public void cleanup() throws Exception {
        worker.shutdownNow();
        worker.awaitTermination(3, TimeUnit.SECONDS);
        if (directory != null) {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }

    @Test public void shouldDiscoverEncodedLocalFilesAndBuildReadableVttTracks() throws Exception {
        List<Sub> found = worker.submit(() -> LocalSubtitleScanner.scan(target, video.toURI().toString())).get(5, TimeUnit.SECONDS);

        assertEquals(2, found.size());
        assertEquals("Movie + 字幕.vtt", found.get(0).getName());
        assertEquals(MimeTypes.TEXT_VTT, found.get(0).getFormat());
        assertEquals(C.SELECTION_FLAG_DEFAULT, found.get(0).getRawFlag());
        assertEquals("en", found.get(1).getLang());
        assertTrue(new File(found.get(0).getUri().getPath()).isFile());
        try (java.io.InputStream input = target.getContentResolver().openInputStream(found.get(0).getUri())) {
            assertNotNull(input);
            assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).startsWith("WEBVTT"));
        }
        assertEquals(0, counters().getInt("queries"));
    }

    @Test public void shouldUseReadableLocalMetadataWithoutCopyingTheVideo() throws Exception {
        Uri uri = provider.buildUpon().appendPath("local").appendQueryParameter("path", video.getAbsolutePath()).build();

        List<Sub> found = worker.submit(() -> LocalSubtitleScanner.scan(target, uri.toString())).get(5, TimeUnit.SECONDS);

        assertEquals(2, found.size());
        assertEquals(1, counters().getInt("queries"));
        assertEquals(0, counters().getInt("opens"));
    }

    @Test public void shouldLeaveOpaqueDocumentsAndRemoteUrlsAlone() throws Exception {
        assertTrue(worker.submit(() -> LocalSubtitleScanner.scan(target, provider.buildUpon().appendPath("opaque").build().toString())).get(5, TimeUnit.SECONDS).isEmpty());
        assertTrue(worker.submit(() -> LocalSubtitleScanner.scan(target, "https://example.invalid/video.mp4")).get(5, TimeUnit.SECONDS).isEmpty());
        assertEquals(1, counters().getInt("queries"));
        assertEquals(0, counters().getInt("opens"));
    }

    @Test public void shouldNeverQueryProvidersOrScanFoldersOnTheMainThread() {
        AtomicReference<List<Sub>> found = new AtomicReference<>();
        Uri uri = provider.buildUpon().appendPath("local").appendQueryParameter("path", video.getAbsolutePath()).build();

        instrumentation.runOnMainSync(() -> found.set(LocalSubtitleScanner.scan(target, uri.toString())));

        assertTrue(found.get().isEmpty());
        assertEquals(0, counters().getInt("queries"));
    }

    @Test public void shouldCarryLocalSidecarsInPreloadedHttpProxyResultsWithoutScanningRemoteSources() throws Exception {
        Site fixture = App.gson().fromJson("{\"key\":\"subtitle-fixture-" + UUID.randomUUID()
                + "\",\"name\":\"Local subtitle fixture\",\"type\":3,\"api\":\"csp_SubtitleFixture\",\"jar\":\"subtitle-fixture\"}", Site.class);
        Object jarLoader = field(BaseLoader.get(), "jarLoader");
        Map<String, Spider> spiders = (Map<String, Spider>) field(jarLoader, "spiders");
        String cacheKey = Util.md5(fixture.getJar()) + fixture.getKey();
        Object savedSites = field(VodConfig.get(), "sites");
        Object savedRecent = field(jarLoader, "recent");
        AtomicReference<String> reply = new AtomicReference<>("{\"url\":\"http://127.0.0.1:12345/video.mp4\",\"parse\":0}");
        Spider spider = new Spider() {
            @Override public String playerContent(String flag, String id, List<String> vipFlags) { return reply.get(); }
        };
        try {
            setField(VodConfig.get(), "sites", List.of(fixture));
            spiders.put(cacheKey, spider);
            Result local = worker.submit(() -> SiteApi.preloadContent(fixture.getKey(), "file", video.toURI().toString())).get(5, TimeUnit.SECONDS);

            assertEquals("http://127.0.0.1:12345/video.mp4", local.getUrl().v());
            assertEquals("A proxy-backed local episode carries its subtitles into the cached Result", 2, local.getSubs().size());
            assertEquals(MimeTypes.TEXT_VTT, local.getSubs().get(0).getFormat());

            Result remote = worker.submit(() -> SiteApi.preloadContent(fixture.getKey(), "file", "https://example.invalid/episode-2")).get(5, TimeUnit.SECONDS);
            assertTrue("Remote speculative requests must not search local directories", remote.getSubs().isEmpty());
            assertEquals(0, counters().getInt("queries"));

            reply.set("{\"url\":\"http://127.0.0.1:12345/video.mp4\",\"parse\":0,\"subs\":[{\"name\":\"Provided\",\"url\":\"https://example.invalid/provided.srt\",\"lang\":\"en\"}]}");
            Result provided = worker.submit(() -> SiteApi.preloadContent(fixture.getKey(), "file", video.toURI().toString())).get(5, TimeUnit.SECONDS);
            assertEquals(1, provided.getSubs().size());
            assertEquals("https://example.invalid/provided.srt", provided.getSubs().get(0).getUrl());
        } finally {
            spiders.remove(cacheKey);
            setField(VodConfig.get(), "sites", savedSites);
            setField(jarLoader, "recent", savedRecent);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private Bundle counters() {
        Bundle counters = target.getContentResolver().call(provider, "counts", null, null);
        assertNotNull(counters);
        return counters;
    }
}
