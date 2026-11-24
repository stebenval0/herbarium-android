package com.herbarium.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * A small engraved seal for each chapter (a handset for Phone, a coin for Money, a book for Reading…), set on the
 * foot of its flower plate. The flower is the decoration; the seal says what's inside, so a chapter can be
 * recognised at a glance rather than remembered.
 */
final class Glyph {
    private Glyph() {}

    /** A round seal (paper, with a fine sepia ring) holding the chapter's sign. */
    static View seal(Context c, Theme t, String chapter) {
        return new View(c) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            { setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - t.dp(1);
                p.setStyle(Paint.Style.FILL); p.setColor(t.paperHi); cv.drawCircle(w / 2f, h / 2f, r, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(t.dp(t.contrast ? 1.8f : 1.2f)); p.setColor(t.sepia); cv.drawCircle(w / 2f, h / 2f, r, p);
                Glyph.draw(cv, chapter, w / 2f, h / 2f, r * .58f, t.ink, t.dp(t.contrast ? 2.2f : 1.7f));
            }
        };
    }

    /** The sign itself, drawn in a box of half-size `s` around (x, y). */
    static void draw(Canvas c, String chapter, float x, float y, float s, int color, float stroke) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE); p.setColor(color); p.setStrokeWidth(stroke); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        switch (chapter) {
            case "Phone": {          // an old handset
                path.moveTo(x - s * .85f, y - s * .55f);
                path.cubicTo(x - s * .95f, y + s * .1f, x - s * .1f, y + s * .95f, x + s * .55f, y + s * .85f);
                c.drawPath(path, p);
                c.drawRoundRect(new RectF(x - s * .95f, y - s * .95f, x - s * .45f, y - s * .45f), s * .15f, s * .15f, p);
                c.drawRoundRect(new RectF(x + s * .45f, y + s * .45f, x + s * .95f, y + s * .95f), s * .15f, s * .15f, p);
                break;
            }
            case "Every day": {      // the sun
                c.drawCircle(x, y, s * .42f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    c.drawLine(x + (float) Math.cos(a) * s * .62f, y + (float) Math.sin(a) * s * .62f, x + (float) Math.cos(a) * s * .95f, y + (float) Math.sin(a) * s * .95f, p);
                }
                break;
            }
            case "Money": {          // a coin with a mark
                c.drawCircle(x, y, s * .9f, p); c.drawCircle(x, y, s * .62f, p);
                c.drawLine(x, y - s * .35f, x, y + s * .35f, p);
                break;
            }
            case "Travel": {         // a map pin
                path.moveTo(x, y + s);
                path.cubicTo(x - s * .9f, y + s * .05f, x - s * .75f, y - s, x, y - s);
                path.cubicTo(x + s * .75f, y - s, x + s * .9f, y + s * .05f, x, y + s);
                c.drawPath(path, p); c.drawCircle(x, y - s * .3f, s * .25f, p);
                break;
            }
            case "Reading & TV": {   // an open book
                path.moveTo(x, y - s * .55f); path.quadTo(x - s * .5f, y - s * .85f, x - s * .95f, y - s * .6f); path.lineTo(x - s * .95f, y + s * .7f);
                path.quadTo(x - s * .5f, y + s * .45f, x, y + s * .75f); path.quadTo(x + s * .5f, y + s * .45f, x + s * .95f, y + s * .7f);
                path.lineTo(x + s * .95f, y - s * .6f); path.quadTo(x + s * .5f, y - s * .85f, x, y - s * .55f); path.lineTo(x, y + s * .75f);
                c.drawPath(path, p);
                break;
            }
            case "Work & study": {   // a pencil
                path.moveTo(x - s * .75f, y + s * .95f); path.lineTo(x - s * .95f, y + s * .75f); path.lineTo(x + s * .6f, y - s * .8f);
                path.lineTo(x + s * .8f, y - s * .6f); path.close();
                c.drawPath(path, p); c.drawLine(x - s * .95f, y + s * .95f, x - s * .8f, y + s * .8f, p);
                break;
            }
            case "Camera": {         // a camera
                c.drawRoundRect(new RectF(x - s * .95f, y - s * .5f, x + s * .95f, y + s * .75f), s * .18f, s * .18f, p);
                c.drawCircle(x, y + s * .12f, s * .38f, p);
                c.drawLine(x - s * .35f, y - s * .5f, x - s * .2f, y - s * .78f, p); c.drawLine(x - s * .2f, y - s * .78f, x + s * .2f, y - s * .78f, p); c.drawLine(x + s * .2f, y - s * .78f, x + s * .35f, y - s * .5f, p);
                break;
            }
            case "House": {          // a house
                path.moveTo(x - s * .95f, y - s * .05f); path.lineTo(x, y - s * .9f); path.lineTo(x + s * .95f, y - s * .05f);
                c.drawPath(path, p);
                c.drawRect(x - s * .7f, y - s * .2f, x + s * .7f, y + s * .9f, p);
                c.drawRect(x - s * .2f, y + s * .3f, x + s * .2f, y + s * .9f, p);
                break;
            }
            case "Shopping": {       // a bag with handles
                c.drawRoundRect(new RectF(x - s * .8f, y - s * .35f, x + s * .8f, y + s * .95f), s * .12f, s * .12f, p);
                c.drawArc(new RectF(x - s * .4f, y - s * .95f, x + s * .4f, y + s * .2f), 180, 180, false, p);
                break;
            }
            case "Health": {         // a heart
                path.moveTo(x, y + s * .85f);
                path.cubicTo(x - s * 1.25f, y - s * .05f, x - s * .55f, y - s * 1.05f, x, y - s * .4f);
                path.cubicTo(x + s * .55f, y - s * 1.05f, x + s * 1.25f, y - s * .05f, x, y + s * .85f);
                c.drawPath(path, p);
                break;
            }
            default: {               // "Other", and any chapter named by hand: three dots
                p.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) c.drawCircle(x + i * s * .6f, y, s * .16f, p);
            }
        }
    }
}
