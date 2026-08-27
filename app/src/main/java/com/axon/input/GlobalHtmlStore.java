package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Persistent global HTML library plus its lightweight runtime cache. */
final class GlobalHtmlStore {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int FONT_MODE_PAGE = 0;
    static final int FONT_MODE_FOLLOW_APP = 1;

    static final class HtmlInfo {
        final String id;
        final String name;

        HtmlInfo(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }
    }

    private static final String KEY_ENABLED = "global_html_enabled";
    private static final String KEY_FONT_MODE = "global_html_font_mode";
    private static final String KEY_NAME = "global_html_name"; // 旧版名称。

    // HTML 库使用独立 preferences，避免载入普通配置时误删用户已经导入的 HTML 文件列表。
    private static final String LIBRARY_PREFS = "axon_input_html_library";
    private static final String KEY_LIBRARY = "items_v2";
    private static final String KEY_SELECTED_ID = "selected_id";
    private static final String LEGACY_ID = "__legacy_html__";
    private static final String LEGACY_FILE_NAME = "global_display.html";
    private static final String LIBRARY_DIR = "imported_html";

    private static String cachedPath = "";
    private static long cachedModified = Long.MIN_VALUE;
    private static long cachedLength = Long.MIN_VALUE;
    private static String cachedContent;

    private GlobalHtmlStore() {}

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        if (PreferenceWriter.putBooleanIfChanged(preferences(context), KEY_ENABLED, enabled)) {
            AxonInputAccessibilityService.refreshActiveService();
        }
    }

    static int getFontMode(Context context) {
        int value = preferences(context).getInt(KEY_FONT_MODE, FONT_MODE_PAGE);
        return value == FONT_MODE_FOLLOW_APP ? FONT_MODE_FOLLOW_APP : FONT_MODE_PAGE;
    }

    static void setFontMode(Context context, int mode) {
        int safe = mode == FONT_MODE_FOLLOW_APP ? FONT_MODE_FOLLOW_APP : FONT_MODE_PAGE;
        if (PreferenceWriter.putIntIfChanged(preferences(context), KEY_FONT_MODE, safe)) {
            AxonInputAccessibilityService.refreshActiveService();
        }
    }

    /** 列出全部导入的 HTML。旧版单 HTML 会作为第一项保留。 */
    static List<HtmlInfo> listDocuments(Context context) {
        Context app = context.getApplicationContext();
        ArrayList<HtmlInfo> result = new ArrayList<>();
        File legacy = legacyFile(app);
        if (legacy.isFile() && legacy.length() > 0L && legacy.length() <= MAX_BYTES) {
            String name = preferences(app).getString(KEY_NAME, "");
            if (name == null || name.trim().isEmpty()) name = "display.html";
            result.add(new HtmlInfo(LEGACY_ID, name.trim()));
        }

        String raw = libraryPreferences(app).getString(KEY_LIBRARY, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "").trim();
                    String name = item.optString("name", "").trim();
                    if (id.isEmpty() || LEGACY_ID.equals(id)) continue;
                    File file = libraryFile(app, id);
                    if (!file.isFile() || file.length() <= 0L || file.length() > MAX_BYTES) continue;
                    if (name.isEmpty()) name = "display.html";
                    result.add(new HtmlInfo(id, name));
                }
            } catch (Throwable ignored) { }
        }
        return Collections.unmodifiableList(result);
    }

    static String getSelectedId(Context context) {
        List<HtmlInfo> documents = listDocuments(context);
        if (documents.isEmpty()) return "";
        String selected = libraryPreferences(context).getString(KEY_SELECTED_ID, "");
        if (selected != null && !selected.isEmpty()) {
            for (HtmlInfo info : documents) if (selected.equals(info.id)) return selected;
        }
        return documents.get(0).id;
    }

    static void setSelectedId(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        for (HtmlInfo info : listDocuments(context)) {
            if (!id.equals(info.id)) continue;
            if (PreferenceWriter.putStringIfChanged(libraryPreferences(context), KEY_SELECTED_ID, id)) {
                invalidateCache();
                AxonInputAccessibilityService.refreshActiveService();
            }
            return;
        }
    }

    static String getName(Context context) {
        String selected = getSelectedId(context);
        if (selected.isEmpty()) return "";
        for (HtmlInfo info : listDocuments(context)) if (selected.equals(info.id)) return info.name;
        return "";
    }

    static boolean exists(Context context) {
        File file = selectedFile(context);
        return file.isFile() && file.length() > 0L && file.length() <= MAX_BYTES;
    }

    /** 导入 HTML 时追加到 HTML 库并自动选中新文件，不再覆盖之前导入的 HTML。 */
    static HtmlInfo save(Context context, String displayName, String html) throws IOException {
        return saveInternal(context, displayName, html, true, true);
    }

    private static HtmlInfo saveInternal(Context context, String displayName, String html,
                                         boolean enableAfterSave, boolean refreshAfterSave) throws IOException {
        if (html == null) throw new IOException("HTML is null");
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > MAX_BYTES) throw new IOException("HTML size out of range");

        Context app = context.getApplicationContext();
        File dir = libraryDir(app);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create HTML library");
        String id = "html_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");
        File target = libraryFile(app, id);
        writeAtomic(target, data);

        String name = displayName == null || displayName.trim().isEmpty() ? "display.html" : displayName.trim();
        appendLibraryEntry(app, new HtmlInfo(id, name));
        if (!libraryPreferences(app).edit().putString(KEY_SELECTED_ID, id).commit()) {
            if (target.exists()) target.delete();
            throw new IOException("Cannot select HTML");
        }
        if (enableAfterSave) PreferenceWriter.putBooleanIfChanged(preferences(app), KEY_ENABLED, true);
        synchronized (GlobalHtmlStore.class) {
            cachedPath = target.getAbsolutePath();
            cachedModified = target.lastModified();
            cachedLength = target.length();
            cachedContent = html;
        }
        if (refreshAfterSave) AxonInputAccessibilityService.refreshActiveService();
        return new HtmlInfo(id, name);
    }

    static String load(Context context) {
        File target = selectedFile(context);
        if (!target.isFile()) return "";
        long length = target.length();
        if (length <= 0L || length > MAX_BYTES) return "";
        long modified = target.lastModified();
        String path = target.getAbsolutePath();

        synchronized (GlobalHtmlStore.class) {
            if (cachedContent != null && path.equals(cachedPath)
                    && cachedLength == length && cachedModified == modified) {
                return cachedContent;
            }
        }

        String content = readFile(target);
        synchronized (GlobalHtmlStore.class) {
            cachedPath = path;
            cachedModified = modified;
            cachedLength = length;
            cachedContent = content;
        }
        return content;
    }

    /** Restores one HTML payload from config, appends it to the library, and selects it. */
    static void restoreFromConfig(Context context, String displayName, String html) throws IOException {
        // 配置中的 enabled/font mode 已由 ConfigManager 写回，这里只恢复并选择文档。
        saveInternal(context, displayName, html, false, false);
    }

    /** A config without HTML disables HTML rendering but does not destroy the user's imported HTML library. */
    static void clearFromConfig(Context context) {
        PreferenceWriter.putBooleanIfChanged(preferences(context), KEY_ENABLED, false);
        invalidateCache();
    }

    static void invalidateCache() {
        synchronized (GlobalHtmlStore.class) {
            cachedPath = "";
            cachedModified = Long.MIN_VALUE;
            cachedLength = Long.MIN_VALUE;
            cachedContent = null;
        }
    }

    private static void appendLibraryEntry(Context context, HtmlInfo entry) throws IOException {
        JSONArray array = new JSONArray();
        String raw = libraryPreferences(context).getString(KEY_LIBRARY, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray old = new JSONArray(raw);
                for (int i = 0; i < old.length(); i++) {
                    JSONObject item = old.optJSONObject(i);
                    if (item != null) array.put(item);
                }
            } catch (Throwable ignored) { }
        }
        try {
            JSONObject item = new JSONObject();
            item.put("id", entry.id);
            item.put("name", entry.name);
            array.put(item);
        } catch (Throwable error) {
            throw new IOException("Cannot save HTML metadata", error);
        }
        if (!libraryPreferences(context).edit().putString(KEY_LIBRARY, array.toString()).commit()) {
            throw new IOException("Cannot save HTML metadata");
        }
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        AtomicFile atomicFile = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(data);
            output.flush();
            output.getFD().sync();
            atomicFile.finishWrite(output);
        } catch (IOException error) {
            if (output != null) atomicFile.failWrite(output);
            throw error;
        }
    }

    private static String readFile(File file) {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(file.length(), 64 * 1024L))) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) return "";
                output.write(buffer, 0, read);
            }
            return total == 0 ? "" : new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static File selectedFile(Context context) {
        String id = getSelectedId(context);
        if (LEGACY_ID.equals(id)) return legacyFile(context);
        if (id.isEmpty()) return new File(context.getApplicationContext().getFilesDir(), "__missing_html__");
        return libraryFile(context, id);
    }

    private static File legacyFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), LEGACY_FILE_NAME);
    }

    private static File libraryDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), LIBRARY_DIR);
    }

    private static File libraryFile(Context context, String id) {
        return new File(libraryDir(context), id + ".html");
    }

    private static SharedPreferences preferences(Context context) {
        return AppPreferences.get(context);
    }

    private static SharedPreferences libraryPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(LIBRARY_PREFS, Context.MODE_PRIVATE);
    }
}
