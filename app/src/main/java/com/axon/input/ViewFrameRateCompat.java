package com.axon.input;

import android.content.Context;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * API 35+ 的 View 帧率偏好兼容层。
 *
 * Axon 的透明 WebView 只是 Overlay 内容 Surface，不应该用自身的 60fps 内容节奏去限制
 * 整块屏幕的刷新率。这里通过反射请求 NO_PREFERENCE，旧系统完全无操作，同时避免
 * 再次引入高 API 符号的编译兼容问题。
 */
final class ViewFrameRateCompat {
    private static volatile boolean resolved;
    private static Method setRequestedFrameRate;
    private static float noPreference;

    private ViewFrameRateCompat() {}

    static void applyNoPreference(View view) {
        if (view == null) return;
        resolve();
        Method method = setRequestedFrameRate;
        if (method == null) return;
        try {
            method.invoke(view, noPreference);
        } catch (Throwable ignored) {
            // OEM Framework/WebView 实现异常时退回系统默认调度，不影响 Overlay 创建。
        }
    }

    /**
     * Accessibility Overlay 只对齐“当前正在使用”的显示刷新率。
     * 不枚举最高 Mode，也不在拖动/relayout 热路径里重复修改窗口属性。
     * 这样高刷提示与 Cubism Renderer、Overlay 生命周期完全解耦。
     */
    static void preferCurrentDisplayRefresh(Context context, WindowManager.LayoutParams params) {
        if (context == null || params == null) return;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm == null ? null : wm.getDefaultDisplay();
            if (display == null) return;
            float current = display.getRefreshRate();
            if (current > 0f) params.preferredRefreshRate = current;
        } catch (Throwable ignored) {
            // 设备策略/省电模式可以覆盖这个 hint；失败时保持系统默认。
        }
    }

    private static void resolve() {
        if (resolved) return;
        synchronized (ViewFrameRateCompat.class) {
            if (resolved) return;
            try {
                Method method = View.class.getMethod("setRequestedFrameRate", float.class);
                Field field = View.class.getField("REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE");
                setRequestedFrameRate = method;
                noPreference = field.getFloat(null);
            } catch (Throwable ignored) {
                setRequestedFrameRate = null;
                noPreference = 0f;
            }
            resolved = true;
        }
    }
}
