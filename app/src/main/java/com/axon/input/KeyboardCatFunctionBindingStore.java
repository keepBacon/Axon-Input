package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent per-style bindings and debug parameter values for imported keyboard-cat models.
 *
 * Disk data is JSON for forward-compatible persistence, while the active process keeps one parsed
 * snapshot per style. Model-function menus and service refreshes therefore do not repeatedly parse
 * the same 100+ parameter JSON on the UI thread.
 */
public final class KeyboardCatFunctionBindingStore {
    public static final String MODE_HOLD = "hold";
    public static final String MODE_TOGGLE = "toggle";
    public static final String MODE_PULSE = "pulse";
    public static final String MODE_SEQUENCE = "sequence";

    private static final String PREFS = "keyboard_cat_model_functions_v1";
    private static final String KEY_BINDINGS_PREFIX = "bindings_";
    private static final String KEY_VALUES_PREFIX = "values_";

    private static final Object CACHE_LOCK = new Object();
    private static final HashMap<String, Snapshot> CACHE = new HashMap<>();

    private KeyboardCatFunctionBindingStore() {}

    public static final class Binding {
        public final String token;
        public final int inputCode;
        public final String mode;

        Binding(String token, int inputCode, String mode) {
            this.token = token == null ? "" : token;
            this.inputCode = inputCode;
            this.mode = normalizeMode(mode);
        }
    }

    private static final class Snapshot {
        final ArrayList<Binding> bindings;
        final HashMap<String, Binding> byToken;
        final HashMap<Integer, ArrayList<Binding>> byInput;
        final LinkedHashMap<String, Float> values;

        Snapshot(List<Binding> bindings, Map<String, Float> values) {
            this.bindings = new ArrayList<>(bindings);
            this.byToken = new HashMap<>();
            this.byInput = new HashMap<>();
            for (Binding binding : this.bindings) {
                byToken.put(binding.token, binding);
                byInput.computeIfAbsent(binding.inputCode, ignored -> new ArrayList<>()).add(binding);
            }
            this.values = new LinkedHashMap<>(values);
        }
    }

    public static List<Binding> loadBindings(Context context, String styleId) {
        Snapshot snapshot = snapshot(context, styleId);
        return new ArrayList<>(snapshot.bindings);
    }

    public static Binding findByToken(Context context, String styleId, String token) {
        if (token == null || token.isEmpty()) return null;
        return snapshot(context, styleId).byToken.get(token);
    }

    public static void putBinding(Context context, String styleId, String token, int inputCode, String mode) {
        if (token == null || token.isEmpty() || inputCode < 0) return;
        String safe = safeStyle(styleId);
        synchronized (CACHE_LOCK) {
            Snapshot current = snapshotLocked(context, safe);
            ArrayList<Binding> next = new ArrayList<>();
            // One physical input maps to one model action in a style. Rebinding replaces both sides.
            for (Binding item : current.bindings) {
                if (token.equals(item.token) || inputCode == item.inputCode) continue;
                next.add(item);
            }
            next.add(new Binding(token, inputCode, mode));
            writeBindings(context, safe, next);
            CACHE.put(safe, new Snapshot(next, current.values));
        }
    }

    public static void removeBinding(Context context, String styleId, String token) {
        if (token == null || token.isEmpty()) return;
        String safe = safeStyle(styleId);
        synchronized (CACHE_LOCK) {
            Snapshot current = snapshotLocked(context, safe);
            if (!current.byToken.containsKey(token)) return;
            ArrayList<Binding> next = new ArrayList<>();
            for (Binding item : current.bindings) if (!token.equals(item.token)) next.add(item);
            writeBindings(context, safe, next);
            CACHE.put(safe, new Snapshot(next, current.values));
        }
    }

    public static List<Binding> bindingsForInput(Context context, String styleId, int inputCode) {
        if (inputCode < 0) return new ArrayList<>();
        ArrayList<Binding> matches = snapshot(context, styleId).byInput.get(inputCode);
        return matches == null ? new ArrayList<>() : new ArrayList<>(matches);
    }

    public static Map<String, Float> loadParameterValues(Context context, String styleId) {
        return new LinkedHashMap<>(snapshot(context, styleId).values);
    }

    public static float getParameterValue(Context context, String styleId, String parameterId, float fallback) {
        Float value = snapshot(context, styleId).values.get(parameterId);
        return value == null ? fallback : value;
    }

    public static void setParameterValue(Context context, String styleId, String parameterId, Float normalized) {
        if (parameterId == null || parameterId.isEmpty()) return;
        String safe = safeStyle(styleId);
        synchronized (CACHE_LOCK) {
            Snapshot current = snapshotLocked(context, safe);
            LinkedHashMap<String, Float> values = new LinkedHashMap<>(current.values);
            if (normalized == null) values.remove(parameterId);
            else values.put(parameterId, Math.max(0f, Math.min(1f, normalized)));
            writeValues(context, safe, values);
            CACHE.put(safe, new Snapshot(current.bindings, values));
        }
    }

    public static void clearStyle(Context context, String styleId) {
        String safe = safeStyle(styleId);
        SharedPreferences preferences = prefs(context);
        String bindingsKey = KEY_BINDINGS_PREFIX + safe;
        String valuesKey = KEY_VALUES_PREFIX + safe;
        synchronized (CACHE_LOCK) {
            CACHE.remove(safe);
            if (!preferences.contains(bindingsKey) && !preferences.contains(valuesKey)) return;
            preferences.edit().remove(bindingsKey).remove(valuesKey).apply();
        }
    }

    public static JSONObject parameterValuesJson(Context context, String styleId) {
        JSONObject result = new JSONObject();
        try {
            for (Map.Entry<String, Float> entry : snapshot(context, styleId).values.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static Snapshot snapshot(Context context, String styleId) {
        String safe = safeStyle(styleId);
        synchronized (CACHE_LOCK) {
            return snapshotLocked(context, safe);
        }
    }

    private static Snapshot snapshotLocked(Context context, String safeStyle) {
        Snapshot cached = CACHE.get(safeStyle);
        if (cached != null) return cached;
        Snapshot loaded = new Snapshot(readBindings(context, safeStyle), readValues(context, safeStyle));
        CACHE.put(safeStyle, loaded);
        return loaded;
    }

    private static List<Binding> readBindings(Context context, String safeStyle) {
        ArrayList<Binding> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_BINDINGS_PREFIX + safeStyle, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String token = item.optString("token", "").trim();
                int inputCode = item.optInt("input", -1);
                if (token.isEmpty() || inputCode < 0) continue;
                result.add(new Binding(token, inputCode, item.optString("mode", MODE_TOGGLE)));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static Map<String, Float> readValues(Context context, String safeStyle) {
        LinkedHashMap<String, Float> result = new LinkedHashMap<>();
        String raw = prefs(context).getString(KEY_VALUES_PREFIX + safeStyle, "{}");
        try {
            JSONObject object = new JSONObject(raw == null ? "{}" : raw);
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                double value = object.optDouble(id, Double.NaN);
                if (Double.isNaN(value)) continue;
                result.put(id, (float) Math.max(0.0, Math.min(1.0, value)));
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void writeBindings(Context context, String safeStyle, List<Binding> bindings) {
        JSONArray array = new JSONArray();
        try {
            for (Binding binding : bindings) {
                JSONObject item = new JSONObject();
                item.put("token", binding.token);
                item.put("input", binding.inputCode);
                item.put("mode", binding.mode);
                array.put(item);
            }
        } catch (Exception ignored) {}
        PreferenceWriter.putStringIfChanged(
                prefs(context), KEY_BINDINGS_PREFIX + safeStyle, array.toString());
    }

    private static void writeValues(Context context, String safeStyle, Map<String, Float> values) {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, Float> entry : values.entrySet()) object.put(entry.getKey(), entry.getValue());
        } catch (Exception ignored) {}
        PreferenceWriter.putStringIfChanged(
                prefs(context), KEY_VALUES_PREFIX + safeStyle, object.toString());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safeStyle(String styleId) {
        String value = styleId == null || styleId.isEmpty() ? BongoCatStyleManager.BUILTIN_ID : styleId;
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String normalizeMode(String mode) {
        if (MODE_HOLD.equals(mode)) return MODE_HOLD;
        if (MODE_PULSE.equals(mode)) return MODE_PULSE;
        if (MODE_SEQUENCE.equals(mode)) return MODE_SEQUENCE;
        return MODE_TOGGLE;
    }
}
