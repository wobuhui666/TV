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

    EpisodeLabel(String original, Integer season, Integer number, String part, String edition,
                 boolean special, boolean preview, boolean ambiguous) {
        this.original = original;
        this.season = season;
        this.number = number;
        this.part = part;
        this.edition = edition;
        this.special = special;
        this.preview = preview;
        this.ambiguous = ambiguous;
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

    public boolean isSingleEpisode() {
        return number != null && !ambiguous;
    }

    public boolean isApiSafe() {
        return isSingleEpisode() && part.isEmpty() && !preview && (!special || Integer.valueOf(0).equals(season));
    }
}
