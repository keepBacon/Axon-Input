package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 主页捐赠卡使用的单色点赞图标。
 *
 * 视觉基于 Tabler Icons 的 thumb-up：24×24 网格、2px 线宽、round cap / round join。
 * 这里只在尺寸变化时把几何映射到 Android Canvas，不引入图标字体或额外运行时依赖。
 */
final class SolidLikeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    SolidLikeView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        path.reset();
        if (w <= 0 || h <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float side = Math.min(w, h);
        // 留出一个完整 stroke 的安全区，避免圆头线帽贴边被裁。
        float inset = Math.max(density, side * 0.06f);
        float drawSide = Math.max(1f, side - inset * 2f);
        float scale = drawSide / 24f;
        float ox = (w - drawSide) * 0.5f;
        float oy = (h - drawSide) * 0.5f;

        paint.setStrokeWidth(Math.max(density * 1.5f, scale * 2f));

        // Tabler thumb-up 的 24×24 轮廓：M7 11v8...；
        // Android Path 用二/三次曲线表达 SVG arc，保持原图标的简洁比例与圆润转折。
        moveTo(ox, oy, scale, 7f, 11f);
        lineTo(ox, oy, scale, 7f, 19f);
        quadTo(ox, oy, scale, 7f, 20f, 6f, 20f);
        lineTo(ox, oy, scale, 4f, 20f);
        quadTo(ox, oy, scale, 3f, 20f, 3f, 19f);
        lineTo(ox, oy, scale, 3f, 12f);
        quadTo(ox, oy, scale, 3f, 11f, 4f, 11f);
        lineTo(ox, oy, scale, 7f, 11f);

        // 手掌向上过渡到拇指根部。
        path.cubicTo(
                ox + 9.25f * scale, oy + 11f * scale,
                ox + 11f * scale, oy + 9.25f * scale,
                ox + 11f * scale, oy + 7f * scale);
        lineTo(ox, oy, scale, 11f, 6f);

        // 拇指顶部：对应 SVG a2 2 0 0 1 4 0。
        path.cubicTo(
                ox + 11f * scale, oy + 3.35f * scale,
                ox + 15f * scale, oy + 3.35f * scale,
                ox + 15f * scale, oy + 6f * scale);
        lineTo(ox, oy, scale, 15f, 11f);
        lineTo(ox, oy, scale, 18f, 11f);
        quadTo(ox, oy, scale, 20f, 11f, 20f, 13f);
        lineTo(ox, oy, scale, 19f, 18f);

        // 指尖/掌缘保留 Tabler 的窄而克制轮廓，不做实心大块。
        path.cubicTo(
                ox + 18.78f * scale, oy + 19.35f * scale,
                ox + 18.15f * scale, oy + 20f * scale,
                ox + 17f * scale, oy + 20f * scale);
        lineTo(ox, oy, scale, 10f, 20f);
        path.cubicTo(
                ox + 8.2f * scale, oy + 20f * scale,
                ox + 7f * scale, oy + 18.8f * scale,
                ox + 7f * scale, oy + 17f * scale);
    }

    private void moveTo(float ox, float oy, float scale, float x, float y) {
        path.moveTo(ox + x * scale, oy + y * scale);
    }

    private void lineTo(float ox, float oy, float scale, float x, float y) {
        path.lineTo(ox + x * scale, oy + y * scale);
    }

    private void quadTo(float ox, float oy, float scale,
                        float cx, float cy, float x, float y) {
        path.quadTo(ox + cx * scale, oy + cy * scale,
                ox + x * scale, oy + y * scale);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(UiPalette.textPrimary(getContext()));
        canvas.drawPath(path, paint);
    }
}
