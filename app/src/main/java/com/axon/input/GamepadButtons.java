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
            case KeyEvent.KEYCODE_DPAD_UP -> GamepadOverlayView.BTN_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN -> GamepadOverlayView.BTN_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT -> GamepadOverlayView.BTN_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadOverlayView.BTN_DPAD_RIGHT;
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

    public static int toAndroidKeyCode(int bit) {
        if (bit == GamepadOverlayView.BTN_SOUTH) return KeyEvent.KEYCODE_BUTTON_A;
        if (bit == GamepadOverlayView.BTN_EAST) return KeyEvent.KEYCODE_BUTTON_B;
        if (bit == GamepadOverlayView.BTN_WEST || bit == GamepadOverlayView.BTN_C) return KeyEvent.KEYCODE_BUTTON_X;
        if (bit == GamepadOverlayView.BTN_NORTH) return KeyEvent.KEYCODE_BUTTON_Y;
        if (bit == GamepadOverlayView.BTN_L1) return KeyEvent.KEYCODE_BUTTON_L1;
        if (bit == GamepadOverlayView.BTN_R1) return KeyEvent.KEYCODE_BUTTON_R1;
        if (bit == GamepadOverlayView.BTN_L2) return KeyEvent.KEYCODE_BUTTON_L2;
        if (bit == GamepadOverlayView.BTN_R2) return KeyEvent.KEYCODE_BUTTON_R2;
        if (bit == GamepadOverlayView.BTN_L3) return KeyEvent.KEYCODE_BUTTON_THUMBL;
        if (bit == GamepadOverlayView.BTN_R3) return KeyEvent.KEYCODE_BUTTON_THUMBR;
        if (bit == GamepadOverlayView.BTN_SELECT) return KeyEvent.KEYCODE_BUTTON_SELECT;
        if (bit == GamepadOverlayView.BTN_START) return KeyEvent.KEYCODE_BUTTON_START;
        if (bit == GamepadOverlayView.BTN_MODE) return KeyEvent.KEYCODE_BUTTON_MODE;
        if (bit == GamepadOverlayView.BTN_DPAD_UP) return KeyEvent.KEYCODE_DPAD_UP;
        if (bit == GamepadOverlayView.BTN_DPAD_DOWN) return KeyEvent.KEYCODE_DPAD_DOWN;
        if (bit == GamepadOverlayView.BTN_DPAD_LEFT) return KeyEvent.KEYCODE_DPAD_LEFT;
        if (bit == GamepadOverlayView.BTN_DPAD_RIGHT) return KeyEvent.KEYCODE_DPAD_RIGHT;
        return KeyEvent.KEYCODE_UNKNOWN;
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
            case 306 -> GamepadOverlayView.BTN_WEST;  // BTN_C: legacy/alternate X
            case 307 -> GamepadOverlayView.BTN_NORTH; // BTN_NORTH
            case 308 -> GamepadOverlayView.BTN_WEST;  // BTN_WEST
            case 309 -> GamepadOverlayView.BTN_EAST;  // BTN_Z: legacy/alternate B
            case 310 -> GamepadOverlayView.BTN_L1;    // BTN_TL
            case 311 -> GamepadOverlayView.BTN_R1;    // BTN_TR
            case 312 -> GamepadOverlayView.BTN_L2;    // BTN_TL2
            case 313 -> GamepadOverlayView.BTN_R2;    // BTN_TR2
            case 314 -> GamepadOverlayView.BTN_SELECT; // BTN_SELECT
            case 315 -> GamepadOverlayView.BTN_START;  // BTN_START
            case 316 -> GamepadOverlayView.BTN_MODE;   // BTN_MODE
            case 317 -> GamepadOverlayView.BTN_L3;    // BTN_THUMBL
            case 318 -> GamepadOverlayView.BTN_R3;    // BTN_THUMBR
            case 0x220 -> GamepadOverlayView.BTN_DPAD_UP;
            case 0x221 -> GamepadOverlayView.BTN_DPAD_DOWN;
            case 0x222 -> GamepadOverlayView.BTN_DPAD_LEFT;
            case 0x223 -> GamepadOverlayView.BTN_DPAD_RIGHT;
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

    private static boolean isCanonicalModernScanCode(int scanCode) {
        return switch (scanCode) {
            case 304, 305, 306, 307, 308, 309, // SOUTH/EAST/C/NORTH/WEST/Z
                    310, 311, 312, 313, 314, 315, 316, 317, 318,
                    0x220, 0x221, 0x222, 0x223,
                    296, 297, 298, 299,
                    704, 705, 706, 707, 708, 709, 710, 711 -> true;
            default -> false;
        };
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
                || bit == GamepadOverlayView.BTN_BACK_4
                || bit == GamepadOverlayView.BTN_DPAD_UP
                || bit == GamepadOverlayView.BTN_DPAD_DOWN
                || bit == GamepadOverlayView.BTN_DPAD_LEFT
                || bit == GamepadOverlayView.BTN_DPAD_RIGHT;
    }

    /**
     * Resolves a persisted Android KeyCode + Linux ScanCode pair back to one physical gamepad bit.
     * The precedence intentionally matches resolveAndroidEventBit(): canonical evdev scans win,
     * so OEM aliases such as L1 reported as BUTTON_X do not light the wrong target.
     */
    public static int fromStoredKey(int keyCode, int scanCode) {
        int scanMapped = fromScanCode(scanCode);
        if (scanMapped != 0 && isCanonicalModernScanCode(scanCode)) return scanMapped;
        if (isCanonicalFaceKeyCode(keyCode)) return fromAndroidKeyCode(keyCode);
        if (isAuthoritativeNonFaceScanBit(scanMapped)) return scanMapped;
        if (scanMapped != 0) return scanMapped;
        return fromAndroidKeyCode(keyCode);
    }

    private static int resolveAndroidEventBit(KeyEvent event) {
        if (event == null) return 0;
        int keyCode = event.getKeyCode();
        int scanMapped = fromScanCode(event.getScanCode());

        // Linux canonical gamepad scan codes are physical semantics, including ABXY. Treat them
        // as authoritative before Android KeyCode aliases. This fixes stacks that report L1 as
        // BUTTON_X and also the inverse case where a real X/R1 edge gets a generic Android code.
        if (scanMapped != 0 && isCanonicalModernScanCode(event.getScanCode())) return scanMapped;

        // Legacy joystick scans (288..295) are not layout-stable across vendors. For those, keep
        // Android's canonical ABXY meaning when available and use the scan only as a fallback.
        if (isCanonicalFaceKeyCode(keyCode)) return fromAndroidKeyCode(keyCode);
        if (isAuthoritativeNonFaceScanBit(scanMapped)) return scanMapped;
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
