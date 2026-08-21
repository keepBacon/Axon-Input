package com.axon.input;

import android.view.KeyEvent;

/** 统一手柄按键语义。轴数据来自 /dev/input，按键名称来自 Android KeyEvent。 */
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
            default -> 0;
        };
    }

    /** X/Y 必须同时映射，避免设备编码差异造成状态错误。 */
    public static int overrideGroupForAndroidKeyCode(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            return GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_NORTH;
        }
        return fromAndroidKeyCode(keyCode);
    }
}
