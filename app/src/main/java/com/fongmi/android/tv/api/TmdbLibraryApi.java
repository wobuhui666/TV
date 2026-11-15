package com.fongmi.android.tv.api;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.DiscoverDetail;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.LibraryMedia;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.TmdbEndpoint;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Comparator;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public final class TmdbLibraryApi {
    public interface Listener<T> {
        void onSuccess(T value);
        void onError(Exception error);
    }
    public record Page(List<Vod> items, int page, int totalPages) { }
    private record Cached(long time, String body) { }
    private static final Map<Object, Set<Pending>> REQUESTS = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor PARSERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    task.run();
                }, "tmdb-library-parser");
                thread.setDaemon(true);
                return thread;
            });
    static { PARSERS.allowCoreThreadTimeOut(true); }
    private static final Map<String, Cached> CACHE = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) { return size() > 64; }
    });

    private TmdbLibraryApi() { }

    public static void search(String query, String type, int page, String apiKey, Object tag, Listener<Page> listener) {
        String path = "search/" + ("movie".equals(type) || "tv".equals(type) ? type : "multi");
        get(path, Map.of("query", query, "page", String.valueOf(Math.max(1, page)), "include_adult", "false"), apiKey, tag,
                body -> parsePage(body, type), listener);
    }

    public static void detail(DiscoverMediaKey key, String apiKey, Object tag, Listener<LibraryMedia.Metadata> listener) {
        get(key.getMediaType() + "/" + key.getId(), Map.of("append_to_response", "credits,images,alternative_titles", "include_image_language", "zh,en,ja,ko,null"), apiKey, tag,
                body -> parseMetadata(key, body), listener);
    }

    /** Unfiltered artwork fallback for titles whose logo only exists in their original language. */
    public static void logos(DiscoverMediaKey key, String originalLanguage, String apiKey, Object tag, Listener<List<String>> listener) {
        get(key.getMediaType() + "/" + key.getId() + "/images", Map.of("language", ""), apiKey, tag,
                body -> parseLogos(body, originalLanguage), listener);
    }

    public static void logos(DiscoverMediaKey key, String apiKey, Object tag, Listener<List<String>> listener) {
        logos(key, "", apiKey, tag, listener);
    }

    public static void season(DiscoverMediaKey key, int number, String apiKey, Object tag, Listener<List<LibraryMedia.Episode>> listener) {
        get("tv/" + key.getId() + "/season/" + Math.max(0, number), Map.of(), apiKey, tag,
                body -> parseEpisodes(number, body), listener);
    }

    public static void extras(DiscoverMediaKey key, String apiKey, Object tag, Listener<LibraryMedia.Extras> listener) {
        get(key.getMediaType() + "/" + key.getId(), Map.of("append_to_response", "videos,images,recommendations", "include_image_language", "zh,en,null"), apiKey, tag,
                body -> parseExtras(key, body), listener);
    }

    public static void credits(long person, String apiKey, Object tag, Listener<Page> listener) {
        get("person/" + person + "/combined_credits", Map.of(), apiKey, tag,
                body -> {
                    JsonObject object = object(body);
                    JsonObject wrapper = new JsonObject();
                    wrapper.add("results", array(object, "cast"));
                    return new Page(DiscoverApi.parseTmdbResults(wrapper.toString()), 1, 1);
                }, listener);
    }

    private interface Parser<T> { T parse(String body) throws Exception; }

    /** Remains registered through parsing and the queued main-thread delivery. */
    private static final class Pending {
        private final Object tag;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Call call;
        private volatile FutureTask<?> parsing;

        private Pending(Object tag) { this.tag = tag; }

        private boolean active() {
            Set<Pending> requests = REQUESTS.get(tag);
            return !cancelled.get() && requests != null && requests.contains(this);
        }

        private void cancel() {
            cancelled.set(true);
            Call network = call;
            if (network != null) network.cancel();
            FutureTask<?> task = parsing;
            if (task != null) {
                task.cancel(true);
                PARSERS.remove(task);
            }
        }
    }

    private static <T> void get(String path, Map<String, String> params, String apiKey, Object tag, Parser<T> parser, Listener<T> listener) {
        Pending pending = new Pending(tag);
        REQUESTS.compute(tag, (unused, active) -> {
            if (active == null) active = ConcurrentHashMap.newKeySet();
            active.add(pending);
            return active;
        });
        HttpUrl root = HttpUrl.parse(TmdbEndpoint.getApiBase() + path);
        if (root == null || apiKey == null || apiKey.isBlank()) {
            post(pending, () -> listener.onError(new IOException("TMDB configuration unavailable")));
            return;
        }
        HttpUrl.Builder builder = root.newBuilder().addQueryParameter("api_key", apiKey.trim());
        if (!params.containsKey("language")) builder.addQueryParameter("language", "zh-CN");
        params.forEach((key, value) -> { if (!"language".equals(key) || !value.isEmpty()) builder.addQueryParameter(key, value); });
        HttpUrl url = builder.build();
        String cacheKey = url.toString();
        Cached cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.time < 30 * 60_000L) {
            parse(pending, cached.body, null, null, parser, listener);
            return;
        }
        Call call = pending.call = TmdbNetwork.newCall(new Request.Builder().url(url).tag(tag).build());
        if (!pending.active()) { call.cancel(); return; }
        call.enqueue(new Callback() {
            @Override public void onFailure(Call current, IOException error) {
                if (cached != null) parse(pending, cached.body, null, null, parser, listener);
                else post(pending, () -> listener.onError(error));
            }
            @Override public void onResponse(Call current, Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("TMDB response " + response.code());
                    String body = response.body().string();
                    parse(pending, body, cached, cacheKey, parser, listener);
                } catch (Exception error) {
                    if (cached != null) parse(pending, cached.body, null, null, parser, listener);
                    else post(pending, () -> listener.onError(error));
                }
            }
        });
    }

    private static <T> void parse(Pending pending, String body, Cached fallback, String cacheKey, Parser<T> parser, Listener<T> listener) {
        if (!pending.active()) return;
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (!pending.active()) return;
            T value;
            try {
                value = parser.parse(body);
                if (!pending.active()) return;
                if (cacheKey != null) CACHE.put(cacheKey, new Cached(System.currentTimeMillis(), body));
            } catch (Exception error) {
                if (!pending.active()) return;
                if (fallback == null) { post(pending, () -> listener.onError(error)); return; }
                try { value = parser.parse(fallback.body); }
                catch (Exception cachedError) { post(pending, () -> listener.onError(cachedError)); return; }
            }
            T parsed = value;
            post(pending, () -> listener.onSuccess(parsed));
        }, null);
        pending.parsing = task;
        if (!pending.active()) { task.cancel(true); return; }
        try {
            // Never parse on the caller when the queue is full; the UI receives a retryable error.
            PARSERS.execute(task);
            if (!pending.active()) { task.cancel(true); PARSERS.remove(task); }
        } catch (RejectedExecutionException error) {
            post(pending, () -> listener.onError(new IOException("TMDB parser busy", error)));
        }
    }

    private static void post(Pending pending, Runnable deliver) {
        App.post(() -> {
            AtomicBoolean registered = new AtomicBoolean();
            REQUESTS.computeIfPresent(pending.tag, (unused, active) -> {
                registered.set(active.remove(pending));
                return active.isEmpty() ? null : active;
            });
            if (registered.get() && !pending.cancelled.get()) deliver.run();
        });
    }

    public static void cancel(Object tag) {
        Set<Pending> requests = REQUESTS.remove(tag);
        if (requests != null) for (Pending pending : requests) pending.cancel();
    }

    public static void clearCache() { CACHE.clear(); }

    public static Page parsePage(String body, String type) {
        JsonObject object = object(body);
        return new Page(DiscoverApi.parseTmdbResults(body, type), Math.max(1, number(object, "page")), Math.max(1, number(object, "total_pages")));
    }

    public static LibraryMedia.Metadata parseMetadata(DiscoverMediaKey key, String body) {
        JsonObject object = object(body);
        DiscoverDetail detail = DiscoverApi.parseDetail(key, body);
        if (detail == null) throw new IllegalArgumentException("Missing TMDB work");
        if (longNumber(object, "id") != key.getId()) throw new IllegalArgumentException("Unexpected TMDB work");
        List<LibraryMedia.Season> seasons = new ArrayList<>();
        for (JsonElement value : array(object, "seasons")) {
            JsonObject season = value.getAsJsonObject();
            seasons.add(new LibraryMedia.Season(number(season, "season_number"), string(season, "name"), Math.max(0, number(season, "episode_count")), image("w342", string(season, "poster_path"))));
        }
        Set<String> aliases = new LinkedHashSet<>();
        if (!detail.getOriginalTitle().isEmpty()) aliases.add(detail.getOriginalTitle());
        JsonObject alternatives = child(object, "alternative_titles");
        JsonArray names = array(alternatives, key.isMovie() ? "titles" : "results");
        for (JsonElement value : names) {
            String title = string(value.getAsJsonObject(), "title");
            if (!title.isEmpty()) aliases.add(title);
            if (aliases.size() == 20) break;
        }
        String originalLanguage = string(object, "original_language");
        List<String> logos = parseLogos(child(object, "images").toString(), originalLanguage);
        return new LibraryMedia.Metadata(detail, logos.isEmpty() ? "" : logos.get(0), List.copyOf(seasons), List.copyOf(aliases),
                logos, originalLanguage, key.isMovie() ? null : airing(child(object, "last_episode_to_air")),
                key.isMovie() ? null : airing(child(object, "next_episode_to_air")),
                key.isMovie() ? 0 : Math.max(0, number(object, "number_of_episodes")),
                !key.isMovie() && bool(object, "in_production"));
    }

    public static List<String> parseLogos(String body, String originalLanguage) {
        List<JsonObject> choices = new ArrayList<>();
        for (JsonElement value : array(object(body), "logos")) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            if (string(item, "file_path").startsWith("/")) choices.add(item);
        }
        choices.sort(Comparator.comparingInt((JsonObject item) -> logoLanguage(string(item, "iso_639_1"), originalLanguage)).reversed()
                .thenComparing(Comparator.comparingDouble((JsonObject item) -> decimal(item, "vote_average")).reversed())
                .thenComparing(Comparator.comparingInt((JsonObject item) -> number(item, "width")).reversed()));
        Set<String> urls = new LinkedHashSet<>();
        for (JsonObject choice : choices) {
            urls.add(image("w500", string(choice, "file_path")));
            if (urls.size() >= 8) break;
        }
        return List.copyOf(urls);
    }

    private static int logoLanguage(String language, String originalLanguage) {
        if ("zh".equals(language)) return 5;
        if (!language.isEmpty() && language.equals(originalLanguage)) return 4;
        if ("en".equals(language)) return 3;
        return language.isEmpty() ? 2 : 1;
    }

    private static LibraryMedia.AiringEpisode airing(JsonObject episode) {
        int season = number(episode, "season_number"), number = number(episode, "episode_number");
        if (season < 0 || season > 999 || number <= 0 || number > 9999 || !episode.has("season_number")) return null;
        String date = string(episode, "air_date");
        if (!date.isEmpty()) try { LocalDate.parse(date); } catch (RuntimeException error) { return null; }
        return new LibraryMedia.AiringEpisode(season, number, string(episode, "name"), date, Math.max(0, number(episode, "runtime")));
    }

    public static List<LibraryMedia.Episode> parseEpisodes(int season, String body) {
        JsonObject object = object(body);
        if (object.has("season_number") && number(object, "season_number") != season) throw new IllegalArgumentException("Unexpected TMDB season");
        List<LibraryMedia.Episode> result = new ArrayList<>();
        for (JsonElement value : array(object, "episodes")) {
            JsonObject episode = value.getAsJsonObject();
            int number = number(episode, "episode_number");
            if (number <= 0) continue;
            if (episode.has("season_number") && number(episode, "season_number") != season) continue;
            result.add(new LibraryMedia.Episode(season, number, string(episode, "name"), string(episode, "overview"),
                    image("w500", string(episode, "still_path")), string(episode, "air_date"), number(episode, "runtime")));
        }
        return List.copyOf(result);
    }

    public static LibraryMedia.Extras parseExtras(DiscoverMediaKey key, String body) {
        JsonObject object = object(body);
        List<LibraryMedia.Video> videos = new ArrayList<>();
        for (JsonElement value : array(child(object, "videos"), "results")) {
            JsonObject video = value.getAsJsonObject();
            String id = string(video, "key");
            if (!"YouTube".equals(string(video, "site")) || !id.matches("[A-Za-z0-9_-]{6,64}")) continue;
            videos.add(new LibraryMedia.Video(string(video, "name"), "https://www.youtube.com/watch?v=" + id, "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"));
            if (videos.size() == 8) break;
        }
        List<String> images = new ArrayList<>();
        for (JsonElement value : array(child(object, "images"), "backdrops")) {
            String url = image("w780", string(value.getAsJsonObject(), "file_path"));
            if (!url.isEmpty()) images.add(url);
            if (images.size() == 12) break;
        }
        List<Vod> recommendations = DiscoverApi.parseTmdbResults(child(object, "recommendations").toString(), key.getMediaType());
        return new LibraryMedia.Extras(List.copyOf(videos), List.copyOf(images), recommendations);
    }

    private static String image(String size, String path) { return path.isEmpty() ? "" : TmdbEndpoint.getImageBase() + size + path; }
    private static JsonObject object(String body) { return JsonParser.parseString(body).getAsJsonObject(); }
    private static JsonObject child(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject(); }
    private static JsonArray array(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); }
    private static String string(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
    private static int number(JsonObject object, String key) { try { return object.has(key) ? object.get(key).getAsInt() : 0; } catch (RuntimeException error) { return 0; } }
    private static long longNumber(JsonObject object, String key) { try { return object.has(key) ? object.get(key).getAsLong() : 0; } catch (RuntimeException error) { return 0; } }
    private static double decimal(JsonObject object, String key) { try { return object.has(key) ? object.get(key).getAsDouble() : 0; } catch (RuntimeException error) { return 0; } }
    private static boolean bool(JsonObject object, String key) { try { return object.has(key) && object.get(key).getAsBoolean(); } catch (RuntimeException error) { return false; } }
}
