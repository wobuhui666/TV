package com.fongmi.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WallColorUtilTest {

    @Test
    public void shouldKeepBuiltInWallpaperSeedsWithoutOpeningCache() {
        assertEquals(0xFF40C090, WallColorUtil.getColor(1, 0, null));
        assertEquals(0xFF4870E0, WallColorUtil.getColor(2, 0, null));
        assertEquals(0xFF48B0C0, WallColorUtil.getColor(3, 0, null));
        assertEquals(0xFF404040, WallColorUtil.getColor(4, 0, null));
    }

    @Test
    public void shouldUseOriginalFallbackWhenCustomWallpaperHasNoSnapshot() {
        assertEquals(0xFF40C090, WallColorUtil.getColor(0, 0, null));
        assertEquals(0xFF40C090, WallColorUtil.getColor(0, 1, null));
        assertEquals(0xFF40C090, WallColorUtil.getColor(0, 2, null));
    }

    @Test
    public void shouldNotMistakeGifOrVideoForBuiltInWallpaper() {
        assertEquals(0xFF40C090, WallColorUtil.getColor(2, 1, null));
        assertEquals(0xFF40C090, WallColorUtil.getColor(4, 2, null));
    }
}
