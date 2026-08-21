package com.axon.input;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 全局输入服务：只观察输入并绘制悬浮层，不消费原始游戏输入。
 * 键盘使用 Accessibility KeyEvent，鼠标/手柄轴使用低延迟原始输入监听。
 */
public final class AxonInputAccessibilityService extends AccessibilityService
        implements InputManager.InputDeviceListener,
        ShizukuBridge.Listener,
        MouseInputMonitor.Listener,
        KeyOverlayView.DragListener,
        KeyPromptOverlayView.DragListener,
        MouseTrajectoryView.DragListener,
        GamepadOverlayView.DragListener,
        GamepadInputMonitor.Listener,
        SensitivityProxyController.Listener {

    private static final int KEYBOARD_WIDTH_DP = 280;
    private static final int KEYBOARD_HEIGHT_DP = 180;
    private static final int CUSTOM_WIDTH_DP = 280;
    private static final int CUSTOM_ROW_HEIGHT_DP = 50;
    private static final int CUSTOM_MIN_HEIGHT_DP = 56;
    private static final int MOUSE_WIDTH_DP = 180;
    private static final int MOUSE_HEIGHT_DP = 100;
    private static final int KEY_PROMPT_WIDTH_DP = 332;
    private static final int KEY_PROMPT_HEIGHT_DP = 70;
    private static final int TRAJECTORY_SIZE_DP = 106;
    private static final int GAMEPAD_STICK_SIZE_DP = 116;
    private static final int GAMEPAD_FACE_SIZE_DP = 142;
    private static final int GAMEPAD_SHOULDER_WIDTH_DP = 132;
    private static final int GAMEPAD_SHOULDER_HEIGHT_DP = 86;

    private static volatile AxonInputAccessibilityService activeService;
    private static volatile boolean appForeground;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private InputManager inputManager;
    private MouseInputMonitor mouseMonitor;
    private GamepadInputMonitor gamepadMonitor;
    private SensitivityProxyController sensitivityController;
    private boolean mouseTickerRunning;
    private boolean mouseMonitorActive;
    private boolean gamepadMonitorActive;
    private boolean dpsTickerRunning;
    private final DpsTracker dpsTracker = new DpsTracker();
    private int previousGamepadButtonsForDps;
    private int proxyMouseButtons;
    private int gamepadLx, gamepadLy, gamepadRx, gamepadRy, gamepadLt, gamepadRt, gamepadButtons;
    private int rawGamepadButtons;
    private int androidGamepadButtons;
    private int androidGamepadKnownMask;
    private boolean globalHtmlActive;
    private String globalHtmlContent = "";

    private final DisplayWindow keyboardWindow = new DisplayWindow(KeyOverlayView.DISPLAY_KEYBOARD, "AxonInputKeyboard");
    private final DisplayWindow customWindow = new DisplayWindow(KeyOverlayView.DISPLAY_CUSTOM, "AxonInputCustom");
    private final DisplayWindow mouseWindow = new DisplayWindow(KeyOverlayView.DISPLAY_MOUSE, "AxonInputMouse");
    private final GamepadWindow leftStickWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_LEFT_STICK, "AxonInputLeftStick");
    private final GamepadWindow rightStickWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_RIGHT_STICK, "AxonInputRightStick");
    private final GamepadWindow faceWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_FACE, "AxonInputFaceButtons");
    private final GamepadWindow leftShoulderWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_LEFT_SHOULDER, "AxonInputLeftShoulder");
    private final GamepadWindow rightShoulderWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_RIGHT_SHOULDER, "AxonInputRightShoulder");

    private KeyPromptOverlayView keyPromptView;
    private WindowManager.LayoutParams keyPromptParams;
    private boolean keyPromptAttached;
    private boolean keyPromptRemoving;
    private float keyPromptDragStartRawX;
    private float keyPromptDragStartRawY;
    private int keyPromptDragStartWindowX;
    private int keyPromptDragStartWindowY;
    private int keyPromptMouseButtons;

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
            if (!mouseTickerRunning || !OverlayState.isMouseEnabled(AxonInputAccessibilityService.this)) {
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

    public static void refreshActiveService() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) service.applySavedState();
        else service.mainHandler.post(service::applySavedState);
    }

    public static void setAppForeground(boolean foreground) {
        appForeground = foreground;
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        Runnable action = service::applyOverlayVisibility;
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else service.mainHandler.post(action);
    }


    /** Rebuilds lightweight overlay views so an explicit user-selected palette is applied immediately. */
    public static void refreshTheme() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        Runnable action = () -> {
            service.removeWindowImmediate(service.keyboardWindow);
            service.removeWindowImmediate(service.customWindow);
            service.removeWindowImmediate(service.mouseWindow);
            service.removeKeyPromptImmediate();
            service.removeTrajectoryImmediate();
            service.removeGamepadWindowImmediate(service.leftStickWindow);
            service.removeGamepadWindowImmediate(service.rightStickWindow);
            service.removeGamepadWindowImmediate(service.faceWindow);
            service.removeGamepadWindowImmediate(service.leftShoulderWindow);
            service.removeGamepadWindowImmediate(service.rightShoulderWindow);
            service.applySavedState();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else service.mainHandler.post(action);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeService = this;

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(this, null);

        mouseMonitor = new MouseInputMonitor(this);
        gamepadMonitor = new GamepadInputMonitor(this, this);
        sensitivityController = new SensitivityProxyController(this, this);
        ShizukuBridge.addListener(this);
        applySavedState();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Theme is user-selected rather than system-driven. Only re-apply geometry after
        // rotations/density changes; do not switch palette because system night mode changed.
        mainHandler.post(this::applySavedState);
    }

    @Override
    public void onInterrupt() {
        resetPressedState();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;

        // 手柄按键先走 Android 语义层。轴仍由 /dev/input 读取，二者互不冲突。
        if (isPhysicalGamepadEvent(event)) {
            int logicalBit = GamepadButtons.fromAndroidKeyCode(event.getKeyCode());
            if (logicalBit != 0) {
                boolean pressed = action == KeyEvent.ACTION_DOWN;
                int knownGroup = GamepadButtons.overrideGroupForAndroidKeyCode(event.getKeyCode());
                androidGamepadKnownMask |= knownGroup;
                if (pressed) androidGamepadButtons |= logicalBit;
                else androidGamepadButtons &= ~logicalBit;
                applyGamepadState(gamepadLx, gamepadLy, gamepadRx, gamepadRy, gamepadLt, gamepadRt, rawGamepadButtons);
                return false;
            }
        }

        boolean builtin = OverlayState.isEnabled(this);
        boolean custom = OverlayState.isCustomEnabled(this);
        boolean capture = OverlayState.isCustomCaptureEnabled(this);
        boolean keyPrompt = OverlayState.isKeyPromptEnabled(this);
        if (!builtin && !custom && !capture && !keyPrompt) return false;
        if (!isPhysicalKeyboardEvent(event)) return false;

        int keyCode = event.getKeyCode();
        boolean pressed = action == KeyEvent.ACTION_DOWN;

        if (keyPrompt && keyPromptView != null) {
            boolean countPress = pressed && event.getRepeatCount() == 0;
            if (!pressed || countPress) {
                keyPromptView.updateKeyboardKey(keyCode, pressed, countPress, event.getEventTime());
            }
        }

        if (builtin && keyCode == KeyEvent.KEYCODE_SPACE && pressed && event.getRepeatCount() == 0) {
            dpsTracker.record(DpsTracker.SPACE, event.getEventTime());
            if (keyboardWindow.view != null) pushDpsToViews(event.getEventTime());
        }

        if (capture && pressed && event.getRepeatCount() == 0) {
            OverlayState.addDraftKey(this, keyCode);
        }

        if (builtin && keyboardWindow.view != null && NativeKeyEngine.nativeIsTrackedKey(keyCode)) {
            int mask = NativeKeyEngine.nativeUpdateKey(keyCode, pressed);
            keyboardWindow.view.setPressedMask(mask);
        }
        if (custom && customWindow.view != null) {
            customWindow.view.setCustomKeyPressed(keyCode, pressed);
        }
        return false;
    }

    @Override public void onInputDeviceAdded(int deviceId) { resetPressedState(); }
    @Override public void onInputDeviceRemoved(int deviceId) { resetPressedState(); }
    @Override public void onInputDeviceChanged(int deviceId) { resetPressedState(); }

    @Override
    public void onShizukuReady(boolean permissionGranted) {
        if (!permissionGranted) return;
        if (sensitivityController != null) sensitivityController.onShizukuAvailable();
        applySavedState();
    }

    @Override
    public void onShizukuPermissionResult(int requestCode, boolean granted) {
        if (!granted) return;
        if (sensitivityController != null) sensitivityController.onShizukuAvailable();
        applySavedState();
    }

    @Override
    public void onShizukuDead() {
        stopMouseMonitor();
        if (OverlayState.getSensitivityMode(this) == OverlayState.SENSITIVITY_MODE_SHIZUKU) stopGamepadMonitor();
        if (sensitivityController != null) sensitivityController.onShizukuDead();
    }

    @Override
    public void onMouseState(long packedStats) {
        mainHandler.post(() -> {
            int nextButtons = (int) (packedStats & 0x3L);
            int changedButtons = keyPromptMouseButtons ^ nextButtons;
            if (keyPromptView != null && OverlayState.isKeyPromptEnabled(this)) {
                long now = SystemClock.uptimeMillis();
                if ((changedButtons & 1) != 0) {
                    keyPromptView.updateMouseButton(NativeKeyEngine.MOUSE_LEFT, (nextButtons & 1) != 0, now);
                }
                if ((changedButtons & 2) != 0) {
                    keyPromptView.updateMouseButton(NativeKeyEngine.MOUSE_RIGHT, (nextButtons & 2) != 0, now);
                }
            }
            keyPromptMouseButtons = nextButtons;
            if (mouseWindow.view != null && OverlayState.isMouseEnabled(this)) {
                mouseWindow.view.setMouseStats(packedStats);
            }
            if (trajectoryView != null && OverlayState.isMouseTrajectoryEnabled(this)) {
                trajectoryView.setMouseStats(packedStats);
            }
        });
    }

    @Override
    public void onMousePromptButton(int button, boolean pressed) {
        mainHandler.post(() -> {
            if (keyPromptView != null && OverlayState.isKeyPromptEnabled(this)) {
                keyPromptView.updateMouseButton(button, pressed, SystemClock.uptimeMillis());
            }
        });
    }

    @Override
    public void onMouseMotion(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        mainHandler.post(() -> {
            if (trajectoryView != null && OverlayState.isMouseTrajectoryEnabled(this)) {
                trajectoryView.addMotion(dx, dy);
            }
        });
    }

    @Override
    public void onGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        mainHandler.post(() -> applyGamepadState(lx, ly, rx, ry, lt, rt, buttons));
    }

    @Override
    public void onSensitivityGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        mainHandler.post(() -> applyGamepadState(lx, ly, rx, ry, lt, rt, buttons));
    }

    @Override
    public void onSensitivityStatus(String status) {
        // 状态只用于当前会话的设置页，不写入持久配置。
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
    }

    @Override
    public void onDragStart(KeyOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this)) return;
        DisplayWindow target = windowForView(source);
        if (target == null || target.params == null) return;
        target.dragStartRawX = rawX;
        target.dragStartRawY = rawY;
        target.dragStartWindowX = target.params.x;
        target.dragStartWindowY = target.params.y;
    }

    @Override
    public void onDragMove(KeyOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || windowManager == null) return;
        DisplayWindow target = windowForView(source);
        if (target == null || !target.attached || target.params == null || target.view == null) return;

        int x = target.dragStartWindowX + Math.round(rawX - target.dragStartRawX);
        int y = target.dragStartWindowY + Math.round(rawY - target.dragStartRawY);
        // Deliberately do not clamp to the visible frame. FLAG_LAYOUT_NO_LIMITS lets the
        // user place the overlay at the screen edges or partially outside the display.
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
    public void onDragStart(KeyPromptOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || keyPromptParams == null) return;
        keyPromptDragStartRawX = rawX;
        keyPromptDragStartRawY = rawY;
        keyPromptDragStartWindowX = keyPromptParams.x;
        keyPromptDragStartWindowY = keyPromptParams.y;
    }

    @Override
    public void onDragMove(KeyPromptOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || windowManager == null || !keyPromptAttached
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
        if (!OverlayState.isDragEnabled(this) || trajectoryParams == null) return;
        trajectoryDragStartRawX = rawX;
        trajectoryDragStartRawY = rawY;
        trajectoryDragStartWindowX = trajectoryParams.x;
        trajectoryDragStartWindowY = trajectoryParams.y;
    }

    @Override
    public void onDragMove(MouseTrajectoryView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || windowManager == null || !trajectoryAttached
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
        if (!OverlayState.isDragEnabled(this)) return;
        GamepadWindow target = gamepadWindowForView(source);
        if (target == null || target.params == null) return;
        target.dragStartRawX = rawX;
        target.dragStartRawY = rawY;
        target.dragStartWindowX = target.params.x;
        target.dragStartWindowY = target.params.y;
    }

    @Override
    public void onDragMove(GamepadOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || windowManager == null) return;
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
    public void onTaskRemoved(Intent rootIntent) {
        OverlayState.endAppSession(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (activeService == this) activeService = null;
        ShizukuBridge.removeListener(this);
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        stopMouseMonitor();
        stopGamepadMonitor();
        stopDpsTicker();
        if (sensitivityController != null) {
            sensitivityController.destroy();
            sensitivityController = null;
        }
        removeWindowImmediate(keyboardWindow);
        removeWindowImmediate(customWindow);
        removeWindowImmediate(mouseWindow);
        removeKeyPromptImmediate();
        removeTrajectoryImmediate();
        removeGamepadWindowImmediate(leftStickWindow);
        removeGamepadWindowImmediate(rightStickWindow);
        removeGamepadWindowImmediate(faceWindow);
        removeGamepadWindowImmediate(leftShoulderWindow);
        removeGamepadWindowImmediate(rightShoulderWindow);
        super.onDestroy();
    }

    private boolean isPhysicalKeyboardEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null || device.isVirtual()) return false;
        int sources = event.getSource();
        return (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
    }

    private boolean isPhysicalGamepadEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null) return false;
        int sources = event.getSource();
        boolean gamepadSource = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!gamepadSource) return false;
        // 灵敏度超频会把物理手柄代理成虚拟 UHID。此时虚拟设备的 Android KeyEvent 仍是需要的语义来源。
        return !device.isVirtual() || OverlayState.isSensitivityEnabled(this);
    }

    private void applySavedState() {
        globalHtmlActive = OverlayState.isGlobalHtmlEnabled(this) && OverlayState.hasGlobalHtml(this);
        globalHtmlContent = globalHtmlActive ? OverlayState.loadGlobalHtml(this) : "";
        if (globalHtmlContent.isEmpty()) globalHtmlActive = false;

        syncWindow(keyboardWindow, OverlayState.isEnabled(this));
        syncWindow(customWindow, OverlayState.isCustomEnabled(this));
        syncWindow(mouseWindow, OverlayState.isMouseEnabled(this));
        syncKeyPromptWindow(OverlayState.isKeyPromptEnabled(this));
        syncTrajectoryWindow(OverlayState.isMouseTrajectoryEnabled(this));
        syncGamepadWindow(leftStickWindow, OverlayState.isGamepadLeftStickEnabled(this));
        syncGamepadWindow(rightStickWindow, OverlayState.isGamepadRightStickEnabled(this));
        syncGamepadWindow(faceWindow, OverlayState.isGamepadFaceEnabled(this));
        syncGamepadWindow(leftShoulderWindow, OverlayState.isGamepadLeftShoulderEnabled(this));
        syncGamepadWindow(rightShoulderWindow, OverlayState.isGamepadRightShoulderEnabled(this));
        applyOverlayVisibility();
        refreshDpsTicker();

        boolean sensitivity = OverlayState.isSensitivityEnabled(this);
        if (sensitivityController != null) {
            sensitivityController.apply(
                    sensitivity,
                    OverlayState.getMouseSensitivity(this),
                    OverlayState.getGamepadSensitivity(this),
                    OverlayState.getSensitivityMode(this));
        }
        if (sensitivity) {
            // The native proxy owns the physical devices and mirrors mouse + gamepad telemetry.
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
        int visibility = OverlayState.isAutoHideBackground(this) && !appForeground
                ? View.INVISIBLE : View.VISIBLE;
        setVisibility(keyboardWindow.view, visibility);
        setVisibility(customWindow.view, visibility);
        setVisibility(mouseWindow.view, visibility);
        setVisibility(keyPromptView, visibility);
        setVisibility(trajectoryView, visibility);
        setVisibility(leftStickWindow.view, visibility);
        setVisibility(rightStickWindow.view, visibility);
        setVisibility(faceWindow.view, visibility);
        setVisibility(leftShoulderWindow.view, visibility);
        setVisibility(rightShoulderWindow.view, visibility);
    }

    private static void setVisibility(View view, int visibility) {
        if (view != null && view.getVisibility() != visibility) view.setVisibility(visibility);
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
        window.view.setDragEnabled(OverlayState.isDragEnabled(this));
        window.view.setDisplaySize(displaySizePercent(window.type));
        window.view.setAlpha(OverlayState.getDisplayOpacity(this, window.type) / 100f);
        window.view.setAnimationMode(OverlayState.getMotionMode(this, window.type));
        if (window.type == KeyOverlayView.DISPLAY_KEYBOARD) {
            window.view.setKeyboardOptions(
                    OverlayState.isKeyboardSpaceEnabled(this),
                    OverlayState.isKeyboardSpaceDpsEnabled(this));
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

    private int windowFlags() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (!OverlayState.isDragEnabled(this)) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return flags;
    }

    private int displayWidthPx(int type) {
        int base;
        if (type == KeyOverlayView.DISPLAY_MOUSE) base = MOUSE_WIDTH_DP;
        else if (type == KeyOverlayView.DISPLAY_CUSTOM) base = CUSTOM_WIDTH_DP;
        else base = KEYBOARD_WIDTH_DP;
        return Math.max(1, dp(base * displaySizePercent(type) / 100f));
    }

    private int displayHeightPx(int type) {
        int base;
        if (type == KeyOverlayView.DISPLAY_MOUSE) base = MOUSE_HEIGHT_DP;
        else if (type == KeyOverlayView.DISPLAY_CUSTOM) base = customBaseHeightDp();
        else base = OverlayState.isKeyboardSpaceEnabled(this) ? KEYBOARD_HEIGHT_DP : 128;
        return Math.max(1, dp(base * displaySizePercent(type) / 100f));
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
        return Math.max(CUSTOM_MIN_HEIGHT_DP, rows * CUSTOM_ROW_HEIGHT_DP + 4);
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
        windowManager.removeView(window.view);
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
        window.view.setDragEnabled(OverlayState.isDragEnabled(this));
        window.view.setDisplaySize(OverlayState.getGamepadDisplaySize(this, window.type));
        window.view.setAlpha(OverlayState.getDisplayOpacity(this, window.type) / 100f);
        window.view.setGlobalHtmlRenderer(globalHtmlActive, globalHtmlContent);
        if (window.type == GamepadOverlayView.DISPLAY_LEFT_STICK) {
            window.view.setStickShape(OverlayState.getGamepadLeftStickShape(this));
            window.view.setStickDotSize(OverlayState.getGamepadStickDotSize(this, window.type));
        } else if (window.type == GamepadOverlayView.DISPLAY_RIGHT_STICK) {
            window.view.setStickShape(OverlayState.getGamepadRightStickShape(this));
            window.view.setStickDotSize(OverlayState.getGamepadStickDotSize(this, window.type));
        } else if (window.type == GamepadOverlayView.DISPLAY_FACE) {
            window.view.setFaceReversed(OverlayState.isGamepadFaceReversed(this));
            window.view.setFaceDpsVisibility(
                    OverlayState.isGamepadFaceYDpsEnabled(this),
                    OverlayState.isGamepadFaceXDpsEnabled(this),
                    OverlayState.isGamepadFaceBDpsEnabled(this),
                    OverlayState.isGamepadFaceADpsEnabled(this));
        } else if (window.type == GamepadOverlayView.DISPLAY_LEFT_SHOULDER) {
            window.view.setShoulderOptions(
                    OverlayState.isGamepadL2ProgressEnabled(this),
                    OverlayState.isGamepadL1DpsEnabled(this));
        } else if (window.type == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER) {
            window.view.setShoulderOptions(
                    OverlayState.isGamepadR2ProgressEnabled(this),
                    OverlayState.isGamepadR1DpsEnabled(this));
        }
        pushDpsToView(window.view, SystemClock.uptimeMillis());
    }

    private int gamepadWidthPx(int type) {
        int base = (type == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || type == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER)
                ? GAMEPAD_SHOULDER_WIDTH_DP
                : (type == GamepadOverlayView.DISPLAY_FACE ? GAMEPAD_FACE_SIZE_DP : GAMEPAD_STICK_SIZE_DP);
        return Math.max(1, dp(base * OverlayState.getGamepadDisplaySize(this, type) / 100f));
    }

    private int gamepadHeightPx(int type) {
        int base = (type == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || type == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER)
                ? GAMEPAD_SHOULDER_HEIGHT_DP
                : (type == GamepadOverlayView.DISPLAY_FACE ? GAMEPAD_FACE_SIZE_DP : GAMEPAD_STICK_SIZE_DP);
        return Math.max(1, dp(base * OverlayState.getGamepadDisplaySize(this, type) / 100f));
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
        windowManager.removeView(window.view);
        window.attached = false;
        window.view = null;
        window.params = null;
    }

    private void applyGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        rawGamepadButtons = buttons;
        // Android KeyEvent 已确认过的按键采用 Android 语义；其余按键继续使用原始 evdev 状态。
        int effectiveButtons = (buttons & ~androidGamepadKnownMask)
                | (androidGamepadButtons & androidGamepadKnownMask);

        long now = SystemClock.uptimeMillis();
        int previousButtons = previousGamepadButtonsForDps;
        recordGamepadDpsTransitions(previousButtons, effectiveButtons, now);
        previousGamepadButtonsForDps = effectiveButtons;
        gamepadLx = lx; gamepadLy = ly; gamepadRx = rx; gamepadRy = ry;
        gamepadLt = lt; gamepadRt = rt; gamepadButtons = effectiveButtons;
        pushGamepadState(leftStickWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(rightStickWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(faceWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(leftShoulderWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(rightShoulderWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        if (effectiveButtons != previousButtons && needsDpsTicker()) pushDpsToViews(now);
    }

    private void pushGamepadState(GamepadWindow window, int lx, int ly, int rx, int ry,
                                  int lt, int rt, int buttons) {
        if (window.view != null) window.view.setGamepadState(lx, ly, rx, ry, lt, rt, buttons);
    }

    private void syncKeyPromptWindow(boolean enabled) {
        if (!enabled) {
            animateRemoveKeyPrompt();
            return;
        }
        keyPromptRemoving = false;
        ensureKeyPromptWindow();
        if (keyPromptView != null) {
            keyPromptView.setDragEnabled(OverlayState.isDragEnabled(this));
            keyPromptView.setDisplaySize(OverlayState.getKeyPromptSize(this));
            keyPromptView.setUserOpacity(OverlayState.getDisplayOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setGlobalHtmlRenderer(globalHtmlActive, globalHtmlContent);
            keyPromptView.animateIn();
        }
        updateKeyPromptLayout();
    }

    private void ensureKeyPromptWindow() {
        if (keyPromptAttached || windowManager == null) return;
        keyPromptView = new KeyPromptOverlayView(this);
        keyPromptView.setDragListener(this);
        keyPromptView.setDragEnabled(OverlayState.isDragEnabled(this));
        keyPromptView.setDisplaySize(OverlayState.getKeyPromptSize(this));
        keyPromptView.setUserOpacity(OverlayState.getDisplayOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
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
        keyPromptView.setDragEnabled(OverlayState.isDragEnabled(this));
        keyPromptView.setDisplaySize(OverlayState.getKeyPromptSize(this));
        keyPromptView.setUserOpacity(OverlayState.getDisplayOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setGlobalHtmlRenderer(globalHtmlActive, globalHtmlContent);
        applyKeyPromptPosition();
        windowManager.updateViewLayout(keyPromptView, keyPromptParams);
    }

    private int keyPromptWidthPx() {
        return Math.max(1, dp(KEY_PROMPT_WIDTH_DP * OverlayState.getKeyPromptSize(this) / 100f));
    }

    private int keyPromptHeightPx() {
        return Math.max(1, dp(KEY_PROMPT_HEIGHT_DP * OverlayState.getKeyPromptSize(this) / 100f));
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
        windowManager.removeView(keyPromptView);
        keyPromptAttached = false;
        keyPromptView = null;
        keyPromptParams = null;
        keyPromptMouseButtons = 0;
    }

    private void syncTrajectoryWindow(boolean enabled) {
        if (!enabled) {
            animateRemoveTrajectory();
            return;
        }
        trajectoryRemoving = false;
        ensureTrajectoryWindow();
        if (trajectoryView != null) {
            trajectoryView.setDragEnabled(OverlayState.isDragEnabled(this));
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
        trajectoryView.setDragEnabled(OverlayState.isDragEnabled(this));
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
        trajectoryView.setDragEnabled(OverlayState.isDragEnabled(this));
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
        return Math.max(1, dp(TRAJECTORY_SIZE_DP * OverlayState.getMouseTrajectorySize(this) / 100f));
    }

    private int trajectoryHeightPx() {
        return Math.max(1, dp(TRAJECTORY_SIZE_DP * OverlayState.getMouseTrajectorySize(this) / 100f));
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
        windowManager.removeView(trajectoryView);
        trajectoryAttached = false;
        trajectoryView = null;
        trajectoryParams = null;
    }

    private void startMouseMonitor() {
        if (!needsMouseMonitor() || !ShizukuBridge.isReady() || !ShizukuBridge.hasPermission()) return;
        if (mouseMonitor == null) mouseMonitor = new MouseInputMonitor(this);
        mouseMonitor.start();
        mouseMonitorActive = true;
        if (OverlayState.isMouseEnabled(this) && !mouseTickerRunning) {
            mouseTickerRunning = true;
            mainHandler.removeCallbacks(mouseTicker);
            mainHandler.post(mouseTicker);
        } else if (!OverlayState.isMouseEnabled(this)) {
            mouseTickerRunning = false;
            mainHandler.removeCallbacks(mouseTicker);
        }
    }

    private boolean needsMouseMonitor() {
        return !OverlayState.isSensitivityEnabled(this)
                && (OverlayState.isMouseEnabled(this) || OverlayState.isMouseTrajectoryEnabled(this)
                || OverlayState.isKeyPromptEnabled(this));
    }

    private boolean needsGamepadMonitor() {
        return !OverlayState.isSensitivityEnabled(this) && OverlayState.isAnyGamepadDisplayEnabled(this);
    }

    private void startGamepadMonitor() {
        if (!needsGamepadMonitor()) return;
        int mode = OverlayState.getSensitivityMode(this);
        if (mode == OverlayState.SENSITIVITY_MODE_SHIZUKU
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

    private void stopMouseMonitor() {
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
        boolean keyboardDps = keyboardWindow.view != null
                && OverlayState.isKeyboardSpaceEnabled(this)
                && OverlayState.isKeyboardSpaceDpsEnabled(this);
        boolean faceDps = faceWindow.view != null && OverlayState.isAnyGamepadFaceDpsEnabled(this);
        boolean leftDps = leftShoulderWindow.view != null && OverlayState.isGamepadL1DpsEnabled(this);
        boolean rightDps = rightShoulderWindow.view != null && OverlayState.isGamepadR1DpsEnabled(this);
        return keyboardDps || faceDps || leftDps || rightDps;
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
        rawGamepadButtons = 0;
        androidGamepadButtons = 0;
        androidGamepadKnownMask = 0;
        int mask = NativeKeyEngine.nativeReset();
        long mouseStats = NativeKeyEngine.nativeResetMouse(SystemClock.uptimeMillis());
        if (keyboardWindow.view != null) keyboardWindow.view.setPressedMask(mask);
        if (customWindow.view != null) customWindow.view.releaseCustomKeys();
        if (mouseWindow.view != null) mouseWindow.view.setMouseStats(mouseStats);
        if (trajectoryView != null) trajectoryView.resetMotion();
        if (keyPromptView != null) keyPromptView.clearAll();
        keyPromptMouseButtons = 0;
        applyGamepadState(0, 0, 0, 0, 0, 0, 0);
    }

    private void resetWindowPressedState(DisplayWindow window) {
        if (window.view == null) return;
        if (window.type == KeyOverlayView.DISPLAY_KEYBOARD) {
            window.view.setPressedMask(NativeKeyEngine.nativeReset());
        } else if (window.type == KeyOverlayView.DISPLAY_CUSTOM) {
            window.view.releaseCustomKeys();
        } else {
            window.view.setMouseStats(NativeKeyEngine.nativeResetMouse(SystemClock.uptimeMillis()));
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
