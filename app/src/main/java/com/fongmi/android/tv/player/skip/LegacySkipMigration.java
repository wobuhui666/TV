package com.fongmi.android.tv.player.skip;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.History;
import com.github.catvod.utils.Prefers;

public final class LegacySkipMigration {

    private LegacySkipMigration() {
    }

    public static void clearPreferences() {
        if (!Prefers.getPrefers().contains("ai_skip_enabled") && !Prefers.getPrefers().contains("ai_skip_url")
                && !Prefers.getPrefers().contains("ai_skip_token_cipher")) return;
        Prefers.getPrefers().edit().remove("ai_skip_enabled").remove("ai_skip_url")
                .remove("ai_skip_token_cipher").apply();
    }

    /** Remove old length-only results before computing a new playback position. */
    public static void clearBoundaries(History history) {
        if (history == null) return;
        if (isLegacySource(history.getOpeningSource())) {
            history.setOpening(C.TIME_UNSET);
            history.setOpeningSource("unknown");
        }
        if (isLegacySource(history.getEndingSource())) {
            history.setEnding(C.TIME_UNSET);
            history.setEndingSource("unknown");
        }
    }

    private static boolean isLegacySource(String source) {
        return source != null && ("ai".equalsIgnoreCase(source)
                || source.regionMatches(true, 0, "ai:", 0, 3));
    }
}
