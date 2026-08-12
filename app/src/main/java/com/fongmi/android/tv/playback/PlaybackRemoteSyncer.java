package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

public final class PlaybackRemoteSyncer {

    private PlaybackRemoteSyncer() {
    }

    public static boolean sync(PlaybackSyncEndpoint endpoint) {
        if (endpoint == null || !endpoint.enabled || !PlaybackSyncSetting.isEnabled() || Setting.isIncognito() || endpoint.url.isEmpty()) return false;
        String configKey = PlaybackConfigIdentity.currentKey();
        long cursor = cursorFor(endpoint, configKey);
        try {
            boolean more;
            do {
                int limit = Math.clamp(endpoint.limit, 1, 1000);
                Request request = new Request.Builder().url(endpoint.url)
                        .header("X-WebHTV-Config-Key", configKey)
                        .header("X-WebHTV-Config-Name", VodConfig.getDesc())
                        .header("X-WebHTV-Since", String.valueOf(cursor))
                        .header("X-WebHTV-Limit", String.valueOf(limit))
                        .header("X-WebHTV-Token", endpoint.token).get().build();
                try (Response response = OkHttp.client(TimeUnit.SECONDS.toMillis(15)).newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("HTTP " + response.code());
                    PlaybackSyncProtocol.Page page = PlaybackSyncProtocol.parsePage(response.body().string(), cursor);
                    for (JsonElement item : page.items()) applyItem(endpoint, item);
                    cursor = page.cursor();
                    endpoint.cursor = cursor;
                    endpoint.cursors.put(configKey, cursor);
                    more = page.hasMore();
                }
            } while (more);
            endpoint.lastSuccessAt = System.currentTimeMillis();
            endpoint.lastError = "";
            endpoint.lastSuccessByConfig.put(configKey, endpoint.lastSuccessAt);
            endpoint.lastErrorByConfig.remove(configKey);
            return true;
        } catch (Throwable error) {
            endpoint.lastError = TextUtils.isEmpty(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage();
            endpoint.lastErrorByConfig.put(configKey, endpoint.lastError);
            return false;
        }
    }

    private static long cursorFor(PlaybackSyncEndpoint endpoint, String configKey) {
        if (endpoint.cursors.containsKey(configKey)) return Math.max(0, endpoint.cursors.get(configKey));
        if (endpoint.cursors.isEmpty() && endpoint.cursor > 0) return endpoint.cursor;
        return 0;
    }

    private static void applyItem(PlaybackSyncEndpoint endpoint, JsonElement item) {
        if (!item.isJsonObject()) throw new IllegalStateException("无效同步记录");
        JsonObject object = item.getAsJsonObject();
        String event = object.has("event") ? object.get("event").getAsString() : "playback.progress";
        if ("playback.deleted".equals(event)) {
            String scope = object.has("scope") ? object.get("scope").getAsString() : "item";
            String siteKey = object.has("siteKey") ? object.get("siteKey").getAsString() : "";
            String vodId = object.has("vodId") ? object.get("vodId").getAsString() : "";
            long deletedAt = object.has("deletedAt") ? object.get("deletedAt").getAsLong() : object.get("updatedAt").getAsLong();
            String configKey = object.has("configKey") ? object.get("configKey").getAsString() : PlaybackConfigIdentity.currentKey();
            if (!endpoint.siteKeys.isEmpty() && "all".equals(scope)) {
                for (String allowedSite : endpoint.siteKeys) PlaybackMerge.delete("site", allowedSite, "", deletedAt, configKey);
                return;
            }
            if (!endpoint.siteKeys.isEmpty() && !endpoint.siteKeys.contains(siteKey)) return;
            PlaybackMerge.delete(scope, siteKey, vodId, deletedAt, configKey);
            return;
        }
        PlaybackProgress progress = App.gson().fromJson(item, PlaybackProgress.class);
        if (!"webhtv.playback.v1".equals(progress.schema) || TextUtils.isEmpty(progress.siteKey) || TextUtils.isEmpty(progress.vodId) || progress.updatedAt <= 0) {
            throw new IllegalStateException("无效同步记录");
        }
        if (!endpoint.siteKeys.isEmpty() && !endpoint.siteKeys.contains(progress.siteKey)) return;
        PlaybackMerge.apply(progress);
    }
}
