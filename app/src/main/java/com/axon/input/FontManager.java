package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 悬浮文本字体管理。支持保存多个导入字体并在运行时切换。 */
public final class FontManager {
    public static final int CHOICE_SYSTEM = 0;
    public static final int CHOICE_SANS = 1;
    public static final int CHOICE_SERIF = 2;
    public static final int CHOICE_MONOSPACE = 3;
    /** 保留给运行时判断：具体使用哪一个导入字体由 selectedImportedId 决定。 */
    public static final int CHOICE_IMPORTED = 4;

    public static final class FontInfo {
        public final String id;
        public final String name;

        FontInfo(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }
    }

    private static final String PREFS = "axon_input_font";
    private static final String KEY_NAME = "name"; // 旧版单字体名称，保留用于迁移。
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CHOICE = "choice";
    private static final String KEY_SELECTED_ID = "selected_imported_id";
    private static final String KEY_LIBRARY = "imported_font_library_v2";

    private static final String LEGACY_ID = "__legacy_font__";
    private static final String LEGACY_FILE_NAME = "display_font.bin";
    private static final String LIBRARY_DIR = "imported_fonts";
    private static final String TEMP_NAME = "display_font.tmp";
    private static final int MAX_FONT_BYTES = 16 * 1024 * 1024;

    private static volatile Typeface normal;
    private static volatile Typeface bold;
    private static volatile String loadedPath;

    private FontManager() {}

    public static boolean isEnabled(Context context) {
        SharedPreferences values = prefs(context);
        if (values.contains(KEY_ENABLED)) return values.getBoolean(KEY_ENABLED, false);
        // 旧版本只要导入字体就会立即生效。升级后沿用原来的视觉状态。
        return hasImportedFont(context);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getChoice(Context context) {
        SharedPreferences values = prefs(context);
        int fallback = hasImportedFont(context) ? CHOICE_IMPORTED : CHOICE_SYSTEM;
        int value = values.getInt(KEY_CHOICE, fallback);
        return value >= CHOICE_SYSTEM && value <= CHOICE_IMPORTED ? value : fallback;
    }

    public static void setChoice(Context context, int choice) {
        int safe = Math.max(CHOICE_SYSTEM, Math.min(CHOICE_IMPORTED, choice));
        prefs(context).edit().putInt(KEY_CHOICE, safe).apply();
    }

    public static Typeface normal(Context context) {
        return resolve(context, false);
    }

    public static Typeface bold(Context context) {
        return resolve(context, true);
    }

    private static Typeface resolve(Context context, boolean boldStyle) {
        if (!isEnabled(context)) return boldStyle ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        switch (getChoice(context)) {
            case CHOICE_SANS:
                return Typeface.create("sans", boldStyle ? Typeface.BOLD : Typeface.NORMAL);
            case CHOICE_SERIF:
                return Typeface.create("serif", boldStyle ? Typeface.BOLD : Typeface.NORMAL);
            case CHOICE_MONOSPACE:
                return Typeface.create("monospace", boldStyle ? Typeface.BOLD : Typeface.NORMAL);
            case CHOICE_IMPORTED:
                ensureLoaded(context);
                Typeface imported = boldStyle ? bold : normal;
                return imported != null ? imported : (boldStyle ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            case CHOICE_SYSTEM:
            default:
                return boldStyle ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        }
    }

    /** 返回所有已导入字体。旧版单字体会作为第一项自动出现在这里。 */
    public static List<FontInfo> listImportedFonts(Context context) {
        Context app = context.getApplicationContext();
        ArrayList<FontInfo> result = new ArrayList<>();
        File legacy = legacyFontFile(app);
        if (legacy.isFile() && legacy.length() > 0L) {
            String name = prefs(app).getString(KEY_NAME, "");
            if (name == null || name.trim().isEmpty()) name = app.getString(R.string.font_custom_name);
            result.add(new FontInfo(LEGACY_ID, name.trim()));
        }

        String raw = prefs(app).getString(KEY_LIBRARY, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "").trim();
                    String name = item.optString("name", "").trim();
                    if (id.isEmpty() || LEGACY_ID.equals(id)) continue;
                    File file = libraryFontFile(app, id);
                    if (!file.isFile() || file.length() <= 0L) continue;
                    if (name.isEmpty()) name = app.getString(R.string.font_custom_name);
                    result.add(new FontInfo(id, name));
                }
            } catch (Throwable ignored) {
                // 元数据损坏时不影响旧字体和系统字体继续使用。
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean hasImportedFont(Context context) {
        return !listImportedFonts(context).isEmpty();
    }

    public static String getSelectedImportedId(Context context) {
        List<FontInfo> fonts = listImportedFonts(context);
        if (fonts.isEmpty()) return "";
        String selected = prefs(context).getString(KEY_SELECTED_ID, "");
        if (selected != null && !selected.isEmpty()) {
            for (FontInfo info : fonts) if (selected.equals(info.id)) return selected;
        }
        return fonts.get(0).id;
    }

    public static void setSelectedImportedId(Context context, String id) {
        if (id == null || id.isEmpty()) return;
        for (FontInfo info : listImportedFonts(context)) {
            if (!id.equals(info.id)) continue;
            prefs(context).edit()
                    .putString(KEY_SELECTED_ID, id)
                    .putInt(KEY_CHOICE, CHOICE_IMPORTED)
                    .apply();
            invalidateTypefaceCache();
            return;
        }
    }

    public static String getImportedFontName(Context context) {
        String selected = getSelectedImportedId(context);
        if (selected.isEmpty()) return "";
        for (FontInfo info : listImportedFonts(context)) {
            if (selected.equals(info.id)) return info.name;
        }
        return "";
    }

    static boolean shouldServeImportedFont(Context context) {
        return isEnabled(context) && getChoice(context) == CHOICE_IMPORTED && selectedFontFile(context).isFile();
    }

    static InputStream openImportedFont(Context context) throws IOException {
        File file = selectedFontFile(context);
        if (!file.isFile()) throw new IOException("Imported font does not exist");
        return new FileInputStream(file);
    }

    static String cssFamily(Context context) {
        if (!isEnabled(context)) return "sans-serif";
        switch (getChoice(context)) {
            case CHOICE_SERIF:
                return "serif";
            case CHOICE_MONOSPACE:
                return "monospace";
            case CHOICE_IMPORTED:
                return selectedFontFile(context).isFile() ? "'AxonImportedFont'" : "sans-serif";
            case CHOICE_SANS:
            case CHOICE_SYSTEM:
            default:
                return "sans-serif";
        }
    }

    /** 导入字体时追加到字体库，不再覆盖之前导入的字体。返回新字体的信息。 */
    public static synchronized FontInfo importFont(Context context, Uri uri, String displayName) throws IOException {
        if (uri == null) throw new IOException("Font uri is null");
        Context app = context.getApplicationContext();
        File dir = libraryDir(app);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create font library");
        File temp = new File(app.getFilesDir(), TEMP_NAME);
        if (temp.exists() && !temp.delete()) throw new IOException("Cannot clear temp font");

        int total = 0;
        try (InputStream in = app.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp, false)) {
            if (in == null) throw new IOException("Cannot open font");
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FONT_BYTES) throw new IOException("Font too large");
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        } catch (Throwable error) {
            temp.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Cannot import font", error);
        }
        if (total <= 0) {
            temp.delete();
            throw new IOException("Empty font");
        }

        try {
            Typeface test = Typeface.createFromFile(temp);
            if (test == null) throw new IllegalArgumentException("Invalid font");
        } catch (Throwable error) {
            temp.delete();
            throw new IOException("Invalid font", error);
        }

        String id = "font_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");
        File target = libraryFontFile(app, id);
        if (!temp.renameTo(target)) {
            copyFile(temp, target);
            temp.delete();
        }

        String name = displayName == null || displayName.trim().isEmpty()
                ? app.getString(R.string.font_custom_name)
                : displayName.trim();
        appendLibraryEntry(app, new FontInfo(id, name));
        prefs(app).edit()
                .putString(KEY_SELECTED_ID, id)
                .putInt(KEY_CHOICE, CHOICE_IMPORTED)
                .apply();
        invalidateTypefaceCache();
        return new FontInfo(id, name);
    }

    private static void appendLibraryEntry(Context context, FontInfo entry) throws IOException {
        JSONArray array = new JSONArray();
        String raw = prefs(context).getString(KEY_LIBRARY, "");
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
            throw new IOException("Cannot save font metadata", error);
        }
        if (!prefs(context).edit().putString(KEY_LIBRARY, array.toString()).commit()) {
            throw new IOException("Cannot save font metadata");
        }
    }

    private static void ensureLoaded(Context context) {
        File file = selectedFontFile(context);
        if (!file.isFile()) {
            invalidateTypefaceCache();
            return;
        }
        String path = file.getAbsolutePath();
        if (path.equals(loadedPath) && normal != null && bold != null) return;
        synchronized (FontManager.class) {
            if (path.equals(loadedPath) && normal != null && bold != null) return;
            try {
                Typeface base = Typeface.createFromFile(file);
                normal = base;
                bold = Typeface.create(base, Typeface.BOLD);
                loadedPath = path;
            } catch (Throwable ignored) {
                invalidateTypefaceCache();
            }
        }
    }

    private static synchronized void invalidateTypefaceCache() {
        normal = null;
        bold = null;
        loadedPath = "";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File selectedFontFile(Context context) {
        String id = getSelectedImportedId(context);
        if (LEGACY_ID.equals(id)) return legacyFontFile(context);
        if (id.isEmpty()) return new File(context.getApplicationContext().getFilesDir(), "__missing_font__");
        return libraryFontFile(context, id);
    }

    private static File legacyFontFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), LEGACY_FILE_NAME);
    }

    private static File libraryDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), LIBRARY_DIR);
    }

    private static File libraryFontFile(Context context, String id) {
        return new File(libraryDir(context), id + ".bin");
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            out.getFD().sync();
        } catch (Throwable error) {
            if (target.exists()) target.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Cannot save font", error);
        }
    }
}
