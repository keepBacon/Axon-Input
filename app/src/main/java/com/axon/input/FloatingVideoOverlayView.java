package com.axon.input;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;

/**
 * Floating video surface used by both the import preview and the accessibility overlay.
 * Supports a selected [start, end] clip, loop/one-shot playback, pause/resume and replay.
 */
public final class FloatingVideoOverlayView extends FrameLayout {
    public static final int DISPLAY_FLOATING_VIDEO = 90;
    public static final int PLAYBACK_LOOP = 0;
    public static final int PLAYBACK_ONCE = 1;

    private static final long END_GUARD_MS = 18L;
    private static final long END_RECHECK_MS = 120L;

    public interface DragListener {
        void onDragStart(FloatingVideoOverlayView source, float rawX, float rawY);
        void onDragMove(FloatingVideoOverlayView source, float rawX, float rawY);
        void onDragEnd(FloatingVideoOverlayView source);
    }

    public interface PlaybackListener {
        void onOneShotFinished(FloatingVideoOverlayView source);
    }

    public interface ColorPickListener {
        void onColorPicked(int color);
    }

    private final ChromaKeyVideoSurfaceView videoSurface;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable clipEndCheck = new Runnable() {
        @Override public void run() {
            MediaPlayer current = player;
            if (released || hostPaused || seekPending || current == null || !prepared || pausedByUser || getVisibility() != VISIBLE) return;
            try {
                if (!current.isPlaying()) {
                    mainHandler.postDelayed(this, END_RECHECK_MS);
                    return;
                }
                long end = effectiveClipEndMs();
                long position = current.getCurrentPosition();
                if (end > 0L && position >= Math.max(effectiveClipStartMs() + 1L, end - END_GUARD_MS)) {
                    handleClipEnd(current);
                    return;
                }
                scheduleClipEndCheck(current, position);
            } catch (IllegalStateException ignored) {
            }
        }
    };

    private MediaPlayer player;
    private Uri sourceUri;
    private long clipStartMs;
    private long clipEndMs;
    private long mediaDurationMs;
    private int playbackMode = PLAYBACK_LOOP;
    private boolean prepared;
    private boolean released;
    private boolean pausedByUser;
    private boolean playOncePending;
    private boolean dragEnabled;
    private DragListener dragListener;
    private PlaybackListener playbackListener;
    private boolean dragging;
    private float dragDownRawX;
    private float dragDownRawY;
    private boolean dragMoved;
    private Surface outputSurface;
    private ColorPickListener colorPickListener;
    private boolean soundEnabled;
    private boolean hostPaused;
    private boolean resumeAfterHostPause;
    private boolean seekPending;

    public FloatingVideoOverlayView(Context context) {
        super(context);
        setClipToPadding(true);
        setClipChildren(true);
        videoSurface = new ChromaKeyVideoSurfaceView(context);
        videoSurface.setSurfaceListener(surface -> {
            outputSurface = surface;
            preparePlayer(surface);
        });
        addView(videoSurface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setOnTouchListener(this::handleDragTouch);
    }

    public void setVideoUri(Uri uri, long startMs, long endMs, int mode) {
        sourceUri = uri;
        clipStartMs = Math.max(0L, startMs);
        clipEndMs = Math.max(clipStartMs + 1L, endMs);
        playbackMode = normalizePlaybackMode(mode);
        mediaDurationMs = 0L;
        released = false;
        pausedByUser = false;
        playOncePending = false;
        setVisibility(VISIBLE);
        Surface surface = outputSurface;
        if (surface != null && surface.isValid()) preparePlayer(surface);
    }

    public void setVideoFile(File file, long startMs, long endMs, int mode) {
        setVideoUri(file == null ? null : Uri.fromFile(file), startMs, endMs, mode);
    }

    public void setClipRangeMs(long startMs, long endMs) {
        clipStartMs = Math.max(0L, startMs);
        clipEndMs = Math.max(clipStartMs + 1L, endMs);
        MediaPlayer current = player;
        if (!prepared || current == null) return;
        try {
            int position = current.getCurrentPosition();
            if (position < effectiveClipStartMs() || position >= effectiveClipEndMs()) {
                seekToClipStart(current);
            }
        } catch (IllegalStateException ignored) {
        }
        scheduleClipEndCheck(current);
    }

    public void setPlaybackMode(int mode) {
        int normalized = normalizePlaybackMode(mode);
        if (playbackMode == normalized) return;
        playbackMode = normalized;
        MediaPlayer current = player;
        if (!prepared || current == null) return;
        pausedByUser = false;
        playOncePending = false;
        setVisibility(VISIBLE);
        seekToClipStart(current);
        try { current.start(); } catch (IllegalStateException ignored) {}
        scheduleClipEndCheck(current);
    }

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    public void setPlaybackListener(PlaybackListener listener) {
        playbackListener = listener;
    }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled) dragging = false;
        setClickable(enabled);
    }

    public void setChromaKey(boolean enabled, int color, int strengthPercent) {
        videoSurface.setChromaKey(enabled, color, strengthPercent);
    }

    public void setVideoOpacity(float opacity) {
        videoSurface.setVideoOpacity(opacity);
    }

    public void setSoundEnabled(boolean enabled) {
        soundEnabled = enabled;
        applyPlayerVolume(player);
    }

    /** Sets the final display-space video ratio; width/height must already include metadata rotation. */
    public void setVideoDisplaySize(int width, int height) {
        videoSurface.setVideoDisplaySize(width, height);
    }

    /** Non-null listener switches touch handling from dragging to color sampling. */
    public void setColorPickListener(ColorPickListener listener) {
        colorPickListener = listener;
        if (listener != null) {
            dragging = false;
            setClickable(true);
        } else {
            setClickable(dragEnabled);
        }
    }

    public void onHostPause() {
        if (released || hostPaused) return;
        hostPaused = true;
        mainHandler.removeCallbacks(clipEndCheck);
        MediaPlayer current = player;
        resumeAfterHostPause = false;
        if (prepared && current != null) {
            try {
                resumeAfterHostPause = current.isPlaying();
                if (resumeAfterHostPause) current.pause();
            } catch (IllegalStateException ignored) {
            }
        }
        try { videoSurface.onPause(); } catch (Throwable ignored) {}
    }

    public void onHostResume() {
        if (released || !hostPaused) return;
        hostPaused = false;
        try { videoSurface.onResume(); } catch (Throwable ignored) {}
        MediaPlayer current = player;
        if (prepared && current != null && resumeAfterHostPause && !pausedByUser && getVisibility() == VISIBLE) {
            try { current.start(); } catch (IllegalStateException ignored) {}
            scheduleClipEndCheck(current);
        }
        resumeAfterHostPause = false;
    }

    /** Toggle pause/resume. Returns true when playback is paused after the call. */
    public boolean togglePaused() {
        MediaPlayer current = player;
        if (!prepared || current == null || getVisibility() != VISIBLE) return pausedByUser;
        try {
            if (current.isPlaying()) {
                current.pause();
                pausedByUser = true;
                mainHandler.removeCallbacks(clipEndCheck);
            } else {
                pausedByUser = false;
                if (current.getCurrentPosition() < effectiveClipStartMs()
                        || current.getCurrentPosition() >= effectiveClipEndMs()) {
                    seekToClipStart(current);
                }
                current.start();
                scheduleClipEndCheck(current);
            }
        } catch (IllegalStateException ignored) {
        }
        return pausedByUser;
    }

    /** Starts the selected clip from its beginning. In one-shot mode it hides again at clip end. */
    public void playOnce() {
        playOncePending = true;
        pausedByUser = false;
        setVisibility(VISIBLE);
        MediaPlayer current = player;
        if (!prepared || current == null) return;
        seekToClipStart(current);
        try { current.start(); } catch (IllegalStateException ignored) {}
        scheduleClipEndCheck(current);
    }

    public void release() {
        released = true;
        prepared = false;
        mainHandler.removeCallbacks(clipEndCheck);
        releasePlayerOnly();
        outputSurface = null;
        try { videoSurface.releaseVideoSurface(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    private void preparePlayer(Surface surface) {
        releasePlayerOnly();
        Uri uri = sourceUri;
        if (released || uri == null || surface == null || !surface.isValid()) return;
        try {
            MediaPlayer next = new MediaPlayer();
            player = next;
            configureAudioIsolation(next);
            next.setDataSource(getContext(), uri);
            next.setSurface(surface);
            try { next.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT); }
            catch (Throwable ignored) {}
            applyPlayerVolume(next);
            next.setLooping(false);
            next.setOnPreparedListener(mediaPlayer -> {
                if (released || mediaPlayer != player) return;
                prepared = true;
                mediaDurationMs = Math.max(0, mediaPlayer.getDuration());
                setVisibility(VISIBLE);
                // Some vendor media stacks may reconfigure the audio track during prepare.
                // Re-assert the requested local volume before any frame is allowed to start.
                applyPlayerVolume(mediaPlayer);
                seekToClipStart(mediaPlayer);
                if (!hostPaused) {
                    try { mediaPlayer.start(); } catch (IllegalStateException ignored) { return; }
                    mainHandler.removeCallbacks(clipEndCheck);
                    scheduleClipEndCheck(mediaPlayer);
                } else {
                    resumeAfterHostPause = true;
                }
            });
            next.setOnSeekCompleteListener(mediaPlayer -> {
                if (released || mediaPlayer != player) return;
                seekPending = false;
                if (hostPaused || pausedByUser || getVisibility() != VISIBLE) return;
                try {
                    if (!mediaPlayer.isPlaying()) mediaPlayer.start();
                } catch (IllegalStateException ignored) {
                    return;
                }
                scheduleClipEndCheck(mediaPlayer);
            });
            next.setOnCompletionListener(mediaPlayer -> {
                if (released || mediaPlayer != player) return;
                handleClipEnd(mediaPlayer);
            });
            next.setOnErrorListener((mediaPlayer, what, extra) -> {
                prepared = false;
                mainHandler.removeCallbacks(clipEndCheck);
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


    /**
     * Floating media is visual overlay content. Its optional sound is local monitoring only and must
     * not be injected into screen recordings / live-stream internal-audio capture. Android 10+
     * exposes a per-player capture policy, so isolate only this MediaPlayer instead of disabling
     * playback capture for the whole Axon Input process.
     */
    private static void configureAudioIsolation(MediaPlayer mediaPlayer) {
        if (mediaPlayer == null) return;
        try {
            AudioAttributes.Builder attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE);

            // Android 10 (API 29) added AudioAttributes.Builder#setAllowedCapturePolicy().
            // Invoke it reflectively so Axon still compiles with vendor/Termux android.jar stubs
            // that omit some API-29 constants even when placed under android-36.
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    AudioAttributes.Builder.class
                            .getMethod("setAllowedCapturePolicy", int.class)
                            .invoke(attributes, 3 /* capture policy: none */);
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    // Some vendor framework stubs/implementations do not expose this public API.
                    // Local muting below remains the fallback.
                }
            }
            mediaPlayer.setAudioAttributes(attributes.build());
        } catch (Throwable ignored) {
            // Volume muting remains the fallback on old/vendor media implementations.
        }
    }

    private void applyPlayerVolume(MediaPlayer mediaPlayer) {
        if (mediaPlayer == null) return;
        try {
            float volume = soundEnabled ? 1f : 0f;
            mediaPlayer.setVolume(volume, volume);
        } catch (IllegalStateException ignored) {
        }
    }

    private void handleClipEnd(MediaPlayer current) {
        boolean forceSinglePass = playOncePending;
        playOncePending = false;
        if (playbackMode == PLAYBACK_LOOP && !forceSinglePass) {
            seekToClipStart(current);
            try { current.start(); } catch (IllegalStateException ignored) {}
            scheduleClipEndCheck(current);
            return;
        }

        // A manual one-shot while loop mode is selected returns to normal looping after that pass.
        if (playbackMode == PLAYBACK_LOOP) {
            seekToClipStart(current);
            try { current.start(); } catch (IllegalStateException ignored) {}
            scheduleClipEndCheck(current);
            return;
        }

        // One-shot mode: keep the feature enabled but remove the picture until the next replay.
        try { current.pause(); } catch (IllegalStateException ignored) {}
        pausedByUser = false;
        setVisibility(INVISIBLE);
        PlaybackListener listener = playbackListener;
        if (listener != null) listener.onOneShotFinished(this);
    }

    private void scheduleClipEndCheck(MediaPlayer current) {
        if (released || hostPaused || seekPending || current == null || current != player || !prepared || pausedByUser
                || getVisibility() != VISIBLE) return;
        long position;
        try {
            position = current.getCurrentPosition();
        } catch (IllegalStateException ignored) {
            return;
        }
        scheduleClipEndCheck(current, position);
    }

    private void scheduleClipEndCheck(MediaPlayer current, long positionMs) {
        if (released || hostPaused || seekPending || current == null || current != player || !prepared || pausedByUser
                || getVisibility() != VISIBLE) return;
        long end = effectiveClipEndMs();
        long remaining = Math.max(1L, end - Math.max(0L, positionMs) - END_GUARD_MS);
        mainHandler.removeCallbacks(clipEndCheck);
        mainHandler.postDelayed(clipEndCheck, remaining);
    }

    private long effectiveClipStartMs() {
        if (mediaDurationMs <= 0L) return Math.max(0L, clipStartMs);
        return Math.max(0L, Math.min(Math.max(0L, mediaDurationMs - 1L), clipStartMs));
    }

    private long effectiveClipEndMs() {
        long start = effectiveClipStartMs();
        long requested = Math.max(start + 1L, clipEndMs);
        if (mediaDurationMs <= 0L) return requested;
        return Math.max(start + 1L, Math.min(mediaDurationMs, requested));
    }

    private void seekToClipStart(MediaPlayer mediaPlayer) {
        long start = effectiveClipStartMs();
        seekPending = true;
        try {
            mediaPlayer.seekTo(start, MediaPlayer.SEEK_CLOSEST);
        } catch (Throwable first) {
            try {
                mediaPlayer.seekTo((int) Math.min(Integer.MAX_VALUE, start));
            } catch (Throwable ignored) {
                seekPending = false;
            }
        }
    }

    private static int normalizePlaybackMode(int mode) {
        return mode == PLAYBACK_ONCE ? PLAYBACK_ONCE : PLAYBACK_LOOP;
    }

    private void releasePlayerOnly() {
        prepared = false;
        seekPending = false;
        mainHandler.removeCallbacks(clipEndCheck);
        MediaPlayer current = player;
        player = null;
        if (current != null) {
            try { current.setSurface(null); } catch (Throwable ignored) {}
            try { current.release(); } catch (Throwable ignored) {}
        }
    }

    private boolean handleDragTouch(View ignored, MotionEvent event) {
        if (event == null) return false;
        ColorPickListener picker = colorPickListener;
        if (picker != null) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                videoSurface.requestColorSample(event.getX(), event.getY(), picker::onColorPicked);
            }
            return true;
        }
        if (!dragEnabled || dragListener == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dragging = true;
                dragMoved = false;
                dragDownRawX = event.getRawX();
                dragDownRawY = event.getRawY();
                dragListener.onDragStart(this, event.getRawX(), event.getRawY());
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false;
                if (!dragMoved) {
                    float dx = event.getRawX() - dragDownRawX;
                    float dy = event.getRawY() - dragDownRawY;
                    float slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    dragMoved = dx * dx + dy * dy > slop * slop;
                }
                if (dragMoved) dragListener.onDragMove(this, event.getRawX(), event.getRawY());
                return true;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false;
                boolean wasMoved = dragMoved;
                dragging = false;
                dragMoved = false;
                if (wasMoved) dragListener.onDragEnd(this);
                else if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            }
            default -> {
                return dragging;
            }
        }
    }
    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

}
