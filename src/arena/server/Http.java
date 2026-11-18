package arena.server;

import arena.common.Json;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal HTTP/1.1 server running on the same NIO selector.
 *
 *   GET /                 -> web client (index.html)
 *   GET /dashboard        -> live server dashboard
 *   GET /ws               -> WebSocket upgrade (101 Switching Protocols)
 *   GET /r/{CODE}         -> 302 redirect to /?room=CODE   (short invite links)
 *   GET /control          -> Network Control Room page (its data needs the admin password, see Lobby.adminLogin)
 *   GET /api/rooms        -> live rooms (JSON)
 *   GET /api/leaderboard  -> Elo leaderboard (JSON)
 * Server statistics, packet capture, URL anatomy and DNS tools are admin-only WebSocket commands.
 */
final class Http {

    private static final Pattern HOST_OK = Pattern.compile("^[A-Za-z0-9.\\-:]{1,253}$");
    private static final Map<String, String> TYPES = new HashMap<>();

    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("js", "application/javascript; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("woff2", "font/woff2");
    }

    private final Reactor reactor;
    private final Path webRoot;

    Http(Reactor reactor, Path webRoot) {
        this.reactor = reactor;
        this.webRoot = webRoot.toAbsolutePath().normalize();
    }

    void handle(Connection c, String head) {
        reactor.metrics.httpRequests.increment();
        String[] lines = head.split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length != 3 || !req[2].startsWith("HTTP/")) { error(c, 400, "Bad Request"); return; }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        if (!"GET".equals(req[0])) { error(c, 405, "Method Not Allowed"); return; }

        URI uri;
        try {
            uri = new URI(req[1]);           // java.net.URI parses path / query / fragment for us
        } catch (URISyntaxException e) {
            error(c, 400, "Bad Request");
            return;
        }
        String path = uri.getPath() == null ? "/" : uri.getPath();
        Map<String, String> query = parseQuery(uri.getRawQuery());

        if ("/ws".equals(path)) {
            if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && headers.containsKey("sec-websocket-key")) {
                upgrade(c, headers.get("sec-websocket-key"));
            } else {
                error(c, 426, "Upgrade Required");
            }
            return;
        }
        c.mode = Connection.Mode.HTTP_DONE;
        c.clientKind = "http";

        if (path.startsWith("/r/")) {
            String code = path.substring(3).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
            respond(c, 302, "text/plain", new byte[0], "Location: /?room=" + code + "\r\n");
            return;
        }
        switch (path) {
            case "/api/rooms": json(c, reactor.lobby.roomList()); return;
            case "/api/leaderboard": json(c, reactor.lobby.leaderboard.top(20)); return;
            case "/": serveFile(c, "index.html"); return;
            case "/control": serveFile(c, "control.html"); return;
            case "/dashboard": respond(c, 302, "text/plain", new byte[0], "Location: /control\r\n"); return;
            default: serveFile(c, path.substring(1));
        }
    }

    // ------------------------------------------------------------------ websocket

    private void upgrade(Connection c, String key) {
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + WebSocket.acceptKey(key) + "\r\n"
                + "Server: NeonGrid-NIO/1.0\r\n\r\n";
        c.write(ByteBuffer.wrap(resp.getBytes(StandardCharsets.ISO_8859_1)));
        c.mode = Connection.Mode.WEBSOCKET;
        c.clientKind = "websocket";
        reactor.metrics.wsUpgrades.increment();
    }

    // ------------------------------------------------------------------ static files

    private void serveFile(Connection c, String rel) {
        Path p = webRoot.resolve(rel).normalize();
        // Input validation: block path traversal such as /../../secret.txt
        if (!p.startsWith(webRoot)) {
            reactor.metrics.securityEvent("PATH_TRAVERSAL", c.label(), "blocked request for " + rel);
            error(c, 403, "Forbidden");
            return;
        }
        if (!Files.isRegularFile(p)) { error(c, 404, "Not Found"); return; }
        try (FileChannel fc = FileChannel.open(p, StandardOpenOption.READ)) {
            // NIO FileChannel -> ByteBuffer (the same channel/buffer model used for sockets)
            ByteBuffer body = ByteBuffer.allocate((int) fc.size());
            while (body.hasRemaining() && fc.read(body) > 0) { /* keep reading */ }
            body.flip();
            String name = p.getFileName().toString();
            String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            byte[] bytes = new byte[body.remaining()];
            body.get(bytes);
            respond(c, 200, TYPES.getOrDefault(ext, "application/octet-stream"), bytes, "");
        } catch (IOException e) {
            error(c, 500, "Internal Server Error");
        }
    }

    // ------------------------------------------------------------------ URL anatomy (Lecture 4)

    static Map<String, Object> urlAnatomy(String raw) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("input", raw);
        try {
            URI u = new URI(raw.trim());
            r.put("kind", u.isOpaque() ? "URN / opaque URI (names a resource, no location)"
                    : u.isAbsolute() ? "URL (absolute URI with a location)" : "relative URI reference");
            r.put("scheme", u.getScheme());
            r.put("userInfo", u.getUserInfo());
            r.put("host", u.getHost());
            int port = u.getPort();
            if (port == -1 && u.getScheme() != null && !u.isOpaque()) {
                try {
                    URL url = u.toURL();               // java.net.URL knows protocol default ports
                    port = url.getDefaultPort();
                    r.put("portNote", "default port for " + u.getScheme());
                } catch (Exception ignored) { /* unknown scheme */ }
            }
            r.put("port", port == -1 ? null : port);
            r.put("path", u.isOpaque() ? u.getSchemeSpecificPart() : u.getPath());
            r.put("query", u.getQuery());
            r.put("params", parseQuery(u.getRawQuery()));
            r.put("fragment", u.getFragment());
            if (u.getHost() != null) {
                String[] labels = u.getHost().split("\\.");
                if (labels.length >= 2) {
                    Map<String, Object> dns = new LinkedHashMap<>();
                    dns.put("root", ".");
                    dns.put("tld", labels[labels.length - 1]);
                    dns.put("domain", labels[labels.length - 2]);
                    List<String> sub = new ArrayList<>();
                    for (int i = 0; i < labels.length - 2; i++) sub.add(labels[i]);
                    dns.put("subdomains", sub);
                    r.put("dnsHierarchy", dns);
                }
            }
            r.put("ok", true);
        } catch (URISyntaxException e) {
            r.put("ok", false);
            r.put("error", e.getMessage());
        }
        return r;
    }

    /** Blocking DNS lookup (forward + reverse) - always called on a worker thread, never on the selector. */
    static Map<String, Object> dnsLookup(Reactor reactor, String host) throws Exception {
        if (!HOST_OK.matcher(host).matches()) return Json.obj("t", "dns_result", "ok", false, "host", host, "error", "invalid host name");
        long t0 = System.nanoTime();
        reactor.metrics.dnsLookups.increment();
        InetAddress[] all = InetAddress.getAllByName(host);
        List<Object> list = new ArrayList<>();
        for (InetAddress a : all) {
            list.add(Json.obj("ip", a.getHostAddress(), "version", a instanceof Inet4Address ? "IPv4" : "IPv6"));
        }
        String reverse = all.length > 0 ? all[0].getCanonicalHostName() : null;
        return Json.obj("t", "dns_result", "ok", true, "host", host, "addresses", list, "reverse", reverse,
                "millis", (System.nanoTime() - t0) / 1_000_000, "resolvedOn", Thread.currentThread().getName());
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception e) {
                m.put(k, v);
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ responses

    private void json(Connection c, Object body) {
        respond(c, 200, "application/json; charset=utf-8", Json.stringify(body).getBytes(StandardCharsets.UTF_8),
                "Access-Control-Allow-Origin: *\r\n");
    }

    private void error(Connection c, int code, String msg) {
        c.mode = Connection.Mode.HTTP_DONE;
        respond(c, code, "text/plain; charset=utf-8", (code + " " + msg).getBytes(StandardCharsets.UTF_8), "");
    }

    private void respond(Connection c, int code, String type, byte[] body, String extra) {
        if (c.closed) return;
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "X-Frame-Options: DENY\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Server: NeonGrid-NIO/1.0\r\n"
                + "Connection: close\r\n"
                + extra + "\r\n";
        byte[] h = head.getBytes(StandardCharsets.ISO_8859_1);
        ByteBuffer buf = ByteBuffer.allocate(h.length + body.length);
        buf.put(h).put(body).flip();
        c.write(buf);
        c.finishAndClose();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 426: return "Upgrade Required";
            default: return "Error";
        }
    }
}
