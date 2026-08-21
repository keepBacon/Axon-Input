package com.axon.input;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Process-level initialization and foreground tracking for Axon Input. */
public final class AxonApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int startedActivities;

    private final Runnable reportBackground = () -> {
        if (startedActivities == 0) AxonInputAccessibilityService.setAppForeground(false);
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!SignatureVerifier.isValid(this)) {
            throw new SecurityException("Axon Input signature verification failed");
        }
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
        mainHandler.removeCallbacks(reportBackground);
        AxonInputAccessibilityService.setAppForeground(true);
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (startedActivities > 0) startedActivities--;
        if (startedActivities == 0) mainHandler.post(reportBackground);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
