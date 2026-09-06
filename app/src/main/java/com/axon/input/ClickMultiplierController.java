package com.axon.input;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * 点击倍率输入状态机。
 *
 * 设计要点：
 * 1. Java 只负责识别“开关按键 / 倍率按键”的物理按下沿，不再逐次 sleep + 写 uinput。
 * 2. 每个配置只启动一个“目标类型 + 目标 evdev code”的专用 Native uinput，避免一个虚拟键盘
 *    同时声明鼠标/手柄键导致 Android InputReader 分类错误。
 * 3. 一次物理点击只向 Native 发送一条 Burst 指令，重复点击、按键保持时间和间隔全部由 Native
 *    单调时钟调度；即使 Java 主线程繁忙，也不会把倍率点击拖散。
 * 4. Native 每成功输出一次会回报 TAP，供 Axon 自己的按显做补充反馈；Axon 虚拟设备仍由
 *    InputBinding / MouseInputMonitor / gamepad monitor 过滤，因此不会递归触发倍率。
 */
public final class ClickMultiplierController {
    private static final String TAG = "AxonClickMultiplier";
    public interface Listener {
        void onStartFailed();
        void onActiveChanged(boolean active);
        void onSyntheticTap(int inputCode);
    }

    private interface RemoteProcess extends Closeable {
        InputStream input();
        OutputStream output() throws Exception;
    }

    private static final int MAX_PENDING_BURSTS = 32;

    private static final class Burst {
        final int extraCount;
        final int delayMs;
        final long generation;

        Burst(int extraCount, int delayMs, long generation) {
            this.extraCount = extraCount;
            this.delayMs = delayMs;
            this.generation = generation;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private final ArrayDeque<Burst> pendingBursts = new ArrayDeque<>();

    private boolean destroyed;
    private boolean enabled;
    private boolean active;
    private boolean starting;
    private boolean ready;
    private int toggleInputCode = -1;
    private int targetInputCode = -1;
    private int targetEvdevCode = -1;
    private int multiplier = ClickMultiplierStore.DEFAULT_MULTIPLIER;
    private int delayMs = ClickMultiplierStore.DEFAULT_DELAY_MS;
    private long generation;
    /** 只在目标设备/绑定发生变化时递增，避免开关状态变化误杀正在启动的 Native helper。 */
    private long backendGeneration;
    private RemoteProcess process;
    private OutputStream output;

    public ClickMultiplierController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void applyConfiguration(boolean enabled, int toggleInputCode, int targetInputCode,
                                   int targetEvdevCode, int multiplier, int delayMs) {
        boolean shouldStart = false;
        boolean notifyInactive = false;
        synchronized (lock) {
            if (destroyed) return;
            boolean valid = enabled && InputBinding.isValid(toggleInputCode)
                    && InputBinding.isValid(targetInputCode)
                    && toggleInputCode != targetInputCode
                    && targetEvdevCode > 0 && targetEvdevCode <= 0x2ff;
            boolean bindingChanged = this.toggleInputCode != toggleInputCode
                    || this.targetInputCode != targetInputCode
                    || this.targetEvdevCode != targetEvdevCode;

            this.enabled = valid;
            this.toggleInputCode = valid ? toggleInputCode : -1;
            this.targetInputCode = valid ? targetInputCode : -1;
            this.targetEvdevCode = valid ? targetEvdevCode : -1;
            this.multiplier = Math.max(ClickMultiplierStore.MULTIPLIER_MIN,
                    Math.min(ClickMultiplierStore.MULTIPLIER_MAX, multiplier));
            this.delayMs = Math.max(ClickMultiplierStore.DELAY_MIN_MS,
                    Math.min(ClickMultiplierStore.DELAY_MAX_MS, delayMs));

            if (!valid || bindingChanged) {
                generation++;
                backendGeneration++;
                pendingBursts.clear();
                if (active) notifyInactive = true;
                active = false;
                // Native uinput 的设备类别和目标 code 在启动时固定；重绑必须重建，不能复用旧设备。
                if (ready || starting || process != null) {
                    cancelNativeLocked();
                    stopLocked();
                    starting = false;
                }
            }

            if (!valid) {
                cancelNativeLocked();
                stopLocked();
                starting = false;
            } else if (!ready && !starting) {
                shouldStart = true;
            }
        }
        if (notifyInactive && listener != null) listener.onActiveChanged(false);
        if (shouldStart) ensureStartedAsync();
    }

    /**
     * 统一物理按下沿入口。
     * @return true 仅表示“开关快捷键”需要被上层消费；倍率目标键本身保持原始输入，再补 multiplier-1 次。
     */
    public boolean dispatch(int inputCode, boolean pressed, boolean firstPress) {
        boolean toggled = false;
        boolean nextActive = false;
        boolean shouldStart = false;

        synchronized (lock) {
            if (destroyed || !enabled) return false;

            if (inputCode == toggleInputCode) {
                if (pressed && firstPress) {
                    active = !active;
                    generation++;
                    pendingBursts.clear();
                    if (!active) cancelNativeLocked();
                    toggled = true;
                    nextActive = active;
                    if (active && !ready && !starting) shouldStart = true;
                }
            } else if (inputCode == targetInputCode && active && pressed && firstPress) {
                int extra = Math.max(0, multiplier - 1);
                if (extra > 0) {
                    Burst burst = new Burst(extra, delayMs, generation);
                    if (!sendBurstLocked(burst)) {
                        // Helper 正在启动或刚刚异常退出时不丢第一次点击；READY 后立即补发。
                        if (pendingBursts.size() >= MAX_PENDING_BURSTS) pendingBursts.removeFirst();
                        pendingBursts.addLast(burst);
                        if (!starting) shouldStart = true;
                    }
                }
            }
        }

        if (toggled && listener != null) listener.onActiveChanged(nextActive);
        if (shouldStart) ensureStartedAsync();
        return inputCode == toggleInputCode;
    }

    public boolean isToggleInput(int inputCode) {
        synchronized (lock) {
            return !destroyed && enabled && toggleInputCode == inputCode;
        }
    }

    public boolean hasGamepadInput() {
        synchronized (lock) {
            return !destroyed && enabled
                    && (InputBinding.isGamepad(toggleInputCode) || InputBinding.isGamepad(targetInputCode));
        }
    }

    public boolean isActive() {
        synchronized (lock) {
            return !destroyed && enabled && active;
        }
    }

    public void releasePendingAndDeactivate() {
        boolean notify = false;
        synchronized (lock) {
            generation++;
            pendingBursts.clear();
            cancelNativeLocked();
            if (active) notify = true;
            active = false;
        }
        if (notify && listener != null) listener.onActiveChanged(false);
    }

    public void destroy() {
        boolean notify = false;
        synchronized (lock) {
            if (destroyed) return;
            destroyed = true;
            enabled = false;
            generation++;
            backendGeneration++;
            pendingBursts.clear();
            cancelNativeLocked();
            if (active) notify = true;
            active = false;
            stopLocked();
            starting = false;
        }
        if (notify && listener != null) listener.onActiveChanged(false);
    }

    /** 一次 Java 写入对应整组额外点击；真正的节拍在 Native 内完成。 */
    private boolean sendBurstLocked(Burst burst) {
        if (burst == null || burst.extraCount <= 0 || burst.generation != generation) return true;
        if (!ready || output == null) return false;
        String command = "B " + burst.extraCount + " " + burst.delayMs + "\n";
        try {
            output.write(command.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return true;
        } catch (Throwable ignored) {
            stopLocked();
            starting = false;
            return false;
        }
    }

    private void cancelNativeLocked() {
        if (!ready || output == null) return;
        try {
            output.write("C\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Throwable ignored) {
            stopLocked();
            starting = false;
        }
    }

    private void flushPendingLocked() {
        while (ready && output != null && !pendingBursts.isEmpty()) {
            Burst burst = pendingBursts.peekFirst();
            if (burst.generation != generation || !enabled || !active) {
                pendingBursts.removeFirst();
                continue;
            }
            if (!sendBurstLocked(burst)) return;
            pendingBursts.removeFirst();
        }
    }

    private void ensureStartedAsync() {
        final long startGeneration;
        synchronized (lock) {
            if (destroyed || !enabled || ready || starting) return;
            starting = true;
            startGeneration = backendGeneration;
        }

        Thread start = new Thread(() -> startProcessAndInstall(startGeneration), "AxonClickMultiplierStart");
        start.setDaemon(true);
        start.start();
    }

    private void startProcessAndInstall(long startGeneration) {
        RemoteProcess candidate = null;
        BufferedReader reader = null;
        boolean failed = false;
        try {
            candidate = startProcess();
            if (candidate == null) throw new IllegalStateException("No privileged input backend");
            reader = new BufferedReader(new InputStreamReader(candidate.input(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || !line.startsWith("READY")) {
                throw new IllegalStateException(line == null ? "click multiplier exited" : line);
            }
            OutputStream next = candidate.output();

            RemoteProcess installed;
            BufferedReader installedReader;
            synchronized (lock) {
                if (destroyed || !enabled || startGeneration != backendGeneration) {
                    try { candidate.close(); } catch (Throwable ignored) {}
                    candidate = null;
                    starting = false;
                    return;
                }
                stopLocked();
                process = candidate;
                output = next;
                ready = true;
                starting = false;
                installed = candidate;
                installedReader = reader;
                candidate = null;
                reader = null;
                flushPendingLocked();
            }
            watchProcess(installed, installedReader);
            return;
        } catch (Throwable error) {
            Log.e(TAG, "Click multiplier backend start failed", error);
            synchronized (lock) {
                if (startGeneration == backendGeneration) {
                    starting = false;
                    ready = false;
                    failed = enabled && !destroyed;
                }
            }
        } finally {
            if (candidate != null) try { candidate.close(); } catch (Throwable ignored) {}
            if (reader != null) try { reader.close(); } catch (Throwable ignored) {}
        }
        if (failed && listener != null) listener.onStartFailed();
    }

    /**
     * Native helper 的 stdout 同时承担健康检查和补充按显回执。
     * 进程退出只重启一次；若权限本身失效，下一次配置刷新/按键会再次尝试，不做无限重启循环。
     */
    private void watchProcess(RemoteProcess watched, BufferedReader reader) {
        Thread watcher = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("TAP".equals(line) && listener != null) {
                        int target;
                        synchronized (lock) {
                            if (process != watched || destroyed) continue;
                            target = targetInputCode;
                        }
                        if (target >= 0) listener.onSyntheticTap(target);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { reader.close(); } catch (Throwable ignored) {}
            }

            boolean restart = false;
            synchronized (lock) {
                if (process != watched) return;
                process = null;
                output = null;
                ready = false;
                starting = false;
                restart = enabled && !destroyed;
            }
            if (restart) ensureStartedAsync();
        }, "AxonClickMultiplierWatch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private RemoteProcess startProcess() throws Exception {
        String source = binaryPath();
        if (source == null) throw new IllegalStateException("click multiplier binary missing");

        final int target;
        final int evdev;
        synchronized (lock) {
            target = targetInputCode;
            evdev = targetEvdevCode;
        }
        if (!InputBinding.isValid(target) || evdev <= 0 || evdev > 0x2ff) {
            throw new IllegalStateException("invalid click multiplier target");
        }

        String kind = InputBinding.uinputKind(target);
        String suffix = "_" + kind + "_" + evdev;
        if (SensitivitySettingsStore.getMode(context) == SensitivitySettingsStore.MODE_ROOT
                && RootBridge.isRootActive()) {
            String temp = "/data/local/tmp/axon_input_click_multiplier_" + android.os.Process.myUid()
                    + "_root" + suffix;
            RootBridge.RootProcess root = RootBridge.startShell(startCommand(source, temp, kind, evdev));
            return new RemoteProcess() {
                @Override public InputStream input() { return root.getInputStream(); }
                @Override public OutputStream output() { return root.getOutputStream(); }
                @Override public void close() { root.close(); }
            };
        }
        if (ShizukuBridge.isReady() && ShizukuBridge.hasPermission()) {
            String temp = "/data/local/tmp/axon_input_click_multiplier_" + android.os.Process.myUid()
                    + "_shizuku" + suffix;
            ShizukuBridge.ShellProcess shizuku = ShizukuBridge.startShell(
                    startCommand(source, temp, kind, evdev));
            return new RemoteProcess() {
                @Override public InputStream input() { return shizuku.getInputStream(); }
                @Override public OutputStream output() throws Exception { return shizuku.getOutputStream(); }
                @Override public void close() { shizuku.close(); }
            };
        }
        throw new SecurityException("No privileged input channel");
    }

    private String startCommand(String source, String temp, String kind, int code) {
        return "rm -f " + q(temp)
                + "; cat " + q(source) + " > " + q(temp)
                + " && chmod 700 " + q(temp)
                + " && exec " + q(temp)
                + " --kind " + q(kind) + " --code " + code + " 2>&1";
    }

    private String binaryPath() {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info == null || info.nativeLibraryDir == null) return null;
            java.io.File binary = new java.io.File(info.nativeLibraryDir, "libclickmultiplier.so");
            if (!binary.isFile() || !binary.canRead()) {
                Log.e(TAG, "Click multiplier binary is missing from APK: " + binary.getAbsolutePath());
                return null;
            }
            return binary.getAbsolutePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void stopLocked() {
        ready = false;
        output = null;
        RemoteProcess current = process;
        process = null;
        if (current != null) try { current.close(); } catch (Throwable ignored) {}
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
