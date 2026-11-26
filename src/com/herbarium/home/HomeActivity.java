package com.herbarium.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Herbarium's home: the time, the weather, the seasonal plates (chapters of apps) and Seek. */
public class HomeActivity extends Activity {
    private Theme t;
    private String themeKey = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private FrameLayout root, sheet;
    private Deck deck;
    private Steady.Scroll homeScroll;
    private TextView time, date, weatherLine, season, stripTime, stripRight, tabHome;
    private NuntiusPage nuntius;
    private LinearLayout summaryCard;
    private TextView greeting;
    private android.speech.tts.TextToSpeech tts;
    // the pages, left to right: [Nuntius] · Calls · Home · Messages · Weather (no Nuntius in Caretaken mode)
    private int NUNTIUS = -1, CALLS = 0, HOME = 1, MESSAGES = 2, WEATHER = 3;
    private LinearLayout callsPage, messagesPage;
    private TextView[] tabs = new TextView[0];
    private LinearLayout weatherPage;
    private GridLayout grid;
    private LinearLayout results;
    private EditText seek;
    private List<Apps.App> apps = new ArrayList<>();
    private List<Apps.Chapter> chapters = new ArrayList<>();
    private final List<ImageView> plateViews = new ArrayList<>();
    private int battery = -1; private boolean charging;

    private final Runnable tick = new Runnable() { public void run() { tickNow(); ui.postDelayed(this, 15_000); } };
    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            battery = lvl < 0 ? -1 : Math.round(lvl * 100f / sc);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            updateStrip();
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { loadApps(); } };
    private final BroadcastReceiver clausus = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { loadApps(); updateStrip(); renderSummary(); } };
    private final BroadcastReceiver notes = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { renderSummary(); } };
    private final BroadcastReceiver missed = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { renderCare(); renderPages(); } };
    private final BroadcastReceiver texts = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { if (messagesPage != null) HomePages.fillMessages(HomeActivity.this, t, messagesPage, bg, ui); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        build();
        registerReceiver(power, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter pf = new IntentFilter(); pf.addAction(Intent.ACTION_PACKAGE_ADDED); pf.addAction(Intent.ACTION_PACKAGE_REMOVED); pf.addAction(Intent.ACTION_PACKAGE_CHANGED); pf.addDataScheme("package");
        registerReceiver(packages, pf);
        registerReceiver(clausus, new IntentFilter(ClaususService.CHANGED), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(notes, new IntentFilter(Notes.CHANGED), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(missed, new IntentFilter(Missed.CHANGED), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(texts, new IntentFilter(Texts.CHANGED), Context.RECEIVER_NOT_EXPORTED);
        ClaususService.start(this);
        if (SetupActivity.needed(this)) startActivity(new Intent(this, SetupActivity.class));
    }

    @Override protected void onDestroy() { unregisterReceiver(power); unregisterReceiver(packages); unregisterReceiver(clausus); unregisterReceiver(notes); unregisterReceiver(missed); unregisterReceiver(texts); if (tts != null) tts.shutdown(); bg.shutdown(); super.onDestroy(); }

    private TextView callBar;
    private final Runnable callCheck = new Runnable() { @Override public void run() {
        if (callBar != null) callBar.setVisibility(InCall.current() != null ? View.VISIBLE : View.GONE);
        ui.postDelayed(this, 1500);
    } };

    private long stoppedAt;
    @Override protected void onStop() { stoppedAt = android.os.SystemClock.elapsedRealtime(); super.onStop(); }
    @Override protected void onResume() {
        super.onResume();
        // back after more than a minute away (the phone was locked, say): Home, at the top
        if (stoppedAt > 0 && android.os.SystemClock.elapsedRealtime() - stoppedAt > 60_000 && deck != null && sheet.getVisibility() != View.VISIBLE) {
            deck.go(HOME, false); if (homeScroll != null) homeScroll.scrollTo(0, 0);
        }
        stoppedAt = 0;
        ui.removeCallbacks(callCheck); ui.post(callCheck);
        if (SetupActivity.needed(this)) startActivity(new Intent(this, SetupActivity.class));   // [onboarding] an unfinished setup comes back to the front
        if (!themeKey.equals(key(Theme.get(this)))) build();
        hideBars();
        renderCare(); renderPages(); Care.apply(this);
        try { getContentResolver().registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, callLog); } catch (Exception ignored) {}
        ui.removeCallbacks(tick); ui.post(tick);
        if (Weather.stale(this)) bg.execute(() -> { if (Weather.refresh(this)) ui.post(() -> { updateWeather(); if (!themeKey.equals(key(Theme.get(this)))) build(); }); });
    }
    @Override protected void onPause() { ui.removeCallbacks(callCheck); ui.removeCallbacks(tick); try { getContentResolver().unregisterContentObserver(callLog); } catch (Exception ignored) {} super.onPause(); }

    /** Caretaken mode: a missed call or a call back changes Home at once. */
    private final android.database.ContentObserver callLog = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean self) { renderCare(); if (callsPage != null) HomePages.fillCalls(HomeActivity.this, t, callsPage, bg, ui); bg.execute(() -> Missed.notifyWaiting(HomeActivity.this)); }
    };

    /** Pressing Home while already home: close whatever is open and go back to the top. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (sheet.getVisibility() == View.VISIBLE) { closeSheet(); return; }
        if (seek != null && seek.getText().length() > 0) { seek.setText(""); hideKeyboard(); return; }
        deck.go(HOME, true); homeScroll.smoothScrollTo(0, 0);
    }
    @Override public void onBackPressed() {
        if (sheet.getVisibility() == View.VISIBLE) closeSheet();
        else if (seek != null && seek.getText().length() > 0) { seek.setText(""); hideKeyboard(); }
        else if (deck.page != HOME) deck.go(HOME, true);
    }

    private String key(Theme t) { return t.night + "|" + t.scale + "|" + t.contrast + "|" + t.steady + "|" + Engraving.dayNumber() + "|" + Care.on(this); }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
        if (c != null) c.setSystemBarsAppearance(t.night ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    // ------------------------------------------------------------------ building the screen
    private void build() {
        t = Theme.get(this); themeKey = key(t);
        careBox = null; peoplePage = null; seek = null; grid = null; results = null;   // Caretaken Home has no search or plates
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        root = new FrameLayout(this);
        root.setBackgroundColor(t.paper);
        // the paper is one wide sheet behind all three pages, so swiping pans across one garden: it travels a quarter
        // of a screen per page (slower than the pages, like scenery), and the whole view moves sideways together
        final boolean careMode = Care.on(this);
        final int pageCount = careMode ? 4 : 5;
        final int PW = Math.round(W * (pageCount + PAPER_MARGIN * 2));   // a screen of paper for each page, side by side, and a little spare at each end
        paperView = new ImageView(this);
        paperView.setScaleType(ImageView.ScaleType.FIT_XY);
        // (the paper goes on the deck's sheet, below, with the three pages laid on it side by side)
        Bitmap paper = Paper.ready(this, PW, H, t);
        if (paper != null) paperView.setImageBitmap(paper);
        else {
            final ImageView pv = paperView; final Theme th = t;
            bg.execute(() -> { Bitmap b2 = Paper.paint(this, PW, H, th); ui.post(() -> { if (paperView == pv) pv.setImageBitmap(b2); }); });
        }

        deck = new Deck(this, paperView, W, PW);
        int at = 0;
        if (!careMode) {
            Steady.Scroll nsc = new Steady.Scroll(this, t);
            LinearLayout ncol = Ui.column(this); ncol.setPadding(t.dp(24), t.dp(52), t.dp(24), t.dp(120));
            nsc.addView(ncol, Ui.wide());
            nuntius = new NuntiusPage(this, t, ncol, bg, ui);
            deck.addPage(nsc); NUNTIUS = at++;
        } else { nuntius = null; NUNTIUS = -1; }
        callsPage = Ui.column(this); callsPage.setPadding(t.dp(20), t.dp(52), t.dp(20), t.dp(120));
        Steady.Scroll csc = new Steady.Scroll(this, t); csc.addView(callsPage, Ui.wide());
        deck.addPage(csc); CALLS = at++;
        homeScroll = new Steady.Scroll(this, t);
        homeScroll.addView(buildHome(), Ui.wide());
        deck.addPage(homeScroll); HOME = at++;
        messagesPage = Ui.column(this); messagesPage.setPadding(t.dp(20), t.dp(52), t.dp(20), t.dp(120));
        Steady.Scroll msc = new Steady.Scroll(this, t); msc.addView(messagesPage, Ui.wide());
        deck.addPage(msc); MESSAGES = at++;
        Steady.Scroll wx = new Steady.Scroll(this, t);
        weatherPage = Ui.column(this);
        weatherPage.setPadding(t.dp(24), t.dp(56), t.dp(24), t.dp(110));
        wx.addView(weatherPage, Ui.wide());
        deck.addPage(wx); WEATHER = at++;
        deck.page = HOME; deck.go(HOME, false);
        root.addView(deck, Ui.fill());

        root.addView(buildStrip(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(40), Gravity.TOP));
        root.addView(buildTabs(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // during a call, a way back to it (pressing Home leaves the call screen behind)
        callBar = Ui.primary(this, t, "Back to the call", v -> CallActivity.bringBack(this));
        FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        cbp.topMargin = t.dp(44); cbp.leftMargin = cbp.rightMargin = t.dp(18);
        callBar.setElevation(t.dp(14)); callBar.setVisibility(View.GONE);
        root.addView(callBar, cbp);

        sheet = new FrameLayout(this); sheet.setVisibility(View.GONE); sheet.setBackgroundColor(t.paperHi); sheet.setElevation(t.dp(12));
        root.addView(sheet, Ui.fill());

        setContentView(root);
        loadApps();
        tickNow(); updateWeather(); updateStrip(); renderSummary(); warmVoice();
        if (Care.on(this)) renderCare(); else { nuntius.render(); nuntius.sync(); }
        renderPages();
    }

    private View buildStrip() {
        LinearLayout s = Ui.row(this);
        s.setPadding(t.dp(18), t.dp(8), t.dp(18), 0);
        s.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{t.paper, t.paper, 0x00000000}));
        stripTime = Ui.caps(this, t, "", 15, t.sepia); stripTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        stripRight = Ui.caps(this, t, "", 15, t.sepia); stripRight.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.addView(stripTime); s.addView(new View(this), Ui.weight()); s.addView(stripRight);
        s.setMinimumHeight(t.dp(40));
        Steady.tap(s, t, v -> Guard.openShade());          // tap the strip for the shade (no pulling needed)
        s.setContentDescription("Open the shade");
        return s;
    }

    private LinearLayout tabRow;
    private ImageView paperView;
    private static final float PARALLAX = 1f;       // the paper moves with the pages: one solid sheet, nothing sliding against anything
    private static final float PAPER_MARGIN = .1f;   // and has a tenth of a screen to spare at each end

    private View buildTabs() {
        LinearLayout r = Ui.row(this);
        tabRow = r;
        r.setGravity(Gravity.CENTER);
        r.setPadding(t.dp(8), t.dp(30), t.dp(8), t.dp(28));   // clear of the gesture bar
        r.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{t.paper2, t.paper2, t.paper2, (t.paper2 & 0x00ffffff) | 0xcc000000, 0x00000000}));
        List<String> names = new ArrayList<>();
        if (NUNTIUS >= 0) names.add("Nuntius");
        names.add("Calls"); names.add("Home"); names.add("Messages"); names.add("Weather");
        tabs = new TextView[names.size()];
        for (int k = 0; k < tabs.length; k++) {
            TextView tab = Ui.caps(this, t, names.get(k), 15, t.sepia);
            tab.setGravity(Gravity.CENTER); tab.setMinHeight(t.target(56)); tab.setPadding(t.dp(2), 0, t.dp(2), 0);
            tab.setText(names.get(k).toUpperCase(Locale.ROOT)); tab.setLetterSpacing(.06f);
            tab.setMaxLines(1);           // (not setSingleLine: that stops the text from shrinking to fit)
            // every tab fits on one line, however many there are and however large the text
            tab.setAutoSizeTextTypeUniformWithConfiguration(9, Math.round(Math.min(t.sp(15), 15 * 1.25f)), 1, TypedValue.COMPLEX_UNIT_SP);
            final int page = k;
            Steady.tap(tab, t, v -> deck.go(page, true));
            tab.setContentDescription(names.get(k));
            r.addView(tab, Ui.weight());
            tabs[k] = tab;
        }
        tabHome = tabs[HOME];
        tabsFollow(deck.page);
        // all tabs the same size: once each has shrunk to fit, they all take the smallest
        r.post(() -> {
            float min = Float.MAX_VALUE;
            for (TextView tab : tabs) min = Math.min(min, tab.getTextSize());
            for (TextView tab : tabs) { tab.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE); tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, min); }
            // then even gaps between the words (equal cells left CALLS adrift and MESSAGES touching WEATHER)
            for (TextView tab : tabs) tab.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        });
        return r;
    }

    /** The tabs' colours follow the deck smoothly as it moves: no tab changes all at once. */
    private void tabsFollow(float pos) {
        if (tabHome == null) return;
        android.animation.ArgbEvaluator mix = new android.animation.ArgbEvaluator();
        for (int k = 0; k < tabs.length; k++) {
            float near = Math.max(0, 1 - Math.abs(pos - k));
            tabs[k].setTextColor((int) mix.evaluate(near, t.sepia, t.ink));
            tabs[k].setAlpha(.7f + .3f * near);
        }
    }

    private void tabColours(int i) { tabsFollow(i); }

    /** A page has settled: anything slow (fetching Nuntius) happens now, never during the glide. */
    private void showTab(int i) {
        tabColours(i);
        if (i == NUNTIUS && nuntius != null && !Care.on(this)) nuntius.sync();
    }

    private View buildHome() {
        LinearLayout col = Ui.column(this);
        col.setPadding(0, t.dp(46), 0, t.dp(120));
        View moon = new View(this) {
            @Override protected void onDraw(Canvas c) { Paper.moon(c, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f - t.dp(1), t); }
        };
        LinearLayout.LayoutParams mp = Ui.lp(t.dp(t.large() ? 32 : 26), t.dp(t.large() ? 32 : 26)); mp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(moon, mp);
        time = Ui.centered(Ui.text(this, t, "", 84, t.ink, Theme.regular)); time.setLetterSpacing(-.02f);
        date = Ui.centered(Ui.text(this, t, "", 21, t.sepia, Theme.regular));
        weatherLine = Ui.centered(Ui.caps(this, t, "", 15, t.sepia));
        season = Ui.centered(Ui.caps(this, t, "", 13, t.sage));
        if (t.large()) weatherLine.setTypeface(Theme.regular);
        greeting = Ui.centered(Ui.text(this, t, "", 18, t.sage, Theme.italic));
        col.addView(time, Ui.wide(t.sdp(2))); col.addView(greeting, Ui.wide(t.sdp(2))); col.addView(date, Ui.wide(t.sdp(4)));
        col.addView(weatherLine, Ui.wide(t.sdp(8))); col.addView(season, Ui.wide(t.sdp(4)));
        summaryCard = Ui.column(this);
        LinearLayout.LayoutParams scp = Ui.wide(t.sdp(16)); scp.leftMargin = scp.rightMargin = t.dp(22);
        col.addView(summaryCard, scp);

        if (Care.on(this)) {
            careBox = Ui.column(this);
            LinearLayout.LayoutParams cbp = Ui.wide(t.sdp(10)); cbp.leftMargin = cbp.rightMargin = t.dp(20);
            col.addView(careBox, cbp);
            // settings are for the helper: a quiet button that opens only after a 3-second hold (no stray tap gets in)
            TextView access = Ui.text(this, t, "For helpers: hold for settings", 15, t.sepia, Theme.italic);
            access.setGravity(Gravity.CENTER); access.setMinHeight(t.target(56)); access.setAlpha(.75f);
            access.setContentDescription("Settings, for helpers. Touch and hold for three seconds.");
            final Runnable openSettings = () -> startActivity(new Intent(this, AccessActivity.class));
            access.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { access.setText("Keep holding…"); ui.postDelayed(openSettings, 3000); }
                else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                    ui.removeCallbacks(openSettings); access.setText("For helpers: hold for settings"); }
                return true;
            });
            col.addView(access, Ui.wide(t.sdp(30)));
            return col;
        }

        // Seek
        LinearLayout bar = Ui.row(this);
        bar.setMinimumHeight(t.target(56));
        View glass = new View(this) {
            @Override protected void onDraw(Canvas c) {
                float s = getWidth() / 22f; Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setStyle(Paint.Style.STROKE); p.setColor(t.sepia);
                p.setStrokeWidth(1.3f * s); c.drawCircle(9 * s, 9 * s, 6 * s, p);
                p.setStrokeWidth(1.6f * s); p.setStrokeCap(Paint.Cap.ROUND); c.drawLine(13.5f * s, 13.5f * s, 19 * s, 19 * s, p);
                Paper.spark(c, 9 * s, 9 * s, 2.4f * s, t.gold);
            }
        };
        bar.addView(glass, Ui.lp(t.sdp(26), t.sdp(26)));
        seek = new EditText(this);
        seek.setHint(t.huge() ? "Find…" : "Find an app…"); seek.setHintTextColor(t.sepia); seek.setTextColor(t.ink); seek.setTypeface(Theme.regular);
        seek.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp(20)); seek.setBackground(null); seek.setSingleLine(true);
        seek.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); seek.setImeOptions(EditorInfo.IME_ACTION_GO);
        seek.setPadding(t.sdp(10), 0, t.sdp(10), 0);
        bar.addView(seek, Ui.weight());
        TextView clear = Ui.text(this, t, "×", 26, t.sepia, Theme.regular); clear.setGravity(Gravity.CENTER); clear.setMinWidth(t.target(48)); clear.setMinHeight(t.target(48));
        clear.setVisibility(View.GONE);
        Steady.tap(clear, t, v -> { seek.setText(""); hideKeyboard(); });
        bar.addView(clear);
        LinearLayout seekWrap = Ui.column(this);
        seekWrap.addView(bar, Ui.wide());
        View line = new View(this); line.setBackgroundColor(t.sepia); seekWrap.addView(line, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(1)));
        LinearLayout.LayoutParams sp = Ui.wide(t.sdp(14)); sp.leftMargin = sp.rightMargin = t.dp(22);
        col.addView(seekWrap, sp);
        seek.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { clear.setVisibility(e.length() > 0 ? View.VISIBLE : View.GONE); renderResults(); if (e.length() > 0) liftSeek(seekWrap); }
        });
        // the results must show above the keyboard: bring the search line up to the top of the screen
        seek.setOnFocusChangeListener((v, has) -> { if (has) liftSeek(seekWrap); });
        seek.setOnEditorActionListener((v, id, ev) -> { if (results.getChildCount() > 0 && results.getChildAt(0).getTag() instanceof Apps.App) { Apps.launch(this, (Apps.App) results.getChildAt(0).getTag()); } return true; });

        // the plates
        grid = new GridLayout(this);
        int cols = t.scale < 1.35f ? 3 : t.scale < 1.75f ? 2 : 1;     // and at 1 column the plates become an index (see renderGrid)
        grid.setColumnCount(cols);
        grid.setPadding(t.dp(14), t.sdp(14), t.dp(14), t.dp(10));
        col.addView(grid, Ui.wide());
        results = Ui.column(this); results.setVisibility(View.GONE);
        col.addView(results, Ui.wide(t.sdp(8)));

        TextView access = Ui.button(this, t, "✦  Settings", v -> startActivity(new Intent(this, AccessActivity.class)));
        LinearLayout.LayoutParams ap = Ui.wide(t.sdp(26)); ap.leftMargin = ap.rightMargin = t.dp(40);
        col.addView(access, ap);
        return col;
    }

    // ------------------------------------------------------------------ apps and plates
    private void loadApps() {
        bg.execute(() -> {
            List<Apps.App> a = Apps.all(this);
            java.util.Set<String> blocked = State.locked(this) ? State.packages(this) : new java.util.HashSet<>();
            List<Apps.App> open = new ArrayList<>();
            for (Apps.App x : a) if (!blocked.contains(x.pkg) && State.appAllowed(this, x.pkg)) open.add(x);
            List<Apps.Chapter> ch = Apps.chapters(this, open);
            ui.post(() -> { apps = a; chapters = ch; renderGrid(); renderResults(); });
        });
    }

    private void renderGrid() {
        if (grid == null) return;
        grid.removeAllViews(); plateViews.clear();
        int cols = grid.getColumnCount();
        if (cols == 1) { renderIndex(); return; }
        int W = getResources().getDisplayMetrics().widthPixels - t.dp(28);
        int cellW = W / cols, gap = t.dp(cols == 1 ? 40 : 6);
        int plateW = Math.min(cellW - gap * 2, t.dp(cols == 1 ? 220 : 400));
        List<TextView> names = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            Apps.Chapter c = chapters.get(i);
            LinearLayout cell = Ui.column(this); cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(gap, t.sdp(8), gap, t.sdp(8));
            ImageView img = new ImageView(this);
            // the plate, with the chapter's seal at its foot (a handset, a coin, a book…), so it's recognised at a glance
            FrameLayout plate = new FrameLayout(this);
            plate.addView(img, new FrameLayout.LayoutParams(plateW, Math.round(plateW * 320f / 200f)));
            int seal = Math.round(plateW * .3f);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(seal, seal, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); sp.bottomMargin = Math.round(plateW * .04f);
            plate.addView(Glyph.seal(this, t, c.name), sp);
            cell.addView(plate, Ui.lp(plateW, Math.round(plateW * 320f / 200f)));
            TextView name = Ui.centered(Ui.caps(this, t, c.name, 17, t.ink));
            Ui.ellipsize(name, 1);
            names.add(name);
            TextView n = Ui.centered(Ui.text(this, t, c.apps.size() + (c.apps.size() == 1 ? " app" : " apps"), 14, t.sepia, Theme.regular));
            cell.addView(name, Ui.wide(t.sdp(4))); cell.addView(n, Ui.wide(t.sdp(2)));
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams(); gp.width = cellW; gp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            grid.addView(cell, gp);
            final int idx = i;
            Steady.tap(cell, t, v -> openChapter(idx));
            cell.setContentDescription(c.name + ", " + c.apps.size() + " apps");
            plateViews.add(img);
        }
        // every name whole on one line ("READING & TV", not "READING & …"), and all of them the same size
        float fit = 1f;
        for (TextView nm : names) { float w = nm.getPaint().measureText(nm.getText().toString()); if (w > 0) fit = Math.min(fit, (cellW - gap * 2) / w); }
        if (fit < 1f) {
            float px = Math.max(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()), (float) Math.floor(names.get(0).getTextSize() * fit * .97f));
            for (TextView nm : names) nm.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        }
        drawPlates(plateW);
    }

    /** At big text sizes the chapters become an index: a small engraved plate beside each name, read like a list. */
    private void renderIndex() {
        int W = getResources().getDisplayMetrics().widthPixels - t.dp(28);
        int thumb = t.dp(62);
        for (int i = 0; i < chapters.size(); i++) {
            Apps.Chapter c = chapters.get(i);
            LinearLayout row = Ui.row(this); row.setPadding(t.dp(14), t.dp(10), t.dp(10), t.dp(10));
            row.setMinimumHeight(t.target(80));
            ImageView img = new ImageView(this);
            LinearLayout.LayoutParams ip = Ui.lp(thumb, Math.round(thumb * 1.6f)); ip.rightMargin = t.dp(16);
            FrameLayout plate = new FrameLayout(this);
            plate.addView(img, new FrameLayout.LayoutParams(thumb, Math.round(thumb * 1.6f)));
            int seal = Math.round(thumb * .62f);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(seal, seal, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            plate.addView(Glyph.seal(this, t, c.name), sp);
            row.addView(plate, ip);
            LinearLayout words = Ui.column(this);
            TextView nm = Ui.text(this, t, c.name, 24, t.ink, Theme.semi);
            // one line, shrinking only as much as it must (never below a comfortably large size), so no word is ever split
            nm.setMaxLines(1);
            nm.setAutoSizeTextTypeUniformWithConfiguration(Math.round(Math.min(t.sp(24), 26)), Math.round(t.sp(24)), 1, TypedValue.COMPLEX_UNIT_SP);
            words.addView(nm, Ui.wide());
            words.addView(Ui.text(this, t, c.apps.size() + (c.apps.size() == 1 ? " app" : " apps"), 16, t.sepia, Theme.italic), Ui.wide(t.dp(2)));
            row.addView(words, Ui.weight());
            row.addView(Ui.text(this, t, "›", 30, t.gold, Theme.regular));
            View rule = Ui.rule(this, t);
            LinearLayout cell = Ui.column(this); cell.addView(row, Ui.wide());
            LinearLayout.LayoutParams rp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(1)); rp.leftMargin = t.dp(20); rp.rightMargin = t.dp(20);
            cell.addView(rule, rp);
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams(); gp.width = W; gp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            grid.addView(cell, gp);
            final int idx = i;
            Steady.tap(row, t, v -> openChapter(idx));
            row.setContentDescription(c.name + ", " + c.apps.size() + " apps");
            plateViews.add(img);
        }
        drawPlates(thumb);
    }

    /** Draw the plates off the main thread; reuse today's from disk if they're already drawn. */
    private void drawPlates(int w) {
        final Theme th = t; final String k = (th.night ? "n" : "d") + Engraving.dayNumber() + "-" + w + "-" + th.contrast;
        final int n = plateViews.size();
        bg.execute(() -> {
            File dir = new File(Theme.cache(this), "plates"); dir.mkdirs();
            for (int i = 0; i < n; i++) {
                File f = new File(dir, k + "-" + i + ".png");
                Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
                if (b == null) {
                    b = Engraving.plate(i, w, th, true);
                    try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception ignored) {}
                }
                final Bitmap fb = b; final int fi = i;
                ui.post(() -> { if (fi < plateViews.size()) plateViews.get(fi).setImageBitmap(fb); });
            }
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (!f.getName().startsWith(k)) f.delete();
        });
    }

    private View appRow(Apps.App a) {
        LinearLayout r = Ui.row(this);
        r.setMinimumHeight(t.target(64)); r.setPadding(t.dp(26), t.dp(10), t.dp(26), t.dp(10));
        TextView mono = Ui.caps(this, t, a.letter(), 17, t.sepia); mono.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(t.paperHi); g.setStroke(t.dp(1), t.sepia); mono.setBackground(g);
        int ms = t.large() ? t.dp(48) : t.sdp(40);
        r.addView(mono, Ui.lp(ms, ms));
        if (t.large()) mono.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        TextView label = Ui.body(this, t, a.label, 22); Ui.ellipsize(label, 2);
        if (t.large()) {   // one line that shrinks just enough, so a word is never split
            label.setMaxLines(1);
            label.setAutoSizeTextTypeUniformWithConfiguration(24, Math.round(t.sp(22)), 1, TypedValue.COMPLEX_UNIT_SP);
        }
        LinearLayout.LayoutParams lp = Ui.weight(); lp.leftMargin = t.large() ? t.dp(16) : t.sdp(16);
        r.addView(label, lp);
        r.setTag(a);
        r.setContentDescription(a.label);
        Steady.tap(r, t, v -> Apps.launch(this, a));
        return r;
    }

    private void liftSeek(View wrap) {
        homeScroll.postDelayed(() -> {
            int to = Math.max(0, wrap.getTop() - t.dp(56));
            if (Math.abs(homeScroll.getScrollY() - to) > t.dp(8)) homeScroll.smoothScrollTo(0, to);
        }, 150);
    }

    private void renderResults() {
        if (results == null || seek == null) return;
        String q = seek == null ? "" : seek.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean seeking = !q.isEmpty();
        grid.setVisibility(seeking ? View.GONE : View.VISIBLE);
        results.setVisibility(seeking ? View.VISIBLE : View.GONE);
        results.removeAllViews();
        results.setMinimumHeight(seeking ? getResources().getDisplayMetrics().heightPixels : 0);   // room to lift the search line
        if (!seeking) return;
        List<Apps.App> hits = new ArrayList<>();
        for (Apps.App a : apps) if (a.label.toLowerCase(Locale.ROOT).contains(q)) hits.add(a);
        hits.sort((a, b) -> { int d = a.label.toLowerCase(Locale.ROOT).indexOf(q) - b.label.toLowerCase(Locale.ROOT).indexOf(q); return d != 0 ? d : a.label.compareToIgnoreCase(b.label); });
        if (hits.isEmpty()) { TextView e = Ui.text(this, t, "No app by that name.", 19, t.sepia, Theme.regular); e.setPadding(t.dp(26), t.sdp(20), t.dp(26), 0); results.addView(e); }
        for (Apps.App a : hits) {
            View r = appRow(a);
            if (!State.appAllowed(this, a.pkg)) {
                r.setAlpha(.5f);
                TextView nn = Ui.caps(this, t, "Not now", 13, t.sepia);
                ((LinearLayout) r).addView(nn);
                r.setContentDescription(a.label + ", not now: Clausus is on");
                Steady.tap(r, t, v -> startActivity(new Intent(this, NotNowActivity.class).putExtra("pkg", a.pkg)));
            }
            results.addView(r, Ui.wide());
        }
    }

    // ------------------------------------------------------------------ the notification summary
    // ------------------------------------------------------------------ Caretaken mode
    private LinearLayout careBox, peoplePage;

    /** Calls to return, favourites and the three apps on Home; everyone on the People page. */
    /** The Calls and Messages pages (read off the main thread). */
    private void renderPages() {
        if (callsPage != null) HomePages.fillCalls(this, t, callsPage, bg, ui);
        if (messagesPage != null) HomePages.fillMessages(this, t, messagesPage, bg, ui);
    }

    private void renderCare() {
        if (careBox != null) {
            final LinearLayout box = careBox;
            bg.execute(() -> {          // the call log and contacts are read off the main thread (a cold provider can take seconds)
                List<Missed.Call> w = Missed.waiting(this); List<CarePages.Person> f = CarePages.starred(this);
                ui.post(() -> { if (careBox == box && !isDestroyed()) CarePages.fill(this, t, box, this::renderCare, () -> deck.go(CALLS, true), w, f); });
            });
        }
        if (peoplePage != null) CarePages.fillPeople(this, t, peoplePage);
    }

    private void renderSummary() {
        if (summaryCard == null) return;
        summaryCard.removeAllViews();
        if (!Notes.enabled(this)) return;   // set up from Accessibility
        Summary s = Summary.of(this);
        TextView h = Ui.text(this, t, s.styled(t), 20, t.ink, Theme.regular);
        if (s.first.isEmpty() && s.quieter.isEmpty()) Ui.centered(h);
        summaryCard.addView(h, Ui.wide());
        if (s.first.isEmpty() && s.quieter.isEmpty()) return;
        TextView read = Ui.button(this, t, "Read it to me", v -> speak(Summary.of(this).spoken()));
        TextView see = Ui.button(this, t, "See them all", v -> openNotes());
        read.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(14)); see.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(14));
        if (Summary.canSpeak(this)) summaryCard.addView(Ui.pair(this, t, read, see), Ui.wide(t.sdp(10)));
        else summaryCard.addView(see, Ui.wide(t.sdp(10)));
    }

    private void speak(String text) {
        if (ttsReady) { tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "summary"); return; }
        ttsWaiting = text;                       // spoken the moment the voice is ready
        warmVoice();
    }

    private boolean ttsReady; private String ttsWaiting;

    /** Start the voice early (when the screen opens), so the first "Read it to me" speaks at once. */
    private void warmVoice() {
        if (tts != null || !Summary.canSpeak(this)) return;
        tts = new android.speech.tts.TextToSpeech(this, st -> {
            if (st != android.speech.tts.TextToSpeech.SUCCESS) return;
            tts.setSpeechRate(.9f); ttsReady = true;
            if (ttsWaiting != null) { tts.speak(ttsWaiting, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "summary"); ttsWaiting = null; }
        });
    }

    /** Everything Herbarium is holding, grouped: what needs the person first, then what can wait. */
    private void openNotes() {
        sheet.removeAllViews();
        Steady.Scroll sc = new Steady.Scroll(this, t);
        LinearLayout col = Ui.column(this); col.setPadding(t.dp(22), t.dp(48), t.dp(22), t.dp(60));
        Summary s = Summary.of(this);
        col.addView(Ui.centered(Ui.caps(this, t, "Letters", 30, t.ink)));
        lettersHeadline = Ui.text(this, t, s.styled(t), 19, t.ink, Theme.regular);
        col.addView(lettersHeadline, Ui.wide(t.sdp(8)));
        TextView back = Ui.button(this, t, "▾  Back", v -> closeSheet());
        TextView read = Ui.button(this, t, "Read it to me", v -> speak(Summary.of(this).spoken()));
        if (Summary.canSpeak(this)) col.addView(Ui.pair(this, t, back, read), Ui.wide(t.sdp(14)));
        else col.addView(back, Ui.wide(t.sdp(14)));
        if (!s.first.isEmpty()) { View h = Ui.heading(this, t, Summary.FIRST); h.setTag("heading"); col.addView(h); for (Summary.Group g : s.first) col.addView(noteCard(g), Ui.wide(t.sdp(8))); }
        if (!s.quieter.isEmpty()) { View h = Ui.heading(this, t, Summary.LATER); h.setTag("heading"); col.addView(h); for (Summary.Group g : s.quieter) col.addView(noteCard(g), Ui.wide(t.sdp(8))); }
        if (s.resting > 0) col.addView(Ui.text(this, t, s.restingLine(), 16, t.sepia, Theme.italic), Ui.wide(t.sdp(18)));
        if (!s.first.isEmpty() || !s.quieter.isEmpty())
            col.addView(Ui.button(this, t, "Clear them all", v -> { Notes.clearAll(this); closeSheet(); renderSummary(); }), Ui.wide(t.sdp(22)));
        sc.addView(col, Ui.wide());
        sheet.addView(sc, Ui.fill());
        sheet.setVisibility(View.VISIBLE);
        sheet.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        sheet.animate().translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(2)).start();
    }

    private TextView lettersHeadline;

    /** "Done": just this note fades and folds away; the rest stay where they are (no reload). */
    private void putAway(View card) {
        ViewGroup col = (ViewGroup) card.getParent();
        if (col == null) return;
        card.setEnabled(false);
        int h = card.getHeight();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f).setDuration(280);
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            card.setAlpha(f);
            ViewGroup.LayoutParams lp = card.getLayoutParams(); lp.height = Math.max(1, Math.round(h * f)); card.setLayoutParams(lp);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                int at = col.indexOfChild(card);
                col.removeView(card);
                // a section heading with no notes left under it goes too
                if (at > 0 && col.getChildAt(at - 1) != null && "heading".equals(col.getChildAt(at - 1).getTag())
                        && (at >= col.getChildCount() || !"note".equals(col.getChildAt(at).getTag()))) col.removeViewAt(at - 1);
                Summary s = Summary.of(HomeActivity.this);
                boolean any = !s.first.isEmpty() || !s.quieter.isEmpty();
                if (lettersHeadline != null) lettersHeadline.setText(any ? s.styled(t) : "All read.");
                renderSummary();
                if (!any) ui.postDelayed(HomeActivity.this::closeSheet, 700);
            }
        });
        a.start();
    }

    private View noteCard(Summary.Group g) {
        LinearLayout card = Ui.column(this);
        android.graphics.drawable.GradientDrawable bgd = new android.graphics.drawable.GradientDrawable(); bgd.setCornerRadius(t.sdp(16));
        bgd.setColor(t.night ? 0x332c3870 : 0x66f3ecda); bgd.setStroke(t.dp(1), 0x556e5d45); card.setBackground(bgd);
        card.setPadding(t.sdp(16), t.sdp(12), t.sdp(16), t.sdp(12));
        card.setTag("note");
        Notes.Note n = g.latest();
        card.addView(Ui.caps(this, t, n.app + "  ·  " + NuntiusPage.hhmm(n.when), 13, t.sepia));
        card.addView(Ui.body(this, t, g.cardLine(), 21), Ui.wide(t.sdp(4)));
        String d = g.cardDetail();
        if (!d.isEmpty()) card.addView(Ui.ellipsize(Ui.text(this, t, d, 18, t.sepia, Theme.regular), 4), Ui.wide(t.sdp(4)));
        TextView open = Ui.button(this, t, g.latest().key.startsWith(Notes.MISSED) ? "Call back" : "Open", v -> { Notes.open(this, n); for (Notes.Note x : g.notes) Notes.dismiss(this, x.key); closeSheet(); });
        TextView done = Ui.button(this, t, "Done", v -> { for (Notes.Note x : g.notes) Notes.dismiss(this, x.key); putAway(card); });
        card.addView(Ui.pair(this, t, open, done), Ui.wide(t.sdp(10)));
        return card;
    }

    // ------------------------------------------------------------------ a chapter's page
    private void openChapter(int i) {
        Apps.Chapter c = chapters.get(i);
        sheet.removeAllViews();
        Steady.Scroll sc = new Steady.Scroll(this, t);
        LinearLayout col = Ui.column(this); col.setPadding(0, t.dp(48), 0, t.dp(60));
        ImageView img = new ImageView(this);
        int pw = t.dp(t.large() ? 70 : 92);   // the plate stays modest, so the apps come first at big sizes
        LinearLayout.LayoutParams ip = Ui.lp(pw, Math.round(pw * 1.6f)); ip.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(img, ip);
        bg.execute(() -> { Bitmap b = Engraving.plate(i, pw, t, false); ui.post(() -> img.setImageBitmap(b)); });
        col.addView(Ui.centered(Ui.caps(this, t, c.name, 30, t.ink)), Ui.wide(t.sdp(4)));
        col.addView(Ui.centered(Ui.text(this, t, Engraving.flowerFor(i)[1] + " · " + Engraving.skyFor(i).name, 16, t.sepia, Theme.italic)), Ui.wide(t.sdp(4)));
        TextView back = Ui.button(this, t, "▾  Back", v -> closeSheet());
        LinearLayout.LayoutParams bp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); bp.gravity = Gravity.CENTER_HORIZONTAL; bp.topMargin = t.sdp(12);
        col.addView(back, bp);
        View rule = Ui.rule(this, t); LinearLayout.LayoutParams rp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(1)); rp.setMargins(t.dp(26), t.sdp(14), t.dp(26), t.sdp(6));
        col.addView(rule, rp);
        if (c.apps.isEmpty()) { TextView e = Ui.text(this, t, "Nothing here right now.", 19, t.sepia, Theme.regular); e.setPadding(t.dp(26), t.sdp(16), t.dp(26), 0); col.addView(e); }
        for (Apps.App a : c.apps) col.addView(appRow(a), Ui.wide());
        sc.addView(col, Ui.wide());
        sheet.addView(sc, Ui.fill());
        sheet.setVisibility(View.VISIBLE);
        sheet.setTranslationY(getResources().getDisplayMetrics().heightPixels);
        sheet.animate().translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(2)).start();
    }

    private void closeSheet() {
        sheet.animate().translationY(getResources().getDisplayMetrics().heightPixels).setDuration(220)
                .withEndAction(() -> { sheet.setVisibility(View.GONE); sheet.removeAllViews(); }).start();
    }

    // ------------------------------------------------------------------ time, weather, strip
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
    private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    /** "Tuesday, the 24th of November" */
    static String longDate() {
        Calendar d = Calendar.getInstance();
        return DAYS[d.get(Calendar.DAY_OF_WEEK) - 1] + ", the " + ord(d.get(Calendar.DAY_OF_MONTH)) + " of " + MONTHS[d.get(Calendar.MONTH)];
    }
    static String ord(int n) { int m = n % 100; return n + ((m >= 11 && m <= 13) ? "th" : (n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th")); }

    private void tickNow() {
        Calendar d = Calendar.getInstance();
        int h = d.get(Calendar.HOUR) == 0 ? 12 : d.get(Calendar.HOUR);
        String hm = h + ":" + String.format(Locale.US, "%02d", d.get(Calendar.MINUTE));
        if (time != null) time.setText(hm);
        if (date != null) date.setText(t.huge() ? DAYS[d.get(Calendar.DAY_OF_WEEK) - 1] + " " + d.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[d.get(Calendar.MONTH)]
                : longDate());
        if (greeting != null) {
            String n = Theme.prefs(this).getString("name", ""); int hh = d.get(Calendar.HOUR_OF_DAY);
            greeting.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
            greeting.setText((hh < 5 ? "Good night, " : hh < 12 ? "Good morning, " : hh < 17 ? "Good afternoon, " : "Good evening, ") + n);
        }
        if (season != null) season.setText(Engraving.SEASON_NAMES[Engraving.season(d)].toUpperCase(Locale.ROOT));
        if (stripTime != null) stripTime.setText(hm);
        if (!themeKey.equals(key(Theme.get(this)))) build();   // sunset, sunrise or a new day
    }

    private void updateStrip() {
        if (stripRight == null) return;
        stripRight.setText((State.locked(this) ? "🔒  " : "") + (battery < 0 ? "" : (charging ? "⚡ " : "") + battery + "%"));
    }

    private void updateWeather() {
        if (weatherLine == null) return;
        weatherLine.setText(t.large() ? Weather.line(this) : Weather.line(this).toUpperCase(Locale.ROOT));
        renderWeatherPage();
    }

    private void renderWeatherPage() {
        weatherPage.removeAllViews();
        JSONObject j = Weather.cached(this), cur = j.optJSONObject("current");
        weatherPage.addView(Ui.centered(Ui.caps(this, t, "Weather", 30, t.ink)));
        weatherPage.addView(Ui.centered(Ui.text(this, t, Weather.place(this), 17, t.sepia, Theme.italic)), Ui.wide(t.sdp(4)));
        if (cur == null) { weatherPage.addView(Ui.centered(Ui.text(this, t, "Looking at the sky…", 19, t.sepia, Theme.regular)), Ui.wide(t.sdp(30))); return; }
        LinearLayout now = Ui.row(this); now.setGravity(Gravity.CENTER);
        now.addView(Ui.text(this, t, Math.round(cur.optDouble("temperature_2m")) + "°", 72, t.ink, Theme.regular));
        LinearLayout what = Ui.column(this); what.setPadding(t.sdp(14), 0, 0, 0);
        TextView sky = Ui.body(this, t, Weather.words(cur.optInt("weather_code")), 22);
        TextView feels = Ui.text(this, t, "feels " + Math.round(cur.optDouble("apparent_temperature")) + "° · wind " + Math.round(cur.optDouble("wind_speed_10m")) + " mph", 16, t.sepia, Theme.regular);
        what.addView(sky); what.addView(feels);
        if (t.large()) {   // big text: the temperature, then the words under it (side by side they broke as "over / cast")
            now.setOrientation(LinearLayout.VERTICAL); what.setPadding(0, 0, 0, 0); what.setGravity(Gravity.CENTER_HORIZONTAL);
            Ui.centered(sky); Ui.centered(feels);
        }
        now.addView(what);
        weatherPage.addView(now, Ui.wide(t.sdp(18)));

        weatherPage.addView(Ui.heading(this, t, "The next hours"));
        JSONObject hr = j.optJSONObject("hourly");
        JSONArray ht = Weather.arr(hr, "time"), htemp = Weather.arr(hr, "temperature_2m"), hp = Weather.arr(hr, "precipitation_probability");
        LinearLayout hours = Ui.row(this);
        int shown = 0;
        int maxHours = t.huge() ? 3 : t.large() ? 4 : 6;
        for (int i = 0; i < ht.length() && shown < maxHours; i += (t.huge() ? 4 : t.large() ? 3 : 2), shown++) {
            LinearLayout c = Ui.column(this); c.setGravity(Gravity.CENTER_HORIZONTAL);
            String ts = ht.optString(i); int hh = Integer.parseInt(ts.substring(11, 13));
            c.addView(Ui.centered(Ui.text(this, t, (hh % 12 == 0 ? 12 : hh % 12) + (hh < 12 ? "a" : "p"), 15, t.sepia, Theme.regular)));
            c.addView(Ui.centered(Ui.text(this, t, Math.round(htemp.optDouble(i)) + "°", 19, t.ink, Theme.semi)), Ui.wide(t.sdp(4)));
            c.addView(Ui.centered(Ui.text(this, t, hp.optInt(i) > 10 ? hp.optInt(i) + "%" : " ", 14, t.sage, Theme.regular)), Ui.wide(t.sdp(2)));
            hours.addView(c, Ui.weight());
        }
        weatherPage.addView(hours, Ui.wide());

        weatherPage.addView(Ui.heading(this, t, "The week"));
        JSONObject dl = j.optJSONObject("daily");
        JSONArray dt = Weather.arr(dl, "time"), mx = Weather.arr(dl, "temperature_2m_max"), mn = Weather.arr(dl, "temperature_2m_min"),
                code = Weather.arr(dl, "weather_code"), rain = Weather.arr(dl, "precipitation_probability_max");
        for (int i = 0; i < dt.length(); i++) {
            LinearLayout r = Ui.row(this); r.setMinimumHeight(t.sdp(44));
            if (t.large()) {   // big text: the day on its own line, then the sky and the temperatures
                String dayName;
                try { Calendar c = Calendar.getInstance(); c.setTime(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dt.getString(i))); dayName = i == 0 ? "Today" : DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]; }
                catch (Exception e) { dayName = ""; }
                weatherPage.addView(Ui.text(this, t, dayName, 20, t.ink, Theme.semi), Ui.wide(t.sdp(10)));
                weatherPage.addView(Ui.text(this, t, Math.round(mx.optDouble(i)) + "° / " + Math.round(mn.optDouble(i)) + "° · " + Weather.words(code.optInt(i))
                        + (rain.optInt(i) >= 30 ? " · rain " + rain.optInt(i) + "%" : ""), 18, t.sepia, Theme.regular), Ui.wide(t.dp(2)));
                continue;
            }
            String day;
            try { Calendar c = Calendar.getInstance(); c.setTime(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dt.getString(i))); day = i == 0 ? "Today" : DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]; }
            catch (Exception e) { day = ""; }
            TextView dn = Ui.body(this, t, day, 19); r.addView(dn, Ui.lp(t.sdp(118), ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(Ui.text(this, t, Weather.words(code.optInt(i)), 17, t.sepia, Theme.regular), Ui.weight());
            r.addView(Ui.text(this, t, Math.round(mx.optDouble(i)) + "° / " + Math.round(mn.optDouble(i)) + "°", 19, t.ink, Theme.regular));
            weatherPage.addView(r, Ui.wide());
            if (rain.optInt(i) >= 30) weatherPage.addView(Ui.text(this, t, "   rain " + rain.optInt(i) + "%", 15, t.sage, Theme.regular));
        }
    }

    private void hideKeyboard() {
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (seek == null) return;
        if (im != null) im.hideSoftInputFromWindow(seek.getWindowToken(), 0);
        seek.clearFocus();
    }

    // ------------------------------------------------------------------ the pages, side by side
    /** Pages you can swipe between (or reach with the tabs at the bottom). A small, deliberate swipe is needed. */
    final class Deck extends FrameLayout {
        final List<View> pages = new ArrayList<>(); int page = 0;
        private float x0, y0, offset; private boolean dragging; private VelocityTracker vt;
        private ValueAnimator settling;
        /** One wide sheet: the paper, with Nuntius, Home and Weather laid on it side by side. Swiping moves only this. */
        private final FrameLayout board;
        private final int pageW, margin;

        Deck(Context c, ImageView paper, int screenW, int sheetW) {
            super(c);
            pageW = screenW; margin = Math.round(screenW * PAPER_MARGIN);
            board = new FrameLayout(c);
            board.addView(paper, new FrameLayout.LayoutParams(sheetW, ViewGroup.LayoutParams.MATCH_PARENT));
            addView(board, new FrameLayout.LayoutParams(sheetW, ViewGroup.LayoutParams.MATCH_PARENT));
            setClipChildren(true);
        }

        void addPage(View v) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pageW, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.leftMargin = margin + pages.size() * pageW;            // its place on the sheet
            pages.add(v); board.addView(v, lp); layoutPages();
        }

        /** Where the deck is, in pages (1.0 = Home exactly, 1.4 = most of the way to Weather): the tab mark follows this. */
        float position() { int w = Math.max(1, getWidth()); return page - offset / w; }

        private void layoutPages() {
            // the whole sheet moves as one (a single transform: nothing is redrawn, shown or hidden while it moves)
            float x = -margin - page * pageW + offset;
            float min = pageW - board.getLayoutParams().width;
            board.setTranslationX(Math.max(min, Math.min(0, x)));
            if (deck == this) tabsFollow(position());   // an old sheet (before a rebuild) never colours the new tabs
        }

        private void layers(boolean on) { /* moving one view's transform needs no extra layers */ }

        void go(int p, boolean anim) { go(p, anim, 0); }

        /** Settle on page p, carrying the finger's speed (px/s) so the glide continues the swipe instead of jumping. */
        void go(int p, boolean anim, float velocity) {
            if (settling != null) { settling.cancel(); settling = null; }
            int w = getWidth(); int was = page; page = Math.max(0, Math.min(pages.size() - 1, p));
            // keep the sheet exactly where the finger left it: the same place, measured from the new page
            float start = offset + (page - was) * w; offset = start;
            if (deck == this) tabColours(page);
            if (!anim || w == 0) { offset = 0; layoutPages(); layers(false); settled(); return; }
            final float distance = Math.abs(start), dir = start > 0 ? -1 : 1;   // the sheet travels toward rest (offset 0)
            // the finger's speed along the way it's going (px/ms; a flick the other way carries nothing), then the glide (Motion)
            final Motion.Glide glide = Motion.plan(distance, velocity * dir / 1000f, w);
            final float from = start;
            settling = ValueAnimator.ofFloat(0f, 1f);
            settling.setDuration(glide.ms);
            settling.setInterpolator(new android.view.animation.LinearInterpolator());
            settling.addUpdateListener(an -> { offset = from + dir * Motion.travelled(glide, distance, (float) an.getAnimatedValue(), calm); layoutPages(); });
            settling.addListener(new android.animation.AnimatorListenerAdapter() {
                boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator a) { if (cancelled) return; settling = null; offset = 0; layoutPages(); layers(false); settled(); }
            });
            settling.start();
            settling.setCurrentPlayTime(Math.min(glide.ms, 11));   // no held frame at the release: the glide carries straight on from the finger
        }

        private final android.view.animation.PathInterpolator calm = new android.view.animation.PathInterpolator(.33f, 0f, .2f, 1f);

        /** Work that waits until a page has settled, so it never stutters the glide. */
        private void settled() { if (deck == this) showTab(page); }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            // the page follows almost at once (a sticky start felt like nothing was happening); a trembling finger
            // still can't change pages: that takes a third of the screen or a deliberate flick
            float slop = t.steady ? t.dp(14) : t.dp(10);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getX(); y0 = e.getY(); dragging = false;
                    if (vt != null) vt.recycle(); vt = VelocityTracker.obtain(); vt.addMovement(e);
                    if (settling != null) {                       // caught mid-glide: hold it where it is and follow the finger from there
                        settling.cancel(); settling = null; dragging = true; x0 = e.getX() - offset; layers(true); return true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(e);
                    float dx = e.getX() - x0, dy = e.getY() - y0;
                    if (sheet.getVisibility() != VISIBLE && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                        dragging = true; x0 = e.getX() - offset; layers(true); return true;
                    }
                    return false;
                default: return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!dragging) return super.onTouchEvent(e);
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: x0 = e.getX() - offset; return true;
                case MotionEvent.ACTION_MOVE: {
                    float raw = e.getX() - x0;
                    // past the first or last page the deck resists, more the further it's pulled
                    boolean edge = (page == 0 && raw > 0) || (page == pages.size() - 1 && raw < 0);
                    float w = getWidth();
                    if (edge) offset = Math.signum(raw) * w * .18f * (1f - (float) Math.exp(-Math.abs(raw) / (w * .5f)));
                    else {
                        // one page at most: past 80% of the way the page slows under the finger and stops exactly on the
                        // next page, so the page after it never shows (a long swipe used to carry on past and spring back)
                        float a = Math.abs(raw), soft = w * .8f;
                        offset = Math.signum(raw) * (a <= soft ? a : soft + (w - soft) * (1f - (float) Math.exp(-(a - soft) / (w - soft))));
                    }
                    layoutPages(); return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                    float v = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); v = vt.getXVelocity(); }
                    int target = page;
                    // a short deliberate flick changes page; a tremble doesn't (too little, too back-and-forth)
                    float fling = t.dp(300), moved = t.dp(24);
                    if (v < -fling && offset < -moved) target = page + 1;
                    else if (v > fling && offset > moved) target = page - 1;
                    else if (offset < -getWidth() / 4f) target = page + 1;
                    else if (offset > getWidth() / 4f) target = page - 1;
                    dragging = false;
                    go(target, true, target == page ? 0 : v);
                    return true;
                }
            }
            return true;
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); layoutPages(); keepEdges(w, h); }

        /**
         * Android's "back" swipe from the screen edges would catch a page swipe that starts at the edge (and back means
         * nothing on Home). Android lets an app take back at most 200 dp of each edge, so Herbarium takes the middle
         * band, where a thumb starts a sideways swipe; the top and bottom of each edge still work as back.
         */
        private void keepEdges(int w, int h) {
            int band = Math.round(200 * getResources().getDisplayMetrics().density), edge = t.dp(40), mid = h / 2;
            List<android.graphics.Rect> r = new ArrayList<>();
            r.add(new android.graphics.Rect(0, mid - band / 2, edge, mid + band / 2));
            r.add(new android.graphics.Rect(w - edge, mid - band / 2, w, mid + band / 2));
            setSystemGestureExclusionRects(r);
        }
    }
}
