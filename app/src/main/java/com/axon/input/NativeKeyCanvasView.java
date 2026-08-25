package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
    public static final int DISPLAY_TOUCH = 4;

    public static final int TOUCH_MOUSE_LEFT = 1 << 5;
    public static final int TOUCH_MOUSE_RIGHT = 1 << 6;

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
    private final Paint spaceMarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mouseClipPath = new Path();
    private final Path rippleClipPath = new Path();
    private final Typeface typefaceNormal;
    private final Typeface typefaceBold;

    private final int keyIdleColor;
    private final int keyPressedColor;
    private int keyBaseColor;
    private int pressColor;
    private int keyStyle = KeyAppearance.STYLE_ROUNDED;
    private int cornerStrength = KeyAppearance.DEFAULT_CORNER_STRENGTH;
    private final int textIdleColor;
    private final int textPressedColor;
    private int textColorOverride;
    private boolean hasTextColorOverride;
    private int strokeColor;
    private final int shellColor;
    private final int secondaryTextColor;
    private int backgroundOpacityPercent = 100;
    private int strokeOpacityPercent = 100;
    private int textOpacityPercent = 100;
    private int diffusionOpacityPercent = 100;

    private final float[] progress = new float[7];
    private final float[] velocity = new float[7];
    private final boolean[] target = new boolean[7];
    // Diffusion animation follows Matrix Card UI feature-toggle state motion. Every reversal starts
    // from the current rendered reveal, so rapid press/release never jumps to an endpoint.
    private final float[] rippleFill = new float[7];
    private final float[] rippleFrom = new float[7];
    private final float[] rippleTo = new float[7];
    private final long[] rippleAnimStartedAt = new long[7];
    private final long[] rippleAnimDurationMs = new long[7];

    private final int displayType;
    private int[] customKeyCodes = new int[0];
    private String[] customLabels = new String[0];
    private float[] customProgress = new float[0];
    private float[] customVelocity = new float[0];
    private boolean[] customTarget = new boolean[0];
    private float[] customRippleFill = new float[0];
    private float[] customRippleFrom = new float[0];
    private float[] customRippleTo = new float[0];
    private long[] customRippleAnimStartedAt = new long[0];
    private long[] customRippleAnimDurationMs = new long[0];
    private int customColumns = 4;
    private int keyboardSpacingDp = 8;
    private int customSpacingDp = 6;

    private final float keySize;
    private final float gap;
    private final float spaceWidth;
    private final float spaceHeight;
    private final float mouseWidth;
    private final float mouseHeight;

    private int pressedMask;
    private int animationMode = OverlayState.MOTION_SIZE;
    private long mouseStats;
    private boolean showSpace = true;
    private boolean showSpaceDps;
    private boolean showMouseButtons;
    private int keyboardMouseButtons;
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
        if (displayType < DISPLAY_KEYBOARD || displayType > DISPLAY_TOUCH) {
            throw new IllegalArgumentException("Unknown display type: " + displayType);
        }
        this.displayType = displayType;
        typefaceNormal = FontManager.normal(context);
        typefaceBold = FontManager.bold(context);
        setWillNotDraw(false);

        keyIdleColor = UiPalette.overlayKeyIdle(context);
        keyPressedColor = UiPalette.overlayKeyPressed(context);
        keyBaseColor = keyIdleColor;
        pressColor = keyPressedColor;
        textIdleColor = UiPalette.overlayTextIdle(context);
        textPressedColor = UiPalette.overlayTextPressed(context);
        strokeColor = UiPalette.overlayStroke(context);
        shellColor = UiPalette.overlayShell(context);
        secondaryTextColor = UiPalette.overlaySecondary(context);

        keySize = dp(50);
        gap = dp(8);
        spaceWidth = dp(150);
        spaceHeight = dp(44);
        mouseWidth = dp(156);
        mouseHeight = dp(86);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1));
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setStrokeWidth(dp(1));
        dividerPaint.setColor(strokeColor);
        spaceMarkPaint.setStyle(Paint.Style.STROKE);
        spaceMarkPaint.setStrokeWidth(dp(2));
        spaceMarkPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(typefaceBold);
    }

    public int getDisplayType() {
        return displayType;
    }

    public void setKeyAppearance(int style, int color) {
        keyStyle = KeyAppearance.clampStyle(style);
        pressColor = 0xff000000 | (color & 0x00ffffff);
        postInvalidateOnAnimation();
    }

    public void setKeyBaseColor(int color) {
        int resolved = 0xff000000 | (color & 0x00ffffff);
        if (keyBaseColor == resolved) return;
        keyBaseColor = resolved;
        postInvalidateOnAnimation();
    }

    public void setKeyBorderColor(int color) {
        if (strokeColor == color) return;
        strokeColor = color;
        dividerPaint.setColor(strokeColor);
        postInvalidateOnAnimation();
    }

    public void setCornerStrength(int strength) {
        int resolved = KeyAppearance.clampCornerStrength(strength);
        if (cornerStrength == resolved) return;
        cornerStrength = resolved;
        postInvalidateOnAnimation();
    }

    public void setLayerOpacities(int backgroundPercent, int strokePercent, int textPercent) {
        int background = clampPercent(backgroundPercent);
        int stroke = clampPercent(strokePercent);
        int text = clampPercent(textPercent);
        if (backgroundOpacityPercent == background
                && strokeOpacityPercent == stroke
                && textOpacityPercent == text) return;
        backgroundOpacityPercent = background;
        strokeOpacityPercent = stroke;
        textOpacityPercent = text;
        postInvalidateOnAnimation();
    }

    public void setDiffusionOpacity(int percent) {
        int resolved = clampPercent(percent);
        if (diffusionOpacityPercent == resolved) return;
        diffusionOpacityPercent = resolved;
        postInvalidateOnAnimation();
    }

    public void setTextColor(int color) {
        textColorOverride = 0xff000000 | (color & 0x00ffffff);
        hasTextColorOverride = true;
        postInvalidateOnAnimation();
    }

    private int resolveAnimatedTextColor(boolean pressed, float centreFill) {
        if ((displayType == DISPLAY_KEYBOARD || displayType == DISPLAY_TOUCH) && hasTextColorOverride) return textColorOverride;
        int pressedColor = KeyAppearance.pressedTextColor(pressColor);
        if (animationMode != OverlayState.MOTION_RIPPLE) return pressed ? pressedColor : textIdleColor;
        return KeyAppearance.blendColor(textIdleColor, pressedColor,
                KeyAppearance.centreTextMix(centreFill));
    }

    public void setKeySpacing(int spacingDp) {
        int value = Math.max(0, Math.min(16, spacingDp));
        if (displayType == DISPLAY_KEYBOARD || displayType == DISPLAY_TOUCH) {
            if (keyboardSpacingDp == value) return;
            keyboardSpacingDp = value;
        } else if (displayType == DISPLAY_CUSTOM) {
            if (customSpacingDp == value) return;
            customSpacingDp = value;
        } else {
            return;
        }
        postInvalidateOnAnimation();
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
        // Switching motion modes must never leave a stale centre-fill frame behind.
        resetRippleState();
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
        float[] oldRippleFill = customRippleFill;
        float[] oldRippleFrom = customRippleFrom;
        float[] oldRippleTo = customRippleTo;
        long[] oldRippleAnimStartedAt = customRippleAnimStartedAt;
        long[] oldRippleAnimDurationMs = customRippleAnimDurationMs;

        customRippleFill = new float[keyCodes.length];
        customRippleFrom = new float[keyCodes.length];
        customRippleTo = new float[keyCodes.length];
        customRippleAnimStartedAt = new long[keyCodes.length];
        customRippleAnimDurationMs = new long[keyCodes.length];

        for (int i = 0; i < keyCodes.length; i++) {
            customLabels[i] = InputBinding.label(keyCodes[i]);
            for (int j = 0; j < oldCodes.length; j++) {
                if (oldCodes[j] == keyCodes[i]) {
                    customProgress[i] = oldProgress[j];
                    customVelocity[i] = oldVelocity.length > j ? oldVelocity[j] : 0f;
                    customTarget[i] = oldTarget[j];
                    customRippleFill[i] = oldRippleFill.length > j ? oldRippleFill[j] : 0f;
                    customRippleFrom[i] = oldRippleFrom.length > j ? oldRippleFrom[j] : customRippleFill[i];
                    customRippleTo[i] = oldRippleTo.length > j ? oldRippleTo[j] : (oldTarget[j] ? 1f : 0f);
                    customRippleAnimStartedAt[i] = oldRippleAnimStartedAt.length > j ? oldRippleAnimStartedAt[j] : 0L;
                    customRippleAnimDurationMs[i] = oldRippleAnimDurationMs.length > j ? oldRippleAnimDurationMs[j] : 0L;
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

    public void setMouseButtonsVisible(boolean visible) {
        if (displayType != DISPLAY_KEYBOARD && displayType != DISPLAY_TOUCH) return;
        if (showMouseButtons == visible) return;
        showMouseButtons = visible;
        if (!visible) {
            setTarget(SLOT_MOUSE_L, false);
            setTarget(SLOT_MOUSE_R, false);
        } else if (displayType == DISPLAY_KEYBOARD) {
            setTarget(SLOT_MOUSE_L, (keyboardMouseButtons & 1) != 0);
            setTarget(SLOT_MOUSE_R, (keyboardMouseButtons & 2) != 0);
        } else {
            setTarget(SLOT_MOUSE_L, (pressedMask & TOUCH_MOUSE_LEFT) != 0);
            setTarget(SLOT_MOUSE_R, (pressedMask & TOUCH_MOUSE_RIGHT) != 0);
        }
        postInvalidateOnAnimation();
    }

    public void setKeyboardMouseButtons(int buttons) {
        if (displayType != DISPLAY_KEYBOARD) return;
        int next = buttons & 0x3;
        if (keyboardMouseButtons == next) return;
        keyboardMouseButtons = next;
        if (showMouseButtons) {
            setTarget(SLOT_MOUSE_L, (next & 1) != 0);
            setTarget(SLOT_MOUSE_R, (next & 2) != 0);
            postInvalidateOnAnimation();
        }
    }

    public void setKeyboardDps(int dps) {
        if (displayType != DISPLAY_KEYBOARD) return;
        int next = Math.max(0, Math.min(999, dps));
        if (spaceDps == next) return;
        spaceDps = next;
        postInvalidateOnAnimation();
    }

    public void setPressedMask(int newMask) {
        if ((displayType != DISPLAY_KEYBOARD && displayType != DISPLAY_TOUCH) || pressedMask == newMask) return;
        pressedMask = newMask;
        setTarget(SLOT_W, (newMask & NativeKeyEngine.W) != 0);
        setTarget(SLOT_A, (newMask & NativeKeyEngine.A) != 0);
        setTarget(SLOT_S, (newMask & NativeKeyEngine.S) != 0);
        setTarget(SLOT_D, (newMask & NativeKeyEngine.D) != 0);
        setTarget(SLOT_SPACE, (newMask & NativeKeyEngine.SPACE) != 0);
        if (displayType == DISPLAY_TOUCH && showMouseButtons) {
            setTarget(SLOT_MOUSE_L, (newMask & TOUCH_MOUSE_LEFT) != 0);
            setTarget(SLOT_MOUSE_R, (newMask & TOUCH_MOUSE_RIGHT) != 0);
        }
        postInvalidateOnAnimation();
    }

    public void setCustomKeyPressed(int keyCode, boolean pressed) {
        if (displayType != DISPLAY_CUSTOM) return;
        for (int i = 0; i < customKeyCodes.length; i++) {
            if (customKeyCodes[i] == keyCode) {
                if (customTarget[i] != pressed) {
                    long now = SystemClock.uptimeMillis();
                    if (animationMode == OverlayState.MOTION_RIPPLE) {
                        if (pressed) startCustomCentreFill(i, now);
                        else releaseCustomCentreFill(i, now);
                    }
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
                if (animationMode == OverlayState.MOTION_RIPPLE) {
                    releaseCustomCentreFill(i, SystemClock.uptimeMillis());
                }
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
        if (displayType == DISPLAY_KEYBOARD) {
            setPressedMask(0);
            setKeyboardMouseButtons(0);
        } else if (displayType == DISPLAY_TOUCH) {
            setPressedMask(0);
        } else if (displayType == DISPLAY_MOUSE) setMouseStats(0L);
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
        long now = SystemClock.uptimeMillis();
        if (animationMode == OverlayState.MOTION_RIPPLE && hasActiveCentreFill()) animating = true;

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
        } else if (displayType == DISPLAY_TOUCH) {
            drawTouchKeyboard(canvas, centerX, top);
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
        final float rowStep = keySize + dp(keyboardSpacingDp);
        drawKey(canvas, SLOT_W, "W", centerX, top + keySize * 0.5f, keySize, keySize, false);

        final float secondY = top + rowStep + keySize * 0.5f;
        drawKey(canvas, SLOT_A, "A", centerX - rowStep, secondY, keySize, keySize, false);
        drawKey(canvas, SLOT_S, "S", centerX, secondY, keySize, keySize, false);
        drawKey(canvas, SLOT_D, "D", centerX + rowStep, secondY, keySize, keySize, false);

        float nextTop = top + rowStep * 2f;
        if (showMouseButtons) {
            drawMouseButtonRow(canvas, centerX, nextTop);
            nextTop += spaceHeight + dp(keyboardSpacingDp);
        }
        if (showSpace) {
            final float spaceY = nextTop + spaceHeight * 0.5f;
            drawKey(canvas, SLOT_SPACE, "Space", centerX, spaceY, spaceWidth, spaceHeight, true);
        }
    }

    private void drawTouchKeyboard(Canvas canvas, float centerX, float top) {
        final float rowStep = keySize + dp(keyboardSpacingDp);
        drawKey(canvas, SLOT_W, "W", centerX, top + keySize * 0.5f, keySize, keySize, false);

        final float secondY = top + rowStep + keySize * 0.5f;
        drawKey(canvas, SLOT_A, "A", centerX - rowStep, secondY, keySize, keySize, false);
        drawKey(canvas, SLOT_S, "S", centerX, secondY, keySize, keySize, false);
        drawKey(canvas, SLOT_D, "D", centerX + rowStep, secondY, keySize, keySize, false);

        float nextTop = top + rowStep * 2f;
        if (showMouseButtons) {
            drawMouseButtonRow(canvas, centerX, nextTop);
            nextTop += spaceHeight + dp(keyboardSpacingDp);
        }
        final float spaceY = nextTop + spaceHeight * 0.5f;
        // 触屏显示底部空格保持纯长条，避免额外文字抢夺视觉焦点。
        drawKey(canvas, SLOT_SPACE, "", centerX, spaceY, spaceWidth, spaceHeight, true);
    }

    private void drawMouseButtonRow(Canvas canvas, float centerX, float rowTop) {
        final float buttonWidth = (spaceWidth - dp(keyboardSpacingDp)) * 0.5f;
        final float buttonY = rowTop + spaceHeight * 0.5f;
        final float buttonOffset = (buttonWidth + dp(keyboardSpacingDp)) * 0.5f;
        drawKey(canvas, SLOT_MOUSE_L, "LMB", centerX - buttonOffset, buttonY,
                buttonWidth, spaceHeight, false);
        drawKey(canvas, SLOT_MOUSE_R, "RMB", centerX + buttonOffset, buttonY,
                buttonWidth, spaceHeight, false);
    }

    private void drawCustomKeys(Canvas canvas, float top) {
        float contentScale = 0.92f + 0.08f * clamp(revealProgress, 0f, 1.08f);
        int save = canvas.save();
        canvas.scale(contentScale, contentScale, getWidth() * 0.5f, top + dp(customBaseHeightDp()) * 0.5f);

        if (customKeyCodes.length == 0) {
            textPaint.setTypeface(typefaceNormal);
            textPaint.setTextSize(dp(13));
            textPaint.setColor(withLayerAlpha(secondaryTextColor, textOpacityPercent));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = top + dp(customBaseHeightDp()) * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(getContext().getString(R.string.no_custom_keys), getWidth() * 0.5f, baseline, textPaint);
            textPaint.setTypeface(typefaceBold);
            canvas.restoreToCount(save);
            return;
        }

        float horizontalPadding = dp(10);
        float cellGap = dp(customSpacingDp);
        float baseWidth = dp(280);
        float available = baseWidth - horizontalPadding * 2f;
        float contentLeft = getWidth() * 0.5f - baseWidth * 0.5f + horizontalPadding;
        float cellWidth = (available - cellGap * (customColumns - 1)) / customColumns;
        float cellHeight = dp(42);
        float rowStep = dp(44 + customSpacingDp);

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
        float centreFill = animationMode == OverlayState.MOTION_RIPPLE
                ? clamp(customRippleFill[index], 0f, 1f) : 0f;
        float scale = pressScale(motion);
        if (animationMode == OverlayState.MOTION_RIPPLE) {
            scale *= KeyAppearance.cardFeatureBounce(centreFill);
        }
        float w = width * scale;
        float h = height * scale;
        RectF rect = new RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);
        boolean pressed = customTarget[index];

        fillPaint.setStyle(Paint.Style.FILL);
        // In centre-fill mode the base never jumps to pressColor. The press colour exists only
        // inside the centred Matrix-style state surface.
        int baseColor = animationMode == OverlayState.MOTION_RIPPLE
                ? keyBaseColor : (pressed ? pressColor : keyBaseColor);
        fillPaint.setColor(withMotionAlpha(baseColor, motion, backgroundOpacityPercent));
        float keyRadius = KeyAppearance.roundedRadius(rect, cornerStrength);
        KeyAppearance.drawShape(canvas, rect, keyStyle, keyRadius, fillPaint);
        if (animationMode == OverlayState.MOTION_RIPPLE) {
            KeyAppearance.drawCentreFill(canvas, rect, keyStyle, keyRadius,
                    withLayerAlpha(pressColor, diffusionOpacityPercent),
                    centreFill, ripplePaint, rippleClipPath);
        }

        if (animationMode == OverlayState.MOTION_RIPPLE || !pressed) {
            strokePaint.setColor(withMotionAlpha(strokeColor, motion, strokeOpacityPercent));
            KeyAppearance.drawShape(canvas, rect, keyStyle, keyRadius, strokePaint);
        }

        textPaint.setColor(withMotionAlpha(
                resolveAnimatedTextColor(pressed, centreFill), motion, textOpacityPercent));
        float textSize = width < dp(36) ? 10f : width < dp(52) ? 12f : 14f;
        textPaint.setTextSize(dp(textSize));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(label, cx, baseline, textPaint);
    }

    private void drawMouse(Canvas canvas, float centerX, float top) {
        float left = centerX - mouseWidth * 0.5f;
        float right = centerX + mouseWidth * 0.5f;
        float bottom = top + mouseHeight;
        float middle = centerX;
        float cy = top + mouseHeight * 0.5f;
        RectF outer = new RectF(left, top, right, bottom);
        RectF leftArea = new RectF(left, top, middle, bottom);
        RectF rightArea = new RectF(middle, top, right, bottom);
        float outerRadius = mouseOuterRadius(outer);
        int leftCps = (int) ((mouseStats >>> 8) & 0xffL);
        int rightCps = (int) ((mouseStats >>> 16) & 0xffL);

        // 左右按键共用一个外框。中间没有间距和圆角。
        drawMouseHalf(canvas, outer, leftArea, SLOT_MOUSE_L, "L", leftCps, outerRadius, cy);
        drawMouseHalf(canvas, outer, rightArea, SLOT_MOUSE_R, "R", rightCps, outerRadius, cy);

        strokePaint.setColor(withLayerAlpha(strokeColor, strokeOpacityPercent));
        drawMouseOuterShape(canvas, outer, outerRadius, strokePaint);
        dividerPaint.setColor(withLayerAlpha(strokeColor, strokeOpacityPercent));
        canvas.drawLine(middle, top + dp(1), middle, bottom - dp(1), dividerPaint);
    }

    private void drawMouseHalf(Canvas canvas, RectF outer, RectF area, int slot,
                               String label, int cps, float outerRadius, float cy) {
        float motion = progress[slot];
        boolean pressed = target[slot];
        int save = canvas.save();
        clipMouseOuterShape(canvas, outer, outerRadius);
        canvas.clipRect(area);

        float centreFill = animationMode == OverlayState.MOTION_RIPPLE
                ? clamp(rippleFill[slot], 0f, 1f) : 0f;
        fillPaint.setStyle(Paint.Style.FILL);
        int baseColor = animationMode == OverlayState.MOTION_RIPPLE
                ? keyBaseColor : (pressed ? pressColor : keyBaseColor);
        fillPaint.setColor(withMotionAlpha(baseColor, motion, backgroundOpacityPercent));
        canvas.drawRect(area, fillPaint);
        if (animationMode == OverlayState.MOTION_RIPPLE && centreFill > 0f) {
            KeyAppearance.drawCentreFill(canvas, area, KeyAppearance.STYLE_SQUARE, 0f,
                    withLayerAlpha(pressColor, diffusionOpacityPercent),
                    centreFill, ripplePaint, rippleClipPath);
        }
        canvas.restoreToCount(save);

        float cx = area.centerX();
        textPaint.setColor(withMotionAlpha(
                resolveAnimatedTextColor(pressed, centreFill), motion, textOpacityPercent));
        textPaint.setTextSize(dp(13));
        textPaint.setTypeface(typefaceBold);
        canvas.drawText(label, cx, cy - dp(5), textPaint);
        textPaint.setTextSize(dp(8));
        textPaint.setTypeface(typefaceNormal);
        canvas.drawText(cps + " CPS", cx, cy + dp(13), textPaint);
        textPaint.setTypeface(typefaceBold);
    }

    private float mouseOuterRadius(RectF area) {
        if (keyStyle == KeyAppearance.STYLE_SQUARE) return 0f;
        if (keyStyle == KeyAppearance.STYLE_CIRCLE) return Math.min(area.width(), area.height()) * 0.5f;
        return KeyAppearance.roundedRadius(area, cornerStrength);
    }

    private void drawMouseOuterShape(Canvas canvas, RectF area, float outerRadius, Paint paint) {
        if (outerRadius <= 0f) canvas.drawRect(area, paint);
        else canvas.drawRoundRect(area, outerRadius, outerRadius, paint);
    }

    private void clipMouseOuterShape(Canvas canvas, RectF area, float outerRadius) {
        if (outerRadius <= 0f) {
            canvas.clipRect(area);
            return;
        }
        mouseClipPath.reset();
        mouseClipPath.addRoundRect(area, outerRadius, outerRadius, Path.Direction.CW);
        canvas.clipPath(mouseClipPath);
    }

    private void drawKey(Canvas canvas, int slot, String label, float cx, float cy,
                         float width, float height, boolean space) {
        final float motion = progress[slot];
        float centreFill = animationMode == OverlayState.MOTION_RIPPLE
                ? clamp(rippleFill[slot], 0f, 1f) : 0f;
        float scale = pressScale(motion);
        if (animationMode == OverlayState.MOTION_RIPPLE) {
            scale *= KeyAppearance.cardFeatureBounce(centreFill);
        }
        final float w = width * scale;
        final float h = height * scale;
        final RectF rect = new RectF(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f);
        final boolean pressed = target[slot];

        fillPaint.setStyle(Paint.Style.FILL);
        int baseColor = animationMode == OverlayState.MOTION_RIPPLE
                ? keyBaseColor : (pressed ? pressColor : keyBaseColor);
        fillPaint.setColor(withMotionAlpha(baseColor, motion, backgroundOpacityPercent));
        float keyRadius = KeyAppearance.roundedRadius(rect, cornerStrength);
        KeyAppearance.drawShape(canvas, rect, keyStyle, keyRadius, fillPaint);
        if (animationMode == OverlayState.MOTION_RIPPLE) {
            KeyAppearance.drawCentreFill(canvas, rect, keyStyle, keyRadius,
                    withLayerAlpha(pressColor, diffusionOpacityPercent),
                    centreFill, ripplePaint, rippleClipPath);
        }

        if (animationMode == OverlayState.MOTION_RIPPLE || !pressed) {
            strokePaint.setColor(withMotionAlpha(strokeColor, motion, strokeOpacityPercent));
            KeyAppearance.drawShape(canvas, rect, keyStyle, keyRadius, strokePaint);
        }

        textPaint.setColor(withMotionAlpha(
                resolveAnimatedTextColor(pressed, centreFill), motion, textOpacityPercent));
        if (space && showSpaceDps) {
            // Space 始终保持 14dp。
            textPaint.setTextSize(dp(14));
            textPaint.setTypeface(typefaceBold);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float centerBaseline = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(label, cx, centerBaseline - dp(5), textPaint);

            // 空间不足时只缩小 CPS，不缩小 Space。
            float dpsSize = Math.min(dp(8f), Math.max(dp(6f), height * 0.16f));
            textPaint.setTypeface(typefaceNormal);
            textPaint.setTextSize(dpsSize);
            Paint.FontMetrics dpsFm = textPaint.getFontMetrics();
            float dpsBaseline = rect.bottom - dp(4f) - dpsFm.descent;
            canvas.drawText(spaceDps + " CPS", cx, dpsBaseline, textPaint);
            textPaint.setTypeface(typefaceBold);
        } else {
            // Touch-display Space intentionally uses a graphic mark instead of text.
            // The old implementation passed an empty label, so the key body rendered but
            // the expected centre dash could never appear. Drawing the mark directly also
            // avoids font/glyph compatibility problems on different Android builds.
            if (displayType == DISPLAY_TOUCH && slot == SLOT_SPACE && label.isEmpty()) {
                float markHalf = Math.min(rect.width() * 0.19f, dp(27));
                spaceMarkPaint.setStrokeWidth(Math.max(dp(1.5f), rect.height() * 0.045f));
                spaceMarkPaint.setColor(withMotionAlpha(
                        resolveAnimatedTextColor(pressed, centreFill), motion, textOpacityPercent));
                canvas.drawLine(cx - markHalf, cy, cx + markHalf, cy, spaceMarkPaint);
            } else {
                textPaint.setTextSize(space ? dp(14) : dp(17));
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(label, cx, baseline, textPaint);
            }
        }
    }

    private boolean updateMotion() {
        long now = SystemClock.uptimeMillis();
        if (lastFrameMs == 0L) lastFrameMs = now;
        float dt = Math.min(0.024f, Math.max(0.001f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;
        boolean active = false;

        if (animationMode == OverlayState.MOTION_RIPPLE) {
            active |= advanceCentreFill(now);
        } else if (animationMode != OverlayState.MOTION_NONE) {
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

    private int withMotionAlpha(int color, float pressProgress, int layerOpacityPercent) {
        float factor = animationMode == OverlayState.MOTION_ALPHA
                ? clamp(0.56f + 0.44f * pressProgress, 0.48f, 1f) : 1f;
        int alpha = Math.round(Color.alpha(color) * factor * clampPercent(layerOpacityPercent) / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int withLayerAlpha(int color, int opacityPercent) {
        int alpha = Math.round(Color.alpha(color) * clampPercent(opacityPercent) / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public int customBaseHeightDp() {
        int rows = Math.max(1, (customKeyCodes.length + customColumns - 1) / customColumns);
        return Math.max(Math.round(CUSTOM_MIN_HEIGHT_DP), rows * (44 + customSpacingDp) + 4);
    }

    private float clampScale(float value) {
        return Math.max(0.5f, Math.min(1.5f, value));
    }

    private void setTarget(int slot, boolean value) {
        if (target[slot] != value && animationMode == OverlayState.MOTION_RIPPLE) {
            long now = SystemClock.uptimeMillis();
            if (value) startCentreFill(slot, now);
            else releaseCentreFill(slot, now);
        }
        target[slot] = value;
        if (animationMode == OverlayState.MOTION_NONE) {
            progress[slot] = value ? 1f : 0f;
            velocity[slot] = 0f;
        }
    }

    private void startCentreFill(int slot, long now) {
        startRippleTransition(rippleFill, rippleFrom, rippleTo, rippleAnimStartedAt,
                rippleAnimDurationMs, slot, true, now);
    }

    private void releaseCentreFill(int slot, long now) {
        startRippleTransition(rippleFill, rippleFrom, rippleTo, rippleAnimStartedAt,
                rippleAnimDurationMs, slot, false, now);
    }

    private void startCustomCentreFill(int index, long now) {
        startRippleTransition(customRippleFill, customRippleFrom, customRippleTo,
                customRippleAnimStartedAt, customRippleAnimDurationMs, index, true, now);
    }

    private void releaseCustomCentreFill(int index, long now) {
        startRippleTransition(customRippleFill, customRippleFrom, customRippleTo,
                customRippleAnimStartedAt, customRippleAnimDurationMs, index, false, now);
    }

    private void startRippleTransition(float[] values, float[] fromValues, float[] toValues,
                                       long[] startedAt, long[] durationMs, int index,
                                       boolean enabledNow, long now) {
        if (index < 0 || index >= values.length) return;
        float current = evaluateRipple(values, fromValues, toValues, startedAt, durationMs, index, now);
        float end = enabledNow ? 1f : 0f;
        values[index] = current;
        fromValues[index] = current;
        toValues[index] = end;
        long duration = KeyAppearance.cardFeatureToggleDuration(current, end);
        if (duration <= 0L) {
            values[index] = end;
            fromValues[index] = end;
            startedAt[index] = 0L;
            durationMs[index] = 0L;
            return;
        }
        startedAt[index] = now;
        durationMs[index] = duration;
    }

    private float evaluateRipple(float[] values, float[] fromValues, float[] toValues,
                                 long[] startedAt, long[] durationMs, int index, long now) {
        long start = startedAt[index];
        long duration = durationMs[index];
        if (start <= 0L || duration <= 0L) return clamp(values[index], 0f, 1f);
        float linear = (now - start) / (float) duration;
        if (linear >= 1f) {
            float end = clamp(toValues[index], 0f, 1f);
            values[index] = end;
            fromValues[index] = end;
            startedAt[index] = 0L;
            durationMs[index] = 0L;
            return end;
        }
        if (linear <= 0f) return clamp(fromValues[index], 0f, 1f);
        float eased = KeyAppearance.cardFeatureToggleEase(linear);
        float value = fromValues[index] + (toValues[index] - fromValues[index]) * eased;
        values[index] = clamp(value, 0f, 1f);
        return values[index];
    }

    private boolean advanceCentreFill(long now) {
        boolean active = false;
        for (int i = 0; i < rippleFill.length; i++) {
            if (rippleAnimStartedAt[i] <= 0L) continue;
            evaluateRipple(rippleFill, rippleFrom, rippleTo, rippleAnimStartedAt,
                    rippleAnimDurationMs, i, now);
            if (rippleAnimStartedAt[i] > 0L) active = true;
        }
        for (int i = 0; i < customRippleFill.length; i++) {
            if (customRippleAnimStartedAt[i] <= 0L) continue;
            evaluateRipple(customRippleFill, customRippleFrom, customRippleTo,
                    customRippleAnimStartedAt, customRippleAnimDurationMs, i, now);
            if (customRippleAnimStartedAt[i] > 0L) active = true;
        }
        return active;
    }

    private boolean hasActiveCentreFill() {
        for (long start : rippleAnimStartedAt) if (start > 0L) return true;
        for (long start : customRippleAnimStartedAt) if (start > 0L) return true;
        return false;
    }

    private void resetRippleState() {
        boolean syncPressedState = animationMode == OverlayState.MOTION_RIPPLE;
        for (int i = 0; i < rippleFill.length; i++) {
            float value = syncPressedState && target[i] ? 1f : 0f;
            rippleFill[i] = value;
            rippleFrom[i] = value;
            rippleTo[i] = value;
            rippleAnimStartedAt[i] = 0L;
            rippleAnimDurationMs[i] = 0L;
        }
        for (int i = 0; i < customRippleFill.length; i++) {
            float value = syncPressedState && customTarget[i] ? 1f : 0f;
            customRippleFill[i] = value;
            customRippleFrom[i] = value;
            customRippleTo[i] = value;
            customRippleAnimStartedAt[i] = 0L;
            customRippleAnimDurationMs[i] = 0L;
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
