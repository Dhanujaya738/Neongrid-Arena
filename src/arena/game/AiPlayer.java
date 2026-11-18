package arena.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CPU-heavy AI opponent implemented as a {@link Callable}.
 *
 * It is submitted to the server's ExecutorService (thread pool) and returns its result through a
 * {@link java.util.concurrent.Future}. The NIO selector thread never runs this code, so one bot
 * "thinking" for 1.5 seconds never freezes the hundreds of other connections.
 *
 *  - Classic 3x3: perfect minimax search with alpha-beta pruning.
 *  - Ultimate 9x9: Monte-Carlo Tree Search (UCT) with a time budget.
 */
public final class AiPlayer implements Callable<AiPlayer.Result> {

    public static final class Result {
        public final int move;
        public final long iterations;
        public final long millis;
        public final String thread;
        public final String algorithm;

        Result(int move, long iterations, long millis, String algorithm) {
            this.move = move;
            this.iterations = iterations;
            this.millis = millis;
            this.algorithm = algorithm;
            this.thread = Thread.currentThread().getName();
        }
    }

    private final Board board;
    private final String level;

    public AiPlayer(Board snapshot, String level) {
        this.board = snapshot.copy();
        this.level = level == null ? "medium" : level;
    }

    @Override
    public Result call() {
        long t0 = System.nanoTime();
        int[] moves = new int[81];
        int n = board.legalMoves(moves);
        if (n == 0) throw new IllegalStateException("No legal moves");
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        double randomness = level.equals("easy") ? 0.45 : level.equals("medium") ? 0.12 : 0.0;
        if (rnd.nextDouble() < randomness) {
            sleepQuietly(350); // feel more human
            return new Result(moves[rnd.nextInt(n)], 1, ms(t0), "random");
        }
        if (board.mode == Board.Mode.CLASSIC) {
            long[] nodes = {0};
            int best = -1, bestScore = Integer.MIN_VALUE;
            int me = board.turn();
            for (int i = 0; i < n; i++) {
                Board b = board.copy();
                b.play(moves[i]);
                int s = -negamax(b, 3 - me, Integer.MIN_VALUE + 1, Integer.MAX_VALUE, 1, nodes);
                if (s > bestScore || (s == bestScore && rnd.nextBoolean())) { bestScore = s; best = moves[i]; }
            }
            sleepQuietly(400);
            return new Result(best, nodes[0], ms(t0), "minimax + alpha-beta");
        }
        long budget = level.equals("easy") ? 150 : level.equals("medium") ? 600 : 1600;
        return mcts(budget, t0);
    }

    /** Score from the point of view of the player to move. */
    private int negamax(Board b, int player, int alpha, int beta, int depth, long[] nodes) {
        nodes[0]++;
        if (b.over()) {
            if (b.winner() == Board.DRAW) return 0;
            return b.winner() == player ? 10 - depth : depth - 10;
        }
        int[] moves = new int[9];
        int n = b.legalMoves(moves);
        int best = Integer.MIN_VALUE + 1;
        for (int i = 0; i < n; i++) {
            Board c = b.copy();
            c.play(moves[i]);
            int s = -negamax(c, 3 - player, -beta, -alpha, depth + 1, nodes);
            if (s > best) best = s;
            if (best > alpha) alpha = best;
            if (alpha >= beta) break;
        }
        return best;
    }

    // ----------------------------------------------------------------- MCTS

    private static final class Node {
        final Node parent;
        final int move;
        final int justMoved;
        final List<Node> children = new ArrayList<>();
        int[] untried;
        int untriedCount;
        double wins;
        int visits;

        Node(Node parent, int move, int justMoved, Board state) {
            this.parent = parent;
            this.move = move;
            this.justMoved = justMoved;
            this.untried = new int[81];
            this.untriedCount = state.legalMoves(untried);
        }

        Node select() {
            Node best = null;
            double bestVal = -1;
            double logN = Math.log(visits);
            for (Node c : children) {
                double v = c.wins / c.visits + 1.41 * Math.sqrt(logN / c.visits);
                if (v > bestVal) { bestVal = v; best = c; }
            }
            return best;
        }
    }

    private Result mcts(long budgetMs, long t0) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Node root = new Node(null, -1, 3 - board.turn(), board);
        long deadline = t0 + budgetMs * 1_000_000L;
        long iterations = 0;
        int[] buf = new int[81];

        // Immediate win check: always take a winning move.
        for (int i = 0; i < root.untriedCount; i++) {
            Board b = board.copy();
            b.play(root.untried[i]);
            if (b.winner() == board.turn()) {
                sleepQuietly(300);
                return new Result(root.untried[i], 1, ms(t0), "MCTS (instant win)");
            }
        }

        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            Node node = root;
            Board state = board.copy();
            // 1. selection
            while (node.untriedCount == 0 && !node.children.isEmpty()) {
                node = node.select();
                state.play(node.move);
            }
            // 2. expansion
            if (node.untriedCount > 0) {
                int idx = rnd.nextInt(node.untriedCount);
                int m = node.untried[idx];
                node.untried[idx] = node.untried[--node.untriedCount];
                int mover = state.turn();
                state.play(m);
                Node child = new Node(node, m, mover, state);
                node.children.add(child);
                node = child;
            }
            // 3. simulation (random playout)
            while (!state.over()) {
                int n = state.legalMoves(buf);
                state.play(buf[rnd.nextInt(n)]);
            }
            // 4. back-propagation
            int w = state.winner();
            while (node != null) {
                node.visits++;
                if (w == Board.DRAW) node.wins += 0.5;
                else if (w == node.justMoved) node.wins += 1.0;
                node = node.parent;
            }
            iterations++;
        }
        Node best = null;
        for (Node c : root.children) if (best == null || c.visits > best.visits) best = c;
        int move = best != null ? best.move : root.untried[0];
        return new Result(move, iterations, ms(t0), "Monte-Carlo Tree Search");
    }

    private static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000L; }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
