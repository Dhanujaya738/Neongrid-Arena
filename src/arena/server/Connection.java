package arena.server;

import arena.common.Crypto;
import arena.common.Json;
import arena.common.SecureLink;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * State of one TCP connection inside the reactor.
 *
 * A single TCP port speaks three protocols. The first bytes decide ("protocol sniffing"):
 *   "NGA1"  -> RAW   : length-prefixed frames  [int32 length][UTF-8 payload]  (Java console / bot clients)
 *   other   -> HTTP  : static web app + REST API; "Upgrade: websocket" switches to WEBSOCKET (browsers)
 *
 * Reading follows the classic NIO pattern:
 *     channel.read(in)  ->  in.flip()  ->  decode complete frames  ->  in.compact()
 * compact() keeps a half-received frame at the start of the buffer so the next read appends to it.
 */
final class Connection {

    /** HTTP_DONE = one request parsed, response being produced; further input is ignored. */
    enum Mode { SNIFF, HTTP, HTTP_DONE, WEBSOCKET, RAW }

    static final byte[] RAW_MAGIC = {'N', 'G', 'A', '1'};
    static final int MAX_FRAME = 64 * 1024;
    static final int MAX_BUFFER = 256 * 1024;
    static final int MAX_PENDING_OUT = 4 * 1024 * 1024;

    final Reactor reactor;
    final long id;
    final SocketChannel ch;
    final InetSocketAddress remote;
    final long openedAt = System.currentTimeMillis();
    SelectionKey key;

    Mode mode = Mode.SNIFF;
    private ByteBuffer in = ByteBuffer.allocate(8 * 1024);
    private final ArrayDeque<ByteBuffer> out = new ArrayDeque<>();
    private long pendingOut;
    private boolean closeAfterFlush;
    boolean closed;

    long bytesIn, bytesOut, framesIn, framesOut;
    long lastActivity = System.currentTimeMillis();
    String hostName;
    String clientKind = "?";

    // ---- session / security
    SecureLink link;
    Session session;
    boolean handshaking;
    boolean dashboard;
    boolean admin;
    int adminFails;
    long lastCaptureId;
    /** Per-connection challenge for the control-room login (HMAC challenge-response). */
    final String adminNonce = Crypto.hex(Crypto.randomBytes(16));
    long rttMs = -1;
    private final TokenBucket limiter = new TokenBucket(40, 20); // burst 40, 20 msg/s sustained
    private int strikes;
    private long rateDropped, rateNoticeAt;
    private long strikeWindowStart = System.currentTimeMillis();

    // ---- websocket fragmentation
    private java.io.ByteArrayOutputStream fragments;

    private Reactor.Timer idleTimer;

    Connection(Reactor reactor, long id, SocketChannel ch) throws IOException {
        this.reactor = reactor;
        this.id = id;
        this.ch = ch;
        this.remote = (InetSocketAddress) ch.getRemoteAddress();
    }

    String label() {
        String who = session != null ? session.name : (hostName != null ? hostName : remote.getAddress().getHostAddress());
        return "#" + id + " " + who + " (" + remote.getAddress().getHostAddress() + ":" + remote.getPort() + ")";
    }

    // =================================================================== reading

    void onReadable() throws IOException {
        int n = ch.read(in);
        if (n < 0) { close("peer closed connection"); return; }
        if (n == 0) return;
        bytesIn += n;
        reactor.metrics.bytesIn.add(n);
        lastActivity = System.currentTimeMillis();

        in.flip();                       // switch buffer to "read mode": position=0, limit=bytes available
        try {
            decode();
        } catch (ProtocolException | IllegalArgumentException e) {
            reactor.metrics.securityEvent("PROTOCOL", label(), e.getMessage());
            close("protocol error: " + e.getMessage());
            return;
        } finally {
            if (!closed) {
                in.compact();            // keep unread (partial) bytes, go back to "write mode"
                reactor.metrics.compactions.increment();
            }
        }
        if (!closed && !in.hasRemaining()) growBuffer();
    }

    private void growBuffer() {
        if (in.capacity() >= MAX_BUFFER) {
            reactor.metrics.securityEvent("OVERSIZE", label(), "frame exceeds " + MAX_BUFFER + " bytes");
            close("frame too large");
            return;
        }
        ByteBuffer bigger = ByteBuffer.allocate(in.capacity() * 2);
        in.flip();
        bigger.put(in);
        in = bigger;
    }

    private void decode() throws ProtocolException {
        while (!closed && !closeAfterFlush && in.hasRemaining()) {
            switch (mode) {
                case SNIFF:
                    if (in.remaining() < 4) return;
                    if (startsWithMagic()) {
                        in.position(in.position() + 4);
                        mode = Mode.RAW;
                        clientKind = "raw-tcp";
                        reactor.metrics.rawConnections.increment();
                    } else {
                        mode = Mode.HTTP;
                    }
                    break;
                case HTTP:
                    if (!decodeHttpHead()) return;
                    break;
                case WEBSOCKET: {
                    WebSocket.Frame f = WebSocket.decode(in, MAX_FRAME);
                    if (f == null) return;
                    onWebSocketFrame(f);
                    break;
                }
                case RAW: {
                    if (in.remaining() < 4) return;
                    int len = in.getInt(in.position());          // peek the length prefix
                    if (len <= 0 || len > MAX_FRAME) throw new ProtocolException("bad frame length " + len);
                    if (in.remaining() < 4 + len) return;         // frame not complete yet
                    in.getInt();
                    byte[] payload = new byte[len];
                    in.get(payload);
                    onText(new String(payload, StandardCharsets.UTF_8));
                    break;
                }
                default:
                    return;
            }
        }
    }

    private boolean startsWithMagic() {
        int p = in.position();
        for (int i = 0; i < 4; i++) if (in.get(p + i) != RAW_MAGIC[i]) return false;
        return true;
    }

    private boolean decodeHttpHead() throws ProtocolException {
        int start = in.position(), end = in.limit();
        for (int i = start; i + 3 < end; i++) {
            if (in.get(i) == '\r' && in.get(i + 1) == '\n' && in.get(i + 2) == '\r' && in.get(i + 3) == '\n') {
                byte[] head = new byte[i - start];
                in.get(head);
                in.position(i + 4);
                reactor.http.handle(this, new String(head, StandardCharsets.ISO_8859_1));
                return true;
            }
        }
        if (in.remaining() > 16 * 1024) throw new ProtocolException("HTTP header too large");
        return false;
    }

    private void onWebSocketFrame(WebSocket.Frame f) {
        switch (f.opcode) {
            case WebSocket.OP_TEXT:
            case WebSocket.OP_BINARY:
                if (f.fin) {
                    onText(new String(f.payload, StandardCharsets.UTF_8));
                } else {
                    fragments = new java.io.ByteArrayOutputStream();
                    fragments.write(f.payload, 0, f.payload.length);
                }
                break;
            case WebSocket.OP_CONT:
                if (fragments == null) return;
                fragments.write(f.payload, 0, f.payload.length);
                if (fragments.size() > MAX_FRAME) { close("fragmented message too large"); return; }
                if (f.fin) {
                    String s = new String(fragments.toByteArray(), StandardCharsets.UTF_8);
                    fragments = null;
                    onText(s);
                }
                break;
            case WebSocket.OP_PING:
                write(WebSocket.encode(WebSocket.OP_PONG, f.payload));
                break;
            case WebSocket.OP_CLOSE:
                write(WebSocket.encode(WebSocket.OP_CLOSE, f.payload));
                finishAndClose();
                break;
            default:
                break; // pong / unknown: ignore
        }
    }

    /** One complete application message (from WebSocket or RAW framing). */
    private void onText(String text) {
        framesIn++;
        reactor.metrics.framesIn.increment();

        // DoS protection: token bucket rate limiting BEFORE doing any expensive work.
        if (!limiter.tryAcquire()) {
            capPeek(text, "RATE_LIMIT");
            strike("RATE_LIMIT", "more than 20 msg/s (token bucket empty)");
            rateDropped++;
            long now = System.currentTimeMillis();
            if (link != null && now - rateNoticeAt > 1500) {  // tell the client at most once per burst
                rateNoticeAt = now;
                reactor.schedule(400, () -> sendSecure(Json.obj("t", "rejected", "reason", "RATE_LIMIT",
                        "detail", "Flood protection: dropped " + rateDropped + " messages (token bucket 40 burst / 20 per s)")));
            }
            return;
        }
        if (link == null) {
            if (handshaking) return; // still computing our DH reply; client must wait for "welcome"
            cap("in", "hello", null, text, "plain");
            reactor.lobby.onHello(this, text);
            return;
        }
        Map<String, Object> msg;
        try {
            msg = link.open(text); // verifies HMAC + sequence number
        } catch (SecureLink.Rejected r) {
            capPeek(text, r.kind);
            strike(r.kind, r.getMessage());
            sendSecure(Json.obj("t", "rejected", "reason", r.kind, "detail", r.getMessage()));
            return;
        }
        cap("in", Json.str(msg, "t", "?"), msg.get("seq"), text, "ok");
        reactor.lobby.onMessage(this, msg);
    }

    /** Server-side packet capture for the Network Control Room (only while an admin is watching). */
    void cap(String dir, String type, Object seq, String frame, String status) {
        if (!reactor.metrics.captureOn || dashboard) return;
        // load-test bots produce ~1000 frames/s: sample 1 in 50 of their normal frames so people stay visible
        if (clientKind.startsWith("bot") && "ok".equals(status) && (framesIn + framesOut) % 50 != 0) return;
        reactor.metrics.capture(this, dir, type, seq, frame, status);
    }

    /** Capture a rejected incoming frame, reading its type/seq without trusting it. */
    private void capPeek(String text, String status) {
        if (!reactor.metrics.captureOn || dashboard) return;
        String type = "?";
        Object seq = null;
        try {
            Map<String, Object> peek = Json.parseObject(text.substring(Math.min(text.length(), SecureLink.MAC_HEX_LEN)));
            type = Json.str(peek, "t", "?");
            seq = peek.get("seq");
        } catch (RuntimeException ignored) { /* unreadable */ }
        cap("in", type, seq, text, status);
    }

    void sendPlain(String text, String type) {
        cap("out", type, null, text, "plain");
        sendText(text);
    }

    private void strike(String kind, String detail) {
        reactor.metrics.securityEvent(kind, label(), detail);
        long now = System.currentTimeMillis();
        if (now - strikeWindowStart > 10_000) { strikeWindowStart = now; strikes = 0; }
        if (++strikes > 400) {
            reactor.metrics.securityEvent("BANNED", label(), "too many violations - connection dropped");
            close("too many security violations");
        }
    }

    // =================================================================== writing

    void sendSecure(Map<String, Object> msg) {
        if (link == null || closed) return;
        String frame = link.seal(msg);
        cap("out", String.valueOf(msg.get("t")), msg.get("seq"), frame, "ok");
        sendText(frame);
    }

    void sendText(String text) {
        if (closed) return;
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf;
        if (mode == Mode.WEBSOCKET) {
            buf = WebSocket.encode(WebSocket.OP_TEXT, b);
        } else if (mode == Mode.RAW) {
            buf = ByteBuffer.allocate(4 + b.length);
            buf.putInt(b.length).put(b).flip();
        } else {
            return;
        }
        framesOut++;
        reactor.metrics.framesOut.increment();
        write(buf);
    }

    /** Queue bytes for sending. Tries to write immediately; leftovers wait for OP_WRITE. */
    void write(ByteBuffer buf) {
        if (closed) return;
        if (out.isEmpty()) {
            try {
                int n = ch.write(buf);
                account(n);
            } catch (IOException e) {
                close("write failed: " + e.getMessage());
                return;
            }
            if (!buf.hasRemaining()) return;
            reactor.metrics.partialWrites.increment(); // socket send buffer was full
        }
        out.add(buf);
        pendingOut += buf.remaining();
        if (pendingOut > MAX_PENDING_OUT) { close("client too slow (output backlog)"); return; }
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    void onWritable() throws IOException {
        while (!out.isEmpty()) {
            ByteBuffer b = out.peek();
            int n = ch.write(b);
            account(n);
            pendingOut -= n;
            if (b.hasRemaining()) return; // still full: wait for the next OP_WRITE
            out.poll();
        }
        key.interestOps(SelectionKey.OP_READ); // nothing left: stop asking for write-readiness
        if (closeAfterFlush) close("response complete");
    }

    private void account(int n) {
        bytesOut += n;
        reactor.metrics.bytesOut.add(n);
    }

    /** Close once all queued output has been flushed (used after HTTP responses). */
    void finishAndClose() {
        if (out.isEmpty()) close("response complete");
        else closeAfterFlush = true;
    }

    void close(String reason) {
        if (closed) return;
        closed = true;
        if (idleTimer != null) idleTimer.cancel();
        if (key != null) key.cancel();
        try { ch.close(); } catch (IOException ignored) { }
        reactor.removed(this);
        if (session != null) reactor.lobby.onDisconnect(session, this, reason);
    }

    /** Slowloris / idle protection: drop sockets that stay silent for too long. */
    void armIdleTimer() {
        idleTimer = reactor.schedule(10_000, () -> {
            if (closed) return;
            boolean http = mode == Mode.HTTP || mode == Mode.HTTP_DONE;
            long limit = (mode == Mode.SNIFF || http) ? 10_000 : 45_000;
            if (System.currentTimeMillis() - lastActivity > limit) {
                if (!http) reactor.metrics.securityEvent("IDLE", label(), "no traffic for " + limit / 1000 + "s");
                close("idle timeout");
            } else {
                armIdleTimer();
            }
        });
    }

    /** Classic token-bucket rate limiter. */
    static final class TokenBucket {
        private final double capacity, refillPerMs;
        private double tokens;
        private long last = System.currentTimeMillis();

        TokenBucket(int capacity, int perSecond) {
            this.capacity = capacity;
            this.refillPerMs = perSecond / 1000.0;
            this.tokens = capacity;
        }

        boolean tryAcquire() {
            long now = System.currentTimeMillis();
            tokens = Math.min(capacity, tokens + (now - last) * refillPerMs);
            last = now;
            if (tokens < 1) return false;
            tokens -= 1;
            return true;
        }
    }
}
