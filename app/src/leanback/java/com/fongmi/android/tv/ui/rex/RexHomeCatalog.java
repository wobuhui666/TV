package com.fongmi.android.tv.ui.rex;

import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Rex 栏目卡与推荐页的内容策略。 */
public final class RexHomeCatalog {

    public static final int PER_SECTION_CAP = 20;
    public static final int MIXED_TOTAL_CAP = 100;
    public static final int SECTION_PREVIEW_CAP = 12;

    private RexHomeCatalog() {
    }

    /** 每栏目保留一组预览，横图代表作置顶，其余影片保持来源顺序。 */
    public static List<RexFeedResult> spotlights(List<RexFeedResult> results) {
        List<RexFeedResult> spotlights = new ArrayList<>();
        if (results == null) return spotlights;
        for (RexFeedResult result : results) {
            List<Vod> valid = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Vod item : result.getItems()) {
                if (item != null && !item.getId().isEmpty() && seen.add(item.getId())) valid.add(item);
            }
            Vod chosen = pick(valid);
            if (chosen == null) continue;
            int cap = Math.min(SECTION_PREVIEW_CAP, result.getSection().getLimit());
            List<Vod> preview = new ArrayList<>();
            preview.add(chosen);
            for (Vod item : valid) {
                if (preview.size() >= cap) break;
                if (item != chosen) preview.add(item);
            }
            spotlights.add(new RexFeedResult(result.getSection(), preview));
        }
        return spotlights;
    }

    static Vod pick(List<Vod> items) {
        if (items == null || items.isEmpty()) return null;
        for (Vod item : items) if (hasLandscape(item)) return item;
        return items.get(0);
    }

    static boolean hasLandscape(Vod item) {
        return item != null && !item.getBackdrop().isEmpty() && !item.getBackdrop().equals(item.getPic());
    }

    /** 混合推荐流：按栏目顺序拼接、每栏目取前 N、按 id 去重、总量上限 {@link #MIXED_TOTAL_CAP}。 */
    public static List<Vod> mixed(List<RexFeedResult> results) {
        List<Vod> mixed = new ArrayList<>();
        if (results == null) return mixed;
        Set<String> seen = new LinkedHashSet<>();
        for (RexFeedResult result : results) {
            int cap = Math.max(1, Math.min(result.getSection().getLimit(), PER_SECTION_CAP));
            int taken = 0;
            for (Vod item : result.getItems()) {
                if (taken >= cap || mixed.size() >= MIXED_TOTAL_CAP) break;
                if (item == null || item.getId().isEmpty()) continue;
                if (seen.add(item.getId())) {
                    mixed.add(item);
                    taken++;
                }
            }
            if (mixed.size() >= MIXED_TOTAL_CAP) break;
        }
        return mixed;
    }
}
