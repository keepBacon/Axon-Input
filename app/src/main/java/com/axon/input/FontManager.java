package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 悬浮文本字体管理。 */
public final class FontManager {
    private static final String PREFS = "axon_input_font";
    private static final String KEY_NAME = "name";
    private static final String FILE_NAME = "display_font.bin";
    private static final String TEMP_NAME = "display_font.tmp";
    private static final int MAX_FONT_BYTES = 16 * 1024 * 1024;

    private static volatile Typeface normal;
    private static volatile Typeface bold;
    private static volatile String loadedPath;

    private FontManager() {}

    public static Typeface normal(Context context) {
        ensureLoaded(context);
        Typeface value = normal;
        return value != null ? value : Typeface.create("sans", Typeface.NORMAL);
    }

    public static Typeface bold(Context context) {
        ensureLoaded(context);
        Typeface value = bold;
        return value != null ? value : Typeface.create("sans", Typeface.BOLD);
    }

    public static boolean hasImportedFont(Context context) {
        return fontFile(context).isFile();
    }

    public static String getImportedFontName(Context context) {
        if (!hasImportedFont(context)) return "";
        String value = prefs(context).getString(KEY_NAME, "");
        return value == null ? "" : value;
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
            // 导入失败时删除临时文件。
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
