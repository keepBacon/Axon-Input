package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 应用 UI 字体入口。安装后只在用户尚未做出选择时展示一次；选择结果写入应用私有配置，
 * 后续冷启动直接恢复，不因 Activity recreate 或新进程再次弹出。
 * “应用字体”不随 APK 打包：首次选择时从 Adobe 官方 Source Han Sans 仓库下载并校验；
 * 若字体缓存被系统清理，则按已保存的选择自动补下载，而不是再次询问字体类型。
 */
final class AppFontChoiceController {
    private static final String PRIMARY_URL =
            "https://raw.githubusercontent.com/adobe-fonts/source-han-sans/2.005R/OTF/SimplifiedChinese/SourceHanSansSC-Heavy.otf";
    private static final String FALLBACK_URL =
            "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-sans@2.005R/OTF/SimplifiedChinese/SourceHanSansSC-Heavy.otf";
    private static final String EXPECTED_SHA256 =
            "6374b11bc4c2cd4bd7be1a1d64cf5047906c8a6a025c64e023c6792e50ba985e";
    private static final long EXPECTED_MIN_BYTES = 16L * 1024L * 1024L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String PREFS = "axon_app_font_choice";
    private static final String KEY_CHOICE_MADE = "choice_made";
    private static final String KEY_USE_APP_FONT = "use_app_font";
    private static final Object DOWNLOAD_LOCK = new Object();
    private static final List<DownloadRequest> DOWNLOAD_WAITERS = new ArrayList<>();
    private static boolean downloadInFlight;

    private static final class DownloadRequest {
        final WeakReference<Activity> activity;
        final WeakReference<ProgressDialog> progress;
        final Runnable continuation;

        DownloadRequest(Activity activity, ProgressDialog progress, Runnable continuation) {
            this.activity = new WeakReference<>(activity);
            this.progress = new WeakReference<>(progress);
            this.continuation = continuation;
        }
    }

    private AppFontChoiceController() {}

    /**
     * 进程启动时尽早恢复已经持久化的字体选择。这里只读取应用私有缓存，绝不触发网络、
     * Dialog 或 Activity 回调，因此可以安全地从 Application.onCreate() 调用。
     *
     * 这样 MainActivity 第一次创建 TextView / 启动品牌字母动画时就已经使用最终字体，
     * 不会出现“启动动画先用系统字体，动画结束后整页再切 Heavy 字体”的视觉跳变。
     * 若用户选择了应用字体但缓存被系统清理，这里只保持系统字体兜底；真正的自动补下载
     * 仍由 showOnce() 在密码验证通过后接管，并且补下载完成后才继续品牌动画。
     */
    static void preloadSavedChoice(Context context) {
        if (context == null) return;
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_CHOICE_MADE, false)) return;
        if (!prefs.getBoolean(KEY_USE_APP_FONT, false)) {
            AppTypeface.selectSystem();
            return;
        }
        AppTypeface.selectDownloaded(context.getApplicationContext());
    }

    static void showOnce(Activity activity, Runnable continuation) {
        if (!isActivityAlive(activity)) return;

        // SharedPreferences 是唯一真值。不要用“本进程已显示”布尔值抢跑：Activity 在首次弹窗期间
        // recreate 时，进程布尔值会让新 Activity 绕过选择页，而持久化状态其实还没写入。
        SharedPreferences prefs = prefs(activity);
        if (prefs.getBoolean(KEY_CHOICE_MADE, false)) {
            restoreSavedChoice(activity, continuation,
                    prefs.getBoolean(KEY_USE_APP_FONT, false));
            return;
        }
        showChoice(activity, continuation);
    }

    private static void restoreSavedChoice(Activity activity, Runnable continuation,
                                           boolean useAppFont) {
        if (!useAppFont) {
            AppTypeface.selectSystem();
            runContinuation(activity, continuation);
            return;
        }
        if (AppTypeface.selectDownloaded(activity)) {
            applyNow(activity);
            runContinuation(activity, continuation);
            return;
        }
        // 用户已明确选择应用字体，但缓存可能被系统清理；此时自动补下载，
        // 不重新询问字体类型，避免“只显示一次”的入口规则失效。
        downloadFont(activity, continuation);
    }

    private static void showChoice(Activity activity, Runnable continuation) {
        final String[] items = {"系统字体", "应用字体（云端下载）"};
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("选择字体")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        saveChoice(activity, false);
                        AppTypeface.selectSystem();
                        runContinuation(activity, continuation);
                    } else {
                        // 点击即代表用户已经完成选择。即使这次网络失败，也不再在下次启动
                        // 重复显示类型选择弹窗；下载失败界面仍可重试或切回系统字体。
                        saveChoice(activity, true);
                        useAppFont(activity, continuation);
                    }
                })
                .setOnCancelListener(d -> {
                    saveChoice(activity, false);
                    AppTypeface.selectSystem();
                    runContinuation(activity, continuation);
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private static void useAppFont(Activity activity, Runnable continuation) {
        if (AppTypeface.selectDownloaded(activity)) {
            applyNow(activity);
            runContinuation(activity, continuation);
            return;
        }
        downloadFont(activity, continuation);
    }

    private static void downloadFont(Activity activity, Runnable continuation) {
        if (!isActivityAlive(activity)) return;

        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("下载应用字体");
        progress.setMessage("正在从云端获取 Source Han Sans SC Heavy…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setIndeterminate(false);
        progress.setCancelable(false);
        progress.show();

        boolean startWorker = false;
        synchronized (DOWNLOAD_LOCK) {
            DOWNLOAD_WAITERS.add(new DownloadRequest(activity, progress, continuation));
            if (!downloadInFlight) {
                downloadInFlight = true;
                startWorker = true;
            }
        }
        if (!startWorker) return;

        Context app = activity.getApplicationContext();
        Thread worker = new Thread(() -> {
            Throwable lastError = null;
            String[] urls = {PRIMARY_URL, FALLBACK_URL};
            for (String url : urls) {
                try {
                    downloadAndValidate(app, url, AppFontChoiceController::dispatchProgress);
                    if (!AppTypeface.selectDownloaded(app)) {
                        throw new IllegalStateException("字体文件加载失败");
                    }
                    MAIN.post(() -> finishDownload(true, null));
                    return;
                } catch (Throwable error) {
                    lastError = error;
                }
            }
            Throwable finalError = lastError;
            MAIN.post(() -> finishDownload(false, finalError));
        }, "Axon-AppFontDownload");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 字体下载是进程级 single-flight：Activity recreate 只挂接新的弱引用 waiter，
     * 不再启动第二条线程竞争同一个 temp/target 文件。
     */
    private static void dispatchProgress(int value) {
        MAIN.post(() -> {
            synchronized (DOWNLOAD_LOCK) {
                for (int i = DOWNLOAD_WAITERS.size() - 1; i >= 0; i--) {
                    DownloadRequest request = DOWNLOAD_WAITERS.get(i);
                    Activity activity = request.activity.get();
                    ProgressDialog progress = request.progress.get();
                    if (!isActivityAlive(activity)) {
                        DOWNLOAD_WAITERS.remove(i);
                        continue;
                    }
                    if (progress != null && progress.isShowing()) {
                        try { progress.setProgress(value); } catch (Throwable ignored) {}
                    }
                }
            }
        });
    }

    private static void finishDownload(boolean success, Throwable error) {
        List<DownloadRequest> requests;
        synchronized (DOWNLOAD_LOCK) {
            downloadInFlight = false;
            requests = new ArrayList<>(DOWNLOAD_WAITERS);
            DOWNLOAD_WAITERS.clear();
        }

        for (DownloadRequest request : requests) {
            Activity activity = request.activity.get();
            ProgressDialog progress = request.progress.get();
            if (progress != null && progress.isShowing()) {
                try { progress.dismiss(); } catch (Throwable ignored) {}
            }
            if (!isActivityAlive(activity)) continue;

            if (success) {
                if (!AppTypeface.selectDownloaded(activity)) {
                    showDownloadFailure(activity, request.continuation,
                            new IllegalStateException("字体文件加载失败"));
                    continue;
                }
                applyNow(activity);
                Toast.makeText(activity, "应用字体已启用", Toast.LENGTH_SHORT).show();
                runContinuation(activity, request.continuation);
            } else {
                showDownloadFailure(activity, request.continuation, error);
            }
        }
    }

    private static boolean isActivityAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void downloadAndValidate(Context context, String source,
                                            ProgressCallback callback) throws Exception {
        File target = AppTypeface.downloadedFontFile(context);
        File temp = AppTypeface.downloadedFontTempFile(context);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建字体缓存目录");
        }
        if (temp.exists() && !temp.delete()) {
            throw new IllegalStateException("无法清理字体临时文件");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(source).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "Axon-Input/2.1");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            long total = connection.getContentLengthLong();

            try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream(), 64 * 1024);
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long readTotal = 0L;
                int lastProgress = -1;
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    out.write(buffer, 0, count);
                    readTotal += count;
                    if (total > 0L) {
                        int value = (int) Math.min(99L, readTotal * 100L / total);
                        if (value != lastProgress) {
                            lastProgress = value;
                            callback.onProgress(value);
                        }
                    }
                }
                out.getFD().sync();
            }

            if (temp.length() < EXPECTED_MIN_BYTES) throw new IllegalStateException("字体文件不完整");
            String sha256 = sha256(temp);
            if (!EXPECTED_SHA256.equalsIgnoreCase(sha256)) {
                throw new IllegalStateException("字体校验失败");
            }
            Typeface probe = Typeface.createFromFile(temp);
            if (probe == null) throw new IllegalStateException("字体格式无效");

            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("无法替换旧字体缓存");
            }
            if (!temp.renameTo(target)) {
                copyFile(temp, target);
                temp.delete();
            }
            callback.onProgress(100);
        } finally {
            if (connection != null) connection.disconnect();
            if (temp.exists() && !target.isFile()) temp.delete();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return out.toString();
    }

    private static void copyFile(File source, File target) throws Exception {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) >= 0) {
                if (count > 0) out.write(buffer, 0, count);
            }
            out.getFD().sync();
        }
    }

    private static void showDownloadFailure(Activity activity, Runnable continuation, Throwable error) {
        if (!isActivityAlive(activity)) return;
        String detail = error == null || error.getMessage() == null ? "网络连接失败" : error.getMessage();
        new AlertDialog.Builder(activity)
                .setTitle("字体下载失败")
                .setMessage("无法获取应用字体：" + detail)
                .setPositiveButton("重试", (d, w) -> downloadFont(activity, continuation))
                .setNegativeButton("使用系统字体", (d, w) -> {
                    saveChoice(activity, false);
                    AppTypeface.selectSystem();
                    runContinuation(activity, continuation);
                })
                .setCancelable(false)
                .show();
    }


    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void saveChoice(Context context, boolean useAppFont) {
        // 这是一次性低频选择，先同步落盘再进入主界面，避免用户刚选完就被系统杀进程时下次又弹。
        prefs(context).edit()
                .putBoolean(KEY_CHOICE_MADE, true)
                .putBoolean(KEY_USE_APP_FONT, useAppFont)
                .commit();
    }

    private static void applyNow(Activity activity) {
        if (!isActivityAlive(activity) || activity.getWindow() == null) return;
        AppTypeface.applyToViewTree(activity.getWindow().getDecorView());
    }

    private static void runContinuation(Activity activity, Runnable continuation) {
        if (continuation == null) return;
        MAIN.post(() -> {
            if (isActivityAlive(activity)) continuation.run();
        });
    }

    private interface ProgressCallback {
        void onProgress(int value);
    }
}
