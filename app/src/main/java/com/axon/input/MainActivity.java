package com.axon.input;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Outline;
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
import android.view.ViewOutlineProvider;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private static final int CLICK_MULTIPLIER_DELAY_STEP_MS = 5;
    // 隐藏实验性输入功能入口；底层实现和现有配置保留，改为 true 可重新显示。
    private static final boolean SHOW_CUSTOM_MAPPING_UI = false;
    private static final boolean SHOW_CLICK_MULTIPLIER_UI = false;
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
    private GlobalSettingsSearchBar globalSearchBar;
    private final List<GlobalSearchTarget> globalSearchIndex = new ArrayList<>();
    private boolean globalSearchIndexReady;
    private ValueAnimator searchHighlightAnimator;
    private View searchHighlightView;
    private Drawable searchHighlightDrawable;
    private int selectedSectionPage;
    private int displayedSectionPage;
    private int sectionPageTransitionToken;
    private float pageSwipeDownX;
    private float pageSwipeDownY;
    private boolean pageSwipeBlocked;
    private boolean pageSwipeClaimed;
    private boolean searchDismissGestureConsumed;

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
    private Switch keyboardMouseSpaceSwapSwitch;
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
    private Spinner touchDisplayModeSpinner;
    private Button touchDisplayGamepadAdjustButton;
    private Button touchDisplayEditRegionsButton;
    private Button touchDisplayRetryButton;
    private TextView touchDisplayStatusText;
    private TextView touchDisplayHintText;
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
    private Switch clickMultiplierSwitch;
    private LinearLayout clickMultiplierDetails;
    private TextView clickMultiplierStatusText;
    private TextView clickMultiplierValueLabel;
    private SeekBar clickMultiplierValueSeekBar;
    private TextView clickMultiplierDelayLabel;
    private SeekBar clickMultiplierDelaySeekBar;
    private Button clickMultiplierConfigButton;
    private int clickMultiplierCaptureStep;
    private int clickMultiplierTogglePending = -1;
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
    private final Map<String, Button> featureShortcutButtons = new LinkedHashMap<>();
    private String featureShortcutCaptureId = "";
    private Button featureShortcutCaptureButton;
    private boolean featureShortcutPointerGestureConsumed;
    private final Runnable featureShortcutCaptureTimeout = () -> cancelFeatureShortcutCapture(true);

    public static boolean isAppForeground() {
        MainActivity activity = activeBindingActivity;
        return activity != null && activity.activityResumed && !activity.isFinishing();
    }

    /** AccessibilityService 使用功能快捷键切换状态后，仅同步对应主开关，不重新触发监听器。 */
    static void notifyFeatureShortcutStateChanged(String featureId, boolean enabled) {
        MainActivity activity = activeBindingActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.mainHandler.post(() -> activity.applyFeatureShortcutSwitchState(featureId, enabled));
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
    private final SparseArray<String> sliderTitleCache = new SparseArray<>();
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
    private EntryPasswordGate entryPasswordGate;
    private EntryBrandRevealView entryBrandRevealView;
    private View entryContentView;
    private boolean freshActivityLaunch;
    private boolean entryBrandRevealShown;
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
            if (displaySwitch != null && displaySwitch.isChecked()
                    && TouchDisplayStore.isTouchMode(MainActivity.this) && !isFinishing()) {
                mainHandler.postDelayed(this, 500L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 自由拖动属于临时编辑模式。重新进入设置页时先关闭，确保即使上一次把悬浮贴图
        // 放得过大，Activity 也一定能重新获得触摸并让用户调整尺寸。
        OverlayState.setDragEnabled(this, false);

        // 只在 Android 确认任务被移除时清理运行配置。
        // 重新创建或打开 Activity 不清理配置。

        setTheme(OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK
                ? R.style.AppThemeBlack : R.style.AppThemeLight);
        super.onCreate(savedInstanceState);
        freshActivityLaunch = savedInstanceState == null;
        applySystemBars();
        internalChange = true;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UiPalette.background(this));
        // 新 Activity 首次进入时先隐藏主内容，等待访问验证/字体准备完成后，
        // 再与 Axon Input 品牌遍历共用同一时间轴淡入。只动画这一层父容器，
        // 避免给大量子 View 分别创建 Animator。Activity recreate 不重复播放。
        entryContentView = page;
        page.setAlpha(freshActivityLaunch ? 0f : 1f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        // 页面横向留白保持总量不变，但左右对称，避免整列功能卡视觉中心偏左。
        root.setPadding(dp(24), dp(8), dp(24), dp(28));
        root.setBackgroundColor(UiPalette.background(this));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(54));
        header.setPadding(dp(18), dp(3), dp(18), dp(3));
        TextView title = createTitle();
        title.setText(R.string.app_name);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        globalSearchBar = new GlobalSettingsSearchBar(this, new GlobalSettingsSearchBar.Provider() {
            @Override public List<GlobalSettingsSearchBar.Item> search(String query) {
                return searchGlobalSettings(query);
            }

            @Override public void onItemSelected(GlobalSettingsSearchBar.Item item) {
                navigateToGlobalSearchItem(item);
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        searchParams.leftMargin = dp(8);
        header.addView(globalSearchBar, searchParams);
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView appearanceSection = createSectionLabel();
        appearanceSection.setText(R.string.section_home);
        root.addView(appearanceSection, contentParams(dp(8)));

        // 第一分页的数据位置和功能顺序完全不动，只把左上角分页名与底部导航统一为“主页”。
        // 新主页信息区仍然插在原功能层之前，不重排任何既有功能。
        root.addView(createHomeOverviewBlock(), contentParams(dp(14)));

        themeSpinner = createChoiceSpinner(new String[]{
                getString(R.string.theme_light), getString(R.string.theme_black)});
        themeSpinner.setSelection(OverlayState.getUiTheme(this) == OverlayState.UI_THEME_BLACK ? 1 : 0, false);
        root.addView(createChoiceGroup(R.string.theme_label, themeSpinner), contentParams(dp(14)));

        TextView displaySection = createSectionLabel();
        displaySection.setText(R.string.section_keyboard_mouse);
        root.addView(displaySection, contentParams(dp(8)));

        displaySwitch = createSwitch(R.string.switch_label);
        keyboardDetails = createDetailsContainer();

        // “常规按显”只有一个外观/位置配置源；模式只决定输入来自键鼠、手柄还是触屏。
        touchDisplayModeSpinner = createChoiceSpinner(new String[]{
                getString(R.string.regular_display_mode_keyboard_mouse),
                getString(R.string.touch_display_mode_gamepad),
                getString(R.string.touch_display_mode_touch)
        });
        keyboardDetails.addView(createInlineChoiceRow(
                R.string.touch_display_mode_label, touchDisplayModeSpinner), supportingParams(0));

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
        keyboardMouseSpaceSwapSwitch = createSwitch(R.string.keyboard_mouse_space_swap_switch);
        keyboardMouseSpaceSwapSwitch.setTextSize(14f);
        keyboardDetails.addView(keyboardMouseSpaceSwapSwitch, switchParams(dp(2)));
        keyboardMouseCpsSwitch = createSwitch(R.string.keyboard_mouse_cps_display_switch);
        keyboardMouseCpsSwitch.setTextSize(14f);
        keyboardDetails.addView(keyboardMouseCpsSwitch, switchParams(dp(2)));

        touchDisplayGamepadAdjustButton = new Button(this);
        touchDisplayGamepadAdjustButton.setText(R.string.touch_display_gamepad_adjust);
        touchDisplayGamepadAdjustButton.setAllCaps(false);
        touchDisplayGamepadAdjustButton.setTextSize(13f);
        touchDisplayGamepadAdjustButton.setMinHeight(dp(44));
        touchDisplayGamepadAdjustButton.setMinimumHeight(dp(44));
        styleActionButton(touchDisplayGamepadAdjustButton);
        keyboardDetails.addView(touchDisplayGamepadAdjustButton, supportingParams(dp(4)));

        touchDisplayEditRegionsButton = new Button(this);
        touchDisplayEditRegionsButton.setText(R.string.touch_display_edit_regions);
        touchDisplayEditRegionsButton.setAllCaps(false);
        touchDisplayEditRegionsButton.setTextSize(13f);
        touchDisplayEditRegionsButton.setMinHeight(dp(44));
        touchDisplayEditRegionsButton.setMinimumHeight(dp(44));
        styleActionButton(touchDisplayEditRegionsButton);
        keyboardDetails.addView(touchDisplayEditRegionsButton, supportingParams(dp(4)));

        touchDisplayRetryButton = new Button(this);
        touchDisplayRetryButton.setText(R.string.touch_display_retry);
        touchDisplayRetryButton.setAllCaps(false);
        touchDisplayRetryButton.setTextSize(13f);
        touchDisplayRetryButton.setMinHeight(dp(44));
        touchDisplayRetryButton.setMinimumHeight(dp(44));
        styleActionButton(touchDisplayRetryButton);
        keyboardDetails.addView(touchDisplayRetryButton, supportingParams(dp(4)));

        touchDisplayStatusText = createSupportingText();
        keyboardDetails.addView(touchDisplayStatusText, supportingParams(dp(2)));
        touchDisplayHintText = createSupportingText();
        keyboardDetails.addView(touchDisplayHintText, supportingParams(dp(2)));

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
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView keyboardCatExpressionHotkeySelectionLabel = createLabel();
        keyboardCatExpressionHotkeySelectionLabel.setText(R.string.keyboard_cat_expression_hotkey_selection_label);
        keyboardCatDetails.addView(keyboardCatExpressionHotkeySelectionLabel, supportingParams(dp(6)));
        keyboardCatExpressionHotkeySelectionButton = createConfigButton(
                R.string.keyboard_cat_expression_hotkey_selection_empty,
                v -> showKeyboardCatExpressionHotkeySelectionDialog());
        keyboardCatDetails.addView(keyboardCatExpressionHotkeySelectionButton,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView keyboardCatModelFunctionsLabel = createLabel();
        keyboardCatModelFunctionsLabel.setText(R.string.keyboard_cat_model_functions_label);
        keyboardCatDetails.addView(keyboardCatModelFunctionsLabel, supportingParams(dp(6)));
        keyboardCatModelFunctionsButton = createConfigButton(
                R.string.keyboard_cat_model_functions_button, v -> showKeyboardCatModelFunctionsDialog());
        keyboardCatDetails.addView(keyboardCatModelFunctionsButton,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        ((LagSeekBar) columnsSeekBar).setValueDisplaySpec(1, 1, "");
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
        addKeyAppearanceControls(gamepadLeftStickDetails, GamepadOverlayView.DISPLAY_LEFT_STICK);
        gamepadLeftStickCenterCornerLabel = createLabel();
        gamepadLeftStickDetails.addView(gamepadLeftStickCenterCornerLabel, supportingParams(dp(2)));
        gamepadLeftStickCenterCornerSeekBar = createCornerStrengthSeekBar();
        gamepadLeftStickDetails.addView(gamepadLeftStickCenterCornerSeekBar, seekBarLayoutParams(dp(4)));
        gamepadLeftStickCenterColorDot = addStickCenterColorControl(gamepadLeftStickDetails, GamepadOverlayView.DISPLAY_LEFT_STICK);
        addKeyLayerOpacityControls(gamepadLeftStickDetails, GamepadOverlayView.DISPLAY_LEFT_STICK);
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
        addKeyAppearanceControls(gamepadRightStickDetails, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        gamepadRightStickCenterCornerLabel = createLabel();
        gamepadRightStickDetails.addView(gamepadRightStickCenterCornerLabel, supportingParams(dp(2)));
        gamepadRightStickCenterCornerSeekBar = createCornerStrengthSeekBar();
        gamepadRightStickDetails.addView(gamepadRightStickCenterCornerSeekBar, seekBarLayoutParams(dp(4)));
        gamepadRightStickCenterColorDot = addStickCenterColorControl(gamepadRightStickDetails, GamepadOverlayView.DISPLAY_RIGHT_STICK);
        addKeyLayerOpacityControls(gamepadRightStickDetails, GamepadOverlayView.DISPLAY_RIGHT_STICK);
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
            if (mouseSensitivitySeekBar instanceof LagSeekBar) ((LagSeekBar) mouseSensitivitySeekBar).setDisplayValue(100, "%");
            if (gamepadSensitivitySeekBar instanceof LagSeekBar) ((LagSeekBar) gamepadSensitivitySeekBar).setDisplayValue(100, "%");
            setSliderLabelTitle(mouseSensitivityLabel, R.string.mouse_sensitivity_format);
            setSliderLabelTitle(gamepadSensitivityLabel, R.string.gamepad_sensitivity_format);
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
        ((LagSeekBar) customMappingDelaySeekBar).setValueDisplaySpec(0, CUSTOM_MAPPING_DELAY_STEP_MS, "");
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

        clickMultiplierSwitch = createSwitch(R.string.click_multiplier_switch_label);
        clickMultiplierDetails = createDetailsContainer();
        clickMultiplierStatusText = createSupportingText();
        clickMultiplierDetails.addView(clickMultiplierStatusText, supportingParams(dp(2)));
        clickMultiplierConfigButton = createConfigButton(
                R.string.click_multiplier_config, v -> startClickMultiplierCapture());
        clickMultiplierDetails.addView(clickMultiplierConfigButton, supportingParams(dp(4)));

        clickMultiplierValueLabel = createLabel();
        clickMultiplierDetails.addView(clickMultiplierValueLabel, supportingParams(dp(4)));
        clickMultiplierValueSeekBar = new LagSeekBar(this);
        clickMultiplierValueSeekBar.setMax(ClickMultiplierStore.MULTIPLIER_MAX - ClickMultiplierStore.MULTIPLIER_MIN);
        clickMultiplierValueSeekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(clickMultiplierValueSeekBar);
        ((LagSeekBar) clickMultiplierValueSeekBar).setValueDisplaySpec(ClickMultiplierStore.MULTIPLIER_MIN, 1, "");
        clickMultiplierDetails.addView(clickMultiplierValueSeekBar, seekBarLayoutParams(dp(4)));

        clickMultiplierDelayLabel = createLabel();
        clickMultiplierDetails.addView(clickMultiplierDelayLabel, supportingParams(dp(4)));
        clickMultiplierDelaySeekBar = new LagSeekBar(this);
        clickMultiplierDelaySeekBar.setMax(ClickMultiplierStore.DELAY_MAX_MS / CLICK_MULTIPLIER_DELAY_STEP_MS);
        clickMultiplierDelaySeekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(clickMultiplierDelaySeekBar);
        ((LagSeekBar) clickMultiplierDelaySeekBar).setValueDisplaySpec(0, CLICK_MULTIPLIER_DELAY_STEP_MS, "");
        clickMultiplierDetails.addView(clickMultiplierDelaySeekBar, seekBarLayoutParams(dp(4)));

        TextView clickMultiplierHint = createSupportingText();
        clickMultiplierHint.setText(R.string.click_multiplier_hint);
        clickMultiplierDetails.addView(clickMultiplierHint, supportingParams(0));
        View clickMultiplierGroup = createFeatureGroup(clickMultiplierSwitch, clickMultiplierDetails);
        clickMultiplierGroup.setVisibility(SHOW_CLICK_MULTIPLIER_UI ? View.VISIBLE : View.GONE);
        root.addView(clickMultiplierGroup, contentParams(dp(10)));

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
        ((LagSeekBar) live2dSizeSeekBar).setValueDisplaySpec(LIVE2D_SIZE_MIN, 1, "%");
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
        ((LagSeekBar) live2dPhysicsStrengthSeekBar).setValueDisplaySpec(0, 1, "%");
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

        // 先按源码中的真实 section 顺序拆页，再只调整分页/底栏展示顺序。
        // splitContentIntoSectionPages 要求 sectionStarts 单调递增，不能直接传重排后的起点。
        View[] sourceSectionOrder = new View[]{appearanceSection, displaySection, superCustomSection,
                gamepadSection, sensitivitySection, behaviorSection, configurationSection};
        ScrollView[] sourcePages = splitContentIntoSectionPages(root, sourceSectionOrder);
        sectionPageViews = new ScrollView[]{sourcePages[0], sourcePages[1], sourcePages[3],
                sourcePages[2], sourcePages[4], sourcePages[5], sourcePages[6]};
        sectionPagerHost = createSectionPagerHost(sectionPageViews);

        SectionNavigationBar sectionNav = createSectionNavigation(
                new int[]{R.string.section_home, R.string.section_keyboard_mouse,
                        R.string.section_gamepad, R.string.section_super_custom,
                        R.string.section_sensitivity, R.string.section_behavior,
                        R.string.section_configuration},
                new int[]{0, 1, 3, 2, 4, 5, 6},
                sectionPageViews.length);
        sectionNavigationView = sectionNav;
        selectSectionPage(0, false);

        // Content owns the visual hierarchy; primary navigation stays reachable at the bottom.
        page.addView(sectionPagerHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View navDivider = new View(this);
        navDivider.setBackgroundColor(UiPalette.divider(this));
        page.addView(navDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        // 导航高度由图标 + 当前字体 metrics 决定。固定 68dp 在 Heavy 字体或系统字体缩放后会裁掉标签。
        sectionNav.setMinimumHeight(dp(68));
        page.addView(sectionNav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activityRoot = new FrameLayout(this);
        activityRoot.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(activityRoot);
        // 功能卡全部创建完成后再做一次确定性的补齐扫描。正常情况下 createFeatureGroup
        // 已经插入；如果某个公共组件/构建顺序让首次插入错过，这里仍保证详情第 0 项存在。
        ensureAllFeatureShortcutRows();
        if (AppTypeface.isAppFontSelected()) {
            AppTypeface.applyToViewTree(activityRoot);
        }
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

        bindFeatureSwitch(displaySwitch, keyboardDetails, enabled -> {
            OverlayState.setEnabled(MainActivity.this, enabled);
            mainHandler.removeCallbacks(touchDisplayStatusTicker);
            if (enabled && TouchDisplayStore.isTouchMode(MainActivity.this)) {
                ShizukuBridge.addListener(MainActivity.this);
                ensureAccessibility();
                ensureShizukuForTouchDisplay();
                mainHandler.post(touchDisplayStatusTicker);
            } else if (enabled && TouchDisplayStore.isGamepadMode(MainActivity.this)) {
                AxonInputAccessibilityService.hideTouchRegionEditorOverlay();
                ensureAccessibility();
                if (TouchDisplayStore.isGamepadConfigComplete(MainActivity.this)) {
                    ensurePrivilegedInputForGamepadMapping();
                }
            } else {
                AxonInputAccessibilityService.hideTouchRegionEditorOverlay();
            }
            syncTouchDisplayModeUi();
            refreshHideDisplayHotkeyUi();
        });

        spaceDisplaySwitch.setOnCheckedChangeListener((button, enabled) -> {
            setControlEnabled(spaceDpsSwitch, enabled);
            setControlEnabled(spaceDashSwitch, enabled);
            syncKeyboardMouseSpaceSwapAvailability();
            if (!internalChange) OverlayState.setKeyboardSpaceEnabled(this, enabled);
        });
        bindSimpleSwitch(spaceDpsSwitch,
                enabled -> OverlayState.setKeyboardSpaceDpsEnabled(this, enabled));
        bindSimpleSwitch(spaceDashSwitch,
                enabled -> OverlayState.setKeyboardSpaceDashEnabled(this, enabled));
        keyboardMouseButtonsSwitch.setOnCheckedChangeListener((button, enabled) -> {
            setControlEnabled(keyboardMouseCpsSwitch, enabled);
            syncKeyboardMouseSpaceSwapAvailability();
            if (!internalChange) OverlayState.setKeyboardMouseButtonsEnabled(this, enabled);
        });
        bindSimpleSwitch(keyboardMouseCpsSwitch,
                enabled -> OverlayState.setKeyboardMouseCpsEnabled(this, enabled));
        bindSimpleSwitch(keyboardMouseSpaceSwapSwitch,
                enabled -> OverlayState.setKeyboardMouseSpaceSwapEnabled(this, enabled));

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
                setSliderLabelTitle(columnsLabel, R.string.columns_format);
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
                setSliderLabelTitle(customMappingDelayLabel, R.string.custom_mapping_delay_format);
                if (fromUser && !internalChange) CustomMappingStore.setDelayMs(MainActivity.this, delay);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        clickMultiplierSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (internalChange) return;
            if (!enabled) {
                ClickMultiplierStore.setEnabled(MainActivity.this, false);
                cancelClickMultiplierCapture();
                updateClickMultiplierUi();
                handleDisplayModeChanged();
                return;
            }
            if (!ClickMultiplierStore.hasBinding(MainActivity.this)) {
                startClickMultiplierCapture();
                return;
            }
            ClickMultiplierStore.setEnabled(MainActivity.this, true);
            updateClickMultiplierUi();
            ensureAccessibility();
            ensurePrivilegedInputForGamepadMapping();
            handleDisplayModeChanged();
        });

        clickMultiplierValueSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = ClickMultiplierStore.MULTIPLIER_MIN + progress;
                setSliderLabelTitle(clickMultiplierValueLabel, R.string.click_multiplier_value_format);
                if (fromUser && !internalChange) ClickMultiplierStore.setMultiplier(MainActivity.this, value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        clickMultiplierDelaySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int delay = progress * CLICK_MULTIPLIER_DELAY_STEP_MS;
                setSliderLabelTitle(clickMultiplierDelayLabel, R.string.click_multiplier_delay_format);
                if (fromUser && !internalChange) ClickMultiplierStore.setDelayMs(MainActivity.this, delay);
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
            if (enabled && featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
            if (enabled && (customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0
                    || simultaneousClickCaptureStep != 0 || gamepadMappingCaptureArmed || gamepadCustomSwapCaptureStep != 0)) {
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

        touchDisplayModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (internalChange) return;
                int mode = position == 1 ? TouchDisplayStore.MODE_GAMEPAD
                        : position == 2 ? TouchDisplayStore.MODE_TOUCH
                        : TouchDisplayStore.MODE_KEYBOARD_MOUSE;
                TouchDisplayStore.setMode(MainActivity.this, mode);
                mainHandler.removeCallbacks(touchDisplayStatusTicker);
                if (mode == TouchDisplayStore.MODE_TOUCH && displaySwitch.isChecked()) {
                    ShizukuBridge.addListener(MainActivity.this);
                    ensureAccessibility();
                    ensureShizukuForTouchDisplay();
                    mainHandler.post(touchDisplayStatusTicker);
                } else {
                    AxonInputAccessibilityService.hideTouchRegionEditorOverlay();
                    if (mode == TouchDisplayStore.MODE_GAMEPAD && displaySwitch.isChecked()) {
                        ensureAccessibility();
                        if (TouchDisplayStore.isGamepadConfigComplete(MainActivity.this)) {
                            ensurePrivilegedInputForGamepadMapping();
                        }
                    }
                }
                syncTouchDisplayModeUi();
                invalidateGlobalSearchIndex();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        touchDisplayGamepadAdjustButton.setOnClickListener(v -> showTouchDisplayGamepadAdjustDialog());
        touchDisplayEditRegionsButton.setOnClickListener(v -> {
            if (!TouchDisplayStore.isTouchMode(MainActivity.this)) return;
            // 框选属于配置动作，不依赖“常规按显”主开关是否已经开启。先确保无障碍服务，
            // 再请求编辑 Overlay；Shizuku 仅负责给编辑器提供真实触点预览，不阻塞框本身显示。
            ensureAccessibility();
            ensureShizukuForTouchDisplay();
            AxonInputAccessibilityService.showTouchRegionEditorOverlay();
            if (AxonInputAccessibilityService.isServiceConnected()) {
                Toast.makeText(MainActivity.this, "已开启悬浮框选，可直接调整四个触屏区域", Toast.LENGTH_SHORT).show();
                moveTaskToBack(true);
            }
        });
        touchDisplayRetryButton.setOnClickListener(v -> {
            if (!TouchDisplayStore.isTouchMode(MainActivity.this)) return;
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
                setSliderLabelTitle(live2dPhysicsStrengthLabel, R.string.live2d_physics_strength_format);
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
            if (enabled && featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
            if (enabled && (customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0
                    || simultaneousClickCaptureStep != 0 || forceHoldCaptureStep != 0 || gamepadMappingCaptureArmed)) {
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
    public void onBackPressed() {
        // 访问验证是独立入口页，未通过验证时不允许通过 Back 绕过。
        if (entryPasswordGate != null) return;
        // 搜索展开时 Back 只关闭搜索，第二次 Back 才退出页面。
        if (globalSearchBar != null && globalSearchBar.collapseFromBack()) return;
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return false;
        // 验证页只把实体键交给当前输入框，不触发设置页的录入/映射入口。全局 evdev 监听不受影响。
        if (entryPasswordGate != null) return super.dispatchKeyEvent(event);

        boolean shortcutCapture = featureShortcutCaptureActive();
        boolean firstDown = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        if (isPhysicalGamepadEvent(event)
                || ((gamepadMappingCaptureArmed || customMappingCaptureStep != 0
                || clickMultiplierCaptureStep != 0)
                && InputBinding.isGamepadEventForMapping(event))) {
            updateBindableGamepadKeyEvent(event);
            // 录入期间不让触发键/目标键继续操作设置页。
            if (shortcutCapture || customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0) return true;
            return super.dispatchKeyEvent(event);
        }

        if (isPhysicalKeyboardEvent(event)) {
            if (firstDown) {
                int inputCode = InputBinding.keyboard(event.getKeyCode());
                handleBindableInputPressed(inputCode, InputBinding.evdevCode(inputCode, event.getScanCode()), true);
            }
            // 包括 KEYCODE_BACK 在内都只作为录入内容，不触发 Activity 行为。
            if (shortcutCapture || customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (entryPasswordGate != null) return super.dispatchGenericMotionEvent(event);
        boolean shortcutCapture = featureShortcutCaptureActive();
        captureBindableMousePress(event);
        updateBindableGamepadMotionEvent(event);
        if (shortcutCapture || customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0) return true;
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

        // Android 可能把手柄高频 axis 事件批处理到一个 MotionEvent。先按时间顺序消费 historical
        // 样本，再消费当前样本，避免 L2/R2 / D-pad 在一批里完成按下+松开后完全漏录。
        for (int i = 0; i < event.getHistorySize(); i++) {
            applyBindableGamepadMotionButtons(InputBinding.gamepadButtonsFromMotionEvent(event, i));
        }
        applyBindableGamepadMotionButtons(InputBinding.gamepadButtonsFromMotionEvent(event));
    }

    private void applyBindableGamepadMotionButtons(int motionButtons) {
        int before = bindableGamepadButtonsDown();
        bindableGamepadMotionButtonsDown = motionButtons;
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

    static boolean isClickMultiplierCaptureActive() {
        MainActivity activity = activeBindingActivity;
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.clickMultiplierCaptureStep != 0;
    }

    static void notifyClickMultiplierActiveChanged(boolean active) {
        MainActivity activity = activeBindingActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.mainHandler.post(activity::updateClickMultiplierUi);
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
                || activity.clickMultiplierCaptureStep != 0
                || activity.simultaneousClickCaptureStep != 0
                || activity.forceHoldCaptureStep != 0
                || activity.gamepadCustomSwapCaptureStep != 0
                || activity.featureShortcutCaptureActive());
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

        if (featureShortcutCaptureActive()) {
            if (hasOtherBindingCapture()) {
                cancelFeatureShortcutCapture(false);
            } else {
                captureFeatureShortcut(inputCode);
                return;
            }
        }

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

        if (clickMultiplierCaptureStep != 0) {
            if (clickMultiplierCaptureStep == 1) {
                if (clickMultiplierToggleConflicts(inputCode)) {
                    Toast.makeText(this, R.string.click_multiplier_conflict, Toast.LENGTH_SHORT).show();
                    return;
                }
                clickMultiplierTogglePending = inputCode;
                clickMultiplierCaptureStep = 2;
                updateClickMultiplierUi();
                return;
            }
            if (inputCode == clickMultiplierTogglePending) {
                Toast.makeText(this, R.string.click_multiplier_same_key, Toast.LENGTH_SHORT).show();
                return;
            }
            if (clickMultiplierTargetConflicts(inputCode)) {
                Toast.makeText(this, R.string.click_multiplier_target_conflict, Toast.LENGTH_SHORT).show();
                return;
            }
            int targetEvdev = evdevCode;
            if (targetEvdev <= 0 && InputBinding.isKeyboard(inputCode)) {
                targetEvdev = GamepadMappingStore.keyboardEvdevCode(inputCode);
            }
            if (targetEvdev <= 0 || targetEvdev > 0x2ff) {
                Toast.makeText(this, R.string.click_multiplier_target_unsupported, Toast.LENGTH_SHORT).show();
                return;
            }
            ClickMultiplierStore.setBinding(this, clickMultiplierTogglePending, inputCode, targetEvdev);
            ClickMultiplierStore.setEnabled(this, true);
            clickMultiplierCaptureStep = 0;
            clickMultiplierTogglePending = -1;
            updateClickMultiplierUi();
            AxonInputAccessibilityService.refreshActiveService();
            ensureAccessibility();
            ensurePrivilegedInputForGamepadMapping();
            handleDisplayModeChanged();
            Toast.makeText(this, R.string.click_multiplier_saved, Toast.LENGTH_SHORT).show();
            return;
        }

        if (gamepadMappingCaptureArmed) {
            if (!InputBinding.isGamepad(inputCode)) {
                Toast.makeText(this, R.string.gamepad_mapping_gamepad_only, Toast.LENGTH_SHORT).show();
                return;
            }
            if (FeatureShortcutStore.conflicts(this, null, inputCode)
                    || (OverlayState.isForceHoldEnabled(this)
                    && inputCode == OverlayState.getForceHoldTriggerKeyCode(this))
                    || inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)
                    || (OverlayState.isHideDisplayHotkeyEnabled(this)
                    && inputCode == OverlayState.getHideDisplayHotkeyInputCode(this))
                    || physicsHotkeyConflicts(inputCode, null, null)
                    || keyboardCatFunctionHotkeyConflicts(inputCode)
                    || FloatingMediaStore.hotkeyConflicts(this, null, inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || clickMultiplierUsesInput(inputCode)
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
            if (FeatureShortcutStore.conflicts(this, null, inputCode)
                    || (OverlayState.isForceHoldEnabled(this)
                    && inputCode == OverlayState.getForceHoldTriggerKeyCode(this))
                    || inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)
                    || inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)
                    || gamepadMappingUsesSource(inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || clickMultiplierUsesInput(inputCode)
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
            if (FeatureShortcutStore.conflicts(this, null, inputCode)
                    || (OverlayState.isForceHoldEnabled(this)
                    && inputCode == OverlayState.getForceHoldTriggerKeyCode(this))
                    || inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)
                    || inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)
                    || gamepadMappingUsesSource(inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || clickMultiplierUsesInput(inputCode)
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
            if (FeatureShortcutStore.conflicts(this, null, inputCode)
                    || (OverlayState.isForceHoldEnabled(this)
                    && inputCode == OverlayState.getForceHoldTriggerKeyCode(this))
                    || inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)
                    || gamepadMappingUsesSource(inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || clickMultiplierUsesInput(inputCode)
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
            } else if (inputCode == simultaneousClickSourcePending
                    && (InputBinding.isMouse(inputCode) || InputBinding.isGamepad(inputCode))) {
                // 鼠标同键会与系统按钮状态合并；手柄同键会形成原始设备与镜像 uinput 的重复语义。
                // 为避免重复按下/松开边沿和厂商手柄驱动差异，仅键盘允许同键同时点击。
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
                if (FeatureShortcutStore.conflicts(this, null, inputCode)
                        || gamepadMappingUsesSource(inputCode) || customMappingUsesTrigger(inputCode)
                        || clickMultiplierUsesInput(inputCode) || simultaneousClickUsesSource(inputCode)) {
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
            } else if (FeatureShortcutStore.conflicts(this, null, inputCode)
                    || inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)
                    || inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)
                    || gamepadMappingUsesSource(inputCode)
                    || customMappingUsesTrigger(inputCode)
                    || clickMultiplierUsesInput(inputCode)
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
                || clickMultiplierCaptureStep != 0
                || ClickMultiplierStore.isEnabled(this)
                || simultaneousClickCaptureStep != 0
                || SimultaneousClickStore.isEnabled(this)
                || FeatureShortcutStore.hasAnyBinding(this)
                || !GamepadMappingStore.load(this).isEmpty();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!RootBridge.isRootActive() || TouchDisplayStore.isTouchCaptureEnabled(this)) {
            ShizukuBridge.addListener(this);
        }
        // 全局权限策略：先探测/请求 Root；只有 Root 不可用时才回退 Shizuku。
        RootBridge.ensureActivated(this, (activity, rootActive) -> {
            MainActivity owner = (MainActivity) activity;
            owner.syncRootActivationUi(rootActive);
            if (rootActive) {
                owner.waitingForShizuku = false;
                owner.shizukuPermissionRequestInFlight = false;
                // Root normally owns privileged input, but touch display is intentionally
                // Shizuku-only and must keep receiving Shizuku lifecycle callbacks.
                if (!TouchDisplayStore.isTouchCaptureEnabled(owner)) ShizukuBridge.removeListener(owner);
            }
            if (owner.needsAccessibility()) owner.ensureAccessibility();
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
        clearSearchHighlight();
        if (globalSearchBar != null) globalSearchBar.dismiss();
        dismissEntryBrandReveal(false);
        if (entryContentView != null) {
            entryContentView.animate().cancel();
            entryContentView = null;
        }
        removeInAppLive2D();
        // Activity 销毁不清理运行配置，任务移除时由服务统一处理。
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (globalSearchBar != null) globalSearchBar.onHostPause();
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
        cancelClickMultiplierCapture();

        // Binding prompts are foreground-only.  Without this reset, leaving the app halfway through
        // recording (especially Force Hold step 2) makes a later unrelated key silently become the binding.
        simultaneousClickCaptureStep = 0;
        simultaneousClickSourcePending = -1;
        forceHoldCaptureStep = 0;
        forceHoldTargetKeyPending = -1;
        forceHoldTargetScanPending = -1;
        gamepadCustomSwapCaptureStep = 0;
        gamepadCustomSwapFirstPending = 0;
        cancelFeatureShortcutCapture(false);
        AxonInputAccessibilityService.refreshActiveService();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (globalSearchBar != null) globalSearchBar.onHostResume();
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
        keyboardMouseSpaceSwapSwitch.setChecked(OverlayState.isKeyboardMouseSpaceSwapEnabled(this));
        setControlEnabled(spaceDpsSwitch, spaceDisplaySwitch.isChecked());
        setControlEnabled(spaceDashSwitch, spaceDisplaySwitch.isChecked());
        setControlEnabled(keyboardMouseCpsSwitch, keyboardMouseButtonsSwitch.isChecked());
        syncKeyboardMouseSpaceSwapAvailability();
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
        sensitivitySwitch.setChecked(SensitivitySettingsStore.isEnabled(this));
        dpsSwitch.setChecked(OverlayState.isDpsEnabled(this));
        customMappingSwitch.setChecked(customMappingCaptureStep != 0 || CustomMappingStore.isEnabled(this));
        int customMappingDelay = CustomMappingStore.getDelayMs(this);
        customMappingDelaySeekBar.setProgress(customMappingDelay / CUSTOM_MAPPING_DELAY_STEP_MS);
        setSliderLabelTitle(customMappingDelayLabel, R.string.custom_mapping_delay_format);
        updateCustomMappingUi();
        int clickMultiplierValue = ClickMultiplierStore.getMultiplier(this);
        int clickMultiplierDelay = ClickMultiplierStore.getDelayMs(this);
        clickMultiplierSwitch.setChecked(clickMultiplierCaptureStep != 0 || ClickMultiplierStore.isEnabled(this));
        clickMultiplierValueSeekBar.setProgress(clickMultiplierValue - ClickMultiplierStore.MULTIPLIER_MIN);
        setSliderLabelTitle(clickMultiplierValueLabel, R.string.click_multiplier_value_format);
        clickMultiplierDelaySeekBar.setProgress(clickMultiplierDelay / CLICK_MULTIPLIER_DELAY_STEP_MS);
        setSliderLabelTitle(clickMultiplierDelayLabel, R.string.click_multiplier_delay_format);
        updateClickMultiplierUi();
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
        autoHideSwitch.setChecked(OverlayState.isAutoHideBackground(this));
        touchDisplayModeSpinner.setSelection(TouchDisplayStore.getMode(this), false);
        syncTouchDisplayModeUi();
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
        refreshAllFeatureShortcutButtons();
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
        if (displaySwitch.isChecked() && TouchDisplayStore.isTouchMode(this)) {
            ShizukuBridge.addListener(this);
            ensureShizukuForTouchDisplay();
            mainHandler.post(touchDisplayStatusTicker);
        }
        // Re-check the real service connection after returning from Shizuku/accessibility settings.
        // This is also the recovery entry for ROMs that persisted the secure setting but failed to
        // bind the AccessibilityService on the first attempt.
        if (needsAccessibility()) mainHandler.post(this::ensureAccessibility);
        syncInAppLive2D();
        // 页面动态文案/条件状态可能在后台发生变化；恢复前台后让下一次搜索重建轻量索引。
        invalidateGlobalSearchIndex();

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
            final int slot = pendingSuperCustomImportSlot;
            pendingSuperCustomImportSlot = 0;
            if (slot < 1 || slot > SuperCustomConfigStore.SLOT_COUNT) return;
            final Context app = getApplicationContext();
            new Thread(() -> {
                boolean success = false;
                try {
                    String json = readText(app, uri, SuperCustomConfigStore.MAX_CONFIG_BYTES);
                    SuperCustomConfigStore.importSlot(app, slot, json);
                    success = true;
                } catch (Throwable ignored) {
                }
                final boolean imported = success;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (imported) {
                        syncSuperCustomConfigRows();
                        Toast.makeText(MainActivity.this,
                                getString(R.string.super_custom_import_slot_success, slot), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, R.string.super_custom_config_import_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }, "AxonSuperCustomImport").start();
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
                        if (isFinishing() || isDestroyed()) return;
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
                        if (isFinishing() || isDestroyed()) return;
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
                        if (isFinishing() || isDestroyed()) return;
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
                        if (isFinishing() || isDestroyed()) return;
                        String detail = error.getMessage() == null ? "" : error.getMessage();
                        Toast.makeText(MainActivity.this,
                                getString(R.string.live2d_import_failed, detail), Toast.LENGTH_LONG).show();
                    });
                }
            }, "AxonLive2DImport").start();
            return;
        }

        if (requestCode == FONT_IMPORT_REQUEST) {
            final Context app = getApplicationContext();
            final Uri fontUri = uri;
            new Thread(() -> {
                boolean success = false;
                try {
                    FontManager.importFont(app, fontUri, queryDisplayName(app, fontUri, "font.ttf"));
                    success = true;
                } catch (Throwable ignored) {
                }
                final boolean imported = success;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    internalChange = true;
                    if (imported) {
                        syncFontChoices();
                        syncFontUi();
                        if (FontManager.isEnabled(MainActivity.this)) AxonInputAccessibilityService.refreshTheme();
                        Toast.makeText(MainActivity.this, R.string.font_import_success, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, R.string.font_import_failed, Toast.LENGTH_SHORT).show();
                    }
                    internalChange = false;
                });
            }, "AxonFontImport").start();
            return;
        }

        if (requestCode == HTML_REQUEST_GLOBAL) {
            final Context app = getApplicationContext();
            final Uri htmlUri = uri;
            new Thread(() -> {
                boolean success = false;
                try {
                    String html = readText(app, htmlUri, GlobalHtmlStore.MAX_BYTES);
                    String name = queryDisplayName(app, htmlUri, "display.html");
                    GlobalHtmlStore.save(app, name, html);
                    success = true;
                } catch (Throwable ignored) {
                }
                final boolean imported = success;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    internalChange = true;
                    if (imported) {
                        globalHtmlSwitch.setChecked(true);
                        syncGlobalHtmlChoices();
                        syncGlobalHtmlUi();
                        invalidateGlobalSearchIndex();
                        Toast.makeText(MainActivity.this, R.string.global_html_import_success, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, R.string.global_html_import_failed, Toast.LENGTH_SHORT).show();
                    }
                    internalChange = false;
                });
            }, "AxonGlobalHtmlImport").start();
            return;
        }

        if (requestCode == CONFIG_EXPORT_REQUEST) {
            final Context app = getApplicationContext();
            final Uri exportUri = uri;
            new Thread(() -> {
                boolean success = false;
                try (OutputStream out = app.getContentResolver().openOutputStream(exportUri, "wt")) {
                    if (out == null) throw new IOException("Cannot open export target");
                    out.write(ConfigManager.exportCurrent(app).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    success = true;
                } catch (Throwable ignored) {
                }
                final boolean exported = success;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(MainActivity.this, exported
                            ? R.string.config_export_success : R.string.config_export_failed, Toast.LENGTH_SHORT).show();
                });
            }, "AxonConfigExport").start();
            return;
        }

        if (requestCode == CONFIG_IMPORT_REQUEST) {
            final Context app = getApplicationContext();
            final Uri importUri = uri;
            new Thread(() -> {
                boolean success = false;
                try {
                    ConfigManager.importInto(app, readText(app, importUri, ConfigManager.MAX_CONFIG_BYTES));
                    success = true;
                } catch (Throwable ignored) {
                }
                final boolean imported = success;
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(MainActivity.this, imported
                            ? R.string.config_import_success : R.string.config_import_failed, Toast.LENGTH_SHORT).show();
                    if (imported) recreate();
                });
            }, "AxonConfigImport").start();
        }
    }

    @Override
    public void onShizukuReady(boolean permissionGranted) {
        boolean touchShizuku = TouchDisplayStore.isTouchCaptureEnabled(this);
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
        boolean touchShizuku = TouchDisplayStore.isTouchCaptureEnabled(this);
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
            RootBridge.ensureActivated(this, (activity, rootActive) -> {
                MainActivity owner = (MainActivity) activity;
                owner.syncRootActivationUi(rootActive);
                owner.ensureAccessibility();
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
        int featureShortcutInputMask = FeatureShortcutStore.inputMask(this);
        boolean featureShortcutNeedsPrivileged = (featureShortcutInputMask
                & (FeatureShortcutStore.INPUT_MOUSE | FeatureShortcutStore.INPUT_GAMEPAD)) != 0;
        // Root 是全局激活方式：只要已获得 uid 0，显示类无障碍自动启用也直接走 Root。
        boolean rootMode = RootBridge.isRootActive()
                || SensitivitySettingsStore.getMode(this) == SensitivitySettingsStore.MODE_ROOT;

        if (AxonInputAccessibilityService.isServiceConnected()) {
            accessibilityVerificationGeneration++;
            accessibilityVerificationInFlight = false;
            AxonInputAccessibilityService.refreshActiveService();
            if ((sensitivity || keyboardCatMouseCapture || superCustomMouseCapture
                    || gamepadMappingNeedsPrivileged || featureShortcutNeedsPrivileged) && !rootMode) {
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

    private void syncTouchDisplayModeUi() {
        if (touchDisplayModeSpinner == null) return;
        boolean keyboardMouseMode = TouchDisplayStore.isKeyboardMouseMode(this);
        boolean gamepadMode = TouchDisplayStore.isGamepadMode(this);
        boolean touchMode = TouchDisplayStore.isTouchMode(this);
        if (touchDisplayGamepadAdjustButton != null) {
            touchDisplayGamepadAdjustButton.setVisibility(gamepadMode ? View.VISIBLE : View.GONE);
            if (gamepadMode) {
                touchDisplayGamepadAdjustButton.setText(TouchDisplayStore.isGamepadConfigComplete(this)
                        ? R.string.touch_display_gamepad_adjust_complete
                        : R.string.touch_display_gamepad_adjust);
            }
        }
        if (touchDisplayEditRegionsButton != null) {
            touchDisplayEditRegionsButton.setVisibility(touchMode ? View.VISIBLE : View.GONE);
        }
        if (touchDisplayRetryButton != null) {
            touchDisplayRetryButton.setVisibility(touchMode ? View.VISIBLE : View.GONE);
        }
        if (touchDisplayStatusText != null) {
            touchDisplayStatusText.setVisibility(touchMode ? View.VISIBLE : View.GONE);
        }
        if (touchDisplayHintText != null) {
            touchDisplayHintText.setVisibility(keyboardMouseMode ? View.GONE : View.VISIBLE);
            if (!keyboardMouseMode) {
                touchDisplayHintText.setText(touchMode
                        ? R.string.touch_display_hint
                        : R.string.regular_display_gamepad_hint);
            }
        }
    }

    /**
     * 手柄模式保存物理摇杆与按钮绑定。运行时只映射到“按显亮起状态”，
     * 不向游戏注入键鼠事件；录入通过 Dialog KeyEvent 完成，避免新增监听线程。
     */
    private void showTouchDisplayGamepadAdjustDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(10), dp(20), dp(6));

        int savedStick = TouchDisplayStore.getGamepadStick(this);
        int[] pendingStick = {savedStick};
        int[] pendingKeyCodes = {
                TouchDisplayStore.getGamepadBindingKeyCode(this, TouchDisplayStore.TARGET_RMB),
                TouchDisplayStore.getGamepadBindingKeyCode(this, TouchDisplayStore.TARGET_LMB),
                TouchDisplayStore.getGamepadBindingKeyCode(this, TouchDisplayStore.TARGET_SPACE)
        };
        int[] pendingScanCodes = {
                TouchDisplayStore.getGamepadBindingScanCode(this, TouchDisplayStore.TARGET_RMB),
                TouchDisplayStore.getGamepadBindingScanCode(this, TouchDisplayStore.TARGET_LMB),
                TouchDisplayStore.getGamepadBindingScanCode(this, TouchDisplayStore.TARGET_SPACE)
        };
        int[] captureTarget = {-1};
        int[] motionButtonsDown = {0};

        Spinner stickSpinner = createChoiceSpinner(new String[]{
                getString(R.string.touch_display_gamepad_stick_unset),
                getString(R.string.touch_display_gamepad_stick_left),
                getString(R.string.touch_display_gamepad_stick_right)
        });
        panel.addView(createInlineChoiceRow(R.string.touch_display_gamepad_wasd_stick, stickSpinner),
                supportingParams(0));
        stickSpinner.setSelection(savedStick == TouchDisplayStore.STICK_LEFT ? 1
                : savedStick == TouchDisplayStore.STICK_RIGHT ? 2 : 0, false);

        TextView hint = createSupportingText();
        hint.setText(R.string.touch_display_gamepad_adjust_hint);
        panel.addView(hint, supportingParams(dp(6)));

        String[] targetLabels = {"RMB", "LMB", "Space"};
        Button[] bindingButtons = new Button[3];
        for (int i = 0; i < bindingButtons.length; i++) {
            final int target = i;
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setTextSize(13f);
            button.setMinHeight(dp(44));
            button.setMinimumHeight(dp(44));
            styleActionButton(button);
            button.setOnClickListener(v -> {
                captureTarget[0] = target;
                updateTouchGamepadBindingButtons(bindingButtons, targetLabels,
                        pendingKeyCodes, pendingScanCodes, captureTarget[0]);
            });
            bindingButtons[i] = button;
            panel.addView(button, supportingParams(dp(i == 0 ? 8 : 4)));
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.touch_display_gamepad_adjust_title)
                .setView(panel)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.touch_display_gamepad_finish, null)
                .create();

        Runnable refresh = () -> {
            updateTouchGamepadBindingButtons(bindingButtons, targetLabels,
                    pendingKeyCodes, pendingScanCodes, captureTarget[0]);
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setEnabled(isTouchGamepadPendingComplete(
                        pendingStick[0], pendingKeyCodes, pendingScanCodes));
            }
        };

        stickSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                pendingStick[0] = position == 1 ? TouchDisplayStore.STICK_LEFT
                        : position == 2 ? TouchDisplayStore.STICK_RIGHT : TouchDisplayStore.STICK_UNSET;
                refresh.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        dialog.setOnKeyListener((d, keyCode, event) -> {
            int target = captureTarget[0];
            if (target < 0) return false;
            if (event == null) return true;
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                captureTarget[0] = -1;
                refresh.run();
                return true;
            }
            InputDevice device = event.getDevice();
            if (device != null && InputBinding.isAxonVirtualDevice(device)) return true;
            if (!InputBinding.isGamepadEventForMapping(event)
                    || GamepadButtons.fromStoredKey(keyCode, event.getScanCode()) == 0) {
                Toast.makeText(MainActivity.this,
                        R.string.touch_display_gamepad_gamepad_key_only, Toast.LENGTH_SHORT).show();
                return true;
            }
            pendingKeyCodes[target] = keyCode;
            pendingScanCodes[target] = Math.max(0, event.getScanCode());
            captureTarget[0] = -1;
            refresh.run();
            return true;
        });

        dialog.setOnShowListener(ignored -> {
            View decor = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
            if (decor != null) {
                decor.setOnGenericMotionListener((v, event) -> {
                    if (event == null || !InputBinding.isPhysicalGamepadMotionEvent(event)) return false;
                    int current = InputBinding.gamepadButtonsFromMotionEvent(event);
                    int rising = current & ~motionButtonsDown[0];
                    motionButtonsDown[0] = current;
                    if (captureTarget[0] < 0 || rising == 0) return false;
                    int bit = Integer.lowestOneBit(rising);
                    int keyCode = GamepadButtons.toAndroidKeyCode(bit);
                    if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return false;
                    int target = captureTarget[0];
                    pendingKeyCodes[target] = keyCode;
                    pendingScanCodes[target] = Math.max(0,
                            InputBinding.evdevCode(InputBinding.gamepad(bit), -1));
                    captureTarget[0] = -1;
                    refresh.run();
                    return true;
                });
            }
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                if (!isTouchGamepadPendingComplete(pendingStick[0], pendingKeyCodes, pendingScanCodes)) return;
                TouchDisplayStore.saveGamepadConfig(MainActivity.this, pendingStick[0],
                        pendingKeyCodes, pendingScanCodes);
                dialog.dismiss();
                syncTouchDisplayModeUi();
                invalidateGlobalSearchIndex();
                if (displaySwitch != null && displaySwitch.isChecked()
                        && TouchDisplayStore.isGamepadMode(MainActivity.this)) {
                    ensureAccessibility();
                    ensurePrivilegedInputForGamepadMapping();
                }
            });
            refresh.run();
        });
        dialog.show();
    }

    private boolean isTouchGamepadPendingComplete(int stick, int[] keyCodes, int[] scanCodes) {
        if (stick == TouchDisplayStore.STICK_UNSET || keyCodes == null || keyCodes.length < 3) return false;
        for (int i = 0; i < 3; i++) {
            int keyCode = keyCodes[i];
            int scanCode = scanCodes != null && i < scanCodes.length ? scanCodes[i] : 0;
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN
                    || GamepadButtons.fromStoredKey(keyCode, scanCode) == 0) return false;
        }
        return true;
    }

    private void updateTouchGamepadBindingButtons(Button[] buttons, String[] labels,
                                                   int[] keyCodes, int[] scanCodes,
                                                   int captureTarget) {
        if (buttons == null || labels == null || keyCodes == null) return;
        for (int i = 0; i < buttons.length; i++) {
            Button button = buttons[i];
            if (button == null) continue;
            if (i == captureTarget) {
                button.setText(labels[i] + " · " + getString(R.string.touch_display_gamepad_press_key));
                continue;
            }
            int keyCode = i < keyCodes.length ? keyCodes[i] : KeyEvent.KEYCODE_UNKNOWN;
            int scanCode = scanCodes != null && i < scanCodes.length ? scanCodes[i] : 0;
            button.setText(labels[i] + " · " + touchGamepadKeyLabel(keyCode, scanCode));
        }
    }

    private String touchGamepadKeyLabel(int keyCode, int scanCode) {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return getString(R.string.touch_display_gamepad_unbound);
        int bit = GamepadButtons.fromStoredKey(keyCode, scanCode);
        if (bit != 0) return InputBinding.gamepadLabel(bit);
        String label = KeyEvent.keyCodeToString(keyCode);
        if (label.startsWith("KEYCODE_")) label = label.substring("KEYCODE_".length());
        if (label.isEmpty() && scanCode > 0) return "SCAN_" + scanCode;
        return label.isEmpty() ? String.valueOf(keyCode) : label;
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
                if (isFinishing() || isDestroyed()) return;
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
            if (!result) RootBridge.reportRootChannelFailure(getApplicationContext());
            mainHandler.post(() -> {
                accessibilityGrantInFlight = false;
                if (isFinishing() || isDestroyed()) return;
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
            int shortcutMask = FeatureShortcutStore.inputMask(this);
            boolean needsInput = SensitivitySettingsStore.isEnabled(this)
                    || OverlayState.isKeyboardCatEnabled(this)
                    || !GamepadMappingStore.load(this).isEmpty()
                    || (shortcutMask & (FeatureShortcutStore.INPUT_MOUSE
                    | FeatureShortcutStore.INPUT_GAMEPAD)) != 0
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

        superCustomDisplaySwitch = createSwitch(R.string.super_custom_display_switch);
        if (superCustomDisplaySwitch instanceof MotionSwitch) {
            ((MotionSwitch) superCustomDisplaySwitch).setPrimaryToggleStyle();
        }
        installFeatureShortcut(superCustomDisplaySwitch, group);

        TextView hint = createSupportingText();
        hint.setText(R.string.super_custom_home_hint);
        hint.setTextSize(12f);
        group.addView(hint, supportingParams(dp(10)));

        Button enter = createConfigButton(R.string.super_custom_enter, v ->
                startActivity(new Intent(MainActivity.this, SuperCustomDisplayActivity.class)));
        group.addView(enter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

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
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            label.setGravity(Gravity.CENTER_VERTICAL);

            Button load = createConfigButton(R.string.super_custom_load, v -> loadSuperCustomSlot(configSlot));
            Button importButton = createConfigButton(R.string.super_custom_import, v -> openSuperCustomImportPicker(configSlot));
            LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT);
            loadLp.leftMargin = dp(8);
            LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT);
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
        title.setTypeface(AppTypeface.heavy(this));
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
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftParams.rightMargin = dp(6);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
        if (isFinishing() || isDestroyed()) return;
        if (OverlayState.isEntryAuthorized(this)) {
            continueAuthorizedEntry(false);
            return;
        }
        if (activityRoot == null || entryPasswordGate != null) return;

        entryPasswordGate = new EntryPasswordGate(this, new EntryPasswordGate.Listener() {
            @Override public boolean isPasswordCorrect(String value) {
                return EntryAccessPolicy.matches(value);
            }

            @Override public void onAuthorized() {
                OverlayState.setEntryAuthorized(MainActivity.this, true);
                EntryPasswordGate gate = entryPasswordGate;
                entryPasswordGate = null;
                if (gate != null && activityRoot != null) {
                    try { activityRoot.removeView(gate); } catch (Throwable ignored) {}
                }
                applySystemBars();
                continueAuthorizedEntry(true);
                syncInAppLive2D();
            }

            @Override public void onOpenPasswordSource() {
                openPasswordSource();
            }
        });
        applyEntryGateSystemBars();
        activityRoot.addView(entryPasswordGate, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        entryPasswordGate.bringToFront();
    }

    /**
     * 密码已通过后的统一进入流程。字体必须先进入最终状态，再播放品牌遍历：
     * 已缓存字体在 Application.onCreate() 已预热，这里通常只做一次同步确认；
     * 若缓存被清理，则先完成自动补下载，再创建 EntryBrandRevealView。
     * 这样启动动画和随后页面始终使用同一 Typeface，不会在动画结束后发生整页字体跳变。
     */
    private void continueAuthorizedEntry(boolean justAuthorized) {
        AppFontChoiceController.showOnce(MainActivity.this, () -> {
            if (isFinishing() || isDestroyed()) return;
            continueAuthorizedEntryAfterFont(justAuthorized);
        });
    }

    private void continueAuthorizedEntryAfterFont(boolean justAuthorized) {
        boolean shouldReveal = justAuthorized || freshActivityLaunch;
        if (!shouldReveal || entryBrandRevealShown || activityRoot == null) {
            mainHandler.post(this::checkCloudNoticeThenContinue);
            return;
        }
        showEntryBrandReveal(this::checkCloudNoticeThenContinue);
    }

    private void showEntryBrandReveal(Runnable continuation) {
        if (activityRoot == null || isFinishing() || isDestroyed()) {
            if (continuation != null) mainHandler.post(continuation);
            return;
        }
        entryBrandRevealShown = true;
        dismissEntryBrandReveal(false);

        EntryBrandRevealView reveal = new EntryBrandRevealView(this);
        entryBrandRevealView = reveal;
        View content = entryContentView;
        if (content != null) {
            // 品牌动画开始前统一从透明状态起步，避免页面先闪现再被拉回 0。
            content.animate().cancel();
            content.setAlpha(0f);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        activityRoot.addView(reveal, params);
        reveal.bringToFront();
        reveal.start(progress -> {
            if (entryBrandRevealView != reveal || content == null) return;
            // 与字母遍历使用同一个 ValueAnimator 时间轴。smoothstep 比线性柔和，
            // 但仍严格在 progress=1 时才达到完全不透明。
            float t = Math.max(0f, Math.min(1f, progress));
            float alpha = t * t * (3f - 2f * t);
            content.setAlpha(alpha);
        }, () -> {
            if (content != null) content.setAlpha(1f);
            if (entryBrandRevealView == reveal) {
                entryBrandRevealView = null;
                if (activityRoot != null) {
                    try { activityRoot.removeView(reveal); } catch (Throwable ignored) {}
                }
            }
            if (continuation != null && !isFinishing() && !isDestroyed()) {
                mainHandler.post(continuation);
            }
        });
    }

    private void dismissEntryBrandReveal(boolean runContinuation) {
        EntryBrandRevealView reveal = entryBrandRevealView;
        if (reveal == null) return;
        entryBrandRevealView = null;
        reveal.cancel(runContinuation);
        if (activityRoot != null) {
            try { activityRoot.removeView(reveal); } catch (Throwable ignored) {}
        }
    }

    private void checkCloudNoticeThenContinue() {
        if (isFinishing() || isDestroyed()) return;
        CloudNoticeChecker.check(this, activity -> ((MainActivity) activity).continueAfterEntry());
    }

    private void continueAfterEntry() {
        mainHandler.post(this::ensureAccessibility);
        mainHandler.post(() -> UpdateChecker.check(this));
    }

    private LinearLayout createHomeOverviewBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        FluidHomeCard appCard = new FluidHomeCard(this);
        appCard.setOrientation(LinearLayout.HORIZONTAL);
        appCard.setGravity(Gravity.CENTER_VERTICAL);
        appCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        appCard.setMinimumHeight(dp(82));
        appCard.setClickable(true);
        appCard.setFocusable(true);
        appCard.setContentDescription(getString(R.string.home_open_source_title));
        appCard.setOnClickListener(v -> showOpenSourceDialog());
        UiMotion.bindPressFeedback(appCard);

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.mipmap.ic_launcher);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        appIcon.setClipToOutline(true);
        appIcon.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                int radius = dp(12);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        appCard.addView(appIcon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        LinearLayout appCopy = new LinearLayout(this);
        appCopy.setOrientation(LinearLayout.VERTICAL);
        appCopy.setGravity(Gravity.CENTER_VERTICAL);
        TextView appName = new TextView(this);
        appName.setText(R.string.app_name);
        appName.setTextColor(UiPalette.textPrimary(this));
        appName.setTextSize(16f);
        appName.setTypeface(AppTypeface.heavy(this));
        appCopy.addView(appName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView version = new TextView(this);
        version.setText(getString(R.string.home_version_format, AppVersion.name(this)));
        version.setTextColor(UiPalette.textSecondary(this));
        version.setTextSize(11f);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionParams.topMargin = dp(3);
        appCopy.addView(version, versionParams);

        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.leftMargin = dp(13);
        appCard.addView(appCopy, copyParams);
        block.addView(appCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tileRow = new LinearLayout(this);
        tileRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(10);
        block.addView(tileRow, rowParams);

        LinearLayout developerCard = createHomeSquareTile();
        developerCard.setContentDescription(getString(R.string.home_developer_name));
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.developer_bacon);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setClipToOutline(true);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        avatarParams.gravity = Gravity.CENTER_HORIZONTAL;
        avatarParams.topMargin = dp(6);
        developerCard.addView(avatar, avatarParams);

        TextView developerName = createHomeTileTitle(R.string.home_developer_name);
        LinearLayout.LayoutParams developerNameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        developerNameParams.topMargin = dp(7);
        developerCard.addView(developerName, developerNameParams);

        TextView developerRole = createHomeTileSubtitle(R.string.home_developer_role);
        LinearLayout.LayoutParams roleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        roleParams.topMargin = dp(2);
        developerCard.addView(developerRole, roleParams);
        developerCard.setOnClickListener(v -> showDeveloperContactDialog());
        UiMotion.bindPressFeedback(developerCard);

        LinearLayout donationCard = createHomeSquareTile();
        donationCard.setContentDescription(getString(R.string.home_donate));
        SolidLikeView like = new SolidLikeView(this);
        LinearLayout.LayoutParams likeParams = new LinearLayout.LayoutParams(dp(62), dp(62));
        likeParams.gravity = Gravity.CENTER_HORIZONTAL;
        likeParams.topMargin = dp(9);
        donationCard.addView(like, likeParams);

        TextView donateText = createHomeTileTitle(R.string.home_donate);
        LinearLayout.LayoutParams donateTextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        donateTextParams.topMargin = dp(8);
        donationCard.addView(donateText, donateTextParams);
        donationCard.setOnClickListener(v -> showRewardDialog());
        UiMotion.bindPressFeedback(donationCard);

        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, dp(150), 1f);
        tileRow.addView(developerCard, tileParams);
        LinearLayout.LayoutParams donateParams = new LinearLayout.LayoutParams(0, dp(150), 1f);
        donateParams.leftMargin = dp(10);
        tileRow.addView(donationCard, donateParams);
        return block;
    }

    private LinearLayout createHomeSquareTile() {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(10), dp(8), dp(10), dp(10));
        tile.setBackground(UiChrome.homeSecondaryCard(this));
        tile.setClickable(true);
        tile.setFocusable(true);
        return tile;
    }

    private TextView createHomeTileTitle(int textRes) {
        TextView text = new TextView(this);
        text.setText(textRes);
        text.setTextColor(UiPalette.textPrimary(this));
        text.setTextSize(13.5f);
        text.setGravity(Gravity.CENTER);
        text.setTypeface(AppTypeface.heavy(this));
        return text;
    }

    private TextView createHomeTileSubtitle(int textRes) {
        TextView text = new TextView(this);
        text.setText(textRes);
        text.setTextColor(UiPalette.textTertiary(this));
        text.setTextSize(10.5f);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private void showOpenSourceDialog() {
        TextView content = createSupportingText();
        content.setText(R.string.home_open_source_body);
        content.setTextSize(13f);
        content.setTextColor(UiPalette.textPrimary(this));
        content.setLineSpacing(dp(2), 1.12f);
        content.setPadding(dp(20), dp(8), dp(20), dp(16));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.home_open_source_title)
                .setView(scroll)
                .setPositiveButton(R.string.reward_close, null)
                .show();
    }

    private void showDeveloperContactDialog() {
        String[] options = new String[]{
                getString(R.string.home_contact_qq),
                getString(R.string.home_contact_bilibili)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.home_developer_name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) openQqProfile();
                    else openBilibiliProfile();
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
                .setTitle(R.string.home_reward_title)
                .setView(container)
                .setPositiveButton(R.string.reward_close, null)
                .show();
    }

    private void openQqProfile() {
        Uri profile = Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=3904457897&card_type=person&source=qrcode");
        Intent qq = new Intent(Intent.ACTION_VIEW, profile);
        qq.setPackage("com.tencent.mobileqq");
        try {
            startActivity(qq);
            return;
        } catch (ActivityNotFoundException ignored) {
        }

        Intent browser = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://user.qzone.qq.com/3904457897"));
        try {
            startActivity(browser);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.home_qq_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openBilibiliProfile() {
        Intent bilibili = new Intent(Intent.ACTION_VIEW,
                Uri.parse("bilibili://space/3546608574663321"));
        bilibili.setPackage("tv.danmaku.bili");
        try {
            startActivity(bilibili);
            return;
        } catch (ActivityNotFoundException ignored) {
        }

        Intent browser = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://space.bilibili.com/3546608574663321"));
        try {
            startActivity(browser);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, R.string.home_bilibili_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openPasswordSource() {
        openBilibiliProfile();
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
        if (featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
        if (hideDisplayHotkeyCaptureArmed) {
            cancelHideDisplayHotkeyCapture(true);
            return;
        }
        if (forceHoldCaptureStep != 0 || customMappingCaptureStep != 0
                || clickMultiplierCaptureStep != 0 || simultaneousClickCaptureStep != 0 || gamepadCustomSwapCaptureStep != 0
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
        if (FeatureShortcutStore.conflicts(this, null, inputCode)) return true;
        if (OverlayState.isForceHoldEnabled(this)
                && inputCode == OverlayState.getForceHoldTriggerKeyCode(this)) return true;
        if (inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)) return true;
        if (physicsHotkeyConflicts(inputCode, null, null)) return true;
        if (keyboardCatFunctionHotkeyConflicts(inputCode)) return true;
        if (gamepadMappingUsesSource(inputCode)) return true;
        if (customMappingUsesTrigger(inputCode)) return true;
        if (simultaneousClickUsesSource(inputCode)) return true;
        if (clickMultiplierUsesInput(inputCode)) return true;
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
        if (FeatureShortcutStore.conflicts(this, null, inputCode)) return true;
        if (gamepadMappingUsesSource(inputCode)) return true;
        if (simultaneousClickUsesSource(inputCode)) return true;
        if (clickMultiplierUsesInput(inputCode)) return true;
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
        if (featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
        if (customMappingCaptureStep != 0) {
            cancelCustomMappingCapture();
            return;
        }
        if (forceHoldCaptureStep != 0 || simultaneousClickCaptureStep != 0
                || clickMultiplierCaptureStep != 0 || gamepadCustomSwapCaptureStep != 0 || gamepadMappingCaptureArmed
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

    private boolean clickMultiplierUsesInput(int inputCode) {
        return ClickMultiplierStore.usesToggle(this, inputCode)
                || ClickMultiplierStore.usesTarget(this, inputCode);
    }

    private boolean clickMultiplierToggleConflicts(int inputCode) {
        if (inputCode < 0) return true;
        if (FeatureShortcutStore.conflicts(this, null, inputCode)) return true;
        if (gamepadMappingUsesSource(inputCode)) return true;
        if (customMappingUsesTrigger(inputCode)) return true;
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

    private boolean clickMultiplierTargetConflicts(int inputCode) {
        // 倍率键本身保持原始输入，因此不能同时充当另一项会消费/切换状态的快捷键。
        return clickMultiplierToggleConflicts(inputCode);
    }

    private void startClickMultiplierCapture() {
        if (featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
        if (clickMultiplierCaptureStep != 0) {
            cancelClickMultiplierCapture();
            return;
        }
        if (customMappingCaptureStep != 0 || simultaneousClickCaptureStep != 0
                || forceHoldCaptureStep != 0 || gamepadCustomSwapCaptureStep != 0
                || gamepadMappingCaptureArmed || hideDisplayHotkeyCaptureArmed
                || keyboardCatExpressionHotkeyCaptureArmed || physicsHotkeyCaptureArmed
                || keyboardCatFunctionBindingCaptureArmed) {
            Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        AxonInputAccessibilityService.releaseClickMultiplierForCapture();
        clickMultiplierCaptureStep = 1;
        clickMultiplierTogglePending = -1;
        updateClickMultiplierUi();
        AxonInputAccessibilityService.refreshActiveService();
        ensureAccessibility();
        Toast.makeText(this, R.string.click_multiplier_wait_toggle, Toast.LENGTH_SHORT).show();
    }

    private void cancelClickMultiplierCapture() {
        if (clickMultiplierCaptureStep == 0) return;
        clickMultiplierCaptureStep = 0;
        clickMultiplierTogglePending = -1;
        if (clickMultiplierSwitch != null) updateClickMultiplierUi();
        AxonInputAccessibilityService.refreshActiveService();
    }

    private void updateClickMultiplierUi() {
        if (clickMultiplierSwitch == null || clickMultiplierStatusText == null
                || clickMultiplierConfigButton == null) return;
        boolean previousInternal = internalChange;
        internalChange = true;
        try {
            clickMultiplierSwitch.setChecked(clickMultiplierCaptureStep != 0
                    || ClickMultiplierStore.isEnabled(this));
        } finally {
            internalChange = previousInternal;
        }

        int multiplier = ClickMultiplierStore.getMultiplier(this);
        int delay = ClickMultiplierStore.getDelayMs(this);
        if (clickMultiplierValueSeekBar != null) {
            clickMultiplierValueSeekBar.setProgress(multiplier - ClickMultiplierStore.MULTIPLIER_MIN);
        }
        if (clickMultiplierDelaySeekBar != null) {
            clickMultiplierDelaySeekBar.setProgress(delay / CLICK_MULTIPLIER_DELAY_STEP_MS);
        }
        if (clickMultiplierValueLabel != null) {
            setSliderLabelTitle(clickMultiplierValueLabel, R.string.click_multiplier_value_format);
        }
        if (clickMultiplierDelayLabel != null) {
            setSliderLabelTitle(clickMultiplierDelayLabel, R.string.click_multiplier_delay_format);
        }

        if (clickMultiplierCaptureStep == 1) {
            clickMultiplierStatusText.setText(R.string.click_multiplier_wait_toggle);
            clickMultiplierConfigButton.setText(android.R.string.cancel);
            return;
        }
        if (clickMultiplierCaptureStep == 2 && clickMultiplierTogglePending >= 0) {
            clickMultiplierStatusText.setText(getString(R.string.click_multiplier_wait_target,
                    InputBinding.label(clickMultiplierTogglePending)));
            clickMultiplierConfigButton.setText(android.R.string.cancel);
            return;
        }
        if (!ClickMultiplierStore.hasBinding(this)) {
            clickMultiplierStatusText.setText(R.string.click_multiplier_empty);
            clickMultiplierConfigButton.setText(R.string.click_multiplier_config);
            return;
        }
        boolean active = AxonInputAccessibilityService.isClickMultiplierActive();
        clickMultiplierStatusText.setText(getString(R.string.click_multiplier_ready,
                InputBinding.label(ClickMultiplierStore.getToggleInputCode(this)),
                InputBinding.label(ClickMultiplierStore.getTargetInputCode(this)),
                getString(active ? R.string.status_enabled : R.string.status_disabled)));
        clickMultiplierConfigButton.setText(R.string.click_multiplier_rebind);
    }

    private boolean simultaneousClickUsesSource(int inputCode) {
        return SimultaneousClickStore.usesSource(this, inputCode);
    }

    private boolean simultaneousClickSourceConflicts(int inputCode) {
        if (inputCode < 0) return true;
        if (FeatureShortcutStore.conflicts(this, null, inputCode)) return true;
        if (gamepadMappingUsesSource(inputCode)) return true;
        if (customMappingUsesTrigger(inputCode)) return true;
        if (clickMultiplierUsesInput(inputCode)) return true;
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
        if (featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
        if (simultaneousClickCaptureStep != 0) {
            simultaneousClickCaptureStep = 0;
            simultaneousClickSourcePending = -1;
            updateSimultaneousClickUi();
            AxonInputAccessibilityService.refreshActiveService();
            return;
        }
        if (forceHoldCaptureStep != 0 || gamepadCustomSwapCaptureStep != 0
                || customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0
                || gamepadMappingCaptureArmed || hideDisplayHotkeyCaptureArmed
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

    private void syncKeyboardMouseSpaceSwapAvailability() {
        if (keyboardMouseSpaceSwapSwitch == null) return;
        boolean unlocked = spaceDisplaySwitch != null && spaceDisplaySwitch.isChecked()
                && keyboardMouseButtonsSwitch != null && keyboardMouseButtonsSwitch.isChecked();
        setControlEnabled(keyboardMouseSpaceSwapSwitch, unlocked);
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
        if (featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
        if (gamepadMappingCaptureArmed) {
            gamepadMappingCaptureArmed = false;
            pendingGamepadMappingSource = -1;
            updateGamepadMappingUi();
            return;
        }
        if (forceHoldCaptureStep != 0 || simultaneousClickCaptureStep != 0 || customMappingCaptureStep != 0
                || clickMultiplierCaptureStep != 0 || gamepadCustomSwapCaptureStep != 0 || hideDisplayHotkeyCaptureArmed
                || keyboardCatExpressionHotkeyCaptureArmed || physicsHotkeyCaptureArmed
                || keyboardCatFunctionBindingCaptureArmed) {
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
        RootBridge.ensureActivated(this, (activity, rootActive) -> {
            MainActivity owner = (MainActivity) activity;
            if (!rootActive && (!ShizukuBridge.isReady() || !ShizukuBridge.hasPermission())) {
                owner.ensureShizukuForSensitivity();
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
        setSliderLabelTitle(live2dPhysicsStrengthLabel, R.string.live2d_physics_strength_format);
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
        SeekBar weight = new LagSeekBar(this);
        weight.setMax(100);
        styleSeekBar(weight);
        ((LagSeekBar) weight).setValueDisplaySpec(0, 1, "%");
        int storedWeight = Live2DDebugSettingsStore.getExpressionWeight(this, target, expression.token);
        weight.setProgress(storedWeight);
        setSliderLabelTitle(weightLabel, R.string.live2d_expression_weight_format);
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
        actions.addView(preview, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(actions, supportingParams(dp(8)));

        final boolean[] previewing = {false};
        weight.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setSliderLabelTitle(weightLabel, R.string.live2d_expression_weight_format);
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
        SeekBar lockValue = new LagSeekBar(this);
        lockValue.setMax(100);
        styleSeekBar(lockValue);
        ((LagSeekBar) lockValue).setValueDisplaySpec(0, 1, "%");
        int initial = storedLock == null ? 50 : Math.round(storedLock * 100f);
        lockValue.setProgress(initial);
        lockValue.setEnabled(storedLock != null);
        setSliderLabelTitle(lockValueLabel, R.string.live2d_parameter_lock_value);
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
                setSliderLabelTitle(lockValueLabel, R.string.live2d_parameter_lock_value);
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
        SeekBar value = new LagSeekBar(this);
        value.setMax(200);
        styleSeekBar(value);
        ((LagSeekBar) value).setValueDisplaySpec(0, 1, "%");
        int initial = Live2DPhysicsSettingsStore.getGlobalStrength(this, target);
        value.setProgress(initial);
        setSliderLabelTitle(label, R.string.live2d_physics_strength_format);
        panel.addView(label, supportingParams(0));
        panel.addView(value, seekBarLayoutParams(dp(2)));

        value.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setSliderLabelTitle(label, R.string.live2d_physics_strength_format);
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
        SeekBar strength = new LagSeekBar(this);
        strength.setMax(200);
        styleSeekBar(strength);
        ((LagSeekBar) strength).setValueDisplaySpec(0, 1, "%");
        strength.setProgress(stored.strength);
        strength.setEnabled(stored.enabled);
        setSliderLabelTitle(strengthLabel, R.string.live2d_physics_group_strength_format);
        panel.addView(strengthLabel, supportingParams(dp(6)));
        panel.addView(strength, seekBarLayoutParams(dp(2)));

        Button reset = new Button(this);
        reset.setText(R.string.live2d_physics_group_reset);
        reset.setAllCaps(false);
        reset.setTextSize(12f);
        reset.setMinHeight(dp(44));
        reset.setMinimumHeight(dp(44));
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
        hotkeyEnabled.setAlpha(1f);
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
        LinearLayout.LayoutParams actionLeft = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actionLeft.setMarginEnd(dp(4));
        LinearLayout.LayoutParams actionRight = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
                setSliderLabelTitle(strengthLabel, R.string.live2d_physics_group_strength_format);
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
            setSliderLabelTitle(strengthLabel, R.string.live2d_physics_group_strength_format);
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
            hotkeyEnabled.setAlpha(1f);
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
        SeekBar value = new LagSeekBar(this);
        value.setMax(100);
        styleSeekBar(value);
        ((LagSeekBar) value).setValueDisplaySpec(0, 1, "%");
        float stored = KeyboardCatFunctionBindingStore.getParameterValue(this, styleId, option.id, 0f);
        Float storedLock = Live2DDebugSettingsStore.getParameterLock(this, debugTarget, option.id);
        value.setProgress(Math.round((storedLock == null ? stored : storedLock) * 100f));
        setSliderLabelTitle(valueLabel, R.string.keyboard_cat_parameter_value);
        panel.addView(value, seekBarLayoutParams(dp(2)));

        Switch lock = createSwitch(R.string.live2d_parameter_lock);
        lock.setChecked(storedLock != null);
        panel.addView(lock, supportingParams(dp(6)));

        value.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setSliderLabelTitle(valueLabel, R.string.keyboard_cat_parameter_value);
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
        actions.addView(test, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(bind, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
        if (featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
        if (customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0
                || simultaneousClickCaptureStep != 0 || forceHoldCaptureStep != 0 || gamepadMappingCaptureArmed) {
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
        if (featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
        if (customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0
                || simultaneousClickCaptureStep != 0 || forceHoldCaptureStep != 0 || gamepadMappingCaptureArmed) {
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
        if (featureShortcutCaptureActive()) cancelFeatureShortcutCapture(false);
        if (keyboardCatExpressions.isEmpty()) {
            Toast.makeText(this, R.string.keyboard_cat_expression_hotkey_no_expressions, Toast.LENGTH_SHORT).show();
            return;
        }
        if (keyboardCatExpressionHotkeyCaptureArmed) {
            cancelKeyboardCatExpressionHotkeyCapture(true);
            return;
        }
        if (customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0
                || simultaneousClickCaptureStep != 0 || forceHoldCaptureStep != 0 || gamepadMappingCaptureArmed) {
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
                && entryPasswordGate == null
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
        return readText(this, uri, maxBytes);
    }

    private static String readText(Context context, Uri uri, int maxBytes) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
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
        return queryDisplayName(this, uri, fallback);
    }

    private static String queryDisplayName(Context context, Uri uri, String fallback) {
        String name = fallback == null || fallback.isEmpty() ? "file" : fallback;
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
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
        colorTarget.setBackground(UiChrome.controlRipple(this));
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
        ((LagSeekBar) cornerSeekBar).setValueDisplaySpec(0, 1, "%");
        keyCornerStrengthControls.put(displayType, new CornerStrengthControl(cornerLabel, cornerSeekBar));
        parent.addView(cornerLabel, supportingParams(dp(2)));
        parent.addView(cornerSeekBar, seekBarLayoutParams(dp(4)));

        cornerSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setSliderLabelTitle(cornerLabel, isStickDisplay(displayType)
                        ? R.string.gamepad_stick_background_corner_strength_format
                        : R.string.key_corner_strength_format);
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
        baseColorRow.setBackgroundColor(Color.TRANSPARENT);
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
        borderColorRow.setBackgroundColor(Color.TRANSPARENT);
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
        colorRow.setBackgroundColor(Color.TRANSPARENT);
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
        colorRow.setBackgroundColor(Color.TRANSPARENT);
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
        row.setBackgroundColor(Color.TRANSPARENT);
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
        row.setBackgroundColor(Color.TRANSPARENT);
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
        ((LagSeekBar) seekBar).setValueDisplaySpec(0, 1, "%");
        opacityControls.put(displayType, new OpacityControl(label, seekBar));
        parent.addView(label, supportingParams(dp(2)));
        parent.addView(seekBar, seekBarLayoutParams(dp(4)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                setSliderLabelTitle(label, R.string.display_opacity_format);
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
        final FrameLayout textSeekHost;
        final View textLock;
        final TextView diffusionLabel;
        final SeekBar diffusionSeek;
        final FrameLayout diffusionSeekHost;
        final View diffusionLock;
        final boolean diffusionAsCenterOpacity;
        final boolean textOpacityApplicable;

        KeyLayerOpacityControl(TextView backgroundLabel, SeekBar backgroundSeek,
                               TextView strokeLabel, SeekBar strokeSeek,
                               TextView textLabel, SeekBar textSeek,
                               FrameLayout textSeekHost, View textLock,
                               TextView diffusionLabel, SeekBar diffusionSeek,
                               FrameLayout diffusionSeekHost, View diffusionLock,
                               boolean diffusionAsCenterOpacity, boolean textOpacityApplicable) {
            this.backgroundLabel = backgroundLabel;
            this.backgroundSeek = backgroundSeek;
            this.strokeLabel = strokeLabel;
            this.strokeSeek = strokeSeek;
            this.textLabel = textLabel;
            this.textSeek = textSeek;
            this.textSeekHost = textSeekHost;
            this.textLock = textLock;
            this.diffusionLabel = diffusionLabel;
            this.diffusionSeek = diffusionSeek;
            this.diffusionSeekHost = diffusionSeekHost;
            this.diffusionLock = diffusionLock;
            this.diffusionAsCenterOpacity = diffusionAsCenterOpacity;
            this.textOpacityApplicable = textOpacityApplicable;
        }
    }

    private void addKeyLayerOpacityControls(LinearLayout parent, int displayType) {
        TextView backgroundLabel = createLabel();
        SeekBar backgroundSeek = createOpacitySeekBar();
        TextView strokeLabel = createLabel();
        SeekBar strokeSeek = createOpacitySeekBar();
        boolean stickDisplay = isStickDisplay(displayType);

        // 摇杆没有文本层。与其保留一个永远锁定的“文本透明度”，不如不创建无意义控件；
        // 底层旧配置字段仍然保留，确保旧配置导入和其他按显组件完全兼容。
        TextView textLabel = null;
        SeekBar textSeek = null;
        FrameLayout textSeekHost = null;
        View textLock = null;
        if (!stickDisplay) {
            textLabel = createLabel();
            textSeek = createOpacitySeekBar();
            textSeekHost = createLockableSeekHost(textSeek);
            textLock = lockViewOf(textSeekHost);
        }

        TextView diffusionLabel = createLabel();
        SeekBar diffusionSeek = createOpacitySeekBar();
        FrameLayout diffusionSeekHost = createLockableSeekHost(diffusionSeek);
        View diffusionLock = lockViewOf(diffusionSeekHost);

        parent.addView(backgroundLabel, supportingParams(dp(2)));
        parent.addView(backgroundSeek, seekBarLayoutParams(dp(3)));
        parent.addView(strokeLabel, supportingParams(dp(2)));
        parent.addView(strokeSeek, seekBarLayoutParams(dp(3)));
        if (!stickDisplay) {
            parent.addView(textLabel, supportingParams(dp(2)));
            parent.addView(textSeekHost, seekBarLayoutParams(dp(3)));
        }
        parent.addView(diffusionLabel, supportingParams(dp(2)));
        parent.addView(diffusionSeekHost, seekBarLayoutParams(dp(4)));

        keyLayerOpacityControls.put(displayType, new KeyLayerOpacityControl(
                backgroundLabel, backgroundSeek, strokeLabel, strokeSeek, textLabel, textSeek,
                textSeekHost, textLock, diffusionLabel, diffusionSeek,
                diffusionSeekHost, diffusionLock, stickDisplay, !stickDisplay));

        bindKeyLayerOpacity(backgroundSeek, backgroundLabel, displayType, 0);
        bindKeyLayerOpacity(strokeSeek, strokeLabel, displayType, 1);
        if (!stickDisplay) bindKeyLayerOpacity(textSeek, textLabel, displayType, 2);
        bindKeyLayerOpacity(diffusionSeek, diffusionLabel, displayType, 3);

        updateDiffusionOpacityEnabled(displayType,
                OverlayState.getMotionMode(this, displayType) == OverlayState.MOTION_RIPPLE);
    }

    private SeekBar createCornerStrengthSeekBar() {
        SeekBar seekBar = new LagSeekBar(this);
        seekBar.setMax(100);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        ((LagSeekBar) seekBar).setValueDisplaySpec(0, 1, "%");
        return seekBar;
    }

    private SeekBar.OnSeekBarChangeListener cornerStrengthListener(TextView label, IntSetter setter) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setSliderLabelTitle(label, R.string.gamepad_stick_center_corner_strength_format);
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
        ((LagSeekBar) seekBar).setValueDisplaySpec(0, 1, "%");
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
                setSliderLabelTitle(label, format);
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
            // 摇杆复用该值作为圆心透明度，因此始终可编辑。
            setLockedDebugItem(control.diffusionLabel, control.diffusionSeek,
                    control.diffusionLock, false);
            return;
        }
        boolean available = !hasSelectableMotionMode(displayType) || diffusionModeSelected;
        setLockedDebugItem(control.diffusionLabel, control.diffusionSeek,
                control.diffusionLock, !available);
    }

    private FrameLayout createLockableSeekHost(SeekBar seekBar) {
        FrameLayout host = new FrameLayout(this);
        host.setClipChildren(false);
        host.setClipToPadding(false);
        host.addView(seekBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));
        // LagSeekBar 自身绘制锁；不再叠第二个 View，避免锁和轨道的几何中心出现偏差。
        return host;
    }

    private View lockViewOf(FrameLayout host) {
        return null;
    }

    private void setLockedDebugItem(TextView label, SeekBar seekBar, View lock, boolean locked) {
        if (label != null) {
            label.setEnabled(!locked);
            label.setAlpha(1f);
            label.setTextColor(locked
                    ? UiPalette.textTertiary(this)
                    : UiPalette.textSecondary(this));
            label.setBackground(null);
        }
        if (seekBar != null) {
            seekBar.setEnabled(!locked);
            seekBar.setAlpha(1f);
        }
        if (lock != null) lock.setVisibility(View.GONE);
    }

    private TextView createTitle() {
        TextView title = new TextView(this);
        title.setTextColor(UiPalette.textPrimary(this));
        title.setTextSize(21f);
        title.setTypeface(AppTypeface.heavy(this));
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setMinHeight(dp(44));
        return title;
    }

    private TextView createSectionLabel() {
        TextView label = new TextView(this);
        label.setTextColor(UiPalette.textTertiary(this));
        label.setTextSize(12f);
        label.setTypeface(AppTypeface.heavy(this));
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
        // 保存功能标题资源 ID，只作为 UI 语义元数据；不参与状态、配置或输入路由。
        view.setTag(Integer.valueOf(labelRes));
        view.setTextColor(UiPalette.textSecondary(this));
        view.setTextSize(13f);
        AppTypeface.applyIfSelected(view);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(0, 0, 0, 0);
        view.setSingleLine(false);
        UiChrome.styleSwitch(this, view);
        return view;
    }

    private TextView createLabel() {
        TextView label = new TextView(this);
        label.setTextColor(UiPalette.textSecondary(this));
        label.setTextSize(12.5f);
        AppTypeface.applyIfSelected(label);
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        label.setMinHeight(dp(22));
        label.setIncludeFontPadding(true);
        label.setPadding(0, 0, 0, 0);
        label.setBackground(null);
        return label;
    }

    private TextView createSupportingText() {
        TextView text = new TextView(this);
        text.setTextColor(UiPalette.textTertiary(this));
        text.setTextSize(12f);
        AppTypeface.applyIfSelected(text);
        text.setLineSpacing(0f, 1.08f);
        text.setIncludeFontPadding(true);
        text.setBackground(null);
        return text;
    }

    private LinearLayout createDetailsContainer() {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        // 详情直接嵌在功能卡片里，不再额外套一层明显的“调试背景”。
        details.setPadding(dp(4), dp(4), dp(4), dp(2));
        details.setBackgroundColor(Color.TRANSPARENT);
        details.setVisibility(View.GONE);
        return details;
    }

    private LinearLayout createSwitchGroup(Switch primary) {
        // 即使原功能没有其它调试项，也提供统一的快捷键详情入口。
        return createFeatureGroup(primary, createDetailsContainer());
    }

    private LinearLayout createFeatureGroup(Switch primary, LinearLayout details) {
        if (primary instanceof MotionSwitch) ((MotionSwitch) primary).setPrimaryToggleStyle();
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        // 两行标题后仍保持紧凑，避免新增说明文字后让卡片整体变高。
        group.setPadding(dp(16), dp(2), dp(12), dp(6));
        group.setBackground(UiChrome.card(this));
        UiMotion.enableLayoutMotion(group);

        CharSequence featureText = primary.getText();
        int descriptionRes = featureDescriptionRes(primary);
        primary.setText("");
        primary.setContentDescription(featureText);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = createFeatureCopy(featureText, descriptionRes);
        TextView featureTitle = (TextView) copy.getChildAt(0);
        // 点击区域覆盖标题与说明两行，仍然只属于“功能文本”，同时保证足够大的触摸目标。
        copy.setClickable(true);
        copy.setFocusable(true);
        copy.setContentDescription(featureText + " · " + getString(R.string.details_expand));
        copy.setOnClickListener(v -> setDetailsExpanded(
                details, details.getVisibility() != View.VISIBLE, true));
        UiMotion.bindPressFeedback(copy);

        header.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(primary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        group.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶层可开关功能统一把“快捷键”放在详情第一项，避免每个分页复制录入逻辑。
        installFeatureShortcut(primary, details);

        // 详情文本与开关在这里做一次统一视觉归一化；Slider 名称必须保留并正常显示。
        normalizeDetailsVisuals(details);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(1);
        detailParams.rightMargin = dp(4);
        group.addView(details, detailParams);

        detailDisclosures.put(details, null);
        detailDisclosureTargets.put(details, copy);
        return group;
    }

    private void installFeatureShortcut(Switch primary, LinearLayout container) {
        String featureId = featureShortcutId(primary);
        if (featureId == null || container == null) return;
        String rowTag = "axon_feature_shortcut:" + featureId;
        // createFeatureGroup 与最终 UI 补齐扫描可能都会经过这里；按稳定 tag 去重，
        // 保证每个功能详情有且只有一条快捷键，同时允许 Activity recreate 安全重建。
        if (container.findViewWithTag(rowTag) != null) return;

        LinearLayout row = new LinearLayout(this);
        row.setTag(rowTag);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));

        TextView label = createLabel();
        label.setText(R.string.feature_shortcut_label);
        label.setTextSize(13f);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button button = createConfigButton(R.string.feature_shortcut_unbound,
                v -> beginFeatureShortcutCapture(featureId));
        button.setTextSize(12f);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setOnLongClickListener(v -> {
            if (featureShortcutCaptureId.equals(featureId)) cancelFeatureShortcutCapture(false);
            FeatureShortcutStore.clearBinding(MainActivity.this, featureId);
            refreshFeatureShortcutButton(featureId);
            Toast.makeText(MainActivity.this, R.string.feature_shortcut_cleared, Toast.LENGTH_SHORT).show();
            return true;
        });
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonLp.leftMargin = dp(10);
        row.addView(button, buttonLp);

        featureShortcutButtons.put(featureId, button);
        container.addView(row, 0, supportingParams(0));
        refreshFeatureShortcutButton(featureId);
    }

    private void ensureAllFeatureShortcutRows() {
        installFeatureShortcut(displaySwitch, keyboardDetails);
        installFeatureShortcut(inputFullKeyboardSwitch, inputFullKeyboardDetails);
        installFeatureShortcut(mouseSwitch, mouseDetails);
        installFeatureShortcut(keyboardCatSwitch, keyboardCatDetails);
        installFeatureShortcut(keyPromptSwitch, keyPromptDetails);
        installFeatureShortcut(mouseTrajectorySwitch, mouseTrajectoryDetails);
        installFeatureShortcut(customDisplaySwitch, customDetails);
        installFeatureShortcut(gamepadLeftStickSwitch, gamepadLeftStickDetails);
        installFeatureShortcut(gamepadRightStickSwitch, gamepadRightStickDetails);
        installFeatureShortcut(gamepadFaceSwitch, gamepadFaceDetails);
        installFeatureShortcut(gamepadDpadSwitch, gamepadDpadDetails);
        installFeatureShortcut(gamepadLeftShoulderSwitch, gamepadLeftShoulderDetails);
        installFeatureShortcut(gamepadRightShoulderSwitch, gamepadRightShoulderDetails);
        installFeatureShortcut(gamepadBackSwitch, gamepadBackDetails);
        installFeatureShortcut(sensitivitySwitch, sensitivityDetails);
        installFeatureShortcut(customMappingSwitch, customMappingDetails);
        installFeatureShortcut(clickMultiplierSwitch, clickMultiplierDetails);
        installFeatureShortcut(simultaneousClickSwitch, simultaneousClickDetails);
        installFeatureShortcut(forceHoldSwitch, forceHoldDetails);
        installFeatureShortcut(hideDisplayHotkeySwitch, hideDisplayHotkeyDetails);
        installFeatureShortcut(dpsSwitch, dpsDetails);
        installFeatureShortcut(fontSwitch, fontDetails);
        installFeatureShortcut(globalHtmlSwitch, globalHtmlDetails);
        installFeatureShortcut(live2dSwitch, live2dDetails);
    }

    private String featureShortcutId(Switch primary) {
        // 主开关在 createSwitch() 时已经保存稳定的标题资源 ID。快捷键入口必须基于这个
        // UI 语义标识解析，而不是依赖 Activity 字段与当前 View 实例恰好是同一个对象。
        // 页面重建、字体/主题 recreate、公共组件包装后都仍能得到相同 Feature ID。
        if (primary == null || !(primary.getTag() instanceof Integer)) return null;
        int labelRes = (Integer) primary.getTag();
        if (labelRes == R.string.switch_label) return FeatureShortcutStore.REGULAR_DISPLAY;
        if (labelRes == R.string.input_full_keyboard_switch_label) return FeatureShortcutStore.FULL_KEYBOARD;
        if (labelRes == R.string.mouse_switch_label) return FeatureShortcutStore.MOUSE_DISPLAY;
        if (labelRes == R.string.keyboard_cat_switch_label) return FeatureShortcutStore.KEYBOARD_CAT;
        if (labelRes == R.string.key_prompt_switch_label) return FeatureShortcutStore.KEY_PROMPT;
        if (labelRes == R.string.mouse_trajectory_switch_label) return FeatureShortcutStore.MOUSE_TRAJECTORY;
        if (labelRes == R.string.custom_switch_label) return FeatureShortcutStore.CUSTOM_DISPLAY;
        if (labelRes == R.string.super_custom_display_switch) return FeatureShortcutStore.SUPER_CUSTOM;
        if (labelRes == R.string.gamepad_left_stick_switch) return FeatureShortcutStore.GAMEPAD_LEFT_STICK;
        if (labelRes == R.string.gamepad_right_stick_switch) return FeatureShortcutStore.GAMEPAD_RIGHT_STICK;
        if (labelRes == R.string.gamepad_face_switch) return FeatureShortcutStore.GAMEPAD_FACE;
        if (labelRes == R.string.gamepad_dpad_switch) return FeatureShortcutStore.GAMEPAD_DPAD;
        if (labelRes == R.string.gamepad_left_shoulder_switch) return FeatureShortcutStore.GAMEPAD_LEFT_SHOULDER;
        if (labelRes == R.string.gamepad_right_shoulder_switch) return FeatureShortcutStore.GAMEPAD_RIGHT_SHOULDER;
        if (labelRes == R.string.gamepad_back_switch) return FeatureShortcutStore.GAMEPAD_BACK;
        if (labelRes == R.string.sensitivity_switch_label) return FeatureShortcutStore.SENSITIVITY;
        if (labelRes == R.string.custom_mapping_switch_label) return FeatureShortcutStore.CUSTOM_MAPPING;
        if (labelRes == R.string.click_multiplier_switch_label) return FeatureShortcutStore.CLICK_MULTIPLIER;
        if (labelRes == R.string.simultaneous_click_switch_label) return FeatureShortcutStore.SIMULTANEOUS_CLICK;
        if (labelRes == R.string.force_hold_switch_label) return FeatureShortcutStore.FORCE_HOLD;
        if (labelRes == R.string.hide_display_hotkey_switch_label) return FeatureShortcutStore.HIDE_DISPLAY_HOTKEY;
        if (labelRes == R.string.dps_switch_label) return FeatureShortcutStore.DPS;
        if (labelRes == R.string.font_switch_label) return FeatureShortcutStore.FONT;
        if (labelRes == R.string.global_html_switch_label) return FeatureShortcutStore.GLOBAL_HTML;
        if (labelRes == R.string.live2d_switch_label) return FeatureShortcutStore.LIVE2D;
        if (labelRes == R.string.drag_switch_label) return FeatureShortcutStore.DRAG;
        if (labelRes == R.string.auto_hide_label) return FeatureShortcutStore.AUTO_HIDE;
        return null;
    }

    private boolean featureShortcutCaptureActive() {
        return featureShortcutCaptureId != null && !featureShortcutCaptureId.isEmpty();
    }

    private boolean hasOtherBindingCapture() {
        return customMappingCaptureStep != 0 || clickMultiplierCaptureStep != 0
                || simultaneousClickCaptureStep != 0 || forceHoldCaptureStep != 0
                || gamepadCustomSwapCaptureStep != 0 || gamepadMappingCaptureArmed
                || hideDisplayHotkeyCaptureArmed || keyboardCatExpressionHotkeyCaptureArmed
                || physicsHotkeyCaptureArmed || keyboardCatFunctionBindingCaptureArmed;
    }

    private void beginFeatureShortcutCapture(String featureId) {
        if (featureId == null || featureId.isEmpty()) return;
        if (featureShortcutCaptureId.equals(featureId)) {
            cancelFeatureShortcutCapture(true);
            return;
        }
        if (hasOtherBindingCapture() || SuperCustomDisplayActivity.isInputCaptureActive()) {
            Toast.makeText(this, R.string.gamepad_mapping_capture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        cancelFeatureShortcutCapture(false);
        featureShortcutCaptureId = featureId;
        featureShortcutCaptureButton = featureShortcutButtons.get(featureId);
        if (featureShortcutCaptureButton != null) {
            featureShortcutCaptureButton.setText(R.string.feature_shortcut_recording);
        }
        mainHandler.removeCallbacks(featureShortcutCaptureTimeout);
        mainHandler.postDelayed(featureShortcutCaptureTimeout, 5000L);
        AxonInputAccessibilityService.refreshActiveService();
    }

    private void captureFeatureShortcut(int inputCode) {
        String featureId = featureShortcutCaptureId;
        if (featureId == null || featureId.isEmpty() || !InputBinding.isValid(inputCode)) return;
        if (featureShortcutConflicts(featureId, inputCode)) {
            Toast.makeText(this, R.string.feature_shortcut_conflict, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!FeatureShortcutStore.setBinding(this, featureId, inputCode)) {
            Toast.makeText(this, R.string.feature_shortcut_conflict, Toast.LENGTH_SHORT).show();
            return;
        }
        String label = InputBinding.label(inputCode);
        cancelFeatureShortcutCapture(false);
        refreshFeatureShortcutButton(featureId);
        ensureAccessibility();
        Toast.makeText(this, getString(R.string.feature_shortcut_saved, label), Toast.LENGTH_SHORT).show();
    }

    private boolean featureShortcutConflicts(String featureId, int inputCode) {
        if (FeatureShortcutStore.conflicts(this, featureId, inputCode)) return true;
        if (OverlayState.isForceHoldEnabled(this)
                && inputCode == OverlayState.getForceHoldTriggerKeyCode(this)) return true;
        if (OverlayState.isHideDisplayHotkeyEnabled(this)
                && inputCode == OverlayState.getHideDisplayHotkeyInputCode(this)) return true;
        if (inputCode == OverlayState.getKeyboardCatExpressionHotkeyKeyCode(this)) return true;
        if (gamepadMappingUsesSource(inputCode) || customMappingUsesTrigger(inputCode)
                || clickMultiplierUsesInput(inputCode) || simultaneousClickUsesSource(inputCode)) return true;
        if (physicsHotkeyConflicts(inputCode, null, null) || keyboardCatFunctionHotkeyConflicts(inputCode)) return true;
        return FloatingMediaStore.hotkeyConflicts(this, null, inputCode);
    }

    private void cancelFeatureShortcutCapture(boolean timedOut) {
        if (!featureShortcutCaptureActive()) return;
        String featureId = featureShortcutCaptureId;
        mainHandler.removeCallbacks(featureShortcutCaptureTimeout);
        featureShortcutCaptureId = "";
        featureShortcutCaptureButton = null;
        refreshFeatureShortcutButton(featureId);
        AxonInputAccessibilityService.refreshActiveService();
        if (timedOut && activityResumed) {
            Toast.makeText(this, R.string.feature_shortcut_capture_cancelled, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshFeatureShortcutButton(String featureId) {
        Button button = featureShortcutButtons.get(featureId);
        if (button == null) return;
        if (featureShortcutCaptureId.equals(featureId)) {
            button.setText(R.string.feature_shortcut_recording);
            return;
        }
        int inputCode = FeatureShortcutStore.getBinding(this, featureId);
        button.setText(inputCode >= 0
                ? getString(R.string.feature_shortcut_bound, InputBinding.label(inputCode))
                : getString(R.string.feature_shortcut_unbound));
    }

    private void refreshAllFeatureShortcutButtons() {
        for (String featureId : featureShortcutButtons.keySet()) refreshFeatureShortcutButton(featureId);
    }

    private void applyFeatureShortcutSwitchState(String featureId, boolean enabled) {
        Switch toggle = null;
        if (FeatureShortcutStore.REGULAR_DISPLAY.equals(featureId)) toggle = displaySwitch;
        else if (FeatureShortcutStore.FULL_KEYBOARD.equals(featureId)) toggle = inputFullKeyboardSwitch;
        else if (FeatureShortcutStore.MOUSE_DISPLAY.equals(featureId)) toggle = mouseSwitch;
        else if (FeatureShortcutStore.KEYBOARD_CAT.equals(featureId)) toggle = keyboardCatSwitch;
        else if (FeatureShortcutStore.KEY_PROMPT.equals(featureId)) toggle = keyPromptSwitch;
        else if (FeatureShortcutStore.MOUSE_TRAJECTORY.equals(featureId)) toggle = mouseTrajectorySwitch;
        else if (FeatureShortcutStore.CUSTOM_DISPLAY.equals(featureId)) toggle = customDisplaySwitch;
        else if (FeatureShortcutStore.SUPER_CUSTOM.equals(featureId)) toggle = superCustomDisplaySwitch;
        else if (FeatureShortcutStore.GAMEPAD_LEFT_STICK.equals(featureId)) toggle = gamepadLeftStickSwitch;
        else if (FeatureShortcutStore.GAMEPAD_RIGHT_STICK.equals(featureId)) toggle = gamepadRightStickSwitch;
        else if (FeatureShortcutStore.GAMEPAD_FACE.equals(featureId)) toggle = gamepadFaceSwitch;
        else if (FeatureShortcutStore.GAMEPAD_DPAD.equals(featureId)) toggle = gamepadDpadSwitch;
        else if (FeatureShortcutStore.GAMEPAD_LEFT_SHOULDER.equals(featureId)) toggle = gamepadLeftShoulderSwitch;
        else if (FeatureShortcutStore.GAMEPAD_RIGHT_SHOULDER.equals(featureId)) toggle = gamepadRightShoulderSwitch;
        else if (FeatureShortcutStore.GAMEPAD_BACK.equals(featureId)) toggle = gamepadBackSwitch;
        else if (FeatureShortcutStore.SENSITIVITY.equals(featureId)) toggle = sensitivitySwitch;
        else if (FeatureShortcutStore.CUSTOM_MAPPING.equals(featureId)) toggle = customMappingSwitch;
        else if (FeatureShortcutStore.CLICK_MULTIPLIER.equals(featureId)) toggle = clickMultiplierSwitch;
        else if (FeatureShortcutStore.SIMULTANEOUS_CLICK.equals(featureId)) toggle = simultaneousClickSwitch;
        else if (FeatureShortcutStore.FORCE_HOLD.equals(featureId)) toggle = forceHoldSwitch;
        else if (FeatureShortcutStore.HIDE_DISPLAY_HOTKEY.equals(featureId)) toggle = hideDisplayHotkeySwitch;
        else if (FeatureShortcutStore.DPS.equals(featureId)) toggle = dpsSwitch;
        else if (FeatureShortcutStore.FONT.equals(featureId)) toggle = fontSwitch;
        else if (FeatureShortcutStore.GLOBAL_HTML.equals(featureId)) toggle = globalHtmlSwitch;
        else if (FeatureShortcutStore.LIVE2D.equals(featureId)) toggle = live2dSwitch;
        else if (FeatureShortcutStore.DRAG.equals(featureId)) toggle = dragSwitch;
        else if (FeatureShortcutStore.AUTO_HIDE.equals(featureId)) toggle = autoHideSwitch;
        if (toggle == null) return;
        boolean previous = internalChange;
        internalChange = true;
        try {
            toggle.setChecked(enabled);
        } finally {
            internalChange = previous;
        }
        if (FeatureShortcutStore.DPS.equals(featureId)) updateDpsTargetUi();
        if (FeatureShortcutStore.HIDE_DISPLAY_HOTKEY.equals(featureId)) refreshHideDisplayHotkeyUi();
        if (FeatureShortcutStore.LIVE2D.equals(featureId)) syncInAppLive2D();
    }

    private LinearLayout createFeatureCopy(CharSequence featureText, int descriptionRes) {
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.TOP);
        copy.setPadding(0, dp(2), dp(12), dp(2));
        copy.setMinimumHeight(dp(50));

        TextView title = new TextView(this);
        title.setText(featureText == null ? "" : featureText);
        title.setTextColor(UiPalette.textPrimary(this));
        title.setTextSize(14.5f);
        title.setTypeface(AppTypeface.heavy(this));
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        // 云端 Heavy 字体的 ascent/descent 比系统默认字更高，不能再锁死 21dp。
        // 使用 WRAP_CONTENT + 最小高度，让字体 metrics 决定真实基线空间，避免上下裁字。
        title.setIncludeFontPadding(true);
        title.setMinHeight(dp(24));
        copy.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView description = new TextView(this);
        description.setText(descriptionRes != 0 ? getString(descriptionRes) : getString(R.string.feature_desc_default));
        description.setTextColor(UiPalette.textTertiary(this));
        description.setTextSize(11f);
        description.setSingleLine(true);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        description.setIncludeFontPadding(true);
        description.setMinHeight(dp(18));
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = 0;
        copy.addView(description, descParams);
        return copy;
    }

    private int featureDescriptionRes(Switch primary) {
        if (primary == null || !(primary.getTag() instanceof Integer)) return R.string.feature_desc_default;
        int labelRes = (Integer) primary.getTag();
        if (labelRes == R.string.switch_label) return R.string.feature_desc_regular_display;
        if (labelRes == R.string.input_full_keyboard_switch_label) return R.string.feature_desc_full_keyboard;
        if (labelRes == R.string.mouse_switch_label) return R.string.feature_desc_mouse;
        if (labelRes == R.string.keyboard_cat_switch_label) return R.string.feature_desc_keyboard_cat;
        if (labelRes == R.string.key_prompt_switch_label) return R.string.feature_desc_key_prompt;
        if (labelRes == R.string.mouse_trajectory_switch_label) return R.string.feature_desc_mouse_trajectory;
        if (labelRes == R.string.custom_switch_label) return R.string.feature_desc_custom_keys;
        if (labelRes == R.string.gamepad_left_stick_switch) return R.string.feature_desc_left_stick;
        if (labelRes == R.string.gamepad_right_stick_switch) return R.string.feature_desc_right_stick;
        if (labelRes == R.string.gamepad_face_switch) return R.string.feature_desc_gamepad_face;
        if (labelRes == R.string.gamepad_dpad_switch) return R.string.feature_desc_gamepad_dpad;
        if (labelRes == R.string.gamepad_left_shoulder_switch) return R.string.feature_desc_left_shoulder;
        if (labelRes == R.string.gamepad_right_shoulder_switch) return R.string.feature_desc_right_shoulder;
        if (labelRes == R.string.gamepad_back_switch) return R.string.feature_desc_gamepad_back;
        if (labelRes == R.string.sensitivity_switch_label) return R.string.feature_desc_sensitivity;
        if (labelRes == R.string.custom_mapping_switch_label) return R.string.feature_desc_custom_mapping;
        if (labelRes == R.string.click_multiplier_switch_label) return R.string.feature_desc_click_multiplier;
        if (labelRes == R.string.simultaneous_click_switch_label) return R.string.feature_desc_simultaneous_click;
        if (labelRes == R.string.force_hold_switch_label) return R.string.feature_desc_force_hold;
        if (labelRes == R.string.hide_display_hotkey_switch_label) return R.string.feature_desc_hide_hotkey;
        if (labelRes == R.string.dps_switch_label) return R.string.feature_desc_cps;
        if (labelRes == R.string.font_switch_label) return R.string.feature_desc_font;
        if (labelRes == R.string.global_html_switch_label) return R.string.feature_desc_global_html;
        if (labelRes == R.string.live2d_switch_label) return R.string.feature_desc_live2d;
        if (labelRes == R.string.drag_switch_label) return R.string.feature_desc_drag;
        if (labelRes == R.string.auto_hide_label) return R.string.feature_desc_auto_hide;
        return R.string.feature_desc_default;
    }

    /**
     * 详情区域只统一视觉，不改变控件层级和状态逻辑。普通设置文字使用同一灰阶，
     * MotionSwitch 的标签也跟随相同层级；文本自身不再带独立背景。
     */
    private void normalizeDetailsVisuals(ViewGroup root) {
        if (root == null) return;
        int secondary = UiPalette.textSecondary(this);
        int disabled = UiChrome.withAlpha(secondary, UiChrome.DISABLED_ALPHA);
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof Switch) {
                Switch sw = (Switch) child;
                sw.setTextColor(new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_enabled},
                                new int[]{-android.R.attr.state_enabled}},
                        new int[]{secondary, disabled}));
            } else if (child instanceof TextView
                    && !(child instanceof Button)
                    && !(child instanceof EditText)) {
                TextView text = (TextView) child;
                child.setBackground(null);
                int current = text.getCurrentTextColor();
                int primary = UiPalette.textPrimary(this);
                int tertiary = UiPalette.textTertiary(this);
                if (current == primary || current == secondary || current == tertiary
                        || current == disabled) {
                    text.setTextColor(text.isEnabled() ? secondary : tertiary);
                }
                AppTypeface.applyIfSelected(text);
            }
            if (child instanceof ViewGroup) normalizeDetailsVisuals((ViewGroup) child);
        }
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
        AppTypeface.applyIfSelected(text);
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
        if (seekBar instanceof LagSeekBar) {
            LagSeekBar lag = (LagSeekBar) seekBar;
            if (seekBar.getMax() == 100) lag.setValueDisplaySpec(0, 1, "%");
            else lag.setValueDisplaySpec(0, 1, "");
        }
    }

    /**
     * 全局搜索只建立对现有 View 的轻量索引。索引不复制功能状态，也不改变分页树；
     * 点击结果时只切换到原页面并滚动到原控件，因此不会产生第二套配置入口。
     */
    private List<GlobalSettingsSearchBar.Item> searchGlobalSettings(String rawQuery) {
        String query = normalizeSearchText(rawQuery);
        if (query.isEmpty() || sectionPageViews == null) return java.util.Collections.emptyList();
        ensureGlobalSearchIndex();

        ArrayList<RankedSearchTarget> matches = new ArrayList<>();
        for (GlobalSearchTarget target : globalSearchIndex) {
            String liveSearchText = currentSearchText(target);
            int score = searchMatchScore(liveSearchText, query);
            if (score < 0) continue;
            matches.add(new RankedSearchTarget(target, score));
        }
        matches.sort((left, right) -> {
            int byScore = Integer.compare(left.score, right.score);
            if (byScore != 0) return byScore;
            String leftTitle = currentSearchTitle(left.target);
            String rightTitle = currentSearchTitle(right.target);
            int byLength = Integer.compare(leftTitle.length(), rightTitle.length());
            if (byLength != 0) return byLength;
            return leftTitle.compareToIgnoreCase(rightTitle);
        });

        int count = Math.min(24, matches.size());
        ArrayList<GlobalSettingsSearchBar.Item> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            GlobalSearchTarget target = matches.get(i).target;
            result.add(new GlobalSettingsSearchBar.Item(
                    currentSearchTitle(target), target.subtitle, target));
        }
        return result;
    }

    private void invalidateGlobalSearchIndex() {
        globalSearchIndexReady = false;
        globalSearchIndex.clear();
    }

    private String currentSearchTitle(GlobalSearchTarget target) {
        if (target != null && target.view instanceof TextView) {
            CharSequence raw = ((TextView) target.view).getText();
            String live = compactSearchLabel(raw == null ? "" : raw.toString());
            if (!live.isEmpty()) return live;
        }
        return target == null ? "" : target.title;
    }

    private String currentSearchText(GlobalSearchTarget target) {
        if (target == null) return "";
        String liveTitle = currentSearchTitle(target);
        return normalizeSearchText(liveTitle + " " + target.staticSearchContext);
    }

    private void ensureGlobalSearchIndex() {
        if (globalSearchIndexReady || sectionPageViews == null) return;
        globalSearchIndexReady = true;
        globalSearchIndex.clear();

        String detailsAction = getString(R.string.details_action);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int pageIndex = 0; pageIndex < sectionPageViews.length; pageIndex++) {
            ScrollView page = sectionPageViews[pageIndex];
            if (page == null || page.getChildCount() == 0) continue;
            View content = page.getChildAt(0);
            if (!(content instanceof ViewGroup)) continue;
            ViewGroup pageContent = (ViewGroup) content;
            String sectionTitle = findSectionTitle(pageContent);

            for (int childIndex = 0; childIndex < pageContent.getChildCount(); childIndex++) {
                View anchor = pageContent.getChildAt(childIndex);
                // 隐藏的实验功能入口不进入搜索；已折叠的调试详情仍属于可搜索内容。
                if (anchor == null || anchor.getVisibility() == View.GONE) continue;
                String contextTitle = findPrimarySearchText(anchor);
                collectGlobalSearchTargets(anchor, anchor, pageIndex, sectionTitle,
                        contextTitle, detailsAction, seen);
            }
        }
    }

    private void collectGlobalSearchTargets(
            View view,
            View anchor,
            int pageIndex,
            String sectionTitle,
            String contextTitle,
            String detailsAction,
            Set<String> seen) {
        if (view instanceof TextView) {
            CharSequence raw = ((TextView) view).getText();
            String title = compactSearchLabel(raw == null ? "" : raw.toString());
            if (isUsefulSearchLabel(title, detailsAction)) {
                String key = pageIndex + ":" + System.identityHashCode(anchor) + ":"
                        + normalizeSearchText(title);
                if (seen.add(key)) {
                    View details = findContainingDetails(view);
                    String subtitle = sectionTitle;
                    if (!contextTitle.isEmpty()
                            && !normalizeSearchText(contextTitle).equals(normalizeSearchText(title))
                            && !normalizeSearchText(contextTitle).equals(normalizeSearchText(sectionTitle))) {
                        subtitle = sectionTitle.isEmpty()
                                ? contextTitle
                                : sectionTitle + " · " + contextTitle;
                    }
                    String staticSearchContext = normalizeSearchText(subtitle + " " + contextTitle);
                    globalSearchIndex.add(new GlobalSearchTarget(
                            pageIndex, view, anchor, details, title, subtitle, staticSearchContext));
                }
            }
        }

        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectGlobalSearchTargets(group.getChildAt(i), anchor, pageIndex, sectionTitle,
                    contextTitle, detailsAction, seen);
        }
    }

    private String findSectionTitle(ViewGroup pageContent) {
        for (int i = 0; i < pageContent.getChildCount(); i++) {
            View child = pageContent.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            String text = compactSearchLabel(((TextView) child).getText().toString());
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private String findPrimarySearchText(View anchor) {
        String switchText = findTextByType(anchor, Switch.class);
        if (!switchText.isEmpty()) return switchText;
        String buttonText = findTextByType(anchor, Button.class);
        if (!buttonText.isEmpty()) return buttonText;
        if (anchor instanceof TextView) {
            return compactSearchLabel(((TextView) anchor).getText().toString());
        }
        return "";
    }

    private String findTextByType(View view, Class<? extends TextView> type) {
        if (type.isInstance(view)) {
            String text = compactSearchLabel(((TextView) view).getText().toString());
            if (!text.isEmpty()) return text;
        }
        if (!(view instanceof ViewGroup)) return "";
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            String text = findTextByType(group.getChildAt(i), type);
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private View findContainingDetails(View target) {
        View current = target;
        while (current != null) {
            if (detailDisclosures.containsKey(current)) return current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private boolean isUsefulSearchLabel(String title, String detailsAction) {
        if (title.isEmpty() || title.equals(detailsAction) || title.length() > 48) return false;
        boolean meaningful = false;
        for (int i = 0; i < title.length(); i++) {
            if (Character.isLetterOrDigit(title.charAt(i))) {
                meaningful = true;
                break;
            }
        }
        return meaningful;
    }

    private String compactSearchLabel(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private String normalizeSearchText(String value) {
        return compactSearchLabel(value).toLowerCase(java.util.Locale.ROOT);
    }

    private int searchMatchScore(String haystack, String query) {
        if (haystack.equals(query)) return 0;
        if (haystack.startsWith(query)) return 1;
        int index = haystack.indexOf(query);
        if (index >= 0) return 2 + Math.min(index, 40);

        // 多词搜索要求每个词都能在“控件名 + 所属分页/功能”中命中。
        String[] words = query.split(" ");
        int score = 60;
        for (String word : words) {
            if (word.isEmpty()) continue;
            int wordIndex = haystack.indexOf(word);
            if (wordIndex < 0) return -1;
            score += Math.min(wordIndex, 20);
        }
        return score;
    }

    private void navigateToGlobalSearchItem(GlobalSettingsSearchBar.Item item) {
        if (item == null || !(item.token instanceof GlobalSearchTarget)) return;
        GlobalSearchTarget target = (GlobalSearchTarget) item.token;
        if (sectionPageViews == null
                || target.pageIndex < 0
                || target.pageIndex >= sectionPageViews.length) return;

        selectSectionPage(target.pageIndex, true);
        if (target.details != null && target.details.getVisibility() != View.VISIBLE) {
            // 搜索属于高频导航动作：命中调试项时直接展开所属详情，避免再叠加 420ms 的详情动画。
            setDetailsExpanded(target.details, true, false);
        }

        ScrollView page = sectionPageViews[target.pageIndex];
        // 先把所属卡片带入视野，下一帧再精确对齐目标，避免分页切换时发生坐标跳变。
        scrollSearchTargetIntoView(page, target.anchor, false);
        mainHandler.postDelayed(() -> {
            if (isFinishing() || sectionPageViews == null
                    || target.pageIndex >= sectionPageViews.length) return;
            scrollSearchTargetIntoView(sectionPageViews[target.pageIndex], target.view, false);
            highlightSearchFeature(target.anchor);
        }, 96L);
    }

    private void scrollSearchTargetIntoView(ScrollView page, View target, boolean emphasize) {
        if (page == null || target == null || page.getChildCount() == 0) return;
        View content = page.getChildAt(0);
        if (!(content instanceof ViewGroup)) return;
        int y = descendantTopWithin(target, content) - dp(16);
        page.smoothScrollTo(0, Math.max(0, y));
        if (!emphasize) return;
        View pulse = target.getVisibility() == View.VISIBLE ? target : findSearchAnchor(target, content);
        if (pulse != null) highlightSearchFeature(pulse);
    }

    /** Search navigation feedback: tint the complete feature card blue, then fade cleanly. */
    private void highlightSearchFeature(View featureCard) {
        if (featureCard == null || !featureCard.isShown()) return;
        clearSearchHighlight();

        GradientDrawable highlight = new GradientDrawable();
        int blue = UiPalette.controlAccent(this);
        highlight.setColor(Color.argb(34, Color.red(blue), Color.green(blue), Color.blue(blue)));
        highlight.setStroke(dp(1), Color.argb(118, Color.red(blue), Color.green(blue), Color.blue(blue)));
        highlight.setCornerRadius(dp(12));
        highlight.setBounds(0, 0, featureCard.getWidth(), featureCard.getHeight());
        highlight.setAlpha(0);
        featureCard.getOverlay().add(highlight);
        searchHighlightView = featureCard;
        searchHighlightDrawable = highlight;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(820L);
        animator.setInterpolator(UiMotion.easeOut());
        animator.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            // 120ms 内迅速建立定位反馈，之后平滑淡出；不移动、不缩放功能卡。
            float alpha = p < 0.15f ? p / 0.15f : Math.max(0f, 1f - (p - 0.15f) / 0.85f);
            highlight.setAlpha(Math.round(255f * alpha));
            if (highlight.getBounds().width() != featureCard.getWidth()
                    || highlight.getBounds().height() != featureCard.getHeight()) {
                highlight.setBounds(0, 0, featureCard.getWidth(), featureCard.getHeight());
            }
            featureCard.invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (searchHighlightAnimator == animation) clearSearchHighlight();
            }
            @Override public void onAnimationCancel(android.animation.Animator animation) {
                if (searchHighlightAnimator == animation) clearSearchHighlight();
            }
        });
        searchHighlightAnimator = animator;
        animator.start();
    }

    private void clearSearchHighlight() {
        ValueAnimator animator = searchHighlightAnimator;
        searchHighlightAnimator = null;
        if (animator != null) animator.cancel();
        View view = searchHighlightView;
        Drawable drawable = searchHighlightDrawable;
        searchHighlightView = null;
        searchHighlightDrawable = null;
        if (view != null && drawable != null) {
            try { view.getOverlay().remove(drawable); } catch (Throwable ignored) {}
        }
    }

    private int descendantTopWithin(View target, View ancestor) {
        int top = 0;
        View current = target;
        while (current != null && current != ancestor) {
            top += current.getTop();
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return top;
    }

    private View findSearchAnchor(View target, View ancestor) {
        View current = target;
        View last = target;
        while (current != null && current != ancestor) {
            last = current;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return last;
    }

    private static final class GlobalSearchTarget {
        final int pageIndex;
        final View view;
        final View anchor;
        final View details;
        final String title;
        final String subtitle;
        final String staticSearchContext;

        GlobalSearchTarget(int pageIndex, View view, View anchor, View details,
                           String title, String subtitle, String staticSearchContext) {
            this.pageIndex = pageIndex;
            this.view = view;
            this.anchor = anchor;
            this.details = details;
            this.title = title;
            this.subtitle = subtitle;
            this.staticSearchContext = staticSearchContext;
        }
    }

    private static final class RankedSearchTarget {
        final GlobalSearchTarget target;
        final int score;

        RankedSearchTarget(GlobalSearchTarget target, int score) {
            this.target = target;
            this.score = score;
        }
    }

    private SectionNavigationBar createSectionNavigation(int[] labelResIds, int[] iconTypes, int pageCount) {
        SectionNavigationBar nav = new SectionNavigationBar();

        int count = Math.min(labelResIds.length, pageCount);
        SectionNavigationItem[] items = new SectionNavigationItem[count];
        for (int i = 0; i < count; i++) {
            final int pageIndex = i;
            int iconType = iconTypes != null && i < iconTypes.length ? iconTypes[i] : i;
            SectionNavigationItem item = new SectionNavigationItem(labelResIds[i], iconType);
            items[i] = item;
            item.setFocusable(true);
            item.setDefaultFocusHighlightEnabled(false);
            item.setOnClickListener(v -> selectSectionPage(pageIndex, true));
            UiMotion.bindPressFeedback(item);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
            setMinimumHeight(dp(68));
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
            itemRow.setPadding(dp(8), dp(4), dp(8), dp(4));
            FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
            addView(itemRow, rowParams);
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
            // 选中背景只包住真实的“图标 + 当前字体标签”视觉内容，不再写死 44dp。
            // 触摸目标仍保持至少 60dp；字体切换/系统字号变化时，背景高度跟随 measured height 自适应。
            int availableHeight = Math.max(1, target.getHeight() - inset * 2);
            int desiredHeight = target instanceof SectionNavigationItem
                    ? ((SectionNavigationItem) target).getSelectionVisualHeight()
                    : availableHeight;
            int height = Math.min(availableHeight, Math.max(dp(34), desiredHeight));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) selectionIndicator.getLayoutParams();
            if (lp.width != width || lp.height != height) {
                lp.width = width;
                lp.height = height;
                selectionIndicator.setLayoutParams(lp);
            }

            float targetX = itemRow.getX() + target.getX() + inset;
            float targetY = itemRow.getY() + target.getY()
                    + Math.max(0f, (target.getHeight() - height) * 0.5f);
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
            setPadding(dp(3), dp(3), dp(3), dp(3));
            // 60dp 只作为触摸目标下限；选中背景高度单独按图标 + 字体真实高度计算。
            setMinimumHeight(dp(60));

            iconView = new ImageView(MainActivity.this);
            // 24dp 安全盒承载 20dp 图标，给 2px stroke 留出左右抗锯齿空间，避免边缘图标被裁。
            iconView.setScaleType(ImageView.ScaleType.CENTER);
            iconView.setPadding(dp(2), dp(2), dp(2), dp(2));
            iconView.setCropToPadding(false);
            addView(iconView, new LinearLayout.LayoutParams(dp(24), dp(24)));

            labelView = new TextView(MainActivity.this);
            labelView.setText(labelResId);
            labelView.setTextSize(9.5f);
            labelView.setTypeface(AppTypeface.heavy(MainActivity.this));
            labelView.setSingleLine(true);
            labelView.setEllipsize(TextUtils.TruncateAt.END);
            labelView.setGravity(Gravity.CENTER);
            // 云端 Heavy 字体的 descent 比系统字体更深，固定 16dp 会裁掉底部笔画。
            // 使用字体 metrics 自适应高度并保留 font padding，避免任何字体被硬裁。
            labelView.setIncludeFontPadding(true);
            // 不再额外写死 20dp 文本盒高度。WRAP_CONTENT + font padding 会按当前字体真实 metrics 测量，
            // 既避免 Heavy 字体裁切，也让底栏选中背景能跟随实际内容高度。
            labelView.setMinHeight(0);
            labelView.setMinimumHeight(0);
            labelView.setPadding(0, 0, 0, 0);
            AppTypeface.applyIfSelected(labelView);
            labelView.setAlpha(0f);
            labelView.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 轻微收紧图标与标签之间的视觉距离，不通过 translation 制造假布局。
            labelParams.topMargin = -dp(1);
            addView(labelView, labelParams);
        }

        int getSelectionVisualHeight() {
            ViewGroup.LayoutParams raw = labelView.getLayoutParams();
            int labelTopMargin = raw instanceof LinearLayout.LayoutParams
                    ? ((LinearLayout.LayoutParams) raw).topMargin : 0;
            int iconHeight = Math.max(iconView.getMeasuredHeight(), dp(24));
            int labelHeight = Math.max(0, labelView.getMeasuredHeight());
            // 背景只比实际内容上下各多约 2dp；系统字体放大或 Heavy 字体更高时自然增高。
            return Math.max(dp(34), iconHeight + labelTopMargin + labelHeight + dp(4));
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
            // 分页内容沿用 24/24 对称留白；原 18/30 会让所有功能卡整体向左偏约 6dp。
            content.setPadding(dp(24), dp(8), dp(24), dp(52));
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
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            featureShortcutPointerGestureConsumed = featureShortcutCaptureActive()
                    && InputBinding.isPhysicalMouseEvent(event);
            pageSwipeDownX = event.getRawX();
            pageSwipeDownY = event.getRawY();
            pageSwipeClaimed = false;
            searchDismissGestureConsumed = globalSearchBar != null
                    && globalSearchBar.collapseFromOutsideTouch(pageSwipeDownX, pageSwipeDownY);
            if (searchDismissGestureConsumed) {
                pageSwipeBlocked = true;
                return true;
            }
            captureBindableMousePress(event);
            if (featureShortcutPointerGestureConsumed) {
                pageSwipeBlocked = true;
                return true;
            }
            pageSwipeBlocked = shouldBlockPageSwipe(pageSwipeDownX, pageSwipeDownY);
            return super.dispatchTouchEvent(event);
        }
        if (searchDismissGestureConsumed) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                searchDismissGestureConsumed = false;
                pageSwipeBlocked = false;
                pageSwipeClaimed = false;
            }
            return true;
        }
        if (featureShortcutPointerGestureConsumed) {
            captureBindableMousePress(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                featureShortcutPointerGestureConsumed = false;
                pageSwipeBlocked = false;
                pageSwipeClaimed = false;
            }
            return true;
        }
        captureBindableMousePress(event);

        if (action == MotionEvent.ACTION_MOVE && !pageSwipeBlocked && !pageSwipeClaimed) {
            float deltaX = event.getRawX() - pageSwipeDownX;
            float deltaY = event.getRawY() - pageSwipeDownY;
            float absX = Math.abs(deltaX);
            float absY = Math.abs(deltaY);
            if (sectionPageViews != null && absX >= dp(64) && absX > absY * 1.35f) {
                // 页级横滑一旦成立，先给当前子控件发送 CANCEL，再由分页手势接管后续事件。
                // 这样 Button/功能标题不会先收到 ACTION_UP 触发点击，然后页面又切走。
                cancelCurrentChildGesture(event);
                pageSwipeClaimed = true;
                return true;
            }
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            boolean claimed = pageSwipeClaimed;
            pageSwipeBlocked = false;
            pageSwipeClaimed = false;
            if (claimed) return true;
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

            if (switchPage) {
                if (!pageSwipeClaimed) cancelCurrentChildGesture(event);
                int nextPage = selectedSectionPage + (deltaX < 0f ? 1 : -1);
                if (nextPage >= 0 && nextPage < sectionPageViews.length) {
                    selectSectionPage(nextPage, true);
                }
                pageSwipeBlocked = false;
                pageSwipeClaimed = false;
                return true;
            }

            boolean handled = pageSwipeClaimed || super.dispatchTouchEvent(event);
            pageSwipeBlocked = false;
            pageSwipeClaimed = false;
            return handled;
        }

        if (pageSwipeClaimed) return true;
        return super.dispatchTouchEvent(event);
    }

    private void cancelCurrentChildGesture(MotionEvent source) {
        MotionEvent cancel = MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            super.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }

    private boolean shouldBlockPageSwipe(float rawX, float rawY) {
        if (isPointInsideView(sectionNavigationView, rawX, rawY)) return true;
        if (isPointInsideView(globalSearchBar, rawX, rawY)) return true;

        View decor = getWindow() != null ? getWindow().getDecorView() : null;
        View touched = findDeepestViewAt(decor, rawX, rawY);
        View current = touched;
        while (current != null) {
            if (current instanceof SeekBar
                    || current instanceof Switch
                    || current instanceof Spinner
                    || current instanceof EditText
                    || current instanceof Button
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

    /** 密码验证页使用独立深色系统栏，避免浅色主题的深色图标落在粒子背景上。 */
    private void applyEntryGateSystemBars() {
        getWindow().setStatusBarColor(Color.rgb(9, 10, 15));
        getWindow().setNavigationBarColor(Color.rgb(9, 10, 15));
        getWindow().getDecorView().setSystemUiVisibility(0);
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
        ((LagSeekBar) seekBar).setValueDisplaySpec(SIZE_MIN, 1, "%");
        return seekBar;
    }

    private SeekBar createKeySpacingSeekBar() {
        SeekBar seekBar = new LagSeekBar(this);
        seekBar.setMax(KEY_SPACING_MAX);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        ((LagSeekBar) seekBar).setValueDisplaySpec(0, 1, "");
        return seekBar;
    }

    private SeekBar createSensitivitySeekBar() {
        SeekBar seekBar = new LagSeekBar(this);
        seekBar.setMax(SENSITIVITY_SEEKBAR_MAX);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        ((LagSeekBar) seekBar).setDisplayValue(100, "%");
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
        setSliderLabelTitle(columnsLabel, R.string.columns_format);

        int mouseSensitivity = SensitivitySettingsStore.getMousePercent(this);
        if (mouseSensitivitySeekBar != null) {
            mouseSensitivitySeekBar.setProgress(sensitivityToProgress(mouseSensitivity));
            if (mouseSensitivitySeekBar instanceof LagSeekBar) ((LagSeekBar) mouseSensitivitySeekBar).setDisplayValue(mouseSensitivity, "%");
        }
        if (mouseSensitivityLabel != null) {
            setSliderLabelTitle(mouseSensitivityLabel, R.string.mouse_sensitivity_format);
        }
        int gamepadSensitivity = SensitivitySettingsStore.getGamepadPercent(this);
        if (gamepadSensitivitySeekBar != null) {
            gamepadSensitivitySeekBar.setProgress(sensitivityToProgress(gamepadSensitivity));
            if (gamepadSensitivitySeekBar instanceof LagSeekBar) ((LagSeekBar) gamepadSensitivitySeekBar).setDisplayValue(gamepadSensitivity, "%");
        }
        if (gamepadSensitivityLabel != null) {
            setSliderLabelTitle(gamepadSensitivityLabel, R.string.gamepad_sensitivity_format);
        }

        for (int i = 0; i < opacityControls.size(); i++) {
            int displayType = opacityControls.keyAt(i);
            OpacityControl control = opacityControls.valueAt(i);
            if (control == null) continue;
            int value = OverlayState.getDisplayOpacity(this, displayType);
            control.seekBar.setProgress(value);
            setSliderLabelTitle(control.label, R.string.display_opacity_format);
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
            if (control.textOpacityApplicable && control.textSeek != null && control.textLabel != null) {
                syncOpacitySeekBar(control.textSeek, control.textLabel,
                        R.string.key_text_opacity_format,
                        OverlayState.getKeyTextOpacity(this, displayType));
            }
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
            setSliderLabelTitle(control.label, isStickDisplay(displayType)
                    ? R.string.gamepad_stick_background_corner_strength_format
                    : R.string.key_corner_strength_format);
        }
    }

    /**
     * Slider 的实时数值统一由 LagSeekBar 右侧展示。上方标签只保留语义名称，
     * 避免同一个百分比/数值在标题和轨道右侧重复出现。
     */
    private void setSliderLabelTitle(TextView label, int formatRes) {
        if (label == null) return;
        String title = sliderTitleCache.get(formatRes);
        if (title == null) {
            String raw = formatRes == R.string.columns_format
                    ? getString(R.string.columns_label) : getString(formatRes);
            int marker = raw.indexOf('%');
            title = marker >= 0 ? raw.substring(0, marker).trim() : raw.trim();
            while (title.endsWith(":") || title.endsWith("：") || title.endsWith("·")) {
                title = title.substring(0, title.length() - 1).trim();
            }
            sliderTitleCache.put(formatRes, title);
        }
        CharSequence current = label.getText();
        if (current == null || !title.contentEquals(current)) label.setText(title);
    }

    private void syncBoundedSeekBar(SeekBar seekBar, TextView label, int formatRes,
                                    int value, int progressOffset) {
        if (seekBar != null) {
            seekBar.setProgress(Math.max(0, value - progressOffset));
        }
        setSliderLabelTitle(label, formatRes);
    }

    private void syncOpacitySeekBar(SeekBar seekBar, TextView label, int formatRes, int value) {
        if (seekBar != null) seekBar.setProgress(value);
        setSliderLabelTitle(label, formatRes);
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
                setSliderLabelTitle(label, formatRes);
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
                setSliderLabelTitle(label, formatRes);
                if (seekBar instanceof LagSeekBar) ((LagSeekBar) seekBar).setDisplayValue(value, "%");
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
