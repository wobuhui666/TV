package com.fongmi.android.tv.api.rex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 豆瓣云桥客户端（@rexnow/douban 0.6.1 的 Java 移植）：
 * GET {base}/v1/catalog/{collectionId}?skip= 与 POST {base}/v1/items {"ids": [...]}。
 *
 * <p>Authorization: Bearer {sk} 仅在 sk 非空时发送；sk 为空时调用方直接走
 * {@link RexNowClient} 豆瓣 feed 回退。401/403 走 {@code onUnauthorized} 供上层切换回退源。
 * 条目有 tmdbId 时按 {@code tmdb:{movie|tv}:{id}} 映射并把 TMDB 图换成应用反代地址；
 * 无 tmdbId 时按 {@code douban:{id}} 复用应用既有豆瓣匹配链路。
 */
public final class DoubanBridgeClient {

    public static final String DEFAULT_BASE = "https://douban-bridge.baran.wang";

    public interface Listener {

        void onSuccess(List<Vod> items);

        void onError(Exception e);

        void onUnauthorized();
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    /** 云桥返回的 TMDB 图是 image.tmdb.org 完整 URL，剥成路径后经应用反代重建。 */
    private static final Pattern TMDB_IMAGE = Pattern.compile("^https://image\\.tmdb\\.org/t/p/[^/]+(/.*)$");
    private static final TaggedCalls PENDING = new TaggedCalls();

    private DoubanBridgeClient() {
    }

    public static void fetchCatalog(String base, @Nullable String sk, @Nullable String userId, String collectionId, int skip, @Nullable Object tag, Listener listener) {
        String url = catalogUrl(base, collectionId, skip);
        if (url.isEmpty()) {
            post(() -> listener.onError(new IOException("Douban bridge url unavailable")));
            return;
        }
        enqueue(url, headers(sk, userId), null, tag, listener);
    }

    public static void fetchItems(String base, @Nullable String sk, @Nullable String userId, List<String> doubanIds, @Nullable Object tag, Listener listener) {
        if (doubanIds == null || doubanIds.isEmpty()) {
            post(() -> listener.onSuccess(new ArrayList<>()));
            return;
        }
        enqueue(normalizeBase(base) + "/v1/items", headers(sk, userId), RequestBody.create(itemsBody(doubanIds).toString(), JSON), tag, listener);
    }

    /** 取消某个 tag 的全部在途请求。 */
    public static void cancel(@Nullable Object tag) {
        PENDING.cancel(tag);
    }

    static String catalogUrl(String base, String collectionId, int skip) {
        HttpUrl root = HttpUrl.parse(normalizeBase(base) + "/v1/catalog/");
        if (root == null) return "";
        return root.newBuilder()
                .addPathSegment(collectionId)
                .addQueryParameter("skip", String.valueOf(Math.max(0, skip)))
                .build()
                .toString();
    }

    static JsonObject itemsBody(List<String> doubanIds) {
        JsonArray ids = new JsonArray();
        for (String id : doubanIds) if (!RexJson.isEmpty(id)) ids.add(id.trim());
        JsonObject body = new JsonObject();
        body.add("ids", ids);
        return body;
    }

    static Map<String, String> headers(@Nullable String sk, @Nullable String userId) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!RexJson.isEmpty(sk)) headers.put("Authorization", "Bearer " + sk.trim());
        if (!RexJson.isEmpty(userId)) headers.put("X-User-Id", userId.trim());
        return headers;
    }

    /** 补协议头、去尾部斜杠；空值落回官方云桥。 */
    static String normalizeBase(@Nullable String base) {
        String value = base == null ? "" : base.trim();
        if (value.isEmpty()) value = DEFAULT_BASE;
        if (!value.contains("://")) value = "https://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    static List<Vod> parseCatalog(@Nullable String body) {
        List<Vod> items = new ArrayList<>();
        JsonArray array = RexJson.array(RexJson.object(body), "items");
        if (array == null) return items;
        for (JsonElement element : array) {
            JsonObject entry = RexJson.objectOf(element);
            if (entry == null) continue;
            Vod item = vodFromEntry(entry);
            if (item != null) items.add(item);
        }
        return items;
    }

    @Nullable
    static Vod vodFromEntry(@Nullable JsonObject entry) {
        if (entry == null) return null;
        String title = RexJson.string(entry, "title");
        if (title.isEmpty()) return null;
        long tmdbId = RexJson.longValue(entry, "tmdbId");
        String doubanId = RexJson.string(entry, "doubanId");
        String mediaType = RexJson.string(entry, "mediaType");
        if (!DiscoverMediaKey.MOVIE.equals(mediaType) && !DiscoverMediaKey.TV.equals(mediaType)) mediaType = "";
        String poster = RexJson.string(RexJson.child(entry, "images"), "poster");
        String background = RexJson.string(RexJson.child(entry, "images"), "background");
        Vod item = new Vod();
        if (tmdbId > 0 && !mediaType.isEmpty()) {
            item.setId(DiscoverMediaKey.of(mediaType, tmdbId).toString());
            String posterPath = tmdbPath(poster);
            item.setPic(posterPath.isEmpty() ? poster : DiscoverApi.tmdbPic(posterPath));
            String backdropPath = tmdbPath(background);
            item.setBackdrop(backdropPath.isEmpty() ? background : DiscoverApi.tmdbBackdrop(backdropPath));
            item.setTypeName(mediaType);
        } else if (!doubanId.isEmpty()) {
            item.setId("douban:" + doubanId);
            item.setPic(poster);
            item.setBackdrop(background);
        } else {
            return null;
        }
        item.setName(title);
        long year = RexJson.longValue(entry, "year");
        if (year > 0) item.setYear(String.valueOf(year));
        item.setRemarks(DiscoverApi.tmdbRemarks(RexJson.doubleValue(entry, "rating")));
        item.setContent(RexJson.string(entry, "description"));
        item.setGenreText(RexGenreDict.joinStrings(RexJson.array(entry, "genres"), RexGenreDict.DEFAULT_MAX));
        return item;
    }

    /** image.tmdb.org 完整 URL → 路径（交给应用 TMDB 反代重建）；非 TMDB 图返回空串。 */
    static String tmdbPath(@Nullable String url) {
        if (url == null || url.isEmpty()) return "";
        Matcher matcher = TMDB_IMAGE.matcher(url.trim());
        return matcher.matches() ? matcher.group(1) : "";
    }

    private static void enqueue(String url, Map<String, String> headers, @Nullable RequestBody body, @Nullable Object tag, Listener listener) {
        Request.Builder builder = new Request.Builder().url(url).tag(tag == null ? url : tag);
        if (!headers.isEmpty()) builder.headers(Headers.of(headers));
        if (body != null) builder.post(body);
        Call call = OkHttp.client().newCall(builder.build());
        PENDING.track(tag, call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                post(tag, call, () -> listener.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    int code = resp.code();
                    if (code == 401 || code == 403) {
                        post(tag, call, listener::onUnauthorized);
                        return;
                    }
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Douban bridge failed: HTTP " + code);
                    List<Vod> items = parseCatalog(resp.body().string());
                    post(tag, call, () -> listener.onSuccess(items));
                } catch (Exception e) {
                    post(tag, call, () -> listener.onError(e));
                }
            }
        });
    }

    private static void post(Object tag, Call call, Runnable runnable) {
        PENDING.deliver(tag, call, runnable, DoubanBridgeClient::post);
    }

    private static void post(Runnable runnable) {
        if (App.get() == null) runnable.run();
        else App.post(runnable);
    }
}
