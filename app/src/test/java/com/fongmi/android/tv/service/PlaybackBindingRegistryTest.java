package com.fongmi.android.tv.service;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackBindingRegistryTest {

    @Test
    public void claimReplacesPreviousOwnerOnce() {
        PlaybackBindingRegistry<Object> registry = new PlaybackBindingRegistry<>();
        AtomicInteger replaced = new AtomicInteger();
        Object first = new Object();
        Object second = new Object();

        registry.claim(first, replaced::incrementAndGet);
        registry.claim(first, replaced::incrementAndGet);
        registry.claim(second, () -> {
        });

        assertTrue(registry.owns(second));
        assertFalse(registry.owns(first));
        assertEquals(1, replaced.get());
    }

    @Test
    public void staleOwnerCannotReleaseCurrentOwner() {
        PlaybackBindingRegistry<Object> registry = new PlaybackBindingRegistry<>();
        Object first = new Object();
        Object second = new Object();

        registry.claim(first, () -> {
        });
        registry.claim(second, () -> {
        });

        assertFalse(registry.release(first));
        assertTrue(registry.owns(second));
        assertTrue(registry.release(second));
        assertFalse(registry.owns(second));
    }
}
