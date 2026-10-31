package com.fongmi.android.tv.test;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.ui.custom.CustomKeyDownLive;
import com.fongmi.android.tv.ui.custom.CustomKeyDownVod;

/** Disposable input callback host; does not create a player or load sources. */
public final class SeekInputActivity extends AppCompatActivity implements CustomKeyDownVod.Listener, CustomKeyDownLive.Listener {
    public long preview;
    public long committed;
    public int commits;
    public int cancellations;
    public int leftLines;
    public int rightLines;
    public boolean seekable = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new View(this));
    }

    @Override public boolean dispatch(boolean check) { return true; }
    @Override public boolean canSeek() { return seekable; }
    @Override public void onSeeking(long delta) { preview = delta; }
    @Override public void onSeekEnd(long delta) { committed = delta; commits++; }
    @Override public void onSeekCancel() { cancellations++; }
    @Override public void onKeyLeft(long delta) { leftLines++; }
    @Override public void onKeyRight(long delta) { rightLines++; }
    @Override public void onShow(String number) {}
    @Override public void onFind(String number) {}
    @Override public void onMenu() {}
    @Override public boolean onSpeedUp() { return false; }
    @Override public void onSpeedEnd() {}
    @Override public void onKeyUp() {}
    @Override public void onKeyDown() {}
    @Override public void onKeyCenter() {}
    @Override public void onSingleTap() {}
    @Override public void onDoubleTap() {}
}
