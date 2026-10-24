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
    private final TouchSwipeDetector swipe;
    private final Listener listener;
    private final PendingSeek pendingSeek;
    private boolean changeSpeed;
    private boolean full;

    public static CustomKeyDownVod create(Activity activity) {
        return new CustomKeyDownVod(activity);
    }

    private CustomKeyDownVod(Activity activity) {
        this.detector = new GestureDetector(activity, this);
        this.swipe = new TouchSwipeDetector(activity);
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
        int direction = swipe.onTouchEvent(e);
        if (direction != TouchSwipeDetector.NONE) {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            detector.onTouchEvent(cancel);
            cancel.recycle();
            onSwipe(direction);
        } else detector.onTouchEvent(e);
        return true;
    }

    public void setFull(boolean full) {
        if (!full) endSpeed();
        if (this.full && !full) cancelGesture();
        this.full = full;
    }

    public boolean hasEvent(KeyEvent event) {
        return KeyUtil.isEnterKey(event) || KeyUtil.isUpKey(event) || KeyUtil.isDownKey(event) || KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event);
    }

    public boolean onKeyDown(KeyEvent event) {
        check(event);
        return true;
    }

    public boolean finishSpeedKey(KeyEvent event) {
        if (!changeSpeed || !KeyUtil.isActionUp(event) || !KeyUtil.isUpKey(event)) return false;
        endSpeed();
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
            if (changeSpeed) endSpeed();
            else listener.onKeyUp();
            changeSpeed = false;
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isDownKey(event)) {
            listener.onKeyDown();
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isEnterKey(event)) {
            listener.onKeyCenter();
        } else if (event.isLongPress() && KeyUtil.isUpKey(event)) {
            if (!changeSpeed) changeSpeed = listener.onSpeedUp();
        }
    }

    @Override
    public boolean onDown(@NonNull MotionEvent event) {
        return true;
    }

    private void onSwipe(int direction) {
        if (direction == TouchSwipeDetector.RIGHT || direction == TouchSwipeDetector.LEFT) {
            listener.onSeeking(pendingSeek.add(direction == TouchSwipeDetector.RIGHT ? Constant.INTERVAL_SEEK : -Constant.INTERVAL_SEEK));
            pendingSeek.post(250);
        } else if (direction == TouchSwipeDetector.UP) listener.onKeyUp();
        else listener.onKeyDown();
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
        endSpeed();
        pendingSeek.clear();
        cancelGesture();
    }

    private void cancelGesture() {
        swipe.reset();
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        detector.onTouchEvent(cancel);
        cancel.recycle();
    }

    private void endSpeed() {
        if (!changeSpeed) return;
        changeSpeed = false;
        listener.onSpeedEnd();
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
