package com.herbarium.home;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The television remote: big buttons, Bluetooth only (works when the Wi-Fi doesn't). */
public class TvActivity extends Activity {
    private Theme t;
    private TvRemote tv;
    private TextView status;
    private LinearLayout pairing;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        t = Theme.get(this);
        tv = TvRemote.get(this);
        build();
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
    }

    @Override protected void onStart() {
        super.onStart();
        tv.listener = () -> main.post(this::refresh);
        if (allowed()) tv.start(); else requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}, 1);
        refresh();
    }

    /** Back from Android's "turn Bluetooth on" question: start straight away. */
    @Override protected void onResume() { super.onResume(); if (allowed() && !tv.asking()) tv.start(); refresh(); }

    @Override protected void onStop() {
        super.onStop();
        tv.listener = null;
        tv.stop();             // the television gets its own on-screen keyboard back
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) { if (allowed()) tv.start(); refresh(); }

    private boolean allowed() { return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }

    private void build() {
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        Steady.Scroll sc = new Steady.Scroll(this, t);
        Bitmap paper = Paper.ready(this, W, H, t);
        sc.setBackground(paper != null ? new BitmapDrawable(getResources(), paper) : Paper.quick(t));
        LinearLayout col = Ui.column(this); col.setPadding(t.dp(22), t.dp(48), t.dp(22), t.dp(60));
        sc.addView(col, Ui.wide());

        col.addView(Ui.centered(Ui.caps(this, t, "Television", 28, t.ink)));
        col.addView(Ui.centered(Ui.text(this, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
        status = Ui.centered(Ui.text(this, t, "", 18, t.sepia, Theme.italic));
        col.addView(status, Ui.wide(t.sdp(10)));
        pairing = Ui.column(this);
        col.addView(pairing, Ui.wide(t.sdp(6)));

        // the arrows round a big OK
        int pad = t.target(80);           // the D-pad: three even rows, OK the same size as the arrows round it
        col.addView(row(null, key("▲", "Up", TvRemote.UP, false, true), null, pad), Ui.wide(t.sdp(16)));
        TextView ok = Ui.primary(this, t, "OK", v -> press(v, TvRemote.OK, false));
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(26));
        col.addView(row(key("◀", "Left", TvRemote.LEFT, false, true), ok, key("▶", "Right", TvRemote.RIGHT, false, true), pad), Ui.wide(t.sdp(8)));
        col.addView(row(null, key("▼", "Down", TvRemote.DOWN, false, true), null, pad), Ui.wide(t.sdp(8)));

        col.addView(row(key("Back", "Back", TvRemote.BACK, true, false), key("Home", "Home", TvRemote.HOME, true, false), key("Menu", "Menu", TvRemote.MENU, true, false), t.target(64)), Ui.wide(t.sdp(20)));
        col.addView(row(key("«", "Back a little", TvRemote.REWIND, true, true), key("Play / pause", "Play or pause", TvRemote.PLAY, true, false), key("»", "On a little", TvRemote.FORWARD, true, true), t.target(64)), Ui.wide(t.sdp(10)));
        col.addView(row(key("Softer", "Softer", TvRemote.SOFTER, true, true), key("Mute", "Mute", TvRemote.MUTE, true, false), key("Louder", "Louder", TvRemote.LOUDER, true, true), t.target(64)), Ui.wide(t.sdp(10)));

        TextView forget = Ui.text(this, t, "Use a different television", 16, t.sage, Theme.regular);
        forget.setGravity(Gravity.CENTER); forget.setMinHeight(t.target(48));
        Steady.tap(forget, t, v -> { tv.forget(); refresh(); });
        col.addView(forget, Ui.wide(t.sdp(24)));
        setContentView(sc);
    }

    /** Three across (an empty slot keeps the D-pad's shape); stacked never: a remote's layout is its meaning. */
    private LinearLayout row(View a, View b, View c, int height) {
        LinearLayout r = Ui.row(this);
        for (View v : new View[]{a, b, c}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, height, 1); p.setMargins(t.dp(5), 0, t.dp(5), 0);
            r.addView(v != null ? v : new View(this), p);
        }
        return r;
    }

    /** A remote key. Arrows, skipping and volume repeat while held. */
    private TextView key(String label, String spoken, int code, boolean media, boolean repeats) {
        TextView k = Ui.button(this, t, label, v -> {});
        k.setMinHeight(0); k.setPadding(t.dp(4), 0, t.dp(4), 0); k.setGravity(Gravity.CENTER);
        if (label.length() <= 2) k.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(label.length() == 1 ? 40 : 30));    // arrows and skips: big marks
        else { k.setMaxLines(2); k.setAutoSizeTextTypeUniformWithConfiguration(12, Math.round(t.btn(18)), 1, TypedValue.COMPLEX_UNIT_SP); }
        k.setContentDescription(spoken);
        final Runnable[] again = new Runnable[1];
        again[0] = () -> { tv.press(code, media); main.postDelayed(again[0], 140); };
        k.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true); press(v, code, media);
                    if (repeats) main.postDelayed(again[0], 450);
                    return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false); main.removeCallbacks(again[0]);
                    return true;
            }
            return true;
        });
        k.setOnClickListener(v -> press(v, code, media));       // screen readers and switch access
        return k;
    }

    private void press(View v, int code, boolean media) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        tv.press(code, media);
    }

    private void refresh() {
        pairing.removeAllViews();
        if (!allowed()) { status.setText("Herbarium needs permission to use Bluetooth."); return; }
        if (!tv.bluetoothOn()) {
            status.setText("Bluetooth is off.");
            pairing.addView(Ui.primary(this, t, "Turn Bluetooth on", v -> { try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) {} }), Ui.wide());
            return;
        }
        if (tv.connected()) { status.setText("Connected to " + tv.tvName() + "."); return; }
        if (tv.saved() != null) { status.setText("Reaching " + tv.tvName() + "… (is it switched on?)"); return; }
        status.setText("Not paired with a television yet.");
        String me = "this phone";
        try { BluetoothManager m = getSystemService(BluetoothManager.class); if (m != null && m.getAdapter().getName() != null) me = "“" + m.getAdapter().getName() + "”"; } catch (SecurityException ignored) {}
        pairing.addView(Ui.text(this, t, "Once only. On the television, open Settings → Controllers & Bluetooth Devices → Other Bluetooth Devices → Add Bluetooth Devices. Then tap the button below, choose " + me + " on the television, and accept the pairing on both.", 17, t.ink, Theme.regular), Ui.wide());
        pairing.addView(Ui.primary(this, t, "Let the television find this phone", v -> {
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)); } catch (Exception ignored) {}
        }), Ui.wide(t.sdp(10)));
    }
}
