package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 鼠标相对运动显示。
 * 圆点只读取方向和相对移动量，不读取屏幕绝对坐标。输入先改变短期目标，阻尼状态再追踪目标；
 * 停止输入后目标回到中心。动画稳定后不再请求下一帧。
 */
public final class MouseTrajectoryView extends FrameLayout {
    public static final int DISPLAY_TRAJECTORY = 4;

    public interface DragListener {
        void onDragStart(MouseTrajectoryView source, float rawX, float rawY);
        void onDragMove(MouseTrajectoryView source, float rawX, float rawY);
        void onDragEnd(MouseTrajectoryView source);
    }

    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF();

    private DragListener dragListener;
    private boolean dragEnabled;
    private boolean dragging;
    private GlobalHtmlWebView htmlView;
    private boolean globalHtmlEnabled;
    private int displaySizePercent = 100;
    private int dotSizePercent = 100;
    private boolean leftColorEnabled;
    private boolean rightColorEnabled;
    private int leftPressedColor = 0xffff3b30;
    private int rightPressedColor = 0xff34c759;
    private boolean leftPressed;
    private boolean rightPressed;
    private int activeColorButton = -1;

    private float targetX;
    private float targetY;
    private float offsetX;
    private float offsetY;
    private float velocityX;
    private float velocityY;

    private float windowProgress;
    private float windowVelocity;
    private float windowTarget = 1f;
    private float dragProgress;
    private float dragVelocity;
    private long lastFrameMs;
    private boolean framePosted;
    private Runnable exitCallback;
    private float springValue;
    private float springVelocity;

    private final Runnable frameRunnable = new Runnable() {
        @Override public void run() {
            framePosted = false;
            stepFrame();
        }
    };

    public MouseTrajectoryView(Context context) {
        super(context);
        setWillNotDraw(false);
        panelPaint.setStyle(Paint.Style.FILL);
        panelPaint.setColor(UiPalette.trajectoryPanel(context));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1));
        strokePaint.setColor(UiPalette.trajectoryStroke(context));
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(UiPalette.trajectoryDot(context));
        setScaleX(0.94f);
        setScaleY(0.94f);
        postFrame();
    }

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled && dragging) finishDrag();
    }

    public void setDisplaySize(int percent) {
        displaySizePercent = Math.max(50, Math.min(150, percent));
        if (htmlView != null) htmlView.setDisplaySize(displaySizePercent);
    }

    public void setDotSize(int percent) {
        dotSizePercent = Math.max(50, Math.min(150, percent));
        if (htmlView != null) htmlView.setDotSizePercent(dotSizePercent);
        invalidate();
    }

    public void setButtonColorConfig(boolean leftEnabled, int leftColor,
                                     boolean rightEnabled, int rightColor) {
        leftColorEnabled = leftEnabled;
        rightColorEnabled = rightEnabled;
        leftPressedColor = 0xff000000 | (leftColor & 0x00ffffff);
        rightPressedColor = 0xff000000 | (rightColor & 0x00ffffff);
        if (htmlView != null) {
            htmlView.setTrajectoryButtonColors(
                    leftColorEnabled, leftPressedColor, rightColorEnabled, rightPressedColor);
        }
        invalidate();
    }

    public void setMouseStats(long stats) {
        boolean nextLeft = (stats & 1L) != 0;
        boolean nextRight = (stats & 2L) != 0;
        if (!leftPressed && nextLeft) activeColorButton = 0;
        if (!rightPressed && nextRight) activeColorButton = 1;
        if (leftPressed && !nextLeft && activeColorButton == 0) activeColorButton = nextRight ? 1 : -1;
        if (rightPressed && !nextRight && activeColorButton == 1) activeColorButton = nextLeft ? 0 : -1;
        leftPressed = nextLeft;
        rightPressed = nextRight;
        if (htmlView != null) htmlView.setMouseStats(stats);
        invalidate();
    }

    public void setGlobalHtmlRenderer(boolean enabled, String html) {
        boolean usable = enabled && html != null && !html.trim().isEmpty();
        globalHtmlEnabled = usable;
        if (!usable) {
            destroyHtmlView();
            invalidate();
            return;
        }
        GlobalHtmlWebView web = ensureHtmlView();
        web.setVisibility(View.VISIBLE);
        web.setDisplaySize(displaySizePercent);
        web.setDotSizePercent(dotSizePercent);
        web.setTrajectoryButtonColors(
                leftColorEnabled, leftPressedColor, rightColorEnabled, rightPressedColor);
        web.setMouseStats((leftPressed ? 1L : 0L) | (rightPressed ? 2L : 0L));
        web.loadRendererHtml(html);
        invalidate();
    }

    public void animateIn() {
        exitCallback = null;
        windowTarget = 1f;
        postFrame();
    }

    public void animateOut(Runnable endAction) {
        exitCallback = endAction;
        windowTarget = 0f;
        postFrame();
    }

    /**
     * Adds relative movement. Direction maps directly; magnitude is softened so fast swipes remain
     * inside the square instead of slamming against an edge.
     */
    public void addMotion(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        if (htmlView != null) htmlView.addPointerDelta(dx, dy);
        int safeDx = clamp(dx, -1024, 1024);
        int safeDy = clamp(dy, -1024, 1024);

        float impulse = dp(0.72f);
        targetX += soften(safeDx) * impulse;
        targetY += soften(safeDy) * impulse;

        // A tiny direct component makes the first frame feel immediate, while the target follower
        // provides the actual smooth motion.
        offsetX += soften(safeDx) * dp(0.06f);
        offsetY += soften(safeDy) * dp(0.06f);
        clampState();
        postFrame();
    }

    public void resetMotion() {
        if (htmlView != null) htmlView.resetPointer();
        targetX = targetY = 0f;
        offsetX = offsetY = 0f;
        velocityX = velocityY = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (globalHtmlEnabled) return;
        final float pad = dp(4);
        final float side = Math.min(getWidth(), getHeight());
        final float radius = Math.min(dp(18), side * 0.19f);
        panel.set(pad, pad, getWidth() - pad, getHeight() - pad);
        canvas.drawRoundRect(panel, radius, radius, panelPaint);
        canvas.drawRoundRect(panel, radius, radius, strokePaint);

        final float centerX = panel.centerX();
        final float centerY = panel.centerY();
        float speed = (float) Math.hypot(velocityX, velocityY);
        float speedNorm = Math.min(1f, speed / Math.max(dp(900), 1f));
        float dotRadius = dp(5.8f) * (dotSizePercent / 100f) * (1f + speedNorm * 0.055f);
        dotPaint.setColor(resolveDotColor());
        canvas.drawCircle(centerX + offsetX, centerY + offsetY, dotRadius, dotPaint);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return dragEnabled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!dragEnabled) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (dragListener != null) dragListener.onDragStart(this, event.getRawX(), event.getRawY());
                postFrame();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && dragListener != null) dragListener.onDragMove(this, event.getRawX(), event.getRawY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishDrag();
                return true;
            default:
                return true;
        }
    }

    private int resolveDotColor() {
        if (activeColorButton == 0 && leftPressed && leftColorEnabled) return leftPressedColor;
        if (activeColorButton == 1 && rightPressed && rightColorEnabled) return rightPressedColor;
        if (leftPressed && leftColorEnabled) return leftPressedColor;
        if (rightPressed && rightColorEnabled) return rightPressedColor;
        return UiPalette.trajectoryDot(getContext());
    }

    private GlobalHtmlWebView ensureHtmlView() {
        if (htmlView != null) return htmlView;
        GlobalHtmlWebView web = new GlobalHtmlWebView(getContext(), GlobalHtmlWebView.TYPE_MOUSE_TRAJECTORY);
        web.setDisplaySize(displaySizePercent);
        web.setDotSizePercent(dotSizePercent);
        web.setTrajectoryButtonColors(
                leftColorEnabled, leftPressedColor, rightColorEnabled, rightPressedColor);
        htmlView = web;
        addView(web, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return web;
    }

    private void destroyHtmlView() {
        GlobalHtmlWebView web = htmlView;
        if (web == null) return;
        htmlView = null;
        removeView(web);
        web.stopLoading();
        web.loadUrl("about:blank");
        web.destroy();
    }

    @Override
    protected void onDetachedFromWindow() {
        GlobalHtmlWebView web = htmlView;
        htmlView = null;
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDetachedFromWindow();
    }

    private void finishDrag() {
        if (!dragging) return;
        dragging = false;
        if (dragListener != null) dragListener.onDragEnd(this);
        postFrame();
    }

    private void postFrame() {
        if (framePosted) return;
        framePosted = true;
        postOnAnimation(frameRunnable);
    }

    private void stepFrame() {
        long now = SystemClock.uptimeMillis();
        if (lastFrameMs == 0L) lastFrameMs = now;
        float dt = Math.min(0.022f, Math.max(0.001f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;

        // Target memory is deliberately short. It makes repeated motion feel weighted without
        // leaving the dot stuck near an edge after the hand stops.
        float targetDecay = (float) Math.exp(-8.6f * dt);
        targetX *= targetDecay;
        targetY *= targetDecay;

        // Near-critical damping: fast arrival, essentially no visible bounce.
        final float stiffness = 225f;
        final float damping = 28.5f;
        velocityX += (stiffness * (targetX - offsetX) - damping * velocityX) * dt;
        velocityY += (stiffness * (targetY - offsetY) - damping * velocityY) * dt;
        offsetX += velocityX * dt;
        offsetY += velocityY * dt;
        clampState();

        spring(windowProgress, windowVelocity, windowTarget, dt, 315f, 29f);
        windowProgress = springValue;
        windowVelocity = springVelocity;
        spring(dragProgress, dragVelocity, dragging ? 1f : 0f, dt, 430f, 36f);
        dragProgress = springValue;
        dragVelocity = springVelocity;

        float enterScale = 0.94f + 0.06f * clamp(windowProgress, 0f, 1.04f);
        float dragScale = 1f - 0.015f * clamp(dragProgress, 0f, 1.02f);
        float scale = enterScale * dragScale;
        setPivotX(getWidth() * 0.5f);
        setPivotY(getHeight() * 0.5f);
        setScaleX(scale);
        setScaleY(scale);
        invalidate();

        boolean dotActive = Math.abs(targetX) > 0.025f || Math.abs(targetY) > 0.025f
                || Math.abs(offsetX) > 0.035f || Math.abs(offsetY) > 0.035f
                || Math.abs(velocityX) > 0.35f || Math.abs(velocityY) > 0.35f;
        if (!dotActive) {
            targetX = targetY = 0f;
            offsetX = offsetY = 0f;
            velocityX = velocityY = 0f;
        }
        boolean hostActive = Math.abs(windowTarget - windowProgress) > 0.0015f
                || Math.abs(windowVelocity) > 0.02f;
        boolean dragActive = Math.abs((dragging ? 1f : 0f) - dragProgress) > 0.0015f
                || Math.abs(dragVelocity) > 0.02f;

        if (windowTarget == 0f && !hostActive && exitCallback != null) {
            Runnable callback = exitCallback;
            exitCallback = null;
            post(callback);
            return;
        }
        if (dotActive || hostActive || dragActive) postFrame();
    }

    private void clampState() {
        float side = Math.min(getWidth(), getHeight());
        float max = side > 0 ? side * 0.28f : dp(28);
        targetX = clamp(targetX, -max, max);
        targetY = clamp(targetY, -max, max);
        if (offsetX < -max) { offsetX = -max; velocityX *= 0.30f; }
        else if (offsetX > max) { offsetX = max; velocityX *= 0.30f; }
        if (offsetY < -max) { offsetY = -max; velocityY *= 0.30f; }
        else if (offsetY > max) { offsetY = max; velocityY *= 0.30f; }
    }

    private float soften(int delta) {
        float sign = delta < 0 ? -1f : 1f;
        float magnitude = Math.abs(delta);
        // Sub-linear mapping preserves precision for tiny mouse deltas and compresses large bursts.
        return sign * (float) Math.sqrt(magnitude);
    }

    private void spring(float value, float velocity, float target, float dt, float stiffness, float damping) {
        float acceleration = stiffness * (target - value) - damping * velocity;
        velocity += acceleration * dt;
        value += velocity * dt;
        if (Math.abs(target - value) < 0.0015f && Math.abs(velocity) < 0.02f) {
            value = target;
            velocity = 0f;
        }
        springValue = value;
        springVelocity = velocity;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
