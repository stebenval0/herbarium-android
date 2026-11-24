package com.herbarium.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ExecutorService;

/** Nuntius: the summary Claude writes (the day ahead, tomorrow, messages, letters), ticks, and notes for Claude. */
final class NuntiusPage {
    private final Activity a; private final Theme t; private final LinearLayout host;
    private final ExecutorService bg; private final android.os.Handler ui;
    private boolean form;
    private String status = "";

    NuntiusPage(Activity a, Theme t, LinearLayout host, ExecutorService bg, android.os.Handler ui) { this.a = a; this.t = t; this.host = host; this.bg = bg; this.ui = ui; }

    void sync() { if (Mailbox.configured(a)) bg.execute(() -> { Mailbox.sync(a); ui.post(this::render); }); }

    void render() {
        // don't wipe something being typed
        View f = a.getCurrentFocus();
        if (f instanceof EditText && isInside(f) && ((EditText) f).getText().length() > 0) return;
        host.removeAllViews();
        host.addView(Ui.centered(Ui.caps(a, t, "Nuntius", 32, t.ink)));
        host.addView(Ui.centered(Ui.text(a, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
        JSONObject s = Mailbox.summary(a);

        long asked = Mailbox.asked(a);
        if (asked > 0 && (s == null || Mailbox.parse(s.optString("generated")) < asked))
            host.addView(Ui.centered(Ui.text(a, t, "Asked Claude for a fresh summary at " + hhmm(asked) + ". It arrives within the hour.", 16, t.sage, Theme.regular)), Ui.wide(t.sdp(10)));
        if (!status.isEmpty()) host.addView(Ui.centered(Ui.text(a, t, status, 16, t.ochre, Theme.regular)), Ui.wide(t.sdp(8)));

        if (s == null) {
            host.addView(Ui.centered(Ui.text(a, t, Mailbox.configured(a) ? "No summary yet. Ask Claude for one below." : "Connect the mailbox (below) and Claude's summaries of your day appear here.", 20, t.sepia, Theme.regular)), Ui.wide(t.sdp(16)));
        } else {
            String head = s.optString("headline");
            if (!head.isEmpty()) host.addView(Ui.centered(Ui.body(a, t, head, 21)), Ui.wide(t.sdp(16)));
            JSONArray today = s.optJSONArray("today");
            host.addView(Ui.heading(a, t, "The Day Ahead"));
            if (today == null || today.length() == 0) host.addView(Ui.text(a, t, "Nothing on the calendar.", 18, t.sepia, Theme.regular));
            else events(today);
            JSONArray tomorrow = s.optJSONArray("tomorrow");
            if (tomorrow != null && tomorrow.length() > 0) { host.addView(Ui.heading(a, t, "Tomorrow")); events(tomorrow); }
            JSONArray msgs = s.optJSONArray("messages");
            if (msgs != null && msgs.length() > 0) {
                host.addView(Ui.heading(a, t, "Messages"));
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject m = msgs.optJSONObject(i); if (m == null) continue;
                    JSONObject idObj = new JSONObject(); try { idObj.put("from", m.optString("from")).put("subject", m.optString("gist")); if (m.has("id")) idObj.put("id", m.optString("id")); } catch (Exception ignored) {}
                    String who = (m.optBoolean("unread") ? "● " : "") + m.optString("from") + (m.optString("app").isEmpty() ? "" : " · " + m.optString("app")) + (m.optString("when").isEmpty() ? "" : " · " + m.optString("when"));
                    item(Mailbox.itemId(idObj), m.optString("from") + ": " + m.optString("gist"), who, null, m.optString("gist"), m.optBoolean("reply"), null);
                }
            }
            JSONArray letters = s.optJSONArray("letters");
            if (letters != null && letters.length() > 0) {
                host.addView(Ui.heading(a, t, "Letters"));
                for (int i = 0; i < letters.length(); i++) {
                    JSONObject l = letters.optJSONObject(i); if (l == null) continue;
                    String who = (l.optBoolean("unread") ? "● " : "") + l.optString("from") + (l.optString("when").isEmpty() ? "" : " · " + l.optString("when"));
                    item(Mailbox.itemId(l), l.optString("subject"), who, l.optString("subject"), l.optString("gist"), l.optBoolean("reply"), "com.google.android.gm");
                }
            }
            long g = Mailbox.parse(s.optString("generated"));
            if (g > 0) {
                Calendar gc = Calendar.getInstance(); gc.setTimeInMillis(g); Calendar now = Calendar.getInstance();
                boolean sameDay = gc.get(Calendar.YEAR) == now.get(Calendar.YEAR) && gc.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
                String day = sameDay ? "today" : new java.text.SimpleDateFormat("EEEE", java.util.Locale.US).format(new Date(g));
                host.addView(Ui.centered(Ui.text(a, t, "written " + day + " at " + hhmm(g), 15, t.sepia, Theme.italic)), Ui.wide(t.sdp(20)));
            }
        }

        // ask for a fresh one (a button, so no pulling or swiping is needed)
        if (Mailbox.configured(a)) host.addView(Ui.button(a, t, "Ask for a fresh summary", v -> {
            status = "Sending…"; render();
            bg.execute(() -> { String err = Mailbox.askForFresh(a); ui.post(() -> { status = err.isEmpty() ? "" : "Couldn't send: " + err; render(); }); });
        }), Ui.wide(t.sdp(20)));

        // Tell Claude
        host.addView(Ui.heading(a, t, "Tell Claude"));
        EditText note = field("What have you actually done? e.g. replied to Sam, moved the dentist to Friday…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setSingleLine(false); note.setMinLines(2); note.setGravity(Gravity.TOP);
        host.addView(note, Ui.wide());
        host.addView(Ui.button(a, t, "Leave a note", v -> { Mailbox.tell(a, note.getText().toString()); note.setText(""); hideKeys(note); render(); sync(); }), Ui.wide(t.sdp(10)));
        JSONArray ns = Mailbox.notes(a).optJSONArray("notes");
        for (int i = 0; ns != null && i < ns.length(); i++) {
            JSONObject x = ns.optJSONObject(i); if (x == null) continue;
            final int idx = i;
            LinearLayout r = Ui.row(a); r.setMinimumHeight(t.target(56));
            r.addView(Ui.text(a, t, hhmm(Mailbox.parse(x.optString("at"))), 15, t.sepia, Theme.regular), Ui.lp(t.sdp(76), ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(Ui.ellipsize(Ui.body(a, t, x.optString("text"), 18), 3), Ui.weight());
            TextView del = Ui.text(a, t, "×", 26, t.sepia, Theme.regular); del.setGravity(Gravity.CENTER); del.setMinWidth(t.target(48)); del.setMinHeight(t.target(48));
            del.setContentDescription("Remove this note");
            Steady.tap(del, t, v -> { Mailbox.unnote(a, idx); render(); });
            r.addView(del);
            host.addView(r, Ui.wide(t.sdp(4)));
        }

        // the mailbox
        host.addView(Ui.heading(a, t, "Mailbox"));
        if (form || !Mailbox.configured(a)) {
            if (!form) {
                host.addView(Ui.text(a, t, "Nuntius reads its summaries from a private GitHub repository that Claude writes to. You need its name and a token that can only reach that repository.", 16, t.sepia, Theme.regular));
                host.addView(Ui.button(a, t, "Set up the mailbox", v -> { form = true; render(); }), Ui.wide(t.sdp(10)));
            } else {
                EditText repo = field("your-name/herbarium-mailbox", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                repo.setText(Mailbox.repo(a));
                EditText tok = field(Mailbox.configured(a) ? "token saved (leave empty to keep it)" : "the repository's token", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                host.addView(repo, Ui.wide(t.sdp(6))); host.addView(tok, Ui.wide(t.sdp(8)));
                host.addView(Ui.primary(a, t, "Save", v -> {
                    String r = repo.getText().toString().trim().replaceFirst("^https?://github.com/", "");
                    if (!r.matches("[\\w.-]+/[\\w.-]+")) { repo.setError("It looks like your-name/herbarium-mailbox"); return; }
                    Mailbox.configure(a, r, tok.getText().toString()); form = false; hideKeys(tok); render(); sync();
                }), Ui.wide(t.sdp(10)));
                if (Mailbox.configured(a)) host.addView(Ui.button(a, t, "Forget the mailbox", v -> { Mailbox.forget(a); form = false; render(); }), Ui.wide(t.sdp(8)));
                host.addView(Ui.button(a, t, "Cancel", v -> { form = false; hideKeys(repo); render(); }), Ui.wide(t.sdp(8)));
            }
        } else {
            long lf = Mailbox.lastFetch(a);
            String err = Mailbox.error(a);
            host.addView(Ui.text(a, t, Mailbox.repo(a) + " · checked " + (lf > 0 ? hhmm(lf) : "not yet") + (err.isEmpty() ? "" : " · " + err), 16, err.isEmpty() ? t.sepia : t.ochre, Theme.regular));
            host.addView(Ui.button(a, t, "Change", v -> { form = true; render(); }), Ui.wide(t.sdp(10)));
        }
    }

    private void events(JSONArray list) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i); if (e == null) continue;
            item(Mailbox.itemId(e), e.optString("title"), e.optString("time"), e.optString("title"), e.optString("where"), false, "com.google.android.calendar");
        }
    }

    /** One line of the summary with its tick: tapping the words opens the app, tapping the circle ticks it. */
    private void item(String id, String what, String small, String title, String detail, boolean reply, String opens) {
        boolean done = Mailbox.done(a, id);
        LinearLayout r = Ui.row(a); r.setGravity(Gravity.TOP); r.setPadding(0, t.sdp(8), 0, t.sdp(8));
        LinearLayout body = Ui.column(a);
        if (small != null && !small.isEmpty()) body.addView(Ui.caps(a, t, small, 15, t.sepia));
        if (title != null && !title.isEmpty()) body.addView(Ui.body(a, t, title, 20), Ui.wide(t.sdp(2)));
        if (detail != null && !detail.isEmpty()) body.addView(Ui.text(a, t, detail, 17, t.sepia, Theme.regular), Ui.wide(t.sdp(2)));
        if (reply) { TextView tag = Ui.caps(a, t, "wants a reply", 13, t.gold); tag.setPadding(t.sdp(8), t.sdp(2), t.sdp(8), t.sdp(2));
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(8)); g.setStroke(t.dp(1), t.gold); tag.setBackground(g);
            LinearLayout.LayoutParams tp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); tp.topMargin = t.sdp(4); body.addView(tag, tp); }
        if (done) body.setAlpha(.45f);
        r.addView(body, Ui.weight());
        if (opens != null) Steady.tap(body, t, v -> { Intent i = a.getPackageManager().getLaunchIntentForPackage(opens); if (i != null) a.startActivity(i); });
        TextView tick = Ui.text(a, t, "✓", 20, done ? t.paperHi : 0x00000000, Theme.semi); tick.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setStroke(t.dp(1.4f), done ? t.sage : t.sepia); g.setColor(done ? t.sage : t.paperHi);
        tick.setBackground(g);
        tick.setContentDescription(done ? "Done. Tap to undo." : "Mark as done");
        int sz = Math.max(t.dp(48), t.sdp(40));
        LinearLayout.LayoutParams kp = Ui.lp(sz, sz); kp.leftMargin = t.sdp(12);
        // a bigger invisible target around the circle
        LinearLayout hit = Ui.column(a); hit.setGravity(Gravity.CENTER); hit.setMinimumWidth(t.target(64)); hit.setMinimumHeight(t.target(64));
        hit.addView(tick, kp);
        Steady.tap(hit, t, v -> { Mailbox.toggle(a, id, what); render(); });
        r.addView(hit);
        host.addView(r, Ui.wide());
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(a);
        e.setHint(hint); e.setHintTextColor(t.sepia); e.setTextColor(t.ink); e.setTypeface(Theme.regular);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp(18)); e.setInputType(type);
        e.setMinHeight(t.target(56)); e.setPadding(t.sdp(12), t.sdp(10), t.sdp(12), t.sdp(10));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(12)); g.setStroke(t.dp(1.2f), t.sepia); g.setColor(t.night ? 0x661e2650 : 0x99f3ecda);
        e.setBackground(g);
        return e;
    }

    private boolean isInside(View v) { for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == host) return true; return false; }
    private void hideKeys(View v) { android.view.inputmethod.InputMethodManager im = a.getSystemService(android.view.inputmethod.InputMethodManager.class); if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0); v.clearFocus(); }
    static String hhmm(long ms) { return ms <= 0 ? "" : DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ms)); }
}
