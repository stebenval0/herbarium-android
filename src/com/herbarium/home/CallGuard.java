package com.herbarium.home;

import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;

/** Android asks this about each incoming call from a number that isn't in the contacts; see Calls for the rules. */
public class CallGuard extends CallScreeningService {
    @Override public void onScreenCall(Call.Details d) {
        Care.keepRinging(this, true);      // Caretaken: loud before Android starts ringing
        if (d.getCallDirection() != Call.Details.DIRECTION_INCOMING) { respondToCall(d, new CallResponse.Builder().build()); return; }
        Uri h = d.getHandle();
        String number = h == null ? "" : h.getSchemeSpecificPart();
        if (Calls.mayRing(this, number)) {
            respondToCall(d, new CallResponse.Builder().build());
        } else {
            Calls.declined(this, number);
            respondToCall(d, new CallResponse.Builder()
                    .setDisallowCall(true).setRejectCall(true)
                    // in Caretaken mode a turned-away stranger leaves no trace in the phone app (Herbarium still keeps its list)
                    .setSkipNotification(true).setSkipCallLog(Care.on(this)).build());
        }
    }
}
