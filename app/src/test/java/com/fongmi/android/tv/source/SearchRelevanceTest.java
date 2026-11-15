package com.fongmi.android.tv.source;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchRelevanceTest {
    @Test
    public void neverMatchesEmptyOrShorterUnrelatedTitlesByReverseContains() {
        assertFalse(match("庆余年", "庆").relevant());
        assertFalse(match("庆余年", "高清").relevant());
        assertFalse(match("庆余年", "").relevant());
        assertFalse(match("", "庆余年").relevant());
        assertFalse(match("", "").relevant());
    }

    @Test
    public void normalizesChineseScriptWidthSpacingAndResourceTags() {
        SearchRelevance.Match result = match("庆余年 第二季", "慶餘年２【國語】 1080p");
        assertTrue(result.strict());
        assertTrue(result.confident());
        assertTrue(match("斗破 苍穹", "斗破苍穹【中文字幕】").strict());
    }

    @Test
    public void distinguishesRelatedSeriesFromExactTitle() {
        SearchRelevance.Match result = match("斗破苍穹", "斗破苍穹 年番");
        assertTrue(result.relevant());
        assertFalse(result.strict());
        assertFalse(result.confident());
        assertFalse(match("斗破苍穹", "完美世界").relevant());
    }

    @Test
    public void englishMatchingUsesWordsAndKeepsRealLetters() {
        assertFalse(match("It", "Titanic").relevant());
        assertFalse(match("Matrix", "Matri").relevant());
        assertTrue(match("The Matrix", "The.Matrix [4K]").strict());
        assertTrue(match("The Matrix", "The Matrix Reloaded").relevant());
        assertFalse(match("The Matrix", "The Matrix Reloaded").strict());
        assertTrue(match("Spider-Man", "Spider Man").strict());
        assertTrue(match("It", "IT").strict());
    }

    @Test
    public void explicitYearsRejectRemakesButNumericalTitlesSurvive() {
        assertFalse(match("Dune (2021)", "Dune (1984)").relevant());
        assertTrue(match("Dune 2021", "Dune (2021) 4K").strict());
        assertTrue(match("1917", "1917").strict());
        assertFalse(match("1917", "任意电影").relevant());
        assertTrue(match("2001: A Space Odyssey", "2001 A Space Odyssey").strict());
    }

    @Test
    public void missingMetadataMayRemainVisibleButNeverClaimsConfidence() {
        SearchRelevance.Query query = new SearchRelevance.Query("沙丘", List.of("Dune"), "2021", null);
        SearchRelevance.Match missing = SearchRelevance.evaluate("Dune", "", query);
        assertTrue(missing.strict());
        assertFalse(missing.confident());
        assertTrue(SearchRelevance.evaluate("Dune", "2021", query).confident());
        assertFalse(SearchRelevance.evaluate("Dune", "1984", query).relevant());
    }

    @Test
    public void knownAliasesAndExplicitSourceAliasesCanMatchExactly() {
        SearchRelevance.Query query = new SearchRelevance.Query("黑客帝国", List.of("The Matrix"), "1999", null);
        assertTrue(SearchRelevance.evaluate("The.Matrix", "1999", query).confident());
        assertTrue(match("The Matrix", "黑客帝国 / The Matrix").strict());
        assertTrue(match("The Matrix", "黑客帝国（The Matrix）").strict());
        assertTrue(match("The Matrix", "黑客帝国 The Matrix").relevant());
        assertTrue(match("猛毒", "毒液（又名：猛毒）").strict());
        assertFalse(match("黑客帝国", "The Matrix").relevant());
    }

    @Test
    public void trailersAndFeaturettesAreNotConfirmedAsTheFeature() {
        assertFalse(match("庆余年", "庆余年（花絮）").strict());
        assertFalse(match("庆余年", "庆余年 / 解说").confident());
        assertFalse(match("The Matrix", "The Matrix (Trailer)").strict());
        assertTrue(match("Trailer Park Boys", "Trailer Park Boys").strict());
        assertFalse(match("Trailer Park Boys", "Trailer Park Boys / Review").titleMatch());
        assertTrue(SearchRelevance.evaluate("Trailer Park Boys", "", new SearchRelevance.Query("公园男孩", List.of("Trailer Park Boys"), "", null)).titleMatch());
    }

    @Test
    public void posterTitleEvidenceIsDistinctFromBroadSearchAndSeasonSelection() {
        SearchRelevance.Match allSeasons = match("庆余年", "庆余年 第二季");
        assertTrue(allSeasons.titleMatch());
        assertFalse(allSeasons.strict());
        SearchRelevance.Query constrained = new SearchRelevance.Query("庆余年", List.of(), "2024", 2);
        SearchRelevance.Match broad = SearchRelevance.evaluate("庆余年 第二季 主创见面会", "2024", constrained);
        assertEquals(65, broad.score());
        assertTrue(broad.relevant());
        assertFalse(broad.titleMatch());
        SearchRelevance.Match supplement = SearchRelevance.evaluate("庆余年 第二季 / 影评", "2024", constrained);
        assertEquals(45, supplement.score());
        assertTrue(supplement.relevant());
        assertFalse(supplement.titleMatch());
    }

    @Test
    public void seasonsMatchOnlyWhenNotContradictingExplicitRequest() {
        assertTrue(match("庆余年2", "庆余年 第二季").strict());
        assertTrue(match("Planet Earth II", "Planet Earth Season 2").strict());
        assertFalse(match("庆余年2", "庆余年 第一季").relevant());
        assertFalse(match("Planet Earth II", "Planet Earth S01").relevant());
        assertTrue(match("庆余年", "庆余年 第二季").relevant());
        assertFalse(match("庆余年", "庆余年 第二季").strict());
        assertFalse(match("庆余年", "庆余年 第二季").confident());
    }

    @Test
    public void strictSearchDoesNotSilentlyChooseNumberedMovieSequels() {
        assertTrue(match("Rocky", "Rocky II").relevant());
        assertFalse(match("Rocky", "Rocky II").strict());
        assertFalse(match("Rocky", "Rocky 2").strict());
        assertFalse(match("Alien", "Alien 2").strict());
        assertFalse(match("The Matrix", "The Matrix II").strict());
        assertTrue(match("Rocky II", "Rocky 2").strict());
        assertFalse(match("Rocky II", "Rocky").strict());
        assertFalse(match("庆余年2", "庆余年").strict());
        assertFalse(match("Rocky II", "Rocky III").relevant());
        assertTrue(match("The Matrix", "The Matrix").strict());
    }

    @Test
    public void oneCharacterQueriesNeedAnExactTitle() {
        assertFalse(match("爱", "因为爱情有幸福").relevant());
        assertTrue(match("爱", "爱").strict());
        assertFalse(match("X", "Matrix").relevant());
        assertTrue(match("X", "X").strict());
    }

    @Test
    public void metadataMakesExactCandidatesRankAboveRelatedOnes() {
        SearchRelevance.Query query = new SearchRelevance.Query("庆余年", List.of(), "2024", 2);
        assertEquals(100, SearchRelevance.evaluate("庆余年 第二季", "2024", query).score());
        assertFalse(SearchRelevance.evaluate("庆余年 第一季", "2024", query).relevant());
        assertFalse(SearchRelevance.evaluate("庆余年 第二季", "2019", query).relevant());
    }

    @Test public void shouldReusePreparedQueriesWithoutLosingBracketAliasesOrSupplementEvidence() {
        List<SearchRelevance.Query> queries = List.of(
                new SearchRelevance.Query("黑客帝国", List.of("The Matrix"), "1999", null),
                new SearchRelevance.Query("公园男孩", List.of("Trailer Park Boys"), "", null),
                new SearchRelevance.Query("庆余年", List.of("Joy of Life"), "2024", 2));
        var preparedQueries = queries.stream().map(SearchRelevance::prepareQuery).toList();
        for (String title : List.of("黑客帝国（The Matrix）", "黑客帝国 / The Matrix", "The Matrix / Trailer",
                "Trailer Park Boys", "Trailer Park Boys / Review", "庆余年 第二季", "Joy of Life S02", "庆余年 第一季")) {
            var result = SearchRelevance.prepareResult(title, "2024");
            for (int index = 0; index < queries.size(); index++) assertEquals(
                    SearchRelevance.evaluate(title, "2024", queries.get(index)),
                    SearchRelevance.evaluatePrepared(result, preparedQueries.get(index)));
        }
    }

    private static SearchRelevance.Match match(String query, String title) {
        return SearchRelevance.evaluate(title, "", SearchRelevance.Query.of(query));
    }
}
