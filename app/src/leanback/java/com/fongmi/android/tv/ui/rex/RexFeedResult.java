package com.fongmi.android.tv.ui.rex;

import com.fongmi.android.tv.bean.RexSection;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.Diffable;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** 一个栏目的加载产物：栏目定义 + 条目（列表保持栏目顺序）。 */
public final class RexFeedResult implements Diffable<RexFeedResult> {

    private final RexSection section;
    private final List<Vod> items;

    public RexFeedResult(RexSection section, List<Vod> items) {
        this.section = section;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    public RexSection getSection() {
        return section;
    }

    public List<Vod> getItems() {
        return items;
    }

    @Override
    public boolean isSameItem(RexFeedResult other) {
        return section != null && other != null && section.getKey().equals(other.getSection().getKey());
    }

    @Override
    public boolean isSameContent(RexFeedResult other) {
        return other != null && section.getLabelRes() == other.section.getLabelRes()
                && sameItems(items, other.items);
    }

    /** Compare every visible field, including artwork and subtitles of the smaller cards. */
    public static boolean sameItems(List<Vod> first, List<Vod> second) {
        if (first == second) return true;
        if (first == null || second == null || first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (!sameContent(first.get(i), second.get(i))) return false;
        return true;
    }

    public static boolean sameContent(Vod first, Vod second) {
        if (first == second) return true;
        return first != null && second != null && first.isSameItem(second) && first.isSameContent(second)
                && first.getBackdrop().equals(second.getBackdrop()) && first.getYear().equals(second.getYear())
                && first.getGenreText().equals(second.getGenreText()) && first.getTypeName().equals(second.getTypeName())
                && first.getContent().equals(second.getContent());
    }
}
