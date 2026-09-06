package com.axon.input;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * 顶部全局搜索控件。
 *
 * 设计上保持“单个搜索图标 -> 向左展开输入框”的低频空间变化，搜索结果使用独立 Popup，
 * 不重排 7 个设置页面，也不会在输入时重新挂载页面 View。这样搜索只负责导航，不介入功能状态。
 */
final class GlobalSettingsSearchBar extends FrameLayout {
    interface Provider {
        List<Item> search(String query);
        void onItemSelected(Item item);
    }

    static final class Item {
        final String title;
        final String subtitle;
        final Object token;

        Item(String title, String subtitle, Object token) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.token = token;
        }
    }

    private static final long EXPAND_MS = 220L;
    private static final long COLLAPSE_MS = 160L;
    private static final int MAX_RESULTS = 10;
    private static final long IME_CLOSE_VERIFY_MS = 140L;
    private static final long IME_TRANSITION_GUARD_MS = 420L;

    private final Provider provider;
    private final EditText input;
    private final SearchIconView iconView;
    private final View focusLine;
    private final GradientDrawable shell;
    private final PathInterpolator easeOut = new PathInterpolator(0.23f, 1f, 0.32f, 1f);

    private PopupWindow resultPopup;
    private ScrollView resultScroll;
    private ValueAnimator widthAnimator;
    private final Runnable imeRequest;
    private final Runnable verifyImeClosed;
    private final ViewTreeObserver.OnGlobalLayoutListener imeLayoutListener;
    private int imeAttempt;
    private boolean expanded;
    private boolean hostInteractive = true;
    private boolean keyboardRequestArmed;
    private boolean imeVisibleKnown;
    private boolean imeVisible;
    private boolean imeSeenVisibleThisExpansion;
    private long suppressImeCollapseUntil;
    private int collapsedWidth;
    private int expandedWidth;
    private float currentRadiusPx;

    GlobalSettingsSearchBar(Context context, Provider provider) {
        super(context);
        this.provider = provider;
        collapsedWidth = dp(44);
        expandedWidth = resolveExpandedWidth();

        setClipChildren(false);
        setClipToPadding(false);
        setMinimumHeight(dp(44));
        setFocusable(false);

        shell = UiPalette.rounded(context, UiPalette.controlSurface(context), 22f);
        currentRadiusPx = dpFloat(22f);
        setBackground(shell);

        input = new EditText(context);
        input.setSingleLine(true);
        input.setTextSize(13.5f);
        input.setTextColor(UiPalette.textPrimary(context));
        input.setHintTextColor(UiPalette.textTertiary(context));
        input.setHint(R.string.global_search_hint);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(12), 0, dp(48), 0);
        input.setAlpha(0f);
        input.setFocusableInTouchMode(true);
        input.setFocusable(false);
        input.setCursorVisible(false);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setShowSoftInputOnFocus(true);
        addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.CENTER_VERTICAL));

        focusLine = new View(context);
        focusLine.setBackgroundColor(UiPalette.controlAccent(context));
        focusLine.setScaleX(0f);
        focusLine.setPivotX(dp(44));
        FrameLayout.LayoutParams lineParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM);
        lineParams.leftMargin = dp(10);
        lineParams.rightMargin = dp(10);
        addView(focusLine, lineParams);

        iconView = new SearchIconView(context);
        iconView.setContentDescription(context.getString(R.string.global_search_content_description));
        iconView.setFocusable(true);
        iconView.setClickable(true);
        iconView.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL);
        addView(iconView, iconParams);
        UiMotion.bindPressFeedback(iconView);

        imeRequest = this::tryShowKeyboard;
        verifyImeClosed = this::verifyImeClosedAfterDebounce;
        imeLayoutListener = this::observeImeVisibility;

        iconView.setOnClickListener(v -> {
            if (expanded) {
                collapse(true);
            } else {
                expand();
            }
        });

        input.setOnClickListener(v -> focusInputAndShowKeyboard());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s == null ? "" : s.toString();
                updateResults(value);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            List<Item> matches = provider == null
                    ? Collections.emptyList()
                    : provider.search(input.getText().toString());
            if (!matches.isEmpty()) select(matches.get(0));
            return true;
        });
    }

    void dismiss() {
        removeCallbacks(imeRequest);
        removeCallbacks(verifyImeClosed);
        dismissResults();
        if (widthAnimator != null) widthAnimator.cancel();
        input.animate().cancel();
        focusLine.animate().cancel();
        iconView.animate().cancel();
        hideKeyboard();
        input.clearFocus();
        expanded = false;
        keyboardRequestArmed = false;
        input.setCursorVisible(false);
        input.setFocusable(false);
    }

    /**
     * 页面 Back 优先关闭搜索，而不是直接退出 Activity。
     * 返回 true 表示这次 Back 已被搜索消费。
     */
    boolean collapseFromBack() {
        if (!expanded) return false;
        collapse(true);
        return true;
    }

    /**
     * Activity 的 ACTION_DOWN 会经过这里。只有明确点到搜索栏和结果 Popup 之外时才收起，
     * 不再依赖 EditText 焦点变化推断用户意图，避免 IME / Window focus 抢状态。
     */
    boolean collapseFromOutsideTouch(float rawX, float rawY) {
        if (!expanded) return false;
        if (containsRawPoint(this, rawX, rawY) || containsRawPoint(resultScroll, rawX, rawY)) return false;
        collapse(true);
        return true;
    }

    /** Activity 失去前台时只撤销 IME 请求，不把系统导致的键盘关闭误判成“用户收起搜索”。 */
    void onHostPause() {
        hostInteractive = false;
        keyboardRequestArmed = false;
        suppressImeCollapseUntil = SystemClock.uptimeMillis() + 1200L;
        removeCallbacks(imeRequest);
        removeCallbacks(verifyImeClosed);
        dismissResults();
        hideKeyboard();
    }

    void onHostResume() {
        hostInteractive = true;
        // Window / IME 在 Activity resume 阶段会短暂报告 hidden；给系统一次稳定窗口，避免误收起。
        suppressImeCollapseUntil = SystemClock.uptimeMillis() + IME_TRANSITION_GUARD_MS;
        post(this::observeImeVisibility);
    }

    private void expand() {
        if (expanded) return;
        expanded = true;
        imeSeenVisibleThisExpansion = false;
        suppressImeCollapseUntil = SystemClock.uptimeMillis() + IME_TRANSITION_GUARD_MS;
        removeCallbacks(imeRequest);
        removeCallbacks(verifyImeClosed);
        expandedWidth = resolveExpandedWidth();
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setCursorVisible(true);
        input.animate().cancel();
        input.animate().alpha(1f).setDuration(120L).setInterpolator(easeOut).start();
        focusLine.animate().cancel();
        focusLine.setPivotX(Math.max(1f, getWidth()));
        focusLine.animate().scaleX(1f).setDuration(EXPAND_MS).setInterpolator(easeOut).start();
        animateWidth(expandedWidth, EXPAND_MS, 10f);
        focusInputAndShowKeyboard();
    }

    private void collapse(boolean clearQuery) {
        if (!expanded) return;
        expanded = false;
        keyboardRequestArmed = false;
        removeCallbacks(imeRequest);
        removeCallbacks(verifyImeClosed);
        suppressImeCollapseUntil = SystemClock.uptimeMillis() + IME_TRANSITION_GUARD_MS;
        imeAttempt = 0;
        dismissResults();

        // 收起必须是一次完整事务：先结束 IME/focus，再执行视觉动画。
        // 不能只 setFocusable(false)，否则部分 OEM 的输入法仍会保持显示，看起来像“收不起来”。
        hideKeyboard();
        input.clearFocus();
        if (clearQuery && input.length() > 0) input.setText("");

        input.animate().cancel();
        input.animate().alpha(0f).setDuration(100L).setInterpolator(easeOut).start();
        focusLine.animate().cancel();
        focusLine.animate().scaleX(0f).setDuration(COLLAPSE_MS).setInterpolator(easeOut).start();
        animateWidth(collapsedWidth, COLLAPSE_MS, 22f);
        input.setCursorVisible(false);
        input.setFocusable(false);
    }

    private void animateWidth(int targetWidth, long duration, float targetRadiusDp) {
        if (widthAnimator != null) widthAnimator.cancel();
        ViewGroup.LayoutParams params = getLayoutParams();
        int startWidth = getWidth() > 0 ? getWidth()
                : (params != null && params.width > 0 ? params.width : collapsedWidth);
        float startRadius = currentRadiusPx;
        float targetRadius = dpFloat(targetRadiusDp);

        widthAnimator = ValueAnimator.ofFloat(0f, 1f);
        widthAnimator.setDuration(duration);
        widthAnimator.setInterpolator(easeOut);
        widthAnimator.addUpdateListener(animation -> {
            float p = (float) animation.getAnimatedValue();
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null) {
                lp.width = Math.round(startWidth + (targetWidth - startWidth) * p);
                setLayoutParams(lp);
            }
            currentRadiusPx = startRadius + (targetRadius - startRadius) * p;
            shell.setCornerRadius(currentRadiusPx);
        });
        widthAnimator.start();
    }

    private void updateResults(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (!expanded || query.isEmpty()) {
            dismissResults();
            return;
        }
        List<Item> items = provider == null ? Collections.emptyList() : provider.search(query);
        showResults(items == null ? Collections.emptyList() : items);
    }

    private void showResults(List<Item> items) {
        Context context = getContext();
        int popupWidth = Math.min(dp(320), context.getResources().getDisplayMetrics().widthPixels - dp(24));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(6), dp(6), dp(6), dp(6));
        list.setBackground(UiChrome.popupSurface(context));

        if (items.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(R.string.global_search_no_results);
            empty.setTextColor(UiPalette.textTertiary(context));
            empty.setTextSize(12.5f);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(dp(12), 0, dp(12), 0);
            AppTypeface.applyIfSelected(empty);
            empty.setMinHeight(dp(52));
            list.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            int count = Math.min(MAX_RESULTS, items.size());
            for (int i = 0; i < count; i++) {
                Item item = items.get(i);
                LinearLayout row = createResultRow(item);
                row.setMinimumHeight(dp(58));
                list.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                if (i + 1 < count) {
                    View divider = new View(context);
                    divider.setBackgroundColor(UiPalette.divider(context));
                    LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                    dividerParams.leftMargin = dp(12);
                    dividerParams.rightMargin = dp(12);
                    list.addView(divider, dividerParams);
                }
            }
        }

        if (resultScroll == null) {
            resultScroll = new ScrollView(context);
            resultScroll.setFillViewport(false);
            resultScroll.setClipToPadding(false);
            resultScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        } else {
            resultScroll.removeAllViews();
        }
        resultScroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        resultScroll.scrollTo(0, 0);

        int itemCount = items.isEmpty() ? 1 : Math.min(MAX_RESULTS, items.size());
        int desiredHeight = dp(12) + itemCount * dp(58) + Math.max(0, itemCount - 1) * dp(1);
        int maxHeight = Math.min(dp(360), Math.round(
                context.getResources().getDisplayMetrics().heightPixels * 0.48f));
        int popupHeight = Math.min(desiredHeight, maxHeight);

        if (resultPopup == null) {
            resultPopup = new PopupWindow(resultScroll, popupWidth, popupHeight, false);
            resultPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            resultPopup.setOutsideTouchable(true);
            resultPopup.setFocusable(false);
            resultPopup.setClippingEnabled(true);
            resultPopup.setElevation(dp(6));
        }
        resultPopup.setWidth(popupWidth);
        resultPopup.setHeight(popupHeight);

        if (resultPopup.isShowing()) {
            resultPopup.update(this, getWidth() - popupWidth, dp(6), popupWidth, popupHeight);
        } else if (isShown() && getWindowToken() != null) {
            resultPopup.showAsDropDown(this, getWidth() - popupWidth, dp(6));
        }
    }

    private LinearLayout createResultRow(Item item) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.setBackground(UiChrome.ripple(context, UiPalette.surfaceRaised(context), 9f));
        row.setFocusable(true);
        row.setClickable(true);

        TextView title = new TextView(context);
        title.setText(item.title);
        title.setTextColor(UiPalette.textPrimary(context));
        title.setTextSize(13.5f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        AppTypeface.applyIfSelected(title);
        row.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!item.subtitle.isEmpty()) {
            TextView subtitle = new TextView(context);
            subtitle.setText(item.subtitle);
            subtitle.setTextColor(UiPalette.textTertiary(context));
            subtitle.setTextSize(11f);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            AppTypeface.applyIfSelected(subtitle);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = dp(2);
            row.addView(subtitle, subtitleParams);
        }

        row.setOnClickListener(v -> select(item));
        UiMotion.bindPressFeedback(row);
        return row;
    }

    private void select(Item item) {
        collapse(true);
        if (provider != null) provider.onItemSelected(item);
    }

    private void dismissResults() {
        if (resultPopup != null && resultPopup.isShowing()) resultPopup.dismiss();
    }

    private boolean containsRawPoint(View view, float rawX, float rawY) {
        if (view == null || !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX < location[0] + view.getWidth()
                && rawY >= location[1] && rawY < location[1] + view.getHeight();
    }

    private int resolveExpandedWidth() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(176), Math.min(dp(238), Math.round(screen * 0.58f)));
    }

    private void focusInputAndShowKeyboard() {
        keyboardRequestArmed = true;
        suppressImeCollapseUntil = SystemClock.uptimeMillis() + IME_TRANSITION_GUARD_MS;
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setCursorVisible(true);
        input.requestFocusFromTouch();
        input.requestFocus();
        String currentQuery = input.getText() == null ? "" : input.getText().toString();
        if (expanded && !currentQuery.trim().isEmpty()) updateResults(currentQuery);
        requestKeyboardWhenReady();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) observer.addOnGlobalLayoutListener(imeLayoutListener);
        post(this::observeImeVisibility);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(imeRequest);
        removeCallbacks(verifyImeClosed);
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(imeLayoutListener);
        super.onDetachedFromWindow();
    }

    /**
     * 搜索的收起触发源改成“IME 由可见变为真正不可见”，而不是焦点/Window 状态猜测。
     * API 30+ 通过兼容反射读取 IME visibility；旧系统用可见窗口高度差回退。
     */
    private void observeImeVisibility() {
        Boolean visible = readImeVisible();
        if (visible == null) return;
        boolean nextVisible = visible;
        boolean wasKnown = imeVisibleKnown;
        boolean previous = imeVisible;
        imeVisibleKnown = true;
        imeVisible = nextVisible;

        if (nextVisible) {
            if (expanded) imeSeenVisibleThisExpansion = true;
            removeCallbacks(verifyImeClosed);
            return;
        }

        if (!wasKnown || previous == nextVisible) return;
        if (!shouldArmImeCloseCollapse()) return;
        removeCallbacks(verifyImeClosed);
        postDelayed(verifyImeClosed, IME_CLOSE_VERIFY_MS);
    }

    private void verifyImeClosedAfterDebounce() {
        if (!shouldArmImeCloseCollapse()) return;
        Boolean visible = readImeVisible();
        if (Boolean.TRUE.equals(visible)) {
            imeVisible = true;
            imeSeenVisibleThisExpansion = true;
            return;
        }
        if (visible == null) return;
        imeVisible = false;
        // 用户明确关闭输入法后，搜索栏作为同一输入 workflow 一起收起。
        collapse(true);
    }

    private boolean shouldArmImeCloseCollapse() {
        return expanded
                && hostInteractive
                && imeSeenVisibleThisExpansion
                && input.hasFocus()
                && hasWindowFocus()
                && SystemClock.uptimeMillis() >= suppressImeCollapseUntil;
    }

    private Boolean readImeVisible() {
        View root = getRootView();
        if (root == null || !isAttachedToWindow()) return null;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = root.getRootWindowInsets();
            if (insets != null) {
                Boolean api30Visible = readImeVisibleApi30(insets);
                if (api30Visible != null) return api30Visible;
            }
        }

        Rect visibleFrame = new Rect();
        root.getWindowVisibleDisplayFrame(visibleFrame);
        int rootHeight = root.getHeight();
        if (rootHeight <= 0 || visibleFrame.bottom <= 0) return null;
        int obscuredBottom = Math.max(0, rootHeight - visibleFrame.bottom);
        int threshold = Math.max(dp(120), Math.round(rootHeight * 0.18f));
        return obscuredBottom > threshold;
    }


    /**
     * API 30+ 的 WindowInsets.Type 在部分 Termux SDK stub 中没有暴露完整嵌套类型。
     * 这里通过反射读取 IME type 与可见性，避免编译期直接依赖 WindowInsets.Type，
     * 同时保留 Android 11+ 的真实 IME visibility 判定。失败时回退到可见区域高度方案。
     */
    private Boolean readImeVisibleApi30(WindowInsets insets) {
        try {
            Class<?> typeClass = Class.forName("android.view.WindowInsets$Type");
            java.lang.reflect.Method imeMethod = typeClass.getMethod("ime");
            Object typeValue = imeMethod.invoke(null);
            if (!(typeValue instanceof Integer)) return null;

            java.lang.reflect.Method isVisibleMethod = WindowInsets.class.getMethod("isVisible", int.class);
            Object result = isVisibleMethod.invoke(insets, ((Integer) typeValue).intValue());
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void requestKeyboardWhenReady() {
        removeCallbacks(imeRequest);
        imeAttempt = 0;
        if (!hostInteractive || !keyboardRequestArmed) return;
        post(imeRequest);
    }

    private void tryShowKeyboard() {
        if (!hostInteractive || !keyboardRequestArmed || !expanded || !input.isAttachedToWindow()) return;
        if (!input.hasFocus()) input.requestFocus();

        // showSoftInput 只有在 EditText 与所属 Window 同时获得焦点时才可靠。
        // 用一个可取消的重试 Runnable 等待 Window focus，而不是同时堆 0/80/220ms 三个请求。
        if (input.hasFocus() && input.hasWindowFocus()) {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, 0);
            return;
        }
        if (++imeAttempt < 8) postDelayed(imeRequest, 60L);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            suppressImeCollapseUntil = SystemClock.uptimeMillis() + IME_TRANSITION_GUARD_MS;
            removeCallbacks(verifyImeClosed);
            return;
        }
        post(this::observeImeVisibility);
        if (hostInteractive && keyboardRequestArmed && expanded && input.hasFocus()) {
            requestKeyboardWhenReady();
        }
    }

    private void hideKeyboard() {
        removeCallbacks(imeRequest);
        imeAttempt = 0;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private int dp(float value) {
        return Math.max(1, Math.round(dpFloat(value)));
    }

    private float dpFloat(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class SearchIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect bounds = new Rect();

        SearchIconView(Context context) {
            super(context);
            paint.setColor(UiPalette.textPrimary(context));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(1.5f, context.getResources().getDisplayMetrics().density * 1.55f));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            getDrawingRect(bounds);
            float density = getResources().getDisplayMetrics().density;
            float cx = bounds.exactCenterX() - 1.5f * density;
            float cy = bounds.exactCenterY() - 1.5f * density;
            float radius = 6.2f * density;
            canvas.drawCircle(cx, cy, radius, paint);
            float d = 4.4f * density;
            float start = radius * 0.68f;
            canvas.drawLine(cx + start, cy + start, cx + start + d, cy + start + d, paint);
        }
    }
}
