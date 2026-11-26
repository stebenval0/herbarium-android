package com.herbarium.home;

/**
 * Plain checks of the rules that matter most, run on the computer by build.sh (no phone needed).
 * A failure stops the build. Add a line whenever a rule changes.
 */
public class Checks {
    private static int failed, passed;

    private static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.err.println("FAILED: " + what); }
    }
    private static void same(String what, Object got, Object want) {
        check(what + " (got " + got + ", want " + want + ")", want == null ? got == null : want.equals(got));
    }

    public static void main(String[] a) {
        // ---- who may ring
        check("screening off: anyone rings", Calls.decide(false, false, false, false, "2255550100", false));
        check("a contact rings", Calls.decide(true, false, true, true, "2255550100", false));
        check("a stranger is turned away", !Calls.decide(true, false, false, true, "2255550100", false));
        check("a stranger's second try within 3 min rings", Calls.decide(true, false, false, true, "2255550100", true));
        check("...but not with the second-try rule off", !Calls.decide(true, false, false, false, "2255550100", true));
        check("a hidden number rings on a second try", Calls.decide(true, false, false, true, "", true));
        check("a hidden number's first try doesn't ring", !Calls.decide(true, false, false, true, "", false));
        check("after an emergency call, a stranger rings", Calls.decide(true, true, false, false, "5550000", false));
        check("after an emergency call, a hidden number rings", Calls.decide(true, true, false, false, "", false));

        // ---- matching numbers
        same("norm strips +1 and punctuation", Calls.norm("+1 (225) 555-0100"), "2255550100");
        same("norm keeps short numbers", Calls.norm("911"), "911");
        same("norm of nothing", Calls.norm(null), "");
        check("the same person, written two ways", Calls.norm("225.555.0100").equals(Calls.norm("+12255550100")));

        // ---- words people read
        same("numbers in words", Summary.number(7), "seven");
        same("teens", Summary.number(11), "eleven");
        same("tens", Summary.number(40), "forty");
        same("compound", Summary.number(23), "twenty-three");
        same("too many", Summary.number(140), "many");
        same("one reminder", Summary.count(1, "reminder"), "a reminder");
        same("an alert", Summary.count(1, "alert"), "an alert");
        same("several", Summary.count(3, "message"), "three messages");

        // ---- notice cards: the heading names the app, so the line must not repeat it
        Notes.Note cal = new Notes.Note(); cal.app = "Calendar"; cal.pkg = "cal"; cal.title = "Leave the House"; cal.text = "From yesterday";
        cal.category = ""; cal.person = "";
        Summary.Group one = new Summary.Group("Calendar", false); one.notes.add(cal);
        same("card line is the notice's title", one.cardLine(), "Leave the House");
        same("card detail is its text", one.cardDetail(), "From yesterday");
        Notes.Note bare = new Notes.Note(); bare.app = "Clock"; bare.pkg = "clk"; bare.title = "Clock"; bare.text = "Alarm at 7:00";
        bare.category = ""; bare.person = "";
        Summary.Group two = new Summary.Group("Clock", false); two.notes.add(bare);
        same("title that is just the app's name gives way to the text", two.cardLine(), "Alarm at 7:00");
        same("...and the text isn't shown twice", two.cardDetail(), "");
        two.notes.add(bare);
        same("several notices from one app", two.cardLine(), "Two notices");
        // ---- the headline names every source, one line per kind
        java.util.List<Notes.Note> pile = new java.util.ArrayList<>();
        pile.add(note("k1", "dialer", "Phone", "Mom", "Missed call", "", false, true, true, android.app.Notification.CATEGORY_MISSED_CALL));
        pile.add(note("k2", "sms", "Messages", "Anna", "Coming at six?", "Anna", true, false, true, ""));
        pile.add(note("k3", "sms", "Messages", "Anna", "Bring bread", "Anna", true, false, true, ""));
        pile.add(note("k4", "cal", "Calendar", "Dentist at 3", "Dr Lee", "", false, false, true, android.app.Notification.CATEGORY_REMINDER));
        pile.add(note("k5", "gm", "Gmail", "Delta", "Your trip", "", false, false, false, ""));
        pile.add(note("k6", "gm", "Gmail", "CVS", "Ready", "", false, false, false, ""));
        pile.add(note("k7", "sms", "Messages", "22395", "Code 4821", "22395", true, false, true, ""));
        pile.add(note("k8", "rd", "Reddit", "r/all", "Hot", "", false, false, false, ""));
        Summary sum = Summary.build(pile, new java.util.HashSet<>(java.util.Arrays.asList("rd")), w -> false);
        same("headline", sum.headline, "Missed call: Mom\nMessages: Anna (2)\nReminder: Dentist at 3\nCan wait: Gmail (2), Unknown numbers");
        check("blocked apps only counted", sum.resting == 1);
        same("nothing", Summary.build(new java.util.ArrayList<>(), new java.util.HashSet<>(), w -> false).headline, "Nothing new.");
        check("spoken has no separators", !sum.spoken().contains("·") && sum.spoken().startsWith("Mom called."));
        java.util.List<Notes.Note> many = new java.util.ArrayList<>();
        for (String w : new String[]{"Ann", "Ben", "Cy", "Di", "Ed"}) many.add(note("m" + w, "sms", "Messages", w, "hi", w, true, false, true, ""));
        same("long lists are capped", Summary.build(many, new java.util.HashSet<>(), w -> true).headline, "Messages: Ann, Ben, Cy +2");

        same("first name for the call-back button", Missed.first("Ruth Ellis"), "Ruth");
        same("a number stays whole", Missed.first("(225) 555-0100"), "(225) 555-0100");

        // ---- voice typing
        same("I gets its capital", Keys.tidy("i think i'm late"), "I think I'm late");
        same("words with i inside are left alone", Keys.tidy("it is raining in iowa"), "it is raining in iowa");

        // ---- sorting texts
        check("a contact is people, whatever they say", Sorter.sort(true, false, "2255550100", "your code is 123456") == Sorter.PEOPLE);
        check("someone written to is people", Sorter.sort(false, true, "2255550100", "on my way") == Sorter.PEOPLE);
        check("a bank's short code you replied to is not people", Sorter.sort(false, true, "227898", "Hi, it's Capital One. Did you just try to make this purchase?") != Sorter.PEOPLE);
        check("a sign-in code", Sorter.sort(false, false, "22395", "Your Chase verification code is 482913. Don't share it.") == Sorter.CODES);
        check("a code, number first", Sorter.sort(false, false, "87892", "482913 is your Google verification code") == Sorter.CODES);
        check("an offer with STOP", Sorter.sort(false, false, "30458", "FLASH SALE! 40% off everything today. Reply STOP to opt out") == Sorter.OFFERS);
        check("a pharmacy update", Sorter.sort(false, false, "28767", "CVS Pharmacy: your prescription is ready for pickup.") == Sorter.UPDATES);
        check("a named sender", Sorter.sort(false, false, "AMAZON", "Delivered: your package was left at the front door.") == Sorter.UPDATES);
        check("a stranger's plain text", Sorter.sort(false, false, "5045550123", "hey is this still your number?") == Sorter.UNKNOWN);
        check("a stranger with a delivery link is an update", Sorter.sort(false, false, "5045550123", "USPS: your package is held, update at usps-track.co/x1") == Sorter.UPDATES);
        check("short code", Sorter.business("28767") && !Sorter.business("2255550100") && Sorter.business("CVS"));
        same("links come out (Caretaken)", Sorter.withoutLinks("Pay now at https://bit.ly/3xYz or you lose it"), "Pay now at (a link: ask a helper) or you lose it");
        same("bare domains too", Sorter.withoutLinks("visit amazon-refund.com/claim today"), "visit (a link: ask a helper) today");
        check("ordinary words are not links", !Sorter.hasLink("I'll be there at 5. See you soon."));
        check("a decimal is not a link", !Sorter.hasLink("It was 3.50 for the bread"));
        same("the code itself", Sorter.code("Your code is 482913."), "482913");
        same("codes are spaced for reading", MessagesActivity.spaced("482913"), "482 913");

        // ---- Home's glide: never past the page, never backwards, always lands on it
        boolean ok = true;
        for (float d : new float[]{60, 360, 720}) for (int k = 0; k <= 100; k++) {
            float v = k * 0.08f;                                     // 0 to 8 px/ms: a still release up to a very hard flick
            Motion.Glide g = Motion.plan(d, v, 720);
            float last = 0;
            for (int i = 0; i <= 200; i++) {
                float x = Motion.travelled(g, d, i / 200f, null);
                if (x > d + 0.01f || x < last - 0.01f) ok = false;   // past the page, or moving backwards
                last = x;
            }
            if (Math.abs(Motion.travelled(g, d, 1f, null) - d) > 0.01f) ok = false;   // must land exactly on the page
            if (g.ms < 320 || g.ms > 650) ok = false;
        }
        check("the glide never passes the page, never goes back, always lands", ok);
        check("a flick leaves at the finger's speed (when it can stop in time)", Math.abs(Motion.plan(720, 2f, 720).speed - 2f) < 0.01f);
        check("a slow release uses the calm ease", Motion.plan(720, 0.1f, 720).speed == 0);

        System.out.println("checks: " + passed + " passed" + (failed > 0 ? ", " + failed + " FAILED" : ""));
        if (failed > 0) System.exit(1);
    }
    static Notes.Note note(String key, String pkg, String app, String title, String text, String person, boolean msg, boolean call, boolean imp, String cat) {
        Notes.Note n = new Notes.Note(); n.key = key; n.pkg = pkg; n.app = app; n.title = title; n.text = text; n.person = person;
        n.message = msg; n.call = call; n.important = imp; n.category = cat; return n;
    }
}
