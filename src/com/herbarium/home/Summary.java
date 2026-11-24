package com.herbarium.home;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the notifications Herbarium is holding into a short, calm summary (worked out on the phone; nothing is sent anywhere):
 *   headline: "Two messages from Anna, a missed call from Mom, and four quieter things."
 *   first:    calls, then messages grouped by person, then reminders and events
 *   quieter:  one line per app ("Gmail · 3 · Anna, CVS, Delta")
 *   resting:  how many came from apps Clausus is blocking (shown only as a number)
 */
final class Summary {
    final String headline;
    final List<Group> first = new ArrayList<>(), quieter = new ArrayList<>();
    final int resting;

    static final class Group {
        final String title; final List<Notes.Note> notes = new ArrayList<>(); final boolean person;
        Group(String t, boolean p) { title = t; person = p; }
        Notes.Note latest() { return notes.get(0); }
        /** "Anna · 2 messages", "Missed call · Mom", "Gmail · 3" */
        String line() {
            Notes.Note n = latest();
            if (n.call) return (n.title.isEmpty() ? "A call" : n.title) + (n.text.isEmpty() ? "" : " · " + n.text);
            if (person) return title + " · " + count(notes.size(), "message");
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
    }

    private Summary(String h, int r) { headline = h; resting = r; }

    static Summary of(Context c) {
        List<Notes.Note> all = Notes.all(c);
        Set<String> blocked = State.locked(c) ? State.packages(c) : new java.util.HashSet<>();
        Map<String, Group> first = new LinkedHashMap<>(), quiet = new LinkedHashMap<>();
        int resting = 0;
        for (Notes.Note n : all) {
            if (blocked.contains(n.pkg)) { resting++; continue; }
            if (n.call) { first.computeIfAbsent("call:" + n.key, k -> new Group("Call", false)).notes.add(n); continue; }
            if (n.message && n.person != null && !n.person.isEmpty()) {
                // a bare number that isn't a saved contact is usually automated (codes, offers): it goes with the quieter things
                boolean bare = n.person.replaceAll("[^0-9]", "").length() >= 5 && n.person.replaceAll("[0-9()+\\-. ]", "").isEmpty();
                if (bare && !Calls.inContacts(c, n.person)) { quiet.computeIfAbsent("unknown-texts", k -> new Group("Texts from unknown numbers", false)).notes.add(n); continue; }
                first.computeIfAbsent("msg:" + n.person, k -> new Group(n.person, true)).notes.add(n); continue;
            }
            if (n.important) { first.computeIfAbsent("imp:" + n.key, k -> new Group(n.app, false)).notes.add(n); continue; }
            quiet.computeIfAbsent(n.pkg, k -> new Group(n.app, false)).notes.add(n);
        }
        // the headline
        List<String> parts = new ArrayList<>();
        int calls = 0;
        for (Group g : first.values()) if (g.latest().call) calls++;
        if (calls > 0) parts.add(calls == 1 ? "a missed call" + from(firstCall(first)) : number(calls) + " calls");
        int people = 0;
        for (Group g : first.values()) if (g.person) { if (people < 2) parts.add(count(g.notes.size(), "message") + " from " + g.title); people++; }
        if (people > 2) parts.add("messages from " + number(people - 2) + " more " + (people - 2 == 1 ? "person" : "people"));
        // reminders are reminders (and events and alarms); anything else marked important is an alert (a bank, a delivery)
        int reminders = 0, alerts = 0;
        for (Group g : first.values()) if (!g.person && !g.latest().call) { if (reminder(g.latest())) reminders++; else alerts++; }
        if (reminders > 0) parts.add(count(reminders, "reminder"));
        if (alerts > 0) parts.add(count(alerts, "alert"));
        int q = 0; for (Group g : quiet.values()) q += g.notes.size();
        if (q > 0) parts.add(count(q, "quieter thing"));
        String h = parts.isEmpty() ? "Nothing new. All is quiet." : capital(and(parts)) + ".";
        Summary s = new Summary(h, resting);
        s.first.addAll(first.values()); s.quieter.addAll(quiet.values());
        return s;
    }

    /** Whether this phone has a voice to read aloud with (phones without Google's services may have none). */
    static boolean canSpeak(android.content.Context c) {
        return !c.getPackageManager().queryIntentServices(new android.content.Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isEmpty();
    }

    /** What "Read it to me" says. */
    String spoken() {
        StringBuilder b = new StringBuilder(headline);
        for (Group g : first) { b.append(" ").append(g.person ? g.title + " says: " + g.latest().text : g.line() + ". " + g.detail()); b.append("."); }
        for (Group g : quieter) b.append(" ").append(g.title).append(": ").append(g.detail()).append(".");
        return b.toString().replace("..", ".");
    }

    private static boolean reminder(Notes.Note n) {
        String c = n.category == null ? "" : n.category;
        return c.equals(android.app.Notification.CATEGORY_REMINDER) || c.equals(android.app.Notification.CATEGORY_EVENT) || c.equals(android.app.Notification.CATEGORY_ALARM);
    }

    private static String firstCall(Map<String, Group> m) { for (Group g : m.values()) if (g.latest().call) return g.latest().title; return ""; }
    private static String from(String who) { return who == null || who.isEmpty() ? "" : " from " + who; }
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
