package app.voltorb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Finds the DS screen in a camera frame and straightens it.
// The lit screen is the biggest bright blob, surrounded by the dark bezel, so:
//  1. downscale to <= 960 px and threshold on brightness
//  2. flood-fill the bright blobs and take each one's convex hull
//  3. simplify the hull down to a few corners (Douglas-Peucker)
// Vision picks the 4 corners from that, and warp() flattens the screen.
// Reads the NV21 camera frame directly: Y plane, then V/U pairs at half resolution.
final class ScreenFinder {
    private final byte[] yuv;
    private final int w, h;

    ScreenFinder(byte[] nv21, int w, int h) {
        this.yuv = nv21;
        this.w = w;
        this.h = h;
    }

    // Outlines of the big bright blobs, a few corners each, in frame px
    List<double[][]> outlines() {
        double k = Math.min(1, 960.0 / Math.max(w, h));   // 960 px: corners accurate to ~1 px
        int tw = (int) Math.round(w * k), th = (int) Math.round(h * k);

        // Brightness thumbnail + histogram
        int[] v = new int[tw * th], rgb = new int[3], hist = new int[256];
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                pixel((int) ((x + 0.5) / k), (int) ((y + 0.5) / k), rgb);
                int b = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
                v[y * tw + x] = b;
                hist[b]++;
            }
        }

        // Bright = over 35% of the 95th percentile, so it adapts to exposure
        int p95 = 0;
        for (int count = 0; p95 < 255 && (count += hist[p95]) < 0.95 * v.length; ) p95++;
        boolean[] bright = new boolean[v.length];
        for (int i = 0; i < v.length; i++) bright[i] = v[i] > 0.35 * p95;
        bright = erode(dilate(bright, tw, th), tw, th);  // fill small dark gaps

        List<double[][]> out = new ArrayList<>();
        boolean[] seen = new boolean[v.length];
        int[] queue = new int[v.length];
        for (int seed = 0; seed < v.length; seed++) {
            if (!bright[seed] || seen[seed]) continue;
            List<double[]> edge = new ArrayList<>();
            long area = region(bright, seen, queue, tw, th, seed, edge);
            if (area <= 0.05 * v.length) continue;        // too small to be the screen
            double[][] hull = hull(edge);
            double[][] poly = simplify(hull, 0.01 * perimeter(hull));
            for (double[] p : poly) {                     // back to frame coordinates
                p[0] = (p[0] + 0.5) / k - 0.5;
                p[1] = (p[1] + 0.5) / k - 0.5;
            }
            out.add(poly);
        }
        return out;
    }

    // Flood-fills the blob at `seed`. Only each row's leftmost and rightmost pixel go into
    // `edge`, that's all the hull needs. Returns the blob's area (holes included).
    private static long region(boolean[] bright, boolean[] seen, int[] queue, int w, int h, int seed, List<double[]> edge) {
        int[] minX = new int[h], maxX = new int[h];
        Arrays.fill(minX, Integer.MAX_VALUE);
        Arrays.fill(maxX, -1);
        int head = 0, tail = 0;
        queue[tail++] = seed;
        seen[seed] = true;
        while (head < tail) {
            int p = queue[head++], px = p % w, py = p / w;
            minX[py] = Math.min(minX[py], px);
            maxX[py] = Math.max(maxX[py], px);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = px + dx, ny = py + dy, n = ny * w + nx;
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h || !bright[n] || seen[n]) continue;
                    seen[n] = true;
                    queue[tail++] = n;
                }
            }
        }
        long area = 0;
        for (int y = 0; y < h; y++) {
            if (maxX[y] < 0) continue;
            area += maxX[y] - minX[y] + 1;
            edge.add(new double[]{minX[y], y});
            if (maxX[y] != minX[y]) edge.add(new double[]{maxX[y], y});
        }
        return area;
    }

    // bright if anything within 2 px is
    private static boolean[] dilate(boolean[] img, int w, int h) { return morph(img, w, h, true); }

    // bright only if everything within 2 px is
    private static boolean[] erode(boolean[] img, int w, int h) { return morph(img, w, h, false); }

    // 5x5 any/all filter, done as a horizontal then a vertical pass
    private static boolean[] morph(boolean[] in, int w, int h, boolean any) {
        boolean[] tmp = new boolean[in.length], out = new boolean[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean r = !any;
                for (int xx = Math.max(0, x - 2); xx <= Math.min(w - 1, x + 2); xx++)
                    r = any ? r || in[y * w + xx] : r && in[y * w + xx];
                tmp[y * w + x] = r;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean r = !any;
                for (int yy = Math.max(0, y - 2); yy <= Math.min(h - 1, y + 2); yy++)
                    r = any ? r || tmp[yy * w + x] : r && tmp[yy * w + x];
                out[y * w + x] = r;
            }
        }
        return out;
    }

    // Convex hull (Andrew's monotone chain)
    private static double[][] hull(List<double[]> points) {
        double[][] p = points.toArray(new double[0][]);
        Arrays.sort(p, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = p.length, k = 0;
        if (n < 3) return p;
        double[][] hull = new double[2 * n][];
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p[i]) <= 0) k--;
            hull[k++] = p[i];
        }
        for (int i = n - 2, lowerSize = k + 1; i >= 0; i--) {
            while (k >= lowerSize && cross(hull[k - 2], hull[k - 1], p[i]) <= 0) k--;
            hull[k++] = p[i];
        }
        return Arrays.copyOf(hull, k - 1);               // drop the repeated first point
    }

    // > 0 if o -> a -> b turns left
    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private static double perimeter(double[][] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) s += dist(p[i], p[(i + 1) % p.length]);
        return s;
    }

    // Douglas-Peucker on a closed polygon: drop points within eps of the simplified outline.
    // Starts from two far-apart points like OpenCV does (hop to the farthest point 3 times).
    // For a quadrilateral those are opposite corners, so the real corners survive; starting
    // from an arbitrary point can leave a vertex a few px off a corner.
    private static double[][] simplify(double[][] p, double eps) {
        int n = p.length;
        if (n < 4) return p;
        int a = 0, b = 0;
        for (int hop = 0; hop < 3; hop++) {
            int far = a;
            for (int i = 0; i < n; i++) if (dist(p[i], p[a]) > dist(p[far], p[a])) far = i;
            b = a;
            a = far;
        }
        double[][] q = new double[n][];                  // rotate so they're at 0 and b
        for (int i = 0; i < n; i++) q[i] = p[(a + i) % n];
        b = (b - a + n) % n;

        boolean[] keep = new boolean[n];
        keep[0] = keep[b] = true;
        keepFarthest(q, 0, b, eps, keep);
        keepFarthest(q, b, n, eps, keep);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(q[i]);
        return out.toArray(new double[0][]);
    }

    // Keep the point farthest from line a-b if it's over eps, then recurse on both halves.
    // b can be n, meaning point 0.
    private static void keepFarthest(double[][] p, int a, int b, double eps, boolean[] keep) {
        double[] A = p[a], B = p[b % p.length];
        double len = dist(A, B), maxDist = 0;
        int farthest = -1;
        for (int i = a + 1; i < b; i++) {
            double d = len == 0 ? dist(p[i], A) : Math.abs(cross(A, B, p[i])) / len;
            if (d > maxDist) {
                maxDist = d;
                farthest = i;
            }
        }
        if (farthest < 0 || maxDist <= eps) return;
        keep[farthest] = true;
        keepFarthest(p, a, farthest, eps, keep);
        keepFarthest(p, farthest, b, eps, keep);
    }

    // Screen corners (TL, TR, BR, BL in frame px) -> flat RGBA image
    byte[] warp(double[][] corners) {
        int ow = Reader.VIEW_W, oh = Reader.VIEW_H;
        double[] m = homography(new double[][]{{0, 0}, {ow, 0}, {ow, oh}, {0, oh}}, corners);
        byte[] out = new byte[ow * oh * 4];
        int[] rgb = new int[3];
        for (int oy = 0; oy < oh; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                int r = 0, g = 0, b = 0;
                for (int s = 0; s < 4; s++) {           // 2x2 samples per pixel
                    double u = ox + (s & 1) * 0.5, v = oy + (s >> 1) * 0.5, d = m[6] * u + m[7] * v + 1;
                    sample((m[0] * u + m[1] * v + m[2]) / d, (m[3] * u + m[4] * v + m[5]) / d, rgb);
                    r += rgb[0];
                    g += rgb[1];
                    b += rgb[2];
                }
                int i = 4 * (oy * ow + ox);
                out[i] = (byte) (r / 4);
                out[i + 1] = (byte) (g / 4);
                out[i + 2] = (byte) (b / 4);
                out[i + 3] = (byte) 255;
            }
        }
        return out;
    }

    // Perspective transform taking src[i] to dst[i]:
    //   x' = (m0 x + m1 y + m2) / (m6 x + m7 y + 1)
    //   y' = (m3 x + m4 y + m5) / (m6 x + m7 y + 1)
    // Each point pair gives 2 linear equations, so 8 for m0..m7. Plain Gaussian elimination.
    static double[] homography(double[][] src, double[][] dst) {
        double[][] a = new double[8][];
        for (int i = 0; i < 4; i++) {
            double x = src[i][0], y = src[i][1], X = dst[i][0], Y = dst[i][1];
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -x * X, -y * X, X};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -x * Y, -y * Y, Y};
        }
        for (int c = 0; c < 8; c++) {
            int pivot = c;                                // partial pivoting
            for (int r = c + 1; r < 8; r++) if (Math.abs(a[r][c]) > Math.abs(a[pivot][c])) pivot = r;
            double[] t = a[c];
            a[c] = a[pivot];
            a[pivot] = t;
            for (int r = 0; r < 8; r++) {
                if (r == c || a[c][c] == 0) continue;
                double f = a[r][c] / a[c][c];
                for (int j = c; j < 9; j++) a[r][j] -= f * a[c][j];
            }
        }
        double[] m = new double[8];
        for (int i = 0; i < 8; i++) m[i] = a[i][8] / a[i][i];
        return m;
    }

    // Color at a sub-pixel position (bilinear luma, nearest chroma), black outside
    private void sample(double x, double y, int[] rgb) {
        if (x < 0 || y < 0 || x > w - 1 || y > h - 1) {
            rgb[0] = rgb[1] = rgb[2] = 0;
            return;
        }
        int x0 = (int) x, y0 = (int) y, x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
        double fx = x - x0, fy = y - y0;
        double top = (1 - fx) * luma(x0, y0) + fx * luma(x1, y0);
        double bottom = (1 - fx) * luma(x0, y1) + fx * luma(x1, y1);
        toRgb((1 - fy) * top + fy * bottom, (int) Math.round(x), (int) Math.round(y), rgb);
    }

    private void pixel(int x, int y, int[] rgb) {
        x = Math.min(x, w - 1);
        y = Math.min(y, h - 1);
        toRgb(luma(x, y), x, y, rgb);
    }

    private int luma(int x, int y) {
        return yuv[y * w + x] & 255;
    }

    // Y + chroma at (x, y) -> RGB, BT.601 video range like Android cameras
    private void toRgb(double Y, int x, int y, int[] rgb) {
        int c = w * h + Math.min(y, h - 1) / 2 * w + Math.min(x, w - 1) / 2 * 2;
        double V = (yuv[c] & 255) - 128, U = (yuv[c + 1] & 255) - 128, L = 1.164 * (Y - 16);
        rgb[0] = clamp(L + 1.596 * V);
        rgb[1] = clamp(L - 0.813 * V - 0.391 * U);
        rgb[2] = clamp(L + 2.018 * U);
    }

    private static int clamp(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) (v + 0.5);
    }
}
