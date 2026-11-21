package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Timeshift;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class LibraryPlaybackPrefetchTest {
    @Test public void shouldHandOffTheSelectedPlaylistWithoutRegeneratingItsParameters() {
        AtomicLong time = new AtomicLong(100);
        var cache = cache(time);
        Vod source = new Vod();
        source.setId("vod"); source.setSite(Site.get("site", "site"));
        Flag flag = new Flag("line");
        Episode episode = Episode.create("第1集", "id=1&_client_ts=first-call");
        flag.getEpisodes().add(episode); source.getFlags().add(flag);
        VodPlayRequest request = VodPlayRequest.create("site", flag, episode);
        assertTrue(LibraryPlaybackPrefetch.containsSelection(source, request));
        var identity = LibraryPlaybackPrefetch.Identity.of("work", 1, "configuration", "vod", request);
        String token = cache.put(identity, Result.vod(source), time.get());
        Vod handedOff = cache.take(token, identity).getVod();
        assertEquals(episode.getUrl(), handedOff.getFlags().get(0).getEpisodes().get(0).getUrl());
        assertTrue(LibraryPlaybackPrefetch.containsSelection(handedOff, request));
        assertNull(cache.take(token, identity));
    }

    @Test public void shouldRejectAPlaylistFromAnotherSiteLineOrEpisode() {
        Vod source = new Vod(); source.setSite(Site.get("site", "site"));
        Flag flag = new Flag("line"); Episode episode = Episode.create("高清", "selected-file");
        flag.getEpisodes().add(episode); source.getFlags().add(flag);
        assertTrue(LibraryPlaybackPrefetch.containsSelection(source, VodPlayRequest.create("site", flag, episode)));
        assertFalse(LibraryPlaybackPrefetch.containsSelection(source, VodPlayRequest.create("other", flag, episode)));
        assertFalse(LibraryPlaybackPrefetch.containsSelection(source, VodPlayRequest.create("site", new Flag("other-line"), episode)));
        assertFalse(LibraryPlaybackPrefetch.containsSelection(source, VodPlayRequest.create("site", flag, Episode.create("高清", "other-file"))));
        assertFalse(LibraryPlaybackPrefetch.containsSelection(source, VodPlayRequest.create("site", flag, Episode.create("另一集", "selected-file"))));
        assertFalse(LibraryPlaybackPrefetch.containsSelection(source, VodPlayRequest.create("site", flag, Episode.create("高清", ""))));
    }

    @Test public void shouldConsumeAnExactConfirmedSelectionOnlyOnce() {
        AtomicLong time = new AtomicLong(100);
        var cache = cache(time);
        Result response = new Result();
        var identity = identity("work", 1, "configuration", "vod", "site", "flag", "episode");
        String token = cache.put(identity, response, 100);
        assertSame(response, cache.take(token, identity));
        assertNull(cache.take(token, identity));
    }

    @Test public void shouldConsumeWhenAnAbsentConfigurationIsRepresentedAsNullOrEmpty() {
        AtomicLong time = new AtomicLong(100);
        var cache = cache(time);
        FixtureResult response = new FixtureResult();
        response.setPosition(120_000L);
        String token = cache.put(identity("work", 1, null, "vod", "site", "flag", "episode"), response, time.get());
        Result consumed = cache.take(token, identity("work", 1, "", "vod", "site", "flag", "episode"));
        assertSame(response, consumed);
        assertEquals("https://cdn.example/film.m3u8?token=fixture", consumed.getRealUrl());
        assertEquals(Long.valueOf(120_000), consumed.getPosition());
    }

    @Test public void shouldRejectEveryOtherPlaybackContextAndInvalidateItsToken() {
        AtomicLong time = new AtomicLong(100);
        var cache = cache(time);
        var expected = identity("work", 1, "configuration", "vod", "site", "flag", "episode");
        for (var actual : new LibraryPlaybackPrefetch.Identity[]{
                identity("other-work", 1, "configuration", "vod", "site", "flag", "episode"),
                identity("work", 2, "configuration", "vod", "site", "flag", "episode"),
                identity("work", 1, "other-configuration", "vod", "site", "flag", "episode"),
                identity("work", 1, "configuration", "other-vod", "site", "flag", "episode"),
                identity("work", 1, "configuration", "vod", "other-site", "flag", "episode"),
                identity("work", 1, "configuration", "vod", "site", "other-flag", "episode"),
                identity("work", 1, "configuration", "vod", "site", "flag", "other-episode")}) {
            String token = cache.put(expected, new Result(), time.get());
            assertNull(cache.take(token, actual));
            assertNull(cache.take(token, expected));
        }
    }

    @Test public void shouldBindTheSelectedNameWhenEpisodesShareAPlaybackUrl() {
        AtomicLong time = new AtomicLong(100);
        var cache = cache(time);
        VodPlayRequest first = VodPlayRequest.create("site", new Flag("line"), Episode.create("第1集", "shared-url"));
        VodPlayRequest second = VodPlayRequest.create("site", new Flag("line"), Episode.create("第2集", "shared-url"));
        var identity = LibraryPlaybackPrefetch.Identity.of("work", 1, "configuration", "vod", first);
        String token = cache.put(identity, new Result(), time.get());
        assertNull(cache.take(token, LibraryPlaybackPrefetch.Identity.of("work", 1, "configuration", "vod", second)));
        assertNull(cache.take(token, identity));
    }

    @Test public void shouldExpireFromLinkCompletionRatherThanHandoffTime() {
        AtomicLong time = new AtomicLong(59_000);
        var cache = cache(time);
        var identity = identity("work", 1, "configuration", "vod", "site", "flag", "episode");
        String token = cache.put(identity, new Result(), 0);
        assertFalse(token.isEmpty());
        time.set(60_000);
        assertNull(cache.take(token, identity));
        assertEquals("", cache.put(identity, new Result(), 0));
    }

    @Test public void shouldKeepAtMostFourHandedOffLinks() {
        AtomicLong time = new AtomicLong(100);
        var cache = cache(time);
        var identity = identity("work", 1, "configuration", "vod", "site", "flag", "episode");
        String first = cache.put(identity, new Result(), 100);
        String last = "";
        for (int i = 0; i < 4; i++) last = cache.put(identity, new Result(), 100);
        assertNull(cache.take(first, identity));
        assertNotNull(cache.take(last, identity));
    }

    @Test public void shouldRejectExtractorAddressesAndKeepDirectSignedLinks() {
        assertTrue(LibraryPlaybackPrefetch.isDirectHttp("https://cdn.example/media.m3u8?signature=123"));
        assertTrue(LibraryPlaybackPrefetch.isDirectHttp("http://127.0.0.1:9978/media"));
        for (String value : new String[]{"video://https://cdn.example/media", "magnet:example", "file:///media.mkv",
                "https://www.youtube.com/watch?v=123", "https://youtu.be/123", "https://youtube.com.example/watch",
                "https://host.example/movie.strm?key=123", "https://host.example/movie%2estrm?key=123", "broken url"}) {
            assertFalse(value, LibraryPlaybackPrefetch.isDirectHttp(value));
        }
    }

    @Test public void shouldKeepParseDrmTimeshiftAndSourceInteractionOnTheConfirmedPlaybackPath() {
        FixtureResult value = new FixtureResult();
        assertTrue(eligible(value));
        value.parse = true; assertFalse(eligible(value));
        value.parse = false; value.useParse = true; assertFalse(eligible(value));
        value.useParse = false; value.drm = Drm.create("fixture", "widevine", java.util.Map.of(), false); assertFalse(eligible(value));
        value.drm = null; value.timeshift = new Timeshift(); assertFalse(eligible(value));
        value.timeshift = null; value.click = "interaction"; assertFalse(eligible(value));
        value.click = ""; value.playUrl = "https://parser.example/?url="; assertFalse(eligible(value));
        value.playUrl = ""; value.message = true; assertFalse(eligible(value));
    }

    @Test public void shouldHonorTheCurrentExtractorRegistryBeforeHandoff() {
        FixtureResult value = new FixtureResult();
        assertFalse(LibraryPlaybackPrefetch.isEligible(value, ignored -> false));
        assertTrue(LibraryPlaybackPrefetch.isEligible(value, ignored -> true));
    }

    private boolean eligible(Result value) {
        return LibraryPlaybackPrefetch.isEligible(value, LibraryPlaybackPrefetch::isDirectHttp);
    }

    private static final class FixtureResult extends Result {
        private boolean parse, useParse, message;
        private String click = "", playUrl = "";
        private Drm drm;
        private Timeshift timeshift;
        @Override public String getRealUrl() { return "https://cdn.example/film.m3u8?token=fixture"; }
        @Override public boolean needParse() { return parse; }
        @Override public boolean isUseParse() { return useParse; }
        @Override public boolean hasMsg() { return message; }
        @Override public Drm getDrm() { return drm; }
        @Override public Timeshift getTimeshift() { return timeshift; }
        @Override public String getClick() { return click; }
        @Override public String getPlayUrl() { return playUrl; }
    }

    private LibraryPlaybackPrefetch.Handoffs cache(AtomicLong time) {
        AtomicInteger tokens = new AtomicInteger();
        return new LibraryPlaybackPrefetch.Handoffs(time::get, () -> String.valueOf(tokens.incrementAndGet()));
    }

    private LibraryPlaybackPrefetch.Identity identity(String work, int cid, String configuration, String vodId, String site, String line, String episode) {
        return LibraryPlaybackPrefetch.Identity.of(work, cid, configuration, vodId,
                VodPlayRequest.create(site, new Flag(line), Episode.create("episode title", episode)));
    }
}
