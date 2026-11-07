package com.fongmi.quickjs.utils;

import org.junit.Test;

import static org.junit.Assert.*;

public class ScriptOptionsTest {

    @Test
    public void rejectsMalformedOptionsBeforeStartingAnEngine() {
        for (String options : new String[]{"null", "[]", "true", "{} trailing", "{\"globals\":null}", "{\"globals\":[]}", "{\"globals\":{\"md5X\":1}}", "{\"globals\":{\"format\":null}}"}) {
            assertThrows(options, RuntimeException.class, () -> new ScriptOptions("1", options));
        }
        assertThrows(IllegalArgumentException.class, () -> new ScriptOptions(null, "{}"));
    }

    @Test
    public void requiresAnExactPositiveTimeoutWithinJavascriptIntegerRange() {
        assertEquals(5000, new ScriptOptions("1", null).timeout);
        assertEquals(1, new ScriptOptions("1", "{\"timeout\":1}").timeout);
        assertEquals(9007199254740991L, new ScriptOptions("1", "{\"timeout\":9007199254740991}").timeout);
        for (String value : new String[]{"0", "-1", "1.5", "true", "null", "\"10\"", "9007199254740992", "1e100"}) {
            assertThrows(value, IllegalArgumentException.class, () -> new ScriptOptions("1", "{\"timeout\":" + value + "}"));
        }
    }
}
