package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
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
        listener.onVideoDecoderReleased(null, "ignored");
        listener.onVideoCodecError(null, null);
        listener.onVideoDisabled(null, null);
        DiagnosticLog.start(folder.getRoot());
        assertFalse(logs().contains("exo-audio"));
        assertFalse(logs().contains("exo-video"));
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

    @Test
    public void shouldRetainVideoDecoderAndPrimitiveFormatWhenRecordingStartsDuringPlayback() throws Exception {
        listener.onVideoDecoderInitialized(null, "OMX.MTK.VIDEO.DECODER.HEVC", 0, 24);
        listener.onVideoInputFormatChanged(null, new Format.Builder().setSampleMimeType("video/hevc")
                .setCodecs("hvc1.1.6.L153.B0").setWidth(3840).setHeight(2160).setFrameRate(59.94f)
                .setAverageBitrate(48_000_000).setColorInfo(new ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
                        .setColorRange(C.COLOR_RANGE_LIMITED).setColorTransfer(C.COLOR_TRANSFER_ST2084)
                        .setLumaBitdepth(10).setChromaBitdepth(10).build())
                .setId("https://private.invalid/video?token=VIDEO_ID_SECRET")
                .setLabel("VIDEO_LABEL_SECRET").build(), null);
        DiagnosticLog.start(folder.getRoot());
        listener.onAudioUnderrun(time, 96_000, 500, 700);
        listener.onDroppedVideoFrames(time, 9, 1_000);
        String log = logs();
        assertTrue(log.contains("lastVideoDecoder=OMX.MTK.VIDEO.DECODER.HEVC"));
        assertTrue(log.contains("videoMime=video/hevc"));
        assertTrue(log.contains("videoCodecs=hvc1.1.6.L153.B0"));
        assertTrue(log.contains("videoWidth=3840 videoHeight=2160 videoFrameRate=59.94"));
        assertTrue(log.contains("videoBitrate=48000000"));
        assertTrue(log.contains("videoColorTransfer=" + C.COLOR_TRANSFER_ST2084));
        assertTrue(log.contains("videoLumaBitdepth=10 videoChromaBitdepth=10"));
        assertFalse(log.contains("private.invalid"));
        assertFalse(log.contains("VIDEO_ID_SECRET"));
        assertFalse(log.contains("VIDEO_LABEL_SECRET"));
    }

    @Test
    public void shouldKeepLatestVideoDecoderWhenPreviousReleaseArrivesLate() throws Exception {
        listener.onVideoDecoderInitialized(null, "ffmpeg", 0, 5);
        listener.onVideoDecoderInitialized(null, "c2.mtk.avc.decoder", 10, 7);
        DiagnosticLog.start(folder.getRoot());
        listener.onVideoDecoderReleased(time, "ffmpeg");
        String log = logs();
        assertTrue(log.contains("videoDecoderRelease releasedVideoDecoder=ffmpeg"));
        assertTrue(log.contains("lastVideoDecoder=c2.mtk.avc.decoder"));
    }

    @Test
    public void shouldRecordVideoCountersAndClearContextWhenVideoIsDisabled() throws Exception {
        listener.onVideoDecoderInitialized(null, "previous-video-decoder", 0, 5);
        listener.onVideoInputFormatChanged(null, new Format.Builder().setSampleMimeType("video/avc")
                .setWidth(1920).setHeight(1080).build(), null);
        DecoderCounters counters = new DecoderCounters();
        counters.renderedOutputBufferCount = 100;
        counters.skippedOutputBufferCount = 2;
        counters.droppedBufferCount = 7;
        counters.droppedInputBufferCount = 3;
        counters.maxConsecutiveDroppedBufferCount = 4;
        DiagnosticLog.start(folder.getRoot());
        listener.onVideoDisabled(time, counters);
        String log = logs();
        assertTrue(log.contains("videoDisabled renderedBuffers=100 skippedBuffers=2 droppedBuffers=7"
                + " droppedInputBuffers=3 maxConsecutiveDroppedBuffers=4"));
        DiagnosticLog.stop();
        DiagnosticLog.start(folder.getRoot());
        listener.onDroppedVideoFrames(time, 1, 100);
        log = logs();
        assertTrue(log.contains("lastVideoDecoder=unknown videoMime=unknown videoCodecs=unknown"));
        assertTrue(log.contains("videoWidth=-1 videoHeight=-1 videoFrameRate=-1.0"));
        assertFalse(log.contains("previous-video-decoder"));
    }

    @Test
    public void shouldRecordVideoCodecErrorsWithRedaction() throws Exception {
        DiagnosticLog.start(folder.getRoot());
        listener.onVideoCodecError(time, new IllegalStateException("https://host.invalid/video?token=VIDEO_ERROR_SECRET"));
        String log = logs();
        assertTrue(log.contains("videoCodecError"));
        assertTrue(log.contains("exo-video"));
        assertFalse(log.contains("VIDEO_ERROR_SECRET"));
    }
}
