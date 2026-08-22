package com.axon.input;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** 按键形状、按下颜色和扩散动画。 */
final class KeyAppearance {
    static final int STYLE_ROUNDED = 0;
    static final int STYLE_SQUARE = 1;
    static final int STYLE_CIRCLE = 2;
    static final long RIPPLE_MS = 260L;

    private KeyAppearance() {}

    static int clampStyle(int style) {
        if (style == STYLE_SQUARE || style == STYLE_CIRCLE) return style;
        return STYLE_ROUNDED;
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

    static void drawRipple(Canvas canvas, RectF area, int pressColor,
                           long startMs, long nowMs, Paint paint) {
        drawRipple(canvas, area, pressColor, startMs, nowMs, 100, paint);
    }

    static void drawRipple(Canvas canvas, RectF area, int pressColor,
                           long startMs, long nowMs, int strengthPercent, Paint paint) {
        if (startMs <= 0L || strengthPercent <= 0) return;
        float t = (nowMs - startMs) / (float) RIPPLE_MS;
        if (t < 0f || t >= 1f) return;
        float eased = 1f - (float) Math.pow(1f - t, 3f);
        float maxRadius = (float) Math.hypot(area.width() * 0.5f, area.height() * 0.5f);
        float rippleRadius = maxRadius * eased;
        int base = isLight(pressColor) ? Color.BLACK : Color.WHITE;
        float strength = Math.max(0f, Math.min(2f, strengthPercent / 100f));
        int alpha = Math.min(255, Math.round(72f * strength * (1f - t)));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)));

        canvas.drawCircle(area.centerX(), area.centerY(), rippleRadius, paint);
    }

    static float scaleRadius(float radius, int scalePercent) {
        return radius * Math.max(0, Math.min(200, scalePercent)) / 100f;
    }

    static int pressedTextColor(int pressColor) {
        return isLight(pressColor) ? Color.rgb(18, 18, 20) : Color.WHITE;
    }

    private static boolean isLight(int color) {
        double luma = Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114;
        return luma >= 170.0;
    }
}
