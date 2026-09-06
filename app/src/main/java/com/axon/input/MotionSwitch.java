package com.axon.input;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.widget.Switch;

/**
 * 全项目统一的开关实现。
 *
 * 默认用于详情/调试项时绘制“框选”复选样式；作为功能主开关时由容器切换回胶囊轨道。
 * 两种形态共用同一份可中断状态进度，快速连续点击不会从旧起点重播。
 */
final class MotionSwitch extends Switch {
    private static final long FULL_TRAVEL_MS = 180L;
    private static final long MIN_TRAVEL_MS = 90L;
    private static final float TRACK_WIDTH_DP = 52f;
    private static final float TRACK_HEIGHT_DP = 30f;
    private static final float BOX_SIZE_DP = 24f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackBounds = new RectF();
    private final RectF boxBounds = new RectF();
    private final RectF lockBody = new RectF();
    private final Path lockPath = new Path();

    private ValueAnimator motionAnimator;
    private float visualProgress;
    private boolean initialized;
    private boolean debugBoxStyle = true;

    MotionSwitch(Context context) {
        super(context);
        setShowText(false);
        setThumbDrawable(null);
        setTrackDrawable(null);
        applyGeometryMode();
        setMinimumHeight(dp(44f));
        setPadding(0, 0, 0, 0);
        setBackground(null);
        setStateListAnimator(null);
        setSoundEffectsEnabled(true);
        setElevation(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
        SimpleLockView.configurePaint(lockPaint, getResources().getDisplayMetrics().density);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        visualProgress = isChecked() ? 1f : 0f;
        initialized = true;
    }

    /** 功能卡片右侧的主开关继续保持胶囊轨道，不与详情框选语义混用。 */
    void setPrimaryToggleStyle() {
        if (!debugBoxStyle) return;
        debugBoxStyle = false;
        applyGeometryMode();
        requestLayout();
        invalidate();
    }

    private void applyGeometryMode() {
        if (debugBoxStyle) {
            setSwitchMinWidth(dp(34f));
            setMinimumWidth(dp(34f));
            setSwitchPadding(dp(10f));
        } else {
            // 52dp 主轨道保留 2dp 绘制安全区；主开关无描边，安全区只防 Thumb/锁贴边。
            setSwitchMinWidth(dp(56f));
            setMinimumWidth(dp(56f));
            setSwitchPadding(dp(12f));
        }
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
        animator.setInterpolator(UiMotion.easeOut());
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
        // 原生 thumb/track 已清空，只借用 Switch 自身的文字布局和无障碍语义。
        super.onDraw(canvas);
        if (debugBoxStyle) drawDebugBox(canvas);
        else drawPrimaryToggle(canvas);
    }

    private void drawDebugBox(Canvas canvas) {
        float size = dp(BOX_SIZE_DP);
        float cy = getHeight() * 0.5f;
        float right = getWidth() - dp(2f);
        float left = right - size;
        float top = cy - size * 0.5f;
        float bottom = cy + size * 0.5f;
        float radius = dp(5f);
        boxBounds.set(left, top, right, bottom);

        float p = clamp01(visualProgress);
        int off = UiPalette.textTertiary(getContext());
        int on = UiPalette.textPrimary(getContext());
        int border = isEnabled()
                ? KeyAppearance.blendColor(off, on, p)
                : 0xFF9A9AA0;

        if (!isEnabled()) {
            trackPaint.setStyle(Paint.Style.FILL);
            trackPaint.setColor(0xFFB8B8BE);
            canvas.drawRoundRect(boxBounds, radius, radius, trackPaint);
        }

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(2f));
        borderPaint.setStrokeCap(Paint.Cap.ROUND);
        borderPaint.setStrokeJoin(Paint.Join.ROUND);
        borderPaint.setColor(border);
        canvas.drawRoundRect(boxBounds, radius, radius, borderPaint);

        float cx = (left + right) * 0.5f;
        if (!isEnabled()) {
            SimpleLockView.drawGlyph(canvas, lockPaint, lockPath, lockBody,
                    cx, cy, dp(15f));
            return;
        }

        if (p <= 0.001f) return;
        checkPaint.setColor(on);
        checkPaint.setStrokeWidth(dp(2.7f));

        // 勾的可视包围盒围绕方框中心对称，避免参考 SVG 的长上挑造成整体偏右上。
        // 仍保留“短下落 + 长上挑”的两段绘制节奏，只重新校正几何中心。
        float x1 = cx - size * 0.25f;
        float y1 = cy - size * 0.01f;
        float x2 = cx - size * 0.07f;
        float y2 = cy + size * 0.18f;
        float x3 = cx + size * 0.27f;
        float y3 = cy - size * 0.20f;
        float len1 = distance(x1, y1, x2, y2);
        float len2 = distance(x2, y2, x3, y3);
        float travel = (len1 + len2) * p;
        if (travel <= len1) {
            float t = len1 <= 0f ? 1f : travel / len1;
            canvas.drawLine(x1, y1, lerp(x1, x2, t), lerp(y1, y2, t), checkPaint);
        } else {
            canvas.drawLine(x1, y1, x2, y2, checkPaint);
            float t = len2 <= 0f ? 1f : Math.min(1f, (travel - len1) / len2);
            canvas.drawLine(x2, y2, lerp(x2, x3, t), lerp(y2, y3, t), checkPaint);
        }
    }

    private void drawPrimaryToggle(Canvas canvas) {
        float trackWidth = dp(TRACK_WIDTH_DP);
        float trackHeight = dp(TRACK_HEIGHT_DP);
        float cy = getHeight() * 0.5f;
        float right = getWidth() - dp(2f);
        float left = right - trackWidth;
        float cx = (left + right) * 0.5f;
        float top = cy - trackHeight * 0.5f;
        float bottom = cy + trackHeight * 0.5f;
        float trackRadius = trackHeight * 0.5f;
        trackBounds.set(left, top, right, bottom);

        int offTrack = UiPalette.switchTrackOff(getContext());
        int onTrack = UiPalette.switchTrackOn(getContext());
        int offThumb = UiPalette.switchThumbOff(getContext());
        int onThumb = UiPalette.switchThumbOn(getContext());
        if (!isEnabled()) {
            offTrack = 0xFFB8B8BE;
            onTrack = 0xFFB8B8BE;
            offThumb = 0xFFD8D8DC;
            onThumb = 0xFFD8D8DC;
        }

        float p = clamp01(visualProgress);
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(KeyAppearance.blendColor(offTrack, onTrack, p));
        canvas.drawRoundRect(trackBounds, trackRadius, trackRadius, trackPaint);

        // 功能主开关不再绘制外边框。状态只由轨道明度/色相与 Thumb 表达，
        // 避免浅色主题里出现一圈突兀灰线；详情中的框选开关仍保留边框语义。
        float thumbRadius = dp(10.5f);
        float inset = dp(4.5f);
        float startX = left + inset + thumbRadius;
        float endX = right - inset - thumbRadius;
        float thumbX = startX + (endX - startX) * p;
        thumbPaint.setColor(KeyAppearance.blendColor(offThumb, onThumb, p));
        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint);

        if (!isEnabled()) {
            SimpleLockView.drawGlyph(canvas, lockPaint, lockPath, lockBody,
                    cx, cy, dp(17f));
        }
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

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
