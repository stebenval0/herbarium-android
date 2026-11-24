package com.herbarium.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * What's blocked and for how long. The time left counts down only while the phone is on
 * (like the computer), so changing the clock or rebooting doesn't shorten a block.
 * A block from the computer can make the local one longer or bigger, never shorter or smaller.
 */
final class State {
    private State() {}
    private static volatile boolean moved;

    private static volatile SharedPreferences cachedPrefs;
    /** Clausus's state (asked for on every app switch and web lookup, so the handle is kept). */
    static SharedPreferences prefs(Context c) {
        SharedPreferences sp = cachedPrefs;
        if (sp != null) return sp;
        Context dp = Theme.store(c);
        if (!moved) { moved = true; try { dp.moveSharedPreferencesFrom(c.getApplicationContext(), "clausus"); } catch (Exception ignored) {} }
        return cachedPrefs = dp.getSharedPreferences("clausus", Context.MODE_PRIVATE);
    }

    private static int bootCount(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
    }

    /** Milliseconds left in the current block (0 = nothing blocked). */
    static synchronized long remaining(Context c) {
        SharedPreferences p = prefs(c);
        long rem = p.getLong("remaining", 0);
        if (rem <= 0) return 0;
        long now = SystemClock.elapsedRealtime();
        long last = p.getLong("lastElapsed", now);
        int boot = bootCount(c);
        long delta = (p.getInt("boot", boot) == boot && now >= last) ? now - last : 0;
        rem = Math.max(0, rem - delta);
        // This runs for every app switch and every web lookup, so it only saves the countdown every few seconds
        // (or when the block ends): until then the stored time and its timestamp still give the same answer.
        boolean sameRun = p.getInt("boot", -1) == boot && now >= last && p.contains("lastElapsed");
        if (rem > 0 && sameRun && delta < 5_000) return rem;
        SharedPreferences.Editor e = p.edit().putLong("remaining", rem).putLong("lastElapsed", now).putInt("boot", boot);
        if (rem == 0 && !p.getBoolean("always", false)) e.remove("domains").remove("packages").remove("lists");
        e.apply();
        if (rem == 0) Lists.invalidate();
        return rem;
    }

    /** On until the computer switches it off (no end time). */
    static boolean always(Context c) { return prefs(c).getBoolean("always", false); }

    static boolean locked(Context c) { return always(c) || remaining(c) > 0; }

    static Set<String> domains(Context c) { return new HashSet<>(prefs(c).getStringSet("domains", new HashSet<>())); }
    static Set<String> packages(Context c) { return new HashSet<>(prefs(c).getStringSet("packages", new HashSet<>())); }
    static String lists(Context c) { return prefs(c).getString("lists", ""); }
    /** Allow-list mode: only these apps may open (empty = ordinary block lists). */
    static Set<String> allowApps(Context c) { return locked(c) ? new HashSet<>(prefs(c).getStringSet("allowApps", new HashSet<>())) : new HashSet<>(); }

    /** May this app open right now? */
    static boolean appAllowed(Context c, String pkg) {
        if (!locked(c)) return true;
        if (packages(c).contains(pkg)) return false;
        Set<String> allow = allowApps(c);
        return allow.isEmpty() || allow.contains(pkg) || essential(c, pkg);
    }

    /** What always works, even in allow-list mode: calls (and emergency calls), the keyboard, permission prompts, Herbarium. */
    static boolean essential(Context c, String pkg) {
        if (pkg.equals(c.getPackageName()) || pkg.equals("android") || pkg.startsWith("com.android.systemui") || pkg.equals("com.android.phone")
                || pkg.equals("com.android.server.telecom") || pkg.contains("permissioncontroller") || pkg.equals("com.android.emergency")
                || pkg.equals("com.google.android.apps.safetyhub")) return true;
        try {
            android.telecom.TelecomManager tm = c.getSystemService(android.telecom.TelecomManager.class);
            if (tm != null && pkg.equals(tm.getDefaultDialerPackage())) return true;
        } catch (Exception ignored) {}
        String ime = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        return ime != null && ime.startsWith(pkg + "/");
    }

    /** Take in what the computer says. The paired computer is in charge: on, off, and what's on the lists. */
    static synchronized void apply(Context c, JSONObject s) throws Exception {
        boolean locked = s.optBoolean("locked"), always = s.optBoolean("always");
        long server = locked && !always ? Math.max(0, (s.optLong("until") - s.optLong("now")) * 1000L) : 0;
        Set<String> doms = new HashSet<>(), pkgs = new HashSet<>(), allow = new HashSet<>();
        if (locked) {
            JSONArray d = s.optJSONArray("domains"), k = s.optJSONArray("packages");
            for (int i = 0; d != null && i < d.length(); i++) doms.add(d.getString(i).toLowerCase());
            for (int i = 0; k != null && i < k.length(); i++) pkgs.add(k.getString(i));
        }
        Set<String> allowApps = new HashSet<>();
        JSONArray aa = locked ? s.optJSONArray("allow_apps") : null;
        for (int i = 0; aa != null && i < aa.length(); i++) allowApps.add(aa.getString(i));
        JSONArray a = s.optJSONArray("allow");
        for (int i = 0; a != null && i < a.length(); i++) allow.add(a.getString(i).toLowerCase());
        JSONObject safe = s.optJSONObject("safe_search");
        prefs(c).edit()
                .putBoolean("always", locked && always)
                .putLong("remaining", server)
                .putLong("lastElapsed", SystemClock.elapsedRealtime()).putInt("boot", bootCount(c))
                .putStringSet("domains", doms).putStringSet("packages", pkgs).putStringSet("allow", allow).putStringSet("allowApps", allowApps)
                .putString("safe", locked && safe != null ? safe.toString() : "{}")
                .putString("feedWant", locked ? s.optString("feed", "") : "")
                .putString("lists", locked ? TextUtils.join(", ", toList(s.optJSONArray("lists"))) : "")
                .apply();
        Lists.invalidate();
    }

    private static java.util.List<String> toList(JSONArray a) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /** Is this name (or a parent of it, e.g. m.reddit.com → reddit.com) blocked right now? Always-allowed names win. */
    static boolean blockedDomain(Context c, String name) {
        if (!locked(c)) return false;
        name = name.toLowerCase();
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        Lists l = Lists.get(c);
        for (String n = name; ; ) { if (l.allow.contains(n)) return false; int dot = n.indexOf('.'); if (dot < 0) break; n = n.substring(dot + 1); }
        for (String n = name; ; ) {
            if (l.domains.contains(n) || l.feed.contains(n)) return true;
            int dot = n.indexOf('.'); if (dot < 0) break; n = n.substring(dot + 1);
        }
        return false;
    }

    // ---------------------------------------------------------------- pairing and health
    static String host(Context c) { return prefs(c).getString("host", ""); }
    static int port(Context c) { return prefs(c).getInt("port", 7461); }
    static String token(Context c) { return prefs(c).getString("token", ""); }
    static long lastHeard(Context c) { return prefs(c).getLong("heard", 0); }

    static ComponentName guard(Context c) { return new ComponentName(c, Guard.class); }

    static boolean guardOn(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(guard(c).flattenToString());
    }

    static boolean adminOn(Context c) {
        android.app.admin.DevicePolicyManager dpm = c.getSystemService(android.app.admin.DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(new ComponentName(c, Admin.class));
    }

    static String fmt(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        if (h >= 24) return (h / 24) + "d " + (h % 24) + "h";
        return h > 0 ? h + "h " + String.format(java.util.Locale.US, "%02d", m) + "m" : Math.max(1, m + (s % 60 > 0 ? 1 : 0)) + "m";
    }
}
