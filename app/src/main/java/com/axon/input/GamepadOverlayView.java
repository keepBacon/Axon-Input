package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 原生 Canvas 手柄 HUD。左摇杆、右摇杆、ABXY、左肩键、右肩键分别是独立悬浮窗口。
 * 此类只维护绘制和动画状态，不负责读取设备文件。
 */
public final class GamepadOverlayView extends FrameLayout {
    public static final int DISPLAY_LEFT_STICK = 10;
    public static final int DISPLAY_RIGHT_STICK = 11;
    public static final int DISPLAY_FACE = 12;
    public static final int DISPLAY_LEFT_SHOULDER = 13;
    public static final int DISPLAY_RIGHT_SHOULDER = 14;

    public static final int SHAPE_CIRCLE = 0;
    public static final int SHAPE_SQUARE = 1;

    public static final int BTN_SOUTH = 1 << 0;
    public static final int BTN_EAST = 1 << 1;
    public static final int BTN_C = 1 << 2;
    public static final int BTN_NORTH = 1 << 3;
    public static final int BTN_WEST = 1 << 4;
    public static final int BTN_Z = 1 << 5;
    public static final int BTN_L1 = 1 << 6;
    public static final int BTN_R1 = 1 << 7;
    public static final int BTN_L2 = 1 << 8;
    public static final int BTN_R2 = 1 << 9;
    public static final int BTN_L3 = 1 << 13;
    public static final int BTN_R3 = 1 << 14;

    private static final String[] FACE_LABELS_NORMAL = {"Y", "B", "A", "X"};
    private static final String[] FACE_LABELS_REVERSED = {"A", "X", "Y", "B"};

    public interface DragListener {
        void onDragStart(GamepadOverlayView source, float rawX, float rawY);
        void onDragMove(GamepadOverlayView source, float rawX, float rawY);
        void onDragEnd(GamepadOverlayView source);
    }

    private final int displayType;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private DragListener dragListener;
    private boolean dragEnabled;
    private boolean dragging;
    private int stickShape = SHAPE_CIRCLE;
    private boolean faceReversed;
    private boolean faceYDpsEnabled;
    private boolean faceXDpsEnabled;
    private boolean faceBDpsEnabled;
    private boolean faceADpsEnabled;
    private boolean triggerProgressEnabled;
    private boolean shoulderDpsEnabled;
    private int faceYDps;
    private int faceXDps;
    private int faceBDps;
    private int faceADps;
    private int l1Dps;
    private int r1Dps;
    private int displaySizePercent = 100;
    private int stickDotSizePercent = 100;
    private GlobalHtmlWebView htmlView;
    private boolean globalHtmlEnabled;

    private float targetX;
    private float targetY;
    private float shownX;
    private float shownY;
    private float velocityX;
    private float velocityY;
    private float targetLt;
    private float targetRt;
    private float shownLt;
    private float shownRt;
    private float triggerVelocityLt;
    private float triggerVelocityRt;
    private int buttons;
    private boolean stickPressDown;
    private float stickPulse;
    private float stickPulseVelocity;

    private final float[] press = {1f, 1f, 1f, 1f};
    private final float[] pressVelocity = {0f, 0f, 0f, 0f};
    private final boolean[] pressTargets = new boolean[4];

    private float hostProgress;
    private float hostVelocity;
    private float hostTarget = 1f;
    private float dragProgress;
    private float dragVelocity;
    private long lastFrameMs;
    private boolean framePosted;
    private Runnable exitCallback;

    private final Runnable frameRunnable = new Runnable() {
        @Override public void run() {
            framePosted = false;
            stepFrame();
        }
    };

    public GamepadOverlayView(Context context, int displayType) {
        super(context);
        this.displayType = displayType;
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(UiPalette.overlayShell(context));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setColor(UiPalette.overlayStroke(context));
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        textPaint.setColor(UiPalette.overlayTextIdle(context));
        accentPaint.setStyle(Paint.Style.FILL);
        accentPaint.setColor(UiPalette.overlayKeyPressed(context));

        setScaleX(0.94f);
        setScaleY(0.94f);
        postFrame();
    }

    public int getDisplayType() { return displayType; }

    public void setDragListener(DragListener listener) { dragListener = listener; }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled && dragging) finishDrag();
    }

    public void setDisplaySize(int percent) {
        displaySizePercent = Math.max(50, Math.min(150, percent));
        if (htmlView != null) htmlView.setDisplaySize(displaySizePercent);
    }

    public void setStickDotSize(int percent) {
        stickDotSizePercent = Math.max(50, Math.min(150, percent));
        if (htmlView != null) htmlView.setDotSizePercent(stickDotSizePercent);
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
        web.setDotSizePercent(stickDotSizePercent);
        web.loadRendererHtml(html);
        web.setStickShape(stickShape);
        web.setFaceReversed(faceReversed);
        web.setFaceDpsConfig(faceYDpsEnabled, faceXDpsEnabled, faceBDpsEnabled, faceADpsEnabled);
        web.setShoulderConfig(triggerProgressEnabled, shoulderDpsEnabled);
        web.setGamepadDpsStats(faceYDps, faceXDps, faceBDps, faceADps, l1Dps, r1Dps);
        web.setGamepadState(gamepadLxValue(), gamepadLyValue(), gamepadRxValue(), gamepadRyValue(),
                Math.round(targetLt * 1000f), Math.round(targetRt * 1000f), buttons);
        invalidate();
    }

    public void setStickShape(int shape) {
        int resolved = shape == SHAPE_SQUARE ? SHAPE_SQUARE : SHAPE_CIRCLE;
        if (stickShape == resolved) return;
        stickShape = resolved;
        if (htmlView != null) htmlView.setStickShape(stickShape);
        invalidate();
    }

    public void setFaceReversed(boolean reversed) {
        if (faceReversed == reversed) return;
        faceReversed = reversed;
        if (htmlView != null) htmlView.setFaceReversed(faceReversed);
        invalidate();
    }

    public void setFaceDpsVisibility(boolean y, boolean x, boolean b, boolean a) {
        faceYDpsEnabled = y;
        faceXDpsEnabled = x;
        faceBDpsEnabled = b;
        faceADpsEnabled = a;
        if (htmlView != null) htmlView.setFaceDpsConfig(y, x, b, a);
        invalidate();
    }

    public void setShoulderOptions(boolean showTriggerProgress, boolean showDps) {
        triggerProgressEnabled = showTriggerProgress;
        shoulderDpsEnabled = showDps;
        if (htmlView != null) htmlView.setShoulderConfig(showTriggerProgress, showDps);
        invalidate();
    }

    public void setDpsStats(int y, int x, int b, int a, int l1, int r1) {
        int nextY = Math.max(0, y);
        int nextX = Math.max(0, x);
        int nextB = Math.max(0, b);
        int nextA = Math.max(0, a);
        int nextL1 = Math.max(0, l1);
        int nextR1 = Math.max(0, r1);
        if (faceYDps == nextY && faceXDps == nextX && faceBDps == nextB && faceADps == nextA
                && l1Dps == nextL1 && r1Dps == nextR1) return;
        faceYDps = nextY; faceXDps = nextX; faceBDps = nextB; faceADps = nextA;
        l1Dps = nextL1; r1Dps = nextR1;
        if (htmlView != null) htmlView.setGamepadDpsStats(faceYDps, faceXDps, faceBDps, faceADps, l1Dps, r1Dps);
        invalidate();
    }

    public void animateIn() {
        exitCallback = null;
        hostTarget = 1f;
        postFrame();
    }

    public void animateOut(Runnable endAction) {
        exitCallback = endAction;
        hostTarget = 0f;
        postFrame();
    }

    public void resetState() {
        targetX = targetY = shownX = shownY = velocityX = velocityY = 0f;
        targetLt = targetRt = shownLt = shownRt = triggerVelocityLt = triggerVelocityRt = 0f;
        buttons = 0;
        stickPressDown = false;
        stickPulse = 0f;
        stickPulseVelocity = 0f;
        for (int i = 0; i < 4; i++) {
            press[i] = 1f;
            pressVelocity[i] = 0f;
            pressTargets[i] = false;
        }
        if (htmlView != null) htmlView.setGamepadState(0, 0, 0, 0, 0, 0, 0);
        invalidate();
    }

    /** State ranges: sticks -1000..1000, triggers 0..1000, buttons use Linux gamepad bits. */
    public void setGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttonMask) {
        if (displayType == DISPLAY_LEFT_STICK) {
            targetX = clamp(lx / 1000f, -1f, 1f);
            targetY = clamp(ly / 1000f, -1f, 1f);
        } else if (displayType == DISPLAY_RIGHT_STICK) {
            targetX = clamp(rx / 1000f, -1f, 1f);
            targetY = clamp(ry / 1000f, -1f, 1f);
        }
        targetLt = clamp(lt / 1000f, 0f, 1f);
        targetRt = clamp(rt / 1000f, 0f, 1f);
        buttons = buttonMask;

        if (displayType == DISPLAY_LEFT_STICK || displayType == DISPLAY_RIGHT_STICK) {
            boolean pressed = displayType == DISPLAY_LEFT_STICK
                    ? (buttons & BTN_L3) != 0 : (buttons & BTN_R3) != 0;
            if (pressed && !stickPressDown) {
                // L3/R3 feedback is size-only: a short radial pulse without moving the knob.
                stickPulseVelocity += 8.2f;
                postFrame();
            }
            stickPressDown = pressed;
        }

        if (displayType == DISPLAY_FACE) {
            pressTargets[0] = (buttons & BTN_NORTH) != 0;
            pressTargets[1] = (buttons & (BTN_EAST | BTN_Z)) != 0;
            pressTargets[2] = (buttons & BTN_SOUTH) != 0;
            pressTargets[3] = (buttons & (BTN_WEST | BTN_C)) != 0;
        } else if (displayType == DISPLAY_LEFT_SHOULDER) {
            pressTargets[0] = (buttons & BTN_L1) != 0;
            pressTargets[1] = (buttons & BTN_L2) != 0 || targetLt > 0.08f;
        } else if (displayType == DISPLAY_RIGHT_SHOULDER) {
            pressTargets[0] = (buttons & BTN_R1) != 0;
            pressTargets[1] = (buttons & BTN_R2) != 0 || targetRt > 0.08f;
        }
        if (htmlView != null) htmlView.setGamepadState(lx, ly, rx, ry, lt, rt, buttonMask);
        postFrame();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (globalHtmlEnabled) return;
        if (displayType == DISPLAY_LEFT_STICK || displayType == DISPLAY_RIGHT_STICK) drawStick(canvas);
        else if (displayType == DISPLAY_FACE) drawFaceButtons(canvas);
        else if (displayType == DISPLAY_LEFT_SHOULDER) drawShoulders(canvas, true);
        else if (displayType == DISPLAY_RIGHT_SHOULDER) drawShoulders(canvas, false);
    }

    private void drawStick(Canvas canvas) {
        float side = Math.min(getWidth(), getHeight());
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float outer = side * 0.43f;
        float innerBase = side * 0.21f * (stickDotSizePercent / 100f);
        float pulseScale = 1f + clamp(stickPulse, -0.08f, 0.16f);
        float inner = innerBase * pulseScale;

        if (stickShape == SHAPE_SQUARE) {
            float radius = side * 0.16f;
            rect.set(cx - outer, cy - outer, cx + outer, cy + outer);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        } else {
            canvas.drawCircle(cx, cy, outer, fillPaint);
            canvas.drawCircle(cx, cy, outer, strokePaint);
        }

        // Travel is based on the un-pulsed knob radius so L3/R3 size feedback never
        // changes the knob center position, even while the stick is tilted.
        float travel = outer - innerBase - side * 0.04f;
        float knobX = cx + shownX * travel;
        float knobY = cy + shownY * travel;
        canvas.drawCircle(knobX, knobY, inner, accentPaint);
        strokePaint.setColor(UiPalette.overlayStroke(getContext()));
        canvas.drawCircle(knobX, knobY, inner, strokePaint);
    }

    private void drawFaceButtons(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float gap = Math.min(w, h) * 0.245f;
        float radius = Math.min(w, h) * 0.155f;
        textPaint.setTextSize(radius * 0.92f);

        String[] labels = faceReversed ? FACE_LABELS_REVERSED : FACE_LABELS_NORMAL;
        drawFaceButton(canvas, cx, cy - gap, radius, labels[0], press[0], faceDpsEnabled(labels[0]), faceDps(labels[0]));
        drawFaceButton(canvas, cx + gap, cy, radius, labels[1], press[1], faceDpsEnabled(labels[1]), faceDps(labels[1]));
        drawFaceButton(canvas, cx, cy + gap, radius, labels[2], press[2], faceDpsEnabled(labels[2]), faceDps(labels[2]));
        drawFaceButton(canvas, cx - gap, cy, radius, labels[3], press[3], faceDpsEnabled(labels[3]), faceDps(labels[3]));
    }

    private boolean faceDpsEnabled(String label) {
        switch (label) {
            case "Y": return faceYDpsEnabled;
            case "X": return faceXDpsEnabled;
            case "B": return faceBDpsEnabled;
            case "A": return faceADpsEnabled;
            default: return false;
        }
    }

    private int faceDps(String label) {
        switch (label) {
            case "Y": return faceYDps;
            case "X": return faceXDps;
            case "B": return faceBDps;
            case "A": return faceADps;
            default: return 0;
        }
    }

    private void drawFaceButton(Canvas canvas, float cx, float cy, float radius, String label, float scale, boolean showDps, int dps) {
        float r = radius * scale;
        Paint body = scale < 0.97f ? accentPaint : fillPaint;
        int oldText = textPaint.getColor();
        Typeface oldTypeface = textPaint.getTypeface();
        textPaint.setColor(scale < 0.97f ? UiPalette.overlayTextPressed(getContext()) : UiPalette.overlayTextIdle(getContext()));
        canvas.drawCircle(cx, cy, r, body);
        canvas.drawCircle(cx, cy, r, strokePaint);

        // Keep the primary label at exactly the same size whether DPS is enabled or not.
        final float primarySize = radius * 0.92f;
        textPaint.setTextSize(primarySize);
        Paint.FontMetrics primaryFm = textPaint.getFontMetrics();
        float primaryBaseline = cy - (primaryFm.ascent + primaryFm.descent) * 0.5f;

        if (showDps) {
            // Move only the baseline upward; never shrink Y/X/B/A just to make room for DPS.
            primaryBaseline -= radius * 0.16f;
            canvas.drawText(label, cx, primaryBaseline, textPaint);

            textPaint.setTextSize(Math.min(radius * 0.28f, dp(7.5f)));
            textPaint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            Paint.FontMetrics dpsFm = textPaint.getFontMetrics();
            float dpsBaseline = cy + radius * 0.62f - dpsFm.descent;
            canvas.drawText(dps + " DPS", cx, dpsBaseline, textPaint);
        } else {
            canvas.drawText(label, cx, primaryBaseline, textPaint);
        }
        textPaint.setTypeface(oldTypeface);
        textPaint.setColor(oldText);
    }

    private void drawShoulders(Canvas canvas, boolean left) {
        float pad = dp(6f);
        float w = getWidth() - pad * 2f;
        float h = getHeight() - pad * 2f;
        float topHeight = h * 0.38f;
        float bottomTop = pad + topHeight + h * 0.08f;
        float radius = Math.min(w, h) * 0.12f;

        float topScale = press[0];
        float topInset = (1f - topScale) * w * 0.06f;
        rect.set(pad + topInset, pad + (1f - topScale) * topHeight * 0.24f,
                pad + w - topInset, pad + topHeight);
        Paint topPaint = topScale < 0.97f ? accentPaint : fillPaint;
        canvas.drawRoundRect(rect, radius, radius, topPaint);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
        if (shoulderDpsEnabled) {
            drawCenteredTextWithDps(canvas, left ? "L1" : "R1", left ? l1Dps : r1Dps, rect, topScale < 0.97f);
        } else {
            drawCenteredText(canvas, left ? "L1" : "R1", rect, topScale < 0.97f);
        }

        float trigger = left ? shownLt : shownRt;
        float bottomScale = press[1];
        float bottomInset = (1f - bottomScale) * w * 0.05f;
        rect.set(pad + bottomInset, bottomTop,
                pad + w - bottomInset, pad + h);
        boolean triggerPressed = bottomScale < 0.97f || trigger > 0.08f;
        Paint bottomPaint = (!triggerProgressEnabled && triggerPressed) ? accentPaint : fillPaint;
        canvas.drawRoundRect(rect, radius * 1.18f, radius * 1.18f, bottomPaint);

        if (triggerProgressEnabled && trigger > 0.001f) {
            RectF fill = new RectF(rect.left, rect.top,
                    rect.left + rect.width() * clamp(trigger, 0f, 1f), rect.bottom);
            canvas.save();
            canvas.clipRect(fill);
            canvas.drawRoundRect(rect, radius * 1.18f, radius * 1.18f, accentPaint);
            canvas.restore();
        }
        canvas.drawRoundRect(rect, radius * 1.18f, radius * 1.18f, strokePaint);
        drawCenteredText(canvas, left ? "L2" : "R2", rect, triggerPressed);
    }

    private void drawCenteredText(Canvas canvas, String text, RectF area, boolean pressed) {
        textPaint.setTextSize(Math.min(area.height() * 0.43f, dp(18f)));
        textPaint.setColor(pressed ? UiPalette.overlayTextPressed(getContext()) : UiPalette.overlayTextIdle(getContext()));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = area.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(text, area.centerX(), baseline, textPaint);
    }

    private void drawCenteredTextWithDps(Canvas canvas, String text, int dps, RectF area, boolean pressed) {
        Typeface oldTypeface = textPaint.getTypeface();
        textPaint.setColor(pressed ? UiPalette.overlayTextPressed(getContext()) : UiPalette.overlayTextIdle(getContext()));

        // Primary L1/R1 text uses the exact same size/typeface as drawCenteredText().
        final float primarySize = Math.min(area.height() * 0.43f, dp(18f));
        textPaint.setTextSize(primarySize);
        textPaint.setTypeface(oldTypeface);
        Paint.FontMetrics primaryFm = textPaint.getFontMetrics();
        float primaryBaseline = area.centerY() - (primaryFm.ascent + primaryFm.descent) * 0.5f;
        primaryBaseline -= Math.min(dp(4f), area.height() * 0.09f);
        canvas.drawText(text, area.centerX(), primaryBaseline, textPaint);

        // DPS is a subordinate readout: always below and allowed to shrink first when space is tight.
        final float dpsSize = Math.min(area.height() * 0.17f, dp(8.5f));
        textPaint.setTextSize(Math.max(dp(6f), dpsSize));
        textPaint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        Paint.FontMetrics dpsFm = textPaint.getFontMetrics();
        float dpsBaseline = area.bottom - dp(3f) - dpsFm.descent;
        canvas.drawText(dps + " DPS", area.centerX(), dpsBaseline, textPaint);
        textPaint.setTypeface(oldTypeface);
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

    private GlobalHtmlWebView ensureHtmlView() {
        if (htmlView != null) return htmlView;
        String type;
        switch (displayType) {
            case DISPLAY_RIGHT_STICK: type = GlobalHtmlWebView.TYPE_GAMEPAD_RIGHT_STICK; break;
            case DISPLAY_FACE: type = GlobalHtmlWebView.TYPE_GAMEPAD_FACE; break;
            case DISPLAY_LEFT_SHOULDER: type = GlobalHtmlWebView.TYPE_GAMEPAD_LEFT_SHOULDER; break;
            case DISPLAY_RIGHT_SHOULDER: type = GlobalHtmlWebView.TYPE_GAMEPAD_RIGHT_SHOULDER; break;
            default: type = GlobalHtmlWebView.TYPE_GAMEPAD_LEFT_STICK; break;
        }
        GlobalHtmlWebView web = new GlobalHtmlWebView(getContext(), type);
        web.setDisplaySize(displaySizePercent);
        web.setDotSizePercent(stickDotSizePercent);
        web.setFaceDpsConfig(faceYDpsEnabled, faceXDpsEnabled, faceBDpsEnabled, faceADpsEnabled);
        web.setShoulderConfig(triggerProgressEnabled, shoulderDpsEnabled);
        web.setGamepadDpsStats(faceYDps, faceXDps, faceBDps, faceADps, l1Dps, r1Dps);
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

    private int gamepadLxValue() { return displayType == DISPLAY_LEFT_STICK ? Math.round(targetX * 1000f) : 0; }
    private int gamepadLyValue() { return displayType == DISPLAY_LEFT_STICK ? Math.round(targetY * 1000f) : 0; }
    private int gamepadRxValue() { return displayType == DISPLAY_RIGHT_STICK ? Math.round(targetX * 1000f) : 0; }
    private int gamepadRyValue() { return displayType == DISPLAY_RIGHT_STICK ? Math.round(targetY * 1000f) : 0; }

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

        // Stick: quick, near-critically-damped tracking. No deliberate overshoot.
        float ax = 210f * (targetX - shownX) - 28f * velocityX;
        float ay = 210f * (targetY - shownY) - 28f * velocityY;
        velocityX += ax * dt;
        velocityY += ay * dt;
        shownX += velocityX * dt;
        shownY += velocityY * dt;

        float alt = 190f * (targetLt - shownLt) - 25f * triggerVelocityLt;
        float art = 190f * (targetRt - shownRt) - 25f * triggerVelocityRt;
        triggerVelocityLt += alt * dt;
        triggerVelocityRt += art * dt;
        shownLt += triggerVelocityLt * dt;
        shownRt += triggerVelocityRt * dt;

        // L3/R3 radial pulse. Slightly under-damped so the knob gives one compact tactile-looking
        // size vibration while its center position remains exactly unchanged.
        float stickPulseA = -300f * stickPulse - 24f * stickPulseVelocity;
        stickPulseVelocity += stickPulseA * dt;
        stickPulse += stickPulseVelocity * dt;
        if (Math.abs(stickPulse) < 0.0007f && Math.abs(stickPulseVelocity) < 0.012f) {
            stickPulse = 0f;
            stickPulseVelocity = 0f;
        }

        boolean buttonActive = false;
        for (int i = 0; i < 4; i++) {
            float target = pressTargets[i] ? 0.86f : 1f;
            float a = 360f * (target - press[i]) - 31f * pressVelocity[i];
            pressVelocity[i] += a * dt;
            press[i] += pressVelocity[i] * dt;
            if (Math.abs(target - press[i]) < 0.0008f && Math.abs(pressVelocity[i]) < 0.012f) {
                press[i] = target;
                pressVelocity[i] = 0f;
            } else {
                buttonActive = true;
            }
        }

        float hostA = 315f * (hostTarget - hostProgress) - 29f * hostVelocity;
        hostVelocity += hostA * dt;
        hostProgress += hostVelocity * dt;
        float dragTarget = dragging ? 1f : 0f;
        float dragA = 430f * (dragTarget - dragProgress) - 36f * dragVelocity;
        dragVelocity += dragA * dt;
        dragProgress += dragVelocity * dt;

        if (Math.abs(hostTarget - hostProgress) < 0.001f && Math.abs(hostVelocity) < 0.02f) {
            hostProgress = hostTarget;
            hostVelocity = 0f;
        }
        if (Math.abs(dragTarget - dragProgress) < 0.001f && Math.abs(dragVelocity) < 0.02f) {
            dragProgress = dragTarget;
            dragVelocity = 0f;
        }

        float enterScale = 0.94f + 0.06f * clamp(hostProgress, 0f, 1.04f);
        float dragScale = 1f - 0.015f * clamp(dragProgress, 0f, 1.02f);
        float hostScale = enterScale * dragScale;
        setPivotX(getWidth() * 0.5f);
        setPivotY(getHeight() * 0.5f);
        setScaleX(hostScale);
        setScaleY(hostScale);
        invalidate();

        boolean stickActive = Math.abs(targetX - shownX) > 0.0008f || Math.abs(targetY - shownY) > 0.0008f
                || Math.abs(velocityX) > 0.01f || Math.abs(velocityY) > 0.01f;
        boolean triggerActive = Math.abs(targetLt - shownLt) > 0.0008f || Math.abs(targetRt - shownRt) > 0.0008f
                || Math.abs(triggerVelocityLt) > 0.01f || Math.abs(triggerVelocityRt) > 0.01f;
        boolean stickPulseActive = Math.abs(stickPulse) > 0.0007f || Math.abs(stickPulseVelocity) > 0.012f;
        boolean hostActive = Math.abs(hostTarget - hostProgress) > 0.001f || Math.abs(hostVelocity) > 0.02f;
        boolean dragActive = Math.abs(dragTarget - dragProgress) > 0.001f || Math.abs(dragVelocity) > 0.02f;

        if (hostTarget == 0f && !hostActive && exitCallback != null) {
            Runnable callback = exitCallback;
            exitCallback = null;
            post(callback);
            return;
        }
        if (stickActive || triggerActive || stickPulseActive || buttonActive || hostActive || dragActive) postFrame();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
