package com.herbarium.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Nuntius's mailbox: a private GitHub repository shared with the Claude routine and the computer.
 *   summary.json   written by the routine, read here
 *   notes.json     this phone's ticks and notes (same shape as before: {"notes": [...], "done": [...]})
 *   refresh.flag   "please write a fresh summary"
 */
final class Mailbox {
    private Mailbox() {}

    static SharedPreferences p(Context c) { return Theme.prefs(c); }
    static String repo(Context c) { return p(c).getString("mbRepo", ""); }
    static boolean configured(Context c) { return !repo(c).isEmpty() && !p(c).getString("mbToken", "").isEmpty(); }
    static String error(Context c) { return p(c).getString("mbError", ""); }
    static long lastFetch(Context c) { return p(c).getLong("mbFetch", 0); }
    static long asked(Context c) { return p(c).getLong("mbAsked", 0); }

    static void configure(Context c, String repo, String token) {
        repo = repo.trim().replaceFirst("^https?://github.com/", "").replaceAll("/+$", "");
        SharedPreferences.Editor e = p(c).edit().putString("mbRepo", repo).remove("summarySha").remove("mbError");
        if (token != null && !token.trim().isEmpty()) e.putString("mbToken", token.trim());
        e.apply();
    }
    static void forget(Context c) { p(c).edit().remove("mbRepo").remove("mbToken").remove("summarySha").remove("mbError").apply(); }

    static JSONObject summary(Context c) {
        String s = p(c).getString("summary", "");
        try { return s.isEmpty() ? null : new JSONObject(s); } catch (Exception e) { return null; }
    }

    // ---------------------------------------------------------------- ticks and notes
    static JSONObject notes(Context c) {
        try {
            JSONObject o = new JSONObject(p(c).getString("notes", "{}"));
            if (!o.has("notes")) o.put("notes", new JSONArray());
            if (!o.has("done")) o.put("done", new JSONArray());
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }
    static void saveNotes(Context c, JSONObject n) { p(c).edit().putString("notes", n.toString()).putBoolean("notesDirty", true).apply(); }

    static String itemId(JSONObject it) {
        String id = it.optString("id", "");
        if (!id.isEmpty()) return id;
        String a = it.optString("time", ""); if (a.isEmpty()) a = it.optString("from", "");
        String b = it.optString("title", ""); if (b.isEmpty()) b = it.optString("subject", "");
        String s = (a + "|" + b).toLowerCase(Locale.ROOT);
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    static boolean done(Context c, String id) {
        JSONArray d = notes(c).optJSONArray("done");
        for (int i = 0; d != null && i < d.length(); i++) if (id.equals(d.optString(i))) return true;
        return false;
    }

    /** Tick or untick an item; a tick also becomes a "✓ Done: …" note, as before. */
    static void toggle(Context c, String id, String what) {
        try {
            JSONObject n = notes(c); JSONArray d = n.getJSONArray("done"), ns = n.getJSONArray("notes");
            JSONArray nd = new JSONArray(), nn = new JSONArray(); boolean was = false;
            for (int i = 0; i < d.length(); i++) if (id.equals(d.optString(i))) was = true; else nd.put(d.optString(i));
            for (int i = 0; i < ns.length(); i++) { JSONObject x = ns.getJSONObject(i); if (!(was && id.equals(x.optString("tick")))) nn.put(x); }
            if (!was) {
                nd.put(id);
                nn.put(new JSONObject().put("at", now()).put("text", "✓ Done: " + (what.length() > 120 ? what.substring(0, 120) : what)).put("tick", id));
            }
            n.put("done", nd); n.put("notes", nn); saveNotes(c, n);
        } catch (Exception ignored) {}
    }

    static void tell(Context c, String text) {
        text = text.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) return;
        try { JSONObject n = notes(c); n.getJSONArray("notes").put(new JSONObject().put("at", now()).put("text", text.length() > 500 ? text.substring(0, 500) : text)); saveNotes(c, n); }
        catch (Exception ignored) {}
    }

    static void unnote(Context c, int index) {
        try { JSONObject n = notes(c); JSONArray ns = n.getJSONArray("notes"), out = new JSONArray(); for (int i = 0; i < ns.length(); i++) if (i != index) out.put(ns.get(i)); n.put("notes", out); saveNotes(c, n); }
        catch (Exception ignored) {}
    }

    static String now() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new java.util.Date()); }
    static long parse(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        for (String fmt : new String[]{"yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mmX"}) {
            try { return new SimpleDateFormat(fmt, Locale.US).parse(iso.replace("Z", "+00:00").replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2")).getTime(); } catch (Exception ignored) {}
        }
        return 0;
    }

    /** Once the routine has used notes (summary.notes_through) they're cleared; ticks stay only for items still listed. */
    static void prune(Context c, JSONObject s) {
        long through = parse(s.optString("notes_through"));
        if (through == 0) return;
        try {
            Set<String> ids = new HashSet<>();
            for (String k : new String[]{"today", "tomorrow", "letters", "messages"}) { JSONArray a = s.optJSONArray(k); for (int i = 0; a != null && i < a.length(); i++) ids.add(itemId(a.getJSONObject(i))); }
            JSONObject n = notes(c); JSONArray ns = n.getJSONArray("notes"), d = n.getJSONArray("done"), nn = new JSONArray(), nd = new JSONArray();
            for (int i = 0; i < ns.length(); i++) if (parse(ns.getJSONObject(i).optString("at")) > through) nn.put(ns.get(i));
            for (int i = 0; i < d.length(); i++) if (ids.contains(d.optString(i))) nd.put(d.optString(i));
            n.put("notes", nn); n.put("done", nd); saveNotes(c, n);
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- talking to GitHub (background thread only)
    private static JSONObject gh(Context c, String path, String method, JSONObject body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo(c) + "/contents/" + path).openConnection();
        h.setConnectTimeout(10000); h.setReadTimeout(15000); h.setRequestMethod(method);
        h.setRequestProperty("Authorization", "Bearer " + p(c).getString("mbToken", ""));
        h.setRequestProperty("Accept", "application/vnd.github+json"); h.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        h.setRequestProperty("User-Agent", "Herbarium/2");
        if (body != null) { h.setDoOutput(true); h.setRequestProperty("Content-Type", "application/json"); try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); } }
        int code = h.getResponseCode();
        if (code == 404 && method.equals("GET")) return null;
        if (code >= 300) throw new Exception(code == 401 ? "the token wasn't accepted" : code == 403 ? "the token can't reach that repository" : code == 404 ? "that repository wasn't found" : "GitHub said " + code);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
        return new JSONObject(b.toString("UTF-8"));
    }

    private static void put(Context c, String path, byte[] raw) throws Exception {
        JSONObject cur = gh(c, path, "GET", null);
        JSONObject body = new JSONObject().put("message", "phone: " + path).put("content", Base64.encodeToString(raw, Base64.NO_WRAP));
        if (cur != null) body.put("sha", cur.optString("sha"));
        gh(c, path, "PUT", body);
    }

    /** Fetch a new summary if it changed, and send this phone's notes if they changed. True when a new summary arrived. */
    static boolean sync(Context c) {
        if (!configured(c)) return false;
        boolean fresh = false;
        try {
            if (p(c).getBoolean("notesDirty", false)) { put(c, "notes.json", notes(c).toString().getBytes("UTF-8")); p(c).edit().putBoolean("notesDirty", false).apply(); }
            JSONObject o = gh(c, "summary.json", "GET", null);
            if (o != null && !o.optString("sha").equals(p(c).getString("summarySha", ""))) {
                String raw = new String(Base64.decode(o.optString("content"), Base64.DEFAULT), "UTF-8");
                JSONObject s = new JSONObject(raw);
                p(c).edit().putString("summary", s.toString()).putString("summarySha", o.optString("sha")).apply();
                prune(c, s); fresh = true;
            }
            p(c).edit().putLong("mbFetch", System.currentTimeMillis()).remove("mbError").apply();
        } catch (Exception e) {
            p(c).edit().putLong("mbFetch", System.currentTimeMillis()).putString("mbError", String.valueOf(e.getMessage())).apply();
        }
        return fresh;
    }

    /** Ask the routine for a fresh summary: send the notes and leave the flag. Returns "" or what went wrong. */
    static String askForFresh(Context c) {
        if (!configured(c)) return "Nuntius isn't connected to a mailbox yet.";
        try {
            put(c, "notes.json", notes(c).toString().getBytes("UTF-8"));
            put(c, "refresh.flag", String.valueOf(System.currentTimeMillis()).getBytes("UTF-8"));
            p(c).edit().putBoolean("notesDirty", false).putLong("mbAsked", System.currentTimeMillis()).remove("mbError").apply();
            return "";
        } catch (Exception e) { p(c).edit().putString("mbError", String.valueOf(e.getMessage())).apply(); return String.valueOf(e.getMessage()); }
    }
}
