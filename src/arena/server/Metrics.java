package arena.server;

import arena.common.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe server statistics. Counters are {@link LongAdder}s because they are incremented
 * from both the selector thread and the worker threads; the security log uses synchronized blocks.
 */
public final class Metrics {

    final long startedAt = System.currentTimeMillis();

    final LongAdder bytesIn = new LongAdder(), bytesOut = new LongAdder();
    final LongAdder framesIn = new LongAdder(), framesOut = new LongAdder();
    final LongAdder accepted = new LongAdder(), closed = new LongAdder();
    final LongAdder loops = new LongAdder(), selectedKeys = new LongAdder();
    final LongAdder compactions = new LongAdder(), partialWrites = new LongAdder();
    final LongAdder udpIn = new LongAdder(), udpOut = new LongAdder();
    final LongAdder dnsLookups = new LongAdder(), aiJobs = new LongAdder(), handshakes = new LongAdder();
    final LongAdder httpRequests = new LongAdder(), wsUpgrades = new LongAdder(), rawConnections = new LongAdder();
    final LongAdder gamesStarted = new LongAdder(), gamesFinished = new LongAdder(), movesPlayed = new LongAdder();

    private final Map<String, LongAdder> securityCounts = new ConcurrentHashMap<>();
    private final ArrayDeque<Map<String, Object>> securityLog = new ArrayDeque<>();
    private final ArrayDeque<Map<String, Object>> discoveryLog = new ArrayDeque<>();

    // per-second history for sparklines (reactor thread only)
    private static final int HISTORY = 60;
    private final ArrayDeque<long[]> history = new ArrayDeque<>();
    private long[] prev = new long[6];
    private volatile Map<String, Object> last = Json.obj("status", "starting");

    // packet capture ring buffer (Control Room "Wireshark" view)
    volatile boolean captureOn;
    private long captureId;
    private final ArrayDeque<Map<String, Object>> captureLog = new ArrayDeque<>();
    private final ArrayDeque<Map<String, Object>> aiLog = new ArrayDeque<>();

    void capture(Connection c, String dir, String type, Object seq, String frame, String status) {
        boolean signed = !"plain".equals(status) && frame.length() > 64;
        String body = signed ? frame.substring(64) : frame;
        if (body.length() > 220) body = body.substring(0, 220) + "…";
        Map<String, Object> e = Json.obj("id", 0L, "ts", System.currentTimeMillis(), "conn", c.id,
                "who", c.session != null ? c.session.name : c.remote.getAddress().getHostAddress(),
                "kind", c.clientKind, "dir", dir, "type", type, "seq", seq,
                "bytes", frame.length(), "status", status, "mac", signed ? frame.substring(0, 16) : null, "body", body);
        synchronized (captureLog) {
            e.put("id", ++captureId);
            captureLog.addLast(e);
            while (captureLog.size() > 600) captureLog.removeFirst();
        }
    }

    long lastCaptureId() {
        synchronized (captureLog) { return captureId; }
    }

    List<Object> captureSince(long id, int max) {
        List<Object> out = new ArrayList<>();
        synchronized (captureLog) {
            for (Map<String, Object> e : captureLog) if ((Long) e.get("id") > id) out.add(e);
        }
        return out.size() > max ? new ArrayList<>(out.subList(out.size() - max, out.size())) : out;
    }

    void aiRun(Map<String, Object> e) {
        synchronized (aiLog) {
            aiLog.addFirst(e);
            while (aiLog.size() > 8) aiLog.removeLast();
        }
    }

    public void securityEvent(String kind, String who, String detail) {
        securityCounts.computeIfAbsent(kind, k -> new LongAdder()).increment();
        long now = System.currentTimeMillis();
        synchronized (securityLog) {
            // Coalesce bursts (e.g. a flood) into one feed entry with a counter.
            Map<String, Object> head = securityLog.peekFirst();
            if (head != null && kind.equals(head.get("kind")) && who.equals(head.get("who")) && now - (Long) head.get("ts") < 5000) {
                head.put("n", (Long) head.get("n") + 1);
                head.put("ts", now);
                return;
            }
            securityLog.addFirst(Json.obj("ts", now, "kind", kind, "who", who, "detail", detail, "n", 1L));
            while (securityLog.size() > 40) securityLog.removeLast();
        }
        if (!"RATE_LIMIT".equals(kind) || securityCounts.get(kind).sum() % 25 == 1) {
            System.out.println("[security] " + kind + " " + who + " - " + detail);
        }
    }

    void discovery(String from, String reply) {
        synchronized (discoveryLog) {
            discoveryLog.addFirst(Json.obj("ts", System.currentTimeMillis(), "from", from, "bytes", reply.length()));
            while (discoveryLog.size() > 12) discoveryLog.removeLast();
        }
    }

    public Map<String, Object> lastSnapshot() { return last; }

    /** Called once per second on the reactor thread. */
    Map<String, Object> tick(Reactor r) {
        long[] now = {bytesIn.sum(), bytesOut.sum(), framesIn.sum(), framesOut.sum(), loops.sum(), selectedKeys.sum()};
        long[] rate = new long[now.length + 1];
        for (int i = 0; i < now.length; i++) rate[i] = now[i] - prev[i];
        rate[now.length] = r.connections.size();
        prev = now;
        history.addLast(rate);
        while (history.size() > HISTORY) history.removeFirst();

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("t", "stats");
        s.put("server", r.config.serverName);
        s.put("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000);
        s.put("selectorThread", r.thread().getName());
        s.put("jvmThreads", Thread.activeCount());

        int ws = 0, raw = 0, http = 0, other = 0;
        List<Object> conns = new ArrayList<>();
        for (Connection c : r.connections.values()) {
            switch (c.mode) {
                case WEBSOCKET: ws++; break;
                case RAW: raw++; break;
                case HTTP: case HTTP_DONE: http++; break;
                default: other++;
            }
        }
        List<Connection> recent = new ArrayList<>(r.connections.values());
        Collections.reverse(recent);
        for (Connection c : recent) {
            if (conns.size() >= 30) break;
            if (c.mode == Connection.Mode.HTTP_DONE) continue;
            conns.add(Json.obj("id", c.id, "ip", c.remote.getAddress().getHostAddress(), "port", c.remote.getPort(),
                    "host", c.hostName, "mode", c.mode.name(), "kind", c.clientKind,
                    "name", c.session != null ? c.session.name : null,
                    "secure", c.link != null, "rtt", c.rttMs,
                    "in", c.bytesIn, "out", c.bytesOut, "framesIn", c.framesIn, "framesOut", c.framesOut,
                    "ageSec", (System.currentTimeMillis() - c.openedAt) / 1000));
        }
        s.put("connections", Json.obj("active", r.connections.size(), "websocket", ws, "raw", raw, "http", http,
                "handshaking", other, "accepted", accepted.sum(), "closed", closed.sum()));
        s.put("connList", conns);

        s.put("nio", Json.obj("loopsPerSec", rate[4], "keysPerSec", rate[5], "loopsTotal", now[4],
                "compactions", compactions.sum(), "partialWrites", partialWrites.sum(),
                "bytesIn", now[0], "bytesOut", now[1], "framesIn", now[2], "framesOut", now[3],
                "bytesInPerSec", rate[0], "bytesOutPerSec", rate[1], "framesInPerSec", rate[2], "framesOutPerSec", rate[3]));
        s.put("workers", Json.obj("poolSize", r.workers.getPoolSize(), "active", r.workers.getActiveCount(),
                "queued", r.workers.getQueue().size(), "completed", r.workers.getCompletedTaskCount(),
                "aiJobs", aiJobs.sum(), "dnsLookups", dnsLookups.sum(), "handshakes", handshakes.sum()));
        s.put("http", Json.obj("requests", httpRequests.sum(), "wsUpgrades", wsUpgrades.sum(), "rawConnections", rawConnections.sum()));
        synchronized (discoveryLog) {
            s.put("udp", Json.obj("port", r.config.udpPort, "requests", udpIn.sum(), "replies", udpOut.sum(),
                    "recent", new ArrayList<>(discoveryLog)));
        }
        s.put("game", r.lobby.gameSummary());
        synchronized (aiLog) { s.put("aiRecent", new ArrayList<>(aiLog)); }
        Map<String, Object> sec = new LinkedHashMap<>();
        for (Map.Entry<String, LongAdder> e : securityCounts.entrySet()) sec.put(e.getKey(), e.getValue().sum());
        synchronized (securityLog) {
            s.put("security", Json.obj("counts", sec, "recent", new ArrayList<>(securityLog)));
        }
        List<Object> hist = new ArrayList<>();
        for (long[] h : history) hist.add(new long[]{h[0], h[1], h[2], h[3], h[4], h[6]});
        s.put("history", hist); // [bytesIn/s, bytesOut/s, framesIn/s, framesOut/s, loops/s, connections]
        last = s;
        return s;
    }
}
