package com.herbarium.home;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Herbarium keeps the phone's notifications itself, so they can be summed up in a few calm lines instead of a pile.
 * With "Keep notifications in Herbarium" on (the default), ordinary notifications are taken out of Android's own list
 * once Herbarium has them; calls, music controls and anything ongoing are left alone.
 */
public class Notes extends NotificationListenerService {
    static final String CHANGED = "com.herbarium.home.NOTES_CHANGED";
    static volatile Notes instance;
    /** Open actions for notifications seen since the app started (they can't be saved to disk). */
    static final Map<String, PendingIntent> OPEN = new HashMap<>();

    static final class Note {
        String key, pkg, app, title, text, category, person; long when; boolean message, call, important;
        JSONObject json() {
            JSONObject o = new JSONObject();
            try { o.put("key", key).put("pkg", pkg).put("app", app).put("title", title).put("text", text).put("cat", category)
                    .put("person", person).put("when", when).put("msg", message).put("call", call).put("imp", important); } catch (Exception ignored) {}
            return o;
        }
        static Note of(JSONObject o) {
            Note n = new Note(); n.key = o.optString("key"); n.pkg = o.optString("pkg"); n.app = o.optString("app"); n.title = o.optString("title");
            n.text = o.optString("text"); n.category = o.optString("cat"); n.person = o.optString("person"); n.when = o.optLong("when");
            n.message = o.optBoolean("msg"); n.call = o.optBoolean("call"); n.important = o.optBoolean("imp"); return n;
        }
    }

    static boolean enabled(Context c) {
        String s = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, Notes.class).flattenToString());
    }
    /** Tidy Android's list only when Herbarium is the home screen (otherwise the person would lose sight of them). */
    static boolean tidy(Context c) { return Theme.prefs(c).getBoolean("tidyNotes", true) && isHome(c); }
    static boolean isHome(Context c) {
        android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    // ---------------------------------------------------------------- the store (kept for two days, newest first)
    // Held in memory; written to disk in the background at most twice a second, so a burst of notifications
    // (a busy group chat) is one write instead of fifty, and nothing is parsed on the main thread after the first read.
    private static List<Note> cache;
    private static final android.os.Handler writer;
    static {
        android.os.HandlerThread th = new android.os.HandlerThread("herbarium-notes"); th.start();
        writer = new android.os.Handler(th.getLooper());
    }

    private static List<Note> load(Context c) {
        if (cache != null) return cache;
        List<Note> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Theme.prefs(c).getString("notes2", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Note.of(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        return cache = out;
    }

    static synchronized List<Note> all(Context c) {
        long cutoff = System.currentTimeMillis() - 2 * 86_400_000L;
        List<Note> out = new ArrayList<>();
        for (Note n : load(c)) if (n.when >= cutoff) out.add(n);
        return out;
    }
    private static synchronized void save(Context c, List<Note> list) {
        cache = new ArrayList<>(list.subList(0, Math.min(200, list.size())));
        Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        writer.removeCallbacksAndMessages(null);
        writer.postDelayed(() -> {
            JSONArray a = new JSONArray();
            synchronized (Notes.class) { for (Note n : cache) a.put(n.json()); }
            Theme.prefs(app).edit().putString("notes2", a.toString()).apply();
        }, 500);
        c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
    }
    static synchronized void dismiss(Context c, String key) {
        List<Note> l = all(c); l.removeIf(n -> n.key.equals(key)); save(c, l); OPEN.remove(key);
        Notes s = instance; if (s != null) try { s.cancelNotification(key); } catch (Exception ignored) {}
    }
    static synchronized void clearAll(Context c) {
        List<Note> keep = new ArrayList<>(); save(c, keep); OPEN.clear();
        Notes s = instance; if (s != null) try { s.cancelAllNotifications(); } catch (Exception ignored) {}
    }

    /** Open what a notification points to (or just its app, if Herbarium was restarted since). */
    static void open(Context c, Note n) {
        PendingIntent pi = OPEN.get(n.key);
        try { if (pi != null) { pi.send(); dismiss(c, n.key); return; } } catch (Exception ignored) {}
        Intent i = c.getPackageManager().getLaunchIntentForPackage(n.pkg);
        if (i != null) c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        dismiss(c, n.key);
    }

    // ---------------------------------------------------------------- listening
    @Override public void onListenerConnected() {
        instance = this;
        try { for (StatusBarNotification s : getActiveNotifications()) take(s); } catch (Exception ignored) {}
    }
    @Override public void onListenerDisconnected() { instance = null; }

    /** Open Android's page for letting Herbarium read notifications (its own entry if the phone has that page, else the list). */
    static void askAccess(android.app.Activity a) {
        Guard.allow(a, "settings");
        Intent detail = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", new android.content.ComponentName(a, Notes.class).flattenToString());
        try { a.startActivity(detail); return; } catch (Exception ignored) {}
        try { a.startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); } catch (Exception ignored) {}
    }

    /** Herbarium's App info page: where ⋮ → "Allow restricted settings" lives. */
    static void openAppInfo(android.app.Activity a) {
        Guard.allow(a, "settings");
        try { a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", a.getPackageName(), null))); } catch (Exception ignored) {}
    }

    @Override public void onNotificationRemoved(StatusBarNotification s) { AlarmActivity.ended(this, s.getKey()); }

    @Override public void onNotificationPosted(StatusBarNotification s) {
        if (AlarmActivity.isRinging(s)) { AlarmActivity.ringing(this, s); return; }   // an alarm ringing: Herbarium's alarm screen
        take(s);
    }

    private void take(StatusBarNotification s) {
        Notification n = s.getNotification();
        // Herbarium's own notices are skipped, except its texts (it's the phone's texting app now): those belong in the summary
        if (s.getPackageName().equals(getPackageName()) && (n.getChannelId() == null || !n.getChannelId().startsWith("texts-") || n.getChannelId().equals("texts-failed"))) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;      // the "3 new messages" header; the messages come separately
        boolean ongoing = s.isOngoing() || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        String cat = n.category == null ? "" : n.category;
        if (ongoing || Notification.CATEGORY_TRANSPORT.equals(cat) || Notification.CATEGORY_PROGRESS.equals(cat) || Notification.CATEGORY_SERVICE.equals(cat)) return;
        Bundle x = n.extras;
        Note note = new Note();
        note.key = s.getKey(); note.pkg = s.getPackageName(); note.when = s.getPostTime(); note.category = cat;
        note.app = label(s.getPackageName());
        note.title = str(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (note.title.isEmpty()) note.title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        note.text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (note.text.isEmpty()) note.text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        // messaging apps: the last message and who sent it
        Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) msgs[msgs.length - 1];
            CharSequence who = last.getCharSequence("sender"); CharSequence t = last.getCharSequence("text");
            if (t != null) note.text = t.toString();
            if (who != null) note.person = who.toString();
        }
        if (note.person == null || note.person.isEmpty()) note.person = Notification.CATEGORY_MESSAGE.equals(cat) ? note.title : "";
        note.message = Notification.CATEGORY_MESSAGE.equals(cat) || msgs != null;
        note.call = Notification.CATEGORY_CALL.equals(cat) || Notification.CATEGORY_MISSED_CALL.equals(cat);
        note.important = note.call || note.message || Notification.CATEGORY_ALARM.equals(cat) || Notification.CATEGORY_REMINDER.equals(cat)
                || Notification.CATEGORY_EVENT.equals(cat) || n.priority >= Notification.PRIORITY_HIGH;
        if (note.title.isEmpty() && note.text.isEmpty()) return;
        if (n.contentIntent != null) synchronized (OPEN) { OPEN.put(note.key, n.contentIntent); }
        synchronized (Notes.class) {
            List<Note> l = all(this);
            l.removeIf(o -> o.key.equals(note.key));
            l.add(0, note);
            save(this, l);
        }
        // alarms are never tidied away (an "upcoming alarm" notice holds its "dismiss early" control)
        if (tidy(this) && s.isClearable() && !note.call && !Notification.CATEGORY_ALARM.equals(n.category)) { try { cancelNotification(s.getKey()); } catch (Exception ignored) {} }
    }

    private String label(String pkg) {
        try { PackageManager pm = getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }
    private static String str(CharSequence c) { return c == null ? "" : c.toString().trim(); }
}
