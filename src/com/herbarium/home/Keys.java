package com.herbarium.home;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Herbarium's keyboard: parchment keys in Literata that grow with Text size, forgiving taps, a few word suggestions,
 * and voice typing. In Caretaken mode it opens listening, with the letters one tap away.
 */
public class Keys extends InputMethodService {
    private Theme t;
    private String themeKey = "";
    private Board board;
    private LinearLayout strip, voice;
    private TextView heard;
    private Mic mic;
    private TextView enterInVoice;
    private boolean shift, caps, symbols, voiceShown;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private boolean listening;
    private final List<String> words = new ArrayList<>();
    private JSONObject learned;

    // ------------------------------------------------------------------ making it the phone's keyboard
    /** Whether the phone can turn speech into text at all (Google's service, or its own on-device one). */
    static boolean canHear(android.content.Context c) { return true; }   // Herbarium's own ear works on every phone

    /** Herbarium's own offline ear when the phone has no voice service (or when told to use it, for testing). */
    /** The phone's own speech service failed (no internet, a broken service): Herbarium's offline ear takes over until restart. */
    static volatile boolean useOwnEar;

    static boolean ownEar(android.content.Context c) {
        if (Theme.prefs(c).getString("ear", "auto").equals("own")) return true;
        if (useOwnEar && OwnEar.ready(c)) return true;
        return !SpeechRecognizer.isRecognitionAvailable(c) && !(android.os.Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c));
    }

    static String id(android.content.Context c) { return new android.content.ComponentName(c, Keys.class).flattenToShortString(); }

    /** Is Herbarium's keyboard switched on? (Asked of Android's keyboard service: from Android 14 apps may not read the setting.) */
    static boolean enabled(android.content.Context c) {
        try {
            android.view.inputmethod.InputMethodManager imm = c.getSystemService(android.view.inputmethod.InputMethodManager.class);
            for (android.view.inputmethod.InputMethodInfo i : imm.getEnabledInputMethodList()) if (i.getPackageName().equals(c.getPackageName())) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Is it the keyboard in use? */
    static boolean isDefault(android.content.Context c) {
        try {
            String d = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
            android.content.ComponentName n = d == null ? null : android.content.ComponentName.unflattenFromString(d);
            return n != null && n.getPackageName().equals(c.getPackageName());
        } catch (Exception e) {                    // not readable: ask the keyboard service instead
            try {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    android.view.inputmethod.InputMethodInfo cur = c.getSystemService(android.view.inputmethod.InputMethodManager.class).getCurrentInputMethodInfo();
                    return cur != null && cur.getPackageName().equals(c.getPackageName());
                }
            } catch (Exception ignored) {}
            return false;
        }
    }

    /** Make Herbarium's keyboard the one in use: at once if the keeper's computer granted secure settings,
     *  otherwise through Android's keyboard pages (Clausus lets them through for a few minutes).
     *  Returns what the person should do next, or "" when it's done. */
    static String choose(android.app.Activity a) {
        try {                                       // only with secure-settings access (granted by the keeper's computer)
            android.content.ContentResolver cr = a.getContentResolver();
            if (!enabled(a)) {
                String on = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_INPUT_METHODS);
                android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_INPUT_METHODS, (on == null || on.isEmpty() ? "" : on + ":") + id(a));
            }
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, id(a));
            if (isDefault(a)) return "";
        } catch (Exception e) { /* no such access on this phone: ask Android */ }
        Guard.allow(a, "keyboard");
        if (!enabled(a)) {
            a.startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS));
            return "Turn on the switch beside Herbarium, tap OK, then come back here and tap the button again.";
        }
        a.getSystemService(android.view.inputmethod.InputMethodManager.class).showInputMethodPicker();
        return "Choose Herbarium in the list.";
    }

    // ------------------------------------------------------------------ the view
    @Override public View onCreateInputView() {
        t = Theme.get(this); themeKey = key(t);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(t.paper2);
        root.setForceDarkAllowed(false);
        if (getWindow() != null && getWindow().getWindow() != null) getWindow().getWindow().setNavigationBarColor(t.paper2);   // Android's bar under the keys in parchment, not black

        strip = Ui.row(this);
        strip.setPadding(t.dp(6), t.dp(4), t.dp(6), 0);
        root.addView(strip, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, stripHeight()));

        FrameLayout body = new FrameLayout(this);
        board = new Board();
        body.addView(board, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        voice = buildVoice();
        body.addView(voice, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, board.boardHeight()));
        root.addView(body, Ui.wide());
        showVoice(voiceShown);
        return root;
    }

    private static String key(Theme t) { return t.night + "|" + t.scale + "|" + t.contrast + "|" + t.steady; }
    private float grow() { return 1 + (t.scale - 1) * .35f; }
    private int stripHeight() { return Math.round(t.dp(46) * grow()); }

    @Override public void onStartInputView(EditorInfo ei, boolean restarting) {
        super.onStartInputView(ei, restarting);
        if (!key(Theme.get(this)).equals(themeKey)) setInputView(onCreateInputView());
        int cls = ei.inputType & InputType.TYPE_MASK_CLASS;
        symbols = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME;
        caps = false;
        if (!restarting) showVoice(Care.voiceFirst(this) && canHear(this) && !symbols && !secret(ei));
        if (voiceShown) listen();
        autoShift(); suggest(); updateEnterLabel();
        board.invalidate();
    }

    @Override public void onFinishInputView(boolean finishing) { stopListening(); super.onFinishInputView(finishing); }
    @Override public void onDestroy() { if (sr != null) { sr.destroy(); sr = null; } super.onDestroy(); }

    /** Never full-screen: the person keeps seeing what they're writing in. */
    @Override public boolean onEvaluateFullscreenMode() { return false; }

    private static boolean secret(EditorInfo ei) {
        int v = ei.inputType & InputType.TYPE_MASK_VARIATION;
        return v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    // ------------------------------------------------------------------ keys
    private void onKey(String k) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (k) {
            case "SHIFT":
                if (shift && !caps) caps = true; else if (caps) { caps = false; shift = false; } else shift = true;
                break;
            case "DEL":
                CharSequence sel = ic.getSelectedText(0);
                if (sel != null && sel.length() > 0) ic.commitText("", 1);
                else sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL);
                break;
            case "SYM": symbols = true; break;
            case "ABC": symbols = false; break;
            case "MIC": showVoice(true); listen(); return;
            case "ENTER": learnLastWord(); enter(ic); break;
            case "SPACE": learnLastWord(); ic.commitText(" ", 1); break;
            default:
                if (k.length() == 1 && ".,!?;:".contains(k)) learnLastWord();
                ic.commitText(shift || caps ? k.toUpperCase(Locale.ROOT) : k, 1);
                if (shift && !caps) shift = false;
        }
        if (!k.equals("SHIFT")) autoShift();
        suggest();
        board.invalidate();
    }

    private void enter(InputConnection ic) {
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = ei == null || (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action);
        else ic.commitText("\n", 1);
    }

    private String enterLabel() {
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ei == null || (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵";
        switch (ei.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_SEND: return "Send";
            case EditorInfo.IME_ACTION_SEARCH: return "Seek";
            case EditorInfo.IME_ACTION_GO: return "Go";
            case EditorInfo.IME_ACTION_DONE: return "Done";
            case EditorInfo.IME_ACTION_NEXT: return "Next";
            default: return "↵";
        }
    }

    private void updateEnterLabel() {
        if (enterInVoice != null) {
            String l = enterLabel();
            String w = l.equals("↵") ? "New line" : l;
            enterInVoice.setText(w);
        }
    }

    /** Capital at the start of a sentence (not in passwords, addresses or numbers). */
    private void autoShift() {
        if (caps) return;
        EditorInfo ei = getCurrentInputEditorInfo(); InputConnection ic = getCurrentInputConnection();
        if (ei == null || ic == null || secret(ei) || (ei.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) { shift = false; return; }
        shift = ic.getCursorCapsMode(ei.inputType) != 0;
    }

    // ------------------------------------------------------------------ suggestions
    private void loadWords() {
        if (!words.isEmpty()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("words.txt"), "UTF-8"))) {
            String line; while ((line = r.readLine()) != null) for (String w : line.trim().split("\\s+")) if (!w.isEmpty() && !words.contains(w)) words.add(w);
        } catch (Exception ignored) {}
        try { learned = new JSONObject(wordStore().getString("learned", "{}")); } catch (Exception e) { learned = new JSONObject(); }
    }

    private String currentWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(40, 0);
        if (before == null) return "";
        int i = before.length();
        while (i > 0 && (Character.isLetter(before.charAt(i - 1)) || before.charAt(i - 1) == '\'')) i--;
        return before.subSequence(i, before.length()).toString();
    }

    private void suggest() {
        strip.removeAllViews();
        EditorInfo ei = getCurrentInputEditorInfo();
        if (voiceShown || ei == null || secret(ei)) return;
        String w = currentWord();
        if (w.isEmpty()) return;
        loadWords();
        String lw = w.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        // the person's own words first (most used first), then the common ones
        List<String> mine = new ArrayList<>();
        for (Iterator<String> it = learned.keys(); it.hasNext(); ) { String x = it.next(); if (x.startsWith(lw) && !x.equals(lw)) mine.add(x); }
        mine.sort((a, b) -> learned.optInt(b) - learned.optInt(a));
        for (String x : mine) if (out.size() < 3) out.add(x);
        for (String x : words) { if (out.size() >= 3) break; String lx = x.toLowerCase(Locale.ROOT); if (lx.startsWith(lw) && !lx.equals(lw) && !out.contains(lx)) out.add(x); }
        for (String s : out) {
            String shown = Character.isUpperCase(w.charAt(0)) ? Character.toUpperCase(s.charAt(0)) + s.substring(1) : s;
            TextView v = Ui.centered(Ui.text(this, t, shown, 0, t.ink, Theme.regular));
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, t.dp(19) * grow());
            v.setGravity(Gravity.CENTER); v.setSingleLine(true); v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Steady.tap(v, t, x -> pick(w, shown));
            strip.addView(v, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT));
            ((LinearLayout.LayoutParams) v.getLayoutParams()).weight = 1;
        }
    }

    private void pick(String typed, String word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!currentWord().equals(typed)) { suggest(); return; }
        ic.beginBatchEdit();
        ic.deleteSurroundingText(typed.length(), 0);
        ic.commitText(word + " ", 1);
        ic.endBatchEdit();
        learn(word);
        autoShift(); suggest(); board.invalidate();
    }

    private void learnLastWord() { learn(currentWord()); }

    /** The person's words live in their own small file, not in Herbarium's settings (moved there once, if older). */
    private android.content.SharedPreferences wordStore() {
        android.content.SharedPreferences w = Theme.store(this).getSharedPreferences("words", MODE_PRIVATE);
        android.content.SharedPreferences main = Theme.prefs(this);
        if (main.contains("learnedWords")) {
            w.edit().putString("learned", main.getString("learnedWords", "{}")).apply();
            main.edit().remove("learnedWords").apply();
        }
        return w;
    }

    /** Remember the person's words (never in passwords or where the app asks for no learning). */
    private void learn(String w) {
        EditorInfo ei = getCurrentInputEditorInfo();
        if (w.length() < 3 || ei == null || secret(ei) || (ei.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return;
        loadWords();
        String lw = w.toLowerCase(Locale.ROOT);
        try {
            learned.put(lw, learned.optInt(lw) + 1);
            if (learned.length() > 3000) {   // forget the least used
                String least = null; int n = Integer.MAX_VALUE;
                for (Iterator<String> it = learned.keys(); it.hasNext(); ) { String x = it.next(); if (learned.optInt(x) < n) { n = learned.optInt(x); least = x; } }
                if (least != null) learned.remove(least);
            }
            wordStore().edit().putString("learned", learned.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ voice typing
    private LinearLayout buildVoice() {
        LinearLayout v = Ui.column(this);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(t.dp(16), t.dp(6), t.dp(16), t.dp(12));
        v.setBackgroundColor(t.paper2);
        heard = Ui.centered(Ui.text(this, t, "", 19, t.sepia, Theme.italic));
        heard.setMaxLines(3); heard.setEllipsize(android.text.TextUtils.TruncateAt.START);
        v.addView(heard, Ui.wide());
        mic = new Mic();
        int ms = Math.round(t.dp(92) * grow());
        LinearLayout.LayoutParams mp = Ui.lp(ms, ms); mp.gravity = Gravity.CENTER_HORIZONTAL; mp.topMargin = t.dp(8);
        v.addView(mic, mp);
        mic.setForeground(new android.graphics.drawable.ColorDrawable(0));   // the circle shows its own state; no square tap highlight
        Steady.tap(mic, t, x -> { if (listening) stopListening(); else listen(); });
        View gap = new View(this);
        v.addView(gap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView letters = Ui.button(this, t, "Letters", x -> { stopListening(); showVoice(false); suggest(); });
        TextView del = Steady.repeatable(Ui.button(this, t, "Undo", x -> deleteWord()));
        del.setContentDescription("Take back the last thing said");
        enterInVoice = Ui.button(this, t, "Send", x -> { InputConnection ic = getCurrentInputConnection(); if (ic != null) enter(ic); });
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams l1 = Ui.weight(), l2 = Ui.weight(), l3 = Ui.weight();
        l2.leftMargin = l3.leftMargin = t.dp(8);
        row.addView(letters, l1); row.addView(del, l2); row.addView(enterInVoice, l3);
        for (TextView b : new TextView[]{letters, del, enterInVoice}) { b.setSingleLine(true); b.setAutoSizeTextTypeUniformWithConfiguration(10, Math.round(b.getTextSize() / getResources().getDisplayMetrics().scaledDensity), 1, android.util.TypedValue.COMPLEX_UNIT_SP); b.setPadding(t.dp(4), b.getPaddingTop(), t.dp(4), b.getPaddingBottom()); }
        v.addView(row, Ui.wide(t.dp(6)));
        return v;
    }

    private void showVoice(boolean on) {
        voiceShown = on;
        if (voice == null) return;
        voice.setVisibility(on ? View.VISIBLE : View.GONE);
        board.setVisibility(on ? View.INVISIBLE : View.VISIBLE);    // keeps its height, so the keyboard doesn't jump
        strip.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        if (on) { updateEnterLabel(); say("Tap the circle and speak.", false); }
    }

    private void say(String s, boolean words) { heard.setText(s); heard.setTextColor(words ? t.ink : t.sepia); heard.setTypeface(words ? Theme.regular : Theme.italic); }

    private void listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Herbarium needs to hear you first. Tap the circle.", false);
            mic.setOnTouchListener(null);
            Steady.tap(mic, t, x -> {
                Guard.allow(this, "mic");
                startActivity(new Intent(this, MicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Steady.tap(mic, t, y -> { if (listening) stopListening(); else listen(); });
            });
            return;
        }
        if (ownEar(this)) {
            usingOwn = true; listening = true; mic.on(true);
            say(OwnEar.ready(this) ? "Getting ready…" : "Getting voice typing ready. This happens once.", false);
            OwnEar.start(this, ownEarListener);
            return;
        }
        usingOwn = false;
        if (sr == null) {
            // Google's voice service if the phone has it; otherwise the phone's own on-device one (phones without Google)
            if (SpeechRecognizer.isRecognitionAvailable(this)) sr = SpeechRecognizer.createSpeechRecognizer(this);
            else if (android.os.Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            else { say("Voice typing isn't available on this phone. Tap Letters to type.", false); return; }
            sr.setRecognitionListener(heardIt);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // patient: a soft or slow voice gets time to finish
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L);
        try { sr.startListening(i); listening = true; mic.on(true); say("Listening… speak whenever you're ready.", false); }
        catch (Exception e) { listening = false; mic.on(false); say("Voice typing didn't start. Tap the circle to try again.", false); }
    }

    private boolean usingOwn;

    private final OwnEar.Ear ownEarListener = new OwnEar.Ear() {
        @Override public void preparing(int pct) { say(pct >= 0 ? "Getting voice typing ready, once only… " + pct + "%" : "Getting voice typing ready, once only…", false); }
        @Override public void listening() { say("Listening… speak whenever you're ready.", false); }
        @Override public void partial(String text) { say(text, true); mic.level(4 + (text.length() % 5)); }
        @Override public void heard(String text) { write(tidy(text)); mic.level(0); }
        @Override public void stopped(String problem) { listening = false; mic.on(false); say(problem != null ? problem : "Tap the circle to say more.", false); }
    };

    /** Herbarium's own ear writes plain lower case: give "I" its capital. */
    static String tidy(String s) {
        return s.replaceAll("\\bi\\b", "I").replaceAll("\\bi'(m|ll|ve|d)\\b", "I'$1");
    }

    private void stopListening() {
        if (usingOwn && listening) OwnEar.stop();
        if (sr != null && listening && !usingOwn) { try { sr.cancel(); } catch (Exception ignored) {} }
        listening = false;
        if (mic != null) mic.on(false);
    }

    private final RecognitionListener heardIt = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float db) { mic.level(db); }
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int e, Bundle b) {}
        @Override public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) say(r.get(0), true);
        }
        @Override public void onResults(Bundle b) {
            listening = false; mic.on(false);
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty() || r.get(0).trim().isEmpty()) { say("I didn't catch that. Tap the circle and try again.", false); return; }
            write(r.get(0).trim());
            say("Tap the circle to say more.", false);
        }
        @Override public void onError(int err) {
            listening = false; mic.on(false);
            switch (err) {
                case SpeechRecognizer.ERROR_NO_MATCH: case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    say("I didn't catch that. Tap the circle and try again.", false); break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: listen(); break;
                case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: case SpeechRecognizer.ERROR_SERVER:
                case 11 /* ERROR_SERVER_DISCONNECTED */: case 12 /* ERROR_LANGUAGE_NOT_SUPPORTED */: case 13 /* ERROR_LANGUAGE_UNAVAILABLE */:
                    if (OwnEar.ready(Keys.this)) { useOwnEar = true; listen(); break; }     // Herbarium's own (offline) ear carries on
                    say("Voice typing needs the internet just now. Tap Letters to type.", false); break;
                default: say("Voice typing stopped. Tap the circle to try again.", false);
            }
        }
    };

    /** Put what was said into the text: a space before it if needed, a capital at the start of a sentence. */
    private void write(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(2, 0);
        boolean space = before != null && before.length() > 0 && !Character.isWhitespace(before.charAt(before.length() - 1));
        EditorInfo ei = getCurrentInputEditorInfo();
        boolean cap = ei != null && ic.getCursorCapsMode(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0;
        if (space) { CharSequence b1 = before.subSequence(before.length() - 1, before.length()); cap = cap || ".!?".contains(b1); }
        if (cap && !s.isEmpty()) s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        ic.commitText((space ? " " : "") + s, 1);
        lastSpoken = (space ? " " : "") + s;
    }
    private String lastSpoken = "";

    /** Take back the last thing said (or the last word typed). */
    private void deleteWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(400, 0);
        if (before == null || before.length() == 0) return;
        String b = before.toString();
        int n;
        if (!lastSpoken.isEmpty() && b.endsWith(lastSpoken)) n = lastSpoken.length();
        else { int i = b.length(); while (i > 0 && Character.isWhitespace(b.charAt(i - 1))) i--; while (i > 0 && !Character.isWhitespace(b.charAt(i - 1))) i--; n = b.length() - i; }
        lastSpoken = "";
        ic.deleteSurroundingText(n, 0);
        say("Taken back. Tap the circle to say it again.", false);
    }

    /** The big round microphone; it breathes with the voice. */
    private final class Mic extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on; private float lvl;
        Mic() { super(Keys.this); setContentDescription("Voice typing"); }
        void on(boolean v) { on = v; lvl = 0; invalidate(); }
        void level(float db) { lvl = Math.max(0, Math.min(1, (db + 2) / 12f)); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - t.dp(4);
            if (on) { p.setStyle(Paint.Style.FILL); p.setColor(t.sage); p.setAlpha(60); c.drawCircle(cx, cy, r * (.86f + .14f * lvl), p); }
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL); p.setColor(on ? t.sage : t.paperHi); c.drawCircle(cx, cy, r * .78f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(t.dp(1.6f)); p.setColor(t.sage); c.drawCircle(cx, cy, r * .78f, p);
            // the microphone: a capsule, a cradle and a stem
            int ink = on ? (t.night ? 0xff1e2650 : 0xfff3ecda) : t.sage;
            float u = r / 4.2f;
            p.setColor(ink); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(cx - u * .62f, cy - u * 1.9f, cx + u * .62f, cy + u * .35f), u * .62f, u * .62f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(u * .28f); p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(new RectF(cx - u * 1.15f, cy - u * 1.1f, cx + u * 1.15f, cy + u * .95f), 20, 140, false, p);
            c.drawLine(cx, cy + u * .95f, cx, cy + u * 1.6f, p);
            c.drawLine(cx - u * .6f, cy + u * 1.6f, cx + u * .6f, cy + u * 1.6f, p);
        }
    }

    // ------------------------------------------------------------------ the keys, drawn in one view (quick)
    private static final String[][] LETTERS = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"_.5", "a", "s", "d", "f", "g", "h", "j", "k", "l", "_.5"},
            {"SHIFT:1.5", "z", "x", "c", "v", "b", "n", "m", "DEL:1.5"},
            {"SYM:1.5", "MIC:1.2", ",", "SPACE:3.8", ".", "ENTER:1.5"}};
    private static final String[][] SYMBOLS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"@", "#", "$", "&", "-", "+", "(", ")", "/", "'"},
            {"?:1.2", "!", "\"", ":", ";", "*", "=", "%", "DEL:1.8"},
            {"ABC:1.5", "MIC:1.2", ",", "SPACE:3.8", ".", "ENTER:1.5"}};

    private final class Board extends View {
        private final class K { String out, label; float w; final RectF r = new RectF(); boolean special; }
        private final List<K> keys = new ArrayList<>();
        private boolean builtFor; private int builtW;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private K down, lastKey; private long lastAt; private boolean repeated;
        private final Runnable repeat = new Runnable() { public void run() { if (down != null && down.out.equals("DEL")) { repeated = true; onKey("DEL"); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); h.postDelayed(this, 90); } } };

        Board() { super(Keys.this); text.setTypeface(Theme.regular); text.setTextAlign(Paint.Align.CENTER); setContentDescription("Keyboard"); }

        // ---- screen readers (TalkBack, Switch Access): each drawn key is its own button
        private int a11yFocus = -1, hovered = -1;

        private String spoken(K k) {
            switch (k.out) {
                case "SHIFT": return caps ? "Capitals, locked" : shift ? "Shift, on" : "Shift";
                case "DEL": return "Delete";
                case "SYM": return "Numbers and symbols";
                case "ABC": return "Letters";
                case "MIC": return "Voice typing";
                case "SPACE": return "Space";
                case "ENTER": { String l = enterLabel(); return l.equals("↵") ? "New line" : l; }
                default: return shift || caps ? k.out.toUpperCase(Locale.ROOT) : k.out;
            }
        }

        private void announce(int id, int type) {
            if (id < 0 || id >= keys.size() || getParent() == null) return;
            android.view.accessibility.AccessibilityEvent ev = android.view.accessibility.AccessibilityEvent.obtain(type);
            ev.setPackageName(getPackageName()); ev.setClassName("android.widget.Button");
            ev.setSource(this, id); ev.setContentDescription(spoken(keys.get(id)));
            getParent().requestSendAccessibilityEvent(this, ev);
        }

        private final android.view.accessibility.AccessibilityNodeProvider provider = new android.view.accessibility.AccessibilityNodeProvider() {
            @Override public android.view.accessibility.AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                if (keys.isEmpty()) layout();
                if (id == View.NO_ID) {
                    android.view.accessibility.AccessibilityNodeInfo host = new android.view.accessibility.AccessibilityNodeInfo(Board.this);
                    onInitializeAccessibilityNodeInfo(host);
                    for (int i = 0; i < keys.size(); i++) host.addChild(Board.this, i);
                    return host;
                }
                if (id < 0 || id >= keys.size()) return null;
                K k = keys.get(id);
                android.view.accessibility.AccessibilityNodeInfo n = new android.view.accessibility.AccessibilityNodeInfo(Board.this, id);
                n.setPackageName(getPackageName()); n.setClassName("android.widget.Button");
                n.setParent(Board.this); n.setContentDescription(spoken(k));
                n.setEnabled(true); n.setVisibleToUser(true); n.setClickable(true); n.setFocusable(true);
                android.graphics.Rect r = new android.graphics.Rect(); k.r.roundOut(r);
                n.setBoundsInParent(r);
                int[] at = new int[2]; getLocationOnScreen(at); r.offset(at[0], at[1]); n.setBoundsInScreen(r);
                n.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                n.addAction(a11yFocus == id ? android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                        : android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
                n.setAccessibilityFocused(a11yFocus == id);
                return n;
            }
            @Override public boolean performAction(int id, int action, Bundle args) {
                if (id == View.NO_ID) return performAccessibilityAction(action, args);
                if (id < 0 || id >= keys.size()) return false;
                switch (action) {
                    case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK:
                        onKey(keys.get(id).out); announce(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED); return true;
                    case android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                        a11yFocus = id; invalidate(); announce(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED); return true;
                    case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                        if (a11yFocus == id) a11yFocus = -1; invalidate(); announce(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED); return true;
                }
                return false;
            }
        };

        @Override public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() { return provider; }

        /** Touch exploration: sliding a finger over the keys reads each one aloud. */
        @Override public boolean dispatchHoverEvent(MotionEvent e) {
            android.view.accessibility.AccessibilityManager am = getContext().getSystemService(android.view.accessibility.AccessibilityManager.class);
            if (am == null || !am.isTouchExplorationEnabled()) return super.dispatchHoverEvent(e);
            if (keys.isEmpty()) layout();
            int id = e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT ? -1 : keys.indexOf(at(e.getX(), e.getY()));
            if (id != hovered) {
                announce(hovered, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
                hovered = id;
                announce(hovered, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
            }
            return true;
        }

        int keyHeight() {
            int max = Math.round(getResources().getDisplayMetrics().heightPixels * .105f);
            return Math.min(max, Math.max(t.dp(54), Math.round(t.dp(52) * grow())));
        }
        int boardHeight() { return keyHeight() * 4 + t.dp(14); }

        @Override protected void onMeasure(int ws, int hs) { setMeasuredDimension(MeasureSpec.getSize(ws), boardHeight()); }

        private void layout() {
            keys.clear();
            int W = getWidth(), kh = keyHeight(), pad = t.dp(4);
            String[][] rows = symbols ? SYMBOLS : LETTERS;
            float y = t.dp(6);
            for (String[] row : rows) {
                float total = 0;
                List<K> rk = new ArrayList<>();
                for (String s : row) {
                    K k = new K(); int c = s.lastIndexOf(':');
                    String name = c > 0 && s.length() > 2 ? s.substring(0, c) : s;
                    k.w = c > 0 && s.length() > 2 ? Float.parseFloat(s.substring(c + 1)) : 1;
                    if (name.startsWith("_")) { k.out = null; k.w = Float.parseFloat(name.substring(1)); } else k.out = name;
                    k.special = name.length() > 1 && name.equals(name.toUpperCase(Locale.ROOT));
                    total += k.w; rk.add(k);
                }
                float unit = (W - pad) / Math.max(10f, total), x = pad / 2f + (W - pad - unit * total) / 2f;
                for (K k : rk) {
                    k.r.set(x + pad / 2f, y + pad / 2f, x + unit * k.w - pad / 2f, y + kh - pad / 2f);
                    x += unit * k.w;
                    if (k.out != null) keys.add(k);
                }
                y += kh;
            }
            builtFor = symbols; builtW = W;
        }

        private String label(K k) {
            switch (k.out) {
                case "SHIFT": return "⇧";
                case "DEL": return "⌫";
                case "SYM": return "123";
                case "ABC": return "ABC";
                case "SPACE": return "";
                case "ENTER": return enterLabel();
                case "MIC": return "";
                default: return shift || caps ? k.out.toUpperCase(Locale.ROOT) : k.out;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (keys.isEmpty() || builtFor != symbols || builtW != getWidth()) layout();
            float rad = t.dp(10);
            for (K k : keys) {
                boolean lit = k.out.equals("SHIFT") && (shift || caps);
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(lit ? t.sage : k == down ? (t.night ? 0xff2c3870 : 0xffd9cdb0) : k.special ? t.paper : t.paperHi);
                c.drawRoundRect(k.r, rad, rad, fill);
                line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(t.dp(t.contrast ? 1.6f : 1f)); line.setColor(t.sepia); line.setAlpha(t.contrast ? 200 : 90);
                c.drawRoundRect(k.r, rad, rad, line);
                if (a11yFocus >= 0 && a11yFocus < keys.size() && keys.get(a11yFocus) == k) {
                    line.setAlpha(255); line.setColor(t.gold); line.setStrokeWidth(t.dp(3)); c.drawRoundRect(k.r, rad, rad, line);
                }
                if (k.out.equals("MIC")) { drawMic(c, k.r.centerX(), k.r.centerY(), k.r.height() * .2f); continue; }
                if (k.out.equals("SPACE")) {   // a small sprig instead of a word
                    line.setAlpha(140); line.setStrokeWidth(t.dp(1.2f));
                    float cx = k.r.centerX(), cy = k.r.centerY() + k.r.height() * .12f, half = k.r.width() * .18f;
                    c.drawLine(cx - half, cy, cx + half, cy, line);
                    continue;
                }
                String l = label(k);
                boolean word = l.length() > 1;
                text.setTypeface(word ? Theme.semi : Theme.regular);
                text.setColor(lit ? (t.night ? 0xff1e2650 : 0xfff3ecda) : k.special ? t.sepia : t.ink);
                float size = (word ? t.dp(15) : t.dp(23)) * grow();
                text.setTextSize(size);
                while (text.measureText(l) > k.r.width() - t.dp(8) && size > t.dp(9)) { size -= t.dp(1); text.setTextSize(size); }
                Paint.FontMetrics fm = text.getFontMetrics();
                c.drawText(l, k.r.centerX(), k.r.centerY() - (fm.ascent + fm.descent) / 2f, text);
            }
        }

        private void drawMic(Canvas c, float cx, float cy, float u) {
            Paint p = text; int was = p.getColor();
            p.setColor(t.sage); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(cx - u * .45f, cy - u * 1.25f, cx + u * .45f, cy + u * .25f), u * .45f, u * .45f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(u * .2f); p.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(new RectF(cx - u * .8f, cy - u * .75f, cx + u * .8f, cy + u * .65f), 20, 140, false, p);
            c.drawLine(cx, cy + u * .65f, cx, cy + u * 1.1f, p);
            p.setStyle(Paint.Style.FILL); p.setColor(was);
        }

        /** The key under the finger, or the nearest one (gaps between keys still count). */
        private K at(float x, float y) {
            K best = null; float bd = Float.MAX_VALUE;
            for (K k : keys) {
                float dx = Math.max(0, Math.max(k.r.left - x, x - k.r.right)), dy = Math.max(0, Math.max(k.r.top - y, y - k.r.bottom));
                float d = dx * dx + dy * dy;
                if (d < bd) { bd = d; best = k; }
            }
            return best;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    begin(at(e.getX(), e.getY())); return true;
                case MotionEvent.ACTION_POINTER_DOWN: {          // quick typing: the first key lands when the next one is touched
                    finish();
                    int i = e.getActionIndex(); begin(at(e.getX(i), e.getY(i))); return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP:
                    finish(); return true;
                case MotionEvent.ACTION_CANCEL:
                    h.removeCallbacks(repeat); down = null; invalidate(); return true;
            }
            return true;   // moving the finger doesn't change the key: an unsteady press still types what it started on
        }

        private void begin(K k) {
            down = k; repeated = false;
            if (k == null) return;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (k.out.equals("DEL")) h.postDelayed(repeat, 500);
            invalidate();
        }

        private void finish() {
            h.removeCallbacks(repeat);
            K k = down; down = null;
            if (k == null) { invalidate(); return; }
            long now = SystemClock.uptimeMillis();
            // steadier taps: the same key twice within 0.15 s is a tremble, not a double letter
            boolean tremble = t.steady && k == lastKey && now - lastAt < 150;
            lastKey = k; lastAt = now;
            if (!(k.out.equals("DEL") && repeated) && !tremble) onKey(k.out);
            invalidate();
        }
    }
}
