package com.axon.input;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/** 使用启动阶段已验证的版本专属 GitHub 策略显示普通/强制更新。 */
final class UpdateChecker {
    private static final AtomicBoolean COMPLETED = new AtomicBoolean(false);

    private UpdateChecker() {}

    static void check(Activity activity) {
        if (!canUse(activity) || !COMPLETED.compareAndSet(false, true)) return;
        GitHubCloudPolicy.VersionPolicy info = GitHubCloudPolicy.version();
        long currentCode = AppVersion.code(activity);
        if (!info.updateAvailable(currentCode)) return;
        showUpdateDialog(activity, info, currentCode);
    }

    private static void showUpdateDialog(
            Activity activity,
            GitHubCloudPolicy.VersionPolicy info,
            long currentCode) {
        boolean forced = info.updateIsForced(currentCode);
        String latest = info.latestVersionName.isEmpty()
                ? String.valueOf(info.latestVersionCode)
                : info.latestVersionName;
        String title = nonEmpty(info.updateTitle, activity.getString(R.string.update_available_title));

        StringBuilder message = new StringBuilder();
        if (!info.updateMessage.isEmpty()) {
            message.append(info.updateMessage);
        } else {
            message.append(activity.getString(
                    R.string.update_version_message,
                    AppVersion.name(activity),
                    latest));
        }
        if (!info.changelog.isEmpty()) {
            if (message.length() > 0) message.append("\n\n");
            message.append(info.changelog);
        }

        String ghostText = forced
                ? nonEmpty(info.forceUpdateExitText, "退出")
                : nonEmpty(info.updateLaterText, activity.getString(R.string.update_later));
        String primaryText = nonEmpty(info.updateNowText, activity.getString(R.string.update_now));

        DocumentModalDialog.show(
                activity,
                title,
                message.toString(),
                ghostText,
                primaryText,
                handle -> {
                    if (forced) {
                        activity.finishAffinity();
                    } else {
                        handle.dismiss();
                    }
                },
                handle -> {
                    if (!forced) handle.dismiss();
                    openDownload(activity, info.downloadUrl);
                },
                !forced,
                !forced,
                null);
    }

    private static void openDownload(Activity activity, String url) {
        String target = nonEmpty(url, "https://github.com/keepBacon/Axon-Input");
        try {
            Uri uri = Uri.parse(target);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("unsupported update URL");
            }
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException error) {
            Toast.makeText(activity, R.string.update_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean canUse(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private static String nonEmpty(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }
}
