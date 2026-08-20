package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;

public final class EpisodeTarget {

    private final Integer number;
    private final String name;

    public EpisodeTarget(Integer number, String name) {
        this.number = number;
        this.name = name == null ? "" : name;
    }

    public static EpisodeTarget of(Episode episode) {
        if (episode == null) return new EpisodeTarget(null, "");
        Integer number = MediaMatcher.extractEpisodeNumber(episode.getName());
        return new EpisodeTarget(number == null ? (episode.getNumber() > 0 ? episode.getNumber() : null) : number, episode.getName());
    }

    public static EpisodeTarget of(int number) {
        return new EpisodeTarget(number, String.valueOf(number));
    }

    public Integer getNumber() {
        return number;
    }

    public String getName() {
        return name;
    }

    public boolean matches(Episode episode) {
        if (episode == null) return false;
        Integer candidate = MediaMatcher.extractEpisodeNumber(episode.getName());
        if (candidate == null && episode.getNumber() > 0) candidate = episode.getNumber();
        if (number != null && candidate != null) return number.equals(candidate);
        return !name.isEmpty() && MediaMatcher.normalizedEpisodeName(name).equals(MediaMatcher.normalizedEpisodeName(episode.getName()));
    }
}
