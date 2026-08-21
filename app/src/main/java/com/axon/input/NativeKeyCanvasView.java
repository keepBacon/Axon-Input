package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** 键盘、鼠标和自定义键位的 Canvas 绘制器。统一处理尺寸、透明度和动画。 */
public final class NativeKeyCanvasView extends View {
    public static final int DISPLAY_KEYBOARD = 1;
    public static final int DISPLAY_CUSTOM = 2;
    public static final int DISPLAY_MOUSE = 3;

    public interface DragListener {
        void onDragStart(NativeKeyCanvasView source, float rawX, float rawY);
        void onDragMove(NativeKeyCanvasView source, float rawX, float rawY);
        void onDragEnd(NativeKeyCanvasView source);
    }

    private static final int SLOT_W = 0;
    private static final int SLOT_A = 1;
    private static final int SLOT_S = 2;
    private static final int SLOT_D = 3;
    private static final int SLOT_SPACE = 4;
    private static final int SLOT_MOUSE_L = 5;
    private static final int SLOT_MOUSE_R = 6;

    private static final float CUSTOM_ROW_HEIGHT_DP = 50f;
    private static final float CUSTOM_MIN_HEIGHT_DP = 56f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int keyIdleColor;
    private final int keyPressedColor;
    private final int textIdleColor;
    private final int textPressedColor;
    private final int strokeColor;
    private final int shellColor;
    private final int secondaryTextColor;

    private final float[] progress = new float[7];
    private final float[] velocity = new float[7];
    private final boolean[] target = new boolean[7];

    private final int displayType;
    private int[] customKeyCodes = new int[0];
    private String[] customLabels = new String[0];
    private float[] customProgress = new float[0];
    private float[] customVelocity = new float[0];
    private boolean[] customTarget = new boolean[0];
    private int customColumns = 4;

    private final float keySize;
    private final float gap;
    private final float radius;
    private final float spaceWidth;
    private final float spaceHeight;
    private final float mouseWidth;
    private final float mouseHeight;

    private int pressedMask;
    private int animationMode = OverlayState.MOTION_SIZE;
    private long mouseStats;
    private boolean showSpace = true;
    private boolean showSpaceDps;
    private int spaceDps;
    private long lastFrameMs;
    private boolean dragEnabled;
    private boolean dragging;

    private float dragProgress;
    private float dragVelocity;

    private float revealProgress = 1f;
    private float revealVelocity;

    private float displayScale = 1f;
    private float displayScaleVelocity;
    private float displayScaleTarget = 1f;
    private boolean scaleInitialized;

    private float windowProgress = 1f;
    private float windowVelocity;
    private float windowTarget = 1f;
    private Runnable exitCallback;

    private final float[] springScratch = new float[2];
    private DragListener dragListener;

    public NativeKeyCanvasView(Context context, int displayType) {
        super(context);
        if (displayType < DISPLAY_KEYBOARD || displayType > DISPLAY_MOUSE) {
            throw new IllegalArgumentException("Unknown display type: " + displayType);
        }
        this.displayType = displayType;
        setWillNotDraw(false);

        keyIdleColor = UiPalette.overlayKeyIdle(context);
        keyPressedColor = UiPalette.overlayKeyPressed(context);
        textIdleColor = UiPalette.overlayTextIdle(context);
        textPressedColor = UiPalette.overlayTextPressed(context);
        strokeColor = UiPalette.overlayStroke(context);
        shellColor = UiPalette.overlayShell(context);
        secondaryTextColor = UiPalette.overlaySecondary(context);

        keySize = dp(50);
        gap = dp(8);
        radius = dp(10);
        spaceWidth = dp(150);
        spaceHeight = dp(44);
        mouseWidth = dp(156);
        mouseHeight = dp(86);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1));
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(dp(1));
        dividerPaint.setColor(strokeColor);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans", Typeface.BOLD));
    }

    public int getDisplayType() {
        return displayType;
    }

    public void setAnimationMode(int mode) {
        int resolved = OverlayState.clampMotionMode(mode);
        if (animationMode == resolved) return;
        animationMode = resolved;
        if (animationMode == OverlayState.MOTION_NONE) {
            for (int i = 0; i < progress.length; i++) {
                progress[i] = target[i] ? 1f : 0f;
                velocity[i] = 0f;
            }
            for (int i = 0; i < customProgress.length; i++) {
                customProgress[i] = customTarget[i] ? 1f : 0f;
                customVelocity[i] = 0f;
            }
        }
        postInvalidateOnAnimation();
    }

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    public void setDragEnabled(boolean enabled) {
        if (dragEnabled == enabled) return;
        dragEnabled = enabled;
        if (!enabled && dragging) finishDrag();
        postInvalidateOnAnimation();
    }

    /** 进入动画支持从退出状态反向恢复。 */
    public void animateIn() {
        exitCallback = null;
        windowTarget = 1f;
        postInvalidateOnAnimation();
    }

    /** 退出动画只改变缩放。 */
    public void animateOut(Runnable endAction) {
        exitCallback = endAction;
        windowTarget = 0f;
        postInvalidateOnAnimation();
    }

    public void setDisplaySize(int percent) {
        displayScaleTarget = clampScale(percent / 100f);
        if (!scaleInitialized) {
            displayScale = displayScaleTarget;
            displayScaleVelocity = 0f;
            scaleInitialized = true;
        }
        postInvalidateOnAnimation();
    }

    public void setCustomKeys(int[] keyCodes, int columns) {
        if (displayType != DISPLAY_CUSTOM) return;
        if (keyCodes == null) keyCodes = new int[0];
        columns = Math.max(1, Math.min(8, columns));

        boolean changed = customColumns != columns || keyCodes.length != customKeyCodes.length;
        if (!changed) {
            for (int i = 0; i < keyCodes.length; i++) {
                if (keyCodes[i] != customKeyCodes[i]) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) return;

        int[] oldCodes = customKeyCodes;
        float[] oldProgress = customProgress;
        float[] oldVelocity = customVelocity;
        boolean[] oldTarget = customTarget;

        customKeyCodes = keyCodes.clone();
        customColumns = columns;
        customLabels = new String[keyCodes.length];
        customProgress = new float[keyCodes.length];
        customVelocity = new float[keyCodes.length];
        customTarget = new boolean[keyCodes.length];

        for (int i = 0; i < keyCodes.length; i++) {
            customLabels[i] = KeyLabel.fromKeyCode(keyCodes[i]);
            for (int j = 0; j < oldCodes.length; j++) {
                if (oldCodes[j] == keyCodes[i]) {
                    customProgress[i] = oldProgress[j];
                    customVelocity[i] = oldVelocity.length > j ? oldVelocity[j] : 0f;
                    customTarget[i] = oldTarget[j];
                    break;
                }
            }
        }

        // 内容变化只调整几何参数，不改变透明度。
        revealProgress = 0f;
        revealVelocity = 0f;
        postInvalidateOnAnimation();
    }

    public void setKeyboardOptions(boolean showSpace, boolean showSpaceDps) {
        if (displayType != DISPLAY_KEYBOARD) return;
        this.showSpace = showSpace;
        this.showSpaceDps = showSpace && showSpaceDps;
        postInvalidateOnAnimation();
    }

    public void setKeyboardDps(int dps) {
        if (displayType != DISPLAY_KEYBOARD) return;
        int next = Math.max(0, Math.min(999, dps));
        if (spaceDps == next) return;
        spaceDps = next;
        postInvalidateOnAnimation();
    }

    public void setPressedMask(int newMask) {
        if (displayType != DISPLAY_KEYBOARD || pressedMask == newMask) return;
        pressedMask = newMask;
        setTarget(SLOT_W, (newMask & NativeKeyEngine.W) != 0);
        setTarget(SLOT_A, (newMask & NativeKeyEngine.A) != 0);
        setTarget(SLOT_S, (newMask & NativeKeyEngine.S) != 0);
        setTarget(SLOT_D, (newMask & NativeKeyEngine.D) != 0);
        setTarget(SLOT_SPACE, (newMask & NativeKeyEngine.SPACE) != 0);
        postInvalidateOnAnimation();
    }

    public void setCustomKeyPressed(int keyCode, boolean pressed) {
        if (displayType != DISPLAY_CUSTOM) return;
        for (int i = 0; i < customKeyCodes.length; i++) {
            if (customKeyCodes[i] == keyCode) {
                if (customTarget[i] != pressed) {
                    customTarget[i] = pressed;
                    if (animationMode == OverlayState.MOTION_NONE) {
                        customProgress[i] = pressed ? 1f : 0f;
                        customVelocity[i] = 0f;
                    }
                    postInvalidateOnAnimation();
                }
                return;
            }
        }
    }

    public void releaseCustomKeys() {
        if (displayType != DISPLAY_CUSTOM) return;
        boolean changed = false;
        for (int i = 0; i < customTarget.length; i++) {
            if (customTarget[i]) {
                customTarget[i] = false;
                if (animationMode == OverlayState.MOTION_NONE) {
                    customProgress[i] = 0f;
                    customVelocity[i] = 0f;
                }
                changed = true;
            }
        }
        if (changed) postInvalidateOnAnimation();
    }

    public void setMouseStats(long stats) {
        if (displayType != DISPLAY_MOUSE || mouseStats == stats) return;
        mouseStats = stats;
        setTarget(SLOT_MOUSE_L, (stats & 1L) != 0);
        setTarget(SLOT_MOUSE_R, (stats & 2L) != 0);
        postInvalidateOnAnimation();
    }

    public void releaseAll() {
        if (displayType == DISPLAY_KEYBOARD) setPressedMask(0);
        else if (displayType == DISPLAY_MOUSE) setMouseStats(0L);
        else releaseCustomKeys();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!dragEnabled) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (dragListener != null) dragListener.onDragStart(this, event.getRawX(), event.getRawY());
                postInvalidateOnAnimation();
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

    private void finishDrag() {
        if (!dragging) return;
        dragging = false;
        if (dragListener != null) dragListener.onDragEnd(this);
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean animating = updateMotion();

        int save = canvas.save();
        float centerX = getWidth() * 0.5f;
        float centerY = getHeight() * 0.5f;
        float top = dp(5);

        // 窗口进入和退出使用 92% 到 100% 缩放。拖动时压缩 1.6%。
        float windowScale = 0.92f + 0.08f * clamp(windowProgress, 0f, 1.08f);
        float dragScale = 1f - 0.016f * clamp(dragProgress, 0f, 1.05f);
        canvas.scale(windowScale * dragScale, windowScale * dragScale, centerX, centerY);
        canvas.scale(displayScale, displayScale, centerX, top);

        if (displayType == DISPLAY_KEYBOARD) {
            drawKeyboard(canvas, centerX, top);
        } else if (displayType == DISPLAY_CUSTOM) {
            drawCustomKeys(canvas, top);
        } else {
            drawMouse(canvas, centerX, top);
        }

        canvas.restoreToCount(save);

        if (windowTarget == 0f
                && Math.abs(windowProgress) < 0.0025f
                && Math.abs(windowVelocity) < 0.02f
                && exitCallback != null) {
            Runnable callback = exitCallback;
            exitCallback = null;
            post(callback);
        } else if (animating) {
            postInvalidateOnAnimation();
        }
    }

    private void drawKeyboard(Canvas canvas, float centerX, float top) {
        final float rowStep = keySize + gap;
        drawKey(canvas, SLOT_W, "W", centerX, top + keySize * 0.5f, keySize, keySize, false);

        final float secondY = top + rowStep + keySize * 0.5f;
        drawKey(canvas, SLOT_A, "A", centerX - rowStep, secondY, keySize, keySize, false);
        drawKey(canvas, SLOT_S, "S", centerX, secondY, keySize, keySize, false);
        drawKey(canvas, SLOT_D, "D", centerX + rowStep, secondY, keySize, keySize, false);

        if (showSpace) {
            final float spaceY = top + rowStep * 2f + spaceHeight * 0.5f;
            drawKey(canvas, SLOT_SPACE, "Space", centerX, spaceY, spaceWidth, spaceHeight, true);
        }
    }

    private void drawCustomKeys(Canvas canvas, float top) {
        float contentScale = 0.92f + 0.08f * clamp(revealProgress, 0f, 1.08f);
        int save = canvas.save();
        canvas.scale(contentScale, contentScale, getWidth() * 0.5f, top + dp(customBaseHeightDp()) * 0.5f);

        if (customKeyCodes.length == 0) {
            textPaint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            textPaint.setTextSize(dp(13));
            textPaint.setColor(secondaryTextColor);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = top + dp(customBaseHeightDp()) * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(getContext().getString(R.string.no_custom_keys), getWidth() * 0.5f, baseline, textPaint);
            textPaint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            canvas.restoreToCount(save);
            return;
        }

        float horizontalPadding = dp(10);
        float cellGap = dp(6);
        float baseWidth = dp(280);
        float available = baseWidth - horizontalPadding * 2f;
        float contentLeft = getWidth() * 0.5f - baseWidth * 0.5f + horizontalPadding;
        float cellWidth = (available - cellGap * (customColumns - 1)) / customColumns;
        float cellHeight = dp(42);
        float rowStep = dp(CUSTOM_ROW_HEIGHT_DP);

        for (int i = 0; i < customKeyCodes.length; i++) {
            int row = i / customColumns;
            int column = i % customColumns;
            float left = contentLeft + column * (cellWidth + cellGap);
            float cx = left + cellWidth * 0.5f;
            float cy = top + row * rowStep + cellHeight * 0.5f + dp(2);
            drawCustomKey(canvas, i, customLabels[i], cx, cy, cellWidth, cellHeight);
        }
        canvas.restoreToCount(save);
    }

    private void drawCustomKey(Canvas canvas, int index, String label, float cx, float cy, float width, float height) {
        float motion = customProgress[index];
        float scale = pressScale(motion);
        float w = width * scale;
        float h = height * scale;
        RectF rect = new RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);
        boolean pressed = customTarget[index];

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(withMotionAlpha(pressed ? keyPressedColor : keyIdleColor, motion));
        canvas.drawRoundRect(rect, radius, radius, fillPaint);

        if (!pressed) {
            strokePaint.setColor(withMotionAlpha(strokeColor, motion));
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        }

        textPaint.setColor(withMotionAlpha(pressed ? textPressedColor : textIdleColor, motion));
        float textSize = width < dp(36) ? 10f : width < dp(52) ? 12f : 14f;
        textPaint.setTextSize(dp(textSize));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(label, cx, baseline, textPaint);
    }

    private void drawMouse(Canvas canvas, float centerX, float top) {
        final float left = centerX - mouseWidth * 0.5f;
        final float right = centerX + mouseWidth * 0.5f;
        final float bottom = top + mouseHeight;
        final float mouseRadius = dp(24);
        final RectF shell = new RectF(left, top, right, bottom);

        // 底层深色区域用于保持鼠标按键释放时可见。
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(shellColor);
        canvas.drawRoundRect(shell, mouseRadius, mouseRadius, fillPaint);

        drawMouseHalf(canvas, shell, true, SLOT_MOUSE_L);
        drawMouseHalf(canvas, shell, false, SLOT_MOUSE_R);

        strokePaint.setColor(strokeColor);
        canvas.drawRoundRect(shell, mouseRadius, mouseRadius, strokePaint);
        canvas.drawLine(centerX, top + dp(5), centerX, bottom - dp(5), dividerPaint);

        int leftDps = (int) ((mouseStats >>> 8) & 0xffL);
        int rightDps = (int) ((mouseStats >>> 16) & 0xffL);
        drawMouseText(canvas, centerX - mouseWidth * 0.25f, top + mouseHeight * 0.42f,
                "L", leftDps, SLOT_MOUSE_L);
        drawMouseText(canvas, centerX + mouseWidth * 0.25f, top + mouseHeight * 0.42f,
                "R", rightDps, SLOT_MOUSE_R);
    }

    private void drawMouseHalf(Canvas canvas, RectF shell, boolean leftHalf, int slot) {
        float mid = shell.centerX();
        float halfCenterX = leftHalf ? (shell.left + mid) * 0.5f : (mid + shell.right) * 0.5f;
        float motion = progress[slot];
        float scale = pressScale(motion);
        boolean pressed = target[slot];

        int save = canvas.save();
        if (leftHalf) canvas.clipRect(shell.left, shell.top, mid, shell.bottom);
        else canvas.clipRect(mid, shell.top, shell.right, shell.bottom);
        canvas.scale(scale, scale, halfCenterX, shell.centerY());

        fillPaint.setColor(withMotionAlpha(pressed ? keyPressedColor : keyIdleColor, motion));
        canvas.drawRoundRect(shell, dp(24), dp(24), fillPaint);
        canvas.restoreToCount(save);
    }

    private void drawMouseText(Canvas canvas, float cx, float cy, String side, int dps, int slot) {
        float motion = progress[slot];
        float scale = pressScale(motion);
        boolean pressed = target[slot];
        int save = canvas.save();
        canvas.scale(scale, scale, cx, cy);

        textPaint.setColor(withMotionAlpha(pressed ? textPressedColor : textIdleColor, motion));
        // L/R 保持 13dp。CPS 使用更小字号。
        textPaint.setTextSize(dp(13));
        textPaint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        canvas.drawText(side, cx, cy - dp(5), textPaint);
        textPaint.setTextSize(dp(8));
        textPaint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        canvas.drawText(dps + " CPS", cx, cy + dp(13), textPaint);
        textPaint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        canvas.restoreToCount(save);
    }

    private void drawKey(Canvas canvas, int slot, String label, float cx, float cy,
                         float width, float height, boolean space) {
        final float motion = progress[slot];
        final float scale = pressScale(motion);
        final float w = width * scale;
        final float h = height * scale;
        final RectF rect = new RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);
        final boolean pressed = target[slot];

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(withMotionAlpha(pressed ? keyPressedColor : keyIdleColor, motion));
        canvas.drawRoundRect(rect, radius, radius, fillPaint);

        if (!pressed) {
            strokePaint.setColor(withMotionAlpha(strokeColor, motion));
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        }

        textPaint.setColor(withMotionAlpha(pressed ? textPressedColor : textIdleColor, motion));
        if (space && showSpaceDps) {
            // Space 始终保持 14dp。
            textPaint.setTextSize(dp(14));
            textPaint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float centerBaseline = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(label, cx, centerBaseline - dp(5), textPaint);

            // 空间不足时只缩小 CPS，不缩小 Space。
            float dpsSize = Math.min(dp(8f), Math.max(dp(6f), height * 0.16f));
            textPaint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            textPaint.setTextSize(dpsSize);
            Paint.FontMetrics dpsFm = textPaint.getFontMetrics();
            float dpsBaseline = rect.bottom - dp(4f) - dpsFm.descent;
            canvas.drawText(spaceDps + " CPS", cx, dpsBaseline, textPaint);
            textPaint.setTypeface(Typeface.create("sans", Typeface.BOLD));
        } else {
            textPaint.setTextSize(space ? dp(14) : dp(17));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(label, cx, baseline, textPaint);
        }
    }

    private boolean updateMotion() {
        long now = SystemClock.uptimeMillis();
        if (lastFrameMs == 0L) lastFrameMs = now;
        float dt = Math.min(0.024f, Math.max(0.001f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;
        boolean active = false;

        if (animationMode != OverlayState.MOTION_NONE) {
            for (int i = 0; i < progress.length; i++) {
                if (advancePressSpring(progress, velocity, target, i, dt)) active = true;
            }
            for (int i = 0; i < customProgress.length; i++) {
                if (advancePressSpring(customProgress, customVelocity, customTarget, i, dt)) active = true;
            }
        }

        active |= advanceScalarSpring(dragProgress, dragVelocity, dragging ? 1f : 0f, dt, 420f, 34f);
        dragProgress = springScratch[0];
        dragVelocity = springScratch[1];

        active |= advanceScalarSpring(revealProgress, revealVelocity, 1f, dt, 360f, 30f);
        revealProgress = springScratch[0];
        revealVelocity = springScratch[1];

        active |= advanceScalarSpring(displayScale, displayScaleVelocity, displayScaleTarget, dt, 260f, 28f);
        displayScale = springScratch[0];
        displayScaleVelocity = springScratch[1];

        active |= advanceScalarSpring(windowProgress, windowVelocity, windowTarget, dt, 320f, 28f);
        windowProgress = springScratch[0];
        windowVelocity = springScratch[1];

        return active;
    }

    private boolean advancePressSpring(float[] values, float[] velocities, boolean[] goals, int index, float dt) {
        float goal = goals[index] ? 1f : 0f;
        float stiffness = goals[index] ? 620f : 390f;
        float damping = goals[index] ? 40f : 30f;

        float x = values[index];
        float v = velocities[index];
        float acceleration = stiffness * (goal - x) - damping * v;
        v += acceleration * dt;
        x += v * dt;

        if (Math.abs(goal - x) < 0.0015f && Math.abs(v) < 0.025f) {
            x = goal;
            v = 0f;
            values[index] = x;
            velocities[index] = v;
            return false;
        }

        values[index] = x;
        velocities[index] = v;
        return true;
    }

    /** 更新弹簧值和速度，不产生每帧分配。 */
    private boolean advanceScalarSpring(float value, float currentVelocity, float targetValue,
                                        float dt, float stiffness, float damping) {
        float acceleration = stiffness * (targetValue - value) - damping * currentVelocity;
        currentVelocity += acceleration * dt;
        value += currentVelocity * dt;

        boolean active = Math.abs(targetValue - value) >= 0.0015f || Math.abs(currentVelocity) >= 0.02f;
        if (!active) {
            value = targetValue;
            currentVelocity = 0f;
        }
        springScratch[0] = value;
        springScratch[1] = currentVelocity;
        return active;
    }

    private float pressScale(float pressProgress) {
        if (animationMode != OverlayState.MOTION_SIZE) return 1f;
        return clamp(1f - 0.055f * pressProgress, 0.935f, 1.012f);
    }

    private int withMotionAlpha(int color, float pressProgress) {
        if (animationMode != OverlayState.MOTION_ALPHA) return color;
        float factor = clamp(0.56f + 0.44f * pressProgress, 0.48f, 1f);
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public int customBaseHeightDp() {
        int rows = Math.max(1, (customKeyCodes.length + customColumns - 1) / customColumns);
        return Math.max(Math.round(CUSTOM_MIN_HEIGHT_DP), rows * Math.round(CUSTOM_ROW_HEIGHT_DP) + 4);
    }

    private float clampScale(float value) {
        return Math.max(0.5f, Math.min(1.5f, value));
    }

    private void setTarget(int slot, boolean value) {
        target[slot] = value;
        if (animationMode == OverlayState.MOTION_NONE) {
            progress[slot] = value ? 1f : 0f;
            velocity[slot] = 0f;
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
