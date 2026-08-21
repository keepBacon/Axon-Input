package com.axon.input;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.TextView;

/** 轻量 CPS 文本悬浮层。 */
public final class DpsOverlayView extends TextView {
    public static final int DISPLAY_DPS = 30;

    public interface DragListener {
        void onDragStart(DpsOverlayView source, float rawX, float rawY);
        void onDragMove(DpsOverlayView source, float rawX, float rawY);
        void onDragEnd(DpsOverlayView source);
    }

    private DragListener dragListener;
    private boolean dragEnabled;
    private boolean dragging;

    public DpsOverlayView(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setSingleLine(true);
        setTextSize(16f);
        setTypeface(FontManager.bold(context));
        setTextColor(UiPalette.overlayTextIdle(context));
        setBackgroundColor(Color.TRANSPARENT);
        float density = getResources().getDisplayMetrics().density;
        int shadow = OverlayState.getUiTheme(context) == OverlayState.UI_THEME_BLACK
                ? 0xaa000000 : 0x66ffffff;
        setShadowLayer(1.5f * density, 0f, 0.75f * density, shadow);
        setText(R.string.dps_overlay_waiting);
    }

    public void setDragListener(DragListener listener) {
        dragListener = listener;
    }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
        if (!enabled && dragging) finishDrag();
    }

    public void setDpsValue(int value) {
        if (value < 0) setText(R.string.dps_overlay_waiting);
        else setText(getContext().getString(R.string.dps_overlay_value, value));
    }

    public void setUserOpacity(int percent) {
        setAlpha(Math.max(0f, Math.min(1f, percent / 100f)));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!dragEnabled) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (dragListener != null) dragListener.onDragStart(this, event.getRawX(), event.getRawY());
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
}
