package com.axon.input;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
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

    static GradientDrawable nestedSurface(Context context) {
        return UiPalette.rounded(context, UiPalette.debugSurface(context), CONTROL_RADIUS_DP);
    }

    static GradientDrawable popupSurface(Context context) {
        return surface(context, UiPalette.surfaceRaised(context), 12f);
    }

    /**
     * Static surface only. Native RippleDrawable timing varies by Android/OEM and competes with
     * the unified motion system, so interaction feedback is handled by UiMotion instead.
     */
    static Drawable ripple(Context context, int fillColor, float radiusDp) {
        return UiPalette.rounded(context, fillColor, radiusDp);
    }

    static Drawable controlRipple(Context context) {
        int fill = UiPalette.controlSurface(context);
        StateListDrawable content = new StateListDrawable();
        GradientDrawable focused = UiPalette.rounded(context, fill, CONTROL_RADIUS_DP);
        focused.setStroke(dp(context, 1f), UiPalette.accent(context));
        GradientDrawable normal = UiPalette.rounded(context, fill, CONTROL_RADIUS_DP);
        content.addState(new int[]{android.R.attr.state_focused}, focused);
        content.addState(new int[]{}, normal);
        return content;
    }

    static Drawable primaryButtonBackground(Context context) {
        int fill = UiPalette.accent(context);
        StateListDrawable content = new StateListDrawable();
        GradientDrawable focused = UiPalette.rounded(context, fill, CONTROL_RADIUS_DP);
        focused.setStroke(dp(context, 1f), UiPalette.background(context));
        GradientDrawable normal = UiPalette.rounded(context, fill, CONTROL_RADIUS_DP);
        content.addState(new int[]{android.R.attr.state_focused}, focused);
        content.addState(new int[]{}, normal);
        return content;
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
        int primary = UiPalette.textPrimary(context);
        view.setTextColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_enabled}, new int[]{-android.R.attr.state_enabled}},
                new int[]{primary, withAlpha(primary, DISABLED_ALPHA)}));
        view.setMinHeight(dp(context, 48f));
        view.setMinimumHeight(dp(context, 48f));
    }

    static void styleSeekBar(Context context, SeekBar seekBar) {
        if (seekBar == null) return;
        seekBar.setProgressTintList(ColorStateList.valueOf(UiPalette.accent(context)));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(UiPalette.divider(context)));
        seekBar.setMinimumHeight(dp(context, 36f));
        seekBar.setPadding(0, dp(context, 8f), 0, dp(context, 8f));
        // All settings sliders now communicate value through the filled track only.
        seekBar.setThumb(null);
        seekBar.setSplitTrack(false);
    }

    static Drawable sectionIcon(Context context, int index, boolean selected) {
        return new SectionIconDrawable(
                selected ? UiPalette.textPrimary(context) : UiPalette.textTertiary(context),
                dp(context, 20f), index);
    }

    /** Small purpose-drawn icons keep the navigation independent from icon-font assets. */
    private static final class SectionIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private final int type;

        SectionIconDrawable(int color, int size, int type) {
            this.size = size;
            this.type = type;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(1.4f, size * 0.085f));
            setBounds(0, 0, size, size);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float u = Math.min(b.width(), b.height()) / 20f;
            switch (type) {
                case 0: // appearance: sun
                    canvas.drawCircle(cx, cy, 3.2f * u, paint);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4.0;
                        float x1 = cx + (float) Math.cos(a) * 6.1f * u;
                        float y1 = cy + (float) Math.sin(a) * 6.1f * u;
                        float x2 = cx + (float) Math.cos(a) * 8.0f * u;
                        float y2 = cy + (float) Math.sin(a) * 8.0f * u;
                        canvas.drawLine(x1, y1, x2, y2, paint);
                    }
                    break;
                case 1: // keyboard / mouse
                    canvas.drawRoundRect(new RectF(cx - 8f*u, cy - 5f*u, cx + 8f*u, cy + 5f*u),
                            2f*u, 2f*u, paint);
                    canvas.drawLine(cx - 5.2f*u, cy - 1.4f*u, cx + 5.2f*u, cy - 1.4f*u, paint);
                    canvas.drawLine(cx - 4f*u, cy + 2.1f*u, cx + 4f*u, cy + 2.1f*u, paint);
                    break;
                case 2: // custom: four modules
                    for (int yy = 0; yy < 2; yy++) for (int xx = 0; xx < 2; xx++) {
                        float l = cx + (xx == 0 ? -7f : 1f) * u;
                        float t = cy + (yy == 0 ? -7f : 1f) * u;
                        canvas.drawRoundRect(new RectF(l, t, l + 6f*u, t + 6f*u), 1.4f*u, 1.4f*u, paint);
                    }
                    break;
                case 3: // gamepad
                    canvas.drawRoundRect(new RectF(cx - 8f*u, cy - 5f*u, cx + 8f*u, cy + 6f*u),
                            4.5f*u, 4.5f*u, paint);
                    canvas.drawLine(cx - 5f*u, cy, cx - 1.5f*u, cy, paint);
                    canvas.drawLine(cx - 3.25f*u, cy - 1.75f*u, cx - 3.25f*u, cy + 1.75f*u, paint);
                    canvas.drawCircle(cx + 3.5f*u, cy - 0.8f*u, 0.8f*u, paint);
                    canvas.drawCircle(cx + 5.7f*u, cy + 1.2f*u, 0.8f*u, paint);
                    break;
                case 4: // sensitivity: gauge
                    RectF gauge = new RectF(cx - 7f*u, cy - 6f*u, cx + 7f*u, cy + 8f*u);
                    canvas.drawArc(gauge, 205f, 130f, false, paint);
                    canvas.drawLine(cx, cy + 1.5f*u, cx + 4.8f*u, cy - 2.5f*u, paint);
                    canvas.drawCircle(cx, cy + 1.5f*u, 1.1f*u, paint);
                    break;
                case 5: // behavior: directional gesture
                    canvas.drawLine(cx - 7f*u, cy, cx + 6f*u, cy, paint);
                    canvas.drawLine(cx + 6f*u, cy, cx + 2.5f*u, cy - 3.5f*u, paint);
                    canvas.drawLine(cx + 6f*u, cy, cx + 2.5f*u, cy + 3.5f*u, paint);
                    canvas.drawCircle(cx - 5.8f*u, cy, 2.1f*u, paint);
                    break;
                default: // configuration: compact gear
                    canvas.drawCircle(cx, cy, 3.1f*u, paint);
                    canvas.drawCircle(cx, cy, 7.0f*u, paint);
                    for (int i = 0; i < 4; i++) {
                        double a = i * Math.PI / 2.0;
                        canvas.drawLine(
                                cx + (float)Math.cos(a) * 7.0f*u,
                                cy + (float)Math.sin(a) * 7.0f*u,
                                cx + (float)Math.cos(a) * 8.5f*u,
                                cy + (float)Math.sin(a) * 8.5f*u, paint);
                    }
                    break;
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
    }

    static void stylePrimaryButton(Context context, Button button) {
        if (button == null) return;
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextColor(OverlayState.getUiTheme(context) == OverlayState.UI_THEME_BLACK
                ? Color.rgb(20, 20, 22) : Color.WHITE);
        button.setMinHeight(dp(context, 44f));
        button.setMinimumHeight(dp(context, 44f));
        button.setPadding(dp(context, 14f), 0, dp(context, 14f), 0);
        button.setBackground(primaryButtonBackground(context));
        button.setStateListAnimator(null);
        UiMotion.bindPressFeedback(button);
    }

    static void styleSecondaryButton(Context context, TextView view) {
        if (view == null) return;
        view.setTextColor(UiPalette.textPrimary(context));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setMinHeight(dp(context, 44f));
        view.setMinimumHeight(dp(context, 44f));
        view.setPadding(dp(context, 12f), 0, dp(context, 12f), 0);
        view.setBackground(controlRipple(context));
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
        input.setPadding(dp(context, 14f), 0, dp(context, 14f), 0);
        input.setBackground(controlRipple(context));
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
        view.setAlpha(enabled ? 1f : DISABLED_ALPHA);
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
