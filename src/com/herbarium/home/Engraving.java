package com.herbarium.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;

/**
 * Herbarium's engraved botanical plates, drawn with Android's own canvas (ported line for line from the
 * web version). Each plate is drawn once into a bitmap and reused, so scrolling never redraws a flower.
 */
final class Engraving {
    private Engraving() {}

    // the colours the drawings are written in; they're swapped for the day or night palette when drawn
    static final int INK = 0xff3a3127, SEPIA = 0xff6e5d45, SAGE = 0xff7fa38a, OCHRE = 0xffc9a24a, GOLD = 0xffb8913e,
            LEAF = 0xff9cbf9f, PLATEWASH = 0xffc9d8c5;

    private static final Map<String, Path> PATHS = new HashMap<>();
    static Path path(String d) {
        Path p = PATHS.get(d);
        if (p == null) { p = PathData.parse(d); if (PATHS.size() < 4000) PATHS.put(d, p); }
        return p;
    }

    /** A seeded random generator, the same sequence as the web version's. */
    static final class Rnd { long s; Rnd(long seed) { s = seed; } float next() { s = (s * 16807L) % 2147483647L; return s / 2147483647f; } }

    // ------------------------------------------------------------------ the engraving kit
    static final class Kit {
        final Canvas c; final Theme t; int wash; final Rnd rnd;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Kit(Canvas c, Theme t, int wash, Rnd rnd) {
            this.c = c; this.t = t; this.wash = wash; this.rnd = rnd;
            stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
        }
        Kit with(int w) { Kit k = new Kit(c, t, w, rnd); return k; }
        int col(int c0) { return t.map(c0); }

        void ink(String d) { ink(d, 1.15f, 1f); }
        void ink(String d, float w) { ink(d, w, 1f); }
        void ink(String d, float w, float op) { stroke.setColor(col(INK)); stroke.setAlpha((int) (255 * op)); stroke.setStrokeWidth(w); c.drawPath(path(d), stroke); }
        void hatch(String d) { stroke.setColor(col(INK)); stroke.setAlpha((int) (255 * .7f)); stroke.setStrokeWidth(.55f); c.drawPath(path(d), stroke); }
        void wash(String d) { wash(d, wash); }
        void wash(String d, int color) { fill.setColor(col(color)); fill.setAlpha((int) (255 * t.washOp)); c.drawPath(path(d), fill); }
        void stem(String d) { ink(d, 1.3f); }
        void stem(String d, float w) { ink(d, w); }
        void dot(float x, float y, float r) { dot(x, y, r, OCHRE); }
        void dot(float x, float y, float r, int color) {
            fill.setColor(col(color)); fill.setAlpha(255); c.drawCircle(x, y, r, fill);
            stroke.setColor(col(INK)); stroke.setAlpha(255); stroke.setStrokeWidth(.6f); c.drawCircle(x, y, r, stroke);
        }
        void solid(float x, float y, float r, int color, float op) { fill.setColor(col(color)); fill.setAlpha((int) (255 * op)); c.drawCircle(x, y, r, fill); }
        void ring(float x, float y, float r, float w) { stroke.setColor(col(INK)); stroke.setAlpha(255); stroke.setStrokeWidth(w); c.drawCircle(x, y, r, stroke); }
        void ellipse(float cx, float cy, float rx, float ry, Integer fillColor, float strokeW) {
            RectF o = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
            if (fillColor != null) { fill.setColor(col(fillColor)); fill.setAlpha(255); c.drawOval(o, fill); }
            if (strokeW > 0) { stroke.setColor(col(INK)); stroke.setAlpha(255); stroke.setStrokeWidth(strokeW); c.drawOval(o, stroke); }
        }
        void washOval(float cx, float cy, float rx, float ry, int color) {
            fill.setColor(col(color)); fill.setAlpha((int) (255 * t.washOp)); c.drawOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), fill);
        }
        void line(float x1, float y1, float x2, float y2, int color, float w) {
            stroke.setColor(col(color)); stroke.setAlpha(255); stroke.setStrokeWidth(w); c.drawLine(x1, y1, x2, y2, stroke);
        }
        void colored(String d, int color, float w, float op) { stroke.setColor(col(color)); stroke.setAlpha((int) (255 * op)); stroke.setStrokeWidth(w); c.drawPath(path(d), stroke); }

        static String petal(float len, float w) {
            return "M0 0 C " + f(w * .9f) + " " + f(-len * .3f) + " " + f(w) + " " + f(-len * .8f) + " 0 " + f(-len) + " C " + f(-w) + " " + f(-len * .8f) + " " + f(-w * .9f) + " " + f(-len * .3f) + " 0 0 Z";
        }
        void leaf(float x, float y, float len, float ang, float w) { leaf(x, y, len, ang, w, LEAF); }
        void leaf(float x, float y, float len, float ang, float w, int color) {
            c.save(); c.translate(x, y); c.rotate(ang);
            Kit sub = with(color);
            String d = "M0 0 Q " + f(len / 2) + " " + f(-w) + " " + f(len) + " 0 Q " + f(len / 2) + " " + f(w) + " 0 0 Z";
            sub.wash(d, color); sub.ink(d, 1); sub.hatch("M0 0 Q " + f(len / 2) + " " + f(-w * .15f) + " " + f(len * .96f) + " 0");
            for (float tt = .25f; tt < .85f; tt += .2f) sub.hatch("M " + f(len * tt) + " " + f(-w * .05f) + " L " + f(len * (tt + .1f)) + " " + f(-w * .62f));
            c.restore();
        }
        void whorl(float x, float y, int n, float len, float w) { whorl(x, y, n, len, w, 0, 1, wash, true); }
        void whorl(float x, float y, int n, float len, float w, float rot) { whorl(x, y, n, len, w, rot, 1, wash, true); }
        void whorl(float x, float y, int n, float len, float w, float rot, float sy, int color, boolean vein) {
            c.save(); c.translate(x, y); c.scale(1, sy); c.rotate(rot);
            Kit pk = with(color); String d = petal(len, w);
            for (int i = 0; i < n; i++) {
                c.save(); c.rotate(i * 360f / n);
                pk.wash(d, color); pk.ink(d, 1); if (vein) pk.hatch("M0 -3 L0 " + f(-len * .6f));
                c.restore();
            }
            c.restore();
        }
        String ruffle(float cx, float cy, float r, float sy, int n, float amp) {
            StringBuilder d = new StringBuilder();
            for (int i = 0; i <= n * 2; i++) {
                double a = i / (n * 2.0) * Math.PI * 2;
                float rr = r + (i % 2 == 1 ? amp : -amp * .4f) + (rnd.next() - .5f) * amp * .6f;
                d.append(i > 0 ? " L " : "M ").append(f((float) (cx + rr * Math.cos(a)))).append(' ').append(f((float) (cy + rr * Math.sin(a) * sy)));
            }
            return d.append(" Z").toString();
        }
    }

    static String f(float v) { return (Math.abs(v - Math.round(v)) < 1e-4) ? Integer.toString(Math.round(v)) : String.format(java.util.Locale.US, "%.2f", v); }
    static String heart(float x, float y, float s) {
        return "M" + f(x) + " " + f(y) + " C " + f(x - s) + " " + f(y - s * .5f) + " " + f(x - s * .7f) + " " + f(y - s * 1.3f) + " " + f(x) + " " + f(y - s * .8f)
                + " C " + f(x + s * .7f) + " " + f(y - s * 1.3f) + " " + f(x + s) + " " + f(y - s * .5f) + " " + f(x) + " " + f(y) + " Z";
    }

    // ------------------------------------------------------------------ the flowers (200×300 box, ground at y=300)
    static void draw(String name, Kit k) {
        Canvas c = k.c;
        switch (name) {
            case "Carnation": {
                k.stem("M100 300 C 96 250 104 200 100 150"); k.stem("M100 206 C 118 192 128 176 134 156");
                k.leaf(100, 272, 62, -152, 6); k.leaf(100, 262, 60, -28, 6); k.leaf(100, 222, 46, -140, 5); k.leaf(100, 214, 46, -38, 5);
                k.wash("M92 152 L94 130 L106 130 L108 152 Z", LEAF); k.ink("M92 152 L94 130 L106 130 L108 152 Z");
                k.hatch("M97 150 L97 133 M103 150 L103 133");
                for (float[] ry : new float[][]{{40, 112}, {30, 106}, {18, 100}}) { String d = k.ruffle(100, ry[1], ry[0], .62f, 16, 4); k.wash(d); k.ink(d, .95f); }
                for (int i = 0; i < 6; i++) k.hatch("M " + (78 + i * 9) + " 118 l 3 -10");
                String bud = "M130 160 C 126 146 132 134 138 132 C 144 138 142 152 136 160 Z"; k.wash(bud); k.ink(bud, 1);
                break;
            }
            case "Tulip": {
                k.stem("M100 300 C 100 260 98 210 100 168");
                k.leaf(100, 296, 98, -112, 15); k.leaf(100, 290, 92, -66, 15);
                for (String d : new String[]{"M80 170 C 66 152 70 120 84 110 C 88 130 92 150 100 172 Z", "M120 170 C 134 152 130 120 116 110 C 112 130 108 150 100 172 Z",
                        "M84 168 C 80 136 90 108 100 100 C 110 108 120 136 116 168 C 108 176 92 176 84 168 Z"}) { k.wash(d); k.ink(d, 1.05f); }
                k.hatch("M92 160 C 90 140 94 122 100 110 M108 160 C 110 140 106 122 100 110 M100 168 L100 118");
                break;
            }
            case "Violet": {
                for (float[] h : new float[][]{{66, 296, 34}, {136, 292, 30}}) { String d = heart(h[0], h[1], h[2]); k.wash(d, LEAF); k.ink(d); k.hatch("M" + f(h[0]) + " " + f(h[1]) + " L " + f(h[0]) + " " + f(h[1] - h[2] * .75f)); }
                k.stem("M100 300 C 100 240 80 200 70 168"); k.stem("M100 300 C 104 230 126 190 136 156");
                for (float[] w : new float[][]{{70, 156, 26}, {136, 142, 50}}) { k.whorl(w[0], w[1], 5, 19, 10, w[2]); k.dot(w[0], w[1], 3); }
                String bud = "M100 210 C 106 198 112 194 116 196 C 116 204 110 210 100 210 Z"; k.wash(bud); k.ink(bud, .9f);
                break;
            }
            case "Marigold": {
                k.stem("M100 300 C 102 250 98 200 100 152");
                for (float[] b : new float[][]{{100, 254, 52, 214, -1}, {100, 214, 148, 182, 1}}) {
                    float x0 = b[0], y0 = b[1], x1 = b[2], y1 = b[3], side = b[4];
                    k.ink("M" + f(x0) + " " + f(y0) + " Q " + f((x0 + x1) / 2) + " " + f(y0 - 6) + " " + f(x1) + " " + f(y1), 1);
                    for (float tt = .25f; tt <= 1.001f; tt += .25f) { float x = x0 + (x1 - x0) * tt, y = y0 + (y1 - y0) * tt - 3; k.leaf(x, y, 16, side > 0 ? -70 : -110, 4); k.leaf(x, y, 15, side > 0 ? 30 : 150, 4); }
                }
                Object[][] rings = {{42f, 112f, null}, {32f, 108f, 0xffc98d3a}, {20f, 104f, null}};
                for (Object[] r : rings) { String d = k.ruffle(100, (float) r[1], (float) r[0], .72f, 18, 4.5f); if (r[2] != null) k.wash(d, (int) r[2]); else k.wash(d); k.ink(d, .95f); }
                for (int i = 0; i < 9; i++) k.solid(92 + (i % 3) * 8, 98 + (i / 3) * 6, 1.3f, INK, 1);
                break;
            }
            case "Sunflower": {
                k.stem("M100 300 C 102 250 98 200 100 160", 1.8f);
                k.leaf(100, 250, 56, -160, 20); k.leaf(100, 214, 52, -20, 19);
                k.whorl(100, 110, 18, 40, 8, 0, .9f, k.wash, false);
                k.washOval(100, 110, 24, 21, 0xff8a5a2b);
                k.ellipse(100, 110, 24, 21, null, 1.1f);
                for (int i = 0; i < 30; i++) { double a = i * 2.4, r = Math.sqrt(i) * 3.8; k.solid((float) (100 + r * Math.cos(a)), (float) (110 + r * Math.sin(a) * .88), 1.2f, INK, 1); }
                break;
            }
            case "Water Lily": {
                String pad = "M28 274 C 30 254 170 254 172 274 C 170 292 30 292 28 274 Z";
                k.wash(pad, LEAF); k.ink(pad); k.ink("M100 274 L 132 260", 1);
                for (int i = 0; i < 6; i++) k.hatch("M" + (44 + i * 22) + " 280 l 10 -6");
                k.hatch("M14 300 h 40 M140 302 h 44 M70 310 h 60");
                c.save(); c.translate(100, 248); c.scale(1, .78f);
                for (Object[] ring : new Object[][]{{new float[]{-78, -52, -26, 0, 26, 52, 78}, 54f, 12f}, {new float[]{-44, -14, 14, 44}, 38f, 11f}}) {
                    float len = (float) ring[1], w = (float) ring[2];
                    for (float a : (float[]) ring[0]) { c.save(); c.rotate(a); String d = Kit.petal(len, w); k.wash(d); k.ink(d, 1.1f); k.hatch("M0 -6 L0 " + f(-len * .7f)); c.restore(); }
                }
                c.restore();
                for (int i = -3; i <= 3; i++) k.line(100 + i * 3, 240, 100 + i * 4.5f, 228, OCHRE, 2);
                break;
            }
            case "Rose": {
                k.stem("M100 300 C 96 250 104 200 100 152");
                for (int y : new int[]{270, 236, 200}) { boolean odd = y % 2 == 1; k.ink("M" + (odd ? 99 : 101) + " " + y + " l " + (odd ? -6 : 6) + " -3 l " + (odd ? 6 : -6) + " -3", .9f); }
                k.ink("M100 250 L 66 228", 1); k.ink("M100 222 L 136 200", 1);
                k.leaf(66, 228, 26, -150, 8); k.leaf(80, 238, 22, -110, 7); k.leaf(80, 238, 22, 170, 7);
                k.leaf(136, 200, 26, -30, 8); k.leaf(122, 210, 22, -70, 7); k.leaf(122, 210, 22, 10, 7);
                k.wash("M92 156 L 100 140 L 108 156 Z", LEAF);
                String cup = "M62 116 C 60 154 140 154 138 116 C 132 92 114 84 100 92 C 86 84 68 92 62 116 Z";
                k.wash(cup); k.ink(cup, 1.1f);
                k.ink("M100 112 c 8 -4 12 6 4 10 c -10 6 -18 -6 -12 -14 c 8 -10 26 -6 28 8 c 2 14 -14 24 -28 18 c -16 -6 -18 -28 -4 -36", 1);
                k.hatch("M70 128 C 76 140 88 146 96 146 M130 128 C 124 140 112 146 104 146");
                break;
            }
            case "Lily": {
                k.stem("M100 300 C 102 240 98 180 104 128");
                for (float[] l : new float[][]{{100, 290, -120}, {101, 272, -60}, {100, 252, -128}, {100, 236, -52}, {101, 212, -124}}) k.leaf(l[0], l[1], 42, l[2], 5);
                k.stem("M101 196 C 90 186 80 182 70 182");
                for (float[] L : new float[][]{{104, 128, 18, 1}, {70, 182, -62, .78f}}) {
                    c.save(); c.translate(L[0], L[1]); c.rotate(L[2]); c.scale(L[3], L[3]);
                    for (float a : new float[]{-34, 0, 34}) {
                        c.save(); c.rotate(a);
                        String d2 = "M0 0 C 9 -14 11 -36 2 -54 C -10 -40 -10 -16 0 0 Z"; k.wash(d2); k.ink(d2, 1); k.ink("M2 -54 C 6 -60 12 -58 12 -54", 1); k.hatch("M0 -6 L1 -40");
                        c.restore();
                    }
                    for (float a : new float[]{-14, -4, 6, 16}) {
                        double r = Math.toRadians(a); float tx = (float) (Math.sin(r) * 46), ty = (float) (-Math.cos(r) * 46);
                        k.line(0, -6, tx, ty, INK, .6f);
                        c.save(); c.rotate(a, tx, ty); k.ellipse(tx, ty, 1.8f, 4, OCHRE, 0); c.restore();
                    }
                    c.restore();
                }
                break;
            }
            case "Magnolia": {
                k.stem("M100 300 C 98 260 104 220 100 168", 2.2f);
                k.ink("M100 262 C 86 252 74 250 62 252", 1.6f);
                k.leaf(100, 250, 70, -152, 19); k.leaf(100, 232, 66, -24, 19); k.leaf(100, 200, 52, -128, 15); k.leaf(62, 252, 40, 170, 12);
                c.save(); c.translate(100, 166);
                for (float[] p : new float[][]{{-58, 54, 20}, {58, 54, 20}, {0, 60, 22}, {-28, 50, 22}, {28, 50, 22}}) {
                    c.save(); c.rotate(p[0]); String d = Kit.petal(p[1], p[2]); k.wash(d); k.ink(d, 1.1f); k.hatch("M0 -8 L0 " + f(-p[1] * .55f)); c.restore();
                }
                k.ellipse(0, -36, 6, 13, OCHRE, .8f);
                c.restore();
                break;
            }
            case "Iris": {
                k.stem("M100 300 L 100 164");
                k.leaf(100, 300, 150, -98, 8); k.leaf(100, 300, 130, -82, 8); k.leaf(100, 300, 108, -114, 7);
                c.save(); c.translate(100, 160);
                for (float a : new float[]{-22, 22, 0}) { c.save(); c.rotate(a); String d = Kit.petal(a != 0 ? 44 : 50, 13); k.wash(d); k.ink(d, 1.05f); k.hatch("M0 -6 L0 -30"); c.restore(); }
                for (String d : new String[]{"M0 2 C -30 -8 -48 10 -42 34 C -36 44 -16 28 0 6 Z", "M0 2 C 30 -8 48 10 42 34 C 36 44 16 28 0 6 Z", "M0 4 C -9 22 -7 44 0 52 C 7 44 9 22 0 4 Z"}) { k.wash(d); k.ink(d, 1.05f); }
                for (String d : new String[]{"M-6 6 L -26 24", "M6 6 L 26 24", "M0 10 L 0 34"}) k.colored(d, OCHRE, 3, .9f);
                c.restore();
                break;
            }
            case "Daffodil": {
                k.stem("M100 300 C 100 250 98 200 100 160");
                k.leaf(100, 300, 120, -104, 7); k.leaf(100, 300, 110, -76, 7);
                k.ink("M100 160 C 100 150 104 144 110 142", 1.1f);
                k.whorl(112, 128, 6, 28, 11, 0, .9f, k.wash, true);
                String tr = "M101 128 L 104 104 C 108 98 120 98 124 104 L 125 128 Z"; k.wash(tr, 0xffe0a83e); k.ink(tr, 1.1f);
                String rim = k.ruffle(114, 102, 12, .38f, 10, 1.8f); k.wash(rim, 0xffe0a83e); k.ink(rim, .9f);
                k.hatch("M108 124 L 110 106 M118 124 L 117 106");
                break;
            }
            case "Snowdrop": {
                k.leaf(100, 300, 110, -96, 5); k.leaf(100, 300, 96, -80, 5); k.leaf(96, 300, 80, -112, 5);
                Object[][] heads = {{"M100 300 C 100 220 104 180 122 160 C 130 154 136 160 136 168", 136f, 168f, 1f}, {"M98 300 C 96 240 82 206 68 196 C 60 192 56 196 57 204", 57f, 204f, .85f}};
                for (Object[] h : heads) {
                    k.stem((String) h[0]);
                    c.save(); c.translate((float) h[1], (float) h[2]); c.rotate(180); c.scale((float) h[3], (float) h[3]);
                    for (float a : new float[]{-26, 0, 26}) { c.save(); c.rotate(a); String d = Kit.petal(30, 9); k.wash(d); k.ink(d, 1); c.restore(); }
                    k.ellipse(0, -16, 5, 8, LEAF, .8f);
                    c.restore();
                }
                break;
            }
            case "Holly": {
                k.stem("M100 300 C 100 250 98 210 100 172", 2);
                float[][] pts = {{.2f, .9f}, {.3f, .5f}, {.45f, 1.1f}, {.55f, .55f}, {.7f, 1.05f}, {.8f, .45f}};
                for (float[] h : new float[][]{{100, 258, -150, 66, 14}, {100, 240, -32, 64, 14}, {100, 206, -136, 54, 12}, {100, 196, -48, 54, 12}, {100, 176, -96, 44, 11}}) {
                    float len = h[3], w = h[4];
                    StringBuilder d = new StringBuilder("M0 0 ");
                    for (float[] p : pts) d.append("L ").append(f(len * p[0])).append(' ').append(f(-w * p[1])).append(' ');
                    d.append("L ").append(f(len)).append(" 0 ");
                    for (int i = pts.length - 1; i >= 0; i--) d.append("L ").append(f(len * pts[i][0])).append(' ').append(f(w * pts[i][1])).append(' ');
                    d.append("Z");
                    c.save(); c.translate(h[0], h[1]); c.rotate(h[2]);
                    Kit lk = k.with(LEAF); lk.wash(d.toString(), LEAF); lk.ink(d.toString(), 1); lk.hatch("M0 0 L " + f(len * .95f) + " 0");
                    c.restore();
                }
                for (float[] b : new float[][]{{92, 172}, {104, 166}, {112, 176}, {98, 182}, {86, 162}}) {
                    k.washOval(b[0], b[1], 7, 7, k.wash); k.ring(b[0], b[1], 7, 1); k.solid(b[0] - 2, b[1] - 2, 1.4f, INK, .5f);
                }
                break;
            }
            case "Hibiscus": {
                k.stem("M100 300 C 98 250 102 210 100 170", 1.6f);
                k.leaf(100, 252, 54, -150, 16); k.leaf(100, 226, 50, -30, 15);
                k.whorl(100, 126, 5, 46, 26, 18, .92f, k.wash, true);
                k.ink("M100 126 C 108 112 116 98 126 86", 1.4f);
                for (float[] d : new float[][]{{126, 86}, {122, 92}, {130, 90}, {118, 98}, {127, 80}}) k.dot(d[0], d[1], 2.2f);
                k.dot(100, 126, 5, 0xff9a3b3b);
                break;
            }
            case "Cherry Blossom": {
                k.ink("M24 300 C 58 248 92 214 172 118", 2.4f); k.ink("M86 222 C 70 200 66 184 70 168", 1.4f); k.ink("M126 172 C 140 164 150 168 160 176", 1.4f);
                for (float[] b : new float[][]{{70, 166, 1}, {100, 204, .9f}, {134, 160, 1.05f}, {172, 116, .95f}, {160, 178, .85f}, {52, 254, .8f}, {112, 144, .8f}}) {
                    c.save(); c.translate(b[0], b[1]); c.scale(b[2], b[2]);
                    k.whorl(0, 0, 5, 14, 9, k.rnd.next() * 72, 1, k.wash, false); k.dot(0, 0, 2.2f);
                    c.restore();
                }
                for (float[] p : new float[][]{{40, 270, -40}, {150, 140, 30}}) { c.save(); c.translate(p[0], p[1]); c.rotate(p[2]); String d = Kit.petal(12, 5); k.wash(d); k.ink(d, .9f); c.restore(); }
                break;
            }
        }
    }

    // ------------------------------------------------------------------ seasons, skies, plates
    /** [drawing, name, watercolour] for what blooms in each season. */
    static final Object[][][] SEASONS = {
            // winter
            {{"Rose", "Camellia", 0xffd98088}, {"Snowdrop", "Summer Snowflake", 0xfff4f1e6}, {"Holly", "Yaupon Holly", 0xffc0453c}, {"Daffodil", "Paperwhite", 0xfff6f0dc},
                    {"Magnolia", "Japanese Magnolia", 0xffdba3c0}, {"Violet", "Pansy", 0xffa99cc8}, {"Cherry Blossom", "Sweet Olive", 0xfff2e3b5}},
            // spring
            {{"Iris", "Louisiana Iris", 0xff8f86c9}, {"Hibiscus", "Azalea", 0xffe58fb0}, {"Lily", "Amaryllis", 0xffd26b6b}, {"Daffodil", "Daffodil", 0xffecd068},
                    {"Cherry Blossom", "Redbud", 0xffc98ab8}, {"Violet", "Louisiana Phlox", 0xff9fa3dc}, {"Rose", "Old Garden Rose", 0xffe3a7b6}},
            // summer
            {{"Magnolia", "Southern Magnolia", 0xfff1ead6}, {"Cherry Blossom", "Crape Myrtle", 0xffd98ab8}, {"Hibiscus", "Hibiscus", 0xffd9665e}, {"Water Lily", "American Lotus", 0xfff2e3a6},
                    {"Lily", "Daylily", 0xffe39a5a}, {"Rose", "Gardenia", 0xfff4efe0}, {"Sunflower", "Black-eyed Susan", 0xffe6b43f}},
            // autumn
            {{"Carnation", "Chrysanthemum", 0xffd9a35a}, {"Violet", "Aster", 0xff9b8ccf}, {"Marigold", "Marigold", 0xffdcae52}, {"Sunflower", "Swamp Sunflower", 0xffe8c04a},
                    {"Rose", "Sasanqua", 0xffe3a1a8}, {"Lily", "Red Spider Lily", 0xffd9665e}, {"Hibiscus", "Cotton Rose", 0xfff0b8c4}},
    };
    static final String[] SEASON_NAMES = {"Winter", "Spring", "Summer", "Autumn"};

    static int season(java.util.Calendar d) {
        int m = d.get(java.util.Calendar.MONTH);
        return (m <= 1 || m == 11) ? 0 : m <= 4 ? 1 : m <= 7 ? 2 : 3;
    }

    static final class Sky {
        final String name, place; final float[][] stars; final int[][] lines;
        Sky(String n, String p, float[][] s, int[][] l) { name = n; place = p; stars = s; lines = l; }
    }
    static final Sky[] SKIES = {
            new Sky("Matariki", "Māori · Aotearoa (New Zealand)", new float[][]{{.38f, .35f}, {.5f, .18f}, {.62f, .3f}, {.56f, .55f, 1.3f}, {.44f, .62f}, {.72f, .5f}, {.66f, .8f}, {.5f, .88f}, {.3f, .62f}}, new int[][]{}),
            new Sky("Beidou, the Northern Dipper", "China", new float[][]{{0, .1f}, {.18f, .22f}, {.34f, .36f}, {.5f, .42f}, {.52f, .85f}, {.86f, .88f}, {.88f, .45f}}, new int[][]{{0, 1, 2, 3, 4, 5, 6, 3}}),
            new Sky("Māui's Fishhook", "Hawaiʻi · Ka Makau Nui o Māui", new float[][]{{.22f, 0}, {.3f, .2f}, {.34f, .42f}, {.4f, .62f, 1.4f}, {.52f, .8f}, {.68f, .88f}, {.82f, .78f}, {.86f, .58f}, {.78f, .46f}}, new int[][]{{0, 1, 2, 3, 4, 5, 6, 7, 8}}),
            new Sky("Yacana, the Llama", "Inca · the Andes", new float[][]{{.1f, .95f}, {.18f, .55f}, {.34f, .45f}, {.6f, .48f}, {.72f, .3f}, {.8f, .04f, 1.5f}, {.92f, .12f, 1.5f}, {.86f, .36f}, {.82f, .95f}}, new int[][]{{0, 1, 2, 3, 4, 5, 6, 7, 3}, {7, 8}}),
            new Sky("Tayamni, the Buffalo", "Lakota · the Great Plains", new float[][]{{.04f, .22f}, {.1f, .12f}, {.13f, .3f}, {.45f, .45f}, {.52f, .5f}, {.59f, .55f}, {.5f, .04f, 1.3f}, {.56f, .96f, 1.3f}, {.96f, .86f, 1.6f}}, new int[][]{{1, 3, 4, 5, 8}, {6, 4, 7}}),
            new Sky("Sah", "Ancient Egypt", new float[][]{{.25f, .04f, 1.4f}, {.75f, .12f}, {.42f, .5f}, {.5f, .53f}, {.58f, .56f}, {.22f, .95f}, {.78f, .92f, 1.4f}}, new int[][]{{0, 2, 3, 4, 1}, {2, 5}, {4, 6}}),
            new Sky("Ak' Ek', the Turtle", "Maya · Mesoamerica", new float[][]{{.36f, .42f}, {.5f, .48f}, {.64f, .54f}, {.16f, .9f}, {.84f, .94f, 1.4f}, {.5f, .1f}}, new int[][]{{0, 1, 2}, {0, 3}, {2, 4}, {0, 5, 2}}),
            new Sky("Thiazi's Eyes", "Norse · Scandinavia", new float[][]{{.32f, .5f, 1.8f}, {.68f, .42f, 1.8f}}, new int[][]{}),
            new Sky("Zhinü, the Weaver Girl", "China · the Qixi legend", new float[][]{{.15f, .12f, 1.7f}, {.06f, .46f}, {.26f, .46f}, {.8f, .62f}, {.86f, .78f, 1.6f}, {.92f, .94f}}, new int[][]{{0, 1, 2, 0}, {3, 4, 5}}),
            new Sky("Gu-an-na, the Bull of Heaven", "Babylon · Mesopotamia", new float[][]{{0, 0}, {.3f, .55f}, {.45f, .75f, 1.5f}, {.6f, .55f}, {1, .05f}, {.45f, 1}}, new int[][]{{0, 1, 2, 3, 4}, {2, 5}}),
            new Sky("Emu in the Sky", "Aboriginal Australia", new float[][]{{.95f, .05f}, {.85f, .2f}, {.72f, .42f}, {.55f, .55f}, {.3f, .55f}, {.12f, .66f}, {.2f, .92f}, {.5f, .94f}, {.7f, .8f}}, new int[][]{{0, 1, 2, 3, 4, 5, 6, 7, 8, 2}}),
    };

    static long dayNumber() {
        java.util.TimeZone tz = java.util.TimeZone.getDefault(); long now = System.currentTimeMillis();
        return Math.floorDiv(now + tz.getOffset(now), 86_400_000L);
    }

    static final String ARCH = "M 6 314 L 6 92 C 6 42 50 8 100 8 C 150 8 194 42 194 92 L 194 314 Z";
    static final String ARCH_IN = "M 14 306 L 14 94 C 14 50 54 18 100 18 C 146 18 186 50 186 94 L 186 306 Z";
    static String spark(float s) { return "M0 " + f(-s) + " Q0 0 " + f(s) + " 0 Q0 0 0 " + f(s) + " Q0 0 " + f(-s) + " 0 Q0 0 0 " + f(-s) + " Z"; }

    /** What plate i shows today: the season's flower and a constellation. */
    static Object[] flowerFor(int i) { Object[][] fl = SEASONS[season(java.util.Calendar.getInstance())]; return fl[i % fl.length]; }
    static Sky skyFor(int i) { return SKIES[(int) Math.floorMod(dayNumber() * 11 + i, SKIES.length)]; }

    /** Draw plate i (arch, sky, flower, spark) into a bitmap `w` pixels wide. */
    static Bitmap plate(int i, int w, Theme t, boolean withSky) {
        int h = Math.round(w * 320f / 200f);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(w / 200f, w / 200f);
        Object[] fl = flowerFor(i); Sky sky = skyFor(i);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path inner = path(ARCH_IN);
        p.setColor(t.map(PLATEWASH)); p.setAlpha(128); c.drawPath(inner, p);           // the plate's own wash
        p.setStyle(Paint.Style.STROKE); p.setColor(t.map(INK)); p.setAlpha(255); p.setStrokeWidth(1.6f); c.drawPath(path(ARCH), p);
        p.setStrokeWidth(.7f); p.setAlpha((int) (255 * .7f)); c.drawPath(inner, p);
        c.save(); c.clipPath(inner);
        if (withSky) {
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG); ln.setStyle(Paint.Style.STROKE); ln.setColor(t.map(GOLD)); ln.setAlpha((int) (255 * .4f)); ln.setStrokeWidth(1);
            ln.setPathEffect(new DashPathEffect(new float[]{2, 4}, 0));
            for (int[] ch : sky.lines) {
                Path pl = new Path();
                for (int j = 0; j < ch.length; j++) { float[] s = sky.stars[ch[j]]; float x = 34 + s[0] * 132, y = 30 + s[1] * 62; if (j == 0) pl.moveTo(x, y); else pl.lineTo(x, y); }
                c.drawPath(pl, ln);
            }
            Paint st = new Paint(Paint.ANTI_ALIAS_FLAG); st.setColor(t.map(GOLD));
            for (float[] s : sky.stars) c.drawCircle(34 + s[0] * 132, 30 + s[1] * 62, 3 * (s.length > 2 ? s[2] : 1), st);
        }
        c.save(); c.translate(100, 300); c.scale(.84f, .84f); c.translate(-100, -300);
        draw((String) fl[0], new Kit(c, t, (int) fl[2], new Rnd(11 + i)));
        c.restore();
        c.restore();
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG); g.setColor(t.map(GOLD));
        c.save(); c.translate(100, 8); c.drawPath(path(spark(6)), g); c.restore();
        return b;
    }
}
