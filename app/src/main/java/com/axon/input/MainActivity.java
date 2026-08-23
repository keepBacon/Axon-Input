package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
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
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;

/** 应用主界面。负责设置、用户操作和权限流程。 */
public final class MainActivity extends Activity implements ShizukuBridge.Listener {
    private static final int SHIZUKU_REQUEST_CODE = 4107;
    private static final int HTML_REQUEST_GLOBAL = 6200;
    private static final int CONFIG_EXPORT_REQUEST = 6201;
    private static final int CONFIG_IMPORT_REQUEST = 6202;
    private static final int FONT_IMPORT_REQUEST = 6203;
    private static final int BONGOCAT_STYLE_IMPORT_REQUEST = 6204;
    private static final int SIZE_MIN = 50;
    private static final int SIZE_MAX = 150;
    private static final int OPACITY_MAX = 100;
    private static final int KEY_SPACING_MAX = 16;
    private static final int SENSITIVITY_FINE_MAX = 200;
    private static final int SENSITIVITY_HIGH_STEP = 5;
    private static final int SENSITIVITY_MAX = 500;
    private static final int SENSITIVITY_SEEKBAR_MAX = 259;
    private static final String KOOK_CHANNEL_URL = "https://kook.vip/GYYrsE";
    private static final int[] MOTION_DISPLAY_TYPES = {
            KeyOverlayView.DISPLAY_KEYBOARD,
            KeyOverlayView.DISPLAY_MOUSE,
            KeyOverlayView.DISPLAY_CUSTOM
    };
    private static final int[] OPACITY_DISPLAY_TYPES = {
            KeyOverlayView.DISPLAY_KEYBOARD,
            KeyOverlayView.DISPLAY_MOUSE,
            KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT,
            KeyPromptOverlayView.DISPLAY_KEY_PROMPT,
            MouseTrajectoryView.DISPLAY_TRAJECTORY,
            KeyOverlayView.DISPLAY_CUSTOM,
            GamepadOverlayView.DISPLAY_LEFT_STICK,
            GamepadOverlayView.DISPLAY_RIGHT_STICK,
            GamepadOverlayView.DISPLAY_FACE,
            GamepadOverlayView.DISPLAY_LEFT_SHOULDER,
            GamepadOverlayView.DISPLAY_RIGHT_SHOULDER,
            GamepadOverlayView.DISPLAY_BACK
    };
    private static final int[] KEY_APPEARANCE_DISPLAY_TYPES = {
            KeyOverlayView.DISPLAY_KEYBOARD,
            KeyOverlayView.DISPLAY_MOUSE,
            KeyPromptOverlayView.DISPLAY_KEY_PROMPT,
            FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD,
            KeyOverlayView.DISPLAY_CUSTOM,
            GamepadOverlayView.DISPLAY_FACE,
            GamepadOverlayView.DISPLAY_LEFT_SHOULDER,
            GamepadOverlayView.DISPLAY_RIGHT_SHOULDER,
            GamepadOverlayView.DISPLAY_BACK
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final IdentityHashMap<View, TextView> detailDisclosures = new IdentityHashMap<>();
    private final IdentityHashMap<View, View> detailDisclosureTargets = new IdentityHashMap<>();

    // Top-level settings pagination. Every section owns a dedicated ScrollView so page
    // transitions never mutate a large layout tree in the middle of an animation.
    private FrameLayout sectionPagerHost;
    private ScrollView[] sectionPageViews;
    private TextView[] sectionNavigationItems;
    private HorizontalScrollView sectionNavigationView;
    private int selectedSectionPage;
    private int displayedSectionPage;
    private int sectionPageTransitionToken;
    private float pageSwipeDownX;
    private float pageSwipeDownY;
    private boolean pageSwipeBlocked;

    private Switch displaySwitch;
    private TextView keyboardSizeLabel;
    private SeekBar keyboardSizeSeekBar;
    private TextView keyboardSpacingLabel;
    private SeekBar keyboardSpacingSeekBar;
    private Switch spaceDisplaySwitch;
    private Switch spaceDpsSwitch;
    private Switch mouseSwitch;
    private Switch keyboardCatSwitch;
    private Switch keyboardCatMouseModeSwitch;
    private Switch keyboardCatGlobalReverseSwitch;
    private Spinner keyboardCatStyleSpinner;
    private Spinner keyboardCatExpressionSpinner;
    private Button keyboardCatDeleteStyleButton;
    private final List<BongoCatStyleManager.StyleInfo> keyboardCatStyles = new ArrayList<>();
    private final List<BongoCatStyleManager.ExpressionOption> keyboardCatExpressions = new ArrayList<>();
    private LinearLayout keyboardCatDetails;
    private TextView keyboardCatSizeLabel;
    private SeekBar keyboardCatSizeSeekBar;
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
    private TextView customSpacingLabel;
    private SeekBar customSpacingSeekBar;
    private Switch captureSwitch;
    private Switch dragSwitch;
    private Switch inputFullKeyboardSwitch;
    private LinearLayout inputFullKeyboardDetails;
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
    private Switch gamepadBackSwitch;
    private LinearLayout gamepadLeftStickDetails;
    private LinearLayout gamepadRightStickDetails;
    private LinearLayout gamepadFaceDetails;
    private LinearLayout gamepadLeftShoulderDetails;
    private LinearLayout gamepadRightShoulderDetails;
    private LinearLayout gamepadBackDetails;
    private TextView gamepadLeftStickSizeLabel;
    private TextView gamepadRightStickSizeLabel;
    private TextView gamepadFaceSizeLabel;
    private TextView gamepadFaceSpacingLabel;
    private TextView gamepadLeftShoulderSizeLabel;
    private TextView gamepadRightShoulderSizeLabel;
    private TextView gamepadBackSizeLabel;
    private SeekBar gamepadLeftStickSizeSeekBar;
    private SeekBar gamepadRightStickSizeSeekBar;
    private TextView gamepadLeftStickDotSizeLabel;
    private TextView gamepadRightStickDotSizeLabel;
    private SeekBar gamepadLeftStickDotSizeSeekBar;
    private SeekBar gamepadRightStickDotSizeSeekBar;
    private SeekBar gamepadFaceSizeSeekBar;
    private SeekBar gamepadFaceSpacingSeekBar;
    private SeekBar gamepadLeftShoulderSizeSeekBar;
    private SeekBar gamepadRightShoulderSizeSeekBar;
    private SeekBar gamepadBackSizeSeekBar;
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
    private final SparseArray<Spinner> keyStyleSpinners = new SparseArray<>();
    private final SparseArray<CornerStrengthControl> keyCornerStrengthControls = new SparseArray<>();
    private final SparseArray<View> keyPressColorDots = new SparseArray<>();
    private View keyboardTextColorDot;
    private Switch globalHtmlSwitch;
    private Button globalHtmlImportButton;
    private TextView globalHtmlStatusText;
    private LinearLayout globalHtmlDetails;
    private Button fontImportButton;
    private TextView fontStatusText;
    private Spinner gamepadCompatibilitySpinner;
    private Switch gamepadSwapXYSwitch;
    private Switch gamepadSwapABSwitch;
    private Switch gamepadSwapSticksSwitch;
    private Switch gamepadSwapTriggersSwitch;
    private Switch gamepadCustomSwapSwitch;
    private TextView gamepadCustomSwapStatus;

    private boolean internalChange;
    private boolean waitingForShizuku;
    private boolean dpsCaptureArmed;
    private int gamepadCustomSwapCaptureStep;
    private int gamepadCustomSwapFirstPending;

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

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UiPalette.background(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(dp(16), dp(8), dp(16), dp(32));
        root.setBackgroundColor(UiPalette.background(this));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(4), dp(16), 0);
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
        UiMotion.bindPressFeedback(authorLink);
        header.addView(authorLink, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

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
        keyboardSpacingLabel = createLabel();
        keyboardDetails.addView(keyboardSpacingLabel, supportingParams(dp(2)));
        keyboardSpacingSeekBar = createKeySpacingSeekBar();
        keyboardDetails.addView(keyboardSpacingSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(keyboardDetails, KeyOverlayView.DISPLAY_KEYBOARD);
        addKeyAppearanceControls(keyboardDetails, KeyOverlayView.DISPLAY_KEYBOARD);
        addKeyboardTextColorControl(keyboardDetails);
        spaceDisplaySwitch = createSwitch(R.string.space_display_switch);
        spaceDisplaySwitch.setTextSize(14f);
        keyboardDetails.addView(spaceDisplaySwitch, switchParams(dp(2)));
        spaceDpsSwitch = createSwitch(R.string.space_dps_switch);
        spaceDpsSwitch.setTextSize(14f);
        keyboardDetails.addView(spaceDpsSwitch, switchParams(dp(2)));
        addMotionControls(keyboardDetails, KeyOverlayView.DISPLAY_KEYBOARD, R.string.keyboard_motion_label);
        root.addView(createFeatureGroup(displaySwitch, keyboardDetails), contentParams(dp(10)));

        inputFullKeyboardSwitch = createSwitch(R.string.input_full_keyboard_switch_label);
        inputFullKeyboardDetails = createDetailsContainer();
        addKeyAppearanceControls(inputFullKeyboardDetails, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD);
        root.addView(createFeatureGroup(inputFullKeyboardSwitch, inputFullKeyboardDetails), contentParams(dp(10)));

        mouseSwitch = createSwitch(R.string.mouse_switch_label);
        mouseDetails = createDetailsContainer();
        mouseSizeLabel = createLabel();
        mouseDetails.addView(mouseSizeLabel, supportingParams(0));
        mouseSizeSeekBar = createSizeSeekBar();
        mouseDetails.addView(mouseSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(mouseDetails, KeyOverlayView.DISPLAY_MOUSE);
        addKeyAppearanceControls(mouseDetails, KeyOverlayView.DISPLAY_MOUSE);
        addMotionControls(mouseDetails, KeyOverlayView.DISPLAY_MOUSE, R.string.mouse_motion_label);
        root.addView(createFeatureGroup(mouseSwitch, mouseDetails), contentParams(dp(10)));

        keyboardCatSwitch = createSwitch(R.string.keyboard_cat_switch_label);
        keyboardCatDetails = createDetailsContainer();
        keyboardCatSizeLabel = createLabel();
        keyboardCatDetails.addView(keyboardCatSizeLabel, supportingParams(0));
        keyboardCatSizeSeekBar = createSizeSeekBar();
        keyboardCatDetails.addView(keyboardCatSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(keyboardCatDetails, KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT);
        TextView keyboardCatStyleLabel = createLabel();
        keyboardCatStyleLabel.setText(R.string.keyboard_cat_style_label);
        keyboardCatDetails.addView(keyboardCatStyleLabel, supportingParams(dp(6)));
        keyboardCatStyleSpinner = createChoiceSpinner(new String[]{getString(R.string.keyboard_cat_style_builtin)});
        keyboardCatDetails.addView(keyboardCatStyleSpinner, seekBarLayoutParams(dp(4)));
        LinearLayout keyboardCatStyleActions = createConfigActionRow(
                R.string.keyboard_cat_style_import, v -> openKeyboardCatStylePicker(),
                R.string.keyboard_cat_style_delete, v -> confirmDeleteKeyboardCatStyle());
        keyboardCatDeleteStyleButton = (Button) keyboardCatStyleActions.getChildAt(1);
        keyboardCatDetails.addView(keyboardCatStyleActions, supportingParams(dp(6)));
        TextView keyboardCatExpressionLabel = createLabel();
        keyboardCatExpressionLabel.setText(R.string.keyboard_cat_expression_label);
        keyboardCatDetails.addView(keyboardCatExpressionLabel, supportingParams(dp(6)));
        keyboardCatExpressionSpinner = createChoiceSpinner(new String[]{getString(R.string.keyboard_cat_expression_auto)});
        keyboardCatDetails.addView(keyboardCatExpressionSpinner, seekBarLayoutParams(dp(4)));
        keyboardCatMouseModeSwitch = createSwitch(R.string.keyboard_cat_mouse_mode_switch_label);
        keyboardCatMouseModeSwitch.setTextSize(14f);
        keyboardCatDetails.addView(keyboardCatMouseModeSwitch, switchParams(dp(4)));
        keyboardCatGlobalReverseSwitch = createSwitch(R.string.keyboard_cat_global_reverse_switch_label);
        keyboardCatGlobalReverseSwitch.setTextSize(14f);
        keyboardCatDetails.addView(keyboardCatGlobalReverseSwitch, switchParams(dp(4)));
        root.addView(createFeatureGroup(keyboardCatSwitch, keyboardCatDetails), contentParams(dp(10)));

        keyPromptSwitch = createSwitch(R.string.key_prompt_switch_label);
        keyPromptDetails = createDetailsContainer();
        keyPromptSizeLabel = createLabel();
        keyPromptDetails.addView(keyPromptSizeLabel, supportingParams(0));
        keyPromptSizeSeekBar = createSizeSeekBar();
        keyPromptDetails.addView(keyPromptSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(keyPromptDetails, KeyPromptOverlayView.DISPLAY_KEY_PROMPT);
        addKeyAppearanceControls(keyPromptDetails, KeyPromptOverlayView.DISPLAY_KEY_PROMPT);
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
        customSpacingLabel = createLabel();
        customDetails.addView(customSpacingLabel, supportingParams(dp(2)));
        customSpacingSeekBar = createKeySpacingSeekBar();
        customDetails.addView(customSpacingSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(customDetails, KeyOverlayView.DISPLAY_CUSTOM);
        addKeyAppearanceControls(customDetails, KeyOverlayView.DISPLAY_CUSTOM);
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
        styleSeekBar(columnsSeekBar);
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
        gamepadFaceSpacingLabel = createLabel();
        gamepadFaceDetails.addView(gamepadFaceSpacingLabel, supportingParams(dp(2)));
        gamepadFaceSpacingSeekBar = createKeySpacingSeekBar();
        gamepadFaceDetails.addView(gamepadFaceSpacingSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(gamepadFaceDetails, GamepadOverlayView.DISPLAY_FACE);
        addKeyAppearanceControls(gamepadFaceDetails, GamepadOverlayView.DISPLAY_FACE);
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
        addKeyAppearanceControls(gamepadLeftShoulderDetails, GamepadOverlayView.DISPLAY_LEFT_SHOULDER);
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
        addKeyAppearanceControls(gamepadRightShoulderDetails, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER);
        gamepadR2ProgressSwitch = createSwitch(R.string.gamepad_r2_progress);
        gamepadR2ProgressSwitch.setTextSize(14f);
        gamepadRightShoulderDetails.addView(gamepadR2ProgressSwitch, switchParams(dp(2)));
        gamepadR1DpsSwitch = createSwitch(R.string.gamepad_r1_dps);
        gamepadR1DpsSwitch.setTextSize(14f);
        gamepadRightShoulderDetails.addView(gamepadR1DpsSwitch, switchParams(dp(2)));
        root.addView(createFeatureGroup(gamepadRightShoulderSwitch, gamepadRightShoulderDetails), contentParams(dp(10)));

        gamepadBackSwitch = createSwitch(R.string.gamepad_back_switch);
        gamepadBackDetails = createDetailsContainer();
        gamepadBackSizeLabel = createLabel();
        gamepadBackDetails.addView(gamepadBackSizeLabel, supportingParams(0));
        gamepadBackSizeSeekBar = createSizeSeekBar();
        gamepadBackDetails.addView(gamepadBackSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(gamepadBackDetails, GamepadOverlayView.DISPLAY_BACK);
        addKeyAppearanceControls(gamepadBackDetails, GamepadOverlayView.DISPLAY_BACK);
        root.addView(createFeatureGroup(gamepadBackSwitch, gamepadBackDetails), contentParams(dp(14)));

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

        Button sensitivityResetButton = new Button(this);
        sensitivityResetButton.setText(R.string.sensitivity_reset);
        sensitivityResetButton.setAllCaps(false);
        sensitivityResetButton.setTextSize(12f);
        sensitivityResetButton.setMinHeight(dp(36));
        sensitivityResetButton.setMinimumHeight(dp(40));
        styleActionButton(sensitivityResetButton);
        sensitivityDetails.addView(sensitivityResetButton, supportingParams(dp(4)));
        sensitivityResetButton.setOnClickListener(v -> {
            OverlayState.setMouseSensitivity(MainActivity.this, 100);
            OverlayState.setGamepadSensitivity(MainActivity.this, 100);
            internalChange = true;
            mouseSensitivitySeekBar.setProgress(sensitivityToProgress(100));
            gamepadSensitivitySeekBar.setProgress(sensitivityToProgress(100));
            mouseSensitivityLabel.setText(getString(R.string.mouse_sensitivity_format, 100));
            gamepadSensitivityLabel.setText(getString(R.string.gamepad_sensitivity_format, 100));
            internalChange = false;
        });

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

        LinearLayout fontGroup = new LinearLayout(this);
        fontGroup.setOrientation(LinearLayout.VERTICAL);
        fontGroup.setPadding(dp(14), dp(10), dp(14), dp(12));
        fontGroup.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 12f));
        TextView fontTitle = createLabel();
        fontTitle.setText(R.string.font_import_title);
        fontGroup.addView(fontTitle, supportingParams(dp(6)));
        fontImportButton = new Button(this);
        fontImportButton.setText(R.string.font_import_button);
        fontImportButton.setAllCaps(false);
        fontImportButton.setTextSize(13f);
        fontImportButton.setMinHeight(dp(38));
        fontImportButton.setMinimumHeight(dp(40));
        styleActionButton(fontImportButton);
        fontGroup.addView(fontImportButton, supportingParams(dp(4)));
        fontStatusText = createSupportingText();
        fontGroup.addView(fontStatusText, supportingParams(dp(2)));
        TextView fontHint = createSupportingText();
        fontHint.setText(R.string.font_import_hint);
        fontGroup.addView(fontHint, supportingParams(0));
        root.addView(fontGroup, contentParams(dp(10)));

        LinearLayout compatibilityGroup = new LinearLayout(this);
        compatibilityGroup.setOrientation(LinearLayout.VERTICAL);
        compatibilityGroup.setPadding(dp(14), dp(10), dp(14), dp(12));
        compatibilityGroup.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 12f));
        TextView compatibilityTitle = createLabel();
        compatibilityTitle.setText(R.string.gamepad_compat_title);
        compatibilityGroup.addView(compatibilityTitle, supportingParams(dp(6)));

        gamepadCompatibilitySpinner = createChoiceSpinner(new String[]{
                getString(R.string.gamepad_compat_auto),
                getString(R.string.gamepad_compat_loose),
                getString(R.string.gamepad_compat_android),
                getString(R.string.gamepad_compat_evdev)});
        compatibilityGroup.addView(createInlineChoiceRow(
                R.string.gamepad_compat_mode_label, gamepadCompatibilitySpinner), supportingParams(dp(4)));

        TextView compatibilityHint = createSupportingText();
        compatibilityHint.setText(R.string.gamepad_compat_hint);
        compatibilityGroup.addView(compatibilityHint, supportingParams(0));

        addDivider(compatibilityGroup, dp(10), dp(6));
        TextView independentCompatibilityLabel = createLabel();
        independentCompatibilityLabel.setText(R.string.gamepad_compat_independent_label);
        compatibilityGroup.addView(independentCompatibilityLabel, supportingParams(dp(2)));

        gamepadSwapXYSwitch = createSwitch(R.string.gamepad_compat_swap_xy);
        compatibilityGroup.addView(gamepadSwapXYSwitch, switchParams(0));

        gamepadSwapABSwitch = createSwitch(R.string.gamepad_compat_swap_ab);
        compatibilityGroup.addView(gamepadSwapABSwitch, switchParams(0));

        gamepadSwapSticksSwitch = createSwitch(R.string.gamepad_compat_swap_sticks);
        compatibilityGroup.addView(gamepadSwapSticksSwitch, switchParams(0));

        gamepadSwapTriggersSwitch = createSwitch(R.string.gamepad_compat_swap_triggers);
        compatibilityGroup.addView(gamepadSwapTriggersSwitch, switchParams(dp(4)));

        gamepadCustomSwapSwitch = createSwitch(R.string.gamepad_compat_custom_swap);
        compatibilityGroup.addView(gamepadCustomSwapSwitch, switchParams(0));
        gamepadCustomSwapStatus = createSupportingText();
        compatibilityGroup.addView(gamepadCustomSwapStatus, supportingParams(dp(4)));

        Button compatibilityResetButton = new Button(this);
        compatibilityResetButton.setText(R.string.gamepad_compat_reset);
        compatibilityResetButton.setAllCaps(false);
        compatibilityResetButton.setTextSize(12f);
        compatibilityResetButton.setMinHeight(dp(36));
        compatibilityResetButton.setMinimumHeight(dp(40));
        styleActionButton(compatibilityResetButton);
        compatibilityGroup.addView(compatibilityResetButton, supportingParams(0));
        root.addView(compatibilityGroup, contentParams(dp(10)));

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
        globalHtmlImportButton.setMinimumHeight(dp(40));
        styleActionButton(globalHtmlImportButton);
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
        UiMotion.bindPressFeedback(htmlGuideLink);
        root.addView(htmlGuideLink, contentParams(0));

        TextView kookJoinLink = createSupportingText();
        kookJoinLink.setText(R.string.kook_join_link);
        kookJoinLink.setTextSize(10f);
        kookJoinLink.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        kookJoinLink.setPaintFlags(kookJoinLink.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        kookJoinLink.setPadding(0, dp(8), dp(8), dp(2));
        kookJoinLink.setOnClickListener(v -> openKookChannel());
        UiMotion.bindPressFeedback(kookJoinLink);
        root.addView(kookJoinLink, contentParams(0));

        View[] sectionPages = new View[]{appearanceSection, displaySection,
                gamepadSection, sensitivitySection, behaviorSection, configurationSection};
        sectionPageViews = splitContentIntoSectionPages(root, sectionPages);
        sectionPagerHost = createSectionPagerHost(sectionPageViews);

        HorizontalScrollView sectionNav = createSectionNavigation(
                new int[]{R.string.section_appearance, R.string.section_keyboard_mouse,
                        R.string.section_gamepad, R.string.section_sensitivity,
                        R.string.section_behavior, R.string.section_configuration},
                sectionPages.length);
        sectionNavigationView = sectionNav;
        selectSectionPage(0, false);
        page.addView(sectionNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        View navDivider = new View(this);
        navDivider.setBackgroundColor(UiPalette.divider(this));
        page.addView(navDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        page.addView(sectionPagerHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);

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

        bindFeatureSwitch(displaySwitch, keyboardDetails,
                enabled -> OverlayState.setEnabled(this, enabled));

        spaceDisplaySwitch.setOnCheckedChangeListener((button, enabled) -> {
            spaceDpsSwitch.setEnabled(enabled);
            if (!internalChange) OverlayState.setKeyboardSpaceEnabled(this, enabled);
        });
        bindSimpleSwitch(spaceDpsSwitch,
                enabled -> OverlayState.setKeyboardSpaceDpsEnabled(this, enabled));

        bindFeatureSwitch(inputFullKeyboardSwitch, inputFullKeyboardDetails,
                enabled -> OverlayState.setInputFullKeyboardEnabled(this, enabled));
        bindFeatureSwitch(mouseSwitch, mouseDetails,
                enabled -> OverlayState.setMouseEnabled(this, enabled));
        bindFeatureSwitch(keyboardCatSwitch, keyboardCatDetails,
                enabled -> OverlayState.setKeyboardCatEnabled(this, enabled));
        bindSimpleSwitch(keyboardCatMouseModeSwitch,
                enabled -> OverlayState.setKeyboardCatMouseMode(this, enabled));
        bindSimpleSwitch(keyboardCatGlobalReverseSwitch,
                enabled -> OverlayState.setKeyboardCatGlobalReverse(this, enabled));
        keyboardCatStyleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange || position < 0 || position >= keyboardCatStyles.size()) return;
                BongoCatStyleManager.StyleInfo style = keyboardCatStyles.get(position);
                OverlayState.setKeyboardCatStyleId(MainActivity.this, style.id);
                OverlayState.setKeyboardCatDebugExpression(MainActivity.this, "auto");
                keyboardCatDeleteStyleButton.setEnabled(!style.builtin);
                keyboardCatMouseModeSwitch.setEnabled(style.builtin);
                syncKeyboardCatExpressionOptions(style);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        keyboardCatExpressionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange) return;
                String token = position <= 0 || position - 1 >= keyboardCatExpressions.size()
                        ? "auto" : keyboardCatExpressions.get(position - 1).token;
                OverlayState.setKeyboardCatDebugExpression(MainActivity.this, token);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        bindFeatureSwitch(keyPromptSwitch, keyPromptDetails,
                enabled -> OverlayState.setKeyPromptEnabled(this, enabled));
        bindFeatureSwitch(mouseTrajectorySwitch, mouseTrajectoryDetails,
                enabled -> OverlayState.setMouseTrajectoryEnabled(this, enabled));
        bindSimpleSwitch(mouseTrajectoryLeftColorSwitch,
                enabled -> OverlayState.setMouseTrajectoryLeftColorEnabled(this, enabled));
        bindSimpleSwitch(mouseTrajectoryRightColorSwitch,
                enabled -> OverlayState.setMouseTrajectoryRightColorEnabled(this, enabled));
        bindFeatureSwitch(customDisplaySwitch, customDetails,
                enabled -> OverlayState.setCustomEnabled(this, enabled));
        bindFeatureSwitch(gamepadLeftStickSwitch, gamepadLeftStickDetails,
                enabled -> OverlayState.setGamepadLeftStickEnabled(this, enabled));
        bindFeatureSwitch(gamepadRightStickSwitch, gamepadRightStickDetails,
                enabled -> OverlayState.setGamepadRightStickEnabled(this, enabled));
        bindFeatureSwitch(gamepadFaceSwitch, gamepadFaceDetails,
                enabled -> OverlayState.setGamepadFaceEnabled(this, enabled));
        bindFeatureSwitch(gamepadLeftShoulderSwitch, gamepadLeftShoulderDetails,
                enabled -> OverlayState.setGamepadLeftShoulderEnabled(this, enabled));
        bindFeatureSwitch(gamepadRightShoulderSwitch, gamepadRightShoulderDetails,
                enabled -> OverlayState.setGamepadRightShoulderEnabled(this, enabled));
        bindFeatureSwitch(gamepadBackSwitch, gamepadBackDetails,
                enabled -> OverlayState.setGamepadBackEnabled(this, enabled));

        keyboardSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                keyboardSizeLabel, R.string.keyboard_size_format,
                value -> OverlayState.setKeyboardSize(MainActivity.this, value)));

        keyboardSpacingSeekBar.setOnSeekBarChangeListener(spacingListener(
                keyboardSpacingLabel, R.string.keyboard_spacing_format,
                value -> OverlayState.setKeyboardSpacing(MainActivity.this, value)));

        mouseSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                mouseSizeLabel, R.string.mouse_size_format,
                value -> OverlayState.setMouseSize(MainActivity.this, value)));

        keyboardCatSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                keyboardCatSizeLabel, R.string.keyboard_cat_size_format,
                value -> OverlayState.setKeyboardCatSize(MainActivity.this, value)));

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

        customSpacingSeekBar.setOnSeekBarChangeListener(spacingListener(
                customSpacingLabel, R.string.custom_spacing_format,
                value -> OverlayState.setCustomSpacing(MainActivity.this, value)));

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

        gamepadFaceSpacingSeekBar.setOnSeekBarChangeListener(spacingListener(
                gamepadFaceSpacingLabel, R.string.gamepad_face_spacing_format,
                value -> OverlayState.setGamepadFaceSpacing(MainActivity.this, value)));

        gamepadLeftShoulderSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadLeftShoulderSizeLabel, R.string.gamepad_left_shoulder_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_LEFT_SHOULDER, value)));

        gamepadRightShoulderSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadRightShoulderSizeLabel, R.string.gamepad_right_shoulder_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER, value)));

        gamepadBackSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadBackSizeLabel, R.string.gamepad_back_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_BACK, value)));

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

        bindSimpleSwitch(gamepadFaceReverseSwitch,
                enabled -> OverlayState.setGamepadFaceReversed(this, enabled));
        bindSimpleSwitch(gamepadFaceYDpsSwitch,
                enabled -> OverlayState.setGamepadFaceYDpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadFaceXDpsSwitch,
                enabled -> OverlayState.setGamepadFaceXDpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadFaceBDpsSwitch,
                enabled -> OverlayState.setGamepadFaceBDpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadFaceADpsSwitch,
                enabled -> OverlayState.setGamepadFaceADpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadL2ProgressSwitch,
                enabled -> OverlayState.setGamepadL2ProgressEnabled(this, enabled));
        bindSimpleSwitch(gamepadR2ProgressSwitch,
                enabled -> OverlayState.setGamepadR2ProgressEnabled(this, enabled));
        bindSimpleSwitch(gamepadL1DpsSwitch,
                enabled -> OverlayState.setGamepadL1DpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadR1DpsSwitch,
                enabled -> OverlayState.setGamepadR1DpsEnabled(this, enabled));

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

        bindSimpleSwitch(dragSwitch,
                enabled -> OverlayState.setDragEnabled(this, enabled));

        globalHtmlSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            OverlayState.setGlobalHtmlEnabled(this, enabled);
            syncGlobalHtmlUi();
            if (enabled && !OverlayState.hasGlobalHtml(this)) {
                Toast.makeText(this, R.string.global_html_import_first, Toast.LENGTH_SHORT).show();
            }
        });
        globalHtmlImportButton.setOnClickListener(v -> openGlobalHtmlPicker());
        fontImportButton.setOnClickListener(v -> openFontPicker());

        gamepadCompatibilitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange) return;
                OverlayState.setGamepadCompatibilityMode(MainActivity.this, position);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        bindSimpleSwitch(gamepadSwapXYSwitch,
                enabled -> OverlayState.setGamepadSwapXY(this, enabled));
        bindSimpleSwitch(gamepadSwapABSwitch,
                enabled -> OverlayState.setGamepadSwapAB(this, enabled));
        bindSimpleSwitch(gamepadSwapSticksSwitch,
                enabled -> OverlayState.setGamepadSwapSticks(this, enabled));
        bindSimpleSwitch(gamepadSwapTriggersSwitch,
                enabled -> OverlayState.setGamepadSwapTriggers(this, enabled));
        gamepadCustomSwapSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (!enabled) {
                gamepadCustomSwapCaptureStep = 0;
                gamepadCustomSwapFirstPending = 0;
                OverlayState.setGamepadCustomSwapEnabled(MainActivity.this, false);
                updateGamepadCustomSwapUi();
                return;
            }
            // 每次开启都重新录入，避免错误映射残留。只有两个按键都录入完成后才真正生效。
            OverlayState.setGamepadCustomSwapEnabled(MainActivity.this, false);
            gamepadCustomSwapCaptureStep = 1;
            gamepadCustomSwapFirstPending = 0;
            updateGamepadCustomSwapUi();
        });
        compatibilityResetButton.setOnClickListener(v -> {
            OverlayState.resetGamepadCompatibility(MainActivity.this);
            internalChange = true;
            gamepadCompatibilitySpinner.setSelection(OverlayState.GAMEPAD_COMPAT_AUTO, false);
            gamepadSwapXYSwitch.setChecked(false);
            gamepadSwapABSwitch.setChecked(false);
            gamepadSwapSticksSwitch.setChecked(false);
            gamepadSwapTriggersSwitch.setChecked(false);
            gamepadCustomSwapSwitch.setChecked(false);
            gamepadCustomSwapCaptureStep = 0;
            gamepadCustomSwapFirstPending = 0;
            internalChange = false;
            updateGamepadCustomSwapUi();
            Toast.makeText(MainActivity.this, R.string.gamepad_compat_reset_done, Toast.LENGTH_SHORT).show();
        });

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
        if (gamepadCustomSwapCaptureStep != 0 && event != null && event.getRepeatCount() == 0) {
            int gamepadBit = GamepadButtons.fromAndroidEvent(event);
            if (gamepadBit != 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (gamepadCustomSwapCaptureStep == 1) {
                        gamepadCustomSwapFirstPending = gamepadBit;
                        gamepadCustomSwapCaptureStep = 2;
                        updateGamepadCustomSwapUi();
                    } else if (gamepadBit == gamepadCustomSwapFirstPending) {
                        Toast.makeText(this, R.string.gamepad_compat_custom_swap_same, Toast.LENGTH_SHORT).show();
                    } else {
                        OverlayState.setGamepadCustomSwapPair(this, gamepadCustomSwapFirstPending, gamepadBit);
                        gamepadCustomSwapCaptureStep = 0;
                        gamepadCustomSwapFirstPending = 0;
                        internalChange = true;
                        gamepadCustomSwapSwitch.setChecked(true);
                        internalChange = false;
                        updateGamepadCustomSwapUi();
                    }
                }
                // 录入期间吞掉 DOWN/UP，避免按键顺带触发界面导航。
                return true;
            }
        }
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

    static void openExternalUrl(Activity activity, String url) {
        if (activity == null || url == null || url.trim().isEmpty()) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())));
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(activity, R.string.link_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    static void openKookUrl(Activity activity, String url) {
        if (activity == null || url == null || url.trim().isEmpty()) return;
        Uri uri = Uri.parse(url.trim());
        Intent kookIntent = new Intent(Intent.ACTION_VIEW, uri);
        kookIntent.setPackage("cn.kaiheila");
        try {
            activity.startActivity(kookIntent);
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // 未安装 KOOK 时使用浏览器。
        }
        openExternalUrl(activity, url);
    }

    private void openKookChannel() {
        openKookUrl(this, KOOK_CHANNEL_URL);
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
        mainHandler.removeCallbacksAndMessages(null);
        // Activity 销毁不清理运行配置，任务移除时由服务统一处理。
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
        inputFullKeyboardSwitch.setChecked(OverlayState.isInputFullKeyboardEnabled(this));
        mouseSwitch.setChecked(OverlayState.isMouseEnabled(this));
        keyboardCatSwitch.setChecked(OverlayState.isKeyboardCatEnabled(this));
        keyboardCatMouseModeSwitch.setChecked(OverlayState.isKeyboardCatMouseMode(this));
        keyboardCatGlobalReverseSwitch.setChecked(OverlayState.isKeyboardCatGlobalReverse(this));
        syncKeyboardCatStyles();
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
        gamepadBackSwitch.setChecked(OverlayState.isGamepadBackEnabled(this));
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
        gamepadCompatibilitySpinner.setSelection(OverlayState.getGamepadCompatibilityMode(this), false);
        gamepadSwapXYSwitch.setChecked(OverlayState.isGamepadSwapXY(this));
        gamepadSwapABSwitch.setChecked(OverlayState.isGamepadSwapAB(this));
        gamepadSwapSticksSwitch.setChecked(OverlayState.isGamepadSwapSticks(this));
        gamepadSwapTriggersSwitch.setChecked(OverlayState.isGamepadSwapTriggers(this));
        gamepadCustomSwapSwitch.setChecked(OverlayState.isGamepadCustomSwapEnabled(this));
        updateGamepadCustomSwapUi();
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

        syncSizeControl(keyboardSizeSeekBar, keyboardSizeLabel,
                OverlayState.getKeyboardSize(this), R.string.keyboard_size_format);
        syncSpacingControl(keyboardSpacingSeekBar, keyboardSpacingLabel,
                OverlayState.getKeyboardSpacing(this), R.string.keyboard_spacing_format);
        syncSizeControl(mouseSizeSeekBar, mouseSizeLabel,
                OverlayState.getMouseSize(this), R.string.mouse_size_format);
        syncSizeControl(keyboardCatSizeSeekBar, keyboardCatSizeLabel,
                OverlayState.getKeyboardCatSize(this), R.string.keyboard_cat_size_format);
        syncSizeControl(keyPromptSizeSeekBar, keyPromptSizeLabel,
                OverlayState.getKeyPromptSize(this), R.string.key_prompt_size_format);
        syncSizeControl(mouseTrajectorySizeSeekBar, mouseTrajectorySizeLabel,
                OverlayState.getMouseTrajectorySize(this), R.string.mouse_trajectory_size_format);
        syncSizeControl(mouseTrajectoryDotSizeSeekBar, mouseTrajectoryDotSizeLabel,
                OverlayState.getMouseTrajectoryDotSize(this), R.string.mouse_trajectory_dot_size_format);
        syncSizeControl(customSizeSeekBar, customSizeLabel,
                OverlayState.getCustomSize(this), R.string.custom_size_format);
        syncSpacingControl(customSpacingSeekBar, customSpacingLabel,
                OverlayState.getCustomSpacing(this), R.string.custom_spacing_format);
        syncSizeControl(gamepadLeftStickSizeSeekBar, gamepadLeftStickSizeLabel,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_LEFT_STICK),
                R.string.gamepad_left_stick_size_format);
        syncSizeControl(gamepadRightStickSizeSeekBar, gamepadRightStickSizeLabel,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_RIGHT_STICK),
                R.string.gamepad_right_stick_size_format);
        syncSizeControl(gamepadLeftStickDotSizeSeekBar, gamepadLeftStickDotSizeLabel,
                OverlayState.getGamepadStickDotSize(this, GamepadOverlayView.DISPLAY_LEFT_STICK),
                R.string.gamepad_left_stick_dot_size_format);
        syncSizeControl(gamepadRightStickDotSizeSeekBar, gamepadRightStickDotSizeLabel,
                OverlayState.getGamepadStickDotSize(this, GamepadOverlayView.DISPLAY_RIGHT_STICK),
                R.string.gamepad_right_stick_dot_size_format);
        syncSizeControl(gamepadFaceSizeSeekBar, gamepadFaceSizeLabel,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_FACE),
                R.string.gamepad_face_size_format);
        syncSpacingControl(gamepadFaceSpacingSeekBar, gamepadFaceSpacingLabel,
                OverlayState.getGamepadFaceSpacing(this), R.string.gamepad_face_spacing_format);
        syncSizeControl(gamepadLeftShoulderSizeSeekBar, gamepadLeftShoulderSizeLabel,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_LEFT_SHOULDER),
                R.string.gamepad_left_shoulder_size_format);
        syncSizeControl(gamepadRightShoulderSizeSeekBar, gamepadRightShoulderSizeLabel,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER),
                R.string.gamepad_right_shoulder_size_format);
        syncSizeControl(gamepadBackSizeSeekBar, gamepadBackSizeLabel,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_BACK),
                R.string.gamepad_back_size_format);

        int mouseSensitivity = OverlayState.getMouseSensitivity(this);
        syncValueControl(mouseSensitivitySeekBar, mouseSensitivityLabel,
                sensitivityToProgress(mouseSensitivity), mouseSensitivity, R.string.mouse_sensitivity_format);
        int gamepadSensitivity = OverlayState.getGamepadSensitivity(this);
        syncValueControl(gamepadSensitivitySeekBar, gamepadSensitivityLabel,
                sensitivityToProgress(gamepadSensitivity), gamepadSensitivity, R.string.gamepad_sensitivity_format);
        sensitivityStatusText.setText(getString(
                R.string.sensitivity_status_format, OverlayState.getSensitivityStatus(this)));

        int columns = OverlayState.getCustomColumns(this);
        columnsSeekBar.setProgress(columns - 1);
        columnsLabel.setText(getString(R.string.columns_format, columns));
        updateRecordedKeys(OverlayState.isCustomCaptureEnabled(this));
        for (int type : MOTION_DISPLAY_TYPES) syncMotionUi(type);
        for (int type : OPACITY_DISPLAY_TYPES) syncOpacityUi(type);
        for (int type : KEY_APPEARANCE_DISPLAY_TYPES) syncKeyAppearanceUi(type);
        syncKeyboardTextColorUi();
        syncGlobalHtmlUi();
        syncFontUi();
        internalChange = false;
        mainHandler.removeCallbacks(sensitivityStatusTicker);
        if (sensitivitySwitch.isChecked()) mainHandler.post(sensitivityStatusTicker);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == BONGOCAT_STYLE_IMPORT_REQUEST) {
            try {
                BongoCatStyleManager.StyleInfo imported = BongoCatStyleManager.importZip(
                        this, uri, queryDisplayName(uri, "BongoCat-style.zip"));
                OverlayState.setKeyboardCatStyleId(this, imported.id);
                internalChange = true;
                syncKeyboardCatStyles();
                internalChange = false;
                Toast.makeText(this, getString(R.string.keyboard_cat_style_import_success, imported.displayLabel()), Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                internalChange = false;
                Toast.makeText(this, getString(R.string.keyboard_cat_style_import_failed, error.getMessage() == null ? "" : error.getMessage()), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == FONT_IMPORT_REQUEST) {
            try {
                FontManager.importFont(this, uri, queryDisplayName(uri, "font.ttf"));
                syncFontUi();
                AxonInputAccessibilityService.refreshTheme();
                Toast.makeText(this, R.string.font_import_success, Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                Toast.makeText(this, R.string.font_import_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == HTML_REQUEST_GLOBAL) {
            try {
                String html = readText(uri, OverlayState.MAX_GLOBAL_HTML_BYTES);
                String name = queryDisplayName(uri, "display.html");
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
                ConfigManager.importInto(this, readText(uri, ConfigManager.MAX_CONFIG_BYTES));
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
        // 键盘猫需要读取全局鼠标 REL_X / REL_Y。即使无障碍服务已经开启，
        // 也必须保证当前选择的 Shizuku / Root 输入通道可用。
        boolean keyboardCatMouseCapture = OverlayState.isKeyboardCatEnabled(this) && !sensitivity;
        boolean rootMode = (sensitivity || keyboardCatMouseCapture)
                && OverlayState.getSensitivityMode(this) == OverlayState.SENSITIVITY_MODE_ROOT;

        if (isAccessibilityServiceEnabled()) {
            AxonInputAccessibilityService.refreshActiveService();
            if ((sensitivity || keyboardCatMouseCapture) && !rootMode) ensureShizukuForSensitivity();
            return;
        }

        // Root 倍率模式不依赖 Shizuku。
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
        styleActionButton(button);
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
            mainHandler.post(this::checkCloudNoticeThenContinue);
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
                    mainHandler.post(MainActivity.this::checkCloudNoticeThenContinue);
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

    private void checkCloudNoticeThenContinue() {
        if (isFinishing()) return;
        CloudNoticeChecker.check(this, this::continueAfterEntry);
    }

    private void continueAfterEntry() {
        mainHandler.post(this::ensureAccessibility);
        mainHandler.post(() -> UpdateChecker.check(this));
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

        Spinner spinner = createChoiceSpinner(new String[]{
                getString(R.string.motion_size), getString(R.string.motion_alpha),
                getString(R.string.motion_ripple), getString(R.string.motion_none)});
        motionSpinners[displayType] = spinner;
        row.addView(spinner, new LinearLayout.LayoutParams(
                Math.min(dp(156), Math.round(getResources().getDisplayMetrics().widthPixels * 0.46f)),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row, supportingParams(dp(2)));

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int mode;
                if (position == 1) mode = OverlayState.MOTION_ALPHA;
                else if (position == 2) mode = OverlayState.MOTION_RIPPLE;
                else if (position == 3) mode = OverlayState.MOTION_NONE;
                else mode = OverlayState.MOTION_SIZE;
                if (!internalChange) OverlayState.setMotionMode(MainActivity.this, displayType, mode);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateGamepadCustomSwapUi() {
        if (gamepadCustomSwapStatus == null || gamepadCustomSwapSwitch == null) return;
        if (gamepadCustomSwapCaptureStep == 1) {
            gamepadCustomSwapStatus.setText(R.string.gamepad_compat_custom_swap_first);
            return;
        }
        if (gamepadCustomSwapCaptureStep == 2) {
            gamepadCustomSwapStatus.setText(getString(
                    R.string.gamepad_compat_custom_swap_second,
                    gamepadButtonLabel(gamepadCustomSwapFirstPending)));
            return;
        }
        int first = OverlayState.getGamepadCustomSwapFirst(this);
        int second = OverlayState.getGamepadCustomSwapSecond(this);
        if (OverlayState.isGamepadCustomSwapEnabled(this) && first != 0 && second != 0) {
            gamepadCustomSwapStatus.setText(getString(
                    R.string.gamepad_compat_custom_swap_active,
                    gamepadButtonLabel(first), gamepadButtonLabel(second)));
        } else {
            gamepadCustomSwapStatus.setText(R.string.gamepad_compat_custom_swap_empty);
        }
    }

    private String gamepadButtonLabel(int bit) {
        return switch (bit) {
            case GamepadOverlayView.BTN_SOUTH -> "A";
            case GamepadOverlayView.BTN_EAST -> "B";
            case GamepadOverlayView.BTN_WEST, GamepadOverlayView.BTN_C -> "X";
            case GamepadOverlayView.BTN_NORTH -> "Y";
            case GamepadOverlayView.BTN_Z -> "Z";
            case GamepadOverlayView.BTN_L1 -> "L1";
            case GamepadOverlayView.BTN_R1 -> "R1";
            case GamepadOverlayView.BTN_L2 -> "L2";
            case GamepadOverlayView.BTN_R2 -> "R2";
            case GamepadOverlayView.BTN_L3 -> "L3";
            case GamepadOverlayView.BTN_R3 -> "R3";
            case GamepadOverlayView.BTN_SELECT -> "Select";
            case GamepadOverlayView.BTN_START -> "Start";
            case GamepadOverlayView.BTN_MODE -> "Mode";
            case GamepadOverlayView.BTN_BACK_1 -> "P1/M1";
            case GamepadOverlayView.BTN_BACK_2 -> "P2/M2";
            case GamepadOverlayView.BTN_BACK_3 -> "P3/M3";
            case GamepadOverlayView.BTN_BACK_4 -> "P4/M4";
            default -> "Button";
        };
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
        } else if (OverlayState.isGamepadDpsTarget(target)) {
            label = gamepadCpsLabel(OverlayState.getGamepadDpsTargetBit(target));
        } else {
            label = KeyLabel.fromKeyCode(target);
        }
        dpsTargetText.setText(getString(R.string.dps_target_selected, label));
    }

    private String gamepadCpsLabel(int bit) {
        int resId = switch (bit) {
            case GamepadOverlayView.BTN_SOUTH -> R.string.cps_target_gamepad_a;
            case GamepadOverlayView.BTN_EAST -> R.string.cps_target_gamepad_b;
            case GamepadOverlayView.BTN_WEST -> R.string.cps_target_gamepad_x;
            case GamepadOverlayView.BTN_NORTH -> R.string.cps_target_gamepad_y;
            case GamepadOverlayView.BTN_L1 -> R.string.cps_target_gamepad_l1;
            case GamepadOverlayView.BTN_R1 -> R.string.cps_target_gamepad_r1;
            case GamepadOverlayView.BTN_L2 -> R.string.cps_target_gamepad_l2;
            case GamepadOverlayView.BTN_R2 -> R.string.cps_target_gamepad_r2;
            case GamepadOverlayView.BTN_L3 -> R.string.cps_target_gamepad_l3;
            case GamepadOverlayView.BTN_R3 -> R.string.cps_target_gamepad_r3;
            case 1 << 10 -> R.string.cps_target_gamepad_select;
            case 1 << 11 -> R.string.cps_target_gamepad_start;
            case 1 << 12 -> R.string.cps_target_gamepad_mode;
            default -> R.string.cps_target_gamepad_button;
        };
        return getString(resId);
    }

    private void syncMotionUi(int displayType) {
        Spinner spinner = motionSpinners[displayType];
        if (spinner == null) return;
        int mode = OverlayState.getMotionMode(this, displayType);
        int position = mode == OverlayState.MOTION_ALPHA ? 1
                : mode == OverlayState.MOTION_RIPPLE ? 2
                : mode == OverlayState.MOTION_NONE ? 3 : 0;
        spinner.setSelection(position, false);
    }

    private void openKeyboardCatStylePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, BONGOCAT_STYLE_IMPORT_REQUEST);
    }

    private void syncKeyboardCatStyles() {
        if (keyboardCatStyleSpinner == null) return;
        keyboardCatStyles.clear();
        keyboardCatStyles.addAll(BongoCatStyleManager.list(this));
        String[] labels = new String[keyboardCatStyles.size()];
        int selected = 0;
        String selectedId = OverlayState.getKeyboardCatStyleId(this);
        for (int i = 0; i < keyboardCatStyles.size(); i++) {
            BongoCatStyleManager.StyleInfo info = keyboardCatStyles.get(i);
            labels[i] = info.displayLabel();
            if (selectedId.equals(info.id)) selected = i;
        }
        if (selected >= keyboardCatStyles.size()) selected = 0;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return createSpinnerText(getItem(position), false);
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return createSpinnerText(getItem(position), true);
            }
        };
        keyboardCatStyleSpinner.setAdapter(adapter);
        keyboardCatStyleSpinner.setSelection(selected, false);
        BongoCatStyleManager.StyleInfo selectedInfo = keyboardCatStyles.get(selected);
        keyboardCatDeleteStyleButton.setEnabled(!selectedInfo.builtin);
        keyboardCatMouseModeSwitch.setEnabled(selectedInfo.builtin);
        if (!selectedInfo.id.equals(selectedId)) OverlayState.setKeyboardCatStyleId(this, selectedInfo.id);
        syncKeyboardCatExpressionOptions(selectedInfo);
    }

    private void syncKeyboardCatExpressionOptions(BongoCatStyleManager.StyleInfo style) {
        if (keyboardCatExpressionSpinner == null) return;
        keyboardCatExpressions.clear();
        if (style != null && !style.builtin) {
            keyboardCatExpressions.addAll(BongoCatStyleManager.expressionOptions(this, style.id));
        }
        String[] labels = new String[keyboardCatExpressions.size() + 1];
        labels[0] = getString(R.string.keyboard_cat_expression_auto);
        for (int i = 0; i < keyboardCatExpressions.size(); i++) labels[i + 1] = keyboardCatExpressions.get(i).label;

        String selectedToken = OverlayState.getKeyboardCatDebugExpression(this);
        int selected = 0;
        for (int i = 0; i < keyboardCatExpressions.size(); i++) {
            if (selectedToken.equals(keyboardCatExpressions.get(i).token)) { selected = i + 1; break; }
        }
        boolean valid = "auto".equals(selectedToken) || selected > 0;
        boolean previousInternal = internalChange;
        internalChange = true;
        try {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels) {
                @Override public View getView(int position, View convertView, ViewGroup parent) {
                    return createSpinnerText(getItem(position), false);
                }
                @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                    return createSpinnerText(getItem(position), true);
                }
            };
            keyboardCatExpressionSpinner.setAdapter(adapter);
            keyboardCatExpressionSpinner.setSelection(selected, false);
            keyboardCatExpressionSpinner.setEnabled(!keyboardCatExpressions.isEmpty());
        } finally {
            internalChange = previousInternal;
        }
        if (!valid) OverlayState.setKeyboardCatDebugExpression(this, "auto");
    }

    private void confirmDeleteKeyboardCatStyle() {
        int position = keyboardCatStyleSpinner == null ? -1 : keyboardCatStyleSpinner.getSelectedItemPosition();
        if (position < 0 || position >= keyboardCatStyles.size()) return;
        BongoCatStyleManager.StyleInfo style = keyboardCatStyles.get(position);
        if (style.builtin) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.keyboard_cat_style_delete)
                .setMessage(getString(R.string.keyboard_cat_style_delete_confirm, style.displayLabel()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.keyboard_cat_style_delete, (dialog, which) -> {
                    BongoCatStyleManager.delete(MainActivity.this, style.id);
                    OverlayState.setKeyboardCatStyleId(MainActivity.this, BongoCatStyleManager.BUILTIN_ID);
                    internalChange = true;
                    syncKeyboardCatStyles();
                    internalChange = false;
                    Toast.makeText(MainActivity.this, R.string.keyboard_cat_style_delete_success, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void openFontPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "font/ttf", "font/otf", "application/x-font-ttf",
                "application/x-font-opentype", "application/octet-stream"});
        startActivityForResult(intent, FONT_IMPORT_REQUEST);
    }

    private void syncFontUi() {
        if (fontStatusText == null) return;
        if (!FontManager.hasImportedFont(this)) {
            fontStatusText.setText(R.string.font_import_default);
            return;
        }
        String name = FontManager.getImportedFontName(this);
        if (name == null || name.isEmpty()) name = getString(R.string.font_custom_name);
        fontStatusText.setText(getString(R.string.font_imported_format, name));
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

    private String queryDisplayName(Uri uri, String fallback) {
        String name = fallback == null || fallback.isEmpty() ? "file" : fallback;
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

        toggle.setMinHeight(dp(44));
        toggle.setMinimumHeight(dp(44));
        row.addView(toggle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout colorTarget = new LinearLayout(this);
        colorTarget.setGravity(Gravity.CENTER);
        colorTarget.setContentDescription(getString(left
                ? R.string.mouse_trajectory_left_color_title
                : R.string.mouse_trajectory_right_color_title));
        colorTarget.setBackground(createRippleBackground(UiPalette.debugSurface(this), 9f));
        colorTarget.addView(dot, new LinearLayout.LayoutParams(dp(22), dp(22)));
        colorTarget.setOnClickListener(v -> showTrajectoryColorDialog(left, dot));
        UiMotion.bindPressFeedback(colorTarget);

        LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        colorParams.leftMargin = dp(8);
        row.addView(colorTarget, colorParams);
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
            styleSeekBar(bar);
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

    private static final class CornerStrengthControl {
        final TextView label;
        final SeekBar seekBar;

        CornerStrengthControl(TextView label, SeekBar seekBar) {
            this.label = label;
            this.seekBar = seekBar;
        }
    }

    private void addKeyAppearanceControls(LinearLayout parent, int displayType) {
        Spinner styleSpinner = createChoiceSpinner(new String[]{
                getString(R.string.key_style_rounded),
                getString(R.string.key_style_square),
                getString(R.string.key_style_circle)});
        keyStyleSpinners.put(displayType, styleSpinner);
        parent.addView(createInlineChoiceRow(R.string.key_style_label, styleSpinner), supportingParams(dp(4)));

        TextView cornerLabel = createLabel();
        SeekBar cornerSeekBar = new SeekBar(this);
        cornerSeekBar.setMax(100);
        cornerSeekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(cornerSeekBar);
        keyCornerStrengthControls.put(displayType, new CornerStrengthControl(cornerLabel, cornerSeekBar));
        parent.addView(cornerLabel, supportingParams(dp(2)));
        parent.addView(cornerSeekBar, seekBarLayoutParams(dp(4)));

        cornerSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                cornerLabel.setText(getString(R.string.key_corner_strength_format, progress));
                if (fromUser && !internalChange && seekBar.isEnabled()) {
                    OverlayState.setKeyCornerStrength(MainActivity.this, displayType, progress);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        styleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View view, int position, long id) {
                updateCornerStrengthEnabled(displayType, position == KeyAppearance.STYLE_ROUNDED);
                if (!internalChange) OverlayState.setKeyStyle(MainActivity.this, displayType, position);
            }

            @Override public void onNothingSelected(AdapterView<?> parentView) {}
        });

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        colorRow.setMinimumHeight(dp(44));
        TextView colorLabel = createLabel();
        colorLabel.setText(R.string.key_press_color);
        colorRow.addView(colorLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View dot = createColorDot(OverlayState.getKeyPressColor(this, displayType));
        keyPressColorDots.put(displayType, dot);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        dotParams.leftMargin = dp(12);
        colorRow.addView(dot, dotParams);
        View.OnClickListener openColor = v -> showKeyPressColorDialog(displayType, dot);
        colorRow.setOnClickListener(openColor);
        colorRow.setBackground(createRippleBackground(UiPalette.debugSurface(this), 8f));
        UiMotion.bindPressFeedback(colorRow);
        dot.setOnClickListener(openColor);
        parent.addView(colorRow, supportingParams(dp(2)));
    }

    private void updateCornerStrengthEnabled(int displayType, boolean enabled) {
        CornerStrengthControl control = keyCornerStrengthControls.get(displayType);
        if (control == null) return;
        control.seekBar.setEnabled(enabled);
        control.seekBar.setAlpha(enabled ? 1f : 0.38f);
        control.label.setAlpha(enabled ? 1f : 0.46f);
    }

    private void addKeyboardTextColorControl(LinearLayout parent) {
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        colorRow.setMinimumHeight(dp(44));

        TextView colorLabel = createLabel();
        colorLabel.setText(R.string.key_text_color);
        colorRow.addView(colorLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        keyboardTextColorDot = createColorDot(OverlayState.getKeyboardTextColor(this));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        dotParams.leftMargin = dp(12);
        colorRow.addView(keyboardTextColorDot, dotParams);

        View.OnClickListener openColor = v -> showKeyboardTextColorDialog(keyboardTextColorDot);
        colorRow.setOnClickListener(openColor);
        colorRow.setBackground(createRippleBackground(UiPalette.debugSurface(this), 8f));
        UiMotion.bindPressFeedback(colorRow);
        keyboardTextColorDot.setOnClickListener(openColor);
        parent.addView(colorRow, supportingParams(dp(2)));
    }

    private void syncKeyboardTextColorUi() {
        if (keyboardTextColorDot != null) {
            updateColorDot(keyboardTextColorDot, OverlayState.getKeyboardTextColor(this));
        }
    }

    private void showKeyboardTextColorDialog(View sourceDot) {
        showRgbColorDialog(
                R.string.key_text_color_title,
                OverlayState.getKeyboardTextColor(this),
                sourceDot,
                color -> OverlayState.setKeyboardTextColor(MainActivity.this, color));
    }

    private void syncKeyAppearanceUi(int displayType) {
        int style = OverlayState.getKeyStyle(this, displayType);
        Spinner spinner = keyStyleSpinners.get(displayType);
        if (spinner != null) spinner.setSelection(style, false);

        CornerStrengthControl corner = keyCornerStrengthControls.get(displayType);
        if (corner != null) {
            int strength = OverlayState.getKeyCornerStrength(this, displayType);
            corner.seekBar.setProgress(strength);
            corner.label.setText(getString(R.string.key_corner_strength_format, strength));
            updateCornerStrengthEnabled(displayType, style == KeyAppearance.STYLE_ROUNDED);
        }

        View dot = keyPressColorDots.get(displayType);
        if (dot != null) updateColorDot(dot, OverlayState.getKeyPressColor(this, displayType));
    }

    private interface ColorCommit {
        void apply(int color);
    }

    private void showKeyPressColorDialog(int displayType, View sourceDot) {
        showRgbColorDialog(
                R.string.key_press_color_title,
                OverlayState.getKeyPressColor(this, displayType),
                sourceDot,
                color -> OverlayState.setKeyPressColor(MainActivity.this, displayType, color));
    }

    private void showRgbColorDialog(int titleRes, int current, View sourceDot, ColorCommit commit) {
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
            styleSeekBar(bar);
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
                .setTitle(titleRes)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
            commit.apply(color);
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
        styleSeekBar(seekBar);
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
        title.setTextSize(24f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setMinHeight(dp(44));
        return title;
    }

    private TextView createSectionLabel() {
        TextView label = new TextView(this);
        label.setTextColor(UiPalette.textTertiary(this));
        label.setTextSize(12f);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        label.setMinHeight(dp(28));
        label.setPadding(dp(2), 0, 0, 0);
        return label;
    }

    private Switch createSwitch(int labelRes) {
        Switch view = new Switch(this);
        view.setText(labelRes);
        view.setTextColor(UiPalette.textPrimary(this));
        view.setTextSize(15f);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(52));
        view.setMinimumHeight(dp(52));
        view.setPadding(0, 0, 0, 0);

        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        view.setTrackTintList(new ColorStateList(states, new int[]{
                UiPalette.switchTrackOn(this), UiPalette.switchTrackOff(this)}));
        view.setThumbTintList(new ColorStateList(states, new int[]{
                UiPalette.switchThumbOn(this), UiPalette.switchThumbOff(this)}));
        return view;
    }

    private TextView createLabel() {
        TextView label = new TextView(this);
        label.setTextColor(UiPalette.textSecondary(this));
        label.setTextSize(13f);
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        label.setMinHeight(dp(24));
        return label;
    }

    private TextView createSupportingText() {
        TextView text = new TextView(this);
        text.setTextColor(UiPalette.textSecondary(this));
        text.setTextSize(12f);
        text.setLineSpacing(0f, 1.08f);
        return text;
    }

    private LinearLayout createDetailsContainer() {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(12), dp(12), dp(12), dp(10));
        details.setBackground(UiPalette.rounded(this, UiPalette.debugSurface(this), 10f));
        details.setVisibility(View.GONE);
        return details;
    }

    private LinearLayout createSwitchGroup(Switch primary) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(14), dp(3), dp(14), dp(3));
        group.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 12f));
        group.addView(primary, switchParams(0));
        return group;
    }

    private LinearLayout createFeatureGroup(Switch primary, LinearLayout details) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(14), dp(3), dp(10), dp(10));
        group.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 12f));
        UiMotion.enableLayoutMotion(group);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(primary, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout disclosureTarget = new LinearLayout(this);
        disclosureTarget.setOrientation(LinearLayout.HORIZONTAL);
        disclosureTarget.setGravity(Gravity.CENTER);
        disclosureTarget.setPadding(dp(9), 0, dp(7), 0);
        disclosureTarget.setMinimumHeight(dp(40));
        disclosureTarget.setContentDescription(getString(R.string.details_expand));
        disclosureTarget.setBackground(createRippleBackground(UiPalette.surface(this), 9f));

        TextView settingsLabel = new TextView(this);
        settingsLabel.setText(R.string.details_action);
        settingsLabel.setTextColor(UiPalette.textTertiary(this));
        settingsLabel.setTextSize(11f);
        settingsLabel.setGravity(Gravity.CENTER);
        disclosureTarget.addView(settingsLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView disclosure = new TextView(this);
        disclosure.setText("⌄");
        disclosure.setTextColor(UiPalette.textTertiary(this));
        disclosure.setTextSize(16f);
        disclosure.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(18), dp(36));
        arrowParams.leftMargin = dp(2);
        disclosureTarget.addView(disclosure, arrowParams);

        LinearLayout.LayoutParams disclosureParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        disclosureParams.leftMargin = dp(4);
        header.addView(disclosureTarget, disclosureParams);
        group.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(4);
        detailParams.rightMargin = dp(4);
        group.addView(details, detailParams);

        detailDisclosures.put(details, disclosure);
        detailDisclosureTargets.put(details, disclosureTarget);
        disclosureTarget.setOnClickListener(v -> setDetailsExpanded(
                details, details.getVisibility() != View.VISIBLE, true));
        UiMotion.bindPressFeedback(disclosureTarget);
        return group;
    }

    private Spinner createChoiceSpinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, values) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return createSpinnerText(getItem(position), false);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return createSpinnerText(getItem(position), true);
            }
        };
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(40));
        spinner.setPadding(0, 0, 0, 0);
        spinner.setBackground(createRippleBackground(UiPalette.controlSurface(this), 9f));
        spinner.setPopupBackgroundDrawable(UiPalette.rounded(this, UiPalette.surfaceRaised(this), 12f));
        spinner.setDropDownVerticalOffset(dp(4));
        return spinner;
    }

    private TextView createSpinnerText(String value, boolean dropdown) {
        TextView text = new TextView(this);
        text.setText(value == null ? "" : value);
        text.setTextColor(UiPalette.textPrimary(this));
        text.setTextSize(13f);
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMinHeight(dp(dropdown ? 44 : 40));
        text.setPadding(dp(12), 0, dp(12), 0);
        return text;
    }

    private LinearLayout createInlineChoiceRow(int labelRes, Spinner spinner) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        TextView label = createLabel();
        label.setText(labelRes);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.rightMargin = dp(12);
        row.addView(label, labelParams);
        int maxSpinnerWidth = Math.min(dp(188), Math.round(getResources().getDisplayMetrics().widthPixels * 0.54f));
        row.addView(spinner, new LinearLayout.LayoutParams(
                maxSpinnerWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout createChoiceGroup(int labelRes, Spinner spinner) {
        LinearLayout group = createInlineChoiceRow(labelRes, spinner);
        group.setPadding(dp(14), dp(5), dp(10), dp(5));
        group.setBackground(UiPalette.rounded(this, UiPalette.surface(this), 12f));
        return group;
    }

    private void styleActionButton(Button button) {
        button.setTextColor(UiPalette.textPrimary(this));
        button.setTextSize(13f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setBackground(createRippleBackground(UiPalette.controlSurface(this), 10f));
        UiMotion.bindPressFeedback(button);
    }

    private RippleDrawable createRippleBackground(int fillColor, float radiusDp) {
        GradientDrawable content = UiPalette.rounded(this, fillColor, radiusDp);
        GradientDrawable mask = UiPalette.rounded(this, Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(UiPalette.ripple(this)), content, mask);
    }

    private void styleSeekBar(SeekBar seekBar) {
        seekBar.setProgressTintList(ColorStateList.valueOf(UiPalette.accent(this)));
        seekBar.setThumbTintList(ColorStateList.valueOf(UiPalette.accent(this)));
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(UiPalette.divider(this)));
        seekBar.setMinimumHeight(dp(32));
    }

    private HorizontalScrollView createSectionNavigation(int[] labelResIds, int pageCount) {
        HorizontalScrollView nav = new HorizontalScrollView(this);
        nav.setHorizontalScrollBarEnabled(false);
        nav.setFillViewport(false);
        nav.setOverScrollMode(View.OVER_SCROLL_NEVER);
        nav.setBackgroundColor(UiPalette.background(this));

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setGravity(Gravity.CENTER_VERTICAL);
        rail.setPadding(dp(10), 0, dp(10), dp(4));

        int count = Math.min(labelResIds.length, pageCount);
        TextView[] items = new TextView[count];
        for (int i = 0; i < count; i++) {
            TextView item = new TextView(this);
            items[i] = item;
            item.setText(labelResIds[i]);
            item.setTextSize(12f);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setMinHeight(dp(38));
            item.setPadding(dp(11), 0, dp(11), 0);
            final int pageIndex = i;
            item.setOnClickListener(v -> selectSectionPage(pageIndex, true));
            UiMotion.bindPressFeedback(item);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            if (i > 0) params.leftMargin = dp(2);
            rail.addView(item, params);
        }
        sectionNavigationItems = items;
        updateSectionNavigationSelection(items, 0);
        nav.addView(rail, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return nav;
    }

    private void selectSectionPage(int requestedIndex, boolean centerNavigation) {
        if (sectionPageViews == null || sectionPageViews.length == 0) return;

        int target = Math.max(0, Math.min(requestedIndex, sectionPageViews.length - 1));
        selectedSectionPage = target;

        if (sectionNavigationItems != null) {
            updateSectionNavigationSelection(sectionNavigationItems, target);
        }

        if (centerNavigation && sectionNavigationView != null
                && sectionNavigationItems != null && target < sectionNavigationItems.length) {
            TextView item = sectionNavigationItems[target];
            sectionNavigationView.post(() -> {
                int targetX = Math.max(0, item.getLeft()
                        - (sectionNavigationView.getWidth() - item.getWidth()) / 2);
                // Do not run a second long scroller beside the page transition. A direct
                // nav correction keeps the content animation on the critical frame path.
                sectionNavigationView.scrollTo(targetX, 0);
            });
        }

        // Initial setup is immediate. All pages already exist; only one is visible.
        if (!centerNavigation) {
            sectionPageTransitionToken++;
            displayedSectionPage = target;
            normalizeSectionPages(target);
            return;
        }

        if (target == displayedSectionPage) return;

        // Interruptions settle instantly onto the latest destination before starting the
        // next transition. This prevents half-translated/half-transparent page states.
        int from = displayedSectionPage;
        int direction = target > from ? 1 : -1;
        int transitionToken = ++sectionPageTransitionToken;
        UiMotion.cancelPageTransition(sectionPageViews);
        normalizeSectionPages(from);

        ScrollView outgoing = sectionPageViews[from];
        ScrollView incoming = sectionPageViews[target];
        incoming.stopNestedScroll();
        incoming.scrollTo(0, 0);
        incoming.post(() -> incoming.scrollTo(0, 0));
        incoming.setVisibility(View.VISIBLE);
        incoming.bringToFront();

        // Treat the destination as current as soon as motion starts. If another page is
        // requested mid-flight, the new gesture continues from this destination cleanly.
        displayedSectionPage = target;
        UiMotion.animatePageTransition(outgoing, incoming, direction, () -> {
            if (transitionToken != sectionPageTransitionToken) return;
            normalizeSectionPages(target);
        });
    }

    /**
     * Converts the original linear settings list into independent page trees once during
     * Activity creation. No child is reparented while a page transition is running.
     */
    private ScrollView[] splitContentIntoSectionPages(
            LinearLayout source, View[] sectionStarts) {
        int pageCount = sectionStarts == null ? 0 : sectionStarts.length;
        ScrollView[] pages = new ScrollView[pageCount];

        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.TOP);
            content.setPadding(dp(16), dp(8), dp(16), dp(32));
            content.setBackgroundColor(UiPalette.background(this));

            int start = source.indexOfChild(sectionStarts[pageIndex]);
            int end = pageIndex + 1 < pageCount
                    ? source.indexOfChild(sectionStarts[pageIndex + 1])
                    : source.getChildCount();
            if (start < 0) start = 0;
            if (end < start) end = source.getChildCount();

            int moveCount = end - start;
            for (int i = 0; i < moveCount; i++) {
                View child = source.getChildAt(start);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                source.removeViewAt(start);
                content.addView(child, params);
            }

            ScrollView page = new ScrollView(this);
            page.setFillViewport(true);
            page.setClipToPadding(false);
            page.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            page.setBackgroundColor(UiPalette.background(this));
            page.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            pages[pageIndex] = page;
        }
        return pages;
    }

    private FrameLayout createSectionPagerHost(ScrollView[] pages) {
        FrameLayout host = new FrameLayout(this);
        host.setClipChildren(true);
        host.setClipToPadding(true);
        host.setBackgroundColor(UiPalette.background(this));
        if (pages != null) {
            for (ScrollView page : pages) {
                page.setVisibility(View.INVISIBLE);
                host.addView(page, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        return host;
    }

    private void normalizeSectionPages(int visibleIndex) {
        if (sectionPageViews == null) return;
        int safe = Math.max(0, Math.min(visibleIndex, sectionPageViews.length - 1));
        for (int i = 0; i < sectionPageViews.length; i++) {
            ScrollView page = sectionPageViews[i];
            page.animate().cancel();
            page.setTranslationX(0f);
            page.setAlpha(1f);
            page.setLayerType(View.LAYER_TYPE_NONE, null);
            page.setVisibility(i == safe ? View.VISIBLE : View.INVISIBLE);
        }
        sectionPageViews[safe].bringToFront();
    }

    private void updateSectionNavigationSelection(TextView[] items, int selectedIndex) {
        for (int i = 0; i < items.length; i++) {
            TextView item = items[i];
            boolean selected = i == selectedIndex;
            item.setSelected(selected);
            item.setTextColor(selected
                    ? UiPalette.textPrimary(this)
                    : UiPalette.textSecondary(this));
            item.setBackground(createRippleBackground(
                    selected ? UiPalette.surface(this) : UiPalette.background(this), 8f));
        }
    }

    /**
     * Observe horizontal gestures without consuming the touch stream. Vertical scrolling and
     * child controls keep their native behaviour; a completed horizontal swipe only changes
     * the top-level settings page after the child has received ACTION_UP.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pageSwipeDownX = event.getRawX();
            pageSwipeDownY = event.getRawY();
            pageSwipeBlocked = shouldBlockPageSwipe(pageSwipeDownX, pageSwipeDownY);
            return super.dispatchTouchEvent(event);
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            pageSwipeBlocked = false;
            return super.dispatchTouchEvent(event);
        }

        if (action == MotionEvent.ACTION_UP) {
            float deltaX = event.getRawX() - pageSwipeDownX;
            float deltaY = event.getRawY() - pageSwipeDownY;
            float absX = Math.abs(deltaX);
            float absY = Math.abs(deltaY);
            boolean switchPage = !pageSwipeBlocked
                    && sectionPageViews != null
                    && absX >= dp(64)
                    && absX > absY * 1.35f;

            // Let the current child finish its gesture before changing page visibility.
            boolean handled = super.dispatchTouchEvent(event);
            if (switchPage) {
                int nextPage = selectedSectionPage + (deltaX < 0f ? 1 : -1);
                if (nextPage >= 0 && nextPage < sectionPageViews.length) {
                    selectSectionPage(nextPage, true);
                }
            }
            pageSwipeBlocked = false;
            return handled;
        }

        return super.dispatchTouchEvent(event);
    }

    private boolean shouldBlockPageSwipe(float rawX, float rawY) {
        if (isPointInsideView(sectionNavigationView, rawX, rawY)) return true;

        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        View touched = findDeepestViewAt(decor, rawX, rawY);
        View current = touched;
        while (current != null) {
            if (current instanceof SeekBar
                    || current instanceof Switch
                    || current instanceof Spinner
                    || current instanceof EditText
                    || current instanceof HorizontalScrollView) {
                return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private View findDeepestViewAt(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE
                || !isPointInsideView(view, rawX, rawY)) {
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View hit = findDeepestViewAt(group.getChildAt(i), rawX, rawY);
                if (hit != null) return hit;
            }
        }
        return view;
    }

    private boolean isPointInsideView(View view, float rawX, float rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX < location[0] + view.getWidth()
                && rawY >= location[1] && rawY < location[1] + view.getHeight();
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

    private void bindFeatureSwitch(Switch toggle, View details, BooleanSetter setter) {
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            setter.set(enabled);
            handleDisplayModeChanged();
        });
    }

    private void bindSimpleSwitch(Switch toggle, BooleanSetter setter) {
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) setter.set(enabled);
        });
    }

    private void syncSizeControl(SeekBar seekBar, TextView label, int value, int formatRes) {
        syncValueControl(seekBar, label, value - SIZE_MIN, value, formatRes);
    }

    private void syncSpacingControl(SeekBar seekBar, TextView label, int value, int formatRes) {
        syncValueControl(seekBar, label, value, value, formatRes);
    }

    private void syncValueControl(
            SeekBar seekBar, TextView label, int progress, int value, int formatRes) {
        seekBar.setProgress(progress);
        label.setText(getString(formatRes, value));
    }

    private void setDetailsVisible(View details, boolean visible) {
        setDetailsExpanded(details, visible, false);
    }

    private void setDetailsExpanded(View details, boolean visible, boolean animated) {
        if (details == null) return;
        TextView disclosure = detailDisclosures.get(details);
        View disclosureTarget = detailDisclosureTargets.get(details);
        UiMotion.setDetailsVisible(details, visible, animated);
        UiMotion.rotateDisclosure(disclosure, visible, animated);
        if (disclosureTarget != null) {
            disclosureTarget.setContentDescription(getString(
                    visible ? R.string.details_collapse : R.string.details_expand));
        }
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
        styleSeekBar(seekBar);
        return seekBar;
    }

    private SeekBar createKeySpacingSeekBar() {
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(KEY_SPACING_MAX);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        return seekBar;
    }

    private SeekBar createSensitivitySeekBar() {
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(SENSITIVITY_SEEKBAR_MAX);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        return seekBar;
    }

    private int sensitivityFromProgress(int progress) {
        int p = Math.max(0, Math.min(SENSITIVITY_SEEKBAR_MAX, progress));
        if (p < SENSITIVITY_FINE_MAX) return p + 1;
        return Math.min(SENSITIVITY_MAX,
                SENSITIVITY_FINE_MAX + (p - (SENSITIVITY_FINE_MAX - 1)) * SENSITIVITY_HIGH_STEP);
    }

    private int sensitivityToProgress(int value) {
        int v = Math.max(1, Math.min(SENSITIVITY_MAX, value));
        if (v <= SENSITIVITY_FINE_MAX) return v - 1;
        return (SENSITIVITY_FINE_MAX - 1)
                + Math.round((v - SENSITIVITY_FINE_MAX) / (float) SENSITIVITY_HIGH_STEP);
    }

    private interface IntSetter {
        void set(int value);
    }

    private interface BooleanSetter {
        void set(boolean value);
    }

    private SeekBar.OnSeekBarChangeListener sizeListener(TextView label, int formatRes, IntSetter setter) {
        return boundedListener(label, formatRes, SIZE_MIN, SIZE_MAX, setter);
    }

    private SeekBar.OnSeekBarChangeListener spacingListener(TextView label, int formatRes, IntSetter setter) {
        return boundedListener(label, formatRes, 0, KEY_SPACING_MAX, setter);
    }

    private SeekBar.OnSeekBarChangeListener boundedListener(
            TextView label, int formatRes, int min, int max, IntSetter setter) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Math.max(min, Math.min(max, min + progress));
                label.setText(getString(formatRes, value));
                if (fromUser && !internalChange) setter.set(value);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private SeekBar.OnSeekBarChangeListener sensitivityListener(TextView label, int formatRes, IntSetter setter) {
        return new SeekBar.OnSeekBarChangeListener() {
            private static final long APPLY_INTERVAL_MS = 40L;
            private int pendingValue = 100;
            private int lastAppliedValue = Integer.MIN_VALUE;
            private long lastApplyAt;

            private final Runnable flush = () -> {
                if (internalChange || pendingValue == lastAppliedValue) return;
                setter.set(pendingValue);
                lastAppliedValue = pendingValue;
                lastApplyAt = SystemClock.uptimeMillis();
            };

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = sensitivityFromProgress(progress);
                label.setText(getString(formatRes, value));
                if (!fromUser || internalChange) return;
                pendingValue = value;
                long now = SystemClock.uptimeMillis();
                long wait = APPLY_INTERVAL_MS - (now - lastApplyAt);
                mainHandler.removeCallbacks(flush);
                if (wait <= 0L) flush.run();
                else mainHandler.postDelayed(flush, wait);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mainHandler.removeCallbacks(flush);
                flush.run();
            }
        };
    }

    private LinearLayout.LayoutParams switchParams(int bottomMargin) {
        return fullWidthParams(bottomMargin);
    }

    private LinearLayout.LayoutParams contentParams(int bottomMargin) {
        return fullWidthParams(bottomMargin);
    }

    private LinearLayout.LayoutParams supportingParams(int bottomMargin) {
        return fullWidthParams(bottomMargin);
    }

    private LinearLayout.LayoutParams seekBarLayoutParams(int bottomMargin) {
        return fullWidthParams(bottomMargin);
    }

    private LinearLayout.LayoutParams fullWidthParams(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
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
