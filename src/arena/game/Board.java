package arena.game;

/**
 * Authoritative game rules for Classic (3x3) and Ultimate (9x9) Tic-Tac-Toe.
 * The server is the single source of truth: clients only send "I want cell N",
 * and the server validates it here before anything changes.
 *
 * Ultimate cell index g = subBoard * 9 + position. Playing at position p sends the
 * opponent to sub-board p; if that sub-board is already decided they may play anywhere.
 */
public final class Board {

    public enum Mode {
        CLASSIC, ULTIMATE;

        public static Mode parse(String s) {
            return "classic".equalsIgnoreCase(s) ? CLASSIC : ULTIMATE;
        }

        public String id() { return name().toLowerCase(); }
    }

    public static final int EMPTY = 0, X = 1, O = 2, DRAW = 3;

    public static final int[][] LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}
    };

    public final Mode mode;
    final byte[] cells;
    final byte[] macro;       // ULTIMATE: result of each sub-board (0 open, 1 X, 2 O, 3 drawn)
    int active = -1;          // ULTIMATE: sub-board the next player must play in (-1 = any)
    int turn = X;
    int winner = EMPTY;       // 0 = in progress, 1/2 = winner, 3 = draw
    int[] winLine = null;
    int lastMove = -1;
    int moveCount = 0;

    public Board(Mode mode) {
        this.mode = mode;
        this.cells = new byte[mode == Mode.CLASSIC ? 9 : 81];
        this.macro = new byte[9];
    }

    private Board(Board o) {
        mode = o.mode;
        cells = o.cells.clone();
        macro = o.macro.clone();
        active = o.active;
        turn = o.turn;
        winner = o.winner;
        winLine = o.winLine;
        lastMove = o.lastMove;
        moveCount = o.moveCount;
    }

    public Board copy() { return new Board(this); }

    public int turn() { return turn; }
    public int winner() { return winner; }
    public boolean over() { return winner != EMPTY; }
    public int[] winLine() { return winLine; }
    public int lastMove() { return lastMove; }
    public int active() { return active; }
    public int moveCount() { return moveCount; }
    public int size() { return cells.length; }
    public int cell(int i) { return cells[i]; }

    public int[] cellsArray() {
        int[] r = new int[cells.length];
        for (int i = 0; i < r.length; i++) r[i] = cells[i];
        return r;
    }

    public int[] macroArray() {
        int[] r = new int[9];
        for (int i = 0; i < 9; i++) r[i] = macro[i];
        return r;
    }

    /** Explains why a move is illegal, or returns null if it is legal. */
    public String whyIllegal(int g) {
        if (winner != EMPTY) return "The game is already over";
        if (g < 0 || g >= cells.length) return "Cell " + g + " does not exist";
        if (cells[g] != EMPTY) return "Cell is already taken";
        if (mode == Mode.ULTIMATE) {
            int b = g / 9;
            if (macro[b] != EMPTY) return "That mini-board is already decided";
            if (active != -1 && active != b) return "You must play in mini-board " + (active + 1);
        }
        return null;
    }

    public boolean isLegal(int g) { return whyIllegal(g) == null; }

    public void play(int g) {
        if (!isLegal(g)) throw new IllegalStateException(whyIllegal(g));
        byte p = (byte) turn;
        cells[g] = p;
        lastMove = g;
        moveCount++;
        if (mode == Mode.CLASSIC) {
            int[] line = findLine(cells, 0);
            if (line != null) { winner = p; winLine = line; }
            else if (moveCount == 9) winner = DRAW;
        } else {
            int b = g / 9, pos = g % 9;
            int[] sub = findLine(cells, b * 9);
            if (sub != null) macro[b] = p;
            else if (full(b)) macro[b] = DRAW;

            if (macro[b] == p) {
                int[] line = findLine(macro, 0);
                if (line != null) { winner = p; winLine = line; }
            }
            if (winner == EMPTY) {
                boolean allDecided = true;
                for (byte m : macro) if (m == EMPTY) { allDecided = false; break; }
                if (allDecided) winner = DRAW;
            }
            active = macro[pos] == EMPTY ? pos : -1;
        }
        turn = 3 - turn;
    }

    /** A player resigns / times out: the other player wins. */
    public void forfeit(int loser) {
        if (winner == EMPTY) winner = 3 - loser;
    }

    private boolean full(int b) {
        for (int i = 0; i < 9; i++) if (cells[b * 9 + i] == EMPTY) return false;
        return true;
    }

    /** Finds a completed line of X or O inside a 3x3 block that starts at offset. */
    private static int[] findLine(byte[] a, int off) {
        for (int[] l : LINES) {
            byte v = a[off + l[0]];
            if ((v == X || v == O) && v == a[off + l[1]] && v == a[off + l[2]]) {
                return new int[]{off + l[0], off + l[1], off + l[2]};
            }
        }
        return null;
    }

    /** Fills {@code out} with the legal moves and returns how many there are. */
    public int legalMoves(int[] out) {
        int n = 0;
        if (winner != EMPTY) return 0;
        if (mode == Mode.CLASSIC) {
            for (int i = 0; i < 9; i++) if (cells[i] == EMPTY) out[n++] = i;
            return n;
        }
        for (int b = 0; b < 9; b++) {
            if (macro[b] != EMPTY || (active != -1 && active != b)) continue;
            for (int i = 0; i < 9; i++) {
                int g = b * 9 + i;
                if (cells[g] == EMPTY) out[n++] = g;
            }
        }
        return n;
    }

    /** Human readable coordinate, e.g. "B5·c3" (mini-board 5, cell 3) or "c7". */
    public String describe(int g) {
        if (mode == Mode.CLASSIC) return "cell " + (g + 1);
        return "board " + (g / 9 + 1) + " · cell " + (g % 9 + 1);
    }
}
