package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Super-custom key display editor.
 * Supports live style editing, CPS templates, free dragging and selection guides.
 */
public final class SuperCustomDisplayActivity extends Activity {
    private static volatile SuperCustomDisplayActivity activeBindingActivity;
    private static final int CAPTURE_NONE = 0;
    private static final int CAPTURE_NEW_CONTROL = 1;
    private static final int CAPTURE_REBIND = 2;
    private static final int CAPTURE_MEDIA_PAUSE = 3;
    private static final int CAPTURE_MEDIA_PLAY = 4;
    private static final int SUPER_CONFIG_EXPORT_REQUEST = 7301;
    private static final int FLOATING_MEDIA_IMPORT_REQUEST = 7302;

    private final List<ControlBinding> controls = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaIoExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AxonFloatingMediaIO");
        thread.setDaemon(true);
        return thread;
    });

    private FrameLayout canvas;
    private TextView addButton;
    private TextView floatingMediaButton;
    private TextView textButton;
    private FloatingMediaPreviewController floatingMediaPreviewController;
    private String pendingMediaHotkeyId;
    private TextView capturePrompt;
    private SelectionGuideView selectionGuide;
    private LinearLayout selectionPanel;
    private LinearLayout idleActionPanel;
    private TextView selectedKeyValue;
    private TextView selectedXValue;
    private TextView selectedYValue;
    private Dialog controlEditorDialog;
    private int pendingExportSlot;

    private ControlBinding selectedBinding;
    private ControlBinding rebindTarget;
    private int captureMode = CAPTURE_NONE;
    private int editorMouseButtonsDown;
    private long editorMouseLastEventTime = -1L;
    private int editorMouseLastActionButton;
    private int editorMouseLastAction = -1;
    private int editorMouseLastUnifiedCode = -1;
    private boolean editorMouseLastUnifiedPressed;
    private long editorMouseLastUnifiedAt = -1L;
    private int editorGamepadKeyButtonsDown;
    private int editorGamepadMotionButtonsDown;

    private final Runnable rebindTimeout = () -> {
        if (captureMode != CAPTURE_REBIND) return;
        captureMode = CAPTURE_NONE;
        rebindTarget = null;
        hideCapturePrompt();
        Toast.makeText(this, R.string.super_custom_rebind_timeout, Toast.LENGTH_SHORT).show();
    };

    private final Runnable mediaHotkeyTimeout = () -> {
        if (captureMode != CAPTURE_MEDIA_PAUSE && captureMode != CAPTURE_MEDIA_PLAY) return;
        finishCapture();
        Toast.makeText(this, "快捷键录入已取消", Toast.LENGTH_SHORT).show();
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
        if (canvas != null) canvas.post(this::reapplyAllComponentPositions);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeBindingActivity = this;
        if (floatingMediaPreviewController != null) floatingMediaPreviewController.onHostResume();
        AxonInputAccessibilityService.refreshActiveService();
    }

    @Override
    protected void onPause() {
        if (activeBindingActivity == this) activeBindingActivity = null;
        // Never leave a visual key pressed or an invisible capture prompt after the editor loses focus.
        finishCapture();
        editorMouseButtonsDown = 0;
        editorGamepadKeyButtonsDown = 0;
        editorGamepadMotionButtonsDown = 0;
        for (ControlBinding binding : controls) binding.view.onBoundKeyEvent(false);
        persistActiveWorkspace();
        if (floatingMediaPreviewController != null) floatingMediaPreviewController.onHostPause();
        // If a loaded super-custom display is currently running, reflect the edited active workspace.
        AxonInputAccessibilityService.refreshActiveService();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (activeBindingActivity == this) activeBindingActivity = null;
        mainHandler.removeCallbacksAndMessages(null);
        persistActiveWorkspace();
        if (floatingMediaPreviewController != null) floatingMediaPreviewController.releaseAll();
        mediaIoExecutor.shutdownNow();
        super.onDestroy();
    }

    static boolean isBindingActivityActive() {
        SuperCustomDisplayActivity activity = activeBindingActivity;
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    static boolean isInputCaptureActive() {
        SuperCustomDisplayActivity activity = activeBindingActivity;
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.captureMode != CAPTURE_NONE;
    }

    static void notifyPhysicalMouseButtonForBinding(int button, boolean pressed, long eventTime) {
        SuperCustomDisplayActivity activity = activeBindingActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        int inputCode = InputBinding.mouse(button);
        activity.mainHandler.post(() -> {
            if (activeBindingActivity != activity || activity.isFinishing() || activity.isDestroyed()) return;
            activity.dispatchUnifiedMouseBinding(inputCode, pressed, eventTime);
        });
    }

    private void buildCanvas() {
        canvas = new FrameLayout(this);
        canvas.setBackgroundColor(UiPalette.background(this));
        canvas.setClipChildren(false);
        canvas.setClipToPadding(false);
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
        addButton.setElevation(0f);
        UiMotion.bindPressFeedback(addButton);
        addButton.setOnClickListener(v -> beginKeyCapture());
        FrameLayout.LayoutParams addLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        addLp.gravity = Gravity.TOP | Gravity.START;
        addLp.leftMargin = dp(16);
        addLp.topMargin = dp(82);
        canvas.addView(addButton, addLp);

        floatingMediaButton = new TextView(this);
        floatingMediaButton.setText("图片");
        floatingMediaButton.setContentDescription(getString(R.string.super_custom_add_floating_media));
        floatingMediaButton.setTextSize(11f);
        floatingMediaButton.setGravity(Gravity.CENTER);
        floatingMediaButton.setTextColor(UiPalette.textPrimary(this));
        floatingMediaButton.setIncludeFontPadding(false);
        floatingMediaButton.setClickable(true);
        floatingMediaButton.setFocusable(true);
        floatingMediaButton.setBackground(UiChrome.ripple(this, UiPalette.surface(this), 12f));
        UiMotion.bindPressFeedback(floatingMediaButton);
        floatingMediaButton.setOnClickListener(v -> openFloatingMediaPicker());
        floatingMediaButton.setOnLongClickListener(v -> {
            showFloatingMediaManager();
            return true;
        });
        FrameLayout.LayoutParams mediaButtonLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        mediaButtonLp.gravity = Gravity.TOP | Gravity.START;
        mediaButtonLp.leftMargin = dp(16);
        mediaButtonLp.topMargin = dp(138);
        canvas.addView(floatingMediaButton, mediaButtonLp);

        textButton = new TextView(this);
        textButton.setText(R.string.super_custom_add_text);
        textButton.setContentDescription(getString(R.string.super_custom_add_text));
        textButton.setTextSize(11f);
        textButton.setGravity(Gravity.CENTER);
        textButton.setTextColor(UiPalette.textPrimary(this));
        textButton.setIncludeFontPadding(false);
        textButton.setClickable(true);
        textButton.setFocusable(true);
        textButton.setBackground(UiChrome.ripple(this, UiPalette.surface(this), 12f));
        UiMotion.bindPressFeedback(textButton);
        textButton.setOnClickListener(v -> beginTextControlCreation());
        FrameLayout.LayoutParams textButtonLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        textButtonLp.gravity = Gravity.TOP | Gravity.START;
        textButtonLp.leftMargin = dp(16);
        textButtonLp.topMargin = dp(194);
        canvas.addView(textButton, textButtonLp);

        selectionGuide = new SelectionGuideView(this);
        selectionGuide.setVisibility(View.GONE);
        selectionGuide.setClickable(false);
        selectionGuide.setFocusable(false);
        canvas.addView(selectionGuide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildSelectionPanel();
        buildIdleActionPanel();

        capturePrompt = new TextView(this);
        capturePrompt.setText(R.string.super_custom_capture_prompt);
        capturePrompt.setTextSize(15f);
        capturePrompt.setTextColor(UiPalette.textPrimary(this));
        capturePrompt.setGravity(Gravity.CENTER);
        capturePrompt.setPadding(dp(20), dp(12), dp(20), dp(12));
        capturePrompt.setBackground(UiChrome.surface(this, UiPalette.surface(this), 14f));
        capturePrompt.setVisibility(View.GONE);
        FrameLayout.LayoutParams promptLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        promptLp.gravity = Gravity.CENTER;
        canvas.addView(capturePrompt, promptLp);
        floatingMediaPreviewController = new FloatingMediaPreviewController(
                this, canvas, this::showFloatingMediaSettings, this::bringEditorChromeToFront);
        restoreActiveWorkspace();
        syncFloatingMediaPreviews();
        installEditorSystemInsets();
    }

    private void installEditorSystemInsets() {
        if (canvas == null) return;
        canvas.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = Math.max(0, insets.getSystemWindowInsetTop());
            setEditorChromeTop(addButton, topInset + dp(82));
            setEditorChromeTop(floatingMediaButton, topInset + dp(138));
            setEditorChromeTop(textButton, topInset + dp(194));
            setEditorChromeTop(selectionPanel, topInset + dp(10));
            setEditorChromeTop(idleActionPanel, topInset + dp(10));
            return insets;
        });
        canvas.requestApplyInsets();
    }

    private void setEditorChromeTop(View view, int topPx) {
        if (view == null || !(view.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.topMargin == topPx) return;
        lp.topMargin = topPx;
        view.setLayoutParams(lp);
    }

    private void buildSelectionPanel() {
        selectionPanel = new LinearLayout(this);
        selectionPanel.setOrientation(LinearLayout.HORIZONTAL);
        selectionPanel.setGravity(Gravity.CENTER_VERTICAL);
        selectionPanel.setPadding(dp(16), dp(12), dp(12), dp(12));
        selectionPanel.setBackground(UiChrome.surface(this, UiPalette.surface(this), 14f));
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

        LinearLayout actionColumn = new LinearLayout(this);
        actionColumn.setOrientation(LinearLayout.VERTICAL);
        actionColumn.setGravity(Gravity.CENTER);

        Button edit = actionButton(R.string.super_custom_edit);
        edit.setOnClickListener(v -> editSelectedControl());
        Button delete = secondaryActionButton(R.string.super_custom_delete_key);
        delete.setOnClickListener(v -> deleteSelectedControl());

        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(dp(104), dp(44));
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(dp(104), dp(44));
        deleteLp.topMargin = dp(4);
        actionColumn.addView(edit, editLp);
        actionColumn.addView(delete, deleteLp);

        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.leftMargin = dp(10);
        selectionPanel.addView(actionColumn, actionsLp);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.TOP;
        panelLp.leftMargin = dp(12);
        panelLp.rightMargin = dp(12);
        panelLp.topMargin = dp(10);
        canvas.addView(selectionPanel, panelLp);
    }

    private void buildIdleActionPanel() {
        idleActionPanel = new LinearLayout(this);
        idleActionPanel.setOrientation(LinearLayout.HORIZONTAL);
        idleActionPanel.setGravity(Gravity.CENTER_VERTICAL);
        idleActionPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
        idleActionPanel.setBackground(UiChrome.surface(this, UiPalette.surface(this), 14f));
        idleActionPanel.setClickable(true);

        Button save = actionButton(R.string.super_custom_save_config);
        Button export = secondaryActionButton(R.string.super_custom_export_config);
        Button exit = secondaryActionButton(R.string.super_custom_exit);
        save.setOnClickListener(v -> showSaveConfigDialog());
        export.setOnClickListener(v -> showExportConfigDialog());
        exit.setOnClickListener(v -> {
            persistActiveWorkspace();
            finish();
        });

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p1.rightMargin = dp(6);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p2.leftMargin = dp(3);
        p2.rightMargin = dp(3);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p3.leftMargin = dp(6);
        idleActionPanel.addView(save, p1);
        idleActionPanel.addView(export, p2);
        idleActionPanel.addView(exit, p3);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.TOP;
        panelLp.leftMargin = dp(12);
        panelLp.rightMargin = dp(12);
        panelLp.topMargin = dp(10);
        canvas.addView(idleActionPanel, panelLp);
    }

    private TextView addSelectionInfoRow(LinearLayout root, String label, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(12f);
        title.setTextColor(UiPalette.textSecondary(this));
        title.setIncludeFontPadding(false);
        row.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        title.setGravity(Gravity.CENTER_VERTICAL);

        TextView value = new TextView(this);
        value.setTextSize(12f);
        value.setTextColor(UiPalette.accent(this));
        value.setIncludeFontPadding(false);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setPadding(dp(8), 0, dp(8), 0);
        value.setClickable(true);
        value.setFocusable(true);
        value.setOnClickListener(v -> action.run());
        row.addView(value, new LinearLayout.LayoutParams(0, dp(40), 1f));

        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        return value;
    }

    private void restoreActiveWorkspace() {
        List<SuperCustomControlSpec> saved = SuperCustomConfigStore.loadActive(this);
        if (saved.isEmpty()) return;
        for (SuperCustomControlSpec spec : saved) createControl(spec.copy());
        canvas.post(this::clearSelection);
    }

    private List<SuperCustomControlSpec> snapshotControls() {
        List<SuperCustomControlSpec> out = new ArrayList<>();
        for (ControlBinding binding : controls) out.add(binding.spec.copy());
        return out;
    }

    private void persistActiveWorkspace() {
        SuperCustomConfigStore.saveActive(this, snapshotControls());
    }

    private String[] configSlotLabels() {
        String[] labels = new String[SuperCustomConfigStore.SLOT_COUNT];
        for (int i = 0; i < labels.length; i++) {
            int slot = i + 1;
            boolean saved = SuperCustomConfigStore.hasSlot(this, slot);
            labels[i] = getString(R.string.super_custom_config_slot_status, slot,
                    getString(saved ? R.string.super_custom_config_saved : R.string.super_custom_config_empty));
        }
        return labels;
    }

    private void showSaveConfigDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.super_custom_save_config)
                .setItems(configSlotLabels(), (dialog, which) -> {
                    int slot = which + 1;
                    try {
                        SuperCustomConfigStore.saveSlot(this, slot, snapshotControls());
                        persistActiveWorkspace();
                        Toast.makeText(this, getString(R.string.super_custom_save_slot_success, slot), Toast.LENGTH_SHORT).show();
                    } catch (Throwable error) {
                        Toast.makeText(this, R.string.super_custom_config_save_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showExportConfigDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.super_custom_export_config)
                .setItems(configSlotLabels(), (dialog, which) -> {
                    int slot = which + 1;
                    if (!SuperCustomConfigStore.hasSlot(this, slot)) {
                        Toast.makeText(this, R.string.super_custom_config_slot_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pendingExportSlot = slot;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, "AxonInput_SuperCustom_Config" + slot + ".json");
                    startActivityForResult(intent, SUPER_CONFIG_EXPORT_REQUEST);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == FLOATING_MEDIA_IMPORT_REQUEST) {
            showFloatingMediaClipDialog(uri);
            return;
        }
        if (requestCode != SUPER_CONFIG_EXPORT_REQUEST) return;
        int slot = pendingExportSlot;
        pendingExportSlot = 0;
        if (slot < 1) return;
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Cannot open export target");
            out.write(SuperCustomConfigStore.exportSlot(this, slot).getBytes(StandardCharsets.UTF_8));
            out.flush();
            Toast.makeText(this, getString(R.string.super_custom_export_slot_success, slot), Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, R.string.super_custom_config_export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openFloatingMediaPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        try {
            startActivityForResult(intent, FLOATING_MEDIA_IMPORT_REQUEST);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.floating_video_picker_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showFloatingMediaClipDialog(Uri uri) {
        long durationMs;
        int videoWidth;
        int videoHeight;
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(this, uri);
            String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            durationMs = duration == null ? 0L : Long.parseLong(duration);
            String width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            videoWidth = width == null ? 16 : Integer.parseInt(width);
            videoHeight = height == null ? 9 : Integer.parseInt(height);
            String rotationValue = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation = rotationValue == null ? 0 : Integer.parseInt(rotationValue);
            rotation = ((rotation % 360) + 360) % 360;
            if (rotation == 90 || rotation == 270) {
                int swap = videoWidth;
                videoWidth = videoHeight;
                videoHeight = swap;
            }
        } catch (Throwable error) {
            Toast.makeText(this, R.string.floating_video_invalid, Toast.LENGTH_SHORT).show();
            try { metadata.release(); } catch (Throwable ignored) {}
            return;
        }
        try { metadata.release(); } catch (Throwable ignored) {}
        if (durationMs < 100L) {
            Toast.makeText(this, R.string.floating_video_invalid, Toast.LENGTH_SHORT).show();
            return;
        }

        final long safeDuration = Math.max(100L, durationMs);
        final int steps = Math.max(1, Math.min(6000, (int) Math.ceil(safeDuration / 100.0)));
        final long[] selectedStartMs = {0L};
        final long[] selectedEndMs = {safeDuration};
        final boolean[] adjusting = {false};

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(6), dp(18), dp(6));

        FloatingVideoOverlayView preview = new FloatingVideoOverlayView(this);
        preview.setVideoDisplaySize(Math.max(1, videoWidth), Math.max(1, videoHeight));
        preview.setVideoUri(uri, 0L, safeDuration, FloatingVideoOverlayView.PLAYBACK_LOOP);
        content.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));

        TextView clipLabel = mediaLabel(getString(R.string.floating_video_clip_format,
                0f, safeDuration / 1000f), true);
        content.addView(clipLabel, wrapParams(dp(8)));

        TextView startLabel = mediaLabel(getString(R.string.floating_video_clip_start_format, 0f), false);
        content.addView(startLabel, wrapParams(0));
        SeekBar startSeek = new LagSeekBar(this);
        startSeek.setMax(steps);
        startSeek.setProgress(0);
        tintSeekBar(startSeek);
        content.addView(startSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        TextView endLabel = mediaLabel(getString(R.string.floating_video_clip_end_format,
                safeDuration / 1000f), false);
        content.addView(endLabel, wrapParams(0));
        SeekBar endSeek = new LagSeekBar(this);
        endSeek.setMax(steps);
        endSeek.setProgress(steps);
        tintSeekBar(endSeek);
        content.addView(endSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        Runnable syncClip = () -> {
            long start = selectedStartMs[0];
            long end = selectedEndMs[0];
            preview.setClipRangeMs(start, end);
            clipLabel.setText(getString(R.string.floating_video_clip_format,
                    start / 1000f, end / 1000f));
            startLabel.setText(getString(R.string.floating_video_clip_start_format, start / 1000f));
            endLabel.setText(getString(R.string.floating_video_clip_end_format, end / 1000f));
        };

        startSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (adjusting[0]) return;
                long value = Math.round((progress / (double) steps) * safeDuration);
                if (value >= selectedEndMs[0] - 100L) {
                    value = Math.max(0L, selectedEndMs[0] - 100L);
                    int corrected = (int) Math.round((value / (double) safeDuration) * steps);
                    adjusting[0] = true;
                    startSeek.setProgress(Math.max(0, Math.min(steps, corrected)));
                    adjusting[0] = false;
                }
                selectedStartMs[0] = value;
                syncClip.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        endSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (adjusting[0]) return;
                long value = Math.round((progress / (double) steps) * safeDuration);
                if (value <= selectedStartMs[0] + 100L) {
                    value = Math.min(safeDuration, selectedStartMs[0] + 100L);
                    int corrected = (int) Math.round((value / (double) safeDuration) * steps);
                    adjusting[0] = true;
                    endSeek.setProgress(Math.max(0, Math.min(steps, corrected)));
                    adjusting[0] = false;
                }
                selectedEndMs[0] = value;
                syncClip.run();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        final int sourceWidth = Math.max(1, videoWidth);
        final int sourceHeight = Math.max(1, videoHeight);
        final String displayName = queryDisplayName(uri, "floating-video");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.floating_video_import_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("下一步", (d, which) -> showFloatingMediaChromaDialogForNew(
                        uri, displayName, safeDuration, selectedStartMs[0], selectedEndMs[0],
                        sourceWidth, sourceHeight))
                .create();
        dialog.setOnDismissListener(d -> preview.release());
        dialog.show();
    }

    private void showFloatingMediaChromaDialogForNew(Uri uri, String displayName, long sourceDuration,
                                                       long clipStart, long clipEnd,
                                                       int sourceWidth, int sourceHeight) {
        showFloatingMediaChromaDialog(null, uri, displayName, sourceDuration, clipStart, clipEnd,
                sourceWidth, sourceHeight);
    }

    private void showFloatingMediaChromaDialogForItem(FloatingMediaStore.Item item) {
        if (item == null) return;
        File file = FloatingMediaStore.fileFor(this, item.id);
        if (!file.isFile()) return;
        showFloatingMediaChromaDialog(item, null, item.name, item.sourceDurationMs,
                item.clipStartMs, item.clipEndMs, item.sourceWidth, item.sourceHeight);
    }

    private void showFloatingMediaChromaDialog(FloatingMediaStore.Item existing, Uri newUri,
                                                String displayName, long sourceDuration,
                                                long clipStart, long clipEnd,
                                                int sourceWidth, int sourceHeight) {
        final boolean[] enabled = {existing != null && existing.chromaEnabled};
        final int[] selectedColor = {existing == null ? 0xff00ff00 : existing.chromaColor};
        final int[] strength = {existing == null ? 36 : existing.chromaStrength};
        final boolean[] sampled = {existing != null && existing.chromaSampled};

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(6), dp(18), dp(8));

        FloatingVideoOverlayView preview = new FloatingVideoOverlayView(this);
        preview.setVideoDisplaySize(sourceWidth, sourceHeight);
        if (existing == null) {
            preview.setVideoUri(newUri, clipStart, clipEnd, FloatingVideoOverlayView.PLAYBACK_LOOP);
        } else {
            preview.setVideoFile(FloatingMediaStore.fileFor(this, existing.id), clipStart, clipEnd,
                    FloatingVideoOverlayView.PLAYBACK_LOOP);
        }
        preview.setChromaKey(enabled[0] && sampled[0], selectedColor[0], strength[0]);
        content.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        TextView hint = mediaLabel(getString(R.string.floating_media_chroma_hint), false);
        content.addView(hint, wrapParams(dp(7)));

        Switch enableSwitch = themedSwitch(R.string.floating_media_chroma_enable);
        enableSwitch.setChecked(enabled[0]);
        content.addView(enableSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView colorStatus = mediaLabel(sampled[0]
                ? getString(R.string.floating_media_chroma_color, selectedColor[0] & 0x00ffffff)
                : getString(R.string.floating_media_chroma_none), true);
        colorStatus.setGravity(Gravity.CENTER);
        colorStatus.setPadding(dp(8), dp(10), dp(8), dp(10));
        colorStatus.setBackground(UiPalette.rounded(this,
                sampled[0] ? selectedColor[0] : UiPalette.debugSurface(this), 10f));
        if (sampled[0]) {
            double luma = 0.299 * Color.red(selectedColor[0]) + 0.587 * Color.green(selectedColor[0])
                    + 0.114 * Color.blue(selectedColor[0]);
            colorStatus.setTextColor(luma > 150 ? Color.BLACK : Color.WHITE);
        }
        content.addView(colorStatus, wrapParams(dp(8)));

        TextView strengthLabel = mediaLabel(getString(R.string.floating_media_chroma_strength,
                strength[0]), false);
        content.addView(strengthLabel, wrapParams(0));
        SeekBar strengthSeek = new LagSeekBar(this);
        strengthSeek.setMax(FloatingMediaStore.CHROMA_STRENGTH_MAX);
        strengthSeek.setProgress(strength[0]);
        strengthSeek.setEnabled(enabled[0]);
        strengthSeek.setAlpha(enabled[0] ? 1f : UiChrome.DISABLED_ALPHA);
        tintSeekBar(strengthSeek);
        content.addView(strengthSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        preview.setColorPickListener(color -> {
            sampled[0] = true;
            selectedColor[0] = color;
            if (!enableSwitch.isChecked()) enableSwitch.setChecked(true);
            colorStatus.setText(getString(R.string.floating_media_chroma_color, color & 0x00ffffff));
            colorStatus.setBackground(UiPalette.rounded(this, color, 10f));
            double luma = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
            colorStatus.setTextColor(luma > 150 ? Color.BLACK : Color.WHITE);
            preview.setChromaKey(true, selectedColor[0], strength[0]);
        });
        enableSwitch.setOnCheckedChangeListener((button, checked) -> {
            enabled[0] = checked;
            strengthSeek.setEnabled(checked);
            strengthSeek.setAlpha(checked ? 1f : UiChrome.DISABLED_ALPHA);
            preview.setChromaKey(checked && sampled[0], selectedColor[0], strength[0]);
        });
        strengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                strength[0] = progress;
                strengthLabel.setText(getString(R.string.floating_media_chroma_strength, progress));
                preview.setChromaKey(enabled[0] && sampled[0], selectedColor[0], strength[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.floating_media_chroma_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.floating_media_apply, (d, which) -> {
                    final boolean finalSampled = sampled[0];
                    final boolean finalEnabled = enabled[0] && finalSampled;
                    final int finalColor = selectedColor[0];
                    final int finalStrength = strength[0];
                    if (existing == null) {
                        Context appContext = getApplicationContext();
                        Toast.makeText(this, R.string.floating_media_importing, Toast.LENGTH_SHORT).show();
                        mediaIoExecutor.execute(() -> {
                            try {
                                FloatingMediaStore.add(appContext, newUri, displayName, sourceDuration,
                                        clipStart, clipEnd, sourceWidth, sourceHeight,
                                        finalEnabled, finalSampled, finalColor, finalStrength);
                                mainHandler.post(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    syncFloatingMediaPreviews();
                                    Toast.makeText(this, R.string.floating_video_import_success, Toast.LENGTH_SHORT).show();
                                });
                            } catch (Throwable error) {
                                mainHandler.post(() -> {
                                    if (!isFinishing() && !isDestroyed()) {
                                        Toast.makeText(this, R.string.floating_video_import_failed, Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    } else {
                        FloatingMediaStore.Item updated = FloatingMediaStore.get(this, existing.id);
                        if (updated == null) return;
                        updated.chromaEnabled = finalEnabled;
                        updated.chromaSampled = finalSampled;
                        updated.chromaColor = finalColor;
                        updated.chromaStrength = finalStrength;
                        FloatingMediaStore.update(this, updated, true);
                        syncFloatingMediaPreviews();
                    }
                })
                .create();
        dialog.setOnDismissListener(d -> preview.release());
        dialog.show();
    }

    private void showFloatingMediaSettings(String mediaId) {
        FloatingMediaStore.Item item = FloatingMediaStore.get(this, mediaId);
        if (item == null) return;
        final FloatingMediaStore.Item working = item.copy();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(6), dp(18), dp(8));

        Switch visible = themedSwitch(R.string.floating_media_visible);
        visible.setChecked(working.enabled);
        content.addView(visible, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        SeekControl size = addSeekControl(content, R.string.floating_media_size_label,
                25, 500, working.sizePercent, "%");
        SeekControl opacity = addSeekControl(content, R.string.floating_media_opacity_label,
                0, 100, working.opacityPercent, "%");

        Switch sound = themedSwitch(R.string.floating_media_sound);
        sound.setChecked(working.soundEnabled);
        content.addView(sound, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Button chromaEdit = secondaryActionButton(R.string.floating_media_chroma_edit);
        content.addView(chromaEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        Spinner playback = choiceSpinner(new String[]{
                getString(R.string.floating_video_playback_loop),
                getString(R.string.floating_video_playback_once)
        });
        playback.setSelection(working.playbackMode == FloatingVideoOverlayView.PLAYBACK_ONCE ? 1 : 0, false);
        content.addView(labeledChoice(R.string.floating_video_playback_mode_label, playback), wrapParams(dp(6)));

        Button pauseHotkey = secondaryActionButton(R.string.floating_video_pause_hotkey_unbound);
        pauseHotkey.setText(working.pauseHotkey >= 0
                ? getString(R.string.floating_video_pause_hotkey_bound, InputBinding.label(working.pauseHotkey))
                : getString(R.string.floating_video_pause_hotkey_unbound));
        content.addView(pauseHotkey, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        Button playHotkey = secondaryActionButton(R.string.floating_video_play_hotkey_unbound);
        playHotkey.setText(working.playHotkey >= 0
                ? getString(R.string.floating_video_play_hotkey_bound, InputBinding.label(working.playHotkey))
                : getString(R.string.floating_video_play_hotkey_unbound));
        LinearLayout.LayoutParams playHotkeyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        playHotkeyLp.topMargin = dp(4);
        content.addView(playHotkey, playHotkeyLp);

        Button resetPosition = secondaryActionButton(R.string.floating_media_reset_position);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        resetLp.topMargin = dp(6);
        content.addView(resetPosition, resetLp);

        Button remove = secondaryActionButton(R.string.floating_media_remove);
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        removeLp.topMargin = dp(4);
        content.addView(remove, removeLp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(working.name == null || working.name.isEmpty()
                        ? getString(R.string.floating_media_settings_title) : working.name)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        visible.setOnCheckedChangeListener((button, checked) -> {
            if (working.enabled == checked) return;
            working.enabled = checked;
            FloatingMediaStore.update(this, working, false);
            floatingMediaPreviewController.rememberItem(working);
            syncFloatingMediaPreviews();
            AxonInputAccessibilityService.refreshActiveService();
        });

        size.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                size.value.setText(progress + size.suffix);
                if (!fromUser) return;
                working.sizePercent = progress;
                floatingMediaPreviewController.applyLayout(mediaId, working);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (FloatingMediaStore.update(SuperCustomDisplayActivity.this, working, true)) {
                    floatingMediaPreviewController.rememberItem(working);
                }
            }
        });

        opacity.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                opacity.value.setText(progress + opacity.suffix);
                if (!fromUser) return;
                working.opacityPercent = progress;
                FloatingVideoOverlayView preview = floatingMediaPreviewController.getPreview(mediaId);
                if (preview != null) preview.setVideoOpacity(progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (FloatingMediaStore.update(SuperCustomDisplayActivity.this, working, true)) {
                    floatingMediaPreviewController.rememberItem(working);
                }
            }
        });

        sound.setOnCheckedChangeListener((button, checked) -> {
            if (working.soundEnabled == checked) return;
            working.soundEnabled = checked;
            FloatingMediaStore.update(this, working, true);
            floatingMediaPreviewController.rememberItem(working);
            FloatingVideoOverlayView preview = floatingMediaPreviewController.getPreview(mediaId);
            if (preview != null) preview.setSoundEnabled(checked);
        });
        chromaEdit.setOnClickListener(v -> {
            dialog.dismiss();
            showFloatingMediaChromaDialogForItem(FloatingMediaStore.get(this, mediaId));
        });
        playback.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int mode = position == 1 ? FloatingVideoOverlayView.PLAYBACK_ONCE : FloatingVideoOverlayView.PLAYBACK_LOOP;
                if (working.playbackMode == mode) return;
                working.playbackMode = mode;
                FloatingMediaStore.update(SuperCustomDisplayActivity.this, working, true);
                floatingMediaPreviewController.rememberItem(working);
                FloatingVideoOverlayView preview = floatingMediaPreviewController.getPreview(mediaId);
                if (preview != null) preview.setPlaybackMode(mode);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        pauseHotkey.setOnClickListener(v -> {
            dialog.dismiss();
            beginFloatingMediaHotkeyCapture(mediaId, true);
        });
        pauseHotkey.setOnLongClickListener(v -> {
            working.pauseHotkey = -1;
            FloatingMediaStore.update(this, working, false);
            floatingMediaPreviewController.rememberItem(working);
            AxonInputAccessibilityService.refreshActiveService();
            pauseHotkey.setText(R.string.floating_video_pause_hotkey_unbound);
            return true;
        });
        playHotkey.setOnClickListener(v -> {
            dialog.dismiss();
            beginFloatingMediaHotkeyCapture(mediaId, false);
        });
        playHotkey.setOnLongClickListener(v -> {
            working.playHotkey = -1;
            FloatingMediaStore.update(this, working, false);
            floatingMediaPreviewController.rememberItem(working);
            AxonInputAccessibilityService.refreshActiveService();
            playHotkey.setText(R.string.floating_video_play_hotkey_unbound);
            return true;
        });
        resetPosition.setOnClickListener(v -> {
            working.xPercent = FloatingMediaStore.DEFAULT_X_PERCENT;
            working.yPercent = FloatingMediaStore.DEFAULT_Y_PERCENT;
            FloatingMediaStore.update(this, working, true);
            floatingMediaPreviewController.rememberItem(working);
            floatingMediaPreviewController.applyLayout(mediaId, working);
        });
        remove.setOnClickListener(v -> {
            FloatingMediaStore.remove(this, mediaId);
            floatingMediaPreviewController.release(mediaId);
            syncFloatingMediaPreviews();
            AxonInputAccessibilityService.refreshActiveService();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void showFloatingMediaManager() {
        List<FloatingMediaStore.Item> items = FloatingMediaStore.list(this);
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.floating_media_manager_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), dp(8), dp(18), dp(8));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < items.size(); i++) {
            FloatingMediaStore.Item item = items.get(i);
            Button button = secondaryActionButton(R.string.floating_media_settings_title);
            String name = item.name == null || item.name.trim().isEmpty()
                    ? getString(R.string.floating_video_default_name) : item.name.trim();
            button.setText((i + 1) + ". " + name
                    + (item.enabled ? "" : " · " + getString(R.string.floating_media_hidden)));
            final String mediaId = item.id;
            button.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                showFloatingMediaSettings(mediaId);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            if (i > 0) lp.topMargin = dp(5);
            list.addView(button, lp);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.floating_media_manager_title)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.show();
    }

    private TextView mediaLabel(String text, boolean primary) {
        TextView out = new TextView(this);
        out.setText(text);
        out.setTextSize(primary ? 13f : 11.5f);
        out.setTextColor(primary ? UiPalette.textPrimary(this) : UiPalette.textSecondary(this));
        out.setIncludeFontPadding(false);
        return out;
    }

    private String queryDisplayName(Uri uri, String fallback) {
        if (uri == null) return fallback;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    private void syncFloatingMediaPreviews() {
        if (floatingMediaPreviewController == null) return;
        int count = floatingMediaPreviewController.sync();
        if (floatingMediaButton != null) {
            floatingMediaButton.setText(count == 0 ? "图片" : "图片 · " + count);
        }
    }

    private void applyFloatingMediaPreviewLayouts() {
        if (floatingMediaPreviewController != null) floatingMediaPreviewController.applyAllLayouts();
    }

    private void beginFloatingMediaHotkeyCapture(String mediaId, boolean pause) {
        mainHandler.removeCallbacks(rebindTimeout);
        mainHandler.removeCallbacks(mediaHotkeyTimeout);
        pendingMediaHotkeyId = mediaId;
        captureMode = pause ? CAPTURE_MEDIA_PAUSE : CAPTURE_MEDIA_PLAY;
        rebindTarget = null;
        capturePrompt.setText(pause
                ? R.string.floating_video_pause_hotkey_recording
                : R.string.floating_video_play_hotkey_recording);
        showCapturePrompt();
        mainHandler.postDelayed(mediaHotkeyTimeout, 5000L);
    }

    private boolean floatingMediaHotkeyConflicts(String mediaId, int inputCode, boolean pause) {
        FloatingMediaStore.Item current = FloatingMediaStore.get(this, mediaId);
        if (current == null) return true;
        int opposite = pause ? current.playHotkey : current.pauseHotkey;
        if (inputCode == opposite) return true;
        if (FloatingMediaStore.hotkeyConflicts(this, mediaId, inputCode)) return true;
        if (OverlayState.isForceHoldEnabled(this)
                && inputCode == OverlayState.getForceHoldTriggerKeyCode(this)) return true;
        return inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)
                || inputCode == OverlayState.getHideDisplayHotkeyInputCode(this);
    }

    private void beginTextControlCreation() {
        boolean dark = OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK;
        SuperCustomControlSpec spec = SuperCustomControlSpec.createText(dark);
        fitTextBounds(spec);
        showControlEditor(null, spec);
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
        if (selectedBinding.spec.isTextElement()) {
            showTextEditor(getString(R.string.super_custom_edit_text_title),
                    selectedBinding.spec.labelText, value -> {
                        String normalized = value == null ? "" : value.trim();
                        if (normalized.isEmpty()) {
                            Toast.makeText(this, R.string.super_custom_text_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        selectedBinding.spec.labelText = normalized;
                        fitTextBounds(selectedBinding.spec);
                        selectedBinding.view.applySpec(selectedBinding.spec);
                        applyControlPosition(selectedBinding);
                        updateSelectionPanel();
                        updateSelectionGuides();
                        persistActiveWorkspace();
                        AxonInputAccessibilityService.refreshActiveService();
                    });
            return;
        }
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
        mainHandler.removeCallbacks(mediaHotkeyTimeout);
        captureMode = CAPTURE_NONE;
        rebindTarget = null;
        pendingMediaHotkeyId = null;
        hideCapturePrompt();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return false;
        boolean keyboard = InputBinding.isPhysicalKeyboardEvent(event);
        boolean gamepad = InputBinding.isPhysicalGamepadEvent(event);
        if (keyboard) {
            int inputCode = InputBinding.keyboard(event.getKeyCode());
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            boolean up = event.getAction() == KeyEvent.ACTION_UP;
            if (down || up) handleEditorBindingEvent(inputCode, down, down && event.getRepeatCount() == 0);
        } else if (gamepad) {
            updateEditorGamepadKeyEvent(event);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        handleEditorMouseEvent(event);
        updateEditorGamepadMotionEvent(event);
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        handleEditorMouseEvent(event);
        return super.dispatchTouchEvent(event);
    }

    private int editorGamepadButtonsDown() {
        return editorGamepadKeyButtonsDown | editorGamepadMotionButtonsDown;
    }

    private void updateEditorGamepadKeyEvent(KeyEvent event) {
        int inputCode = InputBinding.fromGamepadEvent(event);
        if (inputCode < 0) return;
        int bit = InputBinding.payload(inputCode);
        int before = editorGamepadButtonsDown();
        if (event.getAction() == KeyEvent.ACTION_DOWN) editorGamepadKeyButtonsDown |= bit;
        else if (event.getAction() == KeyEvent.ACTION_UP) editorGamepadKeyButtonsDown &= ~bit;
        dispatchEditorGamepadTransitions(before, editorGamepadButtonsDown());
    }

    private void updateEditorGamepadMotionEvent(MotionEvent event) {
        if (!InputBinding.isPhysicalGamepadMotionEvent(event)) return;
        int before = editorGamepadButtonsDown();
        editorGamepadMotionButtonsDown = InputBinding.gamepadButtonsFromMotionEvent(event);
        dispatchEditorGamepadTransitions(before, editorGamepadButtonsDown());
    }

    private void dispatchEditorGamepadTransitions(int before, int after) {
        int changed = before ^ after;
        while (changed != 0) {
            int bit = Integer.lowestOneBit(changed);
            changed &= ~bit;
            boolean pressed = (after & bit) != 0;
            handleEditorBindingEvent(InputBinding.gamepad(bit), pressed, pressed);
        }
    }

    private void handleEditorMouseEvent(MotionEvent event) {
        if (!InputBinding.isPhysicalMouseEvent(event)) return;

        int currentButtons = event.getButtonState();
        int changed = currentButtons ^ editorMouseButtonsDown;
        editorMouseButtonsDown = currentButtons;

        if (changed != 0) {
            dispatchEditorMouseButtonMask(changed, currentButtons, event.getEventTime());
            return;
        }

        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_BUTTON_PRESS && action != MotionEvent.ACTION_BUTTON_RELEASE) return;
        int actionButton = event.getActionButton();
        if (actionButton == 0) return;
        if (event.getEventTime() == editorMouseLastEventTime
                && actionButton == editorMouseLastActionButton
                && action == editorMouseLastAction) return;

        // Fallback for drivers that do not update buttonState on ACTION_BUTTON_* events.
        boolean pressed = action == MotionEvent.ACTION_BUTTON_PRESS;
        int inputCode = InputBinding.mouseFromAndroidButton(actionButton);
        if (inputCode < 0) return;
        editorMouseLastEventTime = event.getEventTime();
        editorMouseLastActionButton = actionButton;
        editorMouseLastAction = action;
        dispatchUnifiedMouseBinding(inputCode, pressed, event.getEventTime());
    }

    private void dispatchEditorMouseButtonMask(int changed, int currentButtons, long eventTime) {
        final int[] buttons = {
                MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY,
                MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD
        };
        for (int button : buttons) {
            if ((changed & button) == 0) continue;
            int inputCode = InputBinding.mouseFromAndroidButton(button);
            if (inputCode < 0) continue;
            boolean pressed = (currentButtons & button) != 0;
            editorMouseLastEventTime = eventTime;
            editorMouseLastActionButton = button;
            editorMouseLastAction = pressed ? MotionEvent.ACTION_BUTTON_PRESS : MotionEvent.ACTION_BUTTON_RELEASE;
            dispatchUnifiedMouseBinding(inputCode, pressed, eventTime);
        }
    }

    private void dispatchUnifiedMouseBinding(int inputCode, boolean pressed, long eventTime) {
        if (inputCode < 0) return;
        long when = eventTime > 0L ? eventTime : SystemClock.uptimeMillis();
        if (inputCode == editorMouseLastUnifiedCode
                && pressed == editorMouseLastUnifiedPressed
                && editorMouseLastUnifiedAt >= 0L
                && Math.abs(when - editorMouseLastUnifiedAt) <= 80L) return;
        editorMouseLastUnifiedCode = inputCode;
        editorMouseLastUnifiedPressed = pressed;
        editorMouseLastUnifiedAt = when;
        handleEditorBindingEvent(inputCode, pressed, pressed);
    }

    private void handleEditorBindingEvent(int inputCode, boolean pressed, boolean firstPress) {
        if (inputCode < 0) return;
        if (captureMode != CAPTURE_NONE && firstPress) {
            if (captureMode == CAPTURE_REBIND && rebindTarget != null) {
                ControlBinding target = rebindTarget;
                target.spec.keyCode = inputCode;
                finishCapture();
                if (selectedBinding == target) updateSelectionPanel();
            } else if (captureMode == CAPTURE_NEW_CONTROL) {
                finishCapture();
                showControlEditor(inputCode);
            } else if (captureMode == CAPTURE_MEDIA_PAUSE || captureMode == CAPTURE_MEDIA_PLAY) {
                boolean pause = captureMode == CAPTURE_MEDIA_PAUSE;
                String mediaId = pendingMediaHotkeyId;
                if (mediaId == null || floatingMediaHotkeyConflicts(mediaId, inputCode, pause)) {
                    Toast.makeText(this, R.string.floating_video_hotkey_conflict, Toast.LENGTH_SHORT).show();
                } else {
                    FloatingMediaStore.Item item = FloatingMediaStore.get(this, mediaId);
                    if (item != null) {
                        if (pause) item.pauseHotkey = inputCode;
                        else item.playHotkey = inputCode;
                        FloatingMediaStore.update(this, item, false);
                        floatingMediaPreviewController.rememberItem(item);
                        AxonInputAccessibilityService.refreshActiveService();
                    }
                    finishCapture();
                    Toast.makeText(this, pause ? "暂停快捷键已绑定" : "播放快捷键已绑定",
                            Toast.LENGTH_SHORT).show();
                }
            }
        }

        for (ControlBinding binding : controls) {
            if (binding.spec.isKeyElement() && binding.spec.keyCode == inputCode) {
                if (!pressed || firstPress) binding.view.onBoundKeyEvent(pressed);
            }
        }
        // 录入与触发只旁路监听，不消费原始键盘/鼠标/手柄输入。
    }

    private void showControlEditor(int keyCode) {
        boolean dark = OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK;
        SuperCustomControlSpec spec = new SuperCustomControlSpec(
                keyCode, InputBinding.label(keyCode), dark);
        showControlEditor(null, spec);
    }

    private void editSelectedControl() {
        if (selectedBinding == null) return;
        showControlEditor(selectedBinding, selectedBinding.spec.copy());
    }

    private void deleteSelectedControl() {
        ControlBinding target = selectedBinding;
        if (target == null) return;

        // Cancel a pending rebind before removing its target so no later input can write into a
        // detached control. The deletion is immediate by design, matching the editor's direct model.
        if (rebindTarget == target || captureMode == CAPTURE_REBIND) finishCapture();
        selectedBinding = null;
        controls.remove(target);
        if (canvas != null && target.view.getParent() == canvas) canvas.removeView(target.view);
        clearSelection();
        persistActiveWorkspace();
        // If the dedicated display switch is already on, update the live overlay in place.
        // This refresh never enables the display by itself.
        AxonInputAccessibilityService.refreshActiveService();
    }


    private void showControlEditor(ControlBinding editing, SuperCustomControlSpec spec) {
        if (spec != null && spec.isTextElement()) {
            showTextControlEditor(editing, spec);
            return;
        }
        Dialog dialog = new Dialog(this);
        controlEditorDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(14), dp(14), dp(14), dp(12));
        shell.setBackground(UiChrome.surface(this, UiPalette.surface(this), 18f));

        boolean compactEditor = getResources().getDisplayMetrics().widthPixels < dp(600)
                || getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(compactEditor ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.TOP);
        shell.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(true);
        leftScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(6), dp(4), compactEditor ? dp(6) : dp(12), dp(10));
        leftScroll.addView(left, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (compactEditor) {
            body.addView(leftScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f));
        } else {
            body.addView(leftScroll, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 0.46f));
        }

        View divider = new View(this);
        divider.setBackgroundColor(UiPalette.divider(this));
        if (compactEditor) {
            LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dividerLp.topMargin = dp(6);
            dividerLp.bottomMargin = dp(8);
            body.addView(divider, dividerLp);
        } else {
            body.addView(divider, new LinearLayout.LayoutParams(dp(1),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(compactEditor ? dp(6) : dp(14), dp(4), dp(4), dp(8));
        if (compactEditor) {
            body.addView(right, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f));
        } else {
            body.addView(right, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 0.54f));
        }

        TextView generalLabel = smallSectionLabel(R.string.super_custom_general);
        generalLabel.setGravity(compactEditor ? Gravity.START : Gravity.END);
        left.addView(generalLabel, wrapParams(dp(4)));

        Spinner motionSpinner = choiceSpinner(new String[]{
                getString(R.string.motion_size),
                getString(R.string.motion_alpha),
                getString(R.string.motion_ripple),
                getString(R.string.motion_none)});
        left.addView(labeledChoice(R.string.super_custom_press_motion, motionSpinner), wrapParams(dp(5)));

        SeekControl corner = addSeekControl(left, R.string.super_custom_corner, 0, 80, spec.cornerDp, " dp");
        SeekControl opacity = addSeekControl(left, R.string.super_custom_opacity, 0, 100,
                spec.opacityPercent, "%");
        SeekControl diffusionOpacity = addSeekControl(left, R.string.super_custom_diffusion_opacity, 0, 100,
                spec.diffusionOpacityPercent, "%");
        SeekControl width = addSeekControl(left, R.string.super_custom_width, 28, 420,
                spec.widthDp, " dp");
        SeekControl height = addSeekControl(left, R.string.super_custom_length, 24, 300,
                spec.heightDp, " dp");

        ColorRow pressColor = addColorRow(left, R.string.super_custom_press_color, spec.pressColor);
        int resolvedBorderColor = spec.borderColor != 0 ? spec.borderColor : UiPalette.overlayStroke(this);
        ColorRow borderColor = addColorRow(left, R.string.super_custom_border_color, resolvedBorderColor);
        ColorRow textColor = addColorRow(left, R.string.super_custom_text_color, spec.textColor);
        SeekControl textSize = addSeekControl(left, R.string.super_custom_text_size, 8, 96,
                spec.textSizeSp, " sp");

        TextView addonsLabel = smallSectionLabel(R.string.super_custom_addons);
        addonsLabel.setGravity(Gravity.START);
        left.addView(addonsLabel, wrapParams(dp(4)));

        Switch cpsSwitch = themedSwitch(R.string.super_custom_cps_switch);
        cpsSwitch.setTextSize(13f);
        left.addView(cpsSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), dp(10), dp(12), dp(10));
        info.setBackground(UiChrome.nestedSurface(this));
        right.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView keyInfo = infoText();
        TextView sizeInfo = infoText();
        info.addView(keyInfo, wrapParams(dp(3)));
        info.addView(sizeInfo, wrapParams(0));

        FrameLayout previewArea = new FrameLayout(this);
        previewArea.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewArea.setBackground(UiChrome.nestedSurface(this));
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
            spec.diffusionOpacityPercent = diffusionOpacity.seek.getProgress();
            spec.widthDp = width.seek.getProgress();
            spec.heightDp = height.seek.getProgress();
            spec.textSizeSp = textSize.seek.getProgress();
            spec.pressColor = pressColor.color;
            spec.borderColor = borderColor.color;
            spec.textColor = textColor.color;
            spec.cpsEnabled = cpsSwitch.isChecked();
            preview.applySpec(spec);
            keyInfo.setText(getString(R.string.super_custom_info_key, InputBinding.label(spec.keyCode)));
            sizeInfo.setText(getString(R.string.super_custom_info_size, spec.widthDp, spec.heightDp));
        };

        int motionSelection = spec.motionMode == OverlayState.MOTION_ALPHA ? 1
                : spec.motionMode == OverlayState.MOTION_RIPPLE ? 2
                : spec.motionMode == OverlayState.MOTION_NONE ? 3 : 0;
        motionSpinner.setSelection(motionSelection, false);
        setSeekControlEnabled(diffusionOpacity, spec.motionMode == OverlayState.MOTION_RIPPLE);
        cpsSwitch.setChecked(spec.cpsEnabled);
        motionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                spec.motionMode = position == 1 ? OverlayState.MOTION_ALPHA
                        : position == 2 ? OverlayState.MOTION_RIPPLE
                        : position == 3 ? OverlayState.MOTION_NONE
                        : OverlayState.MOTION_SIZE;
                setSeekControlEnabled(diffusionOpacity, spec.motionMode == OverlayState.MOTION_RIPPLE);
                preview.applySpec(spec);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        bindSeekRefresh(corner, refreshPreview);
        bindSeekRefresh(opacity, refreshPreview);
        bindSeekRefresh(diffusionOpacity, refreshPreview);
        bindSeekRefresh(width, refreshPreview);
        bindSeekRefresh(height, refreshPreview);
        bindSeekRefresh(textSize, refreshPreview);
        cpsSwitch.setOnCheckedChangeListener((button, checked) -> {
            spec.cpsEnabled = checked;
            refreshPreview.run();
        });

        pressColor.dot.setBackground(sequenceDrawable(spec.pressColors, spec.pressColor));
        pressColor.row.setOnClickListener(v -> showColorEditor(spec.pressColor, spec.pressColors, colors -> {
            spec.pressColor = colors[0];
            spec.pressColors = ColorSequence.encode(colors, spec.pressColor);
            pressColor.color = colors[0];
            pressColor.dot.setBackground(sequenceDrawable(spec.pressColors, spec.pressColor));
            refreshPreview.run();
        }));
        borderColor.dot.setBackground(sequenceDrawable(spec.borderColors,
                spec.borderColor != 0 ? spec.borderColor : UiPalette.overlayStroke(this)));
        borderColor.row.setOnClickListener(v -> showColorEditor(
                spec.borderColor != 0 ? spec.borderColor : UiPalette.overlayStroke(this), spec.borderColors, colors -> {
            spec.borderColor = colors[0];
            spec.borderColors = ColorSequence.encode(colors, spec.borderColor);
            borderColor.color = colors[0];
            borderColor.dot.setBackground(sequenceDrawable(spec.borderColors, spec.borderColor));
            refreshPreview.run();
        }));
        textColor.dot.setBackground(sequenceDrawable(spec.textColors, spec.textColor));
        textColor.row.setOnClickListener(v -> showColorEditor(spec.textColor, spec.textColors, colors -> {
            spec.textColor = colors[0];
            spec.textColors = ColorSequence.encode(colors, spec.textColor);
            textColor.color = colors[0];
            textColor.dot.setBackground(sequenceDrawable(spec.textColors, spec.textColor));
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
            persistActiveWorkspace();
            AxonInputAccessibilityService.refreshActiveService();
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

    private void showTextControlEditor(ControlBinding editing, SuperCustomControlSpec spec) {
        fitTextBounds(spec);
        Dialog dialog = new Dialog(this);
        controlEditorDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(14), dp(14), dp(14), dp(12));
        shell.setBackground(UiChrome.surface(this, UiPalette.surface(this), 18f));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(4), dp(6), dp(10));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView section = smallSectionLabel(R.string.super_custom_text_component);
        section.setGravity(Gravity.START);
        root.addView(section, wrapParams(dp(8)));

        LinearLayout contentRow = new LinearLayout(this);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        contentRow.setGravity(Gravity.CENTER_VERTICAL);
        contentRow.setPadding(dp(2), dp(4), dp(2), dp(4));
        contentRow.setClickable(true);
        contentRow.setFocusable(true);
        UiMotion.bindPressFeedback(contentRow);
        TextView contentTitle = new TextView(this);
        contentTitle.setText(R.string.super_custom_text_content);
        contentTitle.setTextColor(UiPalette.textSecondary(this));
        contentTitle.setTextSize(12f);
        contentTitle.setGravity(Gravity.CENTER_VERTICAL);
        TextView contentValue = new TextView(this);
        contentValue.setTextColor(UiPalette.accent(this));
        contentValue.setTextSize(12f);
        contentValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        contentValue.setSingleLine(true);
        contentValue.setMaxEms(14);
        contentRow.addView(contentTitle, new LinearLayout.LayoutParams(0, dp(44), 1f));
        contentRow.addView(contentValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(contentRow, wrapParams(dp(2)));

        SeekControl opacity = addSeekControl(root, R.string.super_custom_opacity, 0, 100,
                spec.opacityPercent, "%");
        SeekControl textSize = addSeekControl(root, R.string.super_custom_text_size, 8, 96,
                spec.textSizeSp, " sp");
        ColorRow textColor = addColorRow(root, R.string.super_custom_text_color, spec.textColor);

        Switch strokeSwitch = themedSwitch(R.string.super_custom_text_stroke);
        strokeSwitch.setTextSize(13f);
        root.addView(strokeSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        SeekControl strokeWidth = addSeekControl(root, R.string.super_custom_text_stroke_width,
                1, 24, spec.textStrokeWidthDp, " dp");
        ColorRow strokeColor = addColorRow(root, R.string.super_custom_text_stroke_color,
                spec.textStrokeColor);
        strokeColor.dot.setBackground(sequenceDrawable(spec.textStrokeColors, spec.textStrokeColor));

        TextView previewLabel = smallSectionLabel(R.string.super_custom_text_preview);
        previewLabel.setGravity(Gravity.START);
        root.addView(previewLabel, wrapParams(dp(6)));

        FrameLayout previewArea = new FrameLayout(this);
        previewArea.setPadding(dp(12), dp(12), dp(12), dp(12));
        previewArea.setBackground(UiChrome.nestedSurface(this));
        root.addView(previewArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132)));

        SuperCustomControlView preview = new SuperCustomControlView(this);
        preview.setInteractivePreview(false);
        FrameLayout.LayoutParams previewLp = new FrameLayout.LayoutParams(
                dp(spec.widthDp), dp(spec.heightDp));
        previewLp.gravity = Gravity.CENTER;
        previewArea.addView(preview, previewLp);

        Runnable refresh = () -> {
            spec.opacityPercent = opacity.seek.getProgress();
            spec.textSizeSp = textSize.seek.getProgress();
            spec.textColor = textColor.color;
            spec.textStrokeEnabled = strokeSwitch.isChecked();
            spec.textStrokeWidthDp = strokeWidth.seek.getProgress();
            spec.textStrokeColor = strokeColor.color;
            fitTextBounds(spec);
            contentValue.setText(spec.labelText == null ? "" : spec.labelText);
            setSeekControlEnabled(strokeWidth, spec.textStrokeEnabled);
            strokeColor.row.setEnabled(spec.textStrokeEnabled);
            strokeColor.row.setAlpha(spec.textStrokeEnabled ? 1f : UiChrome.DISABLED_ALPHA);
            preview.applySpec(spec);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) preview.getLayoutParams();
            p.width = dp(spec.widthDp);
            p.height = dp(spec.heightDp);
            p.gravity = Gravity.CENTER;
            preview.setLayoutParams(p);
        };

        strokeSwitch.setChecked(spec.textStrokeEnabled);
        bindSeekRefresh(opacity, refresh);
        bindSeekRefresh(textSize, refresh);
        bindSeekRefresh(strokeWidth, refresh);
        strokeSwitch.setOnCheckedChangeListener((button, checked) -> {
            spec.textStrokeEnabled = checked;
            refresh.run();
        });
        contentRow.setOnClickListener(v -> showTextEditor(
                getString(R.string.super_custom_edit_text_title), spec.labelText, value -> {
                    spec.labelText = value;
                    refresh.run();
                }));
        textColor.dot.setBackground(sequenceDrawable(spec.textColors, spec.textColor));
        textColor.row.setOnClickListener(v -> showColorEditor(spec.textColor, spec.textColors, colors -> {
            spec.textColor = colors[0];
            spec.textColors = ColorSequence.encode(colors, spec.textColor);
            textColor.color = colors[0];
            textColor.dot.setBackground(sequenceDrawable(spec.textColors, spec.textColor));
            refresh.run();
        }));
        strokeColor.row.setOnClickListener(v -> {
            if (!spec.textStrokeEnabled) return;
            showColorEditor(spec.textStrokeColor, spec.textStrokeColors, colors -> {
                spec.textStrokeColor = colors[0];
                spec.textStrokeColors = ColorSequence.encode(colors, spec.textStrokeColor);
                strokeColor.color = colors[0];
                strokeColor.dot.setBackground(sequenceDrawable(spec.textStrokeColors, spec.textStrokeColor));
                refresh.run();
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
            refresh.run();
            String value = spec.labelText == null ? "" : spec.labelText.trim();
            if (value.isEmpty()) {
                Toast.makeText(this, R.string.super_custom_text_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            spec.labelText = value;
            fitTextBounds(spec);
            if (editing == null) createControl(spec.copy());
            else applyEditedControl(editing, spec.copy());
            persistActiveWorkspace();
            AxonInputAccessibilityService.refreshActiveService();
            dialog.dismiss();
        });

        refresh.run();
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

    private void fitTextBounds(SuperCustomControlSpec spec) {
        if (spec == null || !spec.isTextElement()) return;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(FontManager.bold(this));
        paint.setTextSize(spec.textSizeSp * getResources().getDisplayMetrics().scaledDensity);
        String value = spec.labelText == null || spec.labelText.isEmpty() ? "文本" : spec.labelText;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float outline = spec.textStrokeEnabled ? dp(spec.textStrokeWidthDp) : 0f;
        float widthPx = paint.measureText(value) + dp(14) + outline * 2f;
        float heightPx = (fm.bottom - fm.top) + dp(10) + outline * 2f;
        float density = Math.max(0.1f, getResources().getDisplayMetrics().density);
        spec.widthDp = clamp(Math.round(widthPx / density), 40, 560);
        spec.heightDp = clamp(Math.round(heightPx / density), 28, 180);
    }

    private void createControl(SuperCustomControlSpec spec) {
        if (spec != null && spec.isTextElement()) fitTextBounds(spec);
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
            if (!binding.spec.positionSet) {
                binding.spec.centerXPx = canvas.getWidth() / 2;
                binding.spec.centerYPx = canvas.getHeight() / 2;
                binding.spec.positionSet = true;
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
        edited.positionSet = binding.spec.positionSet;
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
                            binding.spec.positionSet = true;
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
        if (idleActionPanel != null) idleActionPanel.setVisibility(View.GONE);
        if (addButton != null) addButton.setVisibility(View.GONE);
        if (floatingMediaButton != null) floatingMediaButton.setVisibility(View.GONE);
        if (textButton != null) textButton.setVisibility(View.GONE);
        if (selectionGuide != null) selectionGuide.setVisibility(View.VISIBLE);
        updateSelectionPanel();
        updateSelectionGuides();
        bringEditorChromeToFront();
    }

    private void clearSelection() {
        selectedBinding = null;
        if (selectionPanel != null) selectionPanel.setVisibility(View.GONE);
        if (idleActionPanel != null) idleActionPanel.setVisibility(View.VISIBLE);
        if (selectionGuide != null) {
            selectionGuide.setVisibility(View.GONE);
            selectionGuide.invalidate();
        }
        if (addButton != null) addButton.setVisibility(View.VISIBLE);
        if (floatingMediaButton != null) floatingMediaButton.setVisibility(View.VISIBLE);
        if (textButton != null) textButton.setVisibility(View.VISIBLE);
        finishCapture();
    }

    private void bringEditorChromeToFront() {
        if (selectionGuide != null) selectionGuide.bringToFront();
        if (selectionPanel != null && selectionPanel.getVisibility() == View.VISIBLE) selectionPanel.bringToFront();
        if (idleActionPanel != null && idleActionPanel.getVisibility() == View.VISIBLE) idleActionPanel.bringToFront();
        if (addButton != null && addButton.getVisibility() == View.VISIBLE) addButton.bringToFront();
        if (floatingMediaButton != null && floatingMediaButton.getVisibility() == View.VISIBLE) floatingMediaButton.bringToFront();
        if (textButton != null && textButton.getVisibility() == View.VISIBLE) textButton.bringToFront();
        if (capturePrompt != null && capturePrompt.getVisibility() == View.VISIBLE) capturePrompt.bringToFront();
    }

    private void updateSelectionPanel() {
        if (selectedBinding == null) return;
        if (selectedBinding.spec.isTextElement()) {
            String text = selectedBinding.spec.labelText == null ? "" : selectedBinding.spec.labelText;
            selectedKeyValue.setText(getString(R.string.super_custom_selected_text_value, text));
        } else {
            selectedKeyValue.setText(InputBinding.label(selectedBinding.spec.keyCode));
        }
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
        UiChrome.styleInput(this, input);
        input.setText(String.valueOf(initial));
        input.setSelection(input.length());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
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
                        selectedBinding.spec.positionSet = true;
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

        // v1.8: super-custom components deliberately allow negative / beyond-display centers.
        // Do not clamp here: partially off-screen placement is a valid composition choice.
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) binding.view.getLayoutParams();
        p.width = width;
        p.height = height;
        p.gravity = Gravity.TOP | Gravity.START;
        p.leftMargin = binding.spec.centerXPx - halfW;
        p.topMargin = binding.spec.centerYPx - halfH;
        binding.view.setLayoutParams(p);
    }

    private void reapplyAllComponentPositions() {
        if (canvas == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        for (ControlBinding binding : controls) applyControlPosition(binding);
        applyFloatingMediaPreviewLayouts();
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
        block.setPadding(0, dp(4), 0, dp(4));

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

        SeekBar seek = new LagSeekBar(this);
        seek.setMin(min);
        seek.setMax(max);
        seek.setProgress(initial);
        seek.setMinimumHeight(dp(36));
        tintSeekBar(seek);
        block.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        value.setText(initial + suffix);
        root.addView(block, wrapParams(dp(1)));
        return new SeekControl(block, title, seek, value, suffix);
    }

    private void setSeekControlEnabled(SeekControl control, boolean enabled) {
        control.seek.setEnabled(enabled);
        control.block.setAlpha(enabled ? 1f : UiChrome.DISABLED_ALPHA);
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
        row.setPadding(dp(2), dp(5), dp(2), dp(5));
        row.setClickable(true);
        row.setFocusable(true);
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(UiPalette.textSecondary(this));
        title.setTextSize(12f);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        title.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(circleDrawable(color));
        row.addView(dot, new LinearLayout.LayoutParams(dp(22), dp(22)));
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
        block.addView(label, wrapParams(dp(5)));
        block.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return block;
    }

    private Spinner choiceSpinner(String[] items) {
        Spinner spinner = new AnimatedChoiceSpinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return spinnerText(getItem(position), false);
            }

            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return spinnerText(getItem(position), true);
            }
        };
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(44));
        spinner.setPadding(0, 0, 0, 0);
        spinner.setBackground(UiChrome.controlRipple(this));
        spinner.setPopupBackgroundDrawable(UiChrome.popupSurface(this));
        spinner.setDropDownHorizontalOffset(0);
        spinner.setDropDownVerticalOffset(0);
        spinner.post(() -> {
            if (spinner.getWidth() > 0) spinner.setDropDownWidth(spinner.getWidth());
        });
        UiMotion.bindPressFeedback(spinner);
        return spinner;
    }

    private TextView spinnerText(String value, boolean dropdown) {
        TextView text = new TextView(this);
        text.setText(value == null ? "" : value);
        text.setTextColor(UiPalette.textPrimary(this));
        text.setTextSize(12.5f);
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        text.setSingleLine(true);
        text.setPadding(dp(14), 0, dp(14), 0);
        text.setMinHeight(dp(dropdown ? 48 : 44));
        if (!dropdown) {
            text.setCompoundDrawables(null, null, UiChrome.chevron(this), null);
            text.setCompoundDrawablePadding(dp(8));
        }
        return text;
    }

    private Switch themedSwitch(int textRes) {
        Switch sw = new MotionSwitch(this);
        sw.setText(textRes);
        sw.setTextColor(UiPalette.textPrimary(this));
        sw.setTextSize(14f);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setSingleLine(false);
        UiChrome.styleSwitch(this, sw);
        return sw;
    }

    private Button actionButton(int textRes) {
        Button button = new Button(this);
        button.setText(textRes);
        UiChrome.stylePrimaryButton(this, button);
        return button;
    }

    private Button secondaryActionButton(int textRes) {
        Button button = new Button(this);
        button.setText(textRes);
        UiChrome.styleSecondaryButton(this, button);
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
        UiChrome.styleInput(this, input);
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

    private void showColorEditor(int initial, String encoded, MultiColorCallback callback) {
        int[] initialColors = ColorSequence.decode(encoded, initial);
        MultiColorPickerDialog.show(this, getString(R.string.super_custom_color_title), initialColors,
                callback::onColors);
    }

    private android.graphics.drawable.GradientDrawable sequenceDrawable(String encoded, int fallback) {
        int[] colors = ColorSequence.decode(encoded, fallback);
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (colors.length == 1) drawable.setColor(colors[0]);
        else {
            drawable.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
            drawable.setColors(colors);
        }
        return drawable;
    }

    private void updateColorPreview(TextView preview, int[] channels) {
        int color = Color.rgb(channels[0], channels[1], channels[2]);
        preview.setText(String.format("#%06X", 0xFFFFFF & color));
        preview.setBackground(UiPalette.rounded(this, color, 9f));
        double luma = (0.299 * channels[0] + 0.587 * channels[1] + 0.114 * channels[2]);
        preview.setTextColor(luma > 150 ? Color.BLACK : Color.WHITE);
    }

    private void tintSeekBar(SeekBar seek) {
        UiChrome.styleSeekBar(this, seek);
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
        final LinearLayout block;
        final TextView title;
        final SeekBar seek;
        final TextView value;
        final String suffix;
        SeekControl(LinearLayout block, TextView title, SeekBar seek, TextView value, String suffix) {
            this.block = block;
            this.title = title;
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

    private interface MultiColorCallback { void onColors(int[] colors); }
    private interface TextCallback { void onText(String value); }
}
