package com.axon.input;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_CHECKING = 1;
    private static final int STATE_ACTIVE = 2;
    private static final int STATE_UNAVAILABLE = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object STATE_LOCK = new Object();
    private static final List<ActivationListener> WAITERS = new ArrayList<>();
    private static volatile int activationState = STATE_UNKNOWN;

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
            if (activationState == STATE_ACTIVE || activationState == STATE_UNAVAILABLE) {
                boolean active = activationState == STATE_ACTIVE;
                dispatchWaitersLocked(active);
                return;
            }
            if (activationState == STATE_UNKNOWN) {
                activationState = STATE_CHECKING;
                startProbe = true;
            }
        }
        if (!startProbe) return;

        Thread worker = new Thread(() -> {
            boolean active = probeRoot();
            if (active && app != null) {
                // Root 是全局优先级，不只用于灵敏度增强。
                SensitivitySettingsStore.setMode(app, SensitivitySettingsStore.MODE_ROOT);
            } else if (!active && app != null) {
                // Root 不存在/被拒绝时自动回退 Shizuku，避免旧 Root 偏好造成输入永久失效。
                SensitivitySettingsStore.setMode(app, SensitivitySettingsStore.MODE_SHIZUKU);
            }
            synchronized (STATE_LOCK) {
                activationState = active ? STATE_ACTIVE : STATE_UNAVAILABLE;
                dispatchWaitersLocked(active);
            }
        }, "AxonRootActivation");
        worker.setDaemon(true);
        worker.start();
    }

    private static void dispatchWaitersLocked(boolean active) {
        if (WAITERS.isEmpty()) return;
        List<ActivationListener> callbacks = new ArrayList<>(WAITERS);
        WAITERS.clear();
        MAIN.post(() -> {
            for (ActivationListener callback : callbacks) {
                if (callback == null) continue;
                try { callback.onRootActivationResult(active); } catch (Throwable ignored) {}
            }
        });
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
            if (process != null) process.destroyForcibly();
            return false;
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) {
                // 持续读取输出，避免管道阻塞。
            }
        }
        return process.waitFor();
    }
}
