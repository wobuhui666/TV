package com.fongmi.android.tv.ui.custom;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import com.fongmi.android.tv.R;

/** Focus an explicitly touch-focusable item before its native tap is dispatched. */
public final class RecyclerTouchFocus {
    private RecyclerTouchFocus() { }

    public static void install(RecyclerView recycler) {
        if (Boolean.TRUE.equals(recycler.getTag(R.id.touch_focus_installed))) return;
        recycler.setTag(R.id.touch_focus_installed, true);
        int slop = ViewConfiguration.get(recycler.getContext()).getScaledTouchSlop();
        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float downX;
            private float downY;
            private boolean dragging;
            private View target;

            @Override public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        dragging = false;
                        target = rv.findChildViewUnder(downX, downY);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) dragging = true;
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging = true;
                        target = null;
                        break;
                    case MotionEvent.ACTION_UP:
                        if (!dragging && target != null && target == rv.findChildViewUnder(event.getX(), event.getY())) {
                            int[] origin = new int[2];
                            rv.getLocationOnScreen(origin);
                            int x = Math.round(origin[0] + event.getX());
                            int y = Math.round(origin[1] + event.getY());
                            // Nested horizontal rails own their touched card and its index.
                            if (!insideRecycler(target, x, y)) {
                                View focus = focusAt(target, x, y);
                                if (focus != null) focus.requestFocus();
                            }
                        }
                        target = null;
                        break;
                }
                return false;
            }
        });
    }

    private static boolean hit(View view, int x, int y) {
        Rect bounds = new Rect();
        return view.isShown() && view.isEnabled() && view.getGlobalVisibleRect(bounds) && bounds.contains(x, y);
    }

    private static boolean insideRecycler(View view, int x, int y) {
        if (!hit(view, x, y)) return false;
        if (view instanceof RecyclerView) return true;
        if (view instanceof ViewGroup group) for (int i = group.getChildCount() - 1; i >= 0; i--) {
            if (insideRecycler(group.getChildAt(i), x, y)) return true;
        }
        return false;
    }

    private static View focusAt(View view, int x, int y) {
        if (!hit(view, x, y)) return null;
        if (view instanceof ViewGroup group) for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View focus = focusAt(group.getChildAt(i), x, y);
            if (focus != null) return focus;
        }
        return view.isFocusable() && view.isFocusableInTouchMode() ? view : null;
    }
}
