package com.axon.input;

import android.view.KeyEvent;

/**
 * 手柄数字按键的统一语义层。
 *
 * 原始 /dev/input 主要负责低延迟轴数据；Android KeyEvent 负责确认 A/B/X/Y 等文字语义。
 * 这样可以避开部分手柄驱动在 BTN_NORTH / BTN_WEST 上的标签差异。
 */
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

    /**
     * X/Y 必须成组接管。若设备底层把两者的 evdev 编码对调，只覆盖一个会造成两个灯同时亮。
     */
    public static int overrideGroupForAndroidKeyCode(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            return GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_NORTH;
        }
        return fromAndroidKeyCode(keyCode);
    }
}
