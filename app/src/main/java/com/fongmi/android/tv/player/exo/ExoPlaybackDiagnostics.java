package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;

import com.github.catvod.crawler.diagnostics.DiagnosticLog;

/** Event-only diagnostics, including audio stalls which do not cause a playback error. */
@UnstableApi
final class ExoPlaybackDiagnostics implements AnalyticsListener {
    private String decoder = "unknown";
    private String mime = "unknown";
    private int sampleRate = Format.NO_VALUE;
    private int channels = Format.NO_VALUE;
    // Release callbacks carry a fresh config and can arrive after a replacement is initialized.
    // Keep the latest initialization as historical context, rather than claiming it is active.
    @Nullable private AudioSink.AudioTrackConfig lastOutput;
    private String lastVideoDecoder = "unknown";
    private String videoMime = "unknown";
    private String videoCodecs = "unknown";
    private int videoWidth = Format.NO_VALUE;
    private int videoHeight = Format.NO_VALUE;
    private float videoFrameRate = Format.NO_VALUE;
    private int videoBitrate = Format.NO_VALUE;
    private int videoColorSpace = Format.NO_VALUE;
    private int videoColorRange = Format.NO_VALUE;
    private int videoColorTransfer = Format.NO_VALUE;
    private int videoLumaBitdepth = Format.NO_VALUE;
    private int videoChromaBitdepth = Format.NO_VALUE;

    @Override
    public void onAudioDecoderInitialized(EventTime time, String name, long timestampMs, long durationMs) {
        decoder = name;
        if (DiagnosticLog.isEnabled()) record(time, "audioDecoderInit durationMs=" + durationMs);
    }

    @Override
    public void onAudioInputFormatChanged(EventTime time, Format format, @Nullable DecoderReuseEvaluation reuse) {
        mime = format.sampleMimeType == null ? "unknown" : format.sampleMimeType;
        sampleRate = format.sampleRate;
        channels = format.channelCount;
        record(time, "audioFormat");
    }

    @Override
    public void onAudioTrackInitialized(EventTime time, AudioSink.AudioTrackConfig config) {
        lastOutput = config;
        record(time, "audioTrackInit");
    }

    @Override
    public void onAudioTrackReleased(EventTime time, AudioSink.AudioTrackConfig config) {
        if (!DiagnosticLog.isEnabled()) return;
        record(time, "audioTrackRelease" + describeOutput("releasedOutput", config));
    }

    @Override
    public void onAudioUnderrun(EventTime time, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
        if (!DiagnosticLog.isEnabled()) return;
        record(time, "audioUnderrun bufferBytes=" + bufferSize + " bufferDurationMs=" + duration(bufferSizeMs)
                + " elapsedSinceLastFeedMs=" + elapsedSinceLastFeedMs);
    }

    @Override
    public void onAudioSinkError(EventTime time, Exception error) {
        if (!DiagnosticLog.isEnabled()) return;
        record(time, "audioSinkError");
        DiagnosticLog.record("exo-audio", error);
    }

    @Override
    public void onAudioCodecError(EventTime time, Exception error) {
        if (!DiagnosticLog.isEnabled()) return;
        record(time, "audioCodecError");
        DiagnosticLog.record("exo-audio", error);
    }

    @Override
    public void onDroppedVideoFrames(EventTime time, int droppedFrames, long elapsedMs) {
        if (!DiagnosticLog.isEnabled()) return;
        record(time, "videoDroppedFrames count=" + droppedFrames + " elapsedMs=" + elapsedMs);
    }

    @Override
    public void onVideoDecoderInitialized(EventTime time, String name, long timestampMs, long durationMs) {
        lastVideoDecoder = name;
        if (DiagnosticLog.isEnabled()) record(time, "videoDecoderInit durationMs=" + durationMs);
    }

    @Override
    public void onVideoInputFormatChanged(EventTime time, Format format, @Nullable DecoderReuseEvaluation reuse) {
        // Retain only diagnostic fields, never the Format's URL-bearing id, label or metadata.
        videoMime = format.sampleMimeType == null ? "unknown" : format.sampleMimeType;
        videoCodecs = format.codecs == null ? "unknown" : format.codecs;
        videoWidth = format.width;
        videoHeight = format.height;
        videoFrameRate = format.frameRate;
        videoBitrate = format.bitrate;
        videoColorSpace = format.colorInfo == null ? Format.NO_VALUE : format.colorInfo.colorSpace;
        videoColorRange = format.colorInfo == null ? Format.NO_VALUE : format.colorInfo.colorRange;
        videoColorTransfer = format.colorInfo == null ? Format.NO_VALUE : format.colorInfo.colorTransfer;
        videoLumaBitdepth = format.colorInfo == null ? Format.NO_VALUE : format.colorInfo.lumaBitdepth;
        videoChromaBitdepth = format.colorInfo == null ? Format.NO_VALUE : format.colorInfo.chromaBitdepth;
        record(time, "videoFormat");
    }

    @Override
    public void onVideoDecoderReleased(EventTime time, String name) {
        if (!DiagnosticLog.isEnabled()) return;
        // The old decoder's release can follow a replacement decoder's initialization.
        record(time, "videoDecoderRelease releasedVideoDecoder=" + name);
    }

    @Override
    public void onVideoCodecError(EventTime time, Exception error) {
        if (!DiagnosticLog.isEnabled()) return;
        record(time, "videoCodecError");
        DiagnosticLog.record("exo-video", error);
    }

    @Override
    public void onVideoDisabled(EventTime time, DecoderCounters counters) {
        if (DiagnosticLog.isEnabled()) {
            counters.ensureUpdated();
            record(time, "videoDisabled renderedBuffers=" + counters.renderedOutputBufferCount
                    + " skippedBuffers=" + counters.skippedOutputBufferCount
                    + " droppedBuffers=" + counters.droppedBufferCount
                    + " droppedInputBuffers=" + counters.droppedInputBufferCount
                    + " maxConsecutiveDroppedBuffers=" + counters.maxConsecutiveDroppedBufferCount);
        }
        lastVideoDecoder = videoMime = videoCodecs = "unknown";
        videoWidth = videoHeight = videoBitrate = Format.NO_VALUE;
        videoFrameRate = Format.NO_VALUE;
        videoColorSpace = videoColorRange = videoColorTransfer = Format.NO_VALUE;
        videoLumaBitdepth = videoChromaBitdepth = Format.NO_VALUE;
    }

    @Override
    public void onAudioDisabled(EventTime time, DecoderCounters counters) {
        if (DiagnosticLog.isEnabled()) {
            counters.ensureUpdated();
            record(time, "audioDisabled renderedBuffers=" + counters.renderedOutputBufferCount
                    + " skippedBuffers=" + counters.skippedOutputBufferCount);
        }
        decoder = "unknown";
        mime = "unknown";
        sampleRate = channels = Format.NO_VALUE;
        lastOutput = null;
    }

    private void record(EventTime time, String event) {
        if (!DiagnosticLog.isEnabled()) return;
        DiagnosticLog.record(event.startsWith("video") ? "exo-video" : "exo-audio", event + " realtimeMs=" + time.realtimeMs
                + " positionMs=" + time.currentPlaybackPositionMs
                + " mediaBufferedDurationMs=" + time.totalBufferedDurationMs
                + " decoder=" + decoder + " mime=" + mime + " sampleRate=" + sampleRate
                + " channels=" + channels + describeOutput("lastOutput", lastOutput)
                + " lastVideoDecoder=" + lastVideoDecoder + " videoMime=" + videoMime + " videoCodecs=" + videoCodecs
                + " videoWidth=" + videoWidth + " videoHeight=" + videoHeight + " videoFrameRate=" + videoFrameRate
                + " videoBitrate=" + videoBitrate + " videoColorSpace=" + videoColorSpace
                + " videoColorRange=" + videoColorRange + " videoColorTransfer=" + videoColorTransfer
                + " videoLumaBitdepth=" + videoLumaBitdepth + " videoChromaBitdepth=" + videoChromaBitdepth);
    }

    private static String describeOutput(String prefix, @Nullable AudioSink.AudioTrackConfig output) {
        return output == null ? " " + prefix + "=unknown" : " " + prefix + "Encoding=" + output.encoding
                + " " + prefix + "SampleRate=" + output.sampleRate + " " + prefix + "ChannelMask=" + output.channelConfig
                + " " + prefix + "BufferBytes=" + output.bufferSize + " " + prefix + "Tunneling=" + output.tunneling
                + " " + prefix + "Offload=" + output.offload;
    }

    private static String duration(long value) {
        return value == C.TIME_UNSET ? "TIME_UNSET" : Long.toString(value);
    }
}
