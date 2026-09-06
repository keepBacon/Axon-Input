package com.axon.input;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Shared visual primitives for the app UI. This class intentionally contains only styling;
 * behaviour and state stay in their owning screens.
 */
final class UiChrome {
    static final float DISABLED_ALPHA = 0.46f;
    static final float CARD_RADIUS_DP = 12f;
    static final float CONTROL_RADIUS_DP = 10f;
    static final float DIALOG_RADIUS_DP = 18f;

    private UiChrome() {}

    static GradientDrawable surface(Context context, int fillColor, float radiusDp) {
        GradientDrawable drawable = UiPalette.rounded(context, fillColor, radiusDp);
        int stroke = OverlayState.getUiTheme(context) == OverlayState.UI_THEME_BLACK
                ? Color.argb(26, 255, 255, 255)
                : Color.argb(18, 0, 0, 0);
        drawable.setStroke(dp(context, 1f), stroke);
        return drawable;
    }

    static GradientDrawable card(Context context) {
        // Cards are separated by surface contrast rather than visible outlines.
        return UiPalette.rounded(context, UiPalette.surface(context), CARD_RADIUS_DP);
    }

    /** 主页应用卡固定使用深色背景，和普通功能层形成更清楚但克制的层级。 */
    static GradientDrawable homePrimaryCard(Context context) {
        return UiPalette.rounded(context, Color.rgb(24, 25, 28), 14f);
    }

    /** 两个主页方块仍跟随当前主题，不额外加描边或阴影。 */
    static GradientDrawable homeSecondaryCard(Context context) {
        return UiPalette.rounded(context, UiPalette.controlSurface(context), 14f);
    }

    static GradientDrawable nestedSurface(Context context) {
        GradientDrawable drawable = UiPalette.rounded(context, UiPalette.glassPanel(context), 14f);
        drawable.setStroke(dp(context, 1f), UiPalette.glassBorder(context));
        return drawable;
    }

    static GradientDrawable popupSurface(Context context) {
        GradientDrawable drawable = UiPalette.rounded(context, UiPalette.glassPanel(context), 10f);
        drawable.setStroke(dp(context, 1f), UiPalette.glassBorder(context));
        return drawable;
    }

    /**
     * Static surface only. Native RippleDrawable timing varies by Android/OEM and competes with
     * the unified motion system, so interaction feedback is handled by UiMotion instead.
     */
    static Drawable ripple(Context context, int fillColor, float radiusDp) {
        return UiPalette.rounded(context, fillColor, radiusDp);
    }

    static Drawable controlRipple(Context context) {
        StateListDrawable content = new StateListDrawable();
        GradientDrawable focused = glassControlDrawable(context, true, false);
        focused.setStroke(dp(context, 1f), UiPalette.controlAccent(context));
        GradientDrawable normal = glassControlDrawable(context, false, false);
        content.addState(new int[]{android.R.attr.state_focused}, focused);
        content.addState(new int[]{}, normal);
        return content;
    }

    static Drawable primaryButtonBackground(Context context) {
        StateListDrawable content = new StateListDrawable();
        GradientDrawable focused = glassControlDrawable(context, true, true);
        focused.setStroke(dp(context, 1f), UiPalette.controlAccent(context));
        GradientDrawable normal = glassControlDrawable(context, false, true);
        content.addState(new int[]{android.R.attr.state_focused}, focused);
        content.addState(new int[]{}, normal);
        return content;
    }

    private static GradientDrawable glassControlDrawable(Context context, boolean raised, boolean primary) {
        int fill;
        if (primary) {
            fill = raised ? Color.argb(238, 10, 132, 255) : Color.argb(218, 10, 132, 255);
        } else {
            fill = raised ? UiPalette.glassControlRaised(context) : UiPalette.glassControl(context);
        }
        GradientDrawable drawable = UiPalette.rounded(context, fill, CONTROL_RADIUS_DP);
        drawable.setStroke(dp(context, 1f), UiPalette.glassBorder(context));
        return drawable;
    }


    static Drawable chevron(Context context) {
        ChevronDrawable drawable = new ChevronDrawable(UiPalette.textTertiary(context), dp(context, 16f));
        drawable.setBounds(0, 0, dp(context, 16f), dp(context, 16f));
        return drawable;
    }

    private static final class ChevronDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int intrinsicSize;

        ChevronDrawable(int color, int intrinsicSize) {
            this.intrinsicSize = intrinsicSize;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(1.5f, intrinsicSize * 0.095f));
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float half = Math.min(b.width(), b.height()) * 0.20f;
            canvas.drawLine(cx - half, cy - half * 0.45f, cx, cy + half * 0.55f, paint);
            canvas.drawLine(cx, cy + half * 0.55f, cx + half, cy - half * 0.45f, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return intrinsicSize; }
        @Override public int getIntrinsicHeight() { return intrinsicSize; }
    }

    static void styleSwitch(Context context, Switch view) {
        if (view == null) return;
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked, android.R.attr.state_enabled},
                new int[]{-android.R.attr.state_checked, android.R.attr.state_enabled},
                new int[]{android.R.attr.state_checked, -android.R.attr.state_enabled},
                new int[]{-android.R.attr.state_checked, -android.R.attr.state_enabled}
        };
        int onTrack = UiPalette.switchTrackOn(context);
        int offTrack = UiPalette.switchTrackOff(context);
        int onThumb = UiPalette.switchThumbOn(context);
        int offThumb = UiPalette.switchThumbOff(context);
        if (!(view instanceof MotionSwitch)) {
            view.setTrackTintList(new ColorStateList(states, new int[]{
                    onTrack, offTrack, withAlpha(onTrack, DISABLED_ALPHA), withAlpha(offTrack, DISABLED_ALPHA)}));
            view.setThumbTintList(new ColorStateList(states, new int[]{
                    onThumb, offThumb, withAlpha(onThumb, 0.72f), withAlpha(offThumb, 0.72f)}));
        }
        int labelColor = UiPalette.textSecondary(context);
        view.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{-android.R.attr.state_enabled}},
                new int[]{labelColor, withAlpha(labelColor, DISABLED_ALPHA)}));
        view.setMinHeight(dp(context, 48f));
        view.setMinimumHeight(dp(context, 48f));
        AppTypeface.applyIfSelected(view);
    }

    static void styleSeekBar(Context context, SeekBar seekBar) {
        if (seekBar == null) return;
        seekBar.setProgressTintList(ColorStateList.valueOf(UiPalette.controlAccent(context)));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        // 不要把 LagSeekBar 构造时为锁/Thumb 预留的 42dp 又降回 36dp。
        // 过去多个“锁被裁剪/缩小”问题就是统一样式层覆盖自绘控件几何造成的。
        seekBar.setMinimumHeight(dp(context, seekBar instanceof LagSeekBar ? 46f : 36f));
        // LagSeekBar 自己负责端点安全区；这里不再增加左右 padding，保证视觉轨道与文字对齐。
        seekBar.setPadding(0, 0, 0, 0);
        seekBar.setThumb(null);
        seekBar.setSplitTrack(false);
        seekBar.setBackground(null);
        seekBar.setStateListAnimator(null);
        seekBar.setElevation(0f);
    }

    static Drawable sectionIcon(Context context, int index, boolean selected) {
        // 底栏统一采用 Tabler 24x24 / 2px stroke 的线性图标语义。
        // 这里只加载本地 VectorDrawable，不引入图标字体或运行时依赖；颜色继续由 Axon 主题控制。
        final int resId = switch (index) {
            case 0 -> R.drawable.ic_nav_home;
            case 1 -> R.drawable.ic_nav_keyboard;
            case 2 -> R.drawable.ic_nav_layout_grid;
            case 3 -> R.drawable.ic_nav_gamepad;
            case 4 -> R.drawable.ic_nav_activity;
            case 5 -> R.drawable.ic_nav_eye;
            default -> R.drawable.ic_nav_settings;
        };
        Drawable drawable = context.getDrawable(resId);
        if (drawable == null) return null;
        drawable = drawable.mutate();
        drawable.setTint(selected ? UiPalette.textPrimary(context) : UiPalette.textTertiary(context));
        int size = dp(context, 20f);
        drawable.setBounds(0, 0, size, size);
        return drawable;
    }

    static void stylePrimaryButton(Context context, Button button) {
        if (button == null) return;
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setSingleLine(false);
        button.setMaxLines(3);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(dp(context, 44f));
        button.setMinimumHeight(dp(context, 44f));
        button.setPadding(dp(context, 14f), dp(context, 9f), dp(context, 14f), dp(context, 9f));
        button.setBackground(primaryButtonBackground(context));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        AppTypeface.applyIfSelected(button);
        UiMotion.bindPressFeedback(button);
    }

    static void styleSecondaryButton(Context context, TextView view) {
        if (view == null) return;
        view.setTextColor(UiPalette.textPrimary(context));
        view.setSingleLine(false);
        view.setMaxLines(3);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setMinHeight(dp(context, 44f));
        view.setMinimumHeight(dp(context, 44f));
        view.setPadding(dp(context, 12f), dp(context, 9f), dp(context, 12f), dp(context, 9f));
        view.setBackground(controlRipple(context));
        AppTypeface.applyIfSelected(view);
        UiMotion.bindPressFeedback(view);
    }


    static void styleSecondaryButton(Context context, Button button) {
        if (button == null) return;
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setGravity(android.view.Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        styleSecondaryButton(context, (TextView) button);
    }

    static void styleInput(Context context, EditText input) {
        if (input == null) return;
        input.setTextColor(UiPalette.textPrimary(context));
        input.setHintTextColor(UiPalette.textTertiary(context));
        input.setTextSize(14f);
        input.setSingleLine(true);
        input.setMinHeight(dp(context, 48f));
        input.setPadding(dp(context, 14f), dp(context, 8f), dp(context, 14f), dp(context, 8f));
        input.setBackground(controlRipple(context));
        AppTypeface.applyIfSelected(input);
    }


    static void applySafeInsets(View view, int leftDp, int topDp, int rightDp, int bottomDp) {
        if (view == null) return;
        final int baseLeft = dp(view.getContext(), leftDp);
        final int baseTop = dp(view.getContext(), topDp);
        final int baseRight = dp(view.getContext(), rightDp);
        final int baseBottom = dp(view.getContext(), bottomDp);
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            target.setPadding(
                    baseLeft + Math.max(0, insets.getSystemWindowInsetLeft()),
                    baseTop + Math.max(0, insets.getSystemWindowInsetTop()),
                    baseRight + Math.max(0, insets.getSystemWindowInsetRight()),
                    baseBottom + Math.max(0, insets.getSystemWindowInsetBottom()));
            return insets;
        });
        view.requestApplyInsets();
    }

    static void setEnabledVisual(View view, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        // MotionSwitch / LagSeekBar 有自己的灰态和中央锁，整体降 alpha 会把锁也一起淡掉。
        if (view instanceof MotionSwitch || view instanceof LagSeekBar) {
            view.setAlpha(1f);
        } else {
            view.setAlpha(enabled ? 1f : DISABLED_ALPHA);
        }
    }

    static int withAlpha(int color, float alpha) {
        int base = Color.alpha(color);
        int out = Math.max(0, Math.min(255, Math.round(base * alpha)));
        return (color & 0x00ffffff) | (out << 24);
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
