package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.SystemClock;
import android.widget.Button;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/** 登录后读取云端公告。同一公告 ID 只显示一次。 */
final class CloudNoticeChecker {
    private static final String NOTICE_URL =
            "https://raw.githubusercontent.com/keepBacon/Axon-Input/main/notice.json";
    private static final String DEFAULT_JOIN_URL = "https://kook.vip/GYYrsE";
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);

    private CloudNoticeChecker() {}

    static void check(Activity activity, Runnable onComplete) {
        if (!canUse(activity)) {
            run(onComplete);
            return;
        }
        if (!CHECKING.compareAndSet(false, true)) {
            run(onComplete);
            return;
        }

        Thread worker = new Thread(() -> {
            NoticeInfo info = fetch(activity);
            activity.runOnUiThread(() -> {
                CHECKING.set(false);
                if (!canUse(activity) || info == null || !info.enabled) {
                    run(onComplete);
                    return;
                }
                if (info.id.equals(OverlayState.getLastCloudNoticeId(activity))) {
                    run(onComplete);
                    return;
                }
                show(activity, info, onComplete);
            });
        }, "AxonCloudNotice");
        worker.setDaemon(true);
        worker.start();
    }

    private static NoticeInfo fetch(Activity activity) {
        JSONObject json = RemoteJson.get(activity, NOTICE_URL, true);
        if (json == null) return null;

        String id = json.optString("id", "").trim();
        String message = json.optString("message", "").trim();
        if (id.isEmpty() || message.isEmpty()) return null;

        String title = nonEmpty(json.optString("title", ""), activity.getString(R.string.notice_default_title));
        String joinText = nonEmpty(json.optString("joinText", ""), activity.getString(R.string.notice_join_default));
        String confirmText = nonEmpty(json.optString("confirmText", ""), activity.getString(R.string.notice_confirm_default));
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

    private static void show(Activity activity, NoticeInfo info, Runnable onComplete) {
        if (!canUse(activity)) {
            run(onComplete);
            return;
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(info.title)
                .setMessage(info.message)
                .setNegativeButton(info.joinText, null)
                .setPositiveButton(info.confirmText, null)
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(ignored -> {
            OverlayState.setLastCloudNoticeId(activity, info.id);
            Button join = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            join.setOnClickListener(v -> MainActivity.openKookUrl(activity, info.joinUrl));
            startConfirmDelay(activity, dialog, confirm, info.confirmText, info.waitSeconds);
            confirm.setOnClickListener(v -> dialog.dismiss());
        });
        dialog.setOnDismissListener(ignored -> run(onComplete));
        dialog.show();
    }

    private static void startConfirmDelay(
            Activity activity,
            AlertDialog dialog,
            Button confirm,
            String text,
            int waitSeconds) {
        if (waitSeconds <= 0) {
            confirm.setText(text);
            confirm.setEnabled(true);
            return;
        }

        confirm.setEnabled(false);
        long readyAt = SystemClock.uptimeMillis() + waitSeconds * 1000L;
        Runnable countdown = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) return;
                long remaining = readyAt - SystemClock.uptimeMillis();
                if (remaining <= 0L) {
                    confirm.setText(text);
                    confirm.setEnabled(true);
                    return;
                }
                confirm.setText(text + " (" + ((remaining + 999L) / 1000L) + ")");
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

    private static void run(Runnable runnable) {
        if (runnable != null) runnable.run();
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
