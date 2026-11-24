package com.herbarium.home;

import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import com.google.android.mms.pdu_alt.AcknowledgeInd;
import com.google.android.mms.pdu_alt.CharacterSets;
import com.google.android.mms.pdu_alt.EncodedStringValue;
import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.PduBody;
import com.google.android.mms.pdu_alt.PduComposer;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPart;
import com.google.android.mms.pdu_alt.RetrieveConf;
import com.google.android.mms.pdu_alt.SendReq;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Picture and group messages (MMS). Android's own message service does the network part (fetching from and
 * sending to the carrier); Herbarium reads and writes the messages (Android's MMS format code, in vendor/) and keeps
 * them in Android's message store, the same way the phone's own texting app does.
 *
 * Arriving: the carrier sends a short notice ("a picture message is waiting at …") → Herbarium asks Android to fetch
 * it → TextsIn.Done: the whole message is read, saved (who, words, pictures) and announced, and the carrier is told it
 * arrived. If fetching fails it's kept on a list and tried again (up to 6 times), so nothing is lost.
 */
final class MmsWork {
    private MmsWork() {}

    static final String DOWNLOADED = "com.herbarium.home.MMS_DOWNLOADED", SENT = "com.herbarium.home.MMS_SENT";
    private static final int MAX_TRIES = 6;

    static File dir(Context c) { File d = new File(c.getCacheDir(), "mms"); d.mkdirs(); return d; }

    private static String str(EncodedStringValue v) { return v == null ? null : v.getString(); }
    private static String str(byte[] b) { return b == null ? null : new String(b); }

    // ------------------------------------------------------------------ arriving
    /** The carrier's notice that a message is waiting. Runs off the main thread. */
    static void push(Context c, byte[] pdu, int subId) {
        if (pdu == null) return;
        GenericPdu g;
        try { g = new PduParser(pdu, true).parse(); } catch (Exception e) { g = null; }
        if (!(g instanceof NotificationInd)) return;      // delivery reports and read reports: nothing to fetch
        NotificationInd n = (NotificationInd) g;
        String location = str(n.getContentLocation()), tx = str(n.getTransactionId());
        if (location == null) return;
        String from = str(n.getFrom());
        if (seen(c, tx == null ? location : tx)) return;  // the carrier sometimes says it twice
        rememberWaiting(c, location, tx, from, subId, 0);
        fetch(c, location, tx, from, subId, 0);
    }

    static void fetch(Context c, String location, String tx, String from, int subId, int tries) {
        String name = "in-" + System.nanoTime() + ".pdu";
        Uri u = MmsFiles.uri(c, name);
        Intent done = new Intent(c, TextsIn.Done.class).setAction(DOWNLOADED).putExtra("file", name).putExtra("location", location)
                .putExtra("tx", tx).putExtra("from", from).putExtra("sub", subId).putExtra("tries", tries);
        PendingIntent pi = PendingIntent.getBroadcast(c, (int) (System.nanoTime() & 0x7fffffff), done, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT);
        try { Texts.sms(c, subId).downloadMultimediaMessage(c, location, u, null, pi); }
        catch (Exception e) { scheduleRetry(c, location, tx, from, subId, tries + 1); }
    }

    /** Android fetched it (or couldn't). */
    static void downloaded(Context c, Intent i, boolean ok) {
        String name = i.getStringExtra("file"), location = i.getStringExtra("location"), tx = i.getStringExtra("tx"), from = i.getStringExtra("from");
        int subId = i.getIntExtra("sub", -1), tries = i.getIntExtra("tries", 0);
        File f = new File(dir(c), name == null ? "x" : name);
        byte[] data = ok ? read(f) : null;
        f.delete();
        GenericPdu g = null;
        try { if (data != null && data.length > 0) g = new PduParser(data, true).parse(); } catch (Exception ignored) {}
        if (!(g instanceof RetrieveConf)) { scheduleRetry(c, location, tx, from, subId, tries + 1); return; }
        RetrieveConf rc = (RetrieveConf) g;
        long thread = save(c, rc, subId);
        forgetWaiting(c, location);
        acknowledge(c, rc, subId);
        if (thread >= 0) {
            String sender = str(rc.getFrom());
            Msg words = new Msg(); fillFromPdu(rc, words);
            String text = words.text.isEmpty() ? (words.pictures > 0 ? "Sent a picture" : "Sent a message") : words.text;
            Texts.notifyIncoming(c, thread, sender == null ? "" : sender, text);
        }
        Texts.changed(c);
    }

    private static final class Msg { String text = ""; int pictures; }

    private static void fillFromPdu(RetrieveConf rc, Msg m) {
        PduBody b = rc.getBody(); if (b == null) return;
        StringBuilder t = new StringBuilder();
        for (int k = 0; k < b.getPartsNum(); k++) {
            PduPart p = b.getPart(k); String ct = str(p.getContentType());
            if (ct == null) continue;
            if (ct.startsWith("text/plain")) { if (t.length() > 0) t.append("\n"); t.append(text(p)); }
            else if (ct.startsWith("image/")) m.pictures++;
        }
        m.text = t.toString();
    }

    private static String text(PduPart p) {
        byte[] d = p.getData(); if (d == null) return "";
        try {
            int cs = p.getCharset();
            String name = cs == 0 ? "UTF-8" : CharacterSets.getMimeName(cs);
            return new String(d, name);
        } catch (Exception e) { return new String(d); }
    }

    /** Who's in the conversation: the sender and everyone it went to, except this phone. */
    static Set<String> people(Context c, String from, EncodedStringValue[] to, EncodedStringValue[] cc) {
        Set<String> mine = myNumbers(c);
        Set<String> all = new LinkedHashSet<>();
        if (from != null && !from.isEmpty() && !from.equals("insert-address-token")) all.add(from);
        for (EncodedStringValue[] list : new EncodedStringValue[][]{to, cc}) if (list != null) for (EncodedStringValue v : list) {
            String a = v == null ? null : v.getString();
            if (a == null || a.isEmpty() || a.equals("insert-address-token")) continue;
            if (mine.contains(Sorter.key(a))) continue;
            boolean dup = false; for (String x : all) if (Sorter.key(x).equals(Sorter.key(a))) { dup = true; break; }
            if (!dup) all.add(a);
        }
        return all;
    }

    static Set<String> myNumbers(Context c) {
        Set<String> s = new java.util.HashSet<>();
        try {
            android.telephony.SubscriptionManager sm = c.getSystemService(android.telephony.SubscriptionManager.class);
            if (sm != null) for (android.telephony.SubscriptionInfo si : sm.getActiveSubscriptionInfoList()) {
                String n = android.os.Build.VERSION.SDK_INT >= 33 ? sm.getPhoneNumber(si.getSubscriptionId()) : si.getNumber();
                if (n != null && !n.isEmpty()) s.add(Sorter.key(n));
            }
        } catch (Exception ignored) {}
        // some carriers never tell the phone its own number: Herbarium learns it from picture messages (see save)
        for (String n : Theme.prefs(c).getStringSet("selfNumbers", new java.util.HashSet<>())) s.add(n);
        return s;
    }

    /** Keep a fetched message in Android's store, as the phone's own texting app does. Returns its conversation. */
    static long save(Context c, RetrieveConf rc, int subId) {
        String from = str(rc.getFrom());
        // a message to exactly one address, from someone else, was sent to this phone: that address is ours
        EncodedStringValue[] to0 = rc.getTo();
        if (to0 != null && to0.length == 1 && (rc.getCc() == null || rc.getCc().length == 0) && to0[0] != null && to0[0].getString() != null
                && from != null && !Sorter.key(from).equals(Sorter.key(to0[0].getString())) && !to0[0].getString().equals("insert-address-token")) {
            Set<String> self = new java.util.HashSet<>(Theme.prefs(c).getStringSet("selfNumbers", new java.util.HashSet<>()));
            if (self.add(Sorter.key(to0[0].getString()))) Theme.prefs(c).edit().putStringSet("selfNumbers", self).apply();
        }
        Set<String> who = people(c, from, rc.getTo(), rc.getCc());
        if (who.isEmpty()) who.add(from == null ? "Unknown" : from);
        long thread = Telephony.Threads.getOrCreateThreadId(c, who);
        ContentValues v = new ContentValues();
        v.put("thread_id", thread);
        long date = rc.getDate() > 0 ? rc.getDate() : System.currentTimeMillis() / 1000;
        v.put("date", System.currentTimeMillis() / 1000); v.put("date_sent", date);
        v.put("msg_box", Telephony.Mms.MESSAGE_BOX_INBOX); v.put("read", 0); v.put("seen", 0);
        v.put("m_type", PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF); v.put("v", rc.getMmsVersion());
        if (rc.getMessageId() != null) v.put("m_id", str(rc.getMessageId()));
        if (rc.getTransactionId() != null) v.put("tr_id", str(rc.getTransactionId()));
        if (rc.getContentType() != null) v.put("ct_t", str(rc.getContentType()));
        if (rc.getSubject() != null) { v.put("sub", str(rc.getSubject())); v.put("sub_cs", rc.getSubject().getCharacterSet()); }
        if (rc.getMessageClass() != null) v.put("m_cls", str(rc.getMessageClass()));
        v.put("pri", rc.getPriority()); v.put("text_only", 0);
        int sub = subId >= 0 ? subId : android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
        if (sub >= 0) v.put("sub_id", sub);
        Uri mu = c.getContentResolver().insert(Telephony.Mms.Inbox.CONTENT_URI, v);
        if (mu == null) return -1;
        String id = mu.getLastPathSegment();
        Uri addr = Uri.parse("content://mms/" + id + "/addr");
        if (from != null) addAddr(c, addr, from, PduHeaders.FROM);
        if (rc.getTo() != null) for (EncodedStringValue x : rc.getTo()) if (x != null) addAddr(c, addr, x.getString(), PduHeaders.TO);
        if (rc.getCc() != null) for (EncodedStringValue x : rc.getCc()) if (x != null) addAddr(c, addr, x.getString(), PduHeaders.CC);
        PduBody b = rc.getBody();
        if (b != null) for (int k = 0; k < b.getPartsNum(); k++) savePart(c, id, b.getPart(k));
        return thread;
    }

    private static void addAddr(Context c, Uri addr, String a, int type) {
        if (a == null) return;
        ContentValues v = new ContentValues(); v.put("address", a); v.put("type", type); v.put("charset", CharacterSets.UTF_8);
        try { c.getContentResolver().insert(addr, v); } catch (Exception ignored) {}
    }

    private static void savePart(Context c, String mmsId, PduPart p) {
        String ct = str(p.getContentType());
        if (ct == null) return;
        ContentValues v = new ContentValues();
        v.put("mid", mmsId); v.put("ct", ct);
        if (p.getCharset() != 0) v.put("chset", p.getCharset());
        if (p.getName() != null) v.put("name", str(p.getName()));
        if (p.getFilename() != null) v.put("fn", str(p.getFilename()));
        if (p.getContentId() != null) v.put("cid", str(p.getContentId()));
        if (p.getContentLocation() != null) v.put("cl", str(p.getContentLocation()));
        boolean words = ct.startsWith("text/plain") || ct.equals("application/smil");
        if (words) v.put("text", text(p));
        try {
            Uri pu = c.getContentResolver().insert(Uri.parse("content://mms/" + mmsId + "/part"), v);
            if (!words && pu != null && p.getData() != null) {
                try (OutputStream o = c.getContentResolver().openOutputStream(pu)) { if (o != null) o.write(p.getData()); }
            }
        } catch (Exception ignored) {}
    }

    /** Tell the carrier it arrived (otherwise some carriers send it again). Best effort. */
    private static void acknowledge(Context c, RetrieveConf rc, int subId) {
        try {
            if (rc.getTransactionId() == null) return;
            AcknowledgeInd ack = new AcknowledgeInd(PduHeaders.CURRENT_MMS_VERSION, rc.getTransactionId());
            byte[] bytes = new PduComposer(c, ack).make();
            if (bytes == null) return;
            String name = "ack-" + System.nanoTime() + ".pdu";
            write(new File(dir(c), name), bytes);
            Intent done = new Intent(c, TextsIn.Done.class).setAction(SENT).putExtra("file", name).putExtra("ack", true);
            Texts.sms(c, subId).sendMultimediaMessage(c, MmsFiles.uri(c, name), null, null,
                    PendingIntent.getBroadcast(c, (int) (System.nanoTime() & 0x7fffffff), done, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ waiting list (so nothing is lost when fetching fails)
    private static synchronized JSONArray waiting(Context c) {
        try { return new JSONArray(Theme.prefs(c).getString("mmsWaiting", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static synchronized void rememberWaiting(Context c, String location, String tx, String from, int subId, int tries) {
        JSONArray a = waiting(c), out = new JSONArray();
        for (int k = 0; k < a.length(); k++) if (!location.equals(a.optJSONObject(k).optString("location"))) out.put(a.optJSONObject(k));
        try { out.put(new JSONObject().put("location", location).put("tx", tx).put("from", from).put("sub", subId).put("tries", tries).put("at", System.currentTimeMillis())); } catch (Exception ignored) {}
        Theme.prefs(c).edit().putString("mmsWaiting", out.toString()).apply();
    }

    private static synchronized void forgetWaiting(Context c, String location) {
        JSONArray a = waiting(c), out = new JSONArray();
        for (int k = 0; k < a.length(); k++) if (!location.equals(a.optJSONObject(k).optString("location"))) out.put(a.optJSONObject(k));
        Theme.prefs(c).edit().putString("mmsWaiting", out.toString()).apply();
    }

    /** How many picture messages are still waiting to be fetched (shown in Messages with a "Try again"). */
    static int waitingCount(Context c) { return waiting(c).length(); }

    /** Try every waiting message again now (opening Messages, or "Try again"). */
    static void retryWaiting(Context c) {
        JSONArray a = waiting(c);
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            fetch(c, o.optString("location"), o.optString("tx", null), o.optString("from", null), o.optInt("sub", -1), o.optInt("tries"));
        }
    }

    private static void scheduleRetry(Context c, String location, String tx, String from, int subId, int tries) {
        if (location == null) return;
        if (tries >= MAX_TRIES) { rememberWaiting(c, location, tx, from, subId, tries); Texts.changed(c); return; }   // kept for "Try again"
        rememberWaiting(c, location, tx, from, subId, tries);
        long delay = Math.min(30 * 60_000L, 30_000L << tries);     // 30 s, 1 min, 2 min … up to 30 min
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> fetch(c.getApplicationContext(), location, tx, from, subId, tries), delay);
    }

    private static final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private static synchronized boolean seen(Context c, String key) {
        if (recent.contains(key)) return true;
        recent.addLast(key); if (recent.size() > 64) recent.removeFirst();
        return false;
    }

    // ------------------------------------------------------------------ sending (group texts)
    /** Send words to several people at once, as one group message. */
    static Uri sendText(Context c, List<String> to, String text) {
        try {
            long thread = Telephony.Threads.getOrCreateThreadId(c, new LinkedHashSet<>(to));
            SendReq req = new SendReq();
            req.setTo(EncodedStringValue.encodeStrings(to.toArray(new String[0])));
            req.setDate(System.currentTimeMillis() / 1000);
            req.setMessageClass("personal".getBytes());
            req.setExpiry(7 * 24 * 60 * 60);
            req.setPriority(PduHeaders.PRIORITY_NORMAL);
            req.setDeliveryReport(PduHeaders.VALUE_NO); req.setReadReport(PduHeaders.VALUE_NO);
            req.setContentType("application/vnd.wap.multipart.related".getBytes());
            PduBody body = new PduBody();
            byte[] words = text.getBytes("UTF-8");
            String smil = "<smil><head><layout><root-layout/><region id=\"Text\" top=\"0\" left=\"0\" height=\"100%\" width=\"100%\"/></layout></head>"
                    + "<body><par dur=\"5000ms\"><text src=\"text_0.txt\" region=\"Text\"/></par></body></smil>";
            PduPart sp = new PduPart(); sp.setContentType("application/smil".getBytes()); sp.setContentId("<smil>".getBytes()); sp.setContentLocation("smil.xml".getBytes()); sp.setData(smil.getBytes());
            PduPart tp = new PduPart(); tp.setContentType("text/plain".getBytes()); tp.setCharset(CharacterSets.UTF_8); tp.setContentId("<text_0>".getBytes());
            tp.setContentLocation("text_0.txt".getBytes()); tp.setName("text_0.txt".getBytes()); tp.setData(words);
            body.addPart(sp); body.addPart(tp);
            req.setBody(body);
            req.setMessageSize(words.length + smil.length());
            byte[] bytes = new PduComposer(c, req).make();
            if (bytes == null) return null;
            // keep it in the outbox first, so it shows at once and survives if anything goes wrong
            ContentValues v = new ContentValues();
            v.put("thread_id", thread); v.put("date", System.currentTimeMillis() / 1000); v.put("msg_box", Telephony.Mms.MESSAGE_BOX_OUTBOX);
            v.put("read", 1); v.put("seen", 1); v.put("m_type", PduHeaders.MESSAGE_TYPE_SEND_REQ); v.put("v", PduHeaders.CURRENT_MMS_VERSION);
            v.put("ct_t", "application/vnd.wap.multipart.related"); v.put("m_cls", "personal"); v.put("pri", PduHeaders.PRIORITY_NORMAL); v.put("text_only", 1);
            int sub = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
            if (sub >= 0) v.put("sub_id", sub);
            Uri mu = c.getContentResolver().insert(Telephony.Mms.Outbox.CONTENT_URI, v);
            if (mu == null) return null;
            String id = mu.getLastPathSegment();
            Uri addr = Uri.parse("content://mms/" + id + "/addr");
            addAddr(c, addr, "insert-address-token", PduHeaders.FROM);
            for (String a : to) addAddr(c, addr, a, PduHeaders.TO);
            savePart(c, id, sp); savePart(c, id, tp);
            Texts.changed(c);
            String name = "out-" + System.nanoTime() + ".pdu";
            write(new File(dir(c), name), bytes);
            Intent done = new Intent(c, TextsIn.Done.class).setAction(SENT).putExtra("file", name).putExtra("msg", mu.toString());
            Texts.sms(c, -1).sendMultimediaMessage(c, MmsFiles.uri(c, name), null, null,
                    PendingIntent.getBroadcast(c, (int) (System.nanoTime() & 0x7fffffff), done, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT));
            return mu;
        } catch (Exception e) { return null; }
    }

    /** Android's answer for a group message we sent (or an "it arrived" note to the carrier). */
    static void sent(Context c, Intent i, boolean ok) {
        new File(dir(c), String.valueOf(i.getStringExtra("file"))).delete();
        if (i.getBooleanExtra("ack", false)) return;
        String m = i.getStringExtra("msg"); if (m == null) return;
        Uri mu = Uri.parse(m);
        ContentValues v = new ContentValues(); v.put("msg_box", ok ? Telephony.Mms.MESSAGE_BOX_SENT : Telephony.Mms.MESSAGE_BOX_FAILED);
        try { c.getContentResolver().update(mu, v, null, null); } catch (Exception ignored) {}
        Texts.changed(c);
        if (!ok) Texts.notifyFailed(c, Texts.threadOf(c, mu));
    }

    /** Send a failed group message again. */
    static void retry(Context c, Texts.Msg m) {
        List<String> to = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms/" + m.id + "/addr"), new String[]{"address", "type"}, null, null, null)) {
            while (cur != null && cur.moveToNext()) if (cur.getInt(1) == PduHeaders.TO) to.add(cur.getString(0));
        } catch (Exception ignored) {}
        if (to.isEmpty()) return;
        try { c.getContentResolver().delete(m.uri, null, null); } catch (Exception ignored) {}
        sendText(c, to, m.text);
    }

    // ------------------------------------------------------------------ showing saved picture messages
    /** Fill in every picture message in a list at once: one read for all their parts, one for all their senders. */
    static void fillAll(Context c, List<Texts.Msg> msgs, boolean group) {
        java.util.Map<Long, Texts.Msg> byId = new java.util.HashMap<>();
        for (Texts.Msg m : msgs) if (m.mms) byId.put(m.id, m);
        if (byId.isEmpty()) return;
        StringBuilder in = new StringBuilder();
        for (long id : byId.keySet()) { if (in.length() > 0) in.append(','); in.append(id); }
        java.util.Map<Long, StringBuilder> words = new java.util.HashMap<>();
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms/part"), new String[]{"_id", "mid", "ct", "text"}, "mid IN (" + in + ")", null, "_id")) {
            while (cur != null && cur.moveToNext()) {
                Texts.Msg m = byId.get(cur.getLong(1)); String ct = cur.getString(2);
                if (m == null || ct == null) continue;
                if (ct.startsWith("text/plain")) { String x = cur.getString(3); if (x != null && !x.isEmpty()) { StringBuilder w = words.computeIfAbsent(m.id, k -> new StringBuilder()); if (w.length() > 0) w.append("\n"); w.append(x); } }
                else if (ct.startsWith("image/")) m.pictures.add(Uri.parse("content://mms/part/" + cur.getString(0)));
            }
        } catch (Exception e) { for (Texts.Msg m : byId.values()) fill(c, m); return; }   // a phone that refuses: one by one
        for (java.util.Map.Entry<Long, StringBuilder> e : words.entrySet()) byId.get(e.getKey()).text = e.getValue().toString();
        // senders, for messages others sent (only group conversations show them, but it's one read either way)
        if (group) for (Texts.Msg m : byId.values()) if (!m.mine) try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms/" + m.id + "/addr"), new String[]{"address"}, "type = " + PduHeaders.FROM, null, null)) {
            if (cur != null && cur.moveToFirst()) m.from = cur.getString(0);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ showing a saved picture message
    /** Fill in a saved MMS's words, pictures and sender. */
    static void fill(Context c, Texts.Msg m) {
        StringBuilder t = new StringBuilder();
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms/part"), new String[]{"_id", "ct", "text"}, "mid = ?", new String[]{String.valueOf(m.id)}, null)) {
            while (cur != null && cur.moveToNext()) {
                String ct = cur.getString(1); if (ct == null) continue;
                if (ct.startsWith("text/plain")) { String x = cur.getString(2); if (x != null && !x.isEmpty()) { if (t.length() > 0) t.append("\n"); t.append(x); } }
                else if (ct.startsWith("image/")) m.pictures.add(Uri.parse("content://mms/part/" + cur.getString(0)));
            }
        } catch (Exception ignored) {}
        m.text = t.toString();
        if (!m.mine) try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms/" + m.id + "/addr"), new String[]{"address"}, "type = " + PduHeaders.FROM, null, null)) {
            if (cur != null && cur.moveToFirst()) m.from = cur.getString(0);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ testing (adb only, via Pair): a message as a carrier would deliver it
    /** Build a picture/group message in the carrier's format and run it through the arriving path (read, save, announce). */
    static long selfTest(Context c, String from, String[] alsoTo, String text, boolean picture) throws Exception {
        SendReq req = new SendReq();
        req.setFrom(new EncodedStringValue(from));
        List<String> to = new ArrayList<>();
        String me = "15555215554";
        try { String n = c.getSystemService(android.telephony.TelephonyManager.class).getLine1Number(); if (n != null && !n.isEmpty()) me = n; } catch (Exception ignored) {}
        to.add(me); if (alsoTo != null) for (String a : alsoTo) to.add(a);
        req.setTo(EncodedStringValue.encodeStrings(to.toArray(new String[0])));
        req.setDate(System.currentTimeMillis() / 1000);
        req.setContentType("application/vnd.wap.multipart.related".getBytes());
        req.setTransactionId(("T" + System.nanoTime()).getBytes());
        PduBody body = new PduBody();
        PduPart tp = new PduPart(); tp.setContentType("text/plain".getBytes()); tp.setCharset(CharacterSets.UTF_8);
        tp.setContentId("<t>".getBytes()); tp.setContentLocation("t.txt".getBytes()); tp.setData(text.getBytes("UTF-8"));
        body.addPart(tp);
        if (picture) {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(240, 160, android.graphics.Bitmap.Config.ARGB_8888);
            new android.graphics.Canvas(bmp).drawColor(0xff7fa38a);
            ByteArrayOutputStream png = new ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
            PduPart ip = new PduPart(); ip.setContentType("image/png".getBytes()); ip.setContentId("<i>".getBytes()); ip.setContentLocation("i.png".getBytes()); ip.setData(png.toByteArray());
            body.addPart(ip);
        }
        req.setBody(body);
        byte[] bytes = new PduComposer(c, req).make();
        // turn the send-request into what a carrier hands back (m-retrieve-conf): same headers, message type 0x84
        for (int k = 0; k + 1 < bytes.length; k++) if ((bytes[k] & 0xff) == 0x8c) { bytes[k + 1] = (byte) PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF; break; }
        GenericPdu g = new PduParser(bytes, true).parse();
        if (!(g instanceof RetrieveConf)) throw new Exception("parsed as " + (g == null ? "nothing" : g.getClass().getSimpleName()));
        long thread = save(c, (RetrieveConf) g, -1);
        Msg words = new Msg(); fillFromPdu((RetrieveConf) g, words);
        Texts.notifyIncoming(c, thread, from, words.text.isEmpty() ? "Sent a picture" : words.text);
        Texts.changed(c);
        return thread;
    }

    // ------------------------------------------------------------------ files
    static byte[] read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } catch (Exception e) { return null; }
    }

    static void write(File f, byte[] data) throws Exception { try (OutputStream o = new FileOutputStream(f)) { o.write(data); } }
}
