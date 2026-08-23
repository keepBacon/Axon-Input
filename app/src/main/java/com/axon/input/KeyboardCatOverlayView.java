package com.axon.input;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
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
    private static final int RUNTIME_PROBE_MAX_ATTEMPTS = 180;
    private static final long RUNTIME_PROBE_DELAY_MS = 16L;

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
    private final Object inputBridgeLock = new Object();

    private DragListener dragListener;
    private boolean dragEnabled;
    private boolean dragging;
    private boolean pageReady;
    private int runtimeGeneration;
    private boolean mouseMode;
    private boolean globalReverse;
    private String styleId;
    private String styleMode = BongoCatStyleManager.MODE_KEYBOARD;
    private String debugExpressionKind = "auto";
    private int debugExpressionIndex = -1;
    private int gamepadButtons;
    private int gamepadLx;
    private int gamepadLy;
    private int gamepadRx;
    private int gamepadRy;
    private int gamepadLt;
    private int gamepadRt;
    private int gamepadDirectionalMask;
    private boolean gamepadLtPressed;
    private boolean gamepadRtPressed;
    private int mouseButtons;
    private int pendingMouseDx;
    private int pendingMouseDy;
    private int pendingMousePressPulses;
    private boolean mouseFrameScheduled;
    private float dragStartRawX;
    private float dragStartRawY;
    private Runnable exitCallback;

    private final Runnable mouseFrameDrain = this::drainMouseFrame;

    /**
     * Pull-based fallback for Mver. Some Android WebView/ROM combinations can drop an
     * evaluateJavascript call while the transparent overlay is being attached. The page polls
     * this snapshot and reconciles held keys, so input state cannot get permanently desynced.
     */
    private final class NativeInputBridge {
        @JavascriptInterface
        public String snapshotKeys() {
            JSONObject result = new JSONObject();
            JSONArray keys = new JSONArray();
            synchronized (inputBridgeLock) {
                for (String key : pressedKeyOrder) keys.put(key);
            }
            try { result.put("keys", keys); } catch (Exception ignored) {}
            return result.toString();
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
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
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
                }
            }
        });

        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mouseMode = OverlayState.isKeyboardCatMouseMode(context);
        globalReverse = OverlayState.isKeyboardCatGlobalReverse(context);
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

    /** Applies a debug expression from the imported style without changing its authored bindings. */
    public void setDebugExpression(String token) {
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
        if (kind.equals(debugExpressionKind) && index == debugExpressionIndex) return;
        debugExpressionKind = kind;
        debugExpressionIndex = index;
        if (pageReady) dispatchDebugExpression();
    }

    private void dispatchDebugExpression() {
        if (!pageReady) return;
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.setDebugExpression("
                + JSONObject.quote(debugExpressionKind) + "," + debugExpressionIndex + ")");
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
        gamepadDirectionalMask = 0;
        gamepadLtPressed = false;
        gamepadRtPressed = false;
        webView.stopLoading();

        BongoCatStyleManager.StyleInfo style = BongoCatStyleManager.get(getContext(), styleId);
        styleId = style.id;
        if (!style.builtin) {
            styleMode = style.mode;
            try {
                JSONObject config = BongoCatStyleManager.runtimeConfig(style);
                String template = readAssetText("bongocat/custom/index.html");
                String bootstrap = "window.__AXON_STYLE_CONFIG__=" + config.toString() + ";";
                String html = template.replace("__AXON_STYLE_BOOTSTRAP__", bootstrap);
                webView.loadDataWithBaseURL("file:///android_asset/bongocat/custom/", html, "text/html", "utf-8", null);
                return;
            } catch (Throwable ignored) {
                styleId = BongoCatStyleManager.BUILTIN_ID;
            }
        }
        styleMode = mouseMode ? BongoCatStyleManager.MODE_STANDARD : BongoCatStyleManager.MODE_KEYBOARD;
        webView.loadUrl(mouseMode ? MOUSE_PAGE_URL : KEYBOARD_PAGE_URL);
    }

    private void probeRuntimeReady(int generation, int attempt) {
        if (generation != runtimeGeneration || pageReady) return;
        webView.evaluateJavascript(
                "Boolean(window.AxonBongoCat&&typeof AxonBongoCat.key==='function')",
                result -> {
                    if (generation != runtimeGeneration || pageReady) return;
                    if ("true".equals(result)) {
                        pageReady = true;
                        Log.i(TAG, "Runtime input bridge ready, generation=" + generation);
                        flushInputState();
                        logRuntimeState(generation);
                        return;
                    }
                    if (attempt + 1 >= RUNTIME_PROBE_MAX_ATTEMPTS) {
                        Log.e(TAG, "Runtime input bridge unavailable after "
                                + RUNTIME_PROBE_MAX_ATTEMPTS + " probes");
                        return;
                    }
                    postDelayed(() -> probeRuntimeReady(generation, attempt + 1), RUNTIME_PROBE_DELAY_MS);
                });
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

        synchronized (inputBridgeLock) {
            if (pressed) {
                if (!pressedKeyCodes.get(keyCode)) {
                    pressedKeyCodes.put(keyCode, true);
                    // LinkedHashSet 用于页面重新加载时按真实按下顺序恢复源行为。
                    pressedKeyOrder.remove(key);
                    pressedKeyOrder.add(key);
                }
            } else {
                pressedKeyCodes.delete(keyCode);
                pressedKeyOrder.remove(key);
            }
        }

        dispatch("window.AxonBongoCat&&AxonBongoCat.key(" + JSONObject.quote(key) + "," + pressed + ")");
    }

    public void setMouseButtons(int buttons) {
        int nextButtons = buttons & 0x3;
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
        dispatch("window.AxonBongoCat&&AxonBongoCat.key(" + JSONObject.quote(semantic) + "," + pressed + ")");
    }

    /**
     * Android 全局输入层提供 REL_X / REL_Y；将其累积为屏幕归一化光标，再交给源模型的
     * ParamAngle / ParamEyeBall 参数映射。这样保留 BongoCat 的 0.75 阻尼跟随逻辑。
     */
    public void addMouseMotion(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        pendingMouseDx = saturatingAdd(pendingMouseDx, dx);
        pendingMouseDy = saturatingAdd(pendingMouseDy, dy);
        scheduleMouseFrame();
    }

    public void setGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        if (!isGamepadStyle()) return;
        gamepadLx = lx;
        gamepadLy = ly;
        gamepadRx = rx;
        gamepadRy = ry;
        gamepadLt = lt;
        gamepadRt = rt;
        int next = buttons;
        dispatchGamepadTransition(GamepadOverlayView.BTN_SOUTH, "South", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_EAST, "East", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_NORTH, "North", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_WEST, "West", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_C, "C", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_Z, "Z", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_L1, "LeftTrigger", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_R1, "RightTrigger", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_SELECT, "Select", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_START, "Start", gamepadButtons, next);
        dispatchGamepadTransition(GamepadOverlayView.BTN_MODE, "Mode", gamepadButtons, next);

        boolean nextLt = (next & GamepadOverlayView.BTN_L2) != 0 || lt >= 80;
        boolean nextRt = (next & GamepadOverlayView.BTN_R2) != 0 || rt >= 80;
        if (nextLt != gamepadLtPressed) dispatchGamepadButton("LeftTrigger2", nextLt);
        if (nextRt != gamepadRtPressed) dispatchGamepadButton("RightTrigger2", nextRt);
        gamepadLtPressed = nextLt;
        gamepadRtPressed = nextRt;
        gamepadButtons = next;

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
            case KeyEvent.KEYCODE_DPAD_UP -> 1;
            case KeyEvent.KEYCODE_DPAD_DOWN -> 2;
            case KeyEvent.KEYCODE_DPAD_LEFT -> 4;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> 8;
            default -> 0;
        };
        if (bit == 0) return;
        if (pressed) gamepadDirectionalMask |= bit;
        else gamepadDirectionalMask &= ~bit;
        dispatchGamepadButton(dpadName(bit), pressed);
    }

    private static String dpadName(int bit) {
        return switch (bit) {
            case 1 -> "DPadUp";
            case 2 -> "DPadDown";
            case 4 -> "DPadLeft";
            case 8 -> "DPadRight";
            default -> "";
        };
    }

    private void dispatchGamepadTransition(int bit, String name, int previous, int current) {
        boolean before = (previous & bit) != 0;
        boolean after = (current & bit) != 0;
        if (before != after) dispatchGamepadButton(name, after);
    }

    private void dispatchGamepadButton(String name, boolean pressed) {
        dispatch("window.AxonBongoCat&&AxonBongoCat.gamepadButton(" + JSONObject.quote(name) + "," + pressed + ")");
    }

    public void clearInput() {
        synchronized (inputBridgeLock) {
            pressedKeyCodes.clear();
            pressedKeyOrder.clear();
        }
        mouseButtons = 0;
        pendingMouseDx = 0;
        pendingMouseDy = 0;
        pendingMousePressPulses = 0;
        gamepadButtons = 0;
        gamepadLx = gamepadLy = gamepadRx = gamepadRy = 0;
        gamepadLt = gamepadRt = 0;
        gamepadDirectionalMask = 0;
        gamepadLtPressed = false;
        gamepadRtPressed = false;
        removeCallbacks(mouseFrameDrain);
        mouseFrameScheduled = false;
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
        dispatchDebugExpression();
    }

    private void flushGamepadState() {
        int[] bits = {
                GamepadOverlayView.BTN_SOUTH, GamepadOverlayView.BTN_EAST,
                GamepadOverlayView.BTN_C, GamepadOverlayView.BTN_NORTH,
                GamepadOverlayView.BTN_WEST, GamepadOverlayView.BTN_Z,
                GamepadOverlayView.BTN_L1, GamepadOverlayView.BTN_R1,
                GamepadOverlayView.BTN_SELECT, GamepadOverlayView.BTN_START,
                GamepadOverlayView.BTN_MODE
        };
        String[] names = {
                "South", "East", "C", "North", "West", "Z",
                "LeftTrigger", "RightTrigger", "Select", "Start", "Mode"
        };
        for (int i = 0; i < bits.length; i++) {
            if ((gamepadButtons & bits[i]) != 0) dispatchGamepadButton(names[i], true);
        }
        boolean ltPressed = (gamepadButtons & GamepadOverlayView.BTN_L2) != 0 || gamepadLt >= 80;
        boolean rtPressed = (gamepadButtons & GamepadOverlayView.BTN_R2) != 0 || gamepadRt >= 80;
        if (ltPressed) dispatchGamepadButton("LeftTrigger2", true);
        if (rtPressed) dispatchGamepadButton("RightTrigger2", true);
        for (int bit = 1; bit <= 8; bit <<= 1) {
            if ((gamepadDirectionalMask & bit) != 0) dispatchGamepadButton(dpadName(bit), true);
        }
        dispatchGamepadAxes();
    }

    private void scheduleMouseFrame() {
        if (!pageReady || mouseFrameScheduled) return;
        mouseFrameScheduled = true;
        postOnAnimation(mouseFrameDrain);
    }

    private void drainMouseFrame() {
        mouseFrameScheduled = false;
        if (!pageReady) return;

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
        if (!pageReady) return;
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
}
