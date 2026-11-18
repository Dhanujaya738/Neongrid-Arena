package arena.server;

import arena.common.Crypto;
import arena.common.Json;
import arena.game.Board;

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
 * Sessions, rooms, matchmaking and message routing.
 * The secure handshake lives in {@link SecureHandshake}; the Control Room in {@link AdminConsole}.
 * Everything here runs on the selector thread only (thread confinement => no locks needed).
 */
final class Lobby {

    final Reactor reactor;
    final Leaderboard leaderboard;
    final SecureHandshake handshake;
    final AdminConsole admin;

    final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<Board.Mode, ArrayDeque<Session>> queues = new EnumMap<>(Board.Mode.class);
    private boolean dirty = true;

    Lobby(Reactor reactor, Leaderboard leaderboard) {
        this.reactor = reactor;
        this.leaderboard = leaderboard;
        for (Board.Mode m : Board.Mode.values()) queues.put(m, new ArrayDeque<>());
        this.handshake = new SecureHandshake(this);
        this.admin = new AdminConsole(reactor);
    }

    /** First frame of a new connection (plain JSON) → secure handshake. */
    void onHello(Connection c, String text) {
        handshake.onHello(c, text);
    }

    // ===================================================================== message routing

    /** Every verified (HMAC + seq checked) message from a client arrives here. */
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
            case "ai":
                playBot(s, Board.Mode.parse(Json.str(m, "mode", "ultimate")), Json.str(m, "level", "medium"));
                break;
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
            default:
                if (admin.handle(c, t, m)) break;  // admin_login, stats, dns, url
                reactor.metrics.securityEvent("UNKNOWN_TYPE", c.label(), "message type '" + t + "'");
                c.sendSecure(Json.obj("t", "rejected", "reason", "UNKNOWN_TYPE", "detail", "unknown message type " + t));
        }
    }

    // ===================================================================== rooms & matchmaking

    private void playBot(Session s, Board.Mode mode, String level) {
        leaveCurrent(s);
        if (!level.equals("easy") && !level.equals("medium") && !level.equals("hard")) level = "medium";
        Room r = newRoom(mode, true);
        Session bot = new Session("NeonBot · " + Character.toUpperCase(level.charAt(0)) + level.substring(1), level);
        boolean humanFirst = ThreadLocalRandom.current().nextBoolean();
        r.seat(s, humanFirst ? 1 : 2);
        r.seat(bot, humanFirst ? 2 : 1);
        r.start();
    }

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

    void sendLobby(Session s) {
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

    /** Once per second: push live data to the Control Room and refresh lobby screens if something changed. */
    void tick() {
        admin.pushLiveData(reactor.metrics.tick(reactor));
        if (!dirty) return;
        dirty = false;
        for (Session s : sessions.values()) {
            if (s.online() && s.room == null && s.conn != null && !s.hidden) sendLobby(s);
        }
    }
}
