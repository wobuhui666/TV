package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlaybackEvents {

    private static final ConcurrentHashMap<String, Long> LAST_PROGRESS = new ConcurrentHashMap<>();

    private PlaybackEvents() {
    }

    public static void progress(History history, boolean ended) {
        if (history == null || Setting.isIncognito() || !PlaybackSyncSetting.isEnabled()) return;
        Task.execute(() -> dispatch(ended ? "playback.ended" : "playback.progress", history, "item", false));
    }

    public static void deleted(History history) {
        if (history == null) return;
        PlaybackMerge.writeTombstone("item", history.getSiteKey(), history.getVodId(), System.currentTimeMillis());
        if (Setting.isIncognito() || !PlaybackSyncSetting.isEnabled()) return;
        Task.execute(() -> dispatch("playback.deleted", history, "item", false));
    }

    public static void deletedAll() {
        PlaybackMerge.writeTombstone("all", "", "", System.currentTimeMillis());
        if (Setting.isIncognito() || !PlaybackSyncSetting.isEnabled()) return;
        Task.execute(() -> dispatch("playback.deleted", null, "all", true));
    }

    private static void dispatch(String event, History history, String scope, boolean confirm) {
        List<PlaybackSyncEndpoint> endpoints = PlaybackSyncStore.get();
        String eventId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        for (PlaybackSyncEndpoint endpoint : endpoints) {
            if (!endpoint.enabled || !"webhook".equals(endpoint.kind) || endpoint.url.isEmpty() || !endpoint.events.contains(event)) continue;
            if (history != null && !endpoint.siteKeys.isEmpty() && !endpoint.siteKeys.contains(history.getSiteKey())) continue;
            if ("playback.progress".equals(event) && !canSendProgress(endpoint, history, now)) continue;
            JsonObject payload = payload(eventId, event, history, scope, confirm, endpoint.fieldPreset, endpoint.customFields, now);
            PlaybackWebhookDispatcher.enqueue(eventId, endpoint.id, endpoint.url, payload.toString(), endpoint.retries);
        }
    }

    private static boolean canSendProgress(PlaybackSyncEndpoint endpoint, History history, long now) {
        String key = endpoint.id + ':' + history.getKey();
        long interval = Math.max(0, endpoint.progressIntervalSeconds) * 1000L;
        Long previous = LAST_PROGRESS.put(key, now);
        return previous == null || now - previous >= interval;
    }

    static JsonObject payload(String eventId, String event, History history, String scope, boolean confirm, String preset, List<String> customFields, long now) {
        return payload(eventId, event, PlaybackConfigIdentity.currentKey(), history, scope, confirm, preset, customFields, now);
    }

    static JsonObject payload(String eventId, String event, String configKey, History history, String scope, boolean confirm, String preset, List<String> customFields, long now) {
        JsonObject value = new JsonObject();
        value.addProperty("schema", "webhtv.playback.v1");
        value.addProperty("eventId", eventId);
        value.addProperty("event", event);
        value.addProperty("scope", scope);
        value.addProperty("configKey", configKey);
        value.addProperty("updatedAt", now);
        if (confirm) value.addProperty("confirm", true);
        if (history == null) return value;
        if ("custom".equals(preset)) {
            for (String field : customFields == null ? List.<String>of() : customFields) addHistoryField(value, history, field);
            return value;
        }
        addHistoryField(value, history, "siteKey");
        addHistoryField(value, history, "vodId");
        if ("basic".equals(preset)) return value;
        for (String field : List.of("positionMs", "durationMs", "flag", "episodeName", "episodeUrl")) addHistoryField(value, history, field);
        if (!"full".equals(preset)) return value;
        for (String field : List.of("vodName", "vodPic", "speed")) addHistoryField(value, history, field);
        return value;
    }

    private static void addHistoryField(JsonObject value, History history, String field) {
        switch (field) {
            case "siteKey" -> value.addProperty(field, history.getSiteKey());
            case "vodId" -> value.addProperty(field, history.getVodId());
            case "positionMs" -> value.addProperty(field, history.getPosition());
            case "durationMs" -> value.addProperty(field, history.getDuration());
            case "flag" -> value.addProperty(field, history.getVodFlag());
            case "episodeName" -> value.addProperty(field, history.getVodRemarks());
            case "episodeUrl" -> value.addProperty(field, history.getEpisodeUrl());
            case "vodName" -> value.addProperty(field, history.getVodName());
            case "vodPic" -> value.addProperty(field, history.getVodPic());
            case "speed" -> value.addProperty(field, history.getSpeed());
        }
    }
}
