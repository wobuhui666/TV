package com.fongmi.android.tv.utils;

import java.util.List;
import java.util.Locale;

/** File formats accepted by local subtitle discovery and uploaded subtitle actions. */
public final class SubtitleArchive {

    public static final List<String> EXTENSIONS = List.of("srt", "ssa", "ass", "vtt");

    private SubtitleArchive() {
    }

    public static boolean isSubtitle(String name) {
        if (name == null) return false;
        String path = name.toLowerCase(Locale.ROOT);
        int dot = path.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(path.substring(dot + 1));
    }
}
