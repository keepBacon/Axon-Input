package com.axon.input;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

/**
 * Spinner surface whose options grow from the selector's own centre instead of dropping
 * from the bottom edge. The popup is positioned around the anchor centre and its content
 * scales around that same geometric centre.
 */
final class AnimatedChoiceSpinner extends Spinner {
    private static final float ENTER_SCALE = 0.95f;
    private static final float EXIT_SCALE = 0.98f;
    private static final long ENTER_MS = 180L;
    private static final long EXIT_MS = 140L;
    private static final int MAX_VISIBLE_ROWS = 6;

    private PopupWindow popup;
    private View popupSurface;
    private boolean dismissAnimating;

    AnimatedChoiceSpinner(Context context) {
        super(context, Spinner.MODE_DROPDOWN);
    }

    @Override
    public boolean performClick() {
        SpinnerAdapter source = getAdapter();
        if (source == null || source.getCount() <= 0 || getWindowToken() == null) {
            return super.performClick();
        }
        if (popup != null && popup.isShowing()) {
            dismissPopup(true);
            return true;
        }
        showCenteredPopup(source);
        return true;
    }

    private void showCenteredPopup(SpinnerAdapter source) {
        final int rowHeight = dp(48);
        final int count = source.getCount();
        final int popupWidth = Math.max(getWidth(), dp(160));
        final int visibleRows = Math.max(1, Math.min(MAX_VISIBLE_ROWS, count));
        final int popupHeight = rowHeight * visibleRows + dp(8);

        ListView list = new ListView(getContext());
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(count > visibleRows);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(dropDownAdapter(source));
        list.setSelection(Math.max(0, getSelectedItemPosition()));

        FrameLayout surface = new FrameLayout(getContext());
        surface.setPadding(dp(4), dp(4), dp(4), dp(4));
        surface.setBackground(UiChrome.popupSurface(getContext()));
        surface.setClipChildren(false);
        surface.setClipToPadding(false);
        surface.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        PopupWindow next = new PopupWindow(surface, popupWidth, popupHeight, true);
        next.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        next.setOutsideTouchable(true);
        next.setClippingEnabled(true);
        next.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        // Native dropdown/window animation would use a different origin. Content animation below
        // owns the motion so its pivot can stay exactly at the selector centre.
        next.setAnimationStyle(0);
        next.setOnDismissListener(() -> {
            if (popup == next) {
                popup = null;
                popupSurface = null;
                dismissAnimating = false;
            }
        });

        int[] anchorLocation = new int[2];
        getLocationOnScreen(anchorLocation);
        float anchorCenterX = anchorLocation[0] + getWidth() * 0.5f;
        float anchorCenterY = anchorLocation[1] + getHeight() * 0.5f;
        int x = Math.round(anchorCenterX - popupWidth * 0.5f);
        int y = Math.round(anchorCenterY - popupHeight * 0.5f);

        Rect visible = new Rect();
        View root = getRootView();
        root.getWindowVisibleDisplayFrame(visible);
        x = clamp(x, visible.left, Math.max(visible.left, visible.right - popupWidth));
        y = clamp(y, visible.top, Math.max(visible.top, visible.bottom - popupHeight));

        popup = next;
        popupSurface = surface;
        dismissAnimating = false;
        next.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);

        surface.setPivotX(popupWidth * 0.5f);
        surface.setPivotY(popupHeight * 0.5f);
        surface.setScaleX(ENTER_SCALE);
        surface.setScaleY(ENTER_SCALE);
        surface.setAlpha(0.38f);
        surface.animate().cancel();
        surface.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(ENTER_MS)
                .setInterpolator(UiMotion.easeOut())
                .start();

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < getCount()) {
                setSelection(position);
                performItemClick(view, position, source.getItemId(position));
            }
            dismissPopup(true);
        });
    }

    private ListAdapter dropDownAdapter(SpinnerAdapter source) {
        return new BaseAdapter() {
            @Override public int getCount() { return source.getCount(); }
            @Override public Object getItem(int position) { return source.getItem(position); }
            @Override public long getItemId(int position) { return source.getItemId(position); }
            @Override public boolean hasStableIds() { return source.hasStableIds(); }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = source.getDropDownView(position, convertView, parent);
                if (row != null) {
                    row.setMinimumHeight(dp(48));
                    row.setSelected(position == getSelectedItemPosition());
                    UiMotion.bindPressFeedback(row);
                }
                return row;
            }
        };
    }

    private void dismissPopup(boolean animated) {
        PopupWindow current = popup;
        View surface = popupSurface;
        if (current == null || !current.isShowing()) return;
        if (!animated || surface == null) {
            current.dismiss();
            return;
        }
        if (dismissAnimating) return;
        dismissAnimating = true;
        surface.animate().cancel();
        surface.animate()
                .scaleX(EXIT_SCALE)
                .scaleY(EXIT_SCALE)
                .alpha(0f)
                .setDuration(EXIT_MS)
                .setInterpolator(UiMotion.easeOut())
                .withEndAction(() -> {
                    if (current.isShowing()) current.dismiss();
                })
                .start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (popup != null) popup.dismiss();
        popup = null;
        popupSurface = null;
        dismissAnimating = false;
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
