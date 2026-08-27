package com.axon.input;

import android.content.Context;

/**
 * Immutable snapshot of general settings used by high-frequency physical input callbacks.
 * Rebuild only when configuration changes; callbacks and monitor decisions should not query
 * SharedPreferences repeatedly.
 */
final class InputRuntimeConfig {
    static final InputRuntimeConfig EMPTY = new InputRuntimeConfig(
            false, false, false, false, false, false, false, false, false, false,
            false, false, false, false, SensitivitySettingsStore.MODE_SHIZUKU,
            -1, -1, false, -1, -1, -1, OverlayState.DPS_TARGET_NONE,
            false, false);

    final boolean keyboardEnabled;
    final boolean fullKeyboardEnabled;
    final boolean mouseEnabled;
    final boolean mouseTrajectoryEnabled;
    final boolean customEnabled;
    final boolean superCustomEnabled;
    final boolean keyboardCatEnabled;
    final boolean customCaptureEnabled;
    final boolean keyPromptEnabled;
    final boolean dpsEnabled;
    final boolean keyboardSpaceDpsEnabled;
    final boolean forceHoldEnabled;
    final boolean dragEnabled;
    final boolean sensitivityEnabled;
    final int sensitivityMode;
    final int expressionHotkey;
    final int hideDisplayHotkey;
    final boolean floatingMediaUsesKeyboard;
    final int forceHoldTriggerKey;
    final int forceHoldTargetKey;
    final int forceHoldTargetScanCode;
    final int dpsTargetKey;
    final boolean mouseMonitorRequiredByConfig;
    final boolean gamepadMonitorRequiredByConfig;

    private InputRuntimeConfig(
            boolean keyboardEnabled, boolean fullKeyboardEnabled, boolean mouseEnabled,
            boolean mouseTrajectoryEnabled, boolean customEnabled, boolean superCustomEnabled,
            boolean keyboardCatEnabled, boolean customCaptureEnabled, boolean keyPromptEnabled,
            boolean dpsEnabled, boolean keyboardSpaceDpsEnabled, boolean forceHoldEnabled,
            boolean dragEnabled, boolean sensitivityEnabled, int sensitivityMode,
            int expressionHotkey, int hideDisplayHotkey, boolean floatingMediaUsesKeyboard,
            int forceHoldTriggerKey, int forceHoldTargetKey,
            int forceHoldTargetScanCode, int dpsTargetKey,
            boolean mouseMonitorRequiredByConfig, boolean gamepadMonitorRequiredByConfig) {
        this.keyboardEnabled = keyboardEnabled;
        this.fullKeyboardEnabled = fullKeyboardEnabled;
        this.mouseEnabled = mouseEnabled;
        this.mouseTrajectoryEnabled = mouseTrajectoryEnabled;
        this.customEnabled = customEnabled;
        this.superCustomEnabled = superCustomEnabled;
        this.keyboardCatEnabled = keyboardCatEnabled;
        this.customCaptureEnabled = customCaptureEnabled;
        this.keyPromptEnabled = keyPromptEnabled;
        this.dpsEnabled = dpsEnabled;
        this.keyboardSpaceDpsEnabled = keyboardSpaceDpsEnabled;
        this.forceHoldEnabled = forceHoldEnabled;
        this.dragEnabled = dragEnabled;
        this.sensitivityEnabled = sensitivityEnabled;
        this.sensitivityMode = sensitivityMode;
        this.expressionHotkey = expressionHotkey;
        this.hideDisplayHotkey = hideDisplayHotkey;
        this.floatingMediaUsesKeyboard = floatingMediaUsesKeyboard;
        this.forceHoldTriggerKey = forceHoldTriggerKey;
        this.forceHoldTargetKey = forceHoldTargetKey;
        this.forceHoldTargetScanCode = forceHoldTargetScanCode;
        this.dpsTargetKey = dpsTargetKey;
        this.mouseMonitorRequiredByConfig = mouseMonitorRequiredByConfig;
        this.gamepadMonitorRequiredByConfig = gamepadMonitorRequiredByConfig;
    }

    static InputRuntimeConfig load(Context context) {
        boolean keyboard = OverlayState.isEnabled(context);
        boolean fullKeyboard = OverlayState.isInputFullKeyboardEnabled(context);
        boolean mouse = OverlayState.isMouseEnabled(context);
        boolean trajectory = OverlayState.isMouseTrajectoryEnabled(context);
        boolean custom = OverlayState.isCustomEnabled(context);
        boolean superCustom = OverlayState.isSuperCustomEnabled(context);
        boolean keyboardCat = OverlayState.isKeyboardCatEnabled(context);
        boolean customCapture = OverlayState.isCustomCaptureEnabled(context);
        boolean keyPrompt = OverlayState.isKeyPromptEnabled(context);
        boolean dps = OverlayState.isDpsEnabled(context);
        int dpsTarget = OverlayState.getDpsTargetKeyCode(context);
        boolean forceHold = OverlayState.isForceHoldEnabled(context)
                && OverlayState.hasForceHoldBinding(context);
        SensitivitySettingsStore.RuntimeSnapshot sensitivityState = SensitivitySettingsStore.readRuntimeSnapshot(context);
        boolean sensitivity = sensitivityState.enabled;
        int sensitivityMode = sensitivityState.mode;
        int expressionHotkey = OverlayState.getKeyboardCatExpressionHotkeyKeyCode(context);
        int hideDisplayHotkey = OverlayState.isHideDisplayHotkeyEnabled(context)
                ? OverlayState.getHideDisplayHotkeyInputCode(context) : -1;
        int forceTrigger = forceHold ? OverlayState.getForceHoldTriggerKeyCode(context) : -1;
        int forceTarget = forceHold ? OverlayState.getForceHoldTargetKeyCode(context) : -1;
        int forceScan = forceHold ? OverlayState.getForceHoldTargetScanCode(context) : -1;

        boolean customUsesMouse = false;
        boolean customUsesGamepad = false;
        if (custom || customCapture) {
            int[] bindings = customCapture
                    ? OverlayState.getCustomDraftKeyCodes(context)
                    : OverlayState.getCustomKeyCodes(context);
            for (int binding : bindings) {
                if (InputBinding.isMouse(binding)) customUsesMouse = true;
                if (InputBinding.isGamepad(binding)) customUsesGamepad = true;
                if (customUsesMouse && customUsesGamepad) break;
            }
            // Capture mode must listen before the first mouse/gamepad binding exists.
            if (customCapture) {
                customUsesMouse = true;
                customUsesGamepad = true;
            }
        }

        boolean keyboardCatUsesGamepad = keyboardCat && BongoCatStyleManager.isSelectedGamepad(context);
        boolean keyboardCatUsesMouse = keyboardCat && !keyboardCatUsesGamepad;
        boolean superCustomUsesMouse = superCustom && SuperCustomConfigStore.activeContainsMouse(context);
        boolean superCustomUsesGamepad = superCustom && SuperCustomConfigStore.activeContainsGamepad(context);
        int mediaHotkeyMask = superCustom ? FloatingMediaStore.hotkeyInputMask(context) : 0;
        boolean mediaUsesKeyboard = (mediaHotkeyMask & FloatingMediaStore.HOTKEY_INPUT_KEYBOARD) != 0;
        boolean mediaUsesMouse = (mediaHotkeyMask & FloatingMediaStore.HOTKEY_INPUT_MOUSE) != 0;
        boolean mediaUsesGamepad = (mediaHotkeyMask & FloatingMediaStore.HOTKEY_INPUT_GAMEPAD) != 0;

        boolean dpsNeedsMouse = dps
                && (dpsTarget == OverlayState.DPS_TARGET_NONE || OverlayState.isMouseDpsTarget(dpsTarget));
        boolean dpsNeedsGamepad = dps
                && (dpsTarget == OverlayState.DPS_TARGET_NONE || OverlayState.isGamepadDpsTarget(dpsTarget));
        boolean boundMouse = InputBinding.isMouse(expressionHotkey) || InputBinding.isMouse(hideDisplayHotkey)
                || InputBinding.isMouse(forceTrigger) || customUsesMouse || superCustomUsesMouse || mediaUsesMouse;
        boolean boundGamepad = InputBinding.isGamepad(expressionHotkey) || InputBinding.isGamepad(hideDisplayHotkey)
                || InputBinding.isGamepad(forceTrigger) || customUsesGamepad || superCustomUsesGamepad || mediaUsesGamepad;

        boolean keyboardMouseButtons = keyboard && OverlayState.isKeyboardMouseButtonsEnabled(context);
        boolean mouseMonitor = !sensitivity && (mouse || trajectory || keyPrompt || keyboardCatUsesMouse
                || keyboardMouseButtons || dpsNeedsMouse || boundMouse);
        boolean gamepadMonitor = !sensitivity && (OverlayState.isAnyGamepadDisplayEnabled(context)
                || keyboardCatUsesGamepad || dpsNeedsGamepad || boundGamepad);

        return new InputRuntimeConfig(
                keyboard,
                fullKeyboard,
                mouse,
                trajectory,
                custom,
                superCustom,
                keyboardCat,
                customCapture,
                keyPrompt,
                dps,
                OverlayState.isKeyboardSpaceEnabled(context) && OverlayState.isKeyboardSpaceDpsEnabled(context),
                forceHold,
                OverlayState.isDragEnabled(context),
                sensitivity,
                sensitivityMode,
                expressionHotkey,
                hideDisplayHotkey,
                mediaUsesKeyboard,
                forceTrigger,
                forceTarget,
                forceScan,
                dpsTarget,
                mouseMonitor,
                gamepadMonitor);
    }

    boolean needsKeyboardEvents() {
        return keyboardEnabled || fullKeyboardEnabled || customEnabled || superCustomEnabled
                || keyboardCatEnabled || customCaptureEnabled || keyPromptEnabled || dpsEnabled
                || forceHoldEnabled || (expressionHotkey >= 0 && InputBinding.isKeyboard(expressionHotkey))
                || (hideDisplayHotkey >= 0 && InputBinding.isKeyboard(hideDisplayHotkey))
                || floatingMediaUsesKeyboard;
    }

    boolean needsMouseMonitor(boolean bindingActive) {
        return !sensitivityEnabled && (mouseMonitorRequiredByConfig || bindingActive);
    }

    boolean needsGamepadMonitor(boolean bindingActive) {
        return !sensitivityEnabled && (gamepadMonitorRequiredByConfig || bindingActive);
    }

    InputRuntimeConfig withDpsTarget(int target) {
        if (dpsTargetKey == target) return this;
        boolean dpsNeedsMouse = dpsEnabled
                && (target == OverlayState.DPS_TARGET_NONE || OverlayState.isMouseDpsTarget(target));
        boolean dpsNeedsGamepad = dpsEnabled
                && (target == OverlayState.DPS_TARGET_NONE || OverlayState.isGamepadDpsTarget(target));
        // When the first DPS target is selected, monitor requirements may only become narrower.
        // Keeping an already-required monitor alive is safe; the next config refresh recomputes the
        // exact requirement. This avoids doing persistence reads on the input edge itself.
        return new InputRuntimeConfig(
                keyboardEnabled, fullKeyboardEnabled, mouseEnabled, mouseTrajectoryEnabled,
                customEnabled, superCustomEnabled, keyboardCatEnabled, customCaptureEnabled,
                keyPromptEnabled, dpsEnabled, keyboardSpaceDpsEnabled, forceHoldEnabled,
                dragEnabled, sensitivityEnabled, sensitivityMode, expressionHotkey,
                hideDisplayHotkey, floatingMediaUsesKeyboard, forceHoldTriggerKey, forceHoldTargetKey,
                forceHoldTargetScanCode, target,
                mouseMonitorRequiredByConfig || dpsNeedsMouse,
                gamepadMonitorRequiredByConfig || dpsNeedsGamepad);
    }
}
