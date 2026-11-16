package com.fongmi.android.tv.test

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.player.PlayerManager
import com.fongmi.android.tv.ui.dialog.SubtitleDialog
import com.fongmi.android.tv.ui.dialog.TrackDialog

/** Real texture and file-picker owner, available only in debug APKs. */
class MpvRegressionActivity : FragmentActivity(), TrackDialog.Listener {
    lateinit var view: PlayerView
    lateinit var manager: PlayerManager

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        view = layoutInflater.inflate(R.layout.view_wall_video, null) as PlayerView
        setContentView(view)
    }

    fun openSubtitleTracks() {
        TrackDialog.create().player(manager).type(C.TRACK_TYPE_TEXT).show(this)
        supportFragmentManager.executePendingTransactions()
    }

    override fun onSubtitleClick() {
        SubtitleDialog.create().player(manager).view(view.subtitleView).show(this)
    }
}
