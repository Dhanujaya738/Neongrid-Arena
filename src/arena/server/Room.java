package arena.server;

import arena.common.Json;
import arena.game.AiPlayer;
import arena.game.Board;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One game room: two seats (X = 1, O = 2), any number of spectators, chat, turn clock and rematch.
 * The board is SERVER-AUTHORITATIVE: every move is validated here, then the new state is pushed
 * (synchronised) to every player and spectator.
 */
final class Room {

    enum Phase { WAITING, PLAYING, FINISHED }

    private static final Set<String> EMOJI = new java.util.HashSet<>(java.util.Arrays.asList(
            "🔥", "😂", "😮", "👏", "😡", "🤯", "🎉", "💀", "🧠", "👀"));

    final Lobby lobby;
    final Reactor reactor;
    final String code;
    final Board.Mode mode;
    final boolean isPrivate;
    final long createdAt = System.currentTimeMillis();

    final Session[] seat = new Session[3];
    final Set<Session> spectators = new LinkedHashSet<>();
    Board board;
    Phase phase = Phase.WAITING;
    int gameNo;
    String endReason;
    int[] ratingDelta;
    boolean closed;

    private final boolean[] rematch = new boolean[3];
    private final List<Object> chat = new ArrayList<>();
    private final List<Object> history = new ArrayList<>();
    private final Map<Session, Reactor.Timer> grace = new HashMap<>();
    private Reactor.Timer turnTimer;
    private long turnDeadline;
    private Future<?> aiFuture;
    private long turnStartedAt;

    Room(Lobby lobby, String code, Board.Mode mode, boolean isPrivate) {
        this.lobby = lobby;
        this.reactor = lobby.reactor;
        this.code = code;
        this.mode = mode;
        this.isPrivate = isPrivate;
        this.board = new Board(mode);
    }

    long turnLimitMs() { return mode == Board.Mode.CLASSIC ? 20_000 : 45_000; }

    int sideOf(Session s) {
        if (s == null) return 0;
        if (seat[1] == s) return 1;
        if (seat[2] == s) return 2;
        return 0;
    }

    /** A seat counts as present while its owner is still in this room (bots always are). */
    private boolean present(int side) {
        Session s = seat[side];
        return s != null && (s.isBot() || s.room == this);
    }

    private boolean anyHumanPresent() {
        for (int i = 1; i <= 2; i++) if (seat[i] != null && !seat[i].isBot() && seat[i].room == this && seat[i].online()) return true;
        return false;
    }

    // ================================================================ joining

    void seat(Session s, int side) {
        seat[side] = s;
        if (!s.isBot()) s.room = this;
    }

    void join(Session s) {
        if (sideOf(s) > 0) { s.room = this; sendFull(s); broadcast(); return; }
        if (phase == Phase.WAITING && seat[2] == null) {
            seat(s, 2);
            sendFull(s);
            start();
        } else {
            spectate(s);
        }
    }

    void spectate(Session s) {
        spectators.add(s);
        s.room = this;
        sendFull(s);
        system("👀 " + s.name + " is spectating");
        broadcast();
    }

    // ================================================================ game flow

    void start() {
        board = new Board(mode);
        phase = Phase.PLAYING;
        gameNo++;
        endReason = null;
        ratingDelta = null;
        rematch[1] = rematch[2] = false;
        history.clear();
        reactor.metrics.gamesStarted.increment();
        system("⚔️ Game " + gameNo + " — " + seat[1].name + " (X) vs " + seat[2].name + " (O). X starts!");
        startTurnClock();
        broadcast();
        lobby.markDirty();
        scheduleBot();
    }

    void move(Session s, int cell) {
        int side = sideOf(s);
        String problem = null, code = null;
        if (phase != Phase.PLAYING) { code = "NOT_PLAYING"; problem = "No game in progress"; }
        else if (side == 0) { code = "SPECTATOR"; problem = "Spectators cannot move"; }
        else if (board.turn() != side) { code = "NOT_YOUR_TURN"; problem = "It is not your turn"; }
        else if ((problem = board.whyIllegal(cell)) != null) code = "ILLEGAL_MOVE";
        if (code != null) {
            // Server-side validation: a modified client cannot cheat.
            reactor.metrics.securityEvent("INVALID_MOVE", s.name, code + ": " + problem + " (cell " + cell + ")");
            s.send(Json.obj("t", "error", "code", code, "msg", problem));
            return;
        }
        apply(side, cell, null);
    }

    private void apply(int side, int cell, String note) {
        board.play(cell);
        reactor.metrics.movesPlayed.increment();
        history.add(Json.obj("n", board.moveCount(), "side", side, "cell", cell,
                "ms", System.currentTimeMillis() - turnStartedAt, "note", note));
        if (board.over()) {
            int w = board.winner();
            finish(w == Board.DRAW ? "draw" : "line");
        } else {
            startTurnClock();
            broadcast();
            scheduleBot();
        }
    }

    /** Runs the AI on the worker pool. The selector thread keeps serving everyone else meanwhile. */
    private void scheduleBot() {
        if (phase != Phase.PLAYING) return;
        final int side = board.turn();
        final Session bot = seat[side];
        if (bot == null || !bot.isBot()) return;
        final int game = gameNo, at = board.moveCount();
        reactor.metrics.aiJobs.increment();
        broadcastMsg(Json.obj("t", "thinking", "side", side));
        aiFuture = reactor.submit(new AiPlayer(board, bot.botLevel), res -> {
            if (closed || phase != Phase.PLAYING || gameNo != game || board.moveCount() != at) return;
            String note = String.format("%s · %,d %s · %d ms · %s", res.algorithm, res.iterations,
                    res.algorithm.startsWith("Monte") ? "playouts" : "nodes", res.millis, res.thread);
            broadcastMsg(Json.obj("t", "ai_info", "text", note));
            reactor.metrics.aiRun(Json.obj("ts", System.currentTimeMillis(), "room", code, "mode", mode.id(),
                    "level", bot.botLevel, "algorithm", res.algorithm, "iterations", res.iterations,
                    "millis", res.millis, "thread", res.thread, "move", res.move));
            apply(side, res.move, note);
        }, err -> {
            if (closed || phase != Phase.PLAYING || gameNo != game || board.moveCount() != at) return;
            int[] moves = new int[81];
            int n = board.legalMoves(moves);
            apply(side, moves[ThreadLocalRandom.current().nextInt(n)], "fallback");
        });
    }

    private void startTurnClock() {
        if (turnTimer != null) turnTimer.cancel();
        turnStartedAt = System.currentTimeMillis();
        Session next = seat[board.turn()];
        if (next != null && next.isBot()) { turnDeadline = 0; return; }
        long limit = turnLimitMs();
        turnDeadline = turnStartedAt + limit;
        final int game = gameNo, at = board.moveCount();
        turnTimer = reactor.schedule(limit, () -> {
            if (phase == Phase.PLAYING && gameNo == game && board.moveCount() == at) {
                int loser = board.turn();
                system("⏱️ " + seat[loser].name + " ran out of time!");
                board.forfeit(loser);
                finish("timeout");
            }
        });
    }

    private void finish(String reason) {
        phase = Phase.FINISHED;
        endReason = reason;
        if (turnTimer != null) turnTimer.cancel();
        if (aiFuture != null) aiFuture.cancel(true);
        turnDeadline = 0;
        reactor.metrics.gamesFinished.increment();
        int w = board.winner();
        if (seat[1] != null && seat[2] != null && !seat[1].isBot() && !seat[2].isBot() && seat[1].ranked && seat[2].ranked) {
            ratingDelta = lobby.leaderboard.record(seat[1].name, seat[2].name, w);
            lobby.saveLeaderboardAsync();
        }
        if (w == Board.DRAW) system("🤝 It's a draw!");
        else if (w == 1 || w == 2) system("🏆 " + seat[w].name + " wins" + ("line".equals(reason) ? "!" : " (" + reason + ")"));
        broadcast();
        lobby.markDirty();
    }

    void rematch(Session s) {
        int side = sideOf(s);
        if (phase != Phase.FINISHED || side == 0) return;
        int other = 3 - side;
        if (!present(other)) {
            s.send(Json.obj("t", "error", "code", "OPPONENT_LEFT", "msg", "Your opponent has left the room"));
            return;
        }
        rematch[side] = true;
        if (seat[other].isBot()) rematch[other] = true;
        if (rematch[1] && rematch[2]) {
            Session t = seat[1];            // swap sides so the other player starts
            seat[1] = seat[2];
            seat[2] = t;
            start();
        } else {
            system("🔁 " + s.name + " wants a rematch");
            broadcast();
        }
    }

    // ================================================================ leaving / connectivity

    void leave(Session s) {
        if (spectators.remove(s)) {
            s.room = null;
            broadcast();
            return;
        }
        int side = sideOf(s);
        if (side == 0) return;
        // Remove the leaver first, so the final broadcast doesn't pull them back into the game screen.
        s.room = null;
        cancelGrace(s);
        boolean resigned = phase == Phase.PLAYING;
        if (resigned) {
            system("🏳️ " + s.name + " left the game");
            board.forfeit(side);
            finish("resign");
        }
        if (phase == Phase.WAITING || !anyHumanPresent()) close();
        else if (!resigned) { system("👋 " + s.name + " left the room"); broadcast(); }
    }

    void onDisconnect(Session s) {
        if (spectators.remove(s)) { s.room = null; broadcast(); return; }
        if (sideOf(s) == 0) return;
        system("📡 " + s.name + " disconnected — 30 s to reconnect…");
        broadcast();
        final int game = gameNo;
        grace.put(s, reactor.schedule(30_000, () -> {
            grace.remove(s);
            if (s.online() || closed) return;
            if (phase == Phase.PLAYING && gameNo == game) {
                board.forfeit(sideOf(s));
                finish("disconnect");
            }
            s.room = null;
            if (!anyHumanPresent()) close();
            else broadcast();
        }));
    }

    void onReconnect(Session s) {
        cancelGrace(s);
        sendFull(s);
        if (sideOf(s) > 0) system("⚡ " + s.name + " reconnected");
        broadcast();
    }

    private void cancelGrace(Session s) {
        Reactor.Timer t = grace.remove(s);
        if (t != null) t.cancel();
    }

    void close() {
        if (closed) return;
        closed = true;
        if (turnTimer != null) turnTimer.cancel();
        if (aiFuture != null) aiFuture.cancel(true);
        for (Reactor.Timer t : grace.values()) t.cancel();
        Map<String, Object> msg = Json.obj("t", "room_closed", "code", code);
        for (int i = 1; i <= 2; i++) {
            if (seat[i] != null && seat[i].room == this) { seat[i].room = null; seat[i].send(new java.util.LinkedHashMap<>(msg)); }
        }
        for (Session sp : spectators) { sp.room = null; sp.send(new java.util.LinkedHashMap<>(msg)); }
        spectators.clear();
        lobby.roomClosed(this);
    }

    // ================================================================ social

    void chat(Session s, String text) {
        text = text.replaceAll("\\p{Cntrl}", "").trim();
        if (text.isEmpty()) return;
        if (text.length() > 200) text = text.substring(0, 200);
        Map<String, Object> c = Json.obj("from", s.name, "hue", s.hue, "side", sideOf(s), "text", text, "ts", System.currentTimeMillis());
        remember(c);
        broadcastMsg(Json.obj("t", "chat", "item", c));
    }

    void react(Session s, String emoji) {
        if (!EMOJI.contains(emoji)) return;
        broadcastMsg(Json.obj("t", "react", "from", s.name, "side", sideOf(s), "emoji", emoji));
    }

    void system(String text) {
        Map<String, Object> c = Json.obj("from", "ARENA", "sys", true, "text", text, "ts", System.currentTimeMillis());
        remember(c);
        broadcastMsg(Json.obj("t", "chat", "item", c));
    }

    private void remember(Map<String, Object> c) {
        chat.add(c);
        while (chat.size() > 50) chat.remove(0);
    }

    // ================================================================ state sync

    private Map<String, Object> player(int side) {
        Session s = seat[side];
        if (s == null) return null;
        return Json.obj("name", s.name, "hue", s.hue, "bot", s.isBot(), "level", s.botLevel,
                "online", s.isBot() || (s.room == this && s.online()), "elo", s.isBot() ? null : lobby.leaderboard.elo(s.name),
                "rematch", rematch[side]);
    }

    Map<String, Object> state(Session viewer) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("t", "room");
        m.put("code", code);
        m.put("mode", mode.id());
        m.put("private", isPrivate);
        m.put("phase", phase.name());
        m.put("gameNo", gameNo);
        m.put("players", new Object[]{null, player(1), player(2)});
        m.put("you", sideOf(viewer));
        m.put("spectators", spectators.size());
        m.put("cells", board.cellsArray());
        m.put("macro", board.macroArray());
        m.put("active", board.active());
        m.put("turn", board.turn());
        m.put("winner", board.winner());
        m.put("winLine", board.winLine());
        m.put("lastMove", board.lastMove());
        m.put("moveCount", board.moveCount());
        m.put("deadlineIn", turnDeadline > 0 ? Math.max(0, turnDeadline - System.currentTimeMillis()) : null);
        m.put("turnLimit", turnLimitMs());
        m.put("endReason", endReason);
        m.put("ratingDelta", ratingDelta);
        m.put("history", history);
        m.put("invite", lobby.inviteLinks(code));
        return m;
    }

    private void sendFull(Session s) {
        s.send(state(s));
        s.send(Json.obj("t", "chat_history", "items", new ArrayList<>(chat)));
    }

    void broadcast() {
        for (Session s : audience()) s.send(state(s));
    }

    private void broadcastMsg(Map<String, Object> msg) {
        for (Session s : audience()) s.send(new java.util.LinkedHashMap<>(msg));
    }

    private List<Session> audience() {
        List<Session> l = new ArrayList<>();
        for (int i = 1; i <= 2; i++) if (seat[i] != null && !seat[i].isBot() && seat[i].room == this) l.add(seat[i]);
        l.addAll(spectators);
        return l;
    }

    Map<String, Object> summary() {
        return Json.obj("code", code, "mode", mode.id(), "phase", phase.name(), "private", isPrivate,
                "x", seat[1] != null ? seat[1].name : null, "o", seat[2] != null ? seat[2].name : null,
                "moves", board.moveCount(), "spectators", spectators.size(),
                "vsBot", (seat[1] != null && seat[1].isBot()) || (seat[2] != null && seat[2].isBot()));
    }
}
