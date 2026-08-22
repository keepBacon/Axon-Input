package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/** 启动后检查一次新版本。检测失败不影响应用使用。 */
final class UpdateChecker {
    private static final String VERSION_INFO_URL =
            "https://raw.githubusercontent.com/keepBacon/Axon-Input/main/version.json";
    private static final String REPOSITORY_URL =
            "https://github.com/keepBacon/Axon-Input";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private UpdateChecker() {}

    static void check(Activity activity) {
        if (!canUse(activity) || !STARTED.compareAndSet(false, true)) return;

        Thread worker = new Thread(() -> {
            UpdateInfo info = fetchUpdate(activity);
            if (info == null || info.versionCode <= AppVersion.code(activity)) return;
            activity.runOnUiThread(() -> {
                if (canUse(activity)) showUpdateDialog(activity, info);
            });
        }, "AxonUpdateCheck");
        worker.setDaemon(true);
        worker.start();
    }

    private static UpdateInfo fetchUpdate(Activity activity) {
        JSONObject json = RemoteJson.get(activity, VERSION_INFO_URL, true);
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

        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.update_now, (dialog, which) -> openDownload(activity))
                .setNegativeButton(R.string.update_later, null)
                .show();
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
