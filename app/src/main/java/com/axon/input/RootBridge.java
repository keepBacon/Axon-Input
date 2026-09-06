package com.axon.input;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Axon 的 Root 权限通道。
 *
 * Root 一旦实际获得 uid=0，就作为全局首选特权通道；Shizuku 仅在 Root 不可用时回退。
 * 探测只在后台线程执行并在单进程内缓存，避免每个输入功能反复弹出 su 授权窗口。
 */
public final class RootBridge {
    public interface ActivationListener {
        void onRootActivationResult(boolean active);
    }

    /** Activity 专用回调：RootBridge 只弱持有 Activity，避免 su 授权等待期间保留旧页面。 */
    public interface ActivityActivationListener {
        void onRootActivationResult(Activity activity, boolean active);
    }

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_CHECKING = 1;
    private static final int STATE_ACTIVE = 2;
    private static final int STATE_UNAVAILABLE = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long UNAVAILABLE_RETRY_MS = 15_000L;
    private static final Object STATE_LOCK = new Object();
    private static final List<ActivationListener> WAITERS = new ArrayList<>();
    private static final List<ActivityWaiter> ACTIVITY_WAITERS = new ArrayList<>();
    private static volatile int activationState = STATE_UNKNOWN;
    private static volatile long lastProbeFinishedAt;

    private RootBridge() {}

    public static final class RootProcess implements Closeable {
        private final Process process;

        RootProcess(Process process) {
            this.process = process;
        }

        public InputStream getInputStream() {
            return process.getInputStream();
        }

        public java.io.OutputStream getOutputStream() {
            return process.getOutputStream();
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(120, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /** 当前进程已验证 su 能实际取得 uid 0。 */
    public static boolean isRootActive() {
        return activationState == STATE_ACTIVE;
    }

    /** Root 探测已经有最终结果；false 表示尚未探测或正在等待授权。 */
    public static boolean isProbeComplete() {
        int state = activationState;
        return state == STATE_ACTIVE || state == STATE_UNAVAILABLE;
    }

    public static boolean isProbeInFlight() {
        return activationState == STATE_CHECKING;
    }

    /**
     * 首次需要特权能力时自动请求 Root。成功后强制把全局输入通道切到 Root；
     * 失败/拒绝后保持 Shizuku 回退。listener 总是在主线程回调。
     */
    public static void ensureActivated(Context context, ActivationListener listener) {
        final Context app = context == null ? null : context.getApplicationContext();
        boolean startProbe = false;
        synchronized (STATE_LOCK) {
            if (listener != null) WAITERS.add(listener);
            if (activationState == STATE_ACTIVE) {
                dispatchWaitersLocked(true);
                return;
            }
            if (activationState == STATE_UNAVAILABLE) {
                long age = SystemClock.elapsedRealtime() - lastProbeFinishedAt;
                if (age >= 0L && age < UNAVAILABLE_RETRY_MS) {
                    dispatchWaitersLocked(false);
                    return;
                }
                // Root 管理器可能在应用运行期间刚刚完成授权。失败结果只做短期缓存，
                // 到冷却时间后允许重新探测，避免“首次拒绝后直到杀进程都无法恢复”。
                activationState = STATE_CHECKING;
                startProbe = true;
            } else if (activationState == STATE_UNKNOWN) {
                activationState = STATE_CHECKING;
                startProbe = true;
            }
        }
        if (!startProbe) return;

        startProbeWorker(app);
    }


    public static void ensureActivated(Activity activity, ActivityActivationListener listener) {
        if (activity == null) {
            ensureActivated((Context) null, null);
            return;
        }
        boolean startProbe = false;
        synchronized (STATE_LOCK) {
            if (listener != null) ACTIVITY_WAITERS.add(new ActivityWaiter(activity, listener));
            if (activationState == STATE_ACTIVE) {
                dispatchWaitersLocked(true);
                return;
            }
            if (activationState == STATE_UNAVAILABLE) {
                long age = SystemClock.elapsedRealtime() - lastProbeFinishedAt;
                if (age >= 0L && age < UNAVAILABLE_RETRY_MS) {
                    dispatchWaitersLocked(false);
                    return;
                }
                activationState = STATE_CHECKING;
                startProbe = true;
            } else if (activationState == STATE_UNKNOWN) {
                activationState = STATE_CHECKING;
                startProbe = true;
            }
        }
        if (!startProbe) return;
        final Context app = activity.getApplicationContext();
        startProbeWorker(app);
    }



    private static void startProbeWorker(Context app) {
        Thread worker = new Thread(() -> {
            boolean active = probeRoot();
            if (active && app != null) {
                SensitivitySettingsStore.setMode(app, SensitivitySettingsStore.MODE_ROOT);
            } else if (!active && app != null) {
                SensitivitySettingsStore.setMode(app, SensitivitySettingsStore.MODE_SHIZUKU);
            }
            synchronized (STATE_LOCK) {
                activationState = active ? STATE_ACTIVE : STATE_UNAVAILABLE;
                lastProbeFinishedAt = SystemClock.elapsedRealtime();
                dispatchWaitersLocked(active);
            }
        }, "AxonRootActivation");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 已验证的 Root 通道在运行期失效时撤销缓存，并立即重新探测。
     * 只在真实的 su/getevent 通道异常时调用，避免普通命令业务失败误判为 Root 被撤销。
     */
    public static void reportRootChannelFailure(Context context) {
        synchronized (STATE_LOCK) {
            if (activationState == STATE_CHECKING) return;
            activationState = STATE_UNKNOWN;
            lastProbeFinishedAt = 0L;
        }
        ensureActivated(context, null);
    }

    private static void dispatchWaitersLocked(boolean active) {
        List<ActivationListener> callbacks = new ArrayList<>(WAITERS);
        WAITERS.clear();
        List<ActivityWaiter> activityCallbacks = new ArrayList<>(ACTIVITY_WAITERS);
        ACTIVITY_WAITERS.clear();
        if (callbacks.isEmpty() && activityCallbacks.isEmpty()) return;
        MAIN.post(() -> {
            for (ActivationListener callback : callbacks) {
                if (callback == null) continue;
                try { callback.onRootActivationResult(active); } catch (Throwable ignored) {}
            }
            for (ActivityWaiter waiter : activityCallbacks) {
                if (waiter == null) continue;
                Activity activity = waiter.activity.get();
                if (activity == null || activity.isFinishing()
                        || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) continue;
                try { waiter.listener.onRootActivationResult(activity, active); } catch (Throwable ignored) {}
            }
        });
    }

    private static final class ActivityWaiter {
        final WeakReference<Activity> activity;
        final ActivityActivationListener listener;

        ActivityWaiter(Activity activity, ActivityActivationListener listener) {
            this.activity = new WeakReference<>(activity);
            this.listener = listener;
        }
    }

    private static boolean probeRoot() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c",
                    "if [ \"$(id -u)\" = 0 ]; then exit 0; else exit 1; fi")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
                try {
                    if (!process.waitFor(120, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (Throwable ignored) {
                    try { process.destroyForcibly(); } catch (Throwable ignoredAgain) {}
                }
            }
        }
    }

    public static RootProcess startShell(String command) throws IOException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        return new RootProcess(process);
    }

    public static int runShell(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // 持续读取输出，避免管道阻塞。
                }
            }
            return process.waitFor();
        } finally {
            try { process.destroy(); } catch (Throwable ignored) {}
            try {
                if (!process.waitFor(120, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (Throwable ignored) {
                try { process.destroyForcibly(); } catch (Throwable ignoredAgain) {}
            }
        }
    }
}
