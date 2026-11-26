package com.herbarium.home;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.provider.ContactsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caretaken mode's Home: built around calling.
 *   1. Calls to return: one big card per person who called and is waiting ("Call Sam back" calls at once).
 *   2. People: starred contacts as big call buttons.
 *   3. Phone, Messages, Contacts: the only apps.
 * And the People page (left of Home): everyone in the contacts, each with a big Call button.
 */
final class CarePages {
    private CarePages() {}

    static final class Person { String name, number; }

    /** Fill `box` (on Home, under the clock) with the calls to return, favourites and the three apps. */
    static void fill(Activity a, Theme t, LinearLayout box, Runnable refresh, Runnable everyone) { fill(a, t, box, refresh, everyone, Missed.waiting(a), starred(a)); }

    /** The same, with the call log and contacts already read (off the main thread). */
    static void fill(Activity a, Theme t, LinearLayout box, Runnable refresh, Runnable everyone, List<Missed.Call> waiting, List<Person> fav) {
        if (t.large()) { fillLarge(a, t, box, refresh, everyone, waiting, fav); return; }
        box.removeAllViews();
        // things that stop calls coming in, said plainly (the person can't fix them; a helper can)
        if (android.provider.Settings.Global.getInt(a.getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            box.addView(warning(a, t, "Airplane mode is on. Calls can't come in.", "Ask a helper to turn it off."), Ui.wide(t.sdp(10)));
        if (Guard.instance == null)
            box.addView(warning(a, t, "Ask a helper: Herbarium's protection is off.", "Settings → Accessibility → Herbarium: switch it off and on again."), Ui.wide(t.sdp(10)));
        if (!waiting.isEmpty()) {
            if (!t.large()) box.addView(Ui.heading(a, t, waiting.size() == 1 ? "Someone called you" : "People who called you"));   // (the card says it)
            // big text: the newest call to return in full, the others counted (all of them are on the Calls page)
            int show = t.large() ? 1 : waiting.size();
            for (int i = 0; i < show; i++) box.addView(missedCard(a, t, waiting.get(i), refresh), Ui.wide(t.sdp(10)));
            if (waiting.size() > show) box.addView(Ui.text(a, t, "and " + Summary.count(waiting.size() - show, "other call") + " to return (Contacts)", 17, t.sepia, Theme.italic), Ui.wide(t.sdp(6)));
        } else if (!Missed.canRead(a)) {
            box.addView(Ui.text(a, t, "Missed calls will show here once Herbarium may see them (Settings → Caretaken).", 16, t.sepia, Theme.italic), Ui.wide(t.sdp(8)));
        }

        // favourites look nothing like a missed call: round portraits under "Call someone", an action to choose,
        // never news (no times, no "called you")
        if (!fav.isEmpty() && t.large()) {      // big text: a name button each, two to a row (portraits would push things off the screen)
            box.addView(new View(a), Ui.lp(1, t.dp(14)));
            for (int i = 0; i < fav.size(); i += 2) {
                View x = favButton(a, t, fav.get(i)), y = i + 1 < fav.size() ? favButton(a, t, fav.get(i + 1)) : new View(a);
                box.addView(Ui.sideBySide(a, t, x, y), Ui.wide(t.sdp(8)));
            }
        } else if (!fav.isEmpty()) {
            box.addView(Ui.heading(a, t, "Call someone"));
            int cols = t.huge() ? 1 : 2;
            LinearLayout row = null;
            for (int i = 0; i < fav.size(); i++) {
                if (i % cols == 0) { row = Ui.row(a); row.setGravity(android.view.Gravity.TOP); box.addView(row, Ui.wide(t.sdp(10))); }
                LinearLayout.LayoutParams lp = Ui.weight();
                if (i % cols == 1) lp.leftMargin = t.dp(10);
                row.addView(portrait(a, t, fav.get(i)), lp);
            }
            if (fav.size() % cols != 0 && row != null) row.addView(new View(a), Ui.weight());   // keep the last one its proper size
        }

        if (!t.large()) box.addView(Ui.heading(a, t, "Your phone"));
        else box.addView(new View(a), Ui.lp(1, t.dp(14)));
        TextView phone = tile(a, t, "Phone", "Dial a number", v -> open(a, Apps.dialer(a), new Intent(Intent.ACTION_DIAL)));
        TextView msgs = tile(a, t, "Messages", "Read and send texts", v -> open(a, Apps.messages(a), Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)));
        TextView people = tile(a, t, "Contacts", "Everyone you know", v -> everyone.run());   // Herbarium's own list, never Android's Contacts app
        TextView vm = tile(a, t, "Voicemail", "Listen to messages people left", v -> DialActivity.voicemail(a));
        // two to a row (short words fit at any size), so the whole of Home fits on the screen
        box.addView(Ui.sideBySide(a, t, phone, msgs), Ui.wide(t.sdp(4)));
        box.addView(Ui.sideBySide(a, t, people, vm), Ui.wide(t.sdp(10)));
        TextView sos = tile(a, t, "Emergency call (" + DialActivity.emergencyNumber(a) + ")", "Opens the number pad with the emergency number typed in", v -> DialActivity.emergency(a));
        sos.setTextColor(t.night ? 0xffe8907a : 0xff9c3b2e);
        box.addView(sos, Ui.wide(t.sdp(10)));
    }

    /** A favourite as one big button with their name: tap to call. */
    private static View favButton(Activity a, Theme t, Person p) {
        TextView b = Ui.button(a, t, p.name, v -> Missed.callBack(a, p.number));
        b.setMaxLines(2); Ui.wholeWords(b, 12);
        b.setContentDescription("Call " + p.name);
        return b;
    }

    /**
     * Caretaken Home at big text sizes: his calls and texts, as letters on the desk. Each one is a card he can act on
     * (Call back / Reply, and Done), newest first, as many as fit on the screen (the rest are counted, and wait on the
     * Calls and Messages pages). Under them, always: Phone | Texts, People | Emergency. Nothing to scroll, nothing shrunk.
     */
    static void fillLarge(Activity a, Theme t, LinearLayout box, Runnable refresh, Runnable everyone, List<Missed.Call> waiting, List<Person> fav) {
        fillFeed(a, t, box, refresh, everyone, waiting, fav, new ArrayList<>(), null);
    }

    static void fillFeed(Activity a, Theme t, LinearLayout box, Runnable refresh, Runnable everyone, List<Missed.Call> waiting, List<Person> fav,
                         List<Texts.Conv> texts, Runnable toTexts) {
        box.removeAllViews();
        int warn = t.night ? 0xffe8907a : 0xff9c3b2e;
        if (android.provider.Settings.Global.getInt(a.getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            box.addView(big(Ui.centered(Ui.text(a, t, "Airplane mode is on: no calls.", 15, warn, Theme.semi)), t, 15), Ui.wide(t.dp(4)));
        if (Guard.instance == null)
            box.addView(big(Ui.centered(Ui.text(a, t, "Ask a helper: protection is off.", 15, warn, Theme.semi)), t, 15), Ui.wide(t.dp(4)));

        // the letters: calls to return and texts waiting for him, newest first
        List<Object> feed = new ArrayList<>();
        int ci = 0, ti = 0;
        while (ci < waiting.size() || ti < texts.size()) {
            boolean call = ti >= texts.size() || (ci < waiting.size() && waiting.get(ci).at >= texts.get(ti).date);
            feed.add(call ? waiting.get(ci++) : texts.get(ti++));
        }
        for (Object o : feed) {
            View card = o instanceof Missed.Call ? callLetter(a, t, (Missed.Call) o, refresh) : textLetter(a, t, (Texts.Conv) o, refresh);
            card.setTag("letter");
            box.addView(card, Ui.wide(t.dp(10)));
        }
        TextView more = big(Ui.centered(Ui.text(a, t, "", 14, t.sepia, Theme.italic)), t, 14);
        more.setTag("more"); more.setVisibility(View.GONE);
        Steady.tap(more, t, v -> { if (toTexts != null) toTexts.run(); });
        box.addView(more, Ui.wide(t.dp(4)));

        if (feed.isEmpty()) {          // nothing waiting: a quiet line, and the people he calls most
            box.addView(big(Ui.centered(Ui.text(a, t, "Nothing new.", 19, t.ink, Theme.regular)), t, 19), Ui.wide(t.dp(10)));
            List<View> people = new ArrayList<>();
            for (int i = 0; i < fav.size() && i < 4; i++) people.add(nameButton(a, t, fav.get(i), 20));
            for (int i = 0; i < people.size(); i += 2)
                box.addView(Ui.sideBySide(a, t, people.get(i), i + 1 < people.size() ? people.get(i + 1) : new View(a)), Ui.wide(t.dp(8)));
        }

        // the way to everything else, always in the same place
        View rule = Ui.rule(a, t); LinearLayout.LayoutParams rp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(1)); rp.setMargins(t.dp(30), t.dp(14), t.dp(30), t.dp(4));
        box.addView(rule, rp);
        float size = 18;
        TextView phone = big(Ui.button(a, t, "Phone", v -> open(a, Apps.dialer(a), new Intent(Intent.ACTION_DIAL))), t, size);
        TextView msgs = big(Ui.button(a, t, "Texts", v -> open(a, Apps.messages(a), Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))), t, size);
        msgs.setContentDescription("Messages");
        TextView all = big(Ui.button(a, t, "People", v -> everyone.run()), t, size);
        all.setContentDescription("Everyone you know, with a Call button each");
        TextView sos = big(Ui.button(a, t, "Emergency", v -> DialActivity.emergency(a)), t, size);
        sos.setTextColor(warn); sos.setContentDescription("Emergency call: opens the number pad with the emergency number typed in");
        for (TextView b : new TextView[]{phone, msgs, all, sos}) Ui.wholeWords(b, 14);
        box.addView(Ui.sideBySide(a, t, phone, msgs), Ui.wide(t.dp(8)));
        box.addView(Ui.sideBySide(a, t, all, sos), Ui.wide(t.dp(8)));
    }

    /** Too tall for the screen: the last letter steps off (it's counted in "and N more"). Returns false when none is left to drop. */
    static boolean dropLetter(LinearLayout box) {
        int last = -1, letters = 0; TextView more = null;
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if ("letter".equals(v.getTag())) { last = i; letters++; }
            if ("more".equals(v.getTag())) more = (TextView) v;
        }
        if (letters <= 1 || more == null) return false;
        box.removeViewAt(last);
        int hidden = (more.getTag(R_HIDDEN) instanceof Integer ? (Integer) more.getTag(R_HIDDEN) : 0) + 1;
        more.setTag(R_HIDDEN, hidden);
        more.setText("and " + Summary.number(hidden) + " more waiting");
        more.setVisibility(View.VISIBLE);
        return true;
    }
    private static final int R_HIDDEN = 0x7f0f0001;

    /** "5:36 PM" today, "Yesterday", else the day ("Monday"). */
    static String shortWhen(long at) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance(); then.setTimeInMillis(at);
        int days = (now.get(Calendar.YEAR) - then.get(Calendar.YEAR)) * 366 + now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR);
        if (days <= 0) return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
        if (days == 1) return "Yesterday";
        return new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(new Date(at));
    }

    /** A letter's paper: the page's own colour, a gold hairline, soft corners. */
    private static LinearLayout letter(Activity a, Theme t, boolean urgent) {
        LinearLayout card = Ui.column(a);
        card.setPadding(t.dp(16), t.dp(10), t.dp(12), t.dp(12));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.dp(18));
        g.setColor(t.night ? 0x662c3870 : 0xccf3ecda); g.setStroke(t.dp(urgent ? 2 : 1), urgent ? (t.night ? t.gold : 0xff9a7428) : (t.night ? 0x99b8913e : 0x889a7428));
        card.setBackground(g);
        return card;
    }

    private static View callLetter(Activity a, Theme t, Missed.Call m, Runnable refresh) {
        LinearLayout card = letter(a, t, true);
        card.addView(big(Ui.caps(a, t, (m.times > 1 ? m.times + " calls" : "A call") + " · " + shortWhen(m.at), 11, t.sage), t, 11));
        card.addView(oneLine(big(Ui.text(a, t, m.name, 21, t.ink, Theme.semi), t, 21), t), Ui.wide(t.dp(2)));
        TextView call = big(Ui.primary(a, t, "Call back", v -> Missed.callBack(a, m.number)), t, 18);
        call.setContentDescription("Call " + m.name + " back");
        TextView done = big(Ui.button(a, t, "Done", v -> { Missed.aside(a, m.number); refresh.run(); }), t, 18);
        done.setContentDescription("Done with " + m.name + "'s call");
        card.addView(twoToOne(a, t, call, done), Ui.wide(t.dp(8)));
        return card;
    }

    private static View textLetter(Activity a, Theme t, Texts.Conv v, Runnable refresh) {
        LinearLayout card = letter(a, t, v.unread > 0);
        card.addView(big(Ui.caps(a, t, (v.unread > 1 ? v.unread + " letters" : "A letter") + " · " + shortWhen(v.date), 11, t.sage), t, 11));
        card.addView(oneLine(big(Ui.text(a, t, v.name, 21, t.ink, Theme.semi), t, 21), t), Ui.wide(t.dp(2)));
        TextView said = big(Ui.text(a, t, Sorter.withoutLinks(v.snippet == null ? "" : v.snippet), 17, t.ink, Theme.regular), t, 17);
        said.setMaxLines(2); said.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(said, Ui.wide(t.dp(2)));
        Runnable open = () -> a.startActivity(new Intent(a, ThreadActivity.class).putExtra("thread", v.thread).putExtra("to", v.addresses.toArray(new String[0])));
        Steady.tap(card, t, x -> open.run());
        card.setContentDescription("Letter from " + v.name + ": " + v.snippet + ". Tap to read it all.");
        TextView reply = big(Ui.primary(a, t, "Reply", x -> a.startActivity(new Intent(a, ThreadActivity.class).putExtra("thread", v.thread)
                .putExtra("to", v.addresses.toArray(new String[0])).putExtra("type", true))), t, 18);
        reply.setContentDescription("Reply to " + v.name);
        TextView done = big(Ui.button(a, t, "Done", x -> { HomePages.putAway(a, v.thread, v.date); refresh.run(); }), t, 18);
        done.setContentDescription("Done with " + v.name + "'s letter, no reply needed");
        card.addView(twoToOne(a, t, reply, done), Ui.wide(t.dp(8)));
        return card;
    }

    /** A name on one line: a long one comes down a little in size rather than take a second line. */
    private static TextView oneLine(TextView v, Theme t) {
        v.setMaxLines(1);
        v.setAutoSizeTextTypeUniformWithConfiguration(Math.round(t.btn(13)), Math.round(t.btn(21)), 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        return v;
    }

    /** The main action wide, the other narrow, side by side. */
    private static LinearLayout twoToOne(Activity a, Theme t, View main, View other) {
        LinearLayout r = Ui.row(a);
        LinearLayout.LayoutParams m = Ui.weight(); m.weight = 1.6f; m.rightMargin = t.dp(8);
        r.addView(main, m); r.addView(other, Ui.weight());
        if (other instanceof TextView) Ui.wholeWords((TextView) other, 12);
        if (main instanceof TextView) Ui.wholeWords((TextView) main, 12);
        return r;
    }

    /** Text and button size for the big Caretaken Home: the button scale (t.btn), tight padding, never taller than needed. */
    private static <V extends TextView> V big(V v, Theme t, float sp) {
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, t.btn(sp));
        v.setLineSpacing(0, 1.05f);
        if (v.getBackground() != null) { v.setMinHeight(t.dp(64)); v.setMinimumHeight(t.dp(64)); v.setPadding(t.dp(8), t.dp(6), t.dp(8), t.dp(6)); }
        return v;
    }

    private static View nameButton(Activity a, Theme t, Person p, float size) {
        TextView b = big(Ui.button(a, t, Missed.first(p.name), v -> Missed.callBack(a, p.number)), t, size);
        b.setMaxLines(1); Ui.wholeWords(b, 14);
        b.setContentDescription("Call " + p.name);
        return b;
    }

    /** A plain warning card: what's wrong, and what to do. */
    private static View warning(Activity a, Theme t, String what, String fix) {
        LinearLayout card = Ui.column(a);
        card.setPadding(t.dp(18), t.sdp(14), t.dp(18), t.sdp(14));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(16));
        g.setColor(t.night ? 0x44c96a55 : 0x33c96a55); g.setStroke(t.dp(2), t.night ? 0xffe8907a : 0xff9c3b2e);
        card.setBackground(g);
        card.addView(Ui.text(a, t, what, 21, t.ink, Theme.semi));
        card.addView(Ui.text(a, t, fix, 17, t.sepia, Theme.regular), Ui.wide(t.sdp(6)));
        return card;
    }

    /** A call to return: who, when, and the biggest button on the screen. */
    private static View missedCard(Activity a, Theme t, Missed.Call m, Runnable refresh) {
        LinearLayout card = Ui.column(a);
        card.setPadding(t.dp(18), t.sdp(16), t.dp(18), t.sdp(16));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(18));
        g.setColor(t.night ? 0x552c3870 : 0xccf3ecda); g.setStroke(t.dp(t.contrast ? 3 : 2), t.night ? t.gold : 0xff9a7428);
        card.setBackground(g);
        String who = Missed.first(m.name);
        TextView name = Ui.wholeWords(Ui.text(a, t, m.name + " called", 26, t.ink, Theme.semi), 16);
        card.addView(name, Ui.wide());
        String when = when(m.at) + (m.times > 1 && !t.large() ? " · " + Summary.count(m.times, "time").replace("a time", "once") : "");
        card.addView(Ui.text(a, t, when, t.large() ? 16 : 18, t.sepia, Theme.regular), Ui.wide(t.sdp(4)));
        TextView call = Ui.primary(a, t, "Call " + who + " back", v -> Missed.callBack(a, m.number));
        call.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, t.btn(22));
        call.setMinHeight(Math.round(t.dp(84) * (1 + (t.scale - 1) * .35f)));
        call.setContentDescription("Call " + m.name + " back");
        TextView later = Ui.button(a, t, "Not now", v -> { Missed.aside(a, m.number); refresh.run(); });
        if (t.large()) {          // side by side: the call back wide, "Not now" narrow
            LinearLayout r = Ui.row(a);
            LinearLayout.LayoutParams cw = Ui.weight(); cw.weight = 2; cw.rightMargin = t.dp(8);
            r.addView(call, cw); r.addView(later, Ui.weight());
            Ui.wholeWords(later, 12);
            card.addView(r, Ui.wide(t.sdp(10)));
        } else {
            card.addView(call, Ui.wide(t.sdp(14)));
            card.addView(later, Ui.wide(t.sdp(10)));
        }
        return card;
    }

    static String when(long at) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance(); then.setTimeInMillis(at);
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) return "Today at " + time;
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) return "Yesterday at " + time;
        return new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(new Date(at)) + " at " + time;
    }

    /** One person: their name, and a Call button that rings them at once. */
    static View callRow(Activity a, Theme t, Person p) { return callRow(a, t, p, "Call", null); }

    /** One person: their name (and a line under it, if given), and a button that rings them at once. */
    static View callRow(Activity a, Theme t, Person p, String label, String detail) {
        LinearLayout r = t.large() ? Ui.column(a) : Ui.row(a);
        r.setPadding(t.dp(16), t.sdp(10), t.dp(12), t.sdp(10));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(16));
        g.setColor(t.night ? 0x332c3870 : 0x66f3ecda); g.setStroke(t.dp(1), 0x556e5d45);
        r.setBackground(g);
        LinearLayout words = Ui.column(a);
        TextView name = Ui.wholeWords(Ui.text(a, t, p.name, 22, t.ink, Theme.regular), 16);
        name.setMaxLines(2);
        words.addView(name, Ui.wide());
        if (detail != null) words.addView(Ui.text(a, t, detail, 15, t.sepia, Theme.regular), Ui.wide(t.sdp(2)));
        TextView call = Ui.primary(a, t, label, v -> Missed.callBack(a, p.number));
        call.setContentDescription(label + ": " + p.name);
        if (t.large()) { r.addView(words, Ui.wide()); r.addView(call, Ui.wide(t.sdp(10))); }
        else {
            r.addView(words, Ui.weight());
            LinearLayout.LayoutParams cp = Ui.lp(Math.round(t.dp(130) * (1 + (t.scale - 1) * .35f)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT); cp.leftMargin = t.dp(12);
            r.addView(call, cp);
        }
        return r;
    }

    /** A favourite: a round portrait (their photo, or their initial), their name, and a small handset. One tap calls. */
    static View portrait(Activity a, Theme t, Person p) {
        LinearLayout tile = Ui.column(a);
        tile.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        tile.setPadding(t.dp(8), t.sdp(14), t.dp(8), t.sdp(12));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(22));
        g.setColor(t.night ? 0x222c3870 : 0x44f3ecda); g.setStroke(t.dp(1), t.night ? 0x5593c2a6 : 0x557fa38a);
        tile.setBackground(g);
        int size = Math.round(t.dp(88) * (1 + (t.scale - 1) * .3f));
        android.graphics.Bitmap photo = photo(a, p.number, size);
        View face = new View(a) {
            final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas c) {
                float r = getWidth() / 2f;
                if (photo != null) {
                    android.graphics.BitmapShader sh = new android.graphics.BitmapShader(photo, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                    pt.setShader(sh); c.drawCircle(r, r, r - t.dp(2), pt); pt.setShader(null);
                } else {
                    pt.setColor(t.night ? 0xff2c3870 : 0xffe2d8bd); c.drawCircle(r, r, r - t.dp(2), pt);
                    pt.setColor(t.ink); pt.setTypeface(Theme.regular); pt.setTextAlign(android.graphics.Paint.Align.CENTER); pt.setTextSize(r * .95f);
                    String ini = p.name.trim().isEmpty() ? "?" : p.name.trim().substring(0, 1).toUpperCase(java.util.Locale.ROOT);
                    android.graphics.Paint.FontMetrics fm = pt.getFontMetrics(); c.drawText(ini, r, r - (fm.ascent + fm.descent) / 2f, pt);
                }
                pt.setStyle(android.graphics.Paint.Style.STROKE); pt.setStrokeWidth(t.dp(1.5f)); pt.setColor(t.sage);
                c.drawCircle(r, r, r - t.dp(2), pt); pt.setStyle(android.graphics.Paint.Style.FILL);
            }
        };
        LinearLayout.LayoutParams fp = Ui.lp(size, size); fp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        tile.addView(face, fp);
        TextView name = Ui.centered(Ui.text(a, t, p.name, 20, t.ink, Theme.regular)); name.setMaxLines(2);
        tile.addView(name, Ui.wide(t.sdp(8)));
        TextView call = Ui.centered(Ui.text(a, t, "✆  Call", 16, t.sage, Theme.semi));
        tile.addView(call, Ui.wide(t.sdp(4)));
        Steady.tap(tile, t, v -> Missed.callBack(a, p.number));
        tile.setContentDescription("Call " + p.name);
        return tile;
    }

    /** The contact's photo, square, if they have one. */
    private static android.graphics.Bitmap photo(Activity a, String number, int size) {
        if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        android.net.Uri u = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number));
        try (Cursor c = a.getContentResolver().query(u, new String[]{ContactsContract.PhoneLookup.PHOTO_URI}, null, null, null)) {
            if (c == null || !c.moveToFirst() || c.getString(0) == null) return null;
            try (java.io.InputStream in = a.getContentResolver().openInputStream(android.net.Uri.parse(c.getString(0)))) {
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
                if (b == null) return null;
                int side = Math.min(b.getWidth(), b.getHeight());
                android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
                return android.graphics.Bitmap.createScaledBitmap(sq, size, size, true);
            }
        } catch (Exception e) { return null; }
    }

    /** A big outlined tile for one of the three apps. */
    private static TextView tile(Activity a, Theme t, String title, String detail, Steady.Action act) {
        TextView v = Ui.button(a, t, title, act);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, t.btn(21));
        v.setMinHeight(Math.round(t.dp(76) * (1 + (t.scale - 1) * .35f)));
        v.setContentDescription(title + ": " + detail);
        Ui.wholeWords(v, 12);       // half-width tiles at big text: the word shrinks a little rather than break
        return v;
    }

    private static void open(Activity a, String pkg, Intent fallback) {
        if (a.getPackageName().equals(pkg)) {       // Herbarium is the texting or calling app: its own screens
            boolean texts = fallback.hasCategory(Intent.CATEGORY_APP_MESSAGING) || (fallback.getSelector() != null && fallback.getSelector().hasCategory(Intent.CATEGORY_APP_MESSAGING));
            a.startActivity(new Intent(a, texts ? MessagesActivity.class : DialActivity.class));
            return;
        }
        Intent i = pkg == null || pkg.isEmpty() ? null : a.getPackageManager().getLaunchIntentForPackage(pkg);
        try { a.startActivity(i != null ? i : fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { try { a.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {} }
    }

    // ------------------------------------------------------------------ contacts
    static List<Person> starred(Activity a) { return people(a, true); }

    /** Contacts with a phone number, one row each (the starred ones, or everyone), by name. */
    static List<Person> people(Activity a, boolean starredOnly) {
        List<Person> out = new ArrayList<>();
        if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return out;
        Map<String, Person> byName = new LinkedHashMap<>();
        try (Cursor c = a.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY},
                starredOnly ? ContactsContract.CommonDataKinds.Phone.STARRED + " = 1" : null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC, " + ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC")) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(0), number = c.getString(1);
                if (name == null || number == null || byName.containsKey(name)) continue;
                Person p = new Person(); p.name = name; p.number = number; byName.put(name, p);
                if (starredOnly && byName.size() >= 8) break;
            }
        } catch (Exception ignored) {}
        out.addAll(byName.values());
        return out;
    }

    /** The People page: everyone, each with a big Call button. */
    static void fillPeople(Activity a, Theme t, LinearLayout col) {
        col.removeAllViews();
        col.addView(Ui.centered(Ui.caps(a, t, "People", 28, t.ink)));
        col.addView(Ui.centered(Ui.text(a, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
        List<Person> all = people(a, false);
        if (all.isEmpty()) {
            col.addView(Ui.centered(Ui.body(a, t, a.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    ? "No contacts yet. Add people in Contacts and they'll appear here." : "Let Herbarium see contacts (Settings → Calls) and everyone you know appears here.", 19)), Ui.wide(t.sdp(20)));
            return;
        }
        char letter = 0;
        for (Person p : all) {
            char first = Character.toUpperCase(p.name.charAt(0));
            if (first != letter && Character.isLetter(first)) {
                letter = first;
                TextView h = Ui.caps(a, t, String.valueOf(letter), 20, t.sage);
                h.setPadding(t.dp(4), t.sdp(16), 0, t.sdp(4));
                col.addView(h, Ui.wide());
            }
            col.addView(callRow(a, t, p), Ui.wide(t.sdp(8)));
        }
    }
}
