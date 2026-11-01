package com.fongmi.android.tv.player.subtitle;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.DataReader;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleExtractor;
import androidx.media3.extractor.text.webvtt.WebvttParser;

import com.fongmi.android.tv.player.util.PlayerHelper;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LocalSubtitleOriginTest {

    @Test public void shouldRecognizeDeviceFilesAndDocumentsWithoutTreatingHttpProxiesAsLocalFiles() {
        for (String url : new String[]{"/storage/USB/My Movie.zh.srt", "file:///storage/USB/Movie.vtt",
                "file:/storage/emulated/0/Movie.ass", "content://documents/tree/subtitle"}) {
            assertTrue(url, LocalSubtitleOrigin.isLocalUrl(url));
        }
        for (String url : new String[]{"https://example.invalid/Movie.srt", "http://127.0.0.1/subtitle.vtt",
                "//server/Movie.srt", "ftp://example.invalid/Movie.srt", "Movie.srt", ""}) {
            assertFalse(url, LocalSubtitleOrigin.isLocalUrl(url));
        }
        assertFalse(LocalSubtitleOrigin.isLocalUrl(null));
    }

    @Test public void shouldKeepSameNamedEmbeddedAndRemoteTracksUnmarkedAndPreserveSavedSelectionDescriptors() {
        Format embedded = format();
        Format remote = embedded.buildUpon().setMetadata(LocalSubtitleOrigin.forUrl("https://example.invalid/Movie.vtt")).build();
        Format local = embedded.buildUpon().setMetadata(LocalSubtitleOrigin.forUrl("file:///storage/USB/Movie.vtt")).build();

        assertFalse(LocalSubtitleOrigin.isLocal(embedded));
        assertFalse(LocalSubtitleOrigin.isLocal(remote));
        assertTrue(LocalSubtitleOrigin.isLocal(local));
        assertEquals(PlayerHelper.describeFormat(embedded), PlayerHelper.describeFormat(local));
        assertEquals(PlayerHelper.describeFormat(remote), PlayerHelper.describeFormat(local));
        assertEquals(embedded.id, local.id);
        assertEquals(embedded.label, local.label);
        assertEquals(embedded.selectionFlags, local.selectionFlags);
    }

    @Test public void shouldCarryTheMarkerThroughTheRealVttExtractorWithoutChangingSelectionDescription() {
        Format ordinary = extracted(format());
        Format local = extracted(format().buildUpon()
                .setMetadata(LocalSubtitleOrigin.forUrl("file:///storage/USB/Movie.vtt")).build());

        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, local.sampleMimeType);
        assertTrue(LocalSubtitleOrigin.isLocal(local));
        assertFalse(LocalSubtitleOrigin.isLocal(ordinary));
        assertEquals(PlayerHelper.describeFormat(ordinary), PlayerHelper.describeFormat(local));
    }

    private static Format format() {
        return new Format.Builder().setId("subtitle-original-id").setLabel("Movie.vtt")
                .setLanguage("zh").setSampleMimeType(MimeTypes.TEXT_VTT)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
    }

    private static Format extracted(Format format) {
        AtomicReference<Format> emitted = new AtomicReference<>();
        SubtitleExtractor extractor = new SubtitleExtractor(new WebvttParser(), format);
        extractor.init(new ExtractorOutput() {
            @Override public TrackOutput track(int id, int type) {
                return new TrackOutput() {
                    @Override public void format(Format output) { emitted.set(output); }
                    @Override public int sampleData(DataReader input, int length, boolean allowEnd, int part) { throw new AssertionError("Unexpected read"); }
                    @Override public void sampleData(ParsableByteArray input, int length, int part) { throw new AssertionError("Unexpected sample"); }
                    @Override public void sampleMetadata(long time, int flags, int size, int offset, CryptoData crypto) { throw new AssertionError("Unexpected sample"); }
                };
            }
            @Override public void endTracks() {}
            @Override public void seekMap(SeekMap map) {}
        });
        extractor.release();
        assertNotNull(emitted.get());
        return emitted.get();
    }
}
