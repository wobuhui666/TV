package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.util.List;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Response;

public class DanmakuApi {

    private static final String TAG = DanmakuApi.class.getSimpleName();

    public interface SearchCallback {

        void onSuccess(List<Danmaku> items);

        void onError(Exception e);
    }

    public static boolean canSearch() {
        return DanmakuSetting.isLoad() && DanmakuSetting.isAuto() && DanmakuSetting.hasSearchApi();
    }

    public static Call newCall(String name, String episode) {
        OkHttp.cancel(TAG);
        name = Trans.t2s(name);
        episode = Trans.t2s(episode);
        String url = DanmakuSetting.getEffectiveApiUrl();
        if (url.contains("{name}") || url.contains("{episode}")) {
            return OkHttp.newCall(url.replace("{name}", name).replace("{episode}", episode), TAG);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("name", name);
            params.put("episode", episode);
            return OkHttp.newCall(url, OkHttp.toBody(params), TAG);
        }
    }

    public static void search(String name, String episode, Result result, Consumer<Danmaku> found) {
        search(name, episode, result, true, found);
    }

    public static void search(String name, String episode, Result result, boolean reliableEpisodeLabel, Consumer<Danmaku> found) {
        searchAuto(name, episode, result, reliableEpisodeLabel, new SearchCallback() {
            @Override
            public void onSuccess(List<Danmaku> items) {
                items.stream().findFirst().ifPresent(found);
            }

            @Override
            public void onError(Exception e) {
            }
        });
    }

    public static void searchAuto(String name, String episode, Result result, SearchCallback callback) {
        searchAuto(name, episode, result, true, callback);
    }

    public static void searchAuto(String name, String episode, Result result, boolean reliableEpisodeLabel, SearchCallback callback) {
        if (!TextUtils.isEmpty(DanmakuSetting.getEffectiveLogvarUrl())) {
            LogvarApi.searchAuto(name, episode, result, reliableEpisodeLabel, new LogvarApi.Listener() {
                @Override
                public void onSuccess(List<Danmaku> items) {
                    if (items.isEmpty()) searchLegacyAuto(name, episode, reliableEpisodeLabel, callback);
                    else callback.onSuccess(items);
                }

                @Override
                public void onError(Exception e) {
                    searchLegacyAuto(name, episode, reliableEpisodeLabel, callback);
                }
            });
        } else {
            searchLegacyAuto(name, episode, reliableEpisodeLabel, callback);
        }
    }

    private static void searchLegacyAuto(String name, String episode, boolean reliableEpisodeLabel, SearchCallback callback) {
        if (reliableEpisodeLabel) searchLegacy(name, episode, callback);
        else App.post(() -> callback.onSuccess(List.of()));
    }

    public static void searchManual(String name, String episode, SearchCallback callback) {
        if (!TextUtils.isEmpty(DanmakuSetting.getEffectiveLogvarUrl())) {
            LogvarApi.searchEpisodes(name, episode, new LogvarApi.Listener() {
                @Override
                public void onSuccess(List<Danmaku> items) {
                    if (items.isEmpty()) searchLegacy(name, episode, callback);
                    else callback.onSuccess(items);
                }

                @Override
                public void onError(Exception e) {
                    searchLegacy(name, episode, callback);
                }
            });
        } else {
            searchLegacy(name, episode, callback);
        }
    }

    private static void searchLegacy(String name, String episode, SearchCallback callback) {
        if (TextUtils.isEmpty(DanmakuSetting.getEffectiveApiUrl())) {
            App.post(() -> callback.onSuccess(List.of()));
            return;
        }
        newCall(name, episode).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    List<Danmaku> items = Danmaku.arrayFrom(response.body().string());
                    App.post(() -> callback.onSuccess(items));
                } catch (Exception e) {
                    App.post(() -> callback.onError(e));
                }
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull java.io.IOException e) {
                App.post(() -> callback.onError(e));
            }
        });
    }

    public static void cancel() {
        OkHttp.cancel(TAG);
        LogvarApi.cancel();
    }
}
