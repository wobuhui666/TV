package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/** Independent, opt-in browsing features. Reset never touches sources or watch history. */
public final class BrowseExperienceSettings {
    private static final String HOME = "browse_poster_home";
    private static final String FILTER = "browse_search_filter";
    private static final String SOURCES = "browse_detail_sources";
    private static final String SMART = "browse_smart_sources";

    private BrowseExperienceSettings() {}

    public static boolean isPosterHomeEnabled() { return Prefers.getBoolean(HOME, false); }
    public static void putPosterHomeEnabled(boolean enabled) { Prefers.put(HOME, enabled); }
    public static int getSearchFilterMode() { return Math.max(0, Math.min(2, Prefers.getInt(FILTER, 0))); }
    public static void putSearchFilterMode(int mode) { Prefers.put(FILTER, Math.max(0, Math.min(2, mode))); }
    public static boolean isDetailSourcesEnabled() { return Prefers.getBoolean(SOURCES, false); }
    public static void putDetailSourcesEnabled(boolean enabled) { Prefers.put(SOURCES, enabled); }
    public static boolean isSmartSourceEnabled() { return Prefers.getBoolean(SMART, false); }
    public static void putSmartSourceEnabled(boolean enabled) { Prefers.put(SMART, enabled); }

    public static void restoreOriginal() {
        PosterSourcePrioritySetting.clear();
        putPosterHomeEnabled(false);
        putSearchFilterMode(0);
        putDetailSourcesEnabled(false);
        putSmartSourceEnabled(false);
    }
}
