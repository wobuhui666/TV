package com.fongmi.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.*;

public class SubtitleArchiveTest {

    @Test public void shouldAcceptUploadedVttAndExistingSubtitleFormatsCaseInsensitively() {
        for (String file : new String[]{"caption.vtt", "字幕.VTT", "/upload/subtitle.SrT", "caption.SSA", "caption.ass", "caption#cut?.vtt"}) {
            assertTrue(file, SubtitleArchive.isSubtitle(file));
        }
    }

    @Test public void shouldKeepConfigurationAndNonSubtitleUploadsOnTheirExistingRoute() {
        for (String file : new String[]{"config.json", "config.xml", "picture.png", "caption.srt.zip", "caption.vtt ", "movie.mp4"}) {
            assertFalse(file, SubtitleArchive.isSubtitle(file));
        }
        assertFalse(SubtitleArchive.isSubtitle(null));
    }
}
