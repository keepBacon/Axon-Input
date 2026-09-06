package com.axon.input;

import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * 自由拖动只允许安全尺寸的悬浮窗接管触摸。
 * 过大的窗口如果继续保持可触摸，会因为 TYPE_ACCESSIBILITY_OVERLAY 覆盖应用本身，
 * 导致用户无法回到设置页关闭“自由拖动”。渲染尺寸不受这里限制，只暂停该窗口的拖动命中。
 */
final class OverlayDragSafety {
    private static final float MAX_TOUCH_DIMENSION_RATIO = 0.94f;
    private static final float MAX_TOUCH_AREA_RATIO = 0.70f;

    private OverlayDragSafety() {}

    static boolean allowDrag(boolean requested, DisplayMetrics metrics, int width, int height) {
        if (!requested || metrics == null || width <= 0 || height <= 0
                || metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            return false;
        }

        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        float widthRatio = width / (float) screenWidth;
        float heightRatio = height / (float) screenHeight;
        if (widthRatio >= MAX_TOUCH_DIMENSION_RATIO || heightRatio >= MAX_TOUCH_DIMENSION_RATIO) {
            return false;
        }

        long visibleWidth = Math.min((long) width, (long) screenWidth);
        long visibleHeight = Math.min((long) height, (long) screenHeight);
        float visibleAreaRatio = (visibleWidth * visibleHeight)
                / (float) ((long) screenWidth * (long) screenHeight);
        return visibleAreaRatio < MAX_TOUCH_AREA_RATIO;
    }

    static int windowFlags(boolean requestedDrag, DisplayMetrics metrics, int width, int height) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (!allowDrag(requestedDrag, metrics, width, height)) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        return flags;
    }
}
