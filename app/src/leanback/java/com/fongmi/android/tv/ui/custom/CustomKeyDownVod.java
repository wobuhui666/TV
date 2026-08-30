package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.KeyUtil;

public class CustomKeyDownVod extends GestureDetector.SimpleOnGestureListener {

    private final GestureDetector detector;
    private final Listener listener;
    private final PendingSeek pendingSeek;
    private boolean changeSpeed;
    private boolean full;

    public static CustomKeyDownVod create(Activity activity) {
        return new CustomKeyDownVod(activity);
    }

    private CustomKeyDownVod(Activity activity) {
        this.detector = new GestureDetector(activity, this);
        this.listener = (Listener) activity;
        this.pendingSeek = new PendingSeek(listener::onSeekEnd, new PendingSeek.Scheduler() {
            @Override
            public void post(Runnable runnable, long delayMs) {
                App.post(runnable, delayMs);
            }

            @Override
            public void cancel(Runnable runnable) {
                App.removeCallbacks(runnable);
            }
        });
    }

    public boolean onTouchEvent(MotionEvent e) {
        if (!full) return false;
        return detector.onTouchEvent(e);
    }

    public void setFull(boolean full) {
        this.full = full;
    }

    public boolean hasEvent(KeyEvent event) {
        return KeyUtil.isEnterKey(event) || KeyUtil.isUpKey(event) || KeyUtil.isDownKey(event) || KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event);
    }

    public boolean onKeyDown(KeyEvent event) {
        check(event);
        return true;
    }

    private void check(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) {
            pendingSeek.cancel();
            listener.onSeeking(subTime(event));
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) {
            pendingSeek.cancel();
            listener.onSeeking(addTime(event));
        } else if (KeyUtil.isActionUp(event) && (KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event))) {
            pendingSeek.post(250);
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isUpKey(event)) {
            if (changeSpeed) listener.onSpeedEnd();
            else listener.onKeyUp();
            changeSpeed = false;
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isDownKey(event)) {
            listener.onKeyDown();
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isEnterKey(event)) {
            listener.onKeyCenter();
        } else if (event.isLongPress() && KeyUtil.isUpKey(event)) {
            changeSpeed = listener.onSpeedUp();
        }
    }

    @Override
    public boolean onDoubleTap(@NonNull MotionEvent e) {
        listener.onDoubleTap();
        return true;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        listener.onSingleTap();
        return true;
    }

    private long addTime(KeyEvent event) {
        return pendingSeek.add(seekStep(event));
    }

    private long subTime(KeyEvent event) {
        return pendingSeek.add(-seekStep(event));
    }

    /**
     * 长按加速：按住方向键时步长逐级放大（10s → 20s → 30s），点按保持 10s 精调。
     * 可在 设置→播放 中关闭，关闭后恒为 10s。
     */
    private long seekStep(KeyEvent event) {
        if (!Setting.isSeekAccelerate()) return Constant.INTERVAL_SEEK;
        int repeat = event.getRepeatCount();
        if (repeat < 5) return Constant.INTERVAL_SEEK;
        if (repeat < 25) return Constant.INTERVAL_SEEK * 2;
        return Constant.INTERVAL_SEEK * 3;
    }

    public void reset() {
        pendingSeek.clear();
    }

    public interface Listener {

        void onSeeking(long time);

        void onSeekEnd(long time);

        boolean onSpeedUp();

        void onSpeedEnd();

        void onKeyUp();

        void onKeyDown();

        void onKeyCenter();

        void onSingleTap();

        void onDoubleTap();
    }
}
