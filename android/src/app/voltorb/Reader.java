package app.voltorb;

// Reads clues and flipped cards off the straightened bottom screen (256x192, upscaled 2x).
// Digits are matched against the game's pixel font: correlate the patch with each glyph,
// at a few small offsets to absorb alignment errors, and take the best.
public final class Reader {
    public static final int SCREEN_W = 256, SCREEN_H = 192, SCALE = 2;
    public static final int VIEW_W = SCREEN_W * SCALE, VIEW_H = SCREEN_H * SCALE;

    private static final int CELL = 32;            // card spacing, screen px
    private static final int CARD_HALF = 7;        // half size of the area checked per card
    private static final int SHIFT = 3;            // search +-1.5 screen px around each digit

    // Top-left of the card grid for the two layouts seen so far: board on the right of a
    // coins panel, and HGSS (board on the left, Open/Memo on the right).
    private static final int[][] LAYOUTS = {{64, 0}, {4, 4}};

    // Clue font, 6x8 px: one byte per column, left to right, bit 0 = top row, 1 = ink
    private static final int[][] DIGITS = {
        {0x7E, 0xFF, 0xC3, 0xC3, 0xFF, 0x7E},  // 0
        {0x00, 0xC6, 0xFF, 0xFF, 0xC0, 0x00},  // 1
        {0xC6, 0xE7, 0xF3, 0xFB, 0xDF, 0xCE},  // 2
        {0x66, 0xE7, 0xDB, 0xDB, 0xFF, 0x7E},  // 3
        {0x3F, 0x3F, 0x30, 0xFE, 0xFE, 0x30},  // 4
        {0x6F, 0xEF, 0xCB, 0xCB, 0xFB, 0x73},  // 5
        {0x7E, 0xFF, 0xDB, 0xDB, 0xFB, 0x72},  // 6
        {0x07, 0x07, 0xF3, 0xFB, 0x0F, 0x07},  // 7
        {0x7E, 0xFF, 0xDB, 0xDB, 0xFF, 0x7E},  // 8
        {0x4E, 0xDF, 0xDB, 0xDB, 0xFF, 0x7E},  // 9, drawn by hand (not seen yet)
    };
    private static final int GLYPH_W = 6, GLYPH_H = 8;

    // DIGITS at view scale, normalized so matching is a plain dot product
    private static final double[][] TEMPLATES = new double[10][];

    static {
        for (int d = 0; d < 10; d++) {
            double[] t = new double[GLYPH_W * GLYPH_H * SCALE * SCALE];
            for (int y = 0; y < GLYPH_H * SCALE; y++) {
                for (int x = 0; x < GLYPH_W * SCALE; x++) {
                    boolean ink = (DIGITS[d][x / SCALE] >> (y / SCALE) & 1) == 1;
                    t[y * GLYPH_W * SCALE + x] = ink ? 0 : 1;   // ink is dark
                }
            }
            TEMPLATES[d] = normalize(t);
        }
    }

    // One reading of the screen; clues are 0-4 rows, 5-9 columns
    public static final class Result {
        public final int[] sums = new int[10], volts = new int[10];
        // 0 = Voltorb, 1-3 = points, -1 = face down or unreadable
        public int[][] cards;
        // Worst digit match (1 = perfect), over all clues and over the row clues only
        public double score = 1, rowScore = 1;
        // Row totals equal column totals, as on any real board: a free sanity check
        public boolean consistent;
        int[] origin;

        // To compare readings: consistent ones win, then the better score
        double rank() { return consistent ? 2 + score : rowScore; }
    }

    // Tries each layout, keeps the best clue reading, then reads the cards
    public static Result read(byte[] rgba) {
        int[] brightness = brightness(rgba);
        Result best = null;
        for (int[] origin : LAYOUTS) {
            Result r = readClues(brightness, origin);
            if (best == null || r.rank() > best.rank()) best = r;
        }
        best.cards = readCards(brightness, rgba, best.origin);
        return best;
    }

    // Clue box = 2-digit points (tens is 0 or 1) over the Voltorb count (0-5).
    // Offsets from the grid origin were measured on screenshots.
    private static Result readClues(int[] img, int[] origin) {
        Result res = new Result();
        res.origin = origin;
        for (int i = 0; i < 10; i++) {
            int x, y, vx, vy;                      // top-left of the tens digit / Voltorb digit
            if (i < 5) {                           // right of row i
                x = origin[0] + 174;
                y = origin[1] + 4 + CELL * i;
                vx = origin[0] + 182;
                vy = origin[1] + 17 + CELL * i;
            } else {                               // below column i - 5
                x = origin[0] + 13 + CELL * (i - 5);
                y = origin[1] + 165;
                vx = origin[0] + 21 + CELL * (i - 5);
                vy = origin[1] + 178;
            }
            Match tens = match(img, x, y, 0, 1), ones = match(img, x + 8, y, 0, 9), volt = match(img, vx, vy, 0, 5);
            res.sums[i] = tens.digit * 10 + ones.digit;
            res.volts[i] = volt.digit;
            res.score = Math.min(res.score, Math.min(tens.score, Math.min(ones.score, volt.score)));
            if (i < 5) res.rowScore = res.score;
        }
        int points = 0, voltorbs = 0;
        for (int i = 0; i < 5; i++) {
            points += res.sums[i] - res.sums[5 + i];
            voltorbs += res.volts[i] - res.volts[5 + i];
        }
        res.consistent = points == 0 && voltorbs == 0;
        return res;
    }

    // Looks at the middle of each card: mostly green = face down, red = Voltorb,
    // otherwise try to read a 1-3 (same font as the clues).
    private static int[][] readCards(int[] img, byte[] rgba, int[] origin) {
        int[][] cards = new int[5][5];
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 5; c++) {
                int cx = origin[0] + CELL * c + CELL / 2, cy = origin[1] + CELL * r + CELL / 2;
                int green = 0, red = 0, n = 0;
                for (int y = (cy - CARD_HALF) * SCALE; y < (cy + CARD_HALF) * SCALE; y++) {
                    for (int x = (cx - CARD_HALF) * SCALE; x < (cx + CARD_HALF) * SCALE; x++) {
                        int i = 4 * (y * VIEW_W + x);
                        int R = rgba[i] & 255, G = rgba[i + 1] & 255, B = rgba[i + 2] & 255;
                        if (G > R + 20 && G >= B - 10) green++;
                        if (R > 1.6 * G && R > 1.6 * B && R > 100) red++;
                        n++;
                    }
                }
                if (green > 0.4 * n) {
                    cards[r][c] = Solver.UNKNOWN;
                } else if (red > 0.12 * n) {
                    cards[r][c] = Solver.VOLTORB;
                } else {
                    Match m = match(img, cx - GLYPH_W / 2, cy - GLYPH_H / 2, 1, 3);
                    cards[r][c] = m.score > 0.5 ? m.digit : Solver.UNKNOWN;
                }
            }
        }
        return cards;
    }

    private static final class Match {
        final int digit;
        final double score;

        Match(int digit, double score) {
            this.digit = digit;
            this.score = score;
        }
    }

    // Best digit in [lo, hi] for the glyph with top-left near (x, y), screen px
    private static Match match(int[] img, int x, int y, int lo, int hi) {
        int w = GLYPH_W * SCALE, h = GLYPH_H * SCALE;
        double[] patch = new double[w * h];
        int bestDigit = lo;
        double bestScore = -1;
        for (int dy = -SHIFT; dy <= SHIFT; dy++) {
            for (int dx = -SHIFT; dx <= SHIFT; dx++) {
                for (int py = 0; py < h; py++) {
                    for (int px = 0; px < w; px++) {
                        int ix = x * SCALE + dx + px, iy = y * SCALE + dy + py;
                        boolean inside = ix >= 0 && ix < VIEW_W && iy >= 0 && iy < VIEW_H;
                        patch[py * w + px] = inside ? img[iy * VIEW_W + ix] : 0;
                    }
                }
                double[] p = normalize(patch.clone());
                for (int d = lo; d <= hi; d++) {
                    double score = 0;
                    for (int i = 0; i < p.length; i++) score += p[i] * TEMPLATES[d][i];
                    if (score > bestScore) {
                        bestScore = score;
                        bestDigit = d;
                    }
                }
            }
        }
        return new Match(bestDigit, bestScore);
    }

    // Zero mean, unit length, in place. The dot product of two of these is their correlation,
    // so matching doesn't care about brightness or contrast.
    private static double[] normalize(double[] a) {
        double mean = 0, len = 0;
        for (double x : a) mean += x;
        mean /= a.length;
        for (int i = 0; i < a.length; i++) {
            a[i] -= mean;
            len += a[i] * a[i];
        }
        len = Math.sqrt(len);
        if (len > 0) for (int i = 0; i < a.length; i++) a[i] /= len;
        return a;
    }

    // max(R,G,B): dark ink stands out on any of the colored backgrounds
    private static int[] brightness(byte[] rgba) {
        int[] v = new int[rgba.length / 4];
        for (int i = 0; i < v.length; i++)
            v[i] = Math.max(rgba[4 * i] & 255, Math.max(rgba[4 * i + 1] & 255, rgba[4 * i + 2] & 255));
        return v;
    }
}
