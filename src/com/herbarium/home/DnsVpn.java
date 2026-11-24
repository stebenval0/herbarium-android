package com.herbarium.home;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A VPN that only carries address lookups. Apps ask 10.111.0.2 for a site's address; blocked
 * sites get "no such site", everything else is passed on to the network's real DNS server.
 * No other traffic goes through it.
 */
public class DnsVpn extends VpnService {
    static final String STOP = "com.herbarium.home.STOP_VPN";
    static volatile boolean running;
    private static final String FAKE_DNS = "10.111.0.2";
    private ParcelFileDescriptor tun;
    private Thread reader;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            if (!State.locked(this)) { shutdown(); stopSelf(); }
            return START_NOT_STICKY;
        }
        if (tun == null) {
            try {
                tun = new Builder().setSession("Herbarium")
                        .addAddress("10.111.0.1", 32).addDnsServer(FAKE_DNS).addRoute(FAKE_DNS, 32)
                        .setBlocking(true).establish();
            } catch (Exception e) { Log.w(ClaususService.TAG, "vpn", e); }
            if (tun == null) return START_STICKY;
            running = true;
            reader = new Thread(this::readLoop, "clausus-dns");
            reader.start();
        }
        return START_STICKY;
    }

    @Override public void onRevoke() {
        // another VPN took over, or it was switched off; the service puts it back within 20 s while locked
        shutdown();
        super.onRevoke();
    }

    @Override public void onDestroy() { shutdown(); super.onDestroy(); }

    private synchronized void shutdown() {
        running = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        tun = null;
        if (reader != null) reader.interrupt();
    }

    private void readLoop() {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            while (running) {
                int n = in.read(buf);
                if (n <= 0) continue;
                final byte[] pkt = java.util.Arrays.copyOf(buf, n);
                pool.execute(() -> handle(pkt, out));
            }
        } catch (Exception e) {
            if (running) Log.w(ClaususService.TAG, "dns loop", e);
        }
    }

    // ---------------------------------------------------------------- one lookup
    private void handle(byte[] p, FileOutputStream out) {
        try {
            if ((p[0] >> 4) != 4 || p[9] != 17) return;                  // IPv4 UDP only
            int ihl = (p[0] & 0x0f) * 4;
            int dport = ((p[ihl + 2] & 0xff) << 8) | (p[ihl + 3] & 0xff);
            if (dport != 53) return;
            int udpLen = ((p[ihl + 4] & 0xff) << 8) | (p[ihl + 5] & 0xff);
            int dnsOff = ihl + 8, dnsLen = Math.min(udpLen - 8, p.length - dnsOff);
            byte[] q = java.util.Arrays.copyOfRange(p, dnsOff, dnsOff + dnsLen);
            String name = qname(q);
            byte[] answer;
            boolean locked = State.locked(this);
            String safeTarget = locked && name != null ? Lists.get(this).safe.get(name.toLowerCase()) : null;
            if (name != null && locked && (State.blockedDomain(this, name) || name.equalsIgnoreCase("use-application-dns.net"))) {
                answer = nxdomain(q);       // "use-application-dns.net" tells Firefox to stop using its own DNS-over-HTTPS
            } else if (safeTarget != null) {
                answer = safeSearch(q, safeTarget);   // e.g. google.com → forcesafesearch.google.com
                if (answer == null) return;
            } else {
                answer = forward(q);
                if (answer == null) return;
            }
            byte[] reply = udpReply(p, ihl, answer);
            synchronized (out) { out.write(reply); }
        } catch (Exception e) {
            Log.d(ClaususService.TAG, "lookup", e);
        }
    }

    private final java.util.Map<String, byte[]> safeIps = new java.util.concurrent.ConcurrentHashMap<>();

    /** Answer an A lookup with the safe-search server's address; other record types get an empty answer. */
    private byte[] safeSearch(byte[] q, String target) {
        int end = 12; while (end < q.length && q[end] != 0) end += (q[end] & 0xff) + 1;
        int qtype = end + 2 < q.length ? ((q[end + 1] & 0xff) << 8) | (q[end + 2] & 0xff) : 0;
        int qEnd = end + 5;                       // name + 0 + type(2) + class(2)
        if (qEnd > q.length) return null;
        byte[] ip = safeIps.get(target);
        if (ip == null) {
            try { for (InetAddress a : InetAddress.getAllByName(target)) if (a instanceof Inet4Address) { ip = a.getAddress(); break; } }
            catch (Exception ignored) {}
            if (ip == null) return null;
            safeIps.put(target, ip);
        }
        boolean a = qtype == 1;
        byte[] r = new byte[qEnd + (a ? 16 : 0)];
        System.arraycopy(q, 0, r, 0, qEnd);
        r[2] = (byte) (0x80 | (q[2] & 0x79)); r[3] = (byte) 0x80;   // a normal answer
        r[6] = 0; r[7] = (byte) (a ? 1 : 0); r[8] = r[9] = r[10] = r[11] = 0;
        if (a) {
            int i = qEnd;
            r[i++] = (byte) 0xc0; r[i++] = 0x0c;        // the name from the question
            r[i++] = 0; r[i++] = 1; r[i++] = 0; r[i++] = 1; // type A, class IN
            r[i++] = 0; r[i++] = 0; r[i++] = 0x01; r[i++] = 0x2c;   // TTL 300 s
            r[i++] = 0; r[i++] = 4; System.arraycopy(ip, 0, r, i, 4);
        }
        return r;
    }

    private static String qname(byte[] q) {
        if (q.length < 13) return null;
        StringBuilder sb = new StringBuilder(); int i = 12;
        while (i < q.length) {
            int len = q[i] & 0xff;
            if (len == 0) break;
            if ((len & 0xc0) != 0 || i + 1 + len > q.length) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(q, i + 1, len, java.nio.charset.StandardCharsets.US_ASCII));
            i += 1 + len;
        }
        return sb.toString();
    }

    private static byte[] nxdomain(byte[] q) {
        byte[] r = q.clone();
        r[2] = (byte) (0x80 | (q[2] & 0x79));   // QR=1, keep opcode + RD
        r[3] = (byte) 0x83;                      // RA=1, RCODE=3 (no such name)
        r[6] = r[7] = r[8] = r[9] = r[10] = r[11] = 0;
        return r;
    }

    private byte[] forward(byte[] q) {
        for (InetAddress dns : upstream()) {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s);                                  // keep this socket outside our own VPN
                s.setSoTimeout(3000);
                s.send(new DatagramPacket(q, q.length, dns, 53));
                byte[] buf = new byte[4096];
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                s.receive(r);
                return java.util.Arrays.copyOf(buf, r.getLength());
            } catch (Exception ignored) {}
        }
        return null;
    }

    private java.util.List<InetAddress> upstream() {
        java.util.List<InetAddress> out = new java.util.ArrayList<>();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (!nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (InetAddress a : lp.getDnsServers()) if (a instanceof Inet4Address && !out.contains(a)) out.add(a);
        }
        try { out.add(InetAddress.getByName("1.1.1.1")); out.add(InetAddress.getByName("9.9.9.9")); } catch (Exception ignored) {}
        return out;
    }

    /** Wrap a DNS answer in an IPv4/UDP packet going back to whoever asked. */
    private static byte[] udpReply(byte[] req, int ihl, byte[] dns) {
        int total = 20 + 8 + dns.length;
        byte[] r = new byte[total];
        r[0] = 0x45; r[2] = (byte) (total >> 8); r[3] = (byte) total; r[6] = 0x40; r[8] = 64; r[9] = 17;
        System.arraycopy(req, 16, r, 12, 4);   // src = their destination
        System.arraycopy(req, 12, r, 16, 4);   // dst = their source
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += ((r[i] & 0xff) << 8) | (r[i + 1] & 0xff);
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        sum = ~sum; r[10] = (byte) (sum >> 8); r[11] = (byte) sum;
        r[20] = req[ihl + 2]; r[21] = req[ihl + 3];   // src port = 53
        r[22] = req[ihl];     r[23] = req[ihl + 1];   // dst port = theirs
        int ulen = 8 + dns.length; r[24] = (byte) (ulen >> 8); r[25] = (byte) ulen;   // checksum 0 = none (allowed on IPv4)
        System.arraycopy(dns, 0, r, 28, dns.length);
        return r;
    }
}
