package com.axon.input;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/** Runtime, touch-through renderer for the loaded super-custom key-display configuration. */
final class SuperCustomOverlayView extends FrameLayout {
    private final List<RuntimeBinding> bindings = new ArrayList<>();

    SuperCustomOverlayView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
    }

    void setSpecs(List<SuperCustomControlSpec> specs) {
        clearPressed();
        bindings.clear();
        removeAllViews();
        if (specs == null) return;

        for (SuperCustomControlSpec source : specs) {
            if (source == null) continue;
            if (source.isKeyElement() && !InputBinding.isValid(source.keyCode)) continue;
            SuperCustomControlSpec spec = source.copy();
            SuperCustomControlView view = new SuperCustomControlView(getContext());
            view.setInteractivePreview(false);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(spec.widthDp), dp(spec.heightDp));
            params.gravity = Gravity.TOP | Gravity.START;
            view.setLayoutParams(params);
            view.applySpec(spec);
            addView(view);
            bindings.add(new RuntimeBinding(spec, view));
        }
        requestLayout();
    }

    void setInputPressed(int inputCode, boolean pressed) {
        if (inputCode < 0) return;
        for (RuntimeBinding binding : bindings) {
            if (binding.spec.isKeyElement() && binding.spec.keyCode == inputCode) {
                binding.view.onBoundKeyEvent(pressed);
            }
        }
    }

    void clearPressed() {
        for (RuntimeBinding binding : bindings) binding.view.onBoundKeyEvent(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutBindings(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layoutBindings(right - left, bottom - top);
    }

    private void layoutBindings(int width, int height) {
        if (width <= 0 || height <= 0) return;
        for (RuntimeBinding binding : bindings) {
            int controlWidth = dp(binding.spec.widthDp);
            int controlHeight = dp(binding.spec.heightDp);
            int halfW = controlWidth / 2;
            int halfH = controlHeight / 2;
            int centerX = binding.spec.positionSet ? binding.spec.centerXPx : width / 2;
            int centerY = binding.spec.positionSet ? binding.spec.centerYPx : height / 2;
            // Off-screen centers are intentional in v1.8. The full-screen parent has clipping
            // disabled, so partially visible controls render exactly at the saved coordinates.
            binding.view.layout(centerX - halfW, centerY - halfH,
                    centerX - halfW + controlWidth, centerY - halfH + controlHeight);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class RuntimeBinding {
        final SuperCustomControlSpec spec;
        final SuperCustomControlView view;

        RuntimeBinding(SuperCustomControlSpec spec, SuperCustomControlView view) {
            this.spec = spec;
            this.view = view;
        }
    }
}
