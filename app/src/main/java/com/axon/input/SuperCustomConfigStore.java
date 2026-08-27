package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Five persistent configuration slots for the super-custom display editor. */
final class SuperCustomConfigStore {
    static final int SLOT_COUNT = 5;
    static final int MAX_CONFIG_BYTES = 512 * 1024;

    private static final int SCHEMA_VERSION = 3;
    private static final String PREFS = "super_custom_configs";
    private static final String ACTIVE = "active_workspace";
    private static final String SLOT_PREFIX = "slot_";

    private SuperCustomConfigStore() {}

    static boolean hasSlot(Context context, int slot) {
        checkSlot(slot);
        return prefs(context).contains(SLOT_PREFIX + slot);
    }

    static void saveSlot(Context context, int slot, List<SuperCustomControlSpec> specs) throws Exception {
        checkSlot(slot);
        String json = encode(specs);
        prefs(context).edit().putString(SLOT_PREFIX + slot, json).apply();
    }

    static void saveActive(Context context, List<SuperCustomControlSpec> specs) {
        try {
            prefs(context).edit().putString(ACTIVE, encode(specs)).apply();
        } catch (Throwable ignored) {
        }
    }

    static List<SuperCustomControlSpec> loadActive(Context context) {
        String raw = prefs(context).getString(ACTIVE, null);
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        try {
            return decode(raw);
        } catch (Throwable ignored) {
            // Drop a corrupted active workspace once; otherwise every editor launch repeats the same failure.
            prefs(context).edit().remove(ACTIVE).apply();
            return new ArrayList<>();
        }
    }

    static boolean activeContainsMouse(Context context) {
        for (SuperCustomControlSpec spec : loadActive(context)) {
            if (spec != null && spec.isKeyElement() && InputBinding.isMouse(spec.keyCode)) return true;
        }
        return false;
    }

    static boolean activeContainsGamepad(Context context) {
        for (SuperCustomControlSpec spec : loadActive(context)) {
            if (spec != null && spec.isKeyElement() && InputBinding.isGamepad(spec.keyCode)) return true;
        }
        return false;
    }

    static boolean activeContainsKeyboard(Context context) {
        for (SuperCustomControlSpec spec : loadActive(context)) {
            if (spec != null && spec.isKeyElement() && InputBinding.isKeyboard(spec.keyCode)) return true;
        }
        return false;
    }

    static void loadSlotIntoActive(Context context, int slot) throws Exception {
        checkSlot(slot);
        String raw = prefs(context).getString(SLOT_PREFIX + slot, null);
        if (raw == null) throw new IllegalStateException("empty slot");
        // Validate before replacing the active workspace.
        decode(raw);
        prefs(context).edit().putString(ACTIVE, raw).apply();
    }

    static String exportSlot(Context context, int slot) throws Exception {
        checkSlot(slot);
        String raw = prefs(context).getString(SLOT_PREFIX + slot, null);
        if (raw == null) throw new IllegalStateException("empty slot");
        decode(raw);
        return raw;
    }

    static void importSlot(Context context, int slot, String raw) throws Exception {
        checkSlot(slot);
        if (raw == null) throw new IllegalArgumentException("empty config");
        if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("config too large");
        }
        List<SuperCustomControlSpec> specs = decode(raw);
        prefs(context).edit().putString(SLOT_PREFIX + slot, encode(specs)).apply();
    }

    private static String encode(List<SuperCustomControlSpec> specs) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "AxonInput.SuperCustom");
        root.put("version", SCHEMA_VERSION);
        JSONArray controls = new JSONArray();
        if (specs != null) {
            for (SuperCustomControlSpec spec : specs) {
                if (spec == null) continue;
                JSONObject item = new JSONObject();
                item.put("controlType", spec.controlType);
                item.put("keyCode", spec.keyCode);
                item.put("labelText", spec.labelText == null ? "" : spec.labelText);
                item.put("widthDp", spec.widthDp);
                item.put("heightDp", spec.heightDp);
                item.put("cornerDp", spec.cornerDp);
                item.put("opacityPercent", spec.opacityPercent);
                item.put("diffusionOpacityPercent", spec.diffusionOpacityPercent);
                item.put("pressColor", spec.pressColor);
                item.put("pressColors", spec.pressColors == null ? "" : spec.pressColors);
                item.put("borderColor", spec.borderColor);
                item.put("borderColors", spec.borderColors == null ? "" : spec.borderColors);
                item.put("textColor", spec.textColor);
                item.put("textColors", spec.textColors == null ? "" : spec.textColors);
                item.put("textSizeSp", spec.textSizeSp);
                item.put("motionMode", spec.motionMode);
                item.put("cpsEnabled", spec.cpsEnabled);
                item.put("cpsTemplate", spec.cpsTemplate == null ? SuperCustomControlSpec.CPS_TEMPLATE : spec.cpsTemplate);
                item.put("textStrokeEnabled", spec.textStrokeEnabled);
                item.put("textStrokeColor", spec.textStrokeColor);
                item.put("textStrokeColors", spec.textStrokeColors == null ? "" : spec.textStrokeColors);
                item.put("textStrokeWidthDp", spec.textStrokeWidthDp);
                item.put("centerXPx", spec.centerXPx);
                item.put("centerYPx", spec.centerYPx);
                item.put("positionSet", spec.positionSet);
                controls.put(item);
            }
        }
        root.put("controls", controls);
        return root.toString(2);
    }

    private static List<SuperCustomControlSpec> decode(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        if (!"AxonInput.SuperCustom".equals(root.optString("format"))) {
            throw new IllegalArgumentException("unsupported config");
        }
        int version = root.optInt("version", -1);
        if (version != 1 && version != 2 && version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported version");
        }
        JSONArray controls = root.optJSONArray("controls");
        if (controls == null) throw new IllegalArgumentException("missing controls");
        if (controls.length() > 128) throw new IllegalArgumentException("too many controls");

        List<SuperCustomControlSpec> specs = new ArrayList<>();
        for (int i = 0; i < controls.length(); i++) {
            JSONObject item = controls.getJSONObject(i);
            int controlType = version >= 3
                    ? item.optInt("controlType", SuperCustomControlSpec.TYPE_KEY)
                    : SuperCustomControlSpec.TYPE_KEY;
            if (controlType != SuperCustomControlSpec.TYPE_KEY
                    && controlType != SuperCustomControlSpec.TYPE_TEXT) {
                throw new IllegalArgumentException("invalid control type");
            }

            int keyCode = item.optInt("keyCode", -1);
            if (controlType == SuperCustomControlSpec.TYPE_KEY && !InputBinding.isValid(keyCode)) {
                throw new IllegalArgumentException("invalid binding");
            }
            String fallbackLabel = controlType == SuperCustomControlSpec.TYPE_TEXT
                    ? "文本" : InputBinding.label(keyCode);
            String label = item.optString("labelText", fallbackLabel);
            SuperCustomControlSpec spec = controlType == SuperCustomControlSpec.TYPE_TEXT
                    ? SuperCustomControlSpec.createText(false)
                    : new SuperCustomControlSpec(keyCode, label, false);
            spec.controlType = controlType;
            spec.keyCode = keyCode;
            spec.labelText = label;
            int maxWidthDp = controlType == SuperCustomControlSpec.TYPE_TEXT ? 560 : 420;
            spec.widthDp = clamp(item.optInt("widthDp", spec.widthDp), 28, maxWidthDp);
            spec.heightDp = clamp(item.optInt("heightDp", spec.heightDp), 24, 300);
            spec.cornerDp = clamp(item.optInt("cornerDp", spec.cornerDp), 0, 80);
            spec.opacityPercent = clamp(item.optInt("opacityPercent", spec.opacityPercent), 0, 100);
            spec.diffusionOpacityPercent = clamp(
                    item.optInt("diffusionOpacityPercent", spec.diffusionOpacityPercent), 0, 100);
            spec.pressColor = item.optInt("pressColor", spec.pressColor);
            spec.pressColors = item.optString("pressColors", "");
            spec.borderColor = item.optInt("borderColor", spec.borderColor);
            spec.borderColors = item.optString("borderColors", "");
            spec.textColor = item.optInt("textColor", spec.textColor);
            spec.textColors = item.optString("textColors", "");
            spec.textSizeSp = clamp(item.optInt("textSizeSp", spec.textSizeSp), 8, 96);
            spec.motionMode = OverlayState.clampMotionMode(item.optInt("motionMode", spec.motionMode));
            spec.cpsEnabled = controlType == SuperCustomControlSpec.TYPE_KEY
                    && item.optBoolean("cpsEnabled", false);
            spec.cpsTemplate = item.optString("cpsTemplate", SuperCustomControlSpec.CPS_TEMPLATE);
            if (spec.cpsEnabled && !SuperCustomControlSpec.isValidCpsTemplate(spec.cpsTemplate)) {
                spec.cpsTemplate = SuperCustomControlSpec.CPS_TEMPLATE;
            }
            spec.textStrokeEnabled = controlType == SuperCustomControlSpec.TYPE_TEXT
                    && item.optBoolean("textStrokeEnabled", false);
            spec.textStrokeColor = item.optInt("textStrokeColor", ColorFallback.strokeFor(spec.textColor));
            spec.textStrokeColors = item.optString("textStrokeColors", "");
            spec.textStrokeWidthDp = clamp(item.optInt("textStrokeWidthDp", 2), 1, 24);
            spec.centerXPx = item.optInt("centerXPx", -1);
            spec.centerYPx = item.optInt("centerYPx", -1);
            // v1 used negative coordinates as an implicit "unset" sentinel. v2+ stores an
            // explicit bit so real negative/off-screen coordinates remain valid.
            spec.positionSet = version >= 2
                    ? item.optBoolean("positionSet", false)
                    : (spec.centerXPx >= 0 && spec.centerYPx >= 0);
            specs.add(spec);
        }
        return specs;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void checkSlot(int slot) {
        if (slot < 1 || slot > SLOT_COUNT) throw new IllegalArgumentException("slot");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Keeps the store independent from UI theme classes while producing a readable outline fallback. */
    private static final class ColorFallback {
        static int strokeFor(int textColor) {
            int luminance = (android.graphics.Color.red(textColor) * 299
                    + android.graphics.Color.green(textColor) * 587
                    + android.graphics.Color.blue(textColor) * 114) / 1000;
            return luminance >= 128 ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
        }
    }
}
