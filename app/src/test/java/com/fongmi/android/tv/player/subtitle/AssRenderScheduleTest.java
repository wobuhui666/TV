package com.fongmi.android.tv.player.subtitle;

import org.junit.Test;

import static org.junit.Assert.*;

public class AssRenderScheduleTest {
    @Test public void packetRefreshCannotExceedTheWallClockBudget() {
        AssRenderSchedule schedule = new AssRenderSchedule();
        assertTrue(schedule.isDue(0, 100_000, true));
        schedule.scheduled(0, 100_000);
        for (long delta = 0; delta < 33_000; delta += 1_000) {
            assertFalse(schedule.isDue(delta, 100_000 + delta, true));
        }
        assertTrue(schedule.isDue(33_000, 133_000, true));
    }

    @Test public void fasterPlaybackDoesNotMultiplySubtitleRenderRate() {
        AssRenderSchedule schedule = new AssRenderSchedule();
        schedule.scheduled(0, 100_000);
        assertFalse(schedule.isDue(60_000, 120_000, false));
        assertTrue(schedule.isDue(99_000, 133_000, false));
    }

    @Test public void pausedFramesOnlyRenderAfterARefresh() {
        AssRenderSchedule schedule = new AssRenderSchedule();
        schedule.scheduled(50_000, 100_000);
        assertFalse(schedule.isDue(50_000, 200_000, false));
        assertTrue(schedule.isDue(50_000, 200_000, true));
    }

    @Test public void resetMakesSeekAndReenableImmediate() {
        AssRenderSchedule schedule = new AssRenderSchedule();
        schedule.scheduled(5_000_000, 100_000);
        schedule.reset();
        assertTrue(schedule.isDue(0, 101_000, true));
    }

    @Test public void backwardsClockDoesNotBlockFutureFrames() {
        AssRenderSchedule schedule = new AssRenderSchedule();
        schedule.scheduled(0, 100_000);
        assertTrue(schedule.isDue(20_000, 0, false));
    }
}
