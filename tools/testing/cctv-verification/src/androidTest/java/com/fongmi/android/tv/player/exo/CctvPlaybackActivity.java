package com.fongmi.android.tv.player.exo;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceView;

/** A real display surface for MediaCodec output in the isolated native test APK. */
public final class CctvPlaybackActivity extends Activity {
    SurfaceView video;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        video = new SurfaceView(this);
        setContentView(video);
    }
}
