package com.fongmi.android.tv.bean;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Local shelf snapshot; an unsuccessful read must never masquerade as an empty collection. */
public final class PosterKeepShelfState {

    public static final int MAX_POSTERS = 20;

    private List<Keep> items = List.of();
    private long generation;
    private int cid;
    private boolean enabled;
    private boolean failed;
    private boolean more;
    private boolean closed;

    public Request begin(int cid, boolean enabled) {
        invalidate();
        if (this.cid != cid || !enabled) clear();
        this.cid = cid;
        this.enabled = enabled && !closed;
        return new Request(generation, cid);
    }

    public boolean isCurrent(Request request, int currentCid, boolean currentlyEnabled) {
        return !closed && enabled && currentlyEnabled && request.generation() == generation
                && request.cid() == cid && request.cid() == currentCid;
    }

    public boolean complete(Request request, int currentCid, boolean currentlyEnabled, List<Keep> result) {
        if (!isCurrent(request, currentCid, currentlyEnabled)) return false;
        List<Keep> available = new ArrayList<>();
        for (Keep item : result) {
            if (item != null && belongsToShelf(item, request.cid())) available.add(item);
        }
        available.sort(Comparator.comparingLong(Keep::getCreateTime).reversed().thenComparing(Keep::getKey));
        more = available.size() > MAX_POSTERS;
        items = List.copyOf(available.subList(0, Math.min(available.size(), MAX_POSTERS)));
        failed = false;
        return true;
    }

    public boolean fail(Request request, int currentCid, boolean currentlyEnabled) {
        if (!isCurrent(request, currentCid, currentlyEnabled)) return false;
        failed = true;
        return true;
    }

    public static boolean belongsToShelf(Keep item, int cid) {
        return item.getType() == Keep.TYPE_DISCOVER || item.getType() == Keep.TYPE_VOD && item.getCid() == cid;
    }

    public List<Keep> getItems() { return items; }
    public int getCid() { return cid; }
    public boolean hasMore() { return more; }
    public boolean hasFailed() { return failed; }
    public boolean isVisible() { return enabled && (!items.isEmpty() || failed); }

    public void invalidate() {
        generation++;
    }

    public void close() {
        closed = true;
        enabled = false;
        invalidate();
        clear();
    }

    private void clear() {
        items = List.of();
        more = false;
        failed = false;
    }

    public record Request(long generation, int cid) {
    }
}
