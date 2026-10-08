package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optional poster-source order, isolated by configuration without persisting its URL. */
public final class PosterSourcePrioritySetting {
    public static final int MAX_SOURCES = 128;
    private static final String STORAGE = "browse_poster_source_priority_v1";
    private static final int MAX_CONFIGURATIONS = 32;
    private static final int MAX_KEY_LENGTH = 512;
    private static final int MAX_PAYLOAD_LENGTH = 256 * 1024;

    private PosterSourcePrioritySetting() {}

    public static synchronized List<String> getOrderedKeys() {
        return read(Prefers.getString(STORAGE, ""), VodConfig.getUrl(), VodConfig.get().getSites());
    }

    public static synchronized void putOrderedKeys(List<String> keys) {
        persist(write(Prefers.getString(STORAGE, ""), VodConfig.getUrl(), keys, VodConfig.get().getSites()));
    }

    /** Restore the current configuration's original order; other configurations stay independent. */
    public static synchronized void clear() {
        persist(clear(Prefers.getString(STORAGE, ""), VodConfig.getUrl()));
    }

    /** Search preferred sites first, then every other searchable site in configuration order. */
    public static List<Site> orderSites(List<Site> sites) {
        return orderSites(sites, getOrderedKeys());
    }

    private static void persist(String value) {
        if (value.isEmpty()) Prefers.remove(STORAGE);
        else Prefers.put(STORAGE, value);
    }

    static List<String> read(String stored, String configurationUrl, List<Site> sites) {
        return availableKeys(decode(stored).getOrDefault(scope(configurationUrl), List.of()), sites);
    }

    static String write(String stored, String configurationUrl, List<String> keys, List<Site> sites) {
        String scope = scope(configurationUrl);
        if (scope.isEmpty()) return stored == null ? "" : stored;
        Map<String, List<String>> values = decode(stored);
        values.remove(scope);
        List<String> ordered = availableKeys(keys, sites);
        if (!ordered.isEmpty()) values.put(scope, ordered);
        return encode(values);
    }

    static String clear(String stored, String configurationUrl) {
        String scope = scope(configurationUrl);
        if (scope.isEmpty()) return stored == null ? "" : stored;
        Map<String, List<String>> values = decode(stored);
        values.remove(scope);
        return encode(values);
    }

    static List<Site> orderSites(List<Site> sites, List<String> orderedKeys) {
        Map<String, Site> available = searchableSites(sites);
        List<Site> ordered = new ArrayList<>();
        if (orderedKeys != null) for (String key : orderedKeys) {
            Site site = available.remove(key);
            if (site != null) ordered.add(site);
        }
        ordered.addAll(available.values());
        return ordered;
    }

    private static List<String> availableKeys(List<String> keys, List<Site> sites) {
        Set<String> available = searchableSites(sites).keySet();
        Set<String> ordered = new LinkedHashSet<>();
        if (keys != null) for (String key : keys) {
            if (validKey(key) && available.contains(key)) ordered.add(key);
            if (ordered.size() == MAX_SOURCES) break;
        }
        return new ArrayList<>(ordered);
    }

    private static Map<String, Site> searchableSites(List<Site> sites) {
        Map<String, Site> available = new LinkedHashMap<>();
        if (sites != null) for (Site site : sites) {
            if (site != null && site.isSearchable() && site.getKey() != null && !site.getKey().isBlank()) {
                available.putIfAbsent(site.getKey(), site);
            }
        }
        return available;
    }

    private static boolean validKey(String key) {
        return key != null && !key.isBlank() && key.length() <= MAX_KEY_LENGTH;
    }

    private static String scope(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = digits[(digest[i] & 255) >>> 4];
                hex[i * 2 + 1] = digits[digest[i] & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException ignored) {
            return "";
        }
    }

    private static Map<String, List<String>> decode(String stored) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (stored == null || stored.isEmpty() || stored.length() > MAX_PAYLOAD_LENGTH) return values;
        // Stream and skip unexpected values instead of recursively building an untrusted JSON tree.
        try (JsonReader reader = new JsonReader(new StringReader(stored))) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return values;
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!key.matches("[a-f0-9]{64}") || reader.peek() != JsonToken.BEGIN_ARRAY) {
                    reader.skipValue();
                    continue;
                }
                Set<String> keys = new LinkedHashSet<>();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (reader.peek() != JsonToken.STRING) reader.skipValue();
                    else {
                        String candidate = reader.nextString();
                        if (keys.size() < MAX_SOURCES && validKey(candidate)) keys.add(candidate);
                    }
                }
                reader.endArray();
                values.remove(key);
                if (!keys.isEmpty()) values.put(key, new ArrayList<>(keys));
                trimConfigurations(values);
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) values.clear();
        } catch (IOException | RuntimeException ignored) {
            values.clear();
        }
        return values;
    }

    private static void trimConfigurations(Map<String, List<String>> values) {
        while (values.size() > MAX_CONFIGURATIONS) values.remove(values.keySet().iterator().next());
    }

    private static String encode(Map<String, List<String>> values) {
        trimConfigurations(values);
        while (!values.isEmpty()) {
            JsonObject object = new JsonObject();
            for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                JsonArray array = new JsonArray();
                entry.getValue().forEach(array::add);
                object.add(entry.getKey(), array);
            }
            String result = object.toString();
            if (result.length() <= MAX_PAYLOAD_LENGTH) return result;
            String oldest = values.keySet().iterator().next();
            if (values.size() > 1) values.remove(oldest);
            else {
                // Unusually long escaped keys cannot grow the preferences file without a bound.
                List<String> keys = values.get(oldest);
                keys.remove(keys.size() - 1);
                if (keys.isEmpty()) values.remove(oldest);
            }
        }
        return "";
    }
}
