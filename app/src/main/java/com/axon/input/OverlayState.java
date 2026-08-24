package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.AtomicFile;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 运行配置入口。普通设置保留到任务退出，长期数据单独保存。 */
public final class OverlayState {
    static final int MAX_GLOBAL_HTML_BYTES = 4 * 1024 * 1024;
    static final long MAX_FLOATING_VIDEO_BYTES = 256L * 1024L * 1024L;
    private static final String GLOBAL_HTML_FILE = "global_display.html";
    private static final String FLOATING_VIDEO_FILE = "floating_video_media";
    public static final int MOTION_SIZE = 0;
    public static final int MOTION_ALPHA = 1;
    public static final int MOTION_NONE = 2;
    public static final int MOTION_RIPPLE = 3;
    public static final int UI_THEME_LIGHT = 0;
    public static final int UI_THEME_BLACK = 1;
    public static final int SENSITIVITY_MODE_SHIZUKU = 0;
    public static final int SENSITIVITY_MODE_ROOT = 1;
    public static final int DPS_TARGET_NONE = -1;
    public static final int DPS_TARGET_MOUSE_LEFT = 0x10000;
    public static final int DPS_TARGET_MOUSE_RIGHT = 0x10001;
    public static final int DPS_TARGET_MOUSE_MIDDLE = 0x10002;
    public static final int DPS_TARGET_MOUSE_BACK = 0x10003;
    public static final int DPS_TARGET_MOUSE_FORWARD = 0x10004;
    public static final int DPS_TARGET_GAMEPAD_BASE = 0x20000; // legacy v1.6 encoding
    private static final int DPS_TARGET_GAMEPAD_EXT_BASE = 0x02000000;
    private static final String SESSION_PREFS = "axon_input_session";
    private static final String DURABLE_PREFS = "key_display_durable";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MOUSE_ENABLED = "mouse_enabled";
    private static final String KEY_KEYBOARD_CAT_ENABLED = "keyboard_cat_enabled";
    private static final String KEY_KEYBOARD_CAT_MOUSE_MODE = "keyboard_cat_mouse_mode";
    private static final String KEY_KEYBOARD_CAT_GLOBAL_REVERSE = "keyboard_cat_global_reverse";
    private static final String KEY_KEYBOARD_CAT_STYLE_ID = "keyboard_cat_style_id";
    private static final String KEY_KEYBOARD_CAT_DEBUG_EXPRESSION = "keyboard_cat_debug_expression";
    private static final String KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_KEY_CODE = "keyboard_cat_expression_hotkey_key_code";
    private static final String KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_SELECTION_PREFIX = "keyboard_cat_expression_hotkey_selection_";
    private static final String KEY_INPUT_FULL_KEYBOARD_ENABLED = "input_full_keyboard_enabled";
    private static final String KEY_KEY_PROMPT_ENABLED = "key_prompt_enabled";
    private static final String KEY_MOUSE_TRAJECTORY_ENABLED = "mouse_trajectory_enabled";
    private static final String KEY_CUSTOM_ENABLED = "custom_enabled";
    // v1.6fix introduces an explicit user-facing display switch. Use a new preference key so
    // installs upgraded from the previous auto-display build start with this new switch OFF rather
    // than inheriting the old hidden runtime flag that loadSlotIntoActive() used to force on.
    private static final String KEY_SUPER_CUSTOM_ENABLED = "super_custom_display_enabled_v2";
    private static final String KEY_DRAG_ENABLED = "drag_enabled";
    private static final String KEY_FLOATING_VIDEO_ENABLED = "floating_video_enabled";
    private static final String KEY_FLOATING_VIDEO_NAME = "floating_video_name";
    private static final String KEY_FLOATING_VIDEO_SOURCE_DURATION = "floating_video_source_duration";
    private static final String KEY_FLOATING_VIDEO_LOOP_DURATION = "floating_video_loop_duration";
    private static final String KEY_FLOATING_VIDEO_WIDTH = "floating_video_width";
    private static final String KEY_FLOATING_VIDEO_HEIGHT = "floating_video_height";
    private static final String KEY_FORCE_HOLD_ENABLED = "force_hold_enabled";
    private static final String KEY_FORCE_HOLD_TARGET_KEY_CODE = "force_hold_target_key_code";
    private static final String KEY_FORCE_HOLD_TARGET_SCAN_CODE = "force_hold_target_scan_code";
    private static final String KEY_FORCE_HOLD_TRIGGER_KEY_CODE = "force_hold_trigger_key_code";
    private static final String KEY_DPS_ENABLED = "dps_enabled";
    private static final String KEY_DPS_TARGET_KEY_CODE = "dps_target_key_code";
    private static final String KEY_DPS_OPACITY = "dps_opacity";
    private static final String KEY_DPS_POSITION_X = "dps_position_x";
    private static final String KEY_DPS_POSITION_Y = "dps_position_y";
    private static final String KEY_CUSTOM_CAPTURE = "custom_capture";
    private static final String KEY_CUSTOM_KEYS = "custom_keys";
    private static final String KEY_CUSTOM_DRAFT = "custom_draft";
    private static final String KEY_CUSTOM_COLUMNS = "custom_columns";
    private static final String KEY_KEYBOARD_SIZE = "keyboard_size";
    private static final String KEY_KEYBOARD_SPACING = "keyboard_spacing";
    private static final String KEY_KEYBOARD_SPACE_ENABLED = "keyboard_space_enabled";
    private static final String KEY_KEYBOARD_SPACE_DPS_ENABLED = "keyboard_space_dps_enabled";
    private static final String KEY_CUSTOM_SIZE = "custom_size";
    private static final String KEY_CUSTOM_SPACING = "custom_spacing";
    private static final String KEY_MOUSE_SIZE = "mouse_size";
    private static final String KEY_KEYBOARD_CAT_SIZE = "keyboard_cat_size";
    private static final String KEY_KEY_PROMPT_SIZE = "key_prompt_size";
    private static final String KEY_MOUSE_TRAJECTORY_SIZE = "mouse_trajectory_size";
    private static final String KEY_MOUSE_TRAJECTORY_DOT_SIZE = "mouse_trajectory_dot_size";
    private static final String KEY_KEYBOARD_OPACITY = "keyboard_opacity";
    private static final String KEY_MOUSE_OPACITY = "mouse_opacity";
    private static final String KEY_KEYBOARD_CAT_OPACITY = "keyboard_cat_opacity";
    private static final String KEY_KEY_PROMPT_OPACITY = "key_prompt_opacity";
    private static final String KEY_MOUSE_TRAJECTORY_OPACITY = "mouse_trajectory_opacity";
    private static final String KEY_CUSTOM_OPACITY = "custom_opacity";
    private static final String KEY_KEYBOARD_KEY_STYLE = "keyboard_key_style";
    private static final String KEY_MOUSE_KEY_STYLE = "mouse_key_style";
    private static final String KEY_KEY_PROMPT_KEY_STYLE = "key_prompt_key_style";
    private static final String KEY_FULL_KEYBOARD_KEY_STYLE = "full_keyboard_key_style";
    private static final String KEY_CUSTOM_KEY_STYLE = "custom_key_style";
    private static final String KEY_GAMEPAD_FACE_KEY_STYLE = "gamepad_face_key_style";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_KEY_STYLE = "gamepad_left_shoulder_key_style";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_KEY_STYLE = "gamepad_right_shoulder_key_style";
    private static final String KEY_KEYBOARD_CORNER_STRENGTH = "keyboard_corner_strength";
    private static final String KEY_MOUSE_CORNER_STRENGTH = "mouse_corner_strength";
    private static final String KEY_KEY_PROMPT_CORNER_STRENGTH = "key_prompt_corner_strength";
    private static final String KEY_FULL_KEYBOARD_CORNER_STRENGTH = "full_keyboard_corner_strength";
    private static final String KEY_CUSTOM_CORNER_STRENGTH = "custom_corner_strength";
    private static final String KEY_GAMEPAD_FACE_CORNER_STRENGTH = "gamepad_face_corner_strength";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_CORNER_STRENGTH = "gamepad_left_shoulder_corner_strength";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_CORNER_STRENGTH = "gamepad_right_shoulder_corner_strength";
    private static final String KEY_KEYBOARD_BASE_COLOR = "keyboard_base_color";
    private static final String KEY_KEYBOARD_PRESS_COLOR = "keyboard_press_color";
    private static final String KEY_KEYBOARD_TEXT_COLOR = "keyboard_text_color";
    private static final String KEY_MOUSE_BASE_COLOR = "mouse_base_color";
    private static final String KEY_MOUSE_PRESS_COLOR = "mouse_press_color";
    private static final String KEY_KEY_PROMPT_BASE_COLOR = "key_prompt_base_color";
    private static final String KEY_KEY_PROMPT_PRESS_COLOR = "key_prompt_press_color";
    private static final String KEY_FULL_KEYBOARD_BASE_COLOR = "full_keyboard_base_color";
    private static final String KEY_FULL_KEYBOARD_PRESS_COLOR = "full_keyboard_press_color";
    private static final String KEY_CUSTOM_BASE_COLOR = "custom_base_color";
    private static final String KEY_CUSTOM_PRESS_COLOR = "custom_press_color";
    private static final String KEY_GAMEPAD_FACE_BASE_COLOR = "gamepad_face_base_color";
    private static final String KEY_GAMEPAD_FACE_PRESS_COLOR = "gamepad_face_press_color";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_BASE_COLOR = "gamepad_left_shoulder_base_color";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_PRESS_COLOR = "gamepad_left_shoulder_press_color";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_BASE_COLOR = "gamepad_right_shoulder_base_color";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_PRESS_COLOR = "gamepad_right_shoulder_press_color";
    private static final String KEY_GAMEPAD_BACK_KEY_STYLE = "gamepad_back_key_style";
    private static final String KEY_GAMEPAD_BACK_CORNER_STRENGTH = "gamepad_back_corner_strength";
    private static final String KEY_GAMEPAD_BACK_BASE_COLOR = "gamepad_back_base_color";
    private static final String KEY_GAMEPAD_BACK_PRESS_COLOR = "gamepad_back_press_color";
    private static final String KEY_MOUSE_TRAJECTORY_LEFT_COLOR_ENABLED = "mouse_trajectory_left_color_enabled";
    private static final String KEY_MOUSE_TRAJECTORY_RIGHT_COLOR_ENABLED = "mouse_trajectory_right_color_enabled";
    private static final String KEY_MOUSE_TRAJECTORY_LEFT_COLOR = "mouse_trajectory_left_color";
    private static final String KEY_MOUSE_TRAJECTORY_RIGHT_COLOR = "mouse_trajectory_right_color";
    private static final String KEY_KEYBOARD_POSITION_X = "keyboard_position_x";
    private static final String KEY_KEYBOARD_POSITION_Y = "keyboard_position_y";
    private static final String KEY_CUSTOM_POSITION_X = "custom_position_x";
    private static final String KEY_CUSTOM_POSITION_Y = "custom_position_y";
    private static final String KEY_MOUSE_POSITION_X = "mouse_position_x";
    private static final String KEY_MOUSE_POSITION_Y = "mouse_position_y";
    private static final String KEY_KEYBOARD_CAT_POSITION_X = "keyboard_cat_position_x";
    private static final String KEY_KEYBOARD_CAT_POSITION_Y = "keyboard_cat_position_y";
    private static final String KEY_KEY_PROMPT_POSITION_X = "key_prompt_position_x";
    private static final String KEY_KEY_PROMPT_POSITION_Y = "key_prompt_position_y";
    private static final String KEY_MOUSE_TRAJECTORY_POSITION_X = "mouse_trajectory_position_x";
    private static final String KEY_MOUSE_TRAJECTORY_POSITION_Y = "mouse_trajectory_position_y";
    private static final String KEY_FLOATING_VIDEO_POSITION_X = "floating_video_position_x";
    private static final String KEY_FLOATING_VIDEO_POSITION_Y = "floating_video_position_y";
    private static final String KEY_AUTO_HIDE_BACKGROUND = "auto_hide_background";
    private static final String KEY_ENTRY_AUTHORIZED = "entry_authorized";
    private static final String KEY_LAST_CLOUD_NOTICE_ID = "last_cloud_notice_id";
    private static final String KEY_KEYBOARD_MOTION_MODE = "keyboard_motion_mode";
    private static final String KEY_MOUSE_MOTION_MODE = "mouse_motion_mode";
    private static final String KEY_CUSTOM_MOTION_MODE = "custom_motion_mode";
    private static final String KEY_GLOBAL_HTML_ENABLED = "global_html_enabled";
    private static final String KEY_GLOBAL_HTML_NAME = "global_html_name";
    private static final String KEY_SENSITIVITY_ENABLED = "sensitivity_enabled";
    private static final String KEY_MOUSE_SENSITIVITY = "mouse_sensitivity";
    private static final String KEY_GAMEPAD_SENSITIVITY = "gamepad_sensitivity";
    private static final String KEY_SENSITIVITY_STATUS = "sensitivity_status";
    private static final String KEY_SENSITIVITY_MODE = "sensitivity_mode";
    private static final String KEY_UI_THEME = "ui_theme";
    private static final String KEY_GAMEPAD_LEFT_STICK_ENABLED = "gamepad_left_stick_enabled";
    private static final String KEY_GAMEPAD_RIGHT_STICK_ENABLED = "gamepad_right_stick_enabled";
    private static final String KEY_GAMEPAD_FACE_ENABLED = "gamepad_face_enabled";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_ENABLED = "gamepad_left_shoulder_enabled";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED = "gamepad_right_shoulder_enabled";
    private static final String KEY_GAMEPAD_BACK_ENABLED = "gamepad_back_enabled";
    private static final String KEY_GAMEPAD_LEFT_STICK_SHAPE = "gamepad_left_stick_shape";
    private static final String KEY_GAMEPAD_RIGHT_STICK_SHAPE = "gamepad_right_stick_shape";
    private static final String KEY_GAMEPAD_FACE_REVERSED = "gamepad_face_reversed";
    private static final String KEY_GAMEPAD_COMPATIBILITY_MODE = "gamepad_compatibility_mode";
    private static final String KEY_GAMEPAD_SWAP_XY = "gamepad_swap_xy";
    private static final String KEY_GAMEPAD_SWAP_AB = "gamepad_swap_ab";
    private static final String KEY_GAMEPAD_SWAP_STICKS = "gamepad_swap_sticks";
    private static final String KEY_GAMEPAD_SWAP_TRIGGERS = "gamepad_swap_triggers";
    private static final String KEY_GAMEPAD_CUSTOM_SWAP_ENABLED = "gamepad_custom_swap_enabled";
    private static final String KEY_GAMEPAD_CUSTOM_SWAP_FIRST = "gamepad_custom_swap_first";
    private static final String KEY_GAMEPAD_CUSTOM_SWAP_SECOND = "gamepad_custom_swap_second";
    private static final String KEY_GAMEPAD_FACE_Y_DPS = "gamepad_face_y_dps";
    private static final String KEY_GAMEPAD_FACE_X_DPS = "gamepad_face_x_dps";
    private static final String KEY_GAMEPAD_FACE_B_DPS = "gamepad_face_b_dps";
    private static final String KEY_GAMEPAD_FACE_A_DPS = "gamepad_face_a_dps";
    private static final String KEY_GAMEPAD_L2_PROGRESS = "gamepad_l2_progress";
    private static final String KEY_GAMEPAD_R2_PROGRESS = "gamepad_r2_progress";
    private static final String KEY_GAMEPAD_L1_DPS = "gamepad_l1_dps";
    private static final String KEY_GAMEPAD_R1_DPS = "gamepad_r1_dps";
    private static final String KEY_GAMEPAD_LEFT_STICK_SIZE = "gamepad_left_stick_size";
    private static final String KEY_GAMEPAD_RIGHT_STICK_SIZE = "gamepad_right_stick_size";
    private static final String KEY_GAMEPAD_LEFT_STICK_DOT_SIZE = "gamepad_left_stick_dot_size";
    private static final String KEY_GAMEPAD_RIGHT_STICK_DOT_SIZE = "gamepad_right_stick_dot_size";
    private static final String KEY_GAMEPAD_FACE_SIZE = "gamepad_face_size";
    private static final String KEY_GAMEPAD_FACE_SPACING = "gamepad_face_spacing";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_SIZE = "gamepad_left_shoulder_size";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_SIZE = "gamepad_right_shoulder_size";
    private static final String KEY_GAMEPAD_BACK_SIZE = "gamepad_back_size";
    private static final String KEY_GAMEPAD_LEFT_STICK_OPACITY = "gamepad_left_stick_opacity";
    private static final String KEY_GAMEPAD_RIGHT_STICK_OPACITY = "gamepad_right_stick_opacity";
    private static final String KEY_GAMEPAD_FACE_OPACITY = "gamepad_face_opacity";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_OPACITY = "gamepad_left_shoulder_opacity";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_OPACITY = "gamepad_right_shoulder_opacity";
    private static final String KEY_GAMEPAD_BACK_OPACITY = "gamepad_back_opacity";
    private static final String KEY_GAMEPAD_LEFT_STICK_POSITION_X = "gamepad_left_stick_position_x";
    private static final String KEY_GAMEPAD_LEFT_STICK_POSITION_Y = "gamepad_left_stick_position_y";
    private static final String KEY_GAMEPAD_RIGHT_STICK_POSITION_X = "gamepad_right_stick_position_x";
    private static final String KEY_GAMEPAD_RIGHT_STICK_POSITION_Y = "gamepad_right_stick_position_y";
    private static final String KEY_GAMEPAD_FACE_POSITION_X = "gamepad_face_position_x";
    private static final String KEY_GAMEPAD_FACE_POSITION_Y = "gamepad_face_position_y";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_POSITION_X = "gamepad_left_shoulder_position_x";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_POSITION_Y = "gamepad_left_shoulder_position_y";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_POSITION_X = "gamepad_right_shoulder_position_x";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_POSITION_Y = "gamepad_right_shoulder_position_y";
    private static final String KEY_GAMEPAD_BACK_POSITION_X = "gamepad_back_position_x";
    private static final String KEY_GAMEPAD_BACK_POSITION_Y = "gamepad_back_position_y";

    private static final int DEFAULT_KEYBOARD_X = 50;
    private static final int DEFAULT_KEYBOARD_Y = 34;
    private static final int DEFAULT_CUSTOM_X = 50;
    private static final int DEFAULT_CUSTOM_Y = 62;
    private static final int DEFAULT_MOUSE_X = 50;
    private static final int DEFAULT_MOUSE_Y = 82;
    private static final int DEFAULT_KEYBOARD_CAT_X = 50;
    private static final int DEFAULT_KEYBOARD_CAT_Y = 58;
    private static final int DEFAULT_KEY_PROMPT_X = 50;
    private static final int DEFAULT_KEY_PROMPT_Y = 14;
    private static final int DEFAULT_MOUSE_TRAJECTORY_X = 50;
    private static final int DEFAULT_MOUSE_TRAJECTORY_Y = 52;
    private static final int DEFAULT_FLOATING_VIDEO_X = 50;
    private static final int DEFAULT_FLOATING_VIDEO_Y = 50;
    private static final int DEFAULT_DPS_X = 50;
    private static final int DEFAULT_DPS_Y = 8;
    private static final int DEFAULT_GAMEPAD_LEFT_STICK_X = 14;
    private static final int DEFAULT_GAMEPAD_LEFT_STICK_Y = 68;
    private static final int DEFAULT_GAMEPAD_RIGHT_STICK_X = 66;
    private static final int DEFAULT_GAMEPAD_RIGHT_STICK_Y = 68;
    private static final int DEFAULT_GAMEPAD_FACE_X = 78;
    private static final int DEFAULT_GAMEPAD_FACE_Y = 42;
    private static final int DEFAULT_GAMEPAD_LEFT_SHOULDER_X = 10;
    private static final int DEFAULT_GAMEPAD_LEFT_SHOULDER_Y = 18;
    private static final int DEFAULT_GAMEPAD_RIGHT_SHOULDER_X = 76;
    private static final int DEFAULT_GAMEPAD_RIGHT_SHOULDER_Y = 18;
    private static final int DEFAULT_GAMEPAD_BACK_X = 50;
    private static final int DEFAULT_GAMEPAD_BACK_Y = 26;
    private static final int DEFAULT_COLUMNS = 4;
    private static final int MIN_COLUMNS = 1;
    private static final int MAX_COLUMNS = 8;
    private static final int MAX_CUSTOM_KEYS = 64;
    private static final int DEFAULT_SIZE = 100;
    private static final int DEFAULT_KEYBOARD_SPACING = 8;
    private static final int DEFAULT_CUSTOM_SPACING = 6;
    private static final int DEFAULT_GAMEPAD_FACE_SPACING = 8;
    private static final int MIN_KEY_SPACING = 0;
    private static final int MAX_KEY_SPACING = 16;
    private static final int DEFAULT_OPACITY = 100;
    private static final int DEFAULT_MOUSE_TRAJECTORY_LEFT_COLOR = 0xffff3b30;
    private static final int DEFAULT_MOUSE_TRAJECTORY_RIGHT_COLOR = 0xff34c759;
    private static final int MIN_SIZE = 50;
    private static final int MAX_SIZE = 150;
    private static final int DEFAULT_SENSITIVITY = 100;
    private static final int MIN_SENSITIVITY = 1;
    private static final int MAX_SENSITIVITY = 500;

    public static final int GAMEPAD_COMPAT_AUTO = 0;
    public static final int GAMEPAD_COMPAT_LOOSE = 1;
    public static final int GAMEPAD_COMPAT_ANDROID = 2;
    public static final int GAMEPAD_COMPAT_EVDEV = 3;
    private static final int LEGACY_GAMEPAD_COMPAT_SWAP_XY = 4;
    private static final int LEGACY_GAMEPAD_COMPAT_SWAP_AB = 5;
    private static final int LEGACY_GAMEPAD_COMPAT_SWAP_FACE = 6;

    private OverlayState() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_ENABLED, enabled);
    }

    public static boolean isInputFullKeyboardEnabled(Context context) {
        return prefs(context).getBoolean(KEY_INPUT_FULL_KEYBOARD_ENABLED, false);
    }

    public static void setInputFullKeyboardEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_INPUT_FULL_KEYBOARD_ENABLED, enabled);
    }

    public static boolean isSuperCustomEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SUPER_CUSTOM_ENABLED, false);
    }

    public static void setSuperCustomEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_SUPER_CUSTOM_ENABLED, enabled);
    }

    public static boolean isMouseEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_ENABLED, false);
    }

    public static void setMouseEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_MOUSE_ENABLED, enabled);
    }

    public static boolean isKeyboardCatEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_CAT_ENABLED, false);
    }

    public static void setKeyboardCatEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_KEYBOARD_CAT_ENABLED, enabled);
    }

    /** When enabled, BongoCat uses the source standard model: mouse pad replaces the arrow-key area. */
    public static boolean isKeyboardCatMouseMode(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_CAT_MOUSE_MODE, false);
    }

    public static void setKeyboardCatMouseMode(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_KEYBOARD_CAT_MOUSE_MODE, enabled);
    }

    /** Reverses only BongoCat input targets horizontally; the cat model and background stay unchanged. */
    public static boolean isKeyboardCatGlobalReverse(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_CAT_GLOBAL_REVERSE, false);
    }

    public static void setKeyboardCatGlobalReverse(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_KEYBOARD_CAT_GLOBAL_REVERSE, enabled);
    }

    public static String getKeyboardCatStyleId(Context context) {
        String value = durablePrefs(context).getString(KEY_KEYBOARD_CAT_STYLE_ID, BongoCatStyleManager.BUILTIN_ID);
        return value == null || value.isEmpty() ? BongoCatStyleManager.BUILTIN_ID : value;
    }

    public static void setKeyboardCatStyleId(Context context, String styleId) {
        String value = styleId == null || styleId.isEmpty() ? BongoCatStyleManager.BUILTIN_ID : styleId;
        durablePrefs(context).edit().putString(KEY_KEYBOARD_CAT_STYLE_ID, value).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    /** Debug-only expression override for imported Bongo Cat styles. "auto" restores authored behavior. */
    public static String getKeyboardCatDebugExpression(Context context) {
        String value = prefs(context).getString(KEY_KEYBOARD_CAT_DEBUG_EXPRESSION, "auto");
        return value == null || value.isEmpty() ? "auto" : value;
    }

    public static void setKeyboardCatDebugExpression(Context context, String token) {
        String value = token == null || token.isEmpty() ? "auto" : token;
        prefs(context).edit().putString(KEY_KEYBOARD_CAT_DEBUG_EXPRESSION, value).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    /** Hotkey path: persist the expression without rebuilding every overlay window. */
    static void setKeyboardCatDebugExpressionRuntime(Context context, String token) {
        String value = token == null || token.isEmpty() ? "auto" : token;
        prefs(context).edit().putString(KEY_KEYBOARD_CAT_DEBUG_EXPRESSION, value).apply();
    }

    public static int getKeyboardCatExpressionHotkeyKeyCode(Context context) {
        return prefs(context).getInt(KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_KEY_CODE, -1);
    }

    public static void setKeyboardCatExpressionHotkeyKeyCode(Context context, int keyCode) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (keyCode >= 0) editor.putInt(KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_KEY_CODE, keyCode);
        else editor.remove(KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_KEY_CODE);
        editor.apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean hasKeyboardCatExpressionHotkeySelection(Context context, String styleId) {
        return prefs(context).contains(keyboardCatExpressionSelectionKey(styleId));
    }

    public static Set<String> getKeyboardCatExpressionHotkeySelection(Context context, String styleId) {
        Set<String> stored = prefs(context).getStringSet(keyboardCatExpressionSelectionKey(styleId), null);
        return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
    }

    public static void setKeyboardCatExpressionHotkeySelection(
            Context context, String styleId, Set<String> tokens) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (tokens != null) {
            for (String token : tokens) {
                if (token != null && !token.isEmpty()) clean.add(token);
            }
        }
        prefs(context).edit()
                .putStringSet(keyboardCatExpressionSelectionKey(styleId), clean)
                .apply();
    }

    private static String keyboardCatExpressionSelectionKey(String styleId) {
        String value = styleId == null || styleId.isEmpty() ? BongoCatStyleManager.BUILTIN_ID : styleId;
        return KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_SELECTION_PREFIX + value;
    }

    public static boolean isKeyPromptEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEY_PROMPT_ENABLED, false);
    }

    public static void setKeyPromptEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_KEY_PROMPT_ENABLED, enabled);
    }

    public static boolean isMouseTrajectoryEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_TRAJECTORY_ENABLED, false);
    }

    public static void setMouseTrajectoryEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_MOUSE_TRAJECTORY_ENABLED, enabled);
    }

    public static boolean isCustomEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CUSTOM_ENABLED, false);
    }

    public static void setCustomEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_CUSTOM_ENABLED, enabled);
    }

    public static boolean isDragEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DRAG_ENABLED, false);
    }

    public static void setDragEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_DRAG_ENABLED, enabled);
    }

    public static boolean isFloatingVideoEnabled(Context context) {
        return prefs(context).getBoolean(KEY_FLOATING_VIDEO_ENABLED, false);
    }

    public static void setFloatingVideoEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_FLOATING_VIDEO_ENABLED, enabled && hasFloatingVideo(context));
    }

    public static boolean hasFloatingVideo(Context context) {
        File file = floatingVideoFile(context);
        return file.isFile() && file.length() > 0L;
    }

    public static File getFloatingVideoFile(Context context) {
        return floatingVideoFile(context);
    }

    public static String getFloatingVideoName(Context context) {
        String value = durablePrefs(context).getString(KEY_FLOATING_VIDEO_NAME, "");
        return value == null ? "" : value;
    }

    public static long getFloatingVideoSourceDurationMs(Context context) {
        return Math.max(0L, durablePrefs(context).getLong(KEY_FLOATING_VIDEO_SOURCE_DURATION, 0L));
    }

    public static long getFloatingVideoLoopDurationMs(Context context) {
        long source = getFloatingVideoSourceDurationMs(context);
        long loop = durablePrefs(context).getLong(KEY_FLOATING_VIDEO_LOOP_DURATION, source);
        if (source <= 0L) return Math.max(0L, loop);
        return Math.max(100L, Math.min(source, loop <= 0L ? source : loop));
    }

    public static int getFloatingVideoWidth(Context context) {
        return Math.max(1, durablePrefs(context).getInt(KEY_FLOATING_VIDEO_WIDTH, 16));
    }

    public static int getFloatingVideoHeight(Context context) {
        return Math.max(1, durablePrefs(context).getInt(KEY_FLOATING_VIDEO_HEIGHT, 9));
    }

    public static void importFloatingVideo(
            Context context, Uri uri, String displayName, long sourceDurationMs, long loopDurationMs,
            int sourceWidth, int sourceHeight) throws IOException {
        if (uri == null) throw new IOException("Missing video Uri");
        Context app = context.getApplicationContext();
        File target = floatingVideoFile(app);
        File temp = new File(target.getParentFile(), FLOATING_VIDEO_FILE + ".tmp");
        if (temp.exists() && !temp.delete()) throw new IOException("Cannot replace temp video");

        long total = 0L;
        try (InputStream in = app.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp, false)) {
            if (in == null) throw new IOException("Cannot open video");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_FLOATING_VIDEO_BYTES) throw new IOException("Video is too large");
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        } catch (IOException error) {
            temp.delete();
            throw error;
        }
        if (total <= 0L) {
            temp.delete();
            throw new IOException("Empty video");
        }
        try {
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            temp.delete();
            throw new IOException("Cannot commit video", error);
        }

        long source = Math.max(100L, sourceDurationMs);
        long loop = Math.max(100L, Math.min(source, loopDurationMs <= 0L ? source : loopDurationMs));
        durablePrefs(app).edit()
                .putString(KEY_FLOATING_VIDEO_NAME, displayName == null ? "" : displayName)
                .putLong(KEY_FLOATING_VIDEO_SOURCE_DURATION, source)
                .putLong(KEY_FLOATING_VIDEO_LOOP_DURATION, loop)
                .putInt(KEY_FLOATING_VIDEO_WIDTH, Math.max(1, sourceWidth))
                .putInt(KEY_FLOATING_VIDEO_HEIGHT, Math.max(1, sourceHeight))
                .apply();
        prefs(app).edit().putBoolean(KEY_FLOATING_VIDEO_ENABLED, true).apply();
        AxonInputAccessibilityService.refreshFloatingVideo();
    }

    public static boolean isForceHoldEnabled(Context context) {
        return prefs(context).getBoolean(KEY_FORCE_HOLD_ENABLED, false);
    }

    public static void setForceHoldEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_FORCE_HOLD_ENABLED, enabled);
    }

    public static int getForceHoldTargetKeyCode(Context context) {
        return prefs(context).getInt(KEY_FORCE_HOLD_TARGET_KEY_CODE, -1);
    }

    public static int getForceHoldTargetScanCode(Context context) {
        return prefs(context).getInt(KEY_FORCE_HOLD_TARGET_SCAN_CODE, -1);
    }

    public static int getForceHoldTriggerKeyCode(Context context) {
        return prefs(context).getInt(KEY_FORCE_HOLD_TRIGGER_KEY_CODE, -1);
    }

    public static boolean hasForceHoldBinding(Context context) {
        return getForceHoldTargetKeyCode(context) >= 0
                && getForceHoldTargetScanCode(context) > 0
                && getForceHoldTriggerKeyCode(context) >= 0;
    }

    public static void setForceHoldBinding(
            Context context, int targetKeyCode, int targetScanCode, int triggerKeyCode) {
        prefs(context).edit()
                .putInt(KEY_FORCE_HOLD_TARGET_KEY_CODE, targetKeyCode)
                .putInt(KEY_FORCE_HOLD_TARGET_SCAN_CODE, targetScanCode)
                .putInt(KEY_FORCE_HOLD_TRIGGER_KEY_CODE, triggerKeyCode)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static void clearForceHoldBinding(Context context) {
        prefs(context).edit()
                .remove(KEY_FORCE_HOLD_TARGET_KEY_CODE)
                .remove(KEY_FORCE_HOLD_TARGET_SCAN_CODE)
                .remove(KEY_FORCE_HOLD_TRIGGER_KEY_CODE)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isDpsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DPS_ENABLED, false);
    }

    public static void setDpsEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_DPS_ENABLED, enabled);
    }

    public static int getDpsTargetKeyCode(Context context) {
        return prefs(context).getInt(KEY_DPS_TARGET_KEY_CODE, DPS_TARGET_NONE);
    }

    public static void setDpsTargetKeyCode(Context context, int keyCode) {
        int resolved = keyCode < 0 ? DPS_TARGET_NONE : keyCode;
        setIntAndRefresh(context, KEY_DPS_TARGET_KEY_CODE, resolved);
    }


    public static int mouseDpsTarget(int button) {
        return switch (button) {
            case NativeKeyEngine.MOUSE_LEFT -> DPS_TARGET_MOUSE_LEFT;
            case NativeKeyEngine.MOUSE_RIGHT -> DPS_TARGET_MOUSE_RIGHT;
            case MouseInputMonitor.BUTTON_MIDDLE -> DPS_TARGET_MOUSE_MIDDLE;
            case MouseInputMonitor.BUTTON_BACK -> DPS_TARGET_MOUSE_BACK;
            case MouseInputMonitor.BUTTON_FORWARD -> DPS_TARGET_MOUSE_FORWARD;
            default -> DPS_TARGET_NONE;
        };
    }

    public static boolean isMouseDpsTarget(int target) {
        return target >= DPS_TARGET_MOUSE_LEFT && target <= DPS_TARGET_MOUSE_FORWARD;
    }

    public static int getMouseDpsTargetButton(int target) {
        return switch (target) {
            case DPS_TARGET_MOUSE_LEFT -> NativeKeyEngine.MOUSE_LEFT;
            case DPS_TARGET_MOUSE_RIGHT -> NativeKeyEngine.MOUSE_RIGHT;
            case DPS_TARGET_MOUSE_MIDDLE -> MouseInputMonitor.BUTTON_MIDDLE;
            case DPS_TARGET_MOUSE_BACK -> MouseInputMonitor.BUTTON_BACK;
            case DPS_TARGET_MOUSE_FORWARD -> MouseInputMonitor.BUTTON_FORWARD;
            default -> -1;
        };
    }

    public static int dpsTargetFromBinding(int bindingCode) {
        if (InputBinding.isMouse(bindingCode)) return mouseDpsTarget(InputBinding.payload(bindingCode));
        if (InputBinding.isGamepad(bindingCode)) return gamepadDpsTarget(InputBinding.payload(bindingCode));
        return bindingCode;
    }

    public static int bindingFromDpsTarget(int target) {
        if (isMouseDpsTarget(target)) return InputBinding.mouse(getMouseDpsTargetButton(target));
        if (isGamepadDpsTarget(target)) return InputBinding.gamepad(getGamepadDpsTargetBit(target));
        return target;
    }

    public static int gamepadDpsTarget(int buttonBit) {
        return DPS_TARGET_GAMEPAD_EXT_BASE | (buttonBit & 0x00ffffff);
    }

    public static boolean isGamepadDpsTarget(int target) {
        boolean extended = (target & 0xff000000) == DPS_TARGET_GAMEPAD_EXT_BASE
                && (target & 0x00ffffff) != 0;
        boolean legacy = (target & 0xf0000) == DPS_TARGET_GAMEPAD_BASE
                && (target & 0xffff) != 0;
        return extended || legacy;
    }

    public static int getGamepadDpsTargetBit(int target) {
        if ((target & 0xff000000) == DPS_TARGET_GAMEPAD_EXT_BASE) return target & 0x00ffffff;
        if ((target & 0xf0000) == DPS_TARGET_GAMEPAD_BASE) return target & 0xffff;
        return 0;
    }

    public static boolean isGamepadLeftStickEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_LEFT_STICK_ENABLED, false);
    }

    public static void setGamepadLeftStickEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_LEFT_STICK_ENABLED, enabled);
    }

    public static boolean isGamepadRightStickEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_RIGHT_STICK_ENABLED, false);
    }

    public static void setGamepadRightStickEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_RIGHT_STICK_ENABLED, enabled);
    }

    public static boolean isGamepadFaceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_FACE_ENABLED, false);
    }

    public static void setGamepadFaceEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_FACE_ENABLED, enabled);
    }

    public static boolean isGamepadLeftShoulderEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_LEFT_SHOULDER_ENABLED, false);
    }

    public static void setGamepadLeftShoulderEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_LEFT_SHOULDER_ENABLED, enabled);
    }

    public static boolean isGamepadRightShoulderEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED, false);
    }

    public static void setGamepadRightShoulderEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED, enabled);
    }

    public static boolean isGamepadBackEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_BACK_ENABLED, false);
    }

    public static void setGamepadBackEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_BACK_ENABLED, enabled);
    }

    public static boolean isGamepadShouldersEnabled(Context context) {
        return isGamepadLeftShoulderEnabled(context) || isGamepadRightShoulderEnabled(context);
    }

    public static void setGamepadShouldersEnabled(Context context, boolean enabled) {
        prefs(context).edit()
                .putBoolean(KEY_GAMEPAD_LEFT_SHOULDER_ENABLED, enabled)
                .putBoolean(KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED, enabled)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isAnyGamepadDisplayEnabled(Context context) {
        return isGamepadLeftStickEnabled(context) || isGamepadRightStickEnabled(context)
                || isGamepadFaceEnabled(context) || isGamepadLeftShoulderEnabled(context)
                || isGamepadRightShoulderEnabled(context) || isGamepadBackEnabled(context);
    }

    public static boolean isAnyDisplayEnabled(Context context) {
        return isEnabled(context) || isInputFullKeyboardEnabled(context) || isMouseEnabled(context)
                || isKeyboardCatEnabled(context) || isKeyPromptEnabled(context)
                || isCustomEnabled(context) || isSuperCustomEnabled(context)
                || isMouseTrajectoryEnabled(context)
                || isFloatingVideoEnabled(context)
                || isDpsEnabled(context) || isAnyGamepadDisplayEnabled(context);
    }

    public static boolean isAutoHideBackground(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_HIDE_BACKGROUND, false);
    }

    public static void setAutoHideBackground(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_HIDE_BACKGROUND, enabled).apply();
    }

    public static boolean isEntryAuthorized(Context context) {
        SharedPreferences durable = durablePrefs(context);
        if (durable.contains(KEY_ENTRY_AUTHORIZED)) {
            return durable.getBoolean(KEY_ENTRY_AUTHORIZED, false);
        }
        // 只迁移旧版本的密码授权。
        boolean legacy = context.getSharedPreferences("key_display", Context.MODE_PRIVATE)
                .getBoolean(KEY_ENTRY_AUTHORIZED, false);
        if (legacy) durable.edit().putBoolean(KEY_ENTRY_AUTHORIZED, true).apply();
        return legacy;
    }

    public static void setEntryAuthorized(Context context, boolean authorized) {
        durablePrefs(context).edit().putBoolean(KEY_ENTRY_AUTHORIZED, authorized).apply();
    }

    public static String getLastCloudNoticeId(Context context) {
        return durablePrefs(context).getString(KEY_LAST_CLOUD_NOTICE_ID, "");
    }

    public static void setLastCloudNoticeId(Context context, String noticeId) {
        String value = noticeId == null ? "" : noticeId.trim();
        durablePrefs(context).edit().putString(KEY_LAST_CLOUD_NOTICE_ID, value).apply();
    }

    /** 根任务退出时清理运行配置。手动保存配置和密码授权不删除。 */
    public static void endAppSession(Context context) {
        prefs(context).edit().clear().commit();
        AxonInputAccessibilityService.refreshActiveService();
    }



    public static int getUiTheme(Context context) {
        int value = prefs(context).getInt(KEY_UI_THEME, UI_THEME_LIGHT);
        return value == UI_THEME_BLACK ? UI_THEME_BLACK : UI_THEME_LIGHT;
    }

    public static void setUiTheme(Context context, int theme) {
        int resolved = theme == UI_THEME_BLACK ? UI_THEME_BLACK : UI_THEME_LIGHT;
        prefs(context).edit().putInt(KEY_UI_THEME, resolved).apply();
        AxonInputAccessibilityService.refreshTheme();
    }

    public static int getSensitivityMode(Context context) {
        int value = prefs(context).getInt(KEY_SENSITIVITY_MODE, SENSITIVITY_MODE_SHIZUKU);
        return value == SENSITIVITY_MODE_ROOT ? SENSITIVITY_MODE_ROOT : SENSITIVITY_MODE_SHIZUKU;
    }

    public static void setSensitivityMode(Context context, int mode) {
        int resolved = mode == SENSITIVITY_MODE_ROOT ? SENSITIVITY_MODE_ROOT : SENSITIVITY_MODE_SHIZUKU;
        prefs(context).edit().putInt(KEY_SENSITIVITY_MODE, resolved).apply();
        AxonInputAccessibilityService.refreshSensitivity();
    }

    public static boolean isSensitivityEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SENSITIVITY_ENABLED, false);
    }

    public static void setSensitivityEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SENSITIVITY_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshSensitivity();
    }

    public static int getMouseSensitivity(Context context) {
        return clampSensitivity(prefs(context).getInt(KEY_MOUSE_SENSITIVITY, DEFAULT_SENSITIVITY));
    }

    public static void setMouseSensitivity(Context context, int percent) {
        prefs(context).edit().putInt(KEY_MOUSE_SENSITIVITY, clampSensitivity(percent)).apply();
        AxonInputAccessibilityService.refreshSensitivity();
    }

    public static int getGamepadSensitivity(Context context) {
        return clampSensitivity(prefs(context).getInt(KEY_GAMEPAD_SENSITIVITY, DEFAULT_SENSITIVITY));
    }

    public static void setGamepadSensitivity(Context context, int percent) {
        prefs(context).edit().putInt(KEY_GAMEPAD_SENSITIVITY, clampSensitivity(percent)).apply();
        AxonInputAccessibilityService.refreshSensitivity();
    }

    public static String getSensitivityStatus(Context context) {
        String fallback = context.getString(R.string.status_disabled);
        String value = prefs(context).getString(KEY_SENSITIVITY_STATUS, fallback);
        return value == null ? fallback : value;
    }

    static void setSensitivityStatus(Context context, String status) {
        prefs(context).edit().putString(KEY_SENSITIVITY_STATUS, status == null ? "" : status).apply();
    }

    public static boolean isCustomCaptureEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CUSTOM_CAPTURE, false);
    }

    /** 开始新的按键录入。录入结束前保留已保存按键。 */
    public static void beginCustomCapture(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_CUSTOM_CAPTURE, true)
                .putString(KEY_CUSTOM_DRAFT, "")
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    /** 保存录入结果并结束录入。 */
    public static void finishCustomCapture(Context context) {
        SharedPreferences p = prefs(context);
        String draft = p.getString(KEY_CUSTOM_DRAFT, "");
        p.edit()
                .putString(KEY_CUSTOM_KEYS, draft == null ? "" : draft)
                .putBoolean(KEY_CUSTOM_CAPTURE, false)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static void cancelCustomCapture(Context context) {
        prefs(context).edit().putBoolean(KEY_CUSTOM_CAPTURE, false).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    /** 向当前录入结果加入唯一按键码。 */
    public static boolean addDraftKey(Context context, int keyCode) {
        if (keyCode <= 0 || !isCustomCaptureEnabled(context)) return false;
        SharedPreferences p = prefs(context);
        List<Integer> current = parseCodes(p.getString(KEY_CUSTOM_DRAFT, ""));
        if (current.contains(keyCode) || current.size() >= MAX_CUSTOM_KEYS) return false;
        current.add(keyCode);
        p.edit().putString(KEY_CUSTOM_DRAFT, encodeCodes(current)).apply();
        return true;
    }

    public static int[] getCustomKeyCodes(Context context) {
        return toArray(parseCodes(prefs(context).getString(KEY_CUSTOM_KEYS, "")));
    }

    public static int[] getCustomDraftKeyCodes(Context context) {
        return toArray(parseCodes(prefs(context).getString(KEY_CUSTOM_DRAFT, "")));
    }

    public static boolean containsCustomKey(Context context, int keyCode) {
        int[] keys = getCustomKeyCodes(context);
        for (int key : keys) if (key == keyCode) return true;
        return false;
    }

    public static int getCustomColumns(Context context) {
        return clampColumns(prefs(context).getInt(KEY_CUSTOM_COLUMNS, DEFAULT_COLUMNS));
    }

    public static void setCustomColumns(Context context, int columns) {
        setIntAndRefresh(context, KEY_CUSTOM_COLUMNS, clampColumns(columns));
    }


    public static int getKeyboardSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_KEYBOARD_SIZE, DEFAULT_SIZE));
    }

    public static void setKeyboardSize(Context context, int percent) {
        setIntAndRefresh(context, KEY_KEYBOARD_SIZE, clampSize(percent));
    }

    public static int getKeyboardSpacing(Context context) {
        return clampKeySpacing(prefs(context).getInt(KEY_KEYBOARD_SPACING, DEFAULT_KEYBOARD_SPACING));
    }

    public static void setKeyboardSpacing(Context context, int spacingDp) {
        setIntAndRefresh(context, KEY_KEYBOARD_SPACING, clampKeySpacing(spacingDp));
    }

    public static boolean isKeyboardSpaceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_SPACE_ENABLED, true);
    }

    public static void setKeyboardSpaceEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_KEYBOARD_SPACE_ENABLED, enabled);
    }

    public static boolean isKeyboardSpaceDpsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_SPACE_DPS_ENABLED, false);
    }

    public static void setKeyboardSpaceDpsEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_KEYBOARD_SPACE_DPS_ENABLED, enabled);
    }

    public static int getCustomSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_CUSTOM_SIZE, DEFAULT_SIZE));
    }

    public static void setCustomSize(Context context, int percent) {
        setIntAndRefresh(context, KEY_CUSTOM_SIZE, clampSize(percent));
    }

    public static int getCustomSpacing(Context context) {
        return clampKeySpacing(prefs(context).getInt(KEY_CUSTOM_SPACING, DEFAULT_CUSTOM_SPACING));
    }

    public static void setCustomSpacing(Context context, int spacingDp) {
        setIntAndRefresh(context, KEY_CUSTOM_SPACING, clampKeySpacing(spacingDp));
    }

    public static int getMouseSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_MOUSE_SIZE, DEFAULT_SIZE));
    }

    public static void setMouseSize(Context context, int percent) {
        setIntAndRefresh(context, KEY_MOUSE_SIZE, clampSize(percent));
    }

    public static int getKeyboardCatSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_KEYBOARD_CAT_SIZE, DEFAULT_SIZE));
    }

    public static void setKeyboardCatSize(Context context, int percent) {
        setIntAndRefresh(context, KEY_KEYBOARD_CAT_SIZE, clampSize(percent));
    }

    public static int getKeyPromptSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_KEY_PROMPT_SIZE, DEFAULT_SIZE));
    }

    public static void setKeyPromptSize(Context context, int percent) {
        setIntAndRefresh(context, KEY_KEY_PROMPT_SIZE, clampSize(percent));
    }

    public static int getMouseTrajectorySize(Context context) {
        return clampSize(prefs(context).getInt(KEY_MOUSE_TRAJECTORY_SIZE, DEFAULT_SIZE));
    }

    public static void setMouseTrajectorySize(Context context, int percent) {
        setIntAndRefresh(context, KEY_MOUSE_TRAJECTORY_SIZE, clampSize(percent));
    }

    public static int getMouseTrajectoryDotSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_MOUSE_TRAJECTORY_DOT_SIZE, DEFAULT_SIZE));
    }

    public static void setMouseTrajectoryDotSize(Context context, int percent) {
        setIntAndRefresh(context, KEY_MOUSE_TRAJECTORY_DOT_SIZE, clampSize(percent));
    }

    public static boolean isMouseTrajectoryLeftColorEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_TRAJECTORY_LEFT_COLOR_ENABLED, false);
    }

    public static void setMouseTrajectoryLeftColorEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_MOUSE_TRAJECTORY_LEFT_COLOR_ENABLED, enabled);
    }

    public static boolean isMouseTrajectoryRightColorEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_TRAJECTORY_RIGHT_COLOR_ENABLED, false);
    }

    public static void setMouseTrajectoryRightColorEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_MOUSE_TRAJECTORY_RIGHT_COLOR_ENABLED, enabled);
    }

    public static int getMouseTrajectoryLeftColor(Context context) {
        return prefs(context).getInt(KEY_MOUSE_TRAJECTORY_LEFT_COLOR, DEFAULT_MOUSE_TRAJECTORY_LEFT_COLOR);
    }

    public static void setMouseTrajectoryLeftColor(Context context, int color) {
        setIntAndRefresh(context, KEY_MOUSE_TRAJECTORY_LEFT_COLOR, 0xff000000 | (color & 0x00ffffff));
    }

    public static int getMouseTrajectoryRightColor(Context context) {
        return prefs(context).getInt(KEY_MOUSE_TRAJECTORY_RIGHT_COLOR, DEFAULT_MOUSE_TRAJECTORY_RIGHT_COLOR);
    }

    public static void setMouseTrajectoryRightColor(Context context, int color) {
        setIntAndRefresh(context, KEY_MOUSE_TRAJECTORY_RIGHT_COLOR, 0xff000000 | (color & 0x00ffffff));
    }

    public static int getGamepadLeftStickShape(Context context) {
        int value = prefs(context).getInt(KEY_GAMEPAD_LEFT_STICK_SHAPE, GamepadOverlayView.SHAPE_CIRCLE);
        return value == GamepadOverlayView.SHAPE_SQUARE ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE;
    }

    public static void setGamepadLeftStickShape(Context context, int shape) {
        int value = shape == GamepadOverlayView.SHAPE_SQUARE ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE;
        setIntAndRefresh(context, KEY_GAMEPAD_LEFT_STICK_SHAPE, value);
    }

    public static int getGamepadRightStickShape(Context context) {
        int value = prefs(context).getInt(KEY_GAMEPAD_RIGHT_STICK_SHAPE, GamepadOverlayView.SHAPE_CIRCLE);
        return value == GamepadOverlayView.SHAPE_SQUARE ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE;
    }

    public static void setGamepadRightStickShape(Context context, int shape) {
        int value = shape == GamepadOverlayView.SHAPE_SQUARE ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE;
        setIntAndRefresh(context, KEY_GAMEPAD_RIGHT_STICK_SHAPE, value);
    }

    public static int getGamepadStickDotSize(Context context, int displayType) {
        String key = displayType == GamepadOverlayView.DISPLAY_RIGHT_STICK
                ? KEY_GAMEPAD_RIGHT_STICK_DOT_SIZE : KEY_GAMEPAD_LEFT_STICK_DOT_SIZE;
        return clampSize(prefs(context).getInt(key, DEFAULT_SIZE));
    }

    public static void setGamepadStickDotSize(Context context, int displayType, int percent) {
        if (displayType != GamepadOverlayView.DISPLAY_LEFT_STICK
                && displayType != GamepadOverlayView.DISPLAY_RIGHT_STICK) return;
        String key = displayType == GamepadOverlayView.DISPLAY_RIGHT_STICK
                ? KEY_GAMEPAD_RIGHT_STICK_DOT_SIZE : KEY_GAMEPAD_LEFT_STICK_DOT_SIZE;
        setIntAndRefresh(context, key, clampSize(percent));
    }

    public static boolean isGamepadFaceReversed(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_FACE_REVERSED, false);
    }

    public static void setGamepadFaceReversed(Context context, boolean reversed) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_FACE_REVERSED, reversed);
    }

    public static int getGamepadCompatibilityMode(Context context) {
        SharedPreferences preferences = prefs(context);
        int mode = preferences.getInt(KEY_GAMEPAD_COMPATIBILITY_MODE, GAMEPAD_COMPAT_AUTO);

        // 迁移旧版把交换功能放在模式列表中的配置。
        if (mode >= LEGACY_GAMEPAD_COMPAT_SWAP_XY && mode <= LEGACY_GAMEPAD_COMPAT_SWAP_FACE) {
            SharedPreferences.Editor editor = preferences.edit()
                    .putInt(KEY_GAMEPAD_COMPATIBILITY_MODE, GAMEPAD_COMPAT_AUTO);
            if (mode == LEGACY_GAMEPAD_COMPAT_SWAP_XY || mode == LEGACY_GAMEPAD_COMPAT_SWAP_FACE) {
                editor.putBoolean(KEY_GAMEPAD_SWAP_XY, true);
            }
            if (mode == LEGACY_GAMEPAD_COMPAT_SWAP_AB || mode == LEGACY_GAMEPAD_COMPAT_SWAP_FACE) {
                editor.putBoolean(KEY_GAMEPAD_SWAP_AB, true);
            }
            editor.apply();
            return GAMEPAD_COMPAT_AUTO;
        }
        return mode >= GAMEPAD_COMPAT_AUTO && mode <= GAMEPAD_COMPAT_EVDEV
                ? mode : GAMEPAD_COMPAT_AUTO;
    }

    public static void setGamepadCompatibilityMode(Context context, int mode) {
        int value = mode >= GAMEPAD_COMPAT_AUTO && mode <= GAMEPAD_COMPAT_EVDEV
                ? mode : GAMEPAD_COMPAT_AUTO;
        setIntAndRefresh(context, KEY_GAMEPAD_COMPATIBILITY_MODE, value);
    }

    public static boolean isGamepadSwapXY(Context context) {
        getGamepadCompatibilityMode(context);
        return prefs(context).getBoolean(KEY_GAMEPAD_SWAP_XY, false);
    }

    public static void setGamepadSwapXY(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_SWAP_XY, enabled);
    }

    public static boolean isGamepadSwapAB(Context context) {
        getGamepadCompatibilityMode(context);
        return prefs(context).getBoolean(KEY_GAMEPAD_SWAP_AB, false);
    }

    public static void setGamepadSwapAB(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_SWAP_AB, enabled);
    }

    public static boolean isGamepadSwapSticks(Context context) {
        getGamepadCompatibilityMode(context);
        return prefs(context).getBoolean(KEY_GAMEPAD_SWAP_STICKS, false);
    }

    public static void setGamepadSwapSticks(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_SWAP_STICKS, enabled);
    }

    public static boolean isGamepadSwapTriggers(Context context) {
        getGamepadCompatibilityMode(context);
        return prefs(context).getBoolean(KEY_GAMEPAD_SWAP_TRIGGERS, false);
    }

    public static void setGamepadSwapTriggers(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_SWAP_TRIGGERS, enabled);
    }

    public static boolean isGamepadCustomSwapEnabled(Context context) {
        int first = getGamepadCustomSwapFirst(context);
        int second = getGamepadCustomSwapSecond(context);
        return first != 0 && second != 0 && first != second
                && prefs(context).getBoolean(KEY_GAMEPAD_CUSTOM_SWAP_ENABLED, false);
    }

    public static int getGamepadCustomSwapFirst(Context context) {
        return sanitizeGamepadSwapBit(prefs(context).getInt(KEY_GAMEPAD_CUSTOM_SWAP_FIRST, 0));
    }

    public static int getGamepadCustomSwapSecond(Context context) {
        return sanitizeGamepadSwapBit(prefs(context).getInt(KEY_GAMEPAD_CUSTOM_SWAP_SECOND, 0));
    }

    public static void setGamepadCustomSwapEnabled(Context context, boolean enabled) {
        boolean value = enabled && getGamepadCustomSwapFirst(context) != 0
                && getGamepadCustomSwapSecond(context) != 0
                && getGamepadCustomSwapFirst(context) != getGamepadCustomSwapSecond(context);
        setBooleanAndRefresh(context, KEY_GAMEPAD_CUSTOM_SWAP_ENABLED, value);
    }

    public static void setGamepadCustomSwapPair(Context context, int first, int second) {
        first = sanitizeGamepadSwapBit(first);
        second = sanitizeGamepadSwapBit(second);
        boolean valid = first != 0 && second != 0 && first != second;
        prefs(context).edit()
                .putInt(KEY_GAMEPAD_CUSTOM_SWAP_FIRST, valid ? first : 0)
                .putInt(KEY_GAMEPAD_CUSTOM_SWAP_SECOND, valid ? second : 0)
                .putBoolean(KEY_GAMEPAD_CUSTOM_SWAP_ENABLED, valid)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    private static int sanitizeGamepadSwapBit(int bit) {
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

    public static void resetGamepadCompatibility(Context context) {
        prefs(context).edit()
                .putInt(KEY_GAMEPAD_COMPATIBILITY_MODE, GAMEPAD_COMPAT_AUTO)
                .putBoolean(KEY_GAMEPAD_SWAP_XY, false)
                .putBoolean(KEY_GAMEPAD_SWAP_AB, false)
                .putBoolean(KEY_GAMEPAD_SWAP_STICKS, false)
                .putBoolean(KEY_GAMEPAD_SWAP_TRIGGERS, false)
                .putBoolean(KEY_GAMEPAD_CUSTOM_SWAP_ENABLED, false)
                .putInt(KEY_GAMEPAD_CUSTOM_SWAP_FIRST, 0)
                .putInt(KEY_GAMEPAD_CUSTOM_SWAP_SECOND, 0)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isGamepadFaceYDpsEnabled(Context context) { return prefs(context).getBoolean(KEY_GAMEPAD_FACE_Y_DPS, false); }
    public static boolean isGamepadFaceXDpsEnabled(Context context) { return prefs(context).getBoolean(KEY_GAMEPAD_FACE_X_DPS, false); }
    public static boolean isGamepadFaceBDpsEnabled(Context context) { return prefs(context).getBoolean(KEY_GAMEPAD_FACE_B_DPS, false); }
    public static boolean isGamepadFaceADpsEnabled(Context context) { return prefs(context).getBoolean(KEY_GAMEPAD_FACE_A_DPS, false); }

    public static void setGamepadFaceYDpsEnabled(Context context, boolean enabled) { setBooleanAndRefresh(context, KEY_GAMEPAD_FACE_Y_DPS, enabled); }
    public static void setGamepadFaceXDpsEnabled(Context context, boolean enabled) { setBooleanAndRefresh(context, KEY_GAMEPAD_FACE_X_DPS, enabled); }
    public static void setGamepadFaceBDpsEnabled(Context context, boolean enabled) { setBooleanAndRefresh(context, KEY_GAMEPAD_FACE_B_DPS, enabled); }
    public static void setGamepadFaceADpsEnabled(Context context, boolean enabled) { setBooleanAndRefresh(context, KEY_GAMEPAD_FACE_A_DPS, enabled); }

    public static boolean isGamepadL2ProgressEnabled(Context context) { return prefs(context).getBoolean(KEY_GAMEPAD_L2_PROGRESS, false); }
    public static boolean isGamepadR2ProgressEnabled(Context context) { return prefs(context).getBoolean(KEY_GAMEPAD_R2_PROGRESS, false); }
    public static boolean isGamepadL1DpsEnabled(Context context) { return prefs(context).getBoolean(KEY_GAMEPAD_L1_DPS, false); }
    public static boolean isGamepadR1DpsEnabled(Context context) { return prefs(context).getBoolean(KEY_GAMEPAD_R1_DPS, false); }

    public static void setGamepadL2ProgressEnabled(Context context, boolean enabled) { setBooleanAndRefresh(context, KEY_GAMEPAD_L2_PROGRESS, enabled); }
    public static void setGamepadR2ProgressEnabled(Context context, boolean enabled) { setBooleanAndRefresh(context, KEY_GAMEPAD_R2_PROGRESS, enabled); }
    public static void setGamepadL1DpsEnabled(Context context, boolean enabled) { setBooleanAndRefresh(context, KEY_GAMEPAD_L1_DPS, enabled); }
    public static void setGamepadR1DpsEnabled(Context context, boolean enabled) { setBooleanAndRefresh(context, KEY_GAMEPAD_R1_DPS, enabled); }

    public static boolean isAnyGamepadFaceDpsEnabled(Context context) {
        return isGamepadFaceYDpsEnabled(context) || isGamepadFaceXDpsEnabled(context)
                || isGamepadFaceBDpsEnabled(context) || isGamepadFaceADpsEnabled(context);
    }

    public static int getGamepadDisplaySize(Context context, int displayType) {
        String key;
        switch (displayType) {
            case GamepadOverlayView.DISPLAY_LEFT_STICK: key = KEY_GAMEPAD_LEFT_STICK_SIZE; break;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: key = KEY_GAMEPAD_RIGHT_STICK_SIZE; break;
            case GamepadOverlayView.DISPLAY_FACE: key = KEY_GAMEPAD_FACE_SIZE; break;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: key = KEY_GAMEPAD_LEFT_SHOULDER_SIZE; break;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: key = KEY_GAMEPAD_RIGHT_SHOULDER_SIZE; break;
            case GamepadOverlayView.DISPLAY_BACK: key = KEY_GAMEPAD_BACK_SIZE; break;
            default: return DEFAULT_SIZE;
        }
        return clampSize(prefs(context).getInt(key, DEFAULT_SIZE));
    }

    public static void setGamepadDisplaySize(Context context, int displayType, int percent) {
        String key;
        switch (displayType) {
            case GamepadOverlayView.DISPLAY_LEFT_STICK: key = KEY_GAMEPAD_LEFT_STICK_SIZE; break;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: key = KEY_GAMEPAD_RIGHT_STICK_SIZE; break;
            case GamepadOverlayView.DISPLAY_FACE: key = KEY_GAMEPAD_FACE_SIZE; break;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: key = KEY_GAMEPAD_LEFT_SHOULDER_SIZE; break;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: key = KEY_GAMEPAD_RIGHT_SHOULDER_SIZE; break;
            case GamepadOverlayView.DISPLAY_BACK: key = KEY_GAMEPAD_BACK_SIZE; break;
            default: return;
        }
        setIntAndRefresh(context, key, clampSize(percent));
    }

    public static int getGamepadFaceSpacing(Context context) {
        return clampKeySpacing(prefs(context).getInt(KEY_GAMEPAD_FACE_SPACING, DEFAULT_GAMEPAD_FACE_SPACING));
    }

    public static void setGamepadFaceSpacing(Context context, int spacingDp) {
        setIntAndRefresh(context, KEY_GAMEPAD_FACE_SPACING, clampKeySpacing(spacingDp));
    }

    public static int getDisplayOpacity(Context context, int displayType) {
        String key = opacityKey(displayType);
        if (key == null) return DEFAULT_OPACITY;
        return clampOpacity(prefs(context).getInt(key, DEFAULT_OPACITY));
    }

    public static void setDisplayOpacity(Context context, int displayType, int percent) {
        String key = opacityKey(displayType);
        if (key == null) return;
        setIntAndRefresh(context, key, clampOpacity(percent));
    }

    /** Independent alpha channels for native regular key-display surfaces. */
    public static int getKeyBackgroundOpacity(Context context, int displayType) {
        return getKeyLayerOpacity(context, displayType, "background");
    }

    public static int getKeyStrokeOpacity(Context context, int displayType) {
        return getKeyLayerOpacity(context, displayType, "stroke");
    }

    public static int getKeyTextOpacity(Context context, int displayType) {
        return getKeyLayerOpacity(context, displayType, "text");
    }

    public static void setKeyBackgroundOpacity(Context context, int displayType, int percent) {
        setKeyLayerOpacity(context, displayType, "background", percent);
    }

    public static void setKeyStrokeOpacity(Context context, int displayType, int percent) {
        setKeyLayerOpacity(context, displayType, "stroke", percent);
    }

    public static void setKeyTextOpacity(Context context, int displayType, int percent) {
        setKeyLayerOpacity(context, displayType, "text", percent);
    }

    private static int getKeyLayerOpacity(Context context, int displayType, String layer) {
        String key = keyLayerOpacityKey(displayType, layer);
        if (key == null) return DEFAULT_OPACITY;
        SharedPreferences preferences = prefs(context);
        if (preferences.contains(key)) return clampOpacity(preferences.getInt(key, DEFAULT_OPACITY));
        // Migrate the old whole-view opacity into all three channels on first use.
        String legacyKey = opacityKey(displayType);
        return legacyKey == null ? DEFAULT_OPACITY
                : clampOpacity(preferences.getInt(legacyKey, DEFAULT_OPACITY));
    }

    private static void setKeyLayerOpacity(Context context, int displayType, String layer, int percent) {
        String key = keyLayerOpacityKey(displayType, layer);
        if (key == null) return;
        setIntAndRefresh(context, key, clampOpacity(percent));
    }

    private static String keyLayerOpacityKey(int displayType, String layer) {
        String prefix;
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: prefix = "keyboard"; break;
            case KeyOverlayView.DISPLAY_MOUSE: prefix = "mouse"; break;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: prefix = "key_prompt"; break;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: prefix = "full_keyboard"; break;
            case KeyOverlayView.DISPLAY_CUSTOM: prefix = "custom"; break;
            default: return null;
        }
        return prefix + "_" + layer + "_opacity";
    }

    public static int getKeyStyle(Context context, int displayType) {
        String key = keyStyleKey(displayType);
        if (key == null) return KeyAppearance.STYLE_ROUNDED;
        return KeyAppearance.clampStyle(prefs(context).getInt(key, KeyAppearance.STYLE_ROUNDED));
    }

    public static void setKeyStyle(Context context, int displayType, int style) {
        String key = keyStyleKey(displayType);
        if (key == null) return;
        setIntAndRefresh(context, key, KeyAppearance.clampStyle(style));
    }

    public static int getKeyCornerStrength(Context context, int displayType) {
        String key = keyCornerStrengthKey(displayType);
        if (key == null) return KeyAppearance.DEFAULT_CORNER_STRENGTH;
        return KeyAppearance.clampCornerStrength(
                prefs(context).getInt(key, KeyAppearance.DEFAULT_CORNER_STRENGTH));
    }

    public static void setKeyCornerStrength(Context context, int displayType, int strength) {
        String key = keyCornerStrengthKey(displayType);
        if (key == null) return;
        setIntAndRefresh(context, key, KeyAppearance.clampCornerStrength(strength));
    }

    public static int getKeyBaseColor(Context context, int displayType) {
        String key = keyBaseColorKey(displayType);
        int fallback = defaultKeyBaseColor(context, displayType);
        if (key == null) return fallback;
        return 0xff000000 | (prefs(context).getInt(key, fallback) & 0x00ffffff);
    }

    private static int defaultKeyBaseColor(Context context, int displayType) {
        if (displayType == GamepadOverlayView.DISPLAY_FACE
                || displayType == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || displayType == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER
                || displayType == GamepadOverlayView.DISPLAY_BACK) {
            return UiPalette.overlayShell(context);
        }
        return UiPalette.overlayKeyIdle(context);
    }

    public static void setKeyBaseColor(Context context, int displayType, int color) {
        String key = keyBaseColorKey(displayType);
        if (key == null) return;
        setIntAndRefresh(context, key, 0xff000000 | (color & 0x00ffffff));
    }

    public static int getKeyPressColor(Context context, int displayType) {
        String key = keyPressColorKey(displayType);
        if (key == null) return UiPalette.overlayKeyPressed(context);
        return 0xff000000 | (prefs(context).getInt(key, UiPalette.overlayKeyPressed(context)) & 0x00ffffff);
    }

    public static void setKeyPressColor(Context context, int displayType, int color) {
        String key = keyPressColorKey(displayType);
        if (key == null) return;
        setIntAndRefresh(context, key, 0xff000000 | (color & 0x00ffffff));
    }

    public static int getKeyboardTextColor(Context context) {
        return 0xff000000 | (prefs(context).getInt(
                KEY_KEYBOARD_TEXT_COLOR, UiPalette.overlayTextIdle(context)) & 0x00ffffff);
    }

    public static void setKeyboardTextColor(Context context, int color) {
        setIntAndRefresh(context, KEY_KEYBOARD_TEXT_COLOR,
                0xff000000 | (color & 0x00ffffff));
    }

    private static String keyStyleKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_KEY_STYLE;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_KEY_STYLE;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_KEY_STYLE;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: return KEY_FULL_KEYBOARD_KEY_STYLE;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_KEY_STYLE;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_KEY_STYLE;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_KEY_STYLE;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_KEY_STYLE;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_KEY_STYLE;
            default: return null;
        }
    }

    private static String keyCornerStrengthKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_CORNER_STRENGTH;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_CORNER_STRENGTH;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_CORNER_STRENGTH;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: return KEY_FULL_KEYBOARD_CORNER_STRENGTH;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_CORNER_STRENGTH;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_CORNER_STRENGTH;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_CORNER_STRENGTH;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_CORNER_STRENGTH;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_CORNER_STRENGTH;
            default: return null;
        }
    }

    private static String keyBaseColorKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_BASE_COLOR;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_BASE_COLOR;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_BASE_COLOR;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: return KEY_FULL_KEYBOARD_BASE_COLOR;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_BASE_COLOR;
            default: return null;
        }
    }

    private static String keyPressColorKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_PRESS_COLOR;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_PRESS_COLOR;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_PRESS_COLOR;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: return KEY_FULL_KEYBOARD_PRESS_COLOR;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_PRESS_COLOR;
            default: return null;
        }
    }

    private static String opacityKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_OPACITY;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_OPACITY;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_OPACITY;
            case KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT: return KEY_KEYBOARD_CAT_OPACITY;
            case MouseTrajectoryView.DISPLAY_TRAJECTORY: return KEY_MOUSE_TRAJECTORY_OPACITY;
            case GamepadOverlayView.DISPLAY_LEFT_STICK: return KEY_GAMEPAD_LEFT_STICK_OPACITY;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: return KEY_GAMEPAD_RIGHT_STICK_OPACITY;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_OPACITY;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_OPACITY;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_OPACITY;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_OPACITY;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_OPACITY;
            case DpsOverlayView.DISPLAY_DPS: return KEY_DPS_OPACITY;
            default: return null;
        }
    }

    public static int getMotionMode(Context context, int displayType) {
        return clampMotionMode(prefs(context).getInt(motionModeKey(displayType), MOTION_SIZE));
    }

    public static void setMotionMode(Context context, int displayType, int mode) {
        setIntAndRefresh(context, motionModeKey(displayType), clampMotionMode(mode));
    }

    public static boolean isGlobalHtmlEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GLOBAL_HTML_ENABLED, false);
    }

    public static void setGlobalHtmlEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GLOBAL_HTML_ENABLED, enabled);
    }

    public static String getGlobalHtmlName(Context context) {
        return prefs(context).getString(KEY_GLOBAL_HTML_NAME, "");
    }

    public static boolean hasGlobalHtml(Context context) {
        File file = globalHtmlFile(context);
        return file.isFile() && file.length() > 0;
    }

    public static void saveGlobalHtml(Context context, String displayName, String html) throws IOException {
        if (html == null) throw new IOException("HTML is null");
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > MAX_GLOBAL_HTML_BYTES) {
            throw new IOException("HTML size out of range");
        }
        AtomicFile file = new AtomicFile(globalHtmlFile(context));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(data);
            out.flush();
            out.getFD().sync();
            file.finishWrite(out);
        } catch (IOException error) {
            if (out != null) file.failWrite(out);
            throw error;
        }
        prefs(context).edit()
                .putString(KEY_GLOBAL_HTML_NAME, displayName == null ? "display.html" : displayName)
                .putBoolean(KEY_GLOBAL_HTML_ENABLED, true)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static String loadGlobalHtml(Context context) {
        File file = globalHtmlFile(context);
        if (!file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_GLOBAL_HTML_BYTES) return "";
                out.write(buffer, 0, read);
            }
            if (total == 0) return "";
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    public static int clampMotionMode(int mode) {
        if (mode == MOTION_ALPHA || mode == MOTION_NONE || mode == MOTION_RIPPLE) return mode;
        return MOTION_SIZE;
    }

    private static String motionModeKey(int displayType) {
        if (displayType == KeyOverlayView.DISPLAY_MOUSE) return KEY_MOUSE_MOTION_MODE;
        if (displayType == KeyOverlayView.DISPLAY_CUSTOM) return KEY_CUSTOM_MOTION_MODE;
        return KEY_KEYBOARD_MOTION_MODE;
    }

    private static File globalHtmlFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), GLOBAL_HTML_FILE);
    }

    private static File floatingVideoFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FLOATING_VIDEO_FILE);
    }

    public static int getPositionX(Context context, int displayType) {
        return getPosition(context, displayType, true);
    }

    public static int getPositionY(Context context, int displayType) {
        return getPosition(context, displayType, false);
    }

    /** 保存独立悬浮窗口的拖动位置。 */
    public static void savePosition(Context context, int displayType, int xPercent, int yPercent) {
        String xKey = positionKey(displayType, true);
        String yKey = positionKey(displayType, false);
        if (xKey == null || yKey == null) return;
        prefs(context).edit()
                .putInt(xKey, clampFreePositionPercent(xPercent))
                .putInt(yKey, clampFreePositionPercent(yPercent))
                .apply();
    }

    private static int getPosition(Context context, int displayType, boolean xAxis) {
        String key = positionKey(displayType, xAxis);
        if (key == null) return 50;
        return clampFreePositionPercent(
                prefs(context).getInt(key, defaultPosition(displayType, xAxis)));
    }

    private static String positionKey(int displayType, boolean xAxis) {
        return switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD -> xAxis ? KEY_KEYBOARD_POSITION_X : KEY_KEYBOARD_POSITION_Y;
            case KeyOverlayView.DISPLAY_CUSTOM -> xAxis ? KEY_CUSTOM_POSITION_X : KEY_CUSTOM_POSITION_Y;
            case KeyOverlayView.DISPLAY_MOUSE -> xAxis ? KEY_MOUSE_POSITION_X : KEY_MOUSE_POSITION_Y;
            case KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT -> xAxis ? KEY_KEYBOARD_CAT_POSITION_X : KEY_KEYBOARD_CAT_POSITION_Y;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT -> xAxis ? KEY_KEY_PROMPT_POSITION_X : KEY_KEY_PROMPT_POSITION_Y;
            case MouseTrajectoryView.DISPLAY_TRAJECTORY -> xAxis ? KEY_MOUSE_TRAJECTORY_POSITION_X : KEY_MOUSE_TRAJECTORY_POSITION_Y;
            case FloatingVideoOverlayView.DISPLAY_FLOATING_VIDEO -> xAxis ? KEY_FLOATING_VIDEO_POSITION_X : KEY_FLOATING_VIDEO_POSITION_Y;
            case DpsOverlayView.DISPLAY_DPS -> xAxis ? KEY_DPS_POSITION_X : KEY_DPS_POSITION_Y;
            case GamepadOverlayView.DISPLAY_LEFT_STICK -> xAxis ? KEY_GAMEPAD_LEFT_STICK_POSITION_X : KEY_GAMEPAD_LEFT_STICK_POSITION_Y;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK -> xAxis ? KEY_GAMEPAD_RIGHT_STICK_POSITION_X : KEY_GAMEPAD_RIGHT_STICK_POSITION_Y;
            case GamepadOverlayView.DISPLAY_FACE -> xAxis ? KEY_GAMEPAD_FACE_POSITION_X : KEY_GAMEPAD_FACE_POSITION_Y;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER -> xAxis ? KEY_GAMEPAD_LEFT_SHOULDER_POSITION_X : KEY_GAMEPAD_LEFT_SHOULDER_POSITION_Y;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER -> xAxis ? KEY_GAMEPAD_RIGHT_SHOULDER_POSITION_X : KEY_GAMEPAD_RIGHT_SHOULDER_POSITION_Y;
            case GamepadOverlayView.DISPLAY_BACK -> xAxis ? KEY_GAMEPAD_BACK_POSITION_X : KEY_GAMEPAD_BACK_POSITION_Y;
            default -> null;
        };
    }

    private static int defaultPosition(int displayType, boolean xAxis) {
        return switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD -> xAxis ? DEFAULT_KEYBOARD_X : DEFAULT_KEYBOARD_Y;
            case KeyOverlayView.DISPLAY_CUSTOM -> xAxis ? DEFAULT_CUSTOM_X : DEFAULT_CUSTOM_Y;
            case KeyOverlayView.DISPLAY_MOUSE -> xAxis ? DEFAULT_MOUSE_X : DEFAULT_MOUSE_Y;
            case KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT -> xAxis ? DEFAULT_KEYBOARD_CAT_X : DEFAULT_KEYBOARD_CAT_Y;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT -> xAxis ? DEFAULT_KEY_PROMPT_X : DEFAULT_KEY_PROMPT_Y;
            case MouseTrajectoryView.DISPLAY_TRAJECTORY -> xAxis ? DEFAULT_MOUSE_TRAJECTORY_X : DEFAULT_MOUSE_TRAJECTORY_Y;
            case FloatingVideoOverlayView.DISPLAY_FLOATING_VIDEO -> xAxis ? DEFAULT_FLOATING_VIDEO_X : DEFAULT_FLOATING_VIDEO_Y;
            case DpsOverlayView.DISPLAY_DPS -> xAxis ? DEFAULT_DPS_X : DEFAULT_DPS_Y;
            case GamepadOverlayView.DISPLAY_LEFT_STICK -> xAxis ? DEFAULT_GAMEPAD_LEFT_STICK_X : DEFAULT_GAMEPAD_LEFT_STICK_Y;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK -> xAxis ? DEFAULT_GAMEPAD_RIGHT_STICK_X : DEFAULT_GAMEPAD_RIGHT_STICK_Y;
            case GamepadOverlayView.DISPLAY_FACE -> xAxis ? DEFAULT_GAMEPAD_FACE_X : DEFAULT_GAMEPAD_FACE_Y;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER -> xAxis ? DEFAULT_GAMEPAD_LEFT_SHOULDER_X : DEFAULT_GAMEPAD_LEFT_SHOULDER_Y;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER -> xAxis ? DEFAULT_GAMEPAD_RIGHT_SHOULDER_X : DEFAULT_GAMEPAD_RIGHT_SHOULDER_Y;
            case GamepadOverlayView.DISPLAY_BACK -> xAxis ? DEFAULT_GAMEPAD_BACK_X : DEFAULT_GAMEPAD_BACK_Y;
            default -> 50;
        };
    }

    private static int clampSensitivity(int value) {
        return Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, value));
    }

    private static int clampOpacity(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int clampSize(int value) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
    }

    private static int clampKeySpacing(int value) {
        return Math.max(MIN_KEY_SPACING, Math.min(MAX_KEY_SPACING, value));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /** 拖动位置可超出屏幕。仅限制异常坐标。 */
    private static int clampFreePositionPercent(int value) {
        return Math.max(-1000, Math.min(1000, value));
    }

    private static int clampColumns(int value) {
        return Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, value));
    }

    private static List<Integer> parseCodes(String raw) {
        Set<Integer> unique = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split(",");
            for (String part : parts) {
                try {
                    int code = Integer.parseInt(part.trim());
                    if (code > 0) unique.add(code);
                    if (unique.size() >= MAX_CUSTOM_KEYS) break;
                } catch (NumberFormatException ignored) {}
            }
        }
        return new ArrayList<>(unique);
    }

    private static String encodeCodes(List<Integer> codes) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) out.append(',');
            out.append(codes.get(i));
        }
        return out.toString();
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static void setBooleanAndRefresh(Context context, String key, boolean enabled) {
        prefs(context).edit().putBoolean(key, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    private static void setIntAndRefresh(Context context, String key, int value) {
        prefs(context).edit().putInt(key, value).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    static SharedPreferences preferencesForConfig(Context context) {
        return prefs(context);
    }

    static File globalHtmlFileForConfig(Context context) {
        return globalHtmlFile(context);
    }

    static void refreshAfterConfigChange(Context context) {
        AxonInputAccessibilityService.refreshTheme();
        AxonInputAccessibilityService.refreshActiveService();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences durablePrefs(Context context) {
        return context.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE);
    }
}
