package com.axon.input;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** User-selected light / black palette shared by app settings and overlays. */
public final class UiPalette {
    private UiPalette() {}

    public static int background(Context context) {
        return dark(context) ? Color.rgb(8, 8, 9) : Color.rgb(247, 247, 248);
    }

    public static int surface(Context context) {
        return dark(context) ? Color.rgb(13, 13, 15) : Color.rgb(250, 250, 251);
    }

    /** Slightly raised surface for nested controls, without a visible border. */
    public static int surfaceRaised(Context context) {
        return dark(context) ? Color.rgb(17, 17, 19) : Color.rgb(248, 248, 249);
    }

    /** Quiet control background for buttons, selectors and detail rows. */
    public static int controlSurface(Context context) {
        return dark(context) ? Color.rgb(29, 29, 32) : Color.rgb(239, 239, 241);
    }

    public static int debugSurface(Context context) {
        return dark(context) ? Color.rgb(22, 22, 24) : Color.rgb(246, 246, 247);
    }

    public static int textPrimary(Context context) {
        return dark(context) ? Color.rgb(244, 244, 245) : Color.rgb(21, 21, 23);
    }

    public static int textSecondary(Context context) {
        return dark(context) ? Color.rgb(158, 158, 165) : Color.rgb(105, 105, 112);
    }

    public static int textTertiary(Context context) {
        return dark(context) ? Color.rgb(116, 116, 123) : Color.rgb(137, 137, 145);
    }

    public static int divider(Context context) {
        return dark(context) ? Color.argb(38, 255, 255, 255) : Color.argb(24, 0, 0, 0);
    }

    public static int accent(Context context) {
        return dark(context) ? Color.rgb(244, 244, 245) : Color.rgb(23, 23, 25);
    }

    /** UI 库里的交互蓝，只用于设置控件，不改变按显/Overlay 本身的主题色语义。 */
    public static int controlAccent(Context context) {
        return Color.rgb(10, 132, 255);
    }

    public static int switchTrackOff(Context context) {
        return dark(context) ? Color.rgb(34, 34, 38) : Color.WHITE;
    }

    public static int switchThumbOff(Context context) {
        return Color.rgb(173, 181, 189);
    }

    public static int switchTrackOn(Context context) {
        return controlAccent(context);
    }

    public static int switchThumbOn(Context context) {
        return Color.WHITE;
    }

    public static int glassPanel(Context context) {
        return dark(context) ? Color.argb(196, 36, 36, 38) : Color.argb(210, 255, 255, 255);
    }

    public static int glassControl(Context context) {
        return dark(context) ? Color.argb(22, 255, 255, 255) : Color.argb(148, 217, 217, 217);
    }

    public static int glassControlRaised(Context context) {
        return dark(context) ? Color.argb(36, 255, 255, 255) : Color.argb(186, 224, 224, 226);
    }

    public static int glassBorder(Context context) {
        return dark(context) ? Color.argb(24, 255, 255, 255) : Color.argb(158, 255, 255, 255);
    }

    public static int ripple(Context context) {
        return dark(context) ? Color.argb(28, 255, 255, 255) : Color.argb(18, 0, 0, 0);
    }

    public static int overlayKeyIdle(Context context) {
        return dark(context) ? Color.argb(210, 24, 24, 27) : Color.argb(239, 255, 255, 255);
    }

    public static int overlayKeyPressed(Context context) {
        return dark(context) ? Color.WHITE : Color.rgb(23, 23, 25);
    }

    public static int overlayTextIdle(Context context) {
        return dark(context) ? Color.rgb(235, 235, 238) : Color.rgb(23, 23, 25);
    }

    public static int overlayTextPressed(Context context) {
        return dark(context) ? Color.rgb(16, 16, 18) : Color.WHITE;
    }

    public static int overlayStroke(Context context) {
        return dark(context) ? Color.argb(36, 255, 255, 255) : Color.argb(31, 0, 0, 0);
    }

    public static int overlayShell(Context context) {
        return dark(context) ? Color.argb(176, 10, 10, 12) : Color.argb(230, 255, 255, 255);
    }

    public static int overlaySecondary(Context context) {
        return dark(context) ? Color.rgb(174, 174, 180) : Color.rgb(112, 112, 120);
    }

    public static int trajectoryPanel(Context context) {
        return dark(context) ? Color.argb(226, 20, 20, 23) : Color.argb(238, 255, 255, 255);
    }

    public static int trajectoryStroke(Context context) {
        return dark(context) ? Color.argb(34, 255, 255, 255) : Color.argb(28, 0, 0, 0);
    }

    public static int trajectoryDot(Context context) {
        return dark(context) ? Color.WHITE : Color.rgb(23, 23, 25);
    }

    public static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * context.getResources().getDisplayMetrics().density);
        return drawable;
    }

    private static boolean dark(Context context) {
        return OverlayState.getUiTheme(context) == OverlayState.UI_THEME_BLACK;
    }
}
