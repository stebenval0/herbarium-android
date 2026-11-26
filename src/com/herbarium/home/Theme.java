package com.herbarium.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;

import java.util.Calendar;

/**
 * Herbarium's colours (parchment by day, indigo by night), its typeface, and the text size the person chose.
 * Every size in the app goes through sp()/dp() here, so "Text size" makes everything bigger together.
 */
final class Theme {
    final boolean night;
    final int paper, paper2, paperHi, ink, sepia, sage, gold, ochre, leaf, plateWash, moon, press;
    final float washOp;
    final float scale;          // the person's text size: 1.0 = normal, up to 3.0
    final boolean steady;       // steadier tapping (on by default)
    final boolean contrast;     // stronger contrast
    /** How solid the shade's paper is: a little of what's underneath shows through. */
    static final float VEIL = .92f;
    private final float density;
    static Typeface regular, italic, semi, bold;

    private Theme(Context c, boolean night, float scale, boolean steady, boolean contrast) {
        this.night = night; this.scale = scale; this.steady = steady; this.contrast = contrast;
        density = c.getResources().getDisplayMetrics().density;
        if (!night) {
            paper = 0xffece4cf; paper2 = 0xffe2d8bd; paperHi = 0xfff3ecda;
            ink = contrast ? 0xff1e1812 : 0xff3a3127; sepia = contrast ? 0xff4a3d2c : 0xff6e5d45; sage = contrast ? 0xff3a5e46 : 0xff4f735a;   // deep enough to read on parchment (about 4.5:1)
            gold = 0xffb8913e; ochre = 0xffc9a24a; leaf = 0xff9cbf9f; plateWash = 0xffc9d8c5; moon = 0xffefe2b8; washOp = .78f; press = 0x337fa38a;
        } else {
            paper = 0xff161c38; paper2 = 0xff0c1026; paperHi = 0xff1e2650;
            ink = contrast ? 0xfffff6dc : 0xffeadfb8; sepia = contrast ? 0xffded2b0 : 0xffb8aa88; sage = contrast ? 0xffb4e0c6 : 0xff93c2a6;
            gold = 0xffe6c56e; ochre = 0xffe0b95a; leaf = 0xff5f8a76; plateWash = 0xff2c3870; moon = 0xfff3e6bd; washOp = .62f; press = 0x2693c2a6;
        }
        if (regular == null) {
            regular = Typeface.createFromAsset(c.getAssets(), "fonts/literata-400-normal.ttf");
            italic = Typeface.createFromAsset(c.getAssets(), "fonts/literata-400-italic.ttf");
            semi = Typeface.createFromAsset(c.getAssets(), "fonts/literata-600-normal.ttf");
            bold = Typeface.createFromAsset(c.getAssets(), "fonts/literata-700-normal.ttf");
        }
    }

    /** Storage that's readable straight after a restart, before the first unlock (so Herbarium is there from the start). */
    static Context store(Context c) {
        Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        return app.isDeviceProtectedStorage() ? app : app.createDeviceProtectedStorageContext();
    }
    private static volatile boolean moved;
    private static volatile SharedPreferences cachedPrefs;
    /** Herbarium's settings (asked for constantly, so the handle is kept rather than looked up each time). */
    static SharedPreferences prefs(Context c) {
        SharedPreferences sp = cachedPrefs;
        if (sp != null) return sp;
        Context dp = store(c);
        if (!moved) { moved = true; try { dp.moveSharedPreferencesFrom(c.getApplicationContext(), "herbarium"); } catch (Exception ignored) {} }
        return cachedPrefs = dp.getSharedPreferences("herbarium", Context.MODE_PRIVATE);
    }
    static java.io.File cache(Context c) { return store(c).getCacheDir(); }

    /** Night between sunset and sunrise (from the weather), or the person's fixed choice. */
    static boolean isNight(Context c) {
        SharedPreferences p = prefs(c);
        String mode = p.getString("look", "auto");
        if (mode.equals("day")) return false;
        if (mode.equals("night")) return true;
        long now = System.currentTimeMillis();
        long rise = p.getLong("sunrise", 0), set = p.getLong("sunset", 0);
        if (rise > 0 && set > 0 && Math.abs(now - rise) < 36 * 3600_000L) return now < rise || now >= set;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY), m = Calendar.getInstance().get(Calendar.MINUTE);
        int mins = h * 60 + m;
        return mins < 6 * 60 + 45 || mins >= 17 * 60 + 30;
    }

    /** Herbarium's night look whatever the hour: for resting screens (the screensaver). */
    static Theme night(Context c) {
        SharedPreferences p = prefs(c);
        return new Theme(c, true, p.getFloat("scale", 1f), p.getBoolean("steady", true), p.getBoolean("contrast", false));
    }

    static Theme get(Context c) {
        SharedPreferences p = prefs(c);
        return new Theme(c, isNight(c), p.getFloat("scale", 1f), p.getBoolean("steady", true), p.getBoolean("contrast", false));
    }

    /** Swap a colour the engravings are written in for this palette's version. */
    int map(int c) {
        switch (c) {
            case Engraving.INK: return ink;
            case Engraving.SEPIA: return sepia;
            case Engraving.SAGE: return sage;
            case Engraving.OCHRE: return ochre;
            case Engraving.GOLD: return gold;
            case Engraving.LEAF: return leaf;
            case Engraving.PLATEWASH: return plateWash;
            default: return c;
        }
    }

    /** Text sizes, scaled. Reading text grows fully with the person's size; big display type (the clock, titles)
     *  grows more gently, so it stays on one line, but never ends up smaller than the reading text. */
    float sp(float v) {
        if (v <= 24) return v * scale;
        return Math.max(v * (1 + (scale - 1) * .35f), 24 * scale);
    }
    /** Big sizes: lists instead of grids, one button per row, plain words instead of spaced capitals. */
    boolean large() { return scale >= 1.45f; }
    /** Button labels grow, but more gently than reading text, so a label never needs two lines. */
    float btn(float v) { return v * (1 + (scale - 1) * .5f); }
    boolean huge() { return scale >= 1.75f; }
    /** The steps the "smaller / larger" buttons move through. */
    static final float[] STEPS = {1f, 1.1f, 1.2f, 1.35f, 1.5f, 1.7f, 1.9f, 2.2f, 2.5f, 2.8f, 3f};
    static float step(float cur, int dir) {
        int i = 0; float best = 9;
        for (int k = 0; k < STEPS.length; k++) if (Math.abs(STEPS[k] - cur) < best) { best = Math.abs(STEPS[k] - cur); i = k; }
        return STEPS[Math.max(0, Math.min(STEPS.length - 1, i + dir))];
    }
    int dp(float v) { return Math.round(v * density); }             // fixed sizes
    int sdp(float v) { return Math.round(v * density * scale); }     // sizes that grow with the text
    /** A touch target never smaller than 64 dp, whatever the design says. */
    int target(float v) { return Math.max(dp(64), Math.round(v * density * (1 + (scale - 1) * .35f))); }
    static float px(Context c, float sp) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, c.getResources().getDisplayMetrics()); }
}
