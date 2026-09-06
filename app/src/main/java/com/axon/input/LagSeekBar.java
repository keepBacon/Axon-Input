package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.widget.SeekBar;

/**
 * 统一 Slider：逻辑进度即时更新，视觉只做短跟随。
 *
 * 右侧固定预留数值区，因此所有轨道只从右边缩短，左边界与上方名称保持完全对齐；
 * 未填充轨道仍透明，不重新引入旧灰底。数值文本仅在 progress 变化时更新，不进入每帧格式化路径。
 */
final class LagSeekBar extends SeekBar {
    private static final float VALUE_ZONE_DP = 68f;
    private static final float VALUE_RIGHT_INSET_DP = 8f;
    private static final float MIN_HEIGHT_DP = 46f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path lockPath = new Path();
    private final RectF lockBody = new RectF();

    private float displayedProgress;
    private float targetProgress;
    private boolean visualFramePosted;
    private boolean trackingTouch;

    private int displayOffset;
    private int displayMultiplier = 1;
    private String displaySuffix = "";
    private boolean manualDisplayValue;
    private String displayValueText = "0";

    private final Runnable visualFrame = new Runnable() {
        @Override public void run() {
            visualFramePosted = false;
            float delta = targetProgress - displayedProgress;
            if (Math.abs(delta) <= 0.10f) {
                displayedProgress = targetProgress;
                invalidate();
                return;
            }
            displayedProgress += delta * 0.28f;
            invalidate();
            scheduleVisualFrame();
        }
    };

    LagSeekBar(Context context) {
        super(context);
        displayedProgress = getProgress();
        targetProgress = displayedProgress;
        stripFrameworkChrome();
        // setPadding 会自动把 VALUE_ZONE 追加到右侧；任何调用者都无法意外把数值区清掉。
        setPadding(0, 0, 0, 0);
        setMinimumHeight(dp(MIN_HEIGHT_DP));
        SimpleLockView.configurePaint(lockPaint, getResources().getDisplayMetrics().density);
        // Slider 中央锁需要在长轨道上保持清晰的视觉重量，单独略加粗，不影响 Switch/独立锁。
        lockPaint.setStrokeWidth(dp(1.75f));
        valuePaint.setAntiAlias(true);
        valuePaint.setTextAlign(Paint.Align.RIGHT);
        valuePaint.setTypeface(AppTypeface.heavy(context));
        valuePaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        refreshDisplayValue();
        updateTextMetricSafety();
    }

    void setValueTypeface(Typeface typeface) {
        valuePaint.setTypeface(typeface == null ? Typeface.DEFAULT_BOLD : typeface);
        updateTextMetricSafety();
        requestLayout();
        invalidate();
    }


    /**
     * 自绘数值不能假设系统字体 metrics。Heavy / 云端字体的 top-bottom 可能明显
     * 大于 ascent-descent，因此由真实 FontMetricsInt 决定 Slider 的安全最小高度。
     */
    private void updateTextMetricSafety() {
        Paint.FontMetricsInt fm = valuePaint.getFontMetricsInt();
        int textHeight = Math.max(1, fm.bottom - fm.top);
        int metricSafeHeight = textHeight + dp(14f);
        setMinimumHeight(Math.max(dp(MIN_HEIGHT_DP), metricSafeHeight));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Paint.FontMetricsInt fm = valuePaint.getFontMetricsInt();
        int textHeight = Math.max(1, fm.bottom - fm.top);
        int safeHeight = Math.max(getMinimumHeight(), textHeight + dp(14f));
        // 父级已经统一使用 WRAP_CONTENT；这里再做一层自保护，避免以后某个公共样式
        // 把高度重新压小后导致 Canvas 文本/锁/Thumb 被 View 自身边界裁掉。
        if (getMeasuredHeight() < safeHeight) {
            setMeasuredDimension(getMeasuredWidth(), safeHeight);
        }
    }

    /** 常规整数/百分比/倍率等线性值：actual = offset + progress * multiplier。 */
    void setValueDisplaySpec(int offset, int multiplier, String suffix) {
        displayOffset = offset;
        displayMultiplier = Math.max(1, multiplier);
        displaySuffix = suffix == null ? "" : suffix;
        manualDisplayValue = false;
        refreshDisplayValue();
        invalidate();
    }

    /** 非线性 Slider（例如灵敏度）由拥有者在进度回调中直接提供真实值。 */
    void setDisplayValue(int value, String suffix) {
        manualDisplayValue = true;
        displayValueText = Integer.toString(value) + (suffix == null ? "" : suffix);
        invalidate();
    }

    /** 非线性或小数值由拥有者直接提供最终显示文本。 */
    void setDisplayText(String text) {
        manualDisplayValue = true;
        displayValueText = text == null ? "" : text;
        invalidate();
    }

    private void refreshDisplayValue() {
        if (manualDisplayValue) return;
        int value = displayOffset + getProgress() * displayMultiplier;
        displayValueText = Integer.toString(value) + displaySuffix;
    }

    private void stripFrameworkChrome() {
        setThumb(null);
        setThumbOffset(0);
        setTickMark(null);
        setSplitTrack(false);
        setBackground((Drawable) null);
        setForeground((Drawable) null);
        setStateListAnimator(null);
        setElevation(0f);
        setTranslationZ(0f);
        setOutlineProvider(null);
        setClipToOutline(false);
        setDefaultFocusHighlightEnabled(false);
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        // 只缩右边；left 永远完全尊重调用者传入值。
        super.setPadding(left, top, right + dp(VALUE_ZONE_DP), bottom);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        stripFrameworkChrome();
    }

    @Override public void setProgress(int progress) {
        super.setProgress(progress);
        refreshDisplayValue();
        if (!trackingTouch) {
            displayedProgress = getProgress();
            targetProgress = displayedProgress;
            invalidate();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float railRight = Math.max(getPaddingLeft(), getWidth() - getPaddingRight());
            // 右侧数字是只读状态，不把点击数字误解释成“直接拉到最大值”。
            if (event.getX() > railRight) return false;
            trackingTouch = true;
        }
        boolean handled = super.onTouchEvent(event);
        if (handled) {
            targetProgress = getProgress();
            refreshDisplayValue();
            scheduleVisualFrame();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            trackingTouch = false;
            targetProgress = getProgress();
            refreshDisplayValue();
            scheduleVisualFrame();
        }
        return handled;
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float cy = height * 0.5f;
        float half = dp(1.7f);
        float railRadius = half;
        float thumbRadius = dp(trackingTouch ? 6.5f : 6f);
        float railLeft = getPaddingLeft();
        float railRight = Math.max(railLeft + dp(24f), width - getPaddingRight());
        float usable = Math.max(1f, railRight - railLeft);
        float maxThumbX = Math.max(railLeft + thumbRadius, railRight - thumbRadius);

        int min = getMin();
        int max = Math.max(min + 1, getMax());
        float fraction = Math.max(0f, Math.min(1f,
                (displayedProgress - min) / (float) (max - min)));
        float thumbX = railLeft + thumbRadius
                + (maxThumbX - (railLeft + thumbRadius)) * fraction;

        fillPaint.setColor(isEnabled()
                ? UiPalette.controlAccent(getContext())
                : UiPalette.textTertiary(getContext()));

        // 未填充区域透明。轨道左端不动，只有右端为数值区让位。
        float fillEnd = fraction >= 0.999f ? railRight : Math.max(railLeft, thumbX);
        if (fillEnd - railLeft > 0.5f) {
            canvas.drawRoundRect(railLeft, cy - half, fillEnd, cy + half,
                    railRadius, railRadius, fillPaint);
        }

        thumbPaint.setColor(isEnabled() ? 0xFFFFFFFF : UiPalette.switchThumbOff(getContext()));
        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint);

        if (!isEnabled()) {
            float lockX = railLeft + usable * 0.5f;
            float lockCy = height * 0.5f;

            // size 现在就是最终可视高度。上下按真实 stroke 预留安全区，
            // 不通过 Y 位移或“放大几何基准”补救，因此不会再出现锁太小/越界裁剪。
            float verticalSafe = Math.max(dp(4f), lockPaint.getStrokeWidth() * 2f + dp(2f));
            float availableHeight = Math.max(dp(12f), height - verticalSafe * 2f);
            float lockHeight = Math.min(dp(23f), availableHeight);
            SimpleLockView.drawGlyph(canvas, lockPaint, lockPath, lockBody,
                    lockX, lockCy, lockHeight);
        }

        valuePaint.setColor(isEnabled()
                ? UiPalette.textSecondary(getContext())
                : UiPalette.textTertiary(getContext()));
        Paint.FontMetricsInt fm = valuePaint.getFontMetricsInt();
        // 使用 top/bottom 而不是 ascent/descent：前者覆盖字体完整绘制边界，
        // 自定义 Heavy 字体也不会因为顶部/底部额外字形空间被裁切。
        float valueY = (height - (fm.bottom - fm.top)) * 0.5f - fm.top;
        canvas.drawText(displayValueText == null ? "" : displayValueText,
                width - dp(VALUE_RIGHT_INSET_DP), valueY, valuePaint);
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
