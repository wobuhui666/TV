package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.*;

public class ExperienceSettingsTest {
    @Test public void shouldDefaultInstallsAndUpgradesToLibrary() {
        assertEquals(ExperienceMode.LIBRARY, ExperienceSettings.getMode());
        assertEquals(ExperienceMode.LIBRARY, ExperienceSettings.parse(null));
        assertEquals(ExperienceMode.LIBRARY, ExperienceSettings.parse("future-value"));
    }

    @Test public void shouldKeepBothLibraryModesOnTheSameCatalogue() {
        assertTrue(ExperienceMode.LIBRARY.usesLibrary());
        assertTrue(ExperienceMode.SIMPLE.usesLibrary());
        assertFalse(ExperienceMode.CLASSIC.usesLibrary());
        assertEquals(ExperienceMode.CLASSIC, ExperienceSettings.parse("CLASSIC"));
    }
}
