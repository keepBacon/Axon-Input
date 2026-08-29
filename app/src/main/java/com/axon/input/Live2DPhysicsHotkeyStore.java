package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Input bindings that trigger a short physical impulse in one Cubism Physics group without
 * consuming the original keyboard/mouse/gamepad input. The shortcut itself can be disabled
 * without deleting its binding; the Physics group enable state remains independent. */
public final class Live2DPhysicsHotkeyStore {
    private static final String PREFS = "live2d_physics_hotkeys_v1";
    private static final String KEY_PREFIX = "target.";
    private static final String FIELD_INPUT = "input";
    private static final String FIELD_ENABLED = "enabled";

    private Live2DPhysicsHotkeyStore() {}

    public static final class Binding {
        public final String groupKey;
        public final int inputCode;
        public final boolean enabled;

        Binding(String groupKey, int inputCode, boolean enabled) {
            this.groupKey = groupKey == null ? "" : groupKey;
            this.inputCode = inputCode;
            this.enabled = enabled;
        }
    }

    public static int getHotkey(Context context, String target, String groupKey) {
        String group = normalize(groupKey);
        if (group.isEmpty()) return -1;
        return readInput(read(context, target).opt(group));
    }

    /** Existing v1 integer bindings are treated as enabled for backward compatibility. */
    public static boolean isEnabled(Context context, String target, String groupKey) {
        String group = normalize(groupKey);
        if (group.isEmpty()) return false;
        Object raw = read(context, target).opt(group);
        return readInput(raw) >= 0 && readEnabled(raw);
    }

    public static void setHotkey(Context context, String target, String groupKey, int inputCode) {
        String group = normalize(groupKey);
        if (group.isEmpty()) return;
        JSONObject values = read(context, target);
        try {
            if (inputCode < 0) {
                values.remove(group);
            } else {
                Object previous = values.opt(group);
                JSONObject entry = new JSONObject();
                entry.put(FIELD_INPUT, inputCode);
                entry.put(FIELD_ENABLED, previous == null || previous == JSONObject.NULL || readEnabled(previous));
                values.put(group, entry);
            }
        } catch (Throwable ignored) {}
        write(context, target, values);
    }

    /** Enable/disable only the action shortcut. The binding is intentionally preserved. */
    public static void setEnabled(Context context, String target, String groupKey, boolean enabled) {
        String group = normalize(groupKey);
        if (group.isEmpty()) return;
        JSONObject values = read(context, target);
        Object previous = values.opt(group);
        int inputCode = readInput(previous);
        if (inputCode < 0) return;
        try {
            JSONObject entry = new JSONObject();
            entry.put(FIELD_INPUT, inputCode);
            entry.put(FIELD_ENABLED, enabled);
            values.put(group, entry);
        } catch (Throwable ignored) {}
        write(context, target, values);
    }

    /** Returns all stored bindings, including disabled ones, so conflict detection stays stable. */
    public static List<Binding> load(Context context, String target) {
        ArrayList<Binding> result = new ArrayList<>();
        JSONObject values = read(context, target);
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String group = keys.next();
            Object raw = values.opt(group);
            int input = readInput(raw);
            if (!group.isEmpty() && input >= 0) {
                result.add(new Binding(group, input, readEnabled(raw)));
            }
        }
        return result;
    }

    public static void clearTarget(Context context, String target) {
        prefs(context).edit().remove(KEY_PREFIX + safeTarget(target)).apply();
    }

    private static int readInput(Object raw) {
        if (raw instanceof Number) return ((Number) raw).intValue();
        if (raw instanceof JSONObject) return ((JSONObject) raw).optInt(FIELD_INPUT, -1);
        return -1;
    }

    private static boolean readEnabled(Object raw) {
        if (raw instanceof Number) return true;
        if (raw instanceof JSONObject) return ((JSONObject) raw).optBoolean(FIELD_ENABLED, true);
        return false;
    }

    private static JSONObject read(Context context, String target) {
        String raw = prefs(context).getString(KEY_PREFIX + safeTarget(target), "{}");
        try { return new JSONObject(raw == null ? "{}" : raw); }
        catch (Throwable ignored) { return new JSONObject(); }
    }

    private static void write(Context context, String target, JSONObject object) {
        String key = KEY_PREFIX + safeTarget(target);
        String next = object == null ? "{}" : object.toString();
        String old = prefs(context).getString(key, "{}");
        if (next.equals(old)) return;
        prefs(context).edit().putString(key, next).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safeTarget(String target) {
        String value = target == null || target.trim().isEmpty()
                ? Live2DPhysicsSettingsStore.TARGET_LIVE2D : target.trim();
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }
}
