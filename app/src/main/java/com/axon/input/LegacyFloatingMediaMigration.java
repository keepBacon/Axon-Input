package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** One-shot migration from the removed standalone floating-video implementation. */
final class LegacyFloatingMediaMigration {
    private static final String DURABLE_PREFS = "key_display_durable";
    private static final String LEGACY_FILE_NAME = "floating_video_media";

    private static final String KEY_NAME = "floating_video_name";
    private static final String KEY_SOURCE_DURATION = "floating_video_source_duration";
    private static final String KEY_LOOP_DURATION = "floating_video_loop_duration";
    private static final String KEY_CLIP_START = "floating_video_clip_start";
    private static final String KEY_CLIP_END = "floating_video_clip_end";
    private static final String KEY_WIDTH = "floating_video_width";
    private static final String KEY_HEIGHT = "floating_video_height";

    private static final String KEY_ENABLED = "floating_video_enabled";
    private static final String KEY_SIZE = "floating_video_size";
    private static final String KEY_OPACITY = "floating_video_opacity";
    private static final String KEY_PLAYBACK = "floating_video_playback_mode";
    private static final String KEY_CHROMA_ENABLED = "floating_video_chroma_enabled";
    private static final String KEY_CHROMA_COLOR = "floating_video_chroma_color";
    private static final String KEY_CHROMA_STRENGTH = "floating_video_chroma_strength";
    private static final String KEY_PAUSE_HOTKEY = "floating_video_pause_hotkey";
    private static final String KEY_PLAY_HOTKEY = "floating_video_play_hotkey";
    private static final String KEY_POSITION_X = "floating_video_position_x";
    private static final String KEY_POSITION_Y = "floating_video_position_y";

    private LegacyFloatingMediaMigration() {}

    static FloatingMediaStore.Item migrate(Context context, File target) throws IOException {
        Context app = context.getApplicationContext();
        File source = new File(app.getFilesDir(), LEGACY_FILE_NAME);
        SharedPreferences session = AppPreferences.get(app);
        SharedPreferences durable = app.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE);
        if (!source.isFile() || source.length() <= 0L) {
            cleanupLegacyState(session, durable, source);
            return null;
        }

        FloatingMediaStore.Item item = new FloatingMediaStore.Item();
        item.id = "legacy";
        String name = durable.getString(KEY_NAME, "");
        item.name = name == null ? "" : name;
        item.sourceDurationMs = Math.max(100L, durable.getLong(KEY_SOURCE_DURATION, 100L));
        item.clipStartMs = clampLong(durable.getLong(KEY_CLIP_START, 0L),
                0L, Math.max(0L, item.sourceDurationMs - 100L));
        long legacyLoopEnd = durable.getLong(KEY_LOOP_DURATION, item.sourceDurationMs);
        long rawEnd = durable.contains(KEY_CLIP_END)
                ? durable.getLong(KEY_CLIP_END, item.sourceDurationMs)
                : legacyLoopEnd;
        item.clipEndMs = Math.max(item.clipStartMs + 100L,
                Math.min(item.sourceDurationMs, rawEnd <= 0L ? item.sourceDurationMs : rawEnd));
        item.sourceWidth = Math.max(1, durable.getInt(KEY_WIDTH, 16));
        item.sourceHeight = Math.max(1, durable.getInt(KEY_HEIGHT, 9));
        item.sizePercent = clamp(session.getInt(KEY_SIZE, 100), 25, 500);
        item.opacityPercent = clamp(session.getInt(KEY_OPACITY, 100), 0, 100);
        item.playbackMode = session.getInt(KEY_PLAYBACK, FloatingVideoOverlayView.PLAYBACK_LOOP)
                == FloatingVideoOverlayView.PLAYBACK_ONCE
                ? FloatingVideoOverlayView.PLAYBACK_ONCE : FloatingVideoOverlayView.PLAYBACK_LOOP;
        item.chromaEnabled = session.getBoolean(KEY_CHROMA_ENABLED, false);
        item.chromaSampled = item.chromaEnabled;
        item.chromaColor = 0xff000000 | (session.getInt(KEY_CHROMA_COLOR, 0xff00ff00) & 0x00ffffff);
        item.chromaStrength = clamp(session.getInt(KEY_CHROMA_STRENGTH, 36),
                0, FloatingMediaStore.CHROMA_STRENGTH_MAX);
        item.pauseHotkey = validHotkey(session.getInt(KEY_PAUSE_HOTKEY, -1));
        item.playHotkey = validHotkey(session.getInt(KEY_PLAY_HOTKEY, -1));
        item.xPercent = clamp(session.getInt(KEY_POSITION_X, 50), -300, 400);
        item.yPercent = clamp(session.getInt(KEY_POSITION_Y, 50), -300, 400);
        item.enabled = session.getBoolean(KEY_ENABLED, true);

        copyFile(source, target);
        return item;
    }

    /** Called only after the migrated item has been persisted by FloatingMediaStore. */
    static void complete(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences session = AppPreferences.get(app);
        SharedPreferences durable = app.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE);
        cleanupLegacyState(session, durable, new File(app.getFilesDir(), LEGACY_FILE_NAME));
    }

    private static void cleanupLegacyState(SharedPreferences session, SharedPreferences durable, File source) {
        session.edit()
                .remove(KEY_ENABLED)
                .remove(KEY_SIZE)
                .remove(KEY_OPACITY)
                .remove(KEY_PLAYBACK)
                .remove(KEY_CHROMA_ENABLED)
                .remove(KEY_CHROMA_COLOR)
                .remove(KEY_CHROMA_STRENGTH)
                .remove(KEY_PAUSE_HOTKEY)
                .remove(KEY_PLAY_HOTKEY)
                .remove(KEY_POSITION_X)
                .remove(KEY_POSITION_Y)
                .apply();
        durable.edit()
                .remove(KEY_NAME)
                .remove(KEY_SOURCE_DURATION)
                .remove(KEY_LOOP_DURATION)
                .remove(KEY_CLIP_START)
                .remove(KEY_CLIP_END)
                .remove(KEY_WIDTH)
                .remove(KEY_HEIGHT)
                .apply();
        try {
            source.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        }
    }

    private static int validHotkey(int value) {
        return value >= 0 && InputBinding.isValid(value) ? value : -1;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
