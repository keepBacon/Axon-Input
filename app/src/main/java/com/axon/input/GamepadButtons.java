package com.axon.input;

import android.view.KeyEvent;

/** 统一手柄按键语义。优先使用物理扫描码。 */
public final class GamepadButtons {
    private GamepadButtons() {}

    public static int fromAndroidKeyCode(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A -> GamepadOverlayView.BTN_SOUTH;
            case KeyEvent.KEYCODE_BUTTON_B -> GamepadOverlayView.BTN_EAST;
            case KeyEvent.KEYCODE_BUTTON_X -> GamepadOverlayView.BTN_WEST;
            case KeyEvent.KEYCODE_BUTTON_Y -> GamepadOverlayView.BTN_NORTH;
            case KeyEvent.KEYCODE_BUTTON_L1 -> GamepadOverlayView.BTN_L1;
            case KeyEvent.KEYCODE_BUTTON_R1 -> GamepadOverlayView.BTN_R1;
            case KeyEvent.KEYCODE_BUTTON_L2 -> GamepadOverlayView.BTN_L2;
            case KeyEvent.KEYCODE_BUTTON_R2 -> GamepadOverlayView.BTN_R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadOverlayView.BTN_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadOverlayView.BTN_R3;
            // 部分蓝牙手柄只上报通用 BUTTON_1..16。
            case KeyEvent.KEYCODE_BUTTON_1 -> GamepadOverlayView.BTN_SOUTH;
            case KeyEvent.KEYCODE_BUTTON_2 -> GamepadOverlayView.BTN_EAST;
            case KeyEvent.KEYCODE_BUTTON_3 -> GamepadOverlayView.BTN_WEST;
            case KeyEvent.KEYCODE_BUTTON_4 -> GamepadOverlayView.BTN_NORTH;
            case KeyEvent.KEYCODE_BUTTON_5 -> GamepadOverlayView.BTN_L1;
            case KeyEvent.KEYCODE_BUTTON_6 -> GamepadOverlayView.BTN_R1;
            case KeyEvent.KEYCODE_BUTTON_7 -> GamepadOverlayView.BTN_L2;
            case KeyEvent.KEYCODE_BUTTON_8 -> GamepadOverlayView.BTN_R2;
            case KeyEvent.KEYCODE_BUTTON_11 -> GamepadOverlayView.BTN_L3;
            case KeyEvent.KEYCODE_BUTTON_12 -> GamepadOverlayView.BTN_R3;
            default -> 0;
        };
    }

    /** Linux evdev 标准扫描码。用于修正厂商错误 KeyCode。 */
    private static int fromScanCode(int scanCode) {
        return switch (scanCode) {
            // 旧式 HID/蓝牙手柄可能使用 joystick 按键扫描码。
            case 288 -> GamepadOverlayView.BTN_SOUTH; // BTN_TRIGGER
            case 289 -> GamepadOverlayView.BTN_EAST;  // BTN_THUMB
            case 290 -> GamepadOverlayView.BTN_WEST;  // BTN_THUMB2
            case 291 -> GamepadOverlayView.BTN_NORTH; // BTN_TOP
            case 292 -> GamepadOverlayView.BTN_L1;    // BTN_TOP2
            case 293 -> GamepadOverlayView.BTN_R1;    // BTN_PINKIE
            case 294 -> GamepadOverlayView.BTN_L2;    // BTN_BASE
            case 295 -> GamepadOverlayView.BTN_R2;    // BTN_BASE2
            case 304 -> GamepadOverlayView.BTN_SOUTH; // BTN_SOUTH
            case 305 -> GamepadOverlayView.BTN_EAST;  // BTN_EAST
            case 307 -> GamepadOverlayView.BTN_NORTH; // BTN_NORTH
            case 308 -> GamepadOverlayView.BTN_WEST;  // BTN_WEST
            case 310 -> GamepadOverlayView.BTN_L1;    // BTN_TL
            case 311 -> GamepadOverlayView.BTN_R1;    // BTN_TR
            case 312 -> GamepadOverlayView.BTN_L2;    // BTN_TL2
            case 313 -> GamepadOverlayView.BTN_R2;    // BTN_TR2
            case 317 -> GamepadOverlayView.BTN_L3;    // BTN_THUMBL
            case 318 -> GamepadOverlayView.BTN_R3;    // BTN_THUMBR
            default -> 0;
        };
    }

    private static boolean isCanonicalFaceKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_BUTTON_X
                || keyCode == KeyEvent.KEYCODE_BUTTON_Y;
    }

    public static int fromAndroidEvent(KeyEvent event) {
        if (event == null) return 0;
        int keyCode = event.getKeyCode();
        // ABXY 优先使用 Android 语义。部分手柄的物理扫描码与面键文字不一致。
        if (isCanonicalFaceKeyCode(keyCode)) return fromAndroidKeyCode(keyCode);
        // 肩键和扳机优先扫描码，避免厂商错误 KeyCode 导致串键。
        int scanMapped = fromScanCode(event.getScanCode());
        if (scanMapped != 0) return scanMapped;
        return fromAndroidKeyCode(keyCode);
    }

    public static int overrideGroupForAndroidEvent(KeyEvent event) {
        if (event == null) return 0;
        int keyCode = event.getKeyCode();
        if (isCanonicalFaceKeyCode(keyCode)) return fromAndroidKeyCode(keyCode);
        int scanMapped = fromScanCode(event.getScanCode());
        if (scanMapped != 0) return scanMapped;
        return fromAndroidKeyCode(keyCode);
    }
}
