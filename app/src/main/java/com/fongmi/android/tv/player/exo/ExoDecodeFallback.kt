package com.fongmi.android.tv.player.exo

import androidx.media3.common.C
import com.fongmi.android.tv.player.engine.PlayerEngine

/** A failed track may switch once without changing the other track or the user's mode. */
class ExoDecodeFallback(private var requestedMode: Int) {
    var audioDecode: Int = requestedMode
        private set
    var videoDecode: Int = requestedMode
        private set

    fun setDecode(mode: Int) {
        requestedMode = mode
        reset()
    }

    fun reset(): Boolean {
        val changed = audioDecode != requestedMode || videoDecode != requestedMode
        audioDecode = requestedMode
        videoDecode = requestedMode
        return changed
    }

    fun tryFallback(trackType: Int): Boolean {
        if (requestedMode != PlayerEngine.HARD) return false
        return when (trackType) {
            C.TRACK_TYPE_AUDIO -> {
                if (audioDecode == PlayerEngine.SOFT) return false
                audioDecode = PlayerEngine.SOFT
                true
            }
            C.TRACK_TYPE_VIDEO -> {
                if (videoDecode == PlayerEngine.SOFT) return false
                videoDecode = PlayerEngine.SOFT
                true
            }
            else -> false
        }
    }
}
