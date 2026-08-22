package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 运行配置入口。普通设置保留到任务退出，长期数据单独保存。 */
public final class OverlayState {
    public static final int MOTION_SIZE = 0;
    public static final int MOTION_ALPHA = 1;
    public static final int MOTION_NONE = 2;
    public static final int UI_THEME_LIGHT = 0;
    public static final int UI_THEME_BLACK = 1;
    public static final int SENSITIVITY_MODE_SHIZUKU = 0;
    public static final int SENSITIVITY_MODE_ROOT = 1;
    public static final int DPS_TARGET_NONE = -1;
    public static final int DPS_TARGET_MOUSE_LEFT = 0x10000;
    public static final int DPS_TARGET_MOUSE_RIGHT = 0x10001;
    public static final int DPS_TARGET_GAMEPAD_BASE = 0x20000;
    private static final String SESSION_PREFS = "axon_input_session";
    private static final String DURABLE_PREFS = "key_display_durable";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MOUSE_ENABLED = "mouse_enabled";
    private static final String KEY_INPUT_FULL_KEYBOARD_ENABLED = "input_full_keyboard_enabled";
    private static final String KEY_KEY_PROMPT_ENABLED = "key_prompt_enabled";
    private static final String KEY_MOUSE_TRAJECTORY_ENABLED = "mouse_trajectory_enabled";
    private static final String KEY_CUSTOM_ENABLED = "custom_enabled";
    private static final String KEY_DRAG_ENABLED = "drag_enabled";
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
    private static final String KEY_KEYBOARD_SPACE_ENABLED = "keyboard_space_enabled";
    private static final String KEY_KEYBOARD_SPACE_DPS_ENABLED = "keyboard_space_dps_enabled";
    private static final String KEY_CUSTOM_SIZE = "custom_size";
    private static final String KEY_MOUSE_SIZE = "mouse_size";
    private static final String KEY_KEY_PROMPT_SIZE = "key_prompt_size";
    private static final String KEY_MOUSE_TRAJECTORY_SIZE = "mouse_trajectory_size";
    private static final String KEY_MOUSE_TRAJECTORY_DOT_SIZE = "mouse_trajectory_dot_size";
    private static final String KEY_KEYBOARD_OPACITY = "keyboard_opacity";
    private static final String KEY_MOUSE_OPACITY = "mouse_opacity";
    private static final String KEY_KEY_PROMPT_OPACITY = "key_prompt_opacity";
    private static final String KEY_MOUSE_TRAJECTORY_OPACITY = "mouse_trajectory_opacity";
    private static final String KEY_CUSTOM_OPACITY = "custom_opacity";
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
    private static final String KEY_KEY_PROMPT_POSITION_X = "key_prompt_position_x";
    private static final String KEY_KEY_PROMPT_POSITION_Y = "key_prompt_position_y";
    private static final String KEY_MOUSE_TRAJECTORY_POSITION_X = "mouse_trajectory_position_x";
    private static final String KEY_MOUSE_TRAJECTORY_POSITION_Y = "mouse_trajectory_position_y";
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
    private static final String KEY_GAMEPAD_LEFT_STICK_SHAPE = "gamepad_left_stick_shape";
    private static final String KEY_GAMEPAD_RIGHT_STICK_SHAPE = "gamepad_right_stick_shape";
    private static final String KEY_GAMEPAD_FACE_REVERSED = "gamepad_face_reversed";
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
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_SIZE = "gamepad_left_shoulder_size";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_SIZE = "gamepad_right_shoulder_size";
    private static final String KEY_GAMEPAD_LEFT_STICK_OPACITY = "gamepad_left_stick_opacity";
    private static final String KEY_GAMEPAD_RIGHT_STICK_OPACITY = "gamepad_right_stick_opacity";
    private static final String KEY_GAMEPAD_FACE_OPACITY = "gamepad_face_opacity";
    private static final String KEY_GAMEPAD_LEFT_SHOULDER_OPACITY = "gamepad_left_shoulder_opacity";
    private static final String KEY_GAMEPAD_RIGHT_SHOULDER_OPACITY = "gamepad_right_shoulder_opacity";
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

    private static final int DEFAULT_KEYBOARD_X = 50;
    private static final int DEFAULT_KEYBOARD_Y = 34;
    private static final int DEFAULT_CUSTOM_X = 50;
    private static final int DEFAULT_CUSTOM_Y = 62;
    private static final int DEFAULT_MOUSE_X = 50;
    private static final int DEFAULT_MOUSE_Y = 82;
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
    private static final int DEFAULT_COLUMNS = 4;
    private static final int MIN_COLUMNS = 1;
    private static final int MAX_COLUMNS = 8;
    private static final int MAX_CUSTOM_KEYS = 64;
    private static final int DEFAULT_SIZE = 100;
    private static final int DEFAULT_OPACITY = 100;
    private static final int DEFAULT_MOUSE_TRAJECTORY_LEFT_COLOR = 0xffff3b30;
    private static final int DEFAULT_MOUSE_TRAJECTORY_RIGHT_COLOR = 0xff34c759;
    private static final int MIN_SIZE = 50;
    private static final int MAX_SIZE = 150;
    private static final int DEFAULT_SENSITIVITY = 100;
    private static final int MIN_SENSITIVITY = 1;
    private static final int MAX_SENSITIVITY = 500;

    private OverlayState() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isInputFullKeyboardEnabled(Context context) {
        return prefs(context).getBoolean(KEY_INPUT_FULL_KEYBOARD_ENABLED, false);
    }

    public static void setInputFullKeyboardEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_INPUT_FULL_KEYBOARD_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isMouseEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_ENABLED, false);
    }

    public static void setMouseEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MOUSE_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isKeyPromptEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEY_PROMPT_ENABLED, false);
    }

    public static void setKeyPromptEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KEY_PROMPT_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isMouseTrajectoryEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_TRAJECTORY_ENABLED, false);
    }

    public static void setMouseTrajectoryEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MOUSE_TRAJECTORY_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isCustomEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CUSTOM_ENABLED, false);
    }

    public static void setCustomEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CUSTOM_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isDragEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DRAG_ENABLED, false);
    }

    public static void setDragEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DRAG_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isDpsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DPS_ENABLED, false);
    }

    public static void setDpsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DPS_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getDpsTargetKeyCode(Context context) {
        return prefs(context).getInt(KEY_DPS_TARGET_KEY_CODE, DPS_TARGET_NONE);
    }

    public static void setDpsTargetKeyCode(Context context, int keyCode) {
        int resolved = keyCode < 0 ? DPS_TARGET_NONE : keyCode;
        prefs(context).edit().putInt(KEY_DPS_TARGET_KEY_CODE, resolved).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int gamepadDpsTarget(int buttonBit) {
        return DPS_TARGET_GAMEPAD_BASE | (buttonBit & 0xffff);
    }

    public static boolean isGamepadDpsTarget(int target) {
        return (target & 0xf0000) == DPS_TARGET_GAMEPAD_BASE && (target & 0xffff) != 0;
    }

    public static int getGamepadDpsTargetBit(int target) {
        return isGamepadDpsTarget(target) ? (target & 0xffff) : 0;
    }

    public static boolean isGamepadLeftStickEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_LEFT_STICK_ENABLED, false);
    }

    public static void setGamepadLeftStickEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GAMEPAD_LEFT_STICK_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isGamepadRightStickEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_RIGHT_STICK_ENABLED, false);
    }

    public static void setGamepadRightStickEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GAMEPAD_RIGHT_STICK_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isGamepadFaceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_FACE_ENABLED, false);
    }

    public static void setGamepadFaceEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GAMEPAD_FACE_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isGamepadLeftShoulderEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_LEFT_SHOULDER_ENABLED, false);
    }

    public static void setGamepadLeftShoulderEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GAMEPAD_LEFT_SHOULDER_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isGamepadRightShoulderEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED, false);
    }

    public static void setGamepadRightShoulderEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GAMEPAD_RIGHT_SHOULDER_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isAnyGamepadDisplayEnabled(Context context) {
        return isGamepadLeftStickEnabled(context) || isGamepadRightStickEnabled(context)
                || isGamepadFaceEnabled(context) || isGamepadLeftShoulderEnabled(context)
                || isGamepadRightShoulderEnabled(context);
    }

    public static boolean isAnyDisplayEnabled(Context context) {
        return isEnabled(context) || isInputFullKeyboardEnabled(context) || isMouseEnabled(context) || isKeyPromptEnabled(context)
                || isCustomEnabled(context) || isMouseTrajectoryEnabled(context)
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

    /** 显式重置入口。普通 Activity 创建和重开不会调用。 */
    public static void beginAppSession(Context context) {
        prefs(context).edit().clear().commit();
        AxonInputAccessibilityService.refreshActiveService();
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
        String value = prefs(context).getString(KEY_SENSITIVITY_STATUS, "未启用");
        return value == null ? "未启用" : value;
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
        prefs(context).edit().putInt(KEY_CUSTOM_COLUMNS, clampColumns(columns)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }


    public static int getKeyboardSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_KEYBOARD_SIZE, DEFAULT_SIZE));
    }

    public static void setKeyboardSize(Context context, int percent) {
        prefs(context).edit().putInt(KEY_KEYBOARD_SIZE, clampSize(percent)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isKeyboardSpaceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_SPACE_ENABLED, true);
    }

    public static void setKeyboardSpaceEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KEYBOARD_SPACE_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isKeyboardSpaceDpsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEYBOARD_SPACE_DPS_ENABLED, false);
    }

    public static void setKeyboardSpaceDpsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KEYBOARD_SPACE_DPS_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getCustomSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_CUSTOM_SIZE, DEFAULT_SIZE));
    }

    public static void setCustomSize(Context context, int percent) {
        prefs(context).edit().putInt(KEY_CUSTOM_SIZE, clampSize(percent)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getMouseSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_MOUSE_SIZE, DEFAULT_SIZE));
    }

    public static void setMouseSize(Context context, int percent) {
        prefs(context).edit().putInt(KEY_MOUSE_SIZE, clampSize(percent)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getKeyPromptSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_KEY_PROMPT_SIZE, DEFAULT_SIZE));
    }

    public static void setKeyPromptSize(Context context, int percent) {
        prefs(context).edit().putInt(KEY_KEY_PROMPT_SIZE, clampSize(percent)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getMouseTrajectorySize(Context context) {
        return clampSize(prefs(context).getInt(KEY_MOUSE_TRAJECTORY_SIZE, DEFAULT_SIZE));
    }

    public static void setMouseTrajectorySize(Context context, int percent) {
        prefs(context).edit().putInt(KEY_MOUSE_TRAJECTORY_SIZE, clampSize(percent)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getMouseTrajectoryDotSize(Context context) {
        return clampSize(prefs(context).getInt(KEY_MOUSE_TRAJECTORY_DOT_SIZE, DEFAULT_SIZE));
    }

    public static void setMouseTrajectoryDotSize(Context context, int percent) {
        prefs(context).edit().putInt(KEY_MOUSE_TRAJECTORY_DOT_SIZE, clampSize(percent)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isMouseTrajectoryLeftColorEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_TRAJECTORY_LEFT_COLOR_ENABLED, false);
    }

    public static void setMouseTrajectoryLeftColorEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MOUSE_TRAJECTORY_LEFT_COLOR_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isMouseTrajectoryRightColorEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MOUSE_TRAJECTORY_RIGHT_COLOR_ENABLED, false);
    }

    public static void setMouseTrajectoryRightColorEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_MOUSE_TRAJECTORY_RIGHT_COLOR_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getMouseTrajectoryLeftColor(Context context) {
        return prefs(context).getInt(KEY_MOUSE_TRAJECTORY_LEFT_COLOR, DEFAULT_MOUSE_TRAJECTORY_LEFT_COLOR);
    }

    public static void setMouseTrajectoryLeftColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_MOUSE_TRAJECTORY_LEFT_COLOR, 0xff000000 | (color & 0x00ffffff)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getMouseTrajectoryRightColor(Context context) {
        return prefs(context).getInt(KEY_MOUSE_TRAJECTORY_RIGHT_COLOR, DEFAULT_MOUSE_TRAJECTORY_RIGHT_COLOR);
    }

    public static void setMouseTrajectoryRightColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_MOUSE_TRAJECTORY_RIGHT_COLOR, 0xff000000 | (color & 0x00ffffff)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getGamepadLeftStickShape(Context context) {
        int value = prefs(context).getInt(KEY_GAMEPAD_LEFT_STICK_SHAPE, GamepadOverlayView.SHAPE_CIRCLE);
        return value == GamepadOverlayView.SHAPE_SQUARE ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE;
    }

    public static void setGamepadLeftStickShape(Context context, int shape) {
        int value = shape == GamepadOverlayView.SHAPE_SQUARE ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE;
        prefs(context).edit().putInt(KEY_GAMEPAD_LEFT_STICK_SHAPE, value).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getGamepadRightStickShape(Context context) {
        int value = prefs(context).getInt(KEY_GAMEPAD_RIGHT_STICK_SHAPE, GamepadOverlayView.SHAPE_CIRCLE);
        return value == GamepadOverlayView.SHAPE_SQUARE ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE;
    }

    public static void setGamepadRightStickShape(Context context, int shape) {
        int value = shape == GamepadOverlayView.SHAPE_SQUARE ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE;
        prefs(context).edit().putInt(KEY_GAMEPAD_RIGHT_STICK_SHAPE, value).apply();
        AxonInputAccessibilityService.refreshActiveService();
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
        prefs(context).edit().putInt(key, clampSize(percent)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isGamepadFaceReversed(Context context) {
        return prefs(context).getBoolean(KEY_GAMEPAD_FACE_REVERSED, false);
    }

    public static void setGamepadFaceReversed(Context context, boolean reversed) {
        prefs(context).edit().putBoolean(KEY_GAMEPAD_FACE_REVERSED, reversed).apply();
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
            default: return;
        }
        prefs(context).edit().putInt(key, clampSize(percent)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getDisplayOpacity(Context context, int displayType) {
        String key = opacityKey(displayType);
        if (key == null) return DEFAULT_OPACITY;
        return clampOpacity(prefs(context).getInt(key, DEFAULT_OPACITY));
    }

    public static void setDisplayOpacity(Context context, int displayType, int percent) {
        String key = opacityKey(displayType);
        if (key == null) return;
        prefs(context).edit().putInt(key, clampOpacity(percent)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    private static String opacityKey(int displayType) {
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD: return KEY_KEYBOARD_OPACITY;
            case KeyOverlayView.DISPLAY_CUSTOM: return KEY_CUSTOM_OPACITY;
            case KeyOverlayView.DISPLAY_MOUSE: return KEY_MOUSE_OPACITY;
            case MouseTrajectoryView.DISPLAY_TRAJECTORY: return KEY_MOUSE_TRAJECTORY_OPACITY;
            case GamepadOverlayView.DISPLAY_LEFT_STICK: return KEY_GAMEPAD_LEFT_STICK_OPACITY;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK: return KEY_GAMEPAD_RIGHT_STICK_OPACITY;
            case GamepadOverlayView.DISPLAY_FACE: return KEY_GAMEPAD_FACE_OPACITY;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER: return KEY_GAMEPAD_LEFT_SHOULDER_OPACITY;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER: return KEY_GAMEPAD_RIGHT_SHOULDER_OPACITY;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT: return KEY_KEY_PROMPT_OPACITY;
            case DpsOverlayView.DISPLAY_DPS: return KEY_DPS_OPACITY;
            default: return null;
        }
    }

    public static int getMotionMode(Context context, int displayType) {
        return clampMotionMode(prefs(context).getInt(motionModeKey(displayType), MOTION_SIZE));
    }

    public static void setMotionMode(Context context, int displayType, int mode) {
        prefs(context).edit().putInt(motionModeKey(displayType), clampMotionMode(mode)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isGlobalHtmlEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GLOBAL_HTML_ENABLED, false);
    }

    public static void setGlobalHtmlEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GLOBAL_HTML_ENABLED, enabled).apply();
        AxonInputAccessibilityService.refreshActiveService();
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
        if (data.length == 0 || data.length > 2 * 1024 * 1024) {
            throw new IOException("HTML size must be 1 byte - 2 MB");
        }
        File file = globalHtmlFile(context);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(data);
            out.flush();
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
                if (total > 2 * 1024 * 1024) return "";
                out.write(buffer, 0, read);
            }
            if (total == 0) return "";
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    public static int clampMotionMode(int mode) {
        if (mode == MOTION_ALPHA || mode == MOTION_NONE) return mode;
        return MOTION_SIZE;
    }

    private static String motionModeKey(int displayType) {
        if (displayType == KeyOverlayView.DISPLAY_MOUSE) return KEY_MOUSE_MOTION_MODE;
        if (displayType == KeyOverlayView.DISPLAY_CUSTOM) return KEY_CUSTOM_MOTION_MODE;
        return KEY_KEYBOARD_MOTION_MODE;
    }

    private static File globalHtmlFile(Context context) {
        return new File(context.getFilesDir(), "global_display.html");
    }

    public static int getPositionX(Context context, int displayType) {
        SharedPreferences p = prefs(context);
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD:
                return clampFreePositionPercent(p.getInt(KEY_KEYBOARD_POSITION_X, DEFAULT_KEYBOARD_X));
            case KeyOverlayView.DISPLAY_CUSTOM:
                return clampFreePositionPercent(p.getInt(KEY_CUSTOM_POSITION_X, DEFAULT_CUSTOM_X));
            case KeyOverlayView.DISPLAY_MOUSE:
                return clampFreePositionPercent(p.getInt(KEY_MOUSE_POSITION_X, DEFAULT_MOUSE_X));
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT:
                return clampFreePositionPercent(p.getInt(KEY_KEY_PROMPT_POSITION_X, DEFAULT_KEY_PROMPT_X));
            case MouseTrajectoryView.DISPLAY_TRAJECTORY:
                return clampFreePositionPercent(p.getInt(KEY_MOUSE_TRAJECTORY_POSITION_X, DEFAULT_MOUSE_TRAJECTORY_X));
            case DpsOverlayView.DISPLAY_DPS:
                return clampFreePositionPercent(p.getInt(KEY_DPS_POSITION_X, DEFAULT_DPS_X));
            case GamepadOverlayView.DISPLAY_LEFT_STICK:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_LEFT_STICK_POSITION_X, DEFAULT_GAMEPAD_LEFT_STICK_X));
            case GamepadOverlayView.DISPLAY_RIGHT_STICK:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_RIGHT_STICK_POSITION_X, DEFAULT_GAMEPAD_RIGHT_STICK_X));
            case GamepadOverlayView.DISPLAY_FACE:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_FACE_POSITION_X, DEFAULT_GAMEPAD_FACE_X));
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_LEFT_SHOULDER_POSITION_X, DEFAULT_GAMEPAD_LEFT_SHOULDER_X));
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_RIGHT_SHOULDER_POSITION_X, DEFAULT_GAMEPAD_RIGHT_SHOULDER_X));
            default:
                return 50;
        }
    }

    public static int getPositionY(Context context, int displayType) {
        SharedPreferences p = prefs(context);
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD:
                return clampFreePositionPercent(p.getInt(KEY_KEYBOARD_POSITION_Y, DEFAULT_KEYBOARD_Y));
            case KeyOverlayView.DISPLAY_CUSTOM:
                return clampFreePositionPercent(p.getInt(KEY_CUSTOM_POSITION_Y, DEFAULT_CUSTOM_Y));
            case KeyOverlayView.DISPLAY_MOUSE:
                return clampFreePositionPercent(p.getInt(KEY_MOUSE_POSITION_Y, DEFAULT_MOUSE_Y));
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT:
                return clampFreePositionPercent(p.getInt(KEY_KEY_PROMPT_POSITION_Y, DEFAULT_KEY_PROMPT_Y));
            case MouseTrajectoryView.DISPLAY_TRAJECTORY:
                return clampFreePositionPercent(p.getInt(KEY_MOUSE_TRAJECTORY_POSITION_Y, DEFAULT_MOUSE_TRAJECTORY_Y));
            case DpsOverlayView.DISPLAY_DPS:
                return clampFreePositionPercent(p.getInt(KEY_DPS_POSITION_Y, DEFAULT_DPS_Y));
            case GamepadOverlayView.DISPLAY_LEFT_STICK:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_LEFT_STICK_POSITION_Y, DEFAULT_GAMEPAD_LEFT_STICK_Y));
            case GamepadOverlayView.DISPLAY_RIGHT_STICK:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_RIGHT_STICK_POSITION_Y, DEFAULT_GAMEPAD_RIGHT_STICK_Y));
            case GamepadOverlayView.DISPLAY_FACE:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_FACE_POSITION_Y, DEFAULT_GAMEPAD_FACE_Y));
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_LEFT_SHOULDER_POSITION_Y, DEFAULT_GAMEPAD_LEFT_SHOULDER_Y));
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER:
                return clampFreePositionPercent(p.getInt(KEY_GAMEPAD_RIGHT_SHOULDER_POSITION_Y, DEFAULT_GAMEPAD_RIGHT_SHOULDER_Y));
            default:
                return 50;
        }
    }

    /** 保存独立悬浮窗口的拖动位置。 */
    public static void savePosition(Context context, int displayType, int xPercent, int yPercent) {
        String xKey;
        String yKey;
        switch (displayType) {
            case KeyOverlayView.DISPLAY_KEYBOARD:
                xKey = KEY_KEYBOARD_POSITION_X;
                yKey = KEY_KEYBOARD_POSITION_Y;
                break;
            case KeyOverlayView.DISPLAY_CUSTOM:
                xKey = KEY_CUSTOM_POSITION_X;
                yKey = KEY_CUSTOM_POSITION_Y;
                break;
            case KeyOverlayView.DISPLAY_MOUSE:
                xKey = KEY_MOUSE_POSITION_X;
                yKey = KEY_MOUSE_POSITION_Y;
                break;
            case KeyPromptOverlayView.DISPLAY_KEY_PROMPT:
                xKey = KEY_KEY_PROMPT_POSITION_X;
                yKey = KEY_KEY_PROMPT_POSITION_Y;
                break;
            case MouseTrajectoryView.DISPLAY_TRAJECTORY:
                xKey = KEY_MOUSE_TRAJECTORY_POSITION_X;
                yKey = KEY_MOUSE_TRAJECTORY_POSITION_Y;
                break;
            case DpsOverlayView.DISPLAY_DPS:
                xKey = KEY_DPS_POSITION_X;
                yKey = KEY_DPS_POSITION_Y;
                break;
            case GamepadOverlayView.DISPLAY_LEFT_STICK:
                xKey = KEY_GAMEPAD_LEFT_STICK_POSITION_X;
                yKey = KEY_GAMEPAD_LEFT_STICK_POSITION_Y;
                break;
            case GamepadOverlayView.DISPLAY_RIGHT_STICK:
                xKey = KEY_GAMEPAD_RIGHT_STICK_POSITION_X;
                yKey = KEY_GAMEPAD_RIGHT_STICK_POSITION_Y;
                break;
            case GamepadOverlayView.DISPLAY_FACE:
                xKey = KEY_GAMEPAD_FACE_POSITION_X;
                yKey = KEY_GAMEPAD_FACE_POSITION_Y;
                break;
            case GamepadOverlayView.DISPLAY_LEFT_SHOULDER:
                xKey = KEY_GAMEPAD_LEFT_SHOULDER_POSITION_X;
                yKey = KEY_GAMEPAD_LEFT_SHOULDER_POSITION_Y;
                break;
            case GamepadOverlayView.DISPLAY_RIGHT_SHOULDER:
                xKey = KEY_GAMEPAD_RIGHT_SHOULDER_POSITION_X;
                yKey = KEY_GAMEPAD_RIGHT_SHOULDER_POSITION_Y;
                break;
            default:
                return;
        }
        prefs(context).edit()
                .putInt(xKey, clampFreePositionPercent(xPercent))
                .putInt(yKey, clampFreePositionPercent(yPercent))
                .apply();
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
