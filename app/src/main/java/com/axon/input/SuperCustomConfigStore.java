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

    private static final int SCHEMA_VERSION = 7;
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
            if (spec == null) continue;
            if (spec.isStickElement()) return true;
            if (spec.isKeyElement() && InputBinding.isGamepad(spec.keyCode)) return true;
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
                item.put("controlId", spec.controlId);
                item.put("bindingGroupId", spec.bindingGroupId);
                item.put("bindingGroupIndex", spec.bindingGroupIndex);
                item.put("bindingAnchor", spec.bindingAnchor);
                item.put("keyCode", spec.keyCode);
                item.put("labelText", spec.labelText == null ? "" : spec.labelText);
                item.put("widthDp", spec.widthDp);
                item.put("heightDp", spec.heightDp);
                item.put("cornerDp", spec.cornerDp);
                item.put("opacityPercent", spec.opacityPercent);
                item.put("diffusionOpacityPercent", spec.diffusionOpacityPercent);
                item.put("backgroundOpacityPercent", spec.backgroundOpacityPercent);
                item.put("borderOpacityPercent", spec.borderOpacityPercent);
                item.put("textOpacityPercent", spec.textOpacityPercent);
                item.put("borderWidthDp", spec.borderWidthDp);
                item.put("baseColor", spec.baseColor);
                item.put("baseColors", spec.baseColors == null ? "" : spec.baseColors);
                item.put("pressColor", spec.pressColor);
                item.put("pressColors", spec.pressColors == null ? "" : spec.pressColors);
                item.put("borderColor", spec.borderColor);
                item.put("borderColors", spec.borderColors == null ? "" : spec.borderColors);
                item.put("textColor", spec.textColor);
                item.put("textColors", spec.textColors == null ? "" : spec.textColors);
                item.put("textSizeSp", spec.textSizeSp);
                item.put("motionMode", spec.motionMode);
                item.put("stickSide", spec.stickSide);
                item.put("stickDotSizePercent", spec.stickDotSizePercent);
                item.put("stickDotCornerPercent", spec.stickDotCornerPercent);
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
        if (version < 1 || version > SCHEMA_VERSION) {
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
                    && controlType != SuperCustomControlSpec.TYPE_TEXT
                    && controlType != SuperCustomControlSpec.TYPE_STICK) {
                throw new IllegalArgumentException("invalid control type");
            }

            int keyCode = item.optInt("keyCode", -1);
            if (controlType == SuperCustomControlSpec.TYPE_KEY && !InputBinding.isValid(keyCode)) {
                throw new IllegalArgumentException("invalid binding");
            }
            int stickSide = item.optInt("stickSide", SuperCustomControlSpec.STICK_LEFT);
            if (stickSide != SuperCustomControlSpec.STICK_LEFT
                    && stickSide != SuperCustomControlSpec.STICK_RIGHT) {
                stickSide = SuperCustomControlSpec.STICK_LEFT;
            }
            String fallbackLabel = controlType == SuperCustomControlSpec.TYPE_TEXT
                    ? "文本" : controlType == SuperCustomControlSpec.TYPE_STICK
                    ? (stickSide == SuperCustomControlSpec.STICK_RIGHT ? "右摇杆" : "左摇杆")
                    : InputBinding.label(keyCode);
            String label = item.optString("labelText", fallbackLabel);
            SuperCustomControlSpec spec = controlType == SuperCustomControlSpec.TYPE_TEXT
                    ? SuperCustomControlSpec.createText(false)
                    : controlType == SuperCustomControlSpec.TYPE_STICK
                    ? SuperCustomControlSpec.createStick(stickSide, false)
                    : new SuperCustomControlSpec(keyCode, label, false);
            spec.controlType = controlType;
            spec.controlId = version >= 7 ? item.optLong("controlId", 0L) : 0L;
            spec.bindingGroupId = version >= 7 ? item.optLong("bindingGroupId", 0L) : 0L;
            spec.bindingGroupIndex = version >= 7 ? item.optInt("bindingGroupIndex", 0) : 0;
            spec.bindingAnchor = version >= 7 && item.optBoolean("bindingAnchor", false);
            spec.keyCode = keyCode;
            spec.labelText = label;
            int maxWidthDp = controlType == SuperCustomControlSpec.TYPE_TEXT ? 560 : 420;
            spec.widthDp = clamp(item.optInt("widthDp", spec.widthDp), 28, maxWidthDp);
            spec.heightDp = clamp(item.optInt("heightDp", spec.heightDp), 24, 300);
            spec.cornerDp = clamp(item.optInt("cornerDp", spec.cornerDp), 0,
                    controlType == SuperCustomControlSpec.TYPE_STICK ? 100 : 80);
            spec.opacityPercent = clamp(item.optInt("opacityPercent", spec.opacityPercent), 0, 100);
            spec.diffusionOpacityPercent = clamp(
                    item.optInt("diffusionOpacityPercent", spec.diffusionOpacityPercent), 0, 100);
            spec.backgroundOpacityPercent = clamp(item.optInt("backgroundOpacityPercent", 100), 0, 100);
            spec.borderOpacityPercent = clamp(item.optInt("borderOpacityPercent", 100), 0, 100);
            spec.textOpacityPercent = clamp(item.optInt("textOpacityPercent", 100), 0, 100);
            spec.borderWidthDp = clamp(item.optInt("borderWidthDp", 1), 1, 8);
            spec.baseColor = item.optInt("baseColor", 0);
            spec.baseColors = item.optString("baseColors", "");
            spec.pressColor = item.optInt("pressColor", spec.pressColor);
            spec.pressColors = item.optString("pressColors", "");
            spec.borderColor = item.optInt("borderColor", spec.borderColor);
            spec.borderColors = item.optString("borderColors", "");
            spec.textColor = item.optInt("textColor", spec.textColor);
            spec.textColors = item.optString("textColors", "");
            spec.textSizeSp = clamp(item.optInt("textSizeSp", spec.textSizeSp), 8, 96);
            spec.motionMode = controlType == SuperCustomControlSpec.TYPE_STICK
                    ? OverlayState.MOTION_NONE
                    : OverlayState.clampMotionMode(item.optInt("motionMode", spec.motionMode));
            spec.stickSide = stickSide;
            spec.stickDotSizePercent = clamp(
                    item.optInt("stickDotSizePercent", spec.stickDotSizePercent), 12, 70);
            spec.stickDotCornerPercent = clamp(
                    item.optInt("stickDotCornerPercent", 100), 0, 100);
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
        sanitizeBindings(specs);
        return specs;
    }

    /**
     * 绑定信息属于编辑器关系数据。加载时集中修复旧配置、重复 ID、孤立组和多 Anchor，
     * 避免坏配置把拖动传播成环或留下不可恢复的幽灵成员。
     */
    private static void sanitizeBindings(List<SuperCustomControlSpec> specs) {
        if (specs == null || specs.isEmpty()) return;

        long nextControlId = 1L;
        for (SuperCustomControlSpec spec : specs) {
            if (spec != null && spec.controlId >= nextControlId) nextControlId = spec.controlId + 1L;
        }
        for (int i = 0; i < specs.size(); i++) {
            SuperCustomControlSpec spec = specs.get(i);
            if (spec == null) continue;
            boolean duplicate = spec.controlId <= 0L;
            if (!duplicate) {
                for (int j = 0; j < i; j++) {
                    SuperCustomControlSpec previous = specs.get(j);
                    if (previous != null && previous.controlId == spec.controlId) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (duplicate) spec.controlId = nextControlId++;
            if (spec.bindingGroupId <= 0L) clearBinding(spec);
        }

        // 每个有效组必须恰好有一个 Anchor 且至少两个控件。
        for (int i = 0; i < specs.size(); i++) {
            SuperCustomControlSpec seed = specs.get(i);
            if (seed == null || seed.bindingGroupId <= 0L) continue;
            long groupId = seed.bindingGroupId;
            boolean alreadyChecked = false;
            for (int j = 0; j < i; j++) {
                SuperCustomControlSpec previous = specs.get(j);
                if (previous != null && previous.bindingGroupId == groupId) {
                    alreadyChecked = true;
                    break;
                }
            }
            if (alreadyChecked) continue;

            int count = 0;
            int anchors = 0;
            int groupIndex = 0;
            for (SuperCustomControlSpec candidate : specs) {
                if (candidate == null || candidate.bindingGroupId != groupId) continue;
                count++;
                if (candidate.bindingAnchor) anchors++;
                if (groupIndex == 0 && candidate.bindingGroupIndex > 0) {
                    groupIndex = candidate.bindingGroupIndex;
                }
            }
            if (count < 2 || anchors != 1) {
                for (SuperCustomControlSpec candidate : specs) {
                    if (candidate != null && candidate.bindingGroupId == groupId) clearBinding(candidate);
                }
                continue;
            }
            if (groupIndex <= 0) groupIndex = nextFreeGroupIndex(specs, groupId);
            for (SuperCustomControlSpec candidate : specs) {
                if (candidate != null && candidate.bindingGroupId == groupId) {
                    candidate.bindingGroupIndex = groupIndex;
                }
            }
        }

        // 不同组不允许复用同一显示编号；发生冲突时只重编号后出现的组，不改稳定 groupId。
        for (int i = 0; i < specs.size(); i++) {
            SuperCustomControlSpec seed = specs.get(i);
            if (seed == null || seed.bindingGroupId <= 0L) continue;
            long groupId = seed.bindingGroupId;
            boolean firstOfGroup = true;
            for (int j = 0; j < i; j++) {
                SuperCustomControlSpec previous = specs.get(j);
                if (previous != null && previous.bindingGroupId == groupId) {
                    firstOfGroup = false;
                    break;
                }
            }
            if (!firstOfGroup) continue;
            int desired = seed.bindingGroupIndex;
            boolean conflict = false;
            for (int j = 0; j < i; j++) {
                SuperCustomControlSpec previous = specs.get(j);
                if (previous != null && previous.bindingGroupId > 0L
                        && previous.bindingGroupId != groupId
                        && previous.bindingGroupIndex == desired) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                int replacement = nextFreeGroupIndex(specs, groupId);
                for (SuperCustomControlSpec candidate : specs) {
                    if (candidate != null && candidate.bindingGroupId == groupId) {
                        candidate.bindingGroupIndex = replacement;
                    }
                }
            }
        }
    }

    private static int nextFreeGroupIndex(List<SuperCustomControlSpec> specs, long ignoreGroupId) {
        for (int index = 1; index <= 4096; index++) {
            boolean used = false;
            for (SuperCustomControlSpec spec : specs) {
                if (spec != null && spec.bindingGroupId > 0L
                        && spec.bindingGroupId != ignoreGroupId
                        && spec.bindingGroupIndex == index) {
                    used = true;
                    break;
                }
            }
            if (!used) return index;
        }
        return 4096;
    }

    private static void clearBinding(SuperCustomControlSpec spec) {
        if (spec == null) return;
        spec.bindingGroupId = 0L;
        spec.bindingGroupIndex = 0;
        spec.bindingAnchor = false;
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
