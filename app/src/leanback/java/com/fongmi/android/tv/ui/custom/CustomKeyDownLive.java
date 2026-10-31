package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.utils.KeyUtil;

public class CustomKeyDownLive extends GestureDetector.SimpleOnGestureListener {

    private final GestureDetector detector;
    private final TouchSwipeDetector swipe;
    private final StringBuilder text;
    private final Listener listener;
    private final PendingSeek pendingSeek;
    private final SeekKeySequence seekKeys = new SeekKeySequence();

    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            listener.onFind(text.toString());
            text.setLength(0);
        }
    };

    public static CustomKeyDownLive create(Context context) {
        return new CustomKeyDownLive(context);
    }

    private CustomKeyDownLive(Context context) {
        this.text = new StringBuilder();
        this.listener = (Listener) context;
        this.detector = new GestureDetector(context, this);
        this.swipe = new TouchSwipeDetector(context);
        pendingSeek = new PendingSeek(listener::onSeekEnd, new PendingSeek.Scheduler() {
            @Override public void post(Runnable task, long delayMs) { App.post(task, delayMs); }
            @Override public void cancel(Runnable task) { App.removeCallbacks(task); }
        }, listener::onSeekCancel);
    }

    public boolean onTouchEvent(MotionEvent e) {
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

    public boolean hasEvent(KeyEvent event) {
        return KeyUtil.isEnterKey(event) || KeyUtil.isUpKey(event) || KeyUtil.isDownKey(event) || KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event) || KeyUtil.isDigitKey(event) || KeyUtil.isMenuKey(event) || event.isLongPress();
    }

    public void onKeyDown(KeyEvent event) {
        if (listener.dispatch(true)) check(event);
    }

    private void check(KeyEvent event) {
        if (KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event)) {
            onSeekKey(event);
            return;
        }
        if (KeyUtil.isActionDown(event)) cancelSeek();
        if (KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)) {
            listener.onKeyUp();
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event)) {
            listener.onKeyDown();
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isDigitKey(event)) {
            onKeyDown(event.getKeyCode());
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isEnterKey(event)) {
            listener.onKeyCenter();
        } else if (KeyUtil.isMenuKey(event) || event.isLongPress() && KeyUtil.isEnterKey(event)) {
            listener.onMenu();
        }
    }

    private void onSeekKey(KeyEvent event) {
        if (KeyUtil.isActionDown(event)) {
            if (!seekKeys.down(event.getKeyCode(), event.getDeviceId(), event.getDownTime(), event.getRepeatCount())) return;
            pendingSeek.cancel();
            if (listener.canSeek()) listener.onSeeking(KeyUtil.isLeftKey(event) ? subTime() : addTime());
            else pendingSeek.clear();
        } else if (KeyUtil.isActionUp(event)
                && seekKeys.up(event.getKeyCode(), event.getDeviceId(), event.getDownTime())) {
            if (event.isCanceled()) cancelSeek();
            else if (listener.canSeek()) pendingSeek.post(250);
            else {
                cancelSeek();
                if (KeyUtil.isLeftKey(event)) listener.onKeyLeft(0);
                else listener.onKeyRight(0);
            }
        }
    }

    private void onKeyDown(int keyCode) {
        if (text.length() >= 4) return;
        text.append(getNumber(keyCode));
        listener.onShow(text.toString());
        App.post(runnable, 2000);
    }

    @Override
    public boolean onDown(@NonNull MotionEvent event) {
        return true;
    }

    private void onSwipe(int direction) {
        if (!listener.dispatch(false)) return;
        if (direction == TouchSwipeDetector.UP || direction == TouchSwipeDetector.DOWN) {
            cancelSeek();
            if (direction == TouchSwipeDetector.UP) listener.onKeyDown();
            else listener.onKeyUp();
        }
        else {
            seekKeys.clear();
            pendingSeek.cancel();
            if (listener.canSeek()) {
                listener.onSeeking(direction == TouchSwipeDetector.LEFT ? subTime() : addTime());
                pendingSeek.post(250);
            } else {
                cancelSeek();
                if (direction == TouchSwipeDetector.LEFT) listener.onKeyLeft(0);
                else listener.onKeyRight(0);
            }
        }
    }

    @Override
    public boolean onDoubleTap(@NonNull MotionEvent e) {
        if (listener.dispatch(false)) listener.onDoubleTap();
        return true;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        if (listener.dispatch(false)) listener.onSingleTap();
        return true;
    }

    private int getNumber(int keyCode) {
        return keyCode >= 144 ? keyCode - 144 : keyCode - 7;
    }

    private long addTime() {
        return pendingSeek.add(Constant.INTERVAL_SEEK);
    }

    private long subTime() {
        return pendingSeek.add(-Constant.INTERVAL_SEEK);
    }

    public void reset() {
        cancelSeek();
    }

    public void cancelSeek() {
        seekKeys.clear();
        pendingSeek.clear();
    }

    public interface Listener {

        default boolean canSeek() { return true; }

        default void onSeekEnd(long time) {
            if (time < 0) onKeyLeft(time);
            else onKeyRight(time);
        }

        default void onSeekCancel() {}

        boolean dispatch(boolean check);

        void onShow(String number);

        void onFind(String number);

        void onSeeking(long time);

        void onKeyUp();

        void onKeyDown();

        void onKeyLeft(long time);

        void onKeyRight(long time);

        void onKeyCenter();

        void onMenu();

        void onSingleTap();

        void onDoubleTap();
    }
}
