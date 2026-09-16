package com.axon.input;

import android.app.Activity;

final class CloudNoticeChecker {
    interface Completion {
        void run(Activity activity);
    }

    private CloudNoticeChecker() {}

    static void check(Activity activity, Completion onComplete) {
        if (activity == null || activity.isFinishing()) return;
        if (onComplete != null) onComplete.run(activity);
    }
}
