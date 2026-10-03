package app.voltorb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

// The board as in the game, 5x5 cards plus clue boxes. Unflipped cards are split into one
// tile per possible value, each with its odds.
public final class BoardView extends View {
    public interface Listener {
        void onCard(int row, int col);
        // 0-4 rows, 5-9 columns
        void onClue(int i);
    }

    private static final int[] CLUE_COLORS = {0xFFE07050, 0xFF4AA04A, 0xFFE8A040, 0xFF3A80E8, 0xFFB050E0};
    private static final int[] VALUE_COLORS = {0xFFD64034, 0xFF969696, 0xFF3A80E8, 0xFFE8A040};   // V, 1, 2, 3
    private static final String[] LABELS = {"V", "1", "2", "3"};
    private static final int CARD = 0xFF2A2E33, FLIPPED = 0xFFA87D6E, CERTAIN = 0xFF2E9D55, BEST = 0xFFF5B700;

    // set by the activity, -1 = unknown
    int[] sums, volts;
    int[][] known;
    Solver solver;
    int[] best;
    Listener listener;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp = getResources().getDisplayMetrics().density;

    public BoardView(Context c) {
        super(c);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    // always square
    @Override protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        setMeasuredDimension(size, size);
    }

    private float gap() { return 4 * dp; }

    private float cellSize() { return (getWidth() - 5 * gap()) / 6f; }

    // row/col 5 are the clue boxes
    private RectF cell(int row, int col) {
        float s = cellSize(), x = col * (s + gap()), y = row * (s + gap());
        return new RectF(x, y, x + s, y + s);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (known == null)
            return;
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 6; c++) {
                if (r < 5 && c < 5) drawCard(canvas, cell(r, c), r, c);
                else if (r < 5) drawClue(canvas, cell(r, c), r, r);
                else if (c < 5) drawClue(canvas, cell(r, c), 5 + c, c);
            }
        }
    }

    // Flipped: its value. Otherwise a tile per possible value, or green (red for V) if certain.
    private void drawCard(Canvas canvas, RectF b, int r, int c) {
        float radius = 6 * dp;
        int value = known[r][c];
        if (value != Solver.UNKNOWN) {
            box(canvas, b, value == Solver.VOLTORB ? VALUE_COLORS[0] : FLIPPED, radius);
            label(canvas, b, LABELS[value], 0.5f, Color.WHITE);
            return;
        }
        if (solver == null) {
            box(canvas, b, CARD, radius);
            return;
        }

        double[] p = solver.chance[r][c];
        int n = 0;
        int[] possible = new int[4];
        for (int v = 0; v < 4; v++) if (p[v] > 0) possible[n++] = v;

        boolean useless = p[2] + p[3] == 0;           // only 1 or V left: no point flipping
        if (useless) canvas.saveLayerAlpha(b, 115);
        if (n == 1) {
            box(canvas, b, possible[0] == Solver.VOLTORB ? VALUE_COLORS[0] : CERTAIN, radius);
            label(canvas, b, LABELS[possible[0]], 0.5f, Color.WHITE);
        } else {
            box(canvas, b, CARD, radius);
            for (int i = 0; i < n; i++) {
                int v = possible[i];
                RectF tile = tile(b, n, i);
                fill.setColor(VALUE_COLORS[v]);
                fill.setAlpha((int) (255 * (0.15 + 0.6 * p[v])));   // more likely, stronger color
                canvas.drawRoundRect(tile, radius / 2, radius / 2, fill);
                label(canvas, tile, LABELS[v] + " " + percent(p[v]), n == 4 ? 0.38f : 0.55f, Color.WHITE);
            }
        }
        if (useless) canvas.restore();

        if (best != null && best[0] == r && best[1] == c) {
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(3 * dp);
            fill.setColor(BEST);
            canvas.drawRoundRect(b, radius, radius, fill);
            fill.setStyle(Paint.Style.FILL);
        }
    }

    // Stacked rows for 2-3 values, 2x2 for 4
    private static RectF tile(RectF b, int n, int i) {
        float w = b.width(), h = b.height(), gap = 1;
        if (n == 4) {
            float x = b.left + (i % 2) * w / 2, y = b.top + (i / 2) * h / 2;
            return new RectF(x + gap, y + gap, x + w / 2 - gap, y + h / 2 - gap);
        }
        float y = b.top + i * h / n;
        return new RectF(b.left + gap, y + gap, b.right - gap, y + h / n - gap);
    }

    // Points on top, Voltorbs on the lighter bottom half
    private void drawClue(Canvas canvas, RectF b, int i, int color) {
        float radius = 6 * dp;
        box(canvas, b, CLUE_COLORS[color], radius);
        RectF top = new RectF(b.left, b.top, b.right, b.centerY());
        RectF bottom = new RectF(b.left, b.centerY(), b.right, b.bottom);
        canvas.save();
        canvas.clipRect(bottom);
        box(canvas, b, 0x8CFFFFFF, radius);
        canvas.restore();
        int unset = 0x66000000;
        label(canvas, top, sums[i] < 0 ? "pts" : String.format("%02d", sums[i]), 0.55f, sums[i] < 0 ? unset : Color.BLACK);
        label(canvas, bottom, volts[i] < 0 ? "V" : "V" + volts[i], 0.55f, volts[i] < 0 ? unset : Color.BLACK);
    }

    private void box(Canvas canvas, RectF b, int color, float radius) {
        fill.setColor(color);
        canvas.drawRoundRect(b, radius, radius, fill);
    }

    // Centered, scaled to the box, shrunk until it fits
    private void label(Canvas canvas, RectF b, String s, float size, int color) {
        text.setColor(color);
        text.setTextSize(Math.min(b.height(), cellSize()) * size);
        while (text.measureText(s) > b.width() * 0.92f) text.setTextSize(text.getTextSize() * 0.9f);
        canvas.drawText(s, b.centerX(), b.centerY() - (text.descent() + text.ascent()) / 2, text);
    }

    private static String percent(double x) {
        return x < 0.005 ? "<1%" : Math.round(x * 100) + "%";
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP || listener == null) return false;
        float pitch = cellSize() + gap();
        int c = (int) (e.getX() / pitch), r = (int) (e.getY() / pitch);
        if (r < 5 && c < 5) listener.onCard(r, c);
        else if (r < 5 && c == 5) listener.onClue(r);
        else if (r == 5 && c < 5) listener.onClue(5 + c);
        performClick();
        return true;
    }

    @Override public boolean performClick() {
        return super.performClick();
    }
}
