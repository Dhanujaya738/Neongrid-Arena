package arena.common;

import java.util.Map;

/**
 * The "secure envelope" used on every frame after the handshake (both directions):
 *
 *     frame = HMAC_SHA256(sessionKey, json) as 64 hex chars  +  json
 *
 * and every json carries a strictly increasing "seq" number.
 *   - wrong MAC        -> the frame was TAMPERED with (or forged by someone without the key)
 *   - seq not greater  -> the frame is a REPLAY of a previously accepted message
 */
public final class SecureLink {

    public static final int MAC_HEX_LEN = 64;

    private final byte[] key;
    private long outSeq = 0;
    private long inSeq = 0;

    public SecureLink(byte[] key) {
        this.key = key;
    }

    public byte[] key() { return key; }

    public long lastInSeq() { return inSeq; }

    public long nextOutSeq() { return outSeq + 1; }

    /** Adds the sequence number, serialises and signs the message. */
    public String seal(Map<String, Object> msg) {
        msg.put("seq", ++outSeq);
        String body = Json.stringify(msg);
        return Crypto.hmacHex(key, body) + body;
    }

    /** Verifies MAC and sequence number. Throws {@link Rejected} with the reason on failure. */
    public Map<String, Object> open(String frame) throws Rejected {
        if (frame.length() <= MAC_HEX_LEN) throw new Rejected("MALFORMED", "frame too short to carry a MAC");
        String mac = frame.substring(0, MAC_HEX_LEN);
        String body = frame.substring(MAC_HEX_LEN);
        String expected = Crypto.hmacHex(key, body);
        if (!Crypto.safeEquals(mac, expected)) {
            throw new Rejected("TAMPER", "HMAC mismatch - message altered or forged");
        }
        Map<String, Object> msg;
        try {
            msg = Json.parseObject(body);
        } catch (IllegalArgumentException e) {
            throw new Rejected("MALFORMED", "invalid JSON: " + e.getMessage());
        }
        long seq = Json.num(msg, "seq", -1);
        if (seq <= inSeq) {
            throw new Rejected("REPLAY", "seq " + seq + " already used (last accepted " + inSeq + ")");
        }
        inSeq = seq;
        return msg;
    }

    /** Thrown when an incoming frame fails a security check. */
    public static final class Rejected extends Exception {
        public final String kind;

        public Rejected(String kind, String detail) {
            super(detail);
            this.kind = kind;
        }
    }
}
