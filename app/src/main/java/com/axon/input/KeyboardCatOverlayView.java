package com.axon.input;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
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

import java.util.HashMap;
import java.util.LinkedHashSet;

/**
 * BongoCat 源键盘猫悬浮层。
 *
 * 视觉资源直接使用 BongoCat keyboard model 的 moc3 / texture / key overlays；
 * WebView 中通过 Live2D Cubism Core 驱动原模型参数，Android 仅负责传递系统级输入。
 */
public final class KeyboardCatOverlayView extends FrameLayout {
    public static final int DISPLAY_KEYBOARD_CAT = 40;
    private static final String TAG = "AxonBongoCat";
    // Large imported Live2D models can legitimately need several seconds on slower WebView/GPU
    // implementations. The old ~2.9 s timeout marked a healthy runtime dead too early.
    private static final int RUNTIME_PROBE_MAX_ATTEMPTS = 400;
    private static final long RUNTIME_PROBE_DELAY_MS = 50L;

    public interface DragListener {
        void onDragStart(KeyboardCatOverlayView source, float rawX, float rawY);
        void onDragMove(KeyboardCatOverlayView source, float rawX, float rawY);
        void onDragEnd(KeyboardCatOverlayView source);
    }

    private static final String KEYBOARD_PAGE_URL = "file:///android_asset/bongocat/keyboard/index.html";
    private static final String MOUSE_PAGE_URL = "file:///android_asset/bongocat/standard/index.html";

    private final WebView webView;
    private final SparseBooleanArray pressedKeyCodes = new SparseBooleanArray();
    private final LinkedHashSet<String> pressedKeyOrder = new LinkedHashSet<>();
    private final HashMap<String, Integer> pressedSemanticCounts = new HashMap<>();
    private final Object inputBridgeLock = new Object();
    /** Cached pull-bridge payload; rebuilt only when held-key state actually changes. */
    private volatile String nativeKeySnapshot = "{\"keys\":[]}";

    private DragListener dragListener;
    private boolean dragEnabled;
    private boolean dragging;
    private boolean pageReady;
    private int runtimeGeneration;
    private int runtimeRecoveryStage;
    private BongoCatStyleManager.StyleInfo loadedStyle;
    private boolean released;
    /** Detached/hidden overlays keep native input state but stop JS/WebGL work until visible again. */
    private boolean lifecycleSuspended;
    private boolean mouseMode;
    private boolean globalReverse;
    private int renderQuality;
    private String styleId;
    private String styleMode = BongoCatStyleManager.MODE_KEYBOARD;
    private String debugExpressionKind = "auto";
    private int debugExpressionIndex = -1;
    private float debugExpressionWeight = 1f;
    private int gamepadButtons;
    private int gamepadLx;
    private int gamepadLy;
    private int gamepadRx;
    private int gamepadRy;
    private int gamepadLt;
    private int gamepadRt;
    private boolean gamepadLtPressed;
    private boolean gamepadRtPressed;
    private int mouseButtons;
    private int mouseAuxButtons;
    private int pendingMouseDx;
    private int pendingMouseDy;
    private int pendingMousePressPulses;
    private boolean mouseFrameScheduled;
    private boolean gamepadFrameScheduled;
    private float dragStartRawX;
    private float dragStartRawY;
    private Runnable exitCallback;

    /** Latest explicit UI test request. Unlike gameplay hotkeys, a test may wait briefly for a
     * large imported model to finish becoming ready instead of being silently dropped. */
    private PendingModelFunctionTest pendingModelFunctionTest;

    private static final class PendingModelFunctionTest {
        final String token;
        final String mode;
        final boolean pressed;
        final long expiresAtMs;
        final android.webkit.ValueCallback<Boolean> callback;
        PendingModelFunctionTest(String token, String mode, boolean pressed, long expiresAtMs,
                                 android.webkit.ValueCallback<Boolean> callback) {
            this.token = token; this.mode = mode; this.pressed = pressed;
            this.expiresAtMs = expiresAtMs; this.callback = callback;
        }
    }

    private final Runnable mouseFrameDrain = this::drainMouseFrame;
    private final Runnable gamepadFrameDrain = this::drainGamepadFrame;

    /**
     * Pull-based fallback for Mver. Some Android WebView/ROM combinations can drop an
     * evaluateJavascript call while the transparent overlay is being attached. The page polls
     * this snapshot and reconciles held keys, so input state cannot get permanently desynced.
     */
    private final class NativeInputBridge {
        @JavascriptInterface
        public String snapshotKeys() {
            // JavascriptInterface may run on a WebView bridge thread. Returning an immutable cached
            // String avoids allocating JSONObject/JSONArray objects on every safety poll.
            return nativeKeySnapshot;
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "ObsoleteSdkInt"})
    public KeyboardCatOverlayView(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
        setBackgroundColor(Color.TRANSPARENT);
        setClickable(true);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        webView = new WebView(context);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Keep WebView's default compositor mode. Forcing a hardware layer causes transparent
        // accessibility overlays to render blank on several OEM WebView/GPU combinations. WebGL
        // remains hardware accelerated by the application/window when available.
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
        // 仅加载 APK 内可信的 BongoCat 资源；允许同一 file:// 页面读取 moc3 与纹理。
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        webView.addJavascriptInterface(new NativeInputBridge(), "AxonNativeInput");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message == null) return false;
                String text = "[" + message.messageLevel() + "] " + message.message()
                        + " @" + message.lineNumber() + ":" + message.sourceId();
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, text);
                else if (message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) Log.w(TAG, text);
                else Log.d(TAG, text);
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                probeRuntimeReady(runtimeGeneration, 0);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Log.e(TAG, "WebView load failed: " + (error == null ? "unknown" : error.toString()));
                    recoverRuntime(runtimeGeneration, "main-frame-load");
                }
            }
        });

        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mouseMode = OverlayState.isKeyboardCatMouseMode(context);
        globalReverse = OverlayState.isKeyboardCatGlobalReverse(context);
        renderQuality = OverlayState.getKeyboardCatRenderQuality(context);
        styleId = OverlayState.getKeyboardCatStyleId(context);
        loadCurrentMode();
    }

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    public void setStyleId(String id) {
        String next = id == null || id.isEmpty() ? BongoCatStyleManager.BUILTIN_ID : id;
        if (next.equals(styleId)) return;
        styleId = next;
        loadCurrentMode();
    }

    public void setRenderQuality(int quality) {
        int next = RenderQuality.normalize(quality);
        if (renderQuality == next) return;
        renderQuality = next;
        if (pageReady) dispatchRenderQuality();
    }

    private void dispatchRenderQuality() {
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.setRenderQuality&&AxonBongoCat.setRenderQuality("
                + JSONObject.quote(RenderQuality.jsName(renderQuality)) + ")");
    }

    /** Updates Cubism Physics strength/group controls without reloading the model or input bridge. */
    public void setPhysicsControls(JSONObject controls) {
        if (controls == null) controls = new JSONObject();
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.setPhysicsControls&&AxonBongoCat.setPhysicsControls("
                + controls.toString() + ")");
    }

    public void triggerPhysicsGroupAction(String groupKey) {
        if (groupKey == null || groupKey.isEmpty() || released || !pageReady || lifecycleSuspended) return;
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.triggerPhysicsGroupAction&&"
                + "AxonBongoCat.triggerPhysicsGroupAction(" + JSONObject.quote(groupKey) + ")");
    }

    public void refreshPhysicsControls() {
        BongoCatStyleManager.StyleInfo style = loadedStyle;
        if (style == null || style.builtin) return;
        setPhysicsControls(Live2DPhysicsSettingsStore.runtimeJson(
                getContext(), Live2DPhysicsSettingsStore.keyboardCatTarget(style.id)));
    }

    /** Live preview/persistent model parameter control. normalized is Cubism min..max mapped to 0..1. */
    public void setModelParameter(String parameterId, float normalized) {
        if (parameterId == null || parameterId.isEmpty()) return;
        float value = Math.max(0f, Math.min(1f, normalized));
        dispatch("window.AxonBongoCat&&AxonBongoCat.setModelParameter("
                + JSONObject.quote(parameterId) + "," + value + ")");
    }

    public void resetModelParameter(String parameterId) {
        if (parameterId == null || parameterId.isEmpty()) return;
        dispatch("window.AxonBongoCat&&AxonBongoCat.resetModelParameter("
                + JSONObject.quote(parameterId) + ")");
    }

    public void setParameterLock(String parameterId, float normalized) {
        if (parameterId == null || parameterId.isEmpty()) return;
        float value = Math.max(0f, Math.min(1f, normalized));
        dispatch("window.AxonBongoCat&&AxonBongoCat.setParameterLock("
                + JSONObject.quote(parameterId) + "," + value + ")");
    }

    public void clearParameterLock(String parameterId) {
        if (parameterId == null || parameterId.isEmpty()) return;
        dispatch("window.AxonBongoCat&&AxonBongoCat.clearParameterLock("
                + JSONObject.quote(parameterId) + ")");
    }

    public void requestParameterDebug(String parameterId, android.webkit.ValueCallback<JSONObject> callback) {
        if (callback == null) return;
        if (!pageReady || parameterId == null || parameterId.isEmpty()) { callback.onReceiveValue(null); return; }
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

    public void triggerModelFunction(String token, String mode, boolean pressed) {
        if (token == null || token.isEmpty() || released || !pageReady || lifecycleSuspended) return;
        String trigger = mode == null || mode.isEmpty() ? KeyboardCatFunctionBindingStore.MODE_TOGGLE : mode;
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.triggerModelFunction("
                + JSONObject.quote(token) + "," + JSONObject.quote(trigger) + "," + pressed + ")");
    }

    /**
     * Test-only action path. Returns the JS runtime's real boolean result and tolerates the short
     * loading window of large community models. Gameplay hotkeys intentionally remain non-queued
     * so an input pressed while the overlay is hidden never fires unexpectedly later.
     */
    public void testModelFunction(String token, String mode, boolean pressed,
                                  android.webkit.ValueCallback<Boolean> callback) {
        if (callback == null) return;
        if (token == null || token.isEmpty() || released || lifecycleSuspended) {
            callback.onReceiveValue(false);
            return;
        }
        String trigger = mode == null || mode.isEmpty() ? KeyboardCatFunctionBindingStore.MODE_TOGGLE : mode;
        if (!pageReady) {
            PendingModelFunctionTest previous = pendingModelFunctionTest;
            pendingModelFunctionTest = new PendingModelFunctionTest(
                    token, trigger, pressed, android.os.SystemClock.uptimeMillis() + 25000L, callback);
            if (previous != null && previous.callback != callback) previous.callback.onReceiveValue(false);
            return;
        }
        evaluateModelFunction(token, trigger, pressed, callback);
    }

    private void evaluateModelFunction(String token, String mode, boolean pressed,
                                       android.webkit.ValueCallback<Boolean> callback) {
        if (released || !pageReady || lifecycleSuspended) {
            callback.onReceiveValue(false);
            return;
        }
        try {
            String script;
            if (pressed) {
                // Test has preview semantics: parameter actions pulse and self-restore instead of
                // accidentally leaving a toggle/hold latched. Older runtimes retain a safe fallback.
                script = "Boolean(window.AxonBongoCat&&("
                        + "(AxonBongoCat.testModelFunction&&AxonBongoCat.testModelFunction("
                        + JSONObject.quote(token) + "," + JSONObject.quote(mode) + "))||"
                        + "(!AxonBongoCat.testModelFunction&&AxonBongoCat.triggerModelFunction&&"
                        + "AxonBongoCat.triggerModelFunction(" + JSONObject.quote(token) + ","
                        + JSONObject.quote(mode) + ",true))))";
            } else {
                script = "Boolean(window.AxonBongoCat&&AxonBongoCat.triggerModelFunction&&"
                        + "AxonBongoCat.triggerModelFunction(" + JSONObject.quote(token) + ","
                        + JSONObject.quote(mode) + ",false))";
            }
            webView.evaluateJavascript(script,
                    raw -> callback.onReceiveValue("true".equalsIgnoreCase(raw)));
        } catch (Throwable error) {
            Log.e(TAG, "Model action test dispatch failed", error);
            callback.onReceiveValue(false);
        }
    }

    private void flushPendingModelFunctionTest() {
        PendingModelFunctionTest pending = pendingModelFunctionTest;
        if (pending == null || !pageReady || lifecycleSuspended || released) return;
        pendingModelFunctionTest = null;
        if (android.os.SystemClock.uptimeMillis() > pending.expiresAtMs) {
            pending.callback.onReceiveValue(false);
            return;
        }
        evaluateModelFunction(pending.token, pending.mode, pending.pressed, pending.callback);
    }

    public boolean isLoadedStyle(String expectedStyleId) {
        if (expectedStyleId == null || expectedStyleId.isEmpty()) return false;
        BongoCatStyleManager.StyleInfo style = loadedStyle;
        return style != null && expectedStyleId.equals(style.id);
    }

    /** Applies a debug expression from the imported style without changing its authored bindings. */
    public void setDebugExpression(String token) { setDebugExpression(token, 1f); }

    public void setDebugExpression(String token, float weight) {
        String raw = token == null ? "auto" : token.trim();
        String kind = "auto";
        int index = -1;
        int colon = raw.indexOf(':');
        if (colon > 0) {
            String parsedKind = raw.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            if ("live2d".equals(parsedKind) || "face".equals(parsedKind)) {
                try {
                    int parsedIndex = Integer.parseInt(raw.substring(colon + 1));
                    if (parsedIndex >= 0) { kind = parsedKind; index = parsedIndex; }
                } catch (NumberFormatException ignored) {}
            }
        }
        float nextWeight = Math.max(0f, Math.min(1f, weight));
        if (kind.equals(debugExpressionKind) && index == debugExpressionIndex
                && Math.abs(nextWeight - debugExpressionWeight) < 0.0001f) return;
        debugExpressionKind = kind;
        debugExpressionIndex = index;
        debugExpressionWeight = nextWeight;
        if (pageReady) dispatchDebugExpression();
    }

    private void dispatchDebugExpression() {
        if (!pageReady) return;
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.setDebugExpression("
                + JSONObject.quote(debugExpressionKind) + "," + debugExpressionIndex + ","
                + String.format(java.util.Locale.US, "%.4f", debugExpressionWeight) + ")");
    }

    public boolean isGamepadStyle() {
        return BongoCatStyleManager.MODE_GAMEPAD.equals(styleMode);
    }

    /**
     * false = BongoCat keyboard model (arrow-key block);
     * true  = BongoCat original standard model (mouse + mouse pad in the same region).
     */
    public void setMouseMode(boolean enabled) {
        if (mouseMode == enabled) return;
        mouseMode = enabled;
        loadCurrentMode();
    }

    private void loadCurrentMode() {
        runtimeRecoveryStage = 0;
        loadCurrentModeInternal(false, false);
    }

    private void loadCurrentModeInternal(boolean forceSpriteFallback, boolean forceBuiltin) {
        if (released) return;
        // Explicitly release the old WebGL textures/programs before navigating to another style.
        // Relying on WebView GC lets two large models overlap in GPU memory during a switch.
        try { dispatchRaw("window.AxonBongoCat&&AxonBongoCat.dispose&&AxonBongoCat.dispose()"); }
        catch (Throwable ignored) {}
        runtimeGeneration++;
        pageReady = false;
        removeCallbacks(mouseFrameDrain);
        mouseFrameScheduled = false;
        pendingMouseDx = 0;
        pendingMouseDy = 0;
        pendingMousePressPulses = mouseButtons;
        gamepadButtons = 0;
        gamepadLx = gamepadLy = gamepadRx = gamepadRy = 0;
        gamepadLt = gamepadRt = 0;
        gamepadLtPressed = false;
        gamepadRtPressed = false;
        try { webView.stopLoading(); } catch (Throwable ignored) {}

        BongoCatStyleManager.StyleInfo style = forceBuiltin
                ? BongoCatStyleManager.get(getContext(), BongoCatStyleManager.BUILTIN_ID)
                : BongoCatStyleManager.get(getContext(), styleId);
        loadedStyle = style;
        if (!forceBuiltin) styleId = style.id;
        if (!style.builtin) {
            // Re-detect controller-capable community packs imported by older Axon versions.
            styleMode = BongoCatStyleManager.effectiveMode(style);
            try {
                JSONObject config = BongoCatStyleManager.runtimeConfig(style, forceSpriteFallback);
                RenderQuality.applyRuntimeConfig(config, renderQuality);
                config.put("physicsControls", Live2DPhysicsSettingsStore.runtimeJson(
                        getContext(), Live2DPhysicsSettingsStore.keyboardCatTarget(style.id)));
                config.put("savedParameterValues",
                        KeyboardCatFunctionBindingStore.parameterValuesJson(getContext(), style.id));
                config.put("parameterLocks", Live2DDebugSettingsStore.parameterLocksJson(
                        getContext(), Live2DPhysicsSettingsStore.keyboardCatTarget(style.id)));
                String template = readAssetText("bongocat/custom/index.html");
                String bootstrap = "window.__AXON_STYLE_CONFIG__=" + config.toString() + ";";
                String core = escapeInlineScript(readAssetText("bongocat/live2dcubismcore.min.js"));
                String runtime = escapeInlineScript(readAssetText("bongocat/custom/runtime.js"));
                String html = template
                        .replace("__AXON_STYLE_BOOTSTRAP__", bootstrap)
                        .replace("__AXON_CUBISM_CORE__", core)
                        .replace("__AXON_CUSTOM_RUNTIME__", runtime);

                // Use the imported style directory itself as the document origin. On newer/OEM
                // WebViews a file:///android_asset page may be denied access to file:///data/user
                // textures even when setAllowFileAccessFromFileURLs(true). Same-origin style-root
                // loading removes that fragile cross-file-origin dependency.
                String baseUrl = Uri.fromFile(style.root).toString();
                if (!baseUrl.endsWith("/")) baseUrl += "/";
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", baseUrl);
                return;
            } catch (Throwable error) {
                Log.e(TAG, "Imported style load preparation failed: " + style.id, error);
                recoverRuntime(runtimeGeneration, "prepare");
                return;
            }
        }
        styleMode = mouseMode ? BongoCatStyleManager.MODE_STANDARD : BongoCatStyleManager.MODE_KEYBOARD;
        webView.loadUrl(mouseMode ? MOUSE_PAGE_URL : KEYBOARD_PAGE_URL);
    }

    private static String escapeInlineScript(String source) {
        if (source == null || source.isEmpty()) return "";
        return source.replace("</script", "<\\/script");
    }

    private void probeRuntimeReady(int generation, int attempt) {
        if (released || generation != runtimeGeneration || pageReady) return;
        try {
            webView.evaluateJavascript(
                    "Boolean(window.AxonBongoCat&&typeof AxonBongoCat.key==='function'"
                            + "&&(!AxonBongoCat.isReady||AxonBongoCat.isReady()))",
                    result -> {
                        if (released || generation != runtimeGeneration || pageReady) return;
                        if ("true".equals(result)) {
                            pageReady = true;
                            Log.i(TAG, "Runtime fully ready, generation=" + generation
                                    + ", recoveryStage=" + runtimeRecoveryStage);
                            dispatchRenderQuality();
                            flushInputState();
                            syncRuntimeLifecycle();
                            flushPendingModelFunctionTest();
                            logRuntimeState(generation);
                            return;
                        }
                        if (attempt + 1 >= RUNTIME_PROBE_MAX_ATTEMPTS) {
                            Log.e(TAG, "Runtime not ready after " + RUNTIME_PROBE_MAX_ATTEMPTS
                                    + " probes, generation=" + generation);
                            recoverRuntime(generation, "timeout");
                            return;
                        }
                        postDelayed(() -> probeRuntimeReady(generation, attempt + 1), RUNTIME_PROBE_DELAY_MS);
                    });
        } catch (Throwable error) {
            Log.e(TAG, "Runtime probe failed", error);
            recoverRuntime(generation, "probe");
        }
    }

    private void recoverRuntime(int generation, String reason) {
        if (released || generation != runtimeGeneration) return;
        BongoCatStyleManager.StyleInfo style = loadedStyle;
        if (style != null && !style.builtin
                && BongoCatStyleManager.isMverStyle(style)
                && runtimeRecoveryStage == 0) {
            runtimeRecoveryStage = 1;
            Log.w(TAG, "Imported Mver Live2D failed (" + reason + "), retrying sprite renderer");
            post(() -> loadCurrentModeInternal(true, false));
            return;
        }
        if (style != null && !style.builtin && runtimeRecoveryStage < 2) {
            runtimeRecoveryStage = 2;
            Log.w(TAG, "Imported style failed (" + reason + "), falling back to built-in renderer");
            post(() -> loadCurrentModeInternal(false, true));
            return;
        }
        Log.e(TAG, "BongoCat runtime recovery exhausted: " + reason);
    }

    private void logRuntimeState(int generation) {
        webView.evaluateJavascript(
                "(window.AxonBongoCat&&AxonBongoCat.debugState)"
                        + "?JSON.stringify(AxonBongoCat.debugState()):'no-debug-state'",
                result -> {
                    if (generation == runtimeGeneration) Log.i(TAG, "Runtime state: " + result);
                });
    }

    private String readAssetText(String path) throws Exception {
        try (InputStream in = getContext().getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * Horizontal input reverse only: keep the Live2D cat and background unchanged, while
     * remapping keyboard/arrow targets and reversing only the mouse object's ParamMouseX.
     */
    public void setGlobalReverse(boolean enabled) {
        if (globalReverse == enabled) return;
        globalReverse = enabled;
        if (pageReady) flushInputState();
    }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled && dragging) finishDrag();
    }

    /** BongoCat 源窗口本身没有进入缩放动画；这里保持无额外视觉变形。 */
    public void animateIn() {
        exitCallback = null;
        animate().cancel();
        setScaleX(1f);
        setScaleY(1f);
    }

    public void animateOut(Runnable endAction) {
        animate().cancel();
        exitCallback = endAction;
        Runnable callback = exitCallback;
        exitCallback = null;
        if (callback != null) post(callback);
    }

    public void setKeyState(int keyCode, boolean pressed) {
        String key = sourceKeyName(keyCode);
        if (key == null) return;

        boolean dispatchTransition = false;
        synchronized (inputBridgeLock) {
            boolean wasPressed = pressedKeyCodes.get(keyCode);
            // Android key-repeat and overlapping input sources can report the same state many times.
            // The model only needs transitions; dropping duplicates removes avoidable JS bridge work.
            if (wasPressed == pressed) return;

            int semanticCount = pressedSemanticCounts.getOrDefault(key, 0);
            if (pressed) {
                pressedKeyCodes.put(keyCode, true);
                int nextCount = semanticCount + 1;
                pressedSemanticCounts.put(key, nextCount);
                if (semanticCount == 0) {
                    pressedKeyOrder.remove(key);
                    pressedKeyOrder.add(key);
                    dispatchTransition = true;
                }
            } else {
                pressedKeyCodes.delete(keyCode);
                int nextCount = Math.max(0, semanticCount - 1);
                if (nextCount == 0) {
                    pressedSemanticCounts.remove(key);
                    pressedKeyOrder.remove(key);
                    dispatchTransition = semanticCount > 0;
                } else {
                    pressedSemanticCounts.put(key, nextCount);
                }
            }
            if (dispatchTransition) rebuildNativeKeySnapshotLocked();
        }

        // Multiple physical key codes can intentionally share one model semantic (for example
        // Enter/NumpadEnter). Only release the semantic after its last physical source is released.
        if (dispatchTransition) {
            dispatch("window.AxonBongoCat&&AxonBongoCat.key(" + JSONObject.quote(key) + "," + pressed + ")");
        }
    }

    private void rebuildNativeKeySnapshotLocked() {
        JSONArray keys = new JSONArray();
        for (String held : pressedKeyOrder) keys.put(held);
        JSONObject result = new JSONObject();
        try { result.put("keys", keys); } catch (Exception ignored) {}
        nativeKeySnapshot = result.toString();
    }

    public void setMouseButtons(int buttons) {
        int nextButtons = buttons & 0x3;
        if (nextButtons == mouseButtons) return;
        pendingMousePressPulses |= nextButtons & ~mouseButtons;
        mouseButtons = nextButtons;
        scheduleMouseFrame();
    }

    /** Mver standard 的 mouse_side 支持 M4/M5；中键也作为 VK_MBUTTON 传入组合键。 */
    public void setMouseAuxButton(int button, boolean pressed) {
        String semantic = switch (button) {
            case MouseInputMonitor.BUTTON_MIDDLE -> "MouseMiddle";
            case MouseInputMonitor.BUTTON_BACK -> "MouseSide1";
            case MouseInputMonitor.BUTTON_FORWARD -> "MouseSide2";
            default -> null;
        };
        if (semantic == null) return;
        int bit = 1 << button;
        boolean wasPressed = (mouseAuxButtons & bit) != 0;
        if (wasPressed == pressed) return;
        if (pressed) mouseAuxButtons |= bit;
        else mouseAuxButtons &= ~bit;
        dispatch("window.AxonBongoCat&&AxonBongoCat.key(" + JSONObject.quote(semantic) + "," + pressed + ")");
    }

    /**
     * Android 全局输入层提供 REL_X / REL_Y；将其累积为屏幕归一化光标，再交给源模型的
     * ParamAngle / ParamEyeBall 参数映射。这样保留 BongoCat 的 0.75 阻尼跟随逻辑。
     */
    public void addMouseMotion(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        // Do not replay seconds of hidden relative motion as one giant jump when the overlay resumes.
        if (lifecycleSuspended) return;
        pendingMouseDx = saturatingAdd(pendingMouseDx, dx);
        pendingMouseDy = saturatingAdd(pendingMouseDy, dy);
        scheduleMouseFrame();
    }

    public void setGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        if (!isGamepadStyle()) return;

        // BTN_C/BTN_Z are Linux fallback aliases for X/B. The rest of Axon's gamepad HUD already
        // treats them as the same physical groups; canonicalize them here too so Keyboard Cat
        // never receives a second logical button for one physical face key.
        int next = buttons;
        if ((next & GamepadOverlayView.BTN_C) != 0) next |= GamepadOverlayView.BTN_WEST;
        if ((next & GamepadOverlayView.BTN_Z) != 0) next |= GamepadOverlayView.BTN_EAST;
        next &= ~(GamepadOverlayView.BTN_C | GamepadOverlayView.BTN_Z);

        int previousButtons = gamepadButtons;
        boolean previousLt = gamepadLtPressed;
        boolean previousRt = gamepadRtPressed;
        boolean axesChanged = lx != gamepadLx || ly != gamepadLy || rx != gamepadRx || ry != gamepadRy
                || ((previousButtons ^ next) & (GamepadOverlayView.BTN_L3 | GamepadOverlayView.BTN_R3)) != 0;

        gamepadLx = lx;
        gamepadLy = ly;
        gamepadRx = rx;
        gamepadRy = ry;
        gamepadLt = lt;
        gamepadRt = rt;
        gamepadButtons = next;
        gamepadLtPressed = (next & GamepadOverlayView.BTN_L2) != 0 || lt >= 500;
        gamepadRtPressed = (next & GamepadOverlayView.BTN_R2) != 0 || rt >= 500;

        // Send the complete digital snapshot in one JS bridge call. Releasing stale semantics and
        // pressing new ones atomically prevents transient X/L1 overlap and multi-hand frames.
        if (previousButtons != next || previousLt != gamepadLtPressed || previousRt != gamepadRtPressed) {
            dispatchGamepadDigitalState();
        }
        if (axesChanged) scheduleGamepadFrame();
    }

    private void dispatchGamepadDigitalState() {
        dispatch("window.AxonBongoCat&&AxonBongoCat.gamepadState&&AxonBongoCat.gamepadState("
                + gamepadButtons + "," + gamepadLtPressed + "," + gamepadRtPressed + ")");
    }

    private void scheduleGamepadFrame() {
        if (!pageReady || lifecycleSuspended || gamepadFrameScheduled) return;
        gamepadFrameScheduled = true;
        postOnAnimation(gamepadFrameDrain);
    }

    private void drainGamepadFrame() {
        gamepadFrameScheduled = false;
        if (!pageReady || lifecycleSuspended || !isGamepadStyle()) return;
        dispatchGamepadAxes();
    }

    private void dispatchGamepadAxes() {
        boolean l3 = (gamepadButtons & GamepadOverlayView.BTN_L3) != 0;
        boolean r3 = (gamepadButtons & GamepadOverlayView.BTN_R3) != 0;
        float nlx = Math.max(-1f, Math.min(1f, gamepadLx / 1000f));
        float nly = Math.max(-1f, Math.min(1f, gamepadLy / 1000f));
        float nrx = Math.max(-1f, Math.min(1f, gamepadRx / 1000f));
        float nry = Math.max(-1f, Math.min(1f, gamepadRy / 1000f));
        dispatch("window.AxonBongoCat&&AxonBongoCat.gamepadAxes(" + nlx + "," + nly + "," + nrx + "," + nry
                + "," + l3 + "," + r3 + ")");
    }

    public void setGamepadDirectional(int keyCode, boolean pressed) {
        if (!isGamepadStyle()) return;
        int bit = switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP -> GamepadOverlayView.BTN_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN -> GamepadOverlayView.BTN_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT -> GamepadOverlayView.BTN_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadOverlayView.BTN_DPAD_RIGHT;
            default -> 0;
        };
        if (bit == 0) return;
        boolean before = (gamepadButtons & bit) != 0;
        if (before == pressed) return;
        if (pressed) gamepadButtons |= bit;
        else gamepadButtons &= ~bit;
        dispatchGamepadDigitalState();
    }


    public void clearInput() {
        synchronized (inputBridgeLock) {
            pressedKeyCodes.clear();
            pressedKeyOrder.clear();
            pressedSemanticCounts.clear();
            nativeKeySnapshot = "{\"keys\":[]}";
        }
        mouseButtons = 0;
        mouseAuxButtons = 0;
        pendingMouseDx = 0;
        pendingMouseDy = 0;
        pendingMousePressPulses = 0;
        gamepadButtons = 0;
        gamepadLx = gamepadLy = gamepadRx = gamepadRy = 0;
        gamepadLt = gamepadRt = 0;
        gamepadLtPressed = false;
        gamepadRtPressed = false;
        removeCallbacks(mouseFrameDrain);
        mouseFrameScheduled = false;
        removeCallbacks(gamepadFrameDrain);
        gamepadFrameScheduled = false;
        dispatch("window.AxonBongoCat&&AxonBongoCat.clear()");
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // WebView 在部分 ROM 上会直接吞掉 TYPE_ACCESSIBILITY_OVERLAY 的触摸事件。
        // 拖动开启时由外层优先接管整条手势，保证手指和外接鼠标都能稳定拖动。
        if (dragEnabled) return handleDragTouch(event);
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return dragEnabled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return dragEnabled && handleDragTouch(event);
    }

    private boolean handleDragTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                dragStartRawX = event.getRawX();
                dragStartRawY = event.getRawY();
                if (dragListener != null) {
                    dragListener.onDragStart(this, dragStartRawX, dragStartRawY);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && dragListener != null) {
                    dragListener.onDragMove(this, event.getRawX(), event.getRawY());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishDrag();
                return true;
            default:
                return true;
        }
    }

    private void finishDrag() {
        if (!dragging) return;
        dragging = false;
        if (dragListener != null) dragListener.onDragEnd(this);
    }

    private void flushInputState() {
        if (!pageReady) return;
        removeCallbacks(mouseFrameDrain);
        mouseFrameScheduled = false;
        removeCallbacks(gamepadFrameDrain);
        gamepadFrameScheduled = false;
        pendingMouseDx = 0;
        pendingMouseDy = 0;
        pendingMousePressPulses = 0;
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.clear()");
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.setGlobalReverse(" + globalReverse + ")");
        if (isGamepadStyle()) {
            flushGamepadState();
            dispatchDebugExpression();
            return;
        }
        // BongoCat 源每个 left/right group 只保留最后一个按下贴图；按顺序重放即可恢复该语义。
        String[] heldKeys;
        synchronized (inputBridgeLock) {
            heldKeys = pressedKeyOrder.toArray(new String[0]);
        }
        for (String key : heldKeys) {
            dispatchRaw("window.AxonBongoCat&&AxonBongoCat.key(" + JSONObject.quote(key) + ",true)");
        }
        dispatchMouseFrame(mouseButtons, mouseButtons, 0, 0);
        if ((mouseAuxButtons & (1 << MouseInputMonitor.BUTTON_MIDDLE)) != 0) {
            dispatchRaw("window.AxonBongoCat&&AxonBongoCat.key('MouseMiddle',true)");
        }
        if ((mouseAuxButtons & (1 << MouseInputMonitor.BUTTON_BACK)) != 0) {
            dispatchRaw("window.AxonBongoCat&&AxonBongoCat.key('MouseSide1',true)");
        }
        if ((mouseAuxButtons & (1 << MouseInputMonitor.BUTTON_FORWARD)) != 0) {
            dispatchRaw("window.AxonBongoCat&&AxonBongoCat.key('MouseSide2',true)");
        }
        dispatchDebugExpression();
    }

    private void flushGamepadState() {
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.gamepadState&&AxonBongoCat.gamepadState("
                + gamepadButtons + "," + gamepadLtPressed + "," + gamepadRtPressed + ")");
        dispatchGamepadAxes();
    }

    private void scheduleMouseFrame() {
        if (!pageReady || lifecycleSuspended || mouseFrameScheduled) return;
        mouseFrameScheduled = true;
        postOnAnimation(mouseFrameDrain);
    }

    private void drainMouseFrame() {
        mouseFrameScheduled = false;
        if (!pageReady || lifecycleSuspended) return;

        int dx = pendingMouseDx;
        int dy = pendingMouseDy;
        int pulses = pendingMousePressPulses & 0x3;
        pendingMouseDx = 0;
        pendingMouseDy = 0;
        pendingMousePressPulses = 0;
        dispatchMouseFrame(mouseButtons, pulses, dx, dy);
    }

    private void dispatchMouseFrame(int buttons, int pulses, int dx, int dy) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);
        dispatchRaw("window.AxonBongoCat&&"
                + "(AxonBongoCat.mouseFrame?AxonBongoCat.mouseFrame("
                + (buttons & 0x3) + "," + (pulses & 0x3) + "," + dx + "," + dy + ","
                + width + "," + height + ")"
                + ":(AxonBongoCat.mouseButtons(" + (buttons & 0x3) + "),"
                + "AxonBongoCat.mouseDelta(" + dx + "," + dy + "," + width + "," + height + ")))");
    }

    private static int saturatingAdd(int current, int delta) {
        long value = (long) current + delta;
        if (value > 32767L) return 32767;
        if (value < -32768L) return -32768;
        return (int) value;
    }

    private void dispatch(String javascript) {
        if (!pageReady || lifecycleSuspended) return;
        dispatchRaw(javascript);
    }

    private void dispatchRaw(String javascript) {
        try {
            webView.evaluateJavascript(javascript, null);
        } catch (Throwable error) {
            Log.e(TAG, "JavaScript dispatch failed", error);
        }
    }

    /** rdev/BongoCat 资源名 -> Android KeyEvent 映射。仅映射源 keyboard model 实际支持的键。 */
    private static String sourceKeyName(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            char letter = (char) ('A' + (keyCode - KeyEvent.KEYCODE_A));
            return "Key" + letter;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            int digit = keyCode - KeyEvent.KEYCODE_0;
            return "Num" + digit;
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return "F" + (keyCode - KeyEvent.KEYCODE_F1 + 1);
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return "Numpad" + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }

        return switch (keyCode) {
            case KeyEvent.KEYCODE_ALT_LEFT -> "Alt";
            case KeyEvent.KEYCODE_ALT_RIGHT -> "AltGr";
            case KeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft";
            case KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight";
            case KeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft";
            case KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight";
            case KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> "Meta";
            case KeyEvent.KEYCODE_GRAVE -> "BackQuote";
            case KeyEvent.KEYCODE_DEL -> "Backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL -> "Delete";
            case KeyEvent.KEYCODE_BREAK -> "Pause";
            case KeyEvent.KEYCODE_CAPS_LOCK -> "CapsLock";
            case KeyEvent.KEYCODE_ESCAPE -> "Escape";
            case KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Return";
            case KeyEvent.KEYCODE_SLASH -> "Slash";
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "NumpadDivide";
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "NumpadMultiply";
            case KeyEvent.KEYCODE_NUMPAD_ADD -> "NumpadAdd";
            case KeyEvent.KEYCODE_BACKSLASH -> "BackSlash";
            case KeyEvent.KEYCODE_COMMA -> "Comma";
            case KeyEvent.KEYCODE_PERIOD -> "Dot";
            case KeyEvent.KEYCODE_NUMPAD_DOT -> "NumpadDot";
            case KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_EQUALS -> "Equal";
            case KeyEvent.KEYCODE_LEFT_BRACKET -> "LeftBracket";
            case KeyEvent.KEYCODE_MINUS -> "Minus";
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "NumpadSubtract";
            case KeyEvent.KEYCODE_APOSTROPHE -> "Quote";
            case KeyEvent.KEYCODE_RIGHT_BRACKET -> "RightBracket";
            case KeyEvent.KEYCODE_SEMICOLON -> "SemiColon";
            case KeyEvent.KEYCODE_SPACE -> "Space";
            case KeyEvent.KEYCODE_TAB -> "Tab";
            case KeyEvent.KEYCODE_MOVE_HOME -> "Home";
            case KeyEvent.KEYCODE_MOVE_END -> "End";
            case KeyEvent.KEYCODE_PAGE_UP -> "PageUp";
            case KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown";
            case KeyEvent.KEYCODE_INSERT -> "Insert";
            case KeyEvent.KEYCODE_SYSRQ -> "PrintScreen";
            case KeyEvent.KEYCODE_NUM_LOCK -> "NumLock";
            case KeyEvent.KEYCODE_SCROLL_LOCK -> "ScrollLock";
            case KeyEvent.KEYCODE_VOLUME_MUTE -> "VolumeMute";
            case KeyEvent.KEYCODE_VOLUME_DOWN -> "VolumeDown";
            case KeyEvent.KEYCODE_VOLUME_UP -> "VolumeUp";
            case KeyEvent.KEYCODE_MEDIA_NEXT -> "MediaNext";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MediaPrevious";
            case KeyEvent.KEYCODE_MEDIA_STOP -> "MediaStop";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "MediaPlayPause";
            case KeyEvent.KEYCODE_MENU -> "Apps";
            case KeyEvent.KEYCODE_DPAD_UP -> "UpArrow";
            case KeyEvent.KEYCODE_DPAD_DOWN -> "DownArrow";
            case KeyEvent.KEYCODE_DPAD_LEFT -> "LeftArrow";
            case KeyEvent.KEYCODE_DPAD_RIGHT -> "RightArrow";
            default -> null;
        };
    }
    private void setLifecycleSuspended(boolean suspended) {
        if (released || lifecycleSuspended == suspended) return;
        lifecycleSuspended = suspended;
        if (suspended) {
            removeCallbacks(mouseFrameDrain);
            mouseFrameScheduled = false;
            removeCallbacks(gamepadFrameDrain);
            gamepadFrameScheduled = false;
            pendingMouseDx = 0;
            pendingMouseDy = 0;
            try { dispatchRaw("window.AxonBongoCat&&AxonBongoCat.pause&&AxonBongoCat.pause()"); }
            catch (Throwable ignored) {}
            try { webView.onPause(); } catch (Throwable ignored) {}
            return;
        }
        try { webView.onResume(); } catch (Throwable ignored) {}
        syncRuntimeLifecycle();
        if (pageReady) flushInputState();
    }

    private void syncRuntimeLifecycle() {
        if (!pageReady || released) return;
        dispatchRaw(lifecycleSuspended
                ? "window.AxonBongoCat&&AxonBongoCat.pause&&AxonBongoCat.pause()"
                : "window.AxonBongoCat&&AxonBongoCat.resume&&AxonBongoCat.resume()");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setLifecycleSuspended(getWindowVisibility() != VISIBLE);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (!released) setLifecycleSuspended(visibility != VISIBLE);
    }

    /** Explicit final cleanup. A WindowManager detach can be transient on some ROMs/rotations, so
     * destroying WebView from onDetachedFromWindow() leaves a permanently blank overlay. */
    public void release() {
        if (released) return;
        released = true;
        runtimeGeneration++;
        if (pageReady) {
            try { dispatchRaw("window.AxonBongoCat&&AxonBongoCat.dispose&&AxonBongoCat.dispose()"); }
            catch (Throwable ignored) {}
        }
        pageReady = false;
        PendingModelFunctionTest pendingTest = pendingModelFunctionTest;
        pendingModelFunctionTest = null;
        if (pendingTest != null) pendingTest.callback.onReceiveValue(false);
        removeCallbacks(mouseFrameDrain);
        mouseFrameScheduled = false;
        removeCallbacks(gamepadFrameDrain);
        gamepadFrameScheduled = false;
        pendingMouseDx = 0;
        pendingMouseDy = 0;
        try { webView.removeJavascriptInterface("AxonNativeInput"); } catch (Throwable ignored) {}
        try { webView.stopLoading(); } catch (Throwable ignored) {}
        try { webView.setWebChromeClient(null); } catch (Throwable ignored) {}
        try { webView.setWebViewClient(null); } catch (Throwable ignored) {}
        try { webView.destroy(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onDetachedFromWindow() {
        setLifecycleSuspended(true);
        removeCallbacks(mouseFrameDrain);
        mouseFrameScheduled = false;
        removeCallbacks(gamepadFrameDrain);
        gamepadFrameScheduled = false;
        super.onDetachedFromWindow();
    }

}
