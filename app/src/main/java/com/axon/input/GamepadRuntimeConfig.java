package com.axon.input;

import android.content.Context;

/** Cached gamepad transform/DPS settings used for every evdev state packet. */
final class GamepadRuntimeConfig {
    static final GamepadRuntimeConfig EMPTY = new GamepadRuntimeConfig(
            GamepadSettingsStore.COMPAT_AUTO, false, false, false, false,
            false, 0, 0, false, false, false);

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

    private GamepadRuntimeConfig(int compatibilityMode, boolean swapXY, boolean swapAB,
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

    static GamepadRuntimeConfig load(Context context) {
        GamepadSettingsStore.RuntimeSnapshot state = GamepadSettingsStore.readRuntimeSnapshot(context);
        return new GamepadRuntimeConfig(
                state.compatibilityMode,
                state.swapXY,
                state.swapAB,
                state.swapSticks,
                state.swapTriggers,
                state.customSwapEnabled,
                state.customSwapFirst,
                state.customSwapSecond,
                state.faceDpsEnabled,
                state.leftShoulderDpsEnabled,
                state.rightShoulderDpsEnabled);
    }
}
