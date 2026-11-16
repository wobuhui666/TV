package com.fongmi.android.tv.ui.home;

import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.ui.rex.RexFeedResult;
import com.fongmi.android.tv.ui.rex.RexSections;
import com.fongmi.android.tv.bean.RexSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Presentation policy only: no source queries or playback selection happen on the home wall. */
public final class PosterHomeCatalog {
    private static final Pattern TITLE_SEPARATORS = Pattern.compile("[\\s\\p{P}]");

    public static final int ALL = 0;
    public static final int MOVIES = 1;
    public static final int TV = 2;
    public static final int TOP = 3;
    public static final int COMPACT_SHELF_LIMIT = 12;

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

    public static List<String> compactKeys(int category) {
        return switch (category) {
            case MOVIES -> List.of("cn_movie", "douban_movie", "now_playing");
            case TV -> List.of("cn_tv", "douban_tv", "popular_tv");
            case TOP -> List.of("cn_top_movie", "cn_top_tv");
            default -> List.of("cn_movie", "cn_tv", "trending_day");
        };
    }

    public static List<RexSection> compactSections(int category) {
        List<RexSection> result = new ArrayList<>();
        for (String key : compactKeys(category)) {
            RexSection section = RexSections.byKey(key);
            if (section != null) result.add(section.limit(COMPACT_SHELF_LIMIT));
        }
        return result;
    }

    /** Keep identities intact: titles alone must not collapse different films or seasons. */
    public static List<Vod> compactItems(List<Vod> items, int category) {
        if (items == null) return List.of();
        List<Vod> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Vod item : items) {
            if (item == null || item.getId().isEmpty() || item.getName().isEmpty() || item.getPic().isEmpty()
                    || item.isAction() || item.isFolder()) continue;
            if (category == MOVIES && !DiscoverMediaKey.MOVIE.equals(item.getTypeName())) continue;
            if (category == TV && !DiscoverMediaKey.TV.equals(item.getTypeName())) continue;
            if (!seen.add(item.getId())) continue;
            result.add(item);
            if (result.size() == COMPACT_SHELF_LIMIT) break;
        }
        return result;
    }

    public static List<Vod> compactHero(List<RexFeedResult> feeds, int category) {
        if (feeds == null) return List.of();
        Map<String, List<Vod>> content = new LinkedHashMap<>();
        List<String> order = new ArrayList<>(compactKeys(category));
        // Existing snapshot callers can still offer the original Douban poster fallback.
        if (category != TOP) {
            if (category != TV) order.add("douban_movie");
            if (category != MOVIES) order.add("douban_tv");
        }
        for (RexFeedResult feed : feeds) content.put(feed.getSection().getKey(), compactItems(feed.getItems(), category));
        Map<String, Vod> result = new LinkedHashMap<>();
        // Round-robin keeps domestic films and series visible alongside the global spotlight.
        for (boolean requireLandscape : new boolean[]{true, false}) {
            for (int i = 0; i < COMPACT_SHELF_LIMIT; i++) {
                for (String key : order) {
                    List<Vod> items = content.getOrDefault(key, List.of());
                    if (i >= items.size()) continue;
                    Vod item = items.get(i);
                    if (requireLandscape && item.getBackdrop().equals(item.getPic())) continue;
                    String title = TITLE_SEPARATORS.matcher(item.getName().toLowerCase(Locale.ROOT)).replaceAll("");
                    result.putIfAbsent(title + ":" + item.getYear() + ":" + item.getTypeName(), item);
                    if (result.size() == 5) return new ArrayList<>(result.values());
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    public static List<Vod> sourceVideos(List<Vod> items) {
        List<Vod> result = new ArrayList<>();
        if (items == null) return result;
        for (Vod item : items) {
            if (item == null || item.isAction() || item.isFolder() || item.getName().isEmpty()) continue;
            result.add(item);
            if (result.size() == 20) break;
        }
        return result;
    }

    public static List<Vod> sourceActions(List<Vod> items) {
        List<Vod> result = new ArrayList<>();
        if (items != null) for (Vod item : items) if (item != null && (item.isAction() || item.isFolder())) result.add(item);
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
                DiscoverApi.Row.TMDB_NOW_PLAYING, DiscoverApi.Row.TMDB_POPULAR_MOVIE, DiscoverApi.Row.TMDB_TOP_MOVIE,
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
