package com.fongmi.android.tv.model;

import com.fongmi.android.tv.bean.Site;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class DiscoverSourceSearchTest {
    @Test
    public void preferredSourcesQueueAllAliasesBeforeKeywordFirstFallbacks() {
        List<Site> sites = List.of(site("a"), site("b"), site("c"), site("d"));
        List<DiscoverSourceSearch.Query> queries = DiscoverSourceSearch.queries(sites,
                List.of(" title ", "alias", "title", "third", "fourth"), List.of("c", "a", "c", "missing"));

        assertEquals(List.of("c/title", "c/alias", "c/third", "a/title", "a/alias", "a/third",
                "b/title", "d/title", "b/alias", "d/alias", "b/third", "d/third"), keys(queries));
    }

    @Test
    public void emptyOrUnavailablePriorityKeepsTheOriginalKeywordFirstOrder() {
        List<Site> sites = List.of(site("c"), site("a"), site("b"));
        List<String> expected = List.of("c/title", "a/title", "b/title", "c/alias", "a/alias", "b/alias");

        for (List<String> priorities : Arrays.asList(null, List.<String>of(), List.of("deleted"))) {
            assertEquals(expected, keys(DiscoverSourceSearch.queries(sites, List.of("title", "alias"), priorities)));
        }
    }

    @Test
    public void repeatedOrDisabledSitesNeverGenerateDuplicateRequests() {
        Site firstA = site("a");
        Site disabled = site("disabled");
        disabled.setSearchable(2);
        List<Site> sites = Arrays.asList(firstA, null, site("a"), disabled, site(" "), site("c"));
        List<DiscoverSourceSearch.Query> queries = DiscoverSourceSearch.queries(sites,
                Arrays.asList(null, " ", "title", "title", "alias"), Arrays.asList("disabled", null, "c", "c", "deleted"));

        assertEquals(List.of("c/title", "c/alias", "a/title", "a/alias"), keys(queries));
        assertSame(firstA, queries.get(2).site());
    }

    @Test
    public void buildingTheQueueDoesNotMutateConfigurationOrCallerLists() {
        List<Site> sites = new ArrayList<>(List.of(site("a"), site("b")));
        List<Site> before = new ArrayList<>(sites);
        List<String> keywords = new ArrayList<>(List.of("title", "alias"));
        List<String> priorities = new ArrayList<>(List.of("b"));
        List<DiscoverSourceSearch.Query> queries = DiscoverSourceSearch.queries(sites, keywords, priorities);

        assertEquals(before, sites);
        assertEquals(List.of("title", "alias"), keywords);
        assertEquals(List.of("b"), priorities);
        priorities.clear();
        keywords.clear();
        sites.clear();
        assertEquals(List.of("b/title", "b/alias", "a/title", "a/alias"), keys(queries));
    }

    @Test
    public void missingSitesOrUsableKeywordsProduceNoWork() {
        assertTrue(DiscoverSourceSearch.queries(null, List.of("title"), List.of("a")).isEmpty());
        assertTrue(DiscoverSourceSearch.queries(List.of(site("a")), null, List.of("a")).isEmpty());
        assertTrue(DiscoverSourceSearch.queries(List.of(site("a")), Arrays.asList(null, "", " "), List.of("a")).isEmpty());
    }

    private static Site site(String key) { return Site.get(key, key); }

    private static List<String> keys(List<DiscoverSourceSearch.Query> queries) {
        return queries.stream().map(query -> query.site().getKey() + "/" + query.keyword()).toList();
    }
}
