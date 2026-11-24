package com.herbarium.home;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * Herbarium's screensaver (what shows while the phone charges or stays awake): the night paper, the moon,
 * a soft clock, the date and the day's summary line. It drifts a little every minute so nothing burns in.
 */
public class Dream extends DreamService {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView time, date, line;
    private LinearLayout col;
    private int step;
    private final Runnable tick = new Runnable() { public void run() { update(); h.postDelayed(this, 30_000); } };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); setFullscreen(true); setScreenBright(false);
        Theme t = Theme.night(this);                       // a resting screen is always the night look: calm and dark
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        FrameLayout root = new FrameLayout(this); root.setForceDarkAllowed(false);
        root.setBackgroundColor(0xff0c1026);
        ImageView bg = new ImageView(this); bg.setScaleType(ImageView.ScaleType.FIT_XY);
        android.graphics.Bitmap paper = Paper.ready(this, W, H, t);
        if (paper != null) bg.setImageBitmap(paper); else bg.setImageDrawable(Paper.quick(t));
        bg.setAlpha(.55f);                                  // dimmer than the home screen: it's a resting screen
        root.addView(bg, Ui.fill());
        col = Ui.column(this); col.setGravity(Gravity.CENTER_HORIZONTAL);
        View moon = new View(this) { @Override protected void onDraw(Canvas c) { Paper.moon(c, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f - t.dp(1), t); } };
        LinearLayout.LayoutParams mp = Ui.lp(t.dp(30), t.dp(30)); mp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(moon, mp);
        time = Ui.centered(Ui.text(this, t, "", 96, t.ink, Theme.regular)); time.setAlpha(.85f);
        date = Ui.centered(Ui.text(this, t, "", 21, t.sepia, Theme.regular));
        line = Ui.centered(Ui.text(this, t, "", 18, t.sage, Theme.italic));
        col.addView(time, Ui.wide(t.dp(6))); col.addView(date, Ui.wide(t.dp(4))); col.addView(line, Ui.wide(t.dp(22)));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cp.leftMargin = cp.rightMargin = t.dp(28);
        root.addView(col, cp);
        setContentView(root);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); h.post(tick); }
    @Override public void onDreamingStopped() { h.removeCallbacks(tick); super.onDreamingStopped(); }

    private void update() {
        Calendar d = Calendar.getInstance();
        int hr = d.get(Calendar.HOUR) == 0 ? 12 : d.get(Calendar.HOUR);
        time.setText(hr + ":" + String.format(Locale.US, "%02d", d.get(Calendar.MINUTE)));
        String[] days = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        String[] months = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
        date.setText(days[d.get(Calendar.DAY_OF_WEEK) - 1] + ", the " + HomeActivity.ord(d.get(Calendar.DAY_OF_MONTH)) + " of " + months[d.get(Calendar.MONTH)]);
        try { line.setText(Notes.enabled(this) ? Summary.of(this).headline : Weather.line(this)); } catch (Exception e) { line.setText(""); }
        // drift: a slow figure of eight, about a thumb's width, so no pixel stays lit in one place
        step++;
        float dx = (float) Math.sin(step * .37) * getResources().getDisplayMetrics().density * 18;
        float dy = (float) Math.sin(step * .21) * getResources().getDisplayMetrics().density * 60;
        col.animate().translationX(dx).translationY(dy).setDuration(4000).start();
    }
}
