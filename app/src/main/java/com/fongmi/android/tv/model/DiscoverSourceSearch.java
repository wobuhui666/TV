package com.fongmi.android.tv.model;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.source.health.SourceHealthManager;
import com.github.catvod.utils.Trans;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Poster search shares the global spider budget and has a whole-session deadline. */
public final class DiscoverSourceSearch {
    public interface Listener {
        void onResult(Result result, int returned, int total);
        void onComplete(boolean deadline);
    }

    private final BoundedSearchBatch<Job, Result> batch = new BoundedSearchBatch<>();
    private final AtomicLong generation = new AtomicLong();
    private Runnable deadline;
    private SiteApi.SearchRequest request;
    private int returned;

    public void start(List<Site> sites, List<String> keywords, Listener listener) {
        start(sites, keywords, List.of(), listener);
    }

    public void start(List<Site> sites, List<String> keywords, List<String> priorityKeys, Listener listener) {
        stop();
        long current = generation.get();
        SiteApi.SearchRequest currentRequest = request = new SiteApi.SearchRequest();
        returned = 0;
        List<Job> jobs = new ArrayList<>();
        String scope = SourceHealthManager.currentScope();
        for (Query query : queries(sites, keywords, priorityKeys)) {
            jobs.add(new Job(query.site, query.keyword,
                    SourceHealthManager.attempt(scope, query.site.getKey(), SourceHealthManager.Phase.SEARCH)));
        }
        if (jobs.isEmpty()) { listener.onComplete(false); return; }
        deadline = () -> {
            if (generation.get() != current) return;
            stop();
            listener.onComplete(true);
        };
        App.post(deadline, 30_000);
        batch.start(jobs, job -> () -> {
            job.attempt.start();
            try { return SiteApi.searchRawContent(job.site, job.keyword, false, "1", currentRequest); }
            finally { job.attempt.finish(); }
        }, new BoundedSearchBatch.Observer<>() {
            @Override public void success(Job job, Result result, long elapsedMs) {
                deliver(job, result, null);
            }
            @Override public void failure(Job job, Throwable error, long elapsedMs) {
                deliver(job, null, error);
            }
            private void deliver(Job job, Result result, Throwable error) {
                App.post(() -> {
                    if (generation.get() != current) return;
                    job.attempt.complete(result, error);
                    listener.onResult(result, ++returned, jobs.size());
                    if (returned == jobs.size()) {
                        stop();
                        listener.onComplete(false);
                    }
                });
            }
        }, Constant.TIMEOUT_SEARCH);
    }

    static List<Query> queries(List<Site> sites, List<String> keywords, List<String> priorityKeys) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (keywords != null) for (String keyword : keywords) {
            if (keyword != null && !keyword.trim().isEmpty()) names.add(Trans.t2s(keyword.trim()));
            if (names.size() == 3) break;
        }
        Map<String, Site> remaining = new LinkedHashMap<>();
        if (sites != null) for (Site site : sites) {
            if (site != null && site.isSearchable() && !site.getKey().isBlank()) remaining.putIfAbsent(site.getKey(), site);
        }
        List<Query> queries = new ArrayList<>();
        // Finish queueing each chosen source's title and aliases before the fallback sources.
        if (priorityKeys != null) for (String key : priorityKeys) {
            Site site = remaining.remove(key);
            if (site != null) for (String name : names) queries.add(new Query(site, name));
        }
        // With no chosen source this is the original keyword-first configuration order.
        for (String name : names) for (Site site : remaining.values()) queries.add(new Query(site, name));
        return queries;
    }

    public void stop() {
        generation.incrementAndGet();
        batch.stop();
        if (request != null) request.cancel();
        request = null;
        if (deadline != null) App.removeCallbacks(deadline);
        deadline = null;
    }

    record Query(Site site, String keyword) {}
    private record Job(Site site, String keyword, SourceHealthManager.Attempt attempt) {}
}
