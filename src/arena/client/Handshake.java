package arena.client;

import arena.common.Crypto;
import arena.common.Json;
import arena.common.SecureLink;

import java.math.BigInteger;
import java.util.Map;

/** Client side of the Diffie-Hellman + HMAC handshake (shared by the console client and bot swarm). */
final class Handshake {

    final BigInteger priv = Crypto.newPrivateKey();
    final BigInteger pub = Crypto.publicKey(priv);

    String helloJson(String name, String clientKind) {
        return Json.stringify(Json.obj("t", "hello", "name", name, "client", clientKind, "dh", pub.toString(16)));
    }

    /** Consumes the plain "welcome" frame and returns the secure link for everything after it. */
    SecureLink complete(String welcomeJson) {
        Map<String, Object> w = Json.parseObject(welcomeJson);
        if (!"welcome".equals(w.get("t"))) throw new IllegalStateException("expected welcome, got " + welcomeJson);
        byte[] key = Crypto.deriveSessionKey(new BigInteger(Json.str(w, "dh", ""), 16), priv);
        return new SecureLink(key);
    }
}
