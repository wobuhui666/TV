package com.fongmi.android.tv.player.subtitle

import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssTex

/** Owns newly returned JNI frames, before they are shared with the main thread. */
internal class AssFrameCache {
    var frame: AssFrame? = null
        private set
    private var width = 0
    private var height = 0

    fun clear() {
        frame = null
        width = 0
        height = 0
    }

    fun update(next: AssFrame?, width: Int, height: Int): Boolean {
        if (next?.changed == 0 || next == null && frame == null) return false
        frame = if (next?.changed == 1 && this.width == width && this.height == height) {
            reusePositionOnlyBitmaps(frame, next)
        } else next
        this.width = width
        this.height = height
        return true
    }

    private fun reusePositionOnlyBitmaps(previous: AssFrame?, next: AssFrame): AssFrame {
        val before = previous?.images ?: return next
        val after = next.images ?: return next
        if (before.size != after.size) return next
        // libass ass_render_frame: changed=1 means only image positions may have changed.
        // Keep glyph textures across moves; changed=2 always installs the new pixels.
        if (after.indices.any { index ->
                val old = before[index]
                val current = after[index]
                old.w != current.w || old.h != current.h || old.color != current.color ||
                    (old.bitmap == null) != (current.bitmap == null)
            }) return next
        val reused = AssFrame(Array(after.size) { index ->
            val current = after[index]
            AssTex(current.x, current.y, current.w, current.h, current.color, before[index].bitmap, current.tex)
        }, next.changed)
        // Pinned ass-kt 0.5.1 creates a separate alpha Bitmap copy for every image, even
        // for position-only frames. These new copies were never shared with a View.
        // Release only the discarded copies; previously displayed bitmaps stay alive.
        after.indices.forEach { index ->
            val duplicate = after[index].bitmap
            if (duplicate !== before[index].bitmap) duplicate?.recycle()
        }
        return reused
    }
}
