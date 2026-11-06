package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.*;

public class BrowseExperienceSettingsTest {
    @Test
    public void shouldDefaultToCompactHomeWithoutAnAndroidPreferenceStore() {
        assertEquals(BrowseExperienceSettings.HOME_COMPACT, BrowseExperienceSettings.getHomeLayout());
        assertTrue(BrowseExperienceSettings.isCompactHomeEnabled());
        assertTrue(BrowseExperienceSettings.isPosterHomeEnabled());
    }
}
