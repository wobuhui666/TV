package com.fongmi.android.tv.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public final class MediaRatingHelper {

    private static final String TAG = "MediaRatingHelper";
    private static final String DOUBAN_SUGGEST = "https://movie.douban.com/j/subject_suggest";
    private static final String DOUBAN_ABSTRACT = "https://movie.douban.com/j/subject_abstract";
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern TITLE_NOISE = Pattern.compile("[\\s\\p{P}]+");

    private MediaRatingHelper() {
    }

    public static void findTmdbRating(@Nullable String apiKey, @Nullable String title, @Nullable String year, @Nullable String typeName, @NonNull RatingCallback callback) {
        String safeApiKey = normalize(apiKey);
        String safeTitle = normalize(title);
        String safeYear = normalizeYear(year);
        if (isEmpty(safeTitle)) {
            post(callback::onNotFound);
            return;
        }
        MediaType firstType = guessMediaType(typeName);
        MediaType secondType = firstType == MediaType.TV ? MediaType.MOVIE : MediaType.TV;
        searchTmdbRating(safeApiKey, firstType, safeTitle, safeYear, new TmdbSearchCallback() {
            @Override
            public void onFound(@Nullable Rating rating) {
                if (rating != null) post(() -> callback.onFound(rating));
                else searchAlternativeTmdbRating(safeApiKey, secondType, safeTitle, safeYear, callback);
            }

            @Override
            public void onError(@NonNull Exception error) {
                post(() -> callback.onError(error));
            }
        });
    }

    public static void findDoubanRating(@Nullable String title, @Nullable String year, @Nullable String typeName, @NonNull RatingCallback callback) {
        String safeTitle = normalize(title);
        String safeYear = normalizeYear(year);
        if (isEmpty(safeTitle)) {
            post(callback::onNotFound);
            return;
        }
        HttpUrl url = buildDoubanSuggestUrl(safeTitle);
        if (url == null) {
            post(callback::onNotFound);
            return;
        }
        newDoubanCall(url).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) post(() -> callback.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Douban suggest failed: HTTP " + resp.code());
                    String subjectId = selectDoubanSubjectId(resp.body().string(), safeTitle, safeYear, typeName);
                    if (isEmpty(subjectId)) post(callback::onNotFound);
                    else fetchDoubanRating(subjectId, callback);
                } catch (Exception e) {
                    post(() -> callback.onError(e));
                }
            }
        });
    }

    public static void cancel() {
        OkHttp.cancel(TAG);
    }

    private static void searchAlternativeTmdbRating(String apiKey, MediaType type, String title, String year, RatingCallback callback) {
        searchTmdbRating(apiKey, type, title, year, new TmdbSearchCallback() {
            @Override
            public void onFound(@Nullable Rating rating) {
                if (rating == null) post(callback::onNotFound);
                else post(() -> callback.onFound(rating));
            }

            @Override
            public void onError(@NonNull Exception error) {
                post(() -> callback.onError(error));
            }
        });
    }

    private static void searchTmdbRating(String apiKey, MediaType type, String title, String year, TmdbSearchCallback callback) {
        HttpUrl url = buildTmdbSearchUrl(apiKey, type, title, year);
        if (url == null) {
            callback.onFound(null);
            return;
        }
        OkHttp.newCall(url.toString(), TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) callback.onError(e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("TMDB rating failed: HTTP " + resp.code());
                    callback.onFound(parseTmdbRating(resp.body().string()));
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }

    private static void fetchDoubanRating(String subjectId, RatingCallback callback) {
        HttpUrl url = buildDoubanAbstractUrl(subjectId);
        if (url == null) {
            post(callback::onNotFound);
            return;
        }
        newDoubanCall(url).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!call.isCanceled()) post(() -> callback.onError(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response resp = response) {
                    if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Douban rating failed: HTTP " + resp.code());
                    Rating rating = parseDoubanRating(resp.body().string());
                    if (rating == null) post(callback::onNotFound);
                    else post(() -> callback.onFound(rating));
                } catch (Exception e) {
                    post(() -> callback.onError(e));
                }
            }
        });
    }

    @Nullable
    private static HttpUrl buildTmdbSearchUrl(String apiKey, MediaType type, String title, String year) {
        HttpUrl url = HttpUrl.parse(TmdbEndpoint.getApiBase() + type.searchPath);
        if (url == null) return null;
        HttpUrl.Builder builder = url.newBuilder()
                .addQueryParameter("query", title)
                .addQueryParameter("language", "zh-CN")
                .addQueryParameter("include_adult", "false");
        if (!isEmpty(apiKey)) builder.addQueryParameter("api_key", apiKey);
        if (!isEmpty(year)) builder.addQueryParameter(type.yearParam, year);
        return builder.build();
    }

    @Nullable
    private static HttpUrl buildDoubanSuggestUrl(String title) {
        HttpUrl url = HttpUrl.parse(DOUBAN_SUGGEST);
        return url == null ? null : url.newBuilder().addQueryParameter("q", title).build();
    }

    @Nullable
    private static HttpUrl buildDoubanAbstractUrl(String subjectId) {
        HttpUrl url = HttpUrl.parse(DOUBAN_ABSTRACT);
        return url == null ? null : url.newBuilder().addQueryParameter("subject_id", subjectId).build();
    }

    private static Call newDoubanCall(HttpUrl url) {
        Request.Builder builder = new Request.Builder().url(url).tag(TAG);
        for (Map.Entry<String, String> entry : doubanHeaders().entrySet()) builder.header(entry.getKey(), entry.getValue());
        return OkHttp.client().newCall(builder.build());
    }

    @Nullable
    static Rating parseTmdbRating(String body) {
        JsonArray results = getArray(parseObject(body), "results");
        if (results == null || results.isEmpty()) return null;
        JsonObject result = getObject(results.get(0));
        if (result == null || getInt(result, "id") <= 0) return null;
        return Rating.from(getDouble(result, "vote_average"));
    }

    @Nullable
    static String selectDoubanSubjectId(String body, @Nullable String title, @Nullable String year, @Nullable String typeName) {
        JsonArray items = parseArray(body);
        if (items == null) return null;
        String targetTitle = normalizeForMatch(title);
        String targetYear = normalizeYear(year);
        boolean targetTv = guessMediaType(typeName) == MediaType.TV;
        DoubanCandidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = getObject(items.get(i));
            DoubanCandidate candidate = DoubanCandidate.from(item, i);
            if (candidate == null) continue;
            boolean titleExact = candidate.matchesTitle(targetTitle);
            boolean yearMatch = !isEmpty(targetYear) && targetYear.equals(candidate.year);
            if (!isEmpty(targetYear) && !yearMatch && !titleExact) continue;
            int score = candidate.score(targetTitle, targetYear, targetTv);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best == null ? null : best.id;
    }

    @Nullable
    static Rating parseDoubanRating(String body) {
        JsonObject subject = getObject(parseObject(body), "subject");
        if (subject == null) return null;
        return Rating.from(parseDouble(getString(subject, "rate")));
    }

    static String formatRating(double rating) {
        return String.format(Locale.US, "%.1f", rating);
    }

    private static MediaType guessMediaType(@Nullable String typeName) {
        String lower = normalize(typeName).toLowerCase(Locale.ROOT);
        if (lower.contains("剧") || lower.contains("电视") || lower.contains("动漫") || lower.contains("动画") || lower.contains("综艺") || lower.contains("纪录") || lower.contains("tv")) {
            return MediaType.TV;
        }
        return MediaType.MOVIE;
    }

    private static Map<String, String> doubanHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0");
        headers.put("Referer", "https://movie.douban.com/");
        headers.put("Accept", "application/json,text/plain,*/*");
        return headers;
    }

    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeYear(@Nullable String year) {
        Matcher matcher = YEAR.matcher(normalize(year));
        return matcher.find() ? matcher.group() : "";
    }

    private static String normalizeForMatch(@Nullable String value) {
        String text = normalize(value).toLowerCase(Locale.ROOT);
        return TITLE_NOISE.matcher(text).replaceAll("");
    }

    private static double parseDouble(@Nullable String value) {
        try {
            return isEmpty(value) ? 0 : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    private static JsonObject parseObject(@Nullable String body) {
        try {
            JsonElement element = JsonParser.parseString(normalize(body));
            return getObject(element);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static JsonArray parseArray(@Nullable String body) {
        try {
            JsonElement element = JsonParser.parseString(normalize(body));
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
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
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static int getInt(@Nullable JsonObject object, String name) {
        try {
            return object == null ? 0 : object.get(name).getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double getDouble(@Nullable JsonObject object, String name) {
        try {
            return object == null ? 0 : object.get(name).getAsDouble();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void post(Runnable runnable) {
        if (App.get() == null) runnable.run();
        else App.post(runnable);
    }

    public interface RatingCallback {

        void onFound(@NonNull Rating rating);

        void onNotFound();

        void onError(@NonNull Exception error);
    }

    public static final class Rating {

        private final double value;
        private final String text;

        private Rating(double value) {
            this.value = value;
            this.text = formatRating(value);
        }

        @Nullable
        private static Rating from(double value) {
            return value > 0 ? new Rating(value) : null;
        }

        public double getValue() {
            return value;
        }

        public String getText() {
            return text;
        }
    }

    private interface TmdbSearchCallback {

        void onFound(@Nullable Rating rating);

        void onError(@NonNull Exception error);
    }

    private static class DoubanCandidate {

        private final String id;
        private final String title;
        private final String subTitle;
        private final String year;
        private final boolean tv;
        private final int index;

        private DoubanCandidate(String id, String title, String subTitle, String year, boolean tv, int index) {
            this.id = id;
            this.title = title;
            this.subTitle = subTitle;
            this.year = year;
            this.tv = tv;
            this.index = index;
        }

        @Nullable
        private static DoubanCandidate from(@Nullable JsonObject item, int index) {
            if (item == null) return null;
            String id = normalize(getString(item, "id"));
            if (isEmpty(id)) return null;
            String title = normalizeForMatch(getString(item, "title"));
            String subTitle = normalizeForMatch(getString(item, "sub_title"));
            String year = normalizeYear(getString(item, "year"));
            boolean tv = !isEmpty(getString(item, "episode")) || "tv".equalsIgnoreCase(getString(item, "type"));
            return new DoubanCandidate(id, title, subTitle, year, tv, index);
        }

        private boolean matchesTitle(String targetTitle) {
            return !isEmpty(targetTitle) && (targetTitle.equals(title) || targetTitle.equals(subTitle));
        }

        private int score(String targetTitle, String targetYear, boolean targetTv) {
            int score = -index;
            if (matchesTitle(targetTitle)) score += 100;
            if (!isEmpty(targetYear) && targetYear.equals(year)) score += 40;
            if (targetTv == tv) score += 10;
            if (!isEmpty(targetTitle) && (title.startsWith(targetTitle) || subTitle.startsWith(targetTitle))) score += 5;
            return score;
        }
    }

    private enum MediaType {
        MOVIE("search/movie", "primary_release_year"),
        TV("search/tv", "first_air_date_year");

        private final String searchPath;
        private final String yearParam;

        MediaType(String searchPath, String yearParam) {
            this.searchPath = searchPath;
            this.yearParam = yearParam;
        }
    }
}
