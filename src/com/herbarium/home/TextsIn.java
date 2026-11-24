package com.herbarium.home;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.io.File;

/** Where Android hands texts to Herbarium as the phone's texting app. Each does its work off the main thread. */
final class TextsIn {
    private TextsIn() {}

    static int subId(Intent i) {
        int s = i.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1);
        return s >= 0 ? s : i.getIntExtra("subscription", -1);
    }

    /** A text arrived (SMS_DELIVER: only the texting app gets this). */
    public static class Sms extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(i.getAction())) return;
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(i);
            int sub = subId(i);
            PendingResult r = goAsync();
            new Thread(() -> { try { Texts.received(c.getApplicationContext(), parts, sub); } finally { r.finish(); } }, "herbarium-sms-in").start();
        }
    }

    /** The carrier says a picture or group message is waiting (WAP_PUSH_DELIVER). */
    public static class Mms extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (!Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(i.getAction())) return;
            byte[] pdu = i.getByteArrayExtra("data");
            int sub = subId(i);
            PendingResult r = goAsync();
            new Thread(() -> { try { MmsWork.push(c.getApplicationContext(), pdu, sub); } finally { r.finish(); } }, "herbarium-mms-in").start();
        }
    }

    /** Android finished fetching or sending a picture message. */
    public static class Done extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            boolean ok = getResultCode() == android.app.Activity.RESULT_OK;
            String action = i.getAction();
            PendingResult r = goAsync();
            new Thread(() -> {
                try {
                    if (MmsWork.DOWNLOADED.equals(action)) MmsWork.downloaded(c.getApplicationContext(), i, ok);
                    else if (MmsWork.SENT.equals(action)) MmsWork.sent(c.getApplicationContext(), i, ok);
                } finally { r.finish(); }
            }, "herbarium-mms-done").start();
        }
    }

    /** "Reply with a message" when declining a call (RESPOND_VIA_MESSAGE): send it, quietly. */
    public static class QuickReply extends Service {
        @Override public IBinder onBind(Intent i) { return null; }
        @Override public int onStartCommand(Intent i, int flags, int id) {
            if (i != null && i.getData() != null) {
                String to = i.getData().getSchemeSpecificPart();
                String text = i.getStringExtra(Intent.EXTRA_TEXT);
                if (to != null && text != null && !text.trim().isEmpty()) {
                    new Thread(() -> { Texts.send(getApplicationContext(), to.split("[,;]")[0], text); stopSelf(id); }).start();
                    return START_NOT_STICKY;
                }
            }
            stopSelf(id);
            return START_NOT_STICKY;
        }
    }

    /** The picture-message files Android's message service reads and writes for Herbarium (private; access granted per file). */
    public static class Files extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws java.io.FileNotFoundException {
            String name = u.getLastPathSegment();
            if (name == null || name.contains("/") || name.contains("..")) throw new java.io.FileNotFoundException();
            File f = new File(MmsWork.dir(getContext()), name);
            int m = mode.contains("w") ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                    : ParcelFileDescriptor.MODE_READ_ONLY;
            return ParcelFileDescriptor.open(f, m);
        }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return "application/octet-stream"; }
        @Override public Uri insert(Uri u, ContentValues v) { return null; }
        @Override public int delete(Uri u, String s, String[] a) { return 0; }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }
}

/** The address of a picture-message file, for Android's message service. */
final class MmsFiles {
    private MmsFiles() {}
    static Uri uri(Context c, String name) { return Uri.parse("content://" + c.getPackageName() + ".mmsfiles/" + name); }
}
