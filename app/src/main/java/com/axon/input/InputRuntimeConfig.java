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
            false, false, false, false, false, SensitivitySettingsStore.MODE_SHIZUKU,
            -1, -1, false, false, -1, -1, -1, OverlayState.DPS_TARGET_NONE,
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
    final boolean keyboardMouseCpsEnabled;
    final boolean forceHoldEnabled;
    final boolean dragEnabled;
    final boolean sensitivityEnabled;
    final int sensitivityMode;
    final int expressionHotkey;
    final int hideDisplayHotkey;
    final boolean floatingMediaUsesKeyboard;
    final boolean actionHotkeyUsesKeyboard;
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
            boolean dpsEnabled, boolean keyboardSpaceDpsEnabled, boolean keyboardMouseCpsEnabled,
            boolean forceHoldEnabled, boolean dragEnabled, boolean sensitivityEnabled, int sensitivityMode,
            int expressionHotkey, int hideDisplayHotkey, boolean floatingMediaUsesKeyboard,
            boolean actionHotkeyUsesKeyboard, int forceHoldTriggerKey, int forceHoldTargetKey,
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
        this.keyboardMouseCpsEnabled = keyboardMouseCpsEnabled;
        this.forceHoldEnabled = forceHoldEnabled;
        this.dragEnabled = dragEnabled;
        this.sensitivityEnabled = sensitivityEnabled;
        this.sensitivityMode = sensitivityMode;
        this.expressionHotkey = expressionHotkey;
        this.hideDisplayHotkey = hideDisplayHotkey;
        this.floatingMediaUsesKeyboard = floatingMediaUsesKeyboard;
        this.actionHotkeyUsesKeyboard = actionHotkeyUsesKeyboard;
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

        // Visual layout and input capability are separate for hybrid keyboard-cat models. A style
        // may remain a keyboard while still exposing a model-function controller mode.
        boolean keyboardCatUsesGamepad = keyboardCat && BongoCatStyleManager.selectedSupportsGamepad(context);
        boolean keyboardCatUsesMouse = keyboardCat && !BongoCatStyleManager.isSelectedGamepad(context);
        boolean superCustomUsesMouse = superCustom && SuperCustomConfigStore.activeContainsMouse(context);
        boolean superCustomUsesGamepad = superCustom && SuperCustomConfigStore.activeContainsGamepad(context);
        int mediaHotkeyMask = superCustom ? FloatingMediaStore.hotkeyInputMask(context) : 0;
        boolean mediaUsesKeyboard = (mediaHotkeyMask & FloatingMediaStore.HOTKEY_INPUT_KEYBOARD) != 0;
        boolean mediaUsesMouse = (mediaHotkeyMask & FloatingMediaStore.HOTKEY_INPUT_MOUSE) != 0;
        boolean mediaUsesGamepad = (mediaHotkeyMask & FloatingMediaStore.HOTKEY_INPUT_GAMEPAD) != 0;

        // Action bindings are runtime input consumers too. Include them in this immutable
        // snapshot so a mouse/gamepad-only shortcut works even when its normal overlay is off.
        boolean actionUsesKeyboard = false;
        boolean actionUsesMouse = false;
        boolean actionUsesGamepad = false;
        if (CustomMappingStore.isEnabled(context)) {
            for (CustomMappingStore.Rule rule : CustomMappingStore.load(context)) {
                // Custom-mapping gamepad triggers are intentionally limited to Android KeyEvent
                // buttons so they can be consumed. They do not need the passive raw monitor at
                // runtime; keyboard triggers only need FLAG_REQUEST_FILTER_KEY_EVENTS.
                if (InputBinding.isKeyboard(rule.triggerInputCode)) actionUsesKeyboard = true;
            }
        }
        if (ClickMultiplierStore.isEnabled(context)) {
            int toggle = ClickMultiplierStore.getToggleInputCode(context);
            int target = ClickMultiplierStore.getTargetInputCode(context);
            int[] inputs = new int[]{toggle, target};
            for (int input : inputs) {
                if (InputBinding.isMouse(input)) actionUsesMouse = true;
                else if (InputBinding.isGamepad(input)) actionUsesGamepad = true;
                else if (InputBinding.isKeyboard(input)) actionUsesKeyboard = true;
            }
        }
        if (SimultaneousClickStore.isEnabled(context)) {
            for (SimultaneousClickStore.Binding binding : SimultaneousClickStore.load(context)) {
                int source = binding.sourceInputCode;
                if (InputBinding.isMouse(source)) actionUsesMouse = true;
                else if (InputBinding.isGamepad(source)) actionUsesGamepad = true;
                else if (InputBinding.isKeyboard(source)) actionUsesKeyboard = true;
            }
        }
        if (MainActivity.isCustomMappingCaptureActive()) {
            // During the five-second target window any keyboard, mouse or gamepad button may be
            // recorded, even if its normal display is disabled.
            actionUsesKeyboard = true;
            actionUsesMouse = true;
            actionUsesGamepad = true;
        }
        if (MainActivity.isClickMultiplierCaptureActive()) {
            // 两个录入位都允许键盘、鼠标和手柄按键。
            actionUsesKeyboard = true;
            actionUsesMouse = true;
            actionUsesGamepad = true;
        }
        if (MainActivity.isSimultaneousClickCaptureActive()) {
            // Recording is foreground-only and temporary.  Enabling all passive channels here makes
            // middle/side mouse buttons and evdev-only gamepad buttons recordable on OEM builds that
            // never dispatch those edges to Activity callbacks.
            actionUsesKeyboard = true;
            actionUsesMouse = true;
            actionUsesGamepad = true;
        }
        if (OverlayState.isLive2DEnabled(context)) {
            for (Live2DPhysicsHotkeyStore.Binding binding : Live2DPhysicsHotkeyStore.load(
                    context, Live2DPhysicsSettingsStore.TARGET_LIVE2D)) {
                if (!binding.enabled) continue;
                if (InputBinding.isMouse(binding.inputCode)) actionUsesMouse = true;
                else if (InputBinding.isGamepad(binding.inputCode)) actionUsesGamepad = true;
                else if (InputBinding.isKeyboard(binding.inputCode)) actionUsesKeyboard = true;
            }
        }
        int featureShortcutMask = FeatureShortcutStore.inputMask(context);
        if ((featureShortcutMask & FeatureShortcutStore.INPUT_KEYBOARD) != 0) actionUsesKeyboard = true;
        if ((featureShortcutMask & FeatureShortcutStore.INPUT_MOUSE) != 0) actionUsesMouse = true;
        if ((featureShortcutMask & FeatureShortcutStore.INPUT_GAMEPAD) != 0) actionUsesGamepad = true;

        if (keyboardCat) {
            String styleId = OverlayState.getKeyboardCatStyleId(context);
            String physicsTarget = Live2DPhysicsSettingsStore.keyboardCatTarget(styleId);
            for (Live2DPhysicsHotkeyStore.Binding binding : Live2DPhysicsHotkeyStore.load(context, physicsTarget)) {
                if (!binding.enabled) continue;
                if (InputBinding.isMouse(binding.inputCode)) actionUsesMouse = true;
                else if (InputBinding.isGamepad(binding.inputCode)) actionUsesGamepad = true;
                else if (InputBinding.isKeyboard(binding.inputCode)) actionUsesKeyboard = true;
            }
            for (KeyboardCatFunctionBindingStore.Binding binding :
                    KeyboardCatFunctionBindingStore.loadBindings(context, styleId)) {
                if (InputBinding.isMouse(binding.inputCode)) actionUsesMouse = true;
                else if (InputBinding.isGamepad(binding.inputCode)) actionUsesGamepad = true;
                else if (InputBinding.isKeyboard(binding.inputCode)) actionUsesKeyboard = true;
            }
        }

        boolean dpsNeedsMouse = dps
                && (dpsTarget == OverlayState.DPS_TARGET_NONE || OverlayState.isMouseDpsTarget(dpsTarget));
        boolean dpsNeedsGamepad = dps
                && (dpsTarget == OverlayState.DPS_TARGET_NONE || OverlayState.isGamepadDpsTarget(dpsTarget));
        boolean boundMouse = InputBinding.isMouse(expressionHotkey) || InputBinding.isMouse(hideDisplayHotkey)
                || InputBinding.isMouse(forceTrigger) || customUsesMouse || superCustomUsesMouse || mediaUsesMouse
                || actionUsesMouse;
        boolean boundGamepad = InputBinding.isGamepad(expressionHotkey) || InputBinding.isGamepad(hideDisplayHotkey)
                || InputBinding.isGamepad(forceTrigger) || customUsesGamepad || superCustomUsesGamepad || mediaUsesGamepad
                || actionUsesGamepad;

        boolean touchGamepadDisplay = keyboard
                && TouchDisplayStore.isGamepadMode(context)
                && TouchDisplayStore.isGamepadConfigComplete(context);
        boolean keyboardMouseButtons = keyboard && TouchDisplayStore.isKeyboardMouseMode(context)
                && OverlayState.isKeyboardMouseButtonsEnabled(context);
        boolean keyboardMouseCps = keyboardMouseButtons && OverlayState.isKeyboardMouseCpsEnabled(context);
        boolean mouseMonitor = !sensitivity && (mouse || trajectory || keyPrompt || keyboardCatUsesMouse
                || keyboardMouseButtons || dpsNeedsMouse || boundMouse);
        boolean gamepadMonitor = !sensitivity && (OverlayState.isAnyGamepadDisplayEnabled(context)
                || touchGamepadDisplay || keyboardCatUsesGamepad || dpsNeedsGamepad || boundGamepad);

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
                keyboardMouseCps,
                forceHold,
                OverlayState.isDragEnabled(context),
                sensitivity,
                sensitivityMode,
                expressionHotkey,
                hideDisplayHotkey,
                mediaUsesKeyboard,
                actionUsesKeyboard,
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
                || floatingMediaUsesKeyboard || actionHotkeyUsesKeyboard;
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
                keyPromptEnabled, dpsEnabled, keyboardSpaceDpsEnabled, keyboardMouseCpsEnabled,
                forceHoldEnabled, dragEnabled, sensitivityEnabled, sensitivityMode, expressionHotkey,
                hideDisplayHotkey, floatingMediaUsesKeyboard, actionHotkeyUsesKeyboard,
                forceHoldTriggerKey, forceHoldTargetKey,
                forceHoldTargetScanCode, target,
                mouseMonitorRequiredByConfig || dpsNeedsMouse,
                gamepadMonitorRequiredByConfig || dpsNeedsGamepad);
    }
}
