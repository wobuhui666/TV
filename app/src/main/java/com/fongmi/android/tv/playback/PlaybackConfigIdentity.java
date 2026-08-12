package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class PlaybackConfigIdentity {

    private PlaybackConfigIdentity() {
    }

    public static String currentKey() {
        return keyForUrl(VodConfig.getUrl());
    }

    public static String keyForCid(int cid) {
        Config config = Config.find(cid);
        return keyForUrl(config == null ? VodConfig.getUrl() : config.getUrl());
    }

    public static int cidForKey(String key) {
        if (key == null || key.isEmpty()) return 0;
        for (Config config : Config.getAll(0)) if (key.equalsIgnoreCase(keyForUrl(config.getUrl()))) return config.getId();
        return 0;
    }

    public static String keyForUrl(String url) {
        String normalized = normalizeUrl(url);
        if (normalized.isEmpty()) return "";
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String normalizeUrl(String value) {
        if (value == null) return "";
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            String authority = host + (port < 0 || defaultPort ? "" : ":" + port);
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
            while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return scheme + "://" + authority + path + (uri.getQuery() == null || uri.getQuery().isEmpty() ? "" : "?" + uri.getQuery());
        } catch (Throwable ignored) {
            return value.trim();
        }
    }
}
