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
        box.removeAllViews();
        // things that stop calls coming in, said plainly (the person can't fix them; a helper can)
        if (android.provider.Settings.Global.getInt(a.getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            box.addView(warning(a, t, "Airplane mode is on. Calls can't come in.", "Ask a helper to turn it off."), Ui.wide(t.sdp(10)));
        if (Guard.instance == null)
            box.addView(warning(a, t, "Ask a helper: Herbarium's protection is off.", "Settings → Accessibility → Herbarium: switch it off and on again."), Ui.wide(t.sdp(10)));
        if (!waiting.isEmpty()) {
            box.addView(Ui.heading(a, t, waiting.size() == 1 ? "Someone called you" : "People who called you"));
            for (Missed.Call m : waiting) box.addView(missedCard(a, t, m, refresh), Ui.wide(t.sdp(10)));
        } else if (!Missed.canRead(a)) {
            box.addView(Ui.text(a, t, "Missed calls will show here once Herbarium may see them (Settings → Caretaken).", 16, t.sepia, Theme.italic), Ui.wide(t.sdp(8)));
        }

        // favourites look nothing like a missed call: round portraits under "Call someone", an action to choose,
        // never news (no times, no "called you")
        if (!fav.isEmpty()) {
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

        box.addView(Ui.heading(a, t, "Your phone"));
        TextView phone = tile(a, t, "Phone", "Dial a number", v -> open(a, Apps.dialer(a), new Intent(Intent.ACTION_DIAL)));
        TextView msgs = tile(a, t, "Messages", "Read and send texts", v -> open(a, Apps.messages(a), Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)));
        TextView people = tile(a, t, "Contacts", "Everyone you know", v -> everyone.run());   // Herbarium's own list, never Android's Contacts app
        box.addView(phone, Ui.wide(t.sdp(4)));
        box.addView(msgs, Ui.wide(t.sdp(10)));
        box.addView(people, Ui.wide(t.sdp(10)));
        TextView vm = tile(a, t, "Voicemail", "Listen to messages people left", v -> DialActivity.voicemail(a));
        box.addView(vm, Ui.wide(t.sdp(10)));
        TextView sos = tile(a, t, "Emergency call (" + DialActivity.emergencyNumber(a) + ")", "Opens the number pad with the emergency number typed in", v -> DialActivity.emergency(a));
        sos.setTextColor(t.night ? 0xffe8907a : 0xff9c3b2e);
        box.addView(sos, Ui.wide(t.sdp(18)));
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
        String when = when(m.at) + (m.times > 1 ? " · " + Summary.count(m.times, "time").replace("a time", "once") : "");
        card.addView(Ui.text(a, t, when, 18, t.sepia, Theme.regular), Ui.wide(t.sdp(4)));
        TextView call = Ui.primary(a, t, "Call " + who + " back", v -> Missed.callBack(a, m.number));
        call.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, t.btn(22));
        call.setMinHeight(Math.round(t.dp(84) * (1 + (t.scale - 1) * .35f)));
        call.setContentDescription("Call " + m.name + " back");
        card.addView(call, Ui.wide(t.sdp(14)));
        TextView later = Ui.button(a, t, "Not now", v -> { Missed.aside(a, m.number); refresh.run(); });
        card.addView(later, Ui.wide(t.sdp(10)));
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
