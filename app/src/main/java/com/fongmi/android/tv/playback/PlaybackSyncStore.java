package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PlaybackSyncStore {

    private static final String KEY = "playback_sync_endpoints_v1";
    private static final Type TYPE = TypeToken.getParameterized(List.class, PlaybackSyncEndpoint.class).getType();

    private PlaybackSyncStore() {
    }

    public static synchronized List<PlaybackSyncEndpoint> get() {
        try {
            List<PlaybackSyncEndpoint> items = App.gson().fromJson(Prefers.getString(KEY, "[]"), TYPE);
            if (items == null) return new ArrayList<>();
            for (PlaybackSyncEndpoint endpoint : items) normalize(endpoint);
            return new ArrayList<>(items);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    public static synchronized void save(List<PlaybackSyncEndpoint> endpoints) {
        for (PlaybackSyncEndpoint endpoint : endpoints) if (endpoint.id == null || endpoint.id.isEmpty()) endpoint.id = UUID.randomUUID().toString();
        Prefers.put(KEY, App.gson().toJson(endpoints));
    }

    public static synchronized void save(PlaybackSyncEndpoint endpoint) {
        List<PlaybackSyncEndpoint> items = get();
        items.removeIf(item -> item.id.equals(endpoint.id));
        items.add(endpoint);
        save(items);
    }

    public static void syncNow() {
        syncMatching(endpoint -> "remote".equals(endpoint.kind));
    }

    public static void syncOnStart() {
        syncMatching(endpoint -> "remote".equals(endpoint.kind) && endpoint.syncOnStart);
    }

    public static void syncDue() {
        long now = System.currentTimeMillis();
        String configKey = PlaybackConfigIdentity.currentKey();
        syncMatching(endpoint -> {
            long last = endpoint.lastSuccessByConfig.getOrDefault(configKey, endpoint.lastSuccessAt);
            return "remote".equals(endpoint.kind) && endpoint.periodMinutes > 0
                    && now - last >= java.util.concurrent.TimeUnit.MINUTES.toMillis(endpoint.periodMinutes);
        });
    }

    private static void syncMatching(java.util.function.Predicate<PlaybackSyncEndpoint> filter) {
        if (!PlaybackSyncSetting.isEnabled()) return;
        Task.execute(() -> {
            List<PlaybackSyncEndpoint> endpoints = get();
            for (PlaybackSyncEndpoint endpoint : endpoints) if (endpoint.enabled && filter.test(endpoint)) PlaybackRemoteSyncer.sync(endpoint);
            save(endpoints);
            PlaybackWebhookDispatcher.drain();
        });
    }

    public static void quickCloudflare(String name, String url, String token) {
        List<PlaybackSyncEndpoint> items = get();
        PlaybackSyncEndpoint remote = endpoint("remote", name + "（拉取）", url, token);
        PlaybackSyncEndpoint webhook = endpoint("webhook", name + "（推送）", url, token);
        items.add(remote);
        items.add(webhook);
        save(items);
    }

    private static PlaybackSyncEndpoint endpoint(String kind, String name, String url, String token) {
        PlaybackSyncEndpoint endpoint = new PlaybackSyncEndpoint();
        endpoint.id = UUID.randomUUID().toString();
        endpoint.kind = kind;
        endpoint.name = name == null ? "" : name;
        endpoint.url = url == null ? "" : url;
        endpoint.token = token == null ? "" : token;
        return endpoint;
    }

    private static void normalize(PlaybackSyncEndpoint endpoint) {
        if (endpoint.siteKeys == null) endpoint.siteKeys = new ArrayList<>();
        if (endpoint.events == null) endpoint.events = new ArrayList<>(List.of("playback.progress", "playback.ended", "playback.deleted"));
        if (endpoint.customFields == null) endpoint.customFields = new ArrayList<>();
        if (endpoint.cursors == null) endpoint.cursors = new java.util.HashMap<>();
        if (endpoint.lastSuccessByConfig == null) endpoint.lastSuccessByConfig = new java.util.HashMap<>();
        if (endpoint.lastErrorByConfig == null) endpoint.lastErrorByConfig = new java.util.HashMap<>();
        if (endpoint.kind == null) endpoint.kind = "remote";
        if (endpoint.fieldPreset == null) endpoint.fieldPreset = "standard";
        if (endpoint.lastError == null) endpoint.lastError = "";
    }
}
