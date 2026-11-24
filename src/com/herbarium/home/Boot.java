package com.herbarium.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        ClaususService.start(c);
        // picture messages that hadn't been fetched before the restart: try again now (needs the phone unlocked once)
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && Texts.isDefault(c) && MmsWork.waitingCount(c) > 0) {
            PendingResult r = goAsync();
            new Thread(() -> { try { MmsWork.retryWaiting(c.getApplicationContext()); } finally { r.finish(); } }).start();
        }
        // straight after a restart the phone is locked: show Herbarium's lock screen, not Android's
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(i.getAction()) || Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) LockActivity.show(c);
    }
}
