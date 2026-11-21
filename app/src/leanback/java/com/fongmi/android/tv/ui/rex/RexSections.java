package com.fongmi.android.tv.ui.rex;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.rex.RexTmdbQuery;
import com.fongmi.android.tv.bean.RexSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Rex 栏目清单（首页与推荐页共用，顺序即精选大卡行与推荐流的展示顺序）。
 *
 * <p>数据来源：TMDB（走应用反代）+ rexnow 公开 feed + 豆瓣云桥；
 * {@code shelf()} 标记的栏目同时以经典书架样式出现在首页（「动漫」等新板块）。
 */
public final class RexSections {

    private RexSections() {
    }

    public static List<RexSection> home() {
        List<RexSection> sections = new ArrayList<>();
        sections.add(RexSection.tmdb("trending_day", R.string.rex_section_trending_day, RexTmdbQuery.trending("day")));
        sections.add(RexSection.tmdb("now_playing", R.string.rex_section_now_playing, RexTmdbQuery.nowPlaying()));
        sections.add(RexSection.tmdb("popular_movie", R.string.rex_section_popular_movie, RexTmdbQuery.popular(RexTmdbQuery.MOVIE)));
        sections.add(RexSection.tmdb("popular_tv", R.string.rex_section_popular_tv, RexTmdbQuery.popular(RexTmdbQuery.TV)));
        sections.add(RexSection.tmdb("top_movie", R.string.rex_section_top_movie, RexTmdbQuery.topRated(RexTmdbQuery.MOVIE)));
        sections.add(RexSection.tmdb("top_tv", R.string.rex_section_top_tv, RexTmdbQuery.topRated(RexTmdbQuery.TV)));
        sections.add(RexSection.bridge("douban_movie", R.string.rex_section_douban_movie, "movie_hot_gaia").doubanFallback("trending"));
        sections.add(RexSection.bridge("douban_tv", R.string.rex_section_douban_tv, "tv_hot").doubanFallback("trending"));
        sections.add(RexSection.regional("cn_series", R.string.rex_section_cn_series, "chinese", "series").shelf());
        sections.add(RexSection.regional("kr_series", R.string.rex_section_kr_series, "korean", "series").shelf());
        sections.add(RexSection.regional("cn_variety", R.string.rex_section_cn_variety, "chinese", "variety").shelf());
        sections.add(RexSection.anime("anime_daily", R.string.rex_section_anime).shelf());
        return sections;
    }

    /** 按 key 查栏目（栏目列表承接页用）。 */
    public static RexSection byKey(String key) {
        if (key == null) return null;
        for (RexSection section : home()) if (section.getKey().equals(key)) return section;
        for (RexSection section : domestic()) if (section.getKey().equals(key)) return section;
        return null;
    }

    /** Media-library section cards follow the home layout's fixed four topics. */
    public static List<RexSection> librarySpotlights() {
        return List.of(
                RexSection.regional("cn_variety", R.string.home_library_month_variety, "chinese", "variety").limit(12),
                RexSection.regional("cn_series", R.string.home_library_month_series, "chinese", "series").limit(12),
                RexSection.tmdb("popular_movie", R.string.rex_section_popular_movie, RexTmdbQuery.popular(RexTmdbQuery.MOVIE)).limit(12),
                RexSection.anime("anime_daily", R.string.rex_section_anime).limit(12));
    }

    /** Available compact-home shelves and regional spotlight cards. */
    public static List<RexSection> compactHome() {
        List<RexSection> sections = new ArrayList<>(domestic());
        for (RexSection section : home()) {
            if (List.of("trending_day", "douban_movie", "douban_tv", "now_playing", "popular_movie", "popular_tv",
                    "cn_series", "kr_series", "cn_variety", "anime_daily").contains(section.getKey())) {
                sections.add(section.limit(12));
            }
        }
        return sections;
    }

    private static List<RexSection> domestic() {
        return List.of(
                RexSection.tmdb("cn_movie", R.string.home_compact_cn_movie, RexTmdbQuery.chinese(RexTmdbQuery.MOVIE, false)).limit(12),
                RexSection.regional("cn_tv", R.string.home_compact_cn_tv, "chinese", "series").limit(12),
                RexSection.tmdb("cn_top_movie", R.string.home_compact_cn_top_movie, RexTmdbQuery.chinese(RexTmdbQuery.MOVIE, true)).limit(12),
                RexSection.tmdb("cn_top_tv", R.string.home_compact_cn_top_tv, RexTmdbQuery.chinese(RexTmdbQuery.TV, true)).limit(12));
    }
}
