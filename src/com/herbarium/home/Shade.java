package com.herbarium.home;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Herbarium's own notification shade, over any app. A thin strip along the top edge catches a downward pull
 * (before Android's shade does); the shade holds the day's notes and a few big controls.
 */
final class Shade {
    private final AccessibilityService s;
    private final WindowManager wm;
    private View catcher;
    private FrameLayout panel;
    private boolean torch; private String torchId;
    private final CameraManager.TorchCallback torchWatch = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) { if (torchId == null || id.equals(torchId)) { torchId = id; torch = enabled; } }
    };

    Shade(AccessibilityService s) {
        this.s = s; wm = s.getSystemService(WindowManager.class);
        // follow the torch even when something else switches it, so the button never says the wrong thing
        try {
            s.getSystemService(CameraManager.class).registerTorchCallback(torchWatch, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Exception ignored) {}
    }

    static boolean wanted(Context c) { return Theme.prefs(c).getBoolean("shade", true) && Notes.isHome(c); }

    /** Add or remove the edge strip depending on the settings (called whenever they might have changed). */
    void refresh() {
        boolean want = wanted(s);
        if (want && catcher == null) addCatcher();
        else if (!want && catcher != null) { try { wm.removeView(catcher); } catch (Exception ignored) {} catcher = null; }
    }

    void destroy() {
        if (catcher != null) try { wm.removeView(catcher); } catch (Exception ignored) {}
        try { s.getSystemService(CameraManager.class).unregisterTorchCallback(torchWatch); } catch (Exception ignored) {}
        hide();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addCatcher() {
        Theme t = Theme.get(s);
        catcher = new View(s);
        final float[] y0 = {0};
        catcher.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: y0[0] = e.getRawY(); return true;
                case MotionEvent.ACTION_MOVE: if (e.getRawY() - y0[0] > t.dp(36)) { show(); return false; } return true;
                default: return true;
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(26),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP; lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try { wm.addView(catcher, lp); } catch (Exception e) { catcher = null; }
    }

    void show() {
        KeyguardManager km = s.getSystemService(KeyguardManager.class);
        // over the lock screen: the big controls only (no notes, no Wi-Fi page, no Home), so nothing private shows,
        // and Android's own shade never appears instead
        final boolean locked = km != null && km.isKeyguardLocked();
        if (panel != null) return;
        Theme t = Theme.get(s);
        int W = s.getResources().getDisplayMetrics().widthPixels, H = s.getResources().getDisplayMetrics().heightPixels;
        panel = new FrameLayout(s) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) { hide(); return true; }
                return super.dispatchKeyEvent(e);
            }
        };
        panel.setForceDarkAllowed(false);   // Herbarium keeps its own day and night
        // the paper is very slightly see-through, so the shade feels laid over what was there (solid with stronger contrast)
        android.graphics.Bitmap paper = Paper.ready(s, W, H, t);
        android.widget.ImageView bgImg = new android.widget.ImageView(s);
        bgImg.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        if (paper != null) bgImg.setImageBitmap(paper); else bgImg.setImageDrawable(Paper.quick(t));
        bgImg.setAlpha(t.contrast ? 1f : Theme.VEIL);
        panel.addView(bgImg, Ui.fill());
        Steady.Scroll sc = new Steady.Scroll(s, t);
        LinearLayout col = Ui.column(s); col.setPadding(t.dp(22), t.dp(44), t.dp(22), t.dp(40));
        sc.addView(col, Ui.wide());
        panel.addView(sc, Ui.fill());

        java.util.Calendar d = java.util.Calendar.getInstance();
        int hr = d.get(java.util.Calendar.HOUR) == 0 ? 12 : d.get(java.util.Calendar.HOUR);
        col.addView(Ui.centered(Ui.text(s, t, hr + ":" + String.format(java.util.Locale.US, "%02d", d.get(java.util.Calendar.MINUTE)), 48, t.ink, Theme.regular)));
        col.addView(Ui.centered(Ui.caps(s, t, Weather.line(s), 14, t.sepia)), Ui.wide(t.sdp(4)));

        // big controls, two to a row
        LinearLayout grid = Ui.column(s);
        AudioManager am = s.getSystemService(AudioManager.class);
        NotificationManager nm = s.getSystemService(NotificationManager.class);
        boolean dnd = nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        if (InCall.current() != null) grid.addView(Ui.primary(s, t, "Back to the call", v -> { hide(); CallActivity.bringBack(s); }), Ui.wide(t.dp(10)));
        final boolean helpersOnly = Care.settingsLocked(s);      // Android's settings pages are closed to the person: no buttons that lead there
        TextView torchB = ctl(t, torch ? "Torch · on" : "Torch", torch, v -> { toggleTorch(); rebuild(); });
        if (Care.on(s)) grid.addView(torchB, Ui.wide(t.dp(10)));          // Caretaken: calls always ring, so there's no Quiet
        else addPair(grid, t, torchB, ctl(t, dnd ? "Quiet · on" : "Quiet", dnd, v -> { toggleDnd(nm); rebuild(); }));
        if (Settings.System.canWrite(s))
            addPair(grid, t,
                    ctl(t, "Dimmer", false, v -> brightness(-40)),
                    ctl(t, "Brighter", false, v -> brightness(+40)));
        else if (!locked && !helpersOnly)   // without permission to change brightness, Android's own brightness slider (it can't open over the lock)
            grid.addView(ctl(t, "Brightness", false, v -> { hide(); go(new Intent(Settings.ACTION_DISPLAY_SETTINGS)); }), Ui.wide(t.dp(10)));
        // the volume that matters now: the ringer when nothing plays, the call during a call, music while it plays.
        // The level shows on the button itself (Android's own volume slider never pops up)
        String level = volumeLevel(am);
        TextView softer = Steady.repeatable(ctl(t, "Softer", false, null)), louder = Steady.repeatable(ctl(t, "Louder" + level, false, null));
        Steady.tap(softer, t, v -> { VolumeCard.step(am, false); louder.setText("Louder" + volumeLevel(am)); });
        Steady.tap(louder, t, v -> { VolumeCard.step(am, true); louder.setText("Louder" + volumeLevel(am)); });
        addPair(grid, t, softer, louder);
        if (!locked && helpersOnly) grid.addView(ctl(t, "Home", false, v -> { hide(); s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }), Ui.wide(t.dp(10)));
        else if (!locked) addPair(grid, t,
                ctl(t, "Wi-Fi", false, v -> { hide(); go(new Intent(Settings.Panel.ACTION_WIFI)); }),
                ctl(t, "Home", false, v -> { hide(); s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }));
        col.addView(grid, Ui.wide(t.sdp(16)));

        // the day's notes (never over the lock screen)
        if (Notes.enabled(s) && !locked) {
            Summary sum = Summary.of(s);
            col.addView(Ui.text(s, t, sum.styled(t), 19, t.ink, Theme.regular), Ui.wide(t.sdp(18)));
            int shown = 0;
            for (java.util.List<Summary.Group> part : java.util.Arrays.asList(sum.first, sum.quieter)) {
                if (part.isEmpty() || shown >= 8) continue;
                col.addView(Ui.heading(s, t, part == sum.first ? Summary.FIRST : Summary.LATER));
                for (Summary.Group g : part) {
                    if (shown++ >= 8) break;
                    col.addView(card(t, g), Ui.wide(t.sdp(8)));
                }
            }
            if (shown > 0) col.addView(Ui.button(s, t, "Clear them all", v -> { Notes.clearAll(s); rebuild(); }), Ui.wide(t.sdp(14)));
        }
        col.addView(Ui.primary(s, t, "Close", v -> hide()), Ui.wide(t.sdp(18)));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = android.R.style.Animation_Dialog;
        try { wm.addView(panel, lp); panel.requestFocus(); } catch (Exception e) { panel = null; }
    }

    /** " · 5 of 7" for the stream the volume buttons are changing now. */
    private static String volumeLevel(AudioManager am) {
        if (am == null) return "";
        int stream = VolumeCard.stream(am);
        return " · " + am.getStreamVolume(stream) + " of " + am.getStreamMaxVolume(stream);
    }

    /** Open one of Android's pages; a phone without it just stays put. */
    private void go(Intent i) {
        try { s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { try { s.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {} }
    }

    void hide() { if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) {} panel = null; } }
    private void rebuild() { hide(); show(); }

    private View card(Theme t, Summary.Group g) {
        LinearLayout card = Ui.column(s);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setCornerRadius(t.sdp(16));
        bg.setColor(t.night ? 0x332c3870 : 0x66f3ecda); bg.setStroke(t.dp(1), 0x556e5d45); card.setBackground(bg);
        card.setPadding(t.sdp(14), t.sdp(10), t.sdp(14), t.sdp(10));
        card.addView(Ui.caps(s, t, g.latest().app, 13, t.sepia));
        card.addView(Ui.body(s, t, g.cardLine(), 19), Ui.wide(t.sdp(2)));
        if (!g.cardDetail().isEmpty()) card.addView(Ui.ellipsize(Ui.text(s, t, g.cardDetail(), 16, t.sepia, Theme.regular), 2), Ui.wide(t.sdp(2)));
        TextView open = Ui.button(s, t, g.latest().key.startsWith(Notes.MISSED) ? "Call back" : "Open", v -> { hide(); Notes.open(s, g.latest()); for (Notes.Note x : g.notes) Notes.dismiss(s, x.key); });
        TextView done = Ui.button(s, t, "Clear", v -> { for (Notes.Note x : g.notes) Notes.dismiss(s, x.key); rebuild(); });
        card.addView(Ui.pair(s, t, open, done), Ui.wide(t.sdp(8)));
        return card;
    }

    private TextView ctl(Theme t, String label, boolean on, Steady.Action a) {
        TextView b = on ? Ui.primary(s, t, label, a) : Ui.button(s, t, label, a);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(15));
        b.setSingleLine(true); b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setContentDescription(label.replace(" · on", ", on"));
        return b;
    }
    private void addPair(LinearLayout grid, Theme t, View a, View b) {
        grid.addView(Ui.pair(s, t, a, b), Ui.wide(t.dp(10)));
    }

    private void toggleTorch() {
        try {
            CameraManager cm = s.getSystemService(CameraManager.class);
            if (torchId == null) for (String id : cm.getCameraIdList()) { Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE); if (f != null && f) { torchId = id; break; } }
            if (torchId != null) { torch = !torch; cm.setTorchMode(torchId, torch); }
        } catch (Exception ignored) {}
    }
    private void toggleDnd(NotificationManager nm) {
        if (nm == null) return;
        if (!nm.isNotificationPolicyAccessGranted()) { hide(); go(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return; }
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
    }
    private void brightness(int d) {
        if (!Settings.System.canWrite(s)) return;
        try {
            Settings.System.putInt(s.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            int cur = Settings.System.getInt(s.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
            Settings.System.putInt(s.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(5, Math.min(255, cur + d)));
        } catch (Exception ignored) {}
    }
}
