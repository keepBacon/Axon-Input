package com.axon.input;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.BitmapFactory;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
    /** 用户选择的 ZIP 本体最大允许 100 MiB。 */
    private static final long MAX_IMPORT_ZIP_BYTES = 100L * 1024L * 1024L;
    /** 解压后的资源总量单独设限，既允许高压缩比素材，也阻止 ZIP bomb。 */
    private static final long MAX_EXTRACTED_BYTES = 300L * 1024L * 1024L;
    /** 单个模型/纹理资源最多 100 MiB。 */
    private static final long MAX_SINGLE_FILE_BYTES = 100L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 900;

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
            String suffix = switch (mode) {
                case MODE_GAMEPAD -> "手柄";
                case MODE_STANDARD -> FORMAT_MVER_016.equals(format) ? "标准" : "鼠标";
                default -> "键盘";
            };
            return name + " · " + suffix;
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
        return result;
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
        StyleInfo info = selected(context);
        return !info.builtin && MODE_GAMEPAD.equals(info.mode);
    }

    public static StyleInfo importZip(Context context, Uri uri, String displayName) throws Exception {
        File base = baseDir(context);
        if (!base.exists() && !base.mkdirs()) throw new IOException("Cannot create style directory");
        File temp = new File(base, ".import-" + UUID.randomUUID());
        if (!temp.mkdirs()) throw new IOException("Cannot create import directory");
        try {
            // ContentProvider 能给出文件长度时先快速拒绝；部分云盘 Provider 长度未知，
            // 后续仍由 LimitedInputStream 对真实读取字节数做 100 MiB 硬限制。
            try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd != null) {
                    long length = afd.getLength();
                    if (length > MAX_IMPORT_ZIP_BYTES) {
                        throw new IOException("样式压缩包不能超过 100 MB");
                    }
                }
            }
            // Materialize the archive once so legacy Windows ZIP filename encodings can be retried
            // without reopening SAF/cloud streams. This is still bounded by the same 100 MiB limit.
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

            // Bongo Cat Mver 分享包是完整可携版。Axon 只导入 standard 的逻辑资源，
            // 但 standard 内的键盘、手部、表情、声音、鼠标/数位板和 Live2D 资源会完整保留。
            File mverRoot = findMverRoot(extracted);
            if (mverRoot != null) {
                return importMverPackage(base, mverRoot, displayName);
            }
            return importModernPackage(base, extracted, displayName);
        } finally {
            deleteTree(temp);
        }
    }

    private static StyleInfo importModernPackage(File base, File temp, String displayName) throws Exception {
        File model = findModelFile(temp);
        if (model == null) throw new IOException("未找到 .model3.json");
        File sourceRoot = model.getParentFile();
        if (sourceRoot == null) throw new IOException("无效样式目录");

        validateModel(sourceRoot, model);
        List<String> left = keyNames(new File(sourceRoot, "resources/left-keys"));
        List<String> right = keyNames(new File(sourceRoot, "resources/right-keys"));
        String mode = detectMode(left, right);
        String id = newStyleId();
        File target = new File(base, id);
        if (!target.mkdirs()) throw new IOException("Cannot create target style directory");
        try {
            copyTree(sourceRoot, target);
            String cleanName = cleanDisplayName(displayName);
            JSONObject meta = new JSONObject();
            meta.put("id", id);
            meta.put("name", cleanName);
            meta.put("mode", mode);
            meta.put("format", FORMAT_MODERN);
            meta.put("renderer", RENDERER_LIVE2D);
            meta.put("modelFile", model.getName());
            int[] designSize = detectDesignSize(sourceRoot);
            meta.put("designWidth", designSize[0]);
            meta.put("designHeight", designSize[1]);
            writeText(new File(target, META_FILE), meta.toString());
            StyleInfo imported = readInfo(target);
            if (imported == null) throw new IOException("导入后校验失败");
            return imported;
        } catch (Exception error) {
            deleteTree(target);
            throw error;
        }
    }

    /**
     * Mver 导入只保留 standard。一个分享包在 Axon 中始终对应一个样式，避免
     * keyboard/gamepad 分支产生重复条目与不同的合成行为。导入时只复制 img/standard，
     * EXE/DLL、其它模式资源和程序文件都不会进入应用样式目录。
     */
    private static StyleInfo importMverPackage(File base, File packageRoot, String displayName) throws Exception {
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
        if (useLive2d) validateModel(sourceModel.getParentFile(), sourceModel);

        String id = newStyleId();
        File target = new File(base, id);
        if (!target.mkdirs()) throw new IOException("Cannot create target style directory");
        try {
            copyTree(source, target);
            // Keep the original Mver config beside the normalized standard assets. GitHub's
            // l2dcat converter also preserves behavior metadata separately from the converted
            // images; doing the same lets future runtimes rebuild bindings without asking the
            // user to recover the original executable package.
            writeText(new File(target, MVER_SOURCE_CONFIG_FILE), config.toString());
            String modelRelative = sourceModel == null ? "" : relativePath(source, sourceModel);

            JSONObject meta = new JSONObject();
            meta.put("id", id);
            meta.put("name", cleanMverDisplayName(displayName, packageRoot.getName()));
            meta.put("mode", MODE_STANDARD);
            meta.put("format", FORMAT_MVER_016);
            meta.put("renderer", useLive2d ? RENDERER_LIVE2D : RENDERER_SPRITE);
            meta.put("modelFile", modelRelative);
            meta.put("mverConfig", modeConfig);
            meta.put("mverDecoration", decoration);
            JSONObject workarea = objectIgnoreCase(config, "workarea", "workArea");
            meta.put("mverWorkarea", workarea == null ? new JSONObject() : workarea);
            meta.put("mverSourceMode", jsonInt(config, 1, "mode"));
            int[] designSize = detectMverDesignSize(source, config);
            meta.put("designWidth", designSize[0]);
            meta.put("designHeight", designSize[1]);
            writeText(new File(target, META_FILE), meta.toString());

            StyleInfo imported = readInfo(target);
            if (imported == null) throw new IOException("Mver standard 模式导入后校验失败");
            return imported;
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
        if (id.equals(OverlayState.getKeyboardCatStyleId(context))) {
            OverlayState.setKeyboardCatStyleId(context, BUILTIN_ID);
        }
        return !dir.exists();
    }

    /** 生成通用 WebView runtime 所需配置。 */
    public static JSONObject runtimeConfig(StyleInfo info) throws Exception {
        if (info == null || info.builtin || info.root == null) throw new IOException("Not an imported style");
        if (FORMAT_MVER_016.equals(info.format)) return runtimeConfigMver(info);
        return runtimeConfigModern(info);
    }

    private static JSONObject runtimeConfigModern(StyleInfo info) throws Exception {
        File modelFile = new File(info.root, info.modelFile);
        requireInside(info.root, modelFile);
        File modelRoot = modelFile.getParentFile();
        if (modelRoot == null) throw new IOException("无效 model3 路径");

        File resources = new File(info.root, "resources");
        File background = new File(resources, "background.png");
        File cover = new File(resources, "cover.png");
        List<String> left = keyNames(new File(resources, "left-keys"));
        List<String> right = keyNames(new File(resources, "right-keys"));

        JSONObject config = baseRuntimeConfig(info);
        config.put("format", FORMAT_MODERN);
        config.put("spriteMode", false);
        config.put("background", fileUri(background));
        config.put("cover", fileUri(cover));
        config.put("leftKeys", new JSONArray(left));
        config.put("rightKeys", new JSONArray(right));
        appendLive2dConfig(config, modelRoot, modelFile);
        return config;
    }

    private static JSONObject runtimeConfigMver(StyleInfo info) throws Exception {
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
        boolean useLive2d = RENDERER_LIVE2D.equals(info.renderer)
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
        return config;
    }

    private static void appendLive2dConfig(JSONObject config, File modelRoot, File modelFile) throws Exception {
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
                    expressions.put(parseMverJson(readText(file)));
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
        // Keep unregistered Motion3 files available for modern packages, but do not bind
        // Mver l2d_motion keys to them: Mver 0.1.6 uses CAT_motion/CAT_motion_lock groups.
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

        Object physics = JSONObject.NULL;
        String physicsRelative = jsonString(refs, "", "Physics", "physics").trim();
        if (!physicsRelative.isEmpty()) {
            File physicsFile = resolveRelativeIgnoreCase(modelRoot, physicsRelative);
            if (physicsFile != null) {
                requireInside(modelRoot, physicsFile);
                if (physicsFile.isFile()) physics = parseMverJson(readText(physicsFile));
            }
        }

        Object pose = JSONObject.NULL;
        String poseRelative = jsonString(refs, "", "Pose", "pose").trim();
        if (!poseRelative.isEmpty()) {
            File poseFile = resolveRelativeIgnoreCase(modelRoot, poseRelative);
            if (poseFile != null) {
                requireInside(modelRoot, poseFile);
                if (poseFile.isFile()) pose = parseMverJson(readText(poseFile));
            }
        }

        JSONArray eyeBlinkIds = live2dGroupIds(modelJson, "EyeBlink");
        JSONArray lipSyncIds = live2dGroupIds(modelJson, "LipSync");
        JSONObject layout = objectIgnoreCase(modelJson, "Layout", "layout");

        config.put("textures", textures);
        config.put("expressions", expressions);
        config.put("motions", motions);
        config.put("motionGroups", motionGroups);
        config.put("physics", physics);
        config.put("pose", pose);
        config.put("eyeBlinkIds", eyeBlinkIds);
        config.put("lipSyncIds", lipSyncIds);
        config.put("modelLayout", layout == null ? new JSONObject() : layout);
        config.put("mocBase64", Base64.encodeToString(readBytes(moc), Base64.NO_WRAP));
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
     * 获取样式的合成画布尺寸。BongoCat 样式的 background/key overlays 都以同一画布导出，
     * 因此 background.png 是最稳定的布局基准。只读取图片头，不解码高分辨率像素。
     */
    private static int[] detectDesignSize(File sourceRoot) {
        int[] background = imageSize(new File(sourceRoot, "resources/background.png"));
        if (background != null) return background;
        int[] cover = imageSize(new File(sourceRoot, "resources/cover.png"));
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

    private static String detectMode(List<String> left, List<String> right) {
        // 与 BongoCat 源的模型模式判定保持一致：standard 也包含 left-keys，
        // 因此不能仅凭“存在按键贴图”判断成 keyboard。
        if (right.contains("East") || right.contains("South") || right.contains("North") || right.contains("West")) {
            return MODE_GAMEPAD;
        }
        if (!right.isEmpty()) return MODE_KEYBOARD;
        return MODE_STANDARD;
    }

    private static List<String> keyNames(File dir) {
        List<String> result = new ArrayList<>();
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

    private static void validateModel(File modelRoot, File modelFile) throws Exception {
        if (modelRoot == null || modelFile == null) throw new IOException("无效 model3 路径");
        requireInside(modelRoot, modelFile);
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
        requireInside(modelRoot, mocFile);
        for (int i = 0; i < textures.length(); i++) {
            String texture = String.valueOf(textures.opt(i)).trim();
            if (texture.isEmpty()) throw new IOException("无效纹理路径");
            File textureFile = resolveRelativeIgnoreCase(modelRoot, texture);
            if (textureFile == null || !textureFile.isFile()) throw new IOException("model3 引用的纹理不存在: " + texture);
            requireInside(modelRoot, textureFile);
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

    private static String relativePath(File root, File file) throws IOException {
        if (!isInside(root, file)) throw new IOException("样式文件越界");
        String value = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        if (value.isEmpty()) throw new IOException("无效相对路径");
        return value;
    }

    /** Mver packages are commonly authored on Windows, so asset lookup must be case-insensitive. */
    private static File childIgnoreCase(File parent, String name) {
        if (parent == null || name == null || !parent.isDirectory()) return null;
        File exact = new File(parent, name);
        if (exact.exists()) return exact;
        File[] children = parent.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.getName().equalsIgnoreCase(name)) return child;
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
        String clean = relative.replace('\\', '/').trim();
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
                if (declared > MAX_SINGLE_FILE_BYTES) throw new IOException("样式中的单个文件不能超过 100 MB");
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create parent directory");
                long fileBytes = 0;
                try (InputStream in = zip.getInputStream(entry); FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        fileBytes += n;
                        total += n;
                        if (fileBytes > MAX_SINGLE_FILE_BYTES) throw new IOException("样式中的单个文件不能超过 100 MB");
                        if (total > MAX_EXTRACTED_BYTES) throw new IOException("样式解压后的资源总量超过 300 MB");
                        fos.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    private static void extractZip(InputStream input, File target) throws IOException {
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IOException("样式文件过多");
                String rawName = entry.getName().replace('\\', '/');
                if (rawName.isEmpty() || rawName.startsWith("/") || rawName.contains("../")) {
                    throw new IOException("样式包含非法路径");
                }
                if (rawName.contains("/__MACOSX/") || rawName.endsWith("/.DS_Store") || rawName.endsWith(".DS_Store")) {
                    zip.closeEntry();
                    continue;
                }
                File out = new File(target, rawName);
                if (!isInside(target, out)) throw new IOException("样式包含非法路径");
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) throw new IOException("Cannot create directory");
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create parent directory");
                    long fileBytes = 0;
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zip.read(buffer)) > 0) {
                            fileBytes += n;
                            total += n;
                            if (fileBytes > MAX_SINGLE_FILE_BYTES) {
                                throw new IOException("样式中的单个文件不能超过 100 MB");
                            }
                            if (total > MAX_EXTRACTED_BYTES) {
                                throw new IOException("样式解压后的资源总量超过 300 MB");
                            }
                            fos.write(buffer, 0, n);
                        }
                    }
                }
                zip.closeEntry();
            }
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
        name = name.replaceAll("\\s*[·•-]\\s*(键盘模式|手柄模式|鼠标模式)$", "").trim();
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
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                if ((long) out.size() + n > MAX_SINGLE_FILE_BYTES) throw new IOException("样式中的单个文件不能超过 100 MB");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * 对压缩包原始输入流计数。即使 SAF Provider 不报告长度，也能保证导入上限为 100 MiB。
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
            if (readBytes > limit) throw new IOException("样式压缩包不能超过 100 MB");
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
