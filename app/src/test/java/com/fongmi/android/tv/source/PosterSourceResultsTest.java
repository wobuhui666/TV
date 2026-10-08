package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PosterSourceResultsTest {
    @Test
    public void identicalSourceLocalIdsFromDifferentSitesRemainSelectable() {
        PosterSourceResults results = results("沙丘");
        Vod first = vod("a", "42", "沙丘", "");
        Vod second = vod("b", "42", "沙丘", "");

        results.add(List.of(first, second, first));

        assertEquals(2, results.snapshot(false).size());
        assertFalse(PosterSourceResults.key(first).equals(PosterSourceResults.key(second)));
    }

    @Test
    public void originalTitleMatchesButItsWrongRemakeYearDoesNot() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of("Dune"), "2021", null), "");
        Vod original = vod("a", "1", "Dune", "2021");
        Vod remake = vod("a", "2", "Dune", "1984");

        results.add(List.of(original, remake));

        assertEquals(1, results.snapshot(true).size());
        assertSame(original, results.snapshot(true).get(0).vod());
        assertTrue(results.snapshot(true).get(0).match().confident());
        assertEquals(1, results.hiddenCount());
    }

    @Test
    public void smartOrderPrefersTitleEvidenceBeforePreferredSite() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of("Dune"), "2021", null), "home");
        Vod lowerPreferred = vod("home", "alias", "Dune", "2021");
        Vod exactOther = vod("other", "exact", "沙丘", "2021");
        Vod exactPreferred = vod("home", "exact", "沙丘", "2021");

        results.add(List.of(lowerPreferred, exactOther, exactPreferred));

        List<PosterSourceResults.Candidate> ordered = results.snapshot(true);
        assertSame(exactPreferred, ordered.get(0).vod());
        assertSame(exactOther, ordered.get(1).vod());
        assertSame(lowerPreferred, ordered.get(2).vod());
    }

    @Test
    public void ordinaryOrderFollowsConfigurationDespiteResponseTiming() {
        PosterSourceResults results = new PosterSourceResults(SearchRelevance.Query.of("沙丘"), "b", List.of("a", "b", "c"));
        Vod b = vod("b", "1", "沙丘", "");
        Vod c = vod("c", "1", "沙丘", "");
        Vod a = vod("a", "1", "沙丘", "");
        Vod unknown = vod("outside", "1", "沙丘", "");

        results.add(List.of(unknown, c, b));
        results.add(List.of(a));

        List<PosterSourceResults.Candidate> ordered = results.snapshot(false);
        assertSame(a, ordered.get(0).vod());
        assertSame(b, ordered.get(1).vod());
        assertSame(c, ordered.get(2).vod());
        assertSame(unknown, ordered.get(3).vod());
    }

    @Test
    public void repeatedIdCanImproveMetadataWithoutCreatingAnotherCard() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of(), "2021", null), "");
        Vod incomplete = vod("a", "same", "沙丘", "");
        Vod confirmed = vod("a", "same", "沙丘", "2021");

        results.add(List.of(incomplete, confirmed, incomplete));

        assertEquals(1, results.snapshot(true).size());
        assertSame(confirmed, results.snapshot(true).get(0).vod());
        assertTrue(results.snapshot(true).get(0).match().confident());
    }

    @Test
    public void candidateBoundStillAdmitsABetterLateResult() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of("Dune"), "", null), "");
        List<Vod> early = new ArrayList<>();
        for (int i = 0; i < 140; i++) early.add(vod("a", "alias-" + i, "Dune", ""));
        results.add(early);
        Vod exact = vod("b", "exact", "沙丘", "");
        results.add(List.of(exact));

        assertEquals(120, results.snapshot(true).size());
        assertSame(exact, results.snapshot(true).get(0).vod());
        assertEquals(120, results.snapshot(false).stream().map(it -> PosterSourceResults.key(it.vod())).distinct().count());
    }

    @Test
    public void requestedSeasonRejectsOtherSeasonsAndMarksUnknownOnesUncertain() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("庆余年", List.of(), "", 2), "");
        Vod second = vod("a", "2", "庆余年 第二季", "2024");
        Vod first = vod("a", "1", "庆余年 第一季", "2019");
        Vod unknown = vod("b", "series", "庆余年", "");

        results.add(List.of(first, unknown, second));

        assertEquals(2, results.snapshot(true).size());
        assertSame(second, results.snapshot(true).get(0).vod());
        assertTrue(results.snapshot(true).get(0).match().confident());
        assertFalse(results.snapshot(true).get(1).match().confident());
        assertEquals(1, results.hiddenCount());
    }

    @Test
    public void allSeasonPosterDoesNotApplyTheSeriesPremiereYear() {
        PosterSourceResults results = results("庆余年");
        results.add(List.of(vod("a", "1", "庆余年 第一季", "2019"), vod("a", "2", "庆余年 第二季", "2024")));

        assertEquals(2, results.snapshot(true).size());
        assertEquals(0, results.hiddenCount());
    }

    @Test
    public void rejectedDuplicatesDoNotInflateHiddenCountAndCorrectionClearsIt() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of(), "2021", null), "");
        Vod wrong = vod("a", "same", "沙丘", "1984");
        Vod correct = vod("a", "same", "沙丘", "2021");

        results.add(List.of(wrong, wrong));
        assertEquals(1, results.hiddenCount());
        results.add(List.of(correct));

        assertEquals(1, results.snapshot(true).size());
        assertEquals(0, results.hiddenCount());
    }

    @Test
    public void aWeakerConflictingDuplicateDoesNotCountAVisibleCardAsHidden() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of(), "2021", null), "");
        Vod correct = vod("a", "same", "沙丘", "2021");
        results.add(List.of(correct, vod("a", "same", "沙丘", "1984")));

        assertEquals(1, results.snapshot(true).size());
        assertSame(correct, results.snapshot(true).get(0).vod());
        assertEquals(0, results.hiddenCount());
    }

    @Test
    public void ignoredDirectoryActionAndUnaddressableEntriesNeverBecomeCards() {
        PosterSourceResults results = results("沙丘");
        CandidateVod folder = vod("a", "folder", "沙丘", "");
        folder.folder = true;
        CandidateVod action = vod("a", "action", "沙丘", "");
        action.action = true;
        results.add(Arrays.asList(null, folder, action, vod("a", "", "沙丘", ""), vod("", "1", "沙丘", "")));

        assertTrue(results.snapshot(true).isEmpty());
        assertEquals(0, results.hiddenCount());
    }

    @Test
    public void snapshotsAreIndependentOfCallerMutations() {
        PosterSourceResults results = results("沙丘");
        results.add(List.of(vod("a", "1", "沙丘", "")));
        List<PosterSourceResults.Candidate> first = results.snapshot(true);
        first.clear();

        assertEquals(1, results.snapshot(true).size());
    }

    @Test
    public void seriesPosterRejectsExplicitSameNameMovieCategories() {
        PosterSourceResults results = typedResults("三体", "tv");
        results.add(List.of(typedVod("movie", "三体", "电影"), typedVod("film", "三体", "FILM"),
                typedVod("english", "三体", "Movie"), typedVod("series", "三体", "国产电视剧")));

        assertEquals(1, results.snapshot(true).size());
        assertEquals("series", results.snapshot(true).get(0).vod().getId());
        assertTrue(results.snapshot(true).get(0).match().confident());
        assertEquals(3, results.hiddenCount());
    }

    @Test
    public void moviePosterRejectsExplicitSameNameSeriesCategories() {
        PosterSourceResults results = typedResults("三体", "movie");
        results.add(List.of(typedVod("tv", "三体", "TV"), typedVod("series", "三体", "Drama series"),
                typedVod("chinese", "三体", "连续剧"), typedVod("traditional", "三体", "電視劇"),
                typedVod("movie", "三体", "科幻電影")));

        assertEquals(1, results.snapshot(true).size());
        assertEquals("movie", results.snapshot(true).get(0).vod().getId());
        assertTrue(results.snapshot(true).get(0).match().confident());
        assertEquals(4, results.hiddenCount());
    }

    @Test
    public void unspecifiedOrAmbiguousCategoriesStayVisibleButUnconfirmed() {
        PosterSourceResults results = typedResults("三体", "movie");
        results.add(List.of(typedVod("empty", "三体", ""), typedVod("anime", "三体", "anime"),
                typedVod("animation", "三体", "国产动漫"), typedVod("mixed", "三体", "电影 / 电视剧"),
                typedVod("substring", "三体", "ITV精选")));

        assertEquals(5, results.snapshot(true).size());
        assertTrue(results.snapshot(true).stream().noneMatch(item -> item.match().confident()));
        assertEquals(0, results.hiddenCount());
    }

    @Test
    public void kindIsNeverGuessedFromTitleText() {
        PosterSourceResults results = typedResults("The Movie", "tv");
        results.add(List.of(typedVod("title", "The Movie", "")));

        assertEquals(1, results.snapshot(true).size());
        assertFalse(results.snapshot(true).get(0).match().confident());
    }

    @Test
    public void aLaterKnownKindUpgradesTheSameIdAtTheSameTitleScore() {
        PosterSourceResults results = typedResults("三体", "tv");
        Vod uncertain = typedVod("same", "三体", "");
        Vod confirmed = typedVod("same", "三体", "电视剧");
        results.add(List.of(uncertain, confirmed, uncertain));

        assertEquals(1, results.snapshot(true).size());
        assertSame(confirmed, results.snapshot(true).get(0).vod());
        assertTrue(results.snapshot(true).get(0).match().confident());
    }

    @Test
    public void explicitConflictingTypeRemovesAnEarlierUnclassifiedCandidate() {
        PosterSourceResults results = typedResults("三体", "tv");
        results.add(List.of(typedVod("same", "三体", ""), typedVod("same", "三体", "电影"), typedVod("same", "三体", "")));

        assertTrue(results.snapshot(true).isEmpty());
        assertEquals(1, results.hiddenCount());
        results.add(List.of(typedVod("same", "三体", "电视剧")));
        assertEquals(1, results.snapshot(true).size());
        assertEquals(0, results.hiddenCount());
    }

    @Test
    public void equallyMatchingKnownTypeRanksBeforeUnknownTypeFromPreferredSite() {
        PosterSourceResults results = new PosterSourceResults(SearchRelevance.Query.of("三体"), "home", List.of("home", "other"), "tv");
        Vod unknown = vod("home", "unknown", "三体", "");
        Vod known = vod("other", "known", "三体", "");
        known.setTypeName("电视剧");
        results.add(List.of(unknown, known));

        assertSame(known, results.snapshot(true).get(0).vod());
        assertSame(unknown, results.snapshot(false).get(0).vod());
    }

    @Test
    public void legacyConstructorsDoNotImposeANewKindConstraint() {
        PosterSourceResults results = results("三体");
        results.add(List.of(typedVod("movie", "三体", "电影"), typedVod("tv", "三体", "电视剧"), typedVod("unknown", "三体", "")));

        assertEquals(3, results.snapshot(true).size());
        assertTrue(results.snapshot(true).stream().allMatch(item -> item.match().confident()));
    }

    @Test
    public void spiderManPosterRejectsRealReviewSuitDetailsAndFakeTrailerResults() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("蜘蛛侠：崭新之日", List.of("Spider-Man: Brand New Day"), "2026", null), "", List.of(), "movie");
        results.add(List.of(
                typedVod("review", "线上真实影评《蜘蛛侠崭新之日》", "电影"),
                typedVod("suit", "【漫威】电影《蜘蛛侠：崭新之日》新战衣细节展示", "电影"),
                typedVod("fake-trailer", "蜘蛛侠：崭新之日 Spider-Man: Brand New Day 伪预告片", "电影"),
                typedVod("review-alias", "蜘蛛侠：崭新之日 / 影评", "电影")));

        assertTrue(results.snapshot(true).isEmpty());
        assertEquals(4, results.hiddenCount());
        Vod exact = typedVod("feature", "Spider-Man: Brand New Day", "电影");
        results.add(List.of(exact));
        assertEquals(1, results.snapshot(true).size());
        assertSame(exact, results.snapshot(true).get(0).vod());
        assertFalse("Missing year remains an explicit source choice to verify", results.snapshot(true).get(0).match().confident());
    }

    @Test
    public void onlyTelevisionPostersAllowUnselectedSeasonSuffixes() {
        PosterSourceResults movie = typedResults("Rocky", "movie");
        movie.add(List.of(typedVod("original", "Rocky", "movie"), typedVod("sequel", "Rocky II", "movie")));
        assertEquals(1, movie.snapshot(true).size());
        assertEquals("original", movie.snapshot(true).get(0).vod().getId());

        PosterSourceResults tv = typedResults("庆余年", "tv");
        tv.add(List.of(typedVod("first", "庆余年 第一季", "电视剧"), typedVod("second", "庆余年 第二季", "电视剧"), typedVod("unknown", "庆余年", "")));
        assertEquals(3, tv.snapshot(true).size());
        assertEquals(0, tv.hiddenCount());
    }

    @Test
    public void explicitSourcePriorityOutranksSmartEvidenceAndTheHomeSource() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of("Dune"), "2021", null),
                "home", List.of("home", "second", "first"), "movie", List.of("first", "second"));
        Vod first = vod("first", "1", "Dune", "");
        Vod second = vod("second", "1", "沙丘", "2021");
        Vod home = vod("home", "1", "沙丘", "2021");
        second.setTypeName("电影");
        home.setTypeName("电影");
        results.add(List.of(home, second, first));

        assertTrue(results.snapshot(true).get(0).match().score() < results.snapshot(true).get(1).match().score());
        assertFalse(results.snapshot(true).get(0).match().confident());
        for (boolean smart : new boolean[]{false, true}) {
            assertEquals(List.of(first, second, home), results.snapshot(smart).stream().map(PosterSourceResults.Candidate::vod).toList());
        }
    }

    @Test
    public void explicitPriorityStillRejectsWrongTitlesYearsSeasonsAndKinds() {
        PosterSourceResults movie = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of(), "2021", null),
                "priority", List.of("priority", "fallback"), "movie", List.of("priority"));
        Vod wrongKind = vod("priority", "kind", "沙丘", "2021");
        wrongKind.setTypeName("电视剧");
        Vod correct = vod("fallback", "correct", "沙丘", "2021");
        correct.setTypeName("电影");
        movie.add(List.of(vod("priority", "title", "爱情公寓", "2021"), vod("priority", "year", "沙丘", "1984"),
                vod("priority", "review", "沙丘 / 影评", "2021"), wrongKind, correct));

        assertEquals(1, movie.snapshot(true).size());
        assertSame(correct, movie.snapshot(true).get(0).vod());
        assertEquals(4, movie.hiddenCount());

        PosterSourceResults series = new PosterSourceResults(new SearchRelevance.Query("庆余年", List.of(), "", 2),
                "priority", List.of("priority", "fallback"), "tv", List.of("priority"));
        Vod secondSeason = vod("fallback", "season2", "庆余年 第二季", "2024");
        series.add(List.of(vod("priority", "season1", "庆余年 第一季", "2019"), secondSeason));
        assertEquals(1, series.snapshot(true).size());
        assertSame(secondSeason, series.snapshot(true).get(0).vod());
        assertEquals(1, series.hiddenCount());
    }

    @Test
    public void fallbackSourcesKeepTheirOriginalSmartOrConfigurationOrder() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of("Dune"), "", null),
                "home", List.of("a", "home", "priority"), "", List.of("priority"));
        Vod priority = vod("priority", "1", "Dune", "");
        Vod a = vod("a", "1", "沙丘", "");
        Vod home = vod("home", "1", "沙丘", "");
        results.add(List.of(home, priority, a));

        assertEquals(List.of(priority, a, home), results.snapshot(false).stream().map(PosterSourceResults.Candidate::vod).toList());
        assertEquals(List.of(priority, home, a), results.snapshot(true).stream().map(PosterSourceResults.Candidate::vod).toList());
    }

    @Test
    public void matchesInsideTheSamePreferredSourceKeepTheirOriginalOrdering() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of("Dune"), "", null),
                "", List.of("priority"), "", List.of("priority"));
        Vod alias = vod("priority", "alias", "Dune", "");
        Vod exact = vod("priority", "exact", "沙丘", "");
        results.add(List.of(alias, exact));

        assertSame(alias, results.snapshot(false).get(0).vod());
        assertSame(exact, results.snapshot(true).get(0).vod());
    }

    @Test
    public void fullCandidateListAdmitsALaterWeakerMatchFromAPreferredSource() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of("Dune"), "", null),
                "fallback", List.of("fallback", "second", "first"), "", List.of("first", "second"));
        List<Vod> early = new ArrayList<>();
        for (int i = 0; i < 140; i++) early.add(vod("fallback", "exact-" + i, "沙丘", ""));
        results.add(early);
        Vod second = vod("second", "alias", "Dune", "");
        Vod first = vod("first", "alias", "Dune", "");
        results.add(List.of(second));
        results.add(List.of(first));

        for (boolean smart : new boolean[]{false, true}) {
            List<PosterSourceResults.Candidate> ordered = results.snapshot(smart);
            assertEquals(120, ordered.size());
            assertSame(first, ordered.get(0).vod());
            assertSame(second, ordered.get(1).vod());
            assertEquals(118, ordered.stream().filter(it -> it.vod().getSiteKey().equals("fallback")).count());
        }
    }

    @Test
    public void aLateFallbackCannotEvictAlreadyMatchingPreferredCandidates() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of("Dune"), "", null),
                "fallback", List.of("fallback", "priority"), "", List.of("priority"));
        List<Vod> early = new ArrayList<>();
        for (int i = 0; i < 120; i++) early.add(vod("priority", "alias-" + i, "Dune", ""));
        results.add(early);
        results.add(List.of(vod("fallback", "exact", "沙丘", ""), vod("priority", "wrong", "沙丘 / 影评", "")));

        assertEquals(120, results.snapshot(true).size());
        assertTrue(results.snapshot(true).stream().allMatch(it -> it.vod().getSiteKey().equals("priority")));
        Vod strongerSameSource = vod("priority", "exact", "沙丘", "");
        results.add(List.of(strongerSameSource));
        assertEquals(120, results.snapshot(true).size());
        assertSame(strongerSameSource, results.snapshot(true).get(0).vod());
    }

    @Test
    public void aLateFirstPriorityDisplacesSecondPriorityWhenNoFallbackRemains() {
        PosterSourceResults results = new PosterSourceResults(new SearchRelevance.Query("沙丘", List.of("Dune"), "", null),
                "second", List.of("second", "first"), "", List.of("first", "second"));
        List<Vod> early = new ArrayList<>();
        for (int i = 0; i < 120; i++) early.add(vod("second", "exact-" + i, "沙丘", ""));
        results.add(early);
        Vod first = vod("first", "late", "Dune", "");
        results.add(List.of(first));

        assertEquals(120, results.snapshot(true).size());
        assertSame(first, results.snapshot(true).get(0).vod());
        assertEquals(119, results.snapshot(true).stream().filter(it -> it.vod().getSiteKey().equals("second")).count());
    }

    @Test
    public void anEmptyExplicitPriorityRetainsLegacySortingAndBoundedAdmission() {
        SearchRelevance.Query query = new SearchRelevance.Query("沙丘", List.of("Dune"), "", null);
        PosterSourceResults legacy = new PosterSourceResults(query, "home", List.of("a", "home"), "movie");
        PosterSourceResults empty = new PosterSourceResults(query, "home", List.of("a", "home"), "movie", List.of());
        PosterSourceResults absent = new PosterSourceResults(query, "home", List.of("a", "home"), "movie", null);
        List<Vod> items = new ArrayList<>();
        for (int i = 0; i < 140; i++) items.add(vod(i % 2 == 0 ? "home" : "a", "alias-" + i, "Dune", ""));
        items.add(vod("home", "exact", "沙丘", ""));
        items.add(vod("home", "review", "沙丘 / 影评", ""));
        for (PosterSourceResults target : List.of(legacy, empty, absent)) target.add(items);

        for (boolean smart : new boolean[]{false, true}) {
            assertEquals(legacy.snapshot(smart), empty.snapshot(smart));
            assertEquals(legacy.snapshot(smart), absent.snapshot(smart));
        }
        assertEquals(legacy.hiddenCount(), empty.hiddenCount());
        assertEquals(legacy.hiddenCount(), absent.hiddenCount());
    }

    @Test
    public void explicitPriorityIsCopiedAndInvalidOrRepeatedKeysCannotReorderIt() {
        List<String> priority = new ArrayList<>(Arrays.asList("b", null, "", "a", "b", " "));
        PosterSourceResults results = new PosterSourceResults(SearchRelevance.Query.of("沙丘"), "a", List.of("a", "b"), "", priority);
        priority.clear();
        priority.add("a");
        results.add(List.of(vod("a", "1", "沙丘", ""), vod("b", "1", "沙丘", "")));

        assertEquals("b", results.snapshot(true).get(0).vod().getSiteKey());
        assertEquals("b", results.snapshot(false).get(0).vod().getSiteKey());
    }

    private static PosterSourceResults typedResults(String title, String type) {
        return new PosterSourceResults(SearchRelevance.Query.of(title), "", List.of(), type);
    }

    private static CandidateVod typedVod(String id, String title, String type) {
        CandidateVod vod = vod("source", id, title, "");
        vod.setTypeName(type);
        return vod;
    }

    private static PosterSourceResults results(String title) {
        return new PosterSourceResults(SearchRelevance.Query.of(title), "");
    }

    private static CandidateVod vod(String site, String id, String name, String year) {
        CandidateVod vod = new CandidateVod();
        vod.setSite(Site.get(site, site));
        vod.setId(id);
        vod.setName(name);
        vod.setYear(year);
        return vod;
    }

    /** Directory/action parsing is tested elsewhere; avoids Android TextUtils in local JVM tests. */
    private static final class CandidateVod extends Vod {
        boolean folder;
        boolean action;
        @Override public boolean isFolder() { return folder; }
        @Override public boolean isAction() { return action; }
    }
}
