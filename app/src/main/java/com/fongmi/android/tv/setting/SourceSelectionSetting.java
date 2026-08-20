package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.source.SourceReliabilityStore;
import com.fongmi.android.tv.source.SourceSelectionMode;
import com.github.catvod.utils.Prefers;

public final class SourceSelectionSetting {

    private static final String MODE = "source_selection_mode";
    private static final String CROSS_SITE = "source_selection_cross_site";

    private SourceSelectionSetting() {
    }

    public static SourceSelectionMode getMode() {
        try {
            return SourceSelectionMode.valueOf(Prefers.getString(MODE, SourceSelectionMode.LEGACY.name()));
        } catch (Exception ignored) {
            return SourceSelectionMode.LEGACY;
        }
    }

    public static void putMode(SourceSelectionMode mode) {
        Prefers.put(MODE, (mode == null ? SourceSelectionMode.LEGACY : mode).name());
    }

    public static boolean isCrossSiteEnabled() {
        return Prefers.getBoolean(CROSS_SITE, true);
    }

    public static void putCrossSiteEnabled(boolean enabled) {
        Prefers.put(CROSS_SITE, enabled);
    }

    public static void clearReliability() {
        new SourceReliabilityStore().clear();
    }
}
