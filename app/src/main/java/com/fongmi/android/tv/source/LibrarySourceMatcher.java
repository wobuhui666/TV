package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import java.util.List;
import java.util.regex.Pattern;

/** Match complete titles while allowing sources to omit year and media type. */
public final class LibrarySourceMatcher {
    public record Match(boolean matches, boolean automatic, int score) { }
    private static final Pattern RELEASE_YEAR = Pattern.compile("(?:19|20)[0-9]{2}");

    private LibrarySourceMatcher() { }

    public static Match match(Vod source, String title, List<String> aliases, String year, boolean movie, Integer season) {
        return match(prepareSource(source), prepareWork(title, aliases, year, movie, season));
    }

    public static Match match(Vod source, Vod work) {
        List<String> aliases = work.getOriginalName().isEmpty() ? List.of() : List.of(work.getOriginalName());
        return match(source, work.getName(), aliases, work.getYear(), "movie".equals(work.getTypeName()), null);
    }

    /** Parses one search summary; the result can be matched again whenever the work's metadata is refined. */
    public static PreparedSource prepareSource(Vod source) {
        if (source == null || source.isFolder() || source.isAction()) return new PreparedSource(false, MediaIdentity.Kind.UNKNOWN, false, null, null);
        // These two season parsers intentionally remain separate: title safety uses SearchRelevance.
        return new PreparedSource(true, MediaMatcher.kind(source.getTypeName()), hasPlayback(source), MediaMatcher.extractSeason(source.getName()),
                SearchRelevance.prepareResult(source.getName(), releaseYear(source.getYear())));
    }

    /** Full dates often record when a source added an item, rather than its release year. */
    private static String releaseYear(String value) {
        String year = value == null ? "" : value.trim();
        return RELEASE_YEAR.matcher(year).matches() ? year : "";
    }

    static boolean hasPlayback(Vod source) {
        if (source == null) return false;
        for (Flag flag : source.getFlags()) {
            for (Episode episode : flag.getEpisodes()) {
                if (!episode.getUrl().isBlank()) return true;
            }
        }
        return false;
    }

    static PreparedWork prepareWork(Vod work) {
        List<String> aliases = work.getOriginalName().isEmpty() ? List.of() : List.of(work.getOriginalName());
        return prepareWork(work.getName(), aliases, work.getYear(), "movie".equals(work.getTypeName()), null);
    }

    /** Parses the title and its aliases once, so each source of a search costs a single title parse. */
    public static PreparedWork prepareWork(String title, List<String> aliases, String year, boolean movie, Integer season) {
        year = releaseYear(year);
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
        boolean implicitFirst = knownSeason && SearchRelevance.evaluatePrepared(source.title, work.implicitFirst).strict();
        boolean automatic = matches && (source.kind == expected || source.hasPlayback) && (titleMatch.strict() || implicitFirst);
        return new Match(matches, automatic, titleMatch.score());
    }

    public static final class PreparedSource {
        private final boolean accepted;
        private final MediaIdentity.Kind kind;
        private final boolean hasPlayback;
        private final Integer mediaSeason;
        private final SearchRelevance.PreparedResult title;
        private PreparedSource(boolean accepted, MediaIdentity.Kind kind, boolean hasPlayback, Integer mediaSeason, SearchRelevance.PreparedResult title) {
            this.accepted = accepted; this.kind = kind; this.hasPlayback = hasPlayback; this.mediaSeason = mediaSeason; this.title = title;
        }
    }

    /** Immutable, so a selection may keep using it on a worker after the page moves to other metadata. */
    public static final class PreparedWork {
        private final boolean movie;
        private final Integer season;
        private final SearchRelevance.PreparedQuery withYear, withoutYear, implicitFirst;
        private PreparedWork(boolean movie, Integer season, SearchRelevance.PreparedQuery withYear,
                             SearchRelevance.PreparedQuery withoutYear, SearchRelevance.PreparedQuery implicitFirst) {
            this.movie = movie; this.season = season; this.withYear = withYear;
            this.withoutYear = withoutYear; this.implicitFirst = implicitFirst;
        }

        public Match match(Vod source) { return match(prepareSource(source)); }
        public Match match(PreparedSource source) { return LibrarySourceMatcher.match(source, this); }
    }
}
