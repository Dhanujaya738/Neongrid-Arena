package arena.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Addressing helpers (Lecture 4): enumerates this machine's private LAN IPv4 addresses. */
final class NetUtil {

    private static volatile List<String> cached;
    private static volatile String host;

    private NetUtil() {}

    static List<String> lanAddresses() {
        List<String> c = cached;
        if (c != null) return c;
        List<String> preferred = new ArrayList<>(), others = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String dn = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
                boolean virtualish = dn.contains("vmware") || dn.contains("virtualbox") || dn.contains("hyper-v")
                        || dn.contains("vethernet") || dn.contains("docker") || dn.contains("wsl");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    // Private ranges 10/8, 172.16/12, 192.168/16 are the ones phones on the same Wi-Fi can reach
                    if (a.isSiteLocalAddress() && !virtualish) preferred.add(a.getHostAddress());
                    else others.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { /* no interfaces */ }
        // The interface that owns the default route is the one other devices can most likely reach.
        // "Connecting" a UDP socket sends no packet; it only asks the OS routing table for a source address.
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
            probe.connect(InetAddress.getByName("8.8.8.8"), 53);
            String primary = probe.getLocalAddress().getHostAddress();
            if (preferred.remove(primary) || others.remove(primary)) preferred.add(0, primary);
        } catch (Exception ignored) { /* offline: keep interface order */ }
        preferred.addAll(others);
        cached = preferred;
        return preferred;
    }

    static String hostName() {
        String h = host;
        if (h != null) return h;
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            h = "localhost";
        }
        host = h;
        return h;
    }
}
