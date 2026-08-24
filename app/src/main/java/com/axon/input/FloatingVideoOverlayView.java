package com.axon.input;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;

/**
 * Lightweight muted video surface used both by the import preview and the accessibility overlay.
 * A user-selected prefix of the media can be looped without rewriting/transcoding the source file.
 */
public final class FloatingVideoOverlayView extends FrameLayout implements TextureView.SurfaceTextureListener {
    public static final int DISPLAY_FLOATING_VIDEO = 90;
    private static final long LOOP_TICK_MS = 32L;

    public interface DragListener {
        void onDragStart(FloatingVideoOverlayView source, float rawX, float rawY);
        void onDragMove(FloatingVideoOverlayView source, float rawX, float rawY);
        void onDragEnd(FloatingVideoOverlayView source);
    }

    private final TextureView textureView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable loopTicker = new Runnable() {
        @Override public void run() {
            MediaPlayer current = player;
            if (released || current == null || !prepared) return;
            try {
                long effectiveLoop = effectiveLoopDurationMs();
                if (effectiveLoop > 0L && current.isPlaying()
                        && current.getCurrentPosition() >= Math.max(1L, effectiveLoop - 24L)) {
                    seekToStart(current);
                }
            } catch (IllegalStateException ignored) {
                return;
            }
            mainHandler.postDelayed(this, LOOP_TICK_MS);
        }
    };

    private MediaPlayer player;
    private Uri sourceUri;
    private long loopDurationMs;
    private long mediaDurationMs;
    private boolean prepared;
    private boolean released;
    private boolean dragEnabled;
    private DragListener dragListener;
    private boolean dragging;

    public FloatingVideoOverlayView(Context context) {
        super(context);
        setClipToPadding(true);
        textureView = new TextureView(context);
        textureView.setSurfaceTextureListener(this);
        addView(textureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setOnTouchListener(this::handleDragTouch);
    }

    public void setVideoUri(Uri uri, long loopDurationMs) {
        sourceUri = uri;
        this.loopDurationMs = Math.max(0L, loopDurationMs);
        mediaDurationMs = 0L;
        released = false;
        if (textureView.isAvailable()) preparePlayer(textureView.getSurfaceTexture());
    }

    public void setVideoFile(File file, long loopDurationMs) {
        setVideoUri(file == null ? null : Uri.fromFile(file), loopDurationMs);
    }

    public void setLoopDurationMs(long durationMs) {
        loopDurationMs = Math.max(0L, durationMs);
        MediaPlayer current = player;
        if (!prepared || current == null) return;
        try {
            if (current.getCurrentPosition() >= effectiveLoopDurationMs()) seekToStart(current);
        } catch (IllegalStateException ignored) {
        }
    }

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled) dragging = false;
        setClickable(enabled);
    }

    public void release() {
        released = true;
        prepared = false;
        mainHandler.removeCallbacks(loopTicker);
        MediaPlayer current = player;
        player = null;
        if (current != null) {
            try { current.setSurface(null); } catch (Throwable ignored) {}
            try { current.release(); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        preparePlayer(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // TextureView surfaces are transient (rotation, relayout, window recreation).  Releasing
        // only the player keeps the view reusable when onSurfaceTextureAvailable fires again.
        releasePlayerOnly();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private void preparePlayer(SurfaceTexture surfaceTexture) {
        releasePlayerOnly();
        Uri uri = sourceUri;
        if (released || uri == null || surfaceTexture == null) return;
        try {
            MediaPlayer next = new MediaPlayer();
            player = next;
            next.setDataSource(getContext(), uri);
            Surface surface = new Surface(surfaceTexture);
            next.setSurface(surface);
            surface.release();
            next.setVolume(0f, 0f);
            next.setLooping(false);
            next.setOnPreparedListener(mediaPlayer -> {
                if (released || mediaPlayer != player) return;
                prepared = true;
                mediaDurationMs = Math.max(0, mediaPlayer.getDuration());
                try {
                    mediaPlayer.start();
                } catch (IllegalStateException ignored) {
                    return;
                }
                mainHandler.removeCallbacks(loopTicker);
                mainHandler.post(loopTicker);
            });
            next.setOnCompletionListener(mediaPlayer -> {
                if (!released && mediaPlayer == player) {
                    seekToStart(mediaPlayer);
                    try { mediaPlayer.start(); } catch (IllegalStateException ignored) {}
                }
            });
            next.setOnErrorListener((mediaPlayer, what, extra) -> {
                prepared = false;
                mainHandler.removeCallbacks(loopTicker);
                mainHandler.post(() -> {
                    if (player == mediaPlayer) releasePlayerOnly();
                });
                return true;
            });
            next.prepareAsync();
        } catch (Throwable error) {
            releasePlayerOnly();
        }
    }

    private long effectiveLoopDurationMs() {
        if (mediaDurationMs <= 0L) return loopDurationMs;
        if (loopDurationMs <= 0L) return mediaDurationMs;
        return Math.max(1L, Math.min(loopDurationMs, mediaDurationMs));
    }

    private static void seekToStart(MediaPlayer mediaPlayer) {
        try {
            mediaPlayer.seekTo(0L, MediaPlayer.SEEK_CLOSEST);
        } catch (Throwable first) {
            try { mediaPlayer.seekTo(0); } catch (Throwable ignored) {}
        }
    }

    private void releasePlayerOnly() {
        prepared = false;
        mainHandler.removeCallbacks(loopTicker);
        MediaPlayer current = player;
        player = null;
        if (current != null) {
            try { current.setSurface(null); } catch (Throwable ignored) {}
            try { current.release(); } catch (Throwable ignored) {}
        }
    }

    private boolean handleDragTouch(View ignored, MotionEvent event) {
        if (!dragEnabled || dragListener == null || event == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dragging = true;
                dragListener.onDragStart(this, event.getRawX(), event.getRawY());
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false;
                dragListener.onDragMove(this, event.getRawX(), event.getRawY());
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false;
                dragging = false;
                dragListener.onDragEnd(this);
                return true;
            }
            default -> {
                return dragging;
            }
        }
    }
}
