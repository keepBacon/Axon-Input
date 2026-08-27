package com.axon.input;

import android.annotation.SuppressLint;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 全局输入服务。读取输入并绘制悬浮层；绑定动作只旁路监听，不消费原始输入。 */
public final class AxonInputAccessibilityService extends AccessibilityService
        implements InputManager.InputDeviceListener,
        ShizukuBridge.Listener,
        MouseInputMonitor.Listener,
        TouchInputMonitor.Listener,
        KeyOverlayView.DragListener,
        KeyboardCatOverlayView.DragListener,
        KeyPromptOverlayView.DragListener,
        MouseTrajectoryView.DragListener,
        GamepadOverlayView.DragListener,
        GamepadInputMonitor.Listener,
        Vader5ProUsbMonitor.Listener,
        SensitivityProxyController.Listener,
        DpsOverlayView.DragListener {

    private static final String TAG = "AxonInputService";

    private static final int KEYBOARD_WIDTH_DP = 280;
    private static final int KEYBOARD_HEIGHT_DP = 180;
    private static final int CUSTOM_WIDTH_DP = 280;
    private static final int CUSTOM_MIN_HEIGHT_DP = 56;
    private static final int MOUSE_WIDTH_DP = 180;
    private static final int MOUSE_HEIGHT_DP = 100;
    private static final int TOUCH_DISPLAY_WIDTH_DP = 190;
    private static final int TOUCH_DISPLAY_HEIGHT_DP = 220;
    private static final int KEYBOARD_CAT_WIDTH_DP = 360;
    private static final int KEYBOARD_CAT_HEIGHT_DP = 208;
    private static final int KEY_PROMPT_WIDTH_DP = 332;
    private static final int KEY_PROMPT_HEIGHT_DP = 70;
    private static final int TRAJECTORY_SIZE_DP = 106;
    private static final int GAMEPAD_STICK_SIZE_DP = 116;
    private static final int GAMEPAD_FACE_SIZE_DP = 142;
    private static final int GAMEPAD_DPAD_SIZE_DP = 142;
    private static final int GAMEPAD_SHOULDER_WIDTH_DP = 132;
    private static final int GAMEPAD_SHOULDER_HEIGHT_DP = 86;
    private static final int GAMEPAD_BACK_WIDTH_DP = 132;
    private static final int GAMEPAD_BACK_HEIGHT_DP = 86;
    private static final int DPS_WIDTH_DP = 112;
    private static final int DPS_HEIGHT_DP = 40;
    private static final int FULL_KEYBOARD_MAX_WIDTH_DP = 720;
    private static final int FULL_KEYBOARD_MIN_HEIGHT_DP = 150;
    private static final int FULL_KEYBOARD_MAX_HEIGHT_DP = 260;
    // Keep a safe native render buffer below the old 50% minimum. The visible content may
    // still scale to 25%, but strokes, CPS text and press motion are no longer clipped by a
    // WindowManager surface that became too small to contain them.
    private static final int MIN_OVERLAY_RENDER_BUFFER_PERCENT = 50;
    private static final int[] GAMEPAD_CPS_PRIORITY = {
            GamepadOverlayView.BTN_SOUTH, GamepadOverlayView.BTN_EAST,
            GamepadOverlayView.BTN_WEST, GamepadOverlayView.BTN_NORTH,
            GamepadOverlayView.BTN_L1, GamepadOverlayView.BTN_R1,
            GamepadOverlayView.BTN_L2, GamepadOverlayView.BTN_R2,
            GamepadOverlayView.BTN_L3, GamepadOverlayView.BTN_R3,
            GamepadOverlayView.BTN_SELECT, GamepadOverlayView.BTN_START, GamepadOverlayView.BTN_MODE,
            GamepadOverlayView.BTN_BACK_1, GamepadOverlayView.BTN_BACK_2,
            GamepadOverlayView.BTN_BACK_3, GamepadOverlayView.BTN_BACK_4,
            GamepadOverlayView.BTN_DPAD_UP, GamepadOverlayView.BTN_DPAD_DOWN,
            GamepadOverlayView.BTN_DPAD_LEFT, GamepadOverlayView.BTN_DPAD_RIGHT
    };

    private static volatile AxonInputAccessibilityService activeService;
    private static volatile String lastTouchMonitorStatus = "未启动";
    private static volatile String lastTouchFrameStatus = "未收到触点";
    // Flattened triples: id, normalized display X, normalized display Y. Used only by the
    // touch-region editor to visualize the exact coordinates produced by the Shizuku evdev path.
    private static volatile float[] lastTouchMappedPoints = new float[0];
    private static volatile boolean pendingTouchRegionEditorRequest;
    private String lastForegroundPackage = "";
    private String homePackage = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean savedStateRefreshScheduled = new AtomicBoolean();
    private final Runnable applySavedStateRunnable = () -> {
        savedStateRefreshScheduled.set(false);
        applySavedState();
    };
    private final AtomicBoolean sensitivityRefreshScheduled = new AtomicBoolean();
    private final Runnable applySensitivityRunnable = () -> {
        sensitivityRefreshScheduled.set(false);
        applySensitivityState();
    };
    private final AtomicBoolean floatingMediaRefreshScheduled = new AtomicBoolean();
    private final Runnable syncFloatingMediaRunnable = this::syncFloatingMediaNow;

    private WindowManager windowManager;
    private InputManager inputManager;
    private MouseInputMonitor mouseMonitor;
    private TouchInputMonitor touchMonitor;
    private GamepadInputMonitor gamepadMonitor;
    private Vader5ProUsbMonitor vader5UsbMonitor;
    private SensitivityProxyController sensitivityController;
    private ForceHoldController forceHoldController;
    private GamepadKeyMapper gamepadKeyMapper;
    private boolean mouseTickerRunning;
    private boolean mouseMonitorActive;
    private boolean touchMonitorActive;
    private boolean gamepadMonitorActive;
    private boolean dpsTickerRunning;
    private final DpsTracker dpsTracker = new DpsTracker();
    private int previousGamepadButtonsForDps;
    private int previousGamepadButtonsForBindings;
    private int activeDpsTargetKeyCode = OverlayState.DPS_TARGET_NONE;
    private int proxyMouseButtons;
    private int gamepadLx, gamepadLy, gamepadRx, gamepadRy, gamepadLt, gamepadRt, gamepadButtons;
    private int rawGamepadButtons;
    // Some controllers (notably Flydigi Dune Fox on selected Android stacks) can report
    // L1 as BUTTON_X through KeyEvent while evdev still reports the physical shoulder edge.
    // Keep the latest raw edge so Android semantics can be corrected without changing real X.
    private int recentRawGamepadChangedMask;
    private int recentRawGamepadSnapshot;
    private long recentRawGamepadChangedAt;
    private boolean vader5ProConnected;
    private boolean vader5NativeProfile;
    private boolean vader5UsbProfile;
    private int vader5UsbBackButtons;
    // Capability fingerprint used by Dune Fox/hybrid XInput-DInput devices:
    // canonical BTN_WEST is X while legacy BTN_THUMB2 carries physical L1.
    private boolean legacyThumb2AsL1Profile;
    private int androidGamepadButtons;
    private int androidGamepadKnownMask;
    private boolean globalHtmlActive;
    private String globalHtmlContent = "";
    private InputRuntimeConfig inputRuntimeConfig = InputRuntimeConfig.EMPTY;
    private GamepadRuntimeConfig gamepadRuntimeConfig = GamepadRuntimeConfig.EMPTY;
    private final java.util.HashSet<Integer> physicalKeyboardKeysDown = new java.util.HashSet<>();
    private final java.util.HashSet<Integer> forceHoldVirtualDeviceIds = new java.util.HashSet<>();
    private final java.util.HashSet<Integer> otherAxonVirtualDeviceIds = new java.util.HashSet<>();
    private boolean forceHoldVisualActive;
    private int forceHoldVisualKeyCode = -1;

    private final DisplayWindow keyboardWindow = new DisplayWindow(KeyOverlayView.DISPLAY_KEYBOARD, "AxonInputKeyboard");
    private final DisplayWindow customWindow = new DisplayWindow(KeyOverlayView.DISPLAY_CUSTOM, "AxonInputCustom");
    private final DisplayWindow mouseWindow = new DisplayWindow(KeyOverlayView.DISPLAY_MOUSE, "AxonInputMouse");

    private SuperCustomOverlayView superCustomView;
    private WindowManager.LayoutParams superCustomParams;
    private boolean superCustomAttached;

    private NativeKeyCanvasView touchDisplayView;
    private WindowManager.LayoutParams touchDisplayParams;
    private boolean touchDisplayAttached;
    private int touchDisplayPressedMask;

    private TouchRegionOverlayEditorView touchRegionEditorView;
    private WindowManager.LayoutParams touchRegionEditorParams;
    private boolean touchRegionEditorAttached;
    private final Runnable showTouchRegionEditorRunnable = this::showTouchRegionEditorInternal;

    private final Object mouseMotionLock = new Object();
    private int pendingMouseDx;
    private int pendingMouseDy;
    private boolean mouseMotionPosted;

    private final Object touchFrameLock = new Object();
    private TouchInputMonitor.TouchPoint[] pendingTouchFrame = new TouchInputMonitor.TouchPoint[0];
    private boolean touchFramePosted;
    private final Map<Integer, TouchRoleState> touchRoles = new HashMap<>();
    private final Map<Integer, TouchFramePoint> touchFramePoints = new HashMap<>();
    private int touchFrameGeneration;
    private long lastTouchStatusUpdateUptimeMs;
    private final float[][] touchRegionRects = new float[TouchDisplayStore.REGION_COUNT][4];
    private float touchJoystickThresholdX = 0.010f;
    private float touchJoystickThresholdY = 0.012f;

    private KeyboardCatOverlayView keyboardCatView;
    private WindowManager.LayoutParams keyboardCatParams;
    private boolean keyboardCatAttached;

    private Live2DOverlayView live2dView;
    private WindowManager.LayoutParams live2dParams;
    private boolean live2dAttached;
    private int live2dAttachRetryCount;
    private View live2dDragHandle;
    private WindowManager.LayoutParams live2dDragHandleParams;
    private boolean live2dMouseCaptureEnabled;
    private float live2dDragStartRawX;
    private float live2dDragStartRawY;
    private float live2dDragStartOffsetX;
    private float live2dDragStartOffsetY;
    private boolean keyboardCatRemoving;
    private int keyboardCatAttachRetryCount;
    private float keyboardCatDragStartRawX;
    private float keyboardCatDragStartRawY;
    private int keyboardCatDragStartWindowX;
    private int keyboardCatDragStartWindowY;

    private FloatingMediaOverlayController floatingMediaController;

    private final GamepadWindow leftStickWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_LEFT_STICK, "AxonInputLeftStick");
    private final GamepadWindow rightStickWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_RIGHT_STICK, "AxonInputRightStick");
    private final GamepadWindow faceWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_FACE, "AxonInputFaceButtons");
    private final GamepadWindow leftShoulderWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_LEFT_SHOULDER, "AxonInputLeftShoulder");
    private final GamepadWindow rightShoulderWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_RIGHT_SHOULDER, "AxonInputRightShoulder");
    private final GamepadWindow backWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_BACK, "AxonInputBackButtons");
    private final GamepadWindow dpadWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_DPAD, "AxonInputDpad");

    private KeyPromptOverlayView keyPromptView;
    private WindowManager.LayoutParams keyPromptParams;
    private boolean keyPromptAttached;
    private boolean keyPromptRemoving;
    private float keyPromptDragStartRawX;
    private float keyPromptDragStartRawY;
    private int keyPromptDragStartWindowX;
    private int keyPromptDragStartWindowY;
    private int keyPromptMouseButtons;

    private DpsOverlayView dpsView;
    private WindowManager.LayoutParams dpsParams;
    private boolean dpsAttached;

    private FullKeyboardOverlayView inputFullKeyboardView;
    private WindowManager.LayoutParams inputFullKeyboardParams;
    private boolean inputFullKeyboardAttached;
    private float dpsDragStartRawX;
    private float dpsDragStartRawY;
    private int dpsDragStartWindowX;
    private int dpsDragStartWindowY;

    private MouseTrajectoryView trajectoryView;
    private WindowManager.LayoutParams trajectoryParams;
    private boolean trajectoryAttached;
    private boolean trajectoryRemoving;
    private float trajectoryDragStartRawX;
    private float trajectoryDragStartRawY;
    private int trajectoryDragStartWindowX;
    private int trajectoryDragStartWindowY;

    private final Runnable mouseTicker = new Runnable() {
        @Override
        public void run() {
            if (!mouseTickerRunning || !inputRuntimeConfig.mouseEnabled) {
                mouseTickerRunning = false;
                return;
            }
            if (mouseWindow.view != null) {
                mouseWindow.view.setMouseStats(NativeKeyEngine.nativeGetMouseStats(SystemClock.uptimeMillis()));
            }
            mainHandler.postDelayed(this, 100L);
        }
    };

    private final Runnable dpsTicker = new Runnable() {
        @Override
        public void run() {
            if (!dpsTickerRunning || !needsDpsTicker()) {
                dpsTickerRunning = false;
                return;
            }
            pushDpsToViews(SystemClock.uptimeMillis());
            mainHandler.postDelayed(this, 100L);
        }
    };

    private boolean colorTickerRunning;
    private final Runnable colorTicker = new Runnable() {
        @Override public void run() {
            if (!colorTickerRunning) return;
            refreshAnimatedDisplayColors();
            mainHandler.postDelayed(this, 50L);
        }
    };

    private final class DisplayWindow {
        final int type;
        final String title;
        KeyOverlayView view;
        WindowManager.LayoutParams params;
        boolean attached;
        boolean removing;
        float dragStartRawX;
        float dragStartRawY;
        int dragStartWindowX;
        int dragStartWindowY;

        DisplayWindow(int type, String title) {
            this.type = type;
            this.title = title;
        }
    }

    private final class GamepadWindow {
        final int type;
        final String title;
        GamepadOverlayView view;
        WindowManager.LayoutParams params;
        boolean attached;
        boolean removing;
        float dragStartRawX;
        float dragStartRawY;
        int dragStartWindowX;
        int dragStartWindowY;

        GamepadWindow(int type, String title) {
            this.type = type;
            this.title = title;
        }
    }


    private static final class TouchRoleState {
        static final int NONE = 0;
        static final int JOYSTICK = 1;
        static final int LMB = 2;
        static final int RMB = 3;
        static final int SPACE = 4;

        final int role;
        final float startX;
        final float startY;

        TouchRoleState(int role, float startX, float startY) {
            this.role = role;
            this.startX = startX;
            this.startY = startY;
        }
    }

    private static final class TouchFramePoint {
        float x;
        float y;
        int generation;
    }

    /** Secure settings may report the service enabled before Android has actually bound it. */
    public static boolean isServiceConnected() {
        return activeService != null;
    }

    public static void refreshActiveService() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        service.requestSavedStateRefresh();
    }

    /** Live2D size is a renderer-only parameter; do not rebuild every overlay while dragging it. */
    public static void refreshLive2DSize() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        service.mainHandler.post(() -> {
            if (service.live2dView != null && service.live2dAttached) {
                service.live2dView.setDisplayScalePercent(OverlayState.getLive2DSize(service));
                service.updateLive2DDragHandleLayout();
            }
        });
    }

    /** Watermark visibility is also renderer-only; update the active Live2D runtime in place. */
    public static void refreshLive2DWatermark() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        service.mainHandler.post(() -> {
            if (service.live2dView != null && service.live2dAttached) {
                service.live2dView.setHideWatermark(OverlayState.isLive2DHideWatermarkEnabled(service));
            }
        });
    }

    /**
     * Foreground/in-app and accessibility-overlay renderers are mutually exclusive.  This refresh
     * intentionally bypasses the coalesced full saved-state queue: Activity onPause/onResume can
     * otherwise race an already queued refresh and leave Live2D with no owner after app handoff.
     */
    public static void refreshLive2DOwnership() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        service.mainHandler.post(() ->
                service.syncLive2DWindow(OverlayState.isLive2DEnabled(service)));
    }

    private void requestSavedStateRefresh() {
        ensureColorTicker();
        if (!savedStateRefreshScheduled.compareAndSet(false, true)) return;
        mainHandler.post(applySavedStateRunnable);
    }

    private void ensureColorTicker() {
        if (!OverlayState.hasAnimatedColors(this)) {
            colorTickerRunning = false;
            mainHandler.removeCallbacks(colorTicker);
            return;
        }
        if (colorTickerRunning) return;
        colorTickerRunning = true;
        mainHandler.post(colorTicker);
    }

    private void refreshAnimatedDisplayColors() {
        refreshWindowColors(keyboardWindow);
        refreshWindowColors(mouseWindow);
        refreshWindowColors(customWindow);
        refreshGamepadColors(faceWindow);
        refreshGamepadColors(dpadWindow);
        refreshGamepadColors(leftShoulderWindow);
        refreshGamepadColors(rightShoulderWindow);
        refreshGamepadColors(backWindow);
        if (inputFullKeyboardView != null && inputFullKeyboardAttached) {
            inputFullKeyboardView.setKeyAppearance(
                    OverlayState.getKeyStyle(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                    OverlayState.getKeyPressColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setKeyBaseColor(OverlayState.getKeyBaseColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setKeyBorderColor(OverlayState.getKeyBorderColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
        }
        if (keyPromptView != null && keyPromptAttached) {
            keyPromptView.setKeyAppearance(OverlayState.getKeyStyle(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyPressColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setKeyBaseColor(OverlayState.getKeyBaseColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setKeyBorderColor(OverlayState.getKeyBorderColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        }
        if (trajectoryView != null && trajectoryAttached) {
            trajectoryView.setButtonColorConfig(
                    OverlayState.isMouseTrajectoryLeftColorEnabled(this), OverlayState.getMouseTrajectoryLeftColor(this),
                    OverlayState.isMouseTrajectoryRightColorEnabled(this), OverlayState.getMouseTrajectoryRightColor(this));
        }
    }

    private void refreshWindowColors(DisplayWindow window) {
        if (window == null || !window.attached || window.view == null) return;
        window.view.setKeyAppearance(OverlayState.getKeyStyle(this, window.type), OverlayState.getKeyPressColor(this, window.type));
        window.view.setKeyBaseColor(OverlayState.getKeyBaseColor(this, window.type));
        window.view.setKeyBorderColor(OverlayState.getKeyBorderColor(this, window.type));
        if (window.type == KeyOverlayView.DISPLAY_KEYBOARD) window.view.setTextColor(OverlayState.getKeyboardTextColor(this));
    }

    private void refreshGamepadColors(GamepadWindow window) {
        if (window == null || !window.attached || window.view == null) return;
        window.view.setKeyAppearance(OverlayState.getKeyStyle(this, window.type), OverlayState.getKeyPressColor(this, window.type));
        window.view.setKeyBaseColor(OverlayState.getKeyBaseColor(this, window.type));
        window.view.setKeyBorderColor(OverlayState.getKeyBorderColor(this, window.type));
    }

    public static String getTouchMonitorStatus() {
        String base = lastTouchMonitorStatus == null ? "未知" : lastTouchMonitorStatus;
        String frame = lastTouchFrameStatus == null ? "" : lastTouchFrameStatus;
        return frame.isEmpty() ? base : base + " · " + frame;
    }

    public static float[] getTouchMappedPointsForEditor() {
        float[] points = lastTouchMappedPoints;
        return points == null || points.length == 0 ? new float[0] : points.clone();
    }

    /** Opens the four-region editor as a full-display accessibility overlay over the foreground app. */
    public static void showTouchRegionEditorOverlay() {
        pendingTouchRegionEditorRequest = true;
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        Runnable action = service::showTouchRegionEditorInternal;
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else service.mainHandler.post(action);
    }

    public static void hideTouchRegionEditorOverlay() {
        pendingTouchRegionEditorRequest = false;
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        Runnable action = () -> service.removeTouchRegionEditorImmediate(false);
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else service.mainHandler.post(action);
    }

    public static boolean isTouchRegionEditorOverlayActive() {
        AxonInputAccessibilityService service = activeService;
        return service != null && service.touchRegionEditorAttached;
    }

    public static void restartTouchMonitor() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        Runnable action = () -> {
            service.stopTouchMonitor();
            service.applyTouchMonitorState();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else service.mainHandler.post(action);
    }

    /** 仅同步悬浮媒体；连续配置变更合并到一次主线程刷新。 */
    public static void refreshFloatingVideo() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        service.requestFloatingMediaRefresh();
    }

    private void requestFloatingMediaRefresh() {
        if (!floatingMediaRefreshScheduled.compareAndSet(false, true)) return;
        mainHandler.post(syncFloatingMediaRunnable);
    }

    private void syncFloatingMediaNow() {
        floatingMediaRefreshScheduled.set(false);
        if (floatingMediaController != null) {
            floatingMediaController.sync(inputRuntimeConfig.superCustomEnabled, inputRuntimeConfig.dragEnabled);
        }
    }

    /** 只更新灵敏度代理，避免滑动倍率时重建其他悬浮状态。 */
    public static void refreshSensitivity() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        service.requestSensitivityRefresh();
    }

    private void requestSensitivityRefresh() {
        if (!sensitivityRefreshScheduled.compareAndSet(false, true)) return;
        mainHandler.post(applySensitivityRunnable);
    }


    /** 重建轻量悬浮视图并立即应用用户配色。 */
    public static void refreshTheme() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        Runnable action = () -> {
            service.removeWindowImmediate(service.keyboardWindow);
            service.removeWindowImmediate(service.customWindow);
            service.removeWindowImmediate(service.mouseWindow);
            service.removeKeyboardCatImmediate();
            service.removeLive2DImmediate();
            if (service.floatingMediaController != null) service.floatingMediaController.removeAll();
            service.removeSuperCustomImmediate();
            service.removeKeyPromptImmediate();
            service.removeDpsImmediate();
            service.removeInputFullKeyboardImmediate();
            service.removeTrajectoryImmediate();
            service.removeGamepadWindowImmediate(service.leftStickWindow);
            service.removeGamepadWindowImmediate(service.rightStickWindow);
            service.removeGamepadWindowImmediate(service.faceWindow);
            service.removeGamepadWindowImmediate(service.leftShoulderWindow);
            service.removeGamepadWindowImmediate(service.rightShoulderWindow);
            service.removeGamepadWindowImmediate(service.backWindow);
            service.removeGamepadWindowImmediate(service.dpadWindow);
            service.applySavedState();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else service.mainHandler.post(action);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingMediaController = new FloatingMediaOverlayController(this);
        floatingMediaController.setWindowManager(windowManager);
        activeService = this;
        ensureColorTicker();
        homePackage = resolveHomePackage();
        String foreground = currentForegroundPackage();
        if (!foreground.isEmpty()) lastForegroundPackage = foreground;

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
            setServiceInfo(info);
        }

        if (pendingTouchRegionEditorRequest) mainHandler.post(this::showTouchRegionEditorInternal);
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
            for (int deviceId : inputManager.getInputDeviceIds()) {
                rememberAxonVirtualDevice(deviceId, inputManager.getInputDevice(deviceId));
            }
        }

        mouseMonitor = new MouseInputMonitor(this, this);
        gamepadMonitor = new GamepadInputMonitor(this, this);
        vader5UsbMonitor = new Vader5ProUsbMonitor(this, this);
        sensitivityController = new SensitivityProxyController(this, this);
        forceHoldController = new ForceHoldController(this, () -> mainHandler.post(() -> {
            clearForcedHoldVisual();
            Toast.makeText(this, R.string.force_hold_start_failed, Toast.LENGTH_SHORT).show();
        }));
        gamepadKeyMapper = new GamepadKeyMapper(this);
        if (!RootBridge.isRootActive() || TouchDisplayStore.isEnabled(this)) ShizukuBridge.addListener(this);
        // Root 是全局首选通道；探测完成后重新应用输入状态。显示悬浮层本身仍立即创建。
        RootBridge.ensureActivated(this, rootActive -> {
            if (rootActive && !TouchDisplayStore.isEnabled(this)) ShizukuBridge.removeListener(this);
            else ShizukuBridge.addListener(this);
            applySavedState();
        });
        applySavedState();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event == null ? 0 : event.getEventType();
        if (event != null && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            String foreground = "";
            CharSequence packageName = event.getPackageName();
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && packageName != null && packageName.length() > 0) {
                foreground = packageName.toString();
            }

            /*
             * TYPE_ACCESSIBILITY_OVERLAY itself generates window accessibility events.  While the
             * region editor is attached those events may report Axon (or SystemUI) as the active
             * package for a frame even though the real target app never changed.  Feeding that
             * transient package back into the editor visibility state machine creates a feedback
             * loop: add overlay -> Axon window event -> remove overlay -> target app event -> add.
             * Keep the attached editor stable and never let its own window events tear it down.
             */
            if (!touchRegionEditorAttached) {
                if (foreground.isEmpty()) foreground = currentForegroundPackage();
                if (!foreground.isEmpty()) lastForegroundPackage = foreground;
                handleTouchEditorForegroundChanged(foreground);
            } else if (!foreground.isEmpty()
                    && !getPackageName().equals(foreground)
                    && !"com.android.systemui".equals(foreground)) {
                // Remember a real external package for diagnostics/future sessions, but do not
                // rebuild or hide the editor while the user is actively adjusting regions.
                lastForegroundPackage = foreground;
            }
        }

        // Window changes are a reliable second ownership signal on ROMs that delay Activity
        // lifecycle callbacks. Keep Live2D attached outside Axon even when the normal saved-state
        // refresh was coalesced during the app -> game transition.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            syncLive2DWindow(OverlayState.isLive2DEnabled(this));
        }

        if (!inputRuntimeConfig.fullKeyboardEnabled) {
            removeInputFullKeyboardImmediate();
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            syncInputFullKeyboardVisibility();
        }
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && inputFullKeyboardAttached) {
            flashTextInput(event);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 主题由用户选择。系统配置变化后只更新布局。
        // 旋转或密度变化不修改用户配色。区域编辑器是单实例窗口：横屏配置变化时
        // 原地更新，而不是 remove/add，避免游戏持续触发配置/窗口事件时整层闪烁。
        mainHandler.post(() -> {
            applySavedState();
            if (touchRegionEditorAttached) {
                if (!TouchDisplayStore.isEnabled(this)) {
                    removeTouchRegionEditorImmediate(true);
                } else if (!isActualLandscape()) {
                    removeTouchRegionEditorImmediate(false);
                    pendingTouchRegionEditorRequest = true;
                } else {
                    updateTouchRegionEditorLayoutInPlace();
                }
            } else if (pendingTouchRegionEditorRequest) {
                showTouchRegionEditorInternal();
            }
        });
    }

    @Override
    public void onInterrupt() {
        if (forceHoldController != null) forceHoldController.release();
        forceHoldVisualActive = false;
        forceHoldVisualKeyCode = -1;
        physicalKeyboardKeysDown.clear();
        resetPressedState();
        if (inputFullKeyboardView != null) inputFullKeyboardView.clearPressed();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;

        boolean pressed = action == KeyEvent.ACTION_DOWN;
        boolean firstPress = pressed && event.getRepeatCount() == 0;

        // 手柄按键使用统一绑定编码。标准按钮状态仍交给 applyGamepadState 合并，
        // 避免 Android KeyEvent 与 evdev 同一按键被计算两次。
        if (isPhysicalGamepadEvent(event)
                || (gamepadKeyMapper != null && InputBinding.isGamepadEventForMapping(event)
                && (gamepadKeyMapper.hasMapping(InputBinding.fromGamepadEvent(event))
                || gamepadKeyMapper.isPressEngaged(InputBinding.fromGamepadEvent(event))))) {
            int logicalBit = resolveGamepadAndroidBit(event, pressed);
            int inputCode = logicalBit != 0 ? InputBinding.gamepad(logicalBit) : InputBinding.fromGamepadEvent(event);
            boolean mappedConsumed = false;
            if (inputCode >= 0 && gamepadKeyMapper != null
                    && !MainActivity.isNonDpsBindingCaptureActive()
                    && (gamepadKeyMapper.hasMapping(inputCode)
                    || gamepadKeyMapper.isPressEngaged(inputCode))) {
                mappedConsumed = gamepadKeyMapper.dispatch(
                        inputCode, pressed, firstPress, pressed && !firstPress);
            }
            if (logicalBit == 0 && inputCode >= 0 && !gamepadKeyMapperHasMapping(inputCode)) {
                handleBoundInputEvent(inputCode, pressed, firstPress, event.getEventTime());
                if (firstPress) handleDirectDpsBindingInput(inputCode, event.getEventTime());
            }
            if (inputRuntimeConfig.keyboardCatEnabled && keyboardCatView != null && keyboardCatView.isGamepadStyle()) {
                keyboardCatView.setGamepadDirectional(event.getKeyCode(), pressed);
            }
            if (logicalBit != 0) {
                // Use the corrected semantic bit as the override group. Using the original
                // Android BUTTON_X here would re-introduce the Dune Fox L1 -> X alias.
                int knownGroup = logicalBit;
                androidGamepadKnownMask |= knownGroup;
                if (pressed) androidGamepadButtons |= logicalBit;
                else androidGamepadButtons &= ~logicalBit;
                applyGamepadState(gamepadLx, gamepadLy, gamepadRx, gamepadRy, gamepadLt, gamepadRt, rawGamepadButtons);
            }
            return mappedConsumed;
        }

        InputRuntimeConfig config = inputRuntimeConfig;
        if (!config.needsKeyboardEvents()) return false;
        if (!isPhysicalKeyboardEvent(event)) return false;

        boolean builtin = config.keyboardEnabled;
        boolean inputFullKeyboard = config.fullKeyboardEnabled;
        boolean custom = config.customEnabled;
        boolean superCustom = config.superCustomEnabled;
        boolean keyboardCat = config.keyboardCatEnabled;
        boolean keyPrompt = config.keyPromptEnabled;
        boolean dpsEnabled = config.dpsEnabled;

        int keyCode = event.getKeyCode();
        int inputCode = InputBinding.keyboard(keyCode);
        if (pressed) physicalKeyboardKeysDown.add(keyCode);
        else physicalKeyboardKeysDown.remove(keyCode);

        handleBoundInputEvent(inputCode, pressed, firstPress, event.getEventTime());

        boolean visualPressed = pressed
                || (forceHoldVisualActive && keyCode == forceHoldVisualKeyCode);
        if (inputFullKeyboard && inputFullKeyboardView != null) {
            inputFullKeyboardView.setPhysicalKey(keyCode, visualPressed);
        }
        if (keyboardCat && keyboardCatView != null) {
            keyboardCatView.setKeyState(keyCode, visualPressed);
        }

        int dpsTarget = config.dpsTargetKey;
        if (dpsEnabled && dpsTarget == OverlayState.DPS_TARGET_NONE && firstPress
                && !MainActivity.isNonDpsBindingCaptureActive()) {
            // 启用后第一次输入用于绑定，不计入 CPS。
            OverlayState.setDpsTargetKeyCode(this, keyCode);
            inputRuntimeConfig = config = config.withDpsTarget(keyCode);
            dpsTarget = keyCode;
            if (dpsView != null) dpsView.setDpsValue(0);
        } else if (dpsEnabled && dpsTarget == keyCode && firstPress) {
            dpsTracker.record(DpsTracker.TARGET, event.getEventTime());
            if (dpsView != null) pushDpsToViews(event.getEventTime());
        }

        if (keyPrompt && keyPromptView != null) {
            if (!pressed || firstPress) {
                keyPromptView.updateKeyboardKey(
                        keyCode, visualPressed, firstPress, event.getEventTime());
            }
        }

        if (builtin && keyCode == KeyEvent.KEYCODE_SPACE && firstPress) {
            dpsTracker.record(DpsTracker.SPACE, event.getEventTime());
            if (keyboardWindow.view != null) pushDpsToViews(event.getEventTime());
        }

        if (NativeKeyEngine.nativeIsTrackedKey(keyCode)) {
            int mask = NativeKeyEngine.nativeUpdateKey(keyCode, visualPressed);
            if (builtin && keyboardWindow.view != null) keyboardWindow.view.setPressedMask(mask);
        }
        if (custom && customWindow.view != null) {
            customWindow.view.setCustomKeyPressed(inputCode, visualPressed);
        }
        if (superCustom && superCustomView != null) {
            superCustomView.setInputPressed(inputCode, visualPressed);
        }
        return false;
    }

    private boolean gamepadKeyMapperHasMapping(int inputCode) {
        return gamepadKeyMapper != null && gamepadKeyMapper.hasMapping(inputCode);
    }

    /** 所有已绑定快捷动作的统一触发入口；永远不消费物理输入。 */
    private void handleBoundInputEvent(int inputCode, boolean pressed, boolean firstPress, long eventTime) {
        if (inputCode < 0) return;

        InputRuntimeConfig config = inputRuntimeConfig;
        if (firstPress && config.hideDisplayHotkey >= 0 && inputCode == config.hideDisplayHotkey) {
            OverlayState.toggleHideDisplayTargets(this);
            // This is an Axon action hotkey. Never let stale/conflicting bindings trigger a second action.
            return;
        }
        if (config.forceHoldEnabled && inputCode == config.forceHoldTriggerKey
                && firstPress && forceHoldController != null) {
            int targetInput = config.forceHoldTargetKey;
            boolean held = forceHoldController.toggleHold(
                    targetInput, config.forceHoldTargetScanCode);
            if (InputBinding.isKeyboard(targetInput)) setForcedHoldVisual(targetInput, held);
            else if (!held) clearForcedHoldVisual();
        }

        int expressionHotkey = config.expressionHotkey;
        if (config.keyboardCatEnabled
                && expressionHotkey >= 0 && inputCode == expressionHotkey && firstPress) {
            cycleKeyboardCatExpressionHotkey();
        }

        if (config.superCustomEnabled && firstPress) {
            if (floatingMediaController != null) floatingMediaController.dispatchHotkey(inputCode);
        }

        if (config.customCaptureEnabled && firstPress) {
            OverlayState.addDraftKey(this, inputCode);
        }

        if (config.customEnabled && customWindow.view != null && !InputBinding.isKeyboard(inputCode)) {
            customWindow.view.setCustomKeyPressed(inputCode, pressed);
        }
    }

    private void handleDirectDpsBindingInput(int inputCode, long now) {
        InputRuntimeConfig config = inputRuntimeConfig;
        if (!config.dpsEnabled || inputCode < 0) return;
        int resolved = OverlayState.dpsTargetFromBinding(inputCode);
        int target = config.dpsTargetKey;
        if (target == OverlayState.DPS_TARGET_NONE) {
            if (MainActivity.isNonDpsBindingCaptureActive()) return;
            OverlayState.setDpsTargetKeyCode(this, resolved);
            inputRuntimeConfig = config.withDpsTarget(resolved);
            dpsTracker.resetChannel(DpsTracker.TARGET);
            if (dpsView != null) dpsView.setDpsValue(0);
        } else if (target == resolved) {
            dpsTracker.record(DpsTracker.TARGET, now);
            if (dpsView != null) pushDpsToViews(now);
        }
    }

    private void cycleKeyboardCatExpressionHotkey() {
        String styleId = OverlayState.getKeyboardCatStyleId(this);
        List<BongoCatStyleManager.ExpressionOption> options =
                BongoCatStyleManager.expressionOptions(this, styleId);
        if (options.isEmpty()) return;

        boolean explicit = OverlayState.hasKeyboardCatExpressionHotkeySelection(this, styleId);
        Set<String> selected = OverlayState.getKeyboardCatExpressionHotkeySelection(this, styleId);
        List<BongoCatStyleManager.ExpressionOption> cycle = new ArrayList<>();
        for (BongoCatStyleManager.ExpressionOption option : options) {
            if (!explicit || selected.contains(option.token)) cycle.add(option);
        }
        if (cycle.isEmpty()) return;

        String current = OverlayState.getKeyboardCatDebugExpression(this);
        int nextIndex = 0;
        for (int i = 0; i < cycle.size(); i++) {
            if (cycle.get(i).token.equals(current)) {
                nextIndex = (i + 1) % cycle.size();
                break;
            }
        }
        String next = cycle.get(nextIndex).token;
        OverlayState.setKeyboardCatDebugExpressionRuntime(this, next);
        if (keyboardCatView != null) keyboardCatView.setDebugExpression(next);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice device = inputManager == null ? null : inputManager.getInputDevice(deviceId);
        if (rememberAxonVirtualDevice(deviceId, device)) return;
        // A newly-added physical device starts neutral. Do not clear currently held inputs from
        // other devices; that would make held mouse/keyboard states disappear until the next edge.
        // MouseInputMonitor 使用全局 getevent 并在解析层过滤 Axon 虚拟设备，
        // 因此鼠标热插拔无需重启监听；重启反而会制造 REL_X/REL_Y 输入空窗。
        if (gamepadMonitorActive) {
            stopGamepadMonitor();
            startGamepadMonitor();
        }
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        if (forceHoldVirtualDeviceIds.remove(deviceId)) {
            // 正常停止前 desiredHeld 已经变为 false；仍为 true 说明代理异常退出。
            if (forceHoldController != null && forceHoldController.isHoldRequested()) {
                forceHoldController.release();
                clearForcedHoldVisual();
            }
            return;
        }
        if (otherAxonVirtualDeviceIds.remove(deviceId)) return;
        // 物理触发设备异常移除时主动释放。
        if (forceHoldController != null && forceHoldController.isHoldRequested()) {
            forceHoldController.release();
            clearForcedHoldVisual();
        }
        resetPressedState();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        if (forceHoldVirtualDeviceIds.contains(deviceId)
                || otherAxonVirtualDeviceIds.contains(deviceId)) return;
        InputDevice device = inputManager == null ? null : inputManager.getInputDevice(deviceId);
        if (rememberAxonVirtualDevice(deviceId, device)) return;
        resetPressedState();
    }

    private boolean rememberAxonVirtualDevice(int deviceId, InputDevice device) {
        if (device == null || !InputBinding.isAxonVirtualDevice(device)) return false;
        String name = device.getName();
        if ("Axon Input Virtual Force Hold".equals(name)) {
            forceHoldVirtualDeviceIds.add(deviceId);
        } else {
            otherAxonVirtualDeviceIds.add(deviceId);
        }
        return true;
    }

    @Override
    public void onShizukuReady(boolean permissionGranted) {
        boolean touchShizuku = TouchDisplayStore.isEnabled(this);
        if (RootBridge.isRootActive() && !touchShizuku) return;
        if (!permissionGranted) return;
        if (!RootBridge.isRootActive() && sensitivityController != null) {
            sensitivityController.onShizukuAvailable();
        }
        applySavedState();
    }

    @Override
    public void onShizukuPermissionResult(int requestCode, boolean granted) {
        boolean touchShizuku = TouchDisplayStore.isEnabled(this);
        if (RootBridge.isRootActive() && !touchShizuku) return;
        if (!granted) return;
        if (!RootBridge.isRootActive() && sensitivityController != null) {
            sensitivityController.onShizukuAvailable();
        }
        applySavedState();
    }

    @Override
    public void onShizukuDead() {
        boolean touchShizuku = TouchDisplayStore.isEnabled(this);
        if (RootBridge.isRootActive() && !touchShizuku) return;
        if (!RootBridge.isRootActive()
                && inputRuntimeConfig.sensitivityMode != SensitivitySettingsStore.MODE_ROOT) {
            stopMouseMonitor();
        }
        if (!RootBridge.isRootActive()
                && inputRuntimeConfig.sensitivityMode == SensitivitySettingsStore.MODE_SHIZUKU) {
            stopGamepadMonitor();
        }
        if (touchShizuku) stopTouchMonitor();
        if (!RootBridge.isRootActive()) {
            if (sensitivityController != null) sensitivityController.onShizukuDead();
            if (forceHoldController != null && forceHoldController.isHoldRequested()) {
                forceHoldController.release();
                clearForcedHoldVisual();
            }
        }
    }

    @Override
    public void onMouseState(long packedStats) {
        mainHandler.post(() -> {
            int nextButtons = (int) (packedStats & 0x3L);
            int changedButtons = keyPromptMouseButtons ^ nextButtons;
            long now = SystemClock.uptimeMillis();
            if ((changedButtons & 1) != 0) {
                boolean pressed = (nextButtons & 1) != 0;
                handleBoundInputEvent(InputBinding.mouse(NativeKeyEngine.MOUSE_LEFT),
                        pressed, pressed, now);
                MainActivity.notifyPhysicalMouseButtonForBinding(
                        NativeKeyEngine.MOUSE_LEFT, pressed, now);
                SuperCustomDisplayActivity.notifyPhysicalMouseButtonForBinding(
                        NativeKeyEngine.MOUSE_LEFT, pressed, now);
                if (superCustomView != null && inputRuntimeConfig.superCustomEnabled) {
                    superCustomView.setInputPressed(
                            InputBinding.mouse(NativeKeyEngine.MOUSE_LEFT), pressed);
                }
            }
            if ((changedButtons & 2) != 0) {
                boolean pressed = (nextButtons & 2) != 0;
                handleBoundInputEvent(InputBinding.mouse(NativeKeyEngine.MOUSE_RIGHT),
                        pressed, pressed, now);
                MainActivity.notifyPhysicalMouseButtonForBinding(
                        NativeKeyEngine.MOUSE_RIGHT, pressed, now);
                SuperCustomDisplayActivity.notifyPhysicalMouseButtonForBinding(
                        NativeKeyEngine.MOUSE_RIGHT, pressed, now);
                if (superCustomView != null && inputRuntimeConfig.superCustomEnabled) {
                    superCustomView.setInputPressed(
                            InputBinding.mouse(NativeKeyEngine.MOUSE_RIGHT), pressed);
                }
            }
            updateCpsMouseTarget(changedButtons, nextButtons, now);
            if (keyPromptView != null && inputRuntimeConfig.keyPromptEnabled) {
                if ((changedButtons & 1) != 0) {
                    keyPromptView.updateMouseButton(NativeKeyEngine.MOUSE_LEFT, (nextButtons & 1) != 0, now);
                }
                if ((changedButtons & 2) != 0) {
                    keyPromptView.updateMouseButton(NativeKeyEngine.MOUSE_RIGHT, (nextButtons & 2) != 0, now);
                }
            }
            keyPromptMouseButtons = nextButtons;
            if (keyboardWindow.view != null && inputRuntimeConfig.keyboardEnabled) {
                keyboardWindow.view.setKeyboardMouseButtons(nextButtons);
            }
            if (mouseWindow.view != null && inputRuntimeConfig.mouseEnabled) {
                mouseWindow.view.setMouseStats(packedStats);
            }
            if (trajectoryView != null && inputRuntimeConfig.mouseTrajectoryEnabled) {
                trajectoryView.setMouseStats(packedStats);
            }
            if (keyboardCatView != null && inputRuntimeConfig.keyboardCatEnabled) {
                keyboardCatView.setMouseButtons(nextButtons);
            }
        });
    }

    private void updateCpsMouseTarget(int changedButtons, int nextButtons, long now) {
        InputRuntimeConfig config = inputRuntimeConfig;
        if (!config.dpsEnabled) return;
        boolean leftPressed = (changedButtons & 1) != 0 && (nextButtons & 1) != 0;
        boolean rightPressed = (changedButtons & 2) != 0 && (nextButtons & 2) != 0;
        if (!leftPressed && !rightPressed) return;

        int target = config.dpsTargetKey;
        if (target == OverlayState.DPS_TARGET_NONE) {
            if (MainActivity.isNonDpsBindingCaptureActive()) return;
            int mouseTarget = leftPressed
                    ? OverlayState.DPS_TARGET_MOUSE_LEFT
                    : OverlayState.DPS_TARGET_MOUSE_RIGHT;
            OverlayState.setDpsTargetKeyCode(this, mouseTarget);
            inputRuntimeConfig = config.withDpsTarget(mouseTarget);
            dpsTracker.resetChannel(DpsTracker.TARGET);
            if (dpsView != null) dpsView.setDpsValue(0);
            return;
        }

        if ((target == OverlayState.DPS_TARGET_MOUSE_LEFT && leftPressed)
                || (target == OverlayState.DPS_TARGET_MOUSE_RIGHT && rightPressed)) {
            dpsTracker.record(DpsTracker.TARGET, now);
            if (dpsView != null) pushDpsToViews(now);
        }
    }

    @Override
    public void onMousePromptButton(int button, boolean pressed) {
        mainHandler.post(() -> {
            long now = SystemClock.uptimeMillis();
            handleBoundInputEvent(InputBinding.mouse(button), pressed, pressed, now);
            MainActivity.notifyPhysicalMouseButtonForBinding(button, pressed, now);
            SuperCustomDisplayActivity.notifyPhysicalMouseButtonForBinding(button, pressed, now);
            if (superCustomView != null && inputRuntimeConfig.superCustomEnabled) {
                superCustomView.setInputPressed(InputBinding.mouse(button), pressed);
            }
            updateCpsMouseAuxTarget(button, pressed, now);
            if (keyPromptView != null && inputRuntimeConfig.keyPromptEnabled) {
                keyPromptView.updateMouseButton(button, pressed, now);
            }
            if (keyboardCatView != null && inputRuntimeConfig.keyboardCatEnabled) {
                keyboardCatView.setMouseAuxButton(button, pressed);
            }
        });
    }

    private void updateCpsMouseAuxTarget(int button, boolean pressed, long now) {
        InputRuntimeConfig config = inputRuntimeConfig;
        if (!pressed || !config.dpsEnabled) return;
        int mouseTarget = OverlayState.mouseDpsTarget(button);
        if (mouseTarget == OverlayState.DPS_TARGET_NONE) return;
        int target = config.dpsTargetKey;
        if (target == OverlayState.DPS_TARGET_NONE) {
            if (MainActivity.isNonDpsBindingCaptureActive()) return;
            OverlayState.setDpsTargetKeyCode(this, mouseTarget);
            inputRuntimeConfig = config.withDpsTarget(mouseTarget);
            dpsTracker.resetChannel(DpsTracker.TARGET);
            if (dpsView != null) dpsView.setDpsValue(0);
        } else if (target == mouseTarget) {
            dpsTracker.record(DpsTracker.TARGET, now);
            if (dpsView != null) pushDpsToViews(now);
        }
    }

    @Override
    public void onMouseMotion(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        boolean shouldPost = false;
        synchronized (mouseMotionLock) {
            pendingMouseDx = saturatingAdd(pendingMouseDx, dx);
            pendingMouseDy = saturatingAdd(pendingMouseDy, dy);
            if (!mouseMotionPosted) {
                mouseMotionPosted = true;
                shouldPost = true;
            }
        }
        if (shouldPost) mainHandler.post(this::consumeMouseMotion);
    }

    private void consumeMouseMotion() {
        int dx;
        int dy;
        synchronized (mouseMotionLock) {
            dx = pendingMouseDx;
            dy = pendingMouseDy;
            pendingMouseDx = 0;
            pendingMouseDy = 0;
            mouseMotionPosted = false;
        }
        if (dx == 0 && dy == 0) return;
        if (trajectoryView != null && inputRuntimeConfig.mouseTrajectoryEnabled) {
            trajectoryView.addMotion(dx, dy);
        }
        if (keyboardCatView != null && inputRuntimeConfig.keyboardCatEnabled) {
            keyboardCatView.addMouseMotion(dx, dy);
        }
        if (live2dMouseCaptureEnabled) {
            // Reuse the exact same physical REL_X/REL_Y stream already consumed by Axon. This is
            // also fed by SensitivityProxyController while overclock mode owns the evdev device,
            // so Live2D mouse capture never needs a second input monitor.
            Live2DMouseTracker.addMotion(dx, dy);
        }
    }

    private static int saturatingAdd(int current, int delta) {
        long value = (long) current + delta;
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }

    @Override
    public void onGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        mainHandler.post(() -> applyGamepadState(lx, ly, rx, ry, lt, rt, buttons));
    }

    @Override
    public void onGamepadProfile(boolean vader5Pro, boolean legacyThumb2AsL1) {
        mainHandler.post(() -> {
            vader5NativeProfile = vader5Pro;
            legacyThumb2AsL1Profile = legacyThumb2AsL1;
            updateVader5ProfileState();
        });
    }

    @Override
    public void onVader5UsbProfile(boolean connected) {
        mainHandler.post(() -> {
            vader5UsbProfile = connected;
            updateVader5ProfileState();
        });
    }

    @Override
    public void onVader5UsbBackButtons(int mask) {
        mainHandler.post(() -> {
            if (vader5UsbBackButtons == mask) return;
            vader5UsbBackButtons = mask;
            applyGamepadState(gamepadLx, gamepadLy, gamepadRx, gamepadRy,
                    gamepadLt, gamepadRt, rawGamepadButtons);
        });
    }

    private void updateVader5ProfileState() {
        boolean connected = vader5NativeProfile || vader5UsbProfile;
        vader5ProConnected = connected;
        if (backWindow.view != null) backWindow.view.setVader5BackLabels(connected);
    }

    @Override
    public void onSensitivityGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        mainHandler.post(() -> applyGamepadState(lx, ly, rx, ry, lt, rt, buttons));
    }

    @Override
    public void onSensitivityStatus(String status) {
        // 状态只用于当前会话，不写入长期配置。
    }

    @Override
    public void onSensitivityGamepadProfile(boolean legacyThumb2AsL1) {
        legacyThumb2AsL1Profile = legacyThumb2AsL1;
    }

    @Override
    public void onSensitivityMouseMotion(int dx, int dy) {
        onMouseMotion(dx, dy);
    }

    @Override
    public void onSensitivityMouseButtons(int mask) {
        int previous = proxyMouseButtons;
        proxyMouseButtons = mask;
        long now = SystemClock.uptimeMillis();
        long stats = NativeKeyEngine.nativeGetMouseStats(now);
        boolean oldLeft = (previous & 1) != 0;
        boolean newLeft = (mask & 1) != 0;
        if (oldLeft != newLeft) {
            stats = NativeKeyEngine.nativeUpdateMouseButton(NativeKeyEngine.MOUSE_LEFT, newLeft, now);
        }
        boolean oldRight = (previous & 2) != 0;
        boolean newRight = (mask & 2) != 0;
        if (oldRight != newRight) {
            stats = NativeKeyEngine.nativeUpdateMouseButton(NativeKeyEngine.MOUSE_RIGHT, newRight, now);
        }
        onMouseState(stats);
        int changed = previous ^ mask;
        if ((changed & (1 << 2)) != 0) onMousePromptButton(
                MouseInputMonitor.BUTTON_MIDDLE, (mask & (1 << 2)) != 0);
        if ((changed & (1 << 3)) != 0) onMousePromptButton(
                MouseInputMonitor.BUTTON_BACK, (mask & (1 << 3)) != 0);
        if ((changed & (1 << 4)) != 0) onMousePromptButton(
                MouseInputMonitor.BUTTON_FORWARD, (mask & (1 << 4)) != 0);
    }

    @Override
    public void onDragStart(KeyOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled) return;
        DisplayWindow target = windowForView(source);
        if (target == null || target.params == null) return;
        target.dragStartRawX = rawX;
        target.dragStartRawY = rawY;
        target.dragStartWindowX = target.params.x;
        target.dragStartWindowY = target.params.y;
    }

    @Override
    public void onDragMove(KeyOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || windowManager == null) return;
        DisplayWindow target = windowForView(source);
        if (target == null || !target.attached || target.params == null || target.view == null) return;

        int x = target.dragStartWindowX + Math.round(rawX - target.dragStartRawX);
        int y = target.dragStartWindowY + Math.round(rawY - target.dragStartRawY);
        // 不限制到可见区域。
        // 允许悬浮层拖到屏幕边缘或屏幕外。
        target.params.x = x;
        target.params.y = y;
        windowManager.updateViewLayout(target.view, target.params);
    }

    @Override
    public void onDragEnd(KeyOverlayView source) {
        DisplayWindow target = windowForView(source);
        if (target != null) saveCurrentPosition(target);
    }

    @Override
    public void onDragStart(KeyboardCatOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || keyboardCatParams == null) return;
        keyboardCatDragStartRawX = rawX;
        keyboardCatDragStartRawY = rawY;
        keyboardCatDragStartWindowX = keyboardCatParams.x;
        keyboardCatDragStartWindowY = keyboardCatParams.y;
    }

    @Override
    public void onDragMove(KeyboardCatOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || windowManager == null || !keyboardCatAttached
                || keyboardCatParams == null || keyboardCatView == null) return;
        keyboardCatParams.x = keyboardCatDragStartWindowX + Math.round(rawX - keyboardCatDragStartRawX);
        keyboardCatParams.y = keyboardCatDragStartWindowY + Math.round(rawY - keyboardCatDragStartRawY);
        windowManager.updateViewLayout(keyboardCatView, keyboardCatParams);
    }

    @Override
    public void onDragEnd(KeyboardCatOverlayView source) {
        saveKeyboardCatPosition();
    }

    @Override
    public void onDragStart(KeyPromptOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || keyPromptParams == null) return;
        keyPromptDragStartRawX = rawX;
        keyPromptDragStartRawY = rawY;
        keyPromptDragStartWindowX = keyPromptParams.x;
        keyPromptDragStartWindowY = keyPromptParams.y;
    }

    @Override
    public void onDragMove(KeyPromptOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || windowManager == null || !keyPromptAttached
                || keyPromptParams == null || keyPromptView == null) return;
        int x = keyPromptDragStartWindowX + Math.round(rawX - keyPromptDragStartRawX);
        int y = keyPromptDragStartWindowY + Math.round(rawY - keyPromptDragStartRawY);
        keyPromptParams.x = x;
        keyPromptParams.y = y;
        windowManager.updateViewLayout(keyPromptView, keyPromptParams);
    }

    @Override
    public void onDragEnd(KeyPromptOverlayView source) {
        saveKeyPromptPosition();
    }

    @Override
    public void onDragStart(MouseTrajectoryView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || trajectoryParams == null) return;
        trajectoryDragStartRawX = rawX;
        trajectoryDragStartRawY = rawY;
        trajectoryDragStartWindowX = trajectoryParams.x;
        trajectoryDragStartWindowY = trajectoryParams.y;
    }

    @Override
    public void onDragMove(MouseTrajectoryView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || windowManager == null || !trajectoryAttached
                || trajectoryParams == null || trajectoryView == null) return;
        int x = trajectoryDragStartWindowX + Math.round(rawX - trajectoryDragStartRawX);
        int y = trajectoryDragStartWindowY + Math.round(rawY - trajectoryDragStartRawY);
        trajectoryParams.x = x;
        trajectoryParams.y = y;
        windowManager.updateViewLayout(trajectoryView, trajectoryParams);
    }

    @Override
    public void onDragEnd(MouseTrajectoryView source) {
        saveTrajectoryPosition();
    }

    @Override
    public void onDragStart(GamepadOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled) return;
        GamepadWindow target = gamepadWindowForView(source);
        if (target == null || target.params == null) return;
        target.dragStartRawX = rawX;
        target.dragStartRawY = rawY;
        target.dragStartWindowX = target.params.x;
        target.dragStartWindowY = target.params.y;
    }

    @Override
    public void onDragMove(GamepadOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || windowManager == null) return;
        GamepadWindow target = gamepadWindowForView(source);
        if (target == null || !target.attached || target.params == null || target.view == null) return;
        int x = target.dragStartWindowX + Math.round(rawX - target.dragStartRawX);
        int y = target.dragStartWindowY + Math.round(rawY - target.dragStartRawY);
        target.params.x = x;
        target.params.y = y;
        windowManager.updateViewLayout(target.view, target.params);
    }

    @Override
    public void onDragEnd(GamepadOverlayView source) {
        GamepadWindow target = gamepadWindowForView(source);
        if (target != null) saveGamepadPosition(target);
    }

    @Override
    public void onDragStart(DpsOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || dpsParams == null) return;
        dpsDragStartRawX = rawX;
        dpsDragStartRawY = rawY;
        dpsDragStartWindowX = dpsParams.x;
        dpsDragStartWindowY = dpsParams.y;
    }

    @Override
    public void onDragMove(DpsOverlayView source, float rawX, float rawY) {
        if (!inputRuntimeConfig.dragEnabled || windowManager == null || !dpsAttached
                || dpsParams == null || dpsView == null) return;
        dpsParams.x = dpsDragStartWindowX + Math.round(rawX - dpsDragStartRawX);
        dpsParams.y = dpsDragStartWindowY + Math.round(rawY - dpsDragStartRawY);
        windowManager.updateViewLayout(dpsView, dpsParams);
    }

    @Override
    public void onDragEnd(DpsOverlayView source) {
        saveDpsPosition();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Live2D is a persistent display behavior: removing Axon's Activity task must not tear down
        // the accessibility renderer. Preserve only its controls; every other session overlay still
        // follows the original cleanup behavior.
        if (OverlayState.isLive2DEnabled(this) && Live2DModelStore.exists(this)) {
            OverlayState.endAppSessionPreservingLive2D(this);
            inputRuntimeConfig = InputRuntimeConfig.load(this);
            syncLive2DWindow(true);
            if (OverlayState.isLive2DMotionTrackingEnabled(this)) {
                Live2DMotionTrackingService.start(this);
            }
        } else {
            OverlayState.endAppSession(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (activeService == this) activeService = null;
        ShizukuBridge.removeListener(this);
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        stopMouseMonitor();
        stopTouchMonitor();
        stopGamepadMonitor();
        stopVader5UsbMonitor();
        stopDpsTicker();
        colorTickerRunning = false;
        mainHandler.removeCallbacks(colorTicker);
        if (sensitivityController != null) {
            sensitivityController.destroy();
            sensitivityController = null;
        }
        if (forceHoldController != null) {
            forceHoldController.destroy();
            forceHoldController = null;
        }
        if (gamepadKeyMapper != null) {
            gamepadKeyMapper.destroy();
            gamepadKeyMapper = null;
        }
        forceHoldVisualActive = false;
        forceHoldVisualKeyCode = -1;
        physicalKeyboardKeysDown.clear();
        forceHoldVirtualDeviceIds.clear();
        otherAxonVirtualDeviceIds.clear();
        savedStateRefreshScheduled.set(false);
        sensitivityRefreshScheduled.set(false);
        floatingMediaRefreshScheduled.set(false);
        mainHandler.removeCallbacksAndMessages(null);
        removeWindowImmediate(keyboardWindow);
        removeWindowImmediate(customWindow);
        removeWindowImmediate(mouseWindow);
        removeKeyboardCatImmediate();
        removeLive2DImmediate();
        if (floatingMediaController != null) {
            floatingMediaController.removeAll();
            floatingMediaController = null;
        }
        removeSuperCustomImmediate();
        removeTouchRegionEditorImmediate(false);
        removeTouchDisplayImmediate();
        removeKeyPromptImmediate();
        removeDpsImmediate();
        removeInputFullKeyboardImmediate();
        removeTrajectoryImmediate();
        removeGamepadWindowImmediate(leftStickWindow);
        removeGamepadWindowImmediate(rightStickWindow);
        removeGamepadWindowImmediate(faceWindow);
        removeGamepadWindowImmediate(leftShoulderWindow);
        removeGamepadWindowImmediate(rightShoulderWindow);
        removeGamepadWindowImmediate(backWindow);
        removeGamepadWindowImmediate(dpadWindow);
        super.onDestroy();
    }

    private boolean isPhysicalKeyboardEvent(KeyEvent event) {
        return InputBinding.isPhysicalKeyboardEvent(event);
    }

    private boolean isPhysicalGamepadEvent(KeyEvent event) {
        if (event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null || InputBinding.isAxonVirtualDevice(device)) return false;
        int sources = event.getSource();
        boolean gamepadSource = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        boolean mappedGamepadKey = InputBinding.fromGamepadEvent(event) >= 0;
        if (!gamepadSource && !mappedGamepadKey) return false;
        // 灵敏度代理可能以 Android 虚拟设备形式回送手柄语义。
        return !device.isVirtual() || inputRuntimeConfig.sensitivityEnabled;
    }

    /**
     * Correct vendor Android key aliases by correlating them with the authoritative evdev edge.
     * Dune Fox can expose physical L1 as KEYCODE_BUTTON_X on some Android stacks. The real X
     * also reports BUTTON_X, so a global X->L1 remap would break the face button. We only
     * override when the recent raw transition proves that L1 changed and X did not.
     */
    private int resolveGamepadAndroidBit(KeyEvent event, boolean pressed) {
        // Dune Fox hybrid firmwares may advertise normal BTN_TL but emit L1 on
        // legacy BTN_THUMB2 (scan 290). The native profile is capability-based,
        // so this remap does not touch a normal controller's X button.
        if (legacyThumb2AsL1Profile && event != null && event.getScanCode() == 290) {
            return GamepadOverlayView.BTN_L1;
        }
        int androidBit = GamepadButtons.fromAndroidEvent(event);
        if (androidBit != GamepadOverlayView.BTN_WEST) return androidBit;

        // Xbox/XInput compatibility on some controllers can expose the same physical LB edge
        // as a correct evdev BTN_TL plus a bogus Android BUTTON_X. Do not rely only on event
        // ordering: while raw input proves L1 is held and raw X is not held, the Android X
        // semantic is an alias. A real X still has its own BTN_WEST/BTN_C raw bit and therefore
        // is never rewritten by this guard.
        int xGroup = GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_C;
        boolean rawL1Held = (rawGamepadButtons & GamepadOverlayView.BTN_L1) != 0;
        boolean rawXHeld = (rawGamepadButtons & xGroup) != 0;
        if (rawL1Held && !rawXHeld) return GamepadOverlayView.BTN_L1;

        // Keep the short edge-correlation fallback for devices where Android delivers its
        // KeyEvent just before the evdev snapshot reaches the main thread.
        long age = SystemClock.uptimeMillis() - recentRawGamepadChangedAt;
        if (age < 0L || age > 220L) return androidBit;

        int changed = recentRawGamepadChangedMask;
        boolean l1Changed = (changed & GamepadOverlayView.BTN_L1) != 0;
        boolean xChanged = (changed & xGroup) != 0;
        if (!l1Changed || xChanged) return androidBit;

        boolean rawL1Pressed = (recentRawGamepadSnapshot & GamepadOverlayView.BTN_L1) != 0;
        if (rawL1Pressed != pressed) return androidBit;
        return GamepadOverlayView.BTN_L1;
    }

    private void applySavedState() {
        InputRuntimeConfig config = InputRuntimeConfig.load(this);
        inputRuntimeConfig = config;
        gamepadRuntimeConfig = GamepadRuntimeConfig.load(this);
        live2dMouseCaptureEnabled = OverlayState.isLive2DEnabled(this)
                && OverlayState.isLive2DMouseCaptureEnabled(this);
        globalHtmlActive = GlobalHtmlStore.isEnabled(this) && GlobalHtmlStore.exists(this);
        globalHtmlContent = globalHtmlActive ? GlobalHtmlStore.load(this) : "";
        if (globalHtmlContent.isEmpty()) globalHtmlActive = false;

        OverlayState.sanitizeHiddenDisplayHotkeyTargets(this);
        java.util.Set<Integer> hiddenDisplayTargets = OverlayState.getHiddenDisplayHotkeyTargets(this);
        syncWindow(keyboardWindow, config.keyboardEnabled
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_KEYBOARD));
        syncWindow(customWindow, config.customEnabled
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_CUSTOM));
        syncWindow(mouseWindow, config.mouseEnabled
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_MOUSE));
        syncKeyboardCatWindow(config.keyboardCatEnabled
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_KEYBOARD_CAT));
        syncLive2DWindow(OverlayState.isLive2DEnabled(this));
        if (floatingMediaController != null) {
            floatingMediaController.sync(config.superCustomEnabled, config.dragEnabled);
        }
        syncSuperCustomWindow(config.superCustomEnabled
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_SUPER_CUSTOM));
        reloadTouchRegionCache();
        boolean touchDisplayEnabled = TouchDisplayStore.isEnabled(this);
        if (!touchDisplayEnabled && touchRegionEditorAttached) {
            removeTouchRegionEditorImmediate(true);
        }
        syncTouchDisplayWindow(touchDisplayEnabled
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_TOUCH));
        syncInputFullKeyboardVisibility();
        syncKeyPromptWindow(config.keyPromptEnabled
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_KEY_PROMPT));
        int nextDpsTarget = config.dpsTargetKey;
        if (nextDpsTarget != activeDpsTargetKeyCode) {
            activeDpsTargetKeyCode = nextDpsTarget;
            dpsTracker.resetChannel(DpsTracker.TARGET);
        }
        syncDpsWindow(config.dpsEnabled
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_DPS));
        syncTrajectoryWindow(config.mouseTrajectoryEnabled
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_MOUSE_TRAJECTORY));
        syncGamepadWindow(leftStickWindow, OverlayState.isGamepadLeftStickEnabled(this)
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_GAMEPAD_LEFT_STICK));
        syncGamepadWindow(rightStickWindow, OverlayState.isGamepadRightStickEnabled(this)
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_GAMEPAD_RIGHT_STICK));
        syncGamepadWindow(faceWindow, OverlayState.isGamepadFaceEnabled(this)
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_GAMEPAD_FACE));
        syncGamepadWindow(dpadWindow, OverlayState.isGamepadDpadEnabled(this)
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_GAMEPAD_DPAD));
        syncGamepadWindow(leftShoulderWindow, OverlayState.isGamepadLeftShoulderEnabled(this)
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_GAMEPAD_LEFT_SHOULDER));
        syncGamepadWindow(rightShoulderWindow, OverlayState.isGamepadRightShoulderEnabled(this)
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_GAMEPAD_RIGHT_SHOULDER));
        syncGamepadWindow(backWindow, OverlayState.isGamepadBackEnabled(this)
                && !hiddenDisplayTargets.contains(OverlayState.HIDE_DISPLAY_GAMEPAD_BACK));
        syncVader5UsbMonitor();

        boolean forceHoldEnabled = config.forceHoldEnabled;
        int forceHoldTargetKey = config.forceHoldTargetKey;
        int forceHoldTargetScan = config.forceHoldTargetScanCode;
        if (forceHoldVisualActive
                && (!forceHoldEnabled || forceHoldVisualKeyCode != forceHoldTargetKey)) {
            clearForcedHoldVisual();
        }
        if (forceHoldController != null) {
            forceHoldController.applyConfiguration(forceHoldEnabled, forceHoldTargetKey, forceHoldTargetScan);
        }
        if (gamepadKeyMapper != null) {
            gamepadKeyMapper.applyMappings(GamepadMappingStore.load(this));
        }
        if (forceHoldVisualActive) {
            applyForcedHoldVisualState(forceHoldVisualKeyCode, true);
        }

        applyOverlayVisibility();
        refreshDpsTicker();

        applySensitivityState();
        applyTouchMonitorState();
    }

    private void applySensitivityState() {
        if (!RootBridge.isProbeComplete()) {
            RootBridge.ensureActivated(this, rootActive -> applySensitivityState());
            return;
        }
        boolean sensitivity = SensitivitySettingsStore.isEnabled(this);
        if (sensitivityController != null) {
            sensitivityController.apply(
                    sensitivity,
                    SensitivitySettingsStore.getMousePercent(this),
                    SensitivitySettingsStore.getGamepadPercent(this),
                    SensitivitySettingsStore.getMode(this));
        }
        if (sensitivity) {
            // 代理接管设备后关闭普通读取，避免重复读取输入。
            stopMouseMonitor();
            stopGamepadMonitor();
        } else {
            if (needsMouseMonitor()) startMouseMonitor();
            else stopMouseMonitor();
            if (needsGamepadMonitor()) startGamepadMonitor();
            else stopGamepadMonitor();
        }
    }

    private void applyOverlayVisibility() {
        // “隐藏后台”只影响最近任务卡片。
        // 悬浮层由各显示开关独立控制。
        // 应用进入后台后按键、CPS 和手柄显示继续工作。
    }

    private void syncInputFullKeyboardVisibility() {
        boolean show = inputRuntimeConfig.fullKeyboardEnabled && isInputMethodWindowVisible()
                && !OverlayState.isHideDisplayTargetHidden(
                        this, OverlayState.HIDE_DISPLAY_FULL_KEYBOARD);
        if (!show) {
            removeInputFullKeyboardImmediate();
            return;
        }
        ensureInputFullKeyboardWindow();
        if (inputFullKeyboardView != null) {
            inputFullKeyboardView.setKeyAppearance(
                    OverlayState.getKeyStyle(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                    OverlayState.getKeyPressColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setKeyBaseColor(
                    OverlayState.getKeyBaseColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setKeyBorderColor(
                    OverlayState.getKeyBorderColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setCornerStrength(
                    OverlayState.getKeyCornerStrength(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setLayerOpacities(
                    OverlayState.getKeyBackgroundOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                    OverlayState.getKeyStrokeOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                    OverlayState.getKeyTextOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setDiffusionOpacity(
                    OverlayState.getKeyDiffusionOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
        }
        updateInputFullKeyboardLayout();
    }

    private boolean isInputMethodWindowVisible() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void ensureInputFullKeyboardWindow() {
        if (inputFullKeyboardAttached || windowManager == null) return;
        inputFullKeyboardView = new FullKeyboardOverlayView(this);
        inputFullKeyboardView.setKeyAppearance(
                OverlayState.getKeyStyle(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                OverlayState.getKeyPressColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
        inputFullKeyboardView.setKeyBaseColor(
                OverlayState.getKeyBaseColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
        inputFullKeyboardView.setKeyBorderColor(
                OverlayState.getKeyBorderColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
        inputFullKeyboardView.setCornerStrength(
                OverlayState.getKeyCornerStrength(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
        inputFullKeyboardView.setLayerOpacities(
                OverlayState.getKeyBackgroundOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                OverlayState.getKeyStrokeOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                OverlayState.getKeyTextOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
        inputFullKeyboardView.setDiffusionOpacity(
                OverlayState.getKeyDiffusionOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
        inputFullKeyboardParams = new WindowManager.LayoutParams(
                fullKeyboardWidthPx(), fullKeyboardHeightPx(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                inputFullKeyboardFlags(), PixelFormat.TRANSLUCENT);
        inputFullKeyboardParams.gravity = Gravity.TOP | Gravity.START;
        inputFullKeyboardParams.setTitle("AxonInputFullKeyboard");
        applyInputFullKeyboardPosition();
        windowManager.addView(inputFullKeyboardView, inputFullKeyboardParams);
        inputFullKeyboardAttached = true;
    }

    private void updateInputFullKeyboardLayout() {
        if (!inputFullKeyboardAttached || inputFullKeyboardView == null
                || inputFullKeyboardParams == null || windowManager == null) return;
        inputFullKeyboardParams.width = fullKeyboardWidthPx();
        inputFullKeyboardParams.height = fullKeyboardHeightPx();
        inputFullKeyboardParams.flags = inputFullKeyboardFlags();
        applyInputFullKeyboardPosition();
        windowManager.updateViewLayout(inputFullKeyboardView, inputFullKeyboardParams);
    }

    private int inputFullKeyboardFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    }

    private int fullKeyboardWidthPx() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return Math.max(1, Math.min(metrics.widthPixels - dp(16), dp(FULL_KEYBOARD_MAX_WIDTH_DP)));
    }

    private int fullKeyboardHeightPx() {
        int target = Math.round(fullKeyboardWidthPx() * 0.38f);
        return Math.max(dp(FULL_KEYBOARD_MIN_HEIGHT_DP), Math.min(dp(FULL_KEYBOARD_MAX_HEIGHT_DP), target));
    }

    private void applyInputFullKeyboardPosition() {
        if (inputFullKeyboardParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        inputFullKeyboardParams.x = Math.max(0, (metrics.widthPixels - inputFullKeyboardParams.width) / 2);
        inputFullKeyboardParams.y = dp(12);
    }

    private void removeInputFullKeyboardImmediate() {
        if (!inputFullKeyboardAttached || windowManager == null || inputFullKeyboardView == null) {
            inputFullKeyboardAttached = false;
            inputFullKeyboardView = null;
            inputFullKeyboardParams = null;
            return;
        }
        inputFullKeyboardView.clearPressed();
        removeViewBestEffort(inputFullKeyboardView);
        inputFullKeyboardAttached = false;
        inputFullKeyboardView = null;
        inputFullKeyboardParams = null;
    }

    private void flashTextInput(AccessibilityEvent event) {
        if (event == null || inputFullKeyboardView == null) return;
        int added = Math.max(0, event.getAddedCount());
        int removed = Math.max(0, event.getRemovedCount());
        if (added == 0) {
            if (removed > 0) inputFullKeyboardView.flashKey(KeyEvent.KEYCODE_DEL);
            return;
        }
        List<CharSequence> textItems = event.getText();
        if (textItems == null || textItems.isEmpty() || textItems.get(0) == null) return;
        CharSequence text = textItems.get(0);
        int start = Math.max(0, Math.min(event.getFromIndex(), text.length()));
        int end = Math.max(start, Math.min(text.length(), start + added));
        for (int i = start; i < end; i++) {
            int keyCode = keyCodeForInputChar(text.charAt(i));
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) inputFullKeyboardView.flashKey(keyCode);
        }
    }

    private int keyCodeForInputChar(char value) {
        if (value >= 'a' && value <= 'z') return KeyEvent.KEYCODE_A + (value - 'a');
        if (value >= 'A' && value <= 'Z') return KeyEvent.KEYCODE_A + (value - 'A');
        if (value >= '0' && value <= '9') return KeyEvent.KEYCODE_0 + (value - '0');
        return switch (value) {
            case ' ' -> KeyEvent.KEYCODE_SPACE;
            case '\n', '\r' -> KeyEvent.KEYCODE_ENTER;
            case '`', '~' -> KeyEvent.KEYCODE_GRAVE;
            case '-', '_' -> KeyEvent.KEYCODE_MINUS;
            case '=', '+' -> KeyEvent.KEYCODE_EQUALS;
            case '[', '{' -> KeyEvent.KEYCODE_LEFT_BRACKET;
            case ']', '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET;
            case '\\', '|' -> KeyEvent.KEYCODE_BACKSLASH;
            case ';', ':' -> KeyEvent.KEYCODE_SEMICOLON;
            case '\'', '"' -> KeyEvent.KEYCODE_APOSTROPHE;
            case ',', '<' -> KeyEvent.KEYCODE_COMMA;
            case '.', '>' -> KeyEvent.KEYCODE_PERIOD;
            case '/', '?' -> KeyEvent.KEYCODE_SLASH;
            case '!' -> KeyEvent.KEYCODE_1;
            case '@' -> KeyEvent.KEYCODE_2;
            case '#' -> KeyEvent.KEYCODE_3;
            case '$' -> KeyEvent.KEYCODE_4;
            case '%' -> KeyEvent.KEYCODE_5;
            case '^' -> KeyEvent.KEYCODE_6;
            case '&' -> KeyEvent.KEYCODE_7;
            case '*' -> KeyEvent.KEYCODE_8;
            case '(' -> KeyEvent.KEYCODE_9;
            case ')' -> KeyEvent.KEYCODE_0;
            default -> KeyEvent.KEYCODE_UNKNOWN;
        };
    }

    private void syncWindow(DisplayWindow window, boolean enabled) {
        if (!enabled) {
            animateRemoveWindow(window);
            return;
        }
        window.removing = false;
        ensureWindow(window);
        if (window.view != null) window.view.animateIn();
        configureView(window);
        updateWindowLayout(window);
    }

    private void ensureWindow(DisplayWindow window) {
        if (window.attached || windowManager == null) return;

        KeyOverlayView view = new KeyOverlayView(this, window.type);
        view.setDragListener(this);
        window.view = view;
        configureView(window);

        window.params = new WindowManager.LayoutParams(
                displayWidthPx(window.type),
                displayHeightPx(window.type),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                windowFlags(),
                PixelFormat.TRANSLUCENT);
        window.params.gravity = Gravity.TOP | Gravity.START;
        window.params.setTitle(window.title);
        applyPositionToParams(window);

        windowManager.addView(view, window.params);
        window.attached = true;
        resetWindowPressedState(window);
    }

    private void configureView(DisplayWindow window) {
        if (window.view == null) return;
        window.view.setDragEnabled(inputRuntimeConfig.dragEnabled);
        window.view.setDisplaySize(displaySizePercent(window.type));
        window.view.setAlpha(1f);
        window.view.setLayerOpacities(
                OverlayState.getKeyBackgroundOpacity(this, window.type),
                OverlayState.getKeyStrokeOpacity(this, window.type),
                OverlayState.getKeyTextOpacity(this, window.type));
        window.view.setDiffusionOpacity(OverlayState.getKeyDiffusionOpacity(this, window.type));
        window.view.setAnimationMode(OverlayState.getMotionMode(this, window.type));
        window.view.setKeyAppearance(
                OverlayState.getKeyStyle(this, window.type),
                OverlayState.getKeyPressColor(this, window.type));
        window.view.setKeyBaseColor(OverlayState.getKeyBaseColor(this, window.type));
        window.view.setKeyBorderColor(OverlayState.getKeyBorderColor(this, window.type));
        window.view.setCornerStrength(OverlayState.getKeyCornerStrength(this, window.type));
        if (window.type == KeyOverlayView.DISPLAY_KEYBOARD) {
            window.view.setTextColor(OverlayState.getKeyboardTextColor(this));
            window.view.setKeySpacing(OverlayState.getKeyboardSpacing(this));
        } else if (window.type == KeyOverlayView.DISPLAY_CUSTOM) {
            window.view.setKeySpacing(OverlayState.getCustomSpacing(this));
        }
        if (window.type == KeyOverlayView.DISPLAY_KEYBOARD) {
            window.view.setKeyboardOptions(
                    OverlayState.isKeyboardSpaceEnabled(this),
                    OverlayState.isKeyboardSpaceDpsEnabled(this));
            window.view.setKeyboardMouseButtonsEnabled(OverlayState.isKeyboardMouseButtonsEnabled(this));
            window.view.setKeyboardMouseButtons(keyPromptMouseButtons);
            window.view.setKeyboardDps(dpsTracker.count(DpsTracker.SPACE, SystemClock.uptimeMillis()));
        }
        window.view.setGlobalHtmlRenderer(globalHtmlActive, globalHtmlContent);
        if (window.type == KeyOverlayView.DISPLAY_CUSTOM) {
            window.view.setCustomKeys(
                    OverlayState.getCustomKeyCodes(this),
                    OverlayState.getCustomColumns(this));
        }
    }

    private void updateWindowLayout(DisplayWindow window) {
        if (!window.attached || window.view == null || window.params == null || windowManager == null) return;
        window.params.width = displayWidthPx(window.type);
        window.params.height = displayHeightPx(window.type);
        window.params.flags = windowFlags();
        applyPositionToParams(window);
        windowManager.updateViewLayout(window.view, window.params);
    }

    private int renderBufferPercent(int requestedPercent) {
        return Math.max(MIN_OVERLAY_RENDER_BUFFER_PERCENT, requestedPercent);
    }

    private int windowFlags() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (!inputRuntimeConfig.dragEnabled) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return flags;
    }

    private int displayWidthPx(int type) {
        int base;
        if (type == KeyOverlayView.DISPLAY_MOUSE) base = MOUSE_WIDTH_DP;
        else if (type == KeyOverlayView.DISPLAY_CUSTOM) base = CUSTOM_WIDTH_DP;
        else base = KEYBOARD_WIDTH_DP;
        return Math.max(1, dp(base * renderBufferPercent(displaySizePercent(type)) / 100f));
    }

    private int displayHeightPx(int type) {
        int base;
        if (type == KeyOverlayView.DISPLAY_MOUSE) base = MOUSE_HEIGHT_DP;
        else if (type == KeyOverlayView.DISPLAY_CUSTOM) base = customBaseHeightDp();
        else {
            int spacingExtra = Math.max(0, OverlayState.getKeyboardSpacing(this) - 8);
            boolean showSpace = OverlayState.isKeyboardSpaceEnabled(this);
            boolean showMouseButtons = OverlayState.isKeyboardMouseButtonsEnabled(this);
            base = 128 + spacingExtra;
            if (showSpace || showMouseButtons) base += 52 + spacingExtra;
            if (showSpace && showMouseButtons) base += 52 + spacingExtra;
        }
        return Math.max(1, dp(base * renderBufferPercent(displaySizePercent(type)) / 100f));
    }

    private int displaySizePercent(int type) {
        if (type == KeyOverlayView.DISPLAY_MOUSE) return OverlayState.getMouseSize(this);
        if (type == KeyOverlayView.DISPLAY_CUSTOM) return OverlayState.getCustomSize(this);
        return OverlayState.getKeyboardSize(this);
    }

    private int customBaseHeightDp() {
        int count = OverlayState.getCustomKeyCodes(this).length;
        int columns = Math.max(1, OverlayState.getCustomColumns(this));
        int rows = Math.max(1, (count + columns - 1) / columns);
        return Math.max(CUSTOM_MIN_HEIGHT_DP, rows * (44 + OverlayState.getCustomSpacing(this)) + 4);
    }

    private void applyPositionToParams(DisplayWindow window) {
        if (window.params == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - window.params.width);
        int maxY = Math.max(0, metrics.heightPixels - window.params.height);
        window.params.x = Math.round(maxX * (OverlayState.getPositionX(this, window.type) / 100f));
        window.params.y = Math.round(maxY * (OverlayState.getPositionY(this, window.type) / 100f));
    }

    private void saveCurrentPosition(DisplayWindow window) {
        if (window.params == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - window.params.width);
        int maxY = Math.max(0, metrics.heightPixels - window.params.height);
        int x = maxX == 0 ? 0 : Math.round((window.params.x / (float) maxX) * 100f);
        int y = maxY == 0 ? 0 : Math.round((window.params.y / (float) maxY) * 100f);
        OverlayState.savePosition(this, window.type, x, y);
    }

    private void animateRemoveWindow(DisplayWindow window) {
        if (!window.attached || window.view == null) {
            removeWindowImmediate(window);
            return;
        }
        if (window.removing) return;
        window.removing = true;
        KeyOverlayView exitingView = window.view;
        exitingView.animateOut(() -> {
            if (window.removing && window.view == exitingView) {
                removeWindowImmediate(window);
            }
        });
    }

    private void removeWindowImmediate(DisplayWindow window) {
        window.removing = false;
        if (!window.attached || windowManager == null || window.view == null) {
            window.attached = false;
            window.view = null;
            window.params = null;
            return;
        }
        resetWindowPressedState(window);
        removeViewBestEffort(window.view);
        window.attached = false;
        window.view = null;
        window.params = null;
    }

    private DisplayWindow windowForView(KeyOverlayView source) {
        if (keyboardWindow.view == source) return keyboardWindow;
        if (customWindow.view == source) return customWindow;
        if (mouseWindow.view == source) return mouseWindow;
        return null;
    }

    private void syncLive2DWindow(boolean enabled) {
        /*
         * Live2D has one long-lived owner whenever AccessibilityService is connected.
         * Do NOT hand the renderer back and forth between MainActivity and the service:
         * rebuilding a WebView/moc3/texture stack during app transitions is both expensive and
         * unreliable on ROMs that reorder Activity and accessibility window callbacks.
         * TYPE_ACCESSIBILITY_OVERLAY is valid above Axon's own Activity as well as other apps, so
         * keeping this single renderer attached makes the app -> game transition a no-op.
         */
        if (!enabled || !Live2DModelStore.exists(this)) {
            live2dAttachRetryCount = 0;
            removeLive2DImmediate();
            return;
        }
        String version = Live2DModelStore.getVersion(this);
        if (live2dView != null && live2dAttached && version.equals(live2dView.getLoadedVersion())) {
            live2dView.setDisplayScalePercent(OverlayState.getLive2DSize(this));
            live2dView.setDisplayOffsetNormalized(
                    OverlayState.getLive2DOffsetX(this), OverlayState.getLive2DOffsetY(this));
            live2dView.setHideWatermark(OverlayState.isLive2DHideWatermarkEnabled(this));
            updateLive2DInteraction();
            return;
        }
        removeLive2DImmediate();
        ensureLive2DWindow();
    }

    private void ensureLive2DWindow() {
        if (live2dAttached || !Live2DModelStore.exists(this) || windowManager == null) return;
        Live2DOverlayView view = null;
        try {
            view = new Live2DOverlayView(this);
            view.setDisplayScalePercent(OverlayState.getLive2DSize(this));
            view.setDisplayOffsetNormalized(
                    OverlayState.getLive2DOffsetX(this), OverlayState.getLive2DOffsetY(this));
            // The full-screen renderer is always NOT_TOUCHABLE. Dragging uses a separate,
            // model-sized accessibility overlay created with public WindowManager APIs only.
            view.setDragListener(null);
            view.setDragEnabled(false);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    live2dWindowFlags(),
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = 0;
            params.setTitle("AxonInputLive2D");
            windowManager.addView(view, params);
            live2dView = view;
            live2dParams = params;
            live2dAttached = true;
            live2dAttachRetryCount = 0;
            updateLive2DInteraction();
            view.loadCurrentModel();
            // If MainActivity created its temporary fallback before AccessibilityService bound,
            // retire that fallback now. From here on the accessibility renderer is the sole owner.
            MainActivity.onExternalLive2DRendererReady();
        } catch (Throwable error) {
            Log.e(TAG, "Live2D overlay attach failed", error);
            if (view != null) {
                try { view.release(); } catch (Throwable ignored) {}
                try { windowManager.removeViewImmediate(view); } catch (Throwable ignored) {}
            }
            live2dView = null;
            live2dParams = null;
            live2dAttached = false;
            // Some ROMs reject an accessibility overlay during the exact window-transition frame.
            // Retry without waiting for another accessibility event; otherwise a single failed add
            // leaves Live2D invisible until the next unrelated state refresh.
            if (OverlayState.isLive2DEnabled(this) && Live2DModelStore.exists(this)
                    && live2dAttachRetryCount < 4) {
                final int attempt = ++live2dAttachRetryCount;
                final long delay = attempt == 1 ? 120L : attempt == 2 ? 280L
                        : attempt == 3 ? 520L : 900L;
                mainHandler.postDelayed(() -> {
                    if (!live2dAttached && OverlayState.isLive2DEnabled(this)
                            && Live2DModelStore.exists(this)) {
                        ensureLive2DWindow();
                    }
                }, delay);
            }
        }
    }

    private int live2dWindowFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    }

    private int live2dDragHandleFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    }

    private void updateLive2DInteraction() {
        if (!live2dAttached || live2dView == null || live2dParams == null || windowManager == null) {
            removeLive2DDragHandle();
            return;
        }
        // Never make the full-screen WebView touchable; otherwise it would swallow game input.
        live2dView.setDragEnabled(false);
        int nextFlags = live2dWindowFlags();
        if (live2dParams.flags != nextFlags) {
            live2dParams.flags = nextFlags;
            try { windowManager.updateViewLayout(live2dView, live2dParams); } catch (Throwable ignored) {}
        }
        if (inputRuntimeConfig.dragEnabled) ensureLive2DDragHandle();
        else removeLive2DDragHandle();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void ensureLive2DDragHandle() {
        if (!live2dAttached || live2dView == null || windowManager == null || !inputRuntimeConfig.dragEnabled) return;
        if (live2dDragHandle == null) {
            View handle = new View(this);
            handle.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            handle.setOnTouchListener((v, event) -> {
                if (event == null || live2dView == null || !inputRuntimeConfig.dragEnabled) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        live2dDragStartRawX = event.getRawX();
                        live2dDragStartRawY = event.getRawY();
                        live2dDragStartOffsetX = live2dView.getDisplayOffsetX();
                        live2dDragStartOffsetY = live2dView.getDisplayOffsetY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int sw = Math.max(1, getResources().getDisplayMetrics().widthPixels);
                        int sh = Math.max(1, getResources().getDisplayMetrics().heightPixels);
                        float nextX = live2dDragStartOffsetX + ((event.getRawX() - live2dDragStartRawX) * 2f / sw);
                        float nextY = live2dDragStartOffsetY + ((event.getRawY() - live2dDragStartRawY) * 2f / sh);
                        live2dView.setDisplayOffsetNormalized(nextX, nextY);
                        updateLive2DDragHandleLayout();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        OverlayState.saveLive2DOffset(
                                AxonInputAccessibilityService.this,
                                live2dView.getDisplayOffsetX(), live2dView.getDisplayOffsetY());
                        updateLive2DDragHandleLayout();
                        return true;
                    default:
                        return true;
                }
            });
            live2dDragHandle = handle;
        }
        if (live2dDragHandleParams == null) {
            live2dDragHandleParams = buildLive2DDragHandleParams();
            try { windowManager.addView(live2dDragHandle, live2dDragHandleParams); }
            catch (Throwable error) {
                Log.w(TAG, "Live2D drag handle attach failed", error);
                live2dDragHandleParams = null;
                return;
            }
        }
        updateLive2DDragHandleLayout();
    }

    private WindowManager.LayoutParams buildLive2DDragHandleParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                live2dDragHandleFlags(),
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("AxonInputLive2DDrag");
        applyLive2DDragHandleGeometry(params);
        return params;
    }

    private void applyLive2DDragHandleGeometry(WindowManager.LayoutParams params) {
        if (params == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.max(1, dm.widthPixels);
        int screenH = Math.max(1, dm.heightPixels);
        float scale = Math.max(0.5f, Math.min(2.0f, OverlayState.getLive2DSize(this) / 100f));
        int minSize = Math.max(96, Math.round(dm.density * 144f));
        int width = Math.max(minSize, Math.min(screenW, Math.round(screenW * 0.56f * scale)));
        int height = Math.max(minSize, Math.min(screenH, Math.round(screenH * 0.76f * scale)));
        float offsetX = live2dView == null ? OverlayState.getLive2DOffsetX(this) : live2dView.getDisplayOffsetX();
        float offsetY = live2dView == null ? OverlayState.getLive2DOffsetY(this) : live2dView.getDisplayOffsetY();
        int centerX = Math.round(screenW * (0.5f + offsetX * 0.5f));
        int centerY = Math.round(screenH * (0.5f + offsetY * 0.5f));
        params.width = width;
        params.height = height;
        params.x = Math.max(0, Math.min(screenW - width, centerX - width / 2));
        params.y = Math.max(0, Math.min(screenH - height, centerY - height / 2));
    }

    private void updateLive2DDragHandleLayout() {
        if (live2dDragHandle == null || live2dDragHandleParams == null || windowManager == null) return;
        applyLive2DDragHandleGeometry(live2dDragHandleParams);
        try { windowManager.updateViewLayout(live2dDragHandle, live2dDragHandleParams); } catch (Throwable ignored) {}
    }

    private void removeLive2DDragHandle() {
        View handle = live2dDragHandle;
        live2dDragHandle = null;
        live2dDragHandleParams = null;
        if (handle != null && windowManager != null) {
            try { windowManager.removeViewImmediate(handle); } catch (Throwable ignored) {}
        }
    }

    private void removeLive2DImmediate() {
        removeLive2DDragHandle();
        Live2DOverlayView view = live2dView;
        live2dView = null;
        live2dParams = null;
        boolean attached = live2dAttached;
        live2dAttached = false;
        if (view == null) return;
        try { view.release(); } catch (Throwable ignored) {}
        if (attached && windowManager != null) {
            try { windowManager.removeViewImmediate(view); } catch (Throwable ignored) {}
        }
    }

    private void syncKeyboardCatWindow(boolean enabled) {
        if (!enabled) {
            animateRemoveKeyboardCat();
            return;
        }
        keyboardCatRemoving = false;
        ensureKeyboardCatWindow();
        if (keyboardCatView != null) {
            keyboardCatView.setStyleId(OverlayState.getKeyboardCatStyleId(this));
            keyboardCatView.setMouseMode(OverlayState.isKeyboardCatMouseMode(this));
            keyboardCatView.setGlobalReverse(OverlayState.isKeyboardCatGlobalReverse(this));
            keyboardCatView.setDebugExpression(OverlayState.getKeyboardCatDebugExpression(this));
            pushCurrentGamepadToKeyboardCat(keyboardCatView);
            keyboardCatView.setDragEnabled(inputRuntimeConfig.dragEnabled);
            keyboardCatView.setAlpha(OverlayState.getDisplayOpacity(
                    this, KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT) / 100f);
            keyboardCatView.animateIn();
        }
        updateKeyboardCatLayout();
    }

    private void ensureKeyboardCatWindow() {
        if (keyboardCatAttached || windowManager == null) return;
        KeyboardCatOverlayView view = null;
        try {
            view = new KeyboardCatOverlayView(this);
            view.setStyleId(OverlayState.getKeyboardCatStyleId(this));
            view.setMouseMode(OverlayState.isKeyboardCatMouseMode(this));
            view.setDebugExpression(OverlayState.getKeyboardCatDebugExpression(this));
            view.setGlobalReverse(OverlayState.isKeyboardCatGlobalReverse(this));
            view.setDragListener(this);
            view.setDragEnabled(inputRuntimeConfig.dragEnabled);
            view.setAlpha(OverlayState.getDisplayOpacity(
                    this, KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT) / 100f);
            keyboardCatView = view;

            keyboardCatParams = new WindowManager.LayoutParams(
                    keyboardCatWidthPx(), keyboardCatHeightPx(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    windowFlags(), PixelFormat.TRANSLUCENT);
            keyboardCatParams.gravity = Gravity.TOP | Gravity.START;
            keyboardCatParams.setTitle("AxonInputKeyboardCat");
            applyKeyboardCatPosition();
            windowManager.addView(view, keyboardCatParams);
            keyboardCatAttached = true;
            keyboardCatAttachRetryCount = 0;
            view.clearInput();
            pushCurrentGamepadToKeyboardCat(view);
        } catch (Throwable error) {
            Log.e(TAG, "KeyboardCat overlay attach failed", error);
            if (view != null) view.release();
            keyboardCatView = null;
            keyboardCatParams = null;
            keyboardCatAttached = false;
            if (keyboardCatAttachRetryCount < 2 && OverlayState.isKeyboardCatEnabled(this)) {
                keyboardCatAttachRetryCount++;
                mainHandler.postDelayed(() -> {
                    if (OverlayState.isKeyboardCatEnabled(this) && !keyboardCatAttached) {
                        ensureKeyboardCatWindow();
                    }
                }, 350L * keyboardCatAttachRetryCount);
            }
        }
    }

    private void updateKeyboardCatLayout() {
        if (!keyboardCatAttached || keyboardCatView == null || keyboardCatParams == null
                || windowManager == null) return;
        keyboardCatParams.width = keyboardCatWidthPx();
        keyboardCatParams.height = keyboardCatHeightPx();
        keyboardCatParams.flags = windowFlags();
        keyboardCatView.setStyleId(OverlayState.getKeyboardCatStyleId(this));
        keyboardCatView.setMouseMode(OverlayState.isKeyboardCatMouseMode(this));
        keyboardCatView.setGlobalReverse(OverlayState.isKeyboardCatGlobalReverse(this));
        keyboardCatView.setDebugExpression(OverlayState.getKeyboardCatDebugExpression(this));
        pushCurrentGamepadToKeyboardCat(keyboardCatView);
        keyboardCatView.setDragEnabled(inputRuntimeConfig.dragEnabled);
        keyboardCatView.setAlpha(OverlayState.getDisplayOpacity(
                this, KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT) / 100f);
        applyKeyboardCatPosition();
        windowManager.updateViewLayout(keyboardCatView, keyboardCatParams);
    }

    private void pushCurrentGamepadToKeyboardCat(KeyboardCatOverlayView view) {
        if (view == null || !view.isGamepadStyle()) return;
        view.setGamepadState(gamepadLx, gamepadLy, gamepadRx, gamepadRy, gamepadLt, gamepadRt, gamepadButtons);
    }

    /**
     * 导入样式不再强制使用原版 360x208 的横向窗口。
     * 保持原版窗口面积基本不变，仅按样式自身画布比例重排宽高，避免方形/竖向模型
     * 被原版键盘猫的横向视口裁掉，同时避免因为画布分辨率更大而视觉尺寸突然放大。
     */
    private int[] keyboardCatBaseSizeDp() {
        BongoCatStyleManager.StyleInfo style = BongoCatStyleManager.selected(this);
        if (style == null || style.builtin) {
            return new int[]{KEYBOARD_CAT_WIDTH_DP, KEYBOARD_CAT_HEIGHT_DP};
        }

        float aspect = style.aspectRatio();
        if (!Float.isFinite(aspect) || aspect <= 0.05f || aspect >= 20f) {
            aspect = KEYBOARD_CAT_WIDTH_DP / (float) KEYBOARD_CAT_HEIGHT_DP;
        }

        float baseArea = KEYBOARD_CAT_WIDTH_DP * (float) KEYBOARD_CAT_HEIGHT_DP;
        int width = Math.max(1, Math.round((float) Math.sqrt(baseArea * aspect)));
        int height = Math.max(1, Math.round(width / aspect));
        return new int[]{width, height};
    }

    private int keyboardCatWidthPx() {
        int[] base = keyboardCatBaseSizeDp();
        return Math.max(1, dp(base[0] * OverlayState.getKeyboardCatSize(this) / 100f));
    }

    private int keyboardCatHeightPx() {
        int[] base = keyboardCatBaseSizeDp();
        return Math.max(1, dp(base[1] * OverlayState.getKeyboardCatSize(this) / 100f));
    }

    private void applyKeyboardCatPosition() {
        if (keyboardCatParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - keyboardCatParams.width);
        int maxY = Math.max(0, metrics.heightPixels - keyboardCatParams.height);
        keyboardCatParams.x = Math.round(maxX * (OverlayState.getPositionX(
                this, KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT) / 100f));
        keyboardCatParams.y = Math.round(maxY * (OverlayState.getPositionY(
                this, KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT) / 100f));
    }

    private void saveKeyboardCatPosition() {
        if (keyboardCatParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - keyboardCatParams.width);
        int maxY = Math.max(0, metrics.heightPixels - keyboardCatParams.height);
        int x = maxX == 0 ? 0 : Math.round((keyboardCatParams.x / (float) maxX) * 100f);
        int y = maxY == 0 ? 0 : Math.round((keyboardCatParams.y / (float) maxY) * 100f);
        OverlayState.savePosition(this, KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT, x, y);
    }

    private void animateRemoveKeyboardCat() {
        if (!keyboardCatAttached || keyboardCatView == null) {
            removeKeyboardCatImmediate();
            return;
        }
        if (keyboardCatRemoving) return;
        keyboardCatRemoving = true;
        KeyboardCatOverlayView exiting = keyboardCatView;
        exiting.animateOut(() -> {
            if (keyboardCatRemoving && keyboardCatView == exiting) {
                removeKeyboardCatImmediate();
            }
        });
    }

    private void removeKeyboardCatImmediate() {
        keyboardCatRemoving = false;
        if (!keyboardCatAttached || windowManager == null || keyboardCatView == null) {
            keyboardCatAttached = false;
            keyboardCatView = null;
            keyboardCatParams = null;
            return;
        }
        keyboardCatView.clearInput();
        keyboardCatView.release();
        removeViewBestEffort(keyboardCatView);
        keyboardCatAttached = false;
        keyboardCatView = null;
        keyboardCatParams = null;
    }

    private void syncGamepadWindow(GamepadWindow window, boolean enabled) {
        if (!enabled) {
            animateRemoveGamepadWindow(window);
            return;
        }
        window.removing = false;
        ensureGamepadWindow(window);
        configureGamepadView(window);
        updateGamepadWindowLayout(window);
        if (window.view != null) {
            window.view.setGamepadState(gamepadLx, gamepadLy, gamepadRx, gamepadRy, gamepadLt, gamepadRt, gamepadButtons);
            window.view.animateIn();
        }
    }

    private void ensureGamepadWindow(GamepadWindow window) {
        if (window.attached || windowManager == null) return;
        GamepadOverlayView view = new GamepadOverlayView(this, window.type);
        view.setDragListener(this);
        window.view = view;
        configureGamepadView(window);
        window.params = new WindowManager.LayoutParams(
                gamepadWidthPx(window.type), gamepadHeightPx(window.type),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                windowFlags(), PixelFormat.TRANSLUCENT);
        window.params.gravity = Gravity.TOP | Gravity.START;
        window.params.setTitle(window.title);
        applyGamepadPosition(window);
        windowManager.addView(view, window.params);
        window.attached = true;
        view.resetState();
    }

    private void configureGamepadView(GamepadWindow window) {
        if (window.view == null) return;
        window.view.setDragEnabled(inputRuntimeConfig.dragEnabled);
        window.view.setDisplaySize(OverlayState.getGamepadDisplaySize(this, window.type));
        window.view.setAlpha(OverlayState.getDisplayOpacity(this, window.type) / 100f);
        if (window.type == GamepadOverlayView.DISPLAY_FACE
                || window.type == GamepadOverlayView.DISPLAY_DPAD
                || window.type == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || window.type == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER
                || window.type == GamepadOverlayView.DISPLAY_BACK) {
            window.view.setKeyAppearance(
                    OverlayState.getKeyStyle(this, window.type),
                    OverlayState.getKeyPressColor(this, window.type));
            window.view.setKeyBaseColor(OverlayState.getKeyBaseColor(this, window.type));
            window.view.setKeyBorderColor(OverlayState.getKeyBorderColor(this, window.type));
            window.view.setCornerStrength(OverlayState.getKeyCornerStrength(this, window.type));
        }
        window.view.setGlobalHtmlRenderer(globalHtmlActive, globalHtmlContent);
        if (window.type == GamepadOverlayView.DISPLAY_LEFT_STICK) {
            window.view.setStickShape(OverlayState.getGamepadLeftStickShape(this));
            window.view.setStickDotSize(OverlayState.getGamepadStickDotSize(this, window.type));
        } else if (window.type == GamepadOverlayView.DISPLAY_RIGHT_STICK) {
            window.view.setStickShape(OverlayState.getGamepadRightStickShape(this));
            window.view.setStickDotSize(OverlayState.getGamepadStickDotSize(this, window.type));
        } else if (window.type == GamepadOverlayView.DISPLAY_FACE) {
            window.view.setFaceSpacing(OverlayState.getGamepadFaceSpacing(this));
            window.view.setFaceReversed(OverlayState.isGamepadFaceReversed(this));
            window.view.setFaceSymbolIcons(OverlayState.isGamepadFaceSymbolIcons(this));
            window.view.setFaceDpsVisibility(
                    GamepadSettingsStore.isFaceYDpsEnabled(this),
                    GamepadSettingsStore.isFaceXDpsEnabled(this),
                    GamepadSettingsStore.isFaceBDpsEnabled(this),
                    GamepadSettingsStore.isFaceADpsEnabled(this));
        } else if (window.type == GamepadOverlayView.DISPLAY_LEFT_SHOULDER) {
            window.view.setShoulderOptions(
                    GamepadSettingsStore.isL2ProgressEnabled(this),
                    GamepadSettingsStore.isL1DpsEnabled(this));
        } else if (window.type == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER) {
            window.view.setShoulderOptions(
                    GamepadSettingsStore.isR2ProgressEnabled(this),
                    GamepadSettingsStore.isR1DpsEnabled(this));
        } else if (window.type == GamepadOverlayView.DISPLAY_BACK) {
            window.view.setVader5BackLabels(vader5ProConnected);
        }
        pushDpsToView(window.view, SystemClock.uptimeMillis());
    }

    private int gamepadWidthPx(int type) {
        int base = (type == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || type == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER)
                ? GAMEPAD_SHOULDER_WIDTH_DP
                : (type == GamepadOverlayView.DISPLAY_BACK ? GAMEPAD_BACK_WIDTH_DP
                : (type == GamepadOverlayView.DISPLAY_DPAD ? GAMEPAD_DPAD_SIZE_DP
                : (type == GamepadOverlayView.DISPLAY_FACE ? GAMEPAD_FACE_SIZE_DP : GAMEPAD_STICK_SIZE_DP)));
        return Math.max(1, dp(base * renderBufferPercent(OverlayState.getGamepadDisplaySize(this, type)) / 100f));
    }

    private int gamepadHeightPx(int type) {
        int base = (type == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || type == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER)
                ? GAMEPAD_SHOULDER_HEIGHT_DP
                : (type == GamepadOverlayView.DISPLAY_BACK ? GAMEPAD_BACK_HEIGHT_DP
                : (type == GamepadOverlayView.DISPLAY_DPAD ? GAMEPAD_DPAD_SIZE_DP
                : (type == GamepadOverlayView.DISPLAY_FACE ? GAMEPAD_FACE_SIZE_DP : GAMEPAD_STICK_SIZE_DP)));
        return Math.max(1, dp(base * renderBufferPercent(OverlayState.getGamepadDisplaySize(this, type)) / 100f));
    }

    private void updateGamepadWindowLayout(GamepadWindow window) {
        if (!window.attached || window.view == null || window.params == null || windowManager == null) return;
        window.params.width = gamepadWidthPx(window.type);
        window.params.height = gamepadHeightPx(window.type);
        window.params.flags = windowFlags();
        configureGamepadView(window);
        applyGamepadPosition(window);
        windowManager.updateViewLayout(window.view, window.params);
    }

    private void applyGamepadPosition(GamepadWindow window) {
        if (window.params == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - window.params.width);
        int maxY = Math.max(0, metrics.heightPixels - window.params.height);
        window.params.x = Math.round(maxX * (OverlayState.getPositionX(this, window.type) / 100f));
        window.params.y = Math.round(maxY * (OverlayState.getPositionY(this, window.type) / 100f));
    }

    private void saveGamepadPosition(GamepadWindow window) {
        if (window.params == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - window.params.width);
        int maxY = Math.max(0, metrics.heightPixels - window.params.height);
        int x = maxX == 0 ? 0 : Math.round((window.params.x / (float) maxX) * 100f);
        int y = maxY == 0 ? 0 : Math.round((window.params.y / (float) maxY) * 100f);
        OverlayState.savePosition(this, window.type, x, y);
    }

    private GamepadWindow gamepadWindowForView(GamepadOverlayView source) {
        if (leftStickWindow.view == source) return leftStickWindow;
        if (rightStickWindow.view == source) return rightStickWindow;
        if (faceWindow.view == source) return faceWindow;
        if (leftShoulderWindow.view == source) return leftShoulderWindow;
        if (rightShoulderWindow.view == source) return rightShoulderWindow;
        if (backWindow.view == source) return backWindow;
        if (dpadWindow.view == source) return dpadWindow;
        return null;
    }

    private void animateRemoveGamepadWindow(GamepadWindow window) {
        if (!window.attached || window.view == null) {
            removeGamepadWindowImmediate(window);
            return;
        }
        if (window.removing) return;
        window.removing = true;
        GamepadOverlayView exiting = window.view;
        exiting.animateOut(() -> {
            if (window.removing && window.view == exiting) removeGamepadWindowImmediate(window);
        });
    }

    private void removeGamepadWindowImmediate(GamepadWindow window) {
        window.removing = false;
        if (!window.attached || windowManager == null || window.view == null) {
            window.attached = false;
            window.view = null;
            window.params = null;
            return;
        }
        window.view.resetState();
        removeViewBestEffort(window.view);
        window.attached = false;
        window.view = null;
        window.params = null;
    }

    private int mergeGamepadButtons(int rawButtons) {
        int mode = gamepadRuntimeConfig.compatibilityMode;
        int triggerMask = GamepadOverlayView.BTN_L2 | GamepadOverlayView.BTN_R2;
        int merged;

        if (mode == GamepadSettingsStore.COMPAT_LOOSE) {
            // 宽松模式保留两路按键。适合单一路径缺键的设备。
            merged = rawButtons | androidGamepadButtons;
            return suppressXboxShoulderAlias(rawButtons, merged);
        }
        if (mode == GamepadSettingsStore.COMPAT_EVDEV) {
            // 底层模式不使用 Android 的按键语义。
            return rawButtons;
        }

        // 自动和 Android 优先模式使用 Android 已识别的按键覆盖对应底层位。
        int semanticMask = androidGamepadKnownMask & ~triggerMask;
        merged = (rawButtons & ~semanticMask) | (androidGamepadButtons & semanticMask);
        // 扳机同时接受数字键和模拟轴对应的底层状态。
        merged = (merged & ~triggerMask)
                | ((rawButtons | androidGamepadButtons) & triggerMask);

        // Android 已确认某个 ABXY 正在按下时，清掉底层同组的冲突位。
        // 只处理当前按下状态，不永久屏蔽另一颗按键。
        if ((androidGamepadButtons & GamepadOverlayView.BTN_WEST) != 0) {
            merged &= ~GamepadOverlayView.BTN_NORTH;
        } else if ((androidGamepadButtons & GamepadOverlayView.BTN_NORTH) != 0) {
            merged &= ~(GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_C);
        }
        if ((androidGamepadButtons & GamepadOverlayView.BTN_SOUTH) != 0) {
            merged &= ~(GamepadOverlayView.BTN_EAST | GamepadOverlayView.BTN_Z);
        } else if ((androidGamepadButtons & GamepadOverlayView.BTN_EAST) != 0) {
            merged &= ~GamepadOverlayView.BTN_SOUTH;
        }
        merged |= androidGamepadButtons;

        if (mode == GamepadSettingsStore.COMPAT_ANDROID) {
            int known = androidGamepadKnownMask;
            merged = (rawButtons & ~known) | (androidGamepadButtons & known);
        }
        return suppressXboxShoulderAlias(rawButtons, merged);
    }

    /**
     * Some Xbox/XInput compatibility stacks duplicate physical LB as Android BUTTON_X.
     * evdev is authoritative for this ambiguous pair: if it says L1 is down and no physical
     * X bit is down, remove only the X alias. Real X remains untouched because BTN_WEST/BTN_C
     * is present in rawButtons whenever the physical X button is actually held.
     */
    private int suppressXboxShoulderAlias(int rawButtons, int mergedButtons) {
        int xGroup = GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_C;
        boolean rawL1Held = (rawButtons & GamepadOverlayView.BTN_L1) != 0;
        boolean rawXHeld = (rawButtons & xGroup) != 0;
        if (rawL1Held && !rawXHeld) {
            return mergedButtons & ~xGroup;
        }
        return mergedButtons;
    }

    private int swapGamepadButtonGroups(int buttons, int firstMask, int secondMask) {
        boolean first = (buttons & firstMask) != 0;
        boolean second = (buttons & secondMask) != 0;
        int result = buttons & ~(firstMask | secondMask);
        if (first) result |= canonicalGamepadBit(secondMask);
        if (second) result |= canonicalGamepadBit(firstMask);
        return result;
    }

    private int canonicalGamepadBit(int mask) {
        if ((mask & GamepadOverlayView.BTN_WEST) != 0 || (mask & GamepadOverlayView.BTN_C) != 0) {
            return GamepadOverlayView.BTN_WEST;
        }
        if ((mask & GamepadOverlayView.BTN_EAST) != 0 || (mask & GamepadOverlayView.BTN_Z) != 0) {
            return GamepadOverlayView.BTN_EAST;
        }
        return Integer.lowestOneBit(mask);
    }

    private void applyGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        int rawChanged = rawGamepadButtons ^ buttons;
        if (rawChanged != 0) {
            recentRawGamepadChangedMask = rawChanged;
            recentRawGamepadSnapshot = buttons;
            recentRawGamepadChangedAt = SystemClock.uptimeMillis();

            // Ordering differs between OEMs: some deliver the bad Android BUTTON_X event
            // before the evdev L1 edge. Once raw input proves this was L1 (and no physical
            // X is down), discard the false Android X state and its semantic override.
            int xGroup = GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_C;
            if ((rawChanged & GamepadOverlayView.BTN_L1) != 0
                    && (buttons & xGroup) == 0
                    && (androidGamepadButtons & xGroup) != 0) {
                androidGamepadButtons &= ~xGroup;
                androidGamepadKnownMask &= ~xGroup;
            }
        }
        rawGamepadButtons = buttons;
        int effectiveButtons = mergeGamepadButtons(buttons | vader5UsbBackButtons);

        InputRuntimeConfig config = inputRuntimeConfig;
        if (gamepadRuntimeConfig.swapXY) {
            effectiveButtons = swapGamepadButtonGroups(
                    effectiveButtons,
                    GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_C,
                    GamepadOverlayView.BTN_NORTH);
        }
        if (gamepadRuntimeConfig.swapAB) {
            effectiveButtons = swapGamepadButtonGroups(
                    effectiveButtons,
                    GamepadOverlayView.BTN_SOUTH,
                    GamepadOverlayView.BTN_EAST | GamepadOverlayView.BTN_Z);
        }
        if (gamepadRuntimeConfig.customSwapEnabled) {
            int first = gamepadRuntimeConfig.customSwapFirst;
            int second = gamepadRuntimeConfig.customSwapSecond;
            if (first != 0 && second != 0 && first != second) {
                effectiveButtons = swapGamepadButtonGroups(effectiveButtons, first, second);
            }
        }

        if (gamepadRuntimeConfig.swapSticks) {
            int tx = lx;
            int ty = ly;
            lx = rx;
            ly = ry;
            rx = tx;
            ry = ty;
        }
        if (gamepadRuntimeConfig.swapTriggers) {
            int t = lt;
            lt = rt;
            rt = t;
            effectiveButtons = swapGamepadButtonGroups(
                    effectiveButtons, GamepadOverlayView.BTN_L2, GamepadOverlayView.BTN_R2);
        }

        long now = SystemClock.uptimeMillis();
        // 模拟扳机超过一半行程时按一次按键处理，供 CPS 绑定和统计。
        int cpsButtons = effectiveButtons;
        if (lt >= 500) cpsButtons |= GamepadOverlayView.BTN_L2;
        if (rt >= 500) cpsButtons |= GamepadOverlayView.BTN_R2;
        int previousBindingButtons = previousGamepadButtonsForBindings;
        dispatchGamepadBindingTransitions(previousBindingButtons, cpsButtons, now);
        previousGamepadButtonsForBindings = cpsButtons;

        int previousButtons = previousGamepadButtonsForDps;
        updateCpsGamepadTarget(previousButtons, cpsButtons, now);
        recordGamepadDpsTransitions(previousButtons, cpsButtons, now);
        previousGamepadButtonsForDps = cpsButtons;
        gamepadLx = lx; gamepadLy = ly; gamepadRx = rx; gamepadRy = ry;
        gamepadLt = lt; gamepadRt = rt; gamepadButtons = effectiveButtons;
        pushGamepadState(leftStickWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(rightStickWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(faceWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(dpadWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(leftShoulderWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(rightShoulderWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(backWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        if (keyboardCatView != null && inputRuntimeConfig.keyboardCatEnabled && keyboardCatView.isGamepadStyle()) {
            keyboardCatView.setGamepadState(lx, ly, rx, ry, lt, rt, effectiveButtons);
        }
        if (effectiveButtons != previousButtons && needsDpsTicker()) pushDpsToViews(now);
    }

    private void dispatchGamepadBindingTransitions(int previous, int current, long now) {
        int changed = previous ^ current;
        while (changed != 0) {
            int bit = Integer.lowestOneBit(changed);
            changed &= ~bit;
            boolean pressed = (current & bit) != 0;
            int inputCode = InputBinding.gamepad(bit);
            if (!gamepadKeyMapperHasMapping(inputCode)) {
                handleBoundInputEvent(inputCode, pressed, pressed, now);
            }
            if (superCustomView != null && inputRuntimeConfig.superCustomEnabled) {
                superCustomView.setInputPressed(inputCode, pressed);
            }
        }
    }

    private void pushGamepadState(GamepadWindow window, int lx, int ly, int rx, int ry,
                                  int lt, int rt, int buttons) {
        if (window.view != null) window.view.setGamepadState(lx, ly, rx, ry, lt, rt, buttons);
    }

    private void syncSuperCustomWindow(boolean enabled) {
        if (!enabled) {
            removeSuperCustomImmediate();
            return;
        }
        List<SuperCustomControlSpec> specs = SuperCustomConfigStore.loadActive(this);
        if (specs.isEmpty()) {
            removeSuperCustomImmediate();
            return;
        }
        ensureSuperCustomWindow();
        if (superCustomView != null) superCustomView.setSpecs(specs);
    }

    private void ensureSuperCustomWindow() {
        if (superCustomAttached || windowManager == null) return;
        SuperCustomOverlayView view = new SuperCustomOverlayView(this);
        superCustomView = view;
        superCustomParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        superCustomParams.gravity = Gravity.TOP | Gravity.START;
        superCustomParams.setTitle("AxonInputSuperCustom");
        try {
            windowManager.addView(view, superCustomParams);
            superCustomAttached = true;
        } catch (RuntimeException error) {
            Log.e(TAG, "SuperCustom overlay attach failed", error);
            superCustomView = null;
            superCustomParams = null;
            superCustomAttached = false;
        }
    }

    private void removeSuperCustomImmediate() {
        if (superCustomView != null) superCustomView.clearPressed();
        if (superCustomAttached && superCustomView != null) removeViewBestEffort(superCustomView);
        superCustomAttached = false;
        superCustomView = null;
        superCustomParams = null;
    }

    private void showTouchRegionEditorInternal() {
        if (touchRegionEditorAttached || windowManager == null) return;
        if (!TouchDisplayStore.isEnabled(this)) {
            pendingTouchRegionEditorRequest = false;
            return;
        }
        // Region editing is meaningful only against the actual landscape target app.
        // Keep the request pending while portrait; onConfigurationChanged will attach it after rotation.
        if (!isActualLandscape()) {
            pendingTouchRegionEditorRequest = true;
            Toast.makeText(this, "请切换到横屏，四个触屏区域会自动显示", Toast.LENGTH_SHORT).show();
            return;
        }
        String foregroundPackage = currentForegroundPackage();
        if (foregroundPackage.isEmpty()) foregroundPackage = lastForegroundPackage;
        if (shouldDeferTouchEditorForPackage(foregroundPackage)) {
            pendingTouchRegionEditorRequest = true;
            return;
        }
        // Keep the evdev monitor alive for diagnostic touch crosses, but hide the runtime key display
        // while the editor is intercepting gestures.
        removeTouchDisplayImmediate();
        TouchRegionOverlayEditorView view = new TouchRegionOverlayEditorView(this, save -> {
            pendingTouchRegionEditorRequest = false;
            removeTouchRegionEditorImmediate(false);
            applySavedState();
        });
        touchRegionEditorView = view;
        touchRegionEditorParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        touchRegionEditorParams.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            touchRegionEditorParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        touchRegionEditorParams.setTitle("AxonInputTouchRegionEditor");
        try {
            windowManager.addView(view, touchRegionEditorParams);
            touchRegionEditorAttached = true;
            // The request is no longer pending once the single editor window is attached.
            pendingTouchRegionEditorRequest = false;
            applyTouchMonitorState();
        } catch (RuntimeException error) {
            Log.e(TAG, "Touch region overlay editor attach failed", error);
            touchRegionEditorView = null;
            touchRegionEditorParams = null;
            touchRegionEditorAttached = false;
        }
    }

    private void updateTouchRegionEditorLayoutInPlace() {
        if (!touchRegionEditorAttached || touchRegionEditorView == null
                || touchRegionEditorParams == null || windowManager == null) return;
        try {
            // MATCH_PARENT normally follows display geometry automatically. updateViewLayout is
            // intentionally idempotent here and keeps the same View/Surface alive.
            windowManager.updateViewLayout(touchRegionEditorView, touchRegionEditorParams);
            touchRegionEditorView.requestLayout();
        } catch (Throwable error) {
            Log.w(TAG, "Touch region overlay in-place layout update failed", error);
        }
    }

    private void removeTouchRegionEditorImmediate(boolean clearPending) {
        mainHandler.removeCallbacks(showTouchRegionEditorRunnable);
        TouchRegionOverlayEditorView view = touchRegionEditorView;
        touchRegionEditorAttached = false;
        touchRegionEditorView = null;
        touchRegionEditorParams = null;
        if (clearPending) pendingTouchRegionEditorRequest = false;
        if (view != null && windowManager != null) {
            try { windowManager.removeViewImmediate(view); } catch (Throwable ignored) {}
        }
    }

    private void handleTouchEditorForegroundChanged(String packageName) {
        if (!TouchDisplayStore.isEnabled(this)) return;
        // Once attached, keep the editor window stable.  Accessibility overlays generate their own
        // window events, so using those events to remove the same overlay causes an add/remove loop.
        if (touchRegionEditorAttached) return;
        boolean defer = shouldDeferTouchEditorForPackage(packageName);
        if (pendingTouchRegionEditorRequest && !defer) {
            mainHandler.removeCallbacks(showTouchRegionEditorRunnable);
            mainHandler.postDelayed(showTouchRegionEditorRunnable, 80L);
        }
    }

    private boolean shouldDeferTouchEditorForPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        if (getPackageName().equals(packageName)) return true;
        if (packageName.equals(homePackage)) return true;
        return "com.android.systemui".equals(packageName);
    }

    private String resolveHomePackage() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = getPackageManager().resolveActivity(intent, 0);
            if (info != null && info.activityInfo != null && info.activityInfo.packageName != null) {
                return info.activityInfo.packageName;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String currentForegroundPackage() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence packageName = root.getPackageName();
                if (packageName != null) return packageName.toString();
            }
        } catch (Throwable ignored) {
        }
        return lastForegroundPackage == null ? "" : lastForegroundPackage;
    }

    private boolean isTouchCaptureAllowed() {
        return TouchDisplayStore.isEnabled(this) && isActualLandscape();
    }

    private boolean isTouchRuntimeAllowed() {
        return isTouchCaptureAllowed()
                && !touchRegionEditorAttached;
    }

    private boolean isActualLandscape() {
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            try {
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return metrics.widthPixels > metrics.heightPixels;
                }
            } catch (Throwable ignored) {
            }
        }
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void syncTouchDisplayWindow(boolean enabled) {
        if (!enabled || !isTouchRuntimeAllowed()) {
            removeTouchDisplayImmediate();
            return;
        }
        ensureTouchDisplayWindow();
        configureTouchDisplayView();
        updateTouchDisplayLayout();
    }

    private void ensureTouchDisplayWindow() {
        if (touchDisplayAttached || windowManager == null) return;
        NativeKeyCanvasView view = new NativeKeyCanvasView(this, NativeKeyCanvasView.DISPLAY_TOUCH);
        touchDisplayView = view;
        configureTouchDisplayView();
        touchDisplayParams = new WindowManager.LayoutParams(
                touchDisplayWidthPx(), touchDisplayHeightPx(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                windowFlags() | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        touchDisplayParams.gravity = Gravity.TOP | Gravity.START;
        touchDisplayParams.setTitle("AxonInputTouchDisplay");
        applyTouchDisplayPosition();
        try {
            windowManager.addView(view, touchDisplayParams);
            touchDisplayAttached = true;
            view.setPressedMask(touchDisplayPressedMask);
        } catch (RuntimeException error) {
            Log.e(TAG, "Touch display overlay attach failed", error);
            touchDisplayAttached = false;
            touchDisplayView = null;
            touchDisplayParams = null;
        }
    }

    private void configureTouchDisplayView() {
        NativeKeyCanvasView view = touchDisplayView;
        if (view == null) return;
        int keyboardType = KeyOverlayView.DISPLAY_KEYBOARD;
        // Touch-display is visual-only. Region input is read from the Shizuku evdev monitor, so the overlay must
        // never consume game touches even when the global overlay drag switch is enabled.
        view.setDragEnabled(false);
        view.setDisplaySize(OverlayState.getKeyboardSize(this));
        view.setLayerOpacities(
                OverlayState.getKeyBackgroundOpacity(this, keyboardType),
                OverlayState.getKeyStrokeOpacity(this, keyboardType),
                OverlayState.getKeyTextOpacity(this, keyboardType));
        view.setDiffusionOpacity(OverlayState.getKeyDiffusionOpacity(this, keyboardType));
        view.setAnimationMode(OverlayState.getMotionMode(this, keyboardType));
        view.setKeyAppearance(
                OverlayState.getKeyStyle(this, keyboardType),
                OverlayState.getKeyPressColor(this, keyboardType));
        view.setKeyBaseColor(OverlayState.getKeyBaseColor(this, keyboardType));
        view.setKeyBorderColor(OverlayState.getKeyBorderColor(this, keyboardType));
        view.setCornerStrength(OverlayState.getKeyCornerStrength(this, keyboardType));
        view.setTextColor(OverlayState.getKeyboardTextColor(this));
        view.setKeySpacing(OverlayState.getKeyboardSpacing(this));
        view.setMouseButtonsVisible(OverlayState.isKeyboardMouseButtonsEnabled(this));
        view.setPressedMask(touchDisplayPressedMask);
    }

    private void updateTouchDisplayLayout() {
        if (!touchDisplayAttached || touchDisplayView == null || touchDisplayParams == null
                || windowManager == null) return;
        touchDisplayParams.width = touchDisplayWidthPx();
        touchDisplayParams.height = touchDisplayHeightPx();
        touchDisplayParams.flags = windowFlags() | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        applyTouchDisplayPosition();
        try {
            windowManager.updateViewLayout(touchDisplayView, touchDisplayParams);
        } catch (RuntimeException ignored) {
        }
    }

    private int touchDisplayWidthPx() {
        return Math.max(1, dp(TOUCH_DISPLAY_WIDTH_DP * OverlayState.getKeyboardSize(this) / 100f));
    }

    private int touchDisplayHeightPx() {
        int spacing = OverlayState.getKeyboardSpacing(this);
        int spacingDelta = Math.max(-8, spacing - 8);
        int base = OverlayState.isKeyboardMouseButtonsEnabled(this)
                ? TOUCH_DISPLAY_HEIGHT_DP + spacingDelta * 3
                : 168 + spacingDelta * 2;
        return Math.max(1, dp(base * OverlayState.getKeyboardSize(this) / 100f));
    }

    @SuppressWarnings("deprecation")
    private void applyTouchDisplayPosition() {
        if (touchDisplayParams == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
            }
        } catch (Throwable ignored) {
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            DisplayMetrics fallback = getResources().getDisplayMetrics();
            metrics.widthPixels = fallback.widthPixels;
            metrics.heightPixels = fallback.heightPixels;
        }
        int maxX = Math.max(0, metrics.widthPixels - touchDisplayParams.width);
        int maxY = Math.max(0, metrics.heightPixels - touchDisplayParams.height);
        touchDisplayParams.x = Math.round(maxX * (TouchDisplayStore.getPositionX(this) / 100f));
        touchDisplayParams.y = Math.round(maxY * (TouchDisplayStore.getPositionY(this) / 100f));
    }

    private void removeTouchDisplayImmediate() {
        touchDisplayPressedMask = 0;
        touchRoles.clear();
        if (touchDisplayView != null) touchDisplayView.releaseAll();
        if (touchDisplayAttached && touchDisplayView != null) removeViewBestEffort(touchDisplayView);
        touchDisplayAttached = false;
        touchDisplayView = null;
        touchDisplayParams = null;
    }

    private void applyTouchMonitorState() {
        // Keep the read-only evdev monitor alive while the region editor is open. The editor uses
        // these frames to show the actual mapped touch point, while runtime key presses stay muted.
        if (isTouchCaptureAllowed()) startTouchMonitor();
        else stopTouchMonitor();
    }

    private void startTouchMonitor() {
        if (!isTouchCaptureAllowed()) return;
        // Start the worker immediately. TouchInputMonitor itself waits for Shizuku permission and
        // reconnects when the Shizuku binder comes back. Never gate touch capture on Root probing.
        if (touchMonitor == null) touchMonitor = new TouchInputMonitor(this, this);
        touchMonitor.start();
        touchMonitorActive = true;
    }

    private void stopTouchMonitor() {
        if (touchMonitor != null) touchMonitor.stop();
        touchMonitorActive = false;
        lastTouchFrameStatus = "未收到触点";
        synchronized (touchFrameLock) {
            pendingTouchFrame = new TouchInputMonitor.TouchPoint[0];
            touchFramePosted = false;
        }
        lastTouchMappedPoints = new float[0];
        clearTouchDisplayPressedState();
    }

    @Override
    public void onTouchStatus(String status) {
        lastTouchMonitorStatus = status == null || status.trim().isEmpty() ? "未知" : status.trim();
        Log.i(TAG, "Touch monitor: " + lastTouchMonitorStatus);
    }

    @Override
    public void onTouchFrame(TouchInputMonitor.TouchPoint[] points) {
        if (points == null) points = new TouchInputMonitor.TouchPoint[0];
        synchronized (touchFrameLock) {
            pendingTouchFrame = points;
            if (touchFramePosted) return;
            touchFramePosted = true;
        }
        mainHandler.post(this::consumeTouchFrame);
    }

    private void consumeTouchFrame() {
        TouchInputMonitor.TouchPoint[] points;
        synchronized (touchFrameLock) {
            points = pendingTouchFrame;
            touchFramePosted = false;
        }
        applyTouchFrame(points);
    }

    private void applyTouchFrame(TouchInputMonitor.TouchPoint[] points) {
        if (!isTouchCaptureAllowed()) {
            lastTouchMappedPoints = new float[0];
            touchFramePoints.clear();
            clearTouchDisplayPressedState();
            return;
        }

        int rotation = windowManager == null || windowManager.getDefaultDisplay() == null
                ? Surface.ROTATION_0 : windowManager.getDefaultDisplay().getRotation();
        int generation = ++touchFrameGeneration;
        if (generation == Integer.MAX_VALUE) {
            touchFrameGeneration = generation = 1;
            for (TouchFramePoint point : touchFramePoints.values()) point.generation = 0;
        }

        if (points != null) {
            for (TouchInputMonitor.TouchPoint source : points) {
                if (source == null) continue;
                TouchFramePoint target = touchFramePoints.get(source.id);
                if (target == null) {
                    target = new TouchFramePoint();
                    touchFramePoints.put(source.id, target);
                }
                mapTouchPoint(target, source.x, source.y, rotation);
                target.generation = generation;
            }
        }

        java.util.Iterator<Map.Entry<Integer, TouchFramePoint>> pointIterator = touchFramePoints.entrySet().iterator();
        while (pointIterator.hasNext()) {
            Map.Entry<Integer, TouchFramePoint> entry = pointIterator.next();
            if (entry.getValue().generation == generation) continue;
            touchRoles.remove(entry.getKey());
            pointIterator.remove();
        }

        if (touchRegionEditorAttached) {
            float[] mapped = new float[touchFramePoints.size() * 3];
            int mappedIndex = 0;
            for (Map.Entry<Integer, TouchFramePoint> entry : touchFramePoints.entrySet()) {
                TouchFramePoint point = entry.getValue();
                mapped[mappedIndex++] = entry.getKey();
                mapped[mappedIndex++] = point.x;
                mapped[mappedIndex++] = point.y;
            }
            lastTouchMappedPoints = mapped;
        } else if (lastTouchMappedPoints.length != 0) {
            lastTouchMappedPoints = new float[0];
        }

        if (!isTouchRuntimeAllowed()) {
            clearTouchDisplayPressedState();
            updateTouchFrameStatusForEditor();
            return;
        }

        for (Map.Entry<Integer, TouchFramePoint> entry : touchFramePoints.entrySet()) {
            TouchFramePoint point = entry.getValue();
            TouchRoleState existing = touchRoles.get(entry.getKey());
            if (existing != null && existing.role != TouchRoleState.NONE) continue;
            int role = classifyTouchRole(point.x, point.y);
            if (existing == null || role != TouchRoleState.NONE) {
                touchRoles.put(entry.getKey(), new TouchRoleState(role, point.x, point.y));
            }
        }

        int mask = 0;
        for (Map.Entry<Integer, TouchRoleState> entry : touchRoles.entrySet()) {
            TouchFramePoint point = touchFramePoints.get(entry.getKey());
            if (point == null) continue;
            TouchRoleState role = entry.getValue();
            switch (role.role) {
                case TouchRoleState.JOYSTICK:
                    float dx = point.x - role.startX;
                    float dy = point.y - role.startY;
                    if (dx < -touchJoystickThresholdX) mask |= NativeKeyEngine.A;
                    else if (dx > touchJoystickThresholdX) mask |= NativeKeyEngine.D;
                    if (dy < -touchJoystickThresholdY) mask |= NativeKeyEngine.W;
                    else if (dy > touchJoystickThresholdY) mask |= NativeKeyEngine.S;
                    break;
                case TouchRoleState.LMB:
                    mask |= NativeKeyCanvasView.TOUCH_MOUSE_LEFT;
                    break;
                case TouchRoleState.RMB:
                    mask |= NativeKeyCanvasView.TOUCH_MOUSE_RIGHT;
                    break;
                case TouchRoleState.SPACE:
                    mask |= NativeKeyEngine.SPACE;
                    break;
                default:
                    break;
            }
        }
        setTouchDisplayPressedMask(mask);
        updateTouchFrameStatus(mask);
    }

    private void updateTouchFrameStatusForEditor() {
        if (touchFramePoints.isEmpty()) {
            lastTouchFrameStatus = "编辑器 · 等待触摸";
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTouchStatusUpdateUptimeMs < 120L) return;
        lastTouchStatusUpdateUptimeMs = now;
        Map.Entry<Integer, TouchFramePoint> first = touchFramePoints.entrySet().iterator().next();
        TouchFramePoint point = first.getValue();
        lastTouchFrameStatus = String.format(java.util.Locale.US,
                "编辑器 · %d触点 · %.3f,%.3f", touchFramePoints.size(), point.x, point.y);
    }

    private void updateTouchFrameStatus(int mask) {
        if (touchFramePoints.isEmpty()) {
            lastTouchFrameStatus = "等待触摸";
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTouchStatusUpdateUptimeMs < 120L) return;
        lastTouchStatusUpdateUptimeMs = now;
        Map.Entry<Integer, TouchFramePoint> first = touchFramePoints.entrySet().iterator().next();
        TouchFramePoint point = first.getValue();
        TouchRoleState role = touchRoles.get(first.getKey());
        lastTouchFrameStatus = String.format(java.util.Locale.US,
                "%d触点 · %.3f,%.3f · %s · mask=%d",
                touchFramePoints.size(), point.x, point.y,
                touchRoleName(role == null ? TouchRoleState.NONE : role.role), mask);
    }

    private String touchRoleName(int role) {
        switch (role) {
            case TouchRoleState.JOYSTICK: return "摇杆";
            case TouchRoleState.LMB: return "左键";
            case TouchRoleState.RMB: return "右键";
            case TouchRoleState.SPACE: return "空格";
            default: return "未命中区域";
        }
    }

    private int classifyTouchRole(float x, float y) {
        if (containsTouchRegion(TouchDisplayStore.REGION_MOUSE_LEFT, x, y)) return TouchRoleState.LMB;
        if (containsTouchRegion(TouchDisplayStore.REGION_MOUSE_RIGHT, x, y)) return TouchRoleState.RMB;
        if (containsTouchRegion(TouchDisplayStore.REGION_SPACE, x, y)) return TouchRoleState.SPACE;
        if (containsTouchRegion(TouchDisplayStore.REGION_JOYSTICK, x, y)) return TouchRoleState.JOYSTICK;
        return TouchRoleState.NONE;
    }

    private boolean containsTouchRegion(int region, float x, float y) {
        if (region < 0 || region >= touchRegionRects.length) return false;
        float[] rect = touchRegionRects[region];
        return x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3];
    }

    private void reloadTouchRegionCache() {
        for (int region = 0; region < touchRegionRects.length; region++) {
            float[] stored = TouchDisplayStore.getRegion(this, region);
            System.arraycopy(stored, 0, touchRegionRects[region], 0, 4);
        }
        float[] joystick = touchRegionRects[TouchDisplayStore.REGION_JOYSTICK];
        touchJoystickThresholdX = Math.max(0.010f, (joystick[2] - joystick[0]) * 0.075f);
        touchJoystickThresholdY = Math.max(0.012f, (joystick[3] - joystick[1]) * 0.075f);
    }

    private void mapTouchPoint(TouchFramePoint out, float x, float y, int rotation) {
        switch (rotation) {
            case Surface.ROTATION_90:
                // evdev reports coordinates in the panel's natural (portrait) orientation.
                out.x = y;
                out.y = 1f - x;
                break;
            case Surface.ROTATION_180:
                out.x = 1f - x;
                out.y = 1f - y;
                break;
            case Surface.ROTATION_270:
                out.x = 1f - y;
                out.y = x;
                break;
            case Surface.ROTATION_0:
            default:
                out.x = x;
                out.y = y;
                break;
        }
    }

    private void setTouchDisplayPressedMask(int mask) {
        if (touchDisplayPressedMask == mask) return;
        touchDisplayPressedMask = mask;
        if (touchDisplayView != null) touchDisplayView.setPressedMask(mask);
    }

    private void clearTouchDisplayPressedState() {
        touchRoles.clear();
        setTouchDisplayPressedMask(0);
    }

    private void syncKeyPromptWindow(boolean enabled) {
        if (!enabled) {
            animateRemoveKeyPrompt();
            return;
        }
        keyPromptRemoving = false;
        ensureKeyPromptWindow();
        if (keyPromptView != null) {
            keyPromptView.setDragEnabled(inputRuntimeConfig.dragEnabled);
            keyPromptView.setDisplaySize(OverlayState.getKeyPromptSize(this));
            keyPromptView.setLayerOpacities(
                    OverlayState.getKeyBackgroundOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyStrokeOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyTextOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setDiffusionOpacity(
                    OverlayState.getKeyDiffusionOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setKeyAppearance(
                    OverlayState.getKeyStyle(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyPressColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setKeyBaseColor(
                    OverlayState.getKeyBaseColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setKeyBorderColor(
                    OverlayState.getKeyBorderColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setCornerStrength(
                    OverlayState.getKeyCornerStrength(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setGlobalHtmlRenderer(globalHtmlActive, globalHtmlContent);
            keyPromptView.animateIn();
        }
        updateKeyPromptLayout();
    }

    private void ensureKeyPromptWindow() {
        if (keyPromptAttached || windowManager == null) return;
        keyPromptView = new KeyPromptOverlayView(this);
        keyPromptView.setDragListener(this);
        keyPromptView.setDragEnabled(inputRuntimeConfig.dragEnabled);
        keyPromptView.setDisplaySize(OverlayState.getKeyPromptSize(this));
        keyPromptView.setLayerOpacities(
                    OverlayState.getKeyBackgroundOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyStrokeOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyTextOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setDiffusionOpacity(
                OverlayState.getKeyDiffusionOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyAppearance(
                OverlayState.getKeyStyle(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                OverlayState.getKeyPressColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyBaseColor(
                OverlayState.getKeyBaseColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyBorderColor(
                OverlayState.getKeyBorderColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setCornerStrength(
                OverlayState.getKeyCornerStrength(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptParams = new WindowManager.LayoutParams(
                keyPromptWidthPx(), keyPromptHeightPx(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                windowFlags(), PixelFormat.TRANSLUCENT);
        keyPromptParams.gravity = Gravity.TOP | Gravity.START;
        keyPromptParams.setTitle("AxonInputKeyPrompt");
        applyKeyPromptPosition();
        windowManager.addView(keyPromptView, keyPromptParams);
        keyPromptAttached = true;
        keyPromptMouseButtons = (int) (NativeKeyEngine.nativeGetMouseStats(SystemClock.uptimeMillis()) & 0x3L);
    }

    private void updateKeyPromptLayout() {
        if (!keyPromptAttached || keyPromptView == null || keyPromptParams == null || windowManager == null) return;
        keyPromptParams.width = keyPromptWidthPx();
        keyPromptParams.height = keyPromptHeightPx();
        keyPromptParams.flags = windowFlags();
        keyPromptView.setDragEnabled(inputRuntimeConfig.dragEnabled);
        keyPromptView.setDisplaySize(OverlayState.getKeyPromptSize(this));
        keyPromptView.setLayerOpacities(
                    OverlayState.getKeyBackgroundOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyStrokeOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyTextOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setDiffusionOpacity(
                OverlayState.getKeyDiffusionOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyAppearance(
                OverlayState.getKeyStyle(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                OverlayState.getKeyPressColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyBaseColor(
                OverlayState.getKeyBaseColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyBorderColor(
                OverlayState.getKeyBorderColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setCornerStrength(
                OverlayState.getKeyCornerStrength(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setGlobalHtmlRenderer(globalHtmlActive, globalHtmlContent);
        applyKeyPromptPosition();
        windowManager.updateViewLayout(keyPromptView, keyPromptParams);
    }

    private int keyPromptWidthPx() {
        return Math.max(1, dp(KEY_PROMPT_WIDTH_DP * renderBufferPercent(OverlayState.getKeyPromptSize(this)) / 100f));
    }

    private int keyPromptHeightPx() {
        return Math.max(1, dp(KEY_PROMPT_HEIGHT_DP * renderBufferPercent(OverlayState.getKeyPromptSize(this)) / 100f));
    }

    private void applyKeyPromptPosition() {
        if (keyPromptParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - keyPromptParams.width);
        int maxY = Math.max(0, metrics.heightPixels - keyPromptParams.height);
        keyPromptParams.x = Math.round(maxX * (OverlayState.getPositionX(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT) / 100f));
        keyPromptParams.y = Math.round(maxY * (OverlayState.getPositionY(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT) / 100f));
    }

    private void saveKeyPromptPosition() {
        if (keyPromptParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - keyPromptParams.width);
        int maxY = Math.max(0, metrics.heightPixels - keyPromptParams.height);
        int x = maxX == 0 ? 0 : Math.round((keyPromptParams.x / (float) maxX) * 100f);
        int y = maxY == 0 ? 0 : Math.round((keyPromptParams.y / (float) maxY) * 100f);
        OverlayState.savePosition(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT, x, y);
    }

    private void animateRemoveKeyPrompt() {
        if (!keyPromptAttached || keyPromptView == null) {
            removeKeyPromptImmediate();
            return;
        }
        if (keyPromptRemoving) return;
        keyPromptRemoving = true;
        KeyPromptOverlayView exiting = keyPromptView;
        exiting.animateOut(() -> {
            if (keyPromptRemoving && keyPromptView == exiting) removeKeyPromptImmediate();
        });
    }

    private void removeKeyPromptImmediate() {
        keyPromptRemoving = false;
        if (!keyPromptAttached || windowManager == null || keyPromptView == null) {
            keyPromptAttached = false;
            keyPromptView = null;
            keyPromptParams = null;
            keyPromptMouseButtons = 0;
            return;
        }
        keyPromptView.clearAll();
        removeViewBestEffort(keyPromptView);
        keyPromptAttached = false;
        keyPromptView = null;
        keyPromptParams = null;
        keyPromptMouseButtons = 0;
    }

    private void syncDpsWindow(boolean enabled) {
        if (!enabled) {
            removeDpsImmediate();
            return;
        }
        ensureDpsWindow();
        updateDpsLayout();
        if (dpsView != null) {
            int target = inputRuntimeConfig.dpsTargetKey;
            dpsView.setDpsValue(target == OverlayState.DPS_TARGET_NONE
                    ? -1 : dpsTracker.count(DpsTracker.TARGET, SystemClock.uptimeMillis()));
        }
    }

    private void ensureDpsWindow() {
        if (dpsAttached || windowManager == null) return;
        dpsView = new DpsOverlayView(this);
        dpsView.setDragListener(this);
        dpsView.setDragEnabled(inputRuntimeConfig.dragEnabled);
        dpsView.setUserOpacity(OverlayState.getDisplayOpacity(this, DpsOverlayView.DISPLAY_DPS));
        dpsParams = new WindowManager.LayoutParams(
                dp(DPS_WIDTH_DP), dp(DPS_HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                windowFlags(), PixelFormat.TRANSLUCENT);
        dpsParams.gravity = Gravity.TOP | Gravity.START;
        dpsParams.setTitle("AxonInputDps");
        applyDpsPosition();
        windowManager.addView(dpsView, dpsParams);
        dpsAttached = true;
    }

    private void updateDpsLayout() {
        if (!dpsAttached || dpsView == null || dpsParams == null || windowManager == null) return;
        dpsParams.width = dp(DPS_WIDTH_DP);
        dpsParams.height = dp(DPS_HEIGHT_DP);
        dpsParams.flags = windowFlags();
        dpsView.setDragEnabled(inputRuntimeConfig.dragEnabled);
        dpsView.setUserOpacity(OverlayState.getDisplayOpacity(this, DpsOverlayView.DISPLAY_DPS));
        applyDpsPosition();
        windowManager.updateViewLayout(dpsView, dpsParams);
    }

    private void applyDpsPosition() {
        if (dpsParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - dpsParams.width);
        int maxY = Math.max(0, metrics.heightPixels - dpsParams.height);
        dpsParams.x = Math.round(maxX * (OverlayState.getPositionX(this, DpsOverlayView.DISPLAY_DPS) / 100f));
        dpsParams.y = Math.round(maxY * (OverlayState.getPositionY(this, DpsOverlayView.DISPLAY_DPS) / 100f));
    }

    private void saveDpsPosition() {
        if (dpsParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - dpsParams.width);
        int maxY = Math.max(0, metrics.heightPixels - dpsParams.height);
        int x = maxX == 0 ? 0 : Math.round((dpsParams.x / (float) maxX) * 100f);
        int y = maxY == 0 ? 0 : Math.round((dpsParams.y / (float) maxY) * 100f);
        OverlayState.savePosition(this, DpsOverlayView.DISPLAY_DPS, x, y);
    }

    private void removeDpsImmediate() {
        if (!dpsAttached || windowManager == null || dpsView == null) {
            dpsAttached = false;
            dpsView = null;
            dpsParams = null;
            return;
        }
        removeViewBestEffort(dpsView);
        dpsAttached = false;
        dpsView = null;
        dpsParams = null;
    }

    private void syncTrajectoryWindow(boolean enabled) {
        if (!enabled) {
            animateRemoveTrajectory();
            return;
        }
        trajectoryRemoving = false;
        ensureTrajectoryWindow();
        if (trajectoryView != null) {
            trajectoryView.setDragEnabled(inputRuntimeConfig.dragEnabled);
            trajectoryView.setDisplaySize(OverlayState.getMouseTrajectorySize(this));
            trajectoryView.setAlpha(OverlayState.getDisplayOpacity(this, MouseTrajectoryView.DISPLAY_TRAJECTORY) / 100f);
            trajectoryView.setDotSize(OverlayState.getMouseTrajectoryDotSize(this));
            trajectoryView.setButtonColorConfig(
                    OverlayState.isMouseTrajectoryLeftColorEnabled(this),
                    OverlayState.getMouseTrajectoryLeftColor(this),
                    OverlayState.isMouseTrajectoryRightColorEnabled(this),
                    OverlayState.getMouseTrajectoryRightColor(this));
            trajectoryView.setMouseStats(NativeKeyEngine.nativeGetMouseStats(SystemClock.uptimeMillis()));
            trajectoryView.setGlobalHtmlRenderer(globalHtmlActive, globalHtmlContent);
            trajectoryView.animateIn();
        }
        updateTrajectoryLayout();
    }

    private void ensureTrajectoryWindow() {
        if (trajectoryAttached || windowManager == null) return;
        trajectoryView = new MouseTrajectoryView(this);
        trajectoryView.setDragListener(this);
        trajectoryView.setDragEnabled(inputRuntimeConfig.dragEnabled);
        trajectoryView.setAlpha(OverlayState.getDisplayOpacity(this, MouseTrajectoryView.DISPLAY_TRAJECTORY) / 100f);
        trajectoryParams = new WindowManager.LayoutParams(
                trajectoryWidthPx(), trajectoryHeightPx(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                windowFlags(), PixelFormat.TRANSLUCENT);
        trajectoryParams.gravity = Gravity.TOP | Gravity.START;
        trajectoryParams.setTitle("AxonInputMouseTrajectory");
        applyTrajectoryPosition();
        windowManager.addView(trajectoryView, trajectoryParams);
        trajectoryAttached = true;
        trajectoryView.resetMotion();
    }

    private void updateTrajectoryLayout() {
        if (!trajectoryAttached || trajectoryView == null || trajectoryParams == null || windowManager == null) return;
        trajectoryParams.width = trajectoryWidthPx();
        trajectoryParams.height = trajectoryHeightPx();
        trajectoryParams.flags = windowFlags();
        trajectoryView.setDragEnabled(inputRuntimeConfig.dragEnabled);
        trajectoryView.setDisplaySize(OverlayState.getMouseTrajectorySize(this));
        trajectoryView.setAlpha(OverlayState.getDisplayOpacity(this, MouseTrajectoryView.DISPLAY_TRAJECTORY) / 100f);
        trajectoryView.setDotSize(OverlayState.getMouseTrajectoryDotSize(this));
        trajectoryView.setButtonColorConfig(
                OverlayState.isMouseTrajectoryLeftColorEnabled(this),
                OverlayState.getMouseTrajectoryLeftColor(this),
                OverlayState.isMouseTrajectoryRightColorEnabled(this),
                OverlayState.getMouseTrajectoryRightColor(this));
        trajectoryView.setMouseStats(NativeKeyEngine.nativeGetMouseStats(SystemClock.uptimeMillis()));
        trajectoryView.setGlobalHtmlRenderer(globalHtmlActive, globalHtmlContent);
        applyTrajectoryPosition();
        windowManager.updateViewLayout(trajectoryView, trajectoryParams);
    }

    private int trajectoryWidthPx() {
        return Math.max(1, dp(TRAJECTORY_SIZE_DP * renderBufferPercent(OverlayState.getMouseTrajectorySize(this)) / 100f));
    }

    private int trajectoryHeightPx() {
        return Math.max(1, dp(TRAJECTORY_SIZE_DP * renderBufferPercent(OverlayState.getMouseTrajectorySize(this)) / 100f));
    }

    private void applyTrajectoryPosition() {
        if (trajectoryParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - trajectoryParams.width);
        int maxY = Math.max(0, metrics.heightPixels - trajectoryParams.height);
        trajectoryParams.x = Math.round(maxX * (OverlayState.getPositionX(this, MouseTrajectoryView.DISPLAY_TRAJECTORY) / 100f));
        trajectoryParams.y = Math.round(maxY * (OverlayState.getPositionY(this, MouseTrajectoryView.DISPLAY_TRAJECTORY) / 100f));
    }

    private void saveTrajectoryPosition() {
        if (trajectoryParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - trajectoryParams.width);
        int maxY = Math.max(0, metrics.heightPixels - trajectoryParams.height);
        int x = maxX == 0 ? 0 : Math.round((trajectoryParams.x / (float) maxX) * 100f);
        int y = maxY == 0 ? 0 : Math.round((trajectoryParams.y / (float) maxY) * 100f);
        OverlayState.savePosition(this, MouseTrajectoryView.DISPLAY_TRAJECTORY, x, y);
    }

    private void animateRemoveTrajectory() {
        if (!trajectoryAttached || trajectoryView == null) {
            removeTrajectoryImmediate();
            return;
        }
        if (trajectoryRemoving) return;
        trajectoryRemoving = true;
        MouseTrajectoryView exiting = trajectoryView;
        exiting.animateOut(() -> {
            if (trajectoryRemoving && trajectoryView == exiting) removeTrajectoryImmediate();
        });
    }

    private void removeTrajectoryImmediate() {
        trajectoryRemoving = false;
        if (!trajectoryAttached || windowManager == null || trajectoryView == null) {
            trajectoryAttached = false;
            trajectoryView = null;
            trajectoryParams = null;
            return;
        }
        trajectoryView.resetMotion();
        removeViewBestEffort(trajectoryView);
        trajectoryAttached = false;
        trajectoryView = null;
        trajectoryParams = null;
    }

    private void startMouseMonitor() {
        if (!needsMouseMonitor()) return;
        if (!RootBridge.isProbeComplete()) {
            RootBridge.ensureActivated(this, rootActive -> startMouseMonitor());
            return;
        }
        int mode = inputRuntimeConfig.sensitivityMode;
        if (mode != SensitivitySettingsStore.MODE_ROOT
                && (!ShizukuBridge.isReady() || !ShizukuBridge.hasPermission())) return;
        if (mouseMonitor == null) mouseMonitor = new MouseInputMonitor(this, this);
        mouseMonitor.start();
        mouseMonitorActive = true;
        if (inputRuntimeConfig.mouseEnabled && !mouseTickerRunning) {
            mouseTickerRunning = true;
            mainHandler.removeCallbacks(mouseTicker);
            mainHandler.post(mouseTicker);
        } else if (!inputRuntimeConfig.mouseEnabled) {
            mouseTickerRunning = false;
            mainHandler.removeCallbacks(mouseTicker);
        }
    }

    private boolean needsMouseMonitor() {
        boolean bindingActive = MainActivity.isBindingActivityActive()
                || SuperCustomDisplayActivity.isBindingActivityActive();
        // When sensitivity proxying is disabled, Live2D mouse capture shares the existing global
        // getevent monitor. If sensitivity is enabled the proxy callback feeds onMouseMotion(), so
        // InputRuntimeConfig intentionally keeps the normal monitor stopped to avoid duplicates.
        return inputRuntimeConfig.needsMouseMonitor(bindingActive)
                || (!inputRuntimeConfig.sensitivityEnabled && live2dMouseCaptureEnabled);
    }

    private boolean needsGamepadMonitor() {
        boolean bindingActive = MainActivity.isBindingActivityActive()
                || SuperCustomDisplayActivity.isBindingActivityActive();
        return inputRuntimeConfig.needsGamepadMonitor(bindingActive);
    }

    private void startGamepadMonitor() {
        if (!needsGamepadMonitor()) return;
        if (!RootBridge.isProbeComplete()) {
            RootBridge.ensureActivated(this, rootActive -> startGamepadMonitor());
            return;
        }
        int mode = inputRuntimeConfig.sensitivityMode;
        if (mode == SensitivitySettingsStore.MODE_SHIZUKU
                && (!ShizukuBridge.isReady() || !ShizukuBridge.hasPermission())) return;
        if (gamepadMonitor == null) gamepadMonitor = new GamepadInputMonitor(this, this);
        if (!gamepadMonitorActive) {
            gamepadMonitorActive = true;
            gamepadMonitor.start();
        }
    }

    private void stopGamepadMonitor() {
        if (gamepadMonitorActive) {
            gamepadMonitorActive = false;
            if (gamepadMonitor != null) gamepadMonitor.stop();
        }
    }

    private void syncVader5UsbMonitor() {
        // M1-M4 不在标准 XInput 报告中。背键显示开启时始终尝试 USB Host 扩展接口，
        // 不依赖 Root/Shizuku，也不受灵敏度代理是否接管 evdev 的影响。
        boolean needed = OverlayState.isGamepadBackEnabled(this);
        if (needed) {
            if (vader5UsbMonitor == null) vader5UsbMonitor = new Vader5ProUsbMonitor(this, this);
            vader5UsbMonitor.start();
        } else {
            stopVader5UsbMonitor();
        }
    }

    private void stopVader5UsbMonitor() {
        if (vader5UsbMonitor != null) vader5UsbMonitor.stop();
        vader5UsbBackButtons = 0;
        vader5UsbProfile = false;
        updateVader5ProfileState();
    }

    private void stopMouseMonitor() {
        synchronized (mouseMotionLock) {
            pendingMouseDx = 0;
            pendingMouseDy = 0;
            mouseMotionPosted = false;
        }
        mouseTickerRunning = false;
        mainHandler.removeCallbacks(mouseTicker);
        if (!mouseMonitorActive) return;
        mouseMonitorActive = false;
        if (mouseMonitor != null) mouseMonitor.stop();
        proxyMouseButtons = 0;
        if (mouseWindow.view != null) mouseWindow.view.setMouseStats(0L);
        if (trajectoryView != null) trajectoryView.resetMotion();
        if (keyPromptView != null) keyPromptView.clearAll();
        keyPromptMouseButtons = 0;
    }

    private void updateCpsGamepadTarget(int previous, int current, long now) {
        InputRuntimeConfig config = inputRuntimeConfig;
        if (!config.dpsEnabled) return;
        int rising = (~previous) & current & 0x00ffffff;
        if (rising == 0) return;

        int target = config.dpsTargetKey;
        if (target == OverlayState.DPS_TARGET_NONE) {
            if (MainActivity.isNonDpsBindingCaptureActive()) return;
            int buttonBit = firstGamepadCpsButton(rising);
            if (buttonBit == 0) return;
            int resolvedTarget = OverlayState.gamepadDpsTarget(buttonBit);
            OverlayState.setDpsTargetKeyCode(this, resolvedTarget);
            inputRuntimeConfig = config.withDpsTarget(resolvedTarget);
            dpsTracker.resetChannel(DpsTracker.TARGET);
            if (dpsView != null) dpsView.setDpsValue(0);
            return;
        }

        if (!OverlayState.isGamepadDpsTarget(target)) return;
        int targetBit = OverlayState.getGamepadDpsTargetBit(target);
        int targetMask = gamepadCpsMask(targetBit);
        if ((rising & targetMask) != 0) {
            dpsTracker.record(DpsTracker.TARGET, now);
            if (dpsView != null) pushDpsToViews(now);
        }
    }

    private int firstGamepadCpsButton(int rising) {
        for (int bit : GAMEPAD_CPS_PRIORITY) {
            if ((rising & gamepadCpsMask(bit)) != 0) return bit;
        }
        return 0;
    }

    private int gamepadCpsMask(int buttonBit) {
        if (buttonBit == GamepadOverlayView.BTN_WEST) {
            return GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_C;
        }
        if (buttonBit == GamepadOverlayView.BTN_EAST) {
            return GamepadOverlayView.BTN_EAST | GamepadOverlayView.BTN_Z;
        }
        return buttonBit;
    }

    private void recordGamepadDpsTransitions(int previous, int current, long now) {
        recordRising(previous, current, GamepadOverlayView.BTN_NORTH, DpsTracker.FACE_Y, now);
        recordRising(previous, current, GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_C, DpsTracker.FACE_X, now);
        recordRising(previous, current, GamepadOverlayView.BTN_EAST | GamepadOverlayView.BTN_Z, DpsTracker.FACE_B, now);
        recordRising(previous, current, GamepadOverlayView.BTN_SOUTH, DpsTracker.FACE_A, now);
        recordRising(previous, current, GamepadOverlayView.BTN_L1, DpsTracker.L1, now);
        recordRising(previous, current, GamepadOverlayView.BTN_R1, DpsTracker.R1, now);
    }

    private void recordRising(int previous, int current, int mask, int channel, long now) {
        if ((previous & mask) == 0 && (current & mask) != 0) dpsTracker.record(channel, now);
    }

    private void pushDpsToViews(long now) {
        int space = dpsTracker.count(DpsTracker.SPACE, now);
        if (keyboardWindow.view != null) keyboardWindow.view.setKeyboardDps(space);
        if (dpsView != null) {
            int target = inputRuntimeConfig.dpsTargetKey;
            dpsView.setDpsValue(target == OverlayState.DPS_TARGET_NONE
                    ? -1 : dpsTracker.count(DpsTracker.TARGET, now));
        }
        pushDpsToView(faceWindow.view, now);
        pushDpsToView(leftShoulderWindow.view, now);
        pushDpsToView(rightShoulderWindow.view, now);
    }

    private void pushDpsToView(GamepadOverlayView view, long now) {
        if (view == null) return;
        view.setDpsStats(
                dpsTracker.count(DpsTracker.FACE_Y, now),
                dpsTracker.count(DpsTracker.FACE_X, now),
                dpsTracker.count(DpsTracker.FACE_B, now),
                dpsTracker.count(DpsTracker.FACE_A, now),
                dpsTracker.count(DpsTracker.L1, now),
                dpsTracker.count(DpsTracker.R1, now));
    }

    private boolean needsDpsTicker() {
        boolean keyboardDps = keyboardWindow.view != null && inputRuntimeConfig.keyboardSpaceDpsEnabled;
        boolean faceDps = faceWindow.view != null && gamepadRuntimeConfig.faceDpsEnabled;
        boolean leftDps = leftShoulderWindow.view != null && gamepadRuntimeConfig.leftShoulderDpsEnabled;
        boolean rightDps = rightShoulderWindow.view != null && gamepadRuntimeConfig.rightShoulderDpsEnabled;
        boolean targetDps = dpsView != null && inputRuntimeConfig.dpsEnabled
                && inputRuntimeConfig.dpsTargetKey != OverlayState.DPS_TARGET_NONE;
        return keyboardDps || faceDps || leftDps || rightDps || targetDps;
    }

    private void refreshDpsTicker() {
        mainHandler.removeCallbacks(dpsTicker);
        dpsTickerRunning = needsDpsTicker();
        if (dpsTickerRunning) {
            pushDpsToViews(SystemClock.uptimeMillis());
            mainHandler.postDelayed(dpsTicker, 100L);
        }
    }

    private void stopDpsTicker() {
        dpsTickerRunning = false;
        mainHandler.removeCallbacks(dpsTicker);
    }

    private void resetPressedState() {
        dpsTracker.reset();
        previousGamepadButtonsForDps = 0;
        previousGamepadButtonsForBindings = 0;
        rawGamepadButtons = 0;
        androidGamepadButtons = 0;
        androidGamepadKnownMask = 0;
        recentRawGamepadChangedMask = 0;
        recentRawGamepadSnapshot = 0;
        recentRawGamepadChangedAt = 0L;
        physicalKeyboardKeysDown.clear();
        int mask = NativeKeyEngine.nativeReset();
        long mouseStats = NativeKeyEngine.nativeResetMouse(SystemClock.uptimeMillis());
        if (keyboardWindow.view != null) {
            keyboardWindow.view.setPressedMask(mask);
            keyboardWindow.view.setKeyboardMouseButtons(0);
        }
        if (customWindow.view != null) customWindow.view.releaseCustomKeys();
        if (mouseWindow.view != null) mouseWindow.view.setMouseStats(mouseStats);
        if (keyboardCatView != null) keyboardCatView.clearInput();
        if (superCustomView != null) superCustomView.clearPressed();
        if (trajectoryView != null) trajectoryView.resetMotion();
        if (keyPromptView != null) keyPromptView.clearAll();
        if (dpsView != null) {
            dpsView.setDpsValue(OverlayState.getDpsTargetKeyCode(this) == OverlayState.DPS_TARGET_NONE ? -1 : 0);
        }
        keyPromptMouseButtons = 0;
        applyGamepadState(0, 0, 0, 0, 0, 0, 0);
        if (forceHoldVisualActive && forceHoldVisualKeyCode >= 0) {
            applyForcedHoldVisualState(forceHoldVisualKeyCode, true);
        }
    }

    private void setForcedHoldVisual(int keyCode, boolean active) {
        if (active) {
            if (forceHoldVisualActive && forceHoldVisualKeyCode != keyCode) {
                clearForcedHoldVisual();
            }
            forceHoldVisualActive = true;
            forceHoldVisualKeyCode = keyCode;
            applyForcedHoldVisualState(keyCode, true);
            return;
        }
        clearForcedHoldVisual();
    }

    private void clearForcedHoldVisual() {
        if (!forceHoldVisualActive || forceHoldVisualKeyCode < 0) return;
        int keyCode = forceHoldVisualKeyCode;
        forceHoldVisualActive = false;
        forceHoldVisualKeyCode = -1;
        applyForcedHoldVisualState(keyCode, physicalKeyboardKeysDown.contains(keyCode));
    }

    private void applyForcedHoldVisualState(int keyCode, boolean pressed) {
        long now = SystemClock.uptimeMillis();
        if (inputFullKeyboardView != null && OverlayState.isInputFullKeyboardEnabled(this)) {
            inputFullKeyboardView.setPhysicalKey(keyCode, pressed);
        }
        if (keyboardCatView != null && inputRuntimeConfig.keyboardCatEnabled) {
            keyboardCatView.setKeyState(keyCode, pressed);
        }
        if (keyPromptView != null && inputRuntimeConfig.keyPromptEnabled) {
            keyPromptView.updateKeyboardKey(keyCode, pressed, false, now);
        }
        if (NativeKeyEngine.nativeIsTrackedKey(keyCode)) {
            int mask = NativeKeyEngine.nativeUpdateKey(keyCode, pressed);
            if (keyboardWindow.view != null && OverlayState.isEnabled(this)) {
                keyboardWindow.view.setPressedMask(mask);
            }
        }
        if (customWindow.view != null && OverlayState.isCustomEnabled(this)) {
            customWindow.view.setCustomKeyPressed(keyCode, pressed);
        }
        if (superCustomView != null && inputRuntimeConfig.superCustomEnabled) {
            superCustomView.setInputPressed(InputBinding.keyboard(keyCode), pressed);
        }
    }

    private void resetWindowPressedState(DisplayWindow window) {
        if (window.view == null) return;
        if (window.type == KeyOverlayView.DISPLAY_KEYBOARD) {
            window.view.setPressedMask(NativeKeyEngine.nativeReset());
            window.view.setKeyboardMouseButtons(0);
        } else if (window.type == KeyOverlayView.DISPLAY_CUSTOM) {
            window.view.releaseCustomKeys();
        } else {
            window.view.setMouseStats(NativeKeyEngine.nativeResetMouse(SystemClock.uptimeMillis()));
        }
    }


    private void removeViewBestEffort(View view) {
        if (windowManager == null || view == null) return;
        try {
            windowManager.removeView(view);
        } catch (IllegalArgumentException ignored) {
            // Window may already be detached during accessibility-service teardown.
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
