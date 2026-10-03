package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FeaturedPosterCacheTest {

    @Test
    public void shouldRejectAnImageFromThePreviousFeaturedList() {
        FeaturedPosterCache cache = new FeaturedPosterCache();
        Vod first = item("First");
        cache.prepare(List.of(first));
        String previous = cache.getSignature();
        cache.prepare(List.of(item("Second")));

        cache.put(previous, first, "https://image/old");

        assertFalse(cache.isCurrent(previous));
        assertNull(cache.get(first));
    }

    @Test
    public void shouldKeepKnownArtworkAcrossReorderingWithoutTouchingDisk() {
        FeaturedPosterCache cache = new FeaturedPosterCache();
        Vod first = item("First");
        Vod second = item("Second");
        assertTrue(cache.prepare(List.of(first, second)));
        cache.put(cache.getSignature(), first, "https://image/first");

        assertFalse(cache.prepare(List.of(second, first)));
        assertEquals("https://image/first", cache.get(first));
    }

    @Test
    public void shouldBoundMetadataAndRetainRecentlyUsedArtwork() {
        FeaturedPosterCache cache = new FeaturedPosterCache();
        for (int i = 0; i < 128; i++) cache.put(cache.getSignature(), item("Movie " + i), "https://image/" + i);
        cache.get(item("Movie 0"));
        cache.put(cache.getSignature(), item("New"), "https://image/new");

        assertEquals("https://image/0", cache.get(item("Movie 0")));
        assertNull(cache.get(item("Movie 1")));
        assertEquals("https://image/new", cache.get(item("New")));
    }

    @Test
    public void shouldEvictAFailedImageAndAllowItsSourceFallback() {
        FeaturedPosterCache cache = new FeaturedPosterCache();
        Vod item = item("First");
        cache.put(cache.getSignature(), item, "https://image/missing");

        cache.invalidate(item, "https://image/missing");

        assertNull(cache.get(item));
    }

    @Test
    public void shouldNotRemoveNewArtworkWhenAnOlderDownloadFails() {
        FeaturedPosterCache cache = new FeaturedPosterCache();
        Vod item = item("First");
        cache.put(cache.getSignature(), item, "https://image/new");

        cache.invalidate(item, "https://image/old");

        assertEquals("https://image/new", cache.get(item));
    }

    private static Vod item(String name) {
        Vod item = new Vod();
        item.setName(name);
        item.setYear("2026");
        item.setTypeName("movie");
        return item;
    }
}
