package com.axon.input;

import android.app.Activity;
import android.os.Build;
import android.os.SystemClock;

/** 当前 APK 只读取 cloud-control/notices/<version>.json，对旧 notice.json 零影响。 */
final class CloudNoticeChecker {
    interface Completion {
        void run(Activity activity);
    }

    private static final String DEFAULT_JOIN_URL = "https://kook.vip/GYYrsE";

    private CloudNoticeChecker() {}

    static void check(Activity activity, Completion onComplete) {
        if (!canUse(activity)) return;
        GitHubCloudPolicy.NoticePolicy info = GitHubCloudPolicy.notice();
        String durableId = info.durableId();
        if (!info.enabled
                || (info.showOnce && durableId.equals(OverlayState.getLastCloudNoticeId(activity)))) {
            complete(activity, onComplete);
            return;
        }
        show(activity, info, durableId, onComplete);
    }

    private static void show(
            Activity activity,
            GitHubCloudPolicy.NoticePolicy info,
            String durableId,
            Completion onComplete) {
        if (!canUse(activity)) return;

        if (info.showOnce) OverlayState.setLastCloudNoticeId(activity, durableId);
        String title = nonEmpty(info.title, activity.getString(R.string.notice_default_title));
        String joinText = nonEmpty(info.joinText, activity.getString(R.string.notice_join_default));
        String confirmText = nonEmpty(info.confirmText, activity.getString(R.string.notice_confirm_default));
        String joinUrl = nonEmpty(info.joinUrl, DEFAULT_JOIN_URL);

        DocumentModalDialog.Handle handle = DocumentModalDialog.show(
                activity,
                title,
                info.message,
                joinText,
                confirmText,
                ignored -> MainActivity.openKookUrl(activity, joinUrl),
                DocumentModalDialog.Handle::dismiss,
                false,
                info.waitSeconds <= 0,
                () -> complete(activity, onComplete));
        startConfirmDelay(activity, handle, confirmText, info.waitSeconds);
    }

    private static void startConfirmDelay(
            Activity activity,
            DocumentModalDialog.Handle handle,
            String text,
            int waitSeconds) {
        if (waitSeconds <= 0) {
            handle.setPrimaryText(text);
            handle.setPrimaryEnabled(true);
            handle.setCloseEnabled(true);
            return;
        }

        handle.setPrimaryEnabled(false);
        handle.setCloseEnabled(false);
        long readyAt = SystemClock.uptimeMillis() + waitSeconds * 1000L;
        Runnable countdown = new Runnable() {
            @Override
            public void run() {
                if (!canUse(activity) || !handle.isShowing()) return;
                long remaining = readyAt - SystemClock.uptimeMillis();
                if (remaining <= 0L) {
                    handle.setPrimaryText(text);
                    handle.setPrimaryEnabled(true);
                    handle.setCloseEnabled(true);
                    return;
                }
                handle.setPrimaryText(text + " (" + ((remaining + 999L) / 1000L) + ")");
                activity.getWindow().getDecorView().postDelayed(this, Math.min(1000L, remaining));
            }
        };
        activity.getWindow().getDecorView().post(countdown);
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

    private static void complete(Activity activity, Completion completion) {
        if (completion != null && canUse(activity)) completion.run(activity);
    }
}
