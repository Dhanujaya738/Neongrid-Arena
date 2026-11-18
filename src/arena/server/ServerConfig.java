package arena.server;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Command-line configurable server settings. */
public final class ServerConfig {
    public int tcpPort = 8080;
    public int udpPort = 8888;
    public int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
    public int maxConnections = 2000;
    public String serverName = "NeonGrid Arena";
    /** Password for the Network Control Room (/control). Never sent over the wire: login is HMAC challenge-response. */
    public String adminPassword = "arena-admin";
    public Path webRoot = Paths.get("web");
    public Path dataDir = Paths.get("data");

    public static ServerConfig fromArgs(String[] args) {
        ServerConfig c = new ServerConfig();
        for (int i = 0; i + 1 < args.length; i += 2) {
            String k = args[i], v = args[i + 1];
            switch (k) {
                case "--port": c.tcpPort = Integer.parseInt(v); break;
                case "--udp": c.udpPort = Integer.parseInt(v); break;
                case "--workers": c.workers = Integer.parseInt(v); break;
                case "--max-conn": c.maxConnections = Integer.parseInt(v); break;
                case "--name": c.serverName = v; break;
                case "--admin-pass": c.adminPassword = v; break;
                case "--web": c.webRoot = Paths.get(v); break;
                case "--data": c.dataDir = Paths.get(v); break;
                default: System.err.println("Unknown option " + k);
            }
        }
        return c;
    }
}
