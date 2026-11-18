package arena.server;

import arena.common.Crypto;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Hand-written RFC 6455 WebSocket codec working directly on NIO ByteBuffers.
 * (No libraries: the upgrade handshake, frame header parsing, masking and
 *  length encodings are all done here.)
 */
final class WebSocket {

    static final int OP_CONT = 0x0, OP_TEXT = 0x1, OP_BINARY = 0x2, OP_CLOSE = 0x8, OP_PING = 0x9, OP_PONG = 0xA;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocket() {}

    static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /** Sec-WebSocket-Accept = Base64( SHA-1( key + GUID ) ) */
    static String acceptKey(String clientKey) {
        byte[] sha1 = Crypto.sha1((clientKey.trim() + GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(sha1);
    }

    /**
     * Tries to decode one complete client frame starting at in.position().
     * Returns null (and leaves the position untouched) if the frame has not fully arrived yet -
     * the caller then compact()s the buffer and waits for more bytes.
     */
    static Frame decode(ByteBuffer in, int maxPayload) throws ProtocolException {
        int start = in.position();
        int avail = in.remaining();
        if (avail < 2) return null;
        int b0 = in.get(start) & 0xFF;
        int b1 = in.get(start + 1) & 0xFF;
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        int header = 2;
        if (len == 126) {
            if (avail < 4) return null;
            len = in.getShort(start + 2) & 0xFFFF;
            header = 4;
        } else if (len == 127) {
            if (avail < 10) return null;
            len = in.getLong(start + 2);
            header = 10;
        }
        if (len < 0 || len > maxPayload) throw new ProtocolException("WebSocket frame too large: " + len);
        if (!masked) throw new ProtocolException("Client frames must be masked (RFC 6455 5.1)");
        int total = header + 4 + (int) len;
        if (avail < total) return null;

        int maskAt = start + header;
        byte m0 = in.get(maskAt), m1 = in.get(maskAt + 1), m2 = in.get(maskAt + 2), m3 = in.get(maskAt + 3);
        byte[] payload = new byte[(int) len];
        int dataAt = maskAt + 4;
        for (int i = 0; i < payload.length; i++) {
            byte m;
            switch (i & 3) { case 0: m = m0; break; case 1: m = m1; break; case 2: m = m2; break; default: m = m3; }
            payload[i] = (byte) (in.get(dataAt + i) ^ m);
        }
        in.position(start + total);
        return new Frame(fin, opcode, payload);
    }

    /** Encodes an unmasked server-to-client frame. */
    static ByteBuffer encode(int opcode, byte[] payload) {
        int len = payload.length;
        int header = len < 126 ? 2 : len <= 0xFFFF ? 4 : 10;
        ByteBuffer b = ByteBuffer.allocate(header + len);
        b.put((byte) (0x80 | opcode));
        if (len < 126) {
            b.put((byte) len);
        } else if (len <= 0xFFFF) {
            b.put((byte) 126);
            b.putShort((short) len);
        } else {
            b.put((byte) 127);
            b.putLong(len);
        }
        b.put(payload);
        b.flip();
        return b;
    }
}
