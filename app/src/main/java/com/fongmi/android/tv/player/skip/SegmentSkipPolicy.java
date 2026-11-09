package com.fongmi.android.tv.player.skip;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.History;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps community time ranges separate from the user's start/end offsets. */
public final class SegmentSkipPolicy {

    private List<SkipSegment> segments;
    private final Set<SkipSegment> skipped = new HashSet<>();
    private long matchedDurationMs;

    public SegmentSkipPolicy(List<SkipSegment> segments, long matchedDurationMs) {
        this.segments = List.copyOf(segments);
        this.matchedDurationMs = matchedDurationMs;
    }

    /** Provider supplements must not make an already skipped range skip again after a rewind. */
    public void updateSegments(List<SkipSegment> segments) {
        this.segments = List.copyOf(segments);
    }

    /** Revalidated duration replaces the ranges without resetting already consumed markers. */
    public void updateSegments(List<SkipSegment> segments, long matchedDurationMs) {
        updateSegments(segments);
        this.matchedDurationMs = matchedDurationMs;
    }

    public long target(long positionMs, long durationMs, boolean manualIntro, boolean manualCredits) {
        if (positionMs < 0 || durationMs <= 0 || matchedDurationMs <= 0
                || Math.abs(durationMs - matchedDurationMs) > 2_000) return C.TIME_UNSET;
        for (SkipSegment segment : segments) {
            if (skipped.contains(segment) || !isUsable(segment, durationMs)) continue;
            if (segment.type() == SkipSegment.Type.INTRO ? manualIntro : manualCredits) continue;
            // An intro may follow a cold open. Credits may precede a final scene.
            if (positionMs >= segment.startMs() && positionMs < segment.endMs()) {
                skipped.add(segment);
                return segment.endMs();
            }
        }
        return C.TIME_UNSET;
    }

    public static boolean isUsable(SkipSegment segment, long durationMs) {
        if (segment == null || durationMs <= 0 || segment.startMs() < 0
                || segment.endMs() <= segment.startMs() || segment.endMs() > durationMs) return false;
        long length = segment.endMs() - segment.startMs();
        return segment.type() == SkipSegment.Type.INTRO
                ? length <= 300_000 && segment.endMs() < durationMs
                : length <= 1_800_000 && segment.startMs() >= durationMs / 2;
    }

    static boolean hasManualBoundary(long value, String source) {
        return "manual".equalsIgnoreCase(source) || value > 0;
    }

    public long target(long positionMs, long durationMs, History history) {
        return target(positionMs, durationMs,
                history != null && hasManualBoundary(history.getOpening(), history.getOpeningSource()),
                history != null && hasManualBoundary(history.getEnding(), history.getEndingSource()));
    }
}
