package com.herbarium.home;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;

/** Asks Android once whether Herbarium may hear the person, for voice typing; then goes straight back. */
public class MicActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme t = Theme.get(this);
        LinearLayout col = Ui.column(this); col.setGravity(Gravity.CENTER); col.setPadding(t.dp(28), 0, t.dp(28), 0);
        col.setBackground(Paper.quick(t));
        col.addView(Ui.centered(Ui.caps(this, t, "Voice typing", 26, t.ink)));
        col.addView(Ui.centered(Ui.body(this, t, "So you can speak instead of typing, Herbarium needs to hear you while the keyboard is listening.", 20)), Ui.wide(t.sdp(14)));
        setContentView(col);
        Guard.allow(this, "mic");
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }
    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) { finish(); }
}
