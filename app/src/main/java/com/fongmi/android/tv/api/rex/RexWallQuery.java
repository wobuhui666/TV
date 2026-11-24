package com.fongmi.android.tv.api.rex;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Full-library wall queries are independent of the smaller, recent home previews. */
public final class RexWallQuery {

    private static final String VARIETY_GENRES = "10764|10767";

    private RexWallQuery() {}

    /** Expand domestic home previews and turn the variety category into a switchable preset. */
    public static RexTmdbQuery.Query browse(RexTmdbQuery.Query original) {
        Map<String, String> params = new LinkedHashMap<>(original.params);
        if (original.path.startsWith("discover/") && "CN".equals(params.get("with_origin_country"))) {
            params.remove("primary_release_date.gte");
            params.remove("primary_release_date.lte");
            params.remove("air_date.gte");
            params.remove("air_date.lte");
        }
        if (RexTmdbQuery.TV.equals(original.fallbackMediaType) && VARIETY_GENRES.equals(params.get("with_genres"))) {
            params.remove("with_genres");
        }
        if (RexTmdbQuery.TV.equals(original.fallbackMediaType) && params.getOrDefault("with_type", "").matches("[0-6]")) {
            params.remove("with_type");
        }
        return params.equals(original.params) ? original
                : new RexTmdbQuery.Query(original.path, original.fallbackMediaType, params);
    }

    public static RexTmdbQuery.Query apply(RexTmdbQuery.Query original, String mediaType,
                                           String genre, String country, String dateStart,
                                           String dateEnd, String sort) {
        return apply(original, mediaType, genre, country, dateStart, dateEnd, sort, "");
    }

    public static RexTmdbQuery.Query apply(RexTmdbQuery.Query original, String mediaType,
                                           String genre, String country, String dateStart,
                                           String dateEnd, String sort, String tvType) {
        boolean trending = original.path.startsWith("trending/");
        boolean filtered = !genre.isEmpty() || !country.isEmpty() || !dateStart.isEmpty()
                || !dateEnd.isEmpty() || !sort.isEmpty() || !tvType.isEmpty();
        if (trending && !filtered) {
            if (mediaType.isEmpty()) return original;
            String window = original.path.substring(original.path.lastIndexOf('/') + 1);
            return new RexTmdbQuery.Query("trending/" + mediaType + "/" + window, mediaType,
                    new LinkedHashMap<>(original.params));
        }
        String type = RexTmdbQuery.TV.equals(trending ? mediaType : original.fallbackMediaType)
                || trending && !tvType.isEmpty() ? RexTmdbQuery.TV : RexTmdbQuery.MOVIE;
        if (!trending && genre.isEmpty() && dateStart.isEmpty() && dateEnd.isEmpty() && sort.isEmpty()
                && tvType.isEmpty() && country.equals(original.params.getOrDefault("with_origin_country", ""))) return original;
        Map<String, String> params = new LinkedHashMap<>(original.params);
        if (!genre.isEmpty()) {
            String fixed = params.getOrDefault("with_genres", "");
            params.put("with_genres", fixed.isEmpty() || fixed.equals(genre) ? genre : fixed + "," + genre);
        }
        if (country.isEmpty()) params.remove("with_origin_country");
        else params.put("with_origin_country", country);
        if (RexTmdbQuery.TV.equals(type)) {
            if ("variety".equals(tvType)) {
                // TMDB accepts a single with_type; OR belongs in the genre expression.
                params.put("with_genres", VARIETY_GENRES);
                params.remove("with_type");
                removeVarietyExclusions(params);
            } else if (tvType.matches("[0-6]")) {
                params.put("with_type", tvType);
                if (VARIETY_GENRES.equals(params.get("with_genres"))) params.remove("with_genres");
                if ("3".equals(tvType) || "5".equals(tvType)) removeVarietyExclusions(params);
            }
        }
        String dateField = RexTmdbQuery.TV.equals(type) ? "first_air_date" : "primary_release_date";
        if (!dateStart.isEmpty() || !dateEnd.isEmpty()) {
            params.remove(dateField + ".gte");
            params.remove(dateField + ".lte");
            if (!dateStart.isEmpty()) params.put(dateField + ".gte", dateStart);
            if (!dateEnd.isEmpty()) params.put(dateField + ".lte", dateEnd);
        }
        boolean topRated = original.path.endsWith("/top_rated");
        String effectiveSort = sort.isEmpty() ? topRated ? "rating" : "popular" : sort;
        String originalSort = params.get("sort_by");
        params.put("sort_by", sort.isEmpty() && originalSort != null ? originalSort : switch (effectiveSort) {
            case "rating" -> "vote_average.desc";
            case "latest" -> dateField + ".desc";
            default -> "popularity.desc";
        });
        if (topRated || "rating".equals(effectiveSort)) params.put("vote_count.gte", "100");
        if (original.path.equals("movie/now_playing")) {
            LocalDate today = LocalDate.now();
            params.put("with_release_type", "2|3");
            params.put("release_date.gte", today.minusDays(42).toString());
            params.put("release_date.lte", today.toString());
        } else if (original.path.equals("tv/on_the_air")) {
            LocalDate today = LocalDate.now();
            params.put("air_date.gte", today.minusDays(7).toString());
            params.put("air_date.lte", today.plusDays(7).toString());
        }
        return new RexTmdbQuery.Query("discover/" + type, type, params);
    }

    private static void removeVarietyExclusions(Map<String, String> params) {
        String excluded = params.getOrDefault("without_genres", "");
        if (excluded.isEmpty()) return;
        List<String> remaining = new ArrayList<>();
        for (String value : excluded.split(",")) if (!"10764".equals(value) && !"10767".equals(value)) remaining.add(value);
        if (remaining.isEmpty()) params.remove("without_genres");
        else params.put("without_genres", String.join(",", remaining));
    }
}
