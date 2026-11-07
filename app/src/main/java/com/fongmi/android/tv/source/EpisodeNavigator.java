package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        if (selected != null && !selected.isGeneratedLabel() && (selected.getLabel().isVariety()
                || isNumbered(selected) && isVarietyPlaylist(episodes))) {
            int direction = Integer.signum(offset);
            for (long step = 0; step < Math.abs((long) offset); step++) {
                int next = nextVarietyPosition(episodes, position, direction);
                if (next == position) break;
                position = next;
            }
            return episodes.get(position);
        }
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

    private static boolean isVarietyPlaylist(List<Episode> episodes) {
        for (Episode episode : episodes) {
            if (hasVarietyCoordinate(episode) && episode.getLabel().isVariety()) return true;
        }
        return false;
    }

    private static boolean hasVarietyCoordinate(Episode episode) {
        if (episode == null || episode.isGeneratedLabel()) return false;
        EpisodeLabel label = episode.getLabel();
        return !label.isAmbiguous() && (label.hasAirDate() || label.number() != null && label.number() > 0);
    }

    private static boolean sameVarietyEdition(EpisodeLabel first, EpisodeLabel second) {
        return sameEdition(first, second) && first.isPreview() == second.isPreview()
                && first.variant().equals(second.variant()) && first.hasAirDate() == second.hasAirDate();
    }

    private static Integer varietySeason(List<Episode> episodes, EpisodeLabel selected) {
        Integer season = null;
        for (Episode episode : episodes) {
            if (!hasVarietyCoordinate(episode)) continue;
            EpisodeLabel candidate = episode.getLabel();
            if (!sameVarietyEdition(selected, candidate) || candidate.season() == null) continue;
            if (season != null && !season.equals(candidate.season())) return CONFLICTING_SEASONS;
            season = candidate.season();
        }
        return season;
    }

    private static boolean sameVarietyTrack(EpisodeLabel selected, EpisodeLabel candidate, Integer season) {
        return sameVarietyEdition(selected, candidate) && sameTrack(selected, candidate, season);
    }

    private static String varietyCoordinate(EpisodeLabel label) {
        return label.hasAirDate() ? label.airDate() : String.format(Locale.ROOT, "%010d", label.number());
    }

    private static boolean sameVarietyCoordinate(EpisodeLabel first, EpisodeLabel second) {
        return varietyCoordinate(first).equals(varietyCoordinate(second))
                && (first.number() == null || second.number() == null || first.number().equals(second.number()));
    }

    private static int partRank(String part) {
        if (part.equals("upper")) return 1;
        if (part.equals("middle")) return 2;
        if (part.equals("lower")) return 3;
        if (part.startsWith("part")) {
            try {
                return Integer.parseInt(part.substring(4));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** Dates advance through actual source entries; issue numbers never become calendar arithmetic. */
    private static int nextVarietyPosition(List<Episode> episodes, int current, int direction) {
        Episode selectedEpisode = episodes.get(current);
        if (!hasVarietyCoordinate(selectedEpisode)) return current;
        EpisodeLabel selected = selectedEpisode.getLabel();
        Integer season = varietySeason(episodes, selected);
        int order = varietyOrder(episodes, selected, season);
        if (order == 0) return current;

        int partOrder = varietyPartOrder(episodes, selected, season);
        if (partOrder == 0) return current;
        int selectedPart = partRank(selected.part());
        String nextPart = null;
        int nearestPart = Integer.MAX_VALUE;
        String nextCoordinate = null;
        String coordinate = varietyCoordinate(selected);
        for (Episode episode : episodes) {
            if (!hasVarietyCoordinate(episode) || !sameVarietyTrack(selected, episode.getLabel(), season)) continue;
            EpisodeLabel candidate = episode.getLabel();
            if (sameVarietyCoordinate(selected, candidate)) {
                int rank = partRank(candidate.part());
                int distance = (rank - selectedPart) * partOrder * direction;
                // Whole episodes and split episodes are separate releases of a coordinate.
                if (selectedPart != 0 && rank != 0 && distance > 0 && distance < nearestPart) {
                    nearestPart = distance;
                    nextPart = candidate.part();
                }
                continue;
            }
            String candidateCoordinate = varietyCoordinate(candidate);
            if (candidateCoordinate.compareTo(coordinate) * order * direction <= 0) continue;
            if (!selected.hasAirDate() && (long) candidate.number() != (long) selected.number() + order * direction) continue;
            if ((selectedPart == 0) != candidate.part().isEmpty()) continue;
            if (nextCoordinate == null || candidateCoordinate.compareTo(nextCoordinate) * order * direction < 0) {
                nextCoordinate = candidateCoordinate;
            }
        }
        if (nextPart != null) return chooseVarietyCandidate(episodes, current, selected, season, coordinate, nextPart);
        if (nextCoordinate == null) return current;

        int edge = partOrder * direction > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        nextPart = "";
        if (selectedPart != 0) {
            for (Episode episode : episodes) {
                if (!hasVarietyCoordinate(episode) || !sameVarietyTrack(selected, episode.getLabel(), season)) continue;
                EpisodeLabel candidate = episode.getLabel();
                if (!varietyCoordinate(candidate).equals(nextCoordinate)) continue;
                int rank = partRank(candidate.part());
                if (rank != 0 && (partOrder * direction > 0 ? rank < edge : rank > edge)) {
                    edge = rank;
                    nextPart = candidate.part();
                }
            }
        }
        return chooseVarietyCandidate(episodes, current, selected, season, nextCoordinate, nextPart);
    }

    private static int chooseVarietyCandidate(List<Episode> episodes, int current, EpisodeLabel selected,
                                              Integer season, String coordinate, String part) {
        int chosen = current;
        int best = -1;
        boolean ambiguous = false;
        Integer knownNumber = null;
        for (int index = 0; index < episodes.size(); index++) {
            Episode episode = episodes.get(index);
            if (!hasVarietyCoordinate(episode) || !sameVarietyTrack(selected, episode.getLabel(), season)) continue;
            EpisodeLabel candidate = episode.getLabel();
            if (!varietyCoordinate(candidate).equals(coordinate) || !candidate.part().equals(part)) continue;
            if (coordinate.equals(varietyCoordinate(selected)) && !sameVarietyCoordinate(selected, candidate)) continue;
            if (knownNumber != null && candidate.number() != null && !knownNumber.equals(candidate.number())) return current;
            if (candidate.number() != null) knownNumber = candidate.number();
            int score = selected.edition().equals(candidate.edition()) ? 1 : 0;
            if (score > best) {
                chosen = index;
                best = score;
                ambiguous = false;
            } else if (score == best && !episodes.get(chosen).getUrl().equals(episode.getUrl())) {
                ambiguous = true;
            }
        }
        return ambiguous ? current : chosen;
    }

    private static int varietyOrder(List<Episode> episodes, EpisodeLabel selected, Integer season) {
        String previous = null;
        int order = 0;
        Set<String> seen = new HashSet<>();
        for (Episode episode : episodes) {
            if (!hasVarietyCoordinate(episode) || !sameVarietyTrack(selected, episode.getLabel(), season)) continue;
            String coordinate = varietyCoordinate(episode.getLabel());
            if (!seen.add(coordinate)) continue;
            if (previous != null) {
                int nextOrder = Integer.signum(coordinate.compareTo(previous));
                if (order != 0 && order != nextOrder) return 0;
                order = nextOrder;
            }
            previous = coordinate;
        }
        return order == 0 ? 1 : order;
    }

    private static int varietyPartOrder(List<Episode> episodes, EpisodeLabel selected, Integer season) {
        Map<String, Integer> previous = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int order = 0;
        for (Episode episode : episodes) {
            if (!hasVarietyCoordinate(episode) || !sameVarietyTrack(selected, episode.getLabel(), season)) continue;
            EpisodeLabel label = episode.getLabel();
            int rank = partRank(label.part());
            String coordinate = varietyCoordinate(label);
            if (rank == 0 || !seen.add(coordinate + ":" + rank)) continue;
            Integer prior = previous.put(coordinate, rank);
            if (prior != null) {
                int nextOrder = Integer.compare(rank, prior);
                if (order != 0 && order != nextOrder) return 0;
                order = nextOrder;
            }
        }
        return order == 0 ? 1 : order;
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
