package com.fongmi.android.tv.player.skip;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.source.EpisodeLabel;
import com.fongmi.android.tv.source.EpisodeLabelParser;
import com.fongmi.android.tv.utils.TmdbEndpoint;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.utils.Trans;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** Runs on a worker; ambiguous titles, seasons, and split episodes leave playback unchanged. */
public final class EpisodeIdentityResolver {

    private static final String NUMBER = "[零〇一二两三四五六七八九十百0-9]{1,6}";
    private static final Pattern SEASON = Pattern.compile("(?i)(?<![a-z0-9])(?:season\\s*|s)([0-9]{1,3})(?![0-9])|第\\s*(" + NUMBER + ")\\s*季");
    private static final Pattern UNSUPPORTED = Pattern.compile("(?i)预告|花絮|合集|全集|trailer|preview|teaser|\\bpart\\s*[0-9]|第\\s*" + NUMBER + "\\s*部分|[上中下](?:部分|半|集|部|篇){0,2}[\\s\\]）)]*$|(?:[零〇一二两三四五六七八九十百0-9]|[集话])\\s*[-~至到+、,/&]\\s*(?:e|ep|第)?\\s*[零〇一二两三四五六七八九十百0-9]|e[0-9]+\\s*e[0-9]+");
    private static final Pattern YEAR = Pattern.compile("(?<![0-9])(?:19|20)[0-9]{2}(?![0-9])");
    private static final Pattern MOVIE = Pattern.compile("(?i)电影|movie|film");
    private static final long CACHE_TTL_MS = TimeUnit.HOURS.toMillis(6);
    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<>(16, 0.75f, true);

    private final String apiKey;
    private final String apiBase;
    private final Function<Request, Call> callFactory;
    private final long lookupTimeoutNs;
    private volatile boolean cancelled;
    private long deadlineNs;
    private Call activeCall;

    public EpisodeIdentityResolver(@Nullable String apiKey) {
        this(apiKey, TmdbEndpoint.getApiBase(), TmdbNetwork::newCall);
    }

    EpisodeIdentityResolver(@Nullable String apiKey, String apiBase, Function<Request, Call> callFactory) {
        this(apiKey, apiBase, callFactory, 8_000);
    }

    EpisodeIdentityResolver(@Nullable String apiKey, String apiBase, Function<Request, Call> callFactory, long lookupTimeoutMs) {
        this.apiKey = safe(apiKey);
        this.apiBase = apiBase.endsWith("/") ? apiBase : apiBase + "/";
        this.callFactory = callFactory;
        this.lookupTimeoutNs = TimeUnit.MILLISECONDS.toNanos(lookupTimeoutMs);
    }

    @Nullable
    public EpisodeIdentity resolve(String vodId, String title, String year, String typeName, String episodeName) throws IOException {
        return resolve(vodId, title, year, typeName, episodeName, true);
    }

    public static boolean isMovie(String vodId, String typeName) {
        DiscoverMediaKey key = DiscoverMediaKey.parse(vodId);
        return key == null ? MOVIE.matcher(normalize(typeName)).find() : key.isMovie();
    }

    @Nullable
    public EpisodeIdentity resolve(String vodId, String title, String year, String typeName, String episodeName, boolean reliableEpisodeLabel) throws IOException {
        return resolve(vodId, title, year, typeName, episodeName, reliableEpisodeLabel, 0);
    }

    @Nullable
    public EpisodeIdentity resolve(String vodId, String title, String year, String typeName, String episodeName,
                                   boolean reliableEpisodeLabel, int continuousEpisodeCount) throws IOException {
        deadlineNs = System.nanoTime() + lookupTimeoutNs;
        DiscoverMediaKey mediaKey = DiscoverMediaKey.parse(vodId);
        if (cancelled || UNSUPPORTED.matcher(normalize(episodeName)).find()) return null;
        if (isMovie(vodId, typeName))
            return resolveMovie(mediaKey == null ? 0 : mediaKey.getId(), title, year);
        if (!reliableEpisodeLabel) return null;
        ParsedEpisode parsed = parseEpisode(title, episodeName);
        if (parsed == null || cancelled) return null;
        long explicitId = mediaKey == null ? 0 : mediaKey.getId();
        EpisodeIdentity explicit = explicitId > 0 && parsed.season() != null
                ? new EpisodeIdentity(explicitId, parsed.season(), parsed.episode()) : null;
        if (apiKey.isEmpty()) return explicit;
        if (explicitId > 0) {
            try {
                JsonObject detail = get("tv/" + explicitId, null, true);
                return cancelled ? null : identityFromDetail(detail, explicitId, parsed, "", false, continuousEpisodeCount);
            } catch (IOException error) {
                if (cancelled) return null;
                // An explicit canonical TMDB id plus explicit season/episode remains usable offline.
                return explicit;
            }
        }
        String targetYear = firstYear(year);
        if (targetYear.isEmpty()) targetYear = firstYear(title);
        if (targetYear.isEmpty() || parsed.queryTitle().isEmpty()) return null;
        Metadata search = getMetadata("search/tv", parsed.queryTitle(), false);
        if (search.searchTitle() != null) parsed = new ParsedEpisode(search.searchTitle(), parsed.season(), parsed.episode());
        List<Long> candidates = matchingCandidates(search.body(), parsed.queryTitle(), targetYear, parsed.season());
        EpisodeIdentity found = null;
        // Do not make an unbounded sequence of requests for a common title.
        if (candidates.size() > 3) return null;
        for (long id : candidates) {
            if (cancelled) return null;
            EpisodeIdentity candidate = identityFromDetail(get("tv/" + id, null, true), id, parsed, targetYear, true, continuousEpisodeCount);
            if (candidate == null) continue;
            if (found != null && found.tmdbId() != candidate.tmdbId()) return null;
            found = candidate;
        }
        return cancelled ? null : found;
    }

    @Nullable
    private EpisodeIdentity resolveMovie(long explicitId, String title, String year) throws IOException {
        EpisodeIdentity explicit = explicitId > 0 ? EpisodeIdentity.movie(explicitId, "") : null;
        if (apiKey.isEmpty()) return explicit;
        if (explicitId > 0) {
            try {
                return movieFromDetail(get("movie/" + explicitId, null, true), explicitId, "", "", false);
            } catch (IOException error) {
                return cancelled ? null : explicit;
            }
        }
        String targetYear = firstYear(year);
        if (targetYear.isEmpty()) targetYear = firstYear(title);
        String query = normalize(title).replaceAll("[（(](?:19|20)[0-9]{2}[）)]\\s*$", "").trim();
        if (query.isEmpty() || targetYear.isEmpty()) return null;
        Metadata search = getMetadata("search/movie", query, false);
        if (search.searchTitle() != null) query = search.searchTitle();
        List<Long> candidates = matchingMovieCandidates(search.body(), query, targetYear);
        if (candidates.size() != 1 || cancelled) return null;
        long id = candidates.get(0);
        return movieFromDetail(get("movie/" + id, null, true), id, query, targetYear, true);
    }

    @Nullable
    private EpisodeIdentity movieFromDetail(JsonObject detail, long expectedId, String title, String year, boolean verifyTitle) {
        if (cancelled || integer(detail, "id") != expectedId) return null;
        if (verifyTitle && (!year.equals(firstYear(string(detail, "release_date")))
                || !normalizedTitle(title).equals(normalizedTitle(string(detail, "title")))
                && !normalizedTitle(title).equals(normalizedTitle(string(detail, "original_title"))))) return null;
        JsonObject external = object(detail, "external_ids");
        String imdb = external == null ? string(detail, "imdb_id") : string(external, "imdb_id");
        return EpisodeIdentity.movie(expectedId, imdb);
    }

    public synchronized void cancel() {
        cancelled = true;
        if (activeCall != null) activeCall.cancel();
        activeCall = null;
    }

    private JsonObject get(String path, @Nullable String query, boolean externalIds) throws IOException {
        return getMetadata(path, query, externalIds).body();
    }

    private Metadata getMetadata(String path, @Nullable String query, boolean externalIds) throws IOException {
        if (cancelled) throw new IOException("Lookup cancelled");
        HttpUrl url = HttpUrl.parse(apiBase + path);
        if (url == null) throw new IOException("Invalid metadata endpoint");
        HttpUrl.Builder builder = url.newBuilder().addQueryParameter("api_key", apiKey)
                .addQueryParameter("language", "zh-CN");
        if (query != null) builder.addQueryParameter("query", query).addQueryParameter("include_adult", "false");
        if (externalIds) builder.addQueryParameter("append_to_response", path.startsWith("tv/") ? "external_ids,episode_groups" : "external_ids");
        HttpUrl requestUrl = builder.build();
        // Include the route and credential in the private cache key, never in logs.
        String cacheKey = requestUrl.toString();
        synchronized (CACHE) {
            CacheEntry cached = CACHE.get(cacheKey);
            if (cached != null && cached.expiresAt() > System.currentTimeMillis())
                return new Metadata(cached.body().deepCopy(), cached.searchTitle());
            CACHE.remove(cacheKey);
        }
        Call call = callFactory.apply(new Request.Builder().url(requestUrl).build());
        long remainingNs = deadlineNs - System.nanoTime();
        if (remainingNs <= 0) throw new IOException("Metadata lookup timed out");
        call.timeout().timeout(remainingNs, TimeUnit.NANOSECONDS);
        synchronized (this) {
            if (cancelled) {
                call.cancel();
                throw new IOException("Lookup cancelled");
            }
            activeCall = call;
        }
        try (Response response = call.execute()) {
            if (cancelled) throw new IOException("Lookup cancelled");
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Metadata unavailable");
            if (response.body().contentLength() > 512 * 1024) throw new IOException("Metadata too large");
            if (response.body().source().request(512 * 1024 + 1)) throw new IOException("Metadata too large");
            String body = response.body().source().readUtf8();
            JsonElement element;
            try {
                element = JsonParser.parseString(body);
            } catch (RuntimeException error) {
                throw new IOException("Invalid metadata", error);
            }
            if (!element.isJsonObject()) throw new IOException("Invalid metadata");
            JsonObject result = element.getAsJsonObject();
            HttpUrl responseUrl = response.request().url();
            boolean selectedProxy = !"api.themoviedb.org".equals(requestUrl.host())
                    && requestUrl.scheme().equals(responseUrl.scheme()) && requestUrl.host().equals(responseUrl.host())
                    && requestUrl.port() == responseUrl.port();
            String searchTitle = selectedProxy && query != null
                    ? approvedSearchTitle(query, response.header("X-Tmdb-Query"), response.header("X-Title-Normalizer")) : null;
            synchronized (CACHE) {
                CACHE.put(cacheKey, new CacheEntry(result.deepCopy(), searchTitle, System.currentTimeMillis() + CACHE_TTL_MS));
                while (CACHE.size() > 64) CACHE.remove(CACHE.keySet().iterator().next());
            }
            return new Metadata(result, searchTitle);
        } finally {
            synchronized (this) {
                if (activeCall == call) activeCall = null;
            }
        }
    }

    /** A selected proxy may remove release labels; it may not replace or shorten the actual title. */
    @Nullable
    static String approvedSearchTitle(String query, @Nullable String encodedTitle, @Nullable String source) {
        if (encodedTitle == null || encodedTitle.length() > 4096
                || !"rules".equals(source) && !"groq".equals(source) && !"workers-ai".equals(source)) return null;
        String title;
        try {
            title = URLDecoder.decode(encodedTitle, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException | IOException error) {
            return null;
        }
        if (title.isEmpty() || title.length() > 256 || title.indexOf('\uFFFD') >= 0) return null;
        for (int index = 0; index < title.length(); index++) {
            char character = title.charAt(index);
            if (Character.isISOControl(character) || Character.getType(character) == Character.FORMAT) return null;
        }
        title = normalize(title);
        String expected = cleanSearchMetadata(query);
        String output = normalizedTitle(title);
        String cleanedOutput = title.replaceAll("\\s+", " ");
        if (output.isEmpty() || !normalizedTitle(query).contains(output)) return null;
        Matcher numbers = Pattern.compile("[0-9]+").matcher(expected);
        while (numbers.find()) if (!title.contains(numbers.group())) return null;
        if (expected.equalsIgnoreCase(cleanedOutput)) return title;
        if ("rules".equals(source) || output.length() < 2) return null;
        // Only explicit release/source wrappers provide evidence beyond the local metadata rules.
        expected = expected.replaceAll("^\\s*(?:\\[[^\\[\\]\\r\\n]{1,80}\\]|【[^【】\\r\\n]{1,80}】)\\s*", "")
                .replaceAll("\\s*(?:\\[[^\\[\\]\\r\\n]{1,80}\\]|【[^【】\\r\\n]{1,80}】)\\s*$", "")
                .replaceAll("^\\S{1,40}(?:影视|字幕组|资源)\\s+", "")
                .replaceAll("\\s+(?:在线观看|在线播放|下载)\\s*$", "").trim();
        return expected.equalsIgnoreCase(cleanedOutput) ? title : null;
    }

    private static String cleanSearchMetadata(String query) {
        String value = normalize(query)
                .replaceAll("[\\[(【]\\s*(?:19|20)[0-9]{2}\\s*[\\])】]", " ");
        value = SEASON.matcher(value).replaceAll(" ")
                .replaceAll("(?:更新(?:至)?|更至)\\s*(?:第\\s*)?" + NUMBER + "\\s*[集话期]?", " ")
                .replaceAll("第\\s*" + NUMBER + "\\s*[集话期]", " ")
                .replaceAll("(?i)(?<![a-z0-9])(?:s[0-9]{1,3}(?:[ ._-]*e[0-9]{1,4})?|(?:episode|ep|e)\\s*[0-9]{1,4})(?![a-z0-9])", " ")
                .replaceAll("(?i)(?<![a-z0-9])(?:[0-9]{3,4}[pi]|[48]k|uhd|fhd|hd|web[- .]?dl|webrip|blu[- .]?ray)(?![a-z0-9])", " ")
                .replaceAll("(?:中文字幕|中文配音|国语配音|粤语配音|国语版|粤语版|原声版|高清版)(?=$|[\\s\\])】])", " ")
                .replaceAll("(^|[\\s\\[(【])(?:国语|粤语|粤配|中配|原声|高清|更新|连载中|已完结|完结|大结局|全集|最新)(?=$|[\\s\\])】])", "$1")
                .replaceAll("[\\[(【]\\s*[\\])】]", " ").replaceAll("\\s+", " ").trim();
        return value.isEmpty() ? normalize(query) : value;
    }

    @Nullable
    static ParsedEpisode parseEpisode(String title, String episodeName) {
        String name = normalize(episodeName);
        String showTitle = normalize(title);
        if (name.isEmpty() || UNSUPPORTED.matcher(name).find()) return null;
        EpisodeLabel label = EpisodeLabelParser.parse(name);
        EpisodeLabel show = EpisodeLabelParser.parse(showTitle);
        // A source's issue number or air date does not establish a TMDB episode position.
        if (label.isVariety()) return null;
        if (label.isAmbiguous() || show.isAmbiguous()) return null;
        Integer season = label.season() == null ? show.season() : label.season();
        if (show.season() != null && label.season() != null && !show.season().equals(label.season())) return null;
        // A special can be identified by an explicit season zero in the show title.
        boolean titledSpecial = label.isSpecial() && season != null && season == 0
                && label.isSingleEpisode() && label.part().isEmpty() && !label.isPreview();
        if (!label.isApiSafe() && !titledSpecial) return null;
        if (label.number() == null || label.number() <= 0 || label.number() > 9999
                || season != null && season > 999) return null;
        String queryTitle = SEASON.matcher(showTitle).replaceAll("").replaceAll("[（(](?:19|20)[0-9]{2}[）)]\\s*$", "").trim();
        return new ParsedEpisode(queryTitle, season, label.number());
    }

    static List<Long> matchingCandidates(JsonObject search, String title, String year, @Nullable Integer season) {
        List<Long> ids = new ArrayList<>();
        JsonArray results = array(search, "results");
        if (results == null) return ids;
        String normalizedTitle = normalizedTitle(title);
        for (JsonElement element : results) {
            if (!element.isJsonObject()) continue;
            JsonObject result = element.getAsJsonObject();
            if (!normalizedTitle.equals(normalizedTitle(string(result, "name")))
                    && !normalizedTitle.equals(normalizedTitle(string(result, "original_name")))) continue;
            if (season == null || season <= 1) {
                if (!year.equals(firstYear(string(result, "first_air_date")))) continue;
            }
            long id = integer(result, "id");
            if (id > 0 && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    static List<Long> matchingMovieCandidates(JsonObject search, String title, String year) {
        List<Long> ids = new ArrayList<>();
        JsonArray results = array(search, "results");
        if (results == null) return ids;
        String normalizedTitle = normalizedTitle(title);
        for (JsonElement element : results) {
            if (!element.isJsonObject()) continue;
            JsonObject result = element.getAsJsonObject();
            if (!year.equals(firstYear(string(result, "release_date")))
                    || !normalizedTitle.equals(normalizedTitle(string(result, "title")))
                    && !normalizedTitle.equals(normalizedTitle(string(result, "original_title")))) continue;
            long id = integer(result, "id");
            if (id > 0 && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    @Nullable
    static EpisodeIdentity identityFromDetail(JsonObject detail, long expectedId, ParsedEpisode parsed, String year, boolean verifyTitle) {
        if (integer(detail, "id") != expectedId) return null;
        if (verifyTitle && !normalizedTitle(parsed.queryTitle()).equals(normalizedTitle(string(detail, "name")))
                && !normalizedTitle(parsed.queryTitle()).equals(normalizedTitle(string(detail, "original_name")))) return null;
        JsonArray seasons = array(detail, "seasons");
        if (seasons == null) return null;
        JsonObject selected = null;
        for (JsonElement element : seasons) {
            if (!element.isJsonObject()) continue;
            JsonObject candidate = element.getAsJsonObject();
            long season = integer(candidate, "season_number");
            long count = integer(candidate, "episode_count");
            if (count <= 0 || season < 0) continue;
            if (parsed.season() == null) {
                if (season == 0) continue;
                // Multiple normal seasons require an explicit season; never choose season one by default.
                if (selected != null) return null;
                selected = candidate;
            } else if (season == parsed.season()) {
                if (selected != null) return null;
                selected = candidate;
            }
        }
        if (selected == null || parsed.episode() > integer(selected, "episode_count")) return null;
        if (!year.isEmpty() && !year.equals(firstYear(string(selected, "air_date")))
                && !year.equals(firstYear(string(detail, "first_air_date")))) return null;
        JsonObject external = object(detail, "external_ids");
        String imdb = external == null ? "" : string(external, "imdb_id");
        return new EpisodeIdentity(expectedId, imdb, (int) integer(selected, "season_number"), parsed.episode());
    }

    @Nullable
    private EpisodeIdentity identityFromDetail(JsonObject detail, long expectedId, ParsedEpisode parsed, String year,
                                               boolean verifyTitle, int continuousEpisodeCount) throws IOException {
        EpisodeIdentity direct = identityFromDetail(detail, expectedId, parsed, year, verifyTitle);
        if (direct != null || parsed.season() != null || continuousEpisodeCount <= 0
                || continuousEpisodeCount > 9999 || parsed.episode() > continuousEpisodeCount
                || integer(detail, "id") != expectedId) return direct;
        if (verifyTitle && !normalizedTitle(parsed.queryTitle()).equals(normalizedTitle(string(detail, "name")))
                && !normalizedTitle(parsed.queryTitle()).equals(normalizedTitle(string(detail, "original_name")))) return null;
        if (!year.isEmpty() && !year.equals(firstYear(string(detail, "first_air_date")))) return null;
        JsonArray seasons = array(detail, "seasons");
        if (seasons == null) return null;
        Map<Integer, Integer> seasonCounts = new HashMap<>();
        int maximumCount = 0;
        for (JsonElement element : seasons) {
            if (!element.isJsonObject()) return null;
            JsonObject season = element.getAsJsonObject();
            long number = integer(season, "season_number");
            long count = integer(season, "episode_count");
            if (number == 0) continue;
            if (number < 0 || number > 999 || count < 0 || count > 9999) return null;
            if (count == 0) continue;
            if (seasonCounts.put((int) number, (int) count) != null) return null;
            maximumCount = Math.max(maximumCount, (int) count);
        }
        // A playlist longer than every season establishes continuous rather than season-local numbering.
        if (seasonCounts.size() < 2 || continuousEpisodeCount <= maximumCount) return null;
        JsonObject episodeGroups = object(detail, "episode_groups");
        JsonArray groups = episodeGroups == null ? null : array(episodeGroups, "results");
        if (groups == null) return null;
        JsonObject absolute = null;
        for (JsonElement element : groups) {
            if (!element.isJsonObject()) return null;
            JsonObject group = element.getAsJsonObject();
            if (integer(group, "type") != 2) continue;
            if (absolute != null) return null;
            absolute = group;
        }
        if (absolute == null) return null;
        String groupId = string(absolute, "id");
        long groupCount = integer(absolute, "episode_count");
        if (!groupId.matches("[A-Za-z0-9_-]{1,128}") || groupCount < continuousEpisodeCount || groupCount > 9999) return null;
        JsonObject mapping = get("tv/episode_group/" + groupId, null, false);
        ParsedEpisode mapped = absoluteEpisode(mapping, groupId, (int) groupCount, parsed, seasonCounts);
        return cancelled || mapped == null ? null : identityFromDetail(detail, expectedId, mapped, year, verifyTitle);
    }

    @Nullable
    private static ParsedEpisode absoluteEpisode(JsonObject mapping, String groupId, int count, ParsedEpisode parsed,
                                                  Map<Integer, Integer> seasonCounts) {
        if (!groupId.equals(string(mapping, "id")) || integer(mapping, "type") != 2
                || integer(mapping, "episode_count") != count) return null;
        JsonArray groups = array(mapping, "groups");
        if (groups == null) return null;
        boolean[] orders = new boolean[count];
        Set<String> canonical = new HashSet<>();
        ParsedEpisode selected = null;
        int seen = 0;
        for (JsonElement group : groups) {
            if (!group.isJsonObject()) return null;
            JsonArray episodes = array(group.getAsJsonObject(), "episodes");
            if (episodes == null) return null;
            for (JsonElement element : episodes) {
                if (!element.isJsonObject()) return null;
                JsonObject episode = element.getAsJsonObject();
                long order = integer(episode, "order");
                long season = integer(episode, "season_number");
                long number = integer(episode, "episode_number");
                Integer seasonCount = season > 0 && season <= 999 ? seasonCounts.get((int) season) : null;
                if (order < 0 || order >= count || orders[(int) order] || seasonCount == null
                        || number <= 0 || number > seasonCount || !canonical.add(season + ":" + number)) return null;
                orders[(int) order] = true;
                seen++;
                if (order == parsed.episode() - 1) selected = new ParsedEpisode(parsed.queryTitle(), (int) season, (int) number);
            }
        }
        // Require the complete group to be contiguous so missing entries cannot shift later episodes.
        return seen == count ? selected : null;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(Trans.t2s(false, safe(value)), Normalizer.Form.NFKC).trim();
    }

    private static String normalizedTitle(String value) {
        return normalize(value).replaceAll("[^\\p{L}\\p{Nd}]", "").toLowerCase(Locale.ROOT);
    }

    private static String firstYear(String value) {
        Matcher matcher = YEAR.matcher(safe(value));
        return matcher.find() ? matcher.group() : "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static long integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (RuntimeException error) {
            return -1;
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    record ParsedEpisode(String queryTitle, Integer season, int episode) { }
    private record Metadata(JsonObject body, @Nullable String searchTitle) { }
    private record CacheEntry(JsonObject body, @Nullable String searchTitle, long expiresAt) { }
}
