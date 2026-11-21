package com.fongmi.android.tv.source.health;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SourceHealthScopeTest {
    @Test public void changingConfigurationKeepsObservationsInTheirOwnScope() {
        String first = SourceHealthManager.scopeFor("https://first.invalid/config");
        String second = SourceHealthManager.scopeFor("https://second.invalid/config");
        assertNotEquals(first, second);
        assertEquals(first, SourceHealthManager.scopeFor("https://first.invalid/config"));
    }

    @Test public void clearingHealthInvalidatesTheCachedScopeOfTheSameConfiguration() {
        String before = SourceHealthManager.scopeFor("https://first.invalid/config");
        SourceHealthManager.clear();
        assertNotEquals(before, SourceHealthManager.scopeFor("https://first.invalid/config"));
    }
}
