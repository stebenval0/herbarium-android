package com.herbarium.home;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Calendar;
import java.util.Random;

/** The paper behind every Herbarium screen, and the little drawings (moon, seek glass, spark). */
final class Paper {
    private Paper() {}
    /** Painted papers by size and day/night, shared by Home, the lock, the shade and the screensaver. */
    private static final java.util.Map<String, Bitmap> painted = new java.util.HashMap<>();

    private static String key(int w, int h, Theme t) { return w + "x" + h + (t.night ? "n" : "d"); }

    /** Night paper is dark and day paper is light; a saved paper that's the wrong one is thrown away. */
    private static boolean matches(Bitmap b, Theme t) {
        int p = b.getPixel(b.getWidth() / 2, b.getHeight() / 3);
        if ((p >>> 24) != 255) return false;                       // paper is never see-through
        int lum = (((p >> 16) & 255) * 3 + ((p >> 8) & 255) * 6 + (p & 255)) / 10;
        return t.night ? lum < 110 : lum > 140;
    }

    /** The paper if it's already painted (in memory or saved on the phone), else null. */
    static Bitmap ready(android.content.Context c, int w, int h, Theme t) {
        String key = key(w, h, t);
        synchronized (painted) { Bitmap b = painted.get(key); if (b != null) return b; }
        java.io.File f = new java.io.File(Theme.cache(c), "paper-" + key + ".png");
        if (f.exists()) {
            Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath());
            if (b != null && matches(b, t)) { synchronized (painted) { painted.put(key, b); } return b; }
            f.delete();
        }
        return null;
    }

    /** Paint the paper (slow: blur and grain), keep it in memory and save it for the next start. */
    static Bitmap paint(android.content.Context ctx, int w, int h, Theme t) {
        Bitmap b = background(w, h, t);
        java.io.File f = new java.io.File(Theme.cache(ctx), "paper-" + key(w, h, t) + ".png");
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception ignored) {}
        return b;
    }

    /** A quick stand-in (just the gradient) for the very first frame. */
    static android.graphics.drawable.Drawable quick(Theme t) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{t.paperHi, t.paper, t.paper2}); g.setGradientCenter(.5f, .18f); g.setGradientRadius(2200);
        return g;
    }

    static Bitmap background(int w, int h, Theme t) {
        String key = key(w, h, t);
        synchronized (painted) { Bitmap old = painted.get(key); if (old != null) return old; }
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        // radial gradient: bright near the top, deepening to the edges
        Paint g = new Paint();
        Matrix m = new Matrix(); m.setScale(1f, (h * .6f) / (w * .9f), w / 2f, h * .18f);
        RadialGradient rg = new RadialGradient(w / 2f, h * .18f, w * .9f, new int[]{t.paperHi, t.paper, t.paper2}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
        rg.setLocalMatrix(m); g.setShader(rg); c.drawRect(0, 0, w, h, g);
        // three soft watercolour washes
        float op = t.night ? .12f : .26f;
        Paint wc = new Paint(Paint.ANTI_ALIAS_FLAG); wc.setMaskFilter(new BlurMaskFilter(w * .12f, BlurMaskFilter.Blur.NORMAL));
        float[][] blobs = {{.15f, .2f, .45f}, {.9f, .55f, .5f}, {.3f, .9f, .5f}};
        int[] cols = {0xffa9c9bf, 0xffb9b0d2, 0xffd9c9a0};
        for (int i = 0; i < 3; i++) { wc.setColor(cols[i]); wc.setAlpha((int) (255 * op)); c.drawCircle(w * blobs[i][0], h * blobs[i][1], w * blobs[i][2], wc); }
        // grain
        Bitmap noise = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Random r = new Random(3); int[] px = new int[128 * 128];
        for (int i = 0; i < px.length; i++) px[i] = ((int) (r.nextFloat() * (t.night ? 40 : 56)) << 24) | 0x6b5c45;
        noise.setPixels(px, 0, 128, 0, 0, 128, 128);
        Paint gr = new Paint(); gr.setShader(new BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        gr.setBlendMode(android.graphics.BlendMode.MULTIPLY);   // keeps the paper opaque (PorterDuff MULTIPLY multiplied the alpha too)
        c.drawRect(0, 0, w, h, gr);
        synchronized (painted) { painted.put(key, b); }
        return b;
    }

    /** Today's moon phase, as on the web version: an outlined disc with its lit part filled. */
    static void moon(Canvas c, float cx, float cy, float r, Theme t) {
        double syn = 29.530588853;
        Calendar ref = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); ref.clear(); ref.set(2000, 0, 6, 18, 14);
        double days = (System.currentTimeMillis() - ref.getTimeInMillis()) / 864e5;
        double f = ((days % syn) + syn) % syn / syn;
        double k = Math.cos(f * 2 * Math.PI); boolean waxing = f < .5;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // lit part: one half-disc plus (or minus) a half-ellipse
        Path lit = new Path();
        RectF disc = new RectF(cx - r, cy - r, cx + r, cy + r);
        float ex = (float) Math.abs(k) * r; RectF ell = new RectF(cx - ex, cy - r, cx + ex, cy + r);
        lit.moveTo(cx, cy - r);
        lit.arcTo(disc, -90, waxing ? 180 : -180);              // the bright limb
        boolean bulge = (k > 0) == waxing;                        // crescent (thin) or gibbous (fat)
        lit.arcTo(ell, 90, waxing == bulge ? -180 : 180);
        lit.close();
        p.setColor(t.moon); c.drawPath(lit, p);
        p.setStyle(Paint.Style.STROKE); p.setColor(t.sepia); p.setStrokeWidth(r / 11f * .6f); c.drawPath(lit, p);
        p.setStrokeWidth(r / 11f); c.drawCircle(cx, cy, r, p);
    }

    static void spark(Canvas c, float cx, float cy, float s, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color);
        c.save(); c.translate(cx, cy); c.drawPath(Engraving.path(Engraving.spark(s)), p); c.restore();
    }
}
