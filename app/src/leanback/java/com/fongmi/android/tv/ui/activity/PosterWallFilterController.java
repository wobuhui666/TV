package com.fongmi.android.tv.ui.activity;

import android.view.View;
import android.view.ViewGroup;

import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.api.rex.RexTmdbQuery;
import com.fongmi.android.tv.api.rex.RexWallQuery;
import com.fongmi.android.tv.bean.DiscoverFacet;
import com.fongmi.android.tv.bean.DiscoverFilterOption;
import com.fongmi.android.tv.bean.DiscoverFilterPanel;
import com.fongmi.android.tv.bean.DiscoverQuery;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.ui.presenter.DiscoverFilterPanelPresenter;
import com.fongmi.android.tv.utils.ResUtil;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compact, palette-backed wall filters; remote lists query the server, feeds filter their own items. */
final class PosterWallFilterController implements DiscoverFilterPanelPresenter.Listener {

    private static final int MEDIA = 1, GENRE = 2, REGION = 3, YEAR = 4, SORT = 5;
    private static final java.util.regex.Pattern RATING = java.util.regex.Pattern.compile("[0-9]+(?:\\.[0-9]+)?分?");
    private static final java.util.regex.Pattern RELEASE_YEAR = java.util.regex.Pattern.compile("\\d{4}");
    private final ViewGroup container;
    private final RexTmdbQuery.Query original;
    private final Runnable changed;
    private final Object tag = new Object();
    private final DiscoverFilterPanel panel = new DiscoverFilterPanel();
    private final DiscoverFilterPanelPresenter presenter = new DiscoverFilterPanelPresenter(this);
    private final Presenter.ViewHolder holder;
    private List<DiscoverFacet> remoteGenres = List.of();
    private List<Vod> localItems = List.of();
    private Map<String, List<Vod>> weekly = Map.of();
    private String mediaType = "", genre = "", region = "", dateStart = "", dateEnd = "", sort = "", weekday = "";
    private boolean closed;

    PosterWallFilterController(ViewGroup container, RexTmdbQuery.Query original, Runnable changed) {
        this.container = container;
        this.original = original;
        this.changed = changed;
        holder = presenter.onCreateViewHolder(container);
        container.addView(holder.view);
        holder.view.setPadding(0, ResUtil.dp2px(4), 0, ResUtil.dp2px(4));
        holder.view.findViewById(R.id.filterTitle).setVisibility(View.GONE);
        holder.view.findViewById(R.id.filterHint).setVisibility(View.GONE);
        update();
        if (original != null && !isTrending() && !original.params.containsKey("with_genres")) loadGenres();
    }

    RexTmdbQuery.Query query() {
        return RexWallQuery.apply(original, mediaType, genre, region, dateStart, dateEnd, sort);
    }

    void setLocalItems(List<Vod> items, Map<String, List<Vod>> groups) {
        localItems = List.copyOf(items);
        weekly = groups == null ? Map.of() : groups;
        update();
    }

    List<Vod> filterLocal() {
        List<Vod> source = weekday.isEmpty() ? localItems : weekly.getOrDefault(weekday, List.of());
        List<Vod> result = new ArrayList<>();
        for (Vod item : source) {
            if (!genre.isEmpty() && !genres(item).contains(genre)) continue;
            String year = releaseYear(item);
            if (!dateStart.isEmpty() && (year.isEmpty() || year.compareTo(dateStart.substring(0, 4)) < 0)) continue;
            if (!dateEnd.isEmpty() && (year.isEmpty() || year.compareTo(dateEnd.substring(0, 4)) > 0)) continue;
            result.add(item);
        }
        if (DiscoverQuery.SORT_LATEST.equals(sort)) result.sort(Comparator.comparing(PosterWallFilterController::releaseYear).reversed());
        if (DiscoverQuery.SORT_RATING.equals(sort)) result.sort(Comparator.comparingDouble(PosterWallFilterController::rating).reversed());
        return result;
    }

    boolean focus() { return !closed && container.isShown() && holder.view.findViewById(R.id.summary).requestFocus(); }

    boolean collapse() {
        if (panel.getExpandedRow() < 0) return false;
        panel.setExpandedRow(-1);
        update();
        focus();
        return true;
    }

    void release() {
        closed = true;
        DiscoverApi.cancel(tag);
        presenter.onUnbindViewHolder(holder);
    }

    private boolean isTrending() { return original != null && original.path.startsWith("trending/"); }

    private void loadGenres() {
        DiscoverApi.fetchGenres(original.fallbackMediaType, BuildConfig.TMDB_API_KEY, tag, new DiscoverApi.FacetListener() {
            @Override public void onSuccess(List<DiscoverFacet> items) {
                if (closed) return;
                remoteGenres = items;
                update();
            }
            @Override public void onError(Exception error) { if (!closed) update(); }
        });
    }

    private void update() {
        if (closed) return;
        for (int row = 0; row < panel.getRowCount(); row++) panel.setRow(row, List.of());
        if (isTrending()) {
            panel.setRow(MEDIA, List.of(option("", text(R.string.poster_wall_all_media), mediaType),
                    option("movie", text(R.string.discover_media_movie), mediaType), option("tv", text(R.string.discover_media_tv), mediaType)));
        } else {
            if (!weekly.isEmpty()) {
                panel.setLabel(MEDIA, text(R.string.poster_wall_update_day));
                List<DiscoverFilterOption> days = new ArrayList<>();
                days.add(option("", text(R.string.poster_wall_all_update_days), weekday));
                for (String day : weekly.keySet()) days.add(option(day, day, weekday));
                panel.setRow(MEDIA, days);
            }
            List<DiscoverFilterOption> genres = new ArrayList<>();
            genres.add(option("", text(R.string.discover_all), genre));
            if (original != null) for (DiscoverFacet value : remoteGenres) genres.add(option(value.getId(), value.getName(), genre));
            else {
                Set<String> available = new LinkedHashSet<>();
                for (Vod item : localItems) available.addAll(genres(item));
                for (String value : available) genres.add(option(value, value, genre));
            }
            if (genres.size() > 1 && (original == null || !original.params.containsKey("with_genres"))) panel.setRow(GENRE, genres);
            if (original != null) panel.setRow(REGION, regionOptions());
            List<DiscoverFilterOption> years = yearOptions();
            if (years.size() > 1) panel.setRow(YEAR, years);
            List<DiscoverFilterOption> sorts = new ArrayList<>();
            sorts.add(option("", text(R.string.poster_wall_original_order), sort));
            if (original != null) sorts.add(option(DiscoverQuery.SORT_POPULAR, text(R.string.discover_sort_popular), sort));
            if (original != null || localItems.stream().anyMatch(item -> rating(item) >= 0)) sorts.add(option(DiscoverQuery.SORT_RATING, text(R.string.discover_sort_rating), sort));
            if (original != null || localItems.stream().anyMatch(item -> !releaseYear(item).isEmpty())) sorts.add(option(DiscoverQuery.SORT_LATEST, text(R.string.discover_sort_latest), sort));
            if (sorts.size() > 1) panel.setRow(SORT, sorts);
        }
        container.setVisibility(panel.getVisibleRows().isEmpty() ? View.GONE : View.VISIBLE);
        presenter.onBindViewHolder(holder, panel);
    }

    private List<DiscoverFilterOption> regionOptions() {
        return List.of(option("", text(R.string.discover_all), region), option("CN", text(R.string.discover_region_cn), region),
                option("HK", text(R.string.discover_region_hk), region), option("TW", text(R.string.discover_region_tw), region),
                option("US", text(R.string.discover_region_us), region), option("JP", text(R.string.discover_region_jp), region),
                option("KR", text(R.string.discover_region_kr), region), option("GB", text(R.string.discover_region_gb), region),
                option("FR", text(R.string.discover_region_fr), region));
    }

    private List<DiscoverFilterOption> yearOptions() {
        List<DiscoverFilterOption> result = new ArrayList<>();
        result.add(year(text(R.string.discover_all), "", ""));
        if (original == null) {
            Set<String> values = new java.util.TreeSet<>(Comparator.reverseOrder());
            for (Vod item : localItems) if (!releaseYear(item).isEmpty()) values.add(releaseYear(item));
            for (String value : values) result.add(year(value, value + "-01-01", value + "-12-31"));
        } else if (!original.path.equals("movie/now_playing")) {
            int current = LocalDate.now().getYear();
            for (int value = current; value >= current - 7; value--) result.add(year(String.valueOf(value), value + "-01-01", value + "-12-31"));
            for (int start = (current - 8) / 10 * 10; start >= 1960; start -= 10) result.add(year(container.getContext().getString(R.string.discover_year_decade, start), start + "-01-01", (start + 9) + "-12-31"));
            result.add(year(text(R.string.discover_year_before_1960), "", "1959-12-31"));
        }
        return result;
    }

    private static List<String> genres(Vod item) {
        List<String> result = new ArrayList<>();
        for (String value : item.getGenreText().split("[、,，/|]+")) if (!value.trim().isEmpty()) result.add(value.trim());
        return result;
    }

    private static double rating(Vod item) {
        String value = item.getRemarks().trim();
        if (!RATING.matcher(value).matches()) return -1;
        try {
            double rating = Double.parseDouble(value.replace("分", ""));
            return rating >= 0 && rating <= 10 ? rating : -1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    private static String releaseYear(Vod item) { return RELEASE_YEAR.matcher(item.getYear()).matches() ? item.getYear() : ""; }

    private String text(int resource) { return container.getContext().getString(resource); }
    private DiscoverFilterOption option(String value, String label, String selected) { return new DiscoverFilterOption(value, label, value.equals(selected)); }
    private DiscoverFilterOption year(String label, String start, String end) { return new DiscoverFilterOption(label, label, start, end, start.equals(dateStart) && end.equals(dateEnd)); }

    @Override public void onFilterGroupClick(int row) {
        panel.setExpandedRow(panel.getExpandedRow() == row ? -1 : row);
        update();
    }

    @Override public void onFilterClick(int row, DiscoverFilterOption option) {
        boolean differs = switch (row) {
            case MEDIA -> !(isTrending() ? mediaType : weekday).equals(option.getValue());
            case GENRE -> !genre.equals(option.getValue());
            case REGION -> !region.equals(option.getValue());
            case YEAR -> !dateStart.equals(option.getStartDate()) || !dateEnd.equals(option.getEndDate());
            case SORT -> !sort.equals(option.getValue());
            default -> false;
        };
        if (!differs) return;
        switch (row) {
            case MEDIA -> { if (isTrending()) mediaType = option.getValue(); else weekday = option.getValue(); }
            case GENRE -> genre = option.getValue();
            case REGION -> region = option.getValue();
            case YEAR -> { dateStart = option.getStartDate(); dateEnd = option.getEndDate(); }
            case SORT -> sort = option.getValue();
            default -> { return; }
        }
        update();
        changed.run();
    }
}
