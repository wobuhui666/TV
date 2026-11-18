package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/** Complete TV experiences; old browsing preferences remain available to the classic experience. */
public final class ExperienceSettings {
    private static final String MODE = "tv_experience_mode_v1";

    private ExperienceSettings() { }

    public static ExperienceMode getMode() {
        return parse(Prefers.getString(MODE, ExperienceMode.LIBRARY.name()));
    }

    public static ExperienceMode parse(String value) {
        try { return ExperienceMode.valueOf(value); }
        catch (RuntimeException error) { return ExperienceMode.LIBRARY; }
    }

    public static void putMode(ExperienceMode mode) {
        Prefers.put(MODE, (mode == null ? ExperienceMode.LIBRARY : mode).name());
    }

    public static boolean usesLibrary() { return getMode().usesLibrary(); }
    public static int homeLayout() { return usesLibrary() ? BrowseExperienceSettings.HOME_COMPACT : BrowseExperienceSettings.HOME_SOURCE; }
    public static int heroRotationMode() {
        return switch (getMode()) {
            case LIBRARY -> BrowseExperienceSettings.HERO_ROTATION_FOCUS_PAUSED;
            case SIMPLE -> BrowseExperienceSettings.HERO_ROTATION_MANUAL;
            case CLASSIC -> BrowseExperienceSettings.getHeroRotationMode();
        };
    }
}
