package arena.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Security primitives shared by the server and the Java clients.
 *
 *  - Diffie-Hellman key agreement (RFC 3526 2048-bit MODP group 14) so both sides derive the same
 *    secret session key WITHOUT ever sending it over the network (defeats eavesdropping of the key).
 *  - HMAC-SHA256 message authentication (data integrity + authenticity, detects tampering / MITM edits).
 *  - SHA-256 / SHA-1 hashing (SHA-1 is only used for the RFC 6455 WebSocket handshake).
 */
public final class Crypto {

    private Crypto() {}

    /** RFC 3526, 2048-bit MODP Group (id 14). Same constant is used in web/js/net.js */
    public static final String P_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
          + "29024E088A67CC74020BBEA63B139B22514A08798E3404DD"
          + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"
          + "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
          + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D"
          + "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
          + "83655D23DCA3AD961C62F356208552BB9ED529077096966D"
          + "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
          + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9"
          + "DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
          + "15728E5A8AACAA68FFFFFFFFFFFFFFFF";

    public static final BigInteger P = new BigInteger(P_HEX, 16);
    public static final BigInteger G = BigInteger.TWO;

    private static final SecureRandom RNG = new SecureRandom();

    /** A 256-bit private exponent (plenty of security for group 14 and fast in the browser). */
    public static BigInteger newPrivateKey() {
        return new BigInteger(256, RNG).setBit(255);
    }

    public static BigInteger publicKey(BigInteger priv) {
        return G.modPow(priv, P);
    }

    /** Validates the peer value and derives the 32-byte session key = SHA-256(hex(peer^priv mod p)). */
    public static byte[] deriveSessionKey(BigInteger peerPublic, BigInteger priv) {
        if (peerPublic.compareTo(BigInteger.ONE) <= 0 || peerPublic.compareTo(P.subtract(BigInteger.ONE)) >= 0) {
            throw new IllegalArgumentException("Invalid Diffie-Hellman public value");
        }
        BigInteger shared = peerPublic.modPow(priv, P);
        return sha256(shared.toString(16).getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    public static byte[] sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    private static byte[] digest(String alg, byte[] data) {
        try {
            return MessageDigest.getInstance(alg).digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacHex(byte[] key, String data) {
        return hex(hmac(key, data.getBytes(StandardCharsets.UTF_8)));
    }

    /** Constant-time comparison so attackers cannot learn the MAC byte-by-byte from timing. */
    public static boolean safeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static String randomCode(int len) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I confusion
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(RNG.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** Key used to wrap (XOR-encrypt) the resume token so it never travels in the clear. */
    public static byte[] tokenWrapKey(byte[] sessionKey) {
        byte[] label = "token".getBytes(StandardCharsets.US_ASCII);
        byte[] in = new byte[sessionKey.length + label.length];
        System.arraycopy(sessionKey, 0, in, 0, sessionKey.length);
        System.arraycopy(label, 0, in, sessionKey.length, label.length);
        return sha256(in);
    }

    public static byte[] xor(byte[] a, byte[] b) {
        byte[] r = new byte[Math.min(a.length, b.length)];
        for (int i = 0; i < r.length; i++) r[i] = (byte) (a[i] ^ b[i]);
        return r;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = HEX[(b[i] >> 4) & 0xF];
            c[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        return new String(c);
    }

    public static byte[] unhex(String s) {
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16), lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Bad hex");
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }
}
