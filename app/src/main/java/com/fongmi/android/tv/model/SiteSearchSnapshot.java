package com.fongmi.android.tv.model;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable multi-site search state in the order frozen when the search starts. */
public final class SiteSearchSnapshot {

    private final List<Entry> entries;
    private final List<Vod> all;

    SiteSearchSnapshot(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        List<Vod> grouped = new ArrayList<>();
        for (Entry entry : entries) grouped.addAll(entry.items());
        this.all = Collections.unmodifiableList(grouped);
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<Vod> all() {
        return all;
    }

    public record Entry(Site site, State state, List<Vod> items, long elapsedMs, String error) {
        public Entry {
            items = Collections.unmodifiableList(new ArrayList<>(items));
            error = error == null ? "" : error;
        }
    }

    public enum State {LOADING, SUCCESS, EMPTY, FAILURE}
}
