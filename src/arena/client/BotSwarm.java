package arena.client;

import arena.common.Json;
import arena.common.SecureLink;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Load generator: N bot players, ALL driven by ONE thread and ONE Selector on the client side too.
 * Each bot is a non-blocking SocketChannel that connects, performs the secure handshake,
 * joins quick-match, plays random legal moves and asks for rematches forever.
 *
 * Usage: java -cp build/classes arena.client.BotSwarm [--bots 200] [--host localhost] [--port 8080]
 *                                          [--mode ultimate|classic|mixed] [--think 400]
 * With no --host the server is discovered over UDP.
 */
public final class BotSwarm {

    private static final class Bot {
        final int id;
        final SocketChannel ch;
        SelectionKey key;
        final ByteBuffer in = ByteBuffer.allocate(64 * 1024);
        final ArrayDeque<ByteBuffer> out = new ArrayDeque<>();
        Handshake hs;
        SecureLink link;
        String mode;
        boolean secured;
        int lastScheduledFor = -1;

        Bot(int id, SocketChannel ch, String mode) { this.id = id; this.ch = ch; this.mode = mode; }
    }

    private static final class Task implements Comparable<Task> {
        final long at;
        final Runnable r;
        Task(long at, Runnable r) { this.at = at; this.r = r; }
        public int compareTo(Task o) { return Long.compare(at, o.at); }
    }

    private final Selector selector = Selector.open();
    private final PriorityQueue<Task> tasks = new PriorityQueue<>();
    private final List<Bot> bots = new ArrayList<>();
    private final int think;
    private long moves, games, bytesIn, bytesOut, rejected, closed;

    private BotSwarm(int think) throws IOException { this.think = think; }

    public static void main(String[] args) throws Exception {
        int n = 100, port = 8080, think = 400;
        String host = null, mode = "mixed";
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--bots": n = Integer.parseInt(args[i + 1]); break;
                case "--host": host = args[i + 1]; break;
                case "--port": port = Integer.parseInt(args[i + 1]); break;
                case "--mode": mode = args[i + 1]; break;
                case "--think": think = Integer.parseInt(args[i + 1]); break;
                default: break;
            }
        }
        if (host == null) {
            System.out.println("Discovering server via UDP broadcast...");
            List<LanScanner.Found> f = LanScanner.scan(8888, 1200, false);
            if (f.isEmpty()) { System.out.println("No server found, using localhost"); host = "localhost"; }
            else { host = f.get(0).address; port = f.get(0).port; System.out.println("Found " + f.get(0)); }
        }
        new BotSwarm(think).run(host, port, n, mode);
    }

    private void run(String host, int port, int n, String mode) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        System.out.printf("Launching %d bots against %s:%d from ONE selector thread (%s)%n", n, host, port, Thread.currentThread().getName());
        for (int i = 0; i < n; i++) {
            final int id = i;
            String m = mode.equals("mixed") ? (i % 3 == 0 ? "classic" : "ultimate") : mode;
            schedule(i * 15L, () -> connect(id, addr, m)); // ramp up gently
        }
        // heartbeat: the server drops sockets that stay silent for 45 s (idle / slowloris protection)
        schedule(15_000, new Runnable() {
            @Override
            public void run() {
                for (Bot b : bots) if (b.secured) send(b, Json.obj("t", "ping", "ts", System.currentTimeMillis()));
                schedule(15_000, this);
            }
        });
        long lastReport = 0;
        while (true) {
            long wait = tasks.isEmpty() ? 500 : Math.max(1, tasks.peek().at - System.currentTimeMillis());
            selector.select(Math.min(wait, 500));
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey k = it.next();
                it.remove();
                Bot b = (Bot) k.attachment();
                try {
                    if (!k.isValid()) continue;
                    if (k.isConnectable()) finishConnect(b);
                    else {
                        if (k.isReadable()) read(b);
                        if (k.isValid() && k.isWritable()) flush(b);
                    }
                } catch (IOException | RuntimeException e) {
                    kill(b);
                }
            }
            long now = System.currentTimeMillis();
            while (!tasks.isEmpty() && tasks.peek().at <= now) tasks.poll().r.run();
            if (now - lastReport > 2000) {
                lastReport = now;
                int live = 0;
                for (Bot b : bots) if (b.secured && b.ch.isOpen()) live++;
                System.out.printf("[swarm] secure bots %4d/%d | moves %7d | games %5d | in %8.1f KB | out %8.1f KB | rejected %d | dropped %d%n",
                        live, n, moves, games, bytesIn / 1024.0, bytesOut / 1024.0, rejected, closed);
            }
        }
    }

    private void schedule(long delay, Runnable r) { tasks.add(new Task(System.currentTimeMillis() + delay, r)); }

    private void connect(int id, InetSocketAddress addr, String mode) {
        try {
            SocketChannel ch = SocketChannel.open();
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            Bot b = new Bot(id, ch, mode);
            bots.add(b);
            b.key = ch.register(selector, SelectionKey.OP_CONNECT, b);
            if (ch.connect(addr)) finishConnect(b);  // non-blocking connect usually returns false
        } catch (IOException e) {
            closed++;
        }
    }

    private void finishConnect(Bot b) throws IOException {
        if (!b.ch.finishConnect()) return;
        b.key.interestOps(SelectionKey.OP_READ);
        b.hs = new Handshake();
        write(b, ByteBuffer.wrap(new byte[]{'N', 'G', 'A', '1'}));
        frame(b, b.hs.helloJson("Bot-" + String.format("%03d", b.id), "bot"));
    }

    private void read(Bot b) throws IOException {
        int n = b.ch.read(b.in);
        if (n < 0) { kill(b); return; }
        bytesIn += n;
        b.in.flip();
        while (b.in.remaining() >= 4) {
            int len = b.in.getInt(b.in.position());
            if (b.in.remaining() < 4 + len) break;      // wait for the rest of the frame
            b.in.getInt();
            byte[] p = new byte[len];
            b.in.get(p);
            onFrame(b, new String(p, StandardCharsets.UTF_8));
        }
        b.in.compact();
    }

    @SuppressWarnings("unchecked")
    private void onFrame(Bot b, String frame) {
        if (!b.secured) {
            b.link = b.hs.complete(frame);
            b.secured = true;
            send(b, Json.obj("t", "quick", "mode", b.mode));
            return;
        }
        Map<String, Object> m;
        try { m = b.link.open(frame); } catch (SecureLink.Rejected r) { rejected++; return; }
        String t = Json.str(m, "t", "");
        if ("rejected".equals(t)) { rejected++; return; }
        if ("error".equals(t)) {
            if ("OPPONENT_LEFT".equals(m.get("code"))) send(b, Json.obj("t", "quick", "mode", b.mode));
            return;
        }
        if ("room_closed".equals(t)) { schedule(300, () -> send(b, Json.obj("t", "quick", "mode", b.mode))); return; }
        if (!"room".equals(t)) return;
        String phase = Json.str(m, "phase", "");
        int you = (int) Json.num(m, "you", 0);
        if ("FINISHED".equals(phase)) {
            int gameNo = (int) Json.num(m, "gameNo", 0);
            if (b.lastScheduledFor != -gameNo) {
                b.lastScheduledFor = -gameNo;
                if (you == 1) games++;
                schedule(800 + ThreadLocalRandom.current().nextInt(800), () -> send(b, Json.obj("t", "rematch")));
            }
            return;
        }
        if (!"PLAYING".equals(phase) || Json.num(m, "turn", 0) != you) return;
        List<Object> cells = (List<Object>) m.get("cells");
        List<Object> macro = (List<Object>) m.get("macro");
        int active = (int) Json.num(m, "active", -1);
        int moveCount = (int) Json.num(m, "moveCount", 0);
        if (b.lastScheduledFor == moveCount) return;
        b.lastScheduledFor = moveCount;
        List<Integer> legal = new ArrayList<>();
        for (int g = 0; g < cells.size(); g++) {
            if (((Number) cells.get(g)).intValue() != 0) continue;
            if (cells.size() == 81) {
                int bd = g / 9;
                if (((Number) macro.get(bd)).intValue() != 0 || (active != -1 && active != bd)) continue;
            }
            legal.add(g);
        }
        if (legal.isEmpty()) return;
        int cell = legal.get(ThreadLocalRandom.current().nextInt(legal.size()));
        schedule(think / 2 + ThreadLocalRandom.current().nextInt(Math.max(1, think)), () -> {
            send(b, Json.obj("t", "move", "cell", cell));
            moves++;
        });
    }

    private void send(Bot b, Map<String, Object> msg) {
        if (b.link != null && b.ch.isOpen()) frame(b, b.link.seal(msg));
    }

    private void frame(Bot b, String s) {
        byte[] p = s.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + p.length);
        buf.putInt(p.length).put(p).flip();
        write(b, buf);
    }

    private void write(Bot b, ByteBuffer buf) {
        try {
            if (b.out.isEmpty()) {
                bytesOut += b.ch.write(buf);
                if (!buf.hasRemaining()) return;
            }
            b.out.add(buf);
            b.key.interestOps(b.key.interestOps() | SelectionKey.OP_WRITE);
        } catch (IOException e) {
            kill(b);
        }
    }

    private void flush(Bot b) throws IOException {
        while (!b.out.isEmpty()) {
            ByteBuffer x = b.out.peek();
            bytesOut += b.ch.write(x);
            if (x.hasRemaining()) return;
            b.out.poll();
        }
        b.key.interestOps(SelectionKey.OP_READ);
    }

    private void kill(Bot b) {
        closed++;
        b.secured = false;
        try { b.ch.close(); } catch (IOException ignored) { }
    }
}
