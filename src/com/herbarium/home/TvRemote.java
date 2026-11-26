package com.herbarium.home;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The phone as a Bluetooth remote for the television (a Fire TV, or anything that takes a Bluetooth keyboard):
 * no Wi-Fi or internet involved. It is a keyboard plus media keys, registered only while the remote screen is open,
 * because a television that sees a keyboard stops showing its own on-screen keyboard.
 */
final class TvRemote {
    interface Listener { void changed(); }

    // keyboard (report 1): arrows, Enter; media keys (report 2): Back, Home, Menu, play/pause, skip, volume
    private static final byte[] DESCRIPTOR = {
            0x05, 0x01, 0x09, 0x06, (byte) 0xA1, 0x01, (byte) 0x85, 0x01,
            0x05, 0x07, 0x19, (byte) 0xE0, 0x29, (byte) 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
            (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
            (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, (byte) 0x81, 0x00,
            (byte) 0xC0,
            0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01, (byte) 0x85, 0x02,
            0x15, 0x00, 0x26, (byte) 0xFF, 0x03, 0x19, 0x00, 0x2A, (byte) 0xFF, 0x03, 0x75, 0x10, (byte) 0x95, 0x01, (byte) 0x81, 0x00,
            (byte) 0xC0
    };
    static final int UP = 0x52, DOWN = 0x51, LEFT = 0x50, RIGHT = 0x4F, OK = 0x28;                              // keyboard
    static final int BACK = 0x224, HOME = 0x223, MENU = 0x40, PLAY = 0xCD, FORWARD = 0xB3, REWIND = 0xB4,
            LOUDER = 0xE9, SOFTER = 0xEA, MUTE = 0xE2;                                                               // media

    private static TvRemote one;
    static synchronized TvRemote get(Context c) { if (one == null) one = new TvRemote(c.getApplicationContext()); return one; }

    private final Context c;
    private final Executor work = Executors.newSingleThreadExecutor();
    private BluetoothHidDevice hid;
    private boolean registered;
    private BluetoothDevice tv;
    private int state = BluetoothProfile.STATE_DISCONNECTED;
    volatile Listener listener;

    private TvRemote(Context c) { this.c = c; }

    private BluetoothAdapter adapter() { BluetoothManager m = c.getSystemService(BluetoothManager.class); return m == null ? null : m.getAdapter(); }

    boolean bluetoothOn() { BluetoothAdapter a = adapter(); return a != null && a.isEnabled(); }
    boolean connected() { return state == BluetoothProfile.STATE_CONNECTED && tv != null; }
    boolean connecting() { return state == BluetoothProfile.STATE_CONNECTING || (registered && saved() != null && !connected()); }
    String saved() { return Theme.prefs(c).getString("tvAddress", null); }
    String tvName() {
        try { if (tv != null && tv.getName() != null) return tv.getName(); } catch (SecurityException ignored) {}
        return Theme.prefs(c).getString("tvName", "the television");
    }

    /** Start being a remote (the screen opened). */
    void start() {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) { tell(); return; }
        if (hid != null) { if (!registered) register(); else reconnect(); return; }
        try {
            a.getProfileProxy(c, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile p) { hid = (BluetoothHidDevice) p; register(); }
                @Override public void onServiceDisconnected(int profile) { hid = null; registered = false; tell(); }
            }, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException e) { tell(); }
    }

    /** Stop being a remote (the screen closed): the television gets its own keyboard back. */
    void stop() {
        try { if (hid != null && tv != null) hid.disconnect(tv); } catch (Exception ignored) {}
        try { if (hid != null && registered) hid.unregisterApp(); } catch (Exception ignored) {}
        registered = false; state = BluetoothProfile.STATE_DISCONNECTED;
        try { BluetoothAdapter a = adapter(); if (a != null && hid != null) a.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); } catch (Exception ignored) {}
        hid = null;
    }

    private void register() {
        if (hid == null || registered) return;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings("Herbarium remote", "Remote control", "Herbarium",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
        try {
            hid.registerApp(sdp, null, null, work, new BluetoothHidDevice.Callback() {
                @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean ok) {
                    registered = ok;
                    if (ok) { if (plugged != null) tv = plugged; reconnect(); }
                    tell();
                }
                @Override public void onConnectionStateChanged(BluetoothDevice d, int s) {
                    state = s;
                    if (s == BluetoothProfile.STATE_CONNECTED) {
                        tv = d;
                        String name = null; try { name = d.getName(); } catch (SecurityException ignored) {}
                        Theme.prefs(c).edit().putString("tvAddress", d.getAddress()).putString("tvName", name == null ? "the television" : name).apply();
                    }
                    tell();
                }
            });
        } catch (SecurityException e) { tell(); }
    }

    private void reconnect() {
        String addr = saved();
        if (hid == null || addr == null || connected()) return;
        try { BluetoothAdapter a = adapter(); if (a != null) hid.connect(a.getRemoteDevice(addr)); } catch (Exception ignored) {}
    }

    /** Forget the television (to pair with a different one). */
    void forget() { Theme.prefs(c).edit().remove("tvAddress").remove("tvName").apply(); try { if (hid != null && tv != null) hid.disconnect(tv); } catch (Exception ignored) {} tv = null; tell(); }

    /** Press and let go of a key. */
    void press(int key, boolean media) {
        BluetoothDevice d = tv; BluetoothHidDevice h = hid;
        if (d == null || h == null || !connected()) { start(); return; }
        work.execute(() -> {
            try {
                if (media) {
                    h.sendReport(d, 2, new byte[]{(byte) (key & 0xff), (byte) (key >> 8)});
                    h.sendReport(d, 2, new byte[]{0, 0});
                } else {
                    h.sendReport(d, 1, new byte[]{0, 0, (byte) key, 0, 0, 0, 0, 0});
                    h.sendReport(d, 1, new byte[8]);
                }
            } catch (SecurityException ignored) {}
        });
    }

    private void tell() { Listener l = listener; if (l != null) l.changed(); }
}
