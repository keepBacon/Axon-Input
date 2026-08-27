package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent one-to-one gamepad-button -> keyboard-key mappings. */
public final class GamepadMappingStore {
    private static final String KEY_MAPPINGS = "gamepad_keyboard_mappings_v1";

    public static final class Mapping {
        public final int sourceInputCode;
        public final int targetKeyCode;

        Mapping(int sourceInputCode, int targetKeyCode) {
            this.sourceInputCode = sourceInputCode;
            this.targetKeyCode = targetKeyCode;
        }
    }

    private GamepadMappingStore() {}

    public static List<Mapping> load(Context context) {
        SharedPreferences prefs = AppPreferences.get(context);
        Set<String> stored = prefs.getStringSet(KEY_MAPPINGS, null);
        ArrayList<Mapping> result = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return result;
        LinkedHashMap<Integer, Integer> unique = new LinkedHashMap<>();
        for (String raw : stored) {
            if (raw == null) continue;
            int split = raw.indexOf(':');
            if (split <= 0 || split >= raw.length() - 1) continue;
            try {
                int source = Integer.parseInt(raw.substring(0, split));
                int target = Integer.parseInt(raw.substring(split + 1));
                if (!InputBinding.isGamepad(source)) continue;
                if (!isSupportedKeyboardTarget(target)) continue;
                unique.put(source, target);
            } catch (NumberFormatException ignored) {
            }
        }
        for (Map.Entry<Integer, Integer> entry : unique.entrySet()) {
            result.add(new Mapping(entry.getKey(), entry.getValue()));
        }
        result.sort(Comparator.comparingInt(m -> m.sourceInputCode));
        return result;
    }

    public static void put(Context context, int sourceInputCode, int targetKeyCode) {
        if (!InputBinding.isGamepad(sourceInputCode) || !isSupportedKeyboardTarget(targetKeyCode)) return;
        LinkedHashMap<Integer, Integer> map = asMap(load(context));
        Integer previous = map.put(sourceInputCode, targetKeyCode);
        if (previous != null && previous == targetKeyCode) return;
        write(context, map);
    }

    public static void remove(Context context, int sourceInputCode) {
        LinkedHashMap<Integer, Integer> map = asMap(load(context));
        if (map.remove(sourceInputCode) == null) return;
        write(context, map);
    }

    public static void clear(Context context) {
        SharedPreferences prefs = AppPreferences.get(context);
        if (!prefs.contains(KEY_MAPPINGS)) return;
        prefs.edit().remove(KEY_MAPPINGS).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isSupportedKeyboardTarget(int keyCode) {
        return keyboardEvdevCode(keyCode) > 0;
    }

    /** Linux evdev key code used by the uinput keyboard mapper. */
    public static int keyboardEvdevCode(int keyCode) {
        return switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_ESCAPE -> 1;
            case android.view.KeyEvent.KEYCODE_1 -> 2;
            case android.view.KeyEvent.KEYCODE_2 -> 3;
            case android.view.KeyEvent.KEYCODE_3 -> 4;
            case android.view.KeyEvent.KEYCODE_4 -> 5;
            case android.view.KeyEvent.KEYCODE_5 -> 6;
            case android.view.KeyEvent.KEYCODE_6 -> 7;
            case android.view.KeyEvent.KEYCODE_7 -> 8;
            case android.view.KeyEvent.KEYCODE_8 -> 9;
            case android.view.KeyEvent.KEYCODE_9 -> 10;
            case android.view.KeyEvent.KEYCODE_0 -> 11;
            case android.view.KeyEvent.KEYCODE_MINUS -> 12;
            case android.view.KeyEvent.KEYCODE_EQUALS -> 13;
            case android.view.KeyEvent.KEYCODE_DEL -> 14;
            case android.view.KeyEvent.KEYCODE_TAB -> 15;
            case android.view.KeyEvent.KEYCODE_Q -> 16;
            case android.view.KeyEvent.KEYCODE_W -> 17;
            case android.view.KeyEvent.KEYCODE_E -> 18;
            case android.view.KeyEvent.KEYCODE_R -> 19;
            case android.view.KeyEvent.KEYCODE_T -> 20;
            case android.view.KeyEvent.KEYCODE_Y -> 21;
            case android.view.KeyEvent.KEYCODE_U -> 22;
            case android.view.KeyEvent.KEYCODE_I -> 23;
            case android.view.KeyEvent.KEYCODE_O -> 24;
            case android.view.KeyEvent.KEYCODE_P -> 25;
            case android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> 26;
            case android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> 27;
            case android.view.KeyEvent.KEYCODE_ENTER -> 28;
            case android.view.KeyEvent.KEYCODE_CTRL_LEFT -> 29;
            case android.view.KeyEvent.KEYCODE_A -> 30;
            case android.view.KeyEvent.KEYCODE_S -> 31;
            case android.view.KeyEvent.KEYCODE_D -> 32;
            case android.view.KeyEvent.KEYCODE_F -> 33;
            case android.view.KeyEvent.KEYCODE_G -> 34;
            case android.view.KeyEvent.KEYCODE_H -> 35;
            case android.view.KeyEvent.KEYCODE_J -> 36;
            case android.view.KeyEvent.KEYCODE_K -> 37;
            case android.view.KeyEvent.KEYCODE_L -> 38;
            case android.view.KeyEvent.KEYCODE_SEMICOLON -> 39;
            case android.view.KeyEvent.KEYCODE_APOSTROPHE -> 40;
            case android.view.KeyEvent.KEYCODE_GRAVE -> 41;
            case android.view.KeyEvent.KEYCODE_SHIFT_LEFT -> 42;
            case android.view.KeyEvent.KEYCODE_BACKSLASH -> 43;
            case android.view.KeyEvent.KEYCODE_Z -> 44;
            case android.view.KeyEvent.KEYCODE_X -> 45;
            case android.view.KeyEvent.KEYCODE_C -> 46;
            case android.view.KeyEvent.KEYCODE_V -> 47;
            case android.view.KeyEvent.KEYCODE_B -> 48;
            case android.view.KeyEvent.KEYCODE_N -> 49;
            case android.view.KeyEvent.KEYCODE_M -> 50;
            case android.view.KeyEvent.KEYCODE_COMMA -> 51;
            case android.view.KeyEvent.KEYCODE_PERIOD -> 52;
            case android.view.KeyEvent.KEYCODE_SLASH -> 53;
            case android.view.KeyEvent.KEYCODE_SHIFT_RIGHT -> 54;
            case android.view.KeyEvent.KEYCODE_ALT_LEFT -> 56;
            case android.view.KeyEvent.KEYCODE_SPACE -> 57;
            case android.view.KeyEvent.KEYCODE_CAPS_LOCK -> 58;
            case android.view.KeyEvent.KEYCODE_F1 -> 59;
            case android.view.KeyEvent.KEYCODE_F2 -> 60;
            case android.view.KeyEvent.KEYCODE_F3 -> 61;
            case android.view.KeyEvent.KEYCODE_F4 -> 62;
            case android.view.KeyEvent.KEYCODE_F5 -> 63;
            case android.view.KeyEvent.KEYCODE_F6 -> 64;
            case android.view.KeyEvent.KEYCODE_F7 -> 65;
            case android.view.KeyEvent.KEYCODE_F8 -> 66;
            case android.view.KeyEvent.KEYCODE_F9 -> 67;
            case android.view.KeyEvent.KEYCODE_F10 -> 68;
            case android.view.KeyEvent.KEYCODE_F11 -> 87;
            case android.view.KeyEvent.KEYCODE_F12 -> 88;
            case android.view.KeyEvent.KEYCODE_CTRL_RIGHT -> 97;
            case android.view.KeyEvent.KEYCODE_ALT_RIGHT -> 100;
            case android.view.KeyEvent.KEYCODE_META_LEFT -> 125;
            case android.view.KeyEvent.KEYCODE_META_RIGHT -> 126;
            default -> -1;
        };
    }

    private static LinkedHashMap<Integer, Integer> asMap(List<Mapping> mappings) {
        LinkedHashMap<Integer, Integer> out = new LinkedHashMap<>();
        for (Mapping mapping : mappings) out.put(mapping.sourceInputCode, mapping.targetKeyCode);
        return out;
    }

    private static void write(Context context, LinkedHashMap<Integer, Integer> map) {
        LinkedHashSet<String> encoded = new LinkedHashSet<>();
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            encoded.add(entry.getKey() + ":" + entry.getValue());
        }
        SharedPreferences.Editor editor = AppPreferences.get(context).edit();
        if (encoded.isEmpty()) editor.remove(KEY_MAPPINGS);
        else editor.putStringSet(KEY_MAPPINGS, encoded);
        editor.apply();
        AxonInputAccessibilityService.refreshActiveService();
    }
}
