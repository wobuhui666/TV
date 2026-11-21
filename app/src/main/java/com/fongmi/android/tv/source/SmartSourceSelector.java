package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Trans;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class SmartSourceSelector {

    private static final Pattern MOVIE_CUT = Pattern.compile("(?i)导演剪辑|導演剪輯|导剪|導剪|加长|加長|未删减|未刪減|剧场版|劇場版|影院版|院线版|院線版|director(?:['’]s)?[ ._-]*cut|extended|uncut|theatrical|remaster(?:ed)?");
    private static final Pattern SUBTITLE_FILE = Pattern.compile("(?i)\\.(?:srt|ass|ssa|vtt|sub|idx|lrc)(?=\\s*(?:$|[\\[（(【]))");
    private static final Pattern RESOLUTION_TAG = Pattern.compile("(?:^|\\|)(?:16k|8k|4k|[0-9]{3,4}[pi]|uhd|fhd)(?=\\||$)");

    private SmartSourceSelector() {
    }

    public static Vod selectSource(List<Vod> sources, EpisodeTarget target, String currentSite, SourceReliabilityStore reliability) {
        return selectSource(sources, target, currentSite, reliability, "");
    }

    public static Vod selectSource(List<Vod> sources, EpisodeTarget target, String currentSite, SourceReliabilityStore reliability, String preferredFlag) {
        if (sources == null || target == null) return null;
        List<Candidate> candidates = new ArrayList<>();
        for (Vod source : sources) {
            if (source == null) continue;
            FlagSelection flag = selectFlag(source, target, preferredFlag, reliability);
            boolean detailPending = source.getFlags().isEmpty();
            if (flag == null && !detailPending) continue;
            boolean sameSite = source.getSiteKey().equals(currentSite);
            int reliabilityScore = reliability == null ? 0 : reliability.score(source);
            int coverage = flag == null ? 0 : flag.coverage;
            int score = (sameSite ? 1000 : 0) + coverage * 10 + source.getFlags().size() + reliabilityScore;
            if (flag != null) score += target.score(flag.episode()) * 1000;
            candidates.add(new Candidate(source, score));
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed());
        return candidates.isEmpty() ? null : candidates.get(0).source;
    }

    public static FlagSelection selectFlag(Vod source, EpisodeTarget target, String preferredFlag, SourceReliabilityStore reliability) {
        if (source == null || target == null) return null;
        List<FlagSelection> matches = new ArrayList<>();
        for (Flag flag : source.getFlags()) {
            Episode episode = findEpisode(flag, target, MediaMatcher.identity(source).getSeason());
            if (episode == null) continue;
            int score = flag.getFlag().equals(preferredFlag) ? 100 : 0;
            score += Math.min(flag.getEpisodes().size(), 1000);
            score += target.score(episode) * 1000;
            score += reliability == null ? 0 : reliability.score(source);
            matches.add(new FlagSelection(flag, episode, score, flag.getEpisodes().size()));
        }
        matches.sort(Comparator.comparingInt(FlagSelection::score).reversed());
        return matches.isEmpty() ? null : matches.get(0);
    }

    public static Episode findEpisode(Flag flag, EpisodeTarget target) {
        return findEpisode(flag, target, null);
    }

    public static Episode findEpisode(Flag flag, EpisodeTarget target, Integer sourceSeason) {
        if (flag == null || target == null) return null;
        List<Episode> matches = new ArrayList<>();
        int best = -1;
        for (Episode episode : flag.getEpisodes()) {
            if (!target.matches(episode, sourceSeason)) continue;
            int score = target.score(episode);
            if (score > best) {
                matches.clear();
                matches.add(episode);
                best = score;
            } else if (score == best) matches.add(episode);
        }
        if (matches.isEmpty()) return null;
        Episode selected = matches.get(0);
        String selectedUrl = selected.getUrl();
        if (matches.stream().allMatch(episode -> selectedUrl.equals(episode.getUrl()))) return selected;
        int highest = 0;
        boolean ambiguous = false;
        String edition = null;
        for (Episode episode : matches) {
            int resolution = knownResolution(episode);
            if (resolution == 0) return null;
            String remainingEdition = RESOLUTION_TAG.matcher(episode.getLabel().edition()).replaceAll("").replaceAll("^\\||\\|$", "");
            if (edition != null && !edition.equals(remainingEdition)) return null;
            edition = remainingEdition;
            if (resolution > highest) {
                selected = episode;
                highest = resolution;
                ambiguous = false;
            } else if (resolution == highest && !selected.getUrl().equals(episode.getUrl())) ambiguous = true;
        }
        return ambiguous ? null : selected;
    }

    /** Only explicit, consistent resolution tags can settle an otherwise equal content identity. */
    private static int knownResolution(Episode episode) {
        if (SUBTITLE_FILE.matcher(episode.getName()).find()) return 0;
        int resolution = 0;
        for (String tag : episode.getLabel().edition().split("\\|")) {
            int value = switch (tag) {
                case "16k", "8640p", "8640i" -> 8640;
                case "8k", "4320p", "4320i" -> 4320;
                case "4k", "2160p", "2160i" -> 2160;
                case "1080p", "1080i" -> 1080;
                case "720p", "720i" -> 720;
                default -> 0;
            };
            if (value == 0 && tag.matches("[0-9]{3,4}[pi]")) return 0;
            if (value > 0) {
                if (resolution != 0 && resolution != value) return 0;
                resolution = value;
            }
        }
        return resolution;
    }

    /**
     * Supplies a season only for bare labels. Explicit file coordinates always remain authoritative.
     * A mixed-season catalogue cannot identify the season of an unmarked file by list position.
     */
    public static Integer implicitEpisodeSeason(Vod source) {
        if (source == null) return null;
        Integer declared = MediaMatcher.identity(source).getSeason();
        Set<Integer> labelled = new LinkedHashSet<>();
        for (Flag flag : source.getFlags()) for (Episode episode : flag.getEpisodes()) {
            EpisodeLabel label = episode.getLabel();
            if (!episode.isGeneratedLabel() && label.isSingleEpisode() && !label.isPreview()
                    && !label.isSpecial() && label.season() != null) labelled.add(label.season());
        }
        if (declared != null) {
            return labelled.stream().allMatch(declared::equals) ? declared : null;
        }
        // Unlabelled catalogues can be the implicit first season. Once explicit season files
        // exist, bare ordinals do not establish which season they belong to.
        return labelled.isEmpty() ? 1 : null;
    }

    /** A film with several cuts or files needs an explicit choice, unless its saved URL still exists. */
    public static FlagSelection selectMovie(Vod source, String savedUrl, String preferredFlag) {
        if (source == null) return null;
        List<Flag> flags = new ArrayList<>(source.getFlags());
        flags.sort(Comparator.comparingInt(flag -> flag.getFlag().equals(preferredFlag) ? 0 : 1));
        if (savedUrl != null && !savedUrl.isEmpty()) {
            for (Flag flag : flags) for (Episode episode : flag.getEpisodes()) {
                if (savedUrl.equals(episode.getUrl()) && !episode.getLabel().isPreview()) {
                    return new FlagSelection(flag, episode, 1000, flag.getEpisodes().size());
                }
            }
        }
        FlagSelection selected = null;
        Set<String> cuts = new LinkedHashSet<>();
        for (Flag flag : flags) {
            List<Episode> content = flag.getEpisodes().stream().filter(episode ->
                    !episode.getUrl().isEmpty() && !episode.getLabel().isPreview() && !episode.getLabel().isSpecial()).toList();
            if (content.isEmpty()) continue;
            // A list can contain separate edits, parts, or language versions of the film.
            if (content.stream().map(Episode::getUrl).distinct().count() != 1) return null;
            Episode episode = content.get(0);
            java.util.regex.Matcher cut = MOVIE_CUT.matcher(episode.getName());
            cuts.add(cut.find() ? cut.group().toLowerCase(java.util.Locale.ROOT) : "");
            if (cuts.size() > 1) return null;
            if (selected == null) selected = new FlagSelection(flag, episode, 0, flag.getEpisodes().size());
        }
        return selected;
    }

    /** Keeps an explicit library choice when a source refreshes its signed playback parameters. */
    public static FlagSelection selectLibraryEpisode(Vod source, String url, String name, String preferredFlag) {
        if (source == null) return null;
        boolean selectedLine = preferredFlag != null && !preferredFlag.isEmpty();
        List<Flag> flags = source.getFlags();
        if (selectedLine) flags = flags.stream().filter(flag -> flag.getFlag().equals(preferredFlag)).toList();
        // A removed or renamed selected line cannot transfer the choice to another line.
        if (url != null && !url.isEmpty()) {
            FlagSelection exact = null, named = null;
            boolean differentNames = false;
            for (Flag flag : flags) for (Episode episode : flag.getEpisodes()) {
                if (!url.equals(episode.getUrl())) continue;
                FlagSelection match = new FlagSelection(flag, episode, 1000, flag.getEpisodes().size());
                if (exact == null) exact = match;
                else if (!episode.getName().equals(exact.episode().getName())) differentNames = true;
                if (name != null && name.equals(episode.getName()) && named == null) named = match;
            }
            if (named != null) return named;
            if (exact != null) return differentNames ? null : exact;
        }
        if (!selectedLine || name == null || name.isEmpty()) return null;
        FlagSelection selected = null;
        for (Flag flag : flags) for (Episode episode : flag.getEpisodes()) {
            if (!name.equals(episode.getName()) || episode.getUrl().isEmpty()) continue;
            if (selected != null && !episode.getUrl().equals(selected.episode().getUrl())) return null;
            if (selected == null) selected = new FlagSelection(flag, episode, 600, flag.getEpisodes().size());
        }
        return selected;
    }

    /** Explicit list selections never fall back to a saved position or an unrelated episode. */
    public static FlagSelection selectExplicitEpisode(Vod source, String value, String preferredFlag) {
        if (source == null || value == null || value.isEmpty()) return null;
        Flag preferred = source.getFlags().stream().filter(flag -> flag.getFlag().equals(preferredFlag)).findFirst().orElse(null);
        if (preferred != null) for (Episode episode : preferred.getEpisodes()) {
            if (episode.getUrl().equals(value)) return new FlagSelection(preferred, episode, 1000, preferred.getEpisodes().size());
        }
        for (Flag flag : source.getFlags()) for (Episode episode : flag.getEpisodes()) {
            if (episode.getUrl().equals(value)) return new FlagSelection(flag, episode, 1000, flag.getEpisodes().size());
        }

        EpisodeTarget target = new EpisodeTarget(null, Trans.s2t(value));
        Integer season = MediaMatcher.identity(source).getSeason();
        FlagSelection selected = null;
        boolean ambiguous = false;
        for (Flag flag : source.getFlags()) for (Episode episode : flag.getEpisodes()) {
            if (!target.matches(episode, season)) continue;
            int score = target.score(episode);
            if (selected == null || score > selected.score()) {
                selected = new FlagSelection(flag, episode, score, flag.getEpisodes().size());
                ambiguous = false;
            } else if (score == selected.score()) {
                if (!episode.getUrl().equals(selected.episode().getUrl())) ambiguous = true;
                else if (flag == preferred) selected = new FlagSelection(flag, episode, score, flag.getEpisodes().size());
            }
        }
        return ambiguous ? null : selected;
    }

    public record FlagSelection(Flag flag, Episode episode, int score, int coverage) {
    }

    private record Candidate(Vod source, int score) {
    }
}
