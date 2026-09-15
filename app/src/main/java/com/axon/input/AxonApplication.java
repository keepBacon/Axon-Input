package com.axon.input;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** 应用进程初始化和前后台状态跟踪。 */
public final class AxonApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int startedActivities;

    private final Runnable reportBackground = () -> {
        if (startedActivities != 0) return;
        if (OverlayState.isAutoHideBackground(this)) {
            setTaskExcludedFromRecents(true);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!SignatureVerifier.isValid(this)) {
            throw new SecurityException("Axon Input signature verification failed");
        }
        // 新控制面必须在线拿到当前版本的 security/version/notice 三份策略。
        // 任一缺失、格式错误、版本不匹配或当前版本被远端禁用，均 fail-closed 终止启动。
        GitHubCloudPolicy.bootstrapRequired(this);
        // 字体选择属于应用 UI 的启动依赖。已缓存的云端字体在 Activity 创建任何 TextView 前
        // 就恢复，避免先用系统字体绘制启动动画/主页，再在动画结束后整体换字体。
        // 这里只读取私有文件，不联网；缓存缺失时仍由验证后的字体流程负责自动补下载。
        AppFontChoiceController.preloadSavedChoice(this);
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
        mainHandler.removeCallbacks(reportBackground);
        // 应用在前台时保持任务正常显示。
        // 开启“隐藏后台”后，离开应用时只隐藏最近任务卡片。
        // 无障碍悬浮层不受影响。
        syncTaskVisibility(activity, false);
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (startedActivities > 0) startedActivities--;
        if (startedActivities == 0) mainHandler.post(reportBackground);
    }

    public static void syncTaskVisibility(Activity activity, boolean excluded) {
        if (activity == null) return;
        try {
            ActivityManager manager = (ActivityManager) activity.getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return;
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                task.setExcludeFromRecents(excluded);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void setTaskExcludedFromRecents(boolean excluded) {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return;
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                task.setExcludeFromRecents(excluded);
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {
        // 云端应用字体只作用于 Activity UI；按显/悬浮层仍使用各自 FontManager。
        if (activity != null && activity.getWindow() != null) {
            AppTypeface.applyToViewTree(activity.getWindow().getDecorView());
        }
    }
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
