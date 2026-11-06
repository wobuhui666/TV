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
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class LogvarApi {

    private static final String TAG = LogvarApi.class.getSimpleName();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern TITLE_SEASON = Pattern.compile("(?i)(?<![a-z0-9])(?:season\\s*|s)[0-9]{1,3}(?![a-z0-9])|第\\s*[零〇一二两三四五六七八九十百千0-9]+\\s*季");
    private static final Pattern TITLE_YEAR = Pattern.compile("[（(](?:19|20)[0-9]{2}[）)]\\s*$");

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
        String base = normalizeBase(DanmakuSetting.getEffectiveLogvarUrl());
        if (base.isEmpty()) {
            postSuccess(listener, Collections.emptyList());
            return;
        }
        String videoUrl = preferredVideoUrl(result);
        if (isHttpUrl(videoUrl)) {
            searchByVideoUrl(base, videoUrl, new Listener() {
                @Override
                public void onSuccess(List<Danmaku> items) {
                    if (items.isEmpty()) searchByMatch(base, name, episode, reliableEpisodeLabel, listener);
                    else listener.onSuccess(items);
                }

                @Override
                public void onError(Exception e) {
                    searchByMatch(base, name, episode, reliableEpisodeLabel, listener);
                }
            });
        } else {
            searchByMatch(base, name, episode, reliableEpisodeLabel, listener);
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

    private static void searchByVideoUrl(String base, String videoUrl, Listener listener) {
        String url = buildCommentByUrlUrl(base, videoUrl);
        if (url.isEmpty()) {
            postSuccess(listener, Collections.emptyList());
            return;
        }
        OkHttp.newCall(url, TAG).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    String text = body == null ? "" : body.string();
                    if (response.isSuccessful() && hasDanmakuXml(text)) {
                        postSuccess(listener, Collections.singletonList(createDanmaku("Logvar URL", url)));
                    } else {
                        postSuccess(listener, Collections.emptyList());
                    }
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

    private static void searchByMatch(String base, String name, String episode, boolean reliableEpisodeLabel, Listener listener) {
        EpisodeLabel label = EpisodeLabelParser.parse(episode);
        EpisodeLabel title = EpisodeLabelParser.parse(name);
        if (!reliableEpisodeLabel || !label.isApiSafe() || conflictingSeasons(title, label)) {
            postSuccess(listener, Collections.emptyList());
            return;
        }
        String url = buildMatchUrl(base);
        if (url.isEmpty()) {
            postSuccess(listener, Collections.emptyList());
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("fileName", buildMatchFileName(name, episode));
        RequestBody body = RequestBody.create(json.toString(), JSON);
        OkHttp.newCall(url, body, TAG).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    String text = body == null ? "" : body.string();
                    postSuccess(listener, response.isSuccessful() ? parseAutoMatch(base, text, name, episode) : Collections.emptyList());
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
        EpisodeLabel label = EpisodeLabelParser.parse(epName);
        EpisodeLabel show = EpisodeLabelParser.parse(title);
        Integer season = label.season() == null ? show.season() : label.season();
        // A release filename carries version/group information needed by the matcher.
        // Unknown seasons remain unknown instead of silently becoming season one.
        if (label.isApiSafe() && !conflictingSeasons(show, label) && season != null && label.edition().isEmpty()) {
            return String.format(Locale.ROOT, "%s S%02dE%02d", title, season, label.number()).trim();
        }
        return (title + " " + epName).trim();
    }

    public static String buildSearchEpisodeQuery(String episode) {
        String value = safe(episode);
        EpisodeLabel label = EpisodeLabelParser.parse(value);
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
        EpisodeLabel expected = EpisodeLabelParser.parse(episode);
        EpisodeLabel show = EpisodeLabelParser.parse(name);
        if (!expected.isApiSafe() || conflictingSeasons(show, expected)) return Collections.emptyList();
        String expectedTitle = normalizedMatchTitle(name);
        if (expectedTitle.isEmpty()) return Collections.emptyList();
        JsonObject response = parseObject(json);
        if (explicitFailure(response)) return Collections.emptyList();
        JsonArray matches = getArray(response, "matches");
        if (matches == null) return Collections.emptyList();
        Integer expectedSeason = expected.season() == null ? show.season() : expected.season();
        List<Danmaku> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : matches) {
            JsonObject item = object(element);
            String animeTitle = string(item, "animeTitle");
            String episodeTitle = string(item, "episodeTitle");
            EpisodeLabel candidate = EpisodeLabelParser.parse(episodeTitle);
            EpisodeLabel candidateShow = EpisodeLabelParser.parse(animeTitle);
            if (!candidate.isApiSafe() || conflictingSeasons(candidateShow, candidate)
                    || !expected.number().equals(candidate.number())
                    || !expectedTitle.equals(normalizedMatchTitle(animeTitle))) continue;
            Integer candidateSeason = candidate.season() == null ? candidateShow.season() : candidate.season();
            if (expectedSeason != null ? !expectedSeason.equals(candidateSeason) : candidateSeason != null) continue;
            String episodeId = string(item, "episodeId");
            String url = buildCommentUrl(base, episodeId);
            if (url.isEmpty() || !seen.add(episodeId)) continue;
            items.add(createDanmaku(joinName(animeTitle, episodeTitle), url));
            if (items.size() > 1) return Collections.emptyList();
        }
        return items;
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

    private static String normalizedMatchTitle(String title) {
        String normalized = safe(title);
        try {
            normalized = Trans.t2s(false, normalized);
        } catch (Throwable ignored) {
        }
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        normalized = TITLE_SEASON.matcher(normalized).replaceAll("");
        normalized = TITLE_YEAR.matcher(normalized).replaceAll("");
        return normalized.replaceAll("[^\\p{L}\\p{Nd}]", "").toLowerCase(Locale.ROOT);
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
