package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
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

    /** User package limit. The supplied yumi package is ~22 MiB. */
    private static final long MAX_ZIP_BYTES = 256L * 1024L * 1024L;
    /** Separate extracted-size limit protects against ZIP bombs while allowing large textures. */
    private static final long MAX_EXTRACTED_BYTES = 768L * 1024L * 1024L;
    private static final long MAX_SINGLE_FILE_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 1800;
    /** Mobile WebGL compatibility target. The uploaded yumi texture is 8192x8192. */
    private static final int MAX_TEXTURE_EDGE = 4096;

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
            File modelFile = chooseModel3(staging);
            if (modelFile == null) throw new IOException("ZIP 中未找到 *.model3.json");

            JSONObject model = readJson(modelFile);
            validateModel3(staging, modelFile, model);
            injectVTubeStudioIdleIfPresent(staging, modelFile, model);
            writeJson(modelFile, model);
            int adapted = adaptLargeTextures(staging, modelFile, model);
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
        File moc = safeResolve(modelRoot, mocRelative);
        requireInside(packageRoot, moc);
        if (!moc.isFile()) throw new IOException("Moc 文件不存在: " + mocRelative);

        JSONArray textures = refs.optJSONArray("Textures");
        if (textures == null || textures.length() == 0) throw new IOException("model3 缺少 Textures");
        for (int i = 0; i < textures.length(); i++) {
            String relative = textures.optString(i, "").trim();
            if (relative.isEmpty()) throw new IOException("存在空纹理路径");
            File texture = safeResolve(modelRoot, relative);
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
        File file = safeResolve(modelRoot, relative);
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
                File declared = safeResolve(vtubeFile.getParentFile(), declaredModel);
                if (!declared.getCanonicalFile().equals(modelFile.getCanonicalFile())) continue;
            }
            String idle = vtubeRefs.optString("IdleAnimation", "").trim();
            if (idle.isEmpty()) continue;
            File idleFile = safeResolve(vtubeFile.getParentFile(), idle);
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

    private static int adaptLargeTextures(File packageRoot, File modelFile, JSONObject model) throws Exception {
        JSONObject refs = model.optJSONObject("FileReferences");
        JSONArray textures = refs == null ? null : refs.optJSONArray("Textures");
        File modelRoot = modelFile.getParentFile();
        if (textures == null || modelRoot == null) return 0;
        int adapted = 0;
        for (int i = 0; i < textures.length(); i++) {
            String relative = textures.optString(i, "").trim();
            if (relative.isEmpty()) continue;
            File texture = safeResolve(modelRoot, relative);
            requireInside(packageRoot, texture);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(texture.getAbsolutePath(), bounds);
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width <= 0 || height <= 0 || (width <= MAX_TEXTURE_EDGE && height <= MAX_TEXTURE_EDGE)) continue;

            int sample = 1;
            while ((width / (sample * 2)) >= MAX_TEXTURE_EDGE
                    || (height / (sample * 2)) >= MAX_TEXTURE_EDGE) {
                sample *= 2;
            }
            if (Math.max(width / sample, height / sample) > MAX_TEXTURE_EDGE) sample *= 2;

            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = Math.max(2, sample);
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(texture.getAbsolutePath(), decode);
            if (bitmap == null) throw new IOException("无法适配大纹理: " + relative);

            File temp = new File(texture.getParentFile(), texture.getName() + ".axon_tmp");
            Bitmap.CompressFormat format = compressFormat(texture.getName());
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                int quality = format == Bitmap.CompressFormat.JPEG ? 95 : 100;
                if (!bitmap.compress(format, quality, out)) throw new IOException("纹理压缩失败: " + relative);
            } finally {
                bitmap.recycle();
            }
            if (!texture.delete() || !temp.renameTo(texture)) {
                copyFile(temp, texture);
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
                if (total > maxBytes) throw new IOException("Live2D ZIP 超过 256MB");
                out.write(buffer, 0, read);
            }
            if (total <= 0) throw new IOException("模型 ZIP 为空");
        }
    }

    private static void extractZip(File zipFile, File target) throws IOException {
        long total = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(zipFile)) {
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
                        if (entryTotal > MAX_SINGLE_FILE_BYTES) throw new IOException("单个模型资源超过 256MB");
                        if (total > MAX_EXTRACTED_BYTES) throw new IOException("模型解压后超过 768MB");
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static JSONObject readJson(File file) throws Exception {
        return new JSONObject(readText(file));
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
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
