package com.fongmi.android.tv.playback.vod;

import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.utils.Task;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.Predicate;

/** Page-owned link retrieval. It never starts an extractor, a media source, or a player. */
public final class LibraryPlaybackPrefetch {
    private static final long MAX_AGE_MS = 60_000;
    private static final long RETRY_DELAY_MS = 15_000;
    private static final Handoffs HANDOFFS = new Handoffs(SystemClock::elapsedRealtime, () -> UUID.randomUUID().toString());
    private ListenableFuture<Result> future;
    private Identity identity;
    private Identity attempted;
    private Result result;
    private long completedAt, attemptedAt;
    private int generation;

    /** Call only after a source and its exact episode were confirmed against the selected work. */
    public void prepare(String reference, Vod source, Flag flag, Episode episode, Runnable changed) {
        if (source == null || flag == null || episode == null || reference == null || reference.isEmpty()) { clear(); return; }
        Identity next = Identity.of(reference, VodConfig.getCid(), VodConfig.getUrl(), source.getId(),
                VodPlayRequest.create(source.getSiteKey(), flag, episode));
        long now = SystemClock.elapsedRealtime();
        if (next.equals(identity) && (isRunning() || isReady())) return;
        if (next.equals(attempted) && now - attemptedAt < RETRY_DELAY_MS) return;
        clear();
        identity = attempted = next;
        attemptedAt = now;
        int ticket = generation;
        ListenableFuture<Result> request = Task.submit(() -> SiteApi.preloadContent(next.site, next.flag, next.episode), 10_000, false);
        future = request;
        if (changed != null) changed.run();
        Futures.addCallback(request, Task.callback(value -> App.post(() -> {
            if (!isCurrent(ticket, next)) return;
            future = null;
            if (value != null && value.getFlag().isEmpty()) value.setFlag(next.flag);
            if (isEligible(value) && next.flag.equals(value.getFlag())) {
                result = value;
                completedAt = SystemClock.elapsedRealtime();
            }
            if (changed != null) changed.run();
        }), problem -> App.post(() -> {
            if (!isCurrent(ticket, next)) return;
            future = null;
            result = null;
            if (changed != null) changed.run();
        })), MoreExecutors.directExecutor());
    }

    private boolean isCurrent(int ticket, Identity expected) {
        return ticket == generation && expected.equals(identity)
                && expected.cid == VodConfig.getCid() && expected.configuration.equals(Objects.toString(VodConfig.getUrl(), ""));
    }

    public boolean isRunning() { return future != null && !future.isDone(); }

    public boolean isReady() {
        return result != null && identity != null && identity.cid == VodConfig.getCid()
                && identity.configuration.equals(Objects.toString(VodConfig.getUrl(), "")) && SystemClock.elapsedRealtime() - completedAt < MAX_AGE_MS;
    }

    /** Transfers a ready response to the clicked player; clearing the page cannot revoke this token. */
    public String handoff(String reference, Vod source, Flag flag, Episode episode) {
        if (source == null || flag == null || episode == null) return "";
        Identity requested = Identity.of(reference, VodConfig.getCid(), VodConfig.getUrl(), source.getId(),
                VodPlayRequest.create(source.getSiteKey(), flag, episode));
        if (!requested.equals(identity) || !isReady() || !isEligible(result)) return "";
        String token = HANDOFFS.put(identity, result, completedAt);
        clear();
        return token;
    }

    /** One use, after the full-screen player has confirmed the same source, flag, and episode. */
    public static Result consume(String token, String reference, int cid, String vodId, VodPlayRequest request) {
        if (request == null || cid != VodConfig.getCid()) return null;
        Result value = HANDOFFS.take(token, Identity.of(reference, cid, VodConfig.getUrl(), vodId, request));
        return isEligible(value) && request.accepts(value) ? value : null;
    }

    /** Ends this page attempt; resuming the page may immediately warm the same episode again. */
    public void clear() {
        generation++;
        if (future != null) future.cancel(true);
        future = null;
        identity = null;
        result = null;
        completedAt = 0;
        attempted = null;
        attemptedAt = 0;
    }

    /** Unresolved extractor/parse targets retain the ordinary resolver when playback is confirmed. */
    static boolean isEligible(Result result) {
        return isEligible(result, value -> isDirectHttp(value) && Source.get().isDirectPlaybackUrl(value));
    }

    static boolean isEligible(Result result, Predicate<String> directPlayback) {
        if (!NextEpisodePreloader.isEligible(result) || !result.getClick().isEmpty() || !result.getPlayUrl().isEmpty()
                || result.getTimeshift() != null) return false;
        return directPlayback.test(result.getRealUrl());
    }

    static boolean isDirectHttp(String value) {
        try {
            URI uri = new URI(value);
            String scheme = Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT);
            String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
            String path = Objects.toString(uri.getPath(), "").toLowerCase(Locale.ROOT);
            return (scheme.equals("https") || scheme.equals("http")) && !host.isEmpty()
                    && !host.contains("youtube.com") && !host.contains("youtu.be")
                    && !path.endsWith(".strm");
        } catch (Exception invalid) { return false; }
    }

    record Identity(String reference, int cid, String configuration, String vodId, String site, String flag, String episode) {
        static Identity of(String reference, int cid, String configuration, String vodId, VodPlayRequest request) {
            return new Identity(Objects.toString(reference, ""), cid, Objects.toString(configuration, ""), Objects.toString(vodId, ""),
                    request.getKey(), request.getFlag(), request.getId());
        }
    }

    /** Results stay in process memory; URLs and headers never become intent extras or preferences. */
    static final class Handoffs {
        private record Entry(Identity identity, Result result, long completedAt) { }
        private final Map<String, Entry> entries = new LinkedHashMap<>();
        private final LongSupplier now;
        private final Supplier<String> token;

        Handoffs(LongSupplier now, Supplier<String> token) { this.now = now; this.token = token; }

        synchronized String put(Identity identity, Result result, long completedAt) {
            expire();
            if (now.getAsLong() - completedAt >= MAX_AGE_MS) return "";
            while (entries.size() >= 4) entries.remove(entries.keySet().iterator().next());
            String value = token.get();
            entries.put(value, new Entry(identity, result, completedAt));
            return value;
        }

        synchronized Result take(String token, Identity expected) {
            expire();
            Entry entry = entries.remove(token);
            return entry != null && entry.identity.equals(expected) ? entry.result : null;
        }

        private void expire() {
            long time = now.getAsLong();
            Iterator<Entry> iterator = entries.values().iterator();
            while (iterator.hasNext()) if (time - iterator.next().completedAt >= MAX_AGE_MS) iterator.remove();
        }
    }
}
