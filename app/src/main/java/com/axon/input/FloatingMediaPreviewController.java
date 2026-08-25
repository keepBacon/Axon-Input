package com.axon.input;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Owns all floating-media preview views inside the Super Custom editor canvas. */
final class FloatingMediaPreviewController {
    private final Context context;
    private final FrameLayout canvas;
    private final Consumer<String> settingsAction;
    private final Runnable chromeFrontAction;
    private final Map<String, FloatingVideoOverlayView> previews = new HashMap<>();
    private final Map<String, FloatingMediaStore.Item> previewStates = new HashMap<>();
    private final Map<String, DragState> dragStates = new HashMap<>();
    private boolean layoutPassPosted;

    FloatingMediaPreviewController(Context context, FrameLayout canvas,
                                   Consumer<String> settingsAction, Runnable chromeFrontAction) {
        this.context = context;
        this.canvas = canvas;
        this.settingsAction = settingsAction;
        this.chromeFrontAction = chromeFrontAction;
    }

    int sync() {
        List<FloatingMediaStore.Item> items = FloatingMediaStore.list(context);
        Set<String> liveIds = new HashSet<>(Math.max(4, items.size() * 2));
        for (FloatingMediaStore.Item item : items) {
            if (item == null || item.id == null || item.id.isEmpty() || !item.enabled) continue;
            File file = FloatingMediaStore.fileFor(context, item.id);
            if (!file.isFile()) continue;
            liveIds.add(item.id);
            FloatingVideoOverlayView preview = previews.get(item.id);
            if (preview == null) createPreview(item, file);
            else applyItem(item);
        }
        for (String id : new ArrayList<>(previews.keySet())) {
            if (!liveIds.contains(id)) release(id);
        }
        scheduleLayoutPass();
        return items.size();
    }

    void onHostResume() {
        for (FloatingVideoOverlayView preview : previews.values()) preview.onHostResume();
    }

    void onHostPause() {
        for (FloatingVideoOverlayView preview : previews.values()) preview.onHostPause();
    }

    FloatingVideoOverlayView getPreview(String mediaId) {
        return previews.get(mediaId);
    }

    void rememberItem(FloatingMediaStore.Item item) {
        if (item != null && item.id != null) previewStates.put(item.id, item.copy());
    }

    void applyItem(FloatingMediaStore.Item item) {
        if (item == null || item.id == null) return;
        FloatingVideoOverlayView preview = previews.get(item.id);
        if (preview == null) {
            if (item.enabled) {
                File file = FloatingMediaStore.fileFor(context, item.id);
                if (file.isFile()) createPreview(item, file);
            }
            return;
        }
        FloatingMediaStore.Item previous = previewStates.get(item.id);
        if (previous == null || previous.opacityPercent != item.opacityPercent) {
            preview.setVideoOpacity(item.opacityPercent / 100f);
        }
        if (previous == null || previous.chromaEnabled != item.chromaEnabled
                || previous.chromaColor != item.chromaColor || previous.chromaStrength != item.chromaStrength) {
            preview.setChromaKey(item.chromaEnabled, item.chromaColor, item.chromaStrength);
        }
        if (previous == null || previous.soundEnabled != item.soundEnabled) {
            preview.setSoundEnabled(item.soundEnabled);
        }
        if (previous == null || previous.sourceWidth != item.sourceWidth || previous.sourceHeight != item.sourceHeight) {
            preview.setVideoDisplaySize(item.sourceWidth, item.sourceHeight);
        }
        if (previous == null || previous.clipStartMs != item.clipStartMs || previous.clipEndMs != item.clipEndMs) {
            preview.setClipRangeMs(item.clipStartMs, item.clipEndMs);
        }
        if (previous == null || previous.playbackMode != item.playbackMode) {
            preview.setPlaybackMode(item.playbackMode);
        }
        if (previous == null || previous.sizePercent != item.sizePercent
                || previous.sourceWidth != item.sourceWidth || previous.sourceHeight != item.sourceHeight
                || Float.compare(previous.xPercent, item.xPercent) != 0
                || Float.compare(previous.yPercent, item.yPercent) != 0) {
            applyLayout(item.id, item);
        }
        previewStates.put(item.id, item.copy());
    }

    void applyAllLayouts() {
        if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        for (Map.Entry<String, FloatingMediaStore.Item> entry : previewStates.entrySet()) {
            applyLayout(entry.getKey(), entry.getValue());
        }
    }

    private void scheduleLayoutPass() {
        if (layoutPassPosted) return;
        layoutPassPosted = true;
        canvas.post(() -> {
            layoutPassPosted = false;
            applyAllLayouts();
            chromeFrontAction.run();
        });
    }


    void applyLayout(String mediaId, FloatingMediaStore.Item item) {
        if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0 || item == null) return;
        FloatingVideoOverlayView preview = previews.get(mediaId);
        if (preview == null) return;
        int[] size = FloatingMediaLayout.sizePx(context, item);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) preview.getLayoutParams();
        params.width = size[0];
        params.height = size[1];
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = Math.round(canvas.getWidth() * (item.xPercent / 100f));
        params.topMargin = Math.round(canvas.getHeight() * (item.yPercent / 100f));
        preview.setLayoutParams(params);
    }

    void release(String mediaId) {
        FloatingVideoOverlayView preview = previews.remove(mediaId);
        previewStates.remove(mediaId);
        dragStates.remove(mediaId);
        if (preview == null) return;
        try {
            preview.release();
        } catch (Throwable ignored) {
        }
        if (preview.getParent() == canvas) {
            try {
                canvas.removeView(preview);
            } catch (Throwable ignored) {
            }
        }
    }

    void releaseAll() {
        for (String id : new ArrayList<>(previews.keySet())) release(id);
        previews.clear();
        previewStates.clear();
        dragStates.clear();
    }

    private void createPreview(FloatingMediaStore.Item item, File file) {
        if (item == null || file == null || !file.isFile()) return;
        String mediaId = item.id;
        FloatingVideoOverlayView preview = new FloatingVideoOverlayView(context);
        preview.setVideoOpacity(item.opacityPercent / 100f);
        preview.setChromaKey(item.chromaEnabled, item.chromaColor, item.chromaStrength);
        preview.setSoundEnabled(item.soundEnabled);
        preview.setVideoDisplaySize(item.sourceWidth, item.sourceHeight);
        preview.setVideoFile(file, item.clipStartMs, item.clipEndMs, item.playbackMode);
        preview.setDragEnabled(true);
        preview.setOnClickListener(v -> settingsAction.accept(mediaId));
        preview.setDragListener(new FloatingVideoOverlayView.DragListener() {
            @Override
            public void onDragStart(FloatingVideoOverlayView source, float rawX, float rawY) {
                source.bringToFront();
                chromeFrontAction.run();
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) source.getLayoutParams();
                dragStates.put(mediaId, new DragState(rawX, rawY, params.leftMargin, params.topMargin));
            }

            @Override
            public void onDragMove(FloatingVideoOverlayView source, float rawX, float rawY) {
                DragState state = dragStates.get(mediaId);
                if (state == null) return;
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) source.getLayoutParams();
                params.leftMargin = Math.round(state.left + rawX - state.rawX);
                params.topMargin = Math.round(state.top + rawY - state.rawY);
                params.gravity = Gravity.TOP | Gravity.START;
                source.setLayoutParams(params);
            }

            @Override
            public void onDragEnd(FloatingVideoOverlayView source) {
                dragStates.remove(mediaId);
                savePosition(mediaId, source);
            }
        });
        previews.put(mediaId, preview);
        previewStates.put(mediaId, item.copy());
        canvas.addView(preview, new FrameLayout.LayoutParams(1, 1));
        applyLayout(mediaId, item);
    }

    private void savePosition(String mediaId, FloatingVideoOverlayView preview) {
        if (preview == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        FloatingMediaStore.Item current = previewStates.get(mediaId);
        if (current == null) return;
        FloatingMediaStore.Item item = current.copy();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) preview.getLayoutParams();
        item.xPercent = (params.leftMargin / (float) canvas.getWidth()) * 100f;
        item.yPercent = (params.topMargin / (float) canvas.getHeight()) * 100f;
        FloatingMediaStore.update(context, item, true);
        previewStates.put(mediaId, item.copy());
    }

    private static final class DragState {
        final float rawX;
        final float rawY;
        final int left;
        final int top;

        DragState(float rawX, float rawY, int left, int top) {
            this.rawX = rawX;
            this.rawY = rawY;
            this.left = left;
            this.top = top;
        }
    }
}
