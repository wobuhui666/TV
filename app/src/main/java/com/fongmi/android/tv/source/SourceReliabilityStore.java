package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Prefers;

import java.util.HashMap;
import java.util.Map;

public final class SourceReliabilityStore {

    private static final String PREFIX = "source_reliability_";
    private static final int MIN_SCORE = -3;
    private static final int MAX_SCORE = 3;
    private final Map<String, Integer> memory;
    private final boolean persistent;

    public SourceReliabilityStore() {
        this.memory = new HashMap<>();
        this.persistent = true;
    }

    public SourceReliabilityStore(Map<String, Integer> initial) {
        this.memory = initial == null ? new HashMap<>() : new HashMap<>(initial);
        this.persistent = false;
    }

    public int score(Vod item) {
        return item == null ? 0 : score(key(item));
    }

    public int score(String key) {
        if (key == null || key.isEmpty()) return 0;
        if (!persistent) return memory.getOrDefault(key, 0);
        try {
            int value = Math.clamp(Prefers.getInt(PREFIX + key, 0), MIN_SCORE, MAX_SCORE);
            remember(key);
            return value;
        } catch (Throwable ignored) {
            return memory.getOrDefault(key, 0);
        }
    }

    public void recordSuccess(Vod item) {
        update(item, 1);
    }

    public void recordFailure(Vod item) {
        update(item, -1);
    }

    public void clear() {
        if (!persistent) {
            memory.clear();
            return;
        }
        try {
            String keys = Prefers.getString(PREFIX + "keys", "");
            for (String key : keys.split("\\n")) if (!key.isEmpty()) Prefers.remove(PREFIX + key);
            Prefers.remove(PREFIX + "keys");
        } catch (Throwable ignored) {
        }
        memory.clear();
    }

    private void update(Vod item, int delta) {
        if (item == null) return;
        String key = key(item);
        int value = Math.clamp(score(key) + delta, MIN_SCORE, MAX_SCORE);
        memory.put(key, value);
        if (persistent) {
            try {
                Prefers.put(PREFIX + key, value);
                remember(key);
            } catch (Throwable ignored) {
            }
        }
    }

    private String key(Vod item) {
        return (item.getSiteKey() + "_" + item.getId()).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void remember(String key) {
        if (!persistent || key.isEmpty()) return;
        try {
            String keys = Prefers.getString(PREFIX + "keys", "");
            for (String known : keys.split("\\n")) if (known.equals(key)) return;
            Prefers.put(PREFIX + "keys", keys.isEmpty() ? key : keys + "\n" + key);
        } catch (Throwable ignored) {
        }
    }
}
