package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** 手柄 HUD。各区域使用独立悬浮窗口，只负责绘制和动画。 */
public final class GamepadOverlayView extends FrameLayout {
    public static final int DISPLAY_LEFT_STICK = 10;
    public static final int DISPLAY_RIGHT_STICK = 11;
    public static final int DISPLAY_FACE = 12;
    public static final int DISPLAY_LEFT_SHOULDER = 13;
    public static final int DISPLAY_RIGHT_SHOULDER = 14;
    public static final int DISPLAY_BACK = 15;
    public static final int DISPLAY_DPAD = 16;

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
    public static final int BTN_SELECT = 1 << 10;
    public static final int BTN_START = 1 << 11;
    public static final int BTN_MODE = 1 << 12;
    public static final int BTN_L3 = 1 << 13;
    public static final int BTN_R3 = 1 << 14;
    public static final int BTN_BACK_1 = 1 << 15;
    public static final int BTN_BACK_2 = 1 << 16;
    public static final int BTN_BACK_3 = 1 << 17;
    public static final int BTN_BACK_4 = 1 << 18;
    // D-pad is also bindable input. Keep these bits outside the rendered button ranges.
    public static final int BTN_DPAD_UP = 1 << 20;
    public static final int BTN_DPAD_DOWN = 1 << 21;
    public static final int BTN_DPAD_LEFT = 1 << 22;
    public static final int BTN_DPAD_RIGHT = 1 << 23;

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
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF auxRect = new RectF();
    private final Path motionClipPath = new Path();
    private final Typeface typefaceNormal;
    private final Typeface typefaceBold;

    private DragListener dragListener;
    private boolean dragEnabled;
    private boolean dragging;
    private boolean faceReversed;
    private boolean faceSymbolIcons;
    private boolean vader5BackLabels;
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
    private int faceSpacingDp = 8;
    private int stickDotSizePercent = 100;
    private int stickCenterCornerStrength = 100;
    private int stickCenterColor;
    private GlobalHtmlWebView htmlView;
    private boolean globalHtmlEnabled;
    private int keyStyle = KeyAppearance.STYLE_ROUNDED;
    private int cornerStrength = KeyAppearance.DEFAULT_CORNER_STRENGTH;
    private int baseColor;
    private int borderColor;
    private int pressColor;
    private int textColor;
    private int backgroundOpacityPercent = 100;
    private int strokeOpacityPercent = 100;
    private int textOpacityPercent = 100;
    private int diffusionOpacityPercent = 100;
    private int animationMode = OverlayState.MOTION_SIZE;

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
    private float stickPressProgress;
    private float stickPressVelocity;

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
        typefaceNormal = FontManager.normal(context);
        typefaceBold = FontManager.bold(context);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        fillPaint.setStyle(Paint.Style.FILL);
        baseColor = UiPalette.overlayShell(context);
        fillPaint.setColor(baseColor);
        borderColor = UiPalette.overlayStroke(context);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setColor(borderColor);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(typefaceBold);
        textColor = UiPalette.overlayTextIdle(context);
        textPaint.setColor(textColor);
        accentPaint.setStyle(Paint.Style.FILL);
        buttonPaint.setStyle(Paint.Style.FILL);
        pressColor = UiPalette.overlayKeyPressed(context);
        stickCenterColor = pressColor;
        accentPaint.setColor(stickCenterColor);
        buttonPaint.setColor(pressColor);

        setScaleX(0.97f);
        setScaleY(0.97f);
        postFrame();
    }

    public int getDisplayType() { return displayType; }

    public void setDragListener(DragListener listener) { dragListener = listener; }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled && dragging) finishDrag();
    }

    public void setDisplaySize(int percent) {
        displaySizePercent = Math.max(25, Math.min(300, percent));
        if (htmlView != null) htmlView.setDisplaySize(displaySizePercent);
    }

    public void setFaceSpacing(int spacingDp) {
        faceSpacingDp = Math.max(0, Math.min(40, spacingDp));
        invalidate();
    }

    public void setKeyAppearance(int style, int color) {
        // Shape presets are retired; all built-in surfaces use cornerStrength.
        keyStyle = KeyAppearance.STYLE_ROUNDED;
        pressColor = 0xff000000 | (color & 0x00ffffff);
        buttonPaint.setColor(withOpacity(pressColor, diffusionOpacityPercent));
        accentPaint.setColor(withOpacity(pressColor, diffusionOpacityPercent));
        invalidate();
    }

    public void setKeyBaseColor(int color) {
        int resolved = 0xff000000 | (color & 0x00ffffff);
        if (baseColor == resolved) return;
        baseColor = resolved;
        fillPaint.setColor(withOpacity(baseColor, backgroundOpacityPercent));
        invalidate();
    }

    public void setKeyBorderColor(int color) {
        if (borderColor == color) return;
        borderColor = color;
        strokePaint.setColor(withOpacity(borderColor, strokeOpacityPercent));
        invalidate();
    }

    public void setTextColor(int color) {
        int resolved = 0xff000000 | (color & 0x00ffffff);
        if (textColor == resolved) return;
        textColor = resolved;
        textPaint.setColor(withOpacity(textColor, textOpacityPercent));
        invalidate();
    }

    public void setLayerOpacities(int backgroundPercent, int strokePercent, int textPercent) {
        backgroundOpacityPercent = clampPercent(backgroundPercent);
        strokeOpacityPercent = clampPercent(strokePercent);
        textOpacityPercent = clampPercent(textPercent);
        fillPaint.setColor(withOpacity(baseColor, backgroundOpacityPercent));
        strokePaint.setColor(withOpacity(borderColor, strokeOpacityPercent));
        textPaint.setColor(withOpacity(textColor, textOpacityPercent));
        invalidate();
    }

    public void setDiffusionOpacity(int percent) {
        diffusionOpacityPercent = clampPercent(percent);
        buttonPaint.setColor(withOpacity(pressColor, diffusionOpacityPercent));
        accentPaint.setColor(withOpacity(pressColor, diffusionOpacityPercent));
        invalidate();
    }

    public void setCornerStrength(int strength) {
        int resolved = KeyAppearance.clampCornerStrength(strength);
        if (cornerStrength == resolved) return;
        cornerStrength = resolved;
        invalidate();
    }

    public void setStickDotSize(int percent) {
        stickDotSizePercent = Math.max(25, Math.min(300, percent));
        if (htmlView != null) htmlView.setDotSizePercent(stickDotSizePercent);
        invalidate();
    }

    public void setStickCenterCornerStrength(int strength) {
        int resolved = KeyAppearance.clampCornerStrength(strength);
        if (stickCenterCornerStrength == resolved) return;
        stickCenterCornerStrength = resolved;
        invalidate();
    }

    public void setStickCenterColor(int color) {
        int resolved = 0xff000000 | (color & 0x00ffffff);
        if (stickCenterColor == resolved) return;
        stickCenterColor = resolved;
        invalidate();
    }

    public void setAnimationMode(int mode) {
        int resolved = OverlayState.clampMotionMode(mode);
        if (animationMode == resolved) return;
        animationMode = resolved;
        if (animationMode == OverlayState.MOTION_NONE) {
            stickPressProgress = stickPressDown ? 1f : 0f;
            stickPressVelocity = 0f;
            for (int i = 0; i < press.length; i++) {
                press[i] = pressTargets[i] ? 0.86f : 1f;
                pressVelocity[i] = 0f;
            }
        }
        postFrame();
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
        web.setFaceReversed(faceReversed);
        web.setFaceSymbolIcons(faceSymbolIcons);
        web.setFaceDpsConfig(faceYDpsEnabled, faceXDpsEnabled, faceBDpsEnabled, faceADpsEnabled);
        web.setShoulderConfig(triggerProgressEnabled, shoulderDpsEnabled);
        web.setGamepadDpsStats(faceYDps, faceXDps, faceBDps, faceADps, l1Dps, r1Dps);
        web.setGamepadState(gamepadLxValue(), gamepadLyValue(), gamepadRxValue(), gamepadRyValue(),
                Math.round(targetLt * 1000f), Math.round(targetRt * 1000f), buttons);
        invalidate();
    }

    public void setFaceReversed(boolean reversed) {
        if (faceReversed == reversed) return;
        faceReversed = reversed;
        if (htmlView != null) htmlView.setFaceReversed(faceReversed);
        invalidate();
    }

    public void setFaceSymbolIcons(boolean enabled) {
        if (faceSymbolIcons == enabled) return;
        faceSymbolIcons = enabled;
        if (htmlView != null) htmlView.setFaceSymbolIcons(faceSymbolIcons);
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
        stickPressProgress = 0f;
        stickPressVelocity = 0f;
        for (int i = 0; i < 4; i++) {
            press[i] = 1f;
            pressVelocity[i] = 0f;
            pressTargets[i] = false;
        }
        if (htmlView != null) htmlView.setGamepadState(0, 0, 0, 0, 0, 0, 0);
        invalidate();
    }

    /** 状态范围：摇杆 -1000..1000，扳机 0..1000，按键使用 Linux 位状态。 */
    public void setGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttonMask) {
        boolean digitalChanged = buttons != buttonMask;
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
            if (stickPressDown != pressed) {
                stickPressDown = pressed;
                if (animationMode == OverlayState.MOTION_NONE) {
                    stickPressProgress = pressed ? 1f : 0f;
                    stickPressVelocity = 0f;
                }
            }
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
        } else if (displayType == DISPLAY_BACK) {
            pressTargets[0] = (buttons & BTN_BACK_1) != 0;
            pressTargets[1] = (buttons & BTN_BACK_2) != 0;
            pressTargets[2] = (buttons & BTN_BACK_3) != 0;
            pressTargets[3] = (buttons & BTN_BACK_4) != 0;
        } else if (displayType == DISPLAY_DPAD) {
            // 顺时针：上、右、下、左。复用现有四按键弹性反馈。
            pressTargets[0] = (buttons & BTN_DPAD_UP) != 0;
            pressTargets[1] = (buttons & BTN_DPAD_RIGHT) != 0;
            pressTargets[2] = (buttons & BTN_DPAD_DOWN) != 0;
            pressTargets[3] = (buttons & BTN_DPAD_LEFT) != 0;
        }
        if (htmlView != null) htmlView.setGamepadState(lx, ly, rx, ry, lt, rt, buttonMask);
        // Digital colour/text state can be painted on the very next traversal; the frame runner is
        // still responsible for the secondary scale/axis interpolation.
        if (digitalChanged && !globalHtmlEnabled) invalidate();
        postFrame();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (globalHtmlEnabled) return;
        int save = canvas.save();
        // Settings now allow 25%. Below the original 50% render floor the WindowManager buffer
        // stays larger, so scale the native composition inside that safe viewport. This keeps the
        // requested visual size without letting strokes/text/press motion hit the window edge.
        float viewportScale = displaySizePercent < 50 ? displaySizePercent / 50f : 1f;
        if (viewportScale < 0.999f) {
            canvas.scale(viewportScale, viewportScale, getWidth() * 0.5f, getHeight() * 0.5f);
        }
        if (displayType == DISPLAY_LEFT_STICK || displayType == DISPLAY_RIGHT_STICK) drawStick(canvas);
        else if (displayType == DISPLAY_FACE) drawFaceButtons(canvas);
        else if (displayType == DISPLAY_LEFT_SHOULDER) drawShoulders(canvas, true);
        else if (displayType == DISPLAY_RIGHT_SHOULDER) drawShoulders(canvas, false);
        else if (displayType == DISPLAY_BACK) drawBackButtons(canvas);
        else if (displayType == DISPLAY_DPAD) drawDpad(canvas);
        canvas.restoreToCount(save);
    }

    private void drawStick(Canvas canvas) {
        float side = Math.min(getWidth(), getHeight());
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float outer = side * 0.43f;
        float innerBase = side * 0.21f * (stickDotSizePercent / 100f);
        float pressProgress = clamp(stickPressProgress, 0f, 1f);
        float centerScale = 1f;
        if (animationMode == OverlayState.MOTION_SIZE) {
            centerScale = 1f - 0.08f * pressProgress;
        } else if (animationMode == OverlayState.MOTION_RIPPLE) {
            centerScale = KeyAppearance.cardFeatureBounce(pressProgress);
        }
        float inner = innerBase * centerScale;

        rect.set(cx - outer, cy - outer, cx + outer, cy + outer);
        float outerRadius = KeyAppearance.roundedRadius(rect, cornerStrength);
        fillPaint.setColor(withOpacity(baseColor, backgroundOpacityPercent));
        strokePaint.setColor(withOpacity(borderColor, strokeOpacityPercent));
        KeyAppearance.drawShape(canvas, rect, KeyAppearance.STYLE_ROUNDED, outerRadius, fillPaint);
        KeyAppearance.drawShape(canvas, rect, KeyAppearance.STYLE_ROUNDED, outerRadius, strokePaint);

        // 移动范围按原始摇杆半径计算。L3/R3 动效只改变圆心视觉，不移动圆心。
        float travel = outer - innerBase - side * 0.04f;
        float knobX = cx + shownX * travel;
        float knobY = cy + shownY * travel;
        rect.set(knobX - inner, knobY - inner, knobX + inner, knobY + inner);
        float centerRadius = KeyAppearance.roundedRadius(rect, stickCenterCornerStrength);

        int centerBody = animationMode == OverlayState.MOTION_RIPPLE
                ? stickCenterColor : (stickPressDown ? pressColor : stickCenterColor);
        accentPaint.setColor(withMotionOpacity(centerBody, pressProgress, diffusionOpacityPercent));
        KeyAppearance.drawShape(canvas, rect, KeyAppearance.STYLE_ROUNDED, centerRadius, accentPaint);
        if (animationMode == OverlayState.MOTION_RIPPLE && pressProgress > 0f) {
            KeyAppearance.drawCentreFill(canvas, rect, KeyAppearance.STYLE_ROUNDED, centerRadius,
                    withOpacity(pressColor, diffusionOpacityPercent), pressProgress,
                    accentPaint, motionClipPath);
        }
        strokePaint.setColor(withMotionOpacity(borderColor, pressProgress, strokeOpacityPercent));
        KeyAppearance.drawShape(canvas, rect, KeyAppearance.STYLE_ROUNDED, centerRadius, strokePaint);
    }

    private void drawFaceButtons(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float radius = Math.min(w, h) * 0.155f;
        // 8 dp 保持原布局。调节值只改变四个按键离中心的距离。
        float gap = Math.min(w, h) * 0.245f + dp(faceSpacingDp - 8f);
        float maxGap = Math.max(radius, Math.min(w, h) * 0.5f - radius - dp(2f));
        gap = Math.max(radius, Math.min(maxGap, gap));
        textPaint.setTextSize(radius * 0.92f);

        String[] labels = faceReversed ? FACE_LABELS_REVERSED : FACE_LABELS_NORMAL;
        drawFaceButton(canvas, cx, cy - gap, radius, labels[0], faceDisplayLabel(labels[0]), press[0], faceDpsEnabled(labels[0]), faceDps(labels[0]));
        drawFaceButton(canvas, cx + gap, cy, radius, labels[1], faceDisplayLabel(labels[1]), press[1], faceDpsEnabled(labels[1]), faceDps(labels[1]));
        drawFaceButton(canvas, cx, cy + gap, radius, labels[2], faceDisplayLabel(labels[2]), press[2], faceDpsEnabled(labels[2]), faceDps(labels[2]));
        drawFaceButton(canvas, cx - gap, cy, radius, labels[3], faceDisplayLabel(labels[3]), press[3], faceDpsEnabled(labels[3]), faceDps(labels[3]));
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

    private String faceDisplayLabel(String logicalLabel) {
        if (!faceSymbolIcons) return logicalLabel;
        switch (logicalLabel) {
            case "Y": return "△";
            case "B": return "○";
            case "A": return "X";
            case "X": return "□";
            default: return logicalLabel;
        }
    }

    private void drawFaceButton(Canvas canvas, float cx, float cy, float radius, String logicalLabel, String label, float scale, boolean showDps, int dps) {
        int index = faceIndex(logicalLabel);
        float progress = pressProgress(scale);
        boolean pressed = index >= 0 && pressTargets[index];
        float r = radius * motionScale(progress);
        rect.set(cx - r, cy - r, cx + r, cy + r);
        float corner = KeyAppearance.roundedRadius(rect, cornerStrength);
        drawMotionShape(canvas, rect, corner, pressed, progress);
        int oldText = textPaint.getColor();
        Typeface oldTypeface = textPaint.getTypeface();
        textPaint.setColor(motionTextColor(pressed, progress));

        // CPS 开关不改变主标签字号。
        final float primarySize = radius * 0.92f;
        textPaint.setTextSize(primarySize);
        Paint.FontMetrics primaryFm = textPaint.getFontMetrics();
        float primaryBaseline = cy - (primaryFm.ascent + primaryFm.descent) * 0.5f;

        if (showDps) {
            // 显示 CPS 时只上移主标签，不缩小主图标/字母。
            primaryBaseline -= radius * 0.16f;
            if (faceSymbolIcons) drawFaceSymbol(canvas, logicalLabel, cx, cy - radius * 0.16f, radius, textPaint);
            else canvas.drawText(label, cx, primaryBaseline, textPaint);

            textPaint.setTextSize(Math.min(radius * 0.28f, dp(7.5f)));
            textPaint.setTypeface(typefaceNormal);
            Paint.FontMetrics dpsFm = textPaint.getFontMetrics();
            float dpsBaseline = cy + radius * 0.62f - dpsFm.descent;
            canvas.drawText(dps + " CPS", cx, dpsBaseline, textPaint);
        } else {
            if (faceSymbolIcons) drawFaceSymbol(canvas, logicalLabel, cx, cy, radius, textPaint);
            else canvas.drawText(label, cx, primaryBaseline, textPaint);
        }
        textPaint.setTypeface(oldTypeface);
        textPaint.setColor(oldText);
    }

    private void drawFaceSymbol(Canvas canvas, String logicalLabel, float cx, float cy,
                                float radius, Paint sourcePaint) {
        Paint.Style oldStyle = sourcePaint.getStyle();
        float oldStroke = sourcePaint.getStrokeWidth();
        Paint.Cap oldCap = sourcePaint.getStrokeCap();
        sourcePaint.setStyle(Paint.Style.STROKE);
        sourcePaint.setStrokeWidth(Math.max(dp(1.35f), radius * 0.105f));
        sourcePaint.setStrokeCap(Paint.Cap.ROUND);
        float size = radius * 0.72f;
        switch (logicalLabel) {
            case "Y": { // triangle
                float top = cy - size * 0.58f;
                float bottom = cy + size * 0.48f;
                canvas.drawLine(cx, top, cx - size * 0.58f, bottom, sourcePaint);
                canvas.drawLine(cx - size * 0.58f, bottom, cx + size * 0.58f, bottom, sourcePaint);
                canvas.drawLine(cx + size * 0.58f, bottom, cx, top, sourcePaint);
                break;
            }
            case "B": // circle
                canvas.drawCircle(cx, cy, size * 0.55f, sourcePaint);
                break;
            case "X": // square (Xbox X -> PlayStation square)
                rect.set(cx - size * 0.52f, cy - size * 0.52f,
                        cx + size * 0.52f, cy + size * 0.52f);
                canvas.drawRoundRect(rect, size * 0.05f, size * 0.05f, sourcePaint);
                break;
            case "A": // cross
                float d = size * 0.48f;
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, sourcePaint);
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, sourcePaint);
                break;
            default:
                break;
        }
        sourcePaint.setStyle(oldStyle);
        sourcePaint.setStrokeWidth(oldStroke);
        sourcePaint.setStrokeCap(oldCap);
    }

    private int faceIndex(String label) {
        String[] labels = faceReversed ? FACE_LABELS_REVERSED : FACE_LABELS_NORMAL;
        for (int i = 0; i < labels.length; i++) if (labels[i].equals(label)) return i;
        return -1;
    }

    private void drawShoulders(Canvas canvas, boolean left) {
        float pad = dp(6f);
        float w = getWidth() - pad * 2f;
        float h = getHeight() - pad * 2f;
        float topHeight = h * 0.38f;
        float bottomTop = pad + topHeight + h * 0.08f;

        float topProgress = pressProgress(press[0]);
        float topScale = motionScale(topProgress);
        float topInset = (1f - topScale) * w * 0.06f;
        rect.set(pad + topInset, pad + (1f - topScale) * topHeight * 0.24f,
                pad + w - topInset, pad + topHeight);
        boolean topPressed = pressTargets[0];
        float topRadius = KeyAppearance.roundedRadius(rect, cornerStrength);
        drawMotionShape(canvas, rect, topRadius, topPressed, topProgress);
        if (shoulderDpsEnabled) {
            drawCenteredTextWithDps(canvas, left ? "L1" : "R1", left ? l1Dps : r1Dps,
                    rect, topPressed, topProgress);
        } else {
            drawCenteredText(canvas, left ? "L1" : "R1", rect, topPressed, topProgress);
        }

        float trigger = left ? shownLt : shownRt;
        float bottomProgress = pressProgress(press[1]);
        float bottomScale = motionScale(bottomProgress);
        float bottomInset = (1f - bottomScale) * w * 0.05f;
        rect.set(pad + bottomInset, bottomTop,
                pad + w - bottomInset, pad + h);
        boolean triggerPressed = pressTargets[1] || trigger > 0.08f;
        float bottomRadius = KeyAppearance.roundedRadius(rect, cornerStrength);
        // 进度条模式下底板必须保持 idle 色，否则整块先变成按下色会吃掉模拟扳机进度。
        drawMotionShape(canvas, rect, bottomRadius, triggerProgressEnabled ? false : triggerPressed, bottomProgress);

        if (triggerProgressEnabled && trigger > 0.001f) {
            auxRect.set(rect.left, rect.top,
                    rect.left + rect.width() * clamp(trigger, 0f, 1f), rect.bottom);
            buttonPaint.setColor(withMotionOpacity(pressColor, bottomProgress, diffusionOpacityPercent));
            canvas.save();
            canvas.clipRect(auxRect);
            KeyAppearance.drawShape(canvas, rect, keyStyle, bottomRadius, buttonPaint);
            canvas.restore();
            strokePaint.setColor(withMotionOpacity(borderColor, bottomProgress, strokeOpacityPercent));
            KeyAppearance.drawShape(canvas, rect, keyStyle, bottomRadius, strokePaint);
        }
        drawCenteredText(canvas, left ? "L2" : "R2", rect, triggerPressed, bottomProgress);
    }

    public void setVader5BackLabels(boolean enabled) {
        if (vader5BackLabels == enabled) return;
        vader5BackLabels = enabled;
        invalidate();
    }

    private void drawDpad(Canvas canvas) {
        fillPaint.setColor(withOpacity(baseColor, backgroundOpacityPercent));
        buttonPaint.setColor(withOpacity(pressColor, diffusionOpacityPercent));
        strokePaint.setColor(withOpacity(borderColor, strokeOpacityPercent));
        float side = Math.min(getWidth(), getHeight());
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float gap = dp(4f);
        float cell = Math.max(dp(18f), (side - gap * 2f) / 3f);
        float step = cell + gap;

        drawDpadButton(canvas, 0, "▲", cx, cy - step, cell);
        drawDpadButton(canvas, 1, "▶", cx + step, cy, cell);
        drawDpadButton(canvas, 2, "▼", cx, cy + step, cell);
        drawDpadButton(canvas, 3, "◀", cx - step, cy, cell);
    }

    private void drawDpadButton(Canvas canvas, int index, String label, float cx, float cy, float size) {
        float progress = pressProgress(press[index]);
        float half = size * 0.5f * motionScale(progress);
        rect.set(cx - half, cy - half, cx + half, cy + half);
        boolean pressed = pressTargets[index];
        float radius = KeyAppearance.roundedRadius(rect, cornerStrength);
        drawMotionShape(canvas, rect, radius, pressed, progress);
        textPaint.setTextSize(Math.min(size * 0.36f, dp(18f)));
        textPaint.setColor(motionTextColor(pressed, progress));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = rect.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(label, rect.centerX(), baseline, textPaint);
    }

    private void drawBackButtons(Canvas canvas) {
        fillPaint.setColor(withOpacity(baseColor, backgroundOpacityPercent));
        buttonPaint.setColor(withOpacity(pressColor, diffusionOpacityPercent));
        strokePaint.setColor(withOpacity(borderColor, strokeOpacityPercent));
        float pad = dp(6f);
        float gap = dp(6f);
        float cellW = (getWidth() - pad * 2f - gap) * 0.5f;
        float cellH = (getHeight() - pad * 2f - gap) * 0.5f;
        String prefix = vader5BackLabels ? "M" : "P";
        drawBackButton(canvas, 0, prefix + "1", pad, pad, cellW, cellH);
        drawBackButton(canvas, 1, prefix + "2", pad + cellW + gap, pad, cellW, cellH);
        drawBackButton(canvas, 2, prefix + "3", pad, pad + cellH + gap, cellW, cellH);
        drawBackButton(canvas, 3, prefix + "4", pad + cellW + gap, pad + cellH + gap, cellW, cellH);
    }

    private void drawBackButton(Canvas canvas, int index, String label, float left, float top, float width, float height) {
        float progress = pressProgress(press[index]);
        float scale = motionScale(progress);
        float insetX = (1f - scale) * width * 0.08f;
        float insetY = (1f - scale) * height * 0.10f;
        rect.set(left + insetX, top + insetY, left + width - insetX, top + height - insetY);
        boolean pressed = pressTargets[index];
        float radius = KeyAppearance.roundedRadius(rect, cornerStrength);
        drawMotionShape(canvas, rect, radius, pressed, progress);
        drawCenteredText(canvas, label, rect, pressed, progress);
    }

    private void drawCenteredText(Canvas canvas, String text, RectF area, boolean pressed, float progress) {
        textPaint.setTextSize(Math.min(area.height() * 0.43f, dp(18f)));
        textPaint.setColor(motionTextColor(pressed, progress));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = area.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(text, area.centerX(), baseline, textPaint);
    }

    private void drawCenteredTextWithDps(Canvas canvas, String text, int dps, RectF area, boolean pressed, float progress) {
        Typeface oldTypeface = textPaint.getTypeface();
        textPaint.setColor(motionTextColor(pressed, progress));

        // L1/R1 主标签使用统一字号和字体。
        final float primarySize = Math.min(area.height() * 0.43f, dp(18f));
        textPaint.setTextSize(primarySize);
        textPaint.setTypeface(oldTypeface);
        Paint.FontMetrics primaryFm = textPaint.getFontMetrics();
        float primaryBaseline = area.centerY() - (primaryFm.ascent + primaryFm.descent) * 0.5f;
        primaryBaseline -= Math.min(dp(4f), area.height() * 0.09f);
        canvas.drawText(text, area.centerX(), primaryBaseline, textPaint);

        // CPS 固定在主标签下方，空间不足时优先缩小。
        final float dpsSize = Math.min(area.height() * 0.17f, dp(8.5f));
        textPaint.setTextSize(Math.max(dp(6f), dpsSize));
        textPaint.setTypeface(typefaceNormal);
        Paint.FontMetrics dpsFm = textPaint.getFontMetrics();
        float dpsBaseline = area.bottom - dp(3f) - dpsFm.descent;
        canvas.drawText(dps + " CPS", area.centerX(), dpsBaseline, textPaint);
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
            case DISPLAY_BACK: type = GlobalHtmlWebView.TYPE_GAMEPAD_BACK; break;
            case DISPLAY_DPAD: type = GlobalHtmlWebView.TYPE_GAMEPAD_DPAD; break;
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

        // 摇杆保持临界阻尼和连续改道，但降低刚度，让视觉跟随更平静。
        float ax = 120f * (targetX - shownX) - 22f * velocityX;
        float ay = 120f * (targetY - shownY) - 22f * velocityY;
        velocityX += ax * dt;
        velocityY += ay * dt;
        shownX += velocityX * dt;
        shownY += velocityY * dt;

        float alt = 105f * (targetLt - shownLt) - 20.5f * triggerVelocityLt;
        float art = 105f * (targetRt - shownRt) - 20.5f * triggerVelocityRt;
        triggerVelocityLt += alt * dt;
        triggerVelocityRt += art * dt;
        shownLt += triggerVelocityLt * dt;
        shownRt += triggerVelocityRt * dt;

        // L3/R3 的状态进度独立于摇杆位移。关闭动效时直接落到目标状态；
        // 其余模式共享同一条临界阻尼状态曲线，由绘制层决定缩放/透明度/扩散。
        boolean stickPressActive = false;
        float stickPressTarget = stickPressDown ? 1f : 0f;
        if (animationMode == OverlayState.MOTION_NONE) {
            stickPressProgress = stickPressTarget;
            stickPressVelocity = 0f;
        } else {
            float stickPressA = 145f * (stickPressTarget - stickPressProgress) - 24f * stickPressVelocity;
            stickPressVelocity += stickPressA * dt;
            stickPressProgress += stickPressVelocity * dt;
            if (Math.abs(stickPressTarget - stickPressProgress) < 0.0008f
                    && Math.abs(stickPressVelocity) < 0.012f) {
                stickPressProgress = stickPressTarget;
                stickPressVelocity = 0f;
            } else {
                stickPressActive = true;
            }
        }

        boolean buttonActive = false;
        for (int i = 0; i < 4; i++) {
            float target = pressTargets[i] ? 0.86f : 1f;
            if (animationMode == OverlayState.MOTION_NONE) {
                press[i] = target;
                pressVelocity[i] = 0f;
                continue;
            }
            float a = 145f * (target - press[i]) - 24f * pressVelocity[i];
            pressVelocity[i] += a * dt;
            press[i] += pressVelocity[i] * dt;
            if (Math.abs(target - press[i]) < 0.0008f && Math.abs(pressVelocity[i]) < 0.012f) {
                press[i] = target;
                pressVelocity[i] = 0f;
            } else {
                buttonActive = true;
            }
        }

        float hostA = 100f * (hostTarget - hostProgress) - 20f * hostVelocity;
        hostVelocity += hostA * dt;
        hostProgress += hostVelocity * dt;
        float dragTarget = dragging ? 1f : 0f;
        float dragA = 135f * (dragTarget - dragProgress) - 23f * dragVelocity;
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

        float enterScale = 0.97f + 0.03f * clamp(hostProgress, 0f, 1f);
        float dragScale = 1f - 0.010f * clamp(dragProgress, 0f, 1f);
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
        boolean hostActive = Math.abs(hostTarget - hostProgress) > 0.001f || Math.abs(hostVelocity) > 0.02f;
        boolean dragActive = Math.abs(dragTarget - dragProgress) > 0.001f || Math.abs(dragVelocity) > 0.02f;
        if (hostTarget == 0f && !hostActive && exitCallback != null) {
            Runnable callback = exitCallback;
            exitCallback = null;
            post(callback);
            return;
        }
        if (stickActive || triggerActive || stickPressActive || buttonActive || hostActive || dragActive) postFrame();
    }

    private float pressProgress(float scale) {
        return clamp((1f - scale) / 0.14f, 0f, 1f);
    }

    private float motionScale(float progress) {
        if (animationMode == OverlayState.MOTION_SIZE) {
            return 1f - 0.14f * clamp(progress, 0f, 1f);
        }
        if (animationMode == OverlayState.MOTION_RIPPLE) {
            return KeyAppearance.cardFeatureBounce(progress);
        }
        return 1f;
    }

    private int withMotionOpacity(int color, float progress, int percent) {
        float factor = animationMode == OverlayState.MOTION_ALPHA
                ? clamp(0.56f + 0.44f * clamp(progress, 0f, 1f), 0.48f, 1f)
                : 1f;
        int alpha = Math.round(android.graphics.Color.alpha(color)
                * clampPercent(percent) / 100f * factor);
        return android.graphics.Color.argb(Math.max(0, Math.min(255, alpha)),
                android.graphics.Color.red(color), android.graphics.Color.green(color),
                android.graphics.Color.blue(color));
    }

    private void drawMotionShape(Canvas canvas, RectF area, float radius,
                                 boolean pressed, float progress) {
        int bodyColor = animationMode == OverlayState.MOTION_RIPPLE
                ? baseColor : (pressed ? pressColor : baseColor);
        fillPaint.setColor(withMotionOpacity(bodyColor, progress, backgroundOpacityPercent));
        KeyAppearance.drawShape(canvas, area, keyStyle, radius, fillPaint);
        if (animationMode == OverlayState.MOTION_RIPPLE && progress > 0f) {
            KeyAppearance.drawCentreFill(canvas, area, keyStyle, radius,
                    withOpacity(pressColor, diffusionOpacityPercent), progress,
                    buttonPaint, motionClipPath);
        }
        strokePaint.setColor(withMotionOpacity(borderColor, progress, strokeOpacityPercent));
        KeyAppearance.drawShape(canvas, area, keyStyle, radius, strokePaint);
    }

    private int motionTextColor(boolean pressed, float progress) {
        int color;
        if (animationMode == OverlayState.MOTION_RIPPLE) {
            color = KeyAppearance.blendColor(textColor, KeyAppearance.pressedTextColor(pressColor),
                    KeyAppearance.centreTextMix(progress));
        } else {
            color = pressed ? KeyAppearance.pressedTextColor(pressColor) : textColor;
        }
        return withMotionOpacity(color, progress, textOpacityPercent);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static int withOpacity(int color, int percent) {
        int alpha = Math.round(android.graphics.Color.alpha(color) * clampPercent(percent) / 100f);
        return android.graphics.Color.argb(alpha, android.graphics.Color.red(color),
                android.graphics.Color.green(color), android.graphics.Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
