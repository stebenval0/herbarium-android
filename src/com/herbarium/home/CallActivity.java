package com.herbarium.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telecom.Call;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The call screen, in Herbarium. A call coming in: who it is, in the biggest letters, then Answer and Not now
 * (plain taps; no swiping). On a call: who, how long, Speaker, Mute, Keypad, and Hang up. Shows over the lock screen,
 * and the screen goes dark against the ear so a cheek can't press anything.
 */
public class CallActivity extends Activity {
    private Theme t;
    private LinearLayout col;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() { render(); h.postDelayed(this, 1000); } };
    private PowerManager.WakeLock ear;
    private boolean keypad;
    private String digits = "";
    private int lastState = -1; private boolean lastSpeaker, lastMuted, lastKeypad;

    /** Put the call screen in front again (the lock screen hands over to it). */
    static void bringBack(Context c) {
        try { c.startActivity(new android.content.Intent(c, CallActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
        catch (Exception ignored) {}
    }

    /** The caller's name (contacts or Herbarium's list), else their number written out, else "Someone". */
    static String who(Context c, Call call) {
        Call.Details d = call.getDetails();
        String name = d.getContactDisplayName();
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String n = d.getHandle() == null ? "" : d.getHandle().getSchemeSpecificPart();
        if (n == null || n.isEmpty()) return "A hidden number";
        return Missed.name(c, n, d.getCallerDisplayName());
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true); setTurnScreenOn(true);
        InCall.screenAlive = true;
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        t = Theme.get(this);
        col = Ui.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(t.dp(24), t.dp(70), t.dp(24), t.dp(44));
        col.setBackground(Paper.quick(t));
        android.graphics.Bitmap paper = Paper.ready(this, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels, t);
        if (paper != null) col.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), paper));
        // keep the buttons clear of Android's navigation bar, whatever its size
        col.setOnApplyWindowInsetsListener((v, in) -> {
            int nav = in.getInsets(WindowInsets.Type.navigationBars()).bottom;
            v.setPadding(t.dp(24), t.dp(70), t.dp(24), nav + t.dp(24));
            return in;
        });
        setContentView(col);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
            ear = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "herbarium:call");
    }

    @Override protected void onResume() {
        super.onResume(); InCall.screenShown = true;
        WindowInsetsController wc = getWindow().getInsetsController();      // Android's status bar stays away during a call
        if (wc != null) { wc.hide(WindowInsets.Type.statusBars()); wc.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); } InCall.onChange = this::render; lastState = -2; h.post(tick);
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);   // the pop-up isn't needed now
        Call call = InCall.current(); if (nm != null && call != null && InCall.me != null) InCall.me.refresh();
    }
    @Override protected void onDestroy() { InCall.screenAlive = false; super.onDestroy(); }
    @Override protected void onPause() { InCall.screenShown = false; h.removeCallbacks(tick); if (InCall.onChange != null) InCall.onChange = null; earOff(); super.onPause(); }
    @Override public void onBackPressed() { /* the call screen stays until the call ends; Home is still a press away */ moveTaskToBack(true); }

    private void earOn() { if (ear != null && !ear.isHeld()) ear.acquire(3 * 60 * 60_000L); }
    private void earOff() { if (ear != null && ear.isHeld()) ear.release(); }

    private void render() {
        Call call = InCall.current();
        if (call == null) {
            earOff();
            // the call is over: if the phone is still locked, Herbarium's lock screen comes back, not Android's
            LockActivity.show(this);
            finish(); overridePendingTransition(0, 0);
            return;
        }
        int st = call.getDetails().getState();
        boolean spk = InCall.speaker(), mute = InCall.muted();
        // the screen only needs rebuilding when something changes (the timer updates in place)
        if (st == lastState && spk == lastSpeaker && mute == lastMuted && keypad == lastKeypad) { updateTimer(call); return; }
        lastState = st; lastSpeaker = spk; lastMuted = mute; lastKeypad = keypad;
        col.removeAllViews();
        String name = who(this, call);
        boolean incoming = st == Call.STATE_RINGING;
        if (st == Call.STATE_ACTIVE && !spk) earOn(); else earOff();

        TextView status = Ui.centered(Ui.text(this, t, statusLine(call, st), 20, t.sepia, Theme.regular));
        status.setTag("status");
        TextView big = Ui.wholeWords(Ui.centered(Ui.text(this, t, name, 44, t.ink, Theme.semi)), 20);
        big.setMaxLines(3);
        col.addView(Ui.centered(Ui.text(this, t, incoming ? "Calling you" : "", 20, t.sage, Theme.regular)));
        col.addView(big, Ui.wide(t.sdp(8)));
        col.addView(status, Ui.wide(t.sdp(8)));
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));

        if (incoming) {
            TextView answer = Ui.primary(this, t, "Answer", v -> InCall.answer(call));
            answer.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(26));
            answer.setMinHeight(Math.round(t.dp(104) * grow()));
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(20)); g.setColor(t.night ? 0xff6fae86 : 0xff3f7a55);
            answer.setBackground(g); answer.setTextColor(0xfff6f1e2);
            answer.setContentDescription("Answer the call from " + name);
            col.addView(answer, Ui.wide());
            TextView no = Ui.button(this, t, "Not now", v -> InCall.decline(call));
            no.setMinHeight(Math.round(t.dp(72) * grow()));
            no.setContentDescription("Don't answer");
            col.addView(no, Ui.wide(t.sdp(16)));
            return;
        }

        if (keypad) col.addView(keypadView(call), Ui.wide(t.sdp(8)));
        else {
            TextView speaker = toggle("Speaker", spk, v -> InCall.speakerOn(!InCall.speaker()));
            TextView muteB = toggle("Mute", mute, v -> InCall.muteOn(!InCall.muted()));
            col.addView(t.large() ? stack(speaker, muteB) : Ui.pair(this, t, speaker, muteB), Ui.wide());
            TextView kp = Ui.button(this, t, "Keypad", v -> { keypad = true; render(); });
            kp.setContentDescription("Show the number keys, for menus that say press 1");
            col.addView(kp, Ui.wide(t.sdp(12)));
        }
        TextView end = Ui.primary(this, t, st == Call.STATE_DIALING || st == Call.STATE_CONNECTING ? "Stop calling" : "Hang up", v -> InCall.hangUp(call));
        end.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(24));
        end.setMinHeight(Math.round(t.dp(96) * grow()));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(20)); g.setColor(t.night ? 0xffc96a55 : 0xff9c3b2e);
        end.setBackground(g); end.setTextColor(0xfff6f1e2);
        col.addView(end, Ui.wide(t.sdp(20)));
    }

    private float grow() { return 1 + (t.scale - 1) * .35f; }

    private LinearLayout stack(View a, View b) { LinearLayout l = Ui.column(this); l.addView(a, Ui.wide()); l.addView(b, Ui.wide(t.dp(10))); return l; }

    /** A big button that's filled when on ("Speaker" on = filled sage). */
    private TextView toggle(String label, boolean on, Steady.Action a) {
        TextView b = Ui.button(this, t, label, a);
        b.setMinHeight(Math.round(t.dp(80) * grow()));
        if (on) {
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(14)); g.setColor(t.sage);
            b.setBackground(g); b.setTextColor(t.night ? 0xff1e2650 : 0xfff3ecda);
        }
        b.setContentDescription(label + (on ? ", on" : ", off"));
        return b;
    }

    private String statusLine(Call call, int st) {
        switch (st) {
            case Call.STATE_RINGING: return "";
            case Call.STATE_DIALING: case Call.STATE_CONNECTING: case Call.STATE_NEW: case Call.STATE_SELECT_PHONE_ACCOUNT: return "Calling…";
            case Call.STATE_HOLDING: return "On hold";
            case Call.STATE_DISCONNECTING: case Call.STATE_DISCONNECTED: return "Call ended";
            case Call.STATE_ACTIVE: return elapsed(call);
            default: return "";
        }
    }

    private static String elapsed(Call call) {
        long start = call.getDetails().getConnectTimeMillis();
        if (start <= 0) return "";
        long s = Math.max(0, (System.currentTimeMillis() - start) / 1000);
        long m = s / 60;
        return m == 0 ? s + " seconds" : m == 1 ? "1 minute" : m + " minutes";
    }

    private void updateTimer(Call call) {
        View v = col.findViewWithTag("status");
        if (v instanceof TextView && call.getDetails().getState() == Call.STATE_ACTIVE) ((TextView) v).setText(elapsed(call));
    }

    /** Big number keys for "press 1 for…", with what's been pressed above them. */
    private View keypadView(Call call) {
        LinearLayout box = Ui.column(this);
        TextView shown = Ui.centered(Ui.text(this, t, digits, 30, t.ink, Theme.regular));
        shown.setMinHeight(t.sdp(40));
        box.addView(shown, Ui.wide());
        android.widget.GridLayout g = new android.widget.GridLayout(this); g.setColumnCount(3);
        int cell = Math.min((getResources().getDisplayMetrics().widthPixels - t.dp(70)) / 3, Math.round(t.dp(100) * grow()));
        for (String k : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"}) {
            TextView b = Ui.button(this, t, k, v -> {
                call.playDtmfTone(k.charAt(0)); h.postDelayed(call::stopDtmfTone, 200);
                digits += k; shown.setText(digits);
            });
            Steady.repeatable(b);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(28));
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(); lp.width = cell; lp.height = Math.round(cell * .72f);
            lp.setMargins(t.dp(5), t.dp(5), t.dp(5), t.dp(5));
            g.addView(b, lp);
        }
        LinearLayout.LayoutParams gp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); gp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(g, gp);
        TextView hide = Ui.button(this, t, "Hide the keypad", v -> { keypad = false; render(); });
        box.addView(hide, Ui.wide(t.sdp(10)));
        return box;
    }
}
