package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent gamepad input semantics and per-button DPS/progress behavior. */
final class GamepadSettingsStore {
    static final int COMPAT_AUTO = 0;
    static final int COMPAT_LOOSE = 1;
    static final int COMPAT_ANDROID = 2;
    static final int COMPAT_EVDEV = 3;

    private static final int LEGACY_COMPAT_SWAP_XY = 4;
    private static final int LEGACY_COMPAT_SWAP_AB = 5;
    private static final int LEGACY_COMPAT_SWAP_FACE = 6;

    private static final String KEY_COMPATIBILITY_MODE = "gamepad_compatibility_mode";
    private static final String KEY_SWAP_XY = "gamepad_swap_xy";
    private static final String KEY_SWAP_AB = "gamepad_swap_ab";
    private static final String KEY_SWAP_STICKS = "gamepad_swap_sticks";
    private static final String KEY_SWAP_TRIGGERS = "gamepad_swap_triggers";
    private static final String KEY_CUSTOM_SWAP_ENABLED = "gamepad_custom_swap_enabled";
    private static final String KEY_CUSTOM_SWAP_FIRST = "gamepad_custom_swap_first";
    private static final String KEY_CUSTOM_SWAP_SECOND = "gamepad_custom_swap_second";
    private static final String KEY_FACE_Y_DPS = "gamepad_face_y_dps";
    private static final String KEY_FACE_X_DPS = "gamepad_face_x_dps";
    private static final String KEY_FACE_B_DPS = "gamepad_face_b_dps";
    private static final String KEY_FACE_A_DPS = "gamepad_face_a_dps";
    private static final String KEY_L2_PROGRESS = "gamepad_l2_progress";
    private static final String KEY_R2_PROGRESS = "gamepad_r2_progress";
    private static final String KEY_L1_DPS = "gamepad_l1_dps";
    private static final String KEY_R1_DPS = "gamepad_r1_dps";

    private GamepadSettingsStore() {}

    static int getCompatibilityMode(Context context) {
        return readCompatibilityMode(AppPreferences.get(context));
    }


    static RuntimeSnapshot readRuntimeSnapshot(Context context) {
        SharedPreferences values = AppPreferences.get(context);
        int compatibilityMode = readCompatibilityMode(values);
        int customFirst = sanitizeSwapBit(values.getInt(KEY_CUSTOM_SWAP_FIRST, 0));
        int customSecond = sanitizeSwapBit(values.getInt(KEY_CUSTOM_SWAP_SECOND, 0));
        boolean customSwap = values.getBoolean(KEY_CUSTOM_SWAP_ENABLED, false)
                && customFirst != 0 && customSecond != 0 && customFirst != customSecond;
        boolean faceDps = values.getBoolean(KEY_FACE_Y_DPS, false)
                || values.getBoolean(KEY_FACE_X_DPS, false)
                || values.getBoolean(KEY_FACE_B_DPS, false)
                || values.getBoolean(KEY_FACE_A_DPS, false);
        return new RuntimeSnapshot(
                compatibilityMode,
                values.getBoolean(KEY_SWAP_XY, false),
                values.getBoolean(KEY_SWAP_AB, false),
                values.getBoolean(KEY_SWAP_STICKS, false),
                values.getBoolean(KEY_SWAP_TRIGGERS, false),
                customSwap,
                customSwap ? customFirst : 0,
                customSwap ? customSecond : 0,
                faceDps,
                values.getBoolean(KEY_L1_DPS, false),
                values.getBoolean(KEY_R1_DPS, false));
    }

    static final class RuntimeSnapshot {
        final int compatibilityMode;
        final boolean swapXY;
        final boolean swapAB;
        final boolean swapSticks;
        final boolean swapTriggers;
        final boolean customSwapEnabled;
        final int customSwapFirst;
        final int customSwapSecond;
        final boolean faceDpsEnabled;
        final boolean leftShoulderDpsEnabled;
        final boolean rightShoulderDpsEnabled;

        private RuntimeSnapshot(int compatibilityMode, boolean swapXY, boolean swapAB,
                                boolean swapSticks, boolean swapTriggers,
                                boolean customSwapEnabled, int customSwapFirst, int customSwapSecond,
                                boolean faceDpsEnabled, boolean leftShoulderDpsEnabled,
                                boolean rightShoulderDpsEnabled) {
            this.compatibilityMode = compatibilityMode;
            this.swapXY = swapXY;
            this.swapAB = swapAB;
            this.swapSticks = swapSticks;
            this.swapTriggers = swapTriggers;
            this.customSwapEnabled = customSwapEnabled;
            this.customSwapFirst = customSwapFirst;
            this.customSwapSecond = customSwapSecond;
            this.faceDpsEnabled = faceDpsEnabled;
            this.leftShoulderDpsEnabled = leftShoulderDpsEnabled;
            this.rightShoulderDpsEnabled = rightShoulderDpsEnabled;
        }
    }

    static void setCompatibilityMode(Context context, int mode) {
        int value = mode >= COMPAT_AUTO && mode <= COMPAT_EVDEV ? mode : COMPAT_AUTO;
        putIntAndRefresh(context, KEY_COMPATIBILITY_MODE, value);
    }

    static boolean isSwapXY(Context context) {
        getCompatibilityMode(context);
        return AppPreferences.get(context).getBoolean(KEY_SWAP_XY, false);
    }

    static void setSwapXY(Context context, boolean enabled) {
        putBooleanAndRefresh(context, KEY_SWAP_XY, enabled);
    }

    static boolean isSwapAB(Context context) {
        getCompatibilityMode(context);
        return AppPreferences.get(context).getBoolean(KEY_SWAP_AB, false);
    }

    static void setSwapAB(Context context, boolean enabled) {
        putBooleanAndRefresh(context, KEY_SWAP_AB, enabled);
    }

    static boolean isSwapSticks(Context context) {
        getCompatibilityMode(context);
        return AppPreferences.get(context).getBoolean(KEY_SWAP_STICKS, false);
    }

    static void setSwapSticks(Context context, boolean enabled) {
        putBooleanAndRefresh(context, KEY_SWAP_STICKS, enabled);
    }

    static boolean isSwapTriggers(Context context) {
        getCompatibilityMode(context);
        return AppPreferences.get(context).getBoolean(KEY_SWAP_TRIGGERS, false);
    }

    static void setSwapTriggers(Context context, boolean enabled) {
        putBooleanAndRefresh(context, KEY_SWAP_TRIGGERS, enabled);
    }

    static boolean isCustomSwapEnabled(Context context) {
        int first = getCustomSwapFirst(context);
        int second = getCustomSwapSecond(context);
        return first != 0 && second != 0 && first != second
                && AppPreferences.get(context).getBoolean(KEY_CUSTOM_SWAP_ENABLED, false);
    }

    static int getCustomSwapFirst(Context context) {
        return sanitizeSwapBit(AppPreferences.get(context).getInt(KEY_CUSTOM_SWAP_FIRST, 0));
    }

    static int getCustomSwapSecond(Context context) {
        return sanitizeSwapBit(AppPreferences.get(context).getInt(KEY_CUSTOM_SWAP_SECOND, 0));
    }

    static void setCustomSwapEnabled(Context context, boolean enabled) {
        int first = getCustomSwapFirst(context);
        int second = getCustomSwapSecond(context);
        putBooleanAndRefresh(context, KEY_CUSTOM_SWAP_ENABLED,
                enabled && first != 0 && second != 0 && first != second);
    }

    static void setCustomSwapPair(Context context, int first, int second) {
        int cleanFirst = sanitizeSwapBit(first);
        int cleanSecond = sanitizeSwapBit(second);
        boolean valid = cleanFirst != 0 && cleanSecond != 0 && cleanFirst != cleanSecond;
        int nextFirst = valid ? cleanFirst : 0;
        int nextSecond = valid ? cleanSecond : 0;
        SharedPreferences values = AppPreferences.get(context);
        if (values.getInt(KEY_CUSTOM_SWAP_FIRST, 0) == nextFirst
                && values.getInt(KEY_CUSTOM_SWAP_SECOND, 0) == nextSecond
                && values.getBoolean(KEY_CUSTOM_SWAP_ENABLED, false) == valid) return;
        values.edit()
                .putInt(KEY_CUSTOM_SWAP_FIRST, nextFirst)
                .putInt(KEY_CUSTOM_SWAP_SECOND, nextSecond)
                .putBoolean(KEY_CUSTOM_SWAP_ENABLED, valid)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    static void resetCompatibility(Context context) {
        SharedPreferences values = AppPreferences.get(context);
        if (values.getInt(KEY_COMPATIBILITY_MODE, COMPAT_AUTO) == COMPAT_AUTO
                && !values.getBoolean(KEY_SWAP_XY, false)
                && !values.getBoolean(KEY_SWAP_AB, false)
                && !values.getBoolean(KEY_SWAP_STICKS, false)
                && !values.getBoolean(KEY_SWAP_TRIGGERS, false)
                && !values.getBoolean(KEY_CUSTOM_SWAP_ENABLED, false)
                && values.getInt(KEY_CUSTOM_SWAP_FIRST, 0) == 0
                && values.getInt(KEY_CUSTOM_SWAP_SECOND, 0) == 0) return;
        values.edit()
                .putInt(KEY_COMPATIBILITY_MODE, COMPAT_AUTO)
                .putBoolean(KEY_SWAP_XY, false)
                .putBoolean(KEY_SWAP_AB, false)
                .putBoolean(KEY_SWAP_STICKS, false)
                .putBoolean(KEY_SWAP_TRIGGERS, false)
                .putBoolean(KEY_CUSTOM_SWAP_ENABLED, false)
                .putInt(KEY_CUSTOM_SWAP_FIRST, 0)
                .putInt(KEY_CUSTOM_SWAP_SECOND, 0)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    static boolean isFaceYDpsEnabled(Context context) { return getBoolean(context, KEY_FACE_Y_DPS); }
    static boolean isFaceXDpsEnabled(Context context) { return getBoolean(context, KEY_FACE_X_DPS); }
    static boolean isFaceBDpsEnabled(Context context) { return getBoolean(context, KEY_FACE_B_DPS); }
    static boolean isFaceADpsEnabled(Context context) { return getBoolean(context, KEY_FACE_A_DPS); }
    static boolean isL2ProgressEnabled(Context context) { return getBoolean(context, KEY_L2_PROGRESS); }
    static boolean isR2ProgressEnabled(Context context) { return getBoolean(context, KEY_R2_PROGRESS); }
    static boolean isL1DpsEnabled(Context context) { return getBoolean(context, KEY_L1_DPS); }
    static boolean isR1DpsEnabled(Context context) { return getBoolean(context, KEY_R1_DPS); }

    static void setFaceYDpsEnabled(Context context, boolean enabled) { putBooleanAndRefresh(context, KEY_FACE_Y_DPS, enabled); }
    static void setFaceXDpsEnabled(Context context, boolean enabled) { putBooleanAndRefresh(context, KEY_FACE_X_DPS, enabled); }
    static void setFaceBDpsEnabled(Context context, boolean enabled) { putBooleanAndRefresh(context, KEY_FACE_B_DPS, enabled); }
    static void setFaceADpsEnabled(Context context, boolean enabled) { putBooleanAndRefresh(context, KEY_FACE_A_DPS, enabled); }
    static void setL2ProgressEnabled(Context context, boolean enabled) { putBooleanAndRefresh(context, KEY_L2_PROGRESS, enabled); }
    static void setR2ProgressEnabled(Context context, boolean enabled) { putBooleanAndRefresh(context, KEY_R2_PROGRESS, enabled); }
    static void setL1DpsEnabled(Context context, boolean enabled) { putBooleanAndRefresh(context, KEY_L1_DPS, enabled); }
    static void setR1DpsEnabled(Context context, boolean enabled) { putBooleanAndRefresh(context, KEY_R1_DPS, enabled); }

    static boolean isAnyFaceDpsEnabled(Context context) {
        return isFaceYDpsEnabled(context) || isFaceXDpsEnabled(context)
                || isFaceBDpsEnabled(context) || isFaceADpsEnabled(context);
    }

    private static int readCompatibilityMode(SharedPreferences preferences) {
        int mode = preferences.getInt(KEY_COMPATIBILITY_MODE, COMPAT_AUTO);
        if (mode >= LEGACY_COMPAT_SWAP_XY && mode <= LEGACY_COMPAT_SWAP_FACE) {
            SharedPreferences.Editor editor = preferences.edit().putInt(KEY_COMPATIBILITY_MODE, COMPAT_AUTO);
            if (mode == LEGACY_COMPAT_SWAP_XY || mode == LEGACY_COMPAT_SWAP_FACE) {
                editor.putBoolean(KEY_SWAP_XY, true);
            }
            if (mode == LEGACY_COMPAT_SWAP_AB || mode == LEGACY_COMPAT_SWAP_FACE) {
                editor.putBoolean(KEY_SWAP_AB, true);
            }
            editor.apply();
            return COMPAT_AUTO;
        }
        return mode >= COMPAT_AUTO && mode <= COMPAT_EVDEV ? mode : COMPAT_AUTO;
    }

    private static boolean getBoolean(Context context, String key) {
        return AppPreferences.get(context).getBoolean(key, false);
    }

    private static void putBooleanAndRefresh(Context context, String key, boolean value) {
        if (PreferenceWriter.putBooleanIfChanged(AppPreferences.get(context), key, value)) {
            AxonInputAccessibilityService.refreshActiveService();
        }
    }

    private static void putIntAndRefresh(Context context, String key, int value) {
        if (PreferenceWriter.putIntIfChanged(AppPreferences.get(context), key, value)) {
            AxonInputAccessibilityService.refreshActiveService();
        }
    }

    private static int sanitizeSwapBit(int bit) {
        if (bit == 0 || Integer.bitCount(bit) != 1) return 0;
        int allowed = GamepadOverlayView.BTN_SOUTH | GamepadOverlayView.BTN_EAST
                | GamepadOverlayView.BTN_C | GamepadOverlayView.BTN_NORTH
                | GamepadOverlayView.BTN_WEST | GamepadOverlayView.BTN_Z
                | GamepadOverlayView.BTN_L1 | GamepadOverlayView.BTN_R1
                | GamepadOverlayView.BTN_L2 | GamepadOverlayView.BTN_R2
                | GamepadOverlayView.BTN_SELECT | GamepadOverlayView.BTN_START
                | GamepadOverlayView.BTN_MODE | GamepadOverlayView.BTN_L3
                | GamepadOverlayView.BTN_R3 | GamepadOverlayView.BTN_BACK_1
                | GamepadOverlayView.BTN_BACK_2 | GamepadOverlayView.BTN_BACK_3
                | GamepadOverlayView.BTN_BACK_4 | GamepadOverlayView.BTN_DPAD_UP
                | GamepadOverlayView.BTN_DPAD_DOWN | GamepadOverlayView.BTN_DPAD_LEFT
                | GamepadOverlayView.BTN_DPAD_RIGHT;
        return (bit & allowed) != 0 ? bit : 0;
    }
}
