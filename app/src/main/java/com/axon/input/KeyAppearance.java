package com.axon.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.animation.PathInterpolator;

/** 按键形状、按下颜色和扩散动画。 */
final class KeyAppearance {
    static final int STYLE_ROUNDED = 0;
    static final int STYLE_SQUARE = 1;
    static final int STYLE_CIRCLE = 2;
    // v1.7: user requested 0.5x speed relative to the first Matrix migration.
    // Matrix source is 190/72 ms; Axon intentionally doubles both to 380/144 ms.
    static final long CARD_FEATURE_TOGGLE_MS = 380L;
    static final long RIPPLE_MIN_MS = 144L;
    static final int DEFAULT_CORNER_STRENGTH = 40;

    // Matrix Card UI feature-toggle motion. Keep this curve and timing aligned with
    // ClickUiView.animateCardFeatureState(): 380 ms full travel, 144 ms minimum,
    // cubic-bezier(0.18, 0.94, 0.28, 1).
    private static final PathInterpolator CARD_FEATURE_TOGGLE_EASE =
            new PathInterpolator(0.18f, 0.94f, 0.28f, 1f);

    private KeyAppearance() {}

    static int clampStyle(int style) {
        if (style == STYLE_SQUARE || style == STYLE_CIRCLE) return style;
        return STYLE_ROUNDED;
    }

    static int clampCornerStrength(int strength) {
        return Math.max(0, Math.min(100, strength));
    }

    static float roundedRadius(RectF area, int strength) {
        if (area == null) return 0f;
        float maxRadius = Math.min(area.width(), area.height()) * 0.5f;
        return maxRadius * (clampCornerStrength(strength) / 100f);
    }

    static void drawShape(Canvas canvas, RectF area, int style, float radius, Paint paint) {
        style = clampStyle(style);
        if (style == STYLE_SQUARE) {
            canvas.drawRect(area, paint);
        } else if (style == STYLE_CIRCLE) {
            canvas.drawCircle(area.centerX(), area.centerY(), Math.min(area.width(), area.height()) * 0.5f, paint);
        } else {
            canvas.drawRoundRect(area, radius, radius, paint);
        }
    }

    /**
     * Matrix Card UI feature-state fill. This is NOT a radial ripple. The whole key-shaped state
     * surface grows from the centre toward the final bounds, matching ClickUiView's centred
     * scratchRectD expansion.
     */
    static void drawCentreFill(Canvas canvas, RectF area, int style, float radius,
                               int pressColor, float progress, Paint paint, Path clipPath) {
        if (area == null || progress <= 0f) return;
        float spread = cardFeatureSpread(progress);
        if (spread <= 0.0001f) return;
        int resolvedStyle = clampStyle(style);

        clipPath.reset();
        if (resolvedStyle == STYLE_SQUARE) {
            clipPath.addRect(area, Path.Direction.CW);
        } else if (resolvedStyle == STYLE_CIRCLE) {
            clipPath.addCircle(area.centerX(), area.centerY(),
                    Math.min(area.width(), area.height()) * 0.5f, Path.Direction.CW);
        } else {
            clipPath.addRoundRect(area, radius, radius, Path.Direction.CW);
        }

        float cx = area.centerX();
        float cy = area.centerY();
        float left = cx - (cx - area.left) * spread;
        float top = cy - (cy - area.top) * spread;
        float right = cx + (area.right - cx) * spread;
        float bottom = cy + (area.bottom - cy) * spread;

        int save = canvas.save();
        canvas.clipPath(clipPath);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressColor);
        if (resolvedStyle == STYLE_SQUARE) {
            canvas.drawRect(left, top, right, bottom, paint);
        } else if (resolvedStyle == STYLE_CIRCLE) {
            float revealRadius = Math.min(right - left, bottom - top) * 0.5f;
            canvas.drawCircle(cx, cy, revealRadius, paint);
        } else {
            float revealRadius = Math.min(radius,
                    Math.max(0f, Math.min(right - left, bottom - top) * 0.5f));
            canvas.drawRoundRect(left, top, right, bottom,
                    revealRadius, revealRadius, paint);
        }
        canvas.restoreToCount(save);
    }

    /** Matrix Card UI feature-toggle easing. Input and output are clamped to [0, 1]. */
    static float cardFeatureToggleEase(float input) {
        float t = Math.max(0f, Math.min(1f, input));
        return CARD_FEATURE_TOGGLE_EASE.getInterpolation(t);
    }

    /**
     * Matches Matrix Card UI interruption semantics: duration scales with the remaining visual
     * distance, but never drops below 144 ms for a real state change in the 0.5x-speed Axon variant.
     */
    static long cardFeatureToggleDuration(float from, float to) {
        float distance = Math.abs(Math.max(0f, Math.min(1f, to))
                - Math.max(0f, Math.min(1f, from)));
        if (distance <= 0.001f) return 0L;
        return Math.max(RIPPLE_MIN_MS, Math.round(CARD_FEATURE_TOGGLE_MS * distance));
    }

    /** Matrix Card UI uses smoothstep on the animated state to produce the actual spread. */
    static float cardFeatureSpread(float stateReveal) {
        float p = Math.max(0f, Math.min(1f, stateReveal));
        return p * p * (3f - 2f * p);
    }

    /** Exact card feature-state micro-bounce from Matrix ClickUiView. */
    static float cardFeatureBounce(float stateReveal) {
        float p = Math.max(0f, Math.min(1f, stateReveal));
        return 1f + 0.048f * (float) Math.sin(Math.PI * p);
    }

    static float centreTextMix(float fillProgress) {
        return cardFeatureSpread(fillProgress);
    }

    static int blendColor(int from, int to, float progress) {
        float t = Math.max(0f, Math.min(1f, progress));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    static int pressedTextColor(int pressColor) {
        return isLight(pressColor) ? Color.rgb(18, 18, 20) : Color.WHITE;
    }

    private static boolean isLight(int color) {
        double luma = Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114;
        return luma >= 170.0;
    }
}
