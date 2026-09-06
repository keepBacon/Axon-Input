package com.axon.input;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RadialGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * 独立的访问验证页。
 *
 * 它只覆盖设置 Activity 的视觉层，不接触输入监控、Overlay 或 Native 链路。验证期间在
 * Android 12+ 优先通过反射调用平台 RenderEffect，旧系统或不完整 android.jar 环境使用低分辨率复用快照模糊；状态指示器保持独立清晰。
 */
final class EntryPasswordGate extends FrameLayout {
    interface Listener {
        boolean isPasswordCorrect(String value);
        void onAuthorized();
        void onOpenPasswordSource();
    }

    private static final long VERIFY_DELAY_MS = 2000L;
    private static final long RESULT_HOLD_MS = 620L;
    private static final long EXIT_MS = 260L;

    private final Listener listener;
    private final FrameLayout visualLayer;
    private final LinearLayout content;
    private final EditText input;
    private final Button confirmButton;
    private final Button sourceButton;
    private final ImageView blurSnapshotView;
    private final View statusScrim;
    private final VerificationIndicator indicator;
    private Bitmap blurSnapshotBitmap;
    private int[] blurPixels;
    private int[] blurTemp;
    private boolean verifying;
    private boolean pendingCorrect;

    // 在构造函数完成 indicator 初始化后再绑定，避免 javac 的 final 字段确定赋值检查失败。
    private final Runnable finishVerification;
    private final Runnable finishAuthorizedRunnable;
    private final Runnable restoreAfterFailureRunnable;

    EntryPasswordGate(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.rgb(9, 10, 15));

        visualLayer = new FrameLayout(context);
        addView(visualLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ParticleBackgroundView particles = new ParticleBackgroundView(context);
        visualLayer.addView(particles, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(2), dp(20), dp(2), dp(20));

        TextView brand = new TextView(context);
        brand.setText(R.string.app_name);
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(24f);
        brand.setGravity(Gravity.CENTER);
        brand.setTypeface(AppTypeface.heavy(context));
        content.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText(R.string.password_title);
        title.setTextColor(Color.rgb(232, 232, 236));
        title.setTextSize(14f);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(8);
        content.addView(title, titleParams);

        TextView message = new TextView(context);
        message.setText(R.string.password_message);
        message.setTextColor(Color.rgb(142, 142, 151));
        message.setTextSize(12f);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(4);
        messageParams.bottomMargin = dp(22);
        content.addView(message, messageParams);

        input = new EditText(context);
        input.setHint(R.string.password_hint);
        input.setHintTextColor(Color.rgb(132, 132, 141));
        input.setTextColor(Color.WHITE);
        input.setTextSize(14f);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setMinHeight(dp(50));
        input.setBackground(gateControlBackground(false));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.addView(input, inputParams);

        confirmButton = new Button(context);
        confirmButton.setText(R.string.password_confirm);
        styleGateButton(confirmButton, true);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        confirmParams.topMargin = dp(12);
        content.addView(confirmButton, confirmParams);

        sourceButton = new Button(context);
        sourceButton.setText(R.string.password_get);
        styleGateButton(sourceButton, false);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceParams.topMargin = dp(8);
        content.addView(sourceButton, sourceParams);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                dp(360), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        contentParams.leftMargin = dp(24);
        contentParams.rightMargin = dp(24);
        visualLayer.addView(content, contentParams);

        // Android 8–11 的一次性软件模糊快照独立于实时视觉层，避免把位图模糊放进动画帧。
        blurSnapshotView = new ImageView(context);
        blurSnapshotView.setScaleType(ImageView.ScaleType.FIT_XY);
        blurSnapshotView.setVisibility(INVISIBLE);
        addView(blurSnapshotView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusScrim = new View(context);
        statusScrim.setBackgroundColor(Color.argb(68, 0, 0, 0));
        statusScrim.setAlpha(0f);
        statusScrim.setVisibility(INVISIBLE);
        addView(statusScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        indicator = new VerificationIndicator(context);
        indicator.setVisibility(INVISIBLE);
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                dp(74), dp(74), Gravity.CENTER);
        addView(indicator, indicatorParams);

        // indicator 已完成赋值后再创建 Runnable，保持 removeCallbacks/postDelayed 使用同一实例。
        finishVerification = this::finishVerificationNow;
        finishAuthorizedRunnable = this::finishAuthorized;
        restoreAfterFailureRunnable = this::restoreAfterFailure;

        confirmButton.setOnClickListener(v -> beginVerification());
        sourceButton.setOnClickListener(v -> {
            if (!verifying && listener != null) listener.onOpenPasswordSource();
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            beginVerification();
            return true;
        });
        UiMotion.bindPressFeedback(confirmButton);
        UiMotion.bindPressFeedback(sourceButton);

        post(() -> {
            if (!isAttachedToWindow() || verifying) return;
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    boolean isVerifying() {
        return verifying;
    }

    private void finishVerificationNow() {
        if (!verifying || !isAttachedToWindow()) return;
        indicator.setResult(pendingCorrect);
        if (pendingCorrect) {
            postDelayed(finishAuthorizedRunnable, 320L);
        } else {
            postDelayed(restoreAfterFailureRunnable, RESULT_HOLD_MS);
        }
    }

    private void beginVerification() {
        if (verifying) return;
        String value = input.getText() == null ? "" : input.getText().toString();
        pendingCorrect = listener != null && listener.isPasswordCorrect(value);
        verifying = true;

        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        setInputEnabled(false);
        applyVerificationBlur(true);

        statusScrim.setVisibility(VISIBLE);
        statusScrim.animate().cancel();
        statusScrim.animate().alpha(1f).setDuration(140L).setInterpolator(UiMotion.easeOut()).start();
        indicator.setVisibility(VISIBLE);
        indicator.startSpinner();

        removeCallbacks(finishVerification);
        postDelayed(finishVerification, VERIFY_DELAY_MS);
    }

    private void finishAuthorized() {
        if (!verifying || !pendingCorrect) return;
        setEnabled(false);
        animate().cancel();
        animate()
                .alpha(0f)
                .setDuration(EXIT_MS)
                .setInterpolator(UiMotion.easeOut())
                .withEndAction(() -> {
                    if (isAttachedToWindow() && listener != null) listener.onAuthorized();
                })
                .start();
    }

    private void restoreAfterFailure() {
        if (!verifying || pendingCorrect) return;
        verifying = false;
        indicator.stop();
        indicator.setVisibility(INVISIBLE);
        statusScrim.animate().cancel();
        statusScrim.animate()
                .alpha(0f)
                .setDuration(160L)
                .setInterpolator(UiMotion.easeOut())
                .withEndAction(() -> statusScrim.setVisibility(INVISIBLE))
                .start();
        applyVerificationBlur(false);
        setInputEnabled(true);
        input.setError(getResources().getString(R.string.password_wrong));
        input.selectAll();
        input.requestFocus();
    }

    private void setInputEnabled(boolean enabled) {
        input.setEnabled(enabled);
        confirmButton.setEnabled(enabled);
        sourceButton.setEnabled(enabled);
        content.setAlpha(enabled ? 1f : 0.92f);
    }

    private void applyVerificationBlur(boolean enabled) {
        // 不直接引用 API 31 的 RenderEffect / View#setRenderEffect。
        // Axon 的无 Gradle Termux 构建在部分设备上会遇到裁剪过的 android.jar，
        // 即使路径名是 android-36，也可能缺少这些编译期符号。反射可同时保留
        // Android 12+ 的硬件模糊，并让旧/不完整 SDK stub 正常编译。
        if (Build.VERSION.SDK_INT >= 31 && applyPlatformRenderEffect(enabled)) {
            blurSnapshotView.setVisibility(INVISIBLE);
            visualLayer.setVisibility(VISIBLE);
            visualLayer.setAlpha(1f);
            return;
        }

        if (enabled && captureSoftwareBlurSnapshot()) {
            visualLayer.setAlpha(1f);
            visualLayer.setVisibility(INVISIBLE);
            blurSnapshotView.setVisibility(VISIBLE);
        } else {
            blurSnapshotView.setVisibility(INVISIBLE);
            visualLayer.setVisibility(VISIBLE);
            visualLayer.setAlpha(1f);
        }
    }

    /**
     * 通过反射使用 Android 12+ RenderEffect，避免对 API 31 stub 形成编译期依赖。
     * 这是低频验证路径，反射开销远小于持续动画成本；失败时直接回退软件模糊。
     */
    private boolean applyPlatformRenderEffect(boolean enabled) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            Class<?> renderEffectClass = Class.forName("android.graphics.RenderEffect");
            java.lang.reflect.Method setRenderEffect = View.class.getMethod(
                    "setRenderEffect", renderEffectClass);
            Object effect = null;
            if (enabled) {
                java.lang.reflect.Method createBlurEffect = renderEffectClass.getMethod(
                        "createBlurEffect", float.class, float.class, Shader.TileMode.class);
                effect = createBlurEffect.invoke(
                        null, (float) dp(13), (float) dp(13), Shader.TileMode.CLAMP);
            }
            setRenderEffect.invoke(visualLayer, effect);
            return true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Android 8–11 使用 1/8 尺寸快照做一次双向盒式模糊。像素数组和 Bitmap 都按尺寸复用，
     * 只在用户确认密码时执行，不进入粒子背景或输入系统的高频路径。
     */
    private boolean captureSoftwareBlurSnapshot() {
        int sourceW = visualLayer.getWidth();
        int sourceH = visualLayer.getHeight();
        if (sourceW <= 0 || sourceH <= 0) return false;
        int w = Math.max(1, sourceW / 8);
        int h = Math.max(1, sourceH / 8);
        if (blurSnapshotBitmap == null || blurSnapshotBitmap.getWidth() != w
                || blurSnapshotBitmap.getHeight() != h || blurSnapshotBitmap.isRecycled()) {
            if (blurSnapshotBitmap != null && !blurSnapshotBitmap.isRecycled()) blurSnapshotBitmap.recycle();
            blurSnapshotBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            blurPixels = new int[w * h];
            blurTemp = new int[w * h];
        }

        blurSnapshotBitmap.eraseColor(Color.TRANSPARENT);
        Canvas snapshotCanvas = new Canvas(blurSnapshotBitmap);
        snapshotCanvas.scale(w / (float) sourceW, h / (float) sourceH);
        visualLayer.draw(snapshotCanvas);
        boxBlurInPlace(blurSnapshotBitmap, 4);
        blurSnapshotView.setImageBitmap(blurSnapshotBitmap);
        return true;
    }

    private void boxBlurInPlace(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int count = w * h;
        if (radius <= 0 || count <= 0 || blurPixels == null || blurTemp == null
                || blurPixels.length < count || blurTemp.length < count) return;
        bitmap.getPixels(blurPixels, 0, w, 0, 0, w, h);

        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int from = Math.max(0, x - radius);
                int to = Math.min(w - 1, x + radius);
                int a = 0, r = 0, g = 0, b = 0;
                int n = to - from + 1;
                for (int xx = from; xx <= to; xx++) {
                    int c = blurPixels[row + xx];
                    a += (c >>> 24);
                    r += (c >> 16) & 0xff;
                    g += (c >> 8) & 0xff;
                    b += c & 0xff;
                }
                blurTemp[row + x] = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int from = Math.max(0, y - radius);
                int to = Math.min(h - 1, y + radius);
                int a = 0, r = 0, g = 0, b = 0;
                int n = to - from + 1;
                for (int yy = from; yy <= to; yy++) {
                    int c = blurTemp[yy * w + x];
                    a += (c >>> 24);
                    r += (c >> 16) & 0xff;
                    g += (c >> 8) & 0xff;
                    b += c & 0xff;
                }
                blurPixels[y * w + x] = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
        bitmap.setPixels(blurPixels, 0, w, 0, 0, w, h);
    }

    private void styleGateButton(Button button, boolean primary) {
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setMinHeight(dp(46));
        button.setPadding(dp(14), dp(9), dp(14), dp(9));
        button.setBackground(gateControlBackground(primary));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        // 入口页按钮也复用全局按压节奏，避免它与设置页按钮出现不同速度。
        UiMotion.bindPressFeedback(button);
    }

    private GradientDrawable gateControlBackground(boolean primary) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(primary ? Color.argb(226, 10, 132, 255) : Color.argb(24, 255, 255, 255));
        background.setStroke(dp(1), primary ? Color.argb(125, 255, 255, 255) : Color.argb(28, 255, 255, 255));
        return background;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ViewGroup.LayoutParams raw = content.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        int horizontalMargin = dp(24);
        lp.width = Math.max(dp(240), Math.min(dp(388), w - horizontalMargin * 2));
        lp.leftMargin = horizontalMargin;
        lp.rightMargin = horizontalMargin;
        content.setLayoutParams(lp);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(finishVerification);
        removeCallbacks(finishAuthorizedRunnable);
        removeCallbacks(restoreAfterFailureRunnable);
        animate().cancel();
        statusScrim.animate().cancel();
        visualLayer.animate().cancel();
        verifying = false;
        indicator.stop();
        if (Build.VERSION.SDK_INT >= 31) applyPlatformRenderEffect(false);
        blurSnapshotView.setImageBitmap(null);
        if (blurSnapshotBitmap != null && !blurSnapshotBitmap.isRecycled()) blurSnapshotBitmap.recycle();
        blurSnapshotBitmap = null;
        blurPixels = null;
        blurTemp = null;
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 三层缓慢粒子背景。数组和 Shader 都复用，高频绘制不创建临时对象。 */
    private static final class ParticleBackgroundView extends View {
        private static final int PARTICLE_COUNT = 108;
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] x = new float[PARTICLE_COUNT];
        private final float[] y = new float[PARTICLE_COUNT];
        private final float[] radius = new float[PARTICLE_COUNT];
        private final float[] speed = new float[PARTICLE_COUNT];
        private final int[] alpha = new int[PARTICLE_COUNT];
        private long lastFrameMs;

        ParticleBackgroundView(Context context) {
            super(context);
            setWillNotDraw(false);
            Random random = new Random(0xA60F20L);
            for (int i = 0; i < PARTICLE_COUNT; i++) {
                x[i] = random.nextFloat();
                y[i] = random.nextFloat();
                int layer = i % 3;
                radius[i] = layer == 0 ? 0.75f : (layer == 1 ? 1.15f : 1.7f);
                speed[i] = layer == 0 ? 0.0065f : (layer == 1 ? 0.0105f : 0.0155f);
                alpha[i] = layer == 0 ? 118 : (layer == 1 ? 158 : 205);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w <= 0 || h <= 0) return;
            backgroundPaint.setShader(new RadialGradient(
                    w * 0.5f, h * 1.04f, Math.max(w, h) * 1.08f,
                    Color.rgb(27, 39, 53), Color.rgb(9, 10, 15), Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), backgroundPaint);
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameMs == 0L ? 0f : Math.min(0.05f, (now - lastFrameMs) / 1000f);
            lastFrameMs = now;

            float density = getResources().getDisplayMetrics().density;
            for (int i = 0; i < PARTICLE_COUNT; i++) {
                y[i] -= speed[i] * dt;
                if (y[i] < -0.02f) y[i] += 1.04f;
                particlePaint.setColor(Color.argb(alpha[i], 255, 255, 255));
                canvas.drawCircle(x[i] * getWidth(), y[i] * getHeight(), radius[i] * density, particlePaint);
            }
            if (isShown()) postInvalidateDelayed(33L);
        }

        @Override
        protected void onDetachedFromWindow() {
            lastFrameMs = 0L;
            super.onDetachedFromWindow();
        }
    }

    /** 中心验证状态：旋转环在结果到达后原位变成勾或叉。 */
    private static final class VerificationIndicator extends View {
        private static final int STATE_IDLE = 0;
        private static final int STATE_SPINNER = 1;
        private static final int STATE_SUCCESS = 2;
        private static final int STATE_ERROR = 3;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int state = STATE_IDLE;
        private long spinnerStartMs;

        VerificationIndicator(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeCap(Paint.Cap.ROUND);
        }

        void startSpinner() {
            state = STATE_SPINNER;
            spinnerStartMs = SystemClock.uptimeMillis();
            invalidate();
        }

        void setResult(boolean success) {
            state = success ? STATE_SUCCESS : STATE_ERROR;
            invalidate();
        }

        void stop() {
            state = STATE_IDLE;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float density = getResources().getDisplayMetrics().density;
            float radius = Math.min(getWidth(), getHeight()) * 0.29f;
            float stroke = 3f * density;
            paint.setStrokeWidth(stroke);
            track.setStrokeWidth(stroke);

            if (state == STATE_SPINNER) {
                track.setColor(Color.argb(52, 255, 255, 255));
                canvas.drawCircle(cx, cy, radius, track);
                float rotation = ((SystemClock.uptimeMillis() - spinnerStartMs) % 880L) / 880f * 360f;
                paint.setColor(Color.WHITE);
                canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                        rotation - 80f, 225f, false, paint);
                postInvalidateOnAnimation();
                return;
            }

            if (state == STATE_SUCCESS) {
                paint.setColor(Color.rgb(48, 209, 88));
                path.reset();
                path.moveTo(cx - radius * 0.66f, cy + radius * 0.02f);
                path.lineTo(cx - radius * 0.16f, cy + radius * 0.50f);
                path.lineTo(cx + radius * 0.76f, cy - radius * 0.55f);
                canvas.drawPath(path, paint);
            } else if (state == STATE_ERROR) {
                paint.setColor(Color.rgb(255, 69, 58));
                float d = radius * 0.62f;
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, paint);
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, paint);
            }
        }
    }
}
