package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** 输入时显示的只读全键盘。 */
public final class FullKeyboardOverlayView extends View {
    private static final long FLASH_MS = 140L;
    private static final float KEY_GAP_DP = 3f;
    private static final float PANEL_PADDING_DP = 6f;
    private static final float KEY_RADIUS_DP = 5f;
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
    private final RectF rect = new RectF();
    private final SparseBooleanArray held = new SparseBooleanArray();
    private final SparseLongArray flashUntil = new SparseLongArray();
    private final float density;

    public FullKeyboardOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        paint.setTypeface(FontManager.normal(context));
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setPhysicalKey(int keyCode, boolean pressed) {
        if (!containsKey(keyCode)) return;
        if (pressed) held.put(keyCode, true);
        else held.delete(keyCode);
        invalidate();
    }

    public void flashKey(int keyCode) {
        if (!containsKey(keyCode)) return;
        long until = SystemClock.uptimeMillis() + FLASH_MS;
        flashUntil.put(keyCode, until);
        invalidate();
        postInvalidateDelayed(FLASH_MS + 8L);
    }

    public void clearPressed() {
        held.clear();
        flashUntil.clear();
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
        float pad = dp(PANEL_PADDING_DP);
        float gap = dp(KEY_GAP_DP);
        float rowHeight = (getHeight() - pad * 2f - gap * (ROWS.length - 1)) / ROWS.length;
        if (rowHeight <= 1f) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(UiPalette.overlayShell(getContext()));
        rect.set(0f, 0f, getWidth(), getHeight());
        canvas.drawRoundRect(rect, dp(PANEL_RADIUS_DP), dp(PANEL_RADIUS_DP), paint);

        float y = pad;
        for (KeySpec[] row : ROWS) {
            drawRow(canvas, row, y, rowHeight, pad, gap, now);
            y += rowHeight + gap;
        }
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
            paint.setColor(pressed
                    ? UiPalette.overlayKeyPressed(getContext())
                    : UiPalette.overlayKeyIdle(getContext()));
            rect.set(x, y, x + width, y + height);
            canvas.drawRoundRect(rect, dp(KEY_RADIUS_DP), dp(KEY_RADIUS_DP), paint);

            paint.setColor(pressed
                    ? UiPalette.overlayTextPressed(getContext())
                    : UiPalette.overlayTextIdle(getContext()));
            paint.setTypeface(FontManager.normal(getContext()));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(dp(7f), Math.min(height * 0.36f, dp(12f))));
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = y + height * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(key.label, x + width * 0.5f, baseline, paint);
            x += width + gap;
        }
    }

    private boolean containsKey(int keyCode) {
        for (KeySpec[] row : ROWS) {
            for (KeySpec key : row) if (key.code == keyCode) return true;
        }
        return false;
    }

    private float dp(float value) { return value * density; }

    private static KeySpec k(int code, String label) { return new KeySpec(code, label); }
    private static KeySpec k(int code, String label, float weight) { return new KeySpec(code, label, weight); }
}
