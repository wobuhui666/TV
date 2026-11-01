package com.fongmi.android.tv.api.rex;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.bean.DiscoverFacet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TMDB 查询参数构造（移植 rex-tmdb.js 的 categories / networks / companies / applySortBy 规则）。
 * 纯函数、无网络；实际请求由 {@link RexTmdbClient} 走应用现有 TMDB 反代发出。
 */
public final class RexTmdbQuery {

    public static final String MOVIE = "movie";
    public static final String TV = "tv";

    /** 科幻在剧集侧合并为 10765（Sci-Fi &amp; Fantasy），与 rex 一致。 */
    public static final String TV_SCI_FI_FANTASY = "10765";

    /** rex：动作 / 惊悚只有电影维度。 */
    private static final String[] ONLY_MOVIE_GENRES = {"28", "53"};
    /** rex：儿童 / 真人秀 / 肥皂剧只有剧集维度。 */
    private static final String[] ONLY_TV_GENRES = {"10762", "10764", "10766"};

    /** 电影没有「播出平台」维度，按 rex 的映射表改用观看渠道（固定 US 区）。 */
    public static final Map<String, String> NETWORK_MOVIE_PROVIDERS = Map.of(
            "213", "8",           // Netflix
            "2739", "337",        // Disney+
            "2552", "350",        // Apple TV+
            "3186", "1899",       // HBO Max
            "453", "15",          // Hulu
            "1024", "9",          // Prime Video
            "4330", "2303|2616"   // Paramount+（Premium / Essential 两档）
    );

    private RexTmdbQuery() {
    }

    /** 一次查询：TMDB 路径 + 解析用兜底类型 + 附加参数（不含 api_key 与 language）。 */
    public static final class Query {

        public final String path;
        public final String fallbackMediaType;
        public final Map<String, String> params;

        Query(String path, String fallbackMediaType, Map<String, String> params) {
            this.path = path;
            this.fallbackMediaType = fallbackMediaType;
            this.params = params;
        }
    }

    /** rex categories：科幻 + 剧集 → 10765。 */
    public static String resolveGenreId(@Nullable String genreId, String mediaType) {
        if (genreId == null || genreId.isEmpty()) return "";
        if ("878".equals(genreId) && TV.equals(mediaType)) return TV_SCI_FI_FANTASY;
        return genreId;
    }

    /** rex categories：电影独有 / 剧集独有的流派会把类型收敛到对应维度。 */
    public static String resolveGenreType(@Nullable String genreId, String mediaType) {
        String type = TV.equals(mediaType) ? TV : MOVIE;
        if (genreId == null || genreId.isEmpty()) return type;
        if (contains(ONLY_MOVIE_GENRES, genreId)) return MOVIE;
        if (contains(ONLY_TV_GENRES, genreId)) return TV;
        return type;
    }

    /** rex applySortBy：空或 default 哨兵不发；release_date.* 按端点换成对应日期字段。 */
    public static void applySortBy(Map<String, String> params, String mediaType) {
        String sort = params.get("sort_by");
        if (sort == null || sort.isEmpty() || "default".equals(sort)) {
            params.remove("sort_by");
            return;
        }
        String dateField = MOVIE.equals(mediaType) ? "primary_release_date" : "first_air_date";
        if ("release_date.desc".equals(sort)) params.put("sort_by", dateField + ".desc");
        else if ("release_date.asc".equals(sort)) params.put("sort_by", dateField + ".asc");
    }

    /** rex categories：流派 discover。 */
    public static Query discover(@Nullable String genreId, String mediaType, @Nullable String sortBy) {
        String type = resolveGenreType(genreId, mediaType);
        Map<String, String> params = new LinkedHashMap<>();
        String resolved = resolveGenreId(genreId, type);
        if (!resolved.isEmpty()) params.put("with_genres", resolved);
        if (sortBy != null && !sortBy.isEmpty()) params.put("sort_by", sortBy);
        applySortBy(params, type);
        return new Query("discover/" + type, type, params);
    }

    /** rex networks：电影走 with_watch_providers + watch_region=US，其余按剧集 with_networks。 */
    public static Query networks(@Nullable String networkId, String mediaType, @Nullable String sortBy) {
        String provider = networkId == null ? null : NETWORK_MOVIE_PROVIDERS.get(networkId);
        Map<String, String> params = new LinkedHashMap<>();
        if (MOVIE.equals(mediaType) && provider != null) {
            params.put("with_watch_providers", provider);
            // TMDB 要求 with_watch_providers 搭配 watch_region，否则筛选被忽略
            params.put("watch_region", "US");
            if (sortBy != null && !sortBy.isEmpty()) params.put("sort_by", sortBy);
            applySortBy(params, MOVIE);
            return new Query("discover/movie", MOVIE, params);
        }
        if (networkId != null && !networkId.isEmpty()) params.put("with_networks", networkId);
        if (sortBy != null && !sortBy.isEmpty()) params.put("sort_by", sortBy);
        applySortBy(params, TV);
        return new Query("discover/tv", TV, params);
    }

    /** rex companies：固定电影 discover。 */
    public static Query companies(@Nullable String companyId, @Nullable String sortBy) {
        Map<String, String> params = new LinkedHashMap<>();
        if (companyId != null && !companyId.isEmpty()) params.put("with_companies", companyId);
        if (sortBy != null && !sortBy.isEmpty()) params.put("sort_by", sortBy);
        applySortBy(params, MOVIE);
        return new Query("discover/movie", MOVIE, params);
    }

    /** trending/all/{day|week}（条目自带 media_type）。 */
    public static Query trending(String window) {
        return new Query("trending/all/" + ("week".equals(window) ? "week" : "day"), "", new LinkedHashMap<>());
    }

    public static Query popular(String mediaType) {
        String type = TV.equals(mediaType) ? TV : MOVIE;
        return new Query(type + "/popular", type, new LinkedHashMap<>());
    }

    public static Query topRated(String mediaType) {
        String type = TV.equals(mediaType) ? TV : MOVIE;
        return new Query(type + "/top_rated", type, new LinkedHashMap<>());
    }

    public static Query nowPlaying() {
        return new Query("movie/now_playing", MOVIE, new LinkedHashMap<>());
    }

    public static Query onTheAir() {
        return new Query("tv/on_the_air", TV, new LinkedHashMap<>());
    }

    /** TMDB 片单 API（rex 的 HTML 抓取降级为后备，不再使用）。 */
    public static Query list(String listId) {
        return new Query("list/" + listId, "", new LinkedHashMap<>());
    }

    public static Query forFacet(DiscoverFacet facet) {
        return switch (facet.getKind() == null ? "" : facet.getKind()) {
            case DiscoverFacet.TOP_MOVIE -> topRated(MOVIE);
            case DiscoverFacet.TOP_TV -> topRated(TV);
            case DiscoverFacet.NOW_PLAYING -> nowPlaying();
            default -> {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("sort_by", "popularity.desc");
                if (DiscoverFacet.GENRE.equals(facet.getKind())) params.put("with_genres", facet.getId());
                if (DiscoverFacet.COMPANY.equals(facet.getKind())) params.put("with_companies", facet.getId());
                if (DiscoverFacet.PROVIDER.equals(facet.getKind())) {
                    params.put("with_watch_providers", facet.getId());
                    params.put("watch_region", "CN");
                }
                yield new Query("discover/movie", MOVIE, params);
            }
        };
    }

    private static boolean contains(String[] values, String value) {
        for (String item : values) if (item.equals(value)) return true;
        return false;
    }
}
