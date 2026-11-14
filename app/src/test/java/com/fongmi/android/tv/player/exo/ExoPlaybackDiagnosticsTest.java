package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime;
import androidx.media3.exoplayer.audio.AudioSink;

import com.github.catvod.crawler.diagnostics.DiagnosticLog;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

public class ExoPlaybackDiagnosticsTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private final ExoPlaybackDiagnostics listener = new ExoPlaybackDiagnostics();
    private final EventTime time = new EventTime(8_000, Timeline.EMPTY, 0, null, 12_000,
            Timeline.EMPTY, 0, null, 12_000, 45_000);

    @Before public void stopBefore() { DiagnosticLog.stop(); }
    @After public void stopAfter() { DiagnosticLog.stop(); }

    private String logs() throws Exception {
        StringBuilder result = new StringBuilder();
        try (ZipFile zip = new ZipFile(DiagnosticLog.exportZip())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".log")) continue;
                try (var stream = zip.getInputStream(entry)) {
                    result.append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return result.toString();
    }

    private AudioSink.AudioTrackConfig output() {
        return new AudioSink.AudioTrackConfig(C.ENCODING_PCM_16BIT, 48_000, 12, false, false, 96_000);
    }

    @Test
    public void shouldIgnoreDisabledEventsWithoutReadingEventTimeOrErrors() throws Exception {
        listener.onAudioUnderrun(null, 96_000, 500, 700);
        listener.onDroppedVideoFrames(null, 12, 1_000);
        listener.onAudioSinkError(null, null);
        listener.onAudioCodecError(null, null);
        DiagnosticLog.start(folder.getRoot());
        assertFalse(logs().contains("exo-audio"));
    }

    @Test
    public void shouldRetainOutputContextWhenRecordingStartsDuringPlayback() throws Exception {
        listener.onAudioDecoderInitialized(null, "c2.vendor.aac.decoder", 0, 12);
        listener.onAudioInputFormatChanged(null, new Format.Builder().setSampleMimeType("audio/mp4a-latm")
                .setSampleRate(48_000).setChannelCount(2).setId("https://private.invalid/token").build(), null);
        listener.onAudioTrackInitialized(null, output());
        DiagnosticLog.start(folder.getRoot());
        listener.onAudioUnderrun(time, 96_000, 500, 700);
        String log = logs();
        assertTrue(log.contains("audioUnderrun"));
        assertTrue(log.contains("decoder=c2.vendor.aac.decoder"));
        assertTrue(log.contains("mime=audio/mp4a-latm"));
        assertTrue(log.contains("positionMs=12000"));
        assertTrue(log.contains("mediaBufferedDurationMs=45000"));
        assertTrue(log.contains("bufferDurationMs=500"));
        assertTrue(log.contains("elapsedSinceLastFeedMs=700"));
        assertTrue(log.contains("lastOutputBufferBytes=96000"));
        assertFalse(log.contains("private.invalid"));
    }

    @Test
    public void shouldKeepUnknownAudioDurationSeparateFromMediaBuffer() throws Exception {
        DiagnosticLog.start(folder.getRoot());
        listener.onAudioUnderrun(time, 48_000, C.TIME_UNSET, 300);
        String log = logs();
        assertTrue(log.contains("bufferDurationMs=TIME_UNSET"));
        assertTrue(log.contains("mediaBufferedDurationMs=45000"));
        assertTrue(log.contains("lastOutput=unknown"));
    }

    @Test
    public void shouldClearPreviousTrackWhenAudioIsDisabled() throws Exception {
        listener.onAudioTrackInitialized(null, output());
        listener.onAudioInputFormatChanged(null, new Format.Builder().setSampleMimeType("audio/previous").build(), null);
        listener.onAudioDisabled(null, new DecoderCounters());
        DiagnosticLog.start(folder.getRoot());
        listener.onAudioUnderrun(time, 0, C.TIME_UNSET, 300);
        String log = logs();
        assertTrue(log.contains("lastOutput=unknown"));
        assertTrue(log.contains("mime=unknown"));
        assertFalse(log.contains("audio/previous"));
    }

    @Test
    public void shouldRecordCodecSinkAndVideoEventsWithRedaction() throws Exception {
        DiagnosticLog.start(folder.getRoot());
        listener.onAudioCodecError(time, new IllegalStateException("https://host.invalid/a?token=PRIVATE_TOKEN"));
        listener.onAudioSinkError(time, new IllegalArgumentException("AudioTrack write failed"));
        listener.onDroppedVideoFrames(time, 7, 1_000);
        String log = logs();
        assertTrue(log.contains("audioCodecError"));
        assertTrue(log.contains("audioSinkError"));
        assertTrue(log.contains("videoDroppedFrames count=7"));
        assertFalse(log.contains("PRIVATE_TOKEN"));
    }

    @Test
    public void shouldLogReleasedConfigurationSeparatelyFromLatestInitialization() throws Exception {
        listener.onAudioTrackInitialized(null, output());
        listener.onAudioTrackInitialized(null, new AudioSink.AudioTrackConfig(C.ENCODING_PCM_16BIT,
                48_000, 12, false, false, 192_000));
        DiagnosticLog.start(folder.getRoot());
        // Media3 creates another config object for release; old releases may arrive late.
        listener.onAudioTrackReleased(time, output());
        listener.onAudioUnderrun(time, 192_000, 1_000, 1_100);
        String log = logs();
        assertTrue(log.contains("audioTrackRelease releasedOutputEncoding=2"));
        assertTrue(log.contains("releasedOutputBufferBytes=96000"));
        assertTrue(log.contains("lastOutputBufferBytes=192000"));
    }
}
