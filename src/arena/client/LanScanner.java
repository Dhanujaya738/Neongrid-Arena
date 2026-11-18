package arena.client;

import arena.common.Json;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds NeonGrid servers on the local network using UDP broadcast (java.net.DatagramSocket).
 *
 *  1. send the datagram "NEONGRID_DISCOVER v1" to 255.255.255.255:8888, to every interface's
 *     directed broadcast address (e.g. 192.168.1.255) and to 127.0.0.1;
 *  2. listen for JSON beacons for a short time - UDP is unreliable, so we send the query 3 times.
 */
public final class LanScanner {

    public static final class Found {
        public final String address;
        public final int port;
        public final Map<String, Object> beacon;
        public final long rttMs;

        Found(String address, int port, Map<String, Object> beacon, long rttMs) {
            this.address = address;
            this.port = port;
            this.beacon = beacon;
            this.rttMs = rttMs;
        }

        @Override
        public String toString() {
            return String.format("%-16s tcp:%-5d  \"%s\"  players=%s rooms=%s  (%d ms)", address, port,
                    beacon.get("name"), beacon.get("players"), beacon.get("rooms"), rttMs);
        }
    }

    public static List<Found> scan(int udpPort, int timeoutMs, boolean verbose) {
        Map<String, Found> found = new LinkedHashMap<>();
        byte[] query = "NEONGRID_DISCOVER v1".getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            sock.setSoTimeout(200);
            Set<InetAddress> targets = new LinkedHashSet<>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
                }
            }
            targets.add(InetAddress.getLoopbackAddress());

            long start = System.currentTimeMillis();
            long nextSend = 0;
            int sends = 0;
            byte[] buf = new byte[2048];
            long sentAt = 0;
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (sends < 3 && System.currentTimeMillis() >= nextSend) {
                    sentAt = System.currentTimeMillis();
                    for (InetAddress t : targets) {
                        try {
                            sock.send(new DatagramPacket(query, query.length, t, udpPort));
                            if (verbose && sends == 0) System.out.println("  → UDP query " + query.length + " bytes to " + t.getHostAddress() + ":" + udpPort);
                        } catch (Exception ignored) { /* interface without route */ }
                    }
                    sends++;
                    nextSend = System.currentTimeMillis() + 400;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try {
                    String json = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    Map<String, Object> beacon = Json.parseObject(json);
                    if (!"beacon".equals(beacon.get("t"))) continue;
                    int port = (int) Json.num(beacon, "tcpPort", 8080);
                    String addr = p.getAddress().getHostAddress();
                    String key = String.valueOf(beacon.get("host")) + ":" + port;
                    if (!found.containsKey(key)) {
                        Found f = new Found(addr, port, beacon, System.currentTimeMillis() - sentAt);
                        found.put(key, f);
                        if (verbose) System.out.println("  ← beacon " + p.getLength() + " bytes from " + addr + ":" + p.getPort());
                    }
                } catch (Exception ignored) { /* not our datagram */ }
            }
        } catch (Exception e) {
            System.err.println("UDP discovery failed: " + e.getMessage());
        }
        return new ArrayList<>(found.values());
    }

    public static void main(String[] args) {
        System.out.println("Scanning the LAN for NeonGrid servers over UDP...");
        List<Found> list = scan(args.length > 0 ? Integer.parseInt(args[0]) : 8888, 1500, true);
        if (list.isEmpty()) System.out.println("No servers answered.");
        for (Found f : list) System.out.println("  ✔ " + f);
    }
}
