package com.fongmi.android.tv.api;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.source.EpisodeLabel;
import com.fongmi.android.tv.source.EpisodeLabelParser;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.text.Normalizer;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Response;

public class DanmakuApi {

    private static final String TAG = DanmakuApi.class.getSimpleName();
    private static final Pattern DATE_LABEL = Pattern.compile("(?<![0-9])(?:[0-9]{8}|[0-9]{4}[- ./_]+[0-9]{1,2}[- ./_]+[0-9]{1,2}|[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日)(?![0-9])");

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
        if (reliableEpisodeLabel && canAutoSearchLegacy(episode)) searchLegacy(name, episode, callback);
        else App.post(() -> callback.onSuccess(List.of()));
    }

    static boolean canAutoSearchLegacy(String episode) {
        EpisodeLabel label = EpisodeLabelParser.parse(episode);
        // Invalid or conflicting dates retain ambiguity even though they have no canonical air date.
        if (label.isAmbiguous() && DATE_LABEL.matcher(Normalizer.normalize(label.original(), Normalizer.Form.NFKC)).find()) return false;
        return !label.isVariety() || label.isApiSafe();
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
