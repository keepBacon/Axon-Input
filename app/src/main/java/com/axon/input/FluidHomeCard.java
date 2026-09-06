package com.axon.input;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

/**
 * 主页应用信息卡的局部流体背景。
 *
 * 只在这张低频主页卡片内做一枚缓慢移动的柔和色团；Shader、Path、Matrix 全部缓存，
 * 每帧只更新 Shader local matrix 和局部 invalidate，避免把装饰动画扩散到页面其它区域。
 */
final class FluidHomeCard extends LinearLayout {
    private static final long LOOP_MS = 5200L;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF innerBounds = new RectF();
    private final Path clipPath = new Path();
    private final Matrix blobMatrix = new Matrix();

    private RadialGradient blobShader;
    private ValueAnimator animator;
    private float phase;
    private float blobRadius;
    private float blobX;
    private float blobY;

    FluidHomeCard(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(true);
        setClipToPadding(false);

        // 外层 card 承载流体 blob，内层 bg 向内缩进形成真正的流体边框。
        // 浅色主题按用户要求使用纯白卡片本体；深色主题保留原黑色层级。
        boolean dark = OverlayState.getUiTheme(context) == OverlayState.UI_THEME_BLACK;
        basePaint.setColor(dark ? Color.rgb(9, 10, 13) : Color.WHITE);
        innerPaint.setColor(dark ? Color.argb(226, 15, 16, 20) : Color.WHITE);
        innerOutlinePaint.setStyle(Paint.Style.STROKE);
        innerOutlinePaint.setStrokeWidth(dp(1f));
        innerOutlinePaint.setColor(dark
                ? Color.argb(28, 255, 255, 255)
                : Color.argb(18, 0, 0, 0));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        bounds.set(0f, 0f, w, h);
        float radius = dp(14f);
        clipPath.reset();
        clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        float inset = dp(5f);
        innerBounds.set(inset, inset, Math.max(inset, w - inset), Math.max(inset, h - inset));

        // 参考网页的红色 blob，但用缓存 RadialGradient 模拟 blur，避免实时 Bitmap Blur。
        blobRadius = Math.max(dp(72f), Math.max(w, h) * 0.58f);
        blobShader = new RadialGradient(
                0f, 0f, blobRadius,
                new int[]{0xF2FF2B47, 0xC8E11D3D, 0x806E1830, 0x286E1830, 0x006E1830},
                new float[]{0f, 0.24f, 0.52f, 0.76f, 1f},
                Shader.TileMode.CLAMP);
        blobPaint.setShader(blobShader);
        updateBlobMatrix();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawRect(bounds, basePaint);
        if (blobShader != null) {
            updateBlobPosition(phase);
            canvas.drawCircle(blobX, blobY, blobRadius, blobPaint);
        }
        // 5px inset 的主题内层让外圈直接露出运动 blob，
        // 浅色使用纯白本体、深色使用原深色本体，流体只负责外圈层次。
        canvas.drawRoundRect(innerBounds, dp(10f), dp(10f), innerPaint);
        canvas.drawRoundRect(innerBounds, dp(10f), dp(10f), innerOutlinePaint);
        canvas.restoreToCount(save);
        super.onDraw(canvas);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startMotion();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMotion();
        super.onDetachedFromWindow();
    }
    @Override
    protected void onVisibilityChanged(android.view.View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!isAttachedToWindow()) return;
        if (getVisibility() == VISIBLE && isShown()) startMotion();
        else stopMotion();
    }


    private void startMotion() {
        if (animator != null) return;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        animator = a;
        a.setDuration(LOOP_MS);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            updateBlobMatrix();
            postInvalidateOnAnimation();
        });
        a.start();
    }

    private void stopMotion() {
        if (animator == null) return;
        ValueAnimator old = animator;
        animator = null;
        old.cancel();
    }

    private void updateBlobMatrix() {
        if (blobShader == null || getWidth() <= 0 || getHeight() <= 0) return;
        updateBlobPosition(phase);
        blobMatrix.reset();
        blobMatrix.setTranslate(blobX, blobY);
        blobShader.setLocalMatrix(blobMatrix);
    }

    private void updateBlobPosition(float p) {
        // 色团沿圆角长方形四角巡航，直接构成卡片背景，而不是藏在另一层 Card 后面。
        float left = getWidth() * 0.08f;
        float right = getWidth() * 0.92f;
        float top = getHeight() * 0.10f;
        float bottom = getHeight() * 0.90f;
        float segment = (p - (float) Math.floor(p)) * 4f;
        int side = Math.min(3, (int) segment);
        float t = smooth(segment - side);
        switch (side) {
            case 0:
                blobX = lerp(left, right, t);
                blobY = top;
                break;
            case 1:
                blobX = right;
                blobY = lerp(top, bottom, t);
                break;
            case 2:
                blobX = lerp(right, left, t);
                blobY = bottom;
                break;
            default:
                blobX = left;
                blobY = lerp(bottom, top, t);
                break;
        }
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
