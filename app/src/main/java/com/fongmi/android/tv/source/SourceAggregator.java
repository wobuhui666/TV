package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SourceAggregator {

    public List<Vod> aggregate(Collection<Vod> items) {
        Map<String, Vod> groups = new LinkedHashMap<>();
        if (items == null) return new ArrayList<>();
        for (Vod item : items) add(groups, item);
        return new ArrayList<>(groups.values());
    }

    public void mergeInto(List<Vod> aggregate, Collection<Vod> items) {
        if (aggregate == null || items == null) return;
        Map<String, Vod> groups = new LinkedHashMap<>();
        for (Vod item : aggregate) add(groups, item);
        for (Vod item : items) add(groups, item);
        aggregate.clear();
        aggregate.addAll(groups.values());
    }

    public int sourceCount(Vod item) {
        return item == null ? 0 : item.getSourceOptions().size();
    }

    private void add(Map<String, Vod> groups, Vod item) {
        if (item == null || item.getName().isEmpty()) return;
        List<Vod> options = item.getSourceOptions();
        for (Vod option : options) {
            if (option == null || option.getName().isEmpty()) continue;
            String key = MediaMatcher.identity(option).key();
            Vod representative = groups.get(key);
            if (representative == null) {
                representative = option;
                representative.setSourceCandidates(new ArrayList<>());
                groups.put(key, representative);
            } else {
                addCandidate(representative, option);
            }
        }
    }

    private void addCandidate(Vod representative, Vod candidate) {
        if (sameSource(representative, candidate)) return;
        for (Vod option : representative.getSourceCandidates()) if (sameSource(option, candidate)) return;
        candidate.setSourceCandidates(null);
        representative.getSourceCandidates().add(candidate);
    }

    private boolean sameSource(Vod first, Vod second) {
        return first.getSiteKey().equals(second.getSiteKey()) && first.getId().equals(second.getId());
    }
}
