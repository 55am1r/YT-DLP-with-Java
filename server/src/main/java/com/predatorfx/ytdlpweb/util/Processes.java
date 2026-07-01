package com.predatorfx.ytdlpweb.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Small helpers around ProcessBuilder. Always prepends Homebrew's bin to PATH so
 * yt-dlp / ffmpeg / brew resolve no matter how the server was launched.
 */
public final class Processes {

    private Processes() {}

    public record Result(int code, String stdout, String stderr) {}

    /** Run a command, capture stdout+stderr separately, with a timeout. */
    public static Result run(List<String> cmd, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        applyPath(pb);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread to = pump(p.getInputStream(), out);
        Thread te = pump(p.getErrorStream(), err);
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            to.join(1000);
            te.join(1000);
            throw new IOException("Command timed out after " + timeout + ": " + String.join(" ", cmd));
        }
        to.join();
        te.join();
        return new Result(p.exitValue(), out.toString(), err.toString());
    }

    /** Run a command streaming each stdout/stderr line to a consumer (for live progress). */
    public static int stream(List<String> cmd, Path cwd, Consumer<String> onLine)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        applyPath(pb);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                onLine.accept(line);
            }
        }
        return p.waitFor();
    }

    private static Thread pump(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // stream closed; nothing to do
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void applyPath(ProcessBuilder pb) {
        var env = pb.environment();
        String brewBin = "/opt/homebrew/bin";
        env.merge("PATH", brewBin, (old, add) -> old.contains(add) ? old : add + ":" + old);
    }
}
