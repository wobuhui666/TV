package com.fongmi.android.tv.player.skip;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.History;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SegmentSkipPolicyTest {

    private static final long DURATION = 1_800_000;

    private static SkipSegment intro() {
        return new SkipSegment(SkipSegment.Type.INTRO, 80_000, 170_000, "theintrodb");
    }

    @Test
    public void shouldPreserveColdOpenAndSeekOnlyInsideTheIntro() {
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(intro()), DURATION);
        assertEquals(C.TIME_UNSET, policy.target(0, DURATION, false, false));
        assertEquals(C.TIME_UNSET, policy.target(79_999, DURATION, false, false));
        assertEquals(170_000, policy.target(80_000, DURATION, false, false));
    }

    @Test
    public void shouldKeepPostCreditSceneInsteadOfAdvancingTheEpisode() {
        SkipSegment credits = new SkipSegment(SkipSegment.Type.CREDITS, 1_600_000, 1_750_000, "theotherdb");
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(credits), DURATION);
        assertEquals(C.TIME_UNSET, policy.target(1_599_999, DURATION, false, false));
        assertEquals(1_750_000, policy.target(1_610_000, DURATION, false, false));
        assertEquals(C.TIME_UNSET, policy.target(1_760_000, DURATION, false, false));
    }

    @Test
    public void shouldSkipEachRangeOnceSoRewindingCanReplayIt() {
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(intro()), DURATION);
        assertEquals(170_000, policy.target(100_000, DURATION, false, false));
        assertEquals(C.TIME_UNSET, policy.target(100_000, DURATION, false, false));
    }

    @Test
    public void shouldPreserveConsumedIntroWhenCreditsArriveLater() {
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(intro()), DURATION);
        assertEquals(170_000, policy.target(100_000, DURATION, false, false));
        SkipSegment credits = new SkipSegment(SkipSegment.Type.CREDITS, 1_600_000, 1_750_000, "introdb");
        policy.updateSegments(List.of(intro(), credits));
        assertEquals(C.TIME_UNSET, policy.target(100_000, DURATION, false, false));
        assertEquals(1_750_000, policy.target(1_610_000, DURATION, false, false));
        assertEquals(C.TIME_UNSET, policy.target(1_610_000, DURATION, false, false));
    }

    @Test
    public void shouldApplyALateResultOnlyWhileStillInsideItsRange() {
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(), DURATION);
        assertEquals(C.TIME_UNSET, policy.target(100_000, DURATION, false, false));
        policy.updateSegments(List.of(intro()));
        assertEquals(C.TIME_UNSET, policy.target(170_000, DURATION, false, false));
        assertEquals(170_000, policy.target(110_000, DURATION, false, false));
    }

    @Test
    public void shouldNotConsumeTheIntroAgainWhenDurationIsRevalidated() {
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(intro()), DURATION);
        assertEquals(170_000, policy.target(100_000, DURATION, false, false));
        policy.updateSegments(List.of(), DURATION + 5_000);
        assertEquals(C.TIME_UNSET, policy.target(100_000, DURATION + 5_000, false, false));
        policy.updateSegments(List.of(intro()), DURATION + 5_000);
        assertEquals(C.TIME_UNSET, policy.target(100_000, DURATION + 5_000, false, false));
    }

    @Test
    public void shouldIgnoreRangeEndAndResumeAfterTheIntro() {
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(intro()), DURATION);
        assertEquals(C.TIME_UNSET, policy.target(170_000, DURATION, false, false));
        assertEquals(C.TIME_UNSET, policy.target(300_000, DURATION, false, false));
        assertEquals(170_000, policy.target(100_000, DURATION, false, false));
    }

    @Test
    public void shouldHonorManualOffsetsAndExplicitManualReset() {
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(intro()), DURATION);
        History history = new History();
        history.setOpening(0);
        history.setOpeningSource("manual");
        assertEquals(C.TIME_UNSET, policy.target(100_000, DURATION, history));
        history.setOpening(20_000);
        history.setOpeningSource("unknown");
        assertEquals(C.TIME_UNSET, policy.target(100_000, DURATION, history));
    }

    @Test
    public void shouldOverrideOnlyTheManuallyConfiguredCategory() {
        SkipSegment credits = new SkipSegment(SkipSegment.Type.CREDITS, 1_600_000, 1_750_000, "introdb");
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(intro(), credits), DURATION);
        assertEquals(C.TIME_UNSET, policy.target(100_000, DURATION, true, false));
        assertEquals(1_750_000, policy.target(1_610_000, DURATION, true, false));
    }

    @Test
    public void shouldRejectRangesFromAnotherCutOrUnknownDuration() {
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(intro()), DURATION);
        assertEquals(C.TIME_UNSET, policy.target(100_000, C.TIME_UNSET, false, false));
        assertEquals(C.TIME_UNSET, policy.target(100_000, DURATION + 10_000, false, false));
        assertEquals(C.TIME_UNSET, policy.target(-1, DURATION, false, false));
    }

    @Test
    public void shouldAllowAnIntroThatStartsAfterHalfOfTheVideo() {
        SkipSegment lateIntro = new SkipSegment(SkipSegment.Type.INTRO, 1_050_000, 1_150_000, "introdb");
        SegmentSkipPolicy policy = new SegmentSkipPolicy(List.of(lateIntro), DURATION);
        assertEquals(C.TIME_UNSET, policy.target(900_000, DURATION, false, false));
        assertEquals(1_150_000, policy.target(1_050_000, DURATION, false, false));
    }

    @Test
    public void shouldRejectRangesOutsideTheMovieOrAnImplausibleCreditLocation() {
        assertFalse(SegmentSkipPolicy.isUsable(new SkipSegment(SkipSegment.Type.CREDITS,
                1_600_000, DURATION + 1, "introdb"), DURATION));
        assertFalse(SegmentSkipPolicy.isUsable(new SkipSegment(SkipSegment.Type.INTRO,
                100_000, 800_000, "introdb"), DURATION));
        assertFalse(SegmentSkipPolicy.isUsable(new SkipSegment(SkipSegment.Type.CREDITS,
                80_000, 170_000, "introdb"), DURATION));
    }
}
