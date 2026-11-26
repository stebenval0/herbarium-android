package com.herbarium.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Telephony;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The apps on the phone, sorted into Herbarium's chapters (the arches on the home screen). */
final class Apps {
    private Apps() {}

    static final class App {
        final String pkg, act, label;
        App(String p, String a, String l) { pkg = p; act = a; label = l; }
        String letter() { String s = label.replaceAll("[^A-Za-z0-9]", ""); return s.isEmpty() ? "·" : s.substring(0, 1).toUpperCase(); }
    }

    static final class Chapter {
        final String name; final List<App> apps = new ArrayList<>();
        Chapter(String n) { name = n; }
    }

    /** Plain names: what's in each chapter, said the ordinary way (the old garden names map onto these). */
    private static final Map<String, String> OLD_NAMES = new java.util.HashMap<>();
    static {
        String[][] pairs = {{"Day-to-day", "Every day"}, {"Banking", "Money"}, {"Voyages", "Travel"}, {"Library", "Reading & TV"}, {"Study", "Work & study"},
                {"Hearth", "House"}, {"Market", "Shopping"}, {"Wellbeing", "Health"}, {"Aux", "Other"}};
        for (String[] p : pairs) OLD_NAMES.put(p[0], p[1]);
    }
    static final String[] NAMES = {"Phone", "Every day", "Money", "Travel", "Reading & TV", "Work & study", "Camera", "House", "Shopping", "Health", "Other"};

    /** The layout from the first Herbarium phone; used when the phone has most of these apps. */
    private static final Object[][] MINE = {
            {"Phone", new String[]{"com.android.contacts", "com.discord", "com.google.android.gm", "com.google.android.apps.messaging", "com.microsoft.office.outlook", "com.android.dialer", "com.google.android.dialer", "com.google.android.contacts"}},
            {"Every day", new String[]{"com.google.android.apps.youtube.music", "com.thetileapp.tile", "com.costarastrology", "com.google.android.apps.maps", "com.anthropic.claude", "org.wikipedia"}},
            {"Money", new String[]{"com.konylabs.capitalone", "com.squareup.cash", "com.discoverfinancial.mobile", "gov.la.omv.lawallet", "com.venmo", "com.google.android.apps.walletnfcrel", "com.vzw.hss.myverizon"}},
            {"Travel", new String[]{"com.airbnb.android", "me.lyft.android", "com.transloc.microtransit"}},
            {"Reading & TV", new String[]{"net.activote.app", "com.amazon.kindle", "com.overdrive.mobile.android.libby", "com.nytimes.android", "com.google.android.apps.nbu.files"}},
            {"Work & study", new String[]{"com.google.android.calendar", "com.desmos.calculator", "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs", "com.dropbox.android", "com.google.android.keep", "md.obsidian", "com.google.android.apps.docs.editors.sheets", "com.microsoft.teams", "org.readera", "com.ratta.supernote", "com.azure.authenticator", "us.zoom.videomeetings"}},
            {"Camera", new String[]{"com.photo.android.camera", "com.google.android.apps.photos", "com.google.ar.lens"}},
            {"House", new String[]{"com.zehnder.entergy", "com.amazon.storm.lightning.client.aosp", "org.kde.kdeconnect_tp", "com.life360.android.safetymapd", "com.google.android.apps.tycho"}},
            {"Shopping", new String[]{"com.amazon.mShop.android.shopping", "com.dominospizza", "com.yum.pizzahut", "com.walmart.android"}},
            {"Health", new String[]{"com.cvs.launchers.cvs", "epic.mychart.android"}},
            {"Other", new String[]{"com.google.android.calculator", "com.google.android.deskclock", "com.android.vending", "com.android.settings", "com.google.android.apps.accessibility.voiceaccess", "com.yahoo.mobile.client.android.mail"}},
    };
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList("com.esc.browser.dev", "org.fdroid.fdroid", "com.google.android.apps.adm",
            "org.mozilla.firefox", "com.google.android.googlequicksearchbox", "com.google.android.apps.chromecast.app", "com.google.android.videos",
            "com.yamaha.av.musiccastcontroller", "com.nimblerx", "com.podcast.podcasts", "com.shift.devicemanager", "com.herbarium.phone", "com.herbarium.clausus"));

    static List<App> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // when Herbarium is the phone's texting or calling app, its own Messages and Phone stand in for the old ones
        Set<String> replaced = new HashSet<>();
        if (Texts.isDefault(c)) {
            out.add(new App(c.getPackageName(), MessagesActivity.class.getName(), "Messages"));
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:1")), 0)) replaced.add(r.activityInfo.packageName);
        }
        if (c.getPackageName().equals(defaultDialer(c))) {
            out.add(new App(c.getPackageName(), DialActivity.class.getName(), "Phone"));
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_DIAL), 0)) replaced.add(r.activityInfo.packageName);
        }
        out.add(new App(c.getPackageName(), TvActivity.class.getName(), "Television"));   // Bluetooth: works when the Wi-Fi doesn't
        replaced.remove(c.getPackageName());
        replaced.remove("com.google.android.contacts"); replaced.remove("com.android.contacts");   // Contacts also dials; keep it
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || replaced.contains(pkg) || !seen.add(pkg)) continue;
            out.add(new App(pkg, r.activityInfo.name, String.valueOf(r.loadLabel(pm))));
        }
        Collator col = Collator.getInstance();
        out.sort((a, b) -> col.compare(a.label, b.label));
        return out;
    }

    /** Chapters: the person's own layout if saved, else the first phone's layout, else sorted by kind of app. */
    static List<Chapter> chapters(Context c, List<App> apps) {
        Map<String, App> byPkg = new HashMap<>();
        for (App a : apps) byPkg.put(a.pkg, a);
        Map<String, String> place = new HashMap<>();   // package → chapter
        String saved = Theme.prefs(c).getString("chapters", "");
        if (!saved.isEmpty()) {
            try {
                JSONObject o = new JSONObject(saved);
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String ch = it.next(); JSONArray arr = o.getJSONArray(ch);
                    String plain = OLD_NAMES.getOrDefault(ch, ch);           // layouts saved before the plain names
                    for (int i = 0; i < arr.length(); i++) place.put(arr.getString(i), plain);
                }
            } catch (Exception ignored) {}
        } else {
            int known = 0;
            for (Object[] ch : MINE) for (String p : (String[]) ch[1]) if (byPkg.containsKey(p)) known++;
            if (known >= 10) for (Object[] ch : MINE) for (String p : (String[]) ch[1]) place.put(p, (String) ch[0]);
        }
        Map<String, Chapter> chs = new LinkedHashMap<>();
        for (String n : NAMES) chs.put(n, new Chapter(n));
        String dialer = defaultDialer(c), sms = Telephony.Sms.getDefaultSmsPackage(c);
        for (App a : apps) {
            if (HIDDEN.contains(a.pkg) && !place.containsKey(a.pkg)) continue;
            String ch = TvActivity.class.getName().equals(a.act) ? "Reading & TV" : place.get(a.pkg);
            if (ch == null) ch = guess(c, a, dialer, sms);
            Chapter target = chs.get(ch);
            if (target == null) { target = new Chapter(ch); chs.put(ch, target); }
            target.apps.add(a);
        }
        List<Chapter> out = new ArrayList<>();
        for (Chapter ch : chs.values()) if (!ch.apps.isEmpty()) out.add(ch);
        return out;
    }

    /** The phone's own apps for calling, messages and contacts (Caretaken mode shows only these). */
    static String dialer(Context c) { String d = defaultDialer(c); return d == null ? "" : d; }
    static String messages(Context c) {
        if (Texts.isDefault(c)) return c.getPackageName();
        String s = android.provider.Telephony.Sms.getDefaultSmsPackage(c); return s == null ? "" : s;
    }
    static String contacts(Context c) {
        android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS), 0);
        return r == null || r.activityInfo == null ? "" : r.activityInfo.packageName;
    }

    private static String defaultDialer(Context c) {
        try { android.telecom.TelecomManager tm = c.getSystemService(android.telecom.TelecomManager.class); return tm == null ? null : tm.getDefaultDialerPackage(); }
        catch (Exception e) { return null; }
    }

    /** Where an app goes when nobody has placed it: by what kind of app it says it is. */
    private static String guess(Context c, App a, String dialer, String sms) {
        String p = a.pkg;
        if (p.equals(dialer) || p.equals(sms) || p.contains("contacts") || p.contains("dialer") || p.contains("messaging") || p.endsWith(".mms")) return "Phone";
        if (p.contains("camera") || p.contains("gallery") || p.contains("photos")) return "Camera";
        if (p.contains("bank") || p.contains("wallet") || p.contains("pay")) return "Money";
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(p, 0);
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_SOCIAL: return "Phone";
                case ApplicationInfo.CATEGORY_MAPS: return "Travel";
                case ApplicationInfo.CATEGORY_NEWS: case ApplicationInfo.CATEGORY_AUDIO: case ApplicationInfo.CATEGORY_VIDEO: return "Reading & TV";
                case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Work & study";
                case ApplicationInfo.CATEGORY_IMAGE: return "Camera";
                case ApplicationInfo.CATEGORY_ACCESSIBILITY: return "Health";
                default: break;
            }
        } catch (Exception ignored) {}
        return "Other";
    }

    static void launch(Context c, App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(a.pkg, a.act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { c.startActivity(i); }
        catch (Exception e) {
            Intent l = c.getPackageManager().getLaunchIntentForPackage(a.pkg);
            if (l != null) c.startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
