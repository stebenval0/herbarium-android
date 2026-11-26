package com.herbarium.home;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;

/**
 * Caretaken mode: for a phone that someone else looks after. One switch (Accessibility page, or over adb with
 * `am broadcast -n com.herbarium.home/.Pair --ez caretaken true`), and the place every caretaken behaviour asks.
 *
 * What it does:
 *   - Home is about calling: missed calls from people allowed to ring wait in big letters until returned (one tap
 *     calls back), the person's starred contacts are big call buttons, and the only apps are Phone, Messages and Contacts;
 *   - the same calls to return lead the lock screen, and sit as a notice in Android's list;
 *   - calls go through: the ringer is kept on and loud, and Do Not Disturb is lifted;
 *   - declined strangers are still recorded, but never shown;
 *   - the keyboard opens listening (voice typing first, letters one tap away).
 */
final class Care {
    private Care() {}

    static boolean on(Context c) { return Theme.prefs(c).getBoolean("caretaken", false); }
    static void set(Context c, boolean on) { Theme.prefs(c).edit().putBoolean("caretaken", on).apply(); }

    // ------------------------------------------------------------------ the helper PIN
    // With Caretaken mode on and a PIN set, Herbarium's settings and Android's Settings open only for a helper who
    // knows it, for 10 minutes at a time. Stored salted and hashed, never as the digits. Forgotten: reset over adb
    // (`am broadcast -n com.herbarium.home/.Pair --ez helperPinReset true`).
    private static final long HELPER_MS = 10 * 60_000L;

    static boolean hasPin(Context c) { return !Theme.prefs(c).getString("helperPin", "").isEmpty(); }

    static void setPin(Context c, String pin) {
        byte[] salt = new byte[16]; new java.security.SecureRandom().nextBytes(salt);
        String s = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP);
        Theme.prefs(c).edit().putString("helperPin", s + ":" + hash(s, pin)).apply();
        helperIn(c);
    }

    static void clearPin(Context c) { Theme.prefs(c).edit().remove("helperPin").apply(); }

    /** Check a PIN; a right one opens settings for 10 minutes. Wrong tries slow down after three. */
    static boolean tryPin(Context c, String pin) {
        android.content.SharedPreferences p = Theme.prefs(c);
        long now = System.currentTimeMillis();
        if (pinWait(c) > 0) return false;
        String[] v = p.getString("helperPin", "").split(":");
        if (v.length == 2 && hash(v[0], pin).equals(v[1])) { p.edit().putInt("pinWrong", 0).apply(); helperIn(c); return true; }
        int wrong = p.getInt("pinWrong", 0) + 1;
        p.edit().putInt("pinWrong", wrong).putLong("pinWaitUntil", wrong >= 3 ? now + Math.min(300_000L, 15_000L * (wrong - 2)) : 0).apply();
        return false;
    }

    /** Seconds before another try is allowed (0 = now). */
    static long pinWait(Context c) { long w = Theme.prefs(c).getLong("pinWaitUntil", 0) - System.currentTimeMillis(); return w > 0 && w <= 300_000L ? w / 1000 + 1 : 0; }

    private static void helperIn(Context c) { Theme.prefs(c).edit().putLong("helperUntil", System.currentTimeMillis() + HELPER_MS).apply(); }
    static void helperOut(Context c) { Theme.prefs(c).edit().putLong("helperUntil", 0).apply(); }

    /** Settings are closed to the person: Caretaken mode is on, a PIN is set, and no helper is in. */
    static boolean settingsLocked(Context c) {
        long left = Theme.prefs(c).getLong("helperUntil", 0) - System.currentTimeMillis();
        return on(c) && hasPin(c) && !(left > 0 && left <= HELPER_MS);     // a moved clock can't stretch a helper's 10 minutes
    }

    private static String hash(String salt, String pin) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((salt + "|" + pin).getBytes("UTF-8"));
            for (int i = 0; i < 20000; i++) h = md.digest(h);            // slow to guess
            return android.util.Base64.encodeToString(h, android.util.Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    /** Answered calls go to the speaker (Caretaken mode; can be switched off). */
    static boolean speakerCalls(Context c) { return on(c) && Theme.prefs(c).getBoolean("speakerCalls", true); }

    /** The keyboard starts with voice typing. */
    static boolean voiceFirst(Context c) { return on(c); }

    /** What Caretaken mode asks Android for: seeing missed calls, and calling back with one tap. */
    static final String[] PERMISSIONS = {Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS};

    /** The only apps Home shows in Caretaken mode. */
    static boolean showsApp(Context c, String pkg) {
        return pkg.equals(Apps.dialer(c)) || pkg.equals(Apps.messages(c)) || pkg.equals(Apps.contacts(c));
    }

    /** Bring everything in line with the switch: the ringer, and the call-back notice. */
    static void apply(Context c) {
        if (on(c)) keepRinging(c);
        new Thread(() -> Missed.notifyWaiting(c.getApplicationContext())).start();   // reads the call log
    }

    private static long lastCheck;

    /** Calls must be heard: ringer on (not silent or vibrate), loud, and no Do Not Disturb. Cheap to call often. */
    static void keepRinging(Context c) { keepRinging(c, false); }

    /** `now`: a call is coming in, check whatever the time since the last check. */
    static void keepRinging(Context c, boolean now0) {
        if (!on(c)) return;
        long now = SystemClock.elapsedRealtime();
        if (!now0 && now - lastCheck < 15_000) return;
        lastCheck = now;
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            boolean policy = nm != null && nm.isNotificationPolicyAccessGranted();
            if (policy && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am == null) return;
            if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL && (policy || am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE))
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_RING), want = Math.round(max * .85f);
            if (am.getStreamVolume(AudioManager.STREAM_RING) < want) am.setStreamVolume(AudioManager.STREAM_RING, want, 0);
        } catch (Exception ignored) {}   // without Do Not Disturb access Android may refuse some of this
    }
}
