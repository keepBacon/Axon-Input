package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent state for touch-to-key display regions and overlay position. */
final class TouchDisplayStore {
    static final int REGION_JOYSTICK = 0;
    static final int REGION_MOUSE_LEFT = 1;
    static final int REGION_MOUSE_RIGHT = 2;
    static final int REGION_SPACE = 3;
    static final int REGION_COUNT = 4;
    static final int COORD_FULL_DISPLAY = 2;

    private static final String KEY_ENABLED = "touch_display_enabled";
    private static final String KEY_POSITION_X = "touch_display_position_x";
    private static final String KEY_POSITION_Y = "touch_display_position_y";
    private static final String KEY_REGION_PREFIX = "touch_region_";
    private static final String KEY_COORD_VERSION = "touch_region_coord_version";

    private TouchDisplayStore() {}

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        if (!PreferenceWriter.putBooleanIfChanged(prefs(context), KEY_ENABLED, enabled)) return;
        if (enabled) AxonInputAccessibilityService.refreshActiveService();
        else AxonInputAccessibilityService.refreshDisplayVisibilityImmediate();
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
        int fallback = OverlayState.getPositionX(context, KeyOverlayView.DISPLAY_KEYBOARD);
        return clampFreePosition(prefs(context).getInt(KEY_POSITION_X, fallback));
    }

    static int getPositionY(Context context) {
        int fallback = OverlayState.getPositionY(context, KeyOverlayView.DISPLAY_KEYBOARD);
        return clampFreePosition(prefs(context).getInt(KEY_POSITION_Y, fallback));
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

    private static float normalized(int value) {
        return clamp01(value / 10000f);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clampFreePosition(int value) {
        return Math.max(-1000, Math.min(1000, value));
    }

    private static SharedPreferences prefs(Context context) {
        return AppPreferences.get(context);
    }
}
