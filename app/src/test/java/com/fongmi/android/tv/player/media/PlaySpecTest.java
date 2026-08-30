package com.fongmi.android.tv.player.media;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaySpecTest {

    @Test
    public void shouldRemoveStaticRangeWithoutMutatingSourceHeaders() {
        Map<String, String> source = new HashMap<>();
        source.put("range", "bytes=100-");
        source.put("Referer", "https://example.com/");

        Map<String, String> sanitized = PlaySpec.sanitizeHeaders(source);

        assertFalse(sanitized.keySet().stream().anyMatch("Range"::equalsIgnoreCase));
        assertEquals("https://example.com/", sanitized.get("Referer"));
        assertTrue(source.containsKey("range"));
    }
}
