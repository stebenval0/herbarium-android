package com.herbarium.home;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Updates from the download link: once a day Herbarium reads latest.json
 * ({"code": 3, "version": "2.1", "apk": "https://…/Herbarium-2.1.apk", "sha256": "…", "notes": "…"}),
 * and if it's newer, the Accessibility page offers "Update Herbarium". The download must match its fingerprint.
 */
final class Updater {
    private Updater() {}
    static final String DEFAULT_URL = "https://github.com/stebenval0/herbarium-android/releases/latest/download/latest.json";

    /** While Herbarium installs its own update, Clausus lets Android's installer screens through (see Guard). */
    static void openWindow(Context c) { Guard.allow(c, "update"); }

    static String url(Context c) { return Theme.prefs(c).getString("updateUrl", DEFAULT_URL); }

    static long myCode(Context c) {
        try { PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0); return p.getLongVersionCode(); } catch (Exception e) { return 0; }
    }

    /** The newer release on offer, or null. Checks at most once a day unless forced (background thread). */
    static JSONObject available(Context c, boolean force) {
        try {
            long last = Theme.prefs(c).getLong("updateChecked", 0);
            if (force || System.currentTimeMillis() - last > 86_400_000L) {
                JSONObject j = new JSONObject(new String(fetch(url(c)), "UTF-8"));
                Theme.prefs(c).edit().putString("updateInfo", j.toString()).putLong("updateChecked", System.currentTimeMillis()).apply();
            }
            JSONObject j = new JSONObject(Theme.prefs(c).getString("updateInfo", "{}"));
            return j.optLong("code") > myCode(c) && j.optString("apk").startsWith("https://") ? j : null;
        } catch (Exception e) { return null; }
    }

    /** Download, check the fingerprint, and hand it to Android's installer. Returns "" or what went wrong. */
    static String install(Context c, JSONObject rel) {
        try {
            byte[] apk = fetch(rel.getString("apk"));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(); for (byte b : md.digest(apk)) hex.append(String.format("%02x", b));
            if (!hex.toString().equalsIgnoreCase(rel.optString("sha256"))) return "the download didn't match its fingerprint";
            openWindow(c);
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (OutputStream o = s.openWrite("herbarium.apk", 0, apk.length)) { o.write(apk); s.fsync(o); }
                Intent done = new Intent(c, UpdateResult.class);
                PendingIntent p = PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                s.commit(p.getIntentSender());
            }
            return "";
        } catch (Exception e) { return String.valueOf(e.getMessage()); }
    }

    /**
     * Reinstall Herbarium over itself through Android's installer (the same version). Android 13+ restricts some switches
     * (accessibility, notification access) for apps that arrived as a downloaded file; an app installed through the
     * installer isn't treated that way. Android asks the person to confirm once. Returns "" or what went wrong.
     */
    static String reinstallSelf(Context c) {
        try {
            File apk = new File(c.getApplicationInfo().sourceDir);
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            if (android.os.Build.VERSION.SDK_INT >= 33) sp.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE);
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id); InputStream in = new FileInputStream(apk)) {
                try (OutputStream o = s.openWrite("herbarium.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n); s.fsync(o);
                }
                Intent done = new Intent(c, UpdateResult.class);
                PendingIntent p = PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Guard.allow(c, "update");
                s.commit(p.getIntentSender());
            }
            Theme.prefs(c).edit().putBoolean("reinstalledSelf", true).apply();
            return "";
        } catch (Exception e) { return String.valueOf(e.getMessage()); }
    }

    private static byte[] fetch(String url) throws Exception {
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false); h.setConnectTimeout(10000); h.setReadTimeout(60000); h.setRequestProperty("User-Agent", "Herbarium/2");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) { url = h.getHeaderField("Location"); h.disconnect(); continue; }   // GitHub sends release files via a redirect
            if (code != 200) throw new Exception("the download link said " + code);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) { byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
            return b.toByteArray();
        }
        throw new Exception("too many redirects");
    }

    /** Android tells us how the install went; if it needs the person's OK, show its screen. */
    public static class UpdateResult extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                openWindow(c);
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
    }
}
