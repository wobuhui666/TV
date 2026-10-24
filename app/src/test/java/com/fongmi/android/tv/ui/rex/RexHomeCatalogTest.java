package com.fongmi.android.tv.ui.rex;

import com.fongmi.android.tv.bean.RexSection;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RexHomeCatalogTest {

    private static Vod vod(String id, String type, boolean landscape) {
        Vod item = new Vod();
        item.setId(id);
        item.setName(id);
        item.setTypeName(type);
        item.setPic("poster:" + id);
        item.setBackdrop(landscape ? "backdrop:" + id : "poster:" + id);
        return item;
    }

    private static RexSection section(String key, int limit) {
        return RexSection.regional(key, 0, "chinese", "series").limit(limit);
    }

    @Test
    public void spotlightsPickLandscapeFirst() {
        RexFeedResult result = new RexFeedResult(section("a", 20), List.of(vod("1", "tv", false), vod("2", "tv", true)));
        List<RexFeedResult> spots = RexHomeCatalog.spotlights(List.of(result));
        assertEquals(1, spots.size());
        assertEquals("2", spots.get(0).getItems().get(0).getId());
        assertEquals("1", spots.get(0).getItems().get(1).getId());
        assertEquals("1", result.getItems().get(0).getId());
    }

    @Test
    public void spotlightsFallBackToFirstWhenNoLandscape() {
        RexFeedResult result = new RexFeedResult(section("a", 20), List.of(vod("1", "tv", false)));
        assertEquals("1", RexHomeCatalog.spotlights(List.of(result)).get(0).getItems().get(0).getId());
    }

    @Test
    public void spotlightsSkipEmptySections() {
        assertTrue(RexHomeCatalog.spotlights(List.of(new RexFeedResult(section("a", 20), List.of()))).isEmpty());
        assertTrue(RexHomeCatalog.spotlights(null).isEmpty());
    }

    @Test
    public void sectionPreviewsDeduplicateAndCapWithoutMixingSections() {
        List<Vod> items = new ArrayList<>();
        items.add(null);
        items.add(vod("", "tv", true));
        for (int i = 0; i < 30; i++) items.add(vod("a" + i, "tv", i == 20));
        items.add(vod("a0", "tv", true));
        List<RexFeedResult> cards = RexHomeCatalog.spotlights(List.of(
                new RexFeedResult(section("a", 20), items),
                new RexFeedResult(section("b", 2), List.of(vod("b0", "tv", true), vod("b1", "tv", true), vod("b2", "tv", true)))));
        assertEquals(RexHomeCatalog.SECTION_PREVIEW_CAP, cards.get(0).getItems().size());
        assertEquals("a20", cards.get(0).getItems().get(0).getId());
        assertEquals("a0", cards.get(0).getItems().get(1).getId());
        assertEquals(2, cards.get(1).getItems().size());
        assertTrue(cards.get(1).getItems().stream().allMatch(item -> item.getId().startsWith("b")));
    }

    @Test
    public void sectionDiffTracksSmallCardMetadataAndArtwork() {
        RexSection section = section("a", 12);
        Vod hero = vod("hero", "tv", true);
        Vod original = vod("item", "tv", true);
        RexFeedResult before = new RexFeedResult(section, List.of(hero, original));
        Vod updated = vod("item", "tv", true);
        assertTrue(before.isSameContent(new RexFeedResult(section, List.of(hero, updated))));
        updated.setYear("2030");
        org.junit.Assert.assertFalse(before.isSameContent(new RexFeedResult(section, List.of(hero, updated))));
        updated.setYear("");
        updated.setBackdrop("new-backdrop");
        org.junit.Assert.assertFalse(before.isSameContent(new RexFeedResult(section, List.of(hero, updated))));
        updated.setYear(null);
        updated.setBackdrop("backdrop:item");
        updated.setContent("<p>updated description</p>");
        org.junit.Assert.assertFalse(before.isSameContent(new RexFeedResult(section, List.of(hero, updated))));
        org.junit.Assert.assertFalse(before.isSameContent(new RexFeedResult(section, List.of(hero))));
    }

    @Test
    public void mixedConcatenatesInSectionOrderWithPerSectionCap() {
        List<Vod> a = new ArrayList<>();
        for (int i = 0; i < 30; i++) a.add(vod("a" + i, "tv", true));
        List<Vod> b = new ArrayList<>();
        for (int i = 0; i < 30; i++) b.add(vod("b" + i, "movie", true));
        List<Vod> mixed = RexHomeCatalog.mixed(List.of(
                new RexFeedResult(section("a", 20), a),
                new RexFeedResult(section("b", 5), b)));
        assertEquals(25, mixed.size());
        assertEquals("a0", mixed.get(0).getId());
        assertEquals("a19", mixed.get(19).getId());
        assertEquals("b0", mixed.get(20).getId());
        assertEquals("b4", mixed.get(24).getId());
    }

    @Test
    public void mixedCapsTotalAtHundred() {
        List<RexFeedResult> results = new ArrayList<>();
        for (int s = 0; s < 6; s++) {
            List<Vod> items = new ArrayList<>();
            for (int i = 0; i < 20; i++) items.add(vod("s" + s + "i" + i, "tv", true));
            results.add(new RexFeedResult(section("s" + s, 20), items));
        }
        assertEquals(100, RexHomeCatalog.mixed(results).size());
    }

    @Test
    public void mixedDedupesAndSkipsBlankIds() {
        List<Vod> mixed = RexHomeCatalog.mixed(List.of(
                new RexFeedResult(section("a", 20), List.of(vod("x", "tv", true), vod("", "tv", true))),
                new RexFeedResult(section("b", 20), List.of(vod("x", "tv", true), vod("y", "tv", true)))));
        assertEquals(2, mixed.size());
        assertEquals("x", mixed.get(0).getId());
        assertEquals("y", mixed.get(1).getId());
    }
}
