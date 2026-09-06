package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Multi-instance floating media storage used by Super Custom Display. */
final class FloatingMediaStore {
    private static final String PREFS = "floating_media_multi_v1";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_MIGRATED = "legacy_migrated";
    private static final String FILE_PREFIX = "floating_media_";
    private static final String FILE_SUFFIX = ".mp4";
    private static final int MAX_ITEMS = 24;
    private static final long MAX_MEDIA_BYTES = 1L * 1024L * 1024L * 1024L;
    static final int CHROMA_STRENGTH_MAX = 300;
    static final int HOTKEY_INPUT_KEYBOARD = 1;
    static final int HOTKEY_INPUT_MOUSE = 1 << 1;
    static final int HOTKEY_INPUT_GAMEPAD = 1 << 2;
    static final float DEFAULT_X_PERCENT = 24f;
    static final float DEFAULT_Y_PERCENT = 22f;

    private static String cachedItemsRaw;
    private static ArrayList<Item> cachedItems;
    private static boolean filesCleaned;
    private static boolean migrationChecked;

    static final class Item {
        String id = "";
        String name = "";
        long sourceDurationMs = 100L;
        long clipStartMs = 0L;
        long clipEndMs = 100L;
        int sourceWidth = 16;
        int sourceHeight = 9;
        int sizePercent = 100;
        int opacityPercent = 100;
        int playbackMode = FloatingVideoOverlayView.PLAYBACK_LOOP;
        boolean chromaEnabled;
        boolean chromaSampled;
        int chromaColor = 0xff00ff00;
        int chromaStrength = 36;
        boolean soundEnabled;
        int pauseHotkey = -1;
        int playHotkey = -1;
        float xPercent = DEFAULT_X_PERCENT;
        float yPercent = DEFAULT_Y_PERCENT;
        boolean enabled = true;

        Item copy() {
            Item out = new Item();
            out.id = id;
            out.name = name;
            out.sourceDurationMs = sourceDurationMs;
            out.clipStartMs = clipStartMs;
            out.clipEndMs = clipEndMs;
            out.sourceWidth = sourceWidth;
            out.sourceHeight = sourceHeight;
            out.sizePercent = sizePercent;
            out.opacityPercent = opacityPercent;
            out.playbackMode = playbackMode;
            out.chromaEnabled = chromaEnabled;
            out.chromaSampled = chromaSampled;
            out.chromaColor = chromaColor;
            out.chromaStrength = chromaStrength;
            out.soundEnabled = soundEnabled;
            out.pauseHotkey = pauseHotkey;
            out.playHotkey = playHotkey;
            out.xPercent = xPercent;
            out.yPercent = yPercent;
            out.enabled = enabled;
            return out;
        }
    }

    private FloatingMediaStore() {}

    static synchronized List<Item> list(Context context) {
        return copyItems(loadCachedItems(context));
    }

    private static ArrayList<Item> loadCachedItems(Context context) {
        migrateLegacyIfNeeded(context);
        String raw = prefs(context).getString(KEY_ITEMS, "[]");
        if (raw == null) raw = "[]";
        if (cachedItems == null || cachedItemsRaw == null || !cachedItemsRaw.equals(raw)) {
            cachedItems = parseItems(context, raw);
            cachedItemsRaw = raw;
        }
        if (!filesCleaned) {
            filesCleaned = true;
            cleanupManagedFiles(context, cachedItems);
        }
        return cachedItems;
    }

    private static ArrayList<Item> parseItems(Context context, String raw) {
        ArrayList<Item> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Item item = fromJson(object);
                if (!item.id.isEmpty() && fileFor(context, item.id).isFile()) out.add(item);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void cleanupManagedFiles(Context context, List<Item> items) {
        try {
            java.util.HashSet<String> liveNames = new java.util.HashSet<>();
            if (items != null) {
                for (Item item : items) {
                    if (item == null || item.id == null || item.id.isEmpty()) continue;
                    liveNames.add(FILE_PREFIX + sanitizeId(item.id) + FILE_SUFFIX);
                }
            }
            File dir = context.getApplicationContext().getFilesDir();
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file == null || !file.isFile()) continue;
                String name = file.getName();
                boolean temp = name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX + ".tmp");
                boolean orphan = name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)
                        && !liveNames.contains(name);
                if (temp || orphan) {
                    try { file.delete(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static ArrayList<Item> copyItems(List<Item> source) {
        ArrayList<Item> out = new ArrayList<>(source == null ? 0 : source.size());
        if (source != null) for (Item item : source) if (item != null) out.add(item.copy());
        return out;
    }

    static synchronized Item get(Context context, String id) {
        if (id == null) return null;
        for (Item item : loadCachedItems(context)) {
            if (id.equals(item.id)) return item.copy();
        }
        return null;
    }

    static File fileFor(Context context, String id) {
        return new File(context.getApplicationContext().getFilesDir(),
                FILE_PREFIX + sanitizeId(id) + FILE_SUFFIX);
    }

    private static String sanitizeId(String id) {
        if (id == null || id.isEmpty()) return "";
        StringBuilder safe = null;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (allowed) {
                if (safe != null) safe.append(c);
            } else if (safe == null) {
                safe = new StringBuilder(id.length());
                safe.append(id, 0, i);
            }
        }
        return safe == null ? id : safe.toString();
    }

    static synchronized Item add(Context context, Uri uri, String displayName, long sourceDurationMs,
                                 long clipStartMs, long clipEndMs, int sourceWidth, int sourceHeight,
                                 boolean chromaEnabled, boolean chromaSampled,
                                 int chromaColor, int chromaStrength) throws IOException {
        if (uri == null) throw new IOException("Missing video Uri");
        List<Item> items = list(context);
        if (items.size() >= MAX_ITEMS) throw new IOException("Too many floating media items");

        Item item = new Item();
        item.id = UUID.randomUUID().toString().replace("-", "");
        item.name = displayName == null ? "" : displayName;
        item.sourceDurationMs = Math.max(100L, sourceDurationMs);
        item.clipStartMs = Math.max(0L, Math.min(Math.max(0L, item.sourceDurationMs - 100L), clipStartMs));
        item.clipEndMs = Math.max(item.clipStartMs + 100L,
                Math.min(item.sourceDurationMs, clipEndMs <= 0L ? item.sourceDurationMs : clipEndMs));
        item.sourceWidth = Math.max(1, sourceWidth);
        item.sourceHeight = Math.max(1, sourceHeight);
        item.chromaEnabled = chromaEnabled;
        item.chromaSampled = chromaSampled;
        item.chromaColor = 0xff000000 | (chromaColor & 0x00ffffff);
        item.chromaStrength = clampChromaStrength(chromaStrength);
        int cascade = items.size() % 6;
        item.xPercent = 18 + cascade * 5;
        item.yPercent = 18 + cascade * 4;

        copyUriToFile(context, uri, fileFor(context, item.id));
        items.add(item);
        save(context, items);
        refresh();
        return item.copy();
    }

    static synchronized boolean update(Context context, Item updated, boolean refreshRuntime) {
        if (updated == null || updated.id == null || updated.id.isEmpty()) return false;
        List<Item> items = list(context);
        normalize(updated);
        for (int i = 0; i < items.size(); i++) {
            Item previous = items.get(i);
            if (!updated.id.equals(previous.id)) continue;
            if (sameItem(previous, updated)) return false;
            items.set(i, updated.copy());
            save(context, items);
            if (refreshRuntime) refresh();
            return true;
        }
        return false;
    }

    static synchronized void remove(Context context, String id) {
        if (id == null) return;
        List<Item> items = list(context);
        boolean removed = items.removeIf(item -> id.equals(item.id));
        File file = fileFor(context, id);
        boolean fileExisted = file.exists();
        if (fileExisted) try { file.delete(); } catch (Throwable ignored) {}
        if (!removed && !fileExisted) return;
        save(context, items);
        refresh();
    }


    static synchronized int hotkeyInputMask(Context context) {
        int mask = 0;
        for (Item item : loadCachedItems(context)) {
            if (!item.enabled) continue;
            int[] hotkeys = {item.pauseHotkey, item.playHotkey};
            for (int hotkey : hotkeys) {
                if (InputBinding.isKeyboard(hotkey)) mask |= HOTKEY_INPUT_KEYBOARD;
                else if (InputBinding.isMouse(hotkey)) mask |= HOTKEY_INPUT_MOUSE;
                else if (InputBinding.isGamepad(hotkey)) mask |= HOTKEY_INPUT_GAMEPAD;
                if (mask == (HOTKEY_INPUT_KEYBOARD | HOTKEY_INPUT_MOUSE | HOTKEY_INPUT_GAMEPAD)) return mask;
            }
        }
        return mask;
    }

    static synchronized boolean hotkeyConflicts(Context context, String excludeId, int inputCode) {
        for (Item item : loadCachedItems(context)) {
            if (excludeId != null && excludeId.equals(item.id)) continue;
            if (inputCode == item.pauseHotkey || inputCode == item.playHotkey) return true;
        }
        return false;
    }


    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void save(Context context, List<Item> items) {
        JSONArray array = new JSONArray();
        ArrayList<Item> normalized = new ArrayList<>();
        for (Item item : items) {
            if (item == null) continue;
            normalize(item);
            normalized.add(item.copy());
            array.put(toJson(item));
        }
        String raw = array.toString();
        if (cachedItemsRaw == null || !cachedItemsRaw.equals(raw)) {
            prefs(context).edit().putString(KEY_ITEMS, raw).apply();
        }
        cachedItemsRaw = raw;
        cachedItems = normalized;
    }

    private static JSONObject toJson(Item item) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", item.id);
            object.put("name", item.name);
            object.put("sourceDuration", item.sourceDurationMs);
            object.put("clipStart", item.clipStartMs);
            object.put("clipEnd", item.clipEndMs);
            object.put("width", item.sourceWidth);
            object.put("height", item.sourceHeight);
            object.put("size", item.sizePercent);
            object.put("opacity", item.opacityPercent);
            object.put("playback", item.playbackMode);
            object.put("chromaEnabled", item.chromaEnabled);
            object.put("chromaSampled", item.chromaSampled);
            object.put("chromaColor", item.chromaColor);
            object.put("chromaStrength", item.chromaStrength);
            object.put("soundEnabled", item.soundEnabled);
            object.put("pauseHotkey", item.pauseHotkey);
            object.put("playHotkey", item.playHotkey);
            object.put("x", item.xPercent);
            object.put("y", item.yPercent);
            object.put("enabled", item.enabled);
        } catch (Throwable ignored) {
        }
        return object;
    }

    private static Item fromJson(JSONObject object) {
        Item item = new Item();
        item.id = object.optString("id", "");
        item.name = object.optString("name", "");
        item.sourceDurationMs = Math.max(100L, object.optLong("sourceDuration", 100L));
        item.clipStartMs = object.optLong("clipStart", 0L);
        item.clipEndMs = object.optLong("clipEnd", item.sourceDurationMs);
        item.sourceWidth = object.optInt("width", 16);
        item.sourceHeight = object.optInt("height", 9);
        item.sizePercent = object.optInt("size", 100);
        item.opacityPercent = object.optInt("opacity", 100);
        item.playbackMode = object.optInt("playback", FloatingVideoOverlayView.PLAYBACK_LOOP);
        item.chromaEnabled = object.optBoolean("chromaEnabled", false);
        item.chromaSampled = object.optBoolean("chromaSampled", item.chromaEnabled);
        item.chromaColor = object.optInt("chromaColor", 0xff00ff00);
        item.chromaStrength = object.optInt("chromaStrength", 36);
        item.soundEnabled = object.optBoolean("soundEnabled", false);
        item.pauseHotkey = object.optInt("pauseHotkey", -1);
        item.playHotkey = object.optInt("playHotkey", -1);
        item.xPercent = (float) object.optDouble("x", 24.0);
        item.yPercent = (float) object.optDouble("y", 22.0);
        item.enabled = object.optBoolean("enabled", true);
        normalize(item);
        return item;
    }

    private static void normalize(Item item) {
        if (item.name == null) item.name = "";
        item.sourceDurationMs = Math.max(100L, item.sourceDurationMs);
        item.clipStartMs = Math.max(0L, Math.min(Math.max(0L, item.sourceDurationMs - 100L), item.clipStartMs));
        item.clipEndMs = Math.max(item.clipStartMs + 100L,
                Math.min(item.sourceDurationMs, item.clipEndMs <= 0L ? item.sourceDurationMs : item.clipEndMs));
        item.sourceWidth = Math.max(1, item.sourceWidth);
        item.sourceHeight = Math.max(1, item.sourceHeight);
        item.sizePercent = Math.max(25, Math.min(500, item.sizePercent));
        item.opacityPercent = clampPercent(item.opacityPercent);
        item.playbackMode = item.playbackMode == FloatingVideoOverlayView.PLAYBACK_ONCE
                ? FloatingVideoOverlayView.PLAYBACK_ONCE : FloatingVideoOverlayView.PLAYBACK_LOOP;
        item.chromaColor = 0xff000000 | (item.chromaColor & 0x00ffffff);
        item.chromaStrength = clampChromaStrength(item.chromaStrength);
        if (item.pauseHotkey >= 0 && !InputBinding.isValid(item.pauseHotkey)) item.pauseHotkey = -1;
        if (item.playHotkey >= 0 && !InputBinding.isValid(item.playHotkey)) item.playHotkey = -1;
        if (!Float.isFinite(item.xPercent)) item.xPercent = DEFAULT_X_PERCENT;
        if (!Float.isFinite(item.yPercent)) item.yPercent = DEFAULT_Y_PERCENT;
        // 位置使用屏幕百分比而不是“剩余可移动范围”，因此 100% 会让左上角落到屏外。
        // 保留 10% 屏幕恢复区，避免图片/视频被永久拖丢。
        item.xPercent = Math.max(0f, Math.min(90f, item.xPercent));
        item.yPercent = Math.max(0f, Math.min(90f, item.yPercent));
    }

    private static boolean sameItem(Item a, Item b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return safeEquals(a.id, b.id)
                && safeEquals(a.name, b.name)
                && a.sourceDurationMs == b.sourceDurationMs
                && a.clipStartMs == b.clipStartMs
                && a.clipEndMs == b.clipEndMs
                && a.sourceWidth == b.sourceWidth
                && a.sourceHeight == b.sourceHeight
                && a.sizePercent == b.sizePercent
                && a.opacityPercent == b.opacityPercent
                && a.playbackMode == b.playbackMode
                && a.chromaEnabled == b.chromaEnabled
                && a.chromaSampled == b.chromaSampled
                && a.chromaColor == b.chromaColor
                && a.chromaStrength == b.chromaStrength
                && a.soundEnabled == b.soundEnabled
                && a.pauseHotkey == b.pauseHotkey
                && a.playHotkey == b.playHotkey
                && Float.compare(a.xPercent, b.xPercent) == 0
                && Float.compare(a.yPercent, b.yPercent) == 0
                && a.enabled == b.enabled;
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int clampChromaStrength(int value) {
        return Math.max(0, Math.min(CHROMA_STRENGTH_MAX, value));
    }

    private static void copyUriToFile(Context context, Uri uri, File target) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        if (temp.exists() && !temp.delete()) throw new IOException("Cannot replace temp video");
        long total = 0L;
        try (InputStream in = context.getApplicationContext().getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp, false)) {
            if (in == null) throw new IOException("Cannot open video");
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_MEDIA_BYTES) throw new IOException("Video is too large");
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        } catch (IOException error) {
            temp.delete();
            throw error;
        }
        if (total <= 0L) {
            temp.delete();
            throw new IOException("Empty video");
        }
        try {
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            temp.delete();
            throw error;
        }
    }

    private static void migrateLegacyIfNeeded(Context context) {
        if (migrationChecked) return;
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean(KEY_MIGRATED, false)) {
            migrationChecked = true;
            return;
        }
        try {
            String raw = preferences.getString(KEY_ITEMS, "[]");
            ArrayList<Item> items = parseItems(context, raw == null ? "[]" : raw);
            File target = fileFor(context, "legacy");
            FloatingMediaStore.Item migrated = LegacyFloatingMediaMigration.migrate(context, target);
            if (migrated != null) {
                normalize(migrated);
                boolean alreadyPresent = false;
                for (Item item : items) {
                    if (item != null && migrated.id.equals(item.id)) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) items.add(migrated);
                save(context, items);
                LegacyFloatingMediaMigration.complete(context);
            }
            PreferenceWriter.putBooleanIfChanged(preferences, KEY_MIGRATED, true);
            migrationChecked = true;
        } catch (Throwable ignored) {
            // Leave the marker unset so a failed file copy or parse can retry next launch.
            migrationChecked = false;
        }
    }

    private static void refresh() {
        AxonInputAccessibilityService.refreshFloatingVideo();
    }
}
