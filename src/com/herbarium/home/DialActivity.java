package com.herbarium.home;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Herbarium's dial pad: opens for "Phone", and whenever anything asks to dial a number (tel: links, call-backs
 * without permission). Big keys, the number written out large, and one big Call button.
 */
public class DialActivity extends Activity {
    private Theme t;
    private final StringBuilder number = new StringBuilder();
    private TextView shown, msg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        t = Theme.get(this);
        Uri data = getIntent().getData();
        if (data != null && "tel".equals(data.getScheme())) number.append(data.getSchemeSpecificPart().replaceAll("[^0-9+*#]", ""));

        Steady.Scroll sc = new Steady.Scroll(this, t);
        android.graphics.Bitmap paper = Paper.ready(this, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels, t);
        sc.setBackground(paper != null ? new android.graphics.drawable.BitmapDrawable(getResources(), paper) : Paper.quick(t));
        LinearLayout col = Ui.column(this); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(t.dp(22), t.dp(54), t.dp(22), t.dp(40));
        sc.addView(col, Ui.wide());

        col.addView(Ui.centered(Ui.caps(this, t, "Phone", 26, t.ink)));
        shown = Ui.centered(Ui.text(this, t, "", 36, t.ink, Theme.regular));
        shown.setMinHeight(t.sdp(56)); shown.setSingleLine(true);
        shown.setAutoSizeTextTypeUniformWithConfiguration(16, Math.round(t.sp(36)), 1, TypedValue.COMPLEX_UNIT_SP);
        col.addView(shown, Ui.wide(t.sdp(14)));
        msg = Ui.centered(Ui.text(this, t, "", 16, t.ochre, Theme.regular));
        col.addView(msg, Ui.wide());

        android.widget.GridLayout g = new android.widget.GridLayout(this); g.setColumnCount(3);
        float grow = 1 + (t.scale - 1) * .3f;
        int cell = Math.min((getResources().getDisplayMetrics().widthPixels - t.dp(64)) / 3, Math.round(t.dp(100) * grow));
        for (String k : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"}) {
            TextView key = Ui.button(this, t, k, v -> { if (number.length() < 20) number.append(k); show(); });
            Steady.repeatable(key);
            key.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(30));
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(); lp.width = cell; lp.height = Math.round(cell * .74f);
            lp.setMargins(t.dp(5), t.dp(5), t.dp(5), t.dp(5));
            g.addView(key, lp);
        }
        LinearLayout.LayoutParams gp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); gp.gravity = Gravity.CENTER_HORIZONTAL; gp.topMargin = t.sdp(10);
        col.addView(g, gp);

        TextView call = Ui.primary(this, t, "Call", v -> call());
        call.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(24));
        call.setMinHeight(Math.round(t.dp(92) * (1 + (t.scale - 1) * .35f)));
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(); gd.setCornerRadius(t.sdp(20)); gd.setColor(t.night ? 0xff6fae86 : 0xff3f7a55);
        call.setBackground(gd); call.setTextColor(0xfff6f1e2);
        col.addView(call, Ui.wide(t.sdp(16)));
        TextView del = Ui.button(this, t, "Delete last digit", v -> { if (number.length() > 0) number.setLength(number.length() - 1); show(); });
        Steady.repeatable(del);
        TextView back = Ui.button(this, t, "Back", v -> finish());
        col.addView(Ui.pair(this, t, del, back), Ui.wide(t.sdp(12)));

        setContentView(sc);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
        show();
    }

    private void show() {
        String n = number.toString();
        String pretty = n.length() >= 7 && n.matches("[0-9+]+") ? PhoneNumberUtils.formatNumber(n, "US") : n;
        shown.setText(n.isEmpty() ? "" : pretty == null ? n : pretty);
        shown.setContentDescription(n.isEmpty() ? "No number yet" : "Number: " + n.replaceAll("(.)", "$1 "));
        msg.setText("");
    }

    private void call() {
        String n = number.toString();
        if (n.isEmpty()) { msg.setText("Type the number first."); return; }
        try {
            getSystemService(TelecomManager.class).placeCall(Uri.fromParts("tel", n, null), new Bundle());
            finish();
        } catch (SecurityException e) {
            msg.setText("Herbarium may not place calls yet (Settings → Calls).");
        }
    }
}
