package arena.server;

import java.net.BindException;

/**
 * NeonGrid Arena server entry point.
 *
 *   java -cp build/classes arena.server.ArenaServer [--port 8080] [--udp 8888] [--workers 4] [--web web]
 */
public final class ArenaServer {

    public static final String VERSION = "1.0.0";

    public static void main(String[] args) throws Exception {
        ServerConfig cfg = ServerConfig.fromArgs(args);
        Metrics metrics = new Metrics();
        Reactor reactor;
        try {
            reactor = new Reactor(cfg, metrics);
        } catch (BindException e) {
            System.err.println("Port " + cfg.tcpPort + " (TCP) or " + cfg.udpPort + " (UDP) is already in use. "
                    + "Stop the other server or start with --port <n> --udp <n>.");
            System.exit(1);
            return;
        }
        Leaderboard leaderboard = new Leaderboard(cfg.dataDir);
        reactor.lobby = new Lobby(reactor, leaderboard);
        reactor.http = new Http(reactor, cfg.webRoot);
        reactor.discovery = new Discovery(reactor);
        reactor.start();
        reactor.execute(() -> reactor.every(1000, reactor.lobby::tick));

        banner(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[server] shutting down...");
            try { leaderboard.save(); } catch (Exception ignored) { }
            reactor.shutdown();
        }));
    }

    private static void banner(ServerConfig cfg) {
        String c = "\u001B[36m", m = "\u001B[35m", r = "\u001B[0m", b = "\u001B[1m";
        System.out.println(c + b);
        System.out.println("  ███╗   ██╗███████╗ ██████╗ ███╗   ██╗ ██████╗ ██████╗ ██╗██████╗ ");
        System.out.println("  ████╗  ██║██╔════╝██╔═══██╗████╗  ██║██╔════╝ ██╔══██╗██║██╔══██╗");
        System.out.println("  ██╔██╗ ██║█████╗  ██║   ██║██╔██╗ ██║██║  ███╗██████╔╝██║██║  ██║");
        System.out.println("  ██║╚██╗██║██╔══╝  ██║   ██║██║╚██╗██║██║   ██║██╔══██╗██║██║  ██║");
        System.out.println("  ██║ ╚████║███████╗╚██████╔╝██║ ╚████║╚██████╔╝██║  ██║██║██████╔╝");
        System.out.println("  ╚═╝  ╚═══╝╚══════╝ ╚═════╝ ╚═╝  ╚═══╝ ╚═════╝ ╚═╝  ╚═╝╚═╝╚═════╝ " + r);
        System.out.println(m + b + "              A R E N A   ·   Java NIO multiplayer server v" + VERSION + r);
        System.out.println();
        System.out.println("  TCP  :" + cfg.tcpPort + "  HTTP + WebSocket + raw NGA1 protocol (one Selector thread)");
        System.out.println("  UDP  :" + cfg.udpPort + "  LAN discovery beacon (DatagramChannel on the same Selector)");
        System.out.println("  Pool : " + cfg.workers + " worker threads (AI, Diffie-Hellman, DNS, disk)");
        System.out.println("  Host : " + NetUtil.hostName());
        System.out.println();
        System.out.println(b + "  Play      → " + r + c + "http://localhost:" + cfg.tcpPort + "/" + r);
        for (String ip : NetUtil.lanAddresses()) {
            System.out.println(b + "  LAN       → " + r + c + "http://" + ip + ":" + cfg.tcpPort + "/" + r + "   (phones on the same Wi-Fi)");
        }
        System.out.println(b + "  Control   → " + r + c + "http://localhost:" + cfg.tcpPort + "/control" + r
                + "   password: " + b + cfg.adminPassword + r + "  (change with --admin-pass)");
        System.out.println();
    }
}
