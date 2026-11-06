package com.fongmi.android.tv.api;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.source.EpisodeLabel;
import com.fongmi.android.tv.source.EpisodeLabelParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class LogvarApi {

    private static final String TAG = LogvarApi.class.getSimpleName();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern TITLE_SEASON = Pattern.compile("(?i)(?<![a-z0-9])(?:season\\s*|s)[0-9]{1,3}(?![a-z0-9])|第\\s*[零〇一二两三四五六七八九十百千0-9]+\\s*季");
    private static final String PLATFORMS = "qq|tencent|qiyi|iqiyi|bilibili[0-9]*|bili|mgtv|mango|youku|renren|hanjutv|bahamut|baha|dandan(?:play)?|animeko|tmdb|local|hongguo|aiyifan|sohu|pptv|douyin|ixigua|letv|leshi|maiduidui|xigua|vod|unknown";
    private static final Pattern PLATFORM_PREFIX = Pattern.compile("(?i)^\\s*(?:【|\\[)(?:" + PLATFORMS + ")(?:】|\\])\\s*");
    private static final Pattern SOURCE_SUFFIX = Pattern.compile("(?i)\\s*from\\s*(?:" + PLATFORMS + ")\\s*$");
    private static final Pattern TITLE_CATEGORY = Pattern.compile("(?i)\\s*【(?:电视剧|动漫|电影|动画|综艺|纪录片|番剧|剧集|TV|TV Series|Movie|OVA|ONA|Special|未知|其他|Other)】\\s*$");
    private static final Pattern SPECIAL_CATEGORY = Pattern.compile("(?i)【(?:特别篇|特典|OVA|OAD|Special|剧场版|Movie|电影)】");
    private static final Pattern TITLE_YEAR = Pattern.compile("[（(](?:(?:19|20)[0-9]{2}|N/A)[）)]\\s*$");
    private static final Pattern SOURCE_YEAR = Pattern.compile("[（(]((?:19|20)[0-9]{2})[）)]");

    public interface Listener {

        void onSuccess(List<Danmaku> items);

        void onError(Exception e);
    }

    public static boolean canSearch() {
        return !normalizeBase(DanmakuSetting.getEffectiveLogvarUrl()).isEmpty();
    }

    public static void searchAuto(String name, String episode, @Nullable Result result, Listener listener) {
        searchAuto(name, episode, result, true, listener);
    }

    public static void searchAuto(String name, String episode, @Nullable Result result, boolean reliableEpisodeLabel, Listener listener) {
        searchAuto(DanmakuSetting.getEffectiveLogvarUrl(), name, episode, preferredVideoUrl(result), reliableEpisodeLabel,
                request -> OkHttp.client().newCall(request), App::post, listener);
    }

    static void searchAuto(String serviceUrl, String name, String episode, String videoUrl, boolean reliableEpisodeLabel,
                           Function<Request, Call> calls, Consumer<Runnable> dispatch, Listener listener) {
        String base = normalizeBase(serviceUrl);
        if (base.isEmpty()) {
            dispatch.accept(() -> listener.onSuccess(Collections.emptyList()));
            return;
        }
        if (isHttpUrl(videoUrl)) {
            HttpUrl commentUrl = HttpUrl.parse(buildCommentByUrlUrl(base, videoUrl));
            if (commentUrl == null) {
                searchByEpisode(base, name, episode, reliableEpisodeLabel, calls, dispatch, listener);
                return;
            }
            Request request = new Request.Builder().url(commentUrl).tag(TAG).build();
            requestAuto(request, calls, dispatch, text -> hasDanmakuXml(text)
                    ? Collections.singletonList(createDanmaku("Logvar URL", request.url().toString())) : Collections.emptyList(), new Listener() {
                @Override
                public void onSuccess(List<Danmaku> items) {
                    if (items.isEmpty()) searchByEpisode(base, name, episode, reliableEpisodeLabel, calls, dispatch, listener);
                    else listener.onSuccess(items);
                }

                @Override
                public void onError(Exception e) {
                    searchByEpisode(base, name, episode, reliableEpisodeLabel, calls, dispatch, listener);
                }
            });
        } else {
            searchByEpisode(base, name, episode, reliableEpisodeLabel, calls, dispatch, listener);
        }
    }

    public static void searchEpisodes(String name, String episode, Listener listener) {
        String base = normalizeBase(DanmakuSetting.getEffectiveLogvarUrl());
        if (base.isEmpty()) {
            postSuccess(listener, Collections.emptyList());
            return;
        }
        String url = buildSearchEpisodesUrl(base, name, episode);
        if (url.isEmpty()) {
            postSuccess(listener, Collections.emptyList());
            return;
        }
        OkHttp.newCall(url, TAG).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    String text = body == null ? "" : body.string();
                    postSuccess(listener, response.isSuccessful() ? parseEpisodes(base, text) : Collections.emptyList());
                } catch (Exception e) {
                    postError(listener, e);
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                postError(listener, e);
            }
        });
    }

    private static void searchByEpisode(String base, String name, String episode, boolean reliableEpisodeLabel,
                                        Function<Request, Call> calls, Consumer<Runnable> dispatch, Listener listener) {
        EpisodeLabel label = EpisodeLabelParser.parse(stripPlatformPrefix(episode));
        EpisodeLabel title = EpisodeLabelParser.parse(undecoratedTitle(name));
        if (!reliableEpisodeLabel || !safeCoordinate(title, label, name)) {
            dispatch.accept(() -> listener.onSuccess(Collections.emptyList()));
            return;
        }
        Integer season = label.season() == null ? title.season() : label.season();
        String url = season == null ? buildSearchEpisodesUrl(base, matchTitle(name), String.valueOf(label.number())) : buildMatchUrl(base);
        HttpUrl endpoint = HttpUrl.parse(url);
        if (endpoint == null) {
            dispatch.accept(() -> listener.onSuccess(Collections.emptyList()));
            return;
        }
        Request.Builder request = new Request.Builder().url(endpoint).tag(TAG);
        if (season != null) {
            JsonObject json = new JsonObject();
            json.addProperty("fileName", buildMatchFileName(name, episode));
            request.post(RequestBody.create(json.toString(), JSON));
        }
        requestAuto(request.build(), calls, dispatch,
                text -> season == null ? parseAutoEpisodes(base, text, name, episode) : parseAutoMatch(base, text, name, episode), listener);
    }

    private static void requestAuto(Request request, Function<Request, Call> calls, Consumer<Runnable> dispatch,
                                    Function<String, List<Danmaku>> parse, Listener listener) {
        calls.apply(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    String text = body == null ? "" : body.string();
                    List<Danmaku> items = response.isSuccessful() ? parse.apply(text) : Collections.emptyList();
                    dispatch.accept(() -> listener.onSuccess(items));
                } catch (Exception error) {
                    dispatch.accept(() -> listener.onError(error));
                }
            }

            @Override
            public void onFailure(Call call, IOException error) {
                dispatch.accept(() -> listener.onError(error));
            }
        });
    }

    public static String normalizeBase(String url) {
        if (url == null) return "";
        String base = url.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) return "";
        return base.endsWith("/api/v2") ? base : base + "/api/v2";
    }

    public static String buildCommentByUrlUrl(String base, String videoUrl) {
        HttpUrl parsed = HttpUrl.parse(normalizeBase(base) + "/comment");
        if (parsed == null || !isHttpUrl(videoUrl)) return "";
        return parsed.newBuilder().addQueryParameter("url", videoUrl).addQueryParameter("format", "xml").build().toString();
    }

    public static String buildCommentUrl(String base, String episodeId) {
        if (episodeId == null || episodeId.trim().isEmpty()) return "";
        HttpUrl parsed = HttpUrl.parse(normalizeBase(base) + "/comment/" + episodeId.trim());
        if (parsed == null) return "";
        return parsed.newBuilder().addQueryParameter("format", "xml").build().toString();
    }

    public static String buildSearchEpisodesUrl(String base, String name, String episode) {
        HttpUrl parsed = HttpUrl.parse(normalizeBase(base) + "/search/episodes");
        if (parsed == null) return "";
        return parsed.newBuilder().addQueryParameter("anime", safe(name)).addQueryParameter("episode", buildSearchEpisodeQuery(episode)).build().toString();
    }

    public static String buildMatchUrl(String base) {
        return normalizeBase(base).isEmpty() ? "" : normalizeBase(base) + "/match";
    }

    public static String buildMatchFileName(String name, String episode) {
        String title = safe(name);
        String epName = safe(episode);
        EpisodeLabel label = EpisodeLabelParser.parse(stripPlatformPrefix(epName));
        EpisodeLabel show = EpisodeLabelParser.parse(undecoratedTitle(title));
        Integer season = label.season() == null ? show.season() : label.season();
        // The server recognizes the first SxxExx coordinate; release evidence goes after it.
        if (safeCoordinate(show, label, title) && season != null) {
            String canonical = String.format(Locale.ROOT, "%s S%02dE%02d", matchTitle(title), season, label.number()).trim();
            Integer year = sourceYear(title);
            if (year != null) canonical += " (" + year + ")";
            return label.edition().isEmpty() ? canonical : canonical + " " + epName;
        }
        return (title + " " + epName).trim();
    }

    public static String buildSearchEpisodeQuery(String episode) {
        String value = safe(episode);
        EpisodeLabel label = EpisodeLabelParser.parse(stripPlatformPrefix(value));
        return label.isApiSafe() ? String.valueOf(label.number()) : value;
    }

    public static String preferredVideoUrl(@Nullable Result result) {
        if (result == null) return "";
        String raw = result.getUrl().v();
        if (isHttpUrl(raw)) return raw;
        String real = result.getRealUrl();
        return isHttpUrl(real) ? real : "";
    }

    public static boolean hasDanmakuXml(String text) {
        return text != null && (text.contains("<d ") || text.contains("<d\t") || text.contains("<d\n"));
    }

    public static List<Danmaku> parseMatch(String base, String json) {
        List<Danmaku> items = new ArrayList<>();
        JsonObject response = parseObject(json);
        if (explicitFailure(response)) return items;
        JsonArray matches = getArray(response, "matches");
        if (matches == null) return items;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : matches) {
            JsonObject item = object(element);
            String episodeId = string(item, "episodeId");
            String title = joinName(string(item, "animeTitle"), string(item, "episodeTitle"));
            String url = buildCommentUrl(base, episodeId);
            if (!url.isEmpty() && seen.add(episodeId)) {
                items.add(createDanmaku(title.isEmpty() ? "Logvar" : title, url));
            }
        }
        return items;
    }

    /** Automatic binding needs one consistent title/episode, not merely the first candidate. */
    public static List<Danmaku> parseAutoMatch(String base, String json, String name, String episode) {
        JsonObject response = parseObject(json);
        return parseAutoCandidates(base, response, getArray(response, "matches"), name, episode, confirmedMatch(response));
    }

    /** Unseasoned input uses the server's episode-search endpoint instead of inventing season one. */
    public static List<Danmaku> parseAutoEpisodes(String base, String json, String name, String episode) {
        JsonObject response = parseObject(json);
        JsonArray animes = getArray(response, "animes");
        JsonArray candidates = new JsonArray();
        if (animes != null) for (JsonElement element : animes) {
            JsonObject anime = object(element);
            JsonArray episodes = getArray(anime, "episodes");
            if (episodes == null) continue;
            for (JsonElement episodeElement : episodes) {
                JsonObject candidate = object(episodeElement).deepCopy();
                candidate.addProperty("animeTitle", string(anime, "animeTitle"));
                candidates.add(candidate);
            }
        }
        return parseAutoCandidates(base, response, candidates, name, episode, false);
    }

    private static List<Danmaku> parseAutoCandidates(String base, JsonObject response, JsonArray candidates,
                                                     String name, String episode, boolean matched) {
        EpisodeLabel expected = EpisodeLabelParser.parse(stripPlatformPrefix(episode));
        EpisodeLabel show = EpisodeLabelParser.parse(undecoratedTitle(name));
        if (!safeCoordinate(show, expected, name) || explicitFailure(response) || candidates == null) return Collections.emptyList();
        String expectedTitle = normalizedMatchTitle(name);
        if (expectedTitle.isEmpty()) return Collections.emptyList();
        Integer expectedSeason = expected.season() == null ? show.season() : expected.season();
        Integer expectedYear = sourceYear(name);
        List<Danmaku> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : candidates) {
            JsonObject item = object(element);
            String animeTitle = string(item, "animeTitle");
            String episodeTitle = string(item, "episodeTitle");
            EpisodeLabel candidate = EpisodeLabelParser.parse(stripPlatformPrefix(episodeTitle));
            EpisodeLabel candidateShow = EpisodeLabelParser.parse(undecoratedTitle(animeTitle));
            if (!safeCoordinate(candidateShow, candidate, animeTitle)
                    || !expected.number().equals(candidate.number())
                    || !expectedTitle.equals(normalizedMatchTitle(animeTitle))) continue;
            Integer candidateYear = sourceYear(animeTitle);
            if (expectedYear != null && candidateYear != null && !expectedYear.equals(candidateYear)) continue;
            Integer candidateSeason = candidate.season() == null ? candidateShow.season() : candidate.season();
            if (expectedSeason != null && candidateSeason != null && !expectedSeason.equals(candidateSeason)) continue;
            if (expectedSeason != null && candidateSeason == null && !matched) continue;
            boolean expectedSpecial = expected.isSpecial() || Integer.valueOf(0).equals(expectedSeason);
            boolean candidateSpecial = candidate.isSpecial() || Integer.valueOf(0).equals(candidateSeason);
            if (expectedSpecial != candidateSpecial) continue;
            String episodeId = string(item, "episodeId");
            String url = buildCommentUrl(base, episodeId);
            if (url.isEmpty() || !seen.add(episodeId)) continue;
            items.add(createDanmaku(joinName(animeTitle, episodeTitle), url));
            // Different seasons, years or provider candidates require a choice even if each title matches.
            if (items.size() > 1) return Collections.emptyList();
        }
        return items;
    }

    private static boolean safeCoordinate(EpisodeLabel title, EpisodeLabel episode, String titleText) {
        Integer season = episode.season() == null ? title.season() : episode.season();
        boolean special = episode.isSpecial() || title.isSpecial() || SPECIAL_CATEGORY.matcher(safe(titleText)).find();
        return episode.isSingleEpisode() && episode.part().isEmpty() && !episode.isPreview() && !title.isPreview()
                && !conflictingSeasons(title, episode) && (!special || Integer.valueOf(0).equals(season));
    }

    private static boolean confirmedMatch(JsonObject response) {
        try {
            return response.has("isMatched") && response.get("isMatched").getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean conflictingSeasons(EpisodeLabel title, EpisodeLabel episode) {
        return title.isAmbiguous() || episode.isAmbiguous() || title.season() != null && episode.season() != null
                && !title.season().equals(episode.season());
    }

    private static boolean explicitFailure(JsonObject response) {
        for (String key : new String[]{"success", "isMatched"}) {
            try {
                JsonElement value = response.get(key);
                if (value != null && !value.isJsonNull() && !value.getAsBoolean()) return true;
            } catch (RuntimeException error) {
                return true;
            }
        }
        return false;
    }

    private static String stripPlatformPrefix(String text) {
        String value = normalizeText(text);
        String previous;
        do {
            previous = value;
            value = PLATFORM_PREFIX.matcher(value).replaceFirst("").trim();
        } while (!value.equals(previous));
        return value;
    }

    private static String undecoratedTitle(String title) {
        String value = stripPlatformPrefix(title);
        value = SOURCE_SUFFIX.matcher(value).replaceFirst("").trim();
        value = TITLE_CATEGORY.matcher(value).replaceFirst("").trim();
        return TITLE_YEAR.matcher(value).replaceFirst("").trim();
    }

    private static String matchTitle(String title) {
        return TITLE_SEASON.matcher(undecoratedTitle(title)).replaceAll("").trim();
    }

    private static String normalizedMatchTitle(String title) {
        return matchTitle(title).replaceAll("[^\\p{L}\\p{Nd}]", "").toLowerCase(Locale.ROOT);
    }

    private static Integer sourceYear(String title) {
        Matcher year = SOURCE_YEAR.matcher(normalizeText(title));
        return year.find() ? Integer.parseInt(year.group(1)) : null;
    }

    private static String normalizeText(String text) {
        String value = safe(text);
        try {
            value = Trans.t2s(false, value);
        } catch (Throwable ignored) {
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
    }

    public static List<Danmaku> parseEpisodes(String base, String json) {
        List<Danmaku> items = new ArrayList<>();
        JsonArray animes = getArray(parseObject(json), "animes");
        if (animes == null) return items;
        for (JsonElement animeElement : animes) {
            JsonObject anime = object(animeElement);
            String animeTitle = string(anime, "animeTitle");
            JsonArray episodes = getArray(anime, "episodes");
            if (episodes == null) continue;
            for (JsonElement episodeElement : episodes) {
                JsonObject episode = object(episodeElement);
                String episodeId = string(episode, "episodeId");
                String name = joinName(animeTitle, string(episode, "episodeTitle"));
                String url = buildCommentUrl(base, episodeId);
                if (!url.isEmpty()) items.add(createDanmaku(name.isEmpty() ? "Logvar" : name, url));
            }
        }
        return items;
    }

    public static boolean isHttpUrl(String url) {
        if (url == null) return false;
        String value = url.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static JsonObject parseObject(String json) {
        try {
            return object(JsonParser.parseString(json));
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static JsonObject object(JsonElement element) {
        try {
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static JsonArray getArray(JsonObject object, String key) {
        try {
            JsonElement element = object.get(key);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        try {
            JsonElement element = object.get(key);
            return element == null || element.isJsonNull() ? "" : element.getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Danmaku createDanmaku(String name, String url) {
        Danmaku danmaku = new Danmaku();
        danmaku.setName(name);
        danmaku.setUrl(url);
        return danmaku;
    }

    private static String joinName(String title, String episode) {
        String name = safe(title);
        String ep = safe(episode);
        if (name.isEmpty()) return ep;
        if (ep.isEmpty()) return name;
        return name + " " + ep;
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }

    private static void postSuccess(Listener listener, List<Danmaku> items) {
        App.post(() -> listener.onSuccess(items));
    }

    private static void postError(Listener listener, Exception e) {
        App.post(() -> listener.onError(e));
    }

    public static void cancel() {
        OkHttp.cancel(TAG);
    }
}
