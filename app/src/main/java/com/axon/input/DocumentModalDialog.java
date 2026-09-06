package com.axon.input;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 公告 / 更新内容共用的文档式 Modal。
 * 视觉按用户给出的 Uiverse 模态框还原；业务动作仍由调用方持有，避免弹窗样式反向侵入更新/公告流程。
 */
final class DocumentModalDialog {
    interface Action {
        void run(Handle handle);
    }

    static final class Handle {
        private final Dialog dialog;
        private final Button primaryButton;
        private final View closeButton;

        private Handle(Dialog dialog, Button primaryButton, View closeButton) {
            this.dialog = dialog;
            this.primaryButton = primaryButton;
            this.closeButton = closeButton;
        }

        boolean isShowing() {
            return dialog.isShowing();
        }

        void dismiss() {
            dialog.dismiss();
        }

        void setPrimaryText(CharSequence text) {
            primaryButton.setText(text);
        }

        void setPrimaryEnabled(boolean enabled) {
            primaryButton.setEnabled(enabled);
            primaryButton.setAlpha(enabled ? 1f : 0.42f);
        }

        void setCloseEnabled(boolean enabled) {
            closeButton.setEnabled(enabled);
            closeButton.setAlpha(enabled ? 1f : 0.38f);
        }
    }

    private DocumentModalDialog() {}

    static Handle show(
            Activity activity,
            CharSequence title,
            CharSequence body,
            CharSequence ghostText,
            CharSequence primaryText,
            Action ghostAction,
            Action primaryAction,
            boolean cancelable,
            boolean closeInitiallyEnabled,
            Runnable onDismiss) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);

        LinearLayout modal = new LinearLayout(activity);
        modal.setOrientation(LinearLayout.VERTICAL);
        modal.setBackground(roundRect(activity, Color.WHITE, 16f));
        modal.setClipToOutline(true);
        modal.setElevation(dp(activity, 16f));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(activity, 32f), dp(activity, 14f), dp(activity, 20f), dp(activity, 14f));
        header.setBackgroundColor(Color.WHITE);

        DocumentShieldIcon icon = new DocumentShieldIcon(activity);
        header.addView(icon, new LinearLayout.LayoutParams(dp(activity, 32f), dp(activity, 32f)));

        TextView titleView = new TextView(activity);
        titleView.setText(title == null ? "" : title);
        titleView.setTextColor(Color.rgb(30, 30, 34));
        titleView.setTextSize(18f);
        titleView.setTypeface(AppTypeface.heavy(activity));
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(activity, 8f);
        titleParams.rightMargin = dp(activity, 8f);
        header.addView(titleView, titleParams);

        CloseIcon close = new CloseIcon(activity);
        close.setBackground(ghostIconBackground(activity));
        header.addView(close, new LinearLayout.LayoutParams(dp(activity, 40f), dp(activity, 40f)));
        modal.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View headerDivider = new View(activity);
        headerDivider.setBackgroundColor(Color.rgb(221, 221, 221));
        modal.addView(headerDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1f)));

        FrameLayout bodyHost = new FrameLayout(activity);
        MaxHeightScrollView scroll = new MaxHeightScrollView(activity, dp(activity, 244f));
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setPadding(dp(activity, 32f), dp(activity, 24f), dp(activity, 32f), dp(activity, 51f));

        TextView bodyView = new TextView(activity);
        bodyView.setText(body == null ? "" : body);
        bodyView.setTextColor(Color.rgb(55, 55, 60));
        bodyView.setTextSize(14f);
        bodyView.setLineSpacing(0f, 1.42f);
        bodyView.setTextIsSelectable(true);
        AppTypeface.applyIfSelected(bodyView);
        scroll.addView(bodyView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bodyHost.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View fade = new View(activity);
        GradientDrawable fadeDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.argb(191, 255, 255, 255)});
        fade.setBackground(fadeDrawable);
        fade.setClickable(false);
        FrameLayout.LayoutParams fadeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 50f), Gravity.BOTTOM);
        fadeParams.leftMargin = dp(activity, 24f);
        fadeParams.rightMargin = dp(activity, 24f);
        bodyHost.addView(fade, fadeParams);
        modal.addView(bodyHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        footer.setPadding(dp(activity, 32f), dp(activity, 16f), dp(activity, 32f), dp(activity, 16f));
        footer.setBackgroundColor(Color.WHITE);

        Button ghost = createButton(activity, false);
        ghost.setText(ghostText == null ? "" : ghostText);
        Button primary = createButton(activity, true);
        primary.setText(primaryText == null ? "" : primaryText);

        ghost.setMinHeight(dp(activity, 44f));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footer.addView(ghost, buttonParams);
        primary.setMinHeight(dp(activity, 44f));
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        primaryParams.leftMargin = dp(activity, 12f);
        footer.addView(primary, primaryParams);
        View footerDivider = new View(activity);
        footerDivider.setBackgroundColor(Color.rgb(221, 221, 221));
        modal.addView(footerDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1f)));
        modal.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(modal);
        dialog.setOnDismissListener(ignored -> {
            if (onDismiss != null) onDismiss.run();
        });
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.46f;
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            attrs.width = Math.min(dp(activity, 500f), Math.round(screenWidth * 0.90f));
            attrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(attrs);
        }

        Handle handle = new Handle(dialog, primary, close);
        close.setEnabled(closeInitiallyEnabled);
        close.setAlpha(closeInitiallyEnabled ? 1f : 0.38f);
        close.setOnClickListener(v -> {
            if (v.isEnabled()) handle.dismiss();
        });
        ghost.setOnClickListener(v -> {
            if (ghostAction != null) ghostAction.run(handle);
        });
        primary.setOnClickListener(v -> {
            if (primaryAction != null) primaryAction.run(handle);
        });
        return handle;
    }

    private static Button createButton(Context context, boolean primary) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setTypeface(AppTypeface.heavy(context));
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 20f), 0, dp(context, 20f), 0);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        if (primary) {
            button.setTextColor(Color.WHITE);
            button.setBackground(buttonBackground(context,
                    Color.rgb(117, 5, 80), Color.rgb(74, 4, 51), 8f));
        } else {
            button.setTextColor(Color.rgb(42, 42, 46));
            button.setBackground(buttonBackground(context,
                    Color.TRANSPARENT, Color.rgb(223, 218, 215), 8f));
        }
        return button;
    }

    private static StateListDrawable buttonBackground(Context context, int normal, int active, float radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, roundRect(context, active, radiusDp));
        states.addState(new int[]{android.R.attr.state_focused}, roundRect(context, active, radiusDp));
        states.addState(new int[]{}, roundRect(context, normal, radiusDp));
        return states;
    }

    private static StateListDrawable ghostIconBackground(Context context) {
        return buttonBackground(context, Color.TRANSPARENT, Color.rgb(223, 218, 215), 8f);
    }

    private static GradientDrawable roundRect(Context context, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, capped);
        }
    }

    /** 参考 HTML 里的 document + shield 图标，直接 Canvas 绘制，避免额外矢量资源。 */
    private static final class DocumentShieldIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        DocumentShieldIcon(Context context) {
            super(context);
            paint.setColor(Color.rgb(117, 5, 80));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(context, 1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            float s = Math.min(w, h);
            float l = (w - s) * 0.5f;
            float t = (h - s) * 0.5f;

            path.reset();
            path.moveTo(l + s * 0.22f, t + s * 0.12f);
            path.lineTo(l + s * 0.62f, t + s * 0.12f);
            path.lineTo(l + s * 0.80f, t + s * 0.30f);
            path.lineTo(l + s * 0.80f, t + s * 0.54f);
            path.moveTo(l + s * 0.22f, t + s * 0.12f);
            path.lineTo(l + s * 0.22f, t + s * 0.88f);
            path.lineTo(l + s * 0.51f, t + s * 0.88f);
            path.moveTo(l + s * 0.62f, t + s * 0.12f);
            path.lineTo(l + s * 0.62f, t + s * 0.30f);
            path.lineTo(l + s * 0.80f, t + s * 0.30f);
            canvas.drawPath(path, paint);

            path.reset();
            path.moveTo(l + s * 0.49f, t + s * 0.47f);
            path.lineTo(l + s * 0.82f, t + s * 0.47f);
            path.lineTo(l + s * 0.82f, t + s * 0.69f);
            path.cubicTo(l + s * 0.82f, t + s * 0.80f,
                    l + s * 0.72f, t + s * 0.85f,
                    l + s * 0.655f, t + s * 0.89f);
            path.cubicTo(l + s * 0.59f, t + s * 0.85f,
                    l + s * 0.49f, t + s * 0.80f,
                    l + s * 0.49f, t + s * 0.69f);
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    private static final class CloseIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CloseIcon(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            paint.setColor(Color.rgb(48, 48, 52));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(context, 1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float r = Math.min(getWidth(), getHeight()) * 0.18f;
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint);
            canvas.drawLine(cx + r, cy - r, cx - r, cy + r, paint);
        }
    }
}
