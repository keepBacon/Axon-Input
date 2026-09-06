package com.axon.input;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** 登录后读取云端公告。同一公告 ID 只显示一次。 */
final class CloudNoticeChecker {
    interface Completion {
        void run(Activity activity);
    }

    private static final String NOTICE_URL =
            "https://raw.githubusercontent.com/keepBacon/Axon-Input/main/notice.json";
    private static final String DEFAULT_JOIN_URL = "https://kook.vip/GYYrsE";
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Activity> latestActivity = new WeakReference<>(null);
    private static volatile Completion latestCompletion;

    private CloudNoticeChecker() {}

    static void check(Activity activity, Completion onComplete) {
        if (!canUse(activity)) return;
        latestActivity = new WeakReference<>(activity);
        latestCompletion = onComplete;
        if (!CHECKING.compareAndSet(false, true)) return;

        final Context app = activity.getApplicationContext();
        Thread worker = new Thread(() -> {
            NoticeInfo info = fetch(app);
            MAIN.post(() -> finishCheck(info));
        }, "AxonCloudNotice");
        worker.setDaemon(true);
        worker.start();
    }

    private static void finishCheck(NoticeInfo info) {
        CHECKING.set(false);
        Activity activity = latestActivity.get();
        Completion completion = latestCompletion;
        latestActivity = new WeakReference<>(null);
        latestCompletion = null;
        if (!canUse(activity)) return;
        if (info == null || !info.enabled
                || info.id.equals(OverlayState.getLastCloudNoticeId(activity))) {
            complete(activity, completion);
            return;
        }
        show(activity, info, completion);
    }

    private static NoticeInfo fetch(Context context) {
        JSONObject json = RemoteJson.get(context, NOTICE_URL, true);
        if (json == null) return null;

        String id = json.optString("id", "").trim();
        String message = json.optString("message", "").trim();
        if (id.isEmpty() || message.isEmpty()) return null;

        String title = nonEmpty(json.optString("title", ""), context.getString(R.string.notice_default_title));
        String joinText = nonEmpty(json.optString("joinText", ""), context.getString(R.string.notice_join_default));
        String confirmText = nonEmpty(json.optString("confirmText", ""), context.getString(R.string.notice_confirm_default));
        String joinUrl = nonEmpty(json.optString("joinUrl", ""), DEFAULT_JOIN_URL);
        int waitSeconds = Math.max(0, Math.min(30, json.optInt("waitSeconds", 3)));
        return new NoticeInfo(
                id,
                json.optBoolean("enabled", true),
                title,
                message,
                joinUrl,
                joinText,
                confirmText,
                waitSeconds);
    }

    private static void show(Activity activity, NoticeInfo info, Completion onComplete) {
        if (!canUse(activity)) return;

        OverlayState.setLastCloudNoticeId(activity, info.id);
        DocumentModalDialog.Handle handle = DocumentModalDialog.show(
                activity,
                info.title,
                info.message,
                info.joinText,
                info.confirmText,
                ignored -> MainActivity.openKookUrl(activity, info.joinUrl),
                DocumentModalDialog.Handle::dismiss,
                false,
                info.waitSeconds <= 0,
                () -> complete(activity, onComplete));
        startConfirmDelay(activity, handle, info.confirmText, info.waitSeconds);
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

    private static final class NoticeInfo {
        final String id;
        final boolean enabled;
        final String title;
        final String message;
        final String joinUrl;
        final String joinText;
        final String confirmText;
        final int waitSeconds;

        NoticeInfo(
                String id,
                boolean enabled,
                String title,
                String message,
                String joinUrl,
                String joinText,
                String confirmText,
                int waitSeconds) {
            this.id = id;
            this.enabled = enabled;
            this.title = title;
            this.message = message;
            this.joinUrl = joinUrl;
            this.joinText = joinText;
            this.confirmText = confirmText;
            this.waitSeconds = waitSeconds;
        }
    }
}
