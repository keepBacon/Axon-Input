package com.axon.input;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** 键盘、鼠标和自定义键位的悬浮窗容器。默认使用 Canvas。HTML 模式启用时才创建 WebView。 */
public final class KeyOverlayView extends FrameLayout {
    public static final int DISPLAY_KEYBOARD = 1;
    public static final int DISPLAY_CUSTOM = 2;
    public static final int DISPLAY_MOUSE = 3;

    public interface DragListener {
        void onDragStart(KeyOverlayView source, float rawX, float rawY);
        void onDragMove(KeyOverlayView source, float rawX, float rawY);
        void onDragEnd(KeyOverlayView source);
    }

    private final int displayType;
    private final NativeKeyCanvasView nativeView;
    private GlobalHtmlWebView htmlView;

    private int displaySizePercent = 100;
    private int pressedMask;
    private boolean keyboardShowSpace = true;
    private boolean keyboardShowSpaceDps;
    private int keyboardSpaceDps;
    private long mouseStats;
    private int[] customKeyCodes = new int[0];
    private boolean[] customPressed = new boolean[0];
    private int customColumns = 4;

    private DragListener dragListener;
    private boolean dragEnabled;
    private boolean dragging;

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
            stepHostMotion();
        }
    };

    public KeyOverlayView(Context context, int displayType) {
        super(context);
        if (displayType < DISPLAY_KEYBOARD || displayType > DISPLAY_MOUSE) {
            throw new IllegalArgumentException("Unknown display type: " + displayType);
        }
        this.displayType = displayType;
        setClipChildren(false);
        setClipToPadding(false);

        nativeView = new NativeKeyCanvasView(context, displayType);
        nativeView.setDragEnabled(false);
        addView(nativeView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        setScaleX(0.92f);
        setScaleY(0.92f);
        postFrame();
    }

    public int getDisplayType() { return displayType; }

    public void setDragListener(DragListener listener) { dragListener = listener; }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled && dragging) finishDrag();
    }

    public void setAnimationMode(int mode) {
        nativeView.setAnimationMode(OverlayState.clampMotionMode(mode));
    }

    public void setKeyAppearance(int style, int color) {
        nativeView.setKeyAppearance(style, color);
    }

    public void setKeyColors(int idleColor, int textColor) {
        nativeView.setKeyColors(idleColor, textColor);
    }

    public void setKeyEffects(int cornerScalePercent, int rippleStrengthPercent) {
        nativeView.setKeyEffects(cornerScalePercent, rippleStrengthPercent);
    }

    public void setKeySpacing(int spacingDp) {
        if (displayType == DISPLAY_KEYBOARD || displayType == DISPLAY_CUSTOM) {
            nativeView.setKeySpacing(spacingDp);
        }
    }

    public void setGlobalHtmlRenderer(boolean enabled, String html) {
        boolean usable = enabled && html != null && !html.trim().isEmpty();
        if (!usable) {
            destroyHtmlView();
            nativeView.setVisibility(View.VISIBLE);
            return;
        }
        GlobalHtmlWebView web = ensureHtmlView();
        nativeView.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadRendererHtml(html);
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

    public void setDisplaySize(int percent) {
        displaySizePercent = Math.max(50, Math.min(150, percent));
        nativeView.setDisplaySize(displaySizePercent);
        if (htmlView != null) htmlView.setDisplaySize(displaySizePercent);
    }

    public void setCustomKeys(int[] keyCodes, int columns) {
        customKeyCodes = keyCodes == null ? new int[0] : keyCodes.clone();
        customColumns = Math.max(1, Math.min(8, columns));
        customPressed = new boolean[customKeyCodes.length];
        nativeView.setCustomKeys(customKeyCodes, customColumns);
        if (htmlView != null) htmlView.setCustomKeys(customKeyCodes, customColumns);
    }

    public void setKeyboardOptions(boolean showSpace, boolean showSpaceDps) {
        if (displayType != DISPLAY_KEYBOARD) return;
        keyboardShowSpace = showSpace;
        keyboardShowSpaceDps = showSpace && showSpaceDps;
        nativeView.setKeyboardOptions(keyboardShowSpace, keyboardShowSpaceDps);
        if (htmlView != null) htmlView.setKeyboardOptions(keyboardShowSpace, keyboardShowSpaceDps);
    }

    public void setKeyboardDps(int dps) {
        if (displayType != DISPLAY_KEYBOARD) return;
        keyboardSpaceDps = Math.max(0, Math.min(999, dps));
        nativeView.setKeyboardDps(keyboardSpaceDps);
        if (htmlView != null) htmlView.setKeyboardDps(keyboardSpaceDps);
    }

    public void setPressedMask(int mask) {
        pressedMask = mask;
        nativeView.setPressedMask(mask);
        if (htmlView != null) htmlView.setKeyboardMask(mask);
    }

    public void setCustomKeyPressed(int keyCode, boolean pressed) {
        for (int i = 0; i < customKeyCodes.length; i++) {
            if (customKeyCodes[i] == keyCode) {
                customPressed[i] = pressed;
                break;
            }
        }
        nativeView.setCustomKeyPressed(keyCode, pressed);
        if (htmlView != null) htmlView.setCustomKeyPressed(keyCode, pressed);
    }

    public void releaseCustomKeys() {
        for (int i = 0; i < customPressed.length; i++) customPressed[i] = false;
        nativeView.releaseCustomKeys();
        if (htmlView != null) htmlView.releaseCustomKeys();
    }

    public void setMouseStats(long stats) {
        mouseStats = stats;
        nativeView.setMouseStats(stats);
        if (htmlView != null) htmlView.setMouseStats(stats);
    }

    public void releaseAll() {
        nativeView.releaseAll();
        if (displayType == DISPLAY_KEYBOARD) {
            pressedMask = 0;
            if (htmlView != null) htmlView.setKeyboardMask(0);
        } else if (displayType == DISPLAY_MOUSE) {
            mouseStats = 0L;
            if (htmlView != null) htmlView.setMouseStats(0L);
        } else {
            releaseCustomKeys();
        }
    }

    private GlobalHtmlWebView ensureHtmlView() {
        if (htmlView != null) return htmlView;
        String type = displayType == DISPLAY_MOUSE ? GlobalHtmlWebView.TYPE_MOUSE
                : displayType == DISPLAY_CUSTOM ? GlobalHtmlWebView.TYPE_CUSTOM
                : GlobalHtmlWebView.TYPE_KEYBOARD;
        GlobalHtmlWebView web = new GlobalHtmlWebView(getContext(), type);
        web.setDisplaySize(displaySizePercent);
        if (displayType == DISPLAY_KEYBOARD) {
            web.setKeyboardOptions(keyboardShowSpace, keyboardShowSpaceDps);
            web.setKeyboardDps(keyboardSpaceDps);
            web.setKeyboardMask(pressedMask);
        } else if (displayType == DISPLAY_MOUSE) {
            web.setMouseStats(mouseStats);
        } else {
            web.setCustomKeys(customKeyCodes, customColumns);
            for (int i = 0; i < customKeyCodes.length; i++) {
                if (customPressed[i]) web.setCustomKeyPressed(customKeyCodes[i], true);
            }
        }
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

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) { return dragEnabled; }

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

    private void stepHostMotion() {
        long now = SystemClock.uptimeMillis();
        if (lastFrameMs == 0L) lastFrameMs = now;
        float dt = Math.min(0.024f, Math.max(0.001f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;

        stepSpring(windowProgress, windowVelocity, windowTarget, dt, 330f, 29f);
        windowProgress = springValue;
        windowVelocity = springVelocity;
        stepSpring(dragProgress, dragVelocity, dragging ? 1f : 0f, dt, 430f, 35f);
        dragProgress = springValue;
        dragVelocity = springVelocity;

        float baseScale = 0.92f + 0.08f * clamp(windowProgress, 0f, 1.06f);
        float pressScale = 1f - 0.016f * clamp(dragProgress, 0f, 1.06f);
        float scale = baseScale * pressScale;
        setPivotX(getWidth() * 0.5f);
        setPivotY(getHeight() * 0.5f);
        setScaleX(scale);
        setScaleY(scale);

        boolean windowActive = Math.abs(windowTarget - windowProgress) >= 0.0015f || Math.abs(windowVelocity) >= 0.02f;
        boolean dragActive = Math.abs((dragging ? 1f : 0f) - dragProgress) >= 0.0015f || Math.abs(dragVelocity) >= 0.02f;

        if (windowTarget == 0f && !windowActive && exitCallback != null) {
            Runnable callback = exitCallback;
            exitCallback = null;
            post(callback);
            return;
        }
        if (windowActive || dragActive) postFrame();
    }

    private void stepSpring(float value, float velocity, float target,
                            float dt, float stiffness, float damping) {
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

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
