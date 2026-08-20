package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SmartSourceSelector {

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
            candidates.add(new Candidate(source, score));
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed());
        return candidates.isEmpty() ? null : candidates.get(0).source;
    }

    public static FlagSelection selectFlag(Vod source, EpisodeTarget target, String preferredFlag, SourceReliabilityStore reliability) {
        if (source == null || target == null) return null;
        List<FlagSelection> matches = new ArrayList<>();
        for (Flag flag : source.getFlags()) {
            Episode episode = findEpisode(flag, target);
            if (episode == null) continue;
            int score = flag.getFlag().equals(preferredFlag) ? 100 : 0;
            score += flag.getEpisodes().size();
            score += reliability == null ? 0 : reliability.score(source);
            matches.add(new FlagSelection(flag, episode, score, flag.getEpisodes().size()));
        }
        matches.sort(Comparator.comparingInt(FlagSelection::score).reversed());
        return matches.isEmpty() ? null : matches.get(0);
    }

    public static Episode findEpisode(Flag flag, EpisodeTarget target) {
        if (flag == null || target == null) return null;
        for (Episode episode : flag.getEpisodes()) if (target.matches(episode)) return episode;
        return null;
    }

    public record FlagSelection(Flag flag, Episode episode, int score, int coverage) {
    }

    private record Candidate(Vod source, int score) {
    }
}
