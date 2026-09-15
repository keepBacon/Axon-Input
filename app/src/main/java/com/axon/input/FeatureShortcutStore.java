package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 顶层功能快捷键的统一持久化与切换入口。
 * 只保存“物理输入 -> 功能 ID”，运行时索引由 AccessibilityService 在状态刷新时一次构建，
 * 避免每个输入事件都访问 SharedPreferences。
 */
final class FeatureShortcutStore {
    static final String REGULAR_DISPLAY = "regular_display";
    static final String FULL_KEYBOARD = "full_keyboard";
    static final String MOUSE_DISPLAY = "mouse_display";
    static final String KEYBOARD_CAT = "keyboard_cat";
    static final String KEY_PROMPT = "key_prompt";
    static final String MOUSE_TRAJECTORY = "mouse_trajectory";
    static final String CUSTOM_DISPLAY = "custom_display";
    static final String SUPER_CUSTOM = "super_custom";
    static final String GAMEPAD_LEFT_STICK = "gamepad_left_stick";
    static final String GAMEPAD_RIGHT_STICK = "gamepad_right_stick";
    static final String GAMEPAD_FACE = "gamepad_face";
    static final String GAMEPAD_DPAD = "gamepad_dpad";
    static final String GAMEPAD_LEFT_SHOULDER = "gamepad_left_shoulder";
    static final String GAMEPAD_RIGHT_SHOULDER = "gamepad_right_shoulder";
    static final String GAMEPAD_BACK = "gamepad_back";
    static final String SENSITIVITY = "sensitivity";
    static final String CUSTOM_MAPPING = "custom_mapping";
    static final String CLICK_MULTIPLIER = "click_multiplier";
    static final String SIMULTANEOUS_CLICK = "simultaneous_click";
    static final String FORCE_HOLD = "force_hold";
    static final String HIDE_DISPLAY_HOTKEY = "hide_display_hotkey";
    static final String DPS = "dps";
    static final String FONT = "font";
    static final String GLOBAL_HTML = "global_html";
    static final String LIVE2D = "live2d";
    static final String DRAG = "drag";
    static final String AUTO_HIDE = "auto_hide";

    static final int INPUT_KEYBOARD = 1;
    static final int INPUT_MOUSE = 1 << 1;
    static final int INPUT_GAMEPAD = 1 << 2;

    private static final String KEY_PREFIX = "feature_shortcut_v1_";
    private static final String[] IDS = {
            REGULAR_DISPLAY, FULL_KEYBOARD, MOUSE_DISPLAY, KEYBOARD_CAT, KEY_PROMPT,
            MOUSE_TRAJECTORY, CUSTOM_DISPLAY, SUPER_CUSTOM,
            GAMEPAD_LEFT_STICK, GAMEPAD_RIGHT_STICK, GAMEPAD_FACE, GAMEPAD_DPAD,
            GAMEPAD_LEFT_SHOULDER, GAMEPAD_RIGHT_SHOULDER, GAMEPAD_BACK,
            SENSITIVITY, CUSTOM_MAPPING, CLICK_MULTIPLIER, SIMULTANEOUS_CLICK,
            FORCE_HOLD, HIDE_DISPLAY_HOTKEY, DPS, FONT, GLOBAL_HTML, LIVE2D, DRAG, AUTO_HIDE
    };

    static final class ToggleResult {
        final String featureId;
        final boolean changed;
        final boolean enabled;

        ToggleResult(String featureId, boolean changed, boolean enabled) {
            this.featureId = featureId;
            this.changed = changed;
            this.enabled = enabled;
        }
    }

    private FeatureShortcutStore() {}

    static int getBinding(Context context, String featureId) {
        if (!isKnownFeatureId(featureId) || GitHubFeatureControl.isShortcutBlocked(featureId)) return -1;
        int value = AppPreferences.get(context).getInt(key(featureId), -1);
        return InputBinding.isValid(value) ? value : -1;
    }

    static boolean setBinding(Context context, String featureId, int inputCode) {
        if (!isKnownFeatureId(featureId) || GitHubFeatureControl.isShortcutBlocked(featureId)
                || !InputBinding.isValid(inputCode)) return false;
        if (conflicts(context, featureId, inputCode)) return false;
        AppPreferences.get(context).edit().putInt(key(featureId), inputCode).apply();
        AxonInputAccessibilityService.refreshActiveService();
        return true;
    }

    static void clearBinding(Context context, String featureId) {
        if (!isKnownFeatureId(featureId)) return;
        AppPreferences.get(context).edit().remove(key(featureId)).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    static void clearBindingSilently(Context context, String featureId) {
        if (!isKnownFeatureId(featureId)) return;
        AppPreferences.get(context).edit().remove(key(featureId)).apply();
    }

    static boolean conflicts(Context context, String excludeFeatureId, int inputCode) {
        if (!InputBinding.isValid(inputCode)) return true;
        SharedPreferences prefs = AppPreferences.get(context);
        for (String id : IDS) {
            if (id.equals(excludeFeatureId)) continue;
            int value = prefs.getInt(key(id), -1);
            if (InputBinding.isValid(value) && value == inputCode) return true;
        }
        return false;
    }

    static boolean hasAnyBinding(Context context) {
        return inputMask(context) != 0;
    }

    static int inputMask(Context context) {
        SharedPreferences prefs = AppPreferences.get(context);
        int mask = 0;
        for (String id : IDS) {
            if (GitHubFeatureControl.isShortcutBlocked(id)) continue;
            int inputCode = prefs.getInt(key(id), -1);
            if (!InputBinding.isValid(inputCode)) continue;
            if (InputBinding.isMouse(inputCode)) mask |= INPUT_MOUSE;
            else if (InputBinding.isGamepad(inputCode)) mask |= INPUT_GAMEPAD;
            else if (InputBinding.isKeyboard(inputCode)) mask |= INPUT_KEYBOARD;
        }
        return mask;
    }

    /** 输入高频路径使用的不可变索引；重复绑定时稳定保留 IDS 中更早的一项。 */
    static Map<Integer, String> loadByInput(Context context) {
        SharedPreferences prefs = AppPreferences.get(context);
        HashMap<Integer, String> result = new HashMap<>();
        for (String id : IDS) {
            if (GitHubFeatureControl.isShortcutBlocked(id)) continue;
            int inputCode = prefs.getInt(key(id), -1);
            if (!InputBinding.isValid(inputCode) || result.containsKey(inputCode)) continue;
            result.put(inputCode, id);
        }
        return result.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(result);
    }

    static boolean isFeatureEnabled(Context context, String featureId) {
        return switch (featureId) {
            case REGULAR_DISPLAY -> OverlayState.isEnabled(context);
            case FULL_KEYBOARD -> OverlayState.isInputFullKeyboardEnabled(context);
            case MOUSE_DISPLAY -> OverlayState.isMouseEnabled(context);
            case KEYBOARD_CAT -> OverlayState.isKeyboardCatEnabled(context);
            case KEY_PROMPT -> OverlayState.isKeyPromptEnabled(context);
            case MOUSE_TRAJECTORY -> OverlayState.isMouseTrajectoryEnabled(context);
            case CUSTOM_DISPLAY -> OverlayState.isCustomEnabled(context);
            case SUPER_CUSTOM -> OverlayState.isSuperCustomEnabled(context);
            case GAMEPAD_LEFT_STICK -> OverlayState.isGamepadLeftStickEnabled(context);
            case GAMEPAD_RIGHT_STICK -> OverlayState.isGamepadRightStickEnabled(context);
            case GAMEPAD_FACE -> OverlayState.isGamepadFaceEnabled(context);
            case GAMEPAD_DPAD -> OverlayState.isGamepadDpadEnabled(context);
            case GAMEPAD_LEFT_SHOULDER -> OverlayState.isGamepadLeftShoulderEnabled(context);
            case GAMEPAD_RIGHT_SHOULDER -> OverlayState.isGamepadRightShoulderEnabled(context);
            case GAMEPAD_BACK -> OverlayState.isGamepadBackEnabled(context);
            case SENSITIVITY -> SensitivitySettingsStore.isEnabled(context);
            case CUSTOM_MAPPING -> CustomMappingStore.isEnabled(context);
            case CLICK_MULTIPLIER -> ClickMultiplierStore.isEnabled(context);
            case SIMULTANEOUS_CLICK -> SimultaneousClickStore.isEnabled(context);
            case FORCE_HOLD -> OverlayState.isForceHoldEnabled(context);
            case HIDE_DISPLAY_HOTKEY -> OverlayState.isHideDisplayHotkeyEnabled(context);
            case DPS -> OverlayState.isDpsEnabled(context);
            case FONT -> FontManager.isEnabled(context);
            case GLOBAL_HTML -> GlobalHtmlStore.isEnabled(context);
            case LIVE2D -> OverlayState.isLive2DEnabled(context);
            case DRAG -> OverlayState.isDragEnabled(context);
            case AUTO_HIDE -> OverlayState.isAutoHideBackground(context);
            default -> false;
        };
    }

    static ToggleResult toggle(Context context, String featureId) {
        if (!isKnownFeatureId(featureId) || GitHubFeatureControl.isFeatureBlocked(featureId)
                || GitHubFeatureControl.isFeatureHidden(featureId)) {
            return new ToggleResult(featureId, false, isFeatureEnabled(context, featureId));
        }
        boolean current = isFeatureEnabled(context, featureId);
        boolean target = !current;
        if (target && !canEnable(context, featureId)) {
            return new ToggleResult(featureId, false, current);
        }
        setFeatureEnabled(context, featureId, target);
        boolean actual = isFeatureEnabled(context, featureId);
        return new ToggleResult(featureId, actual != current, actual);
    }

    static boolean canEnable(Context context, String featureId) {
        return switch (featureId) {
            case CUSTOM_MAPPING -> CustomMappingStore.hasRules(context);
            case CLICK_MULTIPLIER -> ClickMultiplierStore.hasBinding(context);
            case SIMULTANEOUS_CLICK -> SimultaneousClickStore.hasBinding(context);
            case FORCE_HOLD -> OverlayState.hasForceHoldBinding(context);
            case FONT -> FontManager.hasImportedFont(context);
            case GLOBAL_HTML -> GlobalHtmlStore.exists(context);
            case LIVE2D -> Live2DModelStore.exists(context);
            default -> true;
        };
    }

    private static void setFeatureEnabled(Context context, String featureId, boolean enabled) {
        switch (featureId) {
            case REGULAR_DISPLAY -> OverlayState.setEnabled(context, enabled);
            case FULL_KEYBOARD -> OverlayState.setInputFullKeyboardEnabled(context, enabled);
            case MOUSE_DISPLAY -> OverlayState.setMouseEnabled(context, enabled);
            case KEYBOARD_CAT -> OverlayState.setKeyboardCatEnabled(context, enabled);
            case KEY_PROMPT -> OverlayState.setKeyPromptEnabled(context, enabled);
            case MOUSE_TRAJECTORY -> OverlayState.setMouseTrajectoryEnabled(context, enabled);
            case CUSTOM_DISPLAY -> OverlayState.setCustomEnabled(context, enabled);
            case SUPER_CUSTOM -> OverlayState.setSuperCustomEnabled(context, enabled);
            case GAMEPAD_LEFT_STICK -> OverlayState.setGamepadLeftStickEnabled(context, enabled);
            case GAMEPAD_RIGHT_STICK -> OverlayState.setGamepadRightStickEnabled(context, enabled);
            case GAMEPAD_FACE -> OverlayState.setGamepadFaceEnabled(context, enabled);
            case GAMEPAD_DPAD -> OverlayState.setGamepadDpadEnabled(context, enabled);
            case GAMEPAD_LEFT_SHOULDER -> OverlayState.setGamepadLeftShoulderEnabled(context, enabled);
            case GAMEPAD_RIGHT_SHOULDER -> OverlayState.setGamepadRightShoulderEnabled(context, enabled);
            case GAMEPAD_BACK -> OverlayState.setGamepadBackEnabled(context, enabled);
            case SENSITIVITY -> SensitivitySettingsStore.setEnabled(context, enabled);
            case CUSTOM_MAPPING -> CustomMappingStore.setEnabled(context, enabled);
            case CLICK_MULTIPLIER -> ClickMultiplierStore.setEnabled(context, enabled);
            case SIMULTANEOUS_CLICK -> SimultaneousClickStore.setEnabled(context, enabled);
            case FORCE_HOLD -> OverlayState.setForceHoldEnabled(context, enabled);
            case HIDE_DISPLAY_HOTKEY -> OverlayState.setHideDisplayHotkeyEnabled(context, enabled);
            case DPS -> OverlayState.setDpsEnabled(context, enabled);
            case FONT -> {
                FontManager.setEnabled(context, enabled);
                AxonInputAccessibilityService.refreshTheme();
            }
            case GLOBAL_HTML -> GlobalHtmlStore.setEnabled(context, enabled);
            case LIVE2D -> OverlayState.setLive2DEnabled(context, enabled);
            case DRAG -> OverlayState.setDragEnabled(context, enabled);
            case AUTO_HIDE -> OverlayState.setAutoHideBackground(context, enabled);
            default -> { }
        }
        AxonInputAccessibilityService.refreshActiveService();
    }

    static void forceSetFeatureEnabled(Context context, String featureId, boolean enabled) {
        if (!isKnownFeatureId(featureId)) return;
        if (enabled && !canEnable(context, featureId)) return;
        setFeatureEnabled(context, featureId, enabled);
    }

    static String[] allFeatureIds() {
        return IDS.clone();
    }

    static String featureIdForLabelRes(int labelRes) {
        if (labelRes == R.string.switch_label) return REGULAR_DISPLAY;
        if (labelRes == R.string.input_full_keyboard_switch_label) return FULL_KEYBOARD;
        if (labelRes == R.string.mouse_switch_label) return MOUSE_DISPLAY;
        if (labelRes == R.string.keyboard_cat_switch_label) return KEYBOARD_CAT;
        if (labelRes == R.string.key_prompt_switch_label) return KEY_PROMPT;
        if (labelRes == R.string.mouse_trajectory_switch_label) return MOUSE_TRAJECTORY;
        if (labelRes == R.string.custom_switch_label) return CUSTOM_DISPLAY;
        if (labelRes == R.string.super_custom_display_switch) return SUPER_CUSTOM;
        if (labelRes == R.string.gamepad_left_stick_switch) return GAMEPAD_LEFT_STICK;
        if (labelRes == R.string.gamepad_right_stick_switch) return GAMEPAD_RIGHT_STICK;
        if (labelRes == R.string.gamepad_face_switch) return GAMEPAD_FACE;
        if (labelRes == R.string.gamepad_dpad_switch) return GAMEPAD_DPAD;
        if (labelRes == R.string.gamepad_left_shoulder_switch) return GAMEPAD_LEFT_SHOULDER;
        if (labelRes == R.string.gamepad_right_shoulder_switch) return GAMEPAD_RIGHT_SHOULDER;
        if (labelRes == R.string.gamepad_back_switch) return GAMEPAD_BACK;
        if (labelRes == R.string.sensitivity_switch_label) return SENSITIVITY;
        if (labelRes == R.string.custom_mapping_switch_label) return CUSTOM_MAPPING;
        if (labelRes == R.string.click_multiplier_switch_label) return CLICK_MULTIPLIER;
        if (labelRes == R.string.simultaneous_click_switch_label) return SIMULTANEOUS_CLICK;
        if (labelRes == R.string.force_hold_switch_label) return FORCE_HOLD;
        if (labelRes == R.string.hide_display_hotkey_switch_label) return HIDE_DISPLAY_HOTKEY;
        if (labelRes == R.string.dps_switch_label) return DPS;
        if (labelRes == R.string.font_switch_label) return FONT;
        if (labelRes == R.string.global_html_switch_label) return GLOBAL_HTML;
        if (labelRes == R.string.live2d_switch_label) return LIVE2D;
        if (labelRes == R.string.drag_switch_label) return DRAG;
        if (labelRes == R.string.auto_hide_label) return AUTO_HIDE;
        return null;
    }

    static boolean isKnownFeatureId(String featureId) {
        if (featureId == null || featureId.isEmpty()) return false;
        for (String id : IDS) if (id.equals(featureId)) return true;
        return false;
    }

    private static String key(String featureId) {
        return KEY_PREFIX + featureId;
    }
}
