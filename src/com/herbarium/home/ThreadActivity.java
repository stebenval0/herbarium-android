package com.herbarium.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One conversation: who, with a big Call button; the messages as large cards (words, pictures, "didn't send — try
 * again"); and a big box to reply in (the keyboard listens first in Caretaken mode). Opened from Messages, from a
 * notice, or by anything asking to text a number (sms:/smsto:). With nobody chosen yet, it starts with "To whom?".
 */
public class ThreadActivity extends Activity {
    private Theme t;
    private long thread = -1;
    private List<String> addresses = new ArrayList<>();
    private LinearLayout head;
    private LinearLayout col, msgs;
    private ScrollView sc;
    private EditText box;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private int limit = 120;
    /** Pictures already decoded (about 16 MB at most), so they never load twice or blink. */
    private static final android.util.LruCache<String, Bitmap> PICS = new android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getAllocationByteCount(); }
    };
    private View compose;
    private long stickUntil;      // just opened or just sent: pictures that load late keep the view at the newest message
    private final BroadcastReceiver changed = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { load(false); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getAttributes().layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        t = Theme.get(this);
        thread = getIntent().getLongExtra("thread", -1);
        String[] withWhom = getIntent().getStringArrayExtra("to");          // who it's with, when the opener already knows
        if (withWhom != null) for (String a : withWhom) addresses.add(a);
        Uri data = getIntent().getData();
        if (thread < 0 && data != null) {          // sms:, smsto:, mms:, mmsto: — "text this number"
            String to = data.getSchemeSpecificPart();
            if (to != null) for (String a : to.split("[,;]")) if (!a.trim().isEmpty()) addresses.add(Uri.decode(a.trim()));
        }
        String draft = getIntent().getStringExtra("sms_body");
        if (draft == null) draft = getIntent().getStringExtra(Intent.EXTRA_TEXT);

        LinearLayout root = Ui.column(this);
        android.graphics.Bitmap paper = Paper.ready(this, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels, t);
        root.setBackground(paper != null ? new android.graphics.drawable.BitmapDrawable(getResources(), paper) : Paper.quick(t));
        // who, Back and Call stay at the top while the messages scroll under them
        head = Ui.row(this); head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(t.dp(14), t.dp(40), t.dp(14), t.dp(8));
        head.setBackgroundColor((t.paper2 & 0x00ffffff) | 0xee000000);
        root.addView(head, Ui.wide());
        sc = new Steady.Scroll(this, t);
        col = Ui.column(this); col.setPadding(t.dp(18), t.dp(8), t.dp(18), t.dp(16));
        sc.addView(col, Ui.wide());
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // the reply box, always at the bottom
        LinearLayout compose = Ui.row(this);
        compose.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(10));
        compose.setBackgroundColor(t.paper2);
        box = new EditText(this);
        box.setTextColor(t.ink); box.setHintTextColor(t.sepia); box.setTypeface(Theme.regular);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp(20)); box.setMinHeight(t.target(60));
        // not "multi-line" (that turns the keyboard's Send key into a new-line key); long messages still wrap
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT | InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE);
        box.setHorizontallyScrolling(false); box.setMaxLines(5);
        // the keyboard's own key says "Send" and sends (no reaching up for the button)
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        box.setOnEditorActionListener((v, id, ev) -> {
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && ev.getAction() == android.view.KeyEvent.ACTION_DOWN)) { send(); return true; }
            return false;
        });
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(14)); g.setStroke(t.dp(1.2f), t.sepia); g.setColor(t.night ? 0x661e2650 : 0xccf3ecda);
        box.setBackground(g); box.setPadding(t.sdp(12), t.sdp(8), t.sdp(12), t.sdp(8));
        if (draft != null) box.setText(draft);
        compose.addView(box, Ui.weight());
        TextView send = Ui.primary(this, t, "Send", v -> send());
        LinearLayout.LayoutParams sp = Ui.lp(Math.round(t.dp(96) * (1 + (t.scale - 1) * .3f)), ViewGroup.LayoutParams.WRAP_CONTENT); sp.leftMargin = t.dp(10);
        compose.addView(send, sp);
        root.addView(compose, Ui.wide());
        this.compose = compose;
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int bottom = Math.max(in.getInsets(WindowInsets.Type.navigationBars()).bottom, in.getInsets(WindowInsets.Type.ime()).bottom);
            v.setPadding(0, 0, 0, bottom);
            return in;
        });
        setContentView(root);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) { c.hide(WindowInsets.Type.statusBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        getWindow().setNavigationBarColor(t.paper2);
        registerReceiver(changed, new IntentFilter(Texts.CHANGED), Context.RECEIVER_NOT_EXPORTED);

        if (thread < 0 && addresses.isEmpty()) chooser(); else start();
    }

    /** Asked for another conversation while this one is open (a notice, a link): switch to it. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        long next = i.getLongExtra("thread", -1);
        List<String> to = new ArrayList<>();
        if (next < 0 && i.getData() != null && i.getData().getSchemeSpecificPart() != null)
            for (String a : i.getData().getSchemeSpecificPart().split("[,;]")) if (!a.trim().isEmpty()) to.add(Uri.decode(a.trim()));
        if (next < 0 && to.isEmpty()) return;
        setIntent(i);
        String[] known = i.getStringArrayExtra("to");
        if (next >= 0 && known != null) for (String a : known) to.add(a);
        thread = next; addresses = to; limit = 120;
        String draft = i.getStringExtra("sms_body"); if (draft == null) draft = i.getStringExtra(Intent.EXTRA_TEXT);
        box.setText(draft == null ? "" : draft);
        start();
        if (thread >= 0) { Texts.openThread = thread; bg.execute(() -> Texts.markRead(this, thread)); }
    }

    @Override protected void onResume() { super.onResume(); if (thread >= 0) { Texts.openThread = thread; bg.execute(() -> Texts.markRead(this, thread)); } }
    @Override protected void onPause() { if (Texts.openThread == thread) Texts.openThread = -1; super.onPause(); }
    @Override protected void onDestroy() { unregisterReceiver(changed); bg.shutdown(); super.onDestroy(); }

    /** Nobody chosen yet: the people they know, big, and a box for a number. */
    /** Nobody chosen yet: type a name or number (the keyboard is already up), or tap one of the people you text most. */
    private void chooser() {
        col.removeAllViews();
        compose.setVisibility(View.GONE);       // nothing to write in until someone is chosen
        col.addView(Ui.centered(Ui.caps(this, t, "To whom?", 26, t.ink)));
        EditText find = new EditText(this);
        find.setHint("Type a name or number"); find.setHintTextColor(t.sepia); find.setTextColor(t.ink); find.setTypeface(Theme.regular);
        find.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp(21)); find.setMinHeight(t.target(60)); find.setSingleLine(true);
        find.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        find.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(14)); g.setStroke(t.dp(1.2f), t.sepia); g.setColor(t.night ? 0x661e2650 : 0xccf3ecda);
        find.setBackground(g); find.setPadding(t.sdp(14), t.sdp(10), t.sdp(14), t.sdp(10)); find.setIncludeFontPadding(true);
        col.addView(find, Ui.wide(t.sdp(14)));
        LinearLayout list = Ui.column(this);
        col.addView(list, Ui.wide(t.sdp(6)));
        col.addView(Ui.button(this, t, "Back", v -> finish()), Ui.wide(t.sdp(16)));
        final List<CarePages.Person> recent = new ArrayList<>(), everyone = new ArrayList<>();
        Runnable show = () -> {
            list.removeAllViews();
            String q = find.getText().toString().trim();
            String digits = q.replaceAll("[^0-9+]", "");
            if (digits.length() >= 3 && digits.length() == q.replaceAll("[\\s()\\-.]", "").length()) {   // they're typing a number
                TextView to = Ui.primary(this, t, "Write to " + q, v -> { addresses.add(q); start(); });
                list.addView(to, Ui.wide(t.sdp(8)));
                return;
            }
            String lq = q.toLowerCase(java.util.Locale.ROOT);
            int n = 0;
            if (lq.isEmpty() && !recent.isEmpty()) {
                list.addView(Ui.heading(this, t, "Recent"));
                for (CarePages.Person p : recent) { list.addView(personButton(p), Ui.wide(t.sdp(8))); n++; }
                list.addView(Ui.heading(this, t, "Everyone"));
            }
            for (CarePages.Person p : everyone) {
                if (!lq.isEmpty() && !matches(p.name, lq)) continue;
                list.addView(personButton(p), Ui.wide(t.sdp(8)));
                if (++n >= 60) break;
            }
            if (n == 0) list.addView(Ui.text(this, t, "Nobody by that name. Type their number instead.", 17, t.sepia, Theme.italic), Ui.wide(t.sdp(10)));
        };
        find.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
            public void afterTextChanged(android.text.Editable e) { show.run(); }
        });
        find.setOnEditorActionListener((v, id, ev) -> {          // "Go": the first match
            for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).getTag() instanceof CarePages.Person) { list.getChildAt(i).performClick(); return true; }
            return true;
        });
        bg.execute(() -> {
            java.util.Set<String> seen = new java.util.HashSet<>(MmsWork.myNumbers(this));   // not yourself
            for (Texts.Conv v : Texts.conversations(this)) {
                if (v.pile != Sorter.PEOPLE || v.group || recent.size() >= 5) continue;
                if (!seen.add(Sorter.key(v.addresses.get(0)))) continue;
                CarePages.Person p = new CarePages.Person(); p.name = v.name; p.number = v.addresses.get(0); recent.add(p);
            }
            everyone.addAll(CarePages.people(this, false));
            ui.post(show);
        });
        find.requestFocus();
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /** "Sam" matches Sam Carter; "car" matches Sam Carter too (the start of any word). */
    private static boolean matches(String name, String q) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith(q)) return true;
        for (String w : n.split("\\s+")) if (w.startsWith(q)) return true;
        return false;
    }

    private View personButton(CarePages.Person p) {
        TextView r = Ui.button(this, t, p.name, v -> { addresses.add(p.number); start(); });
        r.setTag(p);
        r.setContentDescription("Write to " + p.name);
        return r;
    }

    private void start() {
        compose.setVisibility(View.VISIBLE);
        if (thread < 0 && !addresses.isEmpty()) thread = Telephony.Threads.getOrCreateThreadId(this, new java.util.LinkedHashSet<>(addresses));
        Texts.openThread = thread;
        load(true);
    }

    private void load(boolean toEnd) {
        if (thread < 0) return;
        // already read (prepared by the Messages page, or opened before): show it at once, then check for anything new
        List<Texts.Msg> have = Texts.cached(thread);
        if (have != null && !addresses.isEmpty()) show(have, toEnd);
        final boolean shownAlready = have != null && !addresses.isEmpty();
        bg.execute(() -> {
            List<String> who = addresses.isEmpty() ? Texts.addresses(this, thread) : addresses;
            List<Texts.Msg> list = Texts.messages(this, thread, limit);
            Texts.remember(thread, list);
            Texts.markRead(this, thread);
            ui.post(() -> {
                addresses = new ArrayList<>(who);
                if (shownAlready && same(have, list)) return;      // nothing new: leave the screen exactly as it is
                show(list, toEnd && !shownAlready);
            });
        });
    }

    private static boolean same(List<Texts.Msg> a, List<Texts.Msg> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Texts.Msg x = a.get(i), y = b.get(i);
            if (x.id != y.id || x.mms != y.mms || x.failed != y.failed || x.sending != y.sending) return false;
        }
        return true;
    }

    /** Lay the messages out and land on the newest without a visible jump: hidden for the one frame it takes. */
    private void show(List<Texts.Msg> list, boolean toEnd) {
        boolean atEnd = !toEnd && sc.getScrollY() + sc.getHeight() >= col.getHeight() - t.dp(40);
        if (toEnd) col.setAlpha(0f);
        render(list);
        if (toEnd || atEnd) {
            stickUntil = android.os.SystemClock.uptimeMillis() + 4000;
            sc.post(() -> { sc.scrollTo(0, col.getHeight()); col.setAlpha(1f); });
        }
        if (toEnd) readyToType();
    }

    private void render(List<Texts.Msg> list) {
        col.removeAllViews();
        boolean care = Care.on(this) && !getIntent().getBooleanExtra("helper", false), group = addresses.size() > 1;
        String name = addresses.isEmpty() ? "" : Texts.names(this, addresses);
        head.removeAllViews();
        TextView back = Ui.button(this, t, "‹ Back", v -> finish());
        back.setContentDescription("Back to messages");
        head.addView(back, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView who = Ui.wholeWords(Ui.centered(Ui.text(this, t, name, 22, t.ink, Theme.semi)), 14); who.setMaxLines(2);
        LinearLayout.LayoutParams wp = Ui.weight(); wp.leftMargin = wp.rightMargin = t.dp(8);
        head.addView(who, wp);
        boolean canCall = !group && !addresses.isEmpty() && !Sorter.business(addresses.get(0));
        if (canCall) {
            TextView call = Ui.primary(this, t, "Call", v -> Missed.callBack(this, addresses.get(0)));
            call.setContentDescription("Call " + name);
            head.addView(call, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else head.addView(new View(this), Ui.lp(back.getMinWidth(), 1));

        if (list.size() >= limit) col.addView(Ui.button(this, t, "Show earlier messages", v -> { limit += 200; load(false); }), Ui.wide(t.sdp(14)));
        long lastDay = -1;
        for (Texts.Msg m : list) {
            long day = dayOf(m.date);
            if (day != lastDay) { lastDay = day; col.addView(Ui.centered(Ui.text(this, t, dayLabel(m.date), 15, t.sepia, Theme.italic)), Ui.wide(t.sdp(16))); }
            col.addView(card(m, care, group), Ui.wide(t.sdp(8)));
        }
        if (list.isEmpty()) col.addView(Ui.centered(Ui.text(this, t, "Nothing here yet. Write below.", 18, t.sepia, Theme.regular)), Ui.wide(t.sdp(24)));
        // some senders (names like "AMAZON") can't be written back to
        boolean canReply = !addresses.isEmpty() && !addresses.get(0).matches(".*[A-Za-z].*");
        box.setEnabled(canReply);
        box.setHint(canReply ? (group ? "Write to everyone…" : "Write to " + Missed.first(name) + "…") : "This sender can't be written back to");
    }

    /** One message, as a card: mine on the right in sage, theirs on the left on paper. */
    private View card(Texts.Msg m, boolean care, boolean group) {
        LinearLayout wrap = Ui.row(this);
        wrap.setGravity(m.mine ? Gravity.END : Gravity.START);
        LinearLayout c = Ui.column(this);
        c.setPadding(t.dp(14), t.sdp(10), t.dp(14), t.sdp(10));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(t.sdp(16));
        if (m.mine) g.setColor(t.night ? 0x5534523f : 0x55a9c9b0); else { g.setColor(t.night ? 0x442c3870 : 0xddf3ecda); g.setStroke(t.dp(1), 0x556e5d45); }
        if (m.failed) g.setStroke(t.dp(2), t.ochre);
        c.setBackground(g);
        if (group && !m.mine && m.from != null) c.addView(Ui.text(this, t, Missed.first(Missed.name(this, m.from, null)), 15, t.sage, Theme.semi));
        for (Uri pic : m.pictures) {
            ImageView iv = new ImageView(this); iv.setContentDescription("A picture");
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int pw = Math.round(getResources().getDisplayMetrics().widthPixels * .62f);
            c.addView(iv, Ui.lp(pw, Math.round(pw * .75f)));       // space kept from the start, so nothing jumps when it loads
            Bitmap ready = PICS.get(pic.toString());
            if (ready != null) { iv.setImageBitmap(ready); continue; }
            bg.execute(() -> { Bitmap bmp = picture(pic, Math.round(getResources().getDisplayMetrics().widthPixels * .62f)); if (bmp != null) PICS.put(pic.toString(), bmp); if (bmp != null) ui.post(() -> {
                iv.setImageBitmap(bmp);
                if (android.os.SystemClock.uptimeMillis() < stickUntil) sc.post(() -> sc.scrollTo(0, col.getHeight()));
            }); });
        }
        if (!m.text.isEmpty()) {
            String words = care ? Sorter.withoutLinks(m.text) : m.text;
            TextView tv = Ui.text(this, t, words, 20, t.ink, Theme.regular);
            if (!care && Sorter.hasLink(m.text)) { Linkify.addLinks(tv, Linkify.WEB_URLS); tv.setLinkTextColor(t.sage); tv.setMovementMethod(LinkMovementMethod.getInstance()); }
            tv.setTextIsSelectable(!care);
            c.addView(tv, Ui.wide(m.pictures.isEmpty() ? 0 : t.sdp(6)));
        }
        String foot = MessagesActivity.when(m.date) + (m.sending ? " · sending…" : "");
        c.addView(Ui.text(this, t, foot, 14, t.sepia, Theme.regular), Ui.wide(t.sdp(4)));
        if (m.failed) {
            c.addView(Ui.text(this, t, "This didn't send.", 17, t.ochre, Theme.semi), Ui.wide(t.sdp(6)));
            c.addView(Ui.button(this, t, "Try again", v -> bg.execute(() -> Texts.retry(getApplicationContext(), m))), Ui.wide(t.sdp(6)));
        }
        LinearLayout.LayoutParams cp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (m.mine) cp.leftMargin = t.dp(40); else cp.rightMargin = t.dp(40);
        wrap.addView(c, cp);
        return wrap;
    }

    private Bitmap picture(Uri u, int width) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1; while (o.outWidth / (s * 2) >= width) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options(); d.inSampleSize = s;
            try (InputStream in = getContentResolver().openInputStream(u)) { return BitmapFactory.decodeStream(in, null, d); }
        } catch (Exception e) { return null; }
    }

    /** The reply box focused and the keyboard up, so the first thing is typing (or speaking, in Caretaken mode). */
    private void readyToType() {
        box.post(() -> {
            if (!box.isEnabled()) return;
            box.requestFocus();
            android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (im != null) im.showSoftInput(box, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void send() {
        String words = box.getText().toString().trim();
        if (words.isEmpty() || addresses.isEmpty()) return;
        box.setText("");
        List<String> to = new ArrayList<>(addresses);
        bg.execute(() -> {
            if (to.size() > 1) MmsWork.sendText(getApplicationContext(), to, words);
            else Texts.send(getApplicationContext(), to.get(0), words);
            ui.post(() -> load(true));
        });
    }

    private static long dayOf(long at) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(at); return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR); }

    private static String dayLabel(long at) {
        long today = dayOf(System.currentTimeMillis()), d = dayOf(at);
        if (d == today) return "Today";
        if (d == dayOf(System.currentTimeMillis() - 86_400_000L)) return "Yesterday";
        return new java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(new java.util.Date(at));
    }
}
