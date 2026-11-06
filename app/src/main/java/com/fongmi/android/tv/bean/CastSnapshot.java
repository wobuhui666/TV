package com.fongmi.android.tv.bean;

import com.fongmi.android.tv.db.AppDatabase;

import java.util.function.UnaryOperator;
import java.util.HashMap;
import java.util.Map;

public final class CastSnapshot {
    private CastSnapshot() {}

    public static Map<String, String> headers(Map<String, String> source) {
        Map<String, String> snapshot = new HashMap<>();
        if (source != null) source.forEach((name, value) -> {
            if (name != null && value != null) snapshot.put(name, value);
        });
        return Map.copyOf(snapshot);
    }

    public static History capture(History source, long positionMs) {
        if (source == null) return null;
        History snapshot = source.copy();
        if (positionMs >= 0) snapshot.setPosition(positionMs);
        return snapshot;
    }

    public static History transport(History source, UnaryOperator<String> shareUrl) {
        History snapshot = source.copy();
        String[] key = snapshot.getKey().split(AppDatabase.SYMBOL, 3);
        if (key.length < 2) return snapshot;
        String shared = shareUrl.apply(key[1]);
        snapshot.setKey(key[0] + AppDatabase.SYMBOL + shared
                + (key.length == 3 ? AppDatabase.SYMBOL + key[2] : ""));
        if (snapshot.getEpisodeUrl().equals(key[1])) snapshot.setEpisodeUrl(shared);
        return snapshot;
    }
}
