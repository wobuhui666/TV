package com.fongmi.android.tv.player.subtitle

/** Playback-thread scheduler. Packet and style refreshes share the wall-clock render budget. */
internal class AssRenderSchedule {
    private var lastRealtimeUs = Long.MIN_VALUE
    private var lastPositionUs = Long.MIN_VALUE

    fun reset() {
        lastRealtimeUs = Long.MIN_VALUE
        lastPositionUs = Long.MIN_VALUE
    }

    fun isDue(positionUs: Long, realtimeUs: Long, refresh: Boolean): Boolean {
        if (lastRealtimeUs == Long.MIN_VALUE) return true
        val elapsedUs = realtimeUs - lastRealtimeUs
        if (elapsedUs in 0 until 33_000L) return false
        return refresh || positionUs != lastPositionUs
    }

    fun scheduled(positionUs: Long, realtimeUs: Long) {
        lastPositionUs = positionUs
        lastRealtimeUs = realtimeUs
    }
}
