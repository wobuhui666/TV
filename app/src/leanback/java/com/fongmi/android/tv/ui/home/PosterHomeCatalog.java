package com.fongmi.android.tv.ui.home;

import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Presentation policy only: no source queries or playback selection happen on the home wall. */
public final class PosterHomeCatalog {
    private static final Pattern TITLE_SEPARATORS = Pattern.compile("[\\s\\p{P}]");

    public static final int ALL = 0;
    public static final int MOVIES = 1;
    public static final int TV = 2;
    public static final int TOP = 3;

    private PosterHomeCatalog() {
    }

    public static List<Vod> filter(List<Vod> items, int category) {
        if (items == null) return List.of();
        List<Vod> result = new ArrayList<>();
        for (Vod item : items) {
            if (item == null || item.getName().isEmpty() || item.getPic().isEmpty()) continue;
            if (category == MOVIES && !DiscoverMediaKey.MOVIE.equals(item.getTypeName())) continue;
            if (category == TV && !DiscoverMediaKey.TV.equals(item.getTypeName())) continue;
            result.add(item);
            if (result.size() == 20) break;
        }
        return result;
    }

    public static boolean visible(DiscoverApi.Row row, int category) {
        return switch (category) {
            case MOVIES -> row != DiscoverApi.Row.DOUBAN_HOT_TV && row != DiscoverApi.Row.TMDB_POPULAR_TV
                    && row != DiscoverApi.Row.TMDB_TOP_TV;
            case TV -> row == DiscoverApi.Row.DOUBAN_HOT_TV || row == DiscoverApi.Row.TMDB_POPULAR_TV
                    || row == DiscoverApi.Row.TMDB_TOP_TV || row == DiscoverApi.Row.TMDB_DAY;
            case TOP -> row == DiscoverApi.Row.TMDB_TOP_MOVIE || row == DiscoverApi.Row.TMDB_TOP_TV;
            default -> row != DiscoverApi.Row.TMDB_TOP_TV;
        };
    }

    public static List<Vod> hero(Map<DiscoverApi.Row, List<Vod>> content, int category) {
        Map<String, Vod> result = new LinkedHashMap<>();
        DiscoverApi.Row[] order = {
                DiscoverApi.Row.TMDB_DAY, DiscoverApi.Row.TMDB_POPULAR_TV,
                DiscoverApi.Row.TMDB_NOW_PLAYING, DiscoverApi.Row.TMDB_TOP_MOVIE,
                DiscoverApi.Row.TMDB_TOP_TV, DiscoverApi.Row.DOUBAN_HOT_MOVIE,
                DiscoverApi.Row.DOUBAN_HOT_TV
        };
        // Real landscape artwork first; poster-only sources can still supply a useful fallback.
        for (boolean requireLandscape : new boolean[]{true, false}) {
            for (DiscoverApi.Row row : order) {
                if (!visible(row, category)) continue;
                for (Vod item : filter(content.get(row), category)) {
                    if (requireLandscape && item.getBackdrop().equals(item.getPic())) continue;
                    String title = TITLE_SEPARATORS.matcher(item.getName().toLowerCase(Locale.ROOT)).replaceAll("");
                    String key = title + ":" + item.getYear() + ":" + item.getTypeName();
                    result.putIfAbsent(key, item);
                    if (result.size() == 5) return new ArrayList<>(result.values());
                }
            }
        }
        return new ArrayList<>(result.values());
    }
}
