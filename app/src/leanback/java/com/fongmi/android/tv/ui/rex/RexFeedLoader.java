package com.fongmi.android.tv.ui.rex;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.rex.DoubanBridgeClient;
import com.fongmi.android.tv.api.rex.RexNowClient;
import com.fongmi.android.tv.api.rex.RexTmdbClient;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.RexSection;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rex 栏目卡的数据加载编排。
 *
 * <p>逐栏目并行；栏目独立成败——失败或空栏目直接消失，不影响其他栏目；
 * 静态 30 分钟缓存（命中先渲染，同时不重复拉）；24 秒兜底截止（到点交付已到达的部分）；
 * 关闭时按 tag 级联取消三家客户端的在途请求。
 */
public final class RexFeedLoader {

    public static final long DEADLINE_MS = 24_000;
    private static final long CACHE_TTL = 30 * 60 * 1000L;
    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    public interface Listener {

        void onResults(List<RexFeedResult> results);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object tag = new Object();
    private final Map<String, List<Vod>> merged = new LinkedHashMap<>();
    private List<RexSection> sections = List.of();
    private Listener listener;
    private Runnable deadline;
    private Runnable delivery;
    private int pending;
    private int generation;
    private boolean done;
    private boolean closed;

    public void load(List<RexSection> sections, Listener listener) {
        if (closed) return;
        int request = ++generation;
        cancelCalls();
        cancelDelivery();
        this.sections = sections;
        this.listener = listener;
        done = false;
        pending = 0;
        Map<String, List<Vod>> previous = new LinkedHashMap<>(merged);
        List<RexSection> missing = new ArrayList<>();
        merged.clear();
        cancelDeadline();
        for (RexSection section : sections) {
            List<Vod> cached = cached(section.getKey());
            if (cached != null) merged.put(section.getKey(), cached);
            else {
                pending++;
                missing.add(section);
                // A partial refresh must not remove still-visible sections while they load.
                if (previous.containsKey(section.getKey())) merged.put(section.getKey(), previous.get(section.getKey()));
            }
        }
        if (pending == 0) {
            done = true;
            deliver(request);
            return;
        }
        if (!merged.isEmpty()) deliver(request);
        if (closed || request != generation) return;
        deadline = () -> {
            if (closed || done || request != generation) return;
            done = true;
            cancelCalls();
            cancelDelivery();
            deliver(request);
        };
        handler.postDelayed(deadline, DEADLINE_MS);
        for (RexSection section : missing) fetch(section, request);
    }

    public void close() {
        closed = true;
        generation++;
        cancelDeadline();
        cancelDelivery();
        cancelCalls();
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private void fetch(RexSection section, int request) {
        RexNowClient.ItemsListener feedListener = new RexNowClient.ItemsListener() {
            @Override
            public void onSuccess(List<Vod> items) {
                accept(section, items, request);
            }

            @Override
            public void onError(Exception e) {
                accept(section, List.of(), request);
            }
        };
        switch (section.getSource()) {
            case TMDB_LIST, TMDB_DISCOVER -> RexTmdbClient.fetch(section.getQuery(), BuildConfig.TMDB_API_KEY, tag, new RexTmdbClient.ItemsListener() {
                @Override
                public void onSuccess(List<Vod> items) {
                    accept(section, items, request);
                }

                @Override
                public void onError(Exception e) {
                    accept(section, List.of(), request);
                }
            });
            case REXNOW_REGIONAL -> RexNowClient.fetchRegional(section.getRegion(), section.getCategory(), tag, feedListener);
            case REXNOW_DOUBAN -> RexNowClient.fetchDouban(section.getFeedCategory(), tag, feedListener);
            case REXNOW_ANIME -> RexNowClient.fetchAnime(tag, new RexNowClient.WeeklyListener() {
                @Override
                public void onSuccess(Map<String, List<Vod>> weekly) {
                    List<Vod> items = RexNowClient.todayFromWeekly(weekly, System.currentTimeMillis());
                    if (items.isEmpty()) {
                        List<Vod> all = new ArrayList<>();
                        for (List<Vod> group : weekly.values()) all.addAll(group);
                        items = RexNowClient.mergeDistinct(List.of(), all, 0);
                    }
                    accept(section, items, request);
                }

                @Override
                public void onError(Exception e) {
                    accept(section, List.of(), request);
                }
            });
            case BRIDGE_CATALOG -> fetchBridge(section, request);
        }
    }

    /** 云桥：sk 为空或 401/失败时回退 rexnow 豆瓣 feed（按栏目媒体类型过滤）。 */
    private void fetchBridge(RexSection section, int request) {
        String sk = Setting.getBridgeSk();
        if (sk == null || sk.trim().isEmpty()) {
            fetchDoubanFallback(section, request);
            return;
        }
        DoubanBridgeClient.fetchCatalog(Setting.getBridgeBase(), sk, null, section.getCollectionId(), 0, tag, new DoubanBridgeClient.Listener() {
            @Override
            public void onSuccess(List<Vod> items) {
                accept(section, items, request);
            }

            @Override
            public void onError(Exception e) {
                fetchDoubanFallback(section, request);
            }

            @Override
            public void onUnauthorized() {
                fetchDoubanFallback(section, request);
            }
        });
    }

    private void fetchDoubanFallback(RexSection section, int request) {
        String category = section.getFeedCategory().isEmpty() ? "trending" : section.getFeedCategory();
        String mediaType = section.getCollectionId().startsWith("movie") ? DiscoverMediaKey.MOVIE : DiscoverMediaKey.TV;
        RexNowClient.fetchDouban(category, tag, new RexNowClient.ItemsListener() {
            @Override
            public void onSuccess(List<Vod> items) {
                List<Vod> filtered = new ArrayList<>();
                for (Vod item : items) if (mediaType.equals(item.getTypeName())) filtered.add(item);
                accept(section, filtered, request);
            }

            @Override
            public void onError(Exception e) {
                accept(section, List.of(), request);
            }
        });
    }

    private void accept(RexSection section, List<Vod> items, int request) {
        if (Looper.myLooper() != handler.getLooper()) {
            handler.post(() -> accept(section, items, request));
            return;
        }
        if (closed || done || request != generation) return;
        List<Vod> capped = items.isEmpty()
                ? List.of()
                : new ArrayList<>(items.subList(0, Math.min(items.size(), section.getLimit())));
        merged.put(section.getKey(), capped);
        if (!capped.isEmpty()) CACHE.put(section.getKey(), new CacheEntry(capped));
        pending = Math.max(0, pending - 1);
        if (pending == 0) {
            done = true;
            cancelDeadline();
            cancelDelivery();
            deliver(request);
        } else if (!capped.isEmpty() && delivery == null) {
            // Several feeds often finish together; publish one bounded UI update.
            delivery = () -> {
                delivery = null;
                if (!done) deliver(request);
            };
            handler.postDelayed(delivery, 32);
        }
    }

    private void deliver(int request) {
        if (closed || request != generation) return;
        List<RexFeedResult> results = new ArrayList<>();
        for (RexSection section : sections) {
            List<Vod> items = merged.get(section.getKey());
            if (items != null && !items.isEmpty()) results.add(new RexFeedResult(section, items));
        }
        if (listener != null) listener.onResults(results);
    }

    private void cancelDeadline() {
        if (deadline != null) handler.removeCallbacks(deadline);
        deadline = null;
    }

    private void cancelDelivery() {
        if (delivery != null) handler.removeCallbacks(delivery);
        delivery = null;
    }

    private void cancelCalls() {
        RexTmdbClient.cancel(tag);
        RexNowClient.cancel(tag);
        DoubanBridgeClient.cancel(tag);
    }

    @Nullable
    static List<Vod> cached(String key) {
        CacheEntry entry = CACHE.get(key);
        return entry == null || entry.expired() ? null : entry.copy();
    }

    private static final class CacheEntry {

        private final List<Vod> items;
        private final long time;

        private CacheEntry(List<Vod> items) {
            this.items = new ArrayList<>(items);
            this.time = System.currentTimeMillis();
        }

        private boolean expired() {
            return System.currentTimeMillis() - time > CACHE_TTL;
        }

        private List<Vod> copy() {
            return new ArrayList<>(items);
        }
    }
}
