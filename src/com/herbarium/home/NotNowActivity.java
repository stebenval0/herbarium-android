package com.herbarium.home;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shown when something blocked by Clausus was opened anyway (a link from another app, say). */
public class NotNowActivity extends Activity {
    private TextView what, when;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        Theme t = Theme.get(this);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        LinearLayout col = Ui.column(this); col.setGravity(Gravity.CENTER);
        col.setPadding(t.dp(28), t.dp(40), t.dp(28), t.dp(40));
        Bitmap paper = Paper.ready(this, W, H, t);
        col.setBackground(paper != null ? new BitmapDrawable(getResources(), paper) : Paper.quick(t));

        ImageView plate = new ImageView(this);
        int pw = t.sdp(110);
        LinearLayout.LayoutParams pp = Ui.lp(pw, Math.round(pw * 1.6f)); pp.gravity = Gravity.CENTER_HORIZONTAL;
        plate.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final Theme th = t;                                  // drawn off the main thread: the screen itself appears at once
        new Thread(() -> { Bitmap pb = Engraving.plate((int) (Engraving.dayNumber() % 7), pw, th, true); runOnUiThread(() -> plate.setImageBitmap(pb)); }, "notnow-plate").start();
        col.addView(plate, pp);
        col.addView(Ui.centered(Ui.caps(this, t, "Not now", 32, t.ink)), Ui.wide(t.sdp(14)));
        what = Ui.centered(Ui.body(this, t, "", 20));
        when = Ui.centered(Ui.text(this, t, "", 18, t.sage, Theme.italic));
        col.addView(what, Ui.wide(t.sdp(10)));
        col.addView(when, Ui.wide(t.sdp(12)));
        TextView home = Ui.primary(this, t, "Back to Home", v -> {
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        LinearLayout.LayoutParams hp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); hp.gravity = Gravity.CENTER_HORIZONTAL; hp.topMargin = t.sdp(30);
        col.addView(home, hp);
        setContentView(col);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
        show(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); show(i); }

    private void show(Intent i) {
        String why = i.getStringExtra("why"), pkg = i.getStringExtra("pkg"), name = pkg == null ? "That" : pkg;
        try { PackageManager pm = getPackageManager(); name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
        what.setText(why != null ? why : name + " is behind the garden wall while Clausus is on.");
        long rem = State.remaining(this);
        when.setText(State.always(this) ? "It stays closed until Clausus is switched off on the computer." : rem > 0 ? "It opens again in " + State.fmt(rem) + "." : "It's open again now.");
    }

    @Override public void onBackPressed() { startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); }
}
