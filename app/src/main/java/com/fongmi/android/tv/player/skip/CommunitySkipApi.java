package com.fongmi.android.tv.player.skip;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Read-only metadata lookups. One instance belongs to one cancellable playback session. */
public final class CommunitySkipApi {

    private static final int MAX_BODY_BYTES = 512 * 1024;
    private static final long LOOKUP_BUDGET_MS = 6_000;
    private static final long CACHE_SUCCESS_MS = TimeUnit.HOURS.toMillis(6);
    private static final long CACHE_EMPTY_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long CACHE_FAILURE_MS = TimeUnit.SECONDS.toMillis(30);
    private static final String THEINTRODB = "https://api.theintrodb.org/v4/media";
    private static final String INTRODB = "https://api.introdb.app/segments";
    private static final String THEOTHERDB = "https://playback.theotherdb.org/api/metadata";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<>(48, 0.75f, true);
    private static final Map<String, Long> RATE_LIMITS = new LinkedHashMap<>();

    private final Object lock = new Object();
    private final String theIntroDbUrl;
    private final String introDbUrl;
    private final String theOtherDbUrl;
    private final boolean useCache;
    private OkHttpClient client;
    private Call activeCall;
    private volatile boolean cancelled;
    private boolean successfulResponse;

    public CommunitySkipApi() {
        this(null, THEINTRODB, INTRODB, THEOTHERDB, true);
    }

    CommunitySkipApi(OkHttpClient client, String theIntroDbUrl, String introDbUrl, String theOtherDbUrl) {
        this(client, theIntroDbUrl, introDbUrl, theOtherDbUrl, false);
    }

    CommunitySkipApi(OkHttpClient client, String theIntroDbUrl, String introDbUrl, String theOtherDbUrl, boolean useCache) {
        this.client = client;
        this.theIntroDbUrl = theIntroDbUrl;
        this.introDbUrl = introDbUrl;
        this.theOtherDbUrl = theOtherDbUrl;
        this.useCache = useCache;
    }

    /** Must be called on a worker. An unavailable provider never delays or fails playback. */
    public List<SkipSegment> fetch(EpisodeIdentity identity, long durationMs) {
        return fetch(identity, durationMs, null);
    }

    /** Publishes usable cumulative ranges on this worker as each provider responds. */
    public List<SkipSegment> fetch(EpisodeIdentity identity, long durationMs, Consumer<List<SkipSegment>> onSegments) {
        if (cancelled || identity == null || durationMs <= 0) return List.of();
        String key = identity.key() + ":" + identity.imdbId() + ":" + durationMs;
        List<SkipSegment> cachedSegments = null;
        if (useCache) synchronized (CACHE) {
            CacheEntry cached = CACHE.get(key);
            if (cached != null && cached.expiresMs > System.currentTimeMillis()) cachedSegments = cached.segments;
        }
        if (cachedSegments != null) {
            publish(cachedSegments, onSegments, List.of());
            return cancelled ? List.of() : cachedSegments;
        }
        successfulResponse = false;
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOOKUP_BUDGET_MS);
        List<SkipSegment> result = new ArrayList<>();
        List<SkipSegment> published = List.of();
        try {
            mergeMissingTypes(result, CommunitySkipParser.theIntroDb(read(theIntroRequest(identity, durationMs), deadlineNs), identity, durationMs));
            published = publish(result, onSegments, published);
            if (!complete(result) && !cancelled && !identity.imdbId().isEmpty()) {
                mergeMissingTypes(result, CommunitySkipParser.introDb(read(introRequest(identity), deadlineNs), identity, durationMs));
                published = publish(result, onSegments, published);
            }
            if (!complete(result) && !cancelled) {
                mergeMissingTypes(result, CommunitySkipParser.theOtherDb(read(theOtherRequest(identity), deadlineNs), identity, durationMs));
                publish(result, onSegments, published);
            }
        } catch (RuntimeException ignored) {
            // Malformed metadata is equivalent to a provider without coverage.
        }
        if (cancelled) return List.of();
        List<SkipSegment> safe = CommunitySkipParser.normalize(result);
        if (useCache) synchronized (CACHE) {
            long ttl = !safe.isEmpty() ? CACHE_SUCCESS_MS : successfulResponse ? CACHE_EMPTY_MS : CACHE_FAILURE_MS;
            CACHE.put(key, new CacheEntry(safe, System.currentTimeMillis() + ttl));
            while (CACHE.size() > 48) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return safe;
    }

    private List<SkipSegment> publish(List<SkipSegment> segments, Consumer<List<SkipSegment>> onSegments,
                                     List<SkipSegment> previous) {
        List<SkipSegment> snapshot = CommunitySkipParser.normalize(segments);
        if (snapshot.isEmpty() || snapshot.equals(previous)) return previous;
        if (cancelled) return previous;
        if (onSegments != null) {
            try {
                onSegments.accept(snapshot);
            } catch (RuntimeException ignored) {
                // An observer must not prevent fallback providers from completing the lookup.
            }
        }
        return snapshot;
    }

    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            if (activeCall != null) activeCall.cancel();
            activeCall = null;
        }
    }

    private Request theIntroRequest(EpisodeIdentity identity, long durationMs) {
        HttpUrl.Builder url = HttpUrl.get(theIntroDbUrl).newBuilder()
                .addQueryParameter("tmdb_id", Long.toString(identity.tmdbId()))
                .addQueryParameter("duration_ms", Long.toString(durationMs))
                .addQueryParameter("details", "true")
                .addQueryParameter("merge_unknown", "false");
        coordinates(url, identity);
        return request(url.build()).build();
    }

    private Request introRequest(EpisodeIdentity identity) {
        HttpUrl.Builder url = HttpUrl.get(introDbUrl).newBuilder().addQueryParameter("imdb_id", identity.imdbId());
        if (identity.isMovie()) url.addQueryParameter("is_movie", "true");
        else coordinates(url, identity);
        return request(url.build()).build();
    }

    private Request theOtherRequest(EpisodeIdentity identity) {
        JsonObject body = new JsonObject();
        body.addProperty("platform_type", identity.isMovie() ? "tmdb_id_movie" : "tmdb_id_tv");
        body.addProperty("platform_value", Long.toString(identity.tmdbId()));
        if (!identity.isMovie()) {
            body.addProperty("season_number", identity.season());
            body.addProperty("episode_number", identity.episode());
        }
        JsonArray includes = new JsonArray();
        for (String field : List.of("base_video_list", "base_video_season", "base_video_episode", "marker")) includes.add(field);
        body.add("includes", includes);
        body.addProperty("page", 1);
        body.addProperty("page_size", 30);
        return request(HttpUrl.get(theOtherDbUrl)).post(RequestBody.create(body.toString(), JSON)).build();
    }

    private static void coordinates(HttpUrl.Builder url, EpisodeIdentity identity) {
        if (!identity.isMovie()) url.addQueryParameter("season", Integer.toString(identity.season()))
                .addQueryParameter("episode", Integer.toString(identity.episode()));
    }

    private static Request.Builder request(HttpUrl url) {
        return new Request.Builder().url(url).header("Accept", "application/json")
                .header("User-Agent", "TV-CommunitySkip/1.0");
    }

    private String read(Request request, long deadlineNs) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
        if (cancelled || remainingMs <= 0) return "";
        String host = request.url().host();
        if (useCache) synchronized (RATE_LIMITS) {
            if (RATE_LIMITS.getOrDefault(host, 0L) > System.currentTimeMillis()) return "";
        }
        Call call;
        synchronized (lock) {
            if (cancelled) return "";
            if (client == null) client = OkHttp.trustedClient().newBuilder()
                    .connectTimeout(1_500, TimeUnit.MILLISECONDS)
                    .readTimeout(2_000, TimeUnit.MILLISECONDS)
                    .writeTimeout(2_000, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(false).build();
            call = client.newCall(request);
            call.timeout().timeout(Math.min(3_000, remainingMs), TimeUnit.MILLISECONDS);
            activeCall = call;
        }
        try (Response response = call.execute()) {
            if (response.code() == 429 && useCache) synchronized (RATE_LIMITS) {
                long delay = 60_000;
                try { delay = Math.max(30_000, Math.min(300_000, Long.parseLong(response.header("Retry-After", "60")) * 1_000)); }
                catch (NumberFormatException ignored) { }
                RATE_LIMITS.put(host, System.currentTimeMillis() + delay);
            }
            if (!response.isSuccessful()) return "";
            successfulResponse = true;
            ResponseBody body = response.body();
            if (body == null || body.contentLength() > MAX_BODY_BYTES || cancelled) return "";
            InputStream stream = body.byteStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4_096];
            int size;
            while ((size = stream.read(buffer)) != -1) {
                if (cancelled || output.size() + size > MAX_BODY_BYTES) return "";
                output.write(buffer, 0, size);
            }
            return cancelled ? "" : new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            return "";
        } finally {
            synchronized (lock) {
                if (activeCall == call) activeCall = null;
            }
        }
    }

    private static boolean complete(List<SkipSegment> segments) {
        EnumSet<SkipSegment.Type> types = EnumSet.noneOf(SkipSegment.Type.class);
        for (SkipSegment segment : segments) types.add(segment.type());
        return types.size() == SkipSegment.Type.values().length;
    }

    private static void mergeMissingTypes(List<SkipSegment> preferred, List<SkipSegment> fallback) {
        EnumSet<SkipSegment.Type> existing = EnumSet.noneOf(SkipSegment.Type.class);
        for (SkipSegment segment : preferred) existing.add(segment.type());
        for (SkipSegment segment : fallback) if (!existing.contains(segment.type())) preferred.add(segment);
    }

    private record CacheEntry(List<SkipSegment> segments, long expiresMs) { }
}
