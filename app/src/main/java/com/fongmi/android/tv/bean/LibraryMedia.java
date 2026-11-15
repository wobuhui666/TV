package com.fongmi.android.tv.bean;

import java.time.LocalDate;
import java.util.List;

/** TMDB's descriptive catalogue, separate from a source's actual media addresses. */
public final class LibraryMedia {
    public record Season(int number, String name, int episodes, String poster) { }
    public record Episode(int season, int number, String name, String overview, String still, String airDate, int runtime) { }
    public record Video(String name, String url, String thumbnail) { }
    public record AiringEpisode(int season, int number, String name, String airDate, int runtime) { }
    /** The update is an episode coordinate; total is the selected season's catalogue count. */
    public record SeasonProgress(int updatedEpisode, int totalEpisodes, boolean complete, AiringEpisode nextEpisode) { }
    public record Metadata(DiscoverDetail detail, String logo, List<Season> seasons, List<String> aliases,
                           List<String> logos, String originalLanguage, AiringEpisode lastAired,
                           AiringEpisode nextAiring, int totalEpisodes, boolean inProduction) {
        public Metadata(DiscoverDetail detail, String logo, List<Season> seasons, List<String> aliases) {
            this(detail, logo, seasons, aliases, logo.isEmpty() ? List.of() : List.of(logo), "", null, null,
                    detail.getEpisodes(), false);
        }

        /** The series-wide total must never be displayed as the selected season's total. */
        public int seasonEpisodeCount(int number) {
            return seasons.stream().filter(season -> season.number() == number)
                    .mapToInt(Season::episodes).findFirst().orElse(0);
        }

        public AiringEpisode nextAiringForSeason(int number) {
            return nextAiring != null && nextAiring.season() == number ? nextAiring : null;
        }

        public boolean ended() { return "Ended".equalsIgnoreCase(detail.getStatus()); }
        public boolean canceled() { return "Canceled".equalsIgnoreCase(detail.getStatus()); }

        /** Future episodes and the latest update of another season supply no progress for this season. */
        public SeasonProgress seasonProgress(int number, List<Episode> episodes, LocalDate today) {
            int total = seasonEpisodeCount(number);
            int updated = 0;
            if (lastAired != null && lastAired.season() == number && aired(lastAired.airDate(), today, true)) {
                updated = lastAired.number();
            }
            if (episodes != null) for (Episode episode : episodes) {
                if (episode.season() == number && aired(episode.airDate(), today, false)) {
                    updated = Math.max(updated, episode.number());
                }
            }
            return new SeasonProgress(updated, total, total > 0 && updated == total, nextAiringForSeason(number));
        }

        private static boolean aired(String date, LocalDate today, boolean knownLastEpisode) {
            if (date == null || date.isEmpty()) return knownLastEpisode;
            try { return !LocalDate.parse(date).isAfter(today); }
            catch (RuntimeException error) { return false; }
        }
    }
    public record Extras(List<Video> videos, List<String> images, List<Vod> recommendations) { }

    private LibraryMedia() { }
}
