package com.herbarium.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Set;

/**
 * While a block runs: blocked apps are sent home with a "Blocked by Clausus" screen, and the settings
 * pages that could switch Clausus off (its app info, accessibility, device admin, VPN, uninstall)
 * are closed. When nothing is blocked it does nothing at all.
 */
public class Guard extends AccessibilityService {
    private long lastBlock;
    private static final String[] SETTINGS = {
            "com.android.settings", "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.motorola.settings", "com.google.android.permissioncontroller", "com.android.permissioncontroller"};

    private final android.content.BroadcastReceiver screen = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            // the phone locks a moment after the screen goes off; show Herbarium's face so it's ready when it comes back on
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction()) || android.media.AudioManager.RINGER_MODE_CHANGED_ACTION.equals(i.getAction())) {
                Care.keepRinging(Guard.this);                // Caretaken: calls must be heard
                if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) Missed.notifyWaiting(Guard.this);
                if (!Intent.ACTION_SCREEN_ON.equals(i.getAction())) return;
            }
            long wait = Intent.ACTION_SCREEN_OFF.equals(i.getAction()) ? 600 : 0;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> LockActivity.show(Guard.this), wait);
        }
    };

    private final android.database.ContentObserver callLog = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean self) {
            Missed.notifyWaiting(Guard.this);
            sendBroadcast(new Intent(Missed.CHANGED).setPackage(getPackageName()));
        }
    };

    static volatile Guard instance;
    Shade shade;
    private long homeCheck;

    static void openShade() { Guard g = instance; if (g != null && g.shade != null) g.shade.show(); }

    @Override protected void onServiceConnected() {
        instance = this;
        shade = new Shade(this); shade.refresh();
        ClaususService.start(this);
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON); f.addAction(Intent.ACTION_DREAMING_STOPPED); f.addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION);   // the screensaver ends straight into the lock screen
        registerReceiver(screen, f);
        // a missed call shows up in the call log: refresh the call-back notice and Home straight away (Caretaken mode)
        try {
            getContentResolver().registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, callLog);
        } catch (Exception ignored) {}   // no call-log permission yet
        Care.apply(this);
    }

    @Override public void onDestroy() { try { getContentResolver().unregisterContentObserver(callLog); } catch (Exception ignored) {} instance = null; if (shade != null) shade.destroy(); try { unregisterReceiver(screen); } catch (Exception ignored) {} super.onDestroy(); }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        long now0 = SystemClock.elapsedRealtime();
        if (shade != null && now0 - homeCheck > 20_000) { homeCheck = now0; shade.refresh(); }   // the edge strip follows the settings
        Care.keepRinging(this);                                                                    // (checks at most every 15 s)
        if (pkg.equals(getPackageName())) return;
        // an alarm is ringing and the clock app's own screen came up over Herbarium's: put Herbarium's back in front
        if (AlarmActivity.now != null && pkg.equals(AlarmActivity.now.pkg) && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { AlarmActivity.show(this); return; }
        // Caretaken mode with a helper PIN: Android's Settings are for helpers too (Herbarium's own passes still work)
        if ((pkg.equals("com.android.settings") || pkg.equals("com.motorola.settings")) && Care.settingsLocked(this)) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean pass = false;
            if (root != null) { try { pass = passes(pkg, root); } finally { root.recycle(); } }
            if (!pass) { block(pkg, "Settings are for helpers. Ask the person who looks after this phone."); return; }
        }
        if (!State.locked(this)) return;

        Set<String> blocked = State.packages(this);
        if (blocked.contains(pkg)) { block(pkg, null); return; }
        // allow-list mode: any app (or the phone's Settings) that isn't on the list is closed
        if (!State.allowApps(this).isEmpty() && !State.appAllowed(this, pkg) && (isApp(pkg) || pkg.equals("com.android.settings"))) { block(pkg, null); return; }

        for (String s : SETTINGS) {
            if (!s.equals(pkg)) continue;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                // Herbarium updating itself: Android's installer screen and its "allow from this source" page may show,
                // but never anything that uninstalls (and only for a few minutes after Herbarium started the update)
                if (passes(pkg, root)) return;
                // a page about Clausus itself, or any switch-it-off page that mentions it
                // pages about Herbarium itself: its app info, accessibility, admin, VPN, and choosing the home screen
                for (String name : new String[]{"Herbarium", "Clausus"}) {
                    List<AccessibilityNodeInfo> me = root.findAccessibilityNodeInfosByText(name);
                    if (me != null && !me.isEmpty()) { block(pkg, "These settings are locked while Clausus is on."); return; }
                }
            } finally { root.recycle(); }
        }
    }

    // ---- short passes: Herbarium opens one just before it sends the person to a screen it needs, for a few minutes.
    //      Each lets through only that kind of screen, and never one that offers to uninstall.
    static void allow(Context c, String kind) {
        Theme.prefs(c).edit().putLong("pass_" + kind, SystemClock.elapsedRealtime() + 5 * 60_000L).apply();
    }
    private boolean open(String kind) { return SystemClock.elapsedRealtime() < Theme.prefs(this).getLong("pass_" + kind, 0); }

    private boolean passes(String pkg, AccessibilityNodeInfo root) {
        if (has(root, "Uninstall") || has(root, "uninstall")) return false;
        boolean settings = pkg.equals("com.android.settings") || pkg.equals("com.motorola.settings");
        // Herbarium updating itself: the installer, and "Allow from this source" the first time
        if (open("update") && (pkg.endsWith("packageinstaller") || settings && has(root, "Allow from this source"))) return true;
        // voice typing: Android asking whether Herbarium may use the microphone
        if (open("mic") && pkg.endsWith("permissioncontroller") && (has(root, "microphone") || has(root, "record audio"))) return true;
        // Caretaken mode: Android asking about call history and placing calls, and the Do Not Disturb access page
        if (open("calls") && (pkg.endsWith("permissioncontroller") && (has(root, "call log") || has(root, "phone calls") || has(root, "contacts"))
                || settings && has(root, "Do Not Disturb"))) return true;
        // Herbarium's own App info and notification-access pages, opened from setup or Settings
        if (open("settings") && settings && (has(root, "Notification") || has(root, "notification") || has(root, "restricted") || has(root, "App info"))) return true;
        // becoming the texting app: Android's "Change your SMS app?" question
        if (open("texts") && (pkg.endsWith("permissioncontroller") || settings) && (has(root, "SMS") || has(root, "messaging") || has(root, "Messages"))) return true;
        // choosing Herbarium's keyboard: Android's keyboard pages
        if (open("keyboard") && settings && (has(root, "keyboard") || has(root, "Keyboard"))) return true;
        return false;
    }

    // ---- the volume buttons: Herbarium's own volume card instead of Android's slider (locked or not)
    @Override protected boolean onKeyEvent(android.view.KeyEvent e) {
        int k = e.getKeyCode();
        if (k != android.view.KeyEvent.KEYCODE_VOLUME_UP && k != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        android.telecom.Call c = InCall.current();
        if (c != null && c.getDetails().getState() == android.telecom.Call.STATE_RINGING) return false;   // the buttons still silence the ring
        if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        if (am == null) return false;
        VolumeCard.step(am, k == android.view.KeyEvent.KEYCODE_VOLUME_UP);
        VolumeCard.show(this, am);
        return true;
    }

    private static boolean has(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> n = root.findAccessibilityNodeInfosByText(text);
        return n != null && !n.isEmpty();
    }

    private void block(String pkg, String why) {
        long now = SystemClock.elapsedRealtime();
        performGlobalAction(GLOBAL_ACTION_HOME);
        if (now - lastBlock < 1500) return;
        lastBlock = now;
        Intent i = new Intent(this, NotNowActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("pkg", pkg).putExtra("why", why);
        // let the home screen settle first, or it ends up on top of the "Not now" screen
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> startActivity(i), 450);
    }

    private java.util.Set<String> apps; private long appsAt;
    /** Only real apps (with an icon in the app list) are judged; overlays, keyboards and system bits are left alone. */
    private boolean isApp(String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (apps == null || now - appsAt > 60_000) {
            java.util.Set<String> s = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) s.add(r.activityInfo.packageName);
            apps = s; appsAt = now;
        }
        return apps.contains(pkg);
    }

    @Override public void onInterrupt() {}
}
