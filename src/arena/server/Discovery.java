package arena.server;

import arena.common.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;

/**
 * UDP LAN discovery, served by a NON-BLOCKING DatagramChannel registered on the same Selector
 * as all the TCP sockets.
 *
 * A client broadcasts the datagram "NEONGRID_DISCOVER v1" to port 8888. The server answers the
 * sender (connectionless, fire-and-forget) with a small JSON "beacon" describing how to connect.
 * Lost datagrams are harmless: the client simply asks again.
 */
final class Discovery {

    static final String QUERY = "NEONGRID_DISCOVER";

    private final Reactor reactor;
    private final ByteBuffer buf = ByteBuffer.allocate(1500); // one Ethernet MTU is plenty

    Discovery(Reactor reactor) {
        this.reactor = reactor;
    }

    void onReadable(DatagramChannel ch) {
        try {
            for (int i = 0; i < 32; i++) {
                buf.clear();
                SocketAddress from = ch.receive(buf);   // non-blocking: null when no datagram waiting
                if (from == null) return;
                buf.flip();
                reactor.metrics.udpIn.increment();
                String msg = StandardCharsets.UTF_8.decode(buf).toString().trim();
                if (!msg.startsWith(QUERY)) continue;    // ignore unknown datagrams

                String reply = Json.stringify(beacon());
                ch.send(ByteBuffer.wrap(reply.getBytes(StandardCharsets.UTF_8)), from);
                reactor.metrics.udpOut.increment();
                String who = from instanceof InetSocketAddress
                        ? ((InetSocketAddress) from).getAddress().getHostAddress() + ":" + ((InetSocketAddress) from).getPort()
                        : from.toString();
                reactor.metrics.discovery(who, reply);
            }
        } catch (IOException e) {
            System.err.println("[udp] " + e.getMessage());
        }
    }

    private java.util.Map<String, Object> beacon() {
        return Json.obj(
                "t", "beacon",
                "name", reactor.config.serverName,
                "version", ArenaServer.VERSION,
                "tcpPort", reactor.config.tcpPort,
                "lan", NetUtil.lanAddresses(),
                "host", NetUtil.hostName(),
                "players", reactor.lobby.onlineCount(),
                "rooms", reactor.lobby.roomCount(),
                "security", "DH-2048 key agreement + HMAC-SHA256 + anti-replay seq");
    }
}
