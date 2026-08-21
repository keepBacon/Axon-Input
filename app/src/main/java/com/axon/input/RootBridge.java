package com.axon.input;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Root 灵敏度超频模式使用的最小 su 命令通道。 */
public final class RootBridge {
    private RootBridge() {}

    public static final class RootProcess implements Closeable {
        private final Process process;

        RootProcess(Process process) {
            this.process = process;
        }

        public InputStream getInputStream() {
            return process.getInputStream();
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
                // Drain output so root managers / shells cannot block on a full pipe.
            }
        }
        return process.waitFor();
    }
}
