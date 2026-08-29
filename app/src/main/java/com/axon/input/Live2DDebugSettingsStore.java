package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;

/** Persistent model-debug controls shared by standalone Live2D and imported keyboard-cat models. */
public final class Live2DDebugSettingsStore {
    private static final String PREFS = "live2d_debug_controls_v1";
    private static final String KEY_LOCKS_PREFIX = "locks.";
    private static final String KEY_EXPRESSION_WEIGHTS_PREFIX = "expression_weights.";

    private Live2DDebugSettingsStore() {}

    public static Float getParameterLock(Context context, String target, String parameterId) {
        String id = normalizeId(parameterId);
        if (id.isEmpty()) return null;
        JSONObject locks = readObject(context, KEY_LOCKS_PREFIX + safeTarget(target));
        if (!locks.has(id)) return null;
        double value = locks.optDouble(id, Double.NaN);
        if (Double.isNaN(value)) return null;
        return (float) clamp01(value);
    }

    public static void setParameterLock(Context context, String target, String parameterId, Float normalized) {
        String id = normalizeId(parameterId);
        if (id.isEmpty()) return;
        String key = KEY_LOCKS_PREFIX + safeTarget(target);
        JSONObject locks = readObject(context, key);
        try {
            if (normalized == null) locks.remove(id);
            else locks.put(id, clamp01(normalized));
        } catch (Throwable ignored) {}
        writeObject(context, key, locks);
    }

    public static JSONObject parameterLocksJson(Context context, String target) {
        JSONObject source = readObject(context, KEY_LOCKS_PREFIX + safeTarget(target));
        JSONObject result = new JSONObject();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            double value = source.optDouble(id, Double.NaN);
            if (Double.isNaN(value)) continue;
            try { result.put(id, clamp01(value)); } catch (Throwable ignored) {}
        }
        return result;
    }

    public static int getExpressionWeight(Context context, String target, String expressionToken) {
        String token = normalizeId(expressionToken);
        if (token.isEmpty()) return 100;
        JSONObject weights = readObject(context, KEY_EXPRESSION_WEIGHTS_PREFIX + safeTarget(target));
        return Math.max(0, Math.min(100, weights.optInt(token, 100)));
    }

    public static void setExpressionWeight(Context context, String target, String expressionToken, int weight) {
        String token = normalizeId(expressionToken);
        if (token.isEmpty()) return;
        String key = KEY_EXPRESSION_WEIGHTS_PREFIX + safeTarget(target);
        JSONObject weights = readObject(context, key);
        int clamped = Math.max(0, Math.min(100, weight));
        try {
            if (clamped == 100) weights.remove(token);
            else weights.put(token, clamped);
        } catch (Throwable ignored) {}
        writeObject(context, key, weights);
    }

    public static void clearTarget(Context context, String target) {
        String safe = safeTarget(target);
        prefs(context).edit()
                .remove(KEY_LOCKS_PREFIX + safe)
                .remove(KEY_EXPRESSION_WEIGHTS_PREFIX + safe)
                .apply();
    }

    private static JSONObject readObject(Context context, String key) {
        String raw = prefs(context).getString(key, "{}");
        try { return new JSONObject(raw == null ? "{}" : raw); }
        catch (Throwable ignored) { return new JSONObject(); }
    }

    private static void writeObject(Context context, String key, JSONObject object) {
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

    private static String normalizeId(String value) { return value == null ? "" : value.trim(); }
    private static double clamp01(double value) { return Math.max(0.0, Math.min(1.0, value)); }
}
