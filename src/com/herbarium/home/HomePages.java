package com.herbarium.home;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.provider.CallLog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The Calls and Messages pages on Home, one swipe either side of Home. Everything is read off the main thread, then
 * laid out at once.
 *   Calls:    "Call a number", calls to return, recent calls, then everyone in the contacts with a Call button.
 *   Messages: "Write a message", the conversations with people (newest first, new ones marked), and in normal mode
 *             a line for codes, updates and the rest, all one tap from "All messages".
 */
final class HomePages {
    private HomePages() {}

    // ------------------------------------------------------------------ calls
    static final class Recent { String name, number; long at; int type; }

    static void fillCalls(Activity a, Theme t, LinearLayout col, ExecutorService bg, Handler ui) {
        bg.execute(() -> {
            List<Missed.Call> waiting = Missed.waiting(a);
            // Caretaken mode: no "Recent" list (old calls read as people trying to reach them); only calls to return
            List<Recent> recent = Care.on(a) ? new ArrayList<>() : recent(a, 8);
            for (Missed.Call m : waiting) recent.removeIf(r -> Calls.norm(r.number).equals(Calls.norm(m.number)));   // shown once, under "Call back"
            List<CarePages.Person> everyone = CarePages.people(a, false);
            ui.post(() -> {
                col.removeAllViews();
                col.addView(Ui.centered(Ui.caps(a, t, "Calls", 28, t.ink)));
                col.addView(Ui.centered(Ui.text(a, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
                TextView dial = Ui.primary(a, t, "Call a number", v -> a.startActivity(new Intent(Intent.ACTION_DIAL)));
                dial.setMinHeight(Math.round(t.dp(76) * (1 + (t.scale - 1) * .35f)));
                col.addView(dial, Ui.wide(t.sdp(14)));
                if (!waiting.isEmpty()) {
                    col.addView(Ui.heading(a, t, waiting.size() == 1 ? "Call back" : "Calls to return"));
                    for (Missed.Call m : waiting) {
                        CarePages.Person p = new CarePages.Person(); p.name = m.name; p.number = m.number;
                        String when = CarePages.when(m.at);
                        View r = CarePages.callRow(a, t, p, "Call back", "Called " + Character.toLowerCase(when.charAt(0)) + when.substring(1) + (m.times > 1 ? " · " + Summary.count(m.times, "time").replace("a time", "once") : ""));
                        col.addView(r, Ui.wide(t.sdp(8)));
                    }
                }
                if (!recent.isEmpty()) {
                    col.addView(Ui.heading(a, t, "Recent"));
                    for (Recent r : recent) {
                        CarePages.Person p = new CarePages.Person(); p.name = r.name; p.number = r.number;
                        String what = (r.type == CallLog.Calls.OUTGOING_TYPE ? "You called · " : r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE ? "Missed · " : "Called you · ")
                                + MessagesActivity.when(r.at);
                        col.addView(CarePages.callRow(a, t, p, "Call", what), Ui.wide(t.sdp(8)));
                    }
                }
                if (!everyone.isEmpty()) {
                    col.addView(Ui.heading(a, t, "Everyone"));
                    char letter = 0;
                    for (CarePages.Person p : everyone) {
                        char first = Character.toUpperCase(p.name.charAt(0));
                        if (first != letter && Character.isLetter(first)) {
                            letter = first;
                            TextView h = Ui.caps(a, t, String.valueOf(letter), 18, t.sage); h.setPadding(t.dp(4), t.sdp(12), 0, t.sdp(2));
                            col.addView(h, Ui.wide());
                        }
                        col.addView(CarePages.callRow(a, t, p, "Call", null), Ui.wide(t.sdp(8)));
                    }
                } else if (a.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                    col.addView(Ui.text(a, t, "Let Herbarium see contacts (Settings → Calls) and everyone you know appears here.", 17, t.sepia, Theme.italic), Ui.wide(t.sdp(16)));
            });
        });
    }

    /** The last few people spoken with (one line each, newest first). Strangers turned away are never listed. */
    static List<Recent> recent(Activity a, int max) {
        List<Recent> out = new ArrayList<>();
        if (a.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return out;
        Texts.Book book = new Texts.Book(a);
        Map<String, Recent> seen = new LinkedHashMap<>();
        try (Cursor c = a.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                null, null, CallLog.Calls.DATE + " DESC LIMIT 200")) {
            while (c != null && c.moveToNext() && seen.size() < max) {
                String n = c.getString(0), k = Calls.norm(n);
                if (k.isEmpty() || seen.containsKey(k)) continue;
                int type = c.getInt(1);
                if (type == CallLog.Calls.BLOCKED_TYPE) continue;
                if (Care.on(a) && !book.known(n)) continue;         // Caretaken: only people they know
                Recent r = new Recent(); r.number = n; r.name = book.name(n); r.at = c.getLong(2); r.type = type;
                seen.put(k, r);
            }
        } catch (Exception ignored) {}
        out.addAll(seen.values());
        return out;
    }

    // ------------------------------------------------------------------ messages
    static void fillMessages(Activity a, Theme t, LinearLayout col, ExecutorService bg, Handler ui) {
        bg.execute(() -> {
            boolean ours = Texts.isDefault(a);
            List<Texts.Conv> all = ours ? Texts.conversations(a) : new ArrayList<>();
            ui.post(() -> {
                col.removeAllViews();
                boolean care = Care.on(a);
                col.addView(Ui.centered(Ui.caps(a, t, "Messages", 28, t.ink)));
                col.addView(Ui.centered(Ui.text(a, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
                if (!ours) {
                    col.addView(Ui.body(a, t, "Herbarium can keep your texts here: sorted, calm, and in big letters.", 20), Ui.wide(t.sdp(16)));
                    col.addView(Ui.primary(a, t, "Set it up", v -> a.startActivity(new Intent(a, MessagesActivity.class))), Ui.wide(t.sdp(12)));
                    return;
                }
                TextView write = Ui.primary(a, t, "New message", v -> a.startActivity(new Intent(a, ThreadActivity.class)));
                TextView every = Ui.button(a, t, "All messages", v -> a.startActivity(new Intent(a, MessagesActivity.class)));
                col.addView(Ui.pair(a, t, write, every), Ui.wide(t.sdp(14)));
                int shown = 0, others = 0, newOthers = 0;
                java.util.Set<String> mine = MmsWork.myNumbers(a);
                // only what's waiting for you: once you've replied, a conversation steps off this page (it's still in
                // All messages), and comes back when they write again
                for (Texts.Conv v : all) {
                    if (!v.group && mine.contains(Sorter.key(v.addresses.get(0)))) continue;   // texts to yourself
                    if (v.pile != Sorter.PEOPLE) { if (v.unread > 0) { others++; newOthers++; } continue; }
                    if (v.answered || putAway(a, v)) continue;
                    if (System.currentTimeMillis() - v.date > 14 * 86_400_000L && v.unread == 0) continue;   // old and already read: let it rest
                    if (shown == 0) col.addView(Ui.heading(a, t, "Waiting for you"));
                    if (shown++ >= 12) continue;
                    col.addView(row(a, t, v, care), Ui.wide(t.sdp(8)));
                }
                // get the top few ready, so tapping one opens it at once
                List<Long> top = new ArrayList<>();
                for (Texts.Conv v : all) if (v.pile == Sorter.PEOPLE && !v.answered && top.size() < 5) top.add(v.thread);
                bg.execute(() -> Texts.prefetch(a.getApplicationContext(), top));
                if (shown == 0) {
                    col.addView(Ui.centered(Ui.text(a, t, "All answered.", 24, t.ink, Theme.regular)), Ui.wide(t.sdp(30)));
                    col.addView(Ui.centered(Ui.text(a, t, "New messages from people wait here until you reply.", 17, t.sepia, Theme.italic)), Ui.wide(t.sdp(8)));
                }
                if (!care && newOthers > 0)
                    col.addView(Ui.text(a, t, Summary.capital(Summary.count(newOthers, "new code or update")) + " in All messages.", 16, t.sepia, Theme.italic), Ui.wide(t.sdp(16)));
            });
        });
    }

    /** Put a conversation away (until a newer message arrives). */
    static void putAway(android.content.Context c, long thread, long latest) {
        android.content.SharedPreferences p = Theme.prefs(c);
        try { org.json.JSONObject o = new org.json.JSONObject(p.getString("msgsDone", "{}")); o.put(String.valueOf(thread), latest); p.edit().putString("msgsDone", o.toString()).apply(); } catch (Exception ignored) {}
    }

    static boolean putAway(android.content.Context c, Texts.Conv v) {
        try { return new org.json.JSONObject(Theme.prefs(c).getString("msgsDone", "{}")).optLong(String.valueOf(v.thread), 0) >= v.date; }
        catch (Exception e) { return false; }
    }

    private static View row(Activity a, Theme t, Texts.Conv v, boolean care) {
        LinearLayout r = Ui.column(a);
        r.setPadding(t.dp(16), t.sdp(12), t.dp(16), t.sdp(12));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(16));
        g.setColor(t.night ? 0x332c3870 : 0x66f3ecda); g.setStroke(t.dp(v.unread > 0 ? 2 : 1), v.unread > 0 ? t.gold : 0x556e5d45);
        r.setBackground(g);
        LinearLayout top = Ui.row(a);
        TextView name = Ui.text(a, t, v.name, 21, t.ink, v.unread > 0 ? Theme.semi : Theme.regular); name.setMaxLines(2);
        top.addView(name, Ui.weight());
        LinearLayout.LayoutParams wp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); wp.leftMargin = t.dp(10);
        top.addView(Ui.text(a, t, MessagesActivity.when(v.date), 15, t.sepia, Theme.regular), wp);
        r.addView(top, Ui.wide());
        String said = care ? Sorter.withoutLinks(v.snippet) : v.snippet;
        r.addView(Ui.ellipsize(Ui.text(a, t, said, 18, v.unread > 0 ? t.ink : t.sepia, Theme.regular), 2), Ui.wide(t.sdp(4)));
        if (v.unread > 0) r.addView(Ui.text(a, t, "New", 15, t.gold, Theme.semi), Ui.wide(t.sdp(4)));
        // one tap: the conversation opens with the keyboard up, ready to write
        boolean canReply = !v.addresses.isEmpty() && !v.addresses.get(0).matches(".*[A-Za-z].*");
        TextView reply = canReply ? Ui.button(a, t, v.group ? "Reply to everyone" : "Reply to " + Missed.first(v.name),
                x -> a.startActivity(new Intent(a, ThreadActivity.class).putExtra("thread", v.thread).putExtra("to", v.addresses.toArray(new String[0])))) : null;
        // "Done": no reply needed; it steps off this page until they write again
        TextView done = Ui.button(a, t, "Done", x -> {
            putAway(a, v.thread, v.date);
            if (r.getParent() instanceof ViewGroup) ((ViewGroup) r.getParent()).removeView(r);
            a.getApplicationContext().sendBroadcast(new Intent(Texts.CHANGED).setPackage(a.getPackageName()));
        });
        done.setContentDescription("Done with " + v.name + ", no reply needed");
        if (reply != null) {
            LinearLayout pair = Ui.row(a);
            LinearLayout.LayoutParams rp = Ui.weight(); rp.weight = 2; rp.rightMargin = t.dp(8);
            pair.addView(reply, rp); pair.addView(done, Ui.weight());
            r.addView(t.large() ? Ui.pair(a, t, reply, done) : pair, Ui.wide(t.sdp(10)));
        } else r.addView(done, Ui.wide(t.sdp(10)));
        Steady.tap(r, t, x -> a.startActivity(new Intent(a, ThreadActivity.class).putExtra("thread", v.thread).putExtra("to", v.addresses.toArray(new String[0]))));
        r.setContentDescription(v.name + (v.unread > 0 ? ", new" : "") + ". " + said);
        return r;
    }
}
