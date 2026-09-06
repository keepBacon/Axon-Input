package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/** Persistent state for touch-to-key display regions and overlay position. */
final class TouchDisplayStore {
    static final int REGION_JOYSTICK = 0;
    static final int REGION_MOUSE_LEFT = 1;
    static final int REGION_MOUSE_RIGHT = 2;
    static final int REGION_SPACE = 3;
    static final int REGION_COUNT = 4;
    static final int COORD_FULL_DISPLAY = 2;

    static final int MODE_KEYBOARD_MOUSE = 0;
    static final int MODE_GAMEPAD = 1;
    static final int MODE_TOUCH = 2;

    static final int STICK_UNSET = -1;
    static final int STICK_LEFT = 0;
    static final int STICK_RIGHT = 1;

    static final int TARGET_RMB = 0;
    static final int TARGET_LMB = 1;
    static final int TARGET_SPACE = 2;

    private static final String KEY_ENABLED = "touch_display_enabled";
    private static final String KEY_POSITION_X = "touch_display_position_x";
    private static final String KEY_POSITION_Y = "touch_display_position_y";
    private static final String KEY_REGION_PREFIX = "touch_region_";
    private static final String KEY_COORD_VERSION = "touch_region_coord_version";
    private static final String KEY_MODE = "touch_display_mode"; // legacy: 0=gamepad, 1=touch
    private static final String KEY_REGULAR_MODE = "regular_display_mode";
    private static final String KEY_GAMEPAD_STICK = "touch_display_gamepad_stick";
    private static final String KEY_GAMEPAD_BINDING_PREFIX = "touch_display_gamepad_binding_";

    private TouchDisplayStore() {}

    /**
     * 兼容旧“触屏按显”开关。现在唯一的启用状态由“常规按显”主开关负责，
     * 这里仅保留旧 API 以免旧配置/调用链出现双状态。
     */
    static boolean isEnabled(Context context) {
        return OverlayState.isEnabled(context);
    }

    static void setEnabled(Context context, boolean enabled) {
        OverlayState.setEnabled(context, enabled);
    }

    static int getMode(Context context) {
        SharedPreferences values = prefs(context);
        if (values.contains(KEY_REGULAR_MODE)) {
            int mode = values.getInt(KEY_REGULAR_MODE, MODE_KEYBOARD_MOUSE);
            return mode == MODE_GAMEPAD || mode == MODE_TOUCH ? mode : MODE_KEYBOARD_MOUSE;
        }

        // 一次性迁移旧独立“触屏按显”：旧功能启用时保留其 gamepad/touch 模式，
        // 否则新“常规按显”默认继续使用键鼠输入，避免升级后行为突变。
        boolean legacyEnabled = values.getBoolean(KEY_ENABLED, false);
        int migrated = MODE_KEYBOARD_MOUSE;
        if (legacyEnabled) {
            int legacy = values.getInt(KEY_MODE, 1);
            migrated = legacy == 0 ? MODE_GAMEPAD : MODE_TOUCH;
        }
        // 迁移完成后移除旧双状态键，避免后续代码再次把“触屏按显”当成第二个主开关。
        values.edit()
                .putInt(KEY_REGULAR_MODE, migrated)
                .remove(KEY_ENABLED)
                .remove(KEY_MODE)
                .apply();
        // 旧触屏按显如果原本开启，升级后应继续保持“常规按显”开启。这里先写入新模式，
        // 再触发一次主开关迁移，refresh 回调进入下一轮时不会重复执行。
        if (legacyEnabled && !OverlayState.isEnabled(context)) {
            OverlayState.setEnabled(context, true);
        }
        return migrated;
    }

    static boolean isKeyboardMouseMode(Context context) {
        return getMode(context) == MODE_KEYBOARD_MOUSE;
    }

    static boolean isTouchMode(Context context) {
        return getMode(context) == MODE_TOUCH;
    }

    static boolean isGamepadMode(Context context) {
        return getMode(context) == MODE_GAMEPAD;
    }

    static boolean isTouchCaptureEnabled(Context context) {
        return OverlayState.isEnabled(context) && isTouchMode(context);
    }

    static void setMode(Context context, int mode) {
        int normalized = mode == MODE_GAMEPAD ? MODE_GAMEPAD
                : mode == MODE_TOUCH ? MODE_TOUCH : MODE_KEYBOARD_MOUSE;
        if (!PreferenceWriter.putIntIfChanged(prefs(context), KEY_REGULAR_MODE, normalized)) return;
        AxonInputAccessibilityService.refreshActiveService();
    }

    static int getGamepadStick(Context context) {
        int stick = prefs(context).getInt(KEY_GAMEPAD_STICK, STICK_UNSET);
        return stick == STICK_LEFT || stick == STICK_RIGHT ? stick : STICK_UNSET;
    }

    static int getGamepadBindingKeyCode(Context context, int target) {
        validateGamepadTarget(target);
        return prefs(context).getInt(KEY_GAMEPAD_BINDING_PREFIX + target + "_key", KeyEvent.KEYCODE_UNKNOWN);
    }

    static int getGamepadBindingScanCode(Context context, int target) {
        validateGamepadTarget(target);
        return prefs(context).getInt(KEY_GAMEPAD_BINDING_PREFIX + target + "_scan", 0);
    }

    static void saveGamepadConfig(Context context, int stick, int[] keyCodes, int[] scanCodes) {
        int normalizedStick = stick == STICK_LEFT || stick == STICK_RIGHT ? stick : STICK_UNSET;
        if (normalizedStick == STICK_UNSET || keyCodes == null || keyCodes.length < 3) {
            throw new IllegalArgumentException("incomplete gamepad config");
        }
        SharedPreferences.Editor editor = prefs(context).edit().putInt(KEY_GAMEPAD_STICK, normalizedStick);
        for (int target = TARGET_RMB; target <= TARGET_SPACE; target++) {
            int keyCode = Math.max(KeyEvent.KEYCODE_UNKNOWN, keyCodes[target]);
            int scanCode = scanCodes != null && target < scanCodes.length ? Math.max(0, scanCodes[target]) : 0;
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN
                    || GamepadButtons.fromStoredKey(keyCode, scanCode) == 0) {
                throw new IllegalArgumentException("incomplete gamepad config");
            }
            editor.putInt(KEY_GAMEPAD_BINDING_PREFIX + target + "_key", keyCode);
            editor.putInt(KEY_GAMEPAD_BINDING_PREFIX + target + "_scan", scanCode);
        }
        editor.apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    static int getGamepadBindingButtonBit(Context context, int target) {
        int keyCode = getGamepadBindingKeyCode(context, target);
        int scanCode = getGamepadBindingScanCode(context, target);
        return GamepadButtons.fromStoredKey(keyCode, scanCode);
    }

    static boolean isGamepadConfigComplete(Context context) {
        if (getGamepadStick(context) == STICK_UNSET) return false;
        return getGamepadBindingButtonBit(context, TARGET_RMB) != 0
                && getGamepadBindingButtonBit(context, TARGET_LMB) != 0
                && getGamepadBindingButtonBit(context, TARGET_SPACE) != 0;
    }

    static float[] getRegion(Context context, int region) {
        validateRegion(region);
        float[] defaults = defaultRegion(region);
        SharedPreferences values = prefs(context);
        String base = KEY_REGION_PREFIX + region + "_";
        return new float[]{
                normalized(values.getInt(base + "l", Math.round(defaults[0] * 10000f))),
                normalized(values.getInt(base + "t", Math.round(defaults[1] * 10000f))),
                normalized(values.getInt(base + "r", Math.round(defaults[2] * 10000f))),
                normalized(values.getInt(base + "b", Math.round(defaults[3] * 10000f)))
        };
    }

    static void setRegion(Context context, int region, float left, float top, float right, float bottom) {
        validateRegion(region);
        float l = clamp01(left);
        float t = clamp01(top);
        float r = clamp01(right);
        float b = clamp01(bottom);
        if (r < l) { float swap = l; l = r; r = swap; }
        if (b < t) { float swap = t; t = b; b = swap; }
        if (r - l < 0.03f) r = Math.min(1f, l + 0.03f);
        if (b - t < 0.03f) b = Math.min(1f, t + 0.03f);

        int nextL = Math.round(l * 10000f);
        int nextT = Math.round(t * 10000f);
        int nextR = Math.round(r * 10000f);
        int nextB = Math.round(b * 10000f);
        String base = KEY_REGION_PREFIX + region + "_";
        SharedPreferences values = prefs(context);
        if (values.getInt(base + "l", Integer.MIN_VALUE) == nextL
                && values.getInt(base + "t", Integer.MIN_VALUE) == nextT
                && values.getInt(base + "r", Integer.MIN_VALUE) == nextR
                && values.getInt(base + "b", Integer.MIN_VALUE) == nextB) return;
        values.edit()
                .putInt(base + "l", nextL)
                .putInt(base + "t", nextT)
                .putInt(base + "r", nextR)
                .putInt(base + "b", nextB)
                .apply();
    }


    static void setCoordinateVersion(Context context, int version) {
        PreferenceWriter.putIntIfChanged(prefs(context), KEY_COORD_VERSION, Math.max(1, version));
    }

    static int getPositionX(Context context) {
        // 触屏/手柄按显复用键盘按显配置，位置也只有一个权威来源。旧独立位置值仅保留兼容，不再读取。
        return clampFreePosition(OverlayState.getPositionX(context, KeyOverlayView.DISPLAY_KEYBOARD));
    }

    static int getPositionY(Context context) {
        return clampFreePosition(OverlayState.getPositionY(context, KeyOverlayView.DISPLAY_KEYBOARD));
    }


    private static float[] defaultRegion(int region) {
        return switch (region) {
            case REGION_JOYSTICK -> new float[]{0.035f, 0.36f, 0.39f, 0.94f};
            case REGION_MOUSE_LEFT -> new float[]{0.56f, 0.45f, 0.76f, 0.75f};
            case REGION_MOUSE_RIGHT -> new float[]{0.78f, 0.45f, 0.98f, 0.75f};
            case REGION_SPACE -> new float[]{0.59f, 0.79f, 0.97f, 0.96f};
            default -> throw new IllegalArgumentException("touch region");
        };
    }

    private static void validateRegion(int region) {
        if (region < 0 || region >= REGION_COUNT) throw new IllegalArgumentException("touch region");
    }

    private static void validateGamepadTarget(int target) {
        if (target < TARGET_RMB || target > TARGET_SPACE) {
            throw new IllegalArgumentException("gamepad target");
        }
    }

    private static float normalized(int value) {
        return clamp01(value / 10000f);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clampFreePosition(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static SharedPreferences prefs(Context context) {
        return AppPreferences.get(context);
    }
}
