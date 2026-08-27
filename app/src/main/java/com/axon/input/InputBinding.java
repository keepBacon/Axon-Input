package com.axon.input;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * 统一可绑定输入编码：键盘直接保留 Android KeyCode；鼠标/手柄使用高位命名空间。
 * 这样所有“录入按键”功能都能共享同一套存储、标签与触发比较逻辑。
 */
public final class InputBinding {
    private static final int TYPE_MASK = 0x70000000;
    private static final int PAYLOAD_MASK = 0x0fffffff;
    private static final int TYPE_MOUSE = 0x10000000;
    private static final int TYPE_GAMEPAD = 0x20000000;

    public static final int GAMEPAD_DPAD_UP = GamepadOverlayView.BTN_DPAD_UP;
    public static final int GAMEPAD_DPAD_DOWN = GamepadOverlayView.BTN_DPAD_DOWN;
    public static final int GAMEPAD_DPAD_LEFT = GamepadOverlayView.BTN_DPAD_LEFT;
    public static final int GAMEPAD_DPAD_RIGHT = GamepadOverlayView.BTN_DPAD_RIGHT;

    private InputBinding() {}

    public static int keyboard(int keyCode) {
        return Math.max(0, keyCode);
    }

    public static int mouse(int button) {
        return TYPE_MOUSE | (button & PAYLOAD_MASK);
    }

    public static int gamepad(int buttonBit) {
        return TYPE_GAMEPAD | (buttonBit & PAYLOAD_MASK);
    }

    public static boolean isKeyboard(int code) {
        return code >= 0 && (code & TYPE_MASK) == 0;
    }

    public static boolean isMouse(int code) {
        return (code & TYPE_MASK) == TYPE_MOUSE;
    }

    public static boolean isGamepad(int code) {
        return (code & TYPE_MASK) == TYPE_GAMEPAD;
    }

    public static int payload(int code) {
        return code & PAYLOAD_MASK;
    }

    /** Reject malformed persisted/imported binding integers before they reach render/input paths. */
    public static boolean isValid(int code) {
        if (isKeyboard(code)) return code <= 0x0000ffff;
        if (isMouse(code)) {
            int button = payload(code);
            return button >= NativeKeyEngine.MOUSE_LEFT && button <= MouseInputMonitor.BUTTON_FORWARD;
        }
        if (isGamepad(code)) {
            int bit = payload(code);
            if (bit == 0 || Integer.bitCount(bit) != 1) return false;
            int known = GamepadOverlayView.BTN_SOUTH | GamepadOverlayView.BTN_EAST
                    | GamepadOverlayView.BTN_C | GamepadOverlayView.BTN_NORTH
                    | GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_Z
                    | GamepadOverlayView.BTN_L1 | GamepadOverlayView.BTN_R1
                    | GamepadOverlayView.BTN_L2 | GamepadOverlayView.BTN_R2
                    | GamepadOverlayView.BTN_SELECT | GamepadOverlayView.BTN_START
                    | GamepadOverlayView.BTN_MODE | GamepadOverlayView.BTN_L3
                    | GamepadOverlayView.BTN_R3 | GamepadOverlayView.BTN_BACK_1
                    | GamepadOverlayView.BTN_BACK_2 | GamepadOverlayView.BTN_BACK_3
                    | GamepadOverlayView.BTN_BACK_4 | GAMEPAD_DPAD_UP | GAMEPAD_DPAD_DOWN
                    | GAMEPAD_DPAD_LEFT | GAMEPAD_DPAD_RIGHT;
            return (bit & known) != 0;
        }
        return false;
    }

    public static int fromGamepadEvent(KeyEvent event) {
        if (event == null) return -1;
        int bit = GamepadButtons.fromAndroidEvent(event);
        if (bit != 0) return gamepad(bit);
        return switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP -> gamepad(GAMEPAD_DPAD_UP);
            case KeyEvent.KEYCODE_DPAD_DOWN -> gamepad(GAMEPAD_DPAD_DOWN);
            case KeyEvent.KEYCODE_DPAD_LEFT -> gamepad(GAMEPAD_DPAD_LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT -> gamepad(GAMEPAD_DPAD_RIGHT);
            default -> -1;
        };
    }

    public static boolean isPhysicalKeyboardEvent(KeyEvent event) {
        if (event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null || device.isVirtual() || isAxonVirtualDevice(device)) return false;
        int sources = event.getSource();
        return (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                || device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_NONE;
    }

    public static boolean isPhysicalGamepadEvent(KeyEvent event) {
        if (event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null || isAxonVirtualDevice(device)) return false;
        int sources = event.getSource();
        boolean source = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        boolean mapped = fromGamepadEvent(event) >= 0;
        return (source || mapped) && !device.isVirtual();
    }

    /**
     * Mapping path also accepts a non-Axon virtual gamepad. This is required when Axon's sensitivity
     * proxy has EVIOCGRABbed the physical controller and re-emits it through uinput.
     */
    public static boolean isGamepadEventForMapping(KeyEvent event) {
        if (event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null || isAxonVirtualDevice(device)) return false;
        int sources = event.getSource();
        boolean source = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        return source || fromGamepadEvent(event) >= 0;
    }

    public static boolean isPhysicalMouseEvent(MotionEvent event) {
        if (event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null || device.isVirtual() || isAxonVirtualDevice(device)) return false;
        return (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
    }

    /** Converts one Android MotionEvent.BUTTON_* bit into the unified binding code. */
    public static int mouseFromAndroidButton(int button) {
        return switch (button) {
            case MotionEvent.BUTTON_PRIMARY -> mouse(NativeKeyEngine.MOUSE_LEFT);
            case MotionEvent.BUTTON_SECONDARY -> mouse(NativeKeyEngine.MOUSE_RIGHT);
            case MotionEvent.BUTTON_TERTIARY -> mouse(MouseInputMonitor.BUTTON_MIDDLE);
            case MotionEvent.BUTTON_BACK -> mouse(MouseInputMonitor.BUTTON_BACK);
            case MotionEvent.BUTTON_FORWARD -> mouse(MouseInputMonitor.BUTTON_FORWARD);
            default -> -1;
        };
    }

    public static int fromMouseMotionEvent(MotionEvent event) {
        if (!isPhysicalMouseEvent(event)) return -1;
        return mouseFromAndroidButton(event.getActionButton());
    }

    public static boolean isPhysicalGamepadMotionEvent(MotionEvent event) {
        if (event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null || device.isVirtual() || isAxonVirtualDevice(device)) return false;
        int sources = event.getSource();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /**
     * Buttons that Android frequently exposes only through generic-motion axes.
     * This intentionally excludes stick directions; only physical button semantics are bindable.
     */
    public static int gamepadButtonsFromMotionEvent(MotionEvent event) {
        if (!isPhysicalGamepadMotionEvent(event)) return 0;
        int buttons = 0;
        float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS));
        if (lt >= 0.5f) buttons |= GamepadOverlayView.BTN_L2;
        if (rt >= 0.5f) buttons |= GamepadOverlayView.BTN_R2;

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (hatX <= -0.5f) buttons |= GamepadOverlayView.BTN_DPAD_LEFT;
        else if (hatX >= 0.5f) buttons |= GamepadOverlayView.BTN_DPAD_RIGHT;
        if (hatY <= -0.5f) buttons |= GamepadOverlayView.BTN_DPAD_UP;
        else if (hatY >= 0.5f) buttons |= GamepadOverlayView.BTN_DPAD_DOWN;
        return buttons;
    }

    /**
     * Some Android gamepad drivers report a keyboard-like scan code for D-pad events.
     * Only trust scan codes that are actually in common Linux joystick/gamepad ranges.
     */
    private static boolean isLikelyGamepadEvdevCode(int scanCode) {
        return (scanCode >= 288 && scanCode <= 299)
                || (scanCode >= 304 && scanCode <= 318)
                || (scanCode >= 0x220 && scanCode <= 0x223)
                || (scanCode >= 704 && scanCode <= 719);
    }

    public static int evdevCode(int bindingCode, int keyboardScanCode) {
        if (isKeyboard(bindingCode)) return keyboardScanCode;
        if (isMouse(bindingCode)) {
            return switch (payload(bindingCode)) {
                case NativeKeyEngine.MOUSE_LEFT -> 0x110;   // BTN_LEFT
                case NativeKeyEngine.MOUSE_RIGHT -> 0x111;  // BTN_RIGHT
                case MouseInputMonitor.BUTTON_MIDDLE -> 0x112;
                case MouseInputMonitor.BUTTON_BACK -> 0x113; // BTN_SIDE/BACK
                case MouseInputMonitor.BUTTON_FORWARD -> 0x114; // BTN_EXTRA/FORWARD
                default -> -1;
            };
        }
        if (isGamepad(bindingCode)) {
            if (isLikelyGamepadEvdevCode(keyboardScanCode)) return keyboardScanCode;
            int bit = payload(bindingCode);
            if (bit == GamepadOverlayView.BTN_SOUTH) return 304;
            if (bit == GamepadOverlayView.BTN_EAST) return 305;
            if (bit == GamepadOverlayView.BTN_C) return 306;
            if (bit == GamepadOverlayView.BTN_NORTH) return 307;
            if (bit == GamepadOverlayView.BTN_WEST) return 308;
            if (bit == GamepadOverlayView.BTN_Z) return 309;
            if (bit == GamepadOverlayView.BTN_L1) return 310;
            if (bit == GamepadOverlayView.BTN_R1) return 311;
            if (bit == GamepadOverlayView.BTN_L2) return 312;
            if (bit == GamepadOverlayView.BTN_R2) return 313;
            if (bit == GamepadOverlayView.BTN_SELECT) return 314;
            if (bit == GamepadOverlayView.BTN_START) return 315;
            if (bit == GamepadOverlayView.BTN_MODE) return 316;
            if (bit == GamepadOverlayView.BTN_L3) return 317;
            if (bit == GamepadOverlayView.BTN_R3) return 318;
            if (bit == GamepadOverlayView.BTN_BACK_1) return 704;
            if (bit == GamepadOverlayView.BTN_BACK_2) return 705;
            if (bit == GamepadOverlayView.BTN_BACK_3) return 706;
            if (bit == GamepadOverlayView.BTN_BACK_4) return 707;
            if (bit == GAMEPAD_DPAD_UP) return 0x220;
            if (bit == GAMEPAD_DPAD_DOWN) return 0x221;
            if (bit == GAMEPAD_DPAD_LEFT) return 0x222;
            if (bit == GAMEPAD_DPAD_RIGHT) return 0x223;
        }
        return -1;
    }

    /** uinput 设备分类参数：keyboard / mouse / gamepad。 */
    public static String uinputKind(int bindingCode) {
        if (isMouse(bindingCode)) return "mouse";
        if (isGamepad(bindingCode)) return "gamepad";
        return "keyboard";
    }

    public static String label(int code) {
        if (isKeyboard(code)) return KeyLabel.fromKeyCode(code);
        if (isMouse(code)) {
            return switch (payload(code)) {
                case NativeKeyEngine.MOUSE_LEFT -> "Mouse L";
                case NativeKeyEngine.MOUSE_RIGHT -> "Mouse R";
                case MouseInputMonitor.BUTTON_MIDDLE -> "Mouse M";
                case MouseInputMonitor.BUTTON_BACK -> "Mouse Back";
                case MouseInputMonitor.BUTTON_FORWARD -> "Mouse Fwd";
                default -> "Mouse";
            };
        }
        if (isGamepad(code)) return gamepadLabel(payload(code));
        return "Unknown";
    }

    public static String gamepadLabel(int bit) {
        if (bit == GamepadOverlayView.BTN_SOUTH) return "Pad A";
        if (bit == GamepadOverlayView.BTN_EAST) return "Pad B";
        if (bit == GamepadOverlayView.BTN_WEST || bit == GamepadOverlayView.BTN_C) return "Pad X";
        if (bit == GamepadOverlayView.BTN_NORTH) return "Pad Y";
        if (bit == GamepadOverlayView.BTN_Z) return "Pad Z";
        if (bit == GamepadOverlayView.BTN_L1) return "Pad L1";
        if (bit == GamepadOverlayView.BTN_R1) return "Pad R1";
        if (bit == GamepadOverlayView.BTN_L2) return "Pad L2";
        if (bit == GamepadOverlayView.BTN_R2) return "Pad R2";
        if (bit == GamepadOverlayView.BTN_L3) return "Pad L3";
        if (bit == GamepadOverlayView.BTN_R3) return "Pad R3";
        if (bit == GamepadOverlayView.BTN_SELECT) return "Pad Select";
        if (bit == GamepadOverlayView.BTN_START) return "Pad Start";
        if (bit == GamepadOverlayView.BTN_MODE) return "Pad Mode";
        if (bit == GamepadOverlayView.BTN_BACK_1) return "Pad M1";
        if (bit == GamepadOverlayView.BTN_BACK_2) return "Pad M2";
        if (bit == GamepadOverlayView.BTN_BACK_3) return "Pad M3";
        if (bit == GamepadOverlayView.BTN_BACK_4) return "Pad M4";
        if (bit == GAMEPAD_DPAD_UP) return "Pad ↑";
        if (bit == GAMEPAD_DPAD_DOWN) return "Pad ↓";
        if (bit == GAMEPAD_DPAD_LEFT) return "Pad ←";
        if (bit == GAMEPAD_DPAD_RIGHT) return "Pad →";
        return "Gamepad";
    }

    public static boolean isAxonVirtualDevice(InputDevice device) {
        if (device == null || device.getName() == null) return false;
        return device.getName().startsWith("Axon Input Virtual")
                || "Axon Input Force Hold Keyboard".equals(device.getName());
    }
}
