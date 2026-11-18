package arena.server;

import arena.common.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Elo rating table. Read and updated on the selector thread but SAVED TO DISK on a worker thread,
 * so every method is {@code synchronized}: two threads touch the same shared data (Lecture 5:
 * race conditions and the synchronized keyword).
 */
final class Leaderboard {

    static final class Entry {
        final String name;
        int elo = 1200, wins, losses, draws;

        Entry(String name) { this.name = name; }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final Path file;

    Leaderboard(Path dataDir) {
        this.file = dataDir.resolve("leaderboard.json");
        load();
    }

    synchronized int elo(String name) {
        Entry e = entries.get(key(name));
        return e == null ? 1200 : e.elo;
    }

    /**
     * Records a finished game. result: 1 = X won, 2 = O won, 3 = draw.
     * Returns the rating change for X and O.
     */
    synchronized int[] record(String xName, String oName, int result) {
        Entry x = entries.computeIfAbsent(key(xName), k -> new Entry(xName));
        Entry o = entries.computeIfAbsent(key(oName), k -> new Entry(oName));
        double expectedX = 1.0 / (1 + Math.pow(10, (o.elo - x.elo) / 400.0));
        double scoreX = result == 1 ? 1 : result == 2 ? 0 : 0.5;
        int dx = (int) Math.round(32 * (scoreX - expectedX));
        x.elo += dx;
        o.elo -= dx;
        if (result == 1) { x.wins++; o.losses++; }
        else if (result == 2) { o.wins++; x.losses++; }
        else { x.draws++; o.draws++; }
        return new int[]{dx, -dx};
    }

    synchronized List<Object> top(int n) {
        List<Entry> all = new ArrayList<>(entries.values());
        all.sort((a, b) -> b.elo != a.elo ? Integer.compare(b.elo, a.elo) : Integer.compare(b.wins, a.wins));
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, all.size()); i++) {
            Entry e = all.get(i);
            out.add(Json.obj("rank", i + 1, "name", e.name, "elo", e.elo, "w", e.wins, "l", e.losses, "d", e.draws));
        }
        return out;
    }

    /** Called from a worker thread. */
    void save() throws IOException {
        String json;
        synchronized (this) {
            List<Object> list = new ArrayList<>();
            for (Entry e : entries.values()) {
                list.add(Json.obj("name", e.name, "elo", e.elo, "w", e.wins, "l", e.losses, "d", e.draws));
            }
            json = Json.stringify(list);
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("leaderboard.json.tmp");
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    @SuppressWarnings("unchecked")
    private synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Object o = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            for (Object item : (List<Object>) o) {
                Map<String, Object> m = (Map<String, Object>) item;
                Entry e = new Entry(Json.str(m, "name", "?"));
                e.elo = (int) Json.num(m, "elo", 1200);
                e.wins = (int) Json.num(m, "w", 0);
                e.losses = (int) Json.num(m, "l", 0);
                e.draws = (int) Json.num(m, "d", 0);
                entries.put(key(e.name), e);
            }
            System.out.println("[leaderboard] loaded " + entries.size() + " players");
        } catch (Exception ex) {
            System.err.println("[leaderboard] could not load: " + ex.getMessage());
        }
    }

    private static String key(String n) { return n.toLowerCase(Locale.ROOT); }
}
