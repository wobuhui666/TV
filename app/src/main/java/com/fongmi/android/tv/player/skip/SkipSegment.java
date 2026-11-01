package com.fongmi.android.tv.player.skip;

/** Absolute boundaries on the current video's timeline, in milliseconds. */
public record SkipSegment(Type type, long startMs, long endMs, String source) {

    public enum Type { INTRO, CREDITS }
}
