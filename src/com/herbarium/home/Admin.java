package com.herbarium.home;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Device admin: while it's active, Clausus can't be uninstalled. Switching it off is guarded during a block. */
public class Admin extends DeviceAdminReceiver {
    @Override public CharSequence onDisableRequested(Context c, Intent i) {
        return State.locked(c) ? "Blocking is on. Turning this off won't end it." : "Herbarium won't be protected from being removed.";
    }
}
