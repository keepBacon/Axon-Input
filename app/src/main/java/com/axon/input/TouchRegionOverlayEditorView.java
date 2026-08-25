package com.axon.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-display touch-region editor rendered as an accessibility overlay over the actual foreground
 * app. Region coordinates are stored directly in physical-display normalized space, so the box the
 * user sees is the box used by the evdev hit tester.
 */
final class TouchRegionOverlayEditorView extends FrameLayout {
    interface Listener {
        void onEditorFinished(boolean save);
    }

    private final RegionBoxView[] regions = new RegionBoxView[TouchDisplayStore.REGION_COUNT];
    private final Listener listener;
    private final Paint backdrop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private View headerView;
    private boolean laidOut;

    TouchRegionOverlayEditorView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(true);
        setFocusable(false);
        setWillNotDraw(false);
        backdrop.setColor(0x07000000);
        build();
    }

    private void build() {
        // Touch diagnostic marks sit below interactive regions so they never steal drag/resize input.
        addView(new TouchPointLayer(getContext()), new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int[] labels = {
                R.string.touch_display_region_joystick,
                R.string.touch_display_region_lmb,
                R.string.touch_display_region_rmb,
                R.string.touch_display_region_space
        };
        for (int i = 0; i < regions.length; i++) {
            RegionBoxView box = new RegionBoxView(getContext(), i, getContext().getString(labels[i]));
            regions[i] = box;
            addView(box, new LayoutParams(dp(180), dp(120)));
        }

        LinearLayout header = new LinearLayout(getContext());
        headerView = header;
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(9), dp(10), dp(9));
        header.setBackground(UiChrome.surface(getContext(), UiPalette.surface(getContext()), 12f));
        header.setElevation(dp(4));

        LinearLayout copy = new LinearLayout(getContext());
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(getContext().getString(R.string.touch_display_editor_title), 15f,
                UiPalette.textPrimary(getContext()));
        title.setTypeface(FontManager.bold(getContext()));
        copy.addView(title);
        TextView hint = text("正在目标应用上方框选实际触屏区域 · 拖动框体移动，拖任意边/角缩放", 10.5f,
                UiPalette.textSecondary(getContext()));
        copy.addView(hint);
        header.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = action("取消");
        cancel.setOnClickListener(v -> finish(false));
        header.addView(cancel, new LinearLayout.LayoutParams(dp(72), dp(44)));
        TextView save = action("完成");
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(dp(80), dp(44));
        saveLp.leftMargin = dp(6);
        header.addView(save, saveLp);
        save.setOnClickListener(v -> finish(true));

        LayoutParams headerLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.gravity = Gravity.TOP | Gravity.START;
        headerLp.leftMargin = dp(10);
        headerLp.rightMargin = dp(10);
        headerLp.topMargin = dp(8);
        addView(header, headerLp);
        setOnApplyWindowInsetsListener((view, insets) -> {
            LayoutParams lp = (LayoutParams) header.getLayoutParams();
            int safeTop = Math.max(0, insets.getSystemWindowInsetTop());
            int targetTop = safeTop + dp(8);
            if (lp.topMargin != targetTop) {
                lp.topMargin = targetTop;
                header.setLayoutParams(lp);
            }
            return insets;
        });
        requestApplyInsets();

        post(this::layoutSavedRegions);
    }

    private void finish(boolean save) {
        if (save) saveRegions();
        Listener callback = listener;
        if (callback != null) callback.onEditorFinished(save);
    }

    private void layoutSavedRegions() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        for (int i = 0; i < regions.length; i++) {
            float[] r = TouchDisplayStore.getRegion(getContext(), i);
            int left = Math.round(r[0] * getWidth());
            int top = Math.round(r[1] * getHeight());
            int right = Math.round(r[2] * getWidth());
            int bottom = Math.round(r[3] * getHeight());
            applyBounds(regions[i], left, top, right, bottom);
        }
        laidOut = true;
    }

    private void saveRegions() {
        if (!laidOut || getWidth() <= 0 || getHeight() <= 0) return;
        float w = Math.max(1f, getWidth());
        float h = Math.max(1f, getHeight());
        for (int i = 0; i < regions.length; i++) {
            LayoutParams lp = (LayoutParams) regions[i].getLayoutParams();
            TouchDisplayStore.setRegion(getContext(), i,
                    lp.leftMargin / w,
                    lp.topMargin / h,
                    (lp.leftMargin + lp.width) / w,
                    (lp.topMargin + lp.height) / h);
        }
        TouchDisplayStore.setCoordinateVersion(getContext(),
                TouchDisplayStore.COORD_FULL_DISPLAY);
    }

    private void applyBounds(View view, int left, int top, int right, int bottom) {
        int minW = dp(82);
        int minH = dp(66);
        int maxW = Math.max(minW, getWidth());
        int maxH = Math.max(minH, getHeight());
        left = clamp(left, 0, Math.max(0, maxW - minW));
        top = clamp(top, 0, Math.max(0, maxH - minH));
        right = clamp(right, left + minW, maxW);
        bottom = clamp(bottom, top + minH, maxH);
        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        lp.width = right - left;
        lp.height = bottom - top;
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.gravity = Gravity.TOP | Gravity.START;
        view.setLayoutParams(lp);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        if (!laidOut || oldw <= 0 || oldh <= 0) {
            post(this::layoutSavedRegions);
            return;
        }
        // Keep the current unsaved boxes in the same normalized positions when the target app
        // changes usable display geometry.  Do not reload preferences and do not recreate views.
        float sx = w / (float) oldw;
        float sy = h / (float) oldh;
        for (RegionBoxView region : regions) {
            if (region == null) continue;
            LayoutParams lp = (LayoutParams) region.getLayoutParams();
            int left = Math.round(lp.leftMargin * sx);
            int top = Math.round(lp.topMargin * sy);
            int right = Math.round((lp.leftMargin + lp.width) * sx);
            int bottom = Math.round((lp.topMargin + lp.height) * sy);
            applyBounds(region, left, top, right, bottom);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), backdrop);
    }

    private TextView action(String label) {
        TextView out = text(label, 12.5f, UiPalette.textPrimary(getContext()));
        out.setGravity(Gravity.CENTER);
        out.setClickable(true);
        out.setFocusable(true);
        UiChrome.styleSecondaryButton(getContext(), out);
        return out;
    }

    private TextView text(String value, float sp, int color) {
        TextView out = new TextView(getContext());
        out.setText(value);
        out.setTextSize(sp);
        out.setTextColor(color);
        out.setIncludeFontPadding(false);
        return out;
    }

    private final class TouchPointLayer extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        TouchPointLayer(Context context) {
            super(context);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(withAlpha(UiPalette.accent(context), 0.16f));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(2));
            stroke.setColor(UiPalette.accent(context));
            setClickable(false);
        }

        @Override protected void onDraw(Canvas canvas) {
            float[] points = AxonInputAccessibilityService.getTouchMappedPointsForEditor();
            for (int i = 0; i + 2 < points.length; i += 3) {
                float x = points[i + 1] * getWidth();
                float y = points[i + 2] * getHeight();
                float r = dp(10);
                canvas.drawCircle(x, y, r, fill);
                canvas.drawCircle(x, y, r, stroke);
                canvas.drawLine(x - dp(14), y, x + dp(14), y, stroke);
                canvas.drawLine(x, y - dp(14), x, y + dp(14), stroke);
            }
            if (isAttachedToWindow()) postInvalidateDelayed(33L);
        }
    }

    private final class RegionBoxView extends View {
        private static final int LEFT = 1;
        private static final int TOP = 1 << 1;
        private static final int RIGHT = 1 << 2;
        private static final int BOTTOM = 1 << 3;
        private static final int MOVE = 1 << 4;

        private final String title;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int mode;
        private float downRawX, downRawY;
        private int startLeft, startTop, startRight, startBottom;

        RegionBoxView(Context context, int regionId, String title) {
            super(context);
            this.title = title;
            setClickable(true);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(dp(3f));
            outline.setColor(0x52000000);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.5f));
            stroke.setColor(0xF0FFFFFF);
            fill.setStyle(Paint.Style.FILL);
            // Neutral translucent white keeps the actual app visible while making the editable
            // region readable on both dark and saturated game scenes.
            fill.setColor(0x30FFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(FontManager.bold(context));
            text.setTextSize(dp(14));
            text.setColor(Color.WHITE);
            text.setShadowLayer(dp(2f), 0f, dp(1f), 0x7A000000);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            float inset = dp(2);
            RectF rect = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
            float radius = dp(12);
            canvas.drawRoundRect(rect, radius, radius, fill);
            canvas.drawRoundRect(rect, radius, radius, outline);
            canvas.drawRoundRect(rect, radius, radius, stroke);

            // Short corner marks make the resize affordance visible without surrounding the box
            // with heavy handles. The center remains visually quiet and is the drag target.
            float mark = dp(12);
            float pad = dp(7);
            Paint handle = stroke;
            handle.setStrokeWidth(dp(2.25f));
            canvas.drawLine(pad, pad, pad + mark, pad, handle);
            canvas.drawLine(pad, pad, pad, pad + mark, handle);
            canvas.drawLine(getWidth() - pad, pad, getWidth() - pad - mark, pad, handle);
            canvas.drawLine(getWidth() - pad, pad, getWidth() - pad, pad + mark, handle);
            canvas.drawLine(pad, getHeight() - pad, pad + mark, getHeight() - pad, handle);
            canvas.drawLine(pad, getHeight() - pad, pad, getHeight() - pad - mark, handle);
            canvas.drawLine(getWidth() - pad, getHeight() - pad,
                    getWidth() - pad - mark, getHeight() - pad, handle);
            canvas.drawLine(getWidth() - pad, getHeight() - pad,
                    getWidth() - pad, getHeight() - pad - mark, handle);
            handle.setStrokeWidth(dp(1.5f));

            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = getHeight() * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(title, getWidth() * 0.5f, baseline, text);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!laidOut) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    bringToFront();
                    if (headerView != null) headerView.bringToFront();
                    mode = resolveMode(event.getX(), event.getY());
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    LayoutParams lp = (LayoutParams) getLayoutParams();
                    startLeft = lp.leftMargin;
                    startTop = lp.topMargin;
                    startRight = lp.leftMargin + lp.width;
                    startBottom = lp.topMargin + lp.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    resizeOrMove(event.getRawX() - downRawX, event.getRawY() - downRawY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mode = 0;
                    return true;
                default:
                    return true;
            }
        }

        private int resolveMode(float x, float y) {
            float edge = dp(24);
            int result = 0;
            if (x <= edge) result |= LEFT;
            if (x >= getWidth() - edge) result |= RIGHT;
            if (y <= edge) result |= TOP;
            if (y >= getHeight() - edge) result |= BOTTOM;
            return result == 0 ? MOVE : result;
        }

        private void resizeOrMove(float dx, float dy) {
            int left = startLeft, top = startTop, right = startRight, bottom = startBottom;
            if ((mode & MOVE) != 0) {
                int w = startRight - startLeft;
                int h = startBottom - startTop;
                // Bounds must come from the full-screen editor, not this RegionBoxView itself.
                // Using getWidth()/getHeight() here used to clamp every move back to ~0, making
                // the selection box appear completely immovable.
                int parentW = Math.max(w, TouchRegionOverlayEditorView.this.getWidth());
                int parentH = Math.max(h, TouchRegionOverlayEditorView.this.getHeight());
                left = clamp(Math.round(startLeft + dx), 0, Math.max(0, parentW - w));
                top = clamp(Math.round(startTop + dy), 0, Math.max(0, parentH - h));
                right = left + w;
                bottom = top + h;
            } else {
                if ((mode & LEFT) != 0) left = Math.round(startLeft + dx);
                if ((mode & RIGHT) != 0) right = Math.round(startRight + dx);
                if ((mode & TOP) != 0) top = Math.round(startTop + dy);
                if ((mode & BOTTOM) != 0) bottom = Math.round(startBottom + dy);
            }
            applyBounds(this, left, top, right, bottom);
        }
    }

    private int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (color & 0x00ffffff) | (a << 24);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
