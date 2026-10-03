package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small metadata cache. Glide owns the image bytes and disk cache; binding never reads disk. */
public final class FeaturedPosterCache {

    private final Map<String, String> entries = new LinkedHashMap<>(32, 0.75f, true);
    private String signature = "";

    public boolean prepare(List<Vod> items) {
        List<String> keys = new ArrayList<>();
        for (Vod item : items) keys.add(keyOf(item));
        Collections.sort(keys);
        StringBuilder joined = new StringBuilder();
        for (String key : keys) joined.append(key.length()).append(':').append(key);
        String next = joined.toString();
        boolean changed = !next.equals(signature);
        signature = next;
        return changed;
    }

    public String getSignature() {
        return signature;
    }

    public boolean isCurrent(String expected) {
        return signature.equals(expected);
    }

    public String get(Vod item) {
        return entries.get(keyOf(item));
    }

    public void put(String expected, Vod item, String url) {
        if (!isCurrent(expected) || url == null || url.isEmpty()) return;
        entries.put(keyOf(item), url);
        if (entries.size() > 128) entries.remove(entries.keySet().iterator().next());
    }

    public void invalidate(Vod item, String failedUrl) {
        entries.remove(keyOf(item), failedUrl);
    }

    public static String keyOf(Vod item) {
        return item.getName() + "\n" + item.getYear() + "\n" + item.getTypeName();
    }
}
