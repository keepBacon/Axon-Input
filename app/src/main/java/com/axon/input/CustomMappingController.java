package com.axon.input;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Executes consuming trigger -> ordered tap sequences through one persistent privileged uinput helper. */
public final class CustomMappingController {
    private static final long VISUAL_TAP_MS = 16L;
    private static final int MAX_PENDING_TASKS = 48;

    public interface Listener {
        void onStartFailed();
        void onOutputPressed(int inputCode);
        void onOutputReleased(int inputCode);
    }

    private interface RemoteProcess extends Closeable {
        InputStream input();
        OutputStream output() throws Exception;
    }

    private static final class Task {
        final CustomMappingStore.Rule rule;
        final int delayMs;
        final long generation;

        Task(CustomMappingStore.Rule rule, int delayMs, long generation) {
            this.rule = rule;
            this.delayMs = delayMs;
            this.generation = generation;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>(MAX_PENDING_TASKS);
    private final Thread worker;

    private boolean destroyed;
    private boolean enabled;
    private boolean starting;
    private boolean ready;
    private int delayMs = CustomMappingStore.DEFAULT_DELAY_MS;
    private long generation;
    private List<CustomMappingStore.Rule> rules = Collections.emptyList();
    private RemoteProcess process;
    private OutputStream output;

    public CustomMappingController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        worker = new Thread(this::workerLoop, "AxonCustomMappingWorker");
        worker.setDaemon(true);
        worker.start();
    }

    public void applyConfiguration(boolean enabled, List<CustomMappingStore.Rule> rules, int delayMs) {
        boolean shouldStart;
        synchronized (lock) {
            if (destroyed) return;
            this.enabled = enabled && rules != null && !rules.isEmpty();
            this.rules = this.enabled ? List.copyOf(rules) : Collections.emptyList();
            this.delayMs = Math.max(CustomMappingStore.DELAY_MIN_MS,
                    Math.min(CustomMappingStore.DELAY_MAX_MS, delayMs));
            if (!this.enabled) {
                generation++;
                queue.clear();
            }
            shouldStart = this.enabled && !ready && !starting;
        }
        if (shouldStart) ensureStartedAsync();
    }

    /** Returns true whenever the physical source belongs to an enabled rule and must be consumed. */
    public boolean dispatch(int inputCode, boolean pressed, boolean firstPress) {
        CustomMappingStore.Rule matched = null;
        int currentDelay;
        long currentGeneration;
        synchronized (lock) {
            if (destroyed || !enabled) return false;
            for (CustomMappingStore.Rule rule : rules) {
                if (rule.triggerInputCode == inputCode) {
                    matched = rule;
                    break;
                }
            }
            if (matched == null) return false;
            currentDelay = delayMs;
            currentGeneration = generation;
        }
        if (pressed && firstPress) {
            Task task = new Task(matched, currentDelay, currentGeneration);
            if (!queue.offer(task)) {
                // 输入速度超过 uinput 输出速度时宁可丢弃最陈旧的待执行 tap，也不能无限积压，
                // 否则用户松手数秒后仍会继续“补按键”，并持续增长内存。
                queue.poll();
                queue.offer(task);
            }
        }
        return true;
    }

    public boolean usesTrigger(int inputCode) {
        synchronized (lock) {
            if (destroyed || !enabled) return false;
            for (CustomMappingStore.Rule rule : rules) {
                if (rule.triggerInputCode == inputCode) return true;
            }
            return false;
        }
    }

    public boolean hasGamepadTrigger() {
        synchronized (lock) {
            if (destroyed || !enabled) return false;
            for (CustomMappingStore.Rule rule : rules) {
                if (InputBinding.isGamepad(rule.triggerInputCode)) return true;
            }
            return false;
        }
    }

    public void releasePending() {
        synchronized (lock) {
            generation++;
            queue.clear();
        }
    }

    public void destroy() {
        synchronized (lock) {
            if (destroyed) return;
            destroyed = true;
            enabled = false;
            generation++;
            queue.clear();
            stopLocked();
        }
        worker.interrupt();
    }

    private void workerLoop() {
        while (true) {
            try {
                Task task = queue.take();
                synchronized (lock) {
                    if (destroyed) return;
                    if (!enabled || task.generation != generation) continue;
                }
                if (!ensureStartedBlocking()) continue;
                List<CustomMappingStore.Output> outputs = task.rule.outputs;
                for (int i = 0; i < outputs.size(); i++) {
                    synchronized (lock) {
                        if (destroyed) return;
                        if (!enabled || task.generation != generation) break;
                    }
                    CustomMappingStore.Output target = outputs.get(i);
                    if (listener != null) listener.onOutputPressed(target.inputCode);
                    boolean sent = sendTap(target);
                    try { Thread.sleep(VISUAL_TAP_MS); }
                    catch (InterruptedException interrupted) {
                        if (listener != null) listener.onOutputReleased(target.inputCode);
                        if (destroyed) return;
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (listener != null) listener.onOutputReleased(target.inputCode);
                    if (!sent) break;
                    if (i + 1 < outputs.size() && task.delayMs > 0) {
                        try { Thread.sleep(task.delayMs); }
                        catch (InterruptedException interrupted) {
                            if (destroyed) return;
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                synchronized (lock) { if (destroyed) return; }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean sendTap(CustomMappingStore.Output target) {
        char kind = InputBinding.isMouse(target.inputCode) ? 'm'
                : (InputBinding.isGamepad(target.inputCode) ? 'g' : 'k');
        String command = "T " + kind + " " + target.evdevCode + "\n";
        synchronized (lock) {
            if (!ready || output == null) return false;
            try {
                output.write(command.getBytes(StandardCharsets.US_ASCII));
                output.flush();
                return true;
            } catch (Throwable ignored) {
                stopLocked();
            }
        }
        if (listener != null) listener.onStartFailed();
        return false;
    }

    private void ensureStartedAsync() {
        synchronized (lock) {
            if (destroyed || !enabled || ready || starting) return;
            starting = true;
        }
        Thread start = new Thread(() -> {
            boolean ok = startProcessAndInstall();
            if (!ok && listener != null) listener.onStartFailed();
        }, "AxonCustomMappingStart");
        start.setDaemon(true);
        start.start();
    }

    private boolean ensureStartedBlocking() {
        synchronized (lock) {
            if (destroyed || !enabled) return false;
            if (ready && output != null) return true;
            if (starting) {
                long deadline = android.os.SystemClock.uptimeMillis() + 1500L;
                while (starting && !ready && !destroyed) {
                    long remain = deadline - android.os.SystemClock.uptimeMillis();
                    if (remain <= 0L) break;
                    try { lock.wait(Math.min(remain, 120L)); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
                if (ready && output != null) return true;
                if (destroyed || !enabled) return false;
            }
            starting = true;
        }
        boolean ok = startProcessAndInstall();
        if (!ok && listener != null) listener.onStartFailed();
        return ok;
    }

    private boolean startProcessAndInstall() {
        RemoteProcess candidate = null;
        BufferedReader reader = null;
        try {
            candidate = startProcess();
            if (candidate == null) throw new IllegalStateException("No privileged input backend");
            reader = new BufferedReader(new InputStreamReader(candidate.input(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (!"READY".equals(line)) throw new IllegalStateException(line == null ? "custom mapper exited" : line);
            OutputStream next = candidate.output();
            synchronized (lock) {
                if (destroyed || !enabled) {
                    try { candidate.close(); } catch (Throwable ignored) {}
                    candidate = null;
                    starting = false;
                    lock.notifyAll();
                    return false;
                }
                stopLocked();
                process = candidate;
                output = next;
                ready = true;
                starting = false;
                candidate = null;
                lock.notifyAll();
                return true;
            }
        } catch (Throwable ignored) {
            synchronized (lock) {
                starting = false;
                ready = false;
                lock.notifyAll();
            }
            return false;
        } finally {
            if (candidate != null) try { candidate.close(); } catch (Throwable ignored) {}
        }
    }

    private RemoteProcess startProcess() throws Exception {
        String source = binaryPath();
        if (source == null) throw new IllegalStateException("custom mapper binary missing");
        String suffix = "_" + android.os.Process.myUid();
        if (SensitivitySettingsStore.getMode(context) == SensitivitySettingsStore.MODE_ROOT
                && RootBridge.isRootActive()) {
            String temp = "/data/local/tmp/axon_input_custom_mapper_" + android.os.Process.myUid() + "_root" + suffix;
            RootBridge.RootProcess root = RootBridge.startShell(startCommand(source, temp));
            return new RemoteProcess() {
                @Override public InputStream input() { return root.getInputStream(); }
                @Override public OutputStream output() { return root.getOutputStream(); }
                @Override public void close() { root.close(); }
            };
        }
        if (ShizukuBridge.isReady() && ShizukuBridge.hasPermission()) {
            String temp = "/data/local/tmp/axon_input_custom_mapper_" + android.os.Process.myUid() + "_shizuku" + suffix;
            ShizukuBridge.ShellProcess shizuku = ShizukuBridge.startShell(startCommand(source, temp));
            return new RemoteProcess() {
                @Override public InputStream input() { return shizuku.getInputStream(); }
                @Override public OutputStream output() throws Exception { return shizuku.getOutputStream(); }
                @Override public void close() { shizuku.close(); }
            };
        }
        throw new SecurityException("No privileged input channel");
    }

    private String startCommand(String source, String temp) {
        return "rm -f " + q(temp)
                + "; cat " + q(source) + " > " + q(temp)
                + " && chmod 700 " + q(temp)
                + " && exec " + q(temp) + " 2>&1";
    }

    private String binaryPath() {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info == null || info.nativeLibraryDir == null) return null;
            return info.nativeLibraryDir + "/libcustommapper.so";
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
