package com.fongmi.android.tv.player.skip;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EpisodeIdentityResolverTest {

    @Test
    public void shouldParseExplicitSeasonAndEpisodeWithoutUsingListPosition() {
        for (String name : new String[]{"S02E03", "s02.e03", "2x03", "第2季第3集", "第二季第三集"}) {
            EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("Show", name);
            assertNotNull(name, parsed);
            assertEquals(name, Integer.valueOf(2), parsed.season());
            assertEquals(name, 3, parsed.episode());
        }
    }

    @Test
    public void shouldCombineTitleSeasonWithChineseOrNumericEpisodeLabels() {
        for (String name : new String[]{"第十二集", "Episode 12", "12", "十二", "ＥＰ１２"}) {
            EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("剧名 第二季", name);
            assertNotNull(name, parsed);
            assertEquals(Integer.valueOf(2), parsed.season());
            assertEquals(12, parsed.episode());
            assertEquals("剧名", parsed.queryTitle());
        }
    }

    @Test
    public void shouldLeaveSeasonUnknownWhenSourceOnlySaysEpisodeNumber() {
        EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("Show", "第3集");
        assertNotNull(parsed);
        assertNull(parsed.season());
        assertEquals(3, parsed.episode());
    }

    @Test
    public void shouldNotMapVarietyDatesOrIssuesToTmdbEpisodePositions() throws Exception {
        for (String label : new String[]{"20261005", "2026-10-05期", "2026年10月5日", "第3期", "第3期上", "第3期会员版", "S02E03 加更"}) {
            assertNull(label, EpisodeIdentityResolver.parseEpisode("Show 第二季", label));
        }
        try (MockWebServer server = new MockWebServer()) {
            EpisodeIdentityResolver resolver = resolver(server);
            assertNull(resolver.resolve("tmdb:tv:123", "Show 第二季", "2026", "综艺", "第3期"));
            assertNull(resolver.resolve("tmdb:tv:123", "Show", "2026", "综艺", "20261005"));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test
    public void shouldParseMixedReleaseFileLabelsWithoutUsingSizeOrQualityNumbers() {
        String release = "[494.7MB][ANi] 暗部共生少女 - 04 [1080P][Baha][WEB-DL][AAC AVC].mp4";
        EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("暗部共生少女 第二季", release);
        assertNotNull(parsed);
        assertEquals(Integer.valueOf(2), parsed.season());
        assertEquals(4, parsed.episode());
        assertEquals("暗部共生少女", parsed.queryTitle());

        parsed = EpisodeIdentityResolver.parseEpisode("暗部共生少女", "[1.4GB]Toaru.Anbu.no.Item.S01E01.1080p.WEB-DL.x265.mkv");
        assertNotNull(parsed);
        assertEquals(Integer.valueOf(1), parsed.season());
        assertEquals(1, parsed.episode());
    }

    @Test
    public void shouldRejectConflictingSeasonsAndCombinedOrSplitEpisodes() {
        assertNull(EpisodeIdentityResolver.parseEpisode("Show 第二季", "S01E03"));
        assertNull(EpisodeIdentityResolver.parseEpisode("Show S01 第2季", "第3集"));
        for (String name : new String[]{"S01E02E03", "S01E02 S01E03", "S01E02 Episode3", "第01-02集", "第1集-第2集",
                "第十二集上", "第十二集(下)", "第3集上半集", "第十二至十三集", "第十二、十三集", "Episode 3 Part 1",
                "特别篇 第1集", "第1集 SP", "03 预告", "全集", "1080p", "S01E00"}) {
            assertNull(name, EpisodeIdentityResolver.parseEpisode("Show", name));
        }
    }

    @Test
    public void shouldPreserveExplicitSpecialSeasonZero() {
        EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("Show", "S00E01");
        assertNotNull(parsed);
        assertEquals(Integer.valueOf(0), parsed.season());
        assertEquals(Integer.valueOf(0), EpisodeIdentityResolver.parseEpisode("Show", "S00E01 Special").season());
        assertEquals(Integer.valueOf(0), EpisodeIdentityResolver.parseEpisode("Show S00", "第1集 Special").season());
    }

    @Test
    public void shouldOnlyInferSeasonFromOneConfirmedNormalSeason() {
        EpisodeIdentityResolver.ParsedEpisode parsed = EpisodeIdentityResolver.parseEpisode("Show", "第3集");
        assertEquals(new EpisodeIdentity(123, "tt1234567", 1, 3),
                EpisodeIdentityResolver.identityFromDetail(detail("[{\"season_number\":0,\"episode_count\":2},{\"season_number\":1,\"episode_count\":12,\"air_date\":\"2020-01-01\"}]"), 123, parsed, "2020", true));
        assertNull(EpisodeIdentityResolver.identityFromDetail(detail("[{\"season_number\":1,\"episode_count\":12},{\"season_number\":2,\"episode_count\":12}]"), 123, parsed, "", false));
    }

    @Test
    public void shouldRejectWrongSeriesIdOutOfRangeEpisodesAndWrongReleaseYear() {
        JsonObject detail = detail("[{\"season_number\":1,\"episode_count\":2,\"air_date\":\"2020-01-01\"}]");
        EpisodeIdentityResolver.ParsedEpisode episode = EpisodeIdentityResolver.parseEpisode("Show", "S01E03");
        assertNull(EpisodeIdentityResolver.identityFromDetail(detail, 123, episode, "", false));
        episode = EpisodeIdentityResolver.parseEpisode("Show", "S01E02");
        assertNull(EpisodeIdentityResolver.identityFromDetail(detail, 124, episode, "", false));
        assertNull(EpisodeIdentityResolver.identityFromDetail(detail, 123, episode, "2021", true));
    }

    @Test
    public void shouldSelectExactTitleAndYearAndRetainAmbiguousCandidates() {
        JsonObject search = json("{\"results\":[{\"id\":1,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\"},"
                + "{\"id\":2,\"name\":\"Show Returns\",\"first_air_date\":\"2020-01-01\"},"
                + "{\"id\":3,\"name\":\"Show\",\"first_air_date\":\"2021-01-01\"},"
                + "{\"id\":4,\"original_name\":\"Show\",\"first_air_date\":\"2020-01-01\"}]}");
        assertEquals(List.of(1L, 4L), EpisodeIdentityResolver.matchingCandidates(search, "Show", "2020", null));
        assertEquals(List.of(1L, 3L, 4L), EpisodeIdentityResolver.matchingCandidates(search, "Show", "2020", 2));
    }

    @Test
    public void shouldRejectUnknownSeasonWithoutMetadataEvenForKnownTmdbId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            EpisodeIdentityResolver resolver = new EpisodeIdentityResolver("", server.url("/3/").toString(), new OkHttpClient()::newCall);
            assertNull(resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "第3集"));
            assertEquals(new EpisodeIdentity(123, 2, 3), resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "S02E03"));
            assertNull(resolver.resolve("source-id-123", "Show 第二季", "2020", "tv", "第3集"));
            assertEquals(EpisodeIdentity.movie(123, ""), resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "正片"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "上半部"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "下半部分"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "第1部分"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "第1部分 Part 1"));
            assertNull(resolver.resolve("tmdb:movie:123", "Show", "2020", "movie", "预告"));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test
    public void shouldRejectGeneratedSeriesLabelsBeforeMetadataRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            EpisodeIdentityResolver resolver = resolver(server);
            assertNull(resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "01", false));
            assertNull(resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "S02E03", false));
            assertNull(resolver.resolve("source-id-123", "Show 第二季", "2020", "tv", "01", false));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test
    public void shouldResolveGeneratedMovieLabelsWithoutRequiringEpisodeNumbers() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            EpisodeIdentityResolver offline = new EpisodeIdentityResolver("", server.url("/3/").toString(), new OkHttpClient()::newCall);
            assertEquals(EpisodeIdentity.movie(123, ""), offline.resolve("tmdb:movie:123", "Film", "2020", "", "01", false));
            assertEquals(0, server.getRequestCount());

            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":123,\"title\":\"Film\",\"release_date\":\"2020-01-01\"}]}"));
            server.enqueue(new MockResponse().setBody("{\"id\":123,\"title\":\"Film\",\"release_date\":\"2020-01-01\",\"external_ids\":{\"imdb_id\":\"tt1234567\"}}"));
            assertEquals(EpisodeIdentity.movie(123, "tt1234567"), resolver(server).resolve("source-123", "Film", "2020", "movie", "01", false));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    public void shouldDistinguishMovieFromSeriesWithTheSameTmdbNumber() {
        EpisodeIdentity movie = EpisodeIdentity.movie(123, "tt1234567");
        assertTrue(movie.isMovie());
        assertEquals("tmdb:movie:123", movie.key());
        assertEquals("tmdb:tv:123:1:2", new EpisodeIdentity(123, 1, 2).key());
    }

    @Test
    public void shouldResolveKnownMovieAndFetchImdbWithoutGuessingAnEpisode() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"id\":123,\"imdb_id\":\"tt1234567\"}"));
            assertEquals(EpisodeIdentity.movie(123, "tt1234567"), resolver(server).resolve("tmdb:movie:123", "Film", "2020", "movie", "HD"));
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().startsWith("/3/movie/123?"));
        }
    }

    @Test
    public void shouldResolveMovieOnlyWithExactTitleAndReleaseYear() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":123,\"title\":\"Film\",\"release_date\":\"2020-01-01\"},"
                    + "{\"id\":124,\"title\":\"Film\",\"release_date\":\"2021-01-01\"}]}"));
            server.enqueue(new MockResponse().setBody("{\"id\":123,\"title\":\"Film\",\"release_date\":\"2020-01-01\",\"external_ids\":{\"imdb_id\":\"tt1234567\"}}"));
            assertEquals(EpisodeIdentity.movie(123, "tt1234567"), resolver(server).resolve("source-123", "Film", "2020", "movie", "正片"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    public void shouldRejectAmbiguousMovieMatchesWithoutRequestingDetails() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":123,\"title\":\"Film\",\"release_date\":\"2020-01-01\"},"
                    + "{\"id\":124,\"title\":\"Film\",\"release_date\":\"2020-01-01\"}]}"));
            assertNull(resolver(server).resolve("source-123", "Film", "2020", "movie", "正片"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldLoadImdbAndInferSingleSeasonWithOneMetadataRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(detail("[{\"season_number\":1,\"episode_count\":12}]").toString()));
            EpisodeIdentityResolver resolver = resolver(server);
            assertEquals(new EpisodeIdentity(123, "tt1234567", 1, 3), resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "第3集"));
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertTrue(request.getPath().startsWith("/3/tv/123?"));
            assertTrue(request.getPath().contains("append_to_response=external_ids%2Cepisode_groups"));
            assertEquals(new EpisodeIdentity(123, "tt1234567", 1, 4), resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "第4集"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldRejectTwoValidSameTitleAndYearSeries() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":123,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\"},"
                    + "{\"id\":124,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\"}]}"));
            server.enqueue(new MockResponse().setBody(detail("[{\"season_number\":1,\"episode_count\":12}]").toString()));
            JsonObject second = detail("[{\"season_number\":1,\"episode_count\":12}]");
            second.addProperty("id", 124);
            server.enqueue(new MockResponse().setBody(second.toString()));
            assertNull(resolver(server).resolve("source-123", "Show", "2020", "tv", "第3集"));
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test
    public void shouldResolveContinuousAnimeEpisodesThroughCanonicalAbsoluteOrder() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":73223,\"name\":\"黑色五叶草\",\"first_air_date\":\"2017-10-03\"}]}"));
            server.enqueue(new MockResponse().setBody(blackCloverDetail().toString()));
            server.enqueue(new MockResponse().setBody(absoluteGroup().toString()));
            EpisodeIdentityResolver resolver = resolver(server);
            assertEquals(new EpisodeIdentity(73223, "tt7441658", 1, 4),
                    resolver.resolve("source-black-clover", "黑色五叶草", "2017", "动漫", "第4集", true, 171));
            assertEquals(new EpisodeIdentity(73223, "tt7441658", 2, 1),
                    resolver.resolve("source-black-clover", "黑色五叶草", "2017", "动漫", "第171集", true, 171));
            assertEquals(3, server.getRequestCount());
            server.takeRequest(1, TimeUnit.SECONDS);
            RecordedRequest detail = server.takeRequest(1, TimeUnit.SECONDS);
            assertTrue(detail.getPath().contains("append_to_response=external_ids%2Cepisode_groups"));
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS).getPath().startsWith("/3/tv/episode_group/absolute-order?"));
        }
    }

    @Test
    public void shouldUseProxyNormalizedTitleAndRestoreItsEvidenceFromTheMetadataCache() throws Exception {
        String original = "黑色五叶草 (2017) 更新至171集 1080p 国语版";
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":73223,\"name\":\"黑色五叶草\",\"first_air_date\":\"2017-10-03\"}]}")
                    .setHeader("X-Tmdb-Query", encodedTitle("黑色五叶草")).setHeader("X-Title-Normalizer", "rules"));
            server.enqueue(new MockResponse().setBody(blackCloverDetail().toString()));
            server.enqueue(new MockResponse().setBody(absoluteGroup().toString()));
            EpisodeIdentityResolver resolver = resolver(server);
            assertEquals(new EpisodeIdentity(73223, "tt7441658", 1, 4),
                    resolver.resolve("source-black-clover", original, "2017", "动漫", "第4集", true, 171));
            assertEquals(new EpisodeIdentity(73223, "tt7441658", 2, 1),
                    resolver.resolve("source-black-clover", original, "2017", "动漫", "第171集", true, 171));
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test
    public void shouldKeepExplicitSeasonWhenTheProxyRemovesReleaseLabels() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":73223,\"name\":\"黑色五叶草\",\"first_air_date\":\"2017-10-03\"}]}")
                    .setHeader("X-Tmdb-Query", encodedTitle("黑色五叶草")).setHeader("X-Title-Normalizer", "rules"));
            server.enqueue(new MockResponse().setBody(blackCloverDetail().toString()));
            assertEquals(new EpisodeIdentity(73223, "tt7441658", 2, 4),
                    resolver(server).resolve("source-black-clover", "黑色五叶草 第二季 (2026) 1080p 国语版", "2026", "动漫", "第4集", true, 171));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    public void shouldUsePrimarySourceWrapperCleanupWithoutChangingTheExplicitSeason() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":73223,\"name\":\"黑色五叶草\",\"first_air_date\":\"2017-10-03\"}]}")
                    .setHeader("X-Tmdb-Query", encodedTitle("黑色五叶草")).setHeader("X-Title-Normalizer", "primary"));
            server.enqueue(new MockResponse().setBody(blackCloverDetail().toString()));
            assertEquals(new EpisodeIdentity(73223, "tt7441658", 2, 4),
                    resolver(server).resolve("source-black-clover", "[ANi] 黑色五叶草 第二季 (2026) 1080p", "2026", "动漫", "第4集", true, 171));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    public void shouldApplyProxyNormalizationToMoviesWithoutChangingTheReleaseYear() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":123,\"title\":\"终结者2：审判日\",\"release_date\":\"1991-07-03\"}]}")
                    .setHeader("X-Tmdb-Query", encodedTitle("终结者2：审判日")).setHeader("X-Title-Normalizer", "rules"));
            server.enqueue(new MockResponse().setBody("{\"id\":123,\"title\":\"终结者2：审判日\",\"release_date\":\"1991-07-03\",\"external_ids\":{\"imdb_id\":\"tt0103064\"}}"));
            assertEquals(EpisodeIdentity.movie(123, "tt0103064"),
                    resolver(server).resolve("source-123", "终结者2：审判日 (1991) 1080p 国语版", "1991", "电影", "正片"));
        }
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":123,\"title\":\"终结者2：审判日\",\"release_date\":\"1991-07-03\"}]}")
                    .setHeader("X-Tmdb-Query", encodedTitle("终结者2：审判日")).setHeader("X-Title-Normalizer", "rules"));
            assertNull(resolver(server).resolve("source-123", "终结者2：审判日 (1991) 1080p 国语版", "1992", "电影", "正片"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldNotRelaxOriginalTitleMatchingWithoutProxyEvidence() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":73223,\"name\":\"黑色五叶草\",\"first_air_date\":\"2017-10-03\"}]}"));
            assertNull(resolver(server).resolve("source-black-clover", "黑色五叶草 (2017) 1080p 国语版", "2017", "动漫", "第4集", true, 171));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldValidateDeclaredCleanupAndOnlyRemoveExplicitModelSourceWrappers() {
        assertEquals("黑色五叶草", EpisodeIdentityResolver.approvedSearchTitle(
                "黑色五叶草 (2017) 更新至171集 1080p 国语版", encodedTitle("黑色五叶草"), "rules"));
        assertEquals("原声带", EpisodeIdentityResolver.approvedSearchTitle(
                "原声带 第二季 (2023) 1080p", encodedTitle("原声带"), "rules"));
        assertEquals("黑色五叶草", EpisodeIdentityResolver.approvedSearchTitle(
                "[ANi] 黑色五叶草 1080p", encodedTitle("黑色五叶草"), "primary"));
        assertEquals("黑色五叶草", EpisodeIdentityResolver.approvedSearchTitle(
                "[ANi] 黑色五叶草 1080p", encodedTitle("黑色五叶草"), "groq"));
        assertEquals("黑色五叶草", EpisodeIdentityResolver.approvedSearchTitle(
                "【字幕组】黑色五叶草 1080p", encodedTitle("黑色五叶草"), "workers-ai"));
        assertNull(EpisodeIdentityResolver.approvedSearchTitle(
                "[ANi] 黑色五叶草 1080p", encodedTitle("黑色五叶草"), "rules"));
    }

    @Test
    public void shouldRejectTranslatedTruncatedAndSequelChangingProxyTitles() {
        for (String source : new String[]{"rules", "primary", "groq", "workers-ai"}) {
            assertNull(EpisodeIdentityResolver.approvedSearchTitle("黑色五叶草", encodedTitle("黑色"), source));
            assertNull(EpisodeIdentityResolver.approvedSearchTitle("黑色五叶草", encodedTitle("Black Clover"), source));
            assertNull(EpisodeIdentityResolver.approvedSearchTitle("原声带", encodedTitle("带"), source));
            assertNull(EpisodeIdentityResolver.approvedSearchTitle("C++ (2020) 1080p", encodedTitle("C"), source));
            assertNull(EpisodeIdentityResolver.approvedSearchTitle("终结者2：审判日 (1991) 1080p", encodedTitle("终结者：审判日"), source));
            assertNull(EpisodeIdentityResolver.approvedSearchTitle("[ANi] 终结者2：审判日 1080p", encodedTitle("终结者2"), source));
        }
        for (String source : new String[]{"primary", "groq", "workers-ai"}) {
            assertNull(EpisodeIdentityResolver.approvedSearchTitle("[第2版] 黑色五叶草", encodedTitle("黑色五叶草"), source));
        }
    }

    @Test
    public void shouldRejectMalformedControlAndUndeclaredNormalizationHeaders() {
        for (String source : new String[]{"rules", "primary", "groq", "workers-ai"}) {
            for (String header : new String[]{"%GG", "%FF", encodedTitle("黑色五叶草\n"), encodedTitle("黑色五叶草\u202e")}) {
                assertNull(EpisodeIdentityResolver.approvedSearchTitle("黑色五叶草", header, source));
            }
        }
        for (String source : new String[]{"fallback", "none", "untrusted"}) {
            assertNull(EpisodeIdentityResolver.approvedSearchTitle("黑色五叶草 1080p", encodedTitle("黑色五叶草"), source));
        }
    }

    @Test
    public void shouldKeepUnknownSeasonWithoutContinuousPlaylistEvidence() throws Exception {
        for (int count : new int[]{0, 26, 170, 173}) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().setBody(blackCloverDetail().toString()));
                assertNull(resolver(server).resolve("tmdb:tv:73223", "黑色五叶草", "2017", "动漫", "第4集", true, count));
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test
    public void shouldPreserveExplicitSeasonDespiteContinuousPlaylistCount() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(blackCloverDetail().toString()));
            assertEquals(new EpisodeIdentity(73223, "tt7441658", 2, 4),
                    resolver(server).resolve("tmdb:tv:73223", "黑色五叶草", "2017", "动漫", "S02E04", true, 171));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void shouldRejectAmbiguousOrUnsupportedAbsoluteGroupsWithoutFetchingThem() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            JsonObject detail = blackCloverDetail();
            JsonArray groups = detail.getAsJsonObject("episode_groups").getAsJsonArray("results");
            if (variant == 0) groups.add(groups.get(0).deepCopy());
            if (variant == 1) groups.get(0).getAsJsonObject().addProperty("type", 1);
            if (variant == 2) groups.get(0).getAsJsonObject().addProperty("id", "../wrong-series");
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().setBody(detail.toString()));
                assertNull(resolver(server).resolve("tmdb:tv:73223", "黑色五叶草", "2017", "动漫", "第4集", true, 171));
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test
    public void shouldRejectIncompleteDuplicateSpecialOrInvalidAbsoluteMappings() throws Exception {
        for (int variant = 0; variant < 9; variant++) {
            JsonObject mapping = absoluteGroup();
            JsonArray episodes = mapping.getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonArray("episodes");
            switch (variant) {
                case 0 -> episodes.remove(10);
                case 1 -> episodes.get(10).getAsJsonObject().addProperty("order", 9);
                case 2 -> episodes.get(10).getAsJsonObject().addProperty("episode_number", 10);
                case 3 -> episodes.get(10).getAsJsonObject().addProperty("season_number", 0);
                case 4 -> episodes.get(10).getAsJsonObject().addProperty("episode_number", 171);
                case 5 -> mapping.addProperty("id", "wrong-group");
                case 6 -> mapping.addProperty("type", 1);
                case 7 -> mapping.addProperty("episode_count", 171);
                case 8 -> episodes.get(10).getAsJsonObject().addProperty("order", 172);
            }
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().setBody(blackCloverDetail().toString()));
                server.enqueue(new MockResponse().setBody(mapping.toString()));
                assertNull(resolver(server).resolve("tmdb:tv:73223", "黑色五叶草", "2017", "动漫", "第4集", true, 171));
                assertEquals(2, server.getRequestCount());
            }
        }
    }

    @Test
    public void shouldVerifySeriesIdentityBeforeRequestingAbsoluteOrder() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            JsonObject detail = blackCloverDetail();
            switch (variant) {
                case 0 -> detail.addProperty("id", 73224);
                case 1 -> detail.addProperty("name", "另一部动画");
                case 2 -> detail.addProperty("first_air_date", "2020-10-03");
            }
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":73223,\"name\":\"黑色五叶草\",\"first_air_date\":\"2017-10-03\"}]}"));
                server.enqueue(new MockResponse().setBody(detail.toString()));
                assertNull(resolver(server).resolve("source-black-clover", "黑色五叶草", "2017", "动漫", "第4集", true, 171));
                assertEquals(2, server.getRequestCount());
            }
        }
    }

    @Test
    public void shouldShareMetadataDeadlineWithAbsoluteOrderLookup() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"results\":[{\"id\":73223,\"name\":\"黑色五叶草\",\"first_air_date\":\"2017-10-03\"}]}"));
            server.enqueue(new MockResponse().setHeadersDelay(150, TimeUnit.MILLISECONDS).setBody(blackCloverDetail().toString()));
            server.enqueue(new MockResponse().setHeadersDelay(400, TimeUnit.MILLISECONDS).setBody(absoluteGroup().toString()));
            EpisodeIdentityResolver resolver = new EpisodeIdentityResolver("fixture-key", server.url("/3/").toString(), new OkHttpClient()::newCall, 450);
            try {
                resolver.resolve("source-black-clover", "黑色五叶草", "2017", "动漫", "第4集", true, 171);
                fail("The absolute mapping response exceeds the remaining shared lookup budget");
            } catch (IOException expected) {
                assertEquals(3, server.getRequestCount());
            }
        }
    }

    @Test
    public void shouldCancelAnInFlightLookupWithoutPublishingFallbackIdentity() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setBody(detail("[{\"season_number\":1,\"episode_count\":12}]").toString()));
            EpisodeIdentityResolver resolver = resolver(server);
            Future<EpisodeIdentity> result = worker.submit(() -> resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "S01E03"));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            resolver.cancel();
            assertNull(result.get(1, TimeUnit.SECONDS));
            assertNull(resolver.resolve("tmdb:tv:123", "Show", "2020", "tv", "S01E04"));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void shouldShareOneDeadlineAcrossSearchAndDetailRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeadersDelay(150, TimeUnit.MILLISECONDS)
                    .setBody("{\"results\":[{\"id\":123,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\"}]}"));
            server.enqueue(new MockResponse().setHeadersDelay(400, TimeUnit.MILLISECONDS)
                    .setBody(detail("[{\"season_number\":1,\"episode_count\":12}]").toString()));
            EpisodeIdentityResolver resolver = new EpisodeIdentityResolver("fixture-key", server.url("/3/").toString(), new OkHttpClient()::newCall, 450);
            try {
                resolver.resolve("source-123", "Show", "2020", "tv", "第3集");
                fail("The detail response exceeds the remaining shared lookup budget");
            } catch (IOException expected) {
                assertEquals(2, server.getRequestCount());
            }
        }
    }

    private static EpisodeIdentityResolver resolver(MockWebServer server) {
        return new EpisodeIdentityResolver("fixture-key", server.url("/3/").toString(), new OkHttpClient()::newCall);
    }

    private static JsonObject detail(String seasons) {
        return json("{\"id\":123,\"name\":\"Show\",\"first_air_date\":\"2020-01-01\",\"external_ids\":{\"imdb_id\":\"tt1234567\"},\"seasons\":" + seasons + "}");
    }

    private static JsonObject blackCloverDetail() {
        return json("{\"id\":73223,\"name\":\"黑色五叶草\",\"first_air_date\":\"2017-10-03\","
                + "\"external_ids\":{\"imdb_id\":\"tt7441658\"},\"seasons\":[{\"season_number\":0,\"episode_count\":2},"
                + "{\"season_number\":1,\"episode_count\":170,\"air_date\":\"2017-10-03\"},"
                + "{\"season_number\":2,\"episode_count\":26,\"air_date\":\"2026-10-03\"}],"
                + "\"episode_groups\":{\"results\":[{\"id\":\"absolute-order\",\"type\":2,\"episode_count\":172}]}}");
    }

    private static JsonObject absoluteGroup() {
        JsonObject mapping = json("{\"id\":\"absolute-order\",\"type\":2,\"episode_count\":172,\"groups\":[{\"episodes\":[]}]}");
        JsonArray episodes = mapping.getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonArray("episodes");
        for (int order = 0; order < 172; order++) {
            JsonObject episode = new JsonObject();
            episode.addProperty("order", order);
            episode.addProperty("season_number", order < 170 ? 1 : 2);
            episode.addProperty("episode_number", order < 170 ? order + 1 : order - 169);
            episodes.add(episode);
        }
        return mapping;
    }

    private static JsonObject json(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static String encodedTitle(String title) {
        return URLEncoder.encode(title, StandardCharsets.UTF_8);
    }
}
