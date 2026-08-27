package com.axon.input;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera2 service used only by the optional Live2D full-body motion tracker.
 * Facial pose/expression capture has been removed from the project.
 */
public final class Live2DMotionTrackingService extends Service {
    private static final String TAG = "AxonMotionTrack";
    private static final String CHANNEL_ID = "live2d_motion_tracking";
    private static final int NOTIFICATION_ID = 1808;
    // Camera metadata and YUV analysis are intentionally capped near 15 Hz. Live2D itself still
    // renders at its normal refresh rate; lowering camera work removes contention without making
    // model rendering choppy.
    private static final long MIN_PUBLISH_INTERVAL_MS = 40L;
    private static final long FACE_LOST_TIMEOUT_MS = 1400L;
    private static final long BASE_MOTION_INTERVAL_MS = 66L;
    private static final long MAX_MOTION_INTERVAL_MS = 120L;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private HandlerThread analysisThread;
    private Handler analysisHandler;
    private final AtomicBoolean motionAnalysisBusy = new AtomicBoolean(false);
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Rect activeArray;
    private int sensorOrientation;
    private boolean frontFacing;
    private int faceDetectMode;
    private Range<Integer> captureFpsRange;
    private long lastPublishAt;
    private long lastFaceAt;
    private int selectedFaceId = -1;
    private long lastMotionGoodAt;
    private long lastMotionAt;
    private volatile long adaptiveMotionIntervalMs = BASE_MOTION_INTERVAL_MS;
    private volatile boolean motionTrackingEnabled;
    private volatile int cachedRelativeRotation;
    private volatile long cachedRotationAt;
    private volatile Live2DMotionEstimator.FaceGeometry latestFaceGeometry;
    private final Live2DMotionEstimator motionEstimator = new Live2DMotionEstimator();

    public static void start(Context context) {
        if (context == null || !OverlayState.isLive2DMotionTrackingEnabled(context)
                || !OverlayState.isLive2DEnabled(context) || !Live2DModelStore.exists(context)) return;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(context, Live2DMotionTrackingService.class);
        try {
            context.startForegroundService(intent);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to start Live2D tracking service", error);
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        Live2DMotionTracker.clear();
        try { context.stopService(new Intent(context, Live2DMotionTrackingService.class)); }
        catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureForeground();
        cameraThread = new HandlerThread("AxonLive2DMotionCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        analysisThread = new HandlerThread("AxonLive2DMotionAnalysis");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureForeground();
        motionTrackingEnabled = OverlayState.isLive2DMotionTrackingEnabled(this);
        if (!motionTrackingEnabled) Live2DMotionTracker.clear();
        if (!motionTrackingEnabled
                || !OverlayState.isLive2DEnabled(this)
                || !Live2DModelStore.exists(this)
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (cameraDevice == null) openFrontCamera();
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep camera tracking alive when Live2D itself is intentionally persistent outside the
        // Activity task. The foreground camera notification remains visible as required by Android.
        boolean keepTracking = OverlayState.isLive2DEnabled(this)
                && Live2DModelStore.exists(this)
                && OverlayState.isLive2DMotionTrackingEnabled(this)
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (keepTracking) {
            ensureForeground();
            motionTrackingEnabled = OverlayState.isLive2DMotionTrackingEnabled(this);
            if (cameraDevice == null) openFrontCamera();
        } else {
            Live2DMotionTracker.clear();
            stopSelf();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        closeCamera();
        latestFaceGeometry = null;
        Handler analyzer = analysisHandler;
        if (analyzer != null) analyzer.post(motionEstimator::reset);
        else motionEstimator.reset();
        Live2DMotionTracker.clear();
        if (cameraThread != null) {
            try { cameraThread.quitSafely(); } catch (Throwable ignored) {}
            cameraThread = null;
            cameraHandler = null;
        }
        if (analysisThread != null) {
            try { analysisThread.quitSafely(); } catch (Throwable ignored) {}
            analysisThread = null;
            analysisHandler = null;
        }
        try { stopForeground(true); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.live2d_motion_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.live2d_motion_notification_channel_desc));
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.live2d_motion_notification_title))
                .setContentText(getString(R.string.live2d_motion_notification_text))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @SuppressLint("MissingPermission")
    private void openFrontCamera() {
        if (cameraHandler == null) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null) {
            stopSelfSafely();
            return;
        }
        try {
            String selectedId = null;
            CameraCharacteristics selected = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer lens = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lens != null && lens == CameraCharacteristics.LENS_FACING_FRONT) {
                    selectedId = id;
                    selected = characteristics;
                    break;
                }
            }
            if (selectedId == null) {
                String[] ids = manager.getCameraIdList();
                if (ids.length > 0) {
                    selectedId = ids[0];
                    selected = manager.getCameraCharacteristics(selectedId);
                }
            }
            if (selectedId == null || selected == null) {
                Log.w(TAG, "No camera available for Live2D tracking");
                stopSelfSafely();
                return;
            }

            Integer lens = selected.get(CameraCharacteristics.LENS_FACING);
            frontFacing = lens != null && lens == CameraCharacteristics.LENS_FACING_FRONT;
            Integer orientation = selected.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;
            activeArray = selected.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int[] modes = selected.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
            faceDetectMode = chooseFaceMode(modes);
            captureFpsRange = chooseTargetFpsRange(
                    selected.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
            if (faceDetectMode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                // Camera2 metadata is optional. BlazeFace now owns full-frame acquisition and the
                // dense landmark model tracks the resulting ROI, matching MediaPipe's graph.
                Log.i(TAG, "Camera face metadata unavailable; BlazeFace acquisition remains active");
            }

            StreamConfigurationMap map = selected.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseOutputSize(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888));
            imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null || !motionTrackingEnabled) return;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastMotionAt < adaptiveMotionIntervalMs
                            || !motionAnalysisBusy.compareAndSet(false, true)) return;
                    lastMotionAt = now;
                    Handler analyzer = analysisHandler;
                    if (analyzer == null) {
                        motionAnalysisBusy.set(false);
                        return;
                    }
                    final Image analysisImage = image;
                    image = null; // ownership moves to the analysis thread
                    if (!analyzer.post(() -> {
                        long started = SystemClock.elapsedRealtime();
                        try {
                            processAnalysisFrame(analysisImage);
                        } catch (Throwable error) {
                            Log.d(TAG, "Live2D analysis frame skipped", error);
                        } finally {
                            try { analysisImage.close(); } catch (Throwable ignored) {}
                            tuneMotionInterval(SystemClock.elapsedRealtime() - started);
                            motionAnalysisBusy.set(false);
                        }
                    })) {
                        try { analysisImage.close(); } catch (Throwable ignored) {}
                        motionAnalysisBusy.set(false);
                    }
                } catch (Throwable error) {
                    Log.d(TAG, "Live2D analysis frame dispatch skipped", error);
                } finally {
                    if (image != null) image.close();
                }
            }, cameraHandler);

            manager.openCamera(selectedId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    try { camera.close(); } catch (Throwable ignored) {}
                    if (cameraDevice == camera) cameraDevice = null;
                    stopSelfSafely();
                }
                @Override public void onError(CameraDevice camera, int error) {
                    try { camera.close(); } catch (Throwable ignored) {}
                    if (cameraDevice == camera) cameraDevice = null;
                    Log.w(TAG, "Camera error: " + error);
                    stopSelfSafely();
                }
            }, cameraHandler);
        } catch (Throwable error) {
            Log.w(TAG, "Opening front camera failed", error);
            stopSelfSafely();
        }
    }

    private void createSession() {
        CameraDevice device = cameraDevice;
        ImageReader reader = imageReader;
        if (device == null || reader == null || cameraHandler == null) return;
        try {
            Surface surface = reader.getSurface();
            device.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                try { session.close(); } catch (Throwable ignored) {}
                                return;
                            }
                            captureSession = session;
                            try {
                                CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(surface);
                                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                if (faceDetectMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                                    request.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode);
                                }
                                if (captureFpsRange != null) {
                                    try { request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, captureFpsRange); }
                                    catch (Throwable ignored) {}
                                }
                                session.setRepeatingRequest(request.build(), captureCallback, cameraHandler);
                            } catch (Throwable error) {
                                Log.w(TAG, "Starting Live2D tracking capture failed", error);
                                stopSelfSafely();
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            stopSelfSafely();
                        }
                    }, cameraHandler);
        } catch (Throwable error) {
            Log.w(TAG, "Camera session failed", error);
            stopSelfSafely();
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                                 TotalCaptureResult result) {
            onFaces(result.get(CaptureResult.STATISTICS_FACES));
        }
    };

    private void onFaces(Face[] faces) {
        long now = SystemClock.elapsedRealtime();
        Face best = chooseBestFace(faces);
        if (best == null || activeArray == null || activeArray.width() <= 0 || activeArray.height() <= 0) {
            if (now - lastFaceAt >= FACE_LOST_TIMEOUT_MS) {
                latestFaceGeometry = null;
            }
            return;
        }
        lastFaceAt = now;
        Rect bounds = best.getBounds();
        latestFaceGeometry = toFaceGeometry(best, bounds, now);
    }

    private Live2DMotionEstimator.FaceGeometry toFaceGeometry(Face face, Rect bounds, long now) {
        Rect array = activeArray;
        if (face == null || bounds == null || array == null || array.width() <= 0 || array.height() <= 0) return null;
        float cx = (bounds.exactCenterX() - array.left) / array.width();
        float cy = (bounds.exactCenterY() - array.top) / array.height();
        float w = bounds.width() / (float) array.width();
        float h = bounds.height() / (float) array.height();
        return new Live2DMotionEstimator.FaceGeometry(
                clamp01(cx), clamp01(cy), Math.max(0.01f, w), Math.max(0.01f, h),
                bestScore(face), now);
    }

    private void processAnalysisFrame(Image image) {
        long now = SystemClock.elapsedRealtime();
        Live2DMotionEstimator.FaceGeometry geometry = latestFaceGeometry;
        if (!motionTrackingEnabled) return;
        if (geometry == null || now - geometry.timestampMs > 1200L) {
            if (now - lastMotionGoodAt > 1400L) Live2DMotionTracker.clear();
            return;
        }
        int rotation = getRelativeRotation();
        Live2DMotionEstimator.Result body = motionEstimator.process(
                image, geometry, rotation, frontFacing, true);
        if (body != null && body.confidence >= 0.28f) {
            lastMotionGoodAt = now;
            Live2DMotionTracker.publish(
                    body.bodyX, body.bodyY, body.crouch,
                    body.leftArmUp, body.rightArmUp,
                    body.leftHandX, body.leftHandY,
                    body.rightHandX, body.rightHandY,
                    body.leftLegMotion, body.rightLegMotion,
                    body.confidence);
        } else if (now - lastMotionGoodAt > 1400L) {
            Live2DMotionTracker.clear();
        }
    }

    private static float bestScore(Face face) {
        if (face == null) return 0f;
        return clamp01(face.getScore() / 100f);
    }

    private int getRelativeRotation() {
        long now = SystemClock.elapsedRealtime();
        if (now - cachedRotationAt < 1000L) return cachedRelativeRotation;
        int displayDegrees = 0;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) {
                int rotation = wm.getDefaultDisplay().getRotation();
                if (rotation == Surface.ROTATION_90) displayDegrees = 90;
                else if (rotation == Surface.ROTATION_180) displayDegrees = 180;
                else if (rotation == Surface.ROTATION_270) displayDegrees = 270;
            }
        } catch (Throwable ignored) {}
        int resolved = frontFacing
                ? (sensorOrientation + displayDegrees) % 360
                : (sensorOrientation - displayDegrees + 360) % 360;
        cachedRelativeRotation = resolved;
        cachedRotationAt = now;
        return resolved;
    }

    private static float[] rotateNormalized(float x, float y, int degrees) {
        switch ((degrees % 360 + 360) % 360) {
            case 90: return new float[]{-y, x};
            case 180: return new float[]{-x, -y};
            case 270: return new float[]{y, -x};
            default: return new float[]{x, y};
        }
    }

    private Face chooseBestFace(Face[] faces) {
        if (faces == null || faces.length == 0) return null;
        // Prefer the previously tracked Camera2 face id. Choosing largest-area every frame can
        // alternate between nearby faces/background false positives and looks like a violent twitch.
        if (selectedFaceId >= 0) {
            for (Face face : faces) {
                if (face != null && face.getScore() >= 30 && face.getId() == selectedFaceId) return face;
            }
        }

        // A number of Android camera HALs always report face id = -1. In that case use spatial
        // continuity against the last accepted face instead of reverting to largest-area selection.
        // This prevents a transient background false-positive from becoming the tracked performer.
        Live2DMotionEstimator.FaceGeometry previous = latestFaceGeometry;
        if (previous != null && activeArray != null
                && SystemClock.elapsedRealtime() - previous.timestampMs <= 1400L) {
            Face continuityBest = null;
            float continuityCost = Float.MAX_VALUE;
            for (Face face : faces) {
                if (face == null || face.getScore() < 30) continue;
                Rect bounds = face.getBounds();
                if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) continue;
                float cx = (bounds.exactCenterX() - activeArray.left) / activeArray.width();
                float cy = (bounds.exactCenterY() - activeArray.top) / activeArray.height();
                float w = bounds.width() / (float) activeArray.width();
                float h = bounds.height() / (float) activeArray.height();
                float distance = (float) Math.hypot(cx - previous.centerX, cy - previous.centerY);
                float previousSize = Math.max(0.01f, Math.max(previous.width, previous.height));
                float size = Math.max(0.01f, Math.max(w, h));
                float sizeDelta = Math.abs((float) Math.log(size / previousSize));
                float scoreBonus = clamp01(face.getScore() / 100f) * 0.10f;
                float cost = distance * 3.4f + sizeDelta * 0.75f - scoreBonus;
                if (cost < continuityCost) {
                    continuityCost = cost;
                    continuityBest = face;
                }
            }
            // Reject implausibly distant candidates for this frame rather than snapping to them.
            if (continuityBest != null && continuityCost < 1.05f) {
                if (continuityBest.getId() >= 0) selectedFaceId = continuityBest.getId();
                return continuityBest;
            }
        }

        Face best = null;
        long bestArea = -1;
        for (Face face : faces) {
            if (face == null || face.getScore() < 35) continue;
            Rect bounds = face.getBounds();
            long area = bounds == null ? 0 : (long) bounds.width() * bounds.height();
            if (best == null || area > bestArea) {
                best = face;
                bestArea = area;
            }
        }
        if (best != null && best.getId() >= 0) selectedFaceId = best.getId();
        return best;
    }

    private static int chooseFaceMode(int[] modes) {
        if (modes == null) return CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
        for (int mode : modes) if (mode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL) return mode;
        for (int mode : modes) if (mode == CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE) return mode;
        return CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF;
    }

    private static Range<Integer> chooseTargetFpsRange(Range<Integer>[] ranges) {
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            if (range == null) continue;
            int lower = range.getLower();
            int upper = range.getUpper();
            if (upper < 10) continue;
            // Prefer a range that can settle at 15 fps, then the lowest upper bound.
            int containsPenalty = (lower <= 15 && upper >= 15) ? 0 : 1000;
            int score = containsPenalty + Math.abs(upper - 15) * 20 + Math.abs(lower - 15);
            if (score < bestScore) {
                best = range;
                bestScore = score;
            }
        }
        return best;
    }

    private void tuneMotionInterval(long analysisMs) {
        long current = adaptiveMotionIntervalMs;
        if (analysisMs >= 70L) current = Math.min(MAX_MOTION_INTERVAL_MS, Math.max(current, 100L));
        else if (analysisMs >= 48L) current = Math.min(MAX_MOTION_INTERVAL_MS, Math.max(current, 82L));
        else if (analysisMs <= 28L && current > BASE_MOTION_INTERVAL_MS) current = Math.max(BASE_MOTION_INTERVAL_MS, current - 8L);
        adaptiveMotionIntervalMs = current;
    }

    private static Size chooseOutputSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(320, 240);
        List<Size> list = new ArrayList<>(Arrays.asList(sizes));
        list.sort(Comparator.comparingLong((Size size) -> (long) size.getWidth() * size.getHeight()));
        // Dense FaceMesh uses a 192x192 model crop, but a ~640x480 camera source materially
        // improves eyelid/lip detail before resampling. Prefer that class without requesting 720p+.
        Size best = null;
        long bestDelta = Long.MAX_VALUE;
        long target = 640L * 480L;
        for (Size size : list) {
            int w = size.getWidth();
            int h = size.getHeight();
            long area = (long) w * h;
            if (Math.min(w, h) < 300 || Math.max(w, h) > 800) continue;
            long delta = Math.abs(area - target);
            if (delta < bestDelta) { best = size; bestDelta = delta; }
        }
        if (best != null) return best;
        for (Size size : list) {
            int w = size.getWidth();
            int h = size.getHeight();
            if (Math.min(w, h) >= 240 && Math.max(w, h) <= 640) return size;
        }
        return list.get(0);
    }

    private void stopSelfSafely() {
        Handler handler = cameraHandler;
        if (handler != null) handler.post(this::stopSelf);
        else stopSelf();
    }

    private void closeCamera() {
        if (captureSession != null) {
            try { captureSession.close(); } catch (Throwable ignored) {}
            captureSession = null;
        }
        if (cameraDevice != null) {
            try { cameraDevice.close(); } catch (Throwable ignored) {}
            cameraDevice = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
