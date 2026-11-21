package com.fongmi.android.tv.player.subtitle;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.github.peerless2012.ass.Ass;
import io.github.peerless2012.ass.AssFrame;
import io.github.peerless2012.ass.AssRender;
import io.github.peerless2012.ass.AssTex;
import io.github.peerless2012.ass.AssTexType;
import io.github.peerless2012.ass.AssTrack;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AssFrameCacheAndroidTest {
    @Test public void positionOnlyUpdatesKeepTheNativeFrameAndItsFreshPixels() {
        AssFrameCache cache = new AssFrameCache();
        AssFrame first = frame(2, 10, 20, 0xffffffff);
        AssFrame moved = frame(1, 30, 40, 0xffffffff);
        assertTrue(cache.update(first));
        assertTrue(cache.update(moved));
        AssTex result = cache.getFrame().getImages()[0];
        assertSame(moved, cache.getFrame());
        assertSame(moved.getImages()[0].getBitmap(), result.getBitmap());
        assertEquals(30, result.getX());
        assertEquals(40, result.getY());
        assertNotSame(first.getImages()[0].getBitmap(), result.getBitmap());
        assertFalse(first.getImages()[0].getBitmap().isRecycled());
        assertFalse(result.getBitmap().isRecycled());
    }

    @Test public void changedContentInstallsNewPixels() {
        AssFrameCache cache = new AssFrameCache();
        cache.update(frame(2, 10, 20, 0xffffffff));
        AssFrame changed = frame(2, 10, 20, 0xffffffff);
        assertTrue(cache.update(changed));
        assertSame(changed, cache.getFrame());
    }

    @Test public void colorAndSizeUpdatesForwardAllNativeData() {
        AssFrameCache cache = new AssFrameCache();
        cache.update(frame(2, 10, 20, 0xffffffff));
        AssFrame colored = frame(1, 10, 20, 0xff00ffff);
        cache.update(colored);
        assertSame(colored, cache.getFrame());
        AssTex resized = new AssTex(10, 20, 8, 8, 0xff00ffff,
                Bitmap.createBitmap(8, 8, Bitmap.Config.ALPHA_8), 0);
        AssFrame shaped = new AssFrame(new AssTex[]{resized}, 1);
        cache.update(shaped);
        assertSame(shaped, cache.getFrame());
    }

    @Test public void blankIntervalsClearOnceAndCanBecomeVisibleAgain() {
        AssFrameCache cache = new AssFrameCache();
        assertFalse(cache.update(null));
        AssFrame first = frame(2, 0, 0, 0xffffffff);
        assertTrue(cache.update(first));
        assertFalse(cache.update(new AssFrame(null, 0)));
        assertSame(first, cache.getFrame());
        assertTrue(cache.update(null));
        assertNull(cache.getFrame());
        assertFalse(cache.update(null));
        AssFrame next = frame(1, 10, 10, 0xffffffff);
        assertTrue(cache.update(next));
        assertSame(next, cache.getFrame());
    }

    @Test public void clearDropsTheFrameWithoutRecyclingPreviouslyDisplayedPixels() {
        AssFrameCache cache = new AssFrameCache();
        AssFrame first = frame(2, 10, 20, 0xffffffff);
        cache.update(first);
        cache.clear();
        assertNull(cache.getFrame());
        assertFalse(first.getImages()[0].getBitmap().isRecycled());
        AssFrame seek = frame(2, 10, 20, 0xffffffff);
        assertTrue(cache.update(seek));
        assertSame(seek, cache.getFrame());
    }

    @Test public void nativeAnimationContentAndBlankTransitionsReachTheOutput() throws Exception {
        Ass ass = new Ass();
        AssRender renderer = null;
        AssTrack track = null;
        try {
            File font = new File("/system/fonts/Roboto-Regular.ttf");
            assertTrue("Android test image must provide Roboto", font.isFile());
            ass.addFont(font.getName(), Files.readAllBytes(font.toPath()));
            renderer = ass.createRender();
            track = ass.createTrack();
            byte[] script = ("[Script Info]\nScriptType: v4.00+\nPlayResX: 640\nPlayResY: 360\n"
                    + "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, "
                    + "OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, "
                    + "Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
                    + "Style: Default,Roboto,36,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,7,0,0,0,1\n"
                    + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                    + "Dialogue: 0,0:00:00.00,0:00:03.00,Default,,0,0,0,,{\\move(100,100,400,100,0,3000)}Moving subtitle\n")
                    .getBytes(StandardCharsets.UTF_8);
            track.readBuffer(script, 0, script.length);
            renderer.setTrack(track);
            renderer.setFrameSize(640, 360);
            renderer.setStorageSize(640, 360);
            AssFrameCache cache = new AssFrameCache();
            AssFrame first = renderer.renderFrame(100, AssTexType.BITMAP_ALPHA);
            assertNotNull(first);
            assertTrue(cache.update(first));
            AssFrame unchanged = renderer.renderFrame(100, AssTexType.BITMAP_ALPHA);
            assertNotNull(unchanged);
            assertEquals(0, unchanged.getChanged());
            assertFalse(cache.update(unchanged));
            assertSame(first, cache.getFrame());

            AssFrame moved = renderer.renderFrame(200, AssTexType.BITMAP_ALPHA);
            assertNotNull(moved);
            assertEquals("libass must identify pure movement", 1, moved.getChanged());
            assertTrue(cache.update(moved));
            assertSame(moved, cache.getFrame());
            boolean movedPosition = false;
            for (int i = 0; i < moved.getImages().length; i++) {
                AssTex image = moved.getImages()[i];
                movedPosition |= first.getImages()[i].getX() != image.getX();
                if (image.getBitmap() != null) assertFalse(image.getBitmap().isRecycled());
                if (first.getImages()[i].getBitmap() != null) assertFalse(first.getImages()[i].getBitmap().isRecycled());
            }
            assertTrue(movedPosition);
            renderer.setFontScale(1.25f);
            AssFrame resized = renderer.renderFrame(200, AssTexType.BITMAP_ALPHA);
            assertEquals(2, resized.getChanged());
            assertTrue(cache.update(resized));
            assertSame(resized, cache.getFrame());

            AssFrame blank = renderer.renderFrame(4_000, AssTexType.BITMAP_ALPHA);
            assertNull(blank);
            assertTrue(cache.update(blank));
            assertNull(cache.getFrame());
            assertFalse(cache.update(renderer.renderFrame(4_100, AssTexType.BITMAP_ALPHA)));
            AssFrame seek = renderer.renderFrame(100, AssTexType.BITMAP_ALPHA);
            assertNotNull(seek);
            assertTrue(cache.update(seek));
            assertSame(seek, cache.getFrame());
        } finally {
            if (renderer != null) renderer.release();
            if (track != null) track.release();
            ass.release();
        }
    }

    private static AssFrame frame(int changed, int x, int y, int color) {
        return new AssFrame(new AssTex[]{new AssTex(x, y, 4, 4, color,
                Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8), 0)}, changed);
    }
}
