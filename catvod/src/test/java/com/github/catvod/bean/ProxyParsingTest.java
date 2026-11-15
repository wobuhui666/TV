package com.github.catvod.bean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class ProxyParsingTest {
    @Test public void shouldResolveAnEmptyRuleArrayWithoutPlatformTypeAdapters() {
        Gson gson = platformGuardedGson();
        TypeToken<?> list = TypeToken.getParameterized(List.class, Proxy.class);
        assertNotNull(gson.getAdapter(list));
        assertEquals(List.of(), gson.fromJson("[]", list.getType()));
        assertTrue(Proxy.arrayFrom(JsonParser.parseString("[]")).isEmpty());
    }

    @Test public void shouldParseTheDocumentedRuleFields() {
        List<Proxy> rules = Proxy.arrayFrom(JsonParser.parseString(
                "[{\"name\":\"media\",\"hosts\":[\"media.example.invalid\"],\"urls\":[\"http://proxy.example.invalid:8080\"]}]"));
        assertEquals(1, rules.size());
        Proxy rule = rules.get(0);
        assertEquals(List.of("media.example.invalid"), rule.getHosts());
        assertEquals(List.of("http://proxy.example.invalid:8080"), rule.getUrls());
        assertTrue(rule.getProxies().isEmpty());
        JsonObject output = new Gson().toJsonTree(rule).getAsJsonObject();
        assertEquals("media", output.get("name").getAsString());
        assertEquals(Set.of("name", "hosts", "urls"), output.keySet());
    }

    @Test public void shouldIgnoreInjectedRuntimeCachesWithoutRejectingTheRule() {
        String input = "{\"name\":\"safe\",\"hosts\":[\"media.example.invalid\"],\"urls\":[],"
                + "\"proxies\":{\"unexpected\":true},\"uris\":[{\"unexpected\":true}],\"wildcard\":true}";
        Proxy rule = platformGuardedGson().fromJson(input, Proxy.class);
        assertEquals(List.of("media.example.invalid"), rule.getHosts());
        assertTrue(rule.getUrls().isEmpty());
        assertTrue(rule.getProxies().isEmpty());
        assertEquals("Injected wildcard does not alter pre-init ordering", 0, rule.compareTo(new Proxy()));
        assertEquals(Set.of("name", "hosts", "urls"), platformGuardedGson().toJsonTree(rule).getAsJsonObject().keySet());
        assertEquals(1, Proxy.arrayFrom(JsonParser.parseString("[" + input + "]")).size());
    }

    @Test public void shouldTreatNullAndInvalidArraysAsNoRules() {
        assertTrue(Proxy.arrayFrom(JsonParser.parseString("null")).isEmpty());
        assertTrue(Proxy.arrayFrom(JsonParser.parseString("{}")).isEmpty());
    }

    private Gson platformGuardedGson() {
        return new GsonBuilder().registerTypeAdapterFactory(new TypeAdapterFactory() {
            @Override public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                String name = type.getRawType().getName();
                if (name.startsWith("java.net.") || name.startsWith("android.net.Uri")) {
                    throw new AssertionError("Configuration adapters must not inspect runtime platform type " + name);
                }
                return null;
            }
        }).create();
    }
}
