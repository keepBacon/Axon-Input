package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 悬浮文本字体管理。 */
public final class FontManager {
    public static final int CHOICE_SYSTEM = 0;
    public static final int CHOICE_SANS = 1;
    public static final int CHOICE_SERIF = 2;
    public static final int CHOICE_MONOSPACE = 3;
    public static final int CHOICE_IMPORTED = 4;

    private static final String PREFS = "axon_input_font";
    private static final String KEY_NAME = "name";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_CHOICE = "choice";
    private static final String FILE_NAME = "display_font.bin";
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

    public static boolean hasImportedFont(Context context) {
        return fontFile(context).isFile();
    }

    public static String getImportedFontName(Context context) {
        if (!hasImportedFont(context)) return "";
        String value = prefs(context).getString(KEY_NAME, "");
        return value == null ? "" : value;
    }

    static boolean shouldServeImportedFont(Context context) {
        return isEnabled(context) && getChoice(context) == CHOICE_IMPORTED && hasImportedFont(context);
    }

    static InputStream openImportedFont(Context context) throws IOException {
        File file = fontFile(context);
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
                return hasImportedFont(context) ? "'AxonImportedFont'" : "sans-serif";
            case CHOICE_SANS:
            case CHOICE_SYSTEM:
            default:
                return "sans-serif";
        }
    }

    public static synchronized void importFont(Context context, Uri uri, String displayName) throws IOException {
        if (uri == null) throw new IOException("Font uri is null");
        Context app = context.getApplicationContext();
        File target = fontFile(app);
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

        Typeface test;
        try {
            test = Typeface.createFromFile(temp);
            if (test == null) throw new IllegalArgumentException("Invalid font");
        } catch (Throwable error) {
            temp.delete();
            throw new IOException("Invalid font", error);
        }

        if (target.exists() && !target.delete()) {
            temp.delete();
            throw new IOException("Cannot replace font");
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("Cannot save font");
        }

        prefs(app).edit().putString(KEY_NAME,
                displayName == null || displayName.trim().isEmpty()
                        ? app.getString(R.string.font_custom_name)
                        : displayName.trim()).apply();
        loadedPath = target.getAbsolutePath();
        normal = test;
        bold = Typeface.create(test, Typeface.BOLD);
    }

    private static void ensureLoaded(Context context) {
        File file = fontFile(context);
        if (!file.isFile()) {
            normal = null;
            bold = null;
            loadedPath = "";
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
                normal = null;
                bold = null;
                loadedPath = "";
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File fontFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }
}
