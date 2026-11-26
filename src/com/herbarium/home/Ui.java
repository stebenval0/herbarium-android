package com.herbarium.home;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Herbarium's type and controls. Sizes come from the Theme, so "Text size" scales all of it. */
final class Ui {
    private Ui() {}

    static TextView text(Context c, Theme t, CharSequence s, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setText(s); v.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp(sp)); v.setTextColor(color); v.setTypeface(face);
        v.setIncludeFontPadding(false); v.setLineSpacing(0, 1.18f);
        return v;
    }
    static TextView body(Context c, Theme t, CharSequence s, float sp) { return text(c, t, s, sp, t.ink, Theme.regular); }

    /** Never split a word across lines: a long surname on a narrow screen (or at a large text size) would
     *  otherwise break mid-word ("Durgumu / munum"). Shrinks the type just enough for the widest word, not below minSp. */
    static TextView wholeWords(TextView v, float minSp) {
        v.addOnLayoutChangeListener((view, l, tp, r, b, ol, ot, or, ob) -> {
            int w = v.getWidth() - v.getTotalPaddingLeft() - v.getTotalPaddingRight();
            if (w <= 0) return;
            float widest = 0;
            for (String word : v.getText().toString().split("\\s+")) widest = Math.max(widest, v.getPaint().measureText(word));
            if (widest <= w) return;
            float min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, v.getResources().getDisplayMetrics());
            float px = Math.max(min, (float) Math.floor(v.getTextSize() * w / widest * .97f));
            if (px < v.getTextSize()) v.post(() -> v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px));
        });
        return v;
    }

    /** The small-capitals headings of the web version ("PHONE", "AUTUMN"), set in Literata. */
    static TextView caps(Context c, Theme t, String s, float sp, int color) {
        // spaced small capitals at ordinary sizes; at big sizes, longer phrases stay in ordinary case so they don't wrap
        boolean upper = !t.large() || s.length() <= 12;
        TextView v = text(c, t, upper ? s.toUpperCase(java.util.Locale.ROOT) : s, sp * (upper ? .86f : .95f), color, Theme.semi);
        v.setLetterSpacing(t.large() ? .04f : .08f);      // spaced enough to feel engraved, not so much that words fall apart
        return wholeWords(v, 11);                           // big titles at large sizes: "MESSAGES", never "MESSAG / ES"
    }

    /** Two buttons side by side, or one above the other once the text is large. */
    static LinearLayout pair(Context c, Theme t, android.view.View a, android.view.View b) {
        LinearLayout r = row(c);
        if (t.large()) { r.setOrientation(LinearLayout.VERTICAL); r.addView(a, wide()); r.addView(b, wide(t.dp(10))); return r; }
        LinearLayout.LayoutParams l1 = weight(); l1.rightMargin = t.dp(6); LinearLayout.LayoutParams l2 = weight(); l2.leftMargin = t.dp(6);
        r.addView(a, l1); r.addView(b, l2);
        return r;
    }

    static TextView centered(TextView v) { v.setGravity(Gravity.CENTER_HORIZONTAL); v.setTextAlignment(View.TEXT_ALIGNMENT_CENTER); return v; }

    static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    static LinearLayout.LayoutParams wide() { return lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    static LinearLayout.LayoutParams wide(int top) { LinearLayout.LayoutParams p = wide(); p.topMargin = top; return p; }
    static LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT); p.weight = 1; return p; }
    static FrameLayout.LayoutParams fill() { return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); }

    /** An outlined pill button in sage, at least 64 dp tall. Its label is in ordinary words at every size: an action
     *  is read, not admired, and capitals lose the shape of the word. */
    static TextView button(Context c, Theme t, String label, Steady.Action a) {
        TextView b = text(c, t, label, 17, t.sage, Theme.semi);
        b.setLetterSpacing(.01f);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.btn(17));
        b.setGravity(Gravity.CENTER);
        float grow = 1 + (t.scale - 1) * .35f;                         // the button grows with its label, not with the full text size
        b.setMinHeight(Math.max(t.dp(64), Math.round(t.dp(56) * grow)));
        b.setPadding(t.dp(18), Math.round(t.dp(10) * grow), t.dp(18), Math.round(t.dp(10) * grow));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(14)); g.setStroke(t.dp(t.contrast ? 2 : 1.4f), t.sage);
        b.setBackground(g);
        b.setAccessibilityDelegate(new View.AccessibilityDelegate() {      // announced as a button
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.Button");
            }
        });
        Steady.tap(b, t, a);
        return b;
    }

    /** A filled gold button for the one main thing on a page. */
    static TextView primary(Context c, Theme t, String label, Steady.Action a) {
        TextView b = button(c, t, label, a);
        b.setTextColor(t.night ? 0xff1e2650 : 0xfff3ecda);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(14)); g.setColor(t.night ? t.gold : 0xff9a7428);
        b.setBackground(g);
        return b;
    }

    /** A setting row: words on the left, "On"/"Off" pill on the right; the whole row is the target. */
    static LinearLayout toggle(Context c, Theme t, String title, String detail, boolean on, Steady.Action a) {
        LinearLayout r = row(c);
        r.setMinimumHeight(t.target(64)); r.setPadding(0, t.sdp(10), 0, t.sdp(10));
        LinearLayout words = column(c);
        words.addView(body(c, t, title, 20));
        if (detail != null) { TextView d = text(c, t, detail, 16, t.sepia, Theme.regular); words.addView(d, wide(t.sdp(4))); }
        // at large sizes the switch goes under the words: beside them, the words had a sliver of width and broke apart
        boolean stack = t.large();
        if (stack) { r.setOrientation(LinearLayout.VERTICAL); r.addView(words, wide()); } else r.addView(words, weight());
        TextView pill = caps(c, t, on ? "On" : "Off", 15, on ? (t.night ? 0xff1e2650 : 0xfff3ecda) : t.sepia);
        pill.setGravity(Gravity.CENTER); pill.setMinWidth(t.sdp(76)); pill.setPadding(t.sdp(12), t.sdp(10), t.sdp(12), t.sdp(10));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(20));
        if (on) g.setColor(t.sage); else g.setStroke(t.dp(1.4f), t.sepia);
        pill.setBackground(g);
        LinearLayout.LayoutParams pp = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (stack) pp.topMargin = t.sdp(8); else pp.leftMargin = t.sdp(14);
        r.addView(pill, pp);
        // one control for screen readers: "Steadier taps, on, switch"
        r.setContentDescription(title + (detail != null ? ". " + detail : ""));
        r.setStateDescription(on ? "On" : "Off");
        r.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch"); info.setCheckable(true); info.setChecked(on);
            }
        });
        for (int i = 0; i < r.getChildCount(); i++) r.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        Steady.tap(r, t, a);
        return r;
    }

    /** A hairline that fades at both ends, like the web version's rule. */
    static View rule(Context c, Theme t) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00000000, t.sepia, 0x00000000});
        v.setBackground(g); v.setAlpha(.5f);
        return v;
    }

    /** A section heading in sage with a line under it. */
    static TextView heading(Context c, Theme t, String s) {
        TextView h = caps(c, t, s, 17, t.sage);
        h.setPadding(0, t.sdp(22), 0, t.sdp(6));
        return h;
    }

    static TextView ellipsize(TextView v, int lines) { v.setMaxLines(lines); v.setEllipsize(TextUtils.TruncateAt.END); return v; }
}
