package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Complete title evidence is required; detailed episode labels can establish a missing series kind. */
public final class LibrarySourceMatcher {
    public record Match(boolean matches, boolean automatic, int score) { }
    private static final Pattern EPISODE_MARKER = Pattern.compile("(?i)(?:第?\\s*[零〇一二两三四五六七八九十百0-9]+\\s*[集话話]|(?<![a-z])(?:s\\d{1,2}[ ._-]*)?(?:episode|ep|e)[ ._-]*\\d{1,4})");

    private LibrarySourceMatcher() { }

    public static Match match(Vod source, String title, List<String> aliases, String year, boolean movie, Integer season) {
        if (source == null || source.isFolder() || source.isAction()) return new Match(false, false, 0);
        MediaIdentity.Kind kind = sourceKind(source);
        MediaIdentity.Kind expected = movie ? MediaIdentity.Kind.MOVIE : MediaIdentity.Kind.SERIES;
        if (kind != MediaIdentity.Kind.UNKNOWN && kind != expected) return new Match(false, false, 0);
        Integer sourceSeason = MediaMatcher.identity(source).getSeason();
        String expectedYear = movie || (season == null || season <= 1) && (sourceSeason == null || sourceSeason <= 1) ? year : "";
        SearchRelevance.Match titleMatch = SearchRelevance.evaluate(source,
                new SearchRelevance.Query(title, aliases, expectedYear, season));
        boolean matches = titleMatch.titleMatch() && (!movie || titleMatch.strict());
        boolean knownSeason = !movie && season != null && season == 1 && MediaMatcher.identity(source).getSeason() == null;
        boolean implicitFirst = knownSeason && SearchRelevance.evaluate(source,
                new SearchRelevance.Query(title, aliases, "", null)).confident();
        boolean automatic = matches && kind == expected && (titleMatch.confident() || implicitFirst);
        return new Match(matches, automatic, titleMatch.score());
    }

    public static Match match(Vod source, Vod work) {
        List<String> aliases = work.getOriginalName().isEmpty() ? List.of() : List.of(work.getOriginalName());
        return match(source, work.getName(), aliases, work.getYear(), "movie".equals(work.getTypeName()), null);
    }

    static PreparedSource prepareSource(Vod source) {
        if (source == null || source.isFolder() || source.isAction()) return new PreparedSource(false, MediaIdentity.Kind.UNKNOWN, null, null);
        // These two season parsers intentionally remain separate: title safety uses SearchRelevance.
        return new PreparedSource(true, sourceKind(source), MediaMatcher.extractSeason(source.getName()),
                SearchRelevance.prepareResult(source.getName(), source.getYear()));
    }

    private static MediaIdentity.Kind sourceKind(Vod source) {
        MediaIdentity.Kind declared = MediaMatcher.kind(source.getTypeName());
        if (declared != MediaIdentity.Kind.UNKNOWN) return declared;
        // Real sources often provide only genres, or omit type_name. Require two different
        // explicit episode coordinates in one line; generated ordinals and movie parts do not count.
        for (Flag flag : source.getFlags()) {
            Set<String> coordinates = new HashSet<>();
            for (Episode episode : flag.getEpisodes()) {
                if (episode.isGeneratedLabel() || !EPISODE_MARKER.matcher(episode.getName()).find()) continue;
                EpisodeLabel label = episode.getLabel();
                if (!label.isApiSafe() || label.isSpecial() || label.isVariety() || label.number() <= 0) continue;
                coordinates.add(label.season() + ":" + label.number());
                if (coordinates.size() >= 2) return MediaIdentity.Kind.SERIES;
            }
        }
        return MediaIdentity.Kind.UNKNOWN;
    }

    static PreparedWork prepareWork(Vod work) {
        List<String> aliases = work.getOriginalName().isEmpty() ? List.of() : List.of(work.getOriginalName());
        return prepareWork(work.getName(), aliases, work.getYear(), "movie".equals(work.getTypeName()), null);
    }

    static PreparedWork prepareWork(String title, List<String> aliases, String year, boolean movie, Integer season) {
        SearchRelevance.PreparedQuery withYear = SearchRelevance.prepareQuery(new SearchRelevance.Query(title, aliases, year, season));
        SearchRelevance.PreparedQuery withoutYear = movie ? withYear
                : SearchRelevance.prepareQuery(new SearchRelevance.Query(title, aliases, "", season));
        SearchRelevance.PreparedQuery implicitFirst = !movie && season != null && season == 1
                ? SearchRelevance.prepareQuery(new SearchRelevance.Query(title, aliases, "", null)) : null;
        return new PreparedWork(movie, season, withYear, withoutYear, implicitFirst);
    }

    static Match match(PreparedSource source, PreparedWork work) {
        if (!source.accepted) return new Match(false, false, 0);
        MediaIdentity.Kind expected = work.movie ? MediaIdentity.Kind.MOVIE : MediaIdentity.Kind.SERIES;
        if (source.kind != MediaIdentity.Kind.UNKNOWN && source.kind != expected) return new Match(false, false, 0);
        boolean useYear = work.movie || (work.season == null || work.season <= 1) && (source.mediaSeason == null || source.mediaSeason <= 1);
        SearchRelevance.Match titleMatch = SearchRelevance.evaluatePrepared(source.title, useYear ? work.withYear : work.withoutYear);
        boolean matches = titleMatch.titleMatch() && (!work.movie || titleMatch.strict());
        boolean knownSeason = !work.movie && work.season != null && work.season == 1 && source.mediaSeason == null;
        boolean implicitFirst = knownSeason && SearchRelevance.evaluatePrepared(source.title, work.implicitFirst).confident();
        boolean automatic = matches && source.kind == expected && (titleMatch.confident() || implicitFirst);
        return new Match(matches, automatic, titleMatch.score());
    }

    static final class PreparedSource {
        private final boolean accepted;
        private final MediaIdentity.Kind kind;
        private final Integer mediaSeason;
        private final SearchRelevance.PreparedResult title;
        private PreparedSource(boolean accepted, MediaIdentity.Kind kind, Integer mediaSeason, SearchRelevance.PreparedResult title) {
            this.accepted = accepted; this.kind = kind; this.mediaSeason = mediaSeason; this.title = title;
        }
    }

    static final class PreparedWork {
        private final boolean movie;
        private final Integer season;
        private final SearchRelevance.PreparedQuery withYear, withoutYear, implicitFirst;
        private PreparedWork(boolean movie, Integer season, SearchRelevance.PreparedQuery withYear,
                             SearchRelevance.PreparedQuery withoutYear, SearchRelevance.PreparedQuery implicitFirst) {
            this.movie = movie; this.season = season; this.withYear = withYear;
            this.withoutYear = withoutYear; this.implicitFirst = implicitFirst;
        }
    }
}
