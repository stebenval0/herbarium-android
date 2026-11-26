package com.herbarium.home;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Setting up a phone with Herbarium.
 *
 *   1. Welcome, and "Who is this phone for?": the person themselves, or someone a helper looks after (Caretaken mode).
 *   2. A short walk through what the phone needs, one page each: what it's for, one big button, and a tick that
 *      appears by itself on returning from Android's screen. Optional things can be skipped; nothing is lost by
 *      leaving: the list waits in Settings ("Finish setting up") until it's done. Set up from a computer with
 *      clausus-phone-pair, most of the ticks are already there.
 *   3. The list, with what's done and what isn't.
 *   4. Caretaken only: a little practice together (a pretend call, a pretend missed call, a spoken reply),
 *      then "Hand the phone to Sam".
 */
public class SetupActivity extends Activity {
    private Theme t;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences p;
    private String screen = "welcome";       // welcome | who | item | list | practice | handover
    private int item, practice;
    private LinearLayout results;
    private Steady.Scroll scroll;
    private View anchor; private int anchorY = -1;     // text size: keep the Bigger/Smaller buttons under the finger as the page grows

    /** Needed on a new phone; a phone Herbarium has already been used on is left alone. */
    static boolean needed(Context c) {
        SharedPreferences p = Theme.prefs(c);
        if (p.getBoolean("setupDone", false)) return false;
        if (p.contains("setupFor")) return true;                 // begun but not finished: carry on where it stopped
        if (p.contains("weatherAt") || p.contains("scale")) { p.edit().putBoolean("setupDone", true).apply(); return false; }
        return true;
    }

    /** How many of the things this phone needs are still to do (for "Finish setting up" in Settings). */
    static int unfinished(Activity a) {
        int n = 0;
        for (Item i : items(a)) if (i.needed && !i.done.get()) n++;
        return n;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p = Theme.prefs(this);
        if (b != null) { screen = b.getString("screen", "welcome"); item = b.getInt("item"); practice = b.getInt("practice"); }
        else if (getIntent().getBooleanExtra("list", false)) screen = "list";      // from Settings: "Finish setting up"
        else if (p.contains("setupFor")) {                                           // came back part-way through (Android restarts
            screen = p.getString("setupScreen", "list");                             // Herbarium when it becomes the home screen):
            item = p.getInt("setupItem", 0); practice = p.getInt("setupPractice", 0); // the same page, not the start
        }
        show();
    }
    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putString("screen", screen); b.putInt("item", item); b.putInt("practice", practice); }
    @Override protected void onResume() { super.onResume(); show(); autoAdvance(); }   // ticks appear on returning from Android's screens
    private boolean hadFocus = true;
    @Override public void onWindowFocusChanged(boolean has) {                          // and from Android's pop-ups (the keyboard chooser),
        super.onWindowFocusChanged(has);                                               // which don't pause the page
        if (has && !hadFocus && screen.equals("item")) show();
        hadFocus = has;
    }
    private static final int HOME_REQUEST = 10;

    @Override protected void onActivityResult(int req, int res, Intent data) {
        show();
        // becoming the home screen makes Android jump to it; bring setup straight back so the helper carries on here
        if (req == HOME_REQUEST) ui.postDelayed(() -> startActivity(new Intent(this, SetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)), 700);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("list", false)) screen = "list";
        show();
    }
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) { show(); autoAdvance(); }

    /** A step that was waiting on Android and is now done: show its tick a moment, then carry on to the next. */
    private String pendingStep;
    private void autoAdvance() {
        if (!screen.equals("item")) { pendingStep = null; return; }
        List<Item> all = items(this);
        if (item >= all.size()) return;
        Item it = all.get(item);
        if (it.id.equals("name") || it.id.equals("size") || it.id.equals("pin") || it.id.equals("town")) return;   // steps people type in
        if (!it.done.get()) { pendingStep = it.id; return; }
        if (it.id.equals(pendingStep)) {
            pendingStep = null;
            final int at = item;
            ui.postDelayed(() -> { if (screen.equals("item") && item == at) next(); }, 900);
        }
    }
    @Override public void onBackPressed() {
        if (screen.equals("item") && item > 0) { item--; show(); }
        else if (screen.equals("item") || screen.equals("list")) { screen = "who"; show(); }
        else if (screen.equals("practice") && practice > 0) { practice--; show(); }
        else if (screen.equals("practice") || screen.equals("handover")) { screen = "list"; show(); }
        else if (screen.equals("who")) { screen = "welcome"; show(); }
    }

    private boolean care() { return "care".equals(p.getString("setupFor", "")); }
    private String them() { String n = p.getString("name", "").trim(); return n.isEmpty() ? (care() ? "them" : "you") : n; }

    // ------------------------------------------------------------------ the page frame
    private LinearLayout page(int plateIndex, String counter) {
        t = Theme.get(this);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        Steady.Scroll sc = new Steady.Scroll(this, t);
        scroll = sc; anchor = null;
        android.graphics.Bitmap paper = Paper.ready(this, W, H, t);
        sc.setBackground(paper != null ? new android.graphics.drawable.BitmapDrawable(getResources(), paper) : Paper.quick(t));
        LinearLayout col = Ui.column(this); col.setPadding(t.dp(26), t.dp(52), t.dp(26), t.dp(60));
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        sc.addView(col, Ui.wide());
        if (plateIndex >= 0) {
            ImageView plate = new ImageView(this);
            int pw = t.sdp(62);
            LinearLayout.LayoutParams pp = Ui.lp(pw, Math.round(pw * 1.6f)); pp.gravity = Gravity.CENTER_HORIZONTAL;
            final Theme th = t;
            new Thread(() -> { android.graphics.Bitmap bmp = Engraving.plate(plateIndex % 14, pw, th, true); runOnUiThread(() -> plate.setImageBitmap(bmp)); }, "setup-plate").start();
            plate.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            col.addView(plate, pp);
        }
        if (counter != null) col.addView(Ui.centered(Ui.text(this, t, counter, 15, t.sepia, Theme.italic)), Ui.wide(t.sdp(10)));
        setContentView(sc);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
        // after a rebuild at a new text size, scroll so the size buttons sit exactly where they were
        sc.post(() -> {
            if (anchor == null || anchorY < 0) return;
            int[] at = new int[2]; anchor.getLocationOnScreen(at);
            sc.scrollBy(0, at[1] - anchorY);
            anchorY = -1;
        });
        return col;
    }

    private TextView title(LinearLayout col, String s) {
        TextView v = Ui.centered(Ui.caps(this, t, s, 28, t.ink));
        // never break a word: shrink until the longest word fits on a line
        v.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
        v.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        v.post(() -> {
            float avail = v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
            if (avail <= 0) return;
            String longest = ""; for (String w : v.getText().toString().split("\\s+")) if (w.length() > longest.length()) longest = w;
            android.text.TextPaint tp = v.getPaint();
            while (tp.measureText(longest) > avail && v.getTextSize() > t.dp(14)) v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, v.getTextSize() * .92f);
        });
        col.addView(v, Ui.wide(t.sdp(6))); return v;
    }
    private void say(LinearLayout col, String s) { col.addView(Ui.centered(Ui.body(this, t, s, 20)), Ui.wide(t.sdp(12))); }
    private void small(LinearLayout col, String s) { col.addView(Ui.text(this, t, s, 16, t.sepia, Theme.regular), Ui.wide(t.sdp(8))); }
    private void tick(LinearLayout col, String s) { col.addView(Ui.centered(Ui.text(this, t, "✓  " + s, 19, t.sage, Theme.semi)), Ui.wide(t.sdp(14))); }

    private void show() {
        p.edit().putString("setupScreen", screen).putInt("setupItem", item).putInt("setupPractice", practice).apply();   // where we are, in case Android restarts us
        switch (screen) {
            case "who": who(); break;
            case "item": showItem(); break;
            case "list": list(); break;
            case "practice": practice(); break;
            case "handover": handover(); break;
            default: welcome();
        }
    }

    // ------------------------------------------------------------------ 1. welcome, and who it's for
    private void welcome() {
        LinearLayout col = page(0, null);
        title(col, "Welcome to Herbarium");
        say(col, "A calm phone: big letters, the people who matter, and nothing else in the way.");
        say(col, "Setting it up takes about fifteen minutes. Keep the phone on Wi-Fi and charging while you do.");
        col.addView(Ui.primary(this, t, "Begin", v -> { screen = "who"; show(); }), Ui.wide(t.sdp(26)));
    }

    private void who() {
        LinearLayout col = page(1, null);
        title(col, "Who is this phone for?");
        TextView me = Ui.button(this, t, "For me", v -> choose("me"));
        TextView them = Ui.button(this, t, "For someone I look after", v -> choose("care"));
        for (TextView b : new TextView[]{me, them}) { b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(21)); b.setMinHeight(Math.round(t.dp(80) * (1 + (t.scale - 1) * .35f))); }
        col.addView(me, Ui.wide(t.sdp(22)));
        small(col, "You'll use it yourself: your apps in calm chapters, sorted texts, a summary of your notifications.");
        col.addView(them, Ui.wide(t.sdp(20)));
        small(col, "You're setting it up for a parent, a friend or anyone who's better with a simpler phone. "
                + "It becomes all about calling: only people they know get through, missed calls wait in big letters, "
                + "texts from strangers and scams stay out of sight, and the settings are kept for helpers.");
    }

    private void choose(String who) {
        p.edit().putString("setupFor", who).apply();
        Care.set(this, who.equals("care"));
        screen = "item"; item = 0; show();
    }

    // ------------------------------------------------------------------ 2. the things the phone needs
    interface Check { boolean get(); }

    static final class Item {
        final String id, title; final boolean needed; final Check done;
        Item(String id, String title, boolean needed, Check done) { this.id = id; this.title = title; this.needed = needed; this.done = done; }
    }

    /** What this phone needs, in the order that works (roles before the guard, which would otherwise close Android's screens). */
    static List<Item> items(Activity a) {
        SharedPreferences p = Theme.prefs(a);
        boolean care = "care".equals(p.getString("setupFor", "")) || Care.on(a);
        RoleManager rm = a.getSystemService(RoleManager.class);
        List<Item> l = new ArrayList<>();
        l.add(new Item("name", care ? "Their name" : "Your name", false, () -> p.contains("name")));
        l.add(new Item("size", "Text size", true, () -> p.getBoolean("sizeChosen", false)));
        l.add(new Item("town", "Town, for the weather", false, () -> p.contains("lat") || p.getBoolean("skip_town", false)));
        if (restricted(a) || p.getBoolean("unlockShown", false)) {     // only on phones that restrict apps from a download link
            p.edit().putBoolean("unlockShown", true).apply();
            l.add(new Item("unlock", "Unlock Herbarium's switches", true, () -> !restricted(a)));
        }
        l.add(new Item("home", "Herbarium as the home screen", true, () -> rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME)));
        l.add(new Item("calls", "Calls", true, () -> rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER) && granted(a, Manifest.permission.READ_CONTACTS)
                && (!care || (granted(a, Manifest.permission.READ_CALL_LOG) && granted(a, Manifest.permission.CALL_PHONE)))));
        if (care) l.add(new Item("people", "Favourite people", false, () -> !CarePages.starred(a).isEmpty() || p.getBoolean("skip_people", false)));
        l.add(new Item("texts", "Texts", !care ? false : true, () -> Texts.isDefault(a) || p.getBoolean("skip_texts", false)));
        l.add(new Item("keyboard", "Keyboard and voice", care, () -> (Keys.isDefault(a) && granted(a, Manifest.permission.RECORD_AUDIO) && (!Keys.ownEar(a) || OwnEar.ready(a)))
                || p.getBoolean("skip_keyboard", false)));
        l.add(new Item("notes", "Notifications", care, () -> Notes.enabled(a) || p.getBoolean("skip_notes", false)));
        l.add(new Item("guard", care ? "Protection" : "Protection (optional)", care, () -> (State.guardOn(a) && State.adminOn(a)) || p.getBoolean("skip_guard", false)));
        if (care) l.add(new Item("ringer", "Calls always ring", true, () -> { NotificationManager nm = a.getSystemService(NotificationManager.class); return nm != null && nm.isNotificationPolicyAccessGranted(); }));
        l.add(new Item("battery", "Always awake for calls", true, () -> { PowerManager pm = a.getSystemService(PowerManager.class); return pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName()); }));
        l.add(new Item("keeper", "A keeper (optional)", false, () -> !State.token(a).isEmpty() || p.getBoolean("skip_keeper", false)));
        if (care) l.add(new Item("pin", "Helper PIN", true, () -> Care.hasPin(a)));
        return l;
    }

    private static boolean granted(Context c, String perm) { return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private void showItem() {
        List<Item> all = items(this);
        if (item >= all.size()) { screen = "list"; show(); return; }
        Item it = all.get(item);
        LinearLayout col = page(item + 2, (item + 1) + " of " + all.size());
        boolean done = it.done.get();
        if (!done) pendingStep = it.id;            // remembered, so it moves on by itself once Android has done its part
        switch (it.id) {
            case "name": itemName(col); break;
            case "unlock": itemUnlock(col); break;
            case "size": itemSize(col); break;
            case "town": itemTown(col); break;
            case "home": itemRole(col, "The home screen", RoleManager.ROLE_HOME, null,
                    "So the phone opens on Herbarium, every time: after pressing Home, after a restart, always.",
                    "Make Herbarium the home screen", "Herbarium is the home screen"); break;
            case "calls": itemCalls(col); break;
            case "people": itemPeople(col); break;
            case "texts": itemTexts(col); break;
            case "keyboard": itemKeyboard(col); break;
            case "notes": itemNotes(col); break;
            case "guard": itemGuard(col); break;
            case "ringer": itemRinger(col); break;
            case "battery": itemBattery(col); break;
            case "keeper": itemKeeper(col); break;
            case "pin": itemPin(col); break;
        }
        // the way on: "Next" once it's done; otherwise the page's own button leads, with a quieter way past
        if (!it.id.equals("pin") && !it.id.equals("name") && !it.id.equals("size")) {
            if (done) col.addView(Ui.primary(this, t, "Next", v -> next()), Ui.wide(t.sdp(22)));
            else if (!(it.id.equals("unlock") && Care.on(this)))      // Caretaken: the switches it unlocks protect the person, so no way past
                col.addView(Ui.button(this, t, it.needed ? "Do this later" : "Skip this", v -> skip(it)), Ui.wide(t.sdp(22)));
        }
        if (item > 0) col.addView(Ui.button(this, t, "Back", v -> { item--; show(); }), Ui.wide(t.sdp(10)));
    }

    /** On to the next step, passing straight over any that are already done (often several are, after the roles). */
    private void next() {
        List<Item> all = items(this);
        item++;
        while (item < all.size() && all.get(item).done.get() && !all.get(item).id.equals("pin")) item++;
        show();
    }
    private void skip(Item it) { if (!it.needed) p.edit().putBoolean("skip_" + it.id, true).apply(); next(); }

    /** Android restricts two of Herbarium's switches when it came from a download link. Lifting that takes one refusal
     *  (Android insists), then one menu: guided here in two big steps, once, before anything needs it. */
    private void itemUnlock(LinearLayout col) {
        title(col, "Unlock");
        if (!restricted()) { tick(col, "Herbarium's switches are unlocked"); return; }
        say(col, "Android is careful with apps from a download link, so it keeps Herbarium's main switch locked until you say so. Three quick steps.");
        int at = p.getInt("unlockAt", 0);           // how far they've got
        TextView one = step(col, "1. Try the switch", at == 0, v -> { p.edit().putInt("unlockAt", Math.max(1, p.getInt("unlockAt", 0))).apply(); Guard.allow(this, "settings"); openGuardSwitch(); });
        small(col, "In Android's list, tap Herbarium. Android says \u201cRestricted setting\u201d: that's expected. Tap OK, then come back here.");
        step(col, "2. Allow it", at == 1, v -> { p.edit().putInt("unlockAt", Math.max(2, p.getInt("unlockAt", 0))).apply(); Notes.openAppInfo(this); });
        small(col, "Tap \u22ee at the top right, then \u201cAllow restricted settings\u201d (confirm with the phone's PIN if it asks). Then come back.");
        step(col, "3. Turn on Herbarium", at >= 2, v -> { Guard.allow(this, "settings"); openGuardSwitch(); });
        small(col, "Tap Herbarium in the list, switch \u201cUse Herbarium\u201d on, tap Allow, then come back. That's it.");
    }

    /** One numbered step: gold when it's the one to do now. */
    private TextView step(LinearLayout col, String label, boolean now, Steady.Action a) {
        TextView b = now ? Ui.primary(this, t, label, a) : Ui.button(this, t, label, a);
        col.addView(b, Ui.wide(t.sdp(16)));
        return b;
    }

    /** Herbarium's own switch in Android's accessibility settings (no hunting through the list), or the list on older phones. */
    private void openGuardSwitch() {
        Intent direct = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(this, Guard.class).flattenToString());
        try { startActivity(direct); return; } catch (Exception ignored) {}
        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void itemName(LinearLayout col) {
        title(col, care() ? "What do they like to be called?" : "What should Herbarium call you?");
        say(col, care() ? "Herbarium greets them by name, and uses it when talking about them (\"Ready for Dad\")." : "Herbarium greets you by name in the morning.");
        EditText name = field(care() ? "Their first name" : "Your first name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(p.getString("name", ""));
        col.addView(name, Ui.wide(t.sdp(16)));
        col.addView(Ui.primary(this, t, "Next", v -> { p.edit().putString("name", name.getText().toString().trim()).apply(); next(); }), Ui.wide(t.sdp(22)));
    }

    /** Text size, chosen against what they'll actually read: a missed call, as it would appear. */
    private void itemSize(LinearLayout col) {
        title(col, "Text size");
        say(col, care() ? "Hand the phone to " + them() + ". Make it bigger until the card below is easy to read without glasses."
                : "Make the words comfortable to read. Everything on the phone grows together.");
        LinearLayout card = Ui.column(this);
        card.setPadding(t.dp(18), t.sdp(14), t.dp(18), t.sdp(14));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(18)); g.setColor(t.night ? 0x552c3870 : 0xccf3ecda); g.setStroke(t.dp(2), t.night ? t.gold : 0xff9a7428);
        card.setBackground(g);
        card.addView(Ui.text(this, t, "Sam called", 26, t.ink, Theme.semi));
        card.addView(Ui.text(this, t, "Today at 2:14 PM", 18, t.sepia, Theme.regular), Ui.wide(t.sdp(4)));
        TextView pretend = Ui.primary(this, t, "Call Sam back", v -> {});
        pretend.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(pretend, Ui.wide(t.sdp(10)));
        col.addView(card, Ui.wide(t.sdp(16)));
        col.addView(Ui.centered(Ui.text(this, t, Math.round(t.scale * 100) + "%", 24, t.ink, Theme.semi)), Ui.wide(t.sdp(14)));
        TextView sm = Steady.repeatable(Ui.button(this, t, "Smaller", v -> scale(v, -1)));
        TextView lg = Steady.repeatable(Ui.button(this, t, "Bigger", v -> scale(v, +1)));
        // always side by side (a stacked pair would put Smaller where Bigger was, mid-tapping)
        LinearLayout pair = Ui.row(this);
        for (TextView b : new TextView[]{sm, lg}) { b.setSingleLine(true); b.setAutoSizeTextTypeUniformWithConfiguration(12, Math.max(13, Math.round(t.btn(19))), 1, TypedValue.COMPLEX_UNIT_SP); }
        LinearLayout.LayoutParams l1 = Ui.weight(); l1.rightMargin = t.dp(6); LinearLayout.LayoutParams l2 = Ui.weight(); l2.leftMargin = t.dp(6);
        pair.addView(sm, l1); pair.addView(lg, l2);
        col.addView(pair, Ui.wide(t.sdp(10)));
        anchor = pair;
        col.addView(Ui.primary(this, t, "This is easy to read", v -> { p.edit().putBoolean("sizeChosen", true).apply(); next(); }), Ui.wide(t.sdp(22)));
    }

    private void scale(View pressed, int dir) {
        int[] at = new int[2]; ((View) pressed.getParent()).getLocationOnScreen(at); anchorY = at[1];
        p.edit().putFloat("scale", Theme.step(Theme.get(this).scale, dir)).apply(); show();
    }

    private void itemTown(LinearLayout col) {
        title(col, "Where " + (care() ? "they live" : "you live"));
        say(col, "For the weather, and the time of sunrise and sunset (the phone turns to its night look after dark). Type the town, then tap it.");
        if (p.contains("lat")) tick(col, Weather.place(this));
        EditText town = field("Town", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        col.addView(town, Ui.wide(t.sdp(12)));
        col.addView(Ui.button(this, t, "Look it up", v -> lookup(town.getText().toString())), Ui.wide(t.sdp(10)));
        results = Ui.column(this); col.addView(results, Ui.wide(t.sdp(8)));
    }

    /** A page whose job is to make Herbarium one of Android's "default" apps. */
    private void itemRole(LinearLayout col, String heading, String role, String pass, String why, String ask, String done) {
        title(col, heading);
        say(col, why);
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm != null && rm.isRoleHeld(role)) { tick(col, done); return; }
        if (rm == null || !rm.isRoleAvailable(role)) { small(col, "This phone doesn't let apps take this over."); return; }
        col.addView(Ui.primary(this, t, ask, v -> {
            if (pass != null) Guard.allow(this, pass);
            startActivityForResult(rm.createRequestRoleIntent(role), RoleManager.ROLE_HOME.equals(role) ? HOME_REQUEST : 1);
        }), Ui.wide(t.sdp(18)));
        small(col, "Android asks to confirm: choose Herbarium, then Set as default.");
    }

    private void itemCalls(LinearLayout col) {
        title(col, "Calls");
        say(col, care() ? "Herbarium becomes the phone's calling app: a big Answer, a big Hang up, the caller's name in large letters. "
                + "Only people in the contacts (and numbers you add) ring; other callers are quietly turned away. Missed calls wait on the home screen until they're returned."
                : "Herbarium becomes the phone's calling app, with its own calm call screen, and only people you know ring.");
        RoleManager rm = getSystemService(RoleManager.class);
        boolean dialer = rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        if (dialer) tick(col, "Herbarium handles calls");
        else if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER))
            col.addView(Ui.primary(this, t, "Use Herbarium for calls", v -> { Guard.allow(this, "calls"); startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 1); }), Ui.wide(t.sdp(18)));
        // everything Herbarium will ask for, asked once here: Android shows its Allow prompts one after another
        List<String> need = new ArrayList<>(java.util.Arrays.asList(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.RECORD_AUDIO));
        if (android.os.Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String s : need) if (!granted(this, s)) missing.add(s);
        if (missing.isEmpty()) tick(col, "Herbarium can see contacts and calls, and hear for voice typing");
        else col.addView((dialer ? Ui.primary(this, t, "Allow what Herbarium needs", null) : Ui.button(this, t, "Allow what Herbarium needs", null)), Ui.wide(t.sdp(12)));
        if (!missing.isEmpty()) small(col, "Android asks a few short questions in a row (contacts, calls, microphone\u2026). Tap Allow on each.");
        View last = col.getChildAt(col.getChildCount() - 1);
        if (!missing.isEmpty()) Steady.tap(last, t, v -> { Guard.allow(this, "calls"); requestPermissions(missing.toArray(new String[0]), 2); });
        small(col, "Numbers that aren't in the contacts but should ring (the doctor's office, the pharmacy) can be added in Settings → Calls.");
    }

    /** Caretaken: up to eight people as big call buttons on Home (starred in the phone's contacts). */
    private void itemPeople(LinearLayout col) {
        title(col, "Favourite people");
        say(col, "Choose the people " + them() + " calls most. They'll be big Call buttons on the home screen, one tap each.");
        if (!granted(this, Manifest.permission.READ_CONTACTS)) { small(col, "First let Herbarium see the contacts (the Calls step)."); return; }
        List<CarePages.Person> starred = CarePages.starred(this);
        java.util.Set<String> chosen = new java.util.HashSet<>();
        for (CarePages.Person x : starred) chosen.add(x.name);
        if (!granted(this, Manifest.permission.WRITE_CONTACTS))
            col.addView(Ui.primary(this, t, "Let Herbarium mark favourites", v -> requestPermissions(new String[]{Manifest.permission.WRITE_CONTACTS}, 3)), Ui.wide(t.sdp(14)));
        List<CarePages.Person> all = CarePages.people(this, false);
        if (all.isEmpty()) {     // a new phone often has no contacts yet: add them right here
            small(col, "There are no contacts yet. Add the important people one by one; each comes back here.");
            col.addView(Ui.primary(this, t, "Add a person", v -> addContact()), Ui.wide(t.sdp(14)));
        }
        for (CarePages.Person x : all) {
            boolean on = chosen.contains(x.name);
            col.addView(Ui.toggle(this, t, x.name, null, on, v -> { star(x.name, !on); show(); }), Ui.wide(t.sdp(4)));
        }
        if (!all.isEmpty()) col.addView(Ui.button(this, t, "Add someone to the contacts", v -> addContact()), Ui.wide(t.sdp(14)));
    }

    /** Android's "new contact" page; a phone without a contacts app gets Herbarium's own list of numbers instead. */
    private void addContact() {
        try { startActivity(new Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE).putExtra("finishActivityOnSaveCompleted", true)); }
        catch (Exception e) { android.widget.Toast.makeText(this, "This phone has no contacts app. Add numbers later in Settings → Calls → Add a number.", android.widget.Toast.LENGTH_LONG).show(); }
    }

    private void star(String name, boolean on) {
        if (!granted(this, Manifest.permission.WRITE_CONTACTS)) { requestPermissions(new String[]{Manifest.permission.WRITE_CONTACTS}, 3); return; }
        if (on && CarePages.starred(this).size() >= 8) return;
        ContentValues v = new ContentValues(); v.put(ContactsContract.Contacts.STARRED, on ? 1 : 0);
        try { getContentResolver().update(ContactsContract.Contacts.CONTENT_URI, v, ContactsContract.Contacts.DISPLAY_NAME + " = ?", new String[]{name}); } catch (Exception ignored) {}
    }

    private void itemTexts(LinearLayout col) {
        title(col, "Texts");
        say(col, care() ? "Herbarium becomes the phone's texting app. " + them() + " sees only texts from people they know, in big letters, with links taken out (the commonest way in for scams). "
                + "Codes, offers and strangers are kept for helpers and never shown. Replies can be spoken."
                : "Herbarium becomes the phone's texting app: people first, codes shown large, offers folded away, and replies you can speak.");
        RoleManager rm = getSystemService(RoleManager.class);
        if (Texts.isDefault(this)) { tick(col, "Herbarium keeps the texts"); return; }
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS))
            col.addView(Ui.primary(this, t, "Use Herbarium for texts", v -> { Guard.allow(this, "texts"); startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 1); }), Ui.wide(t.sdp(18)));
        small(col, "Old texts stay where they are; Herbarium reads them too. If the phone had a chat feature (\"RCS\"), people's messages now come as ordinary texts.");
    }

    private void itemKeyboard(LinearLayout col) {
        title(col, "Keyboard and voice");
        say(col, care() ? "Herbarium's keyboard has big keys, and it starts by listening: " + them() + " can say a message instead of typing it."
                : "Herbarium's keyboard: big keys that grow with the text, word suggestions, and a microphone key for speaking.");
        boolean kbd = Keys.isDefault(this), mic = granted(this, Manifest.permission.RECORD_AUDIO);
        TextView msg = Ui.centered(Ui.text(this, t, "", 17, t.ochre, Theme.regular));
        if (kbd) tick(col, "Herbarium's keyboard is in use");
        else col.addView(Ui.primary(this, t, "Use Herbarium's keyboard", v -> { Guard.allow(this, "keyboard"); String next = Keys.choose(this); msg.setText(next); }), Ui.wide(t.sdp(18)));
        col.addView(msg, Ui.wide(t.sdp(6)));
        if (mic) tick(col, "Herbarium can hear for voice typing");
        else col.addView(Ui.button(this, t, "Let Herbarium hear for voice typing", v -> { Guard.allow(this, "mic"); requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4); }), Ui.wide(t.sdp(12)));
        // phones without Google's voice service: fetch Herbarium's own speech model now, on Wi-Fi, not on first use
        if (mic && Keys.ownEar(this)) {
            if (OwnEar.ready(this)) tick(col, "Voice typing works without the internet");
            else {
                TextView prog = Ui.centered(Ui.text(this, t, "", 17, t.sepia, Theme.italic));
                col.addView(Ui.button(this, t, "Get voice typing ready (about 40 MB)", v -> fetchVoice(prog)), Ui.wide(t.sdp(12)));
                col.addView(prog, Ui.wide(t.sdp(6)));
            }
        }
    }

    /** Start Herbarium's own ear once, so its model downloads now; stop the moment it's ready to listen. */
    private void fetchVoice(TextView prog) {
        prog.setText("Getting ready…");
        OwnEar.start(this, new OwnEar.Ear() {
            @Override public void preparing(int pct) { prog.setText(pct >= 0 ? "Getting voice typing ready… " + pct + "%" : "Getting voice typing ready…"); }
            @Override public void listening() { OwnEar.stop(); prog.setText(""); show(); autoAdvance(); }
            @Override public void partial(String text) {}
            @Override public void heard(String text) {}
            @Override public void stopped(String problem) { prog.setText(problem == null ? "" : problem); if (OwnEar.ready(SetupActivity.this)) { show(); autoAdvance(); } }
        });
    }

    /** Android blocks two of Herbarium's switches until allowed by hand, for apps not from the Play Store. Explain first. */
    /** Is Herbarium still treated as "downloaded from the web" (its restricted switches greyed out)? */
    /** Would Android lock Herbarium's switches here? Apps from a download link (Android 13+) until a switch is on. Herbarium
     *  can't read the lock itself, so a switch being on is the proof it's lifted. */
    static boolean restricted(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 33) return false;
        if (State.guardOn(c) || Notes.enabled(c)) return false;
        try {
            int ps = c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getPackageSource();
            return ps == android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE || ps == android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                    || (ps == android.content.pm.PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED || ps == android.content.pm.PackageInstaller.PACKAGE_SOURCE_OTHER) && c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) { return false; }
    }

    private boolean restricted() { return restricted(this); }

    /** Android 13+ restricts some switches for apps from a download link. Two ways through, the quick one first. */
    private void restrictedHelp(LinearLayout col) {
        if (!restricted()) return;          // already allowed (or an older Android): nothing to explain
        LinearLayout box = Ui.column(this);
        box.setPadding(t.dp(16), t.sdp(12), t.dp(16), t.sdp(12));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(14)); g.setColor(t.night ? 0x332c3870 : 0x66f3ecda); g.setStroke(t.dp(1), 0x556e5d45);
        box.setBackground(g);
        box.addView(Ui.text(this, t, "If Android says \u201cRestricted setting\u201d", 18, t.ink, Theme.semi));
        box.addView(Ui.text(this, t, "1. Try the switch once (Android will refuse; that's expected).\n2. Tap the button below to open Herbarium's App info.\n3. Tap \u22ee in the top corner, then \u201cAllow restricted settings\u201d.\n4. Come back and try the switch again.", 17, t.sepia, Theme.regular), Ui.wide(t.sdp(6)));
        box.addView(Ui.button(this, t, "Open Herbarium's App info", v -> Notes.openAppInfo(this)), Ui.wide(t.sdp(10)));
        col.addView(box, Ui.wide(t.sdp(16)));
    }

    private void itemNotes(LinearLayout col) {
        title(col, "Notifications");
        say(col, "Herbarium gathers the phone's notifications and sums them up in one calm line (\"A message from Sam and two reminders\"), with a button to hear it read aloud.");
        if (Notes.enabled(this)) { tick(col, "Herbarium holds the notifications"); return; }
        col.addView(Ui.primary(this, t, "Let Herbarium hold notifications", v -> Notes.askAccess(this)), Ui.wide(t.sdp(18)));
        restrictedHelp(col);      // Android 13+ greys this switch out for apps from a download link
        small(col, "On Android's page, switch Herbarium on.");
        restrictedHelp(col);
    }

    private void itemGuard(LinearLayout col) {
        title(col, "Protection");
        say(col, care() ? "Two switches that let Herbarium look after the phone: its own lock screen and pull-down panel, keeping the settings for helpers, "
                + "and making sure Herbarium can't be removed by accident."
                : "Two switches that let Herbarium keep blocked apps closed and stay on the phone. Only needed if you use blocking (Clausus).");
        boolean g = State.guardOn(this), ad = State.adminOn(this);
        if (g) tick(col, "The guard is on");
        else {
            col.addView(Ui.primary(this, t, "Turn on Herbarium's guard", v -> { Guard.allow(this, "settings"); openGuardSwitch(); }), Ui.wide(t.sdp(16)));
            small(col, "Switch \u201cUse Herbarium\u201d on, allow it, then come back.");
            restrictedHelp(col);
        }
        if (ad) tick(col, "Herbarium can't be removed by accident");
        else col.addView(Ui.button(this, t, "Keep Herbarium from being removed", v -> startActivity(new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, Admin.class))
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "So Herbarium can't be removed by accident."))), Ui.wide(t.sdp(12)));
        boolean vpn = android.net.VpnService.prepare(this) == null;
        if (!care() || !State.token(this).isEmpty()) {
            if (vpn) tick(col, "Herbarium can filter websites");
            else col.addView(Ui.button(this, t, "Let Herbarium filter websites", v -> { Intent i = android.net.VpnService.prepare(this); if (i != null) startActivityForResult(i, 5); }), Ui.wide(t.sdp(12)));
        }
    }

    private void itemRinger(LinearLayout col) {
        title(col, "Calls always ring");
        say(col, "So a call from someone " + them() + " knows is never missed because the phone was left on silent: Herbarium keeps the ringer on and loud, and lifts Do Not Disturb.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.isNotificationPolicyAccessGranted()) { tick(col, "Herbarium keeps the ringer on"); return; }
        col.addView(Ui.primary(this, t, "Let Herbarium keep the ringer on", v -> { Guard.allow(this, "calls"); startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); }), Ui.wide(t.sdp(18)));
        small(col, "On Android's page, tap Herbarium, then allow it.");
    }

    private void itemBattery(LinearLayout col) {
        title(col, "Always awake for calls");
        say(col, "Android puts apps to sleep to save battery, which can delay calls, texts and the missed-call reminders. This lets Herbarium stay awake. It uses very little battery.");
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) { tick(col, "Herbarium stays awake"); return; }
        col.addView(Ui.primary(this, t, "Let Herbarium stay awake", v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", getPackageName(), null))); }
            catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        }), Ui.wide(t.sdp(18)));
        small(col, "Android asks to confirm: choose Allow.");
    }

    private void itemKeeper(LinearLayout col) {
        title(col, "A keeper");
        say(col, "Optional. Someone can look after what's blocked on this phone from their computer (with Clausus), wherever the phone is. "
                + "They send an invitation link; open it on this phone, or copy it and tap below.");
        if (!State.token(this).isEmpty()) { tick(col, "Connected to a keeper"); return; }
        TextView msg = Ui.centered(Ui.text(this, t, "", 17, t.ochre, Theme.regular));
        col.addView(Ui.button(this, t, "Paste the invitation", v -> { if (!InviteActivity.fromClipboard(this)) msg.setText("There's no invitation copied yet."); }), Ui.wide(t.sdp(16)));
        col.addView(msg, Ui.wide(t.sdp(6)));
    }

    private void itemPin(LinearLayout col) {
        title(col, "A helper PIN");
        say(col, "The settings are for helpers. With a PIN, Herbarium's settings and Android's own ask for it first, so nothing gets changed by accident. "
                + "Choose 4 to 8 digits that " + them() + " won't guess, and keep a note of it.");
        if (Care.hasPin(this)) {
            tick(col, "The helper PIN is set");
            col.addView(Ui.primary(this, t, "Next", v -> next()), Ui.wide(t.sdp(22)));
            return;
        }
        EditText a = field("PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText b = field("The same PIN again", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        col.addView(a, Ui.wide(t.sdp(16))); col.addView(b, Ui.wide(t.sdp(8)));
        col.addView(Ui.primary(this, t, "Set the PIN", v -> {
            String x = a.getText().toString(), y = b.getText().toString();
            if (!x.matches("[0-9]{4,8}")) { a.setError("4 to 8 digits"); return; }
            if (!x.equals(y)) { b.setError("These don't match"); return; }
            Care.setPin(this, x); next();
        }), Ui.wide(t.sdp(16)));
        col.addView(Ui.button(this, t, "Do this later", v -> next()), Ui.wide(t.sdp(10)));
    }

    // ------------------------------------------------------------------ 3. the list
    private void list() {
        List<Item> all = items(this);
        int left = 0; for (Item i : all) if (i.needed && !i.done.get()) left++;
        LinearLayout col = page(-1, null);
        title(col, left == 0 ? "Everything is set" : "Almost there");
        say(col, left == 0 ? "The phone has everything it needs." : (left == 1 ? "One thing" : Summary.capital(Summary.number(left)) + " things") + " still to do. Tap one to set it up, or come back to it later from Settings.");
        for (int k = 0; k < all.size(); k++) {
            Item it = all.get(k); boolean done = it.done.get();
            LinearLayout r = Ui.row(this);
            r.setMinimumHeight(t.target(60)); r.setPadding(t.dp(8), t.sdp(8), t.dp(8), t.sdp(8));
            TextView mark = Ui.text(this, t, done ? "✓" : it.needed ? "○" : "·", 22, done ? t.sage : it.needed ? t.ochre : t.sepia, Theme.semi);
            mark.setMinWidth(t.sdp(34));
            r.addView(mark);
            r.addView(Ui.text(this, t, it.title, 19, done ? t.sepia : t.ink, Theme.regular), Ui.weight());
            final int at = k;
            Steady.tap(r, t, v -> { screen = "item"; item = at; show(); });
            r.setContentDescription(it.title + (done ? ", done" : it.needed ? ", to do" : ", optional"));
            col.addView(r, Ui.wide(t.sdp(2)));
        }
        if (care()) col.addView(Ui.primary(this, t, left == 0 ? "Practise together" : "Practise together anyway", v -> { screen = "practice"; practice = 0; show(); }), Ui.wide(t.sdp(22)));
        else col.addView(Ui.primary(this, t, "Start using Herbarium", v -> finishSetup()), Ui.wide(t.sdp(22)));
        // never a trap: anything left waits in Settings ("Finish setting up")
        if (left > 0) col.addView(Ui.button(this, t, "Finish the rest later", v -> finishSetup()), Ui.wide(t.sdp(10)));
    }

    // ------------------------------------------------------------------ 4. practice and hand-over (Caretaken)
    /** Three pretend moments, nothing real happens: a call coming in, a missed call, a spoken reply. */
    private void practice() {
        LinearLayout col = page(-1, "Practice " + (practice + 1) + " of 3");
        switch (practice) {
            case 0: {
                title(col, "When someone calls");
                say(col, "The phone rings and shows who it is. Tap the big green Answer. Try it now; nobody is really calling.");
                LinearLayout call = Ui.column(this); call.setGravity(Gravity.CENTER_HORIZONTAL);
                call.addView(Ui.centered(Ui.text(this, t, "Calling you", 18, t.sage, Theme.regular)));
                call.addView(Ui.centered(Ui.text(this, t, "Sam", 40, t.ink, Theme.semi)), Ui.wide(t.sdp(4)));
                TextView done = Ui.centered(Ui.text(this, t, "", 20, t.sage, Theme.semi));
                TextView answer = Ui.primary(this, t, "Answer", v -> { done.setText("That's it. The call starts, on the speaker."); ui.postDelayed(() -> { practice++; show(); }, 2200); });
                GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(20)); g.setColor(t.night ? 0xff6fae86 : 0xff3f7a55);
                answer.setBackground(g); answer.setTextColor(0xfff6f1e2); answer.setMinHeight(Math.round(t.dp(96) * (1 + (t.scale - 1) * .35f)));
                call.addView(answer, Ui.wide(t.sdp(18)));
                call.addView(done, Ui.wide(t.sdp(12)));
                col.addView(call, Ui.wide(t.sdp(18)));
                break;
            }
            case 1: {
                title(col, "If a call is missed");
                say(col, "It waits on the home screen in big letters until it's returned. Tap the gold button to call back.");
                LinearLayout card = Ui.column(this);
                card.setPadding(t.dp(18), t.sdp(14), t.dp(18), t.sdp(14));
                GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(18)); g.setColor(t.night ? 0x552c3870 : 0xccf3ecda); g.setStroke(t.dp(2), t.night ? t.gold : 0xff9a7428);
                card.setBackground(g);
                card.addView(Ui.text(this, t, "Sam called", 26, t.ink, Theme.semi));
                card.addView(Ui.text(this, t, "Today at 2:14 PM", 18, t.sepia, Theme.regular), Ui.wide(t.sdp(4)));
                TextView done = Ui.centered(Ui.text(this, t, "", 20, t.sage, Theme.semi));
                card.addView(Ui.primary(this, t, "Call Sam back", v -> { done.setText("That's it. The phone calls Sam straight away."); ui.postDelayed(() -> { practice++; show(); }, 2200); }), Ui.wide(t.sdp(12)));
                col.addView(card, Ui.wide(t.sdp(18)));
                col.addView(done, Ui.wide(t.sdp(12)));
                break;
            }
            default: {
                title(col, "Writing back");
                say(col, "Tap the box: the keyboard opens listening. Say a few words, and they appear. Then Send.");
                EditText box = field("Say something…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                col.addView(box, Ui.wide(t.sdp(16)));
                TextView done = Ui.centered(Ui.text(this, t, "", 20, t.sage, Theme.semi));
                col.addView(Ui.primary(this, t, "Send", v -> {
                    if (box.getText().toString().trim().isEmpty()) { done.setText("Tap the box and speak first."); return; }
                    done.setText("Lovely. That's how a text is sent.");
                    ui.postDelayed(() -> { screen = "handover"; show(); }, 2200);
                }), Ui.wide(t.sdp(14)));
                col.addView(done, Ui.wide(t.sdp(12)));
            }
        }
        col.addView(Ui.button(this, t, "Skip the practice", v -> { screen = "handover"; show(); }), Ui.wide(t.sdp(26)));
    }

    private void handover() {
        LinearLayout col = page(6, null);
        String n = p.getString("name", "").trim();
        title(col, n.isEmpty() ? "It's ready" : "Ready for " + n);
        say(col, "Hand the phone over. The home screen shows calls to return first, then the favourite people, then Phone, Messages and Contacts.");
        if (Care.hasPin(this)) small(col, "To change anything later: on the home screen, touch and hold \"For helpers\" for three seconds, then type the helper PIN.");
        col.addView(Ui.primary(this, t, n.isEmpty() ? "Open the home screen" : "Hello, " + n, v -> finishSetup()), Ui.wide(t.sdp(26)));
    }

    private void finishSetup() {
        p.edit().putBoolean("setupDone", true).apply();
        Care.apply(this);
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    // ------------------------------------------------------------------ helpers
    /** Find towns by name (Open-Meteo's free place search) and offer them as big buttons. */
    private void lookup(String q) {
        if (q.trim().length() < 2) return;
        results.removeAllViews();
        results.addView(Ui.text(this, t, "Looking…", 18, t.sepia, Theme.italic));
        new Thread(() -> {
            JSONArray found = null;
            try {
                HttpURLConnection h = (HttpURLConnection) new URL("https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&name=" + URLEncoder.encode(q.trim(), "UTF-8")).openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(10000); h.setRequestProperty("User-Agent", "Herbarium/2");
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                try (InputStream in = h.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
                found = new JSONObject(b.toString("UTF-8")).optJSONArray("results");
            } catch (Exception ignored) {}
            final JSONArray f = found;
            ui.post(() -> {
                results.removeAllViews();
                if (f == null || f.length() == 0) { results.addView(Ui.text(this, t, "No town by that name. Check the spelling, or try a bigger town nearby.", 18, t.sepia, Theme.regular)); return; }
                for (int i = 0; i < f.length(); i++) {
                    JSONObject o = f.optJSONObject(i); if (o == null) continue;
                    String label = o.optString("name") + (o.optString("admin1").isEmpty() ? "" : ", " + o.optString("admin1")) + (o.optString("country").isEmpty() ? "" : " · " + o.optString("country"));
                    results.addView(Ui.button(this, t, label, v -> {
                        p.edit().putString("place", o.optString("name"))
                                .putLong("lat", Double.doubleToLongBits(o.optDouble("latitude"))).putLong("lon", Double.doubleToLongBits(o.optDouble("longitude")))
                                .remove("weatherAt").apply();
                        new Thread(() -> Weather.refresh(this)).start();
                        next();
                    }), Ui.wide(t.sdp(8)));
                }
            });
        }).start();
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(t.sepia); e.setTextColor(t.ink); e.setTypeface(Theme.regular);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp(20)); e.setInputType(type); e.setSingleLine(true);
        e.setMinHeight(t.target(56)); e.setPadding(t.sdp(12), 0, t.sdp(12), 0);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(12)); g.setStroke(t.dp(1.2f), t.sepia); g.setColor(t.night ? 0x661e2650 : 0x99f3ecda);
        e.setBackground(g);
        return e;
    }
}
