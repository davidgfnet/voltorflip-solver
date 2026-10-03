package app.voltorb;

import java.util.Arrays;

// Camera frame -> board reading. Tries every screen-shaped outline, both ways up, and keeps
// the best reading, so it doesn't matter how the phone is held.
public final class Vision {
    public static final class Frame {
        // null if nothing screen-like was found
        public Reader.Result reading;
        // the straightened screen it came from (RGBA)
        public byte[] view;

        // all clues read fine and add up
        public boolean found() { return reading != null && reading.consistent && reading.score > 0.35; }

        // bottom clues hidden (message box), but row clues and cards are readable
        public boolean partial() { return reading != null && !found() && reading.rowScore > 0.5; }
    }

    // nv21 camera frame, any orientation
    public static Frame analyze(byte[] nv21, int w, int h) {
        Frame best = new Frame();
        ScreenFinder finder = new ScreenFinder(nv21, w, h);
        for (double[][] outline : finder.outlines()) {
            double[][] quad = corners(outline);
            if (quad == null || !screenShaped(quad)) continue;
            for (double[][] corners : orientations(quad)) {
                byte[] view = finder.warp(corners);
                Reader.Result r = Reader.read(view);
                if (best.reading == null || r.rank() > best.reading.rank()) {
                    best.reading = r;
                    best.view = view;
                }
            }
        }
        return best;
    }

    // DS screens are 4:3, give or take perspective; skips the bezel, the table, etc.
    static boolean screenShaped(double[][] q) {
        double a = dist(q[0], q[1]) + dist(q[2], q[3]), b = dist(q[1], q[2]) + dist(q[3], q[0]);
        double ratio = Math.max(a, b) / Math.min(a, b);
        return ratio <= 1.8;
    }

    // Corners = where the 4 longest edges meet. Still works if a corner is cut off
    // by the edge of the photo (the outline then has 5+ points).
    static double[][] corners(double[][] p) {
        int n = p.length;
        if (n < 4) return null;
        Integer[] byLength = new Integer[n];
        for (int i = 0; i < n; i++)
            byLength[i] = i;
        Arrays.sort(byLength, (a, b) -> Double.compare(edgeLength(p, b), edgeLength(p, a)));
        int[] edges = {byLength[0], byLength[1], byLength[2], byLength[3]};
        Arrays.sort(edges);                              // back to outline order
        double[][] q = new double[4][];
        for (int i = 0; i < 4; i++) {
            int e = edges[i], f = edges[(i + 1) % 4];
            q[i] = intersect(p[e], p[(e + 1) % n], p[f], p[(f + 1) % n]);
            if (q[i] == null) return null;
        }
        return q;
    }

    private static double edgeLength(double[][] p, int i) {
        return dist(p[i], p[(i + 1) % p.length]);
    }

    // Line a-b meets line c-d; null if parallel
    private static double[] intersect(double[] a, double[] b, double[] c, double[] d) {
        double rx = b[0] - a[0], ry = b[1] - a[1], sx = d[0] - c[0], sy = d[1] - c[1];
        double den = rx * sy - ry * sx;
        if (Math.abs(den) < 1e-6)
            return null;
        double t = ((c[0] - a[0]) * sy - (c[1] - a[1]) * sx) / den;
        return new double[]{a[0] + t * rx, a[1] + t * ry};
    }

    // Corners as TL, TR, BR, BL, both ways up: we can't tell top from bottom yet,
    // the reader decides.
    static double[][][] orientations(double[][] q) {
        double cx = 0, cy = 0;
        for (double[] p : q) {
            cx += p[0] / 4;
            cy += p[1] / 4;
        }
        final double fx = cx, fy = cy;
        double[][] s = q.clone();                        // sort clockwise
        Arrays.sort(s, (a, b) -> Double.compare(Math.atan2(a[1] - fy, a[0] - fx), Math.atan2(b[1] - fy, b[0] - fx)));
        if (dist(s[0], s[1]) < dist(s[1], s[2]))         // start on a long edge
            s = new double[][]{s[1], s[2], s[3], s[0]};
        return new double[][][]{s, {s[2], s[3], s[0], s[1]}};
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }
}
