package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 应用主界面。负责设置、用户操作和权限流程。 */
public final class MainActivity extends Activity implements ShizukuBridge.Listener {
    private static final int SHIZUKU_REQUEST_CODE = 4107;
    private static final int HTML_REQUEST_GLOBAL = 6200;
    private static final int CONFIG_EXPORT_REQUEST = 6201;
    private static final int CONFIG_IMPORT_REQUEST = 6202;
    private static final int MAX_CONFIG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private static final int SIZE_MIN = 50;
    private static final int SIZE_MAX = 150;
    private static final int OPACITY_MAX = 100;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Switch displaySwitch;
    private TextView keyboardSizeLabel;
    private SeekBar keyboardSizeSeekBar;
    private Switch spaceDisplaySwitch;
    private Switch spaceDpsSwitch;
    private Switch mouseSwitch;
    private Switch keyPromptSwitch;
    private TextView keyPromptSizeLabel;
    private SeekBar keyPromptSizeSeekBar;
    private TextView mouseSizeLabel;
    private SeekBar mouseSizeSeekBar;
    private Switch mouseTrajectorySwitch;
    private TextView mouseTrajectorySizeLabel;
    private SeekBar mouseTrajectorySizeSeekBar;
    private TextView mouseTrajectoryDotSizeLabel;
    private SeekBar mouseTrajectoryDotSizeSeekBar;
    private Switch mouseTrajectoryLeftColorSwitch;
    private Switch mouseTrajectoryRightColorSwitch;
    private View mouseTrajectoryLeftColorDot;
    private View mouseTrajectoryRightColorDot;
    private Switch customDisplaySwitch;
    private TextView customSizeLabel;
    private SeekBar customSizeSeekBar;
    private Switch captureSwitch;
    private Switch dragSwitch;
    private Switch dpsSwitch;
    private LinearLayout dpsDetails;
    private TextView dpsTargetText;
    private Switch autoHideSwitch;
    private Switch sensitivitySwitch;
    private Spinner sensitivityModeSpinner;
    private Spinner themeSpinner;
    private LinearLayout sensitivityDetails;
    private TextView mouseSensitivityLabel;
    private SeekBar mouseSensitivitySeekBar;
    private TextView gamepadSensitivityLabel;
    private SeekBar gamepadSensitivitySeekBar;
    private TextView sensitivityStatusText;
    private LinearLayout keyboardDetails;
    private LinearLayout mouseDetails;
    private LinearLayout keyPromptDetails;
    private LinearLayout mouseTrajectoryDetails;
    private LinearLayout customDetails;
    private Switch gamepadLeftStickSwitch;
    private Switch gamepadRightStickSwitch;
    private Switch gamepadFaceSwitch;
    private Switch gamepadLeftShoulderSwitch;
    private Switch gamepadRightShoulderSwitch;
    private LinearLayout gamepadLeftStickDetails;
    private LinearLayout gamepadRightStickDetails;
    private LinearLayout gamepadFaceDetails;
    private LinearLayout gamepadLeftShoulderDetails;
    private LinearLayout gamepadRightShoulderDetails;
    private TextView gamepadLeftStickSizeLabel;
    private TextView gamepadRightStickSizeLabel;
    private TextView gamepadFaceSizeLabel;
    private TextView gamepadLeftShoulderSizeLabel;
    private TextView gamepadRightShoulderSizeLabel;
    private SeekBar gamepadLeftStickSizeSeekBar;
    private SeekBar gamepadRightStickSizeSeekBar;
    private TextView gamepadLeftStickDotSizeLabel;
    private TextView gamepadRightStickDotSizeLabel;
    private SeekBar gamepadLeftStickDotSizeSeekBar;
    private SeekBar gamepadRightStickDotSizeSeekBar;
    private SeekBar gamepadFaceSizeSeekBar;
    private SeekBar gamepadLeftShoulderSizeSeekBar;
    private SeekBar gamepadRightShoulderSizeSeekBar;
    private Spinner gamepadLeftStickShapeSpinner;
    private Spinner gamepadRightStickShapeSpinner;
    private Switch gamepadFaceReverseSwitch;
    private Switch gamepadFaceYDpsSwitch;
    private Switch gamepadFaceXDpsSwitch;
    private Switch gamepadFaceBDpsSwitch;
    private Switch gamepadFaceADpsSwitch;
    private Switch gamepadL2ProgressSwitch;
    private Switch gamepadR2ProgressSwitch;
    private Switch gamepadL1DpsSwitch;
    private Switch gamepadR1DpsSwitch;
    private TextView recordedKeysText;
    private TextView columnsLabel;
    private SeekBar columnsSeekBar;
    private final Spinner[] motionSpinners = new Spinner[4];
    private final SparseArray<OpacityControl> opacityControls = new SparseArray<>();
    private Switch globalHtmlSwitch;
    private Button globalHtmlImportButton;
    private TextView globalHtmlStatusText;
    private LinearLayout globalHtmlDetails;

    private boolean internalChange;
    private boolean waitingForShizuku;
    private boolean dpsCaptureArmed;

    private final Runnable cpsBindingPoll = new Runnable() {
        @Override
        public void run() {
            if (!dpsCaptureArmed || dpsSwitch == null || !dpsSwitch.isChecked() || isFinishing()) return;
            if (OverlayState.getDpsTargetKeyCode(MainActivity.this) != OverlayState.DPS_TARGET_NONE) {
                dpsCaptureArmed = false;
                updateDpsTargetUi();
                return;
            }
            mainHandler.postDelayed(this, 100L);
        }
    };

    private final Runnable sensitivityStatusTicker = new Runnable() {
        @Override
        public void run() {
            if (sensitivityStatusText == null) return;
            sensitivityStatusText.setText(getString(
                    R.string.sensitivity_status_format,
                    OverlayState.getSensitivityStatus(MainActivity.this)));
            if (sensitivitySwitch != null && sensitivitySwitch.isChecked() && !isFinishing()) {
                mainHandler.postDelayed(this, 500L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 只在 Android 确认任务被移除时清理运行配置。
        // 重新创建或打开 Activity 不清理配置。

        setTheme(OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK
                ? R.style.AppThemeBlack : R.style.AppThemeLight);
        super.onCreate(savedInstanceState);
        applySystemBars();
        internalChange = true;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setBackgroundColor(UiPalette.background(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(dp(20), dp(14), dp(20), dp(28));
        root.setBackgroundColor(UiPalette.background(this));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = createTitle();
        title.setText(R.string.app_name);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView authorLink = createSupportingText();
        authorLink.setText(R.string.author_link);
        authorLink.setTextSize(11f);
        authorLink.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        authorLink.setPaintFlags(authorLink.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        authorLink.setPadding(dp(8), dp(8), 0, dp(8));
        authorLink.setOnClickListener(v -> showAuthorDialog());
        header.addView(authorLink, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, contentParams(dp(18)));

        TextView appearanceSection = createSectionLabel();
        appearanceSection.setText(R.string.section_appearance);
        root.addView(appearanceSection, contentParams(dp(8)));

        themeSpinner = createChoiceSpinner(new String[]{
                getString(R.string.theme_light), getString(R.string.theme_black)});
        themeSpinner.setSelection(OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK ? 1 : 0, false);
        root.addView(createChoiceGroup(R.string.theme_label, themeSpinner), contentParams(dp(14)));

        TextView displaySection = createSectionLabel();
        displaySection.setText(R.string.section_keyboard_mouse);
        root.addView(displaySection, contentParams(dp(8)));

        displaySwitch = createSwitch(R.string.switch_label);
        keyboardDetails = createDetailsContainer();
        keyboardSizeLabel = createLabel();
        keyboardDetails.addView(keyboardSizeLabel, supportingParams(0));
        keyboardSizeSeekBar = createSizeSeekBar();
        keyboardDetails.addView(keyboardSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(keyboardDetails, KeyOverlayView.DISPLAY_KEYBOARD);
        spaceDisplaySwitch = createSwitch(R.string.space_display_switch);
        spaceDisplaySwitch.setTextSize(14f);
        keyboardDetails.addView(spaceDisplaySwitch, switchParams(dp(2)));
        spaceDpsSwitch = createSwitch(R.string.space_dps_switch);
        spaceDpsSwitch.setTextSize(14f);
        keyboardDetails.addView(spaceDpsSwitch, switchParams(dp(2)));
        addMotionControls(keyboardDetails, KeyOverlayView.DISPLAY_KEYBOARD, R.string.keyboard_motion_label);
        root.addView(createFeatureGroup(displaySwitch, keyboardDetails), contentParams(dp(10)));

        mouseSwitch = createSwitch(R.string.mouse_switch_label);
        mouseDetails = createDetailsContainer();
        mouseSizeLabel = createLabel();
        mouseDetails.addView(mouseSizeLabel, supportingParams(0));
        mouseSizeSeekBar = createSizeSeekBar();
        mouseDetails.addView(mouseSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(mouseDetails, KeyOverlayView.DISPLAY_MOUSE);
        addMotionControls(mouseDetails, KeyOverlayView.DISPLAY_MOUSE, R.string.mouse_motion_label);
        root.addView(createFeatureGroup(mouseSwitch, mouseDetails), contentParams(dp(10)));

        keyPromptSwitch = createSwitch(R.string.key_prompt_switch_label);
        keyPromptDetails = createDetailsContainer();
        keyPromptSizeLabel = createLabel();
        keyPromptDetails.addView(keyPromptSizeLabel, supportingParams(0));
        keyPromptSizeSeekBar = createSizeSeekBar();
        keyPromptDetails.addView(keyPromptSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(keyPromptDetails, KeyPromptOverlayView.DISPLAY_KEY_PROMPT);
        TextView keyPromptHint = createSupportingText();
        keyPromptHint.setText(R.string.key_prompt_hint);
        keyPromptDetails.addView(keyPromptHint, supportingParams(dp(2)));
        root.addView(createFeatureGroup(keyPromptSwitch, keyPromptDetails), contentParams(dp(10)));

        mouseTrajectorySwitch = createSwitch(R.string.mouse_trajectory_switch_label);
        mouseTrajectoryDetails = createDetailsContainer();
        mouseTrajectorySizeLabel = createLabel();
        mouseTrajectoryDetails.addView(mouseTrajectorySizeLabel, supportingParams(0));
        mouseTrajectorySizeSeekBar = createSizeSeekBar();
        mouseTrajectoryDetails.addView(mouseTrajectorySizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(mouseTrajectoryDetails, MouseTrajectoryView.DISPLAY_TRAJECTORY);
        mouseTrajectoryDotSizeLabel = createLabel();
        mouseTrajectoryDetails.addView(mouseTrajectoryDotSizeLabel, supportingParams(dp(2)));
        mouseTrajectoryDotSizeSeekBar = createSizeSeekBar();
        mouseTrajectoryDetails.addView(mouseTrajectoryDotSizeSeekBar, seekBarLayoutParams(dp(4)));
        mouseTrajectoryLeftColorSwitch = createSwitch(R.string.mouse_trajectory_left_color);
        mouseTrajectoryLeftColorSwitch.setTextSize(14f);
        mouseTrajectoryLeftColorDot = createColorDot(OverlayState.getMouseTrajectoryLeftColor(this));
        mouseTrajectoryDetails.addView(
                createTrajectoryColorRow(mouseTrajectoryLeftColorDot, mouseTrajectoryLeftColorSwitch, true),
                supportingParams(dp(2)));
        mouseTrajectoryRightColorSwitch = createSwitch(R.string.mouse_trajectory_right_color);
        mouseTrajectoryRightColorSwitch.setTextSize(14f);
        mouseTrajectoryRightColorDot = createColorDot(OverlayState.getMouseTrajectoryRightColor(this));
        mouseTrajectoryDetails.addView(
                createTrajectoryColorRow(mouseTrajectoryRightColorDot, mouseTrajectoryRightColorSwitch, false),
                supportingParams(dp(2)));
        root.addView(createFeatureGroup(mouseTrajectorySwitch, mouseTrajectoryDetails), contentParams(dp(10)));

        customDisplaySwitch = createSwitch(R.string.custom_switch_label);
        customDetails = createDetailsContainer();
        customSizeLabel = createLabel();
        customDetails.addView(customSizeLabel, supportingParams(0));
        customSizeSeekBar = createSizeSeekBar();
        customDetails.addView(customSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(customDetails, KeyOverlayView.DISPLAY_CUSTOM);
        addMotionControls(customDetails, KeyOverlayView.DISPLAY_CUSTOM, R.string.custom_motion_label);

        captureSwitch = createSwitch(R.string.custom_capture_label);
        captureSwitch.setTextSize(15f);
        customDetails.addView(captureSwitch, switchParams(0));

        recordedKeysText = createSupportingText();
        customDetails.addView(recordedKeysText, supportingParams(dp(8)));

        columnsLabel = createLabel();
        customDetails.addView(columnsLabel, supportingParams(0));

        columnsSeekBar = new SeekBar(this);
        columnsSeekBar.setMax(7);
        customDetails.addView(columnsSeekBar, seekBarLayoutParams(dp(4)));
        root.addView(createFeatureGroup(customDisplaySwitch, customDetails), contentParams(dp(14)));

        TextView gamepadSection = createSectionLabel();
        gamepadSection.setText(R.string.section_gamepad);
        root.addView(gamepadSection, contentParams(dp(8)));

        gamepadLeftStickSwitch = createSwitch(R.string.gamepad_left_stick_switch);
        gamepadLeftStickDetails = createDetailsContainer();
        gamepadLeftStickSizeLabel = createLabel();
        gamepadLeftStickDetails.addView(gamepadLeftStickSizeLabel, supportingParams(0));
        gamepadLeftStickSizeSeekBar = createSizeSeekBar();
        gamepadLeftStickDetails.addView(gamepadLeftStickSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(gamepadLeftStickDetails, GamepadOverlayView.DISPLAY_LEFT_STICK);
        gamepadLeftStickDotSizeLabel = createLabel();
        gamepadLeftStickDetails.addView(gamepadLeftStickDotSizeLabel, supportingParams(dp(2)));
        gamepadLeftStickDotSizeSeekBar = createSizeSeekBar();
        gamepadLeftStickDetails.addView(gamepadLeftStickDotSizeSeekBar, seekBarLayoutParams(dp(4)));
        gamepadLeftStickShapeSpinner = createChoiceSpinner(new String[]{
                getString(R.string.gamepad_shape_circle), getString(R.string.gamepad_shape_square)});
        gamepadLeftStickDetails.addView(createInlineChoiceRow(R.string.gamepad_shape_label, gamepadLeftStickShapeSpinner), supportingParams(dp(4)));
        root.addView(createFeatureGroup(gamepadLeftStickSwitch, gamepadLeftStickDetails), contentParams(dp(10)));

        gamepadRightStickSwitch = createSwitch(R.string.gamepad_right_stick_switch);
        gamepadRightStickDetails = createDetailsContainer();
        gamepadRightStickSizeLabel = createLabel();
        gamepadRightStickDetails.addView(gamepadRightStickSizeLabel, supportingParams(0));
        gamepadRightStickSizeSeekBar = createSizeSeekBar();
        gamepadRightStickDetails.addView(gamepadRightStickSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(gamepadRightStickDetails, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        gamepadRightStickDotSizeLabel = createLabel();
        gamepadRightStickDetails.addView(gamepadRightStickDotSizeLabel, supportingParams(dp(2)));
        gamepadRightStickDotSizeSeekBar = createSizeSeekBar();
        gamepadRightStickDetails.addView(gamepadRightStickDotSizeSeekBar, seekBarLayoutParams(dp(4)));
        gamepadRightStickShapeSpinner = createChoiceSpinner(new String[]{
                getString(R.string.gamepad_shape_circle), getString(R.string.gamepad_shape_square)});
        gamepadRightStickDetails.addView(createInlineChoiceRow(R.string.gamepad_shape_label, gamepadRightStickShapeSpinner), supportingParams(dp(4)));
        root.addView(createFeatureGroup(gamepadRightStickSwitch, gamepadRightStickDetails), contentParams(dp(10)));

        gamepadFaceSwitch = createSwitch(R.string.gamepad_face_switch);
        gamepadFaceDetails = createDetailsContainer();
        gamepadFaceSizeLabel = createLabel();
        gamepadFaceDetails.addView(gamepadFaceSizeLabel, supportingParams(0));
        gamepadFaceSizeSeekBar = createSizeSeekBar();
        gamepadFaceDetails.addView(gamepadFaceSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(gamepadFaceDetails, GamepadOverlayView.DISPLAY_FACE);
        gamepadFaceReverseSwitch = createSwitch(R.string.gamepad_face_reverse);
        gamepadFaceReverseSwitch.setTextSize(14f);
        gamepadFaceDetails.addView(gamepadFaceReverseSwitch, switchParams(dp(2)));
        gamepadFaceYDpsSwitch = createSwitch(R.string.gamepad_face_y_dps);
        gamepadFaceYDpsSwitch.setTextSize(14f);
        gamepadFaceDetails.addView(gamepadFaceYDpsSwitch, switchParams(dp(2)));
        gamepadFaceXDpsSwitch = createSwitch(R.string.gamepad_face_x_dps);
        gamepadFaceXDpsSwitch.setTextSize(14f);
        gamepadFaceDetails.addView(gamepadFaceXDpsSwitch, switchParams(dp(2)));
        gamepadFaceBDpsSwitch = createSwitch(R.string.gamepad_face_b_dps);
        gamepadFaceBDpsSwitch.setTextSize(14f);
        gamepadFaceDetails.addView(gamepadFaceBDpsSwitch, switchParams(dp(2)));
        gamepadFaceADpsSwitch = createSwitch(R.string.gamepad_face_a_dps);
        gamepadFaceADpsSwitch.setTextSize(14f);
        gamepadFaceDetails.addView(gamepadFaceADpsSwitch, switchParams(dp(2)));
        TextView faceHint = createSupportingText();
        faceHint.setText(R.string.gamepad_face_hint);
        gamepadFaceDetails.addView(faceHint, supportingParams(dp(4)));
        root.addView(createFeatureGroup(gamepadFaceSwitch, gamepadFaceDetails), contentParams(dp(10)));

        gamepadLeftShoulderSwitch = createSwitch(R.string.gamepad_left_shoulder_switch);
        gamepadLeftShoulderDetails = createDetailsContainer();
        gamepadLeftShoulderSizeLabel = createLabel();
        gamepadLeftShoulderDetails.addView(gamepadLeftShoulderSizeLabel, supportingParams(0));
        gamepadLeftShoulderSizeSeekBar = createSizeSeekBar();
        gamepadLeftShoulderDetails.addView(gamepadLeftShoulderSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(gamepadLeftShoulderDetails, GamepadOverlayView.DISPLAY_LEFT_SHOULDER);
        gamepadL2ProgressSwitch = createSwitch(R.string.gamepad_l2_progress);
        gamepadL2ProgressSwitch.setTextSize(14f);
        gamepadLeftShoulderDetails.addView(gamepadL2ProgressSwitch, switchParams(dp(2)));
        gamepadL1DpsSwitch = createSwitch(R.string.gamepad_l1_dps);
        gamepadL1DpsSwitch.setTextSize(14f);
        gamepadLeftShoulderDetails.addView(gamepadL1DpsSwitch, switchParams(dp(2)));
        root.addView(createFeatureGroup(gamepadLeftShoulderSwitch, gamepadLeftShoulderDetails), contentParams(dp(10)));

        gamepadRightShoulderSwitch = createSwitch(R.string.gamepad_right_shoulder_switch);
        gamepadRightShoulderDetails = createDetailsContainer();
        gamepadRightShoulderSizeLabel = createLabel();
        gamepadRightShoulderDetails.addView(gamepadRightShoulderSizeLabel, supportingParams(0));
        gamepadRightShoulderSizeSeekBar = createSizeSeekBar();
        gamepadRightShoulderDetails.addView(gamepadRightShoulderSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(gamepadRightShoulderDetails, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER);
        gamepadR2ProgressSwitch = createSwitch(R.string.gamepad_r2_progress);
        gamepadR2ProgressSwitch.setTextSize(14f);
        gamepadRightShoulderDetails.addView(gamepadR2ProgressSwitch, switchParams(dp(2)));
        gamepadR1DpsSwitch = createSwitch(R.string.gamepad_r1_dps);
        gamepadR1DpsSwitch.setTextSize(14f);
        gamepadRightShoulderDetails.addView(gamepadR1DpsSwitch, switchParams(dp(2)));
        root.addView(createFeatureGroup(gamepadRightShoulderSwitch, gamepadRightShoulderDetails), contentParams(dp(14)));

        TextView sensitivitySection = createSectionLabel();
        sensitivitySection.setText(R.string.section_sensitivity);
        root.addView(sensitivitySection, contentParams(dp(8)));

        sensitivitySwitch = createSwitch(R.string.sensitivity_switch_label);
        sensitivityDetails = createDetailsContainer();

        sensitivityModeSpinner = createChoiceSpinner(new String[]{
                getString(R.string.sensitivity_mode_shizuku), getString(R.string.sensitivity_mode_root)});
        sensitivityModeSpinner.setSelection(
                OverlayState.getSensitivityMode(this) == OverlayState.SENSITIVITY_MODE_ROOT ? 1 : 0, false);
        sensitivityDetails.addView(createInlineChoiceRow(R.string.sensitivity_mode_label, sensitivityModeSpinner), supportingParams(dp(8)));

        mouseSensitivityLabel = createLabel();
        sensitivityDetails.addView(mouseSensitivityLabel, supportingParams(0));
        mouseSensitivitySeekBar = createSensitivitySeekBar();
        sensitivityDetails.addView(mouseSensitivitySeekBar, seekBarLayoutParams(dp(4)));

        gamepadSensitivityLabel = createLabel();
        sensitivityDetails.addView(gamepadSensitivityLabel, supportingParams(0));
        gamepadSensitivitySeekBar = createSensitivitySeekBar();
        sensitivityDetails.addView(gamepadSensitivitySeekBar, seekBarLayoutParams(dp(4)));

        TextView sensitivityHint = createSupportingText();
        sensitivityHint.setText(R.string.sensitivity_hint);
        sensitivityDetails.addView(sensitivityHint, supportingParams(dp(6)));

        sensitivityStatusText = createSupportingText();
        sensitivityDetails.addView(sensitivityStatusText, supportingParams(0));
        root.addView(createFeatureGroup(sensitivitySwitch, sensitivityDetails), contentParams(dp(14)));

        TextView behaviorSection = createSectionLabel();

        behaviorSection.setText(R.string.section_behavior);
        root.addView(behaviorSection, contentParams(dp(8)));

        dpsSwitch = createSwitch(R.string.dps_switch_label);
        dpsDetails = createDetailsContainer();
        dpsTargetText = createSupportingText();
        dpsDetails.addView(dpsTargetText, supportingParams(dp(2)));
        TextView dpsHint = createSupportingText();
        dpsHint.setText(R.string.dps_target_hint);
        dpsDetails.addView(dpsHint, supportingParams(dp(2)));
        addOpacityControl(dpsDetails, DpsOverlayView.DISPLAY_DPS);
        root.addView(createFeatureGroup(dpsSwitch, dpsDetails), contentParams(dp(10)));

        dragSwitch = createSwitch(R.string.drag_switch_label);
        root.addView(createSwitchGroup(dragSwitch), contentParams(dp(10)));

        globalHtmlSwitch = createSwitch(R.string.global_html_switch_label);
        globalHtmlDetails = createDetailsContainer();
        TextView globalHtmlHint = createSupportingText();
        globalHtmlHint.setText(R.string.global_html_hint);
        globalHtmlDetails.addView(globalHtmlHint, supportingParams(dp(6)));
        globalHtmlImportButton = new Button(this);
        globalHtmlImportButton.setText(R.string.global_html_import);
        globalHtmlImportButton.setAllCaps(false);
        globalHtmlImportButton.setTextSize(13f);
        globalHtmlImportButton.setMinHeight(dp(38));
        globalHtmlImportButton.setMinimumHeight(dp(38));
        globalHtmlDetails.addView(globalHtmlImportButton, supportingParams(dp(2)));
        globalHtmlStatusText = createSupportingText();
        globalHtmlDetails.addView(globalHtmlStatusText, supportingParams(dp(6)));
        root.addView(createFeatureGroup(globalHtmlSwitch, globalHtmlDetails), contentParams(dp(10)));

        autoHideSwitch = createSwitch(R.string.auto_hide_label);
        root.addView(createSwitchGroup(autoHideSwitch), contentParams(dp(14)));

        TextView configurationSection = createSectionLabel();
        configurationSection.setText(R.string.section_configuration);
        root.addView(configurationSection, contentParams(dp(8)));
        root.addView(createConfig1Group(), contentParams(dp(14)));

        TextView htmlGuideLink = createSupportingText();
        htmlGuideLink.setText(R.string.html_guide_link);
        htmlGuideLink.setTextSize(11f);
        htmlGuideLink.setGravity(Gravity.CENTER);
        htmlGuideLink.setPaintFlags(htmlGuideLink.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        htmlGuideLink.setPadding(dp(4), dp(10), dp(4), dp(10));
        htmlGuideLink.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, HtmlGuideActivity.class));
        });
        root.addView(htmlGuideLink, contentParams(0));

        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange) return;
                int next = position == 1 ? OverlayState.UI_THEME_BLACK : OverlayState.UI_THEME_LIGHT;
                if (OverlayState.getUiTheme(MainActivity.this) == next) return;
                OverlayState.setUiTheme(MainActivity.this, next);
                recreate();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        displaySwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(keyboardDetails, enabled);
            if (internalChange) return;
            OverlayState.setEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        spaceDisplaySwitch.setOnCheckedChangeListener((button, enabled) -> {
            spaceDpsSwitch.setEnabled(enabled);
            if (!internalChange) OverlayState.setKeyboardSpaceEnabled(this, enabled);
        });

        spaceDpsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setKeyboardSpaceDpsEnabled(this, enabled);
        });

        mouseSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(mouseDetails, enabled);
            if (internalChange) return;
            OverlayState.setMouseEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        keyPromptSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(keyPromptDetails, enabled);
            if (internalChange) return;
            OverlayState.setKeyPromptEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        mouseTrajectorySwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(mouseTrajectoryDetails, enabled);
            if (internalChange) return;
            OverlayState.setMouseTrajectoryEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        mouseTrajectoryLeftColorSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            OverlayState.setMouseTrajectoryLeftColorEnabled(this, enabled);
        });

        mouseTrajectoryRightColorSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            OverlayState.setMouseTrajectoryRightColorEnabled(this, enabled);
        });

        customDisplaySwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(customDetails, enabled);
            if (internalChange) return;
            OverlayState.setCustomEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        gamepadLeftStickSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(gamepadLeftStickDetails, enabled);
            if (internalChange) return;
            OverlayState.setGamepadLeftStickEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        gamepadRightStickSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(gamepadRightStickDetails, enabled);
            if (internalChange) return;
            OverlayState.setGamepadRightStickEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        gamepadFaceSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(gamepadFaceDetails, enabled);
            if (internalChange) return;
            OverlayState.setGamepadFaceEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        gamepadLeftShoulderSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(gamepadLeftShoulderDetails, enabled);
            if (internalChange) return;
            OverlayState.setGamepadLeftShoulderEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        gamepadRightShoulderSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(gamepadRightShoulderDetails, enabled);
            if (internalChange) return;
            OverlayState.setGamepadRightShoulderEnabled(this, enabled);
            handleDisplayModeChanged();
        });

        keyboardSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                keyboardSizeLabel, R.string.keyboard_size_format,
                value -> OverlayState.setKeyboardSize(MainActivity.this, value)));

        mouseSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                mouseSizeLabel, R.string.mouse_size_format,
                value -> OverlayState.setMouseSize(MainActivity.this, value)));

        keyPromptSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                keyPromptSizeLabel, R.string.key_prompt_size_format,
                value -> OverlayState.setKeyPromptSize(MainActivity.this, value)));

        mouseTrajectorySizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                mouseTrajectorySizeLabel, R.string.mouse_trajectory_size_format,
                value -> OverlayState.setMouseTrajectorySize(MainActivity.this, value)));

        mouseTrajectoryDotSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                mouseTrajectoryDotSizeLabel, R.string.mouse_trajectory_dot_size_format,
                value -> OverlayState.setMouseTrajectoryDotSize(MainActivity.this, value)));

        customSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                customSizeLabel, R.string.custom_size_format,
                value -> OverlayState.setCustomSize(MainActivity.this, value)));

        gamepadLeftStickSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadLeftStickSizeLabel, R.string.gamepad_left_stick_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_LEFT_STICK, value)));

        gamepadRightStickSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadRightStickSizeLabel, R.string.gamepad_right_stick_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_RIGHT_STICK, value)));

        gamepadLeftStickDotSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadLeftStickDotSizeLabel, R.string.gamepad_left_stick_dot_size_format,
                value -> OverlayState.setGamepadStickDotSize(MainActivity.this, GamepadOverlayView.DISPLAY_LEFT_STICK, value)));

        gamepadRightStickDotSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadRightStickDotSizeLabel, R.string.gamepad_right_stick_dot_size_format,
                value -> OverlayState.setGamepadStickDotSize(MainActivity.this, GamepadOverlayView.DISPLAY_RIGHT_STICK, value)));

        gamepadFaceSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadFaceSizeLabel, R.string.gamepad_face_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_FACE, value)));

        gamepadLeftShoulderSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadLeftShoulderSizeLabel, R.string.gamepad_left_shoulder_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_LEFT_SHOULDER, value)));

        gamepadRightShoulderSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadRightShoulderSizeLabel, R.string.gamepad_right_shoulder_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER, value)));

        gamepadLeftStickShapeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!internalChange) OverlayState.setGamepadLeftStickShape(MainActivity.this,
                        position == 1 ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        gamepadRightStickShapeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!internalChange) OverlayState.setGamepadRightStickShape(MainActivity.this,
                        position == 1 ? GamepadOverlayView.SHAPE_SQUARE : GamepadOverlayView.SHAPE_CIRCLE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        gamepadFaceReverseSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setGamepadFaceReversed(MainActivity.this, enabled);
        });

        gamepadFaceYDpsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setGamepadFaceYDpsEnabled(MainActivity.this, enabled);
        });
        gamepadFaceXDpsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setGamepadFaceXDpsEnabled(MainActivity.this, enabled);
        });
        gamepadFaceBDpsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setGamepadFaceBDpsEnabled(MainActivity.this, enabled);
        });
        gamepadFaceADpsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setGamepadFaceADpsEnabled(MainActivity.this, enabled);
        });
        gamepadL2ProgressSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setGamepadL2ProgressEnabled(MainActivity.this, enabled);
        });
        gamepadR2ProgressSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setGamepadR2ProgressEnabled(MainActivity.this, enabled);
        });
        gamepadL1DpsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setGamepadL1DpsEnabled(MainActivity.this, enabled);
        });
        gamepadR1DpsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setGamepadR1DpsEnabled(MainActivity.this, enabled);
        });

        captureSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (enabled) {
                OverlayState.beginCustomCapture(this);
                updateRecordedKeys(true);
                Toast.makeText(this, R.string.custom_capture_started, Toast.LENGTH_SHORT).show();
            } else {
                OverlayState.finishCustomCapture(this);
                updateRecordedKeys(false);
                Toast.makeText(this, R.string.custom_capture_saved, Toast.LENGTH_SHORT).show();
            }
        });

        columnsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int columns = progress + 1;
                columnsLabel.setText(getString(R.string.columns_format, columns));
                if (fromUser && !internalChange) OverlayState.setCustomColumns(MainActivity.this, columns);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sensitivitySwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(sensitivityDetails, enabled);
            if (internalChange) return;
            OverlayState.setSensitivityEnabled(this, enabled);
            if (enabled) {
                ensureAccessibility();
                mainHandler.removeCallbacks(sensitivityStatusTicker);
                mainHandler.post(sensitivityStatusTicker);
            } else {
                mainHandler.removeCallbacks(sensitivityStatusTicker);
                sensitivityStatusText.setText(getString(R.string.sensitivity_status_format, getString(R.string.status_disabled)));
            }
        });

        sensitivityModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange) return;
                int mode = position == 1 ? OverlayState.SENSITIVITY_MODE_ROOT
                        : OverlayState.SENSITIVITY_MODE_SHIZUKU;
                OverlayState.setSensitivityMode(MainActivity.this, mode);
                if (sensitivitySwitch.isChecked()) ensureAccessibility();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        mouseSensitivitySeekBar.setOnSeekBarChangeListener(sensitivityListener(
                mouseSensitivityLabel, R.string.mouse_sensitivity_format,
                value -> OverlayState.setMouseSensitivity(MainActivity.this, value)));

        gamepadSensitivitySeekBar.setOnSeekBarChangeListener(sensitivityListener(
                gamepadSensitivityLabel, R.string.gamepad_sensitivity_format,
                value -> OverlayState.setGamepadSensitivity(MainActivity.this, value)));

        dpsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(dpsDetails, enabled);
            if (internalChange) return;
            if (enabled) {
                OverlayState.setDpsTargetKeyCode(this, OverlayState.DPS_TARGET_NONE);
                dpsCaptureArmed = true;
                OverlayState.setDpsEnabled(this, true);
                mainHandler.removeCallbacks(cpsBindingPoll);
                mainHandler.post(cpsBindingPoll);
            } else {
                dpsCaptureArmed = false;
                mainHandler.removeCallbacks(cpsBindingPoll);
                OverlayState.setDpsEnabled(this, false);
            }
            updateDpsTargetUi();
            handleDisplayModeChanged();
        });

        dragSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) OverlayState.setDragEnabled(this, enabled);
        });

        globalHtmlSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setDetailsVisible(globalHtmlDetails, enabled);
            if (internalChange) return;
            OverlayState.setGlobalHtmlEnabled(this, enabled);
            syncGlobalHtmlUi();
            if (enabled && !OverlayState.hasGlobalHtml(this)) {
                Toast.makeText(this, R.string.global_html_import_first, Toast.LENGTH_SHORT).show();
            }
        });
        globalHtmlImportButton.setOnClickListener(v -> openGlobalHtmlPicker());

        autoHideSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            OverlayState.setAutoHideBackground(this, enabled);
            // 前台 Activity 保持正常显示。
            // 最后一个 Activity 离开前台后再执行隐藏。
            AxonApplication.syncTaskVisibility(this, false);
        });
        internalChange = false;
        showEntryPasswordIfNeeded();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (dpsCaptureArmed
                && dpsSwitch != null
                && dpsSwitch.isChecked()
                && isPhysicalKeyboardEvent(event)) {
            int currentTarget = OverlayState.getDpsTargetKeyCode(this);
            if (currentTarget != OverlayState.DPS_TARGET_NONE) {
                dpsCaptureArmed = false;
                mainHandler.removeCallbacks(cpsBindingPoll);
                updateDpsTargetUi();
            } else {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    OverlayState.setDpsTargetKeyCode(this, event.getKeyCode());
                    dpsCaptureArmed = false;
                    mainHandler.removeCallbacks(cpsBindingPoll);
                    updateDpsTargetUi();
                }
                return true;
            }
        }
        if (captureSwitch != null
                && OverlayState.isCustomCaptureEnabled(this)
                && isPhysicalKeyboardEvent(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (OverlayState.addDraftKey(this, event.getKeyCode())) {
                    updateRecordedKeys(true);
                }
            }
            // 录入模式会在设置页消费按键，避免控件误触。
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleDisplayModeChanged() {
        if (!needsAccessibility()) {
            waitingForShizuku = false;
            return;
        }
        ensureAccessibility();
    }

    private boolean needsAccessibility() {
        return OverlayState.isAnyDisplayEnabled(this) || OverlayState.isSensitivityEnabled(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShizukuBridge.addListener(this);
    }

    @Override
    protected void onStop() {
        ShizukuBridge.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // 这里不清理运行配置。
        // Back、进程重建和隐藏任务都可能销毁 Activity。
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(sensitivityStatusTicker);
        mainHandler.removeCallbacks(cpsBindingPoll);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AxonApplication.syncTaskVisibility(this, false);

        internalChange = true;
        themeSpinner.setSelection(OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK ? 1 : 0, false);
        displaySwitch.setChecked(OverlayState.isEnabled(this));
        spaceDisplaySwitch.setChecked(OverlayState.isKeyboardSpaceEnabled(this));
        spaceDpsSwitch.setChecked(OverlayState.isKeyboardSpaceDpsEnabled(this));
        spaceDpsSwitch.setEnabled(spaceDisplaySwitch.isChecked());
        mouseSwitch.setChecked(OverlayState.isMouseEnabled(this));
        keyPromptSwitch.setChecked(OverlayState.isKeyPromptEnabled(this));
        mouseTrajectorySwitch.setChecked(OverlayState.isMouseTrajectoryEnabled(this));
        mouseTrajectoryLeftColorSwitch.setChecked(OverlayState.isMouseTrajectoryLeftColorEnabled(this));
        mouseTrajectoryRightColorSwitch.setChecked(OverlayState.isMouseTrajectoryRightColorEnabled(this));
        updateColorDot(mouseTrajectoryLeftColorDot, OverlayState.getMouseTrajectoryLeftColor(this));
        updateColorDot(mouseTrajectoryRightColorDot, OverlayState.getMouseTrajectoryRightColor(this));
        customDisplaySwitch.setChecked(OverlayState.isCustomEnabled(this));
        gamepadLeftStickSwitch.setChecked(OverlayState.isGamepadLeftStickEnabled(this));
        gamepadRightStickSwitch.setChecked(OverlayState.isGamepadRightStickEnabled(this));
        gamepadFaceSwitch.setChecked(OverlayState.isGamepadFaceEnabled(this));
        gamepadLeftShoulderSwitch.setChecked(OverlayState.isGamepadLeftShoulderEnabled(this));
        gamepadRightShoulderSwitch.setChecked(OverlayState.isGamepadRightShoulderEnabled(this));
        gamepadFaceReverseSwitch.setChecked(OverlayState.isGamepadFaceReversed(this));
        gamepadFaceYDpsSwitch.setChecked(OverlayState.isGamepadFaceYDpsEnabled(this));
        gamepadFaceXDpsSwitch.setChecked(OverlayState.isGamepadFaceXDpsEnabled(this));
        gamepadFaceBDpsSwitch.setChecked(OverlayState.isGamepadFaceBDpsEnabled(this));
        gamepadFaceADpsSwitch.setChecked(OverlayState.isGamepadFaceADpsEnabled(this));
        gamepadL2ProgressSwitch.setChecked(OverlayState.isGamepadL2ProgressEnabled(this));
        gamepadR2ProgressSwitch.setChecked(OverlayState.isGamepadR2ProgressEnabled(this));
        gamepadL1DpsSwitch.setChecked(OverlayState.isGamepadL1DpsEnabled(this));
        gamepadR1DpsSwitch.setChecked(OverlayState.isGamepadR1DpsEnabled(this));
        gamepadLeftStickShapeSpinner.setSelection(OverlayState.getGamepadLeftStickShape(this) == GamepadOverlayView.SHAPE_SQUARE ? 1 : 0, false);
        gamepadRightStickShapeSpinner.setSelection(OverlayState.getGamepadRightStickShape(this) == GamepadOverlayView.SHAPE_SQUARE ? 1 : 0, false);
        captureSwitch.setChecked(OverlayState.isCustomCaptureEnabled(this));
        dpsSwitch.setChecked(OverlayState.isDpsEnabled(this));
        dpsCaptureArmed = OverlayState.isDpsEnabled(this)
                && OverlayState.getDpsTargetKeyCode(this) == OverlayState.DPS_TARGET_NONE;
        updateDpsTargetUi();
        mainHandler.removeCallbacks(cpsBindingPoll);
        if (dpsCaptureArmed) mainHandler.post(cpsBindingPoll);
        dragSwitch.setChecked(OverlayState.isDragEnabled(this));
        globalHtmlSwitch.setChecked(OverlayState.isGlobalHtmlEnabled(this));
        autoHideSwitch.setChecked(OverlayState.isAutoHideBackground(this));
        sensitivitySwitch.setChecked(OverlayState.isSensitivityEnabled(this));
        sensitivityModeSpinner.setSelection(
                OverlayState.getSensitivityMode(this) == OverlayState.SENSITIVITY_MODE_ROOT ? 1 : 0, false);

        int keyboardSize = OverlayState.getKeyboardSize(this);
        keyboardSizeSeekBar.setProgress(keyboardSize - SIZE_MIN);
        keyboardSizeLabel.setText(getString(R.string.keyboard_size_format, keyboardSize));

        int mouseSize = OverlayState.getMouseSize(this);
        mouseSizeSeekBar.setProgress(mouseSize - SIZE_MIN);
        mouseSizeLabel.setText(getString(R.string.mouse_size_format, mouseSize));

        int keyPromptSize = OverlayState.getKeyPromptSize(this);
        keyPromptSizeSeekBar.setProgress(keyPromptSize - SIZE_MIN);
        keyPromptSizeLabel.setText(getString(R.string.key_prompt_size_format, keyPromptSize));

        int mouseTrajectorySize = OverlayState.getMouseTrajectorySize(this);
        mouseTrajectorySizeSeekBar.setProgress(mouseTrajectorySize - SIZE_MIN);
        mouseTrajectorySizeLabel.setText(getString(R.string.mouse_trajectory_size_format, mouseTrajectorySize));

        int mouseTrajectoryDotSize = OverlayState.getMouseTrajectoryDotSize(this);
        mouseTrajectoryDotSizeSeekBar.setProgress(mouseTrajectoryDotSize - SIZE_MIN);
        mouseTrajectoryDotSizeLabel.setText(getString(R.string.mouse_trajectory_dot_size_format, mouseTrajectoryDotSize));

        int customSize = OverlayState.getCustomSize(this);
        customSizeSeekBar.setProgress(customSize - SIZE_MIN);
        customSizeLabel.setText(getString(R.string.custom_size_format, customSize));

        int gamepadLeftStickSize = OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_LEFT_STICK);
        gamepadLeftStickSizeSeekBar.setProgress(gamepadLeftStickSize - SIZE_MIN);
        gamepadLeftStickSizeLabel.setText(getString(R.string.gamepad_left_stick_size_format, gamepadLeftStickSize));

        int gamepadRightStickSize = OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        gamepadRightStickSizeSeekBar.setProgress(gamepadRightStickSize - SIZE_MIN);
        gamepadRightStickSizeLabel.setText(getString(R.string.gamepad_right_stick_size_format, gamepadRightStickSize));

        int gamepadLeftStickDotSize = OverlayState.getGamepadStickDotSize(this, GamepadOverlayView.DISPLAY_LEFT_STICK);
        gamepadLeftStickDotSizeSeekBar.setProgress(gamepadLeftStickDotSize - SIZE_MIN);
        gamepadLeftStickDotSizeLabel.setText(getString(R.string.gamepad_left_stick_dot_size_format, gamepadLeftStickDotSize));

        int gamepadRightStickDotSize = OverlayState.getGamepadStickDotSize(this, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        gamepadRightStickDotSizeSeekBar.setProgress(gamepadRightStickDotSize - SIZE_MIN);
        gamepadRightStickDotSizeLabel.setText(getString(R.string.gamepad_right_stick_dot_size_format, gamepadRightStickDotSize));

        int gamepadFaceSize = OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_FACE);
        gamepadFaceSizeSeekBar.setProgress(gamepadFaceSize - SIZE_MIN);
        gamepadFaceSizeLabel.setText(getString(R.string.gamepad_face_size_format, gamepadFaceSize));

        int gamepadLeftShoulderSize = OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_LEFT_SHOULDER);
        gamepadLeftShoulderSizeSeekBar.setProgress(gamepadLeftShoulderSize - SIZE_MIN);
        gamepadLeftShoulderSizeLabel.setText(getString(R.string.gamepad_left_shoulder_size_format, gamepadLeftShoulderSize));

        int gamepadRightShoulderSize = OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER);
        gamepadRightShoulderSizeSeekBar.setProgress(gamepadRightShoulderSize - SIZE_MIN);
        gamepadRightShoulderSizeLabel.setText(getString(R.string.gamepad_right_shoulder_size_format, gamepadRightShoulderSize));

        int mouseSensitivity = OverlayState.getMouseSensitivity(this);
        mouseSensitivitySeekBar.setProgress(mouseSensitivity - 1);
        mouseSensitivityLabel.setText(getString(R.string.mouse_sensitivity_format, mouseSensitivity));

        int gamepadSensitivity = OverlayState.getGamepadSensitivity(this);
        gamepadSensitivitySeekBar.setProgress(gamepadSensitivity - 1);
        gamepadSensitivityLabel.setText(getString(R.string.gamepad_sensitivity_format, gamepadSensitivity));
        sensitivityStatusText.setText(getString(
                R.string.sensitivity_status_format, OverlayState.getSensitivityStatus(this)));

        int columns = OverlayState.getCustomColumns(this);
        columnsSeekBar.setProgress(columns - 1);
        columnsLabel.setText(getString(R.string.columns_format, columns));
        updateRecordedKeys(OverlayState.isCustomCaptureEnabled(this));
        syncMotionUi(KeyOverlayView.DISPLAY_KEYBOARD);
        syncMotionUi(KeyOverlayView.DISPLAY_MOUSE);
        syncMotionUi(KeyOverlayView.DISPLAY_CUSTOM);
        syncOpacityUi(KeyOverlayView.DISPLAY_KEYBOARD);
        syncOpacityUi(KeyOverlayView.DISPLAY_MOUSE);
        syncOpacityUi(KeyPromptOverlayView.DISPLAY_KEY_PROMPT);
        syncOpacityUi(MouseTrajectoryView.DISPLAY_TRAJECTORY);
        syncOpacityUi(KeyOverlayView.DISPLAY_CUSTOM);
        syncOpacityUi(GamepadOverlayView.DISPLAY_LEFT_STICK);
        syncOpacityUi(GamepadOverlayView.DISPLAY_RIGHT_STICK);
        syncOpacityUi(GamepadOverlayView.DISPLAY_FACE);
        syncOpacityUi(GamepadOverlayView.DISPLAY_LEFT_SHOULDER);
        syncOpacityUi(GamepadOverlayView.DISPLAY_RIGHT_SHOULDER);
        setDetailsVisible(keyboardDetails, displaySwitch.isChecked());
        setDetailsVisible(mouseDetails, mouseSwitch.isChecked());
        setDetailsVisible(keyPromptDetails, keyPromptSwitch.isChecked());
        setDetailsVisible(mouseTrajectoryDetails, mouseTrajectorySwitch.isChecked());
        setDetailsVisible(customDetails, customDisplaySwitch.isChecked());
        setDetailsVisible(gamepadLeftStickDetails, gamepadLeftStickSwitch.isChecked());
        setDetailsVisible(gamepadRightStickDetails, gamepadRightStickSwitch.isChecked());
        setDetailsVisible(gamepadFaceDetails, gamepadFaceSwitch.isChecked());
        setDetailsVisible(gamepadLeftShoulderDetails, gamepadLeftShoulderSwitch.isChecked());
        setDetailsVisible(gamepadRightShoulderDetails, gamepadRightShoulderSwitch.isChecked());
        setDetailsVisible(sensitivityDetails, sensitivitySwitch.isChecked());
        setDetailsVisible(globalHtmlDetails, globalHtmlSwitch.isChecked());
        syncGlobalHtmlUi();
        internalChange = false;
        mainHandler.removeCallbacks(sensitivityStatusTicker);
        if (sensitivitySwitch.isChecked()) mainHandler.post(sensitivityStatusTicker);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == HTML_REQUEST_GLOBAL) {
            try {
                String html = readText(uri, MAX_HTML_BYTES);
                String name = queryDisplayName(uri);
                OverlayState.saveGlobalHtml(this, name, html);
                internalChange = true;
                globalHtmlSwitch.setChecked(true);
                setDetailsVisible(globalHtmlDetails, true);
                syncGlobalHtmlUi();
                internalChange = false;
                Toast.makeText(this, R.string.global_html_import_success, Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                internalChange = false;
                Toast.makeText(this, R.string.global_html_import_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == CONFIG_EXPORT_REQUEST) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Cannot open export target");
                out.write(ConfigManager.exportCurrent(this).getBytes(StandardCharsets.UTF_8));
                out.flush();
                Toast.makeText(this, R.string.config_export_success, Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                Toast.makeText(this, R.string.config_export_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == CONFIG_IMPORT_REQUEST) {
            try {
                ConfigManager.importInto(this, readText(uri, MAX_CONFIG_BYTES));
                Toast.makeText(this, R.string.config_import_success, Toast.LENGTH_SHORT).show();
                recreate();
            } catch (Throwable error) {
                Toast.makeText(this, R.string.config_import_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onShizukuReady(boolean permissionGranted) {
        if (!waitingForShizuku) return;
        waitingForShizuku = false;
        ensureAccessibility();
    }

    @Override
    public void onShizukuPermissionResult(int requestCode, boolean granted) {
        if (requestCode != SHIZUKU_REQUEST_CODE) return;
        waitingForShizuku = false;
        if (granted) {
            if (isAccessibilityServiceEnabled()) AxonInputAccessibilityService.refreshActiveService();
            else grantAccessibilityWithShizuku();
        } else {
            Toast.makeText(this, R.string.shizuku_denied, Toast.LENGTH_SHORT).show();
            if (!isAccessibilityServiceEnabled()) openAccessibilitySettings();
        }
    }

    @Override
    public void onShizukuDead() {
        waitingForShizuku = false;
    }

    private void ensureAccessibility() {
        boolean sensitivity = OverlayState.isSensitivityEnabled(this);
        boolean rootMode = sensitivity
                && OverlayState.getSensitivityMode(this) == OverlayState.SENSITIVITY_MODE_ROOT;

        if (isAccessibilityServiceEnabled()) {
            AxonInputAccessibilityService.refreshActiveService();
            if (sensitivity && !rootMode) ensureShizukuForSensitivity();
            return;
        }

        // Root 超频模式不依赖 Shizuku。
        // Root 模式可直接启用无障碍服务。
        if (rootMode) {
            grantAccessibilityWithRoot();
            return;
        }

        if (!ShizukuBridge.isAvailable()) {
            Toast.makeText(this, R.string.shizuku_unavailable, Toast.LENGTH_SHORT).show();
            openAccessibilitySettings();
            return;
        }

        if (!ShizukuBridge.isReady()) {
            waitingForShizuku = true;
            Toast.makeText(this, R.string.shizuku_connecting, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!ShizukuBridge.hasPermission()) {
            waitingForShizuku = true;
            if (!ShizukuBridge.requestPermission(SHIZUKU_REQUEST_CODE)) {
                waitingForShizuku = false;
                openAccessibilitySettings();
            }
            return;
        }

        grantAccessibilityWithShizuku();
    }

    private void ensureShizukuForSensitivity() {
        if (!ShizukuBridge.isAvailable()) {
            Toast.makeText(this, R.string.sensitivity_requires_shizuku, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ShizukuBridge.isReady()) {
            waitingForShizuku = true;
            return;
        }
        if (!ShizukuBridge.hasPermission()) {
            waitingForShizuku = true;
            if (!ShizukuBridge.requestPermission(SHIZUKU_REQUEST_CODE)) {
                waitingForShizuku = false;
            }
            return;
        }
        AxonInputAccessibilityService.refreshActiveService();
    }

    private void grantAccessibilityWithShizuku() {
        final String component = new ComponentName(this, AxonInputAccessibilityService.class).flattenToString();
        final String command =
                "SERVICE='" + component + "'; "
                + "CURRENT=\"$(settings get secure enabled_accessibility_services)\"; "
                + "if [ \"$CURRENT\" = null ] || [ -z \"$CURRENT\" ]; then NEW=\"$SERVICE\"; "
                + "else case \":$CURRENT:\" in *\":$SERVICE:\"*) NEW=\"$CURRENT\";; *) NEW=\"$CURRENT:$SERVICE\";; esac; fi; "
                + "settings put secure enabled_accessibility_services \"$NEW\" && "
                + "settings put secure accessibility_enabled 1";

        new Thread(() -> {
            boolean success;
            try {
                success = ShizukuBridge.runShell(command) == 0;
            } catch (Throwable ignored) {
                success = false;
            }

            final boolean result = success;
            mainHandler.post(() -> {
                if (isFinishing()) return;
                if (result) {
                    Toast.makeText(this, R.string.shizuku_grant_success, Toast.LENGTH_SHORT).show();
                    AxonInputAccessibilityService.refreshActiveService();
                } else {
                    Toast.makeText(this, R.string.shizuku_grant_failed, Toast.LENGTH_SHORT).show();
                    openAccessibilitySettings();
                }
            });
        }, "ShizukuAccessibilityGrant").start();
    }

    private void grantAccessibilityWithRoot() {
        final String component = new ComponentName(this, AxonInputAccessibilityService.class).flattenToString();
        final String command =
                "SERVICE='" + component + "'; "
                + "CURRENT=\"$(settings get secure enabled_accessibility_services)\"; "
                + "if [ \"$CURRENT\" = null ] || [ -z \"$CURRENT\" ]; then NEW=\"$SERVICE\"; "
                + "else case \":$CURRENT:\" in *\":$SERVICE:\"*) NEW=\"$CURRENT\";; *) NEW=\"$CURRENT:$SERVICE\";; esac; fi; "
                + "settings put secure enabled_accessibility_services \"$NEW\" && "
                + "settings put secure accessibility_enabled 1";
        new Thread(() -> {
            boolean success;
            try {
                success = RootBridge.runShell(command) == 0;
            } catch (Throwable ignored) {
                success = false;
            }
            final boolean result = success;
            mainHandler.post(() -> {
                if (isFinishing()) return;
                if (result) {
                    AxonInputAccessibilityService.refreshActiveService();
                    Toast.makeText(this, R.string.root_granted, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.root_denied_open_accessibility, Toast.LENGTH_SHORT).show();
                    openAccessibilitySettings();
                }
            });
        }, "RootAccessibilityGrant").start();
    }

    private LinearLayout createConfig1Group() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(14), dp(10), dp(14), dp(12));
        group.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 12f));

        TextView title = createLabel();
        title.setText(R.string.config1_title);
        title.setTextColor(UiPalette.textPrimary(this));
        title.setTextSize(16f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        group.addView(title, supportingParams(dp(6)));

        LinearLayout details = createDetailsContainer();
        details.setVisibility(View.VISIBLE);
        details.addView(createConfigActionRow(
                R.string.config_save, v -> saveConfig1(),
                R.string.config_load, v -> loadConfig1()), supportingParams(dp(6)));
        details.addView(createConfigActionRow(
                R.string.config_export, v -> openConfigExportPicker(),
                R.string.config_import, v -> openConfigImportPicker()), supportingParams(0));
        group.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return group;
    }

    private LinearLayout createConfigActionRow(int leftText, View.OnClickListener leftAction,
                                               int rightText, View.OnClickListener rightAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button left = createConfigButton(leftText, leftAction);
        Button right = createConfigButton(rightText, rightAction);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        leftParams.rightMargin = dp(6);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        rightParams.leftMargin = dp(6);
        row.addView(left, leftParams);
        row.addView(right, rightParams);
        return row;
    }

    private Button createConfigButton(int textRes, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setOnClickListener(action);
        return button;
    }

    private void saveConfig1() {
        try {
            ConfigManager.saveSlot1(this);
            Toast.makeText(this, R.string.config_save_success, Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, R.string.config_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadConfig1() {
        if (!ConfigManager.hasSlot1(this)) {
            Toast.makeText(this, R.string.config_slot_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.config_load_confirm_title)
                .setMessage(R.string.config_load_confirm_message)
                .setPositiveButton(R.string.config_load, (dialog, which) -> {
                    try {
                        ConfigManager.loadSlot1(this);
                        Toast.makeText(this, R.string.config_load_success, Toast.LENGTH_SHORT).show();
                        recreate();
                    } catch (Throwable error) {
                        Toast.makeText(this, R.string.config_load_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openConfigExportPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "AxonInput_Config1.json");
        startActivityForResult(intent, CONFIG_EXPORT_REQUEST);
    }

    private void openConfigImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        startActivityForResult(intent, CONFIG_IMPORT_REQUEST);
    }

    private void showEntryPasswordIfNeeded() {
        if (isFinishing()) return;
        if (OverlayState.isEntryAuthorized(this)) {
            mainHandler.post(this::ensureAccessibility);
            mainHandler.post(() -> UpdateChecker.check(this));
            return;
        }

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.password_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        int horizontal = dp(20);
        input.setPadding(horizontal, dp(8), horizontal, dp(8));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.password_title)
                .setMessage(R.string.password_message)
                .setView(input)
                .setPositiveButton(R.string.password_confirm, null)
                .setNegativeButton(R.string.password_get, null)
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = input.getText() == null ? "" : input.getText().toString();
                if ("Bacon".equals(value)) {
                    OverlayState.setEntryAuthorized(MainActivity.this, true);
                    input.setError(null);
                    dialog.dismiss();
                    mainHandler.post(MainActivity.this::ensureAccessibility);
                    mainHandler.post(() -> UpdateChecker.check(MainActivity.this));
                } else {
                    input.setError(getString(R.string.password_wrong));
                    input.selectAll();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> openPasswordSource());
            input.requestFocus();
        });
        dialog.show();
    }

    private void showAuthorDialog() {
        String[] options = new String[]{
                getString(R.string.author_github),
                getString(R.string.author_bilibili),
                getString(R.string.author_reward)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.author_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openGithubProfile();
                    } else if (which == 1) {
                        openPasswordSource();
                    } else {
                        showRewardDialog();
                    }
                })
                .show();
    }

    private void showRewardDialog() {
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.reward_code);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int padding = dp(12);
        image.setPadding(padding, padding, padding, padding);

        ScrollView container = new ScrollView(this);
        container.setFillViewport(true);
        container.addView(image, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.author_reward)
                .setView(container)
                .setPositiveButton(R.string.reward_close, null)
                .show();
    }

    private void openGithubProfile() {
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/keepBacon"));
        try {
            startActivity(browser);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.author_github_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openPasswordSource() {
        Intent bilibili = new Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://space/3546608574663321"));
        try {
            startActivity(bilibili);
            return;
        } catch (ActivityNotFoundException ignored) {
        }

        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://b23.tv/FskzSA2"));
        try {
            startActivity(browser);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.password_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openAccessibilitySettings() {
        Toast.makeText(this, R.string.accessibility_hint, Toast.LENGTH_SHORT).show();
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void updateRecordedKeys(boolean draft) {
        int[] keys = draft ? OverlayState.getCustomDraftKeyCodes(this) : OverlayState.getCustomKeyCodes(this);
        if (keys.length == 0) {
            recordedKeysText.setText(draft ? R.string.custom_recording_empty : R.string.custom_saved_empty);
            return;
        }
        StringBuilder text = new StringBuilder(draft ? getString(R.string.custom_recording_prefix) : getString(R.string.custom_saved_prefix));
        for (int key : keys) text.append(' ').append(KeyLabel.fromKeyCode(key));
        recordedKeysText.setText(text.toString());
    }

    private boolean isPhysicalKeyboardEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null || device.isVirtual()) return false;
        int sources = event.getSource();
        return (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
    }

    private void addMotionControls(LinearLayout root, int displayType, int labelRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(2));

        TextView label = createLabel();
        label.setText(labelRes);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.motion_size), getString(R.string.motion_alpha), getString(R.string.motion_none)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        motionSpinners[displayType] = spinner;
        row.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row, supportingParams(dp(2)));

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int mode = OverlayState.clampMotionMode(position);
                if (!internalChange) OverlayState.setMotionMode(MainActivity.this, displayType, mode);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateDpsTargetUi() {
        if (dpsTargetText == null) return;
        int target = OverlayState.getDpsTargetKeyCode(this);
        if (!OverlayState.isDpsEnabled(this) || target == OverlayState.DPS_TARGET_NONE) {
            dpsTargetText.setText(R.string.dps_target_waiting);
            return;
        }
        String label;
        if (target == OverlayState.DPS_TARGET_MOUSE_LEFT) {
            label = getString(R.string.cps_target_mouse_left);
        } else if (target == OverlayState.DPS_TARGET_MOUSE_RIGHT) {
            label = getString(R.string.cps_target_mouse_right);
        } else {
            label = KeyLabel.fromKeyCode(target);
        }
        dpsTargetText.setText(getString(R.string.dps_target_selected, label));
    }

    private void syncMotionUi(int displayType) {
        Spinner spinner = motionSpinners[displayType];
        if (spinner == null) return;
        spinner.setSelection(OverlayState.getMotionMode(this, displayType), false);
    }

    private void openGlobalHtmlPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/html");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "application/xhtml+xml", "text/plain"});
        startActivityForResult(intent, HTML_REQUEST_GLOBAL);
    }

    private void syncGlobalHtmlUi() {
        if (globalHtmlStatusText == null) return;
        if (OverlayState.hasGlobalHtml(this)) {
            String name = OverlayState.getGlobalHtmlName(this);
            globalHtmlStatusText.setText(getString(R.string.global_html_imported_format,
                    name == null || name.isEmpty() ? "display.html" : name));
        } else {
            globalHtmlStatusText.setText(R.string.global_html_not_imported);
        }
    }

    private String readText(Uri uri, int maxBytes) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Cannot open document");
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Document too large");
                out.write(buffer, 0, read);
            }
            if (total == 0) throw new IOException("Empty document");
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String queryDisplayName(Uri uri) {
        String name = "display.html";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isEmpty()) name = value;
                }
            }
        } catch (Throwable ignored) {}
        return name;
    }

    private View createColorDot(int color) {
        View dot = new View(this);
        updateColorDot(dot, color);
        return dot;
    }

    private void updateColorDot(View dot, int color) {
        if (dot == null) return;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(0xff000000 | (color & 0x00ffffff));
        drawable.setStroke(dp(1), UiPalette.divider(this));
        dot.setBackground(drawable);
    }

    private LinearLayout createTrajectoryColorRow(View dot, Switch toggle, boolean left) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        dotParams.rightMargin = dp(10);
        row.addView(dot, dotParams);
        toggle.setMinHeight(dp(44));
        toggle.setMinimumHeight(dp(44));
        row.addView(toggle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dot.setOnClickListener(v -> showTrajectoryColorDialog(left, dot));
        return row;
    }

    private void showTrajectoryColorDialog(boolean left, View sourceDot) {
        int current = left
                ? OverlayState.getMouseTrajectoryLeftColor(this)
                : OverlayState.getMouseTrajectoryRightColor(this);
        final int[] rgb = new int[]{Color.red(current), Color.green(current), Color.blue(current)};

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(4));

        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        View preview = createColorDot(current);
        previewRow.addView(preview, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView hex = createLabel();
        hex.setTextSize(14f);
        hex.setPadding(dp(12), 0, 0, 0);
        hex.setText(String.format(java.util.Locale.US, "#%06X", current & 0x00ffffff));
        previewRow.addView(hex, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(previewRow, supportingParams(dp(10)));

        String[] names = new String[]{"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            final int channel = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = createLabel();
            label.setText(names[i] + " " + rgb[i]);
            row.addView(label, new LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT));
            SeekBar bar = new SeekBar(this);
            bar.setMax(255);
            bar.setProgress(rgb[i]);
            row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    rgb[channel] = progress;
                    label.setText(names[channel] + " " + progress);
                    int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
                    updateColorDot(preview, color);
                    hex.setText(String.format(java.util.Locale.US, "#%06X", color & 0x00ffffff));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            content.addView(row, supportingParams(dp(3)));
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(left ? R.string.mouse_trajectory_left_color_title
                        : R.string.mouse_trajectory_right_color_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
            if (left) OverlayState.setMouseTrajectoryLeftColor(this, color);
            else OverlayState.setMouseTrajectoryRightColor(this, color);
            updateColorDot(sourceDot, color);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private static final class OpacityControl {
        final TextView label;
        final SeekBar seekBar;

        OpacityControl(TextView label, SeekBar seekBar) {
            this.label = label;
            this.seekBar = seekBar;
        }
    }

    private void addOpacityControl(LinearLayout parent, int displayType) {
        TextView label = createLabel();
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(OPACITY_MAX);
        seekBar.setPadding(0, 0, 0, 0);
        opacityControls.put(displayType, new OpacityControl(label, seekBar));
        parent.addView(label, supportingParams(dp(2)));
        parent.addView(seekBar, seekBarLayoutParams(dp(4)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                label.setText(getString(R.string.display_opacity_format, progress));
                if (fromUser && !internalChange) {
                    OverlayState.setDisplayOpacity(MainActivity.this, displayType, progress);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void syncOpacityUi(int displayType) {
        OpacityControl control = opacityControls.get(displayType);
        if (control == null) return;
        int opacity = OverlayState.getDisplayOpacity(this, displayType);
        control.seekBar.setProgress(opacity);
        control.label.setText(getString(R.string.display_opacity_format, opacity));
    }

    private TextView createTitle() {
        TextView title = new TextView(this);
        title.setTextColor(UiPalette.textPrimary(this));
        title.setTextSize(22f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setMinHeight(dp(42));
        return title;
    }

    private TextView createSectionLabel() {
        TextView label = new TextView(this);
        label.setTextColor(UiPalette.textSecondary(this));
        label.setTextSize(13f);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return label;
    }

    private Switch createSwitch(int labelRes) {
        Switch view = new Switch(this);
        view.setText(labelRes);
        view.setTextColor(UiPalette.textPrimary(this));
        view.setTextSize(16f);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(50));
        view.setMinimumHeight(dp(50));
        view.setPadding(0, 0, 0, 0);
        return view;
    }

    private TextView createLabel() {
        TextView label = new TextView(this);
        label.setTextColor(UiPalette.textSecondary(this));
        label.setTextSize(13f);
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return label;
    }

    private TextView createSupportingText() {
        TextView text = new TextView(this);
        text.setTextColor(UiPalette.textSecondary(this));
        text.setTextSize(12f);
        return text;
    }

    private LinearLayout createDetailsContainer() {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), dp(10), dp(12), dp(10));
        details.setBackground(UiPalette.rounded(this, UiPalette.debugSurface(this), 10f));
        details.setVisibility(View.GONE);

        TextView debug = createSupportingText();
        debug.setText(R.string.debug_label);
        debug.setTextSize(11f);
        debug.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        details.addView(debug, supportingParams(dp(8)));
        return details;
    }

    private LinearLayout createSwitchGroup(Switch primary) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(14), dp(2), dp(14), dp(2));
        group.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 12f));
        group.addView(primary, switchParams(0));
        return group;
    }

    private LinearLayout createFeatureGroup(Switch primary, LinearLayout details) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(14), dp(2), dp(14), dp(12));
        group.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 12f));
        group.addView(primary, switchParams(0));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(2);
        group.addView(details, detailParams);
        return group;
    }

    private Spinner createChoiceSpinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private LinearLayout createInlineChoiceRow(int labelRes, Spinner spinner) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = createLabel();
        label.setText(labelRes);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout createChoiceGroup(int labelRes, Spinner spinner) {
        LinearLayout group = createInlineChoiceRow(labelRes, spinner);
        group.setPadding(dp(14), dp(6), dp(10), dp(6));
        group.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 12f));
        return group;
    }

    private void applySystemBars() {
        boolean black = OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK;
        getWindow().setStatusBarColor(UiPalette.background(this));
        getWindow().setNavigationBarColor(UiPalette.background(this));
        int flags = 0;
        if (!black) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void setDetailsVisible(View details, boolean visible) {
        if (details == null) return;
        int target = visible ? View.VISIBLE : View.GONE;
        if (details.getVisibility() != target) details.setVisibility(target);
    }

    private void addDivider(LinearLayout root, int topMargin, int bottomMargin) {
        View divider = new View(this);
        divider.setBackgroundColor(UiPalette.divider(this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = topMargin;
        params.bottomMargin = bottomMargin;
        root.addView(divider, params);
    }

    private SeekBar createSizeSeekBar() {
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(SIZE_MAX - SIZE_MIN);
        seekBar.setPadding(0, 0, 0, 0);
        return seekBar;
    }

    private SeekBar createSensitivitySeekBar() {
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(499); // 范围 1..500%
        seekBar.setPadding(0, 0, 0, 0);
        return seekBar;
    }

    private interface SizeSetter {
        void set(int value);
    }

    private SeekBar.OnSeekBarChangeListener sizeListener(TextView label, int formatRes, SizeSetter setter) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = SIZE_MIN + progress;
                label.setText(getString(formatRes, value));
                if (fromUser && !internalChange) setter.set(value);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private SeekBar.OnSeekBarChangeListener sensitivityListener(TextView label, int formatRes, SizeSetter setter) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 1;
                label.setText(getString(formatRes, value));
                if (fromUser && !internalChange) setter.set(value);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private LinearLayout.LayoutParams switchParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams contentParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams supportingParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams seekBarLayoutParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = 0;
        params.bottomMargin = bottomMargin;
        return params;
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName component = new ComponentName(this, AxonInputAccessibilityService.class);
        String expected = component.flattenToString();
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String service : splitter) {
            if (expected.equalsIgnoreCase(service)) return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
