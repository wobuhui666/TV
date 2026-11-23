package com.fongmi.android.tv.player.mpv;

import java.util.function.UnaryOperator;

/** One log line naming the decoder path and colour signalling of the video being shown. */
final class MpvVideoPath {
    private String reported = "";

    void reset() {
        reported = "";
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
