package com.fongmi.android.tv.api.rex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.TmdbEndpoint;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Rex 栏目用的 TMDB 查询客户端。
 *
 * <p>URL 规则来自 {@link RexTmdbQuery}（移植 rex-tmdb.js），传输复用应用现有 TMDB 反代
 * （{@link TmdbEndpoint} + {@link TmdbNetwork}），解析复用
 * {@link DiscoverApi#parseTmdbResults(String, String)}（会顺带填 genreText）。
 *
 * <p>取消：本类保留 tag → Call 登记直到正文消费和主线程交付结束，
 * 页面级 {@link #cancel(Object)} 同时取消在途正文和已排队的回调。
 */
public final class RexTmdbClient {

    public interface ItemsListener {

        void onSuccess(List<Vod> items);

        void onError(Exception e);
    }

    /** 带分页信息（栏目列表承接页续拉用）。 */
    public interface PageListener {

        void onSuccess(List<Vod> items, int page, int totalPages);

        void onError(Exception e);
    }

    private static final TaggedCalls PENDING = new TaggedCalls();

    private RexTmdbClient() {
    }

    public static void fetch(RexTmdbQuery.Query query, @Nullable String apiKey, @Nullable Object tag, ItemsListener listener) {
        fetchPage(query, 0, apiKey, tag, new PageListener() {
            @Override
            public void onSuccess(List<Vod> items, int page, int totalPages) {
                listener.onSuccess(items);
            }

            @Override
            public void onError(Exception e) {
                listener.onError(e);
            }
        });
    }

    public static void fetchPage(RexTmdbQuery.Query query, int page, @Nullable String apiKey, @Nullable Object tag, PageListener listener) {
        HttpUrl url = buildUrl(query, page, apiKey);
        if (url == null) {
            post(() -> listener.onError(new IOException("Rex tmdb url unavailable")));
            return;
        }
        Object requestTag = tag == null ? query.path : tag;
        Call call = TmdbNetwork.newCall(new Request.Builder().url(url).tag(requestTag).build());
        PENDING.track(tag, call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                post(tag, call, () -> listener.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Rex tmdb failed: HTTP " + resp.code());
                    String body = resp.body().string();
                    List<Vod> items = DiscoverApi.parseTmdbResults(body, query.fallbackMediaType);
                    int totalPages = totalPages(body);
                    post(tag, call, () -> listener.onSuccess(new ArrayList<>(items), page, totalPages));
                } catch (Exception e) {
                    post(tag, call, () -> listener.onError(e));
                }
            }
        });
    }

    /** 取消某个 tag 的全部在途请求。 */
    public static void cancel(@Nullable Object tag) {
        PENDING.cancel(tag);
    }

    static HttpUrl buildUrl(RexTmdbQuery.Query query, int page, @Nullable String apiKey) {
        if (RexJson.isEmpty(apiKey)) return null;
        HttpUrl url = HttpUrl.parse(TmdbEndpoint.getApiBase() + query.path);
        if (url == null) return null;
        HttpUrl.Builder builder = url.newBuilder()
                .addQueryParameter("api_key", apiKey.trim())
                .addQueryParameter("language", "zh-CN");
        if (page > 0) builder.addQueryParameter("page", String.valueOf(page));
        for (Map.Entry<String, String> entry : query.params.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isEmpty()) continue;
            builder.addQueryParameter(entry.getKey(), value);
        }
        return builder.build();
    }

    /** TMDB 响应的 total_pages，异常值收敛到 [1, 500]。 */
    static int totalPages(String body) {
        JsonObject object = RexJson.object(body);
        long total = RexJson.longValue(object, "total_pages");
        if (total <= 0) return 1;
        return (int) Math.min(total, 500);
    }

    private static void post(Object tag, Call call, Runnable runnable) {
        PENDING.deliver(tag, call, runnable, RexTmdbClient::post);
    }

    private static void post(Runnable runnable) {
        if (App.get() == null) runnable.run();
        else App.post(runnable);
    }
}
