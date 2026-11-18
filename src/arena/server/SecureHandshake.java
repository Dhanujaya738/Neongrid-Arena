package arena.server;

import arena.common.Crypto;
import arena.common.Json;
import arena.common.SecureLink;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;

/**
 * Server side of the secure handshake (Member E).
 *
 *   1. client  → {t:"hello", name, dh: g^a mod p, resume?}     (plain JSON)
 *   2. worker thread computes b, g^b mod p and K = SHA-256(hex(g^ab mod p))
 *   3. server  → {t:"welcome", dh: g^b mod p, sid, tokenWrapped}  (plain JSON)
 *   4. from now on every frame is HMAC-signed with K and carries a sequence number (SecureLink)
 *
 * A returning player proves ownership of an existing session with HMAC(token, newDhPublic),
 * so the session token itself never travels in the clear.
 * Runs on the selector thread, except the modular exponentiation (worker pool).
 */
final class SecureHandshake {

    private final Lobby lobby;
    private final Reactor reactor;

    SecureHandshake(Lobby lobby) {
        this.lobby = lobby;
        this.reactor = lobby.reactor;
    }

    /** Step 1: validate the hello message, then compute our DH values on a worker thread. */
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
            byte[] key = Crypto.deriveSessionKey(peer, priv);   // validates the peer value too
            reactor.metrics.handshakes.increment();
            return new Object[]{pub, key};
        }, res -> finish(c, name, kind, dh, resume, (BigInteger) res[0], (byte[]) res[1]), err -> {
            reactor.metrics.securityEvent("HANDSHAKE", c.label(), err.getMessage());
            c.close("handshake failed");
        });
    }

    /** Steps 3–4 (back on the selector thread): resume or create the session, send welcome + profile. */
    private void finish(Connection c, String name, String kind, String clientDh, Map<String, Object> resume,
                        BigInteger serverPub, byte[] key) {
        if (c.closed) return;
        Session s = null;
        boolean resumed = false;
        if (resume != null) {
            Session old = lobby.sessions.get(Json.str(resume, "sid", ""));
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
            lobby.sessions.put(s.sid, s);
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
                "elo", lobby.leaderboard.elo(s.name), "resumed", resumed, "nonce", c.adminNonce,
                "server", Json.obj("name", reactor.config.serverName, "version", ArenaServer.VERSION,
                        "host", NetUtil.hostName(), "lan", NetUtil.lanAddresses(),
                        "tcpPort", reactor.config.tcpPort, "udpPort", reactor.config.udpPort,
                        "connId", c.id, "you", c.remote.getAddress().getHostAddress() + ":" + c.remote.getPort()),
                "crypto", Json.obj("kex", "Diffie-Hellman RFC 3526 group 14 (2048-bit)", "kdf", "SHA-256",
                        "mac", "HMAC-SHA256", "replay", "strictly increasing sequence numbers")));
        System.out.println("[session] " + (resumed ? "resumed " : "new ") + s.name + " via " + c.clientKind + " " + c.label());
        if (resumed && s.room != null && !s.room.closed) s.room.onReconnect(s);
        else { s.room = null; lobby.sendLobby(s); }
        lobby.markDirty();
    }

    /** Input validation: letters, digits, space, _ . - only; at most 16 characters. */
    private static String cleanName(String raw) {
        String n = raw.replaceAll("[^\\p{L}\\p{N} _.\\-]", "").trim();
        if (n.length() > 16) n = n.substring(0, 16).trim();
        if (n.isEmpty()) n = "Player-" + Crypto.randomCode(3);
        return n;
    }

    private String uniqueName(String n) {
        for (Session s : lobby.sessions.values()) {
            if (s.online() && s.name.equalsIgnoreCase(n)) return n + "-" + Crypto.randomCode(2);
        }
        return n;
    }
}
