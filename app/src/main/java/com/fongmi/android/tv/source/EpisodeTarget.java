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
            label = new EpisodeLabel(label.original(), sourceSeason, label.number(), label.part(), label.edition(),
                    label.isSpecial() || sourceSeason == 0, label.isPreview(), label.isAmbiguous());
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
        return number == null && !generated && !label.isAmbiguous() && !label.isPreview() && !label.isSpecial();
    }

    /** Keep season and edition when looking for a missing next episode on another source. */
    public EpisodeTarget offset(int offset) {
        if (generated || number == null || !label.isApiSafe() || number + offset <= 0) return null;
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
        if (label.season() != null && !label.season().equals(season)) return false;
        if (!label.part().equals(candidate.part()) || label.isSpecial() != candidate.isSpecial()
                || label.isPreview() != candidate.isPreview()) return false;
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
