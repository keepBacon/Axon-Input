package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Live2D display model storage.
 *
 * Only data files declared by model3 are rendered. Imported HTML/JS is never executed.
 * The feature intentionally keeps one current model: importing another package atomically
 * replaces the previous model, matching the single "导入模型" debug control.
 */
public final class Live2DModelStore {
    private static final String PREFS = "live2d_display_store";
    private static final String KEY_VERSION = "current_version";
    private static final String KEY_MODEL_RELATIVE = "current_model_relative";
    private static final String KEY_NAME = "current_name";

    private static final String BASE_DIR = "live2d_display";
    private static final String CURRENT_DIR = "current";
    private static final String STAGING_PREFIX = "staging_";

    /** Large community Live2D archives often bundle multiple 4K/8K atlases, motions and audio. */
    private static final long MAX_ZIP_BYTES = 512L * 1024L * 1024L;
    /** Expanded data stays bounded independently to protect against ZIP bombs. */
    private static final long MAX_EXTRACTED_BYTES = 1536L * 1024L * 1024L;
    private static final long MAX_SINGLE_FILE_BYTES = 512L * 1024L * 1024L;
    /** model3/physics/expression metadata is parsed into Java heap; keep text JSON heap-safe. */
    private static final long MAX_JSON_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 5000;
    private static final long IMPORT_STORAGE_RESERVE_BYTES = 128L * 1024L * 1024L;
    /** Per-atlas mobile WebGL compatibility ceiling; aggregate texture memory is budgeted separately. */
    private static final int MAX_TEXTURE_EDGE = 4096;
    /** Keep the aggregate decoded RGBA atlas budget sane on Android WebView/GPU. */
    private static final long MAX_TOTAL_TEXTURE_PIXELS = 48L * 1024L * 1024L;
    private static final AtomicBoolean IMPORT_IN_FLIGHT = new AtomicBoolean(false);

    private Live2DModelStore() {}

    public static final class ImportResult {
        public final String name;
        public final String modelRelative;
        public final int adaptedTextures;

        ImportResult(String name, String modelRelative, int adaptedTextures) {
            this.name = name;
            this.modelRelative = modelRelative;
            this.adaptedTextures = adaptedTextures;
        }
    }

    public static boolean exists(Context context) {
        File root = currentDir(context);
        String relative = prefs(context).getString(KEY_MODEL_RELATIVE, "");
        if (relative == null || relative.isEmpty()) return false;
        try {
            File model = safeResolve(root, relative);
            return model.isFile();
        } catch (IOException ignored) {
            return false;
        }
    }

    public static String getName(Context context) {
        return prefs(context).getString(KEY_NAME, "");
    }

    public static String getVersion(Context context) {
        return prefs(context).getString(KEY_VERSION, "");
    }

    static BongoCatStyleManager.StyleInfo runtimeStyle(Context context) throws IOException {
        File root = currentDir(context);
        String relative = prefs(context).getString(KEY_MODEL_RELATIVE, "");
        if (relative == null || relative.isEmpty()) throw new IOException("尚未导入 Live2D 模型");
        File model = safeResolve(root, relative);
        if (!model.isFile()) throw new IOException("Live2D model3 文件不存在");
        String name = getName(context);
        if (name == null || name.trim().isEmpty()) name = model.getName();
        // The renderer fits the actual Cubism canvas. These design dimensions are only fallback
        // values when a malformed moc omits canvas metadata.
        return new BongoCatStyleManager.StyleInfo(
                "live2d-current", name, BongoCatStyleManager.MODE_STANDARD,
                root, relative, false, "modern", "live2d", 1080, 1920);
    }


    static List<BongoCatStyleManager.PhysicsGroupOption> physicsGroups(Context context) {
        try { return BongoCatStyleManager.physicsGroups(runtimeStyle(context)); }
        catch (Throwable ignored) { return new ArrayList<>(); }
    }

    /** Debug-visible Cubism parameters for the standalone Live2D model. */
    static List<BongoCatStyleManager.ParameterOption> parameterOptions(Context context) {
        ArrayList<BongoCatStyleManager.ParameterOption> result = new ArrayList<>();
        try {
            JSONObject config = BongoCatStyleManager.runtimeConfigLive2DDisplay(runtimeStyle(context));
            JSONArray definitions = config.optJSONArray("parameterDefinitions");
            if (definitions == null) return result;
            for (int i = 0; i < definitions.length(); i++) {
                JSONObject item = definitions.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "").trim();
                if (id.isEmpty()) continue;
                result.add(new BongoCatStyleManager.ParameterOption(
                        id, item.optString("name", id), item.optString("group", ""),
                        item.optString("groupId", ""), item.optString("category", "advanced"),
                        item.optString("defaultTrigger", "toggle"), item.optString("keySemantic", "")));
            }
        } catch (Throwable ignored) {}
        return result;
    }

    /** Expressions keep the exact model3 FileReferences index used by the JS runtime. */
    static List<BongoCatStyleManager.ExpressionOption> expressionOptions(Context context) {
        ArrayList<BongoCatStyleManager.ExpressionOption> result = new ArrayList<>();
        try {
            File root = currentDir(context);
            String relative = prefs(context).getString(KEY_MODEL_RELATIVE, "");
            if (relative == null || relative.isEmpty()) return result;
            File modelFile = safeResolve(root, relative);
            JSONObject model = readJson(modelFile);
            JSONObject refs = model.optJSONObject("FileReferences");
            JSONArray expressions = refs == null ? null : refs.optJSONArray("Expressions");
            if (expressions == null) return result;
            File modelRoot = modelFile.getParentFile();
            if (modelRoot == null) return result;
            for (int i = 0; i < expressions.length(); i++) {
                Object raw = expressions.opt(i);
                String expressionRelative = "";
                String name = "";
                if (raw instanceof JSONObject) {
                    JSONObject ref = (JSONObject) raw;
                    expressionRelative = ref.optString("File", "").trim();
                    name = ref.optString("Name", "").trim();
                } else if (raw instanceof String) {
                    expressionRelative = String.valueOf(raw).trim();
                }
                if (expressionRelative.isEmpty()) continue;
                File expressionFile = safeResolve(modelRoot, expressionRelative);
                requireInside(root, expressionFile);
                if (!expressionFile.isFile()) continue;
                if (name.isEmpty()) name = expressionFile.getName().replaceFirst("(?i)\\.exp3\\.json$", "");
                result.add(new BongoCatStyleManager.ExpressionOption(
                        "live2d:" + i, name, "live2d", i));
            }
        } catch (Throwable ignored) {}
        return result;
    }


    /**
     * Returns only model parameters explicitly labelled as a watermark by the model's DisplayInfo.
     * This never edits textures or imported files; the renderer can temporarily hold those declared
     * toggle parameters at their minimum when the user enables "隐藏水印".
     */
    static JSONArray watermarkParameterIds(Context context) {
        JSONArray result = new JSONArray();
        try {
            File root = currentDir(context);
            String relative = prefs(context).getString(KEY_MODEL_RELATIVE, "");
            if (relative == null || relative.isEmpty()) return result;
            File modelFile = safeResolve(root, relative);
            JSONObject model = readJson(modelFile);
            JSONObject refs = model.optJSONObject("FileReferences");
            String displayInfo = refs == null ? "" : refs.optString("DisplayInfo", "").trim();
            if (displayInfo.isEmpty()) return result;
            File modelRoot = modelFile.getParentFile();
            if (modelRoot == null) return result;
            File cdiFile = safeResolve(modelRoot, displayInfo);
            requireInside(root, cdiFile);
            JSONObject cdi = readJson(cdiFile);
            JSONArray parameters = cdi.optJSONArray("Parameters");
            if (parameters == null) return result;
            for (int i = 0; i < parameters.length(); i++) {
                JSONObject parameter = parameters.optJSONObject(i);
                if (parameter == null) continue;
                String id = parameter.optString("Id", "").trim();
                String name = parameter.optString("Name", "").trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty()) continue;
                if (name.contains("水印") || name.contains("watermark")) result.put(id);
            }
        } catch (Throwable ignored) {}
        return result;
    }

    /** Parts explicitly labelled as watermark in DisplayInfo. Hiding the actual Cubism part is
     * more robust than relying only on a parameter because many community models use an additive
     * expression to show the watermark while the part itself remains visible. */
    static JSONArray watermarkPartIds(Context context) {
        JSONArray result = new JSONArray();
        try {
            File root = currentDir(context);
            String relative = prefs(context).getString(KEY_MODEL_RELATIVE, "");
            if (relative == null || relative.isEmpty()) return result;
            File modelFile = safeResolve(root, relative);
            JSONObject model = readJson(modelFile);
            JSONObject refs = model.optJSONObject("FileReferences");
            String displayInfo = refs == null ? "" : refs.optString("DisplayInfo", "").trim();
            if (displayInfo.isEmpty()) return result;
            File modelRoot = modelFile.getParentFile();
            if (modelRoot == null) return result;
            File cdiFile = safeResolve(modelRoot, displayInfo);
            requireInside(root, cdiFile);
            JSONObject cdi = readJson(cdiFile);
            JSONArray parts = cdi.optJSONArray("Parts");
            if (parts == null) return result;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                String id = part.optString("Id", "").trim();
                String name = part.optString("Name", "").trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty()) continue;
                if (name.contains("水印") || name.contains("watermark")) result.put(id);
            }
        } catch (Throwable ignored) {}
        return result;
    }

    public static ImportResult importZip(Context context, Uri uri, String displayName) throws Exception {
        if (context == null || uri == null) throw new IOException("无效模型文件");
        if (!IMPORT_IN_FLIGHT.compareAndSet(false, true)) {
            throw new IOException("已有 Live2D 模型正在导入，请等待当前导入完成");
        }
        try {
            return importZipSingleFlight(context.getApplicationContext(), uri, displayName);
        } finally {
            IMPORT_IN_FLIGHT.set(false);
        }
    }

    private static ImportResult importZipSingleFlight(Context context, Uri uri, String displayName) throws Exception {
        File base = baseDir(context);
        if (!base.exists() && !base.mkdirs()) throw new IOException("无法创建 Live2D 目录");

        String token = UUID.randomUUID().toString().replace("-", "");
        File zipCopy = new File(base, "import_" + token + ".zip");
        File staging = new File(base, STAGING_PREFIX + token);
        File oldBackup = new File(base, "old_" + token);
        boolean replacementStarted = false;
        if (!staging.mkdirs()) throw new IOException("无法创建模型临时目录");

        try {
            copyUriBounded(context, uri, zipCopy, MAX_ZIP_BYTES);
            extractZip(zipCopy, staging);
            // Free the archive before texture adaptation and the atomic model swap. Large packs
            // otherwise keep hundreds of megabytes duplicated on the same internal volume.
            //noinspection ResultOfMethodCallIgnored
            zipCopy.delete();
            File modelFile = chooseModel3(staging);
            if (modelFile == null) throw new IOException("ZIP 中未找到 *.model3.json");

            JSONObject model = readJson(modelFile);
            validateModel3(staging, modelFile, model);
            injectVTubeStudioIdleIfPresent(staging, modelFile, model);
            writeJson(modelFile, model);
            boolean clearQuality = OverlayState.getLive2DRenderQuality(context) == OverlayState.RENDER_QUALITY_CLEAR;
            int adapted = adaptLargeTextures(staging, modelFile, model, clearQuality);
            // Texture adaptation does not change paths, but re-validate after all mutations.
            validateModel3(staging, modelFile, readJson(modelFile));

            String modelRelative = relativePath(staging, modelFile);
            File current = currentDir(context);
            replacementStarted = true;
            if (current.exists() && !current.renameTo(oldBackup)) {
                deleteTree(oldBackup);
                copyTree(current, oldBackup);
                deleteTree(current);
            }
            if (!staging.renameTo(current)) {
                copyTree(staging, current);
                deleteTree(staging);
            }

            String safeName = displayName == null ? "Live2D" : displayName.trim();
            if (safeName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                safeName = safeName.substring(0, safeName.length() - 4);
            }
            if (safeName.isEmpty()) safeName = modelFile.getName().replaceFirst("(?i)\\.model3\\.json$", "");

            prefs(context).edit()
                    .putString(KEY_VERSION, UUID.randomUUID().toString())
                    .putString(KEY_MODEL_RELATIVE, modelRelative)
                    .putString(KEY_NAME, safeName)
                    .apply();
            // PhysicsSetting identities belong to the imported model. Preserve the user's global
            // strength, but discard old per-group overrides so a new model never inherits an
            // unrelated PhysicsSetting1/2 toggle from the previous package.
            Live2DPhysicsSettingsStore.clearGroups(context, Live2DPhysicsSettingsStore.TARGET_LIVE2D);
            Live2DDebugSettingsStore.clearTarget(context, Live2DPhysicsSettingsStore.TARGET_LIVE2D);
            Live2DPhysicsHotkeyStore.clearTarget(context, Live2DPhysicsSettingsStore.TARGET_LIVE2D);
            deleteTree(oldBackup);
            return new ImportResult(safeName, modelRelative, adapted);
        } catch (Throwable error) {
            deleteTree(staging);
            if (replacementStarted) {
                File current = currentDir(context);
                deleteTree(current);
                if (oldBackup.exists() && !oldBackup.renameTo(current)) {
                    try { copyTree(oldBackup, current); } catch (Throwable ignored) {}
                    deleteTree(oldBackup);
                }
            }
            throw error;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zipCopy.delete();
        }
    }

    private static void validateModel3(File packageRoot, File modelFile, JSONObject model) throws Exception {
        int version = model.optInt("Version", 0);
        if (version < 3) throw new IOException("仅支持 Cubism 3/4/5 model3 模型");
        JSONObject refs = model.optJSONObject("FileReferences");
        if (refs == null) throw new IOException("model3 缺少 FileReferences");
        File modelRoot = modelFile.getParentFile();
        if (modelRoot == null) throw new IOException("无效 model3 路径");

        String mocRelative = refs.optString("Moc", "").trim();
        if (mocRelative.isEmpty()) throw new IOException("model3 缺少 Moc");
        File moc = resolveModelReference(modelRoot, mocRelative);
        requireInside(packageRoot, moc);
        if (!moc.isFile()) throw new IOException("Moc 文件不存在: " + mocRelative);

        JSONArray textures = refs.optJSONArray("Textures");
        if (textures == null || textures.length() == 0) throw new IOException("model3 缺少 Textures");
        for (int i = 0; i < textures.length(); i++) {
            String relative = textures.optString(i, "").trim();
            if (relative.isEmpty()) throw new IOException("存在空纹理路径");
            File texture = resolveModelReference(modelRoot, relative);
            requireInside(packageRoot, texture);
            if (!texture.isFile()) throw new IOException("纹理不存在: " + relative);
        }

        validateOptionalReference(packageRoot, modelRoot, refs, "Physics");
        validateOptionalReference(packageRoot, modelRoot, refs, "Pose");
        validateOptionalReference(packageRoot, modelRoot, refs, "DisplayInfo");
    }

    private static void validateOptionalReference(File packageRoot, File modelRoot,
                                                  JSONObject refs, String key) throws Exception {
        String relative = refs.optString(key, "").trim();
        if (relative.isEmpty()) return;
        File file = resolveModelReference(modelRoot, relative);
        requireInside(packageRoot, file);
        if (!file.isFile()) throw new IOException(key + " 文件不存在: " + relative);
    }

    /**
     * VTube Studio packages may keep their idle motion only in *.vtube.json instead of model3.
     * The supplied yumi model is exactly this layout (IdleAnimation = tear.motion3.json).
     * Mirror that metadata into FileReferences.Motions.Idle so Axon's trusted runtime can play it.
     */
    private static void injectVTubeStudioIdleIfPresent(File packageRoot, File modelFile,
                                                       JSONObject model) throws Exception {
        JSONObject refs = model.optJSONObject("FileReferences");
        if (refs == null) return;
        JSONObject motions = refs.optJSONObject("Motions");
        if (motions != null && motions.optJSONArray("Idle") != null
                && motions.optJSONArray("Idle").length() > 0) return;

        File modelRoot = modelFile.getParentFile();
        if (modelRoot == null) return;
        List<File> vtubeFiles = findFilesBySuffix(modelRoot, ".vtube.json", 2);
        for (File vtubeFile : vtubeFiles) {
            JSONObject vtube;
            try { vtube = readJson(vtubeFile); }
            catch (Throwable ignored) { continue; }
            JSONObject vtubeRefs = vtube.optJSONObject("FileReferences");
            if (vtubeRefs == null) continue;
            String declaredModel = vtubeRefs.optString("Model", "").trim();
            if (!declaredModel.isEmpty()) {
                File declared = resolveModelReference(vtubeFile.getParentFile(), declaredModel);
                if (!declared.getCanonicalFile().equals(modelFile.getCanonicalFile())) continue;
            }
            String idle = vtubeRefs.optString("IdleAnimation", "").trim();
            if (idle.isEmpty()) continue;
            File idleFile = resolveModelReference(vtubeFile.getParentFile(), idle);
            requireInside(packageRoot, idleFile);
            if (!idleFile.isFile()) continue;

            // model3 motion paths are relative to the model3 directory.
            String relative = relativePath(modelRoot, idleFile);
            if (motions == null) {
                motions = new JSONObject();
                refs.put("Motions", motions);
            }
            JSONArray idleGroup = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("File", relative.replace(File.separatorChar, '/'));
            idleGroup.put(entry);
            motions.put("Idle", idleGroup);
            return;
        }
    }

    private static int adaptLargeTextures(File packageRoot, File modelFile, JSONObject model, boolean clearQuality) throws Exception {
        JSONObject refs = model.optJSONObject("FileReferences");
        JSONArray textures = refs == null ? null : refs.optJSONArray("Textures");
        File modelRoot = modelFile.getParentFile();
        if (textures == null || modelRoot == null) return 0;

        final class TextureInfo {
            final String relative;
            final File file;
            final int width;
            final int height;
            TextureInfo(String relative, File file, int width, int height) {
                this.relative = relative;
                this.file = file;
                this.width = width;
                this.height = height;
            }
        }

        List<TextureInfo> infos = new ArrayList<>();
        long totalPixels = 0L;
        for (int i = 0; i < textures.length(); i++) {
            String relative = textures.optString(i, "").trim();
            if (relative.isEmpty()) continue;
            File texture = resolveModelReference(modelRoot, relative);
            requireInside(packageRoot, texture);
            if (!texture.isFile()) continue;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(texture.getAbsolutePath(), bounds);
            int width = Math.max(0, bounds.outWidth);
            int height = Math.max(0, bounds.outHeight);
            if (width <= 0 || height <= 0) continue;
            infos.add(new TextureInfo(relative, texture, width, height));
            totalPixels += (long) width * (long) height;
        }

        // A model with eleven 4096² atlases occupies about 704 MiB after RGBA decode.
        // Downsample the whole atlas set uniformly by powers of two so UV coordinates remain valid,
        // while keeping normal one/two-atlas models at authored resolution.
        long texturePixelBudget = clearQuality ? 80L * 1024L * 1024L : MAX_TOTAL_TEXTURE_PIXELS;
        int budgetSample = 1;
        while (totalPixels / ((long) budgetSample * budgetSample) > texturePixelBudget) {
            budgetSample *= 2;
        }

        int adapted = 0;
        for (TextureInfo info : infos) {
            int edgeSample = 1;
            while (Math.max(info.width, info.height) / edgeSample > MAX_TEXTURE_EDGE) edgeSample *= 2;
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
            if (bitmap == null) throw new IOException("无法适配大纹理: " + info.relative);

            File temp = new File(info.file.getParentFile(), info.file.getName() + ".axon_tmp");
            Bitmap.CompressFormat format = compressFormat(info.file.getName());
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                int quality = format == Bitmap.CompressFormat.JPEG ? 95 : 100;
                if (!bitmap.compress(format, quality, out)) throw new IOException("纹理压缩失败: " + info.relative);
            } finally {
                bitmap.recycle();
            }
            if (!info.file.delete() || !temp.renameTo(info.file)) {
                copyFile(temp, info.file);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            adapted++;
        }
        return adapted;
    }

    private static Bitmap.CompressFormat compressFormat(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return Bitmap.CompressFormat.JPEG;
        if (lower.endsWith(".webp")) return Bitmap.CompressFormat.WEBP;
        return Bitmap.CompressFormat.PNG;
    }

    private static File chooseModel3(File root) throws IOException {
        List<File> models = findFilesBySuffix(root, ".model3.json", 5);
        if (models.isEmpty()) return null;
        models.sort(Comparator
                .comparingInt((File f) -> pathDepth(root, f))
                .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File candidate : models) {
            try {
                validateModel3(root, candidate, readJson(candidate));
                return candidate;
            } catch (Exception ignored) {}
        }
        throw new IOException("找到 model3，但模型资源不完整");
    }

    private static int pathDepth(File root, File file) {
        try {
            String rel = relativePath(root, file);
            int count = 0;
            for (int i = 0; i < rel.length(); i++) if (rel.charAt(i) == File.separatorChar || rel.charAt(i) == '/') count++;
            return count;
        } catch (IOException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static List<File> findFilesBySuffix(File root, String suffix, int maxDepth) {
        List<File> out = new ArrayList<>();
        collect(root, root, suffix.toLowerCase(Locale.ROOT), maxDepth, 0, out);
        return out;
    }

    private static void collect(File packageRoot, File dir, String suffix, int maxDepth,
                                int depth, List<File> out) {
        if (dir == null || depth > maxDepth) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            try { requireInside(packageRoot, file); }
            catch (IOException ignored) { continue; }
            if (file.isDirectory()) collect(packageRoot, file, suffix, maxDepth, depth + 1, out);
            else if (file.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) out.add(file);
        }
    }

    private static void copyUriBounded(Context context, Uri uri, File target, long maxBytes) throws IOException {
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             InputStream in = raw == null ? null : new BufferedInputStream(raw);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            if (in == null) throw new IOException("无法读取模型 ZIP");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Live2D ZIP 超过 512MB");
                out.write(buffer, 0, read);
            }
            if (total <= 0) throw new IOException("模型 ZIP 为空");
        }
    }

    private static final class ZipNameEncodingException extends IOException {
        ZipNameEncodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static void extractZip(File zipFile, File target) throws IOException {
        ZipNameEncodingException utf8Failure;
        try {
            extractZip(zipFile, target, StandardCharsets.UTF_8);
            return;
        } catch (ZipNameEncodingException error) {
            utf8Failure = error;
            deleteTree(target);
            if (!target.mkdirs() && !target.isDirectory()) throw error;
        }

        // Older Windows/Chinese Live2D packs sometimes omit the UTF-8 filename flag and store
        // entry names in GBK/GB18030. Only retry when opening/decoding ZIP entry names failed;
        // policy errors (ZIP bomb, traversal, oversized files) must never be retried under a
        // different charset because that could partially extract the same untrusted archive twice.
        try {
            extractZip(zipFile, target, Charset.forName("GB18030"));
        } catch (IOException fallback) {
            fallback.addSuppressed(utf8Failure);
            throw fallback;
        }
    }

    private static void extractZip(File zipFile, File target, Charset charset) throws IOException {
        long total = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(zipFile, charset)) {
            preflightZipExtraction(zip, target);
            java.util.Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (++entries > MAX_ENTRIES) throw new IOException("模型文件数量过多");
                String rawName = entry.getName();
                if (rawName == null || rawName.isEmpty()) continue;
                String name = rawName.replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IOException("模型 ZIP 包含非法路径");
                }
                File output = safeResolve(target, name);
                requireInside(target, output);
                if (entry.isDirectory()) {
                    if (!output.exists() && !output.mkdirs()) throw new IOException("无法创建模型目录");
                    continue;
                }
                File parent = output.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("无法创建模型目录");

                long entryTotal = 0;
                try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        entryTotal += read;
                        total += read;
                        if (entryTotal > MAX_SINGLE_FILE_BYTES) throw new IOException("单个模型资源超过 512MB");
                        if (total > MAX_EXTRACTED_BYTES) throw new IOException("模型解压后超过 1.5GB");
                        out.write(buffer, 0, read);
                    }
                }
            }
        } catch (IllegalArgumentException | ZipException error) {
            throw new ZipNameEncodingException("模型 ZIP 文件名编码不兼容", error);
        }
    }

    private static void preflightZipExtraction(ZipFile zip, File target) throws IOException {
        long declaredTotal = 0L;
        int declaredEntries = 0;
        java.util.Enumeration<? extends ZipEntry> scan = zip.entries();
        while (scan.hasMoreElements()) {
            ZipEntry entry = scan.nextElement();
            if (++declaredEntries > MAX_ENTRIES) throw new IOException("模型文件数量过多");
            if (entry.isDirectory()) continue;
            long size = entry.getSize();
            if (size > MAX_SINGLE_FILE_BYTES) throw new IOException("单个模型资源超过 512MB");
            if (size > 0L) {
                declaredTotal += size;
                if (declaredTotal > MAX_EXTRACTED_BYTES) throw new IOException("模型解压后超过 1.5GB");
            }
        }
        if (declaredTotal > 0L) ensureUsableStorage(target, declaredTotal);
    }

    private static void ensureUsableStorage(File target, long expectedBytes) throws IOException {
        File probe = target == null ? null : target.getParentFile();
        if (probe == null) return;
        long usable = probe.getUsableSpace();
        if (usable <= 0L) return;
        long required;
        try { required = Math.addExact(expectedBytes, IMPORT_STORAGE_RESERVE_BYTES); }
        catch (ArithmeticException ignored) { required = Long.MAX_VALUE; }
        if (usable < required) {
            long needMb = (required + 1024L * 1024L - 1L) / (1024L * 1024L);
            long freeMb = usable / (1024L * 1024L);
            throw new IOException("存储空间不足：大型 Live2D 预计至少需要 " + needMb + " MB，可用约 " + freeMb + " MB");
        }
    }

    private static JSONObject readJson(File file) throws Exception {
        return new JSONObject(readText(file));
    }

    private static String readText(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("模型 JSON 不存在");
        if (file.length() > MAX_JSON_BYTES) throw new IOException("模型 JSON/配置文件超过 32MB");
        int initial = (int) Math.min(Math.max(0L, file.length()), 256L * 1024L);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream(initial)) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_JSON_BYTES) throw new IOException("模型 JSON/配置文件超过 32MB");
                out.write(buffer, 0, read);
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    private static void writeJson(File file, JSONObject json) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("无法更新 model3", error);
        }
    }

    private static void copyTree(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new IOException("无法创建目录");
            File[] files = source.listFiles();
            if (files != null) for (File file : files) copyTree(file, new File(target, file.getName()));
        } else {
            copyFile(source, target);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("无法创建目录");
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    /** Resolve model3 references across Windows case rules, URL-escaped paths and Unicode NFC/NFD. */
    private static File resolveModelReference(File root, String relative) throws IOException {
        if (root == null || relative == null) throw new IOException("空路径");
        String clean = Uri.decode(relative).replace('\\', '/').trim();
        if (clean.isEmpty() || clean.startsWith("/") || clean.contains("../") || clean.equals("..")) {
            throw new IOException("非法模型资源路径: " + relative);
        }
        File current = root;
        for (String part : clean.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new IOException("非法模型资源路径: " + relative);
            File exact = new File(current, part);
            if (exact.exists()) {
                current = exact;
                continue;
            }
            File[] children = current.listFiles();
            File matched = null;
            String expected = Normalizer.normalize(part, Normalizer.Form.NFC);
            if (children != null) {
                for (File child : children) {
                    String candidate = Normalizer.normalize(child.getName(), Normalizer.Form.NFC);
                    if (candidate.equalsIgnoreCase(expected)) { matched = child; break; }
                }
            }
            if (matched == null) return exact;
            current = matched;
        }
        requireInside(root, current);
        return current;
    }

    private static File safeResolve(File root, String relative) throws IOException {
        if (relative == null) throw new IOException("空路径");
        File file = new File(root, relative);
        requireInside(root, file);
        return file;
    }

    private static void requireInside(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("模型资源路径越界");
        }
    }

    private static String relativePath(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("路径越界");
        }
        if (filePath.equals(rootPath)) return "";
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File baseDir(Context context) {
        return new File(context.getFilesDir(), BASE_DIR);
    }

    private static File currentDir(Context context) {
        return new File(baseDir(context), CURRENT_DIR);
    }
}
