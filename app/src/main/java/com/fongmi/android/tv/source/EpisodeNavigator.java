package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Keeps navigation on the selected release when one source mixes several releases. */
public final class EpisodeNavigator {

    private static final Integer CONFLICTING_SEASONS = Integer.MIN_VALUE;
    private static final Pattern REVISION_TAG = Pattern.compile("(?:^|\\|)revision[0-9]+(?=\\||$)");

    private EpisodeNavigator() {
    }

    public static Episode relative(List<Episode> episodes, int current, int offset) {
        if (episodes == null || episodes.isEmpty()) return null;
        int position = Math.max(0, Math.min(current, episodes.size() - 1));
        Episode selected = episodes.get(position);
        if (offset == 0) return selected;
        if (selected == null || selected.isGeneratedLabel() || !isMixed(episodes)) {
            int target = (int) Math.max(0L, Math.min((long) position + offset, episodes.size() - 1L));
            return episodes.get(target);
        }
        if (!isNumbered(selected)) return selected;

        int direction = Integer.signum(offset);
        long steps = Math.abs((long) offset);
        for (long step = 0; step < steps; step++) {
            int next = nextPosition(episodes, position, direction);
            if (next == position) break;
            position = next;
        }
        return episodes.get(position);
    }

    private static boolean isMixed(List<Episode> episodes) {
        Map<String, String> editions = new HashMap<>();
        Set<String> releaseGroups = new HashSet<>();
        Set<Integer> seasons = new HashSet<>();
        for (Episode episode : episodes) {
            if (!isNumbered(episode)) continue;
            EpisodeLabel label = episode.getLabel();
            String releaseGroup = releaseGroup(label);
            if (!releaseGroup.isEmpty() && releaseGroups.add(releaseGroup) && releaseGroups.size() > 1) return true;
            if (label.season() != null && seasons.add(label.season()) && seasons.size() > 1) return true;
            // With at most one explicit season, a missing season can share these coordinates.
            String key = label.number() + ":" + label.part() + ":" + label.isSpecial();
            String previous = editions.putIfAbsent(key, label.edition());
            if (previous != null && !previous.equals(label.edition())) return true;
        }
        return false;
    }

    private static int nextPosition(List<Episode> episodes, int current, int direction) {
        EpisodeLabel selected = episodes.get(current).getLabel();
        Integer season = uniqueSeason(episodes, selected);
        int order = numberedOrder(episodes, selected, season);
        if (order == 0) return current;
        int expected = selected.number() + order * direction;
        int fallback = current;
        for (int index = current + direction; index >= 0 && index < episodes.size(); index += direction) {
            Episode episode = episodes.get(index);
            if (!isNumbered(episode) || !sameTrack(selected, episode.getLabel(), season)) continue;
            EpisodeLabel candidate = episode.getLabel();
            if (selected.number().equals(candidate.number())) {
                if (fallback != current && (!selected.number().equals(episodes.get(fallback).getLabel().number())
                        || selected.part().equals(candidate.part()))) return fallback;
                if (!selected.part().isEmpty() && !candidate.part().isEmpty() && !selected.part().equals(candidate.part())) {
                    if (fallback != current && !episodes.get(fallback).getLabel().part().equals(candidate.part())) return fallback;
                    if (selected.edition().equals(candidate.edition())) return index;
                    if (fallback == current) fallback = index;
                }
                continue;
            }
            // A missing episode or the start of another revision block ends this search.
            if (candidate.number() != expected) return fallback;
            if (fallback != current && (!candidate.number().equals(episodes.get(fallback).getLabel().number())
                    || !candidate.part().equals(episodes.get(fallback).getLabel().part()))) return fallback;
            if (selected.edition().equals(candidate.edition())) return index;
            if (fallback == current) fallback = index;
        }
        return fallback;
    }

    private static boolean isNumbered(Episode episode) {
        if (episode == null || episode.isGeneratedLabel()) return false;
        EpisodeLabel label = episode.getLabel();
        return label.isSingleEpisode() && !label.isAmbiguous() && !label.isPreview()
                && label.number() != null && label.number() > 0;
    }

    private static Integer uniqueSeason(List<Episode> episodes, EpisodeLabel selected) {
        Integer season = null;
        for (Episode episode : episodes) {
            if (!isNumbered(episode)) continue;
            EpisodeLabel candidate = episode.getLabel();
            if (!sameEdition(selected, candidate) || candidate.season() == null) continue;
            if (season != null && !season.equals(candidate.season())) return CONFLICTING_SEASONS;
            season = candidate.season();
        }
        return season;
    }

    private static boolean sameEdition(EpisodeLabel first, EpisodeLabel second) {
        return releaseGroup(first).equals(releaseGroup(second)) && first.isSpecial() == second.isSpecial();
    }

    private static String releaseGroup(EpisodeLabel label) {
        // Revision belongs to an individual file, not to the whole season's release track.
        String group = REVISION_TAG.matcher(label.edition()).replaceAll("");
        return group.startsWith("|") ? group.substring(1) : group;
    }

    private static boolean sameTrack(EpisodeLabel first, EpisodeLabel second, Integer inferredSeason) {
        if (!sameEdition(first, second)) return false;
        if (first.season() != null && second.season() != null) return first.season().equals(second.season());
        if (CONFLICTING_SEASONS.equals(inferredSeason)) return false;
        return first.season() == null && second.season() == null
                || Objects.equals(first.season() == null ? second.season() : first.season(), inferredSeason);
    }

    private static int numberedOrder(List<Episode> episodes, EpisodeLabel selected, Integer inferredSeason) {
        Integer previous = null;
        int order = 0;
        Set<Integer> seen = new HashSet<>();
        for (Episode episode : episodes) {
            if (!isNumbered(episode) || !sameTrack(selected, episode.getLabel(), inferredSeason)) continue;
            Integer number = episode.getLabel().number();
            if (!seen.add(number)) continue;
            if (previous != null && !previous.equals(number)) {
                int direction = Integer.compare(number, previous);
                if (order != 0 && order != direction) return 0;
                order = direction;
            }
            previous = number;
        }
        return order == 0 ? 1 : order;
    }
}
