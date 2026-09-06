package com.axon.input;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 已通过访问验证后的轻量品牌进入动画。
 *
 * 视觉节奏参考用户提供的 Uiverse/dexter-st 字母 loader：字母依次出现、轻微上移放大，
 * 随后逐字淡出并增加少量模糊。原 loader 的彩色径向渐变、repeating mask 和背景条纹
 * 明确不实现，避免把纯品牌反馈变成持续 GPU 装饰。
 */
final class EntryBrandRevealView extends View {
    interface ProgressListener {
        void onProgress(float progress);
    }

    private static final long LETTER_DELAY_MS = 92L;
    private static final long LETTER_ACTIVE_MS = 860L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final BlurMaskFilter[] blurFilters = new BlurMaskFilter[6];
    private final String text;
    private final float letterGap;
    private final float wordGap;
    private final long totalDurationMs;

    private ValueAnimator animator;
    private Runnable completion;
    private ProgressListener progressListener;
    private float elapsedMs;
    private boolean completed;

    EntryBrandRevealView(Context context) {
        super(context);
        text = context.getString(R.string.app_name);
        paint.setColor(UiPalette.textPrimary(context));
        paint.setTextSize(sp(29f));
        Typeface typeface = AppTypeface.heavy(context);
        if (typeface != null) paint.setTypeface(typeface);
        paint.setTextAlign(Paint.Align.LEFT);

        letterGap = dp(0.8f);
        wordGap = dp(5.5f);
        for (int i = 1; i < blurFilters.length; i++) {
            blurFilters[i] = new BlurMaskFilter(dp(i * 0.72f), BlurMaskFilter.Blur.NORMAL);
        }
        int animatedLetters = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) animatedLetters++;
        }
        totalDurationMs = LETTER_ACTIVE_MS + Math.max(0, animatedLetters - 1) * LETTER_DELAY_MS;

        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // BlurMaskFilter 对小面积文字使用软件层更稳定；动画结束后整个 View 会立即移除。
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    void start(Runnable onComplete) {
        start(null, onComplete);
    }

    void start(ProgressListener onProgress, Runnable onComplete) {
        cancel(false);
        completion = onComplete;
        progressListener = onProgress;
        completed = false;
        elapsedMs = 0f;
        if (progressListener != null) progressListener.onProgress(0f);

        ValueAnimator valueAnimator = ValueAnimator.ofFloat(0f, (float) totalDurationMs);
        animator = valueAnimator;
        valueAnimator.setDuration(totalDurationMs);
        valueAnimator.setInterpolator(new LinearInterpolator());
        valueAnimator.addUpdateListener(animation -> {
            elapsedMs = (float) animation.getAnimatedValue();
            ProgressListener listener = progressListener;
            if (listener != null) {
                float progress = totalDurationMs <= 0L ? 1f : elapsedMs / (float) totalDurationMs;
                listener.onProgress(clamp01(progress));
            }
            postInvalidateOnAnimation();
        });
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(Animator animation) {
                if (animator == animation) animator = null;
                if (!cancelled) finishOnce();
            }
        });
        valueAnimator.start();
    }

    void cancel(boolean runCompletion) {
        Runnable pending = completion;
        completion = null;
        progressListener = null;
        ValueAnimator old = animator;
        animator = null;
        if (old != null) old.cancel();
        if (runCompletion && pending != null && !completed) {
            completed = true;
            pending.run();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancel(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0 || text.isEmpty()) return;

        float totalWidth = measureContentWidth();
        float x = (getWidth() - totalWidth) * 0.5f;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = getHeight() * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        int animatedIndex = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                x += wordGap;
                continue;
            }

            String glyph = String.valueOf(ch);
            float glyphWidth = paint.measureText(glyph);
            float local = (elapsedMs - animatedIndex * LETTER_DELAY_MS) / (float) LETTER_ACTIVE_MS;
            if (local > 0f && local < 1f) {
                drawGlyph(canvas, glyph, x, baseline, glyphWidth, local);
            }
            x += glyphWidth + letterGap;
            animatedIndex++;
        }

        paint.setAlpha(255);
        paint.setMaskFilter(null);
        paint.clearShadowLayer();
    }

    private void drawGlyph(Canvas canvas, String glyph, float x, float baseline,
            float glyphWidth, float local) {
        float alpha;
        float scale;
        float translateY;
        int blurBucket = 0;

        if (local < 0.16f) {
            float t = easeOut(local / 0.16f);
            alpha = t;
            scale = lerp(0.97f, 1.085f, t);
            translateY = lerp(dp(1.5f), -dp(2f), t);
        } else if (local < 0.40f) {
            float t = (local - 0.16f) / 0.24f;
            alpha = lerp(1f, 0.58f, t);
            scale = lerp(1.085f, 1f, easeOut(t));
            translateY = lerp(-dp(2f), 0f, easeOut(t));
        } else {
            float t = (local - 0.40f) / 0.60f;
            alpha = lerp(0.58f, 0f, t);
            scale = 1f;
            translateY = 0f;
            blurBucket = Math.min(5, Math.max(0, Math.round(t * 5f)));
        }

        paint.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * 255f))));
        paint.setMaskFilter(blurBucket == 0 ? null : blurFilters[blurBucket]);
        if (local < 0.30f) {
            paint.setShadowLayer(dp(1.6f), 0f, 0f, UiPalette.textTertiary(getContext()));
        } else {
            paint.clearShadowLayer();
        }

        float pivotX = x + glyphWidth * 0.5f;
        int save = canvas.save();
        canvas.translate(pivotX, baseline + translateY);
        canvas.scale(scale, scale);
        canvas.drawText(glyph, -glyphWidth * 0.5f, 0f, paint);
        canvas.restoreToCount(save);
    }

    private float measureContentWidth() {
        float width = 0f;
        int visible = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                width += wordGap;
            } else {
                if (visible > 0) width += letterGap;
                width += paint.measureText(String.valueOf(ch));
                visible++;
            }
        }
        return width;
    }

    private void finishOnce() {
        if (completed) return;
        completed = true;
        ProgressListener listener = progressListener;
        progressListener = null;
        if (listener != null) listener.onProgress(1f);
        Runnable done = completion;
        completion = null;
        if (done != null) done.run();
    }

    private static float easeOut(float t) {
        t = clamp01(t);
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * clamp01(t);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
