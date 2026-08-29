package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Durable, renderer-agnostic Cubism Physics controls shared by standalone Live2D and
 * imported keyboard-cat Live2D styles.
 *
 * Global strength is stored per target. Group overrides are keyed by the stable PhysicsSetting Id
 * emitted by Cubism (falling back to Name/index in the parser/runtime) so changing UI order does
 * not silently move a user's override to another physics group.
 */
public final class Live2DPhysicsSettingsStore {
    public static final String TARGET_LIVE2D = "live2d";

    private static final String PREFS = "live2d_physics_controls";
    private static final String KEY_GLOBAL_PREFIX = "global.";
    private static final String KEY_GROUPS_PREFIX = "groups.";
    private static final int DEFAULT_STRENGTH = 100;
    private static final int MAX_STRENGTH = 200;

    private Live2DPhysicsSettingsStore() {}

    public static String keyboardCatTarget(String styleId) {
        String id = styleId == null || styleId.trim().isEmpty()
                ? BongoCatStyleManager.BUILTIN_ID : styleId.trim();
        return "keyboard-cat:" + id;
    }

    public static int getGlobalStrength(Context context, String target) {
        return clampStrength(prefs(context).getInt(KEY_GLOBAL_PREFIX + normalizeTarget(target), DEFAULT_STRENGTH));
    }

    public static void setGlobalStrength(Context context, String target, int strength) {
        PreferenceWriter.putIntIfChanged(
                prefs(context), KEY_GLOBAL_PREFIX + normalizeTarget(target), clampStrength(strength));
    }

    public static GroupSetting getGroupSetting(Context context, String target, String groupKey) {
        String key = normalizeGroupKey(groupKey);
        if (key.isEmpty()) return new GroupSetting(true, DEFAULT_STRENGTH);
        JSONObject groups = readGroups(context, target);
        JSONObject item = groups.optJSONObject(key);
        if (item == null) return new GroupSetting(true, DEFAULT_STRENGTH);
        return new GroupSetting(item.optBoolean("enabled", true), clampStrength(item.optInt("strength", DEFAULT_STRENGTH)));
    }

    public static void setGroupEnabled(Context context, String target, String groupKey, boolean enabled) {
        GroupSetting old = getGroupSetting(context, target, groupKey);
        writeGroup(context, target, groupKey, enabled, old.strength);
    }

    public static void setGroupStrength(Context context, String target, String groupKey, int strength) {
        GroupSetting old = getGroupSetting(context, target, groupKey);
        writeGroup(context, target, groupKey, old.enabled, strength);
    }

    public static void resetGroup(Context context, String target, String groupKey) {
        String key = normalizeGroupKey(groupKey);
        if (key.isEmpty()) return;
        JSONObject groups = readGroups(context, target);
        if (!groups.has(key)) return;
        groups.remove(key);
        writeGroups(context, target, groups);
    }

    public static void clearTarget(Context context, String target) {
        String normalized = normalizeTarget(target);
        SharedPreferences values = prefs(context);
        values.edit()
                .remove(KEY_GLOBAL_PREFIX + normalized)
                .remove(KEY_GROUPS_PREFIX + normalized)
                .apply();
    }

    /** Clear stale per-group overrides when the standalone Live2D model is replaced. */
    public static void clearGroups(Context context, String target) {
        SharedPreferences values = prefs(context);
        String key = KEY_GROUPS_PREFIX + normalizeTarget(target);
        if (values.contains(key)) values.edit().remove(key).apply();
    }

    public static JSONObject runtimeJson(Context context, String target) {
        return runtimeJson(context, target, getGlobalStrength(context, target));
    }

    /** Runtime JSON with a temporary global strength, used while a SeekBar is still being dragged. */
    public static JSONObject runtimeJson(Context context, String target, int globalStrengthOverride) {
        JSONObject result = new JSONObject();
        try {
            result.put("globalStrength", clampStrength(globalStrengthOverride) / 100.0);
            JSONObject groupsOut = new JSONObject();
            JSONObject stored = readGroups(context, target);
            Iterator<String> keys = stored.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = stored.optJSONObject(key);
                if (item == null) continue;
                JSONObject runtime = new JSONObject();
                runtime.put("enabled", item.optBoolean("enabled", true));
                runtime.put("strength", clampStrength(item.optInt("strength", DEFAULT_STRENGTH)) / 100.0);
                groupsOut.put(key, runtime);
            }
            result.put("groups", groupsOut);
        } catch (Throwable ignored) {}
        return result;
    }

    /** Runtime JSON with one temporary group value, avoiding SharedPreferences writes during drag. */
    public static JSONObject runtimeJsonWithGroupPreview(Context context, String target, String groupKey,
                                                          boolean enabled, int strength) {
        JSONObject result = runtimeJson(context, target);
        try {
            JSONObject groups = result.optJSONObject("groups");
            if (groups == null) {
                groups = new JSONObject();
                result.put("groups", groups);
            }
            JSONObject item = new JSONObject();
            item.put("enabled", enabled);
            item.put("strength", clampStrength(strength) / 100.0);
            groups.put(normalizeGroupKey(groupKey), item);
        } catch (Throwable ignored) {}
        return result;
    }

    private static void writeGroup(Context context, String target, String groupKey,
                                   boolean enabled, int strength) {
        String key = normalizeGroupKey(groupKey);
        if (key.isEmpty()) return;
        JSONObject groups = readGroups(context, target);
        try {
            if (enabled && clampStrength(strength) == DEFAULT_STRENGTH) {
                // Defaults do not need durable JSON; this keeps the fast path compact.
                groups.remove(key);
            } else {
                JSONObject item = new JSONObject();
                item.put("enabled", enabled);
                item.put("strength", clampStrength(strength));
                groups.put(key, item);
            }
        } catch (Throwable ignored) {}
        writeGroups(context, target, groups);
    }

    private static JSONObject readGroups(Context context, String target) {
        String raw = prefs(context).getString(KEY_GROUPS_PREFIX + normalizeTarget(target), "{}");
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (Throwable ignored) { return new JSONObject(); }
    }

    private static void writeGroups(Context context, String target, JSONObject groups) {
        String key = KEY_GROUPS_PREFIX + normalizeTarget(target);
        String next = groups == null ? "{}" : groups.toString();
        String old = prefs(context).getString(key, "{}");
        if (next.equals(old)) return;
        prefs(context).edit().putString(key, next).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int clampStrength(int value) {
        return Math.max(0, Math.min(MAX_STRENGTH, value));
    }

    private static String normalizeTarget(String target) {
        String value = target == null ? "" : target.trim();
        return value.isEmpty() ? TARGET_LIVE2D : value;
    }

    private static String normalizeGroupKey(String key) {
        return key == null ? "" : key.trim();
    }

    public static final class GroupSetting {
        public final boolean enabled;
        public final int strength;

        GroupSetting(boolean enabled, int strength) {
            this.enabled = enabled;
            this.strength = clampStrength(strength);
        }
    }
}
