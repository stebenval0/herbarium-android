package com.herbarium.home;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Texts, kept by Herbarium as the phone's texting app: reading conversations from Android's message store,
 * saving what arrives, sending (in parts when long, with a retry when it fails), and the notices.
 * Pictures and group texts (MMS) are in MmsWork.
 */
final class Texts {
    private Texts() {}

    static final String CHANGED = "com.herbarium.home.TEXTS_CHANGED";
    static volatile long openThread = -1;           // the conversation on screen: no notice for it, it just updates

    /** Is Herbarium the phone's texting app? (Asks Android's roles; the older check can come back empty on Android 15.) */
    static boolean isDefault(Context c) {
        try {
            android.app.role.RoleManager rm = c.getSystemService(android.app.role.RoleManager.class);
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) return rm.isRoleHeld(android.app.role.RoleManager.ROLE_SMS);
        } catch (Exception ignored) {}
        return c.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(c));
    }

    // ------------------------------------------------------------------ what's on screen
    static final class Conv {
        long thread; List<String> addresses = new ArrayList<>(); String name, snippet; long date; int unread; int pile; boolean group;
        boolean answered;      // the latest message in it is yours: nothing is waiting for a reply
    }

    static final class Msg {
        long id; boolean mms, mine, failed, sending; long date; String text = "", from; List<Uri> pictures = new ArrayList<>(); Uri uri;
    }

    /** Every conversation, newest first, each sorted into its pile. */
    static List<Conv> conversations(Context c) {
        List<Conv> out = new ArrayList<>();
        Map<String, String> addr = canonical(c);
        Set<Long> wrote = threadsWritten(c);
        Map<Long, Integer> unread = unreadCounts(c);
        Map<Long, Boolean> lastMine = lastMine(c);
        Book book = new Book(c);                                   // contacts and the allow-list, read once
        List<Conv> needWords = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms-sms/conversations?simple=true"),
                new String[]{"_id", "date", "recipient_ids", "snippet"}, null, null, "date DESC")) {
            while (cur != null && cur.moveToNext() && out.size() < 300) {
                Conv v = new Conv();
                v.thread = cur.getLong(0); v.date = cur.getLong(1);
                String ids = cur.getString(2);
                if (ids != null) for (String id : ids.trim().split(" ")) { String a = addr.get(id); if (a != null && !a.isEmpty()) v.addresses.add(a); }
                if (v.addresses.isEmpty()) continue;
                v.snippet = cur.getString(3);
                if (v.snippet == null || v.snippet.isEmpty()) { v.snippet = "A picture"; needWords.add(v); }
                v.group = v.addresses.size() > 1;
                v.name = book.names(v.addresses);
                v.unread = unread.containsKey(v.thread) ? unread.get(v.thread) : 0;
                v.answered = Boolean.TRUE.equals(lastMine.get(v.thread));
                out.add(v);
            }
        } catch (Exception ignored) {}
        mmsSnippets(c, needWords);                                 // the words of picture messages, in two reads for all of them
        for (Conv v : out) {
            boolean known = false;
            for (String a : v.addresses) if (book.known(a)) { known = true; break; }
            v.pile = Sorter.sort(known, wrote.contains(v.thread), v.addresses.get(0), v.snippet);
        }
        return out;
    }

    /** Contacts (number → name) and Herbarium's allowed list, read once for a whole list of conversations. */
    static final class Book {
        private final Map<String, String> names = new HashMap<>();
        private final Map<String, String> listed = new HashMap<>();
        Book(Context c) {
            if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try (Cursor cur = c.getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}, null, null, null)) {
                    while (cur != null && cur.moveToNext()) { String k = Calls.norm(cur.getString(0)); if (!k.isEmpty() && cur.getString(1) != null) names.put(k, cur.getString(1)); }
                } catch (Exception ignored) {}
            }
            org.json.JSONArray a = Calls.list(c, "allowed");
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                String k = Calls.norm(o.optString("number"));
                if (!k.isEmpty()) listed.put(k, o.optString("name"));
            }
        }
        boolean known(String address) { String k = Calls.norm(address); return !k.isEmpty() && (names.containsKey(k) || listed.containsKey(k)); }
        String name(String address) {
            String k = Calls.norm(address);
            if (names.containsKey(k)) return names.get(k);
            String l = listed.get(k); if (l != null && !l.isEmpty()) return l;
            if (address != null && address.matches(".*[A-Za-z].*")) return address;
            String f = android.telephony.PhoneNumberUtils.formatNumber(address, "US");
            return f == null ? address : f;
        }
        String names(List<String> addresses) {
            if (addresses.size() == 1) return name(addresses.get(0));
            List<String> firsts = new ArrayList<>();
            for (String a : addresses) firsts.add(Missed.first(name(a)));
            if (firsts.size() == 2) return firsts.get(0) + " and " + firsts.get(1);
            return String.join(", ", firsts.subList(0, firsts.size() - 1)) + " and " + firsts.get(firsts.size() - 1);
        }
    }

    /** For conversations whose latest message is a picture message: its words, fetched for all of them at once. */
    private static void mmsSnippets(Context c, List<Conv> convs) {
        if (convs.isEmpty()) return;
        Map<Long, Conv> byThread = new HashMap<>(); for (Conv v : convs) byThread.put(v.thread, v);
        Map<Long, Long> latest = new HashMap<>();                  // thread → newest MMS id
        try (Cursor cur = c.getContentResolver().query(Telephony.Mms.CONTENT_URI, new String[]{"_id", "thread_id"}, null, null, "date DESC, _id DESC")) {
            while (cur != null && cur.moveToNext()) { long th = cur.getLong(1); if (byThread.containsKey(th) && !latest.containsKey(th)) latest.put(th, cur.getLong(0)); }
        } catch (Exception ignored) {}
        if (latest.isEmpty()) return;
        Map<Long, Long> threadOf = new HashMap<>(); StringBuilder in = new StringBuilder();
        for (Map.Entry<Long, Long> e : latest.entrySet()) { threadOf.put(e.getValue(), e.getKey()); if (in.length() > 0) in.append(','); in.append(e.getValue()); }
        Map<Long, StringBuilder> words = new HashMap<>(); Set<Long> pictures = new HashSet<>();
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms/part"), new String[]{"mid", "ct", "text"}, "mid IN (" + in + ")", null, null)) {
            while (cur != null && cur.moveToNext()) {
                long mid = cur.getLong(0); String ct = cur.getString(1); if (ct == null) continue;
                if (ct.startsWith("text/plain") && cur.getString(2) != null) words.computeIfAbsent(mid, k -> new StringBuilder()).append(words.containsKey(mid) && words.get(mid).length() > 0 ? "\n" : "").append(cur.getString(2));
                else if (ct.startsWith("image/")) pictures.add(mid);
            }
        } catch (Exception ignored) {}
        for (Map.Entry<Long, Long> e : threadOf.entrySet()) {
            Conv v = byThread.get(e.getValue()); if (v == null) continue;
            StringBuilder w = words.get(e.getKey());
            if (w != null && w.length() > 0) v.snippet = pictures.contains(e.getKey()) ? w + " (with a picture)" : w.toString();
        }
    }

    /** A picture message's preview: its words if it has any, else "A picture". */
    private static String mmsSnippet(Context c, long thread) {
        try (Cursor m = c.getContentResolver().query(Telephony.Mms.CONTENT_URI, new String[]{"_id"}, "thread_id = ?", new String[]{String.valueOf(thread)}, "date DESC, _id DESC LIMIT 1")) {
            if (m != null && m.moveToFirst()) {
                Msg x = new Msg(); x.id = m.getLong(0); MmsWork.fill(c, x);
                if (!x.text.isEmpty()) return x.pictures.isEmpty() ? x.text : x.text + " (with a picture)";
            }
        } catch (Exception ignored) {}
        return "A picture";
    }

    /** "Sam Carter", or for a group "Sam, Ruth and Ellen". */
    static String names(Context c, List<String> addresses) {
        if (addresses.size() == 1) return Missed.name(c, addresses.get(0), null);
        List<String> firsts = new ArrayList<>();
        for (String a : addresses) firsts.add(Missed.first(Missed.name(c, a, null)));
        if (firsts.size() == 2) return firsts.get(0) + " and " + firsts.get(1);
        return String.join(", ", firsts.subList(0, firsts.size() - 1)) + " and " + firsts.get(firsts.size() - 1);
    }

    private static Map<String, String> canonical(Context c) {
        Map<String, String> m = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms-sms/canonical-addresses"), new String[]{"_id", "address"}, null, null, null)) {
            while (cur != null && cur.moveToNext()) m.put(cur.getString(0), cur.getString(1));
        } catch (Exception ignored) {}
        return m;
    }

    private static Set<Long> threadsWritten(Context c) {
        Set<Long> s = new HashSet<>();
        try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI, new String[]{"DISTINCT thread_id"}, "type = 2", null, null)) {
            while (cur != null && cur.moveToNext()) s.add(cur.getLong(0));
        } catch (Exception e) {   // some phones refuse DISTINCT in a projection
            try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI, new String[]{"thread_id"}, "type = 2", null, null)) {
                while (cur != null && cur.moveToNext()) s.add(cur.getLong(0));
            } catch (Exception ignored) {}
        }
        try (Cursor cur = c.getContentResolver().query(Telephony.Mms.CONTENT_URI, new String[]{"thread_id"}, "msg_box = 2", null, null)) {
            while (cur != null && cur.moveToNext()) s.add(cur.getLong(0));
        } catch (Exception ignored) {}
        return s;
    }

    /** For each conversation: is its latest message one you sent? (One pass over texts, one over picture messages.) */
    private static Map<Long, Boolean> lastMine(Context c) {
        Map<Long, Boolean> mine = new HashMap<>(); Map<Long, Long> when = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI, new String[]{"thread_id", "type", "date"}, null, null, "date DESC")) {
            while (cur != null && cur.moveToNext()) {
                long th = cur.getLong(0); if (when.containsKey(th)) continue;
                when.put(th, cur.getLong(2)); mine.put(th, cur.getInt(1) != Telephony.Sms.MESSAGE_TYPE_INBOX);
            }
        } catch (Exception ignored) {}
        try (Cursor cur = c.getContentResolver().query(Telephony.Mms.CONTENT_URI, new String[]{"thread_id", "msg_box", "date"}, null, null, "date DESC")) {
            Set<Long> seen = new HashSet<>();
            while (cur != null && cur.moveToNext()) {
                long th = cur.getLong(0); if (!seen.add(th)) continue;
                long at = cur.getLong(2) * 1000L;
                Long other = when.get(th);
                if (other == null || at > other) { when.put(th, at); mine.put(th, cur.getInt(1) != Telephony.Mms.MESSAGE_BOX_INBOX); }
            }
        } catch (Exception ignored) {}
        return mine;
    }

    private static Map<Long, Integer> unreadCounts(Context c) {
        Map<Long, Integer> m = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI, new String[]{"thread_id"}, "read = 0 AND type = 1", null, null)) {
            while (cur != null && cur.moveToNext()) m.merge(cur.getLong(0), 1, Integer::sum);
        } catch (Exception ignored) {}
        try (Cursor cur = c.getContentResolver().query(Telephony.Mms.CONTENT_URI, new String[]{"thread_id"}, "read = 0 AND msg_box = 1", null, null)) {
            while (cur != null && cur.moveToNext()) m.merge(cur.getLong(0), 1, Integer::sum);
        } catch (Exception ignored) {}
        return m;
    }

    /** The addresses in one conversation. */
    /** Who's in one conversation (just that one is looked up). */
    static List<String> addresses(Context c, long thread) {
        List<String> out = new ArrayList<>();
        String ids = null;
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms-sms/conversations?simple=true"),
                new String[]{"recipient_ids"}, "_id = ?", new String[]{String.valueOf(thread)}, null)) {
            if (cur != null && cur.moveToFirst()) ids = cur.getString(0);
        } catch (Exception ignored) {}
        if (ids == null || ids.trim().isEmpty()) return out;
        StringBuilder in = new StringBuilder();
        for (String id : ids.trim().split(" ")) if (id.matches("\\d+")) { if (in.length() > 0) in.append(','); in.append(id); }
        if (in.length() == 0) return out;
        Map<String, String> byId = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://mms-sms/canonical-addresses"), new String[]{"_id", "address"}, "_id IN (" + in + ")", null, null)) {
            while (cur != null && cur.moveToNext()) byId.put(cur.getString(0), cur.getString(1));
        } catch (Exception e) {                       // some phones refuse a selection here: read them all (small table)
            byId.putAll(canonical(c));
        }
        for (String id : ids.trim().split(" ")) { String a = byId.get(id); if (a != null && !a.isEmpty()) out.add(a); }
        return out;
    }

    /** The last `limit` messages of a conversation, oldest first: texts and picture messages together. */
    static List<Msg> messages(Context c, long thread, int limit) {
        List<Msg> all = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI, new String[]{"_id", "address", "body", "date", "type"},
                "thread_id = ?", new String[]{String.valueOf(thread)}, "date DESC LIMIT " + limit)) {
            while (cur != null && cur.moveToNext()) {
                Msg m = new Msg(); m.id = cur.getLong(0); m.from = cur.getString(1); m.text = cur.getString(2) == null ? "" : cur.getString(2);
                m.date = cur.getLong(3); int type = cur.getInt(4);
                m.mine = type != Telephony.Sms.MESSAGE_TYPE_INBOX;
                m.failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED;
                m.sending = type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED;
                m.uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, String.valueOf(m.id));
                all.add(m);
            }
        } catch (Exception ignored) {}
        try (Cursor cur = c.getContentResolver().query(Telephony.Mms.CONTENT_URI, new String[]{"_id", "date", "msg_box"},
                "thread_id = ?", new String[]{String.valueOf(thread)}, "date DESC, _id DESC LIMIT " + limit)) {
            while (cur != null && cur.moveToNext()) {
                Msg m = new Msg(); m.mms = true; m.id = cur.getLong(0); m.date = cur.getLong(1) * 1000L; int box = cur.getInt(2);
                m.mine = box != Telephony.Mms.MESSAGE_BOX_INBOX;
                m.failed = box == Telephony.Mms.MESSAGE_BOX_FAILED;
                m.sending = box == Telephony.Mms.MESSAGE_BOX_OUTBOX;
                m.uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, String.valueOf(m.id));
                all.add(m);
            }
        } catch (Exception ignored) {}
        // oldest first; picture messages only keep whole seconds, so ties go by the order they arrived
        boolean hasMms = false; for (Msg m : all) if (m.mms) { hasMms = true; break; }
        if (hasMms) MmsWork.fillAll(c, all, addresses(c, thread).size() > 1);                     // pictures, words and senders for every picture message, in two reads
        Collections.sort(all, (a, b) -> a.date != b.date ? Long.compare(a.date, b.date) : a.mms == b.mms ? Long.compare(a.id, b.id) : 0);
        return all.size() > limit ? new ArrayList<>(all.subList(all.size() - limit, all.size())) : all;
    }

    // the last messages read for a few conversations: a conversation opens showing these at once, then refreshes
    private static final Map<Long, List<Msg>> recent = new java.util.LinkedHashMap<Long, List<Msg>>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, List<Msg>> e) { return size() > 8; }
    };
    static synchronized List<Msg> cached(long thread) { return recent.get(thread); }
    static synchronized void remember(long thread, List<Msg> list) { recent.put(thread, list); }

    /** Read a few conversations ahead of time (Messages page shown), so opening them is immediate. */
    static void prefetch(Context c, List<Long> threads) {
        for (long th : threads) if (cached(th) == null) remember(th, messages(c, th, 120));
    }

    /** Opening a conversation reads it. */
    static void markRead(Context c, long thread) {
        ContentValues v = new ContentValues(); v.put("read", 1); v.put("seen", 1);
        int n = 0;
        try { n += c.getContentResolver().update(Telephony.Sms.CONTENT_URI, v, "thread_id = ? AND read = 0", new String[]{String.valueOf(thread)}); } catch (Exception ignored) {}
        try { n += c.getContentResolver().update(Telephony.Mms.CONTENT_URI, v, "thread_id = ? AND read = 0", new String[]{String.valueOf(thread)}); } catch (Exception ignored) {}
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(noticeId(thread));
        // only say so when something changed: a conversation reloads on this, and reloading marks it read again
        if (n > 0) changed(c);
    }

    static void changed(Context c) { c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName())); }

    // ------------------------------------------------------------------ receiving
    /** Save a text that just arrived (all its parts, joined), and say so. Runs off the main thread. */
    static void received(Context c, SmsMessage[] parts, int subId) {
        if (parts == null || parts.length == 0 || parts[0] == null) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage p : parts) if (p != null && p.getDisplayMessageBody() != null) body.append(p.getDisplayMessageBody());
        SmsMessage first = parts[0];
        String address = first.getDisplayOriginatingAddress();
        if (address == null) address = "";
        ContentValues v = new ContentValues();
        v.put("address", address); v.put("body", body.toString());
        v.put("date", System.currentTimeMillis()); v.put("date_sent", first.getTimestampMillis());
        v.put("read", 0); v.put("seen", 0); v.put("type", Telephony.Sms.MESSAGE_TYPE_INBOX);
        v.put("protocol", first.getProtocolIdentifier()); v.put("reply_path_present", first.isReplyPathPresent() ? 1 : 0);
        if (first.getServiceCenterAddress() != null) v.put("service_center", first.getServiceCenterAddress());
        int sub = subId >= 0 ? subId : android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
        if (sub >= 0) v.put("sub_id", sub);
        Uri u = null;
        try { u = c.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, v); }
        catch (Exception e) {            // a phone without the sub_id column: save without it
            v.remove("sub_id");
            try { u = c.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, v); } catch (Exception ignored) {}
        }
        long thread = threadOf(c, u);
        notifyIncoming(c, thread, address, body.toString());
        changed(c);
    }

    static long threadOf(Context c, Uri u) {
        if (u == null) return -1;
        try (Cursor cur = c.getContentResolver().query(u, new String[]{"thread_id"}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getLong(0);
        } catch (Exception ignored) {}
        return -1;
    }

    // ------------------------------------------------------------------ sending
    static SmsManager sms(Context c, int subId) {
        if (android.os.Build.VERSION.SDK_INT < 31)      // (Android 11 has no SmsManager system service)
            return subId >= 0 ? SmsManager.getSmsManagerForSubscriptionId(subId) : SmsManager.getDefault();
        SmsManager m = c.getSystemService(SmsManager.class);
        return subId >= 0 && m != null ? m.createForSubscriptionId(subId) : m;
    }

    private static final Map<String, int[]> pending = new HashMap<>();   // outbox uri → {parts left, failed}

    /** Send a text (in parts when long). It sits in the outbox until Android says it went; if not, it's marked to try again. */
    static Uri send(Context c, String address, String body) {
        if (address == null || address.trim().isEmpty() || body == null || body.trim().isEmpty()) return null;
        long thread = Telephony.Threads.getOrCreateThreadId(c, address);
        ContentValues v = new ContentValues();
        v.put("address", address); v.put("body", body); v.put("date", System.currentTimeMillis());
        v.put("read", 1); v.put("seen", 1); v.put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX); v.put("thread_id", thread);
        int sub = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
        if (sub >= 0) v.put("sub_id", sub);     // which SIM it went from: other texting apps filter by this, so they'd hide it otherwise
        Uri u;
        try { u = c.getContentResolver().insert(Telephony.Sms.CONTENT_URI, v); }
        catch (Exception e) { v.remove("sub_id"); u = c.getContentResolver().insert(Telephony.Sms.CONTENT_URI, v); }
        if (u == null) return null;
        changed(c);
        SmsManager m = sms(c, -1);
        ArrayList<String> parts = m.divideMessage(body);
        ArrayList<PendingIntent> sent = new ArrayList<>();
        synchronized (pending) { pending.put(u.toString(), new int[]{parts.size(), 0}); }
        for (int i = 0; i < parts.size(); i++) {
            Intent r = new Intent(c, SentResult.class).setData(u).putExtra("part", i);
            sent.add(PendingIntent.getBroadcast(c, (int) (System.nanoTime() & 0x7fffffff) + i, r, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ONE_SHOT));
        }
        try {
            if (parts.size() == 1) m.sendTextMessage(address, null, parts.get(0), sent.get(0), null);
            else m.sendMultipartTextMessage(address, null, parts, sent, null);
        } catch (Exception e) { failed(c, u); }
        return u;
    }

    /** Android's answer for one part of a text we sent. */
    public static class SentResult extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            Uri u = i.getData(); if (u == null) return;
            boolean ok = getResultCode() == android.app.Activity.RESULT_OK;
            int[] st;
            synchronized (pending) {
                st = pending.get(u.toString());
                if (st == null) st = new int[]{1, 0};   // Herbarium restarted meanwhile: judge by this part
                st[0]--; if (!ok) st[1]++;
                if (st[0] <= 0) pending.remove(u.toString()); else return;
            }
            if (st[1] > 0) failed(c, u);
            else {
                ContentValues v = new ContentValues(); v.put("type", Telephony.Sms.MESSAGE_TYPE_SENT); v.put("date_sent", System.currentTimeMillis());
                try { c.getContentResolver().update(u, v, null, null); } catch (Exception ignored) {}
                changed(c);
            }
        }
    }

    private static void failed(Context c, Uri u) {
        ContentValues v = new ContentValues(); v.put("type", Telephony.Sms.MESSAGE_TYPE_FAILED);
        try { c.getContentResolver().update(u, v, null, null); } catch (Exception ignored) {}
        changed(c);
        notifyFailed(c, threadOf(c, u));
    }

    /** Send a failed text again: same words, same person. */
    static void retry(Context c, Msg m) {
        if (m.mms) { MmsWork.retry(c, m); return; }
        String address = m.from;
        try { c.getContentResolver().delete(m.uri, null, null); } catch (Exception ignored) {}
        send(c, address, m.text);
    }

    // ------------------------------------------------------------------ notices
    private static final String PEOPLE = "texts-people", QUIET = "texts-quiet", CODES = "texts-codes", FAILED = "texts-failed";

    static int noticeId(long thread) { return 8_000_000 + (int) (thread % 1_000_000); }

    private static void channels(NotificationManager nm) {
        if (nm.getNotificationChannel(PEOPLE) != null) return;
        nm.createNotificationChannel(new NotificationChannel(PEOPLE, "Texts from people", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CODES, "Sign-in codes", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(QUIET, "Updates and offers", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(FAILED, "Texts that didn't send", NotificationManager.IMPORTANCE_HIGH));
    }

    /**
     * A text arrived. People: a notice that sounds. Codes: a notice showing the code. Updates and unknown numbers: quiet.
     * Offers: nothing. Caretaken mode: only people, links taken out; everything else is kept silently for helpers.
     */
    /** Has the person ever written in this conversation? (Then whoever replies counts as a person, as in the list.) */
    static boolean wrote(Context c, long thread) {
        try (android.database.Cursor cur = c.getContentResolver().query(android.provider.Telephony.Sms.CONTENT_URI, new String[]{"_id"},
                "thread_id = ? AND type = 2", new String[]{String.valueOf(thread)}, "_id LIMIT 1")) {
            return cur != null && cur.moveToFirst();
        } catch (Exception e) { return false; }
    }

    static void notifyIncoming(Context c, long thread, String address, String text) {
        if (thread < 0 || thread == openThread) return;
        boolean known = Calls.inContacts(c, address) || Calls.onList(c, address);
        int pile = Sorter.sort(known, wrote(c, thread), address, text);
        boolean care = Care.on(c);
        if (care && pile != Sorter.PEOPLE) return;
        if (pile == Sorter.OFFERS) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        channels(nm);
        String name = Missed.name(c, address, null);
        String shown = care ? Sorter.withoutLinks(text) : text;
        String channel = pile == Sorter.PEOPLE ? PEOPLE : pile == Sorter.CODES ? CODES : QUIET;
        String title = pile == Sorter.CODES && !Sorter.code(text).isEmpty() ? "Your code: " + Sorter.code(text) : name;
        PendingIntent open = PendingIntent.getActivity(c, noticeId(thread), new Intent(c, ThreadActivity.class).putExtra("thread", thread)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, channel)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title).setContentText(shown)
                .setStyle(new Notification.BigTextStyle().bigText(shown))
                .setCategory(Notification.CATEGORY_MESSAGE).setAutoCancel(true)
                .setContentIntent(open).setWhen(System.currentTimeMillis()).setShowWhen(true);
        if (!care && pile == Sorter.PEOPLE) {   // reply from the notice (Caretaken mode opens the conversation instead)
            RemoteInput ri = new RemoteInput.Builder(Reply.KEY).setLabel("Reply to " + Missed.first(name)).build();
            PendingIntent reply = PendingIntent.getBroadcast(c, noticeId(thread), new Intent(c, Reply.class).putExtra("thread", thread).putExtra("address", address),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Reply", reply).addRemoteInput(ri).build());
        }
        nm.notify(noticeId(thread), b.build());
    }

    static void notifyFailed(Context c, long thread) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || thread < 0) return;
        channels(nm);
        List<String> a = addresses(c, thread);
        String who = a.isEmpty() ? "someone" : a.size() > 1 ? names(c, a) : Missed.first(names(c, a));   // a group: "Sam and Ruth"
        PendingIntent open = PendingIntent.getActivity(c, noticeId(thread) + 1, new Intent(c, ThreadActivity.class).putExtra("thread", thread)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(noticeId(thread) + 500_000, new Notification.Builder(c, FAILED)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Your text to " + who + " didn't send")
                .setContentText("Tap to open it and try again.")
                .setContentIntent(open).setAutoCancel(true).build());
    }

    /** "Reply" typed straight into the notice. */
    public static class Reply extends android.content.BroadcastReceiver {
        static final String KEY = "reply";
        @Override public void onReceive(Context c, Intent i) {
            android.os.Bundle r = RemoteInput.getResultsFromIntent(i);
            CharSequence words = r == null ? null : r.getCharSequence(KEY);
            String address = i.getStringExtra("address");
            long thread = i.getLongExtra("thread", -1);
            if (words == null || address == null) return;
            PendingResult pr = goAsync();
            new Thread(() -> {
                try { send(c, address, words.toString()); markRead(c, thread); } finally { pr.finish(); }
            }).start();
        }
    }
}
