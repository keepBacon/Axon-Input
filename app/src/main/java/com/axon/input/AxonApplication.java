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
        AppFontChoiceController.preloadSavedChoice(this);
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
        mainHandler.removeCallbacks(reportBackground);
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
        if (activity != null && activity.getWindow() != null) {
            AppTypeface.applyToViewTree(activity.getWindow().getDecorView());
        }
    }
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
