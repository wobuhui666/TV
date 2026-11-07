package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;

import java.util.Objects;

/** An episode coordinate and the selected release, separate from its list position. */
public final class EpisodeTarget {

    private final Integer number;
    private final String name;
    private final String url;
    private final boolean generated;
    private final EpisodeLabel label;

    public EpisodeTarget(Integer number, String name) {
        this(number, name, "", false, EpisodeLabelParser.parse(name));
    }

    private EpisodeTarget(Integer number, String name, String url, boolean generated, EpisodeLabel label) {
        this.label = label;
        this.number = generated ? null : number != null ? number : label.number();
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
        this.generated = generated;
    }

    public static EpisodeTarget of(Episode episode) {
        return of(episode, null);
    }

    public static EpisodeTarget of(Episode episode, Integer sourceSeason) {
        if (episode == null) return new EpisodeTarget(null, "");
        EpisodeLabel label = episode.getLabel();
        if (label.season() == null && sourceSeason != null) {
            label = label.withSeason(sourceSeason);
        }
        return new EpisodeTarget(null, episode.getName(), episode.getUrl(), episode.isGeneratedLabel(), label);
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

    public boolean isUnnumbered() {
        return number == null && !generated && !label.isAmbiguous() && !label.isPreview() && !label.isSpecial()
                && !label.isVariety() && label.part().isEmpty();
    }

    /** Keep season and content track when searching for the next known ordinal on another source. */
    public EpisodeTarget offset(int offset) {
        if (generated || number == null || label.isAmbiguous() || label.hasAirDate() || !label.part().isEmpty()
                || label.isPreview() || label.isSpecial() && !Integer.valueOf(0).equals(label.season())
                || (long) number + offset <= 0 || (long) number + offset > Integer.MAX_VALUE) return null;
        return new EpisodeTarget(number + offset, "", "", false, label);
    }

    public boolean matches(Episode episode) {
        return matches(episode, null);
    }

    boolean matches(Episode episode, Integer sourceSeason) {
        if (episode == null) return false;
        if (generated || episode.isGeneratedLabel()) return !url.isEmpty() && url.equals(episode.getUrl());
        EpisodeLabel candidate = episode.getLabel();
        Integer season = candidate.season() == null ? sourceSeason : candidate.season();
        if (label.season() != null && season != null && !label.season().equals(season)) return false;
        if (!label.part().equals(candidate.part()) || label.isSpecial() != candidate.isSpecial()
                || label.isPreview() != candidate.isPreview() || !label.variant().equals(candidate.variant())) return false;
        if (label.hasAirDate() && candidate.hasAirDate() && !label.airDate().equals(candidate.airDate())) return false;
        if (number != null && candidate.number() != null && !number.equals(candidate.number())) return false;
        // URL identity may preserve a selected item whose replacement label has less evidence.
        if (!url.isEmpty() && url.equals(episode.getUrl())) return true;
        if (label.season() != null && !label.season().equals(season)) return false;
        if (label.isAmbiguous() || candidate.isAmbiguous()) return false;
        // A calendar label cannot establish the period of an undated source (or vice versa).
        if (label.hasAirDate() || candidate.hasAirDate()) {
            return label.hasAirDate() && candidate.hasAirDate();
        }
        if (label.isSingleEpisode() && candidate.isSingleEpisode() && !label.isPreview()) {
            return Objects.equals(number, candidate.number());
        }
        return !name.isEmpty() && MediaMatcher.normalizedEpisodeName(name).equals(MediaMatcher.normalizedEpisodeName(episode.getName()));
    }

    int score(Episode episode) {
        if (!url.isEmpty() && url.equals(episode.getUrl())) return 1000;
        if (!name.isEmpty() && name.equalsIgnoreCase(episode.getName())) return 600;
        int score = 100;
        if (!label.edition().isEmpty() && label.edition().equals(episode.getLabel().edition())) score += 300;
        if (label.edition().isEmpty() && episode.getLabel().edition().isEmpty()) score += 80;
        if (label.season() != null && label.season().equals(episode.getLabel().season())) score += 30;
        return score;
    }
}
