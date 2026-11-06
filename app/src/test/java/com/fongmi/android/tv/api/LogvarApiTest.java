package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Danmaku;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
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
        assertEquals("庆余年 第二季 S02E03", LogvarApi.buildMatchFileName("庆余年 第二季", "第03集"));
        assertEquals("庆余年 预告", LogvarApi.buildMatchFileName("庆余年", "预告"));
    }

    @Test
    public void shouldNormalizeOnlySafeKnownSeasonEpisodes() {
        assertEquals("Show S02E03", LogvarApi.buildMatchFileName("Show", "S02E03"));
        assertEquals("Show 第二季 S02E12", LogvarApi.buildMatchFileName("Show 第二季", "第十二集"));
        assertEquals("Show 第二季 S02E12", LogvarApi.buildMatchFileName("Show 第二季", "ＥＰ１２"));
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
        assertTrue(LogvarApi.parseAutoMatch("http://host", matches("Show", "第3集", 1), "Show", "S02E03").isEmpty());
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
}
