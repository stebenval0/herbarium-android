package com.herbarium.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Who may ring the phone. Calls work as an allow-list:
 *   - anyone saved in the phone's contacts rings through,
 *   - plus the extra numbers on Herbarium's list (pharmacy, doctor's office…),
 *   - plus, if switched on, a number that calls twice within 3 minutes (so an urgent caller can get through),
 *   - everyone else is quietly declined and shown under "Recently declined", where they can be allowed in one tap.
 * Emergency services are never screened (Android doesn't ask Herbarium about them).
 */
final class Calls {
    private Calls() {}
    static final long REPEAT_MS = 3 * 60_000L;

    static boolean screening(Context c) { return Theme.prefs(c).getBoolean("screenCalls", true); }
    static boolean repeatRule(Context c) { return Theme.prefs(c).getBoolean("repeatCalls", true); }

    /** The last 10 digits, so "+1 (225) 555-0100" and "2255550100" match. */
    static String norm(String n) {
        if (n == null) return "";
        String d = n.replaceAll("[^0-9]", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    static JSONArray list(Context c, String key) {
        try { return new JSONArray(Theme.prefs(c).getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    static void save(Context c, String key, JSONArray a) { Theme.prefs(c).edit().putString(key, a.toString()).apply(); }

    static void allow(Context c, String name, String number) {
        String n = norm(number); if (n.isEmpty()) return;
        JSONArray a = list(c, "allowed");
        for (int i = 0; i < a.length(); i++) if (norm(a.optJSONObject(i).optString("number")).equals(n)) return;
        try { a.put(new JSONObject().put("name", name == null ? "" : name.trim()).put("number", number.trim())); } catch (Exception ignored) {}
        save(c, "allowed", a);
        // allowing someone from "Recently declined" takes them off that list
        JSONArray d = list(c, "declined"), keep = new JSONArray();
        for (int i = 0; i < d.length(); i++) if (!norm(d.optJSONObject(i).optString("number")).equals(n)) keep.put(d.optJSONObject(i));
        save(c, "declined", keep);
    }

    static void remove(Context c, String number) {
        String n = norm(number); JSONArray a = list(c, "allowed"), keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!norm(a.optJSONObject(i).optString("number")).equals(n)) keep.put(a.optJSONObject(i));
        save(c, "allowed", keep);
    }

    static boolean onList(Context c, String number) {
        String n = norm(number); if (n.isEmpty()) return false;
        JSONArray a = list(c, "allowed");
        for (int i = 0; i < a.length(); i++) if (norm(a.optJSONObject(i).optString("number")).equals(n)) return true;
        return false;
    }

    /** Whether the contacts can be read right now. */
    static boolean canTell(Context c) {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false;
        android.os.UserManager um = c.getSystemService(android.os.UserManager.class);
        if (um != null && !um.isUserUnlocked()) return false;
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, new String[]{ContactsContract.Contacts._ID}, null, null, null)) {
            // no contacts and nobody on Herbarium's own list (a new phone): nobody can be told apart, so everyone rings
            return cur != null && (cur.getCount() > 0 || list(c, "allowed").length() > 0);
        } catch (Exception e) { return false; }
    }

    static boolean inContacts(Context c, String number) {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED || number == null || number.isEmpty()) return false;
        Uri u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor cur = c.getContentResolver().query(u, new String[]{ContactsContract.PhoneLookup._ID}, null, null, null)) {
            return cur != null && cur.moveToFirst();
        } catch (Exception e) { return false; }
    }

    /** Did this number get declined in the last 3 minutes? (Then a second try rings.) */
    static boolean recentlyDeclined(Context c, String number) {
        String n = norm(number); long now = System.currentTimeMillis(); JSONArray d = list(c, "declined");
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.optJSONObject(i);
            if (norm(o.optString("number")).equals(n) && now - o.optLong("at") < REPEAT_MS) return true;
        }
        return false;
    }

    /** Was this number turned away by Herbarium around this time (so its call-log entry isn't a missed call)? */
    static boolean declinedAround(Context c, String number, long at) {
        String n = norm(number); JSONArray d = list(c, "declined");
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.optJSONObject(i);
            if (norm(o.optString("number")).equals(n) && Math.abs(at - o.optLong("at")) < 120_000) return true;
        }
        return false;
    }

    static void declined(Context c, String number) {
        JSONArray d = list(c, "declined"), out = new JSONArray();
        try { out.put(new JSONObject().put("number", number == null || number.isEmpty() ? "Hidden number" : number).put("at", System.currentTimeMillis())); } catch (Exception ignored) {}
        for (int i = 0; i < d.length() && out.length() < 30; i++) out.put(d.optJSONObject(i));
        save(c, "declined", out);
    }

    /** Within an hour of calling emergency services, every call rings: they may be calling back from any number. */
    /** Herbarium saw an emergency number dialled (some phones never log it). */
    static void emergencyDialled(Context c) { Theme.prefs(c).edit().putLong("emergencyAt", System.currentTimeMillis()).apply(); }

    static boolean afterEmergencyCall(Context c) {
        long at = Theme.prefs(c).getLong("emergencyAt", 0), ago = System.currentTimeMillis() - at;
        if (ago >= 0 && ago < 60 * 60_000L) return true;
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return false;
        android.telephony.TelephonyManager tm = c.getSystemService(android.telephony.TelephonyManager.class);
        long since = System.currentTimeMillis() - 60 * 60_000L;
        try (Cursor cur = c.getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI, new String[]{android.provider.CallLog.Calls.NUMBER},
                android.provider.CallLog.Calls.TYPE + " = " + android.provider.CallLog.Calls.OUTGOING_TYPE + " AND " + android.provider.CallLog.Calls.DATE + " > ?",
                new String[]{String.valueOf(since)}, null)) {
            while (cur != null && cur.moveToNext()) {
                String n = cur.getString(0);
                if (n == null) continue;
                try { if (tm != null && tm.isEmergencyNumber(n)) return true; } catch (Exception e) { if (n.equals("911") || n.equals("112")) return true; }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** The decision for one incoming call. */
    static boolean mayRing(Context c, String number) {
        if (!screening(c)) return true;
        if (!canTell(c)) return true;          // contacts can't be read (just restarted, not yet unlocked, no permission): never turn people away
        return decide(true, afterEmergencyCall(c), inContacts(c, number) || onList(c, number),
                repeatRule(c), number, recentlyDeclined(c, number));   // (a hidden number matches any hidden call turned away in the last 3 minutes)
    }

    /** The rule itself, with nothing looked up (tested in tests/). In order: screening off rings everyone; within an hour
     *  of an emergency call everyone rings; known people ring; anyone else, hidden numbers too (clinics and pharmacies
     *  often call that way), rings only on a second try within 3 minutes, if that rule is on. */
    static boolean decide(boolean screening, boolean afterEmergency, boolean known, boolean repeatRule, String number, boolean triedRecently) {
        if (!screening || afterEmergency || known) return true;
        return repeatRule && triedRecently;
    }
}
