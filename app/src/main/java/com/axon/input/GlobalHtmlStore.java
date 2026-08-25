package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Persistent global HTML document plus its lightweight runtime cache. */
final class GlobalHtmlStore {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int FONT_MODE_PAGE = 0;
    static final int FONT_MODE_FOLLOW_APP = 1;

    private static final String KEY_ENABLED = "global_html_enabled";
    private static final String KEY_FONT_MODE = "global_html_font_mode";
    private static final String KEY_NAME = "global_html_name";
    private static final String FILE_NAME = "global_display.html";

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

    static String getName(Context context) {
        String value = preferences(context).getString(KEY_NAME, "");
        return value == null ? "" : value;
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

    static boolean exists(Context context) {
        File file = file(context);
        return file.isFile() && file.length() > 0L && file.length() <= MAX_BYTES;
    }

    static void save(Context context, String displayName, String html) throws IOException {
        if (html == null) throw new IOException("HTML is null");
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > MAX_BYTES) throw new IOException("HTML size out of range");

        File target = file(context);
        writeAtomic(target, data);

        SharedPreferences values = preferences(context);
        SharedPreferences.Editor editor = values.edit();
        String name = displayName == null ? "display.html" : displayName;
        boolean changed = !name.equals(values.getString(KEY_NAME, null)) || !values.getBoolean(KEY_ENABLED, false);
        if (changed) editor.putString(KEY_NAME, name).putBoolean(KEY_ENABLED, true).apply();

        synchronized (GlobalHtmlStore.class) {
            cachedModified = target.lastModified();
            cachedLength = target.length();
            cachedContent = html;
        }
        AxonInputAccessibilityService.refreshActiveService();
    }

    static String load(Context context) {
        File target = file(context);
        if (!target.isFile()) return "";
        long length = target.length();
        if (length <= 0L || length > MAX_BYTES) return "";
        long modified = target.lastModified();

        synchronized (GlobalHtmlStore.class) {
            if (cachedContent != null && cachedLength == length && cachedModified == modified) {
                return cachedContent;
            }
        }

        String content = readFile(target);
        synchronized (GlobalHtmlStore.class) {
            cachedModified = modified;
            cachedLength = length;
            cachedContent = content;
        }
        return content;
    }

    /** Restores the HTML payload during a full config import without triggering an intermediate runtime refresh. */
    static void restoreFromConfig(Context context, String displayName, String html) throws IOException {
        if (html == null) throw new IOException("HTML is null");
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > MAX_BYTES) throw new IOException("HTML size out of range");
        File target = file(context);
        writeAtomic(target, data);
        String name = displayName == null || displayName.isEmpty() ? "display.html" : displayName;
        PreferenceWriter.putStringIfChanged(preferences(context), KEY_NAME, name);
        synchronized (GlobalHtmlStore.class) {
            cachedModified = target.lastModified();
            cachedLength = target.length();
            cachedContent = html;
        }
    }

    /** Clears the HTML payload during a full config import without triggering an intermediate runtime refresh. */
    static void clearFromConfig(Context context) throws IOException {
        File target = file(context);
        if (target.exists() && !target.delete()) throw new IOException("Cannot remove old HTML");
        SharedPreferences values = preferences(context);
        SharedPreferences.Editor editor = values.edit();
        editor.putBoolean(KEY_ENABLED, false).remove(KEY_NAME).apply();
        invalidateCache();
    }

    static void invalidateCache() {
        synchronized (GlobalHtmlStore.class) {
            cachedModified = Long.MIN_VALUE;
            cachedLength = Long.MIN_VALUE;
            cachedContent = null;
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

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    private static SharedPreferences preferences(Context context) {
        return AppPreferences.get(context);
    }
}
