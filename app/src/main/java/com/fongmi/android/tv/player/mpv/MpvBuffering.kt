package com.fongmi.android.tv.player.mpv

/** Convert mpv's approximate forward-cache duration to the Media3 timeline. */
internal object MpvBuffering {
    @JvmStatic
    fun bufferedPositionMs(positionMs: Long, durationMs: Long, cacheSeconds: Double): Long {
        val position = positionMs.coerceAtLeast(0).let {
            if (durationMs >= 0) it.coerceAtMost(durationMs) else it
        }
        if (!cacheSeconds.isFinite() || cacheSeconds <= 0) return position
        val cacheMs = (cacheSeconds * 1000).toLong().coerceAtLeast(0)
        val end = position + cacheMs.coerceAtMost(Long.MAX_VALUE - position)
        return if (durationMs >= 0) end.coerceAtMost(durationMs) else end
    }
}
