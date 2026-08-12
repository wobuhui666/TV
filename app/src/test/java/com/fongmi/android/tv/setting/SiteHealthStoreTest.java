package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SiteHealthStoreTest {

    @Test
    public void normalizesPlaybackHistoryKeyToSiteKey() {
        assertEquals("demo", SiteHealthStore.normalizeSiteKey("demo@@@vod-1@@@8"));
        assertEquals("demo", SiteHealthStore.normalizeSiteKey("demo"));
    }

    @Test
    public void usesSpecifiedWeightsRewardsPenaltiesAndLatestPlayCorrection() {
        double score = SiteHealthStore.calculateScore(2, 1, 1, 0, 1, 0, 10, 2_000, 3_000, 1);
        double expected = 60.0 * (9 - 1) / (9 + 1 + 4) + 12 - 2 - 2 + 18;
        assertEquals(expected, score, 0.0001);
    }

    @Test
    public void clientOnlyPlaybackResultDoesNotEnterFormula() {
        double score = SiteHealthStore.calculateScore(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0, score, 0.0);
    }
}
