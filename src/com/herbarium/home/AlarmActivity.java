package com.herbarium.home;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * An alarm ringing, in Herbarium. Whatever clock app the alarm belongs to (Google's Clock, the phone's own, any
 * other), its ringing notice carries "Stop"/"Dismiss" and "Snooze"; Herbarium shows its own screen over the lock with
 * the time, the alarm's name, a big Stop and a Snooze, and presses the clock's own controls. So every existing alarm
 * keeps working, and is stopped through Herbarium.
 */
public class AlarmActivity extends Activity {

    /** The alarm that's ringing now (from Notes, the notification listener), or null. */
    static volatile Ringing now;

    static final class Ringing {
        String key, pkg, title, text; PendingIntent stop, snooze; String snoozeLabel; PendingIntent open;
    }

    /** Is this notice an alarm ringing right now (not "upcoming alarm")? */
    static boolean isRinging(StatusBarNotification s) {
        Notification n = s.getNotification();
        if (!Notification.CATEGORY_ALARM.equals(n.category)) return false;
        boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags & Notification.FLAG_NO_CLEAR) != 0;
        return (n.fullScreenIntent != null || ongoing) && (stopAction(n) != null || snoozeAction(n) != null);
    }

    static Notification.Action stopAction(Notification n) { return action(n, "stop", "dismiss", "turn off", "off", "done"); }
    static Notification.Action snoozeAction(Notification n) { return action(n, "snooze"); }

    private static Notification.Action action(Notification n, String... words) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            String t = a.title == null ? "" : a.title.toString().toLowerCase(Locale.ROOT);
            for (String w : words) if (t.contains(w)) return a;
        }
        return null;
    }

    /** Called by Notes when an alarm starts ringing: Herbarium's alarm screen comes up (over the lock). */
    static void ringing(Context c, StatusBarNotification s) {
        Notification n = s.getNotification();
        Ringing r = new Ringing();
        r.key = s.getKey(); r.pkg = s.getPackageName();
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE), text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        r.title = title == null ? "" : title.toString(); r.text = text == null ? "" : text.toString();
        Notification.Action st = stopAction(n), sn = snoozeAction(n);
        r.stop = st == null ? null : st.actionIntent;
        r.snooze = sn == null ? null : sn.actionIntent; r.snoozeLabel = sn == null ? "Snooze" : sn.title.toString();
        r.open = n.fullScreenIntent != null ? n.fullScreenIntent : n.contentIntent;
        boolean fresh = now == null || !now.key.equals(r.key);
        now = r;
        if (fresh || !visible) show(c);
    }

    /** The clock app took its notice away (stopped elsewhere, or timed out): close. */
    static void ended(Context c, String key) {
        Ringing r = now;
        if (r != null && r.key.equals(key)) { now = null; AlarmActivity a = shown; if (a != null) a.finish(); }
    }

    /** Bring the alarm screen to the front (also when the clock app's own screen pops up over it). */
    static void show(Context c) {
        if (now == null) return;
        try { c.startActivity(new Intent(c, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
        catch (Exception ignored) {}
    }

    private static volatile AlarmActivity shown;
    private static volatile boolean visible;
    private Theme t;
    private LinearLayout col;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true); setTurnScreenOn(true);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        t = Theme.get(this);
        col = Ui.column(this); col.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.Bitmap paper = Paper.ready(this, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels, t);
        col.setBackground(paper != null ? new android.graphics.drawable.BitmapDrawable(getResources(), paper) : Paper.quick(t));
        col.setOnApplyWindowInsetsListener((v, in) -> { v.setPadding(t.dp(24), t.dp(80), t.dp(24), in.getInsets(WindowInsets.Type.navigationBars()).bottom + t.dp(28)); return in; });
        setContentView(col);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
        shown = this;
    }

    @Override protected void onResume() { super.onResume(); visible = true; if (now == null) { finish(); return; } render(); }
    @Override protected void onPause() { visible = false; super.onPause(); }
    @Override protected void onDestroy() { if (shown == this) shown = null; super.onDestroy(); }
    @Override public void onBackPressed() { /* an alarm is stopped or snoozed, not backed out of */ }

    private void render() {
        Ringing r = now; if (r == null) return;
        col.removeAllViews();
        Calendar d = Calendar.getInstance();
        int hr = d.get(Calendar.HOUR) == 0 ? 12 : d.get(Calendar.HOUR);
        col.addView(Ui.centered(Ui.caps(this, t, "Alarm", 22, t.sage)));
        TextView time = Ui.centered(Ui.text(this, t, hr + ":" + String.format(Locale.US, "%02d", d.get(Calendar.MINUTE)), 96, t.ink, Theme.regular));
        col.addView(time, Ui.wide(t.sdp(6)));
        String label = !r.text.isEmpty() && !r.text.matches("(?i).*\\d{1,2}:\\d{2}.*") ? r.text : r.title.matches("(?i)alarm|.*\\d{1,2}:\\d{2}.*") ? "" : r.title;
        if (!label.isEmpty()) col.addView(Ui.centered(Ui.text(this, t, label, 26, t.sepia, Theme.italic)), Ui.wide(t.sdp(8)));
        col.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1));
        TextView stop = Ui.primary(this, t, "Stop", v -> press(r.stop != null ? r.stop : r.open, true));
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(28));
        stop.setMinHeight(Math.round(t.dp(112) * (1 + (t.scale - 1) * .35f)));
        stop.setContentDescription("Stop the alarm");
        col.addView(stop, Ui.wide());
        if (r.snooze != null) {
            TextView snooze = Ui.button(this, t, plainSnooze(r.snoozeLabel), v -> press(r.snooze, false));
            snooze.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(21));
            snooze.setMinHeight(Math.round(t.dp(80) * (1 + (t.scale - 1) * .35f)));
            col.addView(snooze, Ui.wide(t.sdp(16)));
        }
    }

    private static String plainSnooze(String s) {
        if (s == null || s.trim().isEmpty()) return "Snooze";
        String x = s.trim(); return Character.toUpperCase(x.charAt(0)) + x.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Press the clock app's own control, then step away (the clock's notice goes, and so does this screen). */
    private void press(PendingIntent p, boolean stopping) {
        try { if (p != null) p.send(); } catch (Exception ignored) {}
        now = null;
        finish(); overridePendingTransition(0, 0);
        LockActivity.show(this);          // back to Herbarium's lock screen, not the clock's or Android's
    }
}
