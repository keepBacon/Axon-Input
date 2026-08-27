package com.axon.input;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consumes mapped gamepad KeyEvents in AccessibilityService and re-emits keyboard keys through
 * a privileged uinput keyboard. The physical event is only consumed after the uinput backend is ready.
 */
public final class GamepadKeyMapper {
    private interface RemoteProcess extends Closeable {
        InputStream input();
        OutputStream output() throws Exception;
    }

    private final Context context;
    private final Object lock = new Object();
    private final Map<Integer, Integer> targetKeyBySource = new HashMap<>();
    private final Map<Integer, Integer> targetEvdevBySource = new HashMap<>();
    // Preserve a well-formed source stream: once a DOWN is either mapped or passed through,
    // every repeat/UP for that physical press follows the same decision.
    private final Set<Integer> mappedUntilUp = new HashSet<>();
    private final Set<Integer> passthroughUntilUp = new HashSet<>();
    private final Map<Integer, Integer> activeEvdevBySource = new HashMap<>();
    private final Map<Integer, Integer> targetHoldCounts = new HashMap<>();
    private RemoteProcess process;
    private OutputStream output;
    private boolean ready;
    private boolean starting;
    private boolean destroyed;

    public GamepadKeyMapper(Context context) {
        this.context = context.getApplicationContext();
    }

    public void applyMappings(List<GamepadMappingStore.Mapping> mappings) {
        synchronized (lock) {
            targetKeyBySource.clear();
            targetEvdevBySource.clear();
            if (mappings != null) {
                for (GamepadMappingStore.Mapping mapping : mappings) {
                    int evdev = GamepadMappingStore.keyboardEvdevCode(mapping.targetKeyCode);
                    if (!InputBinding.isGamepad(mapping.sourceInputCode) || evdev <= 0) continue;
                    targetKeyBySource.put(mapping.sourceInputCode, mapping.targetKeyCode);
                    targetEvdevBySource.put(mapping.sourceInputCode, evdev);
                }
            }
            if (targetKeyBySource.isEmpty()) {
                stopLocked();
                return;
            }
        }
        ensureStartedAsync();
    }

    public boolean hasMapping(int sourceInputCode) {
        synchronized (lock) {
            return targetKeyBySource.containsKey(sourceInputCode);
        }
    }

    /**
     * Returns true when the original gamepad event must be consumed. A complete physical press is
     * kept on one path (mapped or pass-through) so apps never receive DOWN without UP or vice versa.
     */
    public boolean dispatch(int sourceInputCode, boolean pressed, boolean firstPress, boolean repeat) {
        synchronized (lock) {
            if (pressed && !firstPress) {
                if (mappedUntilUp.contains(sourceInputCode)) return true;
                if (passthroughUntilUp.contains(sourceInputCode)) return false;
                // We missed the initial DOWN (for example the service attached mid-press). Do not
                // begin remapping halfway through an input stream.
                passthroughUntilUp.add(sourceInputCode);
                return false;
            }

            if (!pressed) {
                if (passthroughUntilUp.remove(sourceInputCode)) return false;
                if (!mappedUntilUp.remove(sourceInputCode)) return false;
                Integer code = activeEvdevBySource.remove(sourceInputCode);
                if (code != null) {
                    int remaining = Math.max(0, targetHoldCounts.getOrDefault(code, 1) - 1);
                    if (remaining > 0) {
                        targetHoldCounts.put(code, remaining);
                    } else {
                        targetHoldCounts.remove(code);
                        if (ready && output != null) {
                            try {
                                output.write(("U " + code + "\n").getBytes(StandardCharsets.US_ASCII));
                                output.flush();
                            } catch (Throwable ignored) {
                                // The helper releases every held virtual key when it exits. Never
                                // leak the original gamepad UP after its DOWN was already consumed.
                                stopLocked();
                            }
                        }
                    }
                }
                return true;
            }

            Integer code = targetEvdevBySource.get(sourceInputCode);
            if (code == null) return false;
            if (!ready || output == null) {
                passthroughUntilUp.add(sourceInputCode);
                ensureStartedAsync();
                return false;
            }
            try {
                int heldCount = targetHoldCounts.getOrDefault(code, 0);
                if (heldCount == 0) {
                    output.write(("D " + code + "\n").getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                }
                targetHoldCounts.put(code, heldCount + 1);
                activeEvdevBySource.put(sourceInputCode, code);
                mappedUntilUp.add(sourceInputCode);
                passthroughUntilUp.remove(sourceInputCode);
                return true;
            } catch (Throwable ignored) {
                passthroughUntilUp.add(sourceInputCode);
                stopLocked();
            }
        }
        ensureStartedAsync();
        return false;
    }

    public boolean isPressEngaged(int sourceInputCode) {
        synchronized (lock) {
            return mappedUntilUp.contains(sourceInputCode)
                    || passthroughUntilUp.contains(sourceInputCode);
        }
    }

    public void retryBackend() {
        synchronized (lock) {
            if (destroyed || targetKeyBySource.isEmpty() || ready || starting) return;
        }
        ensureStartedAsync();
    }

    public void destroy() {
        synchronized (lock) {
            destroyed = true;
            targetKeyBySource.clear();
            targetEvdevBySource.clear();
            mappedUntilUp.clear();
            passthroughUntilUp.clear();
            activeEvdevBySource.clear();
            targetHoldCounts.clear();
            stopLocked();
        }
    }

    private void ensureStartedAsync() {
        synchronized (lock) {
            if (destroyed || targetKeyBySource.isEmpty() || ready || starting) return;
            starting = true;
        }
        Thread worker = new Thread(() -> {
            RemoteProcess candidate = null;
            try {
                candidate = startProcess();
                if (candidate == null) throw new IllegalStateException("No privileged input backend");
                BufferedReader reader = new BufferedReader(new InputStreamReader(candidate.input(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (!"READY".equals(line)) throw new IllegalStateException(line == null ? "mapper exited" : line);
                OutputStream nextOutput = candidate.output();
                synchronized (lock) {
                    if (destroyed || targetKeyBySource.isEmpty()) {
                        try { candidate.close(); } catch (Throwable ignored) {}
                        starting = false;
                        return;
                    }
                    stopLocked();
                    process = candidate;
                    output = nextOutput;
                    ready = true;
                    starting = false;
                    candidate = null;
                }
            } catch (Throwable ignored) {
                synchronized (lock) {
                    starting = false;
                    ready = false;
                }
            } finally {
                if (candidate != null) {
                    try { candidate.close(); } catch (Throwable ignored) {}
                }
            }
        }, "AxonGamepadKeyMapperStart");
        worker.setDaemon(true);
        worker.start();
    }

    private RemoteProcess startProcess() throws Exception {
        String source = binaryPath();
        if (source == null) throw new IllegalStateException("mapper binary missing");
        if (SensitivitySettingsStore.getMode(context) == SensitivitySettingsStore.MODE_ROOT
                && RootBridge.isRootActive()) {
            String temp = "/data/local/tmp/axon_input_gamepad_mapper_"
                    + android.os.Process.myUid() + "_root";
            RootBridge.RootProcess root = RootBridge.startShell(startCommand(source, temp));
            return new RemoteProcess() {
                @Override public InputStream input() { return root.getInputStream(); }
                @Override public OutputStream output() { return root.getOutputStream(); }
                @Override public void close() { root.close(); }
            };
        }
        if (ShizukuBridge.isReady() && ShizukuBridge.hasPermission()) {
            String temp = "/data/local/tmp/axon_input_gamepad_mapper_"
                    + android.os.Process.myUid() + "_shizuku";
            ShizukuBridge.ShellProcess shizuku = ShizukuBridge.startShell(startCommand(source, temp));
            return new RemoteProcess() {
                @Override public InputStream input() { return shizuku.getInputStream(); }
                @Override public OutputStream output() throws Exception { return shizuku.getOutputStream(); }
                @Override public void close() { shizuku.close(); }
            };
        }
        return null;
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
            if (info.nativeLibraryDir == null) return null;
            return info.nativeLibraryDir + "/libkeymapper.so";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void stopLocked() {
        ready = false;
        output = null;
        // Closing the helper releases all currently held virtual keys. Keep mappedUntilUp so the
        // corresponding physical UP is still swallowed, but forget virtual hold bookkeeping.
        activeEvdevBySource.clear();
        targetHoldCounts.clear();
        RemoteProcess current = process;
        process = null;
        if (current != null) {
            try { current.close(); } catch (Throwable ignored) {}
        }
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
