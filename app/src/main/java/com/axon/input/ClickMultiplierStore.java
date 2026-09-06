package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

/** 点击倍率持久配置。运行时启停状态由 Controller 管理，不写入磁盘。 */
public final class ClickMultiplierStore {
    private static final String KEY_ENABLED = "click_multiplier_enabled_v1";
    private static final String KEY_TOGGLE = "click_multiplier_toggle_v1";
    private static final String KEY_TARGET = "click_multiplier_target_v1";
    private static final String KEY_TARGET_EVDEV = "click_multiplier_target_evdev_v1";
    private static final String KEY_MULTIPLIER = "click_multiplier_value_v1";
    private static final String KEY_DELAY_MS = "click_multiplier_delay_ms_v1";

    public static final int MULTIPLIER_MIN = 1;
    public static final int MULTIPLIER_MAX = 10;
    public static final int DEFAULT_MULTIPLIER = 2;
    public static final int DELAY_MIN_MS = 0;
    public static final int DELAY_MAX_MS = 1000;
    public static final int DEFAULT_DELAY_MS = 40;

    private ClickMultiplierStore() {}

    public static boolean isEnabled(Context context) {
        return AppPreferences.get(context).getBoolean(KEY_ENABLED, false) && hasBinding(context);
    }

    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = AppPreferences.get(context);
        boolean next = enabled && hasBinding(context);
        if (prefs.getBoolean(KEY_ENABLED, false) == next) return;
        prefs.edit().putBoolean(KEY_ENABLED, next).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean hasBinding(Context context) {
        SharedPreferences prefs = AppPreferences.get(context);
        int toggle = prefs.getInt(KEY_TOGGLE, -1);
        int target = prefs.getInt(KEY_TARGET, -1);
        int evdev = prefs.getInt(KEY_TARGET_EVDEV, -1);
        return InputBinding.isValid(toggle) && InputBinding.isValid(target)
                && toggle != target && evdev > 0 && evdev <= 0x2ff;
    }

    public static int getToggleInputCode(Context context) {
        int value = AppPreferences.get(context).getInt(KEY_TOGGLE, -1);
        return InputBinding.isValid(value) ? value : -1;
    }

    public static int getTargetInputCode(Context context) {
        int value = AppPreferences.get(context).getInt(KEY_TARGET, -1);
        return InputBinding.isValid(value) ? value : -1;
    }

    public static int getTargetEvdevCode(Context context) {
        return Math.max(-1, AppPreferences.get(context).getInt(KEY_TARGET_EVDEV, -1));
    }

    public static void setBinding(Context context, int toggleInputCode, int targetInputCode, int targetEvdevCode) {
        if (!InputBinding.isValid(toggleInputCode) || !InputBinding.isValid(targetInputCode)
                || toggleInputCode == targetInputCode || targetEvdevCode <= 0 || targetEvdevCode > 0x2ff) return;
        AppPreferences.get(context).edit()
                .putInt(KEY_TOGGLE, toggleInputCode)
                .putInt(KEY_TARGET, targetInputCode)
                .putInt(KEY_TARGET_EVDEV, targetEvdevCode)
                .putBoolean(KEY_ENABLED, true)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getMultiplier(Context context) {
        return clampMultiplier(AppPreferences.get(context).getInt(KEY_MULTIPLIER, DEFAULT_MULTIPLIER));
    }

    public static void setMultiplier(Context context, int multiplier) {
        int value = clampMultiplier(multiplier);
        SharedPreferences prefs = AppPreferences.get(context);
        if (prefs.getInt(KEY_MULTIPLIER, DEFAULT_MULTIPLIER) == value) return;
        prefs.edit().putInt(KEY_MULTIPLIER, value).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getDelayMs(Context context) {
        return clampDelay(AppPreferences.get(context).getInt(KEY_DELAY_MS, DEFAULT_DELAY_MS));
    }

    public static void setDelayMs(Context context, int delayMs) {
        int value = clampDelay(delayMs);
        SharedPreferences prefs = AppPreferences.get(context);
        if (prefs.getInt(KEY_DELAY_MS, DEFAULT_DELAY_MS) == value) return;
        prefs.edit().putInt(KEY_DELAY_MS, value).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean usesToggle(Context context, int inputCode) {
        return hasBinding(context) && getToggleInputCode(context) == inputCode;
    }

    public static boolean usesTarget(Context context, int inputCode) {
        return hasBinding(context) && getTargetInputCode(context) == inputCode;
    }

    public static void clear(Context context) {
        AppPreferences.get(context).edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_TOGGLE)
                .remove(KEY_TARGET)
                .remove(KEY_TARGET_EVDEV)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    private static int clampMultiplier(int value) {
        return Math.max(MULTIPLIER_MIN, Math.min(MULTIPLIER_MAX, value));
    }

    private static int clampDelay(int value) {
        return Math.max(DELAY_MIN_MS, Math.min(DELAY_MAX_MS, value));
    }
}
