package arena.server;

import arena.common.Crypto;
import arena.common.Json;
import arena.common.SecureLink;
import arena.game.Board;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Session management, secure handshake, matchmaking and message routing.
 * Everything here runs on the selector thread only (thread confinement => no locks needed).
 */
final class Lobby {

    final Reactor reactor;
    final Leaderboard leaderboard;

    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<Board.Mode, ArrayDeque<Session>> queues = new EnumMap<>(Board.Mode.class);
    private boolean dirty = true;

    Lobby(Reactor reactor, Leaderboard leaderboard) {
        this.reactor = reactor;
        this.leaderboard = leaderboard;
        for (Board.Mode m : Board.Mode.values()) queues.put(m, new ArrayDeque<>());
    }

    // ===================================================================== handshake

    /**
     * Step 1 of the secure handshake. The client sent {t:"hello", name, dh:"<g^a mod p>"}.
     * The modular exponentiation is CPU heavy, so it runs on a worker thread.
     */
    void onHello(Connection c, String text) {
        Map<String, Object> m;
        try {
            m = Json.parseObject(text);
        } catch (IllegalArgumentException e) {
            reactor.metrics.securityEvent("MALFORMED", c.label(), "handshake is not JSON");
            c.close("bad handshake");
            return;
        }
        String dh = Json.str(m, "dh", "");
        if (!"hello".equals(m.get("t")) || dh.length() < 64 || dh.length() > 600 || !dh.matches("[0-9a-fA-F]+")) {
            reactor.metrics.securityEvent("HANDSHAKE", c.label(), "invalid hello message");
            c.close("bad handshake");
            return;
        }
        final String name = cleanName(Json.str(m, "name", ""));
        final String kind = Json.str(m, "client", "web").replaceAll("[^a-z]", "");
        @SuppressWarnings("unchecked")
        final Map<String, Object> resume = m.get("resume") instanceof Map ? (Map<String, Object>) m.get("resume") : null;
        c.handshaking = true;
        c.clientKind = kind + (c.mode == Connection.Mode.RAW ? "/raw-tcp" : "/websocket");
        final BigInteger peer = new BigInteger(dh, 16);

        reactor.submit(() -> {
            BigInteger priv = Crypto.newPrivateKey();
            BigInteger pub = Crypto.publicKey(priv);
            byte[] key = Crypto.deriveSessionKey(peer, priv);   // validates peer value too
            reactor.metrics.handshakes.increment();
            return new Object[]{pub, key};
        }, res -> finishHello(c, name, kind, dh, resume, (BigInteger) res[0], (byte[]) res[1]), err -> {
            reactor.metrics.securityEvent("HANDSHAKE", c.label(), err.getMessage());
            c.close("handshake failed");
        });
    }

    private void finishHello(Connection c, String name, String kind, String clientDh, Map<String, Object> resume,
                             BigInteger serverPub, byte[] key) {
        if (c.closed) return;
        Session s = null;
        boolean resumed = false;
        if (resume != null) {
            Session old = sessions.get(Json.str(resume, "sid", ""));
            String proof = Json.str(resume, "proof", "");
            // Proof = HMAC(token, clientDhPublic): only the real owner knows the token.
            if (old != null && proof.length() == 64
                    && Crypto.safeEquals(proof, Crypto.hex(Crypto.hmac(old.token, clientDh.toLowerCase(Locale.ROOT).getBytes())))) {
                s = old;
                resumed = true;
                if (s.conn != null && s.conn != c && !s.conn.closed) {
                    Connection prev = s.conn;
                    prev.session = null;
                    prev.close("session resumed elsewhere");
                }
            } else if (old != null) {
                reactor.metrics.securityEvent("BAD_RESUME", c.label(), "invalid session proof for " + old.name);
            }
        }
        if (s == null) {
            s = new Session(uniqueName(name));
            s.ranked = !"bot".equals(kind) && !"dashboard".equals(kind) && !"lab".equals(kind);
            s.hidden = "dashboard".equals(kind) || "lab".equals(kind);
            sessions.put(s.sid, s);
        }
        if (s.expiryTimer != null) { s.expiryTimer.cancel(); s.expiryTimer = null; }
        s.conn = c;
        c.session = s;
        c.handshaking = false;
        c.dashboard = "dashboard".equals(kind);

        // Plain welcome: the client needs our DH public value before it can verify anything.
        byte[] wrapped = Crypto.xor(s.token, Crypto.tokenWrapKey(key));
        c.sendPlain(Json.stringify(Json.obj("t", "welcome", "dh", serverPub.toString(16), "sid", s.sid,
                "tokenWrapped", Crypto.hex(wrapped), "resumed", resumed)), "welcome");
        c.link = new SecureLink(key);

        // From here on every frame is signed + sequenced.
        c.sendSecure(Json.obj("t", "profile", "sid", s.sid, "name", s.name, "hue", s.hue,
                "elo", leaderboard.elo(s.name), "resumed", resumed, "nonce", c.adminNonce,
                "server", Json.obj("name", reactor.config.serverName, "version", ArenaServer.VERSION,
                        "host", NetUtil.hostName(), "lan", NetUtil.lanAddresses(),
                        "tcpPort", reactor.config.tcpPort, "udpPort", reactor.config.udpPort,
                        "connId", c.id, "you", c.remote.getAddress().getHostAddress() + ":" + c.remote.getPort()),
                "crypto", Json.obj("kex", "Diffie-Hellman RFC 3526 group 14 (2048-bit)", "kdf", "SHA-256",
                        "mac", "HMAC-SHA256", "replay", "strictly increasing sequence numbers")));
        System.out.println("[session] " + (resumed ? "resumed " : "new ") + s.name + " via " + c.clientKind + " " + c.label());
        if (resumed && s.room != null && !s.room.closed) s.room.onReconnect(s);
        else { s.room = null; sendLobby(s); }
        dirty = true;
    }

    private static String cleanName(String raw) {
        String n = raw.replaceAll("[^\\p{L}\\p{N} _.\\-]", "").trim();
        if (n.length() > 16) n = n.substring(0, 16).trim();
        if (n.isEmpty()) n = "Player-" + Crypto.randomCode(3);
        return n;
    }

    private String uniqueName(String n) {
        for (Session s : sessions.values()) {
            if (s.online() && s.name.equalsIgnoreCase(n)) return n + "-" + Crypto.randomCode(2);
        }
        return n;
    }

    // ===================================================================== messages

    void onMessage(Connection c, Map<String, Object> m) {
        Session s = c.session;
        if (s == null) return;
        String t = Json.str(m, "t", "");
        Room room = s.room;
        switch (t) {
            case "ping":
                c.rttMs = Json.num(m, "rtt", -1);
                c.sendSecure(Json.obj("t", "pong", "ts", m.get("ts"), "server", System.currentTimeMillis()));
                break;
            case "lobby":
                sendLobby(s);
                break;
            case "quick":
                quickMatch(s, Board.Mode.parse(Json.str(m, "mode", "ultimate")));
                break;
            case "cancel":
                dequeue(s);
                s.send(Json.obj("t", "queue", "waiting", false));
                break;
            case "create": {
                leaveCurrent(s);
                Room r = newRoom(Board.Mode.parse(Json.str(m, "mode", "ultimate")), Json.bool(m, "private", true));
                r.seat(s, 1);
                r.system("🛰️ Room " + r.code + " created. Share the invite link!");
                r.broadcast();
                break;
            }
            case "join":
            case "spectate": {
                String code = Json.str(m, "code", "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                Room r = rooms.get(code);
                if (r == null) {
                    s.send(Json.obj("t", "error", "code", "NO_ROOM", "msg", "Room " + code + " does not exist"));
                    break;
                }
                if (room != r) leaveCurrent(s);
                dequeue(s);
                if ("spectate".equals(t) && r.sideOf(s) == 0) r.spectate(s); else r.join(s);
                break;
            }
            case "ai": {
                leaveCurrent(s);
                String level = Json.str(m, "level", "medium");
                if (!level.equals("easy") && !level.equals("medium") && !level.equals("hard")) level = "medium";
                Room r = newRoom(Board.Mode.parse(Json.str(m, "mode", "ultimate")), true);
                Session bot = new Session("NeonBot · " + Character.toUpperCase(level.charAt(0)) + level.substring(1), level);
                boolean humanFirst = ThreadLocalRandom.current().nextBoolean();
                r.seat(s, humanFirst ? 1 : 2);
                r.seat(bot, humanFirst ? 2 : 1);
                r.start();
                break;
            }
            case "move":
                if (room == null) { s.send(Json.obj("t", "error", "code", "NO_ROOM", "msg", "You are not in a room")); break; }
                room.move(s, (int) Json.num(m, "cell", -1));
                break;
            case "chat":
                if (room != null) room.chat(s, Json.str(m, "text", ""));
                break;
            case "react":
                if (room != null) room.react(s, Json.str(m, "emoji", ""));
                break;
            case "rematch":
                if (room != null) room.rematch(s);
                break;
            case "leave":
                leaveCurrent(s);
                dequeue(s);
                sendLobby(s);
                break;
            case "admin_login":
                adminLogin(c, Json.str(m, "proof", ""));
                break;
            case "stats":
            case "dns":
            case "url":
                if (!c.admin) {
                    reactor.metrics.securityEvent("FORBIDDEN", c.label(), "'" + t + "' requires the control-room password");
                    c.sendSecure(Json.obj("t", "rejected", "reason", "FORBIDDEN", "detail", "admin login required"));
                    break;
                }
                adminTool(c, t, m);
                break;
            default:
                reactor.metrics.securityEvent("UNKNOWN_TYPE", c.label(), "message type '" + t + "'");
                c.sendSecure(Json.obj("t", "rejected", "reason", "UNKNOWN_TYPE", "detail", "unknown message type " + t));
        }
    }

    /**
     * Control-room login by challenge-response: the browser sends HMAC(SHA-256(password), nonce).
     * The password never travels, and a captured proof is useless on any other connection.
     */
    private void adminLogin(Connection c, String proof) {
        byte[] pwHash = Crypto.sha256(reactor.config.adminPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String expected = Crypto.hmacHex(pwHash, c.adminNonce);
        if (proof.length() == 64 && Crypto.safeEquals(proof, expected)) {
            c.admin = true;
            c.dashboard = true;
            c.lastCaptureId = reactor.metrics.lastCaptureId();
            reactor.metrics.captureOn = true;
            reactor.metrics.securityEvent("ADMIN_LOGIN", c.label(), "control room unlocked");
            c.sendSecure(Json.obj("t", "admin_ok"));
            c.sendSecure(new LinkedHashMap<>(reactor.metrics.lastSnapshot()));
        } else {
            c.adminFails++;
            reactor.metrics.securityEvent("ADMIN_LOGIN_FAIL", c.label(), "wrong password (attempt " + c.adminFails + "/5)");
            c.sendSecure(Json.obj("t", "admin_fail", "remaining", Math.max(0, 5 - c.adminFails)));
            if (c.adminFails >= 5) reactor.schedule(300, () -> c.close("too many control-room login failures"));
        }
    }

    private void adminTool(Connection c, String t, Map<String, Object> m) {
        switch (t) {
            case "stats":
                c.sendSecure(new LinkedHashMap<>(reactor.metrics.lastSnapshot()));
                break;
            case "url": {
                Map<String, Object> r = Http.urlAnatomy(Json.str(m, "u", ""));
                r.put("t", "url_result");
                c.sendSecure(r);
                break;
            }
            case "dns": {
                String host = Json.str(m, "host", "").trim();
                reactor.submit(() -> Http.dnsLookup(reactor, host), c::sendSecure,
                        err -> c.sendSecure(Json.obj("t", "dns_result", "ok", false, "host", host, "error", "Unknown host: " + err.getMessage())));
                break;
            }
            default:
                break;
        }
    }

    private void leaveCurrent(Session s) {
        if (s.room != null) s.room.leave(s);
        s.room = null;
    }

    private Room newRoom(Board.Mode mode, boolean isPrivate) {
        String code;
        do { code = Crypto.randomCode(4); } while (rooms.containsKey(code));
        Room r = new Room(this, code, mode, isPrivate);
        rooms.put(code, r);
        dirty = true;
        return r;
    }

    void roomClosed(Room r) {
        rooms.remove(r.code);
        dirty = true;
    }

    // ===================================================================== matchmaking

    private void quickMatch(Session s, Board.Mode mode) {
        leaveCurrent(s);
        dequeue(s);
        ArrayDeque<Session> q = queues.get(mode);
        Session other;
        while ((other = q.poll()) != null) {
            if (other.online() && other.room == null && other != s) break;
            other.queuedFor = null;
        }
        if (other == null) {
            q.add(s);
            s.queuedFor = mode;
            s.send(Json.obj("t", "queue", "waiting", true, "mode", mode.id(), "size", q.size()));
            dirty = true;
            return;
        }
        other.queuedFor = null;
        Room r = newRoom(mode, false);
        boolean flip = ThreadLocalRandom.current().nextBoolean();
        r.seat(flip ? s : other, 1);
        r.seat(flip ? other : s, 2);
        s.send(Json.obj("t", "queue", "waiting", false, "matched", true));
        other.send(Json.obj("t", "queue", "waiting", false, "matched", true));
        r.start();
    }

    private void dequeue(Session s) {
        if (s.queuedFor != null) {
            queues.get(s.queuedFor).remove(s);
            s.queuedFor = null;
            dirty = true;
        }
    }

    // ===================================================================== disconnects

    void onDisconnect(Session s, Connection c, String reason) {
        if (s.conn != c) return; // already resumed on a newer connection
        s.conn = null;
        s.disconnectedAt = System.currentTimeMillis();
        dequeue(s);
        System.out.println("[session] " + s.name + " disconnected: " + reason);
        if (s.room != null) s.room.onDisconnect(s);
        // keep the session around for 5 minutes so a refreshed tab can resume it
        s.expiryTimer = reactor.schedule(5 * 60_000, () -> {
            if (!s.online()) {
                if (s.room != null) s.room.leave(s);
                sessions.remove(s.sid);
            }
        });
        dirty = true;
    }

    // ===================================================================== info

    private void sendLobby(Session s) {
        s.send(Json.obj("t", "lobby", "online", onlineCount(), "rooms", roomList(), "leaderboard", leaderboard.top(10),
                "queues", Json.obj("classic", queues.get(Board.Mode.CLASSIC).size(), "ultimate", queues.get(Board.Mode.ULTIMATE).size())));
    }

    List<Object> roomList() {
        List<Object> l = new ArrayList<>();
        for (Room r : rooms.values()) if (!r.isPrivate || r.phase == Room.Phase.PLAYING) l.add(r.summary());
        return l;
    }

    List<String> inviteLinks(String code) {
        List<String> l = new ArrayList<>();
        for (String ip : NetUtil.lanAddresses()) l.add("http://" + ip + ":" + reactor.config.tcpPort + "/r/" + code);
        l.add("http://localhost:" + reactor.config.tcpPort + "/r/" + code);
        return l;
    }

    int onlineCount() {
        int n = 0;
        for (Session s : sessions.values()) if (s.online() && !s.hidden) n++;
        return n;
    }

    int roomCount() { return rooms.size(); }

    Map<String, Object> gameSummary() {
        int playing = 0;
        for (Room r : rooms.values()) if (r.phase == Room.Phase.PLAYING) playing++;
        List<Object> list = new ArrayList<>();
        for (Room r : rooms.values()) { if (list.size() >= 25) break; list.add(r.summary()); }
        return Json.obj("online", onlineCount(), "sessions", sessions.size(), "rooms", rooms.size(), "playing", playing,
                "queued", queues.get(Board.Mode.CLASSIC).size() + queues.get(Board.Mode.ULTIMATE).size(),
                "started", reactor.metrics.gamesStarted.sum(), "finished", reactor.metrics.gamesFinished.sum(),
                "moves", reactor.metrics.movesPlayed.sum(), "roomList", list);
    }

    void markDirty() { dirty = true; }

    void saveLeaderboardAsync() {
        reactor.submit(() -> { leaderboard.save(); return null; }, null,
                err -> System.err.println("[leaderboard] save failed: " + err));
    }

    /** Once per second: refresh lobby screens (if something changed) and push stats to dashboards. */
    void tick() {
        Map<String, Object> stats = reactor.metrics.tick(reactor);
        boolean anyAdmin = false;
        for (Connection c : new ArrayList<>(reactor.connections.values())) {
            if (!c.admin || c.link == null) continue;
            anyAdmin = true;
            c.sendSecure(new LinkedHashMap<>(stats));
            List<Object> frames = reactor.metrics.captureSince(c.lastCaptureId, 150);
            if (!frames.isEmpty()) {
                c.lastCaptureId = (Long) ((Map<?, ?>) frames.get(frames.size() - 1)).get("id");
                c.sendSecure(Json.obj("t", "capture", "items", frames));
            }
        }
        reactor.metrics.captureOn = anyAdmin;
        if (!dirty) return;
        dirty = false;
        for (Session s : sessions.values()) {
            if (s.online() && s.room == null && s.conn != null && !s.hidden) sendLobby(s);
        }
    }
}
