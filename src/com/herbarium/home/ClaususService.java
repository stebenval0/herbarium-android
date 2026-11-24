package com.herbarium.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Always running. Every 20 seconds it:
 *   - asks the computer what's blocked (over Wi-Fi, or over the USB cable via adb reverse),
 *   - keeps the website filter on while a block runs,
 *   - switches the app guard back on if someone turned it off (it has WRITE_SECURE_SETTINGS from adb),
 *   - keeps Private DNS off during a block so lookups can't go around the filter.
 */
public class ClaususService extends Service {
    static final String TAG = "Clausus";
    static final String CHANGED = "com.herbarium.home.CLAUSUS_CHANGED";
    private static final int NOTE = 1;
    private volatile boolean running, checkNow;
    private Thread loop;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, ClaususService.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("clausus", "Clausus", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTE, note("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            loop = new Thread(this::run, "clausus-loop");
            loop.start();
        } else if (loop != null) {
            checkNow = true;
            loop.interrupt();   // "check now"
        }
        return START_STICKY;
    }

    @Override public void onDestroy() { running = false; if (loop != null) loop.interrupt(); super.onDestroy(); }

    private void run() {
        while (running) {
            try { cycle(); } catch (Throwable t) { Log.w(TAG, "cycle", t); }
            try { Thread.sleep(20_000); } catch (InterruptedException ignored) {}
        }
    }

    private void cycle() {
        checkIn();
        boolean locked = State.locked(this);
        enforce(locked);
        long rem = State.remaining(this);
        String text = State.always(this) ? "🔒 On: " + State.lists(this) + " blocked until you switch it off on your computer"
                : locked ? "🔒 " + State.lists(this) + " · " + State.fmt(rem) + " left"
                             : (State.token(this).isEmpty() ? "Not paired with your computer yet" : "Nothing is blocked");
        if (!text.equals(shown)) { shown = text; getSystemService(NotificationManager.class).notify(NOTE, note(text)); }
    }
    private String shown;

    // ---------------------------------------------------------------- talking to the computer
    private void checkIn() {
        String token = State.token(this);
        if (token.isEmpty()) return;
        String[] hosts = State.host(this).isEmpty() ? new String[]{"127.0.0.1"} : new String[]{State.host(this), "127.0.0.1"};
        if (!State.host(this).isEmpty() || !Relay.configured(this)) for (String h : hosts) {
            try {
                JSONObject me = new JSONObject();
                me.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                me.put("until", System.currentTimeMillis() / 1000 + State.remaining(this) / 1000);
                me.put("on", State.always(this));
                me.put("protected", State.guardOn(this) && State.adminOn(this));
                JSONObject s = post("http://" + h + ":" + State.port(this) + "/v1/checkin", token, me.toString());
                if (s == null) continue;
                boolean wasOn = State.locked(this); String wasPk = State.packages(this).toString() + State.allowApps(this);
                State.apply(this, s);
                if (wasOn != State.locked(this) || !wasPk.equals(State.packages(this).toString() + State.allowApps(this)))
                    sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));   // the home screen hides or shows blocked apps
                State.prefs(this).edit().putLong("heard", System.currentTimeMillis()).putString("heardVia", h).apply();
                syncFeed("http://" + h + ":" + State.port(this), token);
                return;
            } catch (Exception e) {
                Log.d(TAG, "no answer from " + h + ": " + e);
            }
        }
        if (Relay.configured(this)) viaRelay();
    }

    /** The keeper's computer is somewhere else: read its signed instructions from the relay, and check in there. */
    private long relayAt;
    private void viaRelay() {
        // the relay is GitHub: once a minute is plenty (and kinder to the battery and the data plan) unless asked to check now
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - relayAt < 55_000 && !checkNow) return;
        relayAt = now; checkNow = false;
        try {
            JSONObject s = Relay.state(this);
            if (s != null) {
                // ignore stale posts (the computer refreshes every 10 minutes); a block stays as it was until a fresh word arrives
                long age = System.currentTimeMillis() / 1000 - s.optLong("now");
                if (age < 3600) {
                    boolean wasOn = State.locked(this); String wasPk = State.packages(this).toString() + State.allowApps(this);
                    State.apply(this, s);
                    if (wasOn != State.locked(this) || !wasPk.equals(State.packages(this).toString() + State.allowApps(this)))
                        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
                    State.prefs(this).edit().putLong("heard", System.currentTimeMillis()).putString("heardVia", "relay").apply();
                }
            }
            String want = State.prefs(this).getString("feedWant", ""), have = State.prefs(this).getString("feedHave", "");
            if (!want.isEmpty() && !want.equals(have)) {
                byte[] f = Relay.feed(this);
                if (f != null) {
                    java.io.File tmp = new java.io.File(Theme.store(this).getFilesDir(), "feed.tmp");
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) { o.write(f); }
                    if (tmp.renameTo(Lists.feedFile(this))) { State.prefs(this).edit().putString("feedHave", want).apply(); Lists.invalidate(); }
                }
            }
            long last = State.prefs(this).getLong("relayCheckin", 0);
            if (System.currentTimeMillis() - last > 5 * 60_000L) {
                JSONObject me = new JSONObject();
                me.put("model", Build.MANUFACTURER + " " + Build.MODEL);
                me.put("until", System.currentTimeMillis() / 1000 + State.remaining(this) / 1000);
                me.put("protected", State.guardOn(this) && State.adminOn(this));
                me.put("seen", System.currentTimeMillis() / 1000);
                Relay.checkIn(this, me);
                State.prefs(this).edit().putLong("relayCheckin", System.currentTimeMillis()).apply();
            }
        } catch (Exception e) { Log.d(TAG, "relay: " + e); }
    }

    /** The big downloaded lists (e.g. adult sites) come separately, and only when they change. */
    private void syncFeed(String base, String token) {
        String want = State.prefs(this).getString("feedWant", ""), have = State.prefs(this).getString("feedHave", "");
        if (want.isEmpty() || want.equals(have)) return;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/v1/feed").openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(30000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            if (c.getResponseCode() != 200) { c.disconnect(); return; }
            java.io.File tmp = new java.io.File(Theme.store(this).getFilesDir(), "feed.tmp");
            try (InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { c.disconnect(); }
            if (tmp.renameTo(Lists.feedFile(this))) {
                State.prefs(this).edit().putString("feedHave", want).apply();
                Lists.invalidate();
            }
        } catch (Exception e) { Log.d(TAG, "feed: " + e); }
    }

    private static JSONObject post(String url, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(4000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        if (c.getResponseCode() != 200) { c.disconnect(); return null; }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        } finally { c.disconnect(); }
    }

    // ---------------------------------------------------------------- keeping the protection on
    private void enforce(boolean locked) {
        ContentResolver cr = getContentResolver();
        // the app guard: always on (it does nothing when no block is running)
        if (!State.guardOn(this)) {
            try {
                String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                String me = State.guard(this).flattenToString();
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        cur == null || cur.isEmpty() ? me : cur + ":" + me);
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            } catch (SecurityException e) { Log.w(TAG, "can't re-enable the guard (needs pairing over adb)"); }
        }
        if (locked) {
            // Private DNS would let lookups skip the filter; remember the setting and switch it off for the block.
            try {
                String mode = Settings.Global.getString(cr, "private_dns_mode");
                if (mode != null && !"off".equals(mode)) {
                    State.prefs(this).edit().putString("dnsWas", mode).apply();
                    Settings.Global.putString(cr, "private_dns_mode", "off");
                }
            } catch (SecurityException ignored) {}
            if (VpnService.prepare(this) == null) startService(new Intent(this, DnsVpn.class));
        } else {
            String was = State.prefs(this).getString("dnsWas", null);
            if (was != null) {
                try { Settings.Global.putString(cr, "private_dns_mode", was); } catch (SecurityException ignored) {}
                State.prefs(this).edit().remove("dnsWas").apply();
            }
            if (DnsVpn.running) startService(new Intent(this, DnsVpn.class).setAction(DnsVpn.STOP));
        }
    }

    private Notification note(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, HomeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "clausus")
                .setSmallIcon(R.drawable.ic_note).setContentTitle("Clausus").setContentText(text)
                .setContentIntent(open).setOngoing(true).setShowWhen(false).build();
    }
}
