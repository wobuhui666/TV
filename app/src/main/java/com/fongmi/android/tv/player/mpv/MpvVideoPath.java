package com.fongmi.android.tv.player.mpv;

import java.util.function.UnaryOperator;

/** One log line naming the decoder path and colour signalling of the video being shown. */
final class MpvVideoPath {
    private String reported = "";
    private long lastSampleMs = Long.MIN_VALUE;

    void reset() {
        reported = "";
        lastSampleMs = Long.MIN_VALUE;
    }

    boolean isReported() {
        return !reported.isEmpty();
    }

    /** Returns the line only when it differs from the one already reported for this playback. */
    String changed(String line) {
        if (line == null || line.isEmpty() || line.equals(reported)) return null;
        reported = line;
        return line;
    }

    /** Native buffer layout is needed to distinguish NV12/NV21 and stride/crop errors. */
    static boolean isDecoderConfiguration(String prefix, String message) {
        if (!"ffmpeg/video".equals(prefix) || message == null) return false;
        String value = message.trim();
        for (String codec : new String[]{"hevc_mediacodec: ", "h264_mediacodec: "}) {
            if (!value.startsWith(codec)) continue;
            String detail = value.substring(codec.length());
            return detail.startsWith("Output MediaFormat changed to ")
                    || detail.startsWith("Output crop parameters ")
                    || detail.startsWith("MediaCodec output layout: ")
                    || detail.startsWith("HiSilicon NV21 workaround applied: ");
        }
        return false;
    }

    static boolean isNativeDiagnostics(String prefix, String message) {
        if (isDecoderConfiguration(prefix, message)) return true;
        if (message == null) return false;
        String value = message.trim();
        if ("vd".equals(prefix)) {
            return value.startsWith("Using hardware decoding (")
                    || value.equals("Using software decoding.")
                    || value.startsWith("Attempting next decoding method after failure of ");
        }
        return "ao/audiotrack".equals(prefix) && (value.startsWith("Setting bufferSize = ")
                || value.startsWith("AudioTrack.getBufferSizeInFrames = ")
                || value.startsWith("AudioTrack.nativeOutputSampleRate = "));
    }

    /** Called only on the application thread, using elapsed time rather than the media clock. */
    boolean takeSample(long nowMs, long intervalMs) {
        if (lastSampleMs != Long.MIN_VALUE && nowMs - lastSampleMs < intervalMs) return false;
        lastSampleMs = nowMs;
        return true;
    }

    static String performance(UnaryOperator<String> property, int audioUnderruns) {
        StringBuilder result = new StringBuilder("audio-underruns=").append(audioUnderruns);
        for (String name : new String[]{"time-pos", "speed", "pause", "seeking", "hwdec-current",
                "container-fps", "estimated-vf-fps", "frame-drop-count", "decoder-frame-drop-count",
                "avsync", "demuxer-cache-duration", "cache-speed", "paused-for-cache",
                "current-ao", "audio-params/samplerate", "audio-params/channels"}) {
            result.append(' ').append(name).append('=').append(shown(read(property, name)));
        }
        return result.toString();
    }

    /**
     * Describes decoder output, never the display's tone-mapped values. With hwdec "mediacodec"
     * the device's GPU driver converts the colours of each frame; with "mediacodec-copy" or "no"
     * mpv converts them itself.
     */
    static String describe(UnaryOperator<String> property, int dolbyVisionProfile) {
        String format = read(property, "video-dec-params/pixelformat");
        // A reconfiguration can precede the first decoded frame, when no decoder path is known yet.
        if (format.isEmpty()) return "";
        return "codec=" + shown(read(property, "video-format"))
                + " size=" + shown(read(property, "video-dec-params/w")) + "x" + shown(read(property, "video-dec-params/h"))
                + " hwdec=" + shown(read(property, "hwdec-current"))
                + " requested=" + shown(read(property, "hwdec"))
                + " format=" + format
                + " hw-format=" + shown(read(property, "video-dec-params/hw-pixelformat"))
                + " matrix=" + shown(read(property, "video-dec-params/colormatrix"))
                + " levels=" + shown(read(property, "video-dec-params/colorlevels"))
                + " primaries=" + shown(read(property, "video-dec-params/primaries"))
                + " transfer=" + shown(read(property, "video-dec-params/gamma"))
                + " vo=" + shown(read(property, "current-vo"))
                + " context=" + shown(read(property, "current-gpu-context"))
                + " dolby-vision=" + (dolbyVisionProfile >= 0 ? String.valueOf(dolbyVisionProfile) : "no");
    }

    private static String read(UnaryOperator<String> property, String name) {
        String value = property.apply(name);
        return value == null ? "" : value.trim();
    }

    private static String shown(String value) {
        return value.isEmpty() ? "?" : value;
    }
}
