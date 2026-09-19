from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVICE = (ROOT / 'app/src/main/java/com/axon/input/AxonInputAccessibilityService.java').read_text(encoding='utf-8')

# 1) Keyboard Cat must consume the same reconciled semantic buttons as the regular HUD.
assert 'int keyboardCatButtons = effectiveButtons;' in SERVICE
cat_segment = SERVICE[SERVICE.index('int keyboardCatButtons = effectiveButtons;'):SERVICE.index('// 超级自定义编辑器')]
assert 'nativeGamepadConnected ?' not in cat_segment
assert 'applyGamepadButtonTransforms(buttons | vader5UsbBackButtons)' not in cat_segment

# 2) Adding an unrelated HID device must not restart native gamepad monitoring and emit a neutral frame.
added = SERVICE[SERVICE.index('public void onInputDeviceAdded'):SERVICE.index('public void onInputDeviceRemoved')]
assert 'stopGamepadMonitor();' not in added
assert 'startGamepadMonitor();' not in added

# 3) Android KeyEvent gamepad state is tracked per physical device, not as one destructive global bitmask.
for marker in [
    'androidGamepadButtonsByDevice',
    'androidGamepadKnownByDevice',
    'updateAndroidGamepadDevice(event.getDeviceId(), logicalBit, knownGroup, pressed)',
    'rebuildAndroidGamepadAggregate()',
    'releaseAndroidGamepadDevice(deviceId)',
]:
    assert marker in SERVICE, f'missing per-device gamepad marker: {marker}'

removed = SERVICE[SERVICE.index('public void onInputDeviceRemoved'):SERVICE.index('public void onInputDeviceChanged')]
changed = SERVICE[SERVICE.index('public void onInputDeviceChanged'):SERVICE.index('private boolean rememberAxonVirtualDevice')]
for segment in (removed, changed):
    assert 'resetPressedState();' not in segment
    assert 'releaseAndroidGamepadDevice(deviceId)' in segment
    assert 'applyGamepadState(' in segment

# Alias suppression must mutate the per-device mirrors too, otherwise the next aggregate rebuild would
# resurrect the alias that BuildFix42 intentionally removed.
for marker in [
    'clearAndroidGamepadMirrorBits(xGroup);',
    'clearAndroidGamepadMirrorBits(bGroup);',
    'clearAndroidGamepadMirrorBits(GamepadOverlayView.BTN_L2);',
    'clearAndroidGamepadMirrorBits(GamepadOverlayView.BTN_R2);',
]:
    assert marker in SERVICE

print('BuildFix46 gamepad/Keyboard Cat regression: OK')
