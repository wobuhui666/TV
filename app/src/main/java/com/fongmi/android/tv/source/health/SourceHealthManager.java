package com.fongmi.android.tv.source.health;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.github.catvod.utils.Prefers;

import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class SourceHealthManager {
    public enum Phase { SEARCH, DETAIL }
    private static final SourceHealthStore STORE = new SourceHealthStore();
    private static final AtomicLong EPOCH = new AtomicLong();
    private record Scope(String url, long epoch, String value) { }
    private static volatile Scope cachedScope;
    private static final String SORT = "source_health_sort";
    private SourceHealthManager() {}

    public static String currentScope() { return scopeFor(VodConfig.getUrl()); }
    static String scopeFor(String url) {
        String value = url == null ? "" : url;
        long epoch = EPOCH.get();
        Scope previous = cachedScope;
        if (previous != null && previous.epoch == epoch && previous.url.equals(value)) return previous.value;
        Scope prepared = new Scope(value, epoch, hash(value) + ":" + epoch);
        cachedScope = prepared;
        return prepared.value;
    }
    public static void clear() { EPOCH.incrementAndGet(); cachedScope = null; STORE.clear(); }
    public static boolean isSortEnabled() { return Prefers.getBoolean(SORT, false); }
    public static void setSortEnabled(boolean enabled) { Prefers.put(SORT, enabled); }

    public static List<SiteHealth> snapshot(List<Site> sites) {
        String scope = currentScope();
        List<SiteHealth> rows = new ArrayList<>();
        for (Site site : sites) rows.add(new SiteHealth(site, STORE.get(key(scope, site.getKey()))));
        return rows;
    }

    /** Stable sort of a copy; configuration order and matching/reliability semantics are unchanged. */
    public static List<Site> sort(List<Site> sites) {
        List<Site> result = new ArrayList<>(sites);
        if (!isSortEnabled()) return result;
        List<SiteHealth> rows = snapshot(sites);
        rows.sort(Comparator.comparingInt(SiteHealth::rank).thenComparingLong(SiteHealth::latency));
        result.clear();
        for (SiteHealth row : rows) result.add(row.site);
        return result;
    }

    public static void record(String scope, String siteKey, Phase phase, Result result, Throwable failure, long elapsedMs) {
        record(scope, siteKey, phase, result, failure, elapsedMs, true);
    }

    private static void record(String scope, String siteKey, Phase phase, Result result, Throwable failure, long elapsedMs, boolean onlineAtStart) {
        if (!scope.equals(currentScope()) || ignored(failure)) return;
        SourceHealthStore.Outcome outcome = failure != null || (result != null && result.hasMsg())
                ? SourceHealthStore.Outcome.FAILURE : result == null || result.getList().isEmpty()
                ? SourceHealthStore.Outcome.EMPTY : SourceHealthStore.Outcome.SUCCESS;
        // A server error represented as Result.error is also suppressed while globally offline.
        if (!shouldRecord(outcome, failure, onlineAtStart, isOnline())) return;
        STORE.record(key(scope, siteKey), phase == Phase.SEARCH, outcome, elapsedMs);
    }

    public static Attempt attempt(String siteKey, Phase phase) { return new Attempt(currentScope(), siteKey, phase); }
    public static Attempt attempt(String scope, String siteKey, Phase phase) { return new Attempt(scope, siteKey, phase); }

    static boolean ignored(Throwable error) {
        for (int i = 0; error != null && i < 8; i++, error = error.getCause()) {
            if (error instanceof InterruptedException || error instanceof InterruptedIOException
                    || error instanceof CancellationException || error instanceof TimeoutException) return true;
        }
        return false;
    }

    static boolean shouldRecord(SourceHealthStore.Outcome outcome, Throwable error, boolean onlineAtStart, boolean onlineAtEnd) {
        return !ignored(error) && (outcome != SourceHealthStore.Outcome.FAILURE || onlineAtStart && onlineAtEnd);
    }

    private static boolean isOnline() {
        try {
            ConnectivityManager manager = (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return true;
            NetworkInfo network = manager.getActiveNetworkInfo();
            return network != null && network.isConnected();
        } catch (RuntimeException ignored) { return true; }
    }

    private static String key(String scope, String siteKey) { return hash(scope + "\n" + siteKey); }
    static String hash(String value) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))) {
                result.append("0123456789abcdef".charAt((b >>> 4) & 15)).append("0123456789abcdef".charAt(b & 15));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Attempt {
        private final String scope;
        private final String siteKey;
        private final Phase phase;
        private volatile long started;
        private volatile long elapsedMs;
        private volatile boolean onlineAtStart;
        private Attempt(String scope, String siteKey, Phase phase) { this.scope = scope; this.siteKey = siteKey; this.phase = phase; }
        public void start() { onlineAtStart = isOnline(); started = System.nanoTime(); }
        public void finish() { if (started != 0) elapsedMs = Math.max(0, (System.nanoTime() - started) / 1_000_000); }
        public void complete(Result result, Throwable error) {
            if (started == 0) return;
            record(scope, siteKey, phase, result, error, elapsedMs, onlineAtStart);
        }
    }

    public static final class SiteHealth {
        public final Site site;
        public final SourceHealthStore.Stats search;
        public final SourceHealthStore.Stats detail;
        private SiteHealth(Site site, SourceHealthStore.Entry entry) { this.site = site; search = entry.search; detail = entry.detail; }
        public Site getSite() { return site; }
        public String getLabel() { return "搜索 " + label(search) + "；详情 " + label(detail); }
        private int rank() { return search == null ? 1 : search.outcome == SourceHealthStore.Outcome.FAILURE ? 2 + search.consecutiveFailures : 0; }
        private long latency() { return search == null ? Long.MAX_VALUE : search.elapsedMs; }
        private static String label(SourceHealthStore.Stats stats) {
            if (stats == null) return "未测";
            String status = stats.outcome == SourceHealthStore.Outcome.SUCCESS ? "成功"
                    : stats.outcome == SourceHealthStore.Outcome.EMPTY ? "无结果" : "连续失败 " + stats.consecutiveFailures + " 次";
            return status + " · " + stats.elapsedMs + " ms";
        }
    }
}
