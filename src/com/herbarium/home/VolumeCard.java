package com.herbarium.home;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Herbarium's volume card: pressing the volume buttons shows this small parchment card (what's being changed, and a
 * row of dots for the level) for two seconds, instead of Android's slider. Shown over everything, the lock included.
 */
final class VolumeCard {
    private VolumeCard() {}
    private static View card;
    private static TextView what;
    private static Dots dots;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final Runnable away = VolumeCard::hide;

    private static int lastStream = -1; private static long lastAt;

    /** Which volume the buttons change now: the call during a call, music while it plays, else the ringer.
     *  A run of presses keeps changing the same one (so music paused at a low level is still the one going back up). */
    static int stream(AudioManager am) {
        int s;
        if (am.getMode() == AudioManager.MODE_IN_CALL || am.getMode() == AudioManager.MODE_IN_COMMUNICATION) s = AudioManager.STREAM_VOICE_CALL;
        else if (am.isMusicActive()) s = AudioManager.STREAM_MUSIC;
        else if (lastStream >= 0 && android.os.SystemClock.uptimeMillis() - lastAt < 4000) s = lastStream;
        else s = AudioManager.STREAM_RING;
        lastStream = s; lastAt = android.os.SystemClock.uptimeMillis();
        return s;
    }

    /** One step softer or louder. Never down to silent (that's what "Quiet" is for), and a muted stream is unmuted first. */
    static void step(android.content.Context c, AudioManager am, boolean up) {
        if (am == null) return;
        int st = stream(am);
        try {
            if (up && am.isStreamMute(st)) am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0);
            if (!up && am.getStreamVolume(st) <= 1) return;
            // Caretaken: the ringer never goes below about 60% (calls must be heard)
            if (!up && st == AudioManager.STREAM_RING && Care.on(c) && am.getStreamVolume(st) <= Math.round(am.getStreamMaxVolume(st) * .6f)) return;
            am.adjustStreamVolume(st, up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            if (up && am.getStreamVolume(st) == 0) am.setStreamVolume(st, 1, 0);
        } catch (Exception ignored) {}     // Do Not Disturb can refuse ringer changes
    }

    static void show(AccessibilityService s, AudioManager am) {
        Theme t = Theme.get(s);
        int stream = lastStream >= 0 ? lastStream : stream(am);
        String name = stream == AudioManager.STREAM_VOICE_CALL ? "Call" : stream == AudioManager.STREAM_MUSIC ? "Music" : "Ringer";
        int level = am.getStreamVolume(stream), max = am.getStreamMaxVolume(stream);
        if (card == null) {
            LinearLayout c = Ui.column(s);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(t.dp(22), t.dp(14), t.dp(22), t.dp(16));
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.dp(22)); g.setColor(t.paperHi); g.setStroke(t.dp(1.2f), t.sepia);
            c.setBackground(g); c.setElevation(t.dp(8)); c.setForceDarkAllowed(false);
            what = Ui.centered(Ui.text(s, t, "", 18, t.ink, Theme.semi));
            c.addView(what, Ui.wide());
            dots = new Dots(s, t);
            c.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(22)));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Math.round(s.getResources().getDisplayMetrics().widthPixels * .78f),
                    ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; lp.y = t.dp(56);
            lp.windowAnimations = android.R.style.Animation_Toast;
            try { s.getSystemService(WindowManager.class).addView(c, lp); card = c; } catch (Exception e) { return; }
        }
        what.setText(level == 0 ? name + ": silent" : name + ": " + level + " of " + max);
        dots.set(level, max);
        card.setContentDescription(what.getText());
        h.removeCallbacks(away); h.postDelayed(away, 2000);
    }

    static void hide() {
        View c = card; card = null;
        if (c != null) try { c.getContext().getSystemService(WindowManager.class).removeView(c); } catch (Exception ignored) {}
    }

    /** A row of dots: filled sage up to the level, hollow beyond. */
    private static final class Dots extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final Theme t; private int level, max = 1;
        Dots(android.content.Context c, Theme t) { super(c); this.t = t; }
        void set(int l, int m) { level = l; max = Math.max(1, m); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float r = Math.min(getHeight() / 2f - t.dp(2), getWidth() / (max * 3f));
            float gap = (getWidth() - 2 * r * max) / (max + 1f), y = getHeight() / 2f;
            for (int i = 0; i < max; i++) {
                float x = gap + r + i * (2 * r + gap);
                p.setStyle(i < level ? Paint.Style.FILL : Paint.Style.STROKE); p.setStrokeWidth(t.dp(1.4f));
                p.setColor(i < level ? t.sage : t.sepia);
                c.drawCircle(x, y, r, p);
            }
        }
    }
}
