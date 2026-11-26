package com.herbarium.home;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the notifications Herbarium is holding into a short summary (worked out on the phone; nothing is sent anywhere).
 * The headline names every source in as few words as possible, one line per kind:
 *   Missed call: Mom
 *   Messages: Anna (2), Ben
 *   Reminder: Dentist at 3
 *   Can wait: Gmail (3), Amazon, Unknown numbers (4)
 *   first:    calls, people's messages, reminders and alerts ("Needs you")
 *   later:    everything else, one group per app ("Can wait")
 *   resting:  how many came from apps Clausus is blocking (a number only)
 */
final class Summary {
    final String headline;
    final List<Group> first = new ArrayList<>(), quieter = new ArrayList<>();
    final int resting;

    /** The two sections of the Letters page and the shade. */
    static final String FIRST = "Needs you", LATER = "Can wait";
    static final String NOTHING = "Nothing new.";

    static final class Group {
        final String title; final List<Notes.Note> notes = new ArrayList<>(); final boolean person;
        Group(String t, boolean p) { title = t; person = p; }
        Notes.Note latest() { return notes.get(0); }
        /** "Anna · 2 messages", "Missed call · Mom", "Gmail · 3" */
        String line() {
            Notes.Note n = latest();
            if (n.call) return (n.title.isEmpty() ? "A call" : n.title) + (n.text.isEmpty() ? "" : " · " + n.text);
            if (person) return title + (notes.size() > 1 ? " (" + notes.size() + ")" : "");
            return title + (notes.size() > 1 ? " · " + notes.size() : "");
        }
        /** The words to show under the line. */
        String detail() {
            Notes.Note n = latest();
            if (person) return n.text;
            if (notes.size() == 1) return join(n.title, n.text);
            List<String> who = new ArrayList<>();
            for (Notes.Note x : notes) { String w = x.person != null && !x.person.isEmpty() ? x.person : x.title; if (!w.isEmpty() && !who.contains(w) && who.size() < 4) who.add(w); }
            return who.isEmpty() ? join(n.title, n.text) : String.join(", ", who);
        }
        /** A card's main line, under a heading that already names the app: the notice's own title, so a card
         *  never reads "CALENDAR / Calendar". */
        String cardLine() {
            Notes.Note n = latest();
            if (n.call) return n.title.isEmpty() ? "A call" : n.title;
            if (person || !title.equals(n.app)) return line();
            if (notes.size() > 1) return capital(count(notes.size(), "notice"));
            String own = ownTitle(n);
            return !own.isEmpty() ? own : !n.text.isEmpty() ? n.text : title;
        }
        String cardDetail() {
            Notes.Note n = latest();
            if (n.call) return n.text;
            if (person || !title.equals(n.app) || notes.size() > 1) return detail();
            return ownTitle(n).isEmpty() ? "" : n.text;      // with no title of its own, the text is already the line
        }
        /** What the notice itself is about, in a few words: its own title, else its text. */
        String gist() {
            Notes.Note n = latest();
            String own = ownTitle(n);
            return clip(!own.isEmpty() ? own : !n.text.isEmpty() ? n.text : title, 28);
        }
        private static String ownTitle(Notes.Note n) { return n.title.equalsIgnoreCase(n.app) ? "" : n.title; }
    }

    private Summary(String h, int r) { headline = h; resting = r; }

    static Summary of(Context c) {
        Set<String> blocked = State.locked(c) ? State.packages(c) : new java.util.HashSet<>();
        List<Notes.Note> all = new ArrayList<>();
        // (Caretaken Home already has a big card for each call to return)
        if (!Care.on(c)) for (Missed.Call m : Missed.waiting(c, true)) {      // missed calls (Herbarium is the phone app, so they're not in Android's list)
            Notes.Note n = new Notes.Note();
            n.key = Notes.MISSED + m.number; n.pkg = c.getPackageName(); n.app = "Phone"; n.title = m.name;
            n.text = m.times > 1 ? "called " + m.times + " times" : ""; n.person = ""; n.when = m.at;
            n.call = true; n.important = true; n.category = android.app.Notification.CATEGORY_MISSED_CALL;
            all.add(n);
        }
        all.addAll(Notes.all(c));
        return build(all, blocked, who -> Calls.inContacts(c, who));
    }

    /** The summary itself, with no Android in it (so tests can run it). */
    static Summary build(List<Notes.Note> all, Set<String> blocked, java.util.function.Predicate<String> inContacts) {
        Map<String, Group> first = new LinkedHashMap<>(), quiet = new LinkedHashMap<>();
        int resting = 0;
        for (Notes.Note n : all) {
            if (blocked.contains(n.pkg)) { resting++; continue; }
            if (n.call) { first.computeIfAbsent("call:" + n.key, k -> new Group("Call", false)).notes.add(n); continue; }
            if (n.message && n.person != null && !n.person.isEmpty()) {
                // a bare number that isn't a saved contact is usually automated (codes, offers): it can wait
                boolean bare = n.person.replaceAll("[^0-9]", "").length() >= 5 && n.person.replaceAll("[0-9()+\\-. ]", "").isEmpty();
                if (bare && !inContacts.test(n.person)) { quiet.computeIfAbsent("unknown-texts", k -> new Group("Unknown numbers", false)).notes.add(n); continue; }
                first.computeIfAbsent("msg:" + n.person, k -> new Group(n.person, true)).notes.add(n); continue;
            }
            if (n.important) { first.computeIfAbsent("imp:" + n.key, k -> new Group(n.app, false)).notes.add(n); continue; }
            quiet.computeIfAbsent(n.pkg, k -> new Group(n.app, false)).notes.add(n);
        }
        List<String> calls = new ArrayList<>(), people = new ArrayList<>(), reminders = new ArrayList<>(), alerts = new ArrayList<>(), later = new ArrayList<>();
        Map<String, Integer> callers = new LinkedHashMap<>();
        for (Group g : first.values()) {
            Notes.Note n = g.latest();
            if (n.call) callers.merge(n.title.isEmpty() ? "Unknown" : n.title, 1, Integer::sum);
            else if (g.person) people.add(named(g.title, g.notes.size()));
            else if (reminder(n)) reminders.add(g.gist());
            else alerts.add(g.title + (g.notes.size() == 1 && !g.gist().equals(g.title) ? " · " + g.gist() : ""));
        }
        for (Map.Entry<String, Integer> e : callers.entrySet()) calls.add(named(e.getKey(), e.getValue()));
        for (Group g : quiet.values()) later.add(named(g.title, g.notes.size()));
        List<String> lines = new ArrayList<>();
        line(lines, callers.values().stream().mapToInt(Integer::intValue).sum() == 1 ? "Missed call" : "Missed calls", calls);
        line(lines, "Messages", people);
        line(lines, reminders.size() == 1 ? "Reminder" : "Reminders", reminders);
        line(lines, alerts.size() == 1 ? "Alert" : "Alerts", alerts);
        line(lines, LATER, later);
        Summary s = new Summary(lines.isEmpty() ? NOTHING : String.join("\n", lines), resting);
        s.first.addAll(first.values()); s.quieter.addAll(quiet.values());
        return s;
    }

    /** "Anna", "Anna (2)" */
    private static String named(String who, int n) { return n > 1 ? who + " (" + n + ")" : who; }
    /** "Messages: Anna (2), Ben, Sam +2": at most three names on a line. */
    private static void line(List<String> out, String label, List<String> items) {
        if (items.isEmpty()) return;
        List<String> shown = items.size() > 3 ? new ArrayList<>(items.subList(0, 3)) : items;
        out.add(label + ": " + String.join(", ", shown) + (items.size() > 3 ? " +" + (items.size() - 3) : ""));
    }
    private static String clip(String s, int max) {
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max);
        return s.substring(0, cut > max / 2 ? cut : max).replaceAll("[,.;:]$", "") + "…";
    }

    /** The headline with each line's label ("Messages:") in the quiet colour, so the names stand out. */
    CharSequence styled(Theme t) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(headline);
        int at = 0;
        for (String l : headline.split("\n")) {
            int colon = l.indexOf(": ");
            if (colon > 0) {
                b.setSpan(new android.text.style.ForegroundColorSpan(t.sepia), at, at + colon + 1, 0);
                b.setSpan(new android.text.style.RelativeSizeSpan(.85f), at, at + colon + 1, 0);
            }
            at += l.length() + 1;
        }
        return b;
    }

    /** The headline on one line (the screensaver, small places). */
    String oneLine() { return headline.replace("\n", " · "); }

    /** The resting count, said plainly: "5 from blocked apps, held until Clausus is off." */
    String restingLine() { return resting == 0 ? "" : resting + " from blocked apps, held until Clausus is off."; }

    /** Whether this phone has a voice to read aloud with (phones without Google's services may have none). */
    static boolean canSpeak(android.content.Context c) {
        return !c.getPackageManager().queryIntentServices(new android.content.Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isEmpty();
    }

    /** What "Read it to me" says: the same things as full sentences (separators read badly aloud). */
    String spoken() {
        if (first.isEmpty() && quieter.isEmpty()) return "Nothing new.";
        StringBuilder b = new StringBuilder();
        for (Group g : first) {
            Notes.Note n = g.latest();
            if (n.call) b.append(n.title.isEmpty() ? "Someone" : n.title).append(" called. ");
            else if (g.person) b.append(g.title).append(g.notes.size() > 1 ? " sent " + number(g.notes.size()) + " messages. The last one says: " : " says: ").append(n.text).append(". ");
            else b.append(g.title).append(": ").append(g.gist()).append(". ");
        }
        if (!quieter.isEmpty()) {
            List<String> w = new ArrayList<>();
            for (Group g : quieter) w.add(g.notes.size() > 1 ? number(g.notes.size()) + " from " + g.title : "one from " + g.title);
            b.append("These can wait: ").append(and(w)).append(".");
        }
        return b.toString().replace("..", ".").trim();
    }

    private static boolean reminder(Notes.Note n) {
        String c = n.category == null ? "" : n.category;
        return c.equals(android.app.Notification.CATEGORY_REMINDER) || c.equals(android.app.Notification.CATEGORY_EVENT) || c.equals(android.app.Notification.CATEGORY_ALARM);
    }

    /** Numbers in words, as a letter would write them ("twenty-three"); past ninety-nine, "many". */
    static String number(int n) {
        String[] w = {"no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
                "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        if (n < 0) return String.valueOf(n);
        if (n < 20) return w[n];
        if (n < 100) return tens[n / 10] + (n % 10 == 0 ? "" : "-" + w[n % 10]);
        return "many";
    }
    static String count(int n, String thing) { return (n == 1 ? (thing.matches("^[aeiou].*") ? "an " : "a ") : number(n) + " ") + thing + (n == 1 ? "" : "s"); }
    static String and(List<String> p) { if (p.size() == 1) return p.get(0); return String.join(", ", p.subList(0, p.size() - 1)) + " and " + p.get(p.size() - 1); }
    static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    static String join(String a, String b) { return a.isEmpty() ? b : b.isEmpty() ? a : a + " · " + b; }
}
