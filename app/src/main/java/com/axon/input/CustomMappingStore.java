package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent consuming trigger -> ordered key sequence mappings. */
public final class CustomMappingStore {
    private static final String KEY_ENABLED = "custom_mapping_enabled_v1";
    private static final String KEY_RULES = "custom_mapping_rules_v1";
    private static final String KEY_DELAY_MS = "custom_mapping_delay_ms_v1";

    public static final int DELAY_MIN_MS = 0;
    public static final int DELAY_MAX_MS = 1000;
    public static final int DEFAULT_DELAY_MS = 50;

    public static final class Output {
        public final int inputCode;
        public final int evdevCode;

        public Output(int inputCode, int evdevCode) {
            this.inputCode = inputCode;
            this.evdevCode = evdevCode;
        }
    }

    public static final class Rule {
        public final long id;
        public final int triggerInputCode;
        public final List<Output> outputs;

        Rule(long id, int triggerInputCode, List<Output> outputs) {
            this.id = id;
            this.triggerInputCode = triggerInputCode;
            this.outputs = List.copyOf(outputs);
        }
    }

    private CustomMappingStore() {}

    public static boolean isEnabled(Context context) {
        return AppPreferences.get(context).getBoolean(KEY_ENABLED, false) && hasRules(context);
    }

    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = AppPreferences.get(context);
        boolean next = enabled && hasRules(context);
        if (prefs.getBoolean(KEY_ENABLED, false) == next) return;
        prefs.edit().putBoolean(KEY_ENABLED, next).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static int getDelayMs(Context context) {
        return clampDelay(AppPreferences.get(context).getInt(KEY_DELAY_MS, DEFAULT_DELAY_MS));
    }

    public static void setDelayMs(Context context, int delayMs) {
        int value = clampDelay(delayMs);
        SharedPreferences prefs = AppPreferences.get(context);
        if (prefs.getInt(KEY_DELAY_MS, DEFAULT_DELAY_MS) == value) return;
        prefs.edit().putInt(KEY_DELAY_MS, value).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static List<Rule> load(Context context) {
        return decode(AppPreferences.get(context).getString(KEY_RULES, ""));
    }

    public static boolean hasRules(Context context) {
        return !load(context).isEmpty();
    }

    public static boolean usesTrigger(Context context, int inputCode) {
        if (!InputBinding.isValid(inputCode)) return false;
        for (Rule rule : load(context)) {
            if (rule.triggerInputCode == inputCode) return true;
        }
        return false;
    }

    /**
     * Stores one sequence for a trigger. A trigger is unique: recording it again edits that trigger
     * in place instead of creating two competing consuming rules.
     */
    public static void putRule(Context context, int triggerInputCode, List<Output> outputs) {
        if (!isInterceptableTrigger(triggerInputCode)) return;
        ArrayList<Output> cleanOutputs = sanitizeOutputs(outputs);
        if (cleanOutputs.isEmpty()) return;

        SharedPreferences prefs = AppPreferences.get(context);
        ArrayList<Rule> rules = new ArrayList<>(load(context));
        for (int i = 0; i < rules.size(); i++) {
            Rule current = rules.get(i);
            if (current.triggerInputCode == triggerInputCode) {
                rules.set(i, new Rule(current.id, triggerInputCode, cleanOutputs));
                write(prefs, rules);
                AxonInputAccessibilityService.refreshActiveService();
                return;
            }
        }
        rules.add(new Rule(nextId(rules), triggerInputCode, cleanOutputs));
        write(prefs, rules);
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static void removeRule(Context context, long id) {
        SharedPreferences prefs = AppPreferences.get(context);
        ArrayList<Rule> rules = new ArrayList<>(load(context));
        if (!rules.removeIf(rule -> rule.id == id)) return;
        write(prefs, rules);
        if (rules.isEmpty()) prefs.edit().putBoolean(KEY_ENABLED, false).apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static void clear(Context context) {
        AppPreferences.get(context).edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_RULES)
                .apply();
        AxonInputAccessibilityService.refreshActiveService();
    }

    public static boolean isInterceptableTrigger(int inputCode) {
        return InputBinding.isKeyboard(inputCode) || InputBinding.isGamepad(inputCode);
    }

    private static ArrayList<Rule> decode(String raw) {
        ArrayList<Rule> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;
        Set<Long> ids = new LinkedHashSet<>();
        Set<Integer> triggers = new LinkedHashSet<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                int trigger = object.optInt("trigger", -1);
                if (!isInterceptableTrigger(trigger) || !triggers.add(trigger)) continue;
                JSONArray outputArray = object.optJSONArray("outputs");
                ArrayList<Output> outputs = new ArrayList<>();
                if (outputArray != null) {
                    for (int j = 0; j < outputArray.length(); j++) {
                        JSONObject output = outputArray.optJSONObject(j);
                        if (output == null) continue;
                        int inputCode = output.optInt("input", -1);
                        int evdevCode = output.optInt("evdev", -1);
                        if (isValidOutput(inputCode, evdevCode)) outputs.add(new Output(inputCode, evdevCode));
                    }
                }
                if (outputs.isEmpty()) continue;
                long id = object.optLong("id", 0L);
                if (id <= 0L || ids.contains(id)) id = nextId(result);
                ids.add(id);
                result.add(new Rule(id, trigger, outputs));
            }
        } catch (Throwable ignored) {
            result.clear();
        }
        return result;
    }

    private static ArrayList<Output> sanitizeOutputs(List<Output> outputs) {
        ArrayList<Output> result = new ArrayList<>();
        if (outputs == null) return result;
        for (Output output : outputs) {
            if (output != null && isValidOutput(output.inputCode, output.evdevCode)) {
                // Do not de-duplicate: A,A,B is a valid ordered macro.
                result.add(new Output(output.inputCode, output.evdevCode));
            }
        }
        return result;
    }

    private static boolean isValidOutput(int inputCode, int evdevCode) {
        return InputBinding.isValid(inputCode) && evdevCode > 0;
    }

    private static void write(SharedPreferences prefs, List<Rule> rules) {
        JSONArray array = new JSONArray();
        for (Rule rule : rules) {
            if (rule == null || !isInterceptableTrigger(rule.triggerInputCode) || rule.outputs.isEmpty()) continue;
            try {
                JSONObject object = new JSONObject();
                object.put("id", rule.id);
                object.put("trigger", rule.triggerInputCode);
                JSONArray outputs = new JSONArray();
                for (Output output : rule.outputs) {
                    if (!isValidOutput(output.inputCode, output.evdevCode)) continue;
                    JSONObject encoded = new JSONObject();
                    encoded.put("input", output.inputCode);
                    encoded.put("evdev", output.evdevCode);
                    outputs.put(encoded);
                }
                if (outputs.length() == 0) continue;
                object.put("outputs", outputs);
                array.put(object);
            } catch (Throwable ignored) {
            }
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (array.length() == 0) editor.remove(KEY_RULES);
        else editor.putString(KEY_RULES, array.toString());
        editor.apply();
    }

    private static int clampDelay(int value) {
        return Math.max(DELAY_MIN_MS, Math.min(DELAY_MAX_MS, value));
    }

    private static long nextId(List<Rule> rules) {
        long max = 0L;
        for (Rule rule : rules) if (rule != null) max = Math.max(max, rule.id);
        long now = System.currentTimeMillis();
        return Math.max(max + 1L, now > 0L ? now : 1L);
    }
}
