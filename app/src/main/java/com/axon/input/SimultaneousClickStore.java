package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent non-consuming source -> synchronized target mappings used by Input Enhancement. */
public final class SimultaneousClickStore {
    private static final String KEY_ENABLED = "simultaneous_click_enabled_v1";
    private static final String KEY_BINDINGS = "simultaneous_click_bindings_v2";

    // Legacy single-binding keys. They are migrated once into KEY_BINDINGS and then removed.
    private static final String KEY_SOURCE = "simultaneous_click_source_v1";
    private static final String KEY_TARGET = "simultaneous_click_target_v1";
    private static final String KEY_TARGET_EVDEV = "simultaneous_click_target_evdev_v1";

    public static final class Binding {
        public final long id;
        public final int sourceInputCode;
        public final int targetInputCode;
        public final int targetEvdevCode;

        Binding(long id, int sourceInputCode, int targetInputCode, int targetEvdevCode) {
            this.id = id;
            this.sourceInputCode = sourceInputCode;
            this.targetInputCode = targetInputCode;
            this.targetEvdevCode = targetEvdevCode;
        }
    }

    private SimultaneousClickStore() {}

    public static boolean isEnabled(Context context) {
        return AppPreferences.get(context).getBoolean(KEY_ENABLED, false) && hasBinding(context);
    }

    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = AppPreferences.get(context);
        boolean next = enabled && hasBinding(context);
        if (prefs.getBoolean(KEY_ENABLED, false) == next) return;
        prefs.edit().putBoolean(KEY_ENABLED, next).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static List<Binding> load(Context context) {
        SharedPreferences prefs = AppPreferences.get(context);
        String raw = prefs.getString(KEY_BINDINGS, null);
        ArrayList<Binding> result = decode(raw);
        if (raw == null) {
            Binding legacy = readLegacy(prefs);
            if (legacy != null) {
                result.add(legacy);
                writeInternal(prefs, result, true);
            }
        }
        return result;
    }

    public static boolean hasBinding(Context context) {
        return !load(context).isEmpty();
    }

    public static boolean usesSource(Context context, int inputCode) {
        if (!InputBinding.isValid(inputCode)) return false;
        for (Binding binding : load(context)) {
            if (binding.sourceInputCode == inputCode) return true;
        }
        return false;
    }

    /** Adds another mapping. Existing mappings are preserved; an exact source/target pair is updated in place. */
    public static void addBinding(Context context, int source, int target, int targetEvdev) {
        if (!isValid(source, target, targetEvdev)) return;
        SharedPreferences prefs = AppPreferences.get(context);
        ArrayList<Binding> bindings = new ArrayList<>(load(context));
        for (int i = 0; i < bindings.size(); i++) {
            Binding current = bindings.get(i);
            if (current.sourceInputCode == source && current.targetInputCode == target) {
                if (current.targetEvdevCode == targetEvdev) return;
                bindings.set(i, new Binding(current.id, source, target, targetEvdev));
                writeInternal(prefs, bindings, false);
                AxonInputAccessibilityService.refreshActiveService();
                return;
            }
        }
        bindings.add(new Binding(nextId(bindings), source, target, targetEvdev));
        writeInternal(prefs, bindings, false);
        AxonInputAccessibilityService.refreshActiveService();
    }

    /** Legacy alias retained for imported/older call sites; unlike v1 it no longer replaces prior mappings. */
    public static void setBinding(Context context, int source, int target, int targetEvdev) {
        addBinding(context, source, target, targetEvdev);
    }

    public static void removeBinding(Context context, long id) {
        SharedPreferences prefs = AppPreferences.get(context);
        ArrayList<Binding> bindings = new ArrayList<>(load(context));
        boolean changed = bindings.removeIf(binding -> binding.id == id);
        if (!changed) return;
        writeInternal(prefs, bindings, false);
        if (bindings.isEmpty()) prefs.edit().putBoolean(KEY_ENABLED, false).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static void clearBinding(Context context) {
        SharedPreferences prefs = AppPreferences.get(context);
        prefs.edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_BINDINGS)
                .remove(KEY_SOURCE)
                .remove(KEY_TARGET)
                .remove(KEY_TARGET_EVDEV)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    /** First binding accessors are kept only for old imported code/config compatibility. */
    public static int getSource(Context context) {
        List<Binding> bindings = load(context);
        return bindings.isEmpty() ? -1 : bindings.get(0).sourceInputCode;
    }

    public static int getTarget(Context context) {
        List<Binding> bindings = load(context);
        return bindings.isEmpty() ? -1 : bindings.get(0).targetInputCode;
    }

    public static int getTargetEvdev(Context context) {
        List<Binding> bindings = load(context);
        return bindings.isEmpty() ? -1 : bindings.get(0).targetEvdevCode;
    }

    private static ArrayList<Binding> decode(String raw) {
        ArrayList<Binding> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> pairs = new LinkedHashSet<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                int source = object.optInt("source", -1);
                int target = object.optInt("target", -1);
                int evdev = object.optInt("evdev", -1);
                if (!isValid(source, target, evdev)) continue;
                String pair = source + ":" + target;
                if (!pairs.add(pair)) continue;
                long id = object.optLong("id", 0L);
                if (id <= 0L || ids.contains(id)) id = nextId(result);
                ids.add(id);
                result.add(new Binding(id, source, target, evdev));
            }
        } catch (Throwable ignored) {
            result.clear();
        }
        return result;
    }

    private static Binding readLegacy(SharedPreferences prefs) {
        int source = prefs.getInt(KEY_SOURCE, -1);
        int target = prefs.getInt(KEY_TARGET, -1);
        int evdev = prefs.getInt(KEY_TARGET_EVDEV, -1);
        if (!isValid(source, target, evdev)) return null;
        return new Binding(1L, source, target, evdev);
    }

    private static void writeInternal(SharedPreferences prefs, List<Binding> bindings, boolean migration) {
        JSONArray array = new JSONArray();
        for (Binding binding : bindings) {
            if (binding == null || !isValid(binding.sourceInputCode, binding.targetInputCode, binding.targetEvdevCode)) {
                continue;
            }
            JSONObject object = new JSONObject();
            try {
                object.put("id", binding.id);
                object.put("source", binding.sourceInputCode);
                object.put("target", binding.targetInputCode);
                object.put("evdev", binding.targetEvdevCode);
                array.put(object);
            } catch (Throwable ignored) {
            }
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (array.length() == 0) editor.remove(KEY_BINDINGS);
        else editor.putString(KEY_BINDINGS, array.toString());
        if (migration) {
            editor.remove(KEY_SOURCE).remove(KEY_TARGET).remove(KEY_TARGET_EVDEV);
        }
        editor.apply();
    }

    private static boolean isValid(int source, int target, int targetEvdev) {
        return InputBinding.isValid(source) && InputBinding.isValid(target)
                && source != target && targetEvdev > 0;
    }

    private static long nextId(List<Binding> bindings) {
        long max = 0L;
        for (Binding binding : bindings) if (binding != null) max = Math.max(max, binding.id);
        long now = System.currentTimeMillis();
        return Math.max(max + 1L, now > 0L ? now : 1L);
    }
}
