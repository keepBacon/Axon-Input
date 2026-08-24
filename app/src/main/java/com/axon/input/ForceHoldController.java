package com.axon.input;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.io.Closeable;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 使用特权 uinput 进程维持一个真正的键盘 / 鼠标 / 手柄 DOWN 状态。
 * 触发路径不阻塞 AccessibilityService 的 onKeyEvent 主线程。
 */
public final class ForceHoldController {
    public interface Listener {
        void onForceHoldStartFailed();
    }

    private interface PrivilegedProcess extends Closeable {
        InputStream getInputStream();
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AxonInputForceHold");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    private final String tempBinaryBase;

    private PrivilegedProcess process;
    private PrivilegedProcess startingProcess;
    private boolean enabled;
    private boolean desiredHeld;
    private boolean destroyed;
    private int configuredInputCode = -1;
    private int configuredScanCode = -1;
    private int processInputCode = -1;
    private int processScanCode = -1;

    public ForceHoldController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        tempBinaryBase = "/data/local/tmp/axon_input_keyhold_" + Process.myUid();
    }

    /** 配置变化时只在必要时释放，普通界面刷新不会打断已经保持的按键。 */
    public void applyConfiguration(boolean enabled, int inputCode, int scanCode) {
        boolean shouldRelease;
        synchronized (lock) {
            if (destroyed) return;
            shouldRelease = desiredHeld && (!enabled || scanCode <= 0
                    || configuredInputCode != inputCode || configuredScanCode != scanCode);
            this.enabled = enabled && inputCode >= 0 && scanCode > 0;
            this.configuredInputCode = inputCode;
            this.configuredScanCode = scanCode;
            if (!this.enabled || shouldRelease) desiredHeld = false;
        }
        if (shouldRelease || !this.enabled) {
            cancelStartingProcess();
            enqueue(this::reconcile);
        }
    }

    /** 返回切换后的期望长按状态；实际 uinput 启停在工作线程执行。 */
    public boolean toggleHold(int inputCode, int scanCode) {
        boolean next;
        synchronized (lock) {
            if (destroyed || !enabled || inputCode < 0 || scanCode <= 0
                    || configuredInputCode != inputCode || configuredScanCode != scanCode) return false;
            desiredHeld = !desiredHeld;
            next = desiredHeld;
        }
        if (!next) cancelStartingProcess();
        enqueue(this::reconcile);
        return next;
    }

    public boolean isHoldRequested() {
        synchronized (lock) {
            return desiredHeld;
        }
    }

    public void release() {
        synchronized (lock) {
            if (destroyed) return;
            desiredHeld = false;
        }
        cancelStartingProcess();
        enqueue(this::reconcile);
    }

    public void destroy() {
        synchronized (lock) {
            if (destroyed) return;
            destroyed = true;
            desiredHeld = false;
            enabled = false;
        }
        cancelStartingProcess();
        enqueue(() -> {
            stopProcess();
            cleanupTempBestEffort();
        });
        executor.shutdown();
    }

    private void enqueue(Runnable task) {
        if (task == null) return;
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Service teardown won the race; destroy() already owns process cleanup.
        }
    }

    private void reconcile() {
        boolean shouldHold;
        int inputCode;
        int scanCode;
        synchronized (lock) {
            shouldHold = !destroyed && enabled && desiredHeld;
            inputCode = configuredInputCode;
            scanCode = configuredScanCode;
        }

        if (!shouldHold) {
            stopProcess();
            return;
        }
        if (process != null && processInputCode == inputCode && processScanCode == scanCode) return;

        stopProcess();
        PrivilegedProcess next = null;
        try {
            next = startProcess(inputCode, scanCode);
        } catch (Throwable ignored) {
            synchronized (lock) {
                desiredHeld = false;
            }
            if (listener != null) listener.onForceHoldStartFailed();
        }

        synchronized (lock) {
            if (destroyed || !enabled || !desiredHeld
                    || configuredInputCode != inputCode || configuredScanCode != scanCode) {
                if (next != null) {
                    try { next.close(); } catch (Throwable ignored) {}
                }
                return;
            }
            process = next;
            processInputCode = next == null ? -1 : inputCode;
            processScanCode = next == null ? -1 : scanCode;
            if (next == null) desiredHeld = false;
        }
        if (next != null) watchUnexpectedExit(next);
    }

    private void watchUnexpectedExit(PrivilegedProcess candidate) {
        Thread watcher = new Thread(() -> {
            try {
                InputStream input = candidate.getInputStream();
                if (input != null) while (input.read() >= 0) { /* wait for EOF */ }
            } catch (Throwable ignored) {
            }
            boolean failed = false;
            synchronized (lock) {
                if (process == candidate) {
                    process = null;
                    processInputCode = -1;
                    processScanCode = -1;
                    if (!destroyed && enabled && desiredHeld) {
                        desiredHeld = false;
                        failed = true;
                    }
                }
            }
            if (failed && listener != null) listener.onForceHoldStartFailed();
        }, "AxonInputForceHoldWatch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private PrivilegedProcess startProcess(int inputCode, int scanCode) throws Exception {
        String source = binaryPath();
        if (source == null) throw new IllegalStateException("keyhold binary missing");

        // 优先使用已经授权的 Shizuku；不可用时回退 Root。
        if (ShizukuBridge.isReady() && ShizukuBridge.hasPermission()) {
            try {
                return awaitReady(startShizuku(source, inputCode, scanCode));
            } catch (Throwable ignored) {
                // Root fallback below.
            }
        }
        return awaitReady(startRoot(source, inputCode, scanCode));
    }

    private PrivilegedProcess startRoot(String source, int inputCode, int scanCode) throws Exception {
        String temp = tempBinaryBase + "_root";
        RootBridge.RootProcess root = RootBridge.startShell(startCommand(source, temp, inputCode, scanCode));
        return new PrivilegedProcess() {
            @Override public InputStream getInputStream() { return root.getInputStream(); }
            @Override public void close() { root.close(); }
        };
    }

    private PrivilegedProcess startShizuku(String source, int inputCode, int scanCode) throws Exception {
        String temp = tempBinaryBase + "_shizuku";
        ShizukuBridge.ShellProcess shizuku = ShizukuBridge.startShell(startCommand(source, temp, inputCode, scanCode));
        return new PrivilegedProcess() {
            @Override public InputStream getInputStream() { return shizuku.getInputStream(); }
            @Override public void close() { shizuku.close(); }
        };
    }

    private String startCommand(String source, String temp, int inputCode, int scanCode) {
        return "rm -f " + q(temp)
                + "; cat " + q(source) + " > " + q(temp)
                + " && chmod 700 " + q(temp)
                + " && exec " + q(temp) + " --code " + scanCode
                + " --kind " + InputBinding.uinputKind(inputCode) + " 2>&1";
    }

    private PrivilegedProcess awaitReady(PrivilegedProcess candidate) throws Exception {
        if (candidate == null) throw new IllegalStateException("force-hold process missing");
        synchronized (lock) {
            if (destroyed || !enabled || !desiredHeld) {
                try { candidate.close(); } catch (Throwable ignored) {}
                throw new IllegalStateException("force-hold cancelled");
            }
            startingProcess = candidate;
        }
        String line;
        try {
            line = readLine(candidate.getInputStream(), 256);
        } finally {
            synchronized (lock) {
                if (startingProcess == candidate) startingProcess = null;
            }
        }
        if (!"READY".equals(line)) {
            try { candidate.close(); } catch (Throwable ignored) {}
            throw new IllegalStateException(line == null ? "force-hold process exited" : line);
        }
        return candidate;
    }

    private void cancelStartingProcess() {
        PrivilegedProcess candidate;
        synchronized (lock) {
            candidate = startingProcess;
            startingProcess = null;
        }
        if (candidate != null) {
            try { candidate.close(); } catch (Throwable ignored) {}
        }
    }

    private String readLine(InputStream input, int maxBytes) throws Exception {
        if (input == null) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < maxBytes; i++) {
            int value = input.read();
            if (value < 0) break;
            if (value == '\n') break;
            if (value != '\r') out.append((char) value);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private void stopProcess() {
        PrivilegedProcess current;
        synchronized (lock) {
            current = process;
            process = null;
            processInputCode = -1;
            processScanCode = -1;
        }
        if (current != null) {
            try { current.close(); } catch (Throwable ignored) {}
        }
    }

    private void cleanupTempBestEffort() {
        String command = "rm -f " + q(tempBinaryBase + "_root") + " " + q(tempBinaryBase + "_shizuku");
        try {
            if (ShizukuBridge.isReady() && ShizukuBridge.hasPermission()) ShizukuBridge.runShell(command);
        } catch (Throwable ignored) {
        }
        try {
            RootBridge.runShell(command);
        } catch (Throwable ignored) {
        }
    }

    private String binaryPath() {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info.nativeLibraryDir == null) return null;
            return info.nativeLibraryDir + "/libkeyhold.so";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
