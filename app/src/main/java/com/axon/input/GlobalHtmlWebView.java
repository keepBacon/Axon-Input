package com.axon.input;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** HTML 自定义显示层。每个悬浮窗口独立运行。Android 只推送输入和配置状态。 */
public final class GlobalHtmlWebView extends WebView {
    public static final int API_VERSION = 9;
    public static final String RENDERER_VERSION = "v26";
    public static final String TYPE_KEYBOARD = "keyboard";
    public static final String TYPE_MOUSE = "mouse";
    public static final String TYPE_CUSTOM = "custom";
    public static final String TYPE_MOUSE_TRAJECTORY = "mouse-trajectory";
    public static final String TYPE_KEY_PROMPT = "key-prompt";
    public static final String TYPE_GAMEPAD_LEFT_STICK = "gamepad-left-stick";
    public static final String TYPE_GAMEPAD_RIGHT_STICK = "gamepad-right-stick";
    public static final String TYPE_GAMEPAD_FACE = "gamepad-face";
    public static final String TYPE_GAMEPAD_LEFT_SHOULDER = "gamepad-left-shoulder";
    public static final String TYPE_GAMEPAD_RIGHT_SHOULDER = "gamepad-right-shoulder";

    private static final int BTN_SOUTH = 1 << 0;
    private static final int BTN_EAST = 1 << 1;
    private static final int BTN_C = 1 << 2;
    private static final int BTN_NORTH = 1 << 3;
    private static final int BTN_WEST = 1 << 4;
    private static final int BTN_Z = 1 << 5;
    private static final int BTN_L1 = 1 << 6;
    private static final int BTN_R1 = 1 << 7;
    private static final int BTN_L2 = 1 << 8;
    private static final int BTN_R2 = 1 << 9;
    private static final int BTN_L3 = 1 << 13;
    private static final int BTN_R3 = 1 << 14;

    private final String type;

    private int sizePercent = 100;
    private int dotSizePercent = 100;
    private int pressedMask;
    private boolean keyboardShowSpace = true;
    private boolean keyboardShowSpaceDps;
    private int keyboardSpaceDps;
    private long mouseStats;
    private int[] customKeyCodes = new int[0];
    private boolean[] customPressed = new boolean[0];
    private int customColumns = 4;
    private int[] promptIds = new int[0];
    private String[] promptLabels = new String[0];
    private boolean[] promptPressed = new boolean[0];
    private int[] promptCps = new int[0];
    private int[] promptPressCount = new int[0];

    private int pointerDx;
    private int pointerDy;
    private long pointerSequence;
    private boolean pointerDirty;
    private boolean gamepadDirty;
    private boolean realtimeFramePosted;
    private boolean fullStateFramePosted;

    private int gamepadLx;
    private int gamepadLy;
    private int gamepadRx;
    private int gamepadRy;
    private int gamepadLt;
    private int gamepadRt;
    private int gamepadButtons;
    private int stickShape = GamepadOverlayView.SHAPE_CIRCLE;
    private boolean faceReversed;
    private boolean faceYDpsEnabled;
    private boolean faceXDpsEnabled;
    private boolean faceBDpsEnabled;
    private boolean faceADpsEnabled;
    private boolean triggerProgressEnabled;
    private boolean shoulderDpsEnabled;
    private int faceYDps;
    private int faceXDps;
    private int faceBDps;
    private int faceADps;
    private int l1Dps;
    private int r1Dps;
    private boolean trajectoryLeftColorEnabled;
    private boolean trajectoryRightColorEnabled;
    private int trajectoryLeftColor = 0xffff3b30;
    private int trajectoryRightColor = 0xff34c759;

    private boolean pageReady;
    private String currentHtml = "";

    private final Runnable fullStateDispatch = new Runnable() {
        @Override public void run() {
            fullStateFramePosted = false;
            dispatchFullState();
        }
    };

    private final Runnable realtimeDispatch = new Runnable() {
        @Override public void run() {
            realtimeFramePosted = false;
            if (!pageReady) return;
            // 高频输入只发送变化状态，减少 JSON 重建。
            if (pointerDirty) {
                pointerDirty = false;
                dispatchRealtime("keydisplay:pointer", "pointer", pointerObject());
            }
            if (gamepadDirty) {
                gamepadDirty = false;
                dispatchRealtime("keydisplay:gamepad", "gamepad", gamepadObject());
            }
            pointerDx = 0;
            pointerDy = 0;
        }
    };

    public GlobalHtmlWebView(Context context, String type) {
        super(context);
        this.type = type == null ? TYPE_KEYBOARD : type;

        setBackgroundColor(Color.TRANSPARENT);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);

        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setDefaultTextEncodingName("utf-8");

        setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return uri != null && !"about".equalsIgnoreCase(uri.getScheme());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                installRuntimeApi();
                dispatch("keydisplay:init", buildStateSafely());
                dispatchFullState();
            }
        });
    }

    public void loadRendererHtml(String html) {
        String normalized = html == null ? "" : html;
        if (normalized.equals(currentHtml)) {
            if (pageReady) dispatchFullState();
            return;
        }
        currentHtml = normalized;
        pageReady = false;
        // 用户脚本执行前注入只读 API。
        String document = injectRuntimeBootstrap(normalized);
        loadDataWithBaseURL(
                "https://keydisplay.local/",
                document,
                "text/html",
                "UTF-8",
                "about:blank");
    }

    public void setDisplaySize(int percent) {
        sizePercent = Math.max(50, Math.min(150, percent));
        scheduleFullState();
    }

    public void setDotSizePercent(int percent) {
        dotSizePercent = Math.max(50, Math.min(150, percent));
        scheduleFullState();
    }

    public void setKeyboardOptions(boolean showSpace, boolean showSpaceDps) {
        keyboardShowSpace = showSpace;
        keyboardShowSpaceDps = showSpace && showSpaceDps;
        scheduleFullState();
    }

    public void setKeyboardDps(int dps) {
        keyboardSpaceDps = Math.max(0, Math.min(999, dps));
        scheduleFullState();
    }

    public void setKeyboardMask(int newMask) {
        if (pressedMask == newMask) return;
        int old = pressedMask;
        pressedMask = newMask;
        dispatchKeyboardChanges(old, newMask);
        scheduleFullState();
    }

    public void setCustomKeys(int[] keyCodes, int columns) {
        int[] next = keyCodes == null ? new int[0] : keyCodes.clone();
        boolean[] nextPressed = new boolean[next.length];
        for (int i = 0; i < next.length; i++) {
            for (int j = 0; j < customKeyCodes.length; j++) {
                if (next[i] == customKeyCodes[j]) {
                    nextPressed[i] = customPressed.length > j && customPressed[j];
                    break;
                }
            }
        }
        customKeyCodes = next;
        customPressed = nextPressed;
        customColumns = Math.max(1, Math.min(8, columns));
        scheduleFullState();
    }

    public void setCustomKeyPressed(int keyCode, boolean pressed) {
        for (int i = 0; i < customKeyCodes.length; i++) {
            if (customKeyCodes[i] == keyCode && customPressed[i] != pressed) {
                customPressed[i] = pressed;
                dispatch("keydisplay:key", keyObjectForCustom(i));
                scheduleFullState();
                return;
            }
        }
    }

    public void releaseCustomKeys() {
        boolean changed = false;
        for (int i = 0; i < customPressed.length; i++) {
            if (customPressed[i]) {
                customPressed[i] = false;
                dispatch("keydisplay:key", keyObjectForCustom(i));
                changed = true;
            }
        }
        if (changed) dispatchFullState();
    }

    /** 最近按键列表只在状态变化时更新。 */
    public void setPromptState(int[] ids, String[] labels, boolean[] pressed, int[] cps, int[] pressCount) {
        promptIds = ids == null ? new int[0] : ids.clone();
        promptLabels = labels == null ? new String[0] : labels.clone();
        promptPressed = pressed == null ? new boolean[0] : pressed.clone();
        promptCps = cps == null ? new int[0] : cps.clone();
        promptPressCount = pressCount == null ? new int[0] : pressCount.clone();
        scheduleFullState();
    }

    public void dispatchPromptKey(int id, String label, boolean pressed, int cps, int pressCount) {
        JSONObject key = new JSONObject();
        try {
            key.put("id", "prompt-" + id);
            key.put("rawId", id);
            key.put("label", label == null ? "KEY" : label);
            key.put("pressed", pressed);
            key.put("cps", Math.max(0, cps));
            key.put("dps", Math.max(0, cps));
            key.put("pressCount", Math.max(0, pressCount));
            key.put("source", "prompt");
            key.put("displayType", TYPE_KEY_PROMPT);
        } catch (JSONException ignored) {}
        dispatch("keydisplay:key", key);
    }

    public void setTrajectoryButtonColors(boolean leftEnabled, int leftColor,
                                            boolean rightEnabled, int rightColor) {
        trajectoryLeftColorEnabled = leftEnabled;
        trajectoryRightColorEnabled = rightEnabled;
        trajectoryLeftColor = 0xff000000 | (leftColor & 0x00ffffff);
        trajectoryRightColor = 0xff000000 | (rightColor & 0x00ffffff);
        scheduleFullState();
    }

    public void setMouseStats(long stats) {
        if (mouseStats == stats) return;
        long old = mouseStats;
        mouseStats = stats;
        boolean oldLeft = (old & 1L) != 0;
        boolean oldRight = (old & 2L) != 0;
        boolean newLeft = (stats & 1L) != 0;
        boolean newRight = (stats & 2L) != 0;
        if (oldLeft != newLeft) dispatch("keydisplay:key", mouseKeyObject(true));
        if (oldRight != newRight) dispatch("keydisplay:key", mouseKeyObject(false));
        dispatch("keydisplay:mouse", mouseObject());
        scheduleFullState();
    }

    public void addPointerDelta(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        pointerDx = clamp(pointerDx + dx, -8192, 8192);
        pointerDy = clamp(pointerDy + dy, -8192, 8192);
        pointerSequence++;
        pointerDirty = true;
        scheduleRealtimeFrame();
    }

    public void resetPointer() {
        pointerDx = 0;
        pointerDy = 0;
        pointerSequence++;
        pointerDirty = true;
        scheduleRealtimeFrame();
    }

    public void setGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        int nextLx = clamp(lx, -1000, 1000);
        int nextLy = clamp(ly, -1000, 1000);
        int nextRx = clamp(rx, -1000, 1000);
        int nextRy = clamp(ry, -1000, 1000);
        int nextLt = clamp(lt, 0, 1000);
        int nextRt = clamp(rt, 0, 1000);
        if (gamepadLx == nextLx && gamepadLy == nextLy && gamepadRx == nextRx
                && gamepadRy == nextRy && gamepadLt == nextLt && gamepadRt == nextRt
                && gamepadButtons == buttons) return;
        gamepadLx = nextLx;
        gamepadLy = nextLy;
        gamepadRx = nextRx;
        gamepadRy = nextRy;
        gamepadLt = nextLt;
        gamepadRt = nextRt;
        gamepadButtons = buttons;
        gamepadDirty = true;
        scheduleRealtimeFrame();
    }

    public void setStickShape(int shape) {
        stickShape = shape == GamepadOverlayView.SHAPE_SQUARE
                ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE;
        scheduleFullState();
    }

    public void setFaceReversed(boolean reversed) {
        faceReversed = reversed;
        scheduleFullState();
    }

    public void setFaceDpsConfig(boolean y, boolean x, boolean b, boolean a) {
        faceYDpsEnabled = y;
        faceXDpsEnabled = x;
        faceBDpsEnabled = b;
        faceADpsEnabled = a;
        scheduleFullState();
    }

    public void setShoulderConfig(boolean triggerProgress, boolean dps) {
        triggerProgressEnabled = triggerProgress;
        shoulderDpsEnabled = dps;
        scheduleFullState();
    }

    public void setGamepadDpsStats(int y, int x, int b, int a, int l1, int r1) {
        int ny = Math.max(0, y), nx = Math.max(0, x), nb = Math.max(0, b);
        int na = Math.max(0, a), nl1 = Math.max(0, l1), nr1 = Math.max(0, r1);
        if (faceYDps == ny && faceXDps == nx && faceBDps == nb && faceADps == na
                && l1Dps == nl1 && r1Dps == nr1) return;
        faceYDps = ny; faceXDps = nx; faceBDps = nb; faceADps = na; l1Dps = nl1; r1Dps = nr1;
        scheduleFullState();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) dispatchFullState();
    }

    private void dispatchKeyboardChanges(int oldMask, int newMask) {
        int[] bits = {NativeKeyEngine.W, NativeKeyEngine.A, NativeKeyEngine.S, NativeKeyEngine.D, NativeKeyEngine.SPACE};
        String[] ids = {"w", "a", "s", "d", "space"};
        String[] labels = {"W", "A", "S", "D", "Space"};
        int[] codes = {51, 29, 47, 32, 62};
        for (int i = 0; i < bits.length; i++) {
            if (i == 4 && !keyboardShowSpace) continue;
            boolean before = (oldMask & bits[i]) != 0;
            boolean after = (newMask & bits[i]) != 0;
            if (before != after) {
                int dps = (i == 4 && keyboardShowSpaceDps) ? keyboardSpaceDps : 0;
                dispatch("keydisplay:key", keyObject(ids[i], labels[i], codes[i], i, after, dps));
            }
        }
    }

    private JSONObject buildStateSafely() {
        try {
            return buildState();
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private JSONObject buildState() throws JSONException {
        JSONObject state = new JSONObject();
        state.put("apiVersion", API_VERSION);
        state.put("rendererVersion", RENDERER_VERSION);
        state.put("type", type);
        state.put("theme", OverlayState.getUiTheme(getContext()) == OverlayState.UI_THEME_BLACK ? "dark" : "light");
        state.put("sizePercent", sizePercent);
        state.put("timestamp", SystemClock.uptimeMillis());

        JSONObject viewport = new JSONObject();
        viewport.put("width", getWidth());
        viewport.put("height", getHeight());
        float density = getResources().getDisplayMetrics().density;
        viewport.put("density", density);
        viewport.put("densityDpi", getResources().getDisplayMetrics().densityDpi);
        viewport.put("dpWidth", density > 0f ? getWidth() / density : getWidth());
        viewport.put("dpHeight", density > 0f ? getHeight() / density : getHeight());
        viewport.put("aspectRatio", getHeight() == 0 ? 0.0 : (double) getWidth() / (double) getHeight());
        viewport.put("orientation", getWidth() >= getHeight() ? "landscape" : "portrait");
        state.put("viewport", viewport);

        if (TYPE_KEYBOARD.equals(type) || TYPE_MOUSE.equals(type) || TYPE_CUSTOM.equals(type) || TYPE_KEY_PROMPT.equals(type)) {
            state.put("columns", customColumns);
            JSONArray keys = new JSONArray();
            if (TYPE_KEYBOARD.equals(type)) {
                keys.put(keyObject("w", "W", 51, 0, (pressedMask & NativeKeyEngine.W) != 0, 0));
                keys.put(keyObject("a", "A", 29, 1, (pressedMask & NativeKeyEngine.A) != 0, 0));
                keys.put(keyObject("s", "S", 47, 2, (pressedMask & NativeKeyEngine.S) != 0, 0));
                keys.put(keyObject("d", "D", 32, 3, (pressedMask & NativeKeyEngine.D) != 0, 0));
                if (keyboardShowSpace) {
                    keys.put(keyObject("space", "Space", 62, 4, (pressedMask & NativeKeyEngine.SPACE) != 0,
                            keyboardShowSpaceDps ? keyboardSpaceDps : 0));
                }
            } else if (TYPE_MOUSE.equals(type)) {
                keys.put(mouseKeyObject(true));
                keys.put(mouseKeyObject(false));
            } else if (TYPE_CUSTOM.equals(type)) {
                for (int i = 0; i < customKeyCodes.length; i++) keys.put(keyObjectForCustom(i));
            } else if (TYPE_KEY_PROMPT.equals(type)) {
                int count = Math.min(promptIds.length, promptLabels.length);
                for (int i = 0; i < count; i++) keys.put(promptKeyObject(i));
            }
            state.put("keys", keys);
        }

        if (TYPE_MOUSE_TRAJECTORY.equals(type)) {
            state.put("pointer", pointerObject());
            JSONObject buttons = new JSONObject();
            buttons.put("left", (mouseStats & 1L) != 0);
            buttons.put("right", (mouseStats & 2L) != 0);
            state.put("mouseButtons", buttons);
        }
        if (type.startsWith("gamepad-")) state.put("gamepad", gamepadObject());

        JSONObject config = new JSONObject();
        config.put("stickShape", stickShape == GamepadOverlayView.SHAPE_SQUARE ? "square" : "circle");
        config.put("faceReversed", faceReversed);
        config.put("dotSizePercent", dotSizePercent);
        config.put("showSpace", keyboardShowSpace);
        config.put("showSpaceDps", keyboardShowSpaceDps);
        config.put("showFaceYDps", faceYDpsEnabled);
        config.put("showFaceXDps", faceXDpsEnabled);
        config.put("showFaceBDps", faceBDpsEnabled);
        config.put("showFaceADps", faceADpsEnabled);
        config.put("showTriggerProgress", triggerProgressEnabled);
        config.put("showShoulderDps", shoulderDpsEnabled);
        config.put("showTrajectoryLeftColor", trajectoryLeftColorEnabled);
        config.put("showTrajectoryRightColor", trajectoryRightColorEnabled);
        config.put("trajectoryLeftColor", colorHex(trajectoryLeftColor));
        config.put("trajectoryRightColor", colorHex(trajectoryRightColor));
        config.put("motionMode", currentMotionModeName());
        state.put("config", config);
        state.put("palette", paletteObject());
        state.put("runtime", runtimeObject());
        state.put("settings", settingsObject());
        state.put("capabilities", capabilityArray());
        return state;
    }

    private JSONObject mouseObject() {
        JSONObject mouse = new JSONObject();
        try {
            mouse.put("left", (mouseStats & 1L) != 0);
            mouse.put("right", (mouseStats & 2L) != 0);
            mouse.put("leftDps", (int) ((mouseStats >>> 8) & 0xffL));
            mouse.put("rightDps", (int) ((mouseStats >>> 16) & 0xffL));
            mouse.put("timestamp", SystemClock.uptimeMillis());
        } catch (JSONException ignored) {}
        return mouse;
    }

    private JSONObject pointerObject() {
        JSONObject pointer = new JSONObject();
        try {
            pointer.put("dx", pointerDx);
            pointer.put("dy", pointerDy);
            pointer.put("sequence", pointerSequence);
        } catch (JSONException ignored) {}
        return pointer;
    }

    private JSONObject gamepadObject() {
        JSONObject pad = new JSONObject();
        try {
            pad.put("lx", gamepadLx / 1000.0);
            pad.put("ly", gamepadLy / 1000.0);
            pad.put("rx", gamepadRx / 1000.0);
            pad.put("ry", gamepadRy / 1000.0);
            pad.put("lt", gamepadLt / 1000.0);
            pad.put("rt", gamepadRt / 1000.0);
            pad.put("buttonMask", gamepadButtons);
            JSONObject buttons = new JSONObject();
            buttons.put("south", (gamepadButtons & BTN_SOUTH) != 0);
            buttons.put("east", (gamepadButtons & BTN_EAST) != 0);
            buttons.put("a", (gamepadButtons & BTN_SOUTH) != 0);
            buttons.put("b", (gamepadButtons & BTN_EAST) != 0);
            buttons.put("c", (gamepadButtons & BTN_C) != 0);
            buttons.put("north", (gamepadButtons & BTN_NORTH) != 0);
            buttons.put("west", (gamepadButtons & BTN_WEST) != 0);
            buttons.put("y", (gamepadButtons & BTN_NORTH) != 0);
            buttons.put("x", (gamepadButtons & BTN_WEST) != 0);
            buttons.put("z", (gamepadButtons & BTN_Z) != 0);
            buttons.put("l1", (gamepadButtons & BTN_L1) != 0);
            buttons.put("r1", (gamepadButtons & BTN_R1) != 0);
            buttons.put("l2", (gamepadButtons & BTN_L2) != 0 || gamepadLt > 80);
            buttons.put("r2", (gamepadButtons & BTN_R2) != 0 || gamepadRt > 80);
            buttons.put("l3", (gamepadButtons & BTN_L3) != 0);
            buttons.put("r3", (gamepadButtons & BTN_R3) != 0);
            pad.put("buttons", buttons);
            JSONObject dps = new JSONObject();
            dps.put("y", faceYDps);
            dps.put("x", faceXDps);
            dps.put("b", faceBDps);
            dps.put("a", faceADps);
            dps.put("l1", l1Dps);
            dps.put("r1", r1Dps);
            pad.put("dps", dps);
        } catch (JSONException ignored) {}
        return pad;
    }

    private JSONObject promptKeyObject(int index) {
        JSONObject key = new JSONObject();
        try {
            int id = promptIds[index];
            key.put("id", "prompt-" + id);
            key.put("rawId", id);
            key.put("label", promptLabels[index]);
            key.put("index", index);
            key.put("pressed", index < promptPressed.length && promptPressed[index]);
            int cps = index < promptCps.length ? promptCps[index] : 0;
            key.put("cps", cps);
            key.put("dps", cps);
            key.put("pressCount", index < promptPressCount.length ? promptPressCount[index] : 0);
            key.put("source", "prompt");
            key.put("displayType", TYPE_KEY_PROMPT);
        } catch (JSONException ignored) {}
        return key;
    }

    private JSONObject keyObjectForCustom(int index) {
        return keyObject(
                "key-" + customKeyCodes[index],
                KeyLabel.fromKeyCode(customKeyCodes[index]),
                customKeyCodes[index],
                index,
                customPressed[index],
                0);
    }

    private JSONObject mouseKeyObject(boolean left) {
        int dps = (int) ((mouseStats >>> (left ? 8 : 16)) & 0xffL);
        boolean pressed = (mouseStats & (left ? 1L : 2L)) != 0;
        return keyObject(left ? "mouse-left" : "mouse-right", left ? "L" : "R",
                left ? 272 : 273, left ? 0 : 1, pressed, dps);
    }

    private JSONObject keyObject(String id, String label, int keyCode, int index, boolean pressed, int dps) {
        JSONObject key = new JSONObject();
        try {
            key.put("id", id);
            key.put("label", label);
            key.put("keyCode", keyCode);
            key.put("index", index);
            key.put("pressed", pressed);
            key.put("dps", dps);
            key.put("source", TYPE_MOUSE.equals(type) ? "mouse" : (TYPE_CUSTOM.equals(type) ? "custom" : "keyboard"));
            key.put("displayType", type);
        } catch (JSONException ignored) {}
        return key;
    }


    /** 向 HTML 提供完整调色板。 */
    private JSONObject paletteObject() throws JSONException {
        JSONObject p = new JSONObject();
        Context c = getContext();
        p.put("background", colorRgba(UiPalette.background(c)));
        p.put("surface", colorRgba(UiPalette.surface(c)));
        p.put("debugSurface", colorRgba(UiPalette.debugSurface(c)));
        p.put("textPrimary", colorRgba(UiPalette.textPrimary(c)));
        p.put("textSecondary", colorRgba(UiPalette.textSecondary(c)));
        p.put("divider", colorRgba(UiPalette.divider(c)));
        p.put("accent", colorRgba(UiPalette.accent(c)));
        p.put("keyIdle", colorRgba(UiPalette.overlayKeyIdle(c)));
        p.put("keyPressed", colorRgba(UiPalette.overlayKeyPressed(c)));
        p.put("keyTextIdle", colorRgba(UiPalette.overlayTextIdle(c)));
        p.put("keyTextPressed", colorRgba(UiPalette.overlayTextPressed(c)));
        p.put("overlayStroke", colorRgba(UiPalette.overlayStroke(c)));
        p.put("overlayShell", colorRgba(UiPalette.overlayShell(c)));
        p.put("overlaySecondary", colorRgba(UiPalette.overlaySecondary(c)));
        p.put("trajectoryPanel", colorRgba(UiPalette.trajectoryPanel(c)));
        p.put("trajectoryStroke", colorRgba(UiPalette.trajectoryStroke(c)));
        p.put("trajectoryDot", colorRgba(UiPalette.trajectoryDot(c)));
        return p;
    }

    /** 向 HTML 提供当前运行环境。 */
    private JSONObject runtimeObject() throws JSONException {
        JSONObject r = new JSONObject();
        Context c = getContext();
        r.put("dragEnabled", OverlayState.isDragEnabled(c));
        r.put("htmlEnabled", OverlayState.isGlobalHtmlEnabled(c));
        r.put("sensitivityEnabled", OverlayState.isSensitivityEnabled(c));
        r.put("mouseSensitivity", OverlayState.getMouseSensitivity(c));
        r.put("gamepadSensitivity", OverlayState.getGamepadSensitivity(c));
        r.put("sensitivityMode", OverlayState.getSensitivityMode(c) == OverlayState.SENSITIVITY_MODE_ROOT ? "root" : "shizuku");
        r.put("positionXPercent", OverlayState.getPositionX(c, displayTypeForState()));
        r.put("positionYPercent", OverlayState.getPositionY(c, displayTypeForState()));
        r.put("displayType", displayTypeForState());
        r.put("displayEnabled", isCurrentDisplayEnabled(c));
        r.put("sessionPersistent", false);
        r.put("reducedMotion", false);
        return r;
    }

    /** 向 HTML 提供只读状态快照。HTML 不能直接修改 Android 配置。 */
    private JSONObject settingsObject() throws JSONException {
        Context c = getContext();
        JSONObject root = new JSONObject();

        JSONObject keyboard = new JSONObject();
        keyboard.put("enabled", OverlayState.isEnabled(c));
        keyboard.put("sizePercent", OverlayState.getKeyboardSize(c));
        keyboard.put("showSpace", OverlayState.isKeyboardSpaceEnabled(c));
        keyboard.put("showSpaceDps", OverlayState.isKeyboardSpaceDpsEnabled(c));
        keyboard.put("motionMode", motionModeName(OverlayState.getMotionMode(c, KeyOverlayView.DISPLAY_KEYBOARD)));
        root.put("keyboard", keyboard);

        JSONObject mouse = new JSONObject();
        mouse.put("enabled", OverlayState.isMouseEnabled(c));
        mouse.put("sizePercent", OverlayState.getMouseSize(c));
        mouse.put("motionMode", motionModeName(OverlayState.getMotionMode(c, KeyOverlayView.DISPLAY_MOUSE)));
        root.put("mouse", mouse);

        JSONObject prompt = new JSONObject();
        prompt.put("enabled", OverlayState.isKeyPromptEnabled(c));
        prompt.put("sizePercent", OverlayState.getKeyPromptSize(c));
        root.put("keyPrompt", prompt);

        JSONObject custom = new JSONObject();
        custom.put("enabled", OverlayState.isCustomEnabled(c));
        custom.put("capture", OverlayState.isCustomCaptureEnabled(c));
        custom.put("sizePercent", OverlayState.getCustomSize(c));
        custom.put("columns", OverlayState.getCustomColumns(c));
        custom.put("motionMode", motionModeName(OverlayState.getMotionMode(c, KeyOverlayView.DISPLAY_CUSTOM)));
        root.put("customKeys", custom);

        JSONObject trajectory = new JSONObject();
        trajectory.put("enabled", OverlayState.isMouseTrajectoryEnabled(c));
        trajectory.put("sizePercent", OverlayState.getMouseTrajectorySize(c));
        trajectory.put("dotSizePercent", OverlayState.getMouseTrajectoryDotSize(c));
        trajectory.put("leftColorEnabled", OverlayState.isMouseTrajectoryLeftColorEnabled(c));
        trajectory.put("rightColorEnabled", OverlayState.isMouseTrajectoryRightColorEnabled(c));
        trajectory.put("leftColor", colorHex(OverlayState.getMouseTrajectoryLeftColor(c)));
        trajectory.put("rightColor", colorHex(OverlayState.getMouseTrajectoryRightColor(c)));
        root.put("mouseTrajectory", trajectory);

        JSONObject gamepad = new JSONObject();
        JSONObject leftStick = new JSONObject();
        leftStick.put("enabled", OverlayState.isGamepadLeftStickEnabled(c));
        leftStick.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_LEFT_STICK));
        leftStick.put("dotSizePercent", OverlayState.getGamepadStickDotSize(c, GamepadOverlayView.DISPLAY_LEFT_STICK));
        leftStick.put("shape", OverlayState.getGamepadLeftStickShape(c) == GamepadOverlayView.SHAPE_SQUARE ? "square" : "circle");
        gamepad.put("leftStick", leftStick);

        JSONObject rightStick = new JSONObject();
        rightStick.put("enabled", OverlayState.isGamepadRightStickEnabled(c));
        rightStick.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_RIGHT_STICK));
        rightStick.put("dotSizePercent", OverlayState.getGamepadStickDotSize(c, GamepadOverlayView.DISPLAY_RIGHT_STICK));
        rightStick.put("shape", OverlayState.getGamepadRightStickShape(c) == GamepadOverlayView.SHAPE_SQUARE ? "square" : "circle");
        gamepad.put("rightStick", rightStick);

        JSONObject face = new JSONObject();
        face.put("enabled", OverlayState.isGamepadFaceEnabled(c));
        face.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_FACE));
        face.put("reversed", OverlayState.isGamepadFaceReversed(c));
        face.put("yDps", OverlayState.isGamepadFaceYDpsEnabled(c));
        face.put("xDps", OverlayState.isGamepadFaceXDpsEnabled(c));
        face.put("bDps", OverlayState.isGamepadFaceBDpsEnabled(c));
        face.put("aDps", OverlayState.isGamepadFaceADpsEnabled(c));
        gamepad.put("face", face);

        JSONObject leftShoulder = new JSONObject();
        leftShoulder.put("enabled", OverlayState.isGamepadLeftShoulderEnabled(c));
        leftShoulder.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_LEFT_SHOULDER));
        leftShoulder.put("triggerProgress", OverlayState.isGamepadL2ProgressEnabled(c));
        leftShoulder.put("dps", OverlayState.isGamepadL1DpsEnabled(c));
        gamepad.put("leftShoulder", leftShoulder);

        JSONObject rightShoulder = new JSONObject();
        rightShoulder.put("enabled", OverlayState.isGamepadRightShoulderEnabled(c));
        rightShoulder.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER));
        rightShoulder.put("triggerProgress", OverlayState.isGamepadR2ProgressEnabled(c));
        rightShoulder.put("dps", OverlayState.isGamepadR1DpsEnabled(c));
        gamepad.put("rightShoulder", rightShoulder);
        root.put("gamepad", gamepad);

        JSONObject interaction = new JSONObject();
        interaction.put("dragEnabled", OverlayState.isDragEnabled(c));
        interaction.put("autoHideBackground", OverlayState.isAutoHideBackground(c));
        root.put("interaction", interaction);

        JSONObject sensitivity = new JSONObject();
        sensitivity.put("enabled", OverlayState.isSensitivityEnabled(c));
        sensitivity.put("mode", OverlayState.getSensitivityMode(c) == OverlayState.SENSITIVITY_MODE_ROOT ? "root" : "shizuku");
        sensitivity.put("mousePercent", OverlayState.getMouseSensitivity(c));
        sensitivity.put("gamepadPercent", OverlayState.getGamepadSensitivity(c));
        sensitivity.put("status", OverlayState.getSensitivityStatus(c));
        root.put("sensitivity", sensitivity);

        JSONObject html = new JSONObject();
        html.put("enabled", OverlayState.isGlobalHtmlEnabled(c));
        html.put("name", OverlayState.getGlobalHtmlName(c));
        root.put("html", html);
        return root;
    }

    private static String motionModeName(int mode) {
        if (mode == OverlayState.MOTION_ALPHA) return "alpha";
        if (mode == OverlayState.MOTION_NONE) return "none";
        return "size";
    }

    private String currentMotionModeName() {
        int display = displayTypeForState();
        if (display == KeyOverlayView.DISPLAY_KEYBOARD
                || display == KeyOverlayView.DISPLAY_MOUSE
                || display == KeyOverlayView.DISPLAY_CUSTOM) {
            return motionModeName(OverlayState.getMotionMode(getContext(), display));
        }
        return "native";
    }

    private boolean isCurrentDisplayEnabled(Context c) {
        if (TYPE_MOUSE.equals(type)) return OverlayState.isMouseEnabled(c);
        if (TYPE_CUSTOM.equals(type)) return OverlayState.isCustomEnabled(c);
        if (TYPE_MOUSE_TRAJECTORY.equals(type)) return OverlayState.isMouseTrajectoryEnabled(c);
        if (TYPE_KEY_PROMPT.equals(type)) return OverlayState.isKeyPromptEnabled(c);
        if (TYPE_GAMEPAD_LEFT_STICK.equals(type)) return OverlayState.isGamepadLeftStickEnabled(c);
        if (TYPE_GAMEPAD_RIGHT_STICK.equals(type)) return OverlayState.isGamepadRightStickEnabled(c);
        if (TYPE_GAMEPAD_FACE.equals(type)) return OverlayState.isGamepadFaceEnabled(c);
        if (TYPE_GAMEPAD_LEFT_SHOULDER.equals(type)) return OverlayState.isGamepadLeftShoulderEnabled(c);
        if (TYPE_GAMEPAD_RIGHT_SHOULDER.equals(type)) return OverlayState.isGamepadRightShoulderEnabled(c);
        return OverlayState.isEnabled(c);
    }

    private JSONArray capabilityArray() {
        JSONArray out = new JSONArray();
        out.put("html"); out.put("css"); out.put("svg"); out.put("canvas");
        out.put("web-animations"); out.put("css-variables"); out.put("realtime-events");
        out.put("palette"); out.put("runtime"); out.put("full-settings"); out.put("low-power-realtime");
        if (TYPE_MOUSE_TRAJECTORY.equals(type)) out.put("pointer-delta");
        if (type.startsWith("gamepad-")) { out.put("gamepad"); out.put("semantic-gamepad-buttons"); }
        if (TYPE_KEYBOARD.equals(type) || TYPE_MOUSE.equals(type) || TYPE_CUSTOM.equals(type) || TYPE_KEY_PROMPT.equals(type)) out.put("keys");
        if (TYPE_KEY_PROMPT.equals(type)) { out.put("dynamic-key-list"); out.put("cps"); }
        return out;
    }

    private int displayTypeForState() {
        if (TYPE_MOUSE.equals(type)) return KeyOverlayView.DISPLAY_MOUSE;
        if (TYPE_CUSTOM.equals(type)) return KeyOverlayView.DISPLAY_CUSTOM;
        if (TYPE_MOUSE_TRAJECTORY.equals(type)) return MouseTrajectoryView.DISPLAY_TRAJECTORY;
        if (TYPE_KEY_PROMPT.equals(type)) return KeyPromptOverlayView.DISPLAY_KEY_PROMPT;
        if (TYPE_GAMEPAD_LEFT_STICK.equals(type)) return GamepadOverlayView.DISPLAY_LEFT_STICK;
        if (TYPE_GAMEPAD_RIGHT_STICK.equals(type)) return GamepadOverlayView.DISPLAY_RIGHT_STICK;
        if (TYPE_GAMEPAD_FACE.equals(type)) return GamepadOverlayView.DISPLAY_FACE;
        if (TYPE_GAMEPAD_LEFT_SHOULDER.equals(type)) return GamepadOverlayView.DISPLAY_LEFT_SHOULDER;
        if (TYPE_GAMEPAD_RIGHT_SHOULDER.equals(type)) return GamepadOverlayView.DISPLAY_RIGHT_SHOULDER;
        return KeyOverlayView.DISPLAY_KEYBOARD;
    }

    private String runtimeApiSource() {
        return "(function(){"
                + "const KD=window.KeyDisplay=window.KeyDisplay||{};"
                + "KD.apiVersion=" + API_VERSION + ";KD.version='" + RENDERER_VERSION + "';KD.type=" + JSONObject.quote(type) + ";"
                + "KD.types=['keyboard','mouse','custom','mouse-trajectory','key-prompt','gamepad-left-stick','gamepad-right-stick','gamepad-face','gamepad-left-shoulder','gamepad-right-shoulder'];"
                + "KD.getState=()=>window.__KEYDISPLAY_STATE__||null;"
                + "KD.on=(n,f)=>{const e=n.startsWith('keydisplay:')?n:'keydisplay:'+n;window.addEventListener(e,f);return()=>window.removeEventListener(e,f)};"
                + "KD.once=(n,f)=>{const off=KD.on(n,e=>{off();f(e)});return off};"
                + "KD.has=n=>{const s=KD.getState();return !!(s&&s.capabilities&&s.capabilities.includes(n))};"
                + "KD.clamp=(v,a,b)=>Math.max(a,Math.min(b,v));KD.lerp=(a,b,t)=>a+(b-a)*t;"
                + "KD.raf=f=>requestAnimationFrame(f);KD.cancelFrame=id=>cancelAnimationFrame(id);"
                + "KD.css=(n,v)=>document.documentElement.style.setProperty(n,v);"
                + "KD.__applyState=(s)=>{if(!s)return;const d=document.documentElement.style,p=s.palette||{},r=s.runtime||{},c=s.config||{};"
                + "const set=(k,v)=>{if(v!==undefined&&v!==null)d.setProperty(k,String(v))};"
                + "set('--kd-size',s.sizePercent/100);set('--kd-width',s.viewport.width+'px');set('--kd-height',s.viewport.height+'px');set('--kd-density',s.viewport.density);"
                + "set('--kd-dot-size',(c.dotSizePercent||100)/100);set('--kd-mouse-sensitivity',r.mouseSensitivity||100);set('--kd-gamepad-sensitivity',r.gamepadSensitivity||100);"
                + "set('--kd-position-x',(r.positionXPercent||0)+'%');set('--kd-position-y',(r.positionYPercent||0)+'%');"
                + "for(const k in p)set('--kd-'+k.replace(/[A-Z]/g,m=>'-'+m.toLowerCase()),p[k]);"
                + "document.documentElement.dataset.kdType=s.type;document.documentElement.dataset.kdTheme=s.theme;document.documentElement.dataset.kdMotion=c.motionMode||'none';"
                + "document.documentElement.dataset.kdDrag=r.dragEnabled?'on':'off';document.documentElement.dataset.kdOverclock=r.sensitivityEnabled?'on':'off';};"
                + "})();";
    }

    private String injectRuntimeBootstrap(String html) {
        String script = "<script>" + runtimeApiSource() + "</script>";
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        int doctype = lower.indexOf("<!doctype");
        if (doctype >= 0) {
            int end = lower.indexOf('>', doctype);
            if (end >= 0) return html.substring(0, end + 1) + script + html.substring(end + 1);
        }
        return script + html;
    }

    private void installRuntimeApi() {
        // 页面加载完成后再次写入 KeyDisplay。
        evaluateJavascript(runtimeApiSource(), null);
    }

    private void scheduleFullState() {
        if (!pageReady || fullStateFramePosted) return;
        fullStateFramePosted = true;
        postOnAnimation(fullStateDispatch);
    }

    private void scheduleRealtimeFrame() {
        if (realtimeFramePosted) return;
        realtimeFramePosted = true;
        postOnAnimation(realtimeDispatch);
    }

    private void dispatchFullState() {
        fullStateFramePosted = false;
        if (!pageReady) return;
        JSONObject state = buildStateSafely();
        String payload = state.toString();
        String js = "window.__KEYDISPLAY_STATE__=" + payload + ";"
                + "if(window.KeyDisplay&&typeof window.KeyDisplay.__applyState==='function'){window.KeyDisplay.__applyState(window.__KEYDISPLAY_STATE__);}"
                + "window.dispatchEvent(new CustomEvent('keydisplay:update',{detail:window.__KEYDISPLAY_STATE__}));"
                + "if(window.KeyDisplay&&typeof window.KeyDisplay.update==='function'){window.KeyDisplay.update(window.__KEYDISPLAY_STATE__);}";
        evaluateJavascript(js, null);
    }

    /** 高频输入只更新对应字段，避免每帧序列化完整状态。 */
    private void dispatchRealtime(String eventName, String stateKey, JSONObject detail) {
        if (!pageReady || detail == null) return;
        String payload = detail.toString();
        String safeName = JSONObject.quote(eventName);
        String safeKey = JSONObject.quote(stateKey);
        String js = "(function(){const d=" + payload + ",s=window.__KEYDISPLAY_STATE__;"
                + "if(s){s[" + safeKey + "]=d;s.timestamp=" + SystemClock.uptimeMillis() + ";}"
                + "window.dispatchEvent(new CustomEvent(" + safeName + ",{detail:d}));";
        if ("keydisplay:pointer".equals(eventName)) {
            js += "if(window.KeyDisplay&&typeof window.KeyDisplay.pointer==='function'){window.KeyDisplay.pointer(d);}";
        } else if ("keydisplay:gamepad".equals(eventName)) {
            js += "if(window.KeyDisplay&&typeof window.KeyDisplay.gamepad==='function'){window.KeyDisplay.gamepad(d);}";
        }
        js += "})();";
        evaluateJavascript(js, null);
    }

    private void dispatch(String eventName, JSONObject detail) {
        if (!pageReady || detail == null) return;
        String payload = detail.toString();
        String safeName = JSONObject.quote(eventName);
        String js = "window.dispatchEvent(new CustomEvent(" + safeName + ",{detail:" + payload + "}));";
        if ("keydisplay:key".equals(eventName)) {
            js += "if(window.KeyDisplay&&typeof window.KeyDisplay.key==='function'){window.KeyDisplay.key(" + payload + ");}";
        } else if ("keydisplay:mouse".equals(eventName)) {
            js += "if(window.KeyDisplay&&typeof window.KeyDisplay.mouse==='function'){window.KeyDisplay.mouse(" + payload + ");}";
        } else if ("keydisplay:pointer".equals(eventName)) {
            js += "if(window.KeyDisplay&&typeof window.KeyDisplay.pointer==='function'){window.KeyDisplay.pointer(" + payload + ");}";
        } else if ("keydisplay:gamepad".equals(eventName)) {
            js += "if(window.KeyDisplay&&typeof window.KeyDisplay.gamepad==='function'){window.KeyDisplay.gamepad(" + payload + ");}";
        }
        evaluateJavascript(js, null);
    }

    private static String colorHex(int color) {
        return String.format(java.util.Locale.US, "#%06X", color & 0x00ffffff);
    }

    private static String colorRgba(int color) {
        float alpha = ((color >>> 24) & 0xff) / 255f;
        return String.format(java.util.Locale.US, "rgba(%d,%d,%d,%.3f)",
                (color >>> 16) & 0xff, (color >>> 8) & 0xff, color & 0xff, alpha);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
