package arena.server;

import arena.common.Crypto;
import arena.game.Board;

/**
 * A logged-in player. Survives short disconnects: the browser can reconnect and prove it owns
 * the session with HMAC(token, newDhPublicKey) - the token itself never crosses the wire in clear.
 */
final class Session {
    final String sid = Crypto.hex(Crypto.randomBytes(8));
    final byte[] token = Crypto.randomBytes(32);
    final String name;
    final int hue;

    Connection conn;
    Room room;
    Board.Mode queuedFor;           // non-null while waiting in quick-match
    long disconnectedAt;
    Reactor.Timer expiryTimer;

    /** Non-null for the AI opponent (it has no network connection at all). */
    final String botLevel;

    Session(String name) {
        this(name, null);
    }

    Session(String name, String botLevel) {
        this.name = name;
        this.botLevel = botLevel;
        this.hue = botLevel != null ? 48 : Math.floorMod(name.hashCode() * 47, 360);
    }

    /** Load-test bots (BotSwarm) play normally but never touch the Elo leaderboard. */
    boolean ranked = true;

    /** Control-room and attack-lab connections: not shown as players, not counted online. */
    boolean hidden;

    boolean isBot() { return botLevel != null; }

    boolean online() { return isBot() || (conn != null && !conn.closed); }

    void send(java.util.Map<String, Object> msg) {
        if (!isBot() && conn != null && !conn.closed) conn.sendSecure(msg);
    }
}
