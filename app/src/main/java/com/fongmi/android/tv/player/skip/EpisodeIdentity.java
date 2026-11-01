package com.fongmi.android.tv.player.skip;

/** Canonical media identity; source ids and episode list positions are never TMDB ids. */
public record EpisodeIdentity(long tmdbId, String imdbId, int season, int episode) {

    public EpisodeIdentity {
        if (tmdbId <= 0 || !((season == -1 && episode == -1) || (season >= 0 && episode > 0)))
            throw new IllegalArgumentException("Invalid media identity");
        imdbId = imdbId != null && imdbId.matches("tt[0-9]{5,10}") ? imdbId : "";
    }

    public EpisodeIdentity(long tmdbId, int season, int episode) {
        this(tmdbId, "", season, episode);
    }

    public String key() {
        return isMovie() ? "tmdb:movie:" + tmdbId : "tmdb:tv:" + tmdbId + ":" + season + ":" + episode;
    }

    public boolean isMovie() {
        return season == -1;
    }

    public static EpisodeIdentity movie(long tmdbId, String imdbId) {
        return new EpisodeIdentity(tmdbId, imdbId, -1, -1);
    }
}
