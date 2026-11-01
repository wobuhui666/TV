package com.fongmi.android.tv.ai.skip;

import com.fongmi.android.tv.ai.subtitle.SecretStore;
import com.github.catvod.utils.Prefers;

public final class AiSkipSettings {
    private static final String PREFIX = "ai_skip_";

    private AiSkipSettings() {
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(PREFIX + "enabled", false);
    }

    public static void setEnabled(boolean enabled) {
        Prefers.put(PREFIX + "enabled", enabled);
    }

    public static String getBaseUrl() {
        return Prefers.getString(PREFIX + "base_url", "").trim().replaceAll("/+$", "");
    }

    public static void setBaseUrl(String url) {
        Prefers.put(PREFIX + "base_url", url == null ? "" : url.trim().replaceAll("/+$", ""));
    }

    public static String getToken() {
        return SecretStore.getAiSkipToken();
    }

    public static void setToken(String token) {
        SecretStore.putAiSkipToken(token);
    }

    public static boolean isConfigured() {
        return isEnabled() && !getBaseUrl().isEmpty() && !getToken().isEmpty();
    }
}
