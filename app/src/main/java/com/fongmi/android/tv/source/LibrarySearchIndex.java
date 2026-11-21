package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A work owns its resources. Arrival order never changes the order of known works. */
public final class LibrarySearchIndex {
    public record Work(Vod item, List<Vod> resources) { }
    public record Snapshot(List<Work> works, List<Vod> supplements, int resourceCount) { }
    private final Map<String, CatalogEntry> works = new LinkedHashMap<>();
    private final Map<String, Attachment> sources = new LinkedHashMap<>();
    private final Map<SourceText, LibrarySourceMatcher.PreparedSource> preparedSources = new LinkedHashMap<>();
    private Snapshot cached;

    public synchronized void clear() { works.clear(); sources.clear(); preparedSources.clear(); cached = null; }
    public synchronized void addWorks(List<Vod> items) {
        if (items != null) for (Vod item : items) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (item == null || item.getId().isEmpty()) continue;
            if (!works.containsKey(item.getId())) {
                works.put(item.getId(), new CatalogEntry(item, LibrarySourceMatcher.prepareWork(item)));
                cached = null;
            }
        }
    }
    public synchronized void addSources(List<Vod> items) {
        if (items != null) for (Vod item : items) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (item != null && (!item.getId().isEmpty() || item.isAction()) && !item.getSiteKey().isEmpty()) {
                String key = LibraryIdentity.sourceKey(item);
                if (!sources.containsKey(key)) {
                    // A source may return a folder of versions or an account action from search.
                    // Keep it reachable without claiming it is a playable resource of a TMDB work.
                    LibrarySourceMatcher.PreparedSource prepared = null;
                    if (!item.isFolder() && !item.isAction()) {
                        // Share prepared evidence while preserving whether a source already has playback details.
                        SourceText text = new SourceText(item.getName(), item.getYear(), item.getTypeName(), LibrarySourceMatcher.hasPlayback(item));
                        prepared = preparedSources.computeIfAbsent(text, unused -> LibrarySourceMatcher.prepareSource(item));
                    }
                    sources.put(key, new Attachment(item, prepared)); cached = null;
                }
            }
        }
    }

    public List<Work> works() { return snapshot().works(); }

    public List<Vod> supplements() { return snapshot().supplements(); }

    /** Compare each source/work pair once; pagination can still make an earlier match ambiguous. */
    public synchronized Snapshot snapshot() {
        if (cached != null) return cached;
        List<CatalogEntry> orderedWorks = new ArrayList<>(works.values());
        Map<String, List<Vod>> attached = new LinkedHashMap<>();
        for (String key : works.keySet()) attached.put(key, new ArrayList<>());
        List<Vod> supplements = new ArrayList<>();
        int count = 0;
        for (Attachment source : sources.values()) {
            if (source.prepared == null) { supplements.add(source.item); continue; }
            while (source.compared < orderedWorks.size()) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                CatalogEntry work = orderedWorks.get(source.compared);
                LibrarySourceMatcher.Match match = LibrarySourceMatcher.match(source.prepared, work.prepared);
                source.compared++;
                if (!match.matches()) continue;
                if (match.score() > source.best) { source.chosen = work.item; source.best = match.score(); source.ambiguous = false; }
                else if (match.score() == source.best) source.ambiguous = true;
            }
            if (source.chosen != null && !source.ambiguous) { attached.get(source.chosen.getId()).add(source.item); count++; }
            else supplements.add(source.item);
        }
        List<Work> result = new ArrayList<>();
        for (CatalogEntry work : orderedWorks) result.add(new Work(work.item, List.copyOf(attached.get(work.item.getId()))));
        return cached = new Snapshot(List.copyOf(result), List.copyOf(supplements), count);
    }

    private record CatalogEntry(Vod item, LibrarySourceMatcher.PreparedWork prepared) { }
    private record SourceText(String title, String year, String type, boolean hasPlayback) { }

    private static final class Attachment {
        final Vod item;
        final LibrarySourceMatcher.PreparedSource prepared;
        int compared, best = -1;
        Vod chosen;
        boolean ambiguous;
        Attachment(Vod item, LibrarySourceMatcher.PreparedSource prepared) { this.item = item; this.prepared = prepared; }
    }
}
