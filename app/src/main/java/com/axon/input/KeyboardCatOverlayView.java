package com.axon.input;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.util.LinkedHashSet;

/**
 * BongoCat 源键盘猫悬浮层。
 *
 * 视觉资源直接使用 BongoCat keyboard model 的 moc3 / texture / key overlays；
 * WebView 中通过 Live2D Cubism Core 驱动原模型参数，Android 仅负责传递系统级输入。
 */
public final class KeyboardCatOverlayView extends FrameLayout {
    public static final int DISPLAY_KEYBOARD_CAT = 40;

    public interface DragListener {
        void onDragStart(KeyboardCatOverlayView source, float rawX, float rawY);
        void onDragMove(KeyboardCatOverlayView source, float rawX, float rawY);
        void onDragEnd(KeyboardCatOverlayView source);
    }

    private static final String KEYBOARD_PAGE_URL = "file:///android_asset/bongocat/keyboard/index.html";
    private static final String MOUSE_PAGE_URL = "file:///android_asset/bongocat/standard/index.html";

    private final WebView webView;
    private final SparseBooleanArray pressedKeyCodes = new SparseBooleanArray();
    private final LinkedHashSet<String> pressedKeyOrder = new LinkedHashSet<>();

    private DragListener dragListener;
    private boolean dragEnabled;
    private boolean dragging;
    private boolean pageReady;
    private boolean mouseMode;
    private boolean globalReverse;
    private int mouseButtons;
    private float dragStartRawX;
    private float dragStartRawY;
    private Runnable exitCallback;

    @SuppressLint({"SetJavaScriptEnabled", "ObsoleteSdkInt"})
    public KeyboardCatOverlayView(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
        setBackgroundColor(Color.TRANSPARENT);
        setClickable(true);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        webView = new WebView(context);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setDefaultTextEncodingName("utf-8");
        // 仅加载 APK 内可信的 BongoCat 资源；允许同一 file:// 页面读取 moc3 与纹理。
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                flushInputState();
            }
        });

        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mouseMode = OverlayState.isKeyboardCatMouseMode(context);
        globalReverse = OverlayState.isKeyboardCatGlobalReverse(context);
        loadCurrentMode();
    }

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    /**
     * false = BongoCat keyboard model (arrow-key block);
     * true  = BongoCat original standard model (mouse + mouse pad in the same region).
     */
    public void setMouseMode(boolean enabled) {
        if (mouseMode == enabled) return;
        mouseMode = enabled;
        loadCurrentMode();
    }

    private void loadCurrentMode() {
        pageReady = false;
        webView.stopLoading();
        webView.loadUrl(mouseMode ? MOUSE_PAGE_URL : KEYBOARD_PAGE_URL);
    }

    /**
     * Horizontal input reverse only: keep the Live2D cat and background unchanged, while
     * remapping keyboard/arrow targets and reversing only the mouse object's ParamMouseX.
     */
    public void setGlobalReverse(boolean enabled) {
        if (globalReverse == enabled) return;
        globalReverse = enabled;
        if (pageReady) flushInputState();
    }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled && dragging) finishDrag();
    }

    /** BongoCat 源窗口本身没有进入缩放动画；这里保持无额外视觉变形。 */
    public void animateIn() {
        exitCallback = null;
        animate().cancel();
        setScaleX(1f);
        setScaleY(1f);
    }

    public void animateOut(Runnable endAction) {
        animate().cancel();
        exitCallback = endAction;
        Runnable callback = exitCallback;
        exitCallback = null;
        if (callback != null) post(callback);
    }

    public void setKeyState(int keyCode, boolean pressed) {
        String key = sourceKeyName(keyCode);
        if (key == null) return;

        if (pressed) {
            if (!pressedKeyCodes.get(keyCode)) {
                pressedKeyCodes.put(keyCode, true);
                // LinkedHashSet 用于页面重新加载时按真实按下顺序恢复源行为。
                pressedKeyOrder.remove(key);
                pressedKeyOrder.add(key);
            }
        } else {
            pressedKeyCodes.delete(keyCode);
            pressedKeyOrder.remove(key);
        }

        dispatch("window.AxonBongoCat&&AxonBongoCat.key(" + JSONObject.quote(key) + "," + pressed + ")");
    }

    public void setMouseButtons(int buttons) {
        mouseButtons = buttons & 0x3;
        dispatch("window.AxonBongoCat&&AxonBongoCat.mouseButtons(" + mouseButtons + ")");
    }

    /**
     * Android 全局输入层提供 REL_X / REL_Y；将其累积为屏幕归一化光标，再交给源模型的
     * ParamAngle / ParamEyeBall 参数映射。这样保留 BongoCat 的 0.75 阻尼跟随逻辑。
     */
    public void addMouseMotion(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);
        dispatch("window.AxonBongoCat&&AxonBongoCat.mouseDelta(" + dx + "," + dy + "," + width + "," + height + ")");
    }

    public void clearInput() {
        pressedKeyCodes.clear();
        pressedKeyOrder.clear();
        mouseButtons = 0;
        dispatch("window.AxonBongoCat&&AxonBongoCat.clear()");
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
                dragStartRawX = event.getRawX();
                dragStartRawY = event.getRawY();
                if (dragListener != null) {
                    dragListener.onDragStart(this, dragStartRawX, dragStartRawY);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && dragListener != null) {
                    dragListener.onDragMove(this, event.getRawX(), event.getRawY());
                }
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

    private void flushInputState() {
        if (!pageReady) return;
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.clear()");
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.setGlobalReverse(" + globalReverse + ")");
        // BongoCat 源每个 left/right group 只保留最后一个按下贴图；按顺序重放即可恢复该语义。
        for (String key : pressedKeyOrder) {
            dispatchRaw("window.AxonBongoCat&&AxonBongoCat.key(" + JSONObject.quote(key) + ",true)");
        }
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.mouseButtons(" + mouseButtons + ")");
    }

    private void dispatch(String javascript) {
        if (!pageReady) return;
        dispatchRaw(javascript);
    }

    private void dispatchRaw(String javascript) {
        webView.evaluateJavascript(javascript, null);
    }

    /** rdev/BongoCat 资源名 -> Android KeyEvent 映射。仅映射源 keyboard model 实际支持的键。 */
    private static String sourceKeyName(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            char letter = (char) ('A' + (keyCode - KeyEvent.KEYCODE_A));
            return "Key" + letter;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            int digit = keyCode - KeyEvent.KEYCODE_0;
            return "Num" + digit;
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            // BongoCat 源在缺少独立 F 键贴图时统一回退到 Fn.png。
            return "Fn";
        }

        return switch (keyCode) {
            case KeyEvent.KEYCODE_ALT_LEFT -> "Alt";
            case KeyEvent.KEYCODE_ALT_RIGHT -> "AltGr";
            case KeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft";
            case KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight";
            case KeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft";
            case KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight";
            case KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> "Meta";
            case KeyEvent.KEYCODE_GRAVE -> "BackQuote";
            case KeyEvent.KEYCODE_DEL -> "Backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL -> "Delete";
            case KeyEvent.KEYCODE_CAPS_LOCK -> "CapsLock";
            case KeyEvent.KEYCODE_ESCAPE -> "Escape";
            case KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Return";
            case KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "Slash";
            case KeyEvent.KEYCODE_SPACE -> "Space";
            case KeyEvent.KEYCODE_TAB -> "Tab";
            case KeyEvent.KEYCODE_DPAD_UP -> "UpArrow";
            case KeyEvent.KEYCODE_DPAD_DOWN -> "DownArrow";
            case KeyEvent.KEYCODE_DPAD_LEFT -> "LeftArrow";
            case KeyEvent.KEYCODE_DPAD_RIGHT -> "RightArrow";
            default -> null;
        };
    }
}
