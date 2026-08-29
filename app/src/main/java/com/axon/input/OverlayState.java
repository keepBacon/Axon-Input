package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;


import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 运行配置入口。普通设置保留到任务退出，长期数据单独保存。 */
public final class OverlayState {
    public static final int MOTION_SIZE = 0;
    public static final int MOTION_ALPHA = 1;
    public static final int MOTION_NONE = 2;
    public static final int MOTION_RIPPLE = 3;
    public static final int UI_THEME_LIGHT = 0;
    public static final int UI_THEME_BLACK = 1;
    public static final int RENDER_QUALITY_NORMAL = 0;
    public static final int RENDER_QUALITY_CLEAR = 1;
    /** Visual settings id for the touch-region key display; kept separate from trajectory DISPLAY=4. */
    public static final int DISPLAY_TOUCH_APPEARANCE = 50;
    public static final int DPS_TARGET_NONE = -1;
    public static final int DPS_TARGET_MOUSE_LEFT = 0x10000;
    public static final int DPS_TARGET_MOUSE_RIGHT = 0x10001;
    public static final int DPS_TARGET_MOUSE_MIDDLE = 0x10002;
    public static final int DPS_TARGET_MOUSE_BACK = 0x10003;
    public static final int DPS_TARGET_MOUSE_FORWARD = 0x10004;
    public static final int DPS_TARGET_GAMEPAD_BASE = 0x20000; // legacy v1.6 encoding
    public static final int HIDE_DISPLAY_NONE = 0;
    public static final int HIDE_DISPLAY_KEYBOARD = 1;
    public static final int HIDE_DISPLAY_FULL_KEYBOARD = 2;
    public static final int HIDE_DISPLAY_MOUSE = 3;
    public static final int HIDE_DISPLAY_KEYBOARD_CAT = 4;
    public static final int HIDE_DISPLAY_KEY_PROMPT = 5;
    public static final int HIDE_DISPLAY_MOUSE_TRAJECTORY = 6;
    public static final int HIDE_DISPLAY_CUSTOM = 7;
    public static final int HIDE_DISPLAY_SUPER_CUSTOM = 8;
    public static final int HIDE_DISPLAY_TOUCH = 9;
    public static final int HIDE_DISPLAY_DPS = 10;
    public static final int HIDE_DISPLAY_GAMEPAD_LEFT_STICK = 11;
    public static final int HIDE_DISPLAY_GAMEPAD_RIGHT_STICK = 12;
    public static final int HIDE_DISPLAY_GAMEPAD_FACE = 13;
    public static final int HIDE_DISPLAY_GAMEPAD_DPAD = 14;
    public static final int HIDE_DISPLAY_GAMEPAD_LEFT_SHOULDER = 15;
    public static final int HIDE_DISPLAY_GAMEPAD_RIGHT_SHOULDER = 16;
    public static final int HIDE_DISPLAY_GAMEPAD_BACK = 17;
    private static final int DPS_TARGET_GAMEPAD_EXT_BASE = 0x02000000;
    private static final String DURABLE_PREFS = "key_display_durable";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MOUSE_ENABLED = "mouse_enabled";
    private static final String KEY_KEYBOARD_CAT_ENABLED = "keyboard_cat_enabled";
    private static final String KEY_KEYBOARD_CAT_RENDER_QUALITY = "keyboard_cat_render_quality";
    private static final String KEY_LIVE2D_ENABLED = "live2d_display_enabled";
    private static final String KEY_LIVE2D_RENDER_QUALITY = "live2d_render_quality";
    private static final String KEY_LIVE2D_SIZE = "live2d_display_size";
    private static final String KEY_LIVE2D_MOTION_TRACKING = "live2d_motion_tracking_enabled";
    private static final String KEY_LIVE2D_MOUSE_CAPTURE = "live2d_mouse_capture_enabled";
    private static final String KEY_LIVE2D_HIDE_WATERMARK = "live2d_hide_watermark";
    // Normalized Live2D visual offset, stored as thousandths of half-screen width/height.
    // This moves only the model inside the full-screen renderer; the accessibility window itself
    // remains full-display so Cubism can keep its existing projection/canvas sizing.
    private static final String KEY_LIVE2D_OFFSET_X = "live2d_display_offset_x_milli";
    private static final String KEY_LIVE2D_OFFSET_Y = "live2d_display_offset_y_milli";
    private static final String KEY_KEYBOARD_CAT_MOUSE_MODE = "keyboard_cat_mouse_mode";
    private static final String KEY_KEYBOARD_CAT_GLOBAL_REVERSE = "keyboard_cat_global_reverse";
    private static final String KEY_KEYBOARD_CAT_STYLE_ID = "keyboard_cat_style_id";
    private static final String KEY_KEYBOARD_CAT_DEBUG_EXPRESSION = "keyboard_cat_debug_expression";
    private static final String KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_KEY_CODE = "keyboard_cat_expression_hotkey_key_code";
    private static final String KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_SELECTION_PREFIX = "keyboard_cat_expression_hotkey_selection_";
    private static final String KEY_HIDE_DISPLAY_HOTKEY_ENABLED = "hide_display_hotkey_enabled";
    private static final String KEY_HIDE_DISPLAY_HOTKEY_INPUT = "hide_display_hotkey_input";
    private static final String KEY_HIDE_DISPLAY_HOTKEY_TARGETS = "hide_display_hotkey_targets";
    private static final String KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS = "hide_display_hotkey_hidden_targets";
    private static final String KEY_INPUT_FULL_KEYBOARD_ENABLED = "input_full_keyboard_enabled";
    private static final String KEY_KEY_PROMPT_ENABLED = "key_prompt_enabled";
    private static final String KEY_MOUSE_TRAJECTORY_ENABLED = "mouse_trajectory_enabled";
    private static final String KEY_CUSTOM_ENABLED = "custom_enabled";
    // v1.6fix introduced an explicit user-facing display switch. Keep the dedicated preference key so
    // installs upgraded from the previous auto-display build start with this new switch OFF rather
    // than inheriting the old hidden runtime flag that loadSlotIntoActive() used to force on.
    private static final String KEY_SUPER_CUSTOM_ENABLED = "super_custom_display_enabled_v2";
    private static final String KEY_DRAG_ENABLED = "drag_enabled";
    private static final String KEY_FORCE_HOLD_ENABLED = "force_hold_enabled";
    private static final String KEY_FORCE_HOLD_TARGET_KEY_CODE = "force_hold_target_key_code";
    private static final String KEY_FORCE_HOLD_TARGET_SCAN_CODE = "force_hold_target_scan_code";
    private static final String KEY_FORCE_HOLD_TRIGGER_KEY_CODE = "force_hold_trigger_key_code";
    private static final String KEY_DPS_ENABLED = "dps_enabled";
    private static final String KEY_DPS_TARGET_KEY_CODE = "dps_target_key_code";
    private static final String KEY_DPS_OPACITY = "dps_opacity";
    private static final String KEY_DPS_SIZE = "dps_size";
    private static final String KEY_DPS_TEXT_COLOR = "dps_text_color";
    private static final String KEY_DPS_POSITION_X = "dps_position_x";
    private static final String KEY_DPS_POSITION_Y = "dps_position_y";
    private static final String KEY_CUSTOM_CAPTURE = "custom_capture";
    private static final String KEY_CUSTOM_KEYS = "custom_keys";
    private static final String KEY_CUSTOM_DRAFT = "custom_draft";
    private static final String KEY_CUSTOM_COLUMNS = "custom_columns";
    private static final String KEY_KEYBOARD_SIZE = "keyboard_size";
    private static final String KEY_FULL_KEYBOARD_SIZE = "full_keyboard_size";
    private static final String KEY_TOUCH_SIZE = "touch_display_size";
    private static final String KEY_KEYBOARD_SPACING = "keyboard_spacing";
    private static final String KEY_TOUCH_SPACING = "touch_display_spacing";
    private static final String KEY_KEYBOARD_SPACE_ENABLED = "keyboard_space_enabled";
    private static final String KEY_KEYBOARD_SPACE_DPS_ENABLED = "keyboard_space_dps_enabled";
    private static final String KEY_KEYBOARD_SPACE_DASH_ENABLED = "keyboard_space_dash_enabled";
    private static final String KEY_KEYBOARD_MOUSE_BUTTONS_ENABLED = "keyboard_mouse_buttons_enabled";
    private static final String KEY_KEYBOARD_MOUSE_CPS_ENABLED = "keyboard_mouse_cps_enabled";
    private static final String KEY_CUSTOM_SIZE = "custom_size";
    private static final String KEY_CUSTOM_SPACING = "custom_spacing";
    private static final String KEY_MOUSE_SIZE = "mouse_size";
    private static final String KEY_KEYBOARD_CAT_SIZE = "keyboard_cat_size";
    private static final String KEY_KEY_PROMPT_SIZE = "key_prompt_size";
    private static final String KEY_MOUSE_TRAJECTORY_SIZE = "mouse_trajectory_size";
    private static final String KEY_MOUSE_TRAJECTORY_DOT_SIZE = "mouse_trajectory_dot_size";
    private static final String KEY_KEYBOARD_OPACITY = "keyboard_opacity";
    private static final String KEY_FULL_KEYBOARD_OPACITY = "full_keyboard_opacity";
    private static final String KEY_TOUCH_OPACITY = "touch_display_opacity";
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
    private static final String KEY_TOUCH_KEY_STYLE = "touch_display_key_style";
    private static final String KEY_GAMEPAD_FACE_KEY_STYLE = "gamepad_face_key_style";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_KEY_STYLE = "gamepad_left_shoulder_key_style";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_KEY_STYLE = "gamepad_right_shoulder_key_style";
    private static final String KEY_KEYBOARD_CORNER_STRENGTH = "keyboard_corner_strength";
    private static final String KEY_MOUSE_CORNER_STRENGTH = "mouse_corner_strength";
    private static final String KEY_KEY_PROMPT_CORNER_STRENGTH = "key_prompt_corner_strength";
    private static final String KEY_FULL_KEYBOARD_CORNER_STRENGTH = "full_keyboard_corner_strength";
    private static final String KEY_CUSTOM_CORNER_STRENGTH = "custom_corner_strength";
    private static final String KEY_TOUCH_CORNER_STRENGTH = "touch_display_corner_strength";
    private static final String KEY_GAMEPAD_FACE_CORNER_STRENGTH = "gamepad_face_corner_strength";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_CORNER_STRENGTH = "gamepad_left_shoulder_corner_strength";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_CORNER_STRENGTH = "gamepad_right_shoulder_corner_strength";
    private static final String KEY_KEYBOARD_BASE_COLOR = "keyboard_base_color";
    private static final String KEY_KEYBOARD_BORDER_COLOR = "keyboard_border_color";
    private static final String KEY_KEYBOARD_PRESS_COLOR = "keyboard_press_color";
    private static final String KEY_KEYBOARD_TEXT_COLOR = "keyboard_text_color";
    private static final String KEY_MOUSE_TEXT_COLOR = "mouse_text_color";
    private static final String KEY_KEY_PROMPT_TEXT_COLOR = "key_prompt_text_color";
    private static final String KEY_FULL_KEYBOARD_TEXT_COLOR = "full_keyboard_text_color";
    private static final String KEY_CUSTOM_TEXT_COLOR = "custom_text_color";
    private static final String KEY_TOUCH_TEXT_COLOR = "touch_display_text_color";
    private static final String KEY_GAMEPAD_FACE_TEXT_COLOR = "gamepad_face_text_color";
    private static final String KEY_GAMEPAD_DPAD_TEXT_COLOR = "gamepad_dpad_text_color";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_TEXT_COLOR = "gamepad_left_shoulder_text_color";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_TEXT_COLOR = "gamepad_right_shoulder_text_color";
    private static final String KEY_GAMEPAD_BACK_TEXT_COLOR = "gamepad_back_text_color";
    private static final String KEY_MOUSE_BASE_COLOR = "mouse_base_color";
    private static final String KEY_MOUSE_BORDER_COLOR = "mouse_border_color";
    private static final String KEY_MOUSE_PRESS_COLOR = "mouse_press_color";
    private static final String KEY_KEY_PROMPT_BASE_COLOR = "key_prompt_base_color";
    private static final String KEY_KEY_PROMPT_BORDER_COLOR = "key_prompt_border_color";
    private static final String KEY_KEY_PROMPT_PRESS_COLOR = "key_prompt_press_color";
    private static final String KEY_FULL_KEYBOARD_BASE_COLOR = "full_keyboard_base_color";
    private static final String KEY_FULL_KEYBOARD_BORDER_COLOR = "full_keyboard_border_color";
    private static final String KEY_FULL_KEYBOARD_PRESS_COLOR = "full_keyboard_press_color";
    private static final String KEY_CUSTOM_BASE_COLOR = "custom_base_color";
    private static final String KEY_CUSTOM_BORDER_COLOR = "custom_border_color";
    private static final String KEY_CUSTOM_PRESS_COLOR = "custom_press_color";
    private static final String KEY_TOUCH_BASE_COLOR = "touch_display_base_color";
    private static final String KEY_TOUCH_BORDER_COLOR = "touch_display_border_color";
    private static final String KEY_TOUCH_PRESS_COLOR = "touch_display_press_color";
    private static final String KEY_GAMEPAD_FACE_BASE_COLOR = "gamepad_face_base_color";
    private static final String KEY_GAMEPAD_FACE_BORDER_COLOR = "gamepad_face_border_color";
    private static final String KEY_GAMEPAD_FACE_PRESS_COLOR = "gamepad_face_press_color";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_BASE_COLOR = "gamepad_left_shoulder_base_color";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_BORDER_COLOR = "gamepad_left_shoulder_border_color";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_PRESS_COLOR = "gamepad_left_shoulder_press_color";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_BASE_COLOR = "gamepad_right_shoulder_base_color";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_BORDER_COLOR = "gamepad_right_shoulder_border_color";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_PRESS_COLOR = "gamepad_right_shoulder_press_color";
    private static final String KEY_GAMEPAD_BACK_KEY_STYLE = "gamepad_back_key_style";
    private static final String KEY_GAMEPAD_BACK_CORNER_STRENGTH = "gamepad_back_corner_strength";
    private static final String KEY_GAMEPAD_BACK_BASE_COLOR = "gamepad_back_base_color";
    private static final String KEY_GAMEPAD_BACK_BORDER_COLOR = "gamepad_back_border_color";
    private static final String KEY_GAMEPAD_BACK_PRESS_COLOR = "gamepad_back_press_color";
    private static final String KEY_GAMEPAD_DPAD_KEY_STYLE = "gamepad_dpad_key_style";
    private static final String KEY_GAMEPAD_DPAD_CORNER_STRENGTH = "gamepad_dpad_corner_strength";
    private static final String KEY_GAMEPAD_DPAD_BASE_COLOR = "gamepad_dpad_base_color";
    private static final String KEY_GAMEPAD_DPAD_BORDER_COLOR = "gamepad_dpad_border_color";
    private static final String KEY_GAMEPAD_DPAD_PRESS_COLOR = "gamepad_dpad_press_color";
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
    private static final String KEY_AUTO_HIDE_BACKGROUND = "auto_hide_background";
    private static final String KEY_ENTRY_AUTHORIZED = "entry_authorized";
    private static final String KEY_LAST_CLOUD_NOTICE_ID = "last_cloud_notice_id";
    private static final String KEY_KEYBOARD_MOTION_MODE = "keyboard_motion_mode";
    private static final String KEY_FULL_KEYBOARD_MOTION_MODE = "full_keyboard_motion_mode";
    private static final String KEY_MOUSE_MOTION_MODE = "mouse_motion_mode";
    private static final String KEY_KEY_PROMPT_MOTION_MODE = "key_prompt_motion_mode";
    private static final String KEY_CUSTOM_MOTION_MODE = "custom_motion_mode";
    private static final String KEY_TOUCH_MOTION_MODE = "touch_display_motion_mode";
    private static final String KEY_GAMEPAD_LEFT_STICK_MOTION_MODE = "gamepad_left_stick_motion_mode";
    private static final String KEY_GAMEPAD_RIGHT_STICK_MOTION_MODE = "gamepad_right_stick_motion_mode";
    private static final String KEY_GAMEPAD_FACE_MOTION_MODE = "gamepad_face_motion_mode";
    private static final String KEY_GAMEPAD_DPAD_MOTION_MODE = "gamepad_dpad_motion_mode";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_MOTION_MODE = "gamepad_left_shoulder_motion_mode";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_MOTION_MODE = "gamepad_right_shoulder_motion_mode";
    private static final String KEY_GAMEPAD_BACK_MOTION_MODE = "gamepad_back_motion_mode";
    private static final String KEY_UI_THEME = "ui_theme";
    private static final String KEY_GAMEPAD_LEFT_STICK_ENABLED = "gamepad_left_stick_enabled";
    private static final String KEY_GAMEPAD_RIGHT_STICK_ENABLED = "gamepad_right_stick_enabled";
    private static final String KEY_GAMEPAD_FACE_ENABLED = "gamepad_face_enabled";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_ENABLED = "gamepad_left_shoulder_enabled";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED = "gamepad_right_shoulder_enabled";
    private static final String KEY_GAMEPAD_BACK_ENABLED = "gamepad_back_enabled";
    private static final String KEY_GAMEPAD_DPAD_ENABLED = "gamepad_dpad_enabled";
    private static final String KEY_GAMEPAD_LEFT_STICK_SHAPE = "gamepad_left_stick_shape";
    private static final String KEY_GAMEPAD_RIGHT_STICK_SHAPE = "gamepad_right_stick_shape";
    private static final String KEY_GAMEPAD_LEFT_STICK_CORNER_STRENGTH = "gamepad_left_stick_corner_strength";
    private static final String KEY_GAMEPAD_RIGHT_STICK_CORNER_STRENGTH = "gamepad_right_stick_corner_strength";
    private static final String KEY_GAMEPAD_LEFT_STICK_BASE_COLOR = "gamepad_left_stick_base_color";
    private static final String KEY_GAMEPAD_LEFT_STICK_BORDER_COLOR = "gamepad_left_stick_border_color";
    private static final String KEY_GAMEPAD_LEFT_STICK_PRESS_COLOR = "gamepad_left_stick_press_color";
    private static final String KEY_GAMEPAD_RIGHT_STICK_BASE_COLOR = "gamepad_right_stick_base_color";
    private static final String KEY_GAMEPAD_RIGHT_STICK_BORDER_COLOR = "gamepad_right_stick_border_color";
    private static final String KEY_GAMEPAD_RIGHT_STICK_PRESS_COLOR = "gamepad_right_stick_press_color";
    private static final String KEY_GAMEPAD_FACE_REVERSED = "gamepad_face_reversed";
    private static final String KEY_GAMEPAD_FACE_SYMBOL_ICONS = "gamepad_face_symbol_icons";
    private static final String KEY_GAMEPAD_LEFT_STICK_SIZE = "gamepad_left_stick_size";
    private static final String KEY_GAMEPAD_RIGHT_STICK_SIZE = "gamepad_right_stick_size";
    private static final String KEY_GAMEPAD_LEFT_STICK_DOT_SIZE = "gamepad_left_stick_dot_size";
    private static final String KEY_GAMEPAD_RIGHT_STICK_DOT_SIZE = "gamepad_right_stick_dot_size";
    private static final String KEY_GAMEPAD_LEFT_STICK_CENTER_CORNER_STRENGTH = "gamepad_left_stick_center_corner_strength";
    private static final String KEY_GAMEPAD_RIGHT_STICK_CENTER_CORNER_STRENGTH = "gamepad_right_stick_center_corner_strength";
    private static final String KEY_GAMEPAD_LEFT_STICK_CENTER_COLOR = "gamepad_left_stick_center_color";
    private static final String KEY_GAMEPAD_RIGHT_STICK_CENTER_COLOR = "gamepad_right_stick_center_color";
    private static final String KEY_GAMEPAD_FACE_SIZE = "gamepad_face_size";
    private static final String KEY_GAMEPAD_FACE_SPACING = "gamepad_face_spacing";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_SIZE = "gamepad_left_shoulder_size";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_SIZE = "gamepad_right_shoulder_size";
    private static final String KEY_GAMEPAD_BACK_SIZE = "gamepad_back_size";
    private static final String KEY_GAMEPAD_DPAD_SIZE = "gamepad_dpad_size";
    private static final String KEY_GAMEPAD_LEFT_STICK_OPACITY = "gamepad_left_stick_opacity";
    private static final String KEY_GAMEPAD_RIGHT_STICK_OPACITY = "gamepad_right_stick_opacity";
    private static final String KEY_GAMEPAD_FACE_OPACITY = "gamepad_face_opacity";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_OPACITY = "gamepad_left_shoulder_opacity";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_OPACITY = "gamepad_right_shoulder_opacity";
    private static final String KEY_GAMEPAD_BACK_OPACITY = "gamepad_back_opacity";
    private static final String KEY_GAMEPAD_DPAD_OPACITY = "gamepad_dpad_opacity";
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
    private static final String KEY_GAMEPAD_DPAD_POSITION_X = "gamepad_dpad_position_x";
    private static final String KEY_GAMEPAD_DPAD_POSITION_Y = "gamepad_dpad_position_y";

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
    private static final int DEFAULT_GAMEPAD_DPAD_X = 20;
    private static final int DEFAULT_GAMEPAD_DPAD_Y = 42;
    private static final int DEFAULT_COLUMNS = 4;
    private static final int MIN_COLUMNS = 1;
    private static final int MAX_COLUMNS = 12;
    private static final int MAX_CUSTOM_KEYS = 64;
    private static final int DEFAULT_SIZE = 100;
    private static final int DEFAULT_KEYBOARD_SPACING = 8;
    private static final int DEFAULT_CUSTOM_SPACING = 6;
    private static final int DEFAULT_GAMEPAD_FACE_SPACING = 8;
    private static final int MIN_KEY_SPACING = 0;
    private static final int MAX_KEY_SPACING = 40;
    private static final int DEFAULT_OPACITY = 100;
    private static final int DEFAULT_MOUSE_TRAJECTORY_LEFT_COLOR = 0xffff3b30;
    private static final int DEFAULT_MOUSE_TRAJECTORY_RIGHT_COLOR = 0xff34c759;
    private static final int MIN_SIZE = 25;
    private static final int MAX_SIZE = 300;

    private OverlayState() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_ENABLED, enabled);
    }

    public static boolean isInputFullKeyboardEnabled(Context context) {
        return prefs(context).getBoolean(KEY_INPUT_FULL_KEYBOARD_ENABLED, false);
    }

    public static void setInputFullKeyboardEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_INPUT_FULL_KEYBOARD_ENABLED, enabled);
    }

    public static boolean isSuperCustomEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SUPER_CUSTOM_ENABLED, false);
    }

    public static void setSuperCustomEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_SUPER_CUSTOM_ENABLED, enabled);
    }

    public static boolean isMouseEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_ENABLED, false);
    }

    public static void setMouseEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_MOUSE_ENABLED, enabled);
    }

    public static boolean isKeyboardCatEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_CAT_ENABLED, false);
    }

    public static void setKeyboardCatEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_KEYBOARD_CAT_ENABLED, enabled);
    }

    public static int getKeyboardCatRenderQuality(Context context) {
        return clampRenderQuality(durablePrefs(context).getInt(
                KEY_KEYBOARD_CAT_RENDER_QUALITY, RENDER_QUALITY_NORMAL));
    }

    public static void setKeyboardCatRenderQuality(Context context, int quality) {
        int value = clampRenderQuality(quality);
        if (PreferenceWriter.putIntIfChanged(durablePrefs(context), KEY_KEYBOARD_CAT_RENDER_QUALITY, value)) {
            AxonInputAccessibilityService.refreshKeyboardCatQuality();
        }
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
        if (PreferenceWriter.putStringIfChanged(durablePrefs(context), KEY_KEYBOARD_CAT_STYLE_ID, value)) {
            AxonInputAccessibilityService.refreshActiveService();
        }
    }

    /** Debug-only expression override for imported Bongo Cat styles. "auto" restores authored behavior. */
    public static String getKeyboardCatDebugExpression(Context context) {
        String value = prefs(context).getString(KEY_KEYBOARD_CAT_DEBUG_EXPRESSION, "auto");
        return value == null || value.isEmpty() ? "auto" : value;
    }

    public static void setKeyboardCatDebugExpression(Context context, String token) {
        String value = token == null || token.isEmpty() ? "auto" : token;
        if (PreferenceWriter.putStringIfChanged(prefs(context), KEY_KEYBOARD_CAT_DEBUG_EXPRESSION, value)) {
            AxonInputAccessibilityService.refreshActiveService();
        }
    }

    /** Hotkey path: persist the expression without rebuilding every overlay window. */
    static void setKeyboardCatDebugExpressionRuntime(Context context, String token) {
        String value = token == null || token.isEmpty() ? "auto" : token;
        PreferenceWriter.putStringIfChanged(prefs(context), KEY_KEYBOARD_CAT_DEBUG_EXPRESSION, value);
    }

    public static int getKeyboardCatExpressionHotkeyKeyCode(Context context) {
        return prefs(context).getInt(KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_KEY_CODE, -1);
    }

    public static void setKeyboardCatExpressionHotkeyKeyCode(Context context, int keyCode) {
        SharedPreferences values = prefs(context);
        int current = values.getInt(KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_KEY_CODE, -1);
        int next = keyCode >= 0 ? keyCode : -1;
        if (current == next) return;
        SharedPreferences.Editor editor = values.edit();
        if (next >= 0) editor.putInt(KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_KEY_CODE, next);
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
        PreferenceWriter.putStringSetIfChanged(
                prefs(context), keyboardCatExpressionSelectionKey(styleId), clean);
    }

    private static String keyboardCatExpressionSelectionKey(String styleId) {
        String value = styleId == null || styleId.isEmpty() ? BongoCatStyleManager.BUILTIN_ID : styleId;
        return KEY_KEYBOARD_CAT_EXPRESSION_HOTKEY_SELECTION_PREFIX + value;
    }

    public static boolean isHideDisplayHotkeyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_DISPLAY_HOTKEY_ENABLED, false);
    }

    public static void setHideDisplayHotkeyEnabled(Context context, boolean enabled) {
        SharedPreferences values = prefs(context);
        boolean current = values.getBoolean(KEY_HIDE_DISPLAY_HOTKEY_ENABLED, false);
        if (current == enabled) return;
        SharedPreferences.Editor editor = values.edit().putBoolean(KEY_HIDE_DISPLAY_HOTKEY_ENABLED, enabled);
        if (!enabled) editor.remove(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS);
        editor.apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getHideDisplayHotkeyInputCode(Context context) {
        return prefs(context).getInt(KEY_HIDE_DISPLAY_HOTKEY_INPUT, -1);
    }

    public static void setHideDisplayHotkeyInputCode(Context context, int inputCode) {
        SharedPreferences values = prefs(context);
        int next = InputBinding.isValid(inputCode) ? inputCode : -1;
        int current = values.getInt(KEY_HIDE_DISPLAY_HOTKEY_INPUT, -1);
        if (current == next) return;
        SharedPreferences.Editor editor = values.edit();
        if (next >= 0) editor.putInt(KEY_HIDE_DISPLAY_HOTKEY_INPUT, next);
        else editor.remove(KEY_HIDE_DISPLAY_HOTKEY_INPUT);
        editor.apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static Set<Integer> getHideDisplayHotkeyTargets(Context context) {
        return decodeHideDisplayTargets(prefs(context).getStringSet(KEY_HIDE_DISPLAY_HOTKEY_TARGETS, null));
    }

    public static void setHideDisplayHotkeyTargets(Context context, Set<Integer> targets) {
        LinkedHashSet<Integer> clean = new LinkedHashSet<>();
        if (targets != null) {
            for (Integer target : targets) {
                if (target != null && isKnownHideDisplayTarget(target)
                        && isHideDisplayTargetEnabled(context, target)) clean.add(target);
            }
        }
        Set<Integer> current = getHideDisplayHotkeyTargets(context);
        if (current.equals(clean)) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        if (clean.isEmpty()) editor.remove(KEY_HIDE_DISPLAY_HOTKEY_TARGETS);
        else editor.putStringSet(KEY_HIDE_DISPLAY_HOTKEY_TARGETS, encodeHideDisplayTargets(clean));
        Set<Integer> hidden = getHiddenDisplayHotkeyTargets(context);
        hidden.retainAll(clean);
        if (hidden.isEmpty()) editor.remove(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS);
        else editor.putStringSet(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS, encodeHideDisplayTargets(hidden));
        editor.apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static Set<Integer> getHiddenDisplayHotkeyTargets(Context context) {
        return decodeHideDisplayTargets(prefs(context).getStringSet(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS, null));
    }

    public static boolean isHideDisplayTargetHidden(Context context, int target) {
        return target != HIDE_DISPLAY_NONE && getHiddenDisplayHotkeyTargets(context).contains(target);
    }

    /** Toggle all selected overlay windows together; feature enable switches remain untouched. */
    public static boolean toggleHideDisplayTargets(Context context) {
        if (!isHideDisplayHotkeyEnabled(context)) return false;
        LinkedHashSet<Integer> selected = new LinkedHashSet<>(getHideDisplayHotkeyTargets(context));
        selected.removeIf(target -> !isHideDisplayTargetEnabled(context, target));
        if (selected.isEmpty()) return false;
        LinkedHashSet<Integer> hidden = new LinkedHashSet<>(getHiddenDisplayHotkeyTargets(context));
        boolean allHidden = hidden.containsAll(selected);
        if (allHidden) hidden.removeAll(selected);
        else hidden.addAll(selected);
        SharedPreferences.Editor editor = prefs(context).edit();
        if (hidden.isEmpty()) editor.remove(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS);
        else editor.putStringSet(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS, encodeHideDisplayTargets(hidden));
        editor.apply();
        boolean nowHidden = !allHidden;
        if (nowHidden) AxonInputAccessibilityService.refreshDisplayVisibilityImmediate();
        else AxonInputAccessibilityService.refreshActiveService();
        return nowHidden;
    }

    public static void clearHiddenDisplayHotkeyTargets(Context context) {
        if (!prefs(context).contains(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS)) return;
        prefs(context).edit().remove(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isHideDisplayTargetEnabled(Context context, int target) {
        switch (target) {
            case HIDE_DISPLAY_KEYBOARD: return isEnabled(context);
            case HIDE_DISPLAY_FULL_KEYBOARD: return isInputFullKeyboardEnabled(context);
            case HIDE_DISPLAY_MOUSE: return isMouseEnabled(context);
            case HIDE_DISPLAY_KEYBOARD_CAT: return isKeyboardCatEnabled(context);
            case HIDE_DISPLAY_KEY_PROMPT: return isKeyPromptEnabled(context);
            case HIDE_DISPLAY_MOUSE_TRAJECTORY: return isMouseTrajectoryEnabled(context);
            case HIDE_DISPLAY_CUSTOM: return isCustomEnabled(context);
            case HIDE_DISPLAY_SUPER_CUSTOM: return isSuperCustomEnabled(context);
            case HIDE_DISPLAY_TOUCH: return TouchDisplayStore.isEnabled(context);
            case HIDE_DISPLAY_DPS: return isDpsEnabled(context);
            case HIDE_DISPLAY_GAMEPAD_LEFT_STICK: return isGamepadLeftStickEnabled(context);
            case HIDE_DISPLAY_GAMEPAD_RIGHT_STICK: return isGamepadRightStickEnabled(context);
            case HIDE_DISPLAY_GAMEPAD_FACE: return isGamepadFaceEnabled(context);
            case HIDE_DISPLAY_GAMEPAD_DPAD: return isGamepadDpadEnabled(context);
            case HIDE_DISPLAY_GAMEPAD_LEFT_SHOULDER: return isGamepadLeftShoulderEnabled(context);
            case HIDE_DISPLAY_GAMEPAD_RIGHT_SHOULDER: return isGamepadRightShoulderEnabled(context);
            case HIDE_DISPLAY_GAMEPAD_BACK: return isGamepadBackEnabled(context);
            default: return false;
        }
    }

    public static List<Integer> getEnabledHideDisplayTargets(Context context) {
        ArrayList<Integer> targets = new ArrayList<>();
        for (int target = HIDE_DISPLAY_KEYBOARD; target <= HIDE_DISPLAY_GAMEPAD_BACK; target++) {
            if (isHideDisplayTargetEnabled(context, target)) targets.add(target);
        }
        return targets;
    }

    /** Remove selection/hidden flags for displays that have since been switched off. */
    public static void sanitizeHiddenDisplayHotkeyTargets(Context context) {
        Set<Integer> selected = getHideDisplayHotkeyTargets(context);
        LinkedHashSet<Integer> validSelected = new LinkedHashSet<>();
        for (int target : selected) if (isHideDisplayTargetEnabled(context, target)) validSelected.add(target);
        Set<Integer> hidden = getHiddenDisplayHotkeyTargets(context);
        LinkedHashSet<Integer> validHidden = new LinkedHashSet<>();
        for (int target : hidden) if (validSelected.contains(target)) validHidden.add(target);
        if (selected.equals(validSelected) && hidden.equals(validHidden)) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        if (validSelected.isEmpty()) editor.remove(KEY_HIDE_DISPLAY_HOTKEY_TARGETS);
        else editor.putStringSet(KEY_HIDE_DISPLAY_HOTKEY_TARGETS, encodeHideDisplayTargets(validSelected));
        if (validHidden.isEmpty()) editor.remove(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS);
        else editor.putStringSet(KEY_HIDE_DISPLAY_HOTKEY_HIDDEN_TARGETS, encodeHideDisplayTargets(validHidden));
        editor.apply();
    }

    private static LinkedHashSet<Integer> decodeHideDisplayTargets(Set<String> stored) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        if (stored == null) return out;
        for (String raw : stored) {
            try {
                int target = Integer.parseInt(raw);
                if (isKnownHideDisplayTarget(target)) out.add(target);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static LinkedHashSet<String> encodeHideDisplayTargets(Set<Integer> targets) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (targets != null) for (Integer target : targets) if (target != null) out.add(String.valueOf(target));
        return out;
    }

    private static boolean isKnownHideDisplayTarget(int target) {
        return target >= HIDE_DISPLAY_KEYBOARD && target <= HIDE_DISPLAY_GAMEPAD_BACK;
    }

    public static boolean isKeyPromptEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEY_PROMPT_ENABLED, false);
    }

    public static void setKeyPromptEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_KEY_PROMPT_ENABLED, enabled);
    }

    public static boolean isMouseTrajectoryEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_TRAJECTORY_ENABLED, false);
    }

    public static void setMouseTrajectoryEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_MOUSE_TRAJECTORY_ENABLED, enabled);
    }

    public static boolean isCustomEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CUSTOM_ENABLED, false);
    }

    public static void setCustomEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_CUSTOM_ENABLED, enabled);
    }

    public static boolean isDragEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DRAG_ENABLED, false);
    }

    public static void setDragEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_DRAG_ENABLED, enabled);
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
        SharedPreferences values = prefs(context);
        if (values.getInt(KEY_FORCE_HOLD_TARGET_KEY_CODE, -1) == targetKeyCode
                && values.getInt(KEY_FORCE_HOLD_TARGET_SCAN_CODE, -1) == targetScanCode
                && values.getInt(KEY_FORCE_HOLD_TRIGGER_KEY_CODE, -1) == triggerKeyCode) return;
        values.edit()
                .putInt(KEY_FORCE_HOLD_TARGET_KEY_CODE, targetKeyCode)
                .putInt(KEY_FORCE_HOLD_TARGET_SCAN_CODE, targetScanCode)
                .putInt(KEY_FORCE_HOLD_TRIGGER_KEY_CODE, triggerKeyCode)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static void clearForceHoldBinding(Context context) {
        SharedPreferences values = prefs(context);
        if (!values.contains(KEY_FORCE_HOLD_TARGET_KEY_CODE)
                && !values.contains(KEY_FORCE_HOLD_TARGET_SCAN_CODE)
                && !values.contains(KEY_FORCE_HOLD_TRIGGER_KEY_CODE)) return;
        values.edit()
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
        setDisplayBooleanAndRefresh(context, KEY_DPS_ENABLED, enabled);
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
        setDisplayBooleanAndRefresh(context, KEY_GAMEPAD_LEFT_STICK_ENABLED, enabled);
    }

    public static boolean isGamepadRightStickEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_RIGHT_STICK_ENABLED, false);
    }

    public static void setGamepadRightStickEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_GAMEPAD_RIGHT_STICK_ENABLED, enabled);
    }

    public static boolean isGamepadFaceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_FACE_ENABLED, false);
    }

    public static void setGamepadFaceEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_GAMEPAD_FACE_ENABLED, enabled);
    }

    public static boolean isGamepadLeftShoulderEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_LEFT_SHOULDER_ENABLED, false);
    }

    public static void setGamepadLeftShoulderEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_GAMEPAD_LEFT_SHOULDER_ENABLED, enabled);
    }

    public static boolean isGamepadRightShoulderEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED, false);
    }

    public static void setGamepadRightShoulderEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED, enabled);
    }

    public static boolean isGamepadBackEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_BACK_ENABLED, false);
    }

    public static void setGamepadBackEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_GAMEPAD_BACK_ENABLED, enabled);
    }

    public static boolean isGamepadDpadEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_DPAD_ENABLED, false);
    }

    public static void setGamepadDpadEnabled(Context context, boolean enabled) {
        setDisplayBooleanAndRefresh(context, KEY_GAMEPAD_DPAD_ENABLED, enabled);
    }

    public static boolean isGamepadShouldersEnabled(Context context) {
        return isGamepadLeftShoulderEnabled(context) || isGamepadRightShoulderEnabled(context);
    }

    public static void setGamepadShouldersEnabled(Context context, boolean enabled) {
        SharedPreferences values = prefs(context);
        if (values.getBoolean(KEY_GAMEPAD_LEFT_SHOULDER_ENABLED, false) == enabled
                && values.getBoolean(KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED, false) == enabled) return;
        values.edit()
                .putBoolean(KEY_GAMEPAD_LEFT_SHOULDER_ENABLED, enabled)
                .putBoolean(KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED, enabled)
                .apply();
        if (enabled) AxonInputAccessibilityService.refreshActiveService();
        else AxonInputAccessibilityService.refreshDisplayVisibilityImmediate();
    }

    public static boolean isAnyGamepadDisplayEnabled(Context context) {
        return isGamepadLeftStickEnabled(context) || isGamepadRightStickEnabled(context)
                || isGamepadFaceEnabled(context) || isGamepadDpadEnabled(context)
                || isGamepadLeftShoulderEnabled(context) || isGamepadRightShoulderEnabled(context)
                || isGamepadBackEnabled(context);
    }

    public static boolean isLive2DEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LIVE2D_ENABLED, false);
    }

    public static void setLive2DEnabled(Context context, boolean enabled) {
        if (!PreferenceWriter.putBooleanIfChanged(prefs(context), KEY_LIVE2D_ENABLED, enabled)) return;
        if (enabled) AxonInputAccessibilityService.refreshActiveService();
        else AxonInputAccessibilityService.refreshDisplayVisibilityImmediate();
    }

    public static int getLive2DRenderQuality(Context context) {
        return clampRenderQuality(durablePrefs(context).getInt(
                KEY_LIVE2D_RENDER_QUALITY, RENDER_QUALITY_NORMAL));
    }

    public static void setLive2DRenderQuality(Context context, int quality) {
        int value = clampRenderQuality(quality);
        if (PreferenceWriter.putIntIfChanged(durablePrefs(context), KEY_LIVE2D_RENDER_QUALITY, value)) {
            AxonInputAccessibilityService.refreshLive2DQuality();
        }
    }

    public static int getLive2DSize(Context context) {
        int value = prefs(context).getInt(KEY_LIVE2D_SIZE, 100);
        return Math.max(25, Math.min(400, value));
    }

    public static void setLive2DSize(Context context, int percent) {
        int value = Math.max(25, Math.min(400, percent));
        if (PreferenceWriter.putIntIfChanged(prefs(context), KEY_LIVE2D_SIZE, value)) {
            AxonInputAccessibilityService.refreshLive2DSize();
        }
    }

    public static boolean isLive2DMotionTrackingEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LIVE2D_MOTION_TRACKING, false);
    }

    public static void setLive2DMotionTrackingEnabled(Context context, boolean enabled) {
        PreferenceWriter.putBooleanIfChanged(prefs(context), KEY_LIVE2D_MOTION_TRACKING, enabled);
        if (!enabled) Live2DMotionTracker.clear();
    }

    public static boolean isLive2DMouseCaptureEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LIVE2D_MOUSE_CAPTURE, false);
    }

    public static void setLive2DMouseCaptureEnabled(Context context, boolean enabled) {
        if (PreferenceWriter.putBooleanIfChanged(prefs(context), KEY_LIVE2D_MOUSE_CAPTURE, enabled)) {
            if (!enabled) Live2DMouseTracker.clear();
            AxonInputAccessibilityService.refreshActiveService();
        }
    }


    public static boolean isLive2DHideWatermarkEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LIVE2D_HIDE_WATERMARK, true);
    }

    public static void setLive2DHideWatermarkEnabled(Context context, boolean enabled) {
        if (PreferenceWriter.putBooleanIfChanged(prefs(context), KEY_LIVE2D_HIDE_WATERMARK, enabled)) {
            AxonInputAccessibilityService.refreshLive2DWatermark();
        }
    }

    public static float getLive2DOffsetX(Context context) {
        return clampLive2DOffset(prefs(context).getInt(KEY_LIVE2D_OFFSET_X, 0) / 1000f);
    }

    public static float getLive2DOffsetY(Context context) {
        return clampLive2DOffset(prefs(context).getInt(KEY_LIVE2D_OFFSET_Y, 0) / 1000f);
    }

    /** Persist drag position without triggering a full overlay rebuild on every gesture end. */
    public static void saveLive2DOffset(Context context, float x, float y) {
        int ix = Math.round(clampLive2DOffset(x) * 1000f);
        int iy = Math.round(clampLive2DOffset(y) * 1000f);
        SharedPreferences values = prefs(context);
        if (values.getInt(KEY_LIVE2D_OFFSET_X, Integer.MIN_VALUE) == ix
                && values.getInt(KEY_LIVE2D_OFFSET_Y, Integer.MIN_VALUE) == iy) return;
        values.edit().putInt(KEY_LIVE2D_OFFSET_X, ix).putInt(KEY_LIVE2D_OFFSET_Y, iy).apply();
    }

    private static float clampLive2DOffset(float value) {
        // Keep a usable part of the model inside the display so drag mode can always recover it.
        return Math.max(-0.90f, Math.min(0.90f, value));
    }

    private static int clampRenderQuality(int quality) {
        return quality == RENDER_QUALITY_CLEAR ? RENDER_QUALITY_CLEAR : RENDER_QUALITY_NORMAL;
    }

    public static boolean isAnyDisplayEnabled(Context context) {
        return isEnabled(context) || isInputFullKeyboardEnabled(context) || isMouseEnabled(context)
                || isKeyboardCatEnabled(context) || isLive2DEnabled(context) || isKeyPromptEnabled(context)
                || isCustomEnabled(context) || isSuperCustomEnabled(context) || TouchDisplayStore.isEnabled(context)
                || isMouseTrajectoryEnabled(context)
                || isDpsEnabled(context) || isAnyGamepadDisplayEnabled(context);
    }

    public static boolean isAutoHideBackground(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_HIDE_BACKGROUND, false);
    }

    public static void setAutoHideBackground(Context context, boolean enabled) {
        PreferenceWriter.putBooleanIfChanged(prefs(context), KEY_AUTO_HIDE_BACKGROUND, enabled);
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
        PreferenceWriter.putBooleanIfChanged(durablePrefs(context), KEY_ENTRY_AUTHORIZED, authorized);
    }

    public static String getLastCloudNoticeId(Context context) {
        return durablePrefs(context).getString(KEY_LAST_CLOUD_NOTICE_ID, "");
    }

    public static void setLastCloudNoticeId(Context context, String noticeId) {
        String value = noticeId == null ? "" : noticeId.trim();
        PreferenceWriter.putStringIfChanged(durablePrefs(context), KEY_LAST_CLOUD_NOTICE_ID, value);
    }

    /** 根任务退出时清理运行配置。手动保存配置和密码授权不删除。 */
    public static void endAppSession(Context context) {
        prefs(context).edit().clear().commit();
        Live2DMotionTrackingService.stop(context);
        AxonInputAccessibilityService.refreshActiveService();
    }

    /**
     * Live2D is explicitly allowed to outlive the Activity task. Preserve only the Live2D
     * runtime controls (plus the global drag toggle used to move it) while clearing every other
     * session-only display/input setting. This keeps the old task-exit cleanup semantics for the
     * rest of Axon instead of accidentally leaving all overlays active.
     */
    public static void endAppSessionPreservingLive2D(Context context) {
        SharedPreferences values = prefs(context);
        boolean enabled = values.getBoolean(KEY_LIVE2D_ENABLED, false);
        int size = values.getInt(KEY_LIVE2D_SIZE, 100);
        boolean motion = values.getBoolean(KEY_LIVE2D_MOTION_TRACKING, false);
        boolean mouse = values.getBoolean(KEY_LIVE2D_MOUSE_CAPTURE, false);
        boolean hideWatermark = values.getBoolean(KEY_LIVE2D_HIDE_WATERMARK, true);
        int offsetX = values.getInt(KEY_LIVE2D_OFFSET_X, 0);
        int offsetY = values.getInt(KEY_LIVE2D_OFFSET_Y, 0);
        boolean drag = values.getBoolean(KEY_DRAG_ENABLED, false);

        values.edit().clear()
                .putBoolean(KEY_LIVE2D_ENABLED, enabled)
                .putInt(KEY_LIVE2D_SIZE, size)
                .putBoolean(KEY_LIVE2D_MOTION_TRACKING, motion)
                .putBoolean(KEY_LIVE2D_MOUSE_CAPTURE, mouse)
                .putBoolean(KEY_LIVE2D_HIDE_WATERMARK, hideWatermark)
                .putInt(KEY_LIVE2D_OFFSET_X, offsetX)
                .putInt(KEY_LIVE2D_OFFSET_Y, offsetY)
                .putBoolean(KEY_DRAG_ENABLED, drag)
                .commit();
        AxonInputAccessibilityService.refreshActiveService();
    }



    public static int getUiTheme(Context context) {
        int value = prefs(context).getInt(KEY_UI_THEME, UI_THEME_LIGHT);
        return value == UI_THEME_BLACK ? UI_THEME_BLACK : UI_THEME_LIGHT;
    }

    public static void setUiTheme(Context context, int theme) {
        int resolved = theme == UI_THEME_BLACK ? UI_THEME_BLACK : UI_THEME_LIGHT;
        if (PreferenceWriter.putIntIfChanged(prefs(context), KEY_UI_THEME, resolved)) {
            AxonInputAccessibilityService.refreshTheme();
        }
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
        if (PreferenceWriter.putBooleanIfChanged(prefs(context), KEY_CUSTOM_CAPTURE, false)) {
            AxonInputAccessibilityService.refreshActiveService();
        }
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

    public static int getFullKeyboardSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_FULL_KEYBOARD_SIZE, DEFAULT_SIZE));
    }

    public static void setFullKeyboardSize(Context context, int percent) {
        setIntAndRefresh(context, KEY_FULL_KEYBOARD_SIZE, clampSize(percent));
    }

    public static int getTouchDisplaySize(Context context) {
        return clampSize(prefs(context).getInt(KEY_TOUCH_SIZE, DEFAULT_SIZE));
    }

    public static void setTouchDisplaySize(Context context, int percent) {
        setIntAndRefresh(context, KEY_TOUCH_SIZE, clampSize(percent));
    }

    public static int getKeyboardSpacing(Context context) {
        return clampKeySpacing(prefs(context).getInt(KEY_KEYBOARD_SPACING, DEFAULT_KEYBOARD_SPACING));
    }

    public static void setKeyboardSpacing(Context context, int spacingDp) {
        setIntAndRefresh(context, KEY_KEYBOARD_SPACING, clampKeySpacing(spacingDp));
    }

    public static int getTouchDisplaySpacing(Context context) {
        return clampKeySpacing(prefs(context).getInt(KEY_TOUCH_SPACING, DEFAULT_KEYBOARD_SPACING));
    }

    public static void setTouchDisplaySpacing(Context context, int spacingDp) {
        setIntAndRefresh(context, KEY_TOUCH_SPACING, clampKeySpacing(spacingDp));
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

    /** Debug display option: render the compact keyboard Space label as a graphic dash. */
    public static boolean isKeyboardSpaceDashEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_SPACE_DASH_ENABLED, false);
    }

    public static void setKeyboardSpaceDashEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_KEYBOARD_SPACE_DASH_ENABLED, enabled);
    }

    /** Whether LMB/RMB are rendered as part of the compact keyboard/touch display. */
    public static boolean isKeyboardMouseButtonsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_MOUSE_BUTTONS_ENABLED, false);
    }

    public static void setKeyboardMouseButtonsEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_KEYBOARD_MOUSE_BUTTONS_ENABLED, enabled);
    }

    /** Show live left/right CPS under the compact keyboard's optional LMB/RMB row. */
    public static boolean isKeyboardMouseCpsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_MOUSE_CPS_ENABLED, false);
    }

    public static void setKeyboardMouseCpsEnabled(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_KEYBOARD_MOUSE_CPS_ENABLED, enabled);
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

    public static int getDpsSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_DPS_SIZE, DEFAULT_SIZE));
    }

    public static void setDpsSize(Context context, int percent) {
        setIntAndRefresh(context, KEY_DPS_SIZE, clampSize(percent));
    }

    public static int getDpsTextColor(Context context) {
        return getAnimatedColor(context, KEY_DPS_TEXT_COLOR, UiPalette.overlayTextIdle(context));
    }

    public static int[] getDpsTextColors(Context context) {
        return getColorSequence(context, KEY_DPS_TEXT_COLOR, UiPalette.overlayTextIdle(context));
    }

    public static void setDpsTextColors(Context context, int[] colors) {
        setColorSequence(context, KEY_DPS_TEXT_COLOR, colors, UiPalette.overlayTextIdle(context));
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

    private static String colorSequenceKey(String baseKey) {
        return baseKey + "_colors";
    }

    private static int[] getColorSequence(Context context, String baseKey, int fallback) {
        SharedPreferences values = prefs(context);
        int legacy = values.getInt(baseKey, fallback);
        return ColorSequence.decode(values.getString(colorSequenceKey(baseKey), null), legacy);
    }

    private static int getAnimatedColor(Context context, String baseKey, int fallback) {
        return ColorSequence.current(getColorSequence(context, baseKey, fallback));
    }

    private static void setColorSequence(Context context, String baseKey, int[] colors, int fallback) {
        int[] normalized = ColorSequence.normalize(colors, fallback);
        SharedPreferences values = prefs(context);
        values.edit()
                .putInt(baseKey, normalized[0])
                .putString(colorSequenceKey(baseKey), ColorSequence.encode(normalized, fallback))
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean hasAnimatedColors(Context context) {
        SharedPreferences values = prefs(context);
        for (String key : values.getAll().keySet()) {
            if (key.endsWith("_colors") && ColorSequence.animated(values.getString(key, null))) return true;
        }
        return false;
    }

    public static int getMouseTrajectoryLeftColor(Context context) {
        return getAnimatedColor(context, KEY_MOUSE_TRAJECTORY_LEFT_COLOR, DEFAULT_MOUSE_TRAJECTORY_LEFT_COLOR);
    }

    public static int[] getMouseTrajectoryLeftColors(Context context) {
        return getColorSequence(context, KEY_MOUSE_TRAJECTORY_LEFT_COLOR, DEFAULT_MOUSE_TRAJECTORY_LEFT_COLOR);
    }

    public static void setMouseTrajectoryLeftColor(Context context, int color) {
        setMouseTrajectoryLeftColors(context, new int[]{color});
    }

    public static void setMouseTrajectoryLeftColors(Context context, int[] colors) {
        setColorSequence(context, KEY_MOUSE_TRAJECTORY_LEFT_COLOR, colors, DEFAULT_MOUSE_TRAJECTORY_LEFT_COLOR);
    }

    public static int getMouseTrajectoryRightColor(Context context) {
        return getAnimatedColor(context, KEY_MOUSE_TRAJECTORY_RIGHT_COLOR, DEFAULT_MOUSE_TRAJECTORY_RIGHT_COLOR);
    }

    public static int[] getMouseTrajectoryRightColors(Context context) {
        return getColorSequence(context, KEY_MOUSE_TRAJECTORY_RIGHT_COLOR, DEFAULT_MOUSE_TRAJECTORY_RIGHT_COLOR);
    }

    public static void setMouseTrajectoryRightColor(Context context, int color) {
        setMouseTrajectoryRightColors(context, new int[]{color});
    }

    public static void setMouseTrajectoryRightColors(Context context, int[] colors) {
        setColorSequence(context, KEY_MOUSE_TRAJECTORY_RIGHT_COLOR, colors, DEFAULT_MOUSE_TRAJECTORY_RIGHT_COLOR);
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

    public static int getGamepadStickCenterCornerStrength(Context context, int displayType) {
        if (displayType != GamepadOverlayView.DISPLAY_LEFT_STICK
                && displayType != GamepadOverlayView.DISPLAY_RIGHT_STICK) return 100;
        String key = displayType == GamepadOverlayView.DISPLAY_RIGHT_STICK
                ? KEY_GAMEPAD_RIGHT_STICK_CENTER_CORNER_STRENGTH
                : KEY_GAMEPAD_LEFT_STICK_CENTER_CORNER_STRENGTH;
        return KeyAppearance.clampCornerStrength(prefs(context).getInt(key, 100));
    }

    public static void setGamepadStickCenterCornerStrength(Context context, int displayType, int strength) {
        if (displayType != GamepadOverlayView.DISPLAY_LEFT_STICK
                && displayType != GamepadOverlayView.DISPLAY_RIGHT_STICK) return;
        String key = displayType == GamepadOverlayView.DISPLAY_RIGHT_STICK
                ? KEY_GAMEPAD_RIGHT_STICK_CENTER_CORNER_STRENGTH
                : KEY_GAMEPAD_LEFT_STICK_CENTER_CORNER_STRENGTH;
        setIntAndRefresh(context, key, KeyAppearance.clampCornerStrength(strength));
    }

    public static int getGamepadStickCenterColor(Context context, int displayType) {
        String key = gamepadStickCenterColorKey(displayType);
        int fallback = getKeyPressColor(context, displayType);
        return key == null ? fallback : getAnimatedColor(context, key, fallback);
    }

    public static int[] getGamepadStickCenterColors(Context context, int displayType) {
        String key = gamepadStickCenterColorKey(displayType);
        int fallback = getKeyPressColor(context, displayType);
        return key == null ? new int[]{fallback} : getColorSequence(context, key, fallback);
    }

    public static void setGamepadStickCenterColor(Context context, int displayType, int color) {
        setGamepadStickCenterColors(context, displayType, new int[]{color});
    }

    public static void setGamepadStickCenterColors(Context context, int displayType, int[] colors) {
        String key = gamepadStickCenterColorKey(displayType);
        if (key == null) return;
        setColorSequence(context, key, colors, getKeyPressColor(context, displayType));
    }

    private static String gamepadStickCenterColorKey(int displayType) {
        if (displayType == GamepadOverlayView.DISPLAY_LEFT_STICK) return KEY_GAMEPAD_LEFT_STICK_CENTER_COLOR;
        if (displayType == GamepadOverlayView.DISPLAY_RIGHT_STICK) return KEY_GAMEPAD_RIGHT_STICK_CENTER_COLOR;
        return null;
    }

    public static boolean isGamepadFaceReversed(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_FACE_REVERSED, false);
    }

    public static void setGamepadFaceReversed(Context context, boolean reversed) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_FACE_REVERSED, reversed);
    }

    public static boolean isGamepadFaceSymbolIcons(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_FACE_SYMBOL_ICONS, false);
    }

    public static void setGamepadFaceSymbolIcons(Context context, boolean enabled) {
        setBooleanAndRefresh(context, KEY_GAMEPAD_FACE_SYMBOL_ICONS, enabled);
    }

    public static int getGamepadDisplaySize(Context context, int displayType) {
        String key;
        switch (displayType) {
            case GamepadOverlayView.DISPLAY_LEFT_STICK: key = KEY_GAMEPAD_LEFT_STICK_SIZE; break;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: key = KEY_GAMEPAD_RIGHT_STICK_SIZE; break;
            case GamepadOverlayView.DISPLAY_FACE: key = KEY_GAMEPAD_FACE_SIZE; break;
            case GamepadOverlayView.DISPLAY_DPAD: key = KEY_GAMEPAD_DPAD_SIZE; break;
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
            case GamepadOverlayView.DISPLAY_DPAD: key = KEY_GAMEPAD_DPAD_SIZE; break;
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

    /** Diffusion/state-fill alpha is deliberately independent from the idle key background alpha. */
    public static int getKeyDiffusionOpacity(Context context, int displayType) {
        String key = keyLayerOpacityKey(displayType, "diffusion");
        if (key == null) return DEFAULT_OPACITY;
        return clampOpacity(prefs(context).getInt(key, DEFAULT_OPACITY));
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

    public static void setKeyDiffusionOpacity(Context context, int displayType, int percent) {
        setKeyLayerOpacity(context, displayType, "diffusion", percent);
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
            case DISPLAY_TOUCH_APPEARANCE: prefix = "touch_display"; break;
            case GamepadOverlayView.DISPLAY_LEFT_STICK: prefix = "gamepad_left_stick"; break;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: prefix = "gamepad_right_stick"; break;
            case GamepadOverlayView.DISPLAY_FACE: prefix = "gamepad_face"; break;
            case GamepadOverlayView.DISPLAY_DPAD: prefix = "gamepad_dpad"; break;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: prefix = "gamepad_left_shoulder"; break;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: prefix = "gamepad_right_shoulder"; break;
            case GamepadOverlayView.DISPLAY_BACK: prefix = "gamepad_back"; break;
            default: return null;
        }
        return prefix + "_" + layer + "_opacity";
    }

    public static int getKeyStyle(Context context, int displayType) {
        // Shape selection was removed in v1.9. Preserve old installs by migrating the previous
        // square/circle choice into the continuous corner-strength setting once, then always render
        // through the rounded-rectangle path.
        String legacyStyleKey = keyStyleKey(displayType);
        String cornerKey = keyCornerStrengthKey(displayType);
        if (legacyStyleKey != null && cornerKey != null) {
            SharedPreferences preferences = prefs(context);
            if (!preferences.contains(cornerKey) && preferences.contains(legacyStyleKey)) {
                int legacy = KeyAppearance.clampStyle(
                        preferences.getInt(legacyStyleKey, KeyAppearance.STYLE_ROUNDED));
                if (legacy == KeyAppearance.STYLE_SQUARE) {
                    preferences.edit().putInt(cornerKey, 0).apply();
                } else if (legacy == KeyAppearance.STYLE_CIRCLE) {
                    preferences.edit().putInt(cornerKey, 100).apply();
                }
            }
        }
        return KeyAppearance.STYLE_ROUNDED;
    }

    public static void setKeyStyle(Context context, int displayType, int style) {
        // Compatibility entry point for old configuration imports. Convert shape presets into the
        // new single corner-strength model rather than re-enabling shape selection.
        int resolved = KeyAppearance.clampStyle(style);
        if (resolved == KeyAppearance.STYLE_SQUARE) setKeyCornerStrength(context, displayType, 0);
        else if (resolved == KeyAppearance.STYLE_CIRCLE) setKeyCornerStrength(context, displayType, 100);
    }

    public static int getKeyCornerStrength(Context context, int displayType) {
        String key = keyCornerStrengthKey(displayType);
        if (key == null) return KeyAppearance.DEFAULT_CORNER_STRENGTH;
        SharedPreferences preferences = prefs(context);
        if (!preferences.contains(key)) {
            String legacyStyleKey = keyStyleKey(displayType);
            if (legacyStyleKey != null && preferences.contains(legacyStyleKey)) {
                int legacy = KeyAppearance.clampStyle(
                        preferences.getInt(legacyStyleKey, KeyAppearance.STYLE_ROUNDED));
                if (legacy == KeyAppearance.STYLE_SQUARE) {
                    preferences.edit().putInt(key, 0).apply();
                } else if (legacy == KeyAppearance.STYLE_CIRCLE) {
                    preferences.edit().putInt(key, 100).apply();
                }
            } else if (displayType == GamepadOverlayView.DISPLAY_LEFT_STICK
                    && preferences.contains(KEY_GAMEPAD_LEFT_STICK_SHAPE)) {
                // Preserve the former stick-shape look: circle -> 100%; the old rounded square
                // used about 37% of the available maximum radius.
                int legacy = preferences.getInt(KEY_GAMEPAD_LEFT_STICK_SHAPE, GamepadOverlayView.SHAPE_CIRCLE);
                preferences.edit().putInt(key, legacy == GamepadOverlayView.SHAPE_SQUARE ? 37 : 100).apply();
            } else if (displayType == GamepadOverlayView.DISPLAY_RIGHT_STICK
                    && preferences.contains(KEY_GAMEPAD_RIGHT_STICK_SHAPE)) {
                int legacy = preferences.getInt(KEY_GAMEPAD_RIGHT_STICK_SHAPE, GamepadOverlayView.SHAPE_CIRCLE);
                preferences.edit().putInt(key, legacy == GamepadOverlayView.SHAPE_SQUARE ? 37 : 100).apply();
            }
        }
        return KeyAppearance.clampCornerStrength(
                preferences.getInt(key, KeyAppearance.DEFAULT_CORNER_STRENGTH));
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
        return getAnimatedColor(context, key, fallback);
    }

    public static int[] getKeyBaseColors(Context context, int displayType) {
        String key = keyBaseColorKey(displayType);
        int fallback = defaultKeyBaseColor(context, displayType);
        return key == null ? new int[]{fallback} : getColorSequence(context, key, fallback);
    }

    private static int defaultKeyBaseColor(Context context, int displayType) {
        if (displayType == GamepadOverlayView.DISPLAY_LEFT_STICK
                || displayType == GamepadOverlayView.DISPLAY_RIGHT_STICK
                || displayType == GamepadOverlayView.DISPLAY_FACE
                || displayType == GamepadOverlayView.DISPLAY_DPAD
                || displayType == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || displayType == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER
                || displayType == GamepadOverlayView.DISPLAY_BACK) {
            return UiPalette.overlayShell(context);
        }
        return UiPalette.overlayKeyIdle(context);
    }

    public static void setKeyBaseColor(Context context, int displayType, int color) {
        setKeyBaseColors(context, displayType, new int[]{color});
    }

    public static void setKeyBaseColors(Context context, int displayType, int[] colors) {
        String key = keyBaseColorKey(displayType);
        if (key == null) return;
        setColorSequence(context, key, colors, defaultKeyBaseColor(context, displayType));
    }

    public static int getKeyBorderColor(Context context, int displayType) {
        String key = keyBorderColorKey(displayType);
        int fallback = UiPalette.overlayStroke(context);
        if (key == null) return fallback;
        int stored = getAnimatedColor(context, key, fallback);
        return android.graphics.Color.argb(android.graphics.Color.alpha(fallback),
                android.graphics.Color.red(stored),
                android.graphics.Color.green(stored),
                android.graphics.Color.blue(stored));
    }

    public static int[] getKeyBorderColors(Context context, int displayType) {
        String key = keyBorderColorKey(displayType);
        int fallback = UiPalette.overlayStroke(context);
        return key == null ? new int[]{fallback} : getColorSequence(context, key, fallback);
    }

    public static void setKeyBorderColor(Context context, int displayType, int color) {
        setKeyBorderColors(context, displayType, new int[]{color});
    }

    public static void setKeyBorderColors(Context context, int displayType, int[] colors) {
        String key = keyBorderColorKey(displayType);
        if (key == null) return;
        setColorSequence(context, key, colors, UiPalette.overlayStroke(context));
    }

    public static int getKeyPressColor(Context context, int displayType) {
        String key = keyPressColorKey(displayType);
        int fallback = UiPalette.overlayKeyPressed(context);
        if (key == null) return fallback;
        return getAnimatedColor(context, key, fallback);
    }

    public static int[] getKeyPressColors(Context context, int displayType) {
        String key = keyPressColorKey(displayType);
        int fallback = UiPalette.overlayKeyPressed(context);
        return key == null ? new int[]{fallback} : getColorSequence(context, key, fallback);
    }

    public static void setKeyPressColor(Context context, int displayType, int color) {
        setKeyPressColors(context, displayType, new int[]{color});
    }

    public static void setKeyPressColors(Context context, int displayType, int[] colors) {
        String key = keyPressColorKey(displayType);
        if (key == null) return;
        setColorSequence(context, key, colors, UiPalette.overlayKeyPressed(context));
    }

    public static int getKeyTextColor(Context context, int displayType) {
        String key = keyTextColorKey(displayType);
        int fallback = UiPalette.overlayTextIdle(context);
        return key == null ? fallback : getAnimatedColor(context, key, fallback);
    }

    public static int[] getKeyTextColors(Context context, int displayType) {
        String key = keyTextColorKey(displayType);
        int fallback = UiPalette.overlayTextIdle(context);
        return key == null ? new int[]{fallback} : getColorSequence(context, key, fallback);
    }

    public static void setKeyTextColors(Context context, int displayType, int[] colors) {
        String key = keyTextColorKey(displayType);
        if (key == null) return;
        setColorSequence(context, key, colors, UiPalette.overlayTextIdle(context));
    }

    public static int getKeyboardTextColor(Context context) {
        return getKeyTextColor(context, KeyOverlayView.DISPLAY_KEYBOARD);
    }

    public static int[] getKeyboardTextColors(Context context) {
        return getKeyTextColors(context, KeyOverlayView.DISPLAY_KEYBOARD);
    }

    public static void setKeyboardTextColor(Context context, int color) {
        setKeyTextColors(context, KeyOverlayView.DISPLAY_KEYBOARD, new int[]{color});
    }

    public static void setKeyboardTextColors(Context context, int[] colors) {
        setKeyTextColors(context, KeyOverlayView.DISPLAY_KEYBOARD, colors);
    }

    private static String keyTextColorKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_TEXT_COLOR;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_TEXT_COLOR;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_TEXT_COLOR;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: return KEY_FULL_KEYBOARD_TEXT_COLOR;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_TEXT_COLOR;
            case DISPLAY_TOUCH_APPEARANCE: return KEY_TOUCH_TEXT_COLOR;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_TEXT_COLOR;
            case GamepadOverlayView.DISPLAY_DPAD: return KEY_GAMEPAD_DPAD_TEXT_COLOR;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_TEXT_COLOR;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_TEXT_COLOR;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_TEXT_COLOR;
            default: return null;
        }
    }

    private static String keyStyleKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_KEY_STYLE;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_KEY_STYLE;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_KEY_STYLE;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: return KEY_FULL_KEYBOARD_KEY_STYLE;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_KEY_STYLE;
            case DISPLAY_TOUCH_APPEARANCE: return KEY_TOUCH_KEY_STYLE;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_KEY_STYLE;
            case GamepadOverlayView.DISPLAY_DPAD: return KEY_GAMEPAD_DPAD_KEY_STYLE;
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
            case DISPLAY_TOUCH_APPEARANCE: return KEY_TOUCH_CORNER_STRENGTH;
            case GamepadOverlayView.DISPLAY_LEFT_STICK: return KEY_GAMEPAD_LEFT_STICK_CORNER_STRENGTH;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: return KEY_GAMEPAD_RIGHT_STICK_CORNER_STRENGTH;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_CORNER_STRENGTH;
            case GamepadOverlayView.DISPLAY_DPAD: return KEY_GAMEPAD_DPAD_CORNER_STRENGTH;
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
            case DISPLAY_TOUCH_APPEARANCE: return KEY_TOUCH_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_LEFT_STICK: return KEY_GAMEPAD_LEFT_STICK_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: return KEY_GAMEPAD_RIGHT_STICK_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_DPAD: return KEY_GAMEPAD_DPAD_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_BASE_COLOR;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_BASE_COLOR;
            default: return null;
        }
    }

    private static String keyBorderColorKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_BORDER_COLOR;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_BORDER_COLOR;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_BORDER_COLOR;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: return KEY_FULL_KEYBOARD_BORDER_COLOR;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_BORDER_COLOR;
            case DISPLAY_TOUCH_APPEARANCE: return KEY_TOUCH_BORDER_COLOR;
            case GamepadOverlayView.DISPLAY_LEFT_STICK: return KEY_GAMEPAD_LEFT_STICK_BORDER_COLOR;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: return KEY_GAMEPAD_RIGHT_STICK_BORDER_COLOR;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_BORDER_COLOR;
            case GamepadOverlayView.DISPLAY_DPAD: return KEY_GAMEPAD_DPAD_BORDER_COLOR;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_BORDER_COLOR;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_BORDER_COLOR;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_BORDER_COLOR;
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
            case DISPLAY_TOUCH_APPEARANCE: return KEY_TOUCH_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_LEFT_STICK: return KEY_GAMEPAD_LEFT_STICK_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: return KEY_GAMEPAD_RIGHT_STICK_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_DPAD: return KEY_GAMEPAD_DPAD_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_PRESS_COLOR;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_PRESS_COLOR;
            default: return null;
        }
    }

    private static String opacityKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_OPACITY;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: return KEY_FULL_KEYBOARD_OPACITY;
            case DISPLAY_TOUCH_APPEARANCE: return KEY_TOUCH_OPACITY;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_OPACITY;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_OPACITY;
            case KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT: return KEY_KEYBOARD_CAT_OPACITY;
            case MouseTrajectoryView.DISPLAY_TRAJECTORY: return KEY_MOUSE_TRAJECTORY_OPACITY;
            case GamepadOverlayView.DISPLAY_LEFT_STICK: return KEY_GAMEPAD_LEFT_STICK_OPACITY;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: return KEY_GAMEPAD_RIGHT_STICK_OPACITY;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_OPACITY;
            case GamepadOverlayView.DISPLAY_DPAD: return KEY_GAMEPAD_DPAD_OPACITY;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_OPACITY;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_OPACITY;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_OPACITY;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_OPACITY;
            case DpsOverlayView.DISPLAY_DPS: return KEY_DPS_OPACITY;
            default: return null;
        }
    }

    public static int getMotionMode(Context context, int displayType) {
        String key = motionModeKey(displayType);
        if (key == null) return MOTION_SIZE;
        return clampMotionMode(prefs(context).getInt(key, defaultMotionMode(displayType)));
    }

    private static int defaultMotionMode(int displayType) {
        // Full keyboard and key prompt used centred state-fill before animation selection existed.
        // Keep that visual as their migration default; all other press displays already used size feedback.
        if (displayType == FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD
                || displayType == KeyPromptOverlayView.DISPLAY_KEY_PROMPT) {
            return MOTION_RIPPLE;
        }
        return MOTION_SIZE;
    }

    public static void setMotionMode(Context context, int displayType, int mode) {
        String key = motionModeKey(displayType);
        if (key == null) return;
        setIntAndRefresh(context, key, clampMotionMode(mode));
    }

    public static int clampMotionMode(int mode) {
        if (mode == MOTION_ALPHA || mode == MOTION_NONE || mode == MOTION_RIPPLE) return mode;
        return MOTION_SIZE;
    }

    private static String motionModeKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_MOTION_MODE;
            case FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD: return KEY_FULL_KEYBOARD_MOTION_MODE;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_MOTION_MODE;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_MOTION_MODE;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_MOTION_MODE;
            case DISPLAY_TOUCH_APPEARANCE: return KEY_TOUCH_MOTION_MODE;
            case GamepadOverlayView.DISPLAY_LEFT_STICK: return KEY_GAMEPAD_LEFT_STICK_MOTION_MODE;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: return KEY_GAMEPAD_RIGHT_STICK_MOTION_MODE;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_MOTION_MODE;
            case GamepadOverlayView.DISPLAY_DPAD: return KEY_GAMEPAD_DPAD_MOTION_MODE;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_MOTION_MODE;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_MOTION_MODE;
            case GamepadOverlayView.DISPLAY_BACK: return KEY_GAMEPAD_BACK_MOTION_MODE;
            default: return null;
        }
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
        int nextX = clampFreePositionPercent(xPercent);
        int nextY = clampFreePositionPercent(yPercent);
        SharedPreferences values = prefs(context);
        if (values.getInt(xKey, Integer.MIN_VALUE) == nextX
                && values.getInt(yKey, Integer.MIN_VALUE) == nextY) return;
        values.edit().putInt(xKey, nextX).putInt(yKey, nextY).apply();
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
            case DpsOverlayView.DISPLAY_DPS -> xAxis ? KEY_DPS_POSITION_X : KEY_DPS_POSITION_Y;
            case GamepadOverlayView.DISPLAY_LEFT_STICK -> xAxis ? KEY_GAMEPAD_LEFT_STICK_POSITION_X : KEY_GAMEPAD_LEFT_STICK_POSITION_Y;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK -> xAxis ? KEY_GAMEPAD_RIGHT_STICK_POSITION_X : KEY_GAMEPAD_RIGHT_STICK_POSITION_Y;
            case GamepadOverlayView.DISPLAY_FACE -> xAxis ? KEY_GAMEPAD_FACE_POSITION_X : KEY_GAMEPAD_FACE_POSITION_Y;
            case GamepadOverlayView.DISPLAY_DPAD -> xAxis ? KEY_GAMEPAD_DPAD_POSITION_X : KEY_GAMEPAD_DPAD_POSITION_Y;
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
            case DpsOverlayView.DISPLAY_DPS -> xAxis ? DEFAULT_DPS_X : DEFAULT_DPS_Y;
            case GamepadOverlayView.DISPLAY_LEFT_STICK -> xAxis ? DEFAULT_GAMEPAD_LEFT_STICK_X : DEFAULT_GAMEPAD_LEFT_STICK_Y;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK -> xAxis ? DEFAULT_GAMEPAD_RIGHT_STICK_X : DEFAULT_GAMEPAD_RIGHT_STICK_Y;
            case GamepadOverlayView.DISPLAY_FACE -> xAxis ? DEFAULT_GAMEPAD_FACE_X : DEFAULT_GAMEPAD_FACE_Y;
            case GamepadOverlayView.DISPLAY_DPAD -> xAxis ? DEFAULT_GAMEPAD_DPAD_X : DEFAULT_GAMEPAD_DPAD_Y;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER -> xAxis ? DEFAULT_GAMEPAD_LEFT_SHOULDER_X : DEFAULT_GAMEPAD_LEFT_SHOULDER_Y;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER -> xAxis ? DEFAULT_GAMEPAD_RIGHT_SHOULDER_X : DEFAULT_GAMEPAD_RIGHT_SHOULDER_Y;
            case GamepadOverlayView.DISPLAY_BACK -> xAxis ? DEFAULT_GAMEPAD_BACK_X : DEFAULT_GAMEPAD_BACK_Y;
            default -> 50;
        };
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

    private static void setDisplayBooleanAndRefresh(Context context, String key, boolean enabled) {
        if (!PreferenceWriter.putBooleanIfChanged(prefs(context), key, enabled)) return;
        if (enabled) AxonInputAccessibilityService.refreshActiveService();
        else AxonInputAccessibilityService.refreshDisplayVisibilityImmediate();
    }

    private static void setBooleanAndRefresh(Context context, String key, boolean enabled) {
        if (PreferenceWriter.putBooleanIfChanged(prefs(context), key, enabled)) {
            AxonInputAccessibilityService.refreshActiveService();
        }
    }

    private static void setIntAndRefresh(Context context, String key, int value) {
        if (PreferenceWriter.putIntIfChanged(prefs(context), key, value)) {
            AxonInputAccessibilityService.refreshActiveService();
        }
    }


    static void refreshAfterConfigChange(Context context) {
        AxonInputAccessibilityService.refreshTheme();
    }

    private static SharedPreferences prefs(Context context) {
        return AppPreferences.get(context);
    }

    private static SharedPreferences durablePrefs(Context context) {
        return context.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE);
    }
}
