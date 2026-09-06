package com.axon.input;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/** Live preview / created control for the super-custom key display editor. */
final class SuperCustomControlView extends FrameLayout {
    /**
     * Diffusion mode mirrors Matrix Card UI feature on/off motion: state changes are interruptible,
     * continue from the current visual value, and use the same calm spatial easing as the settings UI.
     */
    private static final TimeInterpolator CENTRE_FILL_EASE = KeyAppearance::cardFeatureToggleEase;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Long> cpsSamples = new ArrayDeque<>();
    private final LinearLayout content;
    private final OutlinedTextView label;
    private final TextView cps;
    private final TextView bindingBadge;

    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF keyBounds = new RectF();
    private final Path keyClipPath = new Path();

    private SuperCustomControlSpec spec;
    private boolean keyPressed;
    private boolean interactivePreview;
    private int idleColor;
    private float stickX;
    private float stickY;

    private ValueAnimator rippleAnimator;
    private float rippleProgress;
    private int rippleGeneration;

    private final Runnable cpsRefresh = new Runnable() {
        @Override public void run() {
            refreshCps();
            if (!cpsSamples.isEmpty()) handler.postDelayed(this, 120L);
        }
    };

    SuperCustomControlView(Context context) {
        super(context);

        // Fully opt out of Android/OEM press visuals. The key surface and Matrix card-state fill
        // are rendered below in onDraw().
        setBackground(null);
        setForeground(null);
        setStateListAnimator(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
        setSoundEffectsEnabled(false);
        setHapticFeedbackEnabled(false);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDuplicateParentStateEnabled(false);
        setWillNotDraw(false);
        setClipToOutline(false);
        setClipChildren(false);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setBackground(null);
        content.setClickable(false);
        content.setFocusable(false);
        addView(content, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        label = new OutlinedTextView(context);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        label.setSingleLine(true);
        label.setBackground(null);
        label.setClickable(false);
        label.setFocusable(false);
        content.addView(label, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        cps = new TextView(context);
        cps.setGravity(Gravity.CENTER);
        cps.setIncludeFontPadding(false);
        cps.setSingleLine(true);
        cps.setTextSize(10f);
        cps.setBackground(null);
        cps.setClickable(false);
        cps.setFocusable(false);
        content.addView(cps, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 绑定组标签只属于编辑器覆盖层，不写进运行时视觉状态。Runtime 从不调用该入口。
        bindingBadge = new TextView(context);
        bindingBadge.setGravity(Gravity.CENTER);
        bindingBadge.setIncludeFontPadding(false);
        bindingBadge.setSingleLine(true);
        bindingBadge.setTextSize(10f);
        bindingBadge.setTextColor(Color.WHITE);
        bindingBadge.setShadowLayer(dp(1.5f), 0f, dp(0.5f), 0xcc000000);
        bindingBadge.setBackground(null);
        bindingBadge.setClickable(false);
        bindingBadge.setFocusable(false);
        bindingBadge.setVisibility(View.GONE);
        addView(bindingBadge, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Preview touch is consumed here so no click/pressed state can be generated by View itself.
        setOnTouchListener((v, event) -> {
            if (!interactivePreview) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setPressedState(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setPressedState(false);
                    return true;
                default:
                    return true;
            }
        });
    }

    void setInteractivePreview(boolean enabled) {
        interactivePreview = enabled;
    }

    TextView labelView() {
        return label;
    }

    TextView cpsView() {
        return cps;
    }

    void setEditorBindingGroup(int groupIndex, boolean visible) {
        if (!visible || groupIndex <= 0) {
            bindingBadge.setVisibility(View.GONE);
            bindingBadge.setText("");
            return;
        }
        bindingBadge.setText("绑定组" + groupIndex);
        bindingBadge.setVisibility(View.VISIBLE);
        bindingBadge.bringToFront();
    }

    void applySpec(SuperCustomControlSpec next) {
        spec = next;
        if (next == null) return;

        idleColor = next.currentBaseColor(UiPalette.controlSurface(getContext()));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (lp == null) lp = new FrameLayout.LayoutParams(dp(next.widthDp), dp(next.heightDp));
        lp.width = dp(next.widthDp);
        lp.height = dp(next.heightDp);
        setLayoutParams(lp);

        // Text components are static composition elements, while key components keep the existing
        // press/ripple semantics. Opacity remains a single top-level multiplier in both cases.
        animate().cancel();
        float baseAlpha = clamp01(next.opacityPercent / 100f);
        boolean independentRipple = next.isKeyElement()
                && next.motionMode == OverlayState.MOTION_RIPPLE;
        setAlpha(independentRipple ? 1f : baseAlpha);
        setScaleX(1f);
        setScaleY(1f);
        content.setVisibility(next.isStickElement() ? View.GONE : View.VISIBLE);

        label.setText(next.labelText == null ? "" : next.labelText);
        // Imported display fonts apply to both key labels and free text components.
        label.setTypeface(FontManager.bold(getContext()));
        cps.setTypeface(FontManager.normal(getContext()));
        int animatedText = next.currentTextColor();
        float textLayer = next.textOpacityPercent / 100f;
        int contentColor = independentRipple
                ? multiplyColorAlpha(animatedText, (next.opacityPercent / 100f) * textLayer)
                : multiplyColorAlpha(animatedText, textLayer);
        label.setTextColor(contentColor);
        label.setTextSize(next.textSizeSp);
        label.setOutline(next.isTextElement() && next.textStrokeEnabled,
                next.currentTextStrokeColor(), dp(next.textStrokeWidthDp));
        cps.setTextColor(contentColor);
        cps.setTextSize(Math.max(9f, next.textSizeSp * 0.56f));
        cps.setVisibility(next.isKeyElement() && next.cpsEnabled ? View.VISIBLE : View.GONE);
        setPadding(next.isTextElement() ? dp(3) : dp(8),
                next.isTextElement() ? dp(2) : dp(6),
                next.isTextElement() ? dp(3) : dp(8),
                next.isTextElement() ? dp(2) : dp(6));

        if (next.isTextElement() || next.motionMode != OverlayState.MOTION_RIPPLE) {
            cancelRippleAnimator();
            rippleProgress = 0f;
        }

        if (!next.isKeyElement()) {
            keyPressed = false;
            handler.removeCallbacks(cpsRefresh);
            cpsSamples.clear();
            cps.setText("");
            if (next.isStickElement()) {
                stickX = 0f;
                stickY = 0f;
            }
        } else {
            refreshCps();
        }
        invalidate();
    }

    void onBoundKeyEvent(boolean down) {
        if (spec == null || !spec.isKeyElement()) return;
        if (down && !keyPressed) {
            long now = SystemClock.uptimeMillis();
            cpsSamples.addLast(now);
            handler.removeCallbacks(cpsRefresh);
            handler.post(cpsRefresh);
        }
        setPressedState(down);
    }

    void setStickState(float x, float y) {
        if (spec == null || !spec.isStickElement()) return;
        float nx = clampAxis(x);
        float ny = clampAxis(y);
        float length = (float) Math.hypot(nx, ny);
        if (length < 0.045f) {
            nx = 0f;
            ny = 0f;
        } else if (length > 1f) {
            nx /= length;
            ny /= length;
        }
        if (Math.abs(stickX - nx) < 0.001f && Math.abs(stickY - ny) < 0.001f) return;
        stickX = nx;
        stickY = ny;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (spec == null) return;
        if (spec.hasAnimatedColors()) {
            idleColor = spec.currentBaseColor(UiPalette.controlSurface(getContext()));
            int animatedText = spec.currentTextColor();
            boolean independentRipple = spec.isKeyElement() && spec.motionMode == OverlayState.MOTION_RIPPLE;
            float textLayer = spec.textOpacityPercent / 100f;
            int contentColor = independentRipple
                    ? multiplyColorAlpha(animatedText, (spec.opacityPercent / 100f) * textLayer)
                    : multiplyColorAlpha(animatedText, textLayer);
            label.setTextColor(contentColor);
            cps.setTextColor(contentColor);
            label.setOutline(spec.isTextElement() && spec.textStrokeEnabled,
                    spec.currentTextStrokeColor(), dp(spec.textStrokeWidthDp));
            postInvalidateOnAnimation();
        }
        if (spec.isTextElement()) return;
        if (spec.isStickElement()) {
            drawStickSurface(canvas);
            return;
        }
        drawKeySurface(canvas);
        drawCardFeatureStateFill(canvas);
        drawKeyBorder(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelRippleAnimator();
        handler.removeCallbacks(cpsRefresh);
        super.onDetachedFromWindow();
    }

    private void setPressedState(boolean pressed) {
        if (spec == null || !spec.isKeyElement() || keyPressed == pressed) return;
        keyPressed = pressed;
        animate().cancel();

        final float baseAlpha = spec.opacityPercent / 100f;
        switch (spec.motionMode) {
            case OverlayState.MOTION_ALPHA:
                animate().alpha(pressed ? baseAlpha * 0.62f : baseAlpha)
                        .setDuration(pressed ? UiMotion.pressMs() : UiMotion.releaseMs())
                        .setInterpolator(UiMotion.easeOut())
                        .start();
                break;

            case OverlayState.MOTION_RIPPLE:
                // Matrix Card UI semantics: press = feature ON, release = feature OFF. A reversal
                // starts from the current rendered value instead of restarting from an endpoint.
                setAlpha(1f);
                setScaleX(1f);
                setScaleY(1f);
                animateCardFeatureState(pressed);
                break;

            case OverlayState.MOTION_NONE:
                cancelRippleAnimator();
                rippleProgress = 0f;
                setAlpha(baseAlpha);
                setScaleX(1f);
                setScaleY(1f);
                break;

            case OverlayState.MOTION_SIZE:
            default:
                cancelRippleAnimator();
                rippleProgress = 0f;
                animate().scaleX(pressed ? 0.978f : 1f)
                        .scaleY(pressed ? 0.978f : 1f)
                        .alpha(baseAlpha)
                        .setDuration(pressed ? UiMotion.pressMs() : UiMotion.releaseMs())
                        .setInterpolator(UiMotion.easeOut())
                        .start();
                break;
        }
        invalidate();
    }

    private void animateCardFeatureState(boolean enabledNow) {
        float start = clamp01(rippleProgress);
        float end = enabledNow ? 1f : 0f;
        int generation = ++rippleGeneration;

        cancelRippleAnimator();

        long duration = KeyAppearance.cardFeatureToggleDuration(start, end);
        if (duration <= 0L) {
            rippleProgress = end;
            invalidate();
            return;
        }

        rippleAnimator = ValueAnimator.ofFloat(start, end);
        rippleAnimator.setDuration(duration);
        rippleAnimator.setInterpolator(CENTRE_FILL_EASE);
        rippleAnimator.addUpdateListener(animation -> {
            rippleProgress = (float) animation.getAnimatedValue();
            float bounce = KeyAppearance.cardFeatureBounce(rippleProgress);
            setScaleX(bounce);
            setScaleY(bounce);
            postInvalidateOnAnimation();
        });
        rippleAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(Animator animation) {
                if (rippleAnimator == animation) rippleAnimator = null;
                if (!cancelled && generation == rippleGeneration) {
                    rippleProgress = end;
                    float bounce = KeyAppearance.cardFeatureBounce(end);
                    setScaleX(bounce);
                    setScaleY(bounce);
                    postInvalidateOnAnimation();
                }
            }
        });
        rippleAnimator.start();
    }

    private void drawKeySurface(Canvas canvas) {
        if (spec == null || !spec.isKeyElement() || getWidth() <= 0 || getHeight() <= 0) return;

        keyBounds.set(0f, 0f, getWidth(), getHeight());
        float corner = resolvedCornerRadius();

        int color = idleColor;
        if (spec.motionMode != OverlayState.MOTION_RIPPLE && keyPressed) {
            color = spec.currentPressColor();
        }

        if (spec.backgroundOpacityPercent <= 0 || Color.alpha(color) <= 0) return;

        surfacePaint.reset();
        surfacePaint.setAntiAlias(true);
        surfacePaint.setStyle(Paint.Style.FILL);
        surfacePaint.setColor(color);
        int surfaceAlpha = Math.round(Color.alpha(color) * spec.backgroundOpacityPercent / 100f);
        surfacePaint.setAlpha(surfaceAlpha);

        // Non-ripple modes already apply the user's opacity at the View layer. Ripple mode keeps
        // View alpha at 1 to preserve diffusion-opacity independence, so only its base surface is
        // multiplied here.
        if (spec.motionMode == OverlayState.MOTION_RIPPLE) {
            int sourceAlpha = Color.alpha(color);
            surfacePaint.setAlpha(Math.round(sourceAlpha * spec.opacityPercent / 100f
                    * spec.backgroundOpacityPercent / 100f));
        }
        canvas.drawRoundRect(keyBounds, corner, corner, surfacePaint);
    }

    private void drawCardFeatureStateFill(Canvas canvas) {
        if (spec == null || !spec.isKeyElement() || spec.motionMode != OverlayState.MOTION_RIPPLE) return;
        if (spec.backgroundOpacityPercent <= 0 || rippleProgress <= 0f || getWidth() <= 0 || getHeight() <= 0) return;

        keyBounds.set(0f, 0f, getWidth(), getHeight());
        float corner = resolvedCornerRadius();
        ripplePaint.reset();
        ripplePaint.setAntiAlias(true);
        ripplePaint.setStyle(Paint.Style.FILL);
        int animatedPress = spec.currentPressColor();
        int alpha = Math.round(Color.alpha(animatedPress) * spec.diffusionOpacityPercent / 100f
                * spec.backgroundOpacityPercent / 100f);
        int press = Color.argb(alpha, Color.red(animatedPress),
                Color.green(animatedPress), Color.blue(animatedPress));
        KeyAppearance.drawCentreFill(canvas, keyBounds, KeyAppearance.STYLE_ROUNDED, corner,
                press, rippleProgress, ripplePaint, keyClipPath);
    }

    private void drawKeyBorder(Canvas canvas) {
        if (spec == null || !spec.isKeyElement() || getWidth() <= 0 || getHeight() <= 0) return;
        int paletteStroke = UiPalette.overlayStroke(getContext());
        int source = spec.currentBorderColor(paletteStroke);
        int alpha = Math.round(Color.alpha(source) * spec.borderOpacityPercent / 100f);
        if (spec.motionMode == OverlayState.MOTION_RIPPLE) {
            alpha = Math.round(alpha * spec.opacityPercent / 100f);
        }
        float strokeWidth = Math.max(1f, dp(spec.borderWidthDp));
        float half = strokeWidth * 0.5f;
        keyBounds.set(half, half, Math.max(half, getWidth() - half), Math.max(half, getHeight() - half));
        float corner = resolvedCornerRadius();

        borderPaint.reset();
        borderPaint.setAntiAlias(true);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(strokeWidth);
        borderPaint.setColor(Color.argb(alpha, Color.red(source), Color.green(source), Color.blue(source)));
        canvas.drawRoundRect(keyBounds, corner, corner, borderPaint);
    }

    private void drawStickSurface(Canvas canvas) {
        if (spec == null || !spec.isStickElement() || getWidth() <= 0 || getHeight() <= 0) return;

        float strokeWidth = Math.max(1f, dp(spec.borderWidthDp));
        float halfStroke = strokeWidth * 0.5f;
        keyBounds.set(halfStroke, halfStroke,
                Math.max(halfStroke, getWidth() - halfStroke),
                Math.max(halfStroke, getHeight() - halfStroke));
        float halfMin = Math.min(keyBounds.width(), keyBounds.height()) * 0.5f;
        float corner = halfMin * clamp01(spec.cornerDp / 100f);

        surfacePaint.reset();
        surfacePaint.setAntiAlias(true);
        surfacePaint.setStyle(Paint.Style.FILL);
        int stickBackground = spec.currentPressColor();
        surfacePaint.setColor(stickBackground);
        surfacePaint.setAlpha(Math.round(Color.alpha(stickBackground)
                * spec.backgroundOpacityPercent / 100f));
        canvas.drawRoundRect(keyBounds, corner, corner, surfacePaint);

        int border = spec.currentBorderColor(UiPalette.overlayStroke(getContext()));
        borderPaint.reset();
        borderPaint.setAntiAlias(true);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(strokeWidth);
        borderPaint.setColor(border);
        borderPaint.setAlpha(Math.round(Color.alpha(border) * spec.borderOpacityPercent / 100f));
        canvas.drawRoundRect(keyBounds, corner, corner, borderPaint);

        float dotDiameter = Math.min(keyBounds.width(), keyBounds.height())
                * Math.max(0.12f, Math.min(0.70f, spec.stickDotSizePercent / 100f));
        float dotRadius = dotDiameter * 0.5f;
        float dotCorner = dotRadius * clamp01(spec.stickDotCornerPercent / 100f);
        float centerX = keyBounds.centerX();
        float centerY = keyBounds.centerY();
        float inset = Math.max(dp(5f), strokeWidth * 2f);
        float travelX = Math.max(0f, keyBounds.width() * 0.5f - dotRadius - inset);
        float travelY = Math.max(0f, keyBounds.height() * 0.5f - dotRadius - inset);

        ripplePaint.reset();
        ripplePaint.setAntiAlias(true);
        ripplePaint.setStyle(Paint.Style.FILL);
        int dotColor = spec.currentTextColor();
        ripplePaint.setColor(dotColor);
        ripplePaint.setAlpha(Math.round(Color.alpha(dotColor) * spec.textOpacityPercent / 100f));
        float dotCenterX = centerX + stickX * travelX;
        float dotCenterY = centerY + stickY * travelY;
        keyBounds.set(dotCenterX - dotRadius, dotCenterY - dotRadius,
                dotCenterX + dotRadius, dotCenterY + dotRadius);
        canvas.drawRoundRect(keyBounds, dotCorner, dotCorner, ripplePaint);
    }

    private float resolvedCornerRadius() {
        return Math.max(0f, Math.min(dp(spec.cornerDp),
                Math.min(keyBounds.width(), keyBounds.height()) * 0.5f));
    }

    private void cancelRippleAnimator() {
        if (rippleAnimator != null) {
            rippleAnimator.cancel();
            rippleAnimator = null;
        }
    }

    private void refreshCps() {
        long cutoff = SystemClock.uptimeMillis() - 1000L;
        while (!cpsSamples.isEmpty() && cpsSamples.peekFirst() < cutoff) {
            cpsSamples.removeFirst();
        }
        if (spec == null || !spec.cpsEnabled) {
            cps.setText("");
            return;
        }
        cps.setText(spec.renderCps(cpsSamples.size()));
    }

    private static int multiplyColorAlpha(int color, float factor) {
        float f = clamp01(factor);
        int alpha = Math.round(Color.alpha(color) * f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static float clampAxis(float value) {
        if (value <= -1f) return -1f;
        if (value >= 1f) return 1f;
        return value;
    }

    private static float clamp01(float value) {
        if (value <= 0f) return 0f;
        if (value >= 1f) return 1f;
        return value;
    }


    /**
     * Single-line TextView with an optional true glyph outline. It only custom-draws when the
     * free-text component enables stroke, so ordinary key labels retain Android's native layout.
     */
    private static final class OutlinedTextView extends TextView {
        private boolean outlineEnabled;
        private int outlineColor = Color.BLACK;
        private float outlineWidthPx;

        OutlinedTextView(Context context) {
            super(context);
        }

        void setOutline(boolean enabled, int color, float widthPx) {
            outlineEnabled = enabled;
            outlineColor = color;
            outlineWidthPx = Math.max(0f, widthPx);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!outlineEnabled || outlineWidthPx <= 0f || getText() == null) {
                super.onDraw(canvas);
                return;
            }

            String value = getText().toString();
            if (value.isEmpty()) return;
            Paint paint = getPaint();
            Paint.Style oldStyle = paint.getStyle();
            Paint.Align oldAlign = paint.getTextAlign();
            float oldWidth = paint.getStrokeWidth();
            int oldColor = paint.getColor();
            Paint.Join oldJoin = paint.getStrokeJoin();

            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float x = getWidth() * 0.5f;
            float y = (getHeight() - fm.bottom - fm.top) * 0.5f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(outlineWidthPx);
            paint.setColor(outlineColor);
            canvas.drawText(value, x, y, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(getCurrentTextColor());
            canvas.drawText(value, x, y, paint);

            paint.setStyle(oldStyle);
            paint.setTextAlign(oldAlign);
            paint.setStrokeWidth(oldWidth);
            paint.setColor(oldColor);
            paint.setStrokeJoin(oldJoin);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
