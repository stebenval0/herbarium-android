package com.herbarium.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Herbarium's own ear: offline speech-to-text (Vosk) for phones without Google's voice service, so voice typing
 * always works. The English model (~40 MB) comes from Herbarium's own releases the first time, checked by fingerprint.
 * Patient with slow or soft speech: each phrase is written as soon as it's understood, and listening only ends
 * after a few seconds of real quiet.
 */
final class OwnEar {
    private OwnEar() {}

    static final String MODEL_URL = "https://github.com/stebenval0/herbarium-android/releases/download/voice-en-1/vosk-model-small-en-us-0.15.zip";
    static final String MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";
    private static final long QUIET_ENDS_MS = 3500, NOTHING_SAID_MS = 12000;

    interface Ear {
        void preparing(int percent);          // first time only: fetching the model
        void listening();
        void partial(String text);             // what it's hearing so far
        void heard(String text);               // a finished phrase, to write
        void stopped(String problem);          // null when it simply ended
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Model model;
    private static SpeechService service;
    private static volatile boolean preparing;

    static File dir(Context c) { return new File(Theme.store(c).getFilesDir(), "voice-en-1"); }
    static boolean ready(Context c) { return new File(dir(c), "ready").exists(); }

    /** Start listening (fetching the model first if needed). Call on the main thread. */
    static void start(Context c, Ear ear) {
        Context app = c.getApplicationContext();
        if (preparing) { ear.preparing(-1); return; }
        stop();
        new Thread(() -> {
            try {
                if (!ready(app)) { preparing = true; fetch(app, ear); }
                if (model == null) { LibVosk.setLogLevel(LogLevel.WARNINGS); model = new Model(new File(dir(app), "model").getPath()); }
                Recognizer rec = new Recognizer(model, 16000f);
                main.post(() -> begin(rec, ear));
            } catch (Exception e) {
                main.post(() -> ear.stopped(ready(app) ? "Voice typing couldn't start. Tap the circle to try again."
                        : "Voice typing needs the internet once, to get ready. Tap the circle to try again."));
            } finally { preparing = false; }
        }, "herbarium-ear").start();
    }

    private static void begin(Recognizer rec, Ear ear) {
        try { service = new SpeechService(rec, 16000f); }
        catch (Exception e) { ear.stopped("The microphone is busy. Tap the circle to try again."); return; }
        final long[] last = {SystemClock.uptimeMillis()};
        final boolean[] spoke = {false};
        final String[] shown = {""};
        final SpeechService mine = service;
        Runnable watch = new Runnable() {
            public void run() {
                if (service != mine) return;
                long quiet = SystemClock.uptimeMillis() - last[0];
                if ((spoke[0] && quiet > QUIET_ENDS_MS) || (!spoke[0] && quiet > NOTHING_SAID_MS)) { mine.stop(); return; }
                main.postDelayed(this, 400);
            }
        };
        service.startListening(new RecognitionListener() {
            @Override public void onPartialResult(String json) {
                String p = field(json, "partial");
                if (!p.equals(shown[0])) { shown[0] = p; if (!p.isEmpty()) { spoke[0] = true; last[0] = SystemClock.uptimeMillis(); ear.partial(p); } }
            }
            @Override public void onResult(String json) {
                String r = field(json, "text"); shown[0] = "";
                if (!r.isEmpty()) { spoke[0] = true; last[0] = SystemClock.uptimeMillis(); ear.heard(r); }
            }
            @Override public void onFinalResult(String json) {
                String r = field(json, "text");
                if (!r.isEmpty()) { spoke[0] = true; ear.heard(r); }
                finish(mine);
                ear.stopped(spoke[0] ? null : "I didn't catch that. Tap the circle and try again.");
            }
            @Override public void onError(Exception e) { finish(mine); ear.stopped("Voice typing stopped. Tap the circle to try again."); }
            @Override public void onTimeout() { finish(mine); ear.stopped(null); }
        });
        ear.listening();
        main.postDelayed(watch, 400);
    }

    private static void finish(SpeechService s) {
        if (s == null) return;
        try { s.shutdown(); } catch (Exception ignored) {}
        if (service == s) service = null;
    }

    /** Stop now, dropping anything half-heard. */
    static void stop() {
        SpeechService s = service; service = null;
        if (s != null) { try { s.cancel(); } catch (Exception ignored) {} try { s.shutdown(); } catch (Exception ignored) {} }
    }

    private static String field(String json, String k) {
        try { return new JSONObject(json).optString(k, "").trim(); } catch (Exception e) { return ""; }
    }

    /** Download the model from Herbarium's releases, check its fingerprint, and unpack it. */
    private static void fetch(Context c, Ear ear) throws Exception {
        File d = dir(c), tmp = new File(d.getParentFile(), "voice-en-1.part");
        delete(tmp); tmp.mkdirs();
        String url = MODEL_URL; HttpURLConnection h = null;
        for (int hops = 0; hops < 5; hops++) {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false); h.setConnectTimeout(15000); h.setReadTimeout(60000); h.setRequestProperty("User-Agent", "Herbarium/2");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) { url = h.getHeaderField("Location"); h.disconnect(); h = null; continue; }
            if (code != 200) throw new Exception("download said " + code);
            break;
        }
        if (h == null) throw new Exception("too many redirects");
        long total = h.getContentLengthLong();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        File zip = new File(tmp, "model.zip");
        try (InputStream in = new DigestInputStream(h.getInputStream(), md); OutputStream o = new FileOutputStream(zip)) {
            byte[] buf = new byte[65536]; long got = 0; int n, lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n); got += n;
                int pct = total > 0 ? (int) (got * 100 / total) : -1;
                if (pct != lastPct) { lastPct = pct; final int p = pct; main.post(() -> ear.preparing(p)); }
            }
        } finally { h.disconnect(); }
        StringBuilder hex = new StringBuilder(); for (byte b : md.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equals(MODEL_SHA256)) { delete(tmp); throw new Exception("the voice model didn't match its fingerprint"); }
        File out = new File(tmp, "model"); out.mkdirs();
        String root = out.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry e; byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName(); int slash = name.indexOf('/');
                if (slash < 0 || slash == name.length() - 1) continue;          // the top folder itself
                File f = new File(out, name.substring(slash + 1));
                if (!f.getCanonicalPath().startsWith(root)) continue;            // nothing outside the folder
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) { int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
            }
        }
        zip.delete();
        new File(tmp, "ready").createNewFile();
        delete(d);
        if (!tmp.renameTo(d)) throw new Exception("couldn't put the voice model in place");
    }

    private static void delete(File f) {
        if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File x : k) delete(x); }
        f.delete();
    }
}
