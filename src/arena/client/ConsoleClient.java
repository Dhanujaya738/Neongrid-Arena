package arena.client;

import arena.common.Json;
import arena.common.SecureLink;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Terminal client built with TRADITIONAL blocking java.net.Socket I/O (Lecture 2), to contrast
 * with the NIO server:
 *   - one thread blocks on readInt()/readFully() waiting for server frames,
 *   - the main thread blocks on System.in waiting for the user,
 *   - a third thread sends heart-beat pings.
 * Sending is {@code synchronized} because several threads share one output stream + sequence counter.
 *
 * Usage:  java -cp build/classes arena.client.ConsoleClient [host] [port] [name]
 *         (with no host, it discovers the server on the LAN via UDP broadcast)
 */
public final class ConsoleClient {

    private static final String X = "\u001B[96m", O = "\u001B[95m", DIM = "\u001B[90m", B = "\u001B[1m", R = "\u001B[0m",
            Y = "\u001B[93m", G = "\u001B[92m", RED = "\u001B[91m";

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private SecureLink link;
    private volatile Map<String, Object> room;
    private volatile boolean running = true;
    private String lastFrame;

    private ConsoleClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : null;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        String name = args.length > 2 ? args[2] : System.getProperty("user.name", "Console");
        if (host == null) {
            System.out.println(DIM + "No host given → discovering servers on the LAN via UDP broadcast..." + R);
            List<LanScanner.Found> found = LanScanner.scan(8888, 1500, true);
            if (found.isEmpty()) { System.out.println(RED + "No server found. Usage: ConsoleClient <host> [port] [name]" + R); return; }
            LanScanner.Found f = found.get(0);
            System.out.println(G + "✔ Found " + f + R);
            host = f.address;
            port = f.port;
        }

        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 4000);   // TCP three-way handshake happens here
        } catch (UnknownHostException e) {
            System.out.println(RED + "Unknown host " + host + R); return;
        } catch (ConnectException | SocketTimeoutException e) {
            System.out.println(RED + "Could not connect to " + host + ":" + port + " - " + e.getMessage() + R); return;
        }
        s.setTcpNoDelay(true);
        System.out.println(DIM + "TCP connected " + s.getLocalSocketAddress() + " → " + s.getRemoteSocketAddress() + R);
        new ConsoleClient(s).run(name);
    }

    private void run(String name) throws Exception {
        // 1. protocol magic so the server's sniffer selects the RAW length-prefixed protocol
        out.write(new byte[]{'N', 'G', 'A', '1'});
        // 2. Diffie-Hellman hello (unsigned)
        Handshake hs = new Handshake();
        writeFrame(hs.helloJson(name, "console"));
        String welcome = readFrame();
        link = hs.complete(welcome);
        System.out.println(G + "🔐 Secure channel ready: DH-2048 shared key → HMAC-SHA256 on every frame" + R);

        Thread reader = new Thread(this::readLoop, "socket-reader");
        reader.setDaemon(true);
        reader.start();
        Thread pinger = new Thread(() -> {
            while (running) {
                try { Thread.sleep(5000); send(Json.obj("t", "ping", "ts", System.currentTimeMillis())); }
                catch (Exception e) { return; }
            }
        }, "heartbeat");
        pinger.setDaemon(true);
        pinger.start();

        help();
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while (running && (line = stdin.readLine()) != null) {
            try {
                command(line.trim());
            } catch (Exception e) {
                System.out.println(RED + "error: " + e.getMessage() + R);
            }
        }
        running = false;
        socket.close();
    }

    private void help() {
        System.out.println(B + "\nCommands:" + R
                + "\n  quick [classic|ultimate]        find an opponent"
                + "\n  ai [classic|ultimate] [easy|medium|hard]"
                + "\n  create [classic|ultimate]       private room with invite code"
                + "\n  join CODE | spectate CODE"
                + "\n  <cell>                          classic: 1-9   ultimate: <board 1-9> <cell 1-9>  e.g. 5 3"
                + "\n  chat <text> | rematch | leave | lobby | board"
                + "\n  replay | tamper                 security demo: resend last frame / forge a move"
                + "\n  quit\n");
    }

    private void command(String line) throws IOException {
        if (line.isEmpty()) return;
        String[] p = line.split("\\s+");
        switch (p[0].toLowerCase()) {
            case "quick": send(Json.obj("t", "quick", "mode", p.length > 1 ? p[1] : "ultimate")); break;
            case "ai": send(Json.obj("t", "ai", "mode", p.length > 1 ? p[1] : "ultimate", "level", p.length > 2 ? p[2] : "medium")); break;
            case "create": send(Json.obj("t", "create", "mode", p.length > 1 ? p[1] : "ultimate", "private", true)); break;
            case "join": send(Json.obj("t", "join", "code", p.length > 1 ? p[1] : "")); break;
            case "spectate": send(Json.obj("t", "spectate", "code", p.length > 1 ? p[1] : "")); break;
            case "chat": send(Json.obj("t", "chat", "text", line.substring(4).trim())); break;
            case "rematch": send(Json.obj("t", "rematch")); break;
            case "leave": send(Json.obj("t", "leave")); break;
            case "lobby": send(Json.obj("t", "lobby")); break;
            case "board": if (room != null) render(room); break;
            case "help": help(); break;
            case "quit": running = false; socket.close(); break;
            case "replay":
                if (lastFrame == null) { System.out.println("nothing to replay yet"); break; }
                System.out.println(Y + "⚠ Re-sending the last signed frame byte-for-byte (replay attack)..." + R);
                synchronized (this) { writeFrame(lastFrame); }
                break;
            case "tamper": {
                System.out.println(Y + "⚠ Sending a move whose body was changed after signing (MITM tampering)..." + R);
                synchronized (this) {
                    String sealed = link.seal(Json.obj("t", "move", "cell", 0));
                    writeFrame(sealed.replace("\"cell\":0", "\"cell\":4"));
                }
                break;
            }
            default:
                Integer cell = parseCell(p);
                if (cell == null) { System.out.println("unknown command - type help"); break; }
                send(Json.obj("t", "move", "cell", cell));
        }
    }

    private Integer parseCell(String[] p) {
        try {
            boolean ultimate = room != null && "ultimate".equals(room.get("mode"));
            if (ultimate) {
                if (p.length == 2) return (Integer.parseInt(p[0]) - 1) * 9 + (Integer.parseInt(p[1]) - 1);
                if (p.length == 1 && p[0].length() == 2) return (p[0].charAt(0) - '1') * 9 + (p[0].charAt(1) - '1');
                return null;
            }
            return Integer.parseInt(p[0]) - 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ network

    /** Shared by the main thread and the heartbeat thread, hence synchronized. */
    private synchronized void send(Map<String, Object> msg) throws IOException {
        String frame = link.seal(msg);
        lastFrame = frame;
        writeFrame(frame);
    }

    private void writeFrame(String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);   // 4-byte length prefix (big-endian, like ByteBuffer.putInt)
        out.write(b);
        out.flush();
    }

    private String readFrame() throws IOException {
        int len = in.readInt();   // BLOCKS until 4 bytes arrive
        if (len <= 0 || len > 1 << 20) throw new IOException("bad frame length " + len);
        byte[] b = new byte[len];
        in.readFully(b);          // BLOCKS until the whole frame arrived
        return new String(b, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void readLoop() {
        try {
            while (running) {
                String frame = readFrame();
                Map<String, Object> m;
                try {
                    m = link.open(frame);
                } catch (SecureLink.Rejected r) {
                    System.out.println(RED + "‼ server frame rejected: " + r.kind + " " + r.getMessage() + R);
                    continue;
                }
                String t = Json.str(m, "t", "");
                switch (t) {
                    case "profile":
                        Map<String, Object> srv = (Map<String, Object>) m.get("server");
                        System.out.println(G + "Welcome " + B + m.get("name") + R + G + " (Elo " + m.get("elo") + ") on "
                                + srv.get("name") + " @ " + srv.get("host") + R);
                        break;
                    case "lobby":
                        System.out.println(DIM + "Lobby: " + m.get("online") + " online, " + ((List<Object>) m.get("rooms")).size()
                                + " public rooms, queues " + m.get("queues") + R);
                        break;
                    case "queue":
                        if (Json.bool(m, "waiting", false)) System.out.println(Y + "⏳ Waiting for an opponent (" + m.get("mode") + ")..." + R);
                        break;
                    case "room":
                        room = m;
                        render(m);
                        break;
                    case "chat": {
                        Map<String, Object> it = (Map<String, Object>) m.get("item");
                        if (Json.bool(it, "sys", false)) System.out.println(DIM + "  · " + it.get("text") + R);
                        else System.out.println("  " + B + it.get("from") + R + ": " + it.get("text"));
                        break;
                    }
                    case "ai_info": System.out.println(DIM + "  🤖 " + m.get("text") + R); break;
                    case "react": System.out.println("  " + m.get("from") + " reacted " + m.get("emoji")); break;
                    case "error": System.out.println(RED + "✖ " + m.get("code") + ": " + m.get("msg") + R); break;
                    case "rejected":
                        System.out.println(RED + B + "🛡 SERVER REJECTED: " + m.get("reason") + R + RED + " — " + m.get("detail") + R);
                        break;
                    case "room_closed": room = null; System.out.println(DIM + "Room closed." + R); break;
                    default: break; // pong, thinking, chat_history...
                }
            }
        } catch (EOFException e) {
            System.out.println(RED + "Server closed the connection." + R);
        } catch (IOException e) {
            if (running) System.out.println(RED + "Connection lost: " + e.getMessage() + R);
        }
        running = false;
    }

    // ------------------------------------------------------------------ rendering

    @SuppressWarnings("unchecked")
    private static void render(Map<String, Object> r) {
        List<Object> cells = (List<Object>) r.get("cells");
        List<Object> players = (List<Object>) r.get("players");
        int you = (int) Json.num(r, "you", 0), turn = (int) Json.num(r, "turn", 1), winner = (int) Json.num(r, "winner", 0);
        int last = (int) Json.num(r, "lastMove", -1), active = (int) Json.num(r, "active", -1);
        StringBuilder sb = new StringBuilder("\n");
        sb.append(B).append("Room ").append(r.get("code")).append(R).append(DIM).append("  ").append(r.get("mode"))
                .append(" · ").append(r.get("phase")).append(R).append('\n');
        for (int side = 1; side <= 2; side++) {
            Map<String, Object> p = (Map<String, Object>) players.get(side);
            sb.append("  ").append(side == 1 ? X + "X" : O + "O").append(R).append(" ")
                    .append(p == null ? DIM + "waiting..." + R : p.get("name"))
                    .append(side == you ? Y + "  (you)" + R : "").append(side == turn && winner == 0 && p != null ? G + "  ◀ to move" + R : "")
                    .append('\n');
        }
        if (cells.size() == 9) {
            for (int row = 0; row < 3; row++) {
                sb.append("     ");
                for (int col = 0; col < 3; col++) sb.append(sym((int) (long) (Long) cells.get(row * 3 + col), row * 3 + col, last, true)).append(col < 2 ? DIM + "│" + R : "");
                sb.append('\n');
                if (row < 2) sb.append(DIM).append("     ───┼───┼───").append(R).append('\n');
            }
        } else {
            List<Object> macro = (List<Object>) r.get("macro");
            for (int bigRow = 0; bigRow < 3; bigRow++) {
                for (int row = 0; row < 3; row++) {
                    sb.append("  ");
                    for (int bigCol = 0; bigCol < 3; bigCol++) {
                        int b = bigRow * 3 + bigCol;
                        long mv = (Long) macro.get(b);
                        boolean hl = winner == 0 && (active == -1 ? mv == 0 : active == b);
                        for (int col = 0; col < 3; col++) {
                            int g = b * 9 + row * 3 + col;
                            String s = sym((int) (long) (Long) cells.get(g), g, last, false);
                            if (mv == 1) s = X + " X " + R; else if (mv == 2) s = O + " O " + R; else if (mv == 3) s = DIM + " · " + R;
                            if (mv != 0 && !(row == 1 && col == 1)) s = mv == 1 ? X + "░░░" + R : mv == 2 ? O + "░░░" + R : DIM + "   " + R;
                            sb.append(hl ? "\u001B[48;5;236m" + s + R : s);
                        }
                        if (bigCol < 2) sb.append(B).append(" ┃ ").append(R);
                    }
                    sb.append('\n');
                }
                if (bigRow < 2) sb.append(B).append("  ━━━━━━━━━╋━━━━━━━━━━━╋━━━━━━━━━").append(R).append('\n');
            }
            sb.append(DIM).append("  move with: <board 1-9> <cell 1-9>").append(active >= 0 && winner == 0 ? "   (must play in board " + (active + 1) + ")" : "").append(R).append('\n');
        }
        if (winner != 0) sb.append(B).append(winner == 3 ? "  🤝 Draw" : winner == you ? G + "  🏆 You win!" : RED + "  Game over").append(R)
                .append(DIM).append("  — type rematch").append(R).append('\n');
        System.out.print(sb);
    }

    private static String sym(int v, int idx, int last, boolean numbered) {
        String hl = idx == last ? "\u001B[4m" : "";
        if (v == 1) return X + B + hl + " X " + R;
        if (v == 2) return O + B + hl + " O " + R;
        return DIM + (numbered ? " " + (idx + 1) + " " : " · ") + R;
    }
}
