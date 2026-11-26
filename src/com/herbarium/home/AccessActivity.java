package com.herbarium.home;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/** Accessibility: text size, steadier taps, contrast, day/night, and who may ring the phone. */
public class AccessActivity extends Activity {
    private Theme t;
    private int scrollY;
    private Steady.Scroll scroll;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        // paper right up to the top edge, as everywhere else (without this the camera cut-out band shows black)
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        build();
    }
    @Override protected void onResume() { super.onResume(); build(); }

    /** "✓ App guard": the tick is held to its first word, so it never ends a line on its own. */
    private static String mark(boolean ok, String what) { return (ok ? "✓" : "✗") + "\u00a0\u00a0" + what; }

    private void rebuild() { scrollY = scroll == null ? 0 : scroll.getScrollY(); build(); }

    private void build() {
        t = Theme.get(this);
        SharedPreferences p = Theme.prefs(this);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        scroll = new Steady.Scroll(this, t);
        Bitmap paper = Paper.ready(this, W, H, t);
        scroll.setBackground(paper != null ? new BitmapDrawable(getResources(), paper) : Paper.quick(t));
        LinearLayout col = Ui.column(this);
        col.setPadding(t.dp(24), t.dp(48), t.dp(24), t.dp(80));
        scroll.addView(col, Ui.wide());

        if (Care.settingsLocked(this)) { gate(col); setContentView(scroll); return; }
        col.addView(Ui.centered(Ui.caps(this, t, "Settings", 30, t.ink)));
        col.addView(Ui.centered(Ui.text(this, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
        TextView back = Ui.button(this, t, "▾  Back", v -> finish());
        LinearLayout.LayoutParams bp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); bp.gravity = Gravity.CENTER_HORIZONTAL; bp.topMargin = t.sdp(10);
        col.addView(back, bp);

        // [onboarding] anything the phone still needs from setup, one tap away
        int unfinished = SetupActivity.unfinished(this);
        if (unfinished > 0) {
            TextView finish = Ui.primary(this, t, "Finish setting up (" + Summary.number(unfinished) + " left)", v -> startActivity(new Intent(this, SetupActivity.class).putExtra("list", true)));
            col.addView(finish, Ui.wide(t.sdp(16)));
        }

        // ---------------- text size
        col.addView(Ui.heading(this, t, "Text size"));
        TextView sample = Ui.body(this, t, HomeActivity.longDate() + ". Everything on the phone grows or shrinks together.", 20);
        col.addView(sample, Ui.wide(t.sdp(6)));
        LinearLayout sizes = Ui.row(this);
        TextView smaller = Steady.repeatable(Ui.button(this, t, "A  smaller", v -> setScale(-.1f)));   // tapped several times on purpose
        TextView larger = Steady.repeatable(Ui.button(this, t, "A  larger", v -> setScale(+.1f)));
        smaller.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(15)); larger.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(20));
        TextView pct = Ui.centered(Ui.text(this, t, Math.round(t.scale * 100) + "%", 22, t.ink, Theme.semi)); pct.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lw = Ui.weight(); lw.rightMargin = t.dp(8);
        if (t.scale >= 1.3f) {
            sizes.setOrientation(LinearLayout.VERTICAL);
            sizes.addView(pct, Ui.wide()); sizes.addView(smaller, Ui.wide(t.dp(8))); sizes.addView(larger, Ui.wide(t.dp(8)));
        } else {
            sizes.addView(smaller, lw); sizes.addView(pct, Ui.lp(t.sdp(84), ViewGroup.LayoutParams.WRAP_CONTENT)); LinearLayout.LayoutParams rw = Ui.weight(); rw.leftMargin = t.dp(8); sizes.addView(larger, rw);
        }
        col.addView(sizes, Ui.wide(t.sdp(14)));

        // ---------------- touch and sight
        col.addView(Ui.heading(this, t, "Touch"));
        col.addView(Ui.toggle(this, t, "Steadier taps", "A tap still counts if your finger slides or rests a moment, and a quick accidental second tap is ignored.",
                t.steady, v -> { p.edit().putBoolean("steady", !t.steady).apply(); rebuild(); }));
        col.addView(Ui.heading(this, t, "Caretaken"));
        col.addView(Ui.toggle(this, t, "Caretaken mode", "For a phone someone else looks after. Home shows only calling, messages and contacts; "
                        + "missed calls wait in big letters until they're returned; the ringer stays on; and the keyboard listens first.",
                Care.on(this), v -> {
                    if (Care.on(this)) {
                        // switching it off always takes the helper PIN, even while settings are open
                        if (Care.hasPin(this)) askPin("Type the helper PIN to switch Caretaken mode off.", () -> { Care.set(this, false); Care.apply(this); rebuild(); });
                        else { Care.set(this, false); Care.apply(this); rebuild(); }
                    } else {
                        // switching it on starts by choosing the helper PIN (without one, anyone could switch it off again)
                        if (Care.hasPin(this)) { Care.set(this, true); Care.apply(this); rebuild(); }
                        else choosePin(() -> { Care.set(this, true); Care.apply(this); rebuild(); });
                    }
                }));
        if (Care.on(this)) {
            if (Texts.isDefault(this)) col.addView(Ui.button(this, t, "See all messages, including held-back ones", v -> startActivity(new Intent(this, MessagesActivity.class).putExtra("helper", true))), Ui.wide(t.sdp(8)));
            col.addView(Ui.toggle(this, t, "Answer calls on the speaker", "Nobody has to hold the phone to their ear; it can rest on the table.",
                    Care.speakerCalls(this), v -> { Theme.prefs(this).edit().putBoolean("speakerCalls", !Care.speakerCalls(this)).apply(); rebuild(); }), Ui.wide(t.sdp(8)));
            // the helper PIN: only someone who knows it can open these settings (or Android's) while Caretaken mode is on
            col.addView(Ui.body(this, t, "Helper PIN", 20), Ui.wide(t.sdp(14)));
            if (!Care.hasPin(this)) col.addView(Ui.text(this, t, "Set a PIN so only helpers can change settings. Without one, anyone can.", 17, t.ochre, Theme.regular), Ui.wide(t.sdp(4)));
            else col.addView(Ui.text(this, t, "Set. Settings ask for it, and close again 10 minutes after it's entered.", 17, t.sepia, Theme.regular), Ui.wide(t.sdp(4)));
            EditText pin1 = field(Care.hasPin(this) ? "New PIN (4 to 8 digits)" : "PIN (4 to 8 digits)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            EditText pin2 = field("The same PIN again", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            col.addView(pin1, Ui.wide(t.sdp(8))); col.addView(pin2, Ui.wide(t.sdp(8)));
            col.addView(Ui.primary(this, t, Care.hasPin(this) ? "Change the PIN" : "Set the PIN", v -> {
                String a = pin1.getText().toString(), b = pin2.getText().toString();
                if (!a.matches("[0-9]{4,8}")) { pin1.setError("4 to 8 digits"); return; }
                if (!a.equals(b)) { pin2.setError("These don't match"); return; }
                Care.setPin(this, a); rebuild();
            }), Ui.wide(t.sdp(10)));
            if (Care.hasPin(this)) col.addView(Ui.button(this, t, "Lock settings now", v -> { Care.helperOut(this); finish(); }), Ui.wide(t.sdp(10)));
            java.util.List<String> need = new java.util.ArrayList<>();
            for (String perm : Care.PERMISSIONS) if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(perm);
            if (!need.isEmpty()) col.addView(Ui.primary(this, t, "Let Herbarium see missed calls and call back", v -> {
                Guard.allow(this, "calls"); requestPermissions(need.toArray(new String[0]), 5); }), Ui.wide(t.sdp(10)));
            if (!getSystemService(android.app.NotificationManager.class).isNotificationPolicyAccessGranted())
                col.addView(Ui.button(this, t, "Let Herbarium keep the ringer on", v -> {
                    Guard.allow(this, "calls"); startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); }), Ui.wide(t.sdp(10)));
        }

        col.addView(Ui.heading(this, t, "Keyboard"));
        boolean kbd = Keys.isDefault(this);
        col.addView(Ui.text(this, t, kbd ? "Herbarium's keyboard is in use: big keys that grow with the text, word suggestions, and voice typing (the round microphone key)."
                : "The phone is using another keyboard. Herbarium's has big keys that grow with the text, word suggestions, and voice typing.", 17, t.sepia, Theme.regular));
        if (!kbd) {
            TextView kmsg = Ui.text(this, t, "", 17, t.ochre, Theme.regular);
            col.addView(Ui.primary(this, t, "Use Herbarium's keyboard", v -> { String next = Keys.choose(this); if (next.isEmpty()) rebuild(); else kmsg.setText(next); }), Ui.wide(t.sdp(10)));
            col.addView(kmsg, Ui.wide(t.sdp(6)));
        }

        col.addView(Ui.heading(this, t, "Seeing"));
        col.addView(Ui.toggle(this, t, "Stronger contrast", "Darker ink by day, brighter ink by night.",
                t.contrast, v -> { p.edit().putBoolean("contrast", !t.contrast).apply(); rebuild(); }));
        col.addView(Ui.body(this, t, "Day and night", 20), Ui.wide(t.sdp(12)));
        LinearLayout looks = Ui.row(this);
        String look = p.getString("look", "auto");
        for (String[] o : new String[][]{{"auto", "With the sun"}, {"day", "Always day"}, {"night", "Always night"}}) {
            TextView b = look.equals(o[0]) ? Ui.primary(this, t, o[1], v -> { p.edit().putString("look", o[0]).apply(); rebuild(); })
                                           : Ui.button(this, t, o[1], v -> { p.edit().putString("look", o[0]).apply(); rebuild(); });
            if (t.large()) { looks.addView(b, Ui.wide(looks.getChildCount() == 0 ? 0 : t.sdp(8))); continue; }   // one under another, words whole
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(13));
            LinearLayout.LayoutParams w = Ui.weight(); w.setMargins(t.dp(3), 0, t.dp(3), 0);
            looks.addView(b, w);
        }
        if (t.large()) looks.setOrientation(LinearLayout.VERTICAL);
        col.addView(looks, Ui.wide(t.sdp(8)));

        // ---------------- notifications
        col.addView(Ui.heading(this, t, "Notifications"));
        if (!Notes.enabled(this)) {
            col.addView(Ui.text(this, t, "Let Herbarium hold your notifications, so the home screen can sum them up in a few calm lines.", 17, t.sepia, Theme.regular));
            col.addView(Ui.primary(this, t, "Let Herbarium hold notifications", v -> Notes.askAccess(this)), Ui.wide(t.sdp(10)));
        }
        col.addView(Ui.toggle(this, t, "Keep notifications in Herbarium", "Takes them out of Android's own list once Herbarium has them. Calls and music controls stay."
                        + (Notes.isHome(this) ? "" : " (Starts once Herbarium is your home screen.)"),
                p.getBoolean("tidyNotes", true), v -> { p.edit().putBoolean("tidyNotes", !Notes.tidy(this)).apply(); rebuild(); }));

        // ---------------- lock screen
        col.addView(Ui.heading(this, t, "Lock screen"));
        col.addView(Ui.toggle(this, t, "Herbarium on the lock screen", "The time, the day's summary and a big Open button, instead of Android's own.",
                p.getBoolean("lockFace", true), v -> { p.edit().putBoolean("lockFace", !p.getBoolean("lockFace", true)).apply(); rebuild(); }));

        col.addView(Ui.toggle(this, t, "Herbarium's shade", "Pull down from the very top edge (or tap the time at the top of the home screen) for the day's notes and big controls."
                        + (Notes.isHome(this) ? "" : " (Starts once Herbarium is your home screen.)"),
                p.getBoolean("shade", true), v -> { p.edit().putBoolean("shade", !p.getBoolean("shade", true)).apply(); rebuild(); }));

        // ---------------- Clausus
        col.addView(Ui.heading(this, t, "Clausus"));
        String cl;
        if (State.token(this).isEmpty()) cl = "Not paired with a computer yet. On the computer, plug the phone in and run: clausus phone pair";
        else {
            long heard = State.lastHeard(this), mins = (System.currentTimeMillis() - heard) / 60000;
            cl = (State.always(this) ? "On: " + State.lists(this) + " are blocked until it's switched off on the computer."
                    : State.locked(this) ? "Blocking " + State.lists(this) + " for another " + State.fmt(State.remaining(this)) + "."
                    : "Off: nothing is blocked.")
                    + "\n" + (heard == 0 ? "Waiting to hear from the computer." : "Last heard from the computer " + (mins < 1 ? "just now" : mins + " min ago") + ".");
        }
        col.addView(Ui.text(this, t, cl, 17, t.sepia, Theme.regular));
        TextView inviteMsg = Ui.text(this, t, "", 17, t.ochre, Theme.regular);
        col.addView(Ui.button(this, t, "Paste the invitation", v -> { if (!InviteActivity.fromClipboard(this)) inviteMsg.setText("There's no invitation copied yet. Copy it from the keeper's message first."); }), Ui.wide(t.sdp(10)));
        col.addView(inviteMsg, Ui.wide(t.sdp(6)));
        boolean guardOn = State.guardOn(this), adminOn = State.adminOn(this), vpnOk = android.net.VpnService.prepare(this) == null;
        col.addView(Ui.text(this, t, String.join(t.large() ? "\n" : "     ", mark(guardOn, "App guard"), mark(adminOn, "Can't be uninstalled"), mark(vpnOk, "Website filter")), 16, guardOn && adminOn && vpnOk ? t.sage : t.ochre, Theme.regular), Ui.wide(t.sdp(8)));

        // ---------------- calls
        col.addView(Ui.heading(this, t, "Calls"));
        boolean care = Care.on(this);
        col.addView(Ui.text(this, t, "Only people you know can ring. Everyone saved in your contacts rings through, and so does anyone on the list below. "
                + (care ? "Other numbers are quietly turned away." : "Other numbers are quietly declined; they appear under “Recently declined”, where you can let them through."), 17, t.sepia, Theme.regular));
        RoleManager rm = getSystemService(RoleManager.class);
        boolean held = rm != null && (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) || rm.isRoleHeld(RoleManager.ROLE_DIALER));
        boolean contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        // calls in Herbarium: its own dial pad and call screen (big Answer, big Hang up), and it screens every call
        boolean dialer = rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        if (!dialer && rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            col.addView(Ui.primary(this, t, "Use Herbarium for calls", v -> { Guard.allow(this, "calls"); startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 3); }), Ui.wide(t.sdp(14)));
            col.addView(Ui.text(this, t, "Calls then come in on Herbarium's own screen, with big Answer and Hang up buttons.", 16, t.sepia, Theme.regular), Ui.wide(t.sdp(4)));
        } else if (dialer) col.addView(Ui.text(this, t, "Calls come in on Herbarium's own screen.", 17, t.sage, Theme.regular), Ui.wide(t.sdp(8)));
        if (!held && !dialer && rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            col.addView(Ui.primary(this, t, "Let Herbarium screen calls", v -> startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 1)), Ui.wide(t.sdp(14)));
        }
        if (!contacts) col.addView(Ui.button(this, t, "Let Herbarium see contacts", v -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 2)), Ui.wide(t.sdp(10)));
        col.addView(Ui.toggle(this, t, "Screen calls", held ? "On: only contacts and the list ring." : "Herbarium isn't screening calls yet. Tap the gold button above.",
                Calls.screening(this) && held, v -> { p.edit().putBoolean("screenCalls", !Calls.screening(this)).apply(); rebuild(); }), Ui.wide(t.sdp(8)));
        col.addView(Ui.toggle(this, t, "Let urgent callers through", "If the same number calls twice within 3 minutes, the second call rings.",
                Calls.repeatRule(this), v -> { p.edit().putBoolean("repeatCalls", !Calls.repeatRule(this)).apply(); rebuild(); }));

        col.addView(Ui.body(this, t, "Also allowed", 20), Ui.wide(t.sdp(16)));
        JSONArray allowed = Calls.list(this, "allowed");
        if (allowed.length() == 0) col.addView(Ui.text(this, t, "Nobody yet, besides your contacts.", 17, t.sepia, Theme.italic), Ui.wide(t.sdp(4)));
        for (int i = 0; i < allowed.length(); i++) {
            JSONObject o = allowed.optJSONObject(i);
            col.addView(personRow(o.optString("name"), o.optString("number"), "Remove", v -> { Calls.remove(this, o.optString("number")); rebuild(); }), Ui.wide(t.sdp(6)));
        }

        LinearLayout add = card();
        add.addView(Ui.caps(this, t, "Add a number", 15, t.sage));
        EditText name = field(t.large() ? "Name" : "Who is it? (e.g. Pharmacy)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText number = field(t.large() ? "Number" : "Their number", InputType.TYPE_CLASS_PHONE);
        add.addView(name, Ui.wide(t.sdp(8))); add.addView(number, Ui.wide(t.sdp(8)));
        add.addView(Ui.primary(this, t, "Add to the list", v -> {
            if (Calls.norm(number.getText().toString()).length() < 3) { number.setError("Type the phone number"); return; }
            Calls.allow(this, name.getText().toString(), number.getText().toString()); rebuild();
        }), Ui.wide(t.sdp(12)));
        col.addView(add, Ui.wide(t.sdp(14)));

        // declined calls are still kept, but never shown in Caretaken mode: a list of strangers only confuses
        JSONArray dec = care ? new JSONArray() : Calls.list(this, "declined");
        if (!care) col.addView(Ui.body(this, t, "Recently declined", 20), Ui.wide(t.sdp(20)));
        if (!care && dec.length() == 0) col.addView(Ui.text(this, t, "No calls have been declined.", 17, t.sepia, Theme.italic), Ui.wide(t.sdp(4)));
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (int i = 0; i < dec.length() && i < 12; i++) {
            JSONObject o = dec.optJSONObject(i); String num = o.optString("number");
            boolean hidden = Calls.norm(num).isEmpty();
            String f = hidden ? null : android.telephony.PhoneNumberUtils.formatNumber(num, "US");
            col.addView(personRow(hidden ? "A hidden number" : f != null ? f : num, df.format(new Date(o.optLong("at"))), hidden ? null : "Allow",
                    v -> { Calls.allow(this, "", num); rebuild(); }), Ui.wide(t.sdp(6)));
        }

        // ---------------- texts
        col.addView(Ui.heading(this, t, "Texts"));
        if (Texts.isDefault(this)) {
            col.addView(Ui.text(this, t, Care.on(this) ? "Herbarium keeps the texts. Only people you know are shown; links are taken out, and codes, offers and strangers are kept for helpers."
                    : "Herbarium keeps the texts, sorted into people, codes, updates, unknown numbers and offers.", 17, t.sepia, Theme.regular));
            col.addView(Ui.button(this, t, "Open Messages", v -> startActivity(new Intent(this, MessagesActivity.class))), Ui.wide(t.sdp(10)));
        } else if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
            col.addView(Ui.text(this, t, "Herbarium can be the phone's texting app: calm, sorted, with big letters and voice replies.", 17, t.sepia, Theme.regular));
            col.addView(Ui.primary(this, t, "Use Herbarium for texts", v -> { Guard.allow(this, "texts"); startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 4); }), Ui.wide(t.sdp(10)));
        }

        // ---------------- Herbarium itself: version and updates
        col.addView(Ui.heading(this, t, "Herbarium"));
        String ver; try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { ver = "?"; }
        col.addView(Ui.text(this, t, "Version " + ver, 17, t.sepia, Theme.regular));
        TextView upd = Ui.text(this, t, "", 17, t.sage, Theme.regular);
        LinearLayout updBox = Ui.column(this);
        col.addView(upd, Ui.wide(t.sdp(6))); col.addView(updBox, Ui.wide());
        Runnable[] check = new Runnable[1];
        check[0] = () -> new Thread(() -> {
            org.json.JSONObject rel = Updater.available(this, true);
            runOnUiThread(() -> {
                updBox.removeAllViews();
                if (rel == null) { upd.setText("This is the newest Herbarium."); return; }
                upd.setText("Herbarium " + rel.optString("version") + " is ready." + (rel.optString("notes").isEmpty() ? "" : " " + rel.optString("notes")));
                updBox.addView(Ui.primary(this, t, "Update Herbarium", v -> {
                    upd.setText("Fetching the update…");
                    new Thread(() -> { String err = Updater.install(this, rel); runOnUiThread(() -> upd.setText(err.isEmpty() ? "Android will ask you to confirm." : "Couldn't update: " + err)); }).start();
                }), Ui.wide(t.sdp(10)));
            });
        }).start();
        col.addView(Ui.button(this, t, "Check for an update", v -> { upd.setText("Looking…"); check[0].run(); }), Ui.wide(t.sdp(10)));

        setContentView(scroll);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
        final int y = scrollY; scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void setScale(float d) {
        float s = Theme.step(t.scale, d > 0 ? 1 : -1);
        Theme.prefs(this).edit().putFloat("scale", s).apply();
        rebuild();
    }

    private LinearLayout card() {
        LinearLayout l = Ui.column(this);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(16)); g.setColor(t.night ? 0x332c3870 : 0x66f3ecda); g.setStroke(t.dp(1), 0x556e5d45);
        l.setBackground(g); l.setPadding(t.sdp(16), t.sdp(14), t.sdp(16), t.sdp(16));
        return l;
    }

    /** A small card over the page asking for the helper PIN; `ok` runs only if it's right. */
    private void askPin(String why, Runnable ok) { pinCard(why, false, ok); }

    /** Choose a helper PIN (typed twice); `ok` runs once it's set. */
    private void choosePin(Runnable ok) { pinCard("First, choose a helper PIN (4 to 8 digits). Only someone who knows it can change settings or switch Caretaken mode off.", true, ok); }

    private void pinCard(String why, boolean choosing, Runnable ok) {
        LinearLayout box = Ui.column(this);
        box.setPadding(t.dp(20), t.dp(16), t.dp(20), t.dp(8));
        box.addView(Ui.text(this, t, why, 18, t.ink, Theme.regular));
        EditText a = field(choosing ? "New PIN" : "Helper PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(a, Ui.wide(t.sdp(12)));
        EditText b = choosing ? field("The same PIN again", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD) : null;
        if (b != null) box.addView(b, Ui.wide(t.sdp(8)));
        TextView msg = Ui.text(this, t, "", 16, t.ochre, Theme.regular);
        box.addView(msg, Ui.wide(t.sdp(6)));
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this).setView(box).create();
        TextView go = Ui.primary(this, t, choosing ? "Set the PIN" : "Switch it off", v -> {
            String x = a.getText().toString();
            if (choosing) {
                if (!x.matches("[0-9]{4,8}")) { msg.setText("Use 4 to 8 digits."); return; }
                if (!x.equals(b.getText().toString())) { msg.setText("The two don't match."); return; }
                Care.setPin(this, x); d.dismiss(); ok.run();
            } else {
                long wait = Care.pinWait(this);
                if (wait > 0) { msg.setText("Wait " + wait + " seconds, then try again."); return; }
                if (Care.tryPin(this, x)) { d.dismiss(); ok.run(); }
                else { a.setText(""); long w2 = Care.pinWait(this); msg.setText(w2 > 0 ? "That isn't it. Wait " + w2 + " seconds." : "That isn't it."); }
            }
        });
        TextView cancel = Ui.button(this, t, "Cancel", v -> d.dismiss());
        box.addView(Ui.pair(this, t, go, cancel), Ui.wide(t.sdp(12)));
        d.show();
        if (d.getWindow() != null) {
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.dp(22)); g.setColor(t.paperHi);
            d.getWindow().setBackgroundDrawable(g);
        }
        a.requestFocus();
    }

    /** Caretaken mode with a helper PIN: a calm PIN pad instead of the settings. */
    private void gate(LinearLayout col) {
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(Ui.centered(Ui.caps(this, t, "For helpers", 28, t.ink)));
        col.addView(Ui.centered(Ui.text(this, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
        col.addView(Ui.centered(Ui.body(this, t, "These settings are for the people who look after this phone. Type the helper PIN.", 19)), Ui.wide(t.sdp(14)));
        StringBuilder typed = new StringBuilder();
        TextView dots = Ui.centered(Ui.text(this, t, "", 34, t.ink, Theme.regular)); dots.setLetterSpacing(.3f); dots.setMinHeight(t.sdp(48));
        TextView msg = Ui.centered(Ui.text(this, t, "", 17, t.ochre, Theme.regular));
        col.addView(dots, Ui.wide(t.sdp(16))); col.addView(msg, Ui.wide(t.sdp(4)));
        Runnable show = () -> { StringBuilder d = new StringBuilder(); for (int i = 0; i < typed.length(); i++) d.append("●"); dots.setText(d); };
        android.widget.GridLayout pad = new android.widget.GridLayout(this); pad.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "Open"};
        int cell = Math.min((getResources().getDisplayMetrics().widthPixels - t.dp(60)) / 3, Math.round(t.dp(96) * (1 + (t.scale - 1) * .3f)));
        for (String k : keys) {
            TextView b = k.equals("Open") ? Ui.primary(this, t, "Open", null) : Ui.button(this, t, k, null);
            if (!k.equals("Open")) { b.setText(k); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(k.length() == 1 ? 28 : 20)); }
            b.setContentDescription(k.equals("⌫") ? "Delete" : k);
            Steady.tap(b, t, v -> {
                if (k.equals("⌫")) { if (typed.length() > 0) typed.setLength(typed.length() - 1); }
                else if (k.equals("Open")) {
                    long wait = Care.pinWait(this);
                    if (wait > 0) { msg.setText("Wait " + wait + " seconds, then try again."); return; }
                    if (Care.tryPin(this, typed.toString())) { rebuild(); return; }
                    typed.setLength(0);
                    long w2 = Care.pinWait(this);
                    msg.setText(w2 > 0 ? "That isn't it. Wait " + w2 + " seconds, then try again." : "That isn't it. Try again.");
                }
                else if (typed.length() < 8) typed.append(k);
                show.run();
            });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(); lp.width = cell; lp.height = Math.round(cell * .78f);
            lp.setMargins(t.dp(5), t.dp(5), t.dp(5), t.dp(5));
            pad.addView(b, lp);
        }
        LinearLayout.LayoutParams pp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); pp.gravity = Gravity.CENTER_HORIZONTAL; pp.topMargin = t.sdp(14);
        col.addView(pad, pp);
        TextView back = Ui.button(this, t, "Back", v -> finish());
        col.addView(back, Ui.wide(t.sdp(22)));
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(t.sepia); e.setTextColor(t.ink); e.setTypeface(Theme.regular);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp(20)); e.setSingleLine(true); e.setInputType(type);
        // (setSingleLine and setInputType both reset these, so they come last)
        if ((type & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD) e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        e.setTypeface(Theme.regular);
        e.setMinHeight(t.target(56)); e.setPadding(t.sdp(12), 0, t.sdp(12), 0);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(12)); g.setStroke(t.dp(1.2f), t.sepia); g.setColor(t.night ? 0x661e2650 : 0x99f3ecda);
        e.setBackground(g);
        return e;
    }

    private View personRow(String title, String detail, String action, Steady.Action a) {
        LinearLayout r = Ui.row(this); r.setMinimumHeight(t.target(64));
        LinearLayout w = Ui.column(this);
        w.addView(Ui.body(this, t, title == null || title.isEmpty() ? detail : title, 20));
        if (title != null && !title.isEmpty() && detail != null) w.addView(Ui.text(this, t, detail, 16, t.sepia, Theme.regular), Ui.wide(t.sdp(2)));
        r.addView(w, Ui.weight());
        if (action != null) { TextView b = Ui.button(this, t, action, a); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(14)); LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.leftMargin = t.dp(10); r.addView(b, lp); }
        return r;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) { rebuild(); }
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) { rebuild(); }
}
