package com.axon.input;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

/** 统一读取当前 APK 版本信息。 */
final class AppVersion {
    private AppVersion() {}

    static long code(Context context) {
        PackageInfo info = packageInfo(context);
        if (info == null) return 0L;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
    }

    static String name(Context context) {
        PackageInfo info = packageInfo(context);
        return info == null || info.versionName == null || info.versionName.trim().isEmpty()
                ? "unknown"
                : info.versionName.trim();
    }

    static String userAgent(Context context) {
        return "Axon-Input/" + name(context).replace(' ', '-');
    }

    private static PackageInfo packageInfo(Context context) {
        if (context == null) return null;
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (Exception ignored) {
            return null;
        }
    }
}
