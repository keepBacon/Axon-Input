package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 处理配置保存、加载、导出和导入。 */
public final class ConfigManager {
    private static final String FORMAT = "KeyDisplayConfig";
    private static final int VERSION = 1;
    static final int MAX_CONFIG_BYTES = 12 * 1024 * 1024;
    private static final String SLOT1_FILE = "config_slot_1.json";

    // 导出时跳过密码授权和临时录入状态。
    private static final String KEY_ENTRY_AUTHORIZED = "entry_authorized";
    private static final String KEY_CUSTOM_CAPTURE = "custom_capture";
    private static final String KEY_CUSTOM_DRAFT = "custom_draft";
    private static final String KEY_SENSITIVITY_STATUS = "sensitivity_status";

    private ConfigManager() {}

    public static boolean hasSlot1(Context context) {
        File file = new File(context.getFilesDir(), SLOT1_FILE);
        return file.isFile() && file.length() > 0;
    }

    public static void saveSlot1(Context context) throws IOException, JSONException {
        writeUtf8(new File(context.getFilesDir(), SLOT1_FILE), exportCurrent(context));
    }

    public static void loadSlot1(Context context) throws IOException, JSONException {
        File file = new File(context.getFilesDir(), SLOT1_FILE);
        if (!file.isFile()) throw new IOException("Slot 1 is empty");
        importInto(context, readUtf8(file, MAX_CONFIG_BYTES));
    }

    public static String exportCurrent(Context context) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);

        JSONArray entries = new JSONArray();
        for (Map.Entry<String, ?> item : AppPreferences.get(context).getAll().entrySet()) {
            String key = item.getKey();
            if (shouldSkip(key)) continue;
            Object value = item.getValue();
            JSONObject entry = encodeEntry(key, value);
            if (entry != null) entries.put(entry);
        }
        root.put("preferences", entries);

        if (GlobalHtmlStore.exists(context)) {
            JSONObject html = new JSONObject();
            html.put("name", GlobalHtmlStore.getName(context));
            html.put("content", GlobalHtmlStore.load(context));
            root.put("globalHtml", html);
        }
        return root.toString(2);
    }

    public static void importInto(Context context, String json) throws JSONException, IOException {
        if (json == null) throw new IOException("Empty config");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_CONFIG_BYTES) {
            throw new IOException("Config size out of range");
        }

        JSONObject root = new JSONObject(json);
        if (!FORMAT.equals(root.optString("format"))) throw new IOException("Unsupported config format");
        int version = root.optInt("version", -1);
        if (version != VERSION) throw new IOException("Unsupported config version");

        JSONObject html = root.optJSONObject("globalHtml");
        String htmlContent = null;
        if (html != null) {
            htmlContent = html.optString("content", "");
            int htmlBytes = htmlContent.getBytes(StandardCharsets.UTF_8).length;
            if (htmlBytes == 0 || htmlBytes > GlobalHtmlStore.MAX_BYTES) {
                throw new IOException("HTML payload out of range");
            }
        }

        SharedPreferences prefs = AppPreferences.get(context);
        boolean entryAuthorized = prefs.getBoolean(KEY_ENTRY_AUTHORIZED, false);
        Map<String, Object> previousPreferences = snapshotPreferences(prefs);
        GlobalHtmlStore.ConfigSnapshot htmlSnapshot = GlobalHtmlStore.snapshotForConfig(context);
        SharedPreferences.Editor editor = prefs.edit().clear();

        JSONArray entries = root.optJSONArray("preferences");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String key = entry.optString("key", "");
                if (key.isEmpty() || shouldSkip(key)) continue;
                decodeEntry(editor, key, entry);
            }
        }

        // 保留访问验证；临时录入状态不恢复。
        editor.putBoolean(KEY_ENTRY_AUTHORIZED, entryAuthorized);
        editor.putBoolean(KEY_CUSTOM_CAPTURE, false);
        editor.putString(KEY_CUSTOM_DRAFT, "");
        editor.putString(KEY_SENSITIVITY_STATUS, context.getString(R.string.status_disabled));
        if (!editor.commit()) throw new IOException("Cannot save imported config");

        try {
            if (html != null) {
                GlobalHtmlStore.restoreFromConfig(context, html.optString("name", "display.html"), htmlContent);
            } else {
                GlobalHtmlStore.clearFromConfig(context);
            }
        } catch (Throwable error) {
            IOException failure = error instanceof IOException
                    ? (IOException) error : new IOException("Cannot restore imported HTML", error);
            try {
                restorePreferences(prefs, previousPreferences);
            } catch (Throwable rollbackError) {
                failure.addSuppressed(rollbackError);
            }
            try {
                GlobalHtmlStore.rollbackConfigMutation(context, htmlSnapshot);
            } catch (Throwable rollbackError) {
                failure.addSuppressed(rollbackError);
            }
            throw failure;
        }

        OverlayState.refreshAfterConfigChange(context);
    }


    private static Map<String, Object> snapshotPreferences(SharedPreferences prefs) {
        HashMap<String, Object> snapshot = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) {
                LinkedHashSet<String> copy = new LinkedHashSet<>();
                for (Object item : (Set<?>) value) if (item instanceof String) copy.add((String) item);
                snapshot.put(entry.getKey(), copy);
            } else if (value != null) {
                snapshot.put(entry.getKey(), value);
            }
        }
        return snapshot;
    }

    private static void restorePreferences(SharedPreferences prefs, Map<String, Object> snapshot) throws IOException {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Set<?>) {
                LinkedHashSet<String> copy = new LinkedHashSet<>();
                for (Object item : (Set<?>) value) if (item instanceof String) copy.add((String) item);
                editor.putStringSet(key, copy);
            }
        }
        if (!editor.commit()) throw new IOException("Cannot rollback imported config");
    }

    private static JSONObject encodeEntry(String key, Object value) throws JSONException {
        if (value == null) return null;
        JSONObject entry = new JSONObject();
        entry.put("key", key);
        if (value instanceof Boolean) {
            entry.put("type", "boolean"); entry.put("value", value);
        } else if (value instanceof Integer) {
            entry.put("type", "int"); entry.put("value", value);
        } else if (value instanceof Long) {
            entry.put("type", "long"); entry.put("value", value);
        } else if (value instanceof Float) {
            entry.put("type", "float"); entry.put("value", ((Float) value).doubleValue());
        } else if (value instanceof String) {
            entry.put("type", "string"); entry.put("value", value);
        } else if (value instanceof Set<?>) {
            entry.put("type", "stringSet");
            JSONArray array = new JSONArray();
            for (Object item : (Set<?>) value) if (item instanceof String) array.put(item);
            entry.put("value", array);
        } else {
            return null;
        }
        return entry;
    }

    private static void decodeEntry(SharedPreferences.Editor editor, String key, JSONObject entry) throws JSONException {
        String type = entry.optString("type", "");
        switch (type) {
            case "boolean" -> editor.putBoolean(key, entry.getBoolean("value"));
            case "int" -> editor.putInt(key, entry.getInt("value"));
            case "long" -> editor.putLong(key, entry.getLong("value"));
            case "float" -> editor.putFloat(key, (float) entry.getDouble("value"));
            case "string" -> editor.putString(key, entry.optString("value", ""));
            case "stringSet" -> {
                JSONArray array = entry.optJSONArray("value");
                java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) set.add(array.optString(i, ""));
                }
                editor.putStringSet(key, set);
            }
            default -> { }
        }
    }

    private static boolean shouldSkip(String key) {
        return KEY_ENTRY_AUTHORIZED.equals(key)
                || KEY_CUSTOM_CAPTURE.equals(key)
                || KEY_CUSTOM_DRAFT.equals(key)
                || KEY_SENSITIVITY_STATUS.equals(key);
    }

    private static void writeUtf8(File target, String text) throws IOException {
        AtomicFile file = new AtomicFile(target);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            file.finishWrite(out);
        } catch (IOException error) {
            if (out != null) file.failWrite(out);
            throw error;
        }
    }

    private static String readUtf8(File file, int maxBytes) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Config too large");
                out.write(buffer, 0, read);
            }
            if (total == 0) throw new IOException("Empty config");
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
