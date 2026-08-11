package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.github.catvod.utils.Prefers;

public final class SubtitleSearchSetting {

    private SubtitleSearchSetting() {
    }

    public static String getToken() {
        return Prefers.getString("subtitle_search_token", "");
    }

    public static boolean hasToken() {
        return !TextUtils.isEmpty(getEffectiveToken());
    }

    public static String getEffectiveToken() {
        String token = getToken();
        if (!TextUtils.isEmpty(token)) return token;
        try {
            String configured = VodConfig.get().getConfig().getAssrt();
            return configured == null ? "" : configured.trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static void putToken(String token) {
        Prefers.put("subtitle_search_token", token == null ? "" : token.trim());
    }
}
