package com.fongmi.android.tv.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.setting.Setting;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class TmdbEndpoint {

    public static final String DEFAULT_ROOT = "https://tapi.coolmarket.eu.org/";
    public static final String DEFAULT_API_BASE = DEFAULT_ROOT + "3/";
    public static final String DEFAULT_IMAGE_BASE = DEFAULT_ROOT + "t/p/";
    public static final String DIRECT_ROOT = "https://api.themoviedb.org/";
    public static final String DIRECT_API_BASE = DIRECT_ROOT + "3/";
    public static final String DIRECT_IMAGE_BASE = "https://image.tmdb.org/t/p/";

    private TmdbEndpoint() {
    }

    @NonNull
    public static String getApiBase() {
        return getApiBase(Setting.getTmdbConnectionMode(), getCustomRoot());
    }

    @NonNull
    public static String getImageBase() {
        return getImageBase(Setting.getTmdbConnectionMode(), getCustomRoot());
    }

    @NonNull
    public static String getEffectiveRoot() {
        return getEffectiveRoot(Setting.getTmdbConnectionMode(), getCustomRoot());
    }

    static String getApiBase(int mode, String customRoot) {
        return mode == Setting.TMDB_DIRECT ? DIRECT_API_BASE : getEffectiveRoot(mode, customRoot) + "3/";
    }

    static String getImageBase(int mode, String customRoot) {
        return mode == Setting.TMDB_DIRECT ? DIRECT_IMAGE_BASE : getEffectiveRoot(mode, customRoot) + "t/p/";
    }

    private static String getEffectiveRoot(int mode, String customRoot) {
        if (mode == Setting.TMDB_DIRECT) return DIRECT_ROOT;
        String root = mode == Setting.TMDB_CUSTOM_PROXY ? normalizeRoot(customRoot) : "";
        return root.isEmpty() ? DEFAULT_ROOT : root;
    }

    @NonNull
    public static String getCustomRoot() {
        return normalizeRoot(Setting.getTmdbProxyUrl());
    }

    public static boolean isCustom() {
        return Setting.getTmdbConnectionMode() == Setting.TMDB_CUSTOM_PROXY && !getCustomRoot().isEmpty();
    }

    public static boolean isDirect() {
        return Setting.getTmdbConnectionMode() == Setting.TMDB_DIRECT;
    }

    @NonNull
    public static String normalizeRoot(@Nullable String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) return "";
        String candidate = raw.contains("://") ? raw : "https://" + raw;
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String authority = uri.getRawAuthority() == null ? "" : uri.getRawAuthority();
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || authority.isEmpty()) return "";
            return scheme + "://" + authority + normalizePath(uri.getRawPath());
        } catch (URISyntaxException e) {
            return "";
        }
    }

    @NonNull
    private static String normalizePath(@Nullable String path) {
        String value = path == null ? "" : path.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("/3")) value = value.substring(0, value.length() - 2);
        else if (value.endsWith("/t/p")) value = value.substring(0, value.length() - 4);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return "/";
        return (value.startsWith("/") ? value : "/" + value) + "/";
    }
}
