package com.axon.input;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns runtime windows for Super Custom floating media.
 *
 * This controller is intentionally independent from AccessibilityService lifecycle details: the
 * service only supplies a WindowManager and tells it when to sync/remove. Media state, window
 * diffing, drag persistence and hotkey dispatch stay in one place instead of being spread across
 * the service.
 */
final class FloatingMediaOverlayController implements FloatingVideoOverlayView.DragListener {
    private static final String WINDOW_TITLE_PREFIX = "AxonInputFloatingMedia:";

    private final Context appContext;
    private final Map<String, MediaWindow> windowsById = new HashMap<>();
    private final Map<FloatingVideoOverlayView, MediaWindow> windowsByView = new HashMap<>();
    private WindowManager windowManager;

    FloatingMediaOverlayController(Context context) {
        appContext = context.getApplicationContext();
    }

    void setWindowManager(WindowManager manager) {
        if (windowManager == manager) return;
        removeAll();
        windowManager = manager;
    }

    void sync(boolean enabled, boolean dragEnabled) {
        if (!enabled || windowManager == null) {
            removeAll();
            return;
        }

        List<FloatingMediaStore.Item> items = FloatingMediaStore.list(appContext);
        Set<String> activeIds = new HashSet<>(Math.max(4, items.size() * 2));
        for (FloatingMediaStore.Item item : items) {
            if (item == null || !item.enabled) continue;
            activeIds.add(item.id);
            MediaWindow mediaWindow = windowsById.get(item.id);
            if (mediaWindow == null) createWindow(item, dragEnabled);
            else updateWindow(mediaWindow, item, dragEnabled);
        }

        if (windowsById.size() != activeIds.size()) {
            for (String id : new ArrayList<>(windowsById.keySet())) {
                if (!activeIds.contains(id)) removeWindow(id);
            }
        }
    }

    void dispatchHotkey(int inputCode) {
        if (inputCode < 0 || windowsById.isEmpty()) return;
        for (MediaWindow mediaWindow : windowsById.values()) {
            FloatingMediaStore.Item item = mediaWindow == null ? null : mediaWindow.item;
            if (item == null || mediaWindow.view == null) continue;
            if (item.pauseHotkey == inputCode) mediaWindow.view.togglePaused();
            else if (item.playHotkey == inputCode) mediaWindow.view.playOnce();
        }
    }

    void removeAll() {
        if (windowsById.isEmpty()) {
            windowsByView.clear();
            return;
        }
        for (String id : new ArrayList<>(windowsById.keySet())) removeWindow(id);
        windowsById.clear();
        windowsByView.clear();
    }

    @Override
    public void onDragStart(FloatingVideoOverlayView source, float rawX, float rawY) {
        MediaWindow mediaWindow = windowsByView.get(source);
        if (mediaWindow == null || mediaWindow.params == null || !mediaWindow.dragEnabled) return;
        mediaWindow.dragStartRawX = rawX;
        mediaWindow.dragStartRawY = rawY;
        mediaWindow.dragStartWindowX = mediaWindow.params.x;
        mediaWindow.dragStartWindowY = mediaWindow.params.y;
    }

    @Override
    public void onDragMove(FloatingVideoOverlayView source, float rawX, float rawY) {
        if (windowManager == null) return;
        MediaWindow mediaWindow = windowsByView.get(source);
        if (mediaWindow == null || mediaWindow.params == null || mediaWindow.view == null
                || !mediaWindow.dragEnabled) return;

        mediaWindow.params.x = mediaWindow.dragStartWindowX + Math.round(rawX - mediaWindow.dragStartRawX);
        mediaWindow.params.y = mediaWindow.dragStartWindowY + Math.round(rawY - mediaWindow.dragStartRawY);
        try {
            windowManager.updateViewLayout(mediaWindow.view, mediaWindow.params);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDragEnd(FloatingVideoOverlayView source) {
        MediaWindow mediaWindow = windowsByView.get(source);
        if (mediaWindow != null) savePosition(mediaWindow);
    }

    private MediaWindow createWindow(FloatingMediaStore.Item item, boolean dragEnabled) {
        if (item == null || item.id == null || item.id.isEmpty() || windowManager == null) return null;
        File mediaFile = FloatingMediaStore.fileFor(appContext, item.id);
        if (!mediaFile.isFile()) return null;

        MediaWindow existing = windowsById.get(item.id);
        if (existing != null) return existing;

        FloatingVideoOverlayView view = new FloatingVideoOverlayView(appContext);
        view.setDragListener(this);
        view.setPlaybackListener(source -> {
            // One-shot completion hides playback only; configuration remains available.
        });
        applyViewState(view, null, item, dragEnabled);
        view.setVideoFile(mediaFile, item.clipStartMs, item.clipEndMs, item.playbackMode);

        int[] size = FloatingMediaLayout.sizePx(appContext, item);
        DisplayMetrics metrics = appContext.getResources().getDisplayMetrics();
        boolean actualDragEnabled = OverlayDragSafety.allowDrag(
                dragEnabled, metrics, size[0], size[1]);
        view.setDragEnabled(actualDragEnabled);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size[0], size[1],
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                OverlayDragSafety.windowFlags(dragEnabled, metrics, size[0], size[1]),
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle(WINDOW_TITLE_PREFIX + item.id);

        MediaWindow mediaWindow = new MediaWindow();
        mediaWindow.id = item.id;
        mediaWindow.item = item.copy();
        mediaWindow.view = view;
        mediaWindow.params = params;
        mediaWindow.dragEnabled = actualDragEnabled;
        applyPosition(mediaWindow, item);

        try {
            windowManager.addView(view, params);
        } catch (Throwable error) {
            try {
                view.release();
            } catch (Throwable ignored) {
            }
            return null;
        }

        windowsById.put(item.id, mediaWindow);
        windowsByView.put(view, mediaWindow);
        return mediaWindow;
    }

    private void updateWindow(MediaWindow mediaWindow, FloatingMediaStore.Item item, boolean dragEnabled) {
        if (mediaWindow == null || mediaWindow.view == null || mediaWindow.params == null
                || item == null || windowManager == null) return;

        FloatingMediaStore.Item previous = mediaWindow.item;
        boolean layoutChanged = false;

        if (previous == null || previous.sizePercent != item.sizePercent
                || previous.sourceWidth != item.sourceWidth || previous.sourceHeight != item.sourceHeight) {
            int[] size = FloatingMediaLayout.sizePx(appContext, item);
            if (mediaWindow.params.width != size[0] || mediaWindow.params.height != size[1]) {
                mediaWindow.params.width = size[0];
                mediaWindow.params.height = size[1];
                layoutChanged = true;
            }
        }

        DisplayMetrics metrics = appContext.getResources().getDisplayMetrics();
        boolean actualDragEnabled = OverlayDragSafety.allowDrag(
                dragEnabled, metrics, mediaWindow.params.width, mediaWindow.params.height);
        if (mediaWindow.dragEnabled != actualDragEnabled) {
            mediaWindow.dragEnabled = actualDragEnabled;
            mediaWindow.view.setDragEnabled(actualDragEnabled);
        }
        int nextFlags = OverlayDragSafety.windowFlags(
                dragEnabled, metrics, mediaWindow.params.width, mediaWindow.params.height);
        if (mediaWindow.params.flags != nextFlags) {
            mediaWindow.params.flags = nextFlags;
            layoutChanged = true;
        }

        applyViewState(mediaWindow.view, previous, item, actualDragEnabled);

        if (previous == null || Float.compare(previous.xPercent, item.xPercent) != 0
                || Float.compare(previous.yPercent, item.yPercent) != 0) {
            applyPosition(mediaWindow, item);
            layoutChanged = true;
        }

        mediaWindow.item = item.copy();
        if (layoutChanged) {
            try {
                windowManager.updateViewLayout(mediaWindow.view, mediaWindow.params);
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyViewState(FloatingVideoOverlayView view, FloatingMediaStore.Item previous,
                                FloatingMediaStore.Item item, boolean dragEnabled) {
        if (view == null || item == null) return;
        if (previous == null) view.setDragEnabled(dragEnabled);
        if (previous == null || previous.sourceWidth != item.sourceWidth || previous.sourceHeight != item.sourceHeight) {
            view.setVideoDisplaySize(item.sourceWidth, item.sourceHeight);
        }
        if (previous == null || previous.opacityPercent != item.opacityPercent) {
            view.setVideoOpacity(item.opacityPercent / 100f);
        }
        if (previous == null || previous.chromaEnabled != item.chromaEnabled
                || previous.chromaColor != item.chromaColor || previous.chromaStrength != item.chromaStrength) {
            view.setChromaKey(item.chromaEnabled, item.chromaColor, item.chromaStrength);
        }
        if (previous == null || previous.soundEnabled != item.soundEnabled) {
            view.setSoundEnabled(item.soundEnabled);
        }
        if (previous != null && (previous.clipStartMs != item.clipStartMs || previous.clipEndMs != item.clipEndMs)) {
            view.setClipRangeMs(item.clipStartMs, item.clipEndMs);
        }
        if (previous != null && previous.playbackMode != item.playbackMode) {
            view.setPlaybackMode(item.playbackMode);
        }
    }

    private void applyPosition(MediaWindow mediaWindow, FloatingMediaStore.Item item) {
        DisplayMetrics metrics = appContext.getResources().getDisplayMetrics();
        mediaWindow.params.x = Math.round(metrics.widthPixels * (item.xPercent / 100f));
        mediaWindow.params.y = Math.round(metrics.heightPixels * (item.yPercent / 100f));
    }

    private void savePosition(MediaWindow mediaWindow) {
        if (mediaWindow == null || mediaWindow.params == null || mediaWindow.item == null) return;
        DisplayMetrics metrics = appContext.getResources().getDisplayMetrics();
        FloatingMediaStore.Item updated = mediaWindow.item.copy();
        if (metrics.widthPixels > 0) {
            updated.xPercent = (mediaWindow.params.x / (float) metrics.widthPixels) * 100f;
        }
        if (metrics.heightPixels > 0) {
            updated.yPercent = (mediaWindow.params.y / (float) metrics.heightPixels) * 100f;
        }
        mediaWindow.item = updated;
        FloatingMediaStore.update(appContext, updated, false);
    }

    private void removeWindow(String id) {
        MediaWindow mediaWindow = windowsById.remove(id);
        if (mediaWindow == null) return;
        if (mediaWindow.view != null) windowsByView.remove(mediaWindow.view);
        if (mediaWindow.view == null) return;

        try {
            mediaWindow.view.release();
        } catch (Throwable ignored) {
        }
        if (windowManager != null) {
            try {
                windowManager.removeViewImmediate(mediaWindow.view);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class MediaWindow {
        String id;
        FloatingMediaStore.Item item;
        FloatingVideoOverlayView view;
        WindowManager.LayoutParams params;
        float dragStartRawX;
        float dragStartRawY;
        int dragStartWindowX;
        int dragStartWindowY;
        boolean dragEnabled;
    }
}
