package arena.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The NIO reactor: ONE thread, ONE {@link Selector}, many channels.
 *
 * Registered with the same selector:
 *   - a non-blocking {@link ServerSocketChannel} (OP_ACCEPT) on the TCP port,
 *   - every accepted {@link SocketChannel} (OP_READ, plus OP_WRITE only while output is pending),
 *   - a non-blocking {@link DatagramChannel} (OP_READ) for UDP LAN discovery.
 *
 * Game state is confined to this thread, so it needs no locks. Anything slow (AI search,
 * Diffie-Hellman maths, reverse DNS, disk writes) is handed to a worker {@link ExecutorService};
 * the worker's {@link Future} is polled with isDone() and its result applied back on this thread.
 */
public final class Reactor implements Runnable {

    final Selector selector;
    final ServerSocketChannel serverChannel;
    final DatagramChannel udpChannel;
    final ThreadPoolExecutor workers;
    final Metrics metrics;
    final ServerConfig config;

    Lobby lobby;
    Http http;
    Discovery discovery;

    /** Live connections (reactor thread only). */
    final Map<Long, Connection> connections = new LinkedHashMap<>();
    private long nextConnId = 1;

    /** Cross-thread hand-off: other threads post Runnables here, then wake the selector. */
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    /** Timers (turn clocks, stats ticks, reconnect grace periods) - reactor thread only. */
    private final PriorityQueue<Timer> timers = new PriorityQueue<>();
    /** Futures of background jobs waiting to be completed on this thread. */
    private final List<PendingJob<?>> pending = new ArrayList<>();
    private final ConcurrentLinkedQueue<PendingJob<?>> newJobs = new ConcurrentLinkedQueue<>();

    private volatile boolean running = true;
    private Thread thread;

    public Reactor(ServerConfig config, Metrics metrics) throws IOException {
        this.config = config;
        this.metrics = metrics;
        this.selector = Selector.open();

        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(config.tcpPort), 256);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        udpChannel = DatagramChannel.open();
        udpChannel.configureBlocking(false);
        udpChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        udpChannel.setOption(StandardSocketOptions.SO_BROADCAST, true);
        udpChannel.bind(new InetSocketAddress(config.udpPort));
        udpChannel.register(selector, SelectionKey.OP_READ);

        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "arena-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        workers = new ThreadPoolExecutor(config.workers, config.workers, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), tf);
    }

    public void start() {
        thread = new Thread(this, "nio-selector");
        thread.start();
    }

    public boolean inReactorThread() { return Thread.currentThread() == thread; }

    public Thread thread() { return thread; }

    @Override
    public void run() {
        System.out.println("[reactor] selector loop running on thread '" + Thread.currentThread().getName() + "'");
        while (running) {
            try {
                long wait = nextTimerDelay();
                int ready = wait == 0 ? selector.selectNow() : selector.select(wait);
                metrics.loops.increment();
                metrics.selectedKeys.add(ready);

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    dispatch(key);
                }
                runTasks();
                completeJobs();
                runTimers();
            } catch (Throwable t) {
                System.err.println("[reactor] loop error: " + t);
                t.printStackTrace();
            }
        }
        System.out.println("[reactor] stopped");
    }

    private void dispatch(SelectionKey key) {
        if (!key.isValid()) return;
        if (key.channel() == serverChannel) {
            if (key.isAcceptable()) accept();
            return;
        }
        if (key.channel() == udpChannel) {
            if (key.isReadable()) discovery.onReadable(udpChannel);
            return;
        }
        Connection c = (Connection) key.attachment();
        try {
            if (key.isReadable()) c.onReadable();
            if (key.isValid() && key.isWritable()) c.onWritable();
        } catch (IOException | RuntimeException e) {
            c.close("I/O error: " + e.getMessage());
        }
    }

    private void accept() {
        // Accept everything that is waiting (a burst of clients is handled in one wake-up).
        for (int i = 0; i < 64; i++) {
            SocketChannel ch;
            try {
                ch = serverChannel.accept();
            } catch (IOException e) {
                System.err.println("[reactor] accept failed: " + e.getMessage());
                return;
            }
            if (ch == null) return; // nothing more pending - non-blocking accept returns null
            try {
                if (connections.size() >= config.maxConnections) {
                    metrics.securityEvent("CONN_LIMIT", String.valueOf(ch.getRemoteAddress()), "server full, connection refused");
                    ch.close();
                    continue;
                }
                ch.configureBlocking(false);
                ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
                Connection c = new Connection(this, nextConnId++, ch);
                c.key = ch.register(selector, SelectionKey.OP_READ, c);
                connections.put(c.id, c);
                metrics.accepted.increment();
                c.armIdleTimer();
                lookupHostAsync(c);
            } catch (IOException e) {
                try { ch.close(); } catch (IOException ignored) { }
            }
        }
    }

    /** Reverse DNS is blocking, so it runs on a worker thread (Lecture 4: DNS reverse resolution). */
    private void lookupHostAsync(Connection c) {
        final java.net.InetAddress addr = c.remote.getAddress();
        submit(() -> {
            metrics.dnsLookups.increment();
            return addr.getCanonicalHostName();
        }, host -> c.hostName = host, err -> c.hostName = addr.getHostAddress());
    }

    void removed(Connection c) {
        connections.remove(c.id);
        metrics.closed.increment();
    }

    // ------------------------------------------------------------ cross-thread & timers

    /** Thread-safe: run r on the reactor thread as soon as possible. */
    public void execute(Runnable r) {
        tasks.add(r);
        selector.wakeup();
    }

    private void runTasks() {
        Runnable r;
        while ((r = tasks.poll()) != null) {
            try { r.run(); } catch (RuntimeException e) { e.printStackTrace(); }
        }
    }

    /**
     * Submits a Callable to the worker pool. The returned Future is tracked by the reactor and,
     * once isDone(), its value is delivered to onResult ON THE REACTOR THREAD.
     */
    public <T> Future<T> submit(Callable<T> job, Consumer<T> onResult, Consumer<Throwable> onError) {
        FutureTask<T> f = new FutureTask<T>(job) {
            @Override
            protected void done() {
                selector.wakeup(); // result is ready: make the selector re-check pending futures now
            }
        };
        PendingJob<T> pj = new PendingJob<>(f, onResult, onError);
        if (inReactorThread()) pending.add(pj); else newJobs.add(pj);
        workers.execute(f);
        return f;
    }

    private void completeJobs() {
        PendingJob<?> nj;
        while ((nj = newJobs.poll()) != null) pending.add(nj);
        if (pending.isEmpty()) return;
        Iterator<PendingJob<?>> it = pending.iterator();
        while (it.hasNext()) {
            PendingJob<?> j = it.next();
            if (j.future.isDone()) {
                it.remove();
                j.complete();
            }
        }
    }

    private static final class PendingJob<T> {
        final Future<T> future;
        final Consumer<T> onResult;
        final Consumer<Throwable> onError;

        PendingJob(Future<T> f, Consumer<T> r, Consumer<Throwable> e) { future = f; onResult = r; onError = e; }

        void complete() {
            if (future.isCancelled()) return;
            T value;
            try {
                value = future.get(); // never blocks: isDone() was true
            } catch (Exception e) {
                if (onError != null) onError.accept(e.getCause() != null ? e.getCause() : e);
                return;
            }
            if (onResult != null) onResult.accept(value);
        }
    }

    public static final class Timer implements Comparable<Timer> {
        final long at;
        final Runnable task;
        boolean cancelled;

        Timer(long at, Runnable task) { this.at = at; this.task = task; }

        public void cancel() { cancelled = true; }

        @Override
        public int compareTo(Timer o) { return Long.compare(at, o.at); }
    }

    /** Reactor-thread only. */
    public Timer schedule(long delayMs, Runnable task) {
        Timer t = new Timer(System.currentTimeMillis() + delayMs, task);
        timers.add(t);
        return t;
    }

    public void every(long periodMs, Runnable task) {
        schedule(periodMs, new Runnable() {
            @Override
            public void run() {
                task.run();
                schedule(periodMs, this);
            }
        });
    }

    private long nextTimerDelay() {
        if (!tasks.isEmpty()) return 0;
        Timer t = timers.peek();
        if (t == null) return 1000;
        long d = t.at - System.currentTimeMillis();
        return d <= 0 ? 0 : Math.min(d, 1000);
    }

    private void runTimers() {
        long now = System.currentTimeMillis();
        while (!timers.isEmpty() && timers.peek().at <= now) {
            Timer t = timers.poll();
            if (t.cancelled) continue;
            try { t.task.run(); } catch (RuntimeException e) { e.printStackTrace(); }
        }
    }

    public void shutdown() {
        running = false;
        selector.wakeup();
        workers.shutdownNow();
    }
}
