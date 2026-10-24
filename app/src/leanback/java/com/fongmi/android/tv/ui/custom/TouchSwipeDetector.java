package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.view.MotionEvent;

/** Recognize a completed single-finger swipe without depending on fling velocity. */
final class TouchSwipeDetector {
    static final int NONE = 0;
    static final int RIGHT = 1;
    static final int LEFT = 2;
    static final int UP = 3;
    static final int DOWN = 4;

    private final float threshold;
    private float startX;
    private float startY;
    private boolean tracking;

    TouchSwipeDetector(Context context) {
        threshold = context.getResources().getDisplayMetrics().density * 48;
    }

    int onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = event.getPointerCount() == 1;
                startX = event.getX();
                startY = event.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                reset();
                break;
            case MotionEvent.ACTION_UP:
                if (!tracking) return NONE;
                reset();
                float dx = event.getX() - startX;
                float dy = event.getY() - startY;
                if (Math.max(Math.abs(dx), Math.abs(dy)) < threshold) return NONE;
                if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? RIGHT : LEFT;
                return dy < 0 ? UP : DOWN;
        }
        return NONE;
    }

    void reset() {
        tracking = false;
    }
}
