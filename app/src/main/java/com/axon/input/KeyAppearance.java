package com.axon.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** 按键形状、按下颜色和扩散动画。 */
final class KeyAppearance {
    static final int STYLE_ROUNDED = 0;
    static final int STYLE_SQUARE = 1;
    static final int STYLE_CIRCLE = 2;
    static final long RIPPLE_MS = 260L;
    static final int DEFAULT_CORNER_STRENGTH = 40;

    // Shared keyboard cadence estimator used by every native key-display surface.  This keeps
    // centre-fill speed consistent when the same physical key event is mirrored by multiple views.
    private static long lastCentreFillPressMs;
    private static float centreFillCadenceEmaMs = 620f;

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
     * Legacy compatibility entry point.  The old white/black translucent OEM-like ripple was
     * removed in v1.6.  Remaining callers receive only an opaque press-colour centre disc.
     */
    static void drawRipple(Canvas canvas, RectF area, int pressColor,
                           long startMs, long nowMs, Paint paint) {
        if (startMs <= 0L) return;
        float t = (nowMs - startMs) / (float) RIPPLE_MS;
        if (t < 0f || t >= 1f) return;
        float maxRadius = (float) Math.hypot(area.width() * 0.5f, area.height() * 0.5f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressColor);
        canvas.drawCircle(area.centerX(), area.centerY(), maxRadius * t, paint);
    }


    /**
     * Draws one opaque press-colour circle from the exact centre of the key and clips it to the
     * key's own geometry. No tint, alpha fade, halo or secondary ripple is applied.
     */
    static void drawCentreFill(Canvas canvas, RectF area, int style, float radius,
                               int pressColor, float progress, Paint paint, Path clipPath) {
        if (area == null || progress <= 0f) return;
        float p = Math.max(0f, Math.min(1f, progress));
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

        float maxRadius = resolvedStyle == STYLE_CIRCLE
                ? Math.min(area.width(), area.height()) * 0.5f
                : (float) Math.hypot(area.width() * 0.5f, area.height() * 0.5f);

        int save = canvas.save();
        canvas.clipPath(clipPath);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressColor);
        canvas.drawCircle(area.centerX(), area.centerY(), maxRadius * p, paint);
        canvas.restoreToCount(save);
    }

    /**
     * Returns an adaptive centre-fill duration from recent key cadence.  Slow deliberate input
     * produces a visibly slower fill; rapid typing shortens it without collapsing into an OEM-like
     * instant ripple.
     */
    static synchronized long nextCentreFillDuration(long nowMs) {
        if (lastCentreFillPressMs > 0L) {
            long interval = nowMs - lastCentreFillPressMs;
            if (interval > 1800L) {
                // A new deliberate sequence starts calm instead of inheriting stale rapid cadence.
                centreFillCadenceEmaMs = 620f;
            } else if (interval >= 55L) {
                // React to typing tempo quickly, but smooth enough that alternating keys do not jitter.
                centreFillCadenceEmaMs = centreFillCadenceEmaMs * 0.55f + interval * 0.45f;
            }
        }
        lastCentreFillPressMs = nowMs;
        return Math.round(Math.max(260f, Math.min(820f, 210f + centreFillCadenceEmaMs * 0.68f)));
    }

    /**
     * Soft centre-fill curve: gentle acceleration, a lively middle, and a damped arrival.
     * It has zero hard corners in velocity, so the solid disc reads as a fill rather than an OEM ripple.
     */
    static float centreFillCurve(float input) {
        float t = Math.max(0f, Math.min(1f, input));
        float smooth = t * t * (3f - 2f * t);
        float softOut = 1f - (float) Math.pow(1f - t, 2.15f);
        return Math.max(0f, Math.min(1f, smooth * 0.72f + softOut * 0.28f));
    }

    static float centreFillProgress(long startMs, long durationMs, long nowMs) {
        if (startMs <= 0L) return 0f;
        float linear = (nowMs - startMs) / (float) Math.max(1L, durationMs);
        return centreFillCurve(linear);
    }

    static float centreTextMix(float fillProgress) {
        return centreFillCurve(Math.max(0f, Math.min(1f, fillProgress / 0.24f)));
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
