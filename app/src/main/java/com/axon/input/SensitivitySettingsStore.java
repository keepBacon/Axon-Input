package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent sensitivity/overclock settings shared by UI and input proxy runtime. */
final class SensitivitySettingsStore {
    static final int MODE_SHIZUKU = 0;
    static final int MODE_ROOT = 1;

    private static final String KEY_ENABLED = "sensitivity_enabled";
    private static final String KEY_MOUSE_PERCENT = "mouse_sensitivity";
    private static final String KEY_GAMEPAD_PERCENT = "gamepad_sensitivity";
    private static final String KEY_STATUS = "sensitivity_status";
    private static final String KEY_MODE = "sensitivity_mode";

    private static final int DEFAULT_PERCENT = 100;
    private static final int MIN_PERCENT = 1;
    private static final int MAX_PERCENT = 1000;

    private SensitivitySettingsStore() {}

    static RuntimeSnapshot readRuntimeSnapshot(Context context) {
        SharedPreferences values = AppPreferences.get(context);
        return new RuntimeSnapshot(
                values.getBoolean(KEY_ENABLED, false),
                clampPercent(values.getInt(KEY_MOUSE_PERCENT, DEFAULT_PERCENT)),
                clampPercent(values.getInt(KEY_GAMEPAD_PERCENT, DEFAULT_PERCENT)),
                resolveMode(values));
    }

    static int getMode(Context context) {
        return resolveMode(AppPreferences.get(context));
    }

    static void setMode(Context context, int mode) {
        int resolved = RootBridge.isRootActive() || mode == MODE_ROOT ? MODE_ROOT : MODE_SHIZUKU;
        if (PreferenceWriter.putIntIfChanged(AppPreferences.get(context), KEY_MODE, resolved)) {
            AxonInputAccessibilityService.refreshSensitivity();
        }
    }

    static boolean isEnabled(Context context) {
        return AppPreferences.get(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        if (PreferenceWriter.putBooleanIfChanged(AppPreferences.get(context), KEY_ENABLED, enabled)) {
            AxonInputAccessibilityService.refreshSensitivity();
        }
    }

    static int getMousePercent(Context context) {
        return clampPercent(AppPreferences.get(context).getInt(KEY_MOUSE_PERCENT, DEFAULT_PERCENT));
    }

    static void setMousePercent(Context context, int percent) {
        if (PreferenceWriter.putIntIfChanged(AppPreferences.get(context), KEY_MOUSE_PERCENT, clampPercent(percent))) {
            AxonInputAccessibilityService.refreshSensitivity();
        }
    }

    static int getGamepadPercent(Context context) {
        return clampPercent(AppPreferences.get(context).getInt(KEY_GAMEPAD_PERCENT, DEFAULT_PERCENT));
    }

    static void setGamepadPercent(Context context, int percent) {
        if (PreferenceWriter.putIntIfChanged(AppPreferences.get(context), KEY_GAMEPAD_PERCENT, clampPercent(percent))) {
            AxonInputAccessibilityService.refreshSensitivity();
        }
    }

    static String getStatus(Context context) {
        String fallback = context.getString(R.string.status_disabled);
        String value = AppPreferences.get(context).getString(KEY_STATUS, fallback);
        return value == null ? fallback : value;
    }

    static void setStatus(Context context, String status) {
        PreferenceWriter.putStringIfChanged(AppPreferences.get(context), KEY_STATUS,
                status == null ? "" : status);
    }

    private static int resolveMode(SharedPreferences values) {
        if (RootBridge.isRootActive()) return MODE_ROOT;
        int mode = values.getInt(KEY_MODE, MODE_SHIZUKU);
        return mode == MODE_ROOT ? MODE_ROOT : MODE_SHIZUKU;
    }

    private static int clampPercent(int value) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, value));
    }

    static final class RuntimeSnapshot {
        final boolean enabled;
        final int mousePercent;
        final int gamepadPercent;
        final int mode;

        private RuntimeSnapshot(boolean enabled, int mousePercent, int gamepadPercent, int mode) {
            this.enabled = enabled;
            this.mousePercent = mousePercent;
            this.gamepadPercent = gamepadPercent;
            this.mode = mode;
        }
    }
}
