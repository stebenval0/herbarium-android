package com.herbarium.home;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
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

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Messages, in Herbarium. Normal mode: every conversation, sorted into People, Codes, Updates, Unknown numbers and
 * Offers (folded away). Caretaken mode: only people the person knows, links taken out; everything else is kept
 * for helpers and never shown.
 */
public class MessagesActivity extends Activity {
    private Theme t;
    private LinearLayout col, list;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private boolean offersOpen;
    private final java.util.Set<Integer> expanded = new java.util.HashSet<>();
    private final BroadcastReceiver changed = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { load(); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        t = Theme.get(this);
        Steady.Scroll sc = new Steady.Scroll(this, t);
        android.graphics.Bitmap paper = Paper.ready(this, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels, t);
        sc.setBackground(paper != null ? new android.graphics.drawable.BitmapDrawable(getResources(), paper) : Paper.quick(t));
        col = Ui.column(this); col.setPadding(t.dp(20), t.dp(52), t.dp(20), t.dp(60));
        sc.addView(col, Ui.wide());
        col.addView(Ui.centered(Ui.caps(this, t, getIntent().getBooleanExtra("helper", false) ? "All messages" : "Messages", 28, t.ink)));
        col.addView(Ui.centered(Ui.text(this, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
        TextView write = Ui.primary(this, t, "Write a message", v -> startActivity(new Intent(this, ThreadActivity.class)));
        TextView back = Ui.button(this, t, "Back", v -> finish());
        col.addView(Ui.pair(this, t, write, back), Ui.wide(t.sdp(14)));
        list = Ui.column(this);
        col.addView(list, Ui.wide(t.sdp(8)));
        setContentView(sc);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
        registerReceiver(changed, new IntentFilter(Texts.CHANGED), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override protected void onResume() {
        super.onResume(); load();
        if (Texts.isDefault(this) && MmsWork.waitingCount(this) > 0) bg.execute(() -> MmsWork.retryWaiting(getApplicationContext()));
    }
    @Override protected void onDestroy() { unregisterReceiver(changed); bg.shutdown(); super.onDestroy(); }

    private void load() {
        bg.execute(() -> {
            List<Texts.Conv> all = Texts.isDefault(this) ? Texts.conversations(this) : new ArrayList<>();
            int waiting = MmsWork.waitingCount(this);
            ui.post(() -> render(all, waiting));
        });
    }

    private void render(List<Texts.Conv> all, int waiting) {
        list.removeAllViews();
        boolean care = Care.on(this) && !getIntent().getBooleanExtra("helper", false);   // a helper (via Settings, behind the PIN) sees everything
        if (!Texts.isDefault(this)) {
            list.addView(Ui.body(this, t, "Herbarium can keep your texts: sorted, calm, and with big letters.", 20), Ui.wide(t.sdp(16)));
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS))
                list.addView(Ui.primary(this, t, "Use Herbarium for texts", v -> { Guard.allow(this, "texts"); startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 4); }), Ui.wide(t.sdp(12)));
            return;
        }
        if (waiting > 0) {
            list.addView(Ui.text(this, t, (waiting == 1 ? "A picture message is" : Summary.capital(Summary.count(waiting, "picture message")) + " are") + " still on the way.", 17, t.ochre, Theme.regular), Ui.wide(t.sdp(12)));
            list.addView(Ui.button(this, t, "Try again now", v -> bg.execute(() -> MmsWork.retryWaiting(getApplicationContext()))), Ui.wide(t.sdp(6)));
        }
        List<List<Texts.Conv>> piles = new ArrayList<>();
        for (int k = 0; k < Sorter.NAMES.length; k++) piles.add(new ArrayList<>());
        for (Texts.Conv v : all) piles.get(v.pile).add(v);
        if (care) {
            // only people; nothing else is mentioned at all
            if (piles.get(Sorter.PEOPLE).isEmpty()) list.addView(Ui.centered(Ui.text(this, t, "No messages yet.", 20, t.sepia, Theme.regular)), Ui.wide(t.sdp(24)));
            int shown = 0, cap = expanded.contains(Sorter.PEOPLE) ? Integer.MAX_VALUE : 30;
            for (Texts.Conv v : piles.get(Sorter.PEOPLE)) { if (shown++ >= cap) break; list.addView(row(v, true), Ui.wide(t.sdp(8))); }
            if (piles.get(Sorter.PEOPLE).size() > cap) list.addView(Ui.button(this, t, "Show older conversations", v -> { expanded.add(Sorter.PEOPLE); load(); }), Ui.wide(t.sdp(6)));
            return;
        }
        if (all.isEmpty()) { list.addView(Ui.centered(Ui.text(this, t, "No messages yet.", 20, t.sepia, Theme.regular)), Ui.wide(t.sdp(24))); return; }
        for (int pile : new int[]{Sorter.PEOPLE, Sorter.CODES, Sorter.UPDATES, Sorter.UNKNOWN}) {
            List<Texts.Conv> p = piles.get(pile);
            if (p.isEmpty()) continue;
            list.addView(Ui.heading(this, t, Sorter.NAMES[pile] + (unread(p) > 0 ? " · " + unread(p) + " new" : "")));
            // the newest few of each pile; the rest one tap away (a long history would otherwise be hundreds of cards)
            int cap = expanded.contains(pile) ? Integer.MAX_VALUE : pile == Sorter.PEOPLE ? 40 : pile == Sorter.CODES ? 5 : 15;
            int shown = 0;
            for (Texts.Conv v : p) { if (shown >= cap) break; list.addView(row(v, false), Ui.wide(t.sdp(8))); shown++; }
            if (p.size() > cap) {
                final int which = pile;
                list.addView(Ui.button(this, t, "Show all " + p.size(), v -> { expanded.add(which); load(); }), Ui.wide(t.sdp(6)));
            }
        }
        List<Texts.Conv> offers = piles.get(Sorter.OFFERS);
        if (!offers.isEmpty()) {
            list.addView(Ui.heading(this, t, "Offers"));
            list.addView(Ui.button(this, t, offersOpen ? "Fold the offers away" : (offers.size() == 1 ? "Show the offer" : "Show the " + Summary.number(offers.size()) + " offers"), v -> { offersOpen = !offersOpen; load(); }), Ui.wide(t.sdp(4)));
            if (offersOpen) for (Texts.Conv v : offers) list.addView(row(v, false), Ui.wide(t.sdp(8)));
        }
    }

    private static int unread(List<Texts.Conv> p) { int n = 0; for (Texts.Conv v : p) if (v.unread > 0) n++; return n; }

    /** One conversation: who, the latest words (or the code, large), when, and a gold mark if it's new. */
    private View row(Texts.Conv v, boolean care) {
        LinearLayout r = Ui.column(this);
        r.setPadding(t.dp(16), t.sdp(12), t.dp(16), t.sdp(12));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(16));
        g.setColor(t.night ? 0x332c3870 : 0x66f3ecda); g.setStroke(t.dp(v.unread > 0 ? 2 : 1), v.unread > 0 ? t.gold : 0x556e5d45);
        r.setBackground(g);
        LinearLayout top = Ui.row(this);
        TextView name = Ui.text(this, t, v.name, 21, t.ink, v.unread > 0 ? Theme.semi : Theme.regular);
        name.setMaxLines(2);
        top.addView(name, Ui.weight());
        TextView when = Ui.text(this, t, when(v.date), 15, t.sepia, Theme.regular);
        LinearLayout.LayoutParams wp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); wp.leftMargin = t.dp(10);
        top.addView(when, wp);
        r.addView(top, Ui.wide());
        if (v.pile == Sorter.CODES && !Sorter.code(v.snippet).isEmpty()) {
            TextView code = Ui.text(this, t, spaced(Sorter.code(v.snippet)), 30, t.ink, Theme.semi);
            code.setLetterSpacing(.06f);
            r.addView(code, Ui.wide(t.sdp(4)));
        } else {
            TextView said = Ui.text(this, t, care ? Sorter.withoutLinks(v.snippet) : v.snippet, 18, v.unread > 0 ? t.ink : t.sepia, Theme.regular);
            Ui.ellipsize(said, 2);
            r.addView(said, Ui.wide(t.sdp(4)));
        }
        if (!care && v.pile == Sorter.UNKNOWN && Sorter.hasLink(v.snippet))
            r.addView(Ui.text(this, t, "Careful: a link from a number you don't know.", 15, t.ochre, Theme.semi), Ui.wide(t.sdp(4)));
        if (v.unread > 0) r.addView(Ui.text(this, t, v.unread == 1 ? "New" : Summary.capital(Summary.number(v.unread)) + " new", 15, t.gold, Theme.semi), Ui.wide(t.sdp(4)));
        Steady.tap(r, t, x -> startActivity(new Intent(this, ThreadActivity.class).putExtra("thread", v.thread).putExtra("to", v.addresses.toArray(new String[0])).putExtra("helper", getIntent().getBooleanExtra("helper", false))));
        r.setContentDescription(v.name + (v.unread > 0 ? ", new" : "") + ". " + (care ? Sorter.withoutLinks(v.snippet) : v.snippet) + ". " + when(v.date));
        return r;
    }

    /** "482913" → "482 913": easier to read and type. */
    static String spaced(String code) { return code.length() >= 6 ? code.substring(0, code.length() / 2) + " " + code.substring(code.length() / 2) : code; }

    static String when(long at) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance(); then.setTimeInMillis(at);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR))
            return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) return "Yesterday";
        if (System.currentTimeMillis() - at < 6 * 86_400_000L) return new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(new Date(at));
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(new Date(at));
    }
}
