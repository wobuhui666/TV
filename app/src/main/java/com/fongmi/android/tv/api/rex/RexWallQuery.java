package com.fongmi.android.tv.api.rex;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Applies supported wall filters while retaining the original category's constraints. */
public final class RexWallQuery {

    private RexWallQuery() {}

    public static RexTmdbQuery.Query apply(RexTmdbQuery.Query original, String mediaType,
                                           String genre, String country, String dateStart,
                                           String dateEnd, String sort) {
        if (original.path.startsWith("trending/")) {
            if (mediaType.isEmpty()) return original;
            String window = original.path.substring(original.path.lastIndexOf('/') + 1);
            return new RexTmdbQuery.Query("trending/" + mediaType + "/" + window, mediaType,
                    new LinkedHashMap<>(original.params));
        }
        if (genre.isEmpty() && country.isEmpty() && dateStart.isEmpty() && dateEnd.isEmpty() && sort.isEmpty()) return original;
        String type = RexTmdbQuery.TV.equals(original.fallbackMediaType) ? RexTmdbQuery.TV : RexTmdbQuery.MOVIE;
        Map<String, String> params = new LinkedHashMap<>(original.params);
        if (!genre.isEmpty()) {
            String fixed = params.getOrDefault("with_genres", "");
            params.put("with_genres", fixed.isEmpty() || fixed.equals(genre) ? genre : fixed + "," + genre);
        }
        if (!country.isEmpty()) params.put("with_origin_country", country);
        String dateField = RexTmdbQuery.TV.equals(type) ? "first_air_date" : "primary_release_date";
        if (!dateStart.isEmpty()) params.put(dateField + ".gte", dateStart);
        if (!dateEnd.isEmpty()) params.put(dateField + ".lte", dateEnd);
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
}
