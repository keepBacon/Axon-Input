package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Super-custom key display editor.
 * Supports live style editing, CPS templates, free dragging and selection guides.
 */
public final class SuperCustomDisplayActivity extends Activity {
    private static final int CAPTURE_NONE = 0;
    private static final int CAPTURE_NEW_CONTROL = 1;
    private static final int CAPTURE_REBIND = 2;

    private final List<ControlBinding> controls = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout canvas;
    private TextView addButton;
    private TextView capturePrompt;
    private SelectionGuideView selectionGuide;
    private LinearLayout selectionPanel;
    private TextView selectedKeyValue;
    private TextView selectedXValue;
    private TextView selectedYValue;
    private Dialog controlEditorDialog;

    private ControlBinding selectedBinding;
    private ControlBinding rebindTarget;
    private int captureMode = CAPTURE_NONE;

    private final Runnable rebindTimeout = () -> {
        if (captureMode != CAPTURE_REBIND) return;
        captureMode = CAPTURE_NONE;
        rebindTarget = null;
        hideCapturePrompt();
        Toast.makeText(this, R.string.super_custom_rebind_timeout, Toast.LENGTH_SHORT).show();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK
                ? R.style.AppThemeBlack : R.style.AppThemeLight);
        super.onCreate(savedInstanceState);
        applySystemBars();
        buildCanvas();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBars();
        if (controlEditorDialog != null && controlEditorDialog.isShowing()) {
            resizeEditorWindow(controlEditorDialog);
        }
        if (canvas != null) canvas.post(this::clampAllControlsToCanvas);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(rebindTimeout);
        super.onDestroy();
    }

    private void buildCanvas() {
        canvas = new FrameLayout(this);
        canvas.setBackgroundColor(UiPalette.background(this));
        canvas.setClickable(true);
        canvas.setOnClickListener(v -> clearSelection());
        setContentView(canvas);

        addButton = new TextView(this);
        addButton.setText("+");
        addButton.setTextSize(28f);
        addButton.setGravity(Gravity.CENTER);
        addButton.setTextColor(UiPalette.textPrimary(this));
        addButton.setIncludeFontPadding(false);
        addButton.setClickable(true);
        addButton.setFocusable(true);
        addButton.setBackground(circleDrawable(UiPalette.surface(this)));
        UiMotion.bindPressFeedback(addButton);
        addButton.setOnClickListener(v -> beginKeyCapture());
        FrameLayout.LayoutParams addLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        addLp.gravity = Gravity.TOP | Gravity.START;
        addLp.leftMargin = dp(16);
        addLp.topMargin = dp(16);
        canvas.addView(addButton, addLp);

        selectionGuide = new SelectionGuideView(this);
        selectionGuide.setVisibility(View.GONE);
        selectionGuide.setClickable(false);
        selectionGuide.setFocusable(false);
        canvas.addView(selectionGuide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildSelectionPanel();

        capturePrompt = new TextView(this);
        capturePrompt.setText(R.string.super_custom_capture_prompt);
        capturePrompt.setTextSize(15f);
        capturePrompt.setTextColor(UiPalette.textPrimary(this));
        capturePrompt.setGravity(Gravity.CENTER);
        capturePrompt.setPadding(dp(20), dp(12), dp(20), dp(12));
        capturePrompt.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 14f));
        capturePrompt.setVisibility(View.GONE);
        FrameLayout.LayoutParams promptLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        promptLp.gravity = Gravity.CENTER;
        canvas.addView(capturePrompt, promptLp);
    }

    private void buildSelectionPanel() {
        selectionPanel = new LinearLayout(this);
        selectionPanel.setOrientation(LinearLayout.HORIZONTAL);
        selectionPanel.setGravity(Gravity.CENTER_VERTICAL);
        selectionPanel.setPadding(dp(14), dp(10), dp(10), dp(10));
        selectionPanel.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 14f));
        selectionPanel.setVisibility(View.GONE);
        selectionPanel.setClickable(true);

        LinearLayout infoColumn = new LinearLayout(this);
        infoColumn.setOrientation(LinearLayout.VERTICAL);
        infoColumn.setGravity(Gravity.CENTER_VERTICAL);
        selectionPanel.addView(infoColumn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        selectedKeyValue = addSelectionInfoRow(infoColumn,
                getString(R.string.super_custom_selected_key), () -> beginSelectedKeyRebind());
        selectedXValue = addSelectionInfoRow(infoColumn,
                getString(R.string.super_custom_selected_x), () -> editSelectedCoordinate(true));
        selectedYValue = addSelectionInfoRow(infoColumn,
                getString(R.string.super_custom_selected_y), () -> editSelectedCoordinate(false));

        Button edit = actionButton(R.string.super_custom_edit);
        edit.setOnClickListener(v -> editSelectedControl());
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(dp(92), dp(44));
        editLp.leftMargin = dp(12);
        selectionPanel.addView(edit, editLp);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.TOP;
        panelLp.leftMargin = dp(12);
        panelLp.rightMargin = dp(12);
        panelLp.topMargin = dp(10);
        canvas.addView(selectionPanel, panelLp);
    }

    private TextView addSelectionInfoRow(LinearLayout root, String label, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(11f);
        title.setTextColor(UiPalette.textSecondary(this));
        title.setIncludeFontPadding(false);
        row.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)));
        title.setGravity(Gravity.CENTER_VERTICAL);

        TextView value = new TextView(this);
        value.setTextSize(11f);
        value.setTextColor(UiPalette.accent(this));
        value.setIncludeFontPadding(false);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setPadding(dp(6), 0, dp(8), 0);
        value.setClickable(true);
        value.setFocusable(true);
        value.setOnClickListener(v -> action.run());
        row.addView(value, new LinearLayout.LayoutParams(0, dp(26), 1f));

        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        return value;
    }

    private void beginKeyCapture() {
        mainHandler.removeCallbacks(rebindTimeout);
        captureMode = CAPTURE_NEW_CONTROL;
        rebindTarget = null;
        capturePrompt.setText(R.string.super_custom_capture_prompt);
        showCapturePrompt();
    }

    private void beginSelectedKeyRebind() {
        if (selectedBinding == null) return;
        mainHandler.removeCallbacks(rebindTimeout);
        captureMode = CAPTURE_REBIND;
        rebindTarget = selectedBinding;
        capturePrompt.setText(R.string.super_custom_rebind_prompt);
        showCapturePrompt();
        mainHandler.postDelayed(rebindTimeout, 3000L);
    }

    private void showCapturePrompt() {
        capturePrompt.setVisibility(View.VISIBLE);
        capturePrompt.bringToFront();
    }

    private void hideCapturePrompt() {
        if (capturePrompt != null) capturePrompt.setVisibility(View.GONE);
    }

    private void finishCapture() {
        mainHandler.removeCallbacks(rebindTimeout);
        captureMode = CAPTURE_NONE;
        rebindTarget = null;
        hideCapturePrompt();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (captureMode != CAPTURE_NONE && isPhysicalKeyboardEvent(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (captureMode == CAPTURE_REBIND && rebindTarget != null) {
                    ControlBinding target = rebindTarget;
                    target.spec.keyCode = event.getKeyCode();
                    finishCapture();
                    if (selectedBinding == target) updateSelectionPanel();
                } else if (captureMode == CAPTURE_NEW_CONTROL) {
                    int keyCode = event.getKeyCode();
                    finishCapture();
                    showControlEditor(keyCode);
                }
            }
            return true;
        }

        boolean handled = false;
        if (isPhysicalKeyboardEvent(event)) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            boolean up = event.getAction() == KeyEvent.ACTION_UP;
            if (down || up) {
                for (ControlBinding binding : controls) {
                    if (binding.spec.keyCode == event.getKeyCode()) {
                        if (!down || event.getRepeatCount() == 0) {
                            binding.view.onBoundKeyEvent(down);
                        }
                        handled = true;
                    }
                }
            }
        }
        return handled || super.dispatchKeyEvent(event);
    }

    private void showControlEditor(int keyCode) {
        boolean dark = OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK;
        SuperCustomControlSpec spec = new SuperCustomControlSpec(
                keyCode, KeyLabel.fromKeyCode(keyCode), dark);
        showControlEditor(null, spec);
    }

    private void editSelectedControl() {
        if (selectedBinding == null) return;
        showControlEditor(selectedBinding, selectedBinding.spec.copy());
    }

    private void showControlEditor(ControlBinding editing, SuperCustomControlSpec spec) {
        Dialog dialog = new Dialog(this);
        controlEditorDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(12), dp(12), dp(12), dp(10));
        shell.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 18f));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.TOP);
        shell.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(true);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(6), dp(2), dp(10), dp(8));
        leftScroll.addView(left, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(leftScroll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.46f));

        View divider = new View(this);
        divider.setBackgroundColor(UiPalette.divider(this));
        body.addView(divider, new LinearLayout.LayoutParams(dp(1),
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), dp(2), dp(4), dp(6));
        body.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.54f));

        TextView generalLabel = smallSectionLabel(R.string.super_custom_general);
        generalLabel.setGravity(Gravity.END);
        left.addView(generalLabel, wrapParams(dp(4)));

        Spinner motionSpinner = choiceSpinner(new String[]{
                getString(R.string.motion_size),
                getString(R.string.motion_alpha),
                getString(R.string.motion_ripple),
                getString(R.string.motion_none)});
        left.addView(labeledChoice(R.string.super_custom_press_motion, motionSpinner), wrapParams(dp(5)));

        SeekControl corner = addSeekControl(left, R.string.super_custom_corner, 0, 36, spec.cornerDp, " dp");
        SeekControl opacity = addSeekControl(left, R.string.super_custom_opacity, 20, 100,
                spec.opacityPercent, "%");
        SeekControl width = addSeekControl(left, R.string.super_custom_width, 44, 240,
                spec.widthDp, " dp");
        SeekControl height = addSeekControl(left, R.string.super_custom_length, 32, 160,
                spec.heightDp, " dp");

        ColorRow pressColor = addColorRow(left, R.string.super_custom_press_color, spec.pressColor);
        ColorRow textColor = addColorRow(left, R.string.super_custom_text_color, spec.textColor);
        SeekControl textSize = addSeekControl(left, R.string.super_custom_text_size, 10, 36,
                spec.textSizeSp, " sp");

        TextView addonsLabel = smallSectionLabel(R.string.super_custom_addons);
        addonsLabel.setGravity(Gravity.START);
        left.addView(addonsLabel, wrapParams(dp(4)));

        Switch cpsSwitch = themedSwitch(R.string.super_custom_cps_switch);
        cpsSwitch.setTextSize(13f);
        left.addView(cpsSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), dp(10), dp(12), dp(10));
        info.setBackground(UiPalette.rounded(this, UiPalette.debugSurface(this), 12f));
        right.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView keyInfo = infoText();
        TextView sizeInfo = infoText();
        info.addView(keyInfo, wrapParams(dp(3)));
        info.addView(sizeInfo, wrapParams(0));

        FrameLayout previewArea = new FrameLayout(this);
        previewArea.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewArea.setBackground(UiPalette.rounded(this, UiPalette.debugSurface(this), 12f));
        LinearLayout.LayoutParams previewAreaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        previewAreaLp.topMargin = dp(10);
        right.addView(previewArea, previewAreaLp);

        SuperCustomControlView preview = new SuperCustomControlView(this);
        preview.setInteractivePreview(true);
        FrameLayout.LayoutParams previewLp = new FrameLayout.LayoutParams(
                dp(spec.widthDp), dp(spec.heightDp));
        previewLp.gravity = Gravity.CENTER;
        previewArea.addView(preview, previewLp);
        preview.applySpec(spec);

        Runnable refreshPreview = () -> {
            spec.cornerDp = corner.seek.getProgress();
            spec.opacityPercent = opacity.seek.getProgress();
            spec.widthDp = width.seek.getProgress();
            spec.heightDp = height.seek.getProgress();
            spec.textSizeSp = textSize.seek.getProgress();
            spec.pressColor = pressColor.color;
            spec.textColor = textColor.color;
            spec.cpsEnabled = cpsSwitch.isChecked();
            preview.applySpec(spec);
            keyInfo.setText(getString(R.string.super_custom_info_key, KeyLabel.fromKeyCode(spec.keyCode)));
            sizeInfo.setText(getString(R.string.super_custom_info_size, spec.widthDp, spec.heightDp));
        };

        int motionSelection = spec.motionMode == OverlayState.MOTION_ALPHA ? 1
                : spec.motionMode == OverlayState.MOTION_RIPPLE ? 2
                : spec.motionMode == OverlayState.MOTION_NONE ? 3 : 0;
        motionSpinner.setSelection(motionSelection, false);
        cpsSwitch.setChecked(spec.cpsEnabled);
        motionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                spec.motionMode = position == 1 ? OverlayState.MOTION_ALPHA
                        : position == 2 ? OverlayState.MOTION_RIPPLE
                        : position == 3 ? OverlayState.MOTION_NONE
                        : OverlayState.MOTION_SIZE;
                preview.applySpec(spec);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        bindSeekRefresh(corner, refreshPreview);
        bindSeekRefresh(opacity, refreshPreview);
        bindSeekRefresh(width, refreshPreview);
        bindSeekRefresh(height, refreshPreview);
        bindSeekRefresh(textSize, refreshPreview);
        cpsSwitch.setOnCheckedChangeListener((button, checked) -> {
            spec.cpsEnabled = checked;
            refreshPreview.run();
        });

        pressColor.row.setOnClickListener(v -> showColorEditor(spec.pressColor, color -> {
            spec.pressColor = color;
            pressColor.color = color;
            pressColor.dot.setBackground(circleDrawable(color));
            refreshPreview.run();
        }));
        textColor.row.setOnClickListener(v -> showColorEditor(spec.textColor, color -> {
            spec.textColor = color;
            textColor.color = color;
            textColor.dot.setBackground(circleDrawable(color));
            refreshPreview.run();
        }));

        preview.labelView().setOnClickListener(v -> showTextEditor(
                getString(R.string.super_custom_edit_text_title), spec.labelText, value -> {
                    spec.labelText = value;
                    refreshPreview.run();
                }));
        preview.cpsView().setOnClickListener(v -> {
            if (!spec.cpsEnabled) return;
            showTextEditor(getString(R.string.super_custom_edit_cps_title), spec.cpsTemplate, value -> {
                spec.cpsTemplate = value;
                refreshPreview.run();
            });
        });

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(8), 0, 0);
        Button confirm = actionButton(R.string.super_custom_confirm);
        footer.addView(confirm, new LinearLayout.LayoutParams(dp(104), dp(44)));
        shell.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        confirm.setOnClickListener(v -> {
            refreshPreview.run();
            if (spec.cpsEnabled && !SuperCustomControlSpec.isValidCpsTemplate(spec.cpsTemplate)) {
                Toast.makeText(this, R.string.super_custom_cps_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            if (editing == null) {
                createControl(spec.copy());
            } else {
                applyEditedControl(editing, spec.copy());
            }
            dialog.dismiss();
        });

        refreshPreview.run();
        dialog.setContentView(shell);
        dialog.setOnDismissListener(d -> {
            if (controlEditorDialog == dialog) controlEditorDialog = null;
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.30f;
            window.setAttributes(attrs);
        }
        dialog.show();
        resizeEditorWindow(dialog);
    }

    private void createControl(SuperCustomControlSpec spec) {
        SuperCustomControlView view = new SuperCustomControlView(this);
        view.setInteractivePreview(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(spec.widthDp), dp(spec.heightDp));
        lp.gravity = Gravity.TOP | Gravity.START;
        view.setLayoutParams(lp);
        view.applySpec(spec);

        ControlBinding binding = new ControlBinding(spec, view);
        installCreatedControlTouch(binding);
        canvas.addView(view);
        controls.add(binding);

        canvas.post(() -> {
            if (binding.spec.centerXPx < 0 || binding.spec.centerYPx < 0) {
                binding.spec.centerXPx = canvas.getWidth() / 2;
                binding.spec.centerYPx = canvas.getHeight() / 2;
            }
            applyControlPosition(binding);
            selectControl(binding);
        });
    }

    private void applyEditedControl(ControlBinding binding, SuperCustomControlSpec edited) {
        int centerX = binding.spec.centerXPx;
        int centerY = binding.spec.centerYPx;
        edited.centerXPx = centerX;
        edited.centerYPx = centerY;
        binding.spec = edited;
        binding.view.applySpec(edited);
        applyControlPosition(binding);
        if (selectedBinding == binding) {
            updateSelectionPanel();
            updateSelectionGuides();
        }
    }

    private void installCreatedControlTouch(ControlBinding binding) {
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        binding.view.setOnTouchListener(new View.OnTouchListener() {
            float downRawX;
            float downRawY;
            int startCenterX;
            int startCenterY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        selectControl(binding);
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startCenterX = binding.spec.centerXPx;
                        startCenterY = binding.spec.centerYPx;
                        dragging = false;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            binding.spec.centerXPx = startCenterX + Math.round(dx);
                            binding.spec.centerYPx = startCenterY + Math.round(dy);
                            applyControlPosition(binding);
                            updateSelectionPanel();
                            updateSelectionGuides();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        selectControl(binding);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void selectControl(ControlBinding binding) {
        selectedBinding = binding;
        if (selectionPanel != null) selectionPanel.setVisibility(View.VISIBLE);
        if (addButton != null) addButton.setVisibility(View.GONE);
        if (selectionGuide != null) selectionGuide.setVisibility(View.VISIBLE);
        updateSelectionPanel();
        updateSelectionGuides();
        bringEditorChromeToFront();
    }

    private void clearSelection() {
        selectedBinding = null;
        if (selectionPanel != null) selectionPanel.setVisibility(View.GONE);
        if (selectionGuide != null) {
            selectionGuide.setVisibility(View.GONE);
            selectionGuide.invalidate();
        }
        if (addButton != null) addButton.setVisibility(View.VISIBLE);
        finishCapture();
    }

    private void bringEditorChromeToFront() {
        if (selectionGuide != null) selectionGuide.bringToFront();
        if (selectionPanel != null) selectionPanel.bringToFront();
        if (addButton != null && addButton.getVisibility() == View.VISIBLE) addButton.bringToFront();
        if (capturePrompt != null && capturePrompt.getVisibility() == View.VISIBLE) capturePrompt.bringToFront();
    }

    private void updateSelectionPanel() {
        if (selectedBinding == null) return;
        selectedKeyValue.setText(KeyLabel.fromKeyCode(selectedBinding.spec.keyCode));
        selectedXValue.setText(String.valueOf(selectedBinding.spec.centerXPx));
        selectedYValue.setText(String.valueOf(selectedBinding.spec.centerYPx));
    }

    private void updateSelectionGuides() {
        if (selectionGuide != null) selectionGuide.invalidate();
        bringEditorChromeToFront();
    }

    private void editSelectedCoordinate(boolean xAxis) {
        if (selectedBinding == null) return;
        int initial = xAxis ? selectedBinding.spec.centerXPx : selectedBinding.spec.centerYPx;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(String.valueOf(initial));
        input.setSelection(input.length());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(UiPalette.textPrimary(this));

        new AlertDialog.Builder(this)
                .setTitle(xAxis ? R.string.super_custom_edit_x : R.string.super_custom_edit_y)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (xAxis) selectedBinding.spec.centerXPx = value;
                        else selectedBinding.spec.centerYPx = value;
                        applyControlPosition(selectedBinding);
                        updateSelectionPanel();
                        updateSelectionGuides();
                    } catch (NumberFormatException error) {
                        Toast.makeText(this, R.string.super_custom_coordinate_invalid,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void applyControlPosition(ControlBinding binding) {
        if (canvas == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        int width = dp(binding.spec.widthDp);
        int height = dp(binding.spec.heightDp);
        int halfW = width / 2;
        int halfH = height / 2;

        binding.spec.centerXPx = clamp(binding.spec.centerXPx, halfW, Math.max(halfW, canvas.getWidth() - halfW));
        binding.spec.centerYPx = clamp(binding.spec.centerYPx, halfH, Math.max(halfH, canvas.getHeight() - halfH));

        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) binding.view.getLayoutParams();
        p.width = width;
        p.height = height;
        p.gravity = Gravity.TOP | Gravity.START;
        p.leftMargin = binding.spec.centerXPx - halfW;
        p.topMargin = binding.spec.centerYPx - halfH;
        binding.view.setLayoutParams(p);
    }

    private void clampAllControlsToCanvas() {
        if (canvas == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        for (ControlBinding binding : controls) applyControlPosition(binding);
        updateSelectionPanel();
        updateSelectionGuides();
    }

    private RectF selectedControlRect() {
        if (selectedBinding == null) return null;
        View view = selectedBinding.view;
        return new RectF(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }

    private int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private void resizeEditorWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        window.setLayout(Math.max(dp(300), Math.round(width * 0.94f)),
                Math.max(dp(360), Math.round(height * 0.86f)));
        window.setGravity(Gravity.CENTER);
    }

    private SeekControl addSeekControl(LinearLayout root, int titleRes, int min, int max,
                                       int initial, String suffix) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(2), 0, dp(2));

        TextView title = new TextView(this);
        title.setTextColor(UiPalette.textSecondary(this));
        title.setTextSize(12f);
        TextView value = new TextView(this);
        value.setTextColor(UiPalette.textTertiary(this));
        value.setTextSize(11f);
        value.setGravity(Gravity.END);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title.setText(titleRes);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        block.addView(header, wrapParams(0));

        SeekBar seek = new SeekBar(this);
        seek.setMin(min);
        seek.setMax(max);
        seek.setProgress(initial);
        seek.setMinimumHeight(dp(32));
        tintSeekBar(seek);
        block.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        value.setText(initial + suffix);
        root.addView(block, wrapParams(dp(1)));
        return new SeekControl(seek, value, suffix);
    }

    private void bindSeekRefresh(SeekControl control, Runnable refresh) {
        control.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                control.value.setText(progress + control.suffix);
                refresh.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private ColorRow addColorRow(LinearLayout root, int titleRes, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(4), dp(2), dp(4));
        row.setClickable(true);
        row.setFocusable(true);
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(UiPalette.textSecondary(this));
        title.setTextSize(12f);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(38), 1f));
        title.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(circleDrawable(color));
        row.addView(dot, new LinearLayout.LayoutParams(dp(24), dp(24)));
        UiMotion.bindPressFeedback(row);
        root.addView(row, wrapParams(dp(1)));
        return new ColorRow(row, dot, color);
    }

    private LinearLayout labeledChoice(int titleRes, Spinner spinner) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(titleRes);
        label.setTextColor(UiPalette.textSecondary(this));
        label.setTextSize(12f);
        block.addView(label, wrapParams(dp(3)));
        block.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return block;
    }

    private Spinner choiceSpinner(String[] items) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(UiPalette.textPrimary(SuperCustomDisplayActivity.this));
                    ((TextView) view).setTextSize(12f);
                }
                return view;
            }
        };
        spinner.setAdapter(adapter);
        spinner.setBackground(UiPalette.rounded(this, UiPalette.controlSurface(this), 9f));
        spinner.setPadding(dp(8), 0, dp(8), 0);
        return spinner;
    }

    private Switch themedSwitch(int textRes) {
        Switch sw = new Switch(this);
        sw.setText(textRes);
        sw.setTextColor(UiPalette.textPrimary(this));
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}
        };
        sw.setTrackTintList(new ColorStateList(states, new int[]{
                UiPalette.switchTrackOn(this), UiPalette.switchTrackOff(this)}));
        sw.setThumbTintList(new ColorStateList(states, new int[]{
                UiPalette.switchThumbOn(this), UiPalette.switchThumbOff(this)}));
        return sw;
    }

    private Button actionButton(int textRes) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTextColor(OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK
                ? Color.rgb(20, 20, 22) : Color.WHITE);
        button.setBackground(UiPalette.rounded(this, UiPalette.accent(this), 10f));
        UiMotion.bindPressFeedback(button);
        return button;
    }

    private TextView smallSectionLabel(int textRes) {
        TextView text = new TextView(this);
        text.setText(textRes);
        text.setTextSize(10f);
        text.setTextColor(UiPalette.textTertiary(this));
        text.setIncludeFontPadding(false);
        return text;
    }

    private TextView infoText() {
        TextView text = new TextView(this);
        text.setTextSize(12f);
        text.setTextColor(UiPalette.textSecondary(this));
        return text;
    }

    private void showTextEditor(String title, String initial, TextCallback callback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial == null ? "" : initial);
        input.setSelection(input.length());
        input.setTextColor(UiPalette.textPrimary(this));
        input.setHintTextColor(UiPalette.textTertiary(this));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> callback.onText(input.getText().toString()))
                .show();
    }

    private void showColorEditor(int initial, ColorCallback callback) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), 0);

        int[] channels = new int[]{Color.red(initial), Color.green(initial), Color.blue(initial)};
        TextView preview = new TextView(this);
        preview.setGravity(Gravity.CENTER);
        preview.setTextSize(12f);
        preview.setTextColor(UiPalette.textPrimary(this));
        preview.setPadding(dp(8), dp(10), dp(8), dp(10));
        root.addView(preview, wrapParams(dp(8)));

        String[] names = new String[]{"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            TextView label = new TextView(this);
            label.setTextColor(UiPalette.textSecondary(this));
            label.setTextSize(12f);
            root.addView(label, wrapParams(0));
            SeekBar bar = new SeekBar(this);
            bar.setMax(255);
            bar.setProgress(channels[i]);
            tintSeekBar(bar);
            root.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
            Runnable updateLabel = () -> label.setText(names[index] + "  " + channels[index]);
            updateLabel.run();
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    channels[index] = progress;
                    updateLabel.run();
                    updateColorPreview(preview, channels);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
        updateColorPreview(preview, channels);

        new AlertDialog.Builder(this)
                .setTitle(R.string.super_custom_color_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        callback.onColor(Color.rgb(channels[0], channels[1], channels[2])))
                .show();
    }

    private void updateColorPreview(TextView preview, int[] channels) {
        int color = Color.rgb(channels[0], channels[1], channels[2]);
        preview.setText(String.format("#%06X", 0xFFFFFF & color));
        preview.setBackground(UiPalette.rounded(this, color, 9f));
        double luma = (0.299 * channels[0] + 0.587 * channels[1] + 0.114 * channels[2]);
        preview.setTextColor(luma > 150 ? Color.BLACK : Color.WHITE);
    }

    private void tintSeekBar(SeekBar seek) {
        ColorStateList tint = ColorStateList.valueOf(UiPalette.accent(this));
        seek.setProgressTintList(tint);
        seek.setThumbTintList(tint);
    }

    private GradientDrawable circleDrawable(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private LinearLayout.LayoutParams wrapParams(int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMargin;
        return lp;
    }

    private boolean isPhysicalKeyboardEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null || device.isVirtual()) return false;
        int sources = event.getSource();
        return (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
    }

    private void applySystemBars() {
        Window window = getWindow();
        if (window == null) return;
        int background = UiPalette.background(this);
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        int flags = window.getDecorView().getSystemUiVisibility();
        boolean darkTheme = OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK;
        if (darkTheme) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private final class SelectionGuideView extends View {
        private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF labelRect = new RectF();

        SelectionGuideView(Context context) {
            super(context);
            setWillNotDraw(false);

            int accent = UiPalette.accent(SuperCustomDisplayActivity.this);
            int primary = UiPalette.textPrimary(SuperCustomDisplayActivity.this);
            int surface = UiPalette.controlSurface(SuperCustomDisplayActivity.this);

            centerPaint.setStyle(Paint.Style.STROKE);
            centerPaint.setStrokeWidth(dp(1.25f));
            centerPaint.setColor(accent);
            centerPaint.setAlpha(205);

            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(dp(1f));
            edgePaint.setColor(UiPalette.textSecondary(SuperCustomDisplayActivity.this));
            edgePaint.setAlpha(145);
            edgePaint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0f));

            labelTextPaint.setStyle(Paint.Style.FILL);
            labelTextPaint.setColor(primary);
            labelTextPaint.setTextSize(sp(9.5f));
            labelTextPaint.setFakeBoldText(true);

            labelFillPaint.setStyle(Paint.Style.FILL);
            labelFillPaint.setColor(surface);
            labelFillPaint.setAlpha(238);

            labelStrokePaint.setStyle(Paint.Style.STROKE);
            labelStrokePaint.setStrokeWidth(dp(1f));
            labelStrokePaint.setColor(accent);
            labelStrokePaint.setAlpha(175);

            markerPaint.setStyle(Paint.Style.FILL);
            markerPaint.setColor(accent);
            markerPaint.setAlpha(230);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (selectedBinding == null) return;
            RectF r = selectedControlRect();
            if (r == null || r.width() <= 0 || r.height() <= 0) return;

            float centerX = r.centerX();
            float centerY = r.centerY();

            // Center crosshair: still split through the selected control so the guides never cover it.
            drawHorizontalOutsideRect(c, centerY, r, centerPaint);
            drawVerticalOutsideRect(c, centerX, r, centerPaint);

            // Bind each center label directly to its guide using an axis glyph and anchor marker.
            String centerXText = getString(R.string.super_custom_guide_x_center, Math.round(centerX));
            float centerXLabelX = chooseHorizontalLabelX(r, centerXText);
            drawGuideLabel(c, centerXText, centerXLabelX, centerY, true, centerPaint.getColor());

            String centerYText = getString(R.string.super_custom_guide_y_center, Math.round(centerY));
            float centerYLabelY = chooseVerticalLabelY(r, centerYText);
            drawGuideLabel(c, centerYText, centerX, centerYLabelY, false, centerPaint.getColor());

            // Four edge guides.
            c.drawLine(r.left, 0, r.left, getHeight(), edgePaint);
            c.drawLine(r.right, 0, r.right, getHeight(), edgePaint);
            c.drawLine(0, r.top, getWidth(), r.top, edgePaint);
            c.drawLine(0, r.bottom, getWidth(), r.bottom, edgePaint);

            // Vertical edge guides -> X coordinates. Labels sit on the exact vertical line.
            float topSafe = dp(8);
            drawGuideLabel(c, getString(R.string.super_custom_guide_x_left, Math.round(r.left)),
                    r.left, topSafe, false, edgePaint.getColor());
            drawGuideLabel(c, getString(R.string.super_custom_guide_x_right, Math.round(r.right)),
                    r.right, topSafe + dp(28), false, edgePaint.getColor());

            // Horizontal edge guides -> Y coordinates. Labels sit on the exact horizontal line.
            float leftSafe = dp(8);
            drawGuideLabel(c, getString(R.string.super_custom_guide_y_top, Math.round(r.top)),
                    leftSafe, r.top, true, edgePaint.getColor());
            drawGuideLabel(c, getString(R.string.super_custom_guide_y_bottom, Math.round(r.bottom)),
                    leftSafe, r.bottom, true, edgePaint.getColor());

            // Small endpoint ticks make it visually obvious which label belongs to which guide.
            drawEdgeTicks(c, r);
        }

        private void drawHorizontalOutsideRect(Canvas c, float y, RectF r, Paint paint) {
            if (y >= r.top && y <= r.bottom) {
                c.drawLine(0, y, Math.max(0, r.left), y, paint);
                c.drawLine(Math.min(getWidth(), r.right), y, getWidth(), y, paint);
            } else {
                c.drawLine(0, y, getWidth(), y, paint);
            }
        }

        private void drawVerticalOutsideRect(Canvas c, float x, RectF r, Paint paint) {
            if (x >= r.left && x <= r.right) {
                c.drawLine(x, 0, x, Math.max(0, r.top), paint);
                c.drawLine(x, Math.min(getHeight(), r.bottom), x, getHeight(), paint);
            } else {
                c.drawLine(x, 0, x, getHeight(), paint);
            }
        }

        private float chooseHorizontalLabelX(RectF r, String text) {
            float width = labelTextPaint.measureText(text) + dp(16);
            float rightSpace = getWidth() - r.right;
            if (rightSpace >= width + dp(8)) return r.right + dp(6);
            if (r.left >= width + dp(8)) return Math.max(dp(4), r.left - width - dp(6));
            return dp(8);
        }

        private float chooseVerticalLabelY(RectF r, String text) {
            float height = sp(9.5f) + dp(12);
            if (r.top >= height + dp(8)) return Math.max(dp(6), r.top - height - dp(6));
            if (getHeight() - r.bottom >= height + dp(8)) return r.bottom + dp(6);
            return dp(42);
        }

        /**
         * Draws a compact pill literally attached to the corresponding guide.
         * For horizontal guides x/y means left + lineY; for vertical guides x/y means lineX + top.
         */
        private void drawGuideLabel(Canvas c, String text, float x, float y,
                                    boolean horizontalGuide, int guideColor) {
            float textWidth = labelTextPaint.measureText(text);
            float padX = dp(7);
            float padY = dp(4);
            Paint.FontMetrics fm = labelTextPaint.getFontMetrics();
            float textHeight = fm.descent - fm.ascent;
            float boxW = textWidth + padX * 2;
            float boxH = textHeight + padY * 2;

            float left;
            float top;
            if (horizontalGuide) {
                left = clampFloat(x, dp(3), Math.max(dp(3), getWidth() - boxW - dp(3)));
                top = clampFloat(y - boxH / 2f, dp(3), Math.max(dp(3), getHeight() - boxH - dp(3)));
            } else {
                left = clampFloat(x - boxW / 2f, dp(3), Math.max(dp(3), getWidth() - boxW - dp(3)));
                top = clampFloat(y, dp(3), Math.max(dp(3), getHeight() - boxH - dp(3)));
            }

            labelRect.set(left, top, left + boxW, top + boxH);
            labelStrokePaint.setColor(guideColor);
            labelStrokePaint.setAlpha(horizontalGuide ? 190 : 165);
            c.drawRoundRect(labelRect, dp(6), dp(6), labelFillPaint);
            c.drawRoundRect(labelRect, dp(6), dp(6), labelStrokePaint);

            float baseline = top + padY - fm.ascent;
            c.drawText(text, left + padX, baseline, labelTextPaint);

            markerPaint.setColor(guideColor);
            markerPaint.setAlpha(235);
            if (horizontalGuide) {
                float markerX = clampFloat(x - dp(3), dp(4), getWidth() - dp(4));
                c.drawCircle(markerX, y, dp(2.2f), markerPaint);
            } else {
                float markerY = clampFloat(top + boxH + dp(3), dp(4), getHeight() - dp(4));
                c.drawCircle(x, markerY, dp(2.2f), markerPaint);
            }
        }

        private void drawEdgeTicks(Canvas c, RectF r) {
            float tick = dp(5);
            Paint p = markerPaint;
            p.setAlpha(210);

            c.drawRect(r.left - dp(1), r.centerY() - tick, r.left + dp(1), r.centerY() + tick, p);
            c.drawRect(r.right - dp(1), r.centerY() - tick, r.right + dp(1), r.centerY() + tick, p);
            c.drawRect(r.centerX() - tick, r.top - dp(1), r.centerX() + tick, r.top + dp(1), p);
            c.drawRect(r.centerX() - tick, r.bottom - dp(1), r.centerX() + tick, r.bottom + dp(1), p);
        }

        private float clampFloat(float value, float min, float max) {
            if (value < min) return min;
            if (value > max) return max;
            return value;
        }
    }

    private static final class ControlBinding {
        SuperCustomControlSpec spec;
        final SuperCustomControlView view;
        ControlBinding(SuperCustomControlSpec spec, SuperCustomControlView view) {
            this.spec = spec;
            this.view = view;
        }
    }

    private static final class SeekControl {
        final SeekBar seek;
        final TextView value;
        final String suffix;
        SeekControl(SeekBar seek, TextView value, String suffix) {
            this.seek = seek;
            this.value = value;
            this.suffix = suffix;
        }
    }

    private static final class ColorRow {
        final LinearLayout row;
        final View dot;
        int color;
        ColorRow(LinearLayout row, View dot, int color) {
            this.row = row;
            this.dot = dot;
            this.color = color;
        }
    }

    private interface ColorCallback { void onColor(int color); }
    private interface TextCallback { void onText(String value); }
}
