package com.fongmi.android.tv.api;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.CueGroup;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AssrtSubtitle;
import com.fongmi.android.tv.bean.AssrtSubtitleFile;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.test.AssrtSubtitlePreviewActivity;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.dialog.SubtitleSearchDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.utils.SubtitleDownload;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import static org.junit.Assert.*;

/** Actual ASSRT HTTP, shipping dialogs and Exo text tracks with generated local fixtures. */
@RunWith(AndroidJUnit4.class)
public final class AssrtSubtitleSearchAndroidTest {

    private static final String TOKEN = "assrt-ui-fixture";
    private static final String RECORD_TITLE = "字幕搜索演示 · 第二季字幕合集";
    private static final String WANTED = "Fixture.Show.S02E03.zh.srt";
    private static final String OTHER = "Fixture.Show.S02E04.zh.srt";
    private static final String CAPTION = "ASSRT 中文字幕";
    private static final Map<String, Object> SETTINGS = Map.ofEntries(
            Map.entry("player_engine", PlayerSetting.ENGINE_EXO), Map.entry("tunnel", false),
            Map.entry("preload", false), Map.entry("preload_next", false), Map.entry("incognito", true),
            Map.entry("audio_effect_preset", 0), Map.entry("audio_pass_through", false),
            Map.entry("audio_prefer", false), Map.entry("video_prefer", false), Map.entry("av3a", false),
            Map.entry("subtitle_libass", false), Map.entry("ai_subtitle_enabled", false),
            Map.entry("community_skip_enabled", false));

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private Map<String, ?> saved;
    private MockWebServer server;
    private AssrtApi.Client client;
    private SubtitleSearchDialog.Services services;
    private AssrtSubtitlePreviewActivity activity;
    private volatile boolean searchError;
    private volatile boolean zip;
    private volatile boolean rar;
    private volatile boolean rarTraversal;
    private volatile boolean rarEncrypted;
    private volatile long searchDelay;
    private volatile long downloadDelay;

    @Before
    public void setup() throws Exception {
        String applicationId = instrumentation.getTargetContext().getPackageName();
        assertTrue("Use an isolated validation app", applicationId.equals("com.fongmi.android.tv.preview")
                || applicationId.equals("com.fongmi.android.tv.sourceprobe"));
        instrumentation.setInTouchMode(false);
        saved = new HashMap<>(Prefers.getPrefers().getAll());
        main(() -> {
            SETTINGS.forEach(Prefers::put);
            Track.delete(AssrtSubtitlePreviewActivity.KEY);
            Track.delete(AssrtSubtitlePreviewActivity.KEY + "|secondary");
        });
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                requests.add(request);
                String path = request.getRequestUrl().encodedPath();
                if (path.equals("/v1/sub/search")) {
                    return json(searchError ? "{\"status\":101,\"errmsg\":\"Controlled query error\"}" : searchResponse())
                            .setBodyDelay(searchDelay, TimeUnit.MILLISECONDS);
                }
                if (path.equals("/v1/sub/detail")) return json(detailResponse());
                if (path.equals("/download/subtitles.zip")) {
                    try {
                        return new MockResponse().setHeader("Content-Type", "application/zip")
                                .setBody(new Buffer().write(archive())).setBodyDelay(downloadDelay, TimeUnit.MILLISECONDS);
                    } catch (Exception error) { throw new AssertionError(error); }
                }
                if (path.equals("/download/subtitles.rar")) {
                    try {
                        return new MockResponse().setHeader("Content-Type", "application/vnd.rar")
                                .setBody(new Buffer().write(rarArchive(rarTraversal, rarEncrypted)))
                                .setBodyDelay(downloadDelay, TimeUnit.MILLISECONDS);
                    } catch (Exception error) { throw new AssertionError(error); }
                }
                if (path.equals("/download/wanted.srt") || path.equals("/download/other.srt")) {
                    return new MockResponse().setHeader("Content-Type", "application/x-subrip; charset=utf-8")
                            .setBody(subtitle(path.endsWith("wanted.srt") ? CAPTION : "Wrong episode must not auto-load"))
                            .setBodyDelay(downloadDelay, TimeUnit.MILLISECONDS);
                }
                return new MockResponse().setResponseCode(404).setBody("Unexpected local fixture request");
            }
        });
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        client = new AssrtApi.Client(url("/v1"), TOKEN,
                request -> OkHttp.client().newCall(request), App::post, SystemClock::elapsedRealtime);
        services = new SubtitleSearchDialog.Services() {
            @Override public boolean isConfigured() { return true; }
            @Override public void search(String query, int position, Object tag, AssrtApi.SearchListener listener) {
                client.search(query, position, tag, new AssrtApi.SearchListener() {
                    @Override public void onSuccess(List<AssrtSubtitle> items) { listener.onSuccess(items); }
                    @Override public void onError(Exception error) { logFailure("search", error); listener.onError(error); }
                });
            }
            @Override public void detail(long id, Object tag, AssrtApi.DetailListener listener) {
                client.detail(id, tag, new AssrtApi.DetailListener() {
                    @Override public void onSuccess(AssrtSubtitle item) { listener.onSuccess(item); }
                    @Override public void onError(Exception error) { logFailure("detail", error); listener.onError(error); }
                });
            }
            @Override public void download(AssrtSubtitleFile file, Object tag, SubtitleDownload.Listener listener) {
                SubtitleDownload.download(file, tag, new SubtitleDownload.Listener() {
                    @Override public void onSuccess(List<Sub> files) { listener.onSuccess(files); }
                    @Override public void onError(Exception error) { logFailure("download", error); listener.onError(error); }
                });
            }
            @Override public void cancel(Object tag) { client.cancel(tag); SubtitleDownload.cancel(tag); }
        };
    }

    @After
    public void cleanup() throws Exception {
        main(() -> { if (activity != null) activity.finish(); });
        instrumentation.waitForIdleSync();
        main(() -> {
            activity = null;
            Track.delete(AssrtSubtitlePreviewActivity.KEY);
            Track.delete(AssrtSubtitlePreviewActivity.KEY + "|secondary");
            for (String key : SETTINGS.keySet()) {
                if (saved.containsKey(key)) Prefers.put(key, saved.get(key));
                else Prefers.getPrefers().edit().remove(key).apply();
            }
        });
        if (server != null) server.close();
    }

    @Test
    public void shouldSearchFromTrackMenuAndLoadOnlyTheChosenFileWithoutResumingPlayback() throws Exception {
        launch(false);
        main(() -> activity.openTracks(services));
        await(() -> ready(trackDialog(), R.id.search), "online subtitle track action owns input focus");
        main(() -> assertTrue(view(trackDialog(), R.id.search).requestFocus()));
        await(() -> view(trackDialog(), R.id.search).hasFocus(), "online subtitle track action focused");
        NativeUiEvidence.capture("assrt-track-search-entry");
        device.pressDPadCenter();
        awaitSearch();
        main(() -> assertEquals(AssrtSubtitlePreviewActivity.TITLE + " S02E03", text(R.id.keyword)));
        assertTrue("Showing search must not consume API quota", requests.isEmpty());
        startSearch();
        awaitRow(RECORD_TITLE);
        NativeUiEvidence.capture("assrt-search-results");
        chooseRow(RECORD_TITLE);
        awaitRow(WANTED);
        main(() -> {
            assertFalse(activity.manager.haveTrack(C.TRACK_TYPE_TEXT));
            assertEquals(2, recycler().getAdapter().getItemCount());
        });
        assertEquals("A result selection does not silently choose another episode", 0, downloadCount());
        NativeUiEvidence.capture("assrt-subtitle-files");
        chooseRow(WANTED);
        await(() -> searchDialog() == null && selectedText(WANTED), "chosen ASSRT track loaded and selected");
        assertLoadedState();
        assertAuthenticationSeparated();
        assertEquals(1, downloadCount());
        assertTrue(requests.stream().filter(request -> request.getRequestUrl().encodedPath().startsWith("/download/"))
                .allMatch(request -> request.getRequestUrl().encodedPath().equals("/download/wanted.srt")));
        NativeUiEvidence.capture("assrt-loaded-primary");
    }

    @Test
    public void shouldKeepAnExistingSecondarySelectionWhileAddingThePrimarySubtitle() throws Exception {
        launch(true);
        await(() -> format(AssrtSubtitlePreviewActivity.EXISTING_NAME) != null, "existing subtitle track");
        String secondary = read(() -> PlayerHelper.describeFormat(format(AssrtSubtitlePreviewActivity.EXISTING_NAME)));
        main(() -> {
            Track track = new Track(C.TRACK_TYPE_TEXT, AssrtSubtitlePreviewActivity.EXISTING_NAME, secondary);
            track.setSelected(true);
            activity.manager.setSecondaryTrack(track);
            assertEquals(secondary, activity.manager.getSecondaryTrack());
        });
        main(() -> activity.openSearch(services));
        awaitSearch();
        startSearch();
        awaitRow(RECORD_TITLE);
        chooseRow(RECORD_TITLE);
        awaitRow(WANTED);
        chooseRow(WANTED);
        await(() -> downloadCount() == 1, "selected primary subtitle download started");
        assertTrue("The primary selection downloads the chosen S02E03 file", requests.stream()
                .filter(request -> request.getRequestUrl().encodedPath().startsWith("/download/"))
                .allMatch(request -> request.getRequestUrl().encodedPath().equals("/download/wanted.srt")));
        await(() -> searchDialog() == null && selectedText(WANTED), "new primary subtitle selected");
        main(() -> {
            assertNotNull(format(AssrtSubtitlePreviewActivity.EXISTING_NAME));
            assertEquals("Online search must keep the same secondary subtitle even if a source prefix changes",
                    PlayerHelper.describeFormat(format(AssrtSubtitlePreviewActivity.EXISTING_NAME)), activity.manager.getSecondaryTrack());
        });
        await(() -> hasSecondaryCue("Existing English subtitle"), "existing secondary subtitle still produces cues");
        assertLoadedState();
    }

    @Test
    public void shouldOfferExtractedZipFilesBeforeLoadingOne() throws Exception {
        zip = true;
        launch(false);
        main(() -> activity.openSearch(services));
        awaitSearch();
        startSearch();
        awaitRow(RECORD_TITLE);
        chooseRow(RECORD_TITLE);
        awaitRow("Fixture.Subtitles.zip");
        chooseRow("Fixture.Subtitles.zip");
        awaitRow(WANTED);
        main(() -> {
            assertFalse(activity.manager.haveTrack(C.TRACK_TYPE_TEXT));
            assertEquals(2, recycler().getAdapter().getItemCount());
        });
        assertEquals(1, downloadCount());
        NativeUiEvidence.capture("assrt-extracted-files");
        chooseRow(WANTED);
        await(() -> searchDialog() == null && selectedText(WANTED), "chosen ZIP subtitle selected");
        assertLoadedState();
        assertAuthenticationSeparated();
        assertEquals("ZIP entries do not require another network download", 1, downloadCount());
    }

    @Test
    public void shouldReadTheOriginalRarPackageAndLetTheUserChooseItsSubtitle() throws Exception {
        rar = true;
        openArchiveFile("Fixture.Subtitles.rar");
        awaitRow(WANTED);
        main(() -> {
            assertFalse(activity.manager.haveTrack(C.TRACK_TYPE_TEXT));
            assertEquals("Both native RAR entries are offered", 2, recycler().getAdapter().getItemCount());
        });
        NativeUiEvidence.capture("assrt-rar-files");
        chooseRow(WANTED);
        await(() -> searchDialog() == null && selectedText(WANTED), "chosen RAR subtitle loaded");
        assertLoadedState();
        assertAuthenticationSeparated();
        assertEquals("The original RAR is downloaded once", 1, downloadCount());
    }

    @Test
    public void shouldRejectRarPathTraversalInsteadOfLoadingItsSubtitle() throws Exception {
        rar = true;
        rarTraversal = true;
        openArchiveFile("Fixture.Subtitles.rar");
        await(() -> text(R.id.status).equals(activity.getString(R.string.assrt_download_error)), "RAR traversal rejected");
        main(() -> {
            assertFalse(activity.manager.haveTrack(C.TRACK_TYPE_TEXT));
            assertFalse(activity.manager.isPlaying());
        });
        assertAuthenticationSeparated();
        assertEquals(1, downloadCount());
    }

    @Test
    public void shouldRejectAnEncryptedRarPackageInsteadOfLoadingItsSubtitle() throws Exception {
        rar = true;
        rarEncrypted = true;
        openArchiveFile("Fixture.Subtitles.rar");
        await(() -> text(R.id.status).equals(activity.getString(R.string.assrt_download_error)), "encrypted RAR rejected");
        main(() -> {
            assertFalse(activity.manager.haveTrack(C.TRACK_TYPE_TEXT));
            assertFalse(activity.manager.isPlaying());
        });
        assertAuthenticationSeparated();
        assertEquals(1, downloadCount());
    }

    @Test
    public void shouldDeleteUnselectedArchiveFilesWhenClosingThePicker() throws Exception {
        zip = true;
        openArchiveFile("Fixture.Subtitles.zip");
        awaitRow(WANTED);
        List<File> pending = read(() -> {
            try {
                @SuppressWarnings("unchecked") List<Sub> files = (List<Sub>) field(searchDialog(), "downloaded");
                assertEquals(2, files.size());
                return files.stream().map(file -> new File(file.getUri().getPath())).toList();
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        assertTrue(pending.stream().allMatch(File::isFile));
        main(() -> searchDialog().dismiss());
        await(() -> searchDialog() == null && pending.stream().noneMatch(File::exists), "unused extracted subtitle files removed");
        main(() -> {
            assertFalse(activity.manager.haveTrack(C.TRACK_TYPE_TEXT));
            assertFalse(activity.manager.isPlaying());
        });
        assertFalse("The unselected temporary extraction directory is removed", pending.get(0).getParentFile().exists());
    }

    @Test
    public void shouldShowAnApiErrorWithoutAddingOrStartingPlayback() throws Exception {
        searchError = true;
        launch(false);
        main(() -> activity.openSearch(services));
        awaitSearch();
        startSearch();
        await(() -> text(R.id.status).equals(activity.getString(R.string.assrt_search_error)), "ASSRT query error");
        main(() -> {
            assertFalse(activity.manager.haveTrack(C.TRACK_TYPE_TEXT));
            assertFalse(activity.manager.isPlaying());
            assertEquals(1.25f, activity.manager.getSpeed(), 0.001f);
        });
        assertEquals(0, downloadCount());
        assertEquals(1, requests.size());
        NativeUiEvidence.capture("assrt-search-error");
    }

    @Test
    public void shouldDiscardDelayedSearchAfterClosingTheDialog() throws Exception {
        searchDelay = 1200;
        launch(false);
        main(() -> activity.openSearch(services));
        awaitSearch();
        startSearch();
        await(() -> !requests.isEmpty(), "slow search started");
        main(() -> searchDialog().dismiss());
        await(() -> searchDialog() == null, "search closed");
        SystemClock.sleep(1500);
        main(() -> {
            assertNull(searchDialog());
            assertFalse(activity.manager.haveTrack(C.TRACK_TYPE_TEXT));
            assertFalse(activity.manager.isPlaying());
        });
        assertEquals(0, downloadCount());
    }

    @Test
    public void shouldDiscardDownloadedSubtitleWhenTheCurrentEpisodeChanges() throws Exception {
        downloadDelay = 900;
        launch(false);
        main(() -> activity.openSearch(services));
        awaitSearch();
        startSearch();
        awaitRow(RECORD_TITLE);
        chooseRow(RECORD_TITLE);
        awaitRow(WANTED);
        chooseRow(WANTED);
        await(() -> downloadCount() == 1, "slow subtitle download started");
        main(() -> activity.manager.setMetadata(PlayerManager.buildMetadata("另一部作品", "S01E01", "")));
        await(() -> text(R.id.status).equals(activity.getString(R.string.assrt_media_changed)), "episode change rejected old download");
        main(() -> {
            assertFalse(activity.manager.haveTrack(C.TRACK_TYPE_TEXT));
            assertFalse(view(searchDialog(), R.id.search).isEnabled());
            assertFalse(activity.manager.isPlaying());
        });
        assertEquals(1, downloadCount());
    }

    private void launch(boolean existing) {
        Intent intent = new Intent(instrumentation.getTargetContext(), AssrtSubtitlePreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("existing_subtitle", existing);
        activity = (AssrtSubtitlePreviewActivity) instrumentation.startActivitySync(intent);
        try {
            await(() -> activity.manager != null && activity.manager.getPlaybackState() == Player.STATE_READY
                    && activity.manager.isVod(), "local PCM VOD ready", 30_000);
        } catch (AssertionError failure) {
            throw new AssertionError("local PCM VOD ready: " + read(() -> "state="
                    + (activity.manager == null ? "no manager" : activity.manager.getPlaybackState())
                    + ", error=" + safeMessage(activity.error)), failure);
        }
        main(() -> {
            assertNull(activity.error);
            activity.manager.pause();
            activity.manager.getPlayer().seekTo(20_000);
            activity.manager.setSpeed(1.25f);
        });
        await(() -> activity.manager.getPlaybackState() == Player.STATE_READY && activity.manager.getPosition() >= 19_950,
                "paused fixture position ready");
    }

    private void assertLoadedState() {
        main(() -> {
            assertNull(activity.error);
            assertFalse("Loading a searched subtitle preserves pause", activity.manager.isPlaying());
            assertFalse(activity.manager.getPlayer().getPlayWhenReady());
            assertEquals(1.25f, activity.manager.getSpeed(), 0.001f);
            assertEquals(20_000, activity.manager.getPosition(), 100);
            assertEquals(AssrtSubtitlePreviewActivity.TITLE, activity.manager.getMetadata().title.toString());
            assertEquals("S02E03", activity.manager.getMetadata().artist.toString());
        });
    }

    private void assertAuthenticationSeparated() {
        assertFalse(requests.isEmpty());
        for (RecordedRequest request : requests) {
            String path = request.getRequestUrl().encodedPath();
            if (path.startsWith("/v1/")) assertEquals("Bearer " + TOKEN, request.getHeader("Authorization"));
            else assertNull("API bearer must not be forwarded to subtitle downloads", request.getHeader("Authorization"));
            assertNull(request.getRequestUrl().queryParameter("token"));
        }
        RecordedRequest search = requests.stream().filter(request -> request.getRequestUrl().encodedPath().equals("/v1/sub/search"))
                .findFirst().orElseThrow();
        assertTrue(search.getRequestUrl().queryParameter("q").contains("S02E03"));
    }

    private void startSearch() {
        awaitSearch();
        main(() -> assertTrue(view(searchDialog(), R.id.search).requestFocus()));
        await(() -> view(searchDialog(), R.id.search).hasFocus(), "subtitle search action focused");
        device.pressDPadCenter();
    }

    private void openArchiveFile(String name) {
        launch(false);
        main(() -> activity.openSearch(services));
        awaitSearch();
        startSearch();
        awaitRow(RECORD_TITLE);
        chooseRow(RECORD_TITLE);
        awaitRow(name);
        assertEquals("The archive is not fetched before its explicit selection", 0, downloadCount());
        chooseRow(name);
    }

    private void awaitSearch() { await(() -> ready(searchDialog(), R.id.search), "search dialog owns input focus"); }
    private void awaitRow(String name) {
        await(() -> ready(searchDialog(), R.id.recycler) && recycler().hasFocus()
                        && row(name) != null && row(name).isLaidOut(),
                "subtitle row: " + name);
    }
    private void chooseRow(String name) {
        awaitRow(name);
        main(() -> assertTrue(row(name).requestFocus()));
        await(() -> row(name) != null && row(name).hasFocus(), "subtitle row focused: " + name);
        device.pressDPadCenter();
    }

    private View row(String name) {
        if (searchDialog() == null) return null;
        RecyclerView list = recycler();
        if (list == null) return null;
        for (int index = 0; index < list.getChildCount(); index++) {
            View child = list.getChildAt(index);
            if (child instanceof TextView && ((TextView) child).getText().toString().contains(name)) return child;
        }
        return null;
    }

    private RecyclerView recycler() { return (RecyclerView) view(searchDialog(), R.id.recycler); }
    private String text(int id) {
        View field = view(searchDialog(), id);
        return field instanceof TextView ? ((TextView) field).getText().toString() : "";
    }

    private TrackDialog trackDialog() {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof TrackDialog) return (TrackDialog) fragment;
        return null;
    }

    private SubtitleSearchDialog searchDialog() {
        Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag("subtitle-search");
        return fragment instanceof SubtitleSearchDialog ? (SubtitleSearchDialog) fragment : null;
    }

    private static View view(DialogFragment fragment, int id) {
        if (fragment == null || fragment.getDialog() == null || fragment.getDialog().getWindow() == null) return null;
        return fragment.getDialog().getWindow().getDecorView().findViewById(id);
    }

    private static boolean ready(DialogFragment fragment, int id) {
        View field = view(fragment, id);
        return field != null && field.isShown() && field.isLaidOut()
                && fragment.getDialog().getWindow().getDecorView().hasWindowFocus();
    }

    private Format format(String label) {
        for (Tracks.Group group : activity.manager.getCurrentTracks().getGroups()) if (group.getType() == C.TRACK_TYPE_TEXT) {
            for (int index = 0; index < group.length; index++) if (label.equals(group.getTrackFormat(index).label)) return group.getTrackFormat(index);
        }
        return null;
    }

    private boolean selectedText(String label) {
        if (activity.manager.getPlaybackState() != Player.STATE_READY) return false;
        for (Tracks.Group group : activity.manager.getCurrentTracks().getGroups()) if (group.getType() == C.TRACK_TYPE_TEXT) {
            for (int index = 0; index < group.length; index++) {
                if (label.equals(group.getTrackFormat(index).label) && group.isTrackSelected(index)) return true;
            }
        }
        return false;
    }

    private boolean hasSecondaryCue(String text) {
        try {
            Object engine = field(activity.manager, "engine");
            Object controller = field(engine, "subtitles");
            CueGroup cues = (CueGroup) field(controller, "secondaryCues");
            return cues.cues.stream().anyMatch(cue -> cue.text != null && cue.text.toString().contains(text));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private int downloadCount() {
        return (int) requests.stream().filter(request -> request.getRequestUrl().encodedPath().startsWith("/download/")).count();
    }

    private String searchResponse() {
        JsonObject item = record();
        JsonArray files = new JsonArray();
        files.add(file(rar ? "Fixture.Subtitles.rar" : zip ? "Fixture.Subtitles.zip" : WANTED, "", "1KB"));
        item.add("filelist", files);
        return envelope(item);
    }

    private String detailResponse() {
        JsonObject item = record();
        JsonArray files = new JsonArray();
        if (rar) {
            // ASSRT exposes the original archive alongside (or instead of) on-the-fly files.
            item.addProperty("filename", "Fixture.Subtitles.rar");
            item.addProperty("url", url("/download/subtitles.rar"));
        } else if (zip) files.add(file("Fixture.Subtitles.zip", url("/download/subtitles.zip"), "1KB"));
        else {
            files.add(file(OTHER, url("/download/other.srt"), "1KB"));
            files.add(file(WANTED, url("/download/wanted.srt"), "1KB"));
        }
        item.add("filelist", files);
        return envelope(item);
    }

    private static JsonObject record() {
        JsonObject item = new JsonObject();
        item.addProperty("id", 201);
        item.addProperty("native_name", RECORD_TITLE);
        item.addProperty("videoname", "Fixture.Show.S02.1080p.WEB-DL");
        item.addProperty("subtype", "Subrip(srt)");
        item.addProperty("upload_time", "2026-11-06 00:00:00");
        JsonObject language = new JsonObject();
        language.addProperty("desc", "简体中文");
        item.add("lang", language);
        return item;
    }

    private static JsonObject file(String name, String url, String size) {
        JsonObject file = new JsonObject();
        file.addProperty("f", name);
        if (!url.isEmpty()) file.addProperty("url", url);
        file.addProperty("s", size);
        return file;
    }

    private static String envelope(JsonObject item) {
        JsonArray items = new JsonArray();
        items.add(item);
        JsonObject sub = new JsonObject();
        sub.add("subs", items);
        JsonObject response = new JsonObject();
        response.addProperty("status", 0);
        response.add("sub", sub);
        return response.toString();
    }

    private static MockResponse json(String text) { return new MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(text); }
    private static String subtitle(String text) { return "1\n00:00:00,000 --> 00:01:30,000\n" + text + "\n"; }
    private static byte[] archive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream stream = new ZipOutputStream(bytes)) {
            for (String name : new String[]{OTHER, WANTED}) {
                stream.putNextEntry(new ZipEntry(name));
                stream.write(subtitle(name.equals(WANTED) ? CAPTION : "Wrong episode must not auto-load").getBytes(StandardCharsets.UTF_8));
                stream.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Minimal stored RAR4; all signatures, header CRCs and file CRCs are generated here. */
    private static byte[] rarArchive(boolean traversal, boolean encrypted) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00});
        rarHeader(bytes, 0x73, encrypted ? 0x0080 : 0, new byte[6]);
        if (traversal || encrypted) {
            rarFile(bytes, traversal ? "../escaped-assrt.srt" : WANTED,
                    subtitle(CAPTION).getBytes(StandardCharsets.UTF_8), encrypted);
        } else {
            rarFile(bytes, OTHER, subtitle("Wrong episode must not auto-load").getBytes(StandardCharsets.UTF_8), false);
            rarFile(bytes, WANTED, subtitle(CAPTION).getBytes(StandardCharsets.UTF_8), false);
        }
        rarHeader(bytes, 0x7b, 0, new byte[0]);
        return bytes.toByteArray();
    }

    private static void rarFile(ByteArrayOutputStream output, String filename, byte[] contents, boolean encrypted) throws Exception {
        byte[] name = filename.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(contents);
        ByteBuffer fields = ByteBuffer.allocate(25 + name.length).order(ByteOrder.LITTLE_ENDIAN);
        fields.putInt(contents.length).putInt(contents.length).put((byte) 3)
                .putInt((int) crc.getValue()).putInt(0x50210000).put((byte) 20).put((byte) 0x30)
                .putShort((short) name.length).putInt(0100644).put(name);
        rarHeader(output, 0x74, 0x8000 | (encrypted ? 0x0004 : 0), fields.array());
        output.write(contents);
    }

    private static void rarHeader(ByteArrayOutputStream output, int type, int flags, byte[] fields) throws Exception {
        ByteBuffer header = ByteBuffer.allocate(5 + fields.length).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) type).putShort((short) flags).putShort((short) (7 + fields.length)).put(fields);
        CRC32 crc = new CRC32();
        crc.update(header.array());
        output.write((int) crc.getValue() & 0xff);
        output.write(((int) crc.getValue() >>> 8) & 0xff);
        output.write(header.array());
    }

    private void main(Runnable task) { instrumentation.runOnMainSync(task); }
    private <T> T read(Supplier<T> task) {
        java.util.concurrent.atomic.AtomicReference<T> value = new java.util.concurrent.atomic.AtomicReference<>();
        main(() -> value.set(task.get()));
        return value.get();
    }
    private String url(String path) {
        // Numeric loopback addresses bypass the app's configured public DoH resolver.
        return server.url(path).newBuilder().host("127.0.0.1").build().toString();
    }

    private static void logFailure(String stage, Exception error) {
        Log.w("AssrtSubtitleTest", stage + " failed: " + error.getClass().getName() + ": " + safeMessage(error.getMessage()));
    }

    private static String safeMessage(String message) {
        return message == null ? "none" : message.replace(TOKEN, "<redacted>")
                .replaceAll("(?i)Bearer\\s+\\S+", "Bearer <redacted>")
                .replaceAll("(?i)https?://\\S+", "<url>");
    }

    private void await(BooleanSupplier condition, String message) { await(condition, message, 12_000); }

    private void await(BooleanSupplier condition, String message, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (read(condition::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail(message);
    }
}
