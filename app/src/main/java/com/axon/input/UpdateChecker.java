package com.axon.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight startup update check.
 *
 * The network request runs once per process and never touches the input/render hot path.
 * Update metadata is controlled by version.json in the public GitHub repository.
 */
final class UpdateChecker {
    private static final String VERSION_INFO_URL =
            "https://raw.githubusercontent.com/keepBacon/Axon-Input/main/version.json";
    private static final String REPOSITORY_URL =
            "https://github.com/keepBacon/Axon-Input";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private UpdateChecker() {}

    static void check(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (!STARTED.compareAndSet(false, true)) return;

        Thread worker = new Thread(() -> {
            UpdateInfo info = fetchUpdate(activity);
            if (info == null || info.versionCode <= getInstalledVersionCode(activity)) return;
            activity.runOnUiThread(() -> showUpdateDialog(activity, info));
        }, "AxonUpdateCheck");
        worker.setDaemon(true);
        worker.start();
    }

    private static UpdateInfo fetchUpdate(Activity activity) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(VERSION_INFO_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Axon-Input/" + getInstalledVersionName(activity));
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            byte[] data;
            try (InputStream input = connection.getInputStream()) {
                data = readLimited(input, MAX_RESPONSE_BYTES);
            }
            if (data == null || data.length == 0) return null;

            JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
            int versionCode = json.optInt("versionCode", -1);
            if (versionCode < 0) return null;

            String versionName = json.optString("versionName", "").trim();
            String changelog = json.optString("changelog", "").trim();
            return new UpdateInfo(versionCode, versionName, changelog);
        } catch (Exception ignored) {
            // Version checking must never interfere with app startup or input handling.
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
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

    private static long getInstalledVersionCode(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return info.getLongVersionCode();
            return info.versionCode;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String getInstalledVersionName(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static void showUpdateDialog(Activity activity, UpdateInfo info) {
        if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;

        String latest = info.versionName.isEmpty()
                ? String.valueOf(info.versionCode)
                : info.versionName;
        StringBuilder message = new StringBuilder();
        message.append(activity.getString(
                R.string.update_version_message,
                getInstalledVersionName(activity),
                latest));
        if (!info.changelog.isEmpty()) {
            message.append("\n\n").append(info.changelog);
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.update_now, (dialog, which) -> openDownload(activity, REPOSITORY_URL))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static void openDownload(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(activity, R.string.update_open_failed, Toast.LENGTH_SHORT).show();
        }
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
