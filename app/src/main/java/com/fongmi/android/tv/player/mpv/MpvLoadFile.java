package com.fongmi.android.tv.player.mpv;

import java.util.Locale;

/** Command layout for the bundled mpv (0.38+): URL, flags, index, then per-file options. */
final class MpvLoadFile {
    private MpvLoadFile() { }

    /** Explicit network playlists can start decoding at the resume segment before UI callbacks run. */
    static boolean networkPlaylist(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        int query = lower.indexOf('?');
        int fragment = lower.indexOf('#');
        int end = query < 0 ? lower.length() : query;
        if (fragment >= 0) end = Math.min(end, fragment);
        return lower.substring(0, end).endsWith(".m3u8");
    }

    static boolean deferInitialSeek(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(Locale.US);
        return !networkPlaylist(url) && (lower.contains(".m3u8") || lower.startsWith("http://") || lower.startsWith("https://"));
    }

    static String[] command(String url, long startPositionMs) {
        if (startPositionMs <= 0 || deferInitialSeek(url)) return new String[]{"loadfile", url, "replace"};
        return new String[]{"loadfile", url, "replace", "-1",
                "start=" + String.format(Locale.US, "%.3f", startPositionMs / 1000.0)};
    }
}
