package com.fongmi.android.tv.player.subtitle

import io.github.peerless2012.ass.AssFrame

/** Suppresses unchanged and repeated empty output without altering native frames or bitmaps. */
internal class AssFrameCache {
    var frame: AssFrame? = null
        private set

    fun clear() {
        frame = null
    }

    fun update(next: AssFrame?): Boolean {
        if (next?.changed == 0 || next == null && frame == null) return false
        frame = next
        return true
    }
}
