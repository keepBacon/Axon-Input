package com.axon.input;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** HTML 自定义显示层。每个悬浮窗口独立运行。Android 只推送输入和配置状态。 */
public final class GlobalHtmlWebView extends WebView {
    public static final int API_VERSION = 10;
    public static final String RENDERER_VERSION = "v31";
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
    public static final String TYPE_GAMEPAD_BACK = "gamepad-back";
    public static final String TYPE_GAMEPAD_DPAD = "gamepad-dpad";

    private static final int BTN_SOUTH = 1 << 0;
    private static final int BTN_EAST = 1 << 1;
    private static final int BTN_C = 1 << 2;
    private static final int BTN_NORTH = 1 << 3;
    private static final int BTN_WEST = 1 << 4;
    private static final int BTN_Z = 1 << 5;
    private static final int BTN_L1 = 1 << 6;
    private static final int BTN_R1 = 1 << 7;
    private static final int BTN_BACK_1 = 1 << 15;
    private static final int BTN_BACK_2 = 1 << 16;
    private static final int BTN_BACK_3 = 1 << 17;
    private static final int BTN_BACK_4 = 1 << 18;
    private static final int BTN_L2 = 1 << 8;
    private static final int BTN_R2 = 1 << 9;
    private static final int BTN_L3 = 1 << 13;
    private static final int BTN_R3 = 1 << 14;
    private static final int BTN_DPAD_UP = 1 << 20;
    private static final int BTN_DPAD_DOWN = 1 << 21;
    private static final int BTN_DPAD_LEFT = 1 << 22;
    private static final int BTN_DPAD_RIGHT = 1 << 23;

    private static final int[] KEYBOARD_BITS = {
            NativeKeyEngine.W, NativeKeyEngine.A, NativeKeyEngine.S, NativeKeyEngine.D, NativeKeyEngine.SPACE
    };
    private static final String[] KEYBOARD_IDS = {"w", "a", "s", "d", "space"};
    private static final String[] KEYBOARD_LABELS = {"W", "A", "S", "D", "Space"};
    private static final int[] KEYBOARD_CODES = {51, 29, 47, 32, 62};

    private final String type;
    private final String runtimeSource;

    private int sizePercent = 100;
    private int dotSizePercent = 100;
    private int pressedMask;
    private boolean keyboardShowSpace = true;
    private boolean keyboardShowSpaceDps;
    private boolean keyboardShowSpaceDash;
    private boolean keyboardSwapMouseButtonsAndSpace;
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
    private boolean faceReversed;
    private boolean faceSymbolIcons;
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
                dispatch("keydisplay:pointer", pointerObject());
            }
            if (gamepadDirty) {
                gamepadDirty = false;
                dispatch("keydisplay:gamepad", gamepadObject());
            }
            pointerDx = 0;
            pointerDy = 0;
        }
    };

    public GlobalHtmlWebView(Context context, String type) {
        super(context);
        this.type = type == null ? TYPE_KEYBOARD : type;
        this.runtimeSource = createRuntimeApiSource();

        setBackgroundColor(Color.TRANSPARENT);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);

        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setDefaultTextEncodingName("utf-8");
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return uri != null && !"about".equalsIgnoreCase(uri.getScheme());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                if (uri != null
                        && "https".equalsIgnoreCase(uri.getScheme())
                        && "keydisplay.local".equalsIgnoreCase(uri.getHost())
                        && "/__axon_font__".equals(uri.getPath())
                        && FontManager.shouldServeImportedFont(getContext())) {
                    try {
                        InputStream input = FontManager.openImportedFont(getContext());
                        return new WebResourceResponse("font/ttf", null, input);
                    } catch (IOException ignored) {
                        return new WebResourceResponse("text/plain", "UTF-8",
                                new java.io.ByteArrayInputStream(new byte[0]));
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (currentHtml.isEmpty()) return;
                pageReady = true;
                installRuntimeApi();
                applyConfiguredFont();
                dispatchInitialState();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) pageReady = false;
            }
        });
    }

    public void loadRendererHtml(String html) {
        String normalized = html == null ? "" : html;
        if (normalized.equals(currentHtml)) {
            if (pageReady) {
                applyConfiguredFont();
                scheduleFullState();
            }
            return;
        }
        currentHtml = normalized;
        pageReady = false;
        removeCallbacks(fullStateDispatch);
        removeCallbacks(realtimeDispatch);
        fullStateFramePosted = false;
        realtimeFramePosted = false;
        pointerDx = 0;
        pointerDy = 0;
        pointerDirty = false;
        gamepadDirty = false;
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
        int next = Math.max(25, Math.min(300, percent));
        if (sizePercent == next) return;
        sizePercent = next;
        scheduleFullState();
    }

    public void setDotSizePercent(int percent) {
        int next = Math.max(25, Math.min(300, percent));
        if (dotSizePercent == next) return;
        dotSizePercent = next;
        scheduleFullState();
    }

    public void setKeyboardOptions(boolean showSpace, boolean showSpaceDps) {
        boolean nextDps = showSpace && showSpaceDps;
        if (keyboardShowSpace == showSpace && keyboardShowSpaceDps == nextDps) return;
        keyboardShowSpace = showSpace;
        keyboardShowSpaceDps = nextDps;
        scheduleFullState();
    }

    public void setKeyboardSpaceDashEnabled(boolean enabled) {
        if (keyboardShowSpaceDash == enabled) return;
        keyboardShowSpaceDash = enabled;
        scheduleFullState();
    }

    public void setKeyboardMouseSpaceSwapEnabled(boolean enabled) {
        if (keyboardSwapMouseButtonsAndSpace == enabled) return;
        keyboardSwapMouseButtonsAndSpace = enabled;
        scheduleFullState();
    }

    private String keyboardLabel(int index) {
        return index == 4 && keyboardShowSpaceDash ? "—" : KEYBOARD_LABELS[index];
    }

    public void setKeyboardDps(int dps) {
        int next = Math.max(0, Math.min(999, dps));
        if (keyboardSpaceDps == next) return;
        keyboardSpaceDps = next;
        if (keyboardShowSpace && keyboardShowSpaceDps) {
            dispatch("keydisplay:key", keyObject(
                    KEYBOARD_IDS[4], keyboardLabel(4), KEYBOARD_CODES[4], 4,
                    (pressedMask & KEYBOARD_BITS[4]) != 0, keyboardSpaceDps));
        }
    }

    public void setKeyboardMask(int newMask) {
        if (pressedMask == newMask) return;
        int old = pressedMask;
        pressedMask = newMask;
        dispatchKeyboardChanges(old, newMask);
    }

    public void setCustomKeys(int[] keyCodes, int columns) {
        int[] next = keyCodes == null ? new int[0] : keyCodes.clone();
        int nextColumns = Math.max(1, Math.min(8, columns));
        if (customColumns == nextColumns && Arrays.equals(customKeyCodes, next)) return;
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
        customColumns = nextColumns;
        scheduleFullState();
    }

    public void setCustomKeyPressed(int keyCode, boolean pressed) {
        for (int i = 0; i < customKeyCodes.length; i++) {
            if (customKeyCodes[i] == keyCode && customPressed[i] != pressed) {
                customPressed[i] = pressed;
                dispatch("keydisplay:key", keyObjectForCustom(i));
                return;
            }
        }
    }

    public void releaseCustomKeys() {
        JSONArray changed = new JSONArray();
        for (int i = 0; i < customPressed.length; i++) {
            if (customPressed[i]) {
                customPressed[i] = false;
                changed.put(keyObjectForCustom(i));
            }
        }
        dispatchKeys(changed);
    }

    /** 最近按键列表：结构变化才发送完整状态，按压/CPS 变化使用细粒度 key 事件。 */
    public void setPromptState(int[] ids, String[] labels, boolean[] pressed, int[] cps, int[] pressCount) {
        int[] nextIds = ids == null ? new int[0] : ids.clone();
        String[] nextLabels = labels == null ? new String[0] : labels.clone();
        boolean[] nextPressed = pressed == null ? new boolean[0] : pressed.clone();
        int[] nextCps = cps == null ? new int[0] : cps.clone();
        int[] nextPressCount = pressCount == null ? new int[0] : pressCount.clone();

        boolean structureChanged = promptIds.length != nextIds.length
                || promptLabels.length != nextLabels.length
                || nextIds.length != nextLabels.length
                || promptIds.length != promptLabels.length;
        if (!structureChanged) {
            for (int i = 0; i < nextIds.length; i++) {
                String oldLabel = i < promptLabels.length ? promptLabels[i] : null;
                String nextLabel = i < nextLabels.length ? nextLabels[i] : null;
                if (promptIds[i] != nextIds[i]
                        || (oldLabel == null ? nextLabel != null : !oldLabel.equals(nextLabel))) {
                    structureChanged = true;
                    break;
                }
            }
        }

        JSONArray changed = new JSONArray();
        if (!structureChanged) {
            int count = Math.min(nextIds.length, nextLabels.length);
            for (int i = 0; i < count; i++) {
                boolean oldPressed = i < promptPressed.length && promptPressed[i];
                boolean newPressed = i < nextPressed.length && nextPressed[i];
                int oldCps = i < promptCps.length ? promptCps[i] : 0;
                int newCps = i < nextCps.length ? nextCps[i] : 0;
                int oldCount = i < promptPressCount.length ? promptPressCount[i] : 0;
                int newCount = i < nextPressCount.length ? nextPressCount[i] : 0;
                if (oldPressed != newPressed || oldCps != newCps || oldCount != newCount) {
                    JSONObject key = new JSONObject();
                    try {
                        key.put("id", "prompt-" + nextIds[i]);
                        key.put("rawId", nextIds[i]);
                        key.put("label", nextLabels[i]);
                        key.put("index", i);
                        key.put("pressed", newPressed);
                        key.put("cps", Math.max(0, newCps));
                        key.put("dps", Math.max(0, newCps));
                        key.put("pressCount", Math.max(0, newCount));
                        key.put("source", "prompt");
                        key.put("displayType", TYPE_KEY_PROMPT);
                    } catch (JSONException ignored) {}
                    changed.put(key);
                }
            }
        }

        promptIds = nextIds;
        promptLabels = nextLabels;
        promptPressed = nextPressed;
        promptCps = nextCps;
        promptPressCount = nextPressCount;
        if (structureChanged) scheduleFullState();
        else dispatchKeys(changed);
    }

    public void setTrajectoryButtonColors(boolean leftEnabled, int leftColor,
                                            boolean rightEnabled, int rightColor) {
        int nextLeft = 0xff000000 | (leftColor & 0x00ffffff);
        int nextRight = 0xff000000 | (rightColor & 0x00ffffff);
        if (trajectoryLeftColorEnabled == leftEnabled
                && trajectoryRightColorEnabled == rightEnabled
                && trajectoryLeftColor == nextLeft && trajectoryRightColor == nextRight) return;
        trajectoryLeftColorEnabled = leftEnabled;
        trajectoryRightColorEnabled = rightEnabled;
        trajectoryLeftColor = nextLeft;
        trajectoryRightColor = nextRight;
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
        JSONArray keyChanges = new JSONArray();
        if (oldLeft != newLeft) keyChanges.put(mouseKeyObject(true));
        if (oldRight != newRight) keyChanges.put(mouseKeyObject(false));
        dispatchMouseBatch(keyChanges, mouseObject());
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

    public void setFaceReversed(boolean reversed) {
        if (faceReversed == reversed) return;
        faceReversed = reversed;
        scheduleFullState();
    }

    public void setFaceSymbolIcons(boolean enabled) {
        if (faceSymbolIcons == enabled) return;
        faceSymbolIcons = enabled;
        scheduleFullState();
    }

    public void setFaceDpsConfig(boolean y, boolean x, boolean b, boolean a) {
        if (faceYDpsEnabled == y && faceXDpsEnabled == x
                && faceBDpsEnabled == b && faceADpsEnabled == a) return;
        faceYDpsEnabled = y;
        faceXDpsEnabled = x;
        faceBDpsEnabled = b;
        faceADpsEnabled = a;
        scheduleFullState();
    }

    public void setShoulderConfig(boolean triggerProgress, boolean dps) {
        if (triggerProgressEnabled == triggerProgress && shoulderDpsEnabled == dps) return;
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
        gamepadDirty = true;
        scheduleRealtimeFrame();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) scheduleFullState();
    }

    private void dispatchKeyboardChanges(int oldMask, int newMask) {
        JSONArray changes = new JSONArray();
        for (int i = 0; i < KEYBOARD_BITS.length; i++) {
            if (i == 4 && !keyboardShowSpace) continue;
            boolean before = (oldMask & KEYBOARD_BITS[i]) != 0;
            boolean after = (newMask & KEYBOARD_BITS[i]) != 0;
            if (before != after) {
                int cps = (i == 4 && keyboardShowSpaceDps) ? keyboardSpaceDps : 0;
                changes.put(keyObject(
                        KEYBOARD_IDS[i], keyboardLabel(i), KEYBOARD_CODES[i], i, after, cps));
            }
        }
        dispatchKeys(changes);
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
                for (int i = 0; i < KEYBOARD_BITS.length; i++) {
                    if (i == 4 && !keyboardShowSpace) continue;
                    int cps = i == 4 && keyboardShowSpaceDps ? keyboardSpaceDps : 0;
                    keys.put(keyObject(KEYBOARD_IDS[i], keyboardLabel(i), KEYBOARD_CODES[i], i,
                            (pressedMask & KEYBOARD_BITS[i]) != 0, cps));
                }
            } else if (TYPE_MOUSE.equals(type)) {
                keys.put(mouseKeyObject(true));
                keys.put(mouseKeyObject(false));
                state.put("mouse", mouseObject());
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
        config.put("stickShape", "rounded");
        if (TYPE_GAMEPAD_LEFT_STICK.equals(type) || TYPE_GAMEPAD_RIGHT_STICK.equals(type)) {
            config.put("stickCornerStrength", OverlayState.getKeyCornerStrength(getContext(), displayTypeForState()));
        }
        config.put("faceReversed", faceReversed);
        config.put("faceSymbolIcons", faceSymbolIcons);
        config.put("dotSizePercent", dotSizePercent);
        config.put("showSpace", keyboardShowSpace);
        config.put("showSpaceCps", keyboardShowSpaceDps);
        config.put("showSpaceDps", keyboardShowSpaceDps); // v9 兼容
        config.put("spaceDash", keyboardShowSpaceDash);
        config.put("swapMouseButtonsAndSpace", keyboardSwapMouseButtonsAndSpace);
        config.put("showFaceYCps", faceYDpsEnabled);
        config.put("showFaceXCps", faceXDpsEnabled);
        config.put("showFaceBCps", faceBDpsEnabled);
        config.put("showFaceACps", faceADpsEnabled);
        config.put("showFaceYDps", faceYDpsEnabled); // v9 兼容
        config.put("showFaceXDps", faceXDpsEnabled);
        config.put("showFaceBDps", faceBDpsEnabled);
        config.put("showFaceADps", faceADpsEnabled);
        config.put("showTriggerProgress", triggerProgressEnabled);
        config.put("showShoulderCps", shoulderDpsEnabled);
        config.put("showShoulderDps", shoulderDpsEnabled); // v9 兼容
        config.put("showTrajectoryLeftColor", trajectoryLeftColorEnabled);
        config.put("showTrajectoryRightColor", trajectoryRightColorEnabled);
        config.put("trajectoryLeftColor", colorHex(trajectoryLeftColor));
        config.put("trajectoryRightColor", colorHex(trajectoryRightColor));
        config.put("motionMode", currentMotionModeName());
        appendCurrentAppearance(config);
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
            int leftCps = (int) ((mouseStats >>> 8) & 0xffL);
            int rightCps = (int) ((mouseStats >>> 16) & 0xffL);
            mouse.put("leftCps", leftCps);
            mouse.put("rightCps", rightCps);
            mouse.put("leftDps", leftCps); // v9 兼容
            mouse.put("rightDps", rightCps);
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
            buttons.put("p1", (gamepadButtons & BTN_BACK_1) != 0);
            buttons.put("p2", (gamepadButtons & BTN_BACK_2) != 0);
            buttons.put("p3", (gamepadButtons & BTN_BACK_3) != 0);
            buttons.put("p4", (gamepadButtons & BTN_BACK_4) != 0);
            buttons.put("dpadUp", (gamepadButtons & BTN_DPAD_UP) != 0);
            buttons.put("dpadDown", (gamepadButtons & BTN_DPAD_DOWN) != 0);
            buttons.put("dpadLeft", (gamepadButtons & BTN_DPAD_LEFT) != 0);
            buttons.put("dpadRight", (gamepadButtons & BTN_DPAD_RIGHT) != 0);
            pad.put("buttons", buttons);
            JSONObject cps = new JSONObject();
            cps.put("y", faceYDps);
            cps.put("x", faceXDps);
            cps.put("b", faceBDps);
            cps.put("a", faceADps);
            cps.put("l1", l1Dps);
            cps.put("r1", r1Dps);
            pad.put("cps", cps);
            pad.put("dps", cps); // v9 兼容
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
                InputBinding.label(customKeyCodes[index]),
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

    private JSONObject keyObject(String id, String label, int keyCode, int index, boolean pressed, int cps) {
        JSONObject key = new JSONObject();
        try {
            key.put("id", id);
            key.put("label", label);
            key.put("keyCode", keyCode);
            key.put("index", index);
            key.put("pressed", pressed);
            key.put("cps", cps);
            key.put("dps", cps); // v9 兼容
            key.put("source", TYPE_MOUSE.equals(type) ? "mouse" : (TYPE_CUSTOM.equals(type) ? "custom" : "keyboard"));
            key.put("displayType", type);
        } catch (JSONException ignored) {}
        return key;
    }


    private void appendCurrentAppearance(JSONObject out) throws JSONException {
        Context c = getContext();
        int display = displayTypeForState();
        out.put("opacityPercent", OverlayState.getDisplayOpacity(c, display));
        if (supportsKeyAppearance()) {
            out.put("keyStyle", keyStyleName(OverlayState.getKeyStyle(c, display)));
            out.put("cornerStrength", OverlayState.getKeyCornerStrength(c, display));
            out.put("baseColor", colorHex(OverlayState.getKeyBaseColor(c, display)));
            out.put("borderColor", colorHex(OverlayState.getKeyBorderColor(c, display)));
            out.put("pressColor", colorHex(OverlayState.getKeyPressColor(c, display)));
            out.put("textColor", colorHex(OverlayState.getKeyTextColor(c, display)));
            out.put("backgroundOpacity", OverlayState.getKeyBackgroundOpacity(c, display));
            out.put("strokeOpacity", OverlayState.getKeyStrokeOpacity(c, display));
            out.put("textOpacity", OverlayState.getKeyTextOpacity(c, display));
            out.put("diffusionOpacity", OverlayState.getKeyDiffusionOpacity(c, display));
        }
        int spacing = currentSpacingDp(c);
        if (spacing >= 0) out.put("spacingDp", spacing);
    }

    private void appendAppearance(JSONObject out, Context c, int display) throws JSONException {
        out.put("opacityPercent", OverlayState.getDisplayOpacity(c, display));
        if (supportsKeyAppearance(display)) {
            out.put("keyStyle", keyStyleName(OverlayState.getKeyStyle(c, display)));
            out.put("cornerStrength", OverlayState.getKeyCornerStrength(c, display));
            out.put("baseColor", colorHex(OverlayState.getKeyBaseColor(c, display)));
            out.put("borderColor", colorHex(OverlayState.getKeyBorderColor(c, display)));
            out.put("pressColor", colorHex(OverlayState.getKeyPressColor(c, display)));
            out.put("textColor", colorHex(OverlayState.getKeyTextColor(c, display)));
            out.put("backgroundOpacity", OverlayState.getKeyBackgroundOpacity(c, display));
            out.put("strokeOpacity", OverlayState.getKeyStrokeOpacity(c, display));
            out.put("textOpacity", OverlayState.getKeyTextOpacity(c, display));
            out.put("diffusionOpacity", OverlayState.getKeyDiffusionOpacity(c, display));
        }
    }

    private boolean supportsKeyAppearance() {
        return supportsKeyAppearance(displayTypeForState());
    }

    private static boolean supportsKeyAppearance(int display) {
        return display == KeyOverlayView.DISPLAY_KEYBOARD
                || display == KeyOverlayView.DISPLAY_MOUSE
                || display == KeyOverlayView.DISPLAY_CUSTOM
                || display == KeyPromptOverlayView.DISPLAY_KEY_PROMPT
                || display == GamepadOverlayView.DISPLAY_LEFT_STICK
                || display == GamepadOverlayView.DISPLAY_RIGHT_STICK
                || display == GamepadOverlayView.DISPLAY_FACE
                || display == GamepadOverlayView.DISPLAY_DPAD
                || display == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || display == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER
                || display == GamepadOverlayView.DISPLAY_BACK;
    }

    private int currentSpacingDp(Context c) {
        if (TYPE_KEYBOARD.equals(type)) return OverlayState.getKeyboardSpacing(c);
        if (TYPE_CUSTOM.equals(type)) return OverlayState.getCustomSpacing(c);
        if (TYPE_GAMEPAD_FACE.equals(type)) return OverlayState.getGamepadFaceSpacing(c);
        return -1;
    }

    private static String keyStyleName(int style) {
        if (style == KeyAppearance.STYLE_SQUARE) return "square";
        if (style == KeyAppearance.STYLE_CIRCLE) return "circle";
        return "rounded";
    }

    private static String gamepadCompatibilityName(int mode) {
        if (mode == GamepadSettingsStore.COMPAT_LOOSE) return "loose";
        if (mode == GamepadSettingsStore.COMPAT_ANDROID) return "android";
        if (mode == GamepadSettingsStore.COMPAT_EVDEV) return "evdev";
        return "auto";
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
        r.put("htmlEnabled", GlobalHtmlStore.isEnabled(c));
        r.put("sensitivityEnabled", SensitivitySettingsStore.isEnabled(c));
        r.put("mouseSensitivity", SensitivitySettingsStore.getMousePercent(c));
        r.put("gamepadSensitivity", SensitivitySettingsStore.getGamepadPercent(c));
        r.put("sensitivityMode", SensitivitySettingsStore.getMode(c) == SensitivitySettingsStore.MODE_ROOT ? "root" : "shizuku");
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
        keyboard.put("swapMouseButtonsAndSpace", OverlayState.isKeyboardMouseSpaceSwapEnabled(c));
        keyboard.put("motionMode", motionModeName(OverlayState.getMotionMode(c, KeyOverlayView.DISPLAY_KEYBOARD)));
        keyboard.put("spacingDp", OverlayState.getKeyboardSpacing(c));
        appendAppearance(keyboard, c, KeyOverlayView.DISPLAY_KEYBOARD);
        root.put("keyboard", keyboard);

        JSONObject mouse = new JSONObject();
        mouse.put("enabled", OverlayState.isMouseEnabled(c));
        mouse.put("sizePercent", OverlayState.getMouseSize(c));
        mouse.put("motionMode", motionModeName(OverlayState.getMotionMode(c, KeyOverlayView.DISPLAY_MOUSE)));
        appendAppearance(mouse, c, KeyOverlayView.DISPLAY_MOUSE);
        root.put("mouse", mouse);

        JSONObject prompt = new JSONObject();
        prompt.put("enabled", OverlayState.isKeyPromptEnabled(c));
        prompt.put("sizePercent", OverlayState.getKeyPromptSize(c));
        prompt.put("motionMode", motionModeName(OverlayState.getMotionMode(c, KeyPromptOverlayView.DISPLAY_KEY_PROMPT)));
        appendAppearance(prompt, c, KeyPromptOverlayView.DISPLAY_KEY_PROMPT);
        root.put("keyPrompt", prompt);

        JSONObject custom = new JSONObject();
        custom.put("enabled", OverlayState.isCustomEnabled(c));
        custom.put("capture", OverlayState.isCustomCaptureEnabled(c));
        custom.put("sizePercent", OverlayState.getCustomSize(c));
        custom.put("columns", OverlayState.getCustomColumns(c));
        custom.put("spacingDp", OverlayState.getCustomSpacing(c));
        custom.put("motionMode", motionModeName(OverlayState.getMotionMode(c, KeyOverlayView.DISPLAY_CUSTOM)));
        appendAppearance(custom, c, KeyOverlayView.DISPLAY_CUSTOM);
        root.put("customKeys", custom);

        JSONObject trajectory = new JSONObject();
        trajectory.put("enabled", OverlayState.isMouseTrajectoryEnabled(c));
        trajectory.put("sizePercent", OverlayState.getMouseTrajectorySize(c));
        trajectory.put("dotSizePercent", OverlayState.getMouseTrajectoryDotSize(c));
        trajectory.put("leftColorEnabled", OverlayState.isMouseTrajectoryLeftColorEnabled(c));
        trajectory.put("rightColorEnabled", OverlayState.isMouseTrajectoryRightColorEnabled(c));
        trajectory.put("leftColor", colorHex(OverlayState.getMouseTrajectoryLeftColor(c)));
        trajectory.put("rightColor", colorHex(OverlayState.getMouseTrajectoryRightColor(c)));
        appendAppearance(trajectory, c, MouseTrajectoryView.DISPLAY_TRAJECTORY);
        root.put("mouseTrajectory", trajectory);

        JSONObject gamepad = new JSONObject();
        JSONObject leftStick = new JSONObject();
        leftStick.put("enabled", OverlayState.isGamepadLeftStickEnabled(c));
        leftStick.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_LEFT_STICK));
        leftStick.put("dotSizePercent", OverlayState.getGamepadStickDotSize(c, GamepadOverlayView.DISPLAY_LEFT_STICK));
        leftStick.put("centerCornerStrength", OverlayState.getGamepadStickCenterCornerStrength(c, GamepadOverlayView.DISPLAY_LEFT_STICK));
        leftStick.put("centerColor", colorHex(OverlayState.getGamepadStickCenterColor(c, GamepadOverlayView.DISPLAY_LEFT_STICK)));
        leftStick.put("motionMode", motionModeName(OverlayState.getMotionMode(c, GamepadOverlayView.DISPLAY_LEFT_STICK)));
        leftStick.put("shape", "rounded");
        appendAppearance(leftStick, c, GamepadOverlayView.DISPLAY_LEFT_STICK);
        gamepad.put("leftStick", leftStick);

        JSONObject rightStick = new JSONObject();
        rightStick.put("enabled", OverlayState.isGamepadRightStickEnabled(c));
        rightStick.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_RIGHT_STICK));
        rightStick.put("dotSizePercent", OverlayState.getGamepadStickDotSize(c, GamepadOverlayView.DISPLAY_RIGHT_STICK));
        rightStick.put("centerCornerStrength", OverlayState.getGamepadStickCenterCornerStrength(c, GamepadOverlayView.DISPLAY_RIGHT_STICK));
        rightStick.put("centerColor", colorHex(OverlayState.getGamepadStickCenterColor(c, GamepadOverlayView.DISPLAY_RIGHT_STICK)));
        rightStick.put("motionMode", motionModeName(OverlayState.getMotionMode(c, GamepadOverlayView.DISPLAY_RIGHT_STICK)));
        rightStick.put("shape", "rounded");
        appendAppearance(rightStick, c, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        gamepad.put("rightStick", rightStick);

        JSONObject face = new JSONObject();
        face.put("enabled", OverlayState.isGamepadFaceEnabled(c));
        face.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_FACE));
        face.put("reversed", OverlayState.isGamepadFaceReversed(c));
        face.put("yDps", GamepadSettingsStore.isFaceYDpsEnabled(c));
        face.put("xDps", GamepadSettingsStore.isFaceXDpsEnabled(c));
        face.put("bDps", GamepadSettingsStore.isFaceBDpsEnabled(c));
        face.put("aCps", GamepadSettingsStore.isFaceADpsEnabled(c));
        face.put("yCps", GamepadSettingsStore.isFaceYDpsEnabled(c));
        face.put("xCps", GamepadSettingsStore.isFaceXDpsEnabled(c));
        face.put("bCps", GamepadSettingsStore.isFaceBDpsEnabled(c));
        face.put("aDps", GamepadSettingsStore.isFaceADpsEnabled(c)); // v9 兼容
        face.put("spacingDp", OverlayState.getGamepadFaceSpacing(c));
        face.put("motionMode", motionModeName(OverlayState.getMotionMode(c, GamepadOverlayView.DISPLAY_FACE)));
        appendAppearance(face, c, GamepadOverlayView.DISPLAY_FACE);
        gamepad.put("face", face);

        JSONObject leftShoulder = new JSONObject();
        leftShoulder.put("enabled", OverlayState.isGamepadLeftShoulderEnabled(c));
        leftShoulder.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_LEFT_SHOULDER));
        leftShoulder.put("triggerProgress", GamepadSettingsStore.isL2ProgressEnabled(c));
        leftShoulder.put("cps", GamepadSettingsStore.isL1DpsEnabled(c));
        leftShoulder.put("dps", GamepadSettingsStore.isL1DpsEnabled(c)); // v9 兼容
        leftShoulder.put("motionMode", motionModeName(OverlayState.getMotionMode(c, GamepadOverlayView.DISPLAY_LEFT_SHOULDER)));
        appendAppearance(leftShoulder, c, GamepadOverlayView.DISPLAY_LEFT_SHOULDER);
        gamepad.put("leftShoulder", leftShoulder);

        JSONObject rightShoulder = new JSONObject();
        rightShoulder.put("enabled", OverlayState.isGamepadRightShoulderEnabled(c));
        rightShoulder.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER));
        rightShoulder.put("triggerProgress", GamepadSettingsStore.isR2ProgressEnabled(c));
        rightShoulder.put("cps", GamepadSettingsStore.isR1DpsEnabled(c));
        rightShoulder.put("dps", GamepadSettingsStore.isR1DpsEnabled(c)); // v9 兼容
        rightShoulder.put("motionMode", motionModeName(OverlayState.getMotionMode(c, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER)));
        appendAppearance(rightShoulder, c, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER);
        gamepad.put("rightShoulder", rightShoulder);

        JSONObject back = new JSONObject();
        back.put("enabled", OverlayState.isGamepadBackEnabled(c));
        back.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_BACK));
        back.put("motionMode", motionModeName(OverlayState.getMotionMode(c, GamepadOverlayView.DISPLAY_BACK)));
        appendAppearance(back, c, GamepadOverlayView.DISPLAY_BACK);
        gamepad.put("back", back);

        JSONObject dpad = new JSONObject();
        dpad.put("enabled", OverlayState.isGamepadDpadEnabled(c));
        dpad.put("sizePercent", OverlayState.getGamepadDisplaySize(c, GamepadOverlayView.DISPLAY_DPAD));
        dpad.put("motionMode", motionModeName(OverlayState.getMotionMode(c, GamepadOverlayView.DISPLAY_DPAD)));
        appendAppearance(dpad, c, GamepadOverlayView.DISPLAY_DPAD);
        gamepad.put("dpad", dpad);

        JSONObject compatibility = new JSONObject();
        compatibility.put("mode", gamepadCompatibilityName(GamepadSettingsStore.getCompatibilityMode(c)));
        compatibility.put("swapXY", GamepadSettingsStore.isSwapXY(c));
        compatibility.put("swapAB", GamepadSettingsStore.isSwapAB(c));
        compatibility.put("swapSticks", GamepadSettingsStore.isSwapSticks(c));
        compatibility.put("swapTriggers", GamepadSettingsStore.isSwapTriggers(c));
        compatibility.put("customSwapEnabled", GamepadSettingsStore.isCustomSwapEnabled(c));
        compatibility.put("customSwapFirst", GamepadSettingsStore.getCustomSwapFirst(c));
        compatibility.put("customSwapSecond", GamepadSettingsStore.getCustomSwapSecond(c));
        gamepad.put("compatibility", compatibility);
        root.put("gamepad", gamepad);

        JSONObject interaction = new JSONObject();
        interaction.put("dragEnabled", OverlayState.isDragEnabled(c));
        interaction.put("autoHideBackground", OverlayState.isAutoHideBackground(c));
        root.put("interaction", interaction);

        JSONObject sensitivity = new JSONObject();
        sensitivity.put("enabled", SensitivitySettingsStore.isEnabled(c));
        sensitivity.put("mode", SensitivitySettingsStore.getMode(c) == SensitivitySettingsStore.MODE_ROOT ? "root" : "shizuku");
        sensitivity.put("mousePercent", SensitivitySettingsStore.getMousePercent(c));
        sensitivity.put("gamepadPercent", SensitivitySettingsStore.getGamepadPercent(c));
        sensitivity.put("status", SensitivitySettingsStore.getStatus(c));
        root.put("sensitivity", sensitivity);

        JSONObject html = new JSONObject();
        html.put("enabled", GlobalHtmlStore.isEnabled(c));
        html.put("name", GlobalHtmlStore.getName(c));
        html.put("apiVersion", API_VERSION);
        html.put("maxBytes", GlobalHtmlStore.MAX_BYTES);
        html.put("domStorage", true);
        html.put("networkAccess", false);
        root.put("html", html);

        JSONObject appearance = new JSONObject();
        appearance.put("fontOverrideEnabled", FontManager.isEnabled(c));
        appearance.put("fontChoice", FontManager.getChoice(c));
        appearance.put("customFont", FontManager.hasImportedFont(c));
        appearance.put("fontName", FontManager.getImportedFontName(c));
        appearance.put("htmlFontMode", GlobalHtmlStore.getFontMode(c));
        root.put("appearance", appearance);
        return root;
    }

    private static String motionModeName(int mode) {
        if (mode == OverlayState.MOTION_ALPHA) return "alpha";
        if (mode == OverlayState.MOTION_RIPPLE) return "ripple";
        if (mode == OverlayState.MOTION_NONE) return "none";
        return "size";
    }

    private String currentMotionModeName() {
        int display = displayTypeForState();
        if (display == KeyOverlayView.DISPLAY_KEYBOARD
                || display == KeyOverlayView.DISPLAY_MOUSE
                || display == KeyOverlayView.DISPLAY_CUSTOM
                || display == KeyPromptOverlayView.DISPLAY_KEY_PROMPT
                || display == GamepadOverlayView.DISPLAY_LEFT_STICK
                || display == GamepadOverlayView.DISPLAY_RIGHT_STICK
                || display == GamepadOverlayView.DISPLAY_FACE
                || display == GamepadOverlayView.DISPLAY_DPAD
                || display == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || display == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER
                || display == GamepadOverlayView.DISPLAY_BACK) {
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
        if (TYPE_GAMEPAD_BACK.equals(type)) return OverlayState.isGamepadBackEnabled(c);
        if (TYPE_GAMEPAD_DPAD.equals(type)) return OverlayState.isGamepadDpadEnabled(c);
        return OverlayState.isEnabled(c);
    }

    private JSONArray capabilityArray() {
        JSONArray out = new JSONArray();
        String[] common = {
                "html", "css", "svg", "canvas", "web-animations", "css-variables",
                "realtime-events", "palette", "runtime", "full-settings", "low-power-realtime",
                "dom-storage", "state-helpers", "math-helpers", "key-appearance", "opacity", "cps"
        };
        for (String item : common) out.put(item);
        if (currentSpacingDp(getContext()) >= 0) out.put("spacing");
        if (TYPE_MOUSE_TRAJECTORY.equals(type)) out.put("pointer-delta");
        if (type.startsWith("gamepad-")) {
            out.put("gamepad");
            out.put("semantic-gamepad-buttons");
            out.put("gamepad-compatibility");
        }
        if (TYPE_KEYBOARD.equals(type) || TYPE_MOUSE.equals(type)
                || TYPE_CUSTOM.equals(type) || TYPE_KEY_PROMPT.equals(type)) out.put("keys");
        if (TYPE_KEY_PROMPT.equals(type)) out.put("dynamic-key-list");
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
        if (TYPE_GAMEPAD_BACK.equals(type)) return GamepadOverlayView.DISPLAY_BACK;
        if (TYPE_GAMEPAD_DPAD.equals(type)) return GamepadOverlayView.DISPLAY_DPAD;
        return KeyOverlayView.DISPLAY_KEYBOARD;
    }

    private String createRuntimeApiSource() {
        return "(function(){"
                + "const KD=window.KeyDisplay=window.KeyDisplay||{};"
                + "KD.apiVersion=" + API_VERSION + ";KD.version='" + RENDERER_VERSION + "';KD.type=" + JSONObject.quote(type) + ";"
                + "KD.types=['keyboard','mouse','custom','mouse-trajectory','key-prompt','gamepad-left-stick','gamepad-right-stick','gamepad-face','gamepad-left-shoulder','gamepad-right-shoulder','gamepad-back','gamepad-dpad'];"
                + "KD.getState=()=>window.__KEYDISPLAY_STATE__||null;"
                + "KD.on=(n,f)=>{if(typeof f!=='function')return()=>{};const e=String(n||'').startsWith('keydisplay:')?String(n):'keydisplay:'+String(n||'');window.addEventListener(e,f);return()=>window.removeEventListener(e,f)};"
                + "KD.once=(n,f)=>{if(typeof f!=='function')return()=>{};let off=()=>{};off=KD.on(n,e=>{off();f(e)});return off};"
                + "KD.ready=f=>{if(typeof f!=='function')return()=>{};const s=KD.getState();if(s){f(s);return()=>{}}return KD.once('init',e=>f(e.detail))};"
                + "KD.onState=(f,immediate=true)=>{if(typeof f!=='function')return()=>{};const off=KD.on('update',e=>f(e.detail,e));const s=KD.getState();if(immediate&&s)f(s,null);return off};"
                + "KD.onInput=h=>{h=h||{};const o=[];for(const n of ['key','mouse','pointer','gamepad','update'])if(typeof h[n]==='function')o.push(KD.on(n,e=>h[n](e.detail,e)));return()=>o.forEach(f=>f())};"
                + "KD.has=n=>{const s=KD.getState();return !!(s&&s.capabilities&&s.capabilities.includes(n))};"
                + "KD.keys=()=>{const s=KD.getState();return s&&Array.isArray(s.keys)?s.keys:[]};"
                + "KD.findKey=q=>{const a=KD.keys(),qs=String(q);return a.find(k=>String(k.id)===qs||String(k.keyCode)===qs||String(k.rawId)===qs)||null};"
                + "KD.button=n=>{n=String(n==null?'':n).toLowerCase();const s=KD.getState(),g=s&&s.gamepad;return !!(g&&g.buttons&&g.buttons[n])};"
                + "KD.isPressed=q=>{const k=KD.findKey(q);return k?!!k.pressed:KD.button(q)};"
                + "KD.axis=n=>{n=String(n==null?'':n).toLowerCase();const s=KD.getState(),g=s&&s.gamepad,v=g&&g[n];return Number.isFinite(v)?v:0};"
                + "KD.cps=q=>{const k=KD.findKey(q);if(k)return Number(k.cps||0);const s=KD.getState(),g=s&&s.gamepad,n=String(q==null?'':q).toLowerCase();return Number(g&&g.cps&&g.cps[n]||0)};"
                + "KD.clamp=(v,a,b)=>Math.max(a,Math.min(b,v));KD.lerp=(a,b,t)=>a+(b-a)*t;"
                + "KD.map=(v,a,b,c,d,limit=true)=>{const t=b===a?0:(v-a)/(b-a),u=limit?KD.clamp(t,0,1):t;return KD.lerp(c,d,u)};"
                + "KD.deadzone=(v,z=.08)=>{v=Number(v)||0;z=KD.clamp(Number(z)||0,0,.99);const a=Math.abs(v);return a<=z?0:Math.sign(v)*(a-z)/(1-z)};"
                + "KD.raf=f=>requestAnimationFrame(f);KD.cancelFrame=id=>cancelAnimationFrame(id);"
                + "KD.css=(n,v)=>document.documentElement.style.setProperty(n,v);"
                + "KD.cssAll=o=>{for(const [k,v] of Object.entries(o||{}))KD.css(k.startsWith('--')?k:'--'+k,v)};"
                + "KD.reducedMotion=()=>!!(window.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches);"
                + "KD.__emit=(n,d)=>{const k=String(n||'').replace(/^keydisplay:/,''),e='keydisplay:'+k;window.dispatchEvent(new CustomEvent(e,{detail:d}));const cb=KD[k];if(typeof cb==='function'){try{cb(d)}catch(err){console.error('[KeyDisplay '+k+']',err)}}};"
                + "KD.__patchKey=(s,d)=>{if(!s||!d||!Array.isArray(s.keys))return;const i=s.keys.findIndex(k=>k.id===d.id);if(i>=0)s.keys[i]=Object.assign({},s.keys[i],d)};"
                + "KD.__native=(k,d,t)=>{const s=KD.getState();if(s){if(k==='key')KD.__patchKey(s,d);else if(k==='mouse'){s.mouse=d;if(s.type==='mouse-trajectory')s.mouseButtons={left:!!d.left,right:!!d.right}}else if(k==='pointer')s.pointer=d;else if(k==='gamepad')s.gamepad=d;s.timestamp=t}KD.__emit(k,d)};"
                + "KD.__nativeKeys=(a,t)=>{a=Array.isArray(a)?a:[];const s=KD.getState();if(s){for(const d of a)KD.__patchKey(s,d);s.timestamp=t}for(const d of a)KD.__emit('key',d)};"
                + "KD.__nativeMouse=(a,m,t)=>{a=Array.isArray(a)?a:[];const s=KD.getState();if(s){for(const d of a)KD.__patchKey(s,d);s.mouse=m;if(s.type==='mouse-trajectory')s.mouseButtons={left:!!m.left,right:!!m.right};s.timestamp=t}for(const d of a)KD.__emit('key',d);KD.__emit('mouse',m)};"
                + "KD.__applyState=s=>{if(!s)return;const e=document.documentElement,d=e.style,p=s.palette||{},r=s.runtime||{},c=s.config||{};"
                + "const set=(k,v)=>{if(v!==undefined&&v!==null)d.setProperty(k,String(v))};"
                + "set('--kd-size',s.sizePercent/100);set('--kd-width',s.viewport.width+'px');set('--kd-height',s.viewport.height+'px');set('--kd-density',s.viewport.density);"
                + "set('--kd-dot-size',(c.dotSizePercent||100)/100);const op=c.opacityPercent==null?100:c.opacityPercent;set('--kd-opacity',op/100);set('--kd-opacity-percent',op);"
                + "set('--kd-key-spacing',(c.spacingDp==null?0:c.spacingDp)+'px');set('--kd-base-color',c.baseColor||p.keyIdle);set('--kd-border-color',c.borderColor||p.overlayStroke);set('--kd-press-color',c.pressColor||p.keyPressed);set('--kd-key-style',c.keyStyle||'rounded');set('--kd-corner-strength',c.cornerStrength==null?40:c.cornerStrength);set('--kd-corner-radius',(c.cornerStrength==null?40:c.cornerStrength)/100*0.5+'em');"
                + "set('--kd-mouse-sensitivity',r.mouseSensitivity||100);set('--kd-gamepad-sensitivity',r.gamepadSensitivity||100);"
                + "set('--kd-position-x',(r.positionXPercent||0)+'%');set('--kd-position-y',(r.positionYPercent||0)+'%');"
                + "for(const k in p)set('--kd-'+k.replace(/[A-Z]/g,m=>'-'+m.toLowerCase()),p[k]);"
                + "e.dataset.kdType=s.type;e.dataset.kdTheme=s.theme;e.dataset.kdMotion=c.motionMode||'none';e.dataset.kdKeyStyle=c.keyStyle||'rounded';"
                + "e.dataset.kdDrag=r.dragEnabled?'on':'off';e.dataset.kdOverclock=r.sensitivityEnabled?'on':'off';e.dataset.kdReducedMotion=KD.reducedMotion()?'on':'off';};"
                + "KD.__setState=(s,initial=false)=>{if(!s)return;window.__KEYDISPLAY_STATE__=s;KD.__applyState(s);if(initial)KD.__emit('init',s);KD.__emit('update',s)};"
                + "})();";
    }

    private String injectRuntimeBootstrap(String html) {
        String script = "<script>" + runtimeSource + "</script>";
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        int doctype = lower.indexOf("<!doctype");
        if (doctype >= 0) {
            int end = lower.indexOf('>', doctype);
            if (end >= 0) return html.substring(0, end + 1) + script + html.substring(end + 1);
        }
        return script + html;
    }

    private void installRuntimeApi() {
        // 页面加载完成后再次恢复只读 API，防止用户脚本意外覆盖 KeyDisplay。
        evaluateJavascript(runtimeSource, null);
    }

    private void applyConfiguredFont() {
        if (GlobalHtmlStore.getFontMode(getContext()) != GlobalHtmlStore.FONT_MODE_FOLLOW_APP
                || !FontManager.isEnabled(getContext())) {
            evaluateJavascript("(function(){var s=document.getElementById('axon-font-override');if(s)s.remove();})()", null);
            return;
        }

        String family = FontManager.cssFamily(getContext());
        String css;
        if (FontManager.shouldServeImportedFont(getContext())) {
            css = "@font-face{font-family:'AxonImportedFont';src:url('https://keydisplay.local/__axon_font__');font-style:normal;font-weight:100 900;}"
                    + "html,body,body *{font-family:" + family + " !important;}";
        } else {
            css = "html,body,body *{font-family:" + family + " !important;}";
        }
        String quoted = JSONObject.quote(css);
        evaluateJavascript("(function(){var s=document.getElementById('axon-font-override');"
                + "if(!s){s=document.createElement('style');s.id='axon-font-override';document.head.appendChild(s);}"
                + "s.textContent=" + quoted + ";})()", null);
    }

    private void dispatchInitialState() {
        if (!pageReady) return;
        String payload = buildStateSafely().toString();
        evaluateJavascript("window.KeyDisplay&&typeof KeyDisplay.__setState==='function'&&KeyDisplay.__setState("
                + payload + ",true)", null);
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
        String payload = buildStateSafely().toString();
        evaluateJavascript("window.KeyDisplay&&typeof KeyDisplay.__setState==='function'&&KeyDisplay.__setState("
                + payload + ",false)", null);
    }

    /** 输入事件只更新对应局部状态，不重复构建完整 state。 */
    private void dispatch(String eventName, JSONObject detail) {
        if (!pageReady || detail == null) return;
        String kind = eventName != null && eventName.startsWith("keydisplay:")
                ? eventName.substring("keydisplay:".length()) : eventName;
        evaluateJavascript("window.KeyDisplay&&typeof KeyDisplay.__native==='function'&&KeyDisplay.__native("
                + JSONObject.quote(kind) + "," + detail.toString() + "," + SystemClock.uptimeMillis() + ")", null);
    }

    private void dispatchKeys(JSONArray details) {
        if (!pageReady || details == null || details.length() == 0) return;
        evaluateJavascript("window.KeyDisplay&&typeof KeyDisplay.__nativeKeys==='function'&&KeyDisplay.__nativeKeys("
                + details.toString() + "," + SystemClock.uptimeMillis() + ")", null);
    }

    private void dispatchMouseBatch(JSONArray keyChanges, JSONObject mouse) {
        if (!pageReady || mouse == null) return;
        JSONArray keys = keyChanges == null ? new JSONArray() : keyChanges;
        evaluateJavascript("window.KeyDisplay&&typeof KeyDisplay.__nativeMouse==='function'&&KeyDisplay.__nativeMouse("
                + keys.toString() + "," + mouse.toString() + "," + SystemClock.uptimeMillis() + ")", null);
    }

    @Override
    public void destroy() {
        pageReady = false;
        currentHtml = "";
        removeCallbacks(fullStateDispatch);
        removeCallbacks(realtimeDispatch);
        fullStateFramePosted = false;
        realtimeFramePosted = false;
        pointerDirty = false;
        gamepadDirty = false;
        try { setWebViewClient(null); } catch (Throwable ignored) {}
        super.destroy();
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
