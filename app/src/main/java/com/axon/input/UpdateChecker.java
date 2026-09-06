package com.axon.input;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** 启动后检查新版本。只有成功拿到有效版本信息后才标记本进程已完成。 */
final class UpdateChecker {
    private static final String VERSION_INFO_URL =
            "https://raw.githubusercontent.com/keepBacon/Axon-Input/main/version.json";
    private static final String REPOSITORY_URL =
            "https://github.com/keepBacon/Axon-Input";
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static final AtomicBoolean COMPLETED = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Activity> latestActivity = new WeakReference<>(null);

    private UpdateChecker() {}

    static void check(Activity activity) {
        if (!canUse(activity)) return;
        latestActivity = new WeakReference<>(activity);
        if (COMPLETED.get() || !CHECKING.compareAndSet(false, true)) return;

        final Context app = activity.getApplicationContext();
        Thread worker = new Thread(() -> {
            UpdateInfo info = fetchUpdate(app);
            MAIN.post(() -> finishCheck(app, info));
        }, "AxonUpdateCheck");
        worker.setDaemon(true);
        worker.start();
    }

    private static void finishCheck(Context app, UpdateInfo info) {
        CHECKING.set(false);
        if (info == null) return; // 网络失败允许本进程后续再次检查。
        COMPLETED.set(true);
        Activity activity = latestActivity.get();
        if (!canUse(activity) || info.versionCode <= AppVersion.code(app)) return;
        showUpdateDialog(activity, info);
    }

    private static UpdateInfo fetchUpdate(Context context) {
        JSONObject json = RemoteJson.get(context, VERSION_INFO_URL, true);
        if (json == null) return null;

        int versionCode = json.optInt("versionCode", -1);
        if (versionCode < 0) return null;
        return new UpdateInfo(
                versionCode,
                json.optString("versionName", "").trim(),
                json.optString("changelog", "").trim());
    }

    private static void showUpdateDialog(Activity activity, UpdateInfo info) {
        String latest = info.versionName.isEmpty()
                ? String.valueOf(info.versionCode)
                : info.versionName;
        StringBuilder message = new StringBuilder(activity.getString(
                R.string.update_version_message,
                AppVersion.name(activity),
                latest));
        if (!info.changelog.isEmpty()) message.append("\n\n").append(info.changelog);

        DocumentModalDialog.show(
                activity,
                activity.getString(R.string.update_available_title),
                message.toString(),
                activity.getString(R.string.update_later),
                activity.getString(R.string.update_now),
                DocumentModalDialog.Handle::dismiss,
                handle -> {
                    handle.dismiss();
                    openDownload(activity);
                },
                true,
                true,
                null);
    }

    private static void openDownload(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)));
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(activity, R.string.update_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean canUse(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final String changelog;

        UpdateInfo(int versionCode, String versionName, String changelog) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.changelog = changelog;
        }
    }
}
