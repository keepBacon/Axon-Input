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
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/** 全局输入服务。读取输入并绘制悬浮层，不消费原始输入。 */
public final class AxonInputAccessibilityService extends AccessibilityService
        implements InputManager.InputDeviceListener,
        ShizukuBridge.Listener,
        MouseInputMonitor.Listener,
        KeyOverlayView.DragListener,
        KeyPromptOverlayView.DragListener,
        MouseTrajectoryView.DragListener,
        GamepadOverlayView.DragListener,
        GamepadInputMonitor.Listener,
        SensitivityProxyController.Listener,
        DpsOverlayView.DragListener {

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
    private static final int DPS_WIDTH_DP = 112;
    private static final int DPS_HEIGHT_DP = 40;
    private static final int FULL_KEYBOARD_MAX_WIDTH_DP = 720;
    private static final int FULL_KEYBOARD_MIN_HEIGHT_DP = 150;
    private static final int FULL_KEYBOARD_MAX_HEIGHT_DP = 260;

    private static volatile AxonInputAccessibilityService activeService;

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
    private int activeDpsTargetKeyCode = OverlayState.DPS_TARGET_NONE;
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

    /** 只更新灵敏度代理，避免滑动倍率时重建其他悬浮状态。 */
    public static void refreshSensitivity() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) service.applySensitivityState();
        else service.mainHandler.post(service::applySensitivityState);
    }


    /** 重建轻量悬浮视图并立即应用用户配色。 */
    public static void refreshTheme() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        Runnable action = () -> {
            service.removeWindowImmediate(service.keyboardWindow);
            service.removeWindowImmediate(service.customWindow);
            service.removeWindowImmediate(service.mouseWindow);
            service.removeKeyPromptImmediate();
            service.removeDpsImmediate();
            service.removeInputFullKeyboardImmediate();
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
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!OverlayState.isInputFullKeyboardEnabled(this)) {
            removeInputFullKeyboardImmediate();
            return;
        }
        int type = event == null ? 0 : event.getEventType();
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
        // 旋转或密度变化不修改用户配色。
        mainHandler.post(this::applySavedState);
    }

    @Override
    public void onInterrupt() {
        resetPressedState();
        if (inputFullKeyboardView != null) inputFullKeyboardView.clearPressed();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;

        // 手柄按键使用 Android 语义层，轴数据继续读取 /dev/input。
        if (isPhysicalGamepadEvent(event)) {
            int logicalBit = GamepadButtons.fromAndroidEvent(event);
            if (logicalBit != 0) {
                boolean pressed = action == KeyEvent.ACTION_DOWN;
                int knownGroup = GamepadButtons.overrideGroupForAndroidEvent(event);
                androidGamepadKnownMask |= knownGroup;
                if (pressed) androidGamepadButtons |= logicalBit;
                else androidGamepadButtons &= ~logicalBit;
                applyGamepadState(gamepadLx, gamepadLy, gamepadRx, gamepadRy, gamepadLt, gamepadRt, rawGamepadButtons);
                return false;
            }
        }

        boolean builtin = OverlayState.isEnabled(this);
        boolean inputFullKeyboard = OverlayState.isInputFullKeyboardEnabled(this);
        boolean custom = OverlayState.isCustomEnabled(this);
        boolean capture = OverlayState.isCustomCaptureEnabled(this);
        boolean keyPrompt = OverlayState.isKeyPromptEnabled(this);
        boolean dpsEnabled = OverlayState.isDpsEnabled(this);
        if (!builtin && !inputFullKeyboard && !custom && !capture && !keyPrompt && !dpsEnabled) return false;
        if (!isPhysicalKeyboardEvent(event)) return false;

        int keyCode = event.getKeyCode();
        boolean pressed = action == KeyEvent.ACTION_DOWN;
        if (inputFullKeyboard && inputFullKeyboardView != null) {
            inputFullKeyboardView.setPhysicalKey(keyCode, pressed);
        }

        int dpsTarget = OverlayState.getDpsTargetKeyCode(this);
        if (dpsEnabled && dpsTarget == OverlayState.DPS_TARGET_NONE
                && pressed && event.getRepeatCount() == 0) {
            // 启用后第一次按键用于绑定，不计入 CPS。
            OverlayState.setDpsTargetKeyCode(this, keyCode);
            dpsTarget = keyCode;
            if (dpsView != null) dpsView.setDpsValue(0);
        } else if (dpsEnabled && dpsTarget != OverlayState.DPS_TARGET_NONE
                && keyCode == dpsTarget && pressed && event.getRepeatCount() == 0) {
            dpsTracker.record(DpsTracker.TARGET, event.getEventTime());
            if (dpsView != null) pushDpsToViews(event.getEventTime());
        }

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
            long now = SystemClock.uptimeMillis();
            updateCpsMouseTarget(changedButtons, nextButtons, now);
            if (keyPromptView != null && OverlayState.isKeyPromptEnabled(this)) {
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

    private void updateCpsMouseTarget(int changedButtons, int nextButtons, long now) {
        if (!OverlayState.isDpsEnabled(this)) return;
        boolean leftPressed = (changedButtons & 1) != 0 && (nextButtons & 1) != 0;
        boolean rightPressed = (changedButtons & 2) != 0 && (nextButtons & 2) != 0;
        if (!leftPressed && !rightPressed) return;

        int target = OverlayState.getDpsTargetKeyCode(this);
        if (target == OverlayState.DPS_TARGET_NONE) {
            int mouseTarget = leftPressed
                    ? OverlayState.DPS_TARGET_MOUSE_LEFT
                    : OverlayState.DPS_TARGET_MOUSE_RIGHT;
            OverlayState.setDpsTargetKeyCode(this, mouseTarget);
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
        // 状态只用于当前会话，不写入长期配置。
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
    public void onDragStart(DpsOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || dpsParams == null) return;
        dpsDragStartRawX = rawX;
        dpsDragStartRawY = rawY;
        dpsDragStartWindowX = dpsParams.x;
        dpsDragStartWindowY = dpsParams.y;
    }

    @Override
    public void onDragMove(DpsOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || windowManager == null || !dpsAttached
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
        removeDpsImmediate();
        removeInputFullKeyboardImmediate();
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
        // 部分手柄会错误标记为键盘来源。明确的手柄 KeyCode/ScanCode 仍按手柄处理。
        boolean mappedGamepadKey = GamepadButtons.fromAndroidEvent(event) != 0;
        if (!gamepadSource && !mappedGamepadKey) return false;
        // 灵敏度超频使用虚拟 UHID。按键语义仍来自 Android KeyEvent。
        return !device.isVirtual() || OverlayState.isSensitivityEnabled(this);
    }

    private void applySavedState() {
        globalHtmlActive = OverlayState.isGlobalHtmlEnabled(this) && OverlayState.hasGlobalHtml(this);
        globalHtmlContent = globalHtmlActive ? OverlayState.loadGlobalHtml(this) : "";
        if (globalHtmlContent.isEmpty()) globalHtmlActive = false;

        syncWindow(keyboardWindow, OverlayState.isEnabled(this));
        syncWindow(customWindow, OverlayState.isCustomEnabled(this));
        syncWindow(mouseWindow, OverlayState.isMouseEnabled(this));
        syncInputFullKeyboardVisibility();
        syncKeyPromptWindow(OverlayState.isKeyPromptEnabled(this));
        int nextDpsTarget = OverlayState.getDpsTargetKeyCode(this);
        if (nextDpsTarget != activeDpsTargetKeyCode) {
            activeDpsTargetKeyCode = nextDpsTarget;
            dpsTracker.resetChannel(DpsTracker.TARGET);
        }
        syncDpsWindow(OverlayState.isDpsEnabled(this));
        syncTrajectoryWindow(OverlayState.isMouseTrajectoryEnabled(this));
        syncGamepadWindow(leftStickWindow, OverlayState.isGamepadLeftStickEnabled(this));
        syncGamepadWindow(rightStickWindow, OverlayState.isGamepadRightStickEnabled(this));
        syncGamepadWindow(faceWindow, OverlayState.isGamepadFaceEnabled(this));
        syncGamepadWindow(leftShoulderWindow, OverlayState.isGamepadLeftShoulderEnabled(this));
        syncGamepadWindow(rightShoulderWindow, OverlayState.isGamepadRightShoulderEnabled(this));
        applyOverlayVisibility();
        refreshDpsTicker();

        applySensitivityState();
    }

    private void applySensitivityState() {
        boolean sensitivity = OverlayState.isSensitivityEnabled(this);
        if (sensitivityController != null) {
            sensitivityController.apply(
                    sensitivity,
                    OverlayState.getMouseSensitivity(this),
                    OverlayState.getGamepadSensitivity(this),
                    OverlayState.getSensitivityMode(this));
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
        boolean show = OverlayState.isInputFullKeyboardEnabled(this) && isInputMethodWindowVisible();
        if (!show) {
            removeInputFullKeyboardImmediate();
            return;
        }
        ensureInputFullKeyboardWindow();
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
        windowManager.removeView(inputFullKeyboardView);
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

    private int mergeGamepadButtons(int rawButtons) {
        int mode = OverlayState.getGamepadCompatibilityMode(this);
        int triggerMask = GamepadOverlayView.BTN_L2 | GamepadOverlayView.BTN_R2;

        if (mode == OverlayState.GAMEPAD_COMPAT_LOOSE) {
            // 宽松模式保留两路按键。适合单一路径缺键的设备。
            return rawButtons | androidGamepadButtons;
        }
        if (mode == OverlayState.GAMEPAD_COMPAT_EVDEV) {
            // 底层模式不使用 Android 的按键语义。
            return rawButtons;
        }

        // 自动和 Android 优先模式使用 Android 已识别的按键覆盖对应底层位。
        int semanticMask = androidGamepadKnownMask & ~triggerMask;
        int merged = (rawButtons & ~semanticMask) | (androidGamepadButtons & semanticMask);
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

        if (mode == OverlayState.GAMEPAD_COMPAT_ANDROID) {
            int known = androidGamepadKnownMask;
            merged = (rawButtons & ~known) | (androidGamepadButtons & known);
        }
        return merged;
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
        rawGamepadButtons = buttons;
        int effectiveButtons = mergeGamepadButtons(buttons);

        if (OverlayState.isGamepadSwapXY(this)) {
            effectiveButtons = swapGamepadButtonGroups(
                    effectiveButtons,
                    GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_C,
                    GamepadOverlayView.BTN_NORTH);
        }
        if (OverlayState.isGamepadSwapAB(this)) {
            effectiveButtons = swapGamepadButtonGroups(
                    effectiveButtons,
                    GamepadOverlayView.BTN_SOUTH,
                    GamepadOverlayView.BTN_EAST | GamepadOverlayView.BTN_Z);
        }

        if (OverlayState.isGamepadSwapSticks(this)) {
            int tx = lx;
            int ty = ly;
            lx = rx;
            ly = ry;
            rx = tx;
            ry = ty;
        }
        if (OverlayState.isGamepadSwapTriggers(this)) {
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
        int previousButtons = previousGamepadButtonsForDps;
        updateCpsGamepadTarget(previousButtons, cpsButtons, now);
        recordGamepadDpsTransitions(previousButtons, cpsButtons, now);
        previousGamepadButtonsForDps = cpsButtons;
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

    private void syncDpsWindow(boolean enabled) {
        if (!enabled) {
            removeDpsImmediate();
            return;
        }
        ensureDpsWindow();
        updateDpsLayout();
        if (dpsView != null) {
            int target = OverlayState.getDpsTargetKeyCode(this);
            dpsView.setDpsValue(target == OverlayState.DPS_TARGET_NONE
                    ? -1 : dpsTracker.count(DpsTracker.TARGET, SystemClock.uptimeMillis()));
        }
    }

    private void ensureDpsWindow() {
        if (dpsAttached || windowManager == null) return;
        dpsView = new DpsOverlayView(this);
        dpsView.setDragListener(this);
        dpsView.setDragEnabled(OverlayState.isDragEnabled(this));
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
        dpsView.setDragEnabled(OverlayState.isDragEnabled(this));
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
        windowManager.removeView(dpsView);
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
        int target = OverlayState.getDpsTargetKeyCode(this);
        boolean cpsNeedsMouse = OverlayState.isDpsEnabled(this)
                && (target == OverlayState.DPS_TARGET_NONE
                || target == OverlayState.DPS_TARGET_MOUSE_LEFT
                || target == OverlayState.DPS_TARGET_MOUSE_RIGHT);
        return !OverlayState.isSensitivityEnabled(this)
                && (OverlayState.isMouseEnabled(this) || OverlayState.isMouseTrajectoryEnabled(this)
                || OverlayState.isKeyPromptEnabled(this) || cpsNeedsMouse);
    }

    private boolean needsGamepadMonitor() {
        int target = OverlayState.getDpsTargetKeyCode(this);
        boolean cpsNeedsGamepad = OverlayState.isDpsEnabled(this)
                && (target == OverlayState.DPS_TARGET_NONE || OverlayState.isGamepadDpsTarget(target));
        return !OverlayState.isSensitivityEnabled(this)
                && (OverlayState.isAnyGamepadDisplayEnabled(this) || cpsNeedsGamepad);
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

    private void updateCpsGamepadTarget(int previous, int current, long now) {
        if (!OverlayState.isDpsEnabled(this)) return;
        int rising = (~previous) & current & 0x7fff;
        if (rising == 0) return;

        int target = OverlayState.getDpsTargetKeyCode(this);
        if (target == OverlayState.DPS_TARGET_NONE) {
            int buttonBit = firstGamepadCpsButton(rising);
            if (buttonBit == 0) return;
            OverlayState.setDpsTargetKeyCode(this, OverlayState.gamepadDpsTarget(buttonBit));
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
        int[] priority = {
                GamepadOverlayView.BTN_SOUTH, GamepadOverlayView.BTN_EAST,
                GamepadOverlayView.BTN_WEST, GamepadOverlayView.BTN_NORTH,
                GamepadOverlayView.BTN_L1, GamepadOverlayView.BTN_R1,
                GamepadOverlayView.BTN_L2, GamepadOverlayView.BTN_R2,
                GamepadOverlayView.BTN_L3, GamepadOverlayView.BTN_R3,
                1 << 10, 1 << 11, 1 << 12
        };
        for (int bit : priority) {
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
            int target = OverlayState.getDpsTargetKeyCode(this);
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
        boolean keyboardDps = keyboardWindow.view != null
                && OverlayState.isKeyboardSpaceEnabled(this)
                && OverlayState.isKeyboardSpaceDpsEnabled(this);
        boolean faceDps = faceWindow.view != null && OverlayState.isAnyGamepadFaceDpsEnabled(this);
        boolean leftDps = leftShoulderWindow.view != null && OverlayState.isGamepadL1DpsEnabled(this);
        boolean rightDps = rightShoulderWindow.view != null && OverlayState.isGamepadR1DpsEnabled(this);
        boolean targetDps = dpsView != null && OverlayState.isDpsEnabled(this)
                && OverlayState.getDpsTargetKeyCode(this) != OverlayState.DPS_TARGET_NONE;
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
        if (dpsView != null) {
            dpsView.setDpsValue(OverlayState.getDpsTargetKeyCode(this) == OverlayState.DPS_TARGET_NONE ? -1 : 0);
        }
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
