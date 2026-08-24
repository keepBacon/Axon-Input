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
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 全局输入服务。读取输入并绘制悬浮层；绑定动作只旁路监听，不消费原始输入。 */
public final class AxonInputAccessibilityService extends AccessibilityService
        implements InputManager.InputDeviceListener,
        ShizukuBridge.Listener,
        MouseInputMonitor.Listener,
        KeyOverlayView.DragListener,
        KeyboardCatOverlayView.DragListener,
        FloatingVideoOverlayView.DragListener,
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
    private static final int CUSTOM_ROW_HEIGHT_DP = 50;
    private static final int CUSTOM_MIN_HEIGHT_DP = 56;
    private static final int MOUSE_WIDTH_DP = 180;
    private static final int MOUSE_HEIGHT_DP = 100;
    private static final int KEYBOARD_CAT_WIDTH_DP = 360;
    private static final int KEYBOARD_CAT_HEIGHT_DP = 208;
    private static final int KEY_PROMPT_WIDTH_DP = 332;
    private static final int KEY_PROMPT_HEIGHT_DP = 70;
    private static final int TRAJECTORY_SIZE_DP = 106;
    private static final int GAMEPAD_STICK_SIZE_DP = 116;
    private static final int GAMEPAD_FACE_SIZE_DP = 142;
    private static final int GAMEPAD_SHOULDER_WIDTH_DP = 132;
    private static final int GAMEPAD_SHOULDER_HEIGHT_DP = 86;
    private static final int GAMEPAD_BACK_WIDTH_DP = 132;
    private static final int GAMEPAD_BACK_HEIGHT_DP = 86;
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
    private Vader5ProUsbMonitor vader5UsbMonitor;
    private SensitivityProxyController sensitivityController;
    private ForceHoldController forceHoldController;
    private boolean mouseTickerRunning;
    private boolean mouseMonitorActive;
    private boolean gamepadMonitorActive;
    private boolean dpsTickerRunning;
    private final DpsTracker dpsTracker = new DpsTracker();
    private int previousGamepadButtonsForDps;
    private int previousGamepadButtonsForBindings;
    private int activeDpsTargetKeyCode = OverlayState.DPS_TARGET_NONE;
    private int proxyMouseButtons;
    private int gamepadLx, gamepadLy, gamepadRx, gamepadRy, gamepadLt, gamepadRt, gamepadButtons;
    private int rawGamepadButtons;
    private boolean vader5ProConnected;
    private boolean vader5NativeProfile;
    private boolean vader5UsbProfile;
    private int vader5UsbBackButtons;
    private int androidGamepadButtons;
    private int androidGamepadKnownMask;
    private boolean globalHtmlActive;
    private String globalHtmlContent = "";
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

    private KeyboardCatOverlayView keyboardCatView;
    private WindowManager.LayoutParams keyboardCatParams;
    private boolean keyboardCatAttached;
    private boolean keyboardCatRemoving;
    private int keyboardCatAttachRetryCount;
    private float keyboardCatDragStartRawX;
    private float keyboardCatDragStartRawY;
    private int keyboardCatDragStartWindowX;
    private int keyboardCatDragStartWindowY;

    private FloatingVideoOverlayView floatingVideoView;
    private WindowManager.LayoutParams floatingVideoParams;
    private boolean floatingVideoAttached;
    private float floatingVideoDragStartRawX;
    private float floatingVideoDragStartRawY;
    private int floatingVideoDragStartWindowX;
    private int floatingVideoDragStartWindowY;

    private final GamepadWindow leftStickWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_LEFT_STICK, "AxonInputLeftStick");
    private final GamepadWindow rightStickWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_RIGHT_STICK, "AxonInputRightStick");
    private final GamepadWindow faceWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_FACE, "AxonInputFaceButtons");
    private final GamepadWindow leftShoulderWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_LEFT_SHOULDER, "AxonInputLeftShoulder");
    private final GamepadWindow rightShoulderWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_RIGHT_SHOULDER, "AxonInputRightShoulder");
    private final GamepadWindow backWindow = new GamepadWindow(GamepadOverlayView.DISPLAY_BACK, "AxonInputBackButtons");

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

    /** Secure settings may report the service enabled before Android has actually bound it. */
    public static boolean isServiceConnected() {
        return activeService != null;
    }

    public static void refreshActiveService() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) service.applySavedState();
        else service.mainHandler.post(service::applySavedState);
    }

    /** 仅重建悬浮视频，导入新媒体时不触碰输入监听和其他悬浮层。 */
    public static void refreshFloatingVideo() {
        AxonInputAccessibilityService service = activeService;
        if (service == null) return;
        Runnable action = () -> {
            service.removeFloatingVideoImmediate();
            service.syncFloatingVideoWindow(OverlayState.isFloatingVideoEnabled(service)
                    && OverlayState.hasFloatingVideo(service));
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else service.mainHandler.post(action);
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
            service.removeKeyboardCatImmediate();
            service.removeFloatingVideoImmediate();
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
        if (isPhysicalGamepadEvent(event)) {
            int inputCode = InputBinding.fromGamepadEvent(event);
            int logicalBit = GamepadButtons.fromAndroidEvent(event);
            if (logicalBit == 0 && inputCode >= 0) {
                handleBoundInputEvent(inputCode, pressed, firstPress, event.getEventTime());
                if (firstPress) handleDirectDpsBindingInput(inputCode, event.getEventTime());
            }
            if (OverlayState.isKeyboardCatEnabled(this) && keyboardCatView != null && keyboardCatView.isGamepadStyle()) {
                keyboardCatView.setGamepadDirectional(event.getKeyCode(), pressed);
            }
            if (logicalBit != 0) {
                int knownGroup = GamepadButtons.overrideGroupForAndroidEvent(event);
                androidGamepadKnownMask |= knownGroup;
                if (pressed) androidGamepadButtons |= logicalBit;
                else androidGamepadButtons &= ~logicalBit;
                applyGamepadState(gamepadLx, gamepadLy, gamepadRx, gamepadRy, gamepadLt, gamepadRt, rawGamepadButtons);
            }
            return false;
        }

        boolean builtin = OverlayState.isEnabled(this);
        boolean inputFullKeyboard = OverlayState.isInputFullKeyboardEnabled(this);
        boolean custom = OverlayState.isCustomEnabled(this);
        boolean superCustom = OverlayState.isSuperCustomEnabled(this);
        boolean keyboardCat = OverlayState.isKeyboardCatEnabled(this);
        boolean capture = OverlayState.isCustomCaptureEnabled(this);
        boolean keyPrompt = OverlayState.isKeyPromptEnabled(this);
        boolean dpsEnabled = OverlayState.isDpsEnabled(this);
        boolean forceHold = OverlayState.isForceHoldEnabled(this) && OverlayState.hasForceHoldBinding(this);
        int expressionHotkey = OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this);
        boolean expressionUsesKeyboard = expressionHotkey >= 0 && InputBinding.isKeyboard(expressionHotkey);
        if (!builtin && !inputFullKeyboard && !custom && !superCustom && !keyboardCat && !capture
                && !keyPrompt && !dpsEnabled && !forceHold && !expressionUsesKeyboard) return false;
        if (!isPhysicalKeyboardEvent(event)) return false;

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

        int dpsTarget = OverlayState.getDpsTargetKeyCode(this);
        if (dpsEnabled && dpsTarget == OverlayState.DPS_TARGET_NONE && firstPress
                && !MainActivity.isNonDpsBindingCaptureActive()) {
            // 启用后第一次输入用于绑定，不计入 CPS。
            OverlayState.setDpsTargetKeyCode(this, keyCode);
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

    /** 所有已绑定快捷动作的统一触发入口；永远不消费物理输入。 */
    private void handleBoundInputEvent(int inputCode, boolean pressed, boolean firstPress, long eventTime) {
        if (inputCode < 0) return;

        boolean forceHold = OverlayState.isForceHoldEnabled(this) && OverlayState.hasForceHoldBinding(this);
        if (forceHold && inputCode == OverlayState.getForceHoldTriggerKeyCode(this)
                && firstPress && forceHoldController != null) {
            int targetInput = OverlayState.getForceHoldTargetKeyCode(this);
            boolean held = forceHoldController.toggleHold(
                    targetInput, OverlayState.getForceHoldTargetScanCode(this));
            if (InputBinding.isKeyboard(targetInput)) setForcedHoldVisual(targetInput, held);
            else if (!held) clearForcedHoldVisual();
        }

        int expressionHotkey = OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this);
        if (OverlayState.isKeyboardCatEnabled(this)
                && expressionHotkey >= 0 && inputCode == expressionHotkey && firstPress) {
            cycleKeyboardCatExpressionHotkey();
        }

        if (OverlayState.isCustomCaptureEnabled(this) && firstPress) {
            OverlayState.addDraftKey(this, inputCode);
        }

        if (OverlayState.isCustomEnabled(this) && customWindow.view != null && !InputBinding.isKeyboard(inputCode)) {
            customWindow.view.setCustomKeyPressed(inputCode, pressed);
        }
    }

    private void handleDirectDpsBindingInput(int inputCode, long now) {
        if (!OverlayState.isDpsEnabled(this) || inputCode < 0) return;
        int resolved = OverlayState.dpsTargetFromBinding(inputCode);
        int target = OverlayState.getDpsTargetKeyCode(this);
        if (target == OverlayState.DPS_TARGET_NONE) {
            if (MainActivity.isNonDpsBindingCaptureActive()) return;
            OverlayState.setDpsTargetKeyCode(this, resolved);
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
        if (OverlayState.getSensitivityMode(this) != OverlayState.SENSITIVITY_MODE_ROOT) {
            stopMouseMonitor();
        }
        if (OverlayState.getSensitivityMode(this) == OverlayState.SENSITIVITY_MODE_SHIZUKU) stopGamepadMonitor();
        if (sensitivityController != null) sensitivityController.onShizukuDead();
        if (forceHoldController != null && forceHoldController.isHoldRequested()) {
            forceHoldController.release();
            clearForcedHoldVisual();
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
                if (superCustomView != null && OverlayState.isSuperCustomEnabled(this)) {
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
                if (superCustomView != null && OverlayState.isSuperCustomEnabled(this)) {
                    superCustomView.setInputPressed(
                            InputBinding.mouse(NativeKeyEngine.MOUSE_RIGHT), pressed);
                }
            }
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
            if (keyboardCatView != null && OverlayState.isKeyboardCatEnabled(this)) {
                keyboardCatView.setMouseButtons(nextButtons);
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
            if (MainActivity.isNonDpsBindingCaptureActive()) return;
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
            long now = SystemClock.uptimeMillis();
            handleBoundInputEvent(InputBinding.mouse(button), pressed, pressed, now);
            MainActivity.notifyPhysicalMouseButtonForBinding(button, pressed, now);
            SuperCustomDisplayActivity.notifyPhysicalMouseButtonForBinding(button, pressed, now);
            if (superCustomView != null && OverlayState.isSuperCustomEnabled(this)) {
                superCustomView.setInputPressed(InputBinding.mouse(button), pressed);
            }
            updateCpsMouseAuxTarget(button, pressed, now);
            if (keyPromptView != null && OverlayState.isKeyPromptEnabled(this)) {
                keyPromptView.updateMouseButton(button, pressed, now);
            }
            if (keyboardCatView != null && OverlayState.isKeyboardCatEnabled(this)) {
                keyboardCatView.setMouseAuxButton(button, pressed);
            }
        });
    }

    private void updateCpsMouseAuxTarget(int button, boolean pressed, long now) {
        if (!pressed || !OverlayState.isDpsEnabled(this)) return;
        int mouseTarget = OverlayState.mouseDpsTarget(button);
        if (mouseTarget == OverlayState.DPS_TARGET_NONE) return;
        int target = OverlayState.getDpsTargetKeyCode(this);
        if (target == OverlayState.DPS_TARGET_NONE) {
            if (MainActivity.isNonDpsBindingCaptureActive()) return;
            OverlayState.setDpsTargetKeyCode(this, mouseTarget);
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
        mainHandler.post(() -> {
            if (trajectoryView != null && OverlayState.isMouseTrajectoryEnabled(this)) {
                trajectoryView.addMotion(dx, dy);
            }
            if (keyboardCatView != null && OverlayState.isKeyboardCatEnabled(this)) {
                keyboardCatView.addMouseMotion(dx, dy);
            }
        });
    }

    @Override
    public void onGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons) {
        mainHandler.post(() -> applyGamepadState(lx, ly, rx, ry, lt, rt, buttons));
    }

    @Override
    public void onGamepadProfile(boolean vader5Pro) {
        mainHandler.post(() -> {
            vader5NativeProfile = vader5Pro;
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
    public void onDragStart(KeyboardCatOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || keyboardCatParams == null) return;
        keyboardCatDragStartRawX = rawX;
        keyboardCatDragStartRawY = rawY;
        keyboardCatDragStartWindowX = keyboardCatParams.x;
        keyboardCatDragStartWindowY = keyboardCatParams.y;
    }

    @Override
    public void onDragMove(KeyboardCatOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || windowManager == null || !keyboardCatAttached
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
    public void onDragStart(FloatingVideoOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || floatingVideoParams == null) return;
        floatingVideoDragStartRawX = rawX;
        floatingVideoDragStartRawY = rawY;
        floatingVideoDragStartWindowX = floatingVideoParams.x;
        floatingVideoDragStartWindowY = floatingVideoParams.y;
    }

    @Override
    public void onDragMove(FloatingVideoOverlayView source, float rawX, float rawY) {
        if (!OverlayState.isDragEnabled(this) || windowManager == null || !floatingVideoAttached
                || floatingVideoParams == null || floatingVideoView == null) return;
        floatingVideoParams.x = floatingVideoDragStartWindowX + Math.round(rawX - floatingVideoDragStartRawX);
        floatingVideoParams.y = floatingVideoDragStartWindowY + Math.round(rawY - floatingVideoDragStartRawY);
        windowManager.updateViewLayout(floatingVideoView, floatingVideoParams);
    }

    @Override
    public void onDragEnd(FloatingVideoOverlayView source) {
        saveFloatingVideoPosition();
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
        stopVader5UsbMonitor();
        stopDpsTicker();
        if (sensitivityController != null) {
            sensitivityController.destroy();
            sensitivityController = null;
        }
        if (forceHoldController != null) {
            forceHoldController.destroy();
            forceHoldController = null;
        }
        forceHoldVisualActive = false;
        forceHoldVisualKeyCode = -1;
        physicalKeyboardKeysDown.clear();
        forceHoldVirtualDeviceIds.clear();
        otherAxonVirtualDeviceIds.clear();
        mainHandler.removeCallbacksAndMessages(null);
        removeWindowImmediate(keyboardWindow);
        removeWindowImmediate(customWindow);
        removeWindowImmediate(mouseWindow);
        removeKeyboardCatImmediate();
        removeFloatingVideoImmediate();
        removeSuperCustomImmediate();
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
        return !device.isVirtual() || OverlayState.isSensitivityEnabled(this);
    }

    private void applySavedState() {
        globalHtmlActive = OverlayState.isGlobalHtmlEnabled(this) && OverlayState.hasGlobalHtml(this);
        globalHtmlContent = globalHtmlActive ? OverlayState.loadGlobalHtml(this) : "";
        if (globalHtmlContent.isEmpty()) globalHtmlActive = false;

        syncWindow(keyboardWindow, OverlayState.isEnabled(this));
        syncWindow(customWindow, OverlayState.isCustomEnabled(this));
        syncWindow(mouseWindow, OverlayState.isMouseEnabled(this));
        syncKeyboardCatWindow(OverlayState.isKeyboardCatEnabled(this));
        syncFloatingVideoWindow(OverlayState.isFloatingVideoEnabled(this) && OverlayState.hasFloatingVideo(this));
        syncSuperCustomWindow(OverlayState.isSuperCustomEnabled(this));
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
        syncGamepadWindow(backWindow, OverlayState.isGamepadBackEnabled(this));
        syncVader5UsbMonitor();

        boolean forceHoldEnabled = OverlayState.isForceHoldEnabled(this)
                && OverlayState.hasForceHoldBinding(this);
        int forceHoldTargetKey = OverlayState.getForceHoldTargetKeyCode(this);
        int forceHoldTargetScan = OverlayState.getForceHoldTargetScanCode(this);
        if (forceHoldVisualActive
                && (!forceHoldEnabled || forceHoldVisualKeyCode != forceHoldTargetKey)) {
            clearForcedHoldVisual();
        }
        if (forceHoldController != null) {
            forceHoldController.applyConfiguration(forceHoldEnabled, forceHoldTargetKey, forceHoldTargetScan);
        }
        if (forceHoldVisualActive) {
            applyForcedHoldVisualState(forceHoldVisualKeyCode, true);
        }

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
        if (inputFullKeyboardView != null) {
            inputFullKeyboardView.setKeyAppearance(
                    OverlayState.getKeyStyle(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                    OverlayState.getKeyPressColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setKeyBaseColor(
                    OverlayState.getKeyBaseColor(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setCornerStrength(
                    OverlayState.getKeyCornerStrength(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
            inputFullKeyboardView.setLayerOpacities(
                    OverlayState.getKeyBackgroundOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                    OverlayState.getKeyStrokeOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                    OverlayState.getKeyTextOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
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
        inputFullKeyboardView.setCornerStrength(
                OverlayState.getKeyCornerStrength(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
        inputFullKeyboardView.setLayerOpacities(
                OverlayState.getKeyBackgroundOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                OverlayState.getKeyStrokeOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD),
                OverlayState.getKeyTextOpacity(this, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD));
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
        window.view.setDragEnabled(OverlayState.isDragEnabled(this));
        window.view.setDisplaySize(displaySizePercent(window.type));
        window.view.setAlpha(1f);
        window.view.setLayerOpacities(
                OverlayState.getKeyBackgroundOpacity(this, window.type),
                OverlayState.getKeyStrokeOpacity(this, window.type),
                OverlayState.getKeyTextOpacity(this, window.type));
        window.view.setAnimationMode(OverlayState.getMotionMode(this, window.type));
        window.view.setKeyAppearance(
                OverlayState.getKeyStyle(this, window.type),
                OverlayState.getKeyPressColor(this, window.type));
        window.view.setKeyBaseColor(OverlayState.getKeyBaseColor(this, window.type));
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
        else if (OverlayState.isKeyboardSpaceEnabled(this)) {
            base = KEYBOARD_HEIGHT_DP + Math.max(0, OverlayState.getKeyboardSpacing(this) - 8) * 2;
        } else {
            base = 128 + Math.max(0, OverlayState.getKeyboardSpacing(this) - 8);
        }
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
            keyboardCatView.setDragEnabled(OverlayState.isDragEnabled(this));
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
            view.setDragEnabled(OverlayState.isDragEnabled(this));
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
        keyboardCatView.setDragEnabled(OverlayState.isDragEnabled(this));
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
        window.view.setDragEnabled(OverlayState.isDragEnabled(this));
        window.view.setDisplaySize(OverlayState.getGamepadDisplaySize(this, window.type));
        window.view.setAlpha(OverlayState.getDisplayOpacity(this, window.type) / 100f);
        if (window.type == GamepadOverlayView.DISPLAY_FACE
                || window.type == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || window.type == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER
                || window.type == GamepadOverlayView.DISPLAY_BACK) {
            window.view.setKeyAppearance(
                    OverlayState.getKeyStyle(this, window.type),
                    OverlayState.getKeyPressColor(this, window.type));
            window.view.setKeyBaseColor(OverlayState.getKeyBaseColor(this, window.type));
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
                : (type == GamepadOverlayView.DISPLAY_FACE ? GAMEPAD_FACE_SIZE_DP : GAMEPAD_STICK_SIZE_DP));
        return Math.max(1, dp(base * OverlayState.getGamepadDisplaySize(this, type) / 100f));
    }

    private int gamepadHeightPx(int type) {
        int base = (type == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || type == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER)
                ? GAMEPAD_SHOULDER_HEIGHT_DP
                : (type == GamepadOverlayView.DISPLAY_BACK ? GAMEPAD_BACK_HEIGHT_DP
                : (type == GamepadOverlayView.DISPLAY_FACE ? GAMEPAD_FACE_SIZE_DP : GAMEPAD_STICK_SIZE_DP));
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
        if (backWindow.view == source) return backWindow;
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
        int effectiveButtons = mergeGamepadButtons(buttons | vader5UsbBackButtons);

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
        if (OverlayState.isGamepadCustomSwapEnabled(this)) {
            int first = OverlayState.getGamepadCustomSwapFirst(this);
            int second = OverlayState.getGamepadCustomSwapSecond(this);
            if (first != 0 && second != 0 && first != second) {
                effectiveButtons = swapGamepadButtonGroups(effectiveButtons, first, second);
            }
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
        pushGamepadState(leftShoulderWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(rightShoulderWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        pushGamepadState(backWindow, lx, ly, rx, ry, lt, rt, effectiveButtons);
        if (keyboardCatView != null && OverlayState.isKeyboardCatEnabled(this) && keyboardCatView.isGamepadStyle()) {
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
            handleBoundInputEvent(inputCode, pressed, pressed, now);
            if (superCustomView != null && OverlayState.isSuperCustomEnabled(this)) {
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

    private void syncFloatingVideoWindow(boolean enabled) {
        if (!enabled || !OverlayState.hasFloatingVideo(this)) {
            removeFloatingVideoImmediate();
            return;
        }
        ensureFloatingVideoWindow();
        updateFloatingVideoLayout();
    }

    private void ensureFloatingVideoWindow() {
        if (floatingVideoAttached || windowManager == null || !OverlayState.hasFloatingVideo(this)) return;
        FloatingVideoOverlayView view = new FloatingVideoOverlayView(this);
        view.setDragListener(this);
        view.setDragEnabled(OverlayState.isDragEnabled(this));
        view.setVideoFile(OverlayState.getFloatingVideoFile(this), OverlayState.getFloatingVideoLoopDurationMs(this));
        floatingVideoView = view;

        int[] size = floatingVideoSizePx();
        floatingVideoParams = new WindowManager.LayoutParams(
                size[0], size[1],
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                windowFlags(), PixelFormat.TRANSLUCENT);
        floatingVideoParams.gravity = Gravity.TOP | Gravity.START;
        floatingVideoParams.setTitle("AxonInputFloatingVideo");
        applyFloatingVideoPosition();
        windowManager.addView(view, floatingVideoParams);
        floatingVideoAttached = true;
    }

    private void updateFloatingVideoLayout() {
        if (!floatingVideoAttached || floatingVideoView == null || floatingVideoParams == null
                || windowManager == null) return;
        int[] size = floatingVideoSizePx();
        floatingVideoParams.width = size[0];
        floatingVideoParams.height = size[1];
        floatingVideoParams.flags = windowFlags();
        floatingVideoView.setDragEnabled(OverlayState.isDragEnabled(this));
        floatingVideoView.setLoopDurationMs(OverlayState.getFloatingVideoLoopDurationMs(this));
        applyFloatingVideoPosition();
        windowManager.updateViewLayout(floatingVideoView, floatingVideoParams);
    }

    private int[] floatingVideoSizePx() {
        int sourceWidth = Math.max(1, OverlayState.getFloatingVideoWidth(this));
        int sourceHeight = Math.max(1, OverlayState.getFloatingVideoHeight(this));
        float aspect = sourceWidth / (float) sourceHeight;
        if (!Float.isFinite(aspect) || aspect <= 0.05f || aspect >= 20f) aspect = 16f / 9f;
        float maxWidthDp = 260f;
        float maxHeightDp = 220f;
        float widthDp = maxWidthDp;
        float heightDp = widthDp / aspect;
        if (heightDp > maxHeightDp) {
            heightDp = maxHeightDp;
            widthDp = heightDp * aspect;
        }
        widthDp = Math.max(96f, widthDp);
        heightDp = Math.max(72f, heightDp);
        return new int[]{Math.max(1, dp(widthDp)), Math.max(1, dp(heightDp))};
    }

    private void applyFloatingVideoPosition() {
        if (floatingVideoParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - floatingVideoParams.width);
        int maxY = Math.max(0, metrics.heightPixels - floatingVideoParams.height);
        floatingVideoParams.x = Math.round(maxX * (OverlayState.getPositionX(
                this, FloatingVideoOverlayView.DISPLAY_FLOATING_VIDEO) / 100f));
        floatingVideoParams.y = Math.round(maxY * (OverlayState.getPositionY(
                this, FloatingVideoOverlayView.DISPLAY_FLOATING_VIDEO) / 100f));
    }

    private void saveFloatingVideoPosition() {
        if (floatingVideoParams == null) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int maxX = Math.max(0, metrics.widthPixels - floatingVideoParams.width);
        int maxY = Math.max(0, metrics.heightPixels - floatingVideoParams.height);
        int x = maxX == 0 ? 0 : Math.round((floatingVideoParams.x / (float) maxX) * 100f);
        int y = maxY == 0 ? 0 : Math.round((floatingVideoParams.y / (float) maxY) * 100f);
        OverlayState.savePosition(this, FloatingVideoOverlayView.DISPLAY_FLOATING_VIDEO, x, y);
    }

    private void removeFloatingVideoImmediate() {
        FloatingVideoOverlayView view = floatingVideoView;
        floatingVideoAttached = false;
        floatingVideoView = null;
        floatingVideoParams = null;
        if (view != null) view.release();
        if (view != null && windowManager != null) {
            try { windowManager.removeViewImmediate(view); } catch (Throwable ignored) {}
        }
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
            keyPromptView.setLayerOpacities(
                    OverlayState.getKeyBackgroundOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyStrokeOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyTextOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setKeyAppearance(
                    OverlayState.getKeyStyle(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyPressColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
            keyPromptView.setKeyBaseColor(
                    OverlayState.getKeyBaseColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
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
        keyPromptView.setDragEnabled(OverlayState.isDragEnabled(this));
        keyPromptView.setDisplaySize(OverlayState.getKeyPromptSize(this));
        keyPromptView.setLayerOpacities(
                    OverlayState.getKeyBackgroundOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyStrokeOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyTextOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyAppearance(
                OverlayState.getKeyStyle(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                OverlayState.getKeyPressColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyBaseColor(
                OverlayState.getKeyBaseColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
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
        keyPromptView.setDragEnabled(OverlayState.isDragEnabled(this));
        keyPromptView.setDisplaySize(OverlayState.getKeyPromptSize(this));
        keyPromptView.setLayerOpacities(
                    OverlayState.getKeyBackgroundOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyStrokeOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                    OverlayState.getKeyTextOpacity(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyAppearance(
                OverlayState.getKeyStyle(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT),
                OverlayState.getKeyPressColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setKeyBaseColor(
                OverlayState.getKeyBaseColor(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
        keyPromptView.setCornerStrength(
                OverlayState.getKeyCornerStrength(this, KeyPromptOverlayView.DISPLAY_KEY_PROMPT));
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
        removeViewBestEffort(trajectoryView);
        trajectoryAttached = false;
        trajectoryView = null;
        trajectoryParams = null;
    }

    private void startMouseMonitor() {
        if (!needsMouseMonitor()) return;
        int mode = OverlayState.getSensitivityMode(this);
        if (mode != OverlayState.SENSITIVITY_MODE_ROOT
                && (!ShizukuBridge.isReady() || !ShizukuBridge.hasPermission())) return;
        if (mouseMonitor == null) mouseMonitor = new MouseInputMonitor(this, this);
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
                && (target == OverlayState.DPS_TARGET_NONE || OverlayState.isMouseDpsTarget(target));
        boolean keyboardCatNeedsMouse = OverlayState.isKeyboardCatEnabled(this)
                && !BongoCatStyleManager.isSelectedGamepad(this);
        boolean bindingNeedsMouse = InputBinding.isMouse(OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this))
                || InputBinding.isMouse(OverlayState.getForceHoldTriggerKeyCode(this))
                || customBindingsContainMouse()
                || (OverlayState.isSuperCustomEnabled(this)
                && SuperCustomConfigStore.activeContainsMouse(this));
        return !OverlayState.isSensitivityEnabled(this)
                && (MainActivity.isBindingActivityActive()
                || SuperCustomDisplayActivity.isBindingActivityActive()
                || OverlayState.isMouseEnabled(this) || OverlayState.isMouseTrajectoryEnabled(this)
                || keyboardCatNeedsMouse || OverlayState.isKeyPromptEnabled(this)
                || cpsNeedsMouse || bindingNeedsMouse);
    }

    private boolean needsGamepadMonitor() {
        int target = OverlayState.getDpsTargetKeyCode(this);
        boolean cpsNeedsGamepad = OverlayState.isDpsEnabled(this)
                && (target == OverlayState.DPS_TARGET_NONE || OverlayState.isGamepadDpsTarget(target));
        boolean keyboardCatGamepad = OverlayState.isKeyboardCatEnabled(this)
                && BongoCatStyleManager.isSelectedGamepad(this);
        boolean bindingNeedsGamepad = InputBinding.isGamepad(OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this))
                || InputBinding.isGamepad(OverlayState.getForceHoldTriggerKeyCode(this))
                || customBindingsContainGamepad()
                || (OverlayState.isSuperCustomEnabled(this)
                && SuperCustomConfigStore.activeContainsGamepad(this));
        return !OverlayState.isSensitivityEnabled(this)
                && (OverlayState.isAnyGamepadDisplayEnabled(this) || keyboardCatGamepad
                || cpsNeedsGamepad || bindingNeedsGamepad);
    }

    private boolean customBindingsContainMouse() {
        if (!OverlayState.isCustomEnabled(this) && !OverlayState.isCustomCaptureEnabled(this)) return false;
        int[] keys = OverlayState.isCustomCaptureEnabled(this)
                ? OverlayState.getCustomDraftKeyCodes(this) : OverlayState.getCustomKeyCodes(this);
        for (int key : keys) if (InputBinding.isMouse(key)) return true;
        return OverlayState.isCustomCaptureEnabled(this);
    }

    private boolean customBindingsContainGamepad() {
        if (!OverlayState.isCustomEnabled(this) && !OverlayState.isCustomCaptureEnabled(this)) return false;
        int[] keys = OverlayState.isCustomCaptureEnabled(this)
                ? OverlayState.getCustomDraftKeyCodes(this) : OverlayState.getCustomKeyCodes(this);
        for (int key : keys) if (InputBinding.isGamepad(key)) return true;
        return OverlayState.isCustomCaptureEnabled(this);
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
        int rising = (~previous) & current & 0x00ffffff;
        if (rising == 0) return;

        int target = OverlayState.getDpsTargetKeyCode(this);
        if (target == OverlayState.DPS_TARGET_NONE) {
            if (MainActivity.isNonDpsBindingCaptureActive()) return;
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
                GamepadOverlayView.BTN_SELECT, GamepadOverlayView.BTN_START, GamepadOverlayView.BTN_MODE,
                GamepadOverlayView.BTN_BACK_1, GamepadOverlayView.BTN_BACK_2,
                GamepadOverlayView.BTN_BACK_3, GamepadOverlayView.BTN_BACK_4,
                GamepadOverlayView.BTN_DPAD_UP, GamepadOverlayView.BTN_DPAD_DOWN,
                GamepadOverlayView.BTN_DPAD_LEFT, GamepadOverlayView.BTN_DPAD_RIGHT
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
        previousGamepadButtonsForBindings = 0;
        rawGamepadButtons = 0;
        androidGamepadButtons = 0;
        androidGamepadKnownMask = 0;
        physicalKeyboardKeysDown.clear();
        int mask = NativeKeyEngine.nativeReset();
        long mouseStats = NativeKeyEngine.nativeResetMouse(SystemClock.uptimeMillis());
        if (keyboardWindow.view != null) keyboardWindow.view.setPressedMask(mask);
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
        if (keyboardCatView != null && OverlayState.isKeyboardCatEnabled(this)) {
            keyboardCatView.setKeyState(keyCode, pressed);
        }
        if (keyPromptView != null && OverlayState.isKeyPromptEnabled(this)) {
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
        if (superCustomView != null && OverlayState.isSuperCustomEnabled(this)) {
            superCustomView.setInputPressed(InputBinding.keyboard(keyCode), pressed);
        }
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
