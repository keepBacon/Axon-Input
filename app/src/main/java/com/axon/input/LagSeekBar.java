package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.widget.SeekBar;

/**
 * SeekBar-compatible control with no thumb and a deliberately soft visual follow.
 *
 * Logical progress remains native SeekBar progress so existing listeners and persistence
 * behaviour stay unchanged. Only the rendered fill trails the finger by a few frames,
 * giving the control the requested weighted/physical response without delaying settings I/O.
 */
final class LagSeekBar extends SeekBar {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float displayedProgress;
    private float targetProgress;
    private boolean visualFramePosted;
    private boolean trackingTouch;

    private final Runnable visualFrame = new Runnable() {
        @Override public void run() {
            visualFramePosted = false;
            float delta = targetProgress - displayedProgress;
            if (Math.abs(delta) <= 0.10f) {
                displayedProgress = targetProgress;
                invalidate();
                return;
            }
            // A calm visual follow: only rendering lags; logical progress and callbacks remain immediate.
            displayedProgress += delta * 0.20f;
            invalidate();
            scheduleVisualFrame();
        }
    };

    LagSeekBar(Context context) {
        super(context);
        displayedProgress = getProgress();
        targetProgress = displayedProgress;
        stripFrameworkChrome();
        setPadding(0, dp(10f), 0, dp(10f));
    }


    /**
     * Native SeekBar styling is stateful: removing the thumb alone does not guarantee that
     * the platform pressed/focus halo disappears. Keep this view as the input/accessibility
     * primitive, but remove every framework drawable/elevation layer so only our track is
     * ever rendered while dragging.
     */
    private void stripFrameworkChrome() {
        setThumb(null);
        setThumbOffset(0);
        setTickMark(null);
        setSplitTrack(false);

        // View.draw() renders background/foreground separately from onDraw(). A themed
        // SeekBar may use either layer for the pressed hotspot, so clear both explicitly.
        setBackground((Drawable) null);
        setForeground((Drawable) null);

        // Material/platform styles can animate Z on pressed/focused states. Removing the
        // StateListAnimator prevents the residual circular spot-shadow on some OEM ROMs.
        setStateListAnimator(null);
        setElevation(0f);
        setTranslationZ(0f);
        setOutlineProvider(null);
        setClipToOutline(false);
        setDefaultFocusHighlightEnabled(false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Reassert after attachment in case the device theme resolved state drawables late.
        stripFrameworkChrome();
    }

    @Override public void setProgress(int progress) {
        super.setProgress(progress);
        if (!trackingTouch) {
            displayedProgress = getProgress();
            targetProgress = displayedProgress;
            invalidate();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) trackingTouch = true;
        boolean handled = super.onTouchEvent(event);
        if (handled) {
            targetProgress = getProgress();
            scheduleVisualFrame();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            trackingTouch = false;
            targetProgress = getProgress();
            scheduleVisualFrame();
        }
        return handled;
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float left = getPaddingLeft();
        float right = width - getPaddingRight();
        float cy = height * 0.5f;
        float half = dp(2f);
        float radius = half;

        trackPaint.setColor(UiPalette.divider(getContext()));
        fillPaint.setColor(UiPalette.accent(getContext()));
        canvas.drawRoundRect(left, cy - half, right, cy + half, radius, radius, trackPaint);

        int max = Math.max(1, getMax());
        float fraction = Math.max(0f, Math.min(1f, displayedProgress / max));
        float fillRight = left + (right - left) * fraction;
        if (fillRight > left + 0.5f) {
            canvas.drawRoundRect(left, cy - half, fillRight, cy + half, radius, radius, fillPaint);
        }
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(visualFrame);
        visualFramePosted = false;
        super.onDetachedFromWindow();
    }

    private void scheduleVisualFrame() {
        if (visualFramePosted) return;
        visualFramePosted = true;
        postOnAnimation(visualFrame);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
