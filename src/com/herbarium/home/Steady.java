package com.herbarium.home;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * Taps that forgive an unsteady hand:
 *   - a tap still counts if the finger slides up to ~10 mm or rests on the screen for a while,
 *   - a second tap on the same thing within 0.7 s is ignored (no accidental double opens),
 *   - nothing needs a long press, a swipe or a fast double tap.
 * With "steady" off it behaves like ordinary Android taps.
 */
final class Steady {
    private Steady() {}

    interface Action { void run(View v); }

    /** Controls people tap several times on purpose ("Larger", "Smaller", "Undo"): a short guard instead of 0.7 s. */
    private static final java.util.Set<View> REPEATABLE = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    static <V extends View> V repeatable(V v) { REPEATABLE.add(v); return v; }

    @SuppressLint("ClickableViewAccessibility")
    static void tap(View v, Theme t, Action a) {
        final float slop = t.steady ? t.dp(40) : t.dp(14);
        final long steadyGap = t.steady ? 700 : 250;
        final float[] down = new float[2];
        final long[] last = {0};
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY();
                    view.setPressed(true);
                    if (view.getForeground() == null) view.setForeground(new ColorDrawable(t.press));
                    view.getForeground().setAlpha(255);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    // drifting away dims the highlight; coming back brings it back, and the tap still counts
                    boolean in = Math.hypot(e.getRawX() - down[0], e.getRawY() - down[1]) <= slop;
                    view.setPressed(in);
                    if (view.getForeground() != null) view.getForeground().setAlpha(in ? 255 : 70);
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    boolean inside = Math.hypot(e.getRawX() - down[0], e.getRawY() - down[1]) <= slop;
                    clear(view);
                    long now = SystemClock.uptimeMillis();
                    long repeatGap = REPEATABLE.contains(view) ? 250 : steadyGap;
                    if (inside && now - last[0] > repeatGap) {
                        last[0] = now;
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        a.run(view);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    clear(view); return true;
            }
            return false;
        });
        // screen readers and switch access use the ordinary click
        v.setOnClickListener(view -> a.run(view));
    }

    private static void clear(View v) { v.setPressed(false); if (v.getForeground() != null) v.getForeground().setAlpha(0); }

    /** A ScrollView that only starts scrolling once the finger has clearly travelled, so a shaky tap stays a tap. */
    static final class Scroll extends ScrollView {
        private final float slop; private float y0, x0; private boolean dragging;
        Scroll(Context c, Theme t) { super(c); slop = t.steady ? t.dp(28) : t.dp(8); setVerticalScrollBarEnabled(false); setOverScrollMode(OVER_SCROLL_NEVER); }
        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: y0 = e.getY(); x0 = e.getX(); dragging = false; super.onInterceptTouchEvent(e); return false;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(e.getY() - y0) > slop && Math.abs(e.getY() - y0) > Math.abs(e.getX() - x0)) {
                        dragging = true;
                        MotionEvent d = MotionEvent.obtain(e); d.setAction(MotionEvent.ACTION_DOWN); super.onTouchEvent(d); d.recycle();
                    }
                    return dragging;
                default: return super.onInterceptTouchEvent(e) && dragging;
            }
        }
    }
}
