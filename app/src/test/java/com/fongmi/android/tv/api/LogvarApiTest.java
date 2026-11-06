package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LogvarApiTest {

    @Test
    public void shouldNormalizeLogvarBaseUrl() {
        assertEquals("http://host:9321/87654321/api/v2", LogvarApi.normalizeBase("http://host:9321/87654321"));
        assertEquals("http://host:9321/87654321/api/v2", LogvarApi.normalizeBase("http://host:9321/87654321/"));
        assertEquals("http://host:9321/87654321/api/v2", LogvarApi.normalizeBase("http://host:9321/87654321/api/v2"));
        assertEquals("", LogvarApi.normalizeBase(" "));
    }

    @Test
    public void shouldBuildCommentByUrlEndpointWithXmlFormat() {
        String url = LogvarApi.buildCommentByUrlUrl("http://host:9321/87654321", "https://v.qq.com/x/cover/abc.html?vid=def");

        assertTrue(url.startsWith("http://host:9321/87654321/api/v2/comment?"));
        assertTrue(url.contains("url=https%3A%2F%2Fv.qq.com%2Fx%2Fcover%2Fabc.html%3Fvid%3Ddef"));
        assertTrue(url.contains("format=xml"));
    }

    @Test
    public void shouldBuildMatchFileNameFromEpisodeTitle() {
        assertEquals("庆余年 第01集", LogvarApi.buildMatchFileName("庆余年", "第01集"));
        assertEquals("庆余年 EP12", LogvarApi.buildMatchFileName("庆余年", "EP12"));
        assertEquals("庆余年 S02E03", LogvarApi.buildMatchFileName("庆余年 第二季", "第03集"));
        assertEquals("庆余年 预告", LogvarApi.buildMatchFileName("庆余年", "预告"));
    }

    @Test
    public void shouldNormalizeOnlySafeKnownSeasonEpisodes() {
        assertEquals("Show S02E03", LogvarApi.buildMatchFileName("Show", "S02E03"));
        assertEquals("Show S02E12", LogvarApi.buildMatchFileName("Show 第二季", "第十二集"));
        assertEquals("Show S02E12", LogvarApi.buildMatchFileName("Show 第二季", "ＥＰ１２"));
        assertEquals("Show S00E01", LogvarApi.buildMatchFileName("Show", "S00E01"));
        assertEquals("Show S01 S02E03", LogvarApi.buildMatchFileName("Show S01", "S02E03"));
    }

    @Test
    public void shouldKeepReleaseVersionsAndAmbiguousLabelsIntact() {
        String filename = "[494.7MB][ANi] 暗部共生少女 - 04 [1080P][Baha][WEB-DL][AAC AVC].mp4";
        assertEquals("暗部共生少女 " + filename, LogvarApi.buildMatchFileName("暗部共生少女", filename));
        for (String episode : new String[]{"1080p", "03预告", "特别篇 第1集", "第12集上", "第12集下", "第1-2集", "2026-10-05"}) {
            assertEquals("Show " + episode, LogvarApi.buildMatchFileName("Show", episode));
            assertEquals(episode, LogvarApi.buildSearchEpisodeQuery(episode));
        }
    }

    @Test
    public void shouldParseChineseAndCompoundManualEpisodeQueries() {
        assertEquals("12", LogvarApi.buildSearchEpisodeQuery("第十二集"));
        assertEquals("12", LogvarApi.buildSearchEpisodeQuery("ＥＰ１２"));
        assertEquals("3", LogvarApi.buildSearchEpisodeQuery("S02E03"));
    }

    @Test
    public void shouldBuildSearchEpisodesEndpointWithNumericEpisode() {
        String url = LogvarApi.buildSearchEpisodesUrl("http://host:9321/87654321", "庆余年", "第01集");

        assertTrue(url.startsWith("http://host:9321/87654321/api/v2/search/episodes?"));
        assertTrue(url.contains("anime=%E5%BA%86%E4%BD%99%E5%B9%B4"));
        assertTrue(url.contains("episode=1"));
    }

    @Test
    public void shouldMapMatchResponseToCommentXmlSource() throws Exception {
        String json = "{\"success\":true,\"isMatched\":true,\"matches\":[{\"episodeId\":10001,\"animeTitle\":\"生万物\",\"episodeTitle\":\"第1集\"}]}";

        List<Danmaku> items = LogvarApi.parseMatch("http://host:9321/87654321", json);

        assertEquals(1, items.size());
        assertEquals("生万物 第1集", field(items.get(0), "name"));
        assertEquals("http://host:9321/87654321/api/v2/comment/10001?format=xml", field(items.get(0), "url"));
    }

    @Test
    public void shouldRejectExplicitFailedMatches() {
        String match = "\"matches\":[{\"episodeId\":10001,\"animeTitle\":\"Show\",\"episodeTitle\":\"第1集\"}]";
        assertTrue(LogvarApi.parseMatch("http://host", "{\"success\":false," + match + "}").isEmpty());
        assertTrue(LogvarApi.parseMatch("http://host", "{\"isMatched\":false," + match + "}").isEmpty());
        assertTrue(LogvarApi.parseAutoMatch("http://host", "{\"isMatched\":false," + match + "}", "Show", "第1集").isEmpty());
    }

    @Test
    public void shouldKeepMultipleCandidatesForManualChoice() {
        String json = matches("Show", "第1集", 1, "Show", "第1集", 2);
        assertEquals(2, LogvarApi.parseMatch("http://host", json).size());
        assertTrue(LogvarApi.parseAutoMatch("http://host", json, "Show", "第1集").isEmpty());
    }

    @Test
    public void shouldFindUniqueConsistentCandidateInsteadOfFirstMatch() throws Exception {
        String json = matches("Show", "第2集", 2, "Show", "第1集", 1);
        List<Danmaku> items = LogvarApi.parseAutoMatch("http://host", json, "Show", "第1集");
        assertEquals(1, items.size());
        assertEquals("http://host/api/v2/comment/1?format=xml", field(items.get(0), "url"));
    }

    @Test
    public void shouldRequireMatchingKnownSeasonsAndTitles() {
        assertEquals(1, LogvarApi.parseAutoMatch("http://host", matches("Show 第二季", "第3集", 1), "Show 第二季", "第3集").size());
        assertEquals(1, LogvarApi.parseAutoMatch("http://host", matches("Show", "S02E03", 1), "Show", "S02E03").size());
        assertTrue(LogvarApi.parseAutoMatch("http://host", matches("Show", "S01E03", 1), "Show", "S02E03").isEmpty());
        assertEquals(1, LogvarApi.parseAutoMatch("http://host", matches("Show", "第3集", 1), "Show", "S02E03").size());
        assertTrue(LogvarApi.parseAutoMatch("http://host", matches("Another Show", "第3集", 1), "Show", "第3集").isEmpty());
        assertTrue(LogvarApi.parseAutoMatch("http://host", matches("Show3", "第3集", 1), "Show2", "第3集").isEmpty());
        assertTrue(LogvarApi.parseAutoMatch("http://host", matches("Show", "S02E03", 1), "Show S01", "S02E03").isEmpty());
    }

    @Test
    public void shouldNeverAutoBindPartsPreviewsOrQualityNumbers() {
        for (String episode : new String[]{"1080p", "第12集上", "第12集下", "03预告", "特别篇 第1集", "第1-2集"}) {
            assertTrue(LogvarApi.parseAutoMatch("http://host", matches("Show", "第12集", 1), "Show", episode).isEmpty());
        }
    }

    @Test
    public void shouldMapSearchEpisodesResponseToCommentXmlSources() throws Exception {
        String json = "{\"success\":true,\"animes\":[{\"animeTitle\":\"生万物\",\"episodes\":[{\"episodeId\":11,\"episodeTitle\":\"第1集\"},{\"episodeId\":\"12\",\"episodeTitle\":\"第2集\"}]}]}";

        List<Danmaku> items = LogvarApi.parseEpisodes("http://host:9321/87654321/api/v2", json);

        assertEquals(2, items.size());
        assertEquals("生万物 第1集", field(items.get(0), "name"));
        assertEquals("http://host:9321/87654321/api/v2/comment/11?format=xml", field(items.get(0), "url"));
        assertEquals("生万物 第2集", field(items.get(1), "name"));
        assertEquals("http://host:9321/87654321/api/v2/comment/12?format=xml", field(items.get(1), "url"));
    }

    @Test
    public void shouldAcceptUniqueDecoratedSeasonResultForUnseasonedInput() {
        String json = episodes("Show 第二季(2024)【动漫】from bilibili", "【bilibili1】 03", 31);
        assertEquals(1, LogvarApi.parseAutoEpisodes("http://host", json, "Show", "第3集").size());
        assertEquals(1, LogvarApi.parseAutoEpisodes("http://host", episodes("Show(2024)【电视剧】from tencent", "【qq】 第03集", 32), "Show", "EP03").size());
        assertEquals(1, LogvarApi.parseAutoEpisodes("http://host", episodes("【bilibili】Show(N/A)【动漫】from bilibili", "【bilibili1】 03", 33), "Show", "3").size());
    }

    @Test
    public void shouldRequireManualChoiceForMultipleSeasonsOrReleaseYears() {
        String seasons = episodes("Show 第一季(2023)【动漫】from bilibili", "【bilibili1】 03", 31,
                "Show 第二季(2024)【动漫】from bilibili", "【bilibili1】 03", 32);
        assertTrue(LogvarApi.parseAutoEpisodes("http://host", seasons, "Show", "第3集").isEmpty());
        assertEquals(2, LogvarApi.parseEpisodes("http://host", seasons).size());
        String years = episodes("Show(2023)【电视剧】from tencent", "【qq】 第3集", 31,
                "Show(2024)【电视剧】from tencent", "【qq】 第3集", 32);
        assertTrue(LogvarApi.parseAutoEpisodes("http://host", years, "Show", "第3集").isEmpty());
    }

    @Test
    public void shouldRejectKnownReleaseYearConflictsEvenForOneCandidate() {
        assertTrue(LogvarApi.parseAutoEpisodes("http://host", episodes("Show(2025)【电视剧】from tencent", "【qq】 第3集", 31), "Show(2024)", "第3集").isEmpty());
        assertEquals(1, LogvarApi.parseAutoEpisodes("http://host", episodes("Show(2024)【电视剧】from tencent", "【qq】 第3集", 31), "Show(2024)", "第3集").size());
        assertTrue(LogvarApi.parseAutoMatch("http://host", matches("Show(2025)【电视剧】from tencent", "【qq】 第3集", 31), "Show(2024)", "S01E03").isEmpty());
    }

    @Test
    public void shouldPreserveRealBracketedTitlesAndTheirNumbers() {
        assertEquals(1, LogvarApi.parseAutoEpisodes("http://host", episodes("【我推的孩子】(2024)【动漫】from bilibili", "【bilibili1】 第3集", 31), "【我推的孩子】", "第3集").size());
        assertTrue(LogvarApi.parseAutoEpisodes("http://host", episodes("Show3(2024)【动漫】from bilibili", "【bilibili1】 第3集", 31), "Show2", "第3集").isEmpty());
    }

    @Test
    public void shouldCheckEpisodeLabelsInsteadOfServerListIndices() {
        String wrongNumber = "{\"success\":true,\"animes\":[{\"animeTitle\":\"Show(2024)【动漫】from bilibili\",\"episodes\":[{\"episodeId\":31,\"episodeNumber\":3,\"episodeTitle\":\"【bilibili1】 第2集\"}]}]}";
        assertTrue(LogvarApi.parseAutoEpisodes("http://host", wrongNumber, "Show", "第3集").isEmpty());
        for (String title : new String[]{"【qq】 风暴来袭", "【qq】 第3集上", "【qq】 03预告", "【qq】 特典 第3集"}) {
            assertTrue(LogvarApi.parseAutoEpisodes("http://host", episodes("Show(2024)【动漫】from bilibili", title, 31), "Show", "第3集").isEmpty());
        }
    }

    @Test
    public void shouldAcceptConfirmedMatchWithUndecoratedFirstSeasonTitle() {
        assertEquals(1, LogvarApi.parseAutoMatch("http://host", matches("Show(2024)【电视剧】from tencent", "【qq】 第3集", 31), "Show", "S01E03").size());
        assertTrue(LogvarApi.parseAutoMatch("http://host", matches("Show 第二季(2024)【电视剧】from tencent", "【qq】 第3集", 31), "Show", "S01E03").isEmpty());
    }

    @Test
    public void shouldRouteUnseasonedEpisodesToSearchWithoutInventingSeasonOne() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(episodes("Show 第二季(2024)【动漫】from bilibili&tencent", "【bilibili1&qq】 03", 31)));
            assertEquals(1, auto(server, "Show", "第3集", "", true).size());
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("GET", request.getMethod());
            assertEquals("/api/v2/search/episodes", request.getRequestUrl().encodedPath());
            assertEquals("Show", request.getRequestUrl().queryParameter("anime"));
            assertEquals("3", request.getRequestUrl().queryParameter("episode"));
            assertNull(request.getRequestUrl().queryParameter("season"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldPostKnownSeasonBeforePreservedReleaseMetadata() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(matches("Show(2024)【电视剧】from tencent", "【qq】 第3集", 31)));
            String filename = "[ANi] Show - 03 [1080p][WEB-DL].mkv";
            assertEquals(1, auto(server, "Show 第二季(2024)", filename, "", true).size());
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("POST", request.getMethod());
            assertEquals("/api/v2/match", request.getRequestUrl().encodedPath());
            String body = request.getBody().readUtf8();
            assertTrue(body.contains("Show S02E03 (2024)"));
            assertTrue(body.contains(filename));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldTryVideoUrlBeforeUnseasonedEpisodeSearch() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("<i/>"));
            server.enqueue(new MockResponse().setBody(episodes("Show(2024)【电视剧】from tencent", "【qq】 第3集", 31)));
            assertEquals(1, auto(server, "Show", "第3集", "https://example.invalid/video/3", true).size());
            assertEquals("/api/v2/comment", server.takeRequest(1, TimeUnit.SECONDS).getRequestUrl().encodedPath());
            assertEquals("/api/v2/search/episodes", server.takeRequest(1, TimeUnit.SECONDS).getRequestUrl().encodedPath());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    public void shouldAvoidGuessedEpisodeRequestsAfterGeneratedUrlFails() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            assertTrue(auto(server, "Show", "03", "https://example.invalid/video/3", false).isEmpty());
            assertEquals("/api/v2/comment", server.takeRequest(1, TimeUnit.SECONDS).getRequestUrl().encodedPath());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldTreatXmlWithoutItemsAsEmpty() {
        assertTrue(LogvarApi.hasDanmakuXml("<i><d p=\"1,1,25,16777215,0,0,0,1\">test</d></i>"));
        assertEquals(0, LogvarApi.parseMatch("http://host", "{\"success\":true,\"matches\":[]}").size());
        assertEquals(0, LogvarApi.parseEpisodes("http://host", "{\"success\":true,\"animes\":[]}").size());
    }

    private String field(Danmaku danmaku, String name) throws Exception {
        Field field = Danmaku.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(danmaku);
    }

    private String matches(Object... fields) {
        StringBuilder json = new StringBuilder("{\"success\":true,\"isMatched\":true,\"matches\":[");
        for (int i = 0; i < fields.length; i += 3) {
            if (i > 0) json.append(',');
            json.append("{\"animeTitle\":\"").append(fields[i]).append("\",\"episodeTitle\":\"").append(fields[i + 1])
                    .append("\",\"episodeId\":").append(fields[i + 2]).append('}');
        }
        return json.append("]}").toString();
    }

    private String episodes(Object... fields) {
        StringBuilder json = new StringBuilder("{\"success\":true,\"animes\":[");
        for (int i = 0; i < fields.length; i += 3) {
            if (i > 0) json.append(',');
            json.append("{\"animeTitle\":\"").append(fields[i]).append("\",\"episodes\":[{\"episodeTitle\":\"").append(fields[i + 1])
                    .append("\",\"episodeId\":").append(fields[i + 2]).append("}]}");
        }
        return json.append("]}").toString();
    }

    private List<Danmaku> auto(MockWebServer server, String name, String episode, String videoUrl, boolean reliable) throws Exception {
        OkHttpClient client = new OkHttpClient();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<Danmaku>> items = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        try {
            LogvarApi.searchAuto(server.url("/").toString(), name, episode, videoUrl, reliable, client::newCall, Runnable::run, new LogvarApi.Listener() {
                @Override public void onSuccess(List<Danmaku> found) { items.set(found); done.countDown(); }
                @Override public void onError(Exception failure) { error.set(failure); done.countDown(); }
            });
            assertTrue("Request did not finish", done.await(3, TimeUnit.SECONDS));
            assertNull(error.get());
            return items.get();
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }
}
