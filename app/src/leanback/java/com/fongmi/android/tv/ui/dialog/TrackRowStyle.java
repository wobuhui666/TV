package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.widget.TextView;

import androidx.media3.common.C;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.ui.custom.JetStreamTrackChoiceButton;

public final class TrackRowStyle {
    private TrackRowStyle() {
    }

    public static void bind(TextView view, Track track) {
        boolean subtitle = track.getType() == C.TRACK_TYPE_TEXT;
        boolean local = subtitle && track.isLocalSubtitle();
        view.setEllipsize(subtitle ? TextUtils.TruncateAt.END : TextUtils.TruncateAt.MARQUEE);
        view.setContentDescription(local
                ? track.getName() + ", " + view.getContext().getString(R.string.track_local_subtitle)
                : track.getName());
        if (view instanceof JetStreamTrackChoiceButton button) button.setLocalSubtitle(local);
    }
}
