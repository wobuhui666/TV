package com.fongmi.android.tv.player.mpv;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public class MpvLoadFileTest {
    @Test public void localResumeUsesIntegerIndexBeforePerFileOptions() {
        String url = "file:///data/user/0/fixture/有空格 video.mp4";
        String[] command = MpvLoadFile.command(url, 8_000);
        assertArrayEquals(new String[]{"loadfile", url, "replace", "-1", "start=8.000"}, command);
        assertEquals(-1, Integer.parseInt(command[3]));
        assertFalse(MpvLoadFile.deferInitialSeek(url));
    }
    @Test public void opaqueHttpAndLocalPlaylistsKeepTheirPostLoadSeek() {
        for (String url : new String[]{"https://media.test/file.mp4", "HTTP://media.test/stream", "https://media.test/resolve?name=stream.m3u8", "file:///cache/PLAYLIST.M3U8"}) {
            assertTrue(MpvLoadFile.deferInitialSeek(url));
            assertArrayEquals(new String[]{"loadfile", url, "replace"}, MpvLoadFile.command(url, 8_000));
        }
    }
    @Test public void networkPlaylistPassesResumeToNativeDemuxerAtLoadTime() {
        String url = "HTTPS://media.test/movie/INDEX.M3U8?quality=high#t";
        assertTrue(MpvLoadFile.networkPlaylist(url));
        assertFalse(MpvLoadFile.deferInitialSeek(url));
        assertArrayEquals(new String[]{"loadfile", url, "replace", "-1", "start=68.125"},
                MpvLoadFile.command(url, 68_125));
        assertArrayEquals(new String[]{"loadfile", url, "replace"}, MpvLoadFile.command(url, 0));
    }

    @Test public void startAtZeroAndUnsetPositionDoNotNeedOptionalArguments() {
        for (long position : new long[]{0, -1, Long.MIN_VALUE + 1})
            assertArrayEquals(new String[]{"loadfile", "/data/local.mp4", "replace"}, MpvLoadFile.command("/data/local.mp4", position));
    }
    @Test public void millisecondsAndDecimalSeparatorAreIndependentOfDeviceLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("start=8.123", MpvLoadFile.command("/data/local.mp4", 8_123)[4]);
        } finally { Locale.setDefault(original); }
    }
}
