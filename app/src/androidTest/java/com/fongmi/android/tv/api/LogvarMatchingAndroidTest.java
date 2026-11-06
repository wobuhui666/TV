package com.fongmi.android.tv.api;

import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the public API, Android preferences and main-thread callbacks using loopback HTTP. */
@RunWith(AndroidJUnit4.class)
public class LogvarMatchingAndroidTest {

    private static final String PREF_KEY = "danmaku_logvar_url";
    private final ConcurrentLinkedQueue<MockResponse> replies = new ConcurrentLinkedQueue<>();
    private MockWebServer server;
    private String base;
    private boolean hadOverride;
    private Object savedOverride;

    @Before
    public void setup() throws Exception {
        String applicationId = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        assertTrue("Use a disposable validation application", applicationId.equals("com.fongmi.android.tv.preview")
                || applicationId.equals("com.fongmi.android.tv.sourceprobe"));
        Map<String, ?> preferences = Prefers.getPrefers().getAll();
        hadOverride = preferences.containsKey(PREF_KEY);
        savedOverride = preferences.get(PREF_KEY);
        LogvarApi.cancel();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                MockResponse response = replies.poll();
                return response == null ? new MockResponse().setResponseCode(404).setBody("Unexpected fixture request") : response;
            }
        });
        server.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
        base = server.url("/bridge").newBuilder().host("127.0.0.1").build().toString();
        DanmakuSetting.putLogvarUrl(base);
    }

    @After
    public void cleanup() throws Exception {
        try {
            LogvarApi.cancel();
            if (server != null) server.shutdown();
        } finally {
            if (hadOverride) Prefers.put(PREF_KEY, savedOverride);
            else Prefers.getPrefers().edit().remove(PREF_KEY).apply();
        }
    }

    @Test
    public void unknownSeasonShouldSearchEpisodesAndAcceptOneDecoratedSeason() throws Exception {
        respondJson(episodes("剧名 第二季(2024)【动漫】from bilibili&tencent", "【bilibili1&qq】 03", "203"));
        List<Danmaku> items = search("剧名", "第3集", null, true);

        assertEquals(1, items.size());
        assertEquals(base + "/api/v2/comment/203?format=xml", items.get(0).getUrl());
        RecordedRequest request = takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/bridge/api/v2/search/episodes", request.getRequestUrl().encodedPath());
        assertEquals("剧名", request.getRequestUrl().queryParameter("anime"));
        assertEquals("3", request.getRequestUrl().queryParameter("episode"));
        assertFalse(request.getPath().contains("S01"));
        assertNoMoreRequests();
    }

    @Test
    public void emptyUrlXmlShouldFallBackToEpisodesSearchWithChineseNumber() throws Exception {
        respondXml("<i></i>");
        respondJson(episodes("剧名(2024)【动漫】from bilibili", "【bilibili1】 12", "112"));
        Result video = video();
        List<Danmaku> items = search("剧名", "第十二集", video, true);

        assertEquals(1, items.size());
        assertCommentRequest(takeRequest(), video.getUrl().v());
        RecordedRequest fallback = takeRequest();
        assertEquals("GET", fallback.getMethod());
        assertEquals("/bridge/api/v2/search/episodes", fallback.getRequestUrl().encodedPath());
        assertEquals("12", fallback.getRequestUrl().queryParameter("episode"));
        assertNoMoreRequests();
    }

    @Test
    public void unknownSeasonShouldRejectAmbiguousFirstAndSecondSeasonCandidates() throws Exception {
        respondJson(episodes("剧名 第一季(2024)【电视剧】from tencent", "【qq】 第03集", "103",
                "剧名 第二季(2025)【动漫】from bilibili", "【bilibili1】 03", "203"));
        assertTrue(search("剧名", "第3集", null, true).isEmpty());
        RecordedRequest request = takeRequest();
        assertEquals("/bridge/api/v2/search/episodes", request.getRequestUrl().encodedPath());
        assertEquals("3", request.getRequestUrl().queryParameter("episode"));
        assertNoMoreRequests();
    }

    @Test
    public void knownSeasonShouldSendCanonicalCoordinatesAndPreserveTheReleaseLabel() throws Exception {
        String label = "[494.7MB][ANi] 剧名 - 03 [1080P][Baha][WEB-DL][AAC AVC].mp4";
        respondJson(matches("剧名 第二季(2024)【电视剧】from tencent", "【qq】 第03集", "203"));
        List<Danmaku> items = search("剧名 第二季", label, null, true);

        assertEquals(1, items.size());
        RecordedRequest request = takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/bridge/api/v2/match", request.getRequestUrl().encodedPath());
        String filename = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().get("fileName").getAsString();
        assertTrue(filename.startsWith("剧名 S02E03"));
        assertTrue(filename.contains(label));
        assertFalse(filename.startsWith("剧名 第二季"));
        assertNoMoreRequests();
    }

    @Test
    public void failedUrlLookupShouldUseKnownSeasonMatch() throws Exception {
        replies.add(new MockResponse().setResponseCode(503).setBody("Fixture URL unavailable"));
        respondJson(matches("剧名(2024)【电视剧】from tencent", "【qq】 S02E03", "203"));
        Result video = video();
        assertEquals(1, search("剧名", "S02E03", video, true).size());
        assertCommentRequest(takeRequest(), video.getUrl().v());
        RecordedRequest request = takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/bridge/api/v2/match", request.getRequestUrl().encodedPath());
        assertEquals("剧名 S02E03", JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().get("fileName").getAsString());
        assertNoMoreRequests();
    }

    @Test
    public void successfulUrlLookupShouldWorkWithoutReliableEpisodeNumbers() throws Exception {
        respondXml("<i><d p=\"1,1,25,16777215,0,0,0,1\">local fixture</d></i>");
        Result video = video();
        List<Danmaku> items = search("剧名", "03", video, false);
        assertEquals(1, items.size());
        assertCommentRequest(takeRequest(), video.getUrl().v());
        assertEquals(LogvarApi.buildCommentByUrlUrl(base, video.getUrl().v()), items.get(0).getUrl());
        assertNoMoreRequests();
    }

    @Test
    public void generatedLabelsShouldNotStartFilenameOrEpisodesSearchAfterUrlFailure() throws Exception {
        respondXml("<i></i>");
        Result video = video();
        assertTrue(search("剧名", "03", video, false).isEmpty());
        assertCommentRequest(takeRequest(), video.getUrl().v());
        assertNoMoreRequests();
    }

    @Test
    public void unsafeLabelsWithoutAUrlShouldNotMakeIdentityRequests() throws Exception {
        assertTrue(search("剧名", "1080p", null, true).isEmpty());
        assertNoMoreRequests();
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void explicitMatchFailureShouldNotBindReturnedCandidates() throws Exception {
        JsonObject response = JsonParser.parseString(matches("剧名", "S02E03", "203")).getAsJsonObject();
        response.addProperty("isMatched", false);
        respondJson(response.toString());
        assertTrue(search("剧名", "S02E03", null, true).isEmpty());
        assertEquals("/bridge/api/v2/match", takeRequest().getRequestUrl().encodedPath());
        assertNoMoreRequests();
    }

    private List<Danmaku> search(String name, String episode, Result result, boolean reliable) throws Exception {
        assertNotSame("Callbacks require an unblocked main looper", Looper.getMainLooper(), Looper.myLooper());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<List<Danmaku>> items = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        LogvarApi.searchAuto(name, episode, result, reliable, new LogvarApi.Listener() {
            @Override
            public void onSuccess(List<Danmaku> values) {
                items.set(values);
                completed.countDown();
            }

            @Override
            public void onError(Exception exception) {
                error.set(exception);
                completed.countDown();
            }
        });
        assertTrue("Local Logvar callback timed out", completed.await(12, TimeUnit.SECONDS));
        assertNull("Local Logvar request failed", error.get());
        assertNotNull(items.get());
        return items.get();
    }

    private Result video() {
        Result result = new Result();
        result.setUrl(server.url("/watch/3").newBuilder().host("127.0.0.1").build().toString());
        return result;
    }

    private RecordedRequest takeRequest() throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull("Expected local HTTP request", request);
        assertNotNull(request.getRequestUrl());
        return request;
    }

    private void assertCommentRequest(RecordedRequest request, String videoUrl) {
        HttpUrl url = request.getRequestUrl();
        assertEquals("GET", request.getMethod());
        assertEquals("/bridge/api/v2/comment", url.encodedPath());
        assertEquals(videoUrl, url.queryParameter("url"));
        assertEquals("xml", url.queryParameter("format"));
    }

    private void assertNoMoreRequests() throws Exception {
        assertNull("Unexpected extra identity request", server.takeRequest(250, TimeUnit.MILLISECONDS));
    }

    private void respondXml(String body) {
        replies.add(new MockResponse().setHeader("Content-Type", "application/xml").setBody(body));
    }

    private void respondJson(String body) {
        replies.add(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
    }

    private String episodes(String... fields) {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        JsonArray animes = new JsonArray();
        for (int index = 0; index < fields.length; index += 3) {
            JsonObject anime = new JsonObject();
            anime.addProperty("animeTitle", fields[index]);
            JsonObject episode = new JsonObject();
            episode.addProperty("episodeTitle", fields[index + 1]);
            episode.addProperty("episodeId", fields[index + 2]);
            JsonArray values = new JsonArray();
            values.add(episode);
            anime.add("episodes", values);
            animes.add(anime);
        }
        response.add("animes", animes);
        return response.toString();
    }

    private String matches(String title, String episode, String id) {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("isMatched", true);
        JsonObject match = new JsonObject();
        match.addProperty("animeTitle", title);
        match.addProperty("episodeTitle", episode);
        match.addProperty("episodeId", id);
        JsonArray values = new JsonArray();
        values.add(match);
        response.add("matches", values);
        return response.toString();
    }
}
