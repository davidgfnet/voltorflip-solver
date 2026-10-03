package app.voltorb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Arrays;

// The only screen: camera panel, board and advice. The board gets filled from the camera
// or by hand (tap a card or a clue).
// No anonymous/inner classes on purpose: javac 21+ gives their constructors nameless
// parameters and d8 from build-tools 34 crashes on them. Lambdas and static classes are fine.
public final class MainActivity extends Activity implements BoardView.Listener, TextureView.SurfaceTextureListener {
    private static final int BACKGROUND = 0xFF17191C, TEXT = 0xFFEEEEEE, MUTED = 0xFF999999;
    private static final int GOOD = 0xFF2E9D55, BAD = 0xFFD64034;

    // Clues (0-4 rows, 5-9 columns) and flipped cards, -1 = unknown
    private final int[] sums = new int[10], volts = new int[10];
    private final int[][] known = new int[5][5];

    private BoardView board;
    private TextView advice, status, evLabel;
    private View cameraPanel;
    private RatioTexture preview;
    private RatioImage screenView;
    private Button cameraButton;

    // Camera runs on cameraThread, frames get analysed on analysisThread
    private boolean cameraOn;
    private HandlerThread cameraThread, analysisThread;
    private Handler cameraHandler, analysisHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader frames;
    private Surface previewSurface;
    private Size frameSize;
    private Range<Integer> evRange;
    private Rational evStep;
    private int ev;
    private byte[] nv21;
    private Reader.Result previous;      // last good reading, to require 2 matching frames

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        clearBoard();

        LinearLayout root = column();
        root.setPadding(dp(16), dp(12), dp(16), dp(24));

        LinearLayout top = row();
        top.addView(text("Voltorb Flip Solver", 18, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        cameraButton = button("Camera", v -> toggleCamera());
        top.addView(cameraButton);
        top.addView(button("New", v -> {
            clearBoard();
            update();
        }));
        root.addView(top);

        // Camera panel: preview | straightened screen, then status and exposure
        LinearLayout panel = column();
        LinearLayout feeds = row();
        preview = new RatioTexture(this, 4f / 3);         // portrait, taller than wide
        screenView = new RatioImage(this, 3f / 4);
        screenView.setScaleType(ImageView.ScaleType.FIT_XY);
        screenView.setBackgroundColor(0xFF000000);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        half.setMargins(0, 0, dp(6), 0);
        feeds.addView(preview, half);
        feeds.addView(screenView, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(feeds);
        LinearLayout bar = row();
        status = text("", 14, MUTED);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("−", v -> setEv(ev - 1)));
        evLabel = text("EV 0", 14, TEXT);
        evLabel.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(evLabel);
        bar.addView(button("+", v -> setEv(ev + 1)));
        panel.addView(bar);
        panel.setVisibility(View.GONE);
        cameraPanel = panel;
        root.addView(panel);

        board = new BoardView(this);
        board.listener = this;
        board.sums = sums;
        board.volts = volts;
        board.known = known;
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(-1, -2);
        boardParams.setMargins(0, dp(10), 0, 0);
        root.addView(board, boardParams);

        advice = text("", 15, TEXT);
        advice.setPadding(0, dp(12), 0, dp(8));
        root.addView(advice);
        root.addView(text("Each card lists its possible values and their chances. Green = certain. "
                + "Faded = only 1 or Voltorb left, no need to flip. Gold = best next flip. "
                + "Tap a card to enter what you flipped, a clue box to edit it.", 12, MUTED));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.addView(root);
        setContentView(scroll);
        update();
    }

    private void clearBoard() {
        Arrays.fill(sums, -1);
        Arrays.fill(volts, -1);
        clearCards();
        previous = null;
    }

    private void clearCards() {
        for (int[] r : known) Arrays.fill(r, Solver.UNKNOWN);
    }

    private boolean cluesComplete() {
        for (int i = 0; i < 10; i++) if (sums[i] < 0 || volts[i] < 0) return false;
        return true;
    }

    // Re-solve and redraw
    private void update() {
        Solver solver = cluesComplete() ? Solver.solve(sums, volts, known) : null;
        int[] best = solver == null ? null : solver.bestFlip(known);
        board.solver = solver;
        board.best = best;
        board.invalidate();

        boolean lost = false;
        for (int[] r : known) for (int v : r) lost |= v == Solver.VOLTORB;

        if (!cluesComplete()) {
            advice.setText("Enter all 10 clues (tap a clue box), or point the camera at the bottom screen.");
        } else if (solver == null) {
            advice.setText("No board matches these clues and cards. Check for a misread value.");
        } else if (lost) {
            advice.setText("Voltorb flipped. Tap New for the next round.");
        } else if (best == null) {
            advice.setText("All 2s and 3s found - round cleared!");
        } else {
            double[] p = solver.chance[best[0]][best[1]];
            advice.setText(String.format("Flip row %d, col %d:  V %s · 1: %s · 2: %s · 3: %s\n%,d possible boards",
                    best[0] + 1, best[1] + 1, percent(p[0]), percent(p[1]), percent(p[2]), percent(p[3]), solver.boards));
        }
    }

    private static String percent(double x) {
        return Math.round(x * 100) + "%";
    }

    // Ask what the card turned out to be
    @Override public void onCard(int r, int c) {
        new AlertDialog.Builder(this)
                .setTitle("Row " + (r + 1) + ", column " + (c + 1))
                .setItems(new String[]{"1", "2", "3", "Voltorb", "Unknown"}, (dialog, which) -> {
                    known[r][c] = which < 3 ? which + 1 : which == 3 ? Solver.VOLTORB : Solver.UNKNOWN;
                    update();
                })
                .show();
    }

    // Edit a clue. A changed clue means a new board, so the cards get cleared.
    @Override public void onClue(int i) {
        NumberPicker points = picker(15, Math.max(sums[i], 0));
        NumberPicker voltorbs = picker(5, Math.max(volts[i], 0));
        LinearLayout box = row();
        box.setGravity(Gravity.CENTER);
        box.addView(text("Points ", 16, TEXT));
        box.addView(points);
        box.addView(text("   Voltorbs ", 16, TEXT));
        box.addView(voltorbs);
        new AlertDialog.Builder(this)
                .setTitle((i < 5 ? "Row " + (i + 1) : "Column " + (i - 4)) + " clue")
                .setView(box)
                .setPositiveButton("OK", (dialog, which) -> {
                    if (sums[i] != points.getValue() || volts[i] != voltorbs.getValue()) clearCards();
                    sums[i] = points.getValue();
                    volts[i] = voltorbs.getValue();
                    update();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Called per analysed frame. Anything we take must show up in 2 frames in a row: all 10
    // clues for a new board, each card on its own. With the bottom clues hidden (message box)
    // we only take cards, and only if the row clues match the current board.
    private void onReading(Vision.Frame f) {
        if (!cameraOn) return;
        if (f.view != null) {
            Bitmap bmp = Bitmap.createBitmap(Reader.VIEW_W, Reader.VIEW_H, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(f.view));
            screenView.setImageBitmap(bmp);
        }

        Reader.Result r = f.reading;
        boolean full = f.found();
        boolean partial = f.partial() && cluesComplete() && rowCluesMatch(r);
        if (full) status.setText(String.format("Board found (%.2f)", r.score));
        else if (partial) status.setText("Partly hidden - updating cards");
        else if (r != null) status.setText(String.format("Low confidence (%.2f)", r.score));
        else status.setText("Not found");
        status.setTextColor(full || partial ? GOOD : BAD);

        boolean sameClues = full && previous != null && Arrays.equals(r.sums, previous.sums) && Arrays.equals(r.volts, previous.volts);
        if (sameClues) {
            if (!Arrays.equals(r.sums, sums) || !Arrays.equals(r.volts, volts)) {   // new board
                clearCards();
                System.arraycopy(r.sums, 0, sums, 0, 10);
                System.arraycopy(r.volts, 0, volts, 0, 10);
            }
            takeCards(r);
        } else if (partial && previous != null) {
            takeCards(r);
        }
        previous = full || partial ? r : null;
        update();
    }

    // Cards read the same in this and the previous frame
    private void takeCards(Reader.Result r) {
        for (int i = 0; i < 5; i++)
            for (int j = 0; j < 5; j++)
                if (r.cards[i][j] != Solver.UNKNOWN && r.cards[i][j] == previous.cards[i][j]) known[i][j] = r.cards[i][j];
    }

    private boolean rowCluesMatch(Reader.Result r) {
        for (int i = 0; i < 5; i++) if (r.sums[i] != sums[i] || r.volts[i] != volts[i]) return false;
        return true;
    }

    private void toggleCamera() {
        if (cameraOn) {
            stopCamera();
            cameraOn = false;
        } else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);   // continues in onRequestPermissionsResult
        } else {
            cameraOn = true;
            startCamera();
        }
        cameraPanel.setVisibility(cameraOn ? View.VISIBLE : View.GONE);
        cameraButton.setText(cameraOn ? "Stop" : "Camera");
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toggleCamera();
    }

    @Override protected void onResume() {
        super.onResume();
        if (cameraOn) startCamera();
    }

    @Override protected void onPause() {
        stopCamera();
        super.onPause();
    }

    // Start the threads; the camera opens once the preview surface is ready
    private void startCamera() {
        if (cameraThread != null) return;
        status.setText("Starting camera…");
        status.setTextColor(MUTED);
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        analysisThread = new HandlerThread("analysis");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
        if (preview.isAvailable()) openCamera();
        else preview.setSurfaceTextureListener(this);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}

    // Open the back camera; DeviceCallback takes it from there
    private void openCamera() {
        if (cameraThread == null) return;
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String id = null;
            for (String c : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    id = c;
                    break;
                }
            }
            if (id == null) {
                status.setText("No back camera");
                return;
            }
            CameraCharacteristics info = manager.getCameraCharacteristics(id);
            frameSize = chooseSize(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888));
            evRange = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            evStep = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            preview.ratio = (float) frameSize.getWidth() / frameSize.getHeight();
            preview.requestLayout();
            showEv();

            frames = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(), ImageFormat.YUV_420_888, 2);
            frames.setOnImageAvailableListener(this::onFrame, analysisHandler);
            manager.openCamera(id, new DeviceCallback(this), cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            status.setText("Camera error: " + e.getMessage());
        }
    }

    // Largest 4:3 size up to 1600 px wide: plenty for a 256x192 screen, cheap to process
    private static Size chooseSize(Size[] sizes) {
        Size best = sizes[0];
        long bestArea = -1;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            boolean fits = s.getWidth() <= 1600 && s.getWidth() * 3 == s.getHeight() * 4;
            if (fits && area > bestArea) {
                best = s;
                bestArea = area;
            }
        }
        return best;
    }

    // One session feeding both the preview and the analysis frames
    @SuppressWarnings("deprecation")
    private void createSession() {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            texture.setDefaultBufferSize(frameSize.getWidth(), frameSize.getHeight());
            previewSurface = new Surface(texture);
            camera.createCaptureSession(Arrays.asList(previewSurface, frames.getSurface()), new SessionCallback(this), cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            runOnUiThread(() -> status.setText("Camera error: " + e.getMessage()));
        }
    }

    // (Re)start capturing with continuous AF and the current exposure
    private void updateRequest() {
        if (session == null || camera == null) return;
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            b.addTarget(frames.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
            session.setRepeatingRequest(b.build(), null, cameraHandler);
        } catch (CameraAccessException | IllegalStateException ignored) {
            // camera is closing, fine
        }
    }

    // Exposure compensation, in camera steps. The DS screen is bright, -1 or -2 often helps.
    private void setEv(int steps) {
        if (evRange == null) return;
        ev = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), steps));
        showEv();
        if (cameraHandler != null) cameraHandler.post(this::updateRequest);
    }

    private void showEv() {
        evLabel.setText(evStep == null ? "EV" : String.format("EV %+.1f", ev * evStep.floatValue()));
    }

    // Close everything on the camera thread, then stop both threads
    private void stopCamera() {
        if (cameraThread == null) return;
        HandlerThread camThread = cameraThread, anaThread = analysisThread;
        cameraHandler.post(() -> {
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (frames != null) frames.close();
            if (previewSurface != null) previewSurface.release();
            session = null;
            camera = null;
            frames = null;
            previewSurface = null;
            camThread.quitSafely();
            anaThread.quitSafely();
        });
        cameraThread = analysisThread = null;
        cameraHandler = analysisHandler = null;
    }

    // Analysis thread: copy the newest frame out, give it back, then read the board
    private void onFrame(ImageReader reader) {
        Image img;
        try {
            img = reader.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;                                      // already closed
        }
        if (img == null) return;
        int w = img.getWidth(), h = img.getHeight();
        try {
            copyToNv21(img, w, h);
        } finally {
            img.close();
        }
        Vision.Frame f = Vision.analyze(nv21, w, h);
        runOnUiThread(() -> onReading(f));
    }

    // YUV_420_888 planes (any strides) -> packed NV21: Y, then V/U pairs
    private void copyToNv21(Image img, int w, int h) {
        if (nv21 == null || nv21.length != w * h * 3 / 2) nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] planes = img.getPlanes();
        ByteBuffer y = planes[0].getBuffer(), u = planes[1].getBuffer(), v = planes[2].getBuffer();
        int yStride = planes[0].getRowStride();
        for (int row = 0; row < h; row++) {
            y.position(row * yStride);
            y.get(nv21, row * w, w);
        }
        int uStride = planes[1].getRowStride(), uStep = planes[1].getPixelStride();
        int vStride = planes[2].getRowStride(), vStep = planes[2].getPixelStride();
        int o = w * h;
        for (int row = 0; row < h / 2; row++) {
            for (int col = 0; col < w / 2; col++) {
                nv21[o++] = v.get(row * vStride + col * vStep);
                nv21[o++] = u.get(row * uStride + col * uStep);
            }
        }
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private NumberPicker picker(int max, int value) {
        NumberPicker p = new NumberPicker(this);
        p.setMinValue(0);
        p.setMaxValue(max);
        p.setValue(value);
        return p;
    }

    // Camera open -> create the session
    private static final class DeviceCallback extends CameraDevice.StateCallback {
        private final MainActivity a;

        DeviceCallback(MainActivity a) { this.a = a; }

        @Override public void onOpened(CameraDevice d) {
            a.camera = d;
            a.createSession();
        }

        @Override public void onDisconnected(CameraDevice d) {
            d.close();
            a.camera = null;
        }

        @Override public void onError(CameraDevice d, int error) {
            d.close();
            a.camera = null;
            a.runOnUiThread(() -> a.status.setText("Camera error " + error));
        }
    }

    // Session ready -> start capturing
    private static final class SessionCallback extends CameraCaptureSession.StateCallback {
        private final MainActivity a;

        SessionCallback(MainActivity a) { this.a = a; }

        @Override public void onConfigured(CameraCaptureSession s) {
            a.session = s;
            a.updateRequest();
        }

        @Override public void onConfigureFailed(CameraCaptureSession s) {
            a.runOnUiThread(() -> a.status.setText("Camera configuration failed"));
        }
    }

    // height = width * ratio
    private static final class RatioTexture extends TextureView {
        float ratio;

        RatioTexture(Context c, float ratio) {
            super(c);
            this.ratio = ratio;
        }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, Math.round(width * ratio));
        }
    }

    // height = width * ratio
    private static final class RatioImage extends ImageView {
        private final float ratio;

        RatioImage(Context c, float ratio) {
            super(c);
            this.ratio = ratio;
        }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, Math.round(width * ratio));
        }
    }
}
