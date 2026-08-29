package com.axon.input;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Full-display, input-transparent Live2D renderer for the Display Behavior feature. */
public final class Live2DOverlayView extends FrameLayout {
    private static final String TAG = "AxonLive2D";

    public interface DragListener {
        void onDragStart(Live2DOverlayView source, float rawX, float rawY);
        void onDragMove(Live2DOverlayView source, float rawX, float rawY);
        void onDragEnd(Live2DOverlayView source);
    }

    private final WebView webView;
    private String loadedVersion = "";
    private boolean released;
    private int loadGeneration;
    private int displayScalePercent = 100;
    private int renderQuality;
    private float displayOffsetX;
    private float displayOffsetY;
    private boolean dragEnabled;
    private boolean dragging;
    private DragListener dragListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long appliedMotionGeneration = Long.MIN_VALUE;
    private long appliedMouseGeneration = Long.MIN_VALUE;
    private long lastMouseTotalDx;
    private long lastMouseTotalDy;
    private boolean mouseBaselineReady;
    private boolean mouseCaptureApplied;
    private boolean displayOffsetApplyScheduled;
    private final Runnable applyDisplayOffsetRunnable = () -> {
        displayOffsetApplyScheduled = false;
        applyDisplayOffsetToRuntime();
        requestLayout();
        invalidate();
    };
    private final Runnable trackingPump = new Runnable() {
        @Override public void run() {
            if (released) return;
            applyTrackingToRuntime();
            mainHandler.postDelayed(this, 50L);
        }
    };

    private static final class PreparedPage {
        final String html;
        final String baseUrl;
        final String version;

        PreparedPage(String html, String baseUrl, String version) {
            this.html = html;
            this.baseUrl = baseUrl;
            this.version = version;
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "ObsoleteSdkInt"})
    public Live2DOverlayView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setClipChildren(true);
        setClipToPadding(true);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        displayOffsetX = OverlayState.getLive2DOffsetX(context);
        displayOffsetY = OverlayState.getLive2DOffsetY(context);
        renderQuality = OverlayState.getLive2DRenderQuality(context);

        webView = new WebView(context);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setLayerType(View.LAYER_TYPE_NONE, null);
        webView.setClickable(false);
        webView.setLongClickable(false);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDefaultTextEncodingName("utf-8");
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message == null) return false;
                String text = message.message() + " @" + message.lineNumber() + ":" + message.sourceId();
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, text);
                else if (message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) Log.w(TAG, text);
                else Log.d(TAG, text);
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "Live2D page finished: " + url);
                appliedMotionGeneration = Long.MIN_VALUE;
                appliedMouseGeneration = Long.MIN_VALUE;
                mouseBaselineReady = false;
                mouseCaptureApplied = false;
                applyRenderQualityToRuntime();
                applyDisplayScaleToRuntime();
                applyDisplayOffsetToRuntime();
                applyWatermarkToRuntime();
                applyTrackingToRuntime();
                mainHandler.postDelayed(() -> {
                    if (released) return;
                    try {
                        webView.evaluateJavascript(
                                "JSON.stringify(window.AxonBongoCat&&window.AxonBongoCat.debugState?window.AxonBongoCat.debugState():{ready:false,missingRuntime:true})",
                                value -> Log.i(TAG, "Live2D runtime state: " + value));
                    } catch (Throwable ignored) {}
                }, 500L);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.e(TAG, "Live2D main frame failed: " + (error == null ? "unknown" : error.toString()));
                }
            }
        });

        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mainHandler.post(trackingPump);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // The renderer itself stays input-transparent in accessibility-overlay mode.
        // A separate public-API drag handle window is owned by the service while dragging is enabled.
        if (!dragEnabled || event == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (dragListener != null) dragListener.onDragStart(this, event.getRawX(), event.getRawY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && dragListener != null) dragListener.onDragMove(this, event.getRawX(), event.getRawY());
                return dragging;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean wasDragging = dragging;
                dragging = false;
                if (wasDragging && dragListener != null) dragListener.onDragEnd(this);
                return wasDragging;
            default:
                return dragging;
        }
    }

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    public void setDragEnabled(boolean enabled) {
        if (dragEnabled == enabled) return;
        dragEnabled = enabled;
        if (!enabled) dragging = false;
        requestLayout();
        invalidate();
    }

    public boolean isDragEnabled() {
        return dragEnabled;
    }

    public float getDisplayOffsetX() { return displayOffsetX; }
    public float getDisplayOffsetY() { return displayOffsetY; }

    public void setDisplayOffsetNormalized(float x, float y) {
        float nextX = clampOffset(x);
        float nextY = clampOffset(y);
        if (Math.abs(nextX - displayOffsetX) < 0.0005f
                && Math.abs(nextY - displayOffsetY) < 0.0005f) return;
        displayOffsetX = nextX;
        displayOffsetY = nextY;
        // MOVE events may arrive above display refresh rate. Coalesce JS/WebView work to one
        // update per animation frame so dragging Live2D does not compete with Cubism rendering.
        if (!displayOffsetApplyScheduled) {
            displayOffsetApplyScheduled = true;
            postOnAnimation(applyDisplayOffsetRunnable);
        }
    }

    private static float clampOffset(float value) {
        return Math.max(-0.90f, Math.min(0.90f, value));
    }

    public String getLoadedVersion() {
        return loadedVersion;
    }

    public void setDisplayScalePercent(int percent) {
        displayScalePercent = Math.max(25, Math.min(400, percent));
        applyDisplayScaleToRuntime();
        requestLayout();
        invalidate();
    }

    public void setRenderQuality(int quality) {
        int next = RenderQuality.normalize(quality);
        if (renderQuality == next) return;
        renderQuality = next;
        applyRenderQualityToRuntime();
    }

    private void applyRenderQualityToRuntime() {
        if (released) return;
        try {
            webView.evaluateJavascript(
                    "window.AxonBongoCat&&window.AxonBongoCat.setRenderQuality&&window.AxonBongoCat.setRenderQuality("
                            + JSONObject.quote(RenderQuality.jsName(renderQuality)) + ");", null);
        } catch (Throwable ignored) {}
    }

    /** Live-update Cubism Physics controls; no WebView/model rebuild is required. */
    public void setPhysicsControls(JSONObject controls) {
        if (released) return;
        if (controls == null) controls = new JSONObject();
        try {
            webView.evaluateJavascript(
                    "window.AxonBongoCat&&window.AxonBongoCat.setPhysicsControls&&window.AxonBongoCat.setPhysicsControls("
                            + controls.toString() + ");", null);
        } catch (Throwable ignored) {}
    }

    public void triggerPhysicsGroupAction(String groupKey) {
        if (released || groupKey == null || groupKey.isEmpty()) return;
        try {
            webView.evaluateJavascript(
                    "window.AxonBongoCat&&window.AxonBongoCat.triggerPhysicsGroupAction&&"
                            + "window.AxonBongoCat.triggerPhysicsGroupAction(" + JSONObject.quote(groupKey) + ");",
                    null);
        } catch (Throwable ignored) {}
    }

    public void refreshPhysicsControls() {
        setPhysicsControls(Live2DPhysicsSettingsStore.runtimeJson(
                getContext(), Live2DPhysicsSettingsStore.TARGET_LIVE2D));
    }

    public void setDebugExpression(String token, float weight) {
        String raw = token == null ? "auto" : token.trim();
        String kind = "auto";
        int index = -1;
        int colon = raw.indexOf(':');
        if (colon > 0 && "live2d".equalsIgnoreCase(raw.substring(0, colon))) {
            try { index = Math.max(-1, Integer.parseInt(raw.substring(colon + 1))); }
            catch (Throwable ignored) { index = -1; }
            if (index >= 0) kind = "live2d";
        }
        float safeWeight = Math.max(0f, Math.min(1f, weight));
        try {
            webView.evaluateJavascript("window.AxonBongoCat&&AxonBongoCat.setDebugExpression&&AxonBongoCat.setDebugExpression("
                    + JSONObject.quote(kind) + "," + index + "," + safeWeight + ")", null);
        } catch (Throwable ignored) {}
    }

    public void setParameterLock(String parameterId, float normalized) {
        if (released || parameterId == null || parameterId.isEmpty()) return;
        float safe = Math.max(0f, Math.min(1f, normalized));
        try { webView.evaluateJavascript("window.AxonBongoCat&&AxonBongoCat.setParameterLock&&AxonBongoCat.setParameterLock("
                + JSONObject.quote(parameterId) + "," + safe + ")", null); }
        catch (Throwable ignored) {}
    }

    public void clearParameterLock(String parameterId) {
        if (released || parameterId == null || parameterId.isEmpty()) return;
        try { webView.evaluateJavascript("window.AxonBongoCat&&AxonBongoCat.clearParameterLock&&AxonBongoCat.clearParameterLock("
                + JSONObject.quote(parameterId) + ")", null); }
        catch (Throwable ignored) {}
    }

    public void requestParameterDebug(String parameterId, android.webkit.ValueCallback<JSONObject> callback) {
        if (callback == null) return;
        if (released || parameterId == null || parameterId.isEmpty()) { callback.onReceiveValue(null); return; }
        try {
            webView.evaluateJavascript("JSON.stringify(window.AxonBongoCat&&AxonBongoCat.parameterDebug?AxonBongoCat.parameterDebug("
                    + JSONObject.quote(parameterId) + "):null)", raw -> callback.onReceiveValue(parseJavascriptJson(raw)));
        } catch (Throwable ignored) { callback.onReceiveValue(null); }
    }

    private static JSONObject parseJavascriptJson(String raw) {
        if (raw == null || raw.equals("null") || raw.equals("undefined")) return null;
        try {
            String decoded = new JSONArray("[" + raw + "]").optString(0, "");
            return decoded.isEmpty() || "null".equals(decoded) ? null : new JSONObject(decoded);
        } catch (Throwable ignored) { return null; }
    }

    private void applyDisplayScaleToRuntime() {
        if (released) return;
        final double scale = displayScalePercent / 100.0;
        try {
            webView.evaluateJavascript(
                    "window.AxonBongoCat&&window.AxonBongoCat.setDisplayScale&&window.AxonBongoCat.setDisplayScale("
                            + String.format(java.util.Locale.US, "%.3f", scale) + ");", null);
        } catch (Throwable ignored) {}
    }

    private void applyDisplayOffsetToRuntime() {
        if (released) return;
        try {
            webView.evaluateJavascript(
                    String.format(java.util.Locale.US,
                            "window.AxonBongoCat&&window.AxonBongoCat.setDisplayOffset&&window.AxonBongoCat.setDisplayOffset(%.4f,%.4f);",
                            displayOffsetX, displayOffsetY), null);
        } catch (Throwable ignored) {}
    }

    public void setHideWatermark(boolean hidden) {
        applyWatermarkToRuntime(hidden);
    }

    private void applyWatermarkToRuntime() {
        applyWatermarkToRuntime(OverlayState.isLive2DHideWatermarkEnabled(getContext()));
    }

    private void applyWatermarkToRuntime(boolean hidden) {
        if (released) return;
        try {
            webView.evaluateJavascript(
                    "window.AxonBongoCat&&window.AxonBongoCat.setHideWatermark&&window.AxonBongoCat.setHideWatermark("
                            + (hidden ? "true" : "false") + ");", null);
        } catch (Throwable ignored) {}
    }

    private void applyTrackingToRuntime() {
        if (released) return;
        boolean motionEnabled = OverlayState.isLive2DMotionTrackingEnabled(getContext());
        long now = SystemClock.elapsedRealtime();
        StringBuilder js = null;

        Live2DMotionTracker.Sample motion = Live2DMotionTracker.snapshot();
        if (motion != null && motion.generation != appliedMotionGeneration) {
            appliedMotionGeneration = motion.generation;
            boolean motionFresh = motionEnabled && motion.detected && (now - motion.timestampMs) <= 900L;
            if (js == null) js = new StringBuilder(768);
            if (!motionFresh) {
                js.append("window.AxonBongoCat&&window.AxonBongoCat.clearMotionTracking&&window.AxonBongoCat.clearMotionTracking();");
            } else {
                js.append(String.format(java.util.Locale.US,
                        "window.AxonBongoCat&&window.AxonBongoCat.setMotionTracking&&window.AxonBongoCat.setMotionTracking(%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f);",
                        motion.bodyX, motion.bodyY, motion.crouch,
                        motion.leftArmUp, motion.rightArmUp,
                        motion.leftHandX, motion.leftHandY,
                        motion.rightHandX, motion.rightHandY,
                        motion.leftLegMotion, motion.rightLegMotion,
                        motion.confidence));
            }
        }

        boolean mouseEnabled = OverlayState.isLive2DEnabled(getContext())
                && OverlayState.isLive2DMouseCaptureEnabled(getContext());
        if (!mouseEnabled) {
            if (mouseCaptureApplied) {
                if (js == null) js = new StringBuilder(256);
                js.append("window.AxonBongoCat&&window.AxonBongoCat.clearMouseTracking&&window.AxonBongoCat.clearMouseTracking();");
            }
            mouseCaptureApplied = false;
            mouseBaselineReady = false;
            appliedMouseGeneration = Long.MIN_VALUE;
        } else {
            Live2DMouseTracker.Sample mouse = Live2DMouseTracker.snapshot();
            if (!mouseBaselineReady) {
                lastMouseTotalDx = mouse.totalDx;
                lastMouseTotalDy = mouse.totalDy;
                appliedMouseGeneration = mouse.generation;
                mouseBaselineReady = true;
                mouseCaptureApplied = true;
            } else if (mouse.generation != appliedMouseGeneration) {
                long rawDx = mouse.totalDx - lastMouseTotalDx;
                long rawDy = mouse.totalDy - lastMouseTotalDy;
                lastMouseTotalDx = mouse.totalDx;
                lastMouseTotalDy = mouse.totalDy;
                appliedMouseGeneration = mouse.generation;
                mouseCaptureApplied = true;
                if (Math.abs(rawDx) <= 32768L && Math.abs(rawDy) <= 32768L
                        && (rawDx != 0L || rawDy != 0L)) {
                    int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
                    int height = Math.max(1, getResources().getDisplayMetrics().heightPixels);
                    if (js == null) js = new StringBuilder(1024);
                    js.append("window.AxonBongoCat&&window.AxonBongoCat.mouseDelta&&window.AxonBongoCat.mouseDelta(")
                            .append((int) rawDx).append(',').append((int) rawDy).append(',')
                            .append(width).append(',').append(height).append(");");
                }
            }
        }

        if (js == null || js.length() == 0) return;
        try { webView.evaluateJavascript(js.toString(), null); }
        catch (Throwable ignored) {}
    }

    /**
     * model3/moc3 parsing, base64 encoding and JS template assembly are intentionally off-main.
     * A 6+ MiB moc like the supplied yumi model must not stall the accessibility service thread.
     */
    public void loadCurrentModel() {
        if (released) return;
        final int generation = ++loadGeneration;
        loadedVersion = Live2DModelStore.getVersion(getContext());
        new Thread(() -> {
            PreparedPage prepared;
            try {
                prepared = prepareCurrentPage();
            } catch (Throwable error) {
                Log.e(TAG, "Live2D model preparation failed", error);
                post(() -> {
                    if (released || generation != loadGeneration) return;
                    loadedVersion = "";
                    try { webView.loadUrl("about:blank"); } catch (Throwable ignored) {}
                });
                return;
            }
            post(() -> {
                if (released || generation != loadGeneration) return;
                loadedVersion = prepared.version;
                webView.loadDataWithBaseURL(
                        prepared.baseUrl, prepared.html, "text/html", "utf-8", prepared.baseUrl);
            });
        }, "AxonLive2DPrepare").start();
    }

    private PreparedPage prepareCurrentPage() throws Exception {
        long startedAt = SystemClock.elapsedRealtime();
        BongoCatStyleManager.StyleInfo style = Live2DModelStore.runtimeStyle(getContext());
        JSONObject config = BongoCatStyleManager.runtimeConfigLive2DDisplay(style);
        RenderQuality.applyRuntimeConfig(config, renderQuality);
        config.put("physicsControls", Live2DPhysicsSettingsStore.runtimeJson(
                getContext(), Live2DPhysicsSettingsStore.TARGET_LIVE2D));
        config.put("parameterLocks", Live2DDebugSettingsStore.parameterLocksJson(
                getContext(), Live2DPhysicsSettingsStore.TARGET_LIVE2D));

        // Reuse Axon's proven Cubism renderer in a minimal Mver-compatible mode. This enables
        // physics, eye blink, breathing and an authored Idle motion while keeping all input,
        // hand, face and key-binding layers empty.
        config.put("format", "mver016");
        config.put("mver", true);
        config.put("useLive2d", true);
        config.put("spriteMode", false);
        config.put("mverFrameRateLimit", 60);
        config.put("mverL2dCorrect", 1.0);
        config.put("axonLive2dDisplay", true);
        config.put("axonLive2dDisplayScale", OverlayState.getLive2DSize(getContext()) / 100.0);
        config.put("axonLive2dDisplayOffsetX", displayOffsetX);
        config.put("axonLive2dDisplayOffsetY", displayOffsetY);
        config.put("axonLive2dHideWatermark", OverlayState.isLive2DHideWatermarkEnabled(getContext()));
        config.put("axonLive2dWatermarkParameters", Live2DModelStore.watermarkParameterIds(getContext()));
        config.put("axonLive2dWatermarkParts", Live2DModelStore.watermarkPartIds(getContext()));
        config.put("mverMouseForceMove", false);
        config.put("mverRenderHandOverlays", false);
        config.put("mverRenderMouseOverlay", false);

        String template = readAssetText("bongocat/custom/index.html");
        String core = escapeInlineScript(readAssetText("bongocat/live2dcubismcore.min.js"));
        String runtime = escapeInlineScript(readAssetText("bongocat/custom/runtime.js"));
        String configJson = config.toString();
        String bootstrap = escapeInlineScript("window.__AXON_STYLE_CONFIG__=" + configJson + ";");
        String html = template
                .replace("__AXON_STYLE_BOOTSTRAP__", bootstrap)
                .replace("__AXON_CUBISM_CORE__", core)
                .replace("__AXON_CUSTOM_RUNTIME__", runtime);

        String baseUrl = Uri.fromFile(style.root).toString();
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        Log.i(TAG, "Live2D page prepared in "
                + (SystemClock.elapsedRealtime() - startedAt) + "ms, config="
                + (configJson.length() / 1024) + "KiB");
        return new PreparedPage(html, baseUrl, Live2DModelStore.getVersion(getContext()));
    }

    public void release() {
        if (released) return;
        released = true;
        mainHandler.removeCallbacks(trackingPump);
        removeCallbacks(applyDisplayOffsetRunnable);
        displayOffsetApplyScheduled = false;
        loadGeneration++;
        loadedVersion = "";
        try { webView.stopLoading(); } catch (Throwable ignored) {}
        try { webView.loadUrl("about:blank"); } catch (Throwable ignored) {}
        try { webView.setWebChromeClient(null); } catch (Throwable ignored) {}
        try { webView.setWebViewClient(null); } catch (Throwable ignored) {}
        try { webView.removeAllViews(); } catch (Throwable ignored) {}
        try { webView.destroy(); } catch (Throwable ignored) {}
        removeAllViews();
    }

    private String readAssetText(String path) throws Exception {
        try (InputStream in = getContext().getAssets().open(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String escapeInlineScript(String source) {
        if (source == null || source.isEmpty()) return "";
        return source.replace("</script", "<\\/script");
    }
}
