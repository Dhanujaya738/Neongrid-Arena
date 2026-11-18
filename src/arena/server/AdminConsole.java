package arena.server;

import arena.common.Crypto;
import arena.common.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Network Control Room back-end (Members E and B).
 *
 *  - Login by challenge-response: the browser sends HMAC(SHA-256(password), nonce). The password
 *    never travels, a captured proof is useless on any other connection, and 5 failures disconnect.
 *  - Authorization: stats, packet capture, DNS and URL tools are refused (FORBIDDEN) without login.
 *  - Once per second, logged-in admins receive the stats snapshot and new packet-capture frames.
 */
final class AdminConsole {

    static final int MAX_FAILURES = 5;

    private final Reactor reactor;

    AdminConsole(Reactor reactor) {
        this.reactor = reactor;
    }

    /** Handles admin_login / stats / dns / url. Returns false if the message type is not an admin message. */
    boolean handle(Connection c, String t, Map<String, Object> m) {
        switch (t) {
            case "admin_login":
                login(c, Json.str(m, "proof", ""));
                return true;
            case "stats":
            case "dns":
            case "url":
                if (!c.admin) {
                    reactor.metrics.securityEvent("FORBIDDEN", c.label(), "'" + t + "' requires the control-room password");
                    c.sendSecure(Json.obj("t", "rejected", "reason", "FORBIDDEN", "detail", "admin login required"));
                } else {
                    tool(c, t, m);
                }
                return true;
            default:
                return false;
        }
    }

    private void login(Connection c, String proof) {
        byte[] pwHash = Crypto.sha256(reactor.config.adminPassword.getBytes(StandardCharsets.UTF_8));
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
            reactor.metrics.securityEvent("ADMIN_LOGIN_FAIL", c.label(), "wrong password (attempt " + c.adminFails + "/" + MAX_FAILURES + ")");
            c.sendSecure(Json.obj("t", "admin_fail", "remaining", Math.max(0, MAX_FAILURES - c.adminFails)));
            if (c.adminFails >= MAX_FAILURES) reactor.schedule(300, () -> c.close("too many control-room login failures"));
        }
    }

    private void tool(Connection c, String t, Map<String, Object> m) {
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

    /** Called once per second: push the stats snapshot and new captured frames to every logged-in admin. */
    void pushLiveData(Map<String, Object> stats) {
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
        reactor.metrics.captureOn = anyAdmin; // packet capture costs nothing when nobody is watching
    }
}
