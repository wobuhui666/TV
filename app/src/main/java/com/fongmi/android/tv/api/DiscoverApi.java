package com.fongmi.android.tv.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.DiscoverCredit;
import com.fongmi.android.tv.bean.DiscoverDetail;
import com.fongmi.android.tv.bean.DiscoverFacet;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.DiscoverQuery;
import com.fongmi.android.tv.bean.DoubanDetail;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.TmdbEndpoint;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public class DiscoverApi {

    private static final String TAG = "DiscoverApi";
    private static final String DOUBAN_LIST = "https://movie.douban.com/j/search_subjects";
    private static final String DOUBAN_ABSTRACT = "https://movie.douban.com/j/subject_abstract";
    private static final String DOUBAN_PIC_SUFFIX = "@Referer=https://movie.douban.com/@User-Agent=Mozilla/5.0";
    private static final long CACHE_TTL = 30 * 60 * 1000L;
    private static final Map<Row, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, DoubanDetail> DOUBAN_DETAIL_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, DiscoverMediaKey> DOUBAN_MATCH_CACHE = new ConcurrentHashMap<>();
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern TITLE_YEAR_SUFFIX = Pattern.compile("\\s*[（(](?:19|20)\\d{2}[)）]\\s*$");
    private static final Pattern TITLE_NOISE = Pattern.compile("[\\s\\p{P}]+");

    public enum Row {
        DOUBAN_HOT_MOVIE, DOUBAN_HOT_TV, DOUBAN_NEW_MOVIE,
        TMDB_DAY, TMDB_WEEK, TMDB_NOW_PLAYING, TMDB_POPULAR_MOVIE, TMDB_POPULAR_TV,
        TMDB_TOP_MOVIE, TMDB_TOP_TV
    }

    public interface Listener {

        void onSuccess(Row row, List<Vod> items);

        void onError(Row row, Exception e);
    }

    public interface QueryListener {

        void onSuccess(List<Vod> items, int page, int totalPages);

        void onError(Exception e);
    }

    public interface DetailListener {

        void onSuccess(DiscoverDetail detail);

        void onError(Exception e);
    }

    public interface DoubanDetailListener {

        void onSuccess(DoubanDetail detail);

        void onError(Exception e);
    }

    public interface MatchListener {

        void onMatch(DiscoverMediaKey key);

        void onNoMatch();

        void onError(Exception e);
    }

    private DiscoverApi() {
    }

    public static void fetch(Row row, @Nullable String tmdbApiKey, Listener listener) {
        fetch(row, tmdbApiKey, TAG, listener);
    }

    public static void fetch(Row row, @Nullable String tmdbApiKey, Object tag, Listener listener) {
        CacheEntry entry = CACHE.get(row);
        if (entry != null && !entry.expired()) {
            post(() -> listener.onSuccess(row, entry.copy()));
            return;
        }
        HttpUrl url = buildUrl(row, tmdbApiKey);
        if (url == null) {
            post(() -> listener.onError(row, new IOException("Discover url unavailable")));
            return;
        }
        newCall(row, url, tag).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) post(() -> listener.onError(row, e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Discover failed: HTTP " + resp.code());
                    List<Vod> items = isDouban(row) ? parseDoubanSubjects(resp.body().string(), rowMediaType(row)) : parseTmdbResults(resp.body().string(), rowMediaType(row));
                    if (!items.isEmpty()) CACHE.put(row, new CacheEntry(items));
                    post(() -> listener.onSuccess(row, new ArrayList<>(items)));
                } catch (Exception e) {
                    post(() -> listener.onError(row, e));
                }
            }
        });
    }

    public static void cancel() {
        OkHttp.cancel(TAG);
    }

    public static void cancel(Object tag) {
        if (tag == null) return;
        for (Call call : OkHttp.client().dispatcher().queuedCalls()) if (tag.equals(call.request().tag())) call.cancel();
        for (Call call : OkHttp.client().dispatcher().runningCalls()) if (tag.equals(call.request().tag())) call.cancel();
    }

    public static void clearTmdbCache() {
        for (Row row : Row.values()) if (!isDouban(row)) CACHE.remove(row);
    }

    private static boolean isDouban(Row row) {
        return row == Row.DOUBAN_HOT_MOVIE || row == Row.DOUBAN_HOT_TV || row == Row.DOUBAN_NEW_MOVIE;
    }

    private static String rowMediaType(Row row) {
        return switch (row) {
            case DOUBAN_HOT_MOVIE, DOUBAN_NEW_MOVIE, TMDB_NOW_PLAYING, TMDB_POPULAR_MOVIE, TMDB_TOP_MOVIE -> DiscoverMediaKey.MOVIE;
            case DOUBAN_HOT_TV, TMDB_POPULAR_TV, TMDB_TOP_TV -> DiscoverMediaKey.TV;
            default -> "";
        };
    }

    @Nullable
    private static HttpUrl buildUrl(Row row, @Nullable String tmdbApiKey) {
        switch (row) {
            case DOUBAN_HOT_MOVIE:
                return buildDoubanUrl("movie", "热门");
            case DOUBAN_HOT_TV:
                return buildDoubanUrl("tv", "热门");
            case DOUBAN_NEW_MOVIE:
                return buildDoubanUrl("movie", "最新");
            case TMDB_DAY:
                return buildTmdbUrl("trending/all/day", tmdbApiKey);
            case TMDB_WEEK:
                return buildTmdbUrl("trending/all/week", tmdbApiKey);
            case TMDB_NOW_PLAYING:
                return buildTmdbUrl("movie/now_playing", tmdbApiKey);
            case TMDB_POPULAR_MOVIE:
                return buildTmdbUrl("movie/popular", tmdbApiKey);
            case TMDB_POPULAR_TV:
                return buildTmdbUrl("tv/popular", tmdbApiKey);
            case TMDB_TOP_MOVIE:
                return buildTmdbUrl("movie/top_rated", tmdbApiKey);
            case TMDB_TOP_TV:
                return buildTmdbUrl("tv/top_rated", tmdbApiKey);
            default:
                return null;
        }
    }

    @Nullable
    private static HttpUrl buildDoubanUrl(String type, String tag) {
        HttpUrl url = HttpUrl.parse(DOUBAN_LIST);
        if (url == null) return null;
        return url.newBuilder()
                .addQueryParameter("type", type)
                .addQueryParameter("tag", tag)
                .addQueryParameter("page_limit", "20")
                .addQueryParameter("page_start", "0")
                .build();
    }

    @Nullable
    private static HttpUrl buildTmdbUrl(String path, @Nullable String apiKey) {
        if (isEmpty(apiKey)) return null;
        HttpUrl url = HttpUrl.parse(TmdbEndpoint.getApiBase() + path);
        if (url == null) return null;
        return url.newBuilder()
                .addQueryParameter("api_key", apiKey.trim())
                .addQueryParameter("language", "zh-CN")
                .build();
    }

    private static Call newCall(Row row, HttpUrl url, Object tag) {
        Request.Builder builder = isDouban(row) ? doubanRequest(url, tag) : new Request.Builder().url(url).tag(tag);
        return OkHttp.client().newCall(builder.build());
    }

    static List<Vod> parseDoubanSubjects(String body) {
        return parseDoubanSubjects(body, "");
    }

    static List<Vod> parseDoubanSubjects(String body, String mediaType) {
        List<Vod> items = new ArrayList<>();
        JsonArray subjects = getArray(parseObject(body), "subjects");
        if (subjects == null) return items;
        for (JsonElement element : subjects) {
            JsonObject subject = getObject(element);
            String name = getString(subject, "title");
            String cover = getString(subject, "cover");
            if (isEmpty(name) || isEmpty(cover)) continue;
            Vod item = new Vod();
            item.setId("douban:" + getString(subject, "id"));
            item.setName(name);
            item.setPic(doubanPic(cover));
            item.setRemarks(doubanRemarks(getString(subject, "rate")));
            item.setTypeName(mediaType);
            items.add(item);
        }
        return items;
    }

    static List<Vod> parseTmdbResults(String body) {
        return parseTmdbResults(body, "");
    }

    static List<Vod> parseTmdbResults(String body, String fallbackMediaType) {
        List<Vod> items = new ArrayList<>();
        JsonArray results = getArray(parseObject(body), "results");
        if (results == null) return items;
        for (JsonElement element : results) {
            JsonObject result = getObject(element);
            if (result == null || "person".equalsIgnoreCase(getString(result, "media_type"))) continue;
            String mediaType = tmdbMediaType(result, fallbackMediaType);
            long id = getLong(result, "id");
            String name = tmdbName(result);
            String poster = getString(result, "poster_path");
            if (id <= 0 || isEmpty(mediaType) || isEmpty(name) || isEmpty(poster)) continue;
            Vod item = new Vod();
            item.setId(DiscoverMediaKey.of(mediaType, id).toString());
            item.setName(name);
            item.setPic(tmdbPic(poster));
            item.setYear(tmdbYear(result));
            item.setRemarks(tmdbRemarks(getDouble(result, "vote_average")));
            item.setContent(getString(result, "overview"));
            item.setTypeName(mediaType);
            item.setBackdrop(tmdbBackdrop(getString(result, "backdrop_path")));
            items.add(item);
        }
        return items;
    }

    static String doubanPic(String cover) {
        return isEmpty(cover) ? "" : cover.trim() + DOUBAN_PIC_SUFFIX;
    }

    static String doubanRemarks(String rate) {
        return isEmpty(rate) ? "" : rate.trim() + "分";
    }

    static String tmdbName(@Nullable JsonObject result) {
        String title = getString(result, "title");
        return isEmpty(title) ? getString(result, "name") : title;
    }

    static String tmdbPic(String posterPath) {
        if (isEmpty(posterPath)) return "";
        String path = posterPath.trim();
        return tmdbImage("w342", path);
    }

    static String tmdbBackdrop(String backdropPath) {
        return tmdbImage("w780", backdropPath);
    }

    static String tmdbLogo(String logoPath) {
        return tmdbImage("w300", logoPath);
    }

    private static String tmdbImage(String size, String value) {
        if (isEmpty(value)) return "";
        String path = value.trim();
        return TmdbEndpoint.getImageBase() + size + (path.startsWith("/") ? path : "/" + path);
    }

    static String tmdbType(@Nullable JsonObject result) {
        String type = tmdbMediaType(result, "");
        return DiscoverMediaKey.TV.equals(type) ? "剧集" : "电影";
    }

    static String tmdbMediaType(@Nullable JsonObject result, String fallback) {
        String type = getString(result, "media_type");
        if (!DiscoverMediaKey.MOVIE.equals(type) && !DiscoverMediaKey.TV.equals(type)) type = fallback;
        if (!DiscoverMediaKey.MOVIE.equals(type) && !DiscoverMediaKey.TV.equals(type)) {
            type = result != null && result.has("title") ? DiscoverMediaKey.MOVIE : result != null && result.has("name") ? DiscoverMediaKey.TV : "";
        }
        return type;
    }

    static String tmdbYear(@Nullable JsonObject result) {
        String date = getString(result, "release_date");
        if (isEmpty(date)) date = getString(result, "first_air_date");
        return date.trim().length() >= 4 ? date.trim().substring(0, 4) : "";
    }

    static String tmdbRemarks(double voteAverage) {
        return voteAverage > 0 ? String.format(Locale.US, "%.1f分", voteAverage) : "";
    }

    public interface FacetListener {
        void onSuccess(List<DiscoverFacet> items);

        void onError(Exception e);
    }

    public static void fetchGenres(@Nullable String apiKey, FacetListener listener) {
        fetchGenres(DiscoverMediaKey.MOVIE, apiKey, listener);
    }

    public static void fetchGenres(String mediaType, @Nullable String apiKey, FacetListener listener) {
        fetchGenres(mediaType, apiKey, TAG, listener);
    }

    public static void fetchGenres(String mediaType, @Nullable String apiKey, Object tag, FacetListener listener) {
        String type = DiscoverMediaKey.TV.equals(mediaType) ? DiscoverMediaKey.TV : DiscoverMediaKey.MOVIE;
        HttpUrl url = buildTmdbUrl("genre/" + type + "/list", apiKey);
        fetchFacets(url, DiscoverFacet.GENRE, tag, listener);
    }

    public static void fetchProviders(@Nullable String apiKey, FacetListener listener) {
        HttpUrl url = buildTmdbUrl("watch/providers/movie", apiKey);
        if (url != null) url = url.newBuilder().addQueryParameter("watch_region", "CN").build();
        fetchFacets(url, DiscoverFacet.PROVIDER, TAG, listener);
    }

    private static void fetchFacets(@Nullable HttpUrl url, String kind, Object tag, FacetListener listener) {
        if (url == null) {
            post(() -> listener.onError(new IOException("Discover facet url unavailable")));
            return;
        }
        OkHttp.client().newCall(new Request.Builder().url(url).tag(tag).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) post(() -> listener.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Discover facet failed: HTTP " + resp.code());
                    List<DiscoverFacet> items = parseFacets(resp.body().string(), kind);
                    post(() -> listener.onSuccess(items));
                } catch (Exception e) {
                    post(() -> listener.onError(e));
                }
            }
        });
    }

    static List<DiscoverFacet> parseFacets(String body, String kind) {
        List<DiscoverFacet> items = new ArrayList<>();
        JsonObject object = parseObject(body);
        JsonArray array = getArray(object, DiscoverFacet.GENRE.equals(kind) ? "genres" : "results");
        if (array == null) return items;
        for (JsonElement element : array) {
            JsonObject value = getObject(element);
            String id = getString(value, DiscoverFacet.PROVIDER.equals(kind) ? "provider_id" : "id");
            String name = getString(value, DiscoverFacet.PROVIDER.equals(kind) ? "provider_name" : "name");
            if (isEmpty(id) || isEmpty(name)) continue;
            items.add(new DiscoverFacet(kind, id, name, tmdbLogo(getString(value, "logo_path"))));
        }
        return items;
    }

    public static void fetchFiltered(DiscoverFacet facet, int page, @Nullable String apiKey, Listener listener) {
        fetchFiltered(facet, page, apiKey, TAG, listener);
    }

    public static void fetchFiltered(DiscoverFacet facet, int page, @Nullable String apiKey, Object tag, Listener listener) {
        Row row = DiscoverFacet.TOP_TV.equals(facet.getKind()) ? Row.TMDB_TOP_TV
                : DiscoverFacet.TOP_MOVIE.equals(facet.getKind()) ? Row.TMDB_TOP_MOVIE
                : DiscoverFacet.NOW_PLAYING.equals(facet.getKind()) ? Row.TMDB_NOW_PLAYING : null;
        HttpUrl url = row == null ? buildDiscoverUrl(facet, page, apiKey) : buildUrl(row, apiKey);
        if (url != null) url = url.newBuilder().setQueryParameter("page", String.valueOf(page)).build();
        if (url == null) {
            Row errorRow = row == null ? Row.TMDB_POPULAR_MOVIE : row;
            post(() -> listener.onError(errorRow, new IOException("Discover filter url unavailable")));
            return;
        }
        Row callbackRow = row == null ? Row.TMDB_POPULAR_MOVIE : row;
        newCall(callbackRow, url, tag).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) post(() -> listener.onError(callbackRow, e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Discover filter failed: HTTP " + resp.code());
                    List<Vod> items = parseTmdbResults(resp.body().string(), rowMediaType(callbackRow));
                    post(() -> listener.onSuccess(callbackRow, items));
                } catch (Exception e) {
                    post(() -> listener.onError(callbackRow, e));
                }
            }
        });
    }

    @Nullable
    private static HttpUrl buildDiscoverUrl(DiscoverFacet facet, int page, @Nullable String apiKey) {
        HttpUrl url = buildTmdbUrl("discover/movie", apiKey);
        if (url == null) return null;
        HttpUrl.Builder builder = url.newBuilder().addQueryParameter("page", String.valueOf(page)).addQueryParameter("sort_by", "popularity.desc");
        if (DiscoverFacet.GENRE.equals(facet.getKind())) builder.addQueryParameter("with_genres", facet.getId());
        if (DiscoverFacet.COMPANY.equals(facet.getKind())) builder.addQueryParameter("with_companies", facet.getId());
        if (DiscoverFacet.PROVIDER.equals(facet.getKind())) builder.addQueryParameter("with_watch_providers", facet.getId()).addQueryParameter("watch_region", "CN");
        return builder.build();
    }

    public static void fetch(DiscoverQuery query, @Nullable String apiKey, QueryListener listener) {
        fetch(query, apiKey, TAG, listener);
    }

    public static void fetch(DiscoverQuery query, @Nullable String apiKey, Object tag, QueryListener listener) {
        HttpUrl url = query.buildUrl(TmdbEndpoint.getApiBase(), apiKey);
        if (url == null) {
            post(() -> listener.onError(new IOException("Discover query url unavailable")));
            return;
        }
        OkHttp.client().newCall(new Request.Builder().url(url).tag(tag).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) post(() -> listener.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Discover query failed: HTTP " + resp.code());
                    String body = resp.body().string();
                    JsonObject object = parseObject(body);
                    int page = Math.max(1, getInt(object, "page"));
                    int totalPages = Math.max(page, getInt(object, "total_pages"));
                    List<Vod> items = parseTmdbResults(body, query.getMediaType());
                    post(() -> listener.onSuccess(items, page, totalPages));
                } catch (Exception e) {
                    post(() -> listener.onError(e));
                }
            }
        });
    }

    public static void fetchDoubanDetail(Vod item, Object tag, DoubanDetailListener listener) {
        String subjectId = doubanSubjectId(item == null ? "" : item.getId());
        if (subjectId.isEmpty()) {
            post(() -> listener.onError(new IOException("Invalid Douban subject")));
            return;
        }
        DoubanDetail cached = DOUBAN_DETAIL_CACHE.get(subjectId);
        if (cached != null) {
            post(() -> listener.onSuccess(cached));
            return;
        }
        HttpUrl base = HttpUrl.parse(DOUBAN_ABSTRACT);
        HttpUrl url = base == null ? null : base.newBuilder().addQueryParameter("subject_id", subjectId).build();
        if (url == null) {
            post(() -> listener.onError(new IOException("Douban detail url unavailable")));
            return;
        }
        Request request = doubanRequest(url, tag).build();
        OkHttp.client().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) post(() -> listener.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Douban detail failed: HTTP " + resp.code());
                    DoubanDetail detail = parseDoubanDetail(subjectId, item, resp.body().string());
                    if (detail == null) throw new IOException("Douban detail invalid");
                    DOUBAN_DETAIL_CACHE.put(subjectId, detail);
                    post(() -> listener.onSuccess(detail));
                } catch (Exception e) {
                    post(() -> listener.onError(e));
                }
            }
        });
    }

    public static void matchDoubanToTmdb(DoubanDetail detail, @Nullable String apiKey, Object tag, MatchListener listener) {
        if (detail == null || !detail.hasReliableMatchFields()) {
            post(listener::onNoMatch);
            return;
        }
        String cacheKey = detail.getSubjectId() + ":" + detail.getMediaType();
        DiscoverMediaKey cached = DOUBAN_MATCH_CACHE.get(cacheKey);
        if (cached != null) {
            post(() -> listener.onMatch(cached));
            return;
        }
        HttpUrl url = buildTmdbSearchUrl(detail, apiKey);
        if (url == null) {
            post(() -> listener.onError(new IOException("TMDB search url unavailable")));
            return;
        }
        OkHttp.client().newCall(new Request.Builder().url(url).tag(tag).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) post(() -> listener.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("TMDB match failed: HTTP " + resp.code());
                    DiscoverMediaKey key = selectTmdbMatch(resp.body().string(), detail.getMediaType(), detail.getTitle(), detail.getYear());
                    if (key == null) post(listener::onNoMatch);
                    else {
                        DOUBAN_MATCH_CACHE.put(cacheKey, key);
                        post(() -> listener.onMatch(key));
                    }
                } catch (Exception e) {
                    post(() -> listener.onError(e));
                }
            }
        });
    }

    @Nullable
    private static HttpUrl buildTmdbSearchUrl(DoubanDetail detail, @Nullable String apiKey) {
        HttpUrl url = buildTmdbUrl("search/" + detail.getMediaType(), apiKey);
        if (url == null) return null;
        String yearParameter = DiscoverMediaKey.TV.equals(detail.getMediaType()) ? "first_air_date_year" : "primary_release_year";
        return url.newBuilder()
                .addQueryParameter("query", detail.getTitle())
                .addQueryParameter(yearParameter, detail.getYear())
                .addQueryParameter("include_adult", "false")
                .build();
    }

    @Nullable
    static DoubanDetail parseDoubanDetail(String subjectId, @Nullable Vod fallback, String body) {
        JsonObject subject = getObject(parseObject(body), "subject");
        if (subject == null) return null;
        boolean tv = getBoolean(subject, "is_tv") || !getString(subject, "episodes_count").isEmpty()
                || "tv".equalsIgnoreCase(getString(subject, "subtype"));
        String mediaType = tv ? DiscoverMediaKey.TV : DiscoverMediaKey.MOVIE;
        String rawTitle = getString(subject, "title");
        String year = firstYear(getString(subject, "release_year"));
        if (year.isEmpty()) year = firstYear(rawTitle);
        String title = fallback == null ? "" : fallback.getName();
        if (title.isEmpty()) title = stripTitleYear(rawTitle);
        String poster = fallback == null ? "" : fallback.getPic();
        String rate = getString(subject, "rate");
        if (rate.isEmpty() && fallback != null) rate = value(fallback.getRemarks()).replace("分", "");
        JsonObject shortComment = getObject(subject, "short_comment");
        return new DoubanDetail(subjectId, mediaType, title, poster, doubanRemarks(rate), year,
                joinStrings(getArray(subject, "types")), getString(subject, "region"), doubanDuration(subject),
                joinStrings(getArray(subject, "directors")), joinStrings(getArray(subject, "actors")),
                getString(shortComment, "content"));
    }

    @Nullable
    static DiscoverMediaKey selectTmdbMatch(String body, String mediaType, String title, String year) {
        if (!DiscoverMediaKey.MOVIE.equals(mediaType) && !DiscoverMediaKey.TV.equals(mediaType)) return null;
        String targetTitle = normalizeTitle(title);
        String targetYear = firstYear(year);
        if (targetTitle.isEmpty() || targetYear.isEmpty()) return null;
        JsonArray results = getArray(parseObject(body), "results");
        if (results == null) return null;
        for (JsonElement element : results) {
            JsonObject result = getObject(element);
            String candidateType = tmdbMediaType(result, "");
            if (!mediaType.equals(candidateType)) continue;
            String candidateTitle = DiscoverMediaKey.MOVIE.equals(mediaType) ? getString(result, "title") : getString(result, "name");
            String originalTitle = DiscoverMediaKey.MOVIE.equals(mediaType) ? getString(result, "original_title") : getString(result, "original_name");
            boolean exact = targetTitle.equals(normalizeTitle(candidateTitle)) || targetTitle.equals(normalizeTitle(originalTitle));
            if (!exact || !targetYear.equals(tmdbYear(result))) continue;
            long id = getLong(result, "id");
            if (id > 0) return DiscoverMediaKey.of(mediaType, id);
        }
        return null;
    }

    private static String doubanSubjectId(String id) {
        if (id == null || !id.startsWith("douban:")) return "";
        return id.substring("douban:".length()).trim();
    }

    private static Request.Builder doubanRequest(HttpUrl url, Object tag) {
        return new Request.Builder().url(url).tag(tag)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://movie.douban.com/")
                .header("Accept", "application/json,text/plain,*/*");
    }

    private static String doubanDuration(JsonObject subject) {
        String duration = getString(subject, "duration");
        String episodes = getString(subject, "episodes_count");
        if (!episodes.isEmpty()) duration = duration.isEmpty() ? episodes + "集" : episodes + "集 · " + duration;
        return duration;
    }

    private static String stripTitleYear(String title) {
        return TITLE_YEAR_SUFFIX.matcher(title == null ? "" : title.trim()).replaceFirst("").trim();
    }

    private static String firstYear(String value) {
        Matcher matcher = YEAR.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group() : "";
    }

    private static String normalizeTitle(String value) {
        return TITLE_NOISE.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT).trim()).replaceAll("");
    }

    private static String joinStrings(@Nullable JsonArray array) {
        List<String> values = new ArrayList<>();
        if (array != null) for (JsonElement element : array) {
            if (element == null || !element.isJsonPrimitive()) continue;
            String value = element.getAsString().trim();
            if (!value.isEmpty()) values.add(value);
        }
        return String.join("、", values);
    }

    public static void fetchDetail(DiscoverMediaKey key, @Nullable String apiKey, DetailListener listener) {
        fetchDetail(key, apiKey, TAG, listener);
    }

    public static void fetchDetail(DiscoverMediaKey key, @Nullable String apiKey, Object tag, DetailListener listener) {
        HttpUrl url = buildTmdbUrl(key.getMediaType() + "/" + key.getId(), apiKey);
        if (url != null) url = url.newBuilder().addQueryParameter("append_to_response", "credits").build();
        if (url == null) {
            post(() -> listener.onError(new IOException("Discover detail url unavailable")));
            return;
        }
        OkHttp.client().newCall(new Request.Builder().url(url).tag(tag).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) post(() -> listener.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Discover detail failed: HTTP " + resp.code());
                    DiscoverDetail detail = parseDetail(key, resp.body().string());
                    if (detail == null) throw new IOException("Discover detail invalid");
                    post(() -> listener.onSuccess(detail));
                } catch (Exception e) {
                    post(() -> listener.onError(e));
                }
            }
        });
    }

    @Nullable
    static DiscoverDetail parseDetail(DiscoverMediaKey key, String body) {
        JsonObject object = parseObject(body);
        if (object == null || getLong(object, "id") <= 0) return null;
        boolean movie = key.isMovie();
        String title = getString(object, movie ? "title" : "name");
        String originalTitle = getString(object, movie ? "original_title" : "original_name");
        String date = getString(object, movie ? "release_date" : "first_air_date");
        String year = date.length() >= 4 ? date.substring(0, 4) : "";
        int runtimeMinutes = runtimeMinutes(object, movie);
        int seasons = movie ? 0 : getInt(object, "number_of_seasons");
        int episodes = movie ? 0 : getInt(object, "number_of_episodes");
        String status = movie ? "" : getString(object, "status");
        String creators = creators(object, movie);
        return new DiscoverDetail(key, title, originalTitle, tmdbBackdropLarge(getString(object, "backdrop_path")),
                tmdbPosterLarge(getString(object, "poster_path")), getString(object, "overview"),
                tmdbRemarks(getDouble(object, "vote_average")), year, joinNames(getArray(object, "genres"), "name"),
                joinNames(getArray(object, "production_countries"), "name"), runtimeMinutes, seasons, episodes,
                status, creators, parseCast(object));
    }

    private static int runtimeMinutes(JsonObject object, boolean movie) {
        int minutes = getInt(object, "runtime");
        if (!movie && minutes <= 0) {
            JsonArray runtimes = getArray(object, "episode_run_time");
            if (runtimes != null && !runtimes.isEmpty()) {
                try { minutes = runtimes.get(0).getAsInt(); } catch (RuntimeException ignored) { }
            }
        }
        return minutes;
    }

    private static String creators(JsonObject object, boolean movie) {
        Set<String> names = new LinkedHashSet<>();
        if (!movie) {
            JsonArray creators = getArray(object, "created_by");
            if (creators != null) for (JsonElement element : creators) {
                String name = getString(getObject(element), "name");
                if (!name.isEmpty()) names.add(name);
            }
        }
        JsonObject credits = getObject(object.get("credits"));
        JsonArray crew = getArray(credits, "crew");
        if (crew != null) for (JsonElement element : crew) {
            JsonObject person = getObject(element);
            String job = getString(person, "job");
            if (!"Director".equals(job) && !"Executive Producer".equals(job)) continue;
            String name = getString(person, "name");
            if (!name.isEmpty()) names.add(name);
            if (names.size() >= 4) break;
        }
        return String.join("、", names);
    }

    private static List<DiscoverCredit> parseCast(JsonObject object) {
        List<DiscoverCredit> result = new ArrayList<>();
        JsonObject credits = getObject(object.get("credits"));
        JsonArray cast = getArray(credits, "cast");
        if (cast == null) return result;
        for (JsonElement element : cast) {
            JsonObject person = getObject(element);
            String name = getString(person, "name");
            if (name.isEmpty()) continue;
            result.add(new DiscoverCredit(name, getString(person, "character"), tmdbProfile(getString(person, "profile_path"))));
            if (result.size() >= 12) break;
        }
        return result;
    }

    private static String joinNames(@Nullable JsonArray array, String field) {
        List<String> values = new ArrayList<>();
        if (array != null) for (JsonElement element : array) {
            String value = getString(getObject(element), field);
            if (!value.isEmpty()) values.add(value);
        }
        return String.join("、", values);
    }

    static String tmdbPosterLarge(String path) {
        return tmdbImage("w500", path);
    }

    static String tmdbBackdropLarge(String path) {
        return tmdbImage("original", path);
    }

    static String tmdbProfile(String path) {
        return tmdbImage("w185", path);
    }

    @Nullable
    private static JsonObject parseObject(@Nullable String body) {
        try {
            return getObject(JsonParser.parseString(body == null ? "" : body));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static JsonObject getObject(@Nullable JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Nullable
    private static JsonObject getObject(@Nullable JsonObject object, String name) {
        return object == null ? null : getObject(object.get(name));
    }

    @Nullable
    private static JsonArray getArray(@Nullable JsonObject object, String name) {
        JsonElement element = object == null ? null : object.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String getString(@Nullable JsonObject object, String name) {
        if (object == null) return "";
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static double getDouble(@Nullable JsonObject object, String name) {
        try {
            return object == null ? 0 : object.get(name).getAsDouble();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int getInt(@Nullable JsonObject object, String name) {
        try {
            return object == null || object.get(name) == null ? 0 : object.get(name).getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long getLong(@Nullable JsonObject object, String name) {
        try {
            return object == null || object.get(name) == null ? 0 : object.get(name).getAsLong();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean getBoolean(@Nullable JsonObject object, String name) {
        try {
            return object != null && object.get(name) != null && object.get(name).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String value(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static void post(Runnable runnable) {
        if (App.get() == null) runnable.run();
        else App.post(runnable);
    }

    private static class CacheEntry {

        private final List<Vod> items;
        private final long time;

        private CacheEntry(List<Vod> items) {
            this.items = new ArrayList<>(items);
            this.time = System.currentTimeMillis();
        }

        private boolean expired() {
            return System.currentTimeMillis() - time > CACHE_TTL;
        }

        private List<Vod> copy() {
            return new ArrayList<>(items);
        }
    }
}
