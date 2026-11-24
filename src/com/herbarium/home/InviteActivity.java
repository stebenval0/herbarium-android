package com.herbarium.home;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * "Connect to the keeper's computer?" Opened by the herbarium://pair link from `clausus phone invite`,
 * or by "Paste the invitation" in setup and Accessibility. Nothing changes until the person says yes.
 */
public class InviteActivity extends Activity {

    /** Read an invitation from the clipboard and open the confirmation; false if there isn't one. */
    static boolean fromClipboard(Activity a) {
        ClipboardManager cm = a.getSystemService(ClipboardManager.class);
        ClipData d = cm == null ? null : cm.getPrimaryClip();
        String text = d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null ? d.getItemAt(0).getText().toString() : "";
        if (Relay.parseInvite(text) == null) return false;
        a.startActivity(new Intent(a, InviteActivity.class).putExtra("code", text));
        return true;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        Theme t = Theme.get(this);
        String text = getIntent().getData() != null ? getIntent().getData().toString() : getIntent().getStringExtra("code");
        JSONObject inv = Relay.parseInvite(text);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        Steady.Scroll sc = new Steady.Scroll(this, t);
        android.graphics.Bitmap paper = Paper.ready(this, W, H, t);
        sc.setBackground(paper != null ? new android.graphics.drawable.BitmapDrawable(getResources(), paper) : Paper.quick(t));
        LinearLayout col = Ui.column(this); col.setPadding(t.dp(26), t.dp(70), t.dp(26), t.dp(60)); col.setGravity(Gravity.CENTER_HORIZONTAL);
        sc.addView(col, Ui.wide());
        col.addView(Ui.centered(Ui.caps(this, t, "A keeper", 28, t.ink)));
        col.addView(Ui.centered(Ui.text(this, t, "✦ ✦ ✦", 14, t.gold, Theme.regular)), Ui.wide(t.sdp(6)));
        if (inv == null) {
            col.addView(Ui.centered(Ui.body(this, t, "This doesn't look like a Herbarium invitation. Ask your keeper to send it again.", 20)), Ui.wide(t.sdp(18)));
            col.addView(Ui.primary(this, t, "Back", v -> finish()), Ui.wide(t.sdp(24)));
        } else {
            col.addView(Ui.centered(Ui.body(this, t, "Connect this phone to your keeper's computer?", 21)), Ui.wide(t.sdp(18)));
            col.addView(Ui.centered(Ui.text(this, t, "From then on, your keeper switches Clausus (what's blocked on this phone) on and off from there, wherever this phone is. "
                    + "Nothing on this phone can switch it off.", 18, t.sepia, Theme.regular)), Ui.wide(t.sdp(12)));
            col.addView(Ui.centered(Ui.text(this, t, "Through " + inv.optString("repo"), 15, t.sage, Theme.italic)), Ui.wide(t.sdp(10)));
            TextView status = Ui.centered(Ui.text(this, t, "", 18, t.ochre, Theme.regular));
            col.addView(Ui.primary(this, t, "Yes, connect", v -> {
                if (Relay.accept(this, inv)) { status.setTextColor(t.sage); status.setText("Connected. Your keeper will see this phone within a few minutes."); }
                else status.setText("Clausus is on with another keeper, so this can't change until it's switched off there.");
            }), Ui.wide(t.sdp(26)));
            col.addView(Ui.button(this, t, "Not now", v -> finish()), Ui.wide(t.sdp(10)));
            col.addView(status, Ui.wide(t.sdp(14)));
        }
        setContentView(sc);
        getWindow().setNavigationBarColor(t.paper2);
    }
}
