package com.herbarium.home;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reaching the keeper's computer from anywhere: it posts what to block to a private GitHub repository
 * (state.json, signed with this phone's key), and this phone leaves checkin.json there.
 */
final class Relay {
    private Relay() {}

    static String repo(Context c) { return State.prefs(c).getString("relayRepo", ""); }
    static boolean configured(Context c) { return !repo(c).isEmpty() && !State.prefs(c).getString("relayToken", "").isEmpty(); }

    /** The invitation from `clausus phone invite`: herbarium://pair?c=<base64 of {"repo","token","key"}> (or just the code). */
    static JSONObject parseInvite(String text) {
        if (text == null) return null;
        text = text.trim();
        int i = text.indexOf("c=");
        String code = i >= 0 ? text.substring(i + 2) : text;
        code = code.replaceAll("[^A-Za-z0-9_\\-]", "");
        try {
            JSONObject o = new JSONObject(new String(Base64.decode(code, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), "UTF-8"));
            if (o.optString("repo").contains("/") && !o.optString("token").isEmpty() && !o.optString("key").isEmpty()) return o;
        } catch (Exception ignored) {}
        return null;
    }

    /** Accept an invitation (refused while a block is on and the keeper would change). */
    static boolean accept(Context c, JSONObject inv) {
        String key = inv.optString("key");
        if (State.locked(c) && !State.token(c).isEmpty() && !key.equals(State.token(c))) return false;
        State.prefs(c).edit().putString("token", key).putString("relayRepo", inv.optString("repo"))
                .putString("relayToken", inv.optString("token")).apply();
        ClaususService.start(c);
        return true;
    }

    private static byte[] get(Context c, String path, boolean raw) throws Exception {
        HttpURLConnection h = open(c, path, "GET");
        h.setRequestProperty("Accept", raw ? "application/vnd.github.raw" : "application/vnd.github+json");
        int code = h.getResponseCode();
        if (code == 404) return null;
        if (code >= 300) throw new Exception("GitHub said " + code);
        return read(h);
    }

    private static HttpURLConnection open(Context c, String path, String method) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo(c) + "/contents/" + path).openConnection();
        h.setConnectTimeout(10000); h.setReadTimeout(30000); h.setRequestMethod(method);
        h.setRequestProperty("Authorization", "Bearer " + State.prefs(c).getString("relayToken", ""));
        h.setRequestProperty("X-GitHub-Api-Version", "2022-11-28"); h.setRequestProperty("User-Agent", "Herbarium/2");
        return h;
    }

    private static byte[] read(HttpURLConnection h) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) { byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
        finally { h.disconnect(); }
        return b.toByteArray();
    }

    /** What to block, if the keeper's computer signed it with this phone's key; null otherwise. */
    static JSONObject state(Context c) throws Exception {
        byte[] b = get(c, "state.json", true);
        if (b == null) return null;
        JSONObject env = new JSONObject(new String(b, "UTF-8"));
        String payload = env.optString("state"), sig = env.optString("sig");
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(State.token(c).getBytes("UTF-8"), "HmacSHA256"));
        StringBuilder hex = new StringBuilder(); for (byte x : mac.doFinal(payload.getBytes("UTF-8"))) hex.append(String.format("%02x", x));
        if (!hex.toString().equals(sig)) throw new Exception("the relay's message isn't signed by the keeper");
        return new JSONObject(payload);
    }

    static byte[] feed(Context c) throws Exception { return get(c, "feed.txt", true); }

    /** Leave a note for the keeper's computer: when this phone last checked, and whether it's protected. */
    static void checkIn(Context c, JSONObject me) throws Exception {
        byte[] cur = get(c, "checkin.json", false);
        String sha = cur == null ? null : new JSONObject(new String(cur, "UTF-8")).optString("sha", null);
        JSONObject body = new JSONObject().put("message", "phone check-in")
                .put("content", Base64.encodeToString(me.toString().getBytes("UTF-8"), Base64.NO_WRAP));
        if (sha != null) body.put("sha", sha);
        HttpURLConnection h = open(c, "checkin.json", "PUT");
        h.setDoOutput(true); h.setRequestProperty("Content-Type", "application/json"); h.setRequestProperty("Accept", "application/vnd.github+json");
        try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
        int code = h.getResponseCode(); h.disconnect();
        if (code >= 300) throw new Exception("GitHub said " + code);
    }
}
