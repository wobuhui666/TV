package com.fongmi.android.tv.player.skip;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CommunitySkipParserTest {

    private static final EpisodeIdentity TV = new EpisodeIdentity(1396, "tt0903747", 1, 1);
    private static final long DURATION = 3_500_000;

    @Test
    public void shouldKeepColdOpenBeforeTheIntroDbIntro() {
        List<SkipSegment> result = CommunitySkipParser.theIntroDb(theIntro("[{\"start_ms\":228627,\"end_ms\":246339,\"confidence\":0.7016}]", "[]"), TV, DURATION);
        assertEquals(List.of(new SkipSegment(SkipSegment.Type.INTRO, 228_627, 246_339, "theintrodb")), result);
    }

    @Test
    public void shouldKeepLateIntroAfterHalfwayWithoutMovingItsStart() {
        List<SkipSegment> result = CommunitySkipParser.theIntroDb(theIntro("[{\"start_ms\":1850000,\"end_ms\":2025000,\"confidence\":0.95}]", "[]"), TV, DURATION);
        assertEquals(List.of(new SkipSegment(SkipSegment.Type.INTRO, 1_850_000, 2_025_000, "theintrodb")), result);
        String introDb = "{\"imdb_id\":\"tt0903747\",\"season\":1,\"episode\":1,\"intro\":{\"start_ms\":1850000,\"end_ms\":2025000,\"confidence\":0.95}}";
        assertEquals(1_850_000, CommunitySkipParser.introDb(introDb, TV, DURATION).get(0).startMs());
    }

    @Test
    public void shouldUseDocumentedExplicitNullBoundariesOnly() {
        List<SkipSegment> result = CommunitySkipParser.theIntroDb(theIntro("[{\"start_ms\":null,\"end_ms\":23000}]", "[{\"start_ms\":3431000,\"end_ms\":null}]"), TV, DURATION);
        assertEquals(0, result.get(0).startMs());
        assertEquals(DURATION, result.get(1).endMs());
        assertTrue(CommunitySkipParser.theIntroDb(theIntro("[{\"end_ms\":23000}]", "[{\"start_ms\":3431000}]"), TV, DURATION).isEmpty());
    }

    @Test
    public void shouldRejectAnotherEpisodeOrRelease() {
        String body = theIntro("[{\"start_ms\":20000,\"end_ms\":70000}]", "[]");
        assertTrue(CommunitySkipParser.theIntroDb(body.replace("\"episode\":1", "\"episode\":2"), TV, DURATION).isEmpty());
        assertTrue(CommunitySkipParser.theIntroDb(body.replace("\"season\":1", "\"season\":2"), TV, DURATION).isEmpty());
        assertTrue(CommunitySkipParser.theIntroDb(body.replace("\"group_duration_ms\":3500192", "\"group_duration_ms\":3400000"), TV, DURATION).isEmpty());
    }

    @Test
    public void shouldRejectLowConfidenceAndMalformedBoundaries() {
        for (String segment : List.of("{\"start_ms\":10,\"end_ms\":50000,\"confidence\":0.25}",
                "{\"start_ms\":-1,\"end_ms\":50000}", "{\"start_ms\":50000,\"end_ms\":10}",
                "{\"start_ms\":0,\"end_ms\":3500001}", "{\"start_ms\":0,\"end_ms\":400000}",
                "{\"start_ms\":\"0\",\"end_ms\":50000}", "{\"start_ms\":0,\"end_ms\":50000.1}",
                "{\"start_ms\":0,\"end_ms\":1e100}", "{\"start_ms\":0,\"end_ms\":50000,\"confidence\":null}")) {
            assertTrue(segment, CommunitySkipParser.theIntroDb(theIntro("[" + segment + "]", "[]"), TV, DURATION).isEmpty());
        }
    }

    @Test
    public void shouldRejectOverlapsAndKeepSeparatedIntroRanges() {
        String segments = "[{\"start_ms\":30000,\"end_ms\":90000},{\"start_ms\":80000,\"end_ms\":100000},{\"start_ms\":120000,\"end_ms\":150000}]";
        List<SkipSegment> result = CommunitySkipParser.theIntroDb(theIntro(segments, "[]"), TV, DURATION);
        assertEquals(List.of(new SkipSegment(SkipSegment.Type.INTRO, 120_000, 150_000, "theintrodb")), result);
        assertEquals(2, CommunitySkipParser.theIntroDb(theIntro("[{\"start_ms\":30000,\"end_ms\":90000},{\"start_ms\":120000,\"end_ms\":150000}]", "[]"), TV, DURATION).size());
    }

    @Test
    public void shouldPreserveOutroEndBeforePostCreditScene() {
        String body = "{\"imdb_id\":\"tt0903747\",\"season\":1,\"episode\":1,\"intro\":null,\"outro\":{\"start_ms\":3431000,\"end_ms\":3470000,\"confidence\":1},\"post_credits\":{\"start_ms\":3470000,\"end_ms\":3500000}}";
        List<SkipSegment> result = CommunitySkipParser.introDb(body, TV, DURATION);
        assertEquals(List.of(new SkipSegment(SkipSegment.Type.CREDITS, 3_431_000, 3_470_000, "introdb")), result);
        assertTrue(CommunitySkipParser.introDb(body.replace("tt0903747", "tt0944947"), TV, DURATION).isEmpty());
        assertTrue(CommunitySkipParser.introDb(body.replace("\"episode\":1", "\"episode\":2"), TV, DURATION).isEmpty());
    }

    @Test
    public void shouldConvertTheOtherDbSecondsAndChooseMatchingRelease() {
        String body = "{\"items\":[" + version(1, 1_100, "intro", 0, 120) + "," + version(1, 1_300, "intro", 12.5, 27.5) + "]}";
        List<SkipSegment> result = CommunitySkipParser.theOtherDb(body, new EpisodeIdentity(226045, 1, 1), 1_300_000);
        assertEquals(List.of(new SkipSegment(SkipSegment.Type.INTRO, 12_500, 27_500, "theotherdb")), result);
    }

    @Test
    public void shouldRejectUnknownRuntimeWrongEpisodeOrAmbiguousRelease() {
        for (String body : List.of("{\"items\":[" + version(2, 1_300, "intro", 0, 27) + "]}",
                "{\"items\":[" + version(1, 1_300, "intro", 0, 27).replace("\"version_runtime\":1300", "\"version_runtime\":null") + "]}",
                "{\"items\":[" + version(1, 1_300, "intro", 0, 27) + "," + version(1, 1_300, "intro", 25, 50) + "]}")) {
            assertTrue(CommunitySkipParser.theOtherDb(body, new EpisodeIdentity(226045, 1, 1), 1_300_000).isEmpty());
        }
    }

    @Test
    public void shouldIgnoreChaptersAndPostCreditMarkers() {
        String body = "{\"items\":[" + version(1, 1_300, "chapter", 0, 27) + "," + version(1, 1_300, "post_credits", 1_250, 1_300) + "]}";
        assertTrue(CommunitySkipParser.theOtherDb(body, new EpisodeIdentity(226045, 1, 1), 1_300_000).isEmpty());
    }

    @Test
    public void shouldSupportMovieCreditsWithoutEpisodeCoordinates() {
        EpisodeIdentity movie = EpisodeIdentity.movie(550, "tt0137523");
        String theIntro = "{\"tvdb_id\":169,\"type\":\"movie\",\"credits\":[{\"start_ms\":3400000,\"end_ms\":3470000}]}";
        assertEquals(1, CommunitySkipParser.theIntroDb(theIntro, movie, DURATION).size());
        String intro = "{\"imdb_id\":\"tt0137523\",\"media_type\":\"movie\",\"is_movie\":true,\"outro\":{\"start_ms\":3400000,\"end_ms\":3470000}}";
        assertEquals(1, CommunitySkipParser.introDb(intro, movie, DURATION).size());
        String other = "{\"items\":[{\"video_type\":\"movie\",\"version_runtime\":3500,\"marker\":[{\"marker_type\":\"credits\",\"time_start\":3400,\"time_end\":3470}]}]}";
        assertEquals(1, CommunitySkipParser.theOtherDb(other, movie, DURATION).size());
        assertTrue(CommunitySkipParser.theIntroDb(theIntro, TV, DURATION).isEmpty());
        assertTrue(CommunitySkipParser.introDb(intro, TV, DURATION).isEmpty());
        assertTrue(CommunitySkipParser.theOtherDb(other, TV, DURATION).isEmpty());
    }

    @Test
    public void shouldReturnNoSegmentsForEmptyMalformedAndUnexpectedBodies() {
        for (String body : List.of("", "not-json", "[]", "null", "{\"error\":\"not found\"}")) {
            assertTrue(CommunitySkipParser.theIntroDb(body, TV, DURATION).isEmpty());
            assertTrue(CommunitySkipParser.introDb(body, TV, DURATION).isEmpty());
            assertTrue(CommunitySkipParser.theOtherDb(body, TV, DURATION).isEmpty());
        }
    }

    private static String theIntro(String intro, String credits) {
        return "{\"tvdb_id\":81189,\"type\":\"tv\",\"season\":1,\"episode\":1,\"group_duration_ms\":3500192,\"intro\":" + intro + ",\"credits\":" + credits + "}";
    }

    private static String version(int episode, int duration, String type, double start, double end) {
        return "{\"video_type\":\"tv\",\"version_runtime\":" + duration + ",\"base_video_season\":{\"season_number\":1},\"base_video_episode\":{\"episode_number\":" + episode + "},\"marker\":[{\"marker_type\":\"" + type + "\",\"time_start\":" + start + ",\"time_end\":" + end + "}]}";
    }
}
