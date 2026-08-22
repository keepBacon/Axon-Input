package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.SystemClock;
import android.widget.Button;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** 登录后读取云端公告。每个公告 ID 只显示一次。 */
final class CloudNoticeChecker {
    private static final String NOTICE_URL =
            "https://raw.githubusercontent.com/keepBacon/Axon-Input/main/notice.json";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);

    private CloudNoticeChecker() {}

    static void check(Activity activity, Runnable onComplete) {
        if (activity == null || activity.isFinishing()) {
            run(onComplete);
            return;
        }
        if (!CHECKING.compareAndSet(false, true)) {
            run(onComplete);
            return;
        }

        Thread worker = new Thread(() -> {
            NoticeInfo info = fetch();
            activity.runOnUiThread(() -> {
                CHECKING.set(false);
                if (!canUseActivity(activity) || info == null || !info.enabled) {
                    run(onComplete);
                    return;
                }
                String lastId = OverlayState.getLastCloudNoticeId(activity);
                if (info.id.equals(lastId)) {
                    run(onComplete);
                    return;
                }
                show(activity, info, onComplete);
            });
        }, "AxonCloudNotice");
        worker.setDaemon(true);
        worker.start();
    }

    private static NoticeInfo fetch() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(NOTICE_URL + "?t=" + System.currentTimeMillis()).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("User-Agent", "Axon-Input/1.2");
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            byte[] data;
            try (InputStream input = connection.getInputStream()) {
                data = readLimited(input, MAX_RESPONSE_BYTES);
            }
            if (data == null || data.length == 0) return null;

            JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
            String id = json.optString("id", "").trim();
            if (id.isEmpty()) return null;

            boolean enabled = json.optBoolean("enabled", true);
            String title = json.optString("title", "公告").trim();
            String message = json.optString("message", "").trim();
            String joinUrl = json.optString("joinUrl", "https://kook.vip/GYYrsE").trim();
            String joinText = json.optString("joinText", "立即加入").trim();
            String confirmText = json.optString("confirmText", "确定").trim();
            int waitSeconds = Math.max(0, Math.min(30, json.optInt("waitSeconds", 3)));

            if (title.isEmpty()) title = "公告";
            if (message.isEmpty()) return null;
            if (joinText.isEmpty()) joinText = "立即加入";
            if (confirmText.isEmpty()) confirmText = "确定";
            return new NoticeInfo(id, enabled, title, message, joinUrl, joinText, confirmText, waitSeconds);
        } catch (Exception ignored) {
            // 公告获取失败不影响进入应用。
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void show(Activity activity, NoticeInfo info, Runnable onComplete) {
        if (!canUseActivity(activity)) {
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
            // 弹出即记录，保证同一公告只出现一次。
            OverlayState.setLastCloudNoticeId(activity, info.id);

            Button join = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            join.setOnClickListener(v -> {
                if (!info.joinUrl.isEmpty()) MainActivity.openKookUrl(activity, info.joinUrl);
            });

            if (info.waitSeconds <= 0) {
                confirm.setText(info.confirmText);
                confirm.setEnabled(true);
            } else {
                confirm.setEnabled(false);
                long readyAt = SystemClock.uptimeMillis() + info.waitSeconds * 1000L;
                Runnable countdown = new Runnable() {
                    @Override public void run() {
                        if (!dialog.isShowing()) return;
                        long remaining = readyAt - SystemClock.uptimeMillis();
                        if (remaining <= 0L) {
                            confirm.setText(info.confirmText);
                            confirm.setEnabled(true);
                            return;
                        }
                        int seconds = (int) ((remaining + 999L) / 1000L);
                        confirm.setText(info.confirmText + " (" + seconds + ")");
                        activity.getWindow().getDecorView().postDelayed(this, Math.min(1000L, remaining));
                    }
                };
                activity.getWindow().getDecorView().post(countdown);
            }
            confirm.setOnClickListener(v -> dialog.dismiss());
        });
        dialog.setOnDismissListener(ignored -> run(onComplete));
        dialog.show();
    }

    private static boolean canUseActivity(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 4096));
        byte[] buffer = new byte[4096];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) break;
            total += read;
            if (total > limit) return null;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
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
