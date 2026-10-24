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
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * rexnow 公开 feed 客户端（无需密钥）：
 * regional-tv（分区剧集/综艺）、douban（豆瓣热门/最新）、anime（每日新番）。
 *
 * <p>目录与文件名按东八区日期回退（{@link RexFeedDate}）；条目优先保留 TMDB id
 * （id 契约 {@code tmdb:{movie|tv}:{id}}），与 DiscoverApi 产物一致，详情/播放链路不变。
 */
public final class RexNowClient {

    public static final String ROOT = "https://assets.rexnow.tv/scripts/datas/rex";
    /** regional-tv 清单 schema 版本（与 rex REX_REGIONAL_TV_SCHEMA 一致）。 */
    public static final int REGIONAL_SCHEMA = 1;
    /** 综艺分区不足该数量时用 western 补足（rex REX_REGIONAL_VARIETY_MINIMUM）。 */
    public static final int REGIONAL_VARIETY_MINIMUM = 20;
    /** 单个文件最多取多少条（feed 文件自身 limit=40）。 */
    public static final int REGIONAL_MERGE_CAP = 40;

    public static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    public interface ItemsListener {

        void onSuccess(List<Vod> items);

        void onError(Exception e);
    }

    public interface WeeklyListener {

        void onSuccess(Map<String, List<Vod>> weekly);

        void onError(Exception e);
    }

    private interface BodyListener {

        void onBody(String body);

        void onError(Exception e);
    }

    private static final Map<Object, Set<Call>> PENDING = new ConcurrentHashMap<>();

    private RexNowClient() {
    }

    // ---------- 网络入口 ----------

    /** regional-tv：manifest 日期 → 逐日回退 → 空/不足时 western 补足（去重）。 */
    public static void fetchRegional(String region, String category, @Nullable Object tag, ItemsListener listener) {
        get(ROOT + "/regional-tv/latest.json", tag, new BodyListener() {
            @Override
            public void onBody(String body) {
                List<String> dates = dates(manifestDate(body));
                fetchRegionFeed(region, category, dates, 0, tag, new ItemsListener() {
                    @Override
                    public void onSuccess(List<Vod> items) {
                        boolean needsWestern = !"western".equals(region)
                                && (items.isEmpty() || ("variety".equals(category) && items.size() < REGIONAL_VARIETY_MINIMUM));
                        if (!needsWestern) {
                            listener.onSuccess(items);
                            return;
                        }
                        fetchRegionFeed("western", category, dates, 0, tag, new ItemsListener() {
                            @Override
                            public void onSuccess(List<Vod> western) {
                                listener.onSuccess(mergeDistinct(items, western, REGIONAL_MERGE_CAP));
                            }

                            @Override
                            public void onError(Exception e) {
                                if (!items.isEmpty()) listener.onSuccess(items);
                                else listener.onError(e);
                            }
                        });
                    }

                    @Override
                    public void onError(Exception e) {
                        listener.onError(e);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                listener.onError(e);
            }
        });
    }

    /** 豆瓣 feed：latest_{trending|latest}.json → 逐日回退 douban_{cat}_enriched_{date}.json。 */
    public static void fetchDouban(String category, @Nullable Object tag, ItemsListener listener) {
        String cat = "latest".equals(category) ? "latest" : "trending";
        get(ROOT + "/douban/latest_" + cat + ".json", tag, new BodyListener() {
            @Override
            public void onBody(String body) {
                List<Vod> items = parseDoubanFeed(body);
                if (!items.isEmpty()) {
                    listener.onSuccess(items);
                    return;
                }
                fetchDoubanDates(cat, RexFeedDate.recent(RexFeedDate.MAX_BACKTRACK_DAYS), 0, tag, listener);
            }

            @Override
            public void onError(Exception e) {
                fetchDoubanDates(cat, RexFeedDate.recent(RexFeedDate.MAX_BACKTRACK_DAYS), 0, tag, listener);
            }
        });
    }

    /** 动漫 feed：anime/latest.json 按星期分组。 */
    public static void fetchAnime(@Nullable Object tag, WeeklyListener listener) {
        get(ROOT + "/anime/latest.json", tag, new BodyListener() {
            @Override
            public void onBody(String body) {
                listener.onSuccess(parseAnimeWeekly(body));
            }

            @Override
            public void onError(Exception e) {
                listener.onError(e);
            }
        });
    }

    /** 动漫 feed 中「今天」（东八区）当更的一组。 */
    public static void fetchAnimeToday(@Nullable Object tag, ItemsListener listener) {
        fetchAnime(tag, new WeeklyListener() {
            @Override
            public void onSuccess(Map<String, List<Vod>> weekly) {
                listener.onSuccess(todayFromWeekly(weekly, System.currentTimeMillis()));
            }

            @Override
            public void onError(Exception e) {
                listener.onError(e);
            }
        });
    }

    /** 取消某个 tag 的全部在途请求。 */
    public static void cancel(@Nullable Object tag) {
        if (tag == null) return;
        Set<Call> calls = PENDING.remove(tag);
        if (calls == null) return;
        for (Call call : calls) call.cancel();
    }

    private static void fetchRegionFeed(String region, String category, List<String> dates, int index, @Nullable Object tag, ItemsListener listener) {
        if (index >= dates.size()) {
            listener.onSuccess(new ArrayList<>());
            return;
        }
        String url = ROOT + "/regional-tv/" + dates.get(index) + "/" + region + "_" + category + ".json";
        get(url, tag, new BodyListener() {
            @Override
            public void onBody(String body) {
                List<Vod> items = parseRegionalFeed(body, region, category);
                if (!items.isEmpty()) listener.onSuccess(items);
                else fetchRegionFeed(region, category, dates, index + 1, tag, listener);
            }

            @Override
            public void onError(Exception e) {
                fetchRegionFeed(region, category, dates, index + 1, tag, listener);
            }
        });
    }

    private static void fetchDoubanDates(String category, List<String> dates, int index, @Nullable Object tag, ItemsListener listener) {
        if (index >= dates.size()) {
            listener.onSuccess(new ArrayList<>());
            return;
        }
        String url = ROOT + "/douban/douban_" + category + "_enriched_" + dates.get(index) + ".json";
        get(url, tag, new BodyListener() {
            @Override
            public void onBody(String body) {
                List<Vod> items = parseDoubanFeed(body);
                if (!items.isEmpty()) listener.onSuccess(items);
                else fetchDoubanDates(category, dates, index + 1, tag, listener);
            }

            @Override
            public void onError(Exception e) {
                fetchDoubanDates(category, dates, index + 1, tag, listener);
            }
        });
    }

    private static void get(String url, @Nullable Object tag, BodyListener listener) {
        Request request = new Request.Builder().url(url).tag(tag == null ? url : tag).build();
        Call call = OkHttp.client().newCall(request);
        track(tag, call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                untrack(tag, call);
                if (!call.isCanceled()) post(() -> listener.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                untrack(tag, call);
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Rex feed failed: HTTP " + resp.code());
                    String body = resp.body().string();
                    post(() -> listener.onBody(body));
                } catch (Exception e) {
                    post(() -> listener.onError(e));
                }
            }
        });
    }

    // ---------- 解析与映射（纯函数，可单测） ----------

    /** manifest 的 date 字段；schemaVersion 不符返回空串。 */
    static String manifestDate(@Nullable String body) {
        JsonObject root = RexJson.object(body);
        if (RexJson.longValue(root, "schemaVersion") != REGIONAL_SCHEMA) return "";
        return RexJson.string(root, "date");
    }

    /** [manifest 日期, 今天, 昨天, ...]（去重，东八区）。 */
    static List<String> dates(@Nullable String manifestDate) {
        return dates(manifestDate, System.currentTimeMillis());
    }

    static List<String> dates(@Nullable String manifestDate, long now) {
        List<String> values = new ArrayList<>();
        if (manifestDate != null && !manifestDate.isEmpty()) values.add(manifestDate);
        for (String recent : RexFeedDate.recentFrom(now, RexFeedDate.MAX_BACKTRACK_DAYS)) if (!values.contains(recent)) values.add(recent);
        return values;
    }

    /** regional-tv 文件 → Vod；schemaVersion/region/category 校验不符时返回空。 */
    static List<Vod> parseRegionalFeed(@Nullable String body, String region, String category) {
        List<Vod> items = new ArrayList<>();
        JsonObject root = RexJson.object(body);
        if (RexJson.longValue(root, "schemaVersion") != REGIONAL_SCHEMA) return items;
        JsonElement regionElement = root.get("region");
        if (regionElement != null && regionElement.isJsonObject()) {
            String id = RexJson.string(RexJson.objectOf(regionElement), "id");
            if (!id.isEmpty() && !id.equals(region)) return items;
        } else if (regionElement != null && regionElement.isJsonPrimitive()) {
            String id = regionElement.getAsString();
            if (!id.isEmpty() && !id.equals(region)) return items;
        }
        String categoryValue = RexJson.string(root, "category");
        if (!categoryValue.isEmpty() && !categoryValue.equals(category)) return items;
        JsonArray array = RexJson.array(root, "items");
        if (array == null) return items;
        for (JsonElement element : array) {
            JsonObject entry = RexJson.objectOf(element);
            if (entry == null) continue;
            Vod item = vodFromEnriched(entry, RexJson.string(entry, "title"));
            if (item == null) continue;
            String genres = regionalGenres(entry);
            if (!genres.isEmpty()) item.setGenreText(genres);
            items.add(item);
        }
        return items;
    }

    /** douban feed（数组根或 {items:[...]}）→ 仅保留带 tmdb_info 的条目。 */
    static List<Vod> parseDoubanFeed(@Nullable String body) {
        List<Vod> items = new ArrayList<>();
        JsonElement root = RexJson.parse(body);
        JsonArray array = RexJson.arrayOf(root);
        if (array == null) array = RexJson.array(RexJson.objectOf(root), "items");
        if (array == null) return items;
        for (JsonElement element : array) {
            JsonObject entry = RexJson.objectOf(element);
            if (entry == null) continue;
            Vod item = vodFromEnriched(RexJson.child(entry, "tmdb_info"), RexJson.string(entry, "title"));
            if (item == null) continue;
            String genres = RexGenreDict.joinStrings(RexJson.array(entry, "genres"), RexGenreDict.DEFAULT_MAX);
            if (!genres.isEmpty()) item.setGenreText(genres);
            items.add(item);
        }
        return items;
    }

    /** anime/latest.json → 星期X → Vods（保留星期一..星期日 顺序，空组不收录）。 */
    static Map<String, List<Vod>> parseAnimeWeekly(@Nullable String body) {
        Map<String, List<Vod>> weekly = new LinkedHashMap<>();
        JsonObject root = RexJson.object(body);
        for (String day : WEEKDAYS) {
            JsonArray array = RexJson.array(root, day);
            if (array == null) continue;
            List<Vod> items = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject entry = RexJson.objectOf(element);
                if (entry == null) continue;
                Vod item = vodFromEnriched(RexJson.child(entry, "tmdb_info"), RexJson.string(entry, "bangumi_name"));
                if (item != null) items.add(item);
            }
            if (!items.isEmpty()) weekly.put(day, items);
        }
        return weekly;
    }

    /** enriched tmdb_info（regional/douban/anime 共用形状）→ Vod；缺 TMDB id 返回 null。 */
    @Nullable
    static Vod vodFromEnriched(@Nullable JsonObject info, @Nullable String fallbackTitle) {
        if (info == null) return null;
        long id = RexJson.longValue(info, "id");
        String mediaType = RexJson.string(info, "mediaType");
        if (id <= 0 || (!DiscoverMediaKey.MOVIE.equals(mediaType) && !DiscoverMediaKey.TV.equals(mediaType))) return null;
        String title = RexJson.string(info, "title");
        if (title.isEmpty() && fallbackTitle != null) title = fallbackTitle.trim();
        if (title.isEmpty()) return null;
        Vod item = new Vod();
        item.setId(DiscoverMediaKey.of(mediaType, id).toString());
        item.setName(title);
        item.setPic(DiscoverApi.tmdbPic(RexJson.string(info, "posterPath")));
        item.setBackdrop(DiscoverApi.tmdbBackdrop(RexJson.string(info, "backdropPath")));
        item.setYear(year(RexJson.string(info, "releaseDate")));
        item.setRemarks(DiscoverApi.tmdbRemarks(RexJson.doubleValue(info, "rating")));
        item.setContent(RexJson.string(info, "description"));
        item.setTypeName(mediaType);
        item.setGenreText(RexGenreDict.normalize(RexJson.string(info, "genreTitle")));
        return item;
    }

    /** regional 条目优先用 genreItems 的 id 查字典，退回中文 genreTitle。 */
    private static String regionalGenres(JsonObject entry) {
        JsonArray genreItems = RexJson.array(entry, "genreItems");
        if (genreItems != null && genreItems.size() > 0) {
            JsonArray ids = new JsonArray();
            for (JsonElement element : genreItems) {
                JsonObject genre = RexJson.objectOf(element);
                if (genre == null) continue;
                try {
                    ids.add(Integer.parseInt(RexJson.string(genre, "id")));
                } catch (NumberFormatException ignored) {
                }
            }
            String joined = RexGenreDict.join(ids);
            if (!joined.isEmpty()) return joined;
        }
        return RexGenreDict.normalize(RexJson.string(entry, "genreTitle"));
    }

    /** base + extra 去重合并（按 Vod id）；cap 大于 0 时限制总数。 */
    public static List<Vod> mergeDistinct(List<Vod> base, List<Vod> extra, int cap) {
        List<Vod> merged = new ArrayList<>(base);
        Set<String> ids = new LinkedHashSet<>();
        for (Vod item : merged) ids.add(item.getId());
        for (Vod item : extra) {
            if (cap > 0 && merged.size() >= cap) break;
            if (ids.add(item.getId())) merged.add(item);
        }
        return merged;
    }

    static String year(@Nullable String releaseDate) {
        String value = releaseDate == null ? "" : releaseDate.trim();
        return value.length() >= 4 ? value.substring(0, 4) : "";
    }

    /** 「星期X」（东八区）。 */
    public static String weekday(long epochMillis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        calendar.setTimeInMillis(epochMillis);
        int index = calendar.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY;
        if (index < 0) index += 7;
        return WEEKDAYS[Math.min(index, WEEKDAYS.length - 1)];
    }

    public static List<Vod> todayFromWeekly(Map<String, List<Vod>> weekly, long epochMillis) {
        List<Vod> items = weekly == null ? null : weekly.get(weekday(epochMillis));
        return items == null ? new ArrayList<>() : items;
    }

    private static void track(@Nullable Object tag, Call call) {
        if (tag == null) return;
        PENDING.computeIfAbsent(tag, key -> ConcurrentHashMap.newKeySet()).add(call);
    }

    private static void untrack(@Nullable Object tag, Call call) {
        if (tag == null) return;
        Set<Call> calls = PENDING.get(tag);
        if (calls == null) return;
        calls.remove(call);
        if (calls.isEmpty()) PENDING.remove(tag);
    }

    private static void post(Runnable runnable) {
        if (App.get() == null) runnable.run();
        else App.post(runnable);
    }
}
