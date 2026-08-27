package com.axon.input;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.widget.Switch;

/**
 * Switch whose selected surface physically travels between off/on positions.
 *
 * The platform/OEM thumb animation is removed so every device uses the same timing. A new toggle
 * cancels the old destination and continues from visualProgress, making rapid reversals continuous.
 */
final class MotionSwitch extends Switch {
    private static final long FULL_TRAVEL_MS = 440L;
    private static final long MIN_TRAVEL_MS = 220L;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackBounds = new RectF();
    private ValueAnimator motionAnimator;
    private float visualProgress;
    private boolean initialized;

    MotionSwitch(Context context) {
        super(context);
        setShowText(false);
        setThumbDrawable(null);
        setTrackDrawable(null);
        setSwitchMinWidth(dp(42f));
        setSwitchPadding(dp(12f));
        setMinimumHeight(dp(48f));
        setBackground(null);
        setStateListAnimator(null);
        setSoundEffectsEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
        visualProgress = isChecked() ? 1f : 0f;
        initialized = true;
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        if (!initialized) return;
        float target = checked ? 1f : 0f;
        if (!changed) {
            if (motionAnimator == null || !motionAnimator.isRunning()) {
                visualProgress = target;
                invalidate();
            }
            return;
        }
        if (!isLaidOut() || !isAttachedToWindow()) {
            cancelMotion();
            visualProgress = target;
            invalidate();
            return;
        }
        animateTo(target);
    }

    private void animateTo(float target) {
        float from = clamp01(visualProgress);
        float distance = Math.abs(target - from);
        cancelMotion();
        if (distance <= 0.001f) {
            visualProgress = target;
            invalidate();
            return;
        }
        long duration = Math.max(MIN_TRAVEL_MS, Math.round(FULL_TRAVEL_MS * distance));
        ValueAnimator animator = ValueAnimator.ofFloat(from, target);
        motionAnimator = animator;
        animator.setDuration(duration);
        animator.setInterpolator(UiMotion.easeMove());
        animator.addUpdateListener(a -> {
            visualProgress = (float) a.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (motionAnimator == animation) motionAnimator = null;
                if (!cancelled && isChecked() == (target >= 0.5f)) {
                    visualProgress = target;
                    postInvalidateOnAnimation();
                }
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); // text only; native switch drawables are deliberately null.

        float trackWidth = dp(42f);
        float trackHeight = dp(24f);
        float right = getWidth() - getPaddingRight();
        float left = right - trackWidth;
        float cy = getHeight() * 0.5f;
        float top = cy - trackHeight * 0.5f;
        float bottom = cy + trackHeight * 0.5f;
        float trackRadius = trackHeight * 0.5f;
        trackBounds.set(left, top, right, bottom);

        int offTrack = UiPalette.switchTrackOff(getContext());
        int onTrack = UiPalette.switchTrackOn(getContext());
        int offThumb = UiPalette.switchThumbOff(getContext());
        int onThumb = UiPalette.switchThumbOn(getContext());
        if (!isEnabled()) {
            offTrack = UiChrome.withAlpha(offTrack, UiChrome.DISABLED_ALPHA);
            onTrack = UiChrome.withAlpha(onTrack, UiChrome.DISABLED_ALPHA);
            offThumb = UiChrome.withAlpha(offThumb, 0.72f);
            onThumb = UiChrome.withAlpha(onThumb, 0.72f);
        }

        float p = clamp01(visualProgress);
        trackPaint.setColor(KeyAppearance.blendColor(offTrack, onTrack, p));
        canvas.drawRoundRect(trackBounds, trackRadius, trackRadius, trackPaint);

        float thumbRadius = dp(9f);
        float inset = dp(3f);
        float startX = left + inset + thumbRadius;
        float endX = right - inset - thumbRadius;
        float thumbX = startX + (endX - startX) * p;
        thumbPaint.setColor(KeyAppearance.blendColor(offThumb, onThumb, p));
        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelMotion();
        super.onDetachedFromWindow();
    }

    private void cancelMotion() {
        if (motionAnimator != null) {
            ValueAnimator current = motionAnimator;
            motionAnimator = null;
            current.cancel();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
