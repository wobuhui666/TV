package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaMatcherTest {

    @Test
    public void mergesArabicAndChineseSeasonTitles() {
        assertTrue(MediaMatcher.sameMedia(vod("庆余年2", "", "电视剧"), vod("慶餘年 第二季", "", "电视剧")));
    }

    @Test
    public void normalizesTraditionalFullWidthAndRomanSeason() {
        assertEquals(MediaMatcher.identity("庆余年2"), MediaMatcher.identity("慶餘年Ⅱ"));
    }

    @Test
    public void doesNotMergeDifferentYears() {
        assertFalse(MediaMatcher.sameMedia(vod("流浪地球", "2019", "电影"), vod("流浪地球", "2023", "电影")));
    }

    @Test
    public void doesNotMergeMovieAndSeries() {
        assertFalse(MediaMatcher.sameMedia(vod("三体", "2023", "电视剧"), vod("三体", "2023", "电影")));
    }

    @Test
    public void doesNotMergeDifferentSeasons() {
        assertFalse(MediaMatcher.sameMedia(vod("庆余年1", "", "电视剧"), vod("庆余年2", "", "电视剧")));
    }

    @Test
    public void queryMatchesFuzzySeasonAndNoise() {
        assertTrue(MediaMatcher.queryMatches("庆余年2", vod("庆余年 第二季 4K 完结", "", "电视剧")));
    }

    private static Vod vod(String name, String year, String type) {
        Vod vod = new Vod();
        vod.setName(name);
        vod.setYear(year);
        vod.setTypeName(type);
        vod.setSite(Site.get(name + year + type, "test"));
        return vod;
    }
}
