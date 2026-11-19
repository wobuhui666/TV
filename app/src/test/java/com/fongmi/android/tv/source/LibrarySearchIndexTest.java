package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class LibrarySearchIndexTest {
    private Vod work(String id, String name, String year, String type) {
        Vod item = new Vod(); item.setId(id); item.setName(name); item.setYear(year); item.setTypeName(type); return item;
    }
    private Vod source(String site, String id, String name, String year, String type) {
        Vod item = work(id, name, year, type);
        Site value = new Site(); value.setKey(site); item.setSite(value); return item;
    }

    @Test public void shouldKeepFolderSearchResultsWithoutCountingThemAsPlayableResources() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        Vod folder = new com.google.gson.Gson().fromJson("{\"vod_id\":\"gy:original-folder-id\",\"vod_name\":\"庆余年 第一季\",\"vod_year\":\"2019\",\"type_name\":\"电视剧\",\"vod_tag\":\"folder\"}", Vod.class);
        Site site = new Site(); site.setKey("AiNewGuanYing"); folder.setSite(site);
        Vod work = work("tmdb:tv:1", "庆余年", "2019", "tv");
        index.addSources(List.of(folder));
        index.addWorks(List.of(work));
        index.addSources(List.of(folder));
        LibrarySearchIndex.Snapshot result = index.snapshot();
        assertTrue(folder.isFolder());
        assertEquals(List.of(folder), result.supplements());
        assertTrue(result.works().get(0).resources().isEmpty());
        assertEquals(0, result.resourceCount());

        Vod playable = source("AiNewGuanYing", "selected-version", "庆余年 第一季", "2019", "电视剧");
        index.addSources(List.of(playable));
        assertEquals(List.of(folder), index.snapshot().supplements());
        assertEquals(List.of(playable), index.snapshot().works().get(0).resources());
        assertEquals(1, index.snapshot().resourceCount());
        index.clear();
        assertTrue(index.snapshot().supplements().isEmpty());
    }

    @Test public void shouldPreserveCategoryAndActionEntriesAcrossMetadataPagination() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Vod category = gson.fromJson("{\"vod_id\":\"versions\",\"vod_name\":\"沙丘\",\"vod_year\":\"2021\",\"cate\":{\"land\":1}}", Vod.class);
        Vod action = gson.fromJson("{\"vod_id\":\"login\",\"vod_name\":\"沙丘\",\"vod_year\":\"2021\",\"action\":\"login\"}", Vod.class);
        Site site = new Site(); site.setKey("source"); category.setSite(site); action.setSite(site);
        index.addSources(List.of(category, action));
        index.addWorks(List.of(work("tmdb:movie:1", "沙丘", "2021", "movie")));
        index.addWorks(List.of(work("tmdb:movie:2", "沙丘", "1984", "movie")));
        assertTrue(category.isFolder()); assertTrue(action.isAction());
        assertEquals(List.of(category, action), index.snapshot().supplements());
        assertEquals(0, index.snapshot().resourceCount());
        assertTrue(index.snapshot().works().stream().allMatch(work -> work.resources().isEmpty()));
    }

    @Test public void shouldKeepDistinctActionsWithoutIdsAndDeduplicateRepeatedActions() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        Vod login = gson.fromJson("{\"vod_name\":\"配置 CK\",\"action\":\"login\"}", Vod.class);
        Vod account = gson.fromJson("{\"vod_name\":\"配置 CK\",\"action\":\"account\"}", Vod.class);
        Vod repeated = gson.fromJson("{\"vod_name\":\"配置 CK\",\"action\":\"login\"}", Vod.class);
        Site site = new Site(); site.setKey("source");
        // JVM Android stubs do not implement TextUtils.isEmpty(null); use the getter's Android value.
        for (Vod item : List.of(login, account, repeated)) { item.setId(""); item.setSite(site); }
        index.addSources(List.of(login, account));
        index.addSources(List.of(repeated));
        assertTrue(login.getId().isEmpty());
        assertEquals(List.of(login, account), index.snapshot().supplements());
        assertEquals(0, index.snapshot().resourceCount());
        assertEquals(LibraryIdentity.sourceKey(login), LibraryIdentity.sourceKey(repeated));
        assertNotEquals(LibraryIdentity.sourceKey(login), LibraryIdentity.sourceKey(account));
        assertNotEquals(LibraryIdentity.sourceKey(login), LibraryIdentity.sourceKey(source("source", "action:login", "影片", "", "")));
        Site another = new Site(); another.setKey("other"); repeated.setSite(another);
        assertNotEquals(LibraryIdentity.sourceKey(login), LibraryIdentity.sourceKey(repeated));
        index.addSources(List.of(repeated));
        assertEquals(List.of(login, account, repeated), index.snapshot().supplements());
    }

    @Test public void shouldAttachLateSourcesWithoutDuplicatingTheWork() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        Vod item = work("tmdb:movie:1", "星际穿越", "2014", "movie");
        index.addWorks(List.of(item));
        index.addSources(List.of(source("a", "1", "星际穿越 4K", "2014", "电影"), source("b", "1", "星际穿越", "2014", "电影")));
        index.addWorks(List.of(item));
        assertEquals(1, index.works().size());
        assertEquals(2, index.works().get(0).resources().size());
        assertTrue(index.supplements().isEmpty());
    }

    @Test public void shouldWaitForEvidenceWhenTwoRemakesHaveTheSameName() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        index.addWorks(List.of(work("tmdb:movie:1", "沙丘", "1984", "movie"), work("tmdb:movie:2", "沙丘", "2021", "movie")));
        Vod unknown = source("a", "x", "沙丘", "", "电影");
        Vod remake = source("a", "y", "沙丘", "2021", "电影");
        index.addSources(List.of(unknown, remake));
        assertEquals(List.of(unknown), index.supplements());
        assertTrue(index.works().get(0).resources().isEmpty());
        assertEquals(List.of(remake), index.works().get(1).resources());
    }

    @Test public void shouldDistinguishMovieAndSeriesAndKeepSourceOnlyContent() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        index.addWorks(List.of(work("tmdb:movie:1", "三体", "2023", "movie"), work("tmdb:tv:2", "三体", "2023", "tv")));
        Vod series = source("a", "x", "三体", "2023", "电视剧");
        Vod exclusive = source("b", "y", "片源独有短剧", "2026", "电视剧");
        index.addSources(List.of(series, exclusive));
        assertTrue(index.works().get(0).resources().isEmpty());
        assertEquals(List.of(series), index.works().get(1).resources());
        assertEquals(List.of(exclusive), index.supplements());
    }

    @Test public void shouldUseAliasesAndPlaybackDetailsWhileKeepingLaterUnlabelledSeasonsManual() {
        Vod movie = work("tmdb:movie:1", "星际穿越", "2014", "movie"); movie.setOriginalName("Interstellar");
        assertTrue(LibrarySourceMatcher.match(source("a", "x", "Interstellar", "2014", "电影"), movie).automatic());
        assertFalse(LibrarySourceMatcher.match(source("a", "x", "Interstellar", "2014", ""), movie).automatic());
        Vod hydrated = source("a", "x", "Interstellar", "", "");
        Flag flag = new Flag("line"); flag.getEpisodes().add(Episode.create("正片", "movie-token")); hydrated.getFlags().add(flag);
        assertTrue(LibrarySourceMatcher.match(hydrated, movie).automatic());
        Vod seasonOne = source("a", "tv", "庆余年", "2019", "电视剧");
        assertTrue(LibrarySourceMatcher.match(seasonOne, "庆余年", List.of(), "2019", false, 1).automatic());
        assertFalse(LibrarySourceMatcher.match(seasonOne, "庆余年", List.of(), "2019", false, 2).automatic());
    }

    @Test public void shouldKeepMissingMetadataCandidatesWithoutResolvingSameNameAmbiguity() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        Vod movie = work("tmdb:movie:1", "星际穿越", "2014", "movie");
        Vod summary = source("a", "summary", "星际穿越", "", "");
        Vod hydrated = source("b", "detail", "星际穿越", "", "");
        Flag flag = new Flag("line"); flag.getEpisodes().add(Episode.create("正片", "movie-token")); hydrated.getFlags().add(flag);
        index.addWorks(List.of(movie)); index.addSources(List.of(summary, hydrated));
        assertEquals(List.of(summary, hydrated), index.snapshot().works().get(0).resources());
        assertFalse(LibrarySourceMatcher.match(summary, movie).automatic());
        assertTrue(LibrarySourceMatcher.match(hydrated, movie).automatic());
        index.addWorks(List.of(work("tmdb:movie:2", "星际穿越", "2024", "movie")));
        assertEquals(List.of(summary, hydrated), index.snapshot().supplements());
        assertEquals(0, index.snapshot().resourceCount());
    }

    @Test public void shouldNotAssignUnknownTypeWhenMovieAndSeriesHaveTheSameTitle() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        index.addWorks(List.of(work("tmdb:movie:1", "三体", "2023", "movie"), work("tmdb:tv:2", "三体", "2023", "tv")));
        Vod unknown = source("a", "detail", "三体", "2023", "");
        Flag flag = new Flag("line"); flag.getEpisodes().add(Episode.create("1", "episode-token")); unknown.getFlags().add(flag);
        index.addSources(List.of(unknown));
        assertEquals(List.of(unknown), index.snapshot().supplements());
        assertEquals(0, index.snapshot().resourceCount());
    }

    @Test public void shouldKeepStableWorkOrderWhenSourcesArriveFirst() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        index.addSources(List.of(source("a", "x", "星际穿越", "2014", "电影")));
        Vod first = work("tmdb:movie:1", "星际穿越", "2014", "movie"), second = work("tmdb:movie:2", "降临", "2016", "movie");
        index.addWorks(List.of(first, second));
        index.addWorks(List.of(second, first));
        assertEquals(List.of(first, second), index.works().stream().map(LibrarySearchIndex.Work::item).toList());
        assertTrue(index.supplements().isEmpty());
    }

    @Test public void shouldDistinguishSeriesRemakesWithoutRejectingALaterSeasonsAirYear() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        index.addWorks(List.of(work("tmdb:tv:1", "某剧", "2011", "tv"), work("tmdb:tv:2", "某剧", "2026", "tv")));
        Vod remake = source("a", "x", "某剧", "2026", "电视剧"); index.addSources(List.of(remake));
        assertTrue(index.works().get(0).resources().isEmpty());
        assertEquals(List.of(remake), index.works().get(1).resources());
        assertFalse(LibrarySourceMatcher.match(remake, "某剧", List.of(), "2011", false, 1).matches());
        assertTrue(LibrarySourceMatcher.match(source("a", "season-2", "某剧 第二季", "2026", "电视剧"), "某剧", List.of(), "2011", false, 2).automatic());
    }

    @Test public void shouldRecognizeAnimeSeriesAndKeepAnimatedMoviesSeparate() {
        Vod show = work("tmdb:tv:1", "作品", "2026", "tv");
        Vod film = work("tmdb:movie:2", "作品", "2026", "movie");
        assertTrue(LibrarySourceMatcher.match(source("a", "show", "作品", "2026", "日本动漫"), show).automatic());
        assertTrue(LibrarySourceMatcher.match(source("a", "film", "作品", "2026", "动画电影"), film).automatic());
        assertFalse(LibrarySourceMatcher.match(source("a", "film", "作品", "2026", "动画电影"), show).matches());
    }

    @Test public void shouldReconcileAnEarlierAttachmentWhenPaginationAddsAnEqualMatch() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        Vod original = work("tmdb:movie:1", "沙丘", "1984", "movie");
        Vod unknown = source("a", "x", "沙丘", "", "电影");
        index.addWorks(List.of(original)); index.addSources(List.of(unknown));
        LibrarySearchIndex.Snapshot first = index.snapshot();
        assertEquals(List.of(unknown), first.works().get(0).resources());
        index.addWorks(List.of(work("tmdb:movie:2", "沙丘", "2021", "movie")));
        LibrarySearchIndex.Snapshot next = index.snapshot();
        assertEquals(List.of(unknown), next.supplements());
        assertEquals(0, next.resourceCount());
        assertEquals(List.of(unknown), first.works().get(0).resources());
    }

    @Test public void shouldForgetPriorAttachmentsWhenSearchIsReset() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        Vod source = source("a", "x", "沙丘", "2021", "电影");
        index.addWorks(List.of(work("tmdb:movie:1", "沙丘", "2021", "movie")));
        index.addSources(List.of(source)); assertEquals(1, index.snapshot().resourceCount());
        index.clear();
        index.addWorks(List.of(work("tmdb:movie:2", "降临", "2016", "movie")));
        index.addSources(List.of(source));
        assertEquals(List.of(source), index.snapshot().supplements());
        assertEquals(0, index.snapshot().resourceCount());
    }

    @Test public void shouldKeepEverySiteResourceWhenSharingIdenticalTitlePreparation() {
        LibrarySearchIndex index = new LibrarySearchIndex();
        Vod movie = work("tmdb:movie:1", "黑客帝国", "1999", "movie"); movie.setOriginalName("The Matrix");
        index.addWorks(List.of(movie));
        List<Vod> sources = java.util.stream.IntStream.range(0, 8)
                .mapToObj(site -> source("site-" + site, "same-id", "黑客帝国（The Matrix）", "1999", "电影")).toList();
        index.addSources(sources);
        assertEquals(sources, index.snapshot().works().get(0).resources());
        assertEquals(sources.stream().map(Vod::getSiteKey).toList(),
                index.snapshot().works().get(0).resources().stream().map(Vod::getSiteKey).toList());
        assertEquals(8, index.snapshot().resourceCount());
        assertTrue(index.snapshot().supplements().isEmpty());
        index.clear();
        index.addWorks(List.of(movie));
        Vod changedType = source("site-0", "same-id", "黑客帝国（The Matrix）", "1999", "电视剧");
        index.addSources(List.of(changedType));
        assertEquals(List.of(changedType), index.snapshot().supplements());
        assertEquals(0, index.snapshot().resourceCount());
    }
}
