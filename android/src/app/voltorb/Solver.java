package app.voltorb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Voltorb Flip solver: exact odds of each card being V, 1, 2 or 3.
//
// Code borrowed from voltorbflip.com (https://github.com/mrtenda/voltorbflipdotcom): every
// board that fits the clues and the flipped cards is equally likely, so P(card = 2) is just
// the fraction of fitting boards where that card is a 2.
//
// We calculate all possible boards and calculate the probabilities for earch cell:
//  1. For each row, generate all the ways to fill it that match its clue ("row options").
//  2. Fill the board top to bottom. After each row, we track the column requirements for the
//     rows that are still to be filled (class Need). We prune early if a Need is impossible.
//  3. In many cases the remaining state for the N first rows, will not matter much (due to the
//     possible arrangements being similar and equivalent), so we index these "Needs" and cache
//     them (class Counter).
//  4. Forward pass: count the ways to reach each state. Picking a row option from a state then
//     appears in (ways to get here) * (ways to finish) boards, which we add to its 5 cards.
//     Divide by the total and we have the odds.
public final class Solver {
    public static final int VOLTORB = 0, UNKNOWN = -1;

    // number of boards that fit
    public final long boards;
    // chance[row][col][value], value 0 = Voltorb
    public final double[][][] chance;

    private Solver(long boards, double[][][] chance) {
        this.boards = boards;
        this.chance = chance;
    }

    // Clues are 0-4 rows, 5-9 columns; known holds flipped cards or UNKNOWN.
    // Returns null if no board fits (usually a misread clue).
    public static Solver solve(int[] sums, int[] volts, int[][] known) {
        // Computes all the possible permutations for each row (ignoring column restrictions).
        List<List<int[]>> options = new ArrayList<>();
        for (int r = 0; r < 5; r++)
            options.add(rowOptions(sums[r], volts[r], known[r]));

        Need start = new Need(Arrays.copyOfRange(sums, 5, 10), Arrays.copyOfRange(volts, 5, 10));
        Counter counter = new Counter(options);
        long boards = counter.completions(0, start);
        if (boards == 0)
            return null;

        long[][][] counts = new long[5][5][4];
        Map<Need, Long> ways = new HashMap<>();      // ways to reach each state before row r
        ways.put(start, 1L);
        for (int r = 0; r < 5; r++) {
            Map<Need, Long> next = new HashMap<>();
            for (Map.Entry<Need, Long> e : ways.entrySet()) {
                for (int[] row : options.get(r)) {
                    Need after = e.getKey().after(row);
                    if (after == null)
                        continue;
                    long finishes = counter.completions(r + 1, after);
                    if (finishes == 0) continue;
                    double n = (double) e.getValue() * finishes;
                    for (int c = 0; c < 5; c++)
                        counts[r][c][row[c]] += n;
                    next.merge(after, e.getValue(), Long::sum);
                }
            }
            ways = next;
        }

        double[][][] probs = new double[5][5][4];
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 5; c++)
                for (int v = 0; v < 4; v++)
                    probs[r][c][v] = counts[r][c][v] / boards;

        return new Solver(boards, probs);
    }

    // Safest useful card: lowest Voltorb odds among cards that can still be a 2 or 3,
    // ties go to the best 2/3 odds. Null when there's nothing left worth flipping (round won).
    public int[] bestFlip(int[][] known) {
        int[] best = null;
        double bestVoltorb = 2, bestGain = 0;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                double[] p = chance[r][c];
                double gain = p[2] + p[3];
                if (known[r][c] != UNKNOWN || gain == 0) continue;
                if (p[0] < bestVoltorb || (p[0] == bestVoltorb && gain > bestGain)) {
                    bestVoltorb = p[0];
                    bestGain = gain;
                    best = new int[]{r, c};
                }
            }
        }
        return best;
    }

    // Every way to fill a row that matches its clue and its flipped cards
    private static List<int[]> rowOptions(int sum, int voltorbs, int[] known) {
        List<int[]> out = new ArrayList<>();
        addRows(new int[5], 0, sum, voltorbs, known, out);
        return out;
    }

    // Recursively fills row[i..4] using up exactly the remaining points and voltorbs
    private static void addRows(int[] row, int i, int points, int voltorbs, int[] known, List<int[]> out) {
        if (i == 5) {
            if (points == 0 && voltorbs == 0) out.add(row.clone());
            return;
        }
        for (int v = 0; v < 4; v++) {
            if (known[i] != UNKNOWN && known[i] != v) continue;
            int p = points - v, vo = voltorbs - (v == VOLTORB ? 1 : 0);
            if (p < 0 || vo < 0) continue;
            row[i] = v;
            addRows(row, i + 1, p, vo, known, out);
        }
    }

    // Hold state that represents the remaining needs for each column.
    // (ie. for the rows that are still not complete).
    private static final class Need {
        final int[] points, voltorbs;
        private final int hash;

        Need(int[] points, int[] voltorbs) {
            this.points = points;
            this.voltorbs = voltorbs;
            this.hash = 31 * Arrays.hashCode(points) + Arrays.hashCode(voltorbs);
        }

        // After setting a row returns null if a column wouldn't possible agree.
        Need after(int[] row) {
            int[] p = new int[5], v = new int[5];
            for (int c = 0; c < 5; c++) {
                p[c] = points[c] - row[c];
                v[c] = voltorbs[c] - (row[c] == VOLTORB ? 1 : 0);
                if (p[c] < 0 || v[c] < 0)
                    return null;
            }
            return new Need(p, v);
        }

        // Can "rows" more cards per column still add up exactly? Prunes dead ends early.
        boolean reachable(int rows) {
            for (int c = 0; c < 5; c++) {
                int pointCards = rows - voltorbs[c];       // the other cards are 1, 2 or 3
                if (pointCards < 0 || points[c] < pointCards || points[c] > 3 * pointCards)
                    return false;
            }
            return true;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Need && Arrays.equals(points, ((Need) o).points) && Arrays.equals(voltorbs, ((Need) o).voltorbs);
        }

        @Override public int hashCode() { return hash; }
    }

    // Counts the ways to finish the board, cached per row and state
    private static final class Counter {
        private final List<List<int[]>> options;
        private final List<Map<Need, Long>> cache = new ArrayList<>();

        Counter(List<List<int[]>> options) {
            this.options = options;
            for (int r = 0; r < 5; r++)
                cache.add(new HashMap<>());
        }

        // Ways to fill rows r..4 so every column ends up exactly at "need"
        long completions(int r, Need need) {
            if (!need.reachable(5 - r))
                return 0;
            if (r == 5)
                return 1;
            Long cached = cache.get(r).get(need);
            if (cached != null)
                return cached;
            long n = 0;
            for (int[] row : options.get(r)) {
                Need after = need.after(row);
                if (after != null) n += completions(r + 1, after);
            }
            cache.get(r).put(need, n);
            return n;
        }
    }
}
