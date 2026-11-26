package com.herbarium.home;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Herbarium's own "Recent" page, laid over Android's app switcher the moment it opens (a home screen can't replace
 * the switcher itself, so Guard covers it). The list is the apps Guard has seen in front, newest first.
 * Tap one to go back to it; Home and Back close the page and the switcher together.
 */
final class Recents {
    private static final int KEEP = 8;
    private static final LinkedList<String> seen = new LinkedList<>();

    private final AccessibilityService s;
    private final WindowManager wm;
    private FrameLayout panel;
    private LinearLayout col;
    volatile String switcherPkg;       // set by Guard when the switcher shows
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Names worked out ahead of time (off the main thread), so opening the page costs only drawing it. */
    private final java.util.Map<String, String> labels = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile android.graphics.Bitmap paper; private volatile String paperKey = "";
    private static final java.util.concurrent.ExecutorService work = java.util.concurrent.Executors.newSingleThreadExecutor();

    Recents(AccessibilityService s) {
        this.s = s; wm = s.getSystemService(WindowManager.class); load();
        work.execute(() -> { synchronized (seen) { for (String p : new ArrayList<>(seen)) label(p); } warmPaper(); });
    }

    private void label(String pkg) {
        if (labels.containsKey(pkg)) return;
        try {
            android.content.pm.PackageManager pm = s.getPackageManager();
            labels.put(pkg, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))));
        } catch (Exception ignored) {}
    }

    /** The paper for the current look and screen size, made (or read from disk) in the background. */
    private void warmPaper() {
        Theme t = Theme.get(s);
        int W = s.getResources().getDisplayMetrics().widthPixels, H = s.getResources().getDisplayMetrics().heightPixels;
        String k = W + "x" + H + "|" + t.night + "|" + t.contrast;
        if (k.equals(paperKey) && paper != null) return;
        android.graphics.Bitmap b = Paper.ready(s, W, H, t);
        if (b != null) { paper = b; paperKey = k; }
    }

    /** Android's switcher, whoever draws it (Motorola, Pixel and Launcher3 all call it RecentsActivity). */
    static boolean isSwitcher(CharSequence cls) { return cls != null && cls.toString().endsWith("RecentsActivity"); }

    private volatile boolean fromHome;
    /** Herbarium's Home came to the front: Back from the Recent page should return there. */
    void sawHome() { fromHome = true; }

    /** An app came to the front. */
    void saw(String pkg) {
        fromHome = false;
        synchronized (seen) {
            if (!seen.isEmpty() && seen.getFirst().equals(pkg)) return;
            seen.remove(pkg); seen.addFirst(pkg);
            while (seen.size() > KEEP) seen.removeLast();
            Theme.prefs(s).edit().putString("recentApps", String.join(",", seen)).apply();
        }
        work.execute(() -> { label(pkg); warmPaper(); });
    }

    private void load() {
        String saved = Theme.prefs(s).getString("recentApps", "");
        synchronized (seen) { seen.clear(); for (String p : saved.split(",")) if (!p.isEmpty()) seen.add(p); }
    }

    boolean showing() { return panel != null; }

    private final Runnable hideLater = this::hide;
    void hide() { main.removeCallbacks(hideLater); if (panel != null) { try { wm.removeView(panel); } catch (Exception ignored) {} panel = null; col = null; } }

    void show() {
        if (panel != null) return;
        Theme t = Theme.get(s);
        panel = new FrameLayout(s) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) close(AccessibilityService.GLOBAL_ACTION_BACK); return true; }
                return super.dispatchKeyEvent(e);
            }
        };
        panel.setForceDarkAllowed(false);
        android.graphics.Bitmap paper = this.paper;     // never painted here: that took over a second
        work.execute(this::warmPaper);                  // (ready next time if the look or size changed)
        android.widget.ImageView bg = new android.widget.ImageView(s);
        bg.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        if (paper != null) bg.setImageBitmap(paper); else bg.setImageDrawable(Paper.quick(t));
        panel.addView(bg, Ui.fill());

        Steady.Scroll sc = new Steady.Scroll(s, t);
        col = Ui.column(s); col.setPadding(t.dp(22), t.dp(56), t.dp(22), t.dp(40));
        sc.addView(col, Ui.wide());
        panel.addView(sc, Ui.fill());
        fill(t);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;      // no fade: the switcher under it must not show first
        try { wm.addView(panel, lp); panel.requestFocus(); } catch (Exception e) { panel = null; }
    }

    /** The page's contents; filled again in place after an app is closed, so the switcher never shows between. */
    private void fill(Theme t) {
        if (col == null) return;
        col.removeAllViews();
        List<String[]> list = new ArrayList<>();      // {package, name}
        synchronized (seen) {
            for (String p : seen) { String l = labels.get(p); if (l != null && State.appAllowed(s, p)) list.add(new String[]{p, l}); }
        }
        col.addView(Ui.centered(Ui.caps(s, t, "Recent", 28, t.ink)));
        col.addView(Ui.centered(Ui.text(s, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
        if (list.isEmpty()) col.addView(Ui.centered(Ui.text(s, t, "Nothing open.", 19, t.sepia, Theme.italic)), Ui.wide(t.sdp(24)));
        for (int i = 0; i < list.size(); i++) {
            String pkg = list.get(i)[0], name = list.get(i)[1];
            TextView open = i == 0 ? Ui.primary(s, t, name, v -> open(pkg, name)) : Ui.button(s, t, name, v -> open(pkg, name));
            open.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, t.btn(20));
            open.setSingleLine(true); open.setEllipsize(android.text.TextUtils.TruncateAt.END);
            open.setContentDescription((i == 0 ? "Back to " : "Open ") + name);
            TextView shut = Ui.button(s, t, "Close", v -> closeApp(pkg, name));
            shut.setContentDescription("Close " + name);
            LinearLayout row = Ui.row(s);
            if (t.huge()) {           // one under the other: the name needs the whole width
                row.setOrientation(LinearLayout.VERTICAL);
                row.addView(open, Ui.wide()); row.addView(shut, Ui.wide(t.sdp(6)));
            } else {
                row.addView(open, Ui.weight());
                LinearLayout.LayoutParams cp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); cp.leftMargin = t.sdp(10);
                row.addView(shut, cp);
            }
            col.addView(row, Ui.wide(t.sdp(i == 0 ? 20 : 10)));
        }
        if (list.size() > 1) col.addView(Ui.button(s, t, "Close them all", v -> closeAll()), Ui.wide(t.sdp(18)));
        TextView home = Ui.button(s, t, "Home", v -> close(AccessibilityService.GLOBAL_ACTION_HOME));
        TextView back = Ui.button(s, t, "Back", v -> close(AccessibilityService.GLOBAL_ACTION_BACK));
        col.addView(Ui.pair(s, t, home, back), Ui.wide(t.sdp(list.size() > 1 ? 10 : 24)));
    }

    // ---- working Android's switcher from underneath (its cards are named after their apps, with Close and click actions)

    /** The switcher's window, found on the worker thread (never on Guard's main thread). */
    private android.view.accessibility.AccessibilityNodeInfo switcherRoot() {
        String sw = switcherPkg;
        if (sw == null) return null;
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : s.getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null && sw.contentEquals(r.getPackageName())) return r;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String topApp() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : s.getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                return r == null || r.getPackageName() == null ? null : r.getPackageName().toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** The card for an app (by the name the switcher gives it), or null if it isn't there or is scrolled out of reach. */
    private static android.view.accessibility.AccessibilityNodeInfo card(android.view.accessibility.AccessibilityNodeInfo n, String name, int depth) {
        if (n == null || depth > 6) return null;
        CharSequence d = n.getContentDescription();
        if (d != null && (name.contentEquals(d) || d.toString().startsWith(name))) return n;
        for (int i = 0; i < n.getChildCount(); i++) { android.view.accessibility.AccessibilityNodeInfo c = card(n.getChild(i), name, depth + 1); if (c != null) return c; }
        return null;
    }

    private static boolean act(android.view.accessibility.AccessibilityNodeInfo n, String label) {
        if (n == null) return false;
        for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction a : n.getActionList())
            if (a.getLabel() != null && a.getLabel().toString().equalsIgnoreCase(label)) return n.performAction(a.getId());
        return false;
    }

    /** Go back to an app. Through its card when there is one, so it comes back exactly as it was left; the page stays
     *  up until the app is in front (Guard takes it down then), so the switcher is never seen on the way. */
    private void open(String pkg, String name) {
        main.removeCallbacks(hideLater); main.postDelayed(hideLater, 1500);          // in case nothing else does
        work.execute(() -> {
            android.view.accessibility.AccessibilityNodeInfo c = card(switcherRoot(), name, 0);
            boolean done = c != null && c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            if (done) {   // lift the page the moment the app is in front (not a beat later)
                for (int k = 0; k < 30; k++) {
                    try { Thread.sleep(50); } catch (Exception ignored) {}
                    if (pkg.equals(topApp())) break;
                }
                main.post(this::hide);
                return;
            }
            main.post(() -> {
                android.content.Intent i = s.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) try { s.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); } catch (Exception ignored) {}
            });
        });
    }

    /** Close an app: its card goes from Android's list (as if swiped away) and its process is stopped. */
    private void closeApp(String pkg, String name) {
        work.execute(() -> {
            act(card(switcherRoot(), name, 0), "Close");
            stop(pkg);
            synchronized (seen) { seen.remove(pkg); Theme.prefs(s).edit().putString("recentApps", String.join(",", seen)).apply(); }
            main.post(() -> fill(Theme.get(s)));
        });
    }

    private void closeAll() {
        work.execute(() -> {
            List<String> all; synchronized (seen) { all = new ArrayList<>(seen); seen.clear(); Theme.prefs(s).edit().putString("recentApps", "").apply(); }
            for (String p : all) stop(p);
            android.view.accessibility.AccessibilityNodeInfo r = switcherRoot();
            boolean cleared = false;
            if (r != null) for (String id : new String[]{"action_clear_all", "clear_all"}) {
                List<android.view.accessibility.AccessibilityNodeInfo> b = r.findAccessibilityNodeInfosByViewId(switcherPkg + ":id/" + id);
                if (b != null) for (android.view.accessibility.AccessibilityNodeInfo x : b) if (!cleared && x.isVisibleToUser()) cleared = x.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            }
            main.post(() -> { hide(); s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); });
        });
    }

    private void stop(String pkg) {
        try { s.getSystemService(android.app.ActivityManager.class).killBackgroundProcesses(pkg); } catch (Exception ignored) {}
    }

    /** Back goes where it goes from Android's switcher: to the app you came from (Android's own Back doesn't close
     *  the switcher here, which left it showing under nothing). Home goes home. */
    private void close(int action) {
        String last;
        synchronized (seen) { last = seen.isEmpty() ? null : seen.getFirst(); }
        if (action == AccessibilityService.GLOBAL_ACTION_BACK && !fromHome && last != null && labels.containsKey(last) && State.appAllowed(s, last)) { open(last, labels.get(last)); return; }
        hide(); s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }
}
