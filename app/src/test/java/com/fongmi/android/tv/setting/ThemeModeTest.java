package com.fongmi.android.tv.setting;

import static org.junit.Assert.*;

import org.junit.Test;

public class ThemeModeTest {
    @Test
    public void shouldPreserveLegacyLightAndDefaultDarkPreferences() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored(-1, true));
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored(-1, false));
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored(99, true));
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored(99, false));
    }

    @Test
    public void shouldPreferStoredModeOverLegacyPreference() {
        for (ThemeMode mode : ThemeMode.values()) {
            assertEquals(mode, ThemeMode.fromStored(mode.value(), true));
            assertEquals(mode, ThemeMode.fromStored(mode.value(), false));
        }
    }

    @Test
    public void shouldFollowSystemOnlyWhenRequested() {
        assertTrue(ThemeMode.SYSTEM.isLight(false));
        assertFalse(ThemeMode.SYSTEM.isLight(true));
        assertTrue(ThemeMode.LIGHT.isLight(false));
        assertTrue(ThemeMode.LIGHT.isLight(true));
        assertFalse(ThemeMode.DARK.isLight(false));
        assertFalse(ThemeMode.DARK.isLight(true));
    }
}
