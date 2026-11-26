package com.herbarium.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Pairing with the computer, sent over adb only (this receiver requires the DUMP permission, which the adb
 * shell has and ordinary apps don't):
 *   am broadcast -n com.herbarium.clausus/.Pair --es host 192.168.1.170 --ei port 7461 --es token XXXX
 * The computer can't be changed while a block is running.
 */
public class Pair extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i.getBooleanExtra("shade", false)) { Guard.openShade(); setResultData("shade"); return; }   // for testing over adb
        if (i.hasExtra("ear")) { Theme.prefs(c).edit().putString("ear", i.getStringExtra("ear")).apply(); setResultData("ear"); return; }   // "own" or "auto"
        if (i.hasExtra("smsIn")) {          // testing, nothing sent: "number|words" filed as a text that just arrived (adb only)
            String[] f = i.getStringExtra("smsIn").split("\\|", 2);
            android.content.ContentValues v = new android.content.ContentValues();
            v.put("address", f[0]); v.put("body", f.length > 1 ? f[1] : ""); v.put("date", System.currentTimeMillis()); v.put("read", 0); v.put("seen", 0); v.put("type", 1);
            android.net.Uri u = null;
            try { u = c.getContentResolver().insert(android.provider.Telephony.Sms.Inbox.CONTENT_URI, v); } catch (Exception e) { setResultData("smsIn failed: " + e); return; }
            Texts.changed(c);
            setResultData("smsIn " + u); return;
        }
        if (i.hasExtra("smsTest")) {        // testing: "number|words", sent `smsTimes` times through the same path as Send
            String[] f = i.getStringExtra("smsTest").split("\\|", 2);
            int times = i.getIntExtra("smsTimes", 1);
            PendingResult pr = goAsync();
            new Thread(() -> {
                int ok = 0;
                for (int k = 0; k < times; k++) if (Texts.send(c.getApplicationContext(), f[0], f[1] + (times > 1 ? " #" + (k + 1) : "")) != null) ok++;
                pr.setResultData("sms queued " + ok + "/" + times); pr.finish();
            }).start();
            return;
        }
        if (i.hasExtra("threadTiming")) {   // testing: what opening one conversation costs
            long th = i.getLongExtra("threadTiming", 1);
            PendingResult pr = goAsync();
            new Thread(() -> {
                long t0 = android.os.SystemClock.elapsedRealtime();
                java.util.List<String> a = Texts.addresses(c, th);
                long t1 = android.os.SystemClock.elapsedRealtime();
                java.util.List<Texts.Msg> m = Texts.messages(c, th, 120);
                long t2 = android.os.SystemClock.elapsedRealtime();
                Texts.markRead(c, th);
                long t3 = android.os.SystemClock.elapsedRealtime();
                pr.setResultData("addresses " + (t1 - t0) + " ms; " + m.size() + " messages " + (t2 - t1) + " ms; markRead " + (t3 - t2) + " ms"); pr.finish();
            }).start();
            return;
        }
        if (i.getBooleanExtra("textsTiming", false)) {   // testing: how long Messages takes to read and sort everything
            PendingResult pr = goAsync();
            new Thread(() -> {
                long t0 = android.os.SystemClock.elapsedRealtime();
                java.util.List<Texts.Conv> all = Texts.conversations(c.getApplicationContext());
                long t1 = android.os.SystemClock.elapsedRealtime();
                int[] piles = new int[Sorter.NAMES.length]; for (Texts.Conv v : all) piles[v.pile]++;
                long t2 = android.os.SystemClock.elapsedRealtime();
                int n = all.isEmpty() ? 0 : Texts.messages(c.getApplicationContext(), all.get(0).thread, 120).size();
                long t3 = android.os.SystemClock.elapsedRealtime();
                pr.setResultData(all.size() + " conversations in " + (t1 - t0) + " ms; piles " + java.util.Arrays.toString(piles) + "; newest conversation " + n + " messages in " + (t3 - t2) + " ms");
                pr.finish();
            }).start();
            return;
        }
        if (i.hasExtra("mmsSend")) {        // testing over the real network: "number,number|words" sent as one picture/group message
            String[] f = i.getStringExtra("mmsSend").split("\\|", 2);
            PendingResult pr = goAsync();
            new Thread(() -> {
                android.net.Uri u = MmsWork.sendText(c.getApplicationContext(), java.util.Arrays.asList(f[0].split(",")), f[1]);
                pr.setResultData("mms handed to Android: " + u); pr.finish();
            }).start();
            return;
        }
        if (i.hasExtra("mmsTest")) {        // testing: "from|other,people|words|picture", repeated `mmsTimes` times
            String[] f = i.getStringExtra("mmsTest").split("\\|", -1);
            int times = i.getIntExtra("mmsTimes", 1);
            PendingResult pr = goAsync();
            new Thread(() -> {
                int ok = 0; StringBuilder err = new StringBuilder();
                for (int k = 0; k < times; k++) {
                    try { MmsWork.selfTest(c.getApplicationContext(), f[0], f[1].isEmpty() ? null : f[1].split(","), f[2] + (times > 1 ? " #" + (k + 1) : ""), f.length > 3 && f[3].equals("picture")); ok++; }
                    catch (Exception e) { err.append(e).append("; "); }
                }
                pr.setResultData("mms ok " + ok + "/" + times + (err.length() > 0 ? " " + err : ""));
                pr.finish();
            }).start();
            return;
        }
        if (i.getBooleanExtra("helperPinReset", false)) { Care.clearPin(c); setResultData("helperPinReset"); return; }   // forgotten helper PIN
        if (i.getBooleanExtra("unmute", false)) {      // testing/repair: unmute and restore music and the ringer
            android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
            for (int st : new int[]{android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.STREAM_RING}) {
                try { am.adjustStreamVolume(st, android.media.AudioManager.ADJUST_UNMUTE, 0); if (am.getStreamVolume(st) < 2) am.setStreamVolume(st, Math.max(2, am.getStreamMaxVolume(st) * 4 / 10), 0); } catch (Exception ignored) {}
            }
            setResultData("unmuted"); return;
        }
        if (i.hasExtra("caretaken")) { Care.set(c, i.getBooleanExtra("caretaken", false)); setResultData("caretaken"); return; }
        if (i.hasExtra("scale")) { Theme.prefs(c).edit().putFloat("scale", Math.max(1f, Math.min(3f, i.getFloatExtra("scale", 1f)))).apply(); setResultData("scale"); return; }
        String token = i.getStringExtra("token");
        if (token == null || token.isEmpty()) { setResultData("missing token"); return; }
        if (State.locked(c) && !State.token(c).isEmpty() && !token.equals(State.token(c))) {
            setResultData("locked: can't re-pair during a block"); return;
        }
        State.prefs(c).edit().putString("token", token).putString("host", i.getStringExtra("host") == null ? "" : i.getStringExtra("host"))
                .putInt("port", i.getIntExtra("port", 7461)).apply();
        ClaususService.start(c);
        setResultData("paired");
    }
}
