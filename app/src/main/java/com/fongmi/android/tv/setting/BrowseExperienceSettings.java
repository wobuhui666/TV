package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/** Independent, opt-in browsing features. Reset never touches sources or watch history. */
public final class BrowseExperienceSettings {
    private static final String HOME = "browse_poster_home";
    private static final String FILTER = "browse_search_filter";
    private static final String SOURCES = "browse_detail_sources";
    private static final String SMART = "browse_smart_sources";
    private static final String HISTORY_ACTIONS = "browse_history_actions";
    private static final String KEEP_SHELF = "browse_poster_keep_shelf";
    private static final String HERO_ROTATION = "browse_hero_rotation";

    public static final int HERO_ROTATION_ORIGINAL = 0;
    public static final int HERO_ROTATION_FOCUS_PAUSED = 1;
    public static final int HERO_ROTATION_MANUAL = 2;

    private BrowseExperienceSettings() {}

    public static boolean isPosterHomeEnabled() { return Prefers.getBoolean(HOME, false); }
    public static void putPosterHomeEnabled(boolean enabled) { Prefers.put(HOME, enabled); }
    public static int getSearchFilterMode() { return Math.max(0, Math.min(2, Prefers.getInt(FILTER, 0))); }
    public static void putSearchFilterMode(int mode) { Prefers.put(FILTER, Math.max(0, Math.min(2, mode))); }
    public static boolean isDetailSourcesEnabled() { return Prefers.getBoolean(SOURCES, false); }
    public static void putDetailSourcesEnabled(boolean enabled) { Prefers.put(SOURCES, enabled); }
    public static boolean isSmartSourceEnabled() { return Prefers.getBoolean(SMART, false); }
    public static void putSmartSourceEnabled(boolean enabled) { Prefers.put(SMART, enabled); }
    public static boolean isHistoryActionsEnabled() { return Prefers.getBoolean(HISTORY_ACTIONS, false); }
    public static void putHistoryActionsEnabled(boolean enabled) { Prefers.put(HISTORY_ACTIONS, enabled); }
    public static boolean isPosterKeepShelfEnabled() { return Prefers.getBoolean(KEEP_SHELF, false); }
    public static void putPosterKeepShelfEnabled(boolean enabled) { Prefers.put(KEEP_SHELF, enabled); }
    public static int getHeroRotationMode() { return normalizeHeroRotation(Prefers.getInt(HERO_ROTATION, HERO_ROTATION_ORIGINAL)); }
    public static void putHeroRotationMode(int mode) { Prefers.put(HERO_ROTATION, normalizeHeroRotation(mode)); }

    private static int normalizeHeroRotation(int mode) {
        return mode >= HERO_ROTATION_ORIGINAL && mode <= HERO_ROTATION_MANUAL ? mode : HERO_ROTATION_ORIGINAL;
    }

    public static void restoreOriginal() {
        PosterSourcePrioritySetting.clear();
        putPosterHomeEnabled(false);
        putSearchFilterMode(0);
        putDetailSourcesEnabled(false);
        putSmartSourceEnabled(false);
        putHistoryActionsEnabled(false);
        putPosterKeepShelfEnabled(false);
        putHeroRotationMode(HERO_ROTATION_ORIGINAL);
    }
}
