package com.herbarium.home;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sorting texts, with nothing looked up (tested in tests/Checks.java).
 *   PEOPLE   contacts, numbers on the allowed list, and anyone the person has written to
 *   CODES    sign-in and verification codes
 *   UPDATES  deliveries, appointments, banks, pharmacies: short numbers and named senders saying something factual
 *   OFFERS   sales and marketing
 *   UNKNOWN  any other number
 * Normal mode shows all five, sorted. Caretaken mode shows only PEOPLE; the rest are kept for helpers, never shown.
 */
final class Sorter {
    private Sorter() {}

    static final int PEOPLE = 0, CODES = 1, UPDATES = 2, OFFERS = 3, UNKNOWN = 4;
    static final String[] NAMES = {"People", "Codes", "Updates", "Offers", "Unknown numbers"};

    private static final Pattern CODE = Pattern.compile(
            "(?i)\\b(code|passcode|verification|verify|one[- ]time|otp|pin|security code|sign[- ]in|log[- ]?in)\\b.*\\b\\d{4,8}\\b|\\b\\d{4,8}\\b.*\\b(is your|code|verification)\\b");
    private static final Pattern OFFER = Pattern.compile(
            "(?i)(\\d+\\s?% off|\\bsale\\b|\\bdeals?\\b|\\boffers?\\b|\\bpromo|\\bcoupon|\\bdiscount|\\bfree shipping|\\blimited time|\\bshop now|\\bclearance|\\bexclusive\\b|\\bwin\\b|\\bwinner\\b|\\bsweepstakes|\\breply stop\\b|\\btext stop\\b|\\bstop to (end|opt out|unsubscribe|quit|cancel)|\\bunsubscribe|\\bmsg ?& ?data rates)");
    private static final Pattern UPDATE = Pattern.compile(
            "(?i)\\b(order|shipped|delivered|delivery|out for delivery|tracking|package|appointment|reminder|confirm(ed|ation)?|balance|payment|deposit|withdrawal|transaction|account|statement|prescription|pharmacy|refill|ready for pickup|alert|due|bill|reservation|flight|gate|booking|scheduled)\\b");
    private static final Pattern LINK = Pattern.compile(
            "(?i)\\b((https?://|www\\.)[^\\s]+|[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(com|net|org|co|io|ly|me|info|biz|us|app|link|xyz|site|online|shop|top|gl|to|cc)(/[^\\s]*)?)");

    /** A short code (5–6 digits, as businesses use) or a name instead of a number. */
    static boolean business(String address) {
        if (address == null) return false;
        String a = address.trim();
        if (a.matches(".*[A-Za-z].*")) return true;                 // "AMAZON", "CVS-Pharmacy"
        String d = a.replaceAll("[^0-9]", "");
        return d.length() >= 3 && d.length() <= 6;
    }

    /**
     * Which pile a conversation belongs in.
     * @param known   the sender is in contacts or on the allowed list
     * @param wrote   the person has written to them before
     * @param address the sender's address (number or name)
     * @param text    the latest message from them
     */
    static int sort(boolean known, boolean wrote, String address, String text) {
        if (known) return PEOPLE;
        if (wrote && !business(address)) return PEOPLE;          // replying "YES" to a bank's short code doesn't make it a person
        String t = text == null ? "" : text;
        if (CODE.matcher(t).find()) return CODES;
        if (OFFER.matcher(t).find()) return OFFERS;
        if (business(address)) return UPDATES;                    // a business that isn't selling: a factual update
        if (UPDATE.matcher(t).find() && LINK.matcher(t).find()) return UPDATES;  // "your package… track at…"
        return UNKNOWN;
    }

    /** Is there a web link in this text? */
    static boolean hasLink(String text) { return text != null && LINK.matcher(text).find(); }

    /** Caretaken mode: links become plain words that can't be followed (the commonest way in for scams). */
    static String withoutLinks(String text) {
        if (text == null) return "";
        Matcher m = LINK.matcher(text);
        return m.replaceAll("(a link: ask a helper)");
    }

    /** The code in a code text, for a big easy-to-read line ("Your code: 482 913"). */
    static String code(String text) {
        if (text == null) return "";
        Matcher m = Pattern.compile("\\b(\\d{4,8})\\b").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** Lower-case, without punctuation: for comparing addresses written two ways. */
    static String key(String address) {
        if (address == null) return "";
        String a = address.trim();
        return a.matches(".*[A-Za-z].*") ? a.toLowerCase(Locale.ROOT) : Calls.norm(a);
    }
}
