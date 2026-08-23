package com.axon.input;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/** Live preview / created control for the super-custom key display editor. */
final class SuperCustomControlView extends FrameLayout {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Long> cpsSamples = new ArrayDeque<>();
    private final LinearLayout content;
    private final TextView label;
    private final TextView cps;
    private SuperCustomControlSpec spec;
    private boolean keyPressed;
    private boolean interactivePreview;
    private int idleColor;

    private final Runnable cpsRefresh = new Runnable() {
        @Override public void run() {
            refreshCps();
            if (!cpsSamples.isEmpty()) handler.postDelayed(this, 120L);
        }
    };

    SuperCustomControlView(Context context) {
        super(context);
        setClipToOutline(false);
        setClickable(true);
        setFocusable(true);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        addView(content, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        label = new TextView(context);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        label.setSingleLine(true);
        content.addView(label, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        cps = new TextView(context);
        cps.setGravity(Gravity.CENTER);
        cps.setIncludeFontPadding(false);
        cps.setSingleLine(true);
        cps.setTextSize(10f);
        content.addView(cps, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        setOnTouchListener((v, event) -> {
            if (!interactivePreview) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                setPressedState(true, false);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                setPressedState(false, false);
            }
            return false;
        });
    }

    void setInteractivePreview(boolean enabled) {
        interactivePreview = enabled;
    }

    TextView labelView() {
        return label;
    }

    TextView cpsView() {
        return cps;
    }

    void applySpec(SuperCustomControlSpec next) {
        spec = next;
        if (next == null) return;
        idleColor = UiPalette.controlSurface(getContext());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (lp == null) lp = new FrameLayout.LayoutParams(dp(next.widthDp), dp(next.heightDp));
        lp.width = dp(next.widthDp);
        lp.height = dp(next.heightDp);
        setLayoutParams(lp);
        setAlpha(next.opacityPercent / 100f);
        label.setText(next.labelText == null ? "" : next.labelText);
        label.setTextColor(next.textColor);
        label.setTextSize(next.textSizeSp);
        cps.setTextColor(next.textColor);
        cps.setTextSize(Math.max(9f, next.textSizeSp * 0.56f));
        cps.setVisibility(next.cpsEnabled ? View.VISIBLE : View.GONE);
        setPadding(dp(8), dp(6), dp(8), dp(6));
        updateBackground(keyPressed ? next.pressColor : idleColor);
        refreshCps();
    }

    void onBoundKeyEvent(boolean down) {
        if (down && !keyPressed) {
            long now = SystemClock.uptimeMillis();
            cpsSamples.addLast(now);
            handler.removeCallbacks(cpsRefresh);
            handler.post(cpsRefresh);
        }
        setPressedState(down, true);
    }

    private void setPressedState(boolean pressed, boolean fromKey) {
        if (spec == null || keyPressed == pressed) return;
        keyPressed = pressed;
        updateBackground(pressed ? spec.pressColor : idleColor);
        animate().cancel();
        final float baseAlpha = spec.opacityPercent / 100f;
        switch (spec.motionMode) {
            case OverlayState.MOTION_ALPHA:
                animate().alpha(pressed ? Math.max(0.22f, baseAlpha * 0.55f) : baseAlpha)
                        .setDuration(pressed ? 90L : 130L)
                        .start();
                break;
            case OverlayState.MOTION_RIPPLE:
                animate().scaleX(pressed ? 1.025f : 1f)
                        .scaleY(pressed ? 1.025f : 1f)
                        .setDuration(pressed ? 95L : 140L)
                        .start();
                break;
            case OverlayState.MOTION_NONE:
                setScaleX(1f);
                setScaleY(1f);
                setAlpha(baseAlpha);
                break;
            case OverlayState.MOTION_SIZE:
            default:
                animate().scaleX(pressed ? 0.965f : 1f)
                        .scaleY(pressed ? 0.965f : 1f)
                        .alpha(baseAlpha)
                        .setDuration(pressed ? 90L : 135L)
                        .start();
                break;
        }
    }

    private void refreshCps() {
        long cutoff = SystemClock.uptimeMillis() - 1000L;
        while (!cpsSamples.isEmpty() && cpsSamples.peekFirst() < cutoff) {
            cpsSamples.removeFirst();
        }
        if (spec == null || !spec.cpsEnabled) {
            cps.setText("");
            return;
        }
        cps.setText(spec.renderCps(cpsSamples.size()));
    }

    private void updateBackground(int color) {
        if (spec == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(spec.cornerDp));
        setBackground(bg);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
