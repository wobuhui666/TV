package com.fongmi.android.tv.api;

import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.bean.AssrtSubtitle;
import com.fongmi.android.tv.bean.AssrtSubtitleFile;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** Read-only ASSRT search/detail client. Credentials are sent only in Authorization headers. */
public final class AssrtApi {

    public static final int PAGE_SIZE = 15;
    private static final String BASE = "https://api.assrt.net/v1";
    private static final long SEARCH_CACHE_MS = 5 * 60_000;
    private static final int CACHE_SIZE = 32;
    private static final int REQUESTS_PER_MINUTE = 20;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private AssrtApi() {
    }

    public interface SearchListener {
        void onSuccess(List<AssrtSubtitle> subtitles);
        void onError(Exception error);
    }

    public interface DetailListener {
        void onSuccess(AssrtSubtitle subtitle);
        void onError(Exception error);
    }

    public static boolean isConfigured() {
        return !token(BuildConfig.ASSRT_API_TOKEN).isEmpty();
    }

    public static void search(String query, int position, Object tag, SearchListener listener) {
        Holder.CLIENT.search(query, position, tag, listener);
    }

    public static void detail(long id, Object tag, DetailListener listener) {
        Holder.CLIENT.detail(id, tag, listener);
    }

    public static void cancel(Object tag) {
        Holder.CLIENT.cancel(tag);
    }

    private static final class Holder {
        private static final Client CLIENT = new Client(BASE, BuildConfig.ASSRT_API_TOKEN,
                request -> OkHttp.client().newCall(request), App::post, SystemClock::elapsedRealtime);
    }

    public static final class ApiException extends IOException {
        private final int code;

        ApiException(int code) {
            super(errorMessage(code));
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public boolean isRateLimited() {
            return code == 30900;
        }
    }

    /** Injectable transport/clock boundary lets tests exercise the production request lifecycle. */
    static final class Client {
        private final String base;
        private final String credential;
        private final Function<Request, Call> calls;
        private final Consumer<Runnable> dispatcher;
        private final LongSupplier clock;
        private final Map<Object, Set<Call>> pending = new HashMap<>();
        private final Map<Object, Long> generations = new WeakHashMap<>();
        private final LinkedHashMap<String, CachedSearch> cache = new LinkedHashMap<>(16, 0.75f, true);
        private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();

        Client(String base, String credential, Function<Request, Call> calls,
               Consumer<Runnable> dispatcher, LongSupplier clock) {
            this.base = base == null ? "" : base.replaceAll("/+$", "");
            this.credential = token(credential);
            this.calls = calls;
            this.dispatcher = dispatcher;
            this.clock = clock;
        }

        void search(String query, int position, Object tag, SearchListener listener) {
            String keyword = query == null ? "" : query.trim();
            long generation = generation(tag);
            if (credential.isEmpty()) {
                deliver(tag, generation, () -> listener.onError(new ApiException(20001)));
                return;
            }
            // Let the service apply its keyword-length rule; short Chinese names may meet its byte limit.
            if (keyword.isEmpty()) {
                deliver(tag, generation, () -> listener.onError(new ApiException(101)));
                return;
            }
            int offset = Math.max(0, position);
            String key = keyword + '\n' + offset;
            CachedSearch cached;
            synchronized (this) {
                cached = cache.get(key);
                if (cached != null && clock.getAsLong() - cached.createdAt >= SEARCH_CACHE_MS) cached = null;
            }
            if (cached != null) {
                List<AssrtSubtitle> items = cached.items;
                deliver(tag, generation, () -> listener.onSuccess(items));
                return;
            }
            HttpUrl root = HttpUrl.parse(base + "/sub/search");
            if (root == null) {
                deliver(tag, generation, () -> listener.onError(new IOException("ASSRT endpoint unavailable")));
                return;
            }
            HttpUrl url = root.newBuilder().addQueryParameter("q", keyword)
                    .addQueryParameter("pos", Integer.toString(offset))
                    .addQueryParameter("cnt", Integer.toString(PAGE_SIZE))
                    .addQueryParameter("filelist", "1").build();
            request(url, tag, generation, body -> {
                List<AssrtSubtitle> items = parseSearch(body);
                synchronized (this) {
                    cache.put(key, new CachedSearch(clock.getAsLong(), items));
                    while (cache.size() > CACHE_SIZE) cache.remove(cache.keySet().iterator().next());
                }
                return () -> listener.onSuccess(items);
            }, listener::onError);
        }

        void detail(long id, Object tag, DetailListener listener) {
            long generation = generation(tag);
            if (credential.isEmpty()) {
                deliver(tag, generation, () -> listener.onError(new ApiException(20001)));
                return;
            }
            if (id <= 0) {
                deliver(tag, generation, () -> listener.onError(new ApiException(20900)));
                return;
            }
            HttpUrl root = HttpUrl.parse(base + "/sub/detail");
            if (root == null) {
                deliver(tag, generation, () -> listener.onError(new IOException("ASSRT endpoint unavailable")));
                return;
            }
            // Detail URLs expire. Never reuse or cache them across selections.
            request(root.newBuilder().addQueryParameter("id", Long.toString(id)).build(), tag, generation,
                    body -> {
                        AssrtSubtitle subtitle = parseDetail(body, id);
                        return () -> listener.onSuccess(subtitle);
                    }, listener::onError);
        }

        void cancel(Object tag) {
            if (tag == null) return;
            Set<Call> cancelled;
            synchronized (this) {
                generations.put(tag, generation(tag) + 1);
                cancelled = pending.remove(tag);
            }
            if (cancelled != null) for (Call call : cancelled) call.cancel();
        }

        private void request(HttpUrl url, Object tag, long generation, Parser parser, Consumer<Exception> error) {
            if (!reserveRequest()) {
                deliver(tag, generation, () -> error.accept(new ApiException(30900)));
                return;
            }
            Request request = new Request.Builder().url(url).tag(tag)
                    .header("Authorization", "Bearer " + credential)
                    .header("Accept", "application/json").build();
            Call call = calls.apply(request);
            synchronized (this) {
                if (generation != generation(tag)) {
                    call.cancel();
                    return;
                }
                if (tag != null) pending.computeIfAbsent(tag, key -> new HashSet<>()).add(call);
            }
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException failure) {
                    finish(tag, call);
                    if (!call.isCanceled()) deliver(tag, generation, () -> error.accept(failure));
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response value = response) {
                        if (value.body() == null) throw new IOException("ASSRT response unavailable");
                        String body = readBody(value.body().byteStream());
                        if (!value.isSuccessful()) {
                            try {
                                checkStatus(body);
                            } catch (ApiException failure) {
                                throw failure;
                            } catch (IOException ignored) {
                                // An HTTP error may be HTML rather than the ASSRT JSON schema.
                            }
                            if (value.code() == 429) throw new ApiException(30900);
                            throw new IOException("ASSRT request failed: HTTP " + value.code());
                        }
                        checkStatus(body);
                        Runnable callback = parser.parse(body);
                        if (!call.isCanceled()) deliver(tag, generation, callback);
                    } catch (Exception failure) {
                        if (!call.isCanceled()) deliver(tag, generation, () -> error.accept(failure));
                    } finally {
                        finish(tag, call);
                    }
                }
            });
        }

        private synchronized boolean reserveRequest() {
            long now = clock.getAsLong();
            while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= 60_000) requestTimes.removeFirst();
            if (requestTimes.size() >= REQUESTS_PER_MINUTE) return false;
            requestTimes.addLast(now);
            return true;
        }

        private synchronized long generation(Object tag) {
            return tag == null ? 0 : generations.getOrDefault(tag, 0L);
        }

        private void deliver(Object tag, long generation, Runnable callback) {
            dispatcher.accept(() -> {
                synchronized (this) {
                    if (generation != generation(tag)) return;
                }
                callback.run();
            });
        }

        private synchronized void finish(Object tag, Call call) {
            if (tag == null) return;
            Set<Call> items = pending.get(tag);
            if (items != null && items.remove(call) && items.isEmpty()) pending.remove(tag);
        }
    }

    private interface Parser {
        Runnable parse(String body) throws IOException;
    }

    private record CachedSearch(long createdAt, List<AssrtSubtitle> items) {
    }

    static List<AssrtSubtitle> parseSearch(String body) throws IOException {
        JsonArray items = resultItems(body);
        List<AssrtSubtitle> subtitles = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject item = object(element);
            long id = number(item, "id");
            if (id > 0) subtitles.add(subtitle(item, id, false));
        }
        return List.copyOf(subtitles);
    }

    static AssrtSubtitle parseDetail(String body, long expectedId) throws IOException {
        JsonArray items = resultItems(body);
        for (JsonElement element : items) {
            JsonObject item = object(element);
            if (number(item, "id") == expectedId) return subtitle(item, expectedId, true);
        }
        throw new ApiException(20900);
    }

    private static AssrtSubtitle subtitle(JsonObject item, long id, boolean detail) {
        String title = string(item, "native_name");
        if (title.isEmpty()) title = string(item, "title");
        if (title.isEmpty()) title = string(item, "videoname");
        if (title.isEmpty()) title = string(item, "filename");
        if (title.isEmpty()) title = "ASSRT #" + id;
        List<AssrtSubtitleFile> files = new ArrayList<>();
        JsonArray filelist = array(item, "filelist");
        if (filelist != null) for (JsonElement element : filelist) {
            JsonObject file = object(element);
            String name = string(file, "f");
            if (name.isEmpty()) name = string(file, "filename");
            String url = detail ? httpUrl(string(file, "url")) : "";
            String size = string(file, "s");
            long bytes = numericSize(file.get("s"));
            if (!name.isEmpty()) files.add(new AssrtSubtitleFile(name, url, size, bytes));
        }
        // Some entries are a directly downloadable single file, without an archive file list.
        if (detail && files.isEmpty()) {
            String name = string(item, "filename");
            String url = httpUrl(string(item, "url"));
            if (!name.isEmpty() && !url.isEmpty()) {
                long bytes = number(item, "size");
                files.add(new AssrtSubtitleFile(name, url, bytes > 0 ? Long.toString(bytes) + " B" : "", bytes));
            }
        }
        if (detail) addArchiveOption(item, files);
        return new AssrtSubtitle(id, title, string(item, "videoname"), string(item, "subtype"),
                string(object(item.get("lang")), "desc"), string(item, "release_site"),
                string(item, "upload_time"), files);
    }

    /** Keep the original package available when the service cannot extract an individual file. */
    private static void addArchiveOption(JsonObject item, List<AssrtSubtitleFile> files) {
        HttpUrl archive = HttpUrl.parse(string(item, "url"));
        if (archive == null) return;
        List<String> segments = archive.pathSegments();
        String name = segments.get(segments.size() - 1);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".zip") && !lower.endsWith(".rar")) return;
        String url = archive.toString();
        for (AssrtSubtitleFile file : files) if (url.equals(file.getUrl())) return;
        long bytes = number(item, "size");
        files.add(new AssrtSubtitleFile(name, url, bytes > 0 ? Long.toString(bytes) + " B" : "", bytes));
    }

    private static JsonArray resultItems(String body) throws IOException {
        JsonObject response = checkStatus(body);
        JsonArray items = array(object(response.get("sub")), "subs");
        if (items == null) throw new IOException("ASSRT subtitle list unavailable");
        return items;
    }

    private static JsonObject checkStatus(String body) throws IOException {
        JsonObject response;
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) throw new IOException("ASSRT response is invalid");
            response = element.getAsJsonObject();
            if (!response.has("status") || !response.get("status").isJsonPrimitive()) throw new IOException("ASSRT status unavailable");
            int status = Integer.parseInt(response.get("status").getAsString());
            if (status != 0) throw new ApiException(status);
        } catch (RuntimeException error) {
            throw new IOException("ASSRT response is invalid");
        }
        return response;
    }

    private static String token(String value) {
        String token = value == null ? "" : value.trim();
        if (token.equalsIgnoreCase("Bearer")) return "";
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) token = token.substring(7).trim();
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character < 0x21 || character > 0x7e) return "";
        }
        return token;
    }

    private static String httpUrl(String value) {
        HttpUrl url = HttpUrl.parse(value);
        return url == null ? "" : url.toString();
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static String string(JsonObject object, String name) {
        try {
            JsonElement value = object.get(name);
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static long number(JsonObject object, String name) {
        try {
            return Long.parseLong(string(object, name));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static long numericSize(JsonElement value) {
        try {
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? Math.max(0, value.getAsLong()) : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String errorMessage(int code) {
        return switch (code) {
            case 101 -> "ASSRT search query is too short";
            case 20001, 1 -> "ASSRT credential unavailable";
            case 20900 -> "ASSRT subtitle not found";
            case 30900 -> "ASSRT request quota exceeded";
            default -> "ASSRT service unavailable (" + code + ")";
        };
    }

    private static String readBody(InputStream source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = source.read(buffer)) != -1) {
            if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("ASSRT response is too large");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
