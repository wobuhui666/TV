package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.player.failure.PlaybackFailure;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class SiteHealthStore {

    private static final String HEALTH_KEY = "site_health_v2";
    private static final String MODE_KEY = "site_sort_mode_";
    private static final String ORDER_KEY = "site_manual_order_";
    private static final long RETAIN_MS = TimeUnit.DAYS.toMillis(90);
    private static final Type TYPE = new TypeToken<Map<String, Health>>() {}.getType();
    private static final Map<String, Health> HEALTH = new LinkedHashMap<>();
    private static final Runnable SAVE = () -> Task.execute(SiteHealthStore::saveNow);
    private static boolean loaded;
    private static boolean dirty;

    private SiteHealthStore() {
    }

    public static void recordSearch(Site site, boolean success, int count, long elapsedMs, String error) {
        if (site == null) return;
        mutate(site.getKey(), health -> {
            if (success) health.searchSuccess++;
            else health.searchFailure++;
            health.lastSearchCount = Math.max(0, count);
            health.lastSearchElapsedMs = Math.max(0, elapsedMs);
            if (!success) health.lastError = trim(error);
        });
    }

    public static void recordDetail(String siteKey, boolean success, long elapsedMs, String error) {
        mutate(siteKey, health -> {
            if (success) health.detailSuccess++;
            else health.detailFailure++;
            health.lastDetailElapsedMs = Math.max(0, elapsedMs);
            if (!success) health.lastError = trim(error);
        });
    }

    public static void recordPlayback(String siteKey, boolean success, PlaybackFailure failure) {
        siteKey = normalizeSiteKey(siteKey);
        if (!success && failure != null && !failure.affectsSiteHealth()) {
            mutate(siteKey, health -> health.lastError = failure.technicalCode());
            return;
        }
        mutate(siteKey, health -> {
            if (success) {
                health.playSuccess++;
                health.lastPlayResult = 1;
            } else {
                health.playFailure++;
                health.lastPlayResult = -1;
                health.lastError = failure == null ? "" : failure.technicalCode();
            }
        });
    }

    /** Accepts both a raw site key and the persisted site@@@vod@@@cid history key. */
    public static String normalizeSiteKey(String key) {
        if (TextUtils.isEmpty(key)) return "";
        int index = key.indexOf(AppDatabase.SYMBOL);
        return index < 0 ? key : key.substring(0, index);
    }

    public static double score(int cid, String siteKey) {
        synchronized (SiteHealthStore.class) {
            Health health = find(cid, siteKey);
            return health == null ? 0 : health.score();
        }
    }

    public static Status status(int cid, String siteKey) {
        synchronized (SiteHealthStore.class) {
            Health health = find(cid, siteKey);
            if (health == null || health.total() == 0) return Status.UNKNOWN;
            double score = health.score();
            if (score >= 20) return Status.GOOD;
            if (score <= -20) return Status.BAD;
            return Status.WARNING;
        }
    }

    public static List<Site> sort(List<Site> configured) {
        int cid = VodConfig.getCid();
        List<Site> result = new ArrayList<>(configured == null ? List.of() : configured);
        SiteSortMode mode = getMode(cid);
        if (mode == SiteSortMode.CONFIG || result.size() < 2) return result;
        Map<String, Integer> original = indexes(result);
        if (mode == SiteSortMode.SMART) {
            result.sort((left, right) -> {
                int value = Double.compare(score(cid, right.getKey()), score(cid, left.getKey()));
                return value != 0 ? value : Integer.compare(original.get(left.getKey()), original.get(right.getKey()));
            });
        } else {
            Map<String, Integer> manual = indexes(reconcileManualOrder(cid, result));
            result.sort((left, right) -> Integer.compare(manual.get(left.getKey()), manual.get(right.getKey())));
        }
        return result;
    }

    public static SiteSortMode getMode(int cid) {
        try {
            return SiteSortMode.valueOf(Prefers.getString(MODE_KEY + cid, SiteSortMode.SMART.name()));
        } catch (Throwable ignored) {
            return SiteSortMode.SMART;
        }
    }

    public static void setMode(int cid, SiteSortMode mode) {
        Prefers.put(MODE_KEY + cid, mode.name());
    }

    public static List<Site> getManualOrder(List<Site> configured) {
        return reconcileManualOrder(VodConfig.getCid(), configured);
    }

    public static List<Site> getDisplayOrder(List<Site> configured) {
        return getMode(VodConfig.getCid()) == SiteSortMode.MANUAL ? getManualOrder(configured) : new ArrayList<>(configured);
    }

    public static void saveManualOrder(List<Site> sites) {
        int cid = VodConfig.getCid();
        List<String> keys = sites.stream().map(Site::getKey).toList();
        Prefers.put(ORDER_KEY + cid, App.gson().toJson(keys));
        setMode(cid, SiteSortMode.MANUAL);
    }

    public static void resetManualOrder() {
        int cid = VodConfig.getCid();
        Prefers.remove(ORDER_KEY + cid);
        setMode(cid, SiteSortMode.CONFIG);
    }

    public static void clear(int cid) {
        synchronized (SiteHealthStore.class) {
            ensureLoaded();
            String prefix = cid + ":";
            HEALTH.keySet().removeIf(key -> key.startsWith(prefix));
            dirty = true;
        }
        saveNow();
    }

    static double calculateScore(int searchSuccess, int searchFailure, int detailSuccess, int detailFailure,
                                 int playSuccess, int playFailure, int resultCount, long searchMs, long detailMs,
                                 int lastPlayResult) {
        int successWeight = searchSuccess + detailSuccess * 2 + playSuccess * 5;
        int failureWeight = searchFailure + detailFailure * 2 + playFailure * 5;
        double score = 60.0 * (successWeight - failureWeight) / (successWeight + failureWeight + 4.0);
        score += Math.min(Math.max(0, resultCount), 20) * 1.2;
        score -= Math.min(Math.max(0, searchMs), 8_000) / 1000.0;
        score -= Math.min(Math.max(0, detailMs), 8_000) / 1500.0;
        score += Integer.compare(lastPlayResult, 0) * 18;
        return score;
    }

    private static List<Site> reconcileManualOrder(int cid, List<Site> configured) {
        List<String> saved = manualKeys(cid);
        Map<String, Site> current = new LinkedHashMap<>();
        for (Site site : configured) current.put(site.getKey(), site);
        List<Site> result = new ArrayList<>();
        for (String key : saved) {
            Site site = current.remove(key);
            if (site != null) result.add(site);
        }
        result.addAll(current.values());
        return result;
    }

    private static List<String> manualKeys(int cid) {
        try {
            Type type = TypeToken.getParameterized(List.class, String.class).getType();
            List<String> result = App.gson().fromJson(Prefers.getString(ORDER_KEY + cid, "[]"), type);
            return result == null ? List.of() : result;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static Map<String, Integer> indexes(List<Site> sites) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < sites.size(); i++) result.put(sites.get(i).getKey(), i);
        return result;
    }

    private static void mutate(String siteKey, java.util.function.Consumer<Health> update) {
        if (TextUtils.isEmpty(siteKey) || SiteApi.PUSH.equals(siteKey)) return;
        synchronized (SiteHealthStore.class) {
            ensureLoaded();
            Health health = HEALTH.computeIfAbsent(VodConfig.getCid() + ":" + siteKey, key -> new Health());
            update.accept(health);
            health.updatedAt = System.currentTimeMillis();
            dirty = true;
        }
        App.removeCallbacks(SAVE);
        App.post(SAVE, 1500);
    }

    private static Health find(int cid, String siteKey) {
        ensureLoaded();
        return HEALTH.get(cid + ":" + siteKey);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Map<String, Health> restored = App.gson().fromJson(Prefers.getString(HEALTH_KEY, "{}"), TYPE);
            if (restored != null) HEALTH.putAll(restored);
        } catch (Throwable ignored) {
        }
        prune();
    }

    private static void saveNow() {
        Map<String, Health> copy;
        synchronized (SiteHealthStore.class) {
            ensureLoaded();
            if (!dirty) return;
            prune();
            copy = new LinkedHashMap<>(HEALTH);
            dirty = false;
        }
        Prefers.put(HEALTH_KEY, App.gson().toJson(copy));
    }

    private static void prune() {
        long expired = System.currentTimeMillis() - RETAIN_MS;
        HEALTH.entrySet().removeIf(entry -> entry.getValue().updatedAt > 0 && entry.getValue().updatedAt < expired);
    }

    private static String trim(String error) {
        if (error == null) return "";
        return error.length() <= 120 ? error : error.substring(0, 120);
    }

    public enum Status {UNKNOWN, GOOD, WARNING, BAD}

    private interface Update {
        void apply(Health health);
    }

    public static class Health {
        int searchSuccess;
        int searchFailure;
        int detailSuccess;
        int detailFailure;
        int playSuccess;
        int playFailure;
        int lastSearchCount;
        int lastPlayResult;
        long lastSearchElapsedMs;
        long lastDetailElapsedMs;
        long updatedAt;
        String lastError;

        int total() {
            return searchSuccess + searchFailure + detailSuccess + detailFailure + playSuccess + playFailure;
        }

        double score() {
            return calculateScore(searchSuccess, searchFailure, detailSuccess, detailFailure, playSuccess, playFailure,
                    lastSearchCount, lastSearchElapsedMs, lastDetailElapsedMs, lastPlayResult);
        }
    }
}
