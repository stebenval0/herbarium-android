package com.herbarium.home;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * Herbarium's face on the lock screen: the moon, the time, the day's summary, and one big "Open" button
 * (no swiping). Android's own PIN screen still follows if the phone has a PIN; no app may replace that.
 */
public class LockActivity extends Activity {
    private TextView time, date, headline;
    private LinearLayout calls;      // Caretaken mode: calls to return, first thing on picking up the phone
    private View readButton;
    private Theme th;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() { update(); h.postDelayed(this, 10_000); } };
    private android.speech.tts.TextToSpeech tts;

    /** On whenever Herbarium is the home screen, unless switched off in Accessibility. */
    /** On once Herbarium is the home screen (until then the first Herbarium app shows its own lock screen). */
    static boolean wanted(Context c) { return Theme.prefs(c).getBoolean("lockFace", true) && Notes.isHome(c); }

    static void show(Context c) {
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked() || !wanted(c)) return;
        // a call (ringing, or held to the ear with the screen dark) always comes first: never cover it
        if (InCall.current() != null) { CallActivity.bringBack(c); return; }
        if (AlarmActivity.now != null) { AlarmActivity.show(c); return; }     // an alarm ringing comes first too
        try { c.startActivity(new Intent(c, LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)); } catch (Exception ignored) {}
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        Theme t = Theme.get(this);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        LinearLayout col = Ui.column(this); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(t.dp(28), t.dp(70), t.dp(28), t.dp(40));
        Bitmap paper = Paper.ready(this, W, H, t);
        col.setBackground(paper != null ? new BitmapDrawable(getResources(), paper) : Paper.quick(t));

        View moon = new View(this) { @Override protected void onDraw(Canvas c) { Paper.moon(c, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f - t.dp(1), t); } };
        LinearLayout.LayoutParams mp = Ui.lp(t.sdp(30), t.sdp(30)); mp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(moon, mp);
        time = Ui.centered(Ui.text(this, t, "", 108, t.ink, Theme.regular)); time.setLetterSpacing(-.02f);
        date = Ui.centered(Ui.text(this, t, "", 23, t.sepia, Theme.regular));
        TextView wx = Ui.centered(Ui.caps(this, t, Weather.line(this), 15, t.sepia));
        col.addView(time, Ui.wide(t.sdp(6))); col.addView(date, Ui.wide(t.sdp(4))); col.addView(wx, Ui.wide(t.sdp(10)));
        headline = Ui.centered(Ui.text(this, t, "", 21, t.ink, Theme.italic));
        col.addView(headline, Ui.wide(t.sdp(34)));
        calls = Ui.column(this); th = t;
        col.addView(calls, Ui.wide(t.sdp(18)));
        TextView read = Ui.button(this, t, "Read it to me", v -> speak(Summary.of(this).spoken()));
        LinearLayout.LayoutParams rp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); rp.gravity = Gravity.CENTER_HORIZONTAL; rp.topMargin = t.sdp(12);
        if (Summary.canSpeak(this)) col.addView(read, rp);
        readButton = read;
        col.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        TextView open = Ui.primary(this, t, "Open", v -> unlock());
        open.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, t.sp(22));
        LinearLayout.LayoutParams op = Ui.wide(); op.leftMargin = op.rightMargin = t.dp(30);
        col.addView(open, op);
        col.setOnApplyWindowInsetsListener((v, in) -> {
            int nav = in.getInsets(WindowInsets.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), nav + t.dp(24));
            return in;
        });
        setContentView(col);
        warmVoice();
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
    }

    @Override protected void onResume() {
        super.onResume();
        if (InCall.current() != null) { CallActivity.bringBack(this); return; }   // a call came in under the lock screen
        if (AlarmActivity.now != null) { AlarmActivity.show(this); return; }      // so did an alarm
        h.post(tick); showCalls();
        try { getContentResolver().registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, callLog); } catch (Exception ignored) {}
    }

    /** A call answered or returned changes what's waiting: the lock screen updates at once. */
    private final android.database.ContentObserver callLog = new android.database.ContentObserver(new Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean self) { showCalls(); }
    };

    /** Caretaken mode: who's waiting for a call back, in the biggest letters on the screen; one tap unlocks and calls. */
    private void showCalls() {
        calls.removeAllViews();
        java.util.List<Missed.Call> w = Care.on(this) ? Missed.waiting(this) : new java.util.ArrayList<>();
        boolean any = !w.isEmpty();
        headline.setVisibility(any ? View.GONE : View.VISIBLE);           // room for what matters most
        if (readButton != null) readButton.setVisibility(any ? View.GONE : View.VISIBLE);
        time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, th.sp(any ? 64 : 108));
        for (int i = 0; i < w.size() && i < 2; i++) {
            Missed.Call m = w.get(i);
            String who = Missed.first(m.name);
            TextView said = Ui.centered(Ui.text(this, th, m.name + " called you", 27, th.ink, Theme.semi));
            calls.addView(said, Ui.wide(i == 0 ? 0 : th.sdp(18)));
            // Caretaken mode: calling back needs no unlocking (the call screen shows over the lock, so Android's PIN
            // screen never appears); otherwise the phone is opened first, as usual
            TextView call = Ui.primary(this, th, "Call " + who + " back", v -> { if (Care.on(this)) Missed.callBack(this, m.number); else unlock(() -> Missed.callBack(this, m.number)); });
            call.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, th.btn(22));
            call.setMinHeight(Math.round(th.dp(88) * (1 + (th.scale - 1) * .35f)));
            call.setContentDescription("Call " + m.name + " back");
            LinearLayout.LayoutParams cp = Ui.wide(th.sdp(10)); cp.leftMargin = cp.rightMargin = th.dp(10);
            calls.addView(call, cp);
        }
        if (w.size() > 2) calls.addView(Ui.centered(Ui.text(this, th, "and " + Summary.count(w.size() - 2, "other call") + " to return", 18, th.sepia, Theme.italic)), Ui.wide(th.sdp(10)));
    }
    @Override protected void onPause() { h.removeCallbacks(tick); try { getContentResolver().unregisterContentObserver(callLog); } catch (Exception ignored) {} super.onPause(); }
    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }
    @Override public void onBackPressed() { /* stays until "Open" */ }

    private void update() {
        Calendar d = Calendar.getInstance();
        int hr = d.get(Calendar.HOUR) == 0 ? 12 : d.get(Calendar.HOUR);
        time.setText(hr + ":" + String.format(Locale.US, "%02d", d.get(Calendar.MINUTE)));
        String[] days = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        String[] months = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
        date.setText(days[d.get(Calendar.DAY_OF_WEEK) - 1] + ", the " + HomeActivity.ord(d.get(Calendar.DAY_OF_MONTH)) + " of " + months[d.get(Calendar.MONTH)]);
        headline.setText(Notes.enabled(this) ? Summary.of(this).headline : "");
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && !km.isKeyguardLocked() && !getIntent().getBooleanExtra("preview", false)) finish();     // unlocked some other way (fingerprint, say)
    }

    private void unlock() { unlock(null); }

    /** Open the phone (Android asks for the fingerprint or PIN if there is one), then do `after`. */
    private void unlock(Runnable after) {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked()) { finish(); if (after != null) after.run(); return; }
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { finish(); if (after != null) after.run(); }
        });
    }

    private void speak(String text) {
        if (ttsReady) { tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "lock"); return; }
        ttsWaiting = text;                       // spoken the moment the voice is ready
        warmVoice();
    }

    private boolean ttsReady; private String ttsWaiting;

    /** Start the voice early (when the screen opens), so the first "Read it to me" speaks at once. */
    private void warmVoice() {
        if (tts != null || !Summary.canSpeak(this)) return;
        tts = new android.speech.tts.TextToSpeech(this, st -> {
            if (st != android.speech.tts.TextToSpeech.SUCCESS) return;
            tts.setSpeechRate(.9f); ttsReady = true;
            if (ttsWaiting != null) { tts.speak(ttsWaiting, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "lock"); ttsWaiting = null; }
        });
    }
}
