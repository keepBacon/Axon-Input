package com.axon.input;

import android.view.KeyEvent;

/** 将 Android KeyCode 转为短标签。 */
public final class KeyLabel {
    private KeyLabel() {}

    public static String fromKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('A' + keyCode - KeyEvent.KEYCODE_A));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return "Space";
            case KeyEvent.KEYCODE_ENTER: return "Enter";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_ESCAPE: return "Esc";
            case KeyEvent.KEYCODE_DEL: return "Back";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "Del";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "LShift";
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "RShift";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "LCtrl";
            case KeyEvent.KEYCODE_CTRL_RIGHT: return "RCtrl";
            case KeyEvent.KEYCODE_ALT_LEFT: return "LAlt";
            case KeyEvent.KEYCODE_ALT_RIGHT: return "RAlt";
            case KeyEvent.KEYCODE_CAPS_LOCK: return "Caps";
            case KeyEvent.KEYCODE_DPAD_UP: return "Up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "Down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "Right";
            case KeyEvent.KEYCODE_PAGE_UP: return "PgUp";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "PgDn";
            case KeyEvent.KEYCODE_HOME: return "Home";
            case KeyEvent.KEYCODE_MOVE_END: return "End";
            case KeyEvent.KEYCODE_INSERT: return "Ins";
            case KeyEvent.KEYCODE_F1: return "F1";
            case KeyEvent.KEYCODE_F2: return "F2";
            case KeyEvent.KEYCODE_F3: return "F3";
            case KeyEvent.KEYCODE_F4: return "F4";
            case KeyEvent.KEYCODE_F5: return "F5";
            case KeyEvent.KEYCODE_F6: return "F6";
            case KeyEvent.KEYCODE_F7: return "F7";
            case KeyEvent.KEYCODE_F8: return "F8";
            case KeyEvent.KEYCODE_F9: return "F9";
            case KeyEvent.KEYCODE_F10: return "F10";
            case KeyEvent.KEYCODE_F11: return "F11";
            case KeyEvent.KEYCODE_F12: return "F12";
            default:
                String name = KeyEvent.keyCodeToString(keyCode);
                if (name.startsWith("KEYCODE_")) name = name.substring(8);
                name = name.replace('_', ' ');
                if (name.length() > 9) name = name.substring(0, 9);
                return name;
        }
    }
}
