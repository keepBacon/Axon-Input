package com.axon.input;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.net.Uri;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** BongoCat 自定义样式导入、校验、选择和删除。导入包永远作为数据读取，不执行其中的脚本。 */
public final class BongoCatStyleManager {
    public static final String BUILTIN_ID = "builtin";
    public static final String MODE_KEYBOARD = "keyboard";
    public static final String MODE_GAMEPAD = "gamepad";
    public static final String MODE_STANDARD = "standard";

    private static final String FORMAT_MODERN = "modern";
    private static final String FORMAT_MVER_016 = "mver016";
    private static final String RENDERER_LIVE2D = "live2d";
    private static final String RENDERER_SPRITE = "sprite";

    private static final String STYLE_DIR = "bongocat_styles";
    private static final String META_FILE = "axon_style.json";
    private static final String MVER_SOURCE_CONFIG_FILE = "mver_source_config.json";
    /** Axon-owned normalized package description shared by every imported keyboard-cat format. */
    private static final String NORMALIZED_MANIFEST_FILE = "axon_package_manifest.json";
    private static final int NORMALIZED_MANIFEST_VERSION = 2;
    /** Community keyboard-cat packs can be very large because MOC3, 4K/8K atlases and media are bundled. */
    private static final long MAX_IMPORT_ZIP_BYTES = 512L * 1024L * 1024L;
    /** Separate expanded-data ceiling still protects against ZIP bombs. */
    private static final long MAX_EXTRACTED_BYTES = 1536L * 1024L * 1024L;
    /** Allow a single large MOC3/atlas/media resource without ever loading it wholesale into Java heap. */
    private static final long MAX_SINGLE_FILE_BYTES = 512L * 1024L * 1024L;
    /** JSON/config files are parsed in-memory, so keep a much smaller heap-safe ceiling for text metadata. */
    private static final long MAX_TEXT_FILE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 5000;
    private static final long IMPORT_STORAGE_RESERVE_BYTES = 128L * 1024L * 1024L;
    /** Large community models must not be copied into a giant base64 JSON string. */
    private static final long MAX_INLINE_MOC_BYTES = 8L * 1024L * 1024L;
    /**
     * Standalone Live2D display must reach first frame before optional animation metadata.
     * Some VTube/Mver archives contain hundreds of motion/expression JSON files; parsing all of
     * them into one JSONObject can create 100+ MB transient strings and stall WebView startup.
     */
    private static final long LIVE2D_DISPLAY_SINGLE_JSON_BYTES = 4L * 1024L * 1024L;
    private static final long LIVE2D_DISPLAY_EXPRESSION_BUDGET_BYTES = 4L * 1024L * 1024L;
    private static final long LIVE2D_DISPLAY_MOTION_BUDGET_BYTES = 8L * 1024L * 1024L;
    private static final long LIVE2D_DISPLAY_AUX_BUDGET_BYTES = 4L * 1024L * 1024L;
    /** Imported keyboard-cat Live2D atlases are rendered inside Android WebView; keep them mobile-safe. */
    private static final int MAX_BONGOCAT_TEXTURE_EDGE = 4096;
    /** Keyboard-cat overlays are relatively small, so cap aggregate RGBA atlas pressure more aggressively. */
    private static final long MAX_BONGOCAT_TEXTURE_PIXELS = 24L * 1024L * 1024L;

    // Heavy model capability metadata is cached, but the small mode-defining resource set is
    // fingerprinted dynamically. This lets edited/repacked community styles self-heal from a
    // previously cached keyboard/gamepad classification without rescanning large textures.
    private static final Object CAPABILITY_CACHE_LOCK = new Object();
    private static final HashMap<String, List<ParameterOption>> PARAMETER_CACHE = new HashMap<>();
    private static final HashMap<String, List<ExpressionOption>> EXPRESSION_CACHE = new HashMap<>();
    private static final HashMap<String, List<ModelFunctionOption>> MOTION_CACHE = new HashMap<>();
    private static final HashMap<String, List<ModelFunctionOption>> FUNCTION_CACHE = new HashMap<>();
    private static final HashMap<String, CapabilityReport> REPORT_CACHE = new HashMap<>();
    private static final HashMap<String, List<PhysicsGroupOption>> PHYSICS_GROUP_CACHE = new HashMap<>();
    private static final HashMap<String, EffectiveModeCacheEntry> EFFECTIVE_MODE_CACHE = new HashMap<>();

    private static final class EffectiveModeCacheEntry {
        final long fingerprint;
        final String mode;

        EffectiveModeCacheEntry(long fingerprint, String mode) {
            this.fingerprint = fingerprint;
            this.mode = mode;
        }
    }

    private BongoCatStyleManager() {}

    public static final class StyleInfo {
        public final String id;
        public final String name;
        public final String mode;
        public final File root;
        public final String modelFile;
        public final boolean builtin;
        /** modern = 当前 BongoCat 资源包；mver016 = Bongo Cat Mver 0.1.6 可携版样式。 */
        public final String format;
        /** live2d 或 sprite。Mver 键盘/手柄模式通常是完整画布 PNG 叠层。 */
        public final String renderer;
        /** 样式原始合成画布尺寸；优先来自背景/静态猫画布。 */
        public final int designWidth;
        public final int designHeight;

        StyleInfo(String id, String name, String mode, File root, String modelFile,
                  boolean builtin, String format, String renderer, int designWidth, int designHeight) {
            this.id = id;
            this.name = name;
            this.mode = mode;
            this.root = root;
            this.modelFile = modelFile == null ? "" : modelFile;
            this.builtin = builtin;
            this.format = format == null || format.isEmpty() ? FORMAT_MODERN : format;
            this.renderer = renderer == null || renderer.isEmpty() ? RENDERER_LIVE2D : renderer;
            this.designWidth = Math.max(1, designWidth);
            this.designHeight = Math.max(1, designHeight);
        }

        public float aspectRatio() {
            return designWidth / (float) designHeight;
        }

        public String displayLabel() {
            if (builtin) return "Bongo Cat 原版";
            String resolvedMode = effectiveMode(this);
            String suffix = switch (resolvedMode) {
                case MODE_GAMEPAD -> "手柄";
                case MODE_STANDARD -> "标准";
                default -> "键盘";
            };
            return name + " · " + suffix;
        }
    }

    /** One model parameter discovered from Cubism DisplayInfo (cdi3). */
    public static final class ParameterOption {
        public final String id;
        public final String label;
        public final String group;
        public final String groupId;
        public final String category;
        public final String defaultTrigger;
        public final String keySemantic;

        ParameterOption(String id, String label, String group, String groupId, String category,
                        String defaultTrigger, String keySemantic) {
            this.id = id == null ? "" : id;
            this.label = label == null || label.isEmpty() ? this.id : label;
            this.group = group == null ? "" : group;
            this.groupId = groupId == null ? "" : groupId;
            this.category = category == null ? "advanced" : category;
            this.defaultTrigger = defaultTrigger == null ? "toggle" : defaultTrigger;
            this.keySemantic = keySemantic == null ? "" : keySemantic;
        }

        public String token() { return "param:" + id; }
        public boolean isFunction() { return "function".equals(category); }
        public boolean isKeyInput() { return "key".equals(category); }
    }

    /** Unified imported-style action: parameter, expression, or motion. */
    public static final class ModelFunctionOption {
        public final String token;
        public final String label;
        public final String kind;
        public final String defaultTrigger;

        ModelFunctionOption(String token, String label, String kind, String defaultTrigger) {
            this.token = token;
            this.label = label;
            this.kind = kind;
            this.defaultTrigger = defaultTrigger;
        }
    }

    /** Small capability report used by the settings UI after importing a community style. */
    public static final class CapabilityReport {
        public final int parameterCount;
        public final int functionCount;
        public final int keyParameterCount;
        public final int expressionCount;
        public final int motionCount;
        public final int physicsSettingCount;
        public final boolean mouseX;
        public final boolean mouseY;
        public final boolean mouseLeft;
        public final boolean mouseRight;
        public final boolean eyeBlink;
        public final boolean breath;

        CapabilityReport(int parameterCount, int functionCount, int keyParameterCount,
                         int expressionCount, int motionCount, int physicsSettingCount,
                         boolean mouseX, boolean mouseY, boolean mouseLeft, boolean mouseRight,
                         boolean eyeBlink, boolean breath) {
            this.parameterCount = parameterCount;
            this.functionCount = functionCount;
            this.keyParameterCount = keyParameterCount;
            this.expressionCount = expressionCount;
            this.motionCount = motionCount;
            this.physicsSettingCount = physicsSettingCount;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
            this.mouseLeft = mouseLeft;
            this.mouseRight = mouseRight;
            this.eyeBlink = eyeBlink;
            this.breath = breath;
        }
    }

    /** One Cubism Physics setting that can be enabled/disabled and scaled independently. */
    public static final class PhysicsGroupOption {
        public final String key;
        public final String label;
        public final int index;

        PhysicsGroupOption(String key, String label, int index) {
            this.key = key == null ? "" : key;
            this.label = label == null || label.isEmpty() ? this.key : label;
            this.index = index;
        }
    }

    /** One debug-selectable expression authored by an imported style. */
    public static final class ExpressionOption {
        public final String token;
        public final String label;
        public final String kind;
        public final int index;

        ExpressionOption(String token, String label, String kind, int index) {
            this.token = token;
            this.label = label;
            this.kind = kind;
            this.index = index;
        }
    }

    /**
     * Enumerates expressions from the selected imported style itself. Live2D entries keep the
     * exact FileReferences.Expressions index so the debug selector addresses the same slot as Mver.
     * PNG face overlays are exposed separately because many sprite-only Mver styles have no Live2D.
     */
    public static List<ExpressionOption> expressionOptions(Context context, String styleId) {
        List<ExpressionOption> result = new ArrayList<>();
        StyleInfo info = get(context, styleId);
        if (info == null || info.builtin || info.root == null) return result;
        synchronized (CAPABILITY_CACHE_LOCK) {
            List<ExpressionOption> cached = EXPRESSION_CACHE.get(info.id);
            if (cached != null) return new ArrayList<>(cached);
        }

        // Live2D expressions declared by the selected model3 manifest.
        if (info.modelFile != null && !info.modelFile.isEmpty()) {
            try {
                File modelFile = resolveRelativeIgnoreCase(info.root, info.modelFile);
                if (modelFile != null && modelFile.isFile()) {
                    JSONObject model = parseMverJson(readText(modelFile));
                    JSONObject refs = objectIgnoreCase(model, "FileReferences", "fileReferences");
                    JSONArray expressions = refs == null ? null : jsonArray(refs, "Expressions", "expressions");
                    File modelRoot = modelFile.getParentFile();
                    if (expressions != null && modelRoot != null) {
                        for (int i = 0; i < expressions.length(); i++) {
                            Object raw = expressions.opt(i);
                            if (!(raw instanceof JSONObject ref)) continue;
                            String relative = jsonString(ref, "", "File", "file").trim();
                            File expressionFile = relative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, relative);
                            if (expressionFile == null || !expressionFile.isFile()) continue;
                            String name = jsonString(ref, "", "Name", "name").trim();
                            if (name.isEmpty()) name = expressionFile.getName().replaceFirst("(?i)\\.exp3\\.json$", "");
                            result.add(new ExpressionOption("live2d:" + i, "Live2D · " + name, "live2d", i));
                        }
                    }
                }
            } catch (Exception ignored) {
                // Keep PNG expressions usable even when a community model3 contains bad optional refs.
            }
        }

        // Mver face overlays. Preserve numeric file indices because config bindings reference them.
        File faceDir = dirIgnoreCase(info.root, "face");
        if (faceDir.isDirectory()) {
            File[] files = faceDir.listFiles(File::isFile);
            if (files != null) {
                List<Integer> indices = new ArrayList<>();
                for (File file : files) {
                    String lower = file.getName().toLowerCase(Locale.ROOT);
                    if (!(lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) continue;
                    int dot = file.getName().indexOf('.');
                    String stem = dot < 0 ? file.getName() : file.getName().substring(0, dot);
                    try {
                        int index = Integer.parseInt(stem);
                        if (!indices.contains(index)) indices.add(index);
                    } catch (NumberFormatException ignored) {}
                }
                indices.sort(Integer::compareTo);
                for (int index : indices) {
                    result.add(new ExpressionOption("face:" + index, "贴图表情 · " + (index + 1), "face", index));
                }
            }
        }
        synchronized (CAPABILITY_CACHE_LOCK) {
            EXPRESSION_CACHE.put(info.id, new ArrayList<>(result));
        }
        return result;
    }

    /** Parameters visible in the model's cdi3 DisplayInfo. */
    public static List<ParameterOption> parameterOptions(Context context, String styleId, boolean includeAdvanced) {
        List<ParameterOption> all;
        StyleInfo info = get(context, styleId);
        if (info == null || info.builtin || info.root == null || info.modelFile == null || info.modelFile.isEmpty()) {
            return new ArrayList<>();
        }
        synchronized (CAPABILITY_CACHE_LOCK) {
            List<ParameterOption> cached = PARAMETER_CACHE.get(info.id);
            all = cached == null ? null : new ArrayList<>(cached);
        }
        if (all == null) {
            all = new ArrayList<>();
            try {
                File modelFile = resolveRelativeIgnoreCase(info.root, info.modelFile);
                if (modelFile == null || !modelFile.isFile()) return all;
                JSONObject model = parseMverJson(readText(modelFile));
                JSONArray definitions = live2dParameterDefinitions(modelFile.getParentFile(), model);
                for (int i = 0; i < definitions.length(); i++) {
                    JSONObject item = definitions.optJSONObject(i);
                    if (item != null) all.add(parameterOptionFromJson(item));
                }
                synchronized (CAPABILITY_CACHE_LOCK) {
                    PARAMETER_CACHE.put(info.id, new ArrayList<>(all));
                }
            } catch (Exception ignored) {}
        }
        if (includeAdvanced) return all;
        List<ParameterOption> functions = new ArrayList<>();
        for (ParameterOption option : all) if (option.isFunction()) functions.add(option);
        return functions;
    }

    /** Unified actions exposed by the imported style without constructing the heavy render config. */
    public static List<ModelFunctionOption> modelFunctionOptions(Context context, String styleId) {
        StyleInfo cachedInfo = get(context, styleId);
        if (cachedInfo != null && !cachedInfo.builtin) {
            synchronized (CAPABILITY_CACHE_LOCK) {
                List<ModelFunctionOption> cached = FUNCTION_CACHE.get(cachedInfo.id);
                if (cached != null) return new ArrayList<>(cached);
            }
        }
        List<ModelFunctionOption> result = new ArrayList<>();
        List<ParameterOption> functionParameters = parameterOptions(context, styleId, false);
        java.util.LinkedHashMap<String, List<ParameterOption>> groupedFunctions = new java.util.LinkedHashMap<>();
        for (ParameterOption parameter : functionParameters) {
            result.add(new ModelFunctionOption(parameter.token(), parameter.label, "parameter", parameter.defaultTrigger));
            String groupKey = parameter.groupId.isEmpty() ? parameter.group : parameter.groupId;
            if (!groupKey.isEmpty()) {
                groupedFunctions.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(parameter);
            }
        }
        // CDI ParameterGroups often describe one authored multi-stage feature (for example a
        // transformation). Expose a group sequence in addition to its individual parameters.
        for (Map.Entry<String, List<ParameterOption>> entry : groupedFunctions.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            ParameterOption first = entry.getValue().get(0);
            String label = first.group.isEmpty() ? entry.getKey() : first.group;
            result.add(new ModelFunctionOption("sequence:" + entry.getKey(),
                    label + " · 序列", "sequence", "sequence"));
        }

        StyleInfo info = get(context, styleId);
        if (info == null || info.builtin || info.root == null) return result;
        // ExpressionOption already preserves exact model3 indices. Face overlays are included too
        // so sprite-only Mver packages can expose authored emoticons from this same function page.
        for (ExpressionOption expression : expressionOptions(context, styleId)) {
            if ("live2d".equals(expression.kind)) {
                result.add(new ModelFunctionOption("expression:" + expression.index,
                        expression.label, "expression", "pulse"));
            } else if ("face".equals(expression.kind)) {
                result.add(new ModelFunctionOption("face:" + expression.index,
                        expression.label, "face", "pulse"));
            }
        }
        result.addAll(live2dMotionFunctionOptions(info));
        synchronized (CAPABILITY_CACHE_LOCK) {
            FUNCTION_CACHE.put(info.id, new ArrayList<>(result));
        }
        return result;
    }

    /** Enumerates motion tokens in exactly the same order appendLive2dConfig assigns runtime indices. */
    private static List<ModelFunctionOption> live2dMotionFunctionOptions(StyleInfo info) {
        List<ModelFunctionOption> result = new ArrayList<>();
        if (info == null || info.root == null || info.modelFile == null || info.modelFile.isEmpty()) return result;
        synchronized (CAPABILITY_CACHE_LOCK) {
            List<ModelFunctionOption> cached = MOTION_CACHE.get(info.id);
            if (cached != null) return new ArrayList<>(cached);
        }
        try {
            File modelFile = resolveRelativeIgnoreCase(info.root, info.modelFile);
            if (modelFile == null || !modelFile.isFile()) return result;
            File modelRoot = modelFile.getParentFile();
            if (modelRoot == null) return result;
            JSONObject model = parseMverJson(readText(modelFile));
            JSONObject refs = objectIgnoreCase(model, "FileReferences", "fileReferences");
            if (refs == null) return result;
            java.util.LinkedHashMap<String, String> ordered = new java.util.LinkedHashMap<>();
            JSONObject motionRefs = objectIgnoreCase(refs, "Motions", "motions");
            if (motionRefs != null) {
                for (String group : jsonKeys(motionRefs)) {
                    JSONArray items = motionRefs.optJSONArray(group);
                    if (items == null) continue;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject ref = items.optJSONObject(i);
                        String relative = ref == null ? "" : jsonString(ref, "", "File", "file").trim();
                        File file = relative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, relative);
                        if (file == null || !file.isFile()) continue;
                        requireInside(modelRoot, file);
                        ordered.putIfAbsent(file.getCanonicalPath(), group + "/" + i);
                    }
                }
            }
            List<File> loose = new ArrayList<>();
            collectFilesBySuffix(modelRoot, modelRoot, ".motion3.json", loose, 0);
            loose.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File file : loose) {
                requireInside(modelRoot, file);
                ordered.putIfAbsent(file.getCanonicalPath(), file.getName());
            }
            int index = 0;
            for (String name : ordered.values()) {
                result.add(new ModelFunctionOption("motion:" + index,
                        "动作 · " + name, "motion", "pulse"));
                index++;
            }
        } catch (Exception ignored) {}
        synchronized (CAPABILITY_CACHE_LOCK) {
            MOTION_CACHE.put(info.id, new ArrayList<>(result));
        }
        return result;
    }

    public static CapabilityReport capabilityReport(Context context, String styleId) {
        StyleInfo info = get(context, styleId);
        if (info == null || info.builtin || info.root == null) {
            return new CapabilityReport(0, 0, 0, 0, 0, 0, false, false, false, false, false, false);
        }
        synchronized (CAPABILITY_CACHE_LOCK) {
            CapabilityReport cached = REPORT_CACHE.get(info.id);
            if (cached != null) return cached;
        }
        try {
            // Reuse the already parsed CDI/parameter snapshot. Opening the function dialog asks for
            // functions and this report back-to-back; rescanning cdi3 here used to duplicate the
            // heaviest metadata walk on the main thread.
            List<ParameterOption> parameters = parameterOptions(context, styleId, true);
            int parameterCount = parameters.size();
            int functionCount = 0;
            int keyCount = 0;
            boolean mouseX = false, mouseY = false, mouseLeft = false, mouseRight = false, breath = false;
            for (ParameterOption item : parameters) {
                if (item.isFunction()) functionCount++;
                if (item.isKeyInput()) keyCount++;
                String id = item.id;
                String name = item.label.toLowerCase(Locale.ROOT);
                mouseX |= "ParamMouseX".equals(id) || name.equals("鼠标x") || name.contains("mouse x");
                mouseY |= "ParamMouseY".equals(id) || name.equals("鼠标y") || name.contains("mouse y");
                mouseLeft |= "ParamMouseLeftDown".equals(id) || name.contains("鼠标左键") || name.contains("mouse left");
                mouseRight |= "ParamMouseRightDown".equals(id) || "ParamMouseRihgtDown".equals(id)
                        || name.contains("鼠标右键") || name.contains("mouse right");
                breath |= "ParamBreath".equals(id) || name.contains("呼吸") || name.contains("breath");
            }

            JSONObject model = null;
            File modelRoot = null;
            JSONObject refs = null;
            if (info.modelFile != null && !info.modelFile.isEmpty()) {
                File modelFile = resolveRelativeIgnoreCase(info.root, info.modelFile);
                if (modelFile != null && modelFile.isFile()) {
                    modelRoot = modelFile.getParentFile();
                    model = parseMverJson(readText(modelFile));
                    refs = objectIgnoreCase(model, "FileReferences", "fileReferences");
                }
            }

            boolean eyeBlink = model != null && live2dGroupIds(model, "EyeBlink").length() > 0;
            int expressionCount = 0;
            for (ExpressionOption expression : expressionOptions(context, styleId)) {
                if ("live2d".equals(expression.kind)) expressionCount++;
            }
            int motionCount = live2dMotionFunctionOptions(info).size();
            int physicsCount = 0;
            if (refs != null && modelRoot != null) {
                String physicsRelative = jsonString(refs, "", "Physics", "physics").trim();
                File physicsFile = physicsRelative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, physicsRelative);
                if (physicsFile != null && physicsFile.isFile()) {
                    requireInside(modelRoot, physicsFile);
                    JSONObject physics = parseMverJson(readText(physicsFile));
                    JSONArray settings = jsonArray(physics, "PhysicsSettings", "physicsSettings");
                    physicsCount = settings == null ? 0 : settings.length();
                }
            }
            CapabilityReport report = new CapabilityReport(parameterCount, functionCount, keyCount,
                    expressionCount, motionCount, physicsCount,
                    mouseX, mouseY, mouseLeft, mouseRight, eyeBlink, breath);
            synchronized (CAPABILITY_CACHE_LOCK) { REPORT_CACHE.put(info.id, report); }
            return report;
        } catch (Exception ignored) {
            CapabilityReport report = new CapabilityReport(0, 0, 0, 0, 0, 0,
                    false, false, false, false, false, false);
            synchronized (CAPABILITY_CACHE_LOCK) { REPORT_CACHE.put(info.id, report); }
            return report;
        }
    }

    /** Enumerates Cubism PhysicsSettings with stable keys matching the WebView runtime. */
    public static List<PhysicsGroupOption> physicsGroups(Context context, String styleId) {
        StyleInfo info = get(context, styleId);
        return physicsGroups(info);
    }

    static List<PhysicsGroupOption> physicsGroups(StyleInfo info) {
        List<PhysicsGroupOption> result = new ArrayList<>();
        if (info == null || info.builtin || info.root == null || info.modelFile == null || info.modelFile.isEmpty()) {
            return result;
        }
        final boolean cacheable = !"live2d-current".equals(info.id);
        if (cacheable) {
            synchronized (CAPABILITY_CACHE_LOCK) {
                List<PhysicsGroupOption> cached = PHYSICS_GROUP_CACHE.get(info.id);
                if (cached != null) return new ArrayList<>(cached);
            }
        }
        try {
            File modelFile = resolveRelativeIgnoreCase(info.root, info.modelFile);
            if (modelFile == null || !modelFile.isFile()) return result;
            File modelRoot = modelFile.getParentFile();
            if (modelRoot == null) return result;
            JSONObject model = parseMverJson(readText(modelFile));
            JSONObject refs = objectIgnoreCase(model, "FileReferences", "fileReferences");
            if (refs == null) return result;
            String physicsRelative = jsonString(refs, "", "Physics", "physics").trim();
            File physicsFile = physicsRelative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, physicsRelative);
            if (physicsFile == null || !physicsFile.isFile()) return result;
            requireInside(modelRoot, physicsFile);
            JSONObject physics = parseMverJson(readText(physicsFile));
            JSONArray settings = jsonArray(physics, "PhysicsSettings", "physicsSettings");
            if (settings == null) return result;

            // CDI names make otherwise generic PhysicsSetting1/2/... entries understandable.
            HashMap<String, String> parameterNames = new HashMap<>();
            String displayRelative = jsonString(refs, "", "DisplayInfo", "displayInfo").trim();
            File displayFile = displayRelative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, displayRelative);
            if (displayFile != null && displayFile.isFile()) {
                try {
                    requireInside(modelRoot, displayFile);
                    JSONObject cdi = parseMverJson(readText(displayFile));
                    JSONArray parameters = jsonArray(cdi, "Parameters", "parameters");
                    if (parameters != null) {
                        for (int i = 0; i < parameters.length(); i++) {
                            JSONObject parameter = parameters.optJSONObject(i);
                            if (parameter == null) continue;
                            String id = jsonString(parameter, "", "Id", "id").trim();
                            String name = jsonString(parameter, "", "Name", "name").trim();
                            if (!id.isEmpty() && !name.isEmpty()) parameterNames.put(id, name);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            java.util.HashSet<String> seenKeys = new java.util.HashSet<>();
            for (int i = 0; i < settings.length(); i++) {
                JSONObject setting = settings.optJSONObject(i);
                if (setting == null) continue;
                String id = jsonString(setting, "", "Id", "id").trim();
                String authoredName = jsonString(setting, "", "Name", "name").trim();
                String key = !id.isEmpty() ? id : (!authoredName.isEmpty() ? authoredName : "physics-" + i);
                if (!seenKeys.add(key)) key = key + "#" + i;

                String label = authoredName;
                if (label.isEmpty()) {
                    java.util.LinkedHashSet<String> destinationNames = new java.util.LinkedHashSet<>();
                    JSONArray outputs = jsonArray(setting, "Output", "output");
                    if (outputs != null) {
                        for (int j = 0; j < outputs.length() && destinationNames.size() < 2; j++) {
                            JSONObject output = outputs.optJSONObject(j);
                            JSONObject destination = output == null ? null : objectIgnoreCase(output, "Destination", "destination");
                            String parameterId = destination == null ? "" : jsonString(destination, "", "Id", "id").trim();
                            if (parameterId.isEmpty()) continue;
                            String name = parameterNames.get(parameterId);
                            destinationNames.add(name == null || name.isEmpty() ? parameterId : name);
                        }
                    }
                    if (!destinationNames.isEmpty()) {
                        StringBuilder joined = new StringBuilder();
                        for (String destinationName : destinationNames) {
                            if (joined.length() > 0) joined.append(" / ");
                            joined.append(destinationName);
                        }
                        label = joined.toString();
                    }
                }
                if (label.isEmpty()) label = key;
                if (!id.isEmpty() && !label.equals(id)) label = label + "  ·  " + id;
                result.add(new PhysicsGroupOption(key, label, i));
            }
        } catch (Throwable ignored) {}
        if (cacheable) {
            synchronized (CAPABILITY_CACHE_LOCK) {
                PHYSICS_GROUP_CACHE.put(info.id, new ArrayList<>(result));
            }
        }
        return result;
    }

    private static ParameterOption parameterOptionFromJson(JSONObject item) {
        return new ParameterOption(item.optString("id", ""), item.optString("name", ""),
                item.optString("group", ""), item.optString("groupId", ""),
                item.optString("category", "advanced"), item.optString("defaultTrigger", "toggle"),
                item.optString("keySemantic", ""));
    }

    public static List<StyleInfo> list(Context context) {
        List<StyleInfo> result = new ArrayList<>();
        result.add(new StyleInfo(BUILTIN_ID, "Bongo Cat 原版", MODE_KEYBOARD, null, null, true, FORMAT_MODERN, RENDERER_LIVE2D, 612, 354));
        File base = baseDir(context);
        File[] dirs = base.listFiles(File::isDirectory);
        if (dirs == null) return result;
        Arrays.sort(dirs, Comparator.comparing(File::getName));
        for (File dir : dirs) {
            StyleInfo info = readInfo(dir);
            if (isVisibleStyle(info)) result.add(info);
        }
        if (result.size() > 2) {
            result.subList(1, result.size()).sort(
                    Comparator.comparing((StyleInfo info) -> info.name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparingInt(info -> modeOrder(info.mode))
                            .thenComparing(info -> info.id));
        }
        return result;
    }

    public static StyleInfo get(Context context, String id) {
        if (id == null || id.isEmpty() || BUILTIN_ID.equals(id)) {
            return new StyleInfo(BUILTIN_ID, "Bongo Cat 原版", MODE_KEYBOARD, null, null, true, FORMAT_MODERN, RENDERER_LIVE2D, 612, 354);
        }
        File dir = new File(baseDir(context), id);
        StyleInfo info = readInfo(dir);
        return isVisibleStyle(info) ? info : new StyleInfo(BUILTIN_ID, "Bongo Cat 原版", MODE_KEYBOARD, null, null, true, FORMAT_MODERN, RENDERER_LIVE2D, 612, 354);
    }


    private static boolean isVisibleStyle(StyleInfo info) {
        if (info == null) return false;
        return !FORMAT_MVER_016.equals(info.format) || MODE_STANDARD.equals(info.mode);
    }

    public static StyleInfo selected(Context context) {
        return get(context, OverlayState.getKeyboardCatStyleId(context));
    }

    public static boolean isSelectedGamepad(Context context) {
        return MODE_GAMEPAD.equals(effectiveMode(selected(context)));
    }

    /**
     * Whether the selected style is capable of consuming controller input. This is deliberately
     * separate from effectiveMode(): hybrid community models often expose CatParamStick* and a
     * model-function controller switch while their default visual layout is still a keyboard.
     * Treating capability as the style mode made those packs permanently appear as gamepad packs.
     */
    public static boolean selectedSupportsGamepad(Context context) {
        return supportsGamepadInput(selected(context));
    }

    public static boolean supportsGamepadInput(StyleInfo info) {
        if (info == null || info.builtin) return false;
        if (MODE_GAMEPAD.equals(effectiveMode(info))) return true;
        if (FORMAT_MVER_016.equals(info.format) || info.root == null) return false;
        try {
            return modelHasGamepadParameterHints(info) || hasUnambiguousGamepadAssets(info);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Resolve the actual visual/input layout from the files that exist now instead of trusting the
     * mode captured at import time. A/B/X/Y alone are ordinary keyboard keys and must never pin a
     * modified package to gamepad mode. CatParamStick* means "controller capable", not "gamepad
     * layout". This distinction lets users remove controller companion files from a large pack and
     * have it immediately behave as a keyboard style without re-authoring the Cubism model.
     */
    public static String effectiveMode(StyleInfo info) {
        if (info == null) return MODE_KEYBOARD;
        if (info.builtin || FORMAT_MVER_016.equals(info.format) || info.root == null) return info.mode;

        long fingerprint = styleModeFingerprint(info);
        synchronized (CAPABILITY_CACHE_LOCK) {
            EffectiveModeCacheEntry cached = EFFECTIVE_MODE_CACHE.get(info.id);
            if (cached != null && cached.fingerprint == fingerprint) return cached.mode;
        }

        String resolved;
        try {
            File resources = dirIgnoreCase(info.root, "resources");
            if (resources == null || !resources.isDirectory()) resources = info.root;
            List<String> left = keyNames(dirIgnoreCase(resources, "left-keys"));
            List<String> right = keyNames(dirIgnoreCase(resources, "right-keys"));
            String detected = detectMode(left, right);

            if (MODE_GAMEPAD.equals(detected)) {
                resolved = MODE_GAMEPAD;
            } else if (MODE_KEYBOARD.equals(detected)) {
                resolved = MODE_KEYBOARD;
            } else if (modelHasKeyboardParameterHints(info)) {
                // Parameter-only and hybrid models frequently have no right-keys directory. If
                // authored keyboard parameters exist, keyboard is the safer default layout.
                resolved = MODE_KEYBOARD;
            } else if (modelHasGamepadParameterHints(info)) {
                // Only use model hints as a last resort when the model is controller-only.
                resolved = MODE_GAMEPAD;
            } else {
                // Never preserve a stale imported GAMEPAD label after its controller files have
                // been deleted. Standard is the neutral fallback when no current evidence remains.
                resolved = MODE_GAMEPAD.equals(info.mode) ? MODE_STANDARD : info.mode;
            }
        } catch (Throwable ignored) {
            resolved = MODE_GAMEPAD.equals(info.mode) ? MODE_STANDARD : info.mode;
        }

        synchronized (CAPABILITY_CACHE_LOCK) {
            EFFECTIVE_MODE_CACHE.put(info.id, new EffectiveModeCacheEntry(fingerprint, resolved));
        }
        return resolved;
    }

    /** Only fingerprints small mode-defining metadata; 100-500 MB textures/MOC files are ignored. */
    private static long styleModeFingerprint(StyleInfo info) {
        if (info == null || info.root == null) return 0L;
        long hash = 0xcbf29ce484222325L;
        try {
            File resources = dirIgnoreCase(info.root, "resources");
            if (resources == null || !resources.isDirectory()) resources = info.root;
            File leftDir = dirIgnoreCase(resources, "left-keys");
            File rightDir = dirIgnoreCase(resources, "right-keys");
            for (String name : keyNames(leftDir)) hash = mixModeFingerprint(hash, "L:" + normalizeAssetToken(name));
            for (String name : keyNames(rightDir)) hash = mixModeFingerprint(hash, "R:" + normalizeAssetToken(name));
            if (leftDir != null) hash ^= leftDir.lastModified();
            if (rightDir != null) hash ^= Long.rotateLeft(rightDir.lastModified(), 13);
            File model = info.modelFile == null || info.modelFile.isEmpty() ? null : new File(info.root, info.modelFile);
            if (model != null && model.isFile()) {
                hash ^= Long.rotateLeft(model.lastModified(), 27);
                hash ^= model.length();
                try {
                    JSONObject modelJson = parseMverJson(readText(model));
                    JSONObject refs = objectIgnoreCase(modelJson, "FileReferences", "fileReferences");
                    String displayRelative = refs == null ? "" : jsonString(refs, "", "DisplayInfo", "displayInfo").trim();
                    File display = displayRelative.isEmpty() ? null : resolveRelativeIgnoreCase(model.getParentFile(), displayRelative);
                    if (display != null && display.isFile()) {
                        hash ^= Long.rotateLeft(display.lastModified(), 39);
                        hash ^= Long.rotateLeft(display.length(), 7);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return hash;
    }

    private static long mixModeFingerprint(long hash, String value) {
        String safe = value == null ? "" : value;
        for (int i = 0; i < safe.length(); i++) {
            hash ^= safe.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static boolean hasUnambiguousGamepadAssets(StyleInfo info) {
        if (info == null || info.root == null) return false;
        File resources = dirIgnoreCase(info.root, "resources");
        if (resources == null || !resources.isDirectory()) resources = info.root;
        List<String> all = new ArrayList<>();
        all.addAll(keyNames(dirIgnoreCase(resources, "left-keys")));
        all.addAll(keyNames(dirIgnoreCase(resources, "right-keys")));
        for (String raw : all) {
            String semantic = canonicalGamepadSemantic(raw);
            if (semantic == null) continue;
            String token = normalizeAssetToken(raw);
            if (isUnambiguousGamepadAssetToken(token, semantic)) return true;
        }
        return false;
    }

    /** Lightweight CDI probe for controller packs that use only Live2D parameters and no PNG keys. */
    private static boolean modelHasGamepadParameterHints(StyleInfo info) {
        if (info == null || info.root == null || info.modelFile == null || info.modelFile.isEmpty()) return false;
        try {
            File modelFile = new File(info.root, info.modelFile);
            File modelRoot = modelFile.getParentFile();
            if (!modelFile.isFile() || modelRoot == null) return false;
            JSONObject model = parseMverJson(readText(modelFile));
            JSONObject refs = objectIgnoreCase(model, "FileReferences", "fileReferences");
            if (refs == null) return false;
            String displayRelative = jsonString(refs, "", "DisplayInfo", "displayInfo").trim();
            if (displayRelative.isEmpty()) return false;
            File displayFile = resolveRelativeIgnoreCase(modelRoot, displayRelative);
            if (displayFile == null || !displayFile.isFile()) return false;
            JSONObject display = parseMverJson(readText(displayFile));
            JSONObject groupNames = new JSONObject();
            JSONArray groups = jsonArray(display, "ParameterGroups", "parameterGroups");
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group == null) continue;
                    String id = jsonString(group, "", "Id", "id").trim();
                    String name = jsonString(group, "", "Name", "name").trim();
                    if (!id.isEmpty()) groupNames.put(id, name);
                }
            }
            JSONArray parameters = jsonArray(display, "Parameters", "parameters");
            if (parameters == null) return false;
            for (int i = 0; i < parameters.length(); i++) {
                JSONObject item = parameters.optJSONObject(i);
                if (item == null) continue;
                String id = jsonString(item, "", "Id", "id").trim();
                String name = jsonString(item, id, "Name", "name").trim();
                String groupId = jsonString(item, "", "GroupId", "groupId").trim();
                String group = groupNames.optString(groupId, groupId);
                String idLower = id.toLowerCase(Locale.ROOT);
                String groupLower = group.toLowerCase(Locale.ROOT);
                if (idLower.equals("catparamsticklx") || idLower.equals("catparamstickly")
                        || idLower.equals("catparamstickrx") || idLower.equals("catparamstickry")
                        || idLower.contains("gamepad") || idLower.contains("controller")
                        || idLower.contains("joystick") || idLower.contains("button") || idLower.contains("btn")
                        || groupLower.contains("手柄") || groupLower.contains("控制器")
                        || groupLower.contains("gamepad") || groupLower.contains("controller")
                        || groupLower.contains("joystick")) {
                    if (inferGamepadParameterSemantic(id, name, group) != null
                            || idLower.startsWith("catparamstick")) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Lightweight CDI probe that distinguishes a hybrid/controller-capable model from a true controller-only layout. */
    private static boolean modelHasKeyboardParameterHints(StyleInfo info) {
        if (info == null || info.root == null || info.modelFile == null || info.modelFile.isEmpty()) return false;
        try {
            File modelFile = new File(info.root, info.modelFile);
            File modelRoot = modelFile.getParentFile();
            if (!modelFile.isFile() || modelRoot == null) return false;
            JSONObject model = parseMverJson(readText(modelFile));
            JSONObject refs = objectIgnoreCase(model, "FileReferences", "fileReferences");
            if (refs == null) return false;
            String displayRelative = jsonString(refs, "", "DisplayInfo", "displayInfo").trim();
            if (displayRelative.isEmpty()) return false;
            File displayFile = resolveRelativeIgnoreCase(modelRoot, displayRelative);
            if (displayFile == null || !displayFile.isFile()) return false;
            JSONObject display = parseMverJson(readText(displayFile));
            JSONObject groupNames = new JSONObject();
            JSONArray groups = jsonArray(display, "ParameterGroups", "parameterGroups");
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group == null) continue;
                    String id = jsonString(group, "", "Id", "id").trim();
                    String name = jsonString(group, "", "Name", "name").trim();
                    if (!id.isEmpty()) groupNames.put(id, name);
                }
            }
            JSONArray parameters = jsonArray(display, "Parameters", "parameters");
            if (parameters == null) return false;
            int keyboardHits = 0;
            for (int i = 0; i < parameters.length(); i++) {
                JSONObject item = parameters.optJSONObject(i);
                if (item == null) continue;
                String id = jsonString(item, "", "Id", "id").trim();
                String name = jsonString(item, id, "Name", "name").trim();
                String groupId = jsonString(item, "", "GroupId", "groupId").trim();
                String group = groupNames.optString(groupId, groupId);
                String semantic = inferKeySemantic(id, name, group);
                if (semantic == null || canonicalGamepadSemantic(semantic) != null) continue;
                keyboardHits++;
                // Two independent authored key parameters are enough to distinguish a keyboard or
                // hybrid model from a controller-only model while avoiding one accidental label.
                if (keyboardHits >= 2) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isMverStyle(StyleInfo info) {
        return info != null && !info.builtin && FORMAT_MVER_016.equals(info.format);
    }

    public static StyleInfo importZip(Context context, Uri uri, String displayName) throws Exception {
        File base = baseDir(context);
        if (!base.exists() && !base.mkdirs()) throw new IOException("Cannot create style directory");
        File temp = new File(base, ".import-" + UUID.randomUUID());
        if (!temp.mkdirs()) throw new IOException("Cannot create import directory");
        try {
            // ContentProvider 能给出文件长度时先快速拒绝；部分云盘 Provider 长度未知，
            // 后续仍由 LimitedInputStream 对真实读取字节数做 512 MiB 硬限制。
            try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd != null) {
                    long length = afd.getLength();
                    if (length > MAX_IMPORT_ZIP_BYTES) {
                        throw new IOException("样式压缩包不能超过 512 MB");
                    }
                }
            }
            // Materialize the archive once so legacy Windows ZIP filename encodings can be retried
            // without reopening SAF/cloud streams. This is still bounded by the same 512 MiB limit.
            File archive = new File(temp, ".axon-source.zip");
            try (InputStream raw = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(archive)) {
                if (raw == null) throw new IOException("Cannot open style archive");
                LimitedInputStream limited = new LimitedInputStream(raw, MAX_IMPORT_ZIP_BYTES);
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = limited.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            File extracted = new File(temp, "payload");
            extractZipArchive(archive, extracted);
            // The archive is no longer needed after extraction. Releasing it early matters for
            // 100-500 MB packs because the normalized style is stored on the same internal volume.
            //noinspection ResultOfMethodCallIgnored
            archive.delete();

            // Bongo Cat Mver 分享包是完整可携版。Axon 只导入 standard 的逻辑资源，
            // 但 standard 内的键盘、手部、表情、声音、鼠标/数位板和 Live2D 资源会完整保留。
            File mverRoot = findMverRoot(extracted);
            if (mverRoot != null) {
                boolean clearQuality = OverlayState.getKeyboardCatRenderQuality(context) == OverlayState.RENDER_QUALITY_CLEAR;
                return importMverPackage(base, mverRoot, displayName, clearQuality);
            }
            boolean clearQuality = OverlayState.getKeyboardCatRenderQuality(context) == OverlayState.RENDER_QUALITY_CLEAR;
            return importModernPackage(base, extracted, displayName, clearQuality);
        } finally {
            deleteTree(temp);
        }
    }

    private static StyleInfo importModernPackage(File base, File temp, String displayName, boolean clearQuality) throws Exception {
        File model = findModelFile(temp);
        if (model == null) throw new IOException("未找到 .model3.json");
        File modelRoot = model.getParentFile();
        if (modelRoot == null) throw new IOException("无效样式目录");
        // Normalize standalone/community exports around the nearest package root that contains the
        // authored BongoCat resources. Some packs place model3 under live2d/model/ while keeping
        // resources/ beside that folder; copying only model.getParentFile() silently loses key art.
        File sourceRoot = findModernPackageRoot(temp, model);
        if (sourceRoot == null) sourceRoot = modelRoot;

        validateModel(sourceRoot, model);
        // Standalone Bongo Cat standard exports frequently ship 8K Live2D atlases. They work on
        // desktop GPUs but can exceed Android WebView's texture/memory budget and make the runtime
        // fall back to the built-in cat. Adapt only the temporary extracted copy; the user's ZIP is
        // never modified and UV coordinates remain valid because atlas dimensions scale uniformly.
        int adaptedTextures = adaptLargeLive2dTextures(sourceRoot, model, clearQuality);
        if (adaptedTextures > 0) validateModel(sourceRoot, model);

        File resources = dirIgnoreCase(sourceRoot, "resources");
        if (resources == null || !resources.isDirectory()) resources = sourceRoot;
        List<String> left = keyNames(dirIgnoreCase(resources, "left-keys"));
        List<String> right = keyNames(dirIgnoreCase(resources, "right-keys"));
        String mode = detectMode(left, right);
        String id = newStyleId();
        File target = new File(base, id);
        try {
            materializeStyleTree(sourceRoot, target);
            normalizeModernCompanionAssets(target);
            String cleanName = cleanDisplayName(displayName);
            JSONObject meta = new JSONObject();
            meta.put("id", id);
            meta.put("name", cleanName);
            meta.put("mode", mode);
            meta.put("format", FORMAT_MODERN);
            meta.put("renderer", RENDERER_LIVE2D);
            meta.put("modelFile", relativePath(sourceRoot, model));
            meta.put("adaptedTextures", adaptedTextures);
            int[] designSize = detectDesignSize(target);
            meta.put("designWidth", designSize[0]);
            meta.put("designHeight", designSize[1]);
            return finalizeImportedStyle(target, meta, "导入后校验失败");
        } catch (Exception error) {
            deleteTree(target);
            throw error;
        }
    }

    /**
     * Normalize legacy exports that place cover/background/key folders directly beside the model.
     * Existing authored files are copied, never moved, so model-relative references stay untouched.
     */
    private static void normalizeModernCompanionAssets(File target) throws IOException {
        if (target == null || !target.isDirectory()) return;
        File existing = dirIgnoreCase(target, "resources");
        if (existing != null && existing.isDirectory()) return;
        File background = assetImageIgnoreCase(target, "background");
        File cover = assetImageIgnoreCase(target, "cover");
        File left = dirIgnoreCase(target, "left-keys");
        File right = dirIgnoreCase(target, "right-keys");
        boolean hasLeft = left != null && left.isDirectory();
        boolean hasRight = right != null && right.isDirectory();
        if (background == null && cover == null && !hasLeft && !hasRight) return;
        File resources = new File(target, "resources");
        if (!resources.mkdirs() && !resources.isDirectory()) throw new IOException("无法规范化键盘猫 resources 目录");
        if (background != null) copyTree(background, new File(resources, background.getName()));
        if (cover != null) copyTree(cover, new File(resources, cover.getName()));
        if (hasLeft) copyTree(left, new File(resources, "left-keys"));
        if (hasRight) copyTree(right, new File(resources, "right-keys"));
    }

    /** Find the narrowest safe root that keeps both model and BongoCat companion resources. */
    private static File findModernPackageRoot(File extractedRoot, File modelFile) {
        if (modelFile == null) return extractedRoot;
        File modelRoot = modelFile.getParentFile();
        if (modelRoot == null) return extractedRoot;
        File current = modelRoot;
        File best = modelRoot;
        for (int depth = 0; depth < 5 && current != null && isInside(extractedRoot, current); depth++) {
            File resources = dirIgnoreCase(current, "resources");
            if (resources != null && resources.isDirectory()) {
                best = current;
                break;
            }
            // A few legacy exports omit the resources wrapper and put key/cover assets beside live2d.
            File leftKeys = dirIgnoreCase(current, "left-keys");
            File rightKeys = dirIgnoreCase(current, "right-keys");
            if ((leftKeys != null && leftKeys.isDirectory())
                    || (rightKeys != null && rightKeys.isDirectory())
                    || assetImageIgnoreCase(current, "cover") != null
                    || assetImageIgnoreCase(current, "background") != null) {
                best = current;
                break;
            }
            if (current.equals(extractedRoot)) break;
            current = current.getParentFile();
        }
        return isInside(extractedRoot, best) ? best : modelRoot;
    }

    /**
     * Mver 导入只保留 standard。一个分享包在 Axon 中始终对应一个样式，避免
     * keyboard/gamepad 分支产生重复条目与不同的合成行为。导入时只复制 img/standard，
     * EXE/DLL、其它模式资源和程序文件都不会进入应用样式目录。
     */
    private static StyleInfo importMverPackage(File base, File packageRoot, String displayName, boolean clearQuality) throws Exception {
        File configFile = firstExisting(childIgnoreCase(packageRoot, "config.json"),
                childIgnoreCase(packageRoot, "config.json5"), childIgnoreCase(packageRoot, "config.jsonc"));
        if (configFile == null || !configFile.isFile()) throw new IOException("Mver 包中未找到 config.json/config.json5");
        JSONObject config = parseMverJson(readText(configFile));
        File source = findMverStandardDir(packageRoot);
        if (source == null || !source.isDirectory()) throw new IOException("Mver 包中未找到 standard 资源");

        JSONObject modeConfig = objectIgnoreCase(config, MODE_STANDARD);
        if (modeConfig == null) modeConfig = new JSONObject();
        JSONObject decoration = objectIgnoreCase(config, "decoration", "decortation");
        if (decoration == null) decoration = new JSONObject();

        File sourceModel = findMverModelFile(source, modeConfig);
        // Community/repacked Mver configs sometimes use Live2D/live2d or string/number booleans.
        // Missing l2d still means "use the model if a valid one exists", matching Mver converters.
        boolean l2dEnabled = jsonBoolean(modeConfig, true, "l2d", "live2d", "useLive2d");
        boolean useLive2d = l2dEnabled && sourceModel != null;
        int adaptedTextures = 0;
        if (useLive2d) {
            validateModel(source, sourceModel);
            adaptedTextures = adaptLargeLive2dTextures(source, sourceModel, clearQuality);
            if (adaptedTextures > 0) validateModel(source, sourceModel);
        }

        String modelRelative = sourceModel == null ? "" : relativePath(source, sourceModel);
        int[] designSize = detectMverDesignSize(source, config);
        String id = newStyleId();
        File target = new File(base, id);
        try {
            materializeStyleTree(source, target);
            // Keep the original Mver config beside the normalized standard assets. GitHub's
            // l2dcat converter also preserves behavior metadata separately from the converted
            // images; doing the same lets future runtimes rebuild bindings without asking the
            // user to recover the original executable package.
            writeText(new File(target, MVER_SOURCE_CONFIG_FILE), config.toString());

            JSONObject meta = new JSONObject();
            meta.put("id", id);
            meta.put("name", cleanMverDisplayName(displayName, packageRoot.getName()));
            meta.put("mode", MODE_STANDARD);
            meta.put("format", FORMAT_MVER_016);
            meta.put("renderer", useLive2d ? RENDERER_LIVE2D : RENDERER_SPRITE);
            meta.put("modelFile", modelRelative);
            meta.put("adaptedTextures", adaptedTextures);
            meta.put("mverConfig", modeConfig);
            meta.put("mverDecoration", decoration);
            JSONObject workarea = objectIgnoreCase(config, "workarea", "workArea");
            meta.put("mverWorkarea", workarea == null ? new JSONObject() : workarea);
            meta.put("mverSourceMode", jsonInt(config, 1, "mode"));
            meta.put("designWidth", designSize[0]);
            meta.put("designHeight", designSize[1]);
            return finalizeImportedStyle(target, meta, "Mver standard 模式导入后校验失败");
        } catch (Exception error) {
            deleteTree(target);
            throw error;
        }
    }

    public static boolean delete(Context context, String id) {
        if (id == null || id.isEmpty() || BUILTIN_ID.equals(id)) return false;
        File dir = new File(baseDir(context), id);
        if (!isInside(baseDir(context), dir)) return false;
        deleteTree(dir);
        invalidateCapabilityCache(id);
        KeyboardCatFunctionBindingStore.clearStyle(context, id);
        String physicsTarget = Live2DPhysicsSettingsStore.keyboardCatTarget(id);
        Live2DPhysicsSettingsStore.clearTarget(context, physicsTarget);
        Live2DDebugSettingsStore.clearTarget(context, physicsTarget);
        Live2DPhysicsHotkeyStore.clearTarget(context, physicsTarget);
        if (id.equals(OverlayState.getKeyboardCatStyleId(context))) {
            OverlayState.setKeyboardCatStyleId(context, BUILTIN_ID);
        }
        return !dir.exists();
    }

    private static void invalidateCapabilityCache(String styleId) {
        if (styleId == null || styleId.isEmpty()) return;
        synchronized (CAPABILITY_CACHE_LOCK) {
            PARAMETER_CACHE.remove(styleId);
            EXPRESSION_CACHE.remove(styleId);
            MOTION_CACHE.remove(styleId);
            FUNCTION_CACHE.remove(styleId);
            REPORT_CACHE.remove(styleId);
            PHYSICS_GROUP_CACHE.remove(styleId);
            EFFECTIVE_MODE_CACHE.remove(styleId);
        }
    }

    /** 生成通用 WebView runtime 所需配置。 */
    public static JSONObject runtimeConfig(StyleInfo info) throws Exception {
        return runtimeConfig(info, false);
    }

    /**
     * Memory-bounded runtime config for the dedicated standalone Live2D display.
     *
     * Unlike keyboard-cat style rendering, standalone display does not need to eagerly scan every
     * loose Motion3 file just to draw the first frame. Declared expressions/motions remain
     * available while pathological metadata is bounded, and MOC/texture payloads stay file-backed.
     */
    public static JSONObject runtimeConfigLive2DDisplay(StyleInfo info) throws Exception {
        if (info == null || info.builtin || info.root == null) throw new IOException("Not an imported style");
        File modelFile = new File(info.root, info.modelFile);
        requireInside(info.root, modelFile);
        File modelRoot = modelFile.getParentFile();
        if (modelRoot == null) throw new IOException("无效 model3 路径");

        JSONObject config = baseRuntimeConfig(info);
        config.put("mode", MODE_STANDARD);
        config.put("format", FORMAT_MODERN);
        config.put("spriteMode", false);
        config.put("background", "");
        config.put("cover", "");
        config.put("leftKeys", new JSONArray());
        config.put("rightKeys", new JSONArray());
        config.put("leftKeyAssets", new JSONObject());
        config.put("rightKeyAssets", new JSONObject());
        appendLive2dConfig(config, modelRoot, modelFile, true);
        return config;
    }

    /**
     * forceSpriteFallback is used only as a recovery path for imported Mver packs when WebGL /
     * Cubism cannot initialize on a particular Android WebView/GPU. Modern packs have no complete
     * raster fallback and therefore keep their authored Live2D renderer.
     */
    public static JSONObject runtimeConfig(StyleInfo info, boolean forceSpriteFallback) throws Exception {
        if (info == null || info.builtin || info.root == null) throw new IOException("Not an imported style");
        if (FORMAT_MVER_016.equals(info.format)) return runtimeConfigMver(info, forceSpriteFallback);
        return runtimeConfigModern(info);
    }

    private static JSONObject runtimeConfigModern(StyleInfo info) throws Exception {
        File modelFile = new File(info.root, info.modelFile);
        requireInside(info.root, modelFile);
        File modelRoot = modelFile.getParentFile();
        if (modelRoot == null) throw new IOException("无效 model3 路径");

        File resources = dirIgnoreCase(info.root, "resources");
        File background = assetImageIgnoreCase(resources, "background");
        File cover = assetImageIgnoreCase(resources, "cover");
        File leftDir = dirIgnoreCase(resources, "left-keys");
        File rightDir = dirIgnoreCase(resources, "right-keys");
        List<String> left = keyNames(leftDir);
        List<String> right = keyNames(rightDir);
        JSONObject leftAssets = keyAssetMap(leftDir);
        JSONObject rightAssets = keyAssetMap(rightDir);
        String runtimeMode = effectiveMode(info);
        if (MODE_GAMEPAD.equals(runtimeMode)) {
            appendCanonicalGamepadAliases(left, leftAssets);
            appendCanonicalGamepadAliases(right, rightAssets);
        }

        JSONObject config = baseRuntimeConfig(info);
        // effectiveMode() repairs metadata written by older Axon versions so the Web runtime and
        // InputRuntimeConfig agree about whether this style consumes controller input.
        config.put("mode", runtimeMode);
        config.put("format", FORMAT_MODERN);
        config.put("spriteMode", false);
        config.put("background", fileUri(background));
        config.put("cover", fileUri(cover));
        config.put("leftKeys", new JSONArray(left));
        config.put("rightKeys", new JSONArray(right));
        // Do not assume lowercase directories or PNG-only key overlays. Community packs commonly
        // use Resources/Left-Keys and WEBP/JPEG assets; pass exact resolved URIs to the runtime.
        config.put("leftKeyAssets", leftAssets);
        config.put("rightKeyAssets", rightAssets);
        appendLive2dConfig(config, modelRoot, modelFile);
        return config;
    }

    private static JSONObject runtimeConfigMver(StyleInfo info, boolean forceSpriteFallback) throws Exception {
        if (!MODE_STANDARD.equals(info.mode)) throw new IOException("Axon 仅支持导入 Mver standard 模式");
        File metaFile = new File(info.root, META_FILE);
        JSONObject meta = new JSONObject(readText(metaFile));
        JSONObject sourceConfig = null;
        File sourceConfigFile = new File(info.root, MVER_SOURCE_CONFIG_FILE);
        if (sourceConfigFile.isFile()) {
            try { sourceConfig = parseMverJson(readText(sourceConfigFile)); }
            catch (Exception ignored) { sourceConfig = null; }
        }
        JSONObject modeConfig = sourceConfig == null ? null : objectIgnoreCase(sourceConfig, MODE_STANDARD);
        if (modeConfig == null) modeConfig = meta.optJSONObject("mverConfig");
        if (modeConfig == null) modeConfig = new JSONObject();
        JSONObject decoration = sourceConfig == null ? null : objectIgnoreCase(sourceConfig, "decoration", "decortation");
        if (decoration == null) decoration = meta.optJSONObject("mverDecoration");
        if (decoration == null) decoration = new JSONObject();
        JSONObject workarea = sourceConfig == null ? null : objectIgnoreCase(sourceConfig, "workarea", "workArea");
        if (workarea == null) workarea = meta.optJSONObject("mverWorkarea");
        if (workarea == null) workarea = new JSONObject();

        boolean standardMode = MODE_STANDARD.equals(info.mode);
        boolean useLive2d = !forceSpriteFallback && RENDERER_LIVE2D.equals(info.renderer)
                && info.modelFile != null && !info.modelFile.isEmpty();
        boolean useMouse;
        Object mouseFlag = valueIgnoreCase(modeConfig, "mouse", "is_mouse", "useMouse");
        if (mouseFlag != null && mouseFlag != JSONObject.NULL) {
            useMouse = coerceBoolean(mouseFlag, true);
        } else {
            // Older/community packs occasionally omit standard.mouse. Infer from authored device assets.
            useMouse = assetImageIgnoreCase(info.root, "mouse") != null
                    || assetImageIgnoreCase(info.root, "tablet") == null;
        }

        JSONObject config = baseRuntimeConfig(info);
        config.put("format", FORMAT_MVER_016);
        config.put("mver", true);
        config.put("useLive2d", useLive2d);
        config.put("spriteMode", !useLive2d);

        // Keep an explicit bg.png as the true back-most background. Standard mouse/tablet plates
        // are classified separately below because Mver packages use them as compositor layers.
        config.put("background", fileUri(assetImageIgnoreCase(info.root, "bg")));
        config.put("cover", fileUri(assetImageIgnoreCase(info.root, "cat")));

        JSONArray keyboardMatrix = mverBindingMatrix(modeConfig, "keyboard", "keys");
        JSONArray leftHandMatrix = mverBindingMatrix(modeConfig, "lefthand", "leftHand");
        JSONArray rightHandMatrix = mverBindingMatrix(modeConfig, "righthand", "rightHand");
        JSONArray handMatrix = mverBindingMatrix(modeConfig, "hand", "hands");
        JSONArray faceMatrix = mverBindingMatrix(modeConfig, "face", "emoticon", "emoticons");
        JSONArray keyBindings = mverSpriteBindings(
                dirIgnoreCase(info.root, "keyboard"), keyboardMatrix, info.mode, false);
        JSONArray leftHandBindings = mverSpriteBindings(
                dirIgnoreCase(info.root, "lefthand"), leftHandMatrix, info.mode, false);
        JSONArray rightHandBindings = mverSpriteBindings(
                dirIgnoreCase(info.root, "righthand"), rightHandMatrix, info.mode, false);
        JSONArray handBindings = mverSpriteBindings(
                dirIgnoreCase(info.root, "hand"), handMatrix, info.mode, false);
        JSONArray faceBindings = mverSpriteBindings(
                dirIgnoreCase(info.root, "face"), faceMatrix, info.mode, true);

        config.put("mverKeyBindings", keyBindings);
        config.put("mverLeftHandBindings", leftHandBindings);
        config.put("mverRightHandBindings", rightHandBindings);
        config.put("mverHandBindings", handBindings);
        config.put("mverFaceBindings", faceBindings);
        config.put("mverFaceAssets", mverIndexedAssets(dirIgnoreCase(info.root, "face")));

        // Retain the legacy single-key maps so styles imported by an older runtime keep working.
        JSONObject keySprites = mverSpriteMap(
                dirIgnoreCase(info.root, "keyboard"), keyboardMatrix, info.mode);
        JSONObject leftHandSprites = mverSpriteMap(
                dirIgnoreCase(info.root, "lefthand"), leftHandMatrix, info.mode);
        JSONObject rightHandSprites = mverSpriteMap(
                dirIgnoreCase(info.root, "righthand"), rightHandMatrix, info.mode);
        JSONObject handSprites = mverSpriteMap(
                dirIgnoreCase(info.root, "hand"), handMatrix, info.mode);
        config.put("mverKeySprites", keySprites);
        config.put("mverLeftHandSprites", leftHandSprites);
        config.put("mverRightHandSprites", rightHandSprites);
        config.put("mverHandSprites", handSprites);
        // Live2D-standard already contains the character, so its legacy white idle paw / pointer /
        // generated arm remain hidden. Sprite-only standard relies on those layers for the actual
        // animation, therefore restore them there. This keeps the user's Live2D preference while
        // remaining compatible with non-Live2D Mver standard packs.
        boolean renderLegacyPointer = !useLive2d;
        config.put("mverLeftIdle", renderLegacyPointer ? fileUri(assetImageIgnoreCase(info.root, "leftup")) : "");
        config.put("mverRightIdle", renderLegacyPointer ? fileUri(assetImageIgnoreCase(info.root, "rightup")) : "");
        config.put("mverUp", renderLegacyPointer ? fileUri(assetImageIgnoreCase(info.root, "up")) : "");
        config.put("mverRenderHandOverlays", true);
        // Mver standard packages normally author keyboard hands as full-canvas PNG layers. When
        // those layers exist they are the source of truth for keyboard-hand placement. Driving the
        // model's CatParam*HandDown parameters at the same time creates a second hand animation;
        // mirrored/swapped layouts can then send the pointer hand to the keyboard. Keep the model
        // hand parameters only as a fallback for Live2D packages that do not provide authored
        // full-frame hand layers.
        config.put("mverSpriteHandsAuthoritative",
                useLive2d && hasFullFrameMverHandOverlay(info.root, info.designWidth, info.designHeight));

        config.put("leftKeys", new JSONArray(jsonKeys(leftHandSprites)));
        config.put("rightKeys", new JSONArray(jsonKeys(rightHandSprites)));

        File mouseBase = assetImageIgnoreCase(info.root, useMouse ? "mouse" : "tablet");
        File mouseLeft = assetImageIgnoreCase(info.root, useMouse ? "mouse_left" : "tablet_left");
        File mouseRight = assetImageIgnoreCase(info.root, useMouse ? "mouse_right" : "tablet_right");
        File mouseSide = assetImageIgnoreCase(info.root, useMouse ? "mouse_side" : "tablet_side");
        config.put("mverRenderMouseOverlay", renderLegacyPointer);
        config.put("mverMouseIsMouse", useMouse);
        config.put("mverMouse", renderLegacyPointer ? fileUri(mouseBase) : "");
        config.put("mverMouseLeft", renderLegacyPointer ? fileUri(mouseLeft) : "");
        config.put("mverMouseRight", renderLegacyPointer ? fileUri(mouseRight) : "");
        config.put("mverMouseSide", renderLegacyPointer ? fileUri(mouseSide) : "");
        config.put("mverArm", renderLegacyPointer ? fileUri(assetImageIgnoreCase(info.root, "arm")) : "");
        JSONArray armLineColor = jsonArray(decoration, "armLineColor", "arm_line_color");
        config.put("mverArmLineColor", armLineColor == null ? new JSONArray() : armLineColor);
        // Background semantics are inferred from the authored Mver filename instead of a skin-specific z-index.
        // l2d*bg is explicitly authored for Mver's Live2D compositor and may contain foreground occlusion;
        // plain mousebg/tabletbg is the non-Live2D base and is safer behind the model when used as fallback.
        File l2dBase = assetImageIgnoreCase(info.root, useMouse ? "l2dmousebg" : "l2dtabletbg");
        File plainBase = assetImageIgnoreCase(info.root, useMouse ? "mousebg" : "tabletbg");
        File mverBaseBackground = useLive2d && l2dBase != null ? l2dBase
                : (plainBase != null ? plainBase : l2dBase);
        boolean authoredLive2dBase = useLive2d && l2dBase != null && mverBaseBackground == l2dBase;
        config.put("mverMouseBg", fileUri(mverBaseBackground));
        config.put("mverBaseLayerRole", authoredLive2dBase ? "foreground" : "background");
        config.put("mverBaseLayerZ", authoredLive2dBase ? 4 : 1);
        // Keep the current Android calibration as a fallback only for plain legacy raster bases.
        // Dedicated l2d*bg assets use their authored coordinates without this correction.
        double legacyRasterOffsetX = useLive2d && !authoredLive2dBase
                ? info.designWidth * (8.0 / 612.0) : 0.0;
        config.put("mverFullFrameOffsetX", legacyRasterOffsetX);
        config.put("mverFullFrameOffsetY", 0.0);

        JSONArray offsetX = jsonArray(decoration, "offsetX", "offset_x");
        JSONArray offsetY = jsonArray(decoration, "offsetY", "offset_y");
        JSONArray scalar = jsonArray(decoration, "scalar", "scale");
        JSONArray handOffset = jsonArray(modeConfig, "hand_offset", "handOffset");
        if (handOffset == null) handOffset = jsonArray(decoration, "hand_offset", "handOffset");
        config.put("mverMouseOffsetX", jsonArrayNumber(offsetX, useMouse ? 0 : 1, 0));
        config.put("mverMouseOffsetY", jsonArrayNumber(offsetY, useMouse ? 0 : 1, 0));
        config.put("mverMouseScale", jsonArrayNumber(scalar, useMouse ? 0 : 1, 1.0));
        config.put("mverHandOffsetX", jsonArrayNumber(handOffset, 0, 0));
        config.put("mverHandOffsetY", jsonArrayNumber(handOffset, 1, 0));
        config.put("mverLeftHanded", jsonBoolean(decoration, false, "leftHanded", "left_handed"));
        config.put("mverEmoticonKeep", jsonBoolean(decoration, false, "emoticonKeep", "emoticon_keep"));
        config.put("mverEmoticonClear", mverKeyCombo(mverKeyRow(valueIgnoreCase(decoration, "emoticonClear", "emoticon_clear")), MODE_STANDARD, true));
        config.put("mverSoundKeep", jsonBoolean(decoration, true, "soundKeep", "sound_keep"));
        config.put("mverSoundClear", mverKeyCombo(mverKeyRow(valueIgnoreCase(decoration, "soundClear", "sound_clear")), MODE_STANDARD, true));
        config.put("mverSoundBindings", mverMediaBindings(
                dirIgnoreCase(info.root, "sounds"), mverBindingMatrix(modeConfig, "sounds", "sound")));
        // Mver mouse_left/right/side arrays are OR key sets, not multi-key chords.
        // Keep the original 0.1.6 defaults when the fields are omitted.
        config.put("mverMouseLeftKeys", mverKeySet(mverKeyRow(valueIgnoreCase(modeConfig, "mouse_left", "mouseLeft")), 0x01));
        config.put("mverMouseRightKeys", mverKeySet(mverKeyRow(valueIgnoreCase(modeConfig, "mouse_right", "mouseRight")), 0x02));
        config.put("mverMouseSideKeys", mverKeySet(mverKeyRow(valueIgnoreCase(modeConfig, "mouse_side", "mouseSide")), 0x05, 0x06));
        config.put("mverMouseForceMove", jsonBoolean(decoration, false, "mouse_force_move", "mouseForceMove"));
        config.put("mverMouseSpeed", jsonDouble(decoration, 1.0, "mouse_speed", "mouseSpeed"));
        int fps = jsonInt(decoration, 60, "framerateLimit", "frameRateLimit", "fps", "frame_rate");
        config.put("mverFrameRateLimit", Math.max(1, Math.min(240, fps)));
        config.put("mverWorkareaEnabled", jsonBoolean(workarea, false, "workarea", "enabled"));
        JSONArray topLeft = jsonArray(workarea, "top_left", "topLeft");
        JSONArray rightBottom = jsonArray(workarea, "right_bottom", "rightBottom");
        config.put("mverWorkareaTopLeft", topLeft == null ? new JSONArray() : topLeft);
        config.put("mverWorkareaRightBottom", rightBottom == null ? new JSONArray() : rightBottom);
        config.put("mverCorrect", jsonDouble(decoration, 100.0, "correct"));
        config.put("mverL2dCorrect", jsonDouble(decoration, 1.0, "l2d_correct", "l2dCorrect"));
        JSONArray l2dOffset = jsonArray(decoration, "l2d_offset", "l2dOffset");
        config.put("mverL2dOffset", l2dOffset == null ? new JSONArray() : l2dOffset);
        // Preserve the legacy field for diagnostics/import round-tripping. Mver 0.1.6 standard
        // Live2D does not visually mirror the model from this flag; runtime.js intentionally
        // keeps the character in native orientation so asymmetric desk/hand layers stay aligned.
        config.put("mverL2dHorizontalFlip", jsonBoolean(decoration, false, "l2d_horizontal_flip", "l2dHorizontalFlip"));
        config.put("mverExpressionBindings", mverComboBindings(mverBindingMatrix(modeConfig, "l2d_expression", "l2dExpression"), true));
        config.put("mverMotionBindings", mverComboBindings(mverBindingMatrix(modeConfig, "l2d_motion", "l2dMotion"), true));
        config.put("mverMotionLockHandBindings", mverComboBindings(mverBindingMatrix(modeConfig, "l2d_motion_lockhand", "l2dMotionLockHand"), true));


        if (useLive2d) {
            File modelFile = new File(info.root, info.modelFile);
            requireInside(info.root, modelFile);
            File modelRoot = modelFile.getParentFile();
            if (modelRoot == null) throw new IOException("无效 Mver model3 路径");
            appendLive2dConfig(config, modelRoot, modelFile);
        } else {
            config.put("textures", new JSONArray());
            config.put("mocBase64", "");
            config.put("mocUri", "");
            config.put("mocVersion", 0);
            config.put("expressions", new JSONArray());
            config.put("motions", new JSONArray());
            config.put("motionGroups", new JSONObject());
            config.put("physics", JSONObject.NULL);
            config.put("pose", JSONObject.NULL);
            config.put("eyeBlinkIds", new JSONArray());
            config.put("lipSyncIds", new JSONArray());
            config.put("modelLayout", new JSONObject());
        }
        return config;
    }

    private static JSONObject baseRuntimeConfig(StyleInfo info) throws Exception {
        JSONObject config = new JSONObject();
        config.put("mode", info.mode);
        config.put("name", info.name);
        config.put("designWidth", info.designWidth);
        config.put("designHeight", info.designHeight);
        config.put("baseUri", ensureSlash(Uri.fromFile(info.root).toString()));
        appendRuntimeResourceProfile(config, info);
        return config;
    }

    /**
     * First-frame parameter discovery for standalone Live2D. Only model3 Groups plus a reasonably
     * sized DisplayInfo are read here. Physics/Expression/Motion files are already parsed later
     * when needed by the renderer; scanning them again merely to infer debug labels caused the
     * large transient allocations visible in device logs.
     */
    private static JSONArray live2dParameterDefinitionsFast(File modelRoot, JSONObject modelJson) throws Exception {
        JSONArray result = new JSONArray();
        if (modelRoot == null || modelJson == null) return result;
        java.util.HashSet<String> seenIds = new java.util.HashSet<>();
        JSONObject refs = objectIgnoreCase(modelJson, "FileReferences", "fileReferences");

        if (refs != null) {
            String displayRelative = jsonString(refs, "", "DisplayInfo", "displayInfo").trim();
            File displayFile = displayRelative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, displayRelative);
            if (displayFile != null && displayFile.isFile()
                    && displayFile.length() <= LIVE2D_DISPLAY_SINGLE_JSON_BYTES) {
                requireInside(modelRoot, displayFile);
                JSONObject display = parseMverJson(readText(displayFile));
                JSONObject groupNames = new JSONObject();
                JSONArray groups = jsonArray(display, "ParameterGroups", "parameterGroups");
                if (groups != null) {
                    for (int i = 0; i < groups.length(); i++) {
                        JSONObject group = groups.optJSONObject(i);
                        if (group == null) continue;
                        String id = jsonString(group, "", "Id", "id").trim();
                        String name = jsonString(group, "", "Name", "name").trim();
                        if (!id.isEmpty()) groupNames.put(id, name);
                    }
                }
                JSONArray parameters = jsonArray(display, "Parameters", "parameters");
                if (parameters != null) {
                    for (int i = 0; i < parameters.length(); i++) {
                        JSONObject raw = parameters.optJSONObject(i);
                        if (raw == null) continue;
                        String id = jsonString(raw, "", "Id", "id").trim();
                        if (id.isEmpty() || !seenIds.add(id)) continue;
                        String name = jsonString(raw, id, "Name", "name").trim();
                        String groupId = jsonString(raw, "", "GroupId", "groupId").trim();
                        String group = groupNames.optString(groupId, groupId);
                        String keySemantic = inferKeySemantic(id, name, group);
                        String category = classifyParameter(id, name, group, keySemantic);
                        JSONObject item = new JSONObject();
                        item.put("id", id);
                        item.put("name", name.isEmpty() ? id : name);
                        item.put("group", group);
                        item.put("groupId", groupId);
                        item.put("category", category);
                        item.put("defaultTrigger", inferTriggerMode(id, name, category));
                        item.put("keySemantic", keySemantic == null ? "" : keySemantic);
                        item.put("source", "cdi");
                        result.put(item);
                    }
                }
            }
        }

        // Cubism model groups are tiny and sufficient to retain EyeBlink/LipSync system parameters.
        JSONArray groups = jsonArray(modelJson, "Groups", "groups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null) continue;
                String target = jsonString(group, "", "Target", "target");
                if (!target.isEmpty() && !"Parameter".equalsIgnoreCase(target)) continue;
                String groupName = jsonString(group, "", "Name", "name");
                JSONArray ids = jsonArray(group, "Ids", "ids");
                if (ids == null) continue;
                for (int j = 0; j < ids.length(); j++) {
                    String id = String.valueOf(ids.opt(j)).trim();
                    appendSyntheticParameter(result, seenIds, id, groupName,
                            ("EyeBlink".equalsIgnoreCase(groupName) || "LipSync".equalsIgnoreCase(groupName))
                                    ? "system" : null);
                }
            }
        }
        return result;
    }

    private static JSONArray live2dParameterDefinitions(File modelRoot, JSONObject modelJson) throws Exception {
        JSONArray result = new JSONArray();
        if (modelRoot == null || modelJson == null) return result;
        java.util.HashSet<String> seenIds = new java.util.HashSet<>();
        JSONObject refs = objectIgnoreCase(modelJson, "FileReferences", "fileReferences");

        // Preferred source: Cubism DisplayInfo/CDI. It carries human-readable names/groups and is
        // what community keyboard-cat authors use to describe functions such as 耳机/翅膀/按键.
        if (refs != null) {
            String displayRelative = jsonString(refs, "", "DisplayInfo", "displayInfo").trim();
            File displayFile = displayRelative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, displayRelative);
            if (displayFile != null && displayFile.isFile()) {
                requireInside(modelRoot, displayFile);
                JSONObject display = parseMverJson(readText(displayFile));
                JSONObject groupNames = new JSONObject();
                JSONArray groups = jsonArray(display, "ParameterGroups", "parameterGroups");
                if (groups != null) {
                    for (int i = 0; i < groups.length(); i++) {
                        JSONObject group = groups.optJSONObject(i);
                        if (group == null) continue;
                        String id = jsonString(group, "", "Id", "id").trim();
                        String name = jsonString(group, "", "Name", "name").trim();
                        if (!id.isEmpty()) groupNames.put(id, name);
                    }
                }

                JSONArray parameters = jsonArray(display, "Parameters", "parameters");
                if (parameters != null) {
                    for (int i = 0; i < parameters.length(); i++) {
                        JSONObject raw = parameters.optJSONObject(i);
                        if (raw == null) continue;
                        String id = jsonString(raw, "", "Id", "id").trim();
                        if (id.isEmpty() || !seenIds.add(id)) continue;
                        String name = jsonString(raw, id, "Name", "name").trim();
                        String groupId = jsonString(raw, "", "GroupId", "groupId").trim();
                        String group = groupNames.optString(groupId, groupId);
                        String keySemantic = inferKeySemantic(id, name, group);
                        String category = classifyParameter(id, name, group, keySemantic);
                        JSONObject item = new JSONObject();
                        item.put("id", id);
                        item.put("name", name.isEmpty() ? id : name);
                        item.put("group", group);
                        item.put("groupId", groupId);
                        item.put("category", category);
                        item.put("defaultTrigger", inferTriggerMode(id, name, category));
                        item.put("keySemantic", keySemantic == null ? "" : keySemantic);
                        item.put("source", "cdi");
                        result.put(item);
                    }
                }
            }
        }

        // CDI is optional in Cubism. Do not make a missing/broken cdi3 turn an otherwise valid
        // community model into a featureless import. Recover every parameter reference we can
        // safely discover from model groups, Physics, Expressions and Motions. Unknown synthesized
        // IDs stay in Advanced rather than being guessed as user-facing toggles.
        appendReferencedParameterDefinitions(result, seenIds, modelRoot, modelJson, refs);
        return result;
    }

    private static void appendReferencedParameterDefinitions(JSONArray result,
                                                               java.util.Set<String> seenIds,
                                                               File modelRoot,
                                                               JSONObject modelJson,
                                                               JSONObject refs) throws Exception {
        JSONArray groups = jsonArray(modelJson, "Groups", "groups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null) continue;
                String target = jsonString(group, "", "Target", "target");
                if (!target.isEmpty() && !"Parameter".equalsIgnoreCase(target)) continue;
                String groupName = jsonString(group, "", "Name", "name");
                JSONArray ids = jsonArray(group, "Ids", "ids");
                if (ids == null) continue;
                for (int j = 0; j < ids.length(); j++) {
                    String id = String.valueOf(ids.opt(j)).trim();
                    appendSyntheticParameter(result, seenIds, id, groupName,
                            ("EyeBlink".equalsIgnoreCase(groupName) || "LipSync".equalsIgnoreCase(groupName))
                                    ? "system" : null);
                }
            }
        }
        if (refs == null) return;

        String physicsRelative = jsonString(refs, "", "Physics", "physics").trim();
        File physicsFile = physicsRelative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, physicsRelative);
        if (physicsFile != null && physicsFile.isFile()) {
            requireInside(modelRoot, physicsFile);
            JSONObject physics = parseMverJson(readText(physicsFile));
            JSONArray settings = jsonArray(physics, "PhysicsSettings", "physicsSettings");
            if (settings != null) {
                for (int i = 0; i < settings.length(); i++) {
                    JSONObject setting = settings.optJSONObject(i);
                    if (setting == null) continue;
                    appendPhysicsEndpointParameters(result, seenIds,
                            jsonArray(setting, "Input", "input"), true);
                    appendPhysicsEndpointParameters(result, seenIds,
                            jsonArray(setting, "Output", "output"), false);
                }
            }
        }

        JSONArray expressions = jsonArray(refs, "Expressions", "expressions");
        if (expressions != null) {
            for (int i = 0; i < expressions.length(); i++) {
                Object raw = expressions.opt(i);
                String relative = raw instanceof JSONObject
                        ? jsonString((JSONObject) raw, "", "File", "file")
                        : (raw instanceof String ? String.valueOf(raw) : "");
                File file = relative.trim().isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, relative);
                if (file == null || !file.isFile()) continue;
                requireInside(modelRoot, file);
                JSONObject expression = parseMverJson(readText(file));
                JSONArray parameters = jsonArray(expression, "Parameters", "parameters");
                if (parameters == null) continue;
                for (int j = 0; j < parameters.length(); j++) {
                    JSONObject parameter = parameters.optJSONObject(j);
                    if (parameter == null) continue;
                    appendSyntheticParameter(result, seenIds,
                            jsonString(parameter, "", "Id", "id"), "Expression", null);
                }
            }
        }

        JSONObject motions = objectIgnoreCase(refs, "Motions", "motions");
        if (motions != null) {
            for (String groupName : jsonKeys(motions)) {
                JSONArray groupItems = motions.optJSONArray(groupName);
                if (groupItems == null) continue;
                for (int i = 0; i < groupItems.length(); i++) {
                    JSONObject ref = groupItems.optJSONObject(i);
                    String relative = ref == null ? "" : jsonString(ref, "", "File", "file").trim();
                    File file = relative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, relative);
                    if (file == null || !file.isFile()) continue;
                    requireInside(modelRoot, file);
                    JSONObject motion = parseMverJson(readText(file));
                    JSONArray curves = jsonArray(motion, "Curves", "curves");
                    if (curves == null) continue;
                    for (int j = 0; j < curves.length(); j++) {
                        JSONObject curve = curves.optJSONObject(j);
                        if (curve == null || !"Parameter".equalsIgnoreCase(
                                jsonString(curve, "", "Target", "target"))) continue;
                        appendSyntheticParameter(result, seenIds,
                                jsonString(curve, "", "Id", "id"), "Motion · " + groupName, null);
                    }
                }
            }
        }
    }

    private static void appendPhysicsEndpointParameters(JSONArray result,
                                                        java.util.Set<String> seenIds,
                                                        JSONArray endpoints,
                                                        boolean input) throws Exception {
        if (endpoints == null) return;
        for (int i = 0; i < endpoints.length(); i++) {
            JSONObject endpoint = endpoints.optJSONObject(i);
            if (endpoint == null) continue;
            JSONObject node = objectIgnoreCase(endpoint, input ? "Source" : "Destination",
                    input ? "source" : "destination");
            if (node == null) continue;
            appendSyntheticParameter(result, seenIds,
                    jsonString(node, "", "Id", "id"), "Physics", "physics");
        }
    }

    private static void appendSyntheticParameter(JSONArray result,
                                                 java.util.Set<String> seenIds,
                                                 String rawId,
                                                 String group,
                                                 String forcedCategory) throws Exception {
        String id = rawId == null ? "" : rawId.trim();
        if (id.isEmpty() || !seenIds.add(id)) return;
        String category = forcedCategory;
        if (category == null || category.isEmpty()) {
            category = classifyParameter(id, id, group == null ? "" : group, null);
            if ("function".equals(category)) category = "advanced";
        }
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("name", id);
        item.put("group", group == null ? "" : group);
        item.put("groupId", "");
        item.put("category", category);
        item.put("defaultTrigger", inferTriggerMode(id, id, category));
        item.put("keySemantic", "");
        item.put("source", "reference");
        result.put(item);
    }

    private static JSONObject buildKeyParameterMap(JSONArray definitions) throws Exception {
        JSONObject result = new JSONObject();
        if (definitions == null) return result;
        boolean hasControllerStickParameters = false;
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject item = definitions.optJSONObject(i);
            if (item == null) continue;
            String semantic = item.optString("keySemantic", "").trim();
            String id = item.optString("id", "").trim();
            if (id.equals("CatParamStickLX") || id.equals("CatParamStickLY")
                    || id.equals("CatParamStickRX") || id.equals("CatParamStickRY")) {
                hasControllerStickParameters = true;
            }
            if (semantic.isEmpty() || id.isEmpty()) continue;
            JSONArray ids = result.optJSONArray(semantic);
            if (ids == null) { ids = new JSONArray(); result.put(semantic, ids); }
            ids.put(id);
        }

        // Community "standard mode" packs can switch into a controller layout without defining
        // ButtonA/ButtonB/... parameters. 大月下 is one such package: it exposes CatParamStick*
        // plus a 手柄模式 function, while the face-button animations intentionally reuse the
        // authored keyboard A/B/X/Y parameters. Generate the canonical gamepad aliases only for
        // models that prove controller capability through CatParamStick*, so a normal keyboard
        // Live2D package is never reclassified.
        if (hasControllerStickParameters) {
            copyKeyParameterAliasIfMissing(result, "South", "KeyA");
            copyKeyParameterAliasIfMissing(result, "East", "KeyB");
            copyKeyParameterAliasIfMissing(result, "West", "KeyX");
            copyKeyParameterAliasIfMissing(result, "North", "KeyY");
            copyKeyParameterAliasIfMissing(result, "DPadUp", "UpArrow");
            copyKeyParameterAliasIfMissing(result, "DPadDown", "DownArrow");
            copyKeyParameterAliasIfMissing(result, "DPadLeft", "LeftArrow");
            copyKeyParameterAliasIfMissing(result, "DPadRight", "RightArrow");
        }
        return result;
    }

    private static void copyKeyParameterAliasIfMissing(JSONObject map, String target, String source)
            throws Exception {
        JSONArray existing = map.optJSONArray(target);
        if (existing != null && existing.length() > 0) return;
        JSONArray sourceIds = map.optJSONArray(source);
        if (sourceIds == null || sourceIds.length() == 0) return;
        JSONArray copy = new JSONArray();
        for (int i = 0; i < sourceIds.length(); i++) {
            String id = sourceIds.optString(i, "").trim();
            if (!id.isEmpty()) copy.put(id);
        }
        if (copy.length() > 0) map.put(target, copy);
    }

    private static JSONArray parameterIdsBySemantic(JSONArray definitions, String semantic) {
        JSONArray result = new JSONArray();
        if (definitions == null) return result;
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject item = definitions.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            String name = item.optString("name", "").toLowerCase(Locale.ROOT);
            boolean match = switch (semantic) {
                case "keyboardDown" -> id.equals("CatParamLeftHandDown")
                        || name.contains("键盘按下") || name.contains("keyboard down");
                case "mouseX" -> id.equals("ParamMouseX") || name.equals("鼠标x") || name.contains("mouse x");
                case "mouseY" -> id.equals("ParamMouseY") || name.equals("鼠标y") || name.contains("mouse y");
                case "mouseLeft" -> id.equals("ParamMouseLeftDown") || name.contains("鼠标左键") || name.contains("mouse left");
                case "mouseRight" -> id.equals("ParamMouseRightDown") || id.equals("ParamMouseRihgtDown")
                        || name.contains("鼠标右键") || name.contains("mouse right");
                default -> false;
            };
            if (match && !id.isEmpty()) result.put(id);
        }
        return result;
    }

    private static String inferKeySemantic(String id, String name, String group) {
        String raw = (name == null ? "" : name).trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        String groupLower = group == null ? "" : group.toLowerCase(Locale.ROOT);
        String idLower = id == null ? "" : id.toLowerCase(Locale.ROOT);
        boolean likelyGamepad = groupLower.contains("手柄") || groupLower.contains("控制器")
                || groupLower.contains("gamepad") || groupLower.contains("controller")
                || groupLower.contains("joystick") || idLower.contains("gamepad")
                || idLower.contains("controller") || idLower.contains("joystick")
                || idLower.contains("stick") || idLower.contains("button") || idLower.contains("btn");
        if (likelyGamepad) {
            String gamepad = inferGamepadParameterSemantic(id, raw, group);
            if (gamepad != null) return gamepad;
        }
        boolean likelyKey = groupLower.contains("按键") || groupLower.contains("keyboard")
                || idLower.contains("key");
        if (!likelyKey && !(lower.equals("space") || lower.equals("空格") || lower.equals("enter")
                || lower.equals("回车") || lower.equals("shift") || lower.equals("ctrl")
                || lower.equals("control") || lower.equals("alt"))) return null;
        if (lower.matches("[a-z]")) return "Key" + lower.toUpperCase(Locale.ROOT);
        if (lower.matches("[0-9]")) return "Num" + lower;
        if (lower.matches("f(?:[1-9]|1[0-2])")) return lower.toUpperCase(Locale.ROOT);
        if (lower.equals("space") || lower.contains("空格")) return "Space";
        if (lower.equals("enter") || lower.equals("return") || lower.contains("回车")) return "Return";
        if (lower.contains("shift")) return "Shift";
        if (lower.equals("ctrl") || lower.contains("control") || lower.contains("控制")) return "Control";
        if (lower.contains("alt")) return "Alt";
        if (lower.contains("tab")) return "Tab";
        if (lower.contains("esc")) return "Escape";
        if (lower.contains("backspace") || lower.contains("退格")) return "Backspace";
        if (lower.contains("delete") || lower.contains("删除")) return "Delete";
        if (lower.contains("上") && lower.contains("方向")) return "UpArrow";
        if (lower.contains("下") && lower.contains("方向")) return "DownArrow";
        if (lower.contains("左") && lower.contains("方向")) return "LeftArrow";
        if (lower.contains("右") && lower.contains("方向")) return "RightArrow";
        return null;
    }

    private static String inferGamepadParameterSemantic(String id, String name) {
        return inferGamepadParameterSemantic(id, name, "");
    }

    /**
     * Resolve controller button semantics from CDI ids/names. Community packs are inconsistent:
     * some use South/East/... while others expose bare A/B/X/Y, LB/RB/LT/RT or Chinese labels.
     * Bare one-letter names are accepted only when the parameter group/id already proves this is
     * a controller parameter, preventing an unrelated parameter named "A" from being reclassified.
     */
    private static String inferGamepadParameterSemantic(String id, String name, String group) {
        String idToken = normalizeAssetToken(id == null ? "" : id);
        String nameToken = normalizeAssetToken(name == null ? "" : name);
        String groupToken = normalizeAssetToken(group == null ? "" : group);
        String combined = idToken + nameToken + groupToken;
        boolean controllerContext = groupToken.contains("手柄") || groupToken.contains("控制器")
                || groupToken.contains("gamepad") || groupToken.contains("controller")
                || groupToken.contains("joystick") || idToken.contains("gamepad")
                || idToken.contains("controller") || idToken.contains("joystick")
                || idToken.contains("button") || idToken.contains("btn")
                || idToken.startsWith("catparamstick");

        if (combined.contains("dpadup") || combined.contains("hatup")
                || combined.contains("十字键上") || combined.contains("方向键上") || combined.contains("方向上")) return "DPadUp";
        if (combined.contains("dpaddown") || combined.contains("hatdown")
                || combined.contains("十字键下") || combined.contains("方向键下") || combined.contains("方向下")) return "DPadDown";
        if (combined.contains("dpadleft") || combined.contains("hatleft")
                || combined.contains("十字键左") || combined.contains("方向键左") || combined.contains("方向左")) return "DPadLeft";
        if (combined.contains("dpadright") || combined.contains("hatright")
                || combined.contains("十字键右") || combined.contains("方向键右") || combined.contains("方向右")) return "DPadRight";

        if (combined.contains("lefttrigger2") || combined.contains("buttonl2") || combined.contains("btnl2")
                || combined.contains("左扳机") || combined.contains("左触发")
                || (controllerContext && (nameToken.equals("l2") || nameToken.equals("lt")))) return "LeftTrigger2";
        if (combined.contains("righttrigger2") || combined.contains("buttonr2") || combined.contains("btnr2")
                || combined.contains("右扳机") || combined.contains("右触发")
                || (controllerContext && (nameToken.equals("r2") || nameToken.equals("rt")))) return "RightTrigger2";
        if (combined.contains("leftbumper") || combined.contains("leftshoulder") || combined.contains("buttonl1")
                || combined.contains("btnl1") || combined.contains("左肩键")
                || (controllerContext && (nameToken.equals("l1") || nameToken.equals("lb")))) return "LeftTrigger";
        if (combined.contains("rightbumper") || combined.contains("rightshoulder") || combined.contains("buttonr1")
                || combined.contains("btnr1") || combined.contains("右肩键")
                || (controllerContext && (nameToken.equals("r1") || nameToken.equals("rb")))) return "RightTrigger";

        if (combined.contains("buttona") || combined.contains("btna") || combined.contains("south") || combined.contains("cross")
                || (controllerContext && (nameToken.equals("a") || nameToken.equals("按键a") || nameToken.equals("按钮a")))) return "South";
        if (combined.contains("buttonb") || combined.contains("btnb") || combined.contains("east") || combined.contains("circle")
                || (controllerContext && (nameToken.equals("b") || nameToken.equals("按键b") || nameToken.equals("按钮b")))) return "East";
        if (combined.contains("buttonx") || combined.contains("btnx") || combined.contains("west") || combined.contains("square")
                || (controllerContext && (nameToken.equals("x") || nameToken.equals("按键x") || nameToken.equals("按钮x")))) return "West";
        if (combined.contains("buttony") || combined.contains("btny") || combined.contains("north") || combined.contains("triangle")
                || (controllerContext && (nameToken.equals("y") || nameToken.equals("按键y") || nameToken.equals("按钮y")))) return "North";

        if (combined.contains("leftstickdown") || combined.contains("leftstickclick") || combined.contains("buttonl3")
                || combined.contains("btnl3") || combined.contains("左摇杆按下") || combined.contains("左摇杆点击")
                || (controllerContext && (nameToken.equals("l3") || nameToken.equals("ls")))) return "L3";
        if (combined.contains("rightstickdown") || combined.contains("rightstickclick") || combined.contains("buttonr3")
                || combined.contains("btnr3") || combined.contains("右摇杆按下") || combined.contains("右摇杆点击")
                || (controllerContext && (nameToken.equals("r3") || nameToken.equals("rs")))) return "R3";
        if (combined.contains("select") || combined.contains("buttonback") || combined.contains("view") || combined.contains("选择键")) return "Select";
        if (combined.contains("start") || combined.contains("menu") || combined.contains("options") || combined.contains("开始键")) return "Start";
        if (combined.contains("guide") || combined.contains("mode") || combined.contains("home") || combined.contains("ps键")) return "Mode";
        if (combined.contains("buttonm1") || combined.contains("btnm1") || combined.contains("back1") || combined.contains("paddle1")
                || (controllerContext && nameToken.equals("m1"))) return "M1";
        if (combined.contains("buttonm2") || combined.contains("btnm2") || combined.contains("back2") || combined.contains("paddle2")
                || (controllerContext && nameToken.equals("m2"))) return "M2";
        if (combined.contains("buttonm3") || combined.contains("btnm3") || combined.contains("back3") || combined.contains("paddle3")
                || (controllerContext && nameToken.equals("m3"))) return "M3";
        if (combined.contains("buttonm4") || combined.contains("btnm4") || combined.contains("back4") || combined.contains("paddle4")
                || (controllerContext && nameToken.equals("m4"))) return "M4";
        return null;
    }

    private static String classifyParameter(String id, String name, String group, String keySemantic) {
        if (keySemantic != null && !keySemantic.isEmpty()) return "key";
        String lower = (id + " " + name + " " + group).toLowerCase(Locale.ROOT);
        String nameLower = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        if (id.equals("ParamMouseX") || id.equals("ParamMouseY")
                || id.equals("ParamMouseLeftDown") || id.equals("ParamMouseRightDown")
                || id.equals("ParamMouseRihgtDown") || nameLower.equals("鼠标x")
                || nameLower.equals("鼠标y") || nameLower.contains("鼠标左键")
                || nameLower.contains("鼠标右键") || nameLower.contains("mouse left")
                || nameLower.contains("mouse right") || nameLower.contains("keyboard down")
                || nameLower.contains("键盘按下")) return "input";
        if (lower.contains("eyeball") || lower.contains("eyeopen") || lower.contains("眼珠")
                || ((nameLower.contains("左眼") || nameLower.contains("右眼"))
                    && (nameLower.contains("开闭") || nameLower.contains("xy")))
                || lower.contains("mouth") || lower.contains("嘴")
                || lower.contains("breath") || lower.contains("呼吸") || lower.contains("anglex")
                || lower.contains("angley") || lower.contains("anglez") || lower.contains("角度")) return "system";
        if (lower.contains("物理") || lower.contains("头发") || lower.contains("带子")
                || lower.contains("衣") || lower.contains("液体") || lower.contains("摆动")) return "physics";
        if (lower.contains("防盗") || lower.contains("watermark") || lower.contains("水印")) return "support";
        return "function";
    }

    private static String inferTriggerMode(String id, String name, String category) {
        if ("key".equals(category) || "input".equals(category)) return "hold";
        String lower = (id + " " + name).toLowerCase(Locale.ROOT);
        if (lower.contains("按下") || lower.contains("down")) return "hold";
        if (lower.contains("喝") || lower.contains("捧") || lower.contains("吸")
                || lower.contains("来信") || lower.contains("消失") || lower.contains("变身")) return "pulse";
        return "toggle";
    }

    private static void appendLive2dConfig(JSONObject config, File modelRoot, File modelFile) throws Exception {
        appendLive2dConfig(config, modelRoot, modelFile, false);
    }

    private static final class RuntimeMetadataBudget {
        long remaining;
        RuntimeMetadataBudget(long bytes) { remaining = Math.max(0L, bytes); }
        boolean take(File file) {
            if (file == null || !file.isFile()) return false;
            long size = Math.max(0L, file.length());
            if (size > LIVE2D_DISPLAY_SINGLE_JSON_BYTES || size > remaining) return false;
            remaining -= size;
            return true;
        }
    }

    private static void appendLive2dConfig(JSONObject config, File modelRoot, File modelFile,
                                           boolean displayOptimized) throws Exception {
        JSONObject modelJson = parseMverJson(readText(modelFile));
        JSONObject refs = objectIgnoreCase(modelJson, "FileReferences", "fileReferences");
        if (refs == null) throw new IOException("model3 缺少 FileReferences");
        String mocRelative = jsonString(refs, "", "Moc", "moc", "Model").trim();
        File moc = resolveRelativeIgnoreCase(modelRoot, mocRelative);
        if (moc == null || !moc.isFile()) throw new IOException("Live2D Moc 文件缺失: " + mocRelative);
        requireInside(modelRoot, moc);

        JSONArray textureRefs = jsonArray(refs, "Textures", "textures");
        if (textureRefs == null || textureRefs.length() == 0) throw new IOException("Live2D Textures 缺失");
        JSONArray textures = new JSONArray();
        for (int i = 0; i < textureRefs.length(); i++) {
            String textureRelative = String.valueOf(textureRefs.opt(i)).trim();
            File file = resolveRelativeIgnoreCase(modelRoot, textureRelative);
            if (file == null || !file.isFile()) throw new IOException("Live2D 纹理缺失: " + textureRelative);
            requireInside(modelRoot, file);
            textures.put(Uri.fromFile(file).toString());
        }

        RuntimeMetadataBudget expressionBudget = displayOptimized
                ? new RuntimeMetadataBudget(LIVE2D_DISPLAY_EXPRESSION_BUDGET_BYTES) : null;
        RuntimeMetadataBudget motionBudget = displayOptimized
                ? new RuntimeMetadataBudget(LIVE2D_DISPLAY_MOTION_BUDGET_BYTES) : null;
        RuntimeMetadataBudget auxBudget = displayOptimized
                ? new RuntimeMetadataBudget(LIVE2D_DISPLAY_AUX_BUDGET_BYTES) : null;

        // Keep expression array indices aligned with FileReferences.Expressions. Mver l2d_expression
        // binds by manifest index, so compacting missing/invalid entries would trigger the wrong face.
        JSONArray expressions = new JSONArray();
        JSONArray expressionRefs = jsonArray(refs, "Expressions", "expressions");
        if (expressionRefs != null) {
            for (int i = 0; i < expressionRefs.length(); i++) {
                Object rawRef = expressionRefs.opt(i);
                String relative = rawRef instanceof JSONObject
                        ? jsonString((JSONObject) rawRef, "", "File", "file")
                        : (rawRef instanceof String ? String.valueOf(rawRef) : "");
                File file = relative.trim().isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, relative);
                if (file != null && file.isFile()) {
                    requireInside(modelRoot, file);
                    if (!displayOptimized || expressionBudget.take(file)) {
                        expressions.put(parseMverJson(readText(file)));
                    } else {
                        expressions.put(JSONObject.NULL);
                    }
                } else {
                    expressions.put(JSONObject.NULL);
                }
            }
        }

        JSONArray motions = new JSONArray();
        JSONObject motionGroups = new JSONObject();
        java.util.HashMap<String, Integer> motionIndices = new java.util.HashMap<>();
        JSONObject motionRefs = objectIgnoreCase(refs, "Motions", "motions");
        if (motionRefs != null) {
            List<String> groups = jsonKeys(motionRefs);
            if (displayOptimized) {
                groups.sort((a, b) -> {
                    boolean aIdle = "idle".equalsIgnoreCase(a);
                    boolean bIdle = "idle".equalsIgnoreCase(b);
                    if (aIdle != bIdle) return aIdle ? -1 : 1;
                    return a.compareToIgnoreCase(b);
                });
            }
            for (String group : groups) {
                JSONArray groupItems = motionRefs.optJSONArray(group);
                if (groupItems == null) continue;
                JSONArray groupIndices = new JSONArray();
                for (int i = 0; i < groupItems.length(); i++) {
                    JSONObject ref = groupItems.optJSONObject(i);
                    String relative = ref == null ? "" : jsonString(ref, "", "File", "file").trim();
                    File file = relative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, relative);
                    if (file == null || !file.isFile()) {
                        // Preserve group slot numbering; Mver bindings address CAT_motion by row index.
                        groupIndices.put(-1);
                        continue;
                    }
                    requireInside(modelRoot, file);
                    String canonical = file.getCanonicalPath();
                    Integer motionIndex = motionIndices.get(canonical);
                    if (motionIndex == null) {
                        if (displayOptimized && !motionBudget.take(file)) {
                            groupIndices.put(-1);
                            continue;
                        }
                        motionIndex = motions.length();
                        JSONObject item = new JSONObject();
                        item.put("name", group + "/" + i);
                        item.put("json", parseMverJson(readText(file)));
                        item.put("sound", motionSoundUri(modelRoot, ref, file));
                        motions.put(item);
                        motionIndices.put(canonical, motionIndex);
                    }
                    groupIndices.put(motionIndex);
                }
                motionGroups.put(group, groupIndices);
            }
        }
        // Keyboard-cat style runtime keeps loose Motion3 discovery for compatibility. The dedicated
        // standalone Live2D display intentionally skips that recursive scan: unreferenced motions
        // are not required for first-frame rendering and can make large VTube packs allocate
        // hundreds of megabytes before WebView receives any HTML.
        if (!displayOptimized) {
            List<File> looseMotions = new ArrayList<>();
            collectFilesBySuffix(modelRoot, modelRoot, ".motion3.json", looseMotions, 0);
            looseMotions.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File file : looseMotions) {
                String canonical = file.getCanonicalPath();
                if (motionIndices.containsKey(canonical)) continue;
                int motionIndex = motions.length();
                JSONObject item = new JSONObject();
                item.put("name", file.getName());
                item.put("json", parseMverJson(readText(file)));
                item.put("sound", adjacentMotionSoundUri(file));
                motions.put(item);
                motionIndices.put(canonical, motionIndex);
            }
        }

        Object physics = JSONObject.NULL;
        String physicsRelative = jsonString(refs, "", "Physics", "physics").trim();
        if (!physicsRelative.isEmpty()) {
            File physicsFile = resolveRelativeIgnoreCase(modelRoot, physicsRelative);
            if (physicsFile != null) {
                requireInside(modelRoot, physicsFile);
                if (physicsFile.isFile() && (!displayOptimized || auxBudget.take(physicsFile))) {
                    physics = parseMverJson(readText(physicsFile));
                }
            }
        }

        Object pose = JSONObject.NULL;
        String poseRelative = jsonString(refs, "", "Pose", "pose").trim();
        if (!poseRelative.isEmpty()) {
            File poseFile = resolveRelativeIgnoreCase(modelRoot, poseRelative);
            if (poseFile != null) {
                requireInside(modelRoot, poseFile);
                if (poseFile.isFile() && (!displayOptimized || auxBudget.take(poseFile))) {
                    pose = parseMverJson(readText(poseFile));
                }
            }
        }

        JSONArray eyeBlinkIds = live2dGroupIds(modelJson, "EyeBlink");
        JSONArray lipSyncIds = live2dGroupIds(modelJson, "LipSync");
        JSONObject layout = objectIgnoreCase(modelJson, "Layout", "layout");
        JSONArray parameterDefinitions = displayOptimized
                ? live2dParameterDefinitionsFast(modelRoot, modelJson)
                : live2dParameterDefinitions(modelRoot, modelJson);

        config.put("parameterDefinitions", parameterDefinitions);
        config.put("keyParameterMap", buildKeyParameterMap(parameterDefinitions));
        config.put("keyboardDownParameterIds", parameterIdsBySemantic(parameterDefinitions, "keyboardDown"));
        config.put("mouseXParameterIds", parameterIdsBySemantic(parameterDefinitions, "mouseX"));
        config.put("mouseYParameterIds", parameterIdsBySemantic(parameterDefinitions, "mouseY"));
        config.put("mouseLeftParameterIds", parameterIdsBySemantic(parameterDefinitions, "mouseLeft"));
        config.put("mouseRightParameterIds", parameterIdsBySemantic(parameterDefinitions, "mouseRight"));

        config.put("textures", textures);
        config.put("expressions", expressions);
        config.put("motions", motions);
        config.put("motionGroups", motionGroups);
        config.put("physics", physics);
        config.put("pose", pose);
        config.put("eyeBlinkIds", eyeBlinkIds);
        config.put("lipSyncIds", lipSyncIds);
        config.put("modelLayout", layout == null ? new JSONObject() : layout);
        config.put("mocUri", Uri.fromFile(moc).toString());
        config.put("mocVersion", readMocFormatVersion(moc));
        // Keep the inline path for ordinary small models because it is maximally compatible with
        // old WebView builds. Large MOC3 files are streamed from file:// instead, avoiding a
        // 4/3 base64 expansion plus two additional Java/JS string copies.
        config.put("mocBase64", !displayOptimized && moc.length() <= MAX_INLINE_MOC_BYTES
                ? Base64.encodeToString(readBytes(moc), Base64.NO_WRAP) : "");
    }

    private static JSONArray mverMediaBindings(File dir, JSONArray bindings) throws Exception {
        JSONArray result = new JSONArray();
        if (bindings == null || !dir.isDirectory()) return result;
        for (int index = 0; index < bindings.length(); index++) {
            File media = firstAssetIgnoreCase(dir,
                    index + ".flac", index + ".wav", index + ".ogg",
                    index + ".mp3", index + ".m4a", index + ".aac");
            if (media == null) continue;
            JSONArray keys = mverKeyCombo(bindings.optJSONArray(index), MODE_STANDARD, true);
            if (keys.length() == 0) continue;
            JSONObject item = new JSONObject();
            item.put("index", index);
            item.put("keys", keys);
            item.put("src", Uri.fromFile(media).toString());
            result.put(item);
        }
        return result;
    }

    private static String motionSoundUri(File modelRoot, JSONObject ref, File motionFile) throws Exception {
        String sound = jsonString(ref, "", "Sound", "sound").trim();
        if (!sound.isEmpty()) {
            File soundFile = resolveRelativeIgnoreCase(modelRoot, sound);
            if (soundFile != null) {
                requireInside(modelRoot, soundFile);
                if (soundFile.isFile()) return Uri.fromFile(soundFile).toString();
            }
        }
        return adjacentMotionSoundUri(motionFile);
    }

    private static String adjacentMotionSoundUri(File motionFile) {
        if (motionFile == null) return "";
        String name = motionFile.getName();
        String stem = name.endsWith(".motion3.json")
                ? name.substring(0, name.length() - ".motion3.json".length())
                : name;
        File parent = motionFile.getParentFile();
        return fileUri(firstAssetIgnoreCase(parent,
                stem + ".flac", stem + ".wav", stem + ".ogg", stem + ".mp3",
                stem + ".m4a", stem + ".aac"));
    }

    private static void collectFilesBySuffix(File root, File dir, String suffix, List<File> out, int depth) {
        if (dir == null || depth > 4) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (!isInside(root, file)) continue;
            if (file.isDirectory()) collectFilesBySuffix(root, file, suffix, out, depth + 1);
            else if (file.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) out.add(file);
        }
    }

    /** Indexed assets independent of key bindings; used by the debug expression selector. */
    private static JSONArray mverIndexedAssets(File dir) {
        JSONArray result = new JSONArray();
        if (dir == null || !dir.isDirectory()) return result;
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return result;
        List<File> ordered = new ArrayList<>(Arrays.asList(files));
        ordered.sort((a, b) -> {
            int ai = numericAssetIndex(a.getName());
            int bi = numericAssetIndex(b.getName());
            if (ai != bi) return Integer.compare(ai, bi);
            return a.getName().compareToIgnoreCase(b.getName());
        });
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (File file : ordered) {
            int index = numericAssetIndex(file.getName());
            if (index < 0 || !seen.add(index)) continue;
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("index", index);
                item.put("src", Uri.fromFile(file).toString());
                result.put(item);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static int numericAssetIndex(String name) {
        if (name == null) return -1;
        int dot = name.indexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        try { return Integer.parseInt(stem); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static JSONArray mverSpriteBindings(File dir, JSONArray bindings, String mode, boolean forceVk) throws Exception {
        JSONArray result = new JSONArray();
        if (bindings == null || !dir.isDirectory()) return result;
        for (int index = 0; index < bindings.length(); index++) {
            File image = firstAssetIgnoreCase(dir,
                    index + ".png", index + ".webp", index + ".jpg", index + ".jpeg");
            if (image == null || !image.isFile()) continue;
            JSONArray keys = mverKeyCombo(bindings.optJSONArray(index), mode, forceVk);
            if (keys.length() == 0) continue;
            JSONObject item = new JSONObject();
            item.put("index", index);
            item.put("keys", keys);
            item.put("src", Uri.fromFile(image).toString());
            result.put(item);
        }
        return result;
    }

    private static JSONArray mverComboBindings(JSONArray bindings, boolean forceVk) {
        JSONArray result = new JSONArray();
        if (bindings == null) return result;
        for (int index = 0; index < bindings.length(); index++) {
            JSONArray keys = mverKeyCombo(bindings.optJSONArray(index), MODE_STANDARD, forceVk);
            if (keys.length() == 0) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("index", index);
                item.put("keys", keys);
                result.put(item);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static JSONArray mverKeyCombo(JSONArray rawKeys, String mode, boolean forceVk) {
        JSONArray keys = new JSONArray();
        if (rawKeys == null) return keys;
        for (int i = 0; i < rawKeys.length(); i++) {
            int raw = rawKeys.optInt(i, Integer.MIN_VALUE);
            String semantic = forceVk || !MODE_GAMEPAD.equals(mode) ? mverVkName(raw) : mverGamepadName(raw);
            if (semantic != null && !semantic.isEmpty()) keys.put(semantic);
        }
        return keys;
    }

    private static JSONArray mverKeySet(JSONArray rawKeys, int... defaultKeys) {
        JSONArray keys = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        if (rawKeys != null) {
            for (int i = 0; i < rawKeys.length(); i++) {
                String semantic = mverVkName(rawKeys.optInt(i, Integer.MIN_VALUE));
                if (semantic != null && !semantic.isEmpty() && seen.add(semantic)) keys.put(semantic);
            }
            return keys;
        }
        if (defaultKeys != null) {
            for (int raw : defaultKeys) {
                String semantic = mverVkName(raw);
                if (semantic != null && !semantic.isEmpty() && seen.add(semantic)) keys.put(semantic);
            }
        }
        return keys;
    }

    private static double jsonArrayNumber(JSONArray array, int index, double fallback) {
        if (array == null || index < 0 || index >= array.length()) return fallback;
        double value = array.optDouble(index, fallback);
        return Double.isFinite(value) ? value : fallback;
    }

    private static JSONObject mverSpriteMap(File dir, JSONArray bindings, String mode) throws Exception {
        JSONObject map = new JSONObject();
        if (bindings == null || !dir.isDirectory()) return map;
        for (int index = 0; index < bindings.length(); index++) {
            File image = firstAssetIgnoreCase(dir,
                    index + ".png", index + ".webp", index + ".jpg", index + ".jpeg");
            if (image == null || !image.isFile()) continue;

            JSONArray keys = bindings.optJSONArray(index);
            if (keys == null) continue;
            for (int k = 0; k < keys.length(); k++) {
                int raw = keys.optInt(k, Integer.MIN_VALUE);
                String semantic = MODE_GAMEPAD.equals(mode) ? mverGamepadName(raw) : mverVkName(raw);
                if (semantic != null && !semantic.isEmpty()) {
                    map.put(semantic, Uri.fromFile(image).toString());
                }
            }
        }
        return map;
    }

    private static List<String> jsonKeys(JSONObject object) {
        List<String> result = new ArrayList<>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) result.add(iterator.next());
        result.sort(String::compareTo);
        return result;
    }

    private static String mverVkName(int vk) {
        if (vk >= 0x30 && vk <= 0x39) return "Num" + (vk - 0x30);
        if (vk >= 0x41 && vk <= 0x5A) return "Key" + (char) ('A' + (vk - 0x41));
        if (vk >= 0x60 && vk <= 0x69) return "Numpad" + (vk - 0x60);
        if (vk >= 0x70 && vk <= 0x7B) return "F" + (vk - 0x70 + 1);
        return switch (vk) {
            case 0x01 -> "MouseLeft";
            case 0x02 -> "MouseRight";
            case 0x04 -> "MouseMiddle";
            case 0x05 -> "MouseSide1";
            case 0x06 -> "MouseSide2";
            case 0x08 -> "Backspace";
            case 0x09 -> "Tab";
            case 0x0D -> "Return";
            case 0x10 -> "Shift";
            case 0x11 -> "Control";
            case 0x12 -> "Alt";
            case 0xA0 -> "ShiftLeft";
            case 0xA1 -> "ShiftRight";
            case 0xA2 -> "ControlLeft";
            case 0xA3 -> "ControlRight";
            case 0xA4 -> "Alt";
            case 0xA5 -> "AltGr";
            case 0x13 -> "Pause";
            case 0x14 -> "CapsLock";
            case 0x1B -> "Escape";
            case 0x20 -> "Space";
            case 0x21 -> "PageUp";
            case 0x22 -> "PageDown";
            case 0x23 -> "End";
            case 0x24 -> "Home";
            case 0x25 -> "LeftArrow";
            case 0x26 -> "UpArrow";
            case 0x27 -> "RightArrow";
            case 0x28 -> "DownArrow";
            case 0x2C -> "PrintScreen";
            case 0x2D -> "Insert";
            case 0x2E -> "Delete";
            case 0x5B, 0x5C -> "Meta";
            case 0x5D -> "Apps";
            case 0x6A -> "NumpadMultiply";
            case 0x6B -> "NumpadAdd";
            case 0x6D -> "NumpadSubtract";
            case 0x6E -> "NumpadDot";
            case 0x6F -> "NumpadDivide";
            case 0x90 -> "NumLock";
            case 0x91 -> "ScrollLock";
            case 0xAD -> "VolumeMute";
            case 0xAE -> "VolumeDown";
            case 0xAF -> "VolumeUp";
            case 0xB0 -> "MediaNext";
            case 0xB1 -> "MediaPrevious";
            case 0xB2 -> "MediaStop";
            case 0xB3 -> "MediaPlayPause";
            case 0xBA -> "SemiColon";
            case 0xBB -> "Equal";
            case 0xBC -> "Comma";
            case 0xBD -> "Minus";
            case 0xBE -> "Dot";
            case 0xBF -> "Slash";
            case 0xC0 -> "BackQuote";
            case 0xDB -> "LeftBracket";
            case 0xDC -> "BackSlash";
            case 0xDD -> "RightBracket";
            case 0xDE -> "Quote";
            case 0xE2 -> "BackSlash";
            default -> null;
        };
    }

    /** Mver 0.1.6 的 gamepad 数字索引与其 XInput/DirectInput 图层顺序。 */
    private static String mverGamepadName(int button) {
        return switch (button) {
            case 0 -> "South";
            case 1 -> "East";
            case 2 -> "West";
            case 3 -> "North";
            case 4 -> "LeftTrigger";
            case 5 -> "RightTrigger";
            case 6 -> "LeftTrigger2";
            case 7 -> "RightTrigger2";
            case 8 -> "L3";
            case 9 -> "R3";
            case 10 -> "DPadUp";
            case 11 -> "DPadDown";
            case 12 -> "DPadLeft";
            case 13 -> "DPadRight";
            default -> null;
        };
    }

    private static String fileUri(File file) {
        return file != null && file.isFile() ? Uri.fromFile(file).toString() : "";
    }

    /**
     * Detect whether an Mver standard package supplies authored full-canvas keyboard-hand layers.
     * Those layers already contain the exact device/hand placement chosen by the skin author and
     * must not be combined with Live2D CatParam*HandDown animation.
     */
    private static boolean hasFullFrameMverHandOverlay(File modeRoot, int designWidth, int designHeight) {
        if (modeRoot == null || designWidth <= 0 || designHeight <= 0) return false;
        final double minimumCoverage = 0.72;
        for (String group : new String[]{"hand", "lefthand", "righthand"}) {
            File dir = dirIgnoreCase(modeRoot, group);
            if (dir == null || !dir.isDirectory()) continue;
            File[] files = dir.listFiles(File::isFile);
            if (files == null) continue;
            for (File file : files) {
                String lower = file.getName().toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".png") || lower.endsWith(".webp")
                        || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) continue;
                int[] size = imageSize(file);
                if (size == null) continue;
                if (size[0] >= designWidth * minimumCoverage
                        && size[1] >= designHeight * minimumCoverage) return true;
            }
        }
        return false;
    }

    /**
     * 获取样式的合成画布尺寸。BongoCat 样式的 background/key overlays 都以同一画布导出，
     * 因此 background.png 是最稳定的布局基准。只读取图片头，不解码高分辨率像素。
     */
    private static int[] detectDesignSize(File sourceRoot) {
        File resources = dirIgnoreCase(sourceRoot, "resources");
        int[] background = imageSize(assetImageIgnoreCase(resources, "background"));
        if (background != null) return background;
        int[] cover = imageSize(assetImageIgnoreCase(resources, "cover"));
        if (cover != null) return cover;
        return new int[]{612, 354};
    }

    private static int[] imageSize(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth > 0 && options.outHeight > 0) {
                return new int[]{options.outWidth, options.outHeight};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int modeOrder(String mode) {
        if (MODE_STANDARD.equals(mode)) return 0;
        if (MODE_KEYBOARD.equals(mode)) return 1;
        if (MODE_GAMEPAD.equals(mode)) return 2;
        return 3;
    }

    private static JSONObject keyAssetMap(File dir) {
        JSONObject result = new JSONObject();
        if (dir == null || !dir.isDirectory()) return result;
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return result;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!"png".equals(ext) && !"webp".equals(ext) && !"jpg".equals(ext) && !"jpeg".equals(ext)) continue;
            try { result.put(name.substring(0, dot), fileUri(file)); }
            catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Adapt oversized Live2D atlases in the temporary Bongo Cat import tree. A uniform power-of-two
     * sample keeps Cubism UV coordinates unchanged while avoiding WebGL MAX_TEXTURE_SIZE failures
     * and excessive decoded RGBA memory on Android.
     */
    private static int adaptLargeLive2dTextures(File packageRoot, File modelFile, boolean clearQuality) throws Exception {
        JSONObject model = parseMverJson(readText(modelFile));
        JSONObject refs = objectIgnoreCase(model, "FileReferences", "fileReferences");
        JSONArray textures = refs == null ? null : jsonArray(refs, "Textures", "textures");
        File modelRoot = modelFile.getParentFile();
        if (textures == null || modelRoot == null) return 0;

        final class TextureInfo {
            final String relative;
            final File file;
            final int width;
            final int height;
            TextureInfo(String relative, File file, int width, int height) {
                this.relative = relative; this.file = file; this.width = width; this.height = height;
            }
        }

        List<TextureInfo> infos = new ArrayList<>();
        long totalPixels = 0L;
        for (int i = 0; i < textures.length(); i++) {
            String relative = String.valueOf(textures.opt(i)).trim();
            if (relative.isEmpty()) continue;
            File texture = resolveRelativeIgnoreCase(modelRoot, relative);
            if (texture == null || !texture.isFile()) continue;
            requireInside(packageRoot, texture);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(texture.getAbsolutePath(), bounds);
            int width = Math.max(0, bounds.outWidth);
            int height = Math.max(0, bounds.outHeight);
            if (width <= 0 || height <= 0) continue;
            infos.add(new TextureInfo(relative, texture, width, height));
            totalPixels += (long) width * (long) height;
        }

        long texturePixelBudget = clearQuality ? 40L * 1024L * 1024L : MAX_BONGOCAT_TEXTURE_PIXELS;
        int budgetSample = 1;
        while (totalPixels / ((long) budgetSample * budgetSample) > texturePixelBudget) {
            budgetSample *= 2;
        }

        int adapted = 0;
        for (TextureInfo info : infos) {
            int edgeSample = 1;
            while (Math.max(info.width, info.height) / edgeSample > MAX_BONGOCAT_TEXTURE_EDGE) edgeSample *= 2;
            int sample = Math.max(edgeSample, budgetSample);
            if (sample <= 1) continue;

            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            decode.inScaled = false;
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            // Live2D WebGL performs premultiplication once at texture upload. Keeping imported
            // atlas pixels straight-alpha here prevents Android's decoder + PNG/WebP re-encode
            // path from baking a first alpha multiplication into resized community textures.
            decode.inPremultiplied = false;
            decode.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            Bitmap bitmap = BitmapFactory.decodeFile(info.file.getAbsolutePath(), decode);
            if (bitmap == null) throw new IOException("无法适配键盘猫大纹理: " + info.relative);

            File temp = new File(info.file.getParentFile(), info.file.getName() + ".axon_tmp");
            Bitmap.CompressFormat format = textureCompressFormat(info.file.getName());
            try (FileOutputStream out = new FileOutputStream(temp)) {
                int quality = format == Bitmap.CompressFormat.JPEG ? 95 : 100;
                if (!bitmap.compress(format, quality, out)) {
                    throw new IOException("键盘猫纹理压缩失败: " + info.relative);
                }
            } finally {
                bitmap.recycle();
            }
            if (!info.file.delete() || !temp.renameTo(info.file)) {
                copyTree(temp, info.file);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            adapted++;
        }
        return adapted;
    }

    private static Bitmap.CompressFormat textureCompressFormat(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return Bitmap.CompressFormat.JPEG;
        if (lower.endsWith(".webp")) return Bitmap.CompressFormat.WEBP;
        return Bitmap.CompressFormat.PNG;
    }

    private static String detectMode(List<String> left, List<String> right) {
        // Bare A/B/X/Y are ambiguous: a normal keyboard pack very often contains all four. They
        // remain valid runtime aliases but are no longer sufficient to classify the whole style as
        // gamepad. Only controller-specific names (South/L1/DPad/etc.) may force GAMEPAD here.
        List<String> all = new ArrayList<>();
        if (left != null) all.addAll(left);
        if (right != null) all.addAll(right);
        for (String raw : all) {
            String semantic = canonicalGamepadSemantic(raw);
            if (semantic == null) continue;
            String token = normalizeAssetToken(raw);
            if (isUnambiguousGamepadAssetToken(token, semantic)) return MODE_GAMEPAD;
        }
        // A right-hand key set is the strongest resource-level signal for a keyboard layout.
        if (right != null && !right.isEmpty()) return MODE_KEYBOARD;
        return MODE_STANDARD;
    }

    private static boolean isUnambiguousGamepadAssetToken(String token, String semantic) {
        if (token == null || semantic == null) return false;
        // Explicit controller face names are unambiguous; bare A/B/X/Y are not.
        if ("South".equals(semantic) || "East".equals(semantic)
                || "West".equals(semantic) || "North".equals(semantic)) {
            return !("a".equals(token) || "b".equals(token) || "x".equals(token) || "y".equals(token));
        }
        // Generic keyboard navigation names are ambiguous even when they have gamepad aliases.
        return !("up".equals(token) || "down".equals(token) || "left".equals(token)
                || "right".equals(token) || "back".equals(token) || "home".equals(token));
    }

    private static String normalizeAssetToken(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
    }

    /** Canonical BongoCat controller semantic used by the Web runtime. */
    private static String canonicalGamepadSemantic(String raw) {
        String token = normalizeAssetToken(raw);
        return switch (token) {
            case "south", "a", "buttona", "btna", "cross" -> "South";
            case "east", "b", "buttonb", "btnb", "circle" -> "East";
            case "west", "x", "buttonx", "btnx", "square" -> "West";
            case "north", "y", "buttony", "btny", "triangle" -> "North";
            case "c" -> "C";
            case "z" -> "Z";
            case "l1", "lb", "leftbumper", "leftshoulder", "lefttrigger" -> "LeftTrigger";
            case "r1", "rb", "rightbumper", "rightshoulder", "righttrigger" -> "RightTrigger";
            case "l2", "lt", "lefttrigger2" -> "LeftTrigger2";
            case "r2", "rt", "righttrigger2" -> "RightTrigger2";
            case "l3", "ls", "leftstick", "leftstickclick", "leftthumb" -> "L3";
            case "r3", "rs", "rightstick", "rightstickclick", "rightthumb" -> "R3";
            case "select", "back", "view" -> "Select";
            case "start", "menu", "options" -> "Start";
            case "mode", "guide", "home", "ps" -> "Mode";
            case "dpadup", "up", "hatup" -> "DPadUp";
            case "dpaddown", "down", "hatdown" -> "DPadDown";
            case "dpadleft", "left", "hatleft" -> "DPadLeft";
            case "dpadright", "right", "hatright" -> "DPadRight";
            case "m1", "back1", "paddle1" -> "M1";
            case "m2", "back2", "paddle2" -> "M2";
            case "m3", "back3", "paddle3" -> "M3";
            case "m4", "back4", "paddle4" -> "M4";
            default -> null;
        };
    }

    private static void appendCanonicalGamepadAliases(List<String> keys, JSONObject assets) {
        if (keys == null || assets == null) return;
        List<String> original = new ArrayList<>(keys);
        java.util.HashSet<String> existing = new java.util.HashSet<>(keys);
        for (String raw : original) {
            String canonical = canonicalGamepadSemantic(raw);
            if (canonical == null || canonical.isEmpty()) continue;
            if (!existing.contains(canonical)) {
                keys.add(canonical);
                existing.add(canonical);
            }
            if (!assets.has(canonical) && assets.has(raw)) {
                try { assets.put(canonical, assets.optString(raw, "")); }
                catch (Exception ignored) {}
            }
        }
    }

    private static List<String> keyNames(File dir) {
        List<String> result = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return result;
        File[] files = dir.listFiles(File::isFile);
        if (files == null) return result;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!"png".equals(ext) && !"webp".equals(ext) && !"jpg".equals(ext) && !"jpeg".equals(ext)) continue;
            result.add(name.substring(0, dot));
        }
        return result;
    }


    /** Immutable import-time profile. It gives every source format the same normalized contract. */
    private static final class TextureProfile {
        int count;
        int maxEdge;
        long pixels;
        long estimatedRgbaBytes;

        JSONObject toJson() throws Exception {
            JSONObject result = new JSONObject();
            result.put("count", count);
            result.put("maxEdge", maxEdge);
            result.put("pixels", pixels);
            result.put("estimatedRgbaBytes", estimatedRgbaBytes);
            return result;
        }
    }

    private static StyleInfo finalizeImportedStyle(File target, JSONObject meta, String invalidMessage) throws Exception {
        writeText(new File(target, META_FILE), meta.toString());
        StyleInfo imported = readInfo(target);
        if (imported == null) throw new IOException(invalidMessage);
        writeNormalizedManifest(imported);
        invalidateCapabilityCache(imported.id);
        return imported;
    }

    /**
     * Store one Axon-owned manifest regardless of whether the input was a standalone model export,
     * a classic BongoCat resource ZIP, or an Mver portable package. Runtime code can therefore make
     * resource/lifecycle decisions without re-detecting the source package shape on every load.
     */
    private static void writeNormalizedManifest(StyleInfo info) throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", NORMALIZED_MANIFEST_VERSION);
        manifest.put("id", info.id);
        manifest.put("name", info.name);
        manifest.put("sourceFormat", info.format);
        manifest.put("mode", info.mode);
        manifest.put("renderer", info.renderer);
        manifest.put("modelFile", info.modelFile == null ? "" : info.modelFile);
        manifest.put("designWidth", info.designWidth);
        manifest.put("designHeight", info.designHeight);

        File resources = dirIgnoreCase(info.root, "resources");
        JSONObject assets = new JSONObject();
        assets.put("background", relativePathOrEmpty(info.root, assetImageIgnoreCase(resources, "background")));
        assets.put("cover", relativePathOrEmpty(info.root, assetImageIgnoreCase(resources, "cover")));
        File left = dirIgnoreCase(resources, "left-keys");
        File right = dirIgnoreCase(resources, "right-keys");
        assets.put("leftKeyCount", keyNames(left).size());
        assets.put("rightKeyCount", keyNames(right).size());
        manifest.put("assets", assets);

        TextureProfile profile = textureProfile(info);
        manifest.put("textures", profile.toJson());
        manifest.put("runtime", runtimeProfileJson(profile, physicsSettingCount(info)));
        writeText(new File(info.root, NORMALIZED_MANIFEST_FILE), manifest.toString());
    }

    private static String relativePathOrEmpty(File root, File file) {
        if (root == null || file == null || !file.isFile() || !isInside(root, file)) return "";
        try { return relativePath(root, file); } catch (Exception ignored) { return ""; }
    }

    private static TextureProfile textureProfile(StyleInfo info) {
        TextureProfile profile = new TextureProfile();
        if (info == null || info.root == null || info.modelFile == null || info.modelFile.isEmpty()) return profile;
        try {
            File modelFile = new File(info.root, info.modelFile);
            if (!modelFile.isFile() || !isInside(info.root, modelFile)) return profile;
            File modelRoot = modelFile.getParentFile();
            JSONObject model = parseMverJson(readText(modelFile));
            JSONObject refs = objectIgnoreCase(model, "FileReferences", "fileReferences");
            JSONArray textures = refs == null ? null : jsonArray(refs, "Textures", "textures");
            if (modelRoot == null || textures == null) return profile;
            for (int i = 0; i < textures.length(); i++) {
                String relative = String.valueOf(textures.opt(i)).trim();
                File file = relative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, relative);
                if (file == null || !file.isFile() || !isInside(info.root, file)) continue;
                int[] size = imageSize(file);
                if (size == null) continue;
                long pixels = (long) size[0] * (long) size[1];
                profile.count++;
                profile.maxEdge = Math.max(profile.maxEdge, Math.max(size[0], size[1]));
                profile.pixels += pixels;
            }
            profile.estimatedRgbaBytes = Math.min(Long.MAX_VALUE / 4, profile.pixels) * 4L;
        } catch (Throwable ignored) {}
        return profile;
    }

    private static int physicsSettingCount(StyleInfo info) {
        if (info == null || info.root == null || info.modelFile == null || info.modelFile.isEmpty()) return 0;
        try {
            File modelFile = new File(info.root, info.modelFile);
            File modelRoot = modelFile.getParentFile();
            if (modelRoot == null || !modelFile.isFile()) return 0;
            JSONObject model = parseMverJson(readText(modelFile));
            JSONObject refs = objectIgnoreCase(model, "FileReferences", "fileReferences");
            String relative = refs == null ? "" : jsonString(refs, "", "Physics", "physics").trim();
            File physics = relative.isEmpty() ? null : resolveRelativeIgnoreCase(modelRoot, relative);
            if (physics == null || !physics.isFile() || !isInside(info.root, physics)) return 0;
            JSONArray settings = jsonArray(parseMverJson(readText(physics)), "PhysicsSettings", "physicsSettings");
            return settings == null ? 0 : settings.length();
        } catch (Throwable ignored) { return 0; }
    }

    private static JSONObject runtimeProfileJson(TextureProfile profile, int physicsCount) throws Exception {
        long bytes = profile == null ? 0L : profile.estimatedRgbaBytes;
        int textureCount = profile == null ? 0 : profile.count;
        double dprCap = bytes >= 72L * 1024L * 1024L ? 1.35
                : bytes >= 48L * 1024L * 1024L ? 1.5
                : bytes >= 24L * 1024L * 1024L ? 1.75 : 2.0;
        int uploadYieldEvery = bytes >= 32L * 1024L * 1024L || textureCount >= 4 ? 1 : 2;
        int physicsActiveFps = physicsCount >= 18 ? 36 : physicsCount >= 10 ? 45 : 60;
        int physicsIdleFps = physicsCount >= 18 ? 24 : 30;
        JSONObject runtime = new JSONObject();
        runtime.put("renderDprCap", dprCap);
        runtime.put("textureUploadYieldEvery", uploadYieldEvery);
        runtime.put("textureEstimatedRgbaBytes", bytes);
        runtime.put("textureCount", textureCount);
        runtime.put("physicsSettingCount", physicsCount);
        runtime.put("physicsActiveFps", physicsActiveFps);
        runtime.put("physicsIdleFps", physicsIdleFps);
        return runtime;
    }

    private static void appendRuntimeResourceProfile(JSONObject config, StyleInfo info) throws Exception {
        TextureProfile profile = null;
        int physicsCount = 0;
        File normalized = new File(info.root, NORMALIZED_MANIFEST_FILE);
        if (normalized.isFile()) {
            try {
                JSONObject manifest = new JSONObject(readText(normalized));
                JSONObject runtime = manifest.optJSONObject("runtime");
                if (runtime != null) {
                    config.put("normalizedSchemaVersion", manifest.optInt("schemaVersion", 0));
                    config.put("renderDprCap", runtime.optDouble("renderDprCap", 2.0));
                    config.put("textureUploadYieldEvery", runtime.optInt("textureUploadYieldEvery", 2));
                    config.put("textureEstimatedRgbaBytes", runtime.optLong("textureEstimatedRgbaBytes", 0));
                    config.put("textureCount", runtime.optInt("textureCount", 0));
                    config.put("physicsActiveFps", runtime.optInt("physicsActiveFps", 60));
                    config.put("physicsIdleFps", runtime.optInt("physicsIdleFps", 30));
                    return;
                }
            } catch (Throwable ignored) {}
        }
        // Existing styles imported by older Axon versions get the same profile lazily without a
        // destructive migration. Their package directory remains untouched until re-imported.
        profile = textureProfile(info);
        physicsCount = physicsSettingCount(info);
        JSONObject runtime = runtimeProfileJson(profile, physicsCount);
        config.put("normalizedSchemaVersion", 0);
        config.put("renderDprCap", runtime.optDouble("renderDprCap", 2.0));
        config.put("textureUploadYieldEvery", runtime.optInt("textureUploadYieldEvery", 2));
        config.put("textureEstimatedRgbaBytes", runtime.optLong("textureEstimatedRgbaBytes", 0));
        config.put("textureCount", runtime.optInt("textureCount", 0));
        config.put("physicsActiveFps", runtime.optInt("physicsActiveFps", 60));
        config.put("physicsIdleFps", runtime.optInt("physicsIdleFps", 30));
    }

    private static StyleInfo readInfo(File dir) {
        try {
            File metaFile = new File(dir, META_FILE);
            if (!metaFile.isFile()) return null;
            JSONObject meta = new JSONObject(readText(metaFile));
            String id = meta.optString("id", dir.getName());
            String name = meta.optString("name", dir.getName());
            String mode = meta.optString("mode", MODE_KEYBOARD);
            String format = meta.optString("format", FORMAT_MODERN);
            String renderer = meta.optString("renderer", RENDERER_LIVE2D);
            String modelFile = meta.optString("modelFile", "");

            if (!modelFile.isEmpty()) {
                File model = new File(dir, modelFile);
                if (!model.isFile() || !isInside(dir, model)) return null;
            } else if (!FORMAT_MVER_016.equals(format) || RENDERER_LIVE2D.equals(renderer)) {
                return null;
            }

            int designWidth = meta.optInt("designWidth", 0);
            int designHeight = meta.optInt("designHeight", 0);
            if (designWidth <= 0 || designHeight <= 0) {
                int[] detected = FORMAT_MVER_016.equals(format)
                        ? detectMverDesignSize(dir, new JSONObject())
                        : detectDesignSize(dir);
                designWidth = detected[0];
                designHeight = detected[1];
            }
            return new StyleInfo(id, name, mode, dir, modelFile, false,
                    format, renderer, designWidth, designHeight);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String newStyleId() {
        return "style_" + Long.toString(System.currentTimeMillis(), 36)
                + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void validateModel(File packageRoot, File modelFile) throws Exception {
        if (packageRoot == null || modelFile == null) throw new IOException("无效 model3 路径");
        File modelRoot = modelFile.getParentFile();
        if (modelRoot == null) throw new IOException("无效 model3 路径");
        requireInside(packageRoot, modelFile);
        JSONObject modelJson = parseMverJson(readText(modelFile));
        JSONObject refs = objectIgnoreCase(modelJson, "FileReferences", "fileReferences");
        if (refs == null) throw new IOException("model3 缺少 FileReferences");
        String moc = jsonString(refs, "", "Moc", "moc", "Model").trim();
        JSONArray textures = jsonArray(refs, "Textures", "textures");
        if (moc.isEmpty() || textures == null || textures.length() == 0) {
            throw new IOException("model3 缺少 Moc 或 Textures");
        }
        File mocFile = resolveRelativeIgnoreCase(modelRoot, moc);
        if (mocFile == null || !mocFile.isFile()) throw new IOException("model3 引用的 Moc 不存在: " + moc);
        requireInside(packageRoot, mocFile);
        for (int i = 0; i < textures.length(); i++) {
            String texture = String.valueOf(textures.opt(i)).trim();
            if (texture.isEmpty()) throw new IOException("无效纹理路径");
            File textureFile = resolveRelativeIgnoreCase(modelRoot, texture);
            if (textureFile == null || !textureFile.isFile()) throw new IOException("model3 引用的纹理不存在: " + texture);
            requireInside(packageRoot, textureFile);
        }
    }

    private static File findMverRoot(File root) {
        return findMverRoot(root, 0);
    }

    private static File findMverRoot(File dir, int depth) {
        if (dir == null || depth > 8) return null;
        File config = firstExisting(childIgnoreCase(dir, "config.json"), childIgnoreCase(dir, "config.json5"),
                childIgnoreCase(dir, "config.jsonc"));
        File standard = findMverStandardDir(dir);
        if (config != null && config.isFile() && standard != null && standard.isDirectory()) {
            try {
                JSONObject json = parseMverJson(readText(config));
                if (objectIgnoreCase(json, MODE_STANDARD) != null) return dir;
            } catch (Throwable ignored) {
            }
        }
        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) return null;
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            if ("__MACOSX".equalsIgnoreCase(child.getName())) continue;
            File match = findMverRoot(child, depth + 1);
            if (match != null) return match;
        }
        return null;
    }

    private static int[] detectMverDesignSize(File modeRoot, JSONObject packageConfig) {
        for (String stem : new String[]{
                "l2dmousebg", "l2dtabletbg", "mousebg", "tabletbg", "bg", "cat"}) {
            int[] size = imageSize(assetImageIgnoreCase(modeRoot, stem));
            if (size != null) return size;
        }
        try {
            JSONObject decoration = objectIgnoreCase(packageConfig, "decoration", "decortation");
            JSONArray window = decoration == null ? null : jsonArray(decoration, "window_size", "windowSize");
            if (window != null && window.length() >= 2) {
                int width = window.optInt(0, 0);
                int height = window.optInt(1, 0);
                if (width > 0 && height > 0) return new int[]{width, height};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{612, 354};
    }

    /** MOC3 stores its format generation in byte 4 after the ASCII "MOC3" header. */
    private static int readMocFormatVersion(File moc) {
        if (moc == null || !moc.isFile()) return 0;
        byte[] header = new byte[8];
        try (FileInputStream in = new FileInputStream(moc)) {
            int read = in.read(header);
            if (read < 5 || header[0] != 'M' || header[1] != 'O' || header[2] != 'C' || header[3] != '3') return 0;
            return header[4] & 0xFF;
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static String relativePath(File root, File file) throws IOException {
        if (!isInside(root, file)) throw new IOException("样式文件越界");
        String value = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        if (value.isEmpty()) throw new IOException("无效相对路径");
        return value;
    }

    /** Mver/Live2D packs are commonly authored on Windows/macOS: compare case and Unicode form. */
    private static File childIgnoreCase(File parent, String name) {
        if (parent == null || name == null || !parent.isDirectory()) return null;
        File exact = new File(parent, name);
        if (exact.exists()) return exact;
        File[] children = parent.listFiles();
        if (children == null) return null;
        String expected = Normalizer.normalize(name, Normalizer.Form.NFC);
        for (File child : children) {
            String candidate = Normalizer.normalize(child.getName(), Normalizer.Form.NFC);
            if (candidate.equalsIgnoreCase(expected)) return child;
        }
        return null;
    }

    private static File dirIgnoreCase(File parent, String name) {
        File file = childIgnoreCase(parent, name);
        return file != null && file.isDirectory() ? file : new File(parent == null ? new File(".") : parent, name);
    }

    private static File assetIgnoreCase(File parent, String name) {
        File file = childIgnoreCase(parent, name);
        return file != null && file.isFile() ? file : null;
    }

    private static File findMverStandardDir(File packageRoot) {
        if (packageRoot == null) return null;
        File img = childIgnoreCase(packageRoot, "img");
        File standard = childIgnoreCase(img, MODE_STANDARD);
        if (standard != null && standard.isDirectory()) return standard;
        // Some style-sharing sites re-pack only config + standard instead of the full portable tree.
        standard = childIgnoreCase(packageRoot, MODE_STANDARD);
        return standard != null && standard.isDirectory() ? standard : null;
    }

    private static File assetImageIgnoreCase(File parent, String stem) {
        return firstAssetIgnoreCase(parent, stem + ".png", stem + ".webp", stem + ".jpg", stem + ".jpeg");
    }

    /** Resolve every relative path segment case-insensitively while keeping traversal inside root. */
    private static File resolveRelativeIgnoreCase(File root, String relative) throws IOException {
        if (root == null || relative == null) return null;
        String clean = Uri.decode(relative).replace('\\', '/').trim();
        if (clean.isEmpty() || clean.startsWith("/") || clean.contains("../") || clean.equals("..")) return null;
        File current = root;
        for (String part : clean.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) return null;
            current = childIgnoreCase(current, part);
            if (current == null) return null;
        }
        requireInside(root, current);
        return current;
    }

    private static JSONObject objectIgnoreCase(JSONObject object, String... names) {
        Object value = valueIgnoreCase(object, names);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    private static Object valueIgnoreCase(JSONObject object, String... names) {
        if (object == null || names == null) return null;
        for (String name : names) {
            if (name == null) continue;
            if (object.has(name)) return object.opt(name);
        }
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String candidate = keys.next();
            for (String name : names) {
                if (name != null && candidate.equalsIgnoreCase(name)) return object.opt(candidate);
            }
        }
        return null;
    }

    private static JSONArray jsonArray(JSONObject object, String... names) {
        Object value = valueIgnoreCase(object, names);
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    private static String jsonString(JSONObject object, String fallback, String... names) {
        Object value = valueIgnoreCase(object, names);
        if (value == null || value == JSONObject.NULL) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean coerceBoolean(Object value, boolean fallback) {
        if (value == null || value == JSONObject.NULL) return fallback;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0.0;
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("yes") || text.equals("on") || text.equals("1")) return true;
        if (text.equals("false") || text.equals("no") || text.equals("off") || text.equals("0")) return false;
        return fallback;
    }

    private static boolean jsonBoolean(JSONObject object, boolean fallback, String... names) {
        return coerceBoolean(valueIgnoreCase(object, names), fallback);
    }

    private static double jsonDouble(JSONObject object, double fallback, String... names) {
        Object value = valueIgnoreCase(object, names);
        if (value == null || value == JSONObject.NULL) return fallback;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? number : fallback;
        }
        try {
            double number = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(number) ? number : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int jsonInt(JSONObject object, int fallback, String... names) {
        double value = jsonDouble(object, fallback, names);
        if (!Double.isFinite(value) || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) return fallback;
        return (int) Math.round(value);
    }

    /** Normalize community Mver binding variants: [[17,49]], [49], 49, or {"0":[49]}. */
    private static JSONArray mverBindingMatrix(JSONObject object, String... names) {
        Object raw = valueIgnoreCase(object, names);
        JSONArray result = new JSONArray();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) result.put(mverKeyRow(array.opt(i)));
            return result;
        }
        if (raw instanceof JSONObject) {
            JSONObject map = (JSONObject) raw;
            java.util.ArrayList<String> keys = new java.util.ArrayList<>();
            java.util.Iterator<String> iterator = map.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            keys.sort((a, b) -> {
                try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
                catch (Throwable ignored) { return a.compareToIgnoreCase(b); }
            });
            for (String key : keys) result.put(mverKeyRow(map.opt(key)));
            return result;
        }
        if (raw != null && raw != JSONObject.NULL) result.put(mverKeyRow(raw));
        return result;
    }

    private static JSONArray mverKeyRow(Object raw) {
        JSONArray result = new JSONArray();
        if (raw == null || raw == JSONObject.NULL) return result;
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) appendMverKeyToken(result, array.opt(i));
            return result;
        }
        appendMverKeyToken(result, raw);
        return result;
    }

    private static void appendMverKeyToken(JSONArray out, Object raw) {
        if (raw == null || raw == JSONObject.NULL) return;
        if (raw instanceof Number) { out.put(((Number) raw).intValue()); return; }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return;
        // JSON5/community configs sometimes store chords as "17+49" or "0x11,0x31".
        for (String token : text.split("[+,\\s;|]+")) {
            if (token.isEmpty()) continue;
            try {
                int value = token.regionMatches(true, 0, "0x", 0, 2)
                        ? Integer.parseInt(token.substring(2), 16) : Integer.parseInt(token);
                out.put(value);
            } catch (Throwable ignored) {
            }
        }
    }

    private static JSONArray live2dGroupIds(JSONObject modelJson, String groupName) {
        JSONArray result = new JSONArray();
        JSONArray groups = jsonArray(modelJson, "Groups", "groups");
        if (groups == null) return result;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            String name = jsonString(group, "", "Name", "name");
            String target = jsonString(group, "Parameter", "Target", "target");
            if (!groupName.equalsIgnoreCase(name) || !"Parameter".equalsIgnoreCase(target)) continue;
            JSONArray ids = jsonArray(group, "Ids", "ids");
            if (ids == null) continue;
            for (int k = 0; k < ids.length(); k++) {
                String id = String.valueOf(ids.opt(k)).trim();
                if (!id.isEmpty() && seen.add(id)) result.put(id);
            }
        }
        return result;
    }

    /**
     * Mver configs in the wild are frequently Windows-authored JSON5 rather than strict JSON.
     * Android JsonReader lenient mode accepts comments, single quotes, unquoted names and trailing
     * separators without executing any package code.
     */
    private static JSONObject parseMverJson(String raw) throws Exception {
        if (raw == null) throw new IOException("JSON 内容为空");
        String input = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        try (JsonReader reader = new JsonReader(new StringReader(input))) {
            reader.setLenient(true);
            Object value = readLenientJsonValue(reader);
            if (!(value instanceof JSONObject)) throw new IOException("Mver JSON 根节点不是对象");
            return (JSONObject) value;
        }
    }

    private static Object readLenientJsonValue(JsonReader reader) throws Exception {
        JsonToken token = reader.peek();
        return switch (token) {
            case BEGIN_OBJECT -> {
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) object.put(reader.nextName(), readLenientJsonValue(reader));
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) array.put(readLenientJsonValue(reader));
                reader.endArray();
                yield array;
            }
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> { reader.nextNull(); yield JSONObject.NULL; }
            case NUMBER -> {
                String number = reader.nextString();
                try {
                    if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0)
                        yield Double.parseDouble(number);
                    yield Long.parseLong(number);
                } catch (NumberFormatException ignored) {
                    yield Double.parseDouble(number);
                }
            }
            case STRING -> reader.nextString();
            default -> throw new IOException("不支持的 JSON token: " + token);
        };
    }

    /**
     * Prefer an explicitly selected Mver Live2D model, then the canonical cat_model fallback.
     * Newer community packs may keep a model library at live2d_models/<name>/ and select one
     * through standard.live2d_model; blindly preferring cat_model loads the wrong character.
     */
    private static File findMverModelFile(File root, JSONObject modeConfig) {
        List<File> matches = new ArrayList<>();
        collectModels(root, root, matches, 0);
        final String preferred = jsonString(modeConfig, "",
                "live2d_model", "live2dModel", "model_name", "modelName").trim();
        matches.sort(Comparator
                .comparingInt((File f) -> mverModelScore(root, f, preferred))
                .thenComparingInt(f -> relativeDepth(root, f))
                .thenComparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER));
        for (File file : matches) {
            try {
                validateModel(file.getParentFile(), file);
                return file;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int mverModelScore(File root, File file, String preferredModel) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        String path;
        try { path = root.toPath().relativize(file.toPath()).toString().replace('\\', '/').toLowerCase(Locale.ROOT); }
        catch (Throwable ignored) { path = file.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.ROOT); }
        int score = 20;
        String preferred = preferredModel == null ? "" : preferredModel.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (preferred.startsWith("./")) preferred = preferred.substring(2);
        while (preferred.startsWith("/")) preferred = preferred.substring(1);
        if (!preferred.isEmpty()) {
            // Accept a selected library folder (nuannuan), relative folder path, or an explicit
            // model3 filename. Segment checks avoid accidentally matching names such as cat2.
            if (path.equals(preferred) || path.endsWith("/" + preferred)) score -= 140;
            if (path.startsWith(preferred + "/") || path.contains("/" + preferred + "/")) score -= 120;
            String libraryNeedle = "live2d_models/" + preferred + "/";
            if (path.startsWith(libraryNeedle) || path.contains("/" + libraryNeedle)) score -= 160;
            if (name.equals(preferred) || (preferred.endsWith(".model3.json") && path.endsWith(preferred))) score -= 160;
        }
        if ("cat.model3.json".equals(name)) score -= 10;
        if (path.contains("/cat_model/") || path.startsWith("cat_model/")) score -= 6;
        if (path.contains("backup") || path.contains("copy") || path.contains("old")
                || path.contains("bak/")) score += 24;
        return score;
    }

    private static File firstAssetIgnoreCase(File parent, String... names) {
        if (parent == null || names == null || !parent.isDirectory()) return null;
        for (String name : names) {
            File file = assetIgnoreCase(parent, name);
            if (file != null) return file;
        }
        return null;
    }

    private static File firstExisting(File... files) {
        if (files == null) return null;
        for (File file : files) {
            if (file != null && file.isFile()) return file;
        }
        return null;
    }

    private static File findModelFile(File root) {
        List<File> matches = new ArrayList<>();
        collectModels(root, root, matches, 0);
        if (matches.isEmpty()) return null;
        matches.sort(Comparator.comparingInt((File f) -> relativeDepth(root, f)).thenComparing(File::getAbsolutePath));
        return matches.get(0);
    }

    private static void collectModels(File root, File dir, List<File> out, int depth) {
        if (depth > 8) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                if (!"__MACOSX".equals(file.getName())) collectModels(root, file, out, depth + 1);
            } else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".model3.json")) {
                out.add(file);
            }
        }
    }

    private static int relativeDepth(File root, File file) {
        try {
            String rel = root.toPath().relativize(file.toPath()).toString();
            return rel.isEmpty() ? 0 : rel.split("[/\\\\]").length;
        } catch (Throwable ignored) {
            return 99;
        }
    }

    /**
     * Windows-authored Mver packs are not guaranteed to use UTF-8 entry names. Retry common
     * legacy encodings from a disk-backed archive while preserving all extraction safety limits.
     */
    private static void extractZipArchive(File archive, File target) throws IOException {
        List<Charset> charsets = new ArrayList<>();
        charsets.add(StandardCharsets.UTF_8);
        for (String name : new String[]{"GBK", "Shift_JIS", "IBM437"}) {
            try {
                if (Charset.isSupported(name)) charsets.add(Charset.forName(name));
            } catch (Throwable ignored) {
            }
        }
        IOException last = null;
        for (Charset charset : charsets) {
            deleteTree(target);
            if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Cannot create import payload directory");
            try {
                extractZipFile(archive, target, charset);
                return;
            } catch (IOException | IllegalArgumentException error) {
                last = error instanceof IOException ? (IOException) error
                        : new IOException("ZIP filename decode failed: " + charset.name(), error);
            }
        }
        deleteTree(target);
        throw last == null ? new IOException("Cannot extract style archive") : last;
    }

    private static void extractZipFile(File archive, File target, Charset charset) throws IOException {
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipFile zip = new ZipFile(archive, charset)) {
            preflightZipExtraction(zip, target);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) throw new IOException("样式文件过多");
                String rawName = entry.getName().replace('\\', '/');
                if (rawName.isEmpty() || rawName.startsWith("/") || rawName.contains("../")
                        || rawName.indexOf('\u0000') >= 0) {
                    throw new IOException("样式包含非法路径");
                }
                if (rawName.contains("/__MACOSX/") || rawName.startsWith("__MACOSX/")
                        || rawName.endsWith("/.DS_Store") || rawName.endsWith(".DS_Store")) continue;
                File out = new File(target, rawName);
                if (!isInside(target, out)) throw new IOException("样式包含非法路径");
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) throw new IOException("Cannot create directory");
                    continue;
                }
                long declared = entry.getSize();
                if (declared > MAX_SINGLE_FILE_BYTES) throw new IOException("样式中的单个文件不能超过 512 MB");
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create parent directory");
                long fileBytes = 0;
                try (InputStream in = zip.getInputStream(entry); FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        fileBytes += n;
                        total += n;
                        if (fileBytes > MAX_SINGLE_FILE_BYTES) throw new IOException("样式中的单个文件不能超过 512 MB");
                        if (total > MAX_EXTRACTED_BYTES) throw new IOException("样式解压后的资源总量超过 1.5 GB");
                        fos.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    private static void preflightZipExtraction(ZipFile zip, File target) throws IOException {
        long declaredTotal = 0L;
        int declaredEntries = 0;
        Enumeration<? extends ZipEntry> scan = zip.entries();
        while (scan.hasMoreElements()) {
            ZipEntry entry = scan.nextElement();
            if (++declaredEntries > MAX_ENTRIES) throw new IOException("样式文件过多");
            if (entry.isDirectory()) continue;
            long size = entry.getSize();
            if (size > MAX_SINGLE_FILE_BYTES) throw new IOException("样式中的单个文件不能超过 512 MB");
            if (size > 0L) {
                declaredTotal += size;
                if (declaredTotal > MAX_EXTRACTED_BYTES) throw new IOException("样式解压后的资源总量超过 1.5 GB");
            }
        }
        if (declaredTotal > 0L) ensureUsableStorage(target, declaredTotal);
    }

    private static void ensureUsableStorage(File target, long expectedBytes) throws IOException {
        File probe = target == null ? null : target.getParentFile();
        if (probe == null) return;
        long usable = probe.getUsableSpace();
        if (usable <= 0L) return; // Some providers/filesystems do not report it reliably.
        long required;
        try { required = Math.addExact(expectedBytes, IMPORT_STORAGE_RESERVE_BYTES); }
        catch (ArithmeticException ignored) { required = Long.MAX_VALUE; }
        if (usable < required) {
            long needMb = (required + 1024L * 1024L - 1L) / (1024L * 1024L);
            long freeMb = usable / (1024L * 1024L);
            throw new IOException("存储空间不足：大型样式预计至少需要 " + needMb + " MB，可用约 " + freeMb + " MB");
        }
    }

    private static void materializeStyleTree(File from, File to) throws IOException {
        if (from == null || !from.exists()) throw new IOException("样式源目录不存在");
        if (to.exists()) throw new IOException("样式目标目录已存在");
        File parent = to.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create style directory");
        // Import temp/payload and the final style directory live under the same app files volume.
        // Prefer a metadata-only rename so very large packs are not copied a second time.
        if (from.renameTo(to)) return;
        if (!to.mkdirs() && !to.isDirectory()) throw new IOException("Cannot create target style directory");
        try {
            copyTree(from, to);
        } catch (IOException error) {
            deleteTree(to);
            throw error;
        }
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.exists() && !to.mkdirs()) throw new IOException("Cannot create style directory");
            File[] children = from.listFiles();
            if (children != null) for (File child : children) copyTree(child, new File(to, child.getName()));
            return;
        }
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private static String cleanDisplayName(String value) {
        String name = value == null ? "自定义样式" : value.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length() - 4);
        name = name.replaceAll("\\s*[·•-]\\s*(键盘模式|手柄模式|鼠标模式|标准模式)$", "").trim();
        if (name.isEmpty()) name = "自定义样式";
        if (name.length() > 48) name = name.substring(0, 48);
        return name;
    }

    private static String cleanMverDisplayName(String displayName, String packageDirectory) {
        String name = cleanDisplayName(displayName);
        name = name.replaceFirst("(?i)[_\\s-]*bongo[_\\s-]*cat[_\\s-]*mver.*$", "").trim();
        if (name.isEmpty() && packageDirectory != null) {
            name = packageDirectory.replaceFirst("(?i)[_\\s-]*bongo[_\\s-]*cat[_\\s-]*mver.*$", "").trim();
        }
        if (name.isEmpty()) name = "Mver 样式";
        if (name.length() > 48) name = name.substring(0, 48);
        return name;
    }

    private static File baseDir(Context context) {
        return new File(context.getFilesDir(), STYLE_DIR);
    }

    private static String ensureSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    private static void requireInside(File root, File child) throws IOException {
        if (!isInside(root, child) || !child.exists()) throw new IOException("样式引用文件不存在或越界: " + child.getName());
    }

    private static boolean isInside(File root, File child) {
        try {
            String rootPath = root.getCanonicalPath();
            String childPath = child.getCanonicalPath();
            return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String readText(File file) throws IOException {
        byte[] data = readBytesBounded(file, MAX_TEXT_FILE_BYTES, "样式 JSON/配置文件超过 32 MB");
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File file) throws IOException {
        // Only small MOC3 files take the inline base64 path. Large MOC3/texture/media files stay
        // file-backed, which is essential for 100+ MB community packs.
        return readBytesBounded(file, Math.max(MAX_INLINE_MOC_BYTES, 16L * 1024L * 1024L),
                "需要内联读取的模型资源过大");
    }

    private static byte[] readBytesBounded(File file, long limit, String errorMessage) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("资源文件不存在");
        long declared = file.length();
        if (declared > limit) throw new IOException(errorMessage);
        int initial = (int) Math.min(Math.max(0L, declared), 256L * 1024L);
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream(initial)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > limit) throw new IOException(errorMessage);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * 对压缩包原始输入流计数。即使 SAF Provider 不报告长度，也能保证导入上限为 512 MiB。
     */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        private long readBytes;

        LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) account(count);
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = delegate.skip(count);
            if (skipped > 0) account(skipped);
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void account(long count) throws IOException {
            readBytes += count;
            if (readBytes > limit) throw new IOException("样式压缩包不能超过 512 MB");
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
