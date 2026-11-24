package com.herbarium.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import java.util.ArrayList;
import java.util.List;

/**
 * Calls, in Herbarium: Android hands every call here once Herbarium is the phone's calling app, and
 * CallActivity shows it (who's calling, big Answer / Not now, then Speaker, Mute, Keypad and Hang up).
 * Android itself keeps ringing and the call sound; this is only what the person sees and touches.
 */
public class InCall extends InCallService {
    static volatile InCall me;
    static final List<Call> calls = new ArrayList<>();
    private final java.util.Set<Call> speakered = new java.util.HashSet<>();
    static Runnable onChange;                 // CallActivity listens here
    // "calls-quiet": an earlier "calls" channel was made loud, and Android never lets an app quieten a channel afterwards
    private static final String CHANNEL = "calls-quiet", RING = "calls-incoming";
    static volatile boolean screenShown;      // CallActivity is on screen: no pop-up notice over it
    static volatile boolean screenAlive;      // CallActivity exists (even if the lock screen is handing over to it)
    private static final int ID = 7201;

    private final Call.Callback cb = new Call.Callback() {
        @Override public void onStateChanged(Call c, int state) {
            // Caretaken mode: answered calls go to the speaker, so nobody has to hold the phone to their ear
            if (state == Call.STATE_ACTIVE && Care.speakerCalls(InCall.this) && !speakered.contains(c)) { speakered.add(c); speakerOn(true); }
            changed();
        }
        @Override public void onDetailsChanged(Call c, Call.Details d) { changed(); }
    };

    private long addedAt;
    private boolean popped;

    @Override public void onCallAdded(Call c) {
        me = this; addedAt = android.os.SystemClock.elapsedRealtime();
        synchronized (calls) { calls.add(c); }
        c.registerCallback(cb);
        show();
        changed();
    }

    @Override public void onCallRemoved(Call c) {
        c.unregisterCallback(cb); speakered.remove(c);
        synchronized (calls) { calls.remove(c); }
        if (current() == null) {
            NotificationManager nm = getSystemService(NotificationManager.class); if (nm != null) nm.cancel(ID);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> Missed.notifyWaiting(this), 2500);   // once the call log has it
        }
        changed();
    }

    @Override public void onCallAudioStateChanged(CallAudioState s) { changed(); }
    @Override public void onDestroy() { if (me == this) me = null; super.onDestroy(); }

    void refresh() { Call c = current(); if (c != null) notifyOngoing(c); }

    private void changed() {
        Runnable r = onChange;
        if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        Call c = current();
        if (c != null) notifyOngoing(c);
    }

    /** The call that matters now: one ringing in, else the live one, else any still going. */
    static Call current() {
        synchronized (calls) {
            Call best = null;
            for (Call c : calls) {
                int s = c.getDetails().getState();
                if (s == Call.STATE_RINGING) return c;
                if (s == Call.STATE_DISCONNECTED) continue;
                if (best == null || s == Call.STATE_ACTIVE) best = c;
            }
            return best;
        }
    }

    /** Open the call screen (over the lock screen); the full-screen notice does it when Android won't let us start it directly. */
    private void show() {
        Intent i = new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            if (android.os.Build.VERSION.SDK_INT >= 33) o.setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR);   // never an icon
            startActivity(i, o.toBundle());
        } catch (Exception ignored) {}
        Call c = current();
        if (c != null) notifyOngoing(c);
    }

    private void notifyOngoing(Call c) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(RING) == null) {
            NotificationChannel ch = new NotificationChannel(RING, "Calls coming in", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);    // Android rings; this notice stays silent
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel("calls") != null) nm.deleteNotificationChannel("calls");
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        boolean ringing = c.getDetails().getState() == Call.STATE_RINGING;
        // the pop-up is only a fallback: give the call screen 1.5 s to open by itself first
        boolean popUp = ringing && !screenShown && !screenAlive && android.os.SystemClock.elapsedRealtime() - addedAt > 1500;
        if (ringing && !screenShown && !screenAlive && !popUp) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refresh, 1600);
            return;
        }
        String who = CallActivity.who(this, c);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (popped && !popUp) nm.cancel(ID);     // the call screen is up: withdraw the pop-up rather than let it linger
        popped = popUp;
        Notification.Builder b = new Notification.Builder(this, popUp ? RING : CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(ringing ? who + " is calling" : "On a call with " + who)
                .setContentText(ringing ? "Tap to answer." : "Tap to see the call.")
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open);
        if (popUp) b.setFullScreenIntent(open, true);
        nm.notify(ID, b.build());
    }

    // ------------------------------------------------------------------ what the call screen asks for
    static void answer(Call c) { if (c != null) c.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY); }
    static void decline(Call c) { if (c != null) c.reject(false, null); }
    static void hangUp(Call c) { if (c != null) c.disconnect(); }

    static boolean speaker() { InCall s = me; CallAudioState a = s == null ? null : s.getCallAudioState(); return a != null && a.getRoute() == CallAudioState.ROUTE_SPEAKER; }
    static boolean muted() { InCall s = me; CallAudioState a = s == null ? null : s.getCallAudioState(); return a != null && a.isMuted(); }
    static void speakerOn(boolean on) { InCall s = me; if (s != null) s.setAudioRoute(on ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_WIRED_OR_EARPIECE); }
    static void muteOn(boolean on) { InCall s = me; if (s != null) s.setMuted(on); }
}
