package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/** 最近按键提示。原生模式显示最近六个输入和 CPS。HTML 模式接收相同输入状态。 */
public final class KeyPromptOverlayView extends FrameLayout {
    public static final int DISPLAY_KEY_PROMPT = 20;

    private static final int MAX_KEYS = 6;
    private static final long RELEASE_HOLD_MS = 1_000L;
    private static final long FLASH_MS = 115L;
    private static final int MOUSE_ID_BASE = 0x10000;
    private static final float KEY_SIZE_DP = 46f;
    private static final float GAP_DP = 8f;
    private static final float CPS_SPACE_DP = 16f;

    public interface DragListener {
        void onDragStart(KeyPromptOverlayView source, float rawX, float rawY);
        void onDragMove(KeyPromptOverlayView source, float rawX, float rawY);
        void onDragEnd(KeyPromptOverlayView source);
    }

    private static final class Entry {
        final int id;
        String label;
        boolean pressed;
        long releaseAt;
        long lastActivityAt;
        long flashUntil;
        float fillProgress;
        float fillFrom;
        float fillTo;
        long fillStartedAt;
        long fillDurationMs;
        int pressCount;
        float reveal;
        float centerX = Float.NaN;
        final long[] pressTimes = new long[32];
        int pressHead;
        int pressSize;

        Entry(int id, String label, long now) {
            this.id = id;
            this.label = label;
            this.lastActivityAt = now;
        }

        void addPress(long now) {
            if (pressSize < pressTimes.length) {
                pressTimes[(pressHead + pressSize) % pressTimes.length] = now;
                pressSize++;
            } else {
                pressTimes[pressHead] = now;
                pressHead = (pressHead + 1) % pressTimes.length;
            }
        }

        boolean prune(long cutoff) {
            boolean changed = false;
            while (pressSize > 0 && pressTimes[pressHead] < cutoff) {
                pressHead = (pressHead + 1) % pressTimes.length;
                pressSize--;
                changed = true;
            }
            return changed;
        }
    }

    private final List<Entry> entries = new ArrayList<>(MAX_KEYS);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF rect = new RectF();
    private final Path fillClipPath = new Path();
    private final float density;
    private final Typeface typefaceNormal;
    private final Typeface typefaceBold;

    private DragListener dragListener;
    private int displaySizePercent = 100;
    private boolean dragEnabled;
    private boolean dragging;
    private long lastFrameMs;
    private boolean framePosted;
    private float hostProgress;

    private int backgroundOpacityPercent = 100;
    private int strokeOpacityPercent = 100;
    private int textOpacityPercent = 100;
    private int diffusionOpacityPercent = 100;
    private float hostVelocity;
    private float hostTarget = 1f;
    private Runnable exitCallback;
    private GlobalHtmlWebView htmlView;
    private boolean globalHtmlEnabled;
    private int keyStyle = KeyAppearance.STYLE_ROUNDED;
    private int cornerStrength = KeyAppearance.DEFAULT_CORNER_STRENGTH;
    private int baseColor;
    private int borderColor;
    private int pressColor;

    private final Runnable frameRunnable = new Runnable() {
        @Override public void run() {
            framePosted = false;
            stepFrame();
        }
    };

    public KeyPromptOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        typefaceNormal = FontManager.normal(context);
        typefaceBold = FontManager.bold(context);
        baseColor = UiPalette.overlayKeyIdle(context);
        borderColor = UiPalette.overlayStroke(context);
        pressColor = UiPalette.overlayKeyPressed(context);
        setWillNotDraw(false);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        textPaint.setTextAlign(Paint.Align.CENTER);
        hostProgress = 0f;
        setScaleX(0.97f);
        setScaleY(0.97f);
        setAlpha(0f);
        postFrame();
    }

    public void setDisplaySize(int percent) {
        displaySizePercent = Math.max(25, Math.min(300, percent));
        if (htmlView != null) htmlView.setDisplaySize(displaySizePercent);
        invalidate();
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

    public void setGlobalHtmlRenderer(boolean enabled, String html) {
        boolean usable = enabled && html != null && !html.trim().isEmpty();
        globalHtmlEnabled = usable;
        if (!usable) {
            destroyHtmlView();
            invalidate();
            return;
        }
        GlobalHtmlWebView web = ensureHtmlView();
        web.setDisplaySize(displaySizePercent);
        syncHtmlPromptState();
        web.loadRendererHtml(html);
        invalidate();
    }

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled && dragging) finishDrag();
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

    public void updateKeyboardKey(int keyCode, boolean pressed, boolean countPress, long now) {
        updateInput(keyCode, labelForKey(keyCode), pressed, countPress, now);
    }

    public void updateMouseButton(int button, boolean pressed, long now) {
        String label;
        switch (button) {
            case NativeKeyEngine.MOUSE_LEFT: label = "LMB"; break;
            case NativeKeyEngine.MOUSE_RIGHT: label = "RMB"; break;
            case MouseInputMonitor.BUTTON_MIDDLE: label = "MMB"; break;
            case MouseInputMonitor.BUTTON_BACK: label = "M4"; break;
            case MouseInputMonitor.BUTTON_FORWARD: label = "M5"; break;
            default: label = "MOUSE"; break;
        }
        updateInput(MOUSE_ID_BASE | button, label, pressed, pressed, now);
    }

    public void clearAll() {
        entries.clear();
        lastFrameMs = 0L;
        syncHtmlPromptState();
        invalidate();
    }

    private void updateInput(int id, String label, boolean pressed, boolean countPress, long now) {
        Entry entry = findEntry(id);
        if (pressed) {
            if (entry == null) {
                if (entries.size() >= MAX_KEYS) evictEntry();
                entry = new Entry(id, label, now);
                entries.add(entries.size() / 2, entry);
            }
            boolean wasPressed = entry.pressed;
            entry.label = label;
            entry.pressed = true;
            entry.releaseAt = 0L;
            entry.lastActivityAt = now;
            if (countPress && !wasPressed) {
                entry.pressCount++;
                entry.addPress(now);
                entry.prune(now - 1_000L);
                entry.flashUntil = now + FLASH_MS;
                startEntryFillTransition(entry, true, now);
            }
        } else if (entry != null && entry.pressed) {
            entry.pressed = false;
            entry.releaseAt = now;
            entry.lastActivityAt = now;
            startEntryFillTransition(entry, false, now);
        }
        if (htmlView != null && entry != null) {
            // GlobalHtmlWebView 会自动区分列表结构变化与单键状态变化，避免重复推送。
            syncHtmlPromptState();
        }
        postFrame();
    }

    private void startEntryFillTransition(Entry entry, boolean enabledNow, long now) {
        if (entry == null) return;
        float current = evaluateEntryFill(entry, now);
        float end = enabledNow ? 1f : 0f;
        entry.fillProgress = current;
        entry.fillFrom = current;
        entry.fillTo = end;
        long duration = KeyAppearance.cardFeatureToggleDuration(current, end);
        if (duration <= 0L) {
            entry.fillProgress = end;
            entry.fillFrom = end;
            entry.fillStartedAt = 0L;
            entry.fillDurationMs = 0L;
            return;
        }
        entry.fillStartedAt = now;
        entry.fillDurationMs = duration;
    }

    private float evaluateEntryFill(Entry entry, long now) {
        if (entry == null || entry.fillStartedAt <= 0L || entry.fillDurationMs <= 0L) {
            return clamp01(entry == null ? 0f : entry.fillProgress);
        }
        float linear = (now - entry.fillStartedAt) / (float) entry.fillDurationMs;
        if (linear >= 1f) {
            entry.fillProgress = entry.fillTo;
            entry.fillFrom = entry.fillTo;
            entry.fillStartedAt = 0L;
            entry.fillDurationMs = 0L;
            return clamp01(entry.fillProgress);
        }
        if (linear <= 0f) return clamp01(entry.fillFrom);
        float eased = KeyAppearance.cardFeatureToggleEase(linear);
        entry.fillProgress = entry.fillFrom + (entry.fillTo - entry.fillFrom) * eased;
        return clamp01(entry.fillProgress);
    }

    private Entry findEntry(int id) {
        for (Entry entry : entries) if (entry.id == id) return entry;
        return null;
    }

    private void evictEntry() {
        if (entries.isEmpty()) return;
        int best = -1;
        long oldest = Long.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.pressed && entry.lastActivityAt < oldest) {
                best = i;
                oldest = entry.lastActivityAt;
            }
        }
        if (best < 0) {
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                if (entry.lastActivityAt < oldest) {
                    best = i;
                    oldest = entry.lastActivityAt;
                }
            }
        }
        if (best >= 0) entries.remove(best);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (globalHtmlEnabled) return;
        long now = SystemClock.uptimeMillis();
        drawPrompt(canvas, now);
    }

    private void drawPrompt(Canvas canvas, long now) {
        if (entries.isEmpty()) return;

        float uiScale = displaySizePercent / 100f;
        float keySize = dp(KEY_SIZE_DP) * uiScale;
        float gap = dp(GAP_DP) * uiScale;
        float cpsSpace = dp(CPS_SPACE_DP) * uiScale;
        int count = Math.min(MAX_KEYS, entries.size());
        float width = keySize * count + gap * Math.max(0, count - 1);
        float left = (getWidth() - width) * 0.5f;
        float top = Math.max(0f, (getHeight() - (keySize + cpsSpace)) * 0.5f);
        float groupCenter = getWidth() * 0.5f;

        int idleColor = baseColor;
        int idleText = UiPalette.overlayTextIdle(getContext());
        int pressedText = KeyAppearance.pressedTextColor(pressColor);

        textPaint.setTypeface(typefaceBold);
        textPaint.setTextSize(dp(12f) * uiScale);
        for (int i = 0; i < count; i++) {
            Entry entry = entries.get(i);
            float targetX = left + keySize * 0.5f + i * (keySize + gap);
            if (Float.isNaN(entry.centerX)) entry.centerX = groupCenter;

            float reveal = clamp01(entry.reveal);
            float eased = 1f - (float) Math.pow(1f - reveal, 3f);
            float itemScale = 0.62f + 0.38f * eased;
            float yOffset = dp(24f) * uiScale * (1f - eased);
            float centerY = top + keySize * 0.5f + yOffset;
            float half = keySize * 0.5f;
            rect.set(entry.centerX - half, centerY - half, entry.centerX + half, centerY + half);
            int alpha = Math.round(255f * eased);
            int backgroundAlpha = Math.round(alpha * backgroundOpacityPercent / 100f);
            int diffusionAlpha = Math.round(alpha * diffusionOpacityPercent / 100f);
            int strokeAlpha = Math.round(alpha * strokeOpacityPercent / 100f);
            int textAlpha = Math.round(alpha * textOpacityPercent / 100f);
            float cornerRadius = KeyAppearance.roundedRadius(rect, cornerStrength);
            float fillProgress = evaluateEntryFill(entry, now);
            float stateBounce = KeyAppearance.cardFeatureBounce(fillProgress);

            int save = canvas.save();
            canvas.scale(itemScale * stateBounce, itemScale * stateBounce, entry.centerX, centerY);

            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(withAlpha(idleColor, backgroundAlpha));
            KeyAppearance.drawShape(canvas, rect, keyStyle, cornerRadius, fillPaint);
            if (fillProgress > 0f) {
                int solidPress = Color.argb(diffusionAlpha, Color.red(pressColor), Color.green(pressColor), Color.blue(pressColor));
                KeyAppearance.drawCentreFill(canvas, rect, keyStyle, cornerRadius, solidPress,
                        fillProgress, fillPaint, fillClipPath);
            }

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(Math.max(1f, dp(0.8f) * uiScale));
            int borderAlpha = Math.round(Color.alpha(borderColor) * strokeAlpha / 255f);
            strokePaint.setColor(withAlpha(borderColor, borderAlpha));
            KeyAppearance.drawShape(canvas, rect, keyStyle, cornerRadius, strokePaint);

            int keyText = KeyAppearance.blendColor(
                    idleText, pressedText, KeyAppearance.centreTextMix(fillProgress));
            textPaint.setColor(withAlpha(keyText, textAlpha));
            float baseline = centerY - (textPaint.ascent() + textPaint.descent()) * 0.5f;
            canvas.drawText(entry.label, entry.centerX, baseline, textPaint);
            canvas.restoreToCount(save);

            if (entry.pressCount >= 5) {
                textPaint.setTypeface(typefaceNormal);
                textPaint.setTextSize(dp(8.5f) * uiScale);
                textPaint.setColor(withAlpha(UiPalette.overlaySecondary(getContext()), Math.round(textAlpha * 0.78f)));
                canvas.drawText(entry.pressSize + " CPS", entry.centerX,
                        top + keySize + dp(11.5f) * uiScale, textPaint);
                textPaint.setTypeface(typefaceBold);
                textPaint.setTextSize(dp(12f) * uiScale);
            }
        }
    }

    private void stepFrame() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameMs == 0L ? 1f / 60f : Math.min(0.05f, Math.max(0.001f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;

        boolean moving = false;
        float hostBefore = hostProgress;
        float hostAccel = (hostTarget - hostProgress) * 105f - hostVelocity * 20.5f;
        hostVelocity += hostAccel * dt;
        hostProgress += hostVelocity * dt;
        if (hostTarget == 0f && hostProgress < 0f) hostProgress = 0f;
        if (hostTarget == 1f && hostProgress > 1f) hostProgress = 1f;
        if (Math.abs(hostTarget - hostProgress) < 0.001f && Math.abs(hostVelocity) < 0.02f) {
            hostProgress = hostTarget;
            hostVelocity = 0f;
        } else {
            moving = true;
        }
        if (hostBefore != hostProgress) {
            float eased = 1f - (float) Math.pow(1f - clamp01(hostProgress), 3f);
            float scale = 0.97f + 0.03f * eased;
            setScaleX(scale);
            setScaleY(scale);
            setAlpha(eased);
        }

        float revealIn = factor(dt, 7.0f);
        float revealOut = factor(dt, 5.5f);
        float position = factor(dt, 8.0f);
        boolean htmlStateChanged = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.prune(now - 1_000L)) htmlStateChanged = true;
            boolean held = entry.pressed || entry.releaseAt <= 0L || now - entry.releaseAt < RELEASE_HOLD_MS;
            float target = held ? 1f : 0f;
            float rf = target > entry.reveal ? revealIn : revealOut;
            float oldReveal = entry.reveal;
            entry.reveal += (target - entry.reveal) * rf;
            if (Math.abs(entry.reveal - oldReveal) > 0.0005f) moving = true;
            if (!held && entry.reveal < 0.008f) {
                entries.remove(i);
                htmlStateChanged = true;
                moving = true;
            }
        }
        if (htmlStateChanged) syncHtmlPromptState();

        if (!entries.isEmpty()) {
            float uiScale = displaySizePercent / 100f;
            float keySize = dp(KEY_SIZE_DP) * uiScale;
            float gap = dp(GAP_DP) * uiScale;
            int count = Math.min(MAX_KEYS, entries.size());
            float width = keySize * count + gap * Math.max(0, count - 1);
            float left = (getWidth() - width) * 0.5f;
            float groupCenter = getWidth() * 0.5f;
            for (int i = 0; i < count; i++) {
                Entry entry = entries.get(i);
                float targetX = left + keySize * 0.5f + i * (keySize + gap);
                if (Float.isNaN(entry.centerX)) entry.centerX = groupCenter;
                float oldX = entry.centerX;
                entry.centerX += (targetX - entry.centerX) * position;
                if (Math.abs(entry.centerX - oldX) > 0.05f) moving = true;
                if (entry.flashUntil > now
                        || entry.fillStartedAt > 0L
                        || (entry.releaseAt > 0 && now - entry.releaseAt < RELEASE_HOLD_MS)) {
                    moving = true;
                }
            }
        }

        invalidate();
        if (hostTarget == 0f && hostProgress <= 0.001f && Math.abs(hostVelocity) < 0.02f) {
            Runnable callback = exitCallback;
            exitCallback = null;
            if (callback != null) callback.run();
            return;
        }
        if (moving) postFrame();
        else lastFrameMs = 0L;
    }

    private void postFrame() {
        if (framePosted) return;
        framePosted = true;
        postOnAnimation(frameRunnable);
    }

    private GlobalHtmlWebView ensureHtmlView() {
        if (htmlView != null) return htmlView;
        GlobalHtmlWebView web = new GlobalHtmlWebView(getContext(), GlobalHtmlWebView.TYPE_KEY_PROMPT);
        web.setDisplaySize(displaySizePercent);
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

    private void syncHtmlPromptState() {
        GlobalHtmlWebView web = htmlView;
        if (web == null) return;
        int count = Math.min(MAX_KEYS, entries.size());
        int[] ids = new int[count];
        String[] labels = new String[count];
        boolean[] pressed = new boolean[count];
        int[] cps = new int[count];
        int[] pressCount = new int[count];
        for (int i = 0; i < count; i++) {
            Entry entry = entries.get(i);
            ids[i] = entry.id;
            labels[i] = entry.label;
            pressed[i] = entry.pressed;
            cps[i] = entry.pressCount >= 5 ? entry.pressSize : 0;
            pressCount[i] = entry.pressCount;
        }
        web.setPromptState(ids, labels, pressed, cps, pressCount);
    }

    @Override
    protected void onDetachedFromWindow() {
        destroyHtmlView();
        super.onDetachedFromWindow();
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
    }

    private float dp(float value) { return value * density; }

    private static float factor(float dt, float response) {
        return 1f - (float) Math.exp(-Math.max(0f, response) * Math.max(0f, dt));
    }

    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String labelForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return "SPACE";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_ESCAPE: return "ESC";
            case KeyEvent.KEYCODE_TAB: return "TAB";
            case KeyEvent.KEYCODE_DEL: return "BKSP";
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "SHIFT";
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return "CTRL";
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return "ALT";
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            default:
                String name = KeyEvent.keyCodeToString(keyCode);
                if (name == null || name.isEmpty()) return "KEY";
                if (name.startsWith("KEYCODE_")) name = name.substring(8);
                return name.length() > 8 ? name.substring(0, 8) : name;
        }
    }
}
