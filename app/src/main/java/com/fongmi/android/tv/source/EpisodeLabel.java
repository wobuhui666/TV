package com.fongmi.android.tv.source;

/** Parsed label evidence, kept separate from the position of an item in a playlist. */
public final class EpisodeLabel {

    private final String original;
    private final Integer season;
    private final Integer number;
    private final String part;
    private final String edition;
    private final boolean special;
    private final boolean preview;
    private final boolean ambiguous;
    private final String airDate;
    private final String variant;
    private final boolean issue;

    EpisodeLabel(String original, Integer season, Integer number, String part, String edition,
                 boolean special, boolean preview, boolean ambiguous) {
        this(original, season, number, part, edition, special, preview, ambiguous, "", "", false);
    }

    EpisodeLabel(String original, Integer season, Integer number, String part, String edition,
                 boolean special, boolean preview, boolean ambiguous, String airDate, String variant, boolean issue) {
        this.original = original;
        this.season = season;
        this.number = number;
        this.part = part;
        this.edition = edition;
        this.special = special;
        this.preview = preview;
        this.ambiguous = ambiguous;
        this.airDate = airDate;
        this.variant = variant;
        this.issue = issue;
    }

    public String original() {
        return original;
    }

    public Integer season() {
        return season;
    }

    public Integer number() {
        return number;
    }

    public String part() {
        return part;
    }

    public String edition() {
        return edition;
    }

    public boolean isSpecial() {
        return special;
    }

    public boolean isPreview() {
        return preview;
    }

    public boolean isAmbiguous() {
        return ambiguous;
    }

    /** Calendar date in ISO format, or an empty string when the label supplies no valid date. */
    public String airDate() {
        return airDate;
    }

    /** Sorted content tags; unmarked and regular/full episodes both use the empty string. */
    public String variant() {
        return variant;
    }

    public boolean isIssue() {
        return issue;
    }

    public boolean hasAirDate() {
        return !airDate.isEmpty();
    }

    public boolean isVariety() {
        return issue || hasAirDate() || !variant.isEmpty();
    }

    public EpisodeLabel withSeason(Integer season) {
        return new EpisodeLabel(original, season, number, part, edition, special || Integer.valueOf(0).equals(season), preview, ambiguous,
                airDate, variant, issue);
    }

    public boolean isSingleEpisode() {
        return number != null && !ambiguous;
    }

    public boolean isApiSafe() {
        return isSingleEpisode() && part.isEmpty() && !preview && airDate.isEmpty() && variant.isEmpty()
                && (!special || Integer.valueOf(0).equals(season));
    }
}
