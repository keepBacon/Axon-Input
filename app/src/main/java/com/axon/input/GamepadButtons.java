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
            case KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadOverlayView.BTN_SELECT;
            case KeyEvent.KEYCODE_BUTTON_START -> GamepadOverlayView.BTN_START;
            case KeyEvent.KEYCODE_BUTTON_MODE -> GamepadOverlayView.BTN_MODE;
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
            case KeyEvent.KEYCODE_BUTTON_13 -> GamepadOverlayView.BTN_BACK_1;
            case KeyEvent.KEYCODE_BUTTON_14 -> GamepadOverlayView.BTN_BACK_2;
            case KeyEvent.KEYCODE_BUTTON_15 -> GamepadOverlayView.BTN_BACK_3;
            case KeyEvent.KEYCODE_BUTTON_16 -> GamepadOverlayView.BTN_BACK_4;
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
            case 314 -> GamepadOverlayView.BTN_SELECT; // BTN_SELECT
            case 315 -> GamepadOverlayView.BTN_START;  // BTN_START
            case 316 -> GamepadOverlayView.BTN_MODE;   // BTN_MODE
            case 317 -> GamepadOverlayView.BTN_L3;    // BTN_THUMBL
            case 318 -> GamepadOverlayView.BTN_R3;    // BTN_THUMBR
            case 296, 704 -> GamepadOverlayView.BTN_BACK_1; // BTN_BASE3 / TRIGGER_HAPPY1
            case 297, 705 -> GamepadOverlayView.BTN_BACK_2; // BTN_BASE4 / TRIGGER_HAPPY2
            case 298, 706 -> GamepadOverlayView.BTN_BACK_3; // BTN_BASE5 / TRIGGER_HAPPY3
            case 299, 707 -> GamepadOverlayView.BTN_BACK_4; // BTN_BASE6 / TRIGGER_HAPPY4
            // Flydigi Vader 5 Pro / Linux SDL paddle range: M1..M4.
            case 708 -> GamepadOverlayView.BTN_BACK_1; // BTN_TRIGGER_HAPPY5 / M1
            case 709 -> GamepadOverlayView.BTN_BACK_2; // BTN_TRIGGER_HAPPY6 / M2
            case 710 -> GamepadOverlayView.BTN_BACK_3; // BTN_TRIGGER_HAPPY7 / M3
            case 711 -> GamepadOverlayView.BTN_BACK_4; // BTN_TRIGGER_HAPPY8 / M4
            default -> 0;
        };
    }

    private static boolean isCanonicalFaceKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_BUTTON_X
                || keyCode == KeyEvent.KEYCODE_BUTTON_Y;
    }

    /**
     * 肩键/扳机/摇杆按下/背键必须以 Linux 物理扫描码为准。
     *
     * 黑武士 5 Pro 等部分厂商手柄会出现“LB 的 Android KeyCode 被报告成 BUTTON_X，
     * 但 ScanCode 仍然正确为 BTN_TL(310)”的情况。如果先相信 BUTTON_X，就会造成 LB 串 X。
     * 面键仍保留 Android 语义优先，只有明确属于非面键组的扫描码才抢占 KeyCode。
     */
    private static boolean isAuthoritativeNonFaceScanBit(int bit) {
        return bit == GamepadOverlayView.BTN_L1
                || bit == GamepadOverlayView.BTN_R1
                || bit == GamepadOverlayView.BTN_L2
                || bit == GamepadOverlayView.BTN_R2
                || bit == GamepadOverlayView.BTN_L3
                || bit == GamepadOverlayView.BTN_R3
                || bit == GamepadOverlayView.BTN_BACK_1
                || bit == GamepadOverlayView.BTN_BACK_2
                || bit == GamepadOverlayView.BTN_BACK_3
                || bit == GamepadOverlayView.BTN_BACK_4;
    }

    private static int resolveAndroidEventBit(KeyEvent event) {
        if (event == null) return 0;
        int keyCode = event.getKeyCode();
        int scanMapped = fromScanCode(event.getScanCode());

        // 先处理物理非面键。修复 Vader 5 Pro LB(scan 310) + BUTTON_X 的串键。
        if (isAuthoritativeNonFaceScanBit(scanMapped)) return scanMapped;

        // ABXY 继续优先使用 Android 的语义映射，避免不同平台的面键扫描码布局差异。
        if (isCanonicalFaceKeyCode(keyCode)) return fromAndroidKeyCode(keyCode);

        if (scanMapped != 0) return scanMapped;
        return fromAndroidKeyCode(keyCode);
    }

    public static int fromAndroidEvent(KeyEvent event) {
        return resolveAndroidEventBit(event);
    }

    public static int overrideGroupForAndroidEvent(KeyEvent event) {
        return resolveAndroidEventBit(event);
    }
}
