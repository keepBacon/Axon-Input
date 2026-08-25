package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** 输入时显示的只读全键盘。 */
public final class FullKeyboardOverlayView extends View {
    public static final int DISPLAY_FULL_KEYBOARD = 21;
    private static final long FLASH_MS = 140L;
    private static final float KEY_GAP_DP = 3f;
    private static final float PANEL_PADDING_DP = 6f;
    private static final float PANEL_RADIUS_DP = 10f;

    private static final class KeySpec {
        final int code;
        final String label;
        final float weight;

        KeySpec(int code, String label) { this(code, label, 1f); }
        KeySpec(int code, String label, float weight) {
            this.code = code;
            this.label = label;
            this.weight = weight;
        }
    }

    private static final KeySpec[][] ROWS = new KeySpec[][]{
            {
                    k(KeyEvent.KEYCODE_ESCAPE, "Esc"), k(KeyEvent.KEYCODE_F1, "F1"),
                    k(KeyEvent.KEYCODE_F2, "F2"), k(KeyEvent.KEYCODE_F3, "F3"),
                    k(KeyEvent.KEYCODE_F4, "F4"), k(KeyEvent.KEYCODE_F5, "F5"),
                    k(KeyEvent.KEYCODE_F6, "F6"), k(KeyEvent.KEYCODE_F7, "F7"),
                    k(KeyEvent.KEYCODE_F8, "F8"), k(KeyEvent.KEYCODE_F9, "F9"),
                    k(KeyEvent.KEYCODE_F10, "F10"), k(KeyEvent.KEYCODE_F11, "F11"),
                    k(KeyEvent.KEYCODE_F12, "F12")
            },
            {
                    k(KeyEvent.KEYCODE_GRAVE, "`"), k(KeyEvent.KEYCODE_1, "1"),
                    k(KeyEvent.KEYCODE_2, "2"), k(KeyEvent.KEYCODE_3, "3"),
                    k(KeyEvent.KEYCODE_4, "4"), k(KeyEvent.KEYCODE_5, "5"),
                    k(KeyEvent.KEYCODE_6, "6"), k(KeyEvent.KEYCODE_7, "7"),
                    k(KeyEvent.KEYCODE_8, "8"), k(KeyEvent.KEYCODE_9, "9"),
                    k(KeyEvent.KEYCODE_0, "0"), k(KeyEvent.KEYCODE_MINUS, "-"),
                    k(KeyEvent.KEYCODE_EQUALS, "="), k(KeyEvent.KEYCODE_DEL, "Back", 1.7f)
            },
            {
                    k(KeyEvent.KEYCODE_TAB, "Tab", 1.45f), k(KeyEvent.KEYCODE_Q, "Q"),
                    k(KeyEvent.KEYCODE_W, "W"), k(KeyEvent.KEYCODE_E, "E"),
                    k(KeyEvent.KEYCODE_R, "R"), k(KeyEvent.KEYCODE_T, "T"),
                    k(KeyEvent.KEYCODE_Y, "Y"), k(KeyEvent.KEYCODE_U, "U"),
                    k(KeyEvent.KEYCODE_I, "I"), k(KeyEvent.KEYCODE_O, "O"),
                    k(KeyEvent.KEYCODE_P, "P"), k(KeyEvent.KEYCODE_LEFT_BRACKET, "["),
                    k(KeyEvent.KEYCODE_RIGHT_BRACKET, "]"), k(KeyEvent.KEYCODE_BACKSLASH, "\\", 1.25f)
            },
            {
                    k(KeyEvent.KEYCODE_CAPS_LOCK, "Caps", 1.7f), k(KeyEvent.KEYCODE_A, "A"),
                    k(KeyEvent.KEYCODE_S, "S"), k(KeyEvent.KEYCODE_D, "D"),
                    k(KeyEvent.KEYCODE_F, "F"), k(KeyEvent.KEYCODE_G, "G"),
                    k(KeyEvent.KEYCODE_H, "H"), k(KeyEvent.KEYCODE_J, "J"),
                    k(KeyEvent.KEYCODE_K, "K"), k(KeyEvent.KEYCODE_L, "L"),
                    k(KeyEvent.KEYCODE_SEMICOLON, ";"), k(KeyEvent.KEYCODE_APOSTROPHE, "'"),
                    k(KeyEvent.KEYCODE_ENTER, "Enter", 1.95f)
            },
            {
                    k(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", 2.15f), k(KeyEvent.KEYCODE_Z, "Z"),
                    k(KeyEvent.KEYCODE_X, "X"), k(KeyEvent.KEYCODE_C, "C"),
                    k(KeyEvent.KEYCODE_V, "V"), k(KeyEvent.KEYCODE_B, "B"),
                    k(KeyEvent.KEYCODE_N, "N"), k(KeyEvent.KEYCODE_M, "M"),
                    k(KeyEvent.KEYCODE_COMMA, ","), k(KeyEvent.KEYCODE_PERIOD, "."),
                    k(KeyEvent.KEYCODE_SLASH, "/"), k(KeyEvent.KEYCODE_SHIFT_RIGHT, "Shift", 2.15f)
            },
            {
                    k(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl", 1.45f),
                    k(KeyEvent.KEYCODE_META_LEFT, "Win", 1.25f),
                    k(KeyEvent.KEYCODE_ALT_LEFT, "Alt", 1.25f),
                    k(KeyEvent.KEYCODE_SPACE, "Space", 6.4f),
                    k(KeyEvent.KEYCODE_ALT_RIGHT, "Alt", 1.25f),
                    k(KeyEvent.KEYCODE_META_RIGHT, "Win", 1.25f),
                    k(KeyEvent.KEYCODE_CTRL_RIGHT, "Ctrl", 1.45f)
            }
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final SparseBooleanArray held = new SparseBooleanArray();
    private final SparseLongArray flashUntil = new SparseLongArray();
    private final SparseArray<Float> fillValue = new SparseArray<>();
    private final SparseArray<Float> fillFrom = new SparseArray<>();
    private final SparseArray<Float> fillTo = new SparseArray<>();
    private final SparseLongArray fillStartedAt = new SparseLongArray();
    private final SparseLongArray fillDurationMs = new SparseLongArray();
    private final Path fillClipPath = new Path();
    private final float density;
    private int keyStyle = KeyAppearance.STYLE_ROUNDED;
    private int cornerStrength = KeyAppearance.DEFAULT_CORNER_STRENGTH;
    private int baseColor;
    private int borderColor;
    private int pressColor;
    private int backgroundOpacityPercent = 100;
    private int strokeOpacityPercent = 100;
    private int textOpacityPercent = 100;
    private int diffusionOpacityPercent = 100;
    private boolean centreFillActive;

    public FullKeyboardOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        baseColor = UiPalette.overlayKeyIdle(context);
        borderColor = UiPalette.overlayStroke(context);
        pressColor = UiPalette.overlayKeyPressed(context);
        paint.setTypeface(FontManager.normal(context));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(0.8f));
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setKeyAppearance(int style, int color) {
        keyStyle = KeyAppearance.clampStyle(style);
        pressColor = 0xff000000 | (color & 0x00ffffff);
        invalidate();
    }

    public void setKeyBaseColor(int color) {
        int resolved = 0xff000000 | (color & 0x00ffffff);
        if (baseColor == resolved) return;
        baseColor = resolved;
        invalidate();
    }

    public void setKeyBorderColor(int color) {
        if (borderColor == color) return;
        borderColor = color;
        invalidate();
    }

    public void setCornerStrength(int strength) {
        int resolved = KeyAppearance.clampCornerStrength(strength);
        if (cornerStrength == resolved) return;
        cornerStrength = resolved;
        invalidate();
    }

    public void setLayerOpacities(int backgroundPercent, int strokePercent, int textPercent) {
        backgroundOpacityPercent = clampPercent(backgroundPercent);
        strokeOpacityPercent = clampPercent(strokePercent);
        textOpacityPercent = clampPercent(textPercent);
        invalidate();
    }

    public void setDiffusionOpacity(int percent) {
        diffusionOpacityPercent = clampPercent(percent);
        invalidate();
    }

    public void setPhysicalKey(int keyCode, boolean pressed) {
        if (!containsKey(keyCode)) return;
        boolean wasPressed = held.get(keyCode);
        if (pressed) held.put(keyCode, true);
        else held.delete(keyCode);
        if (wasPressed != pressed) {
            startFillTransition(keyCode, pressed, SystemClock.uptimeMillis());
        }
        invalidate();
    }

    public void flashKey(int keyCode) {
        if (!containsKey(keyCode)) return;
        long now = SystemClock.uptimeMillis();
        flashUntil.put(keyCode, now + FLASH_MS);
        startFillTransition(keyCode, true, now);
        invalidate();
    }

    public void clearPressed() {
        held.clear();
        flashUntil.clear();
        fillValue.clear();
        fillFrom.clear();
        fillTo.clear();
        fillStartedAt.clear();
        fillDurationMs.clear();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        centreFillActive = false;
        float pad = dp(PANEL_PADDING_DP);
        float gap = dp(KEY_GAP_DP);
        float rowHeight = (getHeight() - pad * 2f - gap * (ROWS.length - 1)) / ROWS.length;
        if (rowHeight <= 1f) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withOpacity(UiPalette.overlayShell(getContext()), backgroundOpacityPercent));
        rect.set(0f, 0f, getWidth(), getHeight());
        canvas.drawRoundRect(rect, dp(PANEL_RADIUS_DP), dp(PANEL_RADIUS_DP), paint);

        float y = pad;
        for (KeySpec[] row : ROWS) {
            drawRow(canvas, row, y, rowHeight, pad, gap, now);
            y += rowHeight + gap;
        }
        if (centreFillActive) postInvalidateOnAnimation();
    }

    private void drawRow(Canvas canvas, KeySpec[] row, float y, float height,
                         float pad, float gap, long now) {
        float totalWeight = 0f;
        for (KeySpec key : row) totalWeight += key.weight;
        float available = getWidth() - pad * 2f - gap * (row.length - 1);
        float unit = Math.max(1f, available / totalWeight);
        float x = pad;

        for (KeySpec key : row) {
            float width = unit * key.weight;
            boolean pressed = held.get(key.code) || flashUntil.get(key.code, 0L) > now;
            rect.set(x, y, x + width, y + height);
            float radius = KeyAppearance.roundedRadius(rect, cornerStrength);

            ensureFillTarget(key.code, pressed, now);
            float fillProgress = evaluateFill(key.code, now);
            float bounce = KeyAppearance.cardFeatureBounce(fillProgress);
            int keySave = canvas.save();
            canvas.scale(bounce, bounce, rect.centerX(), rect.centerY());

            // Matrix Card UI state animation: stable idle surface + centered key-shaped state fill.
            paint.setColor(withOpacity(baseColor, backgroundOpacityPercent));
            KeyAppearance.drawShape(canvas, rect, keyStyle, radius, paint);
            if (fillProgress > 0f) {
                KeyAppearance.drawCentreFill(canvas, rect, keyStyle, radius,
                        withOpacity(pressColor, diffusionOpacityPercent),
                        fillProgress, paint, fillClipPath);
            }
            if (fillStartedAt.get(key.code, 0L) > 0L || flashUntil.get(key.code, 0L) > now) {
                centreFillActive = true;
            }

            strokePaint.setColor(withOpacity(borderColor, strokeOpacityPercent));
            KeyAppearance.drawShape(canvas, rect, keyStyle, radius, strokePaint);

            int keyText = KeyAppearance.blendColor(
                    UiPalette.overlayTextIdle(getContext()),
                    KeyAppearance.pressedTextColor(pressColor),
                    KeyAppearance.centreTextMix(fillProgress));
            paint.setColor(withOpacity(keyText, textOpacityPercent));
            paint.setTypeface(FontManager.normal(getContext()));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(dp(7f), Math.min(height * 0.36f, dp(12f))));
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = y + height * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(key.label, x + width * 0.5f, baseline, paint);
            canvas.restoreToCount(keySave);
            x += width + gap;
        }
    }

    private void ensureFillTarget(int keyCode, boolean enabledNow, long now) {
        float expected = enabledNow ? 1f : 0f;
        float currentTarget = fillTo.get(keyCode, fillValue.get(keyCode, 0f));
        if (Math.abs(currentTarget - expected) > 0.001f) {
            startFillTransition(keyCode, enabledNow, now);
        }
    }

    private void startFillTransition(int keyCode, boolean enabledNow, long now) {
        float current = evaluateFill(keyCode, now);
        float end = enabledNow ? 1f : 0f;
        fillValue.put(keyCode, current);
        fillFrom.put(keyCode, current);
        fillTo.put(keyCode, end);
        long duration = KeyAppearance.cardFeatureToggleDuration(current, end);
        if (duration <= 0L) {
            fillValue.put(keyCode, end);
            fillFrom.put(keyCode, end);
            fillStartedAt.delete(keyCode);
            fillDurationMs.delete(keyCode);
            return;
        }
        fillStartedAt.put(keyCode, now);
        fillDurationMs.put(keyCode, duration);
    }

    private float evaluateFill(int keyCode, long now) {
        float value = fillValue.get(keyCode, 0f);
        long start = fillStartedAt.get(keyCode, 0L);
        long duration = fillDurationMs.get(keyCode, 0L);
        if (start <= 0L || duration <= 0L) return clamp01(value);
        float linear = (now - start) / (float) duration;
        float end = fillTo.get(keyCode, value);
        if (linear >= 1f) {
            fillValue.put(keyCode, end);
            fillFrom.put(keyCode, end);
            fillStartedAt.delete(keyCode);
            fillDurationMs.delete(keyCode);
            return clamp01(end);
        }
        float from = fillFrom.get(keyCode, value);
        if (linear <= 0f) return clamp01(from);
        float eased = KeyAppearance.cardFeatureToggleEase(linear);
        value = from + (end - from) * eased;
        fillValue.put(keyCode, value);
        return clamp01(value);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private boolean containsKey(int keyCode) {
        for (KeySpec[] row : ROWS) {
            for (KeySpec key : row) if (key.code == keyCode) return true;
        }
        return false;
    }

    private static int withOpacity(int color, int percent) {
        int alpha = Math.round(Color.alpha(color) * clampPercent(percent) / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int clampPercent(int value) { return Math.max(0, Math.min(100, value)); }

    private float dp(float value) { return value * density; }

    private static KeySpec k(int code, String label) { return new KeySpec(code, label); }
    private static KeySpec k(int code, String label, float weight) { return new KeySpec(code, label, weight); }
}
