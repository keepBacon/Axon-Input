package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 锁定状态统一使用的自绘小锁。图形只由纯黑线条组成，不依赖 emoji、字体或位图资源。
 * MotionSwitch / LagSeekBar 也复用同一几何，避免不同控件出现不同锁形状。
 */
final class SimpleLockView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shackle = new Path();
    private final RectF body = new RectF();

    SimpleLockView(Context context) {
        super(context);
        configurePaint(paint, getResources().getDisplayMetrics().density);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        drawGlyph(canvas, paint, shackle, body, w * 0.5f, h * 0.5f, Math.min(w, h) * 0.78f);
    }

    static void configurePaint(Paint paint, float density) {
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.35f * density, 1.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setAlpha(255);
    }

    /**
     * 绘制统一锁形。size 表示“最终可视高度”，不再是需要调用者反推的几何基准。
     * 这样 Slider / Switch 只需给出希望看到的锁高度，避免此前 0.48/0.70 隐式比例
     * 导致不同密度下锁过小、压扁或为了放大而越界裁剪。
     */
    static void drawGlyph(Canvas canvas, Paint paint, Path shackle, RectF body,
                          float cx, float cy, float size) {
        if (canvas == null || paint == null || shackle == null || body == null || size <= 0f) return;

        float halfStroke = Math.max(0.5f, paint.getStrokeWidth() * 0.5f);
        float top = cy - size * 0.5f + halfStroke;
        float bottom = cy + size * 0.5f - halfStroke;
        float bodyTop = cy + size * 0.02f;
        float bodyHalf = size * 0.37f;
        float radius = size * 0.075f;

        body.set(cx - bodyHalf, bodyTop, cx + bodyHalf, bottom);
        canvas.drawRoundRect(body, radius, radius, paint);

        float shackleHalf = size * 0.225f;
        float shoulderY = cy - size * 0.12f;
        shackle.reset();
        shackle.moveTo(cx - shackleHalf, bodyTop);
        shackle.lineTo(cx - shackleHalf, shoulderY);
        shackle.cubicTo(
                cx - shackleHalf, top,
                cx + shackleHalf, top,
                cx + shackleHalf, shoulderY);
        shackle.lineTo(cx + shackleHalf, bodyTop);
        canvas.drawPath(shackle, paint);
    }
}
