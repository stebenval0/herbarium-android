package com.herbarium.home;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls to return. A missed call from someone who's allowed to ring (contacts or the list) waits here until
 * they're called back, they get through, or it's put aside; Caretaken mode shows these big on Home and the lock
 * screen, and one tap calls back. Declined strangers never appear here (they aren't "missed", they were turned away).
 */
final class Missed {
    private Missed() {}
    private static final long WINDOW = 3 * 86_400_000L;
    static final String CHANGED = "com.herbarium.home.MISSED_CHANGED";   // to Home: the calls to return changed     // older than three days: let it go

    static final class Call { String number, name; long at; int times; }

    static boolean canRead(Context c) { return c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED; }
    static boolean canCall(Context c) { return c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED; }

    /** Who's waiting for a call back, newest first, one entry per person. */
    static List<Call> waiting(Context c) { return waiting(c, false); }

    /** `anyone`: also callers who rang for another reason (a second try, screening off, after an emergency call),
     *  but never strangers Herbarium turned away. */
    static List<Call> waiting(Context c, boolean anyone) {
        List<Call> out = new ArrayList<>();
        if (!canRead(c)) return out;
        long since = System.currentTimeMillis() - WINDOW;
        JSONObject done = putAside(c);
        Map<String, Call> byNumber = new LinkedHashMap<>();
        Map<String, Long> reached = new java.util.HashMap<>();   // the last time each number was spoken with (either way)
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.CACHED_NAME},
                CallLog.Calls.DATE + " > ?", new String[]{String.valueOf(since)}, CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext()) {
                String number = cur.getString(0); int type = cur.getInt(1); long at = cur.getLong(2); long secs = cur.getLong(3);
                String n = Calls.norm(number);
                if (n.isEmpty()) continue;
                if ((type == CallLog.Calls.OUTGOING_TYPE || type == CallLog.Calls.INCOMING_TYPE) && secs > 0) {   // an unanswered call back still leaves them waiting
                    if (!reached.containsKey(n)) reached.put(n, at);
                    continue;
                }
                if (type != CallLog.Calls.MISSED_TYPE && type != CallLog.Calls.REJECTED_TYPE) continue;   // "Not now" on the call screen counts too
                if (reached.containsKey(n) && reached.get(n) > at) continue;      // already spoken since
                if (done.optLong(n) >= at) continue;                             // put aside
                Call m = byNumber.get(n);
                if (m == null) {
                    if (!(Calls.inContacts(c, number) || Calls.onList(c, number) || (anyone && !Calls.declinedAround(c, number, at)))) continue;
                    m = new Call(); m.number = number; m.at = at; m.name = name(c, number, cur.getString(4));
                    byNumber.put(n, m);
                }
                m.times++;
            }
        } catch (Exception ignored) {}
        out.addAll(byNumber.values());
        return out;
    }

    static String name(Context c, String number, String cached) {
        if (cached != null && !cached.trim().isEmpty()) return cached.trim();
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            Uri u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            try (Cursor cur = c.getContentResolver().query(u, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
            } catch (Exception ignored) {}
        }
        org.json.JSONArray a = Calls.list(c, "allowed");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (Calls.norm(o.optString("number")).equals(Calls.norm(number)) && !o.optString("name").isEmpty()) return o.optString("name");
        }
        return android.telephony.PhoneNumberUtils.formatNumber(number, "US");
    }

    /** First name only, for big buttons ("Call Sam back"). */
    static String first(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.matches(".*[A-Za-z].*")) { int sp = s.indexOf(' '); return sp > 0 ? s.substring(0, sp) : s; }
        return s;
    }

    private static JSONObject putAside(Context c) {
        try { return new JSONObject(Theme.prefs(c).getString("missedAside", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** "Not now": stop showing this person's missed calls (until they call again). */
    static void aside(Context c, String number) {
        JSONObject o = putAside(c);
        try { o.put(Calls.norm(number), System.currentTimeMillis()); } catch (Exception ignored) {}
        Theme.prefs(c).edit().putString("missedAside", o.toString()).apply();
        notifyWaiting(c);
        c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
    }

    /** Ring them back straight away (or open the dial pad with their number, if Herbarium may not place calls). */
    static void callBack(Context c, String number) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) { nm.cancel(ID); nm.cancel(QUIET_ID); }   // calling back: the notice steps aside (it returns if the call doesn't connect)
        Uri tel = Uri.fromParts("tel", number, null);
        // straight to Telecom when allowed: works over the lock screen too (an ACTION_CALL activity can't show there)
        if (canCall(c)) {
            try { android.telecom.TelecomManager tm = c.getSystemService(android.telecom.TelecomManager.class); if (tm != null) { tm.placeCall(tel, new android.os.Bundle()); return; } }
            catch (Exception ignored) {}
        }
        Intent i = new Intent(canCall(c) ? Intent.ACTION_CALL : Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception e) { c.startActivity(new Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    }

    static void callBack(Activity a, String number) { callBack((Context) a, number); }

    // ------------------------------------------------------------------ the notice in Android's own list
    private static final String CHANNEL = "callbacks-2";   // no pop-up banner: Home and the lock screen already lead with the call
    private static final int ID = 7101, QUIET_ID = 7102;   // Caretaken's "Call X back"; normal mode's "X called"

    /** One big notice for whoever's waiting ("Call Sam back"), tapping it calls; gone once nobody is. */
    static void notifyWaiting(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        List<Call> w = Care.on(c) ? waiting(c) : new ArrayList<>();
        if (w.isEmpty()) { nm.cancel(ID); return; }
        if (InCall.current() != null) return;      // never over a call that's ringing or going on (it's updated after)
        if (nm.getNotificationChannel("callbacks") != null) nm.deleteNotificationChannel("callbacks");   // the old one popped a stock banner over Home
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Calls to return", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Someone called and is waiting for a call back."); ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }
        Call m = w.get(0);
        String who = first(m.name);
        Intent call = new Intent(c, CallBack.class).putExtra("number", m.number);
        PendingIntent tap = PendingIntent.getActivity(c, 1, call, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String more = w.size() > 1 ? " (and " + Summary.count(w.size() - 1, "other person") + ")" : "";
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle("Call " + who + " back")
                .setContentText(m.name + " called" + (m.times > 1 ? " " + Summary.count(m.times, "time").replace("a time", "once") : "") + more + ". Tap to call.")
                .setStyle(new Notification.BigTextStyle().bigText(m.name + " called you. Tap here to call " + who + " back."))
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setContentIntent(tap).setAutoCancel(false).setOngoing(true)
                .setOnlyAlertOnce(true)                  // says so once; refreshing it (screen on, new calls) stays quiet
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "Call " + who + " back", tap).build())
                .build();
        nm.notify(ID, n);
    }

    /**
     * As the phone's calling app, Herbarium is asked to show missed calls itself (otherwise Android posts its own
     * "Missed call" notice). It answers with its own: the big "Call … back" notice in Caretaken mode, and in normal
     * mode a quiet one for people allowed to ring.
     */
    public static class Show extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            PendingResult r = goAsync();
            new Thread(() -> {
                try {
                    if (Care.on(c)) notifyWaiting(c);
                    else notifyQuiet(c);
                    c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
                } finally { r.finish(); }
            }).start();
        }
    }

    /** Normal mode: one gentle notice for missed calls from people allowed to ring (tap: call back). */
    static void notifyQuiet(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        List<Call> w = waiting(c, true);
        if (w.isEmpty()) { nm.cancel(QUIET_ID); return; }
        if (nm.getNotificationChannel("missed-quiet") == null)
            nm.createNotificationChannel(new NotificationChannel("missed-quiet", "Missed calls", NotificationManager.IMPORTANCE_DEFAULT));
        Call m = w.get(0);
        PendingIntent tap = PendingIntent.getActivity(c, 1, new Intent(c, CallBack.class).putExtra("number", m.number), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(QUIET_ID, new Notification.Builder(c, "missed-quiet").setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle(w.size() == 1 ? m.name + " called" : Summary.capital(Summary.count(w.size(), "missed call")))
                .setContentText("Tap to call " + first(m.name) + " back.").setCategory(Notification.CATEGORY_MISSED_CALL)
                .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).build());
    }

    /** Opened from the notice: calls back at once, then steps aside. */
    public static class CallBack extends Activity {
        @Override protected void onCreate(android.os.Bundle b) {
            super.onCreate(b);
            String n = getIntent().getStringExtra("number");
            if (n != null) callBack(this, n);
            finish();
        }
    }
}
