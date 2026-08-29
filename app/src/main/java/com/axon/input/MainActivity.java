package com.axon.input;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
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
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 应用主界面。负责设置、用户操作和权限流程。 */
public final class MainActivity extends Activity implements ShizukuBridge.Listener {
    private static final int SHIZUKU_REQUEST_CODE = 4107;
    private static final int HTML_REQUEST_GLOBAL = 6200;
    private static final int CONFIG_EXPORT_REQUEST = 6201;
    private static final int CONFIG_IMPORT_REQUEST = 6202;
    private static final int FONT_IMPORT_REQUEST = 6203;
    private static final int BONGOCAT_STYLE_IMPORT_REQUEST = 6204;
    private static final int SUPER_CUSTOM_IMPORT_REQUEST = 6205;
    private static final int LIVE2D_MODEL_IMPORT_REQUEST = 6206;
    private static final int LIVE2D_MOTION_CAMERA_PERMISSION_REQUEST = 6208;
    private static final int SIZE_MIN = 25;
    private static final int SIZE_MAX = 300;
    private static final int LIVE2D_SIZE_MIN = 25;
    private static final int LIVE2D_SIZE_MAX = 400;
    private static final int OPACITY_MAX = 100;
    private static final int KEY_SPACING_MAX = 40;
    private static final int SENSITIVITY_FINE_MAX = 200;
    private static final int SENSITIVITY_HIGH_STEP = 5;
    private static final int SENSITIVITY_MAX = 1000;
    private static final int SENSITIVITY_SEEKBAR_MAX = 359;
    private static final long CUSTOM_MAPPING_CAPTURE_WINDOW_MS = 5000L;
    private static final int CUSTOM_MAPPING_DELAY_STEP_MS = 5;
    // 功能保留，仅隐藏设置入口；改为 true 可恢复显示。
    private static final boolean SHOW_CUSTOM_MAPPING_UI = false;
    private static final String KOOK_CHANNEL_URL = "https://kook.vip/GYYrsE";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final IdentityHashMap<View, TextView> detailDisclosures = new IdentityHashMap<>();
    private final IdentityHashMap<View, View> detailDisclosureTargets = new IdentityHashMap<>();

    // Top-level settings pagination. Every section owns a dedicated ScrollView so page
    // transitions never mutate a large layout tree in the middle of an animation.
    private FrameLayout sectionPagerHost;
    private ScrollView[] sectionPageViews;
    private SectionNavigationItem[] sectionNavigationItems;
    private SectionNavigationBar sectionNavigationView;
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
    private Switch spaceDashSwitch;
    private Switch keyboardMouseButtonsSwitch;
    private Switch keyboardMouseCpsSwitch;
    private Switch mouseSwitch;
    private Switch keyboardCatSwitch;
    private Switch keyboardCatMouseModeSwitch;
    private Switch keyboardCatGlobalReverseSwitch;
    private Spinner keyboardCatStyleSpinner;
    private Spinner keyboardCatQualitySpinner;
    private Spinner keyboardCatExpressionSpinner;
    private Button keyboardCatExpressionHotkeyButton;
    private Button keyboardCatExpressionHotkeySelectionButton;
    private Button keyboardCatModelFunctionsButton;
    private Button keyboardCatDeleteStyleButton;
    private boolean keyboardCatFunctionBindingCaptureArmed;
    private String keyboardCatFunctionBindingToken = "";
    private String keyboardCatFunctionBindingMode = KeyboardCatFunctionBindingStore.MODE_TOGGLE;
    private String keyboardCatFunctionBindingLabel = "";
    private String keyboardCatFunctionBindingStyleId = BongoCatStyleManager.BUILTIN_ID;
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
    private Switch superCustomDisplaySwitch;
    private final TextView[] superCustomSlotStatusViews = new TextView[SuperCustomConfigStore.SLOT_COUNT];
    private int pendingSuperCustomImportSlot;
    private TextView customSizeLabel;
    private SeekBar customSizeSeekBar;
    private TextView customSpacingLabel;
    private SeekBar customSpacingSeekBar;
    private Switch captureSwitch;
    private Switch dragSwitch;
    private Switch touchDisplaySwitch;
    private LinearLayout touchDisplayDetails;
    private TextView touchDisplaySizeLabel;
    private SeekBar touchDisplaySizeSeekBar;
    private TextView touchDisplaySpacingLabel;
    private SeekBar touchDisplaySpacingSeekBar;
    private Button touchDisplayEditRegionsButton;
    private Button touchDisplayRetryButton;
    private TextView touchDisplayStatusText;
    private Switch customMappingSwitch;
    private LinearLayout customMappingDetails;
    private TextView customMappingStatusText;
    private TextView customMappingDelayLabel;
    private SeekBar customMappingDelaySeekBar;
    private Button customMappingConfigButton;
    private LinearLayout customMappingList;
    private int customMappingCaptureStep;
    private int customMappingTriggerPending = -1;
    private long customMappingCaptureDeadline;
    private final ArrayList<CustomMappingStore.Output> customMappingPendingOutputs = new ArrayList<>();
    private final Runnable customMappingCaptureTimeout = () -> finishCustomMappingCapture(true);
    private final Runnable customMappingCaptureTicker = new Runnable() {
        @Override public void run() {
            if (customMappingCaptureStep != 2) return;
            if (SystemClock.uptimeMillis() >= customMappingCaptureDeadline) {
                finishCustomMappingCapture(true);
                return;
            }
            updateCustomMappingCaptureStatusOnly();
            mainHandler.postDelayed(this, 100L);
        }
    };
    private Switch simultaneousClickSwitch;
    private LinearLayout simultaneousClickDetails;
    private TextView simultaneousClickStatusText;
    private Button simultaneousClickConfigButton;
    private LinearLayout simultaneousClickList;
    private int simultaneousClickCaptureStep;
    private int simultaneousClickSourcePending = -1;
    private Switch forceHoldSwitch;
    private LinearLayout forceHoldDetails;
    private TextView forceHoldStatusText;
    private int forceHoldCaptureStep;
    private int forceHoldTargetKeyPending = -1;
    private int forceHoldTargetScanPending = -1;
    private boolean keyboardCatExpressionHotkeyCaptureArmed;
    private int keyboardCatExpressionHotkeyPreviousKeyCode = -1;
    private boolean hideDisplayHotkeyCaptureArmed;
    private int hideDisplayHotkeyPreviousInputCode = -1;
    private Switch hideDisplayHotkeySwitch;
    private LinearLayout hideDisplayHotkeyDetails;
    private Button hideDisplayHotkeyButton;
    private Button hideDisplayTargetSelectionButton;

    private Button gamepadMappingAddButton;
    private LinearLayout gamepadMappingList;
    private TextView gamepadMappingStatusText;
    private boolean gamepadMappingCaptureArmed;
    private int pendingGamepadMappingSource = -1;
    private AlertDialog gamepadMappingKeyboardDialog;
    private int keyboardCatExpressionHotkeyCapturedKeyCode = -1;
    private boolean physicsHotkeyCaptureArmed;
    private String physicsHotkeyTarget = "";
    private String physicsHotkeyGroupKey = "";
    private String physicsHotkeyGroupLabel = "";
    private static volatile MainActivity activeBindingActivity;

    public static boolean isAppForeground() {
        MainActivity activity = activeBindingActivity;
        return activity != null && activity.activityResumed && !activity.isFinishing();
    }

    static void notifyInAppLive2DPhysicsChanged() {
        MainActivity activity = activeBindingActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (activity.inAppLive2dView != null) activity.inAppLive2dView.refreshPhysicsControls();
            activity.syncLive2DPhysicsUi(Live2DModelStore.exists(activity));
        });
    }

    /** Called by the connected AccessibilityService after its persistent Live2D overlay attaches. */
    static void onExternalLive2DRendererReady() {
        MainActivity activity = activeBindingActivity;
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            if (AxonInputAccessibilityService.isServiceConnected()) {
                activity.removeInAppLive2D();
            }
        });
    }
    private int bindableMouseButtonsDown;
    private long bindableMouseLastEventTime = -1L;
    private int bindableMouseLastActionButton;
    private int lastBindableMouseInputCode = -1;
    private long lastBindableMouseInputAt = -1L;
    private int bindableGamepadKeyButtonsDown;
    private int bindableGamepadMotionButtonsDown;
    private int lastBindableGamepadInputCode = -1;
    private long lastBindableGamepadInputAt = -1L;
    private Switch inputFullKeyboardSwitch;
    private LinearLayout inputFullKeyboardDetails;
    private TextView inputFullKeyboardSizeLabel;
    private SeekBar inputFullKeyboardSizeSeekBar;
    private Switch dpsSwitch;
    private LinearLayout dpsDetails;
    private TextView dpsTargetText;
    private TextView dpsSizeLabel;
    private SeekBar dpsSizeSeekBar;
    private View dpsTextColorDot;
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
    private Switch gamepadDpadSwitch;
    private Switch gamepadLeftShoulderSwitch;
    private Switch gamepadRightShoulderSwitch;
    private Switch gamepadBackSwitch;
    private LinearLayout gamepadLeftStickDetails;
    private LinearLayout gamepadRightStickDetails;
    private LinearLayout gamepadFaceDetails;
    private LinearLayout gamepadDpadDetails;
    private LinearLayout gamepadLeftShoulderDetails;
    private LinearLayout gamepadRightShoulderDetails;
    private LinearLayout gamepadBackDetails;
    private TextView gamepadLeftStickSizeLabel;
    private TextView gamepadRightStickSizeLabel;
    private TextView gamepadFaceSizeLabel;
    private TextView gamepadDpadSizeLabel;
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
    private TextView gamepadLeftStickCenterCornerLabel;
    private TextView gamepadRightStickCenterCornerLabel;
    private SeekBar gamepadLeftStickCenterCornerSeekBar;
    private SeekBar gamepadRightStickCenterCornerSeekBar;
    private View gamepadLeftStickCenterColorDot;
    private View gamepadRightStickCenterColorDot;
    private SeekBar gamepadFaceSizeSeekBar;
    private SeekBar gamepadDpadSizeSeekBar;
    private SeekBar gamepadFaceSpacingSeekBar;
    private SeekBar gamepadLeftShoulderSizeSeekBar;
    private SeekBar gamepadRightShoulderSizeSeekBar;
    private SeekBar gamepadBackSizeSeekBar;
    private Switch gamepadFaceReverseSwitch;
    private Switch gamepadFaceSymbolSwitch;
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
    private final SparseArray<Spinner> motionSpinners = new SparseArray<>();
    private final SparseArray<OpacityControl> opacityControls = new SparseArray<>();
    private final SparseArray<KeyLayerOpacityControl> keyLayerOpacityControls = new SparseArray<>();
    private final SparseArray<CornerStrengthControl> keyCornerStrengthControls = new SparseArray<>();
    private final SparseArray<View> keyBaseColorDots = new SparseArray<>();
    private final SparseArray<View> keyBorderColorDots = new SparseArray<>();
    private final SparseArray<View> keyPressColorDots = new SparseArray<>();
    private final SparseArray<View> keyTextColorDots = new SparseArray<>();
    private Switch globalHtmlSwitch;
    private Spinner globalHtmlChoiceSpinner;
    private Spinner globalHtmlFontSpinner;
    private final List<GlobalHtmlStore.HtmlInfo> globalHtmlChoices = new ArrayList<>();
    private Button globalHtmlImportButton;
    private TextView globalHtmlStatusText;
    private LinearLayout globalHtmlDetails;
    private Switch live2dSwitch;
    private LinearLayout live2dDetails;
    private TextView live2dSizeLabel;
    private SeekBar live2dSizeSeekBar;
    private Spinner live2dQualitySpinner;
    private TextView live2dPhysicsStrengthLabel;
    private SeekBar live2dPhysicsStrengthSeekBar;
    private Button live2dPhysicsGroupsButton;
    private Button live2dExpressionDebugButton;
    private Button live2dParameterDebugButton;
    private Switch live2dMotionTrackingSwitch;
    private Switch live2dMouseCaptureSwitch;
    private Switch live2dHideWatermarkSwitch;
    private Button live2dImportButton;
    private FrameLayout activityRoot;
    private Live2DOverlayView inAppLive2dView;
    private String inAppLive2dVersion = "";
    private boolean activityResumed;
    private Switch fontSwitch;
    private Spinner fontChoiceSpinner;
    private final List<FontManager.FontInfo> importedFontChoices = new ArrayList<>();
    private LinearLayout fontDetails;
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
    private boolean shizukuPermissionRequestInFlight;
    private boolean accessibilityGrantInFlight;
    private boolean accessibilityVerificationInFlight;
    private int accessibilityVerificationGeneration;
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
                    SensitivitySettingsStore.getStatus(MainActivity.this)));
            if (sensitivitySwitch != null && sensitivitySwitch.isChecked() && !isFinishing()) {
                mainHandler.postDelayed(this, 500L);
            }
        }
    };

    private final Runnable touchDisplayStatusTicker = new Runnable() {
        @Override
        public void run() {
            updateTouchDisplayStatusUi();
            if (touchDisplaySwitch != null && touchDisplaySwitch.isChecked() && !isFinishing()) {
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
        root.setPadding(dp(18), dp(8), dp(18), dp(28));
        root.setBackgroundColor(UiPalette.background(this));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(54));
        header.setPadding(dp(18), dp(3), dp(18), dp(3));
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        addKeyAppearanceControls(keyboardDetails, KeyOverlayView.DISPLAY_KEYBOARD);
        addKeyLayerOpacityControls(keyboardDetails, KeyOverlayView.DISPLAY_KEYBOARD);
        addMotionControls(keyboardDetails, KeyOverlayView.DISPLAY_KEYBOARD, R.string.keyboard_motion_label);
        spaceDisplaySwitch = createSwitch(R.string.space_display_switch);
        spaceDisplaySwitch.setTextSize(14f);
        keyboardDetails.addView(spaceDisplaySwitch, switchParams(dp(2)));
        spaceDpsSwitch = createSwitch(R.string.space_dps_switch);
        spaceDpsSwitch.setTextSize(14f);
        keyboardDetails.addView(spaceDpsSwitch, switchParams(dp(2)));
        spaceDashSwitch = createSwitch(R.string.space_dash_switch);
        spaceDashSwitch.setTextSize(14f);
        keyboardDetails.addView(spaceDashSwitch, switchParams(dp(2)));
        keyboardMouseButtonsSwitch = createSwitch(R.string.keyboard_mouse_buttons_display_switch);
        keyboardMouseButtonsSwitch.setTextSize(14f);
        keyboardDetails.addView(keyboardMouseButtonsSwitch, switchParams(dp(2)));
        keyboardMouseCpsSwitch = createSwitch(R.string.keyboard_mouse_cps_display_switch);
        keyboardMouseCpsSwitch.setTextSize(14f);
        keyboardDetails.addView(keyboardMouseCpsSwitch, switchParams(dp(2)));
        root.addView(createFeatureGroup(displaySwitch, keyboardDetails), contentParams(dp(10)));

        inputFullKeyboardSwitch = createSwitch(R.string.input_full_keyboard_switch_label);
        inputFullKeyboardDetails = createDetailsContainer();
        inputFullKeyboardSizeLabel = createLabel();
        inputFullKeyboardDetails.addView(inputFullKeyboardSizeLabel, supportingParams(0));
        inputFullKeyboardSizeSeekBar = createSizeSeekBar();
        inputFullKeyboardDetails.addView(inputFullKeyboardSizeSeekBar, seekBarLayoutParams(dp(4)));
        addKeyAppearanceControls(inputFullKeyboardDetails, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD);
        addOpacityControl(inputFullKeyboardDetails, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD);
        addKeyLayerOpacityControls(inputFullKeyboardDetails, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD);
        addMotionControls(inputFullKeyboardDetails, FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD, R.string.motion_choice_label);
        root.addView(createFeatureGroup(inputFullKeyboardSwitch, inputFullKeyboardDetails), contentParams(dp(10)));

        mouseSwitch = createSwitch(R.string.mouse_switch_label);
        mouseDetails = createDetailsContainer();
        mouseSizeLabel = createLabel();
        mouseDetails.addView(mouseSizeLabel, supportingParams(0));
        mouseSizeSeekBar = createSizeSeekBar();
        mouseDetails.addView(mouseSizeSeekBar, seekBarLayoutParams(dp(4)));
        addKeyAppearanceControls(mouseDetails, KeyOverlayView.DISPLAY_MOUSE);
        addKeyLayerOpacityControls(mouseDetails, KeyOverlayView.DISPLAY_MOUSE);
        addMotionControls(mouseDetails, KeyOverlayView.DISPLAY_MOUSE, R.string.mouse_motion_label);
        root.addView(createFeatureGroup(mouseSwitch, mouseDetails), contentParams(dp(10)));

        keyboardCatSwitch = createSwitch(R.string.keyboard_cat_switch_label);
        keyboardCatDetails = createDetailsContainer();
        keyboardCatSizeLabel = createLabel();
        keyboardCatDetails.addView(keyboardCatSizeLabel, supportingParams(0));
        keyboardCatSizeSeekBar = createSizeSeekBar();
        keyboardCatDetails.addView(keyboardCatSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(keyboardCatDetails, KeyboardCatOverlayView.DISPLAY_KEYBOARD_CAT);
        keyboardCatQualitySpinner = createChoiceSpinner(new String[]{
                getString(R.string.render_quality_normal), getString(R.string.render_quality_clear)});
        keyboardCatDetails.addView(createInlineChoiceRow(
                R.string.render_quality_label, keyboardCatQualitySpinner), supportingParams(dp(6)));
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

        TextView keyboardCatExpressionHotkeyLabel = createLabel();
        keyboardCatExpressionHotkeyLabel.setText(R.string.keyboard_cat_expression_hotkey_label);
        keyboardCatDetails.addView(keyboardCatExpressionHotkeyLabel, supportingParams(dp(6)));
        keyboardCatExpressionHotkeyButton = createConfigButton(
                R.string.keyboard_cat_expression_hotkey_unbound,
                v -> toggleKeyboardCatExpressionHotkeyCapture());
        keyboardCatDetails.addView(keyboardCatExpressionHotkeyButton,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TextView keyboardCatExpressionHotkeySelectionLabel = createLabel();
        keyboardCatExpressionHotkeySelectionLabel.setText(R.string.keyboard_cat_expression_hotkey_selection_label);
        keyboardCatDetails.addView(keyboardCatExpressionHotkeySelectionLabel, supportingParams(dp(6)));
        keyboardCatExpressionHotkeySelectionButton = createConfigButton(
                R.string.keyboard_cat_expression_hotkey_selection_empty,
                v -> showKeyboardCatExpressionHotkeySelectionDialog());
        keyboardCatDetails.addView(keyboardCatExpressionHotkeySelectionButton,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TextView keyboardCatModelFunctionsLabel = createLabel();
        keyboardCatModelFunctionsLabel.setText(R.string.keyboard_cat_model_functions_label);
        keyboardCatDetails.addView(keyboardCatModelFunctionsLabel, supportingParams(dp(6)));
        keyboardCatModelFunctionsButton = createConfigButton(
                R.string.keyboard_cat_model_functions_button, v -> showKeyboardCatModelFunctionsDialog());
        keyboardCatDetails.addView(keyboardCatModelFunctionsButton,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

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
        addKeyAppearanceControls(keyPromptDetails, KeyPromptOverlayView.DISPLAY_KEY_PROMPT);
        addKeyLayerOpacityControls(keyPromptDetails, KeyPromptOverlayView.DISPLAY_KEY_PROMPT);
        addMotionControls(keyPromptDetails, KeyPromptOverlayView.DISPLAY_KEY_PROMPT, R.string.motion_choice_label);
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
        updateColorDotSequence(mouseTrajectoryLeftColorDot, OverlayState.getMouseTrajectoryLeftColors(this));
        mouseTrajectoryDetails.addView(
                createTrajectoryColorRow(mouseTrajectoryLeftColorDot, mouseTrajectoryLeftColorSwitch, true),
                supportingParams(dp(2)));
        mouseTrajectoryRightColorSwitch = createSwitch(R.string.mouse_trajectory_right_color);
        mouseTrajectoryRightColorSwitch.setTextSize(14f);
        mouseTrajectoryRightColorDot = createColorDot(OverlayState.getMouseTrajectoryRightColor(this));
        updateColorDotSequence(mouseTrajectoryRightColorDot, OverlayState.getMouseTrajectoryRightColors(this));
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
        addKeyAppearanceControls(customDetails, KeyOverlayView.DISPLAY_CUSTOM);
        addKeyLayerOpacityControls(customDetails, KeyOverlayView.DISPLAY_CUSTOM);
        addMotionControls(customDetails, KeyOverlayView.DISPLAY_CUSTOM, R.string.custom_motion_label);

        captureSwitch = createSwitch(R.string.custom_capture_label);
        captureSwitch.setTextSize(15f);
        customDetails.addView(captureSwitch, switchParams(0));

        recordedKeysText = createSupportingText();
        customDetails.addView(recordedKeysText, supportingParams(dp(8)));

        columnsLabel = createLabel();
        customDetails.addView(columnsLabel, supportingParams(0));

        columnsSeekBar = new LagSeekBar(this);
        columnsSeekBar.setMax(11);
        styleSeekBar(columnsSeekBar);
        customDetails.addView(columnsSeekBar, seekBarLayoutParams(dp(4)));
        root.addView(createFeatureGroup(customDisplaySwitch, customDetails), contentParams(dp(14)));

        TextView superCustomSection = createSectionLabel();
        superCustomSection.setText(R.string.section_super_custom);
        root.addView(superCustomSection, contentParams(dp(8)));
        root.addView(createSuperCustomGroup(), contentParams(dp(14)));

        TextView gamepadSection = createSectionLabel();
        gamepadSection.setText(R.string.section_gamepad);
        root.addView(gamepadSection, contentParams(dp(8)));

        gamepadLeftStickSwitch = createSwitch(R.string.gamepad_left_stick_switch);
        gamepadLeftStickDetails = createDetailsContainer();
        gamepadLeftStickSizeLabel = createLabel();
        gamepadLeftStickDetails.addView(gamepadLeftStickSizeLabel, supportingParams(0));
        gamepadLeftStickSizeSeekBar = createSizeSeekBar();
        gamepadLeftStickDetails.addView(gamepadLeftStickSizeSeekBar, seekBarLayoutParams(dp(4)));
        gamepadLeftStickDotSizeLabel = createLabel();
        gamepadLeftStickDetails.addView(gamepadLeftStickDotSizeLabel, supportingParams(dp(2)));
        gamepadLeftStickDotSizeSeekBar = createSizeSeekBar();
        gamepadLeftStickDetails.addView(gamepadLeftStickDotSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(gamepadLeftStickDetails, GamepadOverlayView.DISPLAY_LEFT_STICK);
        addKeyAppearanceControls(gamepadLeftStickDetails, GamepadOverlayView.DISPLAY_LEFT_STICK);
        gamepadLeftStickCenterCornerLabel = createLabel();
        gamepadLeftStickDetails.addView(gamepadLeftStickCenterCornerLabel, supportingParams(dp(2)));
        gamepadLeftStickCenterCornerSeekBar = createCornerStrengthSeekBar();
        gamepadLeftStickDetails.addView(gamepadLeftStickCenterCornerSeekBar, seekBarLayoutParams(dp(4)));
        gamepadLeftStickCenterColorDot = addStickCenterColorControl(gamepadLeftStickDetails, GamepadOverlayView.DISPLAY_LEFT_STICK);
        addKeyLayerOpacityControls(gamepadLeftStickDetails, GamepadOverlayView.DISPLAY_LEFT_STICK);
        addMotionControls(gamepadLeftStickDetails, GamepadOverlayView.DISPLAY_LEFT_STICK, R.string.motion_choice_label);
        root.addView(createFeatureGroup(gamepadLeftStickSwitch, gamepadLeftStickDetails), contentParams(dp(10)));

        gamepadRightStickSwitch = createSwitch(R.string.gamepad_right_stick_switch);
        gamepadRightStickDetails = createDetailsContainer();
        gamepadRightStickSizeLabel = createLabel();
        gamepadRightStickDetails.addView(gamepadRightStickSizeLabel, supportingParams(0));
        gamepadRightStickSizeSeekBar = createSizeSeekBar();
        gamepadRightStickDetails.addView(gamepadRightStickSizeSeekBar, seekBarLayoutParams(dp(4)));
        gamepadRightStickDotSizeLabel = createLabel();
        gamepadRightStickDetails.addView(gamepadRightStickDotSizeLabel, supportingParams(dp(2)));
        gamepadRightStickDotSizeSeekBar = createSizeSeekBar();
        gamepadRightStickDetails.addView(gamepadRightStickDotSizeSeekBar, seekBarLayoutParams(dp(4)));
        addOpacityControl(gamepadRightStickDetails, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        addKeyAppearanceControls(gamepadRightStickDetails, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        gamepadRightStickCenterCornerLabel = createLabel();
        gamepadRightStickDetails.addView(gamepadRightStickCenterCornerLabel, supportingParams(dp(2)));
        gamepadRightStickCenterCornerSeekBar = createCornerStrengthSeekBar();
        gamepadRightStickDetails.addView(gamepadRightStickCenterCornerSeekBar, seekBarLayoutParams(dp(4)));
        gamepadRightStickCenterColorDot = addStickCenterColorControl(gamepadRightStickDetails, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        addKeyLayerOpacityControls(gamepadRightStickDetails, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        addMotionControls(gamepadRightStickDetails, GamepadOverlayView.DISPLAY_RIGHT_STICK, R.string.motion_choice_label);
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
        addKeyAppearanceControls(gamepadFaceDetails, GamepadOverlayView.DISPLAY_FACE);
        addOpacityControl(gamepadFaceDetails, GamepadOverlayView.DISPLAY_FACE);
        addKeyLayerOpacityControls(gamepadFaceDetails, GamepadOverlayView.DISPLAY_FACE);
        addMotionControls(gamepadFaceDetails, GamepadOverlayView.DISPLAY_FACE, R.string.motion_choice_label);
        gamepadFaceReverseSwitch = createSwitch(R.string.gamepad_face_reverse);
        gamepadFaceReverseSwitch.setTextSize(14f);
        gamepadFaceDetails.addView(gamepadFaceReverseSwitch, switchParams(dp(2)));
        gamepadFaceSymbolSwitch = createSwitch(R.string.gamepad_face_symbol_icons);
        gamepadFaceSymbolSwitch.setTextSize(14f);
        gamepadFaceDetails.addView(gamepadFaceSymbolSwitch, switchParams(dp(2)));
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

        gamepadDpadSwitch = createSwitch(R.string.gamepad_dpad_switch);
        gamepadDpadDetails = createDetailsContainer();
        gamepadDpadSizeLabel = createLabel();
        gamepadDpadDetails.addView(gamepadDpadSizeLabel, supportingParams(0));
        gamepadDpadSizeSeekBar = createSizeSeekBar();
        gamepadDpadDetails.addView(gamepadDpadSizeSeekBar, seekBarLayoutParams(dp(4)));
        addKeyAppearanceControls(gamepadDpadDetails, GamepadOverlayView.DISPLAY_DPAD);
        addOpacityControl(gamepadDpadDetails, GamepadOverlayView.DISPLAY_DPAD);
        addKeyLayerOpacityControls(gamepadDpadDetails, GamepadOverlayView.DISPLAY_DPAD);
        addMotionControls(gamepadDpadDetails, GamepadOverlayView.DISPLAY_DPAD, R.string.motion_choice_label);
        root.addView(createFeatureGroup(gamepadDpadSwitch, gamepadDpadDetails), contentParams(dp(10)));

        gamepadLeftShoulderSwitch = createSwitch(R.string.gamepad_left_shoulder_switch);
        gamepadLeftShoulderDetails = createDetailsContainer();
        gamepadLeftShoulderSizeLabel = createLabel();
        gamepadLeftShoulderDetails.addView(gamepadLeftShoulderSizeLabel, supportingParams(0));
        gamepadLeftShoulderSizeSeekBar = createSizeSeekBar();
        gamepadLeftShoulderDetails.addView(gamepadLeftShoulderSizeSeekBar, seekBarLayoutParams(dp(4)));
        addKeyAppearanceControls(gamepadLeftShoulderDetails, GamepadOverlayView.DISPLAY_LEFT_SHOULDER);
        addOpacityControl(gamepadLeftShoulderDetails, GamepadOverlayView.DISPLAY_LEFT_SHOULDER);
        addKeyLayerOpacityControls(gamepadLeftShoulderDetails, GamepadOverlayView.DISPLAY_LEFT_SHOULDER);
        addMotionControls(gamepadLeftShoulderDetails, GamepadOverlayView.DISPLAY_LEFT_SHOULDER, R.string.motion_choice_label);
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
        addKeyAppearanceControls(gamepadRightShoulderDetails, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER);
        addOpacityControl(gamepadRightShoulderDetails, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER);
        addKeyLayerOpacityControls(gamepadRightShoulderDetails, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER);
        addMotionControls(gamepadRightShoulderDetails, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER, R.string.motion_choice_label);
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
        addKeyAppearanceControls(gamepadBackDetails, GamepadOverlayView.DISPLAY_BACK);
        addOpacityControl(gamepadBackDetails, GamepadOverlayView.DISPLAY_BACK);
        addKeyLayerOpacityControls(gamepadBackDetails, GamepadOverlayView.DISPLAY_BACK);
        addMotionControls(gamepadBackDetails, GamepadOverlayView.DISPLAY_BACK, R.string.motion_choice_label);
        root.addView(createFeatureGroup(gamepadBackSwitch, gamepadBackDetails), contentParams(dp(14)));

        TextView sensitivitySection = createSectionLabel();
        sensitivitySection.setText(R.string.section_sensitivity);
        root.addView(sensitivitySection, contentParams(dp(8)));

        sensitivitySwitch = createSwitch(R.string.sensitivity_switch_label);
        sensitivityDetails = createDetailsContainer();

        sensitivityModeSpinner = createChoiceSpinner(new String[]{
                getString(R.string.sensitivity_mode_shizuku), getString(R.string.sensitivity_mode_root)});
        sensitivityModeSpinner.setSelection(
                SensitivitySettingsStore.getMode(this) == SensitivitySettingsStore.MODE_ROOT ? 1 : 0, false);
        setControlEnabled(sensitivityModeSpinner, !RootBridge.isRootActive());
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
        sensitivityResetButton.setMinHeight(dp(44));
        sensitivityResetButton.setMinimumHeight(dp(44));
        styleActionButton(sensitivityResetButton);
        sensitivityDetails.addView(sensitivityResetButton, supportingParams(dp(4)));
        sensitivityResetButton.setOnClickListener(v -> {
            SensitivitySettingsStore.setMousePercent(MainActivity.this, 100);
            SensitivitySettingsStore.setGamepadPercent(MainActivity.this, 100);
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

        LinearLayout gamepadMappingGroup = new LinearLayout(this);
        gamepadMappingGroup.setOrientation(LinearLayout.VERTICAL);
        gamepadMappingGroup.setPadding(dp(14), dp(10), dp(14), dp(12));
        gamepadMappingGroup.setBackground(UiChrome.card(this));
        TextView gamepadMappingTitle = createLabel();
        gamepadMappingTitle.setText(R.string.gamepad_mapping_title);
        gamepadMappingGroup.addView(gamepadMappingTitle, supportingParams(dp(6)));
        gamepadMappingAddButton = createConfigButton(
                R.string.gamepad_mapping_add, v -> startGamepadMappingCapture());
        gamepadMappingGroup.addView(gamepadMappingAddButton, supportingParams(dp(4)));
        gamepadMappingStatusText = createSupportingText();
        gamepadMappingGroup.addView(gamepadMappingStatusText, supportingParams(dp(4)));
        gamepadMappingList = new LinearLayout(this);
        gamepadMappingList.setOrientation(LinearLayout.VERTICAL);
        gamepadMappingGroup.addView(gamepadMappingList, supportingParams(dp(4)));
        TextView gamepadMappingHint = createSupportingText();
        gamepadMappingHint.setText(R.string.gamepad_mapping_hint);
        gamepadMappingGroup.addView(gamepadMappingHint, supportingParams(0));
        root.addView(gamepadMappingGroup, contentParams(dp(10)));

        customMappingSwitch = createSwitch(R.string.custom_mapping_switch_label);
        customMappingDetails = createDetailsContainer();
        customMappingStatusText = createSupportingText();
        customMappingDetails.addView(customMappingStatusText, supportingParams(dp(2)));
        customMappingDelayLabel = createLabel();
        customMappingDetails.addView(customMappingDelayLabel, supportingParams(dp(4)));
        customMappingDelaySeekBar = new LagSeekBar(this);
        customMappingDelaySeekBar.setMax(CustomMappingStore.DELAY_MAX_MS / CUSTOM_MAPPING_DELAY_STEP_MS);
        customMappingDelaySeekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(customMappingDelaySeekBar);
        customMappingDetails.addView(customMappingDelaySeekBar, seekBarLayoutParams(dp(4)));
        TextView customMappingDelayHint = createSupportingText();
        customMappingDelayHint.setText(R.string.custom_mapping_delay_hint);
        customMappingDetails.addView(customMappingDelayHint, supportingParams(dp(4)));
        TextView customMappingRecordingHint = createSupportingText();
        customMappingRecordingHint.setText(R.string.custom_mapping_recording_hint);
        customMappingDetails.addView(customMappingRecordingHint, supportingParams(dp(4)));
        customMappingConfigButton = createConfigButton(
                R.string.custom_mapping_config, v -> startCustomMappingCapture());
        customMappingDetails.addView(customMappingConfigButton, supportingParams(dp(4)));
        customMappingList = new LinearLayout(this);
        customMappingList.setOrientation(LinearLayout.VERTICAL);
        customMappingDetails.addView(customMappingList, supportingParams(dp(4)));
        TextView customMappingHint = createSupportingText();
        customMappingHint.setText(R.string.custom_mapping_hint);
        customMappingDetails.addView(customMappingHint, supportingParams(0));
        View customMappingGroup = createFeatureGroup(customMappingSwitch, customMappingDetails);
        customMappingGroup.setVisibility(SHOW_CUSTOM_MAPPING_UI ? View.VISIBLE : View.GONE);
        root.addView(customMappingGroup, contentParams(dp(10)));

        simultaneousClickSwitch = createSwitch(R.string.simultaneous_click_switch_label);
        simultaneousClickDetails = createDetailsContainer();
        simultaneousClickStatusText = createSupportingText();
        simultaneousClickDetails.addView(simultaneousClickStatusText, supportingParams(dp(2)));
        simultaneousClickConfigButton = createConfigButton(
                R.string.simultaneous_click_config, v -> startSimultaneousClickCapture());
        simultaneousClickDetails.addView(simultaneousClickConfigButton, supportingParams(dp(4)));
        simultaneousClickList = new LinearLayout(this);
        simultaneousClickList.setOrientation(LinearLayout.VERTICAL);
        simultaneousClickDetails.addView(simultaneousClickList, supportingParams(dp(4)));
        TextView simultaneousClickHint = createSupportingText();
        simultaneousClickHint.setText(R.string.simultaneous_click_hint);
        simultaneousClickDetails.addView(simultaneousClickHint, supportingParams(0));
        root.addView(createFeatureGroup(simultaneousClickSwitch, simultaneousClickDetails), contentParams(dp(10)));

        forceHoldSwitch = createSwitch(R.string.force_hold_switch_label);
        forceHoldDetails = createDetailsContainer();
        forceHoldStatusText = createSupportingText();
        forceHoldDetails.addView(forceHoldStatusText, supportingParams(dp(2)));
        TextView forceHoldHint = createSupportingText();
        forceHoldHint.setText(R.string.force_hold_hint);
        forceHoldDetails.addView(forceHoldHint, supportingParams(0));
        root.addView(createFeatureGroup(forceHoldSwitch, forceHoldDetails), contentParams(dp(10)));

        TextView behaviorSection = createSectionLabel();

        behaviorSection.setText(R.string.section_behavior);
        root.addView(behaviorSection, contentParams(dp(8)));

        hideDisplayHotkeySwitch = createSwitch(R.string.hide_display_hotkey_switch_label);
        hideDisplayHotkeyDetails = createDetailsContainer();
        hideDisplayHotkeyButton = createConfigButton(
                R.string.hide_display_hotkey_unbound, v -> toggleHideDisplayHotkeyCapture());
        hideDisplayHotkeyDetails.addView(hideDisplayHotkeyButton, supportingParams(dp(4)));
        hideDisplayTargetSelectionButton = createConfigButton(
                R.string.hide_display_target_select, v -> showHideDisplayTargetSelectionDialog());
        hideDisplayHotkeyDetails.addView(hideDisplayTargetSelectionButton, supportingParams(dp(4)));
        TextView hideDisplayHotkeyHint = createSupportingText();
        hideDisplayHotkeyHint.setText(R.string.hide_display_hotkey_hint);
        hideDisplayHotkeyDetails.addView(hideDisplayHotkeyHint, supportingParams(0));
        root.addView(createFeatureGroup(hideDisplayHotkeySwitch, hideDisplayHotkeyDetails), contentParams(dp(10)));

        touchDisplaySwitch = createSwitch(R.string.touch_display_switch_label);
        touchDisplayDetails = createDetailsContainer();
        touchDisplaySizeLabel = createLabel();
        touchDisplayDetails.addView(touchDisplaySizeLabel, supportingParams(0));
        touchDisplaySizeSeekBar = createSizeSeekBar();
        touchDisplayDetails.addView(touchDisplaySizeSeekBar, seekBarLayoutParams(dp(4)));
        touchDisplaySpacingLabel = createLabel();
        touchDisplayDetails.addView(touchDisplaySpacingLabel, supportingParams(dp(2)));
        touchDisplaySpacingSeekBar = createKeySpacingSeekBar();
        touchDisplayDetails.addView(touchDisplaySpacingSeekBar, seekBarLayoutParams(dp(4)));
        addKeyAppearanceControls(touchDisplayDetails, OverlayState.DISPLAY_TOUCH_APPEARANCE);
        addOpacityControl(touchDisplayDetails, OverlayState.DISPLAY_TOUCH_APPEARANCE);
        addKeyLayerOpacityControls(touchDisplayDetails, OverlayState.DISPLAY_TOUCH_APPEARANCE);
        addMotionControls(touchDisplayDetails, OverlayState.DISPLAY_TOUCH_APPEARANCE, R.string.motion_choice_label);
        touchDisplayEditRegionsButton = new Button(this);
        touchDisplayEditRegionsButton.setText(R.string.touch_display_edit_regions);
        touchDisplayEditRegionsButton.setAllCaps(false);
        touchDisplayEditRegionsButton.setTextSize(13f);
        touchDisplayEditRegionsButton.setMinHeight(dp(44));
        touchDisplayEditRegionsButton.setMinimumHeight(dp(44));
        styleActionButton(touchDisplayEditRegionsButton);
        touchDisplayDetails.addView(touchDisplayEditRegionsButton, supportingParams(dp(4)));

        touchDisplayRetryButton = new Button(this);
        touchDisplayRetryButton.setText("重新检测触屏");
        touchDisplayRetryButton.setAllCaps(false);
        touchDisplayRetryButton.setTextSize(13f);
        touchDisplayRetryButton.setMinHeight(dp(44));
        touchDisplayRetryButton.setMinimumHeight(dp(44));
        styleActionButton(touchDisplayRetryButton);
        touchDisplayDetails.addView(touchDisplayRetryButton, supportingParams(dp(4)));

        touchDisplayStatusText = createSupportingText();
        touchDisplayStatusText.setText("状态：" + AxonInputAccessibilityService.getTouchMonitorStatus());
        touchDisplayDetails.addView(touchDisplayStatusText, supportingParams(dp(2)));

        TextView touchDisplayHint = createSupportingText();
        touchDisplayHint.setText(R.string.touch_display_hint);
        touchDisplayDetails.addView(touchDisplayHint, supportingParams(dp(2)));
        root.addView(createFeatureGroup(touchDisplaySwitch, touchDisplayDetails), contentParams(dp(10)));

        dpsSwitch = createSwitch(R.string.dps_switch_label);
        dpsDetails = createDetailsContainer();
        dpsTargetText = createSupportingText();
        dpsDetails.addView(dpsTargetText, supportingParams(dp(2)));
        dpsSizeLabel = createLabel();
        dpsDetails.addView(dpsSizeLabel, supportingParams(dp(2)));
        dpsSizeSeekBar = createSizeSeekBar();
        dpsDetails.addView(dpsSizeSeekBar, seekBarLayoutParams(dp(4)));
        addDpsTextColorControl(dpsDetails);
        TextView dpsHint = createSupportingText();
        dpsHint.setText(R.string.dps_target_hint);
        dpsDetails.addView(dpsHint, supportingParams(dp(2)));
        addOpacityControl(dpsDetails, DpsOverlayView.DISPLAY_DPS);
        root.addView(createFeatureGroup(dpsSwitch, dpsDetails), contentParams(dp(10)));

        dragSwitch = createSwitch(R.string.drag_switch_label);
        root.addView(createSwitchGroup(dragSwitch), contentParams(dp(10)));

        fontSwitch = createSwitch(R.string.font_switch_label);
        fontDetails = createDetailsContainer();
        fontChoiceSpinner = createChoiceSpinner(new String[]{getString(R.string.font_import_none)});
        fontDetails.addView(createInlineChoiceRow(
                R.string.font_choice_label, fontChoiceSpinner), supportingParams(dp(4)));
        fontImportButton = new Button(this);
        fontImportButton.setText(R.string.font_import_button);
        fontImportButton.setAllCaps(false);
        fontImportButton.setTextSize(13f);
        fontImportButton.setMinHeight(dp(44));
        fontImportButton.setMinimumHeight(dp(44));
        styleActionButton(fontImportButton);
        fontDetails.addView(fontImportButton, supportingParams(dp(4)));
        fontStatusText = createSupportingText();
        fontDetails.addView(fontStatusText, supportingParams(dp(2)));
        TextView fontHint = createSupportingText();
        fontHint.setText(R.string.font_import_hint);
        fontDetails.addView(fontHint, supportingParams(0));
        root.addView(createFeatureGroup(fontSwitch, fontDetails), contentParams(dp(10)));

        LinearLayout compatibilityGroup = new LinearLayout(this);
        compatibilityGroup.setOrientation(LinearLayout.VERTICAL);
        compatibilityGroup.setPadding(dp(14), dp(10), dp(14), dp(12));
        compatibilityGroup.setBackground(UiChrome.card(this));
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
        compatibilityResetButton.setMinHeight(dp(44));
        compatibilityResetButton.setMinimumHeight(dp(44));
        styleActionButton(compatibilityResetButton);
        compatibilityGroup.addView(compatibilityResetButton, supportingParams(0));
        root.addView(compatibilityGroup, contentParams(dp(10)));

        globalHtmlSwitch = createSwitch(R.string.global_html_switch_label);
        globalHtmlDetails = createDetailsContainer();
        globalHtmlChoiceSpinner = createChoiceSpinner(new String[]{getString(R.string.global_html_not_imported)});
        globalHtmlDetails.addView(createInlineChoiceRow(
                R.string.global_html_choice_label, globalHtmlChoiceSpinner), supportingParams(dp(4)));
        globalHtmlFontSpinner = createChoiceSpinner(new String[]{
                getString(R.string.global_html_font_page),
                getString(R.string.global_html_font_follow)});
        globalHtmlDetails.addView(createInlineChoiceRow(
                R.string.global_html_font_label, globalHtmlFontSpinner), supportingParams(dp(4)));
        TextView globalHtmlHint = createSupportingText();
        globalHtmlHint.setText(R.string.global_html_hint);
        globalHtmlDetails.addView(globalHtmlHint, supportingParams(dp(6)));
        globalHtmlImportButton = new Button(this);
        globalHtmlImportButton.setText(R.string.global_html_import);
        globalHtmlImportButton.setAllCaps(false);
        globalHtmlImportButton.setTextSize(13f);
        globalHtmlImportButton.setMinHeight(dp(44));
        globalHtmlImportButton.setMinimumHeight(dp(44));
        styleActionButton(globalHtmlImportButton);
        globalHtmlDetails.addView(globalHtmlImportButton, supportingParams(dp(2)));
        globalHtmlStatusText = createSupportingText();
        globalHtmlDetails.addView(globalHtmlStatusText, supportingParams(dp(6)));
        root.addView(createFeatureGroup(globalHtmlSwitch, globalHtmlDetails), contentParams(dp(10)));

        live2dSwitch = createSwitch(R.string.live2d_switch_label);
        live2dDetails = createDetailsContainer();
        live2dSizeLabel = createLabel();
        live2dSizeSeekBar = new LagSeekBar(this);
        live2dSizeSeekBar.setMax(LIVE2D_SIZE_MAX - LIVE2D_SIZE_MIN);
        live2dSizeSeekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(live2dSizeSeekBar);
        live2dDetails.addView(live2dSizeLabel, supportingParams(dp(2)));
        live2dDetails.addView(live2dSizeSeekBar, seekBarLayoutParams(dp(4)));
        live2dQualitySpinner = createChoiceSpinner(new String[]{
                getString(R.string.render_quality_normal), getString(R.string.render_quality_clear)});
        live2dDetails.addView(createInlineChoiceRow(
                R.string.render_quality_label, live2dQualitySpinner), supportingParams(dp(4)));

        live2dPhysicsStrengthLabel = createLabel();
        live2dPhysicsStrengthSeekBar = new LagSeekBar(this);
        live2dPhysicsStrengthSeekBar.setMax(200);
        live2dPhysicsStrengthSeekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(live2dPhysicsStrengthSeekBar);
        live2dDetails.addView(live2dPhysicsStrengthLabel, supportingParams(dp(6)));
        live2dDetails.addView(live2dPhysicsStrengthSeekBar, seekBarLayoutParams(dp(2)));

        live2dPhysicsGroupsButton = new Button(this);
        live2dPhysicsGroupsButton.setAllCaps(false);
        live2dPhysicsGroupsButton.setTextSize(13f);
        live2dPhysicsGroupsButton.setMinHeight(dp(44));
        live2dPhysicsGroupsButton.setMinimumHeight(dp(44));
        styleActionButton(live2dPhysicsGroupsButton);
        live2dDetails.addView(live2dPhysicsGroupsButton, supportingParams(dp(2)));

        addDivider(live2dDetails, dp(10), dp(6));
        TextView live2dDebugGroupLabel = createLabel();
        live2dDebugGroupLabel.setText(R.string.live2d_debug_group);
        live2dDetails.addView(live2dDebugGroupLabel, supportingParams(dp(2)));

        live2dExpressionDebugButton = new Button(this);
        live2dExpressionDebugButton.setAllCaps(false);
        live2dExpressionDebugButton.setTextSize(13f);
        live2dExpressionDebugButton.setMinHeight(dp(44));
        live2dExpressionDebugButton.setMinimumHeight(dp(44));
        styleActionButton(live2dExpressionDebugButton);
        live2dDetails.addView(live2dExpressionDebugButton, supportingParams(dp(2)));

        live2dParameterDebugButton = new Button(this);
        live2dParameterDebugButton.setAllCaps(false);
        live2dParameterDebugButton.setTextSize(13f);
        live2dParameterDebugButton.setMinHeight(dp(44));
        live2dParameterDebugButton.setMinimumHeight(dp(44));
        styleActionButton(live2dParameterDebugButton);
        live2dDetails.addView(live2dParameterDebugButton, supportingParams(dp(2)));

        live2dMotionTrackingSwitch = createSwitch(R.string.live2d_motion_tracking);
        live2dDetails.addView(live2dMotionTrackingSwitch, supportingParams(dp(2)));
        live2dMouseCaptureSwitch = createSwitch(R.string.live2d_mouse_capture);
        live2dDetails.addView(live2dMouseCaptureSwitch, supportingParams(dp(2)));
        live2dHideWatermarkSwitch = createSwitch(R.string.live2d_hide_watermark);
        live2dDetails.addView(live2dHideWatermarkSwitch, supportingParams(dp(2)));

        live2dImportButton = new Button(this);
        live2dImportButton.setText(R.string.live2d_import_model);
        live2dImportButton.setAllCaps(false);
        live2dImportButton.setTextSize(13f);
        live2dImportButton.setMinHeight(dp(44));
        live2dImportButton.setMinimumHeight(dp(44));
        styleActionButton(live2dImportButton);
        live2dDetails.addView(live2dImportButton, supportingParams(dp(2)));

        root.addView(createFeatureGroup(live2dSwitch, live2dDetails), contentParams(dp(10)));

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

        View[] sectionPages = new View[]{appearanceSection, displaySection, superCustomSection,
                gamepadSection, sensitivitySection, behaviorSection, configurationSection};
        sectionPageViews = splitContentIntoSectionPages(root, sectionPages);
        sectionPagerHost = createSectionPagerHost(sectionPageViews);

        SectionNavigationBar sectionNav = createSectionNavigation(
                new int[]{R.string.section_appearance, R.string.section_keyboard_mouse,
                        R.string.section_super_custom, R.string.section_gamepad,
                        R.string.section_sensitivity, R.string.section_behavior,
                        R.string.section_configuration},
                sectionPages.length);
        sectionNavigationView = sectionNav;
        selectSectionPage(0, false);

        // Content owns the visual hierarchy; primary navigation stays reachable at the bottom.
        page.addView(sectionPagerHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View navDivider = new View(this);
        navDivider.setBackgroundColor(UiPalette.divider(this));
        page.addView(navDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        page.addView(sectionNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        activityRoot = new FrameLayout(this);
        activityRoot.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(activityRoot);
        UiChrome.applySafeInsets(page, 0, 0, 0, 0);

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
            setControlEnabled(spaceDpsSwitch, enabled);
            setControlEnabled(spaceDashSwitch, enabled);
            if (!internalChange) OverlayState.setKeyboardSpaceEnabled(this, enabled);
        });
        bindSimpleSwitch(spaceDpsSwitch,
                enabled -> OverlayState.setKeyboardSpaceDpsEnabled(this, enabled));
        bindSimpleSwitch(spaceDashSwitch,
                enabled -> OverlayState.setKeyboardSpaceDashEnabled(this, enabled));
        keyboardMouseButtonsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setControlEnabled(keyboardMouseCpsSwitch, enabled);
            if (!internalChange) OverlayState.setKeyboardMouseButtonsEnabled(this, enabled);
        });
        bindSimpleSwitch(keyboardMouseCpsSwitch,
                enabled -> OverlayState.setKeyboardMouseCpsEnabled(this, enabled));

        bindFeatureSwitch(inputFullKeyboardSwitch, inputFullKeyboardDetails,
                enabled -> OverlayState.setInputFullKeyboardEnabled(this, enabled));
        bindFeatureSwitch(mouseSwitch, mouseDetails,
                enabled -> OverlayState.setMouseEnabled(this, enabled));
        bindFeatureSwitch(keyboardCatSwitch, keyboardCatDetails,
                enabled -> OverlayState.setKeyboardCatEnabled(this, enabled));
        keyboardCatQualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange) return;
                OverlayState.setKeyboardCatRenderQuality(MainActivity.this,
                        position == 1 ? OverlayState.RENDER_QUALITY_CLEAR : OverlayState.RENDER_QUALITY_NORMAL);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        bindSimpleSwitch(keyboardCatMouseModeSwitch,
                enabled -> OverlayState.setKeyboardCatMouseMode(this, enabled));
        bindSimpleSwitch(keyboardCatGlobalReverseSwitch,
                enabled -> OverlayState.setKeyboardCatGlobalReverse(this, enabled));
        keyboardCatStyleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange || position < 0 || position >= keyboardCatStyles.size()) return;
                cancelKeyboardCatExpressionHotkeyCapture(true);
                cancelKeyboardCatFunctionBindingCapture();
                BongoCatStyleManager.StyleInfo style = keyboardCatStyles.get(position);
                OverlayState.setKeyboardCatStyleId(MainActivity.this, style.id);
                OverlayState.setKeyboardCatDebugExpression(MainActivity.this, "auto");
                setControlEnabled(keyboardCatDeleteStyleButton, !style.builtin);
                setControlEnabled(keyboardCatModelFunctionsButton, !style.builtin);
                setControlEnabled(keyboardCatMouseModeSwitch, style.builtin);
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
        superCustomDisplaySwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            OverlayState.setSuperCustomEnabled(MainActivity.this, enabled);
            refreshHideDisplayHotkeyUi();
            handleDisplayModeChanged();
        });
        bindFeatureSwitch(gamepadLeftStickSwitch, gamepadLeftStickDetails,
                enabled -> OverlayState.setGamepadLeftStickEnabled(this, enabled));
        bindFeatureSwitch(gamepadRightStickSwitch, gamepadRightStickDetails,
                enabled -> OverlayState.setGamepadRightStickEnabled(this, enabled));
        bindFeatureSwitch(gamepadFaceSwitch, gamepadFaceDetails,
                enabled -> OverlayState.setGamepadFaceEnabled(this, enabled));
        bindFeatureSwitch(gamepadDpadSwitch, gamepadDpadDetails,
                enabled -> OverlayState.setGamepadDpadEnabled(this, enabled));
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

        inputFullKeyboardSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                inputFullKeyboardSizeLabel, R.string.full_keyboard_size_format,
                value -> OverlayState.setFullKeyboardSize(MainActivity.this, value)));

        touchDisplaySizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                touchDisplaySizeLabel, R.string.touch_display_size_format,
                value -> OverlayState.setTouchDisplaySize(MainActivity.this, value)));

        touchDisplaySpacingSeekBar.setOnSeekBarChangeListener(spacingListener(
                touchDisplaySpacingLabel, R.string.touch_display_spacing_format,
                value -> OverlayState.setTouchDisplaySpacing(MainActivity.this, value)));

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

        gamepadLeftStickCenterCornerSeekBar.setOnSeekBarChangeListener(cornerStrengthListener(
                gamepadLeftStickCenterCornerLabel,
                value -> OverlayState.setGamepadStickCenterCornerStrength(
                        MainActivity.this, GamepadOverlayView.DISPLAY_LEFT_STICK, value)));

        gamepadRightStickCenterCornerSeekBar.setOnSeekBarChangeListener(cornerStrengthListener(
                gamepadRightStickCenterCornerLabel,
                value -> OverlayState.setGamepadStickCenterCornerStrength(
                        MainActivity.this, GamepadOverlayView.DISPLAY_RIGHT_STICK, value)));

        dpsSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                dpsSizeLabel, R.string.dps_size_format,
                value -> OverlayState.setDpsSize(MainActivity.this, value)));

        gamepadFaceSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadFaceSizeLabel, R.string.gamepad_face_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_FACE, value)));

        gamepadDpadSizeSeekBar.setOnSeekBarChangeListener(sizeListener(
                gamepadDpadSizeLabel, R.string.gamepad_dpad_size_format,
                value -> OverlayState.setGamepadDisplaySize(MainActivity.this, GamepadOverlayView.DISPLAY_DPAD, value)));

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

        bindSimpleSwitch(gamepadFaceReverseSwitch,
                enabled -> OverlayState.setGamepadFaceReversed(this, enabled));
        bindSimpleSwitch(gamepadFaceSymbolSwitch,
                enabled -> OverlayState.setGamepadFaceSymbolIcons(this, enabled));
        bindSimpleSwitch(gamepadFaceYDpsSwitch,
                enabled -> GamepadSettingsStore.setFaceYDpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadFaceXDpsSwitch,
                enabled -> GamepadSettingsStore.setFaceXDpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadFaceBDpsSwitch,
                enabled -> GamepadSettingsStore.setFaceBDpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadFaceADpsSwitch,
                enabled -> GamepadSettingsStore.setFaceADpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadL2ProgressSwitch,
                enabled -> GamepadSettingsStore.setL2ProgressEnabled(this, enabled));
        bindSimpleSwitch(gamepadR2ProgressSwitch,
                enabled -> GamepadSettingsStore.setR2ProgressEnabled(this, enabled));
        bindSimpleSwitch(gamepadL1DpsSwitch,
                enabled -> GamepadSettingsStore.setL1DpsEnabled(this, enabled));
        bindSimpleSwitch(gamepadR1DpsSwitch,
                enabled -> GamepadSettingsStore.setR1DpsEnabled(this, enabled));

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
            SensitivitySettingsStore.setEnabled(this, enabled);
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
                int mode = position == 1 ? SensitivitySettingsStore.MODE_ROOT
                        : SensitivitySettingsStore.MODE_SHIZUKU;
                SensitivitySettingsStore.setMode(MainActivity.this, mode);
                if (sensitivitySwitch.isChecked()) ensureAccessibility();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        mouseSensitivitySeekBar.setOnSeekBarChangeListener(sensitivityListener(
                mouseSensitivityLabel, R.string.mouse_sensitivity_format,
                value -> SensitivitySettingsStore.setMousePercent(MainActivity.this, value)));

        gamepadSensitivitySeekBar.setOnSeekBarChangeListener(sensitivityListener(
                gamepadSensitivityLabel, R.string.gamepad_sensitivity_format,
                value -> SensitivitySettingsStore.setGamepadPercent(MainActivity.this, value)));

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
            refreshHideDisplayHotkeyUi();
            handleDisplayModeChanged();
        });

        customMappingSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (!enabled) {
                CustomMappingStore.setEnabled(MainActivity.this, false);
                cancelCustomMappingCapture();
                updateCustomMappingUi();
                handleDisplayModeChanged();
                return;
            }
            if (!CustomMappingStore.hasRules(MainActivity.this)) {
                startCustomMappingCapture();
                return;
            }
            CustomMappingStore.setEnabled(MainActivity.this, true);
            updateCustomMappingUi();
            ensureAccessibility();
            ensurePrivilegedInputForGamepadMapping();
            handleDisplayModeChanged();
        });

        customMappingDelaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int delay = progress * CUSTOM_MAPPING_DELAY_STEP_MS;
                customMappingDelayLabel.setText(getString(R.string.custom_mapping_delay_format, delay));
                if (fromUser && !internalChange) CustomMappingStore.setDelayMs(MainActivity.this, delay);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        simultaneousClickSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (!enabled) {
                SimultaneousClickStore.setEnabled(MainActivity.this, false);
                simultaneousClickCaptureStep = 0;
                simultaneousClickSourcePending = -1;
                updateSimultaneousClickUi();
                handleDisplayModeChanged();
                return;
            }
            if (!SimultaneousClickStore.hasBinding(MainActivity.this)) {
                startSimultaneousClickCapture();
                return;
            }
            SimultaneousClickStore.setEnabled(MainActivity.this, true);
            updateSimultaneousClickUi();
            ensurePrivilegedInputForGamepadMapping();
            handleDisplayModeChanged();
        });

        forceHoldSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (enabled && (customMappingCaptureStep != 0 || simultaneousClickCaptureStep != 0
                    || gamepadMappingCaptureArmed || gamepadCustomSwapCaptureStep != 0)) {
                internalChange = true;
                forceHoldSwitch.setChecked(false);
                internalChange = false;
                Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!enabled) {
                forceHoldCaptureStep = 0;
                forceHoldTargetKeyPending = -1;
                forceHoldTargetScanPending = -1;
                OverlayState.setForceHoldEnabled(MainActivity.this, false);
                updateForceHoldUi();
                handleDisplayModeChanged();
                return;
            }

            // 每次开启都重新录入两颗键，避免旧绑定被误触发。
            cancelKeyboardCatExpressionHotkeyCapture(true);
            cancelHideDisplayHotkeyCapture(true);
            OverlayState.setForceHoldEnabled(MainActivity.this, false);
            OverlayState.clearForceHoldBinding(MainActivity.this);
            forceHoldCaptureStep = 1;
            forceHoldTargetKeyPending = -1;
            forceHoldTargetScanPending = -1;
            updateForceHoldUi();
            Toast.makeText(MainActivity.this, R.string.force_hold_wait_target, Toast.LENGTH_SHORT).show();
        });

        bindFeatureSwitch(hideDisplayHotkeySwitch, hideDisplayHotkeyDetails, enabled -> {
            if (!enabled) cancelHideDisplayHotkeyCapture(true);
            OverlayState.setHideDisplayHotkeyEnabled(MainActivity.this, enabled);
            refreshHideDisplayHotkeyUi();
            if (enabled) ensureAccessibility();
        });

        bindSimpleSwitch(dragSwitch,
                enabled -> OverlayState.setDragEnabled(this, enabled));

        touchDisplaySwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            TouchDisplayStore.setEnabled(MainActivity.this, enabled);
            mainHandler.removeCallbacks(touchDisplayStatusTicker);
            if (enabled) {
                // Touch capture is Shizuku-only. Register immediately even on rooted devices so a
                // Shizuku service that is still starting can continue the permission flow later.
                ShizukuBridge.addListener(MainActivity.this);
                ensureAccessibility();
                ensureShizukuForTouchDisplay();
                mainHandler.post(touchDisplayStatusTicker);
                AxonInputAccessibilityService.showTouchRegionEditorOverlay();
                if (AxonInputAccessibilityService.isServiceConnected()) {
                    Toast.makeText(MainActivity.this, "已开启悬浮框选，切换到目标应用即可直接调整", Toast.LENGTH_SHORT).show();
                    moveTaskToBack(true);
                }
            } else {
                updateTouchDisplayStatusUi();
            }
            refreshHideDisplayHotkeyUi();
        });
        touchDisplayEditRegionsButton.setOnClickListener(v -> {
            ensureAccessibility();
            AxonInputAccessibilityService.showTouchRegionEditorOverlay();
            if (AxonInputAccessibilityService.isServiceConnected()) {
                Toast.makeText(MainActivity.this, "已开启悬浮框选，切换到目标应用即可直接调整", Toast.LENGTH_SHORT).show();
                moveTaskToBack(true);
            }
        });
        touchDisplayRetryButton.setOnClickListener(v -> {
            ShizukuBridge.addListener(MainActivity.this);
            ensureAccessibility();
            ensureShizukuForTouchDisplay();
            AxonInputAccessibilityService.restartTouchMonitor();
            if (touchDisplayStatusText != null) touchDisplayStatusText.setText("状态：正在重新检测");
            mainHandler.removeCallbacks(touchDisplayStatusTicker);
            mainHandler.post(touchDisplayStatusTicker);
        });

        globalHtmlSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            GlobalHtmlStore.setEnabled(this, enabled);
            syncGlobalHtmlUi();
            if (enabled && !GlobalHtmlStore.exists(this)) {
                Toast.makeText(this, R.string.global_html_import_first, Toast.LENGTH_SHORT).show();
            }
        });
        globalHtmlChoiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange || position < 0 || position >= globalHtmlChoices.size()) return;
                GlobalHtmlStore.setSelectedId(MainActivity.this, globalHtmlChoices.get(position).id);
                syncGlobalHtmlUi();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        globalHtmlFontSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange) return;
                GlobalHtmlStore.setFontMode(MainActivity.this, position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        globalHtmlImportButton.setOnClickListener(v -> openGlobalHtmlPicker());

        live2dSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (enabled && !Live2DModelStore.exists(MainActivity.this)) {
                OverlayState.setLive2DEnabled(MainActivity.this, false);
                internalChange = true;
                live2dSwitch.setChecked(false);
                internalChange = false;
                Toast.makeText(MainActivity.this, R.string.live2d_import_first, Toast.LENGTH_SHORT).show();
                return;
            }
            OverlayState.setLive2DEnabled(MainActivity.this, enabled);
            syncInAppLive2D();
            syncLive2DTrackingService();
            handleDisplayModeChanged();
        });
        live2dSizeSeekBar.setOnSeekBarChangeListener(boundedListener(
                live2dSizeLabel, R.string.live2d_size_format,
                LIVE2D_SIZE_MIN, LIVE2D_SIZE_MAX, value -> {
                    OverlayState.setLive2DSize(MainActivity.this, value);
                    if (inAppLive2dView != null) inAppLive2dView.setDisplayScalePercent(value);
                }));
        live2dQualitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange) return;
                int quality = position == 1 ? OverlayState.RENDER_QUALITY_CLEAR : OverlayState.RENDER_QUALITY_NORMAL;
                OverlayState.setLive2DRenderQuality(MainActivity.this, quality);
                if (inAppLive2dView != null) inAppLive2dView.setRenderQuality(quality);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        live2dPhysicsStrengthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                live2dPhysicsStrengthLabel.setText(getString(R.string.live2d_physics_strength_format, progress));
                if (!fromUser) return;
                if (inAppLive2dView != null) {
                    inAppLive2dView.setPhysicsControls(Live2DPhysicsSettingsStore.runtimeJson(
                            MainActivity.this, Live2DPhysicsSettingsStore.TARGET_LIVE2D, progress));
                }
                AxonInputAccessibilityService.previewLive2DPhysicsStrength(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                Live2DPhysicsSettingsStore.setGlobalStrength(
                        MainActivity.this, Live2DPhysicsSettingsStore.TARGET_LIVE2D, seekBar.getProgress());
                if (inAppLive2dView != null) inAppLive2dView.refreshPhysicsControls();
                AxonInputAccessibilityService.refreshLive2DPhysics();
            }
        });
        live2dPhysicsGroupsButton.setOnClickListener(v -> showLive2DPhysicsGroupsDialog());
        live2dExpressionDebugButton.setOnClickListener(v -> showLive2DExpressionDebugDialog());
        live2dParameterDebugButton.setOnClickListener(v -> showLive2DParameterDebugDialog());
        live2dMotionTrackingSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (enabled) {
                if (!Live2DModelStore.exists(MainActivity.this)) {
                    internalChange = true;
                    live2dMotionTrackingSwitch.setChecked(false);
                    internalChange = false;
                    Toast.makeText(MainActivity.this, R.string.live2d_import_first, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    internalChange = true;
                    live2dMotionTrackingSwitch.setChecked(false);
                    internalChange = false;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, LIVE2D_MOTION_CAMERA_PERMISSION_REQUEST);
                    return;
                }
            }
            OverlayState.setLive2DMotionTrackingEnabled(MainActivity.this, enabled);
            syncLive2DTrackingService();
        });
        live2dMouseCaptureSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (enabled && !Live2DModelStore.exists(MainActivity.this)) {
                internalChange = true;
                live2dMouseCaptureSwitch.setChecked(false);
                internalChange = false;
                Toast.makeText(MainActivity.this, R.string.live2d_import_first, Toast.LENGTH_SHORT).show();
                return;
            }
            OverlayState.setLive2DMouseCaptureEnabled(MainActivity.this, enabled);
        });
        live2dHideWatermarkSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            OverlayState.setLive2DHideWatermarkEnabled(MainActivity.this, enabled);
            if (inAppLive2dView != null) inAppLive2dView.setHideWatermark(enabled);
        });
        live2dImportButton.setOnClickListener(v -> openLive2DModelPicker());

        fontSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            FontManager.setEnabled(MainActivity.this, enabled);
            if (enabled && FontManager.hasImportedFont(MainActivity.this)) {
                FontManager.setChoice(MainActivity.this, FontManager.CHOICE_IMPORTED);
            } else if (enabled) {
                Toast.makeText(MainActivity.this, R.string.font_import_first, Toast.LENGTH_SHORT).show();
            }
            syncFontChoices();
            syncFontUi();
            AxonInputAccessibilityService.refreshTheme();
        });
        fontChoiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange || position < 0 || position >= importedFontChoices.size()) return;
                FontManager.setSelectedImportedId(MainActivity.this, importedFontChoices.get(position).id);
                syncFontUi();
                if (FontManager.isEnabled(MainActivity.this)) {
                    AxonInputAccessibilityService.refreshTheme();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        fontImportButton.setOnClickListener(v -> openFontPicker());

        gamepadCompatibilitySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange) return;
                GamepadSettingsStore.setCompatibilityMode(MainActivity.this, position);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        bindSimpleSwitch(gamepadSwapXYSwitch,
                enabled -> GamepadSettingsStore.setSwapXY(this, enabled));
        bindSimpleSwitch(gamepadSwapABSwitch,
                enabled -> GamepadSettingsStore.setSwapAB(this, enabled));
        bindSimpleSwitch(gamepadSwapSticksSwitch,
                enabled -> GamepadSettingsStore.setSwapSticks(this, enabled));
        bindSimpleSwitch(gamepadSwapTriggersSwitch,
                enabled -> GamepadSettingsStore.setSwapTriggers(this, enabled));
        gamepadCustomSwapSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (enabled && (customMappingCaptureStep != 0 || simultaneousClickCaptureStep != 0
                    || forceHoldCaptureStep != 0 || gamepadMappingCaptureArmed)) {
                internalChange = true;
                gamepadCustomSwapSwitch.setChecked(false);
                internalChange = false;
                Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!enabled) {
                gamepadCustomSwapCaptureStep = 0;
                gamepadCustomSwapFirstPending = 0;
                GamepadSettingsStore.setCustomSwapEnabled(MainActivity.this, false);
                updateGamepadCustomSwapUi();
                return;
            }
            // 每次开启都重新录入，避免错误映射残留。只有两个按键都录入完成后才真正生效。
            GamepadSettingsStore.setCustomSwapEnabled(MainActivity.this, false);
            cancelHideDisplayHotkeyCapture(true);
            cancelKeyboardCatExpressionHotkeyCapture(true);
            gamepadCustomSwapCaptureStep = 1;
            gamepadCustomSwapFirstPending = 0;
            updateGamepadCustomSwapUi();
        });
        compatibilityResetButton.setOnClickListener(v -> {
            GamepadSettingsStore.resetCompatibility(MainActivity.this);
            internalChange = true;
            gamepadCompatibilitySpinner.setSelection(GamepadSettingsStore.COMPAT_AUTO, false);
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
        if (event == null) return false;

        boolean firstDown = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        if (isPhysicalGamepadEvent(event)
                || ((gamepadMappingCaptureArmed || customMappingCaptureStep != 0)
                && InputBinding.isGamepadEventForMapping(event))) {
            updateBindableGamepadKeyEvent(event);
            // 自定义映射录入期间不让触发键/目标键继续操作设置页。
            if (customMappingCaptureStep != 0) return true;
            return super.dispatchKeyEvent(event);
        }

        if (isPhysicalKeyboardEvent(event)) {
            if (firstDown) {
                int inputCode = InputBinding.keyboard(event.getKeyCode());
                handleBindableInputPressed(inputCode, InputBinding.evdevCode(inputCode, event.getScanCode()), true);
            }
            // 包括 KEYCODE_BACK 在内都只作为录入内容，不触发 Activity 行为。
            if (customMappingCaptureStep != 0) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        captureBindableMousePress(event);
        updateBindableGamepadMotionEvent(event);
        if (customMappingCaptureStep != 0) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    private int bindableGamepadButtonsDown() {
        return bindableGamepadKeyButtonsDown | bindableGamepadMotionButtonsDown;
    }

    private void updateBindableGamepadKeyEvent(KeyEvent event) {
        int inputCode = InputBinding.fromGamepadEvent(event);
        if (inputCode < 0) return;
        int bit = InputBinding.payload(inputCode);
        int before = bindableGamepadButtonsDown();
        if (event.getAction() == KeyEvent.ACTION_DOWN) bindableGamepadKeyButtonsDown |= bit;
        else if (event.getAction() == KeyEvent.ACTION_UP) bindableGamepadKeyButtonsDown &= ~bit;
        int after = bindableGamepadButtonsDown();
        int rising = after & ~before;
        if ((rising & bit) != 0) {
            handleBindableGamepadPressed(bit, InputBinding.evdevCode(inputCode, event.getScanCode()), true);
        }
    }

    private void updateBindableGamepadMotionEvent(MotionEvent event) {
        if (!InputBinding.isPhysicalGamepadMotionEvent(event)) return;
        // Mapping must be backed by AccessibilityService.onKeyEvent so the original button can be consumed.
        // Axis-only trigger/hat motion remains observable for displays but is not offered as a remap source.
        if (gamepadMappingCaptureArmed) return;
        int before = bindableGamepadButtonsDown();
        bindableGamepadMotionButtonsDown = InputBinding.gamepadButtonsFromMotionEvent(event);
        int after = bindableGamepadButtonsDown();
        int rising = after & ~before;
        while (rising != 0) {
            int bit = Integer.lowestOneBit(rising);
            rising &= ~bit;
            int inputCode = InputBinding.gamepad(bit);
            handleBindableGamepadPressed(bit, InputBinding.evdevCode(inputCode, -1), false);
        }
    }

    private void handleBindableGamepadPressed(int gamepadBit, int evdevCode, boolean interceptCapable) {
        if (gamepadBit == 0) return;
        int unifiedCode = InputBinding.gamepad(gamepadBit);
        long now = SystemClock.uptimeMillis();
        // Activity KeyEvent and the raw evdev monitor can report the same physical edge.  Keep the
        // raw fallback, but collapse only the near-simultaneous duplicate so step 1 cannot instantly
        // become step 2 with the same button.
        if (lastBindableGamepadInputCode == unifiedCode
                && lastBindableGamepadInputAt > 0L
                && now - lastBindableGamepadInputAt >= 0L
                && now - lastBindableGamepadInputAt <= 24L) {
            return;
        }
        lastBindableGamepadInputCode = unifiedCode;
        lastBindableGamepadInputAt = now;

        // 自定义交换本身就是一个独立录入流程，录入时不要让同一颗手柄键
        // 同时写入强制长按 / CPS / 表情快捷键。
        if (gamepadCustomSwapCaptureStep == 0) {
            handleBindableInputPressed(InputBinding.gamepad(gamepadBit), evdevCode, interceptCapable);
            return;
        }

        // “手柄自定义交换”语义上仍是手柄键 ↔ 手柄键，但现在也能录入
        // 只通过轴事件上报的 L2/R2 和十字键。
        if (gamepadCustomSwapCaptureStep == 1) {
            gamepadCustomSwapFirstPending = gamepadBit;
            gamepadCustomSwapCaptureStep = 2;
            updateGamepadCustomSwapUi();
        } else if (gamepadBit == gamepadCustomSwapFirstPending) {
            Toast.makeText(this, R.string.gamepad_compat_custom_swap_same, Toast.LENGTH_SHORT).show();
        } else {
            GamepadSettingsStore.setCustomSwapPair(this, gamepadCustomSwapFirstPending, gamepadBit);
            gamepadCustomSwapCaptureStep = 0;
            gamepadCustomSwapFirstPending = 0;
            internalChange = true;
            gamepadCustomSwapSwitch.setChecked(true);
            internalChange = false;
            updateGamepadCustomSwapUi();
        }
    }

    private void captureBindableMousePress(MotionEvent event) {
        if (!InputBinding.isPhysicalMouseEvent(event)) return;

        int currentButtons = event.getButtonState();
        int rising = currentButtons & ~bindableMouseButtonsDown;
        bindableMouseButtonsDown = currentButtons;

        // Primary mouse clicks may arrive as ACTION_DOWN rather than ACTION_BUTTON_PRESS,
        // so detect rising button-state bits first. dispatchTouchEvent and
        // dispatchGenericMotionEvent may both receive the same MotionEvent; state + event-time
        // dedupe prevents one physical click from being recorded twice.
        if (rising != 0) {
            captureMouseButtonMask(rising, event.getEventTime());
            return;
        }

        if (event.getActionMasked() != MotionEvent.ACTION_BUTTON_PRESS) return;
        int actionButton = event.getActionButton();
        if (actionButton == 0) return;
        if (event.getEventTime() == bindableMouseLastEventTime
                && actionButton == bindableMouseLastActionButton) return;
        captureMouseButtonMask(actionButton, event.getEventTime());
    }

    private void captureMouseButtonMask(int buttonMask, long eventTime) {
        final int[] buttons = {
                MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY,
                MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD
        };
        for (int button : buttons) {
            if ((buttonMask & button) == 0) continue;
            int inputCode = InputBinding.mouseFromAndroidButton(button);
            if (inputCode < 0) continue;
            bindableMouseLastEventTime = eventTime;
            bindableMouseLastActionButton = button;
            handleBindableMouseInputPressed(inputCode, InputBinding.evdevCode(inputCode, -1), eventTime);
        }
    }

    private void handleBindableMouseInputPressed(int inputCode, int evdevCode, long eventTime) {
        if (!InputBinding.isMouse(inputCode)) return;
        long when = eventTime > 0L ? eventTime : SystemClock.uptimeMillis();
        if (lastBindableMouseInputCode == inputCode
                && lastBindableMouseInputAt > 0L
                && Math.abs(when - lastBindableMouseInputAt) <= 80L) {
            return;
        }
        lastBindableMouseInputCode = inputCode;
        lastBindableMouseInputAt = when;
        handleBindableInputPressed(inputCode, evdevCode, false);
    }

    /**
     * 低层 getevent 鼠标监听转发到前台设置页。Android 对中键/侧键的 MotionEvent 分发
     * 在不同厂商系统上并不稳定，因此所有录入功能同时接收这条旁路输入。
     * 只用于录入，不消费系统原始鼠标事件。
     */
    static boolean isBindingActivityActive() {
        MainActivity activity = activeBindingActivity;
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /** True while Simultaneous Click is recording either its source or target. */
    static boolean isSimultaneousClickCaptureActive() {
        MainActivity activity = activeBindingActivity;
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.simultaneousClickCaptureStep != 0;
    }

    static boolean isCustomMappingCaptureActive() {
        MainActivity activity = activeBindingActivity;
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.customMappingCaptureStep != 0;
    }

    /** True while the foreground settings page is recording a non-CPS one-shot binding. */
    static boolean isNonDpsBindingCaptureActive() {
        MainActivity activity = activeBindingActivity;
        boolean mainCapture = activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && (activity.keyboardCatExpressionHotkeyCaptureArmed
                || activity.keyboardCatFunctionBindingCaptureArmed
                || activity.physicsHotkeyCaptureArmed
                || activity.hideDisplayHotkeyCaptureArmed
                || activity.gamepadMappingCaptureArmed
                || activity.customMappingCaptureStep != 0
                || activity.simultaneousClickCaptureStep != 0
                || activity.forceHoldCaptureStep != 0
                || activity.gamepadCustomSwapCaptureStep != 0);
        return mainCapture || SuperCustomDisplayActivity.isInputCaptureActive();
    }

    static void notifyPhysicalMouseButtonForBinding(int button, boolean pressed, long eventTime) {
        if (!pressed) return;
        MainActivity activity = activeBindingActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        int inputCode = InputBinding.mouse(button);
        int evdevCode = InputBinding.evdevCode(inputCode, -1);
        activity.mainHandler.post(() -> {
            if (activeBindingActivity != activity || activity.isFinishing() || activity.isDestroyed()) return;
            activity.handleBindableMouseInputPressed(inputCode, evdevCode, eventTime);
        });
    }

    /** Raw evdev fallback for gamepads whose digital buttons never reach the Activity as KeyEvent. */
    static void notifyPhysicalGamepadButtonForBinding(int buttonBit, boolean pressed, long eventTime) {
        if (!pressed || buttonBit == 0) return;
        MainActivity activity = activeBindingActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        int inputCode = InputBinding.gamepad(buttonBit);
        int evdevCode = InputBinding.evdevCode(inputCode, -1);
        activity.mainHandler.post(() -> {
            if (activeBindingActivity != activity || activity.isFinishing() || activity.isDestroyed()) return;
            activity.handleBindableGamepadPressed(buttonBit, evdevCode, false);
        });
    }

    /**
     * 主界面所有“录入按键”入口共用这一条路径。
     * inputCode 可同时表示键盘、鼠标和手柄；这里只监听，不消费系统输入。
     */
    private void handleBindableInputPressed(int inputCode, int evdevCode, boolean interceptCapable) {
        if (inputCode < 0) return;

        if (customMappingCaptureStep != 0) {
            if (customMappingCaptureStep == 1) {
                if (!interceptCapable || !CustomMappingStore.isInterceptableTrigger(inputCode)) {
                    Toast.makeText(this, R.string.custom_mapping_trigger_not_interceptable, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (customMappingTriggerConflicts(inputCode)) {
                    Toast.makeText(this, R.string.custom_mapping_trigger_conflict, Toast.LENGTH_SHORT).show();
                    return;
                }
                customMappingTriggerPending = inputCode;
                customMappingPendingOutputs.clear();
                customMappingCaptureStep = 2;
                customMappingCaptureDeadline = SystemClock.uptimeMillis() + CUSTOM_MAPPING_CAPTURE_WINDOW_MS;
                mainHandler.removeCallbacks(customMappingCaptureTimeout);
                mainHandler.removeCallbacks(customMappingCaptureTicker);
                mainHandler.postDelayed(customMappingCaptureTimeout, CUSTOM_MAPPING_CAPTURE_WINDOW_MS);
                mainHandler.post(customMappingCaptureTicker);
                updateCustomMappingUi();
                return;
            }

            int targetEvdev = evdevCode;
            if (targetEvdev <= 0 && InputBinding.isKeyboard(inputCode)) {
                targetEvdev = GamepadMappingStore.keyboardEvdevCode(inputCode);
            }
            if (targetEvdev <= 0) {
                Toast.makeText(this, R.string.custom_mapping_target_unsupported, Toast.LENGTH_SHORT).show();
                return;
            }
            customMappingPendingOutputs.add(new CustomMappingStore.Output(inputCode, targetEvdev));
            updateCustomMappingCaptureStatusOnly();
            return;
        }

        if (gamepadMappingCaptureArmed) {
            if (!InputBinding.isGamepad(inputCode)) {
                Toast.makeText(this, R.string.gamepad_mapping_gamepad_only, Toast.LENGTH_SHORT).show();
                return;
            }
            if ((OverlayState.isForceHoldEnabled(this)
                    && inputCode == OverlayState.getForceHoldTriggerKeyCode(this))
                    || inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)
                    || (OverlayState.isHideDisplayHotkeyEnabled(this)
                    && inputCode == OverlayState.getHideDisplayHotkeyInputCode(this))
                    || physicsHotkeyConflicts(inputCode, null, null)
                    || keyboardCatFunctionHotkeyConflicts(inputCode)
                    || FloatingMediaStore.hotkeyConflicts(this, null, inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || simultaneousClickUsesSource(inputCode)) {
                Toast.makeText(this, R.string.gamepad_mapping_source_conflict, Toast.LENGTH_SHORT).show();
                return;
            }
            onGamepadMappingSourceCaptured(inputCode);
            return;
        }

        if (hideDisplayHotkeyCaptureArmed) {
            if (hideDisplayHotkeyConflicts(inputCode)) {
                Toast.makeText(this, R.string.hide_display_hotkey_conflict, Toast.LENGTH_SHORT).show();
            } else {
                OverlayState.setHideDisplayHotkeyInputCode(this, inputCode);
                hideDisplayHotkeyCaptureArmed = false;
                hideDisplayHotkeyPreviousInputCode = -1;
                updateHideDisplayHotkeyButtonUi();
            }
            return;
        }

        if (physicsHotkeyCaptureArmed) {
            if ((OverlayState.isForceHoldEnabled(this)
                    && inputCode == OverlayState.getForceHoldTriggerKeyCode(this))
                    || inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)
                    || inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)
                    || gamepadMappingUsesSource(inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || simultaneousClickUsesSource(inputCode)
                    || physicsHotkeyConflicts(inputCode, physicsHotkeyTarget, physicsHotkeyGroupKey)
                    || keyboardCatFunctionHotkeyConflicts(inputCode)
                    || FloatingMediaStore.hotkeyConflicts(this, null, inputCode)) {
                Toast.makeText(this, R.string.live2d_physics_hotkey_conflict, Toast.LENGTH_SHORT).show();
            } else {
                Live2DPhysicsHotkeyStore.setHotkey(this, physicsHotkeyTarget, physicsHotkeyGroupKey, inputCode);
                Live2DPhysicsHotkeyStore.setEnabled(this, physicsHotkeyTarget, physicsHotkeyGroupKey, true);
                String label = physicsHotkeyGroupLabel;
                cancelPhysicsHotkeyBinding();
                AxonInputAccessibilityService.refreshActiveService();
                Toast.makeText(this, getString(R.string.live2d_physics_hotkey_done,
                        label, InputBinding.label(inputCode)), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (keyboardCatFunctionBindingCaptureArmed) {
            if ((OverlayState.isForceHoldEnabled(this)
                    && inputCode == OverlayState.getForceHoldTriggerKeyCode(this))
                    || inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)
                    || inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)
                    || gamepadMappingUsesSource(inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || simultaneousClickUsesSource(inputCode)
                    || physicsHotkeyConflicts(inputCode, null, null)
                    || FloatingMediaStore.hotkeyConflicts(this, null, inputCode)) {
                Toast.makeText(this, R.string.keyboard_cat_model_function_binding_conflict, Toast.LENGTH_SHORT).show();
            } else {
                String styleId = keyboardCatFunctionBindingStyleId;
                KeyboardCatFunctionBindingStore.putBinding(this, styleId,
                        keyboardCatFunctionBindingToken, inputCode, keyboardCatFunctionBindingMode);
                String label = keyboardCatFunctionBindingLabel;
                cancelKeyboardCatFunctionBindingCapture();
                AxonInputAccessibilityService.refreshActiveService();
                Toast.makeText(this, getString(R.string.keyboard_cat_model_function_binding_done,
                        label, InputBinding.label(inputCode)), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (keyboardCatExpressionHotkeyCaptureArmed) {
            if ((OverlayState.isForceHoldEnabled(this)
                    && inputCode == OverlayState.getForceHoldTriggerKeyCode(this))
                    || inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)
                    || gamepadMappingUsesSource(inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || simultaneousClickUsesSource(inputCode)
                    || physicsHotkeyConflicts(inputCode, null, null)
                    || keyboardCatFunctionHotkeyConflicts(inputCode)
                    || FloatingMediaStore.hotkeyConflicts(this, null, inputCode)) {
                Toast.makeText(this, R.string.floating_video_hotkey_conflict,
                        Toast.LENGTH_SHORT).show();
            } else {
                OverlayState.setKeyboardCatExpressionHotkeyKeyCode(this, inputCode);
                keyboardCatExpressionHotkeyCaptureArmed = false;
                keyboardCatExpressionHotkeyPreviousKeyCode = -1;
                keyboardCatExpressionHotkeyCapturedKeyCode = inputCode;
                updateKeyboardCatExpressionHotkeyUi();
            }
            return;
        }

        if (simultaneousClickCaptureStep != 0) {
            if (simultaneousClickCaptureStep == 1) {
                if (simultaneousClickSourceConflicts(inputCode)) {
                    Toast.makeText(this, R.string.simultaneous_click_conflict, Toast.LENGTH_SHORT).show();
                } else {
                    simultaneousClickSourcePending = inputCode;
                    simultaneousClickCaptureStep = 2;
                    updateSimultaneousClickUi();
                }
            } else if (inputCode == simultaneousClickSourcePending) {
                Toast.makeText(this, R.string.simultaneous_click_same_key, Toast.LENGTH_SHORT).show();
            } else {
                int targetEvdev = evdevCode;
                if (targetEvdev <= 0 && InputBinding.isKeyboard(inputCode)) {
                    targetEvdev = GamepadMappingStore.keyboardEvdevCode(inputCode);
                }
                if (targetEvdev <= 0) {
                    Toast.makeText(this, R.string.simultaneous_click_target_unsupported, Toast.LENGTH_SHORT).show();
                } else {
                    SimultaneousClickStore.addBinding(this, simultaneousClickSourcePending, inputCode, targetEvdev);
                    SimultaneousClickStore.setEnabled(this, true);
                    simultaneousClickCaptureStep = 0;
                    simultaneousClickSourcePending = -1;
                    updateSimultaneousClickUi();
                    AxonInputAccessibilityService.refreshActiveService();
                    ensurePrivilegedInputForGamepadMapping();
                    handleDisplayModeChanged();
                }
            }
            return;
        }

        if (forceHoldCaptureStep != 0) {
            if (forceHoldCaptureStep == 1) {
                if (gamepadMappingUsesSource(inputCode) || customMappingUsesTrigger(inputCode)
                        || simultaneousClickUsesSource(inputCode)) {
                    Toast.makeText(this, R.string.gamepad_mapping_source_conflict, Toast.LENGTH_SHORT).show();
                } else if (evdevCode <= 0) {
                    Toast.makeText(this, R.string.force_hold_scan_unsupported, Toast.LENGTH_SHORT).show();
                } else {
                    forceHoldTargetKeyPending = inputCode;
                    forceHoldTargetScanPending = evdevCode;
                    forceHoldCaptureStep = 2;
                    updateForceHoldUi();
                }
            } else if (inputCode == forceHoldTargetKeyPending) {
                Toast.makeText(this, R.string.force_hold_same_key, Toast.LENGTH_SHORT).show();
            } else if (inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)
                    || inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)
                    || gamepadMappingUsesSource(inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || simultaneousClickUsesSource(inputCode)
                    || FloatingMediaStore.hotkeyConflicts(this, null, inputCode)) {
                Toast.makeText(this, R.string.floating_video_hotkey_conflict, Toast.LENGTH_SHORT).show();
            } else {
                OverlayState.setForceHoldBinding(
                        this, forceHoldTargetKeyPending, forceHoldTargetScanPending, inputCode);
                OverlayState.setForceHoldEnabled(this, true);
                forceHoldCaptureStep = 0;
                forceHoldTargetKeyPending = -1;
                forceHoldTargetScanPending = -1;
                updateForceHoldUi();
                handleDisplayModeChanged();
            }
            return;
        }

        if (dpsCaptureArmed && dpsSwitch != null && dpsSwitch.isChecked()) {
            int currentTarget = OverlayState.getDpsTargetKeyCode(this);
            if (currentTarget == OverlayState.DPS_TARGET_NONE) {
                OverlayState.setDpsTargetKeyCode(this, OverlayState.dpsTargetFromBinding(inputCode));
                dpsCaptureArmed = false;
                mainHandler.removeCallbacks(cpsBindingPoll);
                updateDpsTargetUi();
            } else {
                dpsCaptureArmed = false;
                mainHandler.removeCallbacks(cpsBindingPoll);
                updateDpsTargetUi();
            }
            return;
        }

        if (captureSwitch != null && OverlayState.isCustomCaptureEnabled(this)) {
            if (OverlayState.addDraftKey(this, inputCode)) updateRecordedKeys(true);
        }
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
            shizukuPermissionRequestInFlight = false;
            accessibilityVerificationGeneration++;
            accessibilityVerificationInFlight = false;
            return;
        }
        ensureAccessibility();
    }

    private boolean needsAccessibility() {
        return OverlayState.isAnyDisplayEnabled(this)
                || SensitivitySettingsStore.isEnabled(this)
                || OverlayState.isForceHoldEnabled(this)
                || customMappingCaptureStep != 0
                || CustomMappingStore.isEnabled(this)
                || simultaneousClickCaptureStep != 0
                || SimultaneousClickStore.isEnabled(this)
                || !GamepadMappingStore.load(this).isEmpty();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!RootBridge.isRootActive() || TouchDisplayStore.isEnabled(this)) {
            ShizukuBridge.addListener(this);
        }
        // 全局权限策略：先探测/请求 Root；只有 Root 不可用时才回退 Shizuku。
        RootBridge.ensureActivated(this, rootActive -> {
            syncRootActivationUi(rootActive);
            if (rootActive) {
                waitingForShizuku = false;
                shizukuPermissionRequestInFlight = false;
                // Root normally owns privileged input, but touch display is intentionally
                // Shizuku-only and must keep receiving Shizuku lifecycle callbacks.
                if (!TouchDisplayStore.isEnabled(this)) ShizukuBridge.removeListener(this);
            }
            if (needsAccessibility()) ensureAccessibility();
        });
    }

    @Override
    protected void onStop() {
        ShizukuBridge.removeListener(this);
        // onPause normally hands Live2D to the accessibility overlay.  Repeat at onStop because
        // some ROMs dispatch accessibility work after the Activity transition has already moved.
        AxonInputAccessibilityService.refreshLive2DOwnership();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        removeInAppLive2D();
        // Activity 销毁不清理运行配置，任务移除时由服务统一处理。
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (activeBindingActivity == this) activeBindingActivity = null;
        removeInAppLive2D();
        AxonInputAccessibilityService.refreshActiveService();
        // Live2D ownership is latency-sensitive and must not be swallowed by a coalesced full-state
        // refresh.  Hand off immediately, then once more after the window transition settles.
        AxonInputAccessibilityService.refreshLive2DOwnership();
        mainHandler.postDelayed(AxonInputAccessibilityService::refreshLive2DOwnership, 120L);
        mainHandler.removeCallbacks(sensitivityStatusTicker);
        mainHandler.removeCallbacks(touchDisplayStatusTicker);
        mainHandler.removeCallbacks(cpsBindingPoll);
        cancelKeyboardCatExpressionHotkeyCapture(true);
        cancelKeyboardCatFunctionBindingCapture();
        cancelPhysicsHotkeyBinding();
        cancelHideDisplayHotkeyCapture(true);
        gamepadMappingCaptureArmed = false;
        pendingGamepadMappingSource = -1;
        if (gamepadMappingKeyboardDialog != null) {
            try { gamepadMappingKeyboardDialog.dismiss(); } catch (Throwable ignored) {}
            gamepadMappingKeyboardDialog = null;
        }
        keyboardCatExpressionHotkeyCapturedKeyCode = -1;

        cancelCustomMappingCapture();

        // Binding prompts are foreground-only.  Without this reset, leaving the app halfway through
        // recording (especially Force Hold step 2) makes a later unrelated key silently become the binding.
        simultaneousClickCaptureStep = 0;
        simultaneousClickSourcePending = -1;
        forceHoldCaptureStep = 0;
        forceHoldTargetKeyPending = -1;
        forceHoldTargetScanPending = -1;
        gamepadCustomSwapCaptureStep = 0;
        gamepadCustomSwapFirstPending = 0;
        AxonInputAccessibilityService.refreshActiveService();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        activeBindingActivity = this;
        AxonInputAccessibilityService.refreshActiveService();
        // Remove the external owner as soon as the Activity becomes visible; syncInAppLive2D below
        // will create the in-process renderer after UI state has been restored.
        AxonInputAccessibilityService.refreshLive2DOwnership();
        AxonApplication.syncTaskVisibility(this, false);

        internalChange = true;
        themeSpinner.setSelection(OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK ? 1 : 0, false);
        displaySwitch.setChecked(OverlayState.isEnabled(this));
        spaceDisplaySwitch.setChecked(OverlayState.isKeyboardSpaceEnabled(this));
        spaceDpsSwitch.setChecked(OverlayState.isKeyboardSpaceDpsEnabled(this));
        spaceDashSwitch.setChecked(OverlayState.isKeyboardSpaceDashEnabled(this));
        keyboardMouseButtonsSwitch.setChecked(OverlayState.isKeyboardMouseButtonsEnabled(this));
        keyboardMouseCpsSwitch.setChecked(OverlayState.isKeyboardMouseCpsEnabled(this));
        setControlEnabled(spaceDpsSwitch, spaceDisplaySwitch.isChecked());
        setControlEnabled(spaceDashSwitch, spaceDisplaySwitch.isChecked());
        setControlEnabled(keyboardMouseCpsSwitch, keyboardMouseButtonsSwitch.isChecked());
        inputFullKeyboardSwitch.setChecked(OverlayState.isInputFullKeyboardEnabled(this));
        mouseSwitch.setChecked(OverlayState.isMouseEnabled(this));
        keyboardCatSwitch.setChecked(OverlayState.isKeyboardCatEnabled(this));
        keyboardCatQualitySpinner.setSelection(
                OverlayState.getKeyboardCatRenderQuality(this) == OverlayState.RENDER_QUALITY_CLEAR ? 1 : 0, false);
        keyboardCatMouseModeSwitch.setChecked(OverlayState.isKeyboardCatMouseMode(this));
        keyboardCatGlobalReverseSwitch.setChecked(OverlayState.isKeyboardCatGlobalReverse(this));
        syncKeyboardCatStyles();
        keyPromptSwitch.setChecked(OverlayState.isKeyPromptEnabled(this));
        mouseTrajectorySwitch.setChecked(OverlayState.isMouseTrajectoryEnabled(this));
        mouseTrajectoryLeftColorSwitch.setChecked(OverlayState.isMouseTrajectoryLeftColorEnabled(this));
        mouseTrajectoryRightColorSwitch.setChecked(OverlayState.isMouseTrajectoryRightColorEnabled(this));
        updateColorDotSequence(mouseTrajectoryLeftColorDot, OverlayState.getMouseTrajectoryLeftColors(this));
        updateColorDotSequence(mouseTrajectoryRightColorDot, OverlayState.getMouseTrajectoryRightColors(this));
        customDisplaySwitch.setChecked(OverlayState.isCustomEnabled(this));
        superCustomDisplaySwitch.setChecked(OverlayState.isSuperCustomEnabled(this));
        gamepadLeftStickSwitch.setChecked(OverlayState.isGamepadLeftStickEnabled(this));
        gamepadRightStickSwitch.setChecked(OverlayState.isGamepadRightStickEnabled(this));
        gamepadFaceSwitch.setChecked(OverlayState.isGamepadFaceEnabled(this));
        gamepadDpadSwitch.setChecked(OverlayState.isGamepadDpadEnabled(this));
        gamepadLeftShoulderSwitch.setChecked(OverlayState.isGamepadLeftShoulderEnabled(this));
        gamepadRightShoulderSwitch.setChecked(OverlayState.isGamepadRightShoulderEnabled(this));
        gamepadBackSwitch.setChecked(OverlayState.isGamepadBackEnabled(this));
        gamepadFaceReverseSwitch.setChecked(OverlayState.isGamepadFaceReversed(this));
        gamepadFaceSymbolSwitch.setChecked(OverlayState.isGamepadFaceSymbolIcons(this));
        gamepadFaceYDpsSwitch.setChecked(GamepadSettingsStore.isFaceYDpsEnabled(this));
        gamepadFaceXDpsSwitch.setChecked(GamepadSettingsStore.isFaceXDpsEnabled(this));
        gamepadFaceBDpsSwitch.setChecked(GamepadSettingsStore.isFaceBDpsEnabled(this));
        gamepadFaceADpsSwitch.setChecked(GamepadSettingsStore.isFaceADpsEnabled(this));
        gamepadL2ProgressSwitch.setChecked(GamepadSettingsStore.isL2ProgressEnabled(this));
        gamepadR2ProgressSwitch.setChecked(GamepadSettingsStore.isR2ProgressEnabled(this));
        gamepadL1DpsSwitch.setChecked(GamepadSettingsStore.isL1DpsEnabled(this));
        gamepadR1DpsSwitch.setChecked(GamepadSettingsStore.isR1DpsEnabled(this));
        gamepadCompatibilitySpinner.setSelection(GamepadSettingsStore.getCompatibilityMode(this), false);
        gamepadSwapXYSwitch.setChecked(GamepadSettingsStore.isSwapXY(this));
        gamepadSwapABSwitch.setChecked(GamepadSettingsStore.isSwapAB(this));
        gamepadSwapSticksSwitch.setChecked(GamepadSettingsStore.isSwapSticks(this));
        gamepadSwapTriggersSwitch.setChecked(GamepadSettingsStore.isSwapTriggers(this));
        gamepadCustomSwapSwitch.setChecked(GamepadSettingsStore.isCustomSwapEnabled(this));
        updateGamepadCustomSwapUi();
        captureSwitch.setChecked(OverlayState.isCustomCaptureEnabled(this));
        dpsSwitch.setChecked(OverlayState.isDpsEnabled(this));
        customMappingSwitch.setChecked(customMappingCaptureStep != 0 || CustomMappingStore.isEnabled(this));
        int customMappingDelay = CustomMappingStore.getDelayMs(this);
        customMappingDelaySeekBar.setProgress(customMappingDelay / CUSTOM_MAPPING_DELAY_STEP_MS);
        customMappingDelayLabel.setText(getString(R.string.custom_mapping_delay_format, customMappingDelay));
        updateCustomMappingUi();
        simultaneousClickSwitch.setChecked(simultaneousClickCaptureStep != 0 || SimultaneousClickStore.isEnabled(this));
        updateSimultaneousClickUi();
        forceHoldSwitch.setChecked(forceHoldCaptureStep != 0 || OverlayState.isForceHoldEnabled(this));
        hideDisplayHotkeySwitch.setChecked(OverlayState.isHideDisplayHotkeyEnabled(this));
        updateForceHoldUi();
        updateGamepadMappingUi();
        dpsCaptureArmed = OverlayState.isDpsEnabled(this)
                && OverlayState.getDpsTargetKeyCode(this) == OverlayState.DPS_TARGET_NONE;
        updateDpsTargetUi();
        mainHandler.removeCallbacks(cpsBindingPoll);
        if (dpsCaptureArmed) mainHandler.post(cpsBindingPoll);
        dragSwitch.setChecked(OverlayState.isDragEnabled(this));
        touchDisplaySwitch.setChecked(TouchDisplayStore.isEnabled(this));
        updateTouchDisplayStatusUi();
        globalHtmlSwitch.setChecked(GlobalHtmlStore.isEnabled(this));
        boolean live2dAvailable = Live2DModelStore.exists(this);
        if (!live2dAvailable && OverlayState.isLive2DEnabled(this)) {
            OverlayState.setLive2DEnabled(this, false);
        }
        live2dSwitch.setChecked(live2dAvailable && OverlayState.isLive2DEnabled(this));
        live2dQualitySpinner.setSelection(
                OverlayState.getLive2DRenderQuality(this) == OverlayState.RENDER_QUALITY_CLEAR ? 1 : 0, false);
        syncLive2DPhysicsUi(live2dAvailable);
        boolean motionTrackingEnabled = OverlayState.isLive2DMotionTrackingEnabled(this);
        if (motionTrackingEnabled && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            OverlayState.setLive2DMotionTrackingEnabled(this, false);
            motionTrackingEnabled = false;
        }
        live2dMotionTrackingSwitch.setChecked(motionTrackingEnabled);
        live2dMouseCaptureSwitch.setChecked(live2dAvailable && OverlayState.isLive2DMouseCaptureEnabled(this));
        live2dHideWatermarkSwitch.setChecked(OverlayState.isLive2DHideWatermarkEnabled(this));
        syncLive2DTrackingService();
        syncGlobalHtmlChoices();
        globalHtmlFontSpinner.setSelection(GlobalHtmlStore.getFontMode(this), false);
        fontSwitch.setChecked(FontManager.isEnabled(this));
        syncFontChoices();
        syncGlobalHtmlUi();
        syncFontUi();
        syncSuperCustomConfigRows();
        refreshHideDisplayHotkeyUi();
        syncSeekBarUiFromState();
        syncMotionControlsFromState();
        if (gamepadLeftStickCenterColorDot != null) {
            updateColorDotSequence(gamepadLeftStickCenterColorDot,
                    OverlayState.getGamepadStickCenterColors(this, GamepadOverlayView.DISPLAY_LEFT_STICK));
        }
        if (gamepadRightStickCenterColorDot != null) {
            updateColorDotSequence(gamepadRightStickCenterColorDot,
                    OverlayState.getGamepadStickCenterColors(this, GamepadOverlayView.DISPLAY_RIGHT_STICK));
        }
        internalChange = false;
        mainHandler.removeCallbacks(sensitivityStatusTicker);
        if (sensitivitySwitch.isChecked()) mainHandler.post(sensitivityStatusTicker);
        mainHandler.removeCallbacks(touchDisplayStatusTicker);
        if (touchDisplaySwitch.isChecked()) {
            ShizukuBridge.addListener(this);
            ensureShizukuForTouchDisplay();
            mainHandler.post(touchDisplayStatusTicker);
        }
        // Re-check the real service connection after returning from Shizuku/accessibility settings.
        // This is also the recovery entry for ROMs that persisted the secure setting but failed to
        // bind the AccessibilityService on the first attempt.
        if (needsAccessibility()) mainHandler.post(this::ensureAccessibility);
        syncInAppLive2D();

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LIVE2D_MOTION_CAMERA_PERMISSION_REQUEST) return;
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        OverlayState.setLive2DMotionTrackingEnabled(this, granted);
        internalChange = true;
        live2dMotionTrackingSwitch.setChecked(granted);
        internalChange = false;
        if (!granted) Toast.makeText(this, R.string.live2d_motion_permission_required, Toast.LENGTH_SHORT).show();
        syncLive2DTrackingService();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == SUPER_CUSTOM_IMPORT_REQUEST) {
            int slot = pendingSuperCustomImportSlot;
            pendingSuperCustomImportSlot = 0;
            if (slot < 1 || slot > SuperCustomConfigStore.SLOT_COUNT) return;
            try {
                SuperCustomConfigStore.importSlot(this, slot, readText(uri, SuperCustomConfigStore.MAX_CONFIG_BYTES));
                syncSuperCustomConfigRows();
                Toast.makeText(this, getString(R.string.super_custom_import_slot_success, slot), Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                Toast.makeText(this, R.string.super_custom_config_import_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == BONGOCAT_STYLE_IMPORT_REQUEST) {
            final Uri styleUri = uri;
            final String styleName = queryDisplayName(uri, "BongoCat-style.zip");
            Toast.makeText(this, R.string.keyboard_cat_style_importing, Toast.LENGTH_SHORT).show();
            // Large community skins can contain multiple 8K atlases. Extracting and adapting them on
            // the Activity thread can stall the UI long enough to look like the import was ignored.
            new Thread(() -> {
                try {
                    BongoCatStyleManager.StyleInfo imported = BongoCatStyleManager.importZip(
                            getApplicationContext(), styleUri, styleName);
                    BongoCatStyleManager.CapabilityReport importedReport =
                            BongoCatStyleManager.capabilityReport(getApplicationContext(), imported.id);
                    mainHandler.post(() -> {
                        OverlayState.setKeyboardCatStyleId(MainActivity.this, imported.id);
                        internalChange = true;
                        syncKeyboardCatStyles();
                        internalChange = false;
                        Toast.makeText(MainActivity.this,
                                getString(R.string.keyboard_cat_style_import_success, imported.displayLabel()),
                                Toast.LENGTH_SHORT).show();
                        if (OverlayState.isKeyboardCatEnabled(MainActivity.this)) ensureAccessibility();
                        showKeyboardCatCapabilityReport(imported, importedReport);
                    });
                } catch (Throwable error) {
                    mainHandler.post(() -> {
                        internalChange = false;
                        Toast.makeText(MainActivity.this,
                                getString(R.string.keyboard_cat_style_import_failed,
                                        error.getMessage() == null ? "" : error.getMessage()),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }, "AxonBongoCatImport").start();
            return;
        }
        if (requestCode == LIVE2D_MODEL_IMPORT_REQUEST) {
            final Uri modelUri = uri;
            final String modelName = queryDisplayName(uri, "Live2D-model.zip");
            Toast.makeText(this, R.string.live2d_importing, Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    Live2DModelStore.ImportResult imported = Live2DModelStore.importZip(
                            getApplicationContext(), modelUri, modelName);
                    mainHandler.post(() -> {
                        if (isFinishing()) return;
                        OverlayState.setLive2DEnabled(MainActivity.this, true);
                        internalChange = true;
                        live2dSwitch.setChecked(true);
                        internalChange = false;
                        syncLive2DPhysicsUi(true);
                        syncInAppLive2D();
                        syncLive2DTrackingService();
                        ensureAccessibility();
                        AxonInputAccessibilityService.refreshActiveService();
                        int message = imported.adaptedTextures > 0
                                ? R.string.live2d_import_success_adapted
                                : R.string.live2d_import_success;
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                    });
                } catch (Throwable error) {
                    mainHandler.post(() -> {
                        if (isFinishing()) return;
                        String detail = error.getMessage() == null ? "" : error.getMessage();
                        Toast.makeText(MainActivity.this,
                                getString(R.string.live2d_import_failed, detail), Toast.LENGTH_LONG).show();
                    });
                }
            }, "AxonLive2DImport").start();
            return;
        }

        if (requestCode == FONT_IMPORT_REQUEST) {
            try {
                FontManager.importFont(this, uri, queryDisplayName(uri, "font.ttf"));
                internalChange = true;
                syncFontChoices();
                internalChange = false;
                syncFontUi();
                if (FontManager.isEnabled(this)) AxonInputAccessibilityService.refreshTheme();
                Toast.makeText(this, R.string.font_import_success, Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                internalChange = false;
                Toast.makeText(this, R.string.font_import_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == HTML_REQUEST_GLOBAL) {
            try {
                String html = readText(uri, GlobalHtmlStore.MAX_BYTES);
                String name = queryDisplayName(uri, "display.html");
                GlobalHtmlStore.save(this, name, html);
                internalChange = true;
                globalHtmlSwitch.setChecked(true);
                syncGlobalHtmlChoices();
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
        boolean touchShizuku = TouchDisplayStore.isEnabled(this);
        if (RootBridge.isRootActive() && !touchShizuku) return;
        if (!waitingForShizuku && !shizukuPermissionRequestInFlight && !touchShizuku) return;
        if (permissionGranted) shizukuPermissionRequestInFlight = false;
        waitingForShizuku = false;
        if (touchShizuku) {
            if (permissionGranted) {
                AxonInputAccessibilityService.refreshActiveService();
            } else {
                // Shizuku binder is now ready but permission is still missing: continue the touch
                // permission flow instead of leaving the monitor waiting forever.
                ensureShizukuForTouchDisplay();
            }
        }
        if (needsAccessibility() && !RootBridge.isRootActive()) ensureAccessibility();
    }

    @Override
    public void onShizukuPermissionResult(int requestCode, boolean granted) {
        if (requestCode != SHIZUKU_REQUEST_CODE) return;
        boolean touchShizuku = TouchDisplayStore.isEnabled(this);
        if (RootBridge.isRootActive() && !touchShizuku) return;
        waitingForShizuku = false;
        shizukuPermissionRequestInFlight = false;
        if (granted) {
            if (touchShizuku) AxonInputAccessibilityService.refreshActiveService();
            if (needsAccessibility() && !RootBridge.isRootActive()) ensureAccessibility();
        } else {
            Toast.makeText(this, R.string.shizuku_denied, Toast.LENGTH_SHORT).show();
            if (!isAccessibilityServiceEnabled()) openAccessibilitySettings();
        }
    }

    @Override
    public void onShizukuDead() {
        waitingForShizuku = false;
        shizukuPermissionRequestInFlight = false;
    }

    private void syncRootActivationUi(boolean rootActive) {
        if (sensitivityModeSpinner == null) return;
        internalChange = true;
        sensitivityModeSpinner.setSelection(rootActive ? 1 : 0, false);
        setControlEnabled(sensitivityModeSpinner, !rootActive);
        internalChange = false;
    }

    private void ensureAccessibility() {
        // 权限尚未判定时先等待 Root 探测，避免 Root 设备先弹 Shizuku 授权。
        if (!RootBridge.isProbeComplete()) {
            RootBridge.ensureActivated(this, rootActive -> {
                syncRootActivationUi(rootActive);
                ensureAccessibility();
            });
            return;
        }

        boolean sensitivity = SensitivitySettingsStore.isEnabled(this);
        // The overlay itself depends only on a live AccessibilityService. Root/Shizuku is an input
        // channel for global REL_X/REL_Y and must never gate creation of the keyboard-cat window.
        boolean keyboardCatMouseCapture = OverlayState.isKeyboardCatEnabled(this) && !sensitivity;
        boolean superCustomMouseCapture = OverlayState.isSuperCustomEnabled(this)
                && SuperCustomConfigStore.activeContainsMouse(this) && !sensitivity;
        boolean gamepadMappingNeedsPrivileged = !GamepadMappingStore.load(this).isEmpty();
        // Root 是全局激活方式：只要已获得 uid 0，显示类无障碍自动启用也直接走 Root。
        boolean rootMode = RootBridge.isRootActive()
                || SensitivitySettingsStore.getMode(this) == SensitivitySettingsStore.MODE_ROOT;

        if (AxonInputAccessibilityService.isServiceConnected()) {
            accessibilityVerificationGeneration++;
            accessibilityVerificationInFlight = false;
            AxonInputAccessibilityService.refreshActiveService();
            if ((sensitivity || keyboardCatMouseCapture || superCustomMouseCapture
                    || gamepadMappingNeedsPrivileged) && !rootMode) {
                ensureShizukuForSensitivity();
            }
            return;
        }

        // Secure settings can say "enabled" while the service process is not bound. Verify the
        // real connection before claiming success; if needed we rebind only our own component.
        if (isAccessibilityServiceEnabled()) {
            verifyAccessibilityConnection(false, true, rootMode);
            return;
        }

        if (rootMode) {
            grantAccessibilityWithRoot(false);
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
            if (!shizukuPermissionRequestInFlight) {
                shizukuPermissionRequestInFlight = true;
                if (!ShizukuBridge.requestPermission(SHIZUKU_REQUEST_CODE)) {
                    waitingForShizuku = false;
                    shizukuPermissionRequestInFlight = false;
                    openAccessibilitySettings();
                }
            }
            return;
        }

        shizukuPermissionRequestInFlight = false;
        grantAccessibilityWithShizuku(false);
    }

    private void updateTouchDisplayStatusUi() {
        if (touchDisplayStatusText == null) return;
        String shizuku;
        if (!ShizukuBridge.isAvailable()) {
            shizuku = "Shizuku未连接";
        } else if (!ShizukuBridge.isReady()) {
            shizuku = "Shizuku连接中";
        } else {
            shizuku = ShizukuBridge.hasPermissionCached() ? "Shizuku已授权" : "Shizuku未授权";
        }
        String service = AxonInputAccessibilityService.isServiceConnected() ? "无障碍已连接" : "无障碍未连接";
        touchDisplayStatusText.setText("状态：" + AxonInputAccessibilityService.getTouchMonitorStatus()
                + "\n" + shizuku + " · " + service);
    }

    private void ensureShizukuForTouchDisplay() {
        // Touchscreen capture is deliberately Shizuku-only, even if Root is active for another
        // feature. Request/keep Shizuku independently and let the accessibility service worker
        // begin reading as soon as permission becomes available.
        if (!ShizukuBridge.isAvailable()) {
            Toast.makeText(this, R.string.sensitivity_requires_shizuku, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ShizukuBridge.isReady()) {
            waitingForShizuku = true;
            Toast.makeText(this, R.string.shizuku_connecting, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ShizukuBridge.hasPermission()) {
            waitingForShizuku = true;
            if (!shizukuPermissionRequestInFlight) {
                shizukuPermissionRequestInFlight = true;
                if (!ShizukuBridge.requestPermission(SHIZUKU_REQUEST_CODE)) {
                    waitingForShizuku = false;
                    shizukuPermissionRequestInFlight = false;
                }
            }
            return;
        }
        shizukuPermissionRequestInFlight = false;
        AxonInputAccessibilityService.refreshActiveService();
    }

    private void ensureShizukuForSensitivity() {
        if (RootBridge.isRootActive()) {
            AxonInputAccessibilityService.refreshActiveService();
            return;
        }
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
            if (!shizukuPermissionRequestInFlight) {
                shizukuPermissionRequestInFlight = true;
                if (!ShizukuBridge.requestPermission(SHIZUKU_REQUEST_CODE)) {
                    waitingForShizuku = false;
                    shizukuPermissionRequestInFlight = false;
                }
            }
            return;
        }
        shizukuPermissionRequestInFlight = false;
        AxonInputAccessibilityService.refreshActiveService();
    }

    private String accessibilityGrantCommand(boolean forceRebind) {
        final String component = new ComponentName(this, AxonInputAccessibilityService.class).flattenToString();
        StringBuilder command = new StringBuilder();
        command.append("SERVICE='").append(component).append("'; ")
                .append("CURRENT=\"$(settings get secure enabled_accessibility_services)\"; ")
                .append("if [ \"$CURRENT\" = null ]; then CURRENT=''; fi; ");
        if (forceRebind) {
            // Remove only Axon's component, preserving every other enabled accessibility service.
            command.append("NEW=''; OLDIFS=\"$IFS\"; IFS=':'; ")
                    .append("for ITEM in $CURRENT; do [ -z \"$ITEM\" ] && continue; ")
                    .append("[ \"$ITEM\" = \"$SERVICE\" ] && continue; ")
                    .append("if [ -z \"$NEW\" ]; then NEW=\"$ITEM\"; else NEW=\"$NEW:$ITEM\"; fi; done; ")
                    .append("IFS=\"$OLDIFS\"; ")
                    .append("if [ -n \"$NEW\" ]; then settings put secure enabled_accessibility_services \"$NEW\"; ")
                    .append("else settings delete secure enabled_accessibility_services; fi; sleep 0.25; CURRENT=\"$NEW\"; ");
        }
        command.append("if [ -z \"$CURRENT\" ]; then NEW=\"$SERVICE\"; ")
                .append("else case \":$CURRENT:\" in *\":$SERVICE:\"*) NEW=\"$CURRENT\";; *) NEW=\"$CURRENT:$SERVICE\";; esac; fi; ")
                .append("settings put secure enabled_accessibility_services \"$NEW\" && ")
                .append("settings put secure accessibility_enabled 1");
        return command.toString();
    }

    private void grantAccessibilityWithShizuku() {
        grantAccessibilityWithShizuku(false);
    }

    private void grantAccessibilityWithShizuku(boolean forceRebind) {
        if (RootBridge.isRootActive()) {
            grantAccessibilityWithRoot(forceRebind);
            return;
        }
        if (accessibilityGrantInFlight) return;
        accessibilityGrantInFlight = true;
        final String command = accessibilityGrantCommand(forceRebind);
        new Thread(() -> {
            boolean success;
            try {
                success = ShizukuBridge.runShell(command) == 0;
            } catch (Throwable ignored) {
                success = false;
            }
            final boolean result = success;
            mainHandler.post(() -> {
                accessibilityGrantInFlight = false;
                if (isFinishing()) return;
                if (result) {
                    // Do not show the old false-positive "success" toast yet. The service must
                    // actually connect; a successful settings write alone is insufficient.
                    verifyAccessibilityConnection(true, !forceRebind, false);
                } else {
                    Toast.makeText(this, R.string.shizuku_grant_failed, Toast.LENGTH_SHORT).show();
                    openAccessibilitySettings();
                }
            });
        }, forceRebind ? "ShizukuAccessibilityRebind" : "ShizukuAccessibilityGrant").start();
    }

    private void grantAccessibilityWithRoot() {
        grantAccessibilityWithRoot(false);
    }

    private void grantAccessibilityWithRoot(boolean forceRebind) {
        if (accessibilityGrantInFlight) return;
        accessibilityGrantInFlight = true;
        final String command = accessibilityGrantCommand(forceRebind);
        new Thread(() -> {
            boolean success;
            try {
                success = RootBridge.runShell(command) == 0;
            } catch (Throwable ignored) {
                success = false;
            }
            final boolean result = success;
            mainHandler.post(() -> {
                accessibilityGrantInFlight = false;
                if (isFinishing()) return;
                if (result) {
                    verifyAccessibilityConnection(true, !forceRebind, true);
                } else {
                    Toast.makeText(this, R.string.root_denied_open_accessibility, Toast.LENGTH_SHORT).show();
                    openAccessibilitySettings();
                }
            });
        }, forceRebind ? "RootAccessibilityRebind" : "RootAccessibilityGrant").start();
    }

    private void verifyAccessibilityConnection(boolean showSuccess, boolean allowRepair, boolean rootMode) {
        if (AxonInputAccessibilityService.isServiceConnected()) {
            accessibilityVerificationGeneration++;
            accessibilityVerificationInFlight = false;
            AxonInputAccessibilityService.refreshActiveService();
            if (showSuccess) Toast.makeText(this,
                    rootMode ? R.string.root_granted : R.string.shizuku_grant_success,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (accessibilityVerificationInFlight) return;
        accessibilityVerificationInFlight = true;
        final int generation = ++accessibilityVerificationGeneration;
        pollAccessibilityConnection(generation, 0, showSuccess, allowRepair, rootMode);
    }

    private void pollAccessibilityConnection(int generation, int attempt, boolean showSuccess,
                                             boolean allowRepair, boolean rootMode) {
        if (generation != accessibilityVerificationGeneration || isFinishing()) return;
        if (AxonInputAccessibilityService.isServiceConnected()) {
            accessibilityVerificationInFlight = false;
            AxonInputAccessibilityService.refreshActiveService();
            if (showSuccess) Toast.makeText(this,
                    rootMode ? R.string.root_granted : R.string.shizuku_grant_success,
                    Toast.LENGTH_SHORT).show();
            // Once the overlay is alive, independently bring up the global mouse input channel.
            boolean needsInput = SensitivitySettingsStore.isEnabled(this)
                    || OverlayState.isKeyboardCatEnabled(this)
                    || !GamepadMappingStore.load(this).isEmpty()
                    || (OverlayState.isSuperCustomEnabled(this)
                    && SuperCustomConfigStore.activeContainsMouse(this));
            if (needsInput && !rootMode) ensureShizukuForSensitivity();
            return;
        }
        if (attempt < 19) {
            mainHandler.postDelayed(() -> pollAccessibilityConnection(generation, attempt + 1,
                    showSuccess, allowRepair, rootMode), 200L);
            return;
        }
        accessibilityVerificationInFlight = false;
        if (allowRepair) {
            if (rootMode) {
                grantAccessibilityWithRoot(true);
                return;
            }
            if (ShizukuBridge.isReady() && ShizukuBridge.hasPermission()) {
                grantAccessibilityWithShizuku(true);
                return;
            }
        }
        Toast.makeText(this, R.string.accessibility_service_not_running, Toast.LENGTH_LONG).show();
        openAccessibilitySettings();
    }

    private LinearLayout createSuperCustomGroup() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(16), dp(12), dp(16), dp(12));
        group.setBackground(UiChrome.card(this));

        TextView hint = createSupportingText();
        hint.setText(R.string.super_custom_home_hint);
        hint.setTextSize(12f);
        group.addView(hint, supportingParams(dp(10)));

        Button enter = createConfigButton(R.string.super_custom_enter, v ->
                startActivity(new Intent(MainActivity.this, SuperCustomDisplayActivity.class)));
        group.addView(enter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        superCustomDisplaySwitch = createSwitch(R.string.super_custom_display_switch);
        LinearLayout.LayoutParams displayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        displayLp.topMargin = dp(6);
        group.addView(superCustomDisplaySwitch, displayLp);

        LinearLayout slots = new LinearLayout(this);
        slots.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slotsLp.topMargin = dp(10);
        group.addView(slots, slotsLp);

        for (int slot = 1; slot <= SuperCustomConfigStore.SLOT_COUNT; slot++) {
            final int configSlot = slot;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));

            TextView label = createSupportingText();
            label.setTextSize(12f);
            label.setTextColor(UiPalette.textPrimary(this));
            superCustomSlotStatusViews[slot - 1] = label;
            row.addView(label, new LinearLayout.LayoutParams(0, dp(44), 1f));
            label.setGravity(Gravity.CENTER_VERTICAL);

            Button load = createConfigButton(R.string.super_custom_load, v -> loadSuperCustomSlot(configSlot));
            Button importButton = createConfigButton(R.string.super_custom_import, v -> openSuperCustomImportPicker(configSlot));
            LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(dp(72), dp(44));
            loadLp.leftMargin = dp(8);
            LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(dp(72), dp(44));
            importLp.leftMargin = dp(6);
            row.addView(load, loadLp);
            row.addView(importButton, importLp);
            slots.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        syncSuperCustomConfigRows();
        return group;
    }

    private void syncSuperCustomConfigRows() {
        for (int i = 0; i < superCustomSlotStatusViews.length; i++) {
            TextView view = superCustomSlotStatusViews[i];
            if (view == null) continue;
            int slot = i + 1;
            boolean saved = SuperCustomConfigStore.hasSlot(this, slot);
            view.setText(getString(R.string.super_custom_config_slot_status, slot,
                    getString(saved ? R.string.super_custom_config_saved : R.string.super_custom_config_empty)));
        }
    }

    private void loadSuperCustomSlot(int slot) {
        if (!SuperCustomConfigStore.hasSlot(this, slot)) {
            Toast.makeText(this, R.string.super_custom_config_slot_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SuperCustomConfigStore.loadSlotIntoActive(this, slot);
            // Loading changes only the active workspace. Visibility is controlled exclusively by
            // the dedicated Super Custom display switch and must never be auto-enabled here.
            AxonInputAccessibilityService.refreshActiveService();
            if (OverlayState.isSuperCustomEnabled(this)) ensureAccessibility();
            Toast.makeText(this, getString(R.string.super_custom_load_slot_success, slot), Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, R.string.super_custom_config_load_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSuperCustomImportPicker(int slot) {
        pendingSuperCustomImportSlot = slot;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        startActivityForResult(intent, SUPER_CUSTOM_IMPORT_REQUEST);
    }

    private LinearLayout createConfig1Group() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(16), dp(10), dp(16), dp(12));
        group.setBackground(UiChrome.card(this));

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
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        leftParams.rightMargin = dp(6);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
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
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
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
        UiChrome.styleInput(this, input);
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
        for (int key : keys) text.append(' ').append(InputBinding.label(key));
        recordedKeysText.setText(text.toString());
    }

    private boolean isPhysicalKeyboardEvent(KeyEvent event) {
        return InputBinding.isPhysicalKeyboardEvent(event);
    }

    private boolean isPhysicalGamepadEvent(KeyEvent event) {
        return InputBinding.isPhysicalGamepadEvent(event);
    }

    private void updateForceHoldUi() {
        if (forceHoldStatusText == null) return;
        if (forceHoldCaptureStep == 1) {
            forceHoldStatusText.setText(R.string.force_hold_wait_target);
            return;
        }
        if (forceHoldCaptureStep == 2 && forceHoldTargetKeyPending >= 0) {
            forceHoldStatusText.setText(getString(
                    R.string.force_hold_wait_trigger, InputBinding.label(forceHoldTargetKeyPending)));
            return;
        }
        if (OverlayState.isForceHoldEnabled(this) && OverlayState.hasForceHoldBinding(this)) {
            forceHoldStatusText.setText(getString(
                    R.string.force_hold_ready,
                    InputBinding.label(OverlayState.getForceHoldTargetKeyCode(this)),
                    InputBinding.label(OverlayState.getForceHoldTriggerKeyCode(this))));
            return;
        }
        forceHoldStatusText.setText(R.string.force_hold_wait_target);
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
        motionSpinners.put(displayType, spinner);
        spinner.setSelection(motionModeSelection(OverlayState.getMotionMode(this, displayType)), false);
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
                updateDiffusionOpacityEnabled(displayType, mode == OverlayState.MOTION_RIPPLE);
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
        int first = GamepadSettingsStore.getCustomSwapFirst(this);
        int second = GamepadSettingsStore.getCustomSwapSecond(this);
        if (GamepadSettingsStore.isCustomSwapEnabled(this) && first != 0 && second != 0) {
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
            case GamepadOverlayView.BTN_DPAD_UP -> "D-pad ↑";
            case GamepadOverlayView.BTN_DPAD_DOWN -> "D-pad ↓";
            case GamepadOverlayView.BTN_DPAD_LEFT -> "D-pad ←";
            case GamepadOverlayView.BTN_DPAD_RIGHT -> "D-pad →";
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
        String label = InputBinding.label(OverlayState.bindingFromDpsTarget(target));
        dpsTargetText.setText(getString(R.string.dps_target_selected, label));
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
        setControlEnabled(keyboardCatDeleteStyleButton, !selectedInfo.builtin);
        setControlEnabled(keyboardCatModelFunctionsButton, !selectedInfo.builtin);
        setControlEnabled(keyboardCatMouseModeSwitch, selectedInfo.builtin);
        if (!selectedInfo.id.equals(selectedId)) OverlayState.setKeyboardCatStyleId(this, selectedInfo.id);
        syncKeyboardCatExpressionOptions(selectedInfo);
    }

    private void syncKeyboardCatExpressionOptions(BongoCatStyleManager.StyleInfo style) {
        if (keyboardCatExpressionSpinner == null) return;
        keyboardCatExpressions.clear();
        if (style != null) {
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
            setControlEnabled(keyboardCatExpressionSpinner, !keyboardCatExpressions.isEmpty());
        } finally {
            internalChange = previousInternal;
        }
        if (!valid) OverlayState.setKeyboardCatDebugExpression(this, "auto");
        if (keyboardCatExpressions.isEmpty()) cancelKeyboardCatExpressionHotkeyCapture(true);
        updateKeyboardCatExpressionHotkeyUi();
    }

    private void toggleHideDisplayHotkeyCapture() {
        if (hideDisplayHotkeyCaptureArmed) {
            cancelHideDisplayHotkeyCapture(true);
            return;
        }
        if (forceHoldCaptureStep != 0 || customMappingCaptureStep != 0
                || simultaneousClickCaptureStep != 0 || gamepadCustomSwapCaptureStep != 0
                || gamepadMappingCaptureArmed) {
            Toast.makeText(this, R.string.hide_display_hotkey_capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        cancelKeyboardCatExpressionHotkeyCapture(true);
        hideDisplayHotkeyPreviousInputCode = OverlayState.getHideDisplayHotkeyInputCode(this);
        if (hideDisplayHotkeyPreviousInputCode >= 0) {
            // Rebinding must not trigger the old shortcut while the next physical key is recorded.
            OverlayState.setHideDisplayHotkeyInputCode(this, -1);
        }
        hideDisplayHotkeyCaptureArmed = true;
        updateHideDisplayHotkeyButtonUi();
    }

    private void cancelHideDisplayHotkeyCapture(boolean restorePrevious) {
        if (!hideDisplayHotkeyCaptureArmed) return;
        hideDisplayHotkeyCaptureArmed = false;
        if (restorePrevious && hideDisplayHotkeyPreviousInputCode >= 0) {
            OverlayState.setHideDisplayHotkeyInputCode(this, hideDisplayHotkeyPreviousInputCode);
        }
        hideDisplayHotkeyPreviousInputCode = -1;
        updateHideDisplayHotkeyButtonUi();
    }

    private boolean gamepadMappingUsesSource(int inputCode) {
        if (!InputBinding.isGamepad(inputCode)) return false;
        for (GamepadMappingStore.Mapping mapping : GamepadMappingStore.load(this)) {
            if (mapping.sourceInputCode == inputCode) return true;
        }
        return false;
    }

    private boolean physicsHotkeyConflicts(int inputCode, String exceptTarget, String exceptGroupKey) {
        if (inputCode < 0) return true;
        String[] targets = new String[]{
                Live2DPhysicsSettingsStore.TARGET_LIVE2D,
                Live2DPhysicsSettingsStore.keyboardCatTarget(OverlayState.getKeyboardCatStyleId(this))
        };
        for (String target : targets) {
            for (Live2DPhysicsHotkeyStore.Binding binding : Live2DPhysicsHotkeyStore.load(this, target)) {
                if (binding.inputCode != inputCode) continue;
                if (target.equals(exceptTarget) && binding.groupKey.equals(exceptGroupKey)) continue;
                return true;
            }
        }
        return false;
    }

    private boolean keyboardCatFunctionHotkeyConflicts(int inputCode) {
        if (inputCode < 0) return true;
        String styleId = OverlayState.getKeyboardCatStyleId(this);
        return !KeyboardCatFunctionBindingStore.bindingsForInput(this, styleId, inputCode).isEmpty();
    }

    private boolean hideDisplayHotkeyConflicts(int inputCode) {
        if (inputCode < 0) return true;
        if (OverlayState.isForceHoldEnabled(this)
                && inputCode == OverlayState.getForceHoldTriggerKeyCode(this)) return true;
        if (inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)) return true;
        if (physicsHotkeyConflicts(inputCode, null, null)) return true;
        if (keyboardCatFunctionHotkeyConflicts(inputCode)) return true;
        if (gamepadMappingUsesSource(inputCode)) return true;
        if (customMappingUsesTrigger(inputCode)) return true;
        if (simultaneousClickUsesSource(inputCode)) return true;
        return FloatingMediaStore.hotkeyConflicts(this, null, inputCode);
    }

    private void refreshHideDisplayHotkeyUi() {
        if (hideDisplayHotkeyButton == null || hideDisplayTargetSelectionButton == null
                || hideDisplayHotkeySwitch == null) return;
        OverlayState.sanitizeHiddenDisplayHotkeyTargets(this);
        boolean previousInternal = internalChange;
        internalChange = true;
        try {
            hideDisplayHotkeySwitch.setChecked(OverlayState.isHideDisplayHotkeyEnabled(this));
        } finally {
            internalChange = previousInternal;
        }
        List<Integer> enabledTargets = OverlayState.getEnabledHideDisplayTargets(this);
        Set<Integer> selected = OverlayState.getHideDisplayHotkeyTargets(this);
        int selectedCount = 0;
        for (int target : enabledTargets) if (selected.contains(target)) selectedCount++;
        if (enabledTargets.isEmpty()) {
            hideDisplayTargetSelectionButton.setText(R.string.hide_display_target_none);
            setControlEnabled(hideDisplayTargetSelectionButton, false);
        } else {
            hideDisplayTargetSelectionButton.setText(getString(
                    R.string.hide_display_target_selected_count, selectedCount, enabledTargets.size()));
            setControlEnabled(hideDisplayTargetSelectionButton, true);
        }
        setControlEnabled(hideDisplayHotkeyButton, OverlayState.isHideDisplayHotkeyEnabled(this));
        updateHideDisplayHotkeyButtonUi();
    }

    private void showHideDisplayTargetSelectionDialog() {
        List<Integer> enabledTargets = OverlayState.getEnabledHideDisplayTargets(this);
        if (enabledTargets.isEmpty()) {
            Toast.makeText(this, R.string.hide_display_target_none, Toast.LENGTH_SHORT).show();
            return;
        }
        Set<Integer> selected = OverlayState.getHideDisplayHotkeyTargets(this);
        String[] labels = new String[enabledTargets.size()];
        boolean[] checked = new boolean[enabledTargets.size()];
        for (int i = 0; i < enabledTargets.size(); i++) {
            int target = enabledTargets.get(i);
            labels[i] = hideDisplayTargetLabel(target);
            checked[i] = selected.contains(target);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.hide_display_target_dialog_title)
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    LinkedHashSet<Integer> next = new LinkedHashSet<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) next.add(enabledTargets.get(i));
                    }
                    OverlayState.setHideDisplayHotkeyTargets(MainActivity.this, next);
                    refreshHideDisplayHotkeyUi();
                })
                .show();
    }

    private void updateHideDisplayHotkeyButtonUi() {
        if (hideDisplayHotkeyButton == null) return;
        if (hideDisplayHotkeyCaptureArmed) {
            hideDisplayHotkeyButton.setText(R.string.hide_display_hotkey_recording);
            return;
        }
        int inputCode = OverlayState.getHideDisplayHotkeyInputCode(this);
        hideDisplayHotkeyButton.setText(inputCode >= 0
                ? getString(R.string.hide_display_hotkey_bound, InputBinding.label(inputCode))
                : getString(R.string.hide_display_hotkey_unbound));
    }

    private boolean customMappingUsesTrigger(int inputCode) {
        return CustomMappingStore.usesTrigger(this, inputCode);
    }

    private boolean customMappingTriggerConflicts(int inputCode) {
        if (inputCode < 0) return true;
        if (gamepadMappingUsesSource(inputCode)) return true;
        if (simultaneousClickUsesSource(inputCode)) return true;
        if (OverlayState.isForceHoldEnabled(this)
                && inputCode == OverlayState.getForceHoldTriggerKeyCode(this)) return true;
        if (inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)) return true;
        if (OverlayState.isHideDisplayHotkeyEnabled(this)
                && inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)) return true;
        if (physicsHotkeyConflicts(inputCode, null, null)) return true;
        if (keyboardCatFunctionHotkeyConflicts(inputCode)) return true;
        return FloatingMediaStore.hotkeyConflicts(this, null, inputCode);
    }

    private void startCustomMappingCapture() {
        if (customMappingCaptureStep != 0) {
            cancelCustomMappingCapture();
            return;
        }
        if (forceHoldCaptureStep != 0 || simultaneousClickCaptureStep != 0
                || gamepadCustomSwapCaptureStep != 0 || gamepadMappingCaptureArmed
                || hideDisplayHotkeyCaptureArmed || keyboardCatExpressionHotkeyCaptureArmed
                || physicsHotkeyCaptureArmed || keyboardCatFunctionBindingCaptureArmed) {
            Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        AxonInputAccessibilityService.releaseCustomMappingForCapture();
        customMappingCaptureStep = 1;
        customMappingTriggerPending = -1;
        customMappingCaptureDeadline = 0L;
        customMappingPendingOutputs.clear();
        mainHandler.removeCallbacks(customMappingCaptureTimeout);
        mainHandler.removeCallbacks(customMappingCaptureTicker);
        updateCustomMappingUi();
        AxonInputAccessibilityService.refreshActiveService();
        ensureAccessibility();
        Toast.makeText(this, R.string.custom_mapping_wait_trigger, Toast.LENGTH_SHORT).show();
    }

    private void cancelCustomMappingCapture() {
        mainHandler.removeCallbacks(customMappingCaptureTimeout);
        mainHandler.removeCallbacks(customMappingCaptureTicker);
        customMappingCaptureStep = 0;
        customMappingTriggerPending = -1;
        customMappingCaptureDeadline = 0L;
        customMappingPendingOutputs.clear();
        if (customMappingSwitch != null) updateCustomMappingUi();
        AxonInputAccessibilityService.refreshActiveService();
    }

    private void finishCustomMappingCapture(boolean save) {
        if (customMappingCaptureStep == 0) return;
        mainHandler.removeCallbacks(customMappingCaptureTimeout);
        mainHandler.removeCallbacks(customMappingCaptureTicker);
        int savedCount = customMappingPendingOutputs.size();
        int trigger = customMappingTriggerPending;
        boolean canSave = save && customMappingCaptureStep == 2 && trigger >= 0 && savedCount > 0;
        if (canSave) {
            CustomMappingStore.putRule(this, trigger, new ArrayList<>(customMappingPendingOutputs));
            CustomMappingStore.setEnabled(this, true);
        }
        customMappingCaptureStep = 0;
        customMappingTriggerPending = -1;
        customMappingCaptureDeadline = 0L;
        customMappingPendingOutputs.clear();
        updateCustomMappingUi();
        AxonInputAccessibilityService.refreshActiveService();
        if (canSave) {
            ensureAccessibility();
            ensurePrivilegedInputForGamepadMapping();
            handleDisplayModeChanged();
            Toast.makeText(this, getString(R.string.custom_mapping_saved, savedCount), Toast.LENGTH_SHORT).show();
        } else if (save && savedCount == 0) {
            Toast.makeText(this, R.string.custom_mapping_no_output, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateCustomMappingCaptureStatusOnly() {
        if (customMappingStatusText == null || customMappingCaptureStep != 2
                || customMappingTriggerPending < 0) return;
        float remaining = Math.max(0L, customMappingCaptureDeadline - SystemClock.uptimeMillis()) / 1000f;
        customMappingStatusText.setText(getString(R.string.custom_mapping_recording,
                InputBinding.label(customMappingTriggerPending), remaining, customMappingPendingOutputs.size()));
    }

    private void updateCustomMappingUi() {
        if (customMappingSwitch == null || customMappingStatusText == null
                || customMappingConfigButton == null || customMappingList == null) return;
        List<CustomMappingStore.Rule> rules = CustomMappingStore.load(this);
        boolean previousInternal = internalChange;
        internalChange = true;
        try {
            customMappingSwitch.setChecked(customMappingCaptureStep != 0 || CustomMappingStore.isEnabled(this));
        } finally {
            internalChange = previousInternal;
        }

        if (customMappingCaptureStep == 1) {
            customMappingStatusText.setText(R.string.custom_mapping_wait_trigger);
            customMappingConfigButton.setText(android.R.string.cancel);
        } else if (customMappingCaptureStep == 2 && customMappingTriggerPending >= 0) {
            updateCustomMappingCaptureStatusOnly();
            customMappingConfigButton.setText(android.R.string.cancel);
        } else {
            customMappingStatusText.setText(rules.isEmpty()
                    ? getString(R.string.custom_mapping_empty)
                    : getString(R.string.custom_mapping_count, rules.size()));
            customMappingConfigButton.setText(R.string.custom_mapping_add);
        }

        customMappingList.removeAllViews();
        for (CustomMappingStore.Rule rule : rules) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = createSupportingText();
            StringBuilder sequence = new StringBuilder();
            int shown = Math.min(5, rule.outputs.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) sequence.append(" · ");
                sequence.append(InputBinding.label(rule.outputs.get(i).inputCode));
            }
            if (rule.outputs.size() > shown) sequence.append(" · … +").append(rule.outputs.size() - shown);
            label.setText(getString(R.string.custom_mapping_ready,
                    InputBinding.label(rule.triggerInputCode), sequence.toString()));
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button remove = new Button(this);
            remove.setText(R.string.custom_mapping_remove);
            remove.setAllCaps(false);
            remove.setTextSize(12f);
            remove.setMinHeight(dp(36));
            remove.setMinimumHeight(dp(36));
            styleActionButton(remove);
            remove.setOnClickListener(v -> {
                CustomMappingStore.removeRule(MainActivity.this, rule.id);
                updateCustomMappingUi();
                handleDisplayModeChanged();
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT));
            customMappingList.addView(row, supportingParams(dp(4)));
        }
    }

    private boolean simultaneousClickUsesSource(int inputCode) {
        return SimultaneousClickStore.usesSource(this, inputCode);
    }

    private boolean simultaneousClickSourceConflicts(int inputCode) {
        if (inputCode < 0) return true;
        if (gamepadMappingUsesSource(inputCode)) return true;
        if (customMappingUsesTrigger(inputCode)) return true;
        if (OverlayState.isForceHoldEnabled(this)
                && inputCode == OverlayState.getForceHoldTriggerKeyCode(this)) return true;
        if (inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)) return true;
        if (OverlayState.isHideDisplayHotkeyEnabled(this)
                && inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)) return true;
        if (physicsHotkeyConflicts(inputCode, null, null)) return true;
        if (keyboardCatFunctionHotkeyConflicts(inputCode)) return true;
        return FloatingMediaStore.hotkeyConflicts(this, null, inputCode);
    }

    private void startSimultaneousClickCapture() {
        if (simultaneousClickCaptureStep != 0) {
            simultaneousClickCaptureStep = 0;
            simultaneousClickSourcePending = -1;
            updateSimultaneousClickUi();
            AxonInputAccessibilityService.refreshActiveService();
            return;
        }
        if (forceHoldCaptureStep != 0 || gamepadCustomSwapCaptureStep != 0
                || customMappingCaptureStep != 0 || gamepadMappingCaptureArmed || hideDisplayHotkeyCaptureArmed
                || keyboardCatExpressionHotkeyCaptureArmed || physicsHotkeyCaptureArmed
                || keyboardCatFunctionBindingCaptureArmed) {
            Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        // Existing mappings stay enabled while a new rule is recorded, but release any active
        // mirrored hold first so suppressing dispatch during capture cannot strand a target DOWN.
        AxonInputAccessibilityService.releaseSimultaneousClickForCapture();
        simultaneousClickCaptureStep = 1;
        simultaneousClickSourcePending = -1;
        updateSimultaneousClickUi();
        // Capture accepts keyboard, mouse and gamepad.  Ask the service to temporarily keep all
        // corresponding passive monitors alive so OEMs that do not forward side buttons / pad keys
        // to the Activity can still be recorded.
        AxonInputAccessibilityService.refreshActiveService();
        ensureAccessibility();
        Toast.makeText(this, R.string.simultaneous_click_wait_source, Toast.LENGTH_SHORT).show();
    }

    private void updateSimultaneousClickUi() {
        if (simultaneousClickSwitch == null || simultaneousClickStatusText == null
                || simultaneousClickConfigButton == null || simultaneousClickList == null) return;
        List<SimultaneousClickStore.Binding> bindings = SimultaneousClickStore.load(this);
        boolean previousInternal = internalChange;
        internalChange = true;
        try {
            simultaneousClickSwitch.setChecked(simultaneousClickCaptureStep != 0
                    || SimultaneousClickStore.isEnabled(this));
        } finally {
            internalChange = previousInternal;
        }
        if (simultaneousClickCaptureStep == 1) {
            simultaneousClickStatusText.setText(R.string.simultaneous_click_wait_source);
            simultaneousClickConfigButton.setText(android.R.string.cancel);
        } else if (simultaneousClickCaptureStep == 2 && simultaneousClickSourcePending >= 0) {
            simultaneousClickStatusText.setText(getString(R.string.simultaneous_click_wait_target,
                    InputBinding.label(simultaneousClickSourcePending)));
            simultaneousClickConfigButton.setText(android.R.string.cancel);
        } else {
            simultaneousClickStatusText.setText(bindings.isEmpty()
                    ? getString(R.string.simultaneous_click_empty)
                    : getString(R.string.simultaneous_click_count, bindings.size()));
            simultaneousClickConfigButton.setText(R.string.simultaneous_click_add);
        }

        simultaneousClickList.removeAllViews();
        for (SimultaneousClickStore.Binding binding : bindings) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = createSupportingText();
            label.setText(getString(R.string.simultaneous_click_ready,
                    InputBinding.label(binding.sourceInputCode),
                    InputBinding.label(binding.targetInputCode)));
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button remove = new Button(this);
            remove.setText(R.string.simultaneous_click_remove);
            remove.setAllCaps(false);
            remove.setTextSize(12f);
            remove.setMinHeight(dp(36));
            remove.setMinimumHeight(dp(36));
            styleActionButton(remove);
            remove.setOnClickListener(v -> {
                SimultaneousClickStore.removeBinding(MainActivity.this, binding.id);
                updateSimultaneousClickUi();
                handleDisplayModeChanged();
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT));
            simultaneousClickList.addView(row, supportingParams(dp(4)));
        }
    }

    private void startGamepadMappingCapture() {
        if (gamepadMappingCaptureArmed) {
            gamepadMappingCaptureArmed = false;
            pendingGamepadMappingSource = -1;
            updateGamepadMappingUi();
            return;
        }
        if (forceHoldCaptureStep != 0 || simultaneousClickCaptureStep != 0 || customMappingCaptureStep != 0
                || gamepadCustomSwapCaptureStep != 0 || hideDisplayHotkeyCaptureArmed
                || keyboardCatExpressionHotkeyCaptureArmed) {
            Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        gamepadMappingCaptureArmed = true;
        pendingGamepadMappingSource = -1;
        updateGamepadMappingUi();
        ensureAccessibility();
        Toast.makeText(this, R.string.gamepad_mapping_wait_gamepad, Toast.LENGTH_SHORT).show();
    }

    private void onGamepadMappingSourceCaptured(int sourceInputCode) {
        if (!gamepadMappingCaptureArmed || !InputBinding.isGamepad(sourceInputCode)) return;
        gamepadMappingCaptureArmed = false;
        pendingGamepadMappingSource = sourceInputCode;
        updateGamepadMappingUi();
        showGamepadMappingKeyboardPicker(sourceInputCode);
    }

    private void showGamepadMappingKeyboardPicker(int sourceInputCode) {
        if (gamepadMappingKeyboardDialog != null) {
            try { gamepadMappingKeyboardDialog.dismiss(); } catch (Throwable ignored) {}
            gamepadMappingKeyboardDialog = null;
        }
        FullKeyboardOverlayView keyboard = new FullKeyboardOverlayView(this);
        keyboard.setPickerMode(keyCode -> {
            if (!GamepadMappingStore.isSupportedKeyboardTarget(keyCode)) return;
            GamepadMappingStore.put(MainActivity.this, sourceInputCode, keyCode);
            pendingGamepadMappingSource = -1;
            if (gamepadMappingKeyboardDialog != null) {
                gamepadMappingKeyboardDialog.dismiss();
                gamepadMappingKeyboardDialog = null;
            }
            updateGamepadMappingUi();
            ensureAccessibility();
            ensurePrivilegedInputForGamepadMapping();
        });
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(320), Math.min(screenWidth - dp(24), dp(760)));
        int height = Math.max(dp(210), Math.min(dp(330), Math.round(width * 0.48f)));
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(dp(8), dp(8), dp(8), dp(8));
        wrapper.addView(keyboard, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        gamepadMappingKeyboardDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.gamepad_mapping_choose_keyboard,
                        InputBinding.label(sourceInputCode)))
                .setView(wrapper)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        gamepadMappingKeyboardDialog.setOnDismissListener(dialog -> {
            pendingGamepadMappingSource = -1;
            gamepadMappingKeyboardDialog = null;
            updateGamepadMappingUi();
        });
        gamepadMappingKeyboardDialog.show();
    }

    private void ensurePrivilegedInputForGamepadMapping() {
        RootBridge.ensureActivated(this, rootActive -> {
            if (!rootActive && (!ShizukuBridge.isReady() || !ShizukuBridge.hasPermission())) {
                ensureShizukuForSensitivity();
            }
            AxonInputAccessibilityService.refreshActiveService();
        });
    }

    private void updateGamepadMappingUi() {
        if (gamepadMappingAddButton == null || gamepadMappingList == null || gamepadMappingStatusText == null) return;
        if (gamepadMappingCaptureArmed) {
            gamepadMappingAddButton.setText(R.string.gamepad_mapping_cancel_capture);
            gamepadMappingStatusText.setText(R.string.gamepad_mapping_wait_gamepad);
        } else if (pendingGamepadMappingSource >= 0) {
            gamepadMappingAddButton.setText(R.string.gamepad_mapping_add);
            gamepadMappingStatusText.setText(getString(R.string.gamepad_mapping_wait_keyboard,
                    InputBinding.label(pendingGamepadMappingSource)));
        } else {
            gamepadMappingAddButton.setText(R.string.gamepad_mapping_add);
            List<GamepadMappingStore.Mapping> mappings = GamepadMappingStore.load(this);
            gamepadMappingStatusText.setText(mappings.isEmpty()
                    ? getString(R.string.gamepad_mapping_empty)
                    : getString(R.string.gamepad_mapping_count, mappings.size()));
        }

        gamepadMappingList.removeAllViews();
        List<GamepadMappingStore.Mapping> mappings = GamepadMappingStore.load(this);
        for (GamepadMappingStore.Mapping mapping : mappings) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = createSupportingText();
            label.setText(getString(R.string.gamepad_mapping_row,
                    InputBinding.label(mapping.sourceInputCode), KeyLabel.fromKeyCode(mapping.targetKeyCode)));
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button remove = new Button(this);
            remove.setText(R.string.gamepad_mapping_remove);
            remove.setAllCaps(false);
            remove.setTextSize(12f);
            remove.setMinHeight(dp(36));
            remove.setMinimumHeight(dp(36));
            styleActionButton(remove);
            remove.setOnClickListener(v -> {
                GamepadMappingStore.remove(MainActivity.this, mapping.sourceInputCode);
                updateGamepadMappingUi();
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT));
            gamepadMappingList.addView(row, supportingParams(dp(4)));
        }
    }

    private String hideDisplayTargetLabel(int target) {
        switch (target) {
            case OverlayState.HIDE_DISPLAY_KEYBOARD: return getString(R.string.hide_display_target_keyboard);
            case OverlayState.HIDE_DISPLAY_FULL_KEYBOARD: return getString(R.string.hide_display_target_full_keyboard);
            case OverlayState.HIDE_DISPLAY_MOUSE: return getString(R.string.hide_display_target_mouse);
            case OverlayState.HIDE_DISPLAY_KEYBOARD_CAT: return getString(R.string.hide_display_target_keyboard_cat);
            case OverlayState.HIDE_DISPLAY_KEY_PROMPT: return getString(R.string.hide_display_target_key_prompt);
            case OverlayState.HIDE_DISPLAY_MOUSE_TRAJECTORY: return getString(R.string.hide_display_target_mouse_trajectory);
            case OverlayState.HIDE_DISPLAY_CUSTOM: return getString(R.string.hide_display_target_custom);
            case OverlayState.HIDE_DISPLAY_SUPER_CUSTOM: return getString(R.string.hide_display_target_super_custom);
            case OverlayState.HIDE_DISPLAY_TOUCH: return getString(R.string.hide_display_target_touch);
            case OverlayState.HIDE_DISPLAY_DPS: return getString(R.string.hide_display_target_dps);
            case OverlayState.HIDE_DISPLAY_GAMEPAD_LEFT_STICK: return getString(R.string.hide_display_target_gamepad_left_stick);
            case OverlayState.HIDE_DISPLAY_GAMEPAD_RIGHT_STICK: return getString(R.string.hide_display_target_gamepad_right_stick);
            case OverlayState.HIDE_DISPLAY_GAMEPAD_FACE: return getString(R.string.hide_display_target_gamepad_face);
            case OverlayState.HIDE_DISPLAY_GAMEPAD_DPAD: return getString(R.string.hide_display_target_gamepad_dpad);
            case OverlayState.HIDE_DISPLAY_GAMEPAD_LEFT_SHOULDER: return getString(R.string.hide_display_target_gamepad_left_shoulder);
            case OverlayState.HIDE_DISPLAY_GAMEPAD_RIGHT_SHOULDER: return getString(R.string.hide_display_target_gamepad_right_shoulder);
            case OverlayState.HIDE_DISPLAY_GAMEPAD_BACK: return getString(R.string.hide_display_target_gamepad_back);
            default: return getString(R.string.hide_display_target_none);
        }
    }

    private void syncLive2DPhysicsUi(boolean live2dAvailable) {
        if (live2dPhysicsStrengthSeekBar == null || live2dPhysicsStrengthLabel == null
                || live2dPhysicsGroupsButton == null) return;
        int physicsStrength = Live2DPhysicsSettingsStore.getGlobalStrength(
                this, Live2DPhysicsSettingsStore.TARGET_LIVE2D);
        live2dPhysicsStrengthSeekBar.setProgress(physicsStrength);
        live2dPhysicsStrengthLabel.setText(getString(R.string.live2d_physics_strength_format, physicsStrength));
        List<BongoCatStyleManager.PhysicsGroupOption> groups = live2dAvailable
                ? Live2DModelStore.physicsGroups(this) : java.util.Collections.emptyList();
        live2dPhysicsGroupsButton.setText(groups.isEmpty()
                ? getString(R.string.live2d_physics_groups)
                : getString(R.string.live2d_physics_groups_count, groups.size()));
        live2dPhysicsGroupsButton.setEnabled(live2dAvailable && !groups.isEmpty());
        live2dPhysicsStrengthSeekBar.setEnabled(live2dAvailable && !groups.isEmpty());

        if (live2dExpressionDebugButton != null) {
            int count = live2dAvailable ? Live2DModelStore.expressionOptions(this).size() : 0;
            live2dExpressionDebugButton.setText(count > 0
                    ? getString(R.string.live2d_expression_debug_count, count)
                    : getString(R.string.live2d_expression_debug));
            live2dExpressionDebugButton.setEnabled(live2dAvailable && count > 0);
        }
        if (live2dParameterDebugButton != null) {
            int count = live2dAvailable ? Live2DModelStore.parameterOptions(this).size() : 0;
            live2dParameterDebugButton.setText(count > 0
                    ? getString(R.string.live2d_parameter_debug_count, count)
                    : getString(R.string.live2d_parameter_debug));
            live2dParameterDebugButton.setEnabled(live2dAvailable && count > 0);
        }
    }

    private void showLive2DExpressionDebugDialog() {
        List<BongoCatStyleManager.ExpressionOption> expressions = Live2DModelStore.expressionOptions(this);
        if (expressions.isEmpty()) {
            Toast.makeText(this, R.string.live2d_expression_debug_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[expressions.size()];
        for (int i = 0; i < expressions.size(); i++) labels[i] = expressions.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.live2d_expression_debug_count, expressions.size()))
                .setItems(labels, (dialog, which) -> showExpressionDebugControl(
                        Live2DPhysicsSettingsStore.TARGET_LIVE2D, null, expressions.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showKeyboardCatExpressionDebugDialog(String styleId) {
        List<BongoCatStyleManager.ExpressionOption> allExpressions =
                BongoCatStyleManager.expressionOptions(this, styleId);
        List<BongoCatStyleManager.ExpressionOption> expressions = new ArrayList<>();
        for (BongoCatStyleManager.ExpressionOption option : allExpressions) {
            // The advanced Expression debugger is intentionally limited to Cubism exp3 entries.
            // Sprite-only Mver face overlays keep using the existing expression selector/hotkey path.
            if (option != null && "live2d".equals(option.kind)) expressions.add(option);
        }
        if (expressions.isEmpty()) {
            Toast.makeText(this, R.string.live2d_expression_debug_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[expressions.size()];
        for (int i = 0; i < expressions.size(); i++) labels[i] = expressions.get(i).label;
        String target = Live2DPhysicsSettingsStore.keyboardCatTarget(styleId);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.live2d_expression_debug_count, expressions.size()))
                .setItems(labels, (dialog, which) ->
                        showExpressionDebugControl(target, styleId, expressions.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showExpressionDebugControl(String target, String keyboardCatStyleId,
                                            BongoCatStyleManager.ExpressionOption expression) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), dp(6));

        TextView info = createSupportingText();
        info.setText(getString(R.string.live2d_expression_debug_meta, expression.token));
        panel.addView(info, supportingParams(0));

        TextView weightLabel = createLabel();
        SeekBar weight = new SeekBar(this);
        weight.setMax(100);
        styleSeekBar(weight);
        int storedWeight = Live2DDebugSettingsStore.getExpressionWeight(this, target, expression.token);
        weight.setProgress(storedWeight);
        weightLabel.setText(getString(R.string.live2d_expression_weight_format, storedWeight));
        panel.addView(weightLabel, supportingParams(dp(8)));
        panel.addView(weight, seekBarLayoutParams(dp(2)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button preview = new Button(this);
        preview.setText(R.string.live2d_expression_preview);
        preview.setAllCaps(false);
        styleActionButton(preview);
        Button stop = new Button(this);
        stop.setText(R.string.live2d_expression_stop);
        stop.setAllCaps(false);
        styleActionButton(stop);
        actions.addView(preview, new LinearLayout.LayoutParams(0, dp(42), 1f));
        actions.addView(stop, new LinearLayout.LayoutParams(0, dp(42), 1f));
        panel.addView(actions, supportingParams(dp(8)));

        final boolean[] previewing = {false};
        weight.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                weightLabel.setText(getString(R.string.live2d_expression_weight_format, progress));
                if (fromUser && previewing[0]) previewExpression(keyboardCatStyleId, expression.token, progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                Live2DDebugSettingsStore.setExpressionWeight(
                        MainActivity.this, target, expression.token, seekBar.getProgress());
            }
        });
        preview.setOnClickListener(v -> {
            previewing[0] = true;
            previewExpression(keyboardCatStyleId, expression.token, weight.getProgress() / 100f);
        });
        stop.setOnClickListener(v -> {
            previewing[0] = false;
            if (keyboardCatStyleId == null) previewExpression(null, "auto", 1f);
            else AxonInputAccessibilityService.previewKeyboardCatExpression(
                    OverlayState.getKeyboardCatDebugExpression(MainActivity.this), 1f);
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(expression.label)
                .setView(panel)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (!previewing[0]) return;
            if (keyboardCatStyleId == null) previewExpression(null, "auto", 1f);
            else AxonInputAccessibilityService.previewKeyboardCatExpression(
                    OverlayState.getKeyboardCatDebugExpression(MainActivity.this), 1f);
        });
        dialog.show();
    }

    private void previewExpression(String keyboardCatStyleId, String token, float weight) {
        if (keyboardCatStyleId == null) {
            if (inAppLive2dView != null) inAppLive2dView.setDebugExpression(token, weight);
            AxonInputAccessibilityService.previewLive2DExpression(token, weight);
        } else {
            AxonInputAccessibilityService.previewKeyboardCatExpression(token, weight);
        }
    }

    private void showLive2DParameterDebugDialog() {
        List<BongoCatStyleManager.ParameterOption> parameters = Live2DModelStore.parameterOptions(this);
        if (parameters.isEmpty()) {
            Toast.makeText(this, R.string.live2d_parameter_debug_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) {
            BongoCatStyleManager.ParameterOption item = parameters.get(i);
            String suffix = item.group.isEmpty() ? item.category : item.group;
            labels[i] = item.label + "  ·  " + suffix;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.live2d_parameter_debug_count, parameters.size()))
                .setItems(labels, (dialog, which) -> showStandaloneLive2DParameterControl(parameters.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showStandaloneLive2DParameterControl(BongoCatStyleManager.ParameterOption option) {
        String target = Live2DPhysicsSettingsStore.TARGET_LIVE2D;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), dp(6));

        TextView meta = createSupportingText();
        String group = option.group.isEmpty() ? getString(R.string.keyboard_cat_parameter_no_group) : option.group;
        meta.setText(getString(R.string.keyboard_cat_parameter_meta, option.id, group));
        panel.addView(meta, supportingParams(0));

        TextView realtime = createLabel();
        realtime.setText(R.string.live2d_parameter_realtime_waiting);
        panel.addView(realtime, supportingParams(dp(8)));
        TextView source = createSupportingText();
        source.setText(R.string.live2d_parameter_source_waiting);
        panel.addView(source, supportingParams(dp(2)));

        Float storedLock = Live2DDebugSettingsStore.getParameterLock(this, target, option.id);
        Switch lock = createSwitch(R.string.live2d_parameter_lock);
        lock.setChecked(storedLock != null);
        panel.addView(lock, supportingParams(dp(8)));

        TextView lockValueLabel = createLabel();
        SeekBar lockValue = new SeekBar(this);
        lockValue.setMax(100);
        styleSeekBar(lockValue);
        int initial = storedLock == null ? 50 : Math.round(storedLock * 100f);
        lockValue.setProgress(initial);
        lockValue.setEnabled(storedLock != null);
        lockValueLabel.setText(getString(R.string.live2d_parameter_lock_value, initial));
        panel.addView(lockValueLabel, supportingParams(dp(6)));
        panel.addView(lockValue, seekBarLayoutParams(dp(2)));

        lock.setOnCheckedChangeListener((button, checked) -> {
            lockValue.setEnabled(checked);
            if (checked) {
                float value = lockValue.getProgress() / 100f;
                Live2DDebugSettingsStore.setParameterLock(this, target, option.id, value);
                applyLive2DParameterLock(option.id, value);
            } else {
                Live2DDebugSettingsStore.setParameterLock(this, target, option.id, null);
                clearLive2DParameterLock(option.id);
            }
        });
        lockValue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                lockValueLabel.setText(getString(R.string.live2d_parameter_lock_value, progress));
                if (!fromUser || !lock.isChecked()) return;
                applyLive2DParameterLock(option.id, progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (lock.isChecked()) Live2DDebugSettingsStore.setParameterLock(
                        MainActivity.this, target, option.id, seekBar.getProgress() / 100f);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(option.label)
                .setView(panel)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        startParameterDebugTicker(dialog, option.id, false, realtime, source, lock, lockValue);
    }

    private void applyLive2DParameterLock(String parameterId, float value) {
        if (inAppLive2dView != null) inAppLive2dView.setParameterLock(parameterId, value);
        AxonInputAccessibilityService.setLive2DParameterLock(parameterId, value);
    }

    private void clearLive2DParameterLock(String parameterId) {
        if (inAppLive2dView != null) inAppLive2dView.clearParameterLock(parameterId);
        AxonInputAccessibilityService.clearLive2DParameterLock(parameterId);
    }

    private void startParameterDebugTicker(AlertDialog dialog, String parameterId, boolean keyboardCat,
                                           TextView realtime, TextView source, Switch lock, SeekBar lockValue) {
        final Runnable[] ticker = new Runnable[1];
        ticker[0] = () -> {
            if (dialog == null || !dialog.isShowing()) return;
            android.webkit.ValueCallback<JSONObject> callback = debug -> {
                if (dialog == null || !dialog.isShowing()) return;
                if (debug != null) {
                    double value = debug.optDouble("value", 0);
                    double min = debug.optDouble("min", 0);
                    double max = debug.optDouble("max", 0);
                    double normalized = debug.optDouble("normalized", 0);
                    String owner = debug.optString("source", "BASE");
                    realtime.setText(getString(R.string.live2d_parameter_realtime_format,
                            value, normalized * 100.0, min, max));
                    source.setText(getString(R.string.live2d_parameter_source_format, owner));
                    if (!lock.isChecked() && debug.optBoolean("locked", false)) lock.setChecked(true);
                    if (debug.optBoolean("locked", false) && !lockValue.isPressed()) {
                        int progress = (int) Math.round(debug.optDouble("lockNormalized", normalized) * 100.0);
                        lockValue.setProgress(Math.max(0, Math.min(100, progress)));
                    }
                }
                mainHandler.postDelayed(ticker[0], 160L);
            };
            if (keyboardCat) {
                AxonInputAccessibilityService.requestKeyboardCatParameterDebug(parameterId, callback);
            } else if (inAppLive2dView != null) {
                inAppLive2dView.requestParameterDebug(parameterId, callback);
            } else {
                AxonInputAccessibilityService.requestLive2DParameterDebug(parameterId, callback);
            }
        };
        mainHandler.post(ticker[0]);
    }

    private void showLive2DPhysicsGroupsDialog() {
        List<BongoCatStyleManager.PhysicsGroupOption> groups = Live2DModelStore.physicsGroups(this);
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.live2d_physics_groups_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String target = Live2DPhysicsSettingsStore.TARGET_LIVE2D;
        String[] labels = physicsGroupLabels(target, groups);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.live2d_physics_groups_count, groups.size()))
                .setItems(labels, (dialog, which) ->
                        showPhysicsGroupControl(target, null, groups.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showKeyboardCatPhysicsStrengthDialog(String styleId) {
        String target = Live2DPhysicsSettingsStore.keyboardCatTarget(styleId);
        showPhysicsStrengthDialog(target, styleId);
    }

    private void showKeyboardCatPhysicsGroupsDialog(String styleId) {
        List<BongoCatStyleManager.PhysicsGroupOption> groups =
                BongoCatStyleManager.physicsGroups(this, styleId);
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.live2d_physics_groups_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String target = Live2DPhysicsSettingsStore.keyboardCatTarget(styleId);
        String[] labels = physicsGroupLabels(target, groups);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.live2d_physics_groups_count, groups.size()))
                .setItems(labels, (dialog, which) ->
                        showPhysicsGroupControl(target, styleId, groups.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String[] physicsGroupLabels(String target,
                                        List<BongoCatStyleManager.PhysicsGroupOption> groups) {
        String[] labels = new String[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            BongoCatStyleManager.PhysicsGroupOption group = groups.get(i);
            Live2DPhysicsSettingsStore.GroupSetting setting =
                    Live2DPhysicsSettingsStore.getGroupSetting(this, target, group.key);
            String state = setting.enabled
                    ? getString(R.string.live2d_physics_group_state_on, setting.strength)
                    : getString(R.string.live2d_physics_group_state_off);
            labels[i] = group.label + "  ·  " + state;
        }
        return labels;
    }

    private void showPhysicsStrengthDialog(String target, String keyboardCatStyleId) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), dp(8));

        TextView label = createLabel();
        SeekBar value = new SeekBar(this);
        value.setMax(200);
        styleSeekBar(value);
        int initial = Live2DPhysicsSettingsStore.getGlobalStrength(this, target);
        value.setProgress(initial);
        label.setText(getString(R.string.live2d_physics_strength_format, initial));
        panel.addView(label, supportingParams(0));
        panel.addView(value, seekBarLayoutParams(dp(2)));

        value.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(getString(R.string.live2d_physics_strength_format, progress));
                if (!fromUser) return;
                if (keyboardCatStyleId == null) {
                    if (inAppLive2dView != null) {
                        inAppLive2dView.setPhysicsControls(Live2DPhysicsSettingsStore.runtimeJson(
                                MainActivity.this, target, progress));
                    }
                    AxonInputAccessibilityService.previewLive2DPhysicsStrength(progress);
                } else {
                    AxonInputAccessibilityService.previewKeyboardCatPhysicsStrength(
                            keyboardCatStyleId, progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                Live2DPhysicsSettingsStore.setGlobalStrength(
                        MainActivity.this, target, seekBar.getProgress());
                refreshPhysicsRuntime(keyboardCatStyleId);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.live2d_physics_strength_title)
                .setView(panel)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showPhysicsGroupControl(String target, String keyboardCatStyleId,
                                         BongoCatStyleManager.PhysicsGroupOption group) {
        Live2DPhysicsSettingsStore.GroupSetting stored =
                Live2DPhysicsSettingsStore.getGroupSetting(this, target, group.key);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), dp(6));

        Switch enabled = createSwitch(R.string.live2d_physics_group_enabled);
        enabled.setChecked(stored.enabled);
        panel.addView(enabled, supportingParams(0));

        TextView strengthLabel = createLabel();
        SeekBar strength = new SeekBar(this);
        strength.setMax(200);
        styleSeekBar(strength);
        strength.setProgress(stored.strength);
        strength.setEnabled(stored.enabled);
        strengthLabel.setText(getString(R.string.live2d_physics_group_strength_format, stored.strength));
        panel.addView(strengthLabel, supportingParams(dp(6)));
        panel.addView(strength, seekBarLayoutParams(dp(2)));

        Button reset = new Button(this);
        reset.setText(R.string.live2d_physics_group_reset);
        reset.setAllCaps(false);
        reset.setTextSize(12f);
        reset.setMinHeight(dp(42));
        reset.setMinimumHeight(dp(42));
        styleActionButton(reset);
        panel.addView(reset, supportingParams(dp(8)));

        TextView hotkeyLabel = createSupportingText();
        int physicsHotkey = Live2DPhysicsHotkeyStore.getHotkey(this, target, group.key);
        boolean physicsHotkeyEnabled = physicsHotkey >= 0
                && Live2DPhysicsHotkeyStore.isEnabled(this, target, group.key);
        hotkeyLabel.setText(physicsHotkey >= 0
                ? getString(R.string.live2d_physics_hotkey_bound, InputBinding.label(physicsHotkey))
                : getString(R.string.live2d_physics_hotkey_unbound));
        panel.addView(hotkeyLabel, supportingParams(dp(10)));

        Switch hotkeyEnabled = createSwitch(R.string.live2d_physics_hotkey_enabled);
        hotkeyEnabled.setChecked(physicsHotkeyEnabled);
        hotkeyEnabled.setEnabled(physicsHotkey >= 0);
        hotkeyEnabled.setAlpha(physicsHotkey >= 0 ? 1f : 0.45f);
        panel.addView(hotkeyEnabled, supportingParams(dp(2)));

        LinearLayout hotkeyActions = new LinearLayout(this);
        hotkeyActions.setOrientation(LinearLayout.HORIZONTAL);
        Button bindHotkey = new Button(this);
        bindHotkey.setText(R.string.live2d_physics_hotkey_bind);
        bindHotkey.setAllCaps(false);
        styleActionButton(bindHotkey);
        Button clearHotkey = new Button(this);
        clearHotkey.setText(R.string.live2d_physics_hotkey_clear);
        clearHotkey.setAllCaps(false);
        styleActionButton(clearHotkey);
        LinearLayout.LayoutParams actionLeft = new LinearLayout.LayoutParams(0, dp(42), 1f);
        actionLeft.setMarginEnd(dp(4));
        LinearLayout.LayoutParams actionRight = new LinearLayout.LayoutParams(0, dp(42), 1f);
        actionRight.setMarginStart(dp(4));
        hotkeyActions.addView(bindHotkey, actionLeft);
        hotkeyActions.addView(clearHotkey, actionRight);
        panel.addView(hotkeyActions, supportingParams(dp(4)));

        enabled.setOnCheckedChangeListener((button, checked) -> {
            strength.setEnabled(checked);
            Live2DPhysicsSettingsStore.setGroupEnabled(MainActivity.this, target, group.key, checked);
            previewPhysicsGroup(keyboardCatStyleId, target, group.key, checked, strength.getProgress());
        });
        strength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                strengthLabel.setText(getString(R.string.live2d_physics_group_strength_format, progress));
                if (!fromUser) return;
                previewPhysicsGroup(keyboardCatStyleId, target, group.key,
                        enabled.isChecked(), progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                Live2DPhysicsSettingsStore.setGroupStrength(
                        MainActivity.this, target, group.key, seekBar.getProgress());
                refreshPhysicsRuntime(keyboardCatStyleId);
            }
        });
        reset.setOnClickListener(v -> {
            Live2DPhysicsSettingsStore.resetGroup(MainActivity.this, target, group.key);
            enabled.setChecked(true);
            strength.setProgress(100);
            strength.setEnabled(true);
            strengthLabel.setText(getString(R.string.live2d_physics_group_strength_format, 100));
            previewPhysicsGroup(keyboardCatStyleId, target, group.key, true, 100);
            refreshPhysicsRuntime(keyboardCatStyleId);
        });
        hotkeyEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (Live2DPhysicsHotkeyStore.getHotkey(MainActivity.this, target, group.key) < 0) {
                if (checked) button.setChecked(false);
                return;
            }
            Live2DPhysicsHotkeyStore.setEnabled(MainActivity.this, target, group.key, checked);
            AxonInputAccessibilityService.refreshActiveService();
        });

        final AlertDialog[] physicsDialog = new AlertDialog[1];
        bindHotkey.setOnClickListener(v -> {
            armPhysicsHotkeyBinding(target, group.key, group.label);
            if (physicsDialog[0] != null) physicsDialog[0].dismiss();
            Toast.makeText(this, R.string.live2d_physics_hotkey_recording, Toast.LENGTH_LONG).show();
        });
        clearHotkey.setOnClickListener(v -> {
            Live2DPhysicsHotkeyStore.setHotkey(this, target, group.key, -1);
            hotkeyEnabled.setChecked(false);
            hotkeyEnabled.setEnabled(false);
            hotkeyEnabled.setAlpha(0.45f);
            AxonInputAccessibilityService.refreshActiveService();
            hotkeyLabel.setText(R.string.live2d_physics_hotkey_unbound);
        });
        physicsDialog[0] = new AlertDialog.Builder(this)
                .setTitle(group.label)
                .setView(panel)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        physicsDialog[0].show();
    }

    private void previewPhysicsGroup(String keyboardCatStyleId, String target, String groupKey,
                                     boolean enabled, int strength) {
        if (keyboardCatStyleId == null) {
            if (inAppLive2dView != null) {
                inAppLive2dView.setPhysicsControls(Live2DPhysicsSettingsStore.runtimeJsonWithGroupPreview(
                        this, target, groupKey, enabled, strength));
            }
            AxonInputAccessibilityService.previewLive2DPhysicsGroup(groupKey, enabled, strength);
        } else {
            AxonInputAccessibilityService.previewKeyboardCatPhysicsGroup(
                    keyboardCatStyleId, groupKey, enabled, strength);
        }
    }

    private void refreshPhysicsRuntime(String keyboardCatStyleId) {
        if (keyboardCatStyleId == null) {
            if (inAppLive2dView != null) inAppLive2dView.refreshPhysicsControls();
            AxonInputAccessibilityService.refreshLive2DPhysics();
        } else {
            AxonInputAccessibilityService.refreshKeyboardCatPhysics();
        }
    }

    private void showKeyboardCatModelFunctionsDialog() {
        String styleId = OverlayState.getKeyboardCatStyleId(this);
        BongoCatStyleManager.StyleInfo style = BongoCatStyleManager.get(this, styleId);
        if (style == null || style.builtin) {
            Toast.makeText(this, R.string.keyboard_cat_model_functions_import_required, Toast.LENGTH_SHORT).show();
            return;
        }
        List<BongoCatStyleManager.ModelFunctionOption> functions =
                BongoCatStyleManager.modelFunctionOptions(this, styleId);
        BongoCatStyleManager.CapabilityReport report = BongoCatStyleManager.capabilityReport(this, styleId);
        String reportLabel = getString(R.string.keyboard_cat_capability_report_row,
                report.parameterCount, report.functionCount, report.keyParameterCount,
                report.physicsSettingCount, report.expressionCount, report.motionCount);
        final boolean hasPhysics = report.physicsSettingCount > 0;
        final boolean hasExpressions = report.expressionCount > 0;
        final int expressionRow = hasExpressions ? (hasPhysics ? 3 : 1) : -1;
        final int functionStart = 1 + (hasPhysics ? 2 : 0) + (hasExpressions ? 1 : 0);
        String[] labels = new String[functions.size() + functionStart + 1];
        labels[0] = reportLabel;
        if (hasPhysics) {
            String physicsTarget = Live2DPhysicsSettingsStore.keyboardCatTarget(styleId);
            labels[1] = getString(R.string.keyboard_cat_physics_strength_row,
                    Live2DPhysicsSettingsStore.getGlobalStrength(this, physicsTarget));
            labels[2] = getString(R.string.keyboard_cat_physics_groups_row, report.physicsSettingCount);
        }
        if (hasExpressions) labels[expressionRow] = getString(
                R.string.live2d_expression_debug_count, report.expressionCount);
        java.util.HashMap<String, KeyboardCatFunctionBindingStore.Binding> bindingsByToken =
                new java.util.HashMap<>();
        for (KeyboardCatFunctionBindingStore.Binding binding :
                KeyboardCatFunctionBindingStore.loadBindings(this, styleId)) {
            bindingsByToken.put(binding.token, binding);
        }
        for (int i = 0; i < functions.size(); i++) {
            BongoCatStyleManager.ModelFunctionOption option = functions.get(i);
            KeyboardCatFunctionBindingStore.Binding binding = bindingsByToken.get(option.token);
            labels[i + functionStart] = binding == null ? option.label
                    : option.label + "  ·  " + InputBinding.label(binding.inputCode);
        }
        labels[labels.length - 1] = getString(R.string.keyboard_cat_advanced_parameters,
                report.parameterCount);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.keyboard_cat_model_functions_title, style.displayLabel()))
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        showKeyboardCatCapabilityReport(style, report);
                    } else if (hasPhysics && which == 1) {
                        showKeyboardCatPhysicsStrengthDialog(styleId);
                    } else if (hasPhysics && which == 2) {
                        showKeyboardCatPhysicsGroupsDialog(styleId);
                    } else if (hasExpressions && which == expressionRow) {
                        showKeyboardCatExpressionDebugDialog(styleId);
                    } else if (which == labels.length - 1) {
                        showKeyboardCatAdvancedParametersDialog(styleId);
                    } else {
                        showKeyboardCatFunctionActionDialog(styleId, functions.get(which - functionStart));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showKeyboardCatCapabilityReport(BongoCatStyleManager.StyleInfo style,
                                                   BongoCatStyleManager.CapabilityReport report) {
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.keyboard_cat_capability_model, style.displayLabel())).append('\n');
        text.append(getString(R.string.keyboard_cat_capability_parameters, report.parameterCount)).append('\n');
        text.append(getString(R.string.keyboard_cat_capability_functions, report.functionCount)).append('\n');
        text.append(getString(R.string.keyboard_cat_capability_keys, report.keyParameterCount)).append('\n');
        text.append(getString(R.string.keyboard_cat_capability_mouse,
                yesNo(report.mouseX && report.mouseY), yesNo(report.mouseLeft), yesNo(report.mouseRight))).append('\n');
        text.append(getString(R.string.keyboard_cat_capability_physics, report.physicsSettingCount)).append('\n');
        text.append(getString(R.string.keyboard_cat_capability_expressions, report.expressionCount)).append('\n');
        text.append(getString(R.string.keyboard_cat_capability_motions, report.motionCount)).append('\n');
        text.append(getString(R.string.keyboard_cat_capability_idle,
                yesNo(report.eyeBlink), yesNo(report.breath)));
        new AlertDialog.Builder(this)
                .setTitle(R.string.keyboard_cat_capability_report_title)
                .setMessage(text.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String yesNo(boolean value) {
        return value ? getString(R.string.keyboard_cat_capability_yes) : getString(R.string.keyboard_cat_capability_no);
    }

    private void showKeyboardCatAdvancedParametersDialog(String styleId) {
        List<BongoCatStyleManager.ParameterOption> parameters =
                BongoCatStyleManager.parameterOptions(this, styleId, true);
        if (parameters.isEmpty()) {
            Toast.makeText(this, R.string.keyboard_cat_advanced_parameters_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[parameters.size()];
        for (int i = 0; i < parameters.size(); i++) {
            BongoCatStyleManager.ParameterOption option = parameters.get(i);
            String suffix = option.group.isEmpty() ? option.category : option.group;
            labels[i] = option.label + "  ·  " + suffix;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.keyboard_cat_advanced_parameters_title)
                .setItems(labels, (dialog, which) ->
                        showKeyboardCatParameterControl(styleId, parameters.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showKeyboardCatFunctionActionDialog(String styleId,
                                                       BongoCatStyleManager.ModelFunctionOption option) {
        if (option.token.startsWith("param:")) {
            String parameterId = option.token.substring("param:".length());
            for (BongoCatStyleManager.ParameterOption parameter :
                    BongoCatStyleManager.parameterOptions(this, styleId, true)) {
                if (parameter.id.equals(parameterId)) {
                    showKeyboardCatParameterControl(styleId, parameter);
                    return;
                }
            }
        }
        KeyboardCatFunctionBindingStore.Binding existing =
                KeyboardCatFunctionBindingStore.findByToken(this, styleId, option.token);
        String binding = existing == null ? getString(R.string.keyboard_cat_model_function_unbound)
                : getString(R.string.keyboard_cat_model_function_bound, InputBinding.label(existing.inputCode));
        String[] actions = new String[]{
                getString(R.string.keyboard_cat_model_function_test),
                getString(R.string.keyboard_cat_model_function_bind) + " · " + binding,
                getString(R.string.keyboard_cat_model_function_unbind)
        };
        new AlertDialog.Builder(this)
                .setTitle(option.label)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        testKeyboardCatModelAction(styleId, option.token, option.defaultTrigger);
                    } else if (which == 1) {
                        armKeyboardCatFunctionBinding(styleId, option.token, option.label, option.defaultTrigger);
                    } else {
                        KeyboardCatFunctionBindingStore.removeBinding(this, styleId, option.token);
                        AxonInputAccessibilityService.refreshActiveService();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showKeyboardCatParameterControl(String styleId,
                                                   BongoCatStyleManager.ParameterOption option) {
        String debugTarget = Live2DPhysicsSettingsStore.keyboardCatTarget(styleId);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), dp(4));

        TextView meta = createSupportingText();
        String group = option.group.isEmpty() ? getString(R.string.keyboard_cat_parameter_no_group) : option.group;
        meta.setText(getString(R.string.keyboard_cat_parameter_meta, option.id, group));
        panel.addView(meta, supportingParams(0));

        TextView realtime = createLabel();
        realtime.setText(R.string.live2d_parameter_realtime_waiting);
        panel.addView(realtime, supportingParams(dp(8)));
        TextView source = createSupportingText();
        source.setText(R.string.live2d_parameter_source_waiting);
        panel.addView(source, supportingParams(dp(2)));

        TextView valueLabel = createLabel();
        panel.addView(valueLabel, supportingParams(dp(8)));
        SeekBar value = new SeekBar(this);
        value.setMax(100);
        styleSeekBar(value);
        float stored = KeyboardCatFunctionBindingStore.getParameterValue(this, styleId, option.id, 0f);
        Float storedLock = Live2DDebugSettingsStore.getParameterLock(this, debugTarget, option.id);
        value.setProgress(Math.round((storedLock == null ? stored : storedLock) * 100f));
        valueLabel.setText(getString(R.string.keyboard_cat_parameter_value, value.getProgress()));
        panel.addView(value, seekBarLayoutParams(dp(2)));

        Switch lock = createSwitch(R.string.live2d_parameter_lock);
        lock.setChecked(storedLock != null);
        panel.addView(lock, supportingParams(dp(6)));

        value.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueLabel.setText(getString(R.string.keyboard_cat_parameter_value, progress));
                if (!fromUser) return;
                float normalized = progress / 100f;
                if (lock.isChecked()) {
                    AxonInputAccessibilityService.setKeyboardCatParameterLock(option.id, normalized);
                } else {
                    AxonInputAccessibilityService.setKeyboardCatModelParameter(option.id, normalized);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                float normalized = seekBar.getProgress() / 100f;
                if (lock.isChecked()) {
                    Live2DDebugSettingsStore.setParameterLock(
                            MainActivity.this, debugTarget, option.id, normalized);
                } else {
                    KeyboardCatFunctionBindingStore.setParameterValue(
                            MainActivity.this, styleId, option.id, normalized);
                }
            }
        });
        lock.setOnCheckedChangeListener((button, checked) -> {
            float normalized = value.getProgress() / 100f;
            if (checked) {
                Live2DDebugSettingsStore.setParameterLock(MainActivity.this, debugTarget, option.id, normalized);
                AxonInputAccessibilityService.setKeyboardCatParameterLock(option.id, normalized);
            } else {
                Live2DDebugSettingsStore.setParameterLock(MainActivity.this, debugTarget, option.id, null);
                AxonInputAccessibilityService.clearKeyboardCatParameterLock(option.id);
            }
        });

        TextView modeLabel = createLabel();
        modeLabel.setText(R.string.keyboard_cat_model_function_trigger_mode);
        panel.addView(modeLabel, supportingParams(dp(8)));
        String[] modeLabels = new String[]{
                getString(R.string.keyboard_cat_trigger_hold),
                getString(R.string.keyboard_cat_trigger_toggle),
                getString(R.string.keyboard_cat_trigger_pulse),
                getString(R.string.keyboard_cat_trigger_sequence)
        };
        Spinner modeSpinner = createChoiceSpinner(modeLabels);
        String currentMode = option.defaultTrigger;
        KeyboardCatFunctionBindingStore.Binding existing =
                KeyboardCatFunctionBindingStore.findByToken(this, styleId, option.token());
        if (existing != null) currentMode = existing.mode;
        int modeIndex = KeyboardCatFunctionBindingStore.MODE_HOLD.equals(currentMode) ? 0
                : KeyboardCatFunctionBindingStore.MODE_PULSE.equals(currentMode) ? 2
                : KeyboardCatFunctionBindingStore.MODE_SEQUENCE.equals(currentMode) ? 3 : 1;
        modeSpinner.setSelection(modeIndex, false);
        panel.addView(modeSpinner, seekBarLayoutParams(dp(2)));

        TextView bindingLabel = createSupportingText();
        if (existing == null) {
            bindingLabel.setText(R.string.keyboard_cat_model_function_unbound);
        } else {
            bindingLabel.setText(getString(R.string.keyboard_cat_model_function_bound,
                    InputBinding.label(existing.inputCode)));
        }
        panel.addView(bindingLabel, supportingParams(dp(8)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button test = new Button(this);
        test.setText(R.string.keyboard_cat_model_function_test);
        test.setAllCaps(false);
        styleActionButton(test);
        Button bind = new Button(this);
        bind.setText(R.string.keyboard_cat_model_function_bind);
        bind.setAllCaps(false);
        styleActionButton(bind);
        Button reset = new Button(this);
        reset.setText(R.string.keyboard_cat_model_function_reset);
        reset.setAllCaps(false);
        styleActionButton(reset);
        actions.addView(test, new LinearLayout.LayoutParams(0, dp(42), 1f));
        actions.addView(bind, new LinearLayout.LayoutParams(0, dp(42), 1f));
        actions.addView(reset, new LinearLayout.LayoutParams(0, dp(42), 1f));
        panel.addView(actions, supportingParams(dp(8)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(option.label)
                .setView(panel)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        test.setOnClickListener(v -> {
            String mode = triggerModeFromSpinner(modeSpinner);
            testKeyboardCatModelAction(styleId, option.token(), mode);
        });
        bind.setOnClickListener(v -> {
            String mode = triggerModeFromSpinner(modeSpinner);
            armKeyboardCatFunctionBinding(styleId, option.token(), option.label, mode);
            dialog.dismiss();
        });
        reset.setOnClickListener(v -> {
            KeyboardCatFunctionBindingStore.setParameterValue(MainActivity.this, styleId, option.id, null);
            Live2DDebugSettingsStore.setParameterLock(MainActivity.this, debugTarget, option.id, null);
            KeyboardCatFunctionBindingStore.removeBinding(MainActivity.this, styleId, option.token());
            lock.setChecked(false);
            value.setProgress(0);
            AxonInputAccessibilityService.resetKeyboardCatModelParameter(option.id);
            AxonInputAccessibilityService.clearKeyboardCatParameterLock(option.id);
            AxonInputAccessibilityService.refreshActiveService();
            bindingLabel.setText(R.string.keyboard_cat_model_function_unbound);
        });
        dialog.show();
        startParameterDebugTicker(dialog, option.id, true, realtime, source, lock, value);
    }

    private void testKeyboardCatModelAction(String styleId, String token, String mode) {
        AxonInputAccessibilityService.testKeyboardCatModelFunction(
                styleId, token, mode, true, success -> runOnUiThread(() -> {
                    if (!Boolean.TRUE.equals(success)) {
                        Toast.makeText(MainActivity.this,
                                AxonInputAccessibilityService.isServiceConnected()
                                        ? R.string.keyboard_cat_model_function_test_failed
                                        : R.string.keyboard_cat_model_function_test_unavailable,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(MainActivity.this, R.string.keyboard_cat_model_function_test_ok,
                            Toast.LENGTH_SHORT).show();
                }));
    }

    private String triggerModeFromSpinner(Spinner spinner) {
        int position = spinner == null ? 1 : spinner.getSelectedItemPosition();
        if (position == 0) return KeyboardCatFunctionBindingStore.MODE_HOLD;
        if (position == 2) return KeyboardCatFunctionBindingStore.MODE_PULSE;
        if (position == 3) return KeyboardCatFunctionBindingStore.MODE_SEQUENCE;
        return KeyboardCatFunctionBindingStore.MODE_TOGGLE;
    }

    private void armPhysicsHotkeyBinding(String target, String groupKey, String label) {
        if (customMappingCaptureStep != 0 || simultaneousClickCaptureStep != 0
                || forceHoldCaptureStep != 0 || gamepadMappingCaptureArmed) {
            Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        cancelKeyboardCatExpressionHotkeyCapture(true);
        cancelKeyboardCatFunctionBindingCapture();
        cancelHideDisplayHotkeyCapture(true);
        physicsHotkeyCaptureArmed = true;
        physicsHotkeyTarget = target == null ? Live2DPhysicsSettingsStore.TARGET_LIVE2D : target;
        physicsHotkeyGroupKey = groupKey == null ? "" : groupKey;
        physicsHotkeyGroupLabel = label == null ? physicsHotkeyGroupKey : label;
    }

    private void cancelPhysicsHotkeyBinding() {
        physicsHotkeyCaptureArmed = false;
        physicsHotkeyTarget = "";
        physicsHotkeyGroupKey = "";
        physicsHotkeyGroupLabel = "";
    }

    private void armKeyboardCatFunctionBinding(String styleId, String token, String label, String mode) {
        if (customMappingCaptureStep != 0 || simultaneousClickCaptureStep != 0
                || forceHoldCaptureStep != 0 || gamepadMappingCaptureArmed) {
            Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        cancelKeyboardCatExpressionHotkeyCapture(true);
        cancelPhysicsHotkeyBinding();
        cancelHideDisplayHotkeyCapture(true);
        keyboardCatFunctionBindingCaptureArmed = true;
        keyboardCatFunctionBindingStyleId = styleId == null || styleId.isEmpty()
                ? BongoCatStyleManager.BUILTIN_ID : styleId;
        keyboardCatFunctionBindingToken = token == null ? "" : token;
        keyboardCatFunctionBindingLabel = label == null ? "" : label;
        keyboardCatFunctionBindingMode = mode == null ? KeyboardCatFunctionBindingStore.MODE_TOGGLE : mode;
        Toast.makeText(this, R.string.keyboard_cat_model_function_binding_recording, Toast.LENGTH_LONG).show();
    }

    private void cancelKeyboardCatFunctionBindingCapture() {
        keyboardCatFunctionBindingCaptureArmed = false;
        keyboardCatFunctionBindingToken = "";
        keyboardCatFunctionBindingLabel = "";
        keyboardCatFunctionBindingStyleId = BongoCatStyleManager.BUILTIN_ID;
        keyboardCatFunctionBindingMode = KeyboardCatFunctionBindingStore.MODE_TOGGLE;
    }

    private void toggleKeyboardCatExpressionHotkeyCapture() {
        if (keyboardCatExpressions.isEmpty()) {
            Toast.makeText(this, R.string.keyboard_cat_expression_hotkey_no_expressions, Toast.LENGTH_SHORT).show();
            return;
        }
        if (keyboardCatExpressionHotkeyCaptureArmed) {
            cancelKeyboardCatExpressionHotkeyCapture(true);
            return;
        }
        if (customMappingCaptureStep != 0 || simultaneousClickCaptureStep != 0
                || forceHoldCaptureStep != 0 || gamepadMappingCaptureArmed) {
            Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        cancelHideDisplayHotkeyCapture(true);
        cancelKeyboardCatFunctionBindingCapture();
        cancelPhysicsHotkeyBinding();
        keyboardCatExpressionHotkeyPreviousKeyCode =
                OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this);
        if (keyboardCatExpressionHotkeyPreviousKeyCode >= 0) {
            // 绑定期间先停用旧快捷键，避免重绑按键同时触发旧动作。
            OverlayState.setKeyboardCatExpressionHotkeyKeyCode(this, -1);
        }
        keyboardCatExpressionHotkeyCaptureArmed = true;
        keyboardCatExpressionHotkeyCapturedKeyCode = -1;
        updateKeyboardCatExpressionHotkeyUi();
    }

    private void cancelKeyboardCatExpressionHotkeyCapture(boolean restorePrevious) {
        if (!keyboardCatExpressionHotkeyCaptureArmed) return;
        keyboardCatExpressionHotkeyCaptureArmed = false;
        keyboardCatExpressionHotkeyCapturedKeyCode = -1;
        if (restorePrevious && keyboardCatExpressionHotkeyPreviousKeyCode >= 0) {
            OverlayState.setKeyboardCatExpressionHotkeyKeyCode(
                    this, keyboardCatExpressionHotkeyPreviousKeyCode);
        }
        keyboardCatExpressionHotkeyPreviousKeyCode = -1;
        updateKeyboardCatExpressionHotkeyUi();
    }

    private void updateKeyboardCatExpressionHotkeyUi() {
        if (keyboardCatExpressionHotkeyButton == null
                || keyboardCatExpressionHotkeySelectionButton == null) return;
        boolean hasExpressions = !keyboardCatExpressions.isEmpty();
        setControlEnabled(keyboardCatExpressionHotkeyButton, hasExpressions);
        setControlEnabled(keyboardCatExpressionHotkeySelectionButton, hasExpressions);

        if (!hasExpressions) {
            keyboardCatExpressionHotkeyButton.setText(R.string.keyboard_cat_expression_hotkey_unavailable);
            keyboardCatExpressionHotkeySelectionButton.setText(
                    R.string.keyboard_cat_expression_hotkey_selection_empty);
            return;
        }
        if (keyboardCatExpressionHotkeyCaptureArmed) {
            keyboardCatExpressionHotkeyButton.setText(R.string.keyboard_cat_expression_hotkey_recording);
        } else {
            int keyCode = OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this);
            keyboardCatExpressionHotkeyButton.setText(keyCode >= 0
                    ? getString(R.string.keyboard_cat_expression_hotkey_bound, InputBinding.label(keyCode))
                    : getString(R.string.keyboard_cat_expression_hotkey_unbound));
        }

        String styleId = OverlayState.getKeyboardCatStyleId(this);
        boolean explicit = OverlayState.hasKeyboardCatExpressionHotkeySelection(this, styleId);
        Set<String> selected = OverlayState.getKeyboardCatExpressionHotkeySelection(this, styleId);
        int count = 0;
        for (BongoCatStyleManager.ExpressionOption option : keyboardCatExpressions) {
            if (!explicit || selected.contains(option.token)) count++;
        }
        keyboardCatExpressionHotkeySelectionButton.setText(getString(
                R.string.keyboard_cat_expression_hotkey_selection_count,
                count, keyboardCatExpressions.size()));
    }

    private void showKeyboardCatExpressionHotkeySelectionDialog() {
        if (keyboardCatExpressions.isEmpty()) {
            Toast.makeText(this, R.string.keyboard_cat_expression_hotkey_no_expressions, Toast.LENGTH_SHORT).show();
            return;
        }
        String styleId = OverlayState.getKeyboardCatStyleId(this);
        boolean explicit = OverlayState.hasKeyboardCatExpressionHotkeySelection(this, styleId);
        Set<String> selected = OverlayState.getKeyboardCatExpressionHotkeySelection(this, styleId);
        String[] labels = new String[keyboardCatExpressions.size()];
        boolean[] checked = new boolean[keyboardCatExpressions.size()];
        for (int i = 0; i < keyboardCatExpressions.size(); i++) {
            BongoCatStyleManager.ExpressionOption option = keyboardCatExpressions.get(i);
            labels[i] = option.label;
            checked[i] = !explicit || selected.contains(option.token);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.keyboard_cat_expression_hotkey_selection_dialog_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    LinkedHashSet<String> tokens = new LinkedHashSet<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) tokens.add(keyboardCatExpressions.get(i).token);
                    }
                    OverlayState.setKeyboardCatExpressionHotkeySelection(this, styleId, tokens);
                    updateKeyboardCatExpressionHotkeyUi();
                })
                .show();
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

    private void syncFontChoices() {
        if (fontChoiceSpinner == null) return;
        boolean previousInternalChange = internalChange;
        internalChange = true;
        importedFontChoices.clear();
        importedFontChoices.addAll(FontManager.listImportedFonts(this));

        ArrayList<String> labels = new ArrayList<>();
        if (importedFontChoices.isEmpty()) {
            labels.add(getString(R.string.font_import_none));
        } else {
            for (FontManager.FontInfo info : importedFontChoices) labels.add(info.name);
        }
        setChoiceSpinnerValues(fontChoiceSpinner, labels);
        fontChoiceSpinner.setEnabled(!importedFontChoices.isEmpty());
        fontChoiceSpinner.setAlpha(importedFontChoices.isEmpty() ? 0.55f : 1f);

        if (!importedFontChoices.isEmpty()) {
            String selectedId = FontManager.getSelectedImportedId(this);
            int selection = 0;
            for (int i = 0; i < importedFontChoices.size(); i++) {
                if (selectedId.equals(importedFontChoices.get(i).id)) {
                    selection = i;
                    break;
                }
            }
            fontChoiceSpinner.setSelection(selection, false);
            // “修改字体”的选择器只负责导入字体；开启功能后统一使用当前选中的导入字体。
            if (FontManager.isEnabled(this)) FontManager.setChoice(this, FontManager.CHOICE_IMPORTED);
        }
        internalChange = previousInternalChange;
    }

    private void syncGlobalHtmlChoices() {
        if (globalHtmlChoiceSpinner == null) return;
        boolean previousInternalChange = internalChange;
        internalChange = true;
        globalHtmlChoices.clear();
        globalHtmlChoices.addAll(GlobalHtmlStore.listDocuments(this));

        ArrayList<String> labels = new ArrayList<>();
        if (globalHtmlChoices.isEmpty()) {
            labels.add(getString(R.string.global_html_not_imported));
        } else {
            for (GlobalHtmlStore.HtmlInfo info : globalHtmlChoices) labels.add(info.name);
        }
        setChoiceSpinnerValues(globalHtmlChoiceSpinner, labels);
        globalHtmlChoiceSpinner.setEnabled(!globalHtmlChoices.isEmpty());
        globalHtmlChoiceSpinner.setAlpha(globalHtmlChoices.isEmpty() ? 0.55f : 1f);

        if (!globalHtmlChoices.isEmpty()) {
            String selectedId = GlobalHtmlStore.getSelectedId(this);
            int selection = 0;
            for (int i = 0; i < globalHtmlChoices.size(); i++) {
                if (selectedId.equals(globalHtmlChoices.get(i).id)) {
                    selection = i;
                    break;
                }
            }
            globalHtmlChoiceSpinner.setSelection(selection, false);
        }
        internalChange = previousInternalChange;
    }

    private void setChoiceSpinnerValues(Spinner spinner, List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
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
    }

    private void syncFontUi() {
        if (fontStatusText == null) return;
        boolean imported = FontManager.hasImportedFont(this);
        if (!FontManager.isEnabled(this)) {
            if (!imported) {
                fontStatusText.setText(R.string.font_import_default);
            } else {
                String name = FontManager.getImportedFontName(this);
                if (name == null || name.isEmpty()) name = getString(R.string.font_custom_name);
                fontStatusText.setText(getString(R.string.font_imported_disabled_format, name));
            }
            return;
        }

        if (!imported) {
            fontStatusText.setText(R.string.font_import_none);
            return;
        }

        int choice = FontManager.getChoice(this);
        if (choice == FontManager.CHOICE_IMPORTED) {
            if (!imported) {
                fontStatusText.setText(R.string.font_import_none);
            } else {
                String name = FontManager.getImportedFontName(this);
                if (name == null || name.isEmpty()) name = getString(R.string.font_custom_name);
                fontStatusText.setText(getString(R.string.font_imported_format, name));
            }
            return;
        }

        int labelRes = R.string.font_choice_system;
        if (choice == FontManager.CHOICE_SANS) labelRes = R.string.font_choice_sans;
        else if (choice == FontManager.CHOICE_SERIF) labelRes = R.string.font_choice_serif;
        else if (choice == FontManager.CHOICE_MONOSPACE) labelRes = R.string.font_choice_monospace;
        fontStatusText.setText(getString(R.string.font_current_format, getString(labelRes)));
    }

    private void syncLive2DTrackingService() {
        boolean shouldRun = OverlayState.isLive2DMotionTrackingEnabled(this)
                && OverlayState.isLive2DEnabled(this)
                && Live2DModelStore.exists(this)
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (shouldRun) Live2DMotionTrackingService.start(this);
        else Live2DMotionTrackingService.stop(this);
    }

    private void syncInAppLive2D() {
        boolean shouldShow = activityResumed
                && OverlayState.isLive2DEnabled(this)
                && Live2DModelStore.exists(this)
                && activityRoot != null;
        if (!shouldShow) {
            removeInAppLive2D();
            return;
        }

        // Once AccessibilityService is live, its TYPE_ACCESSIBILITY_OVERLAY renderer is deliberately
        // also used above Axon's own Activity. That same WebView then remains attached when the user
        // switches to a game, so there is no app-background handoff and no model reload.
        if (AxonInputAccessibilityService.isServiceConnected()) {
            removeInAppLive2D();
            AxonInputAccessibilityService.refreshLive2DOwnership();
            return;
        }

        String version = Live2DModelStore.getVersion(this);
        if (inAppLive2dView != null && version.equals(inAppLive2dVersion)) {
            inAppLive2dView.setRenderQuality(OverlayState.getLive2DRenderQuality(this));
            inAppLive2dView.setDisplayScalePercent(OverlayState.getLive2DSize(this));
            inAppLive2dView.setDisplayOffsetNormalized(
                    OverlayState.getLive2DOffsetX(this), OverlayState.getLive2DOffsetY(this));
            inAppLive2dView.setDragEnabled(false);
            inAppLive2dView.setHideWatermark(OverlayState.isLive2DHideWatermarkEnabled(this));
            inAppLive2dView.bringToFront();
            return;
        }

        removeInAppLive2D();
        try {
            Live2DOverlayView view = new Live2DOverlayView(this);
            view.setRenderQuality(OverlayState.getLive2DRenderQuality(this));
            view.setDisplayScalePercent(OverlayState.getLive2DSize(this));
            view.setDisplayOffsetNormalized(
                    OverlayState.getLive2DOffsetX(this), OverlayState.getLive2DOffsetY(this));
            view.setDragEnabled(false);
            view.setHideWatermark(OverlayState.isLive2DHideWatermarkEnabled(this));
            activityRoot.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.bringToFront();
            inAppLive2dView = view;
            inAppLive2dVersion = version;
            view.loadCurrentModel();
        } catch (Throwable error) {
            removeInAppLive2D();
        }
    }

    private void removeInAppLive2D() {
        Live2DOverlayView view = inAppLive2dView;
        inAppLive2dView = null;
        inAppLive2dVersion = "";
        if (view == null) return;
        try { view.release(); } catch (Throwable ignored) {}
        if (activityRoot != null) {
            try { activityRoot.removeView(view); } catch (Throwable ignored) {}
        }
    }

    private void openLive2DModelPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, LIVE2D_MODEL_IMPORT_REQUEST);
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
        if (GlobalHtmlStore.exists(this)) {
            String name = GlobalHtmlStore.getName(this);
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
        int[] current = left
                ? OverlayState.getMouseTrajectoryLeftColors(this)
                : OverlayState.getMouseTrajectoryRightColors(this);
        MultiColorPickerDialog.show(this,
                getString(left ? R.string.mouse_trajectory_left_color_title
                        : R.string.mouse_trajectory_right_color_title),
                current, colors -> {
                    if (left) OverlayState.setMouseTrajectoryLeftColors(this, colors);
                    else OverlayState.setMouseTrajectoryRightColors(this, colors);
                    updateColorDotSequence(sourceDot, colors);
                });
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
        // v1.9: shape selection has been retired. Every built-in key surface is a rounded
        // rectangle whose radius is controlled continuously from 0% (square) to 100%
        // (fully rounded/pill/circle where the bounds are square).
        TextView cornerLabel = createLabel();
        SeekBar cornerSeekBar = new LagSeekBar(this);
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

        LinearLayout baseColorRow = new LinearLayout(this);
        baseColorRow.setOrientation(LinearLayout.HORIZONTAL);
        baseColorRow.setGravity(Gravity.CENTER_VERTICAL);
        baseColorRow.setMinimumHeight(dp(44));
        TextView baseColorLabel = createLabel();
        baseColorLabel.setText(R.string.key_base_color);
        baseColorRow.addView(baseColorLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View baseDot = createColorDot(OverlayState.getKeyBaseColor(this, displayType));
        keyBaseColorDots.put(displayType, baseDot);
        updateColorDotSequence(baseDot, OverlayState.getKeyBaseColors(this, displayType));
        LinearLayout.LayoutParams baseDotParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        baseDotParams.leftMargin = dp(12);
        baseColorRow.addView(baseDot, baseDotParams);
        View.OnClickListener openBaseColor = v -> showKeyBaseColorDialog(displayType, baseDot);
        baseColorRow.setOnClickListener(openBaseColor);
        baseColorRow.setBackground(createRippleBackground(UiPalette.debugSurface(this), 8f));
        UiMotion.bindPressFeedback(baseColorRow);
        baseDot.setOnClickListener(openBaseColor);
        parent.addView(baseColorRow, supportingParams(dp(2)));

        LinearLayout borderColorRow = new LinearLayout(this);
        borderColorRow.setOrientation(LinearLayout.HORIZONTAL);
        borderColorRow.setGravity(Gravity.CENTER_VERTICAL);
        borderColorRow.setMinimumHeight(dp(44));
        TextView borderColorLabel = createLabel();
        borderColorLabel.setText(R.string.key_border_color);
        borderColorRow.addView(borderColorLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View borderDot = createColorDot(OverlayState.getKeyBorderColor(this, displayType));
        keyBorderColorDots.put(displayType, borderDot);
        updateColorDotSequence(borderDot, OverlayState.getKeyBorderColors(this, displayType));
        LinearLayout.LayoutParams borderDotParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        borderDotParams.leftMargin = dp(12);
        borderColorRow.addView(borderDot, borderDotParams);
        View.OnClickListener openBorderColor = v -> showKeyBorderColorDialog(displayType, borderDot);
        borderColorRow.setOnClickListener(openBorderColor);
        borderColorRow.setBackground(createRippleBackground(UiPalette.debugSurface(this), 8f));
        UiMotion.bindPressFeedback(borderColorRow);
        borderDot.setOnClickListener(openBorderColor);
        parent.addView(borderColorRow, supportingParams(dp(2)));

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
        updateColorDotSequence(dot, OverlayState.getKeyPressColors(this, displayType));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        dotParams.leftMargin = dp(12);
        colorRow.addView(dot, dotParams);
        View.OnClickListener openColor = v -> showKeyPressColorDialog(displayType, dot);
        colorRow.setOnClickListener(openColor);
        colorRow.setBackground(createRippleBackground(UiPalette.debugSurface(this), 8f));
        UiMotion.bindPressFeedback(colorRow);
        dot.setOnClickListener(openColor);
        parent.addView(colorRow, supportingParams(dp(2)));

        if (displayType != GamepadOverlayView.DISPLAY_LEFT_STICK
                && displayType != GamepadOverlayView.DISPLAY_RIGHT_STICK) {
            addKeyTextColorControl(parent, displayType);
        }
    }

    private void addKeyTextColorControl(LinearLayout parent, int displayType) {
        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        colorRow.setMinimumHeight(dp(44));

        TextView colorLabel = createLabel();
        colorLabel.setText(R.string.key_text_color);
        colorRow.addView(colorLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View textDot = createColorDot(OverlayState.getKeyTextColor(this, displayType));
        keyTextColorDots.put(displayType, textDot);
        updateColorDotSequence(textDot, OverlayState.getKeyTextColors(this, displayType));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        dotParams.leftMargin = dp(12);
        colorRow.addView(textDot, dotParams);

        View.OnClickListener openColor = v -> showKeyTextColorDialog(displayType, textDot);
        colorRow.setOnClickListener(openColor);
        colorRow.setBackground(createRippleBackground(UiPalette.debugSurface(this), 8f));
        UiMotion.bindPressFeedback(colorRow);
        textDot.setOnClickListener(openColor);
        parent.addView(colorRow, supportingParams(dp(2)));
    }


    private View addStickCenterColorControl(LinearLayout parent, int displayType) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));

        TextView label = createLabel();
        label.setText(R.string.gamepad_stick_center_color);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View dot = createColorDot(OverlayState.getGamepadStickCenterColor(this, displayType));
        updateColorDotSequence(dot, OverlayState.getGamepadStickCenterColors(this, displayType));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
        lp.leftMargin = dp(12);
        row.addView(dot, lp);

        View.OnClickListener open = v -> showRgbColorDialog(
                R.string.gamepad_stick_center_color_title,
                OverlayState.getGamepadStickCenterColors(this, displayType),
                dot,
                colors -> OverlayState.setGamepadStickCenterColors(MainActivity.this, displayType, colors));
        row.setOnClickListener(open);
        dot.setOnClickListener(open);
        row.setBackground(createRippleBackground(UiPalette.debugSurface(this), 8f));
        UiMotion.bindPressFeedback(row);
        parent.addView(row, supportingParams(dp(2)));
        return dot;
    }

    private void addDpsTextColorControl(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        TextView label = createLabel();
        label.setText(R.string.key_text_color);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dpsTextColorDot = createColorDot(OverlayState.getDpsTextColor(this));
        updateColorDotSequence(dpsTextColorDot, OverlayState.getDpsTextColors(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
        lp.leftMargin = dp(12);
        row.addView(dpsTextColorDot, lp);
        View.OnClickListener open = v -> showRgbColorDialog(
                R.string.key_text_color_title, OverlayState.getDpsTextColors(this), dpsTextColorDot,
                colors -> OverlayState.setDpsTextColors(MainActivity.this, colors));
        row.setOnClickListener(open);
        dpsTextColorDot.setOnClickListener(open);
        row.setBackground(createRippleBackground(UiPalette.debugSurface(this), 8f));
        UiMotion.bindPressFeedback(row);
        parent.addView(row, supportingParams(dp(2)));
    }

    private void showKeyTextColorDialog(int displayType, View sourceDot) {
        showRgbColorDialog(
                R.string.key_text_color_title,
                OverlayState.getKeyTextColors(this, displayType),
                sourceDot,
                colors -> OverlayState.setKeyTextColors(MainActivity.this, displayType, colors));
    }


    private interface ColorCommit {
        void apply(int[] colors);
    }

    private void showKeyBaseColorDialog(int displayType, View sourceDot) {
        showRgbColorDialog(
                R.string.key_base_color_title,
                OverlayState.getKeyBaseColors(this, displayType),
                sourceDot,
                colors -> OverlayState.setKeyBaseColors(MainActivity.this, displayType, colors));
    }

    private void showKeyBorderColorDialog(int displayType, View sourceDot) {
        showRgbColorDialog(
                R.string.key_border_color_title,
                OverlayState.getKeyBorderColors(this, displayType),
                sourceDot,
                colors -> OverlayState.setKeyBorderColors(MainActivity.this, displayType, colors));
    }

    private void showKeyPressColorDialog(int displayType, View sourceDot) {
        showRgbColorDialog(
                R.string.key_press_color_title,
                OverlayState.getKeyPressColors(this, displayType),
                sourceDot,
                colors -> OverlayState.setKeyPressColors(MainActivity.this, displayType, colors));
    }

    private void showRgbColorDialog(int titleRes, int[] current, View sourceDot, ColorCommit commit) {
        MultiColorPickerDialog.show(this, getString(titleRes), current, colors -> {
            commit.apply(colors);
            updateColorDotSequence(sourceDot, colors);
        });
    }

    private void updateColorDotSequence(View dot, int[] colors) {
        int[] normalized = ColorSequence.normalize(colors, Color.WHITE);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        if (normalized.length == 1) {
            drawable.setColor(normalized[0]);
        } else {
            drawable.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
            drawable.setColors(normalized);
        }
        dot.setBackground(drawable);
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
        SeekBar seekBar = new LagSeekBar(this);
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


    private static final class KeyLayerOpacityControl {
        final TextView backgroundLabel;
        final SeekBar backgroundSeek;
        final TextView strokeLabel;
        final SeekBar strokeSeek;
        final TextView textLabel;
        final SeekBar textSeek;
        final TextView diffusionLabel;
        final SeekBar diffusionSeek;
        final boolean diffusionAsCenterOpacity;

        KeyLayerOpacityControl(TextView backgroundLabel, SeekBar backgroundSeek,
                               TextView strokeLabel, SeekBar strokeSeek,
                               TextView textLabel, SeekBar textSeek,
                               TextView diffusionLabel, SeekBar diffusionSeek,
                               boolean diffusionAsCenterOpacity) {
            this.backgroundLabel = backgroundLabel;
            this.backgroundSeek = backgroundSeek;
            this.strokeLabel = strokeLabel;
            this.strokeSeek = strokeSeek;
            this.textLabel = textLabel;
            this.textSeek = textSeek;
            this.diffusionLabel = diffusionLabel;
            this.diffusionSeek = diffusionSeek;
            this.diffusionAsCenterOpacity = diffusionAsCenterOpacity;
        }
    }

    private void addKeyLayerOpacityControls(LinearLayout parent, int displayType) {
        TextView backgroundLabel = createLabel();
        SeekBar backgroundSeek = createOpacitySeekBar();
        TextView strokeLabel = createLabel();
        SeekBar strokeSeek = createOpacitySeekBar();
        boolean stickDisplay = isStickDisplay(displayType);
        boolean showTextOpacity = !stickDisplay;
        TextView textLabel = showTextOpacity ? createLabel() : null;
        SeekBar textSeek = showTextOpacity ? createOpacitySeekBar() : null;
        TextView diffusionLabel = createLabel();
        SeekBar diffusionSeek = createOpacitySeekBar();

        parent.addView(backgroundLabel, supportingParams(dp(2)));
        parent.addView(backgroundSeek, seekBarLayoutParams(dp(3)));
        parent.addView(strokeLabel, supportingParams(dp(2)));
        parent.addView(strokeSeek, seekBarLayoutParams(dp(3)));
        if (showTextOpacity) {
            parent.addView(textLabel, supportingParams(dp(2)));
            parent.addView(textSeek, seekBarLayoutParams(dp(3)));
        }
        parent.addView(diffusionLabel, supportingParams(dp(2)));
        parent.addView(diffusionSeek, seekBarLayoutParams(dp(4)));

        keyLayerOpacityControls.put(displayType, new KeyLayerOpacityControl(
                backgroundLabel, backgroundSeek, strokeLabel, strokeSeek, textLabel, textSeek,
                diffusionLabel, diffusionSeek, stickDisplay));

        bindKeyLayerOpacity(backgroundSeek, backgroundLabel, displayType, 0);
        bindKeyLayerOpacity(strokeSeek, strokeLabel, displayType, 1);
        if (showTextOpacity) bindKeyLayerOpacity(textSeek, textLabel, displayType, 2);
        bindKeyLayerOpacity(diffusionSeek, diffusionLabel, displayType, 3);
        updateDiffusionOpacityEnabled(displayType,
                OverlayState.getMotionMode(this, displayType) == OverlayState.MOTION_RIPPLE);
    }

    private SeekBar createCornerStrengthSeekBar() {
        SeekBar seekBar = new LagSeekBar(this);
        seekBar.setMax(100);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        return seekBar;
    }

    private SeekBar.OnSeekBarChangeListener cornerStrengthListener(TextView label, IntSetter setter) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(getString(R.string.gamepad_stick_center_corner_strength_format, progress));
                if (fromUser && !internalChange) setter.set(progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private SeekBar createOpacitySeekBar() {
        SeekBar seekBar = new LagSeekBar(this);
        seekBar.setMax(OPACITY_MAX);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        return seekBar;
    }

    private void bindKeyLayerOpacity(SeekBar seek, TextView label, int displayType, int layer) {
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int format = layer == 0 ? R.string.key_background_opacity_format
                        : layer == 1 ? R.string.key_stroke_opacity_format
                        : layer == 2 ? R.string.key_text_opacity_format
                        : isStickDisplay(displayType)
                        ? R.string.gamepad_stick_center_opacity_format
                        : R.string.key_diffusion_opacity_format;
                label.setText(getString(format, progress));
                if (!fromUser || internalChange || (layer == 3 && !bar.isEnabled())) return;
                if (layer == 0) OverlayState.setKeyBackgroundOpacity(MainActivity.this, displayType, progress);
                else if (layer == 1) OverlayState.setKeyStrokeOpacity(MainActivity.this, displayType, progress);
                else if (layer == 2) OverlayState.setKeyTextOpacity(MainActivity.this, displayType, progress);
                else OverlayState.setKeyDiffusionOpacity(MainActivity.this, displayType, progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }


    private static boolean isStickDisplay(int displayType) {
        return displayType == GamepadOverlayView.DISPLAY_LEFT_STICK
                || displayType == GamepadOverlayView.DISPLAY_RIGHT_STICK;
    }

    private boolean hasSelectableMotionMode(int displayType) {
        return displayType == KeyOverlayView.DISPLAY_KEYBOARD
                || displayType == FullKeyboardOverlayView.DISPLAY_FULL_KEYBOARD
                || displayType == KeyOverlayView.DISPLAY_MOUSE
                || displayType == KeyPromptOverlayView.DISPLAY_KEY_PROMPT
                || displayType == KeyOverlayView.DISPLAY_CUSTOM
                || displayType == OverlayState.DISPLAY_TOUCH_APPEARANCE
                || displayType == GamepadOverlayView.DISPLAY_LEFT_STICK
                || displayType == GamepadOverlayView.DISPLAY_RIGHT_STICK
                || displayType == GamepadOverlayView.DISPLAY_FACE
                || displayType == GamepadOverlayView.DISPLAY_DPAD
                || displayType == GamepadOverlayView.DISPLAY_LEFT_SHOULDER
                || displayType == GamepadOverlayView.DISPLAY_RIGHT_SHOULDER
                || displayType == GamepadOverlayView.DISPLAY_BACK;
    }

    private static int motionModeSelection(int mode) {
        if (mode == OverlayState.MOTION_ALPHA) return 1;
        if (mode == OverlayState.MOTION_RIPPLE) return 2;
        if (mode == OverlayState.MOTION_NONE) return 3;
        return 0;
    }

    private void syncMotionControlsFromState() {
        for (int i = 0; i < motionSpinners.size(); i++) {
            int displayType = motionSpinners.keyAt(i);
            Spinner spinner = motionSpinners.valueAt(i);
            int mode = OverlayState.getMotionMode(this, displayType);
            spinner.setSelection(motionModeSelection(mode), false);
            updateDiffusionOpacityEnabled(displayType, mode == OverlayState.MOTION_RIPPLE);
        }
    }

    private void updateDiffusionOpacityEnabled(int displayType, boolean diffusionModeSelected) {
        KeyLayerOpacityControl control = keyLayerOpacityControls.get(displayType);
        if (control == null) return;
        if (control.diffusionAsCenterOpacity) {
            // Sticks reuse the renderer's diffusion alpha as the center-dot alpha. It is always
            // meaningful and is presented as "center opacity" rather than a fake diffusion control.
            control.diffusionLabel.setVisibility(View.VISIBLE);
            control.diffusionSeek.setVisibility(View.VISIBLE);
            control.diffusionSeek.setEnabled(true);
            control.diffusionSeek.setAlpha(1f);
            control.diffusionLabel.setAlpha(1f);
            return;
        }
        boolean visible = !hasSelectableMotionMode(displayType) || diffusionModeSelected;
        control.diffusionLabel.setVisibility(visible ? View.VISIBLE : View.GONE);
        control.diffusionSeek.setVisibility(visible ? View.VISIBLE : View.GONE);
        control.diffusionSeek.setEnabled(visible);
        control.diffusionSeek.setAlpha(1f);
        control.diffusionLabel.setAlpha(1f);
    }

    private TextView createTitle() {
        TextView title = new TextView(this);
        title.setTextColor(UiPalette.textPrimary(this));
        title.setTextSize(21f);
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

    private void setControlEnabled(View view, boolean enabled) {
        if (view == null) return;
        UiChrome.setEnabledVisual(view, enabled);
    }

    private Switch createSwitch(int labelRes) {
        Switch view = new MotionSwitch(this);
        view.setText(labelRes);
        view.setTextColor(UiPalette.textPrimary(this));
        view.setTextSize(14.5f);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(0, 0, 0, 0);
        view.setSingleLine(false);
        UiChrome.styleSwitch(this, view);
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
        details.setPadding(dp(14), dp(12), dp(14), dp(12));
        details.setBackground(UiChrome.nestedSurface(this));
        details.setVisibility(View.GONE);
        return details;
    }

    private LinearLayout createSwitchGroup(Switch primary) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(16), dp(3), dp(16), dp(3));
        group.setBackground(UiChrome.card(this));
        group.addView(primary, switchParams(0));
        return group;
    }

    private LinearLayout createFeatureGroup(Switch primary, LinearLayout details) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(16), dp(3), dp(12), dp(12));
        group.setBackground(UiChrome.card(this));
        UiMotion.enableLayoutMotion(group);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(primary, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout disclosureTarget = new LinearLayout(this);
        disclosureTarget.setOrientation(LinearLayout.HORIZONTAL);
        disclosureTarget.setGravity(Gravity.CENTER);
        disclosureTarget.setPadding(dp(10), 0, dp(8), 0);
        disclosureTarget.setMinimumHeight(dp(44));
        disclosureTarget.setContentDescription(getString(R.string.details_expand));
        disclosureTarget.setBackground(UiChrome.controlRipple(this));

        TextView settingsLabel = new TextView(this);
        settingsLabel.setText(R.string.details_action);
        settingsLabel.setTextColor(UiPalette.textTertiary(this));
        settingsLabel.setTextSize(11.5f);
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
        Spinner spinner = new AnimatedChoiceSpinner(this);
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

    private TextView createSpinnerText(String value, boolean dropdown) {
        TextView text = new TextView(this);
        text.setText(value == null ? "" : value);
        text.setTextColor(UiPalette.textPrimary(this));
        text.setTextSize(13f);
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMinHeight(dp(dropdown ? 48 : 44));
        text.setPadding(dp(14), 0, dp(14), 0);
        if (!dropdown) {
            text.setCompoundDrawables(null, null, UiChrome.chevron(this), null);
            text.setCompoundDrawablePadding(dp(8));
        }
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
        group.setPadding(dp(16), dp(5), dp(12), dp(5));
        group.setBackground(UiChrome.card(this));
        return group;
    }

    private void styleActionButton(Button button) {
        UiChrome.styleSecondaryButton(this, button);
    }

    private Drawable createRippleBackground(int fillColor, float radiusDp) {
        return UiChrome.ripple(this, fillColor, radiusDp);
    }

    private void styleSeekBar(SeekBar seekBar) {
        UiChrome.styleSeekBar(this, seekBar);
    }

    private SectionNavigationBar createSectionNavigation(int[] labelResIds, int pageCount) {
        SectionNavigationBar nav = new SectionNavigationBar();

        int count = Math.min(labelResIds.length, pageCount);
        SectionNavigationItem[] items = new SectionNavigationItem[count];
        for (int i = 0; i < count; i++) {
            final int pageIndex = i;
            SectionNavigationItem item = new SectionNavigationItem(labelResIds[i], i);
            items[i] = item;
            item.setFocusable(true);
            item.setDefaultFocusHighlightEnabled(false);
            item.setOnClickListener(v -> selectSectionPage(pageIndex, true));
            UiMotion.bindPressFeedback(item);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) params.leftMargin = dp(2);
            nav.addNavigationItem(item, params);
        }
        sectionNavigationItems = items;
        for (int i = 0; i < items.length; i++) {
            items[i].setSelected(i == 0);
            items[i].setActive(i == 0, false);
        }
        nav.moveSelectionTo(0, false);
        return nav;
    }

    /**
     * One shared selection surface lives behind all destinations. It is translated instead
     * of recreating a selected background on every item, so the active surface visibly
     * travels from the previous destination to the next and remains interruptible.
     */
    private final class SectionNavigationBar extends FrameLayout {
        private final View selectionIndicator;
        private final LinearLayout itemRow;
        private int pendingSelectedIndex;

        SectionNavigationBar() {
            super(MainActivity.this);
            setClipChildren(false);
            setClipToPadding(false);
            setBackgroundColor(UiPalette.surfaceRaised(MainActivity.this));

            selectionIndicator = new View(MainActivity.this);
            selectionIndicator.setClickable(false);
            selectionIndicator.setFocusable(false);
            selectionIndicator.setBackground(UiPalette.rounded(
                    MainActivity.this, UiPalette.controlSurface(MainActivity.this), 10f));
            selectionIndicator.setAlpha(1f);
            addView(selectionIndicator, new FrameLayout.LayoutParams(1, 1));

            itemRow = new LinearLayout(MainActivity.this);
            itemRow.setOrientation(LinearLayout.HORIZONTAL);
            itemRow.setGravity(Gravity.CENTER_VERTICAL);
            itemRow.setPadding(dp(8), dp(5), dp(8), dp(5));
            addView(itemRow, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void addNavigationItem(SectionNavigationItem item, LinearLayout.LayoutParams params) {
            itemRow.addView(item, params);
        }

        void moveSelectionTo(int requestedIndex, boolean animated) {
            if (itemRow.getChildCount() == 0) return;
            pendingSelectedIndex = Math.max(0, Math.min(requestedIndex, itemRow.getChildCount() - 1));
            if (!isLaidOut() || itemRow.getWidth() == 0) {
                post(() -> moveSelectionTo(pendingSelectedIndex, false));
                return;
            }

            View target = itemRow.getChildAt(pendingSelectedIndex);
            if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
                post(() -> moveSelectionTo(pendingSelectedIndex, false));
                return;
            }

            final int inset = dp(1);
            int width = Math.max(1, target.getWidth() - inset * 2);
            int height = Math.max(1, target.getHeight() - inset * 2);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) selectionIndicator.getLayoutParams();
            if (lp.width != width || lp.height != height) {
                lp.width = width;
                lp.height = height;
                selectionIndicator.setLayoutParams(lp);
            }

            float targetX = itemRow.getX() + target.getX() + inset;
            float targetY = itemRow.getY() + target.getY() + inset;
            if (!animated) {
                selectionIndicator.animate().cancel();
                selectionIndicator.setTranslationX(targetX);
                selectionIndicator.setTranslationY(targetY);
                return;
            }

            // The shared surface is the reference motion for the whole app: each tap redirects
            // the currently visible surface instead of recreating selection at the destination.
            UiMotion.moveSelectionSurface(selectionIndicator, targetX, targetY);
        }
    }

    /**
     * Bottom navigation destination. Icons are persistent; labels only become visible for
     * the active destination so seven sections remain calm and readable on phone widths.
     */
    private final class SectionNavigationItem extends LinearLayout {
        private final int iconIndex;
        private final ImageView iconView;
        private final TextView labelView;
        private boolean active;

        SectionNavigationItem(int labelResId, int iconIndex) {
            super(MainActivity.this);
            this.iconIndex = iconIndex;
            setContentDescription(getString(labelResId));
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setPadding(dp(3), dp(4), dp(3), dp(3));
            setMinimumHeight(dp(54));

            iconView = new ImageView(MainActivity.this);
            iconView.setScaleType(ImageView.ScaleType.CENTER);
            addView(iconView, new LinearLayout.LayoutParams(dp(22), dp(22)));

            labelView = new TextView(MainActivity.this);
            labelView.setText(labelResId);
            labelView.setTextSize(9.5f);
            labelView.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            labelView.setSingleLine(true);
            labelView.setEllipsize(TextUtils.TruncateAt.END);
            labelView.setGravity(Gravity.CENTER);
            labelView.setAlpha(0f);
            labelView.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(16));
            labelParams.topMargin = dp(1);
            addView(labelView, labelParams);
        }

        void setActive(boolean nextActive, boolean animated) {
            if (active == nextActive && iconView.getDrawable() != null) return;
            active = nextActive;
            iconView.animate().cancel();
            labelView.animate().cancel();

            iconView.setImageDrawable(UiChrome.sectionIcon(
                    MainActivity.this, iconIndex, nextActive));
            labelView.setTextColor(nextActive
                    ? UiPalette.textPrimary(MainActivity.this)
                    : UiPalette.textTertiary(MainActivity.this));
            // Selection fill is owned by SectionNavigationBar's single moving indicator.
            // Items keep only local press feedback so no second background flashes in place.
            setBackground(UiChrome.ripple(
                    MainActivity.this,
                    Color.TRANSPARENT,
                    10f));

            float lift = nextActive ? -dp(1) : 0f;
            if (!animated) {
                iconView.setTranslationY(lift);
                iconView.setScaleX(1f);
                iconView.setScaleY(1f);
                labelView.setTranslationY(0f);
                labelView.setAlpha(nextActive ? 1f : 0f);
                labelView.setVisibility(nextActive ? View.VISIBLE : View.INVISIBLE);
                return;
            }

            iconView.animate()
                    .translationY(lift)
                    .setDuration(UiMotion.stateMs())
                    .setInterpolator(UiMotion.easeMove())
                    .start();

            if (nextActive) {
                // If a previous fade-out was interrupted, keep its current alpha/position and
                // simply redirect to the selected state. Only a truly hidden label gets an enter
                // offset, so rapid tab taps never flash from alpha=0 again.
                if (labelView.getVisibility() != View.VISIBLE) {
                    labelView.setVisibility(View.VISIBLE);
                    labelView.setAlpha(0f);
                    labelView.setTranslationY(dp(3));
                }
                labelView.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(UiMotion.enterMs())
                        .setInterpolator(UiMotion.easeOut())
                        .start();
            } else {
                labelView.animate()
                        .alpha(0f)
                        .translationY(dp(2))
                        .setDuration(UiMotion.exitMs())
                        .setInterpolator(UiMotion.easeOut())
                        .withEndAction(() -> {
                            if (!active) {
                                labelView.setVisibility(View.INVISIBLE);
                                labelView.setTranslationY(0f);
                            }
                        })
                        .start();
            }
        }
    }

    private void selectSectionPage(int requestedIndex, boolean centerNavigation) {
        if (sectionPageViews == null || sectionPageViews.length == 0) return;

        int target = Math.max(0, Math.min(requestedIndex, sectionPageViews.length - 1));
        selectedSectionPage = target;

        if (sectionNavigationItems != null) {
            updateSectionNavigationSelection(sectionNavigationItems, target, centerNavigation);
        }


        // Initial setup is immediate. All pages already exist; only one is visible.
        if (!centerNavigation) {
            sectionPageTransitionToken++;
            displayedSectionPage = target;
            normalizeSectionPages(target);
            return;
        }

        if (target == displayedSectionPage) return;

        // Interruptions preserve the current transforms. The next page request therefore starts
        // from exactly what is on screen, matching the moving bottom-selection surface instead of
        // snapping the previous transition to an endpoint first.
        int from = displayedSectionPage;
        int direction = target > from ? 1 : -1;
        int transitionToken = ++sectionPageTransitionToken;
        UiMotion.interruptPageTransition(sectionPageViews);
        prepareSectionPagesForTransition(from, target);

        ScrollView outgoing = sectionPageViews[from];
        ScrollView incoming = sectionPageViews[target];
        boolean incomingWasVisible = incoming.getVisibility() == View.VISIBLE;
        if (!incomingWasVisible) {
            incoming.stopNestedScroll();
            incoming.scrollTo(0, 0);
            incoming.post(() -> incoming.scrollTo(0, 0));
        }
        incoming.setVisibility(View.VISIBLE);
        incoming.bringToFront();

        // Treat the destination as current as soon as motion starts. A further tap can redirect
        // this same motion without waiting for the current page animation to finish.
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
            content.setPadding(dp(18), dp(8), dp(18), dp(28));
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

    private void prepareSectionPagesForTransition(int fromIndex, int targetIndex) {
        if (sectionPageViews == null) return;
        for (int i = 0; i < sectionPageViews.length; i++) {
            if (i == fromIndex || i == targetIndex) continue;
            ScrollView page = sectionPageViews[i];
            UiMotion.resetPageMotion(page);
            page.setVisibility(View.INVISIBLE);
        }
        if (fromIndex >= 0 && fromIndex < sectionPageViews.length) {
            sectionPageViews[fromIndex].setVisibility(View.VISIBLE);
        }
    }

    private void normalizeSectionPages(int visibleIndex) {
        if (sectionPageViews == null) return;
        int safe = Math.max(0, Math.min(visibleIndex, sectionPageViews.length - 1));
        for (int i = 0; i < sectionPageViews.length; i++) {
            ScrollView page = sectionPageViews[i];
            UiMotion.resetPageMotion(page);
            page.setVisibility(i == safe ? View.VISIBLE : View.INVISIBLE);
        }
        sectionPageViews[safe].bringToFront();
    }

    private void updateSectionNavigationSelection(
            SectionNavigationItem[] items, int selectedIndex, boolean animated) {
        if (sectionNavigationView != null) {
            sectionNavigationView.moveSelectionTo(selectedIndex, animated);
        }
        for (int i = 0; i < items.length; i++) {
            items[i].setSelected(i == selectedIndex);
            items[i].setActive(i == selectedIndex, animated);
        }
    }

    /**
     * Observe horizontal gestures without consuming the touch stream. Vertical scrolling and
     * child controls keep their native behaviour; a completed horizontal swipe only changes
     * the top-level settings page after the child has received ACTION_UP.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        captureBindableMousePress(event);
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
            refreshHideDisplayHotkeyUi();
            handleDisplayModeChanged();
        });
    }

    private void bindSimpleSwitch(Switch toggle, BooleanSetter setter) {
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (!internalChange) setter.set(enabled);
        });
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
        SeekBar seekBar = new LagSeekBar(this);
        seekBar.setMax(SIZE_MAX - SIZE_MIN);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        return seekBar;
    }

    private SeekBar createKeySpacingSeekBar() {
        SeekBar seekBar = new LagSeekBar(this);
        seekBar.setMax(KEY_SPACING_MAX);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        return seekBar;
    }

    private SeekBar createSensitivitySeekBar() {
        SeekBar seekBar = new LagSeekBar(this);
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

    /**
     * Restores every native SeekBar and its value label from persisted state.
     *
     * The v1.8 UI refactor previously relied on onProgressChanged() to populate labels. Android
     * does not dispatch that callback when setProgress() receives the SeekBar's current value
     * (notably the default zero), so labels could stay empty until the user dragged the slider.
     * Keep state restoration explicit instead of depending on a side-effect of SeekBar callbacks.
     */
    private void syncSeekBarUiFromState() {
        syncBoundedSeekBar(keyboardSizeSeekBar, keyboardSizeLabel,
                R.string.keyboard_size_format, OverlayState.getKeyboardSize(this), SIZE_MIN);
        syncBoundedSeekBar(keyboardSpacingSeekBar, keyboardSpacingLabel,
                R.string.keyboard_spacing_format, OverlayState.getKeyboardSpacing(this), 0);
        syncBoundedSeekBar(inputFullKeyboardSizeSeekBar, inputFullKeyboardSizeLabel,
                R.string.full_keyboard_size_format, OverlayState.getFullKeyboardSize(this), SIZE_MIN);
        syncBoundedSeekBar(touchDisplaySizeSeekBar, touchDisplaySizeLabel,
                R.string.touch_display_size_format, OverlayState.getTouchDisplaySize(this), SIZE_MIN);
        syncBoundedSeekBar(touchDisplaySpacingSeekBar, touchDisplaySpacingLabel,
                R.string.touch_display_spacing_format, OverlayState.getTouchDisplaySpacing(this), 0);
        syncBoundedSeekBar(mouseSizeSeekBar, mouseSizeLabel,
                R.string.mouse_size_format, OverlayState.getMouseSize(this), SIZE_MIN);
        syncBoundedSeekBar(keyboardCatSizeSeekBar, keyboardCatSizeLabel,
                R.string.keyboard_cat_size_format, OverlayState.getKeyboardCatSize(this), SIZE_MIN);
        syncBoundedSeekBar(keyPromptSizeSeekBar, keyPromptSizeLabel,
                R.string.key_prompt_size_format, OverlayState.getKeyPromptSize(this), SIZE_MIN);
        syncBoundedSeekBar(mouseTrajectorySizeSeekBar, mouseTrajectorySizeLabel,
                R.string.mouse_trajectory_size_format, OverlayState.getMouseTrajectorySize(this), SIZE_MIN);
        syncBoundedSeekBar(mouseTrajectoryDotSizeSeekBar, mouseTrajectoryDotSizeLabel,
                R.string.mouse_trajectory_dot_size_format, OverlayState.getMouseTrajectoryDotSize(this), SIZE_MIN);
        syncBoundedSeekBar(customSizeSeekBar, customSizeLabel,
                R.string.custom_size_format, OverlayState.getCustomSize(this), SIZE_MIN);
        syncBoundedSeekBar(customSpacingSeekBar, customSpacingLabel,
                R.string.custom_spacing_format, OverlayState.getCustomSpacing(this), 0);
        syncBoundedSeekBar(live2dSizeSeekBar, live2dSizeLabel,
                R.string.live2d_size_format, OverlayState.getLive2DSize(this), LIVE2D_SIZE_MIN);

        syncBoundedSeekBar(gamepadLeftStickSizeSeekBar, gamepadLeftStickSizeLabel,
                R.string.gamepad_left_stick_size_format,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_LEFT_STICK), SIZE_MIN);
        syncBoundedSeekBar(gamepadRightStickSizeSeekBar, gamepadRightStickSizeLabel,
                R.string.gamepad_right_stick_size_format,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_RIGHT_STICK), SIZE_MIN);
        syncBoundedSeekBar(gamepadLeftStickDotSizeSeekBar, gamepadLeftStickDotSizeLabel,
                R.string.gamepad_left_stick_dot_size_format,
                OverlayState.getGamepadStickDotSize(this, GamepadOverlayView.DISPLAY_LEFT_STICK), SIZE_MIN);
        syncBoundedSeekBar(gamepadRightStickDotSizeSeekBar, gamepadRightStickDotSizeLabel,
                R.string.gamepad_right_stick_dot_size_format,
                OverlayState.getGamepadStickDotSize(this, GamepadOverlayView.DISPLAY_RIGHT_STICK), SIZE_MIN);
        syncBoundedSeekBar(gamepadLeftStickCenterCornerSeekBar, gamepadLeftStickCenterCornerLabel,
                R.string.gamepad_stick_center_corner_strength_format,
                OverlayState.getGamepadStickCenterCornerStrength(this, GamepadOverlayView.DISPLAY_LEFT_STICK), 0);
        syncBoundedSeekBar(gamepadRightStickCenterCornerSeekBar, gamepadRightStickCenterCornerLabel,
                R.string.gamepad_stick_center_corner_strength_format,
                OverlayState.getGamepadStickCenterCornerStrength(this, GamepadOverlayView.DISPLAY_RIGHT_STICK), 0);
        syncBoundedSeekBar(gamepadFaceSizeSeekBar, gamepadFaceSizeLabel,
                R.string.gamepad_face_size_format,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_FACE), SIZE_MIN);
        syncBoundedSeekBar(gamepadDpadSizeSeekBar, gamepadDpadSizeLabel,
                R.string.gamepad_dpad_size_format,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_DPAD), SIZE_MIN);
        syncBoundedSeekBar(gamepadFaceSpacingSeekBar, gamepadFaceSpacingLabel,
                R.string.gamepad_face_spacing_format, OverlayState.getGamepadFaceSpacing(this), 0);
        syncBoundedSeekBar(gamepadLeftShoulderSizeSeekBar, gamepadLeftShoulderSizeLabel,
                R.string.gamepad_left_shoulder_size_format,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_LEFT_SHOULDER), SIZE_MIN);
        syncBoundedSeekBar(gamepadRightShoulderSizeSeekBar, gamepadRightShoulderSizeLabel,
                R.string.gamepad_right_shoulder_size_format,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_RIGHT_SHOULDER), SIZE_MIN);
        syncBoundedSeekBar(gamepadBackSizeSeekBar, gamepadBackSizeLabel,
                R.string.gamepad_back_size_format,
                OverlayState.getGamepadDisplaySize(this, GamepadOverlayView.DISPLAY_BACK), SIZE_MIN);

        syncBoundedSeekBar(dpsSizeSeekBar, dpsSizeLabel,
                R.string.dps_size_format, OverlayState.getDpsSize(this), SIZE_MIN);
        if (dpsTextColorDot != null) {
            updateColorDotSequence(dpsTextColorDot, OverlayState.getDpsTextColors(this));
        }

        int columns = OverlayState.getCustomColumns(this);
        if (columnsSeekBar != null) columnsSeekBar.setProgress(Math.max(0, columns - 1));
        if (columnsLabel != null) columnsLabel.setText(getString(R.string.columns_format, columns));

        int mouseSensitivity = SensitivitySettingsStore.getMousePercent(this);
        if (mouseSensitivitySeekBar != null) {
            mouseSensitivitySeekBar.setProgress(sensitivityToProgress(mouseSensitivity));
        }
        if (mouseSensitivityLabel != null) {
            mouseSensitivityLabel.setText(getString(R.string.mouse_sensitivity_format, mouseSensitivity));
        }
        int gamepadSensitivity = SensitivitySettingsStore.getGamepadPercent(this);
        if (gamepadSensitivitySeekBar != null) {
            gamepadSensitivitySeekBar.setProgress(sensitivityToProgress(gamepadSensitivity));
        }
        if (gamepadSensitivityLabel != null) {
            gamepadSensitivityLabel.setText(getString(R.string.gamepad_sensitivity_format, gamepadSensitivity));
        }

        for (int i = 0; i < opacityControls.size(); i++) {
            int displayType = opacityControls.keyAt(i);
            OpacityControl control = opacityControls.valueAt(i);
            if (control == null) continue;
            int value = OverlayState.getDisplayOpacity(this, displayType);
            control.seekBar.setProgress(value);
            control.label.setText(getString(R.string.display_opacity_format, value));
        }

        for (int i = 0; i < keyLayerOpacityControls.size(); i++) {
            int displayType = keyLayerOpacityControls.keyAt(i);
            KeyLayerOpacityControl control = keyLayerOpacityControls.valueAt(i);
            if (control == null) continue;
            syncOpacitySeekBar(control.backgroundSeek, control.backgroundLabel,
                    R.string.key_background_opacity_format,
                    OverlayState.getKeyBackgroundOpacity(this, displayType));
            syncOpacitySeekBar(control.strokeSeek, control.strokeLabel,
                    R.string.key_stroke_opacity_format,
                    OverlayState.getKeyStrokeOpacity(this, displayType));
            syncOpacitySeekBar(control.textSeek, control.textLabel,
                    R.string.key_text_opacity_format,
                    OverlayState.getKeyTextOpacity(this, displayType));
            syncOpacitySeekBar(control.diffusionSeek, control.diffusionLabel,
                    control.diffusionAsCenterOpacity
                            ? R.string.gamepad_stick_center_opacity_format
                            : R.string.key_diffusion_opacity_format,
                    OverlayState.getKeyDiffusionOpacity(this, displayType));
        }

        for (int i = 0; i < keyCornerStrengthControls.size(); i++) {
            int displayType = keyCornerStrengthControls.keyAt(i);
            CornerStrengthControl control = keyCornerStrengthControls.valueAt(i);
            if (control == null) continue;
            int strength = OverlayState.getKeyCornerStrength(this, displayType);
            control.seekBar.setProgress(strength);
            control.label.setText(getString(R.string.key_corner_strength_format, strength));
        }
    }

    private void syncBoundedSeekBar(SeekBar seekBar, TextView label, int formatRes,
                                    int value, int progressOffset) {
        if (seekBar != null) {
            seekBar.setProgress(Math.max(0, value - progressOffset));
        }
        if (label != null) label.setText(getString(formatRes, value));
    }

    private void syncOpacitySeekBar(SeekBar seekBar, TextView label, int formatRes, int value) {
        if (seekBar != null) seekBar.setProgress(value);
        if (label != null) label.setText(getString(formatRes, value));
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
            // Some ROMs persist the class as package/.ShortClass while ComponentName#flattenToString
            // returns the expanded form. Normalize both before deciding the service is disabled.
            ComponentName parsed = ComponentName.unflattenFromString(service);
            if (parsed != null && component.equals(parsed)) return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
